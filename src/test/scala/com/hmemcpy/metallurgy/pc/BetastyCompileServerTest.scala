package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.build.ScalacFlagsService
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PlatformTestUtil
import org.jetbrains.plugins.scala.compiler.ScalaCompilerTestBase
import org.jetbrains.plugins.scala.ScalaVersion
import org.junit.Assert.assertTrue

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/** Proves IntelliJ's ordinary build pipeline emits best-effort TASTy when the active backend adds the discovered
  * producer options to the bundled Scala compiler profile.
  */
final class BetastyCompileServerTest extends ScalaCompilerTestBase:

  private val testScalaVersion = ScalaVersion.fromString("3.5.2").get

  override protected def supportedIn(version: ScalaVersion): Boolean = version == testScalaVersion

  override protected def defaultVersionOverride: Option[ScalaVersion] = Some(testScalaVersion)

  override protected def reuseCompileServerProcessBetweenTests: Boolean = false

  override protected def compileServerShutdownTimeout = 10.seconds

  def testBrokenModuleEmitsBetastyThroughIntellijBuild(): Unit =
    val settings = MetallurgySettings(getProject)
    val flags    = ScalacFlagsService.get(getProject)

    setCompilerBasedHighlighting(enabled = true)
    settings.setEnabled(getModule, enabled = true)
    flags.enableFor(getModule, Scala3PcBridgeCapabilities.bestEffort)
    assertTrue(ScalacFlagsService.BestEffortFlags.forall(flags.additionalOptions(getModule).contains))

    val _ = addFileToProjectSources(
      "Person.scala",
      """final class Person(val name: String):
        |  def validName: String = name
        |  def brokenValue: MissingType = ???
        |""".stripMargin
    )

    val buildStarted = System.nanoTime()
    val messages     = compiler.make().asScala.toSeq
    val buildMillis  = (System.nanoTime() - buildStarted) / 1000000L
    assertTrue(
      "the deliberately broken producer must report a compiler error",
      messages.exists(_.getCategory == CompilerMessageCategory.ERROR)
    )

    val discoveryStarted = System.nanoTime()
    val artifacts        = betastyArtifacts(getBaseDir.toNioPath.resolve("out"))
    val discoveryMillis  = (System.nanoTime() - discoveryStarted) / 1000000L

    assertTrue(
      s"IntelliJ build emitted no .betasty artifact under ${getBaseDir.toNioPath}: $messages",
      artifacts.nonEmpty
    )
    assertTrue(artifacts.exists(_.getFileName.toString == "Person.betasty"))

    val consumer  = addFileToProjectSources(
      "Consumer.scala",
      "val recoveredName: String = new Person(\"Ada\").validName"
    )
    val pcStarted = System.nanoTime()
    val session   = PlatformTestUtil
      .waitForFuture(PcSessionManager.get(getProject).prepareCompilerBackend(consumer), TimeUnit.SECONDS.toMillis(60))
      .get
    val pcMillis  = (System.nanoTime() - pcStarted) / 1000000L
    val document  = FileDocumentManager.getInstance().getDocument(consumer)
    val snapshot  = PcSnapshot(consumer.getUrl, document.getModificationStamp, document.getText)
    val errors    = session.diagnostics(snapshot).toSeq.flatten.filter(_.isError)
    println(
      s"[betasty-loops] IntelliJ build loop: ${buildMillis}ms; artifact discovery: ${discoveryMillis}ms; pc loop: ${pcMillis}ms"
    )
    assertTrue(s"pc did not consume IntelliJ's best-effort output: $errors", errors.isEmpty)

  private def betastyArtifacts(root: Path): Seq[Path] =
    if !Files.isDirectory(root) then Seq.empty
    else
      val files = Files.walk(root)
      try files.iterator().asScala.filter(path => Files.isRegularFile(path) && path.toString.endsWith(".betasty")).toSeq
      finally files.close()

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)

  override protected def tearDown(): Unit =
    try
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
    finally super.tearDown()
