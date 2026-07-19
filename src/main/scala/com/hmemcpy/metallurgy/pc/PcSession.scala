package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.module.Module
import com.intellij.openapi.diagnostic.Logger

import java.io.File
import java.net.{URL, URLClassLoader}
import java.util.ServiceLoader
import scala.jdk.CollectionConverters._
import scala.util.Try

final class PcSession private (
  val scalaVersion: String,
  val classloader: URLClassLoader
) extends AutoCloseable {

  private val Log = Logger.getInstance(classOf[PcSession])

  // The PresentationCompiler interface is loaded from the mtags-interfaces jar
  // (which is on the plugin's own classpath, not the session classloader).
  // The implementation is loaded from the scala3-compiler jar inside the session classloader.
  // Due to classloader isolation, we use reflection to bridge the two.

  def close(): Unit = {
    Try(classloader.close()).fold(
      e => Log.warn(s"Error closing PcSession classloader: $e"),
      _ => Log.debug(s"Closed PcSession for Scala $scalaVersion")
    )
  }
}

object PcSession {
  private val Log = Logger.getInstance(classOf[PcSession])

  def create(
    scalaVersion: String,
    classpath: Seq[File],
    module: Module,
    fetcher: MtagsFetcher
  ): PcSession = {
    val cachedJars = MtagsFetcher.cachedJars(fetcher, scalaVersion) match {
      case Some(jars) => jars
      case None =>
        Log.warn(s"mtags not cached for Scala $scalaVersion; PcSession will be non-functional until download completes")
        Array.empty[File]
    }

    val urls = (cachedJars ++ classpath).map(_.toURI.toURL).toArray
    val parent = classOf[PcSession].getClassLoader
    val classloader = new URLClassLoader(urls, parent)

    new PcSession(scalaVersion, classloader)
  }
}
