package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.{ParserSyntaxSnapshot, Scala3ParserCompilerIdentity}
import com.hmemcpy.metallurgy.status.MetallurgyStatus
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.{VFileDeleteEvent, VFileEvent}
import com.intellij.openapi.vfs.{VfsUtilCore, VirtualFile, VirtualFileManager}

import java.util.HashMap
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

private[metallurgy] enum Scala3SyntaxCapabilityStage:
  case Module, Preparation, Parser, Evidence, RuntimeInventory, AggregateInventory, Catalog, Planner, Lexer, Emitter

private[metallurgy] final case class Scala3SyntaxCapabilityFailure(
    sourceDigest: String,
    stage: Scala3SyntaxCapabilityStage,
    detail: String,
    preparationEpoch: ParserPreparationEpoch,
    compilerIdentity: Option[Scala3ParserCompilerIdentity]
)

@Service(Array(Service.Level.PROJECT))
private[metallurgy] final class Scala3SyntaxCapabilityService(project: Project):
  private final case class Entry(module: Option[Module], failure: Scala3SyntaxCapabilityFailure)

  private val failures = new HashMap[VirtualFile, Entry]

  project.getMessageBus
    .connect(project)
    .subscribe(
      VirtualFileManager.VFS_CHANGES,
      new BulkFileListener:
        override def after(events: java.util.List[? <: VFileEvent]): Unit =
          events.asScala.collect { case event: VFileDeleteEvent => event.getFile }.foreach(clearInvalid)
    )

  def publish(file: VirtualFile, failure: Scala3SyntaxCapabilityFailure): Unit = synchronized:
    publish(file, Option(ProjectFileIndex.getInstance(project).getModuleForFile(file)), failure)

  def publish(file: VirtualFile, module: Option[Module], failure: Scala3SyntaxCapabilityFailure): Unit = synchronized:
    if isCurrent(file, module, failure) then
      val _ = failures.put(file, Entry(module, failure))
      MetallurgyStatus.publish(
        project,
        MetallurgyStatus.SyntaxUnavailable(
          file,
          failure.stage.toString,
          failure.detail,
          failure.compilerIdentity.map(_.toString)
        )
      )

  def resolve(
      file: VirtualFile,
      sourceDigest: String,
      preparationEpoch: ParserPreparationEpoch,
      compilerIdentity: Scala3ParserCompilerIdentity
  ): Unit = synchronized:
    Option(failures.get(file)) match
      case Some(Entry(_, failure))
          if failure.sourceDigest == sourceDigest && failure.preparationEpoch == preparationEpoch &&
            failure.compilerIdentity.forall(_ == compilerIdentity) =>
        val _ = failures.remove(file)
        MetallurgyStatus.publish(project, MetallurgyStatus.SyntaxAvailable(file))
      case _ => ()

  def discard(files: Iterable[VirtualFile]): Unit = synchronized:
    files.foreach: file =>
      val _ = failures.remove(file)

  def discard(module: Module): Unit = synchronized:
    val files = failures
      .entrySet()
      .asScala
      .collect:
        case entry if entry.getValue.module.contains(module) => entry.getKey
    discard(files)

  def currentFailures: Vector[MetallurgyStatus.SyntaxUnavailable] = synchronized:
    clearInvalidEntries()
    failures
      .entrySet()
      .asScala
      .toVector
      .map(entry => status(entry.getKey, entry.getValue.failure))
      .sortBy(_.file.getPresentableUrl)

  def failureFor(file: VirtualFile, sourceDigest: String): Option[Scala3SyntaxCapabilityFailure] = synchronized:
    Option(failures.get(file))
      .filter(entry => isCurrent(file, entry.module, entry.failure))
      .map(_.failure)
      .filter(_.sourceDigest == sourceDigest)

  private def clearInvalid(deleted: VirtualFile): Unit = synchronized:
    val _ = failures.keySet().removeIf(file => file == deleted || !file.isValid)

  private def clearInvalidEntries(): Unit =
    val _ =
      failures.entrySet().removeIf(entry => !isCurrent(entry.getKey, entry.getValue.module, entry.getValue.failure))

  private def isCurrent(
      file: VirtualFile,
      module: Option[Module],
      failure: Scala3SyntaxCapabilityFailure
  ): Boolean =
    file.isValid && currentDigest(file).contains(failure.sourceDigest) && module.forall(value =>
      Scala3ParserPreparationLifecycle.get(project).stateFor(value).currentEpoch == failure.preparationEpoch
    )

  private def currentDigest(file: VirtualFile): Option[String] =
    try
      Option(FileDocumentManager.getInstance.getDocument(file))
        .map(document => ParserSyntaxSnapshot.digest(document.getImmutableCharSequence.toString))
        .orElse(Option.when(file.isValid)(ParserSyntaxSnapshot.digest(VfsUtilCore.loadText(file))))
    catch case NonFatal(_) => None

  private def status(file: VirtualFile, failure: Scala3SyntaxCapabilityFailure): MetallurgyStatus.SyntaxUnavailable =
    MetallurgyStatus.SyntaxUnavailable(
      file,
      failure.stage.toString,
      failure.detail,
      failure.compilerIdentity.map(_.toString)
    )

private[metallurgy] object Scala3SyntaxCapabilityService:
  def get(project: Project): Scala3SyntaxCapabilityService = project.getService(classOf[Scala3SyntaxCapabilityService])
