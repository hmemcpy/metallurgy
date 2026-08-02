package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import com.intellij.lang.{ASTFactory, ASTNode, PsiBuilder, PsiBuilderFactory}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.{CommandProcessor, WriteCommandAction}
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiDocumentManager, PsiErrorElement, PsiFile, SmartPointerManager}
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.impl.source.tree.TreeElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType
import org.jetbrains.plugins.scala.lang.psi.api.base.{ScAnnotation, ScAnnotations, ScModifierList}
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScPatternDefinition, ScVariableDefinition}
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.MetallurgyExpressionPayload
import org.jetbrains.plugins.scala.lang.psi.stubs.{
  ScAccessModifierStub,
  ScAnnotationStub,
  ScAnnotationsStub,
  ScModifiersStub
}
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*

final class DotcPsiProducerEmitterTest extends ScalaLightCodeInsightFixtureTestCase:

  private val packagingSurface =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/packaging/ScPackagingImpl"
  private val packagingRole    = PsiOutputRoleId.PackageStatement

  override def getTestDataPath: String = "src/test/testdata"

  def testRejectsUnsupportedPlanFeaturesBeforeOpeningMarkers(): Unit =
    val source            = "x"
    val base              = emitterPlan(source, 2)
    val unsupportedField  = base.copy(composites =
      base.composites.updated(
        0,
        base.composites.head
          .copy(fieldDispositions = Vector(FieldDisposition("value", FieldDispositionKind.Unsupported)))
      )
    )
    val unsupportedToken  = base.copy(physicalLeafOwnership =
      base.physicalLeafOwnership.map(
        _.copy(target = TerminalLeafTarget.Token(packagingSurface))
      )
    )
    val unknownOwner      = CompositeInstanceId(ProductionInstanceId(InventoryKind.Node, 999L, None), "missing")
    val malformedStub     = base.copy(stubAssertions =
      Vector(
        PlannedStubAssertion(unknownOwner, "stub", "serializer", Vector("index"), "navigation")
      )
    )
    val hostDerivedTarget = base.copy(targetAssertions =
      base.targetAssertions.map(assertion =>
        assertion.copy(targetIdentity =
          PlannedTargetIdentity.TokenRole(PsiOutputRoleId.SourceTerminal, packagingSurface)
        )
      )
    )
    Vector(unsupportedField, unsupportedToken, malformedStub, hostDerivedTarget).foreach: plan =>
      val builder = recordingEmitterBuilder(source)
      assertFalse(
        DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, builder, plan, nativeBindings)
      )
      assertEquals(0, builder.getCurrentOffset)

    val unboundRole = base.copy(targetAssertions =
      base.targetAssertions.map(
        _.copy(targetIdentity = PlannedTargetIdentity.OutputRole(PsiOutputRoleId("scala.output.unbound")))
      )
    )
    val roleBuilder = recordingEmitterBuilder(source)
    assertFalse(
      DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, roleBuilder, unboundRole, nativeBindings)
    )
    assertEquals(0, roleBuilder.getCurrentOffset)

    val widerSource  = "xy"
    val wider        = emitterPlan(widerSource, 1)
    val outsideOwner = wider.copy(composites = wider.composites.map(_.copy(range = PcSourceRange(0, 1))))
    val builder      = recordingEmitterBuilder(widerSource)
    assertFalse(DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, builder, outsideOwner, nativeBindings))
    assertEquals(0, builder.getCurrentOffset)

  def testHandlesDeepCompositeNestingWithoutJvmRecursion(): Unit =
    val source  = "x"
    val builder = recordingEmitterBuilder(source)
    val plan    = emitterPlan(source, 10000)
    val targets = plan.targetAssertions.collect:
      case PlannedTargetAssertion(
            TargetAssertionOwner.Composite(owner),
            PlannedTargetIdentity.OutputRole(outputRoleId),
            _
          ) =>
        owner -> outputRoleId
    DotcPsiProducer.emit(
      plan.composites.head,
      plan.composites.map(value => value.instance -> value).toMap,
      targets.toMap,
      nativeBindings,
      builder
    )
    assertTrue(builder.eof())

  def testEmitsDistinctColocatedZeroWidthCompositeIdentities(): Unit =
    val source  = "x"
    val base    = emitterPlan(source, 1)
    val first   = base.composites.head.copy(
      instance = CompositeInstanceId(ProductionInstanceId(InventoryKind.Node, 2L, None), "empty-first"),
      range = PcSourceRange(0, 0),
      children = Vector.empty
    )
    val second  = first.copy(
      instance = CompositeInstanceId(ProductionInstanceId(InventoryKind.Node, 3L, None), "empty-second")
    )
    val plan    = base.copy(
      composites = Vector(first, second) ++ base.composites,
      targetAssertions = Vector(first.instance, second.instance).map(instance =>
        PlannedTargetAssertion(
          TargetAssertionOwner.Composite(instance),
          PlannedTargetIdentity.OutputRole(packagingRole),
          TargetAssertionKind.NativeComposite
        )
      ) ++ base.targetAssertions
    )
    val builder = recordingEmitterBuilder(source)

    assertTrue(DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, builder, plan, nativeBindings))
    assertEquals(source.length, builder.getCurrentOffset)

  def testFailClosedTreePreservesExactZeroWidthParserErrorAndNeutralSource(): Unit =
    val source      = ")\nval result = 1\n"
    val diagnostics = Vector(
      ParserDiagnostic(
        ParserDiagnosticSeverity.Error,
        "eof expected, but ')' found",
        Some(ParserDiagnosticPosition(PcSourceRange(0, 0), 0))
      )
    )
    val lexer       = PlannedScala3Lexer
      .recovery(source, Vector(0, 0))
      .fold(failure => throw new AssertionError(failure.toString), identity)
    val chameleon   = myFixture.configureByText("RecoveredError.scala", source).getNode
    val builder     = PsiBuilderFactory
      .getInstance()
      .createBuilder(getProject, chameleon, lexer, Scala3DotcLanguage.INSTANCE, source)

    assertTrue(DotcPsiProducer.emitClosedFile(Scala3DotcParserDefinition.FileNodeType, builder, diagnostics).isRight)
    val tree     = builder.getTreeBuilt
    val elements = descendantNodes(tree).map(_.getPsi)
    val errors   = elements.collect { case error: PsiErrorElement => error }

    assertEquals(source, tree.getText)
    assertEquals(1, errors.size)
    assertEquals("eof expected, but ')' found", errors.head.getErrorDescription)
    assertEquals(new TextRange(0, 0), errors.head.getTextRange)
    assertTrue(elements.collect { case _: ScExpression => () }.isEmpty)
    assertTrue(elements.collect { case _: ScFunction => () }.isEmpty)
    assertTrue(elements.collect { case _: ScPatternDefinition => () }.isEmpty)
    assertTrue(elements.collect { case _: ScVariableDefinition => () }.isEmpty)

  def testFailClosedTreePreservesNestedDuplicateAndCoincidentParserDiagnosticIdentities(): Unit =
    val source      = "abcdef"
    val diagnostics = Vector(
      ParserDiagnostic(
        ParserDiagnosticSeverity.Error,
        "outer-first",
        Some(ParserDiagnosticPosition(PcSourceRange(0, 6), 0))
      ),
      ParserDiagnostic(
        ParserDiagnosticSeverity.Error,
        "outer-second",
        Some(ParserDiagnosticPosition(PcSourceRange(0, 6), 0))
      ),
      ParserDiagnostic(
        ParserDiagnosticSeverity.Error,
        "inner",
        Some(ParserDiagnosticPosition(PcSourceRange(1, 3), 1))
      ),
      ParserDiagnostic(
        ParserDiagnosticSeverity.Error,
        "point-first",
        Some(ParserDiagnosticPosition(PcSourceRange(2, 2), 2))
      ),
      ParserDiagnostic(
        ParserDiagnosticSeverity.Error,
        "point-second",
        Some(ParserDiagnosticPosition(PcSourceRange(2, 2), 2))
      ),
      ParserDiagnostic(
        ParserDiagnosticSeverity.Warning,
        "warning",
        Some(ParserDiagnosticPosition(PcSourceRange(4, 5), 4))
      )
    )
    val boundaries  =
      diagnostics.flatMap(_.position.toVector.flatMap(value => Vector(value.range.startOffset, value.range.endOffset)))
    val lexer       = PlannedScala3Lexer
      .recovery(source, boundaries)
      .fold(failure => throw new AssertionError(failure.toString), identity)
    val chameleon   = myFixture.configureByText("NestedRecoveredErrors.scala", source).getNode
    val builder     = PsiBuilderFactory
      .getInstance()
      .createBuilder(getProject, chameleon, lexer, Scala3DotcLanguage.INSTANCE, source)

    assertTrue(DotcPsiProducer.emitClosedFile(Scala3DotcParserDefinition.FileNodeType, builder, diagnostics).isRight)
    val tree   = builder.getTreeBuilt
    val errors = descendantNodes(tree).map(_.getPsi).collect { case error: PsiErrorElement => error }

    assertEquals(source, tree.getText)
    assertEquals(
      Set(
        "outer-first"  -> new TextRange(0, 6),
        "outer-second" -> new TextRange(0, 6),
        "inner"        -> new TextRange(1, 3),
        "point-first"  -> new TextRange(2, 2),
        "point-second" -> new TextRange(2, 2)
      ),
      errors.map(error => error.getErrorDescription -> error.getTextRange).toSet
    )
    assertEquals(5, errors.size)

  def testFailClosedTreeRejectsCrossingAndInvalidDiagnosticRangesWithoutRelocation(): Unit =
    val source      = "abcdef"
    val diagnostics = Vector(
      ParserDiagnostic(
        ParserDiagnosticSeverity.Error,
        "first",
        Some(ParserDiagnosticPosition(PcSourceRange(0, 3), 0))
      ),
      ParserDiagnostic(
        ParserDiagnosticSeverity.Error,
        "crossing",
        Some(ParserDiagnosticPosition(PcSourceRange(2, 5), 2))
      )
    )
    val lexer       = PlannedScala3Lexer
      .recovery(source, Vector(0, 2, 3, 5))
      .fold(failure => throw new AssertionError(failure.toString), identity)
    val chameleon   = myFixture.configureByText("CrossingRecoveredErrors.scala", source).getNode
    val builder     = PsiBuilderFactory
      .getInstance()
      .createBuilder(getProject, chameleon, lexer, Scala3DotcLanguage.INSTANCE, source)

    assertTrue(DotcPsiProducer.emitClosedFile(Scala3DotcParserDefinition.FileNodeType, builder, diagnostics).isLeft)
    val crossingTree = builder.getTreeBuilt
    assertEquals(source, crossingTree.getText)
    assertTrue(descendantNodes(crossingTree).map(_.getPsi).collect { case _: PsiErrorElement => () }.isEmpty)

    val invalidBuilder = PsiBuilderFactory
      .getInstance()
      .createBuilder(
        getProject,
        myFixture.configureByText("InvalidRecoveredError.scala", source).getNode,
        PlannedScala3Lexer.closed,
        Scala3DotcLanguage.INSTANCE,
        source
      )
    val invalid        = Vector(
      ParserDiagnostic(
        ParserDiagnosticSeverity.Error,
        "outside",
        Some(ParserDiagnosticPosition(PcSourceRange(0, source.length + 1), 0))
      )
    )
    assertTrue(DotcPsiProducer.emitClosedFile(Scala3DotcParserDefinition.FileNodeType, invalidBuilder, invalid).isLeft)
    val invalidTree    = invalidBuilder.getTreeBuilt
    assertEquals(source, invalidTree.getText)
    assertTrue(descendantNodes(invalidTree).map(_.getPsi).collect { case _: PsiErrorElement => () }.isEmpty)

    val unpositionedBuilder = PsiBuilderFactory
      .getInstance()
      .createBuilder(
        getProject,
        myFixture.configureByText("UnpositionedRecoveredError.scala", source).getNode,
        PlannedScala3Lexer.closed,
        Scala3DotcLanguage.INSTANCE,
        source
      )
    val unpositioned        = Vector(ParserDiagnostic(ParserDiagnosticSeverity.Error, "unpositioned", None))
    assertTrue(
      DotcPsiProducer
        .emitClosedFile(Scala3DotcParserDefinition.FileNodeType, unpositionedBuilder, unpositioned)
        .left
        .exists(_.contains("has no exact source range"))
    )
    val unpositionedTree    = unpositionedBuilder.getTreeBuilt
    assertEquals(source, unpositionedTree.getText)
    assertTrue(descendantNodes(unpositionedTree).map(_.getPsi).collect { case _: PsiErrorElement => () }.isEmpty)

    def assertPreflightBeforeMutation(expectedReason: String, values: Vector[ParserDiagnostic]): Unit =
      val events      = scala.collection.mutable.ArrayBuffer.empty[String]
      val diagnostics = new Iterable[ParserDiagnostic]:
        override def iterator: Iterator[ParserDiagnostic] =
          values.iterator.map(value =>
            events += "diagnostic"
            value
          )
      val result      = DotcPsiProducer.emitClosedFile(
        Scala3DotcParserDefinition.FileNodeType,
        recordingEmitterBuilder(source, events),
        diagnostics
      )
      assertTrue(result.left.exists(_.contains(expectedReason)))
      assertEquals(Vector.fill(values.size)("diagnostic") :+ "mark", events.take(values.size + 1).toVector)

    assertPreflightBeforeMutation("invalid parser diagnostic range", invalid)
    assertPreflightBeforeMutation("crossing parser diagnostic ranges", diagnostics)
    assertTrue(
      DotcPsiProducer
        .validateClosedDiagnostics(Vector(DotcPsiProducer.ClosedDiagnostic(-1, 0, 0, "negative")), source.length)
        .isLeft
    )
    assertTrue(
      DotcPsiProducer
        .validateClosedDiagnostics(Vector(DotcPsiProducer.ClosedDiagnostic(3, 2, 0, "reversed")), source.length)
        .isLeft
    )

  def testFailClosedTreeEmitsDeeplyNestedDiagnosticsWithoutJvmRecursion(): Unit =
    val depth       = 4096
    val source      = "x" * (depth * 2)
    val diagnostics = (0 until depth)
      .map(index =>
        ParserDiagnostic(
          ParserDiagnosticSeverity.Error,
          s"nested-$index",
          Some(ParserDiagnosticPosition(PcSourceRange(index, source.length - index), index))
        )
      )
      .toVector
    val boundaries  =
      diagnostics.flatMap(_.position.toVector.flatMap(value => Vector(value.range.startOffset, value.range.endOffset)))
    val lexer       = PlannedScala3Lexer
      .recovery(source, boundaries)
      .fold(failure => throw new AssertionError(failure.toString), identity)
    val chameleon   = myFixture.configureByText("DeepRecoveredErrors.scala", source).getNode
    val builder     = PsiBuilderFactory
      .getInstance()
      .createBuilder(getProject, chameleon, lexer, Scala3DotcLanguage.INSTANCE, source)

    assertTrue(DotcPsiProducer.emitClosedFile(Scala3DotcParserDefinition.FileNodeType, builder, diagnostics).isRight)
    val tree = builder.getTreeBuilt
    assertEquals(source, tree.getText)
    assertEquals(depth, descendantNodes(tree).map(_.getPsi).count(_.isInstanceOf[PsiErrorElement]))

  def testCompatibilityExpressionKeepsOneExactFlatRangeWithoutBundledInference(): Unit =
    val file       = myFixture.configureByText("PayloadCase.scala", "val value = \"m\"")
    val bundled    = com.intellij.psi.util.PsiTreeUtil
      .findChildrenOfType(file, classOf[ScExpression])
      .stream()
      .filter(_.getText == "\"m\"")
      .findFirst()
      .orElseThrow()
    val composite  = ASTFactory.composite(MetallurgyExpressionPayload.ElementType)
    val leaf       = ASTFactory.leaf(ScalaTokenTypes.tSTRING, "\"m\"").asInstanceOf[TreeElement]
    composite.rawAddChildren(leaf)
    CommandProcessor
      .getInstance()
      .runUndoTransparentAction(() =>
        ApplicationManager.getApplication.runWriteAction(
          new Runnable:
            override def run(): Unit = bundled.getNode.getTreeParent.replaceChild(bundled.getNode, composite)
        )
      )
    val expression = composite.getPsi.asInstanceOf[ScExpression]
    val pointer    = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(expression)

    assertEquals(classOf[MetallurgyExpressionPayload], expression.getClass)
    assertEquals("\"m\"", expression.getText)
    assertEquals(new TextRange(12, 15), expression.getTextRange)
    assertTrue(expression.getChildren.isEmpty)
    assertEquals(1, expression.getNode.getChildren(null).length)
    assertFalse(PsiTreeUtil.findChildrenOfType(expression, classOf[ScExpression]).iterator().hasNext)
    assertTrue(expression.`type`().isLeft)
    assertEquals(expression, pointer.getElement)
    val copied = expression.copy()
    assertEquals(classOf[MetallurgyExpressionPayload], copied.getClass)
    assertEquals(expression.getText, copied.getText)
    assertEquals(expression.getTextRange.getLength, copied.getTextLength)

  def testSyntheticPlanEmitsExactNativeAnnotationSpineAndFlatCompatibilityPayloads(): Unit =
    val source    = "@deprecated(\"m\", \"1\") final"
    val bindings  = NativePsiElementBindings
      .probe(getProject)
      .flatMap(_.bind(Scala3PsiProductionCatalog.Reviewed))
      .fold(error => throw new AssertionError(error), identity)
    val plan      = annotationSpinePlan(source, bindings)
    val lexer     = PlannedScala3Lexer
      .compile(source, plan, bindings)
      .fold(
        failure => throw new AssertionError(failure.toString),
        identity
      )
    val chameleon = myFixture.configureByText("SyntheticAnnotationSpine.scala", source).getNode
    val builder   = PsiBuilderFactory
      .getInstance()
      .createBuilder(getProject, chameleon, lexer, Scala3DotcLanguage.INSTANCE, source)

    assertTrue(DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, builder, plan, bindings))
    val nodes       = descendantNodes(builder.getTreeBuilt)
    val modifiers   = nodes.map(_.getPsi).collectFirst { case value: ScModifierList => value }.get
    val annotations = nodes.map(_.getPsi).collectFirst { case value: ScAnnotations => value }.get
    val annotation  = annotations.getAnnotations.head
    val expression  = annotation.annotationExpr
    val constructor = expression.constructorInvocation
    val arguments   = constructor.args.get
    val payloads    = nodes.map(_.getPsi).collect { case value: MetallurgyExpressionPayload => value }

    assertEquals("final", modifiers.getText)
    assertEquals(new TextRange(22, 27), modifiers.getTextRange)
    assertEquals("@deprecated(\"m\", \"1\")", annotations.getText)
    assertEquals(new TextRange(0, 21), annotations.getTextRange)
    assertEquals(Vector(annotation), annotations.getAnnotations.toVector)
    assertEquals(annotations, annotation.getParent)
    assertEquals("deprecated(\"m\", \"1\")", expression.getText)
    assertEquals(annotation, expression.getParent)
    assertEquals(expression, constructor.getParent)
    assertEquals("deprecated", constructor.typeElement.getText)
    assertEquals(Some(arguments), constructor.args)
    assertEquals("(\"m\", \"1\")", arguments.getText)
    assertEquals(constructor, arguments.getParent)
    assertEquals(Vector("\"m\"", "\"1\""), arguments.exprs.map(_.getText).toVector)
    assertEquals(Vector(new TextRange(12, 15), new TextRange(17, 20)), payloads.map(_.getTextRange))
    assertTrue(payloads.forall(_.getChildren.isEmpty))
    assertTrue(payloads.forall(value => value.getParent == arguments))
    assertTrue(payloads.forall(value => PsiTreeUtil.findChildrenOfType(value, classOf[ScExpression]).isEmpty))
    assertEquals(source, builder.getTreeBuilt.getText)

  def testNativeModifierAnnotationSpineHasExactPhysicalAndPersistenceContracts(): Unit =
    val source      = "@deprecated(\"m\", \"1\") private[scope] final class C"
    val file        = myFixture.configureByText("AnnotationSpineCase.scala", source)
    val modifiers   = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScModifierList])
      .asScala
      .find(_.getText.startsWith("private"))
      .get
    val annotations = PsiTreeUtil.findChildOfType(file, classOf[ScAnnotations], true)
    val annotation  = PsiTreeUtil.findChildOfType(annotations, classOf[ScAnnotation], true)
    val expression  = annotation.annotationExpr
    val constructor = expression.constructorInvocation
    val arguments   = constructor.args.get
    val access      = modifiers.accessModifier.get

    assertEquals("private[scope] final", modifiers.getText)
    assertEquals(new TextRange(source.indexOf("private"), source.indexOf(" class")), modifiers.getTextRange)
    assertEquals(ScalaElementType.MODIFIERS, modifiers.getNode.getElementType)
    assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.base.ScModifierListImpl", modifiers.getClass.getName)
    assertEquals(Vector("private[scope]"), modifiers.getChildren.map(_.getText).toVector)
    assertEquals(Vector("private", "final"), modifiers.modifiersOrdered.map(_.text).toVector)
    assertTrue(modifiers.hasModifierProperty("private"))
    assertTrue(modifiers.hasModifierProperty("final"))

    assertEquals(modifiers.getParent, annotations.getParent)
    assertEquals(Vector(annotation), annotations.getAnnotations.toVector)
    assertEquals(ScalaElementType.ANNOTATIONS, annotations.getNode.getElementType)
    assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.expr.ScAnnotationsImpl", annotations.getClass.getName)
    assertEquals(annotations, annotation.getParent)
    assertEquals("@deprecated(\"m\", \"1\")", annotation.getText)
    assertEquals(new TextRange(0, "@deprecated(\"m\", \"1\")".length), annotation.getTextRange)
    assertEquals(ScalaElementType.ANNOTATION, annotation.getNode.getElementType)
    assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.expr.ScAnnotationImpl", annotation.getClass.getName)
    assertEquals("scala.deprecated", annotation.getQualifiedName)
    assertEquals(expression, annotation.annotationExpr)
    assertEquals(constructor, annotation.constructorInvocation)
    assertEquals(constructor.typeElement, annotation.typeElement)

    assertEquals(annotation, expression.getParent)
    assertEquals("deprecated(\"m\", \"1\")", expression.getText)
    assertEquals(ScalaElementType.ANNOTATION_EXPR, expression.getNode.getElementType)
    assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.expr.ScAnnotationExprImpl", expression.getClass.getName)
    assertEquals(Vector("\"m\"", "\"1\""), expression.getAnnotationParameters.map(_.getText).toVector)
    assertTrue(expression.getAttributes.isEmpty)
    assertEquals(expression, constructor.getParent)
    assertEquals(
      "org.jetbrains.plugins.scala.lang.psi.impl.base.ScConstructorInvocationImpl",
      constructor.getClass.getName
    )
    assertEquals("deprecated", constructor.typeElement.getText)
    assertEquals(Some("deprecated"), constructor.reference.map(_.getText))
    assertEquals(Some(arguments), constructor.args)
    assertEquals(Vector("(\"m\", \"1\")"), constructor.arguments.map(_.getText).toVector)
    assertEquals(constructor, arguments.getParent)
    assertEquals("(\"m\", \"1\")", arguments.getText)
    assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.expr.ScArgumentExprListImpl", arguments.getClass.getName)
    assertEquals(Vector("\"m\"", "\"1\""), arguments.exprs.map(_.getText).toVector)
    assertTrue(arguments.isArgsInParens)
    assertFalse(arguments.isUsing)
    assertEquals(2, arguments.getArgsCount)

    assertEquals(modifiers, access.getParent)
    assertEquals("private[scope]", access.getText)
    assertEquals(ScalaElementType.ACCESS_MODIFIER, access.getNode.getElementType)
    assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.base.ScAccessModifierImpl", access.getClass.getName)
    assertTrue(access.isPrivate)
    assertFalse(access.isProtected)
    assertFalse(access.isThis)
    assertEquals(Some("scope"), access.idText)

    val copied      = file.copy().asInstanceOf[PsiFile]
    val copiedSpine = PsiTreeUtil
      .findChildrenOfType(copied, classOf[ScModifierList])
      .asScala
      .find(_.getText.startsWith("private"))
      .get
    assertEquals(modifiers.getText, copiedSpine.getText)
    assertEquals(modifiers.getClass, copiedSpine.getClass)
    assertEquals(annotation.getClass, PsiTreeUtil.findChildOfType(copied, classOf[ScAnnotation], true).getClass)

    val stubs    = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala
    assertTrue(stubs.exists(_.isInstanceOf[ScModifiersStub]))
    assertTrue(stubs.exists(_.isInstanceOf[ScAccessModifierStub]))
    assertTrue(stubs.exists(_.isInstanceOf[ScAnnotationsStub]))
    assertTrue(stubs.exists(_.isInstanceOf[ScAnnotationStub]))
    val pointer  = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(annotation)
    val document = myFixture.getEditor.getDocument
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.insertString(0, "\n")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("@deprecated(\"m\", \"1\")", pointer.getElement.getText)
    assertEquals(new TextRange(1, 1 + "@deprecated(\"m\", \"1\")".length), pointer.getRange)

    val malformed =
      myFixture.configureByText("MalformedAnnotationSpine.scala", "@deprecated(\"m\", ) private[scope class C")
    assertEquals("@deprecated(\"m\", )", PsiTreeUtil.findChildOfType(malformed, classOf[ScAnnotation], true).getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(malformed, classOf[ScExpression]).asScala.exists(_.getText == "\"m\""))

    val annotationOnly = myFixture.configureByText("AnnotationOnlyCase.scala", "@ann class D")
    val container      = PsiTreeUtil
      .findChildrenOfType(annotationOnly, classOf[ScAnnotations])
      .asScala
      .find(_.getText == "@ann")
      .get
    val emptyModifiers = PsiTreeUtil
      .findChildrenOfType(annotationOnly, classOf[ScModifierList])
      .asScala
      .find(_.getParent == container.getParent)
      .get
    assertEquals("", emptyModifiers.getText)
    assertEquals(new TextRange(4, 4), emptyModifiers.getTextRange)

  def testRejectsTerminalTextMismatchBeforeOpeningMarkers(): Unit =
    val source    = "x"
    val base      = emitterPlan(source, 1)
    val origin    = base.composites.head.instance.origin
    val terminal  = "wildcard"
    val malformed = base.copy(
      physicalLeafOwnership = base.physicalLeafOwnership.map(
        _.copy(
          sourceOwner = origin,
          terminalId = terminal,
          target = TerminalLeafTarget.Token(NativePsiElementBindings.ImportWildcardTokenSurface, Some("*"))
        )
      ),
      targetAssertions = base.targetAssertions :+ PlannedTargetAssertion(
        TargetAssertionOwner.Terminal(origin, terminal),
        PlannedTargetIdentity.TokenRole(
          PsiOutputRoleId.SourceTerminal,
          NativePsiElementBindings.ImportWildcardTokenSurface
        ),
        TargetAssertionKind.Token
      )
    )
    val builder   = recordingEmitterBuilder(source)
    assertFalse(DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, builder, malformed, nativeBindings))
    assertEquals(0, builder.getCurrentOffset)
    assertEquals(
      Left("terminal token text differs from plan"),
      DotcPsiProducer.parseResult(
        Scala3DotcParserDefinition.FileNodeType,
        recordingEmitterBuilder(source),
        malformed,
        nativeBindings
      )
    )

    val mismatchedSurface = malformed.copy(
      physicalLeafOwnership = malformed.physicalLeafOwnership.map(
        _.copy(
          target = TerminalLeafTarget.Token(NativePsiElementBindings.ImportWildcardTokenSurface, Some("x"))
        )
      ),
      targetAssertions = malformed.targetAssertions.map:
        case assertion @ PlannedTargetAssertion(TargetAssertionOwner.Terminal(_, _), _, _) =>
          assertion.copy(targetIdentity =
            PlannedTargetIdentity.TokenRole(
              PsiOutputRoleId.SourceTerminal,
              NativePsiElementBindings.ImportAliasAsTokenSurface
            )
          )
        case assertion                                                                     => assertion
    )
    val surfaceBuilder    = recordingEmitterBuilder(source)
    assertFalse(
      DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, surfaceBuilder, mismatchedSurface, nativeBindings)
    )
    assertEquals(0, surfaceBuilder.getCurrentOffset)

    val outputRoleTarget  = malformed.copy(targetAssertions = malformed.targetAssertions.map:
      case assertion @ PlannedTargetAssertion(TargetAssertionOwner.Terminal(_, _), _, _) =>
        assertion.copy(targetIdentity = PlannedTargetIdentity.OutputRole(PsiOutputRoleId.SourceTerminal))
      case assertion                                                                     => assertion
    )
    val outputRoleBuilder = recordingEmitterBuilder(source)
    assertFalse(
      DotcPsiProducer.parse(
        Scala3DotcParserDefinition.FileNodeType,
        outputRoleBuilder,
        outputRoleTarget,
        nativeBindings
      )
    )
    assertEquals(0, outputRoleBuilder.getCurrentOffset)

  def testOneTerminalTargetContractBindsEveryValidatedOccurrence(): Unit =
    val source   = "*,*"
    val base     = emitterPlan(source, 1)
    val origin   = base.composites.head.instance.origin
    val terminal = "wildcards"
    val target   = TerminalLeafTarget.Token(NativePsiElementBindings.ImportWildcardTokenSurface, Some("*"))
    val plan     = base.copy(
      physicalLeafOwnership = Vector(
        PlannedPhysicalLeaf(atom(1), 0, 1, PhysicalLeafOwner.FileRoot, origin, terminal, target),
        PlannedPhysicalLeaf(
          atom(2),
          1,
          2,
          PhysicalLeafOwner.FileRoot,
          origin,
          "source",
          TerminalLeafTarget.Parent
        ),
        PlannedPhysicalLeaf(atom(3), 2, 3, PhysicalLeafOwner.FileRoot, origin, terminal, target)
      ),
      targetAssertions = base.targetAssertions :+ PlannedTargetAssertion(
        TargetAssertionOwner.Terminal(origin, terminal),
        PlannedTargetIdentity.TokenRole(
          PsiOutputRoleId.SourceTerminal,
          NativePsiElementBindings.ImportWildcardTokenSurface
        ),
        TargetAssertionKind.Token
      )
    )
    assertTrue(PlannedScala3Lexer.compile(source, plan, nativeBindings).isRight)

    val duplicatedContract = plan.copy(targetAssertions = plan.targetAssertions :+ plan.targetAssertions.last)
    val builder            = recordingEmitterBuilder(source)
    assertFalse(
      DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, builder, duplicatedContract, nativeBindings)
    )
    assertEquals(0, builder.getCurrentOffset)

  def testNativeBindingsRejectAnOutputRoleBoundToAnotherHostSurface(): Unit =
    val catalog = Scala3PsiProductionCatalog(
      Scala3PsiProductionCatalog.Reviewed.productions.map {
        case production if production.id == "file-package" =>
          val template = production.effectiveOutputTemplate
          production.copy(outputTemplate = Some(template.copy(composites = template.composites.map:
            case output if output.id == "package" => output.copy(outputRoleId = PsiOutputRoleId.ImportSelector)
            case output                           => output
          )))
        case production                                    => production
      },
      StableRoleInventory.Reviewed
    )
    assertTrue(nativeBindings.validate(catalog).isLeft)

  def testRejectsMissingOrUnexpectedBoundOutputObligationsBeforeOpeningMarkers(): Unit =
    val source      = "x"
    val base        = emitterPlan(source, 1)
    val owner       = base.composites.head.instance
    val accessor    = AccessorObligation("accessor", required = true)
    val persistence = PersistenceObligations.Required("stub", "serializer", Vector("index"), "stub-navigation")
    val contract    =
      NativeOutputContract(packagingSurface, Vector(accessor), persistence, Some(NavigationObligation.Self))
    val bindings    = nativeBindings.copy(outputContracts = Map(PsiOutputRoleId.PackageStatement -> contract))
    val complete    = base.copy(
      accessorAssertions = Vector(PlannedAccessorAssertion(owner, accessor.surfaceId, accessor.required)),
      stubAssertions = Vector(PlannedStubAssertion(owner, "stub", "serializer", Vector("index"), "stub-navigation")),
      navigationAssertions = Vector(PlannedNavigationAssertion(owner, NavigationObligation.Self))
    )
    assertTrue(
      DotcPsiProducer.parse(
        Scala3DotcParserDefinition.FileNodeType,
        recordingEmitterBuilder(source),
        complete,
        bindings
      )
    )
    Vector(
      complete.copy(accessorAssertions = Vector.empty),
      complete.copy(accessorAssertions = complete.accessorAssertions :+ PlannedAccessorAssertion(owner, "extra", true)),
      complete.copy(accessorAssertions = complete.accessorAssertions.map(_.copy(surfaceKind = SurfaceFactKind.Method))),
      complete.copy(stubAssertions = Vector.empty),
      complete.copy(stubAssertions = complete.stubAssertions.map(_.copy(serializerSurfaceId = "wrong"))),
      complete.copy(navigationAssertions = Vector.empty)
    ).foreach: malformed =>
      val builder = recordingEmitterBuilder(source)
      assertFalse(DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, builder, malformed, bindings))
      assertEquals(0, builder.getCurrentOffset)

  def testPlanBackedLexerOwnsExactSourceWithoutBundledLexer(): Unit =
    val source     = "import a.b.{c /* => */ => d}\n"
    val arrowStart = source.lastIndexOf("=>")
    val base       = emitterPlan(source, 1)
    val origin     = base.composites.head.instance.origin
    val plan       = base.copy(physicalLeafOwnership =
      Vector(
        PlannedPhysicalLeaf(
          atom(1),
          0,
          arrowStart,
          PhysicalLeafOwner.FileRoot,
          origin,
          "source",
          TerminalLeafTarget.Parent
        ),
        PlannedPhysicalLeaf(
          atom(2),
          arrowStart,
          arrowStart + 2,
          PhysicalLeafOwner.FileRoot,
          origin,
          "alias",
          TerminalLeafTarget.Token(NativePsiElementBindings.ImportAliasArrowTokenSurface, Some("=>"))
        ),
        PlannedPhysicalLeaf(
          atom(3),
          arrowStart + 2,
          source.length,
          PhysicalLeafOwner.FileRoot,
          origin,
          "source",
          TerminalLeafTarget.Parent
        )
      )
    )
    val lexer      = PlannedScala3Lexer.compile(source, plan, nativeBindings).toOption.get
    lexer.start(source, 0, source.length, 0)
    val observed   = Vector.newBuilder[(String, com.intellij.psi.tree.IElementType)]
    while lexer.getTokenType != null do
      observed += source.substring(lexer.getTokenStart, lexer.getTokenEnd) -> lexer.getTokenType
      lexer.advance()
    val tokens     = observed.result()
    assertEquals(source, tokens.map(_._1).mkString)
    assertEquals(ScalaTokenTypes.kIMPORT, tokens.head._2)
    assertTrue(
      tokens.contains("=>" -> nativeBindings.elementTypes(NativePsiElementBindings.ImportAliasArrowTokenSurface))
    )
    assertTrue(new Scala3DotcParserDefinition().createLexer(getProject).isInstanceOf[PlannedScala3Lexer])

  def testPlanBackedLexerRejectsMalformedTokenTargetsBeforeBuilderConstruction(): Unit =
    val source        = "x"
    val base          = emitterPlan(source, 1)
    val leaf          = base.physicalLeafOwnership.head.copy(
      target = TerminalLeafTarget.Token(NativePsiElementBindings.ImportWildcardTokenSurface)
    )
    val invalidRanges = Vector(leaf.copy(end = 0), leaf.copy(start = -1), leaf.copy(end = 2))
    assertEquals(
      Vector(
        LexerPlanFailure.InvalidTargetRange(0, 0, 1),
        LexerPlanFailure.InvalidTargetRange(-1, 1, 1),
        LexerPlanFailure.InvalidTargetRange(0, 2, 1)
      ),
      invalidRanges.map(value =>
        PlannedScala3Lexer
          .compile(source, base.copy(physicalLeafOwnership = Vector(value)), nativeBindings)
          .left
          .toOption
          .get
      )
    )
    assertEquals(
      Some(LexerPlanFailure.DuplicateTargetStart(0)),
      PlannedScala3Lexer
        .compile(source, base.copy(physicalLeafOwnership = Vector(leaf, leaf.copy(atomId = atom(2)))), nativeBindings)
        .left
        .toOption
    )
    assertEquals(
      Some(LexerPlanFailure.UnsupportedTargetSurface("missing")),
      PlannedScala3Lexer
        .compile(
          source,
          base.copy(physicalLeafOwnership = Vector(leaf.copy(target = TerminalLeafTarget.Token("missing")))),
          nativeBindings
        )
        .left
        .toOption
    )
    assertEquals(
      Some(LexerPlanFailure.LexicalContractMismatch),
      PlannedScala3Lexer
        .compile(
          source,
          base.copy(lexicalContract = ClosedSourceLexicalContract.from(source + " ")),
          nativeBindings
        )
        .left
        .toOption
    )
    val unsafeSource  = "ab"
    val unsafeBase    = emitterPlan(unsafeSource, 1)
    val unsafeLeaf    = unsafeBase.physicalLeafOwnership.head.copy(
      end = 1,
      target = TerminalLeafTarget.Token(NativePsiElementBindings.ImportWildcardTokenSurface)
    )
    assertEquals(
      Some(LexerPlanFailure.UnsafeTargetBoundary(0, 1)),
      PlannedScala3Lexer
        .compile(unsafeSource, unsafeBase.copy(physicalLeafOwnership = Vector(unsafeLeaf)), nativeBindings)
        .left
        .toOption
    )
    val overlapSource = "x y"
    val overlapBase   = emitterPlan(overlapSource, 1)
    val first         = overlapBase.physicalLeafOwnership.head.copy(
      end = 3,
      target = TerminalLeafTarget.Token(NativePsiElementBindings.ImportWildcardTokenSurface)
    )
    val second        = first.copy(atomId = atom(2), start = 2)
    assertEquals(
      Some(LexerPlanFailure.OverlappingTargetRanges(0, 3, 2, 3)),
      PlannedScala3Lexer
        .compile(overlapSource, overlapBase.copy(physicalLeafOwnership = Vector(first, second)), nativeBindings)
        .left
        .toOption
    )

  def testAcceptsTwoOrderedForestRootsAndRejectsOverlapBeforeMarkers(): Unit =
    val source   = "xy"
    val base     = emitterPlan(source, 1)
    val first    = base.composites.head.copy(range = PcSourceRange(0, 1))
    val secondId = CompositeInstanceId(ProductionInstanceId(InventoryKind.Node, 2L, None), "self")
    val second   = first.copy(instance = secondId, range = PcSourceRange(1, 2))
    val forest   = base.copy(
      physicalLeafOwnership = Vector(
        PlannedPhysicalLeaf(
          atom(1),
          0,
          1,
          PhysicalLeafOwner.FileRoot,
          first.instance.origin,
          "source",
          TerminalLeafTarget.Parent
        ),
        PlannedPhysicalLeaf(
          atom(2),
          1,
          2,
          PhysicalLeafOwner.FileRoot,
          second.instance.origin,
          "source",
          TerminalLeafTarget.Parent
        )
      ),
      composites = Vector(second, first),
      targetAssertions = Vector(first.instance, second.instance).map(id =>
        PlannedTargetAssertion(
          TargetAssertionOwner.Composite(id),
          PlannedTargetIdentity.OutputRole(packagingRole),
          TargetAssertionKind.NativeComposite
        )
      )
    )
    assertTrue(
      DotcPsiProducer.parse(
        Scala3DotcParserDefinition.FileNodeType,
        recordingEmitterBuilder(source),
        forest,
        nativeBindings
      )
    )

    val malformed = forest.copy(composites = Vector(first.copy(range = PcSourceRange(0, 2)), second))
    val builder   = recordingEmitterBuilder(source)
    assertFalse(DotcPsiProducer.parse(Scala3DotcParserDefinition.FileNodeType, builder, malformed, nativeBindings))
    assertEquals(0, builder.getCurrentOffset)

  private def recordingEmitterBuilder(
      source: String,
      events: scala.collection.mutable.ArrayBuffer[String] = scala.collection.mutable.ArrayBuffer.empty
  ): PsiBuilder =
    val offset = new AtomicInteger(0)
    val marker = Proxy
      .newProxyInstance(
        getClass.getClassLoader,
        Array(classOf[PsiBuilder.Marker]),
        (_: Object, _: Method, _: Array[Object]) => null
      )
      .asInstanceOf[PsiBuilder.Marker]
    Proxy
      .newProxyInstance(
        getClass.getClassLoader,
        Array(classOf[PsiBuilder]),
        new InvocationHandler:
          override def invoke(proxy: Object, method: Method, arguments: Array[Object]): Object =
            method.getName match
              case "getOriginalText"   => source
              case "rawLookup"         =>
                if arguments(0).asInstanceOf[Int] < source.length then ScalaElementType.PACKAGING else null
              case "rawTokenTypeStart" => Integer.valueOf(arguments(0).asInstanceOf[Int])
              case "mark"              => events += "mark"; marker
              case "eof"               => java.lang.Boolean.valueOf(offset.get() >= source.length)
              case "getCurrentOffset"  => Integer.valueOf(offset.get())
              case "advanceLexer"      => events += "advance"; offset.incrementAndGet(); null
              case "toString"          => "recording emitter builder"
              case "hashCode"          => Integer.valueOf(System.identityHashCode(proxy))
              case "equals"            => java.lang.Boolean.valueOf(proxy eq arguments(0))
              case _                   => throw new UnsupportedOperationException(method.toString)
      )
      .asInstanceOf[PsiBuilder]

  private def nativeBindings: NativePsiElementBindings =
    NativePsiElementBindings
      .probe(getProject)
      .fold(error => throw new AssertionError(error), identity)
      .copy(outputContracts =
        Map(
          PsiOutputRoleId.PackageStatement -> NativeOutputContract(
            packagingSurface,
            Vector.empty,
            PersistenceObligations.NotApplicable,
            None
          )
        )
      )

  private def emitterPlan(source: String, depth: Int): WholeFileProductionPlan =
    val origins    = Vector.tabulate(depth)(index => ProductionInstanceId(InventoryKind.Node, index + 1L, None))
    val ids        = origins.map(CompositeInstanceId(_, "self"))
    val position   = PcSourceRange(0, source.length)
    val composites = ids.zipWithIndex.map: (id, index) =>
      val children = ids
        .lift(index + 1)
        .toVector
        .map: child =>
          PlannedChild("child", Vector.empty, child)
      PlannedComposite(id, "test", position, children, Vector.empty)
    WholeFileProductionPlan(
      ParserSourceUri.from("file:///EmitterCase.scala").toOption.get,
      ParserSyntaxSnapshot.digest(source),
      "test",
      ClosedSourceLexicalContract.from(source),
      Vector(
        PlannedPhysicalLeaf(
          atom(1),
          0,
          source.length,
          PhysicalLeafOwner.Composite(ids.last),
          ids.last.origin,
          "source",
          TerminalLeafTarget.Parent
        )
      ),
      Vector.empty,
      Vector.empty,
      composites,
      ids.map(id =>
        PlannedTargetAssertion(
          TargetAssertionOwner.Composite(id),
          PlannedTargetIdentity.OutputRole(packagingRole),
          TargetAssertionKind.NativeComposite
        )
      ),
      Vector.empty,
      Vector.empty,
      Vector.empty
    )

  private def annotationSpinePlan(
      source: String,
      bindings: NativePsiElementBindings
  ): WholeFileProductionPlan =
    def id(value: Long, local: String)                  =
      CompositeInstanceId(ProductionInstanceId(InventoryKind.Node, value, None), local)
    val modifier                                        = id(1, "modifier")
    val annotations                                     = id(2, "annotations")
    val annotation                                      = id(3, "annotation")
    val expression                                      = id(4, "expression")
    val constructor                                     = id(5, "constructor")
    val designator                                      = id(6, "designator")
    val reference                                       = id(7, "reference")
    val arguments                                       = id(8, "arguments")
    val firstPayload                                    = id(9, "first-payload")
    val nextPayload                                     = id(10, "next-payload")
    def child(role: String, value: CompositeInstanceId) = PlannedChild(role, Vector.empty, value)
    val composites                                      = Vector(
      PlannedComposite(modifier, "synthetic-modifiers", PcSourceRange(22, 27), Vector.empty, Vector.empty),
      PlannedComposite(
        annotations,
        "synthetic-annotations",
        PcSourceRange(0, 21),
        Vector(child("annotation", annotation)),
        Vector.empty
      ),
      PlannedComposite(
        annotation,
        "synthetic-annotation",
        PcSourceRange(0, 21),
        Vector(child("expression", expression)),
        Vector.empty
      ),
      PlannedComposite(
        expression,
        "synthetic-annotation-expression",
        PcSourceRange(1, 21),
        Vector(child("constructor", constructor)),
        Vector.empty
      ),
      PlannedComposite(
        constructor,
        "synthetic-constructor",
        PcSourceRange(1, 21),
        Vector(child("designator", designator), child("arguments", arguments)),
        Vector.empty
      ),
      PlannedComposite(
        designator,
        "synthetic-designator",
        PcSourceRange(1, 11),
        Vector(child("reference", reference)),
        Vector.empty
      ),
      PlannedComposite(reference, "synthetic-reference", PcSourceRange(1, 11), Vector.empty, Vector.empty),
      PlannedComposite(
        arguments,
        "synthetic-arguments",
        PcSourceRange(11, 21),
        Vector(child("argument", firstPayload), child("argument", nextPayload)),
        Vector.empty
      ),
      PlannedComposite(firstPayload, "synthetic-payload", PcSourceRange(12, 15), Vector.empty, Vector.empty),
      PlannedComposite(nextPayload, "synthetic-payload", PcSourceRange(17, 20), Vector.empty, Vector.empty)
    )
    val roles                                           = Vector(
      modifier     -> PsiOutputRoleId.ModifierList,
      annotations  -> PsiOutputRoleId.Annotations,
      annotation   -> PsiOutputRoleId.Annotation,
      expression   -> PsiOutputRoleId.AnnotationExpr,
      constructor  -> PsiOutputRoleId.ConstructorInvocation,
      designator   -> PsiOutputRoleId.SimpleType,
      reference    -> PsiOutputRoleId.StableReference,
      arguments    -> PsiOutputRoleId.AnnotationArguments,
      firstPayload -> PsiOutputRoleId.ExpressionPayload,
      nextPayload  -> PsiOutputRoleId.ExpressionPayload
    )
    val accessors                                       = roles.flatMap: (instance, role) =>
      bindings
        .outputContracts(role)
        .accessors
        .map(obligation =>
          PlannedAccessorAssertion(instance, obligation.surfaceId, obligation.required, obligation.surfaceKind)
        )
    val stubs                                           = roles.flatMap: (instance, role) =>
      bindings.outputContracts(role).persistence match
        case PersistenceObligations.NotApplicable                                   => None
        case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
          Some(PlannedStubAssertion(instance, stub, serializer, indices, navigation))
    val navigation                                      = roles.flatMap: (instance, role) =>
      bindings.outputContracts(role).navigation.map(PlannedNavigationAssertion(instance, _))
    WholeFileProductionPlan(
      ParserSourceUri.from("file:///SyntheticAnnotationSpine.scala").toOption.get,
      ParserSyntaxSnapshot.digest(source),
      "synthetic-annotation-spine",
      ClosedSourceLexicalContract.from(source),
      Vector(
        PlannedPhysicalLeaf(
          atom(1),
          0,
          source.length,
          PhysicalLeafOwner.FileRoot,
          annotation.origin,
          "source",
          TerminalLeafTarget.Parent
        )
      ),
      Vector.empty,
      Vector.empty,
      composites,
      roles.map: (instance, role) =>
        PlannedTargetAssertion(
          TargetAssertionOwner.Composite(instance),
          PlannedTargetIdentity.OutputRole(role),
          if role == PsiOutputRoleId.ExpressionPayload then TargetAssertionKind.CompatibleComposite
          else TargetAssertionKind.NativeComposite
        ),
      accessors,
      stubs,
      navigation
    )

  private def descendantNodes(root: ASTNode): Vector[ASTNode] =
    val result  = Vector.newBuilder[ASTNode]
    val pending = collection.mutable.Stack.from(root.getChildren(null).reverse)
    while pending.nonEmpty do
      val current = pending.pop()
      result += current
      current.getChildren(null).reverseIterator.foreach(pending.push)
    result.result()

  private def atom(id: Long): SourceAtomId = SourceAtomId(id, 0)
