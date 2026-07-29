package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge
import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.hmemcpy.metallurgy.pc.{ExactScala3ParserPreparation, Scala3ParserBridge}
import com.intellij.lang.LanguageUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.ControlFlowException
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
import java.util.concurrent.Executor
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final class Scala3ParserPreparationLifecycle private[psiproducer] (
    project: Project,
    preparer: Scala3ParserPreparer,
    fileCollector: Scala3ModuleFileCollector,
    activation: Scala3ParserActivation,
    catalogPreparer: Scala3PsiCatalogPreparer = NativeScala3PsiCatalogPreparer,
    preparationExecutor: Executor = AppExecutorUtil.getAppExecutorService
) extends Disposable:

  def this(project: Project) =
    this(
      project,
      ExactScala3ParserPreparer,
      ProjectScala3ModuleFileCollector,
      PlatformScala3ParserActivation(project),
      NativeScala3PsiCatalogPreparer,
      AppExecutorUtil.getAppExecutorService
    )

  private val log            = Logger.getInstance(classOf[Scala3ParserPreparationLifecycle])
  private val nextEpoch      = new AtomicLong(0L)
  private val entries        = new IdentityHashMap[Module, ParserPreparationEntry]()
  private val pendingClosers = new IdentityHashMap[Module, Vector[() => Unit]]()
  private var disposed       = false

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
          retire(module).foreach(_.bridge.close())
          closePending(module)
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
      val previous = Option(entries.get(module))
      val state    = previous.map(_.state) match
        case Some(ParserPreparationState.Ready(_) | ParserPreparationState.Neutralizing(_)) =>
          ParserPreparationState.Neutralizing(epoch)
        case _                                                                              =>
          ParserPreparationState.Preparing(epoch)
      entries.put(module, ParserPreparationEntry(state, None))
      epoch -> previous
    previous match
      case Some(ParserPreparationEntry(ParserPreparationState.Ready(_), retired))  =>
        queuePendingTransition(module, retired)
      case Some(ParserPreparationEntry(ParserPreparationState.Neutralizing(_), _)) => ()
      case entry                                                                   =>
        entry.flatMap(_.prepared).foreach(_.bridge.close())
        startPreparation(module, epoch)
    epoch

  def deactivate(module: Module): Unit =
    val retired = retire(module)
    closePending(module)
    val close   = trackedCloseOnce(module, retired)
    try
      val files = fileCollector.filesFor(module)
      activation.queue(
        files,
        () => stateFor(module) == ParserPreparationState.Inactive,
        close,
        close,
        error =>
          close()
          controlFlowCause(error).foreach(throw _)
      )
    catch
      case control: ControlFlowException =>
        close()
        throw control
      case NonFatal(error) =>
        close()
        rethrowControlFlow(error)(())
        log.warn(s"Exact Scala 3 parser deactivation failed for ${module.getName}: ${failureMessage(error)}")

  private def retire(module: Module): Option[PreparedScala3Parser] = synchronized:
    Option(entries.remove(module)).flatMap(_.prepared)

  private def queuePendingTransition(
      module: Module,
      retired: Option[PreparedScala3Parser]
  ): Unit =
    val close    = trackedCloseOnce(module, retired)
    val files    =
      try fileCollector.filesFor(module)
      catch
        case control: ControlFlowException =>
          close()
          cancelNeutralizing(module)
          throw control
        case NonFatal(error)               =>
          close()
          rethrowControlFlow(error)(cancelNeutralizing(module))
          failNeutralizing(module, failureMessage(error))
          return
    val continue = () =>
      close()
      beginPreparationAfterNeutral(module).foreach(startPreparation(module, _))
    try
      activation.queue(
        files,
        () => isNeutralizing(module),
        continue,
        close,
        error =>
          close()
          controlFlowCause(error) match
            case Some(control) =>
              cancelNeutralizing(module)
              throw control
            case None          => failNeutralizing(module, failureMessage(error))
      )
    catch
      case control: ControlFlowException =>
        close()
        cancelNeutralizing(module)
        throw control
      case NonFatal(error) =>
        close()
        rethrowControlFlow(error)(cancelNeutralizing(module))
        failNeutralizing(module, failureMessage(error))

  private def isNeutralizing(module: Module): Boolean = synchronized:
    Option(entries.get(module)).exists(_.state.isInstanceOf[ParserPreparationState.Neutralizing])

  private def beginPreparationAfterNeutral(module: Module): Option[ParserPreparationEpoch] = synchronized:
    Option(entries.get(module)) match
      case Some(ParserPreparationEntry(ParserPreparationState.Neutralizing(epoch), None)) =>
        entries.put(module, ParserPreparationEntry(ParserPreparationState.Preparing(epoch), None))
        Some(epoch)
      case _                                                                              => None

  private def cancelNeutralizing(module: Module): Unit = synchronized:
    Option(entries.get(module)) match
      case Some(ParserPreparationEntry(ParserPreparationState.Neutralizing(_), None)) =>
        val _ = entries.remove(module)
      case _                                                                          => ()

  private def failNeutralizing(module: Module, message: String): Unit =
    val epoch = synchronized:
      Option(entries.get(module)) match
        case Some(ParserPreparationEntry(ParserPreparationState.Neutralizing(current), None)) =>
          entries.put(
            module,
            ParserPreparationEntry(ParserPreparationState.Unavailable(current, message), None)
          )
          Some(current)
        case _                                                                                => None
    if epoch.nonEmpty then log.warn(s"Exact Scala 3 parser unavailable for ${module.getName}: $message")

  def stateFor(module: Module): ParserPreparationState = synchronized:
    if disposed then ParserPreparationState.Disposed
    else Option(entries.get(module)).map(_.state).getOrElse(ParserPreparationState.Inactive)

  def languageFor(module: Module) =
    stateFor(module) match
      case ParserPreparationState.Activating(_) | ParserPreparationState.Ready(_) =>
        Scala3DotcLanguage.INSTANCE
      case _                                                                      =>
        Scala3ParserPendingLanguage.INSTANCE

  private[metallurgy] def parserFor(module: Module): Option[PreparedScala3Parser] = synchronized:
    Option(entries.get(module)).collect:
      case ParserPreparationEntry(
            ParserPreparationState.Activating(_) | ParserPreparationState.Ready(_),
            Some(prepared)
          ) =>
        prepared

  private def startPreparation(module: Module, epoch: ParserPreparationEpoch): Unit =
    try
      val _ = preparer
        .prepare(module)
        .whenComplete: (result, failure) =>
          var started = false
          try
            preparationExecutor.execute: () =>
              started = true
              completePreparation(module, epoch, result, failure)
          catch
            case control: ControlFlowException =>
              if !started then
                closeCompletedBridge(result)
                cancelPreparation(module, epoch)
              throw control
            case NonFatal(error)               =>
              if started then rethrowControlFlow(error)(())
              else
                closeCompletedBridge(result)
                rethrowControlFlow(error)(cancelPreparation(module, epoch))
                publishUnavailable(module, epoch, failureMessage(error))
    catch
      case control: ControlFlowException =>
        cancelPreparation(module, epoch)
        throw control
      case NonFatal(error)               =>
        rethrowControlFlow(error)(cancelPreparation(module, epoch))
        publishUnavailable(module, epoch, failureMessage(error))

  private def closeCompletedBridge(result: Either[String, Scala3ParserBridge]): Unit =
    Option(result).foreach(_.foreach(_.close()))

  private def completePreparation(
      module: Module,
      epoch: ParserPreparationEpoch,
      result: Either[String, Scala3ParserBridge],
      failure: Throwable
  ): Unit =
    if failure != null then
      controlFlowCause(failure) match
        case Some(control) =>
          cancelPreparation(module, epoch)
          throw control
        case None          => publishUnavailable(module, epoch, failureMessage(failure))
    else
      result match
        case Left(message) => publishUnavailable(module, epoch, message)
        case Right(bridge) =>
          if !isPreparing(module, epoch) then bridge.close()
          else
            val catalog =
              try catalogPreparer.prepare(project)
              catch
                case control: ControlFlowException =>
                  bridge.close()
                  cancelPreparation(module, epoch)
                  throw control
                case NonFatal(error)               =>
                  bridge.close()
                  rethrowControlFlow(error)(cancelPreparation(module, epoch))
                  publishUnavailable(module, epoch, failureMessage(error))
                  return
            catalog match
              case Left(message)  =>
                bridge.close()
                publishUnavailable(module, epoch, message)
              case Right(catalog) => publishActivating(module, epoch, PreparedScala3Parser(bridge, catalog))

  private def cancelPreparation(module: Module, epoch: ParserPreparationEpoch): Unit = synchronized:
    Option(entries.get(module)) match
      case Some(ParserPreparationEntry(ParserPreparationState.Preparing(current), None)) if current == epoch =>
        val _ = entries.remove(module)
      case _                                                                                                 => ()

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
      prepared: PreparedScala3Parser
  ): Unit =
    val published = synchronized:
      Option(entries.get(module)) match
        case Some(ParserPreparationEntry(ParserPreparationState.Preparing(current), None)) if current == epoch =>
          entries.put(
            module,
            ParserPreparationEntry(ParserPreparationState.Activating(epoch), Some(prepared))
          )
          true
        case _                                                                                                 => false
    if !published then prepared.bridge.close()
    else
      val files =
        try fileCollector.filesFor(module)
        catch
          case control: ControlFlowException =>
            discardActivation(module, epoch)
            throw control
          case NonFatal(error)               =>
            rethrowControlFlow(error)(discardActivation(module, epoch))
            failActivation(module, epoch, failureMessage(error))
            return
      try
        activation.queue(
          files,
          () => isActivating(module, epoch),
          () => publishReady(module, epoch),
          () => discardActivation(module, epoch),
          error => handleActivationFailure(module, epoch, error)
        )
      catch
        case control: ControlFlowException =>
          discardActivation(module, epoch)
          throw control
        case NonFatal(error) =>
          rethrowControlFlow(error)(discardActivation(module, epoch))
          failActivation(module, epoch, failureMessage(error))

  private def handleActivationFailure(module: Module, epoch: ParserPreparationEpoch, error: Throwable): Unit =
    controlFlowCause(error) match
      case Some(control) =>
        discardActivation(module, epoch)
        throw control
      case None          => failActivation(module, epoch, failureMessage(error))

  private def discardActivation(module: Module, epoch: ParserPreparationEpoch): Unit =
    val retired = synchronized:
      Option(entries.get(module)) match
        case Some(ParserPreparationEntry(ParserPreparationState.Activating(current), prepared)) if current == epoch =>
          entries.remove(module)
          prepared
        case _                                                                                                      => None
    retired.foreach(_.bridge.close())

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
    retired.foreach(_.bridge.close())
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

  private def controlFlowCause(error: Throwable): Option[Throwable & ControlFlowException] =
    Iterator
      .iterate(error)(_.getCause)
      .takeWhile(_ != null)
      .collectFirst:
        case control: ControlFlowException => control

  private def rethrowControlFlow(error: Throwable)(cleanup: => Unit): Unit =
    controlFlowCause(error).foreach: control =>
      cleanup
      throw control

  private def trackedCloseOnce(module: Module, prepared: Option[PreparedScala3Parser]): () => Unit =
    val closed                 = new AtomicBoolean(false)
    lazy val close: () => Unit = () =>
      if closed.compareAndSet(false, true) then
        synchronized:
          Option(pendingClosers.get(module)).foreach: current =>
            val remaining = current.filterNot(_ eq close)
            if remaining.isEmpty then pendingClosers.remove(module)
            else pendingClosers.put(module, remaining)
        prepared.foreach(_.bridge.close())
    synchronized:
      val current = Option(pendingClosers.get(module)).getOrElse(Vector.empty)
      val _       = pendingClosers.put(module, current :+ close)
    close

  private def closePending(module: Module): Unit =
    val closers = synchronized:
      Option(pendingClosers.remove(module)).getOrElse(Vector.empty)
    closers.foreach(_())

  override def dispose(): Unit =
    val (bridges, closers) = synchronized:
      disposed = true
      val current = entries.values().asScala.flatMap(_.prepared).toVector
      val pending = pendingClosers.values().asScala.flatten.toVector
      entries.clear()
      pendingClosers.clear()
      current -> pending
    bridges.foreach(_.bridge.close())
    closers.foreach(_())

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
  case Neutralizing(epoch: ParserPreparationEpoch)
  case Preparing(epoch: ParserPreparationEpoch)
  case Activating(epoch: ParserPreparationEpoch)
  case Ready(epoch: ParserPreparationEpoch)
  case Unavailable(epoch: ParserPreparationEpoch, detail: String)
  case Disposed

  def currentEpoch: ParserPreparationEpoch =
    this match
      case Inactive              => ParserPreparationEpoch.Disposed
      case Neutralizing(epoch)   => epoch
      case Preparing(epoch)      => epoch
      case Activating(epoch)     => epoch
      case Ready(epoch)          => epoch
      case Unavailable(epoch, _) => epoch
      case Disposed              => ParserPreparationEpoch.Disposed

