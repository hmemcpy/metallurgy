package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

private[metallurgy] final case class ProvisionalSourceEvidencePlan(
    sourceUri: ParserSourceUri,
    sourceDigest: String,
    parserEvidenceFingerprint: String,
    atoms: Vector[SourceAtom],
    structural: Vector[StructuralSourceEvidence],
    lexicalContract: ClosedSourceLexicalContract
):
  def reconstruct(source: String): String =
    atoms.map(atom => source.substring(atom.start, atom.end)).mkString

private[metallurgy] final case class SourceAtomId(provisionalId: Long, partitionOrdinal: Int)

private[metallurgy] final case class SourceAtom(
    id: SourceAtomId,
    start: Int,
    end: Int,
    claims: Vector[SourceClaim],
    comments: Vector[ParserComment]
)

private[metallurgy] final case class SourceAtomReference(id: SourceAtomId, start: Int, end: Int)

private[metallurgy] enum ClosedSourceLexicalKind:
  case Whitespace, LineComment, BlockComment, QuotedIdentifier, Literal, Number, Identifier, OperatorIdentifier
  case Dot, Comma, Colon, LeftBrace, RightBrace, LeftBracket, RightBracket, LeftParenthesis, RightParenthesis, Semicolon

private[metallurgy] final case class ClosedSourceLexicalAtom(
    start: Int,
    end: Int,
    kind: ClosedSourceLexicalKind
)

private[metallurgy] final case class ClosedSourceLexicalContract(
    sourceDigest: String,
    atoms: Vector[ClosedSourceLexicalAtom]
):
  val boundaries: Set[Int] = atoms.flatMap(atom => Vector(atom.start, atom.end)).toSet + 0

  def reconstruct(source: String): String =
    atoms.map(atom => source.substring(atom.start, atom.end)).mkString

