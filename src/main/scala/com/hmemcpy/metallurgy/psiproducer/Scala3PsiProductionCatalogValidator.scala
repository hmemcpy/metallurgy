package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

private[metallurgy] enum CatalogValidationError:
  case DuplicateProductionId(id: String)
  case UnknownGrammarRole(productionId: String, grammarRoleId: GrammarRoleId)
  case CatalogAlternativeDerivedGrammarRole(productionId: String, grammarRoleId: GrammarRoleId)
  case CompilerDerivedGrammarRole(productionId: String, grammarRoleId: GrammarRoleId, compilerPrefix: String)
  case UnreferencedGrammarRole(grammarRoleId: GrammarRoleId)
  case EmptyOccurrencePatterns(productionId: String)
  case DuplicateOccurrencePattern(productionId: String, pattern: CompilerProductionContextPattern)
  case DuplicateRequiredAttachment(productionId: String, keyKind: String)
  case DuplicateChildRoleId(productionId: String, roleId: String)
  case UnknownChildProductionId(productionId: String, childProductionId: String)
  case EmptyOutputRealizations(productionId: String)
  case DuplicateOutputRealizationId(productionId: String, realizationId: String)
  case UnknownRealizationConditionRole(productionId: String, realizationId: String, roleId: String)
  case InvalidRealizationConditionOccurrence(
      productionId: String,
      realizationId: String,
      occurrence: ChildOccurrenceSelector
  )
  case DuplicateRealizationCondition(
      productionId: String,
      realizationId: String,
      roleId: String,
      occurrence: ChildOccurrenceSelector
  )
  case DuplicateRootAttachmentCondition(productionId: String, realizationId: String, keyKind: String)
  case DuplicateChildClosureAbsorption(productionId: String, realizationId: String, roleId: String)
  case UnknownChildClosureAbsorptionRole(productionId: String, realizationId: String, roleId: String)
  case InvalidChildRootOutcome(
      productionId: String,
      realizationId: String,
      roleId: String,
      outcome: ChildRootOutcome,
      reason: String
  )
  case ConflictingChildClosureParticipation(
      productionId: String,
      realizationId: String,
      roleId: String,
      reason: String
  )
  case InvalidAbsorbingRealization(productionId: String, realizationId: String, reason: String)
  case UnknownConditionProductionId(productionId: String, realizationId: String, childProductionId: String)
  case UnknownConditionRealizationId(productionId: String, realizationId: String, childRealizationId: String)
  case UnknownConditionOutputRole(productionId: String, realizationId: String, role: PsiOutputRoleId)
  case DuplicateTerminalId(productionId: String, terminalId: String)
  case DuplicateAccessorObligation(productionId: String, surfaceId: String)
  case DuplicateOutputId(productionId: String, outputId: String)
  case MissingDefaultOutputRole(productionId: String)
  case UnknownOutputRole(productionId: String, outputId: String, outputRoleId: PsiOutputRoleId)
  case HostDerivedOutputRole(
      productionId: String,
      outputId: String,
      outputRoleId: PsiOutputRoleId,
      targetSurfaceId: String
  )
  case UnreferencedOutputRole(outputRoleId: PsiOutputRoleId)
  case UnknownOutputParent(productionId: String, outputId: String, parentId: String)
  case CyclicOutputParent(productionId: String, outputId: String)
  case MissingChildMountRole(productionId: String, roleId: String)
  case ExtraChildMountRole(productionId: String, roleId: String)
  case UnknownChildMountParent(productionId: String, roleId: String, parentId: String)
  case UnsupportedOutputRange(productionId: String, outputId: String, range: OutputRangeDeclaration)
  case InvalidOutputBoundary(productionId: String, outputId: String, boundary: OutputBoundary, reason: String)
  case OverlappingCompilerPositionSiblings(
      productionId: String,
      parentId: Option[String],
      leftOutputId: String,
      rightOutputId: String
  )
  case InvalidChildCardinality(productionId: String, roleId: String)
  case InvalidTerminalCardinality(productionId: String, terminalId: String)
  case EmptyLayoutAlternatives(productionId: String)
  case DuplicateLayoutAlternative(productionId: String, alternative: LayoutAlternative)
  case EmptyRecoveryAlternatives(productionId: String)
  case AmbiguousRecoveryComposite(productionId: String, realizationId: String)
  case DuplicateSurfaceId(id: String)
  case UnclassifiedSurface(id: String)
  case UnresolvedSurface(id: String, status: FactStatus)
  case MissingFieldDisposition(productionId: String, fieldName: String)
  case DuplicateFieldDisposition(productionId: String, fieldName: String)
  case DispositionForUnknownField(productionId: String, fieldName: String)
  case UnknownChildField(productionId: String, fieldName: String)
  case MissingChildDeclaration(productionId: String, fieldName: String)
  case DuplicateChildDeclaration(productionId: String, fieldName: String)
  case ChildDeclarationForNonChildField(productionId: String, fieldName: String)
  case MissingTerminalDeclaration(productionId: String, fieldName: String)
  case UnknownTerminalField(productionId: String, fieldName: String)
  case UnknownTerminalChildRole(productionId: String, roleId: String)
  case UnknownTerminalOutput(productionId: String, terminalId: String, outputId: String)
  case UnknownOutputRangeChildRole(productionId: String, outputId: String, roleId: String)
  case InvalidSurface(
      productionId: String,
      outputRoleId: PsiOutputRoleId,
      surfaceId: String,
      expectedKind: SurfaceFactKind
  )
  case InvalidSurfaceOwner(
      productionId: String,
      outputRoleId: PsiOutputRoleId,
      surfaceId: String,
      expectedOwner: String
  )
  case IncompleteSurfaceStatus(
      productionId: String,
      outputRoleId: PsiOutputRoleId,
      surfaceId: String,
      status: FactStatus
  )
  case UnaccountedSyntaxSurface(surfaceId: String)
  case UnrepresentedCatalogProduction(productionId: String, grammarRoleId: GrammarRoleId)
  case UncoveredCompilerShape(
      kind: InventoryKind,
      prefix: String,
      context: Option[InventoryContext],
      sourceClassification: SourceClassification
  )
  case AmbiguousCompilerShape(
      kind: InventoryKind,
      prefix: String,
      context: Option[InventoryContext],
      sourceClassification: SourceClassification,
      productionIds: Vector[String]
  )
  case UnknownScenarioRealization(productionId: String, realizationIds: Vector[String])
  case AmbiguousScenarioRealization(productionId: String, realizationIds: Vector[String])
  case MissingScenarioOccurrenceOwner(instance: ProductionInstanceId, ownerNodeId: Long)
  case MissingScenarioOccurrenceContext(instance: ProductionInstanceId, occurrence: ProductionOccurrenceId)
  case InvalidOwnedRootRoute(productionId: String, route: OwnedRootRoute, reason: String)
  case InvalidEnabledCandidateRootRoute(productionId: String, route: OwnedRootRoute, reason: String)
  case InvalidAtomicWholePlanChoice(productionId: String, reason: String)

