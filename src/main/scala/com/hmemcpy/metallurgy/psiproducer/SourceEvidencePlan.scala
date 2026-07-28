package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

private[metallurgy] final case class ProvisionalSourceEvidencePlan(
    sourceUri: ParserSourceUri,
    sourceDigest: String,
    parserEvidenceFingerprint: String,
    atoms: Vector[SourceAtom],
    structural: Vector[StructuralSourceEvidence]
):
  def reconstruct(source: String): String =
    atoms.map(atom => source.substring(atom.start, atom.end)).mkString

private[metallurgy] final case class SourceAtom(
    id: Long,
    start: Int,
    end: Int,
    claims: Vector[SourceClaim],
    comments: Vector[ParserComment]
)

private[metallurgy] enum SourceClaim:
  case Node(id: Long, occurrences: Vector[ParserNodeOccurrence])
  case Positioned(id: Long, occurrences: Vector[ParserPositionedOccurrence])
  case Diagnostic(index: Int)

private[metallurgy] final case class StructuralSourceEvidence(claim: SourceClaim, position: ParserNodePosition)

private[metallurgy] enum SourceEvidenceFailure:
  case SourceLengthMismatch(expected: Int, actual: Int)
  case DigestMismatch(expected: String, actual: String)
  case DuplicateIdentity(kind: String, id: Long)
  case InvalidRange(kind: String, identity: String, start: Int, end: Int, sourceLength: Int)
  case InvalidPoint(kind: String, identity: String, start: Int, point: Int, end: Int)
  case CommentMismatch(index: Int, expected: String, actual: String)
  case OverlappingComments(first: Int, second: Int)
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
          else structural += StructuralSourceEvidence(claim, positioned)
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
          else structural += StructuralSourceEvidence(claim, positioned)
        case absent                                                      => structural += StructuralSourceEvidence(claim, absent)

    snapshot.nodes.foreach(node =>
      add("node", node.id.toString, SourceClaim.Node(node.id, node.occurrences), node.position)
    )
    snapshot.positioned.foreach(value =>
      add("positioned", value.id.toString, SourceClaim.Positioned(value.id, value.occurrences), value.position)
    )
    snapshot.diagnostics.zipWithIndex.foreach: (diagnostic, index) =>
      val claim = SourceClaim.Diagnostic(index)
      diagnostic.position match
        case None           => structural += StructuralSourceEvidence(claim, ParserNodePosition.Absent)
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
            id.toLong,
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
        structural.result()
      )
      if result.reconstruct(source) != source then Left(Vector(SourceEvidenceFailure.ReconstructionMismatch))
      else Right(result)

  private def duplicateIds(ids: Vector[Long]): Vector[Long] =
    ids.groupMapReduce(identity)(_ => 1)(_ + _).collect { case (id, count) if count > 1 => id }.toVector.sorted
