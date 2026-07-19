package com.hmemcpy.metallurgy.feature

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module

/** Adds BETASTy scalac flags to opted-in modules on enable, removes them on disable.
  *
  * The actual flag manipulation uses the bundled plugin's `ScalaCompilerConfiguration`
  * API. Implementation deferred to runtime testing — the exact profile/module
  * settings API surface varies between bundled-plugin versions and needs to be
  * validated against a real module in a running IDE.
  */
object ScalacFlagsManager {

  private val Log = Logger.getInstance("com.hmemcpy.metallurgy.feature.ScalacFlagsManager")

  val FlagsToAdd: Seq[String] = Seq("-Ybest-effort", "-Ywith-best-effort-tasty")
  val OptionalFlags: Seq[String] = Seq("-Xsemanticdb")

  def onEnable(module: Module): Unit =
    Log.info(s"ScalacFlagsManager: enabling BETASTy flags for module ${module.getName}")

  def onDisable(module: Module): Unit =
    Log.info(s"ScalacFlagsManager: disabling BETASTy flags for module ${module.getName}")
}