private final case class ParserPreparationEntry(
    state: ParserPreparationState,
    prepared: Option[PreparedScala3Parser]
)

private[metallurgy] final case class PreparedScala3Parser(
    bridge: Scala3ParserBridge,
    catalog: Scala3PsiProductionCatalog
)

private[psiproducer] trait Scala3PsiCatalogPreparer:
  def prepare(project: Project): Either[String, Scala3PsiProductionCatalog]

private[psiproducer] object NativeScala3PsiCatalogPreparer extends Scala3PsiCatalogPreparer:
  override def prepare(project: Project): Either[String, Scala3PsiProductionCatalog] =
    for
      observation <- ScalaPluginSemanticBridge.probeNativeIntegerLiteral(project)
      catalog     <- Scala3PsiProductionCatalog.withNativeIntegerLiteral(observation).left.map(_.toString)
    yield catalog

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
      onDiscarded: () => Unit,
      onFailed: Throwable => Unit
  ): Unit

private final class PlatformScala3ParserActivation(project: Project) extends Scala3ParserActivation:
  override def queue(
      files: Vector[VirtualFile],
      isCurrent: () => Boolean,
      onApplied: () => Unit,
      onDiscarded: () => Unit,
      onFailed: Throwable => Unit
  ): Unit =
    ApplicationManager.getApplication.invokeLater: () =>
      try
        if isCurrent() && !project.isDisposed then
          files.foreach: file =>
            val _ = LanguageUtil.getLanguageForPsi(project, file)
            LanguageSubstitutors.cancelReparsing(file)
          if isCurrent() then
            FileContentUtilCore.reparseFiles(files.asJava)
            onApplied()
          else onDiscarded()
        else onDiscarded()
      catch case error: Throwable => onFailed(error)