private[metallurgy] object ClosedSourceLexicalContract:
  def from(source: String): ClosedSourceLexicalContract =
    val result                                             = Vector.newBuilder[ClosedSourceLexicalAtom]
    var offset                                             = 0
    def add(end: Int, kind: ClosedSourceLexicalKind): Unit =
      result += ClosedSourceLexicalAtom(offset, end, kind)
      offset = end
    while offset < source.length do
      val current = source.charAt(offset)
      if Character.isWhitespace(current) then
        var end = offset + 1
        while end < source.length && Character.isWhitespace(source.charAt(end)) do end += 1
        add(end, ClosedSourceLexicalKind.Whitespace)
      else if current == '/' && offset + 1 < source.length && source.charAt(offset + 1) == '/' then
        var end = offset + 2
        while end < source.length && source.charAt(end) != '\r' && source.charAt(end) != '\n' do end += 1
        add(end, ClosedSourceLexicalKind.LineComment)
      else if current == '/' && offset + 1 < source.length && source.charAt(offset + 1) == '*' then
        var end   = offset + 2
        var depth = 1
        while end < source.length && depth > 0 do
          if end + 1 < source.length && source.charAt(end) == '/' && source.charAt(end + 1) == '*' then
            depth += 1
            end += 2
          else if end + 1 < source.length && source.charAt(end) == '*' && source.charAt(end + 1) == '/' then
            depth -= 1
            end += 2
          else end += Character.charCount(Character.codePointAt(source, end))
        add(end, ClosedSourceLexicalKind.BlockComment)
      else if current == '`' then
        var end = offset + 1
        while end < source.length && source.charAt(end) != '`' do
          end += Character.charCount(Character.codePointAt(source, end))
        if end < source.length then end += 1
        add(end, ClosedSourceLexicalKind.QuotedIdentifier)
      else if current == '"' then add(quotedEnd(source, offset, '"'), ClosedSourceLexicalKind.Literal)
      else if current == '\'' && hasClosingSingleQuote(source, offset) then
        add(quotedEnd(source, offset, '\''), ClosedSourceLexicalKind.Literal)
      else if Character.isDigit(current) then add(numberEnd(source, offset), ClosedSourceLexicalKind.Number)
      else
        val currentCodePoint = Character.codePointAt(source, offset)
        if Character.isUnicodeIdentifierStart(currentCodePoint) || current == '_' then
          var end = offset + Character.charCount(currentCodePoint)
          while end < source.length && Character.isUnicodeIdentifierPart(Character.codePointAt(source, end)) do
            end += Character.charCount(Character.codePointAt(source, end))
          if source.charAt(end - 1) == '_' then
            while end < source.length && isOperatorPart(source, end) do
              end += Character.charCount(Character.codePointAt(source, end))
          add(end, ClosedSourceLexicalKind.Identifier)
        else
          current match
            case '.' => add(offset + 1, ClosedSourceLexicalKind.Dot)
            case ',' => add(offset + 1, ClosedSourceLexicalKind.Comma)
            case ':' =>
              if offset + 1 < source.length && isOperatorPart(source, offset + 1) then
                var end = offset + 2
                while end < source.length && isOperatorPart(source, end) do
                  end += Character.charCount(Character.codePointAt(source, end))
                add(end, ClosedSourceLexicalKind.OperatorIdentifier)
              else add(offset + 1, ClosedSourceLexicalKind.Colon)
            case '{' => add(offset + 1, ClosedSourceLexicalKind.LeftBrace)
            case '}' => add(offset + 1, ClosedSourceLexicalKind.RightBrace)
            case '[' => add(offset + 1, ClosedSourceLexicalKind.LeftBracket)
            case ']' => add(offset + 1, ClosedSourceLexicalKind.RightBracket)
            case '(' => add(offset + 1, ClosedSourceLexicalKind.LeftParenthesis)
            case ')' => add(offset + 1, ClosedSourceLexicalKind.RightParenthesis)
            case ';' => add(offset + 1, ClosedSourceLexicalKind.Semicolon)
            case _   =>
              var end = offset + Character.charCount(currentCodePoint)
              while end < source.length && isOperatorPart(source, end) do
                end += Character.charCount(Character.codePointAt(source, end))
              add(end, ClosedSourceLexicalKind.OperatorIdentifier)
    ClosedSourceLexicalContract(ParserSyntaxSnapshot.digest(source), result.result())

  private def quotedEnd(source: String, start: Int, quote: Char): Int =
    val triple  = quote == '"' && start + 2 < source.length && source.startsWith("\"\"\"", start)
    var end     = start + (if triple then 3 else 1)
    var escaped = false
    while end < source.length do
      if triple && end + 2 < source.length && source.startsWith("\"\"\"", end) then return end + 3
      val current = source.charAt(end)
      if !triple && !escaped && current == quote then return end + 1
      if !triple && !escaped && current == '\\' then escaped = true
      else escaped = false
      end += Character.charCount(Character.codePointAt(source, end))
    end

  private def hasClosingSingleQuote(source: String, start: Int): Boolean =
    val end = quotedEnd(source, start, '\'')
    end > start + 1 && source.charAt(end - 1) == '\''

  private def numberEnd(source: String, start: Int): Int =
    var end                                       = start
    def consume(predicate: Char => Boolean): Unit =
      while end < source.length && predicate(source.charAt(end)) do end += 1
    if start + 1 < source.length && source.charAt(start) == '0' &&
      (source.charAt(start + 1) == 'x' || source.charAt(start + 1) == 'X')
    then
      end = start + 2
      consume(character => Character.digit(character, 16) >= 0 || character == '_')
    else if start + 1 < source.length && source.charAt(start) == '0' &&
      (source.charAt(start + 1) == 'b' || source.charAt(start + 1) == 'B')
    then
      end = start + 2
      consume(character => character == '0' || character == '1' || character == '_')
    else
      consume(character => Character.isDigit(character) || character == '_')
      if end + 1 < source.length && source.charAt(end) == '.' && Character.isDigit(source.charAt(end + 1)) then
        end += 1
        consume(character => Character.isDigit(character) || character == '_')
      if end < source.length && (source.charAt(end) == 'e' || source.charAt(end) == 'E') then
        val exponent = end
        end += 1
        if end < source.length && (source.charAt(end) == '+' || source.charAt(end) == '-') then end += 1
        val digits   = end
        consume(character => Character.isDigit(character) || character == '_')
        if end == digits then end = exponent
    if end < source.length && "fFdDlL".contains(source.charAt(end)) then end + 1 else end

  private def isOperatorPart(source: CharSequence, offset: Int): Boolean =
    val codePoint = Character.codePointAt(source, offset)
    val value     = source.charAt(offset)
    !Character.isWhitespace(codePoint) && !Character.isUnicodeIdentifierPart(codePoint) && value != '`' &&
    value != '"' && value != '\'' && value != '.' && value != ',' && value != '{' && value != '}' && value != '[' &&
    value != ']' && value != '(' && value != ')' && value != ';' &&
    !(value == '/' && offset + 1 < source.length && (source.charAt(offset + 1) == '/' || source.charAt(
      offset + 1
    ) == '*'))

