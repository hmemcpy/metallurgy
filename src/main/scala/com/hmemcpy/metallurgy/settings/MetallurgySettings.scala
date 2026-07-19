package com.hmemcpy.metallurgy.settings

import com.intellij.openapi.components._
import com.intellij.openapi.project.Project

/** Persistent project-level opt-in state for Metallurgy.
  *
  * Per ADR 0006 — distinct from the bundled plugin's `ScalaProjectSettings.isUseCompilerTypes`,
  * which we leave entirely alone.
  */
@volatile
final class MetallurgySettings {
  @volatile var globallyEnabled: Boolean = false
  @volatile var enabledModules: java.util.Set[String] = new java.util.HashSet()
}

object MetallurgySettings {
  def get(project: Project): MetallurgySettings =
    project.getService(classOf[MetallurgySettings])
}
