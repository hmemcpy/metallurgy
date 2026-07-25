package com.hmemcpy.metallurgy.compilertype

import com.hmemcpy.metallurgy.testkit.MetallurgyFixtureTestCase

final class MatchTypeReduceTest extends MetallurgyFixtureTestCase:
  override protected def fixtureName: String = "match_type_reduce"

  def testMetallurgyOn(): Unit = assertMetallurgyOn()

  // SCL-21198, SCL-22088, SCL-21528
  def testMetallurgyOff(): Unit = assertMetallurgyOff()
