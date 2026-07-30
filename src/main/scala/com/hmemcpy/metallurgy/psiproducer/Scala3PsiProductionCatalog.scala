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
private[metallurgy] final case class InventoryAncestor(
    ownerKind: InventoryKind,
    ownerPrefix: String,
    path: Vector[CatalogPathSegment]
)
private[metallurgy] final case class InventoryContext(
    ownerKind: InventoryKind,
    ownerPrefix: String,
    path: Vector[CatalogPathSegment],
    ancestors: Vector[InventoryAncestor] = Vector.empty
)
private[metallurgy] object InventoryContextLineage:
  def normalized(path: Vector[ParserFieldPathSegment]): Vector[CatalogPathSegment] = path.map:
    case ParserFieldPathSegment.NamedField(name)                  => CatalogPathSegment.NamedField(name)
    case ParserFieldPathSegment.OptionalNesting                   => CatalogPathSegment.Optional
    case ParserFieldPathSegment.RepeatedIndex(_)                  => CatalogPathSegment.RepeatedElement
    case ParserFieldPathSegment.NestedProductBoundary(production) => CatalogPathSegment.NestedProduct(production)

  def contexts(
      owner: ParserSyntaxNode,
      path: Vector[ParserFieldPathSegment],
      nodes: Map[Long, ParserSyntaxNode]
  ): Vector[InventoryContext] =
    ancestries(owner, nodes, Set.empty).map(ancestors =>
      InventoryContext(InventoryKind.Node, owner.production, normalized(path), ancestors)
    )

  private def ancestries(
      owner: ParserSyntaxNode,
      nodes: Map[Long, ParserSyntaxNode],
      visited: Set[Long]
  ): Vector[Vector[InventoryAncestor]] =
    if visited(owner.id) then Vector.empty
    else if owner.occurrences.isEmpty then Vector(Vector.empty)
    else
      owner.occurrences.flatMap: occurrence =>
        nodes
          .get(occurrence.ownerNodeId)
          .toVector
          .flatMap: ancestor =>
            ancestries(ancestor, nodes, visited + owner.id).map(
              InventoryAncestor(InventoryKind.Node, ancestor.production, normalized(occurrence.fieldPath)) +: _
            )

private[metallurgy] enum NeutralNameClass:
  case Ordinary, Wildcard, Empty

private[metallurgy] object NeutralNameClass:
  def classify(value: String): NeutralNameClass = value match
    case ""  => Empty
    case "_" => Wildcard
    case _   => Ordinary

