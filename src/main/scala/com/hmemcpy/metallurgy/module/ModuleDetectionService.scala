package com.hmemcpy.metallurgy.module

import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.module.{Module, ModuleUtilCore}
import com.intellij.openapi.project.Project
import com.intellij.ProjectTopics
import com.intellij.openapi.roots.{ModuleRootEvent, ModuleRootListener}
import com.intellij.openapi.vfs.VirtualFile

import java.util.concurrent.ConcurrentHashMap

final class ModuleDetectionService(project: Project) extends Disposable:

  private val cache = new ConcurrentHashMap[Module, java.lang.Boolean]()

  locally {
    val connection = project.getMessageBus.connect(this)
    subscribeRootsChanged(connection)
  }

  @scala.annotation.nowarn("cat=deprecation")
  private def subscribeRootsChanged(connection: com.intellij.util.messages.MessageBusConnection): Unit =
    connection.subscribe(
      ProjectTopics.PROJECT_ROOTS,
      new ModuleRootListener {
        override def rootsChanged(event: ModuleRootEvent): Unit = cache.clear()
      }
    )

  def isEligible(module: Module): Boolean =
    cache.computeIfAbsent(module, m => java.lang.Boolean.valueOf(computeEligible(m)))

  private def computeEligible(module: Module): Boolean =
    BundledPluginBridge.getScalaVersion(module) match
      case v if v != null && v.startsWith("3.") =>
        val major = v.split("\\.")
        major.length >= 2 && major(1).toInt >= 5
      case _                                    => false

  def isEligibleFile(file: VirtualFile): Boolean =
    Option(ModuleUtilCore.findModuleForFile(file, project)).exists(isEligible)

  /** Metallurgy is active for a module iff it is a Scala 3.5+ module the user opted into AND compiler-based
    * highlighting is on. `pc` piggybacks on the compile server's compiled artifacts, so without CBH Metallurgy is a
    * complete no-op (ADR 0008).
    */
  def isActive(module: Module): Boolean =
    isEligible(module) &&
      MetallurgySettings(project).isEnabled(module) &&
      BundledPluginBridge.usesCompilerTypes(project)

  override def dispose(): Unit = cache.clear()

object ModuleDetectionService:
  def get(project: Project): ModuleDetectionService = project.getService(classOf[ModuleDetectionService])
