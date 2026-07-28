package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge
import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.hmemcpy.metallurgy.pc.{ExactScala3ParserPreparation, Scala3ParserBridge}
import com.intellij.lang.LanguageUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.{Module, ModuleManager}
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.{ContentIterator, ModuleRootEvent, ModuleRootListener, ModuleRootManager}
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.LanguageSubstitutors
import com.intellij.util.FileContentUtilCore
import com.intellij.util.concurrency.AppExecutorUtil

import java.util.IdentityHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final class Scala3ParserPreparationLifecycle private[psiproducer] (
    project: Project,
    preparer: Scala3ParserPreparer,
    fileCollector: Scala3ModuleFileCollector,
    activation: Scala3ParserActivation
) extends Disposable:

  def this(project: Project) =
    this(
      project,
      ExactScala3ParserPreparer,
      ProjectScala3ModuleFileCollector,
      PlatformScala3ParserActivation(project)
    )

  private val log       = Logger.getInstance(classOf[Scala3ParserPreparationLifecycle])
  private val nextEpoch = new AtomicLong(0L)
  private val entries   = new IdentityHashMap[Module, ParserPreparationEntry]()
  private var disposed  = false

  locally:
    val connection = project.getMessageBus.connect(this)
    connection.subscribe(
      ModuleRootListener.TOPIC,
      new ModuleRootListener:
        override def rootsChanged(event: ModuleRootEvent): Unit =
          if !project.isDisposed then
            val detection = ModuleDetectionService.get(project)
            ModuleManager
              .getInstance(project)
              .getModules
              .filter(detection.isActive)
              .foreach(prepare)
    )
    connection.subscribe(
      com.intellij.openapi.project.ModuleListener.TOPIC,
      new com.intellij.openapi.project.ModuleListener:
        override def beforeModuleRemoved(project: Project, module: Module): Unit =
          retire(module).foreach(_.close())
    )

  def ensurePreparing(module: Module): ParserPreparationEpoch =
    val start = synchronized:
      if disposed then return ParserPreparationEpoch.Disposed
      Option(entries.get(module)) match
        case Some(entry) => return entry.state.currentEpoch
        case None        =>
          val epoch = ParserPreparationEpoch(nextEpoch.incrementAndGet())
          entries.put(module, ParserPreparationEntry(ParserPreparationState.Preparing(epoch), None))
          Some(epoch)
    start.foreach(startPreparation(module, _))
    start.get

  def prepare(module: Module): ParserPreparationEpoch =
    val (epoch, previous) = synchronized:
      if disposed then return ParserPreparationEpoch.Disposed
      val epoch    = ParserPreparationEpoch(nextEpoch.incrementAndGet())
      val previous = Option(entries.put(module, ParserPreparationEntry(ParserPreparationState.Preparing(epoch), None)))
      epoch -> previous
    previous match
      case Some(ParserPreparationEntry(ParserPreparationState.Ready(_), retired)) =>
        queuePendingTransition(module, epoch, retired)
      case entry                                                                  =>
        entry.flatMap(_.bridge).foreach(_.close())
        startPreparation(module, epoch)
    epoch

  def deactivate(module: Module): Unit =
    val retired = retire(module)
    val files   = fileCollector.filesFor(module)
    val close   = () => retired.foreach(_.close())
    activation.queue(files, () => stateFor(module) == ParserPreparationState.Inactive, close, close)

  private def retire(module: Module): Option[Scala3ParserBridge] = synchronized:
    Option(entries.remove(module)).flatMap(_.bridge)

  private def queuePendingTransition(
      module: Module,
      epoch: ParserPreparationEpoch,
      retired: Option[Scala3ParserBridge]
  ): Unit =
    val files    =
      try fileCollector.filesFor(module)
      catch
        case NonFatal(error) =>
          retired.foreach(_.close())
          publishUnavailable(module, epoch, failureMessage(error))
          return
    val continue = () =>
      retired.foreach(_.close())
      if isPreparing(module, epoch) then startPreparation(module, epoch)
    activation.queue(
      files,
      () => isPreparing(module, epoch),
      continue,
      () => retired.foreach(_.close())
    )

  def stateFor(module: Module): ParserPreparationState = synchronized:
    if disposed then ParserPreparationState.Disposed
    else Option(entries.get(module)).map(_.state).getOrElse(ParserPreparationState.Inactive)

  def languageFor(module: Module) =
    stateFor(module) match
      case ParserPreparationState.Activating(_) | ParserPreparationState.Ready(_) =>
        Scala3DotcLanguage.INSTANCE
      case _                                                                      =>
        Scala3ParserPendingLanguage.INSTANCE

  private[metallurgy] def parserFor(module: Module): Option[Scala3ParserBridge] = synchronized:
    Option(entries.get(module)).collect:
      case ParserPreparationEntry(
            ParserPreparationState.Activating(_) | ParserPreparationState.Ready(_),
            Some(bridge)
          ) =>
        bridge

  private def startPreparation(module: Module, epoch: ParserPreparationEpoch): Unit =
    val _ = preparer
      .prepare(module)
      .whenComplete: (result, failure) =>
        if failure != null then publishUnavailable(module, epoch, failureMessage(failure))
        else
          result match
            case Left(message) => publishUnavailable(module, epoch, message)
            case Right(bridge) => publishActivating(module, epoch, bridge)

  private def publishUnavailable(module: Module, epoch: ParserPreparationEpoch, message: String): Unit =
    val published = synchronized:
      Option(entries.get(module)) match
        case Some(ParserPreparationEntry(ParserPreparationState.Preparing(current), None)) if current == epoch =>
          entries.put(
            module,
            ParserPreparationEntry(ParserPreparationState.Unavailable(epoch, message), None)
          )
          true
        case _                                                                                                 => false
    if published then log.warn(s"Exact Scala 3 parser unavailable for ${module.getName}: $message")

  private def publishActivating(
      module: Module,
      epoch: ParserPreparationEpoch,
      bridge: Scala3ParserBridge
  ): Unit =
    val published = synchronized:
      Option(entries.get(module)) match
        case Some(ParserPreparationEntry(ParserPreparationState.Preparing(current), None)) if current == epoch =>
          entries.put(
            module,
            ParserPreparationEntry(ParserPreparationState.Activating(epoch), Some(bridge))
          )
          true
        case _                                                                                                 => false
    if !published then bridge.close()
    else
      val files =
        try fileCollector.filesFor(module)
        catch
          case NonFatal(error) =>
            failActivation(module, epoch, failureMessage(error))
            return
      activation.queue(
        files,
        () => isActivating(module, epoch),
        () => publishReady(module, epoch),
        () => bridge.close()
      )

  private def failActivation(module: Module, epoch: ParserPreparationEpoch, message: String): Unit =
    val retired = synchronized:
      Option(entries.get(module)) match
        case Some(ParserPreparationEntry(ParserPreparationState.Activating(current), bridge)) if current == epoch =>
          entries.put(
            module,
            ParserPreparationEntry(ParserPreparationState.Unavailable(epoch, message), None)
          )
          bridge
        case _                                                                                                    => None
    retired.foreach(_.close())
    if retired.nonEmpty then log.warn(s"Exact Scala 3 parser activation failed for ${module.getName}: $message")

  private def isActivating(module: Module, epoch: ParserPreparationEpoch): Boolean = synchronized:
    Option(entries.get(module)).exists:
      case ParserPreparationEntry(ParserPreparationState.Activating(current), Some(_)) => current == epoch
      case _                                                                           => false

  private def isPreparing(module: Module, epoch: ParserPreparationEpoch): Boolean = synchronized:
    Option(entries.get(module)).contains(
      ParserPreparationEntry(ParserPreparationState.Preparing(epoch), None)
    )

  private def publishReady(module: Module, epoch: ParserPreparationEpoch): Unit = synchronized:
    Option(entries.get(module)) match
      case Some(ParserPreparationEntry(ParserPreparationState.Activating(current), bridge)) if current == epoch =>
        val _ = entries.put(module, ParserPreparationEntry(ParserPreparationState.Ready(epoch), bridge))
      case _                                                                                                    => ()

  private def failureMessage(error: Throwable): String =
    val cause = Iterator.iterate(error)(_.getCause).takeWhile(_ != null).toSeq.lastOption.getOrElse(error)
    Option(cause.getMessage).filter(_.nonEmpty).getOrElse(cause.getClass.getName)

  override def dispose(): Unit =
    val bridges = synchronized:
      disposed = true
      val current = entries.values().asScala.flatMap(_.bridge).toVector
      entries.clear()
      current
    bridges.foreach(_.close())