private[metallurgy] enum SourceClaim:
  case Node(id: Long, occurrences: Vector[ParserNodeOccurrence])
  case Positioned(id: Long, occurrences: Vector[ParserPositionedOccurrence])
  case Diagnostic(index: Int)

private[metallurgy] enum SourceEvidenceEventId:
  case Node(id: Long)
  case Positioned(id: Long)
  case Diagnostic(index: Int)

private[metallurgy] object SourceEvidenceEventId:
  def from(claim: SourceClaim): SourceEvidenceEventId = claim match
    case SourceClaim.Node(id, _)       => SourceEvidenceEventId.Node(id)
    case SourceClaim.Positioned(id, _) => SourceEvidenceEventId.Positioned(id)
    case SourceClaim.Diagnostic(index) => SourceEvidenceEventId.Diagnostic(index)

private[metallurgy] final case class StructuralSourceEvidence(
    id: SourceEvidenceEventId,
    claim: SourceClaim,
    position: ParserNodePosition
)

private[metallurgy] final case class SourceAtomRefinement(
    atom: SourceAtomReference,
    requestingRoles: Vector[PsiOutputRoleId],
    replacement: Vector[PcSourceRange]
):
  require(requestingRoles.nonEmpty)

private[metallurgy] enum SourceAtomRefinementFailure:
  case UnknownAtom(atom: SourceAtomReference)
  case AtomRangeChanged(atom: SourceAtomReference, actualStart: Int, actualEnd: Int)
  case UnknownRole(role: PsiOutputRoleId)
  case EmptyReplacement(atom: SourceAtomReference)
  case EmptyReplacementInterval(atom: SourceAtomReference, index: Int, start: Int, end: Int)
  case IncompletePartition(
      atom: SourceAtomReference,
      expectedStart: Int,
      expectedEnd: Int,
      actualStart: Int,
      actualEnd: Int
  )
  case NonContiguousPartition(atom: SourceAtomReference, leftEnd: Int, rightStart: Int)
  case OverlappingPartition(atom: SourceAtomReference, leftEnd: Int, rightStart: Int)
  case UnsafeBoundary(atom: SourceAtomReference, offset: Int)
  case OverlappingRefinements(atom: SourceAtomReference, roles: Vector[PsiOutputRoleId])

private[metallurgy] final case class RefinedSourceEvidencePlan(
    sourceUri: ParserSourceUri,
    sourceDigest: String,
    parserEvidenceFingerprint: String,
    atoms: Vector[SourceAtom],
    structural: Vector[StructuralSourceEvidence],
    lexicalContract: ClosedSourceLexicalContract
):
  def reconstruct(source: String): String =
    atoms.map(atom => source.substring(atom.start, atom.end)).mkString

