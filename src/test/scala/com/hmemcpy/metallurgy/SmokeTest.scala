package com.hmemcpy.metallurgy

import com.intellij.testFramework.UsefulTestCase

class SmokeTest extends UsefulTestCase {
  def testPluginClassesLoad(): Unit = {
    assertNotNull(classOf[MetallurgyProjectActivity].getName)
    assertNotNull(classOf[settings.MetallurgySettings].getName)
    assertNotNull(classOf[module.ModuleDetectionService].getName)
    assertNotNull(classOf[pc.PcSessionManager].getName)
  }
}
