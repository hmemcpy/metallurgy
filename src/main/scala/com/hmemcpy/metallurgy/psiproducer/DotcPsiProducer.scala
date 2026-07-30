package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.lang.PsiBuilder
import com.intellij.psi.tree.IElementType

import java.util.ArrayDeque

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
        val targets  = plan.targetAssertions.collect:
          case PlannedTargetAssertion(
                TargetAssertionOwner.Composite(owner),
                PlannedTargetIdentity.OutputRole(outputRoleId),
                _
              ) =>
            owner -> outputRoleId
        val remaps   = plan.physicalLeafOwnership.collect:
          case PlannedPhysicalLeaf(_, start, _, _, _, _, TerminalLeafTarget.Token(surfaceId, _)) =>
            start -> bindings.elementTypes(surfaceId)
        val byParent = plan.composites
          .flatMap(parent => parent.children.map(child => child.child -> parent.instance))
          .toMap
        val roots    = plan.composites.filterNot(value => byParent.contains(value.instance))
        val byId     = plan.composites.map(value => value.instance -> value).toMap
        val root     = builder.mark()
        roots
          .sortBy(value => (value.range.startOffset, value.range.endOffset, value.instance.toString))
          .foreach(emit(_, byId, targets.toMap, bindings, builder, remaps.toMap))
        advanceTo(builder.getOriginalText.length, builder, remaps.toMap)
        root.done(fileElementType)
        Right(())
      case Some(reason) => Left(reason)

  def emitClosedFile(fileElementType: IElementType, builder: PsiBuilder): Unit =
    val root = builder.mark()
    advanceTo(builder.getOriginalText.length, builder)
    root.done(fileElementType)

  private[psiproducer] def emit(
      composite: PlannedComposite,
      byId: Map[CompositeInstanceId, PlannedComposite],
      targets: Map[CompositeInstanceId, PsiOutputRoleId],
      bindings: NativePsiElementBindings,
      builder: PsiBuilder,
      tokenRemaps: Map[Int, IElementType] = Map.empty
  ): Unit =
    val pending = new ArrayDeque[EmitEvent]()
    pending.addFirst(EmitEvent.Enter(composite))
    while !pending.isEmpty do
      pending.removeFirst() match
        case EmitEvent.Enter(current)                =>
          val (from, to) = range(current)
          advanceTo(from, builder, tokenRemaps)
          val marker     = builder.mark()
          pending.addFirst(EmitEvent.Exit(marker, to, bindings.outputRoles(targets(current.instance))))
          current.children.reverseIterator
            .flatMap(child => byId.get(child.child))
            .foreach(child => pending.addFirst(EmitEvent.Enter(child)))
        case EmitEvent.Exit(marker, to, elementType) =>
          advanceTo(to, builder, tokenRemaps)
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
        terminalTargets.groupBy(_.owner).values.exists(_.size != 1) ||
        tokenLeaves.exists(leaf =>
          terminalTargets.count(target =>
            target.owner == TargetAssertionOwner.Terminal(leaf.sourceOwner, leaf.terminalId) &&
              terminalSurface(target).contains(tokenSurface(leaf)) && target.kind == TargetAssertionKind.Token
          ) != 1
        ) ||
        terminalTargets.exists(target =>
          terminalSurface(target).forall(surfaceId =>
            !bindings.elementTypes.contains(surfaceId) || !tokenLeaves.exists(leaf =>
              target.owner == TargetAssertionOwner.Terminal(leaf.sourceOwner, leaf.terminalId) &&
                surfaceId == tokenSurface(leaf)
            )
          )
        )
    val outputContractMismatch                                          = plan.composites.exists: value =>
      targetRoles.get(value.instance).flatMap(bindings.outputContracts.get) match
        case None           => true
        case Some(contract) =>
          val accessors         = plan.accessorAssertions
            .filter(_.owner == value.instance)
            .map(assertion => AccessorObligation(assertion.surfaceId, assertion.required))
            .sortBy(obligation => (obligation.surfaceId, obligation.required))
          val expectedAccessors = contract.accessors.sortBy(obligation => (obligation.surfaceId, obligation.required))
          val stub              = plan.stubAssertions.find(_.owner == value.instance)
          val expectedStub      = contract.persistence match
            case PersistenceObligations.NotApplicable                                      => None
            case PersistenceObligations.Required(surface, serializer, indices, navigation) =>
              Some(PlannedStubAssertion(value.instance, surface, serializer, indices, navigation))
          val navigation        = plan.navigationAssertions.find(_.owner == value.instance).map(_.obligation)
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
        from < 0 || from >= to || to > length || value.children
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
    def loop(pending: List[CompositeInstanceId], seen: Set[CompositeInstanceId]): Set[CompositeInstanceId] =
      pending match
        case Nil          => seen
        case head :: tail =>
          if seen(head) then loop(tail, seen)
          else loop(children.getOrElse(head, Vector.empty).toList ::: tail, seen + head)
    loop(List(root), Set.empty)

  private def lexerBoundariesAreSafe(boundaries: Set[Int], builder: PsiBuilder): Boolean =
    var observed = Set(0, builder.getOriginalText.length)
    var index    = 0
    while builder.rawLookup(index) != null do
      observed += builder.rawTokenTypeStart(index)
      index += 1
    boundaries.subsetOf(observed)

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
    case Exit(marker: PsiBuilder.Marker, to: Int, elementType: IElementType)
