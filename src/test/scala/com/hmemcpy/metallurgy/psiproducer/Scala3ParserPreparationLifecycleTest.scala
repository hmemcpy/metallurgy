package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.editor.{Document, RangeMarker}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.lang.LanguageUtil
import com.intellij.psi.LanguageSubstitutors
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.FileContentUtilCore
import org.junit.Assert.{assertEquals, assertFalse, assertSame, assertTrue}

import java.util.concurrent.CompletableFuture
import java.util.concurrent.{Executor, RejectedExecutionException}
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*

final class Scala3ParserPreparationLifecycleTest extends BasePlatformTestCase:

  def testSeveralFilesActivateThroughOnePlatformBatchWithoutChangingStableIdentity(): Unit =
    val first       = myFixture.addFileToProject("src/First.scala", "class First\n").getVirtualFile
    val second      = myFixture.addFileToProject("src/Second.scala", "class Second\n").getVirtualFile
    val document    = FileDocumentManager.getInstance.getDocument(first)
    val rangeMarker = document.createRangeMarker(0, "class First".length)
    val preparer    = new DeferredPreparer
    val activation  = new PlatformRecordingActivation(getProject)
    val catalogs    = new RecordingCatalogPreparer(NativeScala3PsiCatalogPreparer)
    val lifecycle   = lifecycleFor(preparer, Vector(first, second), activation, catalogs)

    try
      val epoch = lifecycle.prepare(getModule)
      assertEquals(ParserPreparationState.Preparing(epoch), lifecycle.stateFor(getModule))

      val bridge = new TestParserBridge
      preparer.complete(0, bridge)
      await(lifecycle)(_.isInstanceOf[ParserPreparationState.Ready])

      assertEquals(ParserPreparationState.Ready(epoch), lifecycle.stateFor(getModule))
      assertEquals(1, activation.batchCount)
      assertEquals(Vector(first, second), activation.batches.head)
      assertSame(first, activation.batches.head.head)
      assertTrue(first.isValid)
      assertSame(document, FileDocumentManager.getInstance.getDocument(first))
      assertRange(rangeMarker, document, "class First")
      assertFalse(bridge.closed)
      assertEquals(1, catalogs.invocationCount)
      assertFalse(catalogs.ranOnDispatchThread)
      val prepared = lifecycle.parserFor(getModule).get
      assertSame(bridge, prepared.bridge)
      assertTrue(
        prepared.catalog.productions
          .find(_.id == "integer-literal-number")
          .exists(_.targetRequirement == TargetRequirement.Native)
      )
    finally lifecycle.dispose()

  def testNativePsiCapabilityFailureKeepsTheModuleUnavailable(): Unit =
    val file       = myFixture.addFileToProject("src/Unavailable.scala", "class Unavailable\n").getVirtualFile
    val preparer   = new DeferredPreparer
    val activation = new ControlledActivation
    val lifecycle  = lifecycleFor(preparer, Vector(file), activation, _ => Left("native PSI unavailable"))

    try
      val epoch  = lifecycle.prepare(getModule)
      val bridge = new TestParserBridge
      preparer.complete(0, bridge)
      await(lifecycle)(_.isInstanceOf[ParserPreparationState.Unavailable])

      assertEquals(
        ParserPreparationState.Unavailable(epoch, "native PSI unavailable"),
        lifecycle.stateFor(getModule)
      )
      assertTrue(bridge.closed)
      assertTrue(lifecycle.parserFor(getModule).isEmpty)
      assertEquals(0, activation.batchCount)
    finally lifecycle.dispose()

  def testNativePsiCapabilityExceptionClosesTheBridgeAndPublishesFailure(): Unit =
    val file       = myFixture.addFileToProject("src/Failed.scala", "class Failed\n").getVirtualFile
    val preparer   = new DeferredPreparer
    val activation = new ControlledActivation
    val lifecycle  = lifecycleFor(
      preparer,
      Vector(file),
      activation,
      _ => throw new IllegalStateException("native PSI probe crashed")
    )

    try
      val epoch  = lifecycle.prepare(getModule)
      val bridge = new TestParserBridge
      preparer.complete(0, bridge)
      await(lifecycle)(_.isInstanceOf[ParserPreparationState.Unavailable])

      assertEquals(
        ParserPreparationState.Unavailable(epoch, "native PSI probe crashed"),
        lifecycle.stateFor(getModule)
      )
      assertTrue(bridge.closed)
      assertEquals(0, activation.batchCount)
    finally lifecycle.dispose()

  def testStaleCompletionCannotActivateNewerEpoch(): Unit =
    val file       = myFixture.addFileToProject("src/Current.scala", "class Current\n").getVirtualFile
    val preparer   = new DeferredPreparer
    val activation = new ControlledActivation
    val catalogs   = new RecordingCatalogPreparer(_ => Right(Scala3PsiProductionCatalog.Reviewed))
    val lifecycle  = lifecycleFor(preparer, Vector(file), activation, catalogs)

    try
      val staleEpoch   = lifecycle.prepare(getModule)
      val currentEpoch = lifecycle.prepare(getModule)
      val staleBridge  = new TestParserBridge
      preparer.complete(0, staleBridge)
      awaitCondition("stale bridge closure", staleBridge.closed)

      assertTrue(staleBridge.closed)
      assertEquals(ParserPreparationState.Preparing(currentEpoch), lifecycle.stateFor(getModule))
      assertEquals(0, activation.batchCount)
      assertEquals(0, catalogs.invocationCount)

      val currentBridge = new TestParserBridge
      preparer.complete(1, currentBridge)
      await(lifecycle)(_.isInstanceOf[ParserPreparationState.Activating])
      awaitCondition("activation batch", activation.batchCount == 1)
      assertEquals(ParserPreparationState.Activating(currentEpoch), lifecycle.stateFor(getModule))
      assertEquals(1, activation.batchCount)
      assertEquals(1, catalogs.invocationCount)
      assertSame(currentBridge, lifecycle.parserFor(getModule).get.bridge)
      assertSame(Scala3DotcLanguage.INSTANCE, lifecycle.languageFor(getModule))

      activation.runNext()
      assertEquals(ParserPreparationState.Ready(currentEpoch), lifecycle.stateFor(getModule))
      assertFalse(currentBridge.closed)
      assertTrue(staleEpoch != currentEpoch)
    finally lifecycle.dispose()

  def testSynchronousPreparationFailurePublishesUnavailable(): Unit =
    val failure    = new IllegalStateException("preparation crashed")
    val activation = new ControlledActivation
    val lifecycle  = lifecycleFor(_ => throw failure, Vector.empty, activation)

    try
      val epoch = lifecycle.prepare(getModule)

      assertEquals(ParserPreparationState.Unavailable(epoch, failure.getMessage), lifecycle.stateFor(getModule))
      assertEquals(0, activation.batchCount)
    finally lifecycle.dispose()

  def testExecutorSubmissionFailurePublishesUnavailableAndClosesBridge(): Unit =
    val preparer   = new DeferredPreparer
    val activation = new ControlledActivation
    val executor   = new Executor:
      override def execute(command: Runnable): Unit = throw new RejectedExecutionException("executor unavailable")
    val lifecycle  = lifecycleFor(preparer, Vector.empty, activation, executor = executor)

    try
      val epoch  = lifecycle.prepare(getModule)
      val bridge = new TestParserBridge
      preparer.complete(0, bridge)

      assertEquals(
        ParserPreparationState.Unavailable(epoch, "executor unavailable"),
        lifecycle.stateFor(getModule)
      )
      assertTrue(bridge.closed)
      assertEquals(0, activation.batchCount)
    finally lifecycle.dispose()

  def testActivationFailureRetiresThePublishedParser(): Unit =
    val preparer   = new DeferredPreparer
    val activation = new ControlledActivation
    val lifecycle  = lifecycleFor(preparer, Vector.empty, activation)

    try
      val epoch  = lifecycle.prepare(getModule)
      val bridge = new TestParserBridge
      preparer.complete(0, bridge)
      await(lifecycle)(_.isInstanceOf[ParserPreparationState.Activating])
      awaitCondition("activation batch", activation.batchCount == 1)

      activation.failNext(new IllegalStateException("reparse failed"))

      assertEquals(ParserPreparationState.Unavailable(epoch, "reparse failed"), lifecycle.stateFor(getModule))
      assertTrue(bridge.closed)
      assertTrue(lifecycle.parserFor(getModule).isEmpty)
    finally lifecycle.dispose()

  def testControlFlowCancellationRetiresPreparationWithoutPublishingFailure(): Unit =
    val preparer   = new DeferredPreparer
    val activation = new ControlledActivation
    val executor   = new RecordingExecutor
    val lifecycle  = lifecycleFor(
      preparer,
      Vector.empty,
      activation,
      _ => throw new TestControlFlowException,
      executor
    )

    try
      val _        = lifecycle.prepare(getModule)
      val bridge   = new TestParserBridge
      preparer.complete(0, bridge)
      val rethrown =
        try
          executor.runNext()
          false
        catch case _: TestControlFlowException => true

      assertTrue(rethrown)
      assertEquals(ParserPreparationState.Inactive, lifecycle.stateFor(getModule))
      assertEquals(1, bridge.closeCount)
      assertTrue(lifecycle.parserFor(getModule).isEmpty)

      val retry = lifecycle.prepare(getModule)
      assertEquals(ParserPreparationState.Preparing(retry), lifecycle.stateFor(getModule))
      assertEquals(2, preparer.requestCount)
    finally lifecycle.dispose()

  def testExceptionalControlFlowCompletionRetiresPreparationAndRethrows(): Unit =
    val preparer  = new DeferredPreparer
    val executor  = new RecordingExecutor
    val lifecycle = lifecycleFor(preparer, Vector.empty, new ControlledActivation, executor = executor)

    try
      val _        = lifecycle.prepare(getModule)
      preparer.fail(0, new TestControlFlowException)
      val rethrown =
        try
          executor.runNext()
          false
        catch case _: TestControlFlowException => true

      assertTrue(rethrown)
      assertEquals(ParserPreparationState.Inactive, lifecycle.stateFor(getModule))
      val retry = lifecycle.prepare(getModule)
      assertEquals(ParserPreparationState.Preparing(retry), lifecycle.stateFor(getModule))
    finally lifecycle.dispose()

  def testSynchronousActivatingQueueCancellationClosesBridgeOnce(): Unit =
    val preparer   = new DeferredPreparer
    val activation = new ControlledActivation
    val executor   = new RecordingExecutor
    val lifecycle  = lifecycleFor(preparer, Vector.empty, activation, executor = executor)

    try
      val _        = lifecycle.prepare(getModule)
      val bridge   = new TestParserBridge
      activation.failQueueWith(new TestControlFlowException)
      preparer.complete(0, bridge)
      val rethrown =
        try
          executor.runNext()
          false
        catch case _: TestControlFlowException => true

      assertTrue(rethrown)
      assertEquals(ParserPreparationState.Inactive, lifecycle.stateFor(getModule))
      assertEquals(1, bridge.closeCount)
    finally lifecycle.dispose()

  def testReadyReplacementQueueFailureClosesRetiredBridgeAndPublishesUnavailable(): Unit =
    val preparer   = new DeferredPreparer
    val activation = new ControlledActivation
    val lifecycle  = lifecycleFor(preparer, Vector.empty, activation)

    try
      val _      = lifecycle.prepare(getModule)
      val bridge = new TestParserBridge
      preparer.complete(0, bridge)
      await(lifecycle)(_.isInstanceOf[ParserPreparationState.Activating])
      awaitCondition("activation batch", activation.batchCount == 1)
      activation.runNext()

      activation.failQueueWith(new IllegalStateException("queue failed"))
      val epoch = lifecycle.prepare(getModule)

      assertEquals(ParserPreparationState.Unavailable(epoch, "queue failed"), lifecycle.stateFor(getModule))
      assertEquals(1, bridge.closeCount)
    finally lifecycle.dispose()

  def testStaleActivationDiscardDoesNotCloseReplacementBridgeTwice(): Unit =
    val preparer   = new DeferredPreparer
    val activation = new ControlledActivation
    val lifecycle  = lifecycleFor(preparer, Vector.empty, activation)

    try
      val _           = lifecycle.prepare(getModule)
      val staleBridge = new TestParserBridge
      preparer.complete(0, staleBridge)
      await(lifecycle)(_.isInstanceOf[ParserPreparationState.Activating])
      awaitCondition("activation batch", activation.batchCount == 1)

      val _ = lifecycle.prepare(getModule)
      assertEquals(1, staleBridge.closeCount)
      activation.runNext()

      assertEquals(1, staleBridge.closeCount)
    finally lifecycle.dispose()

  def testDuplicateActivationCallbackCannotRepublishAnEpoch(): Unit =
    val file       = myFixture.addFileToProject("src/Once.scala", "class Once\n").getVirtualFile
    val preparer   = new DeferredPreparer
    val activation = new ControlledActivation
    val lifecycle  = lifecycleFor(preparer, Vector(file), activation)

    try
      val epoch = lifecycle.prepare(getModule)
      preparer.complete(0, new TestParserBridge)
      await(lifecycle)(_.isInstanceOf[ParserPreparationState.Activating])
      activation.runNext()
      activation.runLastAgain()

      assertEquals(ParserPreparationState.Ready(epoch), lifecycle.stateFor(getModule))
      assertEquals(1, activation.batchCount)
    finally lifecycle.dispose()

  def testReadyEpochReturnsToNeutralBeforeReplacementPreparationStarts(): Unit =
    val file       = myFixture.addFileToProject("src/Refresh.scala", "class Refresh\n").getVirtualFile
    val preparer   = new DeferredPreparer
    val activation = new ControlledActivation
    val lifecycle  = lifecycleFor(preparer, Vector(file), activation)

    try
      val firstEpoch  = lifecycle.prepare(getModule)
      val firstBridge = new TestParserBridge
      preparer.complete(0, firstBridge)
      await(lifecycle)(_.isInstanceOf[ParserPreparationState.Activating])
      activation.runNext()
      assertEquals(ParserPreparationState.Ready(firstEpoch), lifecycle.stateFor(getModule))

      val nextEpoch = lifecycle.prepare(getModule)
      assertEquals(ParserPreparationState.Neutralizing(nextEpoch), lifecycle.stateFor(getModule))
      assertFalse(firstBridge.closed)
      assertEquals(1, preparer.requestCount)

      activation.runNext()
      assertTrue(firstBridge.closed)
      assertEquals(2, preparer.requestCount)

      preparer.complete(1, new TestParserBridge)
      await(lifecycle)(_.isInstanceOf[ParserPreparationState.Activating])
      activation.runNext()
      assertEquals(ParserPreparationState.Ready(nextEpoch), lifecycle.stateFor(getModule))
    finally lifecycle.dispose()

  def testRepeatedReadyRefreshesCoalesceBehindOneNeutralTransition(): Unit =
    val preparer   = new DeferredPreparer
    val activation = new ControlledActivation
    val lifecycle  = lifecycleFor(preparer, Vector.empty, activation)

    try
      val _ = lifecycle.prepare(getModule)
      preparer.complete(0, new TestParserBridge)
      await(lifecycle)(_.isInstanceOf[ParserPreparationState.Activating])
      awaitCondition("activation batch", activation.batchCount == 1)
      activation.runNext()

      val superseded = lifecycle.prepare(getModule)
      val current    = lifecycle.prepare(getModule)

      assertTrue(superseded != current)
      assertEquals(ParserPreparationState.Neutralizing(current), lifecycle.stateFor(getModule))
      assertEquals(1, preparer.requestCount)
      assertEquals(2, activation.batchCount)

      activation.runNext()

      assertEquals(ParserPreparationState.Preparing(current), lifecycle.stateFor(getModule))
      assertEquals(2, preparer.requestCount)
    finally lifecycle.dispose()

  def testDisposeClosesBridgeOwnedByPendingNeutralTransitionOnce(): Unit =
    val preparer   = new DeferredPreparer
    val activation = new ControlledActivation
    val lifecycle  = lifecycleFor(preparer, Vector.empty, activation)

    try
      val _      = lifecycle.prepare(getModule)
      val bridge = new TestParserBridge
      preparer.complete(0, bridge)
      await(lifecycle)(_.isInstanceOf[ParserPreparationState.Activating])
      awaitCondition("activation batch", activation.batchCount == 1)
      activation.runNext()
      val _      = lifecycle.prepare(getModule)

      lifecycle.dispose()
      assertEquals(1, bridge.closeCount)
      activation.runNext()
      assertEquals(1, bridge.closeCount)
    finally lifecycle.dispose()

  private def lifecycleFor(
      preparer: Scala3ParserPreparer,
      files: Vector[VirtualFile],
      activation: Scala3ParserActivation,
      catalogPreparer: Scala3PsiCatalogPreparer = NativeScala3PsiCatalogPreparer,
      executor: Executor = com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService
  ): Scala3ParserPreparationLifecycle =
    new Scala3ParserPreparationLifecycle(
      getProject,
      preparer,
      _ => files,
      activation,
      catalogPreparer,
      executor
    )

  private def assertRange(marker: RangeMarker, document: Document, expected: String): Unit =
    assertTrue(marker.isValid)
    assertEquals(expected, document.getText(marker.getTextRange))

  private def await(lifecycle: Scala3ParserPreparationLifecycle)(
      predicate: ParserPreparationState => Boolean
  ): Unit =
    awaitCondition("parser preparation state", predicate(lifecycle.stateFor(getModule)))

  private def awaitCondition(label: String, condition: => Boolean): Unit =
    PlatformTestUtil.waitWithEventsDispatching(label, () => condition, 10000)

