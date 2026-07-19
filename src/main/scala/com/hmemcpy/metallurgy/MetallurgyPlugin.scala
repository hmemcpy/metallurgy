package com.hmemcpy.metallurgy

import com.hmemcpy.metallurgy.module.{FirstDetectionNotifier, ModuleDetectionService}
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import org.jetbrains.plugins.scala.project._

final class MetallurgyStartupActivity extends StartupActivity {
  override def runActivity(project: Project): Unit = {
    MetallurgyPlugin.Log.info("Metallurgy loaded")
    val detection = ModuleDetectionService.get(project)
    // Scan existing modules for Scala 3.5+ eligibility and fire first-detection notification
    // for each one the user hasn't been asked about.
    ModuleManager.getInstance(project).getModules
      .filter(detection.isEligible)
      .foreach(FirstDetectionNotifier.notify)
  }
}

object MetallurgyPlugin {
  val Log: Logger = Logger.getInstance(classOf[MetallurgyStartupActivity])
}
