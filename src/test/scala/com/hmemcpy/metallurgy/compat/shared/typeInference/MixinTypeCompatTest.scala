package com.hmemcpy.metallurgy.compat.shared.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Verbatim port of the bundled Scala plugin's `MixinTypeTest` (default `supportedIn` = all versions). */
final class MixinTypeCompatTest extends Scala3CompatTestCase:

  def testAnonymousMixinCompoundType(): Unit = checkTextHasNoErrors(
    """
      |class SCL6573 {
      |  def foo = {
      |    trait A
      |
      |    trait B
      |
      |    new A with B
      |  }
      |}
    """.stripMargin
  )
