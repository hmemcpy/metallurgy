package com.hmemcpy.metallurgy.module

import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge
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
    val cached = cache.get(module)
    if cached != null then cached.booleanValue
    else
      val computed = java.lang.Boolean.valueOf(computeEligible(module))
      val previous = cache.putIfAbsent(module, computed)
      if previous == null then computed.booleanValue else previous.booleanValue

  private def computeEligible(module: Module): Boolean =
    ModuleDetectionService.isScala3Version(ScalaPluginSemanticBridge.getScalaVersion(module))

  def isEligibleFile(file: VirtualFile): Boolean =
    Option(ModuleUtilCore.findModuleForFile(file, project)).exists(isEligible)

  /** Metallurgy is active for a module iff it uses Scala 3, the user enabled the backend, and compiler-based
    * highlighting is on. Exact presentation-compiler artifact resolution determines backend availability
    * asynchronously; optional facilities such as BETASTY are discovered independently.
    */
  def isActive(module: Module): Boolean =
    isEligible(module) &&
      MetallurgySettings(project).isEnabled(module) &&
      ScalaPluginSemanticBridge.usesCompilerTypes(project)

  override def dispose(): Unit = cache.clear()

object ModuleDetectionService:
  private[module] def isScala3Version(version: String): Boolean =
    version != null && (version == "3" || version.startsWith("3."))

  def get(project: Project): ModuleDetectionService = project.getService(classOf[ModuleDetectionService])
