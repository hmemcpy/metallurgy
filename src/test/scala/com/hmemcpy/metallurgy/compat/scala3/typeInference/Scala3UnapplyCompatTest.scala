package com.hmemcpy.metallurgy.compat.scala3.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Port of the bundled Scala plugin's `Scala3UnapplyTest`. The upstream is a parameterized
  * `GeneratedHighlightingParameterizedTest` over a `Seq[String]` of `checkTextHasNoErrors` snippets; here each snippet
  * is fed to `checkNoErrorsAll`. The single `// Error` snippet (name-based no-auto-tupling) is omitted — it expects an
  * error, not a no-error check. Snippets are kept exactly.
  */
final class Scala3UnapplyCompatTest extends Scala3CompatTestCase:

  def testUnapplyPatterns(): Unit = checkNoErrorsAll(
    """
      |// booleanExtractor
      |object A:
      |  def unapply(i: Int): Boolean = true
      |object B:
      |  def unapply(i: Int): true = true
      |
      |val A() = 1
      |val B() = 2
      |""".stripMargin,
    """
      |// booleanExtractorForTypeParam
      |class A[T](a: T):
      |  def unapply(i: Int): T = a
      |
      |object a extends A(true)
      |
      |val a() = 1
      |""".stripMargin,
    """
      |// booleanExtractorForUnapplyWithTypeParam
      |object A:
      |  def unapply[T](t: T): T = t
      |
      |val A() = true
      |""".stripMargin,
    """
      |// productMatch1
      |class Result(val _1 : Int) extends Product1[Int]:
      | override def canEqual(that: Any): Boolean = true
      |
      |object A:
      |  def unapply(i: Int): Result = new Result(i)
      |
      |val A(i) = 1
      |val _i: Int = i
      |""".stripMargin,
    """
      |// productMatch2
      |class Result(val _1 : Int, val _2 : Boolean) extends Product2[Int, Boolean]:
      | override def canEqual(that: Any): Boolean = true
      |
      |object A:
      |  def unapply(i: Int): Result = new Result(i, true)
      |
      |val A(i, b) = 1
      |val _i: Int = i
      |val _b: Boolean = b
      |""".stripMargin,
    """
      |//productMatchWithTypeParam
      |
      |class Result[T](val _1 : T) extends Product1[T]:
      | override def canEqual(that: Any): Boolean = true
      |
      |object A:
      |  def unapply[T](t: T): Result[T] = new Result(t)
      |
      |val A(i) = 1
      |val _i: Int = i
      |""".stripMargin,
    """
      |//productMatchAutoTupling
      |
      |class Result[T](val _1 : T) extends Product1[T]:
      | override def canEqual(that: Any): Boolean = true
      |
      |object A:
      |  def unapply[T](t: T): Result[T] = new Result(t)
      |
      |val A(i, b) = (1, true)
      |val _i: Int = i
      |val _b: Boolean = b
      |
      |val A(t) = (1, true)
      |val _t: (Int, Boolean) = t
      |""".stripMargin,
    """
      |//productMatchNoAutoTupling
      |
      |class Result extends Product2[(Int, Boolean), String]:
      |  override def canEqual(that: Any): Boolean = true
      |  def _1 = (1, true)
      |  def _2 = "s"
      |
      |object A:
      |  def unapply(i: Int): Result = new Result
      |
      |val A(t, s) = 1
      |val _t: (Int, Boolean) = t
      |val _s: String = s
      |""".stripMargin,
    """
      |// singleMatch
      |class Result(val int: Int) {
      |  def isEmpty: Boolean = false
      |  def get: Boolean = true
      |}
      |
      |object A:
      |  def unapply(i: Int): Result = new Result(i)
      |
      |val A(b) = 1
      |
      |val _b: Boolean = b
      |""".stripMargin,
    """
      |// productMatchBeforeSingleMatch
      |class Result(val _1 : Int) extends Product1[Int] {
      |  override def canEqual(that: Any): Boolean = true
      |  def isEmpty: Boolean = false
      |  def get: Boolean = true
      |}
      |
      |object A:
      |  def unapply(i: Int): Result = new Result(i)
      |
      |val A(i) = 1
      |
      |val _i: Int = i
      |""".stripMargin,
    """
      |// productMatchOrSingleMatch
      |class Result(val _1 : Int) extends Product1[Int] {
      |  val _2: String = "blub"
      |  override def canEqual(that: Any): Boolean = true
      |  def isEmpty: Boolean = false
      |  def get: Boolean = true
      |}
      |
      |object A:
      |  def unapply(i: Int): Result = new Result(i)
      |
      |val A(b) = 1
      |val _b: Boolean = b
      |
      |val A(i, s) = 1
      |val _i: Int = i
      |val _s: String = s
      |""".stripMargin,
    """
      |// singleMatchIfNotInheritingProduct
      |class Result(val _1 : Int) {
      |  def isEmpty: Boolean = false
      |  def get: Boolean = true
      |}
      |
      |object A:
      |  def unapply(i: Int): Result = new Result(i)
      |
      |val A(b) = 1
      |
      |val _b: Boolean = b
      |""".stripMargin,
    """
      |// singleMatchWithTypeParameter
      |class Result[T](val get : T):
      |  def isEmpty: Boolean = false
      |
      |object A:
      |  def unapply[T](t: T): Result[T] = new Result(t)
      |
      |val A(s) = "s"
      |
      |val _s: String = s
      |""".stripMargin,
    """
      |// singleMatchAutoTupling
      |class Result[T](val get : T):
      |  def isEmpty: Boolean = false
      |
      |object A:
      |  def unapply[T](t: T): Result[T] = new Result(t)
      |
      |val A(i, b) = (1, true)
      |
      |val _i: Int = i
      |val _b: Boolean = b
      |
      |val A(t) = (1, true)
      |
      |val _t: (Int, Boolean) = t
      |""".stripMargin,
    """
      |// nameBasedMatch2
      |class Result(val _1 : Int) {
      |  def _2: Float = 2.0f
      |}
      |
      |class Unapplied:
      |  def isEmpty = false
      |  def get = new Result(1)
      |
      |object A:
      |  def unapply(i: Int): Unapplied = new Unapplied
      |
      |val A(i, f) = 1
      |
      |val _i: Int = i
      |val _f: Float = f
      |""".stripMargin,
    """
      |// singleMatchBeforeNameBasedMatch
      |class Result(val _1 : Int)
      |
      |class Unapplied:
      |  def isEmpty = false
      |  def get = new Result(1)
      |
      |object A:
      |  def unapply(i: Int): Unapplied = new Unapplied
      |
      |val A(r) = 1
      |val _r: Result = r
      |""".stripMargin,
    """
      |// singleMatchBeforeNameBasedMatch2
      |class Result(val _1 : Int):
      |  def _2: Float = 2.0f
      |
      |class Unapplied:
      |  def isEmpty = false
      |  def get = new Result(1)
      |
      |object A:
      |  def unapply(i: Int): Unapplied = new Unapplied
      |
      |val A(r) = 1
      |val _r: Result = r
      |
      |val A(i, f) = 1
      |val _i: Int = i
      |val _f: Float = f
      |""".stripMargin,
    """
      |//nameBasedMatchNoAutoTupling2
      |class Result[T](val _1 : T):
      |  def _2: Boolean = true
      |
      |class Unapplied[T](val get: Result[T]):
      |  def isEmpty = false
      |
      |object A:
      |  def unapply[T](t: T): Unapplied[T] = new Unapplied(new Result(t))
      |
      |
      |val A(t, b) = ("s", 1)
      |
      |val _t: (String, Int) = t
      |val _b: Boolean = b
      |
      |""".stripMargin
  )
