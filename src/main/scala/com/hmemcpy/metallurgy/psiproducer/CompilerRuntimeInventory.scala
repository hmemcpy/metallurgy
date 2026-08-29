package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import scala.util.boundary
import scala.util.boundary.break

private[metallurgy] final case class CompilerArtifactIdentity(
    ordinal: Int,
    fileName: String,
    byteSize: Long,
    sha256: String
)
private[metallurgy] final case class CompilerRuntimeIdentity(
    coordinate: Scala3ParserArtifactCoordinate,
    artifacts: Vector[CompilerArtifactIdentity],
    compilerOptions: Vector[String]
)

private[metallurgy] enum InventoryKind:
  case Node, Positioned, Product
private[metallurgy] enum SourceClassification:
  case SourceReachable, Synthetic, Absent
private[metallurgy] enum CatalogPathSegment:
  case NamedField(name: String)
  case Optional
  case RepeatedElement
  case NestedProduct(production: String)
private[metallurgy] final case class InventoryAncestor(
    ownerKind: InventoryKind,
    ownerPrefix: String,
    path: Vector[CatalogPathSegment]
)
private[metallurgy] final case class RepeatedOwnedRootEdge(
    insertionIndex: Int,
    edge: InventoryAncestor
)
private[metallurgy] final case class OwnedRootRoute(
    rootProductionId: String,
    descendantPath: Vector[InventoryAncestor],
    rootOwner: InventoryAncestor,
    outerOwner: InventoryAncestor,
    repeatedEdge: Option[RepeatedOwnedRootEdge] = None
)
private[metallurgy] final case class InventoryAncestorEvidence(
    scannerTokenKinds: Vector[ParserScannerTokenKind],
    directNodeEvidence: Vector[DirectNodeFieldEvidence]
)
private[metallurgy] final case class InventoryContext(
    ownerKind: InventoryKind,
    ownerPrefix: String,
    path: Vector[CatalogPathSegment],
    ancestors: Vector[InventoryAncestor] = Vector.empty,
    ownerNodePrefixes: Map[String, String] = Map.empty,
    ancestorEvidence: Vector[InventoryAncestorEvidence] = Vector.empty
)
private[metallurgy] object InventoryContextLineage:
  private final case class Lineage(
      ancestors: Vector[InventoryAncestor],
      evidence: Vector[InventoryAncestorEvidence]
  )

  def normalized(path: Vector[ParserFieldPathSegment]): Vector[CatalogPathSegment] = path.map:
    case ParserFieldPathSegment.NamedField(name)                  => CatalogPathSegment.NamedField(name)
    case ParserFieldPathSegment.OptionalNesting                   => CatalogPathSegment.Optional
    case ParserFieldPathSegment.RepeatedIndex(_)                  => CatalogPathSegment.RepeatedElement
    case ParserFieldPathSegment.NestedProductBoundary(production) => CatalogPathSegment.NestedProduct(production)

  def resolver(
      nodes: Map[Long, ParserSyntaxNode],
      evidence: Map[Long, InventoryAncestorEvidence] = Map.empty
  ): Resolver = new Resolver(nodes, evidence)

  final class Resolver private[InventoryContextLineage] (
      nodes: Map[Long, ParserSyntaxNode],
      evidence: Map[Long, InventoryAncestorEvidence]
  ):
    private val cached =
      val knownParents = nodes.view
        .mapValues(node => node.occurrences.iterator.map(_.ownerNodeId).filter(nodes.contains).toVector.distinct)
        .toMap
      val children     = knownParents.toVector
        .flatMap((child, parents) => parents.map(_ -> child))
        .groupMap(_._1)(_._2)
        .view
        .mapValues(_.distinct.sorted)
        .toMap
      val remaining    = collection.mutable.Map.from(knownParents.view.mapValues(_.size))
      val ready        = collection.mutable.Queue.from(
        remaining.iterator.collect { case (id, 0) => id }.toVector.sorted
      )
      val values       = collection.mutable.Map.empty[Long, Vector[Lineage]]
      while ready.nonEmpty do
        val id       = ready.dequeue()
        val node     = nodes(id)
        val computed =
          if node.occurrences.isEmpty then Vector(Lineage(Vector.empty, Vector.empty))
          else
            node.occurrences.flatMap: occurrence =>
              nodes
                .get(occurrence.ownerNodeId)
                .toVector
                .flatMap: ancestor =>
                  values
                    .getOrElse(ancestor.id, Vector.empty)
                    .map: lineage =>
                      Lineage(
                        InventoryAncestor(
                          InventoryKind.Node,
                          ancestor.production,
                          normalized(occurrence.fieldPath)
                        ) +: lineage.ancestors,
                        evidence.getOrElse(ancestor.id, InventoryAncestorEvidence(Vector.empty, Vector.empty)) +:
                          lineage.evidence
                      )
        values += id -> computed
        children
          .getOrElse(id, Vector.empty)
          .foreach: child =>
            val next = remaining(child) - 1
            remaining.update(child, next)
            if next == 0 then ready.enqueue(child)
      Option.when(values.size == nodes.size)(values.toMap)

    def contexts(owner: ParserSyntaxNode, path: Vector[ParserFieldPathSegment]): Vector[InventoryContext] =
      cached
        .flatMap(_.get(owner.id))
        .getOrElse(ancestries(owner, nodes, evidence, Set.empty))
        .map: lineage =>
          val ownerNodePrefixes = owner.fields.flatMap: field =>
            field.value match
              case ParserFieldValue.Node(id) => nodes.get(id).map(value => field.name -> value.production)
              case _                         => None
          InventoryContext(
            InventoryKind.Node,
            owner.production,
            normalized(path),
            lineage.ancestors,
            ownerNodePrefixes.toMap,
            lineage.evidence
          )

  private def ancestries(
      owner: ParserSyntaxNode,
      nodes: Map[Long, ParserSyntaxNode],
      evidence: Map[Long, InventoryAncestorEvidence],
      visited: Set[Long]
  ): Vector[Lineage] =
    val pending  = collection.mutable.Stack(
      (owner, visited, Vector.empty[InventoryAncestor], Vector.empty[InventoryAncestorEvidence])
    )
    val complete = Vector.newBuilder[Lineage]
    while pending.nonEmpty do
      val (current, currentVisited, currentAncestors, currentEvidence) = pending.pop()
      if !currentVisited(current.id) then
        if current.occurrences.isEmpty then complete += Lineage(currentAncestors, currentEvidence)
        else
          current.occurrences.reverseIterator.foreach: occurrence =>
            nodes
              .get(occurrence.ownerNodeId)
              .foreach: ancestor =>
                pending.push(
                  (
                    ancestor,
                    currentVisited + current.id,
                    currentAncestors :+ InventoryAncestor(
                      InventoryKind.Node,
                      ancestor.production,
                      normalized(occurrence.fieldPath)
                    ),
                    currentEvidence :+ evidence.getOrElse(
                      ancestor.id,
                      InventoryAncestorEvidence(Vector.empty, Vector.empty)
                    )
                  )
                )
    complete.result()

private[metallurgy] enum NeutralNameClass:
  case Ordinary, Wildcard, Empty

private[metallurgy] object NeutralNameClass:
  def classify(value: String): NeutralNameClass = value match
    case ""  => Empty
    case "_" => Wildcard
    case _   => Ordinary

private[metallurgy] enum CatalogValuePattern:
  case Node
  case NodePrefix(prefix: String)
  case NodeExceptPrefix(prefix: String)
  case Positioned
  case Optional(value: CatalogValuePattern)
  case EmptyOptional(value: CatalogValuePattern)
  case Repeated(element: CatalogValuePattern)
  case NonEmptyRepeated(element: CatalogValuePattern)
  case EmptyRepeated(element: CatalogValuePattern)
  case LeadingThenRepeated(leading: CatalogValuePattern, trailing: CatalogValuePattern)
  case NonEmptyRepeatedEndingWith(leading: CatalogValuePattern, trailing: CatalogValuePattern)
  case AnyOf(values: Vector[CatalogValuePattern])
  case Product(prefix: String, fields: Vector[CompilerFieldPattern])
  case Name, GeneratedName
  case ClassifiedName(nameClass: NeutralNameClass)
  case LowercaseName, NonLowercaseName, BacktickedName
  case ExactName(value: String)
  case Scalar(kind: String)
  case ExactScalar(kind: String, rendered: String)
  case Unsupported(runtimeType: String)
