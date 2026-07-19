package com.hmemcpy.metallurgy

import org.junit.Assert.assertNotNull
import org.junit.Test

final class SmokeTest:

  @Test
  def testPluginClassesLoad(): Unit =
    assertNotNull(classOf[MetallurgyProjectActivity].getName)
    assertNotNull(classOf[settings.MetallurgySettings].getName)
    assertNotNull(classOf[module.ModuleDetectionService].getName)
    assertNotNull(classOf[pc.PcSessionManager].getName)
    assertNotNull(Class.forName("com.hmemcpy.metallurgy.pc.PcInlineTypeDriver"))