object Scala3ParserPreparationLifecycle:
  def get(project: Project): Scala3ParserPreparationLifecycle =
    project.getService(classOf[Scala3ParserPreparationLifecycle])

private[metallurgy] opaque type ParserPreparationEpoch = Long

private[metallurgy] object ParserPreparationEpoch:
  val Disposed: ParserPreparationEpoch = 0L

  def apply(value: Long): ParserPreparationEpoch =
    require(value > 0L, s"parser preparation epoch must be positive: $value")
    value

  extension (epoch: ParserPreparationEpoch) def value: Long = epoch

private[metallurgy] enum ParserPreparationState:
  case Inactive
  case Preparing(epoch: ParserPreparationEpoch)
  case Activating(epoch: ParserPreparationEpoch)
  case Ready(epoch: ParserPreparationEpoch)
  case Unavailable(epoch: ParserPreparationEpoch, detail: String)
  case Disposed

  def currentEpoch: ParserPreparationEpoch =
    this match
      case Inactive              => ParserPreparationEpoch.Disposed
      case Preparing(epoch)      => epoch
      case Activating(epoch)     => epoch
      case Ready(epoch)          => epoch
      case Unavailable(epoch, _) => epoch
      case Disposed              => ParserPreparationEpoch.Disposed

private final case class ParserPreparationEntry(
    state: ParserPreparationState,
    bridge: Option[Scala3ParserBridge]
)

