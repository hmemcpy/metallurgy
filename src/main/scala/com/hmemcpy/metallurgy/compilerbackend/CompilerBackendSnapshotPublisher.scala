package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.hmemcpy.metallurgy.pc.{
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
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.{ScBindingPattern, ScPattern}
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScValueOrVariableDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameter

import java.util.concurrent.{CancellationException, CompletableFuture, ConcurrentHashMap}
import scala.util.control.NonFatal

/** Maps a boundary-safe compiler snapshot to current PSI in a cancellable read action, then commits it on the UI thread
  * only while every captured generation remains current.
  */
private[metallurgy] final class CompilerBackendSnapshotPublisher(
    project: Project,
    beforeMap: () => Unit = () => ()
):

  private final case class PublicationKey(
      module: Module,
      fileUri: String,
      documentVersion: Long,
      generation: CompilerBackendGeneration
  )

  private val backend  = Scala3CompilerBackend.get(project)
  private val log      = Logger.getInstance(classOf[CompilerBackendSnapshotPublisher])
  private val inFlight = new ConcurrentHashMap[PublicationKey, CompletableFuture[CompilerBackendCommit]]()

  def publish(
      module: Module,
      snapshot: PcTypedTreeSnapshot,
      generation: CompilerBackendGeneration,
      snapshotCurrency: () => PcSnapshotCurrency
  ): CompletableFuture[CompilerBackendCommit] =
    if project.isDisposed || snapshotCurrency() != PcSnapshotCurrency.Current then
      CompletableFuture.completedFuture(CompilerBackendCommit.Rejected)
    else
      val key        = PublicationKey(module, snapshot.fileUri, snapshot.documentVersion, generation)
      val completion = new CompletableFuture[CompilerBackendCommit]()
      Option(inFlight.putIfAbsent(key, completion)) match
        case Some(existing) => existing
        case None           =>
          val _ = completion.whenComplete: (_, _) =>
            val _ = inFlight.remove(key, completion)
          try
            val promise = ReadAction
              .nonBlocking(() =>
                beforeMap()
                mapCurrentFile(module, snapshot)
              )
              .inSmartMode(project)
              .coalesceBy(this, module, snapshot.fileUri)
              .expireWith(project)
              .expireWhen(() => !isCurrent(module, snapshot, snapshotCurrency))
              .finishOnUiThread(
                ModalityState.nonModal(),
                mappings =>
                  val _ = completion.complete(commit(module, snapshot, generation, snapshotCurrency, mappings))
              )
              .submit(AppExecutorUtil.getAppExecutorService)
            val _       = promise.onError: error =>
              error match
                case _: CancellationException      =>
                  val _ = completion.complete(CompilerBackendCommit.Rejected)
                case control: ControlFlowException =>
                  val _ = completion.completeExceptionally(control)
                  throw control
                case _                             =>
                  log.warn(s"Compiler-backend PSI mapping failed for ${snapshot.fileUri}", error)
                  backend.markFailed(module, snapshot.fileUri, snapshot.documentVersion, generation)
                  val _ = completion.complete(CompilerBackendCommit.Rejected)
          catch
            case control: ControlFlowException =>
              inFlight.remove(key, completion)
              throw control
            case NonFatal(error) =>
              log.warn(s"Could not schedule compiler-backend PSI mapping for ${snapshot.fileUri}", error)
              backend.markFailed(module, snapshot.fileUri, snapshot.documentVersion, generation)
              val _ = completion.complete(CompilerBackendCommit.Rejected)
          completion

  private[metallurgy] def mapCurrentFile(
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
      snapshot: PcTypedTreeSnapshot,
      generation: CompilerBackendGeneration,
      snapshotCurrency: () => PcSnapshotCurrency,
      mappings: Seq[CompilerBackendMapping]
  ): CompilerBackendCommit =
    currentFile(module, snapshot)
      .map: file =>
        backend.commitSnapshot(module, file, snapshot.documentVersion, generation, mappings):
          snapshotCurrency()
      .getOrElse(CompilerBackendCommit.Rejected)

  private def isCurrent(
      module: Module,
      snapshot: PcTypedTreeSnapshot,
      snapshotCurrency: () => PcSnapshotCurrency
  ): Boolean =
    snapshotCurrency() == PcSnapshotCurrency.Current &&
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
      case PcTypedTreeRole.FunctionResult    =>
        exactAncestor[ScFunction](file, entry.range)
          .map(mapping(_, CompilerBackendRole.FunctionResult, entry.renderedType, symbolId))
          .toSeq
      case PcTypedTreeRole.Pattern           =>
        exactAncestor[ScPattern](file, entry.range).toSeq.flatMap:
          case binding: ScBindingPattern =>
            Seq(
              mapping(binding, CompilerBackendRole.Binding, entry.renderedType, symbolId),
              mapping(binding, CompilerBackendRole.Pattern, entry.renderedType, symbolId)
            )
          case pattern                   => Seq(mapping(pattern, CompilerBackendRole.Pattern, entry.renderedType, symbolId))
      case PcTypedTreeRole.PatternExpected   =>
        exactAncestor[ScPattern](file, entry.range)
          .map(mapping(_, CompilerBackendRole.PatternExpected, entry.renderedType, symbolId))
          .toSeq

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
