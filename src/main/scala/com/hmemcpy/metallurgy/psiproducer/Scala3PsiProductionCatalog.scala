package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compilerbackend.*
import com.hmemcpy.metallurgy.pc.*
import org.jetbrains.org.objectweb.asm.Opcodes
import scala.util.boundary
import scala.util.boundary.break
import scala.util.Try

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
private[metallurgy] final case class InventoryContext(
    ownerKind: InventoryKind,
    ownerPrefix: String,
    path: Vector[CatalogPathSegment],
    ancestors: Vector[InventoryAncestor] = Vector.empty,
    ownerNodePrefixes: Map[String, String] = Map.empty
)
private[metallurgy] object InventoryContextLineage:
  def normalized(path: Vector[ParserFieldPathSegment]): Vector[CatalogPathSegment] = path.map:
    case ParserFieldPathSegment.NamedField(name)                  => CatalogPathSegment.NamedField(name)
    case ParserFieldPathSegment.OptionalNesting                   => CatalogPathSegment.Optional
    case ParserFieldPathSegment.RepeatedIndex(_)                  => CatalogPathSegment.RepeatedElement
    case ParserFieldPathSegment.NestedProductBoundary(production) => CatalogPathSegment.NestedProduct(production)

  def resolver(nodes: Map[Long, ParserSyntaxNode]): Resolver = new Resolver(nodes)

  final class Resolver private[InventoryContextLineage] (nodes: Map[Long, ParserSyntaxNode]):
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
      val values       = collection.mutable.Map.empty[Long, Vector[Vector[InventoryAncestor]]]
      while ready.nonEmpty do
        val id       = ready.dequeue()
        val node     = nodes(id)
        val computed =
          if node.occurrences.isEmpty then Vector(Vector.empty)
          else
            node.occurrences.flatMap: occurrence =>
              nodes
                .get(occurrence.ownerNodeId)
                .toVector
                .flatMap: ancestor =>
                  values
                    .getOrElse(ancestor.id, Vector.empty)
                    .map(
                      InventoryAncestor(InventoryKind.Node, ancestor.production, normalized(occurrence.fieldPath)) +: _
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
        .getOrElse(ancestries(owner, nodes, Set.empty))
        .map: ancestors =>
          val ownerNodePrefixes = owner.fields.flatMap: field =>
            field.value match
              case ParserFieldValue.Node(id) => nodes.get(id).map(value => field.name -> value.production)
              case _                         => None
          InventoryContext(
            InventoryKind.Node,
            owner.production,
            normalized(path),
            ancestors,
            ownerNodePrefixes.toMap
          )

  private def ancestries(
      owner: ParserSyntaxNode,
      nodes: Map[Long, ParserSyntaxNode],
      visited: Set[Long]
  ): Vector[Vector[InventoryAncestor]] =
    val pending  = collection.mutable.Stack((owner, visited, Vector.empty[InventoryAncestor]))
    val complete = Vector.newBuilder[Vector[InventoryAncestor]]
    while pending.nonEmpty do
      val (current, currentVisited, currentAncestors) = pending.pop()
      if !currentVisited(current.id) then
        if current.occurrences.isEmpty then complete += currentAncestors
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
  case Product(prefix: String, fields: Vector[CompilerFieldPattern])
  case Name, GeneratedName
  case ClassifiedName(nameClass: NeutralNameClass)
  case Scalar(kind: String)
  case ExactScalar(kind: String, rendered: String)
  case Unsupported(runtimeType: String)
private[metallurgy] enum InventoryValueObservation:
  case Node(id: Long, prefix: String)
  case Positioned(id: Long, prefix: String)
  case Optional(value: Option[InventoryValueObservation])
  case Repeated(values: Vector[InventoryValueObservation])
  case Product(prefix: String, fields: Vector[InventoryFieldObservation])
  case Name(value: String)
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
private[metallurgy] final case class CompilerProductionContextPattern(
    context: ContextPattern,
    sourceClassification: SourceClassification,
    scannerEvidence: ScannerEvidencePattern = ScannerEvidencePattern()
)
private[metallurgy] final case class CompilerProductionPattern(
    kind: InventoryKind,
    prefix: String,
    fields: Vector[CompilerFieldPattern],
    occurrences: Vector[CompilerProductionContextPattern]
)
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
    scannerTokenKinds: Vector[ParserScannerTokenKind] = Vector.empty
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
    scannerTokenKinds: Vector[ParserScannerTokenKind] = Vector.empty
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
                contexts.map(CompilerProductionContext(_, row.sourceClassification, row.scannerTokenKinds))
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
      case None                                                                                 => Left(InventoryAggregationFailure.UnresolvedShape(path))
      case Some(_: InventoryValueObservation.Optional)                                          =>
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
      case Some(_: InventoryValueObservation.Repeated)                                          =>
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
      case Some(InventoryValueObservation.Product(prefix, _))                                   =>
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
      case Some(_: InventoryValueObservation.Node)                                              =>
        sameCategory(observations, path)(_.isInstanceOf[InventoryValueObservation.Node], CatalogValuePattern.Node)
          .flatMap(validate)
      case Some(_: InventoryValueObservation.Positioned)                                        =>
        sameCategory(observations, path)(
          _.isInstanceOf[InventoryValueObservation.Positioned],
          CatalogValuePattern.Positioned
        ).flatMap(validate)
      case Some(_: InventoryValueObservation.Name | _: InventoryValueObservation.GeneratedName) =>
        val classes = observations.collect { case InventoryValueObservation.Name(value) =>
          NeutralNameClass.classify(value)
        }
        if observations.forall(value =>
            value.isInstanceOf[InventoryValueObservation.Name] ||
              value.isInstanceOf[InventoryValueObservation.GeneratedName]
          )
        then
          val result =
            if classes.size == observations.size && classes.distinct.size == 1 then
              CatalogValuePattern.ClassifiedName(classes.head)
            else if observations.forall(_.isInstanceOf[InventoryValueObservation.GeneratedName]) then
              CatalogValuePattern.GeneratedName
            else CatalogValuePattern.Name
          validate(result)
        else incompatible(path)
      case Some(InventoryValueObservation.Scalar(value))                                        =>
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
      case Some(InventoryValueObservation.Unsupported(runtimeType))                             =>
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
    case InventoryValueObservation.GeneratedName(_, _, _)  => e.tag(16)
    case other                                             => writeObservation(other, e)

  private def writeProductionContext(context: CompilerProductionContext, e: CanonicalByteEncoder): Unit =
    writeOptionalContext(context.context, e); e.tag(context.sourceClassification.ordinal)
    e.sequence(context.scannerTokenKinds)(kind => e.tag(kind.ordinal))

  private def writeField(field: CompilerFieldPattern, e: CanonicalByteEncoder): Unit =
    e.string(field.name); writePattern(field.value, e)

  private def writePattern(value: CatalogValuePattern, e: CanonicalByteEncoder): Unit = value match
    case CatalogValuePattern.Node                                   => e.tag(1)
    case CatalogValuePattern.NodePrefix(prefix)                     => e.tag(16); e.string(prefix)
    case CatalogValuePattern.NodeExceptPrefix(prefix)               => e.tag(17); e.string(prefix)
    case CatalogValuePattern.Positioned                             => e.tag(2)
    case CatalogValuePattern.Optional(inner)                        => e.tag(3); writePattern(inner, e)
    case CatalogValuePattern.EmptyOptional(inner)                   => e.tag(12); writePattern(inner, e)
    case CatalogValuePattern.Repeated(inner)                        => e.tag(4); writePattern(inner, e)
    case CatalogValuePattern.NonEmptyRepeated(inner)                => e.tag(15); writePattern(inner, e)
    case CatalogValuePattern.EmptyRepeated(inner)                   => e.tag(10); writePattern(inner, e)
    case CatalogValuePattern.LeadingThenRepeated(leading, trailing) =>
      e.tag(14); writePattern(leading, e); writePattern(trailing, e)
    case CatalogValuePattern.Product(prefix, fields)                => e.tag(5); e.string(prefix); e.sequence(fields)(writeField(_, e))
    case CatalogValuePattern.Name                                   => e.tag(6)
    case CatalogValuePattern.GeneratedName                          => e.tag(7)
    case CatalogValuePattern.ClassifiedName(value)                  => e.tag(11); e.string(value.toString)
    case CatalogValuePattern.Scalar(kind)                           => e.tag(8); e.string(kind)
    case CatalogValuePattern.ExactScalar(kind, value)               => e.tag(13); e.string(kind); e.string(value)
    case CatalogValuePattern.Unsupported(runtime)                   => e.tag(9); e.string(runtime)

  private def writeContext(context: InventoryContext, e: CanonicalByteEncoder): Unit =
    e.tag(context.ownerKind.ordinal); e.string(context.ownerPrefix); e.sequence(context.path)(writePath(_, e))
    e.sequence(context.ancestors): ancestor =>
      e.tag(ancestor.ownerKind.ordinal); e.string(ancestor.ownerPrefix); e.sequence(ancestor.path)(writePath(_, e))
    e.sequence(context.ownerNodePrefixes.toVector.sorted): (name, prefix) =>
      e.string(name); e.string(prefix)

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
    val lineages                                                                                                   = InventoryContextLineage.resolver(nodes)
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
      case ParserFieldValue.Name(value)             => InventoryValueObservation.Name(value)
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
      val observed          = fields.map(f =>
        InventoryFieldObservation(
          f.name,
          observe(f.value, kind, id, Vector(ParserFieldPathSegment.NamedField(f.name))),
          f.declaredShape.map(declaredPattern)
        )
      )
      val classification    = position match
        case ParserNodePosition.Absent                                                   => SourceClassification.Absent
        case ParserNodePosition.Positioned(_, _, ParserPositionProvenance.SourceDerived) =>
          SourceClassification.SourceReachable
        case _                                                                           => SourceClassification.Synthetic
      val scannerTokenKinds = position match
        case ParserNodePosition.Positioned(range, _, _) =>
          snapshot.scannerTokens
            .filter(token => range.startOffset <= token.range.startOffset && token.range.endOffset <= range.endOffset)
            .map(_.kind)
        case _                                          => Vector.empty
      CompilerShapeInventoryRow(
        kind,
        id,
        prefix,
        observed.map(f => CompilerFieldPattern(f.name, f.declaredPattern.getOrElse(pattern(f.value)))),
        observed,
        occurrences.flatMap(contexts(kind, id, _)).distinct,
        classification,
        scannerTokenKinds
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

private[metallurgy] enum SurfaceFactKind:
  case Element, Token, Factory, PublicAccessor, Stub, Serializer, Index, Navigation, ParserProduction, Class, Method
private[metallurgy] enum SurfaceClassification:
  case SyntaxContract, SemanticOnly, MutationRefactoring, Derived, Helper, NotApplicable, Unclassified
private[metallurgy] enum FactStatus:
  case Available
  case Unresolved(reason: String)
  case Unsupported(reason: String)
private[metallurgy] final case class ScalaPsiSurfaceRow(
    id: String,
    kind: SurfaceFactKind,
    ownerId: Option[String],
    status: FactStatus,
    classification: SurfaceClassification,
    evidence: Vector[String] = Vector.empty
)
private[metallurgy] final case class ScalaPsiSurfaceInventory(
    rows: Vector[ScalaPsiSurfaceRow],
    artifact: Option[InstalledScalaPluginArtifact] = None
):
  private lazy val encoded        = ScalaPsiSurfaceInventory.serialize(this)
  lazy val fingerprint: String    = CanonicalByteEncoder.sha256Hex(encoded)
  def canonicalBytes: Array[Byte] = encoded.clone()

  def withCatalogCapabilities(catalog: Scala3PsiProductionCatalog): ScalaPsiSurfaceInventory =
    val compatibleComposites = catalog.productions
      .flatMap(_.effectiveOutputRealizations.flatMap(_.template.composites))
      .filter(_.targetRequirement == TargetRequirement.Compatible)
    val ownedTargets         = compatibleComposites
      .map(_.targetSurfaceId)
      .distinct
      .filterNot(id => rows.exists(_.id == id))
      .map(id =>
        ScalaPsiSurfaceRow(
          id,
          SurfaceFactKind.Element,
          None,
          FactStatus.Available,
          SurfaceClassification.SyntaxContract,
          Vector("capability-probed compatible PSI target")
        )
      )
    val ownedAccessors       = compatibleComposites
      .flatMap(composite => composite.accessors.map(composite.targetSurfaceId -> _))
      .distinct
      .filterNot((_, obligation) => rows.exists(_.id == obligation.surfaceId))
      .map: (owner, obligation) =>
        val available = for
          separator <- obligation.surfaceId.indexOf('#') match
                         case -1    => None
                         case value => Some(value)
          className  = obligation.surfaceId.substring(0, separator).replace('/', '.')
          signature  = obligation.surfaceId.substring(separator + 1)
          clazz     <- Try(Class.forName(className, false, getClass.getClassLoader)).toOption
          method    <-
            clazz.getMethods.find(method =>
              s"${method.getName}${org.jetbrains.org.objectweb.asm.Type.getMethodDescriptor(method)}" == signature
            )
        yield method
        ScalaPsiSurfaceRow(
          obligation.surfaceId,
          obligation.surfaceKind,
          Some(owner),
          if available.nonEmpty then FactStatus.Available
          else FactStatus.Unresolved("compatible PSI accessor is absent"),
          SurfaceClassification.SyntaxContract,
          Vector("capability-probed compatible PSI accessor")
        )
    copy(rows = rows ++ ownedTargets ++ ownedAccessors)

private[metallurgy] object ScalaPsiSurfaceInventory:
  def installed(): Either[String, ScalaPsiSurfaceInventory] =
    ScalaPluginSemanticBridge.installedPsiSurface().map(from)

  def from(surface: InstalledScalaPluginSurface): ScalaPsiSurfaceInventory =
    val classes                                                          = surface.classes.map(clazz => clazz.internalName -> clazz).toMap
    val psiRoot                                                          = "org/jetbrains/plugins/scala/lang/psi/api/ScalaPsiElement"
    val stubRoots                                                        = Set(
      "com/intellij/psi/stubs/StubElement",
      "com/intellij/psi/stubs/NamedStub",
      "com/intellij/psi/stubs/PsiFileStub",
      "com/intellij/psi/stubs/StubBase",
      "com/intellij/psi/stubs/IStubElementType",
      "com/intellij/psi/stubs/IStubFileElementType",
      "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScStubElementType"
    )
    def derives(name: String, roots: Set[String]): Boolean               =
      val visited = scala.collection.mutable.Set.empty[String]
      val pending = scala.collection.mutable.Stack(name)
      var result  = false
      while pending.nonEmpty && !result do
        val current = pending.pop()
        if roots(current) then result = true
        else if visited.add(current) then
          classes.get(current).foreach(clazz => pending.pushAll(clazz.superName.toVector ++ clazz.interfaces))
      result
    def methodReturn(method: InstalledScalaPluginMethod): Option[String] =
      Try(org.jetbrains.org.objectweb.asm.Type.getReturnType(method.descriptor)).toOption
        .filter(_.getSort == org.jetbrains.org.objectweb.asm.Type.OBJECT)
        .map(_.getInternalName)
    val classRows                                                        = surface.classes.flatMap: clazz =>
      val public   = (clazz.access & Opcodes.ACC_PUBLIC) != 0
      val concrete = (clazz.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) == 0
      val psi      = derives(clazz.internalName, Set(psiRoot))
      val api      = public && psi
      val stub     = derives(clazz.internalName, stubRoots)
      val native   = concrete && clazz.methods
        .filter(_.name == "<init>")
        .flatMap(method => Try(org.jetbrains.org.objectweb.asm.Type.getArgumentTypes(method.descriptor)).toOption)
        .flatten
        .exists(argument =>
          argument.getSort == org.jetbrains.org.objectweb.asm.Type.OBJECT &&
            (argument.getInternalName == "com/intellij/lang/ASTNode" || derives(argument.getInternalName, stubRoots))
        )
      val evidence = Vector(
        s"access:${clazz.access}",
        s"super:${clazz.superName.getOrElse("")}",
        s"interfaces:${clazz.interfaces.mkString(",")}",
        s"signature:${clazz.genericSignature.getOrElse("")}",
        s"constructors:${clazz.methods.filter(_.name == "<init>").map(_.descriptor).mkString(",")}"
      )
      val typeRow  = Some(
        ScalaPsiSurfaceRow(
          clazz.internalName,
          if stub then SurfaceFactKind.Stub else if psi then SurfaceFactKind.Element else SurfaceFactKind.Class,
          None,
          FactStatus.Available,
          if psi && native then SurfaceClassification.SyntaxContract
          else if api || (psi && concrete) then SurfaceClassification.Derived
          else if stub then SurfaceClassification.Derived
          else SurfaceClassification.Helper,
          evidence
        )
      )
      val methods  = clazz.methods
        .filter(m => (m.access & Opcodes.ACC_PUBLIC) != 0)
        .filter(m => (m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) == 0)
        .filterNot(m =>
          m.name == "<init>" || m.name == "<clinit>" || m.name.endsWith("_$eq") || m.name.startsWith("set")
        )
        .map: method =>
          val id         = s"${clazz.internalName}#${method.name}${method.descriptor}"
          val arguments  = Try(org.jetbrains.org.objectweb.asm.Type.getArgumentTypes(method.descriptor)).toOption
          val zeroArgs   = arguments.exists(_.isEmpty)
          val returnsPsi =
            methodReturn(method).exists(name => derives(name, Set(psiRoot)) || name.startsWith("com/intellij/psi/")) ||
              method.genericSignature.exists(signature =>
                signature.contains("Lorg/jetbrains/plugins/scala/lang/psi/api/") ||
                  signature.contains("Lcom/intellij/psi/")
              )
          val navigation = psi && zeroArgs && returnsPsi &&
            (method.name == "getNavigationElement" || method.name == "navigationElement")
          val serializer = stub && Set("serialize", "deserialize", "getExternalId")(method.name) &&
            ((method.name == "getExternalId" && zeroArgs) || (method.name != "getExternalId" && arguments.exists(
              _.nonEmpty
            )))
          val accessor   = api && zeroArgs && returnsPsi
          val evidence   = Vector(
            s"access:${method.access}",
            s"descriptor:${method.descriptor}",
            s"signature:${method.genericSignature.getOrElse("")}"
          )
          ScalaPsiSurfaceRow(
            id,
            if navigation then SurfaceFactKind.Navigation
            else if serializer then SurfaceFactKind.Serializer
            else if accessor then SurfaceFactKind.PublicAccessor
            else SurfaceFactKind.Method,
            Some(clazz.internalName),
            FactStatus.Available,
            if accessor then SurfaceClassification.SyntaxContract
            else SurfaceClassification.Derived,
            evidence
          )
      typeRow.toVector ++ methods
    val descriptorRows                                                   = surface.descriptorFacts.map: fact =>
      val kind  = if fact.kind == "stubIndex" then SurfaceFactKind.Index else SurfaceFactKind.Factory
      val bound = fact.implementation.exists(classes.contains)
      val id    =
        s"descriptor:${fact.ordinal}:${fact.kind}:${fact.implementation.getOrElse("unresolved")}"
      ScalaPsiSurfaceRow(
        id,
        kind,
        None,
        if bound then FactStatus.Available
        else FactStatus.Unresolved("registration target is absent or unscanned"),
        if kind == SurfaceFactKind.Index then SurfaceClassification.SyntaxContract else SurfaceClassification.Derived,
        Vector(
          s"registration:${fact.kind}",
          s"ordinal:${fact.ordinal}",
          s"target:${fact.implementation.getOrElse("")}"
        )
      )
    val unresolvedRows                                                   = surface.unresolved.map(reason =>
      ScalaPsiSurfaceRow(
        s"unresolved:$reason",
        SurfaceFactKind.Stub,
        None,
        FactStatus.Unresolved(reason),
        SurfaceClassification.SyntaxContract
      )
    )
    val rows                                                             = (classRows ++ descriptorRows ++ unresolvedRows).distinct.sortBy(canonicalRow)
    ScalaPsiSurfaceInventory(rows, Some(surface.artifact))

  def serialize(inventory: ScalaPsiSurfaceInventory): Array[Byte] =
    val e = CanonicalByteEncoder()
    e.tag(1)
    inventory.artifact match
      case None           => e.tag(0)
      case Some(artifact) => e.tag(1); e.string(artifact.fileName); e.long(artifact.byteSize); e.string(artifact.sha256)
    e.sequence(inventory.rows.distinct.sortBy(canonicalRow))(writeRow(_, e))
    e.result()

  private def canonicalRow(row: ScalaPsiSurfaceRow): String =
    val e = CanonicalByteEncoder(); writeRow(row, e)
    java.util.Base64.getEncoder.encodeToString(e.result())

  private def writeRow(row: ScalaPsiSurfaceRow, e: CanonicalByteEncoder): Unit =
    e.string(row.id); e.tag(row.kind.ordinal)
    row.ownerId.fold(e.tag(0))(owner => { e.tag(1); e.string(owner) })
    row.status match
      case FactStatus.Available           => e.tag(1)
      case FactStatus.Unresolved(reason)  => e.tag(2); e.string(reason)
      case FactStatus.Unsupported(reason) => e.tag(3); e.string(reason)
    e.tag(row.classification.ordinal)
    e.sequence(row.evidence)(e.string)

private[metallurgy] enum FieldDispositionKind:
  case Child, TerminalOrLayout, SemanticOnly, RecoveryOnly, Synthetic, Unsupported
private[metallurgy] final case class FieldDisposition(fieldName: String, kind: FieldDispositionKind)
private[metallurgy] final case class GrammarRoleId(value: String):
  require(value.nonEmpty)
private[metallurgy] object GrammarRoleId:
  val CompilationUnit        = GrammarRoleId("scala.compilation-unit")
  val PackageClause          = GrammarRoleId("scala.package.clause")
  val PackageReference       = GrammarRoleId("scala.package.reference")
  val ImportStatement        = GrammarRoleId("scala.import.statement")
  val ExportStatement        = GrammarRoleId("scala.export.statement")
  val AbsentProduct          = GrammarRoleId("scala.absent-product")
  val StableReference        = GrammarRoleId("scala.reference.stable")
  val ImportSelector         = GrammarRoleId("scala.import.selector")
  val ImportSelectorName     = GrammarRoleId("scala.import.selector-name")
  val SimpleType             = GrammarRoleId("scala.type.simple")
  val TypeProjection         = GrammarRoleId("scala.type.projection")
  val SingletonType          = GrammarRoleId("scala.type.singleton")
  val LiteralType            = GrammarRoleId("scala.type.literal")
  val ParenthesizedType      = GrammarRoleId("scala.type.parenthesized")
  val LiteralValue           = GrammarRoleId("scala.literal.value")
  val AppliedType            = GrammarRoleId("scala.type.applied")
  val TypeArgumentList       = GrammarRoleId("scala.type-argument.list")
  val PositionalTypeArgument = GrammarRoleId("scala.type-argument.positional")
  val NamedTypeArgument      = GrammarRoleId("scala.type-argument.named")
  val ExpressionTypeApply    = GrammarRoleId("scala.expression.type-application-island")
  val WildcardType           = GrammarRoleId("scala.type.wildcard")
  val InfixType              = GrammarRoleId("scala.type.infix")
  val IntegerLiteral         = GrammarRoleId("scala.literal.integer")
  val ExpressionPayload      = GrammarRoleId("scala.expression.payload")
  val Modifiers              = GrammarRoleId("scala.modifiers")
  val AccessModifier         = GrammarRoleId("scala.modifier.access")
  val KeywordModifier        = GrammarRoleId("scala.modifier.keyword")
  val Annotations            = GrammarRoleId("scala.annotations")
  val Annotation             = GrammarRoleId("scala.annotation")
  val AnnotationArguments    = GrammarRoleId("scala.annotation.arguments")
  val ClassDefinition        = GrammarRoleId("scala.definition.class")
  val TraitDefinition        = GrammarRoleId("scala.definition.trait")
  val ObjectDefinition       = GrammarRoleId("scala.definition.object")
  val EnumDefinition         = GrammarRoleId("scala.definition.enum")
  val EnumCase               = GrammarRoleId("scala.definition.enum.case")
  val Template               = GrammarRoleId("scala.template")
  val TemplateConstructor    = GrammarRoleId("scala.template.constructor")
  val TypeParameterClause    = GrammarRoleId("scala.type-parameter.clause")
  val UnboundedTypeParameter = GrammarRoleId("scala.type-parameter.unbounded")
  val TermParameter          = GrammarRoleId("scala.term-parameter")
  val ClassParameter         = GrammarRoleId("scala.class-parameter")
  val TemplateSelf           = GrammarRoleId("scala.template.self")
  val TemplateTypeTree       = GrammarRoleId("scala.template.type-tree")
  val FunctionDefinition     = GrammarRoleId("scala.definition.function")
  val PropertyDefinition     = GrammarRoleId("scala.definition.property")
  val ReferenceBinding       = GrammarRoleId("scala.pattern.reference-binding")
  val TypeAliasDeclaration   = GrammarRoleId("scala.definition.type-alias-declaration")
  val TypeAliasDefinition    = GrammarRoleId("scala.definition.type-alias-definition")
  val InferredTypeAbsence    = GrammarRoleId("scala.type.inferred-absence")
  val OutputFreeExpression   = GrammarRoleId("scala.expression.output-free-descendant")
private[metallurgy] enum ChildCardinality:
  case ExactlyOne, Optional
  case Repeated(minimum: Int, maximum: Option[Int])
  case Grouped(minimum: Int, maximum: Option[Int])
private[metallurgy] enum ChildSlice:
  case All
  case MatchingProductions
  case LeadingBeforeRuntimeTail(fieldName: String)
  case RuntimeTail(fieldName: String)
private[metallurgy] final case class ChildDeclaration(
    roleId: String,
    fieldName: String,
    cardinality: ChildCardinality,
    productionId: String,
    additionalProductionIds: Set[String] = Set.empty,
    slice: ChildSlice = ChildSlice.All
):
  require(productionId.nonEmpty)
  val productionIds: Set[String] = additionalProductionIds + productionId
private[metallurgy] enum TerminalIntervalSelector:
  case FieldBounds(startField: String, endField: String)
  case ChildGap(startRole: String, endRole: String)
  case BeforeChild(roleId: String)
  case CompilerEndMarkerKeyword
  case CompilerScannerToken(
      kind: ParserScannerTokenKind,
      occurrence: ScannerTokenOccurrence = ScannerTokenOccurrence.All
  )
  case LocalOutput(outputId: String)
  case RootOutsideLocalOutput(outputId: String)
  case WholeProduction, WholeSource
private[metallurgy] enum ScannerTokenOccurrence:
  case All, First, Last
private[metallurgy] enum TerminalLeafTarget:
  case Token(surfaceId: String, expectedText: Option[String] = None)
  case Trivia, Delimiter, Separator, Parent
private[metallurgy] enum OccurrenceCardinality:
  case ExactlyOne, Optional
  case Repeated(minimum: Int, maximum: Option[Int])
private[metallurgy] final case class TerminalDeclaration(
    id: String,
    selector: TerminalIntervalSelector,
    target: TerminalLeafTarget,
    cardinality: OccurrenceCardinality,
    outputRoleId: PsiOutputRoleId,
    ownsStructuralEvidence: Option[Boolean] = None
):
  val claimsStructuralEvidence: Boolean = ownsStructuralEvidence.getOrElse(target == TerminalLeafTarget.Parent)
private[metallurgy] enum LayoutAlternative:
  case None
  case Braced(openPath: Vector[String], closePath: Vector[String])
  case Indented(indentPath: Vector[String], outdentPath: Vector[String])
private[metallurgy] enum RecoveryPolicy:
  case Reject
  case DiagnosticBound(diagnostic: ParserDiagnosticSeverity, alternatives: Vector[String])
private[metallurgy] enum TargetRequirement:
  case Native, NativeCandidate, Compatible
private[metallurgy] final case class AccessorObligation(
    surfaceId: String,
    required: Boolean,
    surfaceKind: SurfaceFactKind = SurfaceFactKind.PublicAccessor
)
private[metallurgy] enum PersistenceObligations:
  case NotApplicable
  case Required(
      stubSurfaceId: String,
      serializerSurfaceId: String,
      indexSurfaceIds: Vector[String],
      navigationSurfaceId: String
  )
private[metallurgy] enum NavigationObligation:
  case Self
private[metallurgy] enum PositionProvenancePolicy:
  case SourceDerivedOnly, PositionedIncludingSynthetic
private[metallurgy] enum ChildOccurrenceSelector:
  case First, Last
  case Exact(index: Int)
private[metallurgy] enum OutputBoundary:
  case ProductionStart(policy: PositionProvenancePolicy = PositionProvenancePolicy.SourceDerivedOnly)
  case ProductionEnd(policy: PositionProvenancePolicy = PositionProvenancePolicy.SourceDerivedOnly)
  case ProductionPoint
  case ProductionNameEnd
  case ParentProductionEnd
  case TemplateLayoutStart
  case PreviousSignificantChildTokenStart(
      roleId: String,
      occurrence: ChildOccurrenceSelector,
      policy: PositionProvenancePolicy
  )
  case ChildStart(roleId: String, occurrence: ChildOccurrenceSelector, policy: PositionProvenancePolicy)
  case ChildEnd(roleId: String, occurrence: ChildOccurrenceSelector, policy: PositionProvenancePolicy)
  case EvidenceBoundaryAfterChild(
      roleId: String,
      occurrence: ChildOccurrenceSelector,
      followingRoleId: String,
      followingOccurrence: ChildOccurrenceSelector,
      expectedDelimiters: Vector[String],
      policy: PositionProvenancePolicy,
      fallbackToFollowingChildStart: Boolean = false
  )
  case Advance(boundary: OutputBoundary, boundaryCount: Int)
private[metallurgy] enum OutputRangeDeclaration:
  case CompilerPosition
  case CompilerPositionWithPolicy(policy: PositionProvenancePolicy)
  case CompilerPositionWithTrailingBalancedBrackets(policy: PositionProvenancePolicy)
  case CompilerPositionWithBodyLayoutOrEndMarker(
      headerRole: String,
      bodyRole: Option[String],
      opening: ClosedSourceLexicalKind,
      closing: ClosedSourceLexicalKind,
      indentation: ClosedSourceLexicalKind
  )
  case BoundaryDerived(startBoundary: OutputBoundary, endBoundary: OutputBoundary)
  case BoundaryDerivedWithTrailingBalancedBrackets(startBoundary: OutputBoundary, endBoundary: OutputBoundary)
  case CompilerEndMarker
private[metallurgy] final case class PsiOutputRoleId(value: String):
  require(value.nonEmpty)
private[metallurgy] object PsiOutputRoleId:
  val SourceTerminal        = PsiOutputRoleId("scala.source.terminal")
  val PackageStatement      = PsiOutputRoleId("scala.package.statement")
  val EndStatement          = PsiOutputRoleId("scala.end.statement")
  val EndKeyword            = PsiOutputRoleId("scala.end.keyword")
  val ImportStatement       = PsiOutputRoleId("scala.import.statement")
  val ExportStatement       = PsiOutputRoleId("scala.export.statement")
  val ImportExpression      = PsiOutputRoleId("scala.import.expression")
  val ImportSelectorSet     = PsiOutputRoleId("scala.import.selector-set")
  val ImportSelector        = PsiOutputRoleId("scala.import.selector")
  val StableReference       = PsiOutputRoleId("scala.reference.stable")
  val SimpleType            = PsiOutputRoleId("scala.type.simple")
  val SingletonType         = PsiOutputRoleId("scala.type.singleton")
  val TypeProjection        = PsiOutputRoleId("scala.type.projection")
  val LiteralType           = PsiOutputRoleId("scala.type.literal")
  val ParenthesizedType     = PsiOutputRoleId("scala.type.parenthesized")
  val IntegerLiteralValue   = PsiOutputRoleId("scala.literal.integer-value")
  val LongLiteralValue      = PsiOutputRoleId("scala.literal.long-value")
  val FloatLiteralValue     = PsiOutputRoleId("scala.literal.float-value")
  val DoubleLiteralValue    = PsiOutputRoleId("scala.literal.double-value")
  val CharLiteralValue      = PsiOutputRoleId("scala.literal.char-value")
  val StringLiteralValue    = PsiOutputRoleId("scala.literal.string-value")
  val BooleanLiteralValue   = PsiOutputRoleId("scala.literal.boolean-value")
  val ParameterizedType     = PsiOutputRoleId("scala.type.parameterized")
  val TypeArguments         = PsiOutputRoleId("scala.type.arguments")
  val NamedTypeArguments    = PsiOutputRoleId("scala.type.arguments.named-compatible")
  val NamedTypeArgument     = PsiOutputRoleId("scala.type.argument.named-compatible")
  val WildcardType          = PsiOutputRoleId("scala.type.wildcard")
  val InfixType             = PsiOutputRoleId("scala.type.infix")
  val IntegerLiteral        = PsiOutputRoleId("scala.literal.integer")
  val ExpressionPayload     = PsiOutputRoleId("scala.expression.payload")
  val ModifierList          = PsiOutputRoleId("scala.modifiers")
  val AccessModifier        = PsiOutputRoleId("scala.modifier.access")
  val Annotations           = PsiOutputRoleId("scala.annotations")
  val Annotation            = PsiOutputRoleId("scala.annotation")
  val AnnotationExpr        = PsiOutputRoleId("scala.annotation.expression")
  val ConstructorInvocation = PsiOutputRoleId("scala.annotation.constructor")
  val AnnotationArguments   = PsiOutputRoleId("scala.annotation.arguments")
  val ClassDefinition       = PsiOutputRoleId("scala.definition.class")
  val TraitDefinition       = PsiOutputRoleId("scala.definition.trait")
  val ObjectDefinition      = PsiOutputRoleId("scala.definition.object")
  val EnumDefinition        = PsiOutputRoleId("scala.definition.enum")
  val EnumCases             = PsiOutputRoleId("scala.definition.enum.cases")
  val EnumSingletonCase     = PsiOutputRoleId("scala.definition.enum.case.singleton")
  val EnumClassCase         = PsiOutputRoleId("scala.definition.enum.case.class")
  val ExtendsBlock          = PsiOutputRoleId("scala.template.extends-block")
  val TemplateBody          = PsiOutputRoleId("scala.template.body")
  val PrimaryConstructor    = PsiOutputRoleId("scala.template.constructor.primary")
  val ParameterClauses      = PsiOutputRoleId("scala.template.parameter-clauses")
  val ParameterClause       = PsiOutputRoleId("scala.template.parameter-clause")
  val TypeParameterClause   = PsiOutputRoleId("scala.type-parameter.clause")
  val TypeParameter         = PsiOutputRoleId("scala.type-parameter")
  val Parameter             = PsiOutputRoleId("scala.parameter")
  val ClassParameter        = PsiOutputRoleId("scala.class-parameter")
  val ParameterType         = PsiOutputRoleId("scala.parameter.type")
  val TemplateParents       = PsiOutputRoleId("scala.template.parents")
  val SelfType              = PsiOutputRoleId("scala.template.self-type")
  val DerivesClause         = PsiOutputRoleId("scala.template.derives")
  val FunctionDefinition    = PsiOutputRoleId("scala.definition.function")
  val FunctionDeclaration   = PsiOutputRoleId("scala.declaration.function")
  val PatternDefinition     = PsiOutputRoleId("scala.definition.pattern")
  val ValueDeclaration      = PsiOutputRoleId("scala.declaration.value")
  val VariableDefinition    = PsiOutputRoleId("scala.definition.variable")
  val VariableDeclaration   = PsiOutputRoleId("scala.declaration.variable")
  val PatternList           = PsiOutputRoleId("scala.pattern.list")
  val ReferencePattern      = PsiOutputRoleId("scala.pattern.reference")
  val IdentifierList        = PsiOutputRoleId("scala.identifier.list")
  val FieldId               = PsiOutputRoleId("scala.field.id")
  val TypeAliasDeclaration  = PsiOutputRoleId("scala.definition.type-alias-declaration")
  val TypeAliasDefinition   = PsiOutputRoleId("scala.definition.type-alias-definition")
private[metallurgy] final case class StableRoleInventory(
    grammarRoles: Set[GrammarRoleId],
    outputRoles: Set[PsiOutputRoleId]
)
private[metallurgy] object StableRoleInventory:
  val Empty = StableRoleInventory(Set.empty, Set.empty)

  val Reviewed = StableRoleInventory(
    Set(
      GrammarRoleId.CompilationUnit,
      GrammarRoleId.PackageClause,
      GrammarRoleId.PackageReference,
      GrammarRoleId.ImportStatement,
      GrammarRoleId.ExportStatement,
      GrammarRoleId.AbsentProduct,
      GrammarRoleId.StableReference,
      GrammarRoleId.ImportSelector,
      GrammarRoleId.ImportSelectorName,
      GrammarRoleId.SimpleType,
      GrammarRoleId.TypeProjection,
      GrammarRoleId.SingletonType,
      GrammarRoleId.LiteralType,
      GrammarRoleId.ParenthesizedType,
      GrammarRoleId.LiteralValue,
      GrammarRoleId.AppliedType,
      GrammarRoleId.TypeArgumentList,
      GrammarRoleId.PositionalTypeArgument,
      GrammarRoleId.NamedTypeArgument,
      GrammarRoleId.ExpressionTypeApply,
      GrammarRoleId.WildcardType,
      GrammarRoleId.InfixType,
      GrammarRoleId.IntegerLiteral,
      GrammarRoleId.ExpressionPayload,
      GrammarRoleId.Modifiers,
      GrammarRoleId.AccessModifier,
      GrammarRoleId.KeywordModifier,
      GrammarRoleId.Annotations,
      GrammarRoleId.Annotation,
      GrammarRoleId.AnnotationArguments,
      GrammarRoleId.ClassDefinition,
      GrammarRoleId.TraitDefinition,
      GrammarRoleId.ObjectDefinition,
      GrammarRoleId.EnumDefinition,
      GrammarRoleId.EnumCase,
      GrammarRoleId.Template,
      GrammarRoleId.TemplateConstructor,
      GrammarRoleId.TypeParameterClause,
      GrammarRoleId.UnboundedTypeParameter,
      GrammarRoleId.TermParameter,
      GrammarRoleId.ClassParameter,
      GrammarRoleId.TemplateSelf,
      GrammarRoleId.TemplateTypeTree,
      GrammarRoleId.FunctionDefinition,
      GrammarRoleId.PropertyDefinition,
      GrammarRoleId.ReferenceBinding,
      GrammarRoleId.TypeAliasDeclaration,
      GrammarRoleId.TypeAliasDefinition,
      GrammarRoleId.InferredTypeAbsence,
      GrammarRoleId.OutputFreeExpression
    ),
    Set(
      PsiOutputRoleId.SourceTerminal,
      PsiOutputRoleId.PackageStatement,
      PsiOutputRoleId.EndStatement,
      PsiOutputRoleId.EndKeyword,
      PsiOutputRoleId.ImportStatement,
      PsiOutputRoleId.ExportStatement,
      PsiOutputRoleId.ImportExpression,
      PsiOutputRoleId.ImportSelectorSet,
      PsiOutputRoleId.ImportSelector,
      PsiOutputRoleId.StableReference,
      PsiOutputRoleId.SimpleType,
      PsiOutputRoleId.SingletonType,
      PsiOutputRoleId.TypeProjection,
      PsiOutputRoleId.LiteralType,
      PsiOutputRoleId.ParenthesizedType,
      PsiOutputRoleId.IntegerLiteralValue,
      PsiOutputRoleId.LongLiteralValue,
      PsiOutputRoleId.FloatLiteralValue,
      PsiOutputRoleId.DoubleLiteralValue,
      PsiOutputRoleId.CharLiteralValue,
      PsiOutputRoleId.StringLiteralValue,
      PsiOutputRoleId.BooleanLiteralValue,
      PsiOutputRoleId.ParameterizedType,
      PsiOutputRoleId.TypeArguments,
      PsiOutputRoleId.NamedTypeArguments,
      PsiOutputRoleId.NamedTypeArgument,
      PsiOutputRoleId.WildcardType,
      PsiOutputRoleId.InfixType,
      PsiOutputRoleId.IntegerLiteral,
      PsiOutputRoleId.ExpressionPayload,
      PsiOutputRoleId.ModifierList,
      PsiOutputRoleId.AccessModifier,
      PsiOutputRoleId.Annotations,
      PsiOutputRoleId.Annotation,
      PsiOutputRoleId.AnnotationExpr,
      PsiOutputRoleId.ConstructorInvocation,
      PsiOutputRoleId.AnnotationArguments,
      PsiOutputRoleId.ClassDefinition,
      PsiOutputRoleId.TraitDefinition,
      PsiOutputRoleId.ObjectDefinition,
      PsiOutputRoleId.EnumDefinition,
      PsiOutputRoleId.EnumCases,
      PsiOutputRoleId.EnumSingletonCase,
      PsiOutputRoleId.EnumClassCase,
      PsiOutputRoleId.ExtendsBlock,
      PsiOutputRoleId.TemplateBody,
      PsiOutputRoleId.PrimaryConstructor,
      PsiOutputRoleId.ParameterClauses,
      PsiOutputRoleId.ParameterClause,
      PsiOutputRoleId.TypeParameterClause,
      PsiOutputRoleId.TypeParameter,
      PsiOutputRoleId.Parameter,
      PsiOutputRoleId.ClassParameter,
      PsiOutputRoleId.ParameterType,
      PsiOutputRoleId.TemplateParents,
      PsiOutputRoleId.SelfType,
      PsiOutputRoleId.DerivesClause,
      PsiOutputRoleId.FunctionDefinition,
      PsiOutputRoleId.FunctionDeclaration,
      PsiOutputRoleId.PatternDefinition,
      PsiOutputRoleId.ValueDeclaration,
      PsiOutputRoleId.VariableDefinition,
      PsiOutputRoleId.VariableDeclaration,
      PsiOutputRoleId.PatternList,
      PsiOutputRoleId.ReferencePattern,
      PsiOutputRoleId.IdentifierList,
      PsiOutputRoleId.FieldId,
      PsiOutputRoleId.TypeAliasDeclaration,
      PsiOutputRoleId.TypeAliasDefinition
    )
  )
private[metallurgy] object ImportPersistenceSurfaces:
  val StatementStub         = "org/jetbrains/plugins/scala/lang/psi/stubs/ScImportStmtStub"
  val ExpressionStub        = "org/jetbrains/plugins/scala/lang/psi/stubs/ScImportExprStub"
  val SelectorSetStub       = "org/jetbrains/plugins/scala/lang/psi/stubs/ScImportSelectorsStub"
  val SelectorStub          = "org/jetbrains/plugins/scala/lang/psi/stubs/ScImportSelectorStub"
  val StatementSerializer   =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScImportStmtElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScImportStmtStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val ExpressionSerializer  =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScImportExprElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScImportExprStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val SelectorSetSerializer =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScImportSelectorsElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScImportSelectorsStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val SelectorSerializer    =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScImportSelectorElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScImportSelectorStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val AliasedImportIndex    =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#ALIASED_IMPORT_KEY"
  val SelfNavigation        = "scala.psi.navigation.self"
private[metallurgy] object ExportPersistenceSurfaces:
  val StatementStub        = "org/jetbrains/plugins/scala/lang/psi/stubs/ScExportStmtStub"
  val StatementSerializer  =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScExportStmtElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScExportStmtStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val TopLevelPackageIndex =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#TOP_LEVEL_EXPORT_BY_PKG_KEY"
private[metallurgy] object PackagePersistenceSurfaces:
  val Stub       = "org/jetbrains/plugins/scala/lang/psi/stubs/ScPackagingStub"
  val Serializer =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScPackagingElementType$#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScPackagingStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val FqnIndex   = "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#PACKAGE_FQN_KEY"
private[metallurgy] object ModifierAnnotationPersistenceSurfaces:
  val ModifierStub          = "org/jetbrains/plugins/scala/lang/psi/stubs/ScModifiersStub"
  val ModifierSerializer    =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScModifiersElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScModifiersStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val AccessStub            = "org/jetbrains/plugins/scala/lang/psi/stubs/ScAccessModifierStub"
  val AccessSerializer      =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScAccessModifierElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScAccessModifierStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val AnnotationsStub       = "org/jetbrains/plugins/scala/lang/psi/stubs/ScAnnotationsStub"
  val AnnotationsSerializer =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScStubElementType#serialize(Lcom/intellij/psi/stubs/StubElement;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val AnnotationStub        = "org/jetbrains/plugins/scala/lang/psi/stubs/ScAnnotationStub"
  val AnnotationSerializer  =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScAnnotationElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScAnnotationStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
private[metallurgy] object TemplatePersistenceSurfaces:
  val DefinitionStub                = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScTemplateDefinitionStubImpl"
  val DefinitionSerializer          =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScTemplateDefinitionElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScTemplateDefinitionStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val EnumCasesStub                 = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScEnumCasesStubImpl"
  val ExtendsBlockStub              = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScExtendsBlockStubImpl"
  val ExtendsBlockSerializer        =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScExtendsBlockElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScExtendsBlockStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val TemplateBodyStub              = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScTemplateBodyStubImpl"
  val PrimaryConstructorStub        = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScPrimaryConstructorStubImpl"
  val ParameterClausesStub          = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScParamClausesStubImpl"
  val ParameterClauseStub           = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScParamClauseStubImpl"
  val ParameterStub                 = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScParameterStubImpl"
  val ParameterSerializer           =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/signatures/ScParamElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScParameterStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val TemplateParentsStub           = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScTemplateParentsStubImpl"
  val TemplateParentsSerializer     =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScTemplateParentsElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScTemplateParentsStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val SelfTypeStub                  = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScSelfTypeElementStubImpl"
  val SelfTypeSerializer            =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScSelfTypeElementElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScSelfTypeElementStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val DerivesClauseStub             = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScDerivesClauseStubImpl"
  val TypeParameterClauseStub       = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScTypeParamClauseStubImpl"
  val TypeParameterStub             = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScTypeParamStubImpl"
  val TypeParameterClauseSerializer =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScTypeParamClauseElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScTypeParamClauseStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val TypeParameterSerializer       =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScTypeParamElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScTypeParamStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val GenericSerializer             =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScStubElementType#serialize(Lcom/intellij/psi/stubs/StubElement;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val ShortNameIndex                = "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#SHORT_NAME_KEY"
  val ClassFqnIndex                 = "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#CLASS_FQN_KEY"
  val ClassNameInPackageIndex       =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#CLASS_NAME_IN_PACKAGE_KEY"
  val JavaClassShortNameIndex       = "com/intellij/psi/impl/java/stubs/index/JavaStubIndexKeys#CLASS_SHORT_NAMES"
  val JavaClassFqnIndex             = "com/intellij/psi/impl/java/stubs/index/JavaStubIndexKeys#CLASS_FQN"
  val NotVisibleInJavaIndex         =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#NOT_VISIBLE_IN_JAVA_SHORT_NAME_KEY"
  val AllClassNamesIndex            = "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#ALL_CLASS_NAMES"
  val SuperClassNameIndex           =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#SUPER_CLASS_NAME_KEY"
  val SelfTypeClassNameIndex        =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#SELF_TYPE_CLASS_NAME_KEY"
  val JavaClassNameInPackageIndex   =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#JAVA_CLASS_NAME_IN_PACKAGE_KEY"
  val DefinitionIndices             = Vector(
    ShortNameIndex,
    ClassFqnIndex,
    ClassNameInPackageIndex,
    JavaClassShortNameIndex,
    JavaClassFqnIndex,
    NotVisibleInJavaIndex,
    AllClassNamesIndex,
    JavaClassNameInPackageIndex
  )
  val ExternalIds                   = Map(
    PsiOutputRoleId.ClassDefinition     -> "scala.ScClass",
    PsiOutputRoleId.TraitDefinition     -> "scala.ScTrait",
    PsiOutputRoleId.ObjectDefinition    -> "scala.ScObject",
    PsiOutputRoleId.EnumDefinition      -> "scala.ScEnum",
    PsiOutputRoleId.EnumCases           -> "scala.ScEnumCases",
    PsiOutputRoleId.EnumSingletonCase   -> "scala.ScEnumSingletonCase",
    PsiOutputRoleId.EnumClassCase       -> "scala.ScEnumClassCase",
    PsiOutputRoleId.ExtendsBlock        -> "scala.extends block",
    PsiOutputRoleId.TemplateBody        -> "scala.template body",
    PsiOutputRoleId.PrimaryConstructor  -> "scala.primary constructor",
    PsiOutputRoleId.ParameterClauses    -> "scala.parameter clauses",
    PsiOutputRoleId.ParameterClause     -> "scala.parameter clause",
    PsiOutputRoleId.Parameter           -> "scala.parameter",
    PsiOutputRoleId.ClassParameter      -> "scala.class parameter",
    PsiOutputRoleId.TemplateParents     -> "scala.template parents",
    PsiOutputRoleId.SelfType            -> "scala.self type element",
    PsiOutputRoleId.DerivesClause       -> "scala.template derives",
    PsiOutputRoleId.TypeParameterClause -> "scala.type parameter clause",
    PsiOutputRoleId.TypeParameter       -> "scala.type parameter"
  )
private[metallurgy] object DefinitionPersistenceSurfaces:
  val FunctionStub           = "org/jetbrains/plugins/scala/lang/psi/stubs/ScFunctionStub"
  val FunctionSerializer     =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScFunctionElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScFunctionStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val PropertyStub           = "org/jetbrains/plugins/scala/lang/psi/stubs/ScPropertyStub"
  val PropertySerializer     =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScPropertyElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScPropertyStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val PatternListStub        = "org/jetbrains/plugins/scala/lang/psi/stubs/ScPatternListStub"
  val PatternListSerializer  =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScPatternListElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScPatternListStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val BindingStub            = "org/jetbrains/plugins/scala/lang/psi/stubs/ScBindingPatternStub"
  val BindingSerializer      =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScBindingPatternElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScBindingPatternStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val IdentifierListStub     = "org/jetbrains/plugins/scala/lang/psi/stubs/ScIdListStub"
  val FieldIdStub            = "org/jetbrains/plugins/scala/lang/psi/stubs/ScFieldIdStub"
  val FieldIdSerializer      =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScFieldIdElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScFieldIdStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val TypeAliasStub          = "org/jetbrains/plugins/scala/lang/psi/stubs/ScTypeAliasStub"
  val TypeAliasSerializer    =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScTypeAliasElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScTypeAliasStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val MethodNameIndex        = "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#METHOD_NAME_KEY"
  val TopLevelFunctionIndex  =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#TOP_LEVEL_FUNCTION_BY_PKG_KEY"
  val PropertyNameIndex      = "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#PROPERTY_NAME_KEY"
  val TopLevelPropertyIndex  =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#TOP_LEVEL_VAL_OR_VAR_BY_PKG_KEY"
  val TypeAliasNameIndex     = "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#TYPE_ALIAS_NAME_KEY"
  val TopLevelTypeAliasIndex =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#TOP_LEVEL_TYPE_ALIAS_BY_PKG_KEY"
  val ExternalIds            = Map(
    PsiOutputRoleId.FunctionDefinition   -> "scala.function definition",
    PsiOutputRoleId.FunctionDeclaration  -> "scala.function declaration",
    PsiOutputRoleId.PatternDefinition    -> "scala.value definition",
    PsiOutputRoleId.ValueDeclaration     -> "scala.value declaration",
    PsiOutputRoleId.VariableDefinition   -> "scala.variable definition",
    PsiOutputRoleId.VariableDeclaration  -> "scala.variable declaration",
    PsiOutputRoleId.PatternList          -> "scala.pattern list",
    PsiOutputRoleId.ReferencePattern     -> "scala.reference pattern",
    PsiOutputRoleId.IdentifierList       -> "scala.id list",
    PsiOutputRoleId.FieldId              -> "scala.field id",
    PsiOutputRoleId.TypeAliasDeclaration -> "scala.type alias declaration",
    PsiOutputRoleId.TypeAliasDefinition  -> "scala.type alias definition"
  )
private[metallurgy] enum OutputCompositeRealization:
  case Once
  case PerChildRole(roleId: String)
  case AtFirstRepeatedFieldOccurrenceStart(
      fieldName: String,
      valuePattern: CatalogValuePattern,
      opening: ClosedSourceLexicalKind,
      closing: ClosedSourceLexicalKind
  )
  case AcrossRepeatedFieldOccurrences(
      fieldName: String,
      valuePattern: CatalogValuePattern,
      opening: ClosedSourceLexicalKind,
      closing: ClosedSourceLexicalKind
  )
  case PerRepeatedFieldOccurrence(
      fieldName: String,
      valuePattern: CatalogValuePattern,
      opening: ClosedSourceLexicalKind,
      closing: ClosedSourceLexicalKind
  )
private[metallurgy] final case class OutputCompositeDeclaration(
    id: String,
    parentId: Option[String],
    range: OutputRangeDeclaration,
    outputRoleId: PsiOutputRoleId,
    targetSurfaceId: String,
    targetRequirement: TargetRequirement,
    accessors: Vector[AccessorObligation],
    persistence: PersistenceObligations,
    navigation: Option[NavigationObligation],
    ownsStructuralEvidence: Boolean = false,
    requiresCompilerEndMarker: Boolean = false,
    realization: OutputCompositeRealization = OutputCompositeRealization.Once
)
private[metallurgy] final case class LocalOutputCompositeTemplate(
    composites: Vector[OutputCompositeDeclaration],
    childMounts: Map[String, Option[String]],
    childOutputSelections: Map[String, PsiOutputRoleId] = Map.empty
)
private[metallurgy] enum ChildOutcomeExpectation:
  case Production(productionId: String)
  case Realization(realizationId: String)
private[metallurgy] final case class ChildOutcomeCondition(
    roleId: String,
    occurrence: ChildOccurrenceSelector,
    expected: ChildOutcomeExpectation
)
private[metallurgy] enum EvidenceCondition:
  case TemplateBodyLayout(present: Boolean)
  case RepeatedFieldOccurrence(fieldName: String, valuePattern: CatalogValuePattern, present: Boolean)
  case RuntimeSupplementPositive(fieldName: String, present: Boolean)
  case LeadingBeforeRuntimeTailPresent(repeatedFieldName: String, countFieldName: String, present: Boolean)
private[metallurgy] final case class OutputRealization(
    id: String,
    conditions: Vector[ChildOutcomeCondition],
    template: LocalOutputCompositeTemplate,
    evidenceConditions: Vector[EvidenceCondition] = Vector.empty
)
private[metallurgy] final case class Scala3PsiProduction(
    id: String,
    grammarRoleId: GrammarRoleId,
    pattern: CompilerProductionPattern,
    dispositions: Vector[FieldDisposition],
    children: Vector[ChildDeclaration],
    terminals: Vector[TerminalDeclaration],
    layouts: Vector[LayoutAlternative],
    recovery: RecoveryPolicy,
    targetSurfaceId: String,
    targetRequirement: TargetRequirement,
    accessors: Vector[AccessorObligation],
    persistence: PersistenceObligations,
    navigation: Option[NavigationObligation] = None,
    outputTemplate: Option[LocalOutputCompositeTemplate] = None,
    outputRealizations: Vector[OutputRealization] = Vector.empty,
    outputRoleId: Option[PsiOutputRoleId],
    additionalGrammarRoleIds: Set[GrammarRoleId] = Set.empty
):
  val grammarRoleIds: Set[GrammarRoleId]                                  = additionalGrammarRoleIds + grammarRoleId
  private def defaultOutputTemplate: Option[LocalOutputCompositeTemplate] = outputTemplate.orElse(
    outputRoleId.map(role =>
      LocalOutputCompositeTemplate(
        Vector(
          OutputCompositeDeclaration(
            "self",
            None,
            OutputRangeDeclaration.CompilerPosition,
            role,
            targetSurfaceId,
            targetRequirement,
            accessors,
            persistence,
            navigation
          )
        ),
        children.map(child => child.roleId -> Some("self")).toMap
      )
    )
  )
  def effectiveOutputRealizations: Vector[OutputRealization]              =
    if outputRealizations.nonEmpty then outputRealizations
    else defaultOutputTemplate.toVector.map(OutputRealization("self", Vector.empty, _))
  def effectiveOutputTemplate: LocalOutputCompositeTemplate               = effectiveOutputRealizations.head.template
private[metallurgy] final case class Scala3PsiProductionCatalog(
    productions: Vector[Scala3PsiProduction],
    stableRoles: StableRoleInventory
)
private[metallurgy] enum CatalogCapabilityFailure:
  case MissingProduction(id: String)
  case InvalidTargetRequirement(id: String, requirement: TargetRequirement)
  case IntegerLiteralTargetsUnavailable(
      native: Either[IntegerLiteralProbeFailure, Vector[NativeIntegerLiteralObservation]],
      compatible: Either[IntegerLiteralProbeFailure, Vector[NativeIntegerLiteralObservation]]
  )
private[metallurgy] object Scala3PsiProductionCatalog:
  val Empty: Scala3PsiProductionCatalog = Scala3PsiProductionCatalog(Vector.empty, StableRoleInventory.Empty)

  private val PersistenceExternalIds =
    TemplatePersistenceSurfaces.ExternalIds ++ DefinitionPersistenceSurfaces.ExternalIds

  def persistenceSchemaFingerprint(catalog: Scala3PsiProductionCatalog): String =
    persistenceSchemaFingerprint(catalog, PersistenceExternalIds)

  private[psiproducer] def persistenceSchemaFingerprint(
      catalog: Scala3PsiProductionCatalog,
      externalIds: Map[PsiOutputRoleId, String]
  ): String =
    val encoder = CanonicalByteEncoder()
    encoder.sequence(catalog.stableRoles.outputRoles.toVector.sortBy(_.value))(role => encoder.string(role.value))
    encoder.sequence(catalog.productions.sortBy(_.id)): production =>
      encoder.string(production.id)
      encoder.string(production.pattern.kind.toString)
      encoder.string(production.pattern.prefix)
      encoder.sequence(production.pattern.fields): field =>
        encoder.string(field.name)
        encoder.string(field.value.toString)
      encoder.sequence(production.pattern.occurrences.sortBy(_.toString))(occurrence =>
        encoder.string(occurrence.toString)
      )
      encoder.sequence(production.grammarRoleIds.toVector.sortBy(_.value))(role => encoder.string(role.value))
      encoder.sequence(production.children.sortBy(_.roleId)): child =>
        encoder.string(child.roleId)
        encoder.string(child.fieldName)
        encoder.sequence(child.productionIds.toVector.sorted)(encoder.string)
        encoder.string(child.cardinality.toString)
        encoder.string(child.slice.toString)
      encoder.sequence(production.terminals): terminal =>
        encoder.string(terminal.id)
        encoder.string(terminal.selector.toString)
        encoder.string(terminal.target.toString)
        encoder.string(terminal.cardinality.toString)
        encoder.string(terminal.outputRoleId.value)
      encoder.sequence(production.effectiveOutputRealizations.sortBy(_.id)): realization =>
        encoder.string(realization.id)
        encoder.sequence(realization.conditions.sortBy(_.toString))(condition => encoder.string(condition.toString))
        encoder.sequence(realization.evidenceConditions.sortBy(_.toString))(condition =>
          encoder.string(condition.toString)
        )
        encoder.sequence(realization.template.composites): output =>
          encoder.string(output.id)
          encoder.string(output.parentId.getOrElse(""))
          encoder.string(output.range.toString)
          encoder.string(output.outputRoleId.value)
          encoder.string(output.targetSurfaceId)
          encoder.string(externalIds.getOrElse(output.outputRoleId, ""))
          encoder.string(output.targetRequirement.toString)
          encoder.sequence(output.accessors.sortBy(_.toString))(accessor => encoder.string(accessor.toString))
          encoder.string(output.navigation.toString)
          encoder.string(output.ownsStructuralEvidence.toString)
          encoder.string(output.requiresCompilerEndMarker.toString)
          encoder.string(output.realization.toString)
          output.persistence match
            case PersistenceObligations.NotApplicable                                   => encoder.tag(0)
            case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
              encoder.tag(1)
              encoder.string(stub)
              encoder.string(serializer)
              encoder.sequence(indices.sorted)(encoder.string)
              encoder.string(navigation)
        encoder.sequence(realization.template.childMounts.toVector.sortBy(_._1)): (role, parent) =>
          encoder.string(role)
          encoder.string(parent.getOrElse(""))
    CanonicalByteEncoder.sha256Hex(encoder.result())

  private val PackageSurface              =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/packaging/ScPackagingImpl"
  private val EndSurface                  = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScEndImpl"
  private val ImportStatementSurface      =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/imports/ScImportStmtImpl"
  private val ExportStatementSurface      =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/imports/ScExportStmtImpl"
  private val ExportStatementApi          =
    "org/jetbrains/plugins/scala/lang/psi/api/toplevel/imports/ScExportStmt"
  private val ImportExpressionSurface     =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/imports/ScImportExprImpl"
  private val ImportSelectorsSurface      =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/imports/ScImportSelectorsImpl"
  private val ImportSelectorSurface       =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/imports/ScImportSelectorImpl"
  private val StableReferenceSurface      =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/ScStableCodeReferenceImpl"
  private val SimpleTypeSurface           =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScSimpleTypeElementImpl"
  private val TypeProjectionSurface       =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScTypeProjectionImpl"
  private val LiteralTypeSurface          =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScLiteralTypeElementImpl"
  private val ParenthesizedTypeSurface    =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScParenthesisedTypeElementImpl"
  private val IntegerLiteralSurface       =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/literals/ScIntegerLiteralImpl"
  private val LongLiteralSurface          =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/literals/ScLongLiteralImpl"
  private val FloatLiteralSurface         =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/literals/ScFloatLiteralImpl"
  private val DoubleLiteralSurface        =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/literals/ScDoubleLiteralImpl"
  private val CharLiteralSurface          =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/literals/ScCharLiteralImpl"
  private val StringLiteralSurface        = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScStringLiteralImpl"
  private val BooleanLiteralSurface       =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/literals/ScBooleanLiteralImpl"
  private val ParameterizedTypeSurface    =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScParameterizedTypeElementImpl"
  private val TypeArgumentsSurface        =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScTypeArgsImpl"
  private val NamedTypeArgumentsSurface   =
    "org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyTypeArguments"
  private val NamedTypeArgumentSurface    =
    "org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyNamedTypeArgument"
  private val WildcardTypeSurface         =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScWildcardTypeElementImpl"
  private val InfixTypeSurface            =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScInfixTypeElementImpl"
  private val ModifierListSurface         = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScModifierListImpl"
  private val AccessModifierSurface       = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScAccessModifierImpl"
  private val AnnotationsSurface          = "org/jetbrains/plugins/scala/lang/psi/impl/expr/ScAnnotationsImpl"
  private val AnnotationSurface           = "org/jetbrains/plugins/scala/lang/psi/impl/expr/ScAnnotationImpl"
  private val AnnotationExprSurface       = "org/jetbrains/plugins/scala/lang/psi/impl/expr/ScAnnotationExprImpl"
  private val ConstructorSurface          = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScConstructorInvocationImpl"
  private val AnnotationArgumentsSurface  =
    "org/jetbrains/plugins/scala/lang/psi/impl/expr/ScArgumentExprListImpl"
  private val ExpressionPayloadSurface    =
    "org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyExpressionPayload"
  private val ExpressionSurface           = "org/jetbrains/plugins/scala/lang/psi/api/expr/ScExpression"
  private val ClassDefinitionSurface      =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/typedef/ScClassImpl"
  private val TraitDefinitionSurface      =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/typedef/ScTraitImpl"
  private val ObjectDefinitionSurface     =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/typedef/ScObjectImpl"
  private val EnumDefinitionSurface       =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/typedef/ScEnumImpl"
  private val EnumCasesSurface            =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScEnumCasesImpl"
  private val EnumSingletonCaseSurface    =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScEnumSingletonCaseImpl"
  private val EnumClassCaseSurface        =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScEnumClassCaseImpl"
  private val ExtendsBlockSurface         =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/templates/ScExtendsBlockImpl"
  private val TemplateBodySurface         =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/templates/ScTemplateBodyImpl"
  private val PrimaryConstructorSurface   =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/ScPrimaryConstructorImpl"
  private val ParameterClausesSurface     =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/params/ScParametersImpl"
  private val ParameterClauseSurface      =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/params/ScParameterClauseImpl"
  private val ParameterSurface            =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/params/ScParameterImpl"
  private val ClassParameterSurface       =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/params/ScClassParameterImpl"
  private val ParameterTypeSurface        =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/params/ScParameterTypeImpl"
  private val TemplateParentsSurface      =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/templates/ScTemplateParentsImpl"
  private val SelfTypeSurface             =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScSelfTypeElementImpl"
  private val DerivesClauseSurface        =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/templates/ScDerivesClauseImpl"
  private val TypeParameterClauseSurface  =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/params/ScTypeParamClauseImpl"
  private val TypeParameterSurface        =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/params/ScTypeParamImpl"
  private val FunctionDefinitionSurface   =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScFunctionDefinitionImpl"
  private val FunctionDeclarationSurface  =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScFunctionDeclarationImpl"
  private val PatternDefinitionSurface    =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScPatternDefinitionImpl"
  private val ValueDeclarationSurface     =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScValueDeclarationImpl"
  private val VariableDefinitionSurface   =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScVariableDefinitionImpl"
  private val VariableDeclarationSurface  =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScVariableDeclarationImpl"
  private val PatternListSurface          = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScPatternListImpl"
  private val ReferencePatternSurface     =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/patterns/ScReferencePatternImpl"
  private val IdentifierListSurface       = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScIdListImpl"
  private val FieldIdSurface              = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScFieldIdImpl"
  private val TypeAliasDeclarationSurface =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScTypeAliasDeclarationImpl"
  private val TypeAliasDefinitionSurface  =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScTypeAliasDefinitionImpl"

  private def outputComposite(
      id: String,
      parentId: Option[String],
      range: OutputRangeDeclaration,
      role: PsiOutputRoleId,
      surface: String,
      accessors: Vector[AccessorObligation],
      requirement: TargetRequirement = TargetRequirement.Native
  ): OutputCompositeDeclaration =
    val persistence = role match
      case PsiOutputRoleId.PackageStatement                                           =>
        PersistenceObligations.Required(
          PackagePersistenceSurfaces.Stub,
          PackagePersistenceSurfaces.Serializer,
          Vector(PackagePersistenceSurfaces.FqnIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ImportStatement                                            =>
        PersistenceObligations.Required(
          ImportPersistenceSurfaces.StatementStub,
          ImportPersistenceSurfaces.StatementSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ExportStatement                                            =>
        PersistenceObligations.Required(
          ExportPersistenceSurfaces.StatementStub,
          ExportPersistenceSurfaces.StatementSerializer,
          Vector(ExportPersistenceSurfaces.TopLevelPackageIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ImportExpression                                           =>
        PersistenceObligations.Required(
          ImportPersistenceSurfaces.ExpressionStub,
          ImportPersistenceSurfaces.ExpressionSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ImportSelectorSet                                          =>
        PersistenceObligations.Required(
          ImportPersistenceSurfaces.SelectorSetStub,
          ImportPersistenceSurfaces.SelectorSetSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ImportSelector                                             =>
        PersistenceObligations.Required(
          ImportPersistenceSurfaces.SelectorStub,
          ImportPersistenceSurfaces.SelectorSerializer,
          Vector(ImportPersistenceSurfaces.AliasedImportIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ModifierList                                               =>
        PersistenceObligations.Required(
          ModifierAnnotationPersistenceSurfaces.ModifierStub,
          ModifierAnnotationPersistenceSurfaces.ModifierSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.AccessModifier                                             =>
        PersistenceObligations.Required(
          ModifierAnnotationPersistenceSurfaces.AccessStub,
          ModifierAnnotationPersistenceSurfaces.AccessSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.Annotations                                                =>
        PersistenceObligations.Required(
          ModifierAnnotationPersistenceSurfaces.AnnotationsStub,
          ModifierAnnotationPersistenceSurfaces.AnnotationsSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.Annotation                                                 =>
        PersistenceObligations.Required(
          ModifierAnnotationPersistenceSurfaces.AnnotationStub,
          ModifierAnnotationPersistenceSurfaces.AnnotationSerializer,
          Vector(NativePsiElementBindings.AnnotatedMemberIndexSurface),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ClassDefinition | PsiOutputRoleId.TraitDefinition | PsiOutputRoleId.ObjectDefinition |
          PsiOutputRoleId.EnumDefinition | PsiOutputRoleId.EnumSingletonCase | PsiOutputRoleId.EnumClassCase =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.DefinitionStub,
          TemplatePersistenceSurfaces.DefinitionSerializer,
          TemplatePersistenceSurfaces.DefinitionIndices,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.TypeParameterClause                                        =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.TypeParameterClauseStub,
          TemplatePersistenceSurfaces.TypeParameterClauseSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.TypeParameter                                              =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.TypeParameterStub,
          TemplatePersistenceSurfaces.TypeParameterSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.EnumCases                                                  =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.EnumCasesStub,
          TemplatePersistenceSurfaces.GenericSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ExtendsBlock                                               =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.ExtendsBlockStub,
          TemplatePersistenceSurfaces.ExtendsBlockSerializer,
          Vector(TemplatePersistenceSurfaces.SuperClassNameIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.TemplateBody                                               =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.TemplateBodyStub,
          TemplatePersistenceSurfaces.GenericSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.PrimaryConstructor                                         =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.PrimaryConstructorStub,
          TemplatePersistenceSurfaces.GenericSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ParameterClauses                                           =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.ParameterClausesStub,
          TemplatePersistenceSurfaces.GenericSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ParameterClause                                            =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.ParameterClauseStub,
          TemplatePersistenceSurfaces.GenericSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.Parameter | PsiOutputRoleId.ClassParameter                 =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.ParameterStub,
          TemplatePersistenceSurfaces.ParameterSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.TemplateParents                                            =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.TemplateParentsStub,
          TemplatePersistenceSurfaces.TemplateParentsSerializer,
          Vector(TemplatePersistenceSurfaces.SuperClassNameIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.SelfType                                                   =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.SelfTypeStub,
          TemplatePersistenceSurfaces.SelfTypeSerializer,
          Vector(TemplatePersistenceSurfaces.SelfTypeClassNameIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.DerivesClause                                              =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.DerivesClauseStub,
          TemplatePersistenceSurfaces.GenericSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.FunctionDefinition                                         =>
        PersistenceObligations.Required(
          DefinitionPersistenceSurfaces.FunctionStub,
          DefinitionPersistenceSurfaces.FunctionSerializer,
          Vector(DefinitionPersistenceSurfaces.MethodNameIndex, DefinitionPersistenceSurfaces.TopLevelFunctionIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.FunctionDeclaration                                        =>
        PersistenceObligations.Required(
          DefinitionPersistenceSurfaces.FunctionStub,
          DefinitionPersistenceSurfaces.FunctionSerializer,
          Vector(DefinitionPersistenceSurfaces.MethodNameIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.PatternDefinition | PsiOutputRoleId.VariableDefinition | PsiOutputRoleId.ValueDeclaration |
          PsiOutputRoleId.VariableDeclaration =>
        PersistenceObligations.Required(
          DefinitionPersistenceSurfaces.PropertyStub,
          DefinitionPersistenceSurfaces.PropertySerializer,
          Vector(DefinitionPersistenceSurfaces.PropertyNameIndex, DefinitionPersistenceSurfaces.TopLevelPropertyIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.PatternList                                                =>
        PersistenceObligations.Required(
          DefinitionPersistenceSurfaces.PatternListStub,
          DefinitionPersistenceSurfaces.PatternListSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ReferencePattern                                           =>
        PersistenceObligations.Required(
          DefinitionPersistenceSurfaces.BindingStub,
          DefinitionPersistenceSurfaces.BindingSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.IdentifierList                                             =>
        PersistenceObligations.Required(
          DefinitionPersistenceSurfaces.IdentifierListStub,
          TemplatePersistenceSurfaces.GenericSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.FieldId                                                    =>
        PersistenceObligations.Required(
          DefinitionPersistenceSurfaces.FieldIdStub,
          DefinitionPersistenceSurfaces.FieldIdSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.TypeAliasDeclaration | PsiOutputRoleId.TypeAliasDefinition =>
        PersistenceObligations.Required(
          DefinitionPersistenceSurfaces.TypeAliasStub,
          DefinitionPersistenceSurfaces.TypeAliasSerializer,
          Vector(
            DefinitionPersistenceSurfaces.TypeAliasNameIndex,
            DefinitionPersistenceSurfaces.TopLevelTypeAliasIndex
          ),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case _                                                                          => PersistenceObligations.NotApplicable
    OutputCompositeDeclaration(
      id,
      parentId,
      range,
      role,
      surface,
      requirement,
      accessors,
      persistence,
      Some(NavigationObligation.Self)
    )

  private val ImportStatementAccessors      = Vector(
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/toplevel/imports/ScImportOrExportStmt#importExprs()Lscala/collection/immutable/Seq;",
      required = true
    )
  )
  private val ExportStatementAccessors      = ImportStatementAccessors ++ Vector(
    AccessorObligation(
      s"$ExportStatementApi#isTopLevel()Z",
      required = true,
      surfaceKind = SurfaceFactKind.Method
    ),
    AccessorObligation(
      s"$ExportStatementApi#topLevelQualifier()Lscala/Option;",
      required = true,
      SurfaceFactKind.Method
    )
  )
  private val PackageAccessors              = Vector(
    AccessorObligation(s"$PackageSurface#reference()Lscala/Option;", required = true),
    AccessorObligation(s"$PackageSurface#keyword()Lcom/intellij/psi/PsiElement;", required = true),
    AccessorObligation(
      s"$PackageSurface#parentPackageName()Ljava/lang/String;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(s"$PackageSurface#packageName()Ljava/lang/String;", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$PackageSurface#fullPackageName()Ljava/lang/String;", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$PackageSurface#isExplicit()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$PackageSurface#findExplicitMarker()Lscala/Option;", required = true),
    AccessorObligation(s"$PackageSurface#bodyText()Ljava/lang/String;", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$PackageSurface#packagings()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(s"$PackageSurface#getImportStatements()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(s"$PackageSurface#getExportStatements()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(s"$PackageSurface#getLBrace()Lscala/Option;", required = true),
    AccessorObligation(s"$PackageSurface#getRBrace()Lscala/Option;", required = true),
    AccessorObligation(s"$PackageSurface#getColon()Lscala/Option;", required = true),
    AccessorObligation(s"$PackageSurface#isEnclosedByBraces()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$PackageSurface#isEnclosedByColon()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$PackageSurface#end()Lscala/Option;", required = true)
  )
  private val EndAccessors                  = Vector(
    AccessorObligation(s"$EndSurface#begin()Lscala/Option;", required = true),
    AccessorObligation(s"$EndSurface#keyword()Lcom/intellij/psi/PsiElement;", required = true),
    AccessorObligation(s"$EndSurface#tag()Lcom/intellij/psi/PsiElement;", required = true),
    AccessorObligation(s"$EndSurface#getName()Ljava/lang/String;", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$EndSurface#getReference()Lcom/intellij/psi/PsiReference;", required = true),
    AccessorObligation(s"$EndSurface#getElement()Lcom/intellij/psi/PsiElement;", required = true),
    AccessorObligation(
      s"$EndSurface#getRangeInElement()Lcom/intellij/openapi/util/TextRange;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(s"$EndSurface#resolve()Lcom/intellij/psi/PsiElement;", required = true),
    AccessorObligation(s"$EndSurface#isSoft()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$EndSurface#getCanonicalText()Ljava/lang/String;", required = true, SurfaceFactKind.Method)
  )
  private val ImportExpressionAccessors     = Vector(
    AccessorObligation(s"$ImportExpressionSurface#reference()Lscala/Option;", required = true),
    AccessorObligation(s"$ImportExpressionSurface#selectorSet()Lscala/Option;", required = true),
    AccessorObligation(s"$ImportExpressionSurface#qualifier()Lscala/Option;", required = true)
  )
  private val ImportSelectorsAccessors      = Vector(
    AccessorObligation(
      s"$ImportSelectorsSurface#selectors()Lscala/collection/immutable/Seq;",
      required = true
    )
  )
  private val ImportSelectorAccessors       = Vector(
    AccessorObligation(
      s"$ImportSelectorSurface#parentImportExpression()Lorg/jetbrains/plugins/scala/lang/psi/api/toplevel/imports/ScImportExpr;",
      required = true
    ),
    AccessorObligation(s"$ImportSelectorSurface#reference()Lscala/Option;", required = true),
    AccessorObligation(s"$ImportSelectorSurface#givenTypeElement()Lscala/Option;", required = true)
  )
  private val StableReferenceAccessors      = Vector(
    AccessorObligation(s"$StableReferenceSurface#qualifier()Lscala/Option;", required = true),
    AccessorObligation(
      s"$StableReferenceSurface#nameId()Lcom/intellij/psi/PsiElement;",
      required = true
    )
  )
  private val SimpleTypeAccessors           = Vector(
    AccessorObligation(s"$SimpleTypeSurface#reference()Lscala/Option;", required = true),
    AccessorObligation(
      s"$SimpleTypeSurface#pathElement()Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScPathElement;",
      required = true
    ),
    AccessorObligation(s"$SimpleTypeSurface#isSingleton()Z", required = true, SurfaceFactKind.Method)
  )
  private val TypeProjectionAccessors       = Vector(
    AccessorObligation(
      s"$TypeProjectionSurface#typeElement()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement;",
      required = true
    ),
    AccessorObligation(s"$TypeProjectionSurface#nameId()Lcom/intellij/psi/PsiElement;", required = true),
    AccessorObligation(s"$TypeProjectionSurface#qualifier()Lscala/Option;", required = true)
  )
  private val LiteralTypeAccessors          = Vector(
    AccessorObligation(
      s"$LiteralTypeSurface#getLiteral()Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral;",
      required = true
    ),
    AccessorObligation(s"$LiteralTypeSurface#isSingleton()Z", required = true, SurfaceFactKind.Method)
  )
  private val ParenthesizedTypeAccessors    = Vector(
    AccessorObligation(s"$ParenthesizedTypeSurface#innerElement()Lscala/Option;", required = true),
    AccessorObligation(s"$ParenthesizedTypeSurface#sameTreeParent()Lscala/Option;", required = true)
  )
  private val LiteralValueAccessors         = Vector(
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#getValue()Ljava/lang/Object;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#contentText()Ljava/lang/String;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#isSimpleLiteral()Z",
      required = true,
      SurfaceFactKind.Method
    )
  )
  private val ParameterizedTypeAccessors    = Vector(
    AccessorObligation(
      s"$ParameterizedTypeSurface#typeElement()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement;",
      required = true
    ),
    AccessorObligation(
      s"$ParameterizedTypeSurface#typeArgList()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeArgs;",
      required = true
    )
  )
  private val TypeArgumentsAccessors        = Vector(
    AccessorObligation(s"$TypeArgumentsSurface#typeArgs()Lscala/collection/immutable/Seq;", required = true)
  )
  private val NamedTypeArgumentsAccessors   = Vector(
    AccessorObligation(
      s"$NamedTypeArgumentsSurface#logicalTypeArguments()Lscala/collection/immutable/Seq;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(
      s"$NamedTypeArgumentsSurface#namedTypeArguments()Lscala/collection/immutable/Seq;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(
      s"$NamedTypeArgumentsSurface#typeArgs()Lscala/collection/immutable/Seq;",
      required = true,
      SurfaceFactKind.Method
    )
  )
  private val NamedTypeArgumentAccessors    = Vector(
    AccessorObligation(s"$NamedTypeArgumentSurface#name()Lscala/Option;", required = true, SurfaceFactKind.Method),
    AccessorObligation(
      s"$NamedTypeArgumentSurface#nameElement()Lscala/Option;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(
      s"$NamedTypeArgumentSurface#typeElement()Lscala/Option;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(s"$NamedTypeArgumentSurface#isNamed()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(
      s"$NamedTypeArgumentSurface#type()Lscala/util/Either;",
      required = true,
      SurfaceFactKind.Method
    )
  )
  private val WildcardTypeAccessors         = Vector(
    AccessorObligation(s"$WildcardTypeSurface#lowerTypeElement()Lscala/Option;", required = true),
    AccessorObligation(s"$WildcardTypeSurface#upperTypeElement()Lscala/Option;", required = true)
  )
  private val InfixTypeAccessors            = Vector(
    AccessorObligation(
      s"$InfixTypeSurface#left()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement;",
      required = true
    ),
    AccessorObligation(s"$InfixTypeSurface#rightOption()Lscala/Option;", required = true),
    AccessorObligation(
      s"$InfixTypeSurface#operation()Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScStableCodeReference;",
      required = true
    )
  )
  private val ModifierListAccessors         = Vector(
    AccessorObligation(s"$ModifierListSurface#accessModifier()Lscala/Option;", required = true),
    AccessorObligation(s"$ModifierListSurface#modifiers()I", required = true, SurfaceFactKind.Method),
    AccessorObligation(
      s"$ModifierListSurface#modifiersOrdered()Lscala/collection/immutable/Seq;",
      required = true,
      SurfaceFactKind.Method
    )
  )
  private val AccessModifierAccessors       = Vector(
    AccessorObligation(s"$AccessModifierSurface#idText()Lscala/Option;", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$AccessModifierSurface#isPrivate()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$AccessModifierSurface#isProtected()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$AccessModifierSurface#isThis()Z", required = true, SurfaceFactKind.Method)
  )
  private val AnnotationsAccessors          = Vector(
    AccessorObligation(
      s"$AnnotationsSurface#getAnnotations()[Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScAnnotation;",
      required = true,
      SurfaceFactKind.Method
    )
  )
  private val AnnotationAccessors           = Vector(
    AccessorObligation(
      s"$AnnotationSurface#annotationExpr()Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScAnnotationExpr;",
      required = true
    ),
    AccessorObligation(
      s"$AnnotationSurface#constructorInvocation()Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScConstructorInvocation;",
      required = true
    ),
    AccessorObligation(
      s"$AnnotationSurface#typeElement()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement;",
      required = true
    )
  )
  private val AnnotationExprAccessors       = Vector(
    AccessorObligation(
      s"$AnnotationExprSurface#constructorInvocation()Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScConstructorInvocation;",
      required = true
    ),
    AccessorObligation(s"$AnnotationExprSurface#getAttributes()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(
      s"$AnnotationExprSurface#getAnnotationParameters()Lscala/collection/immutable/Seq;",
      required = true
    )
  )
  private val ConstructorAccessors          = Vector(
    AccessorObligation(
      s"$ConstructorSurface#typeElement()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement;",
      required = true
    ),
    AccessorObligation(s"$ConstructorSurface#simpleTypeElement()Lscala/Option;", required = true),
    AccessorObligation(s"$ConstructorSurface#typeArgList()Lscala/Option;", required = true),
    AccessorObligation(s"$ConstructorSurface#args()Lscala/Option;", required = true),
    AccessorObligation(s"$ConstructorSurface#arguments()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(s"$ConstructorSurface#reference()Lscala/Option;", required = true)
  )
  private val AnnotationArgumentsAccessors  = Vector(
    AccessorObligation(s"$AnnotationArgumentsSurface#exprs()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(s"$AnnotationArgumentsSurface#isUsing()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$AnnotationArgumentsSurface#isArgsInParens()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$AnnotationArgumentsSurface#getArgsCount()I", required = true, SurfaceFactKind.Method)
  )
  private val ExpressionPayloadAccessors    = Vector(
    AccessorObligation(s"$ExpressionSurface#type()Lscala/util/Either;", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$ExpressionSurface#innerType()Lscala/util/Either;", required = true, SurfaceFactKind.Method)
  )
  private val FunctionAccessors             = Vector(
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/ScFunction#hasAssign()Z",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/ScFunction#paramClauses()Lorg/jetbrains/plugins/scala/lang/psi/api/statements/params/ScParameters;",
      required = true
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/ScFunction#returnTypeElement()Lscala/Option;",
      required = true
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/toplevel/ScTypeParametersOwner#typeParameters()Lscala/collection/immutable/Seq;",
      required = true
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/toplevel/ScTypeParametersOwner#typeParametersClause()Lscala/Option;",
      required = true
    )
  )
  private val FunctionDefinitionAccessors   =
    AccessorObligation(s"$FunctionDefinitionSurface#body()Lscala/Option;", required = true) +: FunctionAccessors
  private val FunctionDeclarationAccessors  = FunctionAccessors
  private val PropertyDefinitionAccessors   = Vector(
    AccessorObligation(
      s"$PatternDefinitionSurface#pList()Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScPatternList;",
      required = true
    ),
    AccessorObligation(s"$PatternDefinitionSurface#expr()Lscala/Option;", required = true),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/ScValueOrVariable#typeElement()Lscala/Option;",
      required = true
    )
  )
  private val VariableDefinitionAccessors   = Vector(
    AccessorObligation(
      s"$VariableDefinitionSurface#pList()Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScPatternList;",
      required = true
    ),
    AccessorObligation(s"$VariableDefinitionSurface#expr()Lscala/Option;", required = true),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/ScValueOrVariable#typeElement()Lscala/Option;",
      required = true
    )
  )
  private val PropertyDeclarationAccessors  = Vector(
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/ScValueOrVariableDeclaration#getIdList()Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScIdList;",
      required = true
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/ScValueOrVariable#typeElement()Lscala/Option;",
      required = true
    )
  )
  private val PatternListAccessors          = Vector(
    AccessorObligation(s"$PatternListSurface#bindings()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(s"$PatternListSurface#simplePatterns()Z", required = true, SurfaceFactKind.Method)
  )
  private val ReferencePatternAccessors     = Vector(
    AccessorObligation(s"$ReferencePatternSurface#nameId()Lcom/intellij/psi/PsiElement;", required = true)
  )
  private val IdentifierListAccessors       = Vector(
    AccessorObligation(s"$IdentifierListSurface#fieldIds()Lscala/collection/immutable/Seq;", required = true)
  )
  private val FieldIdAccessors              = Vector(
    AccessorObligation(s"$FieldIdSurface#nameId()Lcom/intellij/psi/PsiElement;", required = true)
  )
  private val TypeAliasDeclarationAccessors = Vector(
    AccessorObligation(s"$TypeAliasDeclarationSurface#nameId()Lcom/intellij/psi/PsiElement;", required = true),
    AccessorObligation(s"$TypeAliasDeclarationSurface#lowerTypeElement()Lscala/Option;", required = true),
    AccessorObligation(s"$TypeAliasDeclarationSurface#upperTypeElement()Lscala/Option;", required = true)
  )
  private val TypeAliasDefinitionAccessors  = Vector(
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/ScTypeAliasDefinition#aliasedTypeElement()Lscala/Option;",
      required = true
    )
  )
  private val ParameterAccessors            = Vector(
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/params/ScParameter#typeElement()Lscala/Option;",
      required = true
    )
  )
  private val ParameterTypeAccessors        = Vector(
    AccessorObligation(
      s"$ParameterTypeSurface#typeElement()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement;",
      required = true
    )
  )
  private val TemplateParentsAccessors      = Vector(
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/toplevel/templates/ScTemplateParents#typeElements()Lscala/collection/immutable/Seq;",
      required = true
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/toplevel/templates/ScTemplateParents#allTypeElements()Lscala/collection/immutable/Seq;",
      required = true
    )
  )
  private val SelfTypeAccessors             = Vector(
    AccessorObligation(s"$SelfTypeSurface#typeElement()Lscala/Option;", required = true)
  )
  private val DerivesClauseAccessors        = Vector(
    AccessorObligation(s"$DerivesClauseSurface#derivedReferences()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(
      s"$DerivesClauseSurface#owner()Lorg/jetbrains/plugins/scala/lang/psi/api/toplevel/typedef/ScDerivesClauseOwner;",
      required = true
    )
  )

  private val GivenSelectorBoundAnchor        = InventoryAncestor(
    InventoryKind.Node,
    "ImportSelector",
    Vector(CatalogPathSegment.NamedField("bound"))
  )
  private val GivenTypeProductionIds          = Set(
    "import-selector-bound-type",
    "import-selector-bound-applied-type",
    "import-selector-given-bound-qualified-type",
    "type-atom-projection",
    "type-atom-singleton-ident",
    "type-atom-singleton-select",
    "type-atom-literal",
    "type-atom-parenthesized",
    "import-selector-given-bound-wildcard-type",
    "import-selector-given-bound-infix-type"
  )
  private val TypeAtomProductionIds           = Set(
    "import-selector-bound-type",
    "ordinary-applied-type",
    "import-selector-given-bound-qualified-type",
    "type-atom-projection",
    "type-atom-singleton-ident",
    "type-atom-singleton-select",
    "type-atom-literal",
    "type-atom-parenthesized"
  )
  private val SimpleTypeAliasProductionIds    = Set(
    "definition-simple-ident-type-alias",
    "definition-simple-select-type-alias",
    "definition-simple-singleton-type-alias",
    "definition-simple-literal-type-alias",
    "definition-simple-parenthesized-type-alias",
    "definition-applied-type-alias"
  )
  private val GivenTypeQualifierProductionIds = Set(
    "import-selector-given-bound-qualifier-ident",
    "import-selector-given-bound-qualifier-select"
  )

  private val OwnerTypeAnchors = Vector(
    InventoryAncestor(InventoryKind.Node, "DefDef", Vector(CatalogPathSegment.NamedField("tpt"))),
    InventoryAncestor(InventoryKind.Node, "ValDef", Vector(CatalogPathSegment.NamedField("tpt"))),
    InventoryAncestor(InventoryKind.Node, "TypeDef", Vector(CatalogPathSegment.NamedField("rhs"))),
    InventoryAncestor(
      InventoryKind.Node,
      "Template",
      Vector(CatalogPathSegment.NamedField("preParentsOrDerived"), CatalogPathSegment.RepeatedElement)
    )
  )

  private def givenTypeOccurrences: Vector[CompilerProductionContextPattern] =
    val direct = CompilerProductionContextPattern(
      ContextPattern.Parent(
        InventoryKind.Node,
        "ImportSelector",
        Vector(CatalogPathSegment.NamedField("bound"))
      ),
      SourceClassification.SourceReachable
    )
    val nested = Vector(
      "AppliedTypeTree" -> Vector(CatalogPathSegment.NamedField("tpt")),
      "AppliedTypeTree" -> Vector(
        CatalogPathSegment.NamedField("args"),
        CatalogPathSegment.RepeatedElement
      ),
      "InfixOp"         -> Vector(CatalogPathSegment.NamedField("left")),
      "InfixOp"         -> Vector(CatalogPathSegment.NamedField("right")),
      "TypeBoundsTree"  -> Vector(CatalogPathSegment.NamedField("lo")),
      "TypeBoundsTree"  -> Vector(CatalogPathSegment.NamedField("hi")),
      "Parens"          -> Vector(CatalogPathSegment.NamedField("t"))
    ).map: (owner, path) =>
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchor(InventoryKind.Node, owner, path, GivenSelectorBoundAnchor),
        SourceClassification.SourceReachable
      )
    direct +: nested

  private def typeAtomOccurrences: Vector[CompilerProductionContextPattern] =
    val direct              = OwnerTypeAnchors.map(anchor =>
      CompilerProductionContextPattern(
        ContextPattern.Parent(anchor.ownerKind, anchor.ownerPrefix, anchor.path),
        SourceClassification.SourceReachable
      )
    )
    val nestedPaths         = Vector("Parens" -> Vector(CatalogPathSegment.NamedField("t")))
    val appliedConstructors = OwnerTypeAnchors.map: anchor =>
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchor(
          InventoryKind.Node,
          "AppliedTypeTree",
          Vector(CatalogPathSegment.NamedField("tpt")),
          anchor
        ),
        SourceClassification.SourceReachable
      )
    givenTypeOccurrences ++ direct ++ appliedConstructors ++ OwnerTypeAnchors.flatMap(anchor =>
      nestedPaths.map: (owner, path) =>
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchor(InventoryKind.Node, owner, path, anchor),
          SourceClassification.SourceReachable
        )
    )

  private def appliedTypeRootOccurrences: Vector[CompilerProductionContextPattern] =
    OwnerTypeAnchors.map: anchor =>
      CompilerProductionContextPattern(
        ContextPattern.Parent(anchor.ownerKind, anchor.ownerPrefix, anchor.path),
        SourceClassification.SourceReachable
      )

  private def appliedTypeChildOccurrences(field: String): Vector[CompilerProductionContextPattern] =
    OwnerTypeAnchors.map: anchor =>
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchor(
          InventoryKind.Node,
          "AppliedTypeTree",
          Vector(CatalogPathSegment.NamedField(field)) ++
            Option.when(field == "args")(CatalogPathSegment.RepeatedElement),
          anchor
        ),
        SourceClassification.SourceReachable
      )

  private def appliedTypeProduction(
      id: String,
      occurrences: Vector[CompilerProductionContextPattern],
      additionalRoles: Set[GrammarRoleId]
  ): Scala3PsiProduction =
    Scala3PsiProduction(
      id = id,
      grammarRoleId = GrammarRoleId.AppliedType,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "AppliedTypeTree",
        Vector(
          CompilerFieldPattern("tpt", CatalogValuePattern.Node),
          CompilerFieldPattern("args", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
        ),
        occurrences
      ),
      dispositions = Vector(
        FieldDisposition("tpt", FieldDispositionKind.Child),
        FieldDisposition("args", FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration(
          "constructor",
          "tpt",
          ChildCardinality.ExactlyOne,
          "import-selector-bound-type",
          (TypeAtomProductionIds + "type-argument-applied") - "import-selector-bound-type"
        ),
        ChildDeclaration(
          "arguments",
          "args",
          ChildCardinality.Repeated(1, None),
          "type-argument-ident",
          Set("type-argument-applied")
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "type-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = ParameterizedTypeSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = ParameterizedTypeAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(
        LocalOutputCompositeTemplate(
          Vector(
            outputComposite(
              "parameterized",
              None,
              OutputRangeDeclaration.CompilerPosition,
              PsiOutputRoleId.ParameterizedType,
              ParameterizedTypeSurface,
              ParameterizedTypeAccessors
            ),
            outputComposite(
              "arguments",
              Some("parameterized"),
              OutputRangeDeclaration.BoundaryDerived(
                OutputBoundary
                  .ChildEnd("constructor", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly),
                OutputBoundary.ProductionEnd()
              ),
              PsiOutputRoleId.TypeArguments,
              TypeArgumentsSurface,
              TypeArgumentsAccessors
            )
          ),
          Map("constructor" -> Some("parameterized"), "arguments" -> Some("arguments"))
        )
      ),
      additionalGrammarRoleIds = additionalRoles + GrammarRoleId.TypeArgumentList
    )

  private val positionalTypeArgumentProduction = Scala3PsiProduction(
    id = "type-argument-ident",
    grammarRoleId = GrammarRoleId.PositionalTypeArgument,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
      appliedTypeChildOccurrences("args")
    ),
    dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "type-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = SimpleTypeSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = SimpleTypeAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "type",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.SimpleType,
            SimpleTypeSurface,
            SimpleTypeAccessors
          ),
          outputComposite(
            "reference",
            Some("type"),
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.StableReference,
            StableReferenceSurface,
            StableReferenceAccessors
          )
        ),
        Map.empty
      )
    ),
    outputRoleId = None
  )

  private val importSelectorAppliedTypeProduction = appliedTypeProduction(
    "import-selector-bound-applied-type",
    givenTypeOccurrences,
    Set.empty
  ).copy(
    children = Vector(
      ChildDeclaration(
        "constructor",
        "tpt",
        ChildCardinality.ExactlyOne,
        "import-selector-bound-type",
        GivenTypeProductionIds - "import-selector-bound-type"
      ),
      ChildDeclaration(
        "arguments",
        "args",
        ChildCardinality.Repeated(1, None),
        "import-selector-bound-type",
        GivenTypeProductionIds - "import-selector-bound-type"
      )
    )
  )

  private def givenTypeQualifierOccurrence(owner: String): Vector[CompilerProductionContextPattern] =
    Vector(
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchor(
          InventoryKind.Node,
          owner,
          Vector(CatalogPathSegment.NamedField("qualifier")),
          GivenSelectorBoundAnchor
        ),
        SourceClassification.SourceReachable
      )
    )

  private def typeAtomQualifierOccurrences(owner: String): Vector[CompilerProductionContextPattern] =
    givenTypeQualifierOccurrence(owner) ++ OwnerTypeAnchors.map(anchor =>
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchor(
          InventoryKind.Node,
          owner,
          Vector(CatalogPathSegment.NamedField("qualifier")),
          anchor
        ),
        SourceClassification.SourceReachable
      )
    )

  private def typeElementTemplate(
      outputRole: PsiOutputRoleId,
      surface: String,
      accessors: Vector[AccessorObligation],
      childRoles: String*
  ): LocalOutputCompositeTemplate =
    typeElementTemplateWithRange(
      outputRole,
      surface,
      accessors,
      OutputRangeDeclaration.CompilerPosition,
      childRoles*
    )

  private def typeElementTemplateWithRange(
      outputRole: PsiOutputRoleId,
      surface: String,
      accessors: Vector[AccessorObligation],
      range: OutputRangeDeclaration,
      childRoles: String*
  ): LocalOutputCompositeTemplate =
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "type",
          None,
          range,
          outputRole,
          surface,
          accessors
        )
      ),
      childRoles.map(_ -> Some("type")).toMap
    )

  private def qualifiedTypeTemplate: LocalOutputCompositeTemplate =
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "type",
          None,
          OutputRangeDeclaration.CompilerPosition,
          PsiOutputRoleId.SimpleType,
          SimpleTypeSurface,
          SimpleTypeAccessors
        ),
        outputComposite(
          "reference",
          Some("type"),
          OutputRangeDeclaration.CompilerPosition,
          PsiOutputRoleId.StableReference,
          StableReferenceSurface,
          StableReferenceAccessors
        )
      ),
      Map("qualifier" -> Some("reference"))
    )

  private def stableReferenceTemplate(childRoles: String*): LocalOutputCompositeTemplate =
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "reference",
          None,
          OutputRangeDeclaration.CompilerPosition,
          PsiOutputRoleId.StableReference,
          StableReferenceSurface,
          StableReferenceAccessors
        )
      ),
      childRoles.map(_ -> Some("reference")).toMap
    )

  private def transparentTemplate(childRoles: String*): LocalOutputCompositeTemplate =
    LocalOutputCompositeTemplate(Vector.empty, childRoles.map(_ -> None).toMap)

  private def packageTemplate(
      childRoles: Vector[String],
      bodyRole: Option[String]
  ): LocalOutputCompositeTemplate =
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "package",
          None,
          OutputRangeDeclaration.CompilerPositionWithBodyLayoutOrEndMarker(
            "package-reference",
            bodyRole,
            ClosedSourceLexicalKind.LeftBrace,
            ClosedSourceLexicalKind.RightBrace,
            ClosedSourceLexicalKind.Colon
          ),
          PsiOutputRoleId.PackageStatement,
          PackageSurface,
          PackageAccessors
        ),
        outputComposite(
          "end",
          Some("package"),
          OutputRangeDeclaration.CompilerEndMarker,
          PsiOutputRoleId.EndStatement,
          EndSurface,
          EndAccessors
        ).copy(requiresCompilerEndMarker = true)
      ),
      childRoles.map(_ -> Some("package")).toMap
    )

  private def selectorTemplate(
      range: OutputRangeDeclaration,
      childRoles: String*
  ): LocalOutputCompositeTemplate =
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "selector",
          None,
          range,
          PsiOutputRoleId.ImportSelector,
          ImportSelectorSurface,
          ImportSelectorAccessors
        )
      ),
      childRoles.map(_ -> Some("selector")).toMap
    )

  private def namedSelectorTemplate: LocalOutputCompositeTemplate =
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "selector",
          None,
          OutputRangeDeclaration.CompilerPosition,
          PsiOutputRoleId.ImportSelector,
          ImportSelectorSurface,
          ImportSelectorAccessors
        ),
        outputComposite(
          "reference",
          Some("selector"),
          OutputRangeDeclaration.BoundaryDerived(
            OutputBoundary.ChildStart(
              "imported",
              ChildOccurrenceSelector.First,
              PositionProvenancePolicy.SourceDerivedOnly
            ),
            OutputBoundary.ChildEnd(
              "imported",
              ChildOccurrenceSelector.First,
              PositionProvenancePolicy.SourceDerivedOnly
            )
          ),
          PsiOutputRoleId.StableReference,
          StableReferenceSurface,
          StableReferenceAccessors
        )
      ),
      Map("imported" -> Some("reference"), "renamed" -> Some("selector"), "bound" -> Some("selector"))
    )

  private def selectorSetStatementTemplate(
      statementRole: PsiOutputRoleId,
      statementSurface: String,
      statementAccessors: Vector[AccessorObligation]
  ): LocalOutputCompositeTemplate =
    val pathStart     = OutputBoundary.ChildStart(
      "path",
      ChildOccurrenceSelector.First,
      PositionProvenancePolicy.SourceDerivedOnly
    )
    val selectorStart = OutputBoundary.EvidenceBoundaryAfterChild(
      "path",
      ChildOccurrenceSelector.First,
      "selectors",
      ChildOccurrenceSelector.First,
      Vector("{", "given"),
      PositionProvenancePolicy.SourceDerivedOnly,
      fallbackToFollowingChildStart = true
    )
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "statement",
          None,
          OutputRangeDeclaration.CompilerPosition,
          statementRole,
          statementSurface,
          statementAccessors
        ),
        outputComposite(
          "expression",
          Some("statement"),
          OutputRangeDeclaration.BoundaryDerived(
            pathStart,
            OutputBoundary.ProductionEnd()
          ),
          PsiOutputRoleId.ImportExpression,
          ImportExpressionSurface,
          ImportExpressionAccessors
        ),
        outputComposite(
          "selectors",
          Some("expression"),
          OutputRangeDeclaration.BoundaryDerived(
            selectorStart,
            OutputBoundary.ProductionEnd()
          ),
          PsiOutputRoleId.ImportSelectorSet,
          ImportSelectorsSurface,
          ImportSelectorsAccessors
        )
      ),
      Map("path" -> Some("expression"), "selectors" -> Some("selectors"))
    )

  private def directStatementTemplate(
      outerReference: Boolean,
      statementRole: PsiOutputRoleId,
      statementSurface: String,
      statementAccessors: Vector[AccessorObligation]
  ): LocalOutputCompositeTemplate =
    val pathStart = OutputBoundary.ChildStart(
      "path",
      ChildOccurrenceSelector.First,
      PositionProvenancePolicy.SourceDerivedOnly
    )

    val selectorEnd     = OutputBoundary.ChildEnd(
      "selectors",
      ChildOccurrenceSelector.Last,
      PositionProvenancePolicy.PositionedIncludingSynthetic
    )
    val expressionRange = OutputRangeDeclaration.BoundaryDerived(pathStart, selectorEnd)
    val base            = Vector(
      outputComposite(
        "statement",
        None,
        OutputRangeDeclaration.CompilerPosition,
        statementRole,
        statementSurface,
        statementAccessors
      ),
      outputComposite(
        "expression",
        Some("statement"),
        expressionRange,
        PsiOutputRoleId.ImportExpression,
        ImportExpressionSurface,
        ImportExpressionAccessors
      )
    )
    val composites      =
      if outerReference then
        base :+ outputComposite(
          "reference",
          Some("expression"),
          expressionRange,
          PsiOutputRoleId.StableReference,
          StableReferenceSurface,
          StableReferenceAccessors
        )
      else base
    LocalOutputCompositeTemplate(
      composites,
      Map(
        "path"      -> Some(if outerReference then "reference" else "expression"),
        "selectors" -> Some(if outerReference then "reference" else "expression")
      )
    )

  private def selectorOwnedStatementTemplate(
      statementRole: PsiOutputRoleId,
      statementSurface: String,
      statementAccessors: Vector[AccessorObligation]
  ): LocalOutputCompositeTemplate =
    val selectorStart =
      OutputBoundary.ChildStart("selectors", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly)
    val selectorEnd   =
      OutputBoundary.ChildEnd("selectors", ChildOccurrenceSelector.Last, PositionProvenancePolicy.SourceDerivedOnly)
    val range         = OutputRangeDeclaration.BoundaryDerived(selectorStart, selectorEnd)
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "statement",
          None,
          OutputRangeDeclaration.CompilerPosition,
          statementRole,
          statementSurface,
          statementAccessors
        ),
        outputComposite(
          "expression",
          Some("statement"),
          range,
          PsiOutputRoleId.ImportExpression,
          ImportExpressionSurface,
          ImportExpressionAccessors
        ),
        outputComposite(
          "selectors",
          Some("expression"),
          range,
          PsiOutputRoleId.ImportSelectorSet,
          ImportSelectorsSurface,
          ImportSelectorsAccessors
        )
      ),
      Map("path" -> Some("expression"), "selectors" -> Some("selectors"))
    )

  private def statementProduction(
      id: String,
      compilerProduction: String,
      grammarRole: GrammarRoleId,
      outputRole: PsiOutputRoleId,
      statementSurface: String,
      statementAccessors: Vector[AccessorObligation]
  ): Scala3PsiProduction = Scala3PsiProduction(
    id = id,
    grammarRoleId = grammarRole,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      compilerProduction,
      Vector(
        CompilerFieldPattern("expr", CatalogValuePattern.Node),
        CompilerFieldPattern("selectors", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "PackageDef",
            Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement)
          ),
          SourceClassification.SourceReachable
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("expr", FieldDispositionKind.Child),
      FieldDisposition("selectors", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "path",
        "expr",
        ChildCardinality.ExactlyOne,
        "import-path-reference",
        Set("import-path-identifier-reference", "import-expression-absent")
      ),
      ChildDeclaration(
        "selectors",
        "selectors",
        ChildCardinality.Repeated(1, None),
        "import-selector-direct",
        Set("import-selector-braced")
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "statement-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = statementSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = statementAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRoleId = None,
    outputRealizations = Vector(
      OutputRealization(
        "selector-owned",
        Vector(
          ChildOutcomeCondition(
            "path",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Production("import-expression-absent")
          ),
          ChildOutcomeCondition(
            "selectors",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Realization("braced-alias")
          )
        ),
        selectorOwnedStatementTemplate(outputRole, statementSurface, statementAccessors)
      ),
      OutputRealization(
        "plain",
        Vector(
          ChildOutcomeCondition(
            "selectors",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Realization("direct-plain")
          )
        ),
        directStatementTemplate(
          outerReference = true,
          outputRole,
          statementSurface,
          statementAccessors
        )
      ),
      OutputRealization(
        "wildcard",
        Vector(
          ChildOutcomeCondition(
            "selectors",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Realization("direct-wildcard")
          )
        ),
        directStatementTemplate(
          outerReference = false,
          outputRole,
          statementSurface,
          statementAccessors
        )
      ),
      OutputRealization(
        "given-direct",
        Vector(
          ChildOutcomeCondition(
            "selectors",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Realization("direct-given")
          )
        ),
        selectorSetStatementTemplate(outputRole, statementSurface, statementAccessors)
      ),
      OutputRealization(
        "named-selectors",
        Vector(
          ChildOutcomeCondition(
            "selectors",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Realization("braced-named")
          )
        ),
        selectorSetStatementTemplate(outputRole, statementSurface, statementAccessors)
      ),
      OutputRealization(
        "given-selectors",
        Vector(
          ChildOutcomeCondition(
            "selectors",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Realization("braced-alias")
          )
        ),
        selectorSetStatementTemplate(outputRole, statementSurface, statementAccessors)
      ),
      OutputRealization(
        "hidden-selectors",
        Vector(
          ChildOutcomeCondition(
            "selectors",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Realization("braced-hidden")
          )
        ),
        selectorSetStatementTemplate(outputRole, statementSurface, statementAccessors)
      ),
      OutputRealization(
        "wildcard-selectors",
        Vector(
          ChildOutcomeCondition(
            "selectors",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Realization("braced-wildcard")
          )
        ),
        selectorSetStatementTemplate(outputRole, statementSurface, statementAccessors)
      ),
      OutputRealization(
        "given-braced-selectors",
        Vector(
          ChildOutcomeCondition(
            "selectors",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Realization("braced-given")
          )
        ),
        selectorSetStatementTemplate(outputRole, statementSurface, statementAccessors)
      )
    )
  )

  private def packageProduction(id: String, body: Boolean): Scala3PsiProduction =
    val packageIds       = Set("file-package", "file-package-top-statements")
    val templateOwnerIds = Set(
      "template-class-definition",
      "template-trait-definition",
      "template-object-definition",
      "template-enum-definition",
      "definition-function-untyped",
      "definition-val-untyped",
      "definition-var-untyped"
    ) ++ SimpleTypeAliasProductionIds
    val children         = Vector(
      ChildDeclaration(
        "package-reference",
        "pid",
        ChildCardinality.ExactlyOne,
        "package-stable-reference",
        Set("package-stable-identifier-reference")
      )
    ) ++ Option.when(body)(
      ChildDeclaration(
        "package-statements",
        "stats",
        ChildCardinality.Grouped(1, None),
        "import-statement",
        Set("export-statement") ++ packageIds ++ templateOwnerIds
      )
    )
    Scala3PsiProduction(
      id = id,
      grammarRoleId = GrammarRoleId.PackageClause,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "PackageDef",
        Vector(
          CompilerFieldPattern("pid", CatalogValuePattern.Node),
          CompilerFieldPattern(
            "stats",
            if body then CatalogValuePattern.Repeated(CatalogValuePattern.Node)
            else CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)
          )
        ),
        Vector(
          CompilerProductionContextPattern(ContextPattern.Root, SourceClassification.SourceReachable),
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              "PackageDef",
              Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement)
            ),
            SourceClassification.SourceReachable
          )
        )
      ),
      dispositions = Vector(
        FieldDisposition("pid", FieldDispositionKind.Child),
        FieldDisposition("stats", if body then FieldDispositionKind.Child else FieldDispositionKind.SemanticOnly)
      ),
      children = children,
      terminals = Vector(
        TerminalDeclaration(
          "package-text",
          TerminalIntervalSelector.LocalOutput("package"),
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "root-remainder",
          TerminalIntervalSelector.RootOutsideLocalOutput("package"),
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.Optional,
          PsiOutputRoleId.SourceTerminal,
          ownsStructuralEvidence = Some(false)
        ),
        TerminalDeclaration(
          "end-keyword",
          TerminalIntervalSelector.CompilerEndMarkerKeyword,
          TerminalLeafTarget.Token(NativePsiElementBindings.EndKeywordTokenSurface, Some("end")),
          OccurrenceCardinality.Optional,
          PsiOutputRoleId.EndKeyword
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = PackageSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = PackageAccessors,
      persistence = PersistenceObligations.Required(
        PackagePersistenceSurfaces.Stub,
        PackagePersistenceSurfaces.Serializer,
        Vector(PackagePersistenceSurfaces.FqnIndex),
        ImportPersistenceSurfaces.SelfNavigation
      ),
      navigation = Some(NavigationObligation.Self),
      outputTemplate = Some(packageTemplate(children.map(_.roleId), Option.when(body)("package-statements"))),
      outputRoleId = None
    )

  private val ModifierOwnerPrefixes = Vector("TypeDef", "ModuleDef", "DefDef", "ValDef", "PatDef", "Template")
  private val ModifierPath          = Vector(CatalogPathSegment.NamedField("mods"))
  private val ModifierChildrenPath  = Vector(
    CatalogPathSegment.NamedField("mods"),
    CatalogPathSegment.NestedProduct("Modifiers")
  )

  private def modifierOccurrences(classification: SourceClassification) = ModifierOwnerPrefixes.map(owner =>
    CompilerProductionContextPattern(
      ContextPattern.Parent(InventoryKind.Node, owner, ModifierPath),
      classification
    )
  )

  private def annotationAnchor(owner: String) = InventoryAncestor(
    InventoryKind.Node,
    owner,
    ModifierChildrenPath ++ Vector(
      CatalogPathSegment.NamedField("annotations"),
      CatalogPathSegment.RepeatedElement
    )
  )

  private def annotationOccurrences(classifications: SourceClassification*) =
    ModifierOwnerPrefixes.flatMap(owner =>
      classifications.map(classification =>
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            owner,
            annotationAnchor(owner).path
          ),
          classification
        )
      )
    )

  private def annotationChildOccurrences(
      owner: String,
      path: Vector[CatalogPathSegment],
      directAncestors: Vector[InventoryAncestor],
      classification: SourceClassification
  ) = ModifierOwnerPrefixes.map(definition =>
    CompilerProductionContextPattern(
      ContextPattern.ParentWithAncestorPrefix(
        InventoryKind.Node,
        owner,
        path,
        directAncestors :+ annotationAnchor(definition)
      ),
      classification
    )
  )

  private val ApplyFunAncestor           = InventoryAncestor(
    InventoryKind.Node,
    "Apply",
    Vector(CatalogPathSegment.NamedField("fun"))
  )
  private val SelectQualifierAncestor    = InventoryAncestor(
    InventoryKind.Node,
    "Select",
    Vector(CatalogPathSegment.NamedField("qualifier"))
  )
  private val NewTypeAncestor            = InventoryAncestor(
    InventoryKind.Node,
    "New",
    Vector(CatalogPathSegment.NamedField("tpt"))
  )
  private val ModifierChildProductionIds = Set(
    "modifier-access-private",
    "modifier-access-protected",
    "modifier-keyword-abstract",
    "modifier-keyword-final",
    "modifier-keyword-sealed",
    "modifier-keyword-implicit",
    "modifier-keyword-lazy",
    "modifier-keyword-override",
    "modifier-keyword-transparent",
    "modifier-keyword-inline",
    "modifier-keyword-infix",
    "modifier-keyword-open",
    "modifier-keyword-opaque",
    "modifier-keyword-given",
    "modifier-keyword-var"
  )

  private def modifierTemplate(withAnnotations: Boolean, withModifiers: Boolean): LocalOutputCompositeTemplate =
    val modifierRange =
      if withModifiers then
        OutputRangeDeclaration.BoundaryDerivedWithTrailingBalancedBrackets(
          OutputBoundary.ChildStart(
            "modifiers",
            ChildOccurrenceSelector.First,
            PositionProvenancePolicy.PositionedIncludingSynthetic
          ),
          OutputBoundary.ChildEnd(
            "modifiers",
            ChildOccurrenceSelector.Last,
            PositionProvenancePolicy.PositionedIncludingSynthetic
          )
        )
      else
        val end = OutputBoundary.ChildEnd(
          "annotations",
          ChildOccurrenceSelector.Last,
          PositionProvenancePolicy.PositionedIncludingSynthetic
        )
        OutputRangeDeclaration.BoundaryDerived(end, end)
    val annotations   = Option.when(withAnnotations)(
      outputComposite(
        "annotations",
        None,
        OutputRangeDeclaration.BoundaryDerived(
          OutputBoundary.ChildStart(
            "annotations",
            ChildOccurrenceSelector.First,
            PositionProvenancePolicy.PositionedIncludingSynthetic
          ),
          OutputBoundary.ChildEnd(
            "annotations",
            ChildOccurrenceSelector.Last,
            PositionProvenancePolicy.PositionedIncludingSynthetic
          )
        ),
        PsiOutputRoleId.Annotations,
        AnnotationsSurface,
        AnnotationsAccessors
      )
    )
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "modifiers",
          None,
          modifierRange,
          PsiOutputRoleId.ModifierList,
          ModifierListSurface,
          ModifierListAccessors
        )
      ) ++ annotations,
      Map(
        "annotations" -> Some(if withAnnotations then "annotations" else "modifiers"),
        "modifiers"   -> Some("modifiers")
      )
    )

  private def modifiersProduction(
      id: String,
      annotations: CatalogValuePattern,
      modifiers: CatalogValuePattern,
      classification: SourceClassification,
      template: LocalOutputCompositeTemplate,
      minimumAnnotations: Int,
      minimumModifiers: Int
  ): Scala3PsiProduction = Scala3PsiProduction(
    id = id,
    grammarRoleId = GrammarRoleId.Modifiers,
    pattern = CompilerProductionPattern(
      InventoryKind.Product,
      "Modifiers",
      Vector(
        CompilerFieldPattern("flags", CatalogValuePattern.Scalar("LongInteger")),
        CompilerFieldPattern("privateWithin", CatalogValuePattern.Name),
        CompilerFieldPattern("annotations", annotations),
        CompilerFieldPattern("mods", modifiers)
      ),
      modifierOccurrences(classification)
    ),
    dispositions = Vector(
      FieldDisposition("flags", FieldDispositionKind.SemanticOnly),
      FieldDisposition("privateWithin", FieldDispositionKind.SemanticOnly),
      FieldDisposition("annotations", FieldDispositionKind.Child),
      FieldDisposition("mods", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "annotations",
        "annotations",
        ChildCardinality.Repeated(minimumAnnotations, None),
        "annotation-apply-simple",
        Set("annotation-apply-arguments")
      ),
      ChildDeclaration(
        "modifiers",
        "mods",
        ChildCardinality.Repeated(minimumModifiers, None),
        ModifierChildProductionIds.head,
        ModifierChildProductionIds.tail
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "modifier-text",
        TerminalIntervalSelector.LocalOutput("modifiers"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ModifierListSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = ModifierListAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(template),
    outputRoleId = None,
    additionalGrammarRoleIds = Option
      .when(annotations != CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node))(
        GrammarRoleId.Annotations
      )
      .toSet
  )

  private def transparentProduction(
      id: String,
      role: GrammarRoleId,
      prefix: String,
      fields: Vector[CompilerFieldPattern],
      occurrences: Vector[CompilerProductionContextPattern],
      dispositions: Vector[FieldDisposition],
      children: Vector[ChildDeclaration]
  ): Scala3PsiProduction = Scala3PsiProduction(
    id,
    role,
    CompilerProductionPattern(InventoryKind.Node, prefix, fields, occurrences),
    dispositions,
    children,
    Vector.empty,
    Vector(LayoutAlternative.None),
    RecoveryPolicy.Reject,
    StableReferenceSurface,
    TargetRequirement.Native,
    Vector.empty,
    PersistenceObligations.NotApplicable,
    outputTemplate = Some(transparentTemplate(children.map(_.roleId)*)),
    outputRoleId = None
  )

  private def annotationTemplate(withArguments: Boolean): LocalOutputCompositeTemplate =
    val whole = OutputRangeDeclaration.CompilerPositionWithPolicy(
      PositionProvenancePolicy.PositionedIncludingSynthetic
    )
    val body  = OutputRangeDeclaration.BoundaryDerived(
      OutputBoundary.Advance(
        OutputBoundary.ProductionStart(PositionProvenancePolicy.PositionedIncludingSynthetic),
        1
      ),
      OutputBoundary.ProductionEnd(PositionProvenancePolicy.PositionedIncludingSynthetic)
    )
    val args  = Option.when(withArguments)(
      outputComposite(
        "arguments",
        Some("constructor"),
        OutputRangeDeclaration.BoundaryDerived(
          OutputBoundary.ChildEnd(
            "fun",
            ChildOccurrenceSelector.First,
            PositionProvenancePolicy.PositionedIncludingSynthetic
          ),
          OutputBoundary.ProductionEnd(PositionProvenancePolicy.PositionedIncludingSynthetic)
        ),
        PsiOutputRoleId.AnnotationArguments,
        AnnotationArgumentsSurface,
        AnnotationArgumentsAccessors
      )
    )
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite("annotation", None, whole, PsiOutputRoleId.Annotation, AnnotationSurface, AnnotationAccessors),
        outputComposite(
          "expression",
          Some("annotation"),
          body,
          PsiOutputRoleId.AnnotationExpr,
          AnnotationExprSurface,
          AnnotationExprAccessors
        ),
        outputComposite(
          "constructor",
          Some("expression"),
          body,
          PsiOutputRoleId.ConstructorInvocation,
          ConstructorSurface,
          ConstructorAccessors
        )
      ) ++ args,
      Map("fun" -> Some("constructor"), "args" -> Some(if withArguments then "arguments" else "constructor"))
    )

  private def annotationApplyProduction(id: String, withArguments: Boolean): Scala3PsiProduction =
    val arguments =
      if withArguments then CatalogValuePattern.Repeated(CatalogValuePattern.Node)
      else CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)
    Scala3PsiProduction(
      id = id,
      grammarRoleId = GrammarRoleId.Annotation,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Apply",
        Vector(
          CompilerFieldPattern("fun", CatalogValuePattern.Node),
          CompilerFieldPattern("args", arguments)
        ),
        annotationOccurrences(SourceClassification.SourceReachable, SourceClassification.Synthetic)
      ),
      dispositions = Vector(
        FieldDisposition("fun", FieldDispositionKind.Child),
        FieldDisposition("args", FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration("fun", "fun", ChildCardinality.ExactlyOne, "annotation-constructor-select"),
        ChildDeclaration(
          "args",
          "args",
          ChildCardinality.Repeated(if withArguments then 1 else 0, None),
          "annotation-argument-literal-payload"
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "annotation-text",
          TerminalIntervalSelector.LocalOutput("annotation"),
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = AnnotationSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = AnnotationAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputTemplate = Some(annotationTemplate(withArguments)),
      outputRoleId = None,
      additionalGrammarRoleIds = Option.when(withArguments)(GrammarRoleId.AnnotationArguments).toSet
    )

  private def modifierKeywordProduction(prefix: String, expectedText: String, surface: String) =
    Scala3PsiProduction(
      id = s"modifier-keyword-${expectedText}",
      grammarRoleId = GrammarRoleId.KeywordModifier,
      pattern = CompilerProductionPattern(
        InventoryKind.Positioned,
        prefix,
        Vector.empty,
        ModifierOwnerPrefixes.map(owner =>
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              owner,
              ModifierChildrenPath ++ Vector(
                CatalogPathSegment.NamedField("mods"),
                CatalogPathSegment.RepeatedElement
              )
            ),
            SourceClassification.SourceReachable
          )
        )
      ),
      dispositions = Vector.empty,
      children = Vector.empty,
      terminals = Vector(
        TerminalDeclaration(
          "keyword",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(surface, Some(expectedText)),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = surface,
      targetRequirement = TargetRequirement.Native,
      accessors = Vector.empty,
      persistence = PersistenceObligations.NotApplicable,
      outputTemplate = Some(LocalOutputCompositeTemplate(Vector.empty, Map.empty)),
      outputRoleId = None
    )

  private def accessModifierProduction(prefix: String, expectedText: String, surface: String) =
    Scala3PsiProduction(
      id = s"modifier-access-$expectedText",
      grammarRoleId = GrammarRoleId.AccessModifier,
      pattern = CompilerProductionPattern(
        InventoryKind.Positioned,
        prefix,
        Vector.empty,
        ModifierOwnerPrefixes.map(owner =>
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              owner,
              ModifierChildrenPath ++ Vector(
                CatalogPathSegment.NamedField("mods"),
                CatalogPathSegment.RepeatedElement
              )
            ),
            SourceClassification.SourceReachable
          )
        )
      ),
      dispositions = Vector.empty,
      children = Vector.empty,
      terminals = Vector(
        TerminalDeclaration(
          "keyword",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(surface, Some(expectedText)),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = AccessModifierSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = AccessModifierAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputTemplate = Some(
        LocalOutputCompositeTemplate(
          Vector(
            outputComposite(
              "access",
              None,
              OutputRangeDeclaration.CompilerPositionWithTrailingBalancedBrackets(
                PositionProvenancePolicy.SourceDerivedOnly
              ),
              PsiOutputRoleId.AccessModifier,
              AccessModifierSurface,
              AccessModifierAccessors
            )
          ),
          Map.empty
        )
      ),
      outputRoleId = None
    )

  private def annotationConstructorSelectProduction: Scala3PsiProduction = transparentProduction(
    "annotation-constructor-select",
    GrammarRoleId.Annotation,
    "Select",
    Vector(
      CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
      CompilerFieldPattern("name", CatalogValuePattern.Name)
    ),
    annotationChildOccurrences(
      "Apply",
      Vector(CatalogPathSegment.NamedField("fun")),
      Vector.empty,
      SourceClassification.Synthetic
    ),
    Vector(
      FieldDisposition("qualifier", FieldDispositionKind.Child),
      FieldDisposition("name", FieldDispositionKind.SemanticOnly)
    ),
    Vector(
      ChildDeclaration(
        "constructor",
        "qualifier",
        ChildCardinality.ExactlyOne,
        "annotation-constructor-new"
      )
    )
  )

  private def annotationConstructorNewProduction: Scala3PsiProduction = transparentProduction(
    "annotation-constructor-new",
    GrammarRoleId.Annotation,
    "New",
    Vector(CompilerFieldPattern("tpt", CatalogValuePattern.Node)),
    annotationChildOccurrences(
      "Select",
      Vector(CatalogPathSegment.NamedField("qualifier")),
      Vector(ApplyFunAncestor),
      SourceClassification.Synthetic
    ),
    Vector(FieldDisposition("tpt", FieldDispositionKind.Child)),
    Vector(
      ChildDeclaration(
        "designator",
        "tpt",
        ChildCardinality.ExactlyOne,
        "annotation-designator-ident",
        Set("annotation-designator-select")
      )
    )
  )

  private def annotationDesignatorOccurrences = annotationChildOccurrences(
    "New",
    Vector(CatalogPathSegment.NamedField("tpt")),
    Vector(SelectQualifierAncestor, ApplyFunAncestor),
    SourceClassification.SourceReachable
  )

  private def annotationDesignatorIdentProduction: Scala3PsiProduction = Scala3PsiProduction(
    id = "annotation-designator-ident",
    grammarRoleId = GrammarRoleId.SimpleType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
      annotationDesignatorOccurrences
    ),
    dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "designator-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = SimpleTypeSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = SimpleTypeAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "type",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.SimpleType,
            SimpleTypeSurface,
            SimpleTypeAccessors
          ),
          outputComposite(
            "reference",
            Some("type"),
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.StableReference,
            StableReferenceSurface,
            StableReferenceAccessors
          )
        ),
        Map.empty
      )
    ),
    outputRoleId = None
  )

  private def annotationDesignatorSelectProduction: Scala3PsiProduction = Scala3PsiProduction(
    id = "annotation-designator-select",
    grammarRoleId = GrammarRoleId.SimpleType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Select",
      Vector(
        CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
        CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))
      ),
      annotationDesignatorOccurrences
    ),
    dispositions = Vector(
      FieldDisposition("qualifier", FieldDispositionKind.Child),
      FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)
    ),
    children = Vector(
      ChildDeclaration(
        "qualifier",
        "qualifier",
        ChildCardinality.ExactlyOne,
        "annotation-designator-qualifier-ident",
        Set("annotation-designator-qualifier-select")
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "designator-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = SimpleTypeSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = SimpleTypeAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(qualifiedTypeTemplate),
    outputRoleId = None
  )

  private def annotationQualifierOccurrences = ModifierOwnerPrefixes.map(definition =>
    CompilerProductionContextPattern(
      ContextPattern.ParentWithRepeatedAncestorPrefix(
        InventoryKind.Node,
        "Select",
        Vector(CatalogPathSegment.NamedField("qualifier")),
        SelectQualifierAncestor,
        Vector(NewTypeAncestor, SelectQualifierAncestor, ApplyFunAncestor, annotationAnchor(definition))
      ),
      SourceClassification.SourceReachable
    )
  )

  private def annotationQualifierProduction(prefix: String, recursive: Boolean): Scala3PsiProduction =
    val children = Option
      .when(recursive)(
        ChildDeclaration(
          "qualifier",
          "qualifier",
          ChildCardinality.ExactlyOne,
          "annotation-designator-qualifier-ident",
          Set("annotation-designator-qualifier-select")
        )
      )
      .toVector
    Scala3PsiProduction(
      id = s"annotation-designator-qualifier-${prefix.toLowerCase}",
      grammarRoleId = GrammarRoleId.StableReference,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        prefix,
        if recursive then
          Vector(
            CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
            CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))
          )
        else Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
        annotationQualifierOccurrences
      ),
      dispositions =
        if recursive then
          Vector(
            FieldDisposition("qualifier", FieldDispositionKind.Child),
            FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)
          )
        else Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
      children = children,
      terminals = Vector(
        TerminalDeclaration(
          "reference-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = StableReferenceSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = StableReferenceAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputTemplate = Some(stableReferenceTemplate(children.map(_.roleId)*)),
      outputRoleId = None
    )

  private def annotationLiteralPayloadProduction: Scala3PsiProduction = Scala3PsiProduction(
    id = "annotation-argument-literal-payload",
    grammarRoleId = GrammarRoleId.ExpressionPayload,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Literal",
      Vector(
        CompilerFieldPattern(
          "const",
          CatalogValuePattern.Product(
            "",
            Vector(CompilerFieldPattern("", CatalogValuePattern.Scalar("Text")))
          )
        )
      ),
      annotationChildOccurrences(
        "Apply",
        Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
        Vector.empty,
        SourceClassification.SourceReachable
      )
    ),
    dispositions = Vector(FieldDisposition("const", FieldDispositionKind.TerminalOrLayout)),
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "payload-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ExpressionPayloadSurface,
    targetRequirement = TargetRequirement.Compatible,
    accessors = ExpressionPayloadAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "payload",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.ExpressionPayload,
            ExpressionPayloadSurface,
            ExpressionPayloadAccessors,
            TargetRequirement.Compatible
          )
        ),
        Map.empty
      )
    ),
    outputRoleId = None
  )

  private def modifierAnnotationProductions: Vector[Scala3PsiProduction] =
    val emptyNode          = CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)
    val repeatedNode       = CatalogValuePattern.Repeated(CatalogValuePattern.Node)
    val emptyPositioned    = CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Positioned)
    val repeatedPositioned = CatalogValuePattern.Repeated(CatalogValuePattern.Positioned)
    val modifiers          = Vector(
      modifiersProduction(
        "modifiers-annotations-synthetic",
        repeatedNode,
        emptyPositioned,
        SourceClassification.Synthetic,
        modifierTemplate(withAnnotations = true, withModifiers = false),
        minimumAnnotations = 1,
        minimumModifiers = 0
      ),
      modifiersProduction(
        "modifiers-annotations-source",
        repeatedNode,
        emptyPositioned,
        SourceClassification.SourceReachable,
        modifierTemplate(withAnnotations = true, withModifiers = false),
        minimumAnnotations = 1,
        minimumModifiers = 0
      ),
      modifiersProduction(
        "modifiers-keywords",
        emptyNode,
        repeatedPositioned,
        SourceClassification.SourceReachable,
        modifierTemplate(withAnnotations = false, withModifiers = true),
        minimumAnnotations = 0,
        minimumModifiers = 1
      ),
      modifiersProduction(
        "modifiers-annotations-keywords",
        repeatedNode,
        repeatedPositioned,
        SourceClassification.SourceReachable,
        modifierTemplate(withAnnotations = true, withModifiers = true),
        minimumAnnotations = 1,
        minimumModifiers = 1
      ),
      Scala3PsiProduction(
        id = "modifiers-absent",
        grammarRoleId = GrammarRoleId.Modifiers,
        pattern = CompilerProductionPattern(
          InventoryKind.Product,
          "Modifiers",
          Vector(
            CompilerFieldPattern("flags", CatalogValuePattern.Scalar("LongInteger")),
            CompilerFieldPattern("privateWithin", CatalogValuePattern.Name),
            CompilerFieldPattern("annotations", emptyNode),
            CompilerFieldPattern("mods", emptyPositioned)
          ),
          modifierOccurrences(SourceClassification.Absent)
        ),
        dispositions = Vector(
          FieldDisposition("flags", FieldDispositionKind.SemanticOnly),
          FieldDisposition("privateWithin", FieldDispositionKind.SemanticOnly),
          FieldDisposition("annotations", FieldDispositionKind.Synthetic),
          FieldDisposition("mods", FieldDispositionKind.Synthetic)
        ),
        children = Vector.empty,
        terminals = Vector.empty,
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = ModifierListSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = Vector.empty,
        persistence = PersistenceObligations.NotApplicable,
        outputTemplate = Some(transparentTemplate()),
        outputRoleId = None
      )
    )
    val access             = Vector("Private" -> "private", "Protected" -> "protected").map: (prefix, text) =>
      accessModifierProduction(prefix, text, NativePsiElementBindings.AccessModifierKeywordSurfaceIds(prefix))
    val keyword            = Vector(
      "Abstract"    -> "abstract",
      "Final"       -> "final",
      "Sealed"      -> "sealed",
      "Implicit"    -> "implicit",
      "Lazy"        -> "lazy",
      "Override"    -> "override",
      "Var"         -> "var",
      "Transparent" -> "transparent",
      "Inline"      -> "inline",
      "Infix"       -> "infix",
      "Open"        -> "open",
      "Opaque"      -> "opaque",
      "Given"       -> "given"
    ).map: (prefix, text) =>
      modifierKeywordProduction(prefix, text, NativePsiElementBindings.ModifierKeywordSurfaceIds(prefix))
    modifiers ++ access ++ keyword ++ Vector(
      annotationApplyProduction("annotation-apply-simple", withArguments = false),
      annotationApplyProduction("annotation-apply-arguments", withArguments = true),
      annotationConstructorSelectProduction,
      annotationConstructorNewProduction,
      annotationDesignatorIdentProduction,
      annotationDesignatorSelectProduction,
      annotationQualifierProduction("Ident", recursive = false),
      annotationQualifierProduction("Select", recursive = true),
      annotationLiteralPayloadProduction
    )

  private def emptyModifiers(flags: Long): CatalogValuePattern = CatalogValuePattern.Product(
    "Modifiers",
    Vector(
      CompilerFieldPattern("flags", CatalogValuePattern.ExactScalar("LongInteger", s"LongInteger($flags)")),
      CompilerFieldPattern("privateWithin", CatalogValuePattern.ClassifiedName(NeutralNameClass.Empty)),
      CompilerFieldPattern("annotations", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)),
      CompilerFieldPattern("mods", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Positioned))
    )
  )

  private val TemplateOwnerOccurrences = Vector(
    CompilerProductionContextPattern(
      ContextPattern.Parent(
        InventoryKind.Node,
        "PackageDef",
        Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement)
      ),
      SourceClassification.SourceReachable
    ),
    CompilerProductionContextPattern(
      ContextPattern.Parent(
        InventoryKind.Node,
        "Template",
        Vector(CatalogPathSegment.NamedField("preBody"), CatalogPathSegment.RepeatedElement)
      ),
      SourceClassification.SourceReachable
    )
  )

  private def zeroOutput(
      id: String,
      parentId: String,
      role: PsiOutputRoleId,
      surface: String,
      boundary: OutputBoundary
  ): OutputCompositeDeclaration =
    val accessors = role match
      case PsiOutputRoleId.Annotations  => AnnotationsAccessors
      case PsiOutputRoleId.ModifierList => ModifierListAccessors
      case _                            => Vector.empty
    outputComposite(
      id,
      Some(parentId),
      OutputRangeDeclaration.BoundaryDerived(boundary, boundary),
      role,
      surface,
      accessors
    )

  private def definitionTemplate(
      role: PsiOutputRoleId,
      surface: String,
      implicitConstructor: Boolean,
      wrapper: Boolean
  ): LocalOutputCompositeTemplate =
    val definitionId   = if wrapper then "case-definition" else "definition"
    val definitionRoot =
      if wrapper then
        Vector(
          outputComposite(
            "enum-cases",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.EnumCases,
            EnumCasesSurface,
            Vector.empty
          ),
          zeroOutput(
            "annotations",
            "enum-cases",
            PsiOutputRoleId.Annotations,
            AnnotationsSurface,
            OutputBoundary.ProductionStart()
          ),
          zeroOutput(
            "modifiers",
            "enum-cases",
            PsiOutputRoleId.ModifierList,
            ModifierListSurface,
            OutputBoundary.ProductionStart()
          ),
          outputComposite(
            definitionId,
            Some("enum-cases"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.ProductionPoint,
              OutputBoundary.ProductionEnd()
            ),
            role,
            surface,
            Vector.empty
          )
        )
      else
        Vector(
          outputComposite(
            definitionId,
            None,
            OutputRangeDeclaration.CompilerPosition,
            role,
            surface,
            Vector.empty
          ),
          zeroOutput(
            "annotations",
            definitionId,
            PsiOutputRoleId.Annotations,
            AnnotationsSurface,
            OutputBoundary.ProductionStart()
          ),
          zeroOutput(
            "modifiers",
            definitionId,
            PsiOutputRoleId.ModifierList,
            ModifierListSurface,
            OutputBoundary.ProductionStart()
          )
        )
    val constructor    = Option
      .when(implicitConstructor)(
        Vector(
          zeroOutput(
            "constructor",
            definitionId,
            PsiOutputRoleId.PrimaryConstructor,
            PrimaryConstructorSurface,
            OutputBoundary.ProductionNameEnd
          ),
          zeroOutput(
            "constructor-annotations",
            "constructor",
            PsiOutputRoleId.Annotations,
            AnnotationsSurface,
            OutputBoundary.ProductionNameEnd
          ),
          zeroOutput(
            "constructor-modifiers",
            "constructor",
            PsiOutputRoleId.ModifierList,
            ModifierListSurface,
            OutputBoundary.ProductionNameEnd
          ),
          zeroOutput(
            "parameter-clauses",
            "constructor",
            PsiOutputRoleId.ParameterClauses,
            ParameterClausesSurface,
            OutputBoundary.ProductionNameEnd
          )
        )
      )
      .getOrElse(Vector.empty)
    LocalOutputCompositeTemplate(
      definitionRoot ++ constructor,
      Map(
        "template"  -> Some(definitionId),
        "modifiers" -> Some(if wrapper then "enum-cases" else definitionId)
      )
    )

  private def ownerRealizations(
      role: PsiOutputRoleId,
      surface: String,
      constructorOwner: Boolean,
      allowed: Vector[(String, Boolean)],
      wrapper: Boolean
  ): Vector[OutputRealization] = allowed.map: (templateRealization, implicitConstructor) =>
    OutputRealization(
      templateRealization,
      Vector(
        ChildOutcomeCondition(
          "template",
          ChildOccurrenceSelector.First,
          ChildOutcomeExpectation.Realization(templateRealization)
        )
      ),
      definitionTemplate(role, surface, constructorOwner && implicitConstructor, wrapper)
    )

  private def templateOwnerProduction(
      id: String,
      prefix: String,
      templateField: String,
      flags: Long,
      grammarRole: GrammarRoleId,
      outputRole: PsiOutputRoleId,
      surface: String,
      constructorOwner: Boolean,
      enumCase: Boolean = false,
      classCase: Boolean = false
  ): Scala3PsiProduction =
    val occurrences =
      if enumCase then
        Vector(
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              "Template",
              Vector(CatalogPathSegment.NamedField("preBody"), CatalogPathSegment.RepeatedElement)
            ),
            SourceClassification.SourceReachable
          )
        )
      else TemplateOwnerOccurrences
    val allowed     =
      if classCase then Vector("absent-explicit" -> false)
      else if enumCase then Vector("absent-synthetic" -> false)
      else
        for
          constructor <- Vector("synthetic", "explicit", "typed", "type")
          body        <- Vector(false, true)
          parents     <- Vector(false, true)
          derives     <- Vector(false, true)
        yield templateRealizationId(constructor, body, parents, derives) -> (constructor == "synthetic")
    Scala3PsiProduction(
      id = id,
      grammarRoleId = grammarRole,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        prefix,
        Vector(
          CompilerFieldPattern("name", CatalogValuePattern.Name),
          CompilerFieldPattern(templateField, CatalogValuePattern.NodePrefix("Template")),
          CompilerFieldPattern("mods", emptyModifiers(flags))
        ),
        occurrences
      ),
      dispositions = Vector(
        FieldDisposition("name", FieldDispositionKind.TerminalOrLayout),
        FieldDisposition(templateField, FieldDispositionKind.Child),
        FieldDisposition("mods", FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration("template", templateField, ChildCardinality.ExactlyOne, "template-template"),
        ChildDeclaration("modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
      ),
      terminals = Vector(
        TerminalDeclaration(
          "definition-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = surface,
      targetRequirement = TargetRequirement.Native,
      accessors = Vector.empty,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRealizations = ownerRealizations(
        outputRole,
        surface,
        constructorOwner,
        allowed,
        wrapper = enumCase
      ),
      outputRoleId = None
    )

  private def templateOutputTemplate(
      body: Boolean,
      parents: Boolean,
      derives: Boolean
  ): LocalOutputCompositeTemplate =
    val headerStart        =
      if parents then
        OutputBoundary.PreviousSignificantChildTokenStart(
          "parents",
          ChildOccurrenceSelector.First,
          PositionProvenancePolicy.SourceDerivedOnly
        )
      else if derives then
        OutputBoundary.PreviousSignificantChildTokenStart(
          "derives",
          ChildOccurrenceSelector.First,
          PositionProvenancePolicy.SourceDerivedOnly
        )
      else OutputBoundary.TemplateLayoutStart
    val end                = Option
      .when(body)(
        outputComposite(
          "end",
          Some("body"),
          OutputRangeDeclaration.CompilerEndMarker,
          PsiOutputRoleId.EndStatement,
          EndSurface,
          EndAccessors
        ).copy(requiresCompilerEndMarker = true)
      )
      .toVector
    val composites         =
      if body then
        Vector(
          outputComposite(
            "extends",
            None,
            OutputRangeDeclaration.BoundaryDerived(
              headerStart,
              OutputBoundary.ParentProductionEnd
            ),
            PsiOutputRoleId.ExtendsBlock,
            ExtendsBlockSurface,
            Vector.empty
          ),
          outputComposite(
            "body",
            Some("extends"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.TemplateLayoutStart,
              OutputBoundary.ParentProductionEnd
            ),
            PsiOutputRoleId.TemplateBody,
            TemplateBodySurface,
            Vector.empty
          )
        ) ++ end
      else
        Vector(
          outputComposite(
            "extends",
            None,
            if parents || derives then
              OutputRangeDeclaration.BoundaryDerived(headerStart, OutputBoundary.ParentProductionEnd)
            else
              OutputRangeDeclaration.BoundaryDerived(
                OutputBoundary.ParentProductionEnd,
                OutputBoundary.ParentProductionEnd
              )
            ,
            PsiOutputRoleId.ExtendsBlock,
            ExtendsBlockSurface,
            Vector.empty
          )
        )
    val parentsComposite   = Option.when(parents)(
      outputComposite(
        "parents",
        Some("extends"),
        OutputRangeDeclaration.BoundaryDerived(
          OutputBoundary
            .ChildStart("parents", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly),
          OutputBoundary.ChildEnd("parents", ChildOccurrenceSelector.Last, PositionProvenancePolicy.SourceDerivedOnly)
        ),
        PsiOutputRoleId.TemplateParents,
        TemplateParentsSurface,
        TemplateParentsAccessors
      )
    )
    val parentConstructors = Option.when(parents)(
      outputComposite(
        "parent-constructor",
        Some("parents"),
        OutputRangeDeclaration.BoundaryDerived(
          OutputBoundary.ChildStart(
            "parents",
            ChildOccurrenceSelector.First,
            PositionProvenancePolicy.SourceDerivedOnly
          ),
          OutputBoundary.ChildEnd("parents", ChildOccurrenceSelector.Last, PositionProvenancePolicy.SourceDerivedOnly)
        ),
        PsiOutputRoleId.ConstructorInvocation,
        ConstructorSurface,
        ConstructorAccessors
      ).copy(realization = OutputCompositeRealization.PerChildRole("parents"))
    )
    val derivesComposite   = Option.when(derives)(
      outputComposite(
        "derives",
        Some("extends"),
        OutputRangeDeclaration.BoundaryDerived(
          OutputBoundary.PreviousSignificantChildTokenStart(
            "derives",
            ChildOccurrenceSelector.First,
            PositionProvenancePolicy.SourceDerivedOnly
          ),
          OutputBoundary.ChildEnd("derives", ChildOccurrenceSelector.Last, PositionProvenancePolicy.SourceDerivedOnly)
        ),
        PsiOutputRoleId.DerivesClause,
        DerivesClauseSurface,
        DerivesClauseAccessors
      )
    )
    LocalOutputCompositeTemplate(
      composites ++ parentsComposite ++ parentConstructors ++ derivesComposite,
      Map(
        "constructor"        -> None,
        "self"               -> Option.when(body)("body"),
        "parents"            -> Option.when(parents)("parent-constructor"),
        "derives"            -> Option.when(derives)("derives"),
        "statements"         -> Option.when(body)("body"),
        "template-modifiers" -> None
      ),
      Option.when(derives)("derives" -> PsiOutputRoleId.StableReference).toMap
    )

  private def templateRealization(
      id: String,
      constructorId: String,
      body: Boolean,
      parents: Boolean,
      derives: Boolean
  ): OutputRealization = OutputRealization(
    id,
    Vector(
      ChildOutcomeCondition(
        "constructor",
        ChildOccurrenceSelector.First,
        ChildOutcomeExpectation.Production(constructorId)
      )
    ),
    templateOutputTemplate(body, parents, derives),
    Vector(
      EvidenceCondition.TemplateBodyLayout(body),
      EvidenceCondition.LeadingBeforeRuntimeTailPresent("preParentsOrDerived", "derivedCount", parents),
      EvidenceCondition.RuntimeSupplementPositive("derivedCount", derives)
    )
  )

  private def templateRealizationId(
      constructorLabel: String,
      body: Boolean,
      parents: Boolean,
      derives: Boolean
  ): String =
    if !parents && !derives then
      constructorLabel match
        case "synthetic" => if body then "layout-synthetic" else "absent-synthetic"
        case "explicit"  => if body then "layout-explicit" else "absent-explicit"
        case "type"      => if body then "type-layout" else "type-absent"
        case "typed"     => if body then "typed-layout" else "typed-absent"
    else s"$constructorLabel-body-$body-parents-$parents-derives-$derives"

  private val templateTemplateProduction = Scala3PsiProduction(
    id = "template-template",
    grammarRoleId = GrammarRoleId.Template,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Template",
      Vector(
        CompilerFieldPattern("constr", CatalogValuePattern.Node),
        CompilerFieldPattern("preParentsOrDerived", CatalogValuePattern.Repeated(CatalogValuePattern.Node)),
        CompilerFieldPattern("self", CatalogValuePattern.Node),
        CompilerFieldPattern("preBody", CatalogValuePattern.Repeated(CatalogValuePattern.Node)),
        CompilerFieldPattern("mods", emptyModifiers(0L))
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(InventoryKind.Node, "TypeDef", Vector(CatalogPathSegment.NamedField("rhs"))),
          SourceClassification.Synthetic
        ),
        CompilerProductionContextPattern(
          ContextPattern.Parent(InventoryKind.Node, "ModuleDef", Vector(CatalogPathSegment.NamedField("impl"))),
          SourceClassification.Synthetic
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("constr", FieldDispositionKind.Child),
      FieldDisposition("preParentsOrDerived", FieldDispositionKind.Child),
      FieldDisposition("self", FieldDispositionKind.Child),
      FieldDisposition("preBody", FieldDispositionKind.Child),
      FieldDisposition("mods", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "constructor",
        "constr",
        ChildCardinality.ExactlyOne,
        "template-constructor-synthetic",
        Set(
          "template-constructor-explicit-empty",
          "template-constructor-typed-parameters",
          "template-constructor-unbounded-type-parameters"
        )
      ),
      ChildDeclaration(
        "parents",
        "preParentsOrDerived",
        ChildCardinality.Repeated(0, None),
        "import-selector-bound-type",
        TypeAtomProductionIds - "import-selector-bound-type",
        ChildSlice.LeadingBeforeRuntimeTail("derivedCount")
      ),
      ChildDeclaration(
        "derives",
        "preParentsOrDerived",
        ChildCardinality.Repeated(0, None),
        "import-selector-bound-type",
        TypeAtomProductionIds - "import-selector-bound-type",
        ChildSlice.RuntimeTail("derivedCount")
      ),
      ChildDeclaration(
        "self",
        "self",
        ChildCardinality.ExactlyOne,
        "template-self-absent",
        Set("template-self-simple")
      ),
      ChildDeclaration(
        "statements",
        "preBody",
        ChildCardinality.Grouped(0, None),
        "template-absent-tree",
        Set(
          "template-class-definition",
          "template-trait-definition",
          "template-object-definition",
          "template-enum-definition",
          "enum-singleton-case",
          "enum-class-case",
          "definition-function-untyped",
          "definition-val-untyped",
          "definition-var-untyped",
          "definition-unbounded-type-alias"
        ) ++ SimpleTypeAliasProductionIds
      ),
      ChildDeclaration("template-modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
    ),
    terminals = Vector(
      TerminalDeclaration(
        "end-keyword",
        TerminalIntervalSelector.CompilerEndMarkerKeyword,
        TerminalLeafTarget.Token(NativePsiElementBindings.EndKeywordTokenSurface, Some("end")),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.EndKeyword
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ExtendsBlockSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRealizations = (for
      (constructorLabel, constructorId) <- Vector(
                                             "synthetic" -> "template-constructor-synthetic",
                                             "explicit"  -> "template-constructor-explicit-empty",
                                             "typed"     -> "template-constructor-typed-parameters",
                                             "type"      -> "template-constructor-unbounded-type-parameters"
                                           )
      body                              <- Vector(false, true)
      parents                           <- Vector(false, true)
      derives                           <- Vector(false, true)
    yield templateRealization(
      templateRealizationId(constructorLabel, body, parents, derives),
      constructorId,
      body,
      parents,
      derives
    )),
    outputRoleId = None
  )

  private val templateConstructorSyntheticProduction = Scala3PsiProduction(
    id = "template-constructor-synthetic",
    grammarRoleId = GrammarRoleId.TemplateConstructor,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "DefDef",
      Vector(
        CompilerFieldPattern("name", CatalogValuePattern.Name),
        CompilerFieldPattern("paramss", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)),
        CompilerFieldPattern("tpt", CatalogValuePattern.Node),
        CompilerFieldPattern("preRhs", CatalogValuePattern.Node),
        CompilerFieldPattern("mods", emptyModifiers(0L))
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(InventoryKind.Node, "Template", Vector(CatalogPathSegment.NamedField("constr"))),
          SourceClassification.Synthetic
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("name", FieldDispositionKind.SemanticOnly),
      FieldDisposition("paramss", FieldDispositionKind.Synthetic),
      FieldDisposition("tpt", FieldDispositionKind.Child),
      FieldDisposition("preRhs", FieldDispositionKind.Child),
      FieldDisposition("mods", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration("type-tree", "tpt", ChildCardinality.ExactlyOne, "template-type-tree-synthetic"),
      ChildDeclaration("rhs", "preRhs", ChildCardinality.ExactlyOne, "template-absent-tree"),
      ChildDeclaration("constructor-modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
    ),
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = PrimaryConstructorSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(transparentTemplate("type-tree", "rhs", "constructor-modifiers")),
    outputRoleId = None
  )

  private val templateConstructorExplicitProduction = templateConstructorSyntheticProduction.copy(
    id = "template-constructor-explicit-empty",
    pattern = templateConstructorSyntheticProduction.pattern.copy(
      fields = templateConstructorSyntheticProduction.pattern.fields.updated(
        1,
        CompilerFieldPattern(
          "paramss",
          CatalogValuePattern.Repeated(CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node))
        )
      ),
      occurrences = templateConstructorSyntheticProduction.pattern.occurrences.map(
        _.copy(sourceClassification = SourceClassification.SourceReachable)
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "constructor-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "constructor",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.PrimaryConstructor,
            PrimaryConstructorSurface,
            Vector.empty
          ),
          zeroOutput(
            "annotations",
            "constructor",
            PsiOutputRoleId.Annotations,
            AnnotationsSurface,
            OutputBoundary.ProductionStart()
          ),
          zeroOutput(
            "modifiers",
            "constructor",
            PsiOutputRoleId.ModifierList,
            ModifierListSurface,
            OutputBoundary.ProductionStart()
          ),
          outputComposite(
            "parameter-clauses",
            Some("constructor"),
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.ParameterClauses,
            ParameterClausesSurface,
            Vector.empty
          ),
          outputComposite(
            "parameter-clause",
            Some("parameter-clauses"),
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.ParameterClause,
            ParameterClauseSurface,
            Vector.empty
          ).copy(
            realization = OutputCompositeRealization.PerRepeatedFieldOccurrence(
              "paramss",
              CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node),
              ClosedSourceLexicalKind.LeftParenthesis,
              ClosedSourceLexicalKind.RightParenthesis
            )
          )
        ),
        Map(
          "type-tree"             -> None,
          "rhs"                   -> None,
          "constructor-modifiers" -> None
        )
      )
    )
  )

  private val templateConstructorTypedParametersProduction = templateConstructorExplicitProduction.copy(
    id = "template-constructor-typed-parameters",
    pattern = templateConstructorExplicitProduction.pattern.copy(
      fields = templateConstructorExplicitProduction.pattern.fields.updated(
        1,
        CompilerFieldPattern(
          "paramss",
          CatalogValuePattern.Repeated(
            CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef"))
          )
        )
      )
    ),
    dispositions = templateConstructorExplicitProduction.dispositions.updated(
      1,
      FieldDisposition("paramss", FieldDispositionKind.Child)
    ),
    children = templateConstructorExplicitProduction.children :+ ChildDeclaration(
      "parameters",
      "paramss",
      ChildCardinality.Repeated(1, None),
      "template-class-parameter",
      Set("template-context-class-parameter")
    ),
    outputTemplate = templateConstructorExplicitProduction.outputTemplate.map(template =>
      template.copy(
        composites = template.composites.map:
          case output if output.id == "parameter-clause" =>
            output.copy(
              realization = OutputCompositeRealization.PerRepeatedFieldOccurrence(
                "paramss",
                CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
                ClosedSourceLexicalKind.LeftParenthesis,
                ClosedSourceLexicalKind.RightParenthesis
              )
            )
          case other                                     => other,
        childMounts = template.childMounts + ("parameters" -> Some("parameter-clause"))
      )
    )
  )

  private val templateConstructorTypeParametersProduction = templateConstructorExplicitProduction.copy(
    id = "template-constructor-unbounded-type-parameters",
    pattern = templateConstructorExplicitProduction.pattern.copy(
      fields = templateConstructorExplicitProduction.pattern.fields.updated(
        1,
        CompilerFieldPattern(
          "paramss",
          CatalogValuePattern.LeadingThenRepeated(
            CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("TypeDef")),
            CatalogValuePattern.Repeated(CatalogValuePattern.Node)
          )
        )
      )
    ),
    dispositions = templateConstructorExplicitProduction.dispositions.updated(
      1,
      FieldDisposition("paramss", FieldDispositionKind.Child)
    ),
    children = templateConstructorExplicitProduction.children ++ Vector(
      ChildDeclaration(
        "parameters",
        "paramss",
        ChildCardinality.Repeated(0, None),
        "template-class-parameter",
        Set("template-context-class-parameter"),
        ChildSlice.MatchingProductions
      ),
      ChildDeclaration(
        "type-parameters",
        "paramss",
        ChildCardinality.Grouped(1, None),
        "template-unbounded-type-parameter-invariant",
        Set("template-unbounded-type-parameter-covariant", "template-unbounded-type-parameter-contravariant"),
        ChildSlice.MatchingProductions
      )
    ),
    outputTemplate = None,
    outputRealizations = Vector(
      OutputRealization(
        "without-empty-term-clauses",
        Vector.empty,
        LocalOutputCompositeTemplate(
          Vector(
            outputComposite(
              "type-parameter-clause",
              None,
              OutputRangeDeclaration.BoundaryDerived(
                OutputBoundary.ProductionStart(),
                OutputBoundary.ProductionEnd()
              ),
              PsiOutputRoleId.TypeParameterClause,
              TypeParameterClauseSurface,
              Vector.empty
            ).copy(
              realization = OutputCompositeRealization.PerRepeatedFieldOccurrence(
                "paramss",
                CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.Node),
                ClosedSourceLexicalKind.LeftBracket,
                ClosedSourceLexicalKind.RightBracket
              )
            ),
            zeroOutput(
              "constructor",
              "type-parameter-clause",
              PsiOutputRoleId.PrimaryConstructor,
              PrimaryConstructorSurface,
              OutputBoundary.ProductionEnd()
            ).copy(parentId = None),
            zeroOutput(
              "annotations",
              "constructor",
              PsiOutputRoleId.Annotations,
              AnnotationsSurface,
              OutputBoundary.ProductionEnd()
            ),
            zeroOutput(
              "modifiers",
              "constructor",
              PsiOutputRoleId.ModifierList,
              ModifierListSurface,
              OutputBoundary.ProductionEnd()
            ),
            zeroOutput(
              "parameter-clauses",
              "constructor",
              PsiOutputRoleId.ParameterClauses,
              ParameterClausesSurface,
              OutputBoundary.ProductionEnd()
            )
          ),
          Map(
            "type-tree"             -> None,
            "rhs"                   -> None,
            "constructor-modifiers" -> None,
            "parameters"            -> None,
            "type-parameters"       -> Some("type-parameter-clause")
          )
        ),
        Vector(
          EvidenceCondition.RepeatedFieldOccurrence(
            "paramss",
            CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node),
            present = false
          ),
          EvidenceCondition.RepeatedFieldOccurrence(
            "paramss",
            CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
            present = false
          )
        )
      ),
      OutputRealization(
        "with-typed-term-clauses",
        Vector.empty,
        LocalOutputCompositeTemplate(
          Vector(
            outputComposite(
              "type-parameter-clause",
              None,
              OutputRangeDeclaration.BoundaryDerived(
                OutputBoundary.ChildStart(
                  "type-parameters",
                  ChildOccurrenceSelector.First,
                  PositionProvenancePolicy.SourceDerivedOnly
                ),
                OutputBoundary.ChildEnd(
                  "type-parameters",
                  ChildOccurrenceSelector.Last,
                  PositionProvenancePolicy.SourceDerivedOnly
                )
              ),
              PsiOutputRoleId.TypeParameterClause,
              TypeParameterClauseSurface,
              Vector.empty
            ).copy(
              realization = OutputCompositeRealization.PerRepeatedFieldOccurrence(
                "paramss",
                CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("TypeDef")),
                ClosedSourceLexicalKind.LeftBracket,
                ClosedSourceLexicalKind.RightBracket
              )
            ),
            outputComposite(
              "constructor",
              None,
              OutputRangeDeclaration.BoundaryDerived(
                OutputBoundary.ChildStart(
                  "parameters",
                  ChildOccurrenceSelector.First,
                  PositionProvenancePolicy.SourceDerivedOnly
                ),
                OutputBoundary.ChildEnd(
                  "parameters",
                  ChildOccurrenceSelector.Last,
                  PositionProvenancePolicy.SourceDerivedOnly
                )
              ),
              PsiOutputRoleId.PrimaryConstructor,
              PrimaryConstructorSurface,
              Vector.empty
            ).copy(
              realization = OutputCompositeRealization.AcrossRepeatedFieldOccurrences(
                "paramss",
                CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
                ClosedSourceLexicalKind.LeftParenthesis,
                ClosedSourceLexicalKind.RightParenthesis
              )
            ),
            outputComposite(
              "annotations",
              Some("constructor"),
              OutputRangeDeclaration.BoundaryDerived(
                OutputBoundary.ChildStart(
                  "parameters",
                  ChildOccurrenceSelector.First,
                  PositionProvenancePolicy.SourceDerivedOnly
                ),
                OutputBoundary.ChildStart(
                  "parameters",
                  ChildOccurrenceSelector.First,
                  PositionProvenancePolicy.SourceDerivedOnly
                )
              ),
              PsiOutputRoleId.Annotations,
              AnnotationsSurface,
              AnnotationsAccessors
            ).copy(
              realization = OutputCompositeRealization.AtFirstRepeatedFieldOccurrenceStart(
                "paramss",
                CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
                ClosedSourceLexicalKind.LeftParenthesis,
                ClosedSourceLexicalKind.RightParenthesis
              )
            ),
            outputComposite(
              "modifiers",
              Some("constructor"),
              OutputRangeDeclaration.BoundaryDerived(
                OutputBoundary.ChildStart(
                  "parameters",
                  ChildOccurrenceSelector.First,
                  PositionProvenancePolicy.SourceDerivedOnly
                ),
                OutputBoundary.ChildStart(
                  "parameters",
                  ChildOccurrenceSelector.First,
                  PositionProvenancePolicy.SourceDerivedOnly
                )
              ),
              PsiOutputRoleId.ModifierList,
              ModifierListSurface,
              ModifierListAccessors
            ).copy(
              realization = OutputCompositeRealization.AtFirstRepeatedFieldOccurrenceStart(
                "paramss",
                CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
                ClosedSourceLexicalKind.LeftParenthesis,
                ClosedSourceLexicalKind.RightParenthesis
              )
            ),
            outputComposite(
              "parameter-clauses",
              Some("constructor"),
              OutputRangeDeclaration.CompilerPosition,
              PsiOutputRoleId.ParameterClauses,
              ParameterClausesSurface,
              Vector.empty
            ).copy(
              realization = OutputCompositeRealization.AcrossRepeatedFieldOccurrences(
                "paramss",
                CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
                ClosedSourceLexicalKind.LeftParenthesis,
                ClosedSourceLexicalKind.RightParenthesis
              )
            ),
            outputComposite(
              "parameter-clause",
              Some("parameter-clauses"),
              OutputRangeDeclaration.CompilerPosition,
              PsiOutputRoleId.ParameterClause,
              ParameterClauseSurface,
              Vector.empty
            ).copy(
              realization = OutputCompositeRealization.PerRepeatedFieldOccurrence(
                "paramss",
                CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
                ClosedSourceLexicalKind.LeftParenthesis,
                ClosedSourceLexicalKind.RightParenthesis
              )
            )
          ),
          Map(
            "type-tree"             -> None,
            "rhs"                   -> None,
            "constructor-modifiers" -> None,
            "parameters"            -> Some("parameter-clause"),
            "type-parameters"       -> Some("type-parameter-clause")
          )
        ),
        Vector(
          EvidenceCondition.RepeatedFieldOccurrence(
            "paramss",
            CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node),
            present = false
          ),
          EvidenceCondition.RepeatedFieldOccurrence(
            "paramss",
            CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
            present = true
          )
        )
      ),
      OutputRealization(
        "with-empty-term-clauses",
        Vector.empty,
        templateConstructorExplicitProduction.outputTemplate.get.copy(
          composites = templateConstructorExplicitProduction.outputTemplate.get.composites
            .map:
              case output if Set("constructor", "parameter-clauses").contains(output.id) =>
                output.copy(
                  realization = OutputCompositeRealization.AcrossRepeatedFieldOccurrences(
                    "paramss",
                    CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node),
                    ClosedSourceLexicalKind.LeftParenthesis,
                    ClosedSourceLexicalKind.RightParenthesis
                  )
                )
              case output if Set("annotations", "modifiers").contains(output.id)         =>
                output.copy(
                  realization = OutputCompositeRealization.AtFirstRepeatedFieldOccurrenceStart(
                    "paramss",
                    CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node),
                    ClosedSourceLexicalKind.LeftParenthesis,
                    ClosedSourceLexicalKind.RightParenthesis
                  )
                )
              case other                                                                 => other
          :+ outputComposite(
            "type-parameter-clause",
            None,
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.ProductionStart(),
              OutputBoundary.ProductionEnd()
            ),
            PsiOutputRoleId.TypeParameterClause,
            TypeParameterClauseSurface,
            Vector.empty
          ).copy(
            realization = OutputCompositeRealization.PerRepeatedFieldOccurrence(
              "paramss",
              CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.Node),
              ClosedSourceLexicalKind.LeftBracket,
              ClosedSourceLexicalKind.RightBracket
            )
          ),
          childMounts = templateConstructorExplicitProduction.outputTemplate.get.childMounts ++ Map(
            "parameters"      -> None,
            "type-parameters" -> Some("type-parameter-clause")
          )
        ),
        Vector(
          EvidenceCondition.RepeatedFieldOccurrence(
            "paramss",
            CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node),
            present = true
          )
        )
      )
    )
  )

  private def unboundedTypeParameterProduction(id: String, flags: Long): Scala3PsiProduction = Scala3PsiProduction(
    id = id,
    grammarRoleId = GrammarRoleId.UnboundedTypeParameter,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "TypeDef",
      Vector(
        CompilerFieldPattern("name", CatalogValuePattern.Name),
        CompilerFieldPattern("rhs", CatalogValuePattern.Node),
        CompilerFieldPattern("mods", emptyModifiers(flags))
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "DefDef",
            Vector(
              CatalogPathSegment.NamedField("paramss"),
              CatalogPathSegment.RepeatedElement,
              CatalogPathSegment.RepeatedElement
            )
          ),
          SourceClassification.SourceReachable
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("name", FieldDispositionKind.TerminalOrLayout),
      FieldDisposition("rhs", FieldDispositionKind.Child),
      FieldDisposition("mods", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration("bounds", "rhs", ChildCardinality.ExactlyOne, "template-unbounded-type-bounds"),
      ChildDeclaration("type-parameter-modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
    ),
    terminals = Vector(
      TerminalDeclaration(
        "type-parameter-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = TypeParameterSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "type-parameter",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.TypeParameter,
            TypeParameterSurface,
            Vector.empty
          )
        ),
        Map("bounds" -> None, "type-parameter-modifiers" -> None)
      )
    ),
    outputRoleId = None
  )

  private val unboundedTypeBoundsProduction = Scala3PsiProduction(
    id = "template-unbounded-type-bounds",
    grammarRoleId = GrammarRoleId.TypeParameterClause,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "TypeBoundsTree",
      Vector(
        CompilerFieldPattern("lo", CatalogValuePattern.Node),
        CompilerFieldPattern("hi", CatalogValuePattern.Node),
        CompilerFieldPattern("alias", CatalogValuePattern.Node)
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(InventoryKind.Node, "TypeDef", Vector(CatalogPathSegment.NamedField("rhs"))),
          SourceClassification.Synthetic
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("lo", FieldDispositionKind.Child),
      FieldDisposition("hi", FieldDispositionKind.Child),
      FieldDisposition("alias", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration("lower", "lo", ChildCardinality.ExactlyOne, "template-absent-tree"),
      ChildDeclaration("upper", "hi", ChildCardinality.ExactlyOne, "template-absent-tree"),
      ChildDeclaration("alias", "alias", ChildCardinality.ExactlyOne, "template-absent-tree")
    ),
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = TypeParameterSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(transparentTemplate("lower", "upper", "alias")),
    outputRoleId = None
  )

  private val templateTypeTreeProduction = Scala3PsiProduction(
    id = "template-type-tree-synthetic",
    grammarRoleId = GrammarRoleId.TemplateTypeTree,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "TypeTree",
      Vector.empty,
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestor(
            InventoryKind.Node,
            "DefDef",
            Vector(CatalogPathSegment.NamedField("tpt")),
            InventoryAncestor(InventoryKind.Node, "Template", Vector(CatalogPathSegment.NamedField("constr")))
          ),
          SourceClassification.Synthetic
        )
      )
    ),
    dispositions = Vector.empty,
    children = Vector.empty,
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = PrimaryConstructorSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(transparentTemplate()),
    outputRoleId = None
  )

  private val templateSelfProduction = Scala3PsiProduction(
    id = "template-self-absent",
    grammarRoleId = GrammarRoleId.TemplateSelf,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "ValDef",
      Vector(
        CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Wildcard)),
        CompilerFieldPattern("tpt", CatalogValuePattern.Node),
        CompilerFieldPattern("preRhs", CatalogValuePattern.Node),
        CompilerFieldPattern("mods", emptyModifiers(8199L))
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(InventoryKind.Node, "Template", Vector(CatalogPathSegment.NamedField("self"))),
          SourceClassification.Absent
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("name", FieldDispositionKind.SemanticOnly),
      FieldDisposition("tpt", FieldDispositionKind.Child),
      FieldDisposition("preRhs", FieldDispositionKind.Child),
      FieldDisposition("mods", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration("type-tree", "tpt", ChildCardinality.ExactlyOne, "template-absent-tree"),
      ChildDeclaration("rhs", "preRhs", ChildCardinality.ExactlyOne, "template-absent-tree"),
      ChildDeclaration("self-modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
    ),
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ModifierListSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(transparentTemplate("type-tree", "rhs", "self-modifiers")),
    outputRoleId = None
  )

  private val templateSimpleSelfProduction = templateSelfProduction.copy(
    id = "template-self-simple",
    pattern = templateSelfProduction.pattern.copy(
      fields = templateSelfProduction.pattern.fields.updated(
        0,
        CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))
      ),
      occurrences = Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(InventoryKind.Node, "Template", Vector(CatalogPathSegment.NamedField("self"))),
          SourceClassification.SourceReachable
        )
      )
    ),
    children = Vector(
      ChildDeclaration(
        "declared-type",
        "tpt",
        ChildCardinality.ExactlyOne,
        "import-selector-bound-type",
        TypeAtomProductionIds - "import-selector-bound-type"
      ),
      ChildDeclaration("rhs", "preRhs", ChildCardinality.ExactlyOne, "template-absent-tree"),
      ChildDeclaration("self-modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
    ),
    terminals = Vector(
      TerminalDeclaration(
        "self-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    targetSurfaceId = SelfTypeSurface,
    accessors = SelfTypeAccessors,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "self",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.SelfType,
            SelfTypeSurface,
            SelfTypeAccessors
          )
        ),
        Map("declared-type" -> Some("self"), "rhs" -> None, "self-modifiers" -> None)
      )
    )
  )

  private val templateAbsentTreeProduction = Scala3PsiProduction(
    id = "template-absent-tree",
    grammarRoleId = GrammarRoleId.AbsentProduct,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Thicket",
      Vector(CompilerFieldPattern("trees", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node))),
      Vector(
        "DefDef"         -> "preRhs",
        "ValDef"         -> "tpt",
        "ValDef"         -> "preRhs",
        "TypeBoundsTree" -> "lo",
        "TypeBoundsTree" -> "hi",
        "TypeBoundsTree" -> "alias",
        "Template"       -> "preBody"
      ).map: (owner, field) =>
        CompilerProductionContextPattern(
          if owner == "TypeBoundsTree" then
            ContextPattern.ParentWithAncestor(
              InventoryKind.Node,
              owner,
              Vector(CatalogPathSegment.NamedField(field)),
              InventoryAncestor(InventoryKind.Node, "TypeDef", Vector(CatalogPathSegment.NamedField("rhs")))
            )
          else
            ContextPattern.Parent(
              InventoryKind.Node,
              owner,
              Vector(CatalogPathSegment.NamedField(field)) ++
                Option.when(owner == "Template")(CatalogPathSegment.RepeatedElement)
            )
          ,
          SourceClassification.Absent
        )
    ),
    dispositions = Vector(FieldDisposition("trees", FieldDispositionKind.Synthetic)),
    children = Vector.empty,
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ModifierListSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(transparentTemplate()),
    outputRoleId = None
  )

  private def definitionOccurrences(owner: String, field: String = "stats") = Vector(
    CompilerProductionContextPattern(
      ContextPattern.Parent(
        InventoryKind.Node,
        owner,
        Vector(CatalogPathSegment.NamedField(field), CatalogPathSegment.RepeatedElement)
      ),
      SourceClassification.SourceReachable
    )
  )

  private def definitionChildOccurrences(field: String) =
    Vector("DefDef", "ValDef").flatMap(owner =>
      Vector("PackageDef" -> "stats", "Template" -> "preBody").map((ancestor, ancestorField) =>
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestor(
            InventoryKind.Node,
            owner,
            Vector(CatalogPathSegment.NamedField(field)),
            InventoryAncestor(
              InventoryKind.Node,
              ancestor,
              Vector(CatalogPathSegment.NamedField(ancestorField), CatalogPathSegment.RepeatedElement)
            )
          ),
          SourceClassification.Synthetic
        )
      )
    )

  private def localDefinitionChildOccurrences(field: String, sourceClassification: SourceClassification) =
    Vector("DefDef", "ValDef").map(anchor =>
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchor(
          InventoryKind.Node,
          "ValDef",
          Vector(CatalogPathSegment.NamedField(field)),
          InventoryAncestor(
            InventoryKind.Node,
            anchor,
            Vector(CatalogPathSegment.NamedField("preRhs"))
          )
        ),
        sourceClassification
      )
    )

  private val inferredDefinitionType = Scala3PsiProduction(
    id = "definition-inferred-type-absence",
    grammarRoleId = GrammarRoleId.InferredTypeAbsence,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "TypeTree",
      Vector.empty,
      definitionChildOccurrences("tpt") ++
        localDefinitionChildOccurrences("tpt", SourceClassification.Synthetic)
    ),
    dispositions = Vector.empty,
    children = Vector.empty,
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ExpressionPayloadSurface,
    targetRequirement = TargetRequirement.Compatible,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(transparentTemplate()),
    outputRoleId = None
  )

  private def typedParameterProduction(
      id: String,
      flags: Long,
      classParameter: Boolean,
      contextual: Boolean = false
  ): Scala3PsiProduction =
    val ancestors =
      if classParameter then
        Vector(InventoryAncestor(InventoryKind.Node, "Template", Vector(CatalogPathSegment.NamedField("constr"))))
      else
        Vector(
          InventoryAncestor(
            InventoryKind.Node,
            "Template",
            Vector(CatalogPathSegment.NamedField("preBody"), CatalogPathSegment.RepeatedElement)
          ),
          InventoryAncestor(
            InventoryKind.Node,
            "PackageDef",
            Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement)
          )
        )
    Scala3PsiProduction(
      id = id,
      grammarRoleId = if classParameter then GrammarRoleId.ClassParameter else GrammarRoleId.TermParameter,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "ValDef",
        Vector(
          CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)),
          CompilerFieldPattern("tpt", CatalogValuePattern.Node),
          CompilerFieldPattern("preRhs", CatalogValuePattern.Node),
          CompilerFieldPattern(
            "mods",
            CatalogValuePattern.Product(
              "Modifiers",
              Vector(
                CompilerFieldPattern("flags", CatalogValuePattern.ExactScalar("LongInteger", s"LongInteger($flags)")),
                CompilerFieldPattern("privateWithin", CatalogValuePattern.ClassifiedName(NeutralNameClass.Empty)),
                CompilerFieldPattern("annotations", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)),
                CompilerFieldPattern("mods", CatalogValuePattern.Repeated(CatalogValuePattern.Positioned))
              )
            )
          )
        ),
        ancestors.map(ancestor =>
          CompilerProductionContextPattern(
            ContextPattern.ParentWithAncestor(
              InventoryKind.Node,
              "DefDef",
              Vector(
                CatalogPathSegment.NamedField("paramss"),
                CatalogPathSegment.RepeatedElement,
                CatalogPathSegment.RepeatedElement
              ),
              ancestor
            ),
            SourceClassification.SourceReachable
          )
        )
      ),
      dispositions = Vector(
        FieldDisposition("name", FieldDispositionKind.TerminalOrLayout),
        FieldDisposition("tpt", FieldDispositionKind.Child),
        FieldDisposition("preRhs", FieldDispositionKind.Child),
        FieldDisposition("mods", if contextual then FieldDispositionKind.SemanticOnly else FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration(
          "declared-type",
          "tpt",
          ChildCardinality.ExactlyOne,
          "import-selector-bound-type",
          TypeAtomProductionIds - "import-selector-bound-type"
        ),
        ChildDeclaration("default", "preRhs", ChildCardinality.ExactlyOne, "template-absent-tree")
      ) ++ Option.when(!contextual)(
        ChildDeclaration(
          "modifiers",
          "mods",
          ChildCardinality.ExactlyOne,
          "modifiers-absent",
          Set("modifiers-keywords", "modifiers-annotations-source", "modifiers-annotations-keywords")
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "parameter-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = if classParameter then ClassParameterSurface else ParameterSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = ParameterAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputTemplate = Some(
        LocalOutputCompositeTemplate(
          Vector(
            outputComposite(
              "parameter",
              None,
              OutputRangeDeclaration.CompilerPosition,
              if classParameter then PsiOutputRoleId.ClassParameter else PsiOutputRoleId.Parameter,
              if classParameter then ClassParameterSurface else ParameterSurface,
              ParameterAccessors
            ),
            outputComposite(
              "parameter-type",
              Some("parameter"),
              OutputRangeDeclaration.BoundaryDerived(
                OutputBoundary.ChildStart(
                  "declared-type",
                  ChildOccurrenceSelector.First,
                  PositionProvenancePolicy.PositionedIncludingSynthetic
                ),
                OutputBoundary.ChildEnd(
                  "declared-type",
                  ChildOccurrenceSelector.Last,
                  PositionProvenancePolicy.PositionedIncludingSynthetic
                )
              ),
              PsiOutputRoleId.ParameterType,
              ParameterTypeSurface,
              ParameterTypeAccessors
            )
          ),
          Map("declared-type" -> Some("parameter-type"), "default" -> None) ++
            Option.when(!contextual)("modifiers" -> Some("parameter"))
        )
      ),
      outputRoleId = None
    )

  private val payloadExpressionProductionIds = Set(
    "definition-payload-number",
    "definition-payload-ident",
    "definition-payload-apply",
    "definition-payload-select",
    "definition-payload-tuple",
    "definition-payload-block",
    "definition-payload-infix",
    "definition-payload-type-apply-positional",
    "definition-payload-type-apply-named",
    "definition-payload-applied-call",
    "payload-descendant-number",
    "payload-descendant-ident",
    "payload-descendant-apply",
    "payload-descendant-select",
    "payload-descendant-tuple",
    "payload-descendant-block",
    "payload-descendant-infix",
    "payload-descendant-type-apply-positional",
    "payload-descendant-type-apply-named"
  )

  private val payloadRootIds            = payloadExpressionProductionIds.filter(_.startsWith("definition-payload-"))
  private val payloadLocalDefinitionIds = Set("payload-descendant-val", "payload-descendant-var")

  private def payloadRoot(
      id: String,
      prefix: String,
      fields: Vector[CompilerFieldPattern],
      children: Vector[ChildDeclaration]
  ) = Scala3PsiProduction(
    id,
    GrammarRoleId.ExpressionPayload,
    CompilerProductionPattern(
      InventoryKind.Node,
      prefix,
      fields,
      definitionChildOccurrences("preRhs").map(_.copy(sourceClassification = SourceClassification.SourceReachable)) ++
        localDefinitionChildOccurrences("preRhs", SourceClassification.SourceReachable)
    ),
    fields.map(field =>
      FieldDisposition(
        field.name,
        if children.exists(_.fieldName == field.name) then FieldDispositionKind.Child
        else FieldDispositionKind.TerminalOrLayout
      )
    ),
    children,
    Vector(
      TerminalDeclaration(
        "payload-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    Vector(LayoutAlternative.None),
    RecoveryPolicy.Reject,
    ExpressionPayloadSurface,
    TargetRequirement.Compatible,
    ExpressionPayloadAccessors,
    PersistenceObligations.NotApplicable,
    Some(NavigationObligation.Self),
    Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "payload",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.ExpressionPayload,
            ExpressionPayloadSurface,
            ExpressionPayloadAccessors,
            TargetRequirement.Compatible
          )
        ),
        children.map(_.roleId -> Some("payload")).toMap
      )
    ),
    Vector.empty,
    None
  )

  private val ExpressionTypeApplicationAnchors = Vector("DefDef", "ValDef").map(owner =>
    InventoryAncestor(InventoryKind.Node, owner, Vector(CatalogPathSegment.NamedField("preRhs")))
  )

  private def expressionTypeApplicationRootOccurrences: Vector[CompilerProductionContextPattern] =
    definitionChildOccurrences("preRhs").map(_.copy(sourceClassification = SourceClassification.SourceReachable)) ++
      localDefinitionChildOccurrences("preRhs", SourceClassification.SourceReachable)

  private def expressionTypeApplicationChildOccurrences(
      owner: String,
      field: String
  ): Vector[CompilerProductionContextPattern] =
    ExpressionTypeApplicationAnchors.map: anchor =>
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchor(
          InventoryKind.Node,
          owner,
          Vector(CatalogPathSegment.NamedField(field)) ++
            Option.when(field == "args")(CatalogPathSegment.RepeatedElement),
          anchor
        ),
        SourceClassification.SourceReachable
      )

  private def outputFreeAppliedCallArgumentOccurrences: Vector[CompilerProductionContextPattern] =
    ExpressionTypeApplicationAnchors.map: anchor =>
      CompilerProductionContextPattern(
        ContextPattern.ParentWithNodeFieldUnderAnchor(
          InventoryKind.Node,
          "Apply",
          Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
          "fun",
          "TypeApply",
          anchor
        ),
        SourceClassification.SourceReachable
      )

  private def outputFreeExpressionProduction(
      id: String,
      prefix: String,
      fields: Vector[CompilerFieldPattern],
      occurrences: Vector[CompilerProductionContextPattern]
  ): Scala3PsiProduction =
    Scala3PsiProduction(
      id,
      GrammarRoleId.OutputFreeExpression,
      CompilerProductionPattern(InventoryKind.Node, prefix, fields, occurrences),
      fields.map(field => FieldDisposition(field.name, FieldDispositionKind.TerminalOrLayout)),
      Vector.empty,
      Vector(
        TerminalDeclaration(
          "output-free-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      Vector(LayoutAlternative.None),
      RecoveryPolicy.Reject,
      ExpressionPayloadSurface,
      TargetRequirement.Compatible,
      Vector.empty,
      PersistenceObligations.NotApplicable,
      None,
      Some(transparentTemplate()),
      Vector.empty,
      None
    )

  private val typeApplicationOutputFreeIdent = outputFreeExpressionProduction(
    "type-application-output-free-ident",
    "Ident",
    Vector(CompilerFieldPattern("name", CatalogValuePattern.Name)),
    expressionTypeApplicationChildOccurrences("TypeApply", "fun")
  )

  private val appliedCallOutputFreeNumber = outputFreeExpressionProduction(
    "type-application-output-free-number",
    "Number",
    Vector(
      CompilerFieldPattern("digits", CatalogValuePattern.Scalar("Text")),
      CompilerFieldPattern(
        "kind",
        CatalogValuePattern.Product(
          "Whole",
          Vector(CompilerFieldPattern("radix", CatalogValuePattern.Scalar("Integer")))
        )
      )
    ),
    outputFreeAppliedCallArgumentOccurrences
  )

  private val appliedCallOutputFreeLiteral = outputFreeExpressionProduction(
    "type-application-output-free-literal",
    "Literal",
    Vector(
      CompilerFieldPattern(
        "const",
        CatalogValuePattern.Product("", Vector(CompilerFieldPattern("", CatalogValuePattern.Scalar("Text"))))
      )
    ),
    outputFreeAppliedCallArgumentOccurrences
  )

  private def expressionTypeArgumentIdent(
      id: String,
      occurrences: Vector[CompilerProductionContextPattern],
      role: GrammarRoleId
  ): Scala3PsiProduction =
    positionalTypeArgumentProduction.copy(
      id = id,
      grammarRoleId = role,
      pattern = positionalTypeArgumentProduction.pattern.copy(occurrences = occurrences)
    )

  private val expressionPositionalTypeArgument = expressionTypeArgumentIdent(
    "expression-type-argument-ident",
    expressionTypeApplicationChildOccurrences("TypeApply", "args"),
    GrammarRoleId.PositionalTypeArgument
  )

  private val expressionNamedArgumentType = expressionTypeArgumentIdent(
    "expression-named-type-argument-type",
    expressionTypeApplicationChildOccurrences("NamedArg", "arg"),
    GrammarRoleId.SimpleType
  )

  private val expressionNamedTypeArgument = Scala3PsiProduction(
    id = "expression-named-type-argument",
    grammarRoleId = GrammarRoleId.NamedTypeArgument,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "NamedArg",
      Vector(
        CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)),
        CompilerFieldPattern("arg", CatalogValuePattern.Node)
      ),
      expressionTypeApplicationChildOccurrences("TypeApply", "args")
    ),
    dispositions = Vector(
      FieldDisposition("name", FieldDispositionKind.TerminalOrLayout),
      FieldDisposition("arg", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "type",
        "arg",
        ChildCardinality.ExactlyOne,
        "expression-named-type-argument-type"
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "named-argument-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "named-argument-assignment",
        TerminalIntervalSelector.BeforeChild("type"),
        TerminalLeafTarget.Token(NativePsiElementBindings.AssignmentTokenSurface, Some("=")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = NamedTypeArgumentSurface,
    targetRequirement = TargetRequirement.Compatible,
    accessors = NamedTypeArgumentAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "named",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.NamedTypeArgument,
            NamedTypeArgumentSurface,
            NamedTypeArgumentAccessors,
            TargetRequirement.Compatible
          ),
          outputComposite(
            "name",
            Some("named"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.ProductionPoint,
              OutputBoundary.ProductionNameEnd
            ),
            PsiOutputRoleId.StableReference,
            StableReferenceSurface,
            StableReferenceAccessors
          )
        ),
        Map("type" -> Some("named"))
      )
    ),
    outputRoleId = None
  )

  private def expressionTypeApplyProduction(
      id: String,
      named: Boolean,
      root: Boolean
  ): Scala3PsiProduction =
    val argumentPattern =
      if named then CatalogValuePattern.NodePrefix("NamedArg")
      else CatalogValuePattern.NodeExceptPrefix("NamedArg")
    val argumentId      = if named then "expression-named-type-argument" else "expression-type-argument-ident"
    val argumentRole    = if named then PsiOutputRoleId.NamedTypeArguments else PsiOutputRoleId.TypeArguments
    val argumentSurface = if named then NamedTypeArgumentsSurface else TypeArgumentsSurface
    val argumentAccess  = if named then NamedTypeArgumentsAccessors else TypeArgumentsAccessors
    val argumentTarget  = if named then TargetRequirement.Compatible else TargetRequirement.Native
    val payload         = Option.when(root)(
      outputComposite(
        "payload",
        None,
        OutputRangeDeclaration.CompilerPosition,
        PsiOutputRoleId.ExpressionPayload,
        ExpressionPayloadSurface,
        ExpressionPayloadAccessors,
        TargetRequirement.Compatible
      )
    )
    val argumentParent  = Option.when(root)("payload")
    Scala3PsiProduction(
      id = id,
      grammarRoleId = GrammarRoleId.ExpressionTypeApply,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "TypeApply",
        Vector(
          CompilerFieldPattern("fun", CatalogValuePattern.Node),
          CompilerFieldPattern("args", CatalogValuePattern.Repeated(argumentPattern))
        ),
        if root then expressionTypeApplicationRootOccurrences
        else expressionTypeApplicationChildOccurrences("Apply", "fun")
      ),
      dispositions = Vector(
        FieldDisposition("fun", FieldDispositionKind.Child),
        FieldDisposition("args", FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration("fun", "fun", ChildCardinality.ExactlyOne, "type-application-output-free-ident"),
        ChildDeclaration("arguments", "args", ChildCardinality.Repeated(1, None), argumentId)
      ),
      terminals = Vector(
        TerminalDeclaration(
          "type-application-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = ExpressionPayloadSurface,
      targetRequirement = TargetRequirement.Compatible,
      accessors = ExpressionPayloadAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputTemplate = Some(
        LocalOutputCompositeTemplate(
          payload.toVector :+ outputComposite(
            "arguments",
            argumentParent,
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.ChildEnd("fun", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly),
              OutputBoundary.ProductionEnd()
            ),
            argumentRole,
            argumentSurface,
            argumentAccess,
            argumentTarget
          ),
          Map("fun" -> argumentParent, "arguments" -> Some("arguments"))
        )
      ),
      outputRoleId = None,
      additionalGrammarRoleIds = Set(GrammarRoleId.TypeArgumentList)
    )

  private def expressionAppliedCallProduction: Scala3PsiProduction =
    Scala3PsiProduction(
      id = "definition-payload-applied-call",
      grammarRoleId = GrammarRoleId.ExpressionTypeApply,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Apply",
        Vector(
          CompilerFieldPattern("fun", CatalogValuePattern.NodePrefix("TypeApply")),
          CompilerFieldPattern("args", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
        ),
        expressionTypeApplicationRootOccurrences
      ),
      dispositions = Vector(
        FieldDisposition("fun", FieldDispositionKind.Child),
        FieldDisposition("args", FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration(
          "fun",
          "fun",
          ChildCardinality.ExactlyOne,
          "payload-descendant-type-apply-positional",
          Set("payload-descendant-type-apply-named")
        ),
        ChildDeclaration(
          "args",
          "args",
          ChildCardinality.Repeated(0, None),
          "type-application-output-free-number",
          Set("type-application-output-free-literal")
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "applied-call-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = ExpressionPayloadSurface,
      targetRequirement = TargetRequirement.Compatible,
      accessors = ExpressionPayloadAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputTemplate = Some(
        LocalOutputCompositeTemplate(
          Vector(
            outputComposite(
              "payload",
              None,
              OutputRangeDeclaration.CompilerPosition,
              PsiOutputRoleId.ExpressionPayload,
              ExpressionPayloadSurface,
              ExpressionPayloadAccessors,
              TargetRequirement.Compatible
            )
          ),
          Map("fun" -> Some("payload"), "args" -> Some("payload"))
        )
      ),
      outputRoleId = None
    )

  private val definitionPayloadProductions = Vector(
    payloadRoot(
      "definition-payload-number",
      "Number",
      Vector(
        CompilerFieldPattern("digits", CatalogValuePattern.Scalar("Text")),
        CompilerFieldPattern(
          "kind",
          CatalogValuePattern
            .Product("Whole", Vector(CompilerFieldPattern("radix", CatalogValuePattern.Scalar("Integer"))))
        )
      ),
      Vector.empty
    ),
    payloadRoot(
      "definition-payload-ident",
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.Name)),
      Vector.empty
    ),
    payloadRoot(
      "definition-payload-apply",
      "Apply",
      Vector(
        CompilerFieldPattern("fun", CatalogValuePattern.NodeExceptPrefix("TypeApply")),
        CompilerFieldPattern("args", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
      ),
      Vector(
        ChildDeclaration(
          "fun",
          "fun",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        ),
        ChildDeclaration(
          "args",
          "args",
          ChildCardinality.Repeated(0, None),
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        )
      )
    ),
    payloadRoot(
      "definition-payload-select",
      "Select",
      Vector(
        CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
        CompilerFieldPattern("name", CatalogValuePattern.Name)
      ),
      Vector(
        ChildDeclaration(
          "qualifier",
          "qualifier",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        )
      )
    ),
    payloadRoot(
      "definition-payload-tuple",
      "Tuple",
      Vector(CompilerFieldPattern("trees", CatalogValuePattern.Repeated(CatalogValuePattern.Node))),
      Vector(
        ChildDeclaration(
          "trees",
          "trees",
          ChildCardinality.Repeated(1, None),
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        )
      )
    ),
    payloadRoot(
      "definition-payload-block",
      "Block",
      Vector(
        CompilerFieldPattern("stats", CatalogValuePattern.Repeated(CatalogValuePattern.Node)),
        CompilerFieldPattern("expr", CatalogValuePattern.Node)
      ),
      Vector(
        ChildDeclaration(
          "stats",
          "stats",
          ChildCardinality.Repeated(0, None),
          payloadLocalDefinitionIds.head,
          payloadLocalDefinitionIds.tail
        ),
        ChildDeclaration(
          "expr",
          "expr",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        )
      )
    ),
    payloadRoot(
      "definition-payload-infix",
      "InfixOp",
      Vector(
        CompilerFieldPattern("left", CatalogValuePattern.Node),
        CompilerFieldPattern("op", CatalogValuePattern.Node),
        CompilerFieldPattern("right", CatalogValuePattern.Node)
      ),
      Vector(
        ChildDeclaration(
          "left",
          "left",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        ),
        ChildDeclaration(
          "op",
          "op",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        ),
        ChildDeclaration(
          "right",
          "right",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        )
      )
    ),
    expressionTypeApplyProduction("definition-payload-type-apply-positional", named = false, root = true),
    expressionTypeApplyProduction("definition-payload-type-apply-named", named = true, root = true),
    expressionAppliedCallProduction
  )

  private def payloadDescendant(
      id: String,
      prefix: String,
      fields: Vector[CompilerFieldPattern],
      dispositions: Vector[FieldDisposition],
      children: Vector[ChildDeclaration],
      grammarRoleId: GrammarRoleId = GrammarRoleId.ExpressionPayload
  ) =
    val anchors = Vector("DefDef", "ValDef").map(owner =>
      InventoryAncestor(InventoryKind.Node, owner, Vector(CatalogPathSegment.NamedField("preRhs")))
    )
    val parents = Vector(
      "Apply"   -> Vector(CatalogPathSegment.NamedField("fun")),
      "Apply"   -> Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
      "Select"  -> Vector(CatalogPathSegment.NamedField("qualifier")),
      "Tuple"   -> Vector(CatalogPathSegment.NamedField("trees"), CatalogPathSegment.RepeatedElement),
      "Block"   -> Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement),
      "Block"   -> Vector(CatalogPathSegment.NamedField("expr")),
      "InfixOp" -> Vector(CatalogPathSegment.NamedField("left")),
      "InfixOp" -> Vector(CatalogPathSegment.NamedField("op")),
      "InfixOp" -> Vector(CatalogPathSegment.NamedField("right"))
    )
    Scala3PsiProduction(
      id,
      grammarRoleId,
      CompilerProductionPattern(
        InventoryKind.Node,
        prefix,
        fields,
        anchors.flatMap(anchor =>
          parents.map((parent, path) =>
            CompilerProductionContextPattern(
              if parent == "Apply" && path.headOption.contains(CatalogPathSegment.NamedField("args")) then
                ContextPattern.ParentWithoutNodeFieldPrefixUnderAnchor(
                  InventoryKind.Node,
                  parent,
                  path,
                  "fun",
                  "TypeApply",
                  anchor
                )
              else ContextPattern.ParentUnderAnchor(InventoryKind.Node, parent, path, anchor),
              SourceClassification.SourceReachable
            )
          )
        )
      ),
      dispositions,
      children,
      Vector(
        TerminalDeclaration(
          "payload-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      Vector(LayoutAlternative.None),
      RecoveryPolicy.Reject,
      ExpressionPayloadSurface,
      TargetRequirement.Compatible,
      ExpressionPayloadAccessors,
      PersistenceObligations.NotApplicable,
      Some(NavigationObligation.Self),
      Some(
        LocalOutputCompositeTemplate(
          Vector(
            outputComposite(
              "payload",
              None,
              OutputRangeDeclaration.CompilerPosition,
              PsiOutputRoleId.ExpressionPayload,
              ExpressionPayloadSurface,
              ExpressionPayloadAccessors,
              TargetRequirement.Compatible
            )
          ),
          children.map(_.roleId -> Some("payload")).toMap
        )
      ),
      Vector.empty,
      None
    )

  private val payloadDescendantProductions = Vector(
    payloadDescendant(
      "payload-descendant-ident",
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.Name)),
      Vector(FieldDisposition("name", FieldDispositionKind.SemanticOnly)),
      Vector.empty
    ),
    payloadDescendant(
      "payload-descendant-number",
      "Number",
      Vector(
        CompilerFieldPattern("digits", CatalogValuePattern.Scalar("Text")),
        CompilerFieldPattern(
          "kind",
          CatalogValuePattern
            .Product("Whole", Vector(CompilerFieldPattern("radix", CatalogValuePattern.Scalar("Integer"))))
        )
      ),
      Vector(
        FieldDisposition("digits", FieldDispositionKind.SemanticOnly),
        FieldDisposition("kind", FieldDispositionKind.SemanticOnly)
      ),
      Vector.empty
    ),
    payloadDescendant(
      "payload-descendant-apply",
      "Apply",
      Vector(
        CompilerFieldPattern("fun", CatalogValuePattern.NodeExceptPrefix("TypeApply")),
        CompilerFieldPattern("args", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
      ),
      Vector(FieldDisposition("fun", FieldDispositionKind.Child), FieldDisposition("args", FieldDispositionKind.Child)),
      Vector(
        ChildDeclaration(
          "fun",
          "fun",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        ),
        ChildDeclaration(
          "args",
          "args",
          ChildCardinality.Repeated(0, None),
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        )
      )
    ),
    payloadDescendant(
      "payload-descendant-select",
      "Select",
      Vector(
        CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
        CompilerFieldPattern("name", CatalogValuePattern.Name)
      ),
      Vector(
        FieldDisposition("qualifier", FieldDispositionKind.Child),
        FieldDisposition("name", FieldDispositionKind.SemanticOnly)
      ),
      Vector(
        ChildDeclaration(
          "qualifier",
          "qualifier",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        )
      )
    ),
    payloadDescendant(
      "payload-descendant-tuple",
      "Tuple",
      Vector(CompilerFieldPattern("trees", CatalogValuePattern.Repeated(CatalogValuePattern.Node))),
      Vector(FieldDisposition("trees", FieldDispositionKind.Child)),
      Vector(
        ChildDeclaration(
          "trees",
          "trees",
          ChildCardinality.Repeated(1, None),
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        )
      )
    ),
    payloadDescendant(
      "payload-descendant-block",
      "Block",
      Vector(
        CompilerFieldPattern("stats", CatalogValuePattern.Repeated(CatalogValuePattern.Node)),
        CompilerFieldPattern("expr", CatalogValuePattern.Node)
      ),
      Vector(
        FieldDisposition("stats", FieldDispositionKind.Child),
        FieldDisposition("expr", FieldDispositionKind.Child)
      ),
      Vector(
        ChildDeclaration(
          "stats",
          "stats",
          ChildCardinality.Repeated(0, None),
          payloadLocalDefinitionIds.head,
          payloadLocalDefinitionIds.tail
        ),
        ChildDeclaration(
          "expr",
          "expr",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        )
      )
    ),
    payloadDescendant(
      "payload-descendant-infix",
      "InfixOp",
      Vector(
        CompilerFieldPattern("left", CatalogValuePattern.Node),
        CompilerFieldPattern("op", CatalogValuePattern.Node),
        CompilerFieldPattern("right", CatalogValuePattern.Node)
      ),
      Vector(
        FieldDisposition("left", FieldDispositionKind.Child),
        FieldDisposition("op", FieldDispositionKind.Child),
        FieldDisposition("right", FieldDispositionKind.Child)
      ),
      Vector(
        ChildDeclaration(
          "left",
          "left",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        ),
        ChildDeclaration(
          "op",
          "op",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        ),
        ChildDeclaration(
          "right",
          "right",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        )
      )
    ),
    expressionTypeApplyProduction("payload-descendant-type-apply-positional", named = false, root = false),
    expressionTypeApplyProduction("payload-descendant-type-apply-named", named = true, root = false),
    payloadLocalDefinition("payload-descendant-val", 0L, mutable = false),
    payloadLocalDefinition("payload-descendant-var", 4097L, mutable = true)
  )

  private def payloadLocalDefinition(id: String, flags: Long, mutable: Boolean): Scala3PsiProduction =
    val modifiers =
      if mutable then
        CatalogValuePattern.Product(
          "Modifiers",
          Vector(
            CompilerFieldPattern("flags", CatalogValuePattern.ExactScalar("LongInteger", s"LongInteger($flags)")),
            CompilerFieldPattern("privateWithin", CatalogValuePattern.ClassifiedName(NeutralNameClass.Empty)),
            CompilerFieldPattern("annotations", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)),
            CompilerFieldPattern("mods", CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.Positioned))
          )
        )
      else emptyModifiers(flags)
    payloadDescendant(
      id,
      "ValDef",
      Vector(
        CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)),
        CompilerFieldPattern("tpt", CatalogValuePattern.Node),
        CompilerFieldPattern("preRhs", CatalogValuePattern.Node),
        CompilerFieldPattern("mods", modifiers)
      ),
      Vector(
        FieldDisposition("name", FieldDispositionKind.SemanticOnly),
        FieldDisposition("tpt", FieldDispositionKind.Child),
        FieldDisposition("preRhs", FieldDispositionKind.Child),
        FieldDisposition("mods", FieldDispositionKind.SemanticOnly)
      ),
      Vector(
        ChildDeclaration("inferred-type", "tpt", ChildCardinality.ExactlyOne, "definition-inferred-type-absence"),
        ChildDeclaration(
          "payload",
          "preRhs",
          ChildCardinality.ExactlyOne,
          payloadRootIds.head,
          payloadRootIds.tail
        )
      ),
      GrammarRoleId.OutputFreeExpression
    ).copy(outputTemplate = Some(transparentTemplate("inferred-type", "payload")))

  private def definitionShell(
      id: String,
      prefix: String,
      role: PsiOutputRoleId,
      surface: String,
      accessors: Vector[AccessorObligation],
      flags: Long
  ) =
    val function                     = prefix == "DefDef"
    val variable                     = role == PsiOutputRoleId.VariableDefinition
    val modifiersShape               =
      if variable then
        CatalogValuePattern.Product(
          "Modifiers",
          Vector(
            CompilerFieldPattern("flags", CatalogValuePattern.ExactScalar("LongInteger", s"LongInteger($flags)")),
            CompilerFieldPattern("privateWithin", CatalogValuePattern.ClassifiedName(NeutralNameClass.Empty)),
            CompilerFieldPattern("annotations", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)),
            CompilerFieldPattern("mods", CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.Positioned))
          )
        )
      else emptyModifiers(flags)
    val children                     = Vector(
      ChildDeclaration(
        "inferred-type",
        "tpt",
        ChildCardinality.ExactlyOne,
        "definition-inferred-type-absence",
        TypeAtomProductionIds
      ),
      ChildDeclaration(
        "payload",
        "preRhs",
        ChildCardinality.ExactlyOne,
        payloadRootIds.head,
        payloadRootIds.tail + "template-absent-tree"
      )
    ) ++ Option.when(!variable)(
      ChildDeclaration(
        "modifiers",
        "mods",
        ChildCardinality.ExactlyOne,
        "modifiers-absent"
      )
    ) ++ Option.when(function)(
      ChildDeclaration(
        "parameters",
        "paramss",
        ChildCardinality.Repeated(0, None),
        "definition-typed-parameter",
        slice = ChildSlice.MatchingProductions
      )
    ) ++ Option.when(function)(
      ChildDeclaration(
        "type-parameters",
        "paramss",
        ChildCardinality.Grouped(0, None),
        "function-unbounded-type-parameter",
        slice = ChildSlice.MatchingProductions
      )
    )
    val declarationRole              =
      if function then PsiOutputRoleId.FunctionDeclaration
      else if variable then PsiOutputRoleId.VariableDeclaration
      else PsiOutputRoleId.ValueDeclaration
    val declarationSurface           =
      if function then FunctionDeclarationSurface
      else if variable then VariableDeclarationSurface
      else ValueDeclarationSurface
    val declarationAccessors         =
      if function then FunctionDeclarationAccessors else PropertyDeclarationAccessors
    def root(declaration: Boolean)   =
      outputComposite(
        "definition",
        None,
        OutputRangeDeclaration.CompilerPosition,
        if declaration then declarationRole else role,
        if declaration then declarationSurface else surface,
        if declaration then declarationAccessors else accessors
      )
    def extras(declaration: Boolean) =
      if function then
        Vector(
          zeroOutput(
            "parameters",
            "definition",
            PsiOutputRoleId.ParameterClauses,
            ParameterClausesSurface,
            OutputBoundary.ProductionNameEnd
          )
        )
      else if declaration then
        Vector(
          outputComposite(
            "identifiers",
            Some("definition"),
            OutputRangeDeclaration.BoundaryDerived(OutputBoundary.ProductionPoint, OutputBoundary.ProductionNameEnd),
            PsiOutputRoleId.IdentifierList,
            IdentifierListSurface,
            IdentifierListAccessors
          ),
          outputComposite(
            "field-id",
            Some("identifiers"),
            OutputRangeDeclaration.BoundaryDerived(OutputBoundary.ProductionPoint, OutputBoundary.ProductionNameEnd),
            PsiOutputRoleId.FieldId,
            FieldIdSurface,
            FieldIdAccessors
          )
        )
      else
        Vector(
          outputComposite(
            "patterns",
            Some("definition"),
            OutputRangeDeclaration.BoundaryDerived(OutputBoundary.ProductionPoint, OutputBoundary.ProductionNameEnd),
            PsiOutputRoleId.PatternList,
            PatternListSurface,
            PatternListAccessors
          ),
          outputComposite(
            "binding",
            Some("patterns"),
            OutputRangeDeclaration.BoundaryDerived(OutputBoundary.ProductionPoint, OutputBoundary.ProductionNameEnd),
            PsiOutputRoleId.ReferencePattern,
            ReferencePatternSurface,
            ReferencePatternAccessors
          )
        )
    Scala3PsiProduction(
      id,
      if function then GrammarRoleId.FunctionDefinition else GrammarRoleId.PropertyDefinition,
      CompilerProductionPattern(
        InventoryKind.Node,
        prefix,
        (if function then
           Vector(
             CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)),
             CompilerFieldPattern(
               "paramss",
               CatalogValuePattern.Repeated(CatalogValuePattern.Repeated(CatalogValuePattern.Node))
             )
           )
         else
           Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)))
        ) ++ Vector(
          CompilerFieldPattern("tpt", CatalogValuePattern.Node),
          CompilerFieldPattern("preRhs", CatalogValuePattern.Node),
          CompilerFieldPattern("mods", modifiersShape)
        ),
        definitionOccurrences("PackageDef") ++ definitionOccurrences("Template", "preBody")
      ),
      (if function then
         Vector(
           FieldDisposition("name", FieldDispositionKind.TerminalOrLayout),
           FieldDisposition("paramss", FieldDispositionKind.Child)
         )
       else Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout))) ++ Vector(
        FieldDisposition("tpt", FieldDispositionKind.Child),
        FieldDisposition("preRhs", FieldDispositionKind.Child),
        FieldDisposition("mods", if variable then FieldDispositionKind.SemanticOnly else FieldDispositionKind.Child)
      ),
      children,
      Vector(
        TerminalDeclaration(
          "definition-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ) ++ Option.when(!function && !variable)(
        TerminalDeclaration(
          "value-keyword",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(NativePsiElementBindings.ValueKeywordTokenSurface, Some("val")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ) ++ Option.when(variable)(
        TerminalDeclaration(
          "variable-keyword",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(NativePsiElementBindings.ModifierKeywordSurfaceIds("Var"), Some("var")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ) ++ Vector(
        TerminalDeclaration(
          "assignment",
          TerminalIntervalSelector.BeforeChild("payload"),
          TerminalLeafTarget.Token(NativePsiElementBindings.AssignmentTokenSurface, Some("=")),
          OccurrenceCardinality.Optional,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      Vector(LayoutAlternative.None),
      RecoveryPolicy.Reject,
      surface,
      TargetRequirement.Native,
      accessors,
      PersistenceObligations.NotApplicable,
      Some(NavigationObligation.Self),
      None,
      (if function then
         val mounts                                                  = Map(
           "inferred-type" -> Some("definition"),
           "payload"       -> Some("definition"),
           "modifiers"     -> Some("definition")
         )
         def typeParameterClause                                     =
           outputComposite(
             "type-parameter-clause",
             Some("definition"),
             OutputRangeDeclaration.BoundaryDerived(
               OutputBoundary.ProductionStart(),
               OutputBoundary.ProductionEnd()
             ),
             PsiOutputRoleId.TypeParameterClause,
             TypeParameterClauseSurface,
             Vector.empty
           ).copy(
             realization = OutputCompositeRealization.PerRepeatedFieldOccurrence(
               "paramss",
               CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("TypeDef")),
               ClosedSourceLexicalKind.LeftBracket,
               ClosedSourceLexicalKind.RightBracket
             )
           )
         def empty(declaration: Boolean, typeParameters: Boolean)    =
           LocalOutputCompositeTemplate(
             root(declaration) +: (extras(declaration) ++ Option.when(typeParameters)(typeParameterClause)),
             mounts ++ Map(
               "parameters"      -> Some("parameters"),
               "type-parameters" -> Option.when(typeParameters)("type-parameter-clause")
             )
           )
         val clauses                                                 = Vector(
           outputComposite(
             "parameters",
             Some("definition"),
             OutputRangeDeclaration.CompilerPosition,
             PsiOutputRoleId.ParameterClauses,
             ParameterClausesSurface,
             Vector.empty
           ).copy(
             realization = OutputCompositeRealization.AcrossRepeatedFieldOccurrences(
               "paramss",
               CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
               ClosedSourceLexicalKind.LeftParenthesis,
               ClosedSourceLexicalKind.RightParenthesis
             )
           ),
           outputComposite(
             "parameter-clause",
             Some("parameters"),
             OutputRangeDeclaration.CompilerPosition,
             PsiOutputRoleId.ParameterClause,
             ParameterClauseSurface,
             Vector.empty
           ).copy(
             realization = OutputCompositeRealization.PerRepeatedFieldOccurrence(
               "paramss",
               CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
               ClosedSourceLexicalKind.LeftParenthesis,
               ClosedSourceLexicalKind.RightParenthesis
             )
           )
         )
         def nonempty(declaration: Boolean, typeParameters: Boolean) =
           LocalOutputCompositeTemplate(
             root(declaration) +: (clauses ++ Option.when(typeParameters)(typeParameterClause)),
             mounts ++ Map(
               "parameters"      -> Some("parameter-clause"),
               "type-parameters" -> Option.when(typeParameters)("type-parameter-clause")
             )
           )
         val declarationCondition                                    = ChildOutcomeCondition(
           "payload",
           ChildOccurrenceSelector.First,
           ChildOutcomeExpectation.Production("template-absent-tree")
         )
         val definitionCondition                                     = Vector.empty[ChildOutcomeCondition]
         def realization(
             id: String,
             declaration: Boolean,
             termParameters: Boolean,
             typeParameters: Boolean
         ) =
           OutputRealization(
             id,
             if declaration then Vector(declarationCondition) else definitionCondition,
             if termParameters then nonempty(declaration, typeParameters) else empty(declaration, typeParameters),
             Vector(
               EvidenceCondition.RepeatedFieldOccurrence(
                 "paramss",
                 CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
                 present = termParameters
               ),
               EvidenceCondition.RepeatedFieldOccurrence(
                 "paramss",
                 CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("TypeDef")),
                 present = typeParameters
               )
             )
           )
         Vector(
           realization(
             "definition-without-parameters",
             declaration = false,
             termParameters = false,
             typeParameters = false
           ),
           realization(
             "definition-with-term-parameters",
             declaration = false,
             termParameters = true,
             typeParameters = false
           ),
           realization(
             "definition-with-type-parameters",
             declaration = false,
             termParameters = false,
             typeParameters = true
           ),
           realization(
             "definition-with-type-and-term-parameters",
             declaration = false,
             termParameters = true,
             typeParameters = true
           ),
           realization(
             "declaration-without-parameters",
             declaration = true,
             termParameters = false,
             typeParameters = false
           ),
           realization(
             "declaration-with-term-parameters",
             declaration = true,
             termParameters = true,
             typeParameters = false
           ),
           realization(
             "declaration-with-type-parameters",
             declaration = true,
             termParameters = false,
             typeParameters = true
           ),
           realization(
             "declaration-with-type-and-term-parameters",
             declaration = true,
             termParameters = true,
             typeParameters = true
           )
         )
       else
         val mounts               = Map(
           "inferred-type" -> Some("definition"),
           "payload"       -> Some("definition")
         ) ++ Option.when(!variable)("modifiers" -> Some("definition"))
         val definitionTemplate   =
           LocalOutputCompositeTemplate(root(declaration = false) +: extras(declaration = false), mounts)
         val declarationTemplate  =
           LocalOutputCompositeTemplate(root(declaration = true) +: extras(declaration = true), mounts)
         val declarationCondition = ChildOutcomeCondition(
           "payload",
           ChildOccurrenceSelector.First,
           ChildOutcomeExpectation.Production("template-absent-tree")
         )
         Vector(
           OutputRealization("definition", Vector.empty, definitionTemplate),
           OutputRealization("declaration", Vector(declarationCondition), declarationTemplate)
         )
      )
      ,
      None,
      Option.when(!function)(GrammarRoleId.ReferenceBinding).toSet
    )

  private val abstractTypeAlias = Scala3PsiProduction(
    "definition-unbounded-type-alias",
    GrammarRoleId.TypeAliasDeclaration,
    CompilerProductionPattern(
      InventoryKind.Node,
      "TypeDef",
      Vector(
        CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)),
        CompilerFieldPattern("rhs", CatalogValuePattern.NodePrefix("TypeBoundsTree")),
        CompilerFieldPattern("mods", emptyModifiers(0L))
      ),
      definitionOccurrences("Template", "preBody")
    ),
    Vector(
      FieldDisposition("name", FieldDispositionKind.TerminalOrLayout),
      FieldDisposition("rhs", FieldDispositionKind.Child),
      FieldDisposition("mods", FieldDispositionKind.Child)
    ),
    Vector(
      ChildDeclaration("bounds", "rhs", ChildCardinality.ExactlyOne, "template-unbounded-type-bounds"),
      ChildDeclaration("modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
    ),
    Vector(
      TerminalDeclaration(
        "alias-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    Vector(LayoutAlternative.None),
    RecoveryPolicy.Reject,
    TypeAliasDeclarationSurface,
    TargetRequirement.Native,
    TypeAliasDeclarationAccessors,
    PersistenceObligations.NotApplicable,
    Some(NavigationObligation.Self),
    Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "alias",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.TypeAliasDeclaration,
            TypeAliasDeclarationSurface,
            TypeAliasDeclarationAccessors
          )
        ),
        Map("bounds" -> None, "modifiers" -> Some("alias"))
      )
    ),
    Vector.empty,
    None
  )

  private def simpleTypeAlias(
      id: String,
      rootProduction: String,
      typeProductionIds: Set[String]
  ): Scala3PsiProduction =
    val firstTypeProduction = typeProductionIds.toVector.sorted.head
    abstractTypeAlias.copy(
      id = id,
      grammarRoleId = GrammarRoleId.TypeAliasDefinition,
      pattern = abstractTypeAlias.pattern.copy(
        fields = abstractTypeAlias.pattern.fields.updated(
          1,
          CompilerFieldPattern("rhs", CatalogValuePattern.NodePrefix(rootProduction))
        ),
        occurrences = definitionOccurrences("PackageDef") ++ definitionOccurrences("Template", "preBody")
      ),
      children = Vector(
        ChildDeclaration(
          "aliased-type",
          "rhs",
          ChildCardinality.ExactlyOne,
          firstTypeProduction,
          typeProductionIds - firstTypeProduction
        ),
        ChildDeclaration("modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
      ),
      terminals = abstractTypeAlias.terminals :+ TerminalDeclaration(
        "alias-assignment",
        TerminalIntervalSelector.BeforeChild("aliased-type"),
        TerminalLeafTarget.Token(NativePsiElementBindings.AssignmentTokenSurface, Some("=")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      targetSurfaceId = TypeAliasDefinitionSurface,
      accessors = TypeAliasDefinitionAccessors,
      outputTemplate = Some(
        LocalOutputCompositeTemplate(
          Vector(
            outputComposite(
              "alias",
              None,
              OutputRangeDeclaration.CompilerPosition,
              PsiOutputRoleId.TypeAliasDefinition,
              TypeAliasDefinitionSurface,
              TypeAliasDefinitionAccessors
            )
          ),
          Map("aliased-type" -> Some("alias"), "modifiers" -> Some("alias"))
        )
      )
    )

  private val simpleTypeAliases = Vector(
    simpleTypeAlias("definition-simple-ident-type-alias", "Ident", Set("import-selector-bound-type")),
    simpleTypeAlias(
      "definition-simple-select-type-alias",
      "Select",
      Set("import-selector-given-bound-qualified-type", "type-atom-projection")
    ),
    simpleTypeAlias(
      "definition-simple-singleton-type-alias",
      "SingletonTypeTree",
      Set("type-atom-singleton-ident", "type-atom-singleton-select", "type-atom-literal")
    ),
    simpleTypeAlias("definition-simple-literal-type-alias", "Literal", Set("type-atom-literal")),
    simpleTypeAlias("definition-simple-parenthesized-type-alias", "Parens", Set("type-atom-parenthesized")),
    simpleTypeAlias(
      "definition-applied-type-alias",
      "AppliedTypeTree",
      Set("ordinary-applied-type")
    )
  )

  private def templateProductions: Vector[Scala3PsiProduction] = Vector(
    templateOwnerProduction(
      "template-class-definition",
      "TypeDef",
      "rhs",
      0L,
      GrammarRoleId.ClassDefinition,
      PsiOutputRoleId.ClassDefinition,
      ClassDefinitionSurface,
      constructorOwner = true
    ),
    templateOwnerProduction(
      "template-trait-definition",
      "TypeDef",
      "rhs",
      1026L,
      GrammarRoleId.TraitDefinition,
      PsiOutputRoleId.TraitDefinition,
      TraitDefinitionSurface,
      constructorOwner = false
    ),
    templateOwnerProduction(
      "template-object-definition",
      "ModuleDef",
      "impl",
      32771L,
      GrammarRoleId.ObjectDefinition,
      PsiOutputRoleId.ObjectDefinition,
      ObjectDefinitionSurface,
      constructorOwner = false
    ),
    templateOwnerProduction(
      "template-enum-definition",
      "TypeDef",
      "rhs",
      1099511627779L,
      GrammarRoleId.EnumDefinition,
      PsiOutputRoleId.EnumDefinition,
      EnumDefinitionSurface,
      constructorOwner = true
    ),
    templateOwnerProduction(
      "enum-singleton-case",
      "ModuleDef",
      "impl",
      1099511758851L,
      GrammarRoleId.EnumCase,
      PsiOutputRoleId.EnumSingletonCase,
      EnumSingletonCaseSurface,
      constructorOwner = false,
      enumCase = true
    ),
    templateOwnerProduction(
      "enum-class-case",
      "TypeDef",
      "rhs",
      1099511758851L,
      GrammarRoleId.EnumCase,
      PsiOutputRoleId.EnumClassCase,
      EnumClassCaseSurface,
      constructorOwner = false,
      enumCase = true,
      classCase = true
    ),
    templateTemplateProduction,
    templateConstructorSyntheticProduction,
    templateConstructorExplicitProduction,
    templateConstructorTypedParametersProduction,
    templateConstructorTypeParametersProduction,
    unboundedTypeParameterProduction("template-unbounded-type-parameter-invariant", 8455L),
    unboundedTypeParameterProduction("template-unbounded-type-parameter-covariant", 1057030L),
    unboundedTypeParameterProduction("template-unbounded-type-parameter-contravariant", 2105606L),
    unboundedTypeParameterProduction("function-unbounded-type-parameter", 259L),
    unboundedTypeBoundsProduction,
    templateTypeTreeProduction,
    templateSelfProduction,
    templateSimpleSelfProduction,
    templateAbsentTreeProduction,
    inferredDefinitionType,
    typedParameterProduction("definition-typed-parameter", 259L, classParameter = false),
    typedParameterProduction("template-class-parameter", 24581L, classParameter = true),
    typedParameterProduction(
      "template-context-class-parameter",
      536895493L,
      classParameter = true,
      contextual = true
    ),
    definitionShell(
      "definition-function-untyped",
      "DefDef",
      PsiOutputRoleId.FunctionDefinition,
      FunctionDefinitionSurface,
      FunctionDefinitionAccessors,
      129L
    ),
    definitionShell(
      "definition-val-untyped",
      "ValDef",
      PsiOutputRoleId.PatternDefinition,
      PatternDefinitionSurface,
      PropertyDefinitionAccessors,
      0L
    ),
    definitionShell(
      "definition-var-untyped",
      "ValDef",
      PsiOutputRoleId.VariableDefinition,
      VariableDefinitionSurface,
      VariableDefinitionAccessors,
      4097L
    ),
    abstractTypeAlias
  ) ++ simpleTypeAliases ++ definitionPayloadProductions ++ payloadDescendantProductions ++ Vector(
    typeApplicationOutputFreeIdent,
    appliedCallOutputFreeNumber,
    appliedCallOutputFreeLiteral,
    expressionPositionalTypeArgument,
    expressionNamedArgumentType,
    expressionNamedTypeArgument
  )

  private val SingletonReferenceProductionIds = Set(
    "type-atom-singleton-reference-ident",
    "type-atom-singleton-reference-select"
  )
  private val LiteralValueProductionIds       = Set(
    "type-atom-literal-value-integer",
    "type-atom-literal-value-long",
    "type-atom-literal-value-float",
    "type-atom-literal-value-double",
    "type-atom-literal-value-char",
    "type-atom-literal-value-string",
    "type-atom-literal-value-boolean"
  )

  private val singletonReferenceOccurrences = (GivenSelectorBoundAnchor +: OwnerTypeAnchors).map(anchor =>
    CompilerProductionContextPattern(
      ContextPattern.ParentUnderAnchor(
        InventoryKind.Node,
        "SingletonTypeTree",
        Vector(CatalogPathSegment.NamedField("ref")),
        anchor
      ),
      SourceClassification.SourceReachable
    )
  )

  private val singletonTypeOccurrences = typeAtomOccurrences.flatMap: occurrence =>
    Vector(occurrence, occurrence.copy(sourceClassification = SourceClassification.Synthetic))

  private val literalTypeOccurrences = typeAtomOccurrences.map:
    _.copy(sourceClassification = SourceClassification.Synthetic)

  private def literalValueProduction(
      id: String,
      scalarKind: String,
      outputRole: PsiOutputRoleId,
      surface: String
  ): Scala3PsiProduction = Scala3PsiProduction(
    id = id,
    grammarRoleId = GrammarRoleId.LiteralValue,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Literal",
      Vector(
        CompilerFieldPattern(
          "const",
          CatalogValuePattern.Product("", Vector(CompilerFieldPattern("", CatalogValuePattern.Scalar(scalarKind))))
        )
      ),
      singletonReferenceOccurrences
    ),
    dispositions = Vector(FieldDisposition("const", FieldDispositionKind.TerminalOrLayout)),
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "literal-value-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = surface,
    targetRequirement = TargetRequirement.Native,
    accessors = LiteralValueAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRoleId = Some(outputRole)
  )

  private val typeAtomProductions = Vector(
    Scala3PsiProduction(
      id = "type-atom-projection",
      grammarRoleId = GrammarRoleId.TypeProjection,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Select",
        Vector(
          CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
          CompilerFieldPattern("name", CatalogValuePattern.Name)
        ),
        typeAtomOccurrences.map(
          _.copy(scannerEvidence =
            ScannerEvidencePattern(
              required = Set(ParserScannerTokenKind.Hash)
            )
          )
        )
      ),
      dispositions = Vector(
        FieldDisposition("qualifier", FieldDispositionKind.Child),
        FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)
      ),
      children = Vector(
        ChildDeclaration(
          "qualifier",
          "qualifier",
          ChildCardinality.ExactlyOne,
          "import-selector-given-bound-qualifier-ident",
          GivenTypeQualifierProductionIds - "import-selector-given-bound-qualifier-ident"
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "projection-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "projection-hash",
          TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.Hash),
          TerminalLeafTarget.Token(NativePsiElementBindings.TypeProjectionHashTokenSurface, Some("#")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = TypeProjectionSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = TypeProjectionAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(
        LocalOutputCompositeTemplate(
          Vector(
            outputComposite(
              "projection",
              None,
              OutputRangeDeclaration.CompilerPosition,
              PsiOutputRoleId.TypeProjection,
              TypeProjectionSurface,
              TypeProjectionAccessors
            ),
            outputComposite(
              "qualifier-type",
              Some("projection"),
              OutputRangeDeclaration.BoundaryDerived(
                OutputBoundary
                  .ChildStart("qualifier", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly),
                OutputBoundary
                  .ChildEnd("qualifier", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly)
              ),
              PsiOutputRoleId.SimpleType,
              SimpleTypeSurface,
              SimpleTypeAccessors
            )
          ),
          Map("qualifier" -> Some("qualifier-type"))
        )
      )
    ),
    Scala3PsiProduction(
      id = "type-atom-singleton-ident",
      grammarRoleId = GrammarRoleId.SingletonType,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "SingletonTypeTree",
        Vector(CompilerFieldPattern("ref", CatalogValuePattern.NodePrefix("Ident"))),
        singletonTypeOccurrences.map(
          _.copy(scannerEvidence =
            ScannerEvidencePattern(
              required = Set(ParserScannerTokenKind.Dot, ParserScannerTokenKind.TypeKeyword),
              forbidden = Set(ParserScannerTokenKind.Hash)
            )
          )
        )
      ),
      dispositions = Vector(FieldDisposition("ref", FieldDispositionKind.Child)),
      children = Vector(
        ChildDeclaration(
          "reference",
          "ref",
          ChildCardinality.ExactlyOne,
          "type-atom-singleton-reference-ident",
          SingletonReferenceProductionIds - "type-atom-singleton-reference-ident"
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "singleton-dot",
          TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.Dot),
          TerminalLeafTarget.Token(NativePsiElementBindings.TypePathDotTokenSurface, Some(".")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "singleton-type",
          TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.TypeKeyword),
          TerminalLeafTarget.Token(NativePsiElementBindings.SingletonTypeKeywordTokenSurface, Some("type")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = SimpleTypeSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = SimpleTypeAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(
        typeElementTemplateWithRange(
          PsiOutputRoleId.SingletonType,
          SimpleTypeSurface,
          SimpleTypeAccessors,
          OutputRangeDeclaration.CompilerPositionWithPolicy(PositionProvenancePolicy.PositionedIncludingSynthetic),
          "reference"
        )
      )
    ),
    Scala3PsiProduction(
      id = "type-atom-singleton-select",
      grammarRoleId = GrammarRoleId.SingletonType,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "SingletonTypeTree",
        Vector(CompilerFieldPattern("ref", CatalogValuePattern.NodePrefix("Select"))),
        singletonTypeOccurrences.map(
          _.copy(scannerEvidence =
            ScannerEvidencePattern(
              required = Set(ParserScannerTokenKind.Dot, ParserScannerTokenKind.TypeKeyword),
              forbidden = Set(ParserScannerTokenKind.Hash)
            )
          )
        )
      ),
      dispositions = Vector(FieldDisposition("ref", FieldDispositionKind.Child)),
      children = Vector(
        ChildDeclaration(
          "reference",
          "ref",
          ChildCardinality.ExactlyOne,
          "type-atom-singleton-reference-select",
          SingletonReferenceProductionIds - "type-atom-singleton-reference-select"
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "singleton-dot",
          TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.Dot, ScannerTokenOccurrence.Last),
          TerminalLeafTarget.Token(NativePsiElementBindings.TypePathDotTokenSurface, Some(".")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "singleton-type",
          TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.TypeKeyword),
          TerminalLeafTarget.Token(NativePsiElementBindings.SingletonTypeKeywordTokenSurface, Some("type")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = SimpleTypeSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = SimpleTypeAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(
        typeElementTemplateWithRange(
          PsiOutputRoleId.SingletonType,
          SimpleTypeSurface,
          SimpleTypeAccessors,
          OutputRangeDeclaration.CompilerPositionWithPolicy(PositionProvenancePolicy.PositionedIncludingSynthetic),
          "reference"
        )
      )
    ),
    Scala3PsiProduction(
      id = "type-atom-literal",
      grammarRoleId = GrammarRoleId.LiteralType,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "SingletonTypeTree",
        Vector(CompilerFieldPattern("ref", CatalogValuePattern.NodePrefix("Literal"))),
        literalTypeOccurrences.map(
          _.copy(scannerEvidence =
            ScannerEvidencePattern(
              required = Set(ParserScannerTokenKind.Literal),
              forbidden = Set(ParserScannerTokenKind.Hash, ParserScannerTokenKind.TypeKeyword)
            )
          )
        )
      ),
      dispositions = Vector(FieldDisposition("ref", FieldDispositionKind.Child)),
      children = Vector(
        ChildDeclaration(
          "literal",
          "ref",
          ChildCardinality.ExactlyOne,
          "type-atom-literal-value-integer",
          LiteralValueProductionIds - "type-atom-literal-value-integer"
        )
      ),
      terminals = Vector.empty,
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = LiteralTypeSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = LiteralTypeAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(
        typeElementTemplateWithRange(
          PsiOutputRoleId.LiteralType,
          LiteralTypeSurface,
          LiteralTypeAccessors,
          OutputRangeDeclaration.CompilerPositionWithPolicy(PositionProvenancePolicy.PositionedIncludingSynthetic),
          "literal"
        )
      )
    ),
    Scala3PsiProduction(
      id = "type-atom-parenthesized",
      grammarRoleId = GrammarRoleId.ParenthesizedType,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Parens",
        Vector(CompilerFieldPattern("t", CatalogValuePattern.Node)),
        typeAtomOccurrences.map(
          _.copy(scannerEvidence =
            ScannerEvidencePattern(
              required = Set(ParserScannerTokenKind.LeftParenthesis, ParserScannerTokenKind.RightParenthesis)
            )
          )
        )
      ),
      dispositions = Vector(FieldDisposition("t", FieldDispositionKind.Child)),
      children = Vector(
        ChildDeclaration(
          "inner",
          "t",
          ChildCardinality.ExactlyOne,
          "import-selector-bound-type",
          GivenTypeProductionIds - "import-selector-bound-type"
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "left-parenthesis",
          TerminalIntervalSelector
            .CompilerScannerToken(ParserScannerTokenKind.LeftParenthesis, ScannerTokenOccurrence.First),
          TerminalLeafTarget.Token(NativePsiElementBindings.TypeLeftParenthesisTokenSurface, Some("(")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "right-parenthesis",
          TerminalIntervalSelector
            .CompilerScannerToken(ParserScannerTokenKind.RightParenthesis, ScannerTokenOccurrence.Last),
          TerminalLeafTarget.Token(NativePsiElementBindings.TypeRightParenthesisTokenSurface, Some(")")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = ParenthesizedTypeSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = ParenthesizedTypeAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(
        typeElementTemplate(
          PsiOutputRoleId.ParenthesizedType,
          ParenthesizedTypeSurface,
          ParenthesizedTypeAccessors,
          "inner"
        )
      )
    ),
    Scala3PsiProduction(
      id = "type-atom-singleton-reference-ident",
      grammarRoleId = GrammarRoleId.StableReference,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Ident",
        Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
        singletonReferenceOccurrences
      ),
      dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
      children = Vector.empty,
      terminals = Vector(
        TerminalDeclaration(
          "reference-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = StableReferenceSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = StableReferenceAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = Some(PsiOutputRoleId.StableReference)
    ),
    Scala3PsiProduction(
      id = "type-atom-singleton-reference-select",
      grammarRoleId = GrammarRoleId.StableReference,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Select",
        Vector(
          CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
          CompilerFieldPattern("name", CatalogValuePattern.Name)
        ),
        singletonReferenceOccurrences
      ),
      dispositions = Vector(
        FieldDisposition("qualifier", FieldDispositionKind.Child),
        FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)
      ),
      children = Vector(
        ChildDeclaration(
          "qualifier",
          "qualifier",
          ChildCardinality.ExactlyOne,
          "import-selector-given-bound-qualifier-ident",
          GivenTypeQualifierProductionIds - "import-selector-given-bound-qualifier-ident"
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "reference-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "path-dot",
          TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.Dot),
          TerminalLeafTarget.Token(NativePsiElementBindings.TypePathDotTokenSurface, Some(".")),
          OccurrenceCardinality.Repeated(1, None),
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = StableReferenceSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = StableReferenceAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(stableReferenceTemplate("qualifier"))
    ),
    literalValueProduction(
      "type-atom-literal-value-integer",
      "Integer",
      PsiOutputRoleId.IntegerLiteralValue,
      IntegerLiteralSurface
    ),
    literalValueProduction(
      "type-atom-literal-value-long",
      "LongInteger",
      PsiOutputRoleId.LongLiteralValue,
      LongLiteralSurface
    ),
    literalValueProduction(
      "type-atom-literal-value-float",
      "FloatDecimal",
      PsiOutputRoleId.FloatLiteralValue,
      FloatLiteralSurface
    ),
    literalValueProduction(
      "type-atom-literal-value-double",
      "Decimal",
      PsiOutputRoleId.DoubleLiteralValue,
      DoubleLiteralSurface
    ),
    literalValueProduction(
      "type-atom-literal-value-char",
      "Character",
      PsiOutputRoleId.CharLiteralValue,
      CharLiteralSurface
    ),
    literalValueProduction(
      "type-atom-literal-value-string",
      "Text",
      PsiOutputRoleId.StringLiteralValue,
      StringLiteralSurface
    ),
    literalValueProduction(
      "type-atom-literal-value-boolean",
      "Logical",
      PsiOutputRoleId.BooleanLiteralValue,
      BooleanLiteralSurface
    )
  )

  val Reviewed: Scala3PsiProductionCatalog = Scala3PsiProductionCatalog(
    Vector(
      packageProduction("file-package", body = false),
      packageProduction("file-package-top-statements", body = true),
      Scala3PsiProduction(
        id = "file-top-statements",
        grammarRoleId = GrammarRoleId.CompilationUnit,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "PackageDef",
          Vector(
            CompilerFieldPattern("pid", CatalogValuePattern.Node),
            CompilerFieldPattern("stats", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
          ),
          Vector(CompilerProductionContextPattern(ContextPattern.Root, SourceClassification.Synthetic))
        ),
        dispositions = Vector(
          FieldDisposition("pid", FieldDispositionKind.Synthetic),
          FieldDisposition("stats", FieldDispositionKind.Child)
        ),
        children = Vector(
          ChildDeclaration(
            "top-statements",
            "stats",
            ChildCardinality.Grouped(1, None),
            "import-statement",
            Set(
              "export-statement",
              "file-package",
              "file-package-top-statements",
              "template-class-definition",
              "template-trait-definition",
              "template-object-definition",
              "template-enum-definition",
              "definition-function-untyped",
              "definition-val-untyped",
              "definition-var-untyped"
            ) ++ SimpleTypeAliasProductionIds
          )
        ),
        terminals = Vector(
          TerminalDeclaration(
            "whole-file",
            TerminalIntervalSelector.WholeSource,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = ImportStatementSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = ImportStatementAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = None,
        outputTemplate = Some(transparentTemplate("top-statements"))
      ),
      statementProduction(
        id = "import-statement",
        compilerProduction = "Import",
        grammarRole = GrammarRoleId.ImportStatement,
        outputRole = PsiOutputRoleId.ImportStatement,
        statementSurface = ImportStatementSurface,
        statementAccessors = ImportStatementAccessors
      ),
      statementProduction(
        id = "export-statement",
        compilerProduction = "Export",
        grammarRole = GrammarRoleId.ExportStatement,
        outputRole = PsiOutputRoleId.ExportStatement,
        statementSurface = ExportStatementSurface,
        statementAccessors = ExportStatementAccessors
      ),
      Scala3PsiProduction(
        id = "import-expression-absent",
        grammarRoleId = GrammarRoleId.AbsentProduct,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Thicket",
          Vector(CompilerFieldPattern("trees", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node))),
          Vector("Import", "Export").map: owner =>
            CompilerProductionContextPattern(
              ContextPattern.Parent(InventoryKind.Node, owner, Vector(CatalogPathSegment.NamedField("expr"))),
              SourceClassification.Absent
            )
        ),
        dispositions = Vector(FieldDisposition("trees", FieldDispositionKind.Synthetic)),
        children = Vector.empty,
        terminals = Vector.empty,
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = ImportExpressionSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = ImportExpressionAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = None,
        outputTemplate = Some(transparentTemplate())
      ),
      Scala3PsiProduction(
        id = "file-import-empty-package",
        grammarRoleId = GrammarRoleId.PackageReference,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Ident",
          Vector(
            CompilerFieldPattern(
              "name",
              CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)
            )
          ),
          Vector(
            CompilerProductionContextPattern(
              ContextPattern.Parent(
                InventoryKind.Node,
                "PackageDef",
                Vector(CatalogPathSegment.NamedField("pid"))
              ),
              SourceClassification.Synthetic
            )
          )
        ),
        dispositions = Vector(FieldDisposition("name", FieldDispositionKind.Synthetic)),
        children = Vector.empty,
        terminals = Vector.empty,
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = ImportStatementSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = Vector.empty,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = None,
        outputTemplate = Some(transparentTemplate())
      ),
      Scala3PsiProduction(
        id = "import-path-identifier-reference",
        grammarRoleId = GrammarRoleId.StableReference,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Ident",
          Vector(
            CompilerFieldPattern(
              "name",
              CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)
            )
          ),
          Vector("Import", "Export").map: owner =>
            CompilerProductionContextPattern(
              ContextPattern.Parent(
                InventoryKind.Node,
                owner,
                Vector(CatalogPathSegment.NamedField("expr"))
              ),
              SourceClassification.SourceReachable
            )
        ),
        dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
        children = Vector.empty,
        terminals = Vector(
          TerminalDeclaration(
            "identifier-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = StableReferenceSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = StableReferenceAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = Some(PsiOutputRoleId.StableReference)
      ),
      Scala3PsiProduction(
        id = "import-path-reference",
        grammarRoleId = GrammarRoleId.StableReference,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Select",
          Vector(
            CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
            CompilerFieldPattern(
              "name",
              CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)
            )
          ),
          Vector("Import", "Export").map: owner =>
            CompilerProductionContextPattern(
              ContextPattern.AnchorOrParentWithRepeatedAncestor(
                InventoryAncestor(
                  InventoryKind.Node,
                  owner,
                  Vector(CatalogPathSegment.NamedField("expr"))
                ),
                InventoryKind.Node,
                "Select",
                Vector(CatalogPathSegment.NamedField("qualifier")),
                InventoryAncestor(
                  InventoryKind.Node,
                  "Select",
                  Vector(CatalogPathSegment.NamedField("qualifier"))
                )
              ),
              SourceClassification.SourceReachable
            )
        ),
        dispositions = Vector(
          FieldDisposition("qualifier", FieldDispositionKind.Child),
          FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)
        ),
        children = Vector(
          ChildDeclaration(
            "qualifier",
            "qualifier",
            ChildCardinality.ExactlyOne,
            "import-path-identifier",
            Set("import-path-reference")
          )
        ),
        terminals = Vector(
          TerminalDeclaration(
            "reference-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = StableReferenceSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = StableReferenceAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = Some(PsiOutputRoleId.StableReference)
      ),
      Scala3PsiProduction(
        id = "import-path-identifier",
        grammarRoleId = GrammarRoleId.StableReference,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Ident",
          Vector(
            CompilerFieldPattern(
              "name",
              CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)
            )
          ),
          Vector("Import", "Export").map: owner =>
            CompilerProductionContextPattern(
              ContextPattern.ParentWithRepeatedAncestor(
                InventoryKind.Node,
                "Select",
                Vector(CatalogPathSegment.NamedField("qualifier")),
                InventoryAncestor(
                  InventoryKind.Node,
                  "Select",
                  Vector(CatalogPathSegment.NamedField("qualifier"))
                ),
                InventoryAncestor(
                  InventoryKind.Node,
                  owner,
                  Vector(CatalogPathSegment.NamedField("expr"))
                )
              ),
              SourceClassification.SourceReachable
            )
        ),
        dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
        children = Vector.empty,
        terminals = Vector(
          TerminalDeclaration(
            "identifier-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = StableReferenceSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = StableReferenceAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = Some(PsiOutputRoleId.StableReference)
      ),
      Scala3PsiProduction(
        id = "import-selector-direct",
        grammarRoleId = GrammarRoleId.ImportSelector,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "ImportSelector",
          Vector(
            CompilerFieldPattern("imported", CatalogValuePattern.Node),
            CompilerFieldPattern("renamed", CatalogValuePattern.Node),
            CompilerFieldPattern("bound", CatalogValuePattern.Node)
          ),
          Vector("Import", "Export").map: owner =>
            CompilerProductionContextPattern(
              ContextPattern.Parent(
                InventoryKind.Node,
                owner,
                Vector(CatalogPathSegment.NamedField("selectors"), CatalogPathSegment.RepeatedElement)
              ),
              SourceClassification.Synthetic
            )
        ),
        dispositions = Vector(
          FieldDisposition("imported", FieldDispositionKind.Child),
          FieldDisposition("renamed", FieldDispositionKind.Child),
          FieldDisposition("bound", FieldDispositionKind.Child)
        ),
        children = Vector(
          ChildDeclaration(
            "imported",
            "imported",
            ChildCardinality.ExactlyOne,
            "import-selector-name",
            Set("import-selector-wildcard-name", "import-selector-empty-name")
          ),
          ChildDeclaration(
            "renamed",
            "renamed",
            ChildCardinality.ExactlyOne,
            "import-selector-name",
            Set("import-selector-hidden-name", "import-selector-absent")
          ),
          ChildDeclaration(
            "bound",
            "bound",
            ChildCardinality.ExactlyOne,
            "import-selector-bound-type",
            (GivenTypeProductionIds - "import-selector-bound-type") + "import-selector-absent"
          )
        ),
        terminals = Vector(
          TerminalDeclaration(
            "selector-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.Optional,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = ImportSelectorSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = ImportSelectorAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = None,
        outputRealizations = Vector(
          OutputRealization(
            "direct-plain",
            Vector(
              ChildOutcomeCondition(
                "imported",
                ChildOccurrenceSelector.First,
                ChildOutcomeExpectation.Production("import-selector-name")
              ),
              ChildOutcomeCondition(
                "renamed",
                ChildOccurrenceSelector.First,
                ChildOutcomeExpectation.Production("import-selector-absent")
              ),
              ChildOutcomeCondition(
                "bound",
                ChildOccurrenceSelector.First,
                ChildOutcomeExpectation.Production("import-selector-absent")
              )
            ),
            transparentTemplate("imported", "renamed", "bound")
          ),
          OutputRealization(
            "direct-wildcard",
            Vector(
              ChildOutcomeCondition(
                "imported",
                ChildOccurrenceSelector.First,
                ChildOutcomeExpectation.Production("import-selector-wildcard-name")
              )
            ),
            transparentTemplate("imported", "renamed", "bound")
          ),
          OutputRealization(
            "direct-given",
            Vector(
              ChildOutcomeCondition(
                "imported",
                ChildOccurrenceSelector.First,
                ChildOutcomeExpectation.Production("import-selector-empty-name")
              )
            ),
            selectorTemplate(
              OutputRangeDeclaration.CompilerPositionWithPolicy(
                PositionProvenancePolicy.PositionedIncludingSynthetic
              ),
              "imported",
              "renamed",
              "bound"
            )
          )
        )
      ),
      Scala3PsiProduction(
        id = "import-selector-braced",
        grammarRoleId = GrammarRoleId.ImportSelector,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "ImportSelector",
          Vector(
            CompilerFieldPattern("imported", CatalogValuePattern.Node),
            CompilerFieldPattern("renamed", CatalogValuePattern.Node),
            CompilerFieldPattern("bound", CatalogValuePattern.Node)
          ),
          Vector("Import", "Export").map: owner =>
            CompilerProductionContextPattern(
              ContextPattern.Parent(
                InventoryKind.Node,
                owner,
                Vector(CatalogPathSegment.NamedField("selectors"), CatalogPathSegment.RepeatedElement)
              ),
              SourceClassification.SourceReachable
            )
        ),
        dispositions = Vector(
          FieldDisposition("imported", FieldDispositionKind.Child),
          FieldDisposition("renamed", FieldDispositionKind.Child),
          FieldDisposition("bound", FieldDispositionKind.Child)
        ),
        children = Vector(
          ChildDeclaration(
            "imported",
            "imported",
            ChildCardinality.ExactlyOne,
            "import-selector-name",
            Set("import-selector-wildcard-name", "import-selector-empty-name")
          ),
          ChildDeclaration(
            "renamed",
            "renamed",
            ChildCardinality.ExactlyOne,
            "import-selector-name",
            Set("import-selector-hidden-name", "import-selector-absent")
          ),
          ChildDeclaration(
            "bound",
            "bound",
            ChildCardinality.ExactlyOne,
            "import-selector-bound-type",
            (GivenTypeProductionIds - "import-selector-bound-type") + "import-selector-absent"
          )
        ),
        terminals = Vector(
          TerminalDeclaration(
            "selector-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          ),
          TerminalDeclaration(
            "scala3-alias-separator",
            TerminalIntervalSelector.ChildGap("imported", "renamed"),
            TerminalLeafTarget.Token(NativePsiElementBindings.ImportAliasAsTokenSurface, Some("as")),
            OccurrenceCardinality.Optional,
            PsiOutputRoleId.SourceTerminal
          ),
          TerminalDeclaration(
            "scala2-alias-separator",
            TerminalIntervalSelector.ChildGap("imported", "renamed"),
            TerminalLeafTarget.Token(NativePsiElementBindings.ImportAliasArrowTokenSurface, Some("=>")),
            OccurrenceCardinality.Optional,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = ImportSelectorSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = ImportSelectorAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = None,
        outputRealizations = Vector(
          OutputRealization(
            "braced-named",
            Vector(
              ChildOutcomeCondition(
                "imported",
                ChildOccurrenceSelector.First,
                ChildOutcomeExpectation.Production("import-selector-name")
              ),
              ChildOutcomeCondition(
                "renamed",
                ChildOccurrenceSelector.First,
                ChildOutcomeExpectation.Production("import-selector-absent")
              )
            ),
            namedSelectorTemplate
          ),
          OutputRealization(
            "braced-alias",
            Vector(
              ChildOutcomeCondition(
                "imported",
                ChildOccurrenceSelector.First,
                ChildOutcomeExpectation.Production("import-selector-name")
              ),
              ChildOutcomeCondition(
                "renamed",
                ChildOccurrenceSelector.First,
                ChildOutcomeExpectation.Production("import-selector-name")
              )
            ),
            namedSelectorTemplate
          ),
          OutputRealization(
            "braced-hidden",
            Vector(
              ChildOutcomeCondition(
                "imported",
                ChildOccurrenceSelector.First,
                ChildOutcomeExpectation.Production("import-selector-name")
              ),
              ChildOutcomeCondition(
                "renamed",
                ChildOccurrenceSelector.First,
                ChildOutcomeExpectation.Production("import-selector-hidden-name")
              )
            ),
            namedSelectorTemplate
          ),
          OutputRealization(
            "braced-wildcard",
            Vector(
              ChildOutcomeCondition(
                "imported",
                ChildOccurrenceSelector.First,
                ChildOutcomeExpectation.Production("import-selector-wildcard-name")
              )
            ),
            selectorTemplate(OutputRangeDeclaration.CompilerPosition, "imported", "renamed", "bound")
          ),
          OutputRealization(
            "braced-given",
            Vector(
              ChildOutcomeCondition(
                "imported",
                ChildOccurrenceSelector.First,
                ChildOutcomeExpectation.Production("import-selector-empty-name")
              )
            ),
            selectorTemplate(OutputRangeDeclaration.CompilerPosition, "imported", "renamed", "bound")
          )
        )
      ),
      Scala3PsiProduction(
        id = "import-selector-name",
        grammarRoleId = GrammarRoleId.ImportSelectorName,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Ident",
          Vector(
            CompilerFieldPattern(
              "name",
              CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)
            )
          ),
          Vector(
            CompilerProductionContextPattern(
              ContextPattern.Parent(
                InventoryKind.Node,
                "ImportSelector",
                Vector(CatalogPathSegment.NamedField("imported"))
              ),
              SourceClassification.SourceReachable
            ),
            CompilerProductionContextPattern(
              ContextPattern.Parent(
                InventoryKind.Node,
                "ImportSelector",
                Vector(CatalogPathSegment.NamedField("renamed"))
              ),
              SourceClassification.SourceReachable
            )
          )
        ),
        dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
        children = Vector.empty,
        terminals = Vector(
          TerminalDeclaration(
            "name-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = StableReferenceSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = StableReferenceAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = None,
        outputTemplate = Some(transparentTemplate())
      ),
      Scala3PsiProduction(
        id = "import-selector-hidden-name",
        grammarRoleId = GrammarRoleId.ImportSelectorName,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Ident",
          Vector(
            CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Wildcard))
          ),
          Vector(
            CompilerProductionContextPattern(
              ContextPattern.Parent(
                InventoryKind.Node,
                "ImportSelector",
                Vector(CatalogPathSegment.NamedField("renamed"))
              ),
              SourceClassification.SourceReachable
            )
          )
        ),
        dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
        children = Vector.empty,
        terminals = Vector(
          TerminalDeclaration(
            "hidden-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Token(NativePsiElementBindings.ImportLegacyWildcardTokenSurface, Some("_")),
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = ImportSelectorSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = ImportSelectorAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = None,
        outputTemplate = Some(transparentTemplate())
      ),
      Scala3PsiProduction(
        id = "import-selector-wildcard-name",
        grammarRoleId = GrammarRoleId.ImportSelectorName,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Ident",
          Vector(
            CompilerFieldPattern(
              "name",
              CatalogValuePattern.ClassifiedName(NeutralNameClass.Wildcard)
            )
          ),
          Vector(
            CompilerProductionContextPattern(
              ContextPattern.Parent(
                InventoryKind.Node,
                "ImportSelector",
                Vector(CatalogPathSegment.NamedField("imported"))
              ),
              SourceClassification.SourceReachable
            )
          )
        ),
        dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
        children = Vector.empty,
        terminals = Vector(
          TerminalDeclaration(
            "wildcard-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Token(NativePsiElementBindings.ImportWildcardTokenSurface, Some("*")),
            OccurrenceCardinality.Optional,
            PsiOutputRoleId.SourceTerminal
          ),
          TerminalDeclaration(
            "legacy-wildcard-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Token(NativePsiElementBindings.ImportLegacyWildcardTokenSurface, Some("_")),
            OccurrenceCardinality.Optional,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = ImportSelectorSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = ImportSelectorAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = None,
        outputTemplate = Some(transparentTemplate())
      ),
      Scala3PsiProduction(
        id = "import-selector-empty-name",
        grammarRoleId = GrammarRoleId.ImportSelectorName,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Ident",
          Vector(
            CompilerFieldPattern(
              "name",
              CatalogValuePattern.ClassifiedName(NeutralNameClass.Empty)
            )
          ),
          Vector(
            CompilerProductionContextPattern(
              ContextPattern.Parent(
                InventoryKind.Node,
                "ImportSelector",
                Vector(CatalogPathSegment.NamedField("imported"))
              ),
              SourceClassification.SourceReachable
            )
          )
        ),
        dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
        children = Vector.empty,
        terminals = Vector(
          TerminalDeclaration(
            "given-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = ImportSelectorSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = ImportSelectorAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = None,
        outputTemplate = Some(transparentTemplate())
      ),
      Scala3PsiProduction(
        id = "import-selector-bound-type",
        grammarRoleId = GrammarRoleId.SimpleType,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Ident",
          Vector(
            CompilerFieldPattern(
              "name",
              CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)
            )
          ),
          typeAtomOccurrences
        ),
        dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
        children = Vector.empty,
        terminals = Vector(
          TerminalDeclaration(
            "type-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = SimpleTypeSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = SimpleTypeAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = None,
        outputTemplate = Some(
          LocalOutputCompositeTemplate(
            Vector(
              outputComposite(
                "type",
                None,
                OutputRangeDeclaration.CompilerPosition,
                PsiOutputRoleId.SimpleType,
                SimpleTypeSurface,
                SimpleTypeAccessors
              ),
              outputComposite(
                "reference",
                Some("type"),
                OutputRangeDeclaration.CompilerPosition,
                PsiOutputRoleId.StableReference,
                StableReferenceSurface,
                StableReferenceAccessors
              )
            ),
            Map.empty
          )
        )
      ),
      importSelectorAppliedTypeProduction,
      appliedTypeProduction("ordinary-applied-type", appliedTypeRootOccurrences, Set.empty),
      appliedTypeProduction(
        "type-argument-applied",
        appliedTypeChildOccurrences("args"),
        Set(GrammarRoleId.PositionalTypeArgument)
      ),
      positionalTypeArgumentProduction,
      Scala3PsiProduction(
        id = "import-selector-given-bound-qualified-type",
        grammarRoleId = GrammarRoleId.SimpleType,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Select",
          Vector(
            CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
            CompilerFieldPattern("name", CatalogValuePattern.Name)
          ),
          typeAtomOccurrences.map(
            _.copy(scannerEvidence =
              ScannerEvidencePattern(
                required = Set(ParserScannerTokenKind.Dot),
                forbidden = Set(ParserScannerTokenKind.Hash)
              )
            )
          )
        ),
        dispositions = Vector(
          FieldDisposition("qualifier", FieldDispositionKind.Child),
          FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)
        ),
        children = Vector(
          ChildDeclaration(
            "qualifier",
            "qualifier",
            ChildCardinality.ExactlyOne,
            "import-selector-given-bound-qualifier-ident",
            GivenTypeQualifierProductionIds - "import-selector-given-bound-qualifier-ident"
          )
        ),
        terminals = Vector(
          TerminalDeclaration(
            "qualified-type-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          ),
          TerminalDeclaration(
            "path-dot",
            TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.Dot),
            TerminalLeafTarget.Token(NativePsiElementBindings.TypePathDotTokenSurface, Some(".")),
            OccurrenceCardinality.Repeated(1, None),
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = SimpleTypeSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = SimpleTypeAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = None,
        outputTemplate = Some(qualifiedTypeTemplate)
      ),
      Scala3PsiProduction(
        id = "import-selector-given-bound-qualifier-ident",
        grammarRoleId = GrammarRoleId.StableReference,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Ident",
          Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
          typeAtomQualifierOccurrences("Select")
        ),
        dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
        children = Vector.empty,
        terminals = Vector(
          TerminalDeclaration(
            "reference-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = StableReferenceSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = StableReferenceAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = None,
        outputTemplate = Some(stableReferenceTemplate())
      ),
      Scala3PsiProduction(
        id = "import-selector-given-bound-qualifier-select",
        grammarRoleId = GrammarRoleId.StableReference,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Select",
          Vector(
            CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
            CompilerFieldPattern("name", CatalogValuePattern.Name)
          ),
          typeAtomQualifierOccurrences("Select")
        ),
        dispositions = Vector(
          FieldDisposition("qualifier", FieldDispositionKind.Child),
          FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)
        ),
        children = Vector(
          ChildDeclaration(
            "qualifier",
            "qualifier",
            ChildCardinality.ExactlyOne,
            "import-selector-given-bound-qualifier-ident",
            GivenTypeQualifierProductionIds - "import-selector-given-bound-qualifier-ident"
          )
        ),
        terminals = Vector(
          TerminalDeclaration(
            "reference-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = StableReferenceSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = StableReferenceAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = None,
        outputTemplate = Some(stableReferenceTemplate("qualifier"))
      ),
      Scala3PsiProduction(
        id = "import-selector-given-bound-wildcard-type",
        grammarRoleId = GrammarRoleId.WildcardType,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "TypeBoundsTree",
          Vector(
            CompilerFieldPattern("lo", CatalogValuePattern.Node),
            CompilerFieldPattern("hi", CatalogValuePattern.Node),
            CompilerFieldPattern("alias", CatalogValuePattern.Node)
          ),
          Vector(
            CompilerProductionContextPattern(
              ContextPattern.ParentUnderAnchor(
                InventoryKind.Node,
                "AppliedTypeTree",
                Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
                GivenSelectorBoundAnchor
              ),
              SourceClassification.SourceReachable
            )
          )
        ),
        dispositions = Vector(
          FieldDisposition("lo", FieldDispositionKind.Child),
          FieldDisposition("hi", FieldDispositionKind.Child),
          FieldDisposition("alias", FieldDispositionKind.Child)
        ),
        children = Vector(
          ChildDeclaration(
            "lower-bound",
            "lo",
            ChildCardinality.ExactlyOne,
            "import-selector-given-bound-absent",
            GivenTypeProductionIds
          ),
          ChildDeclaration(
            "upper-bound",
            "hi",
            ChildCardinality.ExactlyOne,
            "import-selector-given-bound-absent",
            GivenTypeProductionIds
          ),
          ChildDeclaration(
            "alias",
            "alias",
            ChildCardinality.ExactlyOne,
            "import-selector-given-bound-absent"
          )
        ),
        terminals = Vector(
          TerminalDeclaration(
            "wildcard-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          ),
          TerminalDeclaration(
            "question-mark",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Token(NativePsiElementBindings.WildcardQuestionTokenSurface, Some("?")),
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          ),
          TerminalDeclaration(
            "lower-bound-token",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Token(NativePsiElementBindings.LowerTypeBoundTokenSurface, Some(">:")),
            OccurrenceCardinality.Optional,
            PsiOutputRoleId.SourceTerminal
          ),
          TerminalDeclaration(
            "upper-bound-token",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Token(NativePsiElementBindings.UpperTypeBoundTokenSurface, Some("<:")),
            OccurrenceCardinality.Optional,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = WildcardTypeSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = WildcardTypeAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = None,
        outputTemplate = Some(
          typeElementTemplate(
            PsiOutputRoleId.WildcardType,
            WildcardTypeSurface,
            WildcardTypeAccessors,
            "lower-bound",
            "upper-bound",
            "alias"
          )
        )
      ),
      Scala3PsiProduction(
        id = "import-selector-given-bound-absent",
        grammarRoleId = GrammarRoleId.AbsentProduct,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Thicket",
          Vector(CompilerFieldPattern("trees", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node))),
          Vector("lo", "hi", "alias").map: field =>
            CompilerProductionContextPattern(
              ContextPattern.ParentUnderAnchor(
                InventoryKind.Node,
                "TypeBoundsTree",
                Vector(CatalogPathSegment.NamedField(field)),
                GivenSelectorBoundAnchor
              ),
              SourceClassification.Absent
            )
        ),
        dispositions = Vector(FieldDisposition("trees", FieldDispositionKind.Synthetic)),
        children = Vector.empty,
        terminals = Vector.empty,
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = WildcardTypeSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = WildcardTypeAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = None,
        outputTemplate = Some(transparentTemplate())
      ),
      Scala3PsiProduction(
        id = "import-selector-given-bound-infix-type",
        grammarRoleId = GrammarRoleId.InfixType,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "InfixOp",
          Vector(
            CompilerFieldPattern("left", CatalogValuePattern.Node),
            CompilerFieldPattern("op", CatalogValuePattern.Node),
            CompilerFieldPattern("right", CatalogValuePattern.Node)
          ),
          givenTypeOccurrences
        ),
        dispositions = Vector(
          FieldDisposition("left", FieldDispositionKind.Child),
          FieldDisposition("op", FieldDispositionKind.Child),
          FieldDisposition("right", FieldDispositionKind.Child)
        ),
        children = Vector(
          ChildDeclaration(
            "left",
            "left",
            ChildCardinality.ExactlyOne,
            "import-selector-bound-type",
            GivenTypeProductionIds - "import-selector-bound-type"
          ),
          ChildDeclaration(
            "operator",
            "op",
            ChildCardinality.ExactlyOne,
            "import-selector-given-bound-infix-operator"
          ),
          ChildDeclaration(
            "right",
            "right",
            ChildCardinality.ExactlyOne,
            "import-selector-bound-type",
            GivenTypeProductionIds - "import-selector-bound-type"
          )
        ),
        terminals = Vector(
          TerminalDeclaration(
            "infix-type-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = InfixTypeSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = InfixTypeAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = None,
        outputTemplate = Some(
          typeElementTemplate(
            PsiOutputRoleId.InfixType,
            InfixTypeSurface,
            InfixTypeAccessors,
            "left",
            "operator",
            "right"
          )
        )
      ),
      Scala3PsiProduction(
        id = "import-selector-given-bound-infix-operator",
        grammarRoleId = GrammarRoleId.StableReference,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Ident",
          Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
          Vector(
            CompilerProductionContextPattern(
              ContextPattern.ParentUnderAnchor(
                InventoryKind.Node,
                "InfixOp",
                Vector(CatalogPathSegment.NamedField("op")),
                GivenSelectorBoundAnchor
              ),
              SourceClassification.SourceReachable
            )
          )
        ),
        dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
        children = Vector.empty,
        terminals = Vector(
          TerminalDeclaration(
            "operator-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = StableReferenceSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = StableReferenceAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = None,
        outputTemplate = Some(stableReferenceTemplate())
      ),
      Scala3PsiProduction(
        id = "import-selector-absent",
        grammarRoleId = GrammarRoleId.AbsentProduct,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Thicket",
          Vector(
            CompilerFieldPattern(
              "trees",
              CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)
            )
          ),
          Vector(
            CompilerProductionContextPattern(
              ContextPattern.Parent(
                InventoryKind.Node,
                "ImportSelector",
                Vector(CatalogPathSegment.NamedField("renamed"))
              ),
              SourceClassification.Absent
            ),
            CompilerProductionContextPattern(
              ContextPattern.Parent(
                InventoryKind.Node,
                "ImportSelector",
                Vector(CatalogPathSegment.NamedField("bound"))
              ),
              SourceClassification.Absent
            )
          )
        ),
        dispositions = Vector(FieldDisposition("trees", FieldDispositionKind.Synthetic)),
        children = Vector.empty,
        terminals = Vector.empty,
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = ImportSelectorSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = ImportSelectorAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = None,
        outputTemplate = Some(transparentTemplate())
      ),
      Scala3PsiProduction(
        id = "package-stable-identifier-reference",
        grammarRoleId = GrammarRoleId.StableReference,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Ident",
          Vector(
            CompilerFieldPattern(
              "name",
              CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)
            )
          ),
          Vector(
            CompilerProductionContextPattern(
              ContextPattern.Parent(
                InventoryKind.Node,
                "PackageDef",
                Vector(CatalogPathSegment.NamedField("pid"))
              ),
              SourceClassification.SourceReachable
            )
          )
        ),
        dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
        children = Vector.empty,
        terminals = Vector(
          TerminalDeclaration(
            "identifier-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = StableReferenceSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = StableReferenceAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = Some(PsiOutputRoleId.StableReference)
      ),
      Scala3PsiProduction(
        id = "package-stable-reference",
        grammarRoleId = GrammarRoleId.StableReference,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Select",
          Vector(
            CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
            CompilerFieldPattern("name", CatalogValuePattern.Name)
          ),
          Vector(
            CompilerProductionContextPattern(
              ContextPattern.AnchorOrParentWithRepeatedAncestor(
                InventoryAncestor(
                  InventoryKind.Node,
                  "PackageDef",
                  Vector(CatalogPathSegment.NamedField("pid"))
                ),
                InventoryKind.Node,
                "Select",
                Vector(CatalogPathSegment.NamedField("qualifier")),
                InventoryAncestor(
                  InventoryKind.Node,
                  "Select",
                  Vector(CatalogPathSegment.NamedField("qualifier"))
                )
              ),
              SourceClassification.SourceReachable
            )
          )
        ),
        dispositions = Vector(
          FieldDisposition("qualifier", FieldDispositionKind.Child),
          FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)
        ),
        children = Vector(
          ChildDeclaration(
            "qualifier",
            "qualifier",
            ChildCardinality.ExactlyOne,
            "package-stable-identifier",
            Set("package-stable-reference")
          )
        ),
        terminals = Vector(
          TerminalDeclaration(
            "reference-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScStableCodeReferenceImpl",
        targetRequirement = TargetRequirement.Native,
        accessors = StableReferenceAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = Some(PsiOutputRoleId.StableReference)
      ),
      Scala3PsiProduction(
        id = "package-stable-identifier",
        grammarRoleId = GrammarRoleId.StableReference,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Ident",
          Vector(CompilerFieldPattern("name", CatalogValuePattern.Name)),
          Vector(
            CompilerProductionContextPattern(
              ContextPattern.ParentWithRepeatedAncestor(
                InventoryKind.Node,
                "Select",
                Vector(CatalogPathSegment.NamedField("qualifier")),
                InventoryAncestor(
                  InventoryKind.Node,
                  "Select",
                  Vector(CatalogPathSegment.NamedField("qualifier"))
                ),
                InventoryAncestor(
                  InventoryKind.Node,
                  "PackageDef",
                  Vector(CatalogPathSegment.NamedField("pid"))
                )
              ),
              SourceClassification.SourceReachable
            )
          )
        ),
        dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
        children = Vector.empty,
        terminals = Vector(
          TerminalDeclaration(
            "identifier-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScStableCodeReferenceImpl",
        targetRequirement = TargetRequirement.Native,
        accessors = StableReferenceAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = Some(PsiOutputRoleId.StableReference)
      ),
      Scala3PsiProduction(
        id = "integer-literal-number",
        grammarRoleId = GrammarRoleId.IntegerLiteral,
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Number",
          Vector(
            CompilerFieldPattern("digits", CatalogValuePattern.Scalar("Text")),
            CompilerFieldPattern(
              "kind",
              CatalogValuePattern.Product(
                "Whole",
                Vector(CompilerFieldPattern("radix", CatalogValuePattern.Scalar("Integer")))
              )
            )
          ),
          Vector(
            CompilerProductionContextPattern(
              ContextPattern.Parent(
                InventoryKind.Node,
                "InfixOp",
                Vector(CatalogPathSegment.NamedField("right"))
              ),
              SourceClassification.SourceReachable
            )
          )
        ),
        dispositions = Vector(
          FieldDisposition("digits", FieldDispositionKind.TerminalOrLayout),
          FieldDisposition("kind", FieldDispositionKind.TerminalOrLayout)
        ),
        children = Vector.empty,
        terminals = Vector(
          TerminalDeclaration(
            "integer-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = "org/jetbrains/plugins/scala/lang/psi/impl/base/literals/ScIntegerLiteralImpl",
        targetRequirement = TargetRequirement.NativeCandidate,
        accessors = Vector(
          AccessorObligation(
            "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#getValue()Ljava/lang/Object;",
            required = true,
            surfaceKind = SurfaceFactKind.Method
          ),
          AccessorObligation(
            "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#contentText()Ljava/lang/String;",
            required = true,
            surfaceKind = SurfaceFactKind.Method
          ),
          AccessorObligation(
            "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#contentRangeInParent()Lcom/intellij/openapi/util/TextRange;",
            required = true,
            surfaceKind = SurfaceFactKind.Method
          ),
          AccessorObligation(
            "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#isSimpleLiteral()Z",
            required = true,
            surfaceKind = SurfaceFactKind.Method
          ),
          AccessorObligation(
            "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#literalType()Lorg/jetbrains/plugins/scala/lang/psi/types/ScType;",
            required = true,
            surfaceKind = SurfaceFactKind.Method
          )
        ),
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputRoleId = Some(PsiOutputRoleId.IntegerLiteral)
      )
    ) ++ modifierAnnotationProductions ++ templateProductions ++ typeAtomProductions,
    StableRoleInventory.Reviewed
  )

  def withIntegerLiteralTarget(
      native: Either[IntegerLiteralProbeFailure, Vector[NativeIntegerLiteralObservation]],
      compatible: () => Either[IntegerLiteralProbeFailure, Vector[NativeIntegerLiteralObservation]]
  ): Either[CatalogCapabilityFailure, Scala3PsiProductionCatalog] =
    val id = "integer-literal-number"
    Reviewed.productions.find(_.id == id) match
      case None                                                                                  => Left(CatalogCapabilityFailure.MissingProduction(id))
      case Some(production) if production.targetRequirement != TargetRequirement.NativeCandidate =>
        Left(CatalogCapabilityFailure.InvalidTargetRequirement(id, production.targetRequirement))
      case Some(production)                                                                      =>
        def expectedBehavior(observation: NativeIntegerLiteralObservation): Boolean =
          observation.publicSurfaceId ==
            "org/jetbrains/plugins/scala/lang/psi/api/base/literals/ScIntegerLiteral" &&
            observation.text == observation.contentText &&
            observation.valueClass == "java.lang.Integer" &&
            observation.contentStart == 0 && observation.contentEnd == observation.text.length
        val expectedValues                                                          = Vector("0" -> "0", "42" -> "42", "0x2a" -> "42", "1_000" -> "1000")
        def validBehavior(values: Vector[NativeIntegerLiteralObservation]): Boolean =
          values.map(value => value.text -> value.valueText) == expectedValues && values.forall(expectedBehavior)
        def commonBehavior(observation: NativeIntegerLiteralObservation): Boolean   =
          observation.isSimpleLiteral && observation.literalTypeIdentity &&
            observation.literalType == observation.valueText && observation.widenedType == "Int" &&
            observation.visitorDispatched && observation.visitorElementIdentity && observation.navigationIdentity &&
            observation.validPsi && observation.validContainingFile && observation.validParent &&
            observation.nodePsiIdentity && observation.projectIdentity && observation.exactTextRange &&
            observation.directChildCount == 1 && observation.directChildText == observation.text &&
            observation.integerTokenIdentity && !observation.stubBasedPsi && !observation.stubElementType
        val nativeValid                                                             = native.exists(values =>
          validBehavior(values) && values.forall(observation =>
            observation.implementationSurfaceId == production.targetSurfaceId &&
              observation.elementType == "IntegerLiteral" && observation.isScalaIntegerLiteralElementType &&
              !observation.compatibleElementTypeIdentity && commonBehavior(observation)
          )
        )
        if nativeValid then promote(production, TargetRequirement.Native, production.targetSurfaceId)
        else
          val compatibleResult = compatible()
          val compatibleValid  = compatibleResult.exists(values =>
            validBehavior(values) && values.forall(observation =>
              observation.implementationSurfaceId ==
                "org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyIntegerLiteral" &&
                observation.elementType == "METALLURGY_INTEGER_LITERAL" && !observation.isScalaIntegerLiteralElementType &&
                observation.compatibleElementTypeIdentity && commonBehavior(observation)
            )
          )
          if compatibleValid then
            promote(
              production,
              TargetRequirement.Compatible,
              "org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyIntegerLiteral"
            )
          else Left(CatalogCapabilityFailure.IntegerLiteralTargetsUnavailable(native, compatibleResult))

  private def promote(
      production: Scala3PsiProduction,
      requirement: TargetRequirement,
      targetSurfaceId: String
  ): Either[CatalogCapabilityFailure, Scala3PsiProductionCatalog] =
    Right(
      Reviewed.copy(productions = Reviewed.productions.map:
        case value if value.id == production.id =>
          value.copy(targetSurfaceId = targetSurfaceId, targetRequirement = requirement)
        case value                              => value
      )
    )

private[metallurgy] object Scala3PsiProductionCoverageReport:
  def markdown(
      catalog: Scala3PsiProductionCatalog,
      compiler: AggregatedCompilerProductionInventory,
      surfaces: ScalaPsiSurfaceInventory
  ): String =
    val effectiveSurfaces = surfaces.withCatalogCapabilities(catalog)
    val lines             = Vector.newBuilder[String]
    val validation        = Scala3PsiProductionCatalogValidator.validate(catalog, compiler, effectiveSurfaces)
    lines += "# Scala 3 PSI production coverage"
    lines += ""
    lines += s"- Compiler: `${compiler.identity.coordinate.organization}:${compiler.identity.coordinate.artifact}:${compiler.identity.coordinate.version}`"
    lines += s"- Compiler inventory: `${compiler.fingerprint}`"
    lines += s"- Scala PSI inventory: `${effectiveSurfaces.fingerprint}`"
    lines += s"- Reviewed productions: ${catalog.productions.size}"
    lines += s"- Validation: **${if validation.isEmpty then "complete" else "incomplete"}**"
    validation
      .groupMapReduce(_.productPrefix)(_ => 1)(_ + _)
      .toVector
      .sortBy(_._1)
      .foreach((name, count) => lines += s"- Outstanding `$name`: $count")
    lines += ""
    lines += "## Stable role inventory"
    lines += ""
    lines += "### Grammar roles"
    lines += ""
    catalog.stableRoles.grammarRoles.toVector
      .sortBy(_.value)
      .foreach: role =>
        val alternatives = catalog.productions.filter(_.grammarRoleIds(role)).map(_.id).distinct.sorted
        val status       =
          if alternatives.isEmpty then "unreferenced" else s"catalog-alternatives=${alternatives.mkString(",")}"
        lines += s"- `${role.value}` — **$status**"
    lines += ""
    lines += "### Output roles"
    lines += ""
    catalog.stableRoles.outputRoles.toVector
      .sortBy(_.value)
      .foreach: role =>
        val contracts = catalog.productions.flatMap: production =>
          val terminals = production.terminals.collect:
            case terminal if terminal.outputRoleId == role =>
              val target = terminal.target match
                case TerminalLeafTarget.Token(surfaceId, _) => s"->$surfaceId"
                case _                                      => ""
              s"${production.id}:terminal:${terminal.id}$target"
          val outputs   = production.effectiveOutputRealizations.flatMap: realization =>
            realization.template.composites.collect:
              case output if output.outputRoleId == role =>
                s"${production.id}:${realization.id}:${output.id}->${output.targetSurfaceId}"
          terminals ++ outputs
        val status    =
          if contracts.isEmpty then "unreferenced" else s"contracts=${contracts.distinct.sorted.mkString(",")}"
        lines += s"- `${role.value}` — **$status**"
    lines += ""
    lines += "## Compiler productions"
    lines += ""
    compiler.productions
      .sortBy(row => (row.kind.toString, row.prefix, row.fields.map(_.toString).mkString("\u0000")))
      .foreach: row =>
        val fields = row.fields.map(field => s"${field.name}:${render(field.value)}").mkString(", ")
        lines += s"### `${row.kind}.${row.prefix}`"
        lines += ""
        lines += s"- Fields: `$fields`"
        row.occurrences
          .sortBy(render)
          .foreach: occurrence =>
            val selected = CatalogShapeMatcher.selectAggregated(catalog, row, occurrence)
            val status   = selected match
              case Vector(production) =>
                val outputs      = production.effectiveOutputRealizations.flatMap(_.template.composites)
                val terminals    = production.terminals
                val requirements = outputs
                  .map(_.targetRequirement.toString)
                  .distinct
                  .sorted
                val outputRoles  = (outputs.map(_.outputRoleId) ++ terminals.map(_.outputRoleId))
                  .map(_.value)
                  .distinct
                  .sorted
                val targets      = (outputs.map(_.targetSurfaceId) ++ terminals.collect:
                  case TerminalDeclaration(_, _, TerminalLeafTarget.Token(surfaceId, _), _, _, _) => surfaceId
                ).distinct.sorted
                val providers    = if requirements.isEmpty then "transparent" else requirements.mkString(",")
                val boundary     = missingBoundary(production, validation)
                s"mapped; grammar-role=${production.grammarRoleId.value}; catalog-alternative=${production.id}; compiler-shape=${row.kind}.${row.prefix}; compiler-context=${render(occurrence)}; output-roles=${renderList(outputRoles)}; host-targets=${renderList(targets)}; providers=$providers; missing-boundary=$boundary"
              case Vector()           =>
                s"unmapped; compiler-shape=${row.kind}.${row.prefix}; compiler-context=${render(occurrence)}; missing-boundary=bridge-normalization-or-neutral-grammar-role"
              case productions        =>
                s"ambiguous; compiler-shape=${row.kind}.${row.prefix}; compiler-context=${render(occurrence)}; catalog-alternatives=${productions.map(_.id).sorted.mkString(",")}; missing-boundary=neutral-grammar-role-selection"
            lines += s"- `${render(occurrence)}` — **$status**"
        lines += ""
    lines += "## Scala PSI surfaces"
    lines += ""
    val references        = catalog.productions
      .flatMap: production =>
        val terminals = production.terminals.collect:
          case TerminalDeclaration(_, _, TerminalLeafTarget.Token(id, _), _, _, _) => id
        val outputs   = production.effectiveOutputRealizations
          .flatMap(_.template.composites)
          .flatMap: output =>
            val persistence = output.persistence match
              case PersistenceObligations.NotApplicable                                   => Vector.empty
              case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
                Vector(stub, serializer, navigation) ++ indices
            Vector(output.targetSurfaceId) ++ output.accessors.map(_.surfaceId) ++ persistence
        (outputs ++ terminals)
          .map(_ -> production.id)
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.distinct.sorted)
      .toMap
    effectiveSurfaces.rows
      .sortBy(row => (row.kind.toString, row.id))
      .foreach: row =>
        val status = references.get(row.id) match
          case Some(productions)                                                  => s"catalog-referenced:${productions.mkString(",")}"
          case None if row.classification == SurfaceClassification.SyntaxContract =>
            s"unmapped:${row.classification}:missing-boundary=stable-output-role-or-compatibility-binding"
          case None                                                               => s"unmapped:${row.classification}"
        lines += s"- `${row.kind}:${row.id}` — **${row.status}:$status**"
    lines.result().mkString("\n") + "\n"

  private def missingBoundary(
      production: Scala3PsiProduction,
      validation: Vector[CatalogValidationError]
  ): String =
    if validation.exists:
        case CatalogValidationError.UnknownGrammarRole(id, _) if id == production.id                   => true
        case CatalogValidationError.CatalogAlternativeDerivedGrammarRole(id, _) if id == production.id => true
        case CatalogValidationError.CompilerDerivedGrammarRole(id, _, _) if id == production.id        => true
        case _                                                                                         => false
    then "neutral-grammar-role"
    else if validation.exists:
        case CatalogValidationError.MissingDefaultOutputRole(id) if id == production.id       => true
        case CatalogValidationError.UnknownOutputRole(id, _, _) if id == production.id        => true
        case CatalogValidationError.HostDerivedOutputRole(id, _, _, _) if id == production.id => true
        case _                                                                                => false
    then "stable-output-role"
    else if production.effectiveOutputRealizations
        .flatMap(_.template.composites)
        .exists(
          _.targetRequirement == TargetRequirement.NativeCandidate
        ) || validation.exists:
        case CatalogValidationError.InvalidSurface(id, _, _, _) if id == production.id          => true
        case CatalogValidationError.InvalidSurfaceOwner(id, _, _, _) if id == production.id     => true
        case CatalogValidationError.IncompleteSurfaceStatus(id, _, _, _) if id == production.id => true
        case _                                                                                  => false
    then "compatibility-binding"
    else "none"

  private def renderList(values: Vector[String]): String =
    if values.isEmpty then "none" else values.mkString(",")

  private def render(occurrence: CompilerProductionContext): String =
    val context = occurrence.context match
      case None        => "root"
      case Some(value) =>
        val owner     = s"${value.ownerKind}.${value.ownerPrefix}/${renderPath(value.path)}"
        val ancestors = value.ancestors
          .map(ancestor => s"${ancestor.ownerKind}.${ancestor.ownerPrefix}/${renderPath(ancestor.path)}")
          .mkString(">")
        if ancestors.isEmpty then owner else s"$owner@[$ancestors]"
    s"$context:${occurrence.sourceClassification}"

  private def renderPath(path: Vector[CatalogPathSegment]): String = path
    .map:
      case CatalogPathSegment.NamedField(name)        => name
      case CatalogPathSegment.Optional                => "?"
      case CatalogPathSegment.RepeatedElement         => "*"
      case CatalogPathSegment.NestedProduct(producer) => s"product($producer)"
    .mkString("/")

  private def render(pattern: CatalogValuePattern): String = pattern match
    case CatalogValuePattern.Node                                   => "Node"
    case CatalogValuePattern.NodePrefix(prefix)                     => s"Node[$prefix]"
    case CatalogValuePattern.NodeExceptPrefix(prefix)               => s"Node[!$prefix]"
    case CatalogValuePattern.Positioned                             => "Positioned"
    case CatalogValuePattern.Optional(value)                        => s"Optional[${render(value)}]"
    case CatalogValuePattern.EmptyOptional(value)                   => s"EmptyOptional[${render(value)}]"
    case CatalogValuePattern.Repeated(value)                        => s"Repeated[${render(value)}]"
    case CatalogValuePattern.NonEmptyRepeated(value)                => s"NonEmptyRepeated[${render(value)}]"
    case CatalogValuePattern.EmptyRepeated(value)                   => s"EmptyRepeated[${render(value)}]"
    case CatalogValuePattern.LeadingThenRepeated(leading, trailing) =>
      s"LeadingThenRepeated[${render(leading)},${render(trailing)}]"
    case CatalogValuePattern.Product(prefix, fields)                =>
      s"$prefix(${fields.map(field => s"${field.name}:${render(field.value)}").mkString(",")})"
    case CatalogValuePattern.Name                                   => "Name"
    case CatalogValuePattern.GeneratedName                          => "GeneratedName"
    case CatalogValuePattern.ClassifiedName(value)                  => s"Name[$value]"
    case CatalogValuePattern.Scalar(kind)                           => s"Scalar[$kind]"
    case CatalogValuePattern.ExactScalar(kind, value)               => s"ExactScalar[$kind,$value]"
    case CatalogValuePattern.Unsupported(runtimeType)               => s"Unsupported[$runtimeType]"

private[metallurgy] object CatalogShapeMatcher:
  private def scannerEvidenceMatches(
      pattern: ScannerEvidencePattern,
      observed: Vector[ParserScannerTokenKind]
  ): Boolean =
    val kinds = observed.toSet
    pattern.required.subsetOf(kinds) && pattern.forbidden.intersect(kinds).isEmpty

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
      case (CatalogValuePattern.Product(prefix, expected), InventoryValueObservation.Product(actual, fields)) =>
        prefix == actual && matchesFields(expected, fields)
      case (
            CatalogValuePattern.Name,
            _: InventoryValueObservation.Name | _: InventoryValueObservation.GeneratedName
          ) =>
        true
      case (CatalogValuePattern.GeneratedName, InventoryValueObservation.GeneratedName(_, _, _))              => true
      case (CatalogValuePattern.ClassifiedName(expected), InventoryValueObservation.Name(value))              =>
        expected == NeutralNameClass.classify(value)
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

  def contextMatches(pattern: ContextPattern, context: Option[InventoryContext]): Boolean = pattern match
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
    case ContextPattern.ParentWithRepeatedAncestorPrefix(kind, owner, p, repeated, ancestors)                =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p &&
          value.ancestors.dropWhile(_ == repeated).startsWith(ancestors)
      )

  def select(
      catalog: Scala3PsiProductionCatalog,
      kind: InventoryKind,
      prefix: String,
      fields: Vector[InventoryFieldObservation],
      context: Option[InventoryContext],
      sourceClassification: SourceClassification,
      scannerTokenKinds: Vector[ParserScannerTokenKind] = Vector.empty
  ): Vector[Scala3PsiProduction] =
    val matched = catalog.productions.filter(p =>
      p.pattern.kind == kind && p.pattern.prefix == prefix && matchesFields(p.pattern.fields, fields) &&
        p.pattern.occurrences.exists(occurrence =>
          contextMatches(occurrence.context, context) && occurrence.sourceClassification == sourceClassification
            && scannerEvidenceMatches(occurrence.scannerEvidence, scannerTokenKinds)
        )
    )
    val scored  = matched.map: production =>
      val specificity = production.pattern.fields
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
          case _ => false
      production -> specificity
    val highest = scored.map(_._2).maxOption.getOrElse(0)
    scored.collect { case (production, score) if score == highest => production }

  def selectAggregated(
      catalog: Scala3PsiProductionCatalog,
      row: AggregatedCompilerProductionRow,
      occurrence: CompilerProductionContext
  ): Vector[Scala3PsiProduction] =
    catalog.productions.filter(p =>
      p.pattern.kind == row.kind && p.pattern.prefix == row.prefix && coversFields(p.pattern.fields, row.fields) &&
        p.pattern.occurrences.exists(pattern =>
          aggregateContextMatches(pattern.context, occurrence.context) &&
            pattern.sourceClassification == occurrence.sourceClassification &&
            scannerEvidenceMatches(pattern.scannerEvidence, occurrence.scannerTokenKinds)
        )
    )

private[metallurgy] enum CatalogValidationError:
  case DuplicateProductionId(id: String)
  case UnknownGrammarRole(productionId: String, grammarRoleId: GrammarRoleId)
  case CatalogAlternativeDerivedGrammarRole(productionId: String, grammarRoleId: GrammarRoleId)
  case CompilerDerivedGrammarRole(productionId: String, grammarRoleId: GrammarRoleId, compilerPrefix: String)
  case UnreferencedGrammarRole(grammarRoleId: GrammarRoleId)
  case EmptyOccurrencePatterns(productionId: String)
  case DuplicateOccurrencePattern(productionId: String, pattern: CompilerProductionContextPattern)
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
  case UnknownConditionProductionId(productionId: String, realizationId: String, childProductionId: String)
  case UnknownConditionRealizationId(productionId: String, realizationId: String, childRealizationId: String)
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

private[metallurgy] object RuntimeRealizationSelector:
  def validate(catalog: Scala3PsiProductionCatalog, runtime: CompilerRuntimeInventory): Vector[CatalogValidationError] =
    val rows                                                                   = runtime.shapes.map(row => (row.kind, row.id) -> row).toMap
    val nodes                                                                  = runtime.nodes.map(node => node.id -> node).toMap
    val lineages                                                               = InventoryContextLineage.resolver(nodes)
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
            row.scannerTokenKinds
          )
        )
        .map(_.map(_.id))
        .distinct
      matches match
        case Vector(Vector(id))          => selected += instance -> catalog.productions.find(_.id == id).get
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
        case EvidenceCondition.RuntimeSupplementPositive(fieldName, leftPresent)             =>
          right.evidenceConditions.contains(EvidenceCondition.RuntimeSupplementPositive(fieldName, !leftPresent))
        case EvidenceCondition.LeadingBeforeRuntimeTailPresent(repeated, count, leftPresent) =>
          right.evidenceConditions.contains(
            EvidenceCondition.LeadingBeforeRuntimeTailPresent(repeated, count, !leftPresent)
          )
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
              val values = childOutcomes.find(_._1 == condition.roleId).toVector.flatMap(_._2)
              val child  = condition.occurrence match
                case ChildOccurrenceSelector.First        => values.headOption
                case ChildOccurrenceSelector.Last         => values.lastOption
                case ChildOccurrenceSelector.Exact(index) => values.lift(index)
              child.exists(candidate =>
                condition.expected match
                  case ChildOutcomeExpectation.Production(id)  => selected.get(candidate).exists(_.id == id)
                  case ChildOutcomeExpectation.Realization(id) => resolved.get(candidate).exists(_.exists(_.id == id))
              )
          val matches       = matching match
            case Vector() => Vector.empty
            case values   =>
              val mostSpecific = values.map(value => value.conditions.size + value.evidenceConditions.size).max
              values.filter(value => value.conditions.size + value.evidenceConditions.size == mostSpecific)
          matches match
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
    val compilerPrefixes       = catalog.productions.map(_.pattern.prefix).toSet ++ coverage.collect:
      case CatalogValidationError.UncoveredCompilerShape(_, prefix, _, _)    => prefix
      case CatalogValidationError.AmbiguousCompilerShape(_, prefix, _, _, _) => prefix
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
      realizations.foreach(realization =>
        duplicates(realization.conditions.map(condition => condition.roleId -> condition.occurrence)).foreach:
          case (roleId, occurrence) =>
            errors += CatalogValidationError.DuplicateRealizationCondition(
              p.id,
              realization.id,
              roleId,
              occurrence
            )
        realization.conditions.foreach: condition =>
          if !childRoles(condition.roleId) then
            errors += CatalogValidationError.UnknownRealizationConditionRole(p.id, realization.id, condition.roleId)
          condition.occurrence match
            case value @ ChildOccurrenceSelector.Exact(index) if index < 0 =>
              errors += CatalogValidationError.InvalidRealizationConditionOccurrence(p.id, realization.id, value)
            case _                                                         => ()
          p.children
            .find(_.roleId == condition.roleId)
            .foreach: child =>
              condition.expected match
                case ChildOutcomeExpectation.Production(id) if !child.productionIds(id) =>
                  errors += CatalogValidationError.UnknownConditionProductionId(p.id, realization.id, id)
                case ChildOutcomeExpectation.Realization(id)
                    if !catalog.productions
                      .filter(production => child.productionIds(production.id))
                      .exists(_.effectiveOutputRealizations.exists(_.id == id)) =>
                  errors += CatalogValidationError.UnknownConditionRealizationId(p.id, realization.id, id)
                case _                                                                  => ()
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
            case OutputBoundary.ChildStart(role, selector, _)  =>
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
            case OutputBoundary.ChildEnd(role, selector, _)    =>
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
            case OutputBoundary.Advance(_, count) if count < 0 =>
              errors += CatalogValidationError.InvalidOutputBoundary(
                p.id,
                output.id,
                boundary,
                "negative boundary advance"
              )
            case OutputBoundary.Advance(base, _)               => validateBoundary(base)
            case _                                             => ()
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
            case _: TerminalIntervalSelector.BeforeChild                                         => false
            case TerminalIntervalSelector.CompilerEndMarkerKeyword |
                TerminalIntervalSelector.CompilerScannerToken(_, _) | TerminalIntervalSelector.LocalOutput(_) |
                TerminalIntervalSelector.RootOutsideLocalOutput(_) =>
              false
          )
          if !declared then errors += CatalogValidationError.MissingTerminalDeclaration(p.id, name)
      p.terminals.foreach(_.selector match
        case TerminalIntervalSelector.FieldBounds(a, b) =>
          Vector(a, b)
            .filterNot(names.contains)
            .foreach(n => errors += CatalogValidationError.UnknownTerminalField(p.id, n))
        case TerminalIntervalSelector.ChildGap(a, b)    =>
          Vector(a, b)
            .filterNot(childRoles)
            .foreach(role => errors += CatalogValidationError.UnknownTerminalChildRole(p.id, role))
        case TerminalIntervalSelector.BeforeChild(role) =>
          if !childRoles(role) then errors += CatalogValidationError.UnknownTerminalChildRole(p.id, role)
        case _                                          => ()
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
    val catalogProducts = catalog.productions.collect:
      case production if production.pattern.kind == InventoryKind.Product => production.pattern.prefix
    compiler.shapes
      .filter(shape => shape.kind != InventoryKind.Product || catalogProducts.contains(shape.prefix))
      .flatMap: shape =>
        val contexts = if shape.contexts.isEmpty then Vector(None) else shape.contexts.map(Some(_))
        for
          context <- contexts
          selected = CatalogShapeMatcher.select(
                       catalog,
                       shape.kind,
                       shape.prefix,
                       shape.observation,
                       context,
                       shape.sourceClassification,
                       shape.scannerTokenKinds
                     )
          error   <- coverageError(shape.kind, shape.prefix, context, shape.sourceClassification, selected)
        yield error

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
                    pattern.scannerEvidence.forbidden.intersect(occurrence.scannerTokenKinds.toSet).isEmpty
                )
            )
          ) =>
        CatalogValidationError.UnrepresentedCatalogProduction(production.id, production.grammarRoleId)
    uncovered ++ unrepresented

  private def coverageError(
      kind: InventoryKind,
      prefix: String,
      context: Option[InventoryContext],
      sourceClassification: SourceClassification,
      selected: Vector[Scala3PsiProduction]
  ): Vector[CatalogValidationError] =
    if selected.isEmpty then
      Vector(CatalogValidationError.UncoveredCompilerShape(kind, prefix, context, sourceClassification))
    else if selected.size > 1 then
      Vector(
        CatalogValidationError.AmbiguousCompilerShape(
          kind,
          prefix,
          context,
          sourceClassification,
          selected.map(_.id).sorted
        )
      )
    else Vector.empty

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

private[metallurgy] final class PreparedProductionCatalog private (
    val catalog: Scala3PsiProductionCatalog,
    val compiler: AggregatedCompilerProductionInventory,
    val surfaces: ScalaPsiSurfaceInventory
)

private[metallurgy] object PreparedProductionCatalog:
  def prepare(
      catalog: Scala3PsiProductionCatalog,
      compiler: AggregatedCompilerProductionInventory,
      surfaces: ScalaPsiSurfaceInventory
  ): Either[Vector[CatalogValidationError], PreparedProductionCatalog] =
    val errors = Scala3PsiProductionCatalogValidator.validateExecutable(catalog, compiler, surfaces) ++
      compiler.scenarios.flatMap(RuntimeRealizationSelector.validate(catalog, _))
    Either.cond(errors.isEmpty, new PreparedProductionCatalog(catalog, compiler, surfaces), errors)

  def prepareRuntimeSubset(
      catalog: Scala3PsiProductionCatalog,
      runtime: CompilerRuntimeInventory,
      compiler: AggregatedCompilerProductionInventory,
      surfaces: ScalaPsiSurfaceInventory
  ): Either[Vector[CatalogValidationError], PreparedProductionCatalog] =
    val errors = Scala3PsiProductionCatalogValidator.validateExecutable(catalog, runtime, surfaces) ++
      RuntimeRealizationSelector.validate(catalog, runtime)
    Either.cond(errors.isEmpty, new PreparedProductionCatalog(catalog, compiler, surfaces), errors.distinct)

private[metallurgy] object WholeFileProductionPlanner:
  private def runtimeSupplementCount(
      snapshot: ParserSyntaxSnapshot,
      instance: ProductionInstanceId,
      fieldName: String
  ): Int =
    if instance.kind != InventoryKind.Node then 0
    else
      snapshot.runtimeSupplements
        .find(_.ownerNodeId == instance.valueId)
        .flatMap(_.fields.collectFirst:
          case ParserSyntaxField(
                `fieldName`,
                ParserFieldValue.Scalar(ParserScalar.Integer(value)),
                _
              ) =>
            value
        )
        .getOrElse(0)

  def plan(
      snapshot: ParserSyntaxSnapshot,
      evidence: ProvisionalSourceEvidencePlan,
      prepared: PreparedProductionCatalog
  ): Either[WholeFilePlanningFailure, WholeFileProductionPlan] =
    val fingerprint = ParserSyntaxSnapshot.evidenceFingerprint(snapshot)
    if fingerprint != evidence.parserEvidenceFingerprint then
      Left(WholeFilePlanningFailure.EvidenceFingerprintMismatch(fingerprint, evidence.parserEvidenceFingerprint))
    else
      ProvisionalSourceEvidencePlanner.plan(snapshot) match
        case Left(failures)                              => Left(WholeFilePlanningFailure.SourceEvidenceFailures(failures))
        case Right(recomputed) if recomputed != evidence => Left(WholeFilePlanningFailure.SourceEvidencePlanMismatch)
        case Right(_)                                    =>
          CompilerRuntimeInventory.from(snapshot) match
            case Left(failures)  => Left(WholeFilePlanningFailure.InventoryFailures(failures))
            case Right(compiler) => compile(snapshot, evidence, prepared, compiler)

  private def compile(
      snapshot: ParserSyntaxSnapshot,
      evidence: ProvisionalSourceEvidencePlan,
      prepared: PreparedProductionCatalog,
      compiler: CompilerRuntimeInventory
  ): Either[WholeFilePlanningFailure, WholeFileProductionPlan] =
    if compiler.identity != prepared.compiler.identity then
      Left(WholeFilePlanningFailure.CatalogInventoryIdentityMismatch(compiler.identity, prepared.compiler.identity))
    else
      val validation =
        Scala3PsiProductionCatalogValidator.validateExecutable(prepared.catalog, compiler, prepared.surfaces)
      if validation.nonEmpty then Left(WholeFilePlanningFailure.InvalidCatalog(validation))
      else compileClosedSubset(snapshot, evidence, prepared.catalog, compiler)

  private def compileClosedSubset(
      snapshot: ParserSyntaxSnapshot,
      evidence: ProvisionalSourceEvidencePlan,
      catalog: Scala3PsiProductionCatalog,
      compiler: CompilerRuntimeInventory
  ): Either[WholeFilePlanningFailure, WholeFileProductionPlan] =
    boundary[Either[WholeFilePlanningFailure, WholeFileProductionPlan]]:
      val rows                                                                                    = compiler.shapes.map(row => (row.kind, row.id) -> row).toMap
      val nodes                                                                                   = snapshot.nodes.map(node => node.id -> node).toMap
      val positioned                                                                              = snapshot.positioned.map(value => value.id -> value).toMap
      val products                                                                                = compiler.products.map(value => value.id -> value).toMap
      val productsByOccurrence                                                                    = compiler.products
        .flatMap(product =>
          product.occurrences.map(occurrence =>
            ProductionOccurrenceId(occurrence.ownerNodeId, occurrence.fieldPath) -> product
          )
        )
        .toMap
      val lineages                                                                                = InventoryContextLineage.resolver(nodes)
      def fields(instance: ProductionInstanceId): Vector[ParserSyntaxField]                       = instance.kind match
        case InventoryKind.Node       => nodes(instance.valueId).fields
        case InventoryKind.Positioned => positioned(instance.valueId).fields
        case InventoryKind.Product    => products(instance.valueId).fields
      def position(instance: ProductionInstanceId): ParserNodePosition                            = instance.kind match
        case InventoryKind.Node       => nodes(instance.valueId).position
        case InventoryKind.Positioned => positioned(instance.valueId).position
        case InventoryKind.Product    => products(instance.valueId).position
      def fieldPath(instance: ProductionInstanceId, name: String): Vector[ParserFieldPathSegment] =
        if instance.kind == InventoryKind.Product then
          Vector(
            ParserFieldPathSegment.NestedProductBoundary(products(instance.valueId).production),
            ParserFieldPathSegment.NamedField(name)
          )
        else Vector(ParserFieldPathSegment.NamedField(name))
      def references(
          value: ParserFieldValue,
          path: Vector[ParserFieldPathSegment],
          instance: ProductionInstanceId
      ): Vector[(InventoryKind, Long, Vector[ParserFieldPathSegment])] = value match
        case ParserFieldValue.Node(id)                => Vector((InventoryKind.Node, id, path))
        case ParserFieldValue.Positioned(id)          => Vector((InventoryKind.Positioned, id, path))
        case ParserFieldValue.Optional(value)         =>
          value.toVector.flatMap(references(_, path :+ ParserFieldPathSegment.OptionalNesting, instance))
        case ParserFieldValue.Repeated(values)        =>
          values.zipWithIndex.flatMap((candidate, index) =>
            references(candidate, path :+ ParserFieldPathSegment.RepeatedIndex(index), instance)
          )
        case ParserFieldValue.Product(prefix, nested) =>
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
            nested.flatMap(field =>
              references(
                field.value,
                path :+ ParserFieldPathSegment.NestedProductBoundary(prefix) :+
                  ParserFieldPathSegment.NamedField(field.name),
                instance
              )
            )
        case _                                        => Vector.empty
      def childInstance(
          instance: ProductionInstanceId,
          kind: InventoryKind,
          id: Long,
          path: Vector[ParserFieldPathSegment]
      ): ProductionInstanceId =
        ProductionInstanceLineage.child(instance, kind, id, path)
      def children(instance: ProductionInstanceId): Vector[ProductionInstanceId]                  =
        if instance.kind == InventoryKind.Positioned then Vector.empty
        else
          fields(instance).flatMap(field =>
            references(field.value, fieldPath(instance, field.name), instance).map: (kind, id, path) =>
              childInstance(instance, kind, id, path)
          )
      def contexts(instance: ProductionInstanceId): Vector[Option[InventoryContext]]              = instance.occurrence match
        case None             => Vector(None)
        case Some(occurrence) =>
          nodes
            .get(occurrence.ownerNodeId)
            .toVector
            .flatMap(owner => lineages.contexts(owner, occurrence.fieldPath))
            .map(Some(_))
      val root                                                                                    = ProductionInstanceId(InventoryKind.Node, snapshot.rootNodeId, None)
      val instances                                                                               = Vector.newBuilder[ProductionInstanceId]
      val pending                                                                                 = collection.mutable.Stack(root)
      val discovered                                                                              = collection.mutable.Set.empty[ProductionInstanceId]
      while pending.nonEmpty do
        val instance = pending.pop()
        if discovered.add(instance) then
          instances += instance
          children(instance).reverseIterator.foreach(pending.push)
      val ordered                                                                                 = instances.result()
      val selected                                                                                = collection.mutable.LinkedHashMap.empty[ProductionInstanceId, Scala3PsiProduction]
      ordered.foreach: instance =>
        val row        = rows.getOrElse(
          instance.kind -> instance.valueId,
          break(Left(WholeFilePlanningFailure.MissingRuntimeShape(instance.kind, instance.valueId)))
        )
        val selections = contexts(instance).map(context =>
          context -> CatalogShapeMatcher.select(
            catalog,
            row.kind,
            row.prefix,
            row.observation,
            context,
            row.sourceClassification,
            row.scannerTokenKinds
          )
        )
        val distinct   = selections.map(_._2.map(_.id)).distinct
        distinct match
          case Vector(Vector(id))          => selected += instance -> selections.flatMap(_._2).find(_.id == id).get
          case Vector(Vector()) | Vector() =>
            break(
              Left(
                WholeFilePlanningFailure.UnknownProduction(
                  row.kind,
                  row.prefix,
                  row.observation.map(_.name),
                  selections.headOption.flatMap(_._1).map(_.ownerPrefix),
                  instance.occurrence.map(_.fieldPath).getOrElse(Vector.empty)
                )
              )
            )
          case Vector(many)                =>
            break(
              Left(
                WholeFilePlanningFailure.AmbiguousProduction(
                  row.kind,
                  row.prefix,
                  many.sorted,
                  selections.headOption.flatMap(_._1).map(_.ownerPrefix),
                  instance.occurrence.map(_.fieldPath).getOrElse(Vector.empty)
                )
              )
            )
          case _                           =>
            break(
              Left(
                WholeFilePlanningFailure.ContextDependentProduction(
                  instance,
                  selections.map((context, matches) => context -> matches.map(_.id).sorted)
                )
              )
            )

      val active                                                          = collection.mutable.LinkedHashSet(root)
      val incoming                                                        = collection.mutable.LinkedHashMap.empty[ProductionInstanceId, Vector[ProductionInstanceId]]
      val compilerChildren                                                = collection.mutable.LinkedHashMap
        .empty[ProductionInstanceId, Vector[(String, Vector[ParserFieldPathSegment], ProductionInstanceId)]]
      val groupedChildren                                                 = collection.mutable.LinkedHashMap
        .empty[(ProductionInstanceId, String), Vector[Vector[ProductionInstanceId]]]
      def isSharedTemplateAbsent(instance: ProductionInstanceId): Boolean =
        selected(instance).id == "template-absent-tree" &&
          instance.kind == InventoryKind.Node &&
          rows(instance.kind -> instance.valueId).prefix == "Thicket" &&
          rows(instance.kind -> instance.valueId).sourceClassification == SourceClassification.Absent
      ordered.foreach: instance =>
        val production = selected(instance)
        production.dispositions.collectFirst:
          case FieldDisposition(fieldName, FieldDispositionKind.Unsupported) => fieldName
        match
          case Some(fieldName) =>
            break(Left(WholeFilePlanningFailure.UnsupportedFieldDisposition(instance, fieldName)))
          case None            => ()
        if production.layouts != Vector(LayoutAlternative.None) then
          break(Left(WholeFilePlanningFailure.UnsupportedLayout(instance, production.layouts)))
        if production.recovery != RecoveryPolicy.Reject then
          break(Left(WholeFilePlanningFailure.UnsupportedRecovery(instance, production.recovery)))
      ordered.foreach: instance =>
        if active(instance) then
          val production      = selected(instance)
          val plannedChildren = Vector.newBuilder[(String, Vector[ParserFieldPathSegment], ProductionInstanceId)]
          production.children.foreach: declaration =>
            if instance.kind == InventoryKind.Positioned then
              break(Left(WholeFilePlanningFailure.UnsupportedPositionedChildren(instance)))
            val field            = fields(instance).find(_.name == declaration.fieldName).toVector
            val allFound         = field.flatMap(value =>
              references(value.value, fieldPath(instance, value.name), instance).map: (kind, id, path) =>
                childInstance(instance, kind, id, path) -> path
            )
            val runtimeTailCount = declaration.slice match
              case ChildSlice.All | ChildSlice.MatchingProductions => 0
              case ChildSlice.LeadingBeforeRuntimeTail(fieldName)  =>
                runtimeSupplementCount(snapshot, instance, fieldName)
              case ChildSlice.RuntimeTail(fieldName)               => runtimeSupplementCount(snapshot, instance, fieldName)
            val found            = declaration.slice match
              case ChildSlice.All                         => allFound
              case ChildSlice.MatchingProductions         =>
                allFound.filter((child, _) => declaration.productionIds(selected(child).id))
              case ChildSlice.LeadingBeforeRuntimeTail(_) => allFound.dropRight(runtimeTailCount)
              case ChildSlice.RuntimeTail(_)              => allFound.takeRight(runtimeTailCount)
            if !accepts(declaration.cardinality, found.size) then
              break(
                Left(
                  WholeFilePlanningFailure.ChildCardinalityMismatch(
                    instance,
                    declaration.roleId,
                    declaration.cardinality,
                    found.size
                  )
                )
              )
            found.foreach: (child, path) =>
              val actual         = selected(child)
              if !declaration.productionIds(actual.id) then
                break(
                  Left(
                    WholeFilePlanningFailure.ChildProductionMismatch(
                      instance,
                      declaration.roleId,
                      declaration.productionIds.toVector.sorted.mkString("|"),
                      actual.id,
                      child
                    )
                  )
                )
              val previousOwners = incoming.getOrElse(child, Vector.empty)
              val owners         =
                if selected(instance).id == "template-self-absent" && previousOwners.exists(owner =>
                    owner != instance && owner.kind == instance.kind && owner.valueId == instance.valueId
                  )
                then previousOwners
                else previousOwners :+ instance
              incoming.update(child, owners)
              if owners.size > 1 && !isSharedTemplateAbsent(child) then
                break(Left(WholeFilePlanningFailure.MultiplyConsumedChildReference(child, owners)))
              active += child
              plannedChildren += ((declaration.roleId, path, child))
            declaration.cardinality match
              case ChildCardinality.Grouped(_, _) =>
                val groups  = Vector.newBuilder[Vector[ProductionInstanceId]]
                var current = Vector.empty[ProductionInstanceId]
                found.foreach: (child, path) =>
                  if !isSharedTemplateAbsent(child) then
                    val startsGroup     = position(child) match
                      case ParserNodePosition.Positioned(range, point, ParserPositionProvenance.SourceDerived)
                          if point >= range.startOffset && point <= range.endOffset =>
                        point != range.startOffset
                      case _ =>
                        break(
                          Left(
                            WholeFilePlanningFailure.InvalidGroupedChildPosition(
                              instance,
                              declaration.roleId,
                              child
                            )
                          )
                        )
                    val repeatedIndices = path.collect { case ParserFieldPathSegment.RepeatedIndex(value) => value }
                    if repeatedIndices.isEmpty then
                      break(
                        Left(
                          WholeFilePlanningFailure.InvalidGroupedChildPosition(
                            instance,
                            declaration.roleId,
                            child
                          )
                        )
                      )
                    if current.nonEmpty && startsGroup then
                      groups += current
                      current = Vector.empty
                    current :+= child
                if current.nonEmpty then groups += current
                groupedChildren += (instance -> declaration.roleId) -> groups.result()
              case _                              => ()
          compilerChildren.update(instance, plannedChildren.result())
      snapshot.diagnostics.indexWhere(_.severity == ParserDiagnosticSeverity.Error) match
        case index if index >= 0 => break(Left(WholeFilePlanningFailure.UnassignedDiagnostic(index)))
        case _                   => ()

      def lexicalSlice(range: PcSourceRange): Vector[ClosedSourceLexicalAtom] =
        val atoms                                        = evidence.lexicalContract.atoms
        def firstAtomEndingAfter(offset: Int): Int       =
          var low  = 0
          var high = atoms.size
          while low < high do
            val middle = low + (high - low) / 2
            if atoms(middle).end <= offset then low = middle + 1 else high = middle
          low
        def firstAtomStartingAtOrAfter(offset: Int): Int =
          var low  = 0
          var high = atoms.size
          while low < high do
            val middle = low + (high - low) / 2
            if atoms(middle).start < offset then low = middle + 1 else high = middle
          low
        atoms.slice(firstAtomEndingAfter(range.startOffset), firstAtomStartingAtOrAfter(range.endOffset))

      def parentOwner(instance: ProductionInstanceId): Either[String, ProductionInstanceId] =
        instance.occurrence
          .flatMap(occurrence =>
            active.find(candidate =>
              candidate.kind == InventoryKind.Node && candidate.valueId == occurrence.ownerNodeId
            )
          )
          .toRight("parent owner is absent")

      def templateLayoutStart(instance: ProductionInstanceId): Either[String, Option[Int]] =
        parentOwner(instance).flatMap: owner =>
          position(owner) match
            case ParserNodePosition.Positioned(ownerRange, point, ParserPositionProvenance.SourceDerived) =>
              val lexical   = lexicalSlice(ownerRange)
              val nameIndex = lexical.indexWhere(atom =>
                atom.start == point &&
                  (atom.kind == ClosedSourceLexicalKind.Identifier ||
                    atom.kind == ClosedSourceLexicalKind.QuotedIdentifier)
              )
              if nameIndex < 0 then Left("owner point is not one closed lexical identifier")
              else
                def trivia(atom: ClosedSourceLexicalAtom): Boolean = atom.kind match
                  case ClosedSourceLexicalKind.Whitespace | ClosedSourceLexicalKind.LineComment |
                      ClosedSourceLexicalKind.BlockComment =>
                    true
                  case _ => false
                def nextSignificant(after: Int): Option[Int]       =
                  lexical.indices.find(index => index > after && !trivia(lexical(index)))
                val constructorHasParameters                       = compilerChildren
                  .getOrElse(instance, Vector.empty)
                  .collectFirst:
                    case ("constructor", _, constructor) => constructor
                  .exists(constructor =>
                    compilerChildren.getOrElse(constructor, Vector.empty).exists((role, _, _) => role == "parameters")
                  )
                val afterName                                      = nextSignificant(nameIndex)
                val afterConstructor                               =
                  var next    = afterName
                  var invalid = false
                  if next.exists(index => lexical(index).kind == ClosedSourceLexicalKind.LeftBracket) then
                    val openIndex = next.get
                    var depth     = 0
                    var index     = openIndex
                    var closed    = false
                    while index < lexical.size && !closed do
                      lexical(index).kind match
                        case ClosedSourceLexicalKind.LeftBracket  => depth += 1
                        case ClosedSourceLexicalKind.RightBracket =>
                          depth -= 1
                          if depth == 0 then
                            next = nextSignificant(index)
                            closed = true
                        case _                                    => ()
                      index += 1
                    if !closed then invalid = true
                  while next.exists(index => lexical(index).kind == ClosedSourceLexicalKind.LeftParenthesis) && !invalid
                  do
                    val openIndex = next.get
                    if constructorHasParameters then
                      var depth  = 0
                      var index  = openIndex
                      var closed = false
                      while index < lexical.size && !closed do
                        lexical(index).kind match
                          case ClosedSourceLexicalKind.LeftParenthesis  => depth += 1
                          case ClosedSourceLexicalKind.RightParenthesis =>
                            depth -= 1
                            if depth == 0 then
                              next = nextSignificant(index)
                              closed = true
                          case _                                        => ()
                        index += 1
                      if !closed then invalid = true
                    else
                      nextSignificant(openIndex) match
                        case Some(closeIndex) if lexical(closeIndex).kind == ClosedSourceLexicalKind.RightParenthesis =>
                          next = nextSignificant(closeIndex)
                        case _                                                                                        => invalid = true
                  if !invalid then
                    val mountedHeaderEnd = compilerChildren
                      .getOrElse(instance, Vector.empty)
                      .collect:
                        case (role @ ("parents" | "derives"), _, child) => role -> position(child)
                      .collect:
                        case (_, ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived)) =>
                          range.endOffset
                      .maxOption
                    mountedHeaderEnd.foreach: endOffset =>
                      next = lexical.indices.find(index => lexical(index).start >= endOffset && !trivia(lexical(index)))
                  if invalid then Left("owner constructor is not admitted empty parentheses") else Right(next)
                afterConstructor.flatMap:
                  case None        => Right(None)
                  case Some(index) =>
                    lexical(index).kind match
                      case ClosedSourceLexicalKind.LeftBrace | ClosedSourceLexicalKind.Colon =>
                        Right(Some(lexical(index).start))
                      case _                                                                 =>
                        Left("owner header has an unsupported token after its name or empty constructor")
            case _                                                                                        =>
              Left("parent owner has no source-derived position")

      val resolvedRealizations = collection.mutable.LinkedHashMap.empty[ProductionInstanceId, OutputRealization]
      active.toVector.reverse.foreach: instance =>
        val children                                                                   = compilerChildren.getOrElse(instance, Vector.empty)
        def occurrence(condition: ChildOutcomeCondition): Option[ProductionInstanceId] =
          val values = children.collect { case (condition.roleId, _, child) => child }
          condition.occurrence match
            case ChildOccurrenceSelector.First        => values.headOption
            case ChildOccurrenceSelector.Last         => values.lastOption
            case ChildOccurrenceSelector.Exact(index) => values.lift(index)
        val matching                                                                   = selected(instance).effectiveOutputRealizations.filter(realization =>
          val childConditions    = realization.conditions.forall(condition =>
            occurrence(condition).exists(child =>
              condition.expected match
                case ChildOutcomeExpectation.Production(id)  => selected(child).id == id
                case ChildOutcomeExpectation.Realization(id) => resolvedRealizations(child).id == id
            )
          )
          val evidenceConditions = childConditions && realization.evidenceConditions.forall:
            case EvidenceCondition.TemplateBodyLayout(present)                               =>
              templateLayoutStart(instance)
                .fold(
                  reason =>
                    break(
                      Left(
                        WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                          instance,
                          realization.id,
                          OutputBoundary.TemplateLayoutStart,
                          reason
                        )
                      )
                    ),
                  _.nonEmpty == present
                )
            case EvidenceCondition.RepeatedFieldOccurrence(fieldName, valuePattern, present) =>
              val hasMatchingOccurrence = rows(instance.kind -> instance.valueId).observation
                .find(_.name == fieldName)
                .exists:
                  case InventoryFieldObservation(_, InventoryValueObservation.Repeated(values), _) =>
                    values.exists(CatalogShapeMatcher.matches(valuePattern, _))
                  case _                                                                           => false
              hasMatchingOccurrence == present
            case EvidenceCondition.RuntimeSupplementPositive(fieldName, present)             =>
              (runtimeSupplementCount(snapshot, instance, fieldName) > 0) == present
            case EvidenceCondition.LeadingBeforeRuntimeTailPresent(repeated, count, present) =>
              val repeatedCount = fields(instance)
                .find(_.name == repeated)
                .collect:
                  case ParserSyntaxField(_, ParserFieldValue.Repeated(values), _) => values.size
                .getOrElse(0)
              (repeatedCount > runtimeSupplementCount(snapshot, instance, count)) == present
          childConditions && evidenceConditions
        )
        val matches                                                                    = matching match
          case Vector() => Vector.empty
          case values   =>
            val mostSpecific = values.map(value => value.conditions.size + value.evidenceConditions.size).max
            values.filter(value => value.conditions.size + value.evidenceConditions.size == mostSpecific)
        matches match
          case Vector(value) => resolvedRealizations += instance -> value
          case Vector()      =>
            break(Left(WholeFilePlanningFailure.UnknownOutputRealization(instance, selected(instance).id)))
          case values        =>
            break(
              Left(
                WholeFilePlanningFailure.AmbiguousOutputRealization(
                  instance,
                  selected(instance).id,
                  values.map(_.id).sorted
                )
              )
            )

      val outputRoots                                                   = collection.mutable.Map.empty[ProductionInstanceId, Vector[CompositeInstanceId]]
      val localOutputRoots                                              = collection.mutable.Map.empty[ProductionInstanceId, Vector[CompositeInstanceId]]
      val outputRows                                                    = collection.mutable.Map
        .empty[ProductionInstanceId, Vector[(OutputCompositeDeclaration, CompositeInstanceId, PcSourceRange)]]
      val mergedOutputRoots                                             = collection.mutable.Map.empty[CompositeInstanceId, CompositeInstanceId]
      val outputRangeOverrides                                          = collection.mutable.Map.empty[CompositeInstanceId, PcSourceRange]
      val evidenceBoundaries                                            =
        (evidence.atoms.flatMap(atom =>
          Vector(atom.start, atom.end)
        ) ++ evidence.lexicalContract.boundaries).distinct.sorted
      val endMarkersByOwner                                             = snapshot.endMarkers.groupBy(_.ownerNodeId)
      def compilerEndMarker(
          instance: ProductionInstanceId
      )(using
          scala.util.boundary.Label[Either[WholeFilePlanningFailure, WholeFileProductionPlan]]
      ): Option[(PcSourceRange, PcSourceRange)] =
        val markerOwner =
          if selected(instance).grammarRoleId == GrammarRoleId.Template then
            parentOwner(instance).fold(
              reason => break(Left(WholeFilePlanningFailure.InvalidCompilerEndMarker(instance, reason))),
              identity
            )
          else instance
        if markerOwner.kind != InventoryKind.Node then None
        else
          endMarkersByOwner.get(markerOwner.valueId) match
            case None          => None
            case Some(markers) =>
              if markers.size != 1 then
                break(Left(WholeFilePlanningFailure.InvalidCompilerEndMarker(instance, "owner is not unique")))
              val marker          = markers.head
              val lexical         = evidence.lexicalContract.atoms
              val designator      = marker.designatorRange
              val designatorIndex = lexical.indexWhere(atom =>
                atom.start == designator.startOffset && atom.end == designator.endOffset &&
                  (atom.kind == ClosedSourceLexicalKind.Identifier ||
                    atom.kind == ClosedSourceLexicalKind.QuotedIdentifier)
              )
              if designatorIndex < 0 then
                break(
                  Left(
                    WholeFilePlanningFailure.InvalidCompilerEndMarker(
                      instance,
                      "designator is not one closed lexical identifier"
                    )
                  )
                )
              var keywordIndex    = designatorIndex - 1
              while keywordIndex >= 0 && (lexical(keywordIndex).kind match
                  case ClosedSourceLexicalKind.Whitespace | ClosedSourceLexicalKind.LineComment |
                      ClosedSourceLexicalKind.BlockComment =>
                    true
                  case _ => false
                )
              do keywordIndex -= 1
              val keyword         = lexical
                .lift(keywordIndex)
                .filter(atom =>
                  atom.kind == ClosedSourceLexicalKind.Identifier &&
                    snapshot.sourceText.substring(atom.start, atom.end) == "end"
                )
              keyword match
                case Some(atom) =>
                  Some(
                    PcSourceRange(atom.start, designator.endOffset) ->
                      PcSourceRange(atom.start, atom.end)
                  )
                case None       =>
                  break(
                    Left(
                      WholeFilePlanningFailure.InvalidCompilerEndMarker(
                        instance,
                        "compiler marker has no adjacent end-keyword evidence"
                      )
                    )
                  )
      def canonicalOutput(id: CompositeInstanceId): CompositeInstanceId =
        var current = id
        while mergedOutputRoots.contains(current) do current = mergedOutputRoots(current)
        current
      snapshot.endMarkers.foreach: marker =>
        val owners           = active.toVector.filter(instance =>
          instance.kind == InventoryKind.Node && instance.valueId == marker.ownerNodeId
        )
        val markerOwnerRoles = Set(
          GrammarRoleId.PackageClause,
          GrammarRoleId.ClassDefinition,
          GrammarRoleId.TraitDefinition,
          GrammarRoleId.ObjectDefinition,
          GrammarRoleId.EnumDefinition
        )
        if owners.size != 1 || !markerOwnerRoles(selected(owners.head).grammarRoleId) then
          break(
            Left(
              WholeFilePlanningFailure.InvalidCompilerEndMarker(
                owners.headOption.getOrElse(ProductionInstanceId(InventoryKind.Node, marker.ownerNodeId, None)),
                "marker owner is not one active end-marker owner"
              )
            )
          )
      active.toVector.reverse.foreach: instance =>
        val template = resolvedRealizations(instance).template
        def positionedRange(
            target: ProductionInstanceId,
            policy: PositionProvenancePolicy,
            boundary: OutputBoundary,
            outputId: String
        )(using scala.util.boundary.Label[Either[WholeFilePlanningFailure, WholeFileProductionPlan]]): PcSourceRange =
          position(target) match
            case ParserNodePosition.Positioned(value, _, ParserPositionProvenance.SourceDerived) => value
            case ParserNodePosition.Positioned(value, _, ParserPositionProvenance.Synthetic)
                if policy == PositionProvenancePolicy.PositionedIncludingSynthetic =>
              value
            case _                                                                               =>
              break(
                Left(
                  WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                    instance,
                    outputId,
                    boundary,
                    "position is absent or synthetic"
                  )
                )
              )
        def resolve(boundary: OutputBoundary, outputId: String)(using
            scala.util.boundary.Label[Either[WholeFilePlanningFailure, WholeFileProductionPlan]]
        ): Int = boundary match
          case value @ OutputBoundary.ProductionStart(policy)                                    =>
            positionedRange(instance, policy, value, outputId).startOffset
          case value @ OutputBoundary.ProductionEnd(policy)                                      =>
            positionedRange(instance, policy, value, outputId).endOffset
          case OutputBoundary.ProductionPoint                                                    =>
            position(instance) match
              case ParserNodePosition.Positioned(_, point, ParserPositionProvenance.SourceDerived) => point
              case _                                                                               =>
                break(
                  Left(
                    WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                      instance,
                      outputId,
                      boundary,
                      "production point is absent or synthetic"
                    )
                  )
                )
          case OutputBoundary.ProductionNameEnd                                                  =>
            val point = resolve(OutputBoundary.ProductionPoint, outputId)
            evidence.lexicalContract.atoms
              .find(atom =>
                atom.start == point &&
                  (atom.kind == ClosedSourceLexicalKind.Identifier ||
                    atom.kind == ClosedSourceLexicalKind.QuotedIdentifier)
              )
              .map(_.end)
              .getOrElse(
                break(
                  Left(
                    WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                      instance,
                      outputId,
                      boundary,
                      "production point is not one closed lexical identifier"
                    )
                  )
                )
              )
          case OutputBoundary.ParentProductionEnd                                                =>
            parentOwner(instance)
              .map(owner =>
                positionedRange(
                  owner,
                  PositionProvenancePolicy.SourceDerivedOnly,
                  boundary,
                  outputId
                ).endOffset
              )
              .fold(
                reason =>
                  break(
                    Left(
                      WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                        instance,
                        outputId,
                        boundary,
                        reason
                      )
                    )
                  ),
                identity
              )
          case OutputBoundary.TemplateLayoutStart                                                =>
            templateLayoutStart(instance)
              .flatMap(_.toRight("template body layout is absent"))
              .fold(
                reason =>
                  break(
                    Left(
                      WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                        instance,
                        outputId,
                        boundary,
                        reason
                      )
                    )
                  ),
                identity
              )
          case value @ OutputBoundary.PreviousSignificantChildTokenStart(role, selector, policy) =>
            val candidates    = compilerChildren(instance).collect { case (`role`, _, child) => child }
            val selectedChild = selector match
              case ChildOccurrenceSelector.First          => candidates.headOption
              case ChildOccurrenceSelector.Last           => candidates.lastOption
              case ChildOccurrenceSelector.Exact(ordinal) => candidates.lift(ordinal)
            val childStart    = selectedChild
              .map(positionedRange(_, policy, value, outputId).startOffset)
              .getOrElse(
                break(
                  Left(
                    WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                      instance,
                      outputId,
                      value,
                      "child occurrence is missing"
                    )
                  )
                )
              )
            evidence.lexicalContract.atoms
              .takeWhile(_.end <= childStart)
              .reverseIterator
              .find(atom =>
                atom.kind != ClosedSourceLexicalKind.Whitespace && atom.kind != ClosedSourceLexicalKind.LineComment &&
                  atom.kind != ClosedSourceLexicalKind.BlockComment
              )
              .map(_.start)
              .getOrElse(
                break(
                  Left(
                    WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                      instance,
                      outputId,
                      value,
                      "no preceding significant lexical token"
                    )
                  )
                )
              )
          case value @ OutputBoundary.ChildStart(role, selector, policy)                         =>
            val candidates    = compilerChildren(instance).collect { case (`role`, _, child) => child }
            val selectedChild = selector match
              case ChildOccurrenceSelector.First          => candidates.headOption
              case ChildOccurrenceSelector.Last           => candidates.lastOption
              case ChildOccurrenceSelector.Exact(ordinal) => candidates.lift(ordinal)
            val child         = selectedChild.getOrElse(
              break(
                Left(
                  WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                    instance,
                    outputId,
                    value,
                    "child occurrence is missing"
                  )
                )
              )
            )
            val range         = positionedRange(child, policy, value, outputId)
            range.startOffset
          case value @ OutputBoundary.ChildEnd(role, selector, policy)                           =>
            val candidates    = compilerChildren(instance).collect { case (`role`, _, child) => child }
            val selectedChild = selector match
              case ChildOccurrenceSelector.First          => candidates.headOption
              case ChildOccurrenceSelector.Last           => candidates.lastOption
              case ChildOccurrenceSelector.Exact(ordinal) => candidates.lift(ordinal)
            val child         = selectedChild.getOrElse(
              break(
                Left(
                  WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                    instance,
                    outputId,
                    value,
                    "child occurrence is missing"
                  )
                )
              )
            )
            positionedRange(child, policy, value, outputId).endOffset
          case value @ OutputBoundary.EvidenceBoundaryAfterChild(
                role,
                selector,
                followingRole,
                followingSelector,
                expectedDelimiters,
                policy,
                fallbackToFollowingChildStart
              ) =>
            val candidates                                             = compilerChildren(instance).collect { case (`role`, _, child) => child }
            val selectedChild                                          = selector match
              case ChildOccurrenceSelector.First          => candidates.headOption
              case ChildOccurrenceSelector.Last           => candidates.lastOption
              case ChildOccurrenceSelector.Exact(ordinal) => candidates.lift(ordinal)
            val child                                                  = selectedChild.getOrElse(
              break(
                Left(
                  WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                    instance,
                    outputId,
                    value,
                    "child occurrence is missing"
                  )
                )
              )
            )
            val end                                                    = positionedRange(child, policy, value, outputId).endOffset
            def sourceStart(target: ProductionInstanceId): Option[Int] =
              val pending = collection.mutable.Stack(target)
              val visited = collection.mutable.Set.empty[ProductionInstanceId]
              var start   = Option.empty[Int]
              while pending.nonEmpty do
                val current = pending.pop()
                if visited.add(current) then
                  position(current) match
                    case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived) =>
                      start = Some(start.fold(range.startOffset)(math.min(_, range.startOffset)))
                    case _                                                                               =>
                      compilerChildren
                        .getOrElse(current, Vector.empty)
                        .reverseIterator
                        .foreach((_, _, child) => pending.push(child))
              start
            val following                                              = compilerChildren(instance).collect { case (`followingRole`, _, candidate) => candidate }
            val followingChild                                         = followingSelector match
              case ChildOccurrenceSelector.First          => following.headOption
              case ChildOccurrenceSelector.Last           => following.lastOption
              case ChildOccurrenceSelector.Exact(ordinal) => following.lift(ordinal)
            val followingStart                                         = followingChild
              .flatMap(sourceStart)
              .getOrElse(
                break(
                  Left(
                    WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                      instance,
                      outputId,
                      value,
                      "following child occurrence is missing"
                    )
                  )
                )
              )
            val delimiter                                              = expectedDelimiters.iterator
              .flatMap: expected =>
                evidence.lexicalContract.atoms.iterator
                  .filter(atom => end <= atom.start && atom.end <= followingStart)
                  .filter(atom => snapshot.sourceText.substring(atom.start, atom.end) == expected)
                  .map(_.start)
              .minOption
            delimiter
              .orElse(Option.when(fallbackToFollowingChildStart)(followingStart))
              .getOrElse(
                break(
                  Left(
                    WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                      instance,
                      outputId,
                      value,
                      s"none of the expected delimiters occur before the following child: ${expectedDelimiters.mkString(", ")}"
                    )
                  )
                )
              )
          case value @ OutputBoundary.Advance(base, count)                                       =>
            val offset    = resolve(base, outputId)
            val index     = evidenceBoundaries.indexOf(offset)
            val remaining = if index < 0 then -1L else evidenceBoundaries.size.toLong - index.toLong - 1L
            if index < 0 || count.toLong > remaining then
              break(
                Left(
                  WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                    instance,
                    outputId,
                    value,
                    "advance is outside evidence boundaries"
                  )
                )
              )
            evidenceBoundaries((index.toLong + count.toLong).toInt)
        def withTrailingBalancedBrackets(
            base: PcSourceRange,
            outputId: String
        )(using
            scala.util.boundary.Label[Either[WholeFilePlanningFailure, WholeFileProductionPlan]]
        ): PcSourceRange =
          val suffix = evidence.lexicalContract.atoms.iterator.dropWhile(_.start < base.endOffset)
          var atom   = Option.empty[ClosedSourceLexicalAtom]
          while suffix.hasNext && atom.isEmpty do
            val candidate = suffix.next()
            candidate.kind match
              case ClosedSourceLexicalKind.Whitespace | ClosedSourceLexicalKind.LineComment |
                  ClosedSourceLexicalKind.BlockComment =>
                ()
              case _ => atom = Some(candidate)
          atom match
            case Some(open) if open.kind == ClosedSourceLexicalKind.LeftBracket =>
              var depth = 1
              var end   = open.end
              while suffix.hasNext && depth > 0 do
                val candidate = suffix.next()
                candidate.kind match
                  case ClosedSourceLexicalKind.LeftBracket  => depth += 1
                  case ClosedSourceLexicalKind.RightBracket => depth -= 1
                  case _                                    => ()
                end = candidate.end
              if depth != 0 then
                break(
                  Left(
                    WholeFilePlanningFailure.InvalidOutputRange(
                      instance,
                      outputId,
                      base.startOffset,
                      end,
                      base
                    )
                  )
                )
              PcSourceRange(base.startOffset, end)
            case _                                                              => base
        def repeatedOccurrenceRanges(
            declaration: OutputCompositeDeclaration,
            fieldName: String,
            valuePattern: CatalogValuePattern,
            opening: ClosedSourceLexicalKind,
            closing: ClosedSourceLexicalKind
        ): Vector[(OutputCompositeDeclaration, CompositeInstanceId, PcSourceRange)] =
          val values           = rows(instance.kind -> instance.valueId).observation
            .find(_.name == fieldName)
            .toVector
            .flatMap:
              case InventoryFieldObservation(_, InventoryValueObservation.Repeated(values), _) => values
              case _                                                                           => Vector.empty
          val matchingOrdinals = values.zipWithIndex.collect:
            case (value, ordinal) if CatalogShapeMatcher.matches(valuePattern, value) => ordinal
          val ownerRange       = positionedRange(
            instance,
            PositionProvenancePolicy.SourceDerivedOnly,
            OutputBoundary.ProductionStart(PositionProvenancePolicy.SourceDerivedOnly),
            declaration.id
          )
          val lexical          = lexicalSlice(ownerRange)
          val lexicalPairs     =
            val pairs      = Vector.newBuilder[PcSourceRange]
            var openStarts = List.empty[Int]
            lexical.foreach: atom =>
              if atom.kind == opening then openStarts = atom.start :: openStarts
              else if atom.kind == closing then
                openStarts match
                  case start :: remaining =>
                    openStarts = remaining
                    if remaining.isEmpty then pairs += PcSourceRange(start, atom.end)
                  case Nil                => ()
            pairs.result()
          val childExtent      = compilerChildren
            .getOrElse(instance, Vector.empty)
            .flatMap: (_, _, child) =>
              position(child) match
                case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived) => Some(range)
                case _                                                                               => None
          val enclosingPair    = for
            start <- childExtent.map(_.startOffset).minOption
            end   <- childExtent.map(_.endOffset).maxOption
            left  <- lexical.filter(atom => atom.kind == opening && atom.start <= start).lastOption
            right <- lexical.find(atom => atom.kind == closing && atom.end >= end)
          yield PcSourceRange(left.start, right.end)
          val pairs            = if lexicalPairs.nonEmpty then lexicalPairs else enclosingPair.toVector
          if matchingOrdinals.size != pairs.size then
            break(
              Left(
                WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                  instance,
                  declaration.id,
                  OutputBoundary.ProductionStart(),
                  "repeated field occurrences do not correlate one-to-one with adjacent lexical delimiters"
                )
              )
            )
          matchingOrdinals.zip(pairs).map { case (ordinal, range) =>
            (declaration, CompositeInstanceId(instance, declaration.id, ordinal), range)
          }

        val expandedDeclarations = template.composites
          .filter(declaration => !declaration.requiresCompilerEndMarker || compilerEndMarker(instance).nonEmpty)
          .flatMap: declaration =>
            declaration.realization match
              case OutputCompositeRealization.PerChildRole(roleId) =>
                compilerChildren
                  .getOrElse(instance, Vector.empty)
                  .collect { case (`roleId`, _, child) => child }
                  .zipWithIndex
                  .map: (child, ordinal) =>
                    val range = position(child) match
                      case ParserNodePosition.Positioned(value, _, ParserPositionProvenance.SourceDerived) => value
                      case _                                                                               =>
                        break(
                          Left(
                            WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                              instance,
                              declaration.id,
                              OutputBoundary.ProductionStart(),
                              "child position is absent or synthetic"
                            )
                          )
                        )
                    (declaration, CompositeInstanceId(instance, declaration.id, ordinal), Some(range))
              case OutputCompositeRealization.AtFirstRepeatedFieldOccurrenceStart(
                    fieldName,
                    valuePattern,
                    opening,
                    closing
                  ) =>
                repeatedOccurrenceRanges(declaration, fieldName, valuePattern, opening, closing).headOption.toVector
                  .map: (_, _, first) =>
                    val range = PcSourceRange(first.startOffset, first.startOffset)
                    (declaration, CompositeInstanceId(instance, declaration.id), Some(range))
              case OutputCompositeRealization.PerRepeatedFieldOccurrence(
                    fieldName,
                    valuePattern,
                    opening,
                    closing
                  ) =>
                repeatedOccurrenceRanges(declaration, fieldName, valuePattern, opening, closing).map:
                  case (value, id, range) => (value, id, Some(range))
              case OutputCompositeRealization.AcrossRepeatedFieldOccurrences(
                    fieldName,
                    valuePattern,
                    opening,
                    closing
                  ) =>
                val occurrences = repeatedOccurrenceRanges(declaration, fieldName, valuePattern, opening, closing)
                occurrences.headOption.toVector.map: (_, _, first) =>
                  val range = PcSourceRange(first.startOffset, occurrences.last._3.endOffset)
                  (declaration, CompositeInstanceId(instance, declaration.id), Some(range))
              case OutputCompositeRealization.Once                 =>
                Vector((declaration, CompositeInstanceId(instance, declaration.id), None))
        val ranges               = expandedDeclarations.map { (declaration, compositeId, realizedRange) =>
          val range         = declaration.range match
            case OutputRangeDeclaration.CompilerPosition                                     =>
              position(instance) match
                case ParserNodePosition.Positioned(value, _, ParserPositionProvenance.SourceDerived) => value
                case _                                                                               =>
                  break(
                    Left(
                      WholeFilePlanningFailure.InvalidCatalog(
                        Vector(
                          CatalogValidationError.UnsupportedOutputRange(
                            selected(instance).id,
                            declaration.id,
                            declaration.range
                          )
                        )
                      )
                    )
                  )
            case OutputRangeDeclaration.CompilerPositionWithPolicy(policy)                   =>
              positionedRange(instance, policy, OutputBoundary.ProductionStart(policy), declaration.id)
            case OutputRangeDeclaration.CompilerPositionWithTrailingBalancedBrackets(policy) =>
              val base = positionedRange(
                instance,
                policy,
                OutputBoundary.ProductionStart(policy),
                declaration.id
              )
              withTrailingBalancedBrackets(base, declaration.id)
            case OutputRangeDeclaration.CompilerPositionWithBodyLayoutOrEndMarker(
                  headerRole,
                  bodyRole,
                  opening,
                  closing,
                  indentation
                ) =>
              val base         = positionedRange(
                instance,
                PositionProvenancePolicy.SourceDerivedOnly,
                OutputBoundary.ProductionStart(PositionProvenancePolicy.SourceDerivedOnly),
                declaration.id
              )
              val children     = compilerChildren.getOrElse(instance, Vector.empty)
              val headerRange  = children
                .collect { case (`headerRole`, _, child) => child }
                .flatMap(child =>
                  position(child) match
                    case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived) =>
                      Some(range)
                    case _                                                                               => None
                )
                .maxByOption(_.endOffset)
                .getOrElse(
                  break(
                    Left(
                      WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                        instance,
                        declaration.id,
                        OutputBoundary.ProductionStart(PositionProvenancePolicy.SourceDerivedOnly),
                        "header child has no source-derived range"
                      )
                    )
                  )
                )
              val bodyStart    = bodyRole.toVector
                .flatMap(role => children.collect { case (`role`, _, child) => child })
                .flatMap(child =>
                  position(child) match
                    case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived) =>
                      Some(range.startOffset)
                    case _                                                                               => None
                )
                .minOption
                .getOrElse(base.endOffset)
              val delimiter    = evidence.lexicalContract.atoms.find(atom =>
                headerRange.endOffset <= atom.start && atom.end <= bodyStart &&
                  (atom.kind == opening || atom.kind == indentation)
              )
              var delimiterEnd = base.endOffset
              if delimiter.exists(_.kind == opening) then
                var balance = 0
                evidence.lexicalContract.atoms
                  .filter(atom => base.startOffset <= atom.start && atom.end <= base.endOffset)
                  .foreach: atom =>
                    if atom.kind == opening then balance += 1
                    else if atom.kind == closing then balance -= 1
                if balance < 0 then
                  break(
                    Left(
                      WholeFilePlanningFailure.InvalidOutputRange(
                        instance,
                        declaration.id,
                        base.startOffset,
                        base.endOffset,
                        base
                      )
                    )
                  )
                if balance > 0 then
                  var remaining = balance
                  var found     = Option.empty[Int]
                  val suffix    = evidence.lexicalContract.atoms.iterator.filter(_.start >= base.endOffset)
                  while suffix.hasNext && found.isEmpty do
                    val atom = suffix.next()
                    if atom.kind == opening then remaining += 1
                    else if atom.kind == closing then
                      remaining -= 1
                      if remaining == 0 then found = Some(atom.end)
                  delimiterEnd = found.getOrElse(
                    break(
                      Left(
                        WholeFilePlanningFailure.InvalidOutputRange(
                          instance,
                          declaration.id,
                          base.startOffset,
                          base.endOffset,
                          base
                        )
                      )
                    )
                  )
              val marker       = compilerEndMarker(instance)
              if delimiter.exists(_.kind == indentation) && marker.isEmpty then
                delimiterEnd = evidence.lexicalContract.atoms
                  .find(atom =>
                    atom.start >= base.endOffset && (atom.kind match
                      case ClosedSourceLexicalKind.Whitespace | ClosedSourceLexicalKind.LineComment |
                          ClosedSourceLexicalKind.BlockComment | ClosedSourceLexicalKind.Semicolon =>
                        false
                      case _ => true
                    )
                  )
                  .fold(snapshot.sourceLength)(_.start)
              val markerEnd    = marker.fold(base.endOffset)(_._1.endOffset)
              if delimiterEnd < base.endOffset then
                break(
                  Left(
                    WholeFilePlanningFailure.InvalidOutputRange(
                      instance,
                      declaration.id,
                      base.startOffset,
                      base.endOffset,
                      base
                    )
                  )
                )
              PcSourceRange(base.startOffset, math.max(delimiterEnd, markerEnd))
            case OutputRangeDeclaration.CompilerEndMarker                                    =>
              compilerEndMarker(instance)
                .map(_._1)
                .getOrElse(
                  break(
                    Left(
                      WholeFilePlanningFailure.InvalidCompilerEndMarker(
                        instance,
                        "required marker evidence is absent"
                      )
                    )
                  )
                )
            case OutputRangeDeclaration.BoundaryDerived(startBoundary, endBoundary)          =>
              val start = resolve(startBoundary, declaration.id)
              val end   = resolve(endBoundary, declaration.id)
              if start > end then
                break(
                  Left(
                    WholeFilePlanningFailure.InvalidOutputRange(
                      instance,
                      declaration.id,
                      start,
                      end,
                      positionedRange(
                        instance,
                        PositionProvenancePolicy.PositionedIncludingSynthetic,
                        startBoundary,
                        declaration.id
                      )
                    )
                  )
                )
              PcSourceRange(start, end)
            case OutputRangeDeclaration.BoundaryDerivedWithTrailingBalancedBrackets(
                  startBoundary,
                  endBoundary
                ) =>
              val start = resolve(startBoundary, declaration.id)
              val end   = resolve(endBoundary, declaration.id)
              if start > end then
                break(
                  Left(
                    WholeFilePlanningFailure.InvalidOutputRange(
                      instance,
                      declaration.id,
                      start,
                      end,
                      positionedRange(
                        instance,
                        PositionProvenancePolicy.PositionedIncludingSynthetic,
                        startBoundary,
                        declaration.id
                      )
                    )
                  )
                )
              withTrailingBalancedBrackets(PcSourceRange(start, end), declaration.id)
          val ownerRange    = positionedRange(
            instance,
            PositionProvenancePolicy.PositionedIncludingSynthetic,
            OutputBoundary.ProductionStart(PositionProvenancePolicy.PositionedIncludingSynthetic),
            declaration.id
          )
          val parentDerived = declaration.range match
            case OutputRangeDeclaration.BoundaryDerived(start, end) =>
              Set(start, end).exists:
                case OutputBoundary.ParentProductionEnd | OutputBoundary.TemplateLayoutStart |
                    OutputBoundary.PreviousSignificantChildTokenStart(_, _, _) =>
                  true
                case _ => false
            case OutputRangeDeclaration.CompilerEndMarker
                if selected(instance).grammarRoleId == GrammarRoleId.Template =>
              true
            case _                                                  => false
          val extentRange   =
            if parentDerived then
              parentOwner(instance)
                .map(owner =>
                  positionedRange(
                    owner,
                    PositionProvenancePolicy.SourceDerivedOnly,
                    OutputBoundary.ParentProductionEnd,
                    declaration.id
                  )
                )
                .fold(
                  reason =>
                    break(
                      Left(
                        WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                          instance,
                          declaration.id,
                          OutputBoundary.ParentProductionEnd,
                          reason
                        )
                      )
                    ),
                  identity
                )
            else ownerRange
          val validExtent   = declaration.range match
            case OutputRangeDeclaration.CompilerPositionWithBodyLayoutOrEndMarker(_, _, _, _, _) |
                OutputRangeDeclaration.CompilerPositionWithTrailingBalancedBrackets(_) |
                OutputRangeDeclaration.BoundaryDerivedWithTrailingBalancedBrackets(_, _) =>
              range.startOffset >= ownerRange.startOffset && range.endOffset <= snapshot.sourceLength
            case _ if parentDerived =>
              range.startOffset >= extentRange.startOffset && range.endOffset <= extentRange.endOffset
            case _                  =>
              range.startOffset >= ownerRange.startOffset && range.endOffset <= ownerRange.endOffset
          if !validExtent || range.startOffset > range.endOffset || !evidenceBoundaries.contains(
              range.startOffset
            ) || !evidenceBoundaries.contains(range.endOffset)
          then
            break(
              Left(
                WholeFilePlanningFailure.InvalidOutputRange(
                  instance,
                  declaration.id,
                  range.startOffset,
                  range.endOffset,
                  ownerRange
                )
              )
            )
          (declaration, compositeId, realizedRange.getOrElse(range))
        }
        outputRows.update(instance, ranges)
        val localRoots           = ranges.collect { case (declaration, id, _) if declaration.parentId.isEmpty => id }
        localOutputRoots.update(instance, localRoots)
        groupedChildren
          .collect { case ((owner, role), groups) if owner == instance => role -> groups }
          .foreach: (role, groups) =>
            groups.foreach: group =>
              val roots     = group.map: child =>
                val values = outputRoots.getOrElse(child, Vector.empty)
                if values.size != 1 then
                  break(
                    Left(
                      WholeFilePlanningFailure.GroupedChildOutputRootCount(instance, role, child, values.size)
                    )
                  )
                values.head
              val rows      = roots.map: root =>
                outputRows(root.origin)
                  .find(_._2 == root)
                  .getOrElse(
                    break(
                      Left(
                        WholeFilePlanningFailure.GroupedChildOutputRootCount(instance, role, root.origin, 0)
                      )
                    )
                  )
              val contracts = rows.map: (declaration, _, _) =>
                declaration.copy(id = "grouped-root", range = OutputRangeDeclaration.CompilerPosition)
              if contracts.distinct.size != 1 then
                break(Left(WholeFilePlanningFailure.IncompatibleGroupedOutputRoots(instance, role, roots)))
              val canonical = roots.head
              roots.tail.foreach(mergedOutputRoots.update(_, canonical))
              outputRangeOverrides.update(
                canonical,
                PcSourceRange(rows.head._3.startOffset, rows.last._3.endOffset)
              )
        val exported             = compilerChildren
          .getOrElse(instance, Vector.empty)
          .flatMap: (role, _, child) =>
            val mount = template.childMounts.getOrElse(
              role,
              break(
                Left(
                  WholeFilePlanningFailure.InvalidCatalog(
                    Vector(CatalogValidationError.MissingChildMountRole(selected(instance).id, role))
                  )
                )
              )
            )
            if mount.isEmpty then outputRoots.getOrElse(child, Vector.empty).map(canonicalOutput) else Vector.empty
        outputRoots.update(instance, (localRoots.map(canonicalOutput) ++ exported).distinct)

      val outputRangesById                                      = outputRows.valuesIterator.flatten.map(row => row._2 -> row._3).toMap
      val promotedChildOutputs                                  = collection.mutable.LinkedHashSet.empty[CompositeInstanceId]
      val rawComposites                                         = active.toVector.flatMap: instance =>
        val production = selected(instance)
        val template   = resolvedRealizations(instance).template
        outputRows(instance).map: (declaration, id, range) =>
          val localChildren = outputRows(instance).collect {
            case (child, childId, _) if child.parentId.contains(declaration.id) =>
              PlannedChild("output", Vector.empty, childId)
          }
          val mounted       = compilerChildren
            .getOrElse(instance, Vector.empty)
            .flatMap: (role, path, child) =>
              val ordinalMatches = declaration.realization match
                case OutputCompositeRealization.PerChildRole(roleId)                           =>
                  role == roleId && path
                    .collectFirst { case ParserFieldPathSegment.RepeatedIndex(value) => value }
                    .contains(id.ordinal)
                case OutputCompositeRealization.PerRepeatedFieldOccurrence(fieldName, _, _, _) =>
                  path match
                    case Vector(
                          ParserFieldPathSegment.NamedField(`fieldName`),
                          ParserFieldPathSegment.RepeatedIndex(ordinal),
                          _*
                        ) =>
                      id.ordinal == ordinal
                    case _ => false
                case _                                                                         => true
              template.childMounts.getOrElse(
                role,
                break(
                  Left(
                    WholeFilePlanningFailure.InvalidCatalog(
                      Vector(CatalogValidationError.MissingChildMountRole(production.id, role))
                    )
                  )
                )
              ) match
                case Some(parent) if parent == declaration.id && ordinalMatches =>
                  val roots = template.childOutputSelections.get(role) match
                    case Some(outputRole) =>
                      val selectedRoots = outputRows(child).collect:
                        case (output, outputId, _) if output.outputRoleId == outputRole => outputId
                      if selectedRoots.size != 1 then
                        break(
                          Left(
                            WholeFilePlanningFailure.GroupedChildOutputRootCount(
                              instance,
                              role,
                              child,
                              selectedRoots.size
                            )
                          )
                        )
                      promotedChildOutputs += selectedRoots.head
                      selectedRoots
                    case None             => outputRoots(child)
                  roots.map(root => PlannedChild(role, path, canonicalOutput(root)))
                case None                                                       => Vector.empty
                case _                                                          => Vector.empty
          val children      = localChildren ++ mounted
          val normalized    = children.sortBy: child =>
            val childRange = outputRangesById(child.child)
            (childRange.startOffset, childRange.endOffset, child.child.toString)
          PlannedComposite(id, production.id, range, normalized, production.dispositions)
      val suppressedChildRoots                                  = promotedChildOutputs.flatMap: promoted =>
        localOutputRoots.getOrElse(promoted.origin, Vector.empty).filterNot(_ == promoted)
      val promotedOutputsByOrigin                               = promotedChildOutputs.toVector.groupMap(_.origin)(identity)
      val selectedRawComposites                                 = rawComposites.filterNot(value => suppressedChildRoots(value.instance))
      val rawById                                               = selectedRawComposites.map(value => value.instance -> value).toMap
      val mergedSources                                         = mergedOutputRoots.toVector.groupMap(_._2)(_._1)
      val composites                                            = selectedRawComposites
        .filterNot(value => mergedOutputRoots.contains(value.instance))
        .map: composite =>
          val sourceRoots = composite.instance +: mergedSources.getOrElse(composite.instance, Vector.empty)
          val children    = sourceRoots
            .flatMap(root => rawById(root).children)
            .map(child => child.copy(child = canonicalOutput(child.child)))
            .distinctBy(_.child)
          composite.copy(
            range = outputRangeOverrides.getOrElse(composite.instance, composite.range),
            children = children.sortBy(child =>
              val range = outputRangeOverrides.getOrElse(child.child, rawById(child.child).range)
              (range.startOffset, range.endOffset, child.child.toString)
            )
          )
      val compositeRanges                                       = composites.map(value => value.instance -> value.range).toMap
      composites.foreach(parent =>
        parent.children.foreach: child =>
          val parentRange = parent.range
          val childRange  = compositeRanges(child.child)
          if childRange.startOffset < parentRange.startOffset || childRange.endOffset > parentRange.endOffset then
            break(Left(WholeFilePlanningFailure.OutputChildOutsideParent(parent.instance, child.child)))
      )
      def rejectOverlap(ids: Vector[CompositeInstanceId]): Unit =
        ids
          .sortBy(id => (compositeRanges(id).startOffset, compositeRanges(id).endOffset, id.toString))
          .sliding(2)
          .foreach:
            case Vector(left, right) if compositeRanges(left).endOffset > compositeRanges(right).startOffset =>
              break(Left(WholeFilePlanningFailure.OverlappingOutputForest(left, right)))
            case _                                                                                           => ()
      rejectOverlap(outputRoots(root))
      composites.foreach(parent => rejectOverlap(parent.children.map(_.child)))

      def childGapIntervals(
          instance: ProductionInstanceId,
          startRole: String,
          endRole: String
      ): Vector[PcSourceRange] =
        def ranges(role: String): Vector[PcSourceRange] =
          compilerChildren
            .getOrElse(instance, Vector.empty)
            .collect:
              case (`role`, _, child) => position(child)
            .collect:
              case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived) => range
        (ranges(startRole), ranges(endRole)) match
          case (starts, ends) if starts.nonEmpty && ends.nonEmpty =>
            val start = starts.maxBy(_.endOffset)
            val end   = ends.minBy(_.startOffset)
            if start.endOffset <= end.startOffset then Vector(PcSourceRange(start.endOffset, end.startOffset))
            else Vector.empty
          case _                                                  => Vector.empty

      def terminalIntervals(
          instance: ProductionInstanceId,
          production: Scala3PsiProduction,
          terminal: TerminalDeclaration
      ): Vector[PcSourceRange] = terminal.selector match
        case TerminalIntervalSelector.WholeSource if instance != root        =>
          break(
            Left(
              WholeFilePlanningFailure.UnsupportedTerminalSelector(
                production.id,
                terminal.id,
                terminal.selector
              )
            )
          )
        case TerminalIntervalSelector.WholeSource                            =>
          Vector(PcSourceRange(0, snapshot.sourceLength))
        case TerminalIntervalSelector.LocalOutput(outputId)                  =>
          outputRows
            .getOrElse(instance, Vector.empty)
            .collect { case (declaration, _, range) if declaration.id == outputId => range }
        case TerminalIntervalSelector.RootOutsideLocalOutput(outputId)       =>
          if instance != root then Vector.empty
          else
            outputRows
              .getOrElse(instance, Vector.empty)
              .collectFirst { case (declaration, _, range) if declaration.id == outputId => range }
              .toVector
              .flatMap(range =>
                Vector(PcSourceRange(0, range.startOffset), PcSourceRange(range.endOffset, snapshot.sourceLength))
                  .filter(value => value.startOffset < value.endOffset)
              )
        case TerminalIntervalSelector.WholeProduction                        =>
          position(instance) match
            case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived)
                if range.startOffset < range.endOffset =>
              Vector(range)
            case _ => Vector.empty
        case TerminalIntervalSelector.ChildGap(startRole, endRole)           =>
          childGapIntervals(instance, startRole, endRole)
        case TerminalIntervalSelector.BeforeChild(roleId)                    =>
          (
            position(instance),
            compilerChildren.getOrElse(instance, Vector.empty).collectFirst { case (`roleId`, _, child) =>
              position(child)
            }
          ) match
            case (
                  ParserNodePosition.Positioned(parent, _, ParserPositionProvenance.SourceDerived),
                  Some(ParserNodePosition.Positioned(child, _, _))
                ) if parent.startOffset <= child.startOffset =>
              Vector(PcSourceRange(parent.startOffset, child.startOffset))
            case _ => Vector.empty
        case TerminalIntervalSelector.CompilerEndMarkerKeyword               =>
          compilerEndMarker(instance).map(_._2).toVector
        case TerminalIntervalSelector.CompilerScannerToken(kind, occurrence) =>
          position(instance) match
            case ParserNodePosition.Positioned(range, _, _) =>
              val matches = snapshot.scannerTokens
                .filter(token =>
                  token.kind == kind && range.startOffset <= token.range.startOffset && token.range.endOffset <= range.endOffset
                )
                .map(_.range)
              occurrence match
                case ScannerTokenOccurrence.All   => matches
                case ScannerTokenOccurrence.First => matches.headOption.toVector
                case ScannerTokenOccurrence.Last  => matches.lastOption.toVector
            case _                                          => Vector.empty
        case other                                                           =>
          break(Left(WholeFilePlanningFailure.UnsupportedTerminalSelector(production.id, terminal.id, other)))

      def terminalTokenRanges(
          instance: ProductionInstanceId,
          terminal: TerminalDeclaration,
          intervals: Vector[PcSourceRange]
      ): Vector[PcSourceRange] = terminal.target match
        case TerminalLeafTarget.Token(_, Some(expected)) =>
          intervals.flatMap: interval =>
            evidence.lexicalContract.atoms
              .filter(atom => interval.startOffset <= atom.start && atom.end <= interval.endOffset)
              .filter(atom => snapshot.sourceText.substring(atom.start, atom.end) == expected)
              .filter(atom =>
                evidence.atoms.exists(sourceAtom =>
                  sourceAtom.start <= atom.start && atom.end <= sourceAtom.end && sourceAtom.claims.exists(
                    claims(instance, _)
                  )
                )
              )
              .map(atom => PcSourceRange(atom.start, atom.end))
        case _                                           => Vector.empty

      def terminalLexicalKinds(intervals: Vector[PcSourceRange]): Vector[ClosedSourceLexicalKind] =
        intervals.flatMap: interval =>
          evidence.lexicalContract.atoms
            .filter(atom => interval.startOffset <= atom.start && atom.end <= interval.endOffset)
            .map(_.kind)

      def terminalLexicalContractSatisfied(
          terminal: TerminalDeclaration,
          intervals: Vector[PcSourceRange]
      ): Boolean = terminal.target match
        case TerminalLeafTarget.Trivia    =>
          val kinds = terminalLexicalKinds(intervals)
          kinds.nonEmpty && kinds.forall:
            case ClosedSourceLexicalKind.Whitespace | ClosedSourceLexicalKind.LineComment |
                ClosedSourceLexicalKind.BlockComment =>
              true
            case _ => false
        case TerminalLeafTarget.Separator =>
          val kinds = terminalLexicalKinds(intervals)
          kinds.nonEmpty && kinds.forall:
            case ClosedSourceLexicalKind.Whitespace | ClosedSourceLexicalKind.LineComment |
                ClosedSourceLexicalKind.BlockComment | ClosedSourceLexicalKind.Semicolon =>
              true
            case _ => false
        case _                            => true

      val knownEvidenceRoles  = (
        outputRows.valuesIterator.flatten.map(_._1.outputRoleId) ++
          active.iterator.flatMap(instance => selected(instance).terminals.map(_.outputRoleId))
      ).toSet
      val requestedBoundaries = Vector.newBuilder[(PsiOutputRoleId, Int)]
      outputRows.valuesIterator.flatten.foreach: (declaration, _, range) =>
        requestedBoundaries += declaration.outputRoleId -> range.startOffset
        requestedBoundaries += declaration.outputRoleId -> range.endOffset
      active.foreach: instance =>
        val production = selected(instance)
        production.terminals.foreach: terminal =>
          val intervals = terminalIntervals(instance, production, terminal)
          val ranges    = terminal.target match
            case _: TerminalLeafTarget.Token => terminalTokenRanges(instance, terminal, intervals)
            case _                           => intervals
          ranges.foreach: range =>
            requestedBoundaries += terminal.outputRoleId -> range.startOffset
            requestedBoundaries += terminal.outputRoleId -> range.endOffset

      val refinements     = requestedBoundaries
        .result()
        .flatMap: (role, offset) =>
          evidence.atoms.find(atom => atom.start < offset && offset < atom.end).map(atom => (atom, role, offset))
        .groupBy(_._1)
        .toVector
        .sortBy(_._1.id.toString)
        .map: (atom, requests) =>
          val boundaries = (Vector(atom.start, atom.end) ++ requests.map(_._3)).distinct.sorted
          SourceAtomRefinement(
            SourceAtomReference(atom.id, atom.start, atom.end),
            requests.map(_._2).distinct.sortBy(_.value),
            boundaries.sliding(2).collect { case Vector(start, end) => PcSourceRange(start, end) }.toVector
          )
      val refinedEvidence = SourceEvidenceRefinementPlanner
        .refine(evidence, knownEvidenceRoles, refinements)
        .fold(
          failures => break(Left(WholeFilePlanningFailure.SourceAtomRefinementFailures(failures))),
          identity
        )
      val planningAtoms   = refinedEvidence.atoms

      val ownershipChildren                                                                               = incoming.iterator
        .flatMap((child, owners) => owners.map(_ -> child))
        .toVector
        .groupMap(_._1)(_._2)
      val ownershipEntry                                                                                  = collection.mutable.Map.empty[ProductionInstanceId, Int]
      val ownershipExit                                                                                   = collection.mutable.Map.empty[ProductionInstanceId, Int]
      val ownershipParent                                                                                 = collection.mutable.Map.empty[ProductionInstanceId, ProductionInstanceId]
      val ownershipTraversal                                                                              = collection.mutable.Stack((root, false, Option.empty[ProductionInstanceId]))
      var ownershipOrder                                                                                  = 0
      while ownershipTraversal.nonEmpty do
        val (instance, exiting, parent) = ownershipTraversal.pop()
        if exiting then ownershipExit += instance -> ownershipOrder
        else
          parent.foreach(ownershipParent.update(instance, _))
          ownershipEntry += instance -> ownershipOrder
          ownershipOrder += 1
          ownershipTraversal.push((instance, true, parent))
          ownershipChildren
            .getOrElse(instance, Vector.empty)
            .reverseIterator
            .foreach(child => ownershipTraversal.push((child, false, Some(instance))))
      def isAncestor(ancestor: ProductionInstanceId, descendant: ProductionInstanceId): Boolean           =
        ownershipEntry
          .get(ancestor)
          .exists(entry =>
            ownershipEntry.get(descendant).exists(_ >= entry) &&
              ownershipExit.get(descendant).exists(_ <= ownershipExit(ancestor))
          )
      val activeClaimOwners                                                                               = active.toVector
        .groupMap(instance => instance.kind -> instance.valueId)(identity)
      val activeOrder                                                                                     = active.toVector.zipWithIndex.toMap
      def ancestorsIncluding(instance: ProductionInstanceId): Vector[ProductionInstanceId]                =
        val ancestors = Vector.newBuilder[ProductionInstanceId]
        var current   = Option(instance)
        while current.nonEmpty do
          ancestors += current.get
          current = ownershipParent.get(current.get)
        ancestors.result()
      val outputClaimOwners                                                                               = activeClaimOwners.view
        .mapValues(_.filter(instance => localOutputRoots(instance).nonEmpty))
        .toMap
      def claimOwners(atom: SourceAtom, owners: Map[(InventoryKind, Long), Vector[ProductionInstanceId]]) =
        atom.claims
          .flatMap:
            case SourceClaim.Node(id, _)       => owners.getOrElse(InventoryKind.Node -> id, Vector.empty)
            case SourceClaim.Positioned(id, _) => owners.getOrElse(InventoryKind.Positioned -> id, Vector.empty)
            case SourceClaim.Diagnostic(_)     => Vector.empty
          .distinct
          .sortBy(ownershipEntry)
      val deepestOutputClaimOwners                                                                        = planningAtoms
        .map: atom =>
          val owners  = claimOwners(atom, outputClaimOwners)
          val deepest = owners.zipWithIndex.collect:
            case (owner, index)
                if owners.lift(index + 1).forall(next => ownershipEntry(next) >= ownershipExit(owner)) =>
              owner
          atom.id -> deepest
        .toMap
      val eligibleParentClaimAtoms                                                                        = planningAtoms
        .flatMap: atom =>
          claimOwners(atom, activeClaimOwners)
            .filterNot(owner =>
              deepestOutputClaimOwners(atom.id)
                .exists(descendant => descendant != owner && isAncestor(owner, descendant))
            )
            .map(_ -> atom)
        .groupMap(_._1)(_._2)
      val unclaimedAtoms                                                                                  = planningAtoms.filter(atom => claimOwners(atom, activeClaimOwners).isEmpty)
      val sourceExtendedRanges                                                                            = outputRows.view
        .mapValues(_.collect:
          case (declaration, _, range)
              if declaration.range.isInstanceOf[OutputRangeDeclaration.CompilerPositionWithBodyLayoutOrEndMarker] ||
                declaration.range.isInstanceOf[OutputRangeDeclaration.CompilerPositionWithTrailingBalancedBrackets] ||
                declaration.range.isInstanceOf[OutputRangeDeclaration.BoundaryDerivedWithTrailingBalancedBrackets] ||
                (declaration.range match
                  case OutputRangeDeclaration.BoundaryDerived(start, end) =>
                    Set(start, end).exists:
                      case OutputBoundary.ParentProductionEnd | OutputBoundary.TemplateLayoutStart => true
                      case _                                                                       => false
                  case _                                                  => false
                ) =>
            range
        )
        .toMap
      val sourceExtendedAtoms                                                                             = sourceExtendedRanges.map: (instance, ranges) =>
        instance -> planningAtoms.filter: atom =>
          ranges.exists(range => range.startOffset <= atom.start && atom.end <= range.endOffset) &&
            (claimOwners(atom, activeClaimOwners) match
              case Vector() => true
              case owners   => owners.forall(owner => owner == instance || isAncestor(owner, instance))
            )
      val candidates                                                                                      =
        collection.mutable.Map.empty[SourceAtomId, Vector[PlannedPhysicalLeaf]].withDefaultValue(Vector.empty)
      val resolvedTerminals                                                                               = collection.mutable.LinkedHashSet.empty[(ProductionInstanceId, String)]
      active.foreach: instance =>
        val production = selected(instance)
        production.terminals.foreach: terminal =>
          val intervals   = terminalIntervals(instance, production, terminal)
          if !terminalLexicalContractSatisfied(terminal, intervals) then
            break(
              Left(
                WholeFilePlanningFailure.TerminalLexicalContractMismatch(
                  instance,
                  terminal.id,
                  terminal.target,
                  terminalLexicalKinds(intervals)
                )
              )
            )
          val tokenRanges = terminalTokenRanges(instance, terminal, intervals).toSet
          val atoms       = intervals.flatMap: interval =>
            val candidates = (terminal.target match
              case TerminalLeafTarget.Parent =>
                eligibleParentClaimAtoms.getOrElse(instance, Vector.empty) ++
                  sourceExtendedAtoms.getOrElse(instance, Vector.empty) ++
                  Option.when(instance == root)(unclaimedAtoms).getOrElse(Vector.empty)
              case _                         => planningAtoms
            ).distinct
            candidates
              .filter(atom => interval.startOffset <= atom.start && atom.end <= interval.endOffset)
              .filter(atom => terminal.target == TerminalLeafTarget.Parent || atom.claims.exists(claims(instance, _)))
              .filter: atom =>
                terminal.target match
                  case TerminalLeafTarget.Token(_, Some(_)) => tokenRanges(PcSourceRange(atom.start, atom.end))
                  case _                                    => true
          val occurrences = terminal.target match
            case _: TerminalLeafTarget.Token => atoms.size
            case _                           => intervals.size
          if !accepts(terminal.cardinality, occurrences) then
            break(
              Left(
                WholeFilePlanningFailure.TerminalCardinalityMismatch(
                  instance,
                  terminal.id,
                  terminal.cardinality,
                  occurrences
                )
              )
            )
          if atoms.nonEmpty then resolvedTerminals += instance -> terminal.id
          atoms.foreach: atom =>
            val owner = promotedOutputsByOrigin
              .getOrElse(instance, localOutputRoots(instance))
              .map(canonicalOutput)
              .distinct
              .find: root =>
                val range = compositeRanges(root)
                range.startOffset <= atom.start && atom.end <= range.endOffset
              .map(PhysicalLeafOwner.Composite(_))
              .getOrElse(PhysicalLeafOwner.FileRoot)
            val leaf  = PlannedPhysicalLeaf(
              atom.id,
              atom.start,
              atom.end,
              owner,
              instance,
              terminal.id,
              terminal.target
            )
            candidates.update(atom.id, candidates(atom.id) :+ leaf)
      val leaves                                                                                          = planningAtoms.map: atom =>
        val eligible = candidates(atom.id)
        eligible match
          case Vector(leaf) => leaf
          case Vector()     =>
            break(Left(WholeFilePlanningFailure.UnownedSourceAtom(atom.id, atom.start, atom.end)))
          case conflicts    =>
            val winner =
              conflicts.filter(candidate =>
                candidate.target != TerminalLeafTarget.Parent && conflicts.forall(other =>
                  other == candidate || other.target == TerminalLeafTarget.Parent
                )
              ) match
                case Vector(value) => Some(value)
                case _             =>
                  val byOwner = conflicts.groupBy(_.sourceOwner)
                  if byOwner.values.exists(_.size != 1) then None
                  else
                    conflicts.filter(candidate =>
                      conflicts.forall(other =>
                        other == candidate ||
                          (other.target == TerminalLeafTarget.Parent &&
                            isAncestor(other.sourceOwner, candidate.sourceOwner))
                      )
                    ) match
                      case Vector(value) => Some(value)
                      case _             => None
            winner.getOrElse(
              break(
                Left(
                  WholeFilePlanningFailure.ConflictingSourceAtomOwners(
                    atom.id,
                    atom.start,
                    atom.end,
                    conflicts.map(leaf => leaf.sourceOwner -> leaf.terminalId)
                  )
                )
              )
            )
      val atomOwnership                                                                                   = leaves.map: leaf =>
        val role = selected(leaf.sourceOwner).terminals
          .find(_.id == leaf.terminalId)
          .get
          .outputRoleId
        SourceAtomOwnership(
          SourceAtomReference(leaf.atomId, leaf.start, leaf.end),
          SourceEvidenceOwner(role, s"${leaf.sourceOwner}:${leaf.terminalId}")
        )
      val eventOwnership                                                                                  = refinedEvidence.structural.flatMap: event =>
        val sources    = event.claim match
          case SourceClaim.Node(id, _)       => activeClaimOwners.getOrElse(InventoryKind.Node -> id, Vector.empty)
          case SourceClaim.Positioned(id, _) =>
            activeClaimOwners.getOrElse(InventoryKind.Positioned -> id, Vector.empty)
          case SourceClaim.Diagnostic(_)     => Vector.empty
        val owners     =
          if sources.isEmpty then Vector(root)
          else
            ancestorsIncluding(sources.head)
              .filter(instance => sources.forall(source => source == instance || isAncestor(instance, source)))
              .sortBy(activeOrder)
        val candidates = owners.flatMap: instance =>
          val ownsClaim =
            if sources.isEmpty then instance == root
            else sources.forall(source => source == instance || isAncestor(instance, source))
          if !ownsClaim then Vector.empty
          else
            val terminals = selected(instance).terminals.collect:
              case terminal
                  if terminal.claimsStructuralEvidence &&
                    (sources.contains(instance) || terminal.target == TerminalLeafTarget.Parent) =>
                instance -> SourceEventOwnership(
                  event.id,
                  SourceEvidenceOwner(terminal.outputRoleId, s"$instance:${terminal.id}")
                )
            val wrappers  = outputRows
              .getOrElse(instance, Vector.empty)
              .collect:
                case (declaration, composite, _) if declaration.ownsStructuralEvidence =>
                  instance -> SourceEventOwnership(
                    event.id,
                    SourceEvidenceOwner(declaration.outputRoleId, s"$composite")
                  )
            terminals ++ wrappers
        candidates.collect:
          case (instance, ownership)
              if !candidates.exists((other, _) => other != instance && isAncestor(instance, other)) =>
            ownership
      val finalEvidence                                                                                   = FinalSourceEvidencePlanner
        .plan(refinedEvidence, knownEvidenceRoles, atomOwnership, eventOwnership)
        .fold(
          failures => break(Left(WholeFilePlanningFailure.FinalSourceEvidenceFailures(failures))),
          identity
        )
      val inactiveOutputs                                                                                 =
        mergedOutputRoots.keySet ++ suppressedChildRoots
      val targets                                                                                         = active.toVector.flatMap: instance =>
        val production = selected(instance)
        val composites = outputRows(instance).collect:
          case (declaration, id, _) if !inactiveOutputs(id) =>
            val requirement = declaration.targetRequirement match
              case TargetRequirement.Native          => TargetAssertionKind.NativeComposite
              case TargetRequirement.Compatible      => TargetAssertionKind.CompatibleComposite
              case TargetRequirement.NativeCandidate =>
                break(
                  Left(
                    WholeFilePlanningFailure.UnprobedNativeCandidate(
                      instance,
                      production.id,
                      declaration.outputRoleId
                    )
                  )
                )
            PlannedTargetAssertion(
              TargetAssertionOwner.Composite(id),
              PlannedTargetIdentity.OutputRole(declaration.outputRoleId),
              requirement
            )
        val terminals  = production.terminals.collect:
          case TerminalDeclaration(id, _, TerminalLeafTarget.Token(surfaceId, _), _, outputRoleId, _)
              if resolvedTerminals(instance -> id) =>
            PlannedTargetAssertion(
              TargetAssertionOwner.Terminal(instance, id),
              PlannedTargetIdentity.TokenRole(outputRoleId, surfaceId),
              TargetAssertionKind.Token
            )
        composites ++ terminals
      val accessors                                                                                       = active.toVector.flatMap(instance =>
        outputRows(instance).flatMap:
          case (_, id, _) if inactiveOutputs(id) => Vector.empty
          case (declaration, id, _)              =>
            declaration.accessors.map(obligation =>
              PlannedAccessorAssertion(id, obligation.surfaceId, obligation.required, obligation.surfaceKind)
            )
      )
      val stubs                                                                                           = active.toVector.flatMap: instance =>
        outputRows(instance).flatMap:
          case (_, id, _) if inactiveOutputs(id) => Vector.empty
          case (declaration, id, _)              =>
            declaration.persistence match
              case PersistenceObligations.NotApplicable                                   => Vector.empty
              case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
                Vector(PlannedStubAssertion(id, stub, serializer, indices, navigation))
      val navigation                                                                                      = active.toVector.flatMap: instance =>
        outputRows(instance).flatMap:
          case (_, id, _) if inactiveOutputs(id) => Vector.empty
          case (declaration, id, _)              =>
            declaration.navigation.map(PlannedNavigationAssertion(id, _))
      Right(
        WholeFileProductionPlan(
          snapshot.sourceUri,
          snapshot.sourceDigest,
          evidence.parserEvidenceFingerprint,
          finalEvidence.evidence.lexicalContract,
          leaves,
          finalEvidence.eventOwnership.map(ownership =>
            PlannedStructuralEvidenceOwnership(ownership.eventId, ownership.owner)
          ),
          Vector.empty,
          composites,
          targets,
          accessors,
          stubs,
          navigation
        )
      )

  private def claims(instance: ProductionInstanceId, claim: SourceClaim): Boolean = (instance.kind, claim) match
    case (InventoryKind.Node, SourceClaim.Node(id, _))             => instance.valueId == id
    case (InventoryKind.Positioned, SourceClaim.Positioned(id, _)) => instance.valueId == id
    case _                                                         => false

  private def accepts(cardinality: ChildCardinality, actual: Int): Boolean = cardinality match
    case ChildCardinality.ExactlyOne                 => actual == 1
    case ChildCardinality.Optional                   => actual <= 1
    case ChildCardinality.Repeated(minimum, maximum) => actual >= minimum && maximum.forall(actual <= _)
    case ChildCardinality.Grouped(minimum, maximum)  => actual >= minimum && maximum.forall(actual <= _)

  private def accepts(cardinality: OccurrenceCardinality, actual: Int): Boolean = cardinality match
    case OccurrenceCardinality.ExactlyOne                 => actual == 1
    case OccurrenceCardinality.Optional                   => actual <= 1
    case OccurrenceCardinality.Repeated(minimum, maximum) => actual >= minimum && maximum.forall(actual <= _)
