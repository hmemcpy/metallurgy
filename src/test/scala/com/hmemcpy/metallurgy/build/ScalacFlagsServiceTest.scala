package com.hmemcpy.metallurgy.build

import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.hmemcpy.metallurgy.pc.{PcCapabilityStatus, Scala3PcBridgeCapabilities}
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}

import java.nio.file.Path

final class ScalacFlagsServiceTest extends ScalaLightCodeInsightFixtureTestCase:

  private val testScalaVersion = ScalaVersion.fromString("3.5.2").get

  override def getTestDataPath: String =
    Path.of("src", "test", "testdata").toAbsolutePath.toString

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == testScalaVersion

  override protected def defaultVersionOverride: Option[ScalaVersion] =
    Some(testScalaVersion)

  def testOptInDoesNotAssumeOptionalCompilerCapabilities(): Unit =
    val settings = MetallurgySettings(getProject)
    val service  = ScalacFlagsService.get(getProject)

    setCompilerBasedHighlighting(enabled = true)
    settings.setEnabled(getModule, enabled = true)

    assertFalse(service.additionalOptions(getModule).exists(ScalacFlagsService.BestEffortFlags.contains))

    service.enableFor(getModule, Scala3PcBridgeCapabilities.bestEffort)
    service.enableFor(getModule, Scala3PcBridgeCapabilities.bestEffort)

    val enabledOptions = service.additionalOptions(getModule)
    ScalacFlagsService.BestEffortFlags.foreach: flag =>
      assertEquals(flag, 1, enabledOptions.count(_ == flag))

    settings.setEnabled(getModule, enabled = false)

    val disabledOptions = service.additionalOptions(getModule)
    assertFalse(disabledOptions.exists(ScalacFlagsService.ManagedFlags.contains))

  def testSemanticDbFlagFollowsItsSetting(): Unit =
    val settings = MetallurgySettings(getProject)
    val service  = ScalacFlagsService.get(getProject)

    setCompilerBasedHighlighting(enabled = true)
    settings.setXsemanticdbEnabled(true)
    settings.setEnabled(getModule, enabled = true)
    service.enableFor(getModule, Scala3PcBridgeCapabilities.bestEffort)
    assertTrue(service.additionalOptions(getModule).contains(ScalacFlagsService.SemanticDbFlag))

    settings.setXsemanticdbEnabled(false)
    assertFalse(service.additionalOptions(getModule).contains(ScalacFlagsService.SemanticDbFlag))

  def testPresentationCompilerConsumesButDoesNotProduceBestEffortTasty(): Unit =
    val settings = MetallurgySettings(getProject)
    val service  = ScalacFlagsService.get(getProject)

    setCompilerBasedHighlighting(enabled = true)
    settings.setEnabled(getModule, enabled = true)
    service.enableFor(getModule, Scala3PcBridgeCapabilities.bestEffort)

    val options = service.presentationCompilerOptions(getModule)
    assertTrue(options.contains(ScalacFlagsService.BestEffortConsumerFlag))
    assertFalse(options.contains(ScalacFlagsService.BestEffortProducerFlag))

  def testBestEffortProducerAndConsumerCapabilitiesAreIndependent(): Unit =
    val settings = MetallurgySettings(getProject)
    val service  = ScalacFlagsService.get(getProject)

    setCompilerBasedHighlighting(enabled = true)
    settings.setEnabled(getModule, enabled = true)
    service.enableFor(
      getModule,
      Scala3PcBridgeCapabilities.unavailable.copy(bestEffortProduction = PcCapabilityStatus.Available)
    )

    val buildOptions = service.additionalOptions(getModule)
    assertTrue(buildOptions.contains(ScalacFlagsService.BestEffortProducerFlag))
    assertFalse(buildOptions.contains(ScalacFlagsService.BestEffortConsumerFlag))
    assertFalse(service.presentationCompilerOptions(getModule).exists(ScalacFlagsService.BestEffortFlags.contains))

  def testGlobalOptInStillWaitsForCapabilityDiscovery(): Unit =
    val settings = MetallurgySettings(getProject)
    val service  = ScalacFlagsService.get(getProject)

    setCompilerBasedHighlighting(enabled = true)
    settings.setGloballyEnabled(true)
    settings.setEnabled(getModule, enabled = false)

    assertFalse(service.additionalOptions(getModule).exists(ScalacFlagsService.BestEffortFlags.contains))

  def testOptInDoesNotApplyFlagsUntilCompilerBackendIsActive(): Unit =
    val settings = MetallurgySettings(getProject)
    val service  = ScalacFlagsService.get(getProject)

    setCompilerBasedHighlighting(enabled = false)
    settings.setEnabled(getModule, enabled = true)

    assertFalse(service.additionalOptions(getModule).exists(ScalacFlagsService.ManagedFlags.contains))

    setCompilerBasedHighlighting(enabled = true)
    UIUtil.dispatchAllInvocationEvents()

    assertFalse(service.additionalOptions(getModule).exists(ScalacFlagsService.BestEffortFlags.contains))

  def testCompilerBackendDeactivationRemovesManagedFlags(): Unit =
    val settings = MetallurgySettings(getProject)
    val service  = ScalacFlagsService.get(getProject)

    setCompilerBasedHighlighting(enabled = true)
    settings.setXsemanticdbEnabled(true)
    settings.setEnabled(getModule, enabled = true)
    service.enableFor(getModule, Scala3PcBridgeCapabilities.bestEffort)
    assertTrue(ScalacFlagsService.ManagedFlags.forall(service.additionalOptions(getModule).contains))

    setCompilerBasedHighlighting(enabled = false)
    UIUtil.dispatchAllInvocationEvents()

    assertFalse(service.additionalOptions(getModule).exists(ScalacFlagsService.ManagedFlags.contains))

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)

  override protected def tearDown(): Unit =
    try
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      MetallurgySettings(getProject).setGloballyEnabled(false)
      MetallurgySettings(getProject).setXsemanticdbEnabled(false)
      setCompilerBasedHighlighting(enabled = false)
      UIUtil.dispatchAllInvocationEvents()
    finally super.tearDown()
