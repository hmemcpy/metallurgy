package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.{OrderEnumerator, OrderRootType}
import org.jetbrains.plugins.scala.project._

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

final class PcSessionManager(project: Project) {

  private val sessions = new ConcurrentHashMap[String, PcSession]()

  def sessionFor(module: Module): Option[PcSession] = {
    val scalaVersion = module.scalaMinorVersion.map(_.minor).getOrElse(return None)
    Option(sessions.computeIfAbsent(scalaVersion, _ => createSession(module, scalaVersion)))
  }

  private def createSession(module: Module, scalaVersion: String): PcSession = {
    val fetcher = MtagsFetcher(project)
    val classpath = buildClasspath(module)
    PcSession.create(scalaVersion, classpath, module, fetcher)
  }

  private def buildClasspath(module: Module): Seq[File] = {
    val roots = OrderEnumerator.orderEntries(module)
      .recursively
      .classes
      .getRoots
    roots.toSeq.flatMap { vf =>
      if (vf != null && vf.isValid) {
        val path = vf.getPresentableUrl
        if (path != null) Some(new File(path)) else None
      } else None
    }
  }

  def invalidate(module: Module): Unit =
    module.scalaMinorVersion.foreach { v =>
      Option(sessions.remove(v.minor)).foreach(_.close())
    }

  def dispose(): Unit = {
    sessions.values().asScala.foreach(_.close())
    sessions.clear()
  }
}

object PcSessionManager {
  def get(project: Project): PcSessionManager = project.getService(classOf[PcSessionManager])
}