private[psiproducer] final class DeferredPreparer extends Scala3ParserPreparer:
  private val requests = ArrayBuffer.empty[CompletableFuture[Either[String, Scala3ParserBridge]]]

  override def prepare(module: Module): CompletableFuture[Either[String, Scala3ParserBridge]] =
    val request = new CompletableFuture[Either[String, Scala3ParserBridge]]
    requests += request
    request

  def complete(index: Int, bridge: Scala3ParserBridge): Unit =
    assert(requests(index).complete(Right(bridge)))

  def fail(index: Int, error: Throwable): Unit =
    assert(requests(index).completeExceptionally(error))

  def requestCount: Int = requests.size

private[psiproducer] final class RecordingCatalogPreparer(delegate: Scala3PsiCatalogPreparer)
    extends Scala3PsiCatalogPreparer:
  @volatile private var count       = 0
  @volatile var ranOnDispatchThread = false

  def invocationCount: Int = count

  override def prepare(project: com.intellij.openapi.project.Project): Either[String, Scala3PsiProductionCatalog] =
    count += 1
    ranOnDispatchThread = ApplicationManager.getApplication.isDispatchThread
    delegate.prepare(project)

private[psiproducer] final class PlatformRecordingActivation(project: com.intellij.openapi.project.Project)
    extends Scala3ParserActivation:
  val batches = ArrayBuffer.empty[Vector[VirtualFile]]

  def batchCount: Int = batches.size

  override def queue(
      files: Vector[VirtualFile],
      isCurrent: () => Boolean,
      onApplied: () => Unit,
      onDiscarded: () => Unit,
      onFailed: Throwable => Unit
  ): Unit =
    ApplicationManager.getApplication.invokeAndWait: () =>
      if isCurrent() then
        batches += files
        files.foreach: file =>
          val _ = LanguageUtil.getLanguageForPsi(project, file)
          LanguageSubstitutors.cancelReparsing(file)
        FileContentUtilCore.reparseFiles(files.asJava)
        onApplied()
      else onDiscarded()

