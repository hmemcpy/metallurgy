package com.hmemcpy.metallurgy.testkit

private[metallurgy] opaque type SourceOffset = Int

private[metallurgy] object SourceOffset:
  def from(value: Int): Option[SourceOffset]      = Option.when(value >= 0)(value)
  def unsafe(value: Int): SourceOffset            = from(value).getOrElse(throw new IllegalArgumentException(value.toString))
  extension (offset: SourceOffset) def value: Int = offset

private[metallurgy] opaque type LineNumber = Int

private[metallurgy] object LineNumber:
  def from(value: Int): Option[LineNumber]    = Option.when(value >= 1)(value)
  def unsafe(value: Int): LineNumber          = from(value).getOrElse(throw new IllegalArgumentException(value.toString))
  extension (line: LineNumber) def value: Int = line

private[metallurgy] enum OracleAssertion:
  case Hover(offset: SourceOffset, expected: String)
  case TypeAt(offset: SourceOffset, expected: String)
  case Completion(offset: SourceOffset, expectedItems: Set[String])
  case NotRed(line: LineNumber)
  case Red(line: LineNumber)
  case Resolve(symbol: String, target: String)

private[metallurgy] enum OracleParseError:
  case UnknownAssertion(line: LineNumber, kind: String)
  case MalformedAssertion(line: LineNumber, content: String)
  case InvalidNumber(line: LineNumber, value: String)
  case OutOfRange(line: LineNumber, value: Int)

private[metallurgy] object ExpectedOutputParser:
  def parse(input: String): Either[OracleParseError, List[OracleAssertion]] =
    input.linesIterator.zipWithIndex.foldLeft[Either[OracleParseError, List[OracleAssertion]]](Right(Nil)):
      case (result, (rawLine, index)) =>
        result.flatMap: parsed =>
          val lineNumber = LineNumber.unsafe(index + 1)
          val content    = rawLine.takeWhile(_ != '#').trim
          if content.isEmpty then Right(parsed)
          else parseLine(content, lineNumber).map(parsed :+ _)

  private def parseLine(content: String, line: LineNumber): Either[OracleParseError, OracleAssertion] =
    content.split(":", 2).headOption match
      case Some("hover")      => parseOffsetAssertion(content, line, OracleAssertion.Hover.apply)
      case Some("typeAt")     => parseOffsetAssertion(content, line, OracleAssertion.TypeAt.apply)
      case Some("completion") => parseCompletion(content, line)
      case Some("notRed")     => parseHighlight(content, line, OracleAssertion.NotRed.apply)
      case Some("red")        => parseHighlight(content, line, OracleAssertion.Red.apply)
      case Some("resolve")    => parseResolve(content, line)
      case Some(kind)         => Left(OracleParseError.UnknownAssertion(line, kind))
      case None               => Left(OracleParseError.MalformedAssertion(line, content))

  private def parseOffsetAssertion(
      content: String,
      line: LineNumber,
      build: (SourceOffset, String) => OracleAssertion
  ): Either[OracleParseError, OracleAssertion] =
    content.split(":", 3).toList match
      case _ :: rawOffset :: expected :: Nil if expected.nonEmpty =>
        parseOffset(rawOffset, line).map(value => build(value, expected))
      case _                                                      =>
        Left(OracleParseError.MalformedAssertion(line, content))

  private def parseCompletion(content: String, line: LineNumber): Either[OracleParseError, OracleAssertion] =
    content.split(":", 3).toList match
      case _ :: rawOffset :: rawItems :: Nil if rawItems.nonEmpty =>
        parseOffset(rawOffset, line).map: offset =>
          OracleAssertion.Completion(offset, rawItems.split(',').iterator.map(_.trim).toSet)
      case _                                                      =>
        Left(OracleParseError.MalformedAssertion(line, content))

  private def parseHighlight(
      content: String,
      line: LineNumber,
      build: LineNumber => OracleAssertion
  ): Either[OracleParseError, OracleAssertion] =
    content.split(":", 3).toList match
      case _ :: "line" :: rawLine :: Nil => parseLineNumber(rawLine, line).map(build)
      case _                             => Left(OracleParseError.MalformedAssertion(line, content))

  private def parseResolve(content: String, line: LineNumber): Either[OracleParseError, OracleAssertion] =
    content.split(":", 4).toList match
      case _ :: symbol :: "->" :: target :: Nil if symbol.nonEmpty && target.nonEmpty =>
        Right(OracleAssertion.Resolve(symbol, target))
      case _                                                                          =>
        Left(OracleParseError.MalformedAssertion(line, content))

  private def parseNumber(value: String, line: LineNumber): Either[OracleParseError, Int] =
    value.toIntOption.toRight(OracleParseError.InvalidNumber(line, value))

  private def parseOffset(value: String, line: LineNumber): Either[OracleParseError, SourceOffset] =
    parseNumber(value, line).flatMap: parsed =>
      SourceOffset.from(parsed).toRight(OracleParseError.OutOfRange(line, parsed))

  private def parseLineNumber(value: String, line: LineNumber): Either[OracleParseError, LineNumber] =
    parseNumber(value, line).flatMap: parsed =>
      LineNumber.from(parsed).toRight(OracleParseError.OutOfRange(line, parsed))
