package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import org.eclipse.lsp4j.CompletionItem
import scala.meta.pc.{CancelToken, OffsetParams, PresentationCompiler}

import java.io.File
import java.net.{URI, URLClassLoader}
import java.util.ServiceLoader
import java.util.concurrent.{CompletableFuture, CompletionStage, ConcurrentHashMap, TimeUnit}
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final class PcSession private (
    val scalaVersion: String,
    val classloader: URLClassLoader,
    val compilerClasspath: Seq[File],
    val compilerOptions: Seq[String]
) extends AutoCloseable:

  private val Log                   = Logger.getInstance(classOf[PcSession])
  private val presentationCompiler  = new AtomicReference[Option[PresentationCompiler]](None)
  private val inlineTypeDrivers     = new ConcurrentHashMap[String, InlineTypeDriverLease]()
  private val inlineDriverCreations = new AtomicInteger(0)
  private val snapshots             = new PcSnapshotStore()
  private val retypecheckGeneration = new AtomicLong(0L)
  private val pendingRetypecheck    = new AtomicReference[Option[PendingRetypecheck]](None)
  private val retypecheckLock       = new ReentrantLock()
  private val lifetime              = Disposer.newDisposable(s"Metallurgy PC session $scalaVersion")
  private val retypecheckAlarm      = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, lifetime)
  private val closed                = new AtomicBoolean(false)

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

  private[metallurgy] def inlineType(snapshot: PcSnapshot, offset: Int): Option[String] =
    val key = QueryKey.TypeAt(offset)
    snapshots.matching(snapshot.fileUri, snapshot.documentVersion) match
      case Some(active) =>
        active
          .cached[Option[String]](key, System.nanoTime())
          .getOrElse:
            if applicationIsDispatchThread then None
            else
              active.cachedOrCompute(key, System.nanoTime()):
                Option(inlineTypeDrivers.get(snapshot.fileUri))
                  .flatMap(_.use(_.typeAt(snapshot, offset)))
                  .flatten
      case None         => None

  private[metallurgy] def snapshotCount: Int = snapshots.size

  /** Debounces edits per session. A newer edit supersedes the scheduled result; typed state is published only after a
    * successful compiler run.
    */
  private[pc] def scheduleRetypecheck(snapshot: PcSnapshot): CompletableFuture[RetypecheckOutcome] =
    if closed.get() then CompletableFuture.completedFuture(RetypecheckOutcome.Superseded)
    else if snapshots.matching(snapshot.fileUri, snapshot.documentVersion).nonEmpty then
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
      inlineTypeDrivers.values().asScala.foreach(_.retire())
      inlineTypeDrivers.clear()
      presentationCompiler.getAndSet(None).foreach(shutdown)
      snapshots.clear()
      try classloader.close()
      catch case NonFatal(error) => Log.warn("Error closing PcSession classloader", error)

  private[pc] def isClosed: Boolean = closed.get()

  private[pc] def inlineDriverCreationCount: Int = inlineDriverCreations.get()

  private def queryCompletion(snapshot: PcSnapshot, offset: Int): Option[Seq[PcCompletion]] =
    ProgressManager.checkCanceled()
    val future = compiler.complete(PcOffsetParams(URI.create(snapshot.fileUri), snapshot.sourceText, offset))
    try
      val completionList = future.get(5, TimeUnit.SECONDS)
      ProgressManager.checkCanceled()
      Some(completionList.getItems.asScala.flatMap(decodeItem).toSeq)
    catch
      case NonFatal(error) =>
        val _ = future.cancel(true)
        Log.warn(s"PC completion failed for ${snapshot.fileUri} at $offset", error)
        None

  private def runRetypecheck(
      snapshot: PcSnapshot,
      generation: Long,
      result: CompletableFuture[RetypecheckOutcome]
  ): Unit =
    val outcome =
      if generation != retypecheckGeneration.get() || closed.get() then RetypecheckOutcome.Superseded
      else compileAndPublish(snapshot, generation)
    completeRetypecheck(result, outcome)
    val _       = pendingRetypecheck.updateAndGet(_.filterNot(_.result eq result))

  private def compileAndPublish(snapshot: PcSnapshot, generation: Long): RetypecheckOutcome =
    try
      retypecheckLock.lockInterruptibly()
      try
        if generation != retypecheckGeneration.get() || closed.get() then RetypecheckOutcome.Superseded
        else
          val driver = new PcInlineTypeDriver(classloader, compilerClasspath, compilerOptions)
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
      case _: InterruptedException => RetypecheckOutcome.Superseded
      case NonFatal(error)         =>
        Log.warn(s"PC retypecheck failed for ${snapshot.fileUri}", error)
        RetypecheckOutcome.Failed(Option(error.getMessage).getOrElse(error.getClass.getSimpleName))

  private def publish(
      snapshot: PcSnapshot,
      generation: Long,
      driver: PcInlineTypeDriver
  ): RetypecheckOutcome =
    if generation == retypecheckGeneration.get() && !closed.get() then
      val replacement = new InlineTypeDriverLease(driver)
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
        shutdown(created)
        presentationCompiler
          .get()
          .getOrElse:
            throw new IllegalStateException("PcSession was closed while creating its presentation compiler")

  private def createCompiler(): PresentationCompiler =
    val providers = ServiceLoader.load(classOf[PresentationCompiler], classloader).iterator().asScala
    val prototype = providers
      .nextOption()
      .getOrElse:
        throw new IllegalStateException("No Scala presentation compiler provider found")

    prototype.newInstance(
      s"metallurgy-$scalaVersion",
      compilerClasspath.map(_.toPath).asJava,
      compilerOptions.asJava
    )

  private def decodeItem(item: CompletionItem): Option[PcCompletion] =
    Option(item.getLabel).map: label =>
      val filterText = Option(item.getFilterText)
      val detail     = Option(item.getDetail)
      PcCompletion(filterText.getOrElse(label.takeWhile(_ != '(')), label, detail)

  private def applicationIsDispatchThread: Boolean =
    Option(ApplicationManager.getApplication).exists(_.isDispatchThread)

  private def shutdown(compiler: PresentationCompiler): Unit =
    try compiler.shutdown()
    catch case NonFatal(error) => Log.warn("Error shutting down presentation compiler", error)

  private def shutdownInlineDriver(driver: PcInlineTypeDriver): Unit =
    try driver.close()
    catch case NonFatal(error) => Log.warn("Error closing inline type driver", error)

