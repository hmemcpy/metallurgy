package com.hmemcpy.metallurgy.pc

import com.google.gson.JsonElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.{ProcessCanceledException, ProgressManager}
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import org.eclipse.lsp4j.{CompletionItem, CompletionItemKind}
import scala.meta.pc.{CancelToken, OffsetParams, PresentationCompiler}

import java.io.File
import java.net.{URI, URL, URLClassLoader}
import java.util.concurrent.{CompletableFuture, CompletionStage, ConcurrentHashMap, ThreadPoolExecutor, TimeUnit}
import java.util.concurrent.locks.{ReentrantLock, ReentrantReadWriteLock}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final class PcSession private (
    val scalaVersion: String,
    val classloader: URLClassLoader,
    val compilerDistribution: Seq[File],
    val compilerClasspath: Seq[File],
    val compilerOptions: Seq[String],
    private[metallurgy] val capabilities: Scala3PcBridgeCapabilities,
    initialCompilerPrototype: Option[PresentationCompiler]
) extends AutoCloseable:

  private val Log                    = Logger.getInstance(classOf[PcSession])
  private val presentationCompiler   = new AtomicReference[Option[PresentationCompiler]](None)
  private val compilerPrototype      = new AtomicReference(initialCompilerPrototype)
  private val compilerExecutors      = new ConcurrentHashMap[ThreadPoolExecutor, java.lang.Boolean]()
  private val classloaderCloseable   = new AtomicBoolean(true)
  private val inlineTypeDrivers      = new ConcurrentHashMap[String, InlineTypeDriverLease]()
  private val inlineDriverLeases     = new ConcurrentHashMap[InlineTypeDriverLease, java.lang.Boolean]()
  private val inlineDriverCreations  = new AtomicInteger(0)
  private val snapshots              = new PcSnapshotStore()
  private val requestedVersions      = new ConcurrentHashMap[String, java.lang.Long]()
  private val retypecheckGeneration  = new AtomicLong(0L)
  private val pendingRetypecheck     = new AtomicReference[Option[PendingRetypecheck]](None)
  private val retypecheckLock        = new ReentrantLock()
  private val compilerSubmissionLock = new ReentrantLock()
  private val compilerRequestLock    = new ReentrantReadWriteLock()
  private val lifetime               = Disposer.newDisposable(s"Metallurgy PC session $scalaVersion")
  private val retypecheckAlarm       = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, lifetime)
  private val closed                 = new AtomicBoolean(false)

  /** Ask the isolated Metals presentation compiler for semantic completion items. No Scala or LSP4J value may cross the
    * classloader boundary.
    */
  private[metallurgy] def complete(
      fileUri: String,
      sourceText: String,
      documentVersion: Long,
      offset: Int
  ): Seq[PcCompletion] =
    val candidate = PcSnapshot(fileUri, documentVersion, sourceText)
    val key       = QueryKey.Complete(offset)
    snapshots.matching(fileUri, documentVersion) match
      case Some(snapshot) =>
        snapshot
          .cached[Seq[PcCompletion]](key, System.nanoTime())
          .getOrElse(
            snapshot.cachedOrCompute(key, System.nanoTime())(queryCompletion(candidate, offset).getOrElse(Seq.empty))
          )
      case None           => Seq.empty

  private[metallurgy] def inlineType(snapshot: PcSnapshot, range: TextRange): Option[String] =
    val key = QueryKey.TypeAt(range)
    snapshots.matching(snapshot.fileUri, snapshot.documentVersion) match
      case Some(active) =>
        active
          .cached[Option[String]](key, System.nanoTime())
          .getOrElse:
            if applicationIsDispatchThread then None
            else
              active.cachedOrCompute(key, System.nanoTime()):
                try
                  Option(inlineTypeDrivers.get(snapshot.fileUri))
                    .flatMap(_.use(_.typeAt(snapshot, range)))
                    .flatten
                catch
                  case NonFatal(error) =>
                    Log.warn(s"PC inline type failed for ${snapshot.fileUri}", error)
                    None
      case None         => None

  /** Return compiler diagnostics only for the currently published document version. A failed compiler request is
    * distinct from a successful, clean result so callers never suppress bundled diagnostics on uncertainty.
    */
  private[metallurgy] def diagnostics(snapshot: PcSnapshot): Option[Seq[PcDiagnostic]] =
    val key = QueryKey.Diagnose(TextRange(0, snapshot.sourceText.length))
    snapshots.matching(snapshot.fileUri, snapshot.documentVersion) match
      case Some(active) =>
        active
          .cached[Option[Seq[PcDiagnostic]]](key, System.nanoTime())
          .getOrElse:
            if applicationIsDispatchThread then None
            else
              active.cachedOrCompute(key, System.nanoTime()):
                try
                  Option(inlineTypeDrivers.get(snapshot.fileUri))
                    .flatMap(_.use(_.diagnostics(snapshot)))
                catch
                  case NonFatal(error) =>
                    Log.warn(s"PC diagnostics failed for ${snapshot.fileUri}", error)
                    None
      case None         => None

  /** Return the immutable typed-tree view only while this exact document version remains current. */
  private[metallurgy] def typedTreeSnapshot(snapshot: PcSnapshot): Option[PcTypedTreeSnapshot] =
    val key = QueryKey.TypedTreeSnapshot
    snapshots.matching(snapshot.fileUri, snapshot.documentVersion) match
      case Some(active) if !applicationIsDispatchThread =>
        active
          .cached[Option[PcTypedTreeSnapshot]](key, System.nanoTime())
          .getOrElse:
            active.cachedOrCompute(key, System.nanoTime()):
              try
                val currency   = () =>
                  if !closed.get() &&
                    Option(requestedVersions.get(snapshot.fileUri)).exists(_.longValue() == snapshot.documentVersion) &&
                    snapshots.matching(snapshot.fileUri, snapshot.documentVersion).exists(_ eq active)
                  then PcSnapshotCurrency.Current
                  else PcSnapshotCurrency.Superseded
                val extraction = Option(inlineTypeDrivers.get(snapshot.fileUri)).flatMap:
                  _.use: driver =>
                    driver.typedTreeSnapshot(snapshot, currency) match
                      case PcTypedTreeExtraction.Completed(extracted) =>
                        val occurrences = querySemanticdbOccurrences(snapshot, driver)
                        PcTypedTreeExtraction.Completed(mergeReferenceOccurrences(extracted, occurrences))
                      case other                                      => other
                extraction.collect:
                  case PcTypedTreeExtraction.Completed(extracted) if currency() == PcSnapshotCurrency.Current =>
                    extracted
              catch
                case canceled: ProcessCanceledException => throw canceled
                case NonFatal(error)                    =>
                  Log.warn(s"PC typed-tree extraction failed for ${snapshot.fileUri}", error)
                  None
      case _                                            => None

  private def querySemanticdbOccurrences(
      snapshot: PcSnapshot,
      driver: Scala3PcBridge
  ): Option[Vector[PcSemanticdbOccurrence]] =
    withPresentationCompiler(None):
      if !capabilities.semanticdb.isAvailable then None
      else
        ProgressManager.checkCanceled()
        val activeCompiler = compiler
        val future         = submitCompilerRequest(activeCompiler):
          activeCompiler.semanticdbTextDocument(
            PcSourceUri.normalize(snapshot.fileUri),
            snapshot.compilerText
          )
        try
          val bytes = future.get(5, TimeUnit.SECONDS)
          ProgressManager.checkCanceled()
          Some(
            driver
              .semanticdbOccurrences(bytes, snapshot.compilerText)
              .flatMap: occurrence =>
                snapshot.projection
                  .toDocumentRange(occurrence.range.startOffset, occurrence.range.endOffset)
                  .map(documentRange => occurrence.copy(range = documentRange))
          )
        catch
          case canceled: ProcessCanceledException => throw canceled
          case NonFatal(error) =>
            val _ = future.cancel(true)
            Log.warn(s"PC SemanticDB extraction failed for ${snapshot.fileUri}", error)
            None

  private def mergeReferenceOccurrences(
      snapshot: PcTypedTreeSnapshot,
      occurrences: Option[Vector[PcSemanticdbOccurrence]]
  ): PcTypedTreeSnapshot =
    val available = occurrences.getOrElse(Vector.empty)
    snapshot.copy(entries = snapshot.entries.map: entry =>
      if entry.role != PcTypedTreeRole.Reference then entry
      else
        val occurrence = available
          .filter(candidate =>
            candidate.range.startOffset >= entry.range.startOffset &&
              candidate.range.endOffset <= entry.range.endOffset
          )
          .sortBy(candidate => (candidate.range.endOffset == entry.range.endOffset, candidate.range.startOffset))
          .lastOption
        occurrence match
          case None            => entry
          case Some(candidate) =>
            val sourceName = candidate.name.trim.split('.').lastOption.getOrElse(candidate.name)
            val deferred   = snapshot.entries.iterator
              .flatMap(_.symbol)
              .filter(symbol => symbol.isDeferred && symbol.name == sourceName)
              .toVector
              .distinctBy(_.id)
            val metadata   = snapshot.entries.iterator
              .filter(other => other.role != PcTypedTreeRole.Reference && other.range == entry.range)
              .flatMap(_.symbol)
              .find(_.name == sourceName)
              .orElse(entry.symbol)
            val resolved   = Option
              .when(deferred.size == 1)(deferred.head)
              .orElse(metadata.map(_.copy(id = candidate.symbolId, name = sourceName)))
            entry.copy(
              symbol = Some(
                resolved
                  .getOrElse:
                    PcCompilerSymbol(
                      candidate.symbolId,
                      sourceName,
                      Set.empty,
                      None,
                      None,
                      isType = entry.symbol.exists(_.isType)
                    )
              )
            )
    )

  private[metallurgy] def snapshotCount: Int = snapshots.size

  /** Debounces edits per session. A newer edit supersedes the scheduled result; typed state is published only after a
    * successful compiler run.
    */
  private[metallurgy] def scheduleRetypecheck(snapshot: PcSnapshot): CompletableFuture[RetypecheckOutcome] =
    if closed.get() then CompletableFuture.completedFuture(RetypecheckOutcome.Superseded)
    else
      requestedVersions.put(snapshot.fileUri, snapshot.documentVersion)
      if snapshots.matching(snapshot.fileUri, snapshot.documentVersion).nonEmpty then
        CompletableFuture.completedFuture(RetypecheckOutcome.Applied)
      else
        pendingRetypecheck.get() match
          case Some(pending) if pending.isFor(snapshot) => pending.result
          case _                                        =>
            val generation     = retypecheckGeneration.incrementAndGet()
            val result         = new CompletableFuture[RetypecheckOutcome]()
            val task: Runnable = () => runRetypecheck(snapshot, generation, result)
            val pending        = PendingRetypecheck(snapshot.fileUri, snapshot.documentVersion, task, result)
            pendingRetypecheck.getAndSet(Some(pending)).foreach(_.supersede(retypecheckAlarm))
            retypecheckAlarm.addRequest(task, PcSession.RetypecheckDebounceMillis)
            result

  override def close(): Unit =
    if closed.compareAndSet(false, true) then
      retypecheckGeneration.incrementAndGet()
      pendingRetypecheck.getAndSet(None).foreach(_.supersede(retypecheckAlarm))
      Disposer.dispose(lifetime)
      if applicationIsDispatchThread then AppExecutorUtil.getAppExecutorService.execute(() => drainAndClose())
      else drainAndClose()

  private def drainAndClose(): Unit =
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PcSession.ShutdownTimeoutSeconds)
    if !tryLock(compilerRequestLock.writeLock(), deadline) then
      Log.warn("PcSession retains its classloader because active presentation-compiler requests did not quiesce")
    else
      try
        if !tryLock(retypecheckLock, deadline) then
          Log.warn("PcSession retains its classloader because an active retypecheck did not quiesce")
        else
          try
            val drivers         = inlineDriverLeases.keys().asScala.toVector
            drivers.foreach(_.retire())
            val driversClosed   = drivers.forall(_.awaitRetirement(deadline))
            inlineTypeDrivers.clear()
            if driversClosed then inlineDriverLeases.clear()
            val prototypeClosed = compilerPrototype.getAndSet(None).forall(shutdown)
            val compilerClosed  = presentationCompiler.getAndSet(None).forall(shutdown)
            val executorsClosed = compilerExecutors
              .keys()
              .asScala
              .forall(executor => Scala3PcBridge.awaitTermination(executor, remaining(deadline)).isRight)
            snapshots.clear()
            requestedVersions.clear()
            if driversClosed && prototypeClosed && compilerClosed && executorsClosed && classloaderCloseable.get() then
              try classloader.close()
              catch case NonFatal(error) => Log.warn("Error closing PcSession classloader", error)
            else Log.warn("PcSession retains its classloader because compiler shutdown did not quiesce")
          finally retypecheckLock.unlock()
      finally compilerRequestLock.writeLock().unlock()

  private[pc] def isClosed: Boolean = closed.get()

  private[pc] def inlineDriverCreationCount: Int = inlineDriverCreations.get()

  private def queryCompletion(snapshot: PcSnapshot, offset: Int): Option[Seq[PcCompletion]] =
    withPresentationCompiler(None):
      ProgressManager.checkCanceled()
      val activeCompiler = compiler
      val future         = submitCompilerRequest(activeCompiler):
        activeCompiler.complete(
          PcOffsetParams(PcSourceUri.normalize(snapshot.fileUri), snapshot.sourceText, offset)
        )
      try
        val completionList = future.get(5, TimeUnit.SECONDS)
        ProgressManager.checkCanceled()
        val items          = completionList.getItems.asScala.flatMap(decodeItem).toSeq
        val refinements    = structuralCompletions(snapshot, offset)
        val declarations   =
          cachedTypedTreeSnapshot(snapshot).toSeq.flatMap(_.entries).flatMap(_.symbol).filter(_.isDeferred)
        val canonical      = (items ++ refinements).map: item =>
          val matches = declarations
            .filter(symbol => refinements.exists(_.lookupName == item.lookupName) && symbol.name == item.lookupName)
            .distinctBy(_.id)
          if matches.size == 1 then item.copy(symbol = Some(matches.head.id)) else item
        Some(canonical.distinctBy(_.lookupName))
      catch
        case NonFatal(error) =>
          val _ = future.cancel(true)
          Log.warn(s"PC completion failed for ${snapshot.fileUri} at $offset", error)
          None

  private def cachedTypedTreeSnapshot(snapshot: PcSnapshot): Option[PcTypedTreeSnapshot] =
    snapshots
      .matching(snapshot.fileUri, snapshot.documentVersion)
      .flatMap(_.cached[Option[PcTypedTreeSnapshot]](QueryKey.TypedTreeSnapshot, System.nanoTime()).flatten)

  private def structuralCompletions(snapshot: PcSnapshot, offset: Int): Seq[PcCompletion] =
    try
      Option(inlineTypeDrivers.get(snapshot.fileUri))
        .flatMap(_.use(_.structuralCompletions(snapshot, offset)))
        .getOrElse(Seq.empty)
    catch
      case NonFatal(error) =>
        Log.warn(s"Structural completion failed for ${snapshot.fileUri} at $offset", error)
        Seq.empty

  private def runRetypecheck(
      snapshot: PcSnapshot,
      generation: Long,
      result: CompletableFuture[RetypecheckOutcome]
  ): Unit =
    PcSession.settleRetypecheck(
      result, {
        val _ = pendingRetypecheck.updateAndGet(_.filterNot(_.result eq result))
      }
    ):
      val outcome =
        if generation != retypecheckGeneration.get() || closed.get() then RetypecheckOutcome.Superseded
        else compileAndPublish(snapshot, generation)
      completeRetypecheck(result, outcome)

  private def compileAndPublish(snapshot: PcSnapshot, generation: Long): RetypecheckOutcome =
    try
      retypecheckLock.lockInterruptibly()
      try
        if generation != retypecheckGeneration.get() || closed.get() then RetypecheckOutcome.Superseded
        else
          val driver = Scala3PcBridge.open(classloader, compilerClasspath, compilerOptions)
          inlineDriverCreations.incrementAndGet()
          try
            driver.retypecheck(snapshot)
            publish(snapshot, generation, driver)
          catch
            case NonFatal(error) =>
              shutdownInlineDriver(driver)
              throw error
      finally retypecheckLock.unlock()
    catch
      case canceled: ProcessCanceledException => throw canceled
      case _: InterruptedException            => RetypecheckOutcome.Superseded
      case NonFatal(error)                    =>
        Log.warn(s"PC retypecheck failed for ${snapshot.fileUri}", error)
        RetypecheckOutcome.Failed(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))

  private def publish(
      snapshot: PcSnapshot,
      generation: Long,
      driver: Scala3PcBridge
  ): RetypecheckOutcome =
    if generation == retypecheckGeneration.get() && !closed.get() then
      val replacement = new InlineTypeDriverLease(
        driver,
        lease =>
          val _ = inlineDriverLeases.remove(lease)
      )
      val _           = inlineDriverLeases.put(replacement, java.lang.Boolean.TRUE)
      Option(inlineTypeDrivers.put(snapshot.fileUri, replacement)).foreach(_.retire())
      val _           = snapshots.accept(snapshot)
      RetypecheckOutcome.Applied
    else
      shutdownInlineDriver(driver)
      RetypecheckOutcome.Superseded

  private def completeRetypecheck(
      result: CompletableFuture[RetypecheckOutcome],
      outcome: RetypecheckOutcome
  ): Unit =
    val _ = result.complete(outcome)

  private def compiler: PresentationCompiler =
    if closed.get() then throw new IllegalStateException("PcSession is closed")
    val observed = presentationCompiler.get()
    observed.getOrElse:
      val created = createCompiler()
      if presentationCompiler.compareAndSet(observed, Some(created)) then created
      else
        val _ = shutdown(created)
        presentationCompiler
          .get()
          .getOrElse:
            throw new IllegalStateException("PcSession was closed while creating its presentation compiler")

  private def createCompiler(): PresentationCompiler =
    val prototype = compilerPrototype
      .getAndSet(None)
      .getOrElse:
        PresentationCompilerDiscovery
          .load(classloader, compilerDistribution)
          .fold(reason => throw new IllegalStateException(reason.message), identity)

    try
      prototype.newInstance(
        s"metallurgy-$scalaVersion",
        compilerClasspath.map(_.toPath).asJava,
        compilerOptions.asJava
      )
    finally
      val _ = shutdown(prototype)

  private def decodeItem(item: CompletionItem): Option[PcCompletion] =
    Option(item.getLabel).map: label =>
      val filterText = Option(item.getFilterText)
      val detail     = Option(item.getDetail)
      PcCompletion(
        filterText.getOrElse(label.takeWhile(_ != '(')),
        label,
        detail,
        completionSymbol(item.getData),
        completionIsType(item.getKind)
      )

  private def completionSymbol(data: Object): Option[String] =
    data match
      case json: JsonElement if json.isJsonObject =>
        Option(json.getAsJsonObject.get("symbol"))
          .filterNot(_.isJsonNull)
          .map(_.getAsString.trim)
          .filter(value => value.nonEmpty && value != "<no-symbol>")
      case values: java.util.Map[?, ?]            =>
        Option(values.get("symbol"))
          .map(_.toString.trim)
          .filter(value => value.nonEmpty && value != "<no-symbol>")
      case _                                      => None

  private def completionIsType(kind: CompletionItemKind): Boolean =
    kind == CompletionItemKind.Class || kind == CompletionItemKind.Interface || kind == CompletionItemKind.Enum ||
      kind == CompletionItemKind.Struct || kind == CompletionItemKind.TypeParameter

  private def applicationIsDispatchThread: Boolean =
    Option(ApplicationManager.getApplication).exists(_.isDispatchThread)

  private def withPresentationCompiler[A](closedResult: => A)(request: => A): A =
    if closed.get() || !compilerRequestLock.readLock().tryLock() then closedResult
    else
      try if closed.get() then closedResult else request
      finally compilerRequestLock.readLock().unlock()

  private def captureExecutor(compiler: PresentationCompiler): Unit =
    Scala3PcBridge.captureExecutor(compiler) match
      case Right(executor) => val _ = compilerExecutors.put(executor, java.lang.Boolean.TRUE)
      case Left(reason)    =>
        classloaderCloseable.set(false)
        Log.warn(s"Presentation-compiler executor cannot be tracked: $reason")

  private def submitCompilerRequest[A](compiler: PresentationCompiler)(request: => A): A =
    compilerSubmissionLock.lock()
    try
      val result = request
      captureExecutor(compiler)
      result
    finally compilerSubmissionLock.unlock()

  private def shutdown(compiler: PresentationCompiler): Boolean =
    Scala3PcBridge.shutdown(compiler) match
      case Right(_)     => true
      case Left(reason) =>
        Log.warn(s"Error shutting down presentation compiler: $reason")
        false

  private def tryLock(lock: java.util.concurrent.locks.Lock, deadline: Long): Boolean =
    try lock.tryLock(remaining(deadline), TimeUnit.NANOSECONDS)
    catch
      case _: InterruptedException =>
        Thread.currentThread.interrupt()
        false

  private def remaining(deadline: Long): Long =
    math.max(0L, deadline - System.nanoTime())

  private def shutdownInlineDriver(driver: Scala3PcBridge): Unit =
    try driver.close()
    catch case NonFatal(error) => Log.warn("Error closing inline type driver", error)

