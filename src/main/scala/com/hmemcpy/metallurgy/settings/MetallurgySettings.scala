package com.hmemcpy.metallurgy.settings

import com.intellij.openapi.components.{PersistentStateComponent, Service, State, Storage}
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project

import scala.beans.BooleanBeanProperty
import scala.jdk.CollectionConverters._

@Service(Array(Service.Level.PROJECT))
@State(
  name     = "MetallurgySettings",
  storages = Array(new Storage("metallurgy.xml"))
)
final class MetallurgySettings(project: Project) extends PersistentStateComponent[MetallurgySettings.State] {

  private[this] var myState: MetallurgySettings.State = new MetallurgySettings.State

  def isGloballyEnabled: Boolean = myState.globallyEnabled
  def setGloballyEnabled(v: Boolean): Unit = myState.globallyEnabled = v

  def isEnabled(module: Module): Boolean =
    isGloballyEnabled || myState.enabledModules.asScala.contains(module.getName)

  def setEnabled(module: Module, enabled: Boolean): Unit =
    setEnabled(module.getName, enabled)

  def setEnabled(moduleName: String, enabled: Boolean): Unit = {
    val set = myState.enabledModules
    if (enabled) set.add(moduleName) else set.remove(moduleName)
    myState.neverAskModules.remove(moduleName)
  }

  def neverAsk(moduleName: String): Unit = myState.neverAskModules.add(moduleName)
  def shouldAsk(moduleName: String): Boolean = !myState.neverAskModules.asScala.contains(moduleName)

  override def getState: MetallurgySettings.State = myState
  override def loadState(loaded: MetallurgySettings.State): Unit = myState = loaded
}

object MetallurgySettings {
  class State {
    @BooleanBeanProperty var globallyEnabled: Boolean = false
    var enabledModules: java.util.Set[String] = new java.util.HashSet[String]()
    var neverAskModules: java.util.Set[String] = new java.util.HashSet[String]()
  }

  def apply(project: Project): MetallurgySettings =
    project.getService(classOf[MetallurgySettings])
}