private[metallurgy] final case class PcCompletion(
    lookupName: String,
    label: String,
    detail: Option[String]
)

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
private final class InlineTypeDriverLease(driver: PcInlineTypeDriver):
  private val readers = new AtomicInteger(0)
  private val retired = new AtomicBoolean(false)

  def use[A](query: PcInlineTypeDriver => A): Option[A] =
    if !acquire() then None
    else
      try Some(query(driver))
      finally release()

  def retire(): Unit =
    if retired.compareAndSet(false, true) && readers.get() == 0 then driver.close()

  private def acquire(): Boolean =
    readers.incrementAndGet()
    if retired.get() then
      release()
      false
    else true

  private def release(): Unit =
    if readers.decrementAndGet() == 0 && retired.get() then driver.close()

/** Child-first for the Scala compiler implementation, parent-first for JDK/platform classes and the stable Java
  * presentation-compiler boundary.
  */
private final class PcClassLoader(urls: Array[java.net.URL], parent: ClassLoader) extends URLClassLoader(urls, parent):

  override protected def loadClass(name: String, resolve: Boolean): Class[?] =
    if PcClassLoader.isSharedApi(name) then super.loadClass(name, resolve)
    else
      // ClassLoader requires per-name locking around findLoadedClass/findClass.
      getClassLoadingLock(name).synchronized:
        Option(findLoadedClass(name)).getOrElse:
          try
            val loaded = findClass(name)
            if resolve then resolveClass(loaded)
            loaded
          catch case _: ClassNotFoundException => super.loadClass(name, resolve)

private object PcClassLoader:
  private val ParentFirstPrefixes = Seq(
    "java.",
    "javax.",
    "jdk.",
    "sun.",
    "com.intellij.",
    "scala.meta.pc.",
    "org.eclipse.lsp4j."
  )

  def isSharedApi(className: String): Boolean =
    ParentFirstPrefixes.exists(className.startsWith)

object PcSession:
  private val RetypecheckDebounceMillis = 300L

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

    new PcSession(scalaVersion, classloader, classpath, compilerOptions)
