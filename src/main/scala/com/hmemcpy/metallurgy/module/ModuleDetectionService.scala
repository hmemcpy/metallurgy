package com.hmemcpy.metallurgy.module

import com.intellij.openapi.module.{Module, ModuleUtilCore}
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.project._

/** Detects Scala 3.5+ modules. Used as a fast-path gate inside every EP implementation
  * and as the trigger for first-detection notifications.
  *
  * Per ADR 0001 — Scala 3.5.0 is the floor because BETASTy (`-Ybest-effort` /
  * `-Ywith-best-effort-tasty`) lands there and is the core primitive for cross-module
  * error recovery.
  */
final class ModuleDetectionService(private val project: Project) {

  /** A module is eligible iff it has a Scala SDK whose version is Scala 3.5.0 or later. */
  def isEligible(module: Module): Boolean =
    module.hasScala && module.scalaMinorVersion.exists(v =>
      v.isScala3 && v >= ScalaVersion.Latest.Scala_3_5
    )

  /** Convenience overload for code paths that have a VirtualFile but not a Module. */
  def isEligibleFile(project: Project, file: VirtualFile): Boolean =
    Option(ModuleUtilCore.findModuleForFile(file, project)).exists(isEligible)
}

object ModuleDetectionService {
  def get(project: Project): ModuleDetectionService =
    project.getService(classOf[ModuleDetectionService])
}
