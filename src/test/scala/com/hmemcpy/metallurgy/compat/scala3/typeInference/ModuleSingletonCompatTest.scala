package com.hmemcpy.metallurgy.compat.scala3.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Module/companion singleton type fidelity through the full pipeline (the slot the bundled annotator reads). */
final class ModuleSingletonCompatTest extends Scala3CompatTestCase:

  def testStaticModuleSingleton(): Unit = assertExprType(
    """object Foo
      |val x = /*start*/Foo/*end*/
      |//Foo.type""".stripMargin
  )

  def testPathDependentModuleSingleton(): Unit = assertExprType(
    """class Outer:
      |  object Inner
      |val o = new Outer
      |val x = /*start*/o.Inner/*end*/
      |//o.Inner.type""".stripMargin
  )
