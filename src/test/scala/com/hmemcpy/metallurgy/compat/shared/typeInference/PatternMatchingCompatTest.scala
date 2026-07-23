package com.hmemcpy.metallurgy.compat.shared.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Verbatim port of the bundled Scala plugin's `PatternMatchingTest` (default `supportedIn` = all versions). */
final class PatternMatchingCompatTest extends Scala3CompatTestCase:

  def testPatternMatchGenericReturnConformance(): Unit = checkTextHasNoErrors(
    """
      |class Term[A]
      |class Number(val n: Int) extends Term[Int]
      |object X {
      |  def f[B](t: Term[B]): B = t match {
      |    case y: Number => y.n
      |  }
      |}
    """.stripMargin
  )

  def testExistentialPatternMatchBinding(): Unit = checkTextHasNoErrors(
    """
      |object X {
      |  def f(x : List[_]): Any = x match { case z : List[a] => { val e : a = z.head; e } }
      |}
    """.stripMargin
  )
