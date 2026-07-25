package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.pc.CompilerSourceClass.{PhysicalSource, SyntheticSemantic}
import junit.framework.TestCase
import org.junit.Assert.assertEquals

/** The source-classification admission rule that governs which compiler trees may become physical PSI: only a real,
  * non-zero, source-derived span qualifies; everything else is synthetic-only.
  */
final class CompilerTreeDtoTest extends TestCase:

  def testSourceDerivedNonZeroSpanIsPhysical(): Unit =
    assertEquals(PhysicalSource, CompilerTreeDto.sourceClassOf(spanExists = true, spanIsSourceDerived = true, 0, 5))

  def testSyntheticSpanIsSyntheticOnly(): Unit =
    assertEquals(SyntheticSemantic, CompilerTreeDto.sourceClassOf(spanExists = true, spanIsSourceDerived = false, 0, 5))

  def testZeroExtentSpanIsSyntheticOnly(): Unit =
    assertEquals(SyntheticSemantic, CompilerTreeDto.sourceClassOf(spanExists = true, spanIsSourceDerived = true, 3, 3))

  def testMissingSpanIsSyntheticOnly(): Unit =
    assertEquals(
      SyntheticSemantic,
      CompilerTreeDto.sourceClassOf(spanExists = false, spanIsSourceDerived = false, 0, 0)
    )

  def testNegativeStartIsSyntheticOnly(): Unit =
    assertEquals(SyntheticSemantic, CompilerTreeDto.sourceClassOf(spanExists = true, spanIsSourceDerived = true, -1, 5))

  def testTreePartitionsPhysicalAndSynthetic(): Unit =
    val physical  = CompilerSourceNode(1L, None, "ValDef", Some(PcSourceRange(0, 10)), PhysicalSource)
    val synthetic = CompilerSourceNode(2L, Some(1L), "Apply", None, SyntheticSemantic)
    val dto       = CompilerTreeDto(Seq(physical, synthetic))
    assertEquals(Vector(physical), dto.physicalNodes)
    assertEquals(Vector(synthetic), dto.syntheticNodes)