private[metallurgy] final case class PcCompletion(
    lookupName: String,
    label: String,
    detail: Option[String],
    symbol: Option[String] = None,
    isType: Boolean = false
)

private[metallurgy] final case class PcDiagnostic(range: TextRange, isError: Boolean, message: String)

private final case class PcOffsetParams(uri: URI, text: String, offset: Int) extends OffsetParams:
  override def token(): CancelToken = PcCancelToken

private object PcCancelToken extends CancelToken:
  override def checkCanceled(): Unit                          = ProgressManager.checkCanceled()
  override def onCancel(): CompletionStage[java.lang.Boolean] =
    CompletableFuture.completedFuture(java.lang.Boolean.FALSE)

private[pc] enum RetypecheckOutcome:
  case Applied
  case Superseded
  case Failed(message: String)

private final case class PendingRetypecheck(
    fileUri: String,
    documentVersion: Long,
    task: Runnable,
    result: CompletableFuture[RetypecheckOutcome]
):
  def isFor(snapshot: PcSnapshot): Boolean =
    fileUri == snapshot.fileUri && documentVersion == snapshot.documentVersion

  def supersede(alarm: Alarm): Unit =
    val _ = alarm.cancelRequest(task)
    val _ = result.complete(RetypecheckOutcome.Superseded)

