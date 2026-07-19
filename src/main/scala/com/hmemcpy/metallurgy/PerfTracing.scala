package com.hmemcpy.metallurgy

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.registry.Registry

object PerfTracing {

  private val Log = Logger.getInstance("com.hmemcpy.metallurgy.PerfTracing")

  def isEnabled: Boolean =
    Registry.get("metallurgy.tracing").asBoolean()

  def trace[T](operation: String)(body: => T): T = {
    if (!isEnabled) return body
    val start = System.nanoTime()
    val result = body
    val elapsed = (System.nanoTime() - start) / 1_000_000.0
    Log.info(s"[metallurgy] $operation took ${elapsed}ms")
    result
  }

  def registerRegistryKey(): Unit = {
    // Registry key is auto-declared via plugin.xml <registryKey> extension.
    // This is a convenience accessor.
  }
}
