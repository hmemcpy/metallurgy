package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.feature.compilertype.TypeRenderer
import com.hmemcpy.metallurgy.module.BundledPluginBridge
import com.hmemcpy.metallurgy.pc.{PcSessionManager, PcSnapshot}
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.{assertEquals, assertFalse, assertNotNull, assertSame, assertTrue}

import java.lang.management.ManagementFactory
import java.util.Arrays
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

final class BundledCompilerBackendShimTest extends ScalaLightCodeInsightFixtureTestCase:

  private final case class FastPathMeasurement(bytesPerCall: Double, p50: Long, p95: Long, max: Long, result: Object)

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
    assertTrue(BundledCompilerBackendShim.install().isEnabled)

  override protected def tearDown(): Unit =
    try
      Scala3CompilerBackend.get(getProject).clear()
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
    finally super.tearDown()

  def testPublishedCompilerTypeReplacesDeclaredType(): Unit =
    val file        = myFixture.configureByText("DeclaredType.scala", "val value: String = \"text\"")
    val typeElement = PsiTreeUtil.findChildOfType(file, classOf[ScTypeElement])
    val version     = myFixture.getEditor.getDocument.getModificationStamp

    assertEquals("_root_.scala.Predef.String", rendered(typeElement))
    assertEquals(
      CompilerBackendPublication.Published,
      Scala3CompilerBackend.get(getProject).publish(typeElement, CompilerBackendRole.DeclaredType, version, "Int")
    )
    assertEquals("Int", rendered(typeElement))

  def testRealPresentationCompilerResultFlowsThroughDeclaredTypeBackend(): Unit =
    val source       = "val value: String = \"text\""
    val file         = myFixture.configureByText("PcDeclaredType.scala", source)
    val typeElement  = PsiTreeUtil.findChildOfType(file, classOf[ScTypeElement])
    val document     = myFixture.getEditor.getDocument
    val session      = PlatformTestUtil
      .waitForFuture(PcSessionManager.get(getProject).prepareFile(file.getVirtualFile), 60000L)
      .getOrElse(throw new AssertionError("presentation compiler session was unavailable"))
    val snapshot     = PcSnapshot(file.getVirtualFile.getUrl, document.getModificationStamp, document.getText)
    val typeRange    = typeElement.getTextRange
    val compilerType = ApplicationManager.getApplication
      .executeOnPooledThread(() => TypeRenderer.render(session, snapshot, typeRange))
      .get(30, TimeUnit.SECONDS)
      .getOrElse(throw new AssertionError("presentation compiler returned no declared type"))
    assertEquals(
      CompilerBackendPublication.Published,
      Scala3CompilerBackend
        .get(getProject)
        .publish(typeElement, CompilerBackendRole.DeclaredType, snapshot.documentVersion, compilerType)
    )
    val selectedType = typeElement.`type`().fold(failure => throw new AssertionError(failure.toString), identity)
    Scala3CompilerBackend
      .get(getProject)
      .stateForActiveModule(typeElement, getModule, CompilerBackendRole.DeclaredType) match
      case CompilerBackendState.Current(renderedType, result) =>
        assertEquals(compilerType, renderedType)
        assertSame(selectedType, result.fold(failure => throw new AssertionError(failure.toString), identity))
      case state                                              => throw new AssertionError(s"expected current compiler result, got $state")

  def testPendingBackendFallsThroughToBundledType(): Unit =
    assertStateFallsThrough(CompilerBackendState.Pending)

  def testUnavailableBackendFallsThroughToBundledType(): Unit =
    assertStateFallsThrough(CompilerBackendState.Unavailable)

  def testFailedBackendFallsThroughToBundledType(): Unit =
    assertStateFallsThrough(CompilerBackendState.Failed)

  def testStaleDocumentVersionFallsThroughToBundledType(): Unit =
    val (typeElement, version) = declaredString("StaleVersion.scala")
    val _                      = Scala3CompilerBackend
      .get(getProject)
      .publish(typeElement, CompilerBackendRole.DeclaredType, version - 1L, "Int")

    assertEquals("_root_.scala.Predef.String", rendered(typeElement))

  def testNonOptedInModuleFallsThroughToBundledType(): Unit =
    val (typeElement, version) = declaredString("NotOptedIn.scala")
    val _                      = Scala3CompilerBackend
      .get(getProject)
      .publish(typeElement, CompilerBackendRole.DeclaredType, version, "Int")
    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)

    assertEquals("_root_.scala.Predef.String", rendered(typeElement))

  def testInactiveModuleRejectsPublication(): Unit =
    val (typeElement, version) = declaredString("RejectedPublication.scala")
    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)

    assertEquals(
      CompilerBackendPublication.IgnoredInactive,
      Scala3CompilerBackend.get(getProject).publish(typeElement, CompilerBackendRole.DeclaredType, version, "Int")
    )
    assertEquals("_root_.scala.Predef.String", rendered(typeElement))

  def testActiveCompilerTypeReadRejectsCopiedSlotWithoutCurrentSideTableEntry(): Unit =
    val file       = myFixture.configureByText("RejectedCopiedSlot.scala", "val value = List(1).head")
    val expression = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScExpression])
      .stream()
      .filter(_.getText == "List(1).head")
      .findFirst()
      .orElseThrow()
    BundledPluginBridge.setCompilerType(expression, "String")
    val copied     = expression.copy().asInstanceOf[ScExpression]

    assertTrue(compilerType(copied).isEmpty)
    assertTrue(Option(BundledPluginBridge.getCompilerType(copied)).isEmpty)
    assertTrue(compilerType(expression).isEmpty)
    assertTrue(Option(BundledPluginBridge.getCompilerType(expression)).isEmpty)

  def testInactiveCompilerTypeReadPreservesBundledSlot(): Unit =
    val file       = myFixture.configureByText("InactiveCopiedSlot.scala", "val value = List(1).head")
    val expression = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScExpression])
      .stream()
      .filter(_.getText == "List(1).head")
      .findFirst()
      .orElseThrow()
    BundledPluginBridge.setCompilerType(expression, "String")
    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)

    assertEquals(Some("String"), compilerType(expression))
    assertEquals("String", BundledPluginBridge.getCompilerType(expression))

  def testCurrentExpressionRepairsLateBundledCompilerTypeWrite(): Unit =
    val file       = myFixture.configureByText("LateCompilerType.scala", "val value = List(1).head")
    val expression = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScExpression])
      .stream()
      .filter(_.getText == "List(1).head")
      .findFirst()
      .orElseThrow()
    val version    = myFixture.getEditor.getDocument.getModificationStamp
    val backend    = Scala3CompilerBackend.get(getProject)
    assertEquals(
      CompilerBackendPublication.Published,
      backend.publish(expression, CompilerBackendRole.ExpressionExact, version, "String")
    )
    BundledPluginBridge.setCompilerType(expression, "Boolean")

    assertEquals(Some("String"), compilerType(expression))
    assertEquals("String", BundledPluginBridge.getCompilerType(expression))

  def testLocalOptOutKeepsPublishedTypeWhenGlobalOptInRemainsActive(): Unit =
    val settings               = MetallurgySettings(getProject)
    val (typeElement, version) = declaredString("GlobalOptIn.scala")
    val _                      = Scala3CompilerBackend
      .get(getProject)
      .publish(typeElement, CompilerBackendRole.DeclaredType, version, "Int")
    settings.setGloballyEnabled(enabled = true)
    settings.setEnabled(getModule, enabled = false)

    try assertEquals("Int", rendered(typeElement))
    finally settings.setGloballyEnabled(enabled = false)

  def testCompilerHighlightingOffFallsThroughToBundledType(): Unit =
    val (typeElement, version) = declaredString("CompilerHighlightingOff.scala")
    val _                      = Scala3CompilerBackend
      .get(getProject)
      .publish(typeElement, CompilerBackendRole.DeclaredType, version, "Int")
    setCompilerBasedHighlighting(enabled = false)

    assertEquals("_root_.scala.Predef.String", rendered(typeElement))

  def testCorruptBundledClassFingerprintIsRejected(): Unit =
    val corrupted           = BundledCompilerBackendShim.supportedClassBytes().clone()
    corrupted(corrupted.length - 1) = (corrupted.last ^ 1).toByte
    val installationReached = new AtomicBoolean(false)
    val status              = BundledCompilerBackendShim.installIfCompatible(corrupted): (_, _) =>
      installationReached.set(true)
      CompilerBackendShimStatus.Enabled("unexpected", "unexpected")

    assertFalse(status.isEnabled)
    assertFalse(installationReached.get())

  def testInactiveBackendSelectorAllocationAndLatency(): Unit =
    val (typeElement, _) = declaredString("FastPath.scala")
    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
    val bridge           = Class.forName(
      "org.jetbrains.plugins.scala.lang.psi.api.base.types.MetallurgyCompilerBackendBridge",
      false,
      classOf[ScTypeElement].getClassLoader
    )
    val iterations       = 20000
    val baseline         =
      try
        val _ = bridge.getMethod("disable").invoke(null)
        measureFastPath(iterations)(typeElement.`type`().asInstanceOf[Object])
      finally
        val _ = bridge.getMethod("enable").invoke(null)
    val hooked           = measureFastPath(iterations)(typeElement.`type`().asInstanceOf[Object])
    val byteCost         = math.max(0.0, hooked.bytesPerCall - baseline.bytesPerCall)
    println(
      f"[compiler-backend-fast-path] calls=$iterations " +
        f"baseline=${baseline.bytesPerCall}%.2fB/${baseline.p50}ns/${baseline.p95}ns/${baseline.max}ns " +
        f"inactive=${hooked.bytesPerCall}%.2fB/${hooked.p50}ns/${hooked.p95}ns/${hooked.max}ns " +
        f"incremental=$byteCost%.2fB"
    )

    assertNotNull(baseline.result)
    assertNotNull(hooked.result)
    // Wall-clock latency is reported for the go/no-go record but is not a stable CI assertion on shared hosts.
    assertTrue(s"inactive installed path allocated $byteCost incremental bytes/call", byteCost <= 64.0)

  private def assertStateFallsThrough(state: CompilerBackendState): Unit =
    val (typeElement, version) = declaredString(s"${state.productPrefix}.scala")
    Scala3CompilerBackend
      .get(getProject)
      .publishState(typeElement, CompilerBackendRole.DeclaredType, version, state)

    assertEquals("_root_.scala.Predef.String", rendered(typeElement))

  private def declaredString(fileName: String): (ScTypeElement, Long) =
    val file        = myFixture.configureByText(fileName, "val value: String = \"text\"")
    val typeElement = PsiTreeUtil.findChildOfType(file, classOf[ScTypeElement])
    typeElement -> myFixture.getEditor.getDocument.getModificationStamp

  private def rendered(typeElement: ScTypeElement): String =
    typeElement.`type`().fold(failure => throw new AssertionError(failure.toString), _.canonicalText)

  private def compilerType(element: PsiElement): Option[String] =
    val moduleClass = Class.forName("org.jetbrains.plugins.scala.lang.psi.impl.CompilerType$")
    val module      = moduleClass.getField("MODULE$").get(null)
    val option      = moduleClass.getMethod("apply", classOf[PsiElement]).invoke(module, element).asInstanceOf[AnyRef]
    BundledPluginBridge.optionValue(option).map(_.toString)

  private def measureFastPath(iterations: Int)(operation: => Object): FastPathMeasurement =
    var warmup = 0
    while warmup < 5000 do
      val _ = operation
      warmup += 1

    val timings         = new Array[Long](iterations)
    val threadBean      = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    val threadId        = Thread.currentThread().threadId()
    val allocatedBefore = threadBean.getThreadAllocatedBytes(threadId)
    var result: Object  = null
    var index           = 0
    while index < iterations do
      val started = System.nanoTime()
      result = operation
      timings(index) = System.nanoTime() - started
      index += 1
    val allocatedAfter  = threadBean.getThreadAllocatedBytes(threadId)
    Arrays.sort(timings)
    FastPathMeasurement(
      bytesPerCall = (allocatedAfter - allocatedBefore).toDouble / iterations,
      p50 = timings(iterations / 2),
      p95 = timings((iterations * 95) / 100),
      max = timings.last,
      result = result
    )

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)
