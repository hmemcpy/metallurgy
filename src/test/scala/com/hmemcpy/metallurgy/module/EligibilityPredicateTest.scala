package com.hmemcpy.metallurgy.module

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the Scala 3 eligibility predicate. Exact PC artifact resolution determines runtime availability. */
final class EligibilityPredicateTest:

  @Test def everyScala3MinorIsEligible(): Unit =
    Seq("3.0.2", "3.3.7", "3.4.3", "3.5.0", "3.7.4").foreach: version =>
      assertEquals(version, true, ModuleDetectionService.isScala3Version(version))

  @Test def Scala3PrereleaseNightlyAndVendorVersionsAreEligible(): Unit =
    Seq("3.5.0-RC1", "3.8.0-RC1-bin-20260722", "3.7.4-bin-vendor").foreach: version =>
      assertEquals(version, true, ModuleDetectionService.isScala3Version(version))

  @Test def nonScala3AndMissingVersionsAreNotEligible(): Unit =
    Seq(null, "", "2.13.16", "30.0").foreach: version =>
      assertEquals(String.valueOf(version), false, ModuleDetectionService.isScala3Version(version))
