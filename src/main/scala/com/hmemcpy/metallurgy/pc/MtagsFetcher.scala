package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project

import java.io.File
import java.nio.file.Path
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap}

/** Resolves and caches the Scala 3 presentation compiler distribution for an exact Scala version. */
final class MtagsFetcher private[pc] (
    cache: PcArtifactCache,
    resolver: PresentationCompilerResolver,
    backgroundRunner: BackgroundRunner
):

  def this(project: Project) =
    this(
      PcArtifactCache(PathManager.getSystemDir.resolve("caches/metallurgy/presentation-compiler")),
      PresentationCompilerResolver.bundled,
      BackgroundRunner.intellij(project)
    )

  private val inFlight = new ConcurrentHashMap[String, CompletableFuture[Path]]()

  /** Returns the directory containing the exact Scala version's presentation compiler and transitive dependencies. */
  def jarsFor(scalaVersion: String): CompletableFuture[Path] =
    jarsIfCached(scalaVersion)
      .map(CompletableFuture.completedFuture)
      .getOrElse:
        val future = inFlight.computeIfAbsent(scalaVersion, version => resolveAndCache(version))
        future.whenComplete: (_, _) =>
          inFlight.remove(scalaVersion, future)
        future

  /** Returns a validated warm-cache directory without starting network or background work. */
  def jarsIfCached(scalaVersion: String): Option[Path] =
    cache.validDirectory(scalaVersion)

  private[pc] def cachedJars(scalaVersion: String): Option[Seq[File]] =
    cache.validArtifacts(scalaVersion).map(_.map(_.toFile))

  private def resolveAndCache(scalaVersion: String): CompletableFuture[Path] =
    val future = backgroundRunner.submit(s"Downloading Scala $scalaVersion presentation compiler"): () =>
      resolver
        .resolve(scalaVersion)
        .flatMap(cache.store(scalaVersion, _))
        .fold(error => throw error.toException, identity)

    future

object MtagsFetcher:
  def apply(project: Project): MtagsFetcher = project.getService(classOf[MtagsFetcher])

  def cachedJars(fetcher: MtagsFetcher, scalaVersion: String): Option[Array[File]] =
    fetcher.cachedJars(scalaVersion).map(_.toArray)
