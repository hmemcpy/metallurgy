package com.hmemcpy.metallurgy.projectmodel

import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PsiTestUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.MultiScalaModulesInsightFixtureTestCase
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}

final class CompilerBackendMultiModuleDescriptorTest
    extends MultiScalaModulesInsightFixtureTestCase(
      new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2"),
      new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2")
    ):

  override def setUp(): Unit =
    super.setUp()
    MetallurgySettings(getProject).setEnabled(getModule, enabled = true)
    setCompilerBasedHighlighting(enabled = true)

  override def tearDown(): Unit =
    try
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
    finally super.tearDown()

  def testDependencyOrderAndClasspathComeFromTheNormalizedModuleGraph(): Unit =
    val dependencyOutput = myFixture.getTempDirFixture.findOrCreateDir("otherModule/target/classes")
    PsiTestUtil.setCompilerOutputPath(otherModule, dependencyOutput.getUrl, false)
    val descriptor = CompilerBackendModuleDescriptor.read(getProject, getModule) match
      case CompilerBackendModelState.Ready(value) => value
      case state                                  => throw new AssertionError(s"expected ready descriptor, got $state")

    assertEquals(Vector(otherModuleName), descriptor.dependencyModules)
    assertFalse(descriptor.compileClasspath.isEmpty)
    assertTrue(
      descriptor.compileClasspath.mkString("\n"),
      descriptor.compileClasspath.exists(_.getPath == dependencyOutput.getPath)
    )
    assertEquals(CompilerBackendModelState.Inactive, CompilerBackendModuleDescriptor.read(getProject, otherModule))

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)