private[metallurgy] object CatalogValuePattern:
  def isLowercaseName(value: String): Boolean    =
    value.nonEmpty && value != "_" && Character.isLowerCase(value.codePointAt(0))
  def isNonLowercaseName(value: String): Boolean =
    value.nonEmpty && value != "_" && !isLowercaseName(value)
private[metallurgy] enum InventoryValueObservation:
  case Node(id: Long, prefix: String)
  case Positioned(id: Long, prefix: String)
  case Optional(value: Option[InventoryValueObservation])
  case Repeated(values: Vector[InventoryValueObservation])
  case Product(prefix: String, fields: Vector[InventoryFieldObservation])
  case Name(value: String)
  case BacktickedName(value: String)
  case GeneratedName(base: String, separator: String, generationIndex: Int)
  case Scalar(value: ParserScalar)
  case Unsupported(runtimeType: String)
private[metallurgy] final case class InventoryFieldObservation(
    name: String,
    value: InventoryValueObservation,
    declaredPattern: Option[CatalogValuePattern] = None
)
private[metallurgy] final case class CompilerFieldPattern(name: String, value: CatalogValuePattern)
private[metallurgy] final case class ScannerEvidencePattern(
    required: Set[ParserScannerTokenKind] = Set.empty,
    forbidden: Set[ParserScannerTokenKind] = Set.empty
)
private[metallurgy] final case class DirectNodeFieldEvidence(
    fieldName: String,
    sourceClassification: SourceClassification,
    hasSourceWidth: Option[Boolean] = None,
    requiredAttachmentKinds: Set[String] = Set.empty
)
private[metallurgy] final case class AttachmentEvidence(keyKind: String, value: ParserAttachmentValue)
private[metallurgy] final case class AncestorEvidencePattern(
    scannerEvidence: ScannerEvidencePattern = ScannerEvidencePattern(),
    directNodeEvidence: Vector[DirectNodeFieldEvidence] = Vector.empty
)
private[metallurgy] final case class CompilerProductionContextPattern(
    context: ContextPattern,
    sourceClassification: SourceClassification,
    scannerEvidence: ScannerEvidencePattern = ScannerEvidencePattern()
)
private[metallurgy] final case class CompilerProductionPattern(
    kind: InventoryKind,
    prefix: String,
    fields: Vector[CompilerFieldPattern],
    occurrences: Vector[CompilerProductionContextPattern],
    directNodeEvidence: Vector[DirectNodeFieldEvidence],
    requiredAttachments: Vector[AttachmentEvidence] = Vector.empty
)
private[metallurgy] object CompilerProductionPattern:
  def apply(
      kind: InventoryKind,
      prefix: String,
      fields: Vector[CompilerFieldPattern],
      occurrences: Vector[CompilerProductionContextPattern]
  ): CompilerProductionPattern =
    CompilerProductionPattern(kind, prefix, fields, occurrences, Vector.empty, Vector.empty)
private[metallurgy] enum ContextPattern:
  case Any
  case Root
  case Parent(ownerKind: InventoryKind, ownerPrefix: String, path: Vector[CatalogPathSegment])
  case ParentWithNodeField(
      ownerKind: InventoryKind,
      ownerPrefix: String,
      path: Vector[CatalogPathSegment],
      fieldName: String,
      fieldPrefix: String
  )
  case ParentWithoutNodeFieldPrefix(
      ownerKind: InventoryKind,
      ownerPrefix: String,
      path: Vector[CatalogPathSegment],
      fieldName: String,
      excludedPrefix: String
  )
  case ParentWithNodeFieldUnderAnchor(
      ownerKind: InventoryKind,
      ownerPrefix: String,
      path: Vector[CatalogPathSegment],
      fieldName: String,
      fieldPrefix: String,
      anchor: InventoryAncestor
  )
  case ParentWithoutNodeFieldPrefixUnderAnchor(
      ownerKind: InventoryKind,
      ownerPrefix: String,
      path: Vector[CatalogPathSegment],
      fieldName: String,
      excludedPrefix: String,
      anchor: InventoryAncestor
  )
  case ParentUnderAnchor(
      ownerKind: InventoryKind,
      ownerPrefix: String,
      path: Vector[CatalogPathSegment],
      anchor: InventoryAncestor
  )
  case ParentUnderAnchorWithEvidence(
      ownerKind: InventoryKind,
      ownerPrefix: String,
      path: Vector[CatalogPathSegment],
      anchor: InventoryAncestor,
      evidence: Vector[AncestorEvidencePattern]
  )
  case ParentUnderAnchorThrough(
      ownerKind: InventoryKind,
      ownerPrefix: String,
      path: Vector[CatalogPathSegment],
      ancestors: Vector[InventoryAncestor],
      anchor: InventoryAncestor
  )
  case DescendantOfOwnedRoot(routes: Vector[OwnedRootRoute])
  case DescendantOfEnabledCandidateRoot(routes: Vector[OwnedRootRoute])
  case ParentUnderAnchorThroughWithParent(
      ownerKind: InventoryKind,
      ownerPrefix: String,
      path: Vector[CatalogPathSegment],
      ancestors: Vector[InventoryAncestor],
      anchor: InventoryAncestor,
      parent: InventoryAncestor
  )
  case ParentWithoutNodeFieldPrefixUnderAnchorThroughWithParent(
      ownerKind: InventoryKind,
      ownerPrefix: String,
      path: Vector[CatalogPathSegment],
      fieldName: String,
      excludedPrefix: String,
      ancestors: Vector[InventoryAncestor],
      anchor: InventoryAncestor,
      parent: InventoryAncestor
  )
  case ParentWithAncestor(
      ownerKind: InventoryKind,
      ownerPrefix: String,
      path: Vector[CatalogPathSegment],
      ancestor: InventoryAncestor
  )
  case ParentWithAncestorPrefix(
      ownerKind: InventoryKind,
      ownerPrefix: String,
      path: Vector[CatalogPathSegment],
      ancestors: Vector[InventoryAncestor]
  )
  case AnchorOrParentWithRepeatedAncestor(
      anchor: InventoryAncestor,
      ownerKind: InventoryKind,
      ownerPrefix: String,
      path: Vector[CatalogPathSegment],
      repeatedAncestor: InventoryAncestor
  )
  case ParentWithRepeatedAncestor(
      ownerKind: InventoryKind,
      ownerPrefix: String,
      path: Vector[CatalogPathSegment],
      repeatedAncestor: InventoryAncestor,
      anchor: InventoryAncestor
  )
  case ParentWithRepeatedAncestorSequencePrefix(
      ownerKind: InventoryKind,
      ownerPrefix: String,
      path: Vector[CatalogPathSegment],
      repeatedAncestors: Vector[InventoryAncestor],
      ancestors: Vector[InventoryAncestor]
  )
  case ParentWithRepeatedAncestorPrefix(
      ownerKind: InventoryKind,
      ownerPrefix: String,
      path: Vector[CatalogPathSegment],
      repeatedAncestor: InventoryAncestor,
      ancestors: Vector[InventoryAncestor]
  )
