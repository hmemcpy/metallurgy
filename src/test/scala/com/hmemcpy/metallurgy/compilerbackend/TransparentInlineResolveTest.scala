package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.pc.PcSessionManager
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.UIUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScReferenceExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunctionDefinition
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.jetbrains.plugins.scala.settings.ScalaProjectSettings
import org.junit.Assert.{assertSame, assertTrue}

import scala.jdk.CollectionConverters.*

/** Reproduces the cold-start cmd+click issue: a reference to a transparent inline member is unresolved until the
  * compiler-backend snapshot publishes and the daemon re-highlights.
  */
final class TransparentInlineResolveTest extends ScalaLightCodeInsightFixtureTestCase:

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.fromString("3.5.2").get

  override protected def defaultVersionOverride: Option[ScalaVersion] =
    Some(new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2"))

  override def getTestDataPath: String =
    java.nio.file.Path.of("src", "test", "testdata").toAbsolutePath.toString

  override protected def setUp(): Unit =
    super.setUp()
    MetallurgySettings(getProject).setEnabled(getModule, enabled = true)
    ScalaProjectSettings.getInstance(getProject).setCompilerHighlightingScala3(true)
    ScalaProjectSettings.getInstance(getProject).setUseCompilerTypes(true)

  override protected def tearDown(): Unit =
    try
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      ScalaProjectSettings.getInstance(getProject).setCompilerHighlightingScala3(false)
      ScalaProjectSettings.getInstance(getProject).setUseCompilerTypes(false)
    finally super.tearDown()

  def testResolveInvalidatedAfterSnapshotWithoutRehighlight(): Unit =
    val source =
      """object Config:
        |  transparent inline def port: Int = 8080
        |val transparentPort = Config.port
        |""".stripMargin
    val file   = myFixture.configureByText("TransparentInlineCache.scala", source)
    myFixture.doHighlighting()

    val portDef = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunctionDefinition])
      .asScala
      .find(_.name == "port")
      .orNull
    assertTrue("port definition not found", portDef != null)

    val portRef = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScReferenceExpression])
      .asScala
      .find(_.getText == "Config.port")
      .orNull
    assertTrue("Config.port reference (usage) not found", portRef != null)

    // First resolve: caches the result before the snapshot publishes
    val before       = portRef.bind()
    val beforeTarget = before.map(_.element).orNull
    println(s"[resolve] before snapshot: ${Option(beforeTarget).map(_.getText).getOrElse("null")}")

    // Publish snapshot (fills CompilerType slot, bumps modification count)
    val _ = PlatformTestUtil.waitForFuture(
      PcSessionManager.get(getProject).prepareCompilerBackend(file.getVirtualFile),
      60000L
    )
    NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    UIUtil.dispatchAllInvocationEvents()

    // Resolve again WITHOUT re-highlighting — ResolveCache must be invalidated by the snapshot commit
    val target = portRef.resolve()
    assertSame(
      s"Config.port should resolve to def port after snapshot publish (without re-highlight), got: ${Option(target).map(_.getText).getOrElse("null")}",
      portDef,
      target
    )

  def testTransparentInlinePortResolvesToDefinition(): Unit =
    val source =
      """object Config:
        |  transparent inline def port: Int = 8080
        |val transparentPort = Config.port
        |""".stripMargin
    val file   = myFixture.configureByText("TransparentInlineResolve.scala", source)
    myFixture.doHighlighting()
    val _      = PlatformTestUtil.waitForFuture(
      PcSessionManager.get(getProject).prepareCompilerBackend(file.getVirtualFile),
      60000L
    )
    NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    UIUtil.dispatchAllInvocationEvents()
    myFixture.doHighlighting()

    val portDef = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunctionDefinition])
      .asScala
      .find(_.name == "port")
      .orNull
    assertTrue("port definition not found", portDef != null)

    val portRef = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScReferenceExpression])
      .asScala
      .find(_.getText == "Config.port")
      .orNull
    assertTrue("Config.port reference (usage) not found", portRef != null)

    val target = portRef.resolve()
    assertSame(
      s"Config.port should resolve to def port via PsiReference.resolve(), got: ${Option(target).map(_.getText).getOrElse("null")}",
      portDef,
      target
    )
