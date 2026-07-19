package com.hmemcpy.metallurgy.compilertype

import com.hmemcpy.metallurgy.testkit.MetallurgyFixtureTestCase

final class CompiletimeOpsIntTest extends MetallurgyFixtureTestCase:
  override protected def fixtureName: String = "compiletime_ops_int"

  def testMetallurgyOn(): Unit = assertMetallurgyOn()

  // SCL-21198, SCL-21528
  def testMetallurgyOff(): Unit = assertMetallurgyOff()
