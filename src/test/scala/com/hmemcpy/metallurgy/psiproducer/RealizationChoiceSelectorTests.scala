package com.hmemcpy.metallurgy.psiproducer

import org.junit.Assert.*
import org.junit.Test

final class RealizationChoiceSelectorTest:
  @Test def shapeMatchingRetainsDeclaredAlternativesUntilResolution(): Unit =
    val occurrence = CompilerProductionContextPattern(ContextPattern.Root, SourceClassification.SourceReachable)
    val candidate  = production(Vector(realization("candidate")), None).copy(
      id = "candidate-production",
      pattern = production(Vector.empty, None).pattern.copy(occurrences = Vector(occurrence))
    )
    val fallback   = candidate.copy(id = "fallback-production")
    val catalog    = Scala3PsiProductionCatalog(
      Vector(candidate, fallback),
      StableRoleInventory.Empty,
      Vector(ProductionAlternatives(candidate.id, fallback.id))
    )
    val matches    = CatalogShapeMatcher.select(
      catalog,
      InventoryKind.Node,
      "Parent",
      Vector.empty,
      None,
      SourceClassification.SourceReachable
    )

    assertEquals(Vector(candidate.id, fallback.id), matches.map(_.id))
    assertEquals(
      Right(RetainedProductionMatch(candidate, Some(fallback))),
      ProductionMatchRetention.retain(catalog, matches)
    )

  @Test def selectsPreferredCandidateOnlyWhenItsAssessmentIsComplete(): Unit =
    val production = choiceProduction
    val selected   = RealizationChoiceSelector
      .select(production, Vector(realization("candidate")), _ => Right(Vector.empty))
      .fold(error => throw new AssertionError(error.toString), identity)

    assertEquals("candidate", selected.realization.id)
    assertEquals(RealizationSelectionReason.PreferredCandidate, selected.reason)

  @Test def selectsTheSoleFallbackOnlyForReviewedInapplicability(): Unit =
    val reason   = CandidateInapplicability.UnsupportedChildRoot(
      "arguments",
      ProductionInstanceId(InventoryKind.Node, 1L, None),
      PsiOutputRoleId.ExpressionPayload
    )
    val selected = RealizationChoiceSelector
      .select(choiceProduction, Vector(realization("candidate")), _ => Right(Vector(reason)))
      .fold(error => throw new AssertionError(error.toString), identity)

    assertEquals("fallback", selected.realization.id)
    assertEquals(
      RealizationSelectionReason.CompleteFallback(Vector(reason)),
      selected.reason
    )

  @Test def excludedTypeApplicationIsAnExplicitReviewedInapplicability(): Unit =
    val selected = RealizationChoiceSelector
      .select(
        choiceProduction,
        Vector(realization("candidate")),
        _ => Right(Vector(CandidateInapplicability.ExcludedTypeApplication))
      )
      .fold(error => throw new AssertionError(error.toString), identity)

    assertEquals("fallback", selected.realization.id)
    assertEquals(
      RealizationSelectionReason.CompleteFallback(Vector(CandidateInapplicability.ExcludedTypeApplication)),
      selected.reason
    )

  @Test def excludedRootAttachmentIsAnExplicitReviewedInapplicability(): Unit =
    val reason   = CandidateInapplicability.ExcludedRootAttachment(
      AttachmentEvidence("KindOfApply", com.hmemcpy.metallurgy.pc.ParserAttachmentValue.Product("Using"))
    )
    val selected = RealizationChoiceSelector
      .select(choiceProduction, Vector(realization("candidate")), _ => Right(Vector(reason)))
      .fold(error => throw new AssertionError(error.toString), identity)

    assertEquals("fallback", selected.realization.id)
    assertEquals(RealizationSelectionReason.CompleteFallback(Vector(reason)), selected.reason)

  @Test def unavailableHostBindingSelectsOnlyTheCompleteFallback(): Unit =
    val reason   = CandidateInapplicability.UnavailableHostBinding(
      Scala3PsiNamedArgumentProductions.CandidateProductionId,
      Scala3PsiNamedArgumentProductions.NativeRealizationId
    )
    val selected = RealizationChoiceSelector
      .select(choiceProduction, Vector(realization("candidate")), _ => Right(Vector(reason)))
      .fold(error => throw new AssertionError(error.toString), identity)

    assertEquals("fallback", selected.realization.id)
    assertEquals(RealizationSelectionReason.CompleteFallback(Vector(reason)), selected.reason)

  @Test def candidateDefectsNeverSelectTheFallback(): Unit =
    val defect = CandidateRealizationDefect.Binding("argument[1] has two roots")
    val result = RealizationChoiceSelector.select(
      choiceProduction,
      Vector(realization("candidate")),
      _ => Left(defect)
    )

    assertEquals(
      Left(RealizationChoiceFailure.CandidateDefect("parent", "candidate", defect)),
      result
    )

  @Test def bindingAndSourceOwnershipDefectsNeverSelectTheFallback(): Unit =
    Vector(
      CandidateRealizationDefect.Binding("missing native binding"),
      CandidateRealizationDefect.SourceOwnership("candidate source closure is incomplete")
    ).foreach: defect =>
      assertEquals(
        Left(RealizationChoiceFailure.CandidateDefect("parent", "candidate", defect)),
        RealizationChoiceSelector.select(choiceProduction, Vector(realization("candidate")), _ => Left(defect))
      )

  @Test def selectsExactlyOneEvidenceMatchedCandidateWithoutFirstMatchSemantics(): Unit =
    val first      = realization("first")
    val second     = realization("second")
    val fallback   = realization("fallback")
    val production = choiceProduction.copy(
      outputRealizations = Vector(first, second, fallback),
      realizationChoice = Some(RealizationChoice(Vector("first", "second"), "fallback"))
    )
    var assessed   = Vector.empty[String]
    val selected   = RealizationChoiceSelector
      .select(
        production,
        Vector(second, fallback),
        candidate =>
          assessed :+= candidate.id
          Right(Vector.empty)
      )
      .fold(error => throw new AssertionError(error.toString), identity)

    assertEquals("second", selected.realization.id)
    assertEquals(Vector("second"), assessed)

  @Test def overlappingCandidateEvidenceFailsBeforeAssessment(): Unit =
    val first       = realization("first")
    val second      = realization("second")
    val fallback    = realization("fallback")
    val production  = choiceProduction.copy(
      outputRealizations = Vector(first, second, fallback),
      realizationChoice = Some(RealizationChoice(Vector("first", "second"), "fallback"))
    )
    var assessments = 0

    val result = RealizationChoiceSelector.select(
      production,
      Vector(first, second, fallback),
      _ =>
        assessments += 1
        Right(Vector.empty)
    )
    assertTrue(result.left.exists(_.isInstanceOf[RealizationChoiceFailure.CandidateEvidence]))
    assertEquals(0, assessments)

  @Test def missingCandidateEvidenceSelectsTheFallbackWithRecordedReasons(): Unit =
    val first       = realization("first")
    val second      = realization("second")
    val fallback    = realization("fallback")
    val production  = choiceProduction.copy(
      outputRealizations = Vector(first, second, fallback),
      realizationChoice = Some(RealizationChoice(Vector("first", "second"), "fallback"))
    )
    var assessments = 0

    val selected = RealizationChoiceSelector
      .select(
        production,
        Vector(fallback),
        _ =>
          assessments += 1
          Right(Vector.empty)
      )
      .fold(error => throw new AssertionError(error.toString), identity)

    assertEquals(
      RealizationSelectionReason.CompleteFallback(
        Vector(
          CandidateInapplicability.ExcludedCandidateEvidence("parent", "first"),
          CandidateInapplicability.ExcludedCandidateEvidence("parent", "second")
        )
      ),
      selected.reason
    )
    assertEquals("fallback", selected.realization.id)
    assertEquals(0, assessments)

  @Test def ambiguousMissingAndExtraAlternativesFailClosed(): Unit =
    val candidate = realization("candidate")
    val fallback  = realization("fallback")
    val invalid   = Vector(
      production(Vector(candidate), Some(RealizationChoice(Vector("candidate"), "fallback"))),
      production(
        Vector(candidate, fallback, realization("extra")),
        Some(RealizationChoice(Vector("candidate"), "fallback"))
      ),
      production(Vector(candidate, candidate, fallback), Some(RealizationChoice(Vector("candidate"), "fallback"))),
      production(Vector(candidate, fallback), Some(RealizationChoice(Vector.empty, "fallback"))),
      production(Vector(candidate, fallback), Some(RealizationChoice(Vector("candidate", "candidate"), "fallback")))
    )

    invalid.foreach: value =>
      assertTrue(RealizationChoiceSelector.select(value, Vector.empty, _ => Right(Vector.empty)).isLeft)

  @Test def undeclaredProductionsKeepTheExistingSingleMatchContract(): Unit =
    val only     = realization("only")
    val selected = RealizationChoiceSelector
      .select(
        production(Vector(only), None),
        Vector(only),
        _ => Left(CandidateRealizationDefect.Binding("must not run"))
      )
      .fold(error => throw new AssertionError(error.toString), identity)

    assertEquals(SelectedRealization(only, RealizationSelectionReason.Ordinary), selected)

  private def choiceProduction: Scala3PsiProduction =
    production(
      Vector(realization("candidate"), realization("fallback")),
      Some(RealizationChoice(Vector("candidate"), "fallback"))
    )

  private def production(
      realizations: Vector[OutputRealization],
      choice: Option[RealizationChoice]
  ): Scala3PsiProduction =
    Scala3PsiProduction(
      id = "parent",
      grammarRoleId = GrammarRoleId.OutputFreeExpression,
      pattern = CompilerProductionPattern(InventoryKind.Node, "Parent", Vector.empty, Vector.empty),
      dispositions = Vector.empty,
      children = Vector.empty,
      terminals = Vector.empty,
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = "test.Parent",
      targetRequirement = TargetRequirement.Compatible,
      accessors = Vector.empty,
      persistence = PersistenceObligations.NotApplicable,
      outputRealizations = realizations,
      outputRoleId = None,
      realizationChoice = choice
    )

  private def realization(id: String): OutputRealization =
    OutputRealization(id, Vector.empty, LocalOutputCompositeTemplate(Vector.empty, Map.empty))