private final class ControlledActivation extends Scala3ParserActivation:
  private val callbacks                                                                =
    ArrayBuffer.empty[(() => Boolean, () => Unit, () => Unit, Throwable => Unit)]
  private var last: Option[(() => Boolean, () => Unit, () => Unit, Throwable => Unit)] = None
  @volatile var batchCount: Int                                                        = 0
  private var queueFailure: Option[Throwable]                                          = None

  override def queue(
      files: Vector[VirtualFile],
      isCurrent: () => Boolean,
      onApplied: () => Unit,
      onDiscarded: () => Unit,
      onFailed: Throwable => Unit
  ): Unit =
    queueFailure.foreach: error =>
      queueFailure = None
      throw error
    callbacks += ((isCurrent, onApplied, onDiscarded, onFailed))
    batchCount += 1

  def runNext(): Unit =
    val callback = callbacks.remove(0)
    last = Some(callback)
    if callback._1() then callback._2() else callback._3()

  def runLastAgain(): Unit =
    last.foreach: callback =>
      if callback._1() then callback._2()

  def failNext(error: Throwable): Unit =
    val callback = callbacks.remove(0)
    last = Some(callback)
    callback._4(error)

  def failQueueWith(error: Throwable): Unit = queueFailure = Some(error)

private final class RecordingExecutor extends Executor:
  private val commands = ArrayBuffer.empty[Runnable]

  override def execute(command: Runnable): Unit = commands += command

  def runNext(): Unit = commands.remove(0).run()