private[metallurgy] final case class CompilerShapeInventoryRow(
    kind: InventoryKind,
    id: Long,
    prefix: String,
    patternFields: Vector[CompilerFieldPattern],
    observation: Vector[InventoryFieldObservation],
    contexts: Vector[InventoryContext],
    sourceClassification: SourceClassification,
    scannerTokenKinds: Vector[ParserScannerTokenKind] = Vector.empty,
    directNodeEvidence: Vector[DirectNodeFieldEvidence] = Vector.empty,
    rootAttachments: Vector[AttachmentEvidence] = Vector.empty
)
private[metallurgy] final case class CompilerRuntimeInventory(
    identity: CompilerRuntimeIdentity,
    parserEvidenceFingerprint: String,
    shapes: Vector[CompilerShapeInventoryRow],
    nodes: Vector[ParserSyntaxNode],
    products: Vector[ParserProductSyntax] = Vector.empty
)
private[metallurgy] final case class ParserProductSyntax(
    id: Long,
    production: String,
    fields: Vector[ParserSyntaxField],
    position: ParserNodePosition,
    occurrences: Vector[ParserNodeOccurrence]
)
private[metallurgy] final case class CompilerProductionContext(
    context: Option[InventoryContext],
    sourceClassification: SourceClassification,
    scannerTokenKinds: Vector[ParserScannerTokenKind] = Vector.empty,
    directNodeEvidence: Vector[DirectNodeFieldEvidence] = Vector.empty,
    rootAttachments: Vector[AttachmentEvidence] = Vector.empty
)
private[metallurgy] final case class AggregatedCompilerProductionRow(
    kind: InventoryKind,
    prefix: String,
    fields: Vector[CompilerFieldPattern],
    occurrences: Vector[CompilerProductionContext]
):
  def contexts: Vector[Option[InventoryContext]]          = occurrences.map(_.context).distinct
  def sourceClassifications: Vector[SourceClassification] = occurrences.map(_.sourceClassification).distinct
private[metallurgy] final case class AggregatedCompilerProductionInventory(
    identity: CompilerRuntimeIdentity,
    sourceEvidenceFingerprints: Vector[String],
    productions: Vector[AggregatedCompilerProductionRow],
    scenarios: Vector[CompilerRuntimeInventory]
):
  private lazy val encoded: Array[Byte] = AggregatedCompilerProductionInventory.serialize(this)
  lazy val fingerprint: String          = CanonicalByteEncoder.sha256Hex(encoded)
  def canonicalBytes: Array[Byte]       = encoded.clone()

private[metallurgy] enum InventoryAggregationFailure:
  case EmptyInput
  case RuntimeIdentityMismatch(expected: CompilerRuntimeIdentity, actual: CompilerRuntimeIdentity)
  case FieldSignatureConflict(
      kind: InventoryKind,
      prefix: String,
      expected: Vector[String],
      actual: Vector[String]
  )
  case UnresolvedShape(path: Vector[CatalogPathSegment])
  case IncompatibleShape(path: Vector[CatalogPathSegment])

