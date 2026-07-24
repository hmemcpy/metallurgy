package com.hmemcpy.metallurgy.compat.shared.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Port of the bundled Scala plugin's `InfixGenericCallTest` (shared Scala 2/3). */
final class InfixGenericCallCompatTest extends Scala3CompatTestCase:

  override protected def wrapForHighlighting(code: String): String = wrapInObject(code)

  def testSCL17874(): Unit = checkTextHasNoErrors(
    """
      |trait ElementTrait
      |case class Element(some: Int) extends ElementTrait
      |class Rule[T]:
      |  def doStuff[T2 <: T](elem: T2 => Unit, e: T2): Unit = elem(e)
      |val rule: Rule[ElementTrait] = new Rule[ElementTrait]
      |rule.doStuff[Element](a => println(a.some), Element(0))
      |""".stripMargin
  )
