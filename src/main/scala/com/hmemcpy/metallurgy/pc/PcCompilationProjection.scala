package com.hmemcpy.metallurgy.pc

/** A compiler-only insertion woven into a document so the Scala 3 compiler accepts an otherwise illegal top-level
  * expression. The inserted text is placed immediately before `atDocumentOffset`; every original byte keeps its
  * position and relative order. The canonical use makes a bare top-level `ScExpression` the initializer of a synthetic
  * `private val`, mirroring how the Scala 3 REPL turns free terms into result values.
  */
private[metallurgy] final case class PcProjectionInsertion(atDocumentOffset: Int, text: String):
  require(atDocumentOffset >= 0, s"insertion offset must be non-negative: $atDocumentOffset")
  require(text.nonEmpty, "insertion text must be non-empty")

/** Source projection between the verbatim document text held by the IntelliJ PSI and the text fed to the Scala 3
  * compiler. The production backend uses the identity projection. The compatibility harness installs an insertion
  * projection so a ported fragment stays verbatim while still compiling under dotc.
  */
private[metallurgy] sealed trait PcCompilationProjection:
  def documentText: String
  def compilerText: String
  def isIdentity: Boolean
  def fingerprint: String

  /** Map a document range to a compiler range. Always defined: every document byte exists in the compiler text. The
    * start is right-biased at an insertion boundary (an insertion placed at the start attaches before the range); the
    * end is left-biased (an insertion at the end does not shift it).
    */
  def toCompilerRange(start: Int, end: Int): PcSourceRange

  /** Map a document point (caret, or a range start) to a compiler offset, right-biased at insertion boundaries. */
  def toCompilerPoint(documentOffset: Int): Int

  /** Map a compiler range back to a document range. `None` when the range touches inserted text or spans an insertion:
    * such a range has no contiguous verbatim representation and is dropped rather than clamped onto the document.
    */
  def toDocumentRange(start: Int, end: Int): Option[PcSourceRange]

  /** Map a compiler offset back to a document offset. `None` when the offset lies inside inserted text. */
  def toDocumentPoint(compilerOffset: Int): Option[Int]

private[metallurgy] object PcCompilationProjection:
  val IdentityFingerprint: String = "identity"

  def identity(documentText: String): PcCompilationProjection = IdentityProjection(documentText)

  def withInsertions(documentText: String, insertions: Seq[PcProjectionInsertion]): PcCompilationProjection =
    InsertionProjection.build(documentText, insertions)

private object IdentityProjection:
  def apply(documentText: String): PcCompilationProjection =
    new Impl(documentText, documentText, Vector.empty, PcCompilationProjection.IdentityFingerprint)

private object InsertionProjection:
  def build(documentText: String, insertions: Seq[PcProjectionInsertion]): PcCompilationProjection =
    val sorted             = insertions.toVector.sortBy(_.atDocumentOffset)
    sorted.zipWithIndex.foreach: (ins, idx) =>
      require(
        idx == 0 || sorted(idx - 1).atDocumentOffset < ins.atDocumentOffset,
        s"two insertions share offset ${ins.atDocumentOffset}"
      )
      require(
        ins.atDocumentOffset <= documentText.length,
        s"insertion offset ${ins.atDocumentOffset} is past document length ${documentText.length}"
      )
    val builder            = new StringBuilder(documentText.length + sorted.map(_.text.length).sum)
    var previousOffset     = 0
    var fingerprintSeed    = 0L
    val compilerInsertions = new Array[CompilerInsertion](sorted.length)
    var insertedBefore     = 0
    sorted.zipWithIndex.foreach: (ins, idx) =>
      builder.append(documentText.substring(previousOffset, ins.atDocumentOffset))
      val compilerStart = ins.atDocumentOffset + insertedBefore
      builder.append(ins.text)
      val compilerEnd   = compilerStart + ins.text.length
      compilerInsertions(idx) = CompilerInsertion(compilerStart, compilerEnd)
      insertedBefore += ins.text.length
      previousOffset = ins.atDocumentOffset
      fingerprintSeed = fingerprintSeed * 31L + ins.atDocumentOffset * 73856093L + ins.text.length * 19349663L
    builder.append(documentText.substring(previousOffset, documentText.length))
    val fingerprint        = s"ins:$fingerprintSeed:${sorted.length}"
    new Impl(documentText, builder.toString, compilerInsertions.toVector, fingerprint)

private final case class CompilerInsertion(compilerStart: Int, compilerEnd: Int)

private final class Impl(
    val documentText: String,
    val compilerText: String,
    private val insertions: Vector[CompilerInsertion],
    val fingerprint: String
) extends PcCompilationProjection:
  val isIdentity: Boolean = insertions.isEmpty

  def toCompilerRange(start: Int, end: Int): PcSourceRange =
    PcSourceRange(toCompilerStart(start), toCompilerEnd(end))

  def toCompilerPoint(documentOffset: Int): Int = toCompilerStart(documentOffset)

  def toCompilerStart(documentOffset: Int): Int =
    var shift  = 0
    var prefix = 0
    insertions.foreach: ins =>
      // `prefix` is the total inserted length before this insertion, so the document gap is compilerStart − prefix.
      val documentGap = ins.compilerStart - prefix
      // right-biased: an insertion whose document gap is <= the start is placed before this point
      if documentGap <= documentOffset then shift += ins.compilerEnd - ins.compilerStart
      prefix += ins.compilerEnd - ins.compilerStart
    documentOffset + shift

  def toCompilerEnd(documentOffset: Int): Int =
    var shift  = 0
    var prefix = 0
    insertions.foreach: ins =>
      val documentGap = ins.compilerStart - prefix
      // left-biased: an insertion at the end does not shift it
      if documentGap < documentOffset then shift += ins.compilerEnd - ins.compilerStart
      prefix += ins.compilerEnd - ins.compilerStart
    documentOffset + shift

  def toDocumentPoint(compilerOffset: Int): Option[Int] =
    var shift  = 0
    var inside = false
    insertions.foreach: ins =>
      // the insertion owns its start boundary: a point at the first inserted byte, or between inserted bytes, has no
      // document position. The boundary at the end of the insertion reclaims the following document byte.
      if ins.compilerStart <= compilerOffset && compilerOffset < ins.compilerEnd then inside = true
      else if ins.compilerEnd <= compilerOffset then shift += ins.compilerEnd - ins.compilerStart
    if inside then None else Some(compilerOffset - shift)

  def toDocumentRange(start: Int, end: Int): Option[PcSourceRange] =
    // a compiler range is publishable only when it contains no inserted byte; otherwise the verbatim bytes are not
    // contiguous and the range is dropped rather than clamped onto the document.
    val overlapsInsertion = insertions.exists(ins => ins.compilerStart < end && ins.compilerEnd > start)
    if overlapsInsertion then None
    else Some(PcSourceRange(start - insertedLengthBefore(start), end - insertedLengthBefore(end)))

  private def insertedLengthBefore(compilerOffset: Int): Int =
    insertions.iterator.filter(_.compilerEnd <= compilerOffset).map(ins => ins.compilerEnd - ins.compilerStart).sum
