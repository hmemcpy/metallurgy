package com.hmemcpy.metallurgy.build

import com.hmemcpy.metallurgy.settings.MetallurgySettings
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

  def testOptInAddsFlagsExactlyOnceAndOptOutRemovesThem(): Unit =
    val settings = MetallurgySettings(getProject)
    val service  = ScalacFlagsService.get(getProject)

    settings.setEnabled(getModule, enabled = true)
    settings.setEnabled(getModule, enabled = true)

    val enabledOptions = service.additionalOptions(getModule)
    ScalacFlagsService.RequiredFlags.foreach: flag =>
      assertEquals(flag, 1, enabledOptions.count(_ == flag))

    settings.setEnabled(getModule, enabled = false)

    val disabledOptions = service.additionalOptions(getModule)
    assertFalse(disabledOptions.exists(ScalacFlagsService.ManagedFlags.contains))

  def testSemanticDbFlagFollowsItsSetting(): Unit =
    val settings = MetallurgySettings(getProject)
    val service  = ScalacFlagsService.get(getProject)

    settings.setXsemanticdbEnabled(true)
    settings.setEnabled(getModule, enabled = true)
    assertTrue(service.additionalOptions(getModule).contains(ScalacFlagsService.SemanticDbFlag))

    settings.setXsemanticdbEnabled(false)
    assertFalse(service.additionalOptions(getModule).contains(ScalacFlagsService.SemanticDbFlag))

  def testGlobalOptInKeepsFlagsWhenModuleOverrideIsRemoved(): Unit =
    val settings = MetallurgySettings(getProject)
    val service  = ScalacFlagsService.get(getProject)

    settings.setGloballyEnabled(true)
    settings.setEnabled(getModule, enabled = false)

    assertTrue(ScalacFlagsService.RequiredFlags.forall(service.additionalOptions(getModule).contains))

  override protected def tearDown(): Unit =
    try
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      MetallurgySettings(getProject).setGloballyEnabled(false)
      MetallurgySettings(getProject).setXsemanticdbEnabled(false)
    finally super.tearDown()
