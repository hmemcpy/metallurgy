package com.hmemcpy.metallurgy.execution

import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge
import com.hmemcpy.metallurgy.pc.PcSessionManager
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.execution.actions.{ConfigurationContext, RunConfigurationProducer}
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testIntegration.TestFramework
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.jetbrains.plugins.scala.runner.ScalaApplicationConfigurationProducer
import org.jetbrains.plugins.scala.testingSupport.test.munit.MUnitConfigurationProducer
import org.jetbrains.plugins.scala.testingSupport.test.scalatest.ScalaTestConfigurationProducer
import org.jetbrains.plugins.scala.testingSupport.test.specs2.Specs2ConfigurationProducer
import org.jetbrains.plugins.scala.testingSupport.test.utest.UTestConfigurationProducer
import org.junit.Assert.{assertEquals, assertNotNull, assertTrue}

import scala.jdk.CollectionConverters.*

final class BundledExecutionDiscoveryTest extends ScalaLightCodeInsightFixtureTestCase:

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
    assertTrue(ScalaPluginSemanticBridge.install().isEnabled)

  override protected def tearDown(): Unit =
    try
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
    finally super.tearDown()

  def testScala3MainConfigurationRemainsBundledAndDoesNotStartTheBackend(): Unit =
    val file    = myFixture.configureByText(
      "ApplicationDiscovery.scala",
      """object Main:
        |  def main(args: Array[String]): Unit = ()
        |""".stripMargin
    )
    val target  = descendants[ScTypeDefinition](file).find(_.name == "Main").get
    val created = ScalaApplicationConfigurationProducer()
      .createConfigurationFromContext(new ConfigurationContext(target))

    assertNotNull(created)
    val configuration = created.getConfiguration.asInstanceOf[ApplicationConfiguration]
    assertEquals("Main", configuration.getMainClassName)
    assertEquals(getModule, configuration.getConfigurationModule.getModule)
    assertTrue(configuration.getClass.getName.startsWith("com.intellij.execution.application."))
    assertEquals(0, PcSessionManager.get(getProject).activeSessionCount)

  def testAllBundledFrameworkFindersPreserveSuiteDiscoveryAcrossActivation(): Unit =
    installFrameworkMarkers()
    val file       = myFixture.configureByText(
      "FrameworkDiscovery.scala",
      """class ScalaTestSuite extends org.scalatest.Suite
        |class MUnitSuite extends munit.Suite
        |class Specs2Suite extends org.specs2.mutable.Specification
        |class UTestSuite extends utest.TestSuite
        |""".stripMargin
    )
    val suites     = descendants[ScTypeDefinition](file).map(definition => definition.name -> definition).toMap
    val frameworks =
      TestFramework.EXTENSION_NAME.getExtensionList.asScala.map(framework => framework.getName -> framework).toMap
    val expected   = Seq(
      ("ScalaTest", "ScalaTestSuite", ScalaTestConfigurationProducer(), "ScalaTestRunConfiguration"),
      ("MUnit", "MUnitSuite", MUnitConfigurationProducer(), "MUnitConfiguration"),
      ("Specs2", "Specs2Suite", Specs2ConfigurationProducer(), "Specs2RunConfiguration"),
      ("uTest", "UTestSuite", UTestConfigurationProducer(), "UTestRunConfiguration")
    )

    val finders = expected.map: (frameworkName, suiteName, producer, configurationClass) =>
      val framework = frameworks.getOrElse(frameworkName, throw new AssertionError(s"missing $frameworkName finder"))
      assertTrue(framework.getClass.getName, framework.getClass.getName.startsWith("org.jetbrains.plugins.scala."))
      assertTrue(s"$frameworkName did not discover $suiteName", framework.isTestClass(suites(suiteName)))
      assertConfiguration(producer, suites(suiteName), suiteName, configurationClass)
      (frameworkName, suiteName, framework)

    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
    finders.foreach: (frameworkName, suiteName, framework) =>
      assertTrue(
        s"inactive $frameworkName did not discover $suiteName",
        framework.isTestClass(suites(suiteName))
      )
    assertEquals(0, PcSessionManager.get(getProject).activeSessionCount)

  private def installFrameworkMarkers(): Unit =
    val _ = myFixture.addClass("package org.scalatest; public abstract class Suite {}")
    val _ = myFixture.addClass("package munit; public abstract class Suite {}")
    val _ = myFixture.addClass(
      "package org.specs2.specification; public interface SpecificationStructure {}"
    )
    val _ = myFixture.addClass(
      "package org.specs2.mutable; public class Specification implements org.specs2.specification.SpecificationStructure {}"
    )
    val _ = myFixture.addClass("package utest; public abstract class TestSuite {}")

  private def assertConfiguration(
      producer: RunConfigurationProducer[?],
      suite: ScTypeDefinition,
      suiteName: String,
      expectedClass: String
  ): Unit =
    val created = producer.createConfigurationFromContext(new ConfigurationContext(suite))
    assertNotNull(s"no configuration for $suiteName", created)
    assertEquals(expectedClass, created.getConfiguration.getClass.getSimpleName)
    assertTrue(created.getConfiguration.getName, created.getConfiguration.getName.contains(suiteName))

  private def descendants[A <: com.intellij.psi.PsiElement: reflect.ClassTag](
      element: com.intellij.psi.PsiElement
  ): Seq[A] =
    PsiTreeUtil
      .findChildrenOfType(element, reflect.classTag[A].runtimeClass.asInstanceOf[Class[A]])
      .asScala
      .toSeq

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[com.intellij.openapi.project.Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)
