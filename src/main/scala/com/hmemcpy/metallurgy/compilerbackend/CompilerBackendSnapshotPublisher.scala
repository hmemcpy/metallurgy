package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.hmemcpy.metallurgy.pc.{
  PcSession,
  PcSnapshotCurrency,
  PcSourceRange,
  PcTypedTreeEntry,
  PcTypedTreeRole,
  PcTypedTreeSnapshot
}
import com.intellij.openapi.application.{ModalityState, ReadAction}
import com.intellij.openapi.diagnostic.{ControlFlowException, Logger}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.{Module, ModuleUtilCore}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiFile, PsiManager, SmartPointerManager}
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScBindingPattern
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScValueOrVariableDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameter

import java.util.concurrent.CancellationException
import scala.util.control.NonFatal

/** Maps a boundary-safe compiler snapshot to current PSI in a cancellable read action, then commits it on the UI thread
  * only while every captured generation remains current.
  */
private[metallurgy] final class CompilerBackendSnapshotPublisher(
    project: Project,
    snapshotCurrency: (Module, PcSession, CompilerBackendGeneration) => PcSnapshotCurrency
):

  private val backend = Scala3CompilerBackend.get(project)
  private val log     = Logger.getInstance(classOf[CompilerBackendSnapshotPublisher])

  def publish(
      module: Module,
      session: PcSession,
      snapshot: PcTypedTreeSnapshot,
      generation: CompilerBackendGeneration
  ): Unit =
    if project.isDisposed || snapshotCurrency(module, session, generation) != PcSnapshotCurrency.Current then return
    try
      val promise = ReadAction
        .nonBlocking(() => mapCurrentFile(module, snapshot))
        .inSmartMode(project)
        .coalesceBy(this, module, snapshot.fileUri)
        .expireWith(project)
        .expireWhen(() => !isCurrent(module, session, snapshot, generation))
        .finishOnUiThread(
          ModalityState.nonModal(),
          mappings => commit(module, session, snapshot, generation, mappings)
        )
        .submit(AppExecutorUtil.getAppExecutorService)
      val _       = promise.onError: error =>
        error match
          case _: CancellationException      => ()
          case control: ControlFlowException => throw control
          case _                             =>
            log.warn(s"Compiler-backend PSI mapping failed for ${snapshot.fileUri}", error)
            backend.markFailed(module, snapshot.fileUri, snapshot.documentVersion, generation)
    catch
      case NonFatal(error) =>
        log.warn(s"Could not schedule compiler-backend PSI mapping for ${snapshot.fileUri}", error)
        backend.markFailed(module, snapshot.fileUri, snapshot.documentVersion, generation)

  private[compilerbackend] def mapCurrentFile(
      module: Module,
      snapshot: PcTypedTreeSnapshot
  ): Seq[CompilerBackendMapping] =
    currentFile(module, snapshot)
      .map: file =>
        snapshot.entries.iterator
          .flatMap: entry =>
            ProgressManager.checkCanceled()
            mappingsFor(file, entry)
          .toSeq
      .getOrElse(Seq.empty)

  private def commit(
      module: Module,
      session: PcSession,
      snapshot: PcTypedTreeSnapshot,
      generation: CompilerBackendGeneration,
      mappings: Seq[CompilerBackendMapping]
  ): Unit =
    currentFile(module, snapshot).foreach: file =>
      val _ = backend.commitSnapshot(module, file, snapshot.documentVersion, generation, mappings):
        snapshotCurrency(module, session, generation)

  private def isCurrent(
      module: Module,
      session: PcSession,
      snapshot: PcTypedTreeSnapshot,
      generation: CompilerBackendGeneration
  ): Boolean =
    snapshotCurrency(module, session, generation) == PcSnapshotCurrency.Current &&
      ModuleDetectionService.get(project).isActive(module) &&
      Option(VirtualFileManager.getInstance().findFileByUrl(snapshot.fileUri))
        .filter(ModuleUtilCore.findModuleForFile(_, project) == module)
        .flatMap(file => Option(FileDocumentManager.getInstance().getDocument(file)))
        .exists(_.getModificationStamp == snapshot.documentVersion)

  private def currentFile(module: Module, snapshot: PcTypedTreeSnapshot): Option[PsiFile] =
    if !ModuleDetectionService.get(project).isActive(module) then None
    else
      for
        virtualFile <- Option(VirtualFileManager.getInstance().findFileByUrl(snapshot.fileUri))
        if ModuleUtilCore.findModuleForFile(virtualFile, project) == module
        file        <- Option(PsiManager.getInstance(project).findFile(virtualFile))
        document    <- Option(PsiDocumentManager.getInstance(project).getDocument(file))
        if document.getModificationStamp == snapshot.documentVersion
      yield file

  private def mappingsFor(file: PsiFile, entry: PcTypedTreeEntry): Seq[CompilerBackendMapping] =
    ProgressManager.checkCanceled()
    val symbolId = entry.symbol.map(_.id)
    entry.role match
      case PcTypedTreeRole.ExpressionExact   =>
        exactAncestor[ScExpression](file, entry.range)
          .map(mapping(_, CompilerBackendRole.ExpressionExact, entry.renderedType, symbolId))
          .toSeq
      case PcTypedTreeRole.ExpressionWidened =>
        exactAncestor[ScExpression](file, entry.range)
          .map(mapping(_, CompilerBackendRole.ExpressionWidened, entry.renderedType, symbolId))
          .toSeq
      case PcTypedTreeRole.Declared          =>
        exactAncestor[ScTypeElement](file, entry.range)
          .map(mapping(_, CompilerBackendRole.DeclaredType, entry.renderedType, symbolId))
          .toSeq
      case PcTypedTreeRole.Inferred          =>
        exactAncestor[ScValueOrVariableDefinition](file, entry.range).toSeq.flatMap: definition =>
          val binding =
            if definition.bindings.size == 1 then
              definition.bindings.map(mapping(_, CompilerBackendRole.Binding, entry.renderedType, symbolId))
            else Seq.empty
          mapping(definition, CompilerBackendRole.Definition, entry.renderedType, symbolId) +: binding
      case PcTypedTreeRole.Parameter         =>
        exactAncestor[ScParameter](file, entry.range)
          .map(mapping(_, CompilerBackendRole.Parameter, entry.renderedType, symbolId))
          .toSeq
      case PcTypedTreeRole.Function          =>
        exactAncestor[ScFunction](file, entry.range)
          .map(mapping(_, CompilerBackendRole.Function, entry.renderedType, symbolId))
          .toSeq
      case PcTypedTreeRole.Pattern           =>
        exactAncestor[ScBindingPattern](file, entry.range).toSeq
          .flatMap: pattern =>
            Seq(
              mapping(pattern, CompilerBackendRole.Binding, entry.renderedType, symbolId),
              mapping(pattern, CompilerBackendRole.Pattern, entry.renderedType, symbolId)
            )

  private def mapping(
      element: PsiElement,
      role: CompilerBackendRole,
      renderedType: String,
      symbolId: Option[String]
  ): CompilerBackendMapping =
    val range = element.getTextRange
    CompilerBackendMapping(
      SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element),
      PcSourceRange(range.getStartOffset, range.getEndOffset),
      role,
      renderedType,
      symbolId
    )

  private def exactAncestor[A <: PsiElement: reflect.ClassTag](file: PsiFile, range: PcSourceRange): Option[A] =
    val runtimeClass = summon[reflect.ClassTag[A]].runtimeClass
    Option(file.findElementAt(math.min(range.startOffset, math.max(0, file.getTextLength - 1))))
      .flatMap: leaf =>
        Iterator
          .iterate(Option(leaf))(_.flatMap(element => Option(element.getParent)))
          .takeWhile(_.nonEmpty)
          .flatten
          .find: element =>
            val textRange = element.getTextRange
            runtimeClass.isInstance(element) &&
            textRange != null &&
            textRange.getStartOffset == range.startOffset &&
            textRange.getEndOffset == range.endOffset
          .map(_.asInstanceOf[A])
