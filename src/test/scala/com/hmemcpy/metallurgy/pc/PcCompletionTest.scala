package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.build.ScalacFlagsService
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.roots.OrderEnumerator
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.assertTrue

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** Verifies the presentation compiler completes members the bundled plugin leaves out — extension methods and
  * structural-refinement members. Each case retypechecks a snippet, then asks the compiler to complete at the caret
  * (the end of a partial member access) and asserts the expected member appears among the returned items.
  */
final class PcCompletionTest extends ScalaLightCodeInsightFixtureTestCase:

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.fromString("3.5.2").get

  override protected def defaultVersionOverride: Option[ScalaVersion] =
    Some(new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2"))

  override def getTestDataPath: String =
    Path.of("src", "test", "testdata").toAbsolutePath.toString

  // (label, source ending at the caret, expected lookupNames)
  private val cases: Seq[(String, (String, Set[String]))] = Seq(
    "extension method"  -> (
      """extension (s: String) def slugify: String = s.trim
        |val result = " A ".slugi""".stripMargin,
      Set("slugify")
    ),
    "structural member" -> (
      """import scala.reflect.Selectable.reflectiveSelectable
        |val s: { def magic: Int } = new:
        |  def magic: Int = 42
        |val result = s.mag""".stripMargin,
      Set("magic")
    )
  )

  def testPcCompletion(): Unit = withSession: session =>
    val results  = cases.zipWithIndex.map { case ((label, (source, expected)), idx) =>
      val snapshot    = PcSnapshot(s"file:///Completion$idx.scala", 1L, source)
      val _           = session.scheduleRetypecheck(snapshot).get(30, TimeUnit.SECONDS)
      val items       = session.complete(snapshot.fileUri, snapshot.sourceText, snapshot.documentVersion, source.length)
      val lookupNames = items.map(_.lookupName).toSet
      val ok          = expected.subsetOf(lookupNames)
      println(f"[complete] ${if ok then "OK  " else "FAIL"} $label%-20s -> ${lookupNames.toSeq.sorted.mkString(", ")}")
      (ok, label, lookupNames.toSeq.sorted.mkString(", "), expected)
    }
    val failures = results.filterNot(_._1)
    assertTrue(
      s"${failures.size}/${cases.size} completion cases failed:\n" +
        failures.map(f => s"  - ${f._2}: got '${f._3}', required ${f._4.mkString("[", ",", "]")}").mkString("\n"),
      failures.isEmpty
    )

  private def withSession(test: PcSession => Unit): Unit =
    val temporaryDirectory = Files.createTempDirectory("pc-completion")
    val fetcher            = new MtagsFetcher(
      PcArtifactCache(temporaryDirectory.resolve("cache")),
      PresentationCompilerResolver.publicCoursier,
      BackgroundRunner.direct
    )
    val settings           = MetallurgySettings(getProject)
    try
      settings.setEnabled(getModule, enabled = true)
      val _ = onPooledThread(fetcher.jarsFor(testScalaVersion.minor).get(120, TimeUnit.SECONDS))
      onPooledThread:
        val options = ScalacFlagsService.get(getProject).compilerOptions(getModule)
        val session = PcSession.create(testScalaVersion.minor, moduleClasspath, options, fetcher)
        try test(session)
        finally session.close()
    finally
      settings.setEnabled(getModule, enabled = false)
      deleteRecursively(temporaryDirectory)

  private def testScalaVersion: ScalaVersion = new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2")

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

  private def onPooledThread[A](body: => A): A =
    com.intellij.openapi.application.ApplicationManager.getApplication
      .executeOnPooledThread(() => body)
      .get(120, TimeUnit.SECONDS)

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      val stream = Files.walk(path)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      finally stream.close()
