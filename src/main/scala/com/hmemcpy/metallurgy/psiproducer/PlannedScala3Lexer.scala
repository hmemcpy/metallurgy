package com.hmemcpy.metallurgy.psiproducer

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import org.jetbrains.plugins.scala.lang.lexer.{ScalaTokenType, ScalaTokenTypes}

private[psiproducer] final class PlannedScala3Lexer private (
    compiled: Option[PlannedScala3Lexer.Compiled]
) extends LexerBase:

  private var buffer: CharSequence = ""
  private var bufferEnd            = 0
  private var tokens               = Vector.empty[PlannedScala3Lexer.Token]
  private var index                = 0

  override def start(source: CharSequence, startOffset: Int, endOffset: Int, initialState: Int): Unit =
    buffer = source
    bufferEnd = endOffset
    tokens = compiled match
      case Some(value) if startOffset == 0 && endOffset == source.length && value.source == source.toString =>
        value.tokens
      case Some(_)                                                                                          =>
        Vector(PlannedScala3Lexer.Token(startOffset, endOffset, TokenType.BAD_CHARACTER))
      case None                                                                                             =>
        PlannedScala3Lexer.closedTokens(startOffset, endOffset)
    index = 0

  override def getState: Int                   = 0
  override def getTokenType: IElementType      = tokens.lift(index).map(_.elementType).orNull
  override def getTokenStart: Int              = tokens.lift(index).fold(bufferEnd)(_.start)
  override def getTokenEnd: Int                = tokens.lift(index).fold(bufferEnd)(_.end)
  override def advance(): Unit                 = if index < tokens.size then index += 1
  override def getBufferSequence: CharSequence = buffer
  override def getBufferEnd: Int               = bufferEnd

private[psiproducer] enum LexerPlanFailure:
  case InvalidTargetRange(start: Int, end: Int, sourceLength: Int)
  case DuplicateTargetStart(start: Int)
  case OverlappingTargetRanges(firstStart: Int, firstEnd: Int, secondStart: Int, secondEnd: Int)
  case UnsupportedTargetSurface(surfaceId: String)

