package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.pc.{PcSnapshotCurrency, PcSourceRange}
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.codeInsight.intention.CommonIntentionAction
import com.intellij.codeInsight.daemon.impl.HintRenderer
import com.intellij.codeInsight.hint.ShowParameterInfoContext
import com.intellij.codeInspection.{InspectionManager, ProblemDescriptor, ProblemsHolder}
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.lang.documentation.psi.PsiElementDocumentationTarget
import com.intellij.lang.refactoring.InlineActionHandler
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.lang.annotation.{AnnotationSession, HighlightSeverity}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.progress.{EmptyProgressIndicator, ProgressIndicator}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.{Computable, TextRange}
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiFile, SmartPointerManager}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
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
import org.jetbrains.plugins.scala.codeInsight.hints.ScalaHintsSettings
import org.jetbrains.plugins.scala.codeInsight.implicits.ImplicitHint
import org.jetbrains.plugins.scala.codeInspection.collections.UnitInMapInspection
import org.jetbrains.plugins.scala.editor.documentationProvider.ScalaDocQuickInfoGenerator
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScBindingPattern
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.expr.{MethodInvocation, ScExpression, ScReferenceExpression}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScValueOrVariableDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameter
import org.jetbrains.plugins.scala.lang.parameterInfo.ScalaFunctionParameterInfoHandler
import org.jetbrains.plugins.scala.lang.refactoring.changeSignature.changeInfo.ScalaChangeInfo
import org.jetbrains.plugins.scala.lang.refactoring.changeSignature.{ScalaChangeSignatureProcessor, ScalaParameterInfo}
import org.jetbrains.plugins.scala.lang.refactoring.extractMethod.ScalaExtractMethodHandler
import org.jetbrains.plugins.scala.lang.refactoring.introduceVariable.ScalaIntroduceVariableHandler
import org.jetbrains.plugins.scala.lang.formatting.settings.ScalaCodeStyleSettings
import org.jetbrains.plugins.scala.overrideImplement.ScalaOIUtil
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.jetbrains.plugins.scala.structureView.ScalaStructureViewModel
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}

