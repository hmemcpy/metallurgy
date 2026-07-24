package com.hmemcpy.metallurgy.compat.scala3.inspections

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge
import org.jetbrains.plugins.scala.codeInspection.deprecation.Scala3DeprecatedAlphanumericInfixCallInspection

/** Port of the bundled Scala plugin's `Scala3DeprecatedAlphanumericInfixCallInspectionTest` (a representative subset).
  * The upstream wraps each case in a shared `testText` template and enables the inspection + `-deprecation`. Here:
  * `enableInspections` + `setAdditionalCompilerOptions("-deprecation")` + the same template. Snippets are kept exactly.
  */
final class Scala3DeprecatedInfixCallInspectionCompatTest extends Scala3CompatTestCase:

  // The testText template has top-level definitions + a bare trailing expression; always wrap in an object so the
  // expression is a valid constructor statement (the inspection fires on the reference, not the enclosing scope).
  override protected def wrapForHighlighting(code: String): String = wrapInObject(code)

  override protected def setUp(): Unit =
    super.setUp()
    ScalaPluginSemanticBridge.setAdditionalCompilerOptions(getModule, Seq("-deprecation"))
    myFixture.enableInspections(classOf[Scala3DeprecatedAlphanumericInfixCallInspection])

  private def testText(fileText: String): String =
    s"""class C:
       |  infix def op(x: Int): Int = ???
       |  def `bop`(x: Int) = ???
       |  def meth(x: Int): Int = ???
       |  def matching(x: Int => Int) = ???
       |  def +(x: Int): Int = ???
       |
       |object C:
       |  given AnyRef with
       |    extension (x: C)
       |      infix def iop (y: Int) = ???
       |      def mop (y: Int) = ???
       |      def ++ (y: Int) = ???
       |
       |infix class Or[X, Y]
       |class AndC[X, Y]
       |
       |val c = C()
       |
       |$fileText
       |""".stripMargin

  // === Valid (no deprecation) ===
  def testInfixMethodInfixCall(): Unit             = checkTextHasNoErrors(testText("c op 2"))
  def testInfixExtensionMethodInfixCall(): Unit    = checkTextHasNoErrors(testText("c iop 2"))
  def testSymbolicMethodInfixCall(): Unit          = checkTextHasNoErrors(testText("c + 2"))
  def testSymbolicExtensionMethodInfixCall(): Unit = checkTextHasNoErrors(testText("c ++ 2"))
  def testMethodCall(): Unit                       = checkTextHasNoErrors(testText("c.meth(2)"))
  def testExtensionMethodCall(): Unit              = checkTextHasNoErrors(testText("c.mop(2)"))
  def testInfixMethodCall(): Unit                  = checkTextHasNoErrors(testText("c.op(2)"))
  def testInfixExtensionMethodCall(): Unit         = checkTextHasNoErrors(testText("c.iop(2)"))
  def testSymbolicMethodCall(): Unit               = checkTextHasNoErrors(testText("c.+(2)"))
  def testSymbolicExtensionMethodCall(): Unit      = checkTextHasNoErrors(testText("c.++(2)"))
  def testBacktickedMethodInfixCall(): Unit        = checkTextHasNoErrors(testText("c `bop` 2"))
  def testMethodDefinedInScala2InfixCall(): Unit   = checkTextHasNoErrors(testText("1 to 2"))
  def testInfixScala3ClassInInfixType(): Unit      = checkTextHasNoErrors(testText("val x1: Int Or String = ???"))
  def testClassInBacktickedInfixType(): Unit       = checkTextHasNoErrors(testText("val x3: Int `AndC` String = ???"))

  // === Invalid (deprecated — error expected) ===
  def testMethodInfixCall(): Unit                     = checkHasErrorAroundCaret(testText(s"c ${CARET}meth 2"))
  def testExtensionMethodInfixCall(): Unit            = checkHasErrorAroundCaret(testText(s"c ${CARET}mop 2"))
  def testClassInInfixType(): Unit                    = checkHasErrorAroundCaret(
    testText(s"val x2: Int ${CARET}AndC String = ???")
  )
  def testBacktickedMethodInfixBacktickedCall(): Unit = checkTextHasNoErrors(testText("c `bop` 2"))
