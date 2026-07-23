package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.build.ScalacFlagsService
import com.hmemcpy.metallurgy.compilerbackend.{
  CompilerBackendCommit,
  CompilerBackendGeneration,
  CompilerBackendSnapshotPublisher,
  Scala3CompilerBackend
}
import com.hmemcpy.metallurgy.feature.diagnostics.{PcDiagnosticSetCache, PcHighlightRenderer}
import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge
import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.hmemcpy.metallurgy.projectmodel.{CompilerBackendModelState, CompilerBackendModuleDescriptor}
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.{DocumentEvent, DocumentListener}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.{Module, ModuleUtilCore}
import com.intellij.openapi.project.{ModuleListener, Project}
import com.intellij.openapi.roots.{ModuleRootEvent, ModuleRootListener}
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.{VirtualFile, VirtualFileManager}
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap}
import java.util.concurrent.atomic.AtomicLong
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Project-level owner of the current presentation-compiler session for each active module. */
final class PcSessionManager private[pc] (project: Project, fetcher: MtagsFetcher) extends Disposable:

  def this(project: Project) = this(project, MtagsFetcher(project))

  private val log                        = Logger.getInstance(classOf[PcSessionManager])
  private val sessions                   = new ConcurrentHashMap[Module, SessionEntry]()
  private val inFlight                   = new ConcurrentHashMap[SessionKey, CompletableFuture[Option[PcSession]]]()
  private val availability               = new ConcurrentHashMap[Module, PcBackendAvailability]()
  private val backendInFlight            =
    new ConcurrentHashMap[BackendPreparationKey, CompletableFuture[Option[PcSession]]]()
  private val moduleFiles                = new ConcurrentHashMap[Module, java.util.Set[String]]()
  private val sessionGenerations         = new AtomicLong(0L)
  private val classpathGenerations       = new AtomicLong(0L)
  private val compilerOptionsGenerations = new AtomicLong(0L)
  private val backend                    = Scala3CompilerBackend.get(project)
  private val backendPublisher           = new CompilerBackendSnapshotPublisher(project)
  private val modelRefreshFiles          = ConcurrentHashMap.newKeySet[String]()
  private val modelRefreshAlarm          = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

  private def cache: PcDiagnosticSetCache   = PcDiagnosticSetCache.get(project)
  private def renderer: PcHighlightRenderer = PcHighlightRenderer.get(project)

  locally:
    val connection       = project.getMessageBus.connect(this)
    connection.subscribe(
      ModuleRootListener.TOPIC,
      new ModuleRootListener:
        override def rootsChanged(event: ModuleRootEvent): Unit =
          val modules = managedModules
          modules.foreach: module =>
            Option(moduleFiles.get(module)).foreach:
              _.forEach: url =>
                val _ = modelRefreshFiles.add(url)
            discard(module)
          scheduleModelRefresh()
    )
    connection.subscribe(
      ModuleListener.TOPIC,
      new ModuleListener:
        override def beforeModuleRemoved(project: Project, module: Module): Unit =
          discard(module)
    )
    val documentListener = new DocumentListener:
      override def documentChanged(event: DocumentEvent): Unit =
        scheduleRetypecheck(event)
    EditorFactory.getInstance.getEventMulticaster.addDocumentListener(documentListener, this)

  def sessionFor(module: Module): Option[PcSession] =
    if !isManaged(module) then
      deactivate(module)
      None
    else
      Option(ScalaPluginSemanticBridge.getScalaVersion(module)) match
        case None               =>
          availability.put(module, PcBackendAvailability.Unavailable(PcBackendUnavailableReason.MissingScalaVersion))
          None
        case Some(scalaVersion) =>
          Option(sessions.get(module))
            .filter(_.scalaVersion == scalaVersion)
            .filter(sessionEntryIsCurrent(module, _))
            .map(_.session)
            .orElse(prepareSession(module, scalaVersion))

  /** Returns the current session or prepares one asynchronously, including a cold artifact fetch. */
  def sessionForAsync(module: Module): CompletableFuture[Option[PcSession]] =
    if !isManaged(module) then
      deactivate(module)
      CompletableFuture.completedFuture(None)
    else
      Option(ScalaPluginSemanticBridge.getScalaVersion(module)) match
        case None               =>
          availability.put(module, PcBackendAvailability.Unavailable(PcBackendUnavailableReason.MissingScalaVersion))
          CompletableFuture.completedFuture(None)
        case Some(scalaVersion) =>
          Option(sessions.get(module))
            .filter(_.scalaVersion == scalaVersion)
            .filter(sessionEntryIsCurrent(module, _))
            .map(entry => CompletableFuture.completedFuture(Some(entry.session)))
            .getOrElse(scheduleCreation(module, scalaVersion))

  def sessionForFile(file: VirtualFile): Option[PcSession] =
    filePreparation(file).flatMap: (module, snapshot) =>
      sessionFor(module).map: session =>
        val _ = analyze(session, module, snapshot)
        session

  /** Creates the file's session if necessary and completes after its exact document version has been retypechecked. */
  private[metallurgy] def prepareFile(file: VirtualFile): CompletableFuture[Option[PcSession]] =
    prepareFile(file, awaitBackendPublication = false, retries = 0)

  /** Creates the file's session if necessary and completes after the exact-version compiler-backend snapshot commits.
    */
  private[metallurgy] def prepareCompilerBackend(file: VirtualFile): CompletableFuture[Option[PcSession]] =
    filePreparation(file) match
      case Some((module, snapshot)) if isManaged(module) =>
        val key    = BackendPreparationKey(module, snapshot.fileUri, snapshot.documentVersion)
        val future = backendInFlight.computeIfAbsent(
          key,
          _ => prepareFile(file, awaitBackendPublication = true, retries = 1)
        )
        future.whenComplete: (_, _) =>
          val _ = backendInFlight.remove(key, future)
        future
      case _                                             => CompletableFuture.completedFuture(None)

  private def prepareFile(
      file: VirtualFile,
      awaitBackendPublication: Boolean,
      retries: Int
  ): CompletableFuture[Option[PcSession]] =
    val preparation = filePreparation(file)

    preparation match
      case None                     => CompletableFuture.completedFuture(None)
      case Some((module, snapshot)) =>
        sessionForAsync(module).thenCompose:
          case None          => CompletableFuture.completedFuture(None)
          case Some(session) =>
            val alreadyCommitted =
              awaitBackendPublication && currentGeneration(module, session).exists: generation =>
                backend.hasCommittedSnapshot(module, snapshot.fileUri, snapshot.documentVersion, generation)
            if alreadyCommitted then CompletableFuture.completedFuture(Some(session))
            else
              analyze(session, module, snapshot, awaitBackendPublication)
                .thenCompose:
                  case RetypecheckOutcome.Applied                   =>
                    CompletableFuture.completedFuture(Some(session))
                  case RetypecheckOutcome.Superseded if retries > 0 =>
                    prepareFile(file, awaitBackendPublication, retries - 1)
                  case _                                            => CompletableFuture.completedFuture(None)

  def discard(module: Module): Unit =
    backendInFlight
      .entrySet()
      .asScala
      .filter(_.getKey.module == module)
      .foreach: entry =>
        if backendInFlight.remove(entry.getKey, entry.getValue) then
          val _ = entry.getValue.cancel(true)
    inFlight
      .entrySet()
      .asScala
      .filter(_.getKey.module == module)
      .foreach: entry =>
        if inFlight.remove(entry.getKey, entry.getValue) then
          val _ = entry.getValue.cancel(true)
    Option(moduleFiles.remove(module)).foreach(_.forEach { url =>
      cache.markUnavailable(url)
      renderer.erase(url)
    })
    backend.clear(module)
    availability.remove(module)
    Option(sessions.remove(module)).foreach: entry =>
      if applicationIsDispatchThread then AppExecutorUtil.getAppExecutorService.execute(() => entry.session.close())
      else entry.session.close()

  private def deactivate(module: Module): Unit =
    discard(module)
    ScalacFlagsService.get(project).disableFor(module)

  /** Compatibility for existing call sites; new code should use [[discard]]. */
  def invalidate(module: Module): Unit = discard(module)

  private[metallurgy] def activeSessionCount: Int = sessions.size()

  private[metallurgy] def availabilityEntryCount: Int = availability.size()

  private[metallurgy] def availabilityFor(module: Module): PcBackendAvailability =
    if !isManaged(module) then PcBackendAvailability.Inactive
    else Option(availability.get(module)).getOrElse(PcBackendAvailability.Undiscovered)

  override def dispose(): Unit =
    inFlight.values().asScala.foreach(_.cancel(true))
    inFlight.clear()
    backendInFlight.values().asScala.foreach(_.cancel(true))
    backendInFlight.clear()
    modelRefreshAlarm.cancelAllRequests()
    modelRefreshFiles.clear()
    moduleFiles.clear()
    availability.clear()
    sessions.values().asScala.foreach(_.session.close())
    sessions.clear()

  private def isManaged(module: Module): Boolean =
    !module.isDisposed && ModuleDetectionService.get(project).isActive(module)

  private def managedModules: Set[Module] =
    sessions.keySet().asScala.toSet ++ inFlight.keySet().asScala.map(_.module)

  private def scheduleModelRefresh(): Unit =
    modelRefreshAlarm.cancelAllRequests()
    modelRefreshAlarm.addRequest(() => refreshFilesAfterModelChange(), 100)

  private def refreshFilesAfterModelChange(): Unit =
    val urls = modelRefreshFiles.iterator().asScala.toVector
    urls.foreach(modelRefreshFiles.remove)
    urls.foreach: url =>
      Option(VirtualFileManager.getInstance.findFileByUrl(url)).foreach: file =>
        val schedule: Runnable = () =>
          val module = ModuleUtilCore.findModuleForFile(file, project)
          if module != null && isManaged(module) then
            val _ = prepareCompilerBackend(file)
        ApplicationManager.getApplication.runReadAction(schedule)

  private def scheduleRetypecheck(event: DocumentEvent): Unit =
    for
      file    <- Option(FileDocumentManager.getInstance.getFile(event.getDocument))
      module  <- Option(ModuleUtilCore.findModuleForFile(file, project))
      if isManaged(module) && isScalaSource(file)
      session <- Option(sessions.get(module)).map(_.session)
      snapshot = PcSnapshot(file.getUrl, event.getDocument.getModificationStamp, event.getDocument.getText)
    do analyze(session, module, snapshot, awaitBackendPublication = false)

  private def isScalaSource(file: VirtualFile): Boolean =
    Set("scala", "sc", "sbt", "mill").contains(file.getExtension)

  private def snapshotFor(file: VirtualFile): Option[PcSnapshot] =
    readAction: () =>
      Option(FileDocumentManager.getInstance.getDocument(file)).map: document =>
        PcSnapshot(file.getUrl, document.getModificationStamp, document.getText)

  private def filePreparation(file: VirtualFile): Option[(Module, PcSnapshot)] =
    readAction: () =>
      for
        module   <- Option(ModuleUtilCore.findModuleForFile(file, project))
        snapshot <- snapshotFor(file)
      yield module -> snapshot

  private def readAction[A](computation: Computable[A]): A =
    if ApplicationManager.getApplication.isReadAccessAllowed then computation.compute()
    else ApplicationManager.getApplication.runReadAction(computation)

  /** Schedule a debounced retypecheck and mirror its outcome into the diagnostic-set cache: `Pending` while in flight,
    * `CurrentSuccess`/`Failed` when it settles. A superseded outcome leaves the newer pending state intact (the cache
    * drops it).
    */
  private def analyze(
      session: PcSession,
      module: Module,
      snapshot: PcSnapshot,
      awaitBackendPublication: Boolean = false
  ): CompletableFuture[RetypecheckOutcome] =
    currentGeneration(module, session) match
      case None             => CompletableFuture.completedFuture(RetypecheckOutcome.Superseded)
      case Some(generation) =>
        cache.markPending(snapshot.fileUri, snapshot.documentVersion)
        backend.markPending(module, snapshot.fileUri, snapshot.documentVersion, generation)
        renderer.blank(
          snapshot.fileUri,
          snapshot.documentVersion
        ) // clear the pc layer from the previous version while the new one is in flight
        trackFile(module, snapshot.fileUri)
        val retypecheck = session.scheduleRetypecheck(snapshot)
        val prepared    = retypecheck.thenApply: outcome =>
          outcome -> publishOutcome(session, module, snapshot, generation, outcome)
        prepared.whenComplete: (_, error) =>
          if error != null then
            cache.publishFailed(snapshot.fileUri, snapshot.documentVersion)
            backend.markFailed(module, snapshot.fileUri, snapshot.documentVersion, generation)
        if awaitBackendPublication then
          prepared.thenCompose: (outcome, publication) =>
            publication.thenApply:
              case CompilerBackendCommit.Committed(_) => outcome
              case CompilerBackendCommit.Rejected     => RetypecheckOutcome.Superseded
        else prepared.thenApply(_._1)

  private def publishOutcome(
      session: PcSession,
      module: Module,
      snapshot: PcSnapshot,
      generation: CompilerBackendGeneration,
      outcome: RetypecheckOutcome
  ): CompletableFuture[CompilerBackendCommit] =
    outcome match
      case RetypecheckOutcome.Applied    =>
        val publication = session.typedTreeSnapshot(snapshot) match
          case Some(typedTree) =>
            backendPublisher.publish(module, typedTree, generation, () => snapshotCurrency(module, session, generation))
          case None            =>
            backend.markFailed(module, snapshot.fileUri, snapshot.documentVersion, generation)
            CompletableFuture.completedFuture(CompilerBackendCommit.Rejected)
        session.diagnostics(snapshot) match
          case Some(diagnostics) =>
            cache.publishSuccess(snapshot.fileUri, snapshot.documentVersion, diagnostics)
            renderer.render(snapshot.fileUri, snapshot.documentVersion, diagnostics)
          case None              =>
            cache.publishFailed(snapshot.fileUri, snapshot.documentVersion)
            renderer.blank(snapshot.fileUri, snapshot.documentVersion)
        publication
      case RetypecheckOutcome.Failed(_)  =>
        cache.publishFailed(snapshot.fileUri, snapshot.documentVersion)
        backend.markFailed(module, snapshot.fileUri, snapshot.documentVersion, generation)
        renderer.blank(snapshot.fileUri, snapshot.documentVersion)
        CompletableFuture.completedFuture(CompilerBackendCommit.Rejected)
      case RetypecheckOutcome.Superseded => CompletableFuture.completedFuture(CompilerBackendCommit.Rejected)

  private def trackFile(module: Module, fileUrl: String): Unit =
    val _ = moduleFiles.computeIfAbsent(module, _ => ConcurrentHashMap.newKeySet[String]()).add(fileUrl)

  private def prepareSession(module: Module, scalaVersion: String): Option[PcSession] =
    fetcher.jarsIfCached(scalaVersion) match
      case None    =>
        val _ = scheduleCreation(module, scalaVersion)
        None
      case Some(_) =>
        if applicationIsDispatchThread then
          val _ = scheduleCreation(module, scalaVersion)
          None
        else ensureSession(module, scalaVersion)

  private def scheduleCreation(module: Module, scalaVersion: String): CompletableFuture[Option[PcSession]] =
    val key    = SessionKey(module, scalaVersion)
    val future = inFlight.computeIfAbsent(
      key,
      _ =>
        val _ = availability.put(module, PcBackendAvailability.Pending(scalaVersion))
        fetcher
          .jarsFor(scalaVersion)
          .handleAsync(
            (_, error) =>
              if error != null then
                val detail = failureMessage(error)
                recordUnavailable(
                  module,
                  PcBackendUnavailableReason.ArtifactPreparation(scalaVersion, detail),
                  s"Could not prepare a presentation compiler for ${module.getName}: $detail"
                )
                None
              else
                try ensureSession(module, scalaVersion)
                catch
                  case NonFatal(sessionError) =>
                    val detail = failureMessage(sessionError)
                    recordUnavailable(
                      module,
                      PcBackendUnavailableReason.SessionCreation(scalaVersion, detail),
                      s"Could not create a presentation compiler for ${module.getName}: $detail"
                    )
                    None,
            AppExecutorUtil.getAppExecutorService
          )
    )
    future.whenComplete: (_, _) =>
      val _ = inFlight.remove(key, future)
    future

  private def ensureSession(module: Module, scalaVersion: String): Option[PcSession] =
    if !isManaged(module) then
      val _ = availability.remove(module)
      None
    else
      val descriptor    = readModelDescriptor(module) match
        case CompilerBackendModelState.Ready(value)        => value
        case CompilerBackendModelState.Pending(detail)     =>
          val _ = availability.put(module, PcBackendAvailability.Pending(scalaVersion))
          log.info(s"Compiler backend model is pending for ${module.getName}: $detail")
          return None
        case CompilerBackendModelState.Unavailable(detail) =>
          recordUnavailable(
            module,
            PcBackendUnavailableReason.ProjectModel(detail),
            s"Compiler backend model is unavailable for ${module.getName}: $detail"
          )
          return None
        case CompilerBackendModelState.Inactive            => return None
      val classpath     = PcSessionManager.exposeBestEffortTastyRoots(descriptor.compileClasspath)
      val classpathHash = PcSessionManager.fingerprintClasspath(classpath)
      val compilerFlags = descriptor.compilerOptions
      val updated       = sessions.compute(
        module,
        (_, existing) =>
          if existing != null &&
            existing.scalaVersion == scalaVersion &&
            existing.classpathHash == classpathHash &&
            existing.compilerOptions == compilerFlags
          then existing
          else
            Option(existing).foreach: stale =>
              stale.session.close()
              backend.clear(module)
            val generation = CompilerBackendGeneration(
              session = sessionGenerations.incrementAndGet(),
              classpath =
                if existing != null && existing.classpathHash == classpathHash then existing.generation.classpath
                else classpathGenerations.incrementAndGet(),
              compilerOptions =
                if existing != null && existing.compilerOptions == compilerFlags then
                  existing.generation.compilerOptions
                else compilerOptionsGenerations.incrementAndGet()
            )
            val session    = PcSession.create(scalaVersion, classpath, compilerFlags, fetcher)
            ScalacFlagsService.get(project).enableFor(module, session.capabilities)
            SessionEntry(
              scalaVersion,
              classpathHash,
              session.compilerOptions,
              generation,
              session
            )
      )
      if isManaged(module) then
        availability.put(module, PcBackendAvailability.Available(scalaVersion, updated.session.capabilities))
        Some(updated.session)
      else
        Option(sessions.remove(module)).foreach(_.session.close())
        backend.clear(module)
        ScalacFlagsService.get(project).disableFor(module)
        availability.remove(module)
        None

  private def recordUnavailable(
      module: Module,
      reason: PcBackendUnavailableReason,
      message: String
  ): Unit =
    if isManaged(module) then
      val _ = availability.put(module, PcBackendAvailability.Unavailable(reason))
      log.warn(message)
    else
      val _ = availability.remove(module)

  private def failureMessage(error: Throwable): String =
    val cause = Iterator.iterate(error)(_.getCause).takeWhile(_ != null).toSeq.lastOption.getOrElse(error)
    Option(cause.getMessage).getOrElse(cause.getClass.getSimpleName)

  private def modelDescriptor(module: Module): Option[CompilerBackendModuleDescriptor] =
    readModelDescriptor(module) match
      case CompilerBackendModelState.Ready(descriptor) => Some(descriptor)
      case _                                           => None

  private def readModelDescriptor(module: Module): CompilerBackendModelState =
    val computation: Computable[CompilerBackendModelState] = () => CompilerBackendModuleDescriptor.read(project, module)
    readAction(computation)

  private def sessionEntryIsCurrent(module: Module, entry: SessionEntry): Boolean =
    modelDescriptor(module).exists: descriptor =>
      entry.compilerOptions == descriptor.compilerOptions &&
        entry.classpathHash == PcSessionManager.fingerprintClasspath(
          PcSessionManager.exposeBestEffortTastyRoots(descriptor.compileClasspath)
        )

  private def applicationIsDispatchThread: Boolean =
    Option(ApplicationManager.getApplication).exists(_.isDispatchThread)

  private def currentGeneration(module: Module, session: PcSession): Option[CompilerBackendGeneration] =
    Option(sessions.get(module)).filter(_.session eq session).map(_.generation)

  private def snapshotCurrency(
      module: Module,
      session: PcSession,
      generation: CompilerBackendGeneration
  ): PcSnapshotCurrency =
    if isManaged(module) && currentGeneration(module, session).contains(generation) then PcSnapshotCurrency.Current
    else PcSnapshotCurrency.Superseded

