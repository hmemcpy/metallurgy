package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.build.ScalacFlagsService
import com.hmemcpy.metallurgy.psiproducer.{DotcTreeSource, Scala3DotcLanguage}
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.util.Computable
import com.intellij.psi.{PsiErrorElement, PsiFile, PsiFileFactory}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.Scala3Language
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScExpression, ScGenericCall, ScMethodCall, ScReferenceExpression}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameter
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunctionDefinition, ScPatternDefinition}
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

  def testProducesFunctionDefinitionFromDotc(): Unit =
    withDotcProducedFile("def f: Int = 1\n"): file =>
      val defn = PsiTreeUtil.findChildOfType(file, classOf[ScFunctionDefinition])
      assertNotNull("def f: Int = 1 is a function definition", defn)
      assertEquals("def f: Int = 1", defn.getText)
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

  def testValDeclarationBindsName(): Unit =
    withDotcProducedFile("val v = 42\n"): file =>
      val pat      = PsiTreeUtil.findChildOfType(file, classOf[ScPatternDefinition])
      assertNotNull("pattern definition produced", pat)
      val declared = pat.declaredElements
      assertEquals("v is declared", 1, declared.length)
      assertEquals("declared name is v", "v", declared.head.name)

  def testProducerMatchesBundledOnResolverCriticalShape(): Unit =
    val source  = "def foo(x: Int): Int = x\nval v = foo(42)\n"
    val bundled = ApplicationManager.getApplication.runReadAction(
      new Computable[ScalaFile]:
        override def compute(): ScalaFile =
          PsiFileFactory
            .getInstance(getProject)
            .createFileFromText("B.scala", Scala3Language.INSTANCE, source)
            .asInstanceOf[ScalaFile]
    )
    withDotcProducedFile(source): producer =>
      def shape(f: ScalaFile): String =
        val fn = PsiTreeUtil.findChildOfType(f, classOf[ScFunctionDefinition])
        val pd = PsiTreeUtil.findChildOfType(f, classOf[ScPatternDefinition])
        s"top=${f.getChildren.count(!_.isInstanceOf[com.intellij.psi.PsiWhiteSpace])}" +
          s";params=${Option(fn).map(_.parameters.length).orNull}" +
          s";ret=${Option(fn).exists(_.returnTypeElement.isDefined)}" +
          s";declared=${Option(pd).map(_.declaredElements.length).orNull}"
      assertEquals("producer matches bundled on resolver-critical shape", shape(bundled), shape(producer))

  def testNamedTypeArgCallResolvesAndTypes(): Unit =
    withDotcProducedFile(
      """import scala.language.experimental.namedTypeArguments
        |def pair[A, B](a: A, b: B): (A, B) = (a, b)
        |val value = pair[A = Int](1, "text")
        |""".stripMargin
    ): file =>
      val generic  = PsiTreeUtil.findChildOfType(file, classOf[ScGenericCall])
      assertNotNull("pair[A = Int] produced as a ScGenericCall", generic)
      val ref      = PsiTreeUtil.findChildOfType(generic, classOf[ScReferenceExpression])
      assertNotNull("pair reference inside the generic call", ref)
      val resolved = ref.resolve()
      assertNotNull(s"pair resolves to its definition", resolved)

  def testCallExpressionComputesType(): Unit =
    withDotcProducedFile("def foo(x: Int): Int = x\nval v = foo(42)\n"): file =>
      val call = PsiTreeUtil.findChildOfType(file, classOf[ScMethodCall])
      assertNotNull("method call produced", call)
      val t    = call.`type`().toOption.map { tt =>
        given org.jetbrains.plugins.scala.lang.psi.types.TypePresentationContext =
          org.jetbrains.plugins.scala.lang.psi.types.TypePresentationContext.emptyContextIn(scalaVersion)
        tt.presentableText
      }
      assertEquals("foo(42) types as Int", Some("Int"), t)

  def testParameterTypeElementResolves(): Unit =
    withDotcProducedFile("def foo(x: Int): Int = x\n"): file =>
      val fn  = PsiTreeUtil.findChildOfType(file, classOf[ScFunctionDefinition])
      val p   = fn.parameters.headOption.orNull
      assertNotNull("parameter produced", p)
      val tpe = p.typeElement
      assertTrue(s"parameter x has a type element (got $tpe)", tpe.isDefined)

  def testFunctionReturnTypeElementResolves(): Unit =
    withDotcProducedFile("def foo(x: Int): Int = x\n"): file =>
      val fn  = PsiTreeUtil.findChildOfType(file, classOf[ScFunctionDefinition])
      assertNotNull("function definition produced", fn)
      val tpe = fn.returnTypeElement
      assertTrue(s"foo has a return type element (got $tpe)", tpe.isDefined)

  def testCallReferenceResolvesToDefinition(): Unit =
    withDotcProducedFile("def foo(x: Int): Int = x\nval v = foo(42)\n"): file =>
      val refs     = PsiTreeUtil.findChildrenOfType(file, classOf[ScReferenceExpression]).asScala
      val fooRef   = refs.find(_.getText == "foo").orNull
      assertNotNull("reference to foo found", fooRef)
      val resolved = fooRef.resolve()
      assertNotNull(s"foo resolves to its definition", resolved)

  def testFunctionParameterBecomesScParameter(): Unit =
    withDotcProducedFile("def foo(x: Int): Int = x\n"): file =>
      val fn     = PsiTreeUtil.findChildOfType(file, classOf[ScFunctionDefinition])
      assertNotNull("function definition produced", fn)
      val params = fn.parameters
      assertEquals("foo has one parameter", 1, params.length)
      val p      = params.head
      assertEquals("parameter name is x", "x", p.name)
      assertTrue("parameter is a ScParameter", p.isInstanceOf[ScParameter])

  /** Compile the source under dotc, install the extraction, force the dialect parse, and run the assertions against the
    * produced file in a read action.
    */
  private def withDotcProducedFile(source: String)(check: ScalaFile => Unit): Unit =
    withSession: session =>
      val snapshot   = PcSnapshot("file:///DotcProducerCase.scala", 0L, source)
      val _          = onPooledThread(session.scheduleRetypecheck(snapshot).get(30, TimeUnit.SECONDS))
      val extraction = session.compilerTreeExtraction(snapshot)
      assertTrue("dotc tree extraction present", extraction.isDefined)
      val _          = DotcTreeSource.install(source, extraction.get)
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
