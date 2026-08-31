package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest

private[psiproducer] trait Scala3WholeFileProductionPlanningTests extends Scala3PsiProductionCatalogTestSupport:
  private val TransparentRootGrammarRole = GrammarRoleId("test.grammar.transparent-root")
  private val SharedProductGrammarRole   = GrammarRoleId("test.grammar.shared-product")
  private val StructuralEventGrammarRole = GrammarRoleId("test.grammar.structural-event")
  private val SharedOutputRole           = PsiOutputRoleId("test.output.shared-composite")

  @Test def representativeWholeFilePlanHasExactReadableStructure(): Unit =
    val value      = annotationModifierSnapshot
    val runtime    = inventory(value)
    val root       = syntheticModifierOwnerProduction
    val catalog    = Scala3PsiProductionCatalog.Reviewed.copy(
      productions = Scala3PsiProductionCatalog.Reviewed.productions :+ root
    )
    val compiler   = aggregate(Vector(runtime))
    val prepared   = PreparedProductionCatalog
      .prepareRuntimeSubset(catalog, runtime, compiler, contractSurfaces(catalog))
      .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
    val evidence   = ProvisionalSourceEvidencePlanner
      .plan(value)
      .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
    val plan       = WholeFileProductionPlanner
      .plan(value, evidence, prepared)
      .fold(failure => throw new AssertionError(failure.toString), identity)
    val structure  = WholeFileProductionPlanRenderer.structure(plan)
    val categories = structure.rows.map(_.takeWhile(_ != '\t'))

    assertEquals(
      StructuralRows.row("source", value.sourceUri.value, value.sourceDigest, evidence.parserEvidenceFingerprint),
      structure.rows.head
    )
    assertEquals(
      Vector(
        "source",
        "lexical",
        "composite",
        "composite-child",
        "physical-leaf",
        "structural-event",
        "target",
        "accessor",
        "persistence",
        "navigation"
      ),
      categories.distinct
    )
    assertTrue(structure.rows.exists(row => row.startsWith("composite\t") && row.contains("\t22\t27\t")))
    assertTrue(structure.rows.exists(row => row.startsWith("physical-leaf\t0\t") && row.contains("\t0\t1\t")))
    assertTrue(structure.rows.exists(_.contains(PsiOutputRoleId.ModifierList.value)))
    assertTrue(structure.rows.exists(_.contains(PsiOutputRoleId.AnnotationArguments.value)))
    assertTrue(structure.rows.exists(_.contains(ModifierAnnotationPersistenceSurfaces.ModifierSerializer)))
    assertEquals(structure, WholeFileProductionPlanRenderer.structure(plan))

    sys.env
      .get("METALLURGY_CATALOG_STRUCTURE_RUN_ID")
      .foreach: runId =>
        require(runId.matches("[A-Za-z0-9._-]+"), s"invalid catalog structure run ID: $runId")
        writeStructureEvidence(runId, structure)

  @Test def syntheticDefinitionRoutePlansExactModifierAnnotationAndOpaquePayloadRanges(): Unit =
    val value            = annotationModifierSnapshot
    val runtime          = inventory(value)
    val root             = syntheticModifierOwnerProduction
    val catalog          = Scala3PsiProductionCatalog.Reviewed.copy(
      productions = Scala3PsiProductionCatalog.Reviewed.productions :+ root
    )
    val aggregate        = this.aggregate(Vector(runtime))
    val surface          = contractSurfaces(catalog)
    val prepared         = PreparedProductionCatalog
      .prepareRuntimeSubset(catalog, runtime, aggregate, surface)
      .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
    val evidence         = ProvisionalSourceEvidencePlanner
      .plan(value)
      .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
    val plan             = WholeFileProductionPlanner
      .plan(value, evidence, prepared)
      .fold(failure => throw new AssertionError(failure.toString), identity)
    val rolesByComposite = plan.targetAssertions.collect:
      case PlannedTargetAssertion(
            TargetAssertionOwner.Composite(instance),
            PlannedTargetIdentity.OutputRole(role),
            _
          ) =>
        instance -> role
    val roleByComposite  = rolesByComposite.toMap
    val rangesByRole     = plan.composites
      .groupMap(composite => roleByComposite(composite.instance).value)(_.range)
      .view
      .mapValues(_.sortBy(range => (range.startOffset, range.endOffset)))
      .toMap

    assertEquals(Vector(PcSourceRange(22, 27)), rangesByRole(PsiOutputRoleId.ModifierList.value))
    assertEquals(Vector(PcSourceRange(0, 21)), rangesByRole(PsiOutputRoleId.Annotations.value))
    assertEquals(Vector(PcSourceRange(0, 21)), rangesByRole(PsiOutputRoleId.Annotation.value))
    assertEquals(Vector(PcSourceRange(1, 21)), rangesByRole(PsiOutputRoleId.AnnotationExpr.value))
    assertEquals(Vector(PcSourceRange(1, 21)), rangesByRole(PsiOutputRoleId.ConstructorInvocation.value))
    assertEquals(Vector(PcSourceRange(11, 21)), rangesByRole(PsiOutputRoleId.AnnotationArguments.value))
    assertEquals(
      Vector(PcSourceRange(12, 15), PcSourceRange(17, 20)),
      rangesByRole(PsiOutputRoleId.ExpressionPayload.value)
    )
    assertEquals(value.sourceText, evidence.reconstruct(value.sourceText))
    assertEquals(
      value.sourceLength,
      plan.physicalLeafOwnership.map(leaf => leaf.end - leaf.start).sum
    )
    assertEquals(
      plan.physicalLeafOwnership.map(leaf => leaf.start -> leaf.end),
      plan.physicalLeafOwnership.map(leaf => leaf.start -> leaf.end).distinct
    )
    val packetRoles = Set(
      GrammarRoleId.ExpressionPayload,
      GrammarRoleId.Modifiers,
      GrammarRoleId.AccessModifier,
      GrammarRoleId.KeywordModifier,
      GrammarRoleId.Annotations,
      GrammarRoleId.Annotation,
      GrammarRoleId.AnnotationArguments
    )
    assertFalse(
      Scala3PsiProductionCatalog.Reviewed.productions
        .filter(_.grammarRoleIds.exists(packetRoles))
        .exists(_.pattern.occurrences.exists(_.context == ContextPattern.Root))
    )

    def plannedAccess(value: ParserSyntaxSnapshot) =
      val runtime   = inventory(value)
      val aggregate = this.aggregate(Vector(runtime))
      val prepared  = PreparedProductionCatalog
        .prepareRuntimeSubset(catalog, runtime, aggregate, surface)
        .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
      val evidence  = ProvisionalSourceEvidencePlanner
        .plan(value)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
      val plan      = WholeFileProductionPlanner
        .plan(value, evidence, prepared)
        .fold(failure => throw new AssertionError(failure.toString), identity)
      val roles     = plan.targetAssertions.collect:
        case PlannedTargetAssertion(
              TargetAssertionOwner.Composite(instance),
              PlannedTargetIdentity.OutputRole(role),
              _
            ) =>
          instance -> role
      val ranges    = plan.composites.groupMap(composite => roles.toMap.apply(composite.instance))(_.range)
      plan -> ranges

    val accessValue                = qualifiedAccessSnapshot
    val (accessPlan, accessRanges) = plannedAccess(accessValue)
    assertEquals(Vector(PcSourceRange(0, 20)), accessRanges(PsiOutputRoleId.ModifierList))
    assertEquals(Vector(PcSourceRange(0, 14)), accessRanges(PsiOutputRoleId.AccessModifier))
    assertTrue(
      accessPlan.physicalLeafOwnership.exists(leaf =>
        leaf.start == 0 && leaf.end == 7 && leaf.target == TerminalLeafTarget.Token(
          NativePsiElementBindings.AccessModifierKeywordSurfaceIds("Private"),
          Some("private")
        )
      )
    )
    Vector(
      accessRangeSnapshot("private [scope]", "Private", "scope")     -> PcSourceRange(0, 15),
      accessRangeSnapshot("private/*c*/[scope]", "Private", "scope") -> PcSourceRange(0, 19),
      accessRangeSnapshot("protected(x: Int)", "Protected", "")      -> PcSourceRange(0, 9)
    ).foreach: (value, expected) =>
      val (plan, ranges) = plannedAccess(value)
      assertEquals(value.sourceText, Vector(expected), ranges(PsiOutputRoleId.ModifierList))
      assertEquals(value.sourceText, Vector(expected), ranges(PsiOutputRoleId.AccessModifier))
      assertEquals(
        value.sourceText,
        plan.physicalLeafOwnership.map(leaf => value.sourceText.substring(leaf.start, leaf.end)).mkString
      )
      assertEquals(
        (0 until value.sourceLength).toVector,
        plan.physicalLeafOwnership.flatMap(leaf => leaf.start until leaf.end)
      )

    val annotationOnlyValue     = annotationOnlySnapshot
    val annotationOnlyRuntime   = inventory(annotationOnlyValue)
    val annotationOnlyAggregate = this.aggregate(Vector(annotationOnlyRuntime))
    val annotationOnlyPrepared  = PreparedProductionCatalog
      .prepareRuntimeSubset(catalog, annotationOnlyRuntime, annotationOnlyAggregate, surface)
      .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
    val annotationOnlyPlan      = WholeFileProductionPlanner
      .plan(
        annotationOnlyValue,
        ProvisionalSourceEvidencePlanner.plan(annotationOnlyValue).toOption.get,
        annotationOnlyPrepared
      )
      .fold(failure => throw new AssertionError(failure.toString), identity)
    val annotationOnlyRoles     = annotationOnlyPlan.targetAssertions.collect:
      case PlannedTargetAssertion(
            TargetAssertionOwner.Composite(instance),
            PlannedTargetIdentity.OutputRole(role),
            _
          ) =>
        instance -> role
    val annotationOnlyRanges    = annotationOnlyPlan.composites
      .groupMap(composite => annotationOnlyRoles.toMap.apply(composite.instance))(_.range)
    assertEquals(Vector(PcSourceRange(21, 21)), annotationOnlyRanges(PsiOutputRoleId.ModifierList))
    assertEquals(Vector(PcSourceRange(0, 21)), annotationOnlyRanges(PsiOutputRoleId.Annotations))
    assertEquals(
      annotationOnlyValue.sourceText,
      annotationOnlyPlan.physicalLeafOwnership
        .map(leaf => annotationOnlyValue.sourceText.substring(leaf.start, leaf.end))
        .mkString
    )

    val triviaValue     = annotationTriviaSnapshot
    val triviaRuntime   = inventory(triviaValue)
    val triviaAggregate = this.aggregate(Vector(triviaRuntime))
    val triviaPrepared  = PreparedProductionCatalog
      .prepareRuntimeSubset(catalog, triviaRuntime, triviaAggregate, surface)
      .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
    val triviaPlan      = WholeFileProductionPlanner
      .plan(
        triviaValue,
        ProvisionalSourceEvidencePlanner.plan(triviaValue).toOption.get,
        triviaPrepared
      )
      .fold(failure => throw new AssertionError(failure.toString), identity)
    val triviaRoles     = triviaPlan.targetAssertions.collect:
      case PlannedTargetAssertion(
            TargetAssertionOwner.Composite(instance),
            PlannedTargetIdentity.OutputRole(role),
            _
          ) =>
        instance -> role
    val payloadRanges   = triviaPlan.composites
      .filter(composite => triviaRoles.toMap.get(composite.instance).contains(PsiOutputRoleId.ExpressionPayload))
      .map(_.range)
    val expectedPayload = "\"[^\"]*\"".r
      .findAllMatchIn(triviaValue.sourceText)
      .map(value => PcSourceRange(value.start, value.end))
      .toVector
    assertEquals(expectedPayload, payloadRanges)
    assertEquals(
      Vector("\"m\"", "\"1\""),
      payloadRanges.map(range => triviaValue.sourceText.substring(range.startOffset, range.endOffset))
    )
    assertTrue(
      triviaValue.comments.forall(comment =>
        payloadRanges.forall(payload =>
          payload.endOffset <= comment.range.startOffset || comment.range.endOffset <= payload.startOffset
        )
      )
    )
    assertEquals(
      (0 until triviaValue.sourceLength).toVector,
      triviaPlan.physicalLeafOwnership.flatMap(leaf => leaf.start until leaf.end)
    )
    assertEquals(
      triviaValue.sourceText,
      triviaPlan.physicalLeafOwnership.map(leaf => triviaValue.sourceText.substring(leaf.start, leaf.end)).mkString
    )

  @Test def sharedTransparentLoweringMergesTwoProductsAndCoLocatedEventsIntoOneClosedRole(): Unit =
    val value             = sharedLoweringSnapshot
    val runtime           = inventory(value)
    val catalog           = sharedLoweringCatalog
    val evidence          = ProvisionalSourceEvidencePlanner.plan(value).toOption.get
    val compiler          = aggregate(Vector(runtime))
    val surface           = sharedLoweringSurfaces
    val first             = planned(value, evidence, catalog, compiler, surface)
      .fold(failure => throw new AssertionError(failure.toString), identity)
    val reorderedRuntime  = runtime.copy(shapes = runtime.shapes.reverse, nodes = runtime.nodes.reverse)
    val reorderedCompiler = aggregate(Vector(reorderedRuntime))
    val second            = planned(
      value,
      evidence,
      catalog.copy(productions = catalog.productions.reverse),
      reorderedCompiler,
      surface.copy(rows = surface.rows.reverse)
    ).fold(failure => throw new AssertionError(failure.toString), identity)
    assertArrayEquals(compiler.canonicalBytes, reorderedCompiler.canonicalBytes)
    assertEquals(first, second)
    assertEquals(
      Set(SharedProductGrammarRole),
      catalog.productions
        .filter(production => Set("ExactLeft", "ExactRight")(production.pattern.prefix))
        .map(_.grammarRoleId)
        .toSet
    )
    assertTrue(catalog.productions.find(_.id == "exact-root").get.effectiveOutputTemplate.composites.isEmpty)

    assertEquals(value.sourceText, first.lexicalContract.reconstruct(value.sourceText))
    assertEquals(
      value.sourceText,
      first.physicalLeafOwnership
        .sortBy(leaf => (leaf.start, leaf.end))
        .map(leaf => value.sourceText.substring(leaf.start, leaf.end))
        .mkString
    )
    assertEquals(Vector((0, 1), (1, 2)), first.physicalLeafOwnership.map(leaf => leaf.start -> leaf.end))
    assertEquals(evidence.atoms.map(_.id), first.physicalLeafOwnership.map(_.atomId))
    assertEquals(first.physicalLeafOwnership.size, first.physicalLeafOwnership.map(_.atomId).distinct.size)
    assertEquals(Vector(2L, 3L), first.physicalLeafOwnership.map(_.sourceOwner.valueId))
    assertEquals(1, first.composites.size)
    assertEquals(PcSourceRange(0, 2), first.composites.head.range)
    assertEquals("exact-left-product", first.composites.head.productionId)
    assertTrue(first.composites.head.children.isEmpty)
    assertTrue(
      first.physicalLeafOwnership.forall(_.owner == PhysicalLeafOwner.Composite(first.composites.head.instance))
    )

    val coLocatedEvents = evidence.structural.collect:
      case event @ StructuralSourceEvidence(
            SourceEvidenceEventId.Positioned(id @ (10L | 11L)),
            _,
            ParserNodePosition.Positioned(PcSourceRange(1, 1), 1, ParserPositionProvenance.SourceDerived)
          ) =>
        id -> event.id
    assertEquals(Vector(10L, 11L), coLocatedEvents.map(_._1).sorted)
    assertEquals(2, coLocatedEvents.map(_._2).distinct.size)
    assertEquals(coLocatedEvents.map(_._2).toSet, first.structuralEvidenceOwnership.map(_.eventId).toSet)
    assertEquals(
      first.structuralEvidenceOwnership.size,
      first.structuralEvidenceOwnership.map(_.eventId).distinct.size
    )
    assertTrue(first.structuralEvidenceOwnership.forall(_.owner.role == SharedOutputRole))

    assertEquals(
      Vector(PlannedTargetIdentity.OutputRole(SharedOutputRole)),
      first.targetAssertions.map(_.targetIdentity)
    )
    assertEquals(
      Vector(PlannedAccessorAssertion(first.composites.head.instance, "test.host.shared.accessor", required = true)),
      first.accessorAssertions
    )
    assertEquals(
      Vector(
        PlannedStubAssertion(
          first.composites.head.instance,
          "test.host.shared.stub",
          "test.host.shared.serializer",
          Vector("test.host.shared.index"),
          "test.host.shared.stub-navigation"
        )
      ),
      first.stubAssertions
    )
    assertEquals(
      Vector(PlannedNavigationAssertion(first.composites.head.instance, NavigationObligation.Self)),
      first.navigationAssertions
    )
    assertTrue(first.virtualLayout.isEmpty)

  @Test def wholeFilePlanningCompilesAClosedTypedPlanDeterministically(): Unit =
    val value     = snapshot("/one", 1, Vector.empty)
    val compiler  = inventory(value)
    val catalog   = completeCatalog(compiler)
    val evidence  = ProvisionalSourceEvidencePlanner.plan(value).toOption.get
    val aggregate = this.aggregate(Vector(compiler))
    val surface   = surfaces(catalog)
    val first     = planned(value, evidence, catalog, aggregate, surface)
      .fold(failure => throw new AssertionError(failure.toString), identity)
    val second    = planned(
      value,
      evidence,
      catalog.copy(productions = catalog.productions.reverse),
      aggregate,
      surface.copy(rows = surface.rows.reverse)
    )
      .fold(failure => throw new AssertionError(failure.toString), identity)
    assertEquals(first, second)
    assertEquals(value.sourceUri, first.sourceUri)
    assertEquals(value.sourceDigest, first.sourceDigest)
    assertEquals(evidence.parserEvidenceFingerprint, first.parserEvidenceFingerprint)
    assertEquals(Vector("Root", "Child"), first.composites.map(_.productionId))
    assertEquals(
      Vector(PsiOutputRoleId("test.output.Root"), PsiOutputRoleId("test.output.Child")),
      first.targetAssertions.collect:
        case PlannedTargetAssertion(_, PlannedTargetIdentity.OutputRole(outputRoleId), _) => outputRoleId
    )
    assertTrue(
      first.targetAssertions.forall(_.targetIdentity.isInstanceOf[PlannedTargetIdentity.OutputRole])
    )
    assertEquals(Vector.empty, first.virtualLayout)
    assertEquals(Vector.empty, first.accessorAssertions)
    assertEquals(Vector.empty, first.stubAssertions)
    val leaf      = first.physicalLeafOwnership.head
    val child     = first.composites(1).instance
    assertEquals(
      (SourceAtomId(0, 0), 0, 1, PhysicalLeafOwner.Composite(child), "contents"),
      (leaf.atomId, leaf.start, leaf.end, leaf.owner, leaf.terminalId)
    )
    assertEquals("x", value.sourceText.substring(leaf.start, leaf.end))
    assertEquals(
      Vector(ParserFieldPathSegment.NamedField("children"), ParserFieldPathSegment.RepeatedIndex(0)),
      first.composites.head.children.head.fieldPath
    )

  @Test def wholeFilePlanningLowersLocalParentsAndTransparentOutputs(): Unit =
    val value     = snapshot("/outputs", 1, Vector.empty)
    val compiler  = inventory(value)
    val base      = completeCatalog(compiler)
    val aggregate = this.aggregate(Vector(compiler))
    val evidence  = ProvisionalSourceEvidencePlanner.plan(value).toOption.get
    val root      = base.productions.find(_.id == "Root").get
    val self      = root.effectiveOutputTemplate.composites.head

    val wrappedRoot    = root.copy(outputTemplate =
      Some(
        LocalOutputCompositeTemplate(
          Vector(self.copy(id = "outer"), self.copy(id = "inner", parentId = Some("outer"))),
          Map("child" -> Some("inner"))
        )
      )
    )
    val wrappedCatalog = base.copy(productions = base.productions.map(p => if p.id == root.id then wrappedRoot else p))
    val wrapped        = planned(value, evidence, wrappedCatalog, aggregate, surfaces(wrappedCatalog))
      .fold(error => throw new AssertionError(error.toString), identity)
    val outer          = wrapped.composites.find(_.instance.localOutputId == "outer").get
    val inner          = wrapped.composites.find(_.instance.localOutputId == "inner").get
    assertEquals(Vector(inner.instance), outer.children.map(_.child))
    assertEquals("Child", wrapped.composites.find(_.instance == inner.children.head.child).get.productionId)
    assertEquals(3, wrapped.targetAssertions.count(_.owner.isInstanceOf[TargetAssertionOwner.Composite]))
    assertEquals(
      value.sourceText,
      wrapped.physicalLeafOwnership
        .sortBy(_.start)
        .map(leaf => value.sourceText.substring(leaf.start, leaf.end))
        .mkString
    )
    assertEquals(evidence.structural.map(_.id), wrapped.structuralEvidenceOwnership.map(_.eventId))

    val transparentRoot    =
      root.copy(outputTemplate = Some(LocalOutputCompositeTemplate(Vector.empty, Map("child" -> None))))
    val transparentCatalog =
      base.copy(productions = base.productions.map(p => if p.id == root.id then transparentRoot else p))
    val transparent        = planned(value, evidence, transparentCatalog, aggregate, surfaces(transparentCatalog))
      .fold(error => throw new AssertionError(error.toString), identity)
    assertEquals(Vector("Child"), transparent.composites.map(_.productionId))
    assertEquals(1, transparent.targetAssertions.count(_.owner.isInstanceOf[TargetAssertionOwner.Composite]))
    assertEquals(
      value.sourceText,
      transparent.physicalLeafOwnership
        .sortBy(_.start)
        .map(leaf => value.sourceText.substring(leaf.start, leaf.end))
        .mkString
    )
    assertEquals(evidence.structural.map(_.id), transparent.structuralEvidenceOwnership.map(_.eventId))

  @Test def emptyTransparentTemplatesValidateMountsAndAdvanceOverflowFailsClosed(): Unit =
    val value     = snapshot("/empty-template", 1, Vector.empty)
    val compiler  = inventory(value)
    val base      = completeCatalog(compiler)
    val aggregate = this.aggregate(Vector(compiler))
    val root      = base.productions.find(_.id == "Root").get
    val missing   = base.copy(productions = base.productions.map:
      case production if production.id == root.id =>
        production.copy(outputTemplate = Some(LocalOutputCompositeTemplate(Vector.empty, Map.empty)))
      case production                             => production
    )
    val errors    = Scala3PsiProductionCatalogValidator.validateExecutable(missing, compiler, surfaces(missing))
    assertTrue(errors.contains(CatalogValidationError.MissingChildMountRole(root.id, "child")))

    val output   = root.effectiveOutputTemplate.composites.head
    val overflow = base.copy(productions = base.productions.map:
      case production if production.id == root.id =>
        production.copy(outputTemplate =
          Some(
            root.effectiveOutputTemplate.copy(composites =
              Vector(
                output.copy(
                  range = OutputRangeDeclaration.BoundaryDerived(
                    OutputBoundary.ProductionStart(PositionProvenancePolicy.PositionedIncludingSynthetic),
                    OutputBoundary.Advance(
                      OutputBoundary.ProductionStart(PositionProvenancePolicy.PositionedIncludingSynthetic),
                      Int.MaxValue
                    )
                  )
                )
              )
            )
          )
        )
      case production                             => production
    )
    val failure  = planned(
      value,
      ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
      overflow,
      aggregate,
      surfaces(overflow)
    ).left.toOption.get
    assertTrue(failure.isInstanceOf[WholeFilePlanningFailure.OutputBoundaryResolutionFailed])

    val missingDelimiterBoundary = OutputBoundary.EvidenceBoundaryAfterChild(
      "child",
      ChildOccurrenceSelector.First,
      "child",
      ChildOccurrenceSelector.First,
      Vector("{"),
      PositionProvenancePolicy.SourceDerivedOnly
    )
    val missingDelimiter         = base.copy(productions = base.productions.map:
      case production if production.id == root.id =>
        production.copy(outputTemplate =
          Some(
            root.effectiveOutputTemplate.copy(composites =
              Vector(
                output.copy(
                  range = OutputRangeDeclaration.BoundaryDerived(
                    missingDelimiterBoundary,
                    OutputBoundary.ProductionEnd()
                  )
                )
              )
            )
          )
        )
      case production                             => production
    )
    val delimiterFailure         = planned(
      value,
      ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
      missingDelimiter,
      aggregate,
      surfaces(missingDelimiter)
    ).left.toOption.get
    assertTrue(delimiterFailure.isInstanceOf[WholeFilePlanningFailure.OutputBoundaryResolutionFailed])

  @Test def wholeFilePlanningFailsClosedForOwnershipAndChildContractGaps(): Unit =
    val value                                                                  = snapshot("/one", 1, Vector.empty)
    val compiler                                                               = inventory(value)
    val base                                                                   = completeCatalog(compiler)
    val evidence                                                               = ProvisionalSourceEvidencePlanner.plan(value).toOption.get
    val aggregate                                                              = this.aggregate(Vector(compiler))
    val root                                                                   = base.productions.find(_.id == "Root").get
    val child                                                                  = base.productions.find(_.id == "Child").get
    def failure(catalog: Scala3PsiProductionCatalog): WholeFilePlanningFailure =
      planned(value, evidence, catalog, aggregate, surfaces(catalog)).left.toOption.get

    val unowned = base.copy(productions =
      base.productions.map(p => if p.id == child.id then p.copy(terminals = Vector.empty) else p)
    )
    assertEquals(WholeFilePlanningFailure.UnownedSourceAtom(SourceAtomId(0, 0), 0, 1), failure(unowned))

    val parentFallback     = base.copy(productions =
      base.productions.map(p =>
        if p.id == root.id then
          p.copy(terminals =
            Vector(
              TerminalDeclaration(
                "contents",
                TerminalIntervalSelector.WholeProduction,
                TerminalLeafTarget.Parent,
                OccurrenceCardinality.ExactlyOne,
                PsiOutputRoleId.SourceTerminal
              )
            )
          )
        else p
      )
    )
    val parentFallbackPlan = planned(value, evidence, parentFallback, aggregate, surfaces(parentFallback))
      .fold(error => throw new AssertionError(error.toString), identity)
    assertEquals(
      child.id,
      parentFallbackPlan.composites
        .find(composite =>
          parentFallbackPlan.physicalLeafOwnership.head.owner == PhysicalLeafOwner.Composite(composite.instance)
        )
        .get
        .productionId
    )

    val trailingSource   = "x\n"
    val trailingValue    = value.copy(
      sourceText = trailingSource,
      sourceDigest = ParserSyntaxSnapshot.digest(trailingSource),
      sourceLength = trailingSource.length
    )
    val trailingCompiler = inventory(trailingValue)
    val wholeSource      = parentFallback.copy(productions =
      parentFallback.productions.map(production =>
        if production.id == root.id then
          production.copy(terminals = production.terminals.map(_.copy(selector = TerminalIntervalSelector.WholeSource)))
        else production
      )
    )
    val trailingPlan     = planned(
      trailingValue,
      ProvisionalSourceEvidencePlanner.plan(trailingValue).toOption.get,
      wholeSource,
      this.aggregate(Vector(trailingCompiler)),
      surfaces(wholeSource)
    ).fold(error => throw new AssertionError(error.toString), identity)
    val trailingLeaf     = trailingPlan.physicalLeafOwnership.last
    assertEquals("\n", trailingSource.substring(trailingLeaf.start, trailingLeaf.end))
    assertEquals(PhysicalLeafOwner.FileRoot, trailingLeaf.owner)

    val missingChildTerminal = wholeSource.copy(productions =
      wholeSource.productions.map(production =>
        if production.id == child.id then production.copy(terminals = Vector.empty) else production
      )
    )
    assertEquals(
      WholeFilePlanningFailure.UnownedSourceAtom(SourceAtomId(0, 0), 0, 1),
      planned(
        trailingValue,
        ProvisionalSourceEvidencePlanner.plan(trailingValue).toOption.get,
        missingChildTerminal,
        this.aggregate(Vector(trailingCompiler)),
        surfaces(missingChildTerminal)
      ).left.toOption.get
    )

    val emptySource   = ""
    val emptyPosition = ParserNodePosition.Positioned(
      PcSourceRange(0, 0),
      0,
      ParserPositionProvenance.SourceDerived
    )
    val emptyValue    = value.copy(
      sourceText = emptySource,
      sourceDigest = ParserSyntaxSnapshot.digest(emptySource),
      sourceLength = 0,
      nodes = Vector(value.nodes.head.copy(fields = Vector.empty, position = emptyPosition))
    )
    val emptyCompiler = inventory(emptyValue)
    val emptyBase     = completeCatalog(emptyCompiler)
    val emptyCatalog  = emptyBase.copy(productions =
      emptyBase.productions.map(production =>
        production.copy(terminals =
          Vector(
            TerminalDeclaration(
              "whole-source",
              TerminalIntervalSelector.WholeSource,
              TerminalLeafTarget.Parent,
              OccurrenceCardinality.ExactlyOne,
              PsiOutputRoleId.SourceTerminal,
              ownsStructuralEvidence = Some(true)
            )
          )
        )
      )
    )
    val emptyPlan     = planned(
      emptyValue,
      ProvisionalSourceEvidencePlanner.plan(emptyValue).toOption.get,
      emptyCatalog,
      this.aggregate(Vector(emptyCompiler)),
      surfaces(emptyCatalog)
    ).fold(error => throw new AssertionError(error.toString), identity)
    assertTrue(emptyPlan.physicalLeafOwnership.isEmpty)

    val conflict = base.copy(productions =
      base.productions.map(p =>
        if p.id == child.id then p.copy(terminals = p.terminals :+ p.terminals.head.copy(id = "duplicate")) else p
      )
    )
    assertTrue(failure(conflict).isInstanceOf[WholeFilePlanningFailure.ConflictingSourceAtomOwners])

    val cardinality = base.copy(productions =
      base.productions.map(p =>
        if p.id == root.id then
          p.copy(children = p.children.map(_.copy(cardinality = ChildCardinality.Repeated(2, None))))
        else p
      )
    )
    assertTrue(failure(cardinality).isInstanceOf[WholeFilePlanningFailure.ChildCardinalityMismatch])

    val layout = base.copy(productions =
      base.productions.map(p =>
        if p.id == child.id then p.copy(layouts = Vector(LayoutAlternative.Indented(Vector("i"), Vector("o")))) else p
      )
    )
    assertTrue(failure(layout).isInstanceOf[WholeFilePlanningFailure.UnsupportedLayout])

    val grouped     = base.copy(productions =
      base.productions.map(p =>
        if p.id == root.id then
          p.copy(children = p.children.map(_.copy(cardinality = ChildCardinality.Grouped(1, None))))
        else p
      )
    )
    val groupedPlan = planned(value, evidence, grouped, aggregate, surfaces(grouped))
      .fold(error => throw new AssertionError(error.toString), identity)
    assertEquals(Vector("Root", "Child"), groupedPlan.composites.map(_.productionId))

    val groupedMinimum = grouped.copy(productions =
      grouped.productions.map(p =>
        if p.id == root.id then
          p.copy(children = p.children.map(_.copy(cardinality = ChildCardinality.Grouped(2, None))))
        else p
      )
    )
    assertTrue(failure(groupedMinimum).isInstanceOf[WholeFilePlanningFailure.ChildCardinalityMismatch])

    val groupedMultipleRoots        = grouped.copy(productions = grouped.productions.map: p =>
      if p.id == child.id then
        val template = p.effectiveOutputTemplate
        p.copy(outputTemplate =
          Some(template.copy(composites = template.composites :+ template.composites.head.copy(id = "second")))
        )
      else p)
    val groupedMultipleRootsFailure = failure(groupedMultipleRoots)
    assertEquals(
      WholeFilePlanningFailure.InvalidCatalog(
        Vector(CatalogValidationError.OverlappingCompilerPositionSiblings("Child", None, "second", "self"))
      ),
      groupedMultipleRootsFailure
    )

    val unsupported = base.copy(productions =
      base.productions.map(p =>
        if p.id == root.id then
          p.copy(
            dispositions = p.dispositions.map(_.copy(kind = FieldDispositionKind.Unsupported)),
            children = Vector.empty
          )
        else p
      )
    )
    assertTrue(failure(unsupported).isInstanceOf[WholeFilePlanningFailure.UnsupportedFieldDisposition])

  @Test def wholeFilePlanningRejectsInactiveUnsupportedOrRecoveredCompilerDescendants(): Unit =
    val value               = snapshot("/inactive-unsupported", 1, Vector.empty)
    val compiler            = inventory(value)
    val base                = completeCatalog(compiler)
    val inactiveChild       = base.copy(productions = base.productions.map: production =>
      if production.id == "Root" then
        production.copy(
          dispositions = Vector(FieldDisposition("children", FieldDispositionKind.SemanticOnly)),
          children = Vector.empty,
          terminals = Vector(
            TerminalDeclaration(
              "source",
              TerminalIntervalSelector.WholeSource,
              TerminalLeafTarget.Parent,
              OccurrenceCardinality.ExactlyOne,
              PsiOutputRoleId.SourceTerminal
            )
          )
        )
      else production)
    val unsupported         = inactiveChild.copy(productions = inactiveChild.productions.map: production =>
      if production.id == "Child" then
        production.copy(
          dispositions = Vector(FieldDisposition("inactive", FieldDispositionKind.Unsupported)),
          pattern = production.pattern.copy(fields =
            Vector(CompilerFieldPattern("inactive", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)))
          )
        )
      else production)
    val unsupportedValue    = value.copy(nodes =
      value.nodes.updated(
        1,
        value
          .nodes(1)
          .copy(fields =
            Vector(
              ParserSyntaxField(
                "inactive",
                ParserFieldValue.Repeated(Vector.empty),
                Some(ParserDeclaredShape.Repeated(ParserDeclaredShape.Node))
              )
            )
          )
      )
    )
    val unsupportedCompiler = inventory(unsupportedValue)
    assertEquals(
      WholeFilePlanningFailure.UnsupportedFieldDisposition(
        ProductionInstanceId(
          InventoryKind.Node,
          2,
          Some(
            ProductionOccurrenceId(
              1,
              Vector(ParserFieldPathSegment.NamedField("children"), ParserFieldPathSegment.RepeatedIndex(0))
            )
          )
        ),
        "inactive"
      ),
      planned(
        unsupportedValue,
        ProvisionalSourceEvidencePlanner.plan(unsupportedValue).toOption.get,
        unsupported,
        aggregate(Vector(unsupportedCompiler)),
        surfaces(unsupported)
      ).left.toOption.get
    )

    val recovered = inactiveChild.copy(productions = inactiveChild.productions.map: production =>
      if production.id == "Child" then
        production.copy(recovery = RecoveryPolicy.DiagnosticBound(ParserDiagnosticSeverity.Error, Vector("recovered")))
      else production)
    // A DiagnosticBound production whose realizations never match its declared alternative id plans
    // ordinarily; the recovery activation requires a selected recovery realization plus its diagnostic.
    assertTrue(
      "a diagnostic-bound production without a recovery realization plans ordinarily",
      planned(
        value,
        ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
        recovered,
        aggregate(Vector(compiler)),
        surfaces(recovered)
      ).isRight
    )

  @Test def unsafeCandidateScopeWithUndischargedErrorFailsClosed(): Unit =
    val value       = snapshot("/unsafe-scope", 1, Vector.empty)
    val withError   = value.copy(diagnostics =
      Vector(
        ParserDiagnostic(
          ParserDiagnosticSeverity.Error,
          "unowned",
          Some(ParserDiagnosticPosition(PcSourceRange(0, 0), 0, ParserDiagnosticPositionProvenance.Synthetic))
        )
      )
    )
    val compiler    = inventory(withError)
    val base        = completeCatalog(compiler)
    val withAtomic  = base.copy(productions = base.productions.map: production =>
      if production.id == "Child" then
        val existing = production.effectiveOutputRealizations.head
        production.copy(
          outputRealizations = Vector(
            existing.copy(id = "self"),
            existing.copy(id = "fallback")
          ),
          realizationChoice = Some(
            RealizationChoice(Vector("self"), "fallback", RealizationChoicePolicy.AtomicWholePlan)
          )
        )
      else production)
    // An unsafe atomic construction fails closed at catalog validation: the fallback is not a
    // complete payload, so no plan can ship with the Error diagnostic unowned.
    val failure     = planned(
      withError,
      ProvisionalSourceEvidencePlanner.plan(withError).toOption.get,
      withAtomic,
      aggregate(Vector(compiler)),
      surfaces(withAtomic)
    ).left.toOption.get
    val description = failure.toString
    assertTrue(
      "an unsafe candidate scope with an undischarged Error must fail closed",
      description.contains("UnassignedDiagnostic") || description.contains("InvalidAtomicWholePlanChoice")
    )

  @Test def transparentSiblingLeafProvenanceDoesNotBecomeFileRootAncestry(): Unit =
    val baseValue       = snapshot("/transparent-siblings", 1, Vector.empty)
    val root            = baseValue.nodes.head.copy(fields =
      Vector(
        ParserSyntaxField("left", ParserFieldValue.Node(2)),
        ParserSyntaxField("right", ParserFieldValue.Node(3))
      )
    )
    val child           = baseValue
      .nodes(1)
      .copy(occurrences = Vector(ParserNodeOccurrence(1, Vector(ParserFieldPathSegment.NamedField("left")))))
    val sibling         = baseValue
      .nodes(1)
      .copy(
        id = 3,
        production = "Sibling",
        occurrences = Vector(
          ParserNodeOccurrence(
            1,
            Vector(ParserFieldPathSegment.NamedField("right"))
          )
        )
      )
    val value           = baseValue.copy(nodes = Vector(root, child, sibling))
    val compiler        = inventory(value)
    val base            = completeCatalog(compiler)
    val childProduction = base.productions.find(_.id == "Child").get
    val catalog         = base.copy(productions = base.productions.map: production =>
      if production.id == childProduction.id then
        production.copy(outputTemplate = Some(LocalOutputCompositeTemplate(Vector.empty, Map.empty)))
      else if production.id == "Root" then
        production.copy(
          dispositions = Vector("left", "right").map(FieldDisposition(_, FieldDispositionKind.Child)),
          children = Vector(
            ChildDeclaration("left", "left", ChildCardinality.ExactlyOne, "Child"),
            ChildDeclaration("right", "right", ChildCardinality.ExactlyOne, "Sibling")
          )
        )
      else production)
    val result          = planned(
      value,
      ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
      catalog,
      aggregate(Vector(compiler)),
      surfaces(catalog).copy(rows = surfaces(catalog).rows.filterNot(_.id == childProduction.targetSurfaceId))
    )
    val conflict        = result.left.toOption.get match
      case value: WholeFilePlanningFailure.ConflictingSourceAtomOwners => value
      case failure                                                     => throw new AssertionError(failure.toString)
    assertEquals(Vector(2L, 3L), conflict.owners.map(_._1.valueId).sorted)

  @Test def wholeFilePlanningRejectsMultiplyParentedDescendants(): Unit =
    val value     = sharedDescendantSnapshot
    val compiler  = inventory(value)
    val leaf      = compiler.shapes.find(_.id == 3).get
    assertEquals(
      Set("left", "right"),
      leaf.contexts
        .flatMap(_.ancestors.headOption)
        .flatMap(_.path.collect { case CatalogPathSegment.NamedField(name) => name }.lastOption)
        .toSet
    )
    val catalog   = completeCatalog(compiler)
    val aggregate = this.aggregate(Vector(compiler))
    Vector(
      value,
      value.copy(nodes = value.nodes.updated(1, value.nodes(1).copy(occurrences = value.nodes(1).occurrences.reverse)))
    )
      .foreach: candidate =>
        val result = planned(
          candidate,
          ProvisionalSourceEvidencePlanner.plan(candidate).toOption.get,
          catalog,
          aggregate,
          surfaces(catalog)
        )
        assertTrue(
          result.toString,
          result.left.toOption.get.isInstanceOf[WholeFilePlanningFailure.MultiplyConsumedChildReference]
        )

  @Test def wholeFilePlanningDoesNotShareUnrelatedTransparentAbsentProductions(): Unit =
    val baseValue = sharedDescendantSnapshot
    val value     = baseValue.copy(nodes = baseValue.nodes.map: node =>
      if node.id == 3 then node.copy(position = ParserNodePosition.Absent) else node)
    val compiler  = inventory(value)
    val base      = completeCatalog(compiler)
    val catalog   = base.copy(productions = base.productions.map: production =>
      if production.pattern.prefix == "Leaf" then
        production.copy(
          terminals = Vector.empty,
          outputTemplate = Some(LocalOutputCompositeTemplate(Vector.empty, Map.empty))
        )
      else production)
    val result    = planned(
      value,
      ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
      catalog,
      aggregate(Vector(compiler)),
      surfaces(catalog)
    )
    assertTrue(
      result.toString,
      result.left.toOption.get.isInstanceOf[WholeFilePlanningFailure.MultiplyConsumedChildReference]
    )

  @Test def wholeFilePlanningRejectsUnprobedNativeCandidates(): Unit =
    val value     = snapshot("/candidate", 1, Vector.empty)
    val compiler  = inventory(value)
    val base      = completeCatalog(compiler)
    val candidate = base.copy(productions = base.productions.map:
      case production if production.id == "Root" =>
        production.copy(targetRequirement = TargetRequirement.NativeCandidate)
      case production                            => production
    )
    val result    = planned(
      value,
      ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
      candidate,
      aggregate(Vector(compiler)),
      surfaces(candidate)
    )
    assertTrue(result.left.toOption.get.isInstanceOf[WholeFilePlanningFailure.UnprobedNativeCandidate])

  @Test def positionedChildOriginsRemainAbsoluteAndFailAtTheSupportedSubsetBoundary(): Unit =
    val value    = positionedChildSnapshot
    val compiler = inventory(value)
    val child    = compiler.shapes.find(row => row.kind == InventoryKind.Node && row.prefix == "Leaf").get
    assertEquals(
      Vector(
        InventoryContext(
          InventoryKind.Node,
          "Root",
          Vector(
            CatalogPathSegment.NamedField("mods"),
            CatalogPathSegment.RepeatedElement,
            CatalogPathSegment.NamedField("child")
          )
        )
      ),
      child.contexts
    )
    val catalog  = completeCatalog(compiler)
    val result   = planned(
      value,
      ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
      catalog,
      this.aggregate(Vector(compiler)),
      surfaces(catalog)
    )
    assertTrue(result.left.toOption.get.isInstanceOf[WholeFilePlanningFailure.UnsupportedPositionedChildren])

  @Test def absentOptionalTokenTerminalProducesNoTargetAssertion(): Unit =
    val original = snapshot("/absent", 1, Vector.empty)
    val value    = original.copy(nodes =
      original.nodes.map(node => if node.id == 2 then node.copy(position = ParserNodePosition.Absent) else node)
    )
    val compiler = inventory(value)
    val base     = completeCatalog(compiler)
    val root     = base.productions.find(_.id == "Root").get
    val child    = base.productions.find(_.id == "Child").get
    val catalog  = base.copy(productions = base.productions.map:
      case production if production.id == root.id  =>
        production.copy(terminals =
          Vector(
            TerminalDeclaration(
              "root-contents",
              TerminalIntervalSelector.WholeProduction,
              TerminalLeafTarget.Parent,
              OccurrenceCardinality.ExactlyOne,
              PsiOutputRoleId.SourceTerminal
            )
          )
        )
      case production if production.id == child.id =>
        production.copy(
          outputTemplate = Some(LocalOutputCompositeTemplate(Vector.empty, Map.empty)),
          terminals = Vector(
            TerminalDeclaration(
              "optional-token",
              TerminalIntervalSelector.WholeProduction,
              TerminalLeafTarget.Token("token.optional"),
              OccurrenceCardinality.Optional,
              PsiOutputRoleId.SourceTerminal,
              ownsStructuralEvidence = Some(true)
            )
          )
        )
      case production                              => production
    )
    val surface  = surfaces(catalog).copy(rows =
      surfaces(catalog).rows :+
        ScalaPsiSurfaceRow(
          "token.optional",
          SurfaceFactKind.Token,
          None,
          FactStatus.Available,
          SurfaceClassification.Derived
        )
    )
    val plan     = planned(
      value,
      ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
      catalog,
      this.aggregate(Vector(compiler)),
      surface
    )
      .fold(failure => throw new AssertionError(failure.toString), identity)
    assertFalse(
      plan.targetAssertions.exists(_.targetIdentity match
        case PlannedTargetIdentity.TokenRole(_, "token.optional") => true
        case _                                                    => false
      )
    )
    assertFalse(plan.physicalLeafOwnership.exists(_.terminalId == "optional-token"))

    val unownedCatalog = catalog.copy(productions =
      catalog.productions.map(production =>
        production.copy(terminals = production.terminals.map(_.copy(ownsStructuralEvidence = Some(false))))
      )
    )
    assertEquals(
      WholeFilePlanningFailure.FinalSourceEvidenceFailures(
        Vector(FinalSourceEvidenceFailure.UnownedEvent(SourceEvidenceEventId.Node(2)))
      ),
      planned(
        value,
        ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
        unownedCatalog,
        this.aggregate(Vector(compiler)),
        surface
      ).left.toOption.get
    )

  @Test def evidenceFingerprintMismatchFailsBeforeCatalogMatching(): Unit =
    val value    = snapshot("/one", 1, Vector.empty)
    val compiler = inventory(value)
    val catalog  = completeCatalog(compiler)
    val evidence = ProvisionalSourceEvidencePlanner.plan(value).toOption.get.copy(parserEvidenceFingerprint = "other")
    assertTrue(
      planned(
        value,
        evidence,
        catalog,
        aggregate(Vector(compiler)),
        surfaces(catalog)
      ).left.toOption.get
        .isInstanceOf[WholeFilePlanningFailure.EvidenceFingerprintMismatch]
    )

  @Test def wholeFilePlanningRecomputesDetachedEvidenceAndCompilerInventory(): Unit =
    val value     = snapshot("/one", 1, Vector.empty)
    val compiler  = inventory(value)
    val catalog   = completeCatalog(compiler)
    val evidence  = ProvisionalSourceEvidencePlanner.plan(value).toOption.get
    val firstAtom = evidence.atoms.head
    val detached  = evidence.copy(atoms = evidence.atoms.updated(0, firstAtom.copy(claims = Vector.empty)))
    assertEquals(
      Left(WholeFilePlanningFailure.SourceEvidencePlanMismatch),
      planned(
        value,
        detached,
        catalog,
        aggregate(Vector(compiler)),
        surfaces(catalog)
      )
    )
    assertEquals(
      (value.nodes.map(node => InventoryKind.Node -> node.id) ++
        value.positioned.map(positioned => InventoryKind.Positioned -> positioned.id)).toSet,
      compiler.shapes.map(row => row.kind -> row.id).toSet
    )

  @Test def wholeFilePlanningRejectsAnAggregateForAnotherCompilerIdentity(): Unit =
    val value            = snapshot("/one", 1, Vector.empty)
    val compiler         = inventory(value)
    val catalog          = completeCatalog(compiler)
    val catalogInventory = aggregate(Vector(compiler))
      .copy(identity = compiler.identity.copy(compilerOptions = compiler.identity.compilerOptions :+ "-different"))
    assertTrue(
      planned(
        value,
        ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
        catalog,
        catalogInventory,
        surfaces(catalog)
      ).left.toOption.get
        .isInstanceOf[WholeFilePlanningFailure.CatalogInventoryIdentityMismatch]
    )

  private def contractSurfaces(catalog: Scala3PsiProductionCatalog): ScalaPsiSurfaceInventory =
    val outputRows = catalog.productions
      .flatMap(_.effectiveOutputRealizations.flatMap(_.template.composites))
      .flatMap: output =>
        val persistence = output.persistence match
          case PersistenceObligations.NotApplicable                                   => Vector.empty
          case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
            Vector(
              stub       -> SurfaceFactKind.Stub,
              serializer -> SurfaceFactKind.Serializer,
              navigation -> SurfaceFactKind.Navigation
            ) ++ indices.map(_ -> SurfaceFactKind.Index)
        Vector(output.targetSurfaceId -> SurfaceFactKind.Element) ++
          output.accessors.map(accessor => accessor.surfaceId -> accessor.surfaceKind) ++ persistence
    val tokenRows  = catalog.productions.flatMap(_.terminals.collect:
      case TerminalDeclaration(_, _, TerminalLeafTarget.Token(surface, _), _, _, _) =>
        surface -> SurfaceFactKind.Token
    )
    ScalaPsiSurfaceInventory(
      (outputRows ++ tokenRows).distinct
        .map: (id, kind) =>
          ScalaPsiSurfaceRow(id, kind, None, FactStatus.Available, SurfaceClassification.Derived)
    )

  private def syntheticModifierOwnerProduction: Scala3PsiProduction =
    val modifierFields = Vector(
      CompilerFieldPattern("flags", CatalogValuePattern.Scalar("LongInteger")),
      CompilerFieldPattern("privateWithin", CatalogValuePattern.Name),
      CompilerFieldPattern("annotations", CatalogValuePattern.Repeated(CatalogValuePattern.Node)),
      CompilerFieldPattern("mods", CatalogValuePattern.Repeated(CatalogValuePattern.Positioned))
    )
    val modifierIds    = Scala3PsiProductionCatalog.Reviewed.productions
      .filter(_.pattern.kind == InventoryKind.Product)
      .map(_.id)
      .toSet
    Scala3PsiProduction(
      id = "synthetic-modifier-owner",
      grammarRoleId = GrammarRoleId.CompilationUnit,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "TypeDef",
        Vector(
          CompilerFieldPattern("mods", CatalogValuePattern.Product("Modifiers", modifierFields))
        ),
        Vector(
          CompilerProductionContextPattern(ContextPattern.Root, SourceClassification.SourceReachable)
        )
      ),
      dispositions = Vector(FieldDisposition("mods", FieldDispositionKind.Child)),
      children = Vector(
        ChildDeclaration(
          "modifiers",
          "mods",
          ChildCardinality.ExactlyOne,
          modifierIds.head,
          modifierIds.tail
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "source",
          TerminalIntervalSelector.WholeSource,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = "test.synthetic.modifier-owner",
      targetRequirement = TargetRequirement.Compatible,
      accessors = Vector.empty,
      persistence = PersistenceObligations.NotApplicable,
      outputTemplate = Some(LocalOutputCompositeTemplate(Vector.empty, Map("modifiers" -> None))),
      outputRoleId = None
    )

  private def annotationOnlySnapshot: ParserSyntaxSnapshot =
    val base   = annotationModifierSnapshot
    val source = "@deprecated(\"m\", \"1\")"
    base.copy(
      sourceText = source,
      sourceDigest = ParserSyntaxSnapshot.digest(source),
      sourceLength = source.length,
      nodes = base.nodes.map:
        case node if node.id == base.rootNodeId =>
          node.copy(
            fields = node.fields.map:
              case field @ ParserSyntaxField("mods", ParserFieldValue.Product(prefix, fields), _) =>
                field.copy(value =
                  ParserFieldValue.Product(
                    prefix,
                    fields.map:
                      case value @ ParserSyntaxField("mods", _, _) =>
                        value.copy(
                          value = ParserFieldValue.Repeated(Vector.empty),
                          declaredShape = Some(ParserDeclaredShape.Repeated(ParserDeclaredShape.Positioned))
                        )
                      case value                                   => value
                  )
                )
              case field                                                                          => field,
            position = ParserNodePosition.Positioned(
              PcSourceRange(0, source.length),
              0,
              ParserPositionProvenance.SourceDerived
            )
          )
        case node                               => node,
      positioned = Vector.empty
    )

  private def annotationTriviaSnapshot: ParserSyntaxSnapshot =
    val base                                                                             = annotationModifierSnapshot
    val source                                                                           = "@deprecated(/*lead*/ \"m\" /*after*/, /*next*/ \"1\" /*tail*/) final"
    val annotationEnd                                                                    = source.lastIndexOf(')') + 1
    val finalStart                                                                       = source.lastIndexOf("final")
    val literals                                                                         = "\"[^\"]*\"".r.findAllMatchIn(source).toVector
    def position(start: Int, end: Int, point: Int, provenance: ParserPositionProvenance) =
      ParserNodePosition.Positioned(PcSourceRange(start, end), point, provenance)
    val nodes                                                                            = base.nodes.map:
      case node if node.id == 1 =>
        node.copy(position = position(0, source.length, 0, ParserPositionProvenance.SourceDerived))
      case node if node.id == 2 =>
        node.copy(position = position(0, annotationEnd, 1, ParserPositionProvenance.SourceDerived))
      case node if node.id == 6 =>
        node.copy(
          position =
            position(literals(0).start, literals(0).end, literals(0).start, ParserPositionProvenance.SourceDerived)
        )
      case node if node.id == 7 =>
        node.copy(
          position =
            position(literals(1).start, literals(1).end, literals(1).start, ParserPositionProvenance.SourceDerived)
        )
      case node                 => node
    val keyword                                                                          = base.positioned.head.copy(
      position = position(finalStart, finalStart + "final".length, finalStart, ParserPositionProvenance.SourceDerived)
    )
    val comments                                                                         = "/\\*[^*]*\\*/".r
      .findAllMatchIn(source)
      .map: value =>
        ParserComment(PcSourceRange(value.start, value.end), value.matched, ParserCommentKind.Block)
    base.copy(
      sourceText = source,
      sourceDigest = ParserSyntaxSnapshot.digest(source),
      sourceLength = source.length,
      nodes = nodes,
      positioned = Vector(keyword),
      comments = comments.toVector
    )

  private def qualifiedAccessSnapshot: ParserSyntaxSnapshot =
    accessRangeSnapshot("private[scope] final", "Private", "scope", Some(("Final", 15, 20)))

  private def accessRangeSnapshot(
      source: String,
      accessProduction: String,
      privateWithin: String,
      trailingModifier: Option[(String, Int, Int)] = None
  ): ParserSyntaxSnapshot =
    val position                                                                = ParserNodePosition.Positioned(
      PcSourceRange(0, source.length),
      0,
      ParserPositionProvenance.SourceDerived
    )
    val modifiers                                                               = ParserFieldValue.Product(
      "Modifiers",
      Vector(
        ParserSyntaxField("flags", ParserFieldValue.Scalar(ParserScalar.LongInteger(0L))),
        ParserSyntaxField("privateWithin", ParserFieldValue.Name(privateWithin)),
        ParserSyntaxField(
          "annotations",
          ParserFieldValue.Repeated(Vector.empty),
          Some(ParserDeclaredShape.Repeated(ParserDeclaredShape.Node))
        ),
        ParserSyntaxField(
          "mods",
          ParserFieldValue.Repeated(
            Vector(ParserFieldValue.Positioned(1)) ++ trailingModifier.map(_ => ParserFieldValue.Positioned(2))
          )
        )
      )
    )
    val root                                                                    = ParserSyntaxNode(
      1,
      "TypeDef",
      Vector(ParserSyntaxField("mods", modifiers)),
      position,
      Vector.empty
    )
    def keyword(id: Long, production: String, start: Int, end: Int, index: Int) = ParserPositionedSyntax(
      id,
      production,
      Vector.empty,
      ParserNodePosition.Positioned(
        PcSourceRange(start, end),
        start,
        ParserPositionProvenance.SourceDerived
      ),
      Vector(
        ParserPositionedOccurrence(
          1,
          Vector(
            ParserFieldPathSegment.NamedField("mods"),
            ParserFieldPathSegment.NestedProductBoundary("Modifiers"),
            ParserFieldPathSegment.NamedField("mods"),
            ParserFieldPathSegment.RepeatedIndex(index)
          )
        )
      )
    )
    val accessText                                                              = accessProduction.toLowerCase
    val events                                                                  = Vector(keyword(1, accessProduction, 0, accessText.length, 0)) ++
      trailingModifier.map((production, start, end) => keyword(2, production, start, end, 1))
    val comments                                                                = "/\\*[^*]*\\*/".r
      .findAllMatchIn(source)
      .map(value => ParserComment(PcSourceRange(value.start, value.end), value.matched, ParserCommentKind.Block))
      .toVector
    val base                                                                    = snapshot(s"/access-range-${source.length}-${accessProduction}", 1, Vector.empty)
    base.copy(
      sourceUri = base.sourceUri,
      sourceText = source,
      sourceDigest = ParserSyntaxSnapshot.digest(source),
      sourceLength = source.length,
      compilerOptions = base.compilerOptions,
      rootNodeId = 1,
      nodes = Vector(root),
      positioned = events,
      comments = comments,
      diagnostics = Vector.empty,
      capabilities = base.capabilities,
      compilerIdentity = base.compilerIdentity,
      endMarkers = Vector.empty,
      runtimeSupplements = Vector.empty,
      attachments = Vector.empty
    )

  private def sharedLoweringSnapshot: ParserSyntaxSnapshot =
    val base                                                                         = snapshot("/shared-lowering", 1, Vector.empty)
    val root                                                                         = ParserSyntaxNode(
      1,
      "ExactRoot",
      Vector(
        ParserSyntaxField(
          "products",
          ParserFieldValue.Repeated(Vector(ParserFieldValue.Node(2), ParserFieldValue.Node(3)))
        )
      ),
      ParserNodePosition.Positioned(PcSourceRange(0, 2), 0, ParserPositionProvenance.SourceDerived),
      Vector.empty
    )
    def product(id: Long, production: String, start: Int, eventId: Long, index: Int) =
      ParserSyntaxNode(
        id,
        production,
        Vector(ParserSyntaxField("event", ParserFieldValue.Positioned(eventId))),
        ParserNodePosition.Positioned(
          PcSourceRange(start, start + 1),
          start,
          ParserPositionProvenance.SourceDerived
        ),
        Vector(
          ParserNodeOccurrence(
            1,
            Vector(ParserFieldPathSegment.NamedField("products"), ParserFieldPathSegment.RepeatedIndex(index))
          )
        )
      )
    def event(id: Long, owner: Long)                                                 = ParserPositionedSyntax(
      id,
      "ExactEvent",
      Vector.empty,
      ParserNodePosition.Positioned(PcSourceRange(1, 1), 1, ParserPositionProvenance.SourceDerived),
      Vector(ParserPositionedOccurrence(owner, Vector(ParserFieldPathSegment.NamedField("event"))))
    )
    val source                                                                       = "xy"
    base.copy(
      sourceText = source,
      sourceDigest = ParserSyntaxSnapshot.digest(source),
      sourceLength = source.length,
      nodes = Vector(root, product(2, "ExactLeft", 0, 10, 0), product(3, "ExactRight", 1, 11, 1)),
      positioned = Vector(event(10, 2), event(11, 3))
    )

  private def sharedLoweringCatalog: Scala3PsiProductionCatalog =
    val sourceReachable                     = SourceClassification.SourceReachable
    val rootPattern                         = CompilerProductionPattern(
      InventoryKind.Node,
      "ExactRoot",
      Vector(CompilerFieldPattern("products", CatalogValuePattern.Repeated(CatalogValuePattern.Node))),
      Vector(CompilerProductionContextPattern(ContextPattern.Root, sourceReachable))
    )
    def childPattern(prefix: String)        = CompilerProductionPattern(
      InventoryKind.Node,
      prefix,
      Vector(CompilerFieldPattern("event", CatalogValuePattern.Positioned)),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "ExactRoot",
            Vector(CatalogPathSegment.NamedField("products"), CatalogPathSegment.RepeatedElement)
          ),
          sourceReachable
        )
      )
    )
    val eventPattern                        = CompilerProductionPattern(
      InventoryKind.Positioned,
      "ExactEvent",
      Vector.empty,
      Vector("ExactLeft", "ExactRight").map(owner =>
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            owner,
            Vector(CatalogPathSegment.NamedField("event"))
          ),
          sourceReachable
        )
      )
    )
    val transparent                         = LocalOutputCompositeTemplate(Vector.empty, Map("products" -> None))
    val eventTransparent                    = LocalOutputCompositeTemplate(Vector.empty, Map.empty)
    val root                                = Scala3PsiProduction(
      id = "exact-root",
      grammarRoleId = TransparentRootGrammarRole,
      pattern = rootPattern,
      dispositions = Vector(FieldDisposition("products", FieldDispositionKind.Child)),
      children = Vector(
        ChildDeclaration(
          "products",
          "products",
          ChildCardinality.Grouped(2, Some(2)),
          "exact-left-product",
          Set("exact-right-product")
        )
      ),
      terminals = Vector.empty,
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = "test.host.transparent.root",
      targetRequirement = TargetRequirement.Compatible,
      accessors = Vector.empty,
      persistence = PersistenceObligations.NotApplicable,
      outputTemplate = Some(transparent),
      outputRoleId = None
    )
    def product(id: String, prefix: String) = Scala3PsiProduction(
      id = id,
      grammarRoleId = SharedProductGrammarRole,
      pattern = childPattern(prefix),
      dispositions = Vector(FieldDisposition("event", FieldDispositionKind.Child)),
      children = Vector(ChildDeclaration("event", "event", ChildCardinality.ExactlyOne, "exact-event")),
      terminals = Vector(
        TerminalDeclaration(
          "source",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          SharedOutputRole
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = "test.host.shared.element",
      targetRequirement = TargetRequirement.Compatible,
      accessors = Vector(AccessorObligation("test.host.shared.accessor", required = true)),
      persistence = PersistenceObligations.Required(
        "test.host.shared.stub",
        "test.host.shared.serializer",
        Vector("test.host.shared.index"),
        "test.host.shared.stub-navigation"
      ),
      navigation = Some(NavigationObligation.Self),
      outputRoleId = Some(SharedOutputRole)
    )
    val positioned                          = Scala3PsiProduction(
      id = "exact-event",
      grammarRoleId = StructuralEventGrammarRole,
      pattern = eventPattern,
      dispositions = Vector.empty,
      children = Vector.empty,
      terminals = Vector(
        TerminalDeclaration(
          "event",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.Optional,
          SharedOutputRole,
          ownsStructuralEvidence = Some(true)
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = "test.host.transparent.event",
      targetRequirement = TargetRequirement.Compatible,
      accessors = Vector.empty,
      persistence = PersistenceObligations.NotApplicable,
      outputTemplate = Some(eventTransparent),
      outputRoleId = None
    )
    Scala3PsiProductionCatalog(
      Vector(
        root,
        product("exact-left-product", "ExactLeft"),
        product("exact-right-product", "ExactRight"),
        positioned
      ),
      StableRoleInventory(
        Set(TransparentRootGrammarRole, SharedProductGrammarRole, StructuralEventGrammarRole),
        Set(SharedOutputRole)
      )
    )

  private def sharedLoweringSurfaces: ScalaPsiSurfaceInventory =
    def row(id: String, kind: SurfaceFactKind) = ScalaPsiSurfaceRow(
      id,
      kind,
      None,
      FactStatus.Available,
      SurfaceClassification.Derived
    )
    ScalaPsiSurfaceInventory(
      Vector(
        row("test.host.shared.element", SurfaceFactKind.Element),
        row("test.host.shared.accessor", SurfaceFactKind.PublicAccessor),
        row("test.host.shared.stub", SurfaceFactKind.Stub),
        row("test.host.shared.serializer", SurfaceFactKind.Serializer),
        row("test.host.shared.index", SurfaceFactKind.Index),
        row("test.host.shared.stub-navigation", SurfaceFactKind.Navigation)
      )
    )

  private def sharedDescendantSnapshot: ParserSyntaxSnapshot =
    val value  = snapshot("/shared", 1, Vector.empty)
    val range  = ParserNodePosition.Positioned(PcSourceRange(0, 1), 0, ParserPositionProvenance.SourceDerived)
    val root   = ParserSyntaxNode(
      1,
      "Root",
      Vector(
        ParserSyntaxField(
          "children",
          ParserFieldValue.Product(
            "Pair",
            Vector(
              ParserSyntaxField("left", ParserFieldValue.Node(2)),
              ParserSyntaxField("right", ParserFieldValue.Node(2))
            )
          )
        )
      ),
      range,
      Vector.empty
    )
    val parent = ParserSyntaxNode(
      2,
      "Parent",
      Vector(ParserSyntaxField("children", ParserFieldValue.Repeated(Vector(ParserFieldValue.Node(3))))),
      range,
      Vector(
        ParserNodeOccurrence(
          1,
          Vector(
            ParserFieldPathSegment.NamedField("children"),
            ParserFieldPathSegment.NestedProductBoundary("Pair"),
            ParserFieldPathSegment.NamedField("left")
          )
        ),
        ParserNodeOccurrence(
          1,
          Vector(
            ParserFieldPathSegment.NamedField("children"),
            ParserFieldPathSegment.NestedProductBoundary("Pair"),
            ParserFieldPathSegment.NamedField("right")
          )
        )
      )
    )
    val leaf   = ParserSyntaxNode(
      3,
      "Leaf",
      Vector.empty,
      range,
      Vector(
        ParserNodeOccurrence(
          2,
          Vector(ParserFieldPathSegment.NamedField("children"), ParserFieldPathSegment.RepeatedIndex(0))
        )
      )
    )
    value.copy(nodes = Vector(root, parent, leaf))

  private def positionedChildSnapshot: ParserSyntaxSnapshot =
    val value      = snapshot("/positioned", 1, Vector.empty)
    val range      = ParserNodePosition.Positioned(PcSourceRange(0, 1), 0, ParserPositionProvenance.SourceDerived)
    val root       = ParserSyntaxNode(
      1,
      "Root",
      Vector(
        ParserSyntaxField(
          "mods",
          ParserFieldValue.Repeated(
            Vector(
              ParserFieldValue.Positioned(0),
              ParserFieldValue.Positioned(0)
            )
          )
        )
      ),
      range,
      Vector.empty
    )
    val positioned = ParserPositionedSyntax(
      0,
      "Metadata",
      Vector(ParserSyntaxField("child", ParserFieldValue.Node(2))),
      range,
      Vector(
        ParserPositionedOccurrence(
          1,
          Vector(ParserFieldPathSegment.NamedField("mods"), ParserFieldPathSegment.RepeatedIndex(0))
        ),
        ParserPositionedOccurrence(
          1,
          Vector(ParserFieldPathSegment.NamedField("mods"), ParserFieldPathSegment.RepeatedIndex(1))
        )
      )
    )
    val leaf       = ParserSyntaxNode(
      2,
      "Leaf",
      Vector.empty,
      range,
      Vector(
        ParserNodeOccurrence(
          1,
          Vector(
            ParserFieldPathSegment.NamedField("mods"),
            ParserFieldPathSegment.RepeatedIndex(0),
            ParserFieldPathSegment.NamedField("child")
          )
        )
      )
    )
    value.copy(nodes = Vector(root, leaf), positioned = Vector(positioned))

  private def writeStructureEvidence(runId: String, wholeFilePlan: WholeFilePlanStructure): Unit =
    val output      = Path.of("target", "catalog-structure", runId)
    val persistence = Scala3PsiProductionCatalog.persistedSchemaStructure(
      Scala3PsiProductionCatalog.Reviewed,
      Scala3DotcFileElementType.SchemaVersion,
      Scala3DotcFileElementType.ExternalId
    )
    val catalog     = Scala3PsiProductionCatalog.catalogPlanStructure(Scala3PsiProductionCatalog.Reviewed)
    Files.createDirectories(output.getParent)
    Files.createDirectory(output)
    write(output.resolve("persisted-schema.tsv"), persistence.text)
    write(output.resolve("catalog-plan.tsv"), catalog.text)
    write(output.resolve("representative-whole-file-plan-modifier-annotation.tsv"), wholeFilePlan.text)
    write(
      output.resolve("fingerprints.txt"),
      Vector(
        s"source-revision\t${sys.env("METALLURGY_CATALOG_SOURCE_REVISION")}",
        s"source-tree\t${sys.env("METALLURGY_CATALOG_SOURCE_TREE")}",
        s"source-status\t${sys.env("METALLURGY_CATALOG_SOURCE_STATUS")}",
        s"jbr\t${sys.env("METALLURGY_CATALOG_JBR")}",
        s"persistence-schema\t${persistence.fingerprint}",
        s"catalog-plan\t${catalog.fingerprint}"
      ).mkString("\n") + "\n"
    )
    val files       = Vector(
      "catalog-plan.tsv",
      "fingerprints.txt",
      "persisted-schema.tsv",
      "representative-whole-file-plan-modifier-annotation.tsv"
    )
    write(
      output.resolve("SHA256SUMS"),
      files.map(name => s"${sha256(Files.readAllBytes(output.resolve(name)))}  $name").mkString("\n") + "\n"
    )

  private def write(path: Path, value: String): Unit =
    Files.writeString(path, value, StandardCharsets.UTF_8)
    ()

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(byte => f"${byte & 0xff}%02x").mkString
