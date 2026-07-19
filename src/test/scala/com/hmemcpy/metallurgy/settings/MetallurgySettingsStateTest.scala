package com.hmemcpy.metallurgy.settings

import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.Test

import scala.jdk.CollectionConverters._

class MetallurgySettingsStateTest {

  @Test def globallyEnabled_defaultsFalse(): Unit = {
    val state = new MetallurgySettings.State
    assertFalse(state.globallyEnabled)
  }

  @Test def globallyEnabled_setterWorks(): Unit = {
    val state = new MetallurgySettings.State
    state.setGloballyEnabled(true)
    assertTrue(state.isGloballyEnabled())
  }

  @Test def enabledModules_isMutableAndStartsEmpty(): Unit = {
    val state = new MetallurgySettings.State
    assertTrue(state.enabledModules.asScala.isEmpty)
    state.enabledModules.add("foo")
    assertEquals(Set("foo"), state.enabledModules.asScala.toSet)
  }

  @Test def neverAskModules_isMutableAndStartsEmpty(): Unit = {
    val state = new MetallurgySettings.State
    assertTrue(state.neverAskModules.asScala.isEmpty)
    state.neverAskModules.add("bar")
    assertEquals(Set("bar"), state.neverAskModules.asScala.toSet)
  }
}