private object PlannedScala3Lexer:
  private final case class Token(start: Int, end: Int, elementType: IElementType)
  private final case class Compiled(source: String, tokens: Vector[Token])

  def closed: PlannedScala3Lexer = new PlannedScala3Lexer(None)

  def compile(
      source: String,
      plan: WholeFileProductionPlan,
      bindings: NativePsiElementBindings
  ): Either[LexerPlanFailure, PlannedScala3Lexer] =
    val targets = plan.physicalLeafOwnership.foldLeft[
      Either[LexerPlanFailure, Vector[((Int, Int), IElementType)]]
    ](Right(Vector.empty)):
      case (failure @ Left(_), _)                                                                             => failure
      case (Right(result), PlannedPhysicalLeaf(_, start, end, _, _, _, TerminalLeafTarget.Token(surface, _))) =>
        if start < 0 || start >= end || end > source.length then
          Left(LexerPlanFailure.InvalidTargetRange(start, end, source.length))
        else
          bindings.elementTypes
            .get(surface)
            .toRight(LexerPlanFailure.UnsupportedTargetSurface(surface))
            .map(elementType => result :+ ((start -> end) -> elementType))
      case (result, _)                                                                                        => result
    targets.flatMap: targetRows =>
      val ordered    = targetRows.sortBy(_._1)
      val duplicates = ordered
        .groupMap(_._1._1)(identity)
        .collectFirst { case (start, values) if values.size != 1 => start }
      val overlap    = ordered
        .sliding(2)
        .collectFirst:
          case Vector(((firstStart, firstEnd), _), ((secondStart, secondEnd), _)) if firstEnd > secondStart =>
            LexerPlanFailure.OverlappingTargetRanges(firstStart, firstEnd, secondStart, secondEnd)
      duplicates match
        case Some(start) => Left(LexerPlanFailure.DuplicateTargetStart(start))
        case None        =>
          overlap match
            case Some(failure) => Left(failure)
            case None          =>
              val byStart = ordered.map { case ((start, end), elementType) => start -> (end -> elementType) }.toMap
              Right(new PlannedScala3Lexer(Some(Compiled(source, tokens(source, 0, source.length, byStart)))))

  private def closedTokens(startOffset: Int, endOffset: Int): Vector[Token] =
    if startOffset < endOffset then Vector(Token(startOffset, endOffset, TokenType.BAD_CHARACTER)) else Vector.empty

  private def tokens(
      source: CharSequence,
      startOffset: Int,
      endOffset: Int,
      targetsByStart: Map[Int, (Int, IElementType)]
  ): Vector[Token] =
    val result                                         = Vector.newBuilder[Token]
    var offset                                         = startOffset
    def add(end: Int, elementType: IElementType): Unit =
      result += Token(offset, end, elementType)
      offset = end
    while offset < endOffset do
      val current = source.charAt(offset)
      targetsByStart.get(offset) match
        case Some((end, elementType)) => add(end, elementType)
        case None                     =>
          if Character.isWhitespace(current) then
            var end = offset + 1
            while end < endOffset && Character.isWhitespace(source.charAt(end)) do end += 1
            add(end, TokenType.WHITE_SPACE)
          else if current == '/' && offset + 1 < endOffset && source.charAt(offset + 1) == '/' then
            var end = offset + 2
            while end < endOffset && source.charAt(end) != '\r' && source.charAt(end) != '\n' do end += 1
            add(end, ScalaTokenTypes.tLINE_COMMENT)
          else if current == '/' && offset + 1 < endOffset && source.charAt(offset + 1) == '*' then
            var end   = offset + 2
            var depth = 1
            while end < endOffset && depth > 0 do
              if end + 1 < endOffset && source.charAt(end) == '/' && source.charAt(end + 1) == '*' then
                depth += 1
                end += 2
              else if end + 1 < endOffset && source.charAt(end) == '*' && source.charAt(end + 1) == '/' then
                depth -= 1
                end += 2
              else end += Character.charCount(Character.codePointAt(source, end))
            add(end, ScalaTokenTypes.tBLOCK_COMMENT)
          else if current == '`' then
            var end = offset + 1
            while end < endOffset && source.charAt(end) != '`' do
              end += Character.charCount(Character.codePointAt(source, end))
            if end < endOffset then end += 1
            add(end, ScalaTokenTypes.tIDENTIFIER)
          else
            val currentCodePoint = Character.codePointAt(source, offset)
            if Character.isUnicodeIdentifierStart(currentCodePoint) || current == '_' then
              var end         = offset + Character.charCount(currentCodePoint)
              while end < endOffset && Character.isUnicodeIdentifierPart(Character.codePointAt(source, end)) do
                end += Character.charCount(Character.codePointAt(source, end))
              if source.charAt(end - 1) == '_' then
                while end < endOffset && isOperatorPart(source, end) do
                  end += Character.charCount(Character.codePointAt(source, end))
              val text        = source.subSequence(offset, end).toString
              val elementType = text match
                case "package" => ScalaTokenTypes.kPACKAGE
                case "import"  => ScalaTokenTypes.kIMPORT
                case "given"   => ScalaTokenType.GivenKeyword
                case _         => ScalaTokenTypes.tIDENTIFIER
              add(end, elementType)
            else
              current match
                case '.' => add(offset + 1, ScalaTokenTypes.tDOT)
                case ',' => add(offset + 1, ScalaTokenTypes.tCOMMA)
                case '{' => add(offset + 1, ScalaTokenTypes.tLBRACE)
                case '}' => add(offset + 1, ScalaTokenTypes.tRBRACE)
                case '[' => add(offset + 1, ScalaTokenTypes.tLSQBRACKET)
                case ']' => add(offset + 1, ScalaTokenTypes.tRSQBRACKET)
                case ';' => add(offset + 1, ScalaTokenTypes.tSEMICOLON)
                case _   =>
                  var end = offset + Character.charCount(currentCodePoint)
                  while end < endOffset && isOperatorPart(source, end) do
                    end += Character.charCount(Character.codePointAt(source, end))
                  add(end, ScalaTokenTypes.tIDENTIFIER)
    result.result()

  private def isOperatorPart(source: CharSequence, offset: Int): Boolean =
    val codePoint = Character.codePointAt(source, offset)
    val value     = source.charAt(offset)
    !Character.isWhitespace(codePoint) && !Character.isUnicodeIdentifierPart(codePoint) && value != '`' &&
    value != '.' && value != ',' && value != '{' && value != '}' && value != '[' && value != ']' && value != ';' &&
    !(value == '/' && offset + 1 < source.length && (source.charAt(offset + 1) == '/' || source.charAt(
      offset + 1
    ) == '*'))
