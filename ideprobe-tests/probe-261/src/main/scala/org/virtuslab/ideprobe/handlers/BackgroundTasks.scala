package org.virtuslab.ideprobe.handlers

import scala.jdk.CollectionConverters._

import com.intellij.openapi.progress.impl.CoreProgressManager
import com.intellij.openapi.project.{DumbService, ProjectManager}

object BackgroundTasks {
  def currentBackgroundTasks(): Seq[String] = {
    val indicators = CoreProgressManager
      .getCurrentIndicators
      .asScala
      .map(indicator => Option(indicator.toString).getOrElse("<unnamed progress>"))
      .toVector
    val indexing   = ProjectManager
      .getInstance
      .getOpenProjects
      .toVector
      .filter(project => !project.isDisposed && DumbService.getInstance(project).isDumb)
      .map(project => s"Indexing project ${project.getName}")
    indicators ++ indexing
  }
}
