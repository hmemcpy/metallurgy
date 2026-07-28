package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.{Document, RangeMarker}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.lang.LanguageUtil
import com.intellij.psi.LanguageSubstitutors
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.FileContentUtilCore
import org.junit.Assert.{assertEquals, assertFalse, assertSame, assertTrue}

import java.util.concurrent.CompletableFuture
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
    val lifecycle   = lifecycleFor(preparer, Vector(first, second), activation)

    try
      val epoch = lifecycle.prepare(getModule)
      assertEquals(ParserPreparationState.Preparing(epoch), lifecycle.stateFor(getModule))

      val bridge = new TestParserBridge
      preparer.complete(0, bridge)

      assertEquals(ParserPreparationState.Ready(epoch), lifecycle.stateFor(getModule))
      assertEquals(1, activation.batchCount)
      assertEquals(Vector(first, second), activation.batches.head)
      assertSame(first, activation.batches.head.head)
      assertTrue(first.isValid)
      assertSame(document, FileDocumentManager.getInstance.getDocument(first))
      assertRange(rangeMarker, document, "class First")
      assertFalse(bridge.closed)
    finally lifecycle.dispose()

  def testStaleCompletionCannotActivateNewerEpoch(): Unit =
    val file       = myFixture.addFileToProject("src/Current.scala", "class Current\n").getVirtualFile
    val preparer   = new DeferredPreparer
    val activation = new ControlledActivation
    val lifecycle  = lifecycleFor(preparer, Vector(file), activation)

    try
      val staleEpoch   = lifecycle.prepare(getModule)
      val currentEpoch = lifecycle.prepare(getModule)
      val staleBridge  = new TestParserBridge
      preparer.complete(0, staleBridge)

      assertTrue(staleBridge.closed)
      assertEquals(ParserPreparationState.Preparing(currentEpoch), lifecycle.stateFor(getModule))
      assertEquals(0, activation.batchCount)

      val currentBridge = new TestParserBridge
      preparer.complete(1, currentBridge)
      assertEquals(ParserPreparationState.Activating(currentEpoch), lifecycle.stateFor(getModule))
      assertEquals(1, activation.batchCount)

      activation.runNext()
      assertEquals(ParserPreparationState.Ready(currentEpoch), lifecycle.stateFor(getModule))
      assertFalse(currentBridge.closed)
      assertTrue(staleEpoch != currentEpoch)
    finally lifecycle.dispose()

  def testDuplicateActivationCallbackCannotRepublishAnEpoch(): Unit =
    val file       = myFixture.addFileToProject("src/Once.scala", "class Once\n").getVirtualFile
    val preparer   = new DeferredPreparer
    val activation = new ControlledActivation
    val lifecycle  = lifecycleFor(preparer, Vector(file), activation)

    try
      val epoch = lifecycle.prepare(getModule)
      preparer.complete(0, new TestParserBridge)
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
      activation.runNext()
      assertEquals(ParserPreparationState.Ready(firstEpoch), lifecycle.stateFor(getModule))

      val nextEpoch = lifecycle.prepare(getModule)
      assertEquals(ParserPreparationState.Preparing(nextEpoch), lifecycle.stateFor(getModule))
      assertFalse(firstBridge.closed)
      assertEquals(1, preparer.requestCount)

      activation.runNext()
      assertTrue(firstBridge.closed)
      assertEquals(2, preparer.requestCount)

      preparer.complete(1, new TestParserBridge)
      activation.runNext()
      assertEquals(ParserPreparationState.Ready(nextEpoch), lifecycle.stateFor(getModule))
    finally lifecycle.dispose()

  private def lifecycleFor(
      preparer: DeferredPreparer,
      files: Vector[VirtualFile],
      activation: Scala3ParserActivation
  ): Scala3ParserPreparationLifecycle =
    new Scala3ParserPreparationLifecycle(
      getProject,
      preparer,
      _ => files,
      activation
    )

  private def assertRange(marker: RangeMarker, document: Document, expected: String): Unit =
    assertTrue(marker.isValid)
    assertEquals(expected, document.getText(marker.getTextRange))

private[psiproducer] final class DeferredPreparer extends Scala3ParserPreparer:
  private val requests = ArrayBuffer.empty[CompletableFuture[Either[String, Scala3ParserBridge]]]

  override def prepare(module: Module): CompletableFuture[Either[String, Scala3ParserBridge]] =
    val request = new CompletableFuture[Either[String, Scala3ParserBridge]]
    requests += request
    request

  def complete(index: Int, bridge: Scala3ParserBridge): Unit =
    assert(requests(index).complete(Right(bridge)))

  def requestCount: Int = requests.size

private[psiproducer] final class PlatformRecordingActivation(project: com.intellij.openapi.project.Project)
    extends Scala3ParserActivation:
  val batches = ArrayBuffer.empty[Vector[VirtualFile]]

  def batchCount: Int = batches.size

  override def queue(
      files: Vector[VirtualFile],
      isCurrent: () => Boolean,
      onApplied: () => Unit,
      onDiscarded: () => Unit
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
  private val callbacks                                             = ArrayBuffer.empty[(() => Boolean, () => Unit, () => Unit)]
  private var last: Option[(() => Boolean, () => Unit, () => Unit)] = None
  var batchCount: Int                                               = 0

  override def queue(
      files: Vector[VirtualFile],
      isCurrent: () => Boolean,
      onApplied: () => Unit,
      onDiscarded: () => Unit
  ): Unit =
    batchCount += 1
    callbacks += ((isCurrent, onApplied, onDiscarded))

  def runNext(): Unit =
    val callback = callbacks.remove(0)
    last = Some(callback)
    if callback._1() then callback._2() else callback._3()

  def runLastAgain(): Unit =
    last.foreach: callback =>
      if callback._1() then callback._2()

private[psiproducer] final class TestParserBridge extends Scala3ParserBridge:
  private val loader = Scala3ParserLoaderIdentity(TestParserBridge.nextLoader.incrementAndGet())
  private var open   = true

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

  override def close(): Unit = open = false

  def closed: Boolean = !open

private object TestParserBridge:
  val nextLoader = new AtomicLong(0L)
