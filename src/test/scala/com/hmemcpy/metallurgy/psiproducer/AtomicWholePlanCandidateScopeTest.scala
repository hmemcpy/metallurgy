package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import org.junit.Assert.*
import org.junit.Test

final class AtomicWholePlanCandidateScopeTest extends Scala3PsiProductionCatalogTestSupport:
  private def instance(id: Long): ProductionInstanceId =
    ProductionInstanceId(InventoryKind.Node, id, None)

  private def root(
      id: Long,
      start: Int,
      end: Int,
      provenance: ParserPositionProvenance = ParserPositionProvenance.SourceDerived,
      parentCount: Int = 1
  ): AtomicWholePlanCandidateRoot =
    AtomicWholePlanCandidateRoot(
      instance(id),
      ParserNodePosition.Positioned(PcSourceRange(start, end), start, provenance),
      parentCount
    )

  private def diagnostic(
      severity: ParserDiagnosticSeverity,
      start: Int,
      end: Int,
      point: Int,
      provenance: ParserDiagnosticPositionProvenance = ParserDiagnosticPositionProvenance.SourceDerived
  ): ParserDiagnostic =
    ParserDiagnostic(
      severity,
      "parser diagnostic",
      Some(ParserDiagnosticPosition(PcSourceRange(start, end), point, provenance))
    )

  @Test def acceptsDisjointSourceRootsInDeterministicSourceOrder(): Unit =
    val expected = Vector(instance(1), instance(2))
    val roots    = Vector(root(2, 10, 15), root(1, 1, 5))

    assertEquals(Some(expected), AtomicWholePlanCandidateScope.validate(roots, 20))
    assertEquals(Some(expected), AtomicWholePlanCandidateScope.validate(roots.reverse, 20))

  @Test def rejectsDuplicateEqualCrossingAndNestedRoots(): Unit =
    val first = root(1, 1, 8)

    assertEquals(None, AtomicWholePlanCandidateScope.validate(Vector(first, first), 20))
    assertEquals(None, AtomicWholePlanCandidateScope.validate(Vector(first, root(2, 1, 8)), 20))
    assertEquals(None, AtomicWholePlanCandidateScope.validate(Vector(first, root(2, 5, 10)), 20))
    assertEquals(None, AtomicWholePlanCandidateScope.validate(Vector(first, root(2, 2, 5)), 20))

  @Test def rejectsUnpositionedSyntheticEmptyOutOfFileAndMultiplyParentedRoots(): Unit =
    val absent = AtomicWholePlanCandidateRoot(instance(1), ParserNodePosition.Absent, 1)

    assertEquals(None, AtomicWholePlanCandidateScope.validate(Vector(absent), 20))
    assertEquals(
      None,
      AtomicWholePlanCandidateScope.validate(
        Vector(root(1, 1, 5, ParserPositionProvenance.Synthetic)),
        20
      )
    )
    assertEquals(None, AtomicWholePlanCandidateScope.validate(Vector(root(1, 4, 4)), 20))
    assertEquals(None, AtomicWholePlanCandidateScope.validate(Vector(root(1, 1, 21)), 20))
    assertEquals(None, AtomicWholePlanCandidateScope.validate(Vector(root(1, 1, 5, parentCount = 2)), 20))
    assertEquals(
      None,
      AtomicWholePlanCandidateScope.validate(
        Vector(
          AtomicWholePlanCandidateRoot(
            instance(1),
            ParserNodePosition.Positioned(PcSourceRange(1, 5), 6, ParserPositionProvenance.SourceDerived),
            1
          )
        ),
        20
      )
    )

  @Test def repeatedValidationHasStableResult(): Unit =
    val roots    = Vector(root(3, 20, 25), root(1, 1, 5), root(2, 10, 15))
    val expected = AtomicWholePlanCandidateScope.validate(roots, 30)

    assertEquals(Vector.fill(20)(expected), Vector.fill(20)(AtomicWholePlanCandidateScope.validate(roots, 30)))

  @Test def sourceDerivedErrorRangesUseHalfOpenIntersection(): Unit =
    val value                                      = Vector(root(1, 5, 10))
    def validate(start: Int, end: Int, point: Int) =
      AtomicWholePlanCandidateScope.validate(
        value,
        20,
        diagnostics = Vector(diagnostic(ParserDiagnosticSeverity.Error, start, end, point))
      )

    assertEquals(Some(Vector(instance(1))), validate(0, 5, 0))
    assertEquals(Some(Vector.empty), validate(4, 6, 4))
    assertEquals(Some(Vector.empty), validate(5, 6, 5))
    assertEquals(Some(Vector.empty), validate(7, 8, 7))
    assertEquals(Some(Vector.empty), validate(9, 10, 10))
    assertEquals(Some(Vector(instance(1))), validate(10, 12, 10))

  @Test def sourceDerivedErrorPointsIncludeRootStartAndExcludeRootEnd(): Unit =
    val value                = Vector(root(1, 5, 10))
    def validate(point: Int) =
      AtomicWholePlanCandidateScope.validate(
        value,
        20,
        diagnostics = Vector(diagnostic(ParserDiagnosticSeverity.Error, point, point, point))
      )

    assertEquals(Some(Vector(instance(1))), validate(4))
    assertEquals(Some(Vector.empty), validate(5))
    assertEquals(Some(Vector.empty), validate(7))
    assertEquals(Some(Vector(instance(1))), validate(10))

  @Test def unsafeErrorEvidenceAndMissingCapabilityRequireAllFallback(): Unit =
    val value  = Vector(root(1, 5, 10))
    val unsafe = Vector(
      ParserDiagnostic(ParserDiagnosticSeverity.Error, "unpositioned", None),
      diagnostic(ParserDiagnosticSeverity.Error, 5, 5, 5, ParserDiagnosticPositionProvenance.Synthetic),
      diagnostic(ParserDiagnosticSeverity.Error, 1, 21, 1),
      diagnostic(ParserDiagnosticSeverity.Error, 1, 3, 4)
    )

    unsafe.foreach: error =>
      assertEquals(
        error.toString,
        None,
        AtomicWholePlanCandidateScope.validate(value, 20, diagnostics = Vector(error))
      )
    assertEquals(
      None,
      AtomicWholePlanCandidateScope.validate(
        value,
        20,
        ParserCapabilityStatus.Unavailable("provenance unavailable"),
        Vector(diagnostic(ParserDiagnosticSeverity.Error, 5, 6, 5))
      )
    )

  @Test def warningsAndInformationDoNotBlockAtomicCandidates(): Unit =
    val value       = Vector(root(1, 5, 10))
    val diagnostics = Vector(
      diagnostic(
        ParserDiagnosticSeverity.Warning,
        5,
        5,
        5,
        ParserDiagnosticPositionProvenance.Synthetic
      ),
      ParserDiagnostic(ParserDiagnosticSeverity.Information, "unpositioned information", None)
    )

    assertEquals(
      Some(Vector(instance(1))),
      AtomicWholePlanCandidateScope.validate(value, 20, diagnostics = diagnostics)
    )
    assertEquals(
      Some(Vector(instance(1))),
      AtomicWholePlanCandidateScope.validate(
        value,
        20,
        ParserCapabilityStatus.Unavailable("provenance unavailable"),
        diagnostics
      )
    )

  @Test def disjointCandidatesAreFilteredIndependentlyBeforeTrials(): Unit =
    val roots    = Vector(root(2, 10, 15), root(1, 0, 5))
    val errors   = Vector(diagnostic(ParserDiagnosticSeverity.Error, 2, 3, 2))
    val expected = Some(Vector(instance(2)))

    assertEquals(expected, AtomicWholePlanCandidateScope.validate(roots, 20, diagnostics = errors))
    assertEquals(expected, AtomicWholePlanCandidateScope.validate(roots.reverse, 20, diagnostics = errors))
    assertEquals(
      Vector.fill(20)(expected),
      Vector.fill(20)(AtomicWholePlanCandidateScope.validate(roots, 20, diagnostics = errors))
    )

    val eligible = expected.get
    val seen     = Vector.newBuilder[Set[ProductionInstanceId]]
    val selected = AtomicWholePlanTrials.select(eligible): enabled =>
      seen += enabled
      true
    assertEquals(Set(instance(2)), selected)
    assertEquals(Vector(Set(instance(2)), Set(instance(2))), seen.result())

  @Test def admitsTwoNativeRootsOnlyAfterTheirCombinedProof(): Unit =
    val roots = Vector(instance(1), instance(2))
    val seen  = Vector.newBuilder[Set[ProductionInstanceId]]

    val selected = AtomicWholePlanTrials.select(roots): enabled =>
      seen += enabled
      true

    assertEquals(roots.toSet, selected)
    assertEquals(Vector(Set(instance(1)), Set(instance(2)), roots.toSet), seen.result())

  @Test def retainsOnlyIndependentlyAndJointlyProvenRoots(): Unit =
    val roots = Vector(instance(1), instance(2))

    val selected = AtomicWholePlanTrials.select(roots): enabled =>
      !enabled(instance(2))

    assertEquals(Set(instance(1)), selected)

  @Test def combinedFailureReturnsTheAllFallbackSelection(): Unit =
    val roots = Vector(instance(1), instance(2))

    val selected = AtomicWholePlanTrials.select(roots): enabled =>
      enabled.size == 1

    assertEquals(Set.empty, selected)

  @Test def plannerAdmitsTwoDisjointRootsOnlyAsOneClosedPlan(): Unit =
    val value                 = atomicPlanningSnapshot()
    val runtime               = inventory(value)
    val catalog               = atomicPlanningCatalog(runtime)
    val aggregate             = this.aggregate(Vector(runtime))
    val evidence              = ProvisionalSourceEvidencePlanner.plan(value).toOption.get
    def plan                  = planned(value, evidence, catalog, aggregate, surfaces(catalog))
      .fold(failure => throw new AssertionError(failure.toString), identity)
    val first                 = plan
    val second                = plan
    val atomicSelections      =
      first.realizationSelections.filter(selection => selection.owner.valueId == 2L || selection.owner.valueId == 3L)
    val atomicCompositeOwners = first.composites.filter(composite =>
      composite.instance.origin.valueId == 2L || composite.instance.origin.valueId == 3L
    )

    assertEquals(first, second)
    assertEquals(
      Vector(2L, 3L),
      atomicSelections
        .collect:
          case PlannedRealizationSelection(owner, "native", RealizationSelectionReason.PreferredCandidate) =>
            owner.valueId
        .sorted
    )
    assertEquals(Vector("ChildA", "ChildB"), atomicCompositeOwners.map(_.productionId))
    assertEquals(
      value.sourceText,
      first.physicalLeafOwnership.map(leaf => value.sourceText.substring(leaf.start, leaf.end)).mkString
    )

  @Test def plannerRetainsACompleteFallbackForAnUnprovenDisjointRoot(): Unit =
    val value      = atomicPlanningSnapshot()
    val runtime    = inventory(value)
    val catalog    = atomicPlanningCatalog(runtime, secondCandidateMatches = false)
    val evidence   = ProvisionalSourceEvidencePlanner.plan(value).toOption.get
    val plan       = planned(value, evidence, catalog, aggregate(Vector(runtime)), surfaces(catalog))
      .fold(failure => throw new AssertionError(failure.toString), identity)
    val selections =
      plan.realizationSelections.filter(selection => selection.owner.valueId == 2L || selection.owner.valueId == 3L)

    assertEquals(
      Vector(
        2L -> RealizationSelectionReason.PreferredCandidate,
        3L -> RealizationSelectionReason.AtomicWholePlanFallback
      ),
      selections.map(selection => selection.owner.valueId -> selection.reason).sortBy(_._1)
    )
    assertEquals(
      Vector(PsiOutputRoleId("test.output.ChildA"), PsiOutputRoleId.ExpressionPayload),
      plan.targetAssertions.collect:
        case PlannedTargetAssertion(
              TargetAssertionOwner.Composite(composite),
              PlannedTargetIdentity.OutputRole(role),
              _
            ) if composite.origin.valueId == 2L || composite.origin.valueId == 3L =>
          role
    )
    assertEquals(
      value.sourceText,
      plan.physicalLeafOwnership.map(leaf => value.sourceText.substring(leaf.start, leaf.end)).mkString
    )

  @Test def plannerFiltersOnlyTheCandidateIntersectingASourceDerivedError(): Unit =
    val value = atomicPlanningSnapshot(
      Vector(diagnostic(ParserDiagnosticSeverity.Error, 0, 1, 0))
    )

    assertEquals(
      Vector(
        2L -> RealizationSelectionReason.AtomicWholePlanFallback,
        3L -> RealizationSelectionReason.PreferredCandidate
      ),
      atomicSelectionReasons(value)
    )

  @Test def plannerUsesTheCompleteFallbackPlanForUnsafeErrorEvidence(): Unit =
    val value = atomicPlanningSnapshot(
      Vector(
        diagnostic(
          ParserDiagnosticSeverity.Error,
          0,
          1,
          0,
          ParserDiagnosticPositionProvenance.Synthetic
        )
      )
    )

    assertEquals(
      Vector(
        2L -> RealizationSelectionReason.AtomicWholePlanFallback,
        3L -> RealizationSelectionReason.AtomicWholePlanFallback
      ),
      atomicSelectionReasons(value)
    )

  private def atomicSelectionReasons(
      value: ParserSyntaxSnapshot
  ): Vector[(Long, RealizationSelectionReason)] =
    val runtime  = inventory(value)
    val catalog  = atomicPlanningCatalog(runtime)
    val evidence = ProvisionalSourceEvidencePlanner.plan(value).toOption.get
    val plan     = planned(value, evidence, catalog, aggregate(Vector(runtime)), surfaces(catalog))
      .fold(failure => throw new AssertionError(failure.toString), identity)
    assertEquals(
      value.sourceText,
      plan.physicalLeafOwnership.map(leaf => value.sourceText.substring(leaf.start, leaf.end)).mkString
    )
    plan.realizationSelections
      .collect:
        case selection if selection.owner.valueId == 2L || selection.owner.valueId == 3L =>
          selection.owner.valueId -> selection.reason
      .sortBy(_._1)

  private def atomicPlanningSnapshot(
      diagnostics: Vector[ParserDiagnostic] = Vector.empty
  ): ParserSyntaxSnapshot =
    val base                                                                 = snapshot("/atomic-whole-plan", 1, Vector.empty)
    val source                                                               = "xy"
    def position(start: Int, end: Int)                                       =
      ParserNodePosition.Positioned(
        PcSourceRange(start, end),
        start,
        ParserPositionProvenance.SourceDerived
      )
    def child(id: Long, prefix: String, field: String, start: Int, end: Int) =
      ParserSyntaxNode(
        id,
        prefix,
        Vector.empty,
        position(start, end),
        Vector(ParserNodeOccurrence(1L, Vector(ParserFieldPathSegment.NamedField(field))))
      )
    base.copy(
      sourceText = source,
      sourceDigest = ParserSyntaxSnapshot.digest(source),
      sourceLength = source.length,
      diagnostics = diagnostics,
      nodes = Vector(
        ParserSyntaxNode(
          1L,
          "Root",
          Vector(
            ParserSyntaxField("left", ParserFieldValue.Node(2L)),
            ParserSyntaxField("right", ParserFieldValue.Node(3L))
          ),
          position(0, source.length),
          Vector.empty
        ),
        child(2L, "ChildA", "left", 0, 1),
        child(3L, "ChildB", "right", 1, 2)
      )
    )

  private def atomicPlanningCatalog(
      runtime: CompilerRuntimeInventory,
      secondCandidateMatches: Boolean = true
  ): Scala3PsiProductionCatalog =
    val base        = completeCatalog(runtime)
    val productions = base.productions.map:
      case production if production.id == "Root"                                =>
        production.copy(outputTemplate =
          Some(
            LocalOutputCompositeTemplate(
              Vector.empty,
              Map("child-left" -> None, "child-right" -> None)
            )
          )
        )
      case production if production.id == "ChildA" || production.id == "ChildB" =>
        val candidateConditions =
          if production.id == "ChildB" && !secondCandidateMatches then
            Vector(EvidenceCondition.ProductionStartsWith(ClosedSourceLexicalKind.LeftParenthesis, present = true))
          else Vector.empty
        val candidate           = OutputRealization(
          "native",
          Vector.empty,
          production.effectiveOutputTemplate,
          evidenceConditions = candidateConditions
        )
        val fallback            = OutputRealization(
          "fallback",
          Vector.empty,
          LocalOutputCompositeTemplate(
            Vector(
              OutputCompositeDeclaration(
                "payload",
                None,
                OutputRangeDeclaration.CompilerPosition,
                PsiOutputRoleId.ExpressionPayload,
                production.targetSurfaceId,
                production.targetRequirement,
                Vector.empty,
                PersistenceObligations.NotApplicable,
                None
              )
            ),
            Map.empty
          )
        )
        production.copy(
          outputTemplate = None,
          outputRealizations = Vector(candidate, fallback),
          outputRoleId = None,
          realizationChoice = Some(
            RealizationChoice(
              Vector(candidate.id),
              fallback.id,
              RealizationChoicePolicy.AtomicWholePlan
            )
          )
        )
      case production                                                           => production
    Scala3PsiProductionCatalog(productions, focusedRoleInventory(productions))
