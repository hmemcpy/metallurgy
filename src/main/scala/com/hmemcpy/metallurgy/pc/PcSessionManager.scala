package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.module.BundledPluginBridge
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

final class PcSessionManager(project: Project):

  private val sessions = new ConcurrentHashMap[String, PcSession]()

  def sessionFor(module: Module): Option[PcSession] =
    Option(BundledPluginBridge.getScalaVersion(module)).flatMap { scalaVersion =>
      val sessionKey = s"${module.getName}:$scalaVersion"
      Option(sessions.computeIfAbsent(sessionKey, _ => createSession(module, scalaVersion)))
    }

  private def createSession(module: Module, scalaVersion: String): PcSession =
    val fetcher   = MtagsFetcher(project)
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
