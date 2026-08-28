package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import org.junit.Assert.*
import org.junit.Test

private[psiproducer] trait Scala3NestedChildRequirementPlanningTests extends Scala3PsiProductionCatalogTestSupport:
  private val RootRole       = PsiOutputRoleId("test.output.Root")
  private val ChildRole      = PsiOutputRoleId("test.output.Child")
  private val GrandchildRole = PsiOutputRoleId("test.output.Grandchild")

  private def threeLevelSnapshot(grandchildProvenance: ParserPositionProvenance): ParserSyntaxSnapshot =
    val source     = "abc"
    val grandchild = ParserSyntaxNode(
      3,
      "Grandchild",
      Vector.empty,
      ParserNodePosition.Positioned(PcSourceRange(0, 1), 0, grandchildProvenance),
      Vector(
        ParserNodeOccurrence(
          2,
          Vector(ParserFieldPathSegment.NamedField("children"), ParserFieldPathSegment.RepeatedIndex(0))
        )
      )
    )
    val child      = ParserSyntaxNode(
      2,
      "Child",
      Vector(ParserSyntaxField("children", ParserFieldValue.Repeated(Vector(ParserFieldValue.Node(3))))),
      ParserNodePosition.Positioned(PcSourceRange(0, 2), 0, ParserPositionProvenance.SourceDerived),
      Vector(
        ParserNodeOccurrence(
          1,
          Vector(ParserFieldPathSegment.NamedField("children"), ParserFieldPathSegment.RepeatedIndex(0))
        )
      )
    )
    snapshot("nested-requirement", 1L, Vector.empty).copy(
      sourceText = source,
      sourceDigest = ParserSyntaxSnapshot.digest(source),
      sourceLength = source.length,
      nodes = Vector(
        ParserSyntaxNode(
          1,
          "Root",
          Vector(ParserSyntaxField("children", ParserFieldValue.Repeated(Vector(ParserFieldValue.Node(2))))),
          ParserNodePosition.Positioned(PcSourceRange(0, 3), 0, ParserPositionProvenance.SourceDerived),
          Vector.empty
        ),
        child,
        grandchild
      )
    )

  private def payloadComposite: OutputCompositeDeclaration =
    OutputCompositeDeclaration(
      "payload",
      None,
      OutputRangeDeclaration.CompilerPosition,
      PsiOutputRoleId.ExpressionPayload,
      "element.Root",
      TargetRequirement.Compatible,
      Vector.empty,
      PersistenceObligations.NotApplicable,
      None
    )

  private def rootComposite(id: String, range: OutputRangeDeclaration): OutputCompositeDeclaration =
    OutputCompositeDeclaration(
      id,
      None,
      range,
      RootRole,
      "element.Root",
      TargetRequirement.Compatible,
      Vector.empty,
      PersistenceObligations.NotApplicable,
      None
    )

  private def candidateCatalog(
      value: ParserSyntaxSnapshot,
      grandchildRealizations: Vector[OutputRealization]
  ): Scala3PsiProductionCatalog =
    val base    = completeCatalog(inventory(value))
    val nested  = Vector(
      RequiredChildRootOutcome(
        "child",
        ChildRootOutcome.One(ChildOutcomeExpectation.OutputRoles(Set(GrandchildRole)))
      )
    )
    val catalog = base.copy(productions = base.productions.map {
      case production if production.id == "Root"       =>
        production.copy(
          children = production.children.map(_.copy(cardinality = ChildCardinality.ExactlyOne)),
          terminals = Vector(
            TerminalDeclaration(
              "payload",
              TerminalIntervalSelector.WholeProduction,
              TerminalLeafTarget.Parent,
              OccurrenceCardinality.ExactlyOne,
              PsiOutputRoleId.SourceTerminal
            )
          ),
          outputRealizations = Vector(
            OutputRealization(
              "root-candidate",
              Vector.empty,
              LocalOutputCompositeTemplate(
                Vector(rootComposite("self", OutputRangeDeclaration.CompilerPosition)),
                Map("child" -> Some("self"))
              ),
              terminalIds = Some(Set.empty),
              requiredChildRoots = Vector(
                RequiredChildRootOutcome(
                  "child",
                  ChildRootOutcome.One(ChildOutcomeExpectation.OutputRoles(Set(ChildRole)))
                )
              )
            ),
            OutputRealization(
              "root-fallback",
              Vector.empty,
              LocalOutputCompositeTemplate(
                Vector(payloadComposite),
                Map("child" -> None)
              ),
              childClosureAbsorptions = Vector(
                ChildClosureAbsorption("child", ChildRootOutcome.AnyReviewed)
              ),
              terminalIds = Some(Set("payload"))
            )
          ),
          realizationChoice = Some(
            RealizationChoice(
              Vector("root-candidate"),
              "root-fallback",
              policy = RealizationChoicePolicy.AtomicWholePlan
            )
          )
        )
      case production if production.id == "Child"      =>
        production.copy(nestedChildRequirements = nested)
      case production if production.id == "Grandchild" =>
        production.copy(outputRealizations = grandchildRealizations)
      case other                                       => other
    })
    catalog.copy(stableRoles = focusedRoleInventory(catalog.productions))

  private def plannedWithDefectiveNestedGrandchild(
      value: ParserSyntaxSnapshot,
      grandchildRealizations: Vector[OutputRealization]
  ): Either[WholeFilePlanningFailure, WholeFileProductionPlan] =
    val evidence = ProvisionalSourceEvidencePlanner.plan(value).toOption.get
    val compiler = inventory(value)
    val catalog  = candidateCatalog(value, grandchildRealizations)
    planned(value, evidence, catalog, aggregate(Vector(compiler)), surfaces(catalog))

  @Test def nestedRequiredRootAmbiguityDegradesTheRootCandidateToFileScopedFallback(): Unit =
    val grandchildRoleComposite = OutputCompositeDeclaration(
      "self",
      None,
      OutputRangeDeclaration.CompilerPosition,
      GrandchildRole,
      "element.Grandchild",
      TargetRequirement.Compatible,
      Vector.empty,
      PersistenceObligations.NotApplicable,
      None
    )
    val zeroWidthTail           = OutputCompositeDeclaration(
      "tail",
      None,
      OutputRangeDeclaration.BoundaryDerived(
        OutputBoundary.ProductionEnd(PositionProvenancePolicy.PositionedIncludingSynthetic),
        OutputBoundary.ProductionEnd(PositionProvenancePolicy.PositionedIncludingSynthetic)
      ),
      GrandchildRole,
      "element.Grandchild",
      TargetRequirement.Compatible,
      Vector.empty,
      PersistenceObligations.NotApplicable,
      None
    )
    val value                   = threeLevelSnapshot(ParserPositionProvenance.SourceDerived)
    val plan                    = plannedWithDefectiveNestedGrandchild(
      value,
      Vector(
        OutputRealization(
          "self",
          Vector.empty,
          LocalOutputCompositeTemplate(Vector(grandchildRoleComposite, zeroWidthTail), Map.empty)
        )
      )
    ).fold(failure => throw new AssertionError(failure.toString), identity)
    val selected                = plan.realizationSelections.find(_.owner == ProductionInstanceId(InventoryKind.Node, 1, None))
    assertTrue(s"root selection missing: ${plan.realizationSelections}", selected.isDefined)
    assertEquals(
      RealizationSelectionReason.AtomicWholePlanFallback,
      selected.map(_.reason).get
    )

  @Test def nestedRequiredRootSourceOwnershipDefectDegradesTheRootCandidateToFileScopedFallback(): Unit =
    val value    = threeLevelSnapshot(ParserPositionProvenance.Synthetic)
    val plan     = plannedWithDefectiveNestedGrandchild(
      value,
      Vector(
        OutputRealization(
          "self",
          Vector.empty,
          LocalOutputCompositeTemplate(
            Vector(
              OutputCompositeDeclaration(
                "self",
                None,
                OutputRangeDeclaration.CompilerPosition,
                GrandchildRole,
                "element.Grandchild",
                TargetRequirement.Compatible,
                Vector.empty,
                PersistenceObligations.NotApplicable,
                None
              )
            ),
            Map.empty
          )
        )
      )
    ).fold(failure => throw new AssertionError(failure.toString), identity)
    val selected = plan.realizationSelections.find(_.owner == ProductionInstanceId(InventoryKind.Node, 1, None))
    assertTrue(s"root selection missing: ${plan.realizationSelections}", selected.isDefined)
    assertEquals(
      RealizationSelectionReason.AtomicWholePlanFallback,
      selected.map(_.reason).get
    )
