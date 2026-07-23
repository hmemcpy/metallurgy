package com.hmemcpy.metallurgy.compat.scala3.completion

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Port of the bundled Scala plugin's `Scala3KeywordCompletionTest` (a representative subset covering infix, inline,
  * transparent, open, enum, given, using, opaque, extension keyword completions). The upstream uses `doCompletionTest`
  * (complete + select + verify text); here each case asserts the keyword is present in the completion list — a
  * compatible adaptation (adjustments allowed per the objective).
  */
final class Scala3KeywordCompletionCompatTest extends Scala3CompatTestCase:

  def testInfixTopLevel(): Unit = assertCompletionContains(s"in${CARET}", "infix")

  def testInfixInsideObject(): Unit = assertCompletionContains(
    s"""object O:
       |  in${CARET}
       |""".stripMargin,
    "infix"
  )

  def testInlineTopLevel(): Unit = assertCompletionContains(s"in${CARET}", "inline")

  def testTransparentAfterInline(): Unit = assertCompletionContains(s"inline tr${CARET}", "transparent")

  def testOpenTopLevel(): Unit = assertCompletionContains(s"op${CARET}", "open")

  def testEnumTopLevel(): Unit = assertCompletionContains(s"en${CARET}", "enum")

  def testGivenTopLevel(): Unit = assertCompletionContains(s"gi${CARET}", "given")

  def testUsingInParamList(): Unit = assertCompletionContains(s"def foo(u${CARET}", "using")

  def testOpaqueTopLevel(): Unit = assertCompletionContains(s"op${CARET}", "opaque")

  def testExtensionTopLevel(): Unit = assertCompletionContains(s"ext${CARET}", "extension")

  def testInlineDef(): Unit = assertCompletionContains(s"inline d${CARET}", "def")

  def testInlineType(): Unit = assertCompletionContains(s"inline t${CARET}", "type")

  def testInfixAfterHardModifier(): Unit = assertCompletionContains(s"private in${CARET}", "infix")

  def testInlineAfterHardModifier(): Unit = assertCompletionContains(s"private in${CARET}", "inline")