private final case class SessionEntry(
    scalaVersion: String,
    classpathHash: String,
    compilerOptions: Seq[String],
    generation: CompilerBackendGeneration,
    session: PcSession
)

private final case class SessionKey(module: Module, scalaVersion: String)

private[metallurgy] enum PcBackendAvailability:
  case Undiscovered
  case Inactive
  case Pending(scalaVersion: String)
  case Available(scalaVersion: String, capabilities: Scala3PcBridgeCapabilities)
  case Unavailable(reason: PcBackendUnavailableReason)

private[metallurgy] enum PcBackendUnavailableReason:
  case MissingScalaVersion
  case ProjectModel(detail: String)
  case ArtifactPreparation(scalaVersion: String, detail: String)
  case SessionCreation(scalaVersion: String, detail: String)

private final case class BackendPreparationKey(module: Module, fileUri: String, documentVersion: Long)

object PcSessionManager:
  def get(project: Project): PcSessionManager = project.getService(classOf[PcSessionManager])

  /** `.betasty` must sit at a classpath root to be read by `-Ywith-best-effort-tasty`. For each directory root that
    * carries a `META-INF/best-effort` subdir — an upstream module compiled with `-Ybest-effort` while broken — expose
    * that subdir as an additional classpath root. No-op for roots without it (clean modules, jars). Mirrors scala3's
    * `compileWithBestEffortTasty` (`-classpath …:<out>/META-INF/best-effort`).
    */
  private[pc] def exposeBestEffortTastyRoots(roots: Seq[File]): Seq[File] =
    val betastyRoots = roots
      .collect:
        case root if root.isDirectory => File(root, "META-INF/best-effort")
      .filter(_.isDirectory)
    (roots ++ betastyRoots).distinct

  /** Fingerprints ordinary classpath entries by filesystem identity and best-effort roots by artifact content. A
    * `.betasty` rewrite can preserve both size and timestamp, so metadata alone cannot guard compiler-symbol freshness.
    */
  private[pc] def fingerprintClasspath(classpath: Seq[File]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    classpath
      .sortBy(_.getAbsolutePath)
      .foreach: file =>
        updateDigest(digest, s"${file.getAbsolutePath}\u0000${file.length()}\u0000${file.lastModified()}\n")
        if isBestEffortRoot(file) && file.isDirectory then
          try
            val stream = Files.walk(file.toPath)
            try
              stream
                .iterator()
                .asScala
                .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".betasty"))
                .toVector
                .sortBy(path => file.toPath.relativize(path).toString)
                .foreach: artifact =>
                  updateDigest(digest, file.toPath.relativize(artifact).toString)
                  val input = Files.newInputStream(artifact)
                  try
                    val buffer = new Array[Byte](8192)
                    var read   = input.read(buffer)
                    while read >= 0 do
                      if read > 0 then digest.update(buffer, 0, read)
                      read = input.read(buffer)
                  finally input.close()
            finally stream.close()
          catch case NonFatal(error) => updateDigest(digest, s"unreadable:${error.getClass.getName}")
    digest.digest().map(byte => f"${byte & 0xff}%02x").mkString

  private def isBestEffortRoot(file: File): Boolean =
    file.getName == "best-effort" && Option(file.getParentFile).exists(_.getName == "META-INF")

  private def updateDigest(digest: MessageDigest, value: String): Unit =
    digest.update(value.getBytes(StandardCharsets.UTF_8))
