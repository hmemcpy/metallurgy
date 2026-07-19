package com.hmemcpy.metallurgy.module

import com.intellij.openapi.Disposable
import com.intellij.openapi.module.{Module, ModuleUtilCore}
import com.intellij.openapi.project.Project
import com.intellij.ProjectTopics
import com.intellij.openapi.roots.{ModuleRootEvent, ModuleRootListener}
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.project._

import java.util.concurrent.ConcurrentHashMap

/** Detects Scala 3.5+ modules. Used as a fast-path gate inside every EP implementation
  * and as the trigger for first-detection notifications.
  *
  * Per ADR 0001 — Scala 3.5.0 is the floor because BETASTy (`-Ybest-effort` /
  * `-Ywith-best-effort-tasty`) lands there and is the core primitive for cross-module
  * error recovery.
  */
final class ModuleDetectionService(project: Project) extends Disposable {

  private val cache = new ConcurrentHashMap[Module, java.lang.Boolean]()

  /** The fixed Scala 3.5.0 floor. Anything >= this is eligible (per ADR 0001). */
  private val floor: ScalaVersion =
    new ScalaVersion(org.jetbrains.plugins.scala.project.ScalaLanguageLevel.Scala_3_5, "0")

  // Wire invalidation: clear the whole cache on any roots change. This covers:
  //   - sbt/BSP reimport changing the Scala SDK on a module
  //   - module add/remove/rename (these propagate through roots-changed in 2026.1
  //     since the WorkspaceModel bridge fires roots events for structural changes).
  //
  // `ProjectTopics.PROJECT_ROOTS` is the legacy API; the modern equivalent is
  // `WorkspaceModelTopics.CHANGED`. The legacy API still works in 2026.1 and is what
  // the bundled Scala plugin itself uses today. Suppressed deprecation until we
  // intentionally migrate to the workspace-model topic.
  locally {
    val connection = project.getMessageBus.connect(this)
    subscribeRootsChanged(connection)
  }

  @scala.annotation.nowarn("cat=deprecation")
  private def subscribeRootsChanged(connection: com.intellij.util.messages.MessageBusConnection): Unit =
    connection.subscribe(ProjectTopics.PROJECT_ROOTS, new ModuleRootListener {
      override def rootsChanged(event: ModuleRootEvent): Unit = cache.clear()
    })

  /** A module is eligible iff it has a Scala SDK whose version is Scala 3.5.0 or later.
    *
    * Uses `ScalaVersion.Latest.Scala_3_5` (defined in the bundled plugin's
    * `LatestScalaVersions`) as the floor. `ScalaVersion` is `Ordered`, so `>=` is
    * well-defined. `isScala3` short-circuits Scala 2 modules before the version compare.
    */
  def isEligible(module: Module): Boolean =
    cache.computeIfAbsent(module, computeEligibility).booleanValue()

  private def computeEligibility(module: Module): java.lang.Boolean = {
    val v = module.scalaMinorVersion
    val eligible = module.hasScala && v.exists(version =>
      version.isScala3 && version >= floor
    )
    java.lang.Boolean.valueOf(eligible)
  }

  /** Convenience overload for code paths that have a VirtualFile but not a Module. */
  def isEligibleFile(file: VirtualFile): Boolean =
    Option(ModuleUtilCore.findModuleForFile(file, project)).exists(isEligible)

  override def dispose(): Unit = cache.clear()
}

object ModuleDetectionService {
  def get(project: Project): ModuleDetectionService =
    project.getService(classOf[ModuleDetectionService])
}


