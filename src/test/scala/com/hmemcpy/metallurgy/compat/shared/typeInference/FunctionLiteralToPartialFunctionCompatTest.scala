package com.hmemcpy.metallurgy.compat.shared.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Port of the bundled Scala plugin's `FunctionLiteralToPartialFunctionTest` (Scala 2.13+, shared). Previously blocked
  * by M4 (lambda type selection); now portable since the fix.
  */
final class FunctionLiteralToPartialFunctionCompatTest extends Scala3CompatTestCase:

  // Snippets have bare top-level method calls (seq.collect, takeFunctionLike) which the PSI parser
  // rejects on re-highlight after CompilerType slots are filled. Wrap in an object — semantically identical.
  override protected def wrapForHighlighting(code: String): String = wrapInObject(code)

  def testPartialFunctionSynthesis(): Unit = checkTextHasNoErrors(
    """
      |val seq = Seq("a", "b")
      |seq.collect(a => a)
      |seq.collect { a => a + "!" }
      |seq.collect { case a => a }
      |""".stripMargin
  )

  def testFunctionLikeResolve(): Unit = checkTextHasNoErrors(
    """
      |def takeFunctionLike(pf: PartialFunction[String, String]) = println("pf wins")
      |takeFunctionLike(_.reverse)
      |takeFunctionLike { case s => s.reverse }
      |""".stripMargin
  )
