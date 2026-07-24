package com.hmemcpy.metallurgy.compat.shared.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Port of the bundled Scala plugin's `SCL13767Test` (shared Scala 2/3). Tests complex implicit resolution with
  * structural type refinements.
  */
final class SCL13767CompatTest extends Scala3CompatTestCase:

  def testSCL13767(): Unit = checkTextHasNoErrors(
    """object Wrapper:
      |  trait Aux[T, U]
      |
      |  implicit def aux[T <: { type Type = U }, U]: Aux[T { type Type = U }, U] = ???
      |
      |  type Test <: { type Type = Int }
      |
      |  def test[U](implicit ev: Aux[Test, U]): U = ???
      |
      |  test
      |""".stripMargin
  )
