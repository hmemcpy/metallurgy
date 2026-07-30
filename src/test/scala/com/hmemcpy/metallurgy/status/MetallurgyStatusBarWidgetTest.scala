package com.hmemcpy.metallurgy.status

import com.hmemcpy.metallurgy.pc.{
  ParserSyntaxSnapshot,
  Scala3ParserArtifactCoordinate,
  Scala3ParserArtifactIdentity,
  Scala3ParserCompilerIdentity,
  Scala3ParserLoaderIdentity
}
import com.hmemcpy.metallurgy.psiproducer.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.{StatusBar, StatusBarWidget}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import scala.collection.mutable.ArrayBuffer

final class MetallurgyStatusBarWidgetTest extends BasePlatformTestCase:

  def testFactoryAvailabilityDoesNotDependOnProjectOpenState(): Unit =
    val earlyProject =
      Proxy
        .newProxyInstance(
          classOf[Project].getClassLoader,
          Array(classOf[Project]),
          new InvocationHandler:
            override def invoke(_proxy: Any, method: Method, _arguments: Array[AnyRef]): AnyRef =
              if method.getName == "isOpen" then Boolean.box(false)
              else throw new AssertionError(s"Unexpected project access: ${method.getName}")
        )
        .asInstanceOf[Project]

    assertFalse(earlyProject.isOpen)
    assertTrue(new MetallurgyStatusBarWidgetFactory().isAvailable(earlyProject))

  def testFactoryKeepsTheWidgetAvailableAndEnabledByDefault(): Unit =
    val factory = new MetallurgyStatusBarWidgetFactory
    val widget  = factory.createWidget(getProject)
    try
      assertEquals("Metallurgy", factory.getId)
      assertEquals("Metallurgy", factory.getDisplayName)
      assertTrue(factory.isAvailable(getProject))
      assertTrue(factory.isEnabledByDefault)
      assertEquals("Metallurgy", widget.ID())
      assertEquals("Metallurgy: enabled", presentation(widget).getText)
    finally Disposer.dispose(widget)

  def testPreparingPresentationDisplaysTheCompleteModuleReport(): Unit =
    val report  = capabilityReport(
      Scala3SyntaxCapabilityState.Preparing,
      None,
      Scala3SyntaxCapabilityOperation.PrepareExactParser,
      Scala3SyntaxCapabilityRequirement.Capability("exact-parser-preparation"),
      Scala3SyntaxCapabilityEvidence(
        Scala3SyntaxCapabilityEvidenceState.Collecting,
        Scala3SyntaxCapabilityStage.Preparation,
        "exact parser artifacts and capabilities are being prepared"
      ),
      Scala3SyntaxCapabilityRemediationState.AwaitingPreparation,
      None
    )
    val visible = MetallurgyStatusBarWidget.syntaxPresentation(Vector(report)).get

    assertEquals("Metallurgy: preparing syntax…", visible.text)
    assertEquals(
      "<html><b>Metallurgy exact Scala syntax capability</b><br><br>" +
        "<b>Report 1 of 1</b><br>" +
        "<b>State:</b> Preparing<br>" +
        "<b>Affected scope and operation:</b> module=syntax-module; operation=PrepareExactParser<br>" +
        "<b>Missing capability or stable role:</b> capability=exact-parser-preparation<br>" +
        "<b>Compiler identity:</b> org.scala-lang:scala3-compiler_3:3.7.4; loader=7; " +
        "artifacts=scala3-compiler_3-3.7.4.jar[bytes=123;sha256=abc123;ordinal=0]<br>" +
        "<b>Host identity:</b> IDE build=261.26222.65; Scala plugin=org.intellij.scala:2026.1.20<br>" +
        "<b>Retained safe operations:</b> ReadVerbatimSource, EditVerbatimSource<br>" +
        "<b>Evidence:</b> state=Collecting; stage=Preparation; " +
        "detail=exact parser artifacts and capabilities are being prepared<br>" +
        "<b>Remediation and retry:</b> AwaitingPreparation<br>" +
        "<b>Preparation epoch:</b> 42</html>",
      visible.tooltip
    )

  def testUnavailablePresentationDisplaysUnsupportedRoleAndFailedBindingOnce(): Unit =
    val source      = "class Unsupported\n"
    val file        = myFixture.addFileToProject("src/Unsupported.scala", source).getVirtualFile
    val unsupported = capabilityReport(
      Scala3SyntaxCapabilityState.Unavailable,
      Some(file),
      Scala3SyntaxCapabilityOperation.ProduceWholeFilePsi,
      Scala3SyntaxCapabilityRequirement.OutputRole(Some("integer-literal")),
      Scala3SyntaxCapabilityEvidence(
        Scala3SyntaxCapabilityEvidenceState.Recorded,
        Scala3SyntaxCapabilityStage.Planner,
        "unsupported closed output forest"
      ),
      Scala3SyntaxCapabilityRemediationState.ImplementationRequired,
      Some(ParserSyntaxSnapshot.digest(source))
    )
    val binding     = capabilityReport(
      Scala3SyntaxCapabilityState.Unavailable,
      None,
      Scala3SyntaxCapabilityOperation.BindPsiRoles,
      Scala3SyntaxCapabilityRequirement.Capability("psi-role-binding"),
      Scala3SyntaxCapabilityEvidence(
        Scala3SyntaxCapabilityEvidenceState.Recorded,
        Scala3SyntaxCapabilityStage.PsiRoleBinding,
        "output roles have no element-type binding"
      ),
      Scala3SyntaxCapabilityRemediationState.ImplementationRequired,
      None
    )
    val visible     = MetallurgyStatusBarWidget.syntaxPresentation(Vector(unsupported, unsupported, binding)).get

    assertEquals("Metallurgy: syntax unavailable (2)", visible.text)
    assertTrue(visible.tooltip.contains("<b>Report 1 of 2</b>"))
    assertTrue(visible.tooltip.contains("<b>Report 2 of 2</b>"))
    assertFalse(visible.tooltip.contains("Report 3"))
    assertTrue(visible.tooltip.contains("operation=BindPsiRoles"))
    assertTrue(visible.tooltip.contains("capability=psi-role-binding"))
    assertTrue(visible.tooltip.contains("stage=PsiRoleBinding"))
    assertTrue(visible.tooltip.contains("operation=ProduceWholeFilePsi"))
    assertTrue(visible.tooltip.contains("file=" + file.getPresentableUrl))
    assertTrue(visible.tooltip.contains("output-role=integer-literal"))
    assertTrue(visible.tooltip.contains("ReadVerbatimSource, EditVerbatimSource"))
    assertEquals(1, occurrences(visible.tooltip, "unsupported closed output forest"))

  def testIdenticalFailurePublishesOnceAndPersistsAcrossWidgetRecreation(): Unit =
    val source   = "class PersistentUnsupported\n"
    val file     = myFixture.addFileToProject("src/PersistentUnsupported.scala", source).getVirtualFile
    val failure  = capabilityFailure(source, "persistent unsupported role")
    val statuses = ArrayBuffer.empty[MetallurgyStatus]
    getProject.getMessageBus
      .connect(getTestRootDisposable)
      .subscribe(
        MetallurgyStatus.Topic,
        new MetallurgyStatusListener:
          override def statusChanged(status: MetallurgyStatus): Unit = statuses += status
      )
    val service  = Scala3SyntaxCapabilityService.get(getProject)
    service.publish(file, None, failure)
    service.publish(file, None, failure)

    assertEquals(
      1,
      statuses.count:
        case MetallurgyStatus.SyntaxCapability(report) =>
          report.scope.file.contains(file) && report.evidence.detail == failure.detail
        case _                                         => false
    )
    assertEquals(1, service.currentFailures.size)
    val factory      = new MetallurgyStatusBarWidgetFactory
    val first        = factory.createWidget(getProject)
    val firstText    = presentation(first).getText
    val firstTooltip = presentation(first).getTooltipText
    Disposer.dispose(first)
    val recreated    = factory.createWidget(getProject)
    try
      assertEquals("Metallurgy: syntax unavailable", firstText)
      assertEquals(firstText, presentation(recreated).getText)
      assertEquals(firstTooltip, presentation(recreated).getTooltipText)
      assertEquals(1, occurrences(firstTooltip, failure.detail))
    finally Disposer.dispose(recreated)

  def testStaleAndExactResolutionClearOnlyTheMatchingVisibleFinding(): Unit =
    val firstSource  = "class FirstUnsupported\n"
    val secondSource = "class SecondUnsupported\n"
    val firstFile    = myFixture.addFileToProject("src/FirstUnsupported.scala", firstSource).getVirtualFile
    val secondFile   = myFixture.addFileToProject("src/SecondUnsupported.scala", secondSource).getVirtualFile
    val stale        = capabilityFailure(firstSource, "stale first evidence")
    val current      = stale.copy(detail = "current first evidence")
    val second       = capabilityFailure(secondSource, "second evidence")
    val service      = Scala3SyntaxCapabilityService.get(getProject)
    val updates      = ArrayBuffer.empty[String]
    val widget       = new MetallurgyStatusBarWidgetFactory().createWidget(getProject)
    widget.install(recordingStatusBar(updates))
    try
      service.publish(firstFile, None, stale)
      service.publish(firstFile, None, current)
      service.publish(secondFile, None, second)
      assertEquals("Metallurgy: syntax unavailable (2)", presentation(widget).getText)

      service.resolve(firstFile, stale, compilerIdentity)
      assertTrue(presentation(widget).getTooltipText.contains(current.detail))
      assertTrue(presentation(widget).getTooltipText.contains(second.detail))

      service.resolve(firstFile, current, compilerIdentity)
      assertEquals("Metallurgy: syntax unavailable", presentation(widget).getText)
      assertFalse(presentation(widget).getTooltipText.contains(current.detail))
      assertTrue(presentation(widget).getTooltipText.contains(second.detail))

      service.resolve(secondFile, second, compilerIdentity)
      assertEquals("Metallurgy: enabled", presentation(widget).getText)
      assertFalse(presentation(widget).getTooltipText.contains(second.detail))
      assertEquals(Vector.fill(5)("Metallurgy"), updates.toVector)
    finally Disposer.dispose(widget)

  private def capabilityReport(
      state: Scala3SyntaxCapabilityState,
      file: Option[VirtualFile],
      operation: Scala3SyntaxCapabilityOperation,
      requirement: Scala3SyntaxCapabilityRequirement,
      evidence: Scala3SyntaxCapabilityEvidence,
      remediation: Scala3SyntaxCapabilityRemediationState,
      sourceDigest: Option[String]
  ): Scala3SyntaxCapabilityReport =
    Scala3SyntaxCapabilityReport(
      state,
      Some(compilerIdentity.coordinate),
      Some(compilerIdentity),
      Scala3SyntaxHostIdentity("261.26222.65", "org.intellij.scala", Some("2026.1.20")),
      Scala3SyntaxCapabilityScope("syntax-module", file, operation),
      requirement,
      Scala3SyntaxCapabilityService.RetainedOperations,
      evidence,
      remediation,
      sourceDigest,
      ParserPreparationEpoch(42L)
    )

  private def capabilityFailure(source: String, detail: String): Scala3SyntaxCapabilityFailure =
    Scala3SyntaxCapabilityFailure(
      ParserSyntaxSnapshot.digest(source),
      Scala3SyntaxCapabilityStage.Planner,
      detail,
      ParserPreparationEpoch(42L),
      Some(compilerIdentity),
      Scala3SyntaxCapabilityRequirement.GrammarRole(Some("template-definition"))
    )

  private def presentation(widget: StatusBarWidget): StatusBarWidget.TextPresentation =
    widget.getPresentation.asInstanceOf[StatusBarWidget.TextPresentation]

  private def recordingStatusBar(updates: ArrayBuffer[String]): StatusBar =
    Proxy
      .newProxyInstance(
        classOf[StatusBar].getClassLoader,
        Array(classOf[StatusBar]),
        new InvocationHandler:
          override def invoke(_proxy: Any, method: Method, arguments: Array[AnyRef]): AnyRef =
            if method.getName == "updateWidget" then updates += arguments(0).toString
            null
      )
      .asInstanceOf[StatusBar]

  private def occurrences(value: String, needle: String): Int =
    value.sliding(needle.length).count(_ == needle)

  private val compilerIdentity =
    Scala3ParserCompilerIdentity(
      Scala3ParserArtifactCoordinate("org.scala-lang", "scala3-compiler_3", "3.7.4"),
      Vector(
        Scala3ParserArtifactIdentity(
          "scala3-compiler_3-3.7.4.jar",
          "/exact/scala3-compiler_3-3.7.4.jar",
          123L,
          "abc123"
        )
      ),
      Scala3ParserLoaderIdentity(7L)
    )
