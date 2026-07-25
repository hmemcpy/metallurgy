package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.build.ScalacFlagsService
import com.hmemcpy.metallurgy.psiproducer.{DotcTreeSource, Scala3DotcLanguage}
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.util.{Computable, TextRange}
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiErrorElement
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScGenericCall, ScReferenceExpression}
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.{assertEquals, assertNotNull, assertTrue}

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** The dialect file-root parse produces bundled-compatible PSI from the compiler's typed tree. The first slice proves
  * it for a construct the bundled parser cannot parse: a named type argument in expression position.
  */
final class DotcPsiProducerTest extends ScalaLightCodeInsightFixtureTestCase:

  override def getTestDataPath: String = "src/test/testdata"

  private val scalaVersion = new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2")

  def testProducesNamedTypeArgumentGenericCallFromDotc(): Unit =
    withSession: session =>
      val source   =
        """def f[A]: Int = 1
          |val v = f[A = Int]
          |""".stripMargin
      val snapshot = PcSnapshot("file:///NamedTypeArgCase.scala", 0L, source)
      val _        = onPooledThread(session.scheduleRetypecheck(snapshot).get(30, TimeUnit.SECONDS))

      val extraction = session.compilerTreeExtraction(snapshot)
      assertTrue("dotc tree extraction present", extraction.isDefined)
      val e          = extraction.get
      assertTrue(
        "dotc typed f[A = Int] as a named type application",
        e.tree.physicalNodes.exists(_.kind == "TypeApply") &&
          e.tree.physicalNodes.exists(_.kind == "NamedArg")
      )

      DotcTreeSource.install(source, e)
      try
        ApplicationManager.getApplication.runReadAction(
          new Computable[Unit]:
            override def compute(): Unit =
              val file = PsiFileFactory
                .getInstance(getProject)
                .createFileFromText("NamedTypeArgCase.scala", Scala3DotcLanguage.INSTANCE, source)

              val call = PsiTreeUtil.findChildOfType(file, classOf[ScGenericCall])
              assertNotNull("f[A = Int] is a generic-call expression", call)
              assertEquals("f[A = Int]", call.getText)
              assertTrue(call.referencedExpr.isInstanceOf[ScReferenceExpression])
              assertEquals("f", call.referencedExpr.getText)
              assertEquals("[A = Int]", call.typeArgs.getText)

              val start = source.indexOf("f[A = Int]")
              assertEquals(new TextRange(start, start + "f[A = Int]".length), call.getTextRange)

              assertTrue(
                "the dotc-authored region contains no parser errors",
                PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty
              )
        )
      finally DotcTreeSource.clear()

  private def withSession(test: PcSession => Unit): Unit =
    val temporaryDirectory = Files.createTempDirectory("pc-dotc-producer")
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
      .compileOnly
      .withoutSdk
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
