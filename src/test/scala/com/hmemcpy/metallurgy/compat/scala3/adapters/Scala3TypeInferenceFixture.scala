package com.hmemcpy.metallurgy.compat.scala3.adapters

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

abstract class Scala3TypeInferenceFixture extends Scala3CompatTestCase:

  protected final def doTest(fileText: String): Unit =
    assertTypeInferenceResult(fileText)
