package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

private[metallurgy] object CatalogShapeMatcher:
  private def routeLineageMayMatch(route: OwnedRootRoute, lineage: Vector[InventoryAncestor]): Boolean =
    val expected = route.descendantPath :+ route.rootOwner :+ route.outerOwner
    route.repeatedEdge match
      case None           => lineage.startsWith(expected)
      case Some(repeated) =>
        if repeated.insertionIndex < 0 || repeated.insertionIndex > route.descendantPath.size then false
        else
          val before    = expected.take(repeated.insertionIndex)
          val after     = expected.drop(repeated.insertionIndex)
          val remaining = lineage.drop(repeated.insertionIndex).dropWhile(_ == repeated.edge)
          lineage.startsWith(before) && remaining.startsWith(after)

  private def scannerEvidenceMatches(
      pattern: ScannerEvidencePattern,
      observed: Vector[ParserScannerTokenKind]
  ): Boolean =
    val kinds = observed.toSet
    pattern.required.subsetOf(kinds) && pattern.forbidden.intersect(kinds).isEmpty

  private[psiproducer] def directNodeEvidenceMatches(
      expected: Vector[DirectNodeFieldEvidence],
      observed: Vector[DirectNodeFieldEvidence]
  ): Boolean = expected.forall: requirement =>
    observed.exists: evidence =>
      requirement.fieldName == evidence.fieldName &&
        requirement.sourceClassification == evidence.sourceClassification &&
        requirement.hasSourceWidth.forall(expected => evidence.hasSourceWidth.contains(expected)) &&
        requirement.requiredAttachmentKinds.subsetOf(evidence.requiredAttachmentKinds)

  private[psiproducer] def rootAttachmentEvidenceMatches(
      expected: Vector[AttachmentEvidence],
      observed: Vector[AttachmentEvidence]
  ): Boolean =
    expected.map(_.keyKind).distinct.size == expected.size && expected.forall: requirement =>
      observed.filter(_.keyKind == requirement.keyKind) == Vector(requirement)

  private[psiproducer] def rootAttachmentConditionMatches(
      requirement: AttachmentEvidence,
      present: Boolean,
      observed: Vector[AttachmentEvidence]
  ): Boolean =
    observed.filter(_.keyKind == requirement.keyKind) match
      case Vector()              => !present
      case Vector(`requirement`) => present
      case _                     => false

  def matches(pattern: CatalogValuePattern, observation: InventoryValueObservation): Boolean =
    (pattern, observation) match
      case (CatalogValuePattern.Node, InventoryValueObservation.Node(_, _))                                   => true
      case (CatalogValuePattern.NodePrefix(expected), InventoryValueObservation.Node(_, observed))            =>
        expected == observed
      case (CatalogValuePattern.NodeExceptPrefix(excluded), InventoryValueObservation.Node(_, observed))      =>
        excluded != observed
      case (CatalogValuePattern.Positioned, InventoryValueObservation.Positioned(_, _))                       => true
      case (CatalogValuePattern.Optional(expected), InventoryValueObservation.Optional(Some(value)))          =>
        matches(expected, value)
      case (CatalogValuePattern.Optional(_), InventoryValueObservation.Optional(None))                        => true
      case (CatalogValuePattern.EmptyOptional(_), InventoryValueObservation.Optional(None))                   => true
      case (CatalogValuePattern.Repeated(expected), InventoryValueObservation.Repeated(values))               =>
        values.forall(matches(expected, _))
      case (CatalogValuePattern.NonEmptyRepeated(expected), InventoryValueObservation.Repeated(values))       =>
        values.nonEmpty && values.forall(matches(expected, _))
      case (CatalogValuePattern.EmptyRepeated(_), InventoryValueObservation.Repeated(values))                 =>
        values.isEmpty
      case (
            CatalogValuePattern.LeadingThenRepeated(leading, trailing),
            InventoryValueObservation.Repeated(values)
          ) =>
        values.headOption.exists(matches(leading, _)) && values.tail.forall(matches(trailing, _))
      case (
            CatalogValuePattern.NonEmptyRepeatedEndingWith(leading, trailing),
            InventoryValueObservation.Repeated(values)
          ) =>
        values.nonEmpty && values.init.forall(matches(leading, _)) && matches(trailing, values.last)
      case (CatalogValuePattern.AnyOf(values), observed)                                                      =>
        values.exists(matches(_, observed))
      case (CatalogValuePattern.Product(prefix, expected), InventoryValueObservation.Product(actual, fields)) =>
        prefix == actual && matchesFields(expected, fields)
      case (
            CatalogValuePattern.Name,
            _: InventoryValueObservation.Name | _: InventoryValueObservation.GeneratedName
          ) =>
        true
      case (CatalogValuePattern.Name, _: InventoryValueObservation.BacktickedName)                            => true
      case (CatalogValuePattern.GeneratedName, InventoryValueObservation.GeneratedName(_, _, _))              => true
      case (CatalogValuePattern.ClassifiedName(expected), InventoryValueObservation.Name(value))              =>
        expected == NeutralNameClass.classify(value)
      case (CatalogValuePattern.LowercaseName, InventoryValueObservation.Name(value))                         =>
        CatalogValuePattern.isLowercaseName(value)
      case (CatalogValuePattern.NonLowercaseName, InventoryValueObservation.Name(value))                      =>
        CatalogValuePattern.isNonLowercaseName(value)
      case (CatalogValuePattern.BacktickedName, _: InventoryValueObservation.BacktickedName)                  => true
      case (
            CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary),
            InventoryValueObservation.BacktickedName(_)
          ) =>
        true
      case (CatalogValuePattern.Scalar(kind), InventoryValueObservation.Scalar(value))                        =>
        kind == value.productPrefix
      case (CatalogValuePattern.ExactScalar(kind, rendered), InventoryValueObservation.Scalar(value))         =>
        kind == value.productPrefix && rendered == value.toString
      case (CatalogValuePattern.Unsupported(runtimeType), InventoryValueObservation.Unsupported(actual))      =>
        runtimeType == actual
      case _                                                                                                  => false

  def matchesFields(
      expected: Vector[CompilerFieldPattern],
      observed: Vector[InventoryFieldObservation]
  ): Boolean =
    expected.size == observed.size && expected
      .zip(observed)
      .forall: (pattern, observation) =>
        pattern.name == observation.name && matches(pattern.value, observation.value)

  def covers(expected: CatalogValuePattern, observed: CatalogValuePattern): Boolean =
    (expected, observed) match
      case (CatalogValuePattern.Node, CatalogValuePattern.NodePrefix(_))                                        => true
      case (CatalogValuePattern.NodePrefix(expected), CatalogValuePattern.NodePrefix(observed))                 =>
        expected == observed
      case (CatalogValuePattern.NodeExceptPrefix(excluded), CatalogValuePattern.NodePrefix(observed))           =>
        excluded != observed
      case (CatalogValuePattern.Name, CatalogValuePattern.Name | CatalogValuePattern.GeneratedName)             => true
      case (CatalogValuePattern.Name, CatalogValuePattern.ClassifiedName(_))                                    => true
      case (CatalogValuePattern.Name, CatalogValuePattern.LowercaseName | CatalogValuePattern.NonLowercaseName) => true
      case (CatalogValuePattern.Name, CatalogValuePattern.BacktickedName)                                       => true
      case (
            CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary),
            CatalogValuePattern.LowercaseName | CatalogValuePattern.NonLowercaseName
          ) =>
        true
      case (CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary), CatalogValuePattern.BacktickedName)  => true
      case (CatalogValuePattern.ClassifiedName(expected), CatalogValuePattern.ClassifiedName(observed))         =>
        expected == observed
      case (CatalogValuePattern.Optional(expectedValue), CatalogValuePattern.Optional(observedValue))           =>
        covers(expectedValue, observedValue)
      case (CatalogValuePattern.Optional(expectedValue), CatalogValuePattern.EmptyOptional(observedValue))      =>
        covers(expectedValue, observedValue)
      case (CatalogValuePattern.EmptyOptional(expectedValue), CatalogValuePattern.EmptyOptional(observedValue)) =>
        covers(expectedValue, observedValue)
      case (CatalogValuePattern.Repeated(expectedValue), CatalogValuePattern.Repeated(observedValue))           =>
        covers(expectedValue, observedValue)
      case (CatalogValuePattern.NonEmptyRepeated(expectedValue), CatalogValuePattern.Repeated(observedValue))   =>
        covers(expectedValue, observedValue)
      case (CatalogValuePattern.Repeated(expectedValue), CatalogValuePattern.EmptyRepeated(observedValue))      =>
        covers(expectedValue, observedValue)
      case (CatalogValuePattern.EmptyRepeated(expectedValue), CatalogValuePattern.EmptyRepeated(observedValue)) =>
        covers(expectedValue, observedValue)
      case (CatalogValuePattern.LeadingThenRepeated(leading, trailing), CatalogValuePattern.Repeated(observed)) =>
        covers(leading, observed) && covers(trailing, observed)
      case (
            CatalogValuePattern.NonEmptyRepeatedEndingWith(leading, trailing),
            CatalogValuePattern.Repeated(observed)
          ) =>
        covers(leading, observed) && covers(trailing, observed)
      case (
            CatalogValuePattern.NonEmptyRepeatedEndingWith(leading, trailing),
            CatalogValuePattern.NonEmptyRepeatedEndingWith(observedLeading, observedTrailing)
          ) =>
        covers(leading, observedLeading) && covers(trailing, observedTrailing)
      case (CatalogValuePattern.AnyOf(expected), CatalogValuePattern.AnyOf(observed))                          =>
        expected.forall(e => observed.exists(covers(e, _)))
      case (CatalogValuePattern.AnyOf(expected), observed)                                                     =>
        expected.exists(covers(_, observed))
      case (expected, CatalogValuePattern.AnyOf(observed))                                                      =>
        observed.forall(covers(expected, _))
      case (
            CatalogValuePattern.Product(expectedPrefix, expectedFields),
            CatalogValuePattern.Product(observedPrefix, observedFields)
          ) =>
        expectedPrefix == observedPrefix && coversFields(expectedFields, observedFields)
      case (CatalogValuePattern.Scalar(expected), CatalogValuePattern.ExactScalar(observed, _))                 =>
        expected == observed
      case (
            CatalogValuePattern.ExactScalar(expectedKind, expectedValue),
            CatalogValuePattern.ExactScalar(observedKind, observedValue)
          ) =>
        expectedKind == observedKind && expectedValue == observedValue
      case _                                                                                                    => expected == observed

  def coversFields(
      expected: Vector[CompilerFieldPattern],
      observed: Vector[CompilerFieldPattern]
  ): Boolean =
    expected.size == observed.size && expected
      .zip(observed)
      .forall: (catalogField, compilerField) =>
        catalogField.name == compilerField.name && covers(catalogField.value, compilerField.value)

  def contextMatches(
      pattern: ContextPattern,
      context: Option[InventoryContext],
      ownedRootMatches: OwnedRootRoute => Boolean = _ => false,
      enabledCandidateRootMatches: OwnedRootRoute => Boolean = _ => false
  ): Boolean = pattern match
    case ContextPattern.Any                                                                                  => true
    case ContextPattern.Root                                                                                 => context.isEmpty
    case ContextPattern.Parent(kind, owner, p)                                                               =>
      context.exists(value => value.ownerKind == kind && value.ownerPrefix == owner && value.path == p)
    case ContextPattern.ParentWithNodeField(kind, owner, p, fieldName, fieldPrefix)                          =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ownerNodePrefixes.get(fieldName).contains(fieldPrefix)
      )
    case ContextPattern.ParentWithoutNodeFieldPrefix(kind, owner, p, fieldName, excluded)                    =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ownerNodePrefixes.get(fieldName).forall(_ != excluded)
      )
    case ContextPattern.ParentWithNodeFieldUnderAnchor(kind, owner, p, fieldName, prefix, anchor)            =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ownerNodePrefixes.get(fieldName).contains(prefix) && value.ancestors.contains(anchor)
      )
    case ContextPattern.ParentWithoutNodeFieldPrefixUnderAnchor(kind, owner, p, fieldName, excluded, anchor) =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ownerNodePrefixes.get(fieldName).forall(_ != excluded) && value.ancestors.contains(anchor)
      )
    case ContextPattern.ParentUnderAnchor(kind, owner, p, anchor)                                            =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p && value.ancestors.contains(anchor)
      )
    case ContextPattern.ParentUnderAnchorWithEvidence(kind, owner, p, anchor, patterns)                      =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ancestors
            .zip(value.ancestorEvidence)
            .exists: (ancestor, observed) =>
              ancestor == anchor && patterns.exists(pattern =>
                scannerEvidenceMatches(pattern.scannerEvidence, observed.scannerTokenKinds) &&
                  directNodeEvidenceMatches(pattern.directNodeEvidence, observed.directNodeEvidence)
              )
      )
    case ContextPattern.ParentUnderAnchorThrough(kind, owner, p, ancestors, anchor)                          =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ancestors.dropWhile(value => value != anchor && ancestors.contains(value)).headOption.contains(anchor)
      )
    case ContextPattern.DescendantOfOwnedRoot(routes)                                                        =>
      context.exists: value =>
        val lineage = InventoryAncestor(value.ownerKind, value.ownerPrefix, value.path) +: value.ancestors
        routes.exists(route => routeLineageMayMatch(route, lineage) && ownedRootMatches(route))
    case ContextPattern.DescendantOfEnabledCandidateRoot(routes)                                             =>
      context.exists: value =>
        val lineage = InventoryAncestor(value.ownerKind, value.ownerPrefix, value.path) +: value.ancestors
        routes.exists(route => routeLineageMayMatch(route, lineage) && enabledCandidateRootMatches(route))
    case ContextPattern.ParentUnderAnchorThroughWithParent(kind, owner, p, ancestors, anchor, parent)        =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ancestors
            .dropWhile(value => value != anchor && ancestors.contains(value))
            .startsWith(Vector(anchor, parent))
      )
    case ContextPattern.ParentWithoutNodeFieldPrefixUnderAnchorThroughWithParent(
          kind,
          owner,
          p,
          fieldName,
          excluded,
          ancestors,
          anchor,
          parent
        ) =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ownerNodePrefixes.get(fieldName).forall(_ != excluded) &&
          value.ancestors
            .dropWhile(value => value != anchor && ancestors.contains(value))
            .startsWith(Vector(anchor, parent))
      )
    case ContextPattern.ParentWithAncestor(kind, owner, p, next)                                             =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p && value.ancestors.headOption.contains(
          next
        )
      )
    case ContextPattern.ParentWithAncestorPrefix(kind, owner, p, ancestors)                                  =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ancestors.startsWith(ancestors)
      )
    case ContextPattern.AnchorOrParentWithRepeatedAncestor(anchor, kind, owner, p, repeated)                 =>
      context.exists(value =>
        InventoryAncestor(value.ownerKind, value.ownerPrefix, value.path) == anchor ||
          (value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
            value.ancestors.dropWhile(_ == repeated).headOption.contains(anchor))
      )
    case ContextPattern.ParentWithRepeatedAncestor(kind, owner, p, repeated, anchor)                         =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ancestors.dropWhile(_ == repeated).headOption.contains(anchor)
      )
    case ContextPattern.ParentWithRepeatedAncestorSequencePrefix(kind, owner, p, repeated, ancestors)        =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          dropRepeatedAncestorSequence(value.ancestors, repeated).startsWith(ancestors)
      )
    case ContextPattern.ParentWithRepeatedAncestorPrefix(kind, owner, p, repeated, ancestors)                =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ancestors.dropWhile(_ == repeated).startsWith(ancestors)
      )

  def aggregateContextMatches(pattern: ContextPattern, context: Option[InventoryContext]): Boolean = pattern match
    case ContextPattern.Any                                                                                  => false
    case ContextPattern.Root                                                                                 => context.isEmpty
    case ContextPattern.Parent(kind, owner, p)                                                               =>
      context.exists(value => value.ownerKind == kind && value.ownerPrefix == owner && value.path == p)
    case ContextPattern.ParentWithNodeField(kind, owner, p, fieldName, fieldPrefix)                          =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ownerNodePrefixes.get(fieldName).contains(fieldPrefix)
      )
    case ContextPattern.ParentWithoutNodeFieldPrefix(kind, owner, p, fieldName, excluded)                    =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ownerNodePrefixes.get(fieldName).forall(_ != excluded)
      )
    case ContextPattern.ParentWithNodeFieldUnderAnchor(kind, owner, p, fieldName, prefix, anchor)            =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ownerNodePrefixes.get(fieldName).contains(prefix) && value.ancestors.contains(anchor)
      )
    case ContextPattern.ParentWithoutNodeFieldPrefixUnderAnchor(kind, owner, p, fieldName, excluded, anchor) =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ownerNodePrefixes.get(fieldName).forall(_ != excluded) && value.ancestors.contains(anchor)
      )
    case ContextPattern.ParentUnderAnchor(kind, owner, p, anchor)                                            =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p && value.ancestors.contains(anchor)
      )
    case ContextPattern.ParentUnderAnchorWithEvidence(kind, owner, p, anchor, patterns)                      =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ancestors
            .zip(value.ancestorEvidence)
            .exists: (ancestor, observed) =>
              ancestor == anchor && patterns.exists(pattern =>
                scannerEvidenceMatches(pattern.scannerEvidence, observed.scannerTokenKinds) &&
                  directNodeEvidenceMatches(pattern.directNodeEvidence, observed.directNodeEvidence)
              )
      )
    case ContextPattern.ParentUnderAnchorThrough(kind, owner, p, ancestors, anchor)                          =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ancestors.dropWhile(value => value != anchor && ancestors.contains(value)).headOption.contains(anchor)
      )
    case ContextPattern.DescendantOfOwnedRoot(routes)                                                        =>
      false
    case ContextPattern.DescendantOfEnabledCandidateRoot(routes)                                             =>
      false
    case ContextPattern.ParentUnderAnchorThroughWithParent(kind, owner, p, ancestors, anchor, parent)        =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ancestors
            .dropWhile(value => value != anchor && ancestors.contains(value))
            .startsWith(Vector(anchor, parent))
      )
    case ContextPattern.ParentWithoutNodeFieldPrefixUnderAnchorThroughWithParent(
          kind,
          owner,
          p,
          fieldName,
          excluded,
          ancestors,
          anchor,
          parent
        ) =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ownerNodePrefixes.get(fieldName).forall(_ != excluded) &&
          value.ancestors
            .dropWhile(value => value != anchor && ancestors.contains(value))
            .startsWith(Vector(anchor, parent))
      )
    case ContextPattern.ParentWithAncestor(kind, owner, p, next)                                             =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p && value.ancestors.headOption.contains(
          next
        )
      )
    case ContextPattern.ParentWithAncestorPrefix(kind, owner, p, ancestors)                                  =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ancestors.startsWith(ancestors)
      )
    case ContextPattern.AnchorOrParentWithRepeatedAncestor(anchor, kind, owner, p, repeated)                 =>
      context.exists(value =>
        InventoryAncestor(value.ownerKind, value.ownerPrefix, value.path) == anchor ||
          (value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
            value.ancestors.dropWhile(_ == repeated).headOption.contains(anchor))
      )
    case ContextPattern.ParentWithRepeatedAncestor(kind, owner, p, repeated, anchor)                         =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ancestors.dropWhile(_ == repeated).headOption.contains(anchor)
      )
    case ContextPattern.ParentWithRepeatedAncestorSequencePrefix(kind, owner, p, repeated, ancestors)        =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          dropRepeatedAncestorSequence(value.ancestors, repeated).startsWith(ancestors)
      )
    case ContextPattern.ParentWithRepeatedAncestorPrefix(kind, owner, p, repeated, ancestors)                =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ancestors.dropWhile(_ == repeated).startsWith(ancestors)
      )

  private def dropRepeatedAncestorSequence(
      ancestors: Vector[InventoryAncestor],
      repeated: Vector[InventoryAncestor]
  ): Vector[InventoryAncestor] =
    require(repeated.nonEmpty)
    var remaining = ancestors
    while remaining.startsWith(repeated) do remaining = remaining.drop(repeated.size)
    remaining

  def select(
      catalog: Scala3PsiProductionCatalog,
      kind: InventoryKind,
      prefix: String,
      fields: Vector[InventoryFieldObservation],
      context: Option[InventoryContext],
      sourceClassification: SourceClassification,
      scannerTokenKinds: Vector[ParserScannerTokenKind] = Vector.empty,
      directNodeEvidence: Vector[DirectNodeFieldEvidence] = Vector.empty,
      rootAttachments: Vector[AttachmentEvidence] = Vector.empty,
      ownedRootMatches: OwnedRootRoute => Boolean = _ => false,
      enabledCandidateRootMatches: OwnedRootRoute => Boolean = _ => false
  ): Vector[Scala3PsiProduction] =
    val matched              = catalog.productions.filter(p =>
      p.pattern.kind == kind && p.pattern.prefix == prefix && matchesFields(p.pattern.fields, fields) &&
        directNodeEvidenceMatches(p.pattern.directNodeEvidence, directNodeEvidence) &&
        rootAttachmentEvidenceMatches(p.pattern.requiredAttachments, rootAttachments) &&
        p.pattern.occurrences.exists(occurrence =>
          contextMatches(occurrence.context, context, ownedRootMatches, enabledCandidateRootMatches) &&
            occurrence.sourceClassification == sourceClassification
            && scannerEvidenceMatches(occurrence.scannerEvidence, scannerTokenKinds)
        )
    )
    val scored               = matched.map: production =>
      val ownedRootSpecificity = production.pattern.occurrences.count:
        case CompilerProductionContextPattern(
              ContextPattern.DescendantOfOwnedRoot(routes),
              `sourceClassification`,
              scannerEvidence
            ) =>
          scannerEvidenceMatches(scannerEvidence, scannerTokenKinds) && routes.exists(ownedRootMatches)
        case CompilerProductionContextPattern(
              ContextPattern.DescendantOfEnabledCandidateRoot(routes),
              `sourceClassification`,
              scannerEvidence
            ) =>
          scannerEvidenceMatches(scannerEvidence, scannerTokenKinds) && routes.exists(enabledCandidateRootMatches)
        case CompilerProductionContextPattern(
              pattern: (ContextPattern.ParentUnderAnchorThroughWithParent |
                ContextPattern.ParentWithoutNodeFieldPrefixUnderAnchorThroughWithParent),
              `sourceClassification`,
              scannerEvidence
            ) =>
          scannerEvidenceMatches(scannerEvidence, scannerTokenKinds) && contextMatches(
            pattern,
            context,
            ownedRootMatches,
            enabledCandidateRootMatches
          )
        case _ => false
      val specificity          = production.pattern.fields
        .zip(fields)
        .count:
          case (
                CompilerFieldPattern(_, CatalogValuePattern.EmptyRepeated(_)),
                InventoryFieldObservation(_, InventoryValueObservation.Repeated(Vector()), _)
              ) =>
            true
          case (
                CompilerFieldPattern(_, CatalogValuePattern.EmptyOptional(_)),
                InventoryFieldObservation(_, InventoryValueObservation.Optional(None), _)
              ) =>
            true
          case (
                CompilerFieldPattern(_, CatalogValuePattern.ExactScalar(kind, rendered)),
                InventoryFieldObservation(_, InventoryValueObservation.Scalar(value), _)
              ) =>
            kind == value.productPrefix && rendered == value.toString
          case (
                CompilerFieldPattern(_, CatalogValuePattern.LowercaseName),
                InventoryFieldObservation(_, InventoryValueObservation.Name(value), _)
              ) =>
            CatalogValuePattern.isLowercaseName(value)
          case (
                CompilerFieldPattern(_, CatalogValuePattern.NonLowercaseName),
                InventoryFieldObservation(_, InventoryValueObservation.Name(value), _)
              ) =>
            CatalogValuePattern.isNonLowercaseName(value)
          case (
                CompilerFieldPattern(_, CatalogValuePattern.NonEmptyRepeatedEndingWith(_, _)),
                InventoryFieldObservation(_, InventoryValueObservation.Repeated(values), _)
              ) =>
            values.nonEmpty
          case (
                CompilerFieldPattern(_, CatalogValuePattern.AnyOf(values)),
                InventoryFieldObservation(_, value, _)
              ) =>
            values.exists(candidate =>
              candidate == CatalogValuePattern.LowercaseName ||
                candidate == CatalogValuePattern.NonLowercaseName ||
                candidate == CatalogValuePattern.BacktickedName
            ) && CatalogShapeMatcher.matches(CatalogValuePattern.AnyOf(values), value)
          case _ => false
      production -> (
        specificity + production.pattern.directNodeEvidence.size + production.pattern.requiredAttachments.size +
          ownedRootSpecificity
      )
    val highest              = scored.map(_._2).maxOption.getOrElse(0)
    val preferred            = scored.collect { case (production, score) if score == highest => production }
    val matchedById          = matched.map(production => production.id -> production).toMap
    val retainedAlternatives = preferred.flatMap: production =>
      Option
        .when(production.realizationChoice.nonEmpty)(
          catalog.productionAlternatives.flatMap: alternative =>
            if alternative.candidateId == production.id then matchedById.get(alternative.fallbackId)
            else None
        )
        .toVector
        .flatten
    (preferred ++ retainedAlternatives).distinct

  def selectAggregated(
      catalog: Scala3PsiProductionCatalog,
      row: AggregatedCompilerProductionRow,
      occurrence: CompilerProductionContext
  ): Vector[Scala3PsiProduction] =
    val matched = catalog.productions.filter(p =>
      p.pattern.kind == row.kind && p.pattern.prefix == row.prefix && coversFields(p.pattern.fields, row.fields) &&
        directNodeEvidenceMatches(p.pattern.directNodeEvidence, occurrence.directNodeEvidence) &&
        rootAttachmentEvidenceMatches(p.pattern.requiredAttachments, occurrence.rootAttachments) &&
        p.pattern.occurrences.exists(pattern =>
          aggregateContextMatches(pattern.context, occurrence.context) &&
            pattern.sourceClassification == occurrence.sourceClassification &&
            scannerEvidenceMatches(pattern.scannerEvidence, occurrence.scannerTokenKinds)
        )
    )
    val scored  = matched.map: production =>
      val ownedRootSpecificity = production.pattern.occurrences.count:
        case CompilerProductionContextPattern(
              ContextPattern.DescendantOfOwnedRoot(_) | ContextPattern.DescendantOfEnabledCandidateRoot(_) |
              _: ContextPattern.ParentUnderAnchorThroughWithParent |
              _: ContextPattern.ParentWithoutNodeFieldPrefixUnderAnchorThroughWithParent,
              _,
              _
            ) =>
          true
        case _ => false
      production -> (production.pattern.fields.count(field =>
        field.value match
          case CatalogValuePattern.LowercaseName |
              CatalogValuePattern.NonLowercaseName | CatalogValuePattern.BacktickedName =>
            true
          case CatalogValuePattern.NonEmptyRepeatedEndingWith(_, _) =>
            true
          case CatalogValuePattern.AnyOf(values) =>
            values.exists(candidate =>
              candidate == CatalogValuePattern.LowercaseName ||
                candidate == CatalogValuePattern.NonLowercaseName ||
                candidate == CatalogValuePattern.BacktickedName
            )
          case _                                 => false
      ) + production.pattern.directNodeEvidence.size + production.pattern.requiredAttachments.size + ownedRootSpecificity)
    val highest = scored.map(_._2).maxOption.getOrElse(0)
    scored.collect { case (production, score) if score == highest => production }
