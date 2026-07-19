package com.hmemcpy.metallurgy

import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/** Project-level entry point. Registered via `<postStartupActivity>` in plugin.xml.
  *
  * Uses the (deprecated but functional) `StartupActivity` interface rather than
  * `ProjectActivity`, because the latter is a Kotlin suspend interface that's
  * awkward to implement from Scala 2.13.
  */
final class MetallurgyStartupActivity extends StartupActivity {
  override def runActivity(project: Project): Unit = {
    MetallurgyPlugin.log(project, "Metallurgy loaded; Phase 0 scaffold only, no features enabled.")
    ModuleDetectionService.get(project) // eager init kicks notification flow
  }
}

object MetallurgyPlugin {
  private val Log: Logger = Logger.getInstance(classOf[MetallurgyStartupActivity])

  def log(project: Project, msg: String): Unit = Log.info(msg)
}
