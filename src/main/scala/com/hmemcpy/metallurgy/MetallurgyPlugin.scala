package com.hmemcpy.metallurgy

import com.hmemcpy.metallurgy.module.{FirstDetectionNotifier, ModuleDetectionService}
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity
import com.intellij.openapi.util.Disposer
import org.jetbrains.plugins.scala.project._

final class MetallurgyStartupActivity extends StartupActivity {
  override def runActivity(project: Project): Unit = {
    MetallurgyPlugin.Log.info("Metallurgy loaded")
    val indicator = new java.lang.Object
    val thread = new Thread(() => {
      try { Thread.sleep(10000) } catch { case _: InterruptedException => return }
      if (project.isDisposed) return
      scanModules(project)
    }, "Metallurgy-Startup-Scan")
    thread.setDaemon(true)
    thread.start()
  }

  private def scanModules(project: Project): Unit = {
    try {
      val detection = ModuleDetectionService.get(project)
      ModuleManager.getInstance(project).getModules
        .filter(detection.isEligible)
        .foreach(FirstDetectionNotifier.notify)
    } catch {
      case e: Exception => MetallurgyPlugin.Log.warn(s"Module scan failed: $e")
    }
  }
}

object MetallurgyPlugin {
  val Log: Logger = Logger.getInstance(classOf[MetallurgyStartupActivity])
}
