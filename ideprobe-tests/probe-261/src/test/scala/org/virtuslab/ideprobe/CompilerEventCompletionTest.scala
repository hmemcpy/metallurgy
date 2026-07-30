package org.virtuslab.ideprobe

import java.nio.file.Path
import java.util.concurrent.TimeUnit

import org.junit.Assert.assertTrue
import org.junit.Test
import org.jetbrains.plugins.scala.compiler.CompilerEvent
import org.jetbrains.plugins.scala.util.{CanonicalPath, CompilationId}

final class CompilerEventCompletionTest {
  @Test(timeout = 1000L)
  def mismatchedCompilationEventsFailWithinBoundedTimeout(): Unit = {
    val expectedPath = Path.of("/compiler-event-negative/Expected.scala")
    val expected     = EditedDocumentGeneration(CanonicalPath(expectedPath.toString), expectedPath, 2L)
    val mismatchedId = new CompilationId(
      1L,
      Map(CanonicalPath("/compiler-event-negative/Other.scala") -> 1L)
        .asInstanceOf[Map[CanonicalPath, Long] with Serializable]
    )
    val completion   = new CompilerEventCompletion
    completion.expect(expected)
    completion.eventReceived(new CompilerEvent.CompilationStarted(mismatchedId, None))
    completion.eventReceived(new CompilerEvent.CompilationFinished(mismatchedId, None, Set.empty))

    val startedAt = System.nanoTime()
    val failure = try {
      val evidence = completion.await(25, TimeUnit.MILLISECONDS)
      throw new AssertionError(s"Mismatched compiler event unexpectedly completed the wait: $evidence")
    } catch {
      case value: AssertionError
          if Option(value.getMessage).exists(_.contains("No matching Scala CompilationFinished event")) =>
        value
    }
    val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    assertTrue(failure.getMessage, failure.getMessage.contains("No matching Scala CompilationFinished event"))
    assertTrue(failure.getMessage, failure.getMessage.contains("starts=1, finishes=1"))
    println(s"bounded-negative-event-wait elapsedMillis=$elapsedMillis: ${failure.getMessage}")
  }
}
