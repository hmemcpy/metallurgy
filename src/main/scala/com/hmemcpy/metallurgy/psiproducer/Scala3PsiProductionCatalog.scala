package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

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
private[metallurgy] final case class InventoryFieldObservation(name: String, value: InventoryValueObservation)
private[metallurgy] final case class CompilerFieldPattern(name: String, value: CatalogValuePattern)
private[metallurgy] final case class CompilerProductionPattern(
    kind: InventoryKind,
    prefix: String,
    fields: Vector[CompilerFieldPattern],
    contexts: Vector[ContextPattern] = Vector(ContextPattern.Any)
)
private[metallurgy] enum ContextPattern:
  case Any
  case Root
  case Parent(ownerKind: InventoryKind, ownerPrefix: String, path: Vector[CatalogPathSegment])
private[metallurgy] final case class CompilerShapeInventoryRow(
    kind: InventoryKind,
    prefix: String,
    patternFields: Vector[CompilerFieldPattern],
    observations: Vector[Vector[InventoryFieldObservation]],
    contexts: Vector[InventoryContext],
    sourceClassifications: Vector[SourceClassification]
)
private[metallurgy] final case class CompilerRuntimeInventory(
    identity: CompilerRuntimeIdentity,
    parserEvidenceFingerprint: String,
    shapes: Vector[CompilerShapeInventoryRow]
)
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
    val expectedOccurrences                                                          = graph.flatMap { case (owner, references) =>
      references.map { case (kind, id, path) => (kind, id, owner._1, owner._2, path) }
    }
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
              )
            )
          )
        )
      case ParserFieldValue.Name(value)             => InventoryValueObservation.Name(value)
      case ParserFieldValue.GeneratedName(a, b, c)  => InventoryValueObservation.GeneratedName(a, b, c)
      case ParserFieldValue.Scalar(value)           => InventoryValueObservation.Scalar(value)
      case ParserFieldValue.Unsupported(value)      => InventoryValueObservation.Unsupported(value)
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
        InventoryFieldObservation(f.name, observe(f.value, kind, id, Vector(ParserFieldPathSegment.NamedField(f.name))))
      )
      val classification = position match
        case ParserNodePosition.Absent                                                   => SourceClassification.Absent
        case ParserNodePosition.Positioned(_, _, ParserPositionProvenance.SourceDerived) =>
          SourceClassification.SourceReachable
        case _                                                                           => SourceClassification.Synthetic
      CompilerShapeInventoryRow(
        kind,
        prefix,
        observed.map(f => CompilerFieldPattern(f.name, pattern(f.value))),
        Vector(observed),
        occurrences.flatMap(context(kind, id, _)),
        Vector(classification)
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
  case Element, Token, Factory, PublicAccessor, Stub, Serializer, Index, Navigation, ParserProduction
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
    classification: SurfaceClassification
)
private[metallurgy] final case class ScalaPsiSurfaceInventory(rows: Vector[ScalaPsiSurfaceRow])

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
  case WholeProduction
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
  case Native, Compatible
private[metallurgy] final case class AccessorObligation(surfaceId: String, required: Boolean)
private[metallurgy] enum PersistenceObligations:
  case NotApplicable
  case Required(
      stubSurfaceId: String,
      serializerSurfaceId: String,
      indexSurfaceIds: Vector[String],
      navigationSurfaceId: String
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
    persistence: PersistenceObligations
)
private[metallurgy] final case class Scala3PsiProductionCatalog(productions: Vector[Scala3PsiProduction])
private[metallurgy] object Scala3PsiProductionCatalog:
  val Empty: Scala3PsiProductionCatalog = Scala3PsiProductionCatalog(Vector.empty)

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
      case (CatalogValuePattern.Name, InventoryValueObservation.Name(_))                                      => true
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

  def contextMatches(pattern: ContextPattern, context: Option[InventoryContext]): Boolean = pattern match
    case ContextPattern.Any                    => true
    case ContextPattern.Root                   => context.isEmpty
    case ContextPattern.Parent(kind, owner, p) => context.contains(InventoryContext(kind, owner, p))

  def select(
      catalog: Scala3PsiProductionCatalog,
      kind: InventoryKind,
      prefix: String,
      fields: Vector[InventoryFieldObservation],
      context: Option[InventoryContext]
  ): Vector[Scala3PsiProduction] =
    catalog.productions.filter(p =>
      p.pattern.kind == kind && p.pattern.prefix == prefix && matchesFields(p.pattern.fields, fields) &&
        p.pattern.contexts.exists(contextMatches(_, context))
    )

private[metallurgy] enum CatalogValidationError:
  case DuplicateProductionId(id: String)
  case DuplicateSurfaceId(id: String)
  case UnclassifiedSurface(id: String)
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
  case UncoveredCompilerShape(kind: InventoryKind, prefix: String, context: Option[InventoryContext])
  case AmbiguousCompilerShape(
      kind: InventoryKind,
      prefix: String,
      context: Option[InventoryContext],
      productionIds: Vector[String]
  )

