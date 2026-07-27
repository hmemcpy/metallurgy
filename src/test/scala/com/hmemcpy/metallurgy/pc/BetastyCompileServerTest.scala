package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.build.ScalacFlagsService
import com.hmemcpy.metallurgy.compilerbackend.{CompilerBackendRole, Scala3CompilerBackend}
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.codeInsight.completion.{CodeCompletionHandlerBase, CompletionType}
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.ide.util.projectWizard.ModuleBuilder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.fileEditor.{FileDocumentManager, FileEditorManager, OpenFileDescriptor}
import com.intellij.openapi.module.{JavaModuleType, Module, ModuleType}
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.{VfsUtil, VirtualFile}
import com.intellij.psi.{PsiManager, PsiNamedElement}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.{PsiTestUtil, VfsTestUtil}
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.compiler.CompileServerLauncher
import org.jetbrains.plugins.scala.compiler.highlighting.ScalaCompilerHighlightingTestBase
import org.jetbrains.plugins.scala.extensions.{inReadAction, inWriteAction, invokeAndWait}
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScReferenceExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunctionDefinition
import org.jetbrains.plugins.scala.settings.ScalaProjectSettings
import org.jetbrains.plugins.scala.util.CompilerTestUtil.runWithErrorsFromCompiler
import org.junit.Assert.{assertEquals, assertFalse, assertNotNull, assertTrue}

