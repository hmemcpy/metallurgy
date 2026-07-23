package com.hmemcpy.metallurgy.compat.scala3.inspections

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

final class Scala3DeprecatedPackageObjectInspectionCompatTest extends Scala3CompatTestCase:

  override protected def setUp(): Unit =
    super.setUp()
    val cls = Class
      .forName("org.jetbrains.plugins.scala.codeInspection.deprecation.Scala3DeprecatedPackageObjectInspection")
      .asInstanceOf[Class[? <: com.intellij.codeInspection.LocalInspectionTool]]
    myFixture.enableInspections(cls)

  def testDeprecatedPackageObject(): Unit = checkHasErrorAroundCaret(
    s"""
       |package object ${CARET}test {
       |  def foo(): Int = 123
       |  class Foo { def bar: String = "" }
       |  type A <: AnyRef
       |  type B = Boolean
       |  val (a, b) = (1, "2")
       |  val Some(x) = Option(1d)
       |}
       |""".stripMargin
  )
