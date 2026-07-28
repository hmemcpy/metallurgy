package org.virtuslab.ideprobe

import scala.annotation.nowarn

import com.intellij.ide.ApplicationInitializedListenerJavaShim

@nowarn("cat=deprecation")
final class IdeProbeLauncher extends ApplicationInitializedListenerJavaShim {
  override def componentsInitialized(): Unit = IdeProbeService().start()
}
