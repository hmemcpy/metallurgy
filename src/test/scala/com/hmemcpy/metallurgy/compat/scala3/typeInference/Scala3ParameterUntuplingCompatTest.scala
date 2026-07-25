package com.hmemcpy.metallurgy.compat.scala3.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Verbatim port of the bundled Scala plugin's `Scala3ParameterUntuplingTest`. Snippets are kept exactly; only the
  * asserting helpers differ, validating the same PSI data the upstream suite does.
  */
final class Scala3ParameterUntuplingCompatTest extends Scala3CompatTestCase:

  private val START = "/*start*/"
  private val END   = "/*end*/"

  def testSimple(): Unit = assertExprType(
    s"""
       |object A {
       |  val xs: List[(Int, Int)] = ???
       |  ${START}xs.map { (x, y) => x + y }$END
       |}
       |//List[Int]
       |""".stripMargin
  )

  def testUnderscores(): Unit = assertExprType(
    s"""
       |object A {
       |  val xs: List[(Int, Int)] = ???
       |  ${START}xs.map(_ + _)$END
       |}
       |//List[Int]
       |""".stripMargin
  )

  def testSubClassParams(): Unit = assertExprType(
    s"""
       |object A {
       |  trait Baz
       |  trait Qux
       |  trait R
       |  trait Foo extends Baz
       |  trait Bar extends Qux
       |  val xs: List[(Foo, Bar)] = ???
       |  def combine(x: Baz, y: Qux): R = ???
       |  ${START}xs.map(combine)$END
       |}
       |//List[A.R]
       |""".stripMargin
  )

  def testMethodReference(): Unit = assertExprType(
    s"""
       |object A {
       |  val xs: List[(Int, Int)] = ???
       |  def combine(x: Int, y: Int): Int = x + y
       |  ${START}xs.map(combine)$END
       |}
       |//List[Int]
       |""".stripMargin
  )

  def testNonLiteralNeg(): Unit = checkHasErrorAroundCaret(
    s"""
       |object A {
       |  val xs: List[(Int, Int)] = ???
       |  val combiner: (Int, Int) => Int = ???
       |  xs.map(co${CARET}mbiner)
       |}
       |""".stripMargin
  )

  def testUntuplingNoAnnotations(): Unit = checkTextHasNoErrors(
    """
      |object A {
      |  val xs: List[(Int, Int)] = ???
      |  xs.map { (x, y) => x + y }
      |}
      |""".stripMargin
  )

  def testUntuplingCorrectAnnotations(): Unit = checkTextHasNoErrors(
    """
      |object A {
      |  val xs: List[(Int, Int)] = ???
      |  xs.map { (x: Int, y: Int) => x + y }
      |}
      |""".stripMargin
  )

  def testUntuplingWrongAnnotations(): Unit = checkHasErrorAroundCaret(
    s"""
       |object A {
       |  val xs: List[(Int, Int)] = ???
       |  xs.map { (x: S${CARET}tring, y: Int) => x + y }
       |}
       |""".stripMargin
  )
