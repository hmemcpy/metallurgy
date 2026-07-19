package com.hmemcpy.metallurgy

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer

/** Project-level entry point. Runs once per project open. */
final class MetallurgyProjectActivity extends ProjectActivity {
  override def execute(project: Project): Unit = {
    MetallurgyPlugin.log(project, "Metallurgy loaded; Phase 0 scaffold only, no features enabled.")
    ModuleDetectionService.get(project) // eager init kicks notification flow
  }
}

object MetallurgyPlugin {
  def log(project: Project, msg: String): Unit = {
    com.intellij.openapi.diagnostic.Logger.getInstance(classOf[MetallurgyProjectActivity]).info(msg)
  }
}
