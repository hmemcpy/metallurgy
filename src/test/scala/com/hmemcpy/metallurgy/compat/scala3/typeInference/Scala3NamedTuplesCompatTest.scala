package com.hmemcpy.metallurgy.compat.scala3.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge

/** Verbatim port of the bundled Scala plugin's `Scala3NamedTuplesTest`. Named tuples need
  * `-language:experimental.namedTuples`; the upstream relies on the test SDK default, so it is added to the module's
  * compiler options here. Snippets are kept exactly.
  */
final class Scala3NamedTuplesCompatTest extends Scala3CompatTestCase:

  override protected def setUp(): Unit =
    super.setUp()
    ScalaPluginSemanticBridge.setAdditionalCompilerOptions(getModule, Seq("-language:experimental.namedTuples"))

  def testConformanceNormalOk(): Unit = checkTextHasNoErrors(
    """
      |val a: (x: Int) = (x = 1)
      |val b: (x: Int, y: Int) = (x = 1, y = 2)
      |""".stripMargin
  )

  def testConformanceLiteralTypeOk(): Unit = checkTextHasNoErrors(
    """
      |val x: (x: 1) = (x = 1)
      |""".stripMargin
  )

  def testConformanceWithDesugaredOk(): Unit = checkTextHasNoErrors(
    """
      |val a: scala.NamedTuple.NamedTuple[("x", "y"), (Int, String)] = (x = 1, y = "hello")
      |val b: (x: Int, y: String) = scala.NamedTuple[("x", "y"), (Int, String)]((1, "hello"))
      |""".stripMargin
  )

  def testExprType(): Unit = assertExprType(
    s"""
       |val a = $START(x = 1, y = 2)$END
       |//(x: Int, y: Int)
       |""".stripMargin
  )

  def testExprTypeLiteral(): Unit = assertExprType(
    s"""
       |val a: (x: 1, y: 2) = $START(x = 1, y = 2)$END
       |
       |//(x: 1, y: 2)
       |""".stripMargin
  )

  def testTypeElementType(): Unit = assertExprType(
    s"""
       |val a: (x: Int, y: String) = ???
       |println(${START}a$END)
       |
       |//(x: Int, y: String)
       |""".stripMargin
  )

  def testSingleComponentPatternInference(): Unit = assertExprType(
    s"""
       |val (x = value) = (x = 1)
       |${START}value$END
       |
       |//Int
       |""".stripMargin
  )

  def testComponentPatternInference1(): Unit = assertExprType(
    s"""
       |val (x = value, y = _) = (x = 1, y = "")
       |${START}value$END
       |
       |//Int
       |""".stripMargin
  )

  def testComponentPatternInference2(): Unit = assertExprType(
    s"""
       |val (x = _, y = value) = (x = 1, y = "")
       |${START}value$END
       |
       |//String
       |""".stripMargin
  )

  def testReversedComponentPatternInference1(): Unit = assertExprType(
    s"""
       |val (y = _, x = value) = (x = 1, y = "")
       |${START}value$END
       |
       |//Int
       |""".stripMargin
  )

  def testReversedComponentPatternInference2(): Unit = assertExprType(
    s"""
       |val (y = value, x = _) = (x = 1, y = "")
       |${START}value$END
       |
       |//String
       |""".stripMargin
  )

  def testOmittedComponentPatternInference1(): Unit = assertExprType(
    s"""
       |val (x = value) = (x = 1, y = "")
       |${START}value$END
       |
       |//Int
       |""".stripMargin
  )

  def testOmittedComponentPatternInference2(): Unit = assertExprType(
    s"""
       |val (y = value) = (x = 1, y = "")
       |${START}value$END
       |
       |//String
       |""".stripMargin
  )

  def testNamedTupleExprComponents(): Unit = assertExprType(
    s"""
       |val tuple = (x = 1, y = 2)
       |${START}tuple.x$END
       |//Int
       |""".stripMargin
  )

  def testNamedTupleExprComponents2(): Unit = assertExprType(
    s"""
       |val tuple = (x = 1, y = "x")
       |${START}tuple.y$END
       |//String
       |""".stripMargin
  )

  def testNamedTupleTypeComponents(): Unit = assertExprType(
    s"""
       |val tuple: (x: Int, y: String) = ???
       |${START}tuple.x$END
       |//Int
       |""".stripMargin
  )

  def testNamedTupleTypeComponents2(): Unit = assertExprType(
    s"""
       |val tuple: (x: Int, y: String) = ???
       |${START}tuple.y$END
       |//String
       |""".stripMargin
  )

  def testCaseClassNamedTupleComponentAccess(): Unit = assertExprType(
    s"""
       |case class C(p: (x: Range, y: Range), o: (Range, Range)) {
       |  def xp = ${START}p.x.size$END
       |  def xo = o._1.size
       |}
       |//Int
       |""".stripMargin
  )

  def testNamedTupleToNormalTuple(): Unit = assertExprType(
    s"""
       |val (x, _) = (a = (y = 1), b = 2)
       |${START}x$END
       |
       |//(y: Int)
       |""".stripMargin
  )

  def testSingleComponentTypeInference(): Unit = assertExprType(
    s"""(a = "") match {
       |  case (a = x) => ${START}x$END
       |}
       |//String
       |""".stripMargin
  )

  def testTypeInference1(): Unit = assertExprType(
    s"""(a = 1, b = "") match {
       |  case (a = x, b = _) => ${START}x$END
       |}
       |//Int
       |""".stripMargin
  )

  def testTypeInference2(): Unit = assertExprType(
    s"""(a = 1, b = "") match {
       |  case (a = _, b = x) => ${START}x$END
       |}
       |//String
       |""".stripMargin
  )

  def testReversedTypeInference1(): Unit = assertExprType(
    s"""(a = 1, b = "") match {
       |  case (b = _, a = x) => ${START}x$END
       |}
       |//Int
       |""".stripMargin
  )

  def testReversedTypeInference2(): Unit = assertExprType(
    s"""(a = 1, b = "") match {
       |  case (b = x, a = _) => ${START}x$END
       |}
       |//String
       |""".stripMargin
  )

  def testOmittedTypeInference1(): Unit = assertExprType(
    s"""(a = 1, b = "") match {
       |  case (a = x) => ${START}x$END
       |}
       |//Int
       |""".stripMargin
  )

  def testOmittedTypeInference2(): Unit = assertExprType(
    s"""(a = 1, b = "") match {
       |  case (b = x) => ${START}x$END
       |}
       |//String
       |""".stripMargin
  )

  def testScrambledTypeInference(): Unit = assertExprType(
    s"""(a = 1, b = "", c = true) match {
       |  case (c = z, a = x) => $START(x, z)$END
       |}
       |//(Int, Boolean)
       |""".stripMargin
  )

  def testTypeInferenceToNormalTuple(): Unit = assertExprType(
    s"""(a = 1, b = "") match {
       |  case (_, x) => ${START}x$END
       |}
       |//String
       |""".stripMargin
  )

  def testTypeInferenceAfterNTtoTConversion(): Unit = assertExprType(
    s"""def takeTup[A, B](t: (A, B)): (A, B) = t
       |val tup = takeTup((a = true, b = 2))
       |${START}tup$END
       |//(Boolean, Int)
       |""".stripMargin
  )

  def testTtoNTConformance(): Unit = assertExprType(
    s"""def takeTup[A, B](t: (a: A, b: B)): (a: A, b: B) = t
       |val tup = takeTup((true, 2))
       |${START}tup$END
       |//(a: Boolean, b: Int)
       |""".stripMargin
  )

  def testAlias(): Unit = assertExprType(
    s"""
       |type Mk[T] = (field: T)
       |val x: Mk[Int] = (field = 1)
       |${START}x.field$END
       |//Int
       |""".stripMargin
  )

  def testNamedTupleMapComponentAccess(): Unit = assertExprType(
    s"""
       |val x: scala.NamedTuple.Map[(name: String), Option] = null
       |${START}x.name$END
       |// Option[String]
       |""".stripMargin
  )