private[metallurgy] object SourceEvidenceRefinementPlanner:
  def refine(
      evidence: ProvisionalSourceEvidencePlan,
      knownRoles: Set[PsiOutputRoleId],
      refinements: Vector[SourceAtomRefinement]
  ): Either[Vector[SourceAtomRefinementFailure], RefinedSourceEvidencePlan] =
    val failures = Vector.newBuilder[SourceAtomRefinementFailure]
    val byId     = evidence.atoms.map(atom => atom.id -> atom).toMap

    refinements.foreach: refinement =>
      byId.get(refinement.atom.id) match
        case None       => failures += SourceAtomRefinementFailure.UnknownAtom(refinement.atom)
        case Some(atom) =>
          if atom.start != refinement.atom.start || atom.end != refinement.atom.end then
            failures += SourceAtomRefinementFailure.AtomRangeChanged(refinement.atom, atom.start, atom.end)
      refinement.requestingRoles
        .filterNot(knownRoles)
        .foreach(role => failures += SourceAtomRefinementFailure.UnknownRole(role))
      refinement.replacement match
        case Vector() => failures += SourceAtomRefinementFailure.EmptyReplacement(refinement.atom)
        case values   =>
          values.zipWithIndex.foreach: (interval, index) =>
            if interval.startOffset >= interval.endOffset then
              failures += SourceAtomRefinementFailure.EmptyReplacementInterval(
                refinement.atom,
                index,
                interval.startOffset,
                interval.endOffset
              )
          if values.head.startOffset != refinement.atom.start || values.last.endOffset != refinement.atom.end then
            failures += SourceAtomRefinementFailure.IncompletePartition(
              refinement.atom,
              refinement.atom.start,
              refinement.atom.end,
              values.head.startOffset,
              values.last.endOffset
            )
          values
            .sliding(2)
            .foreach:
              case Vector(left, right) if left.endOffset < right.startOffset =>
                failures += SourceAtomRefinementFailure.NonContiguousPartition(
                  refinement.atom,
                  left.endOffset,
                  right.startOffset
                )
              case Vector(left, right) if left.endOffset > right.startOffset =>
                failures += SourceAtomRefinementFailure.OverlappingPartition(
                  refinement.atom,
                  left.endOffset,
                  right.startOffset
                )
              case _                                                         => ()
          values
            .flatMap(interval => Vector(interval.startOffset, interval.endOffset))
            .distinct
            .filter(offset =>
              offset != refinement.atom.start && offset != refinement.atom.end &&
                !evidence.lexicalContract.boundaries(offset)
            )
            .foreach(offset => failures += SourceAtomRefinementFailure.UnsafeBoundary(refinement.atom, offset))

    refinements
      .groupBy(_.atom.id)
      .toVector
      .sortBy(_._1.toString)
      .foreach: (_, values) =>
        if values.size > 1 then
          failures += SourceAtomRefinementFailure.OverlappingRefinements(
            values.head.atom,
            values.flatMap(_.requestingRoles).distinct.sortBy(_.value)
          )

    val found = failures.result()
    if found.nonEmpty then Left(found)
    else
      val replacements = refinements.map(value => value.atom.id -> value.replacement).toMap
      val atoms        = evidence.atoms.flatMap: atom =>
        replacements.get(atom.id) match
          case None            => Vector(atom)
          case Some(partition) =>
            partition.zipWithIndex.map: (interval, index) =>
              SourceAtom(
                SourceAtomId(atom.id.provisionalId, index),
                interval.startOffset,
                interval.endOffset,
                atom.claims,
                atom.comments
              )
      Right(
        RefinedSourceEvidencePlan(
          evidence.sourceUri,
          evidence.sourceDigest,
          evidence.parserEvidenceFingerprint,
          atoms,
          evidence.structural,
          evidence.lexicalContract
        )
      )

private[metallurgy] final case class SourceEvidenceOwner(role: PsiOutputRoleId, identity: String):
  require(identity.nonEmpty)

private[metallurgy] final case class SourceAtomOwnership(
    atom: SourceAtomReference,
    owner: SourceEvidenceOwner
)

private[metallurgy] final case class SourceEventOwnership(
    eventId: SourceEvidenceEventId,
    owner: SourceEvidenceOwner
)

private[metallurgy] enum FinalSourceEvidenceFailure:
  case UnknownRole(role: PsiOutputRoleId)
  case UnknownAtom(atom: SourceAtomReference)
  case MultiplyOwnedAtom(atom: SourceAtomReference, owners: Vector[SourceEvidenceOwner])
  case UnownedAtom(atom: SourceAtomReference)
  case UnknownEvent(eventId: SourceEvidenceEventId)
  case MultiplyOwnedEvent(eventId: SourceEvidenceEventId, owners: Vector[SourceEvidenceOwner])
  case UnownedEvent(eventId: SourceEvidenceEventId)

private[metallurgy] final case class FinalSourceEvidencePlan(
    evidence: RefinedSourceEvidencePlan,
    atomOwnership: Vector[SourceAtomOwnership],
    eventOwnership: Vector[SourceEventOwnership]
):
  def reconstruct(source: String): String = evidence.reconstruct(source)

private[metallurgy] trait PlanningWorkObserver:
  def finalOwnershipEntries(count: Int): Unit
  def terminalLexicalEntries(count: Int): Unit
  def terminalCandidateEntries(count: Int): Unit

private[metallurgy] object PlanningWorkObserver:
  val NoOp: PlanningWorkObserver = new PlanningWorkObserver:
    override def finalOwnershipEntries(count: Int): Unit    = ()
    override def terminalLexicalEntries(count: Int): Unit   = ()
    override def terminalCandidateEntries(count: Int): Unit = ()

