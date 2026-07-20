package com.hmemcpy.metallurgy.regression

import com.hmemcpy.metallurgy.testkit.MetallurgyFixtureTestCase

final class CompilerErrorPreservedTest extends MetallurgyFixtureTestCase:

  override protected val fixtureName: String = "compiler_error_preserved"

  def testMetallurgyOn(): Unit = assertMetallurgyOn()

  def testMetallurgyOff(): Unit = assertMetallurgyOff()
