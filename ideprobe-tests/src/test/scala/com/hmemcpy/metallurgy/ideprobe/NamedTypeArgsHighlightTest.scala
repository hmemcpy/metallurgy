package com.hmemcpy.metallurgy.ideprobe

import org.junit.Test
import org.junit.Assert.assertTrue
import org.virtuslab.ideprobe.IdeProbeFixture
import org.virtuslab.ideprobe.protocol.HighlightInfo

/** Drives a real IDE with the Metallurgy plugin installed, opens the dogfood NamedTypeArgs file, and asserts the
  * delivered producer PSI carries no ERROR-severity highlights after settle — the repeatable, screen-free replacement
  * for manual runIDE observation.
  *
  * Slow: launches (and on first run downloads) an IDE. Run via `sbt test` in this module; requires the plugin packaged
  * and the dogfood project present (config in resources/ideprobe.conf).
  */
final class NamedTypeArgsHighlightTest extends IdeProbeFixture {

  @Test
  def noErrorHighlightsAfterSettle(): Unit =
    fixtureFromConfig().run { intelliJ =>
      val file   = intelliJ.workspace.resolve("src/main/scala/metallurgy/showcase/NamedTypeArgs.scala")
      val infos  = intelliJ.probe.highlightInfos(file)
      val errors = infos.filter(_.severity == HighlightInfo.Severity.Error)
      assertTrue(
        s"expected no ERROR highlights on NamedTypeArgs.scala after the producer settles, got: $errors",
        errors.isEmpty
      )
    }
}
