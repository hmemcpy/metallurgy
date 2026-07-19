package com.hmemcpy.metallurgy

import com.intellij.openapi.project.Project
import com.intellij.util.JavaCoroutines
import kotlin.coroutines.Continuation

/** Scala 3 shim for IntelliJ's suspending project-activity API. Ported from the bundled Scala plugin's
  * `org.jetbrains.plugins.scala.startup.ProjectActivity`.
  */
private[metallurgy] trait ScalaProjectActivity extends com.intellij.openapi.startup.ProjectActivity:
  def execute(project: Project): Unit

  final override def execute(
      project: Project,
      continuation: Continuation[? >: kotlin.Unit]
  ): AnyRef =
    JavaCoroutines.suspendJava[kotlin.Unit](
      callback => {
        execute(project)
        callback.resume(kotlin.Unit.INSTANCE)
      },
      continuation
    )
