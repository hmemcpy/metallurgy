package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.build.ScalacFlagsService
import com.hmemcpy.metallurgy.module.{BundledPluginBridge, ModuleDetectionService}
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.{Module, ModuleUtilCore}
import com.intellij.openapi.project.{ModuleListener, Project}
import com.intellij.openapi.roots.{ModuleRootEvent, ModuleRootListener, OrderEnumerator}
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap}
import scala.jdk.CollectionConverters.*

/** Project-level owner of the current presentation-compiler session for each opted-in module. */
final class PcSessionManager private[pc] (project: Project, fetcher: MtagsFetcher) extends Disposable:

  def this(project: Project) = this(project, MtagsFetcher(project))

  private val log      = Logger.getInstance(classOf[PcSessionManager])
  private val sessions = new ConcurrentHashMap[Module, SessionEntry]()
  private val inFlight = new ConcurrentHashMap[Module, CompletableFuture[Void]]()

  locally:
    val connection = project.getMessageBus.connect(this)
    connection.subscribe(
      ModuleRootListener.TOPIC,
      new ModuleRootListener:
        override def rootsChanged(event: ModuleRootEvent): Unit =
          sessions.keySet().asScala.foreach(discard)
    )
    connection.subscribe(
      ModuleListener.TOPIC,
      new ModuleListener:
        override def beforeModuleRemoved(project: Project, module: Module): Unit =
          discard(module)
    )

  def sessionFor(module: Module): Option[PcSession] =
    if !isManaged(module) then
      discard(module)
      None
    else
      Option(BundledPluginBridge.getScalaVersion(module)).flatMap: scalaVersion =>
        Option(sessions.get(module))
          .filter(_.scalaVersion == scalaVersion)
          .map(_.session)
          .orElse(prepareSession(module, scalaVersion))

  def sessionForFile(file: VirtualFile): Option[PcSession] =
    Option(ModuleUtilCore.findModuleForFile(file, project)).flatMap(sessionFor)

  def discard(module: Module): Unit =
    Option(sessions.remove(module)).foreach: entry =>
      if applicationIsDispatchThread then AppExecutorUtil.getAppExecutorService.execute(() => entry.session.close())
      else entry.session.close()

  /** Compatibility for existing call sites; new code should use [[discard]]. */
  def invalidate(module: Module): Unit = discard(module)

  private[metallurgy] def activeSessionCount: Int = sessions.size()

  override def dispose(): Unit =
    inFlight.values().asScala.foreach(_.cancel(true))
    inFlight.clear()
    sessions.values().asScala.foreach(_.session.close())
    sessions.clear()

  private def isManaged(module: Module): Boolean =
    !module.isDisposed &&
      ModuleDetectionService.get(project).isEligible(module) &&
      MetallurgySettings(project).isEnabled(module)

  private def prepareSession(module: Module, scalaVersion: String): Option[PcSession] =
    fetcher.jarsIfCached(scalaVersion) match
      case None    =>
        fetcher
          .jarsFor(scalaVersion)
          .whenComplete: (_, error) =>
            if error != null then log.warn(s"Could not prepare the Scala $scalaVersion presentation compiler", error)
        None
      case Some(_) =>
        if applicationIsDispatchThread then
          scheduleCreation(module, scalaVersion)
          None
        else ensureSession(module, scalaVersion)

  private def scheduleCreation(module: Module, scalaVersion: String): Unit =
    val future = inFlight.computeIfAbsent(
      module,
      _ =>
        CompletableFuture.runAsync(
          () =>
            ensureSession(module, scalaVersion)
            ()
          ,
          AppExecutorUtil.getAppExecutorService
        )
    )
    future.whenComplete: (_, error) =>
      inFlight.remove(module, future)
      if error != null then log.warn(s"Could not create a presentation compiler for ${module.getName}", error)

  private def ensureSession(module: Module, scalaVersion: String): Option[PcSession] =
    Option.when(isManaged(module)):
      ScalacFlagsService.get(project).enableFor(module)
      val classpath     = buildClasspath(module)
      val classpathHash = hashClasspath(classpath)
      val compilerFlags = ScalacFlagsService.get(project).compilerOptions(module)
      val updated       = sessions.compute(
        module,
        (_, existing) =>
          if existing != null &&
            existing.scalaVersion == scalaVersion &&
            existing.classpathHash == classpathHash &&
            existing.compilerOptions == compilerFlags
          then existing
          else
            Option(existing).foreach(_.session.close())
            SessionEntry(
              scalaVersion,
              classpathHash,
              compilerFlags,
              PcSession.create(scalaVersion, classpath, compilerFlags, fetcher)
            )
      )
      updated.session

  private def buildClasspath(module: Module): Seq[File] =
    OrderEnumerator
      .orderEntries(module)
      .recursively
      .compileOnly
      .withoutSdk
      .classes
      .getPathsList
      .getPathList
      .asScala
      .map(new File(_))
      .distinct
      .toSeq

  private def hashClasspath(classpath: Seq[File]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    classpath
      .sortBy(_.getAbsolutePath)
      .foreach: file =>
        val identity = s"${file.getAbsolutePath}\u0000${file.length()}\u0000${file.lastModified()}\n"
        digest.update(identity.getBytes(StandardCharsets.UTF_8))
    digest.digest().map(byte => f"${byte & 0xff}%02x").mkString

  private def applicationIsDispatchThread: Boolean =
    Option(ApplicationManager.getApplication).exists(_.isDispatchThread)

private final case class SessionEntry(
    scalaVersion: String,
    classpathHash: String,
    compilerOptions: Seq[String],
    session: PcSession
)

object PcSessionManager:
  def get(project: Project): PcSessionManager = project.getService(classOf[PcSessionManager])
