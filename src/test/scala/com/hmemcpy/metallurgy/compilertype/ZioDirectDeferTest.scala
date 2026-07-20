package com.hmemcpy.metallurgy.compilertype

import com.hmemcpy.metallurgy.testkit.MetallurgyFixtureTestCase
import org.jetbrains.plugins.scala.DependencyManagerBase.RichStr
import org.jetbrains.plugins.scala.base.libraryLoaders.{IvyManagedLoader, LibraryLoader}

final class ZioDirectDeferTest extends MetallurgyFixtureTestCase:

  override protected val fixtureName: String = "zio_direct_defer"

  override protected def additionalLibraries: Seq[LibraryLoader] =
    Seq(IvyManagedLoader(("dev.zio" %% "zio-direct" % "1.0.0-RC7").transitive()))

  def testMetallurgyOn(): Unit = assertMetallurgyOn()

  def testMetallurgyOff(): Unit = assertMetallurgyOff()
