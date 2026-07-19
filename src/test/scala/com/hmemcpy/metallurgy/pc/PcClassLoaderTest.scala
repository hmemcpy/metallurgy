package com.hmemcpy.metallurgy.pc

import org.junit.Assert.{assertFalse, assertTrue}
import org.junit.Test

final class PcClassLoaderTest:

  @Test
  def platformAndSharedApiClassesAreParentFirst(): Unit =
    Seq(
      "java.lang.String",
      "javax.swing.JPanel",
      "jdk.internal.misc.Unsafe",
      "sun.misc.Unsafe",
      "com.intellij.openapi.project.Project",
      "scala.meta.pc.PresentationCompiler",
      "org.eclipse.lsp4j.CompletionItem"
    ).foreach(className => assertTrue(className, PcClassLoader.isSharedApi(className)))

  @Test
  def compilerImplementationAndScalaRuntimeAreChildFirst(): Unit =
    Seq(
      "dotty.tools.pc.ScalaPresentationCompiler",
      "dotty.tools.dotc.interactive.InteractiveDriver",
      "scala.Option"
    ).foreach(className => assertFalse(className, PcClassLoader.isSharedApi(className)))