private[metallurgy] object FinalSourceEvidencePlanner:
  def plan(
      evidence: RefinedSourceEvidencePlan,
      knownRoles: Set[PsiOutputRoleId],
      atomOwnership: Vector[SourceAtomOwnership],
      eventOwnership: Vector[SourceEventOwnership]
  ): Either[Vector[FinalSourceEvidenceFailure], FinalSourceEvidencePlan] =
    plan(evidence, knownRoles, atomOwnership, eventOwnership, PlanningWorkObserver.NoOp)

  def plan(
      evidence: RefinedSourceEvidencePlan,
      knownRoles: Set[PsiOutputRoleId],
      atomOwnership: Vector[SourceAtomOwnership],
      eventOwnership: Vector[SourceEventOwnership],
      workObserver: PlanningWorkObserver
  ): Either[Vector[FinalSourceEvidenceFailure], FinalSourceEvidencePlan] =
    val failures         = Vector.newBuilder[FinalSourceEvidenceFailure]
    val atoms            = evidence.atoms.map(atom => SourceAtomReference(atom.id, atom.start, atom.end))
    val atomSet          = atoms.toSet
    val events           = evidence.structural.map(_.id)
    val eventSet         = events.toSet
    val atomAssignments  = assignmentIndex(atomOwnership, _.atom, workObserver)
    val eventAssignments = assignmentIndex(eventOwnership, _.eventId, workObserver)

    (atomOwnership.map(_.owner.role) ++ eventOwnership.map(_.owner.role)).distinct
      .filterNot(knownRoles)
      .sortBy(_.value)
      .foreach(role => failures += FinalSourceEvidenceFailure.UnknownRole(role))
    atomOwnership
      .map(_.atom)
      .distinct
      .filterNot(atomSet)
      .foreach(atom => failures += FinalSourceEvidenceFailure.UnknownAtom(atom))
    atoms.foreach: atom =>
      assignmentsFor(atomAssignments, atom, workObserver) match
        case Vector()    => failures += FinalSourceEvidenceFailure.UnownedAtom(atom)
        case Vector(_)   => ()
        case assignments =>
          failures += FinalSourceEvidenceFailure.MultiplyOwnedAtom(atom, assignments.map(_.owner))
    eventOwnership
      .map(_.eventId)
      .distinct
      .filterNot(eventSet)
      .foreach(event => failures += FinalSourceEvidenceFailure.UnknownEvent(event))
    events.foreach: event =>
      assignmentsFor(eventAssignments, event, workObserver) match
        case Vector()    => failures += FinalSourceEvidenceFailure.UnownedEvent(event)
        case Vector(_)   => ()
        case assignments =>
          failures += FinalSourceEvidenceFailure.MultiplyOwnedEvent(event, assignments.map(_.owner))

    val found = failures.result()
    Either.cond(
      found.isEmpty,
      FinalSourceEvidencePlan(
        evidence,
        atoms.flatMap(atom => assignmentsFor(atomAssignments, atom, workObserver).headOption),
        events.flatMap(event => assignmentsFor(eventAssignments, event, workObserver).headOption)
      ),
      found
    )

  private def assignmentIndex[A, K](
      assignments: Vector[A],
      key: A => K,
      workObserver: PlanningWorkObserver
  ): Map[K, Vector[A]] =
    workObserver.finalOwnershipEntries(assignments.size)
    assignments.groupMap(key)(identity)

  private def assignmentsFor[A, K](
      index: Map[K, Vector[A]],
      key: K,
      workObserver: PlanningWorkObserver
  ): Vector[A] =
    workObserver.finalOwnershipEntries(1)
    index.getOrElse(key, Vector.empty)

private[metallurgy] enum SourceEvidenceFailure:
  case SourceLengthMismatch(expected: Int, actual: Int)
  case DigestMismatch(expected: String, actual: String)
  case DuplicateIdentity(kind: String, id: Long)
  case InvalidRange(kind: String, identity: String, start: Int, end: Int, sourceLength: Int)
  case InvalidPoint(kind: String, identity: String, start: Int, point: Int, end: Int)
  case CommentMismatch(index: Int, expected: String, actual: String)
  case OverlappingComments(first: Int, second: Int)
  case DuplicateEndMarkerOwner(ownerNodeId: Long)
  case UnknownEndMarkerOwner(ownerNodeId: Long)
  case InvalidEndMarkerRange(ownerNodeId: Long, start: Int, end: Int, sourceLength: Int)
  case EndMarkerOutsideOwner(ownerNodeId: Long, start: Int, end: Int)
  case CoverageMismatch(expectedOffset: Int, actualOffset: Int)
  case ReconstructionMismatch

