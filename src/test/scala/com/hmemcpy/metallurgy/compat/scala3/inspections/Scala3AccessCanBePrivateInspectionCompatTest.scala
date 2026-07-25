package com.hmemcpy.metallurgy.compat.scala3.inspections

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import org.jetbrains.plugins.scala.codeInspection.declarationRedundancy.ScalaAccessCanBeTightenedInspection

/** Port of the bundled Scala plugin's `Scala3NegativeAccessCanBePrivateTest`. */
final class Scala3AccessCanBePrivateInspectionCompatTest extends Scala3CompatTestCase:

  override protected def setUp(): Unit =
    super.setUp()
    myFixture.enableInspections(classOf[ScalaAccessCanBeTightenedInspection])

  def testOpaqueType(): Unit = checkTextHasNoErrors(
    "object A { opaque type Foo = Int; val x: Foo = 1 }"
  )
