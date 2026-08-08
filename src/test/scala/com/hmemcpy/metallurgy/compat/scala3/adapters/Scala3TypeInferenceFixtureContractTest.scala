package com.hmemcpy.metallurgy.compat.scala3.adapters

import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge
import com.hmemcpy.metallurgy.compat.scala3.BackendUnavailableException
import com.hmemcpy.metallurgy.psiproducer.{ParserPreparationState, Scala3ParserPreparationLifecycle}
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.util.TextRange
import org.jetbrains.plugins.scala.ScalaVersion
import org.junit.Assert.{assertEquals, assertTrue, fail}

final class Scala3TypeInferenceFixtureContractTest extends Scala3TypeInferenceFixture:

  def testUsesExactCapableCompilerAndParserCell(): Unit =
    val expected = ScalaVersion.fromString("3.7.4").get
    assertEquals(Some(expected), injectedScalaVersion)
    assertEquals(expected, version)
    assertEquals("3.7.4", ScalaPluginSemanticBridge.getScalaVersion(getModule))
    assertTrue(
      Scala3ParserPreparationLifecycle.get(getProject).stateFor(getModule).isInstanceOf[ParserPreparationState.Ready]
    )

  def testPreservesSourcePreparationAndExpectedComment(): Unit =
    val source          = "\r\n  val result = /*start*/List(1).head/*end*/\r\n  //Int\r\n"
    doTest(source)
    assertEquals("dummy.scala", getFile.getName)
    assertEquals("val result = /*start*/List(1).head/*end*/\n  //Int", getFile.getText)
    val expressionStart = getFile.getText.indexOf("List(1).head")
    assertEquals(
      new TextRange(expressionStart, expressionStart + "List(1).head".length),
      configuredSelectedExpressionRange
    )

  def testRejectsChangedExpectedType(): Unit =
    checkChangedExpectedTypeRejected()

  def testRejectsParserErrorsOutsideSelectedExpression(): Unit =
    checkParserErrorsRejected()

  def testRejectsMissingSelectionMarker(): Unit =
    checkMissingSelectionMarkerRejected()

  def testRejectsMovedSelectionMarker(): Unit =
    checkMovedSelectionMarkerRejected()

  private def checkParserErrorsRejected(): Unit =
    expectAssertionContaining("parser errors"):
      doTest(")\nval result = /*start*/1/*end*/\n//Int")

  private def checkMissingSelectionMarkerRejected(): Unit =
    expectAssertionContaining("missing /*start*/ marker"):
      doTest("val result = 1/*end*/\n//Int")

  private def checkMovedSelectionMarkerRejected(): Unit =
    expectAssertionContaining("expected:<[Int]> but was:<[List[Int]]>"):
      doTest("val result = /*start*/List(1)/*end*/.head\n//Int")

  def testUnavailableBackendCannotFallThroughToBundledTyping(): Unit =
    checkUnavailableBackendRejected()

  private def checkChangedExpectedTypeRejected(): Unit =
    expectAssertionContaining("expected:<[String]> but was:<[1]>"):
      doTest("val result = /*start*/1/*end*/\n//String")

  private def checkUnavailableBackendRejected(): Unit =
    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
    try
      try
        doTest("val result = /*start*/1/*end*/\n//Int")
        fail("expected backend unavailability")
      catch case _: BackendUnavailableException => ()
    finally MetallurgySettings(getProject).setEnabled(getModule, enabled = true)

  private def expectAssertionContaining(expected: String)(body: => Unit): Unit =
    var message = Option.empty[String]
    try body
    catch case error: AssertionError => message = Option(error.getMessage)
    assertTrue(s"expected assertion containing '$expected', got $message", message.exists(_.contains(expected)))
