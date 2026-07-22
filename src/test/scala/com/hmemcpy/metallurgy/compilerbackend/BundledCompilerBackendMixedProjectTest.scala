package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.hmemcpy.metallurgy.pc.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.MultiScalaModulesInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.{assertEquals, assertTrue}

import scala.concurrent.duration.DurationInt

final class BundledCompilerBackendMixedProjectTest
    extends MultiScalaModulesInsightFixtureTestCase(
      new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2"),
      new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2")
    ):

  override def setUp(): Unit =
    super.setUp()
    MetallurgySettings(getProject).setEnabled(getModule, enabled = true)
    setCompilerBasedHighlighting(enabled = true)
    assertTrue(BundledCompilerBackendShim.install().isEnabled)

  override def tearDown(): Unit =
    try
      Scala3CompilerBackend.get(getProject).clear()
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
    finally super.tearDown()

  def testMixedProjectKeepsInactiveModuleOnBundledBackend(): Unit =
    val activeFile          = myFixture.configureByText("Active.scala", "val value: String = \"text\"")
    val inactiveFile        = myFixture.addFileToProject(
      s"$otherModuleSourceDir/Inactive.scala",
      "val value: String = \"text\""
    )
    val activeTypeElement   = PsiTreeUtil.findChildOfType(activeFile, classOf[ScTypeElement])
    val inactiveTypeElement = PsiTreeUtil.findChildOfType(inactiveFile, classOf[ScTypeElement])
    val activeDocument      = PsiDocumentManager.getInstance(getProject).getDocument(activeFile)
    val inactiveDocument    = PsiDocumentManager.getInstance(getProject).getDocument(inactiveFile)
    val backend             = Scala3CompilerBackend.get(getProject)
    MetallurgySettings(getProject).setEnabled(otherModule, enabled = true)

    assertEquals(
      CompilerBackendPublication.Published,
      backend.publish(
        activeTypeElement,
        CompilerBackendRole.DeclaredType,
        activeDocument.getModificationStamp,
        "Int"
      )
    )
    assertEquals(
      CompilerBackendPublication.Published,
      backend.publish(
        inactiveTypeElement,
        CompilerBackendRole.DeclaredType,
        inactiveDocument.getModificationStamp,
        "Int"
      )
    )
    MetallurgySettings(getProject).setEnabled(otherModule, enabled = false)
    assertEquals("Int", rendered(activeTypeElement))
    assertEquals("_root_.scala.Predef.String", rendered(inactiveTypeElement))

  def testSnapshotCannotCrossItsOwningModuleBoundary(): Unit =
    val inactiveFile                                         = myFixture.addFileToProject(
      s"$otherModuleSourceDir/OwnedByOther.scala",
      "val value: String = \"text\""
    )
    val typeElement                                          = PsiTreeUtil.findChildOfType(inactiveFile, classOf[ScTypeElement])
    val document                                             = PsiDocumentManager.getInstance(getProject).getDocument(inactiveFile)
    val range                                                = typeElement.getTextRange
    val entry                                                = PcTypedTreeEntry(
      PcSourceRange(range.getStartOffset, range.getEndOffset),
      PcTypedTreeRole.Declared,
      "Int",
      None
    )
    val snapshot                                             = PcTypedTreeSnapshot(
      inactiveFile.getVirtualFile.getUrl,
      document.getModificationStamp,
      Vector(entry),
      PcTypedTreeMetrics(0.nanos, 0.nanos, 0.nanos, 0, 0, 1, 1, 0, 0, 1)
    )
    val publisher                                            = new CompilerBackendSnapshotPublisher(getProject, (_, _, _) => PcSnapshotCurrency.Current)
    val computation: Computable[Seq[CompilerBackendMapping]] = () => publisher.mapCurrentFile(getModule, snapshot)

    assertTrue(ApplicationManager.getApplication.runReadAction(computation).isEmpty)

  private def rendered(typeElement: ScTypeElement): String =
    typeElement.`type`().fold(failure => throw new AssertionError(failure.toString), _.canonicalText)

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)
