package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

private[metallurgy] enum WholeFilePlanningFailure:
  case InventoryFailures(failures: Vector[InventoryFailure])
  case SourceEvidenceFailures(failures: Vector[SourceEvidenceFailure])
  case SourceAtomRefinementFailures(failures: Vector[SourceAtomRefinementFailure])
  case FinalSourceEvidenceFailures(failures: Vector[FinalSourceEvidenceFailure])
  case SourceEvidencePlanMismatch
  case EvidenceFingerprintMismatch(snapshot: String, evidence: String)
  case CatalogInventoryIdentityMismatch(runtime: CompilerRuntimeIdentity, catalog: CompilerRuntimeIdentity)
  case InvalidCatalog(errors: Vector[CatalogValidationError])
  case UnknownProduction(
      kind: InventoryKind,
      prefix: String,
      observedFields: Vector[String],
      ownerProduction: Option[String],
      fieldPath: Vector[ParserFieldPathSegment]
  )
  case AmbiguousProduction(
      kind: InventoryKind,
      prefix: String,
      productionIds: Vector[String],
      ownerProduction: Option[String],
      fieldPath: Vector[ParserFieldPathSegment]
  )
  case ContextDependentProduction(
      instance: ProductionInstanceId,
      selections: Vector[(Option[InventoryContext], Vector[String])]
  )
  case MissingRuntimeShape(kind: InventoryKind, id: Long)
  case ChildCardinalityMismatch(
      owner: ProductionInstanceId,
      roleId: String,
      expected: ChildCardinality,
      actual: Int
  )
  case ChildProductionMismatch(
      owner: ProductionInstanceId,
      roleId: String,
      expectedProductionId: String,
      actualProductionId: String,
      child: ProductionInstanceId
  )
  case MultiplyConsumedChildReference(child: ProductionInstanceId, owners: Vector[ProductionInstanceId])
  case UnsupportedPositionedChildren(owner: ProductionInstanceId)
  case UnsupportedFieldDisposition(owner: ProductionInstanceId, fieldName: String)
  case InvalidGroupedChildPosition(owner: ProductionInstanceId, roleId: String, child: ProductionInstanceId)
  case GroupedChildOutputRootCount(
      owner: ProductionInstanceId,
      roleId: String,
      child: ProductionInstanceId,
      actual: Int
  )
  case IncompatibleGroupedOutputRoots(owner: ProductionInstanceId, roleId: String, roots: Vector[CompositeInstanceId])
  case UnsupportedTerminalSelector(productionId: String, terminalId: String, selector: TerminalIntervalSelector)
  case TerminalCardinalityMismatch(
      owner: ProductionInstanceId,
      terminalId: String,
      expected: OccurrenceCardinality,
      actual: Int
  )
  case TerminalLexicalContractMismatch(
      owner: ProductionInstanceId,
      terminalId: String,
      target: TerminalLeafTarget,
      kinds: Vector[ClosedSourceLexicalKind]
  )
  case UnownedSourceAtom(atomId: SourceAtomId, start: Int, end: Int)
  case ConflictingSourceAtomOwners(
      atomId: SourceAtomId,
      start: Int,
      end: Int,
      owners: Vector[(ProductionInstanceId, String)]
  )
  case UnsupportedLayout(owner: ProductionInstanceId, alternatives: Vector[LayoutAlternative])
  case UnsupportedRecovery(owner: ProductionInstanceId, policy: RecoveryPolicy)
  case UnprobedNativeCandidate(
      owner: ProductionInstanceId,
      productionId: String,
      outputRoleId: PsiOutputRoleId
  )
  case UnassignedDiagnostic(index: Int)
  case OverlappingOutputForest(left: CompositeInstanceId, right: CompositeInstanceId)
  case OutputBoundaryResolutionFailed(
      owner: ProductionInstanceId,
      outputId: String,
      boundary: OutputBoundary,
      reason: String
  )
  case InvalidOutputRange(
      owner: ProductionInstanceId,
      outputId: String,
      start: Int,
      end: Int,
      productionRange: PcSourceRange
  )
  case InvalidCompilerEndMarker(owner: ProductionInstanceId, reason: String)
  case UnknownOutputRealization(owner: ProductionInstanceId, productionId: String)
  case AmbiguousOutputRealization(owner: ProductionInstanceId, productionId: String, realizationIds: Vector[String])
  case InvalidRealizationChoice(owner: ProductionInstanceId, failure: RealizationChoiceFailure)
  case InvalidProductionParticipation(failure: ProductionParticipationFailure)
  case OutputChildOutsideParent(parent: CompositeInstanceId, child: CompositeInstanceId)

