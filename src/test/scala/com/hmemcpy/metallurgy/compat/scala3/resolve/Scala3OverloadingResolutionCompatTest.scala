package com.hmemcpy.metallurgy.compat.scala3.resolve

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Verbatim port of the bundled Scala plugin's `Scala3OverloadingResolutionTest` (the `checkTextHasNoErrors` /
  * `checkHasErrorAroundCaret` cases). The `doResolveTest` cases (which assert resolve-to-target via `REFSRC`/`REFTGT`)
  * are omitted — they need a resolve-check helper not yet ported. Snippets are kept exactly.
  */
final class Scala3OverloadingResolutionCompatTest extends Scala3CompatTestCase:

  def testSimple(): Unit = checkTextHasNoErrors(
    """
      |object A {
      |  def foo(x: Int)(y: String): Unit = ???
      |  def foo(x: Int)(z: Double): Unit = ???
      |
      |  foo(1)("123")
      |}
      |""".stripMargin
  )

  def testDiffParamClausesSize(): Unit = checkTextHasNoErrors(
    """
      |object A {
      |  def foo(x: Int)(y: String): Unit = ???
      |  def foo(x: Int): Unit = ???
      |
      |  foo(1)("123")
      |}
      |""".stripMargin
  )

  def testLateApplyExpansion(): Unit = checkHasErrorAroundCaret(
    s"""
      |class Example {
      |  class Bar { def apply(s: String): String = s; def apply(d: Double): Double = d }
      |  def foo(i: Int): Bar = ???
      |  def foo(i: Int)(s: String): String = ???
      |  fo${CARET}o(1)("213")
      |}
      |""".stripMargin
  )

  def testLateApplyExpansionInapplicable(): Unit = checkTextHasNoErrors(
    """
      |class Example {
      |  class Bar { def apply(d: Double): Double = d }
      |  def foo(i: Int): Bar = ???
      |  def foo(i: Int)(s: String): String = ???
      |  foo(1)("213")
      |}
      |""".stripMargin
  )

  def testLateApplyExpansionMultiClauseApplyAmbiguous(): Unit = checkHasErrorAroundCaret(
    s"""
      |class Example {
      |  class Bar { def apply(s: String)(b: Boolean): String = s }
      |  def foo(i: Int): Bar = ???
      |  def foo(i: Int)(s: String)(b: Boolean): String = ???
      |  fo${CARET}o(1)("213")(true)
      |}
      |""".stripMargin
  )

  def testLateApplyExpansionChainedApplyAmbiguous(): Unit = checkHasErrorAroundCaret(
    s"""
      |class Example {
      |  class Baz { def apply(b: Boolean): String = "" }
      |  class Bar { def apply(s: String): Baz = ??? }
      |  def foo(i: Int): Bar = ???
      |  def foo(i: Int)(s: String)(b: Boolean): String = ???
      |  fo${CARET}o(1)("213")(true)
      |}
      |""".stripMargin
  )

  def testDecideByFirstClause(): Unit = checkHasErrorAroundCaret(
    s"""
      |object A {
      |  trait Foo[A]
      |  def foo[A](a: A)(i: Int): Int = 123
      |  def foo[A](f: Foo[A])(s: String): Int = 456
      |
      |  val ff: Foo[Int] = ???
      |  foo(ff)(1${CARET}23)
      |}
      |""".stripMargin
  )

  def testIArrayApplyResolution(): Unit = checkTextHasNoErrors(
    s"""
       |class TestA {
       |  def testWithIArray(): Unit = {
       |    val array = IArray(1, 2)
       |    val value1 = array(0)
       |    val value2 = array.apply(0)
       |    value1 + value2
       |  }
       |}
       |""".stripMargin
  )

  def testExtensionMethodOverloadResolution(): Unit = checkTextHasNoErrors(
    s"""
       |class MainTest {
       |  class Foo
       |
       |  extension [T](leftSideValue: T) {
       |    def shouldBe(right: Foo): Unit = ???
       |  }
       |
       |  extension [T, R](leftSideValue: T) {
       |    def shouldBe(right: R): Unit = ???
       |  }
       |
       |  shouldBe(1)(new Foo)
       |  1 shouldBe (new Foo)
       |}
       |""".stripMargin
  )

  def testOverloadBarNoSecondClause(): Unit = checkTextHasNoErrors(
    """
      |object A {
      |  def bar(x: Int): String = "1"
      |  def bar(x: Int)(t: Int): Unit = 2
      |  val z = bar(1)
      |}
      |""".stripMargin
  )