private[metallurgy] object Scala3PsiProductionCatalogValidator:
  def validate(
      catalog: Scala3PsiProductionCatalog,
      compiler: CompilerRuntimeInventory,
      surfaces: ScalaPsiSurfaceInventory
  ): Vector[CatalogValidationError] =
    val errors                                                                                                        = Vector.newBuilder[CatalogValidationError]
    duplicates(catalog.productions.map(_.id)).foreach(id => errors += CatalogValidationError.DuplicateProductionId(id))
    duplicates(surfaces.rows.map(_.id)).foreach(id => errors += CatalogValidationError.DuplicateSurfaceId(id))
    surfaces.rows
      .filter(_.classification == SurfaceClassification.Unclassified)
      .foreach(r => errors += CatalogValidationError.UnclassifiedSurface(r.id))
    val surfaceMap                                                                                                    = surfaces.rows.groupBy(_.id).collect { case (id, Vector(row)) => id -> row }
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
            case TerminalIntervalSelector.WholeProduction   => true
            case TerminalIntervalSelector.FieldBounds(a, b) => a == name || b == name
          )
          if !declared then errors += CatalogValidationError.MissingTerminalDeclaration(p.id, name)
      p.terminals.foreach(_.selector match
        case TerminalIntervalSelector.FieldBounds(a, b) =>
          Vector(a, b)
            .filterNot(names.contains)
            .foreach(n => errors += CatalogValidationError.UnknownTerminalField(p.id, n))
        case _                                          => ()
      )
      requireSurface(p, p.targetSurfaceId, SurfaceFactKind.Element)
      p.accessors.foreach(a => requireSurface(p, a.surfaceId, SurfaceFactKind.PublicAccessor, Some(p.targetSurfaceId)))
      p.persistence match
        case PersistenceObligations.NotApplicable                                   => ()
        case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
          requireSurface(p, stub, SurfaceFactKind.Stub, Some(p.targetSurfaceId));
          requireSurface(p, serializer, SurfaceFactKind.Serializer, Some(stub));
          indices.foreach(requireSurface(p, _, SurfaceFactKind.Index, Some(stub)));
          requireSurface(p, navigation, SurfaceFactKind.Navigation, Some(p.targetSurfaceId))
    compiler.shapes.foreach: shape =>
      val contexts = if shape.contexts.isEmpty then Vector(None) else shape.contexts.map(Some(_))
      shape.observations.foreach: observation =>
        contexts.foreach: context =>
          val selected = CatalogShapeMatcher.select(catalog, shape.kind, shape.prefix, observation, context)
          if selected.isEmpty then
            errors += CatalogValidationError.UncoveredCompilerShape(shape.kind, shape.prefix, context)
          else if selected.size > 1 then
            errors += CatalogValidationError.AmbiguousCompilerShape(
              shape.kind,
              shape.prefix,
              context,
              selected.map(_.id).sorted
            )
    val accounted                                                                                                     = catalog.productions.flatMap(p => p.targetSurfaceId +: p.accessors.map(_.surfaceId)).toSet
    surfaces.rows
      .filter(r => r.classification == SurfaceClassification.SyntaxContract && !accounted(r.id))
      .foreach(r => errors += CatalogValidationError.UnaccountedSyntaxSurface(r.id))
    errors.result().distinct.sortBy(_.toString)
  private def duplicates(values: Vector[String]) =
    values.groupMapReduce(identity)(_ => 1)(_ + _).collect { case (id, n) if n > 1 => id }.toVector.sorted

private[metallurgy] enum WholeFilePlanningFailure:
  case InventoryFailures(failures: Vector[InventoryFailure])
  case EvidenceFingerprintMismatch(snapshot: String, evidence: String)
  case InventoryFingerprintMismatch(snapshot: String, inventory: String)
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
  case IncompleteWholeFilePlan(matchedProductionIds: Vector[String])
private[metallurgy] final case class WholeFileProductionPlan(
    physicalLeafOwnership: Vector[String],
    virtualLayout: Vector[String],
    composites: Vector[String],
    targetAssertions: Vector[String],
    accessorAssertions: Vector[String],
    stubAssertions: Vector[String]
)

private[metallurgy] object WholeFileProductionPlanner:
  def plan(
      snapshot: ParserSyntaxSnapshot,
      evidence: ProvisionalSourceEvidencePlan,
      catalog: Scala3PsiProductionCatalog,
      compiler: CompilerRuntimeInventory,
      surfaces: ScalaPsiSurfaceInventory
  ): Either[WholeFilePlanningFailure, WholeFileProductionPlan] =
    val fingerprint = ParserSyntaxSnapshot.evidenceFingerprint(snapshot)
    if fingerprint != evidence.parserEvidenceFingerprint then
      Left(WholeFilePlanningFailure.EvidenceFingerprintMismatch(fingerprint, evidence.parserEvidenceFingerprint))
    else if fingerprint != compiler.parserEvidenceFingerprint then
      Left(WholeFilePlanningFailure.InventoryFingerprintMismatch(fingerprint, compiler.parserEvidenceFingerprint))
    else
      val validation = Scala3PsiProductionCatalogValidator.validate(catalog, compiler, surfaces)
      if validation.nonEmpty then Left(WholeFilePlanningFailure.InvalidCatalog(validation))
      else
        val matched = compiler.shapes.flatMap: shape =>
          val contexts = if shape.contexts.isEmpty then Vector(None) else shape.contexts.map(Some(_))
          shape.observations.flatMap(observation =>
            contexts.flatMap(context =>
              CatalogShapeMatcher.select(catalog, shape.kind, shape.prefix, observation, context).map(_.id)
            )
          )
        Left(WholeFilePlanningFailure.IncompleteWholeFilePlan(matched))