private[metallurgy] final case class ProductionOccurrenceId(
    ownerNodeId: Long,
    fieldPath: Vector[ParserFieldPathSegment]
)
private[metallurgy] final case class ProductionInstanceId(
    kind: InventoryKind,
    valueId: Long,
    occurrence: Option[ProductionOccurrenceId]
)
private[metallurgy] final case class AtomicWholePlanCandidateRoot(
    owner: ProductionInstanceId,
    position: ParserNodePosition,
    parentCount: Int
)
private[metallurgy] object AtomicWholePlanCandidateScope:
  def validate(
      roots: Vector[AtomicWholePlanCandidateRoot],
      sourceLength: Int,
      diagnosticProvenanceCapability: ParserCapabilityStatus = ParserCapabilityStatus.Available,
      diagnostics: Vector[ParserDiagnostic] = Vector.empty
  ): Option[Vector[ProductionInstanceId]] =
    val distinctOwners                         = roots.map(_.owner).distinct.size == roots.size
    val positioned                             = roots.flatMap: root =>
      root.position match
        case ParserNodePosition.Positioned(range, point, ParserPositionProvenance.SourceDerived)
            if root.parentCount == 1 && range.startOffset >= 0 && range.startOffset < range.endOffset &&
              range.endOffset <= sourceLength && point >= range.startOffset && point <= range.endOffset =>
          Some((root.owner, range))
        case _ => None
    val ordered                                = positioned.sortBy: (owner, range) =>
      (
        range.startOffset,
        range.endOffset,
        owner.kind.ordinal,
        owner.valueId,
        owner.occurrence.fold(-1L)(_.ownerNodeId),
        owner.occurrence.fold("")(_.fieldPath.mkString("/"))
      )
    val disjoint                               = ordered
      .zip(ordered.drop(1))
      .forall: (left, right) =>
        left._2.endOffset <= right._2.startOffset
    val errorPositions                         = diagnostics.collect:
      case ParserDiagnostic(ParserDiagnosticSeverity.Error, _, position) => position
    val safeErrors                             = errorPositions.isEmpty ||
      diagnosticProvenanceCapability == ParserCapabilityStatus.Available && errorPositions.forall:
        case Some(
              ParserDiagnosticPosition(
                range,
                point,
                ParserDiagnosticPositionProvenance.SourceDerived
              )
            ) =>
          range.startOffset >= 0 && range.startOffset <= range.endOffset && range.endOffset <= sourceLength &&
          point >= range.startOffset && point <= range.endOffset
        case _ => false
    def isClean(range: PcSourceRange): Boolean =
      errorPositions.forall:
        case Some(
              ParserDiagnosticPosition(
                diagnosticRange,
                point,
                ParserDiagnosticPositionProvenance.SourceDerived
              )
            ) =>
          val rangedOverlap =
            diagnosticRange.startOffset < diagnosticRange.endOffset &&
              diagnosticRange.startOffset < range.endOffset && range.startOffset < diagnosticRange.endOffset
          val pointInside   =
            diagnosticRange.startOffset == diagnosticRange.endOffset &&
              range.startOffset <= point && point < range.endOffset
          !rangedOverlap && !pointInside
        case _ => false
    Option.when(distinctOwners && positioned.size == roots.size && disjoint && safeErrors)(
      ordered.collect { case (owner, range) if isClean(range) => owner }
    )

private[metallurgy] object AtomicWholePlanTrials:
  def select(
      candidates: Vector[ProductionInstanceId]
  )(proves: Set[ProductionInstanceId] => Boolean): Set[ProductionInstanceId] =
    val accepted = candidates.filter(root => proves(Set(root))).toSet
    if accepted.nonEmpty && proves(accepted) then accepted else Set.empty

private[metallurgy] object ProductionInstanceLineage:
  def child(
      parent: ProductionInstanceId,
      kind: InventoryKind,
      id: Long,
      path: Vector[ParserFieldPathSegment]
  ): ProductionInstanceId =
    val origin = parent.kind match
      case InventoryKind.Node                               => ProductionOccurrenceId(parent.valueId, Vector.empty)
      case InventoryKind.Positioned | InventoryKind.Product =>
        parent.occurrence.getOrElse(ProductionOccurrenceId(parent.valueId, Vector.empty))
    ProductionInstanceId(kind, id, Some(ProductionOccurrenceId(origin.ownerNodeId, origin.fieldPath ++ path)))

  def relativePath(
      parent: ProductionInstanceId,
      childOccurrence: ProductionOccurrenceId
  ): Vector[ParserFieldPathSegment] =
    val retainedPrefixLength = parent.kind match
      case InventoryKind.Node                               => 0
      case InventoryKind.Positioned | InventoryKind.Product => parent.occurrence.fold(0)(_.fieldPath.size)
    childOccurrence.fieldPath.drop(retainedPrefixLength)

