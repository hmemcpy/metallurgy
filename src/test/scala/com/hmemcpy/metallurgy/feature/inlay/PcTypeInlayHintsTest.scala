package com.hmemcpy.metallurgy.feature.inlay

import com.hmemcpy.metallurgy.compilerbackend.{
  CompilerBackendPass,
  CompilerBackendRole,
  CompilerBackendState,
  Scala3CompilerBackend
}
import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge
import com.hmemcpy.metallurgy.pc.PcSessionManager
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.PlatformTestUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScValueOrVariableDefinition
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.jetbrains.plugins.scala.settings.ScalaProjectSettings
import org.junit.Assert.{assertEquals, assertNull}

import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** Verifies the CompilerType slot is populated by the snapshot so the Scala plugin's own ScalaTypeHintsPass reads the
  * pc-resolved type through ScExpression.getTypeWithoutImplicits.
  */
final class PcTypeInlayHintsTest extends ScalaLightCodeInsightFixtureTestCase:

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.fromString("3.5.2").get

  override protected def defaultVersionOverride: Option[ScalaVersion] =
    Some(new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2"))

  override def getTestDataPath: String =
    java.nio.file.Path.of("src", "test", "testdata").toAbsolutePath.toString

  override protected def setUp(): Unit =
    super.setUp()
    MetallurgySettings(getProject).setEnabled(getModule, enabled = true)
    setCompilerBasedHighlighting(enabled = true)

  override protected def tearDown(): Unit =
    try
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
    finally super.tearDown()

  def testHintAndExpressionResolutionUseTheSameBulkSnapshotType(): Unit =
    val source         =
      """val id = [A] => (x: A) => x
        |val result = id(42)""".stripMargin
    val file           = myFixture.configureByText("SlotFill.scala", source)
    myFixture.doHighlighting()
    val _              = PlatformTestUtil.waitForFuture(
      PcSessionManager.get(getProject).prepareCompilerBackend(file.getVirtualFile),
      60000L
    )
    myFixture.doHighlighting()
    val definition     = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScValueOrVariableDefinition])
      .asScala
      .last
    val initializer    = definition.expr.get
    val slotType       = ScalaPluginSemanticBridge.getCompilerType(initializer)
    val expressionType = initializer.getTypeWithoutImplicits().fold(_.toString, _.canonicalText)
    val bindingType    = Scala3CompilerBackend
      .get(getProject)
      .stateForActiveModule(definition.bindings.head, getModule, CompilerBackendRole.Binding)
    println(s"[bulk-type] slot=$slotType expression=$expressionType binding=$bindingType")

    assertEquals("Int", slotType)
    assertEquals("Int", expressionType)
    bindingType match
      case CompilerBackendState.Current(renderedType, _) => assertEquals("Int", renderedType)
      case state                                         => throw new AssertionError(s"expected current binding type, got $state")

  def testSemanticPopulationDoesNotDependOnRenderingTypeHints(): Unit =
    val source     = "val compilerResult = Option(42).get"
    val file       = myFixture.configureByText("SemanticOnly.scala", source)
    val definition = PsiTreeUtil
      .findChildOfType(file, classOf[ScValueOrVariableDefinition])
    val binding    = definition.bindings.head

    val readPopulation: Runnable = () =>
      new CompilerBackendPass(myFixture.getEditor, file).doCollectInformation(new EmptyProgressIndicator)
    val population: Runnable     = () => ApplicationManager.getApplication.runReadAction(readPopulation)
    val _                        = ApplicationManager.getApplication
      .executeOnPooledThread(population)
      .get(60, TimeUnit.SECONDS)
    val _                        = PlatformTestUtil.waitForFuture(
      PcSessionManager.get(getProject).prepareCompilerBackend(file.getVirtualFile),
      60000L
    )

    Scala3CompilerBackend
      .get(getProject)
      .stateForActiveModule(binding, getModule, CompilerBackendRole.Binding) match
      case CompilerBackendState.Current(renderedType, _) => assertEquals("Int", renderedType)
      case state                                         => throw new AssertionError(s"expected current binding type, got $state")

  def testInactiveFileDoesNotInstantiateCompilerBackendPass(): Unit =
    val settings = MetallurgySettings(getProject)
    settings.setEnabled(getModule, enabled = false)
    try
      val file = myFixture.configureByText("Inactive.scala", "val untouched = 42")
      assertNull(
        new com.hmemcpy.metallurgy.compilerbackend.CompilerBackendPassFactory()
          .createHighlightingPass(file, myFixture.getEditor)
      )
    finally settings.setEnabled(getModule, enabled = true)

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val settings = ScalaProjectSettings.getInstance(getProject)
    settings.setCompilerHighlightingScala3(enabled)
    settings.setUseCompilerTypes(enabled)
