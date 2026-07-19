package com.hmemcpy.metallurgy.module

import com.intellij.openapi.module.{Module, ModuleUtilCore}
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.project.ScalaLanguageLevels
import org.jetbrains.plugins.scala.project.ModuleExt

/** Detects Scala 3.5+ modules. Used as a fast-path gate inside every EP implementation
  * and as the trigger for first-detection notifications.
  */
final class ModuleDetectionService(project: Project) {

  def isEligible(module: Module): Boolean =
    module.hasScala &&
      module.scalaVersion.exists(v => v.major == 3 && v.minor >= 5)

  def isEligible(project: Project, filePath: String): Boolean =
    Option(ModuleUtilCore.findModuleForFile(
      com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(filePath),
      project
    )).exists(isEligible)
}

object ModuleDetectionService {
  def get(project: Project): ModuleDetectionService =
    project.getService(classOf[ModuleDetectionService])
}
