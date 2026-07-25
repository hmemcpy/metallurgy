package com.hmemcpy.metallurgy.compilertype

import com.hmemcpy.metallurgy.testkit.MetallurgyFixtureTestCase
import org.jetbrains.plugins.scala.DependencyManagerBase.RichStr
import org.jetbrains.plugins.scala.base.libraryLoaders.{IvyManagedLoader, LibraryLoader}
import org.jetbrains.plugins.scala.ScalaVersion

final class JingOpenApiTest extends MetallurgyFixtureTestCase:

  override protected val fixtureName: String = "jing_openapi"

  override protected def supportedIn(version: ScalaVersion): Boolean = version == fixtureScalaVersion

  override protected def defaultVersionOverride: Option[ScalaVersion] = Some(fixtureScalaVersion)

  override protected def additionalLibraries: Seq[LibraryLoader] =
    Seq(IvyManagedLoader(("dev.continuously.jing" %% "jing-openapi" % "0.0.5").transitive()))

  def testMetallurgyOn(): Unit = assertMetallurgyOn()

  def testMetallurgyOff(): Unit = assertMetallurgyOff()

  private val fixtureScalaVersion = ScalaVersion.fromString("3.7.4").get
