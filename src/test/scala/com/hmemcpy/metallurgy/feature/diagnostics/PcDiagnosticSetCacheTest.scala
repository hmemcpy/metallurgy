package com.hmemcpy.metallurgy.feature.diagnostics

import com.hmemcpy.metallurgy.pc.PcDiagnostic
import com.intellij.openapi.util.TextRange
import org.junit.Assert.assertEquals
import org.junit.{Before, Test}
import scala.compiletime.uninitialized

/** Pure-logic tests for the [[PcDiagnosticSetCache]] snapshot FSM: transitions, version-gating, and
  * supersede/​no-regression semantics. No IDE fixture — the store is lock-free and needs none.
  */
final class PcDiagnosticSetCacheTest:
  private var cache: PcDiagnosticSetCache = uninitialized

  @Before def setUp(): Unit = cache = new PcDiagnosticSetCache

  private def diag(start: Int, end: Int, message: String): PcDiagnostic =
    PcDiagnostic(new TextRange(start, end), isError = true, message)

  @Test def noEntryIsUnavailable(): Unit =
    assertEquals(SnapshotState.Unavailable, cache.stateFor("file://x.scala", 1L))

  @Test def markPendingIsPending(): Unit =
    cache.markPending("file://x.scala", 1L)
    assertEquals(SnapshotState.Pending(1L), cache.stateFor("file://x.scala", 1L))

  @Test def publishSuccessIsCurrentSuccess(): Unit =
    cache.markPending("file://x.scala", 1L)
    cache.publishSuccess("file://x.scala", 1L, Seq(diag(0, 1, "boom")))
    assertEquals(
      SnapshotState.CurrentSuccess(1L, Seq(diag(0, 1, "boom"))),
      cache.stateFor("file://x.scala", 1L)
    )

  @Test def publishSuccessEmptyMeansClean(): Unit =
    cache.markPending("file://x.scala", 1L)
    cache.publishSuccess("file://x.scala", 1L, Seq.empty)
    assertEquals(
      SnapshotState.CurrentSuccess(1L, Seq.empty),
      cache.stateFor("file://x.scala", 1L)
    )

  @Test def publishFailedIsFailed(): Unit =
    cache.markPending("file://x.scala", 1L)
    cache.publishFailed("file://x.scala", 1L)
    assertEquals(SnapshotState.Failed(1L), cache.stateFor("file://x.scala", 1L))

  @Test def newerVersionSupersedesPending(): Unit =
    cache.markPending("file://x.scala", 1L)
    cache.markPending("file://x.scala", 2L)
    assertEquals(SnapshotState.Pending(2L), cache.stateFor("file://x.scala", 2L))

  @Test def stalePublishIsDroppedAfterSupersede(): Unit =
    cache.markPending("file://x.scala", 1L)
    cache.markPending("file://x.scala", 2L)
    cache.publishSuccess("file://x.scala", 1L, Seq(diag(0, 1, "stale")))
    assertEquals(SnapshotState.Pending(2L), cache.stateFor("file://x.scala", 2L))

  @Test def documentAdvancedIsUnavailable(): Unit =
    cache.markPending("file://x.scala", 1L)
    cache.publishSuccess("file://x.scala", 1L, Seq.empty)
    // version 2 has no current pc state → never apply a stale result; leave bundled
    assertEquals(SnapshotState.Unavailable, cache.stateFor("file://x.scala", 2L))

  @Test def documentAdvancedPastFailedIsUnavailable(): Unit =
    cache.markPending("file://x.scala", 1L)
    cache.publishFailed("file://x.scala", 1L)
    // a stale Failed must never synthesize Pending (blank) — leave bundled untouched
    assertEquals(SnapshotState.Unavailable, cache.stateFor("file://x.scala", 2L))

  @Test def markUnavailableClearsState(): Unit =
    cache.markPending("file://x.scala", 1L)
    cache.publishSuccess("file://x.scala", 1L, Seq.empty)
    cache.markUnavailable("file://x.scala")
    assertEquals(SnapshotState.Unavailable, cache.stateFor("file://x.scala", 1L))

  @Test def markPendingDoesNotRegress(): Unit =
    cache.markPending("file://x.scala", 3L)
    cache.markPending("file://x.scala", 1L)
    assertEquals(SnapshotState.Pending(3L), cache.stateFor("file://x.scala", 3L))

  @Test def publishWithoutPendingIsNoOp(): Unit =
    cache.publishSuccess("file://x.scala", 1L, Seq(diag(0, 1, "x")))
    assertEquals(SnapshotState.Unavailable, cache.stateFor("file://x.scala", 1L))

  @Test def publishDoesNotRegressACurrentResult(): Unit =
    cache.markPending("file://x.scala", 1L)
    cache.publishSuccess("file://x.scala", 1L, Seq.empty)
    // a stray re-publish for the same version must not flip CurrentSuccess back to Failed
    cache.publishFailed("file://x.scala", 1L)
    assertEquals(SnapshotState.CurrentSuccess(1L, Seq.empty), cache.stateFor("file://x.scala", 1L))

  @Test def newerEditMovesFailedBackToPending(): Unit =
    cache.markPending("file://x.scala", 1L)
    cache.publishFailed("file://x.scala", 1L)
    cache.markPending("file://x.scala", 2L)
    assertEquals(SnapshotState.Pending(2L), cache.stateFor("file://x.scala", 2L))
