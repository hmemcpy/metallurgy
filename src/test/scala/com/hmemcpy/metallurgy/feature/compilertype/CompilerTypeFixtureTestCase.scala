package com.hmemcpy.metallurgy.feature.compilertype

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.{LocalFileSystem, VirtualFile}
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.util.extensions._
import org.junit.Assert._

import java.io.File

/** Base class for the Phase 1 golden corpus tests.
  *
  * Each subclass picks one fixture from `testdata/feature/compilertype/` and runs
  * it in two configurations:
  *
  *   1. Metallurgy-on half: Metallurgy setting is enabled; assertion is that
  *      transparent-inline / macro types resolve correctly.
  *   2. Metallurgy-off half: Metallurgy is disabled; assertion is that the bundled
  *      plugin's result is visibly wrong (returns `Any` / unresolved reference).
  *
  * The negative half proves our value-add is non-zero.
  */
abstract class CompilerTypeFixtureTestCase(
  fixtureFile: String,
  libraries: Seq[(String, String, String)] = Seq.empty
) extends ScalaLightCodeInsightFixtureTestCase {

  private val FixtureDir = "feature/compilertype"

  protected def assertMetallurgyOn(fileText: String): Unit
  protected def assertMetallurgyOff(fileText: String): Unit

  private def loadFixture: String = {
    val dir = new File(System.getProperty("user.dir"), s"src/test/testdata/$FixtureDir")
    val file = new File(dir, fixtureFile)
    FileUtil.loadFile(file)
  }

  // Placeholder: actual test methods will be wired once the testkit backport
  // provides the full LightProjectDescriptor + library loader machinery.
  // For now, this class defines the contract that subclasses implement.
}
