package com.hmemcpy.metallurgy.compilertype

import com.hmemcpy.metallurgy.testkit.MetallurgyFixtureTestCase
import org.jetbrains.plugins.scala.DependencyManagerBase.RichStr
import org.jetbrains.plugins.scala.base.libraryLoaders.{IvyManagedLoader, LibraryLoader}

final class KyoDirectDeferTest extends MetallurgyFixtureTestCase:

  override protected val fixtureName: String = "kyo_direct_defer"

  override protected def additionalLibraries: Seq[LibraryLoader] =
    Seq(IvyManagedLoader(("io.getkyo" %% "kyo-direct" % "0.15.1").transitive()))

  def testMetallurgyOn(): Unit = assertMetallurgyOn()

  def testMetallurgyOff(): Unit = assertMetallurgyOff()
