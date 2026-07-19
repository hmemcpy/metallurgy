package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import org.eclipse.lsp4j.CompletionItem
import scala.meta.pc.{CancelToken, OffsetParams, PresentationCompiler}

import java.io.File
import java.net.{URI, URLClassLoader}
import java.util.ServiceLoader
import java.util.concurrent.{CompletableFuture, CompletionStage, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final class PcSession private (
    val scalaVersion: String,
    val classloader: URLClassLoader,
    val compilerClasspath: Seq[File],
    val compilerOptions: Seq[String]
) extends AutoCloseable:

  private val Log                  = Logger.getInstance(classOf[PcSession])
  private val presentationCompiler = new AtomicReference[Option[PresentationCompiler]](None)
  private val snapshots            = new PcSnapshotStore()
  private val closed               = new AtomicBoolean(false)

  /** Ask the isolated Metals presentation compiler for semantic completion items. No Scala or LSP4J value may cross the
    * classloader boundary.
    */
  private[metallurgy] def complete(
      fileUri: String,
      sourceText: String,
      documentVersion: Long,
      offset: Int
  ): Seq[PcCompletion] =
    val snapshot = snapshots.accept(PcSnapshot(fileUri, documentVersion, sourceText))
    val key      = QueryKey.Complete(offset)
    snapshot
      .cached[Seq[PcCompletion]](key, System.nanoTime())
      .getOrElse:
        if applicationIsDispatchThread then Seq.empty
        else
          snapshot.cachedOrCompute(key, System.nanoTime()):
            ProgressManager.checkCanceled()
            val future = compiler.complete(PcOffsetParams(URI.create(fileUri), sourceText, offset))
            try
              val completionList = future.get(5, TimeUnit.SECONDS)
              ProgressManager.checkCanceled()
              completionList.getItems.asScala.flatMap(decodeItem).toSeq
            catch
              case NonFatal(error) =>
                future.cancel(true)
                Log.warn(s"PC completion failed for $fileUri at $offset", error)
                Seq.empty

  private[metallurgy] def cachedType(snapshot: PcSnapshot, offset: Int)(compute: => Option[String]): Option[String] =
    val active = snapshots.accept(snapshot)
    val key    = QueryKey.TypeAt(offset)
    active
      .cached[Option[String]](key, System.nanoTime())
      .getOrElse:
        if applicationIsDispatchThread then None
        else active.cachedOrCompute(key, System.nanoTime())(compute)

  private[metallurgy] def snapshotCount: Int = snapshots.size

  override def close(): Unit =
    if closed.compareAndSet(false, true) then
      presentationCompiler.getAndSet(None).foreach(shutdown)
      snapshots.clear()
      try classloader.close()
      catch case NonFatal(error) => Log.warn("Error closing PcSession classloader", error)

  private[pc] def isClosed: Boolean = closed.get()

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
