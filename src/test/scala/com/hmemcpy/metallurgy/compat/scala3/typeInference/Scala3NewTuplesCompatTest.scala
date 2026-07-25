package com.hmemcpy.metallurgy.compat.scala3.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Verbatim ports of the bundled Scala plugin's `Scala3NewTuplesTest`. Snippets, markers, spacing, and the trailing
  * expected-type comment are kept exactly; only the asserting helper differs, and it validates the same PSI data the
  * upstream suite does (`ScExpression.type` rendered text).
  */
final class Scala3NewTuplesCompatTest extends Scala3CompatTestCase:

  private val START = "/*start*/"
  private val END   = "/*end*/"

  def testInferredTuple1Type(): Unit = assertExprType(
    s"""
       |val t = Tuple1(1)
       |${START}t$END
       |//Tuple1[Int]
       |""".stripMargin
  )

  def testInferredTuple1HListType(): Unit = assertExprType(
    s"""
       |val t = 1 *: EmptyTuple
       |${START}t$END
       |//Int *: EmptyTuple
       |""".stripMargin
  )

  def testMixedTupleNAndHList(): Unit = assertExprType(
    s"""
       |val t = 1 *: (true, "")
       |${START}t$END
       |// (Int, Boolean, String)
       |""".stripMargin
  )

  def test_underscore_accessor_1(): Unit = assertExprType(
    s"""
       |val t = 1 *: true *: 2 *: EmptyTuple
       |${START}t._1$END
       |// Int
       |""".stripMargin
  )

  def test_underscore_accessor_2(): Unit = assertExprType(
    s"""
       |val t = 1 *: true *: 2 *: EmptyTuple
       |${START}t._2$END
       |// Boolean
       |""".stripMargin
  )
