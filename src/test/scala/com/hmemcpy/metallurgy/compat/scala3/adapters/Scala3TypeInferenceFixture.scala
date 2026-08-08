package com.hmemcpy.metallurgy.compat.scala3.adapters

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import org.jetbrains.plugins.scala.ScalaVersion

abstract class Scala3TypeInferenceFixture extends Scala3CompatTestCase:

  private val ExactScalaVersion = ScalaVersion.fromString("3.7.4").get

  override protected def setUp(): Unit =
    injectedScalaVersion = ExactScalaVersion
    super.setUp()

  override protected def tearDown(): Unit =
    try println(s"### $buildVersionsDetailsMessage ###")
    finally super.tearDown()

  protected final def doTest(fileText: String): Unit =
    assertTypeInferenceResult(fileText)
