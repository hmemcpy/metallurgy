package com.hmemcpy.metallurgy.compat.scala3

/** The conflict-proof suite: re-proves every entry in [[DotcOracleConflicts]] against the exact-version compiler.
  * Distinct from the normal compatibility suite (where the same snippets still fail, as adjudicated conflicts, not
  * regressions). If dotc ever starts accepting one of these snippets, the assertion fails here, forcing the case back
  * into the normal suite.
  */
final class DotcOracleConflictProofTest extends Scala3CompatTestCase:

  def testAllDeclaredOracleConflictsStillReproduceUnderDotc(): Unit =
    DotcOracleConflicts.entries.foreach: conflict =>
      try assertDotcOracleConflict(conflict.snippet)
      catch
        case e: AssertionError =>
          throw new AssertionError(s"[${conflict.upstreamTest}] ${conflict.classification}: ${e.getMessage}", e)
