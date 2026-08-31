package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.{ParserDiagnostic, ParserDiagnosticSeverity, ParserSyntaxSnapshot}
import com.intellij.lang.{PsiBuilder, WhitespacesBinders}
import com.intellij.psi.tree.IElementType

import java.util.ArrayDeque
import scala.collection.mutable.ArrayBuffer

/** Emits a previously validated whole-file plan without interpreting compiler productions. */
private[metallurgy] object DotcPsiProducer:

  def parse(
      fileElementType: IElementType,
      builder: PsiBuilder,
      plan: WholeFileProductionPlan,
      bindings: NativePsiElementBindings
  ): Boolean = parseResult(fileElementType, builder, plan, bindings).isRight

  def parseResult(
      fileElementType: IElementType,
      builder: PsiBuilder,
      plan: WholeFileProductionPlan,
      bindings: NativePsiElementBindings
  ): Either[String, Unit] =
    validate(builder, plan, bindings) match
      case None         =>
        val targets              = plan.targetAssertions.collect:
          case PlannedTargetAssertion(
                TargetAssertionOwner.Composite(owner),
                PlannedTargetIdentity.OutputRole(outputRoleId),
                _
              ) =>
            owner -> outputRoleId
        val targetRoles          = targets.toMap
        val remaps               = plan.physicalLeafOwnership.collect:
          case PlannedPhysicalLeaf(_, start, _, _, _, _, TerminalLeafTarget.Token(surfaceId, _)) =>
            start -> bindings.elementTypes(surfaceId)
        val byParent             = plan.composites
          .flatMap(parent => parent.children.map(child => child.child -> parent.instance))
          .toMap
        val lexicalAtomsByEnd    = plan.lexicalContract.atoms.groupBy(_.end).view.mapValues(_.head).toMap
        val physicalByComposite  = plan.physicalLeafOwnership
          .collect:
            case leaf @ PlannedPhysicalLeaf(_, _, _, PhysicalLeafOwner.Composite(owner), _, _, _) => owner -> leaf
          .groupMap(_._1)(_._2)
        val trailingTriviaOwners = plan.composites.iterator
          .filter(composite => targetRoles.get(composite.instance).forall(_ != PsiOutputRoleId.ExpressionPayload))
          .filter: composite =>
            lexicalAtomsByEnd
              .get(composite.range.endOffset)
              .exists: atom =>
                (atom.kind == ClosedSourceLexicalKind.Whitespace ||
                  atom.kind == ClosedSourceLexicalKind.LineComment ||
                  atom.kind == ClosedSourceLexicalKind.BlockComment) &&
                  physicalByComposite
                    .getOrElse(composite.instance, Vector.empty)
                    .exists: leaf =>
                      leaf.start <= atom.start && atom.end <= leaf.end
          .map(_.instance)
          .toSet
        val roots                = plan.composites.filterNot(value => byParent.contains(value.instance))
        val byId                 = plan.composites.map(value => value.instance -> value).toMap
        val tokenRemaps          = remaps.toMap
        val root                 = builder.mark()
        roots
          .sortBy(value => (value.range.startOffset, value.range.endOffset, value.instance.toString))
          .foreach(
            emit(_, byId, targetRoles, bindings, builder, tokenRemaps, trailingTriviaOwners, plan.recoveryOwnerships)
          )
        advanceTo(builder.getOriginalText.length, builder, tokenRemaps)
        root.done(fileElementType)
        Right(())
      case Some(reason) => Left(reason)

  def emitClosedFile(
      fileElementType: IElementType,
      builder: PsiBuilder,
      diagnostics: Iterable[ParserDiagnostic] = Vector.empty
  ): Either[String, Unit] =
    val sourceLength = builder.getOriginalText.length
    val errors       = diagnostics.zipWithIndex.foldLeft[Either[String, Vector[ClosedDiagnostic]]](Right(Vector.empty)):
      case (failure @ Left(_), _)                                                                              => failure
      case (Right(values), (ParserDiagnostic(ParserDiagnosticSeverity.Error, message, Some(position)), index)) =>
        Right(values :+ ClosedDiagnostic(position.range.startOffset, position.range.endOffset, index, message))
      case (Right(_), (ParserDiagnostic(ParserDiagnosticSeverity.Error, message, None), index))                =>
        Left(s"parser diagnostic $index has no exact source range: $message")
      case (result, _)                                                                                         => result
    val forest       = errors.flatMap(validateClosedDiagnostics(_, sourceLength))
    forest match
      case Right(roots) =>
        val root = builder.mark()
        emitDiagnostics(roots, builder)
        advanceTo(sourceLength, builder)
        root.done(fileElementType)
        Right(())
      case Left(reason) =>
        val root = builder.mark()
        advanceTo(sourceLength, builder)
        root.done(fileElementType)
        Left(reason)

  private[psiproducer] def validateClosedDiagnostics(
      diagnostics: Vector[ClosedDiagnostic],
      sourceLength: Int
  ): Either[String, Vector[DiagnosticNode]] =
    diagnostics
      .find(value => value.start < 0 || value.start > value.end || value.end > sourceLength)
      .toLeft(())
      .left
      .map(value => s"invalid parser diagnostic range ${value.range} for source length $sourceLength")
      .flatMap(_ => nestedDiagnostics(diagnostics))

  private[psiproducer] def nestedDiagnostics(
      diagnostics: Vector[ClosedDiagnostic]
  ): Either[String, Vector[DiagnosticNode]] =
    val roots                   = ArrayBuffer.empty[DiagnosticNode]
    val stack                   = ArrayBuffer.empty[DiagnosticNode]
    val sorted                  = diagnostics.sortBy(value => (value.start, -value.end, value.index))
    var index                   = 0
    var failure: Option[String] = None
    while index < sorted.size && failure.isEmpty do
      val diagnostic = sorted(index)
      while stack.nonEmpty && !stack.last.contains(diagnostic) do
        val previous = stack.remove(stack.size - 1)
        if diagnostic.start < previous.diagnostic.end then
          failure = Some(s"crossing parser diagnostic ranges: ${previous.range} and ${diagnostic.range}")
      if failure.isEmpty then
        val current = DiagnosticNode(diagnostic, ArrayBuffer.empty)
        stack.lastOption match
          case Some(parent) => parent.children += current; ()
          case None         => roots += current; ()
        if diagnostic.start < diagnostic.end then stack += current
      index += 1
    failure.toLeft(roots.toVector)

  private def emitDiagnostics(roots: Vector[DiagnosticNode], builder: PsiBuilder): Unit =
    val pending = new ArrayDeque[DiagnosticEmitEvent]()
    roots.reverseIterator.foreach(root => pending.addFirst(DiagnosticEmitEvent.Enter(root)))
    while !pending.isEmpty do
      pending.removeFirst() match
        case DiagnosticEmitEvent.Enter(node)                =>
          val diagnostic = node.diagnostic
          advanceTo(diagnostic.start, builder)
          val marker     = builder.mark()
          if diagnostic.start == diagnostic.end then marker.error(diagnostic.message)
          else
            pending.addFirst(DiagnosticEmitEvent.Exit(marker, diagnostic.end, diagnostic.message))
            node.children.reverseIterator.foreach(child => pending.addFirst(DiagnosticEmitEvent.Enter(child)))
        case DiagnosticEmitEvent.Exit(marker, end, message) =>
          advanceTo(end, builder)
          marker.error(message)

  private[psiproducer] final case class ClosedDiagnostic(
      start: Int,
      end: Int,
      index: Int,
      message: String
  ):
    def range: String = s"[$start,$end)"

  private[psiproducer] final case class DiagnosticNode(
      diagnostic: ClosedDiagnostic,
      children: ArrayBuffer[DiagnosticNode]
  ):
    def contains(other: ClosedDiagnostic): Boolean =
      diagnostic.start <= other.start && other.end <= diagnostic.end
    def range: String                              = diagnostic.range

  private enum DiagnosticEmitEvent:
    case Enter(node: DiagnosticNode)
    case Exit(marker: PsiBuilder.Marker, end: Int, message: String)

  private[psiproducer] def emit(
      composite: PlannedComposite,
      byId: Map[CompositeInstanceId, PlannedComposite],
      targets: Map[CompositeInstanceId, PsiOutputRoleId],
      bindings: NativePsiElementBindings,
      builder: PsiBuilder,
      tokenRemaps: Map[Int, IElementType] = Map.empty,
      trailingTriviaOwners: Set[CompositeInstanceId] = Set.empty,
      recoveryOwnerships: Vector[PlannedRecoveryOwnership] = Vector.empty
  ): Unit =
    val pending = new ArrayDeque[EmitEvent]()
    pending.addFirst(EmitEvent.Enter(composite))
    while !pending.isEmpty do
      pending.removeFirst() match
        case EmitEvent.Enter(current)                         =>
          val (from, to) = range(current)
          advanceTo(from, builder, tokenRemaps)
          val marker     = builder.mark()
          pending.addFirst(EmitEvent.Exit(marker, current, to, bindings.outputRoles(targets(current.instance))))
          current.children.reverseIterator
            .flatMap(child => byId.get(child.child))
            .foreach(child => pending.addFirst(EmitEvent.Enter(child)))
        case EmitEvent.Exit(marker, current, to, elementType) =>
          advanceTo(to, builder, tokenRemaps)
          recoveryOwnerships
            .find(ownership =>
              !ownership.sharing && ownership.owner == current.instance.origin && ownership.errorOffset == to
            )
            .foreach: ownership =>
              val errorMarker = builder.mark()
              errorMarker.error(ownership.alternativeId)
          if trailingTriviaOwners(current.instance) then
            marker.setCustomEdgeTokenBinders(null, WhitespacesBinders.GREEDY_RIGHT_BINDER)
          marker.done(elementType)

  private def validate(
      builder: PsiBuilder,
      plan: WholeFileProductionPlan,
      bindings: NativePsiElementBindings
  ): Option[String] =
    val source                                                          = builder.getOriginalText
    val length                                                          = source.length
    val leaves                                                          = plan.physicalLeafOwnership.sortBy(_.start)
    val ids                                                             = plan.composites.map(_.instance)
    val composite                                                       = ids.toSet
    val targets                                                         = plan.targetAssertions.collect {
      case value @ PlannedTargetAssertion(
            TargetAssertionOwner.Composite(_),
            _,
            _
          ) =>
        value
    }
    val targetOwners                                                    = targets.map(_.owner).collect { case TargetAssertionOwner.Composite(owner) => owner }.toSet
    val terminalTargets                                                 = plan.targetAssertions.collect:
      case value @ PlannedTargetAssertion(TargetAssertionOwner.Terminal(_, _), _, _) => value
    val tokenLeaves                                                     = leaves.collect:
      case leaf @ PlannedPhysicalLeaf(_, _, _, _, _, _, _: TerminalLeafTarget.Token) => leaf
    val terminalTargetsByOwner                                          = terminalTargets.groupBy(_.owner)
    val tokenLeavesByOwner                                              =
      tokenLeaves.groupBy(leaf => TargetAssertionOwner.Terminal(leaf.sourceOwner, leaf.terminalId))
    val accessorsByOwner                                                = plan.accessorAssertions.groupBy(_.owner)
    val stubsByOwner                                                    = plan.stubAssertions.map(assertion => assertion.owner -> assertion).toMap
    val navigationByOwner                                               = plan.navigationAssertions.map(assertion => assertion.owner -> assertion.obligation).toMap
    val targetRoles                                                     = targets
      .collect:
        case PlannedTargetAssertion(
              TargetAssertionOwner.Composite(instance),
              PlannedTargetIdentity.OutputRole(outputRoleId),
              _
            ) =>
          instance -> outputRoleId
      .toMap
    def tokenSurface(leaf: PlannedPhysicalLeaf): String                 = leaf.target match
      case TerminalLeafTarget.Token(id, _) => id
      case _                               => ""
    def terminalSurface(target: PlannedTargetAssertion): Option[String] = target.targetIdentity match
      case PlannedTargetIdentity.TokenRole(_, targetSurfaceId) => Some(targetSurfaceId)
      case _                                                   => None
    val terminalTargetMismatch                                          =
      terminalTargets.exists(value => value.kind != TargetAssertionKind.Token || terminalSurface(value).isEmpty) ||
        terminalTargetsByOwner.values.exists(_.size != 1) ||
        tokenLeaves.exists(leaf =>
          terminalTargetsByOwner
            .getOrElse(
              TargetAssertionOwner.Terminal(leaf.sourceOwner, leaf.terminalId),
              Vector.empty
            )
            .count(target =>
              target.owner == TargetAssertionOwner.Terminal(leaf.sourceOwner, leaf.terminalId) &&
                terminalSurface(target).contains(tokenSurface(leaf)) && target.kind == TargetAssertionKind.Token
            ) != 1
        ) ||
        terminalTargets.exists(target =>
          terminalSurface(target).forall(surfaceId =>
            !bindings.elementTypes.contains(surfaceId) || !tokenLeavesByOwner
              .getOrElse(target.owner, Vector.empty)
              .exists(leaf =>
                target.owner == TargetAssertionOwner.Terminal(leaf.sourceOwner, leaf.terminalId) &&
                  surfaceId == tokenSurface(leaf)
              )
          )
        )
    val outputContractMismatch                                          = plan.composites.exists: value =>
      targetRoles.get(value.instance).flatMap(bindings.outputContracts.get) match
        case None           => true
        case Some(contract) =>
          val accessors         = accessorsByOwner
            .getOrElse(value.instance, Vector.empty)
            .map(assertion => AccessorObligation(assertion.surfaceId, assertion.required, assertion.surfaceKind))
            .sortBy(obligation => (obligation.surfaceId, obligation.required, obligation.surfaceKind.ordinal))
          val expectedAccessors = contract.accessors
            .sortBy(obligation => (obligation.surfaceId, obligation.required, obligation.surfaceKind.ordinal))
          val stub              = stubsByOwner.get(value.instance)
          val expectedStub      = contract.persistence match
            case PersistenceObligations.NotApplicable                                      => None
            case PersistenceObligations.Required(surface, serializer, indices, navigation) =>
              Some(PlannedStubAssertion(value.instance, surface, serializer, indices, navigation))
          val navigation        = navigationByOwner.get(value.instance)
          accessors != expectedAccessors || stub != expectedStub || navigation != contract.navigation
    val edges                                                           = plan.composites.flatMap(parent => parent.children.map(child => parent.instance -> child.child))
    val children                                                        = edges.groupMap(_._1)(_._2)
    val parents                                                         = edges.groupMap(_._2)(_._1)
    val roots                                                           = ids.filterNot(parents.contains)
    val ranges                                                          = plan.composites.map(value => value.instance -> range(value)).toMap
    val boundaries                                                      = (Vector(0, length) ++ plan.composites.flatMap(value =>
      val (from, to) = range(value)
      Vector(from, to)
    )).toSet
    if ParserSyntaxSnapshot.digest(source.toString) != plan.sourceDigest then Some("source digest differs from plan")
    else if (length > 0 && leaves.isEmpty) || leaves.headOption.exists(_.start != 0) || leaves.lastOption.exists(
        _.end != length
      )
    then Some("plan does not own source")
    else if leaves.exists(leaf => leaf.start < 0 || leaf.start >= leaf.end || leaf.end > length) then
      Some("invalid physical leaf range")
    else if leaves.exists:
        case PlannedPhysicalLeaf(_, start, end, _, _, _, TerminalLeafTarget.Token(_, Some(expected))) =>
          source.subSequence(start, end).toString != expected
        case _                                                                                        => false
    then Some("terminal token text differs from plan")
    else if leaves.sliding(2).exists { case Vector(left, right) => left.end != right.start; case _ => false } then
      Some("physical leaves are not contiguous")
    else if ids.distinct.size != ids.size then Some("composite instances are not unique")
    else if roots.isEmpty && plan.composites.nonEmpty then Some("plan has no structural roots")
    else if edges.distinct.size != edges.size || parents.values.exists(_.size != 1) then
      Some("composite child has duplicate or multiple parent edges")
    else if plan.virtualLayout.nonEmpty then Some("virtual layout emission is unavailable")
    else if plan.composites.exists(_.fieldDispositions.exists(_.kind == FieldDispositionKind.Unsupported)) then
      Some("unsupported field disposition emission is unavailable")
    else if plan.accessorAssertions.exists(value => !composite(value.owner) || value.surfaceId.isEmpty) ||
      plan.accessorAssertions.distinct.size != plan.accessorAssertions.size ||
      plan.stubAssertions.exists(value =>
        !composite(value.owner) || value.stubSurfaceId.isEmpty || value.serializerSurfaceId.isEmpty ||
          value.navigationSurfaceId.isEmpty || value.indexSurfaceIds.exists(_.isEmpty) ||
          value.indexSurfaceIds.distinct.size != value.indexSurfaceIds.size
      ) || plan.stubAssertions.map(_.owner).distinct.size != plan.stubAssertions.size ||
      plan.navigationAssertions.exists(value => !composite(value.owner)) ||
      plan.navigationAssertions.map(_.owner).distinct.size != plan.navigationAssertions.size
    then Some("plan obligations are malformed")
    else if outputContractMismatch then Some("plan obligations do not match the bound output-role contract")
    else if terminalTargetMismatch then Some("terminal token target is not supported")
    else if targetOwners != composite || targets.groupBy(_.owner).exists(_._2.size != 1) ||
      targets.size != plan.composites.size ||
      targets.exists(value =>
        (value.kind != TargetAssertionKind.NativeComposite && value.kind != TargetAssertionKind.CompatibleComposite) ||
          !value.targetIdentity.isInstanceOf[PlannedTargetIdentity.OutputRole]
      ) || targetRoles.values.exists(value => !bindings.outputRoles.contains(value))
    then Some("composite target is not exactly one supported bound composite")
    else if leaves.exists:
        case PlannedPhysicalLeaf(_, _, _, PhysicalLeafOwner.Composite(owner), _, _, _) => !composite(owner)
        case PlannedPhysicalLeaf(_, _, _, PhysicalLeafOwner.FileRoot, _, _, _)         => false
    then Some("physical leaf owner is not an active composite or file root")
    else if leaves.exists:
        case PlannedPhysicalLeaf(_, start, end, PhysicalLeafOwner.Composite(owner), _, _, _) =>
          ranges.get(owner).forall { case (ownerStart, ownerEnd) => start < ownerStart || end > ownerEnd }
        case PlannedPhysicalLeaf(_, _, _, PhysicalLeafOwner.FileRoot, _, _, _)               => false
    then Some("physical leaf is outside its owner composite")
    else if plan.composites.exists(value => value.children.exists(child => !composite(child.child))) then
      Some("planned child is absent")
    else if roots.flatMap(reachable(_, children)).toSet.size != composite.size then
      Some("composite graph has a cycle or unreachable node")
    else if plan.composites.exists(value =>
        val (from, to) = range(value)
        from < 0 || from > to || to > length || value.children
          .flatMap(child => ranges.get(child.child))
          .exists: child =>
            val (childFrom, childTo) = child
            childFrom < from || childTo > to
      )
    then Some("composite containment is invalid")
    else if roots.map(ranges).sortBy(_._1).sliding(2).exists {
        case Vector((_, leftTo), (rightFrom, _)) => leftTo > rightFrom
        case _                                   => false
      }
    then Some("composite roots are unordered or overlapping")
    else if plan.composites.exists(value =>
        val normalized = value.children.sortBy: child =>
          val (start, end) = ranges(child.child)
          (start, end, child.child.toString)
        value.children != normalized || normalized.flatMap(child => ranges.get(child.child)).sliding(2).exists {
          case Vector((_, leftTo), (rightFrom, _)) => leftTo > rightFrom
          case _                                   => false
        }
      )
    then Some("composite children are unordered or overlapping")
    else if !lexerBoundariesAreSafe(boundaries, builder) then Some("composite boundary is not a lexer boundary")
    else if !tokenRangesAreSafe(
        leaves.collect { case leaf if leaf.target.isInstanceOf[TerminalLeafTarget.Token] => leaf },
        builder
      )
    then Some("terminal token target does not cover exactly one lexer token")
    else None

  private def reachable(
      root: CompositeInstanceId,
      children: Map[CompositeInstanceId, Vector[CompositeInstanceId]]
  ): Set[CompositeInstanceId] =
    val pending = new ArrayDeque[CompositeInstanceId]()
    val seen    = collection.mutable.Set.empty[CompositeInstanceId]
    pending.addFirst(root)
    while !pending.isEmpty do
      val current = pending.removeFirst()
      if seen.add(current) then children.getOrElse(current, Vector.empty).reverseIterator.foreach(pending.addFirst)
    seen.toSet

  private def rawTokenStarts(builder: PsiBuilder): Set[Int] =
    var observed = Set(0, builder.getOriginalText.length)
    var index    = 0
    while builder.rawLookup(index) != null do
      observed += builder.rawTokenTypeStart(index)
      index += 1
    observed

  private def lexerBoundariesAreSafe(boundaries: Set[Int], builder: PsiBuilder): Boolean =
    boundaries.subsetOf(rawTokenStarts(builder))

  private def tokenRangesAreSafe(leaves: Vector[PlannedPhysicalLeaf], builder: PsiBuilder): Boolean =
    val ranges    = Vector.newBuilder[(Int, Int)]
    var index     = 0
    while builder.rawLookup(index) != null do
      val start = builder.rawTokenTypeStart(index)
      val end   = Option(builder.rawLookup(index + 1))
        .fold(builder.getOriginalText.length)(_ => builder.rawTokenTypeStart(index + 1))
      ranges += start -> end
      index += 1
    val available = ranges.result().toSet
    leaves.forall(leaf => available(leaf.start -> leaf.end))

  private def range(value: PlannedComposite): (Int, Int) = value.range.startOffset -> value.range.endOffset

  private def advanceTo(offset: Int, builder: PsiBuilder, tokenRemaps: Map[Int, IElementType] = Map.empty): Unit =
    while !builder.eof() && builder.getCurrentOffset < offset do
      tokenRemaps.get(builder.getCurrentOffset).foreach(builder.remapCurrentToken)
      builder.advanceLexer()

  private enum EmitEvent:
    case Enter(composite: PlannedComposite)
    case Exit(marker: PsiBuilder.Marker, composite: PlannedComposite, to: Int, elementType: IElementType)
