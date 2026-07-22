package com.hmemcpy.metallurgy.feature.inlay

import com.hmemcpy.metallurgy.compilerbackend.{
  CompilerBackendPass,
  CompilerBackendPassFactory,
  CompilerBackendRole,
  CompilerBackendState,
  Scala3CompilerBackend
}
import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge
import com.hmemcpy.metallurgy.pc.PcSessionManager
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.codeInsight.daemon.impl.HintRenderer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScValueOrVariableDefinition
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.{assertEquals, assertNull, assertTrue}

import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** Verifies the presentation-compiler type-hint pass renders inline hints with pc's resolved type for value definitions
  * the bundled plugin widens or leaves unresolved. Each case lets the semantic pass schedule population, waits for that
  * generation, reruns the daemon, and reads the inline inlay at the binding name.
  */
final class PcTypeInlayHintsTest extends ScalaLightCodeInsightFixtureTestCase:

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.fromString("3.5.2").get

  override protected def defaultVersionOverride: Option[ScalaVersion] =
    Some(new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2"))

  override def getTestDataPath: String =
    java.nio.file.Path.of("src", "test", "testdata").toAbsolutePath.toString

  // (label, source, bindingName, required substrings in the rendered inlay)
  private val cases: Seq[(String, (String, String, Set[String]))] = Seq(
    "polymorphic function result" -> (
      """val id = [A] => (x: A) => x
        |val result = id(42)""".stripMargin,
      "result",
      Set("Int")
    ),
    "generic tuple head"          -> (
      """val h: Int *: String *: EmptyTuple = (1, "two")
        |val first = h.head""".stripMargin,
      "first",
      Set("Int")
    ),
    "given summon"                -> (
      """given Int = 42
        |val number = summon[Int]""".stripMargin,
      "number",
      Set("Int")
    )
  )

  override protected def setUp(): Unit =
    super.setUp()
    MetallurgySettings(getProject).setEnabled(getModule, enabled = true)
    setCompilerBasedHighlighting(enabled = true)

  override protected def tearDown(): Unit =
    try
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
    finally super.tearDown()

  def testTypeInlayHints(): Unit =
    val results  = cases.zipWithIndex.map { case ((label, (source, bindingName, required)), idx) =>
      val _      = myFixture.configureByText(s"Inlay$idx.scala", source)
      myFixture.doHighlighting()
      val _      = PlatformTestUtil.waitForFuture(
        PcSessionManager.get(getProject).prepareCompilerBackend(myFixture.getFile.getVirtualFile),
        60000L
      )
      myFixture.doHighlighting()
      val offset = source.lastIndexOf(bindingName) + bindingName.length
      val texts  = myFixture.getEditor.getInlayModel
        .getInlineElementsInRange(offset, offset)
        .asScala
        .collect {
          case inlay if inlay.getRenderer.isInstanceOf[HintRenderer] =>
            inlay.getRenderer.asInstanceOf[HintRenderer].getText
        }
        .toSeq
      val ok     = texts.exists(t => required.forall(t.contains))
      println(f"[inlay] ${if ok then "OK  " else "FAIL"} $label%-32s -> ${texts.mkString("[", ", ", "]")}")
      (ok, label, texts.mkString(", "), required)
    }
    val failures = results.filterNot(_._1)
    assertTrue(
      s"${failures.size}/${cases.size} inlay cases failed:\n" +
        failures.map(f => s"  - ${f._2}: got '${f._3}', required ${f._4.mkString("[", ",", "]")}").mkString("\n"),
      failures.isEmpty
    )

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
    assertTrue(myFixture.getEditor.getInlayModel.getInlineElementsInRange(0, source.length).isEmpty)

  def testInactiveFileDoesNotInstantiateCompilerBackendPass(): Unit =
    val settings = MetallurgySettings(getProject)
    settings.setEnabled(getModule, enabled = false)
    try
      val file = myFixture.configureByText("Inactive.scala", "val untouched = 42")
      assertNull(new CompilerBackendPassFactory().createHighlightingPass(file, myFixture.getEditor))
    finally settings.setEnabled(getModule, enabled = true)

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)
