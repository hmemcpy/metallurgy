package com.hmemcpy.metallurgy.pc

import org.junit.Assert.{assertFalse, assertNotSame, assertSame, assertThrows, assertTrue}
import org.junit.Test
import scala.meta.pc.PresentationCompiler

import java.net.URL
import java.nio.file.Path
import java.util.ServiceLoader
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
  def providerDescriptorRemainsVisibleUntilPublishedCompilerArtifactsCarryIt(): Unit =
    val loader = new PcClassLoader(Array.empty[URL], getClass.getClassLoader)
    try assertTrue(loader.getResources("META-INF/services/scala.meta.pc.PresentationCompiler").hasMoreElements)
    finally loader.close()

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

  private def compilerLoader(scalaVersion: String): PcClassLoader =
    val artifacts = PresentationCompilerResolver.publicCoursier
      .resolve(scalaVersion)
      .fold(error => throw error.toException, identity)
    new PcClassLoader(artifacts.map(_.toUri.toURL).toArray, getClass.getClassLoader)

  private def newCompiler(loader: PcClassLoader, scalaVersion: String): PresentationCompiler =
    val prototype = ServiceLoader
      .load(classOf[PresentationCompiler], loader)
      .iterator()
      .asScala
      .nextOption()
      .getOrElse(throw new AssertionError(s"No presentation compiler provider for Scala $scalaVersion"))
    prototype.newInstance(s"test-$scalaVersion", Seq.empty[Path].asJava, Seq.empty[String].asJava)
