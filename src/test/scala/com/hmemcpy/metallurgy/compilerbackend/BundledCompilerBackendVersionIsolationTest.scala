package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.hmemcpy.metallurgy.pc.PcSessionManager
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.lang.psi.uast.converter.Scala2UastConverter.*
import org.jetbrains.plugins.scala.util.runners.{
  MultipleScalaVersionsJUnit4Runner,
  RunWithScalaVersions,
  TestScalaVersion
}
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import org.junit.runner.RunWith
import org.jetbrains.uast.UExpression

import scala.jdk.CollectionConverters.*

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
  def testBackendSelectionFollowsScalaMajorVersion(): Unit =
    val file        = myFixture.configureByText("VersionIsolation.scala", "val value: String = \"text\"")
    val typeElement = PsiTreeUtil.findChildOfType(file, classOf[ScTypeElement])
    val version     = myFixture.getEditor.getDocument.getModificationStamp
    val _           = Scala3CompilerBackend
      .get(getProject)
      .publish(typeElement, CompilerBackendRole.DeclaredType, version, "Int")
    val rendered    = typeElement
      .`type`()
      .fold(failure => throw new AssertionError(failure.toString), _.canonicalText)

    val expected =
      if ScalaPluginSemanticBridge.getScalaVersion(getModule).startsWith("3.") then "Int"
      else "_root_.scala.Predef.String"
    assertEquals(expected, rendered)

  @Test
  def testUastConversionPreservesScala2AndUsesTheBackendOnlyForScala3(): Unit =
    val file       = myFixture.configureByText("VersionUastIsolation.scala", "val value = List(1).head")
    val expression = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScExpression])
      .asScala
      .find(_.getText == "List(1).head")
      .get
    val version    = myFixture.getEditor.getDocument.getModificationStamp
    val result     = Scala3CompilerBackend
      .get(getProject)
      .publish(expression, CompilerBackendRole.ExpressionExact, version, "String")
    val uType      = expression.convertWithParentTo[UExpression]().get.getExpressionType.getCanonicalText
    val isScala3   = ScalaPluginSemanticBridge.getScalaVersion(getModule).startsWith("3.")

    assertEquals(
      if isScala3 then CompilerBackendPublication.Published else CompilerBackendPublication.IgnoredInactive,
      result
    )
    assertEquals(if isScala3 then "java.lang.String" else "int", uType)
    assertEquals(0, PcSessionManager.get(getProject).activeSessionCount)

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)
