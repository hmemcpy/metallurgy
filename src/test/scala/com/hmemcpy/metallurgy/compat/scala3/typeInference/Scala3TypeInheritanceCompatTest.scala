package com.hmemcpy.metallurgy.compat.scala3.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Verbatim port of the bundled Scala plugin's `Scala3TypeInheritance`. */
final class Scala3TypeInheritanceCompatTest extends Scala3CompatTestCase:

  def testOverrideMethodWithNarrowerTypeAndVarAssignment(): Unit = checkTextHasNoErrors(
    """
      |class Foo {
      |  def method: Foo = ???
      |}
      |
      |class Bar extends Foo {
      |  override def method = ??? : Bar // : Foo in Scala 3
      |
      |  var x = method
      |  x = ??? : Foo // Compiles in Scala 3
      |}
      |""".stripMargin
  )
