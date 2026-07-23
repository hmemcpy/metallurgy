package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.pc.*
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.{PsiElement, SmartPointerManager}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.FileContentUtilCore
import com.intellij.util.ui.UIUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScBindingPattern
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScValueOrVariableDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameter
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.{assertEquals, assertNotSame, assertNull, assertSame, assertTrue}

import java.nio.file.Path
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.DurationInt

final class CompilerBackendSnapshotPublisherTest extends ScalaLightCodeInsightFixtureTestCase:

  private val generation = CompilerBackendGeneration(1L, 1L, 1L)

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.fromString("3.5.2").get

  override protected def defaultVersionOverride: Option[ScalaVersion] =
    Some(new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2"))

  override def getTestDataPath: String =
    Path.of("src", "test", "testdata").toAbsolutePath.toString

  override protected def setUp(): Unit =
    super.setUp()
    MetallurgySettings(getProject).setEnabled(getModule, enabled = true)
    setCompilerBasedHighlighting(enabled = true)

  override protected def tearDown(): Unit =
    try
      Scala3CompilerBackend.get(getProject).clear()
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
    finally super.tearDown()

  def testSnapshotMapsFunctionParameterAndDeclaredType(): Unit =
    val source    = "object Main:\n  def function(parameter: Int): String = parameter.toString\n"
    val file      = myFixture.configureByText("MappedSnapshot.scala", source)
    val document  = myFixture.getEditor.getDocument
    val function  = child[ScFunction](file)
    val parameter = child[ScParameter](file)
    val paramType = parameter.typeElement.get
    val declared  = children[ScTypeElement](file).find(_.getText == "String").get
    val entries   = Seq(
      entry(function, PcTypedTreeRole.Function, "(Main.function : (parameter: Int): String)"),
      entry(function, PcTypedTreeRole.FunctionResult, "String"),
      entry(paramType, PcTypedTreeRole.Declared, "Int"),
      entry(declared, PcTypedTreeRole.Declared, "String")
    )
    val snapshot  = typedTreeSnapshot(file.getVirtualFile.getUrl, document.getModificationStamp, entries)

    publishSynchronously(snapshot)

    assertRendered(function, CompilerBackendRole.Function, "(Main.function : (parameter: Int): String)")
    assertCurrent(function, CompilerBackendRole.FunctionResult, "String")
    assertCurrent(parameter, CompilerBackendRole.Parameter, "Int")
    assertCurrent(declared, CompilerBackendRole.DeclaredType, "String")

  def testSnapshotMapsInferredDefinitionBindingAndExpression(): Unit =
    val file       = myFixture.configureByText("MappedInference.scala", "object Main:\n  val inferred = identity(42)\n")
    val document   = myFixture.getEditor.getDocument
    val definition = child[ScValueOrVariableDefinition](file)
    val call       = children[ScExpression](file).find(_.getText == "identity(42)").get
    val snapshot   = typedTreeSnapshot(
      file.getVirtualFile.getUrl,
      document.getModificationStamp,
      Seq(
        entry(definition, PcTypedTreeRole.Inferred, "Int"),
        entry(call, PcTypedTreeRole.ExpressionExact, "Int")
      )
    )

    publishSynchronously(snapshot)

    assertCurrent(definition, CompilerBackendRole.Definition, "Int")
    assertCurrent(definition.bindings.head, CompilerBackendRole.Binding, "Int")
    assertCurrent(call, CompilerBackendRole.ExpressionExact, "Int")
    assertEquals("Int", ScalaPluginSemanticBridge.getCompilerType(call))

  def testSnapshotMapsEachDestructuredPatternTypeToBindingAndPatternRoles(): Unit =
    val file     = myFixture.configureByText("MappedPatterns.scala", "object Main:\n  val (number, text) = (1, \"two\")\n")
    val document = myFixture.getEditor.getDocument
    val patterns = children[ScBindingPattern](file).filter(pattern => Set("number", "text").contains(pattern.getText))
    val entries  = patterns.map(pattern =>
      entry(pattern, PcTypedTreeRole.Pattern, if pattern.getText == "number" then "Int" else "String")
    )

    publishSynchronously(typedTreeSnapshot(file.getVirtualFile.getUrl, document.getModificationStamp, entries))

    patterns.foreach: pattern =>
      val expected = if pattern.getText == "number" then "Int" else "String"
      assertCurrent(pattern, CompilerBackendRole.Binding, expected)
      assertCurrent(pattern, CompilerBackendRole.Pattern, expected)

  def testSnapshotSymbolNavigationMapsReferenceToStableSourcePsi(): Unit =
    val source    = "object Main:\n  def answer: Int = 42\n  val result = answer\n"
    val file      = myFixture.configureByText("MappedSymbol.scala", source)
    val document  = myFixture.getEditor.getDocument
    val function  = child[ScFunction](file)
    val reference = children[org.jetbrains.plugins.scala.lang.psi.api.expr.ScReferenceExpression](file)
      .find(_.getText == "answer")
      .get
    val nameRange = function.getNameIdentifier.getTextRange
    val symbol    = PcCompilerSymbol(
      "Main.answer():Int@19:25",
      Set("Method"),
      Some("Main"),
      Some(
        PcNavigationTarget(
          file.getVirtualFile.getUrl,
          PcSourceRange(nameRange.getStartOffset, nameRange.getEndOffset)
        )
      )
    )
    val entries   = Seq(
      symbolEntry(function, PcTypedTreeRole.Function, "(Main.answer : => Int)", symbol),
      symbolEntry(reference, PcTypedTreeRole.ExpressionExact, "Int", symbol)
    )

    publishSynchronously(typedTreeSnapshot(file.getVirtualFile.getUrl, document.getModificationStamp, entries))

    val backend = Scala3CompilerBackend.get(getProject)
    val first   = backend.symbolTargetFor(reference, getModule, CompilerBackendRole.ExpressionExact).orNull
    val second  = backend.symbolTargetFor(reference, getModule, CompilerBackendRole.ExpressionExact).orNull
    assertSame(function, first)
    assertSame(first, second)

    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
    assertTrue(backend.symbolTargetFor(reference, getModule, CompilerBackendRole.ExpressionExact).isEmpty)
    MetallurgySettings(getProject).setEnabled(getModule, enabled = true)
    assertTrue(backend.symbolTargetFor(reference, getModule, CompilerBackendRole.ExpressionExact).isEmpty)

    backend.markPending(
      getModule,
      file.getVirtualFile.getUrl,
      document.getModificationStamp + 1L,
      generation.copy(session = generation.session + 1L)
    )
    assertTrue(backend.symbolTargetFor(reference, getModule, CompilerBackendRole.ExpressionExact).isEmpty)

    backend.clear(getModule)
    assertTrue(backend.symbolTargetFor(reference, getModule, CompilerBackendRole.ExpressionExact).isEmpty)

  def testRealCompilerSnapshotMapsAllSupportedPsiRoles(): Unit =
    val source =
      """object Main:
        |  def function(parameter: Int): String = parameter.toString
        |  val inferred = function(42)
        |  val pair = (1, "two")
        |  val (number, text) = pair
        |""".stripMargin
    val file   = myFixture.configureByText("RealSnapshotRoles.scala", source)
    val _      = PlatformTestUtil.waitForFuture(
      PcSessionManager.get(getProject).prepareCompilerBackend(file.getVirtualFile),
      60000L
    )
    NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    UIUtil.dispatchAllInvocationEvents()

    val function   = child[ScFunction](file)
    val parameter  = child[ScParameter](file)
    val definition = children[ScValueOrVariableDefinition](file).find(_.getText.startsWith("val inferred")).get
    val declared   = children[ScTypeElement](file).find(_.getText == "String").get
    val call       = children[ScExpression](file).find(_.getText == "function(42)").get
    val patterns   = children[ScBindingPattern](file).filter(pattern => Set("number", "text").contains(pattern.getText))

    assertRendered(function, CompilerBackendRole.Function, "(Main.function : (parameter: Int): String)")
    assertCurrent(function, CompilerBackendRole.FunctionResult, "String")
    assertCurrent(parameter, CompilerBackendRole.Parameter, "Int")
    assertCurrent(declared, CompilerBackendRole.DeclaredType, "String")
    assertCurrent(definition, CompilerBackendRole.Definition, "String")
    assertCurrent(definition.bindings.head, CompilerBackendRole.Binding, "String")
    assertCurrent(call, CompilerBackendRole.ExpressionExact, "String")
    patterns.foreach: pattern =>
      val expected = if pattern.getText == "number" then "Int" else "String"
      assertCurrent(pattern, CompilerBackendRole.Binding, expected)
      assertCurrent(pattern, CompilerBackendRole.Pattern, expected)
      assertCurrent(pattern, CompilerBackendRole.PatternExpected, expected)

  def testRealCompilerSnapshotMapsReferenceSymbolToSourcePsi(): Unit =
    val source = "object Main:\n  def answer: Int = 42\n  val result = answer\n"
    val file   = myFixture.configureByText("RealSymbolNavigation.scala", source)
    val _      = PlatformTestUtil.waitForFuture(
      PcSessionManager.get(getProject).prepareCompilerBackend(file.getVirtualFile),
      60000L
    )
    NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    UIUtil.dispatchAllInvocationEvents()

    val function  = child[ScFunction](file)
    val reference = children[org.jetbrains.plugins.scala.lang.psi.api.expr.ScReferenceExpression](file)
      .find(_.getText == "answer")
      .get
    val target    = Scala3CompilerBackend
      .get(getProject)
      .symbolTargetFor(reference, getModule, CompilerBackendRole.ExpressionExact)
      .orNull

    assertSame(function, target)

  def testRejectedGenerationCannotPublishStateOrMutateCompilerTypeSlot(): Unit =
    val file       = myFixture.configureByText("RejectedSnapshot.scala", "object Main:\n  val value = 1\n")
    val document   = myFixture.getEditor.getDocument
    val definition = child[ScValueOrVariableDefinition](file)
    val backend    = Scala3CompilerBackend.get(getProject)
    val snapshot   = typedTreeSnapshot(
      file.getVirtualFile.getUrl,
      document.getModificationStamp,
      Seq(entry(definition, PcTypedTreeRole.Inferred, "Int"))
    )
    backend.markPending(getModule, snapshot.fileUri, snapshot.documentVersion, generation)

    val publisher = new CompilerBackendSnapshotPublisher(getProject)
    val mappings  = readAction(publisher.mapCurrentFile(getModule, snapshot))
    val _         = readAction:
      backend.commitSnapshot(getModule, file, snapshot.documentVersion, generation, mappings)(
        PcSnapshotCurrency.Superseded
      )

    assertEquals(
      CompilerBackendState.Pending,
      backend.stateForActiveModule(definition, getModule, CompilerBackendRole.Definition)
    )
    assertNull(ScalaPluginSemanticBridge.getCompilerType(definition))

  def testGenerationSupersededAfterStateSwapRollsBackWithoutSlotMutation(): Unit =
    val file       = myFixture.configureByText("SupersededAfterSwap.scala", "object Main:\n  val value = List(1).head\n")
    val document   = myFixture.getEditor.getDocument
    val expression = children[ScExpression](file).find(_.getText == "List(1).head").get
    val backend    = Scala3CompilerBackend.get(getProject)
    val checks     = new AtomicInteger(0)
    backend.markPending(getModule, file.getVirtualFile.getUrl, document.getModificationStamp, generation)

    val result = readAction:
      backend.commitSnapshot(
        getModule,
        file,
        document.getModificationStamp,
        generation,
        Seq(mapping(expression, CompilerBackendRole.ExpressionExact, "String"))
      ):
        if checks.incrementAndGet() < 3 then PcSnapshotCurrency.Current else PcSnapshotCurrency.Superseded

    assertEquals(CompilerBackendCommit.Rejected, result)
    assertEquals(
      CompilerBackendState.Pending,
      backend.stateForActiveModule(expression, getModule, CompilerBackendRole.ExpressionExact)
    )
    assertNull(ScalaPluginSemanticBridge.getCompilerType(expression))

  def testSupersededAsyncPublicationCannotReachEdtCommit(): Unit =
    val file           = myFixture.configureByText("SupersededAsync.scala", "object Main:\n  val value = List(1).head\n")
    val document       = myFixture.getEditor.getDocument
    val expression     = children[ScExpression](file).find(_.getText == "List(1).head").get
    val snapshot       = typedTreeSnapshot(
      file.getVirtualFile.getUrl,
      document.getModificationStamp,
      Seq(entry(expression, PcTypedTreeRole.ExpressionExact, "Int"))
    )
    val mappingStarted = new CountDownLatch(1)
    val currency       = new AtomicReference[PcSnapshotCurrency](PcSnapshotCurrency.Current)
    val publisher      = new CompilerBackendSnapshotPublisher(
      getProject,
      () =>
        currency.set(PcSnapshotCurrency.Superseded)
        mappingStarted.countDown()
    )
    val backend        = Scala3CompilerBackend.get(getProject)
    backend.markPending(getModule, snapshot.fileUri, snapshot.documentVersion, generation)

    val _ = publisher.publish(getModule, snapshot, generation, () => currency.get())
    NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    UIUtil.dispatchAllInvocationEvents()

    assertEquals("mapping did not start", 0L, mappingStarted.getCount)
    assertEquals(
      CompilerBackendState.Pending,
      backend.stateForActiveModule(expression, getModule, CompilerBackendRole.ExpressionExact)
    )
    assertNull(ScalaPluginSemanticBridge.getCompilerType(expression))

  def testAsyncPublicationMapsOffEdtAndCommitsOnEdt(): Unit =
    val file        = myFixture.configureByText("PublishedAsync.scala", "object Main:\n  val value = List(1).head\n")
    val document    = myFixture.getEditor.getDocument
    val expression  = children[ScExpression](file).find(_.getText == "List(1).head").get
    val snapshot    = typedTreeSnapshot(
      file.getVirtualFile.getUrl,
      document.getModificationStamp,
      Seq(entry(expression, PcTypedTreeRole.ExpressionExact, "Int"))
    )
    val mapRanOnEdt = new AtomicReference[java.lang.Boolean]()
    val publisher   = new CompilerBackendSnapshotPublisher(
      getProject,
      () => mapRanOnEdt.set(java.lang.Boolean.valueOf(ApplicationManager.getApplication.isDispatchThread))
    )
    val backend     = Scala3CompilerBackend.get(getProject)
    backend.markPending(getModule, snapshot.fileUri, snapshot.documentVersion, generation)

    val _ = publisher.publish(getModule, snapshot, generation, () => PcSnapshotCurrency.Current)
    NonBlockingReadActionImpl.waitForAsyncTaskCompletion()
    UIUtil.dispatchAllInvocationEvents()

    assertEquals(java.lang.Boolean.FALSE, mapRanOnEdt.get())
    assertCurrent(expression, CompilerBackendRole.ExpressionExact, "Int")
    assertEquals("Int", ScalaPluginSemanticBridge.getCompilerType(expression))

  def testCommitReacquiresCurrentPsiAfterReparse(): Unit =
    val originalFile       = myFixture.configureByText("ReparsedSnapshot.scala", "object Main:\n  val value = 1\n")
    val document           = myFixture.getEditor.getDocument
    val originalDefinition = child[ScValueOrVariableDefinition](originalFile)
    val snapshot           = typedTreeSnapshot(
      originalFile.getVirtualFile.getUrl,
      document.getModificationStamp,
      Seq(entry(originalDefinition, PcTypedTreeRole.Inferred, "Int"))
    )
    val publisher          = new CompilerBackendSnapshotPublisher(getProject)
    val mappings           = readAction(publisher.mapCurrentFile(getModule, snapshot))
    val backend            = Scala3CompilerBackend.get(getProject)
    backend.markPending(getModule, snapshot.fileUri, snapshot.documentVersion, generation)

    FileContentUtilCore.reparseFiles(originalFile.getVirtualFile)
    val currentFile       = com.intellij.psi.PsiManager.getInstance(getProject).findFile(originalFile.getVirtualFile)
    val currentDefinition = child[ScValueOrVariableDefinition](currentFile)
    assertNotSame(originalDefinition, currentDefinition)

    val result = readAction:
      backend.commitSnapshot(getModule, currentFile, snapshot.documentVersion, generation, mappings)(
        PcSnapshotCurrency.Current
      )

    assertEquals(CompilerBackendCommit.Committed(2), result)
    assertCurrent(currentDefinition, CompilerBackendRole.Definition, "Int")
    assertCurrent(currentDefinition.bindings.head, CompilerBackendRole.Binding, "Int")

  def testIdenticalSnapshotRepublishDoesNotInvalidateAgain(): Unit =
    val file       = myFixture.configureByText("IdenticalSnapshot.scala", "object Main:\n  val value = 1\n")
    val document   = myFixture.getEditor.getDocument
    val definition = child[ScValueOrVariableDefinition](file)
    val range      = definition.getTextRange
    val mapping    = CompilerBackendMapping(
      SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(definition),
      PcSourceRange(range.getStartOffset, range.getEndOffset),
      CompilerBackendRole.Definition,
      "Int",
      None
    )
    val backend    = Scala3CompilerBackend.get(getProject)
    backend.markPending(getModule, file.getVirtualFile.getUrl, document.getModificationStamp, generation)
    val before     = ScalaPluginSemanticBridge.scalaPsiModificationCount

    val first       = readAction:
      backend.commitSnapshot(getModule, file, document.getModificationStamp, generation, Seq(mapping))(
        PcSnapshotCurrency.Current
      )
    val afterFirst  = ScalaPluginSemanticBridge.scalaPsiModificationCount
    backend.markPending(getModule, file.getVirtualFile.getUrl, document.getModificationStamp, generation)
    val second      = readAction:
      backend.commitSnapshot(getModule, file, document.getModificationStamp, generation, Seq(mapping))(
        PcSnapshotCurrency.Current
      )
    val afterSecond = ScalaPluginSemanticBridge.scalaPsiModificationCount

    assertTrue(first.toString, first == CompilerBackendCommit.Committed(1))
    assertEquals(CompilerBackendCommit.Committed(0), second)
    assertEquals(before + 1L, afterFirst)
    assertEquals(afterFirst, afterSecond)

  def testCopiedCompilerTypeWithoutCurrentSideTableEntryIsCleared(): Unit =
    val file       = myFixture.configureByText("CopiedSlot.scala", "object Main:\n  val value = 1\n")
    val definition = child[ScValueOrVariableDefinition](file)
    val backend    = Scala3CompilerBackend.get(getProject)
    ScalaPluginSemanticBridge.setCompilerType(definition, "String")

    assertTrue(backend.validatedCompilerType(definition, getModule, CompilerBackendRole.Definition).isEmpty)
    assertNull(ScalaPluginSemanticBridge.getCompilerType(definition))

  def testFileStateReportsPendingSynchronously(): Unit =
    val (definition, backend, fileUri, version) = definitionStateFixture("PendingState.scala")

    backend.markPending(getModule, fileUri, version, generation)

    assertEquals(
      CompilerBackendState.Pending,
      backend.stateForActiveModule(definition, getModule, CompilerBackendRole.Definition)
    )

  def testFileStateReportsFailedSynchronously(): Unit =
    val (definition, backend, fileUri, version) = definitionStateFixture("FailedState.scala")

    backend.markPending(getModule, fileUri, version, generation)
    backend.markFailed(getModule, fileUri, version, generation)

    assertEquals(
      CompilerBackendState.Failed,
      backend.stateForActiveModule(definition, getModule, CompilerBackendRole.Definition)
    )

  def testSameVersionFailureRetiresCommittedCompilerTypeSlot(): Unit =
    val file       = myFixture.configureByText("FailedCommittedState.scala", "object Main:\n  val value = List(1).head\n")
    val document   = myFixture.getEditor.getDocument
    val expression = children[ScExpression](file).find(_.getText == "List(1).head").get
    val backend    = Scala3CompilerBackend.get(getProject)
    val fileUri    = file.getVirtualFile.getUrl
    val version    = document.getModificationStamp
    backend.markPending(getModule, fileUri, version, generation)
    val _          = readAction:
      backend.commitSnapshot(
        getModule,
        file,
        version,
        generation,
        Seq(mapping(expression, CompilerBackendRole.ExpressionExact, "Int"))
      )(PcSnapshotCurrency.Current)
    assertEquals("Int", ScalaPluginSemanticBridge.getCompilerType(expression))

    backend.markFailed(getModule, fileUri, version, generation)

    assertNull(ScalaPluginSemanticBridge.getCompilerType(expression))
    assertEquals(
      CompilerBackendState.Failed,
      backend.stateForActiveModule(expression, getModule, CompilerBackendRole.ExpressionExact)
    )

  def testFileStateReportsUnavailableSynchronously(): Unit =
    val (definition, backend, fileUri, version) = definitionStateFixture("UnavailableState.scala")

    backend.markPending(getModule, fileUri, version, generation)
    backend.markUnavailable(getModule, fileUri, version, generation)

    assertEquals(
      CompilerBackendState.Unavailable,
      backend.stateForActiveModule(definition, getModule, CompilerBackendRole.Definition)
    )

  def testSnapshotSupersededAtFinalGuardCannotMutateStateOrSlots(): Unit =
    val file       = myFixture.configureByText("FinalGuard.scala", "object Main:\n  val value = List(1).head\n")
    val document   = myFixture.getEditor.getDocument
    val expression = children[ScExpression](file).find(_.getText == "List(1).head").get
    val backend    = Scala3CompilerBackend.get(getProject)
    val version    = document.getModificationStamp
    backend.markPending(getModule, file.getVirtualFile.getUrl, version, generation)
    var checks     = 0

    val result = readAction:
      backend.commitSnapshot(
        getModule,
        file,
        version,
        generation,
        Seq(mapping(expression, CompilerBackendRole.ExpressionExact, "Int"))
      ):
        checks += 1
        if checks == 1 then PcSnapshotCurrency.Current else PcSnapshotCurrency.Superseded

    assertEquals(CompilerBackendCommit.Rejected, result)
    assertEquals(
      CompilerBackendState.Pending,
      backend.stateForActiveModule(expression, getModule, CompilerBackendRole.ExpressionExact)
    )
    assertNull(ScalaPluginSemanticBridge.getCompilerType(expression))

  def testDocumentChangeRejectsPreviouslyMappedSnapshot(): Unit =
    val file           = myFixture.configureByText("ChangedDocument.scala", "object Main:\n  val value = 1\n")
    val document       = myFixture.getEditor.getDocument
    val definition     = child[ScValueOrVariableDefinition](file)
    val version        = document.getModificationStamp
    val backendMapping = mapping(definition, CompilerBackendRole.Definition, "Int")
    val backend        = Scala3CompilerBackend.get(getProject)
    backend.markPending(getModule, file.getVirtualFile.getUrl, version, generation)
    myFixture.`type`(" ")

    val result = readAction:
      backend.commitSnapshot(getModule, file, version, generation, Seq(backendMapping))(PcSnapshotCurrency.Current)

    assertEquals(CompilerBackendCommit.Rejected, result)
    assertNull(ScalaPluginSemanticBridge.getCompilerType(definition))

  def testGenerationMismatchRejectsMappedSnapshot(): Unit =
    val file        = myFixture.configureByText("ChangedGeneration.scala", "object Main:\n  val value = 1\n")
    val document    = myFixture.getEditor.getDocument
    val definition  = child[ScValueOrVariableDefinition](file)
    val backend     = Scala3CompilerBackend.get(getProject)
    val mappedEntry = mapping(definition, CompilerBackendRole.Definition, "Int")
    backend.markPending(getModule, file.getVirtualFile.getUrl, document.getModificationStamp, generation)

    val mismatches = Seq(
      generation.copy(session = 2L),
      generation.copy(classpath = 2L),
      generation.copy(compilerOptions = 2L)
    )

    mismatches.foreach: mismatch =>
      assertEquals(
        CompilerBackendCommit.Rejected,
        readAction:
          backend.commitSnapshot(getModule, file, document.getModificationStamp, mismatch, Seq(mappedEntry))(
            PcSnapshotCurrency.Current
          )
      )

  def testRemovedCompilerTypeSlotIsClearedAndInvalidated(): Unit =
    val file           = myFixture.configureByText("RemovedSlot.scala", "object Main:\n  val value = List(1).head\n")
    val document       = myFixture.getEditor.getDocument
    val expression     = children[ScExpression](file).find(_.getText == "List(1).head").get
    val backend        = Scala3CompilerBackend.get(getProject)
    val backendMapping = mapping(expression, CompilerBackendRole.ExpressionExact, "Int")
    backend.markPending(getModule, file.getVirtualFile.getUrl, document.getModificationStamp, generation)
    val _              = readAction:
      backend.commitSnapshot(getModule, file, document.getModificationStamp, generation, Seq(backendMapping))(
        PcSnapshotCurrency.Current
      )
    assertEquals("Int", ScalaPluginSemanticBridge.getCompilerType(expression))

    val removed = readAction:
      backend.commitSnapshot(getModule, file, document.getModificationStamp, generation, Seq.empty)(
        PcSnapshotCurrency.Current
      )

    assertEquals(CompilerBackendCommit.Committed(1), removed)
    assertNull(ScalaPluginSemanticBridge.getCompilerType(expression))

  def testCurrentExpressionOverridesConflictingBundledInference(): Unit =
    val file       = myFixture.configureByText("ExpressionOverride.scala", "object Main:\n  val value = List(1).head\n")
    val document   = myFixture.getEditor.getDocument
    val expression = children[ScExpression](file).find(_.getText == "List(1).head").get
    val backend    = Scala3CompilerBackend.get(getProject)
    assertEquals("Int", expression.getTypeWithoutImplicits().fold(_.toString, _.canonicalText))
    backend.markPending(getModule, file.getVirtualFile.getUrl, document.getModificationStamp, generation)

    val result = readAction:
      backend.commitSnapshot(
        getModule,
        file,
        document.getModificationStamp,
        generation,
        Seq(mapping(expression, CompilerBackendRole.ExpressionExact, "String"))
      )(PcSnapshotCurrency.Current)

    assertEquals(CompilerBackendCommit.Committed(1), result)
    assertEquals(
      "_root_.scala.Predef.String",
      expression.getTypeWithoutImplicits().fold(_.toString, _.canonicalText)
    )

  def testExpressionFallsBackImmediatelyAfterDocumentEdit(): Unit =
    val file       = myFixture.configureByText("EditedExpression.scala", "object Main:\n  val value = List(1).head\n")
    val document   = myFixture.getEditor.getDocument
    val expression = children[ScExpression](file).find(_.getText == "List(1).head").get
    val backend    = Scala3CompilerBackend.get(getProject)
    backend.markPending(getModule, file.getVirtualFile.getUrl, document.getModificationStamp, generation)
    val _          = readAction:
      backend.commitSnapshot(
        getModule,
        file,
        document.getModificationStamp,
        generation,
        Seq(mapping(expression, CompilerBackendRole.ExpressionExact, "String"))
      )(PcSnapshotCurrency.Current)
    assertEquals(
      "_root_.scala.Predef.String",
      expression.getTypeWithoutImplicits().fold(_.toString, _.canonicalText)
    )

    myFixture.getEditor.getCaretModel.moveToOffset(file.getTextLength)
    myFixture.`type`(" ")
    backend.markPending(getModule, file.getVirtualFile.getUrl, document.getModificationStamp, generation)
    val currentExpression = children[ScExpression](myFixture.getFile).find(_.getText == "List(1).head").get

    assertEquals("Int", currentExpression.getTypeWithoutImplicits().fold(_.toString, _.canonicalText))

  def testUnparsableExactTypeDoesNotReachCompilerTypeSlot(): Unit =
    val file       = myFixture.configureByText("UnparsableSlot.scala", "object Main:\n  val value = List(1).head\n")
    val document   = myFixture.getEditor.getDocument
    val expression = children[ScExpression](file).find(_.getText == "List(1).head").get

    publishSynchronously(
      typedTreeSnapshot(
        file.getVirtualFile.getUrl,
        document.getModificationStamp,
        Seq(entry(expression, PcTypedTreeRole.ExpressionExact, "("))
      )
    )

    assertEquals(
      CompilerBackendState.Unavailable,
      Scala3CompilerBackend
        .get(getProject)
        .stateForActiveModule(expression, getModule, CompilerBackendRole.ExpressionExact)
    )
    assertNull(ScalaPluginSemanticBridge.getCompilerType(expression))

  def testDeferredRetirementClearsSlotsNotOwnedByReplacementState(): Unit =
    val file       = myFixture.configureByText("ReplacementState.scala", "object Main:\n  val value = List(1).head\n")
    val document   = myFixture.getEditor.getDocument
    val definition = child[ScValueOrVariableDefinition](file)
    val expression = children[ScExpression](file).find(_.getText == "List(1).head").get
    val backend    = Scala3CompilerBackend.get(getProject)
    val fileUri    = file.getVirtualFile.getUrl
    val version    = document.getModificationStamp
    backend.markPending(getModule, fileUri, version, generation)
    val _          = readAction:
      backend.commitSnapshot(
        getModule,
        file,
        version,
        generation,
        Seq(mapping(expression, CompilerBackendRole.ExpressionExact, "Int"))
      )(PcSnapshotCurrency.Current)
    assertEquals("Int", ScalaPluginSemanticBridge.getCompilerType(expression))

    val clear: Runnable = () => backend.clear(getModule)
    ApplicationManager.getApplication.executeOnPooledThread(clear).get(10, TimeUnit.SECONDS)
    val replacement     = generation.copy(session = 2L)
    backend.markPending(getModule, fileUri, version, replacement)
    val _               = readAction:
      backend.commitSnapshot(
        getModule,
        file,
        version,
        replacement,
        Seq(mapping(definition, CompilerBackendRole.Definition, "Int"))
      )(PcSnapshotCurrency.Current)
    UIUtil.dispatchAllInvocationEvents()

    assertNull(ScalaPluginSemanticBridge.getCompilerType(expression))
    assertCurrent(definition, CompilerBackendRole.Definition, "Int")

  def testCompilerWrapperOverlapPublishesTheFirstRankedExactType(): Unit =
    val source      =
      """object Main:
        |  def identity[A](value: A): A = value
        |  val result = identity[Int](42)
        |""".stripMargin
    val file        = myFixture.configureByText("RankedExactType.scala", source)
    val document    = myFixture.getEditor.getDocument
    val expression  = children[ScExpression](file).find(_.getText == "identity[Int](42)").get
    val range       = expression.getTextRange
    val sourceRange = PcSourceRange(range.getStartOffset, range.getEndOffset)
    val entries     = Seq(
      PcTypedTreeEntry(sourceRange, PcTypedTreeRole.ExpressionExact, "String", None),
      PcTypedTreeEntry(sourceRange, PcTypedTreeRole.ExpressionExact, "Int", None)
    )

    publishSynchronously(typedTreeSnapshot(file.getVirtualFile.getUrl, document.getModificationStamp, entries))

    assertEquals("String", ScalaPluginSemanticBridge.getCompilerType(expression))
    assertCurrent(expression, CompilerBackendRole.ExpressionExact, "String")

  def testDisablingModuleRetiresOnlyItsCompilerBackendState(): Unit =
    val file           = myFixture.configureByText("RetiredModule.scala", "object Main:\n  val value = List(1).head\n")
    val document       = myFixture.getEditor.getDocument
    val expression     = children[ScExpression](file).find(_.getText == "List(1).head").get
    val backend        = Scala3CompilerBackend.get(getProject)
    val backendMapping = mapping(expression, CompilerBackendRole.ExpressionExact, "Int")
    backend.markPending(getModule, file.getVirtualFile.getUrl, document.getModificationStamp, generation)
    val _              = readAction:
      backend.commitSnapshot(getModule, file, document.getModificationStamp, generation, Seq(backendMapping))(
        PcSnapshotCurrency.Current
      )

    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)

    assertNull(ScalaPluginSemanticBridge.getCompilerType(expression))
    assertEquals(
      CompilerBackendState.Unavailable,
      backend.stateForActiveModule(expression, getModule, CompilerBackendRole.ExpressionExact)
    )

  private def publishSynchronously(snapshot: PcTypedTreeSnapshot): Unit =
    val publisher = new CompilerBackendSnapshotPublisher(getProject)
    val backend   = Scala3CompilerBackend.get(getProject)
    backend.markPending(getModule, snapshot.fileUri, snapshot.documentVersion, generation)
    val mappings  = readAction(publisher.mapCurrentFile(getModule, snapshot))
    val file      = myFixture.getFile
    val _         = readAction:
      backend.commitSnapshot(getModule, file, snapshot.documentVersion, generation, mappings)(
        PcSnapshotCurrency.Current
      )

  private def assertCurrent(element: PsiElement, role: CompilerBackendRole, renderedType: String): Unit =
    Scala3CompilerBackend.get(getProject).stateForActiveModule(element, getModule, role) match
      case CompilerBackendState.Current(actual, _) => assertEquals(renderedType, actual)
      case state                                   => throw new AssertionError(s"expected Current for ${element.getText} / $role, got $state")

  private def assertRendered(element: PsiElement, role: CompilerBackendRole, renderedType: String): Unit =
    Scala3CompilerBackend.get(getProject).stateForActiveModule(element, getModule, role) match
      case CompilerBackendState.Rendered(actual) => assertEquals(renderedType, actual)
      case state                                 => throw new AssertionError(s"expected Rendered for ${element.getText} / $role, got $state")

  private def definitionStateFixture(
      fileName: String
  ): (ScValueOrVariableDefinition, Scala3CompilerBackend, String, Long) =
    val file       = myFixture.configureByText(fileName, "object Main:\n  val value = 1\n")
    val definition = child[ScValueOrVariableDefinition](file)
    (
      definition,
      Scala3CompilerBackend.get(getProject),
      file.getVirtualFile.getUrl,
      myFixture.getEditor.getDocument.getModificationStamp
    )

  private def mapping(
      element: PsiElement,
      role: CompilerBackendRole,
      renderedType: String
  ): CompilerBackendMapping =
    val range = element.getTextRange
    CompilerBackendMapping(
      SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(element),
      PcSourceRange(range.getStartOffset, range.getEndOffset),
      role,
      renderedType,
      None
    )

  private def entry(element: PsiElement, role: PcTypedTreeRole, renderedType: String): PcTypedTreeEntry =
    val range = element.getTextRange
    PcTypedTreeEntry(PcSourceRange(range.getStartOffset, range.getEndOffset), role, renderedType, None)

  private def symbolEntry(
      element: PsiElement,
      role: PcTypedTreeRole,
      renderedType: String,
      symbol: PcCompilerSymbol
  ): PcTypedTreeEntry =
    val range = element.getTextRange
    PcTypedTreeEntry(PcSourceRange(range.getStartOffset, range.getEndOffset), role, renderedType, Some(symbol))

  private def typedTreeSnapshot(
      fileUri: String,
      documentVersion: Long,
      entries: Seq[PcTypedTreeEntry]
  ): PcTypedTreeSnapshot =
    PcTypedTreeSnapshot(
      fileUri,
      documentVersion,
      entries.toVector,
      PcTypedTreeMetrics(0.nanos, 0.nanos, 0.nanos, 0, 0, entries.size, entries.size, 0, 0, entries.size)
    )

  private def child[A <: PsiElement](file: PsiElement)(using tag: reflect.ClassTag[A]): A =
    children[A](file).head

  private def children[A <: PsiElement](file: PsiElement)(using tag: reflect.ClassTag[A]): Seq[A] =
    PsiTreeUtil.findChildrenOfType(file, tag.runtimeClass.asInstanceOf[Class[A]]).toArray(new Array[A](0)).toSeq

  private def readAction[A](body: => A): A =
    val computation: Computable[A] = () => body
    ApplicationManager.getApplication.runReadAction(computation)

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)
