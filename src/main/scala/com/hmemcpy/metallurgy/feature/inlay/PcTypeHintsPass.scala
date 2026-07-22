package com.hmemcpy.metallurgy.feature.inlay

import com.hmemcpy.metallurgy.compilerbackend.{CompilerBackendRole, CompilerBackendState, Scala3CompilerBackend}
import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.hmemcpy.metallurgy.pc.PcSessionManager
import com.intellij.codeHighlighting.EditorBoundHighlightingPass
import com.intellij.codeInsight.daemon.impl.HintRenderer
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.{Module, ModuleUtilCore}
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.{Disposer, Key}
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.{PsiElement, PsiFile}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.DocumentUtil
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScTypedPatternLike
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScValueOrVariableDefinition

import java.util.concurrent.{CancellationException, ExecutionException, TimeoutException, TimeUnit}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Inline type-hint pass backed by the current whole-file compiler snapshot. For each simple value definition without a
  * declared type annotation, the compiler's rendered binding type is shown after the binding name. Existing hints are
  * disposed and rebuilt in a single bulk edit on every run, so only the latest committed snapshot is visible.
  */
final class PcTypeHintsPass(editor: Editor, file: PsiFile) extends EditorBoundHighlightingPass(editor, file, false):

  private val hints          = mutable.ArrayBuffer.empty[PcTypeHintsPass.TypeHint]
  private val sessionManager = PcSessionManager.get(file.getProject)
  private val backend        = Scala3CompilerBackend.get(file.getProject)

  override def doCollectInformation(indicator: ProgressIndicator): Unit =
    hints.clear()
    val project = file.getProject
    for
      module      <-
        Option(ModuleUtilCore.findModuleForPsiElement(file)).filter(ModuleDetectionService.get(project).isActive)
      virtualFile <- Option(file.getVirtualFile)
    do
      if PcTypeHintsPass.awaitPublication(sessionManager.prepareCompilerBackend(virtualFile), indicator) then
        collectHints(module, indicator)

  override def doApplyInformationToEditor(): Unit                              =
    val document = editor.getDocument
    val model    = editor.getInlayModel
    val existing =
      model.getInlineElementsInRange(0, math.max(0, document.getTextLength)).asScala.filter(PcTypeHintsPass.owns)

    val bulkChange = existing.size + hints.size > PcTypeHintsPass.BulkChangeThreshold
    DocumentUtil.executeInBulk(
      document,
      bulkChange,
      () =>
        existing.foreach(Disposer.dispose)
        hints
          .sortBy(_.offset)
          .foreach: hint =>
            val inlay = model.addInlineElement(hint.offset, true, new HintRenderer(hint.text))
            if inlay != null then inlay.putUserData(PcTypeHintsPass.InlayKey, java.lang.Boolean.TRUE)
    )
  private def collectHints(module: Module, indicator: ProgressIndicator): Unit =
    val definitions = PsiTreeUtil.findChildrenOfType(file, classOf[ScValueOrVariableDefinition])
    val iterator    = definitions.iterator()
    while iterator.hasNext do
      indicator.checkCanceled()
      iterator.next() match
        case definition: ScValueOrVariableDefinition if definition.bindings.size == 1 =>
          definition.expr.foreach: _ =>
            definition.bindings.headOption.foreach: binding =>
              if !PcTypeHintsPass.hasDeclaredType(binding) then
                backend.stateForActiveModule(binding, module, CompilerBackendRole.Binding) match
                  case CompilerBackendState.Current(tpe, _) if PcTypeHintsPass.isMeaningful(tpe) =>
                    hints += PcTypeHintsPass.TypeHint(
                      binding.getTextRange.getEndOffset,
                      s": ${StringUtil.trim(tpe)}"
                    )
                  case _                                                                         => ()
        case _                                                                        => ()

object PcTypeHintsPass:
  private val InlayKey: Key[java.lang.Boolean] = Key.create("METALLURGY_TYPE_HINT")

  private val BulkChangeThreshold = 1000

  private val AwaitTimeoutNanos = TimeUnit.SECONDS.toNanos(5L)

  private val PollIntervalMillis = 50L

  private final case class TypeHint(offset: Int, text: String)

  private def owns(inlay: com.intellij.openapi.editor.Inlay[?]): Boolean =
    inlay.getUserData(InlayKey) != null

  private def hasDeclaredType(binding: PsiElement): Boolean =
    Option(binding.getParent).exists(_.isInstanceOf[ScTypedPatternLike])

  private def isMeaningful(renderedType: String): Boolean =
    val trimmed = renderedType.trim
    trimmed.nonEmpty && trimmed != "Any" && trimmed != "?"

  /** Wait until the current document's backend snapshot has committed. Polling with cancellation checks lets the daemon
    * abort this pass promptly when the document changes again.
    */
  private def awaitPublication(
      future: java.util.concurrent.CompletableFuture[Option[com.hmemcpy.metallurgy.pc.PcSession]],
      indicator: ProgressIndicator
  ): Boolean =
    try
      val deadline = System.nanoTime() + AwaitTimeoutNanos
      while !future.isDone && System.nanoTime() < deadline do
        indicator.checkCanceled()
        try
          val _ = future.get(PollIntervalMillis, TimeUnit.MILLISECONDS)
        catch case _: TimeoutException => ()
      future.isDone && future.get(0L, TimeUnit.MILLISECONDS).nonEmpty
    catch
      case canceled: ControlFlowException => throw canceled
      case error: ExecutionException =>
        error.getCause match
          case canceled: ControlFlowException => throw canceled
          case _                              => false
      case _: CancellationException  => false
      case _: InterruptedException   =>
        Thread.currentThread().interrupt()
        false
