package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.Assert.{assertEquals, assertFalse, assertNotSame, assertSame, assertThrows, assertTrue}
import org.junit.Test
import scala.meta.pc.PresentationCompiler

import java.net.URI
import java.nio.file.Path
import java.util.concurrent.{CancellationException, CompletableFuture}
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

final class PcClassLoaderTest:

  @Test
  def publishedBoundaryClassesAreShared(): Unit =
    Seq(
      "javax.swing.JPanel",
      "scala.meta.pc.PresentationCompiler",
      "org.eclipse.lsp4j.CompletionItem",
      "com.google.gson.JsonElement"
    ).foreach(className => assertTrue(className, PcClassLoader.isSharedApi(className)))

  @Test
  def implementationAndHostClassesAreNotShared(): Unit =
    Seq(
      "dotty.tools.pc.ScalaPresentationCompiler",
      "dotty.tools.dotc.interactive.InteractiveDriver",
      "scala.Option",
      "com.intellij.openapi.project.Project",
      "com.hmemcpy.metallurgy.pc.PcSession"
    ).foreach(className => assertFalse(className, PcClassLoader.isSharedApi(className)))

  @Test
  def hostImplementationClassesCannotLeakIntoCompilerLoader(): Unit =
    val loader = new PcClassLoader(Array.empty, getClass.getClassLoader)
    try
      val _ = assertThrows(
        classOf[ClassNotFoundException],
        () =>
          val _ = loader.loadClass(classOf[PcSession].getName)
      )
    finally loader.close()

  @Test
  def publishedApiClassesRetainHostIdentity(): Unit =
    val loader = new PcClassLoader(Array.empty, getClass.getClassLoader)
    try assertSame(classOf[scala.meta.pc.PresentationCompiler], loader.loadClass("scala.meta.pc.PresentationCompiler"))
    finally loader.close()

  @Test
  def scalaRuntimeHasIndependentIdentityInEveryCompilerLoader(): Unit =
    val scalaLibrary = classOf[scala.Option[?]].getProtectionDomain.getCodeSource.getLocation
    val first        = new PcClassLoader(Array(scalaLibrary), getClass.getClassLoader)
    val second       = new PcClassLoader(Array(scalaLibrary), getClass.getClassLoader)
    try
      val hostOption   = classOf[scala.Option[?]]
      val firstOption  = first.loadClass("scala.Option")
      val secondOption = second.loadClass("scala.Option")

      assertNotSame(hostOption, firstOption)
      assertNotSame(hostOption, secondOption)
      assertNotSame(firstOption, secondOption)
      assertSame(first, firstOption.getClassLoader)
      assertSame(second, secondOption.getClassLoader)
    finally
      first.close()
      second.close()

  @Test
  def providerIsDiscoveredFromTheExactArtifactWithoutHostMetadata(): Unit =
    val loader    = compilerLoader("3.7.4")
    val prototype = discoverCompiler(loader, "3.7.4")
    try assertSame(loader, prototype.getClass.getClassLoader)
    finally
      assertTrue(Scala3PcBridge.shutdown(prototype).isRight)
      loader.close()

  @Test
  def presentationCompilerJobsQuiesceBeforeTheExactLoaderCloses(): Unit =
    val loader   = compilerLoader("3.7.4")
    val compiler = newCompiler(loader, "3.7.4")
    val request  = compiler.semanticdbTextDocument(URI.create("file:///Shutdown.scala"), "object Shutdown")
    try
      val executor = Scala3PcBridge.captureExecutor(compiler).toOption.get
      assertTrue(Scala3PcBridge.shutdown(compiler).isRight)
      assertTrue(Scala3PcBridge.awaitTermination(executor, java.util.concurrent.TimeUnit.SECONDS.toNanos(30)).isRight)
      assertTrue(request.isDone)
    finally loader.close()

  @Test
  def canceledRetypecheckSettlesItsFutureAndCleansPendingState(): Unit =
    val result   = new CompletableFuture[RetypecheckOutcome]()
    val cleaned  = new AtomicBoolean(false)
    val canceled = new ProcessCanceledException()

    PcSession.settleRetypecheck(result, cleaned.set(true)):
      throw canceled

    assertTrue(result.isCompletedExceptionally)
    assertTrue(cleaned.get())
    val completion = assertThrows(
      classOf[CancellationException],
      () =>
        val _ = result.join()
    )
    assertSame(canceled, completion.getCause)

  @Test
  def exactCompilerVersionsCanCoexistBehindTheSharedApi(): Unit =
    val first  = compilerLoader("3.5.2")
    val second = compilerLoader("3.7.4")
    try
      val firstCompiler  = newCompiler(first, "3.5.2")
      val secondCompiler = newCompiler(second, "3.7.4")
      try
        assertSame(first, firstCompiler.getClass.getClassLoader)
        assertSame(second, secondCompiler.getClass.getClassLoader)
        assertNotSame(firstCompiler.getClass, secondCompiler.getClass)
        assertNotSame(first.loadClass("scala.Option"), second.loadClass("scala.Option"))
        assertNotSame(
          first.loadClass("dotty.tools.dotc.interactive.InteractiveDriver"),
          second.loadClass("dotty.tools.dotc.interactive.InteractiveDriver")
        )
      finally
        assertTrue(Scala3PcBridge.shutdown(firstCompiler).isRight)
        assertTrue(Scala3PcBridge.shutdown(secondCompiler).isRight)
    finally
      first.close()
      second.close()

  @Test
  def optionalCapabilitiesAreDiscoveredFromCompilerShape(): Unit =
    val artifacts = compilerDistribution("3.5.2")
    val loader    = new PcClassLoader(artifacts.map(_.toUri.toURL).toArray, getClass.getClassLoader)
    try
      val capabilities = Scala3PcBridge.discoverCapabilities(loader, artifacts.map(_.toFile))
      assertTrue(capabilities.basePresentationCompiler.isAvailable)
      assertTrue(capabilities.shutdownBarrier.isAvailable)
      assertTrue(capabilities.completion.isAvailable)
      assertTrue(capabilities.hover.isAvailable)
      assertTrue(capabilities.inlineTypes.isAvailable)
      assertTrue(capabilities.typedTreeSnapshots.isAvailable)
      assertTrue(capabilities.structuralCompletions.isAvailable)
      assertTrue(capabilities.bestEffortProduction.isAvailable)
      assertTrue(capabilities.bestEffortConsumption.isAvailable)
      assertTrue(capabilities.publicOperations.contains("complete"))
      assertTrue(capabilities.publicOperations.contains("hover"))
    finally loader.close()

  @Test
  def missingOptionalCapabilitiesAreReportedWithoutFailingBasePcDiscovery(): Unit =
    val loader = new PcClassLoader(Array.empty, getClass.getClassLoader)
    try
      val capabilities = Scala3PcBridge.discoverCapabilities(loader, Seq.empty)
      assertFalse(capabilities.basePresentationCompiler.isAvailable)
      assertFalse(capabilities.shutdownBarrier.isAvailable)
      assertFalse(capabilities.completion.isAvailable)
      assertFalse(capabilities.hover.isAvailable)
      assertFalse(capabilities.inlineTypes.isAvailable)
      assertFalse(capabilities.typedTreeSnapshots.isAvailable)
      assertFalse(capabilities.structuralCompletions.isAvailable)
      assertFalse(capabilities.bestEffortProduction.isAvailable)
      assertFalse(capabilities.bestEffortConsumption.isAvailable)
      assertTrue(capabilities.unavailableReasons.nonEmpty)
    finally loader.close()

  @Test
  def missingPresentationCompilerProviderHasAStructuredReason(): Unit =
    val loader = new PcClassLoader(Array.empty, getClass.getClassLoader)
    try
      assertEquals(
        Left(PresentationCompilerDiscoveryError.ProviderUnavailable),
        PresentationCompilerDiscovery.load(loader, Seq.empty)
      )
    finally loader.close()

  private def compilerLoader(scalaVersion: String): PcClassLoader =
    val artifacts = compilerDistribution(scalaVersion)
    new PcClassLoader(artifacts.map(_.toUri.toURL).toArray, getClass.getClassLoader)

  private def compilerDistribution(scalaVersion: String): Seq[Path] =
    PresentationCompilerResolver.publicCoursier
      .resolve(scalaVersion)
      .fold(error => throw error.toException, identity)

  private def newCompiler(loader: PcClassLoader, scalaVersion: String): PresentationCompiler =
    val prototype = discoverCompiler(loader, scalaVersion)
    try prototype.newInstance(s"test-$scalaVersion", Seq.empty[Path].asJava, Seq.empty[String].asJava)
    finally assertTrue(Scala3PcBridge.shutdown(prototype).isRight)

  private def discoverCompiler(loader: PcClassLoader, scalaVersion: String): PresentationCompiler =
    val artifacts = PresentationCompilerResolver.publicCoursier
      .resolve(scalaVersion)
      .fold(error => throw error.toException, identity)
    PresentationCompilerDiscovery
      .load(loader, artifacts.map(_.toFile))
      .fold(reason => throw new AssertionError(s"${reason.message} for Scala $scalaVersion"), identity)
