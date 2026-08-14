package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import org.junit.Assert.*
import org.junit.Test

final class Scala3ApplicationExpressionContractTest extends Scala3ApplicationExpressionContractTests

private[psiproducer] trait Scala3ApplicationExpressionContractTests extends Scala3PsiProductionCatalogTestSupport:
  private val CandidateRealization = "ordinary-application-child"
  private val PayloadRole          = PsiOutputRoleId.ExpressionPayload

  @Test def ordinaryApplicationFixtureDeclaresTheClosedNonPhysicalContract(): Unit =
    val production = applicationProduction
    assertEquals("ordinary-application-candidate", production.id)
    assertEquals(GrammarRoleId.OrdinaryApplication, production.grammarRoleId)
    assertEquals("Apply", production.pattern.prefix)
    assertEquals(Vector("fun", "args"), production.pattern.fields.map(_.name))
    assertEquals(
      Vector(CatalogValuePattern.NodeExceptPrefix("TypeApply"), CatalogValuePattern.Repeated(CatalogValuePattern.Node)),
      production.pattern.fields.map(_.value)
    )
    assertEquals(4, directOccurrences.size)
    assertEquals(directOccurrences ++ recursiveOccurrences, production.pattern.occurrences)
    assertEquals(Vector("callee", "arguments"), production.children.map(_.roleId))
    assertEquals(ChildCardinality.ExactlyOne, production.children.head.cardinality)
    assertEquals(ChildCardinality.Repeated(0, None), production.children.last.cardinality)
    assertEquals(
      Set("ordinary-application-candidate", "ordinary-callee-reference", "ordinary-callee-selection"),
      production.children.head.productionIds
    )
    assertEquals(
      Set(
        "ordinary-application-candidate",
        "ordinary-argument-reference",
        "ordinary-argument-selection",
        "ordinary-argument-literal",
        "ordinary-argument-this"
      ),
      production.children.last.productionIds
    )
    assertEquals(
      Vector(
        FutureApplicationTerminalSelector.OpeningAfterCallee,
        FutureApplicationTerminalSelector.ClosingAtRootEnd,
        FutureApplicationTerminalSelector.ArgumentSeparatorAtRootDepth
      ),
      futureNativeTerminals.map(_.selector)
    )
    assertEquals(Vector(TerminalIntervalSelector.WholeProduction), production.terminals.map(_.selector))
    val fallback   = production.effectiveOutputRealizations.single
    assertEquals(CandidateRealization, fallback.id)
    assertEquals(Vector(PayloadRole), fallback.template.composites.map(_.outputRoleId))
    assertEquals(Map("callee" -> None, "arguments" -> None), fallback.template.childMounts)
    assertEquals(
      Vector(
        ChildClosureAbsorption(
          "callee",
          ChildRootOutcome.One(ChildOutcomeExpectation.Realization(CandidateRealization))
        ),
        ChildClosureAbsorption(
          "arguments",
          ChildRootOutcome.All(ChildOutcomeExpectation.Realization(CandidateRealization))
        )
      ),
      fallback.childClosureAbsorptions
    )
    assertFalse(
      production.effectiveOutputRealizations.exists(
        _.template.composites.exists(_.outputRoleId.value.contains("method-call"))
      )
    )
    val rows       = Scala3PsiProductionCatalog.catalogPlanStructure(fixtureCatalog).rows
    assertTrue(rows.contains("grammar-role\tscala.expression.application.ordinary"))
    assertTrue(rows.exists(_.startsWith("production\t0\tordinary-application-candidate\tNode\tApply")))
    assertEquals(
      Vector(
        "future-native-terminal\t0\tOpeningAfterCallee\tExactlyOne",
        "future-native-terminal\t1\tClosingAtRootEnd\tExactlyOne",
        "future-native-terminal\t2\tArgumentSeparatorAtRootDepth\tRepeated(0,None)"
      ),
      futureNativeTerminals.zipWithIndex.map((terminal, index) =>
        StructuralRows.row("future-native-terminal", index, terminal.selector, terminal.cardinality)
      )
    )

  @Test def fallbackAbsorbsZeroOneManyAndNestedChildrenButRejectsAMiddleFailure(): Unit =
    Vector(0, 1, 3).foreach: argumentCount =>
      val graph  = applicationGraph(argumentCount)
      val result = plan(graph).fold(error => throw new AssertionError(error.toString), identity)
      assertEquals(Vector(graph.parent), result.retained)
      assertEquals(2, result.absorptions.size)
      assertEquals(Vector(graph.callee), result.absorptions.head.roots)
      assertEquals(graph.arguments, result.absorptions.last.roots)
      assertEquals((graph.callee +: graph.arguments).map(_ -> graph.parent).toMap, result.absorbedBy)
      assertEquals(Vector(PayloadRole), graph.realizations(graph.parent).template.composites.map(_.outputRoleId))

    val failed       = applicationGraph(3)
    val middle       = failed.arguments(1)
    val wrongOutcome = childRealization("unsupported-child")
    val failure      = plan(failed.copy(realizations = failed.realizations.updated(middle, wrongOutcome))).left.toOption.get
    assertEquals(
      ProductionParticipationFailure.ChildRootCount(
        failed.parent,
        CandidateRealization,
        "arguments",
        middle,
        1
      ),
      failure
    )

    val nested     = applicationGraph(1)
    val argument   = nested.arguments.head
    val grandchild = instance(20)
    val graph      = nested.copy(
      active = nested.active :+ grandchild,
      selected = nested.selected
        .updated(
          argument,
          childProduction(
            "ordinary-application-candidate",
            Vector(
              ChildDeclaration("nested", "args", ChildCardinality.Repeated(0, None), "ordinary-argument-reference")
            )
          )
        )
        .updated(grandchild, childProduction("ordinary-argument-reference")),
      children = nested.children.updated(
        argument,
        Vector(
          (
            "nested",
            Vector(ParserFieldPathSegment.NamedField("args"), ParserFieldPathSegment.RepeatedIndex(0)),
            grandchild
          )
        )
      ),
      realizations = nested.realizations.updated(grandchild, childRealization()),
      positions = nested.positions.updated(grandchild, positioned(2, 3))
    )
    val nestedPlan = plan(graph).fold(error => throw new AssertionError(error.toString), identity)
    assertEquals(Vector(argument, grandchild), nestedPlan.absorptions.last.closure)

  @Test def reviewedRuntimeCatalogOwnsOrdinaryApplicationsWithoutChangingPersistence(): Unit =
    val catalog     = Scala3PsiProductionCatalog.catalogPlanStructure(Scala3PsiProductionCatalog.Reviewed)
    val persistence = Scala3PsiProductionCatalog.persistedSchemaStructure(
      Scala3PsiProductionCatalog.Reviewed,
      Scala3DotcFileElementType.SchemaVersion,
      Scala3DotcFileElementType.ExternalId
    )
    val application = Scala3PsiProductionCatalog.Reviewed.productions.find(_.id == "ordinary-application-candidate").get
    val attachment  = AttachmentEvidence("KindOfApply", ParserAttachmentValue.Product("Using"))
    assertEquals(
      Vector(
        Vector(EvidenceCondition.RootAttachment(attachment, present = false)),
        Vector(EvidenceCondition.RootAttachment(attachment, present = true)),
        Vector.empty
      ),
      application.effectiveOutputRealizations.map(_.evidenceConditions)
    )
    assertEquals(
      2,
      catalog.rows.count(row =>
        row.contains("ordinary-application-candidate") &&
          row.contains("RootAttachment(AttachmentEvidence(KindOfApply,Product(Using))")
      )
    )
    assertEquals("fbbb0da749c75700d91998bb34762abfc8050e89863e8a0ed44d1f9988ecf45e", catalog.fingerprint)
    assertEquals("6c513793137193022cbf2ffd5a1b90d364534b5c8ccc8e04dcdf162d1aae7a4a", persistence.fingerprint)
    assertTrue(
      Scala3PsiProductionCatalog.Reviewed.stableRoles.grammarRoles.contains(GrammarRoleId.OrdinaryApplication)
    )
    assertTrue(
      Scala3PsiProductionCatalog.Reviewed.productions.exists(
        _.grammarRoleIds.contains(GrammarRoleId.OrdinaryApplication)
      )
    )

  private final case class ApplicationGraph(
      parent: ProductionInstanceId,
      callee: ProductionInstanceId,
      arguments: Vector[ProductionInstanceId],
      active: Vector[ProductionInstanceId],
      selected: Map[ProductionInstanceId, Scala3PsiProduction],
      children: Map[ProductionInstanceId, Vector[(String, Vector[ParserFieldPathSegment], ProductionInstanceId)]],
      realizations: Map[ProductionInstanceId, OutputRealization],
      positions: Map[ProductionInstanceId, ParserNodePosition]
  )

  private def applicationGraph(argumentCount: Int): ApplicationGraph =
    val parent    = instance(1)
    val callee    = instance(2)
    val arguments = Vector.tabulate(argumentCount)(index => instance(index + 3))
    val active    = parent +: callee +: arguments
    ApplicationGraph(
      parent,
      callee,
      arguments,
      active,
      Map(parent -> applicationProduction, callee -> childProduction("ordinary-callee-reference")) ++
        arguments.map(_ -> childProduction("ordinary-argument-reference")),
      Map(
        parent -> (Vector(("callee", Vector(ParserFieldPathSegment.NamedField("fun")), callee)) ++
          arguments.zipWithIndex.map((argument, index) =>
            (
              "arguments",
              Vector(ParserFieldPathSegment.NamedField("args"), ParserFieldPathSegment.RepeatedIndex(index)),
              argument
            )
          ))
      ),
      Map(parent -> applicationProduction.effectiveOutputRealizations.single, callee -> childRealization()) ++
        arguments.map(_ -> childRealization()),
      Map(parent -> positioned(0, argumentCount + 3), callee -> positioned(0, 1)) ++
        arguments.zipWithIndex.map((argument, index) => argument -> positioned(index + 1, index + 2))
    )

  private def plan(graph: ApplicationGraph) = ProductionParticipationPlanner.plan(
    graph.active,
    graph.selected,
    graph.children,
    graph.realizations,
    graph.positions
  )

  private def applicationProduction: Scala3PsiProduction =
    val calleeIds   = Vector("ordinary-callee-reference", "ordinary-callee-selection", "ordinary-application-candidate")
    val argumentIds = Vector(
      "ordinary-argument-reference",
      "ordinary-argument-selection",
      "ordinary-argument-literal",
      "ordinary-argument-this",
      "ordinary-application-candidate"
    )
    Scala3PsiProduction(
      id = "ordinary-application-candidate",
      grammarRoleId = GrammarRoleId.OrdinaryApplication,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Apply",
        Vector(
          CompilerFieldPattern("fun", CatalogValuePattern.NodeExceptPrefix("TypeApply")),
          CompilerFieldPattern("args", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
        ),
        directOccurrences ++ recursiveOccurrences
      ),
      dispositions = Vector(
        FieldDisposition("fun", FieldDispositionKind.Child),
        FieldDisposition("args", FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration("callee", "fun", ChildCardinality.ExactlyOne, calleeIds.head, calleeIds.tail.toSet),
        ChildDeclaration(
          "arguments",
          "args",
          ChildCardinality.Repeated(0, None),
          argumentIds.head,
          argumentIds.tail.toSet
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "payload",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = Scala3PsiProductionSupport.ExpressionPayloadSurface,
      targetRequirement = TargetRequirement.Compatible,
      accessors = Vector.empty,
      persistence = PersistenceObligations.NotApplicable,
      navigation = None,
      outputRealizations = Vector(
        OutputRealization(
          CandidateRealization,
          Vector.empty,
          LocalOutputCompositeTemplate(Vector(payloadComposite), Map("callee" -> None, "arguments" -> None)),
          childClosureAbsorptions = Vector(
            ChildClosureAbsorption(
              "callee",
              ChildRootOutcome.One(ChildOutcomeExpectation.Realization(CandidateRealization))
            ),
            ChildClosureAbsorption(
              "arguments",
              ChildRootOutcome.All(ChildOutcomeExpectation.Realization(CandidateRealization))
            )
          )
        )
      ),
      outputRoleId = None
    )

  private def fixtureCatalog: Scala3PsiProductionCatalog =
    val children    = Vector(
      "ordinary-callee-reference",
      "ordinary-callee-selection",
      "ordinary-argument-reference",
      "ordinary-argument-selection",
      "ordinary-argument-literal",
      "ordinary-argument-this"
    ).map(childProduction(_))
    val productions = applicationProduction +: children
    Scala3PsiProductionCatalog(productions, focusedRoleInventory(productions))

  private def childProduction(id: String, children: Vector[ChildDeclaration] = Vector.empty): Scala3PsiProduction =
    Scala3PsiProduction(
      id,
      GrammarRoleId.OutputFreeExpression,
      CompilerProductionPattern(
        InventoryKind.Node,
        id,
        Vector.empty,
        Vector(CompilerProductionContextPattern(ContextPattern.Root, SourceClassification.SourceReachable))
      ),
      Vector.empty,
      children,
      Vector.empty,
      Vector(LayoutAlternative.None),
      RecoveryPolicy.Reject,
      Scala3PsiProductionSupport.ExpressionPayloadSurface,
      TargetRequirement.Compatible,
      Vector.empty,
      PersistenceObligations.NotApplicable,
      outputRoleId = Some(PayloadRole)
    )

  private def childRealization(id: String = CandidateRealization): OutputRealization =
    OutputRealization(id, Vector.empty, LocalOutputCompositeTemplate(Vector(payloadComposite), Map.empty))

  private def payloadComposite = OutputCompositeDeclaration(
    "payload",
    None,
    OutputRangeDeclaration.CompilerPosition,
    PayloadRole,
    Scala3PsiProductionSupport.ExpressionPayloadSurface,
    TargetRequirement.Compatible,
    Vector.empty,
    PersistenceObligations.NotApplicable,
    None
  )

  private def directOccurrences = Vector("DefDef", "ValDef").flatMap: owner =>
    Vector("PackageDef" -> "stats", "Template" -> "preBody").map: (outer, field) =>
      CompilerProductionContextPattern(
        ContextPattern.ParentWithAncestor(
          InventoryKind.Node,
          owner,
          Vector(CatalogPathSegment.NamedField("preRhs")),
          InventoryAncestor(
            InventoryKind.Node,
            outer,
            Vector(CatalogPathSegment.NamedField(field), CatalogPathSegment.RepeatedElement)
          )
        ),
        SourceClassification.SourceReachable
      )

  private def recursiveOccurrences = Vector(
    Vector(CatalogPathSegment.NamedField("fun")),
    Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
  ).map: path =>
    CompilerProductionContextPattern(
      ContextPattern.Parent(InventoryKind.Node, "Apply", path),
      SourceClassification.SourceReachable
    )

  private def futureNativeTerminals = Vector(
    FutureApplicationTerminal(
      "left-parenthesis",
      FutureApplicationTerminalSelector.OpeningAfterCallee,
      OccurrenceCardinality.ExactlyOne
    ),
    FutureApplicationTerminal(
      "right-parenthesis",
      FutureApplicationTerminalSelector.ClosingAtRootEnd,
      OccurrenceCardinality.ExactlyOne
    ),
    FutureApplicationTerminal(
      "commas",
      FutureApplicationTerminalSelector.ArgumentSeparatorAtRootDepth,
      OccurrenceCardinality.Repeated(0, None)
    )
  )

  private enum FutureApplicationTerminalSelector:
    case OpeningAfterCallee, ClosingAtRootEnd, ArgumentSeparatorAtRootDepth

  private final case class FutureApplicationTerminal(
      id: String,
      selector: FutureApplicationTerminalSelector,
      cardinality: OccurrenceCardinality
  )

  private def instance(id: Long) = ProductionInstanceId(InventoryKind.Node, id, None)

  private def positioned(start: Int, end: Int): ParserNodePosition =
    ParserNodePosition.Positioned(PcSourceRange(start, end), start, ParserPositionProvenance.SourceDerived)

  extension [A](values: Vector[A])
    private def single: A =
      assertEquals(1, values.size)
      values.head
