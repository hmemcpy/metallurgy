package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.module.Module
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import org.eclipse.lsp4j.CompletionItem
import scala.meta.pc.{CancelToken, OffsetParams, PresentationCompiler}

import java.io.File
import java.net.{URI, URLClassLoader}
import java.util.ServiceLoader
import java.util.concurrent.{CompletableFuture, CompletionStage, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final class PcSession private (
    val scalaVersion: String,
    val classloader: URLClassLoader,
    val compilerClasspath: Seq[File]
) extends AutoCloseable:

  private val Log                  = Logger.getInstance(classOf[PcSession])
  private val presentationCompiler = new AtomicReference[Option[PresentationCompiler]](None)

  /** Ask the isolated Metals presentation compiler for semantic completion items. No Scala or LSP4J value may cross the
    * classloader boundary.
    */
  private[metallurgy] def complete(fileUri: String, sourceText: String, offset: Int): Seq[PcCompletion] =
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

  override def close(): Unit =
    presentationCompiler.getAndSet(None).foreach(shutdown)
    try classloader.close()
    catch case NonFatal(error) => Log.warn("Error closing PcSession classloader", error)

  private def compiler: PresentationCompiler =
    val observed = presentationCompiler.get()
    observed.getOrElse:
      val created = createCompiler()
      if presentationCompiler.compareAndSet(observed, Some(created)) then created
      else
        shutdown(created)
        presentationCompiler.get().getOrElse(createCompiler())

  private def createCompiler(): PresentationCompiler =
    val providers = ServiceLoader.load(classOf[PresentationCompiler], classloader).iterator().asScala
    val prototype = providers
      .nextOption()
      .getOrElse:
        throw new IllegalStateException("No Scala presentation compiler provider found")

    prototype.newInstance(
      s"metallurgy-$scalaVersion",
      compilerClasspath.map(_.toPath).asJava,
      Seq.empty[String].asJava
    )

  private def decodeItem(item: CompletionItem): Option[PcCompletion] =
    Option(item.getLabel).map: label =>
      val filterText = Option(item.getFilterText)
      val detail     = Option(item.getDetail)
      PcCompletion(filterText.getOrElse(label.takeWhile(_ != '(')), label, detail)

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

/** Child-first for the Scala compiler implementation, parent-first only for the stable Java presentation-compiler API
  * and IntelliJ's LSP4J classes.
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
  private val SharedApiPrefixes = Seq("scala.meta.pc.", "org.eclipse.lsp4j.")

  def isSharedApi(className: String): Boolean =
    SharedApiPrefixes.exists(className.startsWith)

object PcSession:
  private val Log = Logger.getInstance(classOf[PcSession])

  def create(
      scalaVersion: String,
      classpath: Seq[File],
      module: Module,
      fetcher: MtagsFetcher
  ): PcSession =
    val cachedJars = MtagsFetcher.cachedJars(fetcher, scalaVersion) match
      case Some(jars) => jars
      case None       =>
        Log.warn(s"mtags not cached for Scala $scalaVersion; PcSession will be non-functional until download completes")
        Array.empty[File]

    val urls        = (cachedJars ++ classpath).map(_.toURI.toURL).toArray
    val classloader = new PcClassLoader(urls, classOf[PcSession].getClassLoader)

    new PcSession(scalaVersion, classloader, classpath)