import java.util.concurrent.{Callable, TimeUnit}
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
    assertTrue(ScalaPluginSemanticBridge.install().isEnabled)

  override protected def tearDown(): Unit =
    try
      Scala3CompilerBackend.get(getProject).clear()
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
    finally super.tearDown()

  def testAutomaticEditorHoverUsesCurrentInitializerType(): Unit =
    val file       = myFixture.configureByText("QuickInfo.scala", "val value = List(1).head")
    val expression = expressionWithText(file, "List(1).head")
    val binding    = PsiTreeUtil.findChildOfType(file, classOf[ScBindingPattern])
    publish(expression, "String")

    val quickInfo = ScalaDocQuickInfoGenerator.getQuickNavigateInfo(binding, binding).getOrElse("")

    assertTrue(quickInfo, quickInfo.contains("String"))
    assertFalse(quickInfo, quickInfo.contains(": Int"))

  def testQuickDocumentationActionUsesCurrentInitializerType(): Unit =
    val source     =
      """val value = List(1).head
        |val use = val<caret>ue
        |""".stripMargin
    val file       = myFixture.configureByText("QuickDocumentation.scala", source)
    val expression = expressionWithText(file, "List(1).head")
    publish(expression, "String")

    val targets = IdeDocumentationTargetProvider
      .getInstance(getProject)
      .documentationTargets(myFixture.getEditor, file, myFixture.getCaretOffset)
      .asScala
    val hint    = targets.collectFirst:
      case target: PsiElementDocumentationTarget => target.computeDocumentationHint()

    assertEquals(s"unexpected Quick Documentation targets: $targets", 1, targets.size)
    assertTrue(s"Quick Documentation did not resolve a PSI target: $targets", hint.isDefined)
    assertTrue(hint.getOrElse(""), hint.exists(_.contains("String")))
    assertFalse(hint.getOrElse(""), hint.exists(_.contains(": Int")))

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

  def testParameterInfoResolvesThroughCurrentReceiverType(): Unit =
    val source     =
      """val receiver = List(1).head
        |receiver.substring(<caret>)""".stripMargin
    val file       = myFixture.configureByText("ParameterInfo.scala", source)
    val expression = expressionWithText(file, "List(1).head")
    publish(expression, "String")

    val context = new ShowParameterInfoContext(
      myFixture.getEditor,
      getProject,
      file,
      myFixture.getCaretOffset,
      -1
    )
    val owner   = new ScalaFunctionParameterInfoHandler().findElementForParameterInfo(context)
    val items   = Option(context.getItemsToShow).fold(Array.empty[AnyRef])(_.asInstanceOf[Array[AnyRef]])

    assertTrue("String.substring parameter owner was not resolved", owner != null)
    assertTrue("String.substring signatures were not offered", items.nonEmpty)

  def testTypeAwareInspectionReadsCurrentExpressionType(): Unit =
    val source     =
      """object Main:
        |  def run(): Unit =
        |    List(1).map(_ => List(1).head)
        |    ()
        |""".stripMargin
    val file       = myFixture.configureByText("TypeAwareInspection.scala", source)
    val expression = expressionWithText(file, "List(1).head")
    val before     = unitInMapProblems(file)
    publish(expression, "Unit")
    val after      = unitInMapProblems(file)

    assertTrue(s"unexpected Unit-in-map warning before compiler publication: $before", before.isEmpty)
    assertTrue(s"Unit-in-map inspection did not observe compiler Unit: $after", after.nonEmpty)

  def testStructureViewRemainsSourceDrivenAfterCompilerPublication(): Unit =
    val source     =
      """object Main:
        |  val visible = List(1).head
        |""".stripMargin
    val file       = myFixture.configureByText("StructureView.scala", source)
    val expression = expressionWithText(file, "List(1).head")
    publish(expression, "String")

    val model = new ScalaStructureViewModel(file.asInstanceOf[ScalaFile])
    try
      val names = Iterator
        .iterate(Seq[TreeElement](model.getRoot))(_.flatMap(_.getChildren.toSeq))
        .takeWhile(_.nonEmpty)
        .flatten
        .collect { case element: StructureViewTreeElement => element }
        .flatMap(element => Option(element.getPresentation.getPresentableText))
        .toSet

      assertTrue(s"source object disappeared from structure view: $names", names.contains("Main"))
      assertTrue(s"source value disappeared from structure view: $names", names.exists(_.startsWith("visible")))
    finally model.dispose()

  def testSourceBackedRenameUsesBundledRefactoringWithCompilerSnapshot(): Unit =
    val source     =
      """def answer: Int = 42
        |val result = answer
        |""".stripMargin
    val file       = myFixture.configureByText("SourceRename.scala", source)
    val function   = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunction])
      .asScala
      .find(_.name == "answer")
      .get
    val expression = expressionWithText(file, "42")
    publish(expression, "Int")

    new RenameProcessor(getProject, function, "renamed", false, false).run()

    assertTrue(file.getText, file.getText.contains("def renamed"))
    assertTrue(file.getText, file.getText.contains("val result = renamed"))

  def testInactiveModulePreservesBundledConsumerResultsWithoutBackendWork(): Unit =
    val settings = MetallurgySettings(getProject)
    settings.setEnabled(getModule, enabled = false)
    try
      val source     =
        """def takesString(value: String): Unit = ()
          |takesString(List(1).head)""".stripMargin
      val file       = myFixture.configureByText("InactiveConsumer.scala", source)
      val expression = expressionWithText(file, "List(1).head")
      val backend    = Scala3CompilerBackend.get(getProject)

      assertEquals(
        CompilerBackendPublication.IgnoredInactive,
        backend.publish(
          expression,
          CompilerBackendRole.ExpressionExact,
          myFixture.getEditor.getDocument.getModificationStamp,
          "String"
        )
      )
      assertTrue(
        "the inactive bundled annotator result must remain unchanged",
        annotate(file, invocationIn(file)).nonEmpty
      )
      assertEquals(0, com.hmemcpy.metallurgy.pc.PcSessionManager.get(getProject).activeSessionCount)
    finally settings.setEnabled(getModule, enabled = true)

  def testImplementMethodUsesCurrentCompilerReturnType(): Unit =
    val source   =
      """trait Parent:
        |  def value: Int
        |
        |class Child extends Parent:
        |  <caret>
        |""".stripMargin
    val file     = myFixture.configureByText("ImplementMethod.scala", source)
    val function = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunction])
      .asScala
      .find(_.name == "value")
      .get
    publishType(function, CompilerBackendRole.FunctionResult, "String")

    given Project = getProject
    given Editor  = myFixture.getEditor
    ScalaOIUtil.invokeOverrideImplement(file, isImplement = true, Some("value"))

    assertTrue(file.getText, file.getText.contains("override def value: String"))

  def testSourceBackedInlineUsesBundledRefactoringWithCompilerSnapshot(): Unit =
    val source     =
      """object Main:
        |  val answer = List(1).head
        |  val result = ans<caret>wer
        |""".stripMargin
    val file       = myFixture.configureByText("InlineValue.scala", source)
    val expression = expressionWithText(file, "List(1).head")
    val reference  = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScReferenceExpression])
      .asScala
      .find(_.refName == "answer")
      .get
    val target     = reference.resolve()
    publish(expression, "String")

    val handler = InlineActionHandler.EP_NAME.getExtensionList.asScala.find(_.canInlineElement(target))
    assertTrue("no bundled inline handler accepted the source value", handler.isDefined)
    handler.get.inlineElement(getProject, myFixture.getEditor, target)
    PsiDocumentManager.getInstance(getProject).commitDocument(myFixture.getEditor.getDocument)

    assertTrue(file.getText, file.getText.contains("val result = List(1).head"))

  def testSourceBackedChangeSignatureUsesBundledRefactoringWithCompilerSnapshot(): Unit =
    val source   =
      """def answer(value: Int): Int = value
        |val result = answer(42)
        |""".stripMargin
    val file     = myFixture.configureByText("ChangeSignature.scala", source)
    val function = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunction])
      .asScala
      .find(_.name == "answer")
      .get
    publishType(function, CompilerBackendRole.FunctionResult, "String")
    val info     = ScalaChangeInfo(
      null,
      function,
      "renamed",
      function.returnType.get,
      Seq(function.parameters.map(new ScalaParameterInfo(_))),
      isAddDefaultArgs = false
    )

    new ScalaChangeSignatureProcessor(info)(using getProject).run()

    assertTrue(file.getText, file.getText.contains("def renamed"))
    assertTrue(file.getText, file.getText.contains("val result = renamed(42)"))

  def testIntroduceVariableRetainsBundledRefactoringWithCompilerSnapshot(): Unit =
    val source     =
      """object Main:
        |  def run = println(<selection>List(1).head</selection>)
        |""".stripMargin
    val file       = myFixture.configureByText("IntroduceVariable.scala", source)
    val expression = expressionWithText(file, "List(1).head")
    publish(expression, "String")
    val options    = ScalaIntroduceVariableHandler.ReplaceTestOptions(
      definitionName = Some("extracted"),
      useInplaceRefactoring = Some(false)
    )
    val context    = SimpleDataContext.builder
      .add(ScalaIntroduceVariableHandler.ForcedReplaceTestOptions, options)
      .build()

    val codeStyle = ScalaCodeStyleSettings.getInstance(getProject)
    val previous  = codeStyle.TYPE_ANNOTATION_LOCAL_DEFINITION
    codeStyle.TYPE_ANNOTATION_LOCAL_DEFINITION = true
    try
      new ScalaIntroduceVariableHandler().invoke(getProject, myFixture.getEditor, file, context)
      PsiDocumentManager.getInstance(getProject).commitDocument(myFixture.getEditor.getDocument)
    finally codeStyle.TYPE_ANNOTATION_LOCAL_DEFINITION = previous

    assertTrue(file.getText, file.getText.contains("val extracted: String"))
    assertTrue(file.getText, file.getText.contains("List(1).head"))
    assertTrue(file.getText, file.getText.contains("println(extracted)"))

  def testExtractMethodUsesCurrentCompilerResultType(): Unit =
    val source     =
      """object Main {
        |  def run = {
        |    val result = <selection>List(1).head</selection>
        |    println(result)
        |  }
        |}
        |""".stripMargin
    val file       = myFixture.configureByText("ExtractMethod.scala", source)
    val expression = expressionWithText(file, "List(1).head")
    publish(expression, "String")
    val context    = SimpleDataContext.getProjectContext(getProject)

    new ScalaExtractMethodHandler().invoke(getProject, myFixture.getEditor, file, context)
    NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    PsiDocumentManager.getInstance(getProject).commitDocument(myFixture.getEditor.getDocument)

    assertTrue(file.getText, file.getText.contains("def testMethodName: String"))
    assertTrue(file.getText, file.getText.contains("testMethodName"))

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

  def testBundledTypeHintsUseCompilerTypesForEverySemanticRoot(): Unit =
    val source =
      """object Main:
        |  val definition = 1
        |  def function = 1
        |  List(1).map(parameter => parameter)
        |  1 match
        |    case pattern => pattern
        |""".stripMargin
    val file   = myFixture.configureByText("SemanticTypeHints.scala", source)

    val definition = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScValueOrVariableDefinition])
      .asScala
      .find(_.bindings.exists(_.name == "definition"))
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
    val pattern    = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScBindingPattern])
      .asScala
      .find(_.name == "pattern")
      .get

    publishType(definition, CompilerBackendRole.Definition, "String")
    publishType(function, CompilerBackendRole.FunctionResult, "String")
    publishType(parameter, CompilerBackendRole.Parameter, "String")
    publishType(pattern, CompilerBackendRole.Pattern, "String")
    runBundledTypeHints(file.asInstanceOf[ScalaFile])

    val inlays = myFixture.getEditor.getInlayModel
      .getInlineElementsInRange(0, source.length)
      .asScala
      .filter(ImplicitHint.isImplicitHint)
    Seq(
      (definition, definition.pList, ": String"),
      (function, function.parameterList, ": String"),
      (parameter, parameter, "(: String)"),
      (pattern, pattern, ": String")
    ).foreach: (semanticElement, hintAnchor, expectedText) =>
      val texts = inlays
        .filter(ImplicitHint.elementOf(_) == hintAnchor)
        .flatMap(inlay => Option(inlay.getRenderer).collect { case renderer: HintRenderer => renderer.getText })
      assertEquals(
        s"bundled type hint for '${semanticElement.getText}'",
        expectedText,
        texts.mkString
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
      backend.commitSnapshotWithMappings(getModule, file, version, generation, Seq(mapping))(PcSnapshotCurrency.Current)
    val result                                    = ApplicationManager.getApplication.runReadAction(commit)
    assertTrue(result.toString, result.isInstanceOf[CompilerBackendCommit.Committed])

  private def publishType(element: PsiElement, role: CompilerBackendRole, renderedType: String): Unit =
    assertEquals(
      CompilerBackendPublication.Published,
      Scala3CompilerBackend
        .get(getProject)
        .publish(element, role, myFixture.getEditor.getDocument.getModificationStamp, renderedType)
    )

  private def runBundledTypeHints(file: ScalaFile): Unit =
    val passClass                 = Class.forName("org.jetbrains.plugins.scala.codeInsight.implicits.ImplicitHintsPass")
    val constructor               = passClass.getDeclaredConstructors
      .find(_.getParameterCount == 4)
      .getOrElse(throw new AssertionError("bundled ImplicitHintsPass constructor was not found"))
    constructor.setAccessible(true)
    val collect                   = passClass.getDeclaredMethod("doCollectInformation", classOf[ProgressIndicator])
    val applyHints                = passClass.getDeclaredMethod("doApplyInformationToEditor")
    collect.setAccessible(true)
    applyHints.setAccessible(true)
    val prepare: Callable[Object] = () =>
      val pass             = constructor.newInstance(
        myFixture.getEditor,
        file,
        new ScalaHintsSettings.CodeInsightSettingsAdapter,
        java.lang.Boolean.FALSE
      )
      val previousXRayMode = ScalaHintsSettings.xRayMode
      ScalaHintsSettings.xRayMode = true
      try
        val action: Runnable = () =>
          val _ = collect.invoke(pass, new EmptyProgressIndicator)
        ApplicationManager.getApplication.runReadAction(action)
        pass
      finally ScalaHintsSettings.xRayMode = previousXRayMode
    val pass                      = ApplicationManager.getApplication
      .executeOnPooledThread(prepare)
      .get(30L, TimeUnit.SECONDS)
    val _                         = applyHints.invoke(pass)

  private def unitInMapProblems(file: PsiFile): Seq[ProblemDescriptor] =
    val inspection = new UnitInMapInspection
    val holder     = new ProblemsHolder(InspectionManager.getInstance(getProject), file, true)
    val visitor    = inspection.buildVisitor(holder, true)
    PsiTreeUtil.processElements(
      file,
      element =>
        element.accept(visitor)
        true
    )
    holder.getResults.asScala.toSeq

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