private[metallurgy] object AggregatedCompilerProductionInventory:
  def aggregate(
      inventories: Vector[CompilerRuntimeInventory]
  ): Either[InventoryAggregationFailure, AggregatedCompilerProductionInventory] =
    inventories.headOption match
      case None        => Left(InventoryAggregationFailure.EmptyInput)
      case Some(first) =>
        inventories.find(_.identity != first.identity) match
          case Some(other) => Left(InventoryAggregationFailure.RuntimeIdentityMismatch(first.identity, other.identity))
          case None        => aggregateMatching(first.identity, inventories)

  def serialize(inventory: AggregatedCompilerProductionInventory): Array[Byte] =
    val e = CanonicalByteEncoder()
    e.tag(6)
    writeIdentity(inventory.identity, e)
    e.sequence(inventory.sourceEvidenceFingerprints)(e.string)
    e.sequence(inventory.productions)(writeRow(_, e))
    e.sequence(inventory.scenarios)(writeScenario(_, e))
    e.result()

  private def aggregateMatching(
      identity: CompilerRuntimeIdentity,
      inventories: Vector[CompilerRuntimeInventory]
  ): Either[InventoryAggregationFailure, AggregatedCompilerProductionInventory] =
    boundary:
      val all             = inventories.flatMap(_.shapes)
      all
        .groupBy(row => row.kind -> row.prefix)
        .foreach:
          case ((kind, prefix), observations) =>
            val signatures = observations.map(_.observation.map(_.name)).distinct
            if signatures.size != 1 then
              break(
                Left(InventoryAggregationFailure.FieldSignatureConflict(kind, prefix, signatures.head, signatures(1)))
              )
      val declaredByField = all
        .groupBy(row => row.kind -> row.prefix)
        .flatMap:
          case ((kind, prefix), observations) =>
            observations.head.observation.indices.flatMap: index =>
              infer(observations.map(_.observation(index)), Vector.empty).toOption.map((kind, prefix, index) -> _)
      def withEmptyDeclarations(
          field: InventoryFieldObservation,
          fallback: Option[CatalogValuePattern]
      ): InventoryFieldObservation =
        val value       = (field.value, fallback) match
          case (
                InventoryValueObservation.Product(prefix, nested),
                Some(CatalogValuePattern.Product(actualPrefix, patterns))
              ) if prefix == actualPrefix =>
            InventoryValueObservation.Product(
              prefix,
              nested.zip(patterns).map((candidate, pattern) => withEmptyDeclarations(candidate, Some(pattern.value)))
            )
          case _ => field.value
        val declaration = field.value match
          case InventoryValueObservation.Optional(None) | InventoryValueObservation.Repeated(Vector()) =>
            field.declaredPattern.orElse(fallback)
          case _                                                                                       => field.declaredPattern
        field.copy(value = value, declaredPattern = declaration)
      val grouped         = all.groupBy: row =>
        val e = CanonicalByteEncoder()
        e.sequence(row.observation): field =>
          e.string(field.name)
          writeObservedPartition(field.value, field.declaredPattern, e)
        (row.kind, row.prefix, java.util.Base64.getEncoder.encodeToString(e.result()))
      val rows            = Vector.newBuilder[AggregatedCompilerProductionRow]
      val ordered         = grouped.toVector.sortBy((key, _) => (key._1.ordinal, key._2, key._3))
      ordered.foreach:
        case ((kind, prefix, _), observations) =>
          val signatures = observations.map(_.observation.map(_.name)).distinct
          if signatures.size != 1 then
            break(
              Left(
                InventoryAggregationFailure.FieldSignatureConflict(kind, prefix, signatures.head, signatures.tail.head)
              )
            )
          val signature  = signatures.head
          val fieldRows  = observations.map(_.observation)
          val fields     = Vector.newBuilder[CompilerFieldPattern]
          signature.zipWithIndex.foreach: (name, index) =>
            val observedFields = fieldRows
              .map(_(index))
              .map(field => withEmptyDeclarations(field, declaredByField.get((kind, prefix, index))))
            infer(
              observedFields,
              Vector(CatalogPathSegment.NamedField(name))
            ) match
              case Left(failure) => break(Left(failure))
              case Right(value)  => fields += CompilerFieldPattern(name, value)
          rows += AggregatedCompilerProductionRow(
            kind,
            prefix,
            fields.result(),
            canonicalDistinct(
              observations.flatMap: row =>
                val contexts = if row.contexts.isEmpty then Vector(None) else row.contexts.map(Some(_))
                contexts.map(
                  CompilerProductionContext(
                    _,
                    row.sourceClassification,
                    row.scannerTokenKinds,
                    row.directNodeEvidence,
                    row.rootAttachments
                  )
                )
            )(writeProductionContext)
          )
      Right(
        AggregatedCompilerProductionInventory(
          identity,
          inventories.map(_.parserEvidenceFingerprint).distinct.sorted,
          rows.result(),
          canonicalDistinct(inventories)(writeScenario)
        )
      )

  private def infer(
      fields: Vector[InventoryFieldObservation],
      path: Vector[CatalogPathSegment]
  ): Either[InventoryAggregationFailure, CatalogValuePattern] =
    val observations                                                                                    = fields.map(_.value)
    val declarations                                                                                    = fields.flatMap(_.declaredPattern).distinct
    def validate(result: CatalogValuePattern): Either[InventoryAggregationFailure, CatalogValuePattern] =
      if declarations.forall(CatalogShapeMatcher.covers(_, result))
      then Right(result)
      else incompatible(path)
    observations.headOption match
      case None                                                     => Left(InventoryAggregationFailure.UnresolvedShape(path))
      case Some(_: InventoryValueObservation.Optional)              =>
        if !observations.forall(_.isInstanceOf[InventoryValueObservation.Optional]) then incompatible(path)
        else
          val concrete = fields.flatMap: field =>
            field.value match
              case InventoryValueObservation.Optional(Some(value)) =>
                Vector(
                  InventoryFieldObservation(
                    field.name,
                    value,
                    field.declaredPattern.collect { case CatalogValuePattern.Optional(inner) =>
                      inner
                    }
                  )
                )
              case _                                               => Vector.empty
          if concrete.nonEmpty then
            infer(concrete, path :+ CatalogPathSegment.Optional)
              .map(CatalogValuePattern.Optional.apply)
              .flatMap(validate)
          else if fields.exists(_.declaredPattern.isEmpty) then Left(InventoryAggregationFailure.UnresolvedShape(path))
          else
            declarations match
              case Vector(CatalogValuePattern.Optional(value)) => validate(CatalogValuePattern.EmptyOptional(value))
              case Vector()                                    => Left(InventoryAggregationFailure.UnresolvedShape(path))
              case _                                           => incompatible(path)
      case Some(_: InventoryValueObservation.Repeated)              =>
        if !observations.forall(_.isInstanceOf[InventoryValueObservation.Repeated]) then incompatible(path)
        else
          val concrete = fields.flatMap: field =>
            field.value match
              case InventoryValueObservation.Repeated(values) =>
                values.map(value =>
                  InventoryFieldObservation(
                    field.name,
                    value,
                    field.declaredPattern.collect { case CatalogValuePattern.Repeated(inner) =>
                      inner
                    }
                  )
                )
              case _                                          => Vector.empty
          if concrete.nonEmpty then
            infer(concrete, path :+ CatalogPathSegment.RepeatedElement)
              .map(CatalogValuePattern.Repeated.apply)
              .flatMap(validate)
          else if fields.exists(_.declaredPattern.isEmpty) then Left(InventoryAggregationFailure.UnresolvedShape(path))
          else
            declarations match
              case Vector(CatalogValuePattern.Repeated(element)) =>
                validate(CatalogValuePattern.EmptyRepeated(element))
              case Vector()                                      => Left(InventoryAggregationFailure.UnresolvedShape(path))
              case _                                             => incompatible(path)
      case Some(InventoryValueObservation.Product(prefix, _))       =>
        boundary:
          val products = observations.collect { case value: InventoryValueObservation.Product => value }
          if products.size != observations.size || products.exists(_.prefix != prefix) then incompatible(path)
          else
            val signatures = products.map(_.fields.map(_.name)).distinct
            if signatures.size != 1 then incompatible(path :+ CatalogPathSegment.NestedProduct(prefix))
            else
              val nestedPath = path :+ CatalogPathSegment.NestedProduct(prefix)
              val fields     = Vector.newBuilder[CompilerFieldPattern]
              signatures.head.zipWithIndex.foreach: (name, index) =>
                infer(products.map(_.fields(index)), nestedPath :+ CatalogPathSegment.NamedField(name)) match
                  case Left(failure) => break(Left(failure))
                  case Right(value)  => fields += CompilerFieldPattern(name, value)
              validate(CatalogValuePattern.Product(prefix, fields.result()))
      case Some(_: InventoryValueObservation.Node)                  =>
        sameCategory(observations, path)(_.isInstanceOf[InventoryValueObservation.Node], CatalogValuePattern.Node)
          .flatMap(validate)
      case Some(_: InventoryValueObservation.Positioned)            =>
        sameCategory(observations, path)(
          _.isInstanceOf[InventoryValueObservation.Positioned],
          CatalogValuePattern.Positioned
        ).flatMap(validate)
      case Some(
            _: InventoryValueObservation.Name | _: InventoryValueObservation.BacktickedName |
            _: InventoryValueObservation.GeneratedName
          ) =>
        val names           = observations.collect { case InventoryValueObservation.Name(value) => value }
        val backtickedNames = observations.collect { case InventoryValueObservation.BacktickedName(value) => value }
        val classes         = observations.collect {
          case InventoryValueObservation.Name(value)           => NeutralNameClass.classify(value)
          case InventoryValueObservation.BacktickedName(value) => NeutralNameClass.classify(value)
        }
        if observations.forall(value =>
            value.isInstanceOf[InventoryValueObservation.Name] ||
              value.isInstanceOf[InventoryValueObservation.BacktickedName] ||
              value.isInstanceOf[InventoryValueObservation.GeneratedName]
          )
        then
          val result =
            if backtickedNames.size == observations.size then CatalogValuePattern.BacktickedName
            else if names.size == observations.size && names.forall(CatalogValuePattern.isLowercaseName) then
              CatalogValuePattern.LowercaseName
            else if names.size == observations.size && names.forall(CatalogValuePattern.isNonLowercaseName) then
              CatalogValuePattern.NonLowercaseName
            else if classes.size == observations.size && classes.distinct.size == 1 then
              CatalogValuePattern.ClassifiedName(classes.head)
            else if observations.forall(_.isInstanceOf[InventoryValueObservation.GeneratedName]) then
              CatalogValuePattern.GeneratedName
            else CatalogValuePattern.Name
          validate(result)
        else incompatible(path)
      case Some(InventoryValueObservation.Scalar(value))            =>
        val kind     = value.productPrefix
        val rendered = observations.collect { case InventoryValueObservation.Scalar(candidate) => candidate.toString }
        if rendered.size == observations.size && observations.forall {
            case InventoryValueObservation.Scalar(candidate) => candidate.productPrefix == kind
            case _                                           => false
          }
        then
          val pattern =
            if rendered.distinct.size == 1 then CatalogValuePattern.ExactScalar(kind, rendered.head)
            else CatalogValuePattern.Scalar(kind)
          validate(pattern)
        else incompatible(path)
      case Some(InventoryValueObservation.Unsupported(runtimeType)) =>
        if observations.forall {
            case InventoryValueObservation.Unsupported(candidate) => candidate == runtimeType
            case _                                                => false
          }
        then validate(CatalogValuePattern.Unsupported(runtimeType))
        else incompatible(path)

  private def sameCategory(
      observations: Vector[InventoryValueObservation],
      path: Vector[CatalogPathSegment]
  )(matches: InventoryValueObservation => Boolean, result: CatalogValuePattern) =
    if observations.forall(matches) then Right(result) else incompatible(path)

  private def incompatible(path: Vector[CatalogPathSegment]) =
    Left(InventoryAggregationFailure.IncompatibleShape(path))

  private def canonicalDistinct[A](values: Vector[A])(write: (A, CanonicalByteEncoder) => Unit): Vector[A] =
    values
      .groupBy(value => canonicalKey(value)(write))
      .toVector
      .sortBy(_._1)
      .map(_._2.head)

  private def canonicalKey[A](value: A)(write: (A, CanonicalByteEncoder) => Unit): String =
    val e = CanonicalByteEncoder()
    write(value, e)
    java.util.Base64.getEncoder.encodeToString(e.result())

  private def writeIdentity(identity: CompilerRuntimeIdentity, e: CanonicalByteEncoder): Unit =
    e.string(identity.coordinate.organization)
    e.string(identity.coordinate.artifact)
    e.string(identity.coordinate.version)
    e.sequence(identity.artifacts): artifact =>
      e.int(artifact.ordinal); e.string(artifact.fileName); e.long(artifact.byteSize); e.string(artifact.sha256)
    e.sequence(identity.compilerOptions)(e.string)

  private def writeRow(row: AggregatedCompilerProductionRow, e: CanonicalByteEncoder): Unit =
    e.tag(row.kind.ordinal); e.string(row.prefix); e.sequence(row.fields)(writeField(_, e))
    e.sequence(row.occurrences)(writeProductionContext(_, e))

  private def writeScenario(scenario: CompilerRuntimeInventory, e: CanonicalByteEncoder): Unit =
    writeIdentity(scenario.identity, e)
    e.string(scenario.parserEvidenceFingerprint)
    e.sequence(scenario.shapes.sortBy(row => (row.kind.ordinal, row.id))): row =>
      e.tag(row.kind.ordinal); e.long(row.id); e.string(row.prefix)
      e.sequence(row.patternFields)(writeField(_, e))
      e.sequence(row.observation): field =>
        e.string(field.name); writeObservation(field.value, e)
      e.sequence(row.contexts)(writeContext(_, e)); e.tag(row.sourceClassification.ordinal)
      e.sequence(row.scannerTokenKinds)(kind => e.tag(kind.ordinal))
      writeDirectNodeEvidence(row.directNodeEvidence, e)
      if row.rootAttachments.nonEmpty then writeAttachmentEvidence(row.rootAttachments, e)

  private def writeObservation(value: InventoryValueObservation, e: CanonicalByteEncoder): Unit = value match
    case InventoryValueObservation.Node(id, prefix)                      => e.tag(1); e.long(id); e.string(prefix)
    case InventoryValueObservation.Positioned(id, prefix)                => e.tag(2); e.long(id); e.string(prefix)
    case InventoryValueObservation.Optional(value)                       =>
      e.tag(3); value.fold(e.tag(0))(candidate => { e.tag(1); writeObservation(candidate, e) })
    case InventoryValueObservation.Repeated(values)                      => e.tag(4); e.sequence(values)(writeObservation(_, e))
    case InventoryValueObservation.Product(prefix, fields)               =>
      e.tag(5); e.string(prefix);
      e.sequence(fields)(field => { e.string(field.name); writeObservation(field.value, e) })
    case InventoryValueObservation.Name(value)                           => e.tag(6); e.string(value)
    case InventoryValueObservation.BacktickedName(value)                 => e.tag(10); e.string(value)
    case InventoryValueObservation.GeneratedName(base, separator, index) =>
      e.tag(7); e.string(base); e.string(separator); e.int(index)
    case InventoryValueObservation.Scalar(value)                         => e.tag(8); e.string(value.toString)
    case InventoryValueObservation.Unsupported(value)                    => e.tag(9); e.string(value)

  private def writeObservedPartition(
      value: InventoryValueObservation,
      declared: Option[CatalogValuePattern],
      e: CanonicalByteEncoder
  ): Unit = value match
    case InventoryValueObservation.Optional(None)          =>
      e.tag(10)
      declared.collect { case CatalogValuePattern.Optional(inner) => inner }.fold(e.tag(0))(writePattern(_, e))
    case InventoryValueObservation.Optional(Some(inner))   =>
      e.tag(11)
      writeObservedPartition(inner, declared.collect { case CatalogValuePattern.Optional(value) => value }, e)
    case InventoryValueObservation.Repeated(Vector())      =>
      e.tag(12)
      declared.collect { case CatalogValuePattern.Repeated(inner) => inner }.fold(e.tag(0))(writePattern(_, e))
    case InventoryValueObservation.Repeated(values)        =>
      e.tag(13)
      val inner = declared.collect { case CatalogValuePattern.Repeated(value) => value }
      e.sequence(values)(writeObservedPartition(_, inner, e))
    case InventoryValueObservation.Product(prefix, fields) =>
      e.tag(14); e.string(prefix)
      e.sequence(fields): field =>
        e.string(field.name)
        writeObservedPartition(field.value, field.declaredPattern, e)
    case InventoryValueObservation.Name(name)              => e.tag(15); e.tag(NeutralNameClass.classify(name).ordinal)
    case InventoryValueObservation.BacktickedName(name)    =>
      e.tag(17); e.tag(NeutralNameClass.classify(name).ordinal)
    case InventoryValueObservation.GeneratedName(_, _, _)  => e.tag(16)
    case other                                             => writeObservation(other, e)

  private def writeProductionContext(context: CompilerProductionContext, e: CanonicalByteEncoder): Unit =
    writeOptionalContext(context.context, e); e.tag(context.sourceClassification.ordinal)
    e.sequence(context.scannerTokenKinds)(kind => e.tag(kind.ordinal))
    writeDirectNodeEvidence(context.directNodeEvidence, e)
    if context.rootAttachments.nonEmpty then writeAttachmentEvidence(context.rootAttachments, e)

  private def writeDirectNodeEvidence(
      evidence: Vector[DirectNodeFieldEvidence],
      e: CanonicalByteEncoder
  ): Unit =
    e.sequence(evidence.sortBy(_.fieldName)): value =>
      e.string(value.fieldName); e.tag(value.sourceClassification.ordinal)
      value.hasSourceWidth.fold(e.tag(0))(width => { e.tag(1); e.boolean(width) })
      e.sequence(value.requiredAttachmentKinds.toVector.sorted)(e.string)

  private def writeAttachmentEvidence(
      attachments: Vector[AttachmentEvidence],
      e: CanonicalByteEncoder
  ): Unit =
    e.tag(18)
    e.sequence(attachments): attachment =>
      e.string(attachment.keyKind)
      attachment.value match
        case ParserAttachmentValue.Product(production) => e.tag(1); e.string(production)
        case ParserAttachmentValue.Name(value)         => e.tag(2); e.string(value)
        case ParserAttachmentValue.Scalar(value)       => e.tag(3); writeAttachmentScalar(value, e)
        case ParserAttachmentValue.RuntimeKind(kind)   => e.tag(4); e.string(kind)

  private def writeAttachmentScalar(value: ParserScalar, e: CanonicalByteEncoder): Unit = value match
    case ParserScalar.Text(value)         => e.tag(1); e.string(value)
    case ParserScalar.Integer(value)      => e.tag(2); e.int(value)
    case ParserScalar.LongInteger(value)  => e.tag(3); e.long(value)
    case ParserScalar.Decimal(value)      => e.tag(4); e.double(value)
    case ParserScalar.FloatDecimal(value) => e.tag(5); e.int(java.lang.Float.floatToRawIntBits(value))
    case ParserScalar.Logical(value)      => e.tag(6); e.boolean(value)
    case ParserScalar.Character(value)    => e.tag(7); e.char(value)
    case ParserScalar.UnitValue           => e.tag(8)
    case ParserScalar.NullValue           => e.tag(9)

  private def writeField(field: CompilerFieldPattern, e: CanonicalByteEncoder): Unit =
    e.string(field.name); writePattern(field.value, e)

  private def writePattern(value: CatalogValuePattern, e: CanonicalByteEncoder): Unit = value match
    case CatalogValuePattern.Node                                          => e.tag(1)
    case CatalogValuePattern.NodePrefix(prefix)                            => e.tag(16); e.string(prefix)
    case CatalogValuePattern.NodeExceptPrefix(prefix)                      => e.tag(17); e.string(prefix)
    case CatalogValuePattern.Positioned                                    => e.tag(2)
    case CatalogValuePattern.Optional(inner)                               => e.tag(3); writePattern(inner, e)
    case CatalogValuePattern.EmptyOptional(inner)                          => e.tag(12); writePattern(inner, e)
    case CatalogValuePattern.Repeated(inner)                               => e.tag(4); writePattern(inner, e)
    case CatalogValuePattern.NonEmptyRepeated(inner)                       => e.tag(15); writePattern(inner, e)
    case CatalogValuePattern.EmptyRepeated(inner)                          => e.tag(10); writePattern(inner, e)
    case CatalogValuePattern.LeadingThenRepeated(leading, trailing)        =>
      e.tag(14); writePattern(leading, e); writePattern(trailing, e)
    case CatalogValuePattern.NonEmptyRepeatedEndingWith(leading, trailing) =>
      e.tag(22); writePattern(leading, e); writePattern(trailing, e)
    case CatalogValuePattern.AnyOf(values)                                 => e.tag(18); e.sequence(values)(writePattern(_, e))
    case CatalogValuePattern.Product(prefix, fields)                       => e.tag(5); e.string(prefix); e.sequence(fields)(writeField(_, e))
    case CatalogValuePattern.Name                                          => e.tag(6)
    case CatalogValuePattern.GeneratedName                                 => e.tag(7)
    case CatalogValuePattern.ClassifiedName(value)                         => e.tag(11); e.string(value.toString)
    case CatalogValuePattern.LowercaseName                                 => e.tag(19)
    case CatalogValuePattern.NonLowercaseName                              => e.tag(20)
    case CatalogValuePattern.BacktickedName                                => e.tag(21)
    case CatalogValuePattern.ExactName(value)                              => e.tag(24); e.string(value)
    case CatalogValuePattern.Scalar(kind)                                  => e.tag(8); e.string(kind)
    case CatalogValuePattern.ExactScalar(kind, value)                      => e.tag(13); e.string(kind); e.string(value)
    case CatalogValuePattern.Unsupported(runtime)                          => e.tag(9); e.string(runtime)

  private def writeContext(context: InventoryContext, e: CanonicalByteEncoder): Unit =
    e.tag(context.ownerKind.ordinal); e.string(context.ownerPrefix); e.sequence(context.path)(writePath(_, e))
    e.sequence(context.ancestors): ancestor =>
      e.tag(ancestor.ownerKind.ordinal); e.string(ancestor.ownerPrefix); e.sequence(ancestor.path)(writePath(_, e))
    e.sequence(context.ownerNodePrefixes.toVector.sorted): (name, prefix) =>
      e.string(name); e.string(prefix)
    e.sequence(context.ancestorEvidence): evidence =>
      e.sequence(evidence.scannerTokenKinds)(kind => e.tag(kind.ordinal))
      writeDirectNodeEvidence(evidence.directNodeEvidence, e)

  private def writeOptionalContext(context: Option[InventoryContext], e: CanonicalByteEncoder): Unit = context match
    case None        => e.tag(0)
    case Some(value) => e.tag(1); writeContext(value, e)

  private def writePath(segment: CatalogPathSegment, e: CanonicalByteEncoder): Unit = segment match
    case CatalogPathSegment.NamedField(name)       => e.tag(1); e.string(name)
    case CatalogPathSegment.Optional               => e.tag(2)
    case CatalogPathSegment.RepeatedElement        => e.tag(3)
    case CatalogPathSegment.NestedProduct(product) => e.tag(4); e.string(product)
