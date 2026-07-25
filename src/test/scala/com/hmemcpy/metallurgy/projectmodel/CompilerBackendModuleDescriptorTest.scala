package com.hmemcpy.metallurgy.projectmodel

import com.hmemcpy.metallurgy.build.ScalacFlagsService
import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge
import com.hmemcpy.metallurgy.pc.PcSessionManager
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PsiTestUtil
import org.jetbrains.jps.model.java.{JavaSourceRootProperties, JavaSourceRootType}
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}

final class CompilerBackendModuleDescriptorTest extends ScalaLightCodeInsightFixtureTestCase:

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

  override protected def tearDown(): Unit =
    try
      PcSessionManager.get(getProject).discard(getModule)
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
    finally super.tearDown()

  def testDescriptorIsDeterministicAndLoaderNeutral(): Unit =
    val first  = readyDescriptor()
    val second = readyDescriptor()

    assertEquals("3.5.2", first.scalaVersion)
    assertFalse(first.sourceRoots.isEmpty)
    assertFalse(first.compileClasspath.isEmpty)
    assertEquals(first, second)
    assertTrue(CompilerBackendModuleDescriptor.diff(first, second).isEmpty)

  def testNativeSbtAndIntellijBspOwnershipProduceTheSameDescriptor(): Unit =
    val properties = ExternalSystemModulePropertyManager.getInstance(getModule)
    properties.setExternalId(new ProjectSystemId("SBT"))
    val sbt        = readyDescriptor()
    properties.setExternalId(new ProjectSystemId("BSP"))
    val bsp        = readyDescriptor()

    assertEquals(sbt, bsp)
    assertTrue(CompilerBackendModuleDescriptor.diff(sbt, bsp).isEmpty)

  def testCompilerProfileChangesAreExplicitDescriptorChanges(): Unit =
    val service  = ScalacFlagsService.get(getProject)
    val original = service.additionalOptions(getModule)
    val before   = readyDescriptor()
    try
      ScalaPluginSemanticBridge.setAdditionalCompilerOptions(
        getModule,
        original :+ "-Xplugin:/tmp/example-compiler-plugin.jar"
      )
      val after = readyDescriptor()

      assertTrue(after.compilerPlugins.contains("/tmp/example-compiler-plugin.jar"))
      assertFalse(before.fingerprint == after.fingerprint)
      assertEquals(Vector("compilerOptions", "compilerPlugins"), CompilerBackendModuleDescriptor.diff(before, after))
    finally ScalaPluginSemanticBridge.setAdditionalCompilerOptions(getModule, original)

  def testSourceSetsGeneratedRootsAndOutputsComeFromTheCommittedIntellijModel(): Unit =
    val generated = myFixture.getTempDirFixture.findOrCreateDir("generated/main")
    val tests     = myFixture.getTempDirFixture.findOrCreateDir("src/test/scala")
    val mainOut   = myFixture.getTempDirFixture.findOrCreateDir("target/classes")
    val testOut   = myFixture.getTempDirFixture.findOrCreateDir("target/test-classes")
    val _         = PsiTestUtil.addSourceRoot(
      getModule,
      generated,
      JavaSourceRootType.SOURCE,
      new JavaSourceRootProperties("", true)
    )
    val _         = PsiTestUtil.addSourceRoot(getModule, tests, true)
    PsiTestUtil.setCompilerOutputPath(getModule, mainOut.getUrl, false)
    PsiTestUtil.setCompilerOutputPath(getModule, testOut.getUrl, true)

    val descriptor = readyDescriptor()
    assertTrue(descriptor.sourceRoots.exists(root => root.url == generated.getUrl && root.generated))
    assertTrue(descriptor.sourceRoots.exists(_.url == tests.getUrl))
    assertEquals(Some(mainOut.getUrl), descriptor.productionOutput)
    assertEquals(Some(testOut.getUrl), descriptor.testOutput)

  def testInactiveModuleDoesNotExposeProjectModelOrCreateSession(): Unit =
    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)

    assertEquals(CompilerBackendModelState.Inactive, CompilerBackendModuleDescriptor.read(getProject, getModule))
    assertTrue(PcSessionManager.get(getProject).sessionFor(getModule).isEmpty)
    assertEquals(0, PcSessionManager.get(getProject).activeSessionCount)

  private def readyDescriptor(): CompilerBackendModuleDescriptor =
    CompilerBackendModuleDescriptor.read(getProject, getModule) match
      case CompilerBackendModelState.Ready(descriptor) => descriptor
      case state                                       => throw new AssertionError(s"expected ready compiler backend model, got $state")

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)
