package com.hmemcpy.metallurgy.compat.scala3.annotator

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge

/** Verbatim port of the bundled Scala plugin's `Scala3NamedTupleAnnotatorTest`. Named tuples need
  * `-language:experimental.namedTuples`, set on the module here. Snippets are kept exactly.
  */
final class Scala3NamedTupleAnnotatorCompatTest extends Scala3CompatTestCase:

  override protected def setUp(): Unit =
    super.setUp()
    ScalaPluginSemanticBridge.setAdditionalCompilerOptions(getModule, Seq("-language:experimental.namedTuples"))

  def testAndNamedTupleBounds(): Unit = checkTextHasNoErrors(
    """
      |type NT = NamedTuple.Concat[(hi: Int), (str: String)]
      |""".stripMargin
  )

  def testNamedTupleReverseNoSoe(): Unit = checkTextHasNoErrors(
    """
      |object NamedTupleExamples:
      |  type NT1 = (name: String, age: Int, city: String)
      |  type NT2 = (country: String, hobby: String)
      |
      |  type Reversed = NamedTuple.Reverse[NT1]
      |
      |  def exampleReverse(namedTuple: NT1): Reversed =
      |    namedTuple.reverse
      |
      |""".stripMargin
  )