private[metallurgy] enum InventoryFailure:
  case DuplicateIdentity(kind: InventoryKind, id: Long)
  case MissingReference(
      kind: InventoryKind,
      id: Long,
      ownerKind: InventoryKind,
      ownerId: Long,
      path: Vector[ParserFieldPathSegment]
  )
  case UnknownOccurrenceOwner(kind: InventoryKind, id: Long, ownerNodeId: Long, path: Vector[ParserFieldPathSegment])
  case MissingOccurrence(
      kind: InventoryKind,
      id: Long,
      ownerKind: InventoryKind,
      ownerId: Long,
      path: Vector[ParserFieldPathSegment]
  )
  case ExtraOccurrence(kind: InventoryKind, id: Long, ownerNodeId: Long, path: Vector[ParserFieldPathSegment])
  case UnreachableValue(kind: InventoryKind, id: Long)
  case InvalidRoot(rootNodeId: Long)
  case GraphCycle(kind: InventoryKind, id: Long, path: Vector[ParserFieldPathSegment])

private[metallurgy] object CompilerRuntimeInventory:
  def from(snapshot: ParserSyntaxSnapshot): Either[Vector[InventoryFailure], CompilerRuntimeInventory] =
    val failures                                                                                                   = Vector.newBuilder[InventoryFailure]
    duplicate(snapshot.nodes.map(_.id)).foreach(id =>
      failures += InventoryFailure.DuplicateIdentity(InventoryKind.Node, id)
    )
    duplicate(snapshot.positioned.map(_.id)).foreach(id =>
      failures += InventoryFailure.DuplicateIdentity(InventoryKind.Positioned, id)
    )
    val nodes                                                                                                      = snapshot.nodes.groupBy(_.id).collect { case (id, Vector(node)) => id -> node }
    val positioned                                                                                                 = snapshot.positioned.groupBy(_.id).collect { case (id, Vector(value)) => id -> value }
    val productIds                                                                                                 = collection.mutable.Map
      .empty[(InventoryKind, Long, Vector[ParserFieldPathSegment]), Long]
    val productRows                                                                                                = Vector.newBuilder[ParserProductSyntax]
    val productPending                                                                                             = collection.mutable.Stack.empty[
      (InventoryKind, Long, Vector[ParserNodeOccurrence], ParserFieldValue, Vector[ParserFieldPathSegment])
    ]
    snapshot.positioned.reverseIterator.foreach: value =>
      value.fields.reverseIterator.foreach: field =>
        productPending.push(
          (
            InventoryKind.Positioned,
            value.id,
            value.occurrences.map(occurrence => ParserNodeOccurrence(occurrence.ownerNodeId, occurrence.fieldPath)),
            field.value,
            Vector(ParserFieldPathSegment.NamedField(field.name))
          )
        )
    snapshot.nodes.reverseIterator.foreach: node =>
      node.fields.reverseIterator.foreach: field =>
        productPending.push(
          (
            InventoryKind.Node,
            node.id,
            Vector(ParserNodeOccurrence(node.id, Vector.empty)),
            field.value,
            Vector(ParserFieldPathSegment.NamedField(field.name))
          )
        )
    var nextProductId                                                                                              = 0L
    while productPending.nonEmpty do
      val (ownerKind, ownerId, ownerOccurrences, value, path) = productPending.pop()
      value match
        case ParserFieldValue.Optional(candidate)     =>
          candidate.foreach(value =>
            productPending.push(
              (ownerKind, ownerId, ownerOccurrences, value, path :+ ParserFieldPathSegment.OptionalNesting)
            )
          )
        case ParserFieldValue.Repeated(candidates)    =>
          candidates.zipWithIndex.reverseIterator.foreach: (candidate, index) =>
            productPending.push(
              (
                ownerKind,
                ownerId,
                ownerOccurrences,
                candidate,
                path :+ ParserFieldPathSegment.RepeatedIndex(index)
              )
            )
        case ParserFieldValue.Product(prefix, fields) =>
          val id = nextProductId
          nextProductId += 1
          productIds += ((ownerKind, ownerId, path)) -> id
          val occurrences = ownerOccurrences.map(occurrence =>
            ParserNodeOccurrence(occurrence.ownerNodeId, occurrence.fieldPath ++ path)
          )
          val values      = collection.mutable.Stack.from(fields.reverseIterator.map(_.value))
          val positions   = Vector.newBuilder[ParserNodePosition.Positioned]
          while values.nonEmpty do
            values.pop() match
              case ParserFieldValue.Node(nodeId)             =>
                nodes
                  .get(nodeId)
                  .foreach(_.position match
                    case value: ParserNodePosition.Positioned => positions += value
                    case ParserNodePosition.Absent            => ()
                  )
              case ParserFieldValue.Positioned(positionedId) =>
                positioned
                  .get(positionedId)
                  .foreach(_.position match
                    case value: ParserNodePosition.Positioned => positions += value
                    case ParserNodePosition.Absent            => ()
                  )
              case ParserFieldValue.Optional(candidate)      => candidate.foreach(values.push)
              case ParserFieldValue.Repeated(candidates)     => candidates.reverseIterator.foreach(values.push)
              case ParserFieldValue.Product(_, nested)       =>
                nested.reverseIterator.foreach(field => values.push(field.value))
              case _                                         => ()
          val observed    = positions.result()
          val position    =
            if observed.isEmpty then ParserNodePosition.Absent
            else
              val range      = PcSourceRange(
                observed.map(_.range.startOffset).min,
                observed.map(_.range.endOffset).max
              )
              val provenance =
                if observed.exists(_.provenance == ParserPositionProvenance.SourceDerived) then
                  ParserPositionProvenance.SourceDerived
                else ParserPositionProvenance.Synthetic
              ParserNodePosition.Positioned(range, range.startOffset, provenance)
          productRows += ParserProductSyntax(id, prefix, fields, position, occurrences)
          fields.reverseIterator.foreach: field =>
            productPending.push(
              (
                InventoryKind.Product,
                id,
                occurrences,
                field.value,
                Vector(
                  ParserFieldPathSegment.NestedProductBoundary(prefix),
                  ParserFieldPathSegment.NamedField(field.name)
                )
              )
            )
        case _                                        => ()
    val products                                                                                                   = productRows.result()
    def nodeEvidence(node: ParserSyntaxNode): InventoryAncestorEvidence                                            =
      val scannerTokenKinds = node.position match
        case ParserNodePosition.Positioned(range, _, _) =>
          snapshot.scannerTokens
            .filter(token => range.startOffset <= token.range.startOffset && token.range.endOffset <= range.endOffset)
            .map(_.kind)
        case _                                          => Vector.empty
      val directEvidence    = node.fields.flatMap: field =>
        field.value match
          case ParserFieldValue.Node(childId) =>
            nodes
              .get(childId)
              .map: child =>
                val classification = child.position match
                  case ParserNodePosition.Absent                                                   => SourceClassification.Absent
                  case ParserNodePosition.Positioned(_, _, ParserPositionProvenance.SourceDerived) =>
                    SourceClassification.SourceReachable
                  case _                                                                           => SourceClassification.Synthetic
                val width          = child.position match
                  case ParserNodePosition.Positioned(range, _, _) => Some(range.startOffset < range.endOffset)
                  case ParserNodePosition.Absent                  => None
                val attachments    = snapshot.attachments
                  .filter(_.ownerNodeId == childId)
                  .map(_.keyKind)
                  .toSet
                DirectNodeFieldEvidence(field.name, classification, width, attachments)
          case _                              => None
      InventoryAncestorEvidence(scannerTokenKinds, directEvidence)
    val ancestorEvidence                                                                                           = snapshot.nodes.map(node => node.id -> nodeEvidence(node)).toMap
    val lineages                                                                                                   = InventoryContextLineage.resolver(nodes, ancestorEvidence)
    if !nodes.contains(snapshot.rootNodeId) then failures += InventoryFailure.InvalidRoot(snapshot.rootNodeId)
    def references(
        value: ParserFieldValue,
        path: Vector[ParserFieldPathSegment],
        ownerKind: InventoryKind,
        ownerId: Long
    ): Vector[(InventoryKind, Long, Vector[ParserFieldPathSegment])] =
      value match
        case ParserFieldValue.Node(id)         => Vector((InventoryKind.Node, id, path))
        case ParserFieldValue.Positioned(id)   => Vector((InventoryKind.Positioned, id, path))
        case ParserFieldValue.Optional(value)  =>
          value.toVector.flatMap(
            references(_, path :+ ParserFieldPathSegment.OptionalNesting, ownerKind, ownerId)
          )
        case ParserFieldValue.Repeated(values) =>
          values.zipWithIndex.flatMap((v, i) =>
            references(v, path :+ ParserFieldPathSegment.RepeatedIndex(i), ownerKind, ownerId)
          )
        case _: ParserFieldValue.Product       =>
          productIds
            .get((ownerKind, ownerId, path))
            .toVector
            .map(id => (InventoryKind.Product, id, path))
        case _                                 => Vector.empty
    def outgoing(kind: InventoryKind, id: Long, fields: Vector[ParserSyntaxField], product: Option[String] = None) =
      fields.flatMap(field =>
        references(
          field.value,
          product.toVector.map(ParserFieldPathSegment.NestedProductBoundary.apply) :+
            ParserFieldPathSegment.NamedField(field.name),
          kind,
          id
        )
      )
    val graph                                                                                                      = snapshot.nodes.map(node =>
      (InventoryKind.Node, node.id) -> outgoing(InventoryKind.Node, node.id, node.fields)
    ) ++ snapshot.positioned.map(value =>
      (InventoryKind.Positioned, value.id) -> outgoing(InventoryKind.Positioned, value.id, value.fields)
    ) ++ products.map(value =>
      (InventoryKind.Product, value.id) -> outgoing(
        InventoryKind.Product,
        value.id,
        value.fields,
        Some(value.production)
      )
    )
    val edges                                                                                                      = graph.toMap
    val expectedOccurrences                                                                                        = snapshot.nodes.flatMap(node =>
      outgoing(InventoryKind.Node, node.id, node.fields).map { case (kind, id, path) =>
        (kind, id, InventoryKind.Node, node.id, path)
      }
    ) ++ snapshot.positioned.flatMap(value =>
      value.occurrences.headOption.toVector.flatMap(origin =>
        outgoing(InventoryKind.Positioned, value.id, value.fields).map { case (kind, id, path) =>
          (kind, id, InventoryKind.Node, origin.ownerNodeId, origin.fieldPath ++ path)
        }
      )
    ) ++ products.flatMap(value =>
      value.occurrences.headOption.toVector.flatMap(origin =>
        outgoing(InventoryKind.Product, value.id, value.fields, Some(value.production)).map { case (kind, id, path) =>
          (kind, id, InventoryKind.Node, origin.ownerNodeId, origin.fieldPath ++ path)
        }
      )
    )
    val actualOccurrences                                                                                          = snapshot.nodes.flatMap(n =>
      n.occurrences.map(o => (InventoryKind.Node, n.id, InventoryKind.Node, o.ownerNodeId, o.fieldPath))
    ) ++ snapshot.positioned.flatMap(n =>
      n.occurrences.map(o => (InventoryKind.Positioned, n.id, InventoryKind.Node, o.ownerNodeId, o.fieldPath))
    ) ++ products.flatMap(n =>
      n.occurrences.map(o => (InventoryKind.Product, n.id, InventoryKind.Node, o.ownerNodeId, o.fieldPath))
    )
    def counts[A](values: Vector[A]): Map[A, Int]                                                                  = values.groupMapReduce(identity)(_ => 1)(_ + _)
    val expectedCounts                                                                                             = counts(expectedOccurrences.toVector)
    val actualCounts                                                                                               = counts(actualOccurrences)
    expectedCounts.foreach: (occurrence, count) =>
      val (kind, id, ownerKind, ownerId, path) = occurrence
      (0 until (count - actualCounts.getOrElse(occurrence, 0))).foreach(_ =>
        failures += InventoryFailure.MissingOccurrence(kind, id, ownerKind, ownerId, path)
      )
    actualCounts.foreach: (occurrence, count) =>
      val (kind, id, _, ownerId, path) = occurrence
      (0 until (count - expectedCounts.getOrElse(occurrence, 0))).foreach(_ =>
        failures += InventoryFailure.ExtraOccurrence(kind, id, ownerId, path)
      )
    val reachable                                                                                                  = scala.collection.mutable.Set.empty[(InventoryKind, Long)]
    val pending                                                                                                    = scala.collection.mutable.Stack[(InventoryKind, Long)](InventoryKind.Node -> snapshot.rootNodeId)
    while pending.nonEmpty do
      val current = pending.pop()
      if !reachable(current) then
        reachable += current
        edges.getOrElse(current, Vector.empty).foreach((kind, id, _) => pending.push(kind -> id))
    edges.keys.filterNot(reachable).foreach((kind, id) => failures += InventoryFailure.UnreachableValue(kind, id))
    val visitState                                                                                                 = scala.collection.mutable.Map.empty[(InventoryKind, Long), Int]
    val traversal                                                                                                  = scala.collection.mutable.Stack.empty[((InventoryKind, Long), Boolean)]
    traversal.push((InventoryKind.Node -> snapshot.rootNodeId) -> false)
    while traversal.nonEmpty do
      val (current, exiting) = traversal.pop()
      if exiting then visitState.update(current, 2)
      else if visitState.getOrElse(current, 0) == 0 then
        visitState.update(current, 1)
        traversal.push(current -> true)
        edges
          .getOrElse(current, Vector.empty)
          .reverseIterator
          .foreach: (kind, id, path) =>
            val next = kind -> id
            if visitState.getOrElse(next, 0) == 1 then failures += InventoryFailure.GraphCycle(kind, id, path)
            else if visitState.getOrElse(next, 0) == 0 then traversal.push(next -> false)
    def contexts(
        kind: InventoryKind,
        id: Long,
        occurrence: (Long, Vector[ParserFieldPathSegment])
    ): Vector[InventoryContext] =
      nodes.get(occurrence._1) match
        case Some(owner) =>
          lineages.contexts(owner, occurrence._2)
        case None        =>
          failures += InventoryFailure.UnknownOccurrenceOwner(kind, id, occurrence._1, occurrence._2)
          Vector.empty
    def observe(
        value: ParserFieldValue,
        ownerKind: InventoryKind,
        ownerId: Long,
        path: Vector[ParserFieldPathSegment]
    ): InventoryValueObservation = value match
      case ParserFieldValue.Node(id)                =>
        val prefix = nodes.get(id).map(_.production).getOrElse {
          failures += InventoryFailure.MissingReference(InventoryKind.Node, id, ownerKind, ownerId, path); ""
        }
        InventoryValueObservation.Node(id, prefix)
      case ParserFieldValue.Positioned(id)          =>
        val prefix = positioned.get(id).map(_.production).getOrElse {
          failures += InventoryFailure.MissingReference(InventoryKind.Positioned, id, ownerKind, ownerId, path); ""
        }
        InventoryValueObservation.Positioned(id, prefix)
      case ParserFieldValue.Optional(value)         =>
        InventoryValueObservation.Optional(
          value.map(observe(_, ownerKind, ownerId, path :+ ParserFieldPathSegment.OptionalNesting))
        )
      case ParserFieldValue.Repeated(values)        =>
        InventoryValueObservation.Repeated(
          values.zipWithIndex.map((v, i) =>
            observe(v, ownerKind, ownerId, path :+ ParserFieldPathSegment.RepeatedIndex(i))
          )
        )
      case ParserFieldValue.Product(prefix, fields) =>
        InventoryValueObservation.Product(
          prefix,
          fields.map(f =>
            InventoryFieldObservation(
              f.name,
              observe(
                f.value,
                ownerKind,
                ownerId,
                path :+ ParserFieldPathSegment.NestedProductBoundary(prefix) :+ ParserFieldPathSegment.NamedField(
                  f.name
                )
              ),
              f.declaredShape.map(declaredPattern)
            )
          )
        )
      case ParserFieldValue.Name(value)             =>
        val backticked = path == Vector(ParserFieldPathSegment.NamedField("name")) && nodes
          .get(ownerId)
          .filter(_.production == "Ident")
          .flatMap(_.position match
            case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived) =>
              Some(snapshot.sourceText.substring(range.startOffset, range.endOffset))
            case _                                                                               => None
          )
          .exists(text => text.length >= 2 && text.head == '`' && text.last == '`')
        if backticked then InventoryValueObservation.BacktickedName(value)
        else InventoryValueObservation.Name(value)
      case ParserFieldValue.GeneratedName(a, b, c)  => InventoryValueObservation.GeneratedName(a, b, c)
      case ParserFieldValue.Scalar(value)           => InventoryValueObservation.Scalar(value)
      case ParserFieldValue.Unsupported(value)      => InventoryValueObservation.Unsupported(value)
    def declaredPattern(shape: ParserDeclaredShape): CatalogValuePattern                                           = shape match
      case ParserDeclaredShape.Node            => CatalogValuePattern.Node
      case ParserDeclaredShape.Positioned      => CatalogValuePattern.Positioned
      case ParserDeclaredShape.Optional(inner) => CatalogValuePattern.Optional(declaredPattern(inner))
      case ParserDeclaredShape.Repeated(inner) => CatalogValuePattern.Repeated(declaredPattern(inner))
      case ParserDeclaredShape.Name            => CatalogValuePattern.Name
      case ParserDeclaredShape.Scalar(kind)    => CatalogValuePattern.Scalar(kind)
    def pattern(value: InventoryValueObservation): CatalogValuePattern                                             = value match
      case InventoryValueObservation.Node(_, _)              => CatalogValuePattern.Node
      case InventoryValueObservation.Positioned(_, _)        => CatalogValuePattern.Positioned
      case InventoryValueObservation.Optional(Some(value))   => CatalogValuePattern.Optional(pattern(value))
      case InventoryValueObservation.Optional(None)          =>
        CatalogValuePattern.Optional(CatalogValuePattern.Unsupported("unobserved"))
      case InventoryValueObservation.Repeated(values)        =>
        CatalogValuePattern.Repeated(
          values.headOption.map(pattern).getOrElse(CatalogValuePattern.Unsupported("unobserved"))
        )
      case InventoryValueObservation.Product(prefix, fields) =>
        CatalogValuePattern.Product(prefix, fields.map(f => CompilerFieldPattern(f.name, pattern(f.value))))
      case InventoryValueObservation.Name(value)             => CatalogValuePattern.ClassifiedName(NeutralNameClass.classify(value))
      case InventoryValueObservation.BacktickedName(_)       => CatalogValuePattern.BacktickedName
      case InventoryValueObservation.GeneratedName(_, _, _)  => CatalogValuePattern.GeneratedName
      case InventoryValueObservation.Scalar(value)           => CatalogValuePattern.Scalar(value.productPrefix)
      case InventoryValueObservation.Unsupported(value)      => CatalogValuePattern.Unsupported(value)
    val rows                                                                                                       = (snapshot.nodes.map(n =>
      (
        InventoryKind.Node,
        n.id,
        n.production,
        n.fields,
        n.position,
        n.occurrences.map(o => o.ownerNodeId -> o.fieldPath)
      )
    ) ++
      snapshot.positioned.map(n =>
        (
          InventoryKind.Positioned,
          n.id,
          n.production,
          n.fields,
          n.position,
          n.occurrences.map(o => o.ownerNodeId -> o.fieldPath)
        )
      ) ++
      products.map(n =>
        (
          InventoryKind.Product,
          n.id,
          n.production,
          n.fields,
          n.position,
          n.occurrences.map(o => o.ownerNodeId -> o.fieldPath)
        )
      )).map: (kind, id, prefix, fields, position, occurrences) =>
      val observed           = fields.map(f =>
        InventoryFieldObservation(
          f.name,
          observe(f.value, kind, id, Vector(ParserFieldPathSegment.NamedField(f.name))),
          f.declaredShape.map(declaredPattern)
        )
      )
      val classification     = position match
        case ParserNodePosition.Absent                                                   => SourceClassification.Absent
        case ParserNodePosition.Positioned(_, _, ParserPositionProvenance.SourceDerived) =>
          SourceClassification.SourceReachable
        case _                                                                           => SourceClassification.Synthetic
      val scannerTokenKinds  = position match
        case ParserNodePosition.Positioned(range, _, _) =>
          snapshot.scannerTokens
            .filter(token => range.startOffset <= token.range.startOffset && token.range.endOffset <= range.endOffset)
            .map(_.kind)
        case _                                          => Vector.empty
      val directNodeEvidence = fields.flatMap: field =>
        field.value match
          case ParserFieldValue.Node(childId) =>
            nodes
              .get(childId)
              .map: child =>
                val childClassification = child.position match
                  case ParserNodePosition.Absent                                                   => SourceClassification.Absent
                  case ParserNodePosition.Positioned(_, _, ParserPositionProvenance.SourceDerived) =>
                    SourceClassification.SourceReachable
                  case _                                                                           => SourceClassification.Synthetic
                val hasSourceWidth      = child.position match
                  case ParserNodePosition.Positioned(range, _, _) => Some(range.startOffset < range.endOffset)
                  case ParserNodePosition.Absent                  => None
                val attachmentKinds     = snapshot.attachments
                  .filter(_.ownerNodeId == childId)
                  .map(_.keyKind)
                  .toSet
                DirectNodeFieldEvidence(field.name, childClassification, hasSourceWidth, attachmentKinds)
          case _                              => None
      val rootAttachments    =
        if kind == InventoryKind.Node then
          snapshot.attachments
            .filter(_.ownerNodeId == id)
            .map(attachment => AttachmentEvidence(attachment.keyKind, attachment.value))
        else Vector.empty
      CompilerShapeInventoryRow(
        kind,
        id,
        prefix,
        observed.map(f => CompilerFieldPattern(f.name, f.declaredPattern.getOrElse(pattern(f.value)))),
        observed,
        occurrences.flatMap(contexts(kind, id, _)).distinct,
        classification,
        scannerTokenKinds,
        directNodeEvidence,
        rootAttachments
      )
    val found                                                                                                      = failures.result()
    if found.nonEmpty then Left(found.distinct.sortBy(_.toString))
    else
      val identity = snapshot.compilerIdentity
      Right(
        CompilerRuntimeInventory(
          CompilerRuntimeIdentity(
            identity.coordinate,
            identity.artifacts.map(a => CompilerArtifactIdentity(a.ordinal, a.fileName, a.byteSize, a.sha256)),
            snapshot.compilerOptions
          ),
          ParserSyntaxSnapshot.evidenceFingerprint(snapshot),
          rows,
          snapshot.nodes,
          products
        )
      )
  private def duplicate(ids: Vector[Long])                                                             =
    ids.groupMapReduce(identity)(_ => 1)(_ + _).collect { case (id, n) if n > 1 => id }.toVector.sorted
