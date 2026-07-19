package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.module.BundledPluginBridge
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

final class PcSessionManager(project: Project):

  private val log      = Logger.getInstance(classOf[PcSessionManager])
  private val fetcher  = MtagsFetcher(project)
  private val sessions = new ConcurrentHashMap[String, PcSession]()

  def sessionFor(module: Module): Option[PcSession] =
    Option(BundledPluginBridge.getScalaVersion(module)).flatMap { scalaVersion =>
      fetcher.jarsIfCached(scalaVersion) match
        case Some(_) =>
          val sessionKey = s"${module.getName}:$scalaVersion"
          Option(sessions.computeIfAbsent(sessionKey, _ => createSession(module, scalaVersion)))
        case None    =>
          fetcher
            .jarsFor(scalaVersion)
            .whenComplete: (_, error) =>
              if error != null then log.warn(s"Could not prepare the Scala $scalaVersion presentation compiler", error)
          None
    }

  private def createSession(module: Module, scalaVersion: String): PcSession =
    val classpath = buildClasspath(module)
    PcSession.create(scalaVersion, classpath, module, fetcher)

  private def buildClasspath(module: Module): Seq[File] =
    OrderEnumerator
      .orderEntries(module)
      .recursively
      .classes
      .getPathsList
      .getPathList
      .asScala
      .map(new File(_))
      .toSeq

  def invalidate(module: Module): Unit =
    Option(BundledPluginBridge.getScalaVersion(module))
      .flatMap(scalaVersion => Option(sessions.remove(s"${module.getName}:$scalaVersion")))
      .foreach(_.close())

  def dispose(): Unit =
    sessions.values().asScala.foreach(_.close())
    sessions.clear()

object PcSessionManager:
  def get(project: Project): PcSessionManager = project.getService(classOf[PcSessionManager])
