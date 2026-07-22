package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.module.{BundledPluginBridge, ModuleDetectionService}
import com.hmemcpy.metallurgy.pc.{PcSnapshotCurrency, PcSourceRange}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.{Module, ModuleUtilCore}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Condition
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.{PsiElement, PsiFile, PsiManager}
import com.intellij.psi.util.PsiTreeUtil
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
import scala.util.control.NonFatal

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

  private val files         = new ConcurrentHashMap[FileKey, FileState]()
  private val nextRevision  = new AtomicLong(0L)
  private val mutationLocks = Array.fill(64)(new Object())

  def publish(
      element: PsiElement,
      role: CompilerBackendRole,
      documentVersion: Long,
      renderedType: String
  ): CompilerBackendPublication =
    activeModule(element) match
      case Some(module) =>
        parsedState(element, role, renderedType) match
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
      val transition = withFileMutation(key):
        transitionFile(key): previous =>
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
                compilerTypeSlots = previous.map(ownedCompilerTypeSlots).getOrElse(Set.empty)
              )
            )
      transition match
        case (before, Some(pending)) if before != Some(pending) =>
          retireMappedValues(
            key,
            pending.revision,
            before.toSeq.flatMap(_.entries.keySet).toSet,
            before.toSeq.flatMap(ownedCompilerTypeSlots).toSet
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
          withFileMutation(target.fileKey):
            commitTarget(module, file, documentVersion, generation) match
              case None                => CompilerBackendCommit.Rejected
              case Some(currentTarget) =>
                val plan = prepareCommit(file, documentVersion, generation, mappings, currentTarget)
                if currency != PcSnapshotCurrency.Current ||
                  !files.replace(currentTarget.fileKey, currentTarget.previous, plan.committed)
                then CompilerBackendCommit.Rejected
                else if currency != PcSnapshotCurrency.Current then
                  val _ = files.replace(currentTarget.fileKey, plan.committed, currentTarget.previous)
                  CompilerBackendCommit.Rejected
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
      key -> parsedState(element, mapping.role, mapping.renderedType).getOrElse(CompilerBackendState.Unavailable)
    val current      = entries.foldLeft(Map.empty[ElementKey, CompilerBackendState]):
      case (ranked, (key, state)) =>
        if ranked.contains(key) then ranked else ranked.updated(key, state)
    val slotMappings = firstCompilerTypeMappings(
      resolved.filter: (mapping, _) =>
        current
          .get(ElementKey(mapping.range, mapping.role, mapping.symbolId))
          .exists(_.isInstanceOf[CompilerBackendState.Current])
    )
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
      ownedCompilerTypeSlots(target.previous) -- slotKeys
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

  private[metallurgy] def stateForActiveModule(
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
        case CompilerBackendState.Current(renderedType, _) =>
          if Option(BundledPluginBridge.getCompilerType(element)).forall(_ != renderedType) then
            BundledPluginBridge.setCompilerType(element, renderedType)
          Some(renderedType)
        case _                                             =>
          if BundledPluginBridge.getCompilerType(element) != null then BundledPluginBridge.clearCompilerType(element)
          None

  def clear(): Unit =
    val retired = files.entrySet().asScala.map(entry => entry.getKey -> entry.getValue).toSeq
    retired.foreach: (key, state) =>
      val removed = withFileMutation(key)(files.remove(key, state))
      if removed then retireRemovedState(key, state.entries.keySet, ownedCompilerTypeSlots(state))

  def clear(module: Module): Unit =
    val retired = files.entrySet().asScala.filter(_.getKey.module == module).toSeq
    retired.foreach: entry =>
      val removed = withFileMutation(entry.getKey)(files.remove(entry.getKey, entry.getValue))
      if removed then
        retireRemovedState(entry.getKey, entry.getValue.entries.keySet, ownedCompilerTypeSlots(entry.getValue))

  private def updateFallback(
      module: Module,
      fileUrl: String,
      documentVersion: Long,
      generation: CompilerBackendGeneration,
      fallback: CompilerBackendState
  ): Unit =
    if ModuleDetectionService.get(project).isActive(module) && fileBelongsToModule(fileUrl, module) then
      val key        = FileKey(module, fileUrl)
      val transition = withFileMutation(key):
        transitionFile(key): previous =>
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
            before.toSeq.flatMap(ownedCompilerTypeSlots).toSet
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
      val _   = withFileMutation(file):
        transitionFile(file): previous =>
          val existing = previous.filter(_.documentVersion == documentVersion)
          Some(
            FileState(
              revision = nextRevision.incrementAndGet(),
              documentVersion = documentVersion,
              identity = CompilerBackendIdentity.Direct,
              fallback = CompilerBackendState.Unavailable,
              entries = existing.map(_.entries).getOrElse(Map.empty).updated(key, state),
              entryOrder = existing.map(_.entryOrder).getOrElse(Vector.empty).filterNot(_ == key) :+ key,
              compilerTypeSlots = existing.map(ownedCompilerTypeSlots).getOrElse(Set.empty)
            )
          )

  private def parsedState(
      element: PsiElement,
      role: CompilerBackendRole,
      renderedType: String
  ): Option[CompilerBackendState.Current] =
    try
      val typeElement = ScalaPsiElementFactory.createTypeElementFromText(renderedType, element, null)
      Option(typeElement).flatMap: syntax =>
        if PsiTreeUtil.hasErrorElements(syntax) && acceptsPresentationOnlyType(role) then
          Some(CompilerBackendState.Current(renderedType, syntax.`type`()))
        else
          ScalaPsiElementFactory
            .createTypeFromText(renderedType, element, null)
            .filter: parsed =>
              !PsiTreeUtil.hasErrorElements(syntax) ||
                (hasBalancedDelimiters(renderedType) && !isFallbackType(renderedType, parsed.canonicalText))
            .map: parsed =>
              val result: TypeResult = Right(parsed)
              CompilerBackendState.Current(renderedType, result)
    catch
      case control: ControlFlowException => throw control
      case NonFatal(_) => None

  private def acceptsPresentationOnlyType(role: CompilerBackendRole): Boolean =
    role match
      case CompilerBackendRole.Function | CompilerBackendRole.Parameter | CompilerBackendRole.Pattern => true
      case _                                                                                          => false

  private def isFallbackType(renderedType: String, canonicalText: String): Boolean =
    val fallbackTypes = Set("Any", "Unit", "Nothing")
    val rendered      = renderedType.trim.stripPrefix("_root_.").stripPrefix("scala.")
    val canonical     = canonicalText.trim.stripPrefix("_root_.").stripPrefix("scala.")
    fallbackTypes.contains(canonical) && rendered != canonical

  private def hasBalancedDelimiters(renderedType: String): Boolean =
    val pairs = Map(')' -> '(', ']' -> '[', '}' -> '{')
    renderedType
      .foldLeft(Option(List.empty[Char])):
        case (Some(stack), char @ ('(' | '[' | '{'))                      => Some(char :: stack)
        case (Some(head :: tail), char) if pairs.get(char).contains(head) => Some(tail)
        case (Some(_), char) if pairs.contains(char)                      => None
        case (state, _)                                                   => state
      .contains(Nil)

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

  private def ownedCompilerTypeSlots(state: FileState): Set[ElementKey] =
    state.compilerTypeSlots ++ state.entries.collect:
      case (key, _: CompilerBackendState.Current) if key.role == CompilerBackendRole.ExpressionExact => key

  private def retireMappedValues(
      fileKey: FileKey,
      expectedRevision: Long,
      entries: Set[ElementKey],
      slots: Set[ElementKey]
  ): Unit =
    retireValuesWhen(fileKey, entries, slots): () =>
      Option.when(Option(files.get(fileKey)).exists(_.revision == expectedRevision))(Set.empty)

  private def retireRemovedState(
      fileKey: FileKey,
      entries: Set[ElementKey],
      slots: Set[ElementKey]
  ): Unit =
    retireValuesWhen(fileKey, entries, slots): () =>
      Some(Option(files.get(fileKey)).map(ownedCompilerTypeSlots).getOrElse(Set.empty))

  private def retireValuesWhen(
      fileKey: FileKey,
      entries: Set[ElementKey],
      slots: Set[ElementKey]
  )(protectedSlots: () => Option[Set[ElementKey]]): Unit =
    if (entries.nonEmpty || slots.nonEmpty) && !project.isDisposed then
      val clear = () =>
        if !project.isDisposed then
          withFileMutation(fileKey):
            protectedSlots().foreach: ownedSlots =>
              currentPsiFile(fileKey.fileUrl).foreach: file =>
                val mapped     = entries.flatMap(findElement(file, _))
                val cleared    = (slots -- ownedSlots)
                  .flatMap(findElement(file, _))
                  .filter: element =>
                    val present = BundledPluginBridge.getCompilerType(element) != null
                    if present then BundledPluginBridge.clearCompilerType(element)
                    present
                val unresolved = entries.exists(findElement(file, _).isEmpty)
                invalidate(mapped ++ cleared ++ Option.when(unresolved)(file))
      val app   = ApplicationManager.getApplication
      if app.isDispatchThread then clear()
      else
        val expired: Condition[Any] = _ => project.isDisposed
        app.invokeLater(() => clear(), expired)

  private def withFileMutation[A](fileKey: FileKey)(body: => A): A =
    mutationLocks(Math.floorMod(fileKey.hashCode(), mutationLocks.length)).synchronized(body)

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
