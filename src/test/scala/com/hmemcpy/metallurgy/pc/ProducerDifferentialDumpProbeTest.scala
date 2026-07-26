package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.build.ScalacFlagsService
import com.hmemcpy.metallurgy.psiproducer.{DotcTreeSource, Scala3DotcLanguage}
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.util.Computable
import com.intellij.psi.impl.DebugUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiFileFactory
import org.jetbrains.plugins.scala.Scala3Language
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.assertTrue

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** Dumps the bundled and producer PSI trees for a source, side by side, to reveal grammar gaps the producer must close.
  */
final class ProducerDifferentialDumpProbeTest extends ScalaLightCodeInsightFixtureTestCase:

  override def getTestDataPath: String = "src/test/testdata"

  private val scalaVersion = new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2")

  def testDumpForComprehension(): Unit =
    dumpDifferential(
      "for-comp",
      """object O:
        |  for
        |    x <- List(1, 2, 3)
        |    if x > 1
        |    y = x * 2
        |  yield y
        |""".stripMargin
    )

  def testDumpUsingClause(): Unit =
    dumpDifferential(
      "using-clause",
      """trait Ev
        |object O:
        |  def foo(using ev: Ev)(x: Int): Int = x
        |""".stripMargin
    )

  def testDumpTraitWithTypeParam(): Unit =
    withSession: session =>
      val source   = "trait Show[A]:\n  def show(a: A): String\n"
      val snapshot = PcSnapshot("file:///TraitTp.scala", 0L, source)
      val _        = onPooledThread(session.scheduleRetypecheck(snapshot).get(30, TimeUnit.SECONDS))
      val dto      = session.untypedTreeDto(snapshot)
      assertTrue(dto.isDefined)
      println("\n===== TRAIT TYPE PARAM NODES =====")
      dto.get.physicalNodes.foreach: node =>
        val r = node.range.fold("?")(rg => s"${rg.startOffset}-${rg.endOffset}")
        println(
          f"  id=${node.id} parent=${node.parentId.getOrElse("-")} ${node.kind}%-20s $r name=${node.name.getOrElse("")} role=${node.role.getOrElse("-")}"
        )

  def testDumpGiven(): Unit =
    dumpDifferential(
      "given",
      """trait Show[A]:
        |  def show(a: A): String
        |object O:
        |  given Show[Int] with
        |    def show(a: Int): String = a.toString
        |""".stripMargin
    )

  def testDumpObjectWithMembers(): Unit =
    dumpDifferential(
      "object-members",
      """object O:
        |  val a = 1
        |  var b = 2
        |  def double(n: Int): Int = n * 2
        |  class Inner
        |""".stripMargin
    )

  private def dumpDifferential(label: String, source: String): Unit =
    withSession: session =>
      val snapshot   = PcSnapshot(s"file:///Diff$label.scala", 0L, source)
      val _          = onPooledThread(session.scheduleRetypecheck(snapshot).get(30, TimeUnit.SECONDS))
      val extraction = session.untypedTreeExtraction(snapshot)
      assertTrue(s"extraction present for $label", extraction.isDefined)
      val _          = DotcTreeSource.install(source, extraction.get)
      try
        ApplicationManager.getApplication.runReadAction(
          new Computable[Unit]:
            override def compute(): Unit =
              val bundled  = PsiFileFactory
                .getInstance(getProject)
                .createFileFromText(s"Bundled$label.scala", Scala3Language.INSTANCE, source)
                .asInstanceOf[ScalaFile]
              val produced = PsiFileFactory
                .getInstance(getProject)
                .createFileFromText(s"Produced$label.scala", Scala3DotcLanguage.INSTANCE, source)
                .asInstanceOf[ScalaFile]
              println(s"\n========== DIFFERENTIAL: $label ==========")
              println(s"--- source ---\n$source")
              println(s"--- BUNDLED PSI ---")
              println(DebugUtil.psiToString(bundled, false, true))
              println(s"--- PRODUCER PSI ---")
              println(DebugUtil.psiToString(produced, false, true))
              val errors   = PsiTreeUtil.findChildrenOfType(produced, classOf[com.intellij.psi.PsiErrorElement])
              assertTrue(
                s"producer has ${errors.size} PsiErrorElements: ${errors.asScala.map(_.getErrorDescription).mkString(", ")}",
                errors.isEmpty
              )
        )
      finally DotcTreeSource.clear()

  private def withSession(test: PcSession => Unit): Unit =
    val temporaryDirectory = Files.createTempDirectory("pc-diff-probe")
    val fetcher            = new MtagsFetcher(
      PcArtifactCache(temporaryDirectory.resolve("cache")),
      PresentationCompilerResolver.publicCoursier,
      BackgroundRunner.direct
    )
    val settings           = MetallurgySettings(getProject)
    try
      settings.setEnabled(getModule, enabled = true)
      val _ = onPooledThread(fetcher.jarsFor(scalaVersion.minor).get(120, TimeUnit.SECONDS))
      onPooledThread:
        val options =
          ScalacFlagsService.get(getProject).compilerOptions(getModule) :+ "-language:experimental.namedTypeArguments"
        val session = PcSession.create(scalaVersion.minor, moduleClasspath, options, fetcher)
        try test(session)
        finally session.close()
    finally
      settings.setEnabled(getModule, enabled = false)
      deleteRecursively(temporaryDirectory)

  private def moduleClasspath: Seq[java.io.File] =
    OrderEnumerator
      .orderEntries(getModule)
      .recursively
      .withoutSdk
      .compileOnly
      .classes
      .getPathsList
      .getPathList
      .asScala
      .map(new java.io.File(_))
      .toSeq

  private def onPooledThread[A](body: => A): A =
    ApplicationManager.getApplication.executeOnPooledThread(() => body).get(120, TimeUnit.SECONDS)

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      val stream = Files.walk(path)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      finally stream.close()
