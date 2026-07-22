package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.pc.{PcSnapshotCurrency, PcSourceRange}
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.codeInsight.intention.CommonIntentionAction
import com.intellij.lang.annotation.{AnnotationSession, HighlightSeverity}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.{Computable, TextRange}
import com.intellij.psi.{PsiElement, PsiFile, SmartPointerManager}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import org.jetbrains.annotations.{Nls, Nullable}
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.annotator.element.ScMethodInvocationAnnotator
import org.jetbrains.plugins.scala.annotator.{
  DummyScalaAnnotationBuilder,
  ScalaAnnotationHolder,
  ScalaAnnotationBuilder
}
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.editor.documentationProvider.ScalaDocQuickInfoGenerator
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScBindingPattern
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.expr.{MethodInvocation, ScExpression, ScReferenceExpression}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScValueOrVariableDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameter
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}

import scala.annotation.nowarn
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

final class BundledCompilerBackendConsumerTest extends ScalaLightCodeInsightFixtureTestCase:

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
    assertTrue(BundledCompilerBackendShim.install().isEnabled)

  override protected def tearDown(): Unit =
    try
      Scala3CompilerBackend.get(getProject).clear()
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
    finally super.tearDown()

  def testQuickInfoUsesCurrentInitializerType(): Unit =
    val file       = myFixture.configureByText("QuickInfo.scala", "val value = List(1).head")
    val expression = expressionWithText(file, "List(1).head")
    val binding    = PsiTreeUtil.findChildOfType(file, classOf[ScBindingPattern])
    publish(expression, "String")

    val quickInfo = ScalaDocQuickInfoGenerator.getQuickNavigateInfo(binding, binding).getOrElse("")

    assertTrue(quickInfo, quickInfo.contains("String"))
    assertFalse(quickInfo, quickInfo.contains(": Int"))

  def testExpressionAnnotatorUsesCurrentArgumentType(): Unit =
    val source     =
      """def takesString(value: String): Unit = ()
        |takesString(List(1).head)""".stripMargin
    val beforeFile = myFixture.configureByText("MismatchBefore.scala", source)
    val before     = annotate(beforeFile, invocationIn(beforeFile))
    val afterFile  = myFixture.configureByText("MismatchAfter.scala", source)
    val expression = expressionWithText(afterFile, "List(1).head")
    publish(expression, "String")
    val after      = annotate(afterFile, invocationIn(afterFile))

    assertTrue(s"expected the bundled annotator to see the original mismatch, got $before", before.nonEmpty)
    assertTrue(s"compiler backend type did not satisfy the bundled annotator: $after", after.isEmpty)

  def testReceiverCompletionUsesCurrentInitializerType(): Unit =
    val source     =
      """val receiver = List(1).head
        |receiver.<caret>""".stripMargin
    val file       = myFixture.configureByText("ReceiverCompletion.scala", source)
    val expression = expressionWithText(file, "List(1).head")
    val _          = PlatformTestUtil.waitForFuture(
      com.hmemcpy.metallurgy.pc.PcSessionManager.get(getProject).prepareCompilerBackend(file.getVirtualFile),
      60000L
    )
    publish(expression, "String")

    val lookupStrings = myFixture.completeBasic().iterator.map(_.getLookupString).toSet

    assertTrue(
      s"String receiver completion was absent: ${lookupStrings.toSeq.sorted.mkString(", ")}",
      lookupStrings.contains("substring")
    )

  def testCompletionExpectedContextUsesCurrentInitializerType(): Unit =
    val source           =
      """def same[A](first: A)(second: A): A = second
        |val expected = List(1).head
        |same(expected)(completionTarget)""".stripMargin
    val file             = myFixture.configureByText("ExpectedCompletion.scala", source)
    val expression       = expressionWithText(file, "List(1).head")
    val completionTarget = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScReferenceExpression])
      .asScala
      .find(_.refName == "completionTarget")
      .get
    publish(expression, "String")

    val expectedTypes = completionTarget.expectedTypes().map(_.canonicalText)

    assertTrue(
      s"completion expected types did not contain String: $expectedTypes",
      expectedTypes.exists(_.contains("String"))
    )
    assertFalse(s"completion retained the bundled Int context: $expectedTypes", expectedTypes.contains("Int"))

  def testQuickInfoUsesRealBulkCompilerSnapshot(): Unit =
    val source     =
      """val id = [A] => (value: A) => value
        |val result = id(42)""".stripMargin
    val file       = myFixture.configureByText("BulkQuickInfo.scala", source)
    val _          = PlatformTestUtil.waitForFuture(
      com.hmemcpy.metallurgy.pc.PcSessionManager.get(getProject).prepareCompilerBackend(file.getVirtualFile),
      60000L
    )
    val definition = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScValueOrVariableDefinition])
      .asScala
      .find(_.bindings.exists(_.name == "result"))
      .get
    val binding    = definition.bindings.head
    val backend    = Scala3CompilerBackend
      .get(getProject)
      .stateForActiveModule(binding, getModule, CompilerBackendRole.Binding)

    val quickInfo = ScalaDocQuickInfoGenerator.getQuickNavigateInfo(binding, binding).getOrElse("")

    backend match
      case CompilerBackendState.Current("Int", _) => ()
      case state                                  => throw new AssertionError(s"expected exact bulk Int state, got $state")
    assertTrue(quickInfo, quickInfo.contains("Int"))

  def testQuickInfoUsesCompilerTypesForEveryDeclaredSemanticRoot(): Unit =
    val source =
      """object Main:
        |  val declared: Int = 1
        |  def function: Int = 1
        |  def parameterOwner(parameter: Int): Unit = ()
        |  val (number, _) = (1, "text")
        |""".stripMargin
    val file   = myFixture.configureByText("SemanticQuickInfo.scala", source)

    val declaredType = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScTypeElement])
      .asScala
      .find(element => element.getText == "Int" && element.getParent.getText.contains("declared"))
      .get
    val function     = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunction])
      .asScala
      .find(_.name == "function")
      .get
    val parameter    = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScParameter])
      .asScala
      .find(_.name == "parameter")
      .get
    val pattern      = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScBindingPattern])
      .asScala
      .find(_.name == "number")
      .get
    val declared     = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScBindingPattern])
      .asScala
      .find(_.name == "declared")
      .get

    publishType(declaredType, CompilerBackendRole.DeclaredType, "String")
    publishType(function, CompilerBackendRole.FunctionResult, "String")
    publishType(parameter, CompilerBackendRole.Parameter, "String")
    publishType(pattern, CompilerBackendRole.Pattern, "String")

    Seq(declared, function, parameter, pattern).foreach: element =>
      val quickInfo = ScalaDocQuickInfoGenerator.getQuickNavigateInfo(element, element).getOrElse("")
      assertTrue(s"quick info did not use String for '${element.getText}': $quickInfo", quickInfo.contains("String"))
      assertFalse(s"quick info retained Int for '${element.getText}': $quickInfo", quickInfo.contains(": Int"))

  private def publish(expression: ScExpression, renderedType: String): Unit =
    val backend                                   = Scala3CompilerBackend.get(getProject)
    val file                                      = expression.getContainingFile
    val fileUrl                                   = file.getVirtualFile.getUrl
    val version                                   = myFixture.getEditor.getDocument.getModificationStamp
    val generation                                = CompilerBackendGeneration(101L, 101L, 101L)
    val range                                     = expression.getTextRange
    val mapping                                   = CompilerBackendMapping(
      SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(expression),
      PcSourceRange(range.getStartOffset, range.getEndOffset),
      CompilerBackendRole.ExpressionExact,
      renderedType,
      None
    )
    backend.markPending(getModule, fileUrl, version, generation)
    val commit: Computable[CompilerBackendCommit] = () =>
      backend.commitSnapshot(getModule, file, version, generation, Seq(mapping))(PcSnapshotCurrency.Current)
    val result                                    = ApplicationManager.getApplication.runReadAction(commit)
    assertTrue(result.toString, result.isInstanceOf[CompilerBackendCommit.Committed])

  private def publishType(element: PsiElement, role: CompilerBackendRole, renderedType: String): Unit =
    assertEquals(
      CompilerBackendPublication.Published,
      Scala3CompilerBackend
        .get(getProject)
        .publish(element, role, myFixture.getEditor.getDocument.getModificationStamp, renderedType)
    )

  private def expressionWithText(file: PsiElement, text: String): ScExpression =
    PsiTreeUtil
      .findChildrenOfType(file, classOf[ScExpression])
      .asScala
      .find(_.getText == text)
      .getOrElse(throw new AssertionError(s"expression '$text' was not found"))

  private def invocationIn(file: PsiFile): MethodInvocation =
    PsiTreeUtil
      .findChildrenOfType(file, classOf[ScExpression])
      .asScala
      .collectFirst { case invocation: MethodInvocation if invocation.getText.startsWith("takesString") => invocation }
      .getOrElse(throw new AssertionError("takesString invocation was not found"))

  private def annotate(file: PsiFile, invocation: MethodInvocation): Vector[String] =
    val holder                  = new RecordingAnnotationHolder(file)
    given ScalaAnnotationHolder = holder
    ScMethodInvocationAnnotator.annotate(invocation, typeAware = true)
    holder.errors

  private final class RecordingAnnotationHolder(file: PsiFile) extends ScalaAnnotationHolder:
    private val recordedErrors = mutable.ArrayBuffer.empty[String]

    def errors: Vector[String] = recordedErrors.toVector

    @nowarn("cat=deprecation")
    override def getCurrentAnnotationSession: AnnotationSession = new AnnotationSession(file)

    override def isBatchMode: Boolean = false

    override def newAnnotation(severity: HighlightSeverity, message: String): ScalaAnnotationBuilder =
      new RecordingAnnotationBuilder(severity, message)

    override def newSilentAnnotation(severity: HighlightSeverity): ScalaAnnotationBuilder =
      new RecordingAnnotationBuilder(severity, null)

    private final class RecordingAnnotationBuilder(
        severity: HighlightSeverity,
        @Nullable @Nls message: String
    ) extends DummyScalaAnnotationBuilder(severity, message):
      override def onCreate(
          severity: HighlightSeverity,
          message: String,
          range: TextRange,
          enforcedAttributes: TextAttributesKey,
          fixes: Seq[CommonIntentionAction]
      ): Unit =
        if severity == HighlightSeverity.ERROR then recordedErrors += Option(message).getOrElse("")

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)
