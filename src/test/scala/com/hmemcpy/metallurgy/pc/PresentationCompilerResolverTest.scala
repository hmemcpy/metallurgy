package com.hmemcpy.metallurgy.pc

import org.junit.Assert.{assertEquals, assertSame, assertTrue}
import org.junit.Test

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

final class PresentationCompilerResolverTest:

  @Test
  def stableRcNightlyAndVendorCoordinatesUseTheSameExactPath(): Unit =
    val artifact     = Path.of("compiler.jar")
    val fetcher      = RecordingArtifactFetcher(Seq(artifact))
    val repositories = Seq("https://example.test/releases", "https://example.test/experiments")
    val resolver     = new CoursierPresentationCompilerResolver(fetcher, () => repositories)
    val versions     = Seq(
      "3.7.4",
      "3.8.0-RC1",
      "3.9.0-RC1-bin-20260722-deadbeef-NIGHTLY",
      "3.7.4-bin-vendor"
    )

    versions.foreach(version => assertEquals(version, Right(Seq(artifact)), resolver.resolve(version)))
    assertEquals(versions.map(ArtifactFetch(_, repositories)), fetcher.requests)

  @Test
  def mapsPublicResolverFailureToTypedResolutionError(): Unit =
    val failure  = new IllegalStateException("unavailable")
    val resolver = new CoursierPresentationCompilerResolver(FailingArtifactFetcher(failure), () => Seq.empty)

    resolver.resolve("3.9.0-experimental") match
      case Left(ArtifactResolutionError.DependencyResolution(version, cause)) =>
        assertEquals("3.9.0-experimental", version)
        assertSame(failure, cause)
      case result                                                             =>
        throw new AssertionError(s"expected dependency-resolution failure, got $result")

  @Test
  def configuredRepositoriesAreAdditiveForEveryCompilerCoordinate(): Unit =
    val repositories = CoursierPresentationCompilerResolver.repositoriesFrom(
      Some(" https://vendor.test/maven,https://repo.scala-lang.org/artifactory/maven-nightlies ")
    )

    assertTrue(repositories.contains("https://central.sonatype.com/repository/maven-snapshots/"))
    assertTrue(repositories.contains("https://repo.scala-lang.org/artifactory/maven-nightlies"))
    assertTrue(repositories.contains("https://vendor.test/maven"))
    assertEquals(repositories.distinct, repositories)

private final case class ArtifactFetch(scalaVersion: String, repositories: Seq[String])

private final class RecordingArtifactFetcher(artifacts: Seq[Path]) extends PresentationCompilerArtifactFetcher:
  private val recordedRequests = new AtomicReference(Vector.empty[ArtifactFetch])

  def requests: Seq[ArtifactFetch] = recordedRequests.get()

  override def fetch(scalaVersion: String, additionalRepositories: Seq[String]): Seq[Path] =
    val _ = recordedRequests.updateAndGet(_ :+ ArtifactFetch(scalaVersion, additionalRepositories))
    artifacts

private final class FailingArtifactFetcher(failure: RuntimeException) extends PresentationCompilerArtifactFetcher:
  override def fetch(scalaVersion: String, additionalRepositories: Seq[String]): Seq[Path] =
    throw failure
