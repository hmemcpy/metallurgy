package com.hmemcpy.metallurgy.compat.scala3

import org.junit.Assert.fail

/** Sanity checks for the compat harness itself. The no-error and caret-error assertions must actually reject broken
  * code; a harness that silently passes everything is worse than none. Each "rejects" case feeds deliberately broken
  * Scala and asserts that the assertion throws. The rejecting bodies live in non-`test`-prefixed helpers so JUnit does
  * not reflect their inner closures as test methods.
  */
final class Scala3CompatHarnessSanityTest extends Scala3CompatTestCase:

  def testValidObjectPasses(): Unit = checkTextHasNoErrors("object A { val x: Int = 1 }")

  def testValidBareValPasses(): Unit = checkTextHasNoErrors("val x: Int = 1")

  def testRejectsMissingClosingBrace(): Unit = checkMissingClosingBraceRejected()

  def testRejectsMissingColonInValType(): Unit = checkMissingColonRejected()

  def testRejectsTypeMismatch(): Unit = checkTypeMismatchRejected()

  def testRejectsUndefinedReference(): Unit = checkUndefinedReferenceRejected()

  def testRejectsTypeMismatchInWrappedBareStatement(): Unit = checkWrappedTypeMismatchRejected()

  def testFindsErrorAtCaret(): Unit =
    checkHasErrorAroundCaret("object A { val x: Int = \"oop${CARET}s\" }")

  // The rejecting bodies live in non-`test`-prefixed helpers so JUnit does not reflect their inner closures as
  // test methods.
  private def checkMissingClosingBraceRejected(): Unit =
    expectRejected("missing closing brace")(checkTextHasNoErrors("object A { val x: Int = 1"))

  private def checkMissingColonRejected(): Unit =
    expectRejected("missing colon in val type annotation")(checkTextHasNoErrors("object A { val x Int = 1 }"))

  private def checkTypeMismatchRejected(): Unit =
    expectRejected("String assigned to Int")(checkTextHasNoErrors("object A { val x: Int = \"oops\" }"))

  private def checkUndefinedReferenceRejected(): Unit =
    expectRejected("reference to undefined member")(checkTextHasNoErrors("object A { val x = nonexistentMember }"))

  private def checkWrappedTypeMismatchRejected(): Unit =
    expectRejected("String assigned to Int (wrapped bare statement)")(checkTextHasNoErrors("val x: Int = \"oops\""))

  private def expectRejected(label: String)(body: => Unit): Unit =
    var threw = false
    try body
    catch case _: AssertionError => threw = true
    if !threw then fail(s"Expected the assertion to reject broken code ($label), but it reported no problem.")
