package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.progress.{ProgressIndicator, Task}
import com.intellij.openapi.project.Project
import com.intellij.util.io.HttpRequests
import org.jetbrains.plugins.scala.ScalaVersion

import java.io.File
import java.nio.file.{Files, Path}
import java.util.concurrent.{CompletableFuture, ConcurrentHashMap}

final class MtagsFetcher(project: Project) {

  private val cacheDir: Path =
    Path.of(System.getProperty("user.home"), ".cache", "metallurgy", "mtags")

  private val fetching = new ConcurrentHashMap[String, CompletableFuture[Path]]()

  def cacheDirFor(scalaVersion: String): Path = cacheDir.resolve(sanitize(scalaVersion))

  def isCached(scalaVersion: String): Boolean = {
    val dir = cacheDirFor(scalaVersion)
    RequiredArtifacts.forVersion(scalaVersion).forall(a => Files.exists(dir.resolve(a.jarName)))
  }

  def ensureCached(scalaVersion: String): CompletableFuture[Path] = {
    if (isCached(scalaVersion)) return CompletableFuture.completedFuture(cacheDirFor(scalaVersion))
    fetching.computeIfAbsent(scalaVersion, _ => fetchAsync(scalaVersion))
  }

  private def fetchAsync(scalaVersion: String): CompletableFuture[Path] = {
    val future = new CompletableFuture[Path]()
    val artifacts = RequiredArtifacts.forVersion(scalaVersion)
    val target = cacheDirFor(scalaVersion)
    Files.createDirectories(target)

    new Task.Backgroundable(project, s"Downloading Scala $scalaVersion presentation compiler", true) {
      override def run(indicator: ProgressIndicator): Unit = {
        try {
          artifacts.foreach { artifact =>
            indicator.setText(s"Downloading ${artifact.jarName}")
            val dest = target.resolve(artifact.jarName)
            if (!Files.exists(dest))
              HttpRequests.request(artifact.url(scalaVersion)).connect(request =>
                request.saveToFile(dest, indicator)
              )
          }
          future.complete(target)
        } catch {
          case e: Exception => future.completeExceptionally(e)
        } finally {
          fetching.remove(scalaVersion)
        }
      }
    }.queue()
    future
  }

  private def sanitize(version: String): String =
    version.replaceAll("[^a-zA-Z0-9._-]", "_")
}

object MtagsFetcher {
  def apply(project: Project): MtagsFetcher = project.getService(classOf[MtagsFetcher])

  def cachedJars(fetcher: MtagsFetcher, scalaVersion: String): Option[Array[File]] = {
    if (!fetcher.isCached(scalaVersion)) return None
    val dir = fetcher.cacheDirFor(scalaVersion)
    Some(dir.toFile.listFiles.filter(_.getName.endsWith(".jar")))
  }
}

object RequiredArtifacts {
  case class Artifact(groupId: String, artifactId: String) {
    def jarName: String = s"$artifactId.jar"
    def url(version: String): String =
      s"https://repo1.maven.org/maven2/${groupId.replace('.', '/')}/$artifactId/$version/$artifactId-$version.jar"
  }

  def forVersion(scalaVersion: String): Seq[Artifact] = Seq(
    Artifact("org.scala-lang", s"scala3-compiler_3"),
    Artifact("org.scala-lang", s"scala3-library_3"),
    Artifact("org.scala-lang", "scala3-interfaces"),
    Artifact("org.scala-lang", s"scala3-tasty-inspector_3"),
    Artifact("org.scala-lang", "scala-library")
  )
}
