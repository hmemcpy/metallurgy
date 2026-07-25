package com.hmemcpy.metallurgy.pc

import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

/** Pure unit tests for the document↔compiler coordinate projection. No IntelliJ fixture: the projection operates on
  * plain offsets, so these run as plain JUnit.
  */
class PcCompilationProjectionTest:

  private val P = "private val __f = "

  @Test def identityIsNoOp(): Unit =
    val doc  = "val t = Tuple1(1)\n/*start*/t/*end*/\n"
    val proj = PcCompilationProjection.identity(doc)
    assertTrue(proj.isIdentity)
    assertEquals(doc, proj.compilerText)
    assertEquals(doc, proj.documentText)
    assertEquals(PcCompilationProjection.IdentityFingerprint, proj.fingerprint)
    assertEquals(PcSourceRange(0, doc.length), proj.toCompilerRange(0, doc.length))
    assertEquals(9, proj.toCompilerPoint(9))
    assertEquals(Some(9), proj.toDocumentPoint(9))
    assertEquals(Some(PcSourceRange(3, 7)), proj.toDocumentRange(3, 7))

  @Test def singleInsertionMapsExpressionRangeAndRoundTrips(): Unit =
    // document: "AB\nCD\n"; insert "X=" before the 'C' at offset 3 -> compiler "AB\nX=CD\n"
    val doc  = "AB\nCD\n"
    val proj = PcCompilationProjection.withInsertions(doc, Seq(PcProjectionInsertion(3, "X=")))
    assertEquals("AB\nX=CD\n", proj.compilerText)
    assertEquals(doc, proj.documentText)
    assertTrue(!proj.isIdentity)
    assertTrue(proj.fingerprint != PcCompilationProjection.IdentityFingerprint)
    // 'C' (doc 3) sits at compiler 5; the "CD" initializer range [3,5) -> compiler [5,7)
    assertEquals(5, proj.toCompilerPoint(3))
    assertEquals(PcSourceRange(5, 7), proj.toCompilerRange(3, 5))
    // round trip back to verbatim coordinates
    assertEquals(Some(PcSourceRange(3, 5)), proj.toDocumentRange(5, 7))
    assertEquals(Some(3), proj.toDocumentPoint(5))

  @Test def bytesBeforeTheFirstInsertionKeepTheirOffsets(): Unit =
    val doc  = "AB\nCD\n"
    val proj = PcCompilationProjection.withInsertions(doc, Seq(PcProjectionInsertion(3, "X=")))
    assertEquals(PcSourceRange(0, 2), proj.toCompilerRange(0, 2))
    assertEquals(Some(PcSourceRange(0, 2)), proj.toDocumentRange(0, 2))

  @Test def insertionAtOffsetZeroAttachesBeforeEverything(): Unit =
    val doc  = "expr\n"
    val proj = PcCompilationProjection.withInsertions(doc, Seq(PcProjectionInsertion(0, P)))
    assertEquals(P + "expr\n", proj.compilerText)
    assertEquals(P.length, proj.toCompilerPoint(0))
    assertEquals(P.length + 2, proj.toCompilerPoint(2))
    assertEquals(Some(0), proj.toDocumentPoint(P.length))

  @Test def rangeExtendingToEndOfDocumentRoundTrips(): Unit =
    // document "A\nB\n"; insert "X=" before B (offset 2); the "B\n" initializer spans to EOF (doc offset 4)
    val doc  = "A\nB\n"
    val proj = PcCompilationProjection.withInsertions(doc, Seq(PcProjectionInsertion(2, "X=")))
    assertEquals("A\nX=B\n", proj.compilerText)
    assertEquals(PcSourceRange(4, 6), proj.toCompilerRange(2, 4))
    assertEquals(Some(PcSourceRange(2, 4)), proj.toDocumentRange(4, 6))
    assertEquals(6, proj.toCompilerPoint(4)) // EOF point sits at the end of the compiler text

  @Test def severalInsertionsArePiecewiseAffine(): Unit =
    // document "A\nB\nC\n"; insert before B (offset 2) and before C (offset 4)
    val doc  = "A\nB\nC\n"
    val proj = PcCompilationProjection.withInsertions(
      doc,
      Seq(PcProjectionInsertion(2, "p0="), PcProjectionInsertion(4, "p1="))
    )
    assertEquals("A\np0=B\np1=C\n", proj.compilerText)
    // 'B' (doc 2) -> compiler 5; 'C' (doc 4) -> compiler 10
    assertEquals(5, proj.toCompilerPoint(2))
    assertEquals(10, proj.toCompilerPoint(4))
    assertEquals(PcSourceRange(5, 7), proj.toCompilerRange(2, 4))   // "B\n" initializer span
    assertEquals(PcSourceRange(10, 12), proj.toCompilerRange(4, 6)) // "C\n" initializer span
    assertEquals(Some(PcSourceRange(2, 4)), proj.toDocumentRange(5, 7))
    assertEquals(Some(PcSourceRange(4, 6)), proj.toDocumentRange(10, 12))

  @Test def rangeCrossingAnInsertionIsDroppedNotClamped(): Unit =
    val doc  = "AB\nCD\n"
    val proj = PcCompilationProjection.withInsertions(doc, Seq(PcProjectionInsertion(3, "X=")))
    // compiler range [2,6) spans the inserted "X=" region [3,5): no contiguous verbatim range exists
    assertEquals(None, proj.toDocumentRange(2, 6))
    // a range fully inside the inserted text is wholly synthetic
    assertEquals(None, proj.toDocumentRange(3, 5))
    assertEquals(None, proj.toDocumentRange(4, 5))

  @Test def offsetsInsideInsertedTextHaveNoDocumentPosition(): Unit =
    val doc  = "AB\nCD\n"
    val proj = PcCompilationProjection.withInsertions(doc, Seq(PcProjectionInsertion(3, "X=")))
    assertEquals(None, proj.toDocumentPoint(3)) // first byte of "X="
    assertEquals(None, proj.toDocumentPoint(4)) // second byte of "X="
    // the boundary at the end of the insertion maps to the byte that follows it in the document
    assertEquals(Some(3), proj.toDocumentPoint(5))

  @Test def emptyRangeAwayFromABoundaryMapsToAnEmptyRange(): Unit =
    val doc  = "AB\nCD\n"
    val proj = PcCompilationProjection.withInsertions(doc, Seq(PcProjectionInsertion(3, "X=")))
    assertEquals(PcSourceRange(0, 0), proj.toCompilerRange(0, 0))
    assertEquals(PcSourceRange(7, 7), proj.toCompilerRange(5, 5)) // doc 5 is past the insertion
    assertEquals(Some(PcSourceRange(0, 0)), proj.toDocumentRange(0, 0))
    assertEquals(Some(PcSourceRange(5, 5)), proj.toDocumentRange(7, 7))

  @Test def crlfLineEndingsAreNeverNormalized(): Unit =
    val doc  = "A\r\nB\r\n"
    val proj = PcCompilationProjection.withInsertions(doc, Seq(PcProjectionInsertion(3, "X=")))
    assertEquals("A\r\nX=B\r\n", proj.compilerText)
    assertTrue(proj.compilerText.contains("\r\n"))
    assertEquals(5, proj.toCompilerPoint(3)) // 'B' at compiler 5
    assertEquals(Some(PcSourceRange(3, 4)), proj.toDocumentRange(5, 6)) // "B"

  @Test def rightAndLeftBiasAtTheInsertionBoundary(): Unit =
    val doc  = "AB\nCD\n"
    val proj = PcCompilationProjection.withInsertions(doc, Seq(PcProjectionInsertion(3, "X=")))
    // a range ending exactly at the boundary is left-biased: its end keeps the pre-insertion offset (3, not 5)
    assertEquals(PcSourceRange(0, 3), proj.toCompilerRange(0, 3))
    // a range starting exactly at the boundary is right-biased: its start attaches the prefix (5, not 3)
    assertEquals(PcSourceRange(5, 6), proj.toCompilerRange(3, 4))
