package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import scala.util.boundary
import scala.util.boundary.break

private[metallurgy] final class SourceOrderedRangeIndex[A](
    values: Vector[A],
    start: A => Int,
    end: A => Int,
    examined: Int => Unit
):
  def within(interval: PcSourceRange): Vector[A] =
    val result = Vector.newBuilder[A]
    var index  = startIndex(interval.startOffset)
    while index < values.size && start(values(index)) <= interval.endOffset do
      examined(1)
      val value = values(index)
      if end(value) <= interval.endOffset then result += value
      index += 1
    result.result()

  private def startIndex(offset: Int): Int =
    var low  = 0
    var high = values.size
    while low < high do
      examined(1)
      val middle = low + (high - low) / 2
      if start(values(middle)) < offset then low = middle + 1
      else high = middle
    low

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
    plan(snapshot, evidence, prepared, PlanningWorkObserver.NoOp)

  def plan(
      snapshot: ParserSyntaxSnapshot,
      evidence: ProvisionalSourceEvidencePlan,
      prepared: PreparedProductionCatalog,
      workObserver: PlanningWorkObserver
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
            case Right(compiler) => compile(snapshot, evidence, prepared, compiler, workObserver)

  private def compile(
      snapshot: ParserSyntaxSnapshot,
      evidence: ProvisionalSourceEvidencePlan,
      prepared: PreparedProductionCatalog,
      compiler: CompilerRuntimeInventory,
      workObserver: PlanningWorkObserver
  ): Either[WholeFilePlanningFailure, WholeFileProductionPlan] =
    if compiler.identity != prepared.compiler.identity then
      Left(WholeFilePlanningFailure.CatalogInventoryIdentityMismatch(compiler.identity, prepared.compiler.identity))
    else
      val validation =
        Scala3PsiProductionCatalogValidator.validateExecutable(prepared.catalog, compiler, prepared.surfaces)
      if validation.nonEmpty then Left(WholeFilePlanningFailure.InvalidCatalog(validation))
      else
        compileClosedSubset(
          snapshot,
          evidence,
          prepared.catalog,
          compiler,
          prepared.unavailableRealizations,
          workObserver
        )

  private def rangeResolvableUnderSynthetic(declaration: OutputRangeDeclaration): Boolean =
    declaration match
      case OutputRangeDeclaration.CompilerPositionWithPolicy(policy) =>
        policy == PositionProvenancePolicy.PositionedIncludingSynthetic
      case _                                                         => false

  private def compileClosedSubset(
      snapshot: ParserSyntaxSnapshot,
      evidence: ProvisionalSourceEvidencePlan,
      catalog: Scala3PsiProductionCatalog,
      compiler: CompilerRuntimeInventory,
      unavailableRealizations: Set[(String, String)],
      workObserver: PlanningWorkObserver
  ): Either[WholeFilePlanningFailure, WholeFileProductionPlan] =
    compileAttempt(
      snapshot,
      evidence,
      catalog,
      compiler,
      unavailableRealizations,
      PlanningWorkObserver.NoOp,
      Set.empty
    ).flatMap: baseline =>
      def provesCandidates(plan: WholeFileProductionPlan, roots: Set[ProductionInstanceId]): Boolean =
        val selections = plan.realizationSelections.map(value => value.owner -> value.reason).toMap
        roots.forall(root => selections.get(root).contains(RealizationSelectionReason.PreferredCandidate))
      val candidateOwners                                                                            = baseline.realizationSelections.collect:
        case PlannedRealizationSelection(owner, _, RealizationSelectionReason.AtomicWholePlanFallback) => owner
      def dischargedByRecovery(index: Int): Boolean                                                  =
        baseline.recoveryOwnerships.exists(ownership =>
          ownership.diagnosticOrdinal == index && ownership.severity == ParserDiagnosticSeverity.Error && !ownership.sharing
        )
      // Each Error diagnostic is discharged only by a non-sharing recovery ownership record that
      // owns exactly that ordinal; every other Error requires the atomic candidate path.
      val firstUndischargedError                                                                     = snapshot.diagnostics.zipWithIndex.collect:
        case (diagnostic, index)
            if diagnostic.severity == ParserDiagnosticSeverity.Error && !dischargedByRecovery(index) =>
          index
      snapshot.diagnostics.indexWhere(_.severity == ParserDiagnosticSeverity.Error) match
        case _ if candidateOwners.isEmpty && firstUndischargedError.nonEmpty =>
          Left(WholeFilePlanningFailure.UnassignedDiagnostic(firstUndischargedError.head))
        case _                                                               =>
          val candidateRoots = candidateOwners.flatMap(owner => atomicCandidateRoot(snapshot, compiler, owner))
          AtomicWholePlanCandidateScope
            .validate(
              candidateRoots,
              snapshot.sourceLength,
              snapshot.capabilities.diagnosticPositionProvenance,
              snapshot.diagnostics
            )
            .filter(_ => candidateRoots.size == candidateOwners.size) match
            case Some(candidates) if candidates.nonEmpty =>
              val accepted = AtomicWholePlanTrials.select(candidates): roots =>
                compileAttempt(
                  snapshot,
                  evidence,
                  catalog,
                  compiler,
                  unavailableRealizations,
                  PlanningWorkObserver.NoOp,
                  roots
                ) match
                  case Left(_)     => false
                  case Right(plan) => provesCandidates(plan, roots)
              compileAttempt(snapshot, evidence, catalog, compiler, unavailableRealizations, workObserver, accepted)
            case _                                       =>
              compileAttempt(
                snapshot,
                evidence,
                catalog,
                compiler,
                unavailableRealizations,
                workObserver,
                Set.empty
              )

  private def atomicCandidateRoot(
      snapshot: ParserSyntaxSnapshot,
      compiler: CompilerRuntimeInventory,
      owner: ProductionInstanceId
  ): Option[AtomicWholePlanCandidateRoot] =
    def occurrenceCount[A](values: Vector[A], occurrence: A => ProductionOccurrenceId): Int =
      owner.occurrence.fold(0)(expected => values.count(value => occurrence(value) == expected))
    def exactlyOne[A](values: Vector[A]): Option[A]                                         = values match
      case Vector(value) => Some(value)
      case _             => None
    owner.kind match
      case InventoryKind.Node       =>
        exactlyOne(snapshot.nodes.filter(_.id == owner.valueId))
          .map: node =>
            val count = occurrenceCount(
              node.occurrences,
              value => ProductionOccurrenceId(value.ownerNodeId, value.fieldPath)
            )
            AtomicWholePlanCandidateRoot(owner, node.position, count)
      case InventoryKind.Positioned =>
        exactlyOne(snapshot.positioned.filter(_.id == owner.valueId))
          .map: positioned =>
            val count = occurrenceCount(
              positioned.occurrences,
              value => ProductionOccurrenceId(value.ownerNodeId, value.fieldPath)
            )
            AtomicWholePlanCandidateRoot(owner, positioned.position, count)
      case InventoryKind.Product    =>
        exactlyOne(compiler.products.filter(_.id == owner.valueId))
          .map: product =>
            val count = occurrenceCount(
              product.occurrences,
              value => ProductionOccurrenceId(value.ownerNodeId, value.fieldPath)
            )
            AtomicWholePlanCandidateRoot(owner, product.position, count)

  private def compileAttempt(
      snapshot: ParserSyntaxSnapshot,
      evidence: ProvisionalSourceEvidencePlan,
      catalog: Scala3PsiProductionCatalog,
      compiler: CompilerRuntimeInventory,
      unavailableRealizations: Set[(String, String)],
      workObserver: PlanningWorkObserver,
      enabledAtomicRoots: Set[ProductionInstanceId]
  ): Either[WholeFilePlanningFailure, WholeFileProductionPlan] =
    boundary[Either[WholeFilePlanningFailure, WholeFileProductionPlan]]:
      val lexicalAtoms                                                                            = evidence.lexicalContract.atoms
      val lexicalRangeIndex                                                                       = new SourceOrderedRangeIndex(
        lexicalAtoms,
        _.start,
        _.end,
        workObserver.terminalLexicalEntries
      )
      def lexicalAtomsWithin(interval: PcSourceRange): Vector[ClosedSourceLexicalAtom]            =
        lexicalRangeIndex.within(interval)
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
      val ancestorEvidence                                                                        = compiler.shapes.collect:
        case row if row.kind == InventoryKind.Node =>
          row.id -> InventoryAncestorEvidence(row.scannerTokenKinds, row.directNodeEvidence)
      val lineages                                                                                =
        InventoryContextLineage.resolver(nodes, ancestorEvidence.toMap)
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
      val retainedProductionMatches                                                               =
        collection.mutable.LinkedHashMap.empty[ProductionInstanceId, RetainedProductionMatch]
      val runtimeParents                                                                          = ordered
        .flatMap(parent =>
          children(parent).flatMap(child =>
            child.occurrence.map(occurrence =>
              child -> RuntimeParentEdge(parent, ProductionInstanceLineage.relativePath(parent, occurrence))
            )
          )
        )
        .groupMap(_._1)(_._2)
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
                catalog,
                enabledAtomicRoots
              ),
            route =>
              OwnedRootRouteMatcher.matches(
                instance,
                route,
                runtimeParents,
                selected,
                candidate => rows(candidate.kind -> candidate.valueId).prefix,
                position,
                catalog,
                enabledAtomicRoots,
                candidateRoute = true
              )
          )
        )
        val distinct   = selections.map(_._2.map(_.id).sorted).distinct
        distinct match
          case Vector(ids) if ids.nonEmpty =>
            val matches = ids.flatMap(id => catalog.productions.find(_.id == id))
            ProductionMatchRetention.retain(catalog, matches) match
              case Right(retained) =>
                selected += instance                  -> retained.candidate
                retainedProductionMatches += instance -> retained
              case Left(_)         =>
                break(
                  Left(
                    WholeFilePlanningFailure.AmbiguousProduction(
                      row.kind,
                      row.prefix,
                      ids,
                      selections.headOption.flatMap(_._1).map(_.ownerPrefix),
                      instance.occurrence.map(_.fieldPath).getOrElse(Vector.empty)
                    )
                  )
                )
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
          production.recovery match
            case RecoveryPolicy.DiagnosticBound(_, alternatives) if alternatives.nonEmpty => ()
            case _                                                                        => break(Left(WholeFilePlanningFailure.UnsupportedRecovery(instance, production.recovery)))
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

      val resolvedRealizations  = collection.mutable.LinkedHashMap.empty[ProductionInstanceId, OutputRealization]
      val realizationSelections =
        collection.mutable.LinkedHashMap.empty[ProductionInstanceId, PlannedRealizationSelection]
      active.toVector.reverse.foreach: instance =>
        val children                                                                   = compilerChildren.getOrElse(instance, Vector.empty)
        val trialEligibility                                                           = selected(instance).realizationChoice
          .filter(_.policy == RealizationChoicePolicy.AtomicWholePlan)
          .map(_.trialEligibility)
          .getOrElse(Vector.empty)
        val trialEligible                                                              = trialEligibility.forall: requirement =>
          val roleChildren                                                                     = children.collect { case (roleId, _, child) if roleId == requirement.roleId => child }
          def matches(child: ProductionInstanceId, expected: ChildOutcomeExpectation): Boolean =
            expected.alternatives.exists:
              case ChildOutcomeExpectation.Production(id)   => selected(child).id == id
              case ChildOutcomeExpectation.OutputRole(role) =>
                resolvedRealizations(child).template.composites.exists(_.outputRoleId == role)
              case _                                        => false
          requirement.rootOutcome match
            case ChildRootOutcome.One(expected) =>
              roleChildren match
                case Vector(child) => matches(child, expected)
                case _             => false
            case ChildRootOutcome.All(expected) => roleChildren.forall(matches(_, expected))
            case ChildRootOutcome.AnyReviewed   => false
        if trialEligibility.nonEmpty && !trialEligible && !enabledAtomicRoots(instance) then
          retainedProductionMatches
            .get(instance)
            .flatMap(_.fallback)
            .foreach(fallback => selected.update(instance, fallback))
        def occurrence(condition: ChildOutcomeCondition): Option[ProductionInstanceId] =
          val values = children.collect { case (condition.roleId, _, child) => child }
          condition.occurrence match
            case ChildOccurrenceSelector.First        => values.headOption
            case ChildOccurrenceSelector.Last         => values.lastOption
            case ChildOccurrenceSelector.Exact(index) => values.lift(index)
        def outcomeMatches(
            child: ProductionInstanceId,
            expected: ChildOutcomeExpectation
        ): Boolean = expected.alternatives.exists:
          case ChildOutcomeExpectation.Production(id)     => selected(child).id == id
          case ChildOutcomeExpectation.Realization(id)    => resolvedRealizations(child).id == id
          case ChildOutcomeExpectation.OutputRole(role)   =>
            resolvedRealizations(child).template.composites.exists(_.outputRoleId == role)
          case ChildOutcomeExpectation.OutputRoles(roles) =>
            resolvedRealizations(child).template.composites.exists(output => roles(output.outputRoleId))
          case ChildOutcomeExpectation.AnyOf(_)           => false
        val matching                                                                   = selected(instance).effectiveOutputRealizations.filter(realization =>
          val childConditions    = realization.conditions.forall(condition =>
            occurrence(condition).exists(outcomeMatches(_, condition.expected))
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
            case EvidenceCondition.TrailingProductionScannerToken(kind, present)             =>
              val endsWithToken = position(instance) match
                case ParserNodePosition.Positioned(range, _, _) =>
                  snapshot.scannerTokens.exists(token =>
                    token.kind == kind && token.range.endOffset == range.endOffset &&
                      range.startOffset <= token.range.startOffset
                  )
                case _                                          => false
              endsWithToken == present
            case EvidenceCondition.RepeatedFieldSize(fieldName, minimum, maximum)            =>
              val size = rows(instance.kind -> instance.valueId).observation
                .find(_.name == fieldName)
                .collect:
                  case InventoryFieldObservation(_, InventoryValueObservation.Repeated(values), _) => values.size
                .getOrElse(-1)
              size >= minimum && maximum.forall(size <= _)
            case EvidenceCondition.RepeatedNodeFieldDistinct(repeated, prefix, fieldName)    =>
              val matchingNodes = rows(instance.kind -> instance.valueId).observation
                .find(_.name == repeated)
                .toVector
                .flatMap:
                  case InventoryFieldObservation(_, InventoryValueObservation.Repeated(values), _) =>
                    values.collect:
                      case InventoryValueObservation.Node(id, production) if production.startsWith(prefix) => id
                  case _                                                                           => Vector.empty
              val fieldValues   = matchingNodes.flatMap: id =>
                rows(InventoryKind.Node -> id).observation
                  .find(_.name == fieldName)
                  .flatMap:
                    case InventoryFieldObservation(_, InventoryValueObservation.Name(value), _)           => Some(value)
                    case InventoryFieldObservation(_, InventoryValueObservation.BacktickedName(value), _) => Some(value)
                    case _                                                                                => None
              fieldValues.size == matchingNodes.size && fieldValues.distinct.size == fieldValues.size
            case EvidenceCondition.RepeatedNodesTrailingPrefix(repeated, prefix)             =>
              val productions = rows(instance.kind -> instance.valueId).observation
                .find(_.name == repeated)
                .toVector
                .flatMap:
                  case InventoryFieldObservation(_, InventoryValueObservation.Repeated(values), _) =>
                    values.collect { case InventoryValueObservation.Node(_, production) => production }
                  case _                                                                           => Vector.empty
              productions.dropWhile(!_.startsWith(prefix)).forall(_.startsWith(prefix))
            case EvidenceCondition.ProductionStartsWith(kind, present)                       =>
              val startsWith = position(instance) match
                case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived) =>
                  evidence.lexicalContract.atoms
                    .find(atom => atom.start >= range.startOffset && atom.end <= range.endOffset)
                    .exists(_.kind == kind)
                case _                                                                               => false
              startsWith == present
            case EvidenceCondition.RuntimeSupplementPositive(fieldName, present)             =>
              (runtimeSupplementCount(snapshot, instance, fieldName) > 0) == present
            case EvidenceCondition.LeadingBeforeRuntimeTailPresent(repeated, count, present) =>
              val repeatedCount = fields(instance)
                .find(_.name == repeated)
                .collect:
                  case ParserSyntaxField(_, ParserFieldValue.Repeated(values), _) => values.size
                .getOrElse(0)
              (repeatedCount > runtimeSupplementCount(snapshot, instance, count)) == present
            case EvidenceCondition.RootAttachment(attachment, present)                       =>
              CatalogShapeMatcher.rootAttachmentConditionMatches(
                attachment,
                present,
                rows(instance.kind -> instance.valueId).rootAttachments
              )
            case EvidenceCondition.TrailingRepeatedNodeChild(
                  repeatedFieldName,
                  nodePrefix,
                  nodeClassification,
                  childField,
                  childPrefix,
                  childClassification,
                  childNameField,
                  childNameExpected,
                  childSourceText
                ) =>
              def childTextMatches(childId: Long): Boolean =
                position(ProductionInstanceId(InventoryKind.Node, childId, None)) match
                  case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived) =>
                    snapshot.sourceText
                      .substring(range.startOffset, range.endOffset) == childSourceText &&
                    evidence.lexicalContract.atoms.exists(atom =>
                      atom.start == range.startOffset && atom.end == range.endOffset
                    )
                  case _                                                                               => false
              def childNameMatches(childId: Long): Boolean =
                rows(InventoryKind.Node -> childId).observation
                  .find(_.name == childNameField)
                  .flatMap:
                    case InventoryFieldObservation(_, InventoryValueObservation.Name(value), _)           => Some(value)
                    case InventoryFieldObservation(_, InventoryValueObservation.BacktickedName(value), _) =>
                      Some(value)
                    case _                                                                                => None
                  .contains(childNameExpected)
              def typedChildMatches(nodeId: Long): Boolean =
                rows(InventoryKind.Node -> nodeId).sourceClassification == nodeClassification &&
                  rows(InventoryKind.Node -> nodeId).observation
                    .find(_.name == childField)
                    .flatMap:
                      case InventoryFieldObservation(_, InventoryValueObservation.Node(childId, prefix), _) =>
                        Some(childId -> prefix)
                      case _                                                                                => None
                    .exists: (childId, prefix) =>
                      prefix.startsWith(childPrefix) &&
                        rows(InventoryKind.Node -> childId).sourceClassification == childClassification &&
                        childNameMatches(childId) && childTextMatches(childId)
              val accepted                                 = rows(instance.kind -> instance.valueId).observation
                .find(_.name == repeatedFieldName)
                .flatMap:
                  case InventoryFieldObservation(_, InventoryValueObservation.Repeated(values), _) =>
                    Some(values)
                  case _                                                                           => None
                .exists: values =>
                  val matching = values.collect:
                    case InventoryValueObservation.Node(id, prefix) if prefix.startsWith(nodePrefix) => id
                  values.lastOption
                    .collect:
                      case InventoryValueObservation.Node(id, prefix) if prefix.startsWith(nodePrefix) => id
                    .exists: lastId =>
                      matching == Vector(lastId) && typedChildMatches(lastId)
              accepted
          childConditions && evidenceConditions
        )
        val matches                                                                    = matching match
          case Vector() => Vector.empty
          case values   =>
            val mostSpecific = values.map(value => value.conditions.size + value.evidenceConditions.size).max
            values.filter(value => value.conditions.size + value.evidenceConditions.size == mostSpecific)
        matches match
          case values if selected(instance).realizationChoice.nonEmpty =>
            val production = selected(instance)
            def rootMatches(
                child: ProductionInstanceId,
                expected: ChildOutcomeExpectation,
                root: OutputCompositeDeclaration
            ): Boolean = expected.alternatives.exists:
              case ChildOutcomeExpectation.Production(id)     => selected(child).id == id
              case ChildOutcomeExpectation.Realization(id)    => resolvedRealizations(child).id == id
              case ChildOutcomeExpectation.OutputRole(role)   => root.outputRoleId == role
              case ChildOutcomeExpectation.OutputRoles(roles) => roles(root.outputRoleId)
              case ChildOutcomeExpectation.AnyOf(_)           => false
            def assess(
                candidate: OutputRealization
            ): Either[CandidateRealizationDefect, Vector[CandidateInapplicability]] =
              val candidateRange      = position(instance) match
                case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived) => Right(range)
                case _                                                                               =>
                  Left(CandidateRealizationDefect.SourceOwnership("candidate root has no source-derived position"))
              val completeFallback    = retainedProductionMatches
                .get(instance)
                .flatMap(_.fallback)
                .exists(OwnedRootRouteMatcher.isCompletePayload) ||
                selected(instance).realizationChoice.exists(_.policy == RealizationChoicePolicy.AtomicWholePlan) &&
                OwnedRootRouteMatcher.hasCompletePayloadFallback(selected(instance))
              val excludedAttachments = candidate.evidenceConditions.collect:
                case EvidenceCondition.RootAttachment(attachment, false)
                    if CatalogShapeMatcher.rootAttachmentConditionMatches(
                      attachment,
                      present = true,
                      rows(instance.kind -> instance.valueId).rootAttachments
                    ) =>
                  CandidateInapplicability.ExcludedRootAttachment(attachment)
              if candidateRange.isLeft then candidateRange.map(_ => Vector.empty)
              else if !completeFallback then
                Left(
                  CandidateRealizationDefect.CandidateEvidence("candidate has no retained complete payload fallback")
                )
              else if excludedAttachments.nonEmpty then Right(excludedAttachments)
              else if unavailableRealizations(production.id -> candidate.id) then
                Right(Vector(CandidateInapplicability.UnavailableHostBinding(production.id, candidate.id)))
              else if !values.exists(_.id == candidate.id) then
                Left(
                  CandidateRealizationDefect.CandidateEvidence("candidate conditions or source evidence do not match")
                )
              else
                boundary[Either[CandidateRealizationDefect, Vector[CandidateInapplicability]]]:
                  val reviewed = Vector.newBuilder[CandidateInapplicability]
                  candidate.requiredChildRoots.foreach: requirement =>
                    val roleChildren = children.collect {
                      case (roleId, _, child) if roleId == requirement.roleId => child
                    }
                    def assessChild(
                        child: ProductionInstanceId,
                        expected: ChildOutcomeExpectation
                    ): Either[CandidateRealizationDefect, Unit] =
                      val roots       = resolvedRealizations(child).template.composites.filter(_.parentId.isEmpty)
                      val sourceOwned = position(child) match
                        case ParserNodePosition.Positioned(range, _, provenance) =>
                          val parent    = candidateRange.toOption.get
                          val contained = parent.startOffset <= range.startOffset && range.endOffset <= parent.endOffset
                          contained && (provenance == ParserPositionProvenance.SourceDerived ||
                            (provenance == ParserPositionProvenance.Synthetic && roots.nonEmpty && roots.forall(root =>
                              rangeResolvableUnderSynthetic(root.range)
                            )))
                        case _                                                   => false
                      if !sourceOwned then
                        Left(
                          CandidateRealizationDefect.SourceOwnership(
                            s"${requirement.roleId} child $child has no contained source-derived position"
                          )
                        )
                      else
                        roots match
                          case Vector()     =>
                            reviewed += CandidateInapplicability.MissingChildRoot(
                              requirement.roleId,
                              child,
                              selected(child).id,
                              resolvedRealizations(child).id
                            )
                            Right(())
                          case Vector(root) =>
                            if rootMatches(child, expected, root) then
                              assessNestedRequirements(
                                child,
                                selected(child).nestedChildRequirements,
                                visited = Vector.empty
                              )
                              Right(())
                            else
                              reviewed += CandidateInapplicability.UnsupportedChildRoot(
                                requirement.roleId,
                                child,
                                root.outputRoleId
                              )
                              Right(())
                          case many         =>
                            Left(CandidateRealizationDefect.ChildRootAmbiguity(requirement.roleId, child, many.size))
                    def assessNestedRequirements(
                        child: ProductionInstanceId,
                        requirements: Vector[RequiredChildRootOutcome],
                        visited: Vector[ProductionInstanceId]
                    ): Unit =
                      if visited.contains(child) then
                        break(
                          Left(
                            WholeFilePlanningFailure.InvalidProductionParticipation(
                              ProductionParticipationFailure.CyclicClosure(visited :+ child)
                            )
                          )
                        )
                      requirements.foreach: requirement =>
                        val nestedRoleChildren                                                                = compilerChildren
                          .getOrElse(child, Vector.empty)
                          .collect { case (rid, _, grand) if rid == requirement.roleId => grand }
                        def assessGrand(grand: ProductionInstanceId, expected: ChildOutcomeExpectation): Unit =
                          assessChild(grand, expected) match
                            case Left(reason) =>
                              break(Left(reason))
                            case Right(_)     =>
                              assessNestedRequirements(grand, requirement.nestedRequirements, visited :+ child)
                        requirement.rootOutcome match
                          case ChildRootOutcome.One(expected) =>
                            nestedRoleChildren match
                              case Vector(grand) => assessGrand(grand, expected)
                              case _             =>
                                reviewed += CandidateInapplicability.MissingChildRoot(
                                  requirement.roleId,
                                  child,
                                  selected(child).id,
                                  resolvedRealizations(child).id
                                )
                          case ChildRootOutcome.All(expected) =>
                            nestedRoleChildren.foreach: grand =>
                              assessGrand(grand, expected)
                          case ChildRootOutcome.AnyReviewed   => ()
                    requirement.rootOutcome match
                      case ChildRootOutcome.One(expected) =>
                        roleChildren match
                          case Vector(child) =>
                            assessChild(child, expected) match
                              case Left(reason) => break(Left(reason))
                              case Right(_)     => ()
                          case values        =>
                            break(
                              Left(
                                CandidateRealizationDefect.CandidateEvidence(
                                  s"${requirement.roleId} has ${values.size} children"
                                )
                              )
                            )
                      case ChildRootOutcome.All(expected) =>
                        roleChildren.foreach(child =>
                          assessChild(child, expected) match
                            case Left(reason) => break(Left(reason))
                            case Right(_)     => ()
                        )
                      case ChildRootOutcome.AnyReviewed   =>
                        break(
                          Left(
                            CandidateRealizationDefect.CandidateEvidence(
                              s"${requirement.roleId} uses an invalid candidate root requirement"
                            )
                          )
                        )
                  Right(reviewed.result())
            val forced     = production.realizationChoice
              .filter(_.policy == RealizationChoicePolicy.AtomicWholePlan)
              .filterNot(_ => enabledAtomicRoots(instance))
              .flatMap(choice => production.effectiveOutputRealizations.find(_.id == choice.fallbackId))
              .map(SelectedRealization(_, RealizationSelectionReason.AtomicWholePlanFallback))
            forced
              .toRight(())
              .fold(
                _ => RealizationChoiceSelector.select(production, values, assess),
                value => Right(value)
              ) match
              case Left(failure)    =>
                break(Left(WholeFilePlanningFailure.InvalidRealizationChoice(instance, failure)))
              case Right(selection) =>
                resolvedRealizations += instance  -> selection.realization
                realizationSelections += instance -> PlannedRealizationSelection(
                  instance,
                  selection.realization.id,
                  selection.reason
                )
          case Vector(value)                                           =>
            resolvedRealizations += instance -> value
          case Vector()                                                =>
            break(Left(WholeFilePlanningFailure.UnknownOutputRealization(instance, selected(instance).id)))
          case values                                                  =>
            break(
              Left(
                WholeFilePlanningFailure.AmbiguousOutputRealization(
                  instance,
                  selected(instance).id,
                  values.map(_.id).sorted
                )
              )
            )

      val participation                                                                = ProductionParticipationPlanner
        .plan(active.toVector, selected, compilerChildren, resolvedRealizations, position)
        .fold(
          failure => break(Left(WholeFilePlanningFailure.InvalidProductionParticipation(failure))),
          identity
        )
      val participating                                                                = participation.retained
      val participatingSet                                                             = participating.toSet
      def activeTerminals(instance: ProductionInstanceId): Vector[TerminalDeclaration] =
        val production = selected(instance)
        resolvedRealizations(instance).terminalIds match
          case None      => production.terminals
          case Some(ids) => production.terminals.filter(terminal => ids(terminal.id))
      def participatingCompilerChildren(
          instance: ProductionInstanceId
      ): Vector[(String, Vector[ParserFieldPathSegment], ProductionInstanceId)] =
        compilerChildren.getOrElse(instance, Vector.empty).filter((_, _, child) => participatingSet(child))

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
        val owners           = participating.filter(instance =>
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
      participating.reverse.foreach: instance =>
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
        def nearestSourceOwnerRange(
            target: ProductionInstanceId,
            boundary: OutputBoundary,
            outputId: String
        )(using scala.util.boundary.Label[Either[WholeFilePlanningFailure, WholeFileProductionPlan]]): PcSourceRange =
          var current = target
          var extent  = position(current) match
            case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived) => Some(range)
            case _                                                                               => None
          while extent.isEmpty do
            parentOwner(current) match
              case Right(owner) =>
                current = owner
                position(owner) match
                  case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived) =>
                    extent = Some(range)
                  case _                                                                               => ()
              case Left(reason) =>
                break(
                  Left(
                    WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                      instance,
                      outputId,
                      boundary,
                      reason
                    )
                  )
                )
          extent.get
        def resolve(boundary: OutputBoundary, outputId: String)(using
            scala.util.boundary.Label[Either[WholeFilePlanningFailure, WholeFileProductionPlan]]
        ): Int = boundary match
          case value @ OutputBoundary.ProductionStart(policy)                                              =>
            positionedRange(instance, policy, value, outputId).startOffset
          case value @ OutputBoundary.ProductionEnd(policy)                                                =>
            positionedRange(instance, policy, value, outputId).endOffset
          case OutputBoundary.ProductionPoint                                                              =>
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
          case OutputBoundary.ProductionNameEnd                                                            =>
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
          case OutputBoundary.ProductionFirstIdentifierStart | OutputBoundary.ProductionFirstIdentifierEnd =>
            val range = positionedRange(
              instance,
              PositionProvenancePolicy.SourceDerivedOnly,
              boundary,
              outputId
            )
            evidence.lexicalContract.atoms
              .find(atom =>
                range.startOffset <= atom.start && atom.end <= range.endOffset &&
                  (atom.kind == ClosedSourceLexicalKind.Identifier ||
                    atom.kind == ClosedSourceLexicalKind.QuotedIdentifier)
              )
              .map(atom => if boundary == OutputBoundary.ProductionFirstIdentifierStart then atom.start else atom.end)
              .getOrElse(
                break(
                  Left(
                    WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                      instance,
                      outputId,
                      boundary,
                      "production has no closed lexical identifier"
                    )
                  )
                )
              )
          case OutputBoundary.ParentProductionEnd                                                          =>
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
          case OutputBoundary.TemplateLayoutStart                                                          =>
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
          case value @ (_: OutputBoundary.PreviousSignificantChildTokenStart |
              _: OutputBoundary.PreviousSignificantChildTokenStartWithinOwner) =>
            val (role, selector, policy) = value match
              case OutputBoundary.PreviousSignificantChildTokenStart(role, selector, policy)            =>
                (role, selector, policy)
              case OutputBoundary.PreviousSignificantChildTokenStartWithinOwner(role, selector, policy) =>
                (role, selector, policy)
            val candidates               = compilerChildren(instance).collect { case (`role`, _, child) => child }
            val selectedChild            = selector match
              case ChildOccurrenceSelector.First          => candidates.headOption
              case ChildOccurrenceSelector.Last           => candidates.lastOption
              case ChildOccurrenceSelector.Exact(ordinal) => candidates.lift(ordinal)
            val childStart               = selectedChild
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
            val ownerStart               = value match
              case _: OutputBoundary.PreviousSignificantChildTokenStart            => 0
              case _: OutputBoundary.PreviousSignificantChildTokenStartWithinOwner =>
                nearestSourceOwnerRange(instance, value, outputId).startOffset
            evidence.lexicalContract.atoms
              .dropWhile(_.start < ownerStart)
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
          case value @ OutputBoundary.ChildStart(role, selector, policy)                                   =>
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
          case value @ OutputBoundary.ChildEnd(role, selector, policy)                                     =>
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
          case value @ OutputBoundary.NextScannerTokenStartAfterChild(role, selector, kind, policy)        =>
            val candidates    = compilerChildren(instance).collect { case (`role`, _, child) => child }
            val selectedChild = selector match
              case ChildOccurrenceSelector.First          => candidates.headOption
              case ChildOccurrenceSelector.Last           => candidates.lastOption
              case ChildOccurrenceSelector.Exact(ordinal) => candidates.lift(ordinal)
            val childEnd      = selectedChild
              .flatMap: child =>
                val resolvedEnd = outputRows
                  .getOrElse(child, Vector.empty)
                  .collectFirst { case (declaration, _, range) if declaration.parentId.isEmpty => range.endOffset }
                Some(resolvedEnd.getOrElse(positionedRange(child, policy, value, outputId).endOffset))
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
            val productionEnd = positionedRange(instance, policy, value, outputId).endOffset
            snapshot.scannerTokens
              .find(token =>
                token.kind == kind && childEnd <= token.range.startOffset && token.range.endOffset <= productionEnd
              )
              .map(_.range.startOffset)
              .getOrElse(
                break(
                  Left(
                    WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                      instance,
                      outputId,
                      value,
                      s"scanner token $kind is missing after child"
                    )
                  )
                )
              )
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
          case value @ OutputBoundary.Advance(base, count)                                                 =>
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
          val values                                                  = rows(instance.kind -> instance.valueId).observation
            .find(_.name == fieldName)
            .toVector
            .flatMap:
              case InventoryFieldObservation(_, InventoryValueObservation.Repeated(values), _) => values
              case _                                                                           => Vector.empty
          val matchingOrdinals                                        = values.zipWithIndex.collect:
            case (value, ordinal) if CatalogShapeMatcher.matches(valuePattern, value) => ordinal
          val ownerRange                                              = positionedRange(
            instance,
            PositionProvenancePolicy.PositionedIncludingSynthetic,
            OutputBoundary.ProductionStart(PositionProvenancePolicy.PositionedIncludingSynthetic),
            declaration.id
          )
          def balancedPairs(lexical: Vector[ClosedSourceLexicalAtom]) =
            val pairs      = Vector.newBuilder[PcSourceRange]
            var openStarts = List.empty[Int]
            lexical.foreach: atom =>
              if atom.kind == opening then openStarts = atom.start :: openStarts
              else if atom.kind == closing then
                openStarts match
                  case start :: remaining =>
                    openStarts = remaining
                    pairs += PcSourceRange(start, atom.end)
                  case Nil                => ()
            pairs.result().sortBy(range => (range.startOffset, range.endOffset))
          val ownerLexical                                            = lexicalSlice(ownerRange)
          val ownerPairs                                              = balancedPairs(ownerLexical)
          val childExtent                                             = compilerChildren
            .getOrElse(instance, Vector.empty)
            .flatMap: (_, _, child) =>
              position(child) match
                case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived) => Some(range)
                case _                                                                               => None
          val enclosingPair                                           = for
            start <- childExtent.map(_.startOffset).minOption
            end   <- childExtent.map(_.endOffset).maxOption
            left  <- ownerLexical.filter(atom => atom.kind == opening && atom.start <= start).lastOption
            right <- ownerLexical.find(atom => atom.kind == closing && atom.end >= end)
          yield PcSourceRange(left.start, right.end)
          val parentLexical                                           = incoming.getOrElse(instance, Vector.empty).distinct match
            case Vector(parent) =>
              position(parent) match
                case ParserNodePosition.Positioned(range, _, _) => lexicalSlice(range)
                case _                                          => Vector.empty
            case _              => Vector.empty
          val sourceOwnerRange                                        = nearestSourceOwnerRange(
            instance,
            OutputBoundary.ProductionStart(PositionProvenancePolicy.PositionedIncludingSynthetic),
            declaration.id
          )
          val parentPairs                                             = balancedPairs(parentLexical).filter(pair =>
            pair.startOffset >= sourceOwnerRange.startOffset && pair.endOffset <= sourceOwnerRange.endOffset
          )
          val candidatePairs                                          = (ownerPairs ++ enclosingPair ++ parentPairs).distinct
          val childRanges                                             = compilerChildren
            .getOrElse(instance, Vector.empty)
            .flatMap:
              case (
                    _,
                    ParserFieldPathSegment.NamedField(`fieldName`) +:
                    ParserFieldPathSegment.RepeatedIndex(ordinal) +: _,
                    child
                  ) =>
                position(child) match
                  case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived) =>
                    Some(ordinal -> range)
                  case _                                                                               => None
              case _ => None
            .groupMap(_._1)(_._2)
          val childCorrelations                                       = childRanges.toVector.flatMap: (ordinal, children) =>
            val child     = PcSourceRange(
              children.iterator.map(_.startOffset).min,
              children.iterator.map(_.endOffset).max
            )
            val enclosing = candidatePairs
              .filter(pair => pair.startOffset <= child.startOffset && pair.endOffset >= child.endOffset)
            val minimum   = enclosing.iterator.map(pair => pair.endOffset - pair.startOffset).minOption
            minimum.flatMap: size =>
              enclosing.filter(pair => pair.endOffset - pair.startOffset == size) match
                case Vector(pair) => Some(ordinal -> pair)
                case _            => None
          val correlated                                              = childCorrelations.filter((ordinal, _) => matchingOrdinals.contains(ordinal))
          def directPairs(pairs: Vector[PcSourceRange])               = pairs.filterNot: pair =>
            pairs.exists(other =>
              other != pair &&
                other.startOffset < pair.startOffset &&
                other.endOffset > pair.endOffset
            )
          val emptyOrdinals                                           = matchingOrdinals.filterNot(childRanges.contains)
          val usedPairs                                               = childCorrelations.map(_._2).toSet
          val ownerDirectPairs                                        = directPairs(ownerPairs).filterNot(usedPairs)
          val parentDirectPairs                                       = directPairs(parentPairs).filterNot(usedPairs)
          val directEmptyPairs                                        =
            if ownerDirectPairs.size == emptyOrdinals.size then ownerDirectPairs
            else if ownerDirectPairs.isEmpty && parentDirectPairs.size == emptyOrdinals.size then parentDirectPairs
            else Vector.empty
          val emptyCorrelations                                       = emptyOrdinals.zip(directEmptyPairs)
          val ranges                                                  = (correlated ++ emptyCorrelations).sortBy(_._1)
          if ranges.size != matchingOrdinals.size || ranges.map(_._2).distinct.size != ranges.size then
            break(
              Left(
                WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                  instance,
                  declaration.id,
                  OutputBoundary.ProductionStart(),
                  s"repeated field occurrences ${matchingOrdinals.mkString("[", ",", "]")} do not correlate " +
                    s"one-to-one with child ordinals ${childRanges.keys.toVector.sorted.mkString("[", ",", "]")} " +
                    s"and adjacent lexical delimiters ${candidatePairs.mkString("[", ",", "]")} " +
                    s"inside source owner $sourceOwnerRange"
                )
              )
            )
          ranges.map { case (ordinal, range) =>
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
          val range                 = declaration.range match
            case OutputRangeDeclaration.CompilerPosition                                              =>
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
            case OutputRangeDeclaration.CompilerPositionWithPolicy(policy)                            =>
              positionedRange(instance, policy, OutputBoundary.ProductionStart(policy), declaration.id)
            case OutputRangeDeclaration.CompilerPositionWithTrailingBalancedBrackets(policy)          =>
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
            case OutputRangeDeclaration.CompilerEndMarker                                             =>
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
            case OutputRangeDeclaration.BoundaryDerived(startBoundary, endBoundary)                   =>
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
            case OutputRangeDeclaration.BalancedLexicalRangeBeforeChildOutput(role, opening, closing) =>
              val sourceOwner = nearestSourceOwnerRange(
                instance,
                OutputBoundary.ParentProductionEnd,
                declaration.id
              )
              val childStarts = compilerChildren
                .getOrElse(instance, Vector.empty)
                .collect { case (`role`, _, child) => child }
                .flatMap(child =>
                  outputRows.getOrElse(child, Vector.empty).collect {
                    case (childDeclaration, _, childRange) if childDeclaration.parentId.isEmpty =>
                      childRange.startOffset
                  }
                )
              val anchor      = childStarts.distinct match
                case Vector(value) => value
                case values        =>
                  break(
                    Left(
                      WholeFilePlanningFailure.OutputBoundaryResolutionFailed(
                        instance,
                        declaration.id,
                        OutputBoundary.ProductionStart(),
                        s"child output anchor is missing or ambiguous: ${values.mkString("[", ",", "]")}"
                      )
                    )
                  )
              val preceding   = lexicalAtomsWithin(PcSourceRange(sourceOwner.startOffset, anchor))
              val closeIndex  = preceding.lastIndexWhere(atom =>
                atom.kind != ClosedSourceLexicalKind.Whitespace &&
                  atom.kind != ClosedSourceLexicalKind.LineComment && atom.kind != ClosedSourceLexicalKind.BlockComment
              )
              if closeIndex < 0 || preceding(closeIndex).kind != closing then
                break(
                  Left(
                    WholeFilePlanningFailure.InvalidOutputRange(
                      instance,
                      declaration.id,
                      anchor,
                      anchor,
                      PcSourceRange(anchor, anchor)
                    )
                  )
                )
              var depth       = 0
              var index       = closeIndex
              var start       = Option.empty[Int]
              while index >= 0 && start.isEmpty do
                val atom = preceding(index)
                if atom.kind == closing then depth += 1
                else if atom.kind == opening then
                  depth -= 1
                  if depth == 0 then start = Some(atom.start)
                  else if depth < 0 then
                    break(
                      Left(
                        WholeFilePlanningFailure.InvalidOutputRange(
                          instance,
                          declaration.id,
                          atom.start,
                          preceding(closeIndex).end,
                          PcSourceRange(atom.start, preceding(closeIndex).end)
                        )
                      )
                    )
                index -= 1
              val rangeStart  = start.getOrElse(
                break(
                  Left(
                    WholeFilePlanningFailure.InvalidOutputRange(
                      instance,
                      declaration.id,
                      anchor,
                      anchor,
                      PcSourceRange(anchor, anchor)
                    )
                  )
                )
              )
              PcSourceRange(rangeStart, preceding(closeIndex).end)
          val ownerRange            = positionedRange(
            instance,
            PositionProvenancePolicy.PositionedIncludingSynthetic,
            OutputBoundary.ProductionStart(PositionProvenancePolicy.PositionedIncludingSynthetic),
            declaration.id
          )
          val parentDerived         = declaration.range match
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
          val sourceAncestorDerived = declaration.range match
            case OutputRangeDeclaration.BoundaryDerived(start, end) =>
              Set(start, end).exists:
                case OutputBoundary.PreviousSignificantChildTokenStartWithinOwner(_, _, _) => true
                case _                                                                     => false
            case _                                                  => false
          val extentRange           =
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
            else if sourceAncestorDerived then
              nearestSourceOwnerRange(instance, OutputBoundary.ParentProductionEnd, declaration.id)
            else ownerRange
          val validExtent           = declaration.range match
            case OutputRangeDeclaration.CompilerPositionWithBodyLayoutOrEndMarker(_, _, _, _, _) |
                OutputRangeDeclaration.CompilerPositionWithTrailingBalancedBrackets(_) |
                OutputRangeDeclaration.BoundaryDerivedWithTrailingBalancedBrackets(_, _) =>
              range.startOffset >= ownerRange.startOffset && range.endOffset <= snapshot.sourceLength
            case OutputRangeDeclaration.BalancedLexicalRangeBeforeChildOutput(_, _, _) =>
              val sourceOwner = nearestSourceOwnerRange(
                instance,
                OutputBoundary.ParentProductionEnd,
                declaration.id
              )
              range.startOffset >= sourceOwner.startOffset && range.endOffset <= sourceOwner.endOffset
            case _ if parentDerived || sourceAncestorDerived                           =>
              range.startOffset >= extentRange.startOffset && range.endOffset <= extentRange.endOffset
            case _                                                                     =>
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
        val exported             = participatingCompilerChildren(instance)
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
      val rawComposites                                         = participating.flatMap: instance =>
        val production = selected(instance)
        val template   = resolvedRealizations(instance).template
        outputRows(instance).map: (declaration, id, range) =>
          val localChildren = outputRows(instance).collect {
            case (child, childId, _) if child.parentId.contains(declaration.id) =>
              PlannedChild("output", Vector.empty, childId)
          }
          val mounted       = participatingCompilerChildren(instance)
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
              case ParserNodePosition.Positioned(range, _, _) => range
        (ranges(startRole), ranges(endRole)) match
          case (starts, ends) if starts.nonEmpty && ends.nonEmpty =>
            val start = starts.maxBy(_.endOffset)
            val end   = ends.minBy(_.startOffset)
            if start.endOffset <= end.startOffset then Vector(PcSourceRange(start.endOffset, end.startOffset))
            else Vector.empty
          case _                                                  => Vector.empty

      def childSeparatorIntervals(instance: ProductionInstanceId, role: String): Vector[PcSourceRange] =
        compilerChildren
          .getOrElse(instance, Vector.empty)
          .collect:
            case (`role`, _, child) => position(child)
          .collect:
            case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived) => range
          .sortBy(range => (range.startOffset, range.endOffset))
          .sliding(2)
          .collect:
            case Vector(left, right) if left.endOffset <= right.startOffset =>
              PcSourceRange(left.endOffset, right.startOffset)
          .toVector

      def childOutputRanges(
          instance: ProductionInstanceId,
          role: String,
          requireOutput: Boolean = true
      ): Vector[PcSourceRange] =
        val ranges = compilerChildren
          .getOrElse(instance, Vector.empty)
          .collect { case (`role`, _, child) => child }
          .flatMap(child =>
            outputRoots
              .getOrElse(child, Vector.empty)
              .map: rootId =>
                outputRows
                  .getOrElse(rootId.origin, Vector.empty)
                  .collectFirst { case (_, `rootId`, range) => range }
                  .getOrElse(
                    break(
                      Left(
                        WholeFilePlanningFailure.UnsupportedTerminalSelector(
                          selected(instance).id,
                          role,
                          TerminalIntervalSelector.BeforeChildOutputs(role)
                        )
                      )
                    )
                  )
          )
          .sortBy(range => (range.startOffset, range.endOffset))
        if (requireOutput && ranges.isEmpty) || ranges.sliding(2).exists {
            case Vector(left, right) => left.endOffset > right.startOffset
            case _                   => false
          }
        then
          break(
            Left(
              WholeFilePlanningFailure.UnsupportedTerminalSelector(
                selected(instance).id,
                role,
                TerminalIntervalSelector.BeforeChildOutputs(role)
              )
            )
          )
        ranges

      def terminalIntervals(
          instance: ProductionInstanceId,
          production: Scala3PsiProduction,
          terminal: TerminalDeclaration
      ): Vector[PcSourceRange] = terminal.selector match
        case TerminalIntervalSelector.WholeSource if instance != root                                            =>
          break(
            Left(
              WholeFilePlanningFailure.UnsupportedTerminalSelector(
                production.id,
                terminal.id,
                terminal.selector
              )
            )
          )
        case TerminalIntervalSelector.WholeSource                                                                =>
          Vector(PcSourceRange(0, snapshot.sourceLength))
        case TerminalIntervalSelector.LocalOutput(outputId)                                                      =>
          outputRows
            .getOrElse(instance, Vector.empty)
            .collect { case (declaration, _, range) if declaration.id == outputId => range }
        case TerminalIntervalSelector.RootOutsideLocalOutput(outputId)                                           =>
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
        case TerminalIntervalSelector.WholeProduction                                                            =>
          position(instance) match
            case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived)
                if range.startOffset < range.endOffset =>
              Vector(range)
            case _ => Vector.empty
        case TerminalIntervalSelector.ChildGap(startRole, endRole)                                               =>
          childGapIntervals(instance, startRole, endRole)
        case TerminalIntervalSelector.ChildSeparators(roleId)                                                    =>
          childSeparatorIntervals(instance, roleId)
        case TerminalIntervalSelector.BeforeChild(roleId)                                                        =>
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
        case TerminalIntervalSelector.AfterChild(roleId)                                                         =>
          (
            position(instance),
            compilerChildren
              .getOrElse(instance, Vector.empty)
              .reverseIterator
              .collectFirst { case (`roleId`, _, child) => position(child) }
          ) match
            case (
                  ParserNodePosition.Positioned(parent, _, ParserPositionProvenance.SourceDerived),
                  Some(ParserNodePosition.Positioned(child, _, _))
                ) if child.endOffset <= parent.endOffset =>
              Vector(PcSourceRange(child.endOffset, parent.endOffset))
            case _ => Vector.empty
        case TerminalIntervalSelector.BeforeChildOutputs(roleId)                                                 =>
          val outputs = childOutputRanges(instance, roleId)
          position(instance) match
            case ParserNodePosition.Positioned(parent, _, _) if parent.startOffset <= outputs.head.startOffset =>
              Vector(PcSourceRange(parent.startOffset, outputs.head.startOffset))
            case _                                                                                             => Vector.empty
        case TerminalIntervalSelector.ChildOutputGap(startRole, endRole)                                         =>
          val starts = childOutputRanges(instance, startRole)
          val ends   = childOutputRanges(instance, endRole)
          if starts.last.endOffset <= ends.head.startOffset then
            Vector(PcSourceRange(starts.last.endOffset, ends.head.startOffset))
          else Vector.empty
        case TerminalIntervalSelector.ChildOutputSeparators(roleId)                                              =>
          if compilerChildren.getOrElse(instance, Vector.empty).exists(_._1 == roleId) then
            childOutputRanges(instance, roleId, requireOutput = false)
              .sliding(2)
              .collect:
                case Vector(left, right) => PcSourceRange(left.endOffset, right.startOffset)
              .toVector
          else Vector.empty
        case TerminalIntervalSelector.CompilerEndMarkerKeyword                                                   =>
          compilerEndMarker(instance).map(_._2).toVector
        case TerminalIntervalSelector.CompilerScannerToken(kind, occurrence)                                     =>
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
        case TerminalIntervalSelector.SourceDerivedChildToScannerTokenGap(
              _,
              roleId,
              occurrence,
              kind,
              scannerOccurrence
            ) =>
          val containedChild = compilerChildren
            .getOrElse(instance, Vector.empty)
            .collectFirst { case (`roleId`, _, child) =>
              position(child) match
                case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived) => Some(range)
                case _                                                                               => None
            }
            .flatten
          position(instance) match
            case ParserNodePosition.Positioned(production, _, _) =>
              containedChild.toVector
                .filter: child =>
                  production.startOffset <= child.startOffset && child.endOffset <= production.endOffset
                .flatMap: child =>
                  val matches = snapshot.scannerTokens
                    .filter(token =>
                      token.kind == kind && child.endOffset <= token.range.startOffset && token.range.endOffset <= production.endOffset
                    )
                  val target  = scannerOccurrence match
                    case ScannerTokenOccurrence.First => matches.headOption
                    case ScannerTokenOccurrence.Last  => matches.lastOption
                    case _                            => None
                  target
                    .map(token => PcSourceRange(child.endOffset, token.range.startOffset))
                    .filter(range => range.startOffset < range.endOffset)
            case _                                               => Vector.empty
        case TerminalIntervalSelector.CompilerScannerTokenBeforeChildOutputs(kind, roleId)                       =>
          val outputs = childOutputRanges(instance, roleId)
          position(instance) match
            case ParserNodePosition.Positioned(parent, _, _) if parent.startOffset <= outputs.head.startOffset =>
              val interval = PcSourceRange(parent.startOffset, outputs.head.startOffset)
              snapshot.scannerTokens
                .filter(token =>
                  token.kind == kind && interval.startOffset <= token.range.startOffset && token.range.endOffset <= interval.endOffset
                )
                .map(_.range)
            case _                                                                                             => Vector.empty
        case TerminalIntervalSelector.CompilerScannerTokenInChildGap(kind, startRole, endRole)                   =>
          childGapIntervals(instance, startRole, endRole).flatMap: gap =>
            snapshot.scannerTokens
              .filter(token =>
                token.kind == kind && gap.startOffset <= token.range.startOffset && token.range.endOffset <= gap.endOffset
              )
              .map(_.range)
        case TerminalIntervalSelector.CompilerScannerTokenInChildOutputGap(kind, startRole, endRole, occurrence) =>
          val starts = childOutputRanges(instance, startRole)
          val ends   = childOutputRanges(instance, endRole)
          if starts.last.endOffset <= ends.head.startOffset then
            val gap     = PcSourceRange(starts.last.endOffset, ends.head.startOffset)
            val matches = snapshot.scannerTokens
              .filter(token =>
                token.kind == kind && gap.startOffset <= token.range.startOffset && token.range.endOffset <= gap.endOffset
              )
              .map(_.range)
            occurrence match
              case ScannerTokenOccurrence.All   => matches
              case ScannerTokenOccurrence.First => matches.take(1)
              case ScannerTokenOccurrence.Last  => matches.takeRight(1)
          else Vector.empty
        case TerminalIntervalSelector.BalancedScannerTokenAfterChild(
              kind,
              opening,
              closing,
              roleId,
              occurrence
            ) =>
          val childEnd = compilerChildren
            .getOrElse(instance, Vector.empty)
            .collectFirst { case (`roleId`, _, child) => position(child) }
            .collect { case ParserNodePosition.Positioned(range, _, _) => range.endOffset }
          (position(instance), childEnd) match
            case (ParserNodePosition.Positioned(parent, _, _), Some(start)) =>
              val tokens       = snapshot.scannerTokens.filter(token =>
                start <= token.range.startOffset && token.range.endOffset <= parent.endOffset
              )
              val openingIndex = tokens.indexWhere(_.kind == opening)
              if openingIndex < 0 then Vector.empty
              else
                var depth   = 0
                var closed  = false
                val matches = Vector.newBuilder[PcSourceRange]
                tokens
                  .drop(openingIndex)
                  .iterator
                  .takeWhile(_ => !closed)
                  .foreach: token =>
                    if token.kind == opening then depth += 1
                    if token.kind == kind && depth == 1 then matches += token.range
                    if token.kind == closing then
                      depth -= 1
                      closed = depth == 0
                val values  = if closed then matches.result() else Vector.empty
                occurrence match
                  case ScannerTokenOccurrence.All   => values
                  case ScannerTokenOccurrence.First => values.headOption.toVector
                  case ScannerTokenOccurrence.Last  => values.lastOption.toVector
            case _                                                          => Vector.empty
        case TerminalIntervalSelector.BalancedKeywordBeforeFirstChild(
              opening,
              closing,
              precedingRoleId,
              childRoleId
            ) =>
          val precedingEnd    = compilerChildren
            .getOrElse(instance, Vector.empty)
            .collectFirst { case (`precedingRoleId`, _, child) => position(child) }
            .collect { case ParserNodePosition.Positioned(range, _, _) => range.endOffset }
          val firstChildStart = compilerChildren
            .getOrElse(instance, Vector.empty)
            .collectFirst { case (`childRoleId`, _, child) => position(child) }
            .collect { case ParserNodePosition.Positioned(range, _, _) => range.startOffset }
          val targetSurface   = terminal.target match
            case TerminalLeafTarget.Token(surfaceId, None) => Some(surfaceId)
            case _                                         => None
          (position(instance), precedingEnd, firstChildStart, targetSurface) match
            case (
                  ParserNodePosition.Positioned(parent, _, ParserPositionProvenance.SourceDerived),
                  Some(after),
                  Some(before),
                  Some(surfaceId)
                ) =>
              val atoms        = lexicalAtomsWithin(parent).filter(atom => after <= atom.start && atom.end <= before)
              val openingIndex = atoms.indexWhere(_.kind == opening)
              val targetType   = PlannedScala3Lexer.keywordTokenType(surfaceId)
              if openingIndex < 0 || targetType.isEmpty then Vector.empty
              else
                var depth   = 0
                var closed  = false
                val matches = Vector.newBuilder[PcSourceRange]
                atoms
                  .drop(openingIndex)
                  .iterator
                  .takeWhile(_ => !closed)
                  .foreach: atom =>
                    if atom.kind == opening then depth += 1
                    if depth == 1 && PlannedScala3Lexer.keywordTokenType(snapshot.sourceText, atom) == targetType then
                      matches += PcSourceRange(atom.start, atom.end)
                    if atom.kind == closing then
                      depth -= 1
                      closed = depth == 0
                val values  = matches.result()
                if closed || values.size != 1 then Vector.empty else values
            case _ => Vector.empty
        case TerminalIntervalSelector.BalancedPrefixBeforeFirstChild(opening, precedingRoleId, childRoleId)      =>
          val precedingEnd    = compilerChildren
            .getOrElse(instance, Vector.empty)
            .collectFirst { case (`precedingRoleId`, _, child) => position(child) }
            .collect { case ParserNodePosition.Positioned(range, _, _) => range.endOffset }
          val firstChildStart = compilerChildren
            .getOrElse(instance, Vector.empty)
            .collectFirst { case (`childRoleId`, _, child) => position(child) }
            .collect { case ParserNodePosition.Positioned(range, _, _) => range.startOffset }
          (position(instance), precedingEnd, firstChildStart) match
            case (
                  ParserNodePosition.Positioned(parent, _, ParserPositionProvenance.SourceDerived),
                  Some(after),
                  Some(before)
                ) =>
              lexicalAtomsWithin(parent)
                .find(atom => atom.start >= after && atom.kind == opening)
                .filter(_.end <= before)
                .map(open => PcSourceRange(open.end, before))
                .filter(range => range.startOffset < range.endOffset)
                .toVector
            case _ => Vector.empty
        case TerminalIntervalSelector.BalancedSuffixAfterLastChild(
              opening,
              closing,
              precedingRoleId,
              childRoleId
            ) =>
          val precedingEnd = compilerChildren
            .getOrElse(instance, Vector.empty)
            .collectFirst { case (`precedingRoleId`, _, child) => position(child) }
            .collect { case ParserNodePosition.Positioned(range, _, _) => range.endOffset }
          val lastChildEnd = compilerChildren
            .getOrElse(instance, Vector.empty)
            .reverseIterator
            .collectFirst { case (`childRoleId`, _, child) => position(child) }
            .collect { case ParserNodePosition.Positioned(range, _, _) => range.endOffset }
          (position(instance), precedingEnd, lastChildEnd) match
            case (
                  ParserNodePosition.Positioned(parent, _, ParserPositionProvenance.SourceDerived),
                  Some(before),
                  Some(after)
                ) =>
              val atoms         = lexicalAtomsWithin(parent)
              val openingIndex  = atoms.indexWhere(atom => atom.start >= before && atom.kind == opening)
              val matchingClose =
                if openingIndex < 0 then None
                else
                  var depth = 0
                  atoms
                    .drop(openingIndex)
                    .iterator
                    .map: atom =>
                      if atom.kind == opening then depth += 1
                      if atom.kind == closing then depth -= 1
                      atom -> depth
                    .find((atom, depth) => atom.kind == closing && depth == 0)
                    .map(_._1)
              matchingClose
                .filter(_.end == parent.endOffset)
                .map(close => PcSourceRange(after, close.start))
                .filter(range => range.startOffset < range.endOffset)
                .toVector
            case _ => Vector.empty
        case other                                                                                               =>
          break(Left(WholeFilePlanningFailure.UnsupportedTerminalSelector(production.id, terminal.id, other)))

      def terminalTokenRanges(
          terminal: TerminalDeclaration,
          intervals: Vector[PcSourceRange]
      ): Vector[PcSourceRange] = (terminal.target, terminal.selector) match
        case (TerminalLeafTarget.Token(_, Some(expected)), _)                                                        =>
          intervals.flatMap: interval =>
            lexicalAtomsWithin(interval)
              .filter(atom => snapshot.sourceText.substring(atom.start, atom.end) == expected)
              .map(atom => PcSourceRange(atom.start, atom.end))
        case (TerminalLeafTarget.Token(_, None), _: TerminalIntervalSelector.CompilerScannerToken)                   => intervals
        case (TerminalLeafTarget.Token(_, None), _: TerminalIntervalSelector.CompilerScannerTokenBeforeChildOutputs) =>
          intervals
        case (TerminalLeafTarget.Token(_, None), _: TerminalIntervalSelector.CompilerScannerTokenInChildGap)         =>
          intervals
        case (TerminalLeafTarget.Token(_, None), _: TerminalIntervalSelector.CompilerScannerTokenInChildOutputGap)   =>
          intervals
        case (TerminalLeafTarget.Token(_, None), _: TerminalIntervalSelector.BalancedScannerTokenAfterChild)         =>
          intervals
        case (TerminalLeafTarget.Token(_, None), _: TerminalIntervalSelector.BalancedKeywordBeforeFirstChild)        =>
          intervals
        case _                                                                                                       => Vector.empty

      def terminalLexicalKinds(intervals: Vector[PcSourceRange]): Vector[ClosedSourceLexicalKind] =
        intervals.flatMap: interval =>
          lexicalAtomsWithin(interval).map(_.kind)

      def terminalLexicalContractSatisfied(
          terminal: TerminalDeclaration,
          intervals: Vector[PcSourceRange]
      ): Boolean =
        val triviaOnly = (terminal.selector match
          case _: TerminalIntervalSelector.SourceDerivedChildToScannerTokenGap => true
          case _                                                               => false
        ) || terminal.target == TerminalLeafTarget.Trivia
        if triviaOnly then
          val kinds = terminalLexicalKinds(intervals)
          intervals.isEmpty || kinds.nonEmpty && kinds.forall:
            case ClosedSourceLexicalKind.Whitespace | ClosedSourceLexicalKind.LineComment |
                ClosedSourceLexicalKind.BlockComment =>
              true
            case _ => false
        else
          terminal.target match
            case TerminalLeafTarget.Separator =>
              val kinds = terminalLexicalKinds(intervals)
              intervals.isEmpty || kinds.nonEmpty && kinds.forall:
                case ClosedSourceLexicalKind.Whitespace | ClosedSourceLexicalKind.LineComment |
                    ClosedSourceLexicalKind.BlockComment | ClosedSourceLexicalKind.Semicolon =>
                  true
                case _ => false
            case _                            => true

      val knownEvidenceRoles  = (
        outputRows.valuesIterator.flatten.map(_._1.outputRoleId) ++
          participating.iterator.flatMap(instance => activeTerminals(instance).map(_.outputRoleId))
      ).toSet
      val requestedBoundaries = Vector.newBuilder[(PsiOutputRoleId, Int)]
      outputRows.valuesIterator.flatten.foreach: (declaration, _, range) =>
        requestedBoundaries += declaration.outputRoleId -> range.startOffset
        requestedBoundaries += declaration.outputRoleId -> range.endOffset
      participating.foreach: instance =>
        val production = selected(instance)
        activeTerminals(instance).foreach: terminal =>
          val intervals = terminalIntervals(instance, production, terminal)
          val ranges    = terminal.target match
            case _: TerminalLeafTarget.Token => terminalTokenRanges(terminal, intervals)
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
      val participatingClaimOwners                                                                        = participating
        .groupMap(instance => instance.kind -> instance.valueId)(identity)
      val activeClaimOwners                                                                               = participatingClaimOwners
      val activeOrder                                                                                     = participating.zipWithIndex.toMap
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
      def transferredOwners(claim: SourceClaim): Vector[ProductionInstanceId]                             =
        participation
          .transferredOwner(claim)
          .fold(
            failure => break(Left(WholeFilePlanningFailure.InvalidProductionParticipation(failure))),
            _.toVector
          )
      def claimOwners(atom: SourceAtom, owners: Map[(InventoryKind, Long), Vector[ProductionInstanceId]]) =
        atom.claims
          .flatMap:
            case claim @ SourceClaim.Node(id, _)       =>
              owners.getOrElse(InventoryKind.Node -> id, Vector.empty) ++ transferredOwners(claim)
            case claim @ SourceClaim.Positioned(id, _) =>
              owners.getOrElse(InventoryKind.Positioned -> id, Vector.empty) ++ transferredOwners(claim)
            case SourceClaim.Diagnostic(_)             => Vector.empty
          .distinct
          .sortBy(ownershipEntry)
      def stableDistinctSourceAtoms(atoms: Vector[SourceAtom]): Vector[SourceAtom]                        =
        val seen   = collection.mutable.HashSet.empty[SourceAtom]
        val result = Vector.newBuilder[SourceAtom]
        atoms.foreach: atom =>
          if seen.add(atom) then result += atom
        result.result()
      val deepestOutputClaimOwners                                                                        = planningAtoms
        .map: atom =>
          val owners  = claimOwners(atom, outputClaimOwners).filter(instance =>
            outputRows
              .getOrElse(instance, Vector.empty)
              .exists((_, _, range) => range.startOffset <= atom.start && atom.end <= range.endOffset)
          )
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
                declaration.range.isInstanceOf[OutputRangeDeclaration.BalancedLexicalRangeBeforeChildOutput] ||
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
      val distinctPlanningAtoms                                                                           = stableDistinctSourceAtoms(planningAtoms)
      val planningAtomRangeIndex                                                                          = new SourceOrderedRangeIndex(
        distinctPlanningAtoms,
        _.start,
        _.end,
        workObserver.terminalCandidateEntries
      )
      val sourceExtendedAtoms                                                                             = sourceExtendedRanges.map: (instance, ranges) =>
        instance -> stableDistinctSourceAtoms(
          ranges
            .flatMap(planningAtomRangeIndex.within)
            .filter: atom =>
              claimOwners(atom, activeClaimOwners) match
                case Vector() => true
                case owners   => owners.forall(owner => owner == instance || isAncestor(owner, instance))
        )
      val sourceExtendedAtomSets                                                                          = sourceExtendedAtoms.view
        .mapValues(_.toSet)
        .toMap
      val distinctParentClaimAtoms                                                                        = participating.iterator
        .filter(instance => activeTerminals(instance).exists(_.target == TerminalLeafTarget.Parent))
        .map: instance =>
          val flattened =
            eligibleParentClaimAtoms.getOrElse(instance, Vector.empty) ++
              sourceExtendedAtoms.getOrElse(instance, Vector.empty) ++
              Option.when(instance == root)(unclaimedAtoms).getOrElse(Vector.empty)
          instance -> stableDistinctSourceAtoms(flattened)
        .toMap
      val candidates                                                                                      =
        collection.mutable.Map.empty[SourceAtomId, Vector[PlannedPhysicalLeaf]].withDefaultValue(Vector.empty)
      val resolvedTerminals                                                                               = collection.mutable.LinkedHashSet.empty[(ProductionInstanceId, String)]
      participating.foreach: instance =>
        val production = selected(instance)
        activeTerminals(instance).foreach: terminal =>
          val intervals            = terminalIntervals(instance, production, terminal)
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
          val tokenRanges          = terminalTokenRanges(terminal, intervals).toSet
          val gapClaim             = terminal.selector match
            case _: TerminalIntervalSelector.SourceDerivedChildToScannerTokenGap => true
            case _: TerminalIntervalSelector.ChildGap                            => true
            case _                                                               => false
          val extendedCandidateSet = sourceExtendedAtomSets.getOrElse(instance, Set.empty)
          val terminalCandidates   = terminal.target match
            case TerminalLeafTarget.Parent => distinctParentClaimAtoms.getOrElse(instance, Vector.empty)
            case _                         => Vector.empty
          val atoms                = intervals.flatMap: interval =>
            val candidates = (terminal.target, gapClaim) match
              case (TerminalLeafTarget.Parent, false) =>
                workObserver.terminalCandidateEntries(terminalCandidates.size)
                terminalCandidates.filter(atom => interval.startOffset <= atom.start && atom.end <= interval.endOffset)
              case _                                  => planningAtomRangeIndex.within(interval)
            candidates
              .filter(atom =>
                gapClaim || (terminal.target == TerminalLeafTarget.Parent && !gapClaim) ||
                  atom.claims.exists(claims(instance, _)) || extendedCandidateSet(atom)
              )
              .filter: atom =>
                terminal.target match
                  case TerminalLeafTarget.Token(_, Some(_)) => tokenRanges(PcSourceRange(atom.start, atom.end))
                  case _                                    => true
          val occurrences          = terminal.target match
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
          case claim @ SourceClaim.Node(id, _)       =>
            activeClaimOwners.getOrElse(InventoryKind.Node -> id, Vector.empty) ++ transferredOwners(claim)
          case claim @ SourceClaim.Positioned(id, _) =>
            activeClaimOwners.getOrElse(InventoryKind.Positioned -> id, Vector.empty) ++ transferredOwners(claim)
          case SourceClaim.Diagnostic(_)             => Vector.empty
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
            val terminals = activeTerminals(instance).collect:
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
        .plan(refinedEvidence, knownEvidenceRoles, atomOwnership, eventOwnership, workObserver)
        .fold(
          failures => break(Left(WholeFilePlanningFailure.FinalSourceEvidenceFailures(failures))),
          identity
        )
      val inactiveOutputs                                                                                 =
        mergedOutputRoots.keySet ++ suppressedChildRoots
      val targets                                                                                         = participating.flatMap: instance =>
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
        val terminals  = activeTerminals(instance).collect:
          case TerminalDeclaration(id, _, TerminalLeafTarget.Token(surfaceId, _), _, outputRoleId, _)
              if resolvedTerminals(instance -> id) =>
            PlannedTargetAssertion(
              TargetAssertionOwner.Terminal(instance, id),
              PlannedTargetIdentity.TokenRole(outputRoleId, surfaceId),
              TargetAssertionKind.Token
            )
        composites ++ terminals
      val accessors                                                                                       = participating.flatMap(instance =>
        outputRows(instance).flatMap:
          case (_, id, _) if inactiveOutputs(id) => Vector.empty
          case (declaration, id, _)              =>
            declaration.accessors.map(obligation =>
              PlannedAccessorAssertion(id, obligation.surfaceId, obligation.required, obligation.surfaceKind)
            )
      )
      val stubs                                                                                           = participating.flatMap: instance =>
        outputRows(instance).flatMap:
          case (_, id, _) if inactiveOutputs(id) => Vector.empty
          case (declaration, id, _)              =>
            declaration.persistence match
              case PersistenceObligations.NotApplicable                                   => Vector.empty
              case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
                Vector(PlannedStubAssertion(id, stub, serializer, indices, navigation))
      val navigation                                                                                      = participating.flatMap: instance =>
        outputRows(instance).flatMap:
          case (_, id, _) if inactiveOutputs(id) => Vector.empty
          case (declaration, id, _)              =>
            declaration.navigation.map(PlannedNavigationAssertion(id, _))
      recoveryOwnershipRecords(snapshot, composites, participating, resolvedRealizations, selected, isAncestor) match
        case Left(failure)             => Left(failure)
        case Right(recoveryOwnerships) =>
          val plan = WholeFileProductionPlan(
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
            navigation,
            participation.absorptions,
            realizationSelections.values.toVector,
            recoveryOwnerships
          )
          Right(plan)

  private def claims(instance: ProductionInstanceId, claim: SourceClaim): Boolean = (instance.kind, claim) match
    case (InventoryKind.Node, SourceClaim.Node(id, _))             => instance.valueId == id
    case (InventoryKind.Positioned, SourceClaim.Positioned(id, _)) => instance.valueId == id
    case _                                                         => false

  private def recoveryOwnershipRecords(
      snapshot: ParserSyntaxSnapshot,
      composites: Vector[PlannedComposite],
      participating: Vector[ProductionInstanceId],
      resolvedRealizations: collection.Map[ProductionInstanceId, OutputRealization],
      selected: ProductionInstanceId => Scala3PsiProduction,
      isAncestor: (ProductionInstanceId, ProductionInstanceId) => Boolean
  ): Either[WholeFilePlanningFailure, Vector[PlannedRecoveryOwnership]] =
    val wrappers: Vector[(PlannedComposite, ParserDiagnosticSeverity, String)] =
      participating.flatMap: instance =>
        val maybe = Option(selected(instance)).flatMap: production =>
          production.recovery match
            case RecoveryPolicy.DiagnosticBound(severity, alternatives) =>
              resolvedRealizations
                .get(instance)
                .filter(realization => alternatives.contains(realization.id))
                .flatMap: realization =>
                  val rootDeclaration = realization.template.composites.find(_.parentId.isEmpty)
                  rootDeclaration.flatMap: declaration =>
                    composites
                      .find: composite =>
                        composite.instance.origin == instance &&
                          composite.instance.localOutputId == declaration.id
                      .map(composite => (composite, severity, realization.id))
            case _                                                      => None
        maybe.toVector
    if wrappers.isEmpty then Right(Vector.empty)
    else
      val records   = Vector.newBuilder[PlannedRecoveryOwnership]
      val failureOr = wrappers
        .groupBy(_._1.range.endOffset)
        .toVector
        .sortBy(_._1)
        .foldLeft[Option[WholeFilePlanningFailure]](None): (failure, offsetGroup) =>
          val (offset, group) = offsetGroup
          failure.orElse {
            // Recovery diagnostics are parser-synthesized (measured Synthetic for missing-close);
            // the provenance label is trusted only while the parser publishes it.
            val provenanceTrusted =
              snapshot.capabilities.diagnosticPositionProvenance == ParserCapabilityStatus.Available
            val atOffset          = snapshot.diagnostics.zipWithIndex.filter: (diagnostic, _) =>
              provenanceTrusted && diagnostic.severity == group.head._2 && diagnostic.position.exists: position =>
                position.provenance == ParserDiagnosticPositionProvenance.Synthetic &&
                  position.range.startOffset == offset && position.point == offset &&
                  position.range.startOffset <= position.range.endOffset &&
                  position.range.endOffset <= snapshot.sourceLength
            atOffset match
              case Vector((diagnostic, ordinal)) =>
                val ownerOption: Option[(PlannedComposite, ParserDiagnosticSeverity, String)] =
                  group.collectFirst {
                    case wrapper @ (composite, _, _) if group.forall(other => {
                          val ownerOrigin = composite.instance.origin
                          val otherOrigin = other._1.instance.origin
                          otherOrigin == ownerOrigin || isAncestor(otherOrigin, ownerOrigin)
                        }) =>
                      wrapper
                  }
                ownerOption match
                  case Some((ownerComposite, _, _))
                      if group.length == 1 || group
                        .forall(other => isAncestor(other._1.instance.origin, ownerComposite.instance.origin)) =>
                    group.foreach: (composite, severity, alternativeId) =>
                      records += PlannedRecoveryOwnership(
                        composite.instance.origin,
                        composite.instance.localOutputId,
                        ordinal,
                        severity,
                        diagnostic.position.fold(
                          ParserDiagnosticPositionProvenance.Synthetic
                        )(_.provenance),
                        diagnostic.position.fold(PcSourceRange(offset, offset))(_.range),
                        diagnostic.position.fold(offset)(_.point),
                        offset,
                        alternativeId,
                        sharing = (composite ne ownerComposite) && isAncestor(
                          composite.instance.origin,
                          ownerComposite.instance.origin
                        )
                      )
                    None
                  case _ =>
                    val representative = group.minBy(_._1.range.startOffset)
                    Some(
                      WholeFilePlanningFailure.UnassignedRecoveryDiagnostic(
                        representative._1.instance.origin,
                        representative._3,
                        offset,
                        representative._2
                      )
                    )
              case Vector()                      =>
                // A selected recovery realization without its exact clamp-point diagnostic is
                // an unowned recovery event and must fail planning.
                val representative = group.minBy(_._1.range.startOffset)
                Some(
                  WholeFilePlanningFailure.UnassignedRecoveryDiagnostic(
                    representative._1.instance.origin,
                    representative._3,
                    offset,
                    representative._2
                  )
                )
              case _                             =>
                val representative = group.minBy(_._1.range.startOffset)
                Some(
                  WholeFilePlanningFailure.UnassignedRecoveryDiagnostic(
                    representative._1.instance.origin,
                    representative._3,
                    offset,
                    representative._2
                  )
                )
          }
      failureOr.toLeft(records.result())

  private def accepts(cardinality: ChildCardinality, actual: Int): Boolean = cardinality match
    case ChildCardinality.ExactlyOne                 => actual == 1
    case ChildCardinality.Optional                   => actual <= 1
    case ChildCardinality.Repeated(minimum, maximum) => actual >= minimum && maximum.forall(actual <= _)
    case ChildCardinality.Grouped(minimum, maximum)  => actual >= minimum && maximum.forall(actual <= _)

  private def accepts(cardinality: OccurrenceCardinality, actual: Int): Boolean = cardinality match
    case OccurrenceCardinality.ExactlyOne                 => actual == 1
    case OccurrenceCardinality.Optional                   => actual <= 1
    case OccurrenceCardinality.Repeated(minimum, maximum) => actual >= minimum && maximum.forall(actual <= _)