private[metallurgy] object RuntimeRealizationSelector:
  def validate(catalog: Scala3PsiProductionCatalog, runtime: CompilerRuntimeInventory): Vector[CatalogValidationError] =
    val rows                                                                   = runtime.shapes.map(row => (row.kind, row.id) -> row).toMap
    val nodes                                                                  = runtime.nodes.map(node => node.id -> node).toMap
    val ancestorEvidence                                                       = runtime.shapes.collect:
      case row if row.kind == InventoryKind.Node =>
        row.id -> InventoryAncestorEvidence(row.scannerTokenKinds, row.directNodeEvidence)
    val lineages                                                               = InventoryContextLineage.resolver(nodes, ancestorEvidence.toMap)
    val selected                                                               = collection.mutable.Map.empty[ProductionInstanceId, Scala3PsiProduction]
    val errors                                                                 = Vector.newBuilder[CatalogValidationError]
    val productsByOccurrence                                                   = runtime.products
      .flatMap(product =>
        product.occurrences.map(occurrence =>
          ProductionOccurrenceId(occurrence.ownerNodeId, occurrence.fieldPath) -> product
        )
      )
      .toMap
    def references(
        value: InventoryValueObservation,
        path: Vector[ParserFieldPathSegment],
        instance: ProductionInstanceId
    ): Vector[(InventoryKind, Long, Vector[ParserFieldPathSegment])] = value match
      case InventoryValueObservation.Node(id, _)             => Vector((InventoryKind.Node, id, path))
      case InventoryValueObservation.Positioned(id, _)       => Vector((InventoryKind.Positioned, id, path))
      case InventoryValueObservation.Optional(value)         =>
        value.toVector.flatMap(references(_, path :+ ParserFieldPathSegment.OptionalNesting, instance))
      case InventoryValueObservation.Repeated(values)        =>
        values.zipWithIndex.flatMap((candidate, index) =>
          references(candidate, path :+ ParserFieldPathSegment.RepeatedIndex(index), instance)
        )
      case InventoryValueObservation.Product(prefix, fields) =>
        if catalog.productions.exists(production =>
            production.pattern.kind == InventoryKind.Product && production.pattern.prefix == prefix
          )
        then
          val occurrence = ProductionInstanceLineage.child(instance, InventoryKind.Product, 0L, path).occurrence
          occurrence
            .flatMap(productsByOccurrence.get)
            .toVector
            .map(product => (InventoryKind.Product, product.id, path))
        else
          fields.flatMap(field =>
            references(
              field.value,
              path :+ ParserFieldPathSegment.NestedProductBoundary(prefix) :+
                ParserFieldPathSegment.NamedField(field.name),
              instance
            )
          )
      case _                                                 => Vector.empty
    def children(instance: ProductionInstanceId): Vector[ProductionInstanceId] =
      if instance.kind == InventoryKind.Positioned then Vector.empty
      else
        rows(instance.kind -> instance.valueId).observation.flatMap(field =>
          val path =
            if instance.kind == InventoryKind.Product then
              Vector(
                ParserFieldPathSegment.NestedProductBoundary(rows(instance.kind -> instance.valueId).prefix),
                ParserFieldPathSegment.NamedField(field.name)
              )
            else Vector(ParserFieldPathSegment.NamedField(field.name))
          references(field.value, path, instance).map: (kind, id, path) =>
            ProductionInstanceLineage.child(instance, kind, id, path)
        )
    val roots                                                                  = runtime.shapes
      .filter(row => row.kind == InventoryKind.Node && row.contexts.isEmpty)
      .map(row => ProductionInstanceId(row.kind, row.id, None))
    val pending                                                                = collection.mutable.Stack.from(roots.reverse)
    val discovered                                                             = collection.mutable.LinkedHashSet.empty[ProductionInstanceId]
    while pending.nonEmpty do
      val instance = pending.pop()
      if discovered.add(instance) then children(instance).reverseIterator.foreach(pending.push)
    val runtimeParents                                                         = discovered.toVector
      .flatMap(parent =>
        children(parent).flatMap(child =>
          child.occurrence.map(occurrence =>
            child -> RuntimeParentEdge(parent, ProductionInstanceLineage.relativePath(parent, occurrence))
          )
        )
      )
      .groupMap(_._1)(_._2)
    def position(instance: ProductionInstanceId): ParserNodePosition           = instance.kind match
      case InventoryKind.Node       => nodes(instance.valueId).position
      case InventoryKind.Product    =>
        runtime.products.find(_.id == instance.valueId).fold[ParserNodePosition](ParserNodePosition.Absent)(_.position)
      case InventoryKind.Positioned => ParserNodePosition.Absent
    discovered.foreach: instance =>
      val row      = rows(instance.kind -> instance.valueId)
      val contexts = instance.occurrence match
        case None             => Vector(None)
        case Some(occurrence) =>
          nodes.get(occurrence.ownerNodeId) match
            case None        =>
              errors += CatalogValidationError.MissingScenarioOccurrenceOwner(instance, occurrence.ownerNodeId)
              Vector.empty
            case Some(owner) =>
              val derived = lineages.contexts(owner, occurrence.fieldPath)
              if derived.isEmpty then
                errors += CatalogValidationError.MissingScenarioOccurrenceContext(instance, occurrence)
              derived.map(Some(_))
      val matches  = contexts
        .map(context =>
          CatalogShapeMatcher.select(
            catalog,
            row.kind,
            row.prefix,
            row.observation,
            context,
            row.sourceClassification,
            row.scannerTokenKinds,
            row.directNodeEvidence,
            row.rootAttachments,
            route =>
              OwnedRootRouteMatcher.matches(
                instance,
                route,
                runtimeParents,
                selected,
                candidate => rows(candidate.kind -> candidate.valueId).prefix,
                position,
                catalog
              )
          )
        )
        .map(_.map(_.id).sorted)
        .distinct
      matches match
        case Vector(ids) if ids.nonEmpty =>
          val productions = ids.flatMap(id => catalog.productions.find(_.id == id))
          ProductionMatchRetention.retain(catalog, productions) match
            case Right(retained) => selected += instance -> retained.candidate
            case Left(_)         =>
              errors += CatalogValidationError.AmbiguousCompilerShape(
                row.kind,
                row.prefix,
                contexts.headOption.flatten,
                row.sourceClassification,
                ids
              )
        case Vector(Vector()) | Vector() =>
          errors += CatalogValidationError.UncoveredCompilerShape(
            row.kind,
            row.prefix,
            contexts.headOption.flatten,
            row.sourceClassification
          )
        case Vector(ids)                 =>
          errors += CatalogValidationError.AmbiguousCompilerShape(
            row.kind,
            row.prefix,
            contexts.headOption.flatten,
            row.sourceClassification,
            ids.sorted
          )
        case values                      =>
          errors += CatalogValidationError.AmbiguousCompilerShape(
            row.kind,
            row.prefix,
            contexts.headOption.flatten,
            row.sourceClassification,
            values.flatten.distinct.sorted
          )

    val resolved                                                                      = collection.mutable.Map.empty[ProductionInstanceId, Vector[OutputRealization]]
    def mutuallyExclusive(left: OutputRealization, right: OutputRealization): Boolean =
      left.conditions.exists(leftCondition =>
        right.conditions.exists(rightCondition =>
          leftCondition.roleId == rightCondition.roleId &&
            leftCondition.occurrence == rightCondition.occurrence &&
            leftCondition.expected != rightCondition.expected
        )
      ) || left.evidenceConditions.exists:
        case EvidenceCondition.TemplateBodyLayout(leftPresent)                               =>
          right.evidenceConditions.contains(EvidenceCondition.TemplateBodyLayout(!leftPresent))
        case EvidenceCondition.RepeatedFieldOccurrence(fieldName, valuePattern, leftPresent) =>
          right.evidenceConditions.contains(
            EvidenceCondition.RepeatedFieldOccurrence(fieldName, valuePattern, !leftPresent)
          )
        case EvidenceCondition.RepeatedFieldSize(fieldName, leftMinimum, leftMaximum)        =>
          right.evidenceConditions.exists:
            case EvidenceCondition.RepeatedFieldSize(`fieldName`, rightMinimum, rightMaximum) =>
              leftMaximum.exists(_ < rightMinimum) || rightMaximum.exists(_ < leftMinimum)
            case _                                                                            => false
        case EvidenceCondition.RepeatedNodeFieldDistinct(_, _, _)                            => false
        case EvidenceCondition.RepeatedNodesTrailingPrefix(_, _)                             => false
        case EvidenceCondition.ProductionStartsWith(kind, leftPresent)                       =>
          right.evidenceConditions.contains(EvidenceCondition.ProductionStartsWith(kind, !leftPresent))
        case EvidenceCondition.TrailingProductionScannerToken(kind, leftPresent)             =>
          right.evidenceConditions.contains(EvidenceCondition.TrailingProductionScannerToken(kind, !leftPresent))
        case EvidenceCondition.RuntimeSupplementPositive(fieldName, leftPresent)             =>
          right.evidenceConditions.contains(EvidenceCondition.RuntimeSupplementPositive(fieldName, !leftPresent))
        case EvidenceCondition.LeadingBeforeRuntimeTailPresent(repeated, count, leftPresent) =>
          right.evidenceConditions.contains(
            EvidenceCondition.LeadingBeforeRuntimeTailPresent(repeated, count, !leftPresent)
          )
        case EvidenceCondition.RootAttachment(attachment, leftPresent)                       =>
          right.evidenceConditions.contains(EvidenceCondition.RootAttachment(attachment, !leftPresent))
        case EvidenceCondition.TrailingRepeatedNodeChild(_, _, _, _, _, _, _, _, _)          => false
    discovered.toVector.reverse.foreach: key =>
      selected
        .get(key)
        .foreach: production =>
          val childOutcomes = production.children.map: declaration =>
            val refs =
              children(key).filter: child =>
                child.occurrence.exists(occurrence =>
                  ProductionInstanceLineage
                    .relativePath(key, occurrence)
                    .headOption
                    .contains(
                      ParserFieldPathSegment.NamedField(declaration.fieldName)
                    )
                )
            declaration.roleId -> refs
          val matching      = production.effectiveOutputRealizations.filter: realization =>
            realization.conditions.forall: condition =>
              val values                                                                               = childOutcomes.find(_._1 == condition.roleId).toVector.flatMap(_._2)
              val child                                                                                = condition.occurrence match
                case ChildOccurrenceSelector.First        => values.headOption
                case ChildOccurrenceSelector.Last         => values.lastOption
                case ChildOccurrenceSelector.Exact(index) => values.lift(index)
              def matches(candidate: ProductionInstanceId, expected: ChildOutcomeExpectation): Boolean =
                expected.alternatives.exists:
                  case ChildOutcomeExpectation.Production(id)     => selected.get(candidate).exists(_.id == id)
                  case ChildOutcomeExpectation.Realization(id)    => resolved.get(candidate).exists(_.exists(_.id == id))
                  case ChildOutcomeExpectation.OutputRole(role)   =>
                    resolved
                      .get(candidate)
                      .exists(_.exists(_.template.composites.exists(_.outputRoleId == role)))
                  case ChildOutcomeExpectation.OutputRoles(roles) =>
                    resolved
                      .get(candidate)
                      .exists(_.exists(_.template.composites.exists(output => roles(output.outputRoleId))))
                  case ChildOutcomeExpectation.AnyOf(_)           => false
              child.exists(matches(_, condition.expected))
          val matches       = matching match
            case Vector() => Vector.empty
            case values   =>
              val mostSpecific = values.map(value => value.conditions.size + value.evidenceConditions.size).max
              values.filter(value => value.conditions.size + value.evidenceConditions.size == mostSpecific)
          matches match
            case many
                if production.realizationChoice
                  .exists(choice => many.map(_.id).toSet == choice.candidateIds.toSet + choice.fallbackId) =>
              resolved += key -> many
            case Vector() =>
              errors += CatalogValidationError.UnknownScenarioRealization(
                production.id,
                production.effectiveOutputRealizations.map(_.id).sorted
              )
            case many
                if many
                  .combinations(2)
                  .forall:
                    case Vector(left, right) => mutuallyExclusive(left, right)
                    case _                   => true
                =>
              resolved += key -> many
            case many     =>
              errors += CatalogValidationError.AmbiguousScenarioRealization(
                production.id,
                many.map(_.id).sorted
              )
    errors.result()

