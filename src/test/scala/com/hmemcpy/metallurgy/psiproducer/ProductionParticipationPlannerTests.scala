package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import org.junit.Assert.*
import org.junit.Test

private[psiproducer] trait ProductionParticipationPlannerTests extends Scala3PsiProductionCatalogTestSupport:
  private val ParentRole = GrammarRoleId("test.grammar.absorbing-parent")
  private val ChildRole  = GrammarRoleId("test.grammar.absorbed-child")
  private val ParentOut  = PsiOutputRoleId("test.output.absorbing-parent")
  private val ChildOut   = PsiOutputRoleId("test.output.absorbed-child")

  @Test def allRootOutcomeAcceptsZeroOneAndManyChildrenInSourceOrder(): Unit =
    Vector(0, 1, 3).foreach: count =>
      val graph         = graphWithChildren(
        count,
        ChildCardinality.Repeated(0, None),
        ChildRootOutcome.All(
          ChildOutcomeExpectation.OutputRole(ChildOut)
        )
      )
      val result        = ProductionParticipationPlanner.plan(
        graph.active,
        graph.selected,
        graph.children,
        graph.realizations,
        graph.position
      )
      assertTrue(result.left.toOption.toString, result.isRight)
      val participation = result.toOption.get
      assertEquals(Vector(graph.parent), participation.retained)
      assertEquals(graph.childrenOfParent, participation.absorptions.single.roots)
      assertEquals(graph.childrenOfParent, participation.absorptions.single.closure)
      assertEquals(graph.childrenOfParent.map(_ -> graph.parent).toMap, participation.absorbedBy)

  @Test def allRootOutcomeRejectsMiddleFailureDuplicateRootMissingRootAndScalarMisuse(): Unit =
    val base   = graphWithChildren(
      3,
      ChildCardinality.Repeated(0, None),
      ChildRootOutcome.All(ChildOutcomeExpectation.OutputRole(ChildOut))
    )
    val middle = base.childrenOfParent(1)
    Vector(
      Vector.empty,
      Vector(root("a"), root("b")),
      Vector(root("expected"), root("unrelated", ParentOut))
    )
      .zip(Vector(0, 2, 2))
      .foreach: (roots, actual) =>
        val changed = base.copy(realizations =
          base.realizations.updated(
            middle,
            realization("child", roots)
          )
        )
        assertEquals(
          Left(
            ProductionParticipationFailure.ChildRootCount(
              base.parent,
              "parent",
              "children",
              middle,
              actual
            )
          ),
          ProductionParticipationPlanner.plan(
            changed.active,
            changed.selected,
            changed.children,
            changed.realizations,
            changed.position
          )
        )

    val scalar = graphWithChildren(
      1,
      ChildCardinality.ExactlyOne,
      ChildRootOutcome.All(ChildOutcomeExpectation.OutputRole(ChildOut))
    )
    assertTrue(
      ProductionParticipationPlanner
        .plan(scalar.active, scalar.selected, scalar.children, scalar.realizations, scalar.position)
        .left
        .toOption
        .exists(_.isInstanceOf[ProductionParticipationFailure.RepeatedRootOutcomeMisuse])
    )

  @Test def oneRootOutcomeAcceptsExactlyOneScalarChildAndRejectsRepeatedUse(): Unit =
    val one      = graphWithChildren(
      1,
      ChildCardinality.ExactlyOne,
      ChildRootOutcome.One(ChildOutcomeExpectation.Realization("child"))
    )
    assertTrue(
      ProductionParticipationPlanner
        .plan(one.active, one.selected, one.children, one.realizations, one.position)
        .isRight
    )
    val repeated = graphWithChildren(
      1,
      ChildCardinality.Repeated(1, None),
      ChildRootOutcome.One(ChildOutcomeExpectation.Realization("child"))
    )
    assertTrue(
      ProductionParticipationPlanner
        .plan(repeated.active, repeated.selected, repeated.children, repeated.realizations, repeated.position)
        .left
        .toOption
        .exists(_.isInstanceOf[ProductionParticipationFailure.ScalarRootOutcomeMisuse])
    )

  @Test def absorptionClosesNestedChildrenAndRejectsSharedCyclesOverlapsAndWrongRanges(): Unit =
    val base       = graphWithChildren(
      1,
      ChildCardinality.ExactlyOne,
      ChildRootOutcome.One(ChildOutcomeExpectation.Production("child"))
    )
    val child      = base.childrenOfParent.single
    val grandchild = instance(20)
    val nested     = base.copy(
      active = base.active :+ grandchild,
      selected = base.selected
        .updated(
          child,
          production(
            "child",
            Vector(ChildDeclaration("nested", "nested", ChildCardinality.ExactlyOne, "grandchild"))
          )
        )
        .updated(grandchild, production("grandchild", Vector.empty)),
      children = base.children
        .updated(child, Vector(("nested", Vector(ParserFieldPathSegment.NamedField("nested")), grandchild))),
      realizations = base.realizations + (grandchild -> realization("grandchild", Vector(root("grandchild")))),
      ranges = base.ranges + (grandchild             -> PcSourceRange(1, 2))
    )
    val planned    = ProductionParticipationPlanner
      .plan(nested.active, nested.selected, nested.children, nested.realizations, nested.position)
      .toOption
      .get
    assertEquals(Vector(child, grandchild), planned.absorptions.single.closure)
    assertEquals(Vector(base.parent), planned.retained)

    val siblings       = graphWithChildren(
      2,
      ChildCardinality.Repeated(0, None),
      ChildRootOutcome.All(ChildOutcomeExpectation.Production("child"))
    )
    val first          = siblings.childrenOfParent(0)
    val second         = siblings.childrenOfParent(1)
    val firstNested    = instance(21)
    val secondNested   = instance(22)
    val nestedSibling  = ChildDeclaration("nested", "nested", ChildCardinality.ExactlyOne, "nested")
    val nestedSiblings = siblings.copy(
      active = siblings.active ++ Vector(firstNested, secondNested),
      selected = siblings.selected
        .updated(first, production("child", Vector(nestedSibling)))
        .updated(second, production("child", Vector(nestedSibling)))
        .updated(firstNested, production("nested", Vector.empty))
        .updated(secondNested, production("nested", Vector.empty)),
      children = siblings.children
        .updated(first, Vector(("nested", Vector(ParserFieldPathSegment.NamedField("nested")), firstNested)))
        .updated(second, Vector(("nested", Vector(ParserFieldPathSegment.NamedField("nested")), secondNested))),
      realizations = siblings.realizations ++ Map(
        firstNested  -> realization("nested", Vector(root("first-nested"))),
        secondNested -> realization("nested", Vector(root("second-nested")))
      ),
      ranges = siblings.ranges ++ Map(firstNested -> PcSourceRange(0, 1), secondNested -> PcSourceRange(1, 2))
    )
    val siblingPlan    = ProductionParticipationPlanner
      .plan(
        nestedSiblings.active,
        nestedSiblings.selected,
        nestedSiblings.children,
        nestedSiblings.realizations,
        nestedSiblings.position
      )
      .fold(error => throw new AssertionError(error.toString), identity)
    assertEquals(Vector(first, firstNested, second, secondNested), siblingPlan.absorptions.single.closure)

    val absorbedOccurrence      = ProductionOccurrenceId(1L, Vector(ParserFieldPathSegment.NamedField("absorbed")))
    val retainedOccurrence      = ProductionOccurrenceId(1L, Vector(ParserFieldPathSegment.NamedField("retained")))
    val occurrenceParticipation = planned.copy(absorbedBy =
      Map(ProductionInstanceId(InventoryKind.Node, child.valueId, Some(absorbedOccurrence)) -> base.parent)
    )
    assertEquals(
      Left(
        ProductionParticipationFailure.PartiallyAbsorbedSourceClaim(
          InventoryKind.Node,
          child.valueId,
          Vector(absorbedOccurrence, retainedOccurrence)
        )
      ),
      occurrenceParticipation.transferredOwner(
        SourceClaim.Node(
          child.valueId,
          Vector(
            ParserNodeOccurrence(absorbedOccurrence.ownerNodeId, absorbedOccurrence.fieldPath),
            ParserNodeOccurrence(retainedOccurrence.ownerNodeId, retainedOccurrence.fieldPath)
          )
        )
      )
    )

    val retainedOwner = instance(30)
    val shared        = nested.copy(
      active = nested.active :+ retainedOwner,
      selected = nested.selected + (retainedOwner         -> production("retained", Vector.empty)),
      children = nested.children + (retainedOwner         -> Vector(
        (
          "shared",
          Vector(ParserFieldPathSegment.NamedField("shared")),
          grandchild
        )
      )),
      realizations = nested.realizations + (retainedOwner -> realization("retained", Vector(root("retained")))),
      ranges = nested.ranges + (retainedOwner             -> PcSourceRange(0, 3))
    )
    assertTrue(failure(shared).isInstanceOf[ProductionParticipationFailure.SharedClosureNode])

    val cyclic = nested.copy(children =
      nested.children.updated(
        grandchild,
        Vector(
          (
            "cycle",
            Vector(ParserFieldPathSegment.NamedField("cycle")),
            child
          )
        )
      )
    )
    assertTrue(failure(cyclic).isInstanceOf[ProductionParticipationFailure.CyclicClosure])

    val overlapping = nested.copy(
      realizations = nested.realizations.updated(
        child,
        nested
          .realizations(child)
          .copy(childClosureAbsorptions =
            Vector(
              ChildClosureAbsorption(
                "nested",
                ChildRootOutcome.One(ChildOutcomeExpectation.Production("grandchild"))
              )
            )
          )
      )
    )
    assertTrue(failure(overlapping).isInstanceOf[ProductionParticipationFailure.MultiplyAbsorbedNode])

    val outside = nested.copy(ranges = nested.ranges.updated(grandchild, PcSourceRange(4, 5)))
    assertTrue(failure(outside).isInstanceOf[ProductionParticipationFailure.ChildOutsideParent])

    val syntheticOutside = nested.copy(
      ranges = nested.ranges - grandchild,
      positionOverrides = Map(
        grandchild -> ParserNodePosition.Positioned(
          PcSourceRange(4, 5),
          4,
          ParserPositionProvenance.Synthetic
        )
      )
    )
    assertTrue(failure(syntheticOutside).isInstanceOf[ProductionParticipationFailure.ChildOutsideParent])

    val absentEvent = nested.copy(
      ranges = nested.ranges - grandchild,
      positionOverrides = Map(grandchild -> ParserNodePosition.Absent)
    )
    assertTrue(failure(absentEvent).isInstanceOf[ProductionParticipationFailure.UnpositionedClosureMember])

    val interrupted = nested.copy(active = nested.active.filterNot(_ == grandchild))
    assertTrue(failure(interrupted).isInstanceOf[ProductionParticipationFailure.InterruptedClosure])

  @Test def noAbsorptionReturnsTheInputParticipationByteForByte(): Unit =
    val base      = graphWithChildren(
      2,
      ChildCardinality.Repeated(0, None),
      ChildRootOutcome.All(
        ChildOutcomeExpectation.OutputRole(ChildOut)
      )
    )
    val unchanged = base.copy(realizations =
      base.realizations.updated(
        base.parent,
        base.realizations(base.parent).copy(childClosureAbsorptions = Vector.empty)
      )
    )
    val result    = ProductionParticipationPlanner
      .plan(unchanged.active, unchanged.selected, unchanged.children, unchanged.realizations, unchanged.position)
      .toOption
      .get
    assertEquals(unchanged.active, result.retained)
    assertEquals(Map.empty, result.absorbedBy)
    assertEquals(Vector.empty, result.absorptions)

  @Test def wholeFileAbsorptionTransfersOneExactClaimAndExcludesEveryChildPhysicalStage(): Unit =
    val value          = snapshot("/whole-file-absorption", 1, Vector.empty)
    val runtime        = inventory(value)
    val generated      = completeCatalog(runtime)
    val originalParent = generated.productions.find(_.id == "Root").get
    val child          = generated.productions.find(_.id == "Child").get
    val childRole      = originalParent.children.single.roleId
    val childOutput    = child.effectiveOutputTemplate.composites.single.outputRoleId
    val parentTemplate = originalParent.effectiveOutputTemplate.copy(childMounts = Map(childRole -> None))
    val parent         = originalParent.copy(
      terminals = Vector(
        TerminalDeclaration(
          "fallback",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          originalParent.effectiveOutputTemplate.composites.single.outputRoleId
        )
      ),
      outputRoleId = None,
      outputRealizations = Vector(
        OutputRealization(
          "fallback",
          Vector.empty,
          parentTemplate,
          childClosureAbsorptions = Vector(
            ChildClosureAbsorption(
              childRole,
              ChildRootOutcome.All(ChildOutcomeExpectation.OutputRole(childOutput))
            )
          )
        )
      )
    )
    val productions    = Vector(parent, child)
    val catalog        = generated.copy(productions = productions, stableRoles = focusedRoleInventory(productions))
    val prepared       = PreparedProductionCatalog
      .prepare(catalog, aggregate(Vector(runtime)), surfaces(catalog))
      .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
    val evidence       = ProvisionalSourceEvidencePlanner
      .plan(value)
      .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
    val plan           = WholeFileProductionPlanner
      .plan(value, evidence, prepared)
      .fold(error => throw new AssertionError(error.toString), identity)
    val absorbed       = plan.childClosureAbsorptions.single

    assertEquals(parent.id, plan.composites.single.productionId)
    assertEquals(Vector(2L), absorbed.roots.map(_.valueId))
    assertEquals(absorbed.roots, absorbed.closure)
    assertEquals(PcSourceRange(0, 1), absorbed.transferredClaim)
    assertEquals(Vector(1L), plan.physicalLeafOwnership.map(_.sourceOwner.valueId).distinct)
    assertFalse(plan.targetAssertions.exists(_.owner match
      case TargetAssertionOwner.Composite(instance)   => instance.origin.valueId == 2L
      case TargetAssertionOwner.Terminal(instance, _) => instance.valueId == 2L
    ))
    assertFalse(plan.accessorAssertions.exists(_.owner.origin.valueId == 2L))
    assertFalse(plan.stubAssertions.exists(_.owner.origin.valueId == 2L))
    assertFalse(plan.navigationAssertions.exists(_.owner.origin.valueId == 2L))
    assertFalse(plan.structuralEvidenceOwnership.exists(_.owner.identity.contains("ProductionInstanceId(Node,2,")))
    val row = WholeFileProductionPlanRenderer
      .structure(plan)
      .rows
      .find(_.startsWith("child-closure-absorption\t"))
      .get
    assertTrue(row.contains("outputs,terminals,source-atoms,events,targets,accessors,stubs,indices,navigation"))

  @Test def aggregateValidationRejectsUnknownScalarMountedAndOutputSelectedAbsorptions(): Unit =
    val value       = snapshot("/invalid-absorption", 1, Vector.empty)
    val runtime     = inventory(value)
    val generated   = completeCatalog(runtime)
    val parent      = generated.productions.find(_.id == "Root").get
    val child       = generated.productions.find(_.id == "Child").get
    val childRole   = parent.children.single.roleId
    val childOutput = child.effectiveOutputTemplate.composites.single.outputRoleId
    val absorbing   = parent.copy(
      terminals = Vector(
        TerminalDeclaration(
          "fallback",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          parent.effectiveOutputTemplate.composites.single.outputRoleId
        )
      ),
      outputRoleId = None,
      outputRealizations = Vector(
        OutputRealization(
          "fallback",
          Vector.empty,
          parent.effectiveOutputTemplate.copy(childOutputSelections = Map(childRole -> childOutput)),
          childClosureAbsorptions = Vector(
            ChildClosureAbsorption(
              childRole,
              ChildRootOutcome.One(ChildOutcomeExpectation.OutputRole(childOutput))
            ),
            ChildClosureAbsorption(
              "unknown",
              ChildRootOutcome.All(ChildOutcomeExpectation.OutputRole(childOutput))
            )
          )
        )
      )
    )
    val productions = Vector(absorbing, child)
    val catalog     = generated.copy(productions = productions, stableRoles = focusedRoleInventory(productions))
    val failures    = Scala3PsiProductionCatalogValidator.validateExecutable(catalog, runtime, surfaces(catalog))
    assertTrue(failures.exists(_.isInstanceOf[CatalogValidationError.UnknownChildClosureAbsorptionRole]))
    assertTrue(failures.exists(_.isInstanceOf[CatalogValidationError.InvalidChildRootOutcome]))
    assertTrue(failures.exists(_.isInstanceOf[CatalogValidationError.ConflictingChildClosureParticipation]))

  private final case class Graph(
      parent: ProductionInstanceId,
      childrenOfParent: Vector[ProductionInstanceId],
      active: Vector[ProductionInstanceId],
      selected: Map[ProductionInstanceId, Scala3PsiProduction],
      children: Map[
        ProductionInstanceId,
        Vector[(String, Vector[ParserFieldPathSegment], ProductionInstanceId)]
      ],
      realizations: Map[ProductionInstanceId, OutputRealization],
      ranges: Map[ProductionInstanceId, PcSourceRange],
      positionOverrides: Map[ProductionInstanceId, ParserNodePosition] = Map.empty
  ):
    def position(instance: ProductionInstanceId): ParserNodePosition =
      positionOverrides.getOrElse(
        instance,
        ranges
          .get(instance)
          .map(range => ParserNodePosition.Positioned(range, range.startOffset, ParserPositionProvenance.SourceDerived))
          .getOrElse(ParserNodePosition.Absent)
      )

  private def graphWithChildren(
      count: Int,
      cardinality: ChildCardinality,
      outcome: ChildRootOutcome
  ): Graph =
    val parent            = instance(1)
    val children          = Vector.tabulate(count)(index => instance(index + 2))
    val childDecl         = ChildDeclaration("children", "children", cardinality, "child")
    val parentProduction  = production("parent", Vector(childDecl))
    val parentRealization = realization("parent", Vector(root("parent", ParentOut))).copy(
      childClosureAbsorptions = Vector(ChildClosureAbsorption("children", outcome))
    )
    Graph(
      parent,
      children,
      parent +: children,
      Map(parent -> parentProduction) ++ children.map(_ -> production("child", Vector.empty)),
      Map(
        parent -> children.map(child =>
          (
            "children",
            Vector(ParserFieldPathSegment.NamedField("children")),
            child
          )
        )
      ),
      Map(parent -> parentRealization) ++ children.map(_ -> realization("child", Vector(root("child")))),
      Map(parent -> PcSourceRange(0, count + 1)) ++ children.zipWithIndex.map((child, index) =>
        child -> PcSourceRange(index, index + 1)
      )
    )

  private def failure(graph: Graph): ProductionParticipationFailure =
    ProductionParticipationPlanner
      .plan(graph.active, graph.selected, graph.children, graph.realizations, graph.position)
      .left
      .toOption
      .get

  private def instance(id: Long): ProductionInstanceId = ProductionInstanceId(InventoryKind.Node, id, None)

  private def realization(
      id: String,
      roots: Vector[OutputCompositeDeclaration]
  ): OutputRealization = OutputRealization(
    id,
    Vector.empty,
    LocalOutputCompositeTemplate(roots, Map.empty)
  )

  private def root(id: String, role: PsiOutputRoleId = ChildOut): OutputCompositeDeclaration =
    OutputCompositeDeclaration(
      id,
      None,
      OutputRangeDeclaration.CompilerPosition,
      role,
      s"test.element.$id",
      TargetRequirement.Compatible,
      Vector.empty,
      PersistenceObligations.NotApplicable,
      None
    )

  private def production(id: String, children: Vector[ChildDeclaration]): Scala3PsiProduction =
    Scala3PsiProduction(
      id,
      if id == "parent" then ParentRole else ChildRole,
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
      s"test.element.$id",
      TargetRequirement.Compatible,
      Vector.empty,
      PersistenceObligations.NotApplicable,
      outputRoleId = Some(if id == "parent" then ParentOut else ChildOut)
    )

  extension [A](values: Vector[A])
    private def single: A =
      assertEquals(1, values.size)
      values.head
