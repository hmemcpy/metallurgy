package com.hmemcpy.metallurgy.compat.scala3.annotator

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Verbatim port of the bundled Scala plugin's `Scala3HighlightingTestsMix`. Snippets are kept exactly. The upstream
  * overrides `messagesFromScalaCode` to read ERROR-severity descriptions straight off the daemon (`doHighlighting`),
  * which is what `assertErrorDescriptions` does here; the no-error cases use `checkTextHasNoErrors`.
  */
final class Scala3HighlightingTestsMixCompatTest extends Scala3CompatTestCase:

  def testAccessCompanionObjectMembersInPresenceOfAnonymousUsingParameterWithCompanionType(): Unit =
    checkTextHasNoErrors(
      s"""type MyClass = Int
         |object MyClass:
         |  def test(): String = ""
         |
         |def foo(using MyClass): Unit = {
         |  summon[MyClass]
         |  MyClass.test()
         |}
         |""".stripMargin
    )

  def testAnonymousUsingParameterCompanionTypeUnresolvedReportsCompanion(): Unit =
    assertErrorDescriptions(
      """type MyClass = Int
        |
        |def foo(using MyClass): Unit = {
        |  summon[MyClass]
        |  MyClass.test()
        |}
        |""".stripMargin,
      "Cannot resolve symbol MyClass"
    )

  def testMultipleAnonymousParameters(): Unit =
    checkTextHasNoErrors(
      """case class Company(name: String)
        |case class SalesRep(name: String)
        |
        |case class Invoice(customer: String)(using Company, SalesRep):
        |  override def toString = s"$${summon[Company].name} / $${summon[SalesRep].name} - Customer: $$customer"
        |
        |@main def test(): Unit =
        |  given Company = Company("Big Corp")
        |  given SalesRep = SalesRep("John")
        |  println(Invoice("Peter LTD"))
        |""".stripMargin
    )

  def testSetterWithUsingParameters(): Unit =
    checkTextHasNoErrors(
      """
        |class Foo {
        |  private var _x = 1
        |  def x(using String): Int = _x
        |  def x_=(y: Int)(using String): Unit = _x = y
        |}
        |
        |object Foo {
        |  def main(args: Array[String]): Unit = {
        |    val foo = Foo()
        |    given String = "foo"
        |    foo.x = 5
        |  }
        |}
        |""".stripMargin
    )

  def testTypeMismatchUnappliedMethod(): Unit =
    assertErrorDescriptions(
      "given Int = 3; def f(int: Int)(using Int): Boolean = true; val v: Int = f(1)",
      "Expression of type Boolean doesn't conform to expected type Int"
    )

  def testStdLibPatches(): Unit =
    checkTextHasNoErrors(s"""import scala.language.dynamics
         |import _root_.scala.language.dynamics
         |
         |import scala.language.experimental.macros
         |import _root_.scala.language.experimental.macros
         |
         |import scala.language.noAutoTupling
         |import _root_.scala.language.noAutoTupling
         |
         |import scala.language.experimental.namedTypeArguments
         |import _root_.scala.language.experimental.namedTypeArguments""".stripMargin)

  def testSummonProductOfPathDependentConformance1(): Unit =
    checkTextHasNoErrors(
      """import scala.deriving.Mirror.ProductOf
        |
        |given Option[String] = ???
        |val _ = summon[Option[String]]
        |val _: Option[String] = summon[Option[String]]
        |
        |case class MyClass(p1: String, p2: String)
        |val _ = summon[ProductOf[MyClass]]
        |val _: ProductOf[MyClass] = summon[ProductOf[MyClass]]
        """.stripMargin
    )

  def testMirrorFromProductWithTuple(): Unit =
    checkTextHasNoErrors(
      """case class A(x: Int, y: String)
        |summon[deriving.Mirror.ProductOf[A]].fromProduct(1 -> "a")
        """.stripMargin
    )

  def testSummonProductOfPathDependentConformance3(): Unit =
    checkTextHasNoErrors(
      """import scala.deriving.Mirror.ProductOf
        |
        |trait ToTuple[E, T] extends (E => Option[T])
        |
        |case class MyClass(p1: String, p2: String)
        |
        |object usage {
        |  def productToTuple[T <: Product](using m: ProductOf[T]): ToTuple[T, m.MirroredElemTypes] = ???
        |  val toTuple1: ToTuple[MyClass, (String, String)] = productToTuple[MyClass]
        |}
        |""".stripMargin
    )

  def testMirrorToPathDependentResultType(): Unit = checkTextHasNoErrors(
    """import scala.deriving.Mirror
      |
      |case class LoginData(email: String, age: Int)
      |def to[A <: Product](value: A)(using mirror: Mirror.ProductOf[A]): Option[mirror.MirroredElemTypes] = ???
      |val _: LoginData => Option[(String, Int)] = x => to[LoginData](x)
      |val _: LoginData => Option[(String, Int)] = to[LoginData]
      |""".stripMargin
  )
