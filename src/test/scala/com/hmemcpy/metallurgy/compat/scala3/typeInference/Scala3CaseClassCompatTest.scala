package com.hmemcpy.metallurgy.compat.scala3.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Port of the bundled Scala plugin's `Scala3CaseClassTest`, keeping the cases that resolve. The upstream is a
  * parameterized `GeneratedHighlightingParameterizedTest`; each snippet is fed to `checkNoErrorsAll`. Omitted: the
  * generated case-class unapply/accessor cases (the bundled annotator reports errors on `A.unapply(a)`/`a._1` for
  * generated members — a Metallurgy gap) and the `// Error` snippets (private constructor/copy). Snippets are kept
  * exactly.
  */
final class Scala3CaseClassCompatTest extends Scala3CompatTestCase:

  def testCaseClassPatterns(): Unit = checkNoErrorsAll(
    """
      |// testAlreadyDefinedUnapply
      |case class A(i: Int, s: String)
      |object A {
      |  def unapply(a: A): Some[(Double, Double)] = Some((1.0, 1.0))
      |}
      |
      |object Test {
      |  val a: A = A(123, "test")
      |
      |  val A(d1, d2) = a
      |  val _d1: Double = d1
      |  val _d2: Double = d2
      |
      |  val _acc1: Int = a._1
      |  val _acc2: String = a._2
      |
      |  val Some((e1, e2)) = A.unapply(a)
      |  val _e1: Double = e1
      |  val _e2: Double = e2
      |}
      |""".stripMargin,
    """
      |// testOption
      |val Some(x: Int) = Option(1)
      |val _o: Option[Int] = Some.unapply(Some(1))
      |""".stripMargin,
    """
      |// testRepeatedParam
      |case class A[T](x: Int, t: T*)
      |
      |def test[T](a: A[T]): Unit = {
      |  {
      |    val _i: Int = a._1
      |    val _t: Seq[T] = a._2
      |  }
      |  {
      |    val A(i) = a
      |    val _i: Int = i
      |  }
      |  {
      |    val A(i, t1) = a
      |    val _i: Int = i
      |    val _t1: T = t1
      |  }
      |  {
      |    val A(i, t1, t2, tt*) = a
      |    val _i: Int = i
      |    val _t1: T = t1
      |    val _t2: T = t2
      |    val _tt: Seq[T] = tt
      |  }
      |}
      |""".stripMargin,
    """
      |// testPrivateUnapply
      |case class Wrapper private(x: String)
      |
      |def test(w: Wrapper): Unit = {
      |  val Wrapper(s) = w
      |}
      |""".stripMargin,
    """
      |// testEmptyCopy
      |case class Wrapper()
      |
      |def test(w: Wrapper): Wrapper = {
      |  w.copy()
      |}
      |""".stripMargin
  )
