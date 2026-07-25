package com.hmemcpy.metallurgy.compat.scala3.annotator

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Verbatim port of the bundled Scala plugin's `Scala3DependentFunTypeAnnotatorTest`. Snippets are kept exactly. */
final class Scala3DependentFunTypeAnnotatorCompatTest extends Scala3CompatTestCase:

  def testCallingDependentFunType(): Unit = checkTextHasNoErrors(
    """
      |def test(f: (i: Int) => Int): Int = f(1)
      |""".stripMargin
  )
