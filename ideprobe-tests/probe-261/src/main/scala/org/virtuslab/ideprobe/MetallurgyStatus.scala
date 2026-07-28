package org.virtuslab.ideprobe

import java.lang.reflect.Method

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.module.ModuleManager

import org.virtuslab.ideprobe.handlers.{IntelliJApi, PSI, Projects}
import org.virtuslab.ideprobe.protocol.FileRef

object MetallurgyStatus extends IntelliJApi {
  private val PluginIdValue = PluginId.getId("com.hmemcpy.metallurgy")

  def inspect(fileRef: FileRef): Map[String, String] = {
    val project = Projects.resolve(fileRef.project)
    val loader = Option(PluginManagerCore.getPlugin(PluginIdValue))
      .map(_.getPluginClassLoader)
      .getOrElse(error("Metallurgy plugin is not loaded"))
    val settingsClass = Class.forName("com.hmemcpy.metallurgy.settings.MetallurgySettings", true, loader)
    val lifecycleClass = Class.forName(
      "com.hmemcpy.metallurgy.psiproducer.Scala3ParserPreparationLifecycle",
      true,
      loader
    )
    val settings = project.getService(settingsClass.asInstanceOf[Class[AnyRef]])
    val lifecycle = project.getService(lifecycleClass.asInstanceOf[Class[AnyRef]])
    val globallyEnabled = invoke(settingsClass, settings, "isGloballyEnabled").toString
    val stateFor = lifecycleClass.getMethod("stateFor", classOf[com.intellij.openapi.module.Module])
    val moduleStates = ModuleManager
      .getInstance(project)
      .getModules
      .toVector
      .map(module => s"module.${module.getName}" -> stateFor.invoke(lifecycle, module).toString)
    val language = read(PSI.resolve(fileRef).getLanguage.getDisplayName)
    (Vector("globallyEnabled" -> globallyEnabled, "language" -> language) ++ moduleStates).toMap
  }

  private def invoke(owner: Class[_], receiver: AnyRef, name: String): AnyRef = {
    val method: Method = owner.getMethod(name)
    method.invoke(receiver)
  }
}
