package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.util.runners.{
  MultipleScalaVersionsJUnit4Runner,
  RunWithScalaVersions,
  TestScalaVersion
}
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(classOf[MultipleScalaVersionsJUnit4Runner])
@RunWithScalaVersions(Array(TestScalaVersion.Scala_2_13, TestScalaVersion.Scala_3_4))
final class BundledCompilerBackendVersionIsolationTest extends ScalaLightCodeInsightFixtureTestCase:

  override def getTestDataPath: String =
    java.nio.file.Path.of("src", "test", "testdata").toAbsolutePath.toString

  override protected def setUp(): Unit =
    super.setUp()
    MetallurgySettings(getProject).setEnabled(getModule, enabled = true)
    setCompilerBasedHighlighting(enabled = true)
    assertTrue(ScalaPluginSemanticBridge.install().isEnabled)

  override protected def tearDown(): Unit =
    try
      Scala3CompilerBackend.get(getProject).clear()
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
    finally super.tearDown()

  @Test
  def testUnsupportedScalaVersionUsesBundledBackend(): Unit =
    val file        = myFixture.configureByText("VersionIsolation.scala", "val value: String = \"text\"")
    val typeElement = PsiTreeUtil.findChildOfType(file, classOf[ScTypeElement])
    val version     = myFixture.getEditor.getDocument.getModificationStamp
    val _           = Scala3CompilerBackend
      .get(getProject)
      .publish(typeElement, CompilerBackendRole.DeclaredType, version, "Int")
    val rendered    = typeElement
      .`type`()
      .fold(failure => throw new AssertionError(failure.toString), _.canonicalText)

    assertEquals("_root_.scala.Predef.String", rendered)

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)
