package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.module.{BundledPluginBridge, ModuleDetectionService}
import com.hmemcpy.metallurgy.pc.{PcSnapshotCurrency, PcSourceRange}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.{Module, ModuleUtilCore}
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.{PsiElement, PsiFile, PsiManager}
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScBindingPattern
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScValueOrVariableDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameter
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory
import org.jetbrains.plugins.scala.lang.psi.types.result.TypeResult

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*

/** Cache-only Scala 3 compiler backend for synchronous PSI reads. The immutable file state is the freshness source;
  * `CompilerType` strings are compatibility data owned and validated by that state.
  */
final class Scala3CompilerBackend(project: Project):

  private final case class FileKey(module: Module, fileUrl: String)
  private final case class ElementKey(range: PcSourceRange, role: CompilerBackendRole, symbolId: Option[String])
  private final case class FileState(
      revision: Long,
      documentVersion: Long,
      identity: CompilerBackendIdentity,
      fallback: CompilerBackendState,
      entries: Map[ElementKey, CompilerBackendState],
      entryOrder: Vector[ElementKey],
      compilerTypeSlots: Set[ElementKey]
  )
  private final case class CommitTarget(fileKey: FileKey, previous: FileState)
  private final case class CommitPlan(
      committed: FileState,
      resolved: Seq[(CompilerBackendMapping, PsiElement)],
      slotMappings: Vector[(ElementKey, String)],
      changedKeys: Set[ElementKey],
      removedSlots: Set[ElementKey]
  )

  private val files        = new ConcurrentHashMap[FileKey, FileState]()
  private val nextRevision = new AtomicLong(0L)

  def publish(
      element: PsiElement,
      role: CompilerBackendRole,
      documentVersion: Long,
      renderedType: String
  ): CompilerBackendPublication =
    activeModule(element) match
      case Some(module) =>
        parsedState(element, renderedType) match
          case Some(state) =>
            put(element, module, role, documentVersion, state)
            CompilerBackendPublication.Published
          case None        =>
            put(element, module, role, documentVersion, CompilerBackendState.Unavailable)
            CompilerBackendPublication.Unparsable
      case None         => CompilerBackendPublication.IgnoredInactive

  def publishState(
      element: PsiElement,
      role: CompilerBackendRole,
      documentVersion: Long,
      state: CompilerBackendState
  ): Unit =
    activeModule(element).foreach(module => put(element, module, role, documentVersion, state))

  private[metallurgy] def markPending(
      module: Module,
      fileUrl: String,
      documentVersion: Long,
      generation: CompilerBackendGeneration
  ): Unit =
    if ModuleDetectionService.get(project).isActive(module) && fileBelongsToModule(fileUrl, module) then
      val key        = FileKey(module, fileUrl)
      val transition = transitionFile(key): previous =>
        val isCurrent = previous.exists: state =>
          state.documentVersion == documentVersion &&
            state.identity == CompilerBackendIdentity.Snapshot(generation) &&
            state.fallback == CompilerBackendState.Unavailable
        if isCurrent then previous
        else
          Some(
            FileState(
              revision = nextRevision.incrementAndGet(),
              documentVersion = documentVersion,
              identity = CompilerBackendIdentity.Snapshot(generation),
              fallback = CompilerBackendState.Pending,
              entries = previous.filter(_.documentVersion == documentVersion).map(_.entries).getOrElse(Map.empty),
              entryOrder =
                previous.filter(_.documentVersion == documentVersion).map(_.entryOrder).getOrElse(Vector.empty),
              compilerTypeSlots = previous.map(_.compilerTypeSlots).getOrElse(Set.empty)
            )
          )
      transition match
        case (before, Some(pending)) if before != Some(pending) =>
          retireMappedValues(
            key,
            pending.revision,
            before.toSeq.flatMap(_.entries.keySet).toSet,
            before.toSeq.flatMap(_.compilerTypeSlots).toSet
          )
        case _                                                  => ()

  private[metallurgy] def markFailed(
      module: Module,
      fileUrl: String,
      documentVersion: Long,
      generation: CompilerBackendGeneration
  ): Unit =
    updateFallback(module, fileUrl, documentVersion, generation, CompilerBackendState.Failed)

  private[metallurgy] def markUnavailable(
      module: Module,
      fileUrl: String,
      documentVersion: Long,
      generation: CompilerBackendGeneration
  ): Unit =
    updateFallback(module, fileUrl, documentVersion, generation, CompilerBackendState.Unavailable)

  private[metallurgy] def commitSnapshot(
      module: Module,
      file: PsiFile,
      documentVersion: Long,
      generation: CompilerBackendGeneration,
      mappings: Seq[CompilerBackendMapping]
  )(currency: => PcSnapshotCurrency): CompilerBackendCommit =
    if currency != PcSnapshotCurrency.Current then CompilerBackendCommit.Rejected
    else
      commitTarget(module, file, documentVersion, generation) match
        case None         => CompilerBackendCommit.Rejected
        case Some(target) =>
          val plan = prepareCommit(file, documentVersion, generation, mappings, target)
          if currency != PcSnapshotCurrency.Current || !files.replace(target.fileKey, target.previous, plan.committed)
          then CompilerBackendCommit.Rejected
          else applyCommit(file, plan)

  private def commitTarget(
      module: Module,
      file: PsiFile,
      documentVersion: Long,
      generation: CompilerBackendGeneration
  ): Option[CommitTarget] =
    for
      virtualFile <- Option(file.getVirtualFile)
      if file.getProject == project
      if ModuleUtilCore.findModuleForFile(virtualFile, project) == module
      if ModuleDetectionService.get(project).isActive(module)
      document    <- Option(FileDocumentManager.getInstance().getDocument(virtualFile))
      if document.getModificationStamp == documentVersion
      fileKey      = FileKey(module, virtualFile.getUrl)
      previous    <- Option(files.get(fileKey))
      if previous.documentVersion == documentVersion
      if previous.identity == CompilerBackendIdentity.Snapshot(generation)
    yield CommitTarget(fileKey, previous)

  private def prepareCommit(
      file: PsiFile,
      documentVersion: Long,
      generation: CompilerBackendGeneration,
      mappings: Seq[CompilerBackendMapping],
      target: CommitTarget
  ): CommitPlan =
    val resolved     = mappings.flatMap(resolveMapping(file, _))
    val entries      = resolved.map: (mapping, element) =>
      val key = ElementKey(mapping.range, mapping.role, mapping.symbolId)
      key -> parsedState(element, mapping.renderedType).getOrElse(CompilerBackendState.Unavailable)
    val current      = entries.foldLeft(Map.empty[ElementKey, CompilerBackendState]):
      case (ranked, (key, state)) =>
        if ranked.contains(key) then ranked else ranked.updated(key, state)
    val slotMappings = firstCompilerTypeMappings(resolved)
    val slotKeys     = slotMappings.iterator.map(_._1).toSet
    val committed    = FileState(
      revision = nextRevision.incrementAndGet(),
      documentVersion = documentVersion,
      identity = CompilerBackendIdentity.Snapshot(generation),
      fallback = CompilerBackendState.Unavailable,
      entries = current,
      entryOrder = entries.iterator.map(_._1).toVector.distinct,
      compilerTypeSlots = slotKeys
    )
    CommitPlan(
      committed,
      resolved,
      slotMappings,
      changedElementKeys(target.previous.entries, current),
      target.previous.compilerTypeSlots -- slotKeys
    )

  private def applyCommit(file: PsiFile, plan: CommitPlan): CompilerBackendCommit =
    val changedSlots     = plan.slotMappings.flatMap: (key, value) =>
      findElement(file, key).filter: element =>
        val changed = Option(BundledPluginBridge.getCompilerType(element)).forall(_ != value)
        if changed then BundledPluginBridge.setCompilerType(element, value)
        changed
    val clearedSlots     = plan.removedSlots.toSeq
      .flatMap(key => findElement(file, key))
      .filter: element =>
        val present = BundledPluginBridge.getCompilerType(element) != null
        if present then BundledPluginBridge.clearCompilerType(element)
        present
    val resolvedByKey    = plan.resolved.map: (mapping, element) =>
      ElementKey(mapping.range, mapping.role, mapping.symbolId) -> element
    val changedElements  = plan.changedKeys.flatMap: key =>
      resolvedByKey.collectFirst { case (`key`, element) => element }.orElse(findElement(file, key))
    val unresolvedChange = plan.changedKeys.exists: key =>
      resolvedByKey.collectFirst { case (`key`, element) => element }.orElse(findElement(file, key)).isEmpty
    val invalidated      =
      (changedElements ++ changedSlots ++ clearedSlots ++ Option.when(unresolvedChange)(file)).toSeq.distinct
    invalidate(invalidated)
    CompilerBackendCommit.Committed(invalidated.size)

  private[compilerbackend] def stateForActiveModule(
      element: PsiElement,
      module: Module,
      role: CompilerBackendRole
  ): CompilerBackendState =
    if element.getProject != project || !ModuleDetectionService.get(project).isActive(module) then
      CompilerBackendState.Unavailable
    else
      (fileKey(element, module), elementRange(element), currentDocument(element)) match
        case (Some(file), Some(range), Some(document)) =>
          Option(files.get(file)) match
            case Some(state) if state.documentVersion == document.getModificationStamp =>
              state.fallback match
                case CompilerBackendState.Pending | CompilerBackendState.Failed => state.fallback
                case _                                                          =>
                  state.entryOrder
                    .find(key => key.range == range && key.role == role)
                    .flatMap(state.entries.get)
                    .getOrElse(CompilerBackendState.Unavailable)
            case Some(_)                                                               => CompilerBackendState.Pending
            case None                                                                  => CompilerBackendState.Unavailable
        case _                                         => CompilerBackendState.Unavailable

  private[metallurgy] def validatedCompilerType(
      element: PsiElement,
      module: Module,
      role: CompilerBackendRole
  ): Option[String] =
    if !ModuleDetectionService.get(project).isActive(module) then None
    else
      stateForActiveModule(element, module, role) match
        case CompilerBackendState.Current(renderedType, _) => Some(renderedType)
        case _                                             =>
          if BundledPluginBridge.getCompilerType(element) != null then BundledPluginBridge.clearCompilerType(element)
          None

  def clear(): Unit =
    val retired = files.entrySet().asScala.map(entry => entry.getKey -> entry.getValue).toSeq
    retired.foreach: (key, state) =>
      if files.remove(key, state) then retireRemovedState(key, state.entries.keySet, state.compilerTypeSlots)

  def clear(module: Module): Unit =
    val retired = files.entrySet().asScala.filter(_.getKey.module == module).toSeq
    retired.foreach: entry =>
      if files.remove(entry.getKey, entry.getValue) then
        retireRemovedState(entry.getKey, entry.getValue.entries.keySet, entry.getValue.compilerTypeSlots)

  private def updateFallback(
      module: Module,
      fileUrl: String,
      documentVersion: Long,
      generation: CompilerBackendGeneration,
      fallback: CompilerBackendState
  ): Unit =
    if ModuleDetectionService.get(project).isActive(module) && fileBelongsToModule(fileUrl, module) then
      val key        = FileKey(module, fileUrl)
      val transition = transitionFile(key): previous =>
        previous.map: state =>
          if state.documentVersion == documentVersion &&
            state.identity == CompilerBackendIdentity.Snapshot(generation)
          then
            state.copy(
              revision = nextRevision.incrementAndGet(),
              fallback = fallback,
              entries = Map.empty,
              entryOrder = Vector.empty
            )
          else state
      transition match
        case (before, Some(updated)) if before != Some(updated) =>
          retireMappedValues(
            key,
            updated.revision,
            before.toSeq.flatMap(_.entries.keySet).toSet,
            before.toSeq.flatMap(_.compilerTypeSlots).toSet
          )
        case _                                                  => ()

  private def put(
      element: PsiElement,
      module: Module,
      role: CompilerBackendRole,
      documentVersion: Long,
      state: CompilerBackendState
  ): Unit =
    for
      file  <- fileKey(element, module)
      range <- elementRange(element)
    do
      val key = ElementKey(range, role, None)
      val _   = transitionFile(file): previous =>
        val existing = previous.filter(_.documentVersion == documentVersion)
        Some(
          FileState(
            revision = nextRevision.incrementAndGet(),
            documentVersion = documentVersion,
            identity = CompilerBackendIdentity.Direct,
            fallback = CompilerBackendState.Unavailable,
            entries = existing.map(_.entries).getOrElse(Map.empty).updated(key, state),
            entryOrder = existing.map(_.entryOrder).getOrElse(Vector.empty).filterNot(_ == key) :+ key,
            compilerTypeSlots = existing.map(_.compilerTypeSlots).getOrElse(Set.empty)
          )
        )

  private def parsedState(element: PsiElement, renderedType: String): Option[CompilerBackendState.Current] =
    ScalaPsiElementFactory
      .createTypeFromText(renderedType, element, null)
      .map: scType =>
        val result: TypeResult = Right(scType)
        CompilerBackendState.Current(renderedType, result)

  private def resolveMapping(
      file: PsiFile,
      mapping: CompilerBackendMapping
  ): Option[(CompilerBackendMapping, PsiElement)] =
    val key = ElementKey(mapping.range, mapping.role, mapping.symbolId)
    Option(mapping.element.getElement)
      .filter(element =>
        element.isValid && element.getContainingFile == file && elementRange(element).contains(mapping.range)
      )
      .orElse(findElement(file, key))
      .map(mapping -> _)

  private def firstCompilerTypeMappings(
      mappings: Seq[(CompilerBackendMapping, PsiElement)]
  ): Vector[(ElementKey, String)] =
    mappings
      .collect:
        case (mapping, _) if mapping.role == CompilerBackendRole.ExpressionExact => mapping
      .distinctBy(_.range)
      .map: mapping =>
        ElementKey(mapping.range, CompilerBackendRole.ExpressionExact, None) -> mapping.renderedType
      .toVector

  private def changedElementKeys(
      previous: Map[ElementKey, CompilerBackendState],
      current: Map[ElementKey, CompilerBackendState]
  ): Set[ElementKey] =
    (previous.keySet ++ current.keySet).filter: key =>
      comparable(previous.get(key)) != comparable(current.get(key))

  private def comparable(state: Option[CompilerBackendState]): Option[(String, String)] =
    state.map:
      case CompilerBackendState.Current(renderedType, _) => "current" -> renderedType
      case other                                         => "state"   -> other.productPrefix

  private def retireMappedValues(
      fileKey: FileKey,
      expectedRevision: Long,
      entries: Set[ElementKey],
      slots: Set[ElementKey]
  ): Unit =
    retireValuesWhen(fileKey, entries, slots)(() => Option(files.get(fileKey)).exists(_.revision == expectedRevision))

  private def retireRemovedState(
      fileKey: FileKey,
      entries: Set[ElementKey],
      slots: Set[ElementKey]
  ): Unit =
    retireValuesWhen(fileKey, entries, slots)(() => files.get(fileKey) == null)

  private def retireValuesWhen(
      fileKey: FileKey,
      entries: Set[ElementKey],
      slots: Set[ElementKey]
  )(stillOwned: () => Boolean): Unit =
    if (entries.nonEmpty || slots.nonEmpty) && !project.isDisposed then
      val clear = () =>
        if stillOwned() then
          currentPsiFile(fileKey.fileUrl).foreach: file =>
            val mapped     = entries.flatMap(findElement(file, _))
            val cleared    = slots
              .flatMap(findElement(file, _))
              .filter: element =>
                val present = BundledPluginBridge.getCompilerType(element) != null
                if present then BundledPluginBridge.clearCompilerType(element)
                present
            val unresolved = entries.exists(findElement(file, _).isEmpty)
            invalidate(mapped ++ cleared ++ Option.when(unresolved)(file))
      val app   = ApplicationManager.getApplication
      if app.isDispatchThread then clear()
      else app.invokeLater(() => clear())

  @annotation.tailrec
  private def transitionFile(
      key: FileKey
  )(update: Option[FileState] => Option[FileState]): (Option[FileState], Option[FileState]) =
    val before  = Option(files.get(key))
    val after   = update(before)
    val changed = (before, after) match
      case (None, Some(next))          => files.putIfAbsent(key, next) == null
      case (Some(current), Some(next)) => current == next || files.replace(key, current, next)
      case (Some(current), None)       => files.remove(key, current)
      case (None, None)                => true
    if changed then before -> after else transitionFile(key)(update)

  private def invalidate(elements: Iterable[PsiElement]): Unit =
    val distinct = elements.iterator.filter(_.isValid).toSeq.distinct
    distinct.foreach(BundledPluginBridge.clearScalaTypeCacheForElement(project, _))
    if distinct.nonEmpty then BundledPluginBridge.invalidateScalaTypeCaches()

  private def findElement(file: PsiFile, key: ElementKey): Option[PsiElement] =
    Option(file.findElementAt(math.min(key.range.startOffset, math.max(0, file.getTextLength - 1))))
      .flatMap: leaf =>
        Iterator
          .iterate(Option(leaf))(_.flatMap(element => Option(element.getParent)))
          .takeWhile(_.nonEmpty)
          .flatten
          .find: element =>
            elementRange(element).contains(key.range) && matchesRole(element, key.role)

  private def matchesRole(element: PsiElement, role: CompilerBackendRole): Boolean =
    role match
      case CompilerBackendRole.ExpressionExact | CompilerBackendRole.ExpressionWidened =>
        element.isInstanceOf[ScExpression]
      case CompilerBackendRole.DeclaredType                                            =>
        element.isInstanceOf[ScTypeElement]
      case CompilerBackendRole.Definition                                              =>
        element.isInstanceOf[ScValueOrVariableDefinition]
      case CompilerBackendRole.Binding | CompilerBackendRole.Pattern                   =>
        element.isInstanceOf[ScBindingPattern]
      case CompilerBackendRole.Function                                                =>
        element.isInstanceOf[ScFunction]
      case CompilerBackendRole.Parameter                                               =>
        element.isInstanceOf[ScParameter]

  private def currentPsiFile(fileUrl: String): Option[PsiFile] =
    Option(VirtualFileManager.getInstance().findFileByUrl(fileUrl))
      .flatMap(file => Option(PsiManager.getInstance(project).findFile(file)))

  private def fileBelongsToModule(fileUrl: String, module: Module): Boolean =
    Option(VirtualFileManager.getInstance().findFileByUrl(fileUrl))
      .exists(ModuleUtilCore.findModuleForFile(_, project) == module)

  private def fileKey(element: PsiElement, module: Module): Option[FileKey] =
    if element.getProject != project then None
    else
      Option(element.getContainingFile)
        .flatMap(file => Option(file.getVirtualFile))
        .map(file => FileKey(module, file.getUrl))

  private def elementRange(element: PsiElement): Option[PcSourceRange] =
    Option(element.getTextRange).map(range => PcSourceRange(range.getStartOffset, range.getEndOffset))

  private def activeModule(element: PsiElement): Option[Module] =
    if element.getProject != project then None
    else
      Option(ModuleUtilCore.findModuleForPsiElement(element))
        .filter(ModuleDetectionService.get(project).isActive)

  private def currentDocument(element: PsiElement): Option[Document] =
    Option(element.getContainingFile)
      .flatMap(file => Option(file.getVirtualFile))
      .flatMap(file => Option(FileDocumentManager.getInstance().getDocument(file)))

object Scala3CompilerBackend:
  def get(project: Project): Scala3CompilerBackend = project.getService(classOf[Scala3CompilerBackend])
