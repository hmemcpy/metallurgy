package com.hmemcpy.metallurgy.feature.inlay

import com.hmemcpy.metallurgy.feature.compilertype.TypeRenderer
import com.hmemcpy.metallurgy.module.{BundledPluginBridge, ModuleDetectionService}
import com.hmemcpy.metallurgy.pc.{PcSession, PcSessionManager, PcSnapshot}
import com.intellij.codeHighlighting.EditorBoundHighlightingPass
import com.intellij.codeInsight.daemon.impl.HintRenderer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.{Disposer, Key}
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.{PsiElement, PsiFile}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.DocumentUtil
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScTypedPatternLike
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScValueOrVariableDefinition

import java.util.concurrent.{TimeoutException, TimeUnit}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Inline type-hint pass backed by the presentation compiler. For each simple value definition without a declared type
  * annotation, the compiler's rendered type is shown as an inline hint after the binding name, and the same type is
  * stored in the bundled plugin's compiler-type slot on the definition's initializer so hover and type resolution read
  * it without waiting for a completion. A retypecheck for the current document version is awaited before querying, so
  * the typed snapshot is always present when hints are read. Existing hints are disposed and rebuilt in a single bulk
  * edit on every run, so only the latest snapshot is visible.
  */
final class PcTypeHintsPass(editor: Editor, file: PsiFile) extends EditorBoundHighlightingPass(editor, file, false):

  private val hints                   = mutable.ArrayBuffer.empty[PcTypeHintsPass.TypeHint]
  private val sessionManager          = PcSessionManager.get(file.getProject)
  @volatile private var filledAnySlot = false

  override def doCollectInformation(indicator: ProgressIndicator): Unit =
    hints.clear()
    filledAnySlot = false
    val project = file.getProject
    for
      module      <-
        Option(ModuleUtilCore.findModuleForPsiElement(file)).filter(ModuleDetectionService.get(project).isActive)
      virtualFile <- Option(file.getVirtualFile)
      session     <- sessionManager.sessionFor(module)
    do
      val document = editor.getDocument
      val snapshot = PcSnapshot(virtualFile.getUrl, document.getModificationStamp, document.getText)
      PcTypeHintsPass.awaitTypedSnapshot(session, snapshot, indicator)
      collectHints(session, snapshot, indicator)

  override def doApplyInformationToEditor(): Unit =
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
    if filledAnySlot then BundledPluginBridge.invalidateScalaTypeCaches()

  private def collectHints(session: PcSession, snapshot: PcSnapshot, indicator: ProgressIndicator): Unit =
    val definitions = PsiTreeUtil.findChildrenOfType(file, classOf[ScValueOrVariableDefinition])
    val iterator    = definitions.iterator()
    while iterator.hasNext do
      indicator.checkCanceled()
      iterator.next() match
        case definition: ScValueOrVariableDefinition if definition.bindings.size == 1 =>
          definition.expr.foreach: initializer =>
            definition.bindings.headOption.foreach: binding =>
              if !PcTypeHintsPass.hasDeclaredType(binding) then
                TypeRenderer.render(session, snapshot, binding.getTextOffset).filter(PcTypeHintsPass.isMeaningful) match
                  case Some(tpe) =>
                    hints +=
                      PcTypeHintsPass.TypeHint(binding.getTextRange.getEndOffset, s": ${StringUtil.trim(tpe)}")
                    BundledPluginBridge.setCompilerType(binding, tpe)
                    BundledPluginBridge.setCompilerType(initializer, tpe)
                    filledAnySlot = true
                  case None      =>
                    BundledPluginBridge.clearCompilerType(binding)
                    BundledPluginBridge.clearCompilerType(initializer)
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

  /** Block on the retypecheck for `snapshot` until the typed document is published. Polling with cancellation checks
    * lets the daemon abort this pass promptly when the document changes again.
    */
  private def awaitTypedSnapshot(session: PcSession, snapshot: PcSnapshot, indicator: ProgressIndicator): Unit =
    val future   = session.scheduleRetypecheck(snapshot)
    val deadline = System.nanoTime() + AwaitTimeoutNanos
    while !future.isDone && System.nanoTime() < deadline do
      indicator.checkCanceled()
      try
        val _ = future.get(PollIntervalMillis, TimeUnit.MILLISECONDS)
      catch case _: TimeoutException => ()
