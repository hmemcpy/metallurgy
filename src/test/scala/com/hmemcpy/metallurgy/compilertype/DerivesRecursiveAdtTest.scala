package com.hmemcpy.metallurgy.compilertype

import com.hmemcpy.metallurgy.testkit.MetallurgyFixtureTestCase

final class DerivesRecursiveAdtTest extends MetallurgyFixtureTestCase:

  override protected val fixtureName: String = "derives_recursive_adt"

  def testMetallurgyOn(): Unit = assertMetallurgyOn()

  def testMetallurgyOff(): Unit = assertMetallurgyOff()
