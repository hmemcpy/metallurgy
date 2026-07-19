package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.project.Project

/** Per-module `pc` session manager. One `PresentationCompiler` per
  * `(module, scalaBinaryVersion, classpathHash)`. Phase 0 stub; real
  * implementation is Phase 1.
  */
final class PcSessionManager(project: Project) {
  // TODO Phase 1: implement per-module PcSession lifecycle.
  // - Load mtags via ServiceLoader from MtagsFetcher-cached jars.
  // - Build classpath from ScalaCompilerConfiguration + module scope.
  // - Hot-swap on classpath change; close on module removal.
}

object PcSessionManager {
  def get(project: Project): PcSessionManager =
    project.getService(classOf[PcSessionManager])
}