private[metallurgy] object Scala3PsiProductionCatalogValidator:
  def validate(
      catalog: Scala3PsiProductionCatalog,
      compiler: CompilerRuntimeInventory,
      surfaces: ScalaPsiSurfaceInventory
  ): Vector[CatalogValidationError] =
    validateCatalog(catalog, surfaces, runtimeCoverage(catalog, compiler), includeUnaccountedSurfaces = true)

  def validate(
      catalog: Scala3PsiProductionCatalog,
      compiler: AggregatedCompilerProductionInventory,
      surfaces: ScalaPsiSurfaceInventory
  ): Vector[CatalogValidationError] =
    validateCatalog(catalog, surfaces, aggregatedCoverage(catalog, compiler), includeUnaccountedSurfaces = true)

  def validateExecutable(
      catalog: Scala3PsiProductionCatalog,
      compiler: AggregatedCompilerProductionInventory,
      surfaces: ScalaPsiSurfaceInventory
  ): Vector[CatalogValidationError] =
    validateCatalog(catalog, surfaces, aggregatedCoverage(catalog, compiler), includeUnaccountedSurfaces = false)

  def validateExecutable(
      catalog: Scala3PsiProductionCatalog,
      compiler: CompilerRuntimeInventory,
      surfaces: ScalaPsiSurfaceInventory
  ): Vector[CatalogValidationError] =
    validateCatalog(catalog, surfaces, runtimeCoverage(catalog, compiler), includeUnaccountedSurfaces = false)

  private def validateCatalog(
      catalog: Scala3PsiProductionCatalog,
      surfaces: ScalaPsiSurfaceInventory,
      coverage: Vector[CatalogValidationError],
      includeUnaccountedSurfaces: Boolean
  ): Vector[CatalogValidationError] =
    val effectiveSurfaces      = surfaces.withCatalogCapabilities(catalog)
    val errors                 = Vector.newBuilder[CatalogValidationError]
    duplicates(catalog.productions.map(_.id)).foreach(id => errors += CatalogValidationError.DuplicateProductionId(id))
    val productionIds          = catalog.productions.map(_.id).toSet
    val productionsById        = catalog.productions.groupBy(_.id)
    val directRootOwners       = Set("DefDef", "ValDef").flatMap: owner =>
      Set("PackageDef" -> "stats", "Template" -> "preBody").map: (outer, field) =>
        InventoryAncestor(InventoryKind.Node, owner, Vector(CatalogPathSegment.NamedField("preRhs"))) ->
          InventoryAncestor(
            InventoryKind.Node,
            outer,
            Vector(CatalogPathSegment.NamedField(field), CatalogPathSegment.RepeatedElement)
          )
    val reviewedRootOwners     = directRootOwners + (
      InventoryAncestor(InventoryKind.Node, "ValDef", Vector(CatalogPathSegment.NamedField("preRhs"))) ->
        InventoryAncestor(
          InventoryKind.Node,
          "Block",
          Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement)
        )
    ) + (
      InventoryAncestor(InventoryKind.Node, "Typed", Vector(CatalogPathSegment.NamedField("expr")))    ->
        InventoryAncestor(
          InventoryKind.Node,
          "Apply",
          Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
        )
    )
    val compilerPrefixes       = catalog.productions.map(_.pattern.prefix).toSet ++ coverage.collect:
      case CatalogValidationError.UncoveredCompilerShape(_, prefix, _, _)    => prefix
      case CatalogValidationError.AmbiguousCompilerShape(_, prefix, _, _, _) => prefix
    catalog.productions.foreach: production =>
      production.realizationChoice
        .filter(_.policy == RealizationChoicePolicy.AtomicWholePlan)
        .foreach: choice =>
          val byId        = production.effectiveOutputRealizations.map(value => value.id -> value).toMap
          val fallback    = byId.get(choice.fallbackId)
          if choice.candidateIds.size != 1 then
            errors += CatalogValidationError.InvalidAtomicWholePlanChoice(
              production.id,
              "atomic choice requires exactly one candidate"
            )
          if !fallback.exists(OwnedRootRouteMatcher.isCompletePayload) then
            errors += CatalogValidationError.InvalidAtomicWholePlanChoice(
              production.id,
              "fallback must be one complete compiler-position payload"
            )
          choice.candidateIds.headOption.flatMap(byId.get) match
            case Some(candidate) if !OwnedRootRouteMatcher.isCompletePayload(candidate) => ()
            case _                                                                      =>
              errors += CatalogValidationError.InvalidAtomicWholePlanChoice(
                production.id,
                "candidate must be one existing richer realization"
              )
          val absorptions = fallback.toVector.flatMap(_.childClosureAbsorptions)
          if absorptions.map(_.roleId).toSet != production.children.map(_.roleId).toSet ||
            absorptions
              .exists(value => value.rootOutcome != ChildRootOutcome.AnyReviewed || value.retainedRootRoles.nonEmpty)
          then
            errors += CatalogValidationError.InvalidAtomicWholePlanChoice(
              production.id,
              "fallback must absorb every child closure without retained roots"
            )
      production.pattern.occurrences.foreach:
        case CompilerProductionContextPattern(ContextPattern.DescendantOfEnabledCandidateRoot(routes), _, _) =>
          duplicates(routes).foreach(route =>
            errors += CatalogValidationError.InvalidEnabledCandidateRootRoute(production.id, route, "duplicate route")
          )
          routes.foreach: route =>
            def invalid(reason: String): Unit =
              errors += CatalogValidationError.InvalidEnabledCandidateRootRoute(production.id, route, reason)
            if route.descendantPath.isEmpty then invalid("empty descendant path")
            else if (route.descendantPath :+ route.rootOwner :+ route.outerOwner).exists(_.path.isEmpty) then
              invalid("empty product-field edge")
            if route.repeatedEdge.nonEmpty then invalid("repeated candidate-root edge is unsupported")
            productionsById.get(route.rootProductionId) match
              case Some(Vector(root))
                  if root.realizationChoice
                    .exists(choice => choice.policy == RealizationChoicePolicy.AtomicWholePlan) =>
                if route.descendantPath.last.ownerKind != root.pattern.kind ||
                  route.descendantPath.last.ownerPrefix != root.pattern.prefix
                then invalid("final edge does not identify the candidate root shape")
              case Some(Vector(_)) => invalid("root is not an atomic whole-plan choice")
              case Some(_)         => invalid("ambiguous root production")
              case None            => invalid("missing root production")
        case CompilerProductionContextPattern(ContextPattern.DescendantOfOwnedRoot(routes), _, _)            =>
          if production.effectiveOutputRealizations.exists(_.template.composites.nonEmpty) then
            routes.foreach(route =>
              errors += CatalogValidationError.InvalidOwnedRootRoute(
                production.id,
                route,
                "descendant production emits output"
              )
            )
          duplicates(routes).foreach(route =>
            errors += CatalogValidationError.InvalidOwnedRootRoute(production.id, route, "duplicate route")
          )
          val structuralClaims = production.terminals.filter(_.claimsStructuralEvidence)
          val validClaim       = structuralClaims match
            case Vector(terminal) =>
              terminal.selector == TerminalIntervalSelector.WholeProduction &&
              terminal.target == TerminalLeafTarget.Parent &&
              Set(OccurrenceCardinality.ExactlyOne, OccurrenceCardinality.Optional)(terminal.cardinality)
            case _                => false
          if !validClaim then
            routes.foreach(route =>
              errors += CatalogValidationError.InvalidOwnedRootRoute(
                production.id,
                route,
                "descendant must have one whole-production parent claim"
              )
            )
          routes.foreach: route =>
            if route.descendantPath.isEmpty then
              errors += CatalogValidationError.InvalidOwnedRootRoute(production.id, route, "empty descendant path")
            else if (route.descendantPath :+ route.rootOwner :+ route.outerOwner).exists(_.path.isEmpty) then
              errors += CatalogValidationError.InvalidOwnedRootRoute(production.id, route, "empty product-field edge")
            route.repeatedEdge.foreach: repeated =>
              val reviewedEdge = InventoryAncestor(
                InventoryKind.Node,
                "Select",
                Vector(CatalogPathSegment.NamedField("qualifier"))
              )
              if repeated.insertionIndex <= 0 || repeated.insertionIndex > route.descendantPath.size ||
                repeated.edge != reviewedEdge ||
                route.descendantPath.lift(repeated.insertionIndex - 1).forall(_ != repeated.edge)
              then errors += CatalogValidationError.InvalidOwnedRootRoute(production.id, route, "invalid repeated edge")
            if !reviewedRootOwners(route.rootOwner -> route.outerOwner) then
              errors += CatalogValidationError.InvalidOwnedRootRoute(
                production.id,
                route,
                "unreviewed root owner boundary"
              )
            productionsById.get(route.rootProductionId) match
              case None                                                                          =>
                errors += CatalogValidationError.InvalidOwnedRootRoute(production.id, route, "missing root production")
              case Some(values) if values.size != 1                                              =>
                errors += CatalogValidationError.InvalidOwnedRootRoute(
                  production.id,
                  route,
                  "ambiguous root production"
                )
              case Some(Vector(root)) if !OwnedRootRouteMatcher.hasCompletePayloadFallback(root) =>
                errors += CatalogValidationError.InvalidOwnedRootRoute(
                  production.id,
                  route,
                  "root is not one complete payload"
                )
              case Some(Vector(root))
                  if route.descendantPath.last.ownerKind != root.pattern.kind ||
                    route.descendantPath.last.ownerPrefix != root.pattern.prefix =>
                errors += CatalogValidationError.InvalidOwnedRootRoute(
                  production.id,
                  route,
                  "final edge does not identify the root production shape"
                )
              case _                                                                             => ()
        case _                                                                                               => ()
    val catalogHostSurfaceIds  = catalog.productions
      .flatMap: production =>
        val terminals = production.terminals.collect:
          case TerminalDeclaration(_, _, TerminalLeafTarget.Token(surfaceId, _), _, _, _) => surfaceId
        val outputs   = production.effectiveOutputRealizations
          .flatMap(_.template.composites)
          .flatMap: output =>
            val persistence = output.persistence match
              case PersistenceObligations.NotApplicable                                   => Vector.empty
              case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
                Vector(stub, serializer, navigation) ++ indices
            Vector(output.targetSurfaceId) ++ output.accessors.map(_.surfaceId) ++ persistence
        outputs ++ terminals
      .toSet
    val hostIdentityIds        = catalogHostSurfaceIds ++ effectiveSurfaces.rows.map(_.id)
    duplicates(effectiveSurfaces.rows.map(_.id)).foreach(id => errors += CatalogValidationError.DuplicateSurfaceId(id))
    effectiveSurfaces.rows
      .filter(_.classification == SurfaceClassification.Unclassified)
      .foreach(r => errors += CatalogValidationError.UnclassifiedSurface(r.id))
    effectiveSurfaces.rows
      .filter(_.status != FactStatus.Available)
      .foreach: row =>
        errors += CatalogValidationError.UnresolvedSurface(row.id, row.status)
    val surfaceMap             = effectiveSurfaces.rows.groupBy(_.id).collect { case (id, Vector(row)) => id -> row }
    def requireSurface(
        p: Scala3PsiProduction,
        outputRoleId: PsiOutputRoleId,
        id: String,
        kind: SurfaceFactKind,
        owner: Option[String] = None
    ): Unit =
      surfaceMap.get(id) match
        case None                                                                 =>
          errors += CatalogValidationError.InvalidSurface(p.id, outputRoleId, id, kind)
        case Some(row) if row.kind != kind                                        =>
          errors += CatalogValidationError.InvalidSurface(p.id, outputRoleId, id, kind)
        case Some(row) if owner.exists(expected => row.ownerId != Some(expected)) =>
          errors += CatalogValidationError.InvalidSurfaceOwner(p.id, outputRoleId, id, owner.get)
        case Some(row) if row.status != FactStatus.Available                      =>
          errors += CatalogValidationError.IncompleteSurfaceStatus(p.id, outputRoleId, id, row.status)
        case _                                                                    => ()
    catalog.productions.foreach: p =>
      val names        = p.pattern.fields.map(_.name)
      val childRoles   = p.children.map(_.roleId).toSet
      val realizations = p.effectiveOutputRealizations
      p.grammarRoleIds.foreach: grammarRoleId =>
        if !catalog.stableRoles.grammarRoles(grammarRoleId) then
          errors += CatalogValidationError.UnknownGrammarRole(p.id, grammarRoleId)
        if productionIds(grammarRoleId.value) then
          errors += CatalogValidationError.CatalogAlternativeDerivedGrammarRole(p.id, grammarRoleId)
        if compilerPrefixes(grammarRoleId.value) then
          errors += CatalogValidationError.CompilerDerivedGrammarRole(p.id, grammarRoleId, grammarRoleId.value)
      if realizations.isEmpty then
        if p.outputTemplate.isEmpty && p.outputRealizations.isEmpty && p.outputRoleId.isEmpty then
          errors += CatalogValidationError.MissingDefaultOutputRole(p.id)
        else errors += CatalogValidationError.EmptyOutputRealizations(p.id)
      duplicates(realizations.map(_.id)).foreach(id =>
        errors += CatalogValidationError.DuplicateOutputRealizationId(p.id, id)
      )
      duplicates(p.pattern.requiredAttachments.map(_.keyKind)).foreach(keyKind =>
        errors += CatalogValidationError.DuplicateRequiredAttachment(p.id, keyKind)
      )
      realizations.foreach(realization =>
        realization.terminalIds.foreach: ids =>
          val declared = p.terminals.map(_.id).toSet
          ids.diff(declared).foreach(id => errors += CatalogValidationError.DuplicateTerminalId(p.id, id))
        duplicates(realization.conditions.map(condition => condition.roleId -> condition.occurrence)).foreach:
          case (roleId, occurrence) =>
            errors += CatalogValidationError.DuplicateRealizationCondition(
              p.id,
              realization.id,
              roleId,
              occurrence
            )
        duplicates(realization.evidenceConditions.collect { case EvidenceCondition.RootAttachment(a, _) => a.keyKind })
          .foreach(key => errors += CatalogValidationError.DuplicateRootAttachmentCondition(p.id, realization.id, key))
        realization.conditions.foreach: condition =>
          if !childRoles(condition.roleId) then
            errors += CatalogValidationError.UnknownRealizationConditionRole(p.id, realization.id, condition.roleId)
          condition.occurrence match
            case value @ ChildOccurrenceSelector.Exact(index) if index < 0 =>
              errors += CatalogValidationError.InvalidRealizationConditionOccurrence(p.id, realization.id, value)
            case _                                                         => ()
          p.children
            .find(_.roleId == condition.roleId)
            .foreach(child =>
              errors ++= ChildOutcomeExpectationValidator.conditionErrors(
                catalog,
                p,
                realization,
                child,
                condition.expected
              )
            )
        duplicates(realization.childClosureAbsorptions.map(_.roleId)).foreach(roleId =>
          errors += CatalogValidationError.DuplicateChildClosureAbsorption(p.id, realization.id, roleId)
        )
        realization.childClosureAbsorptions.foreach: absorption =>
          p.children.find(_.roleId == absorption.roleId) match
            case None        =>
              errors += CatalogValidationError.UnknownChildClosureAbsorptionRole(
                p.id,
                realization.id,
                absorption.roleId
              )
            case Some(child) =>
              val validCardinality = absorption.rootOutcome match
                case ChildRootOutcome.One(_)      => child.cardinality == ChildCardinality.ExactlyOne
                case ChildRootOutcome.All(_)      => child.cardinality.isInstanceOf[ChildCardinality.Repeated]
                case ChildRootOutcome.AnyReviewed => true
              if !validCardinality then
                errors += CatalogValidationError.InvalidChildRootOutcome(
                  p.id,
                  realization.id,
                  absorption.roleId,
                  absorption.rootOutcome,
                  "outcome does not match child cardinality"
                )
              val expected         = absorption.rootOutcome match
                case ChildRootOutcome.One(value)  => Some(value)
                case ChildRootOutcome.All(value)  => Some(value)
                case ChildRootOutcome.AnyReviewed => None
              expected.foreach(value =>
                errors ++= ChildOutcomeExpectationValidator.rootErrors(
                  catalog,
                  p,
                  realization.id,
                  absorption.roleId,
                  absorption.rootOutcome,
                  child,
                  value
                )
              )
          if realization.template.childMounts.get(absorption.roleId).flatten.nonEmpty &&
            absorption.retainedRootRoles.isEmpty
          then
            errors += CatalogValidationError.ConflictingChildClosureParticipation(
              p.id,
              realization.id,
              absorption.roleId,
              "absorbed child output is mounted without a retained root role"
            )
          absorption.retainedRootRoles.foreach: role =>
            val knownRoot = p.children
              .filter(_.roleId == absorption.roleId)
              .flatMap(_.productionIds)
              .flatMap(id => catalog.productions.find(_.id == id))
              .exists(
                _.effectiveOutputRealizations.exists(
                  _.template.composites.exists(output => output.parentId.isEmpty && output.outputRoleId == role)
                )
              )
            if !knownRoot then
              errors += CatalogValidationError.InvalidChildRootOutcome(
                p.id,
                realization.id,
                absorption.roleId,
                absorption.rootOutcome,
                s"unknown retained child root output role ${role.value}"
              )
          if realization.template.childOutputSelections.contains(absorption.roleId) then
            errors += CatalogValidationError.ConflictingChildClosureParticipation(
              p.id,
              realization.id,
              absorption.roleId,
              "absorbed child output is selected"
            )
          val outputSelectors = p.terminals.exists(_.selector match
            case TerminalIntervalSelector.BeforeChildOutputs(role)                                => role == absorption.roleId
            case TerminalIntervalSelector.ChildOutputGap(left, right)                             =>
              left == absorption.roleId || right == absorption.roleId
            case TerminalIntervalSelector.ChildOutputSeparators(role)                             => role == absorption.roleId
            case TerminalIntervalSelector.CompilerScannerTokenBeforeChildOutputs(_, role)         =>
              role == absorption.roleId
            case TerminalIntervalSelector.CompilerScannerTokenInChildOutputGap(_, left, right, _) =>
              left == absorption.roleId || right == absorption.roleId
            case _                                                                                => false
          )
          if outputSelectors then
            errors += CatalogValidationError.ConflictingChildClosureParticipation(
              p.id,
              realization.id,
              absorption.roleId,
              "terminal selector requires absorbed child output"
            )
        errors ++= ChildOutcomeExpectationValidator.requiredRootsErrors(
          catalog,
          p,
          realization.id,
          realization.requiredChildRoots
        )
        if realization.childClosureAbsorptions.nonEmpty then
          val roots        = realization.template.composites.filter(_.parentId.isEmpty)
          if roots.size != 1 || roots.head.realization != OutputCompositeRealization.Once then
            errors += CatalogValidationError.InvalidAbsorbingRealization(
              p.id,
              realization.id,
              "absorbing realization must have one local root"
            )
          val parentClaims = p.terminals.filter: terminal =>
            terminal.selector == TerminalIntervalSelector.WholeProduction &&
              terminal.target == TerminalLeafTarget.Parent && terminal.claimsStructuralEvidence
          if parentClaims.size != 1 then
            errors += CatalogValidationError.InvalidAbsorbingRealization(
              p.id,
              realization.id,
              "absorbing production must have one whole-production parent claim"
            )
      )
      p.realizationChoice.foreach: choice =>
        val realizationId = choice.candidateIds.headOption.getOrElse(choice.fallbackId)
        errors ++= ChildOutcomeExpectationValidator.requiredRootsErrors(
          catalog,
          p,
          realizationId,
          choice.trialEligibility
        )
      realizations.foreach { realization =>
        val template                                             = realization.template; val outputIds = template.composites.map(_.id)
        duplicates(outputIds).foreach(id => errors += CatalogValidationError.DuplicateOutputId(p.id, id))
        p.terminals.foreach:
          case terminal @ TerminalDeclaration(_, selector, _, _, _, _) =>
            selector match
              case TerminalIntervalSelector.LocalOutput(outputId) if !outputIds.contains(outputId)            =>
                errors += CatalogValidationError.UnknownTerminalOutput(p.id, terminal.id, outputId)
              case TerminalIntervalSelector.RootOutsideLocalOutput(outputId) if !outputIds.contains(outputId) =>
                errors += CatalogValidationError.UnknownTerminalOutput(p.id, terminal.id, outputId)
              case _                                                                                          => ()
        template.composites.foreach: output =>
          if !catalog.stableRoles.outputRoles(output.outputRoleId) then
            errors += CatalogValidationError.UnknownOutputRole(p.id, output.id, output.outputRoleId)
          if hostIdentityIds(output.outputRoleId.value) then
            errors += CatalogValidationError.HostDerivedOutputRole(
              p.id,
              output.id,
              output.outputRoleId,
              output.outputRoleId.value
            )
          output.parentId
            .filterNot(outputIds.contains)
            .foreach(parent => errors += CatalogValidationError.UnknownOutputParent(p.id, output.id, parent))
          def validateBoundary(boundary: OutputBoundary): Unit = boundary match
            case OutputBoundary.ChildStart(role, selector, _)                         =>
              if !childRoles(role) then
                errors += CatalogValidationError.InvalidOutputBoundary(p.id, output.id, boundary, "unknown child role")
              selector match
                case ChildOccurrenceSelector.Exact(index) if index < 0 =>
                  errors += CatalogValidationError.InvalidOutputBoundary(
                    p.id,
                    output.id,
                    boundary,
                    "negative occurrence ordinal"
                  )
                case _                                                 => ()
            case OutputBoundary.ChildEnd(role, selector, _)                           =>
              if !childRoles(role) then
                errors += CatalogValidationError.InvalidOutputBoundary(p.id, output.id, boundary, "unknown child role")
              selector match
                case ChildOccurrenceSelector.Exact(index) if index < 0 =>
                  errors += CatalogValidationError.InvalidOutputBoundary(
                    p.id,
                    output.id,
                    boundary,
                    "negative occurrence ordinal"
                  )
                case _                                                 => ()
            case OutputBoundary.EvidenceBoundaryAfterChild(
                  role,
                  selector,
                  followingRole,
                  followingSelector,
                  expectedDelimiters,
                  _,
                  _
                ) =>
              if !childRoles(role) || !childRoles(followingRole) then
                errors += CatalogValidationError.InvalidOutputBoundary(p.id, output.id, boundary, "unknown child role")
              Vector(selector, followingSelector).foreach:
                case ChildOccurrenceSelector.Exact(index) if index < 0 =>
                  errors += CatalogValidationError.InvalidOutputBoundary(
                    p.id,
                    output.id,
                    boundary,
                    "negative occurrence ordinal"
                  )
                case _                                                 => ()
              if expectedDelimiters.isEmpty || expectedDelimiters.exists(_.isEmpty) then
                errors += CatalogValidationError.InvalidOutputBoundary(
                  p.id,
                  output.id,
                  boundary,
                  "expected delimiters must be nonempty"
                )
            case OutputBoundary.NextScannerTokenStartAfterChild(role, selector, _, _) =>
              if !childRoles(role) then
                errors += CatalogValidationError.InvalidOutputBoundary(p.id, output.id, boundary, "unknown child role")
              selector match
                case ChildOccurrenceSelector.Exact(index) if index < 0 =>
                  errors += CatalogValidationError.InvalidOutputBoundary(
                    p.id,
                    output.id,
                    boundary,
                    "negative occurrence ordinal"
                  )
                case _                                                 => ()
            case OutputBoundary.Advance(_, count) if count < 0                        =>
              errors += CatalogValidationError.InvalidOutputBoundary(
                p.id,
                output.id,
                boundary,
                "negative boundary advance"
              )
            case OutputBoundary.Advance(base, _)                                      => validateBoundary(base)
            case _                                                                    => ()
          output.range match
            case OutputRangeDeclaration.CompilerPosition | OutputRangeDeclaration.CompilerPositionWithPolicy(_) |
                OutputRangeDeclaration.CompilerPositionWithTrailingBalancedBrackets(_) |
                OutputRangeDeclaration.CompilerEndMarker =>
              ()
            case OutputRangeDeclaration.CompilerPositionWithBodyLayoutOrEndMarker(
                  headerRole,
                  bodyRole,
                  _,
                  _,
                  _
                ) =>
              (Vector(headerRole) ++ bodyRole)
                .filterNot(childRoles)
                .foreach(role => errors += CatalogValidationError.UnknownOutputRangeChildRole(p.id, output.id, role))
            case OutputRangeDeclaration.BalancedLexicalRangeBeforeChildOutput(role, _, _)       =>
              if !childRoles(role) then
                errors += CatalogValidationError.UnknownOutputRangeChildRole(p.id, output.id, role)
            case OutputRangeDeclaration.BoundaryDerived(start, end)                             =>
              validateBoundary(start); validateBoundary(end)
            case OutputRangeDeclaration.BoundaryDerivedWithTrailingBalancedBrackets(start, end) =>
              validateBoundary(start); validateBoundary(end)
        template.composites
          .filter(_.range == OutputRangeDeclaration.CompilerPosition)
          .groupBy(_.parentId)
          .values
          .foreach: siblings =>
            siblings
              .map(_.id)
              .sorted
              .sliding(2)
              .foreach:
                case Vector(left, right) =>
                  errors += CatalogValidationError.OverlappingCompilerPositionSiblings(
                    p.id,
                    siblings.head.parentId,
                    left,
                    right
                  )
                case _                   => ()
        def cyclicOutput(id: String, seen: Set[String]): Boolean =
          if seen(id) then true
          else template.composites.find(_.id == id).flatMap(_.parentId).exists(cyclicOutput(_, seen + id))
        outputIds.distinct
          .filter(cyclicOutput(_, Set.empty))
          .foreach(id => errors += CatalogValidationError.CyclicOutputParent(p.id, id))
        childRoles
          .diff(template.childMounts.keySet)
          .foreach(role => errors += CatalogValidationError.MissingChildMountRole(p.id, role))
        template.childMounts.keySet
          .diff(childRoles)
          .foreach(role => errors += CatalogValidationError.ExtraChildMountRole(p.id, role))
        template.childMounts.foreach: (role, parent) =>
          parent
            .filterNot(outputIds.contains)
            .foreach(id => errors += CatalogValidationError.UnknownChildMountParent(p.id, role, id))
      }
      if p.pattern.occurrences.isEmpty then errors += CatalogValidationError.EmptyOccurrencePatterns(p.id)
      duplicates(p.pattern.occurrences)
        .foreach(pattern => errors += CatalogValidationError.DuplicateOccurrencePattern(p.id, pattern))
      duplicates(p.children.map(_.roleId))
        .foreach(role => errors += CatalogValidationError.DuplicateChildRoleId(p.id, role))
      p.children
        .flatMap(_.productionIds)
        .filterNot(productionIds)
        .foreach(id => errors += CatalogValidationError.UnknownChildProductionId(p.id, id))
      p.children
        .filter(child => !valid(child.cardinality))
        .foreach(child => errors += CatalogValidationError.InvalidChildCardinality(p.id, child.roleId))
      duplicates(p.terminals.map(_.id))
        .foreach(id => errors += CatalogValidationError.DuplicateTerminalId(p.id, id))
      p.terminals
        .filter(terminal => !valid(terminal.cardinality))
        .foreach(terminal => errors += CatalogValidationError.InvalidTerminalCardinality(p.id, terminal.id))
      p.terminals.foreach: terminal =>
        if !catalog.stableRoles.outputRoles(terminal.outputRoleId) then
          errors += CatalogValidationError.UnknownOutputRole(p.id, terminal.id, terminal.outputRoleId)
        if hostIdentityIds(terminal.outputRoleId.value) then
          errors += CatalogValidationError.HostDerivedOutputRole(
            p.id,
            terminal.id,
            terminal.outputRoleId,
            terminal.outputRoleId.value
          )
      realizations
        .flatMap(_.template.composites)
        .foreach: output =>
          duplicates(output.accessors.map(_.surfaceId))
            .foreach(id => errors += CatalogValidationError.DuplicateAccessorObligation(p.id, id))
      if p.layouts.isEmpty then errors += CatalogValidationError.EmptyLayoutAlternatives(p.id)
      duplicates(p.layouts)
        .foreach(layout => errors += CatalogValidationError.DuplicateLayoutAlternative(p.id, layout))
      p.recovery match
        case RecoveryPolicy.DiagnosticBound(_, alternatives) if alternatives.isEmpty =>
          errors += CatalogValidationError.EmptyRecoveryAlternatives(p.id)
        case _                                                                       => ()
      if p.recovery != RecoveryPolicy.Reject then
        p.effectiveOutputRealizations.foreach: realization =>
          val roots = realization.template.composites.filter(_.parentId.isEmpty)
          if roots.size != 1 || roots.head.realization != OutputCompositeRealization.Once then
            errors += CatalogValidationError.AmbiguousRecoveryComposite(p.id, realization.id)
      duplicates(p.dispositions.map(_.fieldName))
        .foreach(n => errors += CatalogValidationError.DuplicateFieldDisposition(p.id, n))
      names
        .filterNot(n => p.dispositions.exists(_.fieldName == n))
        .foreach(n => errors += CatalogValidationError.MissingFieldDisposition(p.id, n))
      p.dispositions
        .filterNot(d => names.contains(d.fieldName))
        .foreach(d => errors += CatalogValidationError.DispositionForUnknownField(p.id, d.fieldName))
      p.children
        .filterNot(c => names.contains(c.fieldName))
        .foreach(c => errors += CatalogValidationError.UnknownChildField(p.id, c.fieldName))
      names.foreach: name =>
        val disposition = p.dispositions.filter(_.fieldName == name)
        val children    = p.children.count(_.fieldName == name)
        if disposition.size == 1 && disposition.head.kind == FieldDispositionKind.Child then
          if children == 0 then errors += CatalogValidationError.MissingChildDeclaration(p.id, name)
          else if children > 1 && p.children.filter(_.fieldName == name).exists(_.slice == ChildSlice.All) then
            errors += CatalogValidationError.DuplicateChildDeclaration(p.id, name)
        else if children > 0 then errors += CatalogValidationError.ChildDeclarationForNonChildField(p.id, name)
        if disposition.size == 1 && disposition.head.kind == FieldDispositionKind.TerminalOrLayout then
          val declared = p.terminals.exists(_.selector match
            case TerminalIntervalSelector.WholeProduction | TerminalIntervalSelector.WholeSource =>
              true
            case TerminalIntervalSelector.FieldBounds(a, b)                                      => a == name || b == name
            case _: TerminalIntervalSelector.ChildGap                                            => false
            case _: TerminalIntervalSelector.ChildSeparators                                     => false
            case _: TerminalIntervalSelector.BeforeChild                                         => true
            case _: TerminalIntervalSelector.BeforeChildOutputs                                  => true
            case _: TerminalIntervalSelector.AfterChild                                          => false
            case gap: TerminalIntervalSelector.SourceDerivedChildToScannerTokenGap               =>
              gap.field == name
            case _: TerminalIntervalSelector.ChildOutputGap                                      => false
            case _: TerminalIntervalSelector.ChildOutputSeparators                               => false
            case TerminalIntervalSelector.CompilerEndMarkerKeyword |
                TerminalIntervalSelector.CompilerScannerToken(_, _) |
                TerminalIntervalSelector.CompilerScannerTokenBeforeChildOutputs(_, _) |
                TerminalIntervalSelector.CompilerScannerTokenInChildGap(_, _, _) |
                TerminalIntervalSelector.CompilerScannerTokenInChildOutputGap(_, _, _, _) |
                TerminalIntervalSelector.BalancedScannerTokenAfterChild(_, _, _, _, _) |
                TerminalIntervalSelector.BalancedKeywordBeforeFirstChild(_, _, _, _) |
                TerminalIntervalSelector.BalancedPrefixBeforeFirstChild(_, _, _) |
                TerminalIntervalSelector.BalancedSuffixAfterLastChild(_, _, _, _) |
                TerminalIntervalSelector.LocalOutput(_) | TerminalIntervalSelector.RootOutsideLocalOutput(_) =>
              false
          )
          if !declared then errors += CatalogValidationError.MissingTerminalDeclaration(p.id, name)
      p.terminals.foreach(_.selector match
        case TerminalIntervalSelector.FieldBounds(a, b)                                =>
          Vector(a, b)
            .filterNot(names.contains)
            .foreach(n => errors += CatalogValidationError.UnknownTerminalField(p.id, n))
        case TerminalIntervalSelector.ChildGap(a, b)                                   =>
          Vector(a, b)
            .filterNot(childRoles)
            .foreach(role => errors += CatalogValidationError.UnknownTerminalChildRole(p.id, role))
        case TerminalIntervalSelector.ChildSeparators(role)                            =>
          if !childRoles(role) then errors += CatalogValidationError.UnknownTerminalChildRole(p.id, role)
        case TerminalIntervalSelector.BeforeChild(role)                                =>
          if !childRoles(role) then errors += CatalogValidationError.UnknownTerminalChildRole(p.id, role)
        case TerminalIntervalSelector.AfterChild(role)                                 =>
          if !childRoles(role) then errors += CatalogValidationError.UnknownTerminalChildRole(p.id, role)
        case TerminalIntervalSelector.BeforeChildOutputs(role)                         =>
          if !childRoles(role) then errors += CatalogValidationError.UnknownTerminalChildRole(p.id, role)
        case TerminalIntervalSelector.ChildOutputGap(a, b)                             =>
          Vector(a, b)
            .filterNot(childRoles)
            .foreach(role => errors += CatalogValidationError.UnknownTerminalChildRole(p.id, role))
        case TerminalIntervalSelector.ChildOutputSeparators(role)                      =>
          if !childRoles(role) then errors += CatalogValidationError.UnknownTerminalChildRole(p.id, role)
        case TerminalIntervalSelector.CompilerScannerTokenBeforeChildOutputs(_, role)  =>
          if !childRoles(role) then errors += CatalogValidationError.UnknownTerminalChildRole(p.id, role)
        case TerminalIntervalSelector.CompilerScannerTokenInChildGap(_, a, b)          =>
          Vector(a, b)
            .filterNot(childRoles)
            .foreach(role => errors += CatalogValidationError.UnknownTerminalChildRole(p.id, role))
        case TerminalIntervalSelector.CompilerScannerTokenInChildOutputGap(_, a, b, _) =>
          Vector(a, b)
            .filterNot(childRoles)
            .foreach(role => errors += CatalogValidationError.UnknownTerminalChildRole(p.id, role))
        case TerminalIntervalSelector.BalancedKeywordBeforeFirstChild(_, _, a, b)      =>
          Vector(a, b)
            .filterNot(childRoles)
            .foreach(role => errors += CatalogValidationError.UnknownTerminalChildRole(p.id, role))
        case TerminalIntervalSelector.BalancedPrefixBeforeFirstChild(_, a, b)          =>
          Vector(a, b)
            .filterNot(childRoles)
            .foreach(role => errors += CatalogValidationError.UnknownTerminalChildRole(p.id, role))
        case TerminalIntervalSelector.BalancedSuffixAfterLastChild(_, _, a, b)         =>
          Vector(a, b)
            .filterNot(childRoles)
            .foreach(role => errors += CatalogValidationError.UnknownTerminalChildRole(p.id, role))
        case _                                                                         => ()
      )
      p.terminals.foreach:
        case TerminalDeclaration(_, _, TerminalLeafTarget.Token(id, _), _, outputRoleId, _) =>
          requireSurface(p, outputRoleId, id, SurfaceFactKind.Token)
        case _                                                                              => ()
      realizations
        .flatMap(_.template.composites)
        .foreach: output =>
          requireSurface(p, output.outputRoleId, output.targetSurfaceId, SurfaceFactKind.Element)
          output.accessors.foreach(a => requireSurface(p, output.outputRoleId, a.surfaceId, a.surfaceKind))
          output.persistence match
            case PersistenceObligations.NotApplicable                                   => ()
            case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
              requireSurface(p, output.outputRoleId, stub, SurfaceFactKind.Stub)
              requireSurface(p, output.outputRoleId, serializer, SurfaceFactKind.Serializer)
              indices.foreach(requireSurface(p, output.outputRoleId, _, SurfaceFactKind.Index))
              requireSurface(p, output.outputRoleId, navigation, SurfaceFactKind.Navigation)
    errors ++= coverage
    val referencedGrammarRoles = catalog.productions.flatMap(_.grammarRoleIds).toSet
    val referencedOutputRoles  = catalog.productions
      .flatMap(production =>
        production.terminals.map(_.outputRoleId) ++
          production.effectiveOutputRealizations.flatMap(_.template.composites.map(_.outputRoleId))
      )
      .toSet
    val accounted              = catalogHostSurfaceIds
    if includeUnaccountedSurfaces then
      catalog.stableRoles.grammarRoles
        .diff(referencedGrammarRoles)
        .foreach(role => errors += CatalogValidationError.UnreferencedGrammarRole(role))
      catalog.stableRoles.outputRoles
        .diff(referencedOutputRoles)
        .foreach(role => errors += CatalogValidationError.UnreferencedOutputRole(role))
      effectiveSurfaces.rows
        .filter(r =>
          r.status == FactStatus.Available && r.classification == SurfaceClassification.SyntaxContract && !accounted(
            r.id
          )
        )
        .foreach(r => errors += CatalogValidationError.UnaccountedSyntaxSurface(r.id))
    errors.result().distinct.sortBy(_.toString)

  private def runtimeCoverage(
      catalog: Scala3PsiProductionCatalog,
      compiler: CompilerRuntimeInventory
  ): Vector[CatalogValidationError] =
    RuntimeRealizationSelector.validate(catalog, compiler)

  private def aggregatedCoverage(
      catalog: Scala3PsiProductionCatalog,
      compiler: AggregatedCompilerProductionInventory
  ): Vector[CatalogValidationError] =
    val catalogProducts = catalog.productions.collect:
      case production if production.pattern.kind == InventoryKind.Product => production.pattern.prefix
    val uncovered       = compiler.productions
      .filter(row => row.kind != InventoryKind.Product || catalogProducts.contains(row.prefix))
      .flatMap: row =>
        row.occurrences.flatMap: occurrence =>
          coverageError(
            catalog,
            row.kind,
            row.prefix,
            occurrence.context,
            occurrence.sourceClassification,
            CatalogShapeMatcher.selectAggregated(catalog, row, occurrence)
          )
    val unrepresented   = catalog.productions.collect:
      case production
          if production.pattern.occurrences.exists(pattern =>
            !compiler.productions.exists(row =>
              row.kind == production.pattern.kind && row.prefix == production.pattern.prefix &&
                CatalogShapeMatcher.coversFields(production.pattern.fields, row.fields) &&
                row.occurrences.exists(occurrence =>
                  CatalogShapeMatcher.aggregateContextMatches(pattern.context, occurrence.context) &&
                    pattern.sourceClassification == occurrence.sourceClassification &&
                    pattern.scannerEvidence.required.subsetOf(occurrence.scannerTokenKinds.toSet) &&
                    pattern.scannerEvidence.forbidden.intersect(occurrence.scannerTokenKinds.toSet).isEmpty &&
                    CatalogShapeMatcher.directNodeEvidenceMatches(
                      production.pattern.directNodeEvidence,
                      occurrence.directNodeEvidence
                    ) && CatalogShapeMatcher.rootAttachmentEvidenceMatches(
                      production.pattern.requiredAttachments,
                      occurrence.rootAttachments
                    )
                )
            )
          ) =>
        CatalogValidationError.UnrepresentedCatalogProduction(production.id, production.grammarRoleId)
    uncovered ++ unrepresented

  private def coverageError(
      catalog: Scala3PsiProductionCatalog,
      kind: InventoryKind,
      prefix: String,
      context: Option[InventoryContext],
      sourceClassification: SourceClassification,
      selected: Vector[Scala3PsiProduction]
  ): Vector[CatalogValidationError] =
    if selected.isEmpty then
      Vector(CatalogValidationError.UncoveredCompilerShape(kind, prefix, context, sourceClassification))
    else
      ProductionMatchRetention.retain(catalog, selected) match
        case Right(_) => Vector.empty
        case Left(_)  =>
          Vector(
            CatalogValidationError.AmbiguousCompilerShape(
              kind,
              prefix,
              context,
              sourceClassification,
              selected.map(_.id).sorted
            )
          )

  private def valid(cardinality: ChildCardinality): Boolean = cardinality match
    case ChildCardinality.ExactlyOne | ChildCardinality.Optional => true
    case ChildCardinality.Repeated(minimum, maximum)             =>
      minimum >= 0 && maximum.forall(_ >= minimum)
    case ChildCardinality.Grouped(minimum, maximum)              =>
      minimum >= 0 && maximum.forall(_ >= minimum)

  private def valid(cardinality: OccurrenceCardinality): Boolean = cardinality match
    case OccurrenceCardinality.ExactlyOne | OccurrenceCardinality.Optional => true
    case OccurrenceCardinality.Repeated(minimum, maximum)                  =>
      minimum >= 0 && maximum.forall(_ >= minimum)

  private def duplicates[A](values: Vector[A]): Vector[A] =
    values
      .groupMapReduce(identity)(_ => 1)(_ + _)
      .collect { case (value, n) if n > 1 => value }
      .toVector
      .sortBy(_.toString)
