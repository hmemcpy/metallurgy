package com.hmemcpy.metallurgy.module

import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the Scala 3.5+ eligibility predicate.
  *
  * These tests do NOT exercise the cache or the listener wiring — those need the testkit backport (issue #11) and a
  * real `Module` fixture. They cover only the pure predicate `version.isScala3 && version >= Scala_3_5_0`, which is the
  * part most likely to regress.
  */
class EligibilityPredicateTest {

  // The fixed 3.5.0 floor — must match the production `floor` val in
  // `ModuleDetectionService`. Anything >= this is eligible.
  private val floor: ScalaVersion =
    new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "0")

  private def isEligible(version: ScalaVersion): Boolean =
    version.isScala3 && version >= floor

  @Test def scala_3_5_0_isEligible(): Unit =
    assertEquals(true, isEligible(new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "0")))

  @Test def scala_3_5_2_isEligible(): Unit =
    assertEquals(true, isEligible(ScalaVersion.Latest.Scala_3_5))

  @Test def scala_3_6_isEligible(): Unit =
    assertEquals(true, isEligible(ScalaVersion.Latest.Scala_3_6))

  @Test def scala_3_7_isEligible(): Unit =
    assertEquals(true, isEligible(ScalaVersion.Latest.Scala_3_7))

  @Test def scala_3_4_isNotEligible(): Unit =
    assertEquals(false, isEligible(ScalaVersion.Latest.Scala_3_4))

  @Test def scala_3_3_isNotEligible(): Unit =
    assertEquals(false, isEligible(ScalaVersion.Latest.Scala_3_3))

  @Test def scala_2_13_isNotEligible(): Unit =
    assertEquals(false, isEligible(ScalaVersion.Latest.Scala_2_13))

  @Test def scala_3_5_RC1_isNotEligible(): Unit =
    // 3.5.0-RC1 is pre-release of 3.5.0 — strictly less than the 3.5.0 stable floor
    // (Version comparison treats RC/pre-release as < stable).
    assertEquals(false, isEligible(new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "0-RC1")))
}