private[metallurgy] final case class RuntimeParentEdge(
    parent: ProductionInstanceId,
    path: Vector[ParserFieldPathSegment]
)

private[metallurgy] object OwnedRootRouteMatcher:
  private val RetainedPayloadChildRoles = Set(
    PsiOutputRoleId.TypeArguments,
    PsiOutputRoleId.NamedTypeArguments
  )

  def isCompletePayload(realization: OutputRealization): Boolean =
    realization.template.composites.count(output =>
      output.parentId.isEmpty && output.range == OutputRangeDeclaration.CompilerPosition &&
        output.outputRoleId == PsiOutputRoleId.ExpressionPayload &&
        output.realization == OutputCompositeRealization.Once
    ) == 1 && realization.template.composites.count(_.parentId.isEmpty) == 1

  def isCompletePayload(production: Scala3PsiProduction): Boolean =
    production.effectiveOutputRealizations match
      case Vector(realization) => isCompletePayload(realization)
      case _                   => false

  def hasCompletePayloadFallback(production: Scala3PsiProduction): Boolean =
    isCompletePayload(production) || production.realizationChoice.exists(choice =>
      production.effectiveOutputRealizations.find(_.id == choice.fallbackId).exists(isCompletePayload)
    )

  def matches(
      candidate: ProductionInstanceId,
      route: OwnedRootRoute,
      parents: Map[ProductionInstanceId, Vector[RuntimeParentEdge]],
      selected: collection.Map[ProductionInstanceId, Scala3PsiProduction],
      prefix: ProductionInstanceId => String,
      position: ProductionInstanceId => ParserNodePosition,
      catalog: Scala3PsiProductionCatalog = Scala3PsiProductionCatalog.Reviewed,
      enabledAtomicRoots: Set[ProductionInstanceId] = Set.empty,
      candidateRoute: Boolean = false
  ): Boolean =
    def next(current: ProductionInstanceId, expected: InventoryAncestor): Option[ProductionInstanceId] =
      parents.get(current) match
        case Some(Vector(edge))
            if prefix(edge.parent) == expected.ownerPrefix && edge.parent.kind == expected.ownerKind &&
              InventoryContextLineage.normalized(edge.path) == expected.path =>
          Some(edge.parent)
        case _ => None

    def repeat(values: Vector[ProductionInstanceId], edge: InventoryAncestor): Option[Vector[ProductionInstanceId]] =
      next(values.last, edge) match
        case Some(parent) if values.contains(parent) => None
        case Some(parent)                            => repeat(values :+ parent, edge)
        case None                                    => Some(values)
    val fixedPath                                                                                                   = route.descendantPath.zipWithIndex.foldLeft(Option(Vector(candidate))):
      case (current, (expected, index)) =>
        current.flatMap: values =>
          val repeated = route.repeatedEdge match
            case Some(RepeatedOwnedRootEdge(`index`, edge)) => repeat(values, edge)
            case _                                          => Some(values)
          repeated.flatMap(path => next(path.last, expected).map(path :+ _))
    val traversed                                                                                                   = fixedPath.flatMap: values =>
      route.repeatedEdge match
        case Some(RepeatedOwnedRootEdge(index, edge)) if index == route.descendantPath.size => repeat(values, edge)
        case _                                                                              => Some(values)
    traversed.exists: values =>
      val root          = values.last
      val intermediates = values.slice(1, values.size - 1)
      val ownership     =
        selected
          .get(root)
          .exists: production =>
            val atomic      = production.realizationChoice.exists(_.policy == RealizationChoicePolicy.AtomicWholePlan)
            val atomicState = if atomic then enabledAtomicRoots(root) == candidateRoute else !candidateRoute
            val direct      = production.id == route.rootProductionId && atomicState &&
              (atomic || isCompletePayload(production))
            val alternative = catalog.productionAlternatives.exists(value =>
              value.candidateId == production.id && value.fallbackId == route.rootProductionId
            ) && production.realizationChoice.exists(choice =>
              production.effectiveOutputRealizations.find(_.id == choice.fallbackId).exists(isCompletePayload)
            )
            direct || (!candidateRoute && alternative)
        && (candidateRoute || intermediates.forall(instance =>
          selected
            .get(instance)
            .exists(_.effectiveOutputRealizations.forall: realization =>
              val roots = realization.template.composites.filter(_.parentId.isEmpty)
              roots.isEmpty || roots.forall(root => RetainedPayloadChildRoles(root.outputRoleId))
            )
        ))
      val bounded       = for
        definition <- next(root, route.rootOwner)
        _          <- next(definition, route.outerOwner)
      yield ()
      val contained     = (position(candidate), position(root)) match
        case (
              ParserNodePosition.Positioned(candidateRange, _, _),
              ParserNodePosition.Positioned(rootRange, _, _)
            ) =>
          rootRange.startOffset <= candidateRange.startOffset && candidateRange.endOffset <= rootRange.endOffset
        case (ParserNodePosition.Absent, ParserNodePosition.Positioned(_, _, _)) => true
        case _                                                                   => false
      ownership && bounded.nonEmpty && contained