private[metallurgy] enum CatalogValuePattern:
  case Node
  case Positioned
  case Optional(value: CatalogValuePattern)
  case EmptyOptional(value: CatalogValuePattern)
  case Repeated(element: CatalogValuePattern)
  case EmptyRepeated(element: CatalogValuePattern)
  case Product(prefix: String, fields: Vector[CompilerFieldPattern])
  case Name, GeneratedName
  case ClassifiedName(nameClass: NeutralNameClass)
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
  case ParentWithAncestor(
      ownerKind: InventoryKind,
      ownerPrefix: String,
      path: Vector[CatalogPathSegment],
      ancestor: InventoryAncestor
  )
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
    shapes: Vector[CompilerShapeInventoryRow],
    nodes: Vector[ParserSyntaxNode]
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
    e.tag(5)
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
                contexts.map(CompilerProductionContext(_, row.sourceClassification))
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
      if declarations.forall(declaration =>
          declaration == result ||
            (declaration == CatalogValuePattern.Name && result == CatalogValuePattern.GeneratedName) ||
            (declaration == CatalogValuePattern.Name && result.isInstanceOf[CatalogValuePattern.ClassifiedName]) ||
            ((declaration, result) match
              case (CatalogValuePattern.Repeated(expected), CatalogValuePattern.EmptyRepeated(actual)) =>
                expected == actual
              case (CatalogValuePattern.Optional(expected), CatalogValuePattern.EmptyOptional(actual)) =>
                expected == actual
              case _                                                                                   => false
            )
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

  private def writeScenario(scenario: CompilerRuntimeInventory, e: CanonicalByteEncoder): Unit =
    writeIdentity(scenario.identity, e)
    e.string(scenario.parserEvidenceFingerprint)
    e.sequence(scenario.shapes.sortBy(row => (row.kind.ordinal, row.id))): row =>
      e.tag(row.kind.ordinal); e.long(row.id); e.string(row.prefix)
      e.sequence(row.patternFields)(writeField(_, e))
      e.sequence(row.observation): field =>
        e.string(field.name); writeObservation(field.value, e)
      e.sequence(row.contexts)(writeContext(_, e)); e.tag(row.sourceClassification.ordinal)

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

  private def writeField(field: CompilerFieldPattern, e: CanonicalByteEncoder): Unit =
    e.string(field.name); writePattern(field.value, e)

  private def writePattern(value: CatalogValuePattern, e: CanonicalByteEncoder): Unit = value match
    case CatalogValuePattern.Node                    => e.tag(1)
    case CatalogValuePattern.Positioned              => e.tag(2)
    case CatalogValuePattern.Optional(inner)         => e.tag(3); writePattern(inner, e)
    case CatalogValuePattern.EmptyOptional(inner)    => e.tag(12); writePattern(inner, e)
    case CatalogValuePattern.Repeated(inner)         => e.tag(4); writePattern(inner, e)
    case CatalogValuePattern.EmptyRepeated(inner)    => e.tag(10); writePattern(inner, e)
    case CatalogValuePattern.Product(prefix, fields) => e.tag(5); e.string(prefix); e.sequence(fields)(writeField(_, e))
    case CatalogValuePattern.Name                    => e.tag(6)
    case CatalogValuePattern.GeneratedName           => e.tag(7)
    case CatalogValuePattern.ClassifiedName(value)   => e.tag(11); e.string(value.toString)
    case CatalogValuePattern.Scalar(kind)            => e.tag(8); e.string(kind)
    case CatalogValuePattern.Unsupported(runtime)    => e.tag(9); e.string(runtime)

  private def writeContext(context: InventoryContext, e: CanonicalByteEncoder): Unit =
    e.tag(context.ownerKind.ordinal); e.string(context.ownerPrefix); e.sequence(context.path)(writePath(_, e))
    e.sequence(context.ancestors): ancestor =>
      e.tag(ancestor.ownerKind.ordinal); e.string(ancestor.ownerPrefix); e.sequence(ancestor.path)(writePath(_, e))

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
    val failures                                                         = Vector.newBuilder[InventoryFailure]
    duplicate(snapshot.nodes.map(_.id)).foreach(id =>
      failures += InventoryFailure.DuplicateIdentity(InventoryKind.Node, id)
    )
    duplicate(snapshot.positioned.map(_.id)).foreach(id =>
      failures += InventoryFailure.DuplicateIdentity(InventoryKind.Positioned, id)
    )
    val nodes                                                            = snapshot.nodes.groupBy(_.id).collect { case (id, Vector(node)) => id -> node }
    val positioned                                                       = snapshot.positioned.groupBy(_.id).collect { case (id, Vector(value)) => id -> value }
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
    def outgoing(fields: Vector[ParserSyntaxField])                      =
      fields.flatMap(field => references(field.value, Vector(ParserFieldPathSegment.NamedField(field.name))))
    val graph                                                            = snapshot.nodes.map(node => (InventoryKind.Node, node.id) -> outgoing(node.fields)) ++
      snapshot.positioned.map(value => (InventoryKind.Positioned, value.id) -> outgoing(value.fields))
    val edges                                                            = graph.toMap
    val expectedOccurrences                                              = snapshot.nodes.flatMap(node =>
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
    val actualOccurrences                                                = snapshot.nodes.flatMap(n =>
      n.occurrences.map(o => (InventoryKind.Node, n.id, InventoryKind.Node, o.ownerNodeId, o.fieldPath))
    ) ++ snapshot.positioned.flatMap(n =>
      n.occurrences.map(o => (InventoryKind.Positioned, n.id, InventoryKind.Node, o.ownerNodeId, o.fieldPath))
    )
    def counts[A](values: Vector[A]): Map[A, Int]                        = values.groupMapReduce(identity)(_ => 1)(_ + _)
    val expectedCounts                                                   = counts(expectedOccurrences.toVector)
    val actualCounts                                                     = counts(actualOccurrences)
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
    val reachable                                                        = scala.collection.mutable.Set.empty[(InventoryKind, Long)]
    val pending                                                          = scala.collection.mutable.Stack[(InventoryKind, Long)](InventoryKind.Node -> snapshot.rootNodeId)
    while pending.nonEmpty do
      val current = pending.pop()
      if !reachable(current) then
        reachable += current
        edges.getOrElse(current, Vector.empty).foreach((kind, id, _) => pending.push(kind -> id))
    edges.keys.filterNot(reachable).foreach((kind, id) => failures += InventoryFailure.UnreachableValue(kind, id))
    val visitState                                                       = scala.collection.mutable.Map.empty[(InventoryKind, Long), Int]
    val traversal                                                        = scala.collection.mutable.Stack.empty[((InventoryKind, Long), Boolean)]
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
          InventoryContextLineage.contexts(owner, occurrence._2, nodes)
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
    def declaredPattern(shape: ParserDeclaredShape): CatalogValuePattern = shape match
      case ParserDeclaredShape.Node            => CatalogValuePattern.Node
      case ParserDeclaredShape.Positioned      => CatalogValuePattern.Positioned
      case ParserDeclaredShape.Optional(inner) => CatalogValuePattern.Optional(declaredPattern(inner))
      case ParserDeclaredShape.Repeated(inner) => CatalogValuePattern.Repeated(declaredPattern(inner))
      case ParserDeclaredShape.Name            => CatalogValuePattern.Name
      case ParserDeclaredShape.Scalar(kind)    => CatalogValuePattern.Scalar(kind)
    def pattern(value: InventoryValueObservation): CatalogValuePattern   = value match
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
    val rows                                                             = (snapshot.nodes.map(n =>
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
        occurrences.flatMap(contexts(kind, id, _)).distinct,
        classification
      )
    val found                                                            = failures.result()
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
          snapshot.nodes
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
      .flatMap(_.effectiveOutputRealizations.flatMap(_.template.composites))
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
private[metallurgy] final case class ChildDeclaration(
    roleId: String,
    fieldName: String,
    cardinality: ChildCardinality,
    productionId: String,
    additionalProductionIds: Set[String] = Set.empty
):
  require(productionId.nonEmpty)
  val productionIds: Set[String] = additionalProductionIds + productionId
private[metallurgy] enum TerminalIntervalSelector:
  case FieldBounds(startField: String, endField: String)
  case ChildGap(startRole: String, endRole: String)
  case WholeProduction, WholeSource
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
private[metallurgy] enum PositionProvenancePolicy:
  case SourceDerivedOnly, PositionedIncludingSynthetic
private[metallurgy] enum ChildOccurrenceSelector:
  case First, Last
  case Exact(index: Int)
private[metallurgy] enum OutputBoundary:
  case ProductionStart(policy: PositionProvenancePolicy = PositionProvenancePolicy.SourceDerivedOnly)
  case ProductionEnd(policy: PositionProvenancePolicy = PositionProvenancePolicy.SourceDerivedOnly)
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
  case BoundaryDerived(startBoundary: OutputBoundary, endBoundary: OutputBoundary)
private[metallurgy] final case class PsiOutputRoleId(value: String):
  require(value.nonEmpty)
private[metallurgy] object PsiOutputRoleId:
  val PackageStatement  = PsiOutputRoleId("scala.package.statement")
  val ImportStatement   = PsiOutputRoleId("scala.import.statement")
  val ImportExpression  = PsiOutputRoleId("scala.import.expression")
  val ImportSelectorSet = PsiOutputRoleId("scala.import.selector-set")
  val ImportSelector    = PsiOutputRoleId("scala.import.selector")
  val StableReference   = PsiOutputRoleId("scala.reference.stable")
  val SimpleType        = PsiOutputRoleId("scala.type.simple")
  val ParameterizedType = PsiOutputRoleId("scala.type.parameterized")
  val TypeArguments     = PsiOutputRoleId("scala.type.arguments")
  val IntegerLiteral    = PsiOutputRoleId("scala.literal.integer")
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
private[metallurgy] object PackagePersistenceSurfaces:
  val Stub       = "org/jetbrains/plugins/scala/lang/psi/stubs/ScPackagingStub"
  val Serializer =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScPackagingElementType$#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScPackagingStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val FqnIndex   = "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#PACKAGE_FQN_KEY"
private[metallurgy] final case class OutputCompositeDeclaration(
    id: String,
    parentId: Option[String],
    range: OutputRangeDeclaration,
    outputRoleId: PsiOutputRoleId,
    targetSurfaceId: String,
    targetRequirement: TargetRequirement,
    accessors: Vector[AccessorObligation],
    persistence: PersistenceObligations,
    navigation: Option[NavigationObligation]
)
private[metallurgy] final case class LocalOutputCompositeTemplate(
    composites: Vector[OutputCompositeDeclaration],
    childMounts: Map[String, Option[String]]
)
private[metallurgy] enum ChildOutcomeExpectation:
  case Production(productionId: String)
  case Realization(realizationId: String)
private[metallurgy] final case class ChildOutcomeCondition(
    roleId: String,
    occurrence: ChildOccurrenceSelector,
    expected: ChildOutcomeExpectation
)
private[metallurgy] final case class OutputRealization(
    id: String,
    conditions: Vector[ChildOutcomeCondition],
    template: LocalOutputCompositeTemplate
)
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
    navigation: Option[NavigationObligation] = None,
    outputTemplate: Option[LocalOutputCompositeTemplate] = None,
    outputRealizations: Vector[OutputRealization] = Vector.empty,
    outputRoleId: Option[PsiOutputRoleId] = None
):
  private def defaultOutputTemplate: LocalOutputCompositeTemplate = outputTemplate.getOrElse(
    LocalOutputCompositeTemplate(
      Vector(
        OutputCompositeDeclaration(
          "self",
          None,
          OutputRangeDeclaration.CompilerPosition,
          outputRoleId.getOrElse(PsiOutputRoleId(targetSurfaceId)),
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
  def effectiveOutputRealizations: Vector[OutputRealization]      =
    if outputRealizations.nonEmpty then outputRealizations
    else Vector(OutputRealization("self", Vector.empty, defaultOutputTemplate))
  def effectiveOutputTemplate: LocalOutputCompositeTemplate       = effectiveOutputRealizations.head.template
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

  private val PackageSurface           =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/packaging/ScPackagingImpl"
  private val ImportStatementSurface   =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/imports/ScImportStmtImpl"
  private val ImportExpressionSurface  =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/imports/ScImportExprImpl"
  private val ImportSelectorsSurface   =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/imports/ScImportSelectorsImpl"
  private val ImportSelectorSurface    =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/imports/ScImportSelectorImpl"
  private val StableReferenceSurface   =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/ScStableCodeReferenceImpl"
  private val SimpleTypeSurface        =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScSimpleTypeElementImpl"
  private val ParameterizedTypeSurface =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScParameterizedTypeElementImpl"
  private val TypeArgumentsSurface     =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScTypeArgsImpl"

  private def outputComposite(
      id: String,
      parentId: Option[String],
      range: OutputRangeDeclaration,
      role: PsiOutputRoleId,
      surface: String,
      accessors: Vector[AccessorObligation] = Vector.empty
  ): OutputCompositeDeclaration =
    val persistence = role match
      case PsiOutputRoleId.PackageStatement  =>
        PersistenceObligations.Required(
          PackagePersistenceSurfaces.Stub,
          PackagePersistenceSurfaces.Serializer,
          Vector(PackagePersistenceSurfaces.FqnIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ImportStatement   =>
        PersistenceObligations.Required(
          ImportPersistenceSurfaces.StatementStub,
          ImportPersistenceSurfaces.StatementSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ImportExpression  =>
        PersistenceObligations.Required(
          ImportPersistenceSurfaces.ExpressionStub,
          ImportPersistenceSurfaces.ExpressionSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ImportSelectorSet =>
        PersistenceObligations.Required(
          ImportPersistenceSurfaces.SelectorSetStub,
          ImportPersistenceSurfaces.SelectorSetSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ImportSelector    =>
        PersistenceObligations.Required(
          ImportPersistenceSurfaces.SelectorStub,
          ImportPersistenceSurfaces.SelectorSerializer,
          Vector(ImportPersistenceSurfaces.AliasedImportIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case _                                 => PersistenceObligations.NotApplicable
    OutputCompositeDeclaration(
      id,
      parentId,
      range,
      role,
      surface,
      TargetRequirement.Native,
      accessors,
      persistence,
      Some(NavigationObligation.Self)
    )

  private val ImportStatementAccessors   = Vector(
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/toplevel/imports/ScImportOrExportStmt#importExprs()Lscala/collection/immutable/Seq;",
      required = true
    )
  )
  private val PackageAccessors           = Vector(
    AccessorObligation(s"$PackageSurface#reference()Lscala/Option;", required = true),
    AccessorObligation(s"$PackageSurface#keyword()Lcom/intellij/psi/PsiElement;", required = true)
  )
  private val ImportExpressionAccessors  = Vector(
    AccessorObligation(s"$ImportExpressionSurface#reference()Lscala/Option;", required = true),
    AccessorObligation(s"$ImportExpressionSurface#selectorSet()Lscala/Option;", required = true),
    AccessorObligation(s"$ImportExpressionSurface#qualifier()Lscala/Option;", required = true)
  )
  private val ImportSelectorsAccessors   = Vector(
    AccessorObligation(
      s"$ImportSelectorsSurface#selectors()Lscala/collection/immutable/Seq;",
      required = true
    )
  )
  private val ImportSelectorAccessors    = Vector(
    AccessorObligation(
      s"$ImportSelectorSurface#parentImportExpression()Lorg/jetbrains/plugins/scala/lang/psi/api/toplevel/imports/ScImportExpr;",
      required = true
    ),
    AccessorObligation(s"$ImportSelectorSurface#reference()Lscala/Option;", required = true),
    AccessorObligation(s"$ImportSelectorSurface#givenTypeElement()Lscala/Option;", required = true)
  )
  private val StableReferenceAccessors   = Vector(
    AccessorObligation(s"$StableReferenceSurface#qualifier()Lscala/Option;", required = true),
    AccessorObligation(
      s"$StableReferenceSurface#nameId()Lcom/intellij/psi/PsiElement;",
      required = true
    )
  )
  private val ParameterizedTypeAccessors = Vector(
    AccessorObligation(
      s"$ParameterizedTypeSurface#typeElement()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement;",
      required = true
    ),
    AccessorObligation(
      s"$ParameterizedTypeSurface#typeArgList()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeArgs;",
      required = true
    )
  )
  private val TypeArgumentsAccessors     = Vector(
    AccessorObligation(s"$TypeArgumentsSurface#typeArgs()Lscala/collection/immutable/Seq;", required = true)
  )

  private def transparentTemplate(childRoles: String*): LocalOutputCompositeTemplate =
    LocalOutputCompositeTemplate(Vector.empty, childRoles.map(_ -> None).toMap)

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

  private def selectorSetImportTemplate: LocalOutputCompositeTemplate =
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
          PsiOutputRoleId.ImportStatement,
          ImportStatementSurface,
          ImportStatementAccessors
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

  private def directImportTemplate(outerReference: Boolean): LocalOutputCompositeTemplate =
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
        PsiOutputRoleId.ImportStatement,
        ImportStatementSurface,
        ImportStatementAccessors
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

  private def selectorOwnedImportTemplate: LocalOutputCompositeTemplate =
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
          PsiOutputRoleId.ImportStatement,
          ImportStatementSurface,
          ImportStatementAccessors
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

  val Reviewed: Scala3PsiProductionCatalog = Scala3PsiProductionCatalog(
    Vector(
      Scala3PsiProduction(
        id = "file-package",
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "PackageDef",
          Vector(
            CompilerFieldPattern("pid", CatalogValuePattern.Node),
            CompilerFieldPattern("stats", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node))
          ),
          Vector(
            CompilerProductionContextPattern(ContextPattern.Root, SourceClassification.SourceReachable)
          )
        ),
        dispositions = Vector(
          FieldDisposition("pid", FieldDispositionKind.Child),
          FieldDisposition("stats", FieldDispositionKind.SemanticOnly)
        ),
        children = Vector(
          ChildDeclaration(
            "package-reference",
            "pid",
            ChildCardinality.ExactlyOne,
            "package-stable-reference",
            Set("package-stable-identifier-reference")
          )
        ),
        terminals = Vector(
          TerminalDeclaration(
            "whole-file",
            TerminalIntervalSelector.WholeSource,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne
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
        outputRoleId = Some(PsiOutputRoleId.PackageStatement)
      ),
      Scala3PsiProduction(
        id = "file-package-imports",
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "PackageDef",
          Vector(
            CompilerFieldPattern("pid", CatalogValuePattern.Node),
            CompilerFieldPattern("stats", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
          ),
          Vector(
            CompilerProductionContextPattern(ContextPattern.Root, SourceClassification.SourceReachable)
          )
        ),
        dispositions = Vector(
          FieldDisposition("pid", FieldDispositionKind.Child),
          FieldDisposition("stats", FieldDispositionKind.Child)
        ),
        children = Vector(
          ChildDeclaration(
            "package-reference",
            "pid",
            ChildCardinality.ExactlyOne,
            "package-stable-reference",
            Set("package-stable-identifier-reference")
          ),
          ChildDeclaration(
            "imports",
            "stats",
            ChildCardinality.Grouped(1, None),
            "import-statement"
          )
        ),
        terminals = Vector(
          TerminalDeclaration(
            "whole-file",
            TerminalIntervalSelector.WholeSource,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne
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
        outputRoleId = Some(PsiOutputRoleId.PackageStatement)
      ),
      Scala3PsiProduction(
        id = "file-imports",
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
            "imports",
            "stats",
            ChildCardinality.Grouped(1, None),
            "import-statement"
          )
        ),
        terminals = Vector(
          TerminalDeclaration(
            "whole-file",
            TerminalIntervalSelector.WholeSource,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = ImportStatementSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = ImportStatementAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputTemplate = Some(transparentTemplate("imports"))
      ),
      Scala3PsiProduction(
        id = "import-statement",
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Import",
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
            OccurrenceCardinality.ExactlyOne
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = ImportStatementSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = ImportStatementAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
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
            selectorOwnedImportTemplate
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
            directImportTemplate(outerReference = true)
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
            directImportTemplate(outerReference = false)
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
            selectorSetImportTemplate
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
            selectorSetImportTemplate
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
            selectorSetImportTemplate
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
            selectorSetImportTemplate
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
            selectorSetImportTemplate
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
            selectorSetImportTemplate
          )
        )
      ),
      Scala3PsiProduction(
        id = "import-expression-absent",
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Thicket",
          Vector(CompilerFieldPattern("trees", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node))),
          Vector(
            CompilerProductionContextPattern(
              ContextPattern.Parent(InventoryKind.Node, "Import", Vector(CatalogPathSegment.NamedField("expr"))),
              SourceClassification.Absent
            )
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
        outputTemplate = Some(transparentTemplate())
      ),
      Scala3PsiProduction(
        id = "file-import-empty-package",
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
        outputTemplate = Some(transparentTemplate())
      ),
      Scala3PsiProduction(
        id = "import-path-identifier-reference",
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
                "Import",
                Vector(CatalogPathSegment.NamedField("expr"))
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
            OccurrenceCardinality.ExactlyOne
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
          Vector(
            CompilerProductionContextPattern(
              ContextPattern.Parent(
                InventoryKind.Node,
                "Import",
                Vector(CatalogPathSegment.NamedField("expr"))
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
          ChildDeclaration("qualifier", "qualifier", ChildCardinality.ExactlyOne, "import-path-identifier")
        ),
        terminals = Vector(
          TerminalDeclaration(
            "reference-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne
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
              ContextPattern.ParentWithAncestor(
                InventoryKind.Node,
                "Select",
                Vector(CatalogPathSegment.NamedField("qualifier")),
                InventoryAncestor(
                  InventoryKind.Node,
                  "Import",
                  Vector(CatalogPathSegment.NamedField("expr"))
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
            OccurrenceCardinality.ExactlyOne
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
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "ImportSelector",
          Vector(
            CompilerFieldPattern("imported", CatalogValuePattern.Node),
            CompilerFieldPattern("renamed", CatalogValuePattern.Node),
            CompilerFieldPattern("bound", CatalogValuePattern.Node)
          ),
          Vector(
            CompilerProductionContextPattern(
              ContextPattern.Parent(
                InventoryKind.Node,
                "Import",
                Vector(CatalogPathSegment.NamedField("selectors"), CatalogPathSegment.RepeatedElement)
              ),
              SourceClassification.Synthetic
            )
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
            Set("import-selector-bound-applied-type", "import-selector-absent")
          )
        ),
        terminals = Vector(
          TerminalDeclaration(
            "selector-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.Optional
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = ImportSelectorSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = ImportSelectorAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
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
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "ImportSelector",
          Vector(
            CompilerFieldPattern("imported", CatalogValuePattern.Node),
            CompilerFieldPattern("renamed", CatalogValuePattern.Node),
            CompilerFieldPattern("bound", CatalogValuePattern.Node)
          ),
          Vector(
            CompilerProductionContextPattern(
              ContextPattern.Parent(
                InventoryKind.Node,
                "Import",
                Vector(CatalogPathSegment.NamedField("selectors"), CatalogPathSegment.RepeatedElement)
              ),
              SourceClassification.SourceReachable
            )
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
            Set("import-selector-bound-applied-type", "import-selector-absent")
          )
        ),
        terminals = Vector(
          TerminalDeclaration(
            "selector-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne
          ),
          TerminalDeclaration(
            "scala3-alias-separator",
            TerminalIntervalSelector.ChildGap("imported", "renamed"),
            TerminalLeafTarget.Token(NativePsiElementBindings.ImportAliasAsTokenSurface, Some("as")),
            OccurrenceCardinality.Optional
          ),
          TerminalDeclaration(
            "scala2-alias-separator",
            TerminalIntervalSelector.ChildGap("imported", "renamed"),
            TerminalLeafTarget.Token(NativePsiElementBindings.ImportAliasArrowTokenSurface, Some("=>")),
            OccurrenceCardinality.Optional
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = ImportSelectorSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = ImportSelectorAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
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
            OccurrenceCardinality.ExactlyOne
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = StableReferenceSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = StableReferenceAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputTemplate = Some(transparentTemplate())
      ),
      Scala3PsiProduction(
        id = "import-selector-hidden-name",
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
            OccurrenceCardinality.ExactlyOne
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = ImportSelectorSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = ImportSelectorAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputTemplate = Some(transparentTemplate())
      ),
      Scala3PsiProduction(
        id = "import-selector-wildcard-name",
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
            OccurrenceCardinality.Optional
          ),
          TerminalDeclaration(
            "legacy-wildcard-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Token(NativePsiElementBindings.ImportLegacyWildcardTokenSurface, Some("_")),
            OccurrenceCardinality.Optional
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = ImportSelectorSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = ImportSelectorAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputTemplate = Some(transparentTemplate())
      ),
      Scala3PsiProduction(
        id = "import-selector-empty-name",
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
            OccurrenceCardinality.ExactlyOne
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = ImportSelectorSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = ImportSelectorAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
        outputTemplate = Some(transparentTemplate())
      ),
      Scala3PsiProduction(
        id = "import-selector-bound-type",
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
                Vector(CatalogPathSegment.NamedField("bound"))
              ),
              SourceClassification.SourceReachable
            )
          )
        ),
        dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
        children = Vector.empty,
        terminals = Vector(
          TerminalDeclaration(
            "type-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = SimpleTypeSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = Vector.empty,
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
                SimpleTypeSurface
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
      Scala3PsiProduction(
        id = "import-selector-bound-applied-type",
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "AppliedTypeTree",
          Vector(
            CompilerFieldPattern("tpt", CatalogValuePattern.Node),
            CompilerFieldPattern("args", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
          ),
          Vector(
            CompilerProductionContextPattern(
              ContextPattern
                .Parent(InventoryKind.Node, "ImportSelector", Vector(CatalogPathSegment.NamedField("bound"))),
              SourceClassification.SourceReachable
            ),
            CompilerProductionContextPattern(
              ContextPattern.Parent(
                InventoryKind.Node,
                "AppliedTypeTree",
                Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
              ),
              SourceClassification.SourceReachable
            )
          )
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
            "import-selector-bound-applied-constructor"
          ),
          ChildDeclaration(
            "arguments",
            "args",
            ChildCardinality.Repeated(1, None),
            "import-selector-bound-applied-argument",
            Set("import-selector-bound-applied-type")
          )
        ),
        terminals = Vector(
          TerminalDeclaration(
            "type-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = ParameterizedTypeSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = ParameterizedTypeAccessors,
        persistence = PersistenceObligations.NotApplicable,
        navigation = Some(NavigationObligation.Self),
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
        )
      ),
      Scala3PsiProduction(
        id = "import-selector-bound-applied-constructor",
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Ident",
          Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
          Vector(
            CompilerProductionContextPattern(
              ContextPattern
                .Parent(InventoryKind.Node, "AppliedTypeTree", Vector(CatalogPathSegment.NamedField("tpt"))),
              SourceClassification.SourceReachable
            )
          )
        ),
        dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
        children = Vector.empty,
        terminals = Vector(
          TerminalDeclaration(
            "type-name",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = SimpleTypeSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = Vector.empty,
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
                SimpleTypeSurface
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
      Scala3PsiProduction(
        id = "import-selector-bound-applied-argument",
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Ident",
          Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
          Vector(
            CompilerProductionContextPattern(
              ContextPattern.Parent(
                InventoryKind.Node,
                "AppliedTypeTree",
                Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
              ),
              SourceClassification.SourceReachable
            )
          )
        ),
        dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
        children = Vector.empty,
        terminals = Vector(
          TerminalDeclaration(
            "type-name",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne
          )
        ),
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = SimpleTypeSurface,
        targetRequirement = TargetRequirement.Native,
        accessors = Vector.empty,
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
                SimpleTypeSurface
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
      Scala3PsiProduction(
        id = "import-selector-absent",
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
        outputTemplate = Some(transparentTemplate())
      ),
      Scala3PsiProduction(
        id = "package-stable-identifier-reference",
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
            OccurrenceCardinality.ExactlyOne
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
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Select",
          Vector(
            CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
            CompilerFieldPattern("name", CatalogValuePattern.Name)
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
        dispositions = Vector(
          FieldDisposition("qualifier", FieldDispositionKind.Child),
          FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)
        ),
        children = Vector(
          ChildDeclaration(
            "qualifier",
            "qualifier",
            ChildCardinality.ExactlyOne,
            "package-stable-identifier"
          )
        ),
        terminals = Vector(
          TerminalDeclaration(
            "reference-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne
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
        pattern = CompilerProductionPattern(
          InventoryKind.Node,
          "Ident",
          Vector(CompilerFieldPattern("name", CatalogValuePattern.Name)),
          Vector(
            CompilerProductionContextPattern(
              ContextPattern.ParentWithAncestor(
                InventoryKind.Node,
                "Select",
                Vector(CatalogPathSegment.NamedField("qualifier")),
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
            OccurrenceCardinality.ExactlyOne
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
        navigation = Some(NavigationObligation.Self),
        outputRoleId = Some(PsiOutputRoleId.IntegerLiteral)
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
          case Vector(production) =>
            val requirements = production.effectiveOutputRealizations
              .flatMap(_.template.composites)
              .map(_.targetRequirement.toString)
              .distinct
              .sorted
            val rendered     = if requirements.isEmpty then "transparent" else requirements.mkString(",")
            s"shape-mapped:$rendered:${production.id}"
          case Vector()           => s"unmapped:${occurrence.sourceClassification}"
          case productions        => s"ambiguous:${productions.map(_.id).sorted.mkString(",")}"
        lines += s"- `${render(occurrence)}` — **$status**"
      lines += ""
    lines += "## Scala PSI surfaces"
    lines += ""
    val references        = catalog.productions
      .flatMap: production =>
        val terminals = production.terminals.collect:
          case TerminalDeclaration(_, _, TerminalLeafTarget.Token(id, _), _) => id
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
    case CatalogValuePattern.EmptyOptional(value)     => s"EmptyOptional[${render(value)}]"
    case CatalogValuePattern.Repeated(value)          => s"Repeated[${render(value)}]"
    case CatalogValuePattern.EmptyRepeated(value)     => s"EmptyRepeated[${render(value)}]"
    case CatalogValuePattern.Product(prefix, fields)  =>
      s"$prefix(${fields.map(field => s"${field.name}:${render(field.value)}").mkString(",")})"
    case CatalogValuePattern.Name                     => "Name"
    case CatalogValuePattern.GeneratedName            => "GeneratedName"
    case CatalogValuePattern.ClassifiedName(value)    => s"Name[$value]"
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
      case (CatalogValuePattern.EmptyOptional(_), InventoryValueObservation.Optional(None))                   => true
      case (CatalogValuePattern.Repeated(expected), InventoryValueObservation.Repeated(values))               =>
        values.forall(matches(expected, _))
      case (CatalogValuePattern.EmptyRepeated(_), InventoryValueObservation.Repeated(values))                 =>
        values.isEmpty
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
      case (CatalogValuePattern.EmptyRepeated(expectedValue), CatalogValuePattern.EmptyRepeated(observedValue)) =>
        covers(expectedValue, observedValue)
      case (
            CatalogValuePattern.Product(expectedPrefix, expectedFields),
            CatalogValuePattern.Product(observedPrefix, observedFields)
          ) =>
        expectedPrefix == observedPrefix && coversFields(expectedFields, observedFields)
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
    case ContextPattern.Any                                      => true
    case ContextPattern.Root                                     => context.isEmpty
    case ContextPattern.Parent(kind, owner, p)                   =>
      context.exists(value => value.ownerKind == kind && value.ownerPrefix == owner && value.path == p)
    case ContextPattern.ParentWithAncestor(kind, owner, p, next) =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p && value.ancestors.headOption.contains(
          next
        )
      )

  def aggregateContextMatches(pattern: ContextPattern, context: Option[InventoryContext]): Boolean = pattern match
    case ContextPattern.Any                                      => false
    case ContextPattern.Root                                     => context.isEmpty
    case ContextPattern.Parent(kind, owner, p)                   =>
      context.exists(value => value.ownerKind == kind && value.ownerPrefix == owner && value.path == p)
    case ContextPattern.ParentWithAncestor(kind, owner, p, next) =>
      context.exists(value =>
        value.ownerKind == kind && value.ownerPrefix == owner && value.path == p && value.ancestors.headOption.contains(
          next
        )
      )

  def select(
      catalog: Scala3PsiProductionCatalog,
      kind: InventoryKind,
      prefix: String,
      fields: Vector[InventoryFieldObservation],
      context: Option[InventoryContext],
      sourceClassification: SourceClassification
  ): Vector[Scala3PsiProduction] =
    val matched = catalog.productions.filter(p =>
      p.pattern.kind == kind && p.pattern.prefix == prefix && matchesFields(p.pattern.fields, fields) &&
        p.pattern.occurrences.exists(occurrence =>
          contextMatches(occurrence.context, context) && occurrence.sourceClassification == sourceClassification
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
            pattern.sourceClassification == occurrence.sourceClassification
        )
    )

private[metallurgy] enum CatalogValidationError:
  case DuplicateProductionId(id: String)
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
  case UnknownScenarioRealization(productionId: String, realizationIds: Vector[String])
  case AmbiguousScenarioRealization(productionId: String, realizationIds: Vector[String])
  case MissingScenarioOccurrenceOwner(instance: ProductionInstanceId, ownerNodeId: Long)
  case MissingScenarioOccurrenceContext(instance: ProductionInstanceId, occurrence: ProductionOccurrenceId)

private[metallurgy] object RuntimeRealizationSelector:
  def validate(catalog: Scala3PsiProductionCatalog, runtime: CompilerRuntimeInventory): Vector[CatalogValidationError] =
    val rows                                                                   = runtime.shapes.map(row => (row.kind, row.id) -> row).toMap
    val nodes                                                                  = runtime.nodes.map(node => node.id -> node).toMap
    val selected                                                               = collection.mutable.Map.empty[ProductionInstanceId, Scala3PsiProduction]
    val errors                                                                 = Vector.newBuilder[CatalogValidationError]
    def references(
        value: InventoryValueObservation,
        path: Vector[ParserFieldPathSegment]
    ): Vector[(InventoryKind, Long, Vector[ParserFieldPathSegment])] = value match
      case InventoryValueObservation.Node(id, _)             => Vector((InventoryKind.Node, id, path))
      case InventoryValueObservation.Positioned(id, _)       => Vector((InventoryKind.Positioned, id, path))
      case InventoryValueObservation.Optional(value)         =>
        value.toVector.flatMap(references(_, path :+ ParserFieldPathSegment.OptionalNesting))
      case InventoryValueObservation.Repeated(values)        =>
        values.zipWithIndex.flatMap((candidate, index) =>
          references(candidate, path :+ ParserFieldPathSegment.RepeatedIndex(index))
        )
      case InventoryValueObservation.Product(prefix, fields) =>
        fields.flatMap(field =>
          references(
            field.value,
            path :+ ParserFieldPathSegment.NestedProductBoundary(prefix) :+ ParserFieldPathSegment.NamedField(
              field.name
            )
          )
        )
      case _                                                 => Vector.empty
    def children(instance: ProductionInstanceId): Vector[ProductionInstanceId] =
      if instance.kind == InventoryKind.Positioned then Vector.empty
      else
        rows(instance.kind -> instance.valueId).observation.flatMap(field =>
          references(field.value, Vector(ParserFieldPathSegment.NamedField(field.name))).map: (kind, id, path) =>
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
              val derived = InventoryContextLineage.contexts(owner, occurrence.fieldPath, nodes)
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
            row.sourceClassification
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

    val resolved                                 = collection.mutable.Map.empty[ProductionInstanceId, OutputRealization]
    val resolving                                = collection.mutable.Set.empty[ProductionInstanceId]
    def resolve(key: ProductionInstanceId): Unit =
      if !resolved.contains(key) && !resolving(key) then
        selected.get(key) match
          case Some(production) =>
            resolving += key
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
            childOutcomes.flatMap(_._2).foreach(resolve)
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
                    case ChildOutcomeExpectation.Realization(id) => resolved.get(candidate).exists(_.id == id)
                )
            val matches       = matching match
              case Vector() => Vector.empty
              case values   =>
                val mostSpecific = values.map(_.conditions.size).max
                values.filter(_.conditions.size == mostSpecific)
            matches match
              case Vector(realization) => resolved += key -> realization
              case Vector()            =>
                errors += CatalogValidationError.UnknownScenarioRealization(
                  production.id,
                  production.effectiveOutputRealizations.map(_.id).sorted
                )
              case many                =>
                errors += CatalogValidationError.AmbiguousScenarioRealization(
                  production.id,
                  many.map(_.id).sorted
                )
            resolving -= key
          case None             => ()
    discovered.foreach(resolve)
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
      val names        = p.pattern.fields.map(_.name)
      val childRoles   = p.children.map(_.roleId).toSet
      val realizations = p.effectiveOutputRealizations
      if realizations.isEmpty then errors += CatalogValidationError.EmptyOutputRealizations(p.id)
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
        template.composites.foreach: output =>
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
            case OutputRangeDeclaration.CompilerPosition | OutputRangeDeclaration.CompilerPositionWithPolicy(_) => ()
            case OutputRangeDeclaration.BoundaryDerived(start, end)                                             => validateBoundary(start); validateBoundary(end)
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
          else if children > 1 then errors += CatalogValidationError.DuplicateChildDeclaration(p.id, name)
        else if children > 0 then errors += CatalogValidationError.ChildDeclarationForNonChildField(p.id, name)
        if disposition.size == 1 && disposition.head.kind == FieldDispositionKind.TerminalOrLayout then
          val declared = p.terminals.exists(_.selector match
            case TerminalIntervalSelector.WholeProduction | TerminalIntervalSelector.WholeSource => true
            case TerminalIntervalSelector.FieldBounds(a, b)                                      => a == name || b == name
            case _: TerminalIntervalSelector.ChildGap                                            => false
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
        case _                                          => ()
      )
      p.terminals.foreach:
        case TerminalDeclaration(_, _, TerminalLeafTarget.Token(id, _), _) =>
          requireSurface(p, id, SurfaceFactKind.Token)
        case _                                                             => ()
      realizations
        .flatMap(_.template.composites)
        .foreach: output =>
          requireSurface(p, output.targetSurfaceId, SurfaceFactKind.Element)
          output.accessors.foreach(a => requireSurface(p, a.surfaceId, a.surfaceKind))
          output.persistence match
            case PersistenceObligations.NotApplicable                                   => ()
            case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
              requireSurface(p, stub, SurfaceFactKind.Stub)
              requireSurface(p, serializer, SurfaceFactKind.Serializer)
              indices.foreach(requireSurface(p, _, SurfaceFactKind.Index))
              requireSurface(p, navigation, SurfaceFactKind.Navigation)
    errors ++= coverage
    val accounted                                                                                                     = catalog.productions
      .flatMap: p =>
        val terminals = p.terminals.collect { case TerminalDeclaration(_, _, TerminalLeafTarget.Token(id, _), _) => id }
        val outputs   = p.effectiveOutputRealizations
          .flatMap(_.template.composites)
          .flatMap: output =>
            val persistence = output.persistence match
              case PersistenceObligations.NotApplicable                                   => Vector.empty
              case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
                Vector(stub, serializer, navigation) ++ indices
            Vector(output.targetSurfaceId) ++ output.accessors.map(_.surfaceId) ++ persistence
        outputs ++ terminals
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
      case InventoryKind.Node       => ProductionOccurrenceId(parent.valueId, Vector.empty)
      case InventoryKind.Positioned =>
        parent.occurrence.getOrElse(ProductionOccurrenceId(parent.valueId, Vector.empty))
    ProductionInstanceId(kind, id, Some(ProductionOccurrenceId(origin.ownerNodeId, origin.fieldPath ++ path)))

  def relativePath(
      parent: ProductionInstanceId,
      childOccurrence: ProductionOccurrenceId
  ): Vector[ParserFieldPathSegment] =
    val retainedPrefixLength = parent.kind match
      case InventoryKind.Node       => 0
      case InventoryKind.Positioned => parent.occurrence.fold(0)(_.fieldPath.size)
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
    atomId: Long,
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
private[metallurgy] final case class PlannedTargetAssertion(
    owner: TargetAssertionOwner,
    surfaceId: String,
    kind: TargetAssertionKind
)
private[metallurgy] final case class PlannedAccessorAssertion(
    owner: CompositeInstanceId,
    surfaceId: String,
    required: Boolean
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
      val rows                                                                       = compiler.shapes.map(row => (row.kind, row.id) -> row).toMap
      val nodes                                                                      = snapshot.nodes.map(node => node.id -> node).toMap
      val positioned                                                                 = snapshot.positioned.map(value => value.id -> value).toMap
      def fields(instance: ProductionInstanceId): Vector[ParserSyntaxField]          = instance.kind match
        case InventoryKind.Node       => nodes(instance.valueId).fields
        case InventoryKind.Positioned => positioned(instance.valueId).fields
      def position(instance: ProductionInstanceId): ParserNodePosition               = instance.kind match
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
      def childInstance(
          instance: ProductionInstanceId,
          kind: InventoryKind,
          id: Long,
          path: Vector[ParserFieldPathSegment]
      ): ProductionInstanceId =
        ProductionInstanceLineage.child(instance, kind, id, path)
      def children(instance: ProductionInstanceId): Vector[ProductionInstanceId]     =
        if instance.kind == InventoryKind.Positioned then Vector.empty
        else
          fields(instance).flatMap(field =>
            references(field.value, Vector(ParserFieldPathSegment.NamedField(field.name))).map: (kind, id, path) =>
              childInstance(instance, kind, id, path)
          )
      def contexts(instance: ProductionInstanceId): Vector[Option[InventoryContext]] = instance.occurrence match
        case None             => Vector(None)
        case Some(occurrence) =>
          nodes
            .get(occurrence.ownerNodeId)
            .toVector
            .flatMap(owner => InventoryContextLineage.contexts(owner, occurrence.fieldPath, nodes))
            .map(Some(_))
      val root                                                                       = ProductionInstanceId(InventoryKind.Node, snapshot.rootNodeId, None)
      val instances                                                                  = Vector.newBuilder[ProductionInstanceId]
      val pending                                                                    = collection.mutable.Stack(root)
      val discovered                                                                 = collection.mutable.Set.empty[ProductionInstanceId]
      while pending.nonEmpty do
        val instance = pending.pop()
        if discovered.add(instance) then
          instances += instance
          children(instance).reverseIterator.foreach(pending.push)
      val ordered                                                                    = instances.result()
      val selected                                                                   = collection.mutable.LinkedHashMap.empty[ProductionInstanceId, Scala3PsiProduction]
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
            row.sourceClassification
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

      val active           = collection.mutable.LinkedHashSet(root)
      val incoming         = collection.mutable.LinkedHashMap.empty[ProductionInstanceId, Vector[ProductionInstanceId]]
      val compilerChildren = collection.mutable.LinkedHashMap
        .empty[ProductionInstanceId, Vector[(String, Vector[ParserFieldPathSegment], ProductionInstanceId)]]
      val groupedChildren  = collection.mutable.LinkedHashMap
        .empty[(ProductionInstanceId, String), Vector[Vector[ProductionInstanceId]]]
      ordered.foreach: instance =>
        if active(instance) then
          val production      = selected(instance)
          val plannedChildren = Vector.newBuilder[(String, Vector[ParserFieldPathSegment], ProductionInstanceId)]
          production.dispositions.collectFirst:
            case FieldDisposition(fieldName, FieldDispositionKind.Unsupported) => fieldName
          match
            case Some(fieldName) =>
              break(Left(WholeFilePlanningFailure.UnsupportedFieldDisposition(instance, fieldName)))
            case None            => ()
          production.children.foreach: declaration =>
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
              val owners = incoming.getOrElse(child, Vector.empty) :+ instance
              incoming.update(child, owners)
              if owners.size > 1 then
                break(Left(WholeFilePlanningFailure.MultiplyConsumedChildReference(child, owners)))
              active += child
              plannedChildren += ((declaration.roleId, path, child))
            declaration.cardinality match
              case ChildCardinality.Grouped(_, _) =>
                val groups  = Vector.newBuilder[Vector[ProductionInstanceId]]
                var current = Vector.empty[ProductionInstanceId]
                found.foreach: (child, _) =>
                  val continues = position(child) match
                    case ParserNodePosition.Positioned(range, point, ParserPositionProvenance.SourceDerived)
                        if point >= range.startOffset && point <= range.endOffset =>
                      point == range.startOffset
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
                  if current.nonEmpty && !continues then
                    groups += current
                    current = Vector.empty
                  current :+= child
                if current.nonEmpty then groups += current
                groupedChildren += (instance -> declaration.roleId) -> groups.result()
              case _                              => ()
          if production.layouts != Vector(LayoutAlternative.None) then
            break(Left(WholeFilePlanningFailure.UnsupportedLayout(instance, production.layouts)))
          if production.recovery != RecoveryPolicy.Reject then
            break(Left(WholeFilePlanningFailure.UnsupportedRecovery(instance, production.recovery)))
          compilerChildren.update(instance, plannedChildren.result())
      if snapshot.diagnostics.nonEmpty then break(Left(WholeFilePlanningFailure.UnassignedDiagnostic(0)))

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
          realization.conditions.forall(condition =>
            occurrence(condition).exists(child =>
              condition.expected match
                case ChildOutcomeExpectation.Production(id)  => selected(child).id == id
                case ChildOutcomeExpectation.Realization(id) => resolvedRealizations(child).id == id
            )
          )
        )
        val matches                                                                    = matching match
          case Vector() => Vector.empty
          case values   =>
            val mostSpecific = values.map(_.conditions.size).max
            values.filter(_.conditions.size == mostSpecific)
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
      val declaredDelimiterCuts                                         = resolvedRealizations.valuesIterator
        .flatMap(_.template.composites)
        .flatMap(_.range match
          case OutputRangeDeclaration.BoundaryDerived(
                OutputBoundary.EvidenceBoundaryAfterChild(_, _, _, _, expected, _, _),
                _
              ) =>
            expected.iterator.flatMap: delimiter =>
              Iterator
                .iterate(snapshot.sourceText.indexOf(delimiter))(offset =>
                  snapshot.sourceText.indexOf(delimiter, offset + delimiter.length)
                )
                .takeWhile(_ >= 0)
                .filter(offset =>
                  !snapshot.comments
                    .exists(comment => comment.range.startOffset <= offset && offset < comment.range.endOffset)
                )
                .flatMap(offset => Vector(offset, offset + delimiter.length))
          case _ => Vector.empty
        )
        .toVector
      val evidenceBoundaries                                            =
        (evidence.atoms.map(_.start) ++ evidence.atoms.lastOption.map(_.end) ++ declaredDelimiterCuts).distinct.sorted
      def canonicalOutput(id: CompositeInstanceId): CompositeInstanceId =
        mergedOutputRoots.get(id).fold(id)(canonicalOutput)
      active.toVector.reverse.foreach: instance =>
        val template   = resolvedRealizations(instance).template
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
          case value @ OutputBoundary.ProductionStart(policy)            =>
            positionedRange(instance, policy, value, outputId).startOffset
          case value @ OutputBoundary.ProductionEnd(policy)              =>
            positionedRange(instance, policy, value, outputId).endOffset
          case value @ OutputBoundary.ChildStart(role, selector, policy) =>
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
          case value @ OutputBoundary.ChildEnd(role, selector, policy)   =>
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
              position(target) match
                case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived) =>
                  Some(range.startOffset)
                case _                                                                               =>
                  compilerChildren
                    .getOrElse(target, Vector.empty)
                    .flatMap((_, _, child) => sourceStart(child))
                    .minOption
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
                Iterator
                  .iterate(snapshot.sourceText.indexOf(expected, end))(offset =>
                    snapshot.sourceText.indexOf(expected, offset + expected.length)
                  )
                  .takeWhile(offset => offset >= 0 && offset + expected.length <= followingStart)
                  .filter(offset =>
                    !snapshot.comments
                      .exists(comment => comment.range.startOffset <= offset && offset < comment.range.endOffset)
                  )
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
          case value @ OutputBoundary.Advance(base, count)               =>
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
        val ranges     = template.composites.map: declaration =>
          val range            = declaration.range match
            case OutputRangeDeclaration.CompilerPosition                            =>
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
            case OutputRangeDeclaration.CompilerPositionWithPolicy(policy)          =>
              positionedRange(instance, policy, OutputBoundary.ProductionStart(policy), declaration.id)
            case OutputRangeDeclaration.BoundaryDerived(startBoundary, endBoundary) =>
              val start            = resolve(startBoundary, declaration.id)
              val end              = resolve(endBoundary, declaration.id)
              val emptyWholeSource = snapshot.sourceLength == 0 && start == 0 && end == 0
              if start > end || (start == end && !emptyWholeSource) then
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
          val ownerRange       = positionedRange(
            instance,
            PositionProvenancePolicy.PositionedIncludingSynthetic,
            OutputBoundary.ProductionStart(PositionProvenancePolicy.PositionedIncludingSynthetic),
            declaration.id
          )
          val emptyWholeSource = snapshot.sourceLength == 0 && range.startOffset == 0 && range.endOffset == 0
          if range.startOffset < ownerRange.startOffset || range.endOffset > ownerRange.endOffset ||
            (!emptyWholeSource && (range.startOffset >= range.endOffset || !evidenceBoundaries.contains(
              range.startOffset
            ) || !evidenceBoundaries.contains(range.endOffset)))
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
          (declaration, CompositeInstanceId(instance, declaration.id), range)
        outputRows.update(instance, ranges)
        val localRoots = ranges.collect { case (declaration, id, _) if declaration.parentId.isEmpty => id }
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
        val exported   = compilerChildren
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
                case Some(parent) if parent == declaration.id =>
                  outputRoots(child).map(root => PlannedChild(role, path, canonicalOutput(root)))
                case None                                     => Vector.empty
                case _                                        => Vector.empty
          val children      = localChildren ++ mounted
          val childRanges   = outputRows.valuesIterator.flatten.map(row => row._2 -> row._3).toMap
          val normalized    = children.sortBy: child =>
            val childRange = childRanges(child.child)
            (childRange.startOffset, childRange.endOffset, child.child.toString)
          PlannedComposite(id, production.id, range, normalized, production.dispositions)
      val rawById                                               = rawComposites.map(value => value.instance -> value).toMap
      val mergedSources                                         = mergedOutputRoots.toVector.groupMap(_._2)(_._1)
      val composites                                            = rawComposites
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
          case (Vector(start), Vector(end)) if start.endOffset <= end.startOffset =>
            Vector(PcSourceRange(start.endOffset, end.startOffset))
          case _                                                                  => Vector.empty

      def textRanges(interval: PcSourceRange, expected: String): Vector[PcSourceRange] =
        val found = Vector.newBuilder[PcSourceRange]
        var from  = interval.startOffset
        var next  = snapshot.sourceText.indexOf(expected, from)
        while next >= 0 && next + expected.length <= interval.endOffset do
          val range = PcSourceRange(next, next + expected.length)
          if !snapshot.comments.exists(comment =>
              comment.range.startOffset < range.endOffset && range.startOffset < comment.range.endOffset
            )
          then found += range
          from = next + expected.length
          next = snapshot.sourceText.indexOf(expected, from)
        found.result()

      val tokenCuts     = active.toVector.flatMap: instance =>
        selected(instance).terminals.flatMap:
          case TerminalDeclaration(
                _,
                TerminalIntervalSelector.ChildGap(startRole, endRole),
                TerminalLeafTarget.Token(_, Some(expected)),
                _
              ) =>
            childGapIntervals(instance, startRole, endRole)
              .flatMap(textRanges(_, expected))
              .flatMap(range => Vector(range.startOffset, range.endOffset))
          case _ => Vector.empty
      val planningAtoms = evidence.atoms
        .flatMap: atom =>
          val boundaries = (Vector(atom.start, atom.end) ++ evidenceBoundaries.filter(cut =>
            atom.start < cut && cut < atom.end
          ) ++ tokenCuts.filter(cut => atom.start < cut && cut < atom.end)).distinct.sorted
          boundaries
            .sliding(2)
            .collect:
              case Vector(start, end) => SourceAtom(0L, start, end, atom.claims, atom.comments)
        .zipWithIndex
        .map((atom, index) => atom.copy(id = index.toLong))

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
              val intervals   = position(instance) match
                case _ if terminal.selector == TerminalIntervalSelector.WholeSource =>
                  Vector(PcSourceRange(0, snapshot.sourceLength))
                case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived)
                    if range.startOffset < range.endOffset =>
                  Vector(range)
                case _                                                              => Vector.empty
              val atoms       = intervals.flatMap: interval =>
                planningAtoms
                  .filter(atom => interval.startOffset <= atom.start && atom.end <= interval.endOffset)
                  .filter(atom =>
                    terminal.target == TerminalLeafTarget.Parent || atom.claims.exists(claims(instance, _))
                  )
                  .filter: atom =>
                    terminal.target match
                      case TerminalLeafTarget.Token(_, Some(expected)) =>
                        snapshot.sourceText.substring(atom.start, atom.end) == expected
                      case _                                           => true
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
                val owner = localOutputRoots(instance)
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
            case TerminalIntervalSelector.ChildGap(startRole, endRole)                           =>
              val intervals   = childGapIntervals(instance, startRole, endRole)
              val atoms       = intervals.flatMap: interval =>
                planningAtoms
                  .filter(atom => interval.startOffset <= atom.start && atom.end <= interval.endOffset)
                  .filter(atom => atom.claims.exists(claims(instance, _)))
                  .filter: atom =>
                    terminal.target match
                      case TerminalLeafTarget.Token(_, Some(expected)) =>
                        snapshot.sourceText.substring(atom.start, atom.end) == expected
                      case _                                           => true
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
                val owner = localOutputRoots(instance)
                  .map(canonicalOutput)
                  .distinct
                  .find: root =>
                    val range = compositeRanges(root)
                    range.startOffset <= atom.start && atom.end <= range.endOffset
                  .map(PhysicalLeafOwner.Composite(_))
                  .getOrElse(PhysicalLeafOwner.FileRoot)
                candidates.update(
                  atom.id,
                  candidates(atom.id) :+ PlannedPhysicalLeaf(
                    atom.id,
                    atom.start,
                    atom.end,
                    owner,
                    instance,
                    terminal.id,
                    terminal.target
                  )
                )
            case other                                                                           =>
              break(Left(WholeFilePlanningFailure.UnsupportedTerminalSelector(production.id, terminal.id, other)))
      def isAncestor(ancestor: ProductionInstanceId, descendant: ProductionInstanceId): Boolean =
        Iterator
          .iterate(Vector(descendant))(_.flatMap(incoming.getOrElse(_, Vector.empty)))
          .takeWhile(_.nonEmpty)
          .flatten
          .contains(ancestor)
      val leaves                                                                                = planningAtoms.map: atom =>
        val eligible = candidates(atom.id).filterNot(candidate =>
          candidate.target == TerminalLeafTarget.Parent && active.exists(descendant =>
            descendant != candidate.sourceOwner && isAncestor(candidate.sourceOwner, descendant) && atom.claims.exists(
              claims(descendant, _)
            ) && localOutputRoots(descendant).nonEmpty
          )
        )
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
      val targets                                                                               = active.toVector.flatMap: instance =>
        val production = selected(instance)
        val composites = outputRows(instance).collect:
          case (declaration, id, _) if !mergedOutputRoots.contains(id) =>
            val requirement = declaration.targetRequirement match
              case TargetRequirement.Native          => TargetAssertionKind.NativeComposite
              case TargetRequirement.Compatible      => TargetAssertionKind.CompatibleComposite
              case TargetRequirement.NativeCandidate =>
                break(Left(WholeFilePlanningFailure.UnprobedNativeCandidate(instance, production.id)))
            PlannedTargetAssertion(TargetAssertionOwner.Composite(id), declaration.outputRoleId.value, requirement)
        val terminals  = production.terminals.collect:
          case TerminalDeclaration(id, _, TerminalLeafTarget.Token(surfaceId, _), _)
              if resolvedTerminals(instance -> id) =>
            PlannedTargetAssertion(TargetAssertionOwner.Terminal(instance, id), surfaceId, TargetAssertionKind.Token)
        composites ++ terminals
      val accessors                                                                             = active.toVector.flatMap(instance =>
        outputRows(instance).flatMap:
          case (_, id, _) if mergedOutputRoots.contains(id) => Vector.empty
          case (declaration, id, _)                         =>
            declaration.accessors.map(obligation =>
              PlannedAccessorAssertion(id, obligation.surfaceId, obligation.required)
            )
      )
      val stubs                                                                                 = active.toVector.flatMap: instance =>
        outputRows(instance).flatMap:
          case (_, id, _) if mergedOutputRoots.contains(id) => Vector.empty
          case (declaration, id, _)                         =>
            declaration.persistence match
              case PersistenceObligations.NotApplicable                                   => Vector.empty
              case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
                Vector(PlannedStubAssertion(id, stub, serializer, indices, navigation))
      val navigation                                                                            = active.toVector.flatMap: instance =>
        outputRows(instance).flatMap:
          case (_, id, _) if mergedOutputRoots.contains(id) => Vector.empty
          case (declaration, id, _)                         =>
            declaration.navigation.map(PlannedNavigationAssertion(id, _))
      Right(
        WholeFileProductionPlan(
          snapshot.sourceUri,
          snapshot.sourceDigest,
          evidence.parserEvidenceFingerprint,
          leaves,
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
