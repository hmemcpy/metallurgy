package com.hmemcpy.metallurgy.pc

import org.junit.Assert.{assertEquals, assertSame}
import org.junit.Test

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

final class Scala3CompilerResolverTest:

  @Test
  def everyExactCompilerCoordinateUsesTheIndependentArtifactPath(): Unit =
    val artifact     = Path.of("scala3-compiler.jar")
    val fetcher      = RecordingCompilerArtifactFetcher(Seq(artifact))
    val repositories = Seq("https://example.test/releases", "https://example.test/experiments")
    val resolver     = new CoursierScala3CompilerResolver(fetcher, () => repositories)
    val versions     = Seq("3.0.2", "3.3.1", "3.7.4", "3.10.0-RC1-bin-example-NIGHTLY")

    versions.foreach(version => assertEquals(version, Right(Seq(artifact)), resolver.resolve(version)))
    assertEquals(versions.map(CompilerArtifactFetch(_, repositories)), fetcher.requests)

  @Test
  def compilerResolutionFailureRetainsTheExactCoordinateAndCause(): Unit =
    val failure  = new IllegalStateException("unavailable")
    val resolver = new CoursierScala3CompilerResolver(FailingCompilerArtifactFetcher(failure), () => Seq.empty)

    resolver.resolve("3.0.2") match
      case Left(Scala3CompilerResolutionError(version, cause)) =>
        assertEquals("3.0.2", version)
        assertSame(failure, cause)
      case result                                              =>
        throw new AssertionError(s"expected exact compiler resolution failure, got $result")

private final case class CompilerArtifactFetch(scalaVersion: String, repositories: Seq[String])

private final class RecordingCompilerArtifactFetcher(artifacts: Seq[Path]) extends Scala3CompilerArtifactFetcher:
  private val recordedRequests = new AtomicReference(Vector.empty[CompilerArtifactFetch])

  def requests: Seq[CompilerArtifactFetch] = recordedRequests.get()

  override def fetch(scalaVersion: String, additionalRepositories: Seq[String]): Seq[Path] =
    val _ = recordedRequests.updateAndGet(_ :+ CompilerArtifactFetch(scalaVersion, additionalRepositories))
    artifacts

private final class FailingCompilerArtifactFetcher(failure: RuntimeException) extends Scala3CompilerArtifactFetcher:
  override def fetch(scalaVersion: String, additionalRepositories: Seq[String]): Seq[Path] =
    throw failure
