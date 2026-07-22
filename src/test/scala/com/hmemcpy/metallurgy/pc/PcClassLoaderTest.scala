package com.hmemcpy.metallurgy.pc

import org.junit.Assert.{assertFalse, assertNotSame, assertSame, assertThrows, assertTrue}
import org.junit.Test
import scala.meta.pc.PresentationCompiler

import java.nio.file.Path
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
      prototype.shutdown()
      loader.close()

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
        firstCompiler.shutdown()
        secondCompiler.shutdown()
    finally
      first.close()
      second.close()

  @Test
  def optionalCapabilitiesAreDiscoveredFromCompilerShape(): Unit =
    val loader = compilerLoader("3.5.2")
    try
      val capabilities = Scala3PcBridge.discoverCapabilities(loader)
      assertTrue(capabilities.inlineTypes.isAvailable)
      assertTrue(capabilities.typedTreeSnapshots.isAvailable)
      assertTrue(capabilities.structuralCompletions.isAvailable)
      assertTrue(capabilities.bestEffortProduction.isAvailable)
      assertTrue(capabilities.bestEffortConsumption.isAvailable)
    finally loader.close()

  @Test
  def missingOptionalCapabilitiesAreReportedWithoutFailingBasePcDiscovery(): Unit =
    val loader = new PcClassLoader(Array.empty, getClass.getClassLoader)
    try
      val capabilities = Scala3PcBridge.discoverCapabilities(loader)
      assertFalse(capabilities.inlineTypes.isAvailable)
      assertFalse(capabilities.typedTreeSnapshots.isAvailable)
      assertFalse(capabilities.structuralCompletions.isAvailable)
      assertFalse(capabilities.bestEffortProduction.isAvailable)
      assertFalse(capabilities.bestEffortConsumption.isAvailable)
      assertTrue(capabilities.unavailableReasons.nonEmpty)
    finally loader.close()

  private def compilerLoader(scalaVersion: String): PcClassLoader =
    val artifacts = PresentationCompilerResolver.publicCoursier
      .resolve(scalaVersion)
      .fold(error => throw error.toException, identity)
    new PcClassLoader(artifacts.map(_.toUri.toURL).toArray, getClass.getClassLoader)

  private def newCompiler(loader: PcClassLoader, scalaVersion: String): PresentationCompiler =
    val prototype = discoverCompiler(loader, scalaVersion)
    try prototype.newInstance(s"test-$scalaVersion", Seq.empty[Path].asJava, Seq.empty[String].asJava)
    finally prototype.shutdown()

  private def discoverCompiler(loader: PcClassLoader, scalaVersion: String): PresentationCompiler =
    val artifacts = PresentationCompilerResolver.publicCoursier
      .resolve(scalaVersion)
      .fold(error => throw error.toException, identity)
    PresentationCompilerDiscovery
      .load(loader, artifacts.map(_.toFile))
      .fold(reason => throw new AssertionError(s"$reason for Scala $scalaVersion"), identity)
