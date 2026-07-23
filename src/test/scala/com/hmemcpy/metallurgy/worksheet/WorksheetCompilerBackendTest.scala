package com.hmemcpy.metallurgy.worksheet

import com.hmemcpy.metallurgy.compilerbackend.{CompilerBackendRole, CompilerBackendState, Scala3CompilerBackend}
import com.hmemcpy.metallurgy.pc.PcSessionManager
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.editor.documentationProvider.ScalaDocQuickInfoGenerator
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScBindingPattern
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}

import scala.jdk.CollectionConverters.*

final class WorksheetCompilerBackendTest extends ScalaLightCodeInsightFixtureTestCase:

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
      Scala3CompilerBackend.get(getProject).clear()
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
    finally super.tearDown()

  def testWorksheetSourceUsesTheSameVersionedCompilerBackend(): Unit =
    val file = configureWorksheet(
      "plain.sc",
      """val identity = [A] => (value: A) => value
        |val result = identity(42)
        |""".stripMargin
    )

    prepare(file)
    val binding = bindingNamed(file, "result")
    val state   = Scala3CompilerBackend
      .get(getProject)
      .stateForActiveModule(binding, getModule, CompilerBackendRole.Binding)

    assertCurrent("worksheet", state)
    val quickInfo = ScalaDocQuickInfoGenerator.getQuickNavigateInfo(binding, binding).getOrElse("")
    assertTrue(s"worksheet hover did not use the compiler result: $quickInfo", quickInfo.contains("Int"))

  def testWorksheetEditRetiresThePreviousGenerationBeforePublishingTheNext(): Unit =
    val file    = configureWorksheet(
      "generation.sc",
      "val result = List(1).head"
    )
    val binding = bindingNamed(file, "result")
    prepare(file)
    assertCurrent("initial worksheet generation", state(binding))

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.setText("val result = List(\"updated\").head")
    )
    com.intellij.psi.PsiDocumentManager.getInstance(getProject).commitAllDocuments()

    assertFalse("the old worksheet generation remained current after an edit", isCurrent(state(binding)))
    prepare(file)
    val currentBinding = bindingNamed(file, "result")
    state(currentBinding) match
      case CompilerBackendState.Current(renderedType, _) => assertEquals("String", renderedType)
      case other                                         => throw new AssertionError(s"next generation is $other")

  def testInactiveWorksheetDoesNotAllocateBackendWork(): Unit =
    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
    val file = configureWorksheet(
      "inactive.sc",
      "val result = List(1).head"
    )

    val prepared = PlatformTestUtil.waitForFuture(
      PcSessionManager.get(getProject).prepareCompilerBackend(file.getVirtualFile),
      60000L
    )

    assertTrue(prepared.isEmpty)
    assertEquals(0, PcSessionManager.get(getProject).activeSessionCount)

  private def configureWorksheet(
      name: String,
      source: String
  ): PsiFile =
    val file = myFixture.configureByText(name, source)
    assertTrue(file.getLanguage.getID, file.getLanguage.getID.startsWith("Scala"))
    file

  private def prepare(file: PsiFile): Unit =
    val prepared = PlatformTestUtil.waitForFuture(
      PcSessionManager.get(getProject).prepareCompilerBackend(file.getVirtualFile),
      60000L
    )
    assertTrue("worksheet backend preparation failed", prepared.nonEmpty)

  private def bindingNamed(file: PsiFile, name: String): ScBindingPattern =
    PsiTreeUtil
      .findChildrenOfType(file, classOf[ScBindingPattern])
      .asScala
      .find(_.name == name)
      .getOrElse(throw new AssertionError(s"missing worksheet binding $name in ${file.getText}"))

  private def state(binding: ScBindingPattern): CompilerBackendState =
    Scala3CompilerBackend
      .get(getProject)
      .stateForActiveModule(binding, getModule, CompilerBackendRole.Binding)

  private def isCurrent(state: CompilerBackendState): Boolean =
    state match
      case CompilerBackendState.Current(_, _) => true
      case _                                  => false

  private def assertCurrent(label: String, state: CompilerBackendState): Unit =
    assertTrue(s"$label did not receive a current compiler result: $state", isCurrent(state))

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)
