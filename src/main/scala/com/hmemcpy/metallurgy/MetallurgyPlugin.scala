package com.hmemcpy.metallurgy

import com.hmemcpy.metallurgy.feature.compilertype.CompilerTypeRequestResolver
import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge
import com.hmemcpy.metallurgy.module.{FirstDetectionNotifier, ModuleDetectionService}
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.{DumbService, Project}

final class MetallurgyProjectActivity extends ScalaProjectActivity:
  override def execute(project: Project): Unit =
    MetallurgyPlugin.Log.info("Metallurgy loaded")
    val shimStatus = ScalaPluginSemanticBridge.install()
    if !shimStatus.isEnabled then MetallurgyPlugin.Log.warn(s"Scala 3 compiler backend shim disabled: $shimStatus")
    val _          = CompilerTypeRequestResolver(project) // eagerly subscribe to compiler-type requests
    DumbService
      .getInstance(project)
      .runWhenSmart: () =>
        if !project.isDisposed then scanModules(project)

  private def scanModules(project: Project): Unit =
    try
      val detection       = ModuleDetectionService.get(project)
      val eligibleModules = ModuleManager
        .getInstance(project)
        .getModules
        .filter(detection.isEligible)
        .toSeq
      FirstDetectionNotifier.notify(eligibleModules)
    catch case e: Exception => MetallurgyPlugin.Log.warn(s"Module scan failed: $e")

object MetallurgyPlugin:
  val Log: Logger = Logger.getInstance(classOf[MetallurgyProjectActivity])
