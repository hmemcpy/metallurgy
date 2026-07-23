package com.hmemcpy.metallurgy.compat.shared.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Verbatim port of the bundled Scala plugin's `NumericWideningAliasedTest` (`supportedIn >= 2.13`, so shared). */
final class NumericWideningAliasedCompatTest extends Scala3CompatTestCase:

  def testNumericAliasWideningInCaseClassApply(): Unit = checkTextHasNoErrors(
    """
      |object Test {
      |  type MyId = Long
      |  case class MyClass(myId: MyId)
      |  MyClass(123456)
      |}""".stripMargin
  )

  def testNumericAliasWideningAssignment(): Unit = checkTextHasNoErrors(
    """
      |type Id = Long
      |val ln: Long = 42
      |val id: Id = 42
      |""".stripMargin
  )
