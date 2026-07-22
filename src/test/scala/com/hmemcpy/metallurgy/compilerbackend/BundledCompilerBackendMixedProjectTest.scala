package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.pc.*
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.{PsiDocumentManager, SmartPointerManager}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.MultiScalaModulesInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.{assertEquals, assertNull, assertTrue}

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
    assertTrue(ScalaPluginSemanticBridge.install().isEnabled)

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
    val publisher                                            = new CompilerBackendSnapshotPublisher(getProject)
    val computation: Computable[Seq[CompilerBackendMapping]] = () => publisher.mapCurrentFile(getModule, snapshot)

    assertTrue(ApplicationManager.getApplication.runReadAction(computation).isEmpty)

  def testDisablingOneActiveModuleRetiresOnlyItsSnapshotState(): Unit =
    val activeFile   = myFixture.configureByText("ActiveSnapshot.scala", "val active = List(1).head")
    val otherFile    = myFixture.addFileToProject(
      s"$otherModuleSourceDir/OtherSnapshot.scala",
      "val other = List(2).head"
    )
    val activeExpr   = PsiTreeUtil
      .findChildrenOfType(activeFile, classOf[ScExpression])
      .stream()
      .filter(_.getText == "List(1).head")
      .findFirst()
      .orElseThrow()
    val otherExpr    = PsiTreeUtil
      .findChildrenOfType(otherFile, classOf[ScExpression])
      .stream()
      .filter(_.getText == "List(2).head")
      .findFirst()
      .orElseThrow()
    val documents    = PsiDocumentManager.getInstance(getProject)
    val activeDoc    = documents.getDocument(activeFile)
    val otherDoc     = documents.getDocument(otherFile)
    val generation   = CompilerBackendGeneration(1L, 1L, 1L)
    val backend      = Scala3CompilerBackend.get(getProject)
    MetallurgySettings(getProject).setEnabled(otherModule, enabled = true)
    backend.markPending(getModule, activeFile.getVirtualFile.getUrl, activeDoc.getModificationStamp, generation)
    backend.markPending(otherModule, otherFile.getVirtualFile.getUrl, otherDoc.getModificationStamp, generation)
    val activeCommit =
      commitExact(backend, getModule, activeFile, activeExpr, activeDoc.getModificationStamp, generation)
    val otherCommit  = commitExact(backend, otherModule, otherFile, otherExpr, otherDoc.getModificationStamp, generation)
    assertTrue(activeCommit.toString, activeCommit.isInstanceOf[CompilerBackendCommit.Committed])
    assertTrue(otherCommit.toString, otherCommit.isInstanceOf[CompilerBackendCommit.Committed])

    MetallurgySettings(getProject).setEnabled(otherModule, enabled = false)

    assertEquals("Int", ScalaPluginSemanticBridge.getCompilerType(activeExpr))
    assertCurrent(backend, activeExpr, getModule, "Int")
    assertNull(ScalaPluginSemanticBridge.getCompilerType(otherExpr))
    assertEquals(
      CompilerBackendState.Unavailable,
      backend.stateForActiveModule(otherExpr, otherModule, CompilerBackendRole.ExpressionExact)
    )

  private def commitExact(
      backend: Scala3CompilerBackend,
      module: com.intellij.openapi.module.Module,
      file: com.intellij.psi.PsiFile,
      expression: ScExpression,
      version: Long,
      generation: CompilerBackendGeneration
  ): CompilerBackendCommit =
    val range                                     = expression.getTextRange
    val mapping                                   = CompilerBackendMapping(
      SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(expression),
      PcSourceRange(range.getStartOffset, range.getEndOffset),
      CompilerBackendRole.ExpressionExact,
      "Int",
      None
    )
    val commit: Computable[CompilerBackendCommit] = () =>
      backend.commitSnapshot(module, file, version, generation, Seq(mapping))(PcSnapshotCurrency.Current)
    ApplicationManager.getApplication.runReadAction(commit)

  private def assertCurrent(
      backend: Scala3CompilerBackend,
      expression: ScExpression,
      module: com.intellij.openapi.module.Module,
      expected: String
  ): Unit =
    backend.stateForActiveModule(expression, module, CompilerBackendRole.ExpressionExact) match
      case CompilerBackendState.Current(actual, _) => assertEquals(expected, actual)
      case state                                   => throw new AssertionError(s"expected Current, got $state")

  private def rendered(typeElement: ScTypeElement): String =
    typeElement.`type`().fold(failure => throw new AssertionError(failure.toString), _.canonicalText)

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)
