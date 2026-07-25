package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.vfs.VirtualFile

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Immutable source identity and text for one document version, with write-once memoized semantic queries. `sourceText`
  * is the verbatim document text the IntelliJ PSI holds; `compilerText` is what the Scala 3 compiler receives. They are
  * equal for the production identity projection and differ only when a compatibility fixture installs a projection that
  * makes illegal top-level expressions compile.
  */
final class PcSnapshot private (
    val fileUri: String,
    val documentVersion: Long,
    private[pc] val projection: PcCompilationProjection,
    initialAccessNanos: Long
):
  val sourceText: String   = projection.documentText
  val compilerText: String = projection.compilerText

  private val queryCache     = new ConcurrentHashMap[QueryKey, AnyRef]()
  private val lastAccessTime = new AtomicLong(initialAccessNanos)

  def isFor(version: Long): Boolean = documentVersion == version

  def isStale(currentVersion: Long): Boolean = !isFor(currentVersion)

  private[metallurgy] def cached[A](key: QueryKey, nowNanos: Long): Option[A] =
    touch(nowNanos)
    Option(queryCache.get(key)).map(_.asInstanceOf[A])

  private[metallurgy] def cache[A](key: QueryKey, value: A, nowNanos: Long): A =
    touch(nowNanos)
    val boxed    = value.asInstanceOf[AnyRef]
    val observed = queryCache.putIfAbsent(key, boxed)
    Option(observed).fold(value)(_.asInstanceOf[A])

  private[metallurgy] def cachedOrCompute[A](key: QueryKey, nowNanos: Long)(compute: => A): A =
    touch(nowNanos)
    queryCache
      .computeIfAbsent(key, _ => compute.asInstanceOf[AnyRef])
      .asInstanceOf[A]

  private[pc] def lastAccessNanos: Long = lastAccessTime.get()

  private[pc] def touch(nowNanos: Long): Unit =
    lastAccessTime.set(nowNanos)

object PcSnapshot:
  def apply(fileUri: String, documentVersion: Long, sourceText: String): PcSnapshot =
    apply(fileUri, documentVersion, PcCompilationProjection.identity(sourceText))

  def apply(fileUri: String, documentVersion: Long, projection: PcCompilationProjection): PcSnapshot =
    atTime(fileUri, documentVersion, projection, System.nanoTime())

  def forFile(file: VirtualFile, version: Long): PcSnapshot =
    val text = new String(file.contentsToByteArray, file.getCharset)
    apply(file.getUrl, version, text)

  private[pc] def atTime(
      fileUri: String,
      documentVersion: Long,
      projection: PcCompilationProjection,
      nowNanos: Long
  ): PcSnapshot =
    new PcSnapshot(fileUri, documentVersion, projection, nowNanos)

  private[pc] def atTime(
      fileUri: String,
      documentVersion: Long,
      sourceText: String,
      nowNanos: Long
  ): PcSnapshot =
    atTime(fileUri, documentVersion, PcCompilationProjection.identity(sourceText), nowNanos)