private[metallurgy] final case class CompositeInstanceId(
    origin: ProductionInstanceId,
    localOutputId: String,
    ordinal: Int = 0
)
private[metallurgy] enum PhysicalLeafOwner:
  case Composite(instance: CompositeInstanceId)
  case FileRoot
private[metallurgy] final case class PlannedPhysicalLeaf(
    atomId: SourceAtomId,
    start: Int,
    end: Int,
    owner: PhysicalLeafOwner,
    sourceOwner: ProductionInstanceId,
    terminalId: String,
    target: TerminalLeafTarget
)
private[metallurgy] final case class PlannedChild(
    roleId: String,
    fieldPath: Vector[ParserFieldPathSegment],
    child: CompositeInstanceId
)
private[metallurgy] final case class PlannedComposite(
    instance: CompositeInstanceId,
    productionId: String,
    range: PcSourceRange,
    children: Vector[PlannedChild],
    fieldDispositions: Vector[FieldDisposition]
)
private[metallurgy] enum TargetAssertionOwner:
  case Composite(instance: CompositeInstanceId)
  case Terminal(instance: ProductionInstanceId, terminalId: String)
private[metallurgy] enum TargetAssertionKind:
  case NativeComposite, CompatibleComposite
  case Token
private[metallurgy] enum PlannedTargetIdentity:
  case OutputRole(outputRoleId: PsiOutputRoleId)
  case TokenRole(outputRoleId: PsiOutputRoleId, targetSurfaceId: String)
private[metallurgy] final case class PlannedTargetAssertion(
    owner: TargetAssertionOwner,
    targetIdentity: PlannedTargetIdentity,
    kind: TargetAssertionKind
)
private[metallurgy] final case class PlannedAccessorAssertion(
    owner: CompositeInstanceId,
    surfaceId: String,
    required: Boolean,
    surfaceKind: SurfaceFactKind = SurfaceFactKind.PublicAccessor
)
private[metallurgy] final case class PlannedStubAssertion(
    owner: CompositeInstanceId,
    stubSurfaceId: String,
    serializerSurfaceId: String,
    indexSurfaceIds: Vector[String],
    navigationSurfaceId: String
)
private[metallurgy] final case class PlannedNavigationAssertion(
    owner: CompositeInstanceId,
    obligation: NavigationObligation
)
private[metallurgy] final case class PlannedVirtualLayout(
    owner: ProductionInstanceId,
    anchor: Int,
    ordinalAtAnchor: Int
)
private[metallurgy] final case class PlannedStructuralEvidenceOwnership(
    eventId: SourceEvidenceEventId,
    owner: SourceEvidenceOwner
)
private[metallurgy] final case class PlannedChildClosureAbsorption(
    parent: ProductionInstanceId,
    realizationId: String,
    roleId: String,
    roots: Vector[ProductionInstanceId],
    closure: Vector[ProductionInstanceId],
    transferredClaim: PcSourceRange
)
private[metallurgy] final case class PlannedRealizationSelection(
    owner: ProductionInstanceId,
    realizationId: String,
    reason: RealizationSelectionReason
)
private[metallurgy] final case class WholeFileProductionPlan(
    sourceUri: ParserSourceUri,
    sourceDigest: String,
    parserEvidenceFingerprint: String,
    lexicalContract: ClosedSourceLexicalContract,
    physicalLeafOwnership: Vector[PlannedPhysicalLeaf],
    structuralEvidenceOwnership: Vector[PlannedStructuralEvidenceOwnership],
    virtualLayout: Vector[PlannedVirtualLayout],
    composites: Vector[PlannedComposite],
    targetAssertions: Vector[PlannedTargetAssertion],
    accessorAssertions: Vector[PlannedAccessorAssertion],
    stubAssertions: Vector[PlannedStubAssertion],
    navigationAssertions: Vector[PlannedNavigationAssertion],
    childClosureAbsorptions: Vector[PlannedChildClosureAbsorption] = Vector.empty,
    realizationSelections: Vector[PlannedRealizationSelection] = Vector.empty
)

