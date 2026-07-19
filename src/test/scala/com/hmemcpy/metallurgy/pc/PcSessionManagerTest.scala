package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.build.ScalacFlagsService
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.OrderEnumerator
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.junit.Assert.{assertFalse, assertNotSame, assertSame, assertTrue}

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

final class PcSessionManagerTest extends ScalaLightCodeInsightFixtureTestCase:

  private val testScalaVersion = ScalaVersion.fromString("3.5.2").get

  override def getTestDataPath: String =
    Path.of("src", "test", "testdata").toAbsolutePath.toString

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == testScalaVersion

  override protected def defaultVersionOverride: Option[ScalaVersion] =
    Some(testScalaVersion)

  def testBundledResolverLoadsAWorkingPresentationCompiler(): Unit =
    val temporaryDirectory = Files.createTempDirectory("metallurgy-real-pc")
    val fetcher            = new MtagsFetcher(
      PcArtifactCache(temporaryDirectory.resolve("cache")),
      PresentationCompilerResolver.bundled,
      BackgroundRunner.direct
    )
    val settings           = MetallurgySettings(getProject)

    try
      settings.setEnabled(getModule, enabled = true)
      val directory = onPooledThread:
        fetcher.jarsFor(testScalaVersion.minor).get(120, TimeUnit.SECONDS)
      val names     = Files.list(directory)
      try
        val artifactNames = names.iterator().asScala.map(_.getFileName.toString).toSeq
        assertTrue(artifactNames.exists(_.startsWith("scala3-presentation-compiler_3-3.5.2")))
        assertTrue(artifactNames.exists(_.startsWith("scala3-compiler_3-3.5.2")))
        assertTrue(artifactNames.exists(_.startsWith("mtags-interfaces-")))
      finally names.close()

      val source = "object Main:\n  val values = List(1)\n  values."
      val items  = onPooledThread:
        val session = PcSession.create(
          testScalaVersion.minor,
          moduleClasspath,
          ScalacFlagsService.get(getProject).compilerOptions(getModule),
          fetcher
        )
        try session.complete("file:///Main.scala", source, 1L, source.length)
        finally session.close()

      assertTrue(
        items.map(item => s"${item.lookupName} [${item.label}]").mkString(", "),
        items.exists(item => item.lookupName == "map" || item.label.startsWith("map"))
      )
    finally
      settings.setEnabled(getModule, enabled = false)
      deleteRecursively(temporaryDirectory)

  def testEligibilityOptInReuseAndDiscardLifecycle(): Unit =
    val temporaryDirectory = Files.createTempDirectory("metallurgy-session-manager")
    val artifact           = Files.write(temporaryDirectory.resolve("presentation-compiler.jar"), Array[Byte](1))
    val resolver           = new PresentationCompilerResolver:
      override def resolve(scalaVersion: String): Either[ArtifactResolutionError, Seq[Path]] =
        Right(Seq(artifact))
    val fetcher            = new MtagsFetcher(
      PcArtifactCache(temporaryDirectory.resolve("cache")),
      resolver,
      BackgroundRunner.direct
    )
    val manager            = new PcSessionManager(getProject, fetcher)
    val settings           = MetallurgySettings(getProject)

    try
      assertTrue(onPooledThread(manager.sessionFor(getModule)).isEmpty)

      settings.setEnabled(getModule, enabled = true)
      val first  = manager.sessionForAsync(getModule).get(5, TimeUnit.SECONDS).get
      val second = onPooledThread(manager.sessionFor(getModule)).get
      assertSame(first, second)
      assertTrue(ScalacFlagsService.RequiredFlags.forall(first.compilerOptions.contains))

      onPooledThread(manager.discard(getModule))
      assertTrue(first.isClosed)

      val replacement = onPooledThread(manager.sessionFor(getModule)).get
      assertNotSame(first, replacement)
      assertFalse(replacement.isClosed)

      settings.setEnabled(getModule, enabled = false)
      assertTrue(onPooledThread(manager.sessionFor(getModule)).isEmpty)
      assertTrue(replacement.isClosed)
    finally
      manager.dispose()
      settings.setEnabled(getModule, enabled = false)
      deleteRecursively(temporaryDirectory)

  private def onPooledThread[A](body: => A): A =
    ApplicationManager.getApplication
      .executeOnPooledThread(() => body)
      .get(120, TimeUnit.SECONDS)

  private def moduleClasspath =
    OrderEnumerator
      .orderEntries(getModule)
      .recursively
      .compileOnly
      .withoutSdk
      .classes
      .getPathsList
      .getPathList
      .asScala
      .map(new java.io.File(_))
      .toSeq

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      val stream = Files.walk(path)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      finally stream.close()
