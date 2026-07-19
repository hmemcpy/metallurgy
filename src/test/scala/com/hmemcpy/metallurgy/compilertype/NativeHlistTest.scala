package com.hmemcpy.metallurgy.compilertype

import com.hmemcpy.metallurgy.testkit.MetallurgyFixtureTestCase

final class NativeHlistTest extends MetallurgyFixtureTestCase:
  override protected def fixtureName: String = "native_hlist"

  def testMetallurgyOn(): Unit = assertMetallurgyOn()

  // SCL-22088
  def testMetallurgyOff(): Unit = assertMetallurgyOff()
