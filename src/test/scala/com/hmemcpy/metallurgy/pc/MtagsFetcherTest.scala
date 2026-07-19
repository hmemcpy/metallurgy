package com.hmemcpy.metallurgy.pc

import org.junit.Assert.{assertArrayEquals, assertEquals, assertFalse, assertSame, assertTrue}
import org.junit.Test

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}

final class MtagsFetcherTest:

  @Test
  def cacheHitDoesNotResolveAgain(): Unit =
    withTempDirectory: cacheRoot =>
      val artifact = writeArtifact(cacheRoot, "pc.jar", "presentation compiler")
      val resolver = RecordingResolver(Right(Seq(artifact)))
      val fetcher  = testFetcher(cacheRoot.resolve("cache"), resolver)

      val first  = fetcher.jarsFor("3.5.2").get(5, TimeUnit.SECONDS)
      val second = fetcher.jarsFor("3.5.2").get(5, TimeUnit.SECONDS)

      assertEquals(first, second)
      assertEquals(1, resolver.calls.get())
      assertTrue(fetcher.jarsIfCached("3.5.2").contains(first))

  @Test
  def concurrentRequestsShareOneResolution(): Unit =
    withTempDirectory: cacheRoot =>
      val artifact = writeArtifact(cacheRoot, "pc.jar", "presentation compiler")
      val entered  = new CountDownLatch(1)
      val release  = new CountDownLatch(1)
      val calls    = new AtomicInteger()
      val resolver = new PresentationCompilerResolver:
        override def resolve(scalaVersion: String): Either[ArtifactResolutionError, Seq[Path]] =
          calls.incrementAndGet()
          entered.countDown()
          release.await(5, TimeUnit.SECONDS)
          Right(Seq(artifact))
      val executor = Executors.newFixedThreadPool(2)
      val fetcher  = new MtagsFetcher(
        PcArtifactCache(cacheRoot.resolve("cache")),
        resolver,
        BackgroundRunner.fromExecutor(executor)
      )

      try
        val first  = fetcher.jarsFor("3.5.2")
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val second = fetcher.jarsFor("3.5.2")
        assertSame(first, second)
        release.countDown()
        first.get(5, TimeUnit.SECONDS)
        assertEquals(1, calls.get())
      finally
        release.countDown()
        executor.shutdownNow()

  @Test
  def corruptCachedArtifactIsResolvedAgain(): Unit =
    withTempDirectory: cacheRoot =>
      val firstArtifact  = writeArtifact(cacheRoot, "first.jar", "first")
      val secondArtifact = writeArtifact(cacheRoot, "second.jar", "second")
      val calls          = new AtomicInteger()
      val resolver       = new PresentationCompilerResolver:
        override def resolve(scalaVersion: String): Either[ArtifactResolutionError, Seq[Path]] =
          val artifacts = if calls.getAndIncrement() == 0 then Seq(firstArtifact) else Seq(secondArtifact)
          Right(artifacts)
      val cache          = PcArtifactCache(cacheRoot.resolve("cache"))
      val fetcher        = new MtagsFetcher(cache, resolver, BackgroundRunner.direct)

      val directory = fetcher.jarsFor("3.5.2").get(5, TimeUnit.SECONDS)
      Files.writeString(directory.resolve("first.jar"), "corrupt", StandardCharsets.UTF_8)
      assertFalse(fetcher.jarsIfCached("3.5.2").isDefined)

      val refreshed = fetcher.jarsFor("3.5.2").get(5, TimeUnit.SECONDS)
      assertEquals(2, calls.get())
      assertTrue(Files.exists(refreshed.resolve("second.jar")))

  @Test
  def cacheManifestPreservesArtifactBytes(): Unit =
    withTempDirectory: cacheRoot =>
      val expected = "presentation compiler".getBytes(StandardCharsets.UTF_8)
      val artifact = cacheRoot.resolve("pc.jar")
      Files.write(artifact, expected)
      val cache    = PcArtifactCache(cacheRoot.resolve("cache"))

      val directory = cache.store("3.5.2", Seq(artifact)).fold(error => throw error.toException, identity)

      assertArrayEquals(expected, Files.readAllBytes(directory.resolve("pc.jar")))
      assertTrue(cache.validDirectory("3.5.2").contains(directory))

  private def testFetcher(cacheRoot: Path, resolver: PresentationCompilerResolver): MtagsFetcher =
    new MtagsFetcher(PcArtifactCache(cacheRoot), resolver, BackgroundRunner.direct)

  private def writeArtifact(directory: Path, fileName: String, contents: String): Path =
    val path = directory.resolve(fileName)
    Files.writeString(path, contents, StandardCharsets.UTF_8)
    path

  private def withTempDirectory(test: Path => Unit): Unit =
    val directory = Files.createTempDirectory("metallurgy-mtags-test")
    try test(directory)
    finally deleteRecursively(directory)

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      val stream = Files.walk(path)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      finally stream.close()

private final case class RecordingResolver(
    result: Either[ArtifactResolutionError, Seq[Path]],
    calls: AtomicInteger = new AtomicInteger()
) extends PresentationCompilerResolver:
  override def resolve(scalaVersion: String): Either[ArtifactResolutionError, Seq[Path]] =
    calls.incrementAndGet()
    result
