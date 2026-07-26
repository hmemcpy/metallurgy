package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.build.ScalacFlagsService
import com.hmemcpy.metallurgy.psiproducer.{DotcTreeSource, Scala3DotcLanguage}
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.util.Computable
import com.intellij.psi.{PsiErrorElement, PsiFile, PsiFileFactory}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScExpression, ScGenericCall, ScMethodCall, ScReferenceExpression}
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScPatternDefinition
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.{assertEquals, assertNotNull, assertTrue}

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** The dialect file-root parse produces bundled-compatible PSI from the compiler's typed tree. Each case installs a
  * fixed extraction, forces the dialect parse, selects the marked expression (the bundled plugin's `/*start*/…/*end*/`
  * convention), and asserts the produced structure through the public PSI API. The named-type-argument sources are
  * verbatim from the bundled plugin's `Scala3NamedTypeArgumentsInferenceTest`.
  */
final class DotcPsiProducerTest extends ScalaLightCodeInsightFixtureTestCase:

  override def getTestDataPath: String = "src/test/testdata"

  private val scalaVersion = new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2")

  private val StartMarker = "/*start*/"
  private val EndMarker   = "/*end*/"

  def testMethodInvocationWithPartiallyNamedTypeArguments_InferSecondParam(): Unit =
    withDotcProducedFile(
      s"""
         |import scala.language.experimental.namedTypeArguments
         |
         |def pair[A, B](a: A, b: B): (A, B) = (a, b)
         |
         |val value = ${StartMarker}pair[A = Int](1, "text")$EndMarker
         |//(Int, String)
         |""".stripMargin
    ): file =>
      val call    = selectedExpression(file).asInstanceOf[ScMethodCall]
      assertEquals("pair[A = Int](1, \"text\")", call.getText)
      val generic = PsiTreeUtil.findChildOfType(file, classOf[ScGenericCall])
      assertEquals("pair[A = Int]", generic.getText)
      assertNoParserErrors(file)

  def testGenericCallWithPartiallyNamedTypeArguments_InferSecondParamFromExpectedType(): Unit =
    withDotcProducedFile(
      s"""
         |import scala.language.experimental.namedTypeArguments
         |
         |def make[A, B]: (A, B) = ???
         |
         |val value: (Int, String) = ${StartMarker}make[A = Int]$EndMarker
         |//(Int, String)
         |""".stripMargin
    ): file =>
      val call = selectedExpression(file).asInstanceOf[ScGenericCall]
      assertEquals("make[A = Int]", call.getText)
      assertTrue(call.referencedExpr.isInstanceOf[ScReferenceExpression])
      assertEquals("make", call.referencedExpr.getText)
      assertEquals("[A = Int]", call.typeArgs.getText)
      assertNoParserErrors(file)

  def testDocsExampleConstructWithNamedTypeArguments(): Unit =
    withDotcProducedFile(
      s"""
         |import scala.language.experimental.namedTypeArguments
         |
         |def construct[Elem, Coll[_]](xs: Elem*): Coll[Elem] = ???
         |
         |val xs1 = construct[Coll = List, Elem = Int](1, 2, 3)
         |val xs2 = ${StartMarker}construct[Coll = List](1, 2, 3)$EndMarker
         |//List[Int]
         |""".stripMargin
    ): file =>
      val call = selectedExpression(file).asInstanceOf[ScMethodCall]
      assertEquals("construct[Coll = List](1, 2, 3)", call.getText)
      assertNoParserErrors(file)

  def testProducesValueDefinitionFromDotc(): Unit =
    withDotcProducedFile("val v = 1\n"): file =>
      val defn = PsiTreeUtil.findChildOfType(file, classOf[ScPatternDefinition])
      assertNotNull("val v = 1 is a value definition", defn)
      assertEquals("val v = 1", defn.getText)
      assertNoParserErrors(file)

  private def selectedExpression(file: PsiFile): ScExpression =
    val text  = file.getText
    val start = text.indexOf(StartMarker)
    val end   = text.indexOf(EndMarker)
    assertTrue("missing /*start*/ marker", start >= 0)
    assertTrue("missing /*end*/ marker", end >= 0)
    val expr  = PsiTreeUtil.findElementOfClassAtRange(file, start + StartMarker.length, end, classOf[ScExpression])
    assertNotNull(s"no expression between markers in:\n$text", expr)
    expr

  private def assertNoParserErrors(file: PsiFile): Unit =
    assertTrue(
      "the dotc-authored region contains no parser errors",
      PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty
    )

  /** Compile the source under dotc, install the extraction, force the dialect parse, and run the assertions against the
    * produced file in a read action.
    */
  private def withDotcProducedFile(source: String)(check: ScalaFile => Unit): Unit =
    withSession: session =>
      val snapshot   = PcSnapshot("file:///DotcProducerCase.scala", 0L, source)
      val _          = onPooledThread(session.scheduleRetypecheck(snapshot).get(30, TimeUnit.SECONDS))
      val extraction = session.compilerTreeExtraction(snapshot)
      assertTrue("dotc tree extraction present", extraction.isDefined)
      DotcTreeSource.install(source, extraction.get)
      try
        ApplicationManager.getApplication.runReadAction(
          new Computable[Unit]:
            override def compute(): Unit =
              val file = PsiFileFactory
                .getInstance(getProject)
                .createFileFromText("DotcProducerCase.scala", Scala3DotcLanguage.INSTANCE, source)
                .asInstanceOf[ScalaFile]
              check(file)
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