/** Keeps an atomically published typed driver alive until its last concurrent reader completes. */
private final class InlineTypeDriverLease(
    driver: Scala3PcBridge,
    onClosed: InlineTypeDriverLease => Unit = _ => ()
):
  private val readers = new AtomicInteger(0)
  private val retired = new AtomicBoolean(false)
  private val closing = new AtomicBoolean(false)
  private val closed  = new CompletableFuture[Unit]()

  def use[A](query: Scala3PcBridge => A): Option[A] =
    if !acquire() then None
    else
      try Some(query(driver))
      finally release()

  def retire(): Unit =
    if retired.compareAndSet(false, true) && readers.get() == 0 then closeDriver()

  def awaitRetirement(deadline: Long): Boolean =
    try
      val _ = closed.get(math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
      true
    catch
      case _: InterruptedException =>
        Thread.currentThread.interrupt()
        false
      case NonFatal(_)             => false

  private def acquire(): Boolean =
    readers.incrementAndGet()
    if retired.get() then
      release()
      false
    else true

  private def release(): Unit =
    if readers.decrementAndGet() == 0 then if retired.get() then closeDriver()

  private def closeDriver(): Unit =
    if retired.get() && closing.compareAndSet(false, true) then
      try
        AppExecutorUtil.getAppExecutorService.execute: () =>
          try
            driver.close()
            val _ = closed.complete(())
            onClosed(this)
          catch
            case NonFatal(error) =>
              val _ = closed.completeExceptionally(error)
      catch
        case NonFatal(error) =>
          val _ = closed.completeExceptionally(error)

/** Loads the exact compiler distribution while exposing only the published Java presentation-compiler boundary from the
  * plugin classloader. Scala and compiler implementation classes always remain local to this loader.
  */
private final class PcClassLoader(urls: Array[URL], host: ClassLoader)
    extends URLClassLoader(urls, new PcSharedApiClassLoader(host))

/** Restricts parent delegation to platform classes and types that may legally cross the Scalameta boundary. */
private final class PcSharedApiClassLoader(host: ClassLoader) extends ClassLoader(ClassLoader.getPlatformClassLoader):

  override protected def findClass(name: String): Class[?] =
    if PcClassLoader.isSharedApi(name) then host.loadClass(name)
    else throw new ClassNotFoundException(name)

private object PcClassLoader:
  private val SharedApiPrefixes = Seq(
    "javax.",
    "scala.meta.pc.",
    "org.eclipse.lsp4j.",
    "com.google.gson."
  )

  def isSharedApi(className: String): Boolean =
    SharedApiPrefixes.exists(className.startsWith)

object PcSession:
  private val RetypecheckDebounceMillis = 300L
  private val ShutdownTimeoutSeconds    = 15L

  private[pc] def settleRetypecheck(
      result: CompletableFuture[RetypecheckOutcome],
      cleanup: => Unit
  )(operation: => Unit): Unit =
    try operation
    catch
      case canceled: ProcessCanceledException =>
        val _ = result.completeExceptionally(canceled)
    finally cleanup

  def create(
      scalaVersion: String,
      classpath: Seq[File],
      compilerOptions: Seq[String],
      fetcher: MtagsFetcher
  ): PcSession =
    val cachedJars = MtagsFetcher
      .cachedJars(fetcher, scalaVersion)
      .getOrElse:
        throw new IllegalStateException(s"Presentation compiler artifacts are not cached for Scala $scalaVersion")

    val urls        = (cachedJars ++ classpath).map(_.toURI.toURL).toArray
    val classloader = new PcClassLoader(urls, classOf[PcSession].getClassLoader)

    val provider     = PresentationCompilerDiscovery.load(classloader, cachedJars.toIndexedSeq)
    val capabilities = Scala3PcBridge.discoverCapabilities(classloader, provider)
    new PcSession(
      scalaVersion,
      classloader,
      cachedJars.toIndexedSeq,
      classpath,
      capabilities.presentationCompilerOptions(compilerOptions),
      capabilities,
      provider.toOption
    )