private[psiproducer] final class TestParserBridge extends Scala3ParserBridge:
  private val loader = Scala3ParserLoaderIdentity(TestParserBridge.nextLoader.incrementAndGet())
  private var open   = true
  private var closes = 0

  override val identity: Scala3ParserCompilerIdentity =
    Scala3ParserCompilerIdentity(
      Scala3ParserArtifactCoordinate("org.scala-lang", "scala3-compiler_3", "test"),
      Vector.empty,
      loader
    )

  override val capabilities: Scala3ParserCapabilities =
    Scala3ParserCapabilities(
      ParserCapabilityStatus.Available,
      ParserCapabilityStatus.Available,
      ParserCapabilityStatus.Available,
      ParserCapabilityStatus.Available,
      ParserCapabilityStatus.Available,
      ParserCapabilityStatus.Available,
      ParserCapabilityStatus.Available,
      ParserCapabilityStatus.Available,
      ParserCapabilityStatus.Available
    )

  override def loaderState: Scala3ParserLoaderState =
    if open then Scala3ParserLoaderState.Open else Scala3ParserLoaderState.Closed

  override def parse(request: Scala3ParserRequest): Either[Scala3ParserError, ParserSyntaxSnapshot] =
    Left(Scala3ParserError.Closed)

  override def close(): Unit =
    closes += 1
    open = false

  def closed: Boolean = !open
  def closeCount: Int = closes

private object TestParserBridge:
  val nextLoader = new AtomicLong(0L)

private final class TestControlFlowException extends RuntimeException("cancelled"), ControlFlowException
