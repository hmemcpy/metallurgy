package com.hmemcpy.metallurgy.feature.completion

import com.hmemcpy.metallurgy.pc.PcCompletion
import com.intellij.codeInsight.completion.{
  CompletionContributor,
  CompletionContributorEP,
  CompletionParameters,
  CompletionResultSet
}
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.{LoadingOrder, PluginId}
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.completion3.base.ScalaCompletionTestFixture
import org.jetbrains.plugins.scala.lang.completion3.base.ScalaCompletionTestFixture.createPresentation
import org.junit.Assert.{assertEquals, assertNotNull, assertTrue}

import java.nio.file.Path

/** Reflectively instantiated by the completion extension point in [[PcCompletionContributorTest]]. */
final class FixedPcCompletionContributor extends CompletionContributor:
  override def fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet): Unit =
    PcCompletionMerger.mergeRemainingContributors(
      parameters,
      result,
      Seq(
        PcCompletion("name", "name", Some(": String")),
        PcCompletion("`/pet`", "`/pet`: Int", Some("Int"))
      )
    )

final class PcCompletionContributorTest extends ScalaLightCodeInsightFixtureTestCase:

  override def getTestDataPath: String =
    Path.of("src", "test", "testdata").toAbsolutePath.toString

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.Latest.Scala_3

  override protected def setUp(): Unit =
    super.setUp()
    val descriptor = Option(PluginManagerCore.getPlugin(PluginId.getId("com.hmemcpy.metallurgy"))).getOrElse:
      throw new IllegalStateException("Metallurgy plugin descriptor is not loaded in TestKit")
    val extension  = new CompletionContributorEP(
      "Scala",
      classOf[FixedPcCompletionContributor].getName,
      descriptor
    )
    CompletionContributor.EP.getPoint.registerExtension(
      extension,
      LoadingOrder.FIRST,
      getTestRootDisposable
    )

  def testCompilerItemOverridesBundledScalaCompletion(): Unit =
    val fixture = new ScalaCompletionTestFixture(myFixture)
    val items   = fixture.completeBasic(
      """final class Config:
        |  val name: Any = ???
        |
        |val config = Config()
        |config.<caret>
        |""".stripMargin
    )

    val nameItems    = items.filter(_.getLookupString == "name")
    assertEquals(1, nameItems.size)
    val presentation = createPresentation(nameItems.head)
    assertEquals("String", presentation.getTypeText)
    assertNotNull(presentation.getIcon)

  def testBacktickedCompilerItemUsesScalaPrefixMatching(): Unit =
    val fixture = new ScalaCompletionTestFixture(myFixture)
    val items   = fixture.completeBasic(
      """val value: Any = ???
        |value.`<caret>
        |""".stripMargin
    )

    assertTrue(items.exists(_.getLookupString == "`/pet`") || myFixture.getFile.getText.contains("value.`/pet`"))
