package com.hmemcpy.metallurgy.settings

import com.hmemcpy.metallurgy.build.ScalacFlagsService
import com.hmemcpy.metallurgy.compilerbackend.Scala3CompilerBackend
import com.intellij.openapi.components.{PersistentStateComponent, State, Storage}
import com.intellij.openapi.module.{Module, ModuleManager}
import com.intellij.openapi.project.Project

import scala.beans.{BeanProperty, BooleanBeanProperty}
import scala.jdk.CollectionConverters._

@State(
  name = "MetallurgySettings",
  storages = Array(new Storage(value = "metallurgy.xml"))
)
final class MetallurgySettings(project: Project) extends PersistentStateComponent[MetallurgySettings.State]:

  private var myState: MetallurgySettings.State = new MetallurgySettings.State

  def isGloballyEnabled: Boolean = myState.globallyEnabled

  def setGloballyEnabled(enabled: Boolean): Unit =
    myState.globallyEnabled = enabled
    ModuleManager
      .getInstance(project)
      .getModules
      .foreach: module =>
        if enabled then ScalacFlagsService.get(project).enableFor(module)
        else if !myState.enabledModules.contains(module.getName) then
          clearCompilerBackend(module)
          ScalacFlagsService.get(project).disableFor(module)

  def isEnabled(module: Module): Boolean =
    isGloballyEnabled || myState.enabledModules.contains(module.getName)

  def setEnabled(module: Module, enabled: Boolean): Unit =
    setEnabled(module.getName, enabled)
    if !isEnabled(module) then clearCompilerBackend(module)
    if isEnabled(module) then ScalacFlagsService.get(project).enableFor(module)
    else ScalacFlagsService.get(project).disableFor(module)

  def setEnabled(moduleName: String, enabled: Boolean): Unit =
    val set = myState.enabledModules
    val _   = if enabled then set.add(moduleName) else set.remove(moduleName)
    val _   = myState.neverAskModules.remove(moduleName)

  def neverAsk(moduleName: String): Unit =
    val _ = myState.neverAskModules.add(moduleName)

  def shouldAsk(moduleName: String): Boolean = !myState.neverAskModules.asScala.contains(moduleName)

  def isXsemanticdbEnabled: Boolean = myState.xsemanticdbEnabled

  def setXsemanticdbEnabled(enabled: Boolean): Unit =
    myState.xsemanticdbEnabled = enabled
    ModuleManager
      .getInstance(project)
      .getModules
      .filter(isEnabled)
      .foreach(ScalacFlagsService.get(project).enableFor)

  private def clearCompilerBackend(module: Module): Unit =
    Option(project.getServiceIfCreated(classOf[Scala3CompilerBackend])).foreach(_.clear(module))

  override def getState: MetallurgySettings.State                = myState
  override def loadState(loaded: MetallurgySettings.State): Unit = myState = loaded

object MetallurgySettings:
  class State:
    @BooleanBeanProperty var globallyEnabled: Boolean        = false
    @BooleanBeanProperty var xsemanticdbEnabled: Boolean     = false
    @BeanProperty var enabledModules: java.util.Set[String]  = new java.util.HashSet[String]()
    @BeanProperty var neverAskModules: java.util.Set[String] = new java.util.HashSet[String]()

  def apply(project: Project): MetallurgySettings =
    project.getService(classOf[MetallurgySettings])
