package com.hmemcpy.metallurgy.compilertype

import com.hmemcpy.metallurgy.testkit.MetallurgyFixtureTestCase

final class TypesafeConfigTest extends MetallurgyFixtureTestCase:
  override protected def fixtureName: String = "typesafe_config"

  def testMetallurgyOn(): Unit = assertMetallurgyOn()

  // Related upstream gaps: SCL-21591, SCL-21789. The full quote-macro fixture follows with the corpus expansion.
  def testMetallurgyOff(): Unit = assertMetallurgyOff()
