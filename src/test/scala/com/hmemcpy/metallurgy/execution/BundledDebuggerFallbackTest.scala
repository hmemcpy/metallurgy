package com.hmemcpy.metallurgy.execution

import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge
import com.hmemcpy.metallurgy.pc.PcSessionManager
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.debugger.engine.evaluation.{CodeFragmentKind, TextWithImportsImpl}
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.{ScalaFileType, ScalaVersion}
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.debugger.evaluation.ScalaCodeFragmentFactory
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScExpression, ScReferenceExpression}
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.{assertEquals, assertSame, assertTrue}

import scala.jdk.CollectionConverters.*

final class BundledDebuggerFallbackTest extends ScalaLightCodeInsightFixtureTestCase:

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

  def testUnversionedDebuggerFragmentUsesBundledEvaluationWithoutBackendWork(): Unit =
    val file      = myFixture.configureByText(
      "DebuggerContext.scala",
      """class Service:
        |  def render(value: Int): Int = value
        |
        |object Main:
        |  val service = Service()
        |  def run(): Int = service.render(0)
        |""".stripMargin
    )
    val function  = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunction])
      .asScala
      .find(_.name == "render")
      .get
    val context   = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScExpression])
      .asScala
      .find(_.getText == "service.render(0)")
      .get
    val fragment  = ScalaCodeFragmentFactory().createPsiCodeFragment(
      TextWithImportsImpl(CodeFragmentKind.EXPRESSION, "service.render(1)", "", ScalaFileType.INSTANCE),
      context,
      getProject
    )
    val queried   = PsiTreeUtil
      .findChildrenOfType(fragment, classOf[ScExpression])
      .asScala
      .find(_.getText == "service.render(1)")
      .get
    val reference = PsiTreeUtil
      .findChildrenOfType(fragment, classOf[ScReferenceExpression])
      .asScala
      .find(_.refName == "render")
      .get

    assertEquals("Int", queried.`type`().get.canonicalText)
    assertSame(function, reference.resolve())
    assertEquals(getModule, ModuleUtilCore.findModuleForPsiElement(queried))
    assertEquals(0, PcSessionManager.get(getProject).activeSessionCount)

  def testLazyAndEagerDebuggerEvaluatorGatesRemainBundled(): Unit =
    val key      = "scala.debugger.expression.evaluator.lazy.resolve.enabled"
    val registry = Registry.get(key)
    val factory  = ScalaCodeFragmentFactory()

    registry.setValue(false, getTestRootDisposable)
    val eager       = factory.getEvaluatorBuilder.getClass.getName
    registry.setValue(true, getTestRootDisposable)
    val lazyBuilder = factory.getEvaluatorBuilder.getClass.getName

    assertTrue(eager, eager.startsWith("org.jetbrains.plugins.scala.debugger.evaluation."))
    assertTrue(lazyBuilder, lazyBuilder.startsWith("org.jetbrains.plugins.scala.debugger.evaluation."))
    assertTrue(s"the gate selected the same builder twice: $eager", eager != lazyBuilder)
    assertEquals(0, PcSessionManager.get(getProject).activeSessionCount)

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)