private[metallurgy] object ProvisionalSourceEvidencePlanner:
  def plan(snapshot: ParserSyntaxSnapshot): Either[Vector[SourceEvidenceFailure], ProvisionalSourceEvidencePlan] =
    val failures = Vector.newBuilder[SourceEvidenceFailure]
    val source   = snapshot.sourceText
    if snapshot.sourceLength != source.length then
      failures += SourceEvidenceFailure.SourceLengthMismatch(snapshot.sourceLength, source.length)
    val digest   = ParserSyntaxSnapshot.digest(source)
    if snapshot.sourceDigest != digest then
      failures += SourceEvidenceFailure.DigestMismatch(snapshot.sourceDigest, digest)

    duplicateIds(snapshot.nodes.map(_.id)).foreach(id =>
      failures += SourceEvidenceFailure.DuplicateIdentity("node", id)
    )
    duplicateIds(snapshot.positioned.map(_.id)).foreach(id =>
      failures += SourceEvidenceFailure.DuplicateIdentity("positioned", id)
    )
    duplicateIds(snapshot.endMarkers.map(_.ownerNodeId)).foreach(id =>
      failures += SourceEvidenceFailure.DuplicateEndMarkerOwner(id)
    )

    val boundaries = collection.mutable.TreeSet(0, source.length)
    val physical   = Vector.newBuilder[(PcSourceRange, SourceClaim)]
    val structural = Vector.newBuilder[StructuralSourceEvidence]

    def add(kind: String, identity: String, claim: SourceClaim, position: ParserNodePosition): Unit =
      position match
        case positioned @ ParserNodePosition.Positioned(range, point, ParserPositionProvenance.SourceDerived)
            if range.startOffset >= 0 && range.startOffset <= range.endOffset && range.endOffset <= source.length =>
          if point < range.startOffset || point > range.endOffset then
            failures += SourceEvidenceFailure.InvalidPoint(kind, identity, range.startOffset, point, range.endOffset)
          else if range.startOffset < range.endOffset then
            boundaries += range.startOffset
            boundaries += range.endOffset
            physical += range -> claim
          else structural += StructuralSourceEvidence(SourceEvidenceEventId.from(claim), claim, positioned)
        case positioned @ ParserNodePosition.Positioned(range, point, _) =>
          if range.startOffset < 0 || range.startOffset > range.endOffset || range.endOffset > source.length then
            failures += SourceEvidenceFailure.InvalidRange(
              kind,
              identity,
              range.startOffset,
              range.endOffset,
              source.length
            )
          else if point < range.startOffset || point > range.endOffset then
            failures += SourceEvidenceFailure.InvalidPoint(kind, identity, range.startOffset, point, range.endOffset)
          else structural += StructuralSourceEvidence(SourceEvidenceEventId.from(claim), claim, positioned)
        case absent                                                      =>
          structural += StructuralSourceEvidence(SourceEvidenceEventId.from(claim), claim, absent)

    snapshot.nodes.foreach(node =>
      add("node", node.id.toString, SourceClaim.Node(node.id, node.occurrences), node.position)
    )
    snapshot.positioned.foreach(value =>
      add("positioned", value.id.toString, SourceClaim.Positioned(value.id, value.occurrences), value.position)
    )
    val nodesById = snapshot.nodes.map(node => node.id -> node).toMap
    snapshot.endMarkers.foreach: marker =>
      val range = marker.designatorRange
      nodesById.get(marker.ownerNodeId) match
        case None        => failures += SourceEvidenceFailure.UnknownEndMarkerOwner(marker.ownerNodeId)
        case Some(owner) =>
          if range.startOffset < 0 || range.startOffset >= range.endOffset || range.endOffset > source.length then
            failures += SourceEvidenceFailure.InvalidEndMarkerRange(
              marker.ownerNodeId,
              range.startOffset,
              range.endOffset,
              source.length
            )
          else
            owner.position match
              case ParserNodePosition.Positioned(ownerRange, _, ParserPositionProvenance.SourceDerived)
                  if ownerRange.startOffset <= range.startOffset && range.endOffset <= ownerRange.endOffset =>
                boundaries += range.startOffset
                boundaries += range.endOffset
              case _ =>
                failures += SourceEvidenceFailure.EndMarkerOutsideOwner(
                  marker.ownerNodeId,
                  range.startOffset,
                  range.endOffset
                )
    snapshot.diagnostics.zipWithIndex.foreach: (diagnostic, index) =>
      val claim = SourceClaim.Diagnostic(index)
      diagnostic.position match
        case None           =>
          structural += StructuralSourceEvidence(SourceEvidenceEventId.from(claim), claim, ParserNodePosition.Absent)
        case Some(position) =>
          val range = position.range
          if range.startOffset < 0 || range.startOffset > range.endOffset || range.endOffset > source.length then
            failures += SourceEvidenceFailure.InvalidRange(
              "diagnostic",
              index.toString,
              range.startOffset,
              range.endOffset,
              source.length
            )
          else if position.point < range.startOffset || position.point > range.endOffset then
            failures += SourceEvidenceFailure.InvalidPoint(
              "diagnostic",
              index.toString,
              range.startOffset,
              position.point,
              range.endOffset
            )
          else if range.startOffset < range.endOffset then
            boundaries += range.startOffset
            boundaries += range.endOffset
            physical += range -> claim
          else
            structural += StructuralSourceEvidence(
              SourceEvidenceEventId.from(claim),
              claim,
              ParserNodePosition.Positioned(range, position.point, ParserPositionProvenance.Synthetic)
            )

    val sortedComments = snapshot.comments.zipWithIndex.sortBy(_._1.range.startOffset)
    sortedComments
      .sliding(2)
      .foreach:
        case Vector((first, firstIndex), (second, secondIndex)) if first.range.endOffset > second.range.startOffset =>
          failures += SourceEvidenceFailure.OverlappingComments(firstIndex, secondIndex)
        case _                                                                                                      => ()
    sortedComments.foreach: (comment, index) =>
      val range = comment.range
      if range.startOffset < 0 || range.startOffset >= range.endOffset || range.endOffset > source.length then
        failures += SourceEvidenceFailure.InvalidRange(
          "comment",
          index.toString,
          range.startOffset,
          range.endOffset,
          source.length
        )
      else
        val actual = source.substring(range.startOffset, range.endOffset)
        if actual != comment.raw then failures += SourceEvidenceFailure.CommentMismatch(index, comment.raw, actual)
        boundaries += range.startOffset
        boundaries += range.endOffset

    var offset = 0
    while offset < source.length do
      source.charAt(offset) match
        case '\r' if offset + 1 < source.length && source.charAt(offset + 1) == '\n' =>
          boundaries += offset; boundaries += offset + 2; offset += 2
        case '\r' | '\n'                                                             => boundaries += offset; boundaries += offset + 1; offset += 1
        case _                                                                       => offset += 1

    val ranges = physical.result()
    val atoms  = boundaries.toVector
      .sliding(2)
      .zipWithIndex
      .collect:
        case (Vector(start, end), id) if start < end =>
          SourceAtom(
            SourceAtomId(id.toLong, 0),
            start,
            end,
            ranges.collect { case (range, claim) if range.startOffset <= start && range.endOffset >= end => claim },
            sortedComments.collect {
              case (comment, _) if comment.range.startOffset <= start && comment.range.endOffset >= end => comment
            }
          )
      .toVector

    var expected = 0
    atoms.foreach: atom =>
      if atom.start != expected then failures += SourceEvidenceFailure.CoverageMismatch(expected, atom.start)
      expected = atom.end
    if expected != source.length then failures += SourceEvidenceFailure.CoverageMismatch(expected, source.length)

    val found = failures.result()
    if found.nonEmpty then Left(found)
    else
      val result = ProvisionalSourceEvidencePlan(
        snapshot.sourceUri,
        snapshot.sourceDigest,
        ParserSyntaxSnapshot.evidenceFingerprint(snapshot),
        atoms,
        structural.result(),
        ClosedSourceLexicalContract.from(source)
      )
      if result.reconstruct(source) != source || result.lexicalContract.reconstruct(source) != source then
        Left(Vector(SourceEvidenceFailure.ReconstructionMismatch))
      else Right(result)

  private def duplicateIds(ids: Vector[Long]): Vector[Long] =
    ids.groupMapReduce(identity)(_ => 1)(_ + _).collect { case (id, count) if count > 1 => id }.toVector.sorted
