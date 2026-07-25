package com.hmemcpy.metallurgy.compat.scala3.annotator

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Verbatim port of the bundled Scala plugin's `Scala3InlineFunctionHighlightingTest`. `assertNoErrors` maps to
  * `checkTextHasNoErrors`; `assertErrorsText` maps to `assertErrorDescriptions` (the bundled annotator's message).
  */
final class Scala3InlineFunctionHighlightingCompatTest extends Scala3CompatTestCase:

  def testTransparentMethodWithoutInlineIsInvalid(): Unit =
    assertErrorDescriptions(
      s"""transparent def foo(a: Int): Unit = {}
         |""".stripMargin,
      "The `transparent` keyword may only be used for inline methods"
    )

  def testTransparentMethodWithInlineIsValid(): Unit =
    checkTextHasNoErrors(
      s"""transparent inline def foo(a: Int): Unit = {}
         |""".stripMargin
    )

  def testNotInlineMethodWithInlineArgumentIsInvalid(): Unit =
    assertErrorDescriptions(
      s"""def foo(inline p1: String)
         |       (inline p2: String, p3: Int, inline p4: String)
         |       (p5: String)
         |       (p6: String, p7: Int): String = ???
         |""".stripMargin,
      "The `inline` modifier may only be used for arguments of inline methods",
      "The `inline` modifier may only be used for arguments of inline methods",
      "The `inline` modifier may only be used for arguments of inline methods"
    )

  def testInlineMethodWithInlineArgumentIsValid(): Unit =
    checkTextHasNoErrors(
      s"""inline def foo(inline p1: String)
         |              (inline p2: String, p3: Int, inline p4: String)
         |              (p5: String)
         |              (p6: String, p7: Int): String = ???
         |""".stripMargin
    )

  def testInlineAndAnnotationInlineMethodsValid(): Unit =
    checkTextHasNoErrors(
      """inline def foo1(param: String): Int = param.length
        |@inline def foo2(param: String): Int = param.length
        |def foo3(a: String): Int = a.length
        |""".stripMargin
    )
