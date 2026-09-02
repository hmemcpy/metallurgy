package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.psiproducer.{CompilerRuntimeInventory, InventoryKind}
import org.junit.Assert.*
import org.junit.Test

/** Correlation-consistency proof: separator facts that the replay or the node-bounded interval cannot confirm never
  * reach the planner's selection evidence.
  */
final class Scala3SeparatorConsistencyInventoryTest extends Scala3ParserTestSupport:
  @Test
  def wrongSeparatorFactsStayOutsideSelectionEvidence(): Unit =
    val bridge = openBridge()
    try
      val source      =
        """def f(x: Any): Any = x match
          |  case y: pkg.Outer#T => 1
          |""".stripMargin
      val snapshot    = parse(bridge, source, "file:///SeparatorConsistency.scala")
      val outerSelect = snapshot.nodes
        .find(node =>
          node.production == "Select" && node.fields.exists(field =>
            field.name == "name" && field.value == ParserFieldValue.Name("T")
          )
        )
        .getOrElse(throw new AssertionError("outer projection Select missing"))
      val innerDot    = snapshot.scannerTokens
        .find(token => token.kind == ParserScannerTokenKind.Dot)
        .getOrElse(throw new AssertionError("inner dot token missing"))

      // A contradictory parser-owned Dot fact on the projection root whose range is the
      // nested qualifier dot must not become selection evidence for that Select.
      val corrupted = snapshot.copy(nodeSeparators =
        snapshot.nodeSeparators :+ com.hmemcpy.metallurgy.pc
          .ParserNodeSeparator(
            outerSelect.id,
            innerDot.kind,
            innerDot.range,
            innerDot.range.startOffset,
            innerDot.provenance
          )
      )
      val inventory = CompilerRuntimeInventory
        .from(corrupted)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
      val outerRow  = inventory.shapes
        .find(row => row.kind == InventoryKind.Node && row.id == outerSelect.id)
        .getOrElse(throw new AssertionError("outer Select row missing"))
      assertEquals(
        "the contradictory Dot fact must stay outside the projection root's selection evidence",
        Vector(ParserScannerTokenKind.Hash),
        outerRow.separatorKinds
      )

      // The genuine parse's facts survive every confirmation for the same source.
      val honest    = CompilerRuntimeInventory
        .from(snapshot)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
      val honestRow =
        honest.shapes.find(row => row.id == outerSelect.id).getOrElse(throw new AssertionError("row missing"))
      assertEquals(Vector(ParserScannerTokenKind.Hash), honestRow.separatorKinds)

      // A fabricated Dot fact positioned inside the interval survives position
      // consistency, so replay never filters it pre-selection; the matcher then sees
      // both separator kinds on one Select and no production is silently chosen.
      val hashToken           = snapshot.scannerTokens
        .find(token => token.kind == ParserScannerTokenKind.Hash)
        .getOrElse(throw new AssertionError("hash token missing"))
      val fabricated          = snapshot.copy(nodeSeparators =
        snapshot.nodeSeparators :+ com.hmemcpy.metallurgy.pc
          .ParserNodeSeparator(
            outerSelect.id,
            ParserScannerTokenKind.Dot,
            PcSourceRange(hashToken.range.startOffset, hashToken.range.endOffset),
            hashToken.range.startOffset,
            hashToken.provenance
          )
      )
      val fabricatedInventory = CompilerRuntimeInventory
        .from(fabricated)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
      val fabricatedRow       = fabricatedInventory.shapes
        .find(row => row.kind == InventoryKind.Node && row.id == outerSelect.id)
        .getOrElse(throw new AssertionError("row missing"))
      assertEquals(
        "the in-interval fabrication stays in evidence for selection to resolve atomically",
        Vector(ParserScannerTokenKind.Dot, ParserScannerTokenKind.Hash),
        fabricatedRow.separatorKinds.sortBy(_.ordinal)
      )
      val dottedClaim         = com.hmemcpy.metallurgy.psiproducer.CatalogShapeMatcher.select(
        com.hmemcpy.metallurgy.psiproducer.Scala3PsiProductionCatalog.Reviewed,
        InventoryKind.Node,
        fabricatedRow.prefix,
        fabricatedRow.observation,
        fabricatedRow.contexts.headOption,
        fabricatedRow.sourceClassification,
        fabricatedRow.scannerTokenKinds,
        fabricatedRow.separatorKinds
      )
      assertTrue(
        "a Select carrying contradictory separator kinds must claim multiple hierarchies and fail atomically",
        dottedClaim.size > 1
      )
    finally bridge.close()
