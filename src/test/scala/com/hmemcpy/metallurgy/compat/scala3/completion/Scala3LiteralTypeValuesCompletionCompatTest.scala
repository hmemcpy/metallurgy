package com.hmemcpy.metallurgy.compat.scala3.completion

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Port of the bundled Scala plugin's `Scala3LiteralTypeValuesCompletionTest` (representative cases). The upstream uses
  * `doOptimisticCompletionTest` (complete + select + verify); adapted to `assertCompletionContains` (check the literal
  * value is present) and `assertCompletionExcludes` (for the checkNoBasicCompletion cases). Snippets are kept exactly.
  */
final class Scala3LiteralTypeValuesCompletionCompatTest extends Scala3CompatTestCase:

  def testUnionTypeVariableContainsLiteralValues(): Unit = assertCompletionContains(
    s"val x: 42 | -1 = ${CARET}",
    "42"
  )

  def testUnionTypeAliasContainsStringLiterals(): Unit = assertCompletionContains(
    s"""
       |type Color = "red" | "green" | "blue"
       |val color: Color = ${CARET}
       |""".stripMargin,
    "\"red\""
  )

  def testSingleLiteralTypeString(): Unit = assertCompletionContains(
    s"""val x: "literal_string_type" = ${CARET}""",
    "\"literal_string_type\""
  )

  def testUnionTypeAliasInsideStringLiteralContainsColors(): Unit = assertCompletionContains(
    s"""
       |type Color = "red" | "green" | "blue"
       |val color: Color = "${CARET}"
       |""".stripMargin,
    "red"
  )

  def testNoCompletionInStringLiteralAfterDollar(): Unit = assertCompletionExcludes(
    s"""
       |type Color = "red" | "green" | "blue"
       |val color: Color = "$$${CARET}"
       |""".stripMargin,
    "blue"
  )
