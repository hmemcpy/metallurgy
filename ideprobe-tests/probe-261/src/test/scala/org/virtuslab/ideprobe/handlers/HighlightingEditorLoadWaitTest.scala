package org.virtuslab.ideprobe.handlers

import java.util.concurrent.TimeUnit

import scala.collection.mutable.ArrayBuffer

import org.junit.Assert.{assertEquals, assertThrows}
import org.junit.Test

final class HighlightingEditorLoadWaitTest {
  private val PollIntervalNanos = TimeUnit.MILLISECONDS.toNanos(100)
  private val DeadlineNanos     = TimeUnit.MINUTES.toNanos(2)

  @Test
  def returnsImmediatelyAfterInitialDispatchWhenLoaded(): Unit = {
    val events = ArrayBuffer.empty[String]

    Highlighting.waitForEditorLoad(
      "/workspace/Immediate.scala",
      () => {
        events += "loaded"
        true
      },
      () => events += "dispatch",
      () => {
        events += "clock"
        0L
      },
      nanos => events += s"park:$nanos"
    )

    assertEquals(Seq("clock", "dispatch", "loaded"), events.toSeq)
  }

  @Test
  def preservesPollAndDispatchOrderUntilLoaded(): Unit = {
    val events = ArrayBuffer.empty[String]
    var now    = 0L
    var checks = 0

    Highlighting.waitForEditorLoad(
      "/workspace/SeveralPolls.scala",
      () => {
        events += "loaded"
        checks += 1
        checks == 4
      },
      () => events += "dispatch",
      () => {
        events += "clock"
        now
      },
      nanos => {
        events += s"park:$nanos"
        now += nanos
      }
    )

    assertEquals(
      Seq(
        "clock",
        "dispatch",
        "loaded",
        "clock",
        s"park:$PollIntervalNanos",
        "dispatch",
        "loaded",
        "clock",
        s"park:$PollIntervalNanos",
        "dispatch",
        "loaded",
        "clock",
        s"park:$PollIntervalNanos",
        "dispatch",
        "loaded"
      ),
      events.toSeq
    )
  }

  @Test
  def expiresAtTheExactDeadlineWithoutAnotherPark(): Unit = {
    val events     = ArrayBuffer.empty[String]
    var clockCalls = 0

    val failure = assertThrows(
      classOf[AssertionError],
      () =>
        Highlighting.waitForEditorLoad(
          "/workspace/Expired.scala",
          () => {
            events += "loaded"
            false
          },
          () => events += "dispatch",
          () => {
            events += "clock"
            clockCalls += 1
            if (clockCalls == 1) 17L else 17L + DeadlineNanos
          },
          nanos => events += s"park:$nanos"
        )
    )

    assertEquals(
      "Editor loading did not finish for /workspace/Expired.scala within the two-minute editor-load deadline",
      failure.getMessage
    )
    assertEquals(Seq("clock", "dispatch", "loaded", "clock"), events.toSeq)
  }

  @Test
  def succeedsWhenLoadingCompletesAtTheDeadlineBoundary(): Unit = {
    val events = ArrayBuffer.empty[String]
    var now    = 0L
    var checks = 0

    Highlighting.waitForEditorLoad(
      "/workspace/Boundary.scala",
      () => {
        events += "loaded"
        checks += 1
        checks == 2
      },
      () => events += "dispatch",
      () => {
        events += "clock"
        now
      },
      nanos => {
        events += s"park:$nanos"
        assertEquals(PollIntervalNanos, nanos)
        now = DeadlineNanos
      }
    )

    assertEquals(
      Seq("clock", "dispatch", "loaded", "clock", s"park:$PollIntervalNanos", "dispatch", "loaded"),
      events.toSeq
    )
  }
}
