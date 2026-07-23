package com.hmemcpy.metallurgy.compat.scala3.completion

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Verbatim port of the bundled Scala plugin's `Scala3InlineCompletionTest`. The upstream uses `doCompletionTest`;
  * adapted to `assertCompletionContains` (member completion). Snippets are kept exactly.
  */
final class Scala3InlineCompletionCompatTest extends Scala3CompatTestCase:

  def testParamInRegularMethod(): Unit = assertCompletionContains(
    s"""
       |case class Data(id: Int, column: String)
       |
       |def test1(data: Data): Unit = {
       |  println(data.c${CARET})
       |}
       |""".stripMargin,
    "column"
  )

  def testParamInInlineMethod(): Unit = assertCompletionContains(
    s"""
       |case class Data(id: Int, column: String)
       |
       |inline def test1(data: Data): Unit = {
       |  println(data.c${CARET})
       |}
       |""".stripMargin,
    "column"
  )

  def testInlineParamInInlineMethod(): Unit = assertCompletionContains(
    s"""
       |case class Data(id: Int, column: String)
       |
       |inline def test1(inline data: Data): Unit = {
       |  println(data.c${CARET})
       |}
       |""".stripMargin,
    "column"
  )
