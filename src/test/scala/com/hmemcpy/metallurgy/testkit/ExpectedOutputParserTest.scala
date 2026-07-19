package com.hmemcpy.metallurgy.testkit

import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

final class ExpectedOutputParserTest:

  @Test def parsesEveryAssertionKindAndComments(): Unit =
    val input =
      """
        |# the complete Phase 1 oracle vocabulary
        |hover:42:8080
        |typeAt:43:Config { val name: String }
        |completion:77:.asJson,.toJson
        |notRed:line:5
        |red:line:6 # bundled failure
        |resolve:powerCode:->:powerCode(Int, Int)
        |""".stripMargin

    val parsed = ExpectedOutputParser.parse(input)

    assertEquals(
      Right(
        List(
          OracleAssertion.Hover(SourceOffset.unsafe(42), "8080"),
          OracleAssertion.TypeAt(SourceOffset.unsafe(43), "Config { val name: String }"),
          OracleAssertion.Completion(SourceOffset.unsafe(77), Set(".asJson", ".toJson")),
          OracleAssertion.NotRed(LineNumber.unsafe(5)),
          OracleAssertion.Red(LineNumber.unsafe(6)),
          OracleAssertion.Resolve("powerCode", "powerCode(Int, Int)")
        )
      ),
      parsed
    )

  @Test def reportsThePhysicalLineForMalformedInput(): Unit =
    val parsed = ExpectedOutputParser.parse("# comment\n\nhover:not-an-offset:8080")

    assertTrue(parsed.isLeft)
    assertEquals(
      Left(OracleParseError.InvalidNumber(LineNumber.unsafe(3), "not-an-offset")),
      parsed
    )

  @Test def rejectsUnknownAssertionKinds(): Unit =
    assertEquals(
      Left(OracleParseError.UnknownAssertion(LineNumber.unsafe(1), "diagnostic")),
      ExpectedOutputParser.parse("diagnostic:1:error")
    )

  @Test def rejectsEmptyCompletionExpectations(): Unit =
    assertEquals(
      Left(OracleParseError.MalformedAssertion(LineNumber.unsafe(1), "completion:7:")),
      ExpectedOutputParser.parse("completion:7:")
    )

  @Test def rejectsNegativeOffsetsAndNonPositiveLineNumbers(): Unit =
    assertEquals(
      Left(OracleParseError.OutOfRange(LineNumber.unsafe(1), -1)),
      ExpectedOutputParser.parse("typeAt:-1:Int")
    )
    assertEquals(
      Left(OracleParseError.OutOfRange(LineNumber.unsafe(1), 0)),
      ExpectedOutputParser.parse("notRed:line:0")
    )
