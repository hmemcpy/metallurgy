package com.hmemcpy.metallurgy.regression

import com.hmemcpy.metallurgy.testkit.MetallurgyFixtureTestCase

final class StructuralTypesafeConfigTest extends MetallurgyFixtureTestCase:
  override protected def fixtureName: String = "structural_typesafe_config"

  def testMetallurgyOn(): Unit = assertMetallurgyOn()

  // Related upstream gaps: SCL-21591, SCL-21789. The full quote-macro fixture follows with the fixture expansion.
  def testMetallurgyOff(): Unit = assertMetallurgyOff()