private[psiproducer] trait Scala3ParserPreparer:
  def prepare(module: Module): CompletableFuture[Either[String, Scala3ParserBridge]]

private object ExactScala3ParserPreparer extends Scala3ParserPreparer:
  override def prepare(module: Module): CompletableFuture[Either[String, Scala3ParserBridge]] =
    Option(ScalaPluginSemanticBridge.getScalaVersion(module)) match
      case None               => CompletableFuture.completedFuture(Left("Scala compiler coordinate is not available"))
      case Some(scalaVersion) =>
        CompletableFuture.supplyAsync(
          () => ExactScala3ParserPreparation.open(scalaVersion),
          AppExecutorUtil.getAppExecutorService
        )

private[psiproducer] trait Scala3ModuleFileCollector:
  def filesFor(module: Module): Vector[VirtualFile]

private object ProjectScala3ModuleFileCollector extends Scala3ModuleFileCollector:
  private val sourceExtensions = Set("scala", "sc", "sbt", "mill")

  override def filesFor(module: Module): Vector[VirtualFile] =
    val collect     = () =>
      val files = Vector.newBuilder[VirtualFile]
      val _     = ModuleRootManager
        .getInstance(module)
        .getFileIndex
        .iterateContent(
          new ContentIterator:
            override def processFile(file: VirtualFile): Boolean =
              if !file.isDirectory && sourceExtensions.contains(file.getExtension) then files += file
              true
        )
      files.result().distinct.sortBy(_.getUrl)
    val application = ApplicationManager.getApplication
    if application.isReadAccessAllowed then collect()
    else
      application.runReadAction(
        new Computable[Vector[VirtualFile]]:
          override def compute(): Vector[VirtualFile] = collect()
      )

private[psiproducer] trait Scala3ParserActivation:
  def queue(
      files: Vector[VirtualFile],
      isCurrent: () => Boolean,
      onApplied: () => Unit,
      onDiscarded: () => Unit
  ): Unit

private final class PlatformScala3ParserActivation(project: Project) extends Scala3ParserActivation:
  override def queue(
      files: Vector[VirtualFile],
      isCurrent: () => Boolean,
      onApplied: () => Unit,
      onDiscarded: () => Unit
  ): Unit =
    ApplicationManager.getApplication.invokeLater: () =>
      if isCurrent() && !project.isDisposed then
        files.foreach: file =>
          val _ = LanguageUtil.getLanguageForPsi(project, file)
          LanguageSubstitutors.cancelReparsing(file)
        if isCurrent() then
          FileContentUtilCore.reparseFiles(files.asJava)
          onApplied()
        else onDiscarded()
      else onDiscarded()
