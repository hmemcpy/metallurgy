package com.hmemcpy.metallurgy.compilertype

import com.hmemcpy.metallurgy.testkit.MetallurgyFixtureTestCase

final class InlineMatchPeanoTest extends MetallurgyFixtureTestCase:
  override protected def fixtureName: String = "inline_match_peano"

  def testMetallurgyOn(): Unit = assertMetallurgyOn()

  // SCL-21789
  def testMetallurgyOff(): Unit = assertMetallurgyOff()
