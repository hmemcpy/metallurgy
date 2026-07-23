package com.hmemcpy.metallurgy.uast

import com.hmemcpy.metallurgy.compilerbackend.*
import com.hmemcpy.metallurgy.pc.{PcCompilerSymbol, PcSessionManager, PcSnapshotCurrency, PcSourceRange}
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiNamedElement, PsiVariable, SmartPointerManager}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScBindingPattern
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScExpression, ScMethodCall, ScReferenceExpression}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScValueOrVariableDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameter
import org.jetbrains.plugins.scala.lang.psi.uast.converter.Scala2UastConverter.*
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.jetbrains.uast.{
  UCallExpression,
  UExpression,
  ULiteralExpression,
  UMethod,
  UMultiResolvable,
  UParameter,
  UReferenceExpression,
  UVariable,
  UastFacade
}
import org.junit.Assert.{assertEquals, assertNull, assertSame, assertTrue}

import scala.jdk.CollectionConverters.*

final class CompilerBackendUastTest extends ScalaLightCodeInsightFixtureTestCase:

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.fromString("3.5.2").get

  override protected def defaultVersionOverride: Option[ScalaVersion] =
    Some(new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2"))

  override def getTestDataPath: String =
    java.nio.file.Path.of("src", "test", "testdata").toAbsolutePath.toString

  override protected def setUp(): Unit =
    super.setUp()
    MetallurgySettings(getProject).setEnabled(getModule, enabled = true)
    setCompilerBasedHighlighting(enabled = true)
    assertTrue(com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge.install().isEnabled)

  override protected def tearDown(): Unit =
    try
      Scala3CompilerBackend.get(getProject).clear()
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
    finally super.tearDown()

  def testExpressionTypeUsesCurrentCompilerSnapshot(): Unit =
    val file       = myFixture.configureByText("UastExpression.scala", "val value = List(1).head")
    val expression = expressionWithText(file, "List(1).head")
    publish(expression, "String")

    val uExpression = expression.convertWithParentTo[UExpression]().get

    assertEquals("java.lang.String", uExpression.getExpressionType.getCanonicalText)

  def testInactiveModulePreservesBundledUastAndCreatesNoBackendSession(): Unit =
    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
    setCompilerBasedHighlighting(enabled = false)
    val file       = myFixture.configureByText("InactiveUastExpression.scala", "val value = List(1).head")
    val expression = expressionWithText(file, "List(1).head")

    assertEquals(
      CompilerBackendPublication.IgnoredInactive,
      Scala3CompilerBackend
        .get(getProject)
        .publish(
          expression,
          CompilerBackendRole.ExpressionExact,
          myFixture.getEditor.getDocument.getModificationStamp,
          "String"
        )
    )
    val uExpression = expression.convertWithParentTo[UExpression]().get
    assertEquals("int", uExpression.getExpressionType.getCanonicalText)
    assertEquals(0, PcSessionManager.get(getProject).activeSessionCount)

  def testDeclarationPsiTypesUseCurrentCompilerSnapshot(): Unit =
    val source     =
      """object Main:
        |  val inferred = List(1).head
        |  def function: Int = 1
        |  def use(parameter: Int): Unit = ()
        |""".stripMargin
    val file       = myFixture.configureByText("UastDeclarations.scala", source)
    val expression = expressionWithText(file, "List(1).head")
    val definition = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScValueOrVariableDefinition])
      .asScala
      .find(_.bindings.exists(_.name == "inferred"))
      .get
    val binding    = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScBindingPattern])
      .asScala
      .find(_.name == "inferred")
      .get
    val function   = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunction])
      .asScala
      .find(_.name == "function")
      .get
    val parameter  = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScParameter])
      .asScala
      .find(_.name == "parameter")
      .get
    publish(expression, "String")
    publishType(definition, CompilerBackendRole.Definition, "String")
    publishType(function, CompilerBackendRole.FunctionResult, "String")
    publishType(parameter, CompilerBackendRole.Parameter, "String")

    val uVariable  = binding.convertWithParentTo[UVariable]().get
    val uMethod    = function.convertWithParentTo[UMethod]().get
    val uParameter = parameter.convertWithParentTo[UParameter]().get

    assertEquals("java.lang.String", uVariable.getType.getCanonicalText)
    assertEquals("java.lang.String", uVariable.getJavaPsi.asInstanceOf[PsiVariable].getType.getCanonicalText)
    assertEquals("java.lang.String", uMethod.getReturnType.getCanonicalText)
    assertEquals("java.lang.String", uParameter.getType.getCanonicalText)

  def testReceiverResolveConversionAndEvaluationRemainSourceBacked(): Unit =
    val source    =
      """class Service:
        |  def render(value: Int): Int = value
        |
        |val service = Service()
        |val result = service.render(40)
        |""".stripMargin
    val file      = myFixture.configureByText("UastResolve.scala", source)
    val function  = descendants[ScFunction](file).find(_.name == "render").get
    val call      = descendants[ScMethodCall](file).find(_.getText == "service.render(40)").get
    val reference = descendants[ScReferenceExpression](file).find(_.refName == "render").get
    val literal   = descendants[ScExpression](file).find(_.getText == "40").get

    val uCall    = call.convertWithParentTo[UCallExpression]().get
    val uLiteral = literal.convertWithParentTo[ULiteralExpression]().get

    assertEquals("Function1<Object, Object>", uCall.getReceiverType.getPresentableText)
    assertSame(function, reference.resolve())
    assertSame(function, uCall.resolve().getNavigationElement)
    assertEquals(40, uLiteral.evaluate())
    assertSame(literal, uLiteral.getSourcePsi)

  def testMethodCallTypesUseCurrentCompilerSnapshot(): Unit =
    val file    = myFixture.configureByText("UastMethodCall.scala", "val value = List(1).map(identity)")
    val call    = descendants[ScMethodCall](file).find(_.getText == "List(1).map(identity)").get
    val invoked = call.getInvokedExpr
    publishType(invoked, CompilerBackendRole.ExpressionExact, "String")
    publishType(call, CompilerBackendRole.ExpressionExact, "Boolean")

    val uCall = call.convertWithParentTo[UCallExpression]().get

    assertEquals("java.lang.String", uCall.getReceiverType.getCanonicalText)
    assertEquals("boolean", uCall.getReturnType.getCanonicalText)

  def testCompilerOnlySymbolReachesUastResolveAndMultiResolve(): Unit =
    val file       = myFixture.configureByText("UastCompilerOnlyResolve.scala", "val result = generatedMember")
    val reference  = descendants[ScReferenceExpression](file).find(_.refName == "generatedMember").get
    val document   = myFixture.getEditor.getDocument
    val range      = reference.getTextRange
    val generation = CompilerBackendGeneration(41L, 41L, 41L)
    val symbol     = PcCompilerSymbol("Main.generatedMember", "generatedMember", Set("Method"), None, None)
    val mapping    = CompilerBackendMapping(
      SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(reference),
      PcSourceRange(range.getStartOffset, range.getEndOffset),
      CompilerBackendRole.Reference,
      "String",
      Some(symbol.id),
      Some(symbol)
    )
    val backend    = Scala3CompilerBackend.get(getProject)
    backend.markPending(getModule, file.getVirtualFile.getUrl, document.getModificationStamp, generation)
    assertEquals(
      CompilerBackendCommit.Committed(1),
      backend.commitSnapshot(getModule, file, document.getModificationStamp, generation, Seq(mapping))(
        PcSnapshotCurrency.Current
      )
    )

    val uReference = reference.convertWithParentTo[UReferenceExpression]().get
    val target     = uReference.resolve().asInstanceOf[PsiNamedElement]

    assertEquals("generatedMember", target.getName)
    assertSame(target, uReference.asInstanceOf[UMultiResolvable].multiResolve.iterator().next().getElement)

  def testDirectConversionReadsTheLatestCompilerGeneration(): Unit =
    val file       = myFixture.configureByText("UastFreshness.scala", "val value = List(1).head")
    val expression = expressionWithText(file, "List(1).head")
    publish(expression, "String")
    assertEquals(
      "java.lang.String",
      expression.convertWithParentTo[UExpression]().get.getExpressionType.getCanonicalText
    )

    val document           = myFixture.getEditor.getDocument
    val edit: Runnable     = () => document.insertString(document.getTextLength, "\n")
    WriteCommandAction.runWriteCommandAction(getProject, edit)
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    val reparsedExpression = expressionWithText(file, "List(1).head")
    publish(reparsedExpression, "Long")
    assertEquals("long", reparsedExpression.convertWithParentTo[UExpression]().get.getExpressionType.getCanonicalText)

  def testPlatformUastPluginIsUnavailableUnderTheRetainedCbhFailsafe(): Unit =
    val file = myFixture.configureByText("CbhUast.scala", "val value = 1")

    assertNull(UastFacade.INSTANCE.findPlugin(file))

  private def publish(expression: ScExpression, renderedType: String): Unit =
    assertEquals(
      CompilerBackendPublication.Published,
      Scala3CompilerBackend
        .get(getProject)
        .publish(
          expression,
          CompilerBackendRole.ExpressionExact,
          myFixture.getEditor.getDocument.getModificationStamp,
          renderedType
        )
    )

  private def publishType(
      element: com.intellij.psi.PsiElement,
      role: CompilerBackendRole,
      renderedType: String
  ): Unit =
    assertEquals(
      CompilerBackendPublication.Published,
      Scala3CompilerBackend
        .get(getProject)
        .publish(element, role, myFixture.getEditor.getDocument.getModificationStamp, renderedType)
    )

  private def expressionWithText(file: com.intellij.psi.PsiElement, text: String): ScExpression =
    PsiTreeUtil
      .findChildrenOfType(file, classOf[ScExpression])
      .asScala
      .find(_.getText == text)
      .getOrElse(throw new AssertionError(s"expression '$text' was not found"))

  private def descendants[A <: PsiElement: reflect.ClassTag](element: PsiElement): Seq[A] =
    PsiTreeUtil
      .findChildrenOfType(element, reflect.classTag[A].runtimeClass.asInstanceOf[Class[A]])
      .asScala
      .toSeq

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)
