package com.hmemcpy.metallurgy.psiproducer

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import org.jetbrains.plugins.scala.lang.lexer.{ScalaKeywordTokenType, ScalaTokenTypes}

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
  case LexicalContractMismatch
  case UnsafeTargetBoundary(start: Int, end: Int)
  case DuplicateTargetStart(start: Int)
  case OverlappingTargetRanges(firstStart: Int, firstEnd: Int, secondStart: Int, secondEnd: Int)
  case UnsupportedTargetSurface(surfaceId: String)

private object PlannedScala3Lexer:
  private final case class Token(start: Int, end: Int, elementType: IElementType)
  private final case class Compiled(source: String, tokens: Vector[Token])

  private val KeywordTypesByText = ScalaTokenTypes.KEYWORDS.getTypes.iterator
    .collect:
      case token: ScalaKeywordTokenType => token.keywordText -> token
    .toMap

  def closed: PlannedScala3Lexer = new PlannedScala3Lexer(None)

  def compile(
      source: String,
      plan: WholeFileProductionPlan,
      bindings: NativePsiElementBindings
  ): Either[LexerPlanFailure, PlannedScala3Lexer] =
    val lexical = ClosedSourceLexicalContract.from(source)
    if lexical != plan.lexicalContract then return Left(LexerPlanFailure.LexicalContractMismatch)
    val targets = plan.physicalLeafOwnership.foldLeft[
      Either[LexerPlanFailure, Vector[((Int, Int), IElementType)]]
    ](Right(Vector.empty)):
      case (failure @ Left(_), _)                                                                             => failure
      case (Right(result), PlannedPhysicalLeaf(_, start, end, _, _, _, TerminalLeafTarget.Token(surface, _))) =>
        if start < 0 || start >= end || end > source.length then
          Left(LexerPlanFailure.InvalidTargetRange(start, end, source.length))
        else if !lexical.boundaries(start) || !lexical.boundaries(end) then
          Left(LexerPlanFailure.UnsafeTargetBoundary(start, end))
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
              Right(new PlannedScala3Lexer(Some(Compiled(source, tokens(source, lexical, byStart)))))

  private def closedTokens(startOffset: Int, endOffset: Int): Vector[Token] =
    if startOffset < endOffset then Vector(Token(startOffset, endOffset, TokenType.BAD_CHARACTER)) else Vector.empty

  private def tokens(
      source: String,
      lexical: ClosedSourceLexicalContract,
      targetsByStart: Map[Int, (Int, IElementType)]
  ): Vector[Token] =
    val result = Vector.newBuilder[Token]
    var index  = 0
    while index < lexical.atoms.size do
      val atom = lexical.atoms(index)
      targetsByStart.get(atom.start) match
        case Some((end, elementType)) =>
          result += Token(atom.start, end, elementType)
          index += 1
          while index < lexical.atoms.size && lexical.atoms(index).end <= end do index += 1
        case None                     =>
          val elementType = atom.kind match
            case ClosedSourceLexicalKind.Whitespace         => TokenType.WHITE_SPACE
            case ClosedSourceLexicalKind.LineComment        => ScalaTokenTypes.tLINE_COMMENT
            case ClosedSourceLexicalKind.BlockComment       => ScalaTokenTypes.tBLOCK_COMMENT
            case ClosedSourceLexicalKind.QuotedIdentifier   => ScalaTokenTypes.tIDENTIFIER
            case ClosedSourceLexicalKind.Literal            => ScalaTokenTypes.tIDENTIFIER
            case ClosedSourceLexicalKind.Number             => ScalaTokenTypes.tIDENTIFIER
            case ClosedSourceLexicalKind.Identifier         =>
              KeywordTypesByText.getOrElse(source.substring(atom.start, atom.end), ScalaTokenTypes.tIDENTIFIER)
            case ClosedSourceLexicalKind.OperatorIdentifier => ScalaTokenTypes.tIDENTIFIER
            case ClosedSourceLexicalKind.Dot                => ScalaTokenTypes.tDOT
            case ClosedSourceLexicalKind.Comma              => ScalaTokenTypes.tCOMMA
            case ClosedSourceLexicalKind.LeftBrace          => ScalaTokenTypes.tLBRACE
            case ClosedSourceLexicalKind.RightBrace         => ScalaTokenTypes.tRBRACE
            case ClosedSourceLexicalKind.LeftBracket        => ScalaTokenTypes.tLSQBRACKET
            case ClosedSourceLexicalKind.RightBracket       => ScalaTokenTypes.tRSQBRACKET
            case ClosedSourceLexicalKind.LeftParenthesis    => ScalaTokenTypes.tLPARENTHESIS
            case ClosedSourceLexicalKind.RightParenthesis   => ScalaTokenTypes.tRPARENTHESIS
            case ClosedSourceLexicalKind.Semicolon          => ScalaTokenTypes.tSEMICOLON
          result += Token(atom.start, atom.end, elementType)
          index += 1
    result.result()
