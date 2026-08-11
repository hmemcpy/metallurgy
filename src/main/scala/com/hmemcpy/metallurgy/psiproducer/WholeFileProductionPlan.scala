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
    navigationAssertions: Vector[PlannedNavigationAssertion]
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
    WholeFilePlanStructure(rows.result())
