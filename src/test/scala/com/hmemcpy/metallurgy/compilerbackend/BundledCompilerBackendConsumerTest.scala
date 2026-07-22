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
import org.jetbrains.plugins.scala.lang.psi.api.expr.{MethodInvocation, ScExpression}
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.{assertFalse, assertTrue}

import scala.annotation.nowarn
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
    private var recordedErrors = Vector.empty[String]

    def errors: Vector[String] = recordedErrors

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
        if severity == HighlightSeverity.ERROR then recordedErrors :+= Option(message).getOrElse("")

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)
