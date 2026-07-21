package com.hmemcpy.metallurgy

import com.intellij.lang.annotation.HighlightSeverity
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.compiler.highlighting.ScalaCompilerHighlightingTestBase
import org.jetbrains.plugins.scala.util.CompilerTestUtil.runWithErrorsFromCompiler
import org.junit.Assert.assertTrue

/** Proves the backported compile-server test chain fires CBH headlessly. Valid Scala 3 code under CBH-on (Metallurgy
  * off) must produce no error highlights — the prerequisite for trustworthy triage.
  *
  * NOTE: the `runWithErrorsFromCompiler` body is routed through a non-`test*` helper. JUnit reflects `test*` methods,
  * and Scala 3 encodes the by-name body as a method that inherits the `test` prefix — putting it inline makes JUnit try
  * to invoke the closure as a test.
  */
final class CbhSmokeTest extends ScalaCompilerHighlightingTestBase:

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.fromString("3.5.2").get

  def testCompileServerResolvesValidScala3(): Unit = checkValidCodeNotRed()

  private def checkValidCodeNotRed(): Unit =
    runWithErrorsFromCompiler(getProject):
      val file   = addFileToProjectSources("Smoke.scala", "object Smoke:\n  val answer: Int = 42\n")
      waitUntilFileIsHighlighted(file)
      val errors = fetchHighlightInfos(file).filter(_.getSeverity == HighlightSeverity.ERROR)
      assertTrue(
        s"CBH produced unexpected errors on valid code: ${errors.map(_.getDescription).mkString("; ")}",
        errors.isEmpty
      )