import java.nio.file.{Files, Path}
import scala.compiletime.uninitialized
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/** Exercises best-effort TASTy through IntelliJ's ordinary build and editor pipelines with a real module dependency. */
final class BetastyCompileServerTest extends ScalaCompilerHighlightingTestBase:

  private val testScalaVersion = ScalaVersion.fromString("3.5.2").get

  private var moduleB: Module            = uninitialized
  private var moduleBSource: VirtualFile = uninitialized

  override protected def supportedIn(version: ScalaVersion): Boolean = version == testScalaVersion

  override protected def defaultVersionOverride: Option[ScalaVersion] = Some(testScalaVersion)

  override protected def reuseCompileServerProcessBetweenTests: Boolean = false

  override protected def compileServerShutdownTimeout = 10.seconds

  override def setUp(): Unit =
    super.setUp()
    val moduleBDir = VfsTestUtil.createDir(getBaseDir, "moduleB")
    moduleB = PsiTestUtil.addModule(
      getProject,
      JavaModuleType.getModuleType.asInstanceOf[ModuleType[? <: ModuleBuilder]],
      "moduleB",
      moduleBDir
    )
    moduleBSource = VfsTestUtil.createDir(moduleBDir, "src")
    PsiTestUtil.addSourceRoot(moduleB, moduleBSource, false)
    ModuleRootModificationUtil.addDependency(moduleB, getModule)
    setUpLibraries(moduleB)

    val settings = MetallurgySettings(getProject)
    val flags    = ScalacFlagsService.get(getProject)
    setCompilerBasedHighlighting(enabled = true)
    Seq(getModule, moduleB).foreach: module =>
      settings.setEnabled(module, enabled = true)
      flags.enableFor(module, Scala3PcBridgeCapabilities.bestEffort)
      assertTrue(ScalacFlagsService.BestEffortFlags.forall(flags.additionalOptions(module).contains))

  def testBrokenUpstreamModuleRemainsUsableAndRefreshesThroughIntellijEditor(): Unit =
    exerciseBrokenUpstreamModule()

  private def exerciseBrokenUpstreamModule(): Unit =
    runWithErrorsFromCompiler(getProject):
      val upstream = addFileToProjectSources("Person.scala", brokenUpstream("personName"))
      val consumer = addModuleBFile("Consumer.scala", consumerSource("personName"))
      try
        exerciseLifecycle(upstream, consumer)
      finally closeEditor(consumer)

  private def exerciseLifecycle(upstream: VirtualFile, consumer: VirtualFile): Unit =
    ensureBestEffortFlags()
    val brokenMessages = compiler.make().asScala.toSeq
    assertTrue(
      "the deliberately broken producer must report a compiler error",
      brokenMessages.exists(_.getCategory == CompilerMessageCategory.ERROR)
    )
    val artifacts      = betastyArtifacts(getBaseDir.toNioPath.resolve("out"))
    assertTrue(s"IntelliJ build emitted no .betasty artifact: $brokenMessages", artifacts.nonEmpty)
    assertTrue(artifacts.exists(_.getFileName.toString == "Person.betasty"))

    assertEditorContract(consumer, "personName")
    assertCompletion("person", expected = "personName", absent = "personLabel")

    replaceText(upstream, repairedUpstream("personName"))
    ensureBestEffortFlags()
    val repairedMessages = compiler.make().asScala.toSeq
    assertFalse(
      s"repairing module A must produce a clean build: $repairedMessages",
      repairedMessages.exists(_.getCategory == CompilerMessageCategory.ERROR)
    )
    assertEditorContract(consumer, "personName")

    replaceText(upstream, repairedUpstream("personLabel"))
    replaceText(consumer, consumerSource("personLabel"))
    ensureBestEffortFlags()
    val changedMessages = compiler.make().asScala.toSeq
    assertFalse(
      s"changing module A's public API must produce a clean build: $changedMessages",
      changedMessages.exists(_.getCategory == CompilerMessageCategory.ERROR)
    )
    assertEditorContract(consumer, "personLabel")
    assertCompletion("person", expected = "personLabel", absent = "personName")

    VfsTestUtil.deleteFile(upstream)
    val removedMessages = compiler.rebuild().asScala.toSeq
    assertTrue(
      s"removing module A's public API must invalidate module B: $removedMessages",
      removedMessages.exists(_.getCategory == CompilerMessageCategory.ERROR)
    )
    assertRemovedMemberIsHighlighted(consumer)
    assertCompletionExcludes("person", "personName", "personLabel")

    val restored          = addFileToProjectSources("Person.scala", repairedUpstream("personLabel"))
    assertNotNull("restored module A source is unavailable", restored)
    assertTrue("compile server process did not stop", CompileServerLauncher.stopServerAndWait())
    ensureBestEffortFlags()
    val restartedMessages = compiler.rebuild().asScala.toSeq
    assertFalse(
      s"a clean rebuild after compile-server restart must succeed: $restartedMessages",
      restartedMessages.exists(_.getCategory == CompilerMessageCategory.ERROR)
    )
    assertEditorContract(consumer, "personLabel")
    assertCompletion("person", expected = "personLabel", absent = "personName")

  private def assertEditorContract(file: VirtualFile, memberName: String): Unit =
    waitUntilFileIsHighlighted(file)
    val _ = com.intellij.testFramework.PlatformTestUtil.waitForFuture(
      PcSessionManager.get(getProject).prepareCompilerBackend(file),
      60000L
    )
    doAssertion(file, expectedResult())

    inReadAction:
      val psiFile   = PsiManager.getInstance(getProject).findFile(file)
      assertNotNull("module B PSI file is unavailable", psiFile)
      val reference = PsiTreeUtil
        .findChildrenOfType(psiFile, classOf[ScReferenceExpression])
        .asScala
        .find(_.refName == memberName)
        .orNull
      assertNotNull(s"module B reference $memberName is unavailable", reference)

      val bundledTarget = reference.resolve()
      assertNotNull(s"bundled PSI did not resolve $memberName", bundledTarget)
      assertEquals(memberName, bundledTarget.asInstanceOf[PsiNamedElement].getName)
      assertTrue(
        s"$memberName did not resolve to module A's source definition",
        bundledTarget.isInstanceOf[ScFunctionDefinition]
      )

      val compilerTarget = Scala3CompilerBackend
        .get(getProject)
        .symbolTargetFor(reference, moduleB, CompilerBackendRole.Reference)
        .orNull
      assertNotNull(s"compiler backend did not resolve $memberName", compilerTarget)
      assertEquals(memberName, compilerTarget.asInstanceOf[PsiNamedElement].getName)
      assertTrue(
        s"compiler backend returned an invalid target for $memberName",
        compilerTarget.isValid
      )

    val errors = fetchHighlightInfos(file).filter(_.getSeverity == HighlightSeverity.ERROR)
    assertTrue(s"valid module B code is red: ${errors.map(_.getDescription).mkString("; ")}", errors.isEmpty)

  private def assertRemovedMemberIsHighlighted(file: VirtualFile): Unit =
    waitUntilFileIsHighlighted(file)
    val errors = fetchHighlightInfos(file).filter(_.getSeverity == HighlightSeverity.ERROR)
    assertTrue("removed module A API must remain visibly unresolved in module B", errors.nonEmpty)

  private def assertCompletion(prefix: String, expected: String, absent: String): Unit =
    val names = completionNames(prefix, s"Completion$expected.scala")
    assertTrue(s"$expected was not offered: ${names.toSeq.sorted.mkString(", ")}", names.contains(expected))
    assertFalse(s"stale member $absent was offered: ${names.toSeq.sorted.mkString(", ")}", names.contains(absent))

  private def assertCompletionExcludes(prefix: String, absent: String*): Unit =
    val names   = completionNames(prefix, "CompletionAfterRemoval.scala")
    val present = absent.filter(names.contains)
    assertTrue(s"removed members were offered: ${present.mkString(", ")}", present.isEmpty)

  private def completionNames(prefix: String, fileName: String): Set[String] =
    val text = s"""object CompletionProbe:
                   |  val completionPerson = new Person("Ada")
                   |  completionPerson.$prefix""".stripMargin
    val file = addModuleBFile(fileName, text)
    try
      val offset = text.length
      val _      = com.intellij.testFramework.PlatformTestUtil.waitForFuture(
        PcSessionManager.get(getProject).prepareCompilerBackend(file),
        60000L
      )
      val editor = invokeAndWait:
        FileEditorManager.getInstance(getProject).openTextEditor(new OpenFileDescriptor(getProject, file, offset), true)
      invokeAndWait:
        new CodeCompletionHandlerBase(CompletionType.BASIC, false, false, true)
          .invokeCompletion(getProject, editor, 1)
      LookupManager.getActiveLookup(editor) match
        case lookup: LookupImpl =>
          val names = lookup.getItems.asScala.map(_.getLookupString).toSet
          invokeAndWait:
            lookup.hide()
          names
        case _                  => Set.empty
    finally
      closeEditor(file)
      VfsTestUtil.deleteFile(file)

  private def closeEditor(file: VirtualFile): Unit =
    invokeAndWait:
      FileEditorManager.getInstance(getProject).closeFile(file)

  private def addModuleBFile(name: String, text: String): VirtualFile =
    VfsTestUtil.createFile(moduleBSource, name, text)

  private def replaceText(file: VirtualFile, text: String): Unit =
    inWriteAction:
      VfsUtil.saveText(file, text)
    FileDocumentManager.getInstance().saveAllDocuments()
    file.refresh(false, false)

  private def brokenUpstream(memberName: String): String =
    s"""final class Person(val name: String):
       |  def $memberName: String = name
       |  def brokenValue: MissingType = ???
       |""".stripMargin

  private def repairedUpstream(memberName: String): String =
    s"""final class Person(val name: String):
       |  def $memberName: String = name
       |""".stripMargin

  private def consumerSource(memberName: String): String =
    s"""val recoveredName: String = new Person("Ada").$memberName
       |""".stripMargin

  private def ensureBestEffortFlags(): Unit =
    val flags = ScalacFlagsService.get(getProject)
    Seq(getModule, moduleB).foreach: module =>
      flags.enableFor(module, Scala3PcBridgeCapabilities.bestEffort)
      assertTrue(
        s"best-effort producer flags are unavailable for ${module.getName}",
        ScalacFlagsService.BestEffortFlags.forall(flags.additionalOptions(module).contains)
      )

  private def betastyArtifacts(root: Path): Seq[Path] =
    if !Files.isDirectory(root) then Seq.empty
    else
      val files = Files.walk(root)
      try files.iterator().asScala.filter(path => Files.isRegularFile(path) && path.toString.endsWith(".betasty")).toSeq
      finally files.close()

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val settings = ScalaProjectSettings.getInstance(getProject)
    settings.setCompilerHighlightingScala3(enabled)
    settings.setUseCompilerTypes(enabled)

  override protected def tearDown(): Unit =
    try
      val settings = MetallurgySettings(getProject)
      Option(moduleB).foreach: module =>
        settings.setEnabled(module, enabled = false)
        disposeLibraries(module)
      settings.setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
    finally super.tearDown()
