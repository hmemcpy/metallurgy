package com.hmemcpy.metallurgy.compilertype

import com.hmemcpy.metallurgy.testkit.MetallurgyFixtureTestCase

final class TypesafeConfigTest extends MetallurgyFixtureTestCase:
  override protected def fixtureName: String = "typesafe_config"

  def testMetallurgyOn(): Unit = assertMetallurgyOn()

  // SCL-21591, SCL-20893, SCL-21789
  def testMetallurgyOff(): Unit = assertMetallurgyOff()