private[metallurgy] final case class WholeFilePlanStructure(rows: Vector[String]):
  def text: String = StructuralRows.text(rows)

private[metallurgy] object WholeFileProductionPlanRenderer:
  def structure(plan: WholeFileProductionPlan): WholeFilePlanStructure =
    val rows = Vector.newBuilder[String]
    rows += StructuralRows.row(
      "source",
      plan.sourceUri.value,
      plan.sourceDigest,
      plan.parserEvidenceFingerprint
    )
    plan.lexicalContract.atoms.zipWithIndex.foreach((atom, index) =>
      rows += StructuralRows.row("lexical", index, atom.start, atom.end, atom.kind)
    )
    plan.composites.zipWithIndex.foreach: (composite, index) =>
      rows += StructuralRows.row(
        "composite",
        index,
        composite.instance,
        composite.instance.origin,
        composite.productionId,
        composite.range.startOffset,
        composite.range.endOffset,
        composite.fieldDispositions.mkString(",")
      )
      composite.children.zipWithIndex.foreach((child, childIndex) =>
        rows += StructuralRows.row(
          "composite-child",
          index,
          childIndex,
          composite.instance,
          child.roleId,
          child.fieldPath.mkString("/"),
          child.child
        )
      )
    plan.physicalLeafOwnership.zipWithIndex.foreach((leaf, index) =>
      rows += StructuralRows.row(
        "physical-leaf",
        index,
        leaf.atomId,
        leaf.start,
        leaf.end,
        leaf.owner,
        leaf.sourceOwner,
        leaf.sourceOwner.occurrence,
        leaf.terminalId,
        leaf.target
      )
    )
    plan.structuralEvidenceOwnership.zipWithIndex.foreach((ownership, index) =>
      rows += StructuralRows.row(
        "structural-event",
        index,
        ownership.eventId,
        ownership.owner.role.value,
        ownership.owner.identity
      )
    )
    plan.virtualLayout.zipWithIndex.foreach((layout, index) =>
      rows += StructuralRows.row("virtual-layout", index, layout.owner, layout.anchor, layout.ordinalAtAnchor)
    )
    plan.targetAssertions.zipWithIndex.foreach((assertion, index) =>
      rows += StructuralRows.row("target", index, assertion.owner, assertion.targetIdentity, assertion.kind)
    )
    plan.accessorAssertions.zipWithIndex.foreach((assertion, index) =>
      rows += StructuralRows.row(
        "accessor",
        index,
        assertion.owner,
        assertion.surfaceId,
        assertion.required,
        assertion.surfaceKind
      )
    )
    plan.stubAssertions.zipWithIndex.foreach((assertion, index) =>
      rows += StructuralRows.row(
        "persistence",
        index,
        assertion.owner,
        assertion.stubSurfaceId,
        assertion.serializerSurfaceId,
        assertion.indexSurfaceIds.mkString(","),
        assertion.navigationSurfaceId
      )
    )
    plan.navigationAssertions.zipWithIndex.foreach((assertion, index) =>
      rows += StructuralRows.row("navigation", index, assertion.owner, assertion.obligation)
    )
    plan.childClosureAbsorptions.zipWithIndex.foreach((absorption, index) =>
      rows += StructuralRows.row(
        "child-closure-absorption",
        index,
        absorption.parent,
        absorption.realizationId,
        absorption.roleId,
        absorption.roots.mkString(","),
        absorption.closure.mkString(","),
        absorption.transferredClaim.startOffset,
        absorption.transferredClaim.endOffset,
        "outputs,terminals,source-atoms,events,targets,accessors,stubs,indices,navigation"
      )
    )
    plan.realizationSelections.zipWithIndex.foreach((selection, index) =>
      rows += StructuralRows.row(
        "realization-selection",
        index,
        selection.owner,
        selection.realizationId,
        selection.reason
      )
    )
    WholeFilePlanStructure(rows.result())
