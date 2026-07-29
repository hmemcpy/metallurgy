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
  ): Boolean =
    validate(builder, plan, bindings) match
      case None    =>
        val targets  = plan.targetAssertions.collect:
          case PlannedTargetAssertion(TargetAssertionOwner.Composite(owner), surfaceId, _) => owner -> surfaceId
        val byParent = plan.composites
          .flatMap(parent => parent.children.map(child => child.child -> parent.instance))
          .toMap
        val roots    = plan.composites.filterNot(value => byParent.contains(value.instance))
        val byId     = plan.composites.map(value => value.instance -> value).toMap
        val root     = builder.mark()
        roots
          .sortBy(value => (value.range.startOffset, value.range.endOffset, value.instance.toString))
          .foreach(emit(_, byId, targets.toMap, bindings, builder))
        advanceTo(builder.getOriginalText.length, builder)
        root.done(fileElementType)
        true
      case Some(_) => false

  def emitClosedFile(fileElementType: IElementType, builder: PsiBuilder): Unit =
    val root = builder.mark()
    advanceTo(builder.getOriginalText.length, builder)
    root.done(fileElementType)

  private[psiproducer] def emit(
      composite: PlannedComposite,
      byId: Map[CompositeInstanceId, PlannedComposite],
      targets: Map[CompositeInstanceId, String],
      bindings: NativePsiElementBindings,
      builder: PsiBuilder
  ): Unit =
    val pending = new ArrayDeque[EmitEvent]()
    pending.addFirst(EmitEvent.Enter(composite))
    while !pending.isEmpty do
      pending.removeFirst() match
        case EmitEvent.Enter(current)                =>
          val (from, to) = range(current)
          advanceTo(from, builder)
          val marker     = builder.mark()
          pending.addFirst(EmitEvent.Exit(marker, to, bindings.elementTypes(targets(current.instance))))
          current.children.reverseIterator
            .flatMap(child => byId.get(child.child))
            .foreach(child => pending.addFirst(EmitEvent.Enter(child)))
        case EmitEvent.Exit(marker, to, elementType) =>
          advanceTo(to, builder)
          marker.done(elementType)

  private def validate(
      builder: PsiBuilder,
      plan: WholeFileProductionPlan,
      bindings: NativePsiElementBindings
  ): Option[String] =
    val source       = builder.getOriginalText
    val length       = source.length
    val leaves       = plan.physicalLeafOwnership.sortBy(_.start)
    val ids          = plan.composites.map(_.instance)
    val composite    = ids.toSet
    val targets      = plan.targetAssertions.collect {
      case value @ PlannedTargetAssertion(
            TargetAssertionOwner.Composite(_),
            _,
            _
          ) =>
        value
    }
    val targetOwners = targets.map(_.owner).collect { case TargetAssertionOwner.Composite(owner) => owner }.toSet
    val edges        = plan.composites.flatMap(parent => parent.children.map(child => parent.instance -> child.child))
    val children     = edges.groupMap(_._1)(_._2)
    val parents      = edges.groupMap(_._2)(_._1)
    val roots        = ids.filterNot(parents.contains)
    val ranges       = plan.composites.map(value => value.instance -> range(value)).toMap
    val boundaries   = (Vector(0, length) ++ plan.composites.flatMap(value =>
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
    else if leaves.sliding(2).exists { case Vector(left, right) => left.end != right.start; case _ => false } then
      Some("physical leaves are not contiguous")
    else if ids.distinct.size != ids.size then Some("composite instances are not unique")
    else if roots.isEmpty && plan.composites.nonEmpty then Some("plan has no structural roots")
    else if edges.distinct.size != edges.size || parents.values.exists(_.size != 1) then
      Some("composite child has duplicate or multiple parent edges")
    else if plan.virtualLayout.nonEmpty then Some("virtual layout emission is unavailable")
    else if plan.composites.exists(_.fieldDispositions.exists(_.kind == FieldDispositionKind.Unsupported)) then
      Some("unsupported field disposition emission is unavailable")
    else if leaves.exists(_.target.isInstanceOf[TerminalLeafTarget.Token]) then
      Some("token target emission is unavailable")
    else if plan.targetAssertions.exists(_.owner.isInstanceOf[TargetAssertionOwner.Terminal]) then
      Some("terminal target emission is unavailable")
    else if targetOwners != composite || targets.groupBy(_.owner).exists(_._2.size != 1) ||
      targets.size != plan.composites.size ||
      targets.exists(value =>
        value.kind != TargetAssertionKind.NativeComposite && value.kind != TargetAssertionKind.CompatibleComposite
      ) || targets.exists(value => !bindings.elementTypes.contains(value.surfaceId))
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

  private def range(value: PlannedComposite): (Int, Int) = value.range.startOffset -> value.range.endOffset

  private def advanceTo(offset: Int, builder: PsiBuilder): Unit =
    while !builder.eof() && builder.getCurrentOffset < offset do builder.advanceLexer()

  private enum EmitEvent:
    case Enter(composite: PlannedComposite)
    case Exit(marker: PsiBuilder.Marker, to: Int, elementType: IElementType)
