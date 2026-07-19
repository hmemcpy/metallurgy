package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import org.jetbrains.plugins.scala.project._

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

final class PcSessionManager(project: Project):

  private val sessions = new ConcurrentHashMap[String, PcSession]()

  def sessionFor(module: Module): Option[PcSession] =
    module.scalaMinorVersion.flatMap { v =>
      Option(sessions.computeIfAbsent(v.minor, _ => createSession(module, v.minor)))
    }

  private def createSession(module: Module, scalaVersion: String): PcSession =
    val fetcher = MtagsFetcher(project)
    val classpath = buildClasspath(module)
    PcSession.create(scalaVersion, classpath, module, fetcher)

  private def buildClasspath(module: Module): Seq[File] =
    OrderEnumerator.orderEntries(module).recursively.classes.getRoots.toSeq.flatMap { vf =>
      if vf != null && vf.isValid then
        Option(vf.getPresentableUrl).map(new File(_))
      else None
    }

  def invalidate(module: Module): Unit =
    module.scalaMinorVersion.foreach { v =>
      Option(sessions.remove(v.minor)).foreach(_.close())
    }

  def dispose(): Unit =
    sessions.values().asScala.foreach(_.close())
    sessions.clear()

object PcSessionManager:
  def get(project: Project): PcSessionManager = project.getService(classOf[PcSessionManager])
