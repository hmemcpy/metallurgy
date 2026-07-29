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
  case Node, Positioned
private[metallurgy] enum SourceClassification:
  case SourceReachable, Synthetic, Absent
private[metallurgy] enum CatalogPathSegment:
  case NamedField(name: String)
  case Optional
  case RepeatedElement
  case NestedProduct(production: String)
private[metallurgy] final case class InventoryContext(
    ownerKind: InventoryKind,
    ownerPrefix: String,
    path: Vector[CatalogPathSegment]
)

private[metallurgy] enum CatalogValuePattern:
  case Node
  case Positioned
  case Optional(value: CatalogValuePattern)
  case Repeated(element: CatalogValuePattern)
  case Product(prefix: String, fields: Vector[CompilerFieldPattern])
  case Name, GeneratedName
  case Scalar(kind: String)
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
private[metallurgy] final case class CompilerProductionContextPattern(
    context: ContextPattern,
    sourceClassification: SourceClassification
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
private[metallurgy] final case class CompilerShapeInventoryRow(
    kind: InventoryKind,
    id: Long,
    prefix: String,
    patternFields: Vector[CompilerFieldPattern],
    observation: Vector[InventoryFieldObservation],
    contexts: Vector[InventoryContext],
    sourceClassification: SourceClassification
)
private[metallurgy] final case class CompilerRuntimeInventory(
    identity: CompilerRuntimeIdentity,
    parserEvidenceFingerprint: String,
    shapes: Vector[CompilerShapeInventoryRow]
)
private[metallurgy] final case class CompilerProductionContext(
    context: Option[InventoryContext],
    sourceClassification: SourceClassification
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
    productions: Vector[AggregatedCompilerProductionRow]
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
    e.tag(2)
    writeIdentity(inventory.identity, e)
    e.sequence(inventory.sourceEvidenceFingerprints)(e.string)
    e.sequence(inventory.productions)(writeRow(_, e))
    e.result()

  private def aggregateMatching(
      identity: CompilerRuntimeIdentity,
      inventories: Vector[CompilerRuntimeInventory]
  ): Either[InventoryAggregationFailure, AggregatedCompilerProductionInventory] =
    boundary:
      val grouped = inventories.flatMap(_.shapes).groupBy(row => row.kind -> row.prefix)
      val rows    = Vector.newBuilder[AggregatedCompilerProductionRow]
      val ordered = grouped.toVector.sortBy((key, _) => (key._1.ordinal, key._2))
      ordered.foreach:
        case ((kind, prefix), observations) =>
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
            infer(
              fieldRows.map(_(index)),
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
                contexts.map(CompilerProductionContext(_, row.sourceClassification))
            )(writeProductionContext)
          )
      Right(
        AggregatedCompilerProductionInventory(
          identity,
          inventories.map(_.parserEvidenceFingerprint).distinct.sorted,
          rows.result()
        )
      )

  private def infer(
      fields: Vector[InventoryFieldObservation],
      path: Vector[CatalogPathSegment]
  ): Either[InventoryAggregationFailure, CatalogValuePattern] =
    val observations                                                                                    = fields.map(_.value)
    val declarations                                                                                    = fields.flatMap(_.declaredPattern).distinct
    def validate(result: CatalogValuePattern): Either[InventoryAggregationFailure, CatalogValuePattern] =
      if declarations.forall(declaration =>
          declaration == result ||
            (declaration == CatalogValuePattern.Name && result == CatalogValuePattern.GeneratedName)
        )
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
              case Vector(value: CatalogValuePattern.Optional) => Right(value)
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
              case Vector(value: CatalogValuePattern.Repeated) => Right(value)
              case Vector()                                    => Left(InventoryAggregationFailure.UnresolvedShape(path))
              case _                                           => incompatible(path)
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
        if observations.forall(value =>
            value.isInstanceOf[InventoryValueObservation.Name] ||
              value.isInstanceOf[InventoryValueObservation.GeneratedName]
          )
        then
          val result =
            if observations.forall(_.isInstanceOf[InventoryValueObservation.GeneratedName]) then
              CatalogValuePattern.GeneratedName
            else CatalogValuePattern.Name
          validate(result)
        else incompatible(path)
      case Some(InventoryValueObservation.Scalar(value))                                        =>
        val kind = value.productPrefix
        if observations.forall {
            case InventoryValueObservation.Scalar(candidate) => candidate.productPrefix == kind
            case _                                           => false
          }
        then validate(CatalogValuePattern.Scalar(kind))
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

  private def writeProductionContext(context: CompilerProductionContext, e: CanonicalByteEncoder): Unit =
    writeOptionalContext(context.context, e); e.tag(context.sourceClassification.ordinal)

  private def writeField(field: CompilerFieldPattern, e: CanonicalByteEncoder): Unit =
    e.string(field.name); writePattern(field.value, e)

  private def writePattern(value: CatalogValuePattern, e: CanonicalByteEncoder): Unit = value match
    case CatalogValuePattern.Node                    => e.tag(1)
    case CatalogValuePattern.Positioned              => e.tag(2)
    case CatalogValuePattern.Optional(inner)         => e.tag(3); writePattern(inner, e)
    case CatalogValuePattern.Repeated(inner)         => e.tag(4); writePattern(inner, e)
    case CatalogValuePattern.Product(prefix, fields) => e.tag(5); e.string(prefix); e.sequence(fields)(writeField(_, e))
    case CatalogValuePattern.Name                    => e.tag(6)
    case CatalogValuePattern.GeneratedName           => e.tag(7)
    case CatalogValuePattern.Scalar(kind)            => e.tag(8); e.string(kind)
    case CatalogValuePattern.Unsupported(runtime)    => e.tag(9); e.string(runtime)

  private def writeContext(context: InventoryContext, e: CanonicalByteEncoder): Unit =
    e.tag(context.ownerKind.ordinal); e.string(context.ownerPrefix); e.sequence(context.path)(writePath(_, e))

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
    val failures                                                                     = Vector.newBuilder[InventoryFailure]
    duplicate(snapshot.nodes.map(_.id)).foreach(id =>
      failures += InventoryFailure.DuplicateIdentity(InventoryKind.Node, id)
    )
    duplicate(snapshot.positioned.map(_.id)).foreach(id =>
      failures += InventoryFailure.DuplicateIdentity(InventoryKind.Positioned, id)
    )
    val nodes                                                                        = snapshot.nodes.groupBy(_.id).collect { case (id, Vector(node)) => id -> node }
    val positioned                                                                   = snapshot.positioned.groupBy(_.id).collect { case (id, Vector(value)) => id -> value }
    if !nodes.contains(snapshot.rootNodeId) then failures += InventoryFailure.InvalidRoot(snapshot.rootNodeId)
    def references(
        value: ParserFieldValue,
        path: Vector[ParserFieldPathSegment]
    ): Vector[(InventoryKind, Long, Vector[ParserFieldPathSegment])] =
      value match
        case ParserFieldValue.Node(id)                => Vector((InventoryKind.Node, id, path))
        case ParserFieldValue.Positioned(id)          => Vector((InventoryKind.Positioned, id, path))
        case ParserFieldValue.Optional(value)         =>
          value.toVector.flatMap(references(_, path :+ ParserFieldPathSegment.OptionalNesting))
        case ParserFieldValue.Repeated(values)        =>
          values.zipWithIndex.flatMap((v, i) => references(v, path :+ ParserFieldPathSegment.RepeatedIndex(i)))
        case ParserFieldValue.Product(prefix, fields) =>
          fields.flatMap(f =>
            references(
              f.value,
              path :+ ParserFieldPathSegment.NestedProductBoundary(prefix) :+ ParserFieldPathSegment.NamedField(f.name)
            )
          )
        case _                                        => Vector.empty
    def outgoing(fields: Vector[ParserSyntaxField])                                  =
      fields.flatMap(field => references(field.value, Vector(ParserFieldPathSegment.NamedField(field.name))))
    val graph                                                                        = snapshot.nodes.map(node => (InventoryKind.Node, node.id) -> outgoing(node.fields)) ++
      snapshot.positioned.map(value => (InventoryKind.Positioned, value.id) -> outgoing(value.fields))
    val edges                                                                        = graph.toMap
    val expectedOccurrences                                                          = snapshot.nodes.flatMap(node =>
      outgoing(node.fields).map { case (kind, id, path) =>
        (kind, id, InventoryKind.Node, node.id, path)
      }
    ) ++ snapshot.positioned.flatMap(value =>
      value.occurrences.headOption.toVector.flatMap(origin =>
        outgoing(value.fields).map { case (kind, id, path) =>
          (kind, id, InventoryKind.Node, origin.ownerNodeId, origin.fieldPath ++ path)
        }
      )
    )
    val actualOccurrences                                                            = snapshot.nodes.flatMap(n =>
      n.occurrences.map(o => (InventoryKind.Node, n.id, InventoryKind.Node, o.ownerNodeId, o.fieldPath))
    ) ++ snapshot.positioned.flatMap(n =>
      n.occurrences.map(o => (InventoryKind.Positioned, n.id, InventoryKind.Node, o.ownerNodeId, o.fieldPath))
    )
    def counts[A](values: Vector[A]): Map[A, Int]                                    = values.groupMapReduce(identity)(_ => 1)(_ + _)
    val expectedCounts                                                               = counts(expectedOccurrences.toVector)
    val actualCounts                                                                 = counts(actualOccurrences)
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
    val reachable                                                                    = scala.collection.mutable.Set.empty[(InventoryKind, Long)]
    val pending                                                                      = scala.collection.mutable.Stack[(InventoryKind, Long)](InventoryKind.Node -> snapshot.rootNodeId)
    while pending.nonEmpty do
      val current = pending.pop()
      if !reachable(current) then
        reachable += current
        edges.getOrElse(current, Vector.empty).foreach((kind, id, _) => pending.push(kind -> id))
    edges.keys.filterNot(reachable).foreach((kind, id) => failures += InventoryFailure.UnreachableValue(kind, id))
    val visitState                                                                   = scala.collection.mutable.Map.empty[(InventoryKind, Long), Int]
    val traversal                                                                    = scala.collection.mutable.Stack.empty[((InventoryKind, Long), Boolean)]
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
    def normalized(path: Vector[ParserFieldPathSegment]): Vector[CatalogPathSegment] = path.map:
      case ParserFieldPathSegment.NamedField(name)                  => CatalogPathSegment.NamedField(name)
      case ParserFieldPathSegment.OptionalNesting                   => CatalogPathSegment.Optional
      case ParserFieldPathSegment.RepeatedIndex(_)                  => CatalogPathSegment.RepeatedElement
      case ParserFieldPathSegment.NestedProductBoundary(production) => CatalogPathSegment.NestedProduct(production)
    def context(
        kind: InventoryKind,
        id: Long,
        occurrence: (Long, Vector[ParserFieldPathSegment])
    ): Option[InventoryContext] =
      nodes.get(occurrence._1) match
        case Some(owner) => Some(InventoryContext(InventoryKind.Node, owner.production, normalized(occurrence._2)))
        case None        => failures += InventoryFailure.UnknownOccurrenceOwner(kind, id, occurrence._1, occurrence._2); None
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
    def declaredPattern(shape: ParserDeclaredShape): CatalogValuePattern             = shape match
      case ParserDeclaredShape.Node            => CatalogValuePattern.Node
      case ParserDeclaredShape.Positioned      => CatalogValuePattern.Positioned
      case ParserDeclaredShape.Optional(inner) => CatalogValuePattern.Optional(declaredPattern(inner))
      case ParserDeclaredShape.Repeated(inner) => CatalogValuePattern.Repeated(declaredPattern(inner))
      case ParserDeclaredShape.Name            => CatalogValuePattern.Name
      case ParserDeclaredShape.Scalar(kind)    => CatalogValuePattern.Scalar(kind)
    def pattern(value: InventoryValueObservation): CatalogValuePattern               = value match
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
      case InventoryValueObservation.Name(_)                 => CatalogValuePattern.Name
      case InventoryValueObservation.GeneratedName(_, _, _)  => CatalogValuePattern.GeneratedName
      case InventoryValueObservation.Scalar(value)           => CatalogValuePattern.Scalar(value.productPrefix)
      case InventoryValueObservation.Unsupported(value)      => CatalogValuePattern.Unsupported(value)
    val rows                                                                         = (snapshot.nodes.map(n =>
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
      )).map: (kind, id, prefix, fields, position, occurrences) =>
      val observed       = fields.map(f =>
        InventoryFieldObservation(
          f.name,
          observe(f.value, kind, id, Vector(ParserFieldPathSegment.NamedField(f.name))),
          f.declaredShape.map(declaredPattern)
        )
      )
      val classification = position match
        case ParserNodePosition.Absent                                                   => SourceClassification.Absent
        case ParserNodePosition.Positioned(_, _, ParserPositionProvenance.SourceDerived) =>
          SourceClassification.SourceReachable
        case _                                                                           => SourceClassification.Synthetic
      CompilerShapeInventoryRow(
        kind,
        id,
        prefix,
        observed.map(f => CompilerFieldPattern(f.name, f.declaredPattern.getOrElse(pattern(f.value)))),
        observed,
        occurrences.flatMap(context(kind, id, _)).distinct,
        classification
      )
    val found                                                                        = failures.result()
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
          rows
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
    val ownedTargets = catalog.productions
      .filter(_.targetRequirement == TargetRequirement.Compatible)
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
    copy(rows = rows ++ ownedTargets)

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
private[metallurgy] enum ChildCardinality:
  case ExactlyOne, Optional
  case Repeated(minimum: Int, maximum: Option[Int])
  case Grouped(minimum: Int, maximum: Option[Int])
private[metallurgy] enum ChildPlacement:
  case Direct
  case Wrapped(path: Vector[String])
  case Before(path: Vector[String])
  case After(path: Vector[String])
private[metallurgy] final case class ChildDeclaration(
    roleId: String,
    fieldName: String,
    cardinality: ChildCardinality,
    placement: ChildPlacement,
    productionId: String
)
private[metallurgy] enum TerminalIntervalSelector:
  case FieldBounds(startField: String, endField: String)
  case WholeProduction, WholeSource
private[metallurgy] enum TerminalLeafTarget:
  case Token(surfaceId: String)
  case Trivia, Delimiter, Separator, Parent
private[metallurgy] enum OccurrenceCardinality:
  case ExactlyOne, Optional
  case Repeated(minimum: Int, maximum: Option[Int])
private[metallurgy] final case class TerminalDeclaration(
    id: String,
    selector: TerminalIntervalSelector,
    target: TerminalLeafTarget,
    cardinality: OccurrenceCardinality
)
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
private[metallurgy] final case class Scala3PsiProduction(
    id: String,
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
    navigation: Option[NavigationObligation] = None
)
private[metallurgy] final case class Scala3PsiProductionCatalog(productions: Vector[Scala3PsiProduction])
private[metallurgy] enum CatalogCapabilityFailure:
  case MissingProduction(id: String)
  case InvalidTargetRequirement(id: String, requirement: TargetRequirement)
  case IntegerLiteralTargetsUnavailable(
      native: Either[IntegerLiteralProbeFailure, Vector[NativeIntegerLiteralObservation]],
      compatible: Either[IntegerLiteralProbeFailure, Vector[NativeIntegerLiteralObservation]]
  )
private[metallurgy] object Scala3PsiProductionCatalog:
  val Empty: Scala3PsiProductionCatalog = Scala3PsiProductionCatalog(Vector.empty)

  val Reviewed: Scala3PsiProductionCatalog = Scala3PsiProductionCatalog(
    Vector(
      Scala3PsiProduction(
        id = "integer-literal-number",
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
            OccurrenceCardinality.ExactlyOne
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
        navigation = Some(NavigationObligation.Self)
      )
    )
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
    lines += "## Compiler productions"
    lines += ""
    compiler.productions.foreach: row =>
      val fields = row.fields.map(field => s"${field.name}:${render(field.value)}").mkString(", ")
      lines += s"### `${row.kind}.${row.prefix}`"
      lines += ""
      lines += s"- Fields: `$fields`"
      row.occurrences.foreach: occurrence =>
        val selected = CatalogShapeMatcher.selectAggregated(catalog, row, occurrence)
        val status   = selected match
          case Vector(production) => s"shape-mapped:${production.targetRequirement}:${production.id}"
          case Vector()           => s"unmapped:${occurrence.sourceClassification}"
          case productions        => s"ambiguous:${productions.map(_.id).sorted.mkString(",")}"
        lines += s"- `${render(occurrence)}` — **$status**"
      lines += ""
    lines += "## Scala PSI surfaces"
    lines += ""
    val references        = catalog.productions
      .flatMap: production =>
        val terminals   = production.terminals.collect:
          case TerminalDeclaration(_, _, TerminalLeafTarget.Token(id), _) => id
        val persistence = production.persistence match
          case PersistenceObligations.NotApplicable                                   => Vector.empty
          case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
            Vector(stub, serializer, navigation) ++ indices
        (Vector(production.targetSurfaceId) ++ production.accessors.map(_.surfaceId) ++ terminals ++ persistence)
          .map(_ -> production.id)
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.distinct.sorted)
      .toMap
    effectiveSurfaces.rows.foreach: row =>
      val status = references.get(row.id) match
        case Some(productions) => s"catalog-referenced:${productions.mkString(",")}"
        case None              => s"unmapped:${row.classification}"
      lines += s"- `${row.kind}:${row.id}` — **${row.status}:$status**"
    lines.result().mkString("\n") + "\n"

  private def render(occurrence: CompilerProductionContext): String =
    val context = occurrence.context match
      case None        => "root"
      case Some(value) =>
        val path = value.path.map:
          case CatalogPathSegment.NamedField(name)        => name
          case CatalogPathSegment.Optional                => "?"
          case CatalogPathSegment.RepeatedElement         => "*"
          case CatalogPathSegment.NestedProduct(producer) => s"product($producer)"
        s"${value.ownerKind}.${value.ownerPrefix}/${path.mkString("/")}"
    s"$context:${occurrence.sourceClassification}"

  private def render(pattern: CatalogValuePattern): String = pattern match
    case CatalogValuePattern.Node                     => "Node"
    case CatalogValuePattern.Positioned               => "Positioned"
    case CatalogValuePattern.Optional(value)          => s"Optional[${render(value)}]"
    case CatalogValuePattern.Repeated(value)          => s"Repeated[${render(value)}]"
    case CatalogValuePattern.Product(prefix, fields)  =>
      s"$prefix(${fields.map(field => s"${field.name}:${render(field.value)}").mkString(",")})"
    case CatalogValuePattern.Name                     => "Name"
    case CatalogValuePattern.GeneratedName            => "GeneratedName"
    case CatalogValuePattern.Scalar(kind)             => s"Scalar[$kind]"
    case CatalogValuePattern.Unsupported(runtimeType) => s"Unsupported[$runtimeType]"

private[metallurgy] object CatalogShapeMatcher:
  def matches(pattern: CatalogValuePattern, observation: InventoryValueObservation): Boolean =
    (pattern, observation) match
      case (CatalogValuePattern.Node, InventoryValueObservation.Node(_, _))                                   => true
      case (CatalogValuePattern.Positioned, InventoryValueObservation.Positioned(_, _))                       => true
      case (CatalogValuePattern.Optional(expected), InventoryValueObservation.Optional(Some(value)))          =>
        matches(expected, value)
      case (CatalogValuePattern.Optional(_), InventoryValueObservation.Optional(None))                        => true
      case (CatalogValuePattern.Repeated(expected), InventoryValueObservation.Repeated(values))               =>
        values.forall(matches(expected, _))
      case (CatalogValuePattern.Product(prefix, expected), InventoryValueObservation.Product(actual, fields)) =>
        prefix == actual && matchesFields(expected, fields)
      case (
            CatalogValuePattern.Name,
            _: InventoryValueObservation.Name | _: InventoryValueObservation.GeneratedName
          ) =>
        true
      case (CatalogValuePattern.GeneratedName, InventoryValueObservation.GeneratedName(_, _, _))              => true
      case (CatalogValuePattern.Scalar(kind), InventoryValueObservation.Scalar(value))                        =>
        kind == value.productPrefix
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
      case (CatalogValuePattern.Name, CatalogValuePattern.Name | CatalogValuePattern.GeneratedName)   => true
      case (CatalogValuePattern.Optional(expectedValue), CatalogValuePattern.Optional(observedValue)) =>
        covers(expectedValue, observedValue)
      case (CatalogValuePattern.Repeated(expectedValue), CatalogValuePattern.Repeated(observedValue)) =>
        covers(expectedValue, observedValue)
      case (
            CatalogValuePattern.Product(expectedPrefix, expectedFields),
            CatalogValuePattern.Product(observedPrefix, observedFields)
          ) =>
        expectedPrefix == observedPrefix && coversFields(expectedFields, observedFields)
      case _                                                                                          => expected == observed

  def coversFields(
      expected: Vector[CompilerFieldPattern],
      observed: Vector[CompilerFieldPattern]
  ): Boolean =
    expected.size == observed.size && expected
      .zip(observed)
      .forall: (catalogField, compilerField) =>
        catalogField.name == compilerField.name && covers(catalogField.value, compilerField.value)

  def contextMatches(pattern: ContextPattern, context: Option[InventoryContext]): Boolean = pattern match
    case ContextPattern.Any                    => true
    case ContextPattern.Root                   => context.isEmpty
    case ContextPattern.Parent(kind, owner, p) => context.contains(InventoryContext(kind, owner, p))

  def aggregateContextMatches(pattern: ContextPattern, context: Option[InventoryContext]): Boolean = pattern match
    case ContextPattern.Any                    => false
    case ContextPattern.Root                   => context.isEmpty
    case ContextPattern.Parent(kind, owner, p) => context.contains(InventoryContext(kind, owner, p))

  def select(
      catalog: Scala3PsiProductionCatalog,
      kind: InventoryKind,
      prefix: String,
      fields: Vector[InventoryFieldObservation],
      context: Option[InventoryContext],
      sourceClassification: SourceClassification
  ): Vector[Scala3PsiProduction] =
    catalog.productions.filter(p =>
      p.pattern.kind == kind && p.pattern.prefix == prefix && matchesFields(p.pattern.fields, fields) &&
        p.pattern.occurrences.exists(occurrence =>
          contextMatches(occurrence.context, context) && occurrence.sourceClassification == sourceClassification
        )
    )

  def selectAggregated(
      catalog: Scala3PsiProductionCatalog,
      row: AggregatedCompilerProductionRow,
      occurrence: CompilerProductionContext
  ): Vector[Scala3PsiProduction] =
    catalog.productions.filter(p =>
      p.pattern.kind == row.kind && p.pattern.prefix == row.prefix && coversFields(p.pattern.fields, row.fields) &&
        p.pattern.occurrences.exists(pattern =>
          aggregateContextMatches(pattern.context, occurrence.context) &&
            pattern.sourceClassification == occurrence.sourceClassification
        )
    )

private[metallurgy] enum CatalogValidationError:
  case DuplicateProductionId(id: String)
  case EmptyOccurrencePatterns(productionId: String)
  case DuplicateOccurrencePattern(productionId: String, pattern: CompilerProductionContextPattern)
  case DuplicateChildRoleId(productionId: String, roleId: String)
  case UnknownChildProductionId(productionId: String, childProductionId: String)
  case DuplicateTerminalId(productionId: String, terminalId: String)
  case DuplicateAccessorObligation(productionId: String, surfaceId: String)
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
  case InvalidSurface(productionId: String, surfaceId: String, expectedKind: SurfaceFactKind)
  case InvalidSurfaceOwner(productionId: String, surfaceId: String, expectedOwner: String)
  case IncompleteSurfaceStatus(productionId: String, surfaceId: String, status: FactStatus)
  case UnaccountedSyntaxSurface(surfaceId: String)
  case UnrepresentedCatalogProduction(productionId: String)
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
    val effectiveSurfaces                                                                                             = surfaces.withCatalogCapabilities(catalog)
    val errors                                                                                                        = Vector.newBuilder[CatalogValidationError]
    duplicates(catalog.productions.map(_.id)).foreach(id => errors += CatalogValidationError.DuplicateProductionId(id))
    val productionIds                                                                                                 = catalog.productions.map(_.id).toSet
    duplicates(effectiveSurfaces.rows.map(_.id)).foreach(id => errors += CatalogValidationError.DuplicateSurfaceId(id))
    effectiveSurfaces.rows
      .filter(_.classification == SurfaceClassification.Unclassified)
      .foreach(r => errors += CatalogValidationError.UnclassifiedSurface(r.id))
    effectiveSurfaces.rows
      .filter(_.status != FactStatus.Available)
      .foreach: row =>
        errors += CatalogValidationError.UnresolvedSurface(row.id, row.status)
    val surfaceMap                                                                                                    = effectiveSurfaces.rows.groupBy(_.id).collect { case (id, Vector(row)) => id -> row }
    def requireSurface(p: Scala3PsiProduction, id: String, kind: SurfaceFactKind, owner: Option[String] = None): Unit =
      surfaceMap.get(id) match
        case None                                                                 => errors += CatalogValidationError.InvalidSurface(p.id, id, kind)
        case Some(row) if row.kind != kind                                        => errors += CatalogValidationError.InvalidSurface(p.id, id, kind)
        case Some(row) if owner.exists(expected => row.ownerId != Some(expected)) =>
          errors += CatalogValidationError.InvalidSurfaceOwner(p.id, id, owner.get)
        case Some(row) if row.status != FactStatus.Available                      =>
          errors += CatalogValidationError.IncompleteSurfaceStatus(p.id, id, row.status)
        case _                                                                    => ()
    catalog.productions.foreach: p =>
      val names = p.pattern.fields.map(_.name)
      if p.pattern.occurrences.isEmpty then errors += CatalogValidationError.EmptyOccurrencePatterns(p.id)
      duplicates(p.pattern.occurrences)
        .foreach(pattern => errors += CatalogValidationError.DuplicateOccurrencePattern(p.id, pattern))
      duplicates(p.children.map(_.roleId))
        .foreach(role => errors += CatalogValidationError.DuplicateChildRoleId(p.id, role))
      p.children
        .filterNot(child => productionIds(child.productionId))
        .foreach(child => errors += CatalogValidationError.UnknownChildProductionId(p.id, child.productionId))
      p.children
        .filter(child => !valid(child.cardinality))
        .foreach(child => errors += CatalogValidationError.InvalidChildCardinality(p.id, child.roleId))
      duplicates(p.terminals.map(_.id))
        .foreach(id => errors += CatalogValidationError.DuplicateTerminalId(p.id, id))
      p.terminals
        .filter(terminal => !valid(terminal.cardinality))
        .foreach(terminal => errors += CatalogValidationError.InvalidTerminalCardinality(p.id, terminal.id))
      duplicates(p.accessors.map(_.surfaceId))
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
          else if children > 1 then errors += CatalogValidationError.DuplicateChildDeclaration(p.id, name)
        else if children > 0 then errors += CatalogValidationError.ChildDeclarationForNonChildField(p.id, name)
        if disposition.size == 1 && disposition.head.kind == FieldDispositionKind.TerminalOrLayout then
          val declared = p.terminals.exists(_.selector match
            case TerminalIntervalSelector.WholeProduction | TerminalIntervalSelector.WholeSource => true
            case TerminalIntervalSelector.FieldBounds(a, b)                                      => a == name || b == name
          )
          if !declared then errors += CatalogValidationError.MissingTerminalDeclaration(p.id, name)
      p.terminals.foreach(_.selector match
        case TerminalIntervalSelector.FieldBounds(a, b) =>
          Vector(a, b)
            .filterNot(names.contains)
            .foreach(n => errors += CatalogValidationError.UnknownTerminalField(p.id, n))
        case _                                          => ()
      )
      p.terminals.foreach:
        case TerminalDeclaration(_, _, TerminalLeafTarget.Token(id), _) =>
          requireSurface(p, id, SurfaceFactKind.Token)
        case _                                                          => ()
      requireSurface(p, p.targetSurfaceId, SurfaceFactKind.Element)
      p.accessors.foreach(a => requireSurface(p, a.surfaceId, a.surfaceKind))
      p.persistence match
        case PersistenceObligations.NotApplicable                                   => ()
        case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
          requireSurface(p, stub, SurfaceFactKind.Stub);
          requireSurface(p, serializer, SurfaceFactKind.Serializer);
          indices.foreach(requireSurface(p, _, SurfaceFactKind.Index));
          requireSurface(p, navigation, SurfaceFactKind.Navigation)
    errors ++= coverage
    val accounted                                                                                                     = catalog.productions
      .flatMap: p =>
        val terminals   = p.terminals.collect { case TerminalDeclaration(_, _, TerminalLeafTarget.Token(id), _) => id }
        val persistence = p.persistence match
          case PersistenceObligations.NotApplicable                                   => Vector.empty
          case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
            Vector(stub, serializer, navigation) ++ indices
        Vector(p.targetSurfaceId) ++ p.accessors.map(_.surfaceId) ++ terminals ++ persistence
      .toSet
    if includeUnaccountedSurfaces then
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
    compiler.shapes.flatMap: shape =>
      val contexts = if shape.contexts.isEmpty then Vector(None) else shape.contexts.map(Some(_))
      for
        context <- contexts
        selected = CatalogShapeMatcher.select(
                     catalog,
                     shape.kind,
                     shape.prefix,
                     shape.observation,
                     context,
                     shape.sourceClassification
                   )
        error   <- coverageError(shape.kind, shape.prefix, context, shape.sourceClassification, selected)
      yield error

  private def aggregatedCoverage(
      catalog: Scala3PsiProductionCatalog,
      compiler: AggregatedCompilerProductionInventory
  ): Vector[CatalogValidationError] =
    val uncovered     = compiler.productions.flatMap: row =>
      row.occurrences.flatMap: occurrence =>
        coverageError(
          row.kind,
          row.prefix,
          occurrence.context,
          occurrence.sourceClassification,
          CatalogShapeMatcher.selectAggregated(catalog, row, occurrence)
        )
    val unrepresented = catalog.productions.collect:
      case production
          if production.pattern.occurrences.exists(pattern =>
            !compiler.productions.exists(row =>
              row.kind == production.pattern.kind && row.prefix == production.pattern.prefix &&
                CatalogShapeMatcher.coversFields(production.pattern.fields, row.fields) &&
                row.occurrences.exists(occurrence =>
                  CatalogShapeMatcher.aggregateContextMatches(pattern.context, occurrence.context) &&
                    pattern.sourceClassification == occurrence.sourceClassification
                )
            )
          ) =>
        CatalogValidationError.UnrepresentedCatalogProduction(production.id)
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
  case MissingRuntimeShape(kind: InventoryKind, id: Long)
  case UnsupportedChildPlacement(productionId: String, roleId: String, placement: ChildPlacement)
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
  case UnsupportedChildCardinality(productionId: String, roleId: String, cardinality: ChildCardinality)
  case UnsupportedTerminalSelector(productionId: String, terminalId: String, selector: TerminalIntervalSelector)
  case TerminalCardinalityMismatch(
      owner: ProductionInstanceId,
      terminalId: String,
      expected: OccurrenceCardinality,
      actual: Int
  )
  case UnownedSourceAtom(atomId: Long, start: Int, end: Int)
  case ConflictingSourceAtomOwners(
      atomId: Long,
      start: Int,
      end: Int,
      owners: Vector[(ProductionInstanceId, String)]
  )
  case UnsupportedLayout(owner: ProductionInstanceId, alternatives: Vector[LayoutAlternative])
  case UnsupportedRecovery(owner: ProductionInstanceId, policy: RecoveryPolicy)
  case UnprobedNativeCandidate(owner: ProductionInstanceId, productionId: String)
  case UnassignedDiagnostic(index: Int)

private[metallurgy] final case class ProductionOccurrenceId(
    ownerNodeId: Long,
    fieldPath: Vector[ParserFieldPathSegment]
)
private[metallurgy] final case class ProductionInstanceId(
    kind: InventoryKind,
    valueId: Long,
    occurrence: Option[ProductionOccurrenceId]
)
private[metallurgy] final case class PlannedPhysicalLeaf(
    atomId: Long,
    start: Int,
    end: Int,
    owner: ProductionInstanceId,
    terminalId: String,
    target: TerminalLeafTarget
)
private[metallurgy] final case class PlannedChild(
    roleId: String,
    fieldPath: Vector[ParserFieldPathSegment],
    child: ProductionInstanceId,
    placement: ChildPlacement
)
private[metallurgy] final case class PlannedComposite(
    instance: ProductionInstanceId,
    productionId: String,
    position: ParserNodePosition,
    children: Vector[PlannedChild],
    fieldDispositions: Vector[FieldDisposition]
)
private[metallurgy] enum TargetAssertionOwner:
  case Composite(instance: ProductionInstanceId)
  case Terminal(instance: ProductionInstanceId, terminalId: String)
private[metallurgy] enum TargetAssertionKind:
  case NativeComposite, CompatibleComposite
  case Token
private[metallurgy] final case class PlannedTargetAssertion(
    owner: TargetAssertionOwner,
    surfaceId: String,
    kind: TargetAssertionKind
)
private[metallurgy] final case class PlannedAccessorAssertion(
    owner: ProductionInstanceId,
    surfaceId: String,
    required: Boolean
)
private[metallurgy] final case class PlannedStubAssertion(
    owner: ProductionInstanceId,
    stubSurfaceId: String,
    serializerSurfaceId: String,
    indexSurfaceIds: Vector[String],
    navigationSurfaceId: String
)
private[metallurgy] final case class PlannedNavigationAssertion(
    owner: ProductionInstanceId,
    obligation: NavigationObligation
)
private[metallurgy] final case class PlannedVirtualLayout(
    owner: ProductionInstanceId,
    anchor: Int,
    ordinalAtAnchor: Int
)
private[metallurgy] final case class WholeFileProductionPlan(
    sourceUri: ParserSourceUri,
    sourceDigest: String,
    parserEvidenceFingerprint: String,
    physicalLeafOwnership: Vector[PlannedPhysicalLeaf],
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
    val errors = Scala3PsiProductionCatalogValidator.validateExecutable(catalog, compiler, surfaces)
    Either.cond(errors.isEmpty, new PreparedProductionCatalog(catalog, compiler, surfaces), errors)

private[metallurgy] object WholeFileProductionPlanner:
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
    boundary:
      val rows                                                                         = compiler.shapes.map(row => (row.kind, row.id) -> row).toMap
      val nodes                                                                        = snapshot.nodes.map(node => node.id -> node).toMap
      val positioned                                                                   = snapshot.positioned.map(value => value.id -> value).toMap
      def fields(instance: ProductionInstanceId): Vector[ParserSyntaxField]            = instance.kind match
        case InventoryKind.Node       => nodes(instance.valueId).fields
        case InventoryKind.Positioned => positioned(instance.valueId).fields
      def position(instance: ProductionInstanceId): ParserNodePosition                 = instance.kind match
        case InventoryKind.Node       => nodes(instance.valueId).position
        case InventoryKind.Positioned => positioned(instance.valueId).position
      def references(
          value: ParserFieldValue,
          path: Vector[ParserFieldPathSegment]
      ): Vector[(InventoryKind, Long, Vector[ParserFieldPathSegment])] = value match
        case ParserFieldValue.Node(id)                => Vector((InventoryKind.Node, id, path))
        case ParserFieldValue.Positioned(id)          => Vector((InventoryKind.Positioned, id, path))
        case ParserFieldValue.Optional(value)         =>
          value.toVector.flatMap(references(_, path :+ ParserFieldPathSegment.OptionalNesting))
        case ParserFieldValue.Repeated(values)        =>
          values.zipWithIndex.flatMap((candidate, index) =>
            references(candidate, path :+ ParserFieldPathSegment.RepeatedIndex(index))
          )
        case ParserFieldValue.Product(prefix, nested) =>
          nested.flatMap(field =>
            references(
              field.value,
              path :+ ParserFieldPathSegment.NestedProductBoundary(prefix) :+
                ParserFieldPathSegment.NamedField(field.name)
            )
          )
        case _                                        => Vector.empty
      def childOrigin(instance: ProductionInstanceId): ProductionOccurrenceId          = instance.kind match
        case InventoryKind.Node       => ProductionOccurrenceId(instance.valueId, Vector.empty)
        case InventoryKind.Positioned =>
          instance.occurrence.getOrElse(ProductionOccurrenceId(instance.valueId, Vector.empty))
      def childInstance(
          instance: ProductionInstanceId,
          kind: InventoryKind,
          id: Long,
          path: Vector[ParserFieldPathSegment]
      ): ProductionInstanceId =
        val origin = childOrigin(instance)
        ProductionInstanceId(kind, id, Some(ProductionOccurrenceId(origin.ownerNodeId, origin.fieldPath ++ path)))
      def children(instance: ProductionInstanceId): Vector[ProductionInstanceId]       =
        if instance.kind == InventoryKind.Positioned then Vector.empty
        else
          fields(instance).flatMap(field =>
            references(field.value, Vector(ParserFieldPathSegment.NamedField(field.name))).map: (kind, id, path) =>
              childInstance(instance, kind, id, path)
          )
      def normalized(path: Vector[ParserFieldPathSegment]): Vector[CatalogPathSegment] = path.map:
        case ParserFieldPathSegment.NamedField(name)                  => CatalogPathSegment.NamedField(name)
        case ParserFieldPathSegment.OptionalNesting                   => CatalogPathSegment.Optional
        case ParserFieldPathSegment.RepeatedIndex(_)                  => CatalogPathSegment.RepeatedElement
        case ParserFieldPathSegment.NestedProductBoundary(production) => CatalogPathSegment.NestedProduct(production)
      def context(instance: ProductionInstanceId): Option[InventoryContext]            = instance.occurrence.flatMap: occurrence =>
        nodes
          .get(occurrence.ownerNodeId)
          .map(owner => InventoryContext(InventoryKind.Node, owner.production, normalized(occurrence.fieldPath)))
      val root                                                                         = ProductionInstanceId(InventoryKind.Node, snapshot.rootNodeId, None)
      val instances                                                                    = Vector.newBuilder[ProductionInstanceId]
      val pending                                                                      = collection.mutable.Stack(root)
      val discovered                                                                   = collection.mutable.Set.empty[ProductionInstanceId]
      while pending.nonEmpty do
        val instance = pending.pop()
        if discovered.add(instance) then
          instances += instance
          children(instance).reverseIterator.foreach(pending.push)
      val ordered                                                                      = instances.result()
      val selected                                                                     = collection.mutable.LinkedHashMap.empty[ProductionInstanceId, Scala3PsiProduction]
      ordered.foreach: instance =>
        val row     = rows.getOrElse(
          instance.kind -> instance.valueId,
          break(Left(WholeFilePlanningFailure.MissingRuntimeShape(instance.kind, instance.valueId)))
        )
        val matches = CatalogShapeMatcher.select(
          catalog,
          row.kind,
          row.prefix,
          row.observation,
          context(instance),
          row.sourceClassification
        )
        matches match
          case Vector(production) => selected += instance -> production
          case Vector()           =>
            break(
              Left(
                WholeFilePlanningFailure.UnknownProduction(
                  row.kind,
                  row.prefix,
                  row.observation.map(_.name),
                  context(instance).map(_.ownerPrefix),
                  instance.occurrence.map(_.fieldPath).getOrElse(Vector.empty)
                )
              )
            )
          case many               =>
            break(
              Left(
                WholeFilePlanningFailure.AmbiguousProduction(
                  row.kind,
                  row.prefix,
                  many.map(_.id).sorted,
                  context(instance).map(_.ownerPrefix),
                  instance.occurrence.map(_.fieldPath).getOrElse(Vector.empty)
                )
              )
            )

      val active     = collection.mutable.LinkedHashSet(root)
      val incoming   = collection.mutable.LinkedHashMap.empty[ProductionInstanceId, Vector[ProductionInstanceId]]
      val composites = Vector.newBuilder[PlannedComposite]
      ordered.foreach: instance =>
        if active(instance) then
          val production      = selected(instance)
          if production.targetRequirement == TargetRequirement.NativeCandidate then
            break(Left(WholeFilePlanningFailure.UnprobedNativeCandidate(instance, production.id)))
          val plannedChildren = Vector.newBuilder[PlannedChild]
          production.dispositions.collectFirst:
            case FieldDisposition(fieldName, FieldDispositionKind.Unsupported) => fieldName
          match
            case Some(fieldName) =>
              break(Left(WholeFilePlanningFailure.UnsupportedFieldDisposition(instance, fieldName)))
            case None            => ()
          production.children.foreach: declaration =>
            if declaration.placement != ChildPlacement.Direct then
              break(
                Left(
                  WholeFilePlanningFailure.UnsupportedChildPlacement(
                    production.id,
                    declaration.roleId,
                    declaration.placement
                  )
                )
              )
            declaration.cardinality match
              case grouped: ChildCardinality.Grouped =>
                break(
                  Left(
                    WholeFilePlanningFailure.UnsupportedChildCardinality(
                      production.id,
                      declaration.roleId,
                      grouped
                    )
                  )
                )
              case _                                 => ()
            if instance.kind == InventoryKind.Positioned then
              break(Left(WholeFilePlanningFailure.UnsupportedPositionedChildren(instance)))
            val field = fields(instance).find(_.name == declaration.fieldName).toVector
            val found = field.flatMap(value =>
              references(value.value, Vector(ParserFieldPathSegment.NamedField(value.name))).map: (kind, id, path) =>
                childInstance(instance, kind, id, path) -> path
            )
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
              val actual = selected(child)
              if actual.id != declaration.productionId then
                break(
                  Left(
                    WholeFilePlanningFailure.ChildProductionMismatch(
                      instance,
                      declaration.roleId,
                      declaration.productionId,
                      actual.id,
                      child
                    )
                  )
                )
              val owners = incoming.getOrElse(child, Vector.empty) :+ instance
              incoming.update(child, owners)
              if owners.size > 1 then
                break(Left(WholeFilePlanningFailure.MultiplyConsumedChildReference(child, owners)))
              active += child
              plannedChildren += PlannedChild(declaration.roleId, path, child, declaration.placement)
          if production.layouts != Vector(LayoutAlternative.None) then
            break(Left(WholeFilePlanningFailure.UnsupportedLayout(instance, production.layouts)))
          if production.recovery != RecoveryPolicy.Reject then
            break(Left(WholeFilePlanningFailure.UnsupportedRecovery(instance, production.recovery)))
          composites += PlannedComposite(
            instance,
            production.id,
            position(instance),
            plannedChildren.result(),
            production.dispositions
          )
      if snapshot.diagnostics.nonEmpty then break(Left(WholeFilePlanningFailure.UnassignedDiagnostic(0)))

      val candidates                                                                            = collection.mutable.Map.empty[Long, Vector[PlannedPhysicalLeaf]].withDefaultValue(Vector.empty)
      val resolvedTerminals                                                                     = collection.mutable.LinkedHashSet.empty[(ProductionInstanceId, String)]
      active.foreach: instance =>
        val production = selected(instance)
        production.terminals.foreach: terminal =>
          terminal.selector match
            case TerminalIntervalSelector.WholeSource if instance != root                        =>
              break(
                Left(
                  WholeFilePlanningFailure.UnsupportedTerminalSelector(
                    production.id,
                    terminal.id,
                    terminal.selector
                  )
                )
              )
            case TerminalIntervalSelector.WholeProduction | TerminalIntervalSelector.WholeSource =>
              val intervals = position(instance) match
                case _ if terminal.selector == TerminalIntervalSelector.WholeSource =>
                  Vector(PcSourceRange(0, snapshot.sourceLength))
                case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived)
                    if range.startOffset < range.endOffset =>
                  Vector(range)
                case _                                                              => Vector.empty
              if !accepts(terminal.cardinality, intervals.size) then
                break(
                  Left(
                    WholeFilePlanningFailure.TerminalCardinalityMismatch(
                      instance,
                      terminal.id,
                      terminal.cardinality,
                      intervals.size
                    )
                  )
                )
              intervals.foreach: interval =>
                resolvedTerminals += instance -> terminal.id
                evidence.atoms
                  .filter(atom => interval.startOffset <= atom.start && atom.end <= interval.endOffset)
                  .filter(atom =>
                    terminal.target == TerminalLeafTarget.Parent || atom.claims.exists(claims(instance, _))
                  )
                  .foreach: atom =>
                    val leaf = PlannedPhysicalLeaf(
                      atom.id,
                      atom.start,
                      atom.end,
                      instance,
                      terminal.id,
                      terminal.target
                    )
                    candidates.update(atom.id, candidates(atom.id) :+ leaf)
            case other                                                                           =>
              break(Left(WholeFilePlanningFailure.UnsupportedTerminalSelector(production.id, terminal.id, other)))
      def isAncestor(ancestor: ProductionInstanceId, descendant: ProductionInstanceId): Boolean =
        Iterator
          .iterate(Vector(descendant))(_.flatMap(incoming.getOrElse(_, Vector.empty)))
          .takeWhile(_.nonEmpty)
          .flatten
          .contains(ancestor)
      val leaves                                                                                = evidence.atoms.map: atom =>
        val eligible = candidates(atom.id).filterNot(candidate =>
          candidate.target == TerminalLeafTarget.Parent && active.exists(descendant =>
            descendant != candidate.owner && isAncestor(candidate.owner, descendant) &&
              atom.claims.exists(claims(descendant, _))
          )
        )
        eligible match
          case Vector(leaf) => leaf
          case Vector()     =>
            break(Left(WholeFilePlanningFailure.UnownedSourceAtom(atom.id, atom.start, atom.end)))
          case conflicts    =>
            val byOwner = conflicts.groupBy(_.owner)
            val winner  =
              if byOwner.values.exists(_.size != 1) then None
              else
                conflicts.filter(candidate =>
                  conflicts.forall(other =>
                    other == candidate ||
                      (other.target == TerminalLeafTarget.Parent && isAncestor(other.owner, candidate.owner))
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
                    conflicts.map(leaf => leaf.owner -> leaf.terminalId)
                  )
                )
              )
            )
      val targets                                                                               = active.toVector.flatMap: instance =>
        val production  = selected(instance)
        val requirement = production.targetRequirement match
          case TargetRequirement.Native          => TargetAssertionKind.NativeComposite
          case TargetRequirement.Compatible      => TargetAssertionKind.CompatibleComposite
          case TargetRequirement.NativeCandidate =>
            break(Left(WholeFilePlanningFailure.UnprobedNativeCandidate(instance, production.id)))
        val composite   = PlannedTargetAssertion(
          TargetAssertionOwner.Composite(instance),
          production.targetSurfaceId,
          requirement
        )
        val terminals   = production.terminals.collect:
          case TerminalDeclaration(id, _, TerminalLeafTarget.Token(surfaceId), _)
              if resolvedTerminals(instance -> id) =>
            PlannedTargetAssertion(TargetAssertionOwner.Terminal(instance, id), surfaceId, TargetAssertionKind.Token)
        composite +: terminals
      val accessors                                                                             = active.toVector.flatMap(instance =>
        selected(instance).accessors.map(obligation =>
          PlannedAccessorAssertion(instance, obligation.surfaceId, obligation.required)
        )
      )
      val stubs                                                                                 = active.toVector.flatMap: instance =>
        selected(instance).persistence match
          case PersistenceObligations.NotApplicable                                   => Vector.empty
          case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
            Vector(PlannedStubAssertion(instance, stub, serializer, indices, navigation))
      val navigation                                                                            = active.toVector.flatMap: instance =>
        selected(instance).navigation.map(PlannedNavigationAssertion(instance, _))
      Right(
        WholeFileProductionPlan(
          snapshot.sourceUri,
          snapshot.sourceDigest,
          evidence.parserEvidenceFingerprint,
          leaves,
          Vector.empty,
          composites.result(),
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
