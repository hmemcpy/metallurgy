package org.virtuslab.ideprobe.handlers

import java.nio.file.Path

import com.intellij.ide.trustedProjects.TrustedProjects

object TrustedPaths {
  def add(path: Path): Unit = TrustedProjects.setProjectTrusted(path, true)
}
