package com.hmemcpy.metallurgy

import org.junit.Test
import org.junit.Assert.assertNotNull

class SmokeTest {
  @Test
  def testPluginClassesLoad(): Unit = {
    assertNotNull(classOf[MetallurgyProjectActivity].getName)
    assertNotNull(classOf[settings.MetallurgySettings].getName)
    assertNotNull(classOf[module.ModuleDetectionService].getName)
    assertNotNull(classOf[pc.PcSessionManager].getName)
  }
}
