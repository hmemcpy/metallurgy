package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.psiproducer.ProvisionalSourceEvidencePlanner
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.Test

import java.nio.file.Path

final class Scala3TypeAtomParserInventoryTest:

  @Test
  def exactTypeAtomProductsAndScannerEvidenceAreDeterministic(): Unit =
    val bridge = openBridge()
    try
      val snapshots = Cases.zipWithIndex.map: (expected, index) =>
        val first  = parse(bridge, expected.source, s"file:///TypeAtomInventory${index}First.scala")
        val second = parse(bridge, expected.source, s"file:///TypeAtomInventory${index}Second.scala")
        assertEquals(first.copy(sourceUri = second.sourceUri), second)
        assertEquals(expected.source, first.sourceText)
        assertEquals(ParserSyntaxSnapshot.digest(expected.source), first.sourceDigest)
        assertEquals(expected.source.length, first.sourceLength)
        assertTrue(first.compilerOptions.isEmpty)
        assertTrue(first.diagnostics.toString, first.diagnostics.isEmpty)
        assertTrue(first.comments.isEmpty)
        assertTrue(first.endMarkers.isEmpty)
        assertTrue(first.runtimeSupplements.isEmpty)
        assertTrue(first.attachments.isEmpty)
        assertEquals(ScalaVersion, first.compilerIdentity.coordinate.version)
        assertTrue(first.compilerIdentity.artifacts.nonEmpty)
        assertTrue(first.capabilities.requiredUnavailable.toString, first.capabilities.requiredUnavailable.isEmpty)
        assertEquals(expected.source, evidence(first).reconstruct(expected.source))
        assertAtomShape(first, expected)
        assertScannerEvidence(first, expected)
        first

      assertEquals(EvidenceFingerprints, snapshots.map(ParserSyntaxSnapshot.evidenceFingerprint))
      assertEquals(ScannerFingerprints, snapshots.map(ParserSyntaxSnapshot.scannerEvidenceFingerprint))
    finally bridge.close()

  @Test
  def malformedAndExcludedTypeBoundariesRetainExactParserEvidence(): Unit =
    val bridge = openBridge()
    try
      val malformed = Vector(
        "import a.b.given T#\n",
        "import a.b.given x.\n",
        "import a.b.given (A\n"
      ).zipWithIndex.map: (source, index) =>
        parse(bridge, source, s"file:///MalformedTypeAtom$index.scala")
      malformed.foreach: snapshot =>
        assertTrue(snapshot.diagnostics.exists(_.severity == ParserDiagnosticSeverity.Error))
        ProvisionalSourceEvidencePlanner
          .plan(snapshot)
          .foreach(plan => assertEquals(snapshot.sourceText, plan.reconstruct(snapshot.sourceText)))
        assertEquals(snapshot.scannerTokens.indices, snapshot.scannerTokens.map(_.ordinal))
        assertTrue(snapshot.scannerTokens.forall(token => token.range.startOffset <= token.range.endOffset))

      val excluded = parse(bridge, "import a.b.given (A, B)\n", "file:///ExcludedTupleType.scala")
      assertFalse(excluded.diagnostics.exists(_.severity == ParserDiagnosticSeverity.Error))
      assertTrue(excluded.nodes.exists(_.production == "Tuple"))
      assertEquals(excluded.sourceText, evidence(excluded).reconstruct(excluded.sourceText))
    finally bridge.close()

  @Test
  def legacySingletonWrapperRetainsExactScannerEvidence(): Unit =
    val bridge = openBridge("3.5.2")
    try
      val snapshot = parse(bridge, source("x.type"), "file:///LegacySingletonTypeAtom.scala")
      val start    = SourcePrefix.length
      assertEquals(
        Vector(
          (ParserScannerTokenKind.Other, "x", 0, 1, ParserPositionProvenance.SourceDerived),
          (ParserScannerTokenKind.Dot, ".", 1, 2, ParserPositionProvenance.SourceDerived),
          (ParserScannerTokenKind.TypeKeyword, "type", 2, 6, ParserPositionProvenance.SourceDerived)
        ),
        snapshot.scannerTokens.collect:
          case token if token.range.startOffset >= start =>
            (
              token.kind,
              snapshot.sourceText.substring(token.range.startOffset, token.range.endOffset),
              token.range.startOffset - start,
              token.range.endOffset - start,
              token.provenance
            )
      )
      val nodes    = snapshot.nodes.map(node => node.id -> node).toMap
      val selector = snapshot.nodes.find(_.production == "ImportSelector").get
      val bound    = selector.fields.collectFirst:
        case ParserSyntaxField("bound", ParserFieldValue.Node(id), _) => nodes(id)
      assertEquals(
        Vector(ParserSyntaxField("ref", ParserFieldValue.Node(9), Some(ParserDeclaredShape.Node))),
        bound.get.fields
      )
    finally bridge.close()

  private def assertAtomShape(snapshot: ParserSyntaxSnapshot, expected: AtomCase): Unit =
    val nodes     = snapshot.nodes.map(node => node.id -> node).toMap
    val selector  = snapshot.nodes.find(_.production == "ImportSelector").get
    val boundId   = selector.fields.collectFirst:
      case ParserSyntaxField("bound", ParserFieldValue.Node(id), _) => id
    val typeStart = expected.source.indexOf("given") + "given ".length
    val typeEnd   = expected.source.length - 1
    val bound     = nodes(boundId.get)
    assertEquals(expected.boundProduction, bound.production)
    assertEquals(
      ParserNodePosition.Positioned(
        PcSourceRange(typeStart, typeEnd),
        typeStart + expected.boundPoint,
        expected.boundProvenance
      ),
      bound.position
    )

    val pending = java.util.ArrayDeque[Long]()
    val seen    = collection.mutable.Set.empty[Long]
    val found   = Vector.newBuilder[(String, String, Int, ParserPositionProvenance)]
    pending.add(bound.id)
    while !pending.isEmpty do
      val current = nodes(pending.removeFirst())
      if seen.add(current.id) then
        current.position match
          case ParserNodePosition.Positioned(range, point, provenance) =>
            found += ((
              current.production,
              expected.source.substring(range.startOffset, range.endOffset),
              point - typeStart,
              provenance
            ))
          case _                                                       => ()
        current.fields.foreach(field => references(field.value).foreach(pending.addLast))
    assertEquals(expected.positionedSubtree, found.result())
    assertEquals(expected.fieldNames, bound.fields.map(_.name))
    assertTrue(snapshot.nodes.forall(node => node.occurrences.distinct == node.occurrences))
    assertTrue(snapshot.positioned.forall(value => value.occurrences.distinct == value.occurrences))

  private def assertScannerEvidence(snapshot: ParserSyntaxSnapshot, expected: AtomCase): Unit =
    val typeStart = expected.source.indexOf("given") + "given ".length
    assertEquals(snapshot.scannerTokens.indices, snapshot.scannerTokens.map(_.ordinal))
    snapshot.scannerTokens.foreach: token =>
      assertTrue(token.range.startOffset >= 0)
      assertTrue(token.range.endOffset <= snapshot.sourceLength)
      assertTrue(token.range.startOffset <= token.range.endOffset)
      assertEquals(token.range.startOffset, token.point)
      token.provenance match
        case ParserPositionProvenance.SourceDerived => assertTrue(token.range.startOffset < token.range.endOffset)
        case ParserPositionProvenance.Synthetic     => assertEquals(token.range.startOffset, token.range.endOffset)
    val relevant  = snapshot.scannerTokens.collect:
      case token if token.kind != ParserScannerTokenKind.Other && token.range.startOffset >= typeStart =>
        (
          token.kind,
          snapshot.sourceText.substring(token.range.startOffset, token.range.endOffset),
          token.range.startOffset - typeStart,
          token.range.endOffset - typeStart,
          token.provenance
        )
    assertEquals(expected.scannerTokens, relevant)
    val colocated = snapshot.scannerTokens
      .filter(_.provenance == ParserPositionProvenance.Synthetic)
      .groupBy(_.range.startOffset)
      .values
      .filter(_.size > 1)
      .flatten
      .toVector
    assertEquals(colocated.map(_.ordinal).distinct.size, colocated.size)

  private def references(value: ParserFieldValue): Vector[Long] = value match
    case ParserFieldValue.Node(id)           => Vector(id)
    case ParserFieldValue.Optional(value)    => value.toVector.flatMap(references)
    case ParserFieldValue.Repeated(values)   => values.flatMap(references)
    case ParserFieldValue.Product(_, fields) => fields.flatMap(field => references(field.value))
    case _: ParserFieldValue.Positioned | _: ParserFieldValue.Name | _: ParserFieldValue.GeneratedName |
        _: ParserFieldValue.Scalar | _: ParserFieldValue.Unsupported =>
      Vector.empty

  private def evidence(snapshot: ParserSyntaxSnapshot) =
    ProvisionalSourceEvidencePlanner
      .plan(snapshot)
      .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)

  private def parse(bridge: Scala3ParserBridge, source: String, uri: String): ParserSyntaxSnapshot =
    bridge
      .parse(
        Scala3ParserRequest(
          ParserSourceUri.from(uri).fold(message => throw new AssertionError(message), identity),
          source,
          Vector.empty
        )
      )
      .fold(error => throw new AssertionError(error.toString), identity)

  private def openBridge(): Scala3ParserBridge =
    openBridge(ScalaVersion)

  private def openBridge(version: String): Scala3ParserBridge =
    Scala3ParserBridge
      .open(
        Scala3ParserArtifactCoordinate("org.scala-lang", "scala3-compiler_3", version),
        compilerDistribution(version).map(_.toFile)
      )
      .fold(error => throw new AssertionError(error.toString), identity)

  private def compilerDistribution(version: String): Seq[Path] =
    Scala3CompilerResolver.publicCoursier
      .resolve(version)
      .fold(error => throw error.toException, identity)

  private final case class AtomCase(
      source: String,
      boundProduction: String,
      boundPoint: Int,
      fieldNames: Vector[String],
      positionedSubtree: Vector[(String, String, Int, ParserPositionProvenance)],
      scannerTokens: Vector[(ParserScannerTokenKind, String, Int, Int, ParserPositionProvenance)],
      boundProvenance: ParserPositionProvenance = ParserPositionProvenance.SourceDerived
  )

  private val ScalaVersion                  = "3.7.4"
  private val SourcePrefix                  = "import a.b.given "
  private def source(value: String): String = s"$SourcePrefix$value\n"
  private def token(
      kind: ParserScannerTokenKind,
      text: String,
      start: Int,
      end: Int
  ) = (kind, text, start, end, ParserPositionProvenance.SourceDerived)
  private def positioned(
      production: String,
      text: String,
      point: Int,
      provenance: ParserPositionProvenance = ParserPositionProvenance.SourceDerived
  ) = (production, text, point, provenance)

  private val EvidenceFingerprints = Vector(
    "7ef5b1bbde097adb3f74feef2ea8ef8c062f18ccf0fc2261f320e2c79c3e8fdd",
    "825e593a13097bde52a929b942aab78e58cb5d01f06626b29cd640265dc05c12",
    "f19d1557d75db39d2272c9ffec037b07463e08632a594e0f97a67fa16b73e3e1",
    "a48461c99cb4d5d92f0adaf3ea3a3b5dc4a4bfa8b978c02bd876c162823e348e",
    "33cc44549fc641888836052b71e7af71b0a610abcd392008b0d427af753c4a82",
    "1c3a37ac7acc1abd0bb7b4712c5217b552af53553fec35ec1312700dd003c9ef",
    "6dd5c9a010c263afbdbbeef5a1765539e6d1a9cb16a9ec552dd7d780f64e918c",
    "4e394818a8243d83c5cd3bc15b73ad2477bac1913d59947e45c64c24573e4267",
    "384c3db1edc3ac4e04c8664aa97fde5d85316c886e6417c0c8a2df972c786026",
    "ca169e43be570dd970cd97d8e91b0f598a1f72a49feefb5fd88e594e65f01ef0",
    "f874b0308633a5625c90278ac1e31b0f02039b82f1e847ab629029b1453b88df",
    "6af502685f562d9a47eb8f7e79eaeed5ecec33bf965d3f21c035f5e341c8aed1",
    "a64ec55ceab57b4c9181720c28eb48a29f4785b829568bfa490e035f8d7d289f",
    "fbdc6f14ccdbd339e52e6860ac391d2631c70e057163b6bd759ffffba13a4288"
  )
  private val ScannerFingerprints  = Vector(
    "f58742b2aaefee70b2f693718ce2fb39ce908325991ad5d716a9fdee669cd75c",
    "03d8725a469384b4a98a54493e9a89bbbe8b224efb20f600dc3e0c29aa950aa2",
    "91d8067463c356980e3cbd7fbd423404e1805253d8fff55d12033855a0c05a9b",
    "077140332c75a3d62c1b180f0d03c5c3b153389093e03251a4a68fa3bd0fe692",
    "c13316846500b4d209bb65883a246b623ae8129c49d7a2dad1a5f48ef9a69309",
    "0e300e9d67e9b5f57173f7df66f605211d62a49d5627cea3661212a5312b9d0b",
    "0672e3d28e01283590d8020bb13e732a9f596e6a575a81e111096983dcfc1910",
    "99788f84a62f9145cd8d1aafa88f72ff93240360ea009f80831088707a2ad730",
    "767ba999c20085e65df4146df9cdeca73f1ef208640f6fa43b300c0a0e18c5b6",
    "42eeef1fa39a9b22e1b99764a6a378ae633e58a466be5f8f8a84d4d28a3f7b2a",
    "21356d8c9c7d402da277844d5e0df2b817ee38d224c4cf10d41ad549485e53d3",
    "55d6b02517509fb6e090aebd57711ab280a5f8c928f93c4214b06fe156d12983",
    "b8887206e33136269a7697fd21955033243ba812bb9b261d27b33f766dfe197f",
    "f35e7fbe94159e4deb1a7ecc5f5157593ed0c36d711cf68461a7ce14287bcfd5"
  )

  private val Cases = Vector(
    AtomCase(source("A"), "Ident", 0, Vector("name"), Vector(positioned("Ident", "A", 0)), Vector.empty),
    AtomCase(
      source("p.A"),
      "Select",
      2,
      Vector("qualifier", "name"),
      Vector(positioned("Select", "p.A", 2), positioned("Ident", "p", 0)),
      Vector(token(ParserScannerTokenKind.Dot, ".", 1, 2))
    ),
    AtomCase(
      source("T#A"),
      "Select",
      2,
      Vector("qualifier", "name"),
      Vector(positioned("Select", "T#A", 2), positioned("Ident", "T", 0)),
      Vector(token(ParserScannerTokenKind.Hash, "#", 1, 2))
    ),
    AtomCase(
      source("x.type"),
      "SingletonTypeTree",
      0,
      Vector("ref"),
      Vector(positioned("SingletonTypeTree", "x.type", 0), positioned("Ident", "x", 0)),
      Vector(
        token(ParserScannerTokenKind.Dot, ".", 1, 2),
        token(ParserScannerTokenKind.TypeKeyword, "type", 2, 6)
      )
    ),
    AtomCase(
      source("p.x.type"),
      "SingletonTypeTree",
      0,
      Vector("ref"),
      Vector(
        positioned("SingletonTypeTree", "p.x.type", 0),
        positioned("Select", "p.x", 2),
        positioned("Ident", "p", 0)
      ),
      Vector(
        token(ParserScannerTokenKind.Dot, ".", 1, 2),
        token(ParserScannerTokenKind.Dot, ".", 3, 4),
        token(ParserScannerTokenKind.TypeKeyword, "type", 4, 8)
      )
    ),
    AtomCase(
      source("42"),
      "SingletonTypeTree",
      0,
      Vector("ref"),
      Vector(
        positioned("SingletonTypeTree", "42", 0, ParserPositionProvenance.Synthetic),
        positioned("Literal", "42", 0)
      ),
      Vector(token(ParserScannerTokenKind.Literal, "42", 0, 2)),
      ParserPositionProvenance.Synthetic
    ),
    AtomCase(
      source("-42"),
      "SingletonTypeTree",
      0,
      Vector("ref"),
      Vector(
        positioned("SingletonTypeTree", "-42", 0, ParserPositionProvenance.Synthetic),
        positioned("Literal", "-42", 0)
      ),
      Vector(token(ParserScannerTokenKind.Literal, "42", 1, 3)),
      ParserPositionProvenance.Synthetic
    ),
    AtomCase(
      source("1L"),
      "SingletonTypeTree",
      0,
      Vector("ref"),
      Vector(
        positioned("SingletonTypeTree", "1L", 0, ParserPositionProvenance.Synthetic),
        positioned("Literal", "1L", 0)
      ),
      Vector(token(ParserScannerTokenKind.Literal, "1L", 0, 2)),
      ParserPositionProvenance.Synthetic
    ),
    AtomCase(
      source("1.0f"),
      "SingletonTypeTree",
      0,
      Vector("ref"),
      Vector(
        positioned("SingletonTypeTree", "1.0f", 0, ParserPositionProvenance.Synthetic),
        positioned("Literal", "1.0f", 0)
      ),
      Vector(token(ParserScannerTokenKind.Literal, "1.0f", 0, 4)),
      ParserPositionProvenance.Synthetic
    ),
    AtomCase(
      source("1.0"),
      "SingletonTypeTree",
      0,
      Vector("ref"),
      Vector(
        positioned("SingletonTypeTree", "1.0", 0, ParserPositionProvenance.Synthetic),
        positioned("Literal", "1.0", 0)
      ),
      Vector(token(ParserScannerTokenKind.Literal, "1.0", 0, 3)),
      ParserPositionProvenance.Synthetic
    ),
    AtomCase(
      source("'a'"),
      "SingletonTypeTree",
      0,
      Vector("ref"),
      Vector(
        positioned("SingletonTypeTree", "'a'", 0, ParserPositionProvenance.Synthetic),
        positioned("Literal", "'a'", 0)
      ),
      Vector(token(ParserScannerTokenKind.Literal, "'a'", 0, 3)),
      ParserPositionProvenance.Synthetic
    ),
    AtomCase(
      source("\"literal\""),
      "SingletonTypeTree",
      0,
      Vector("ref"),
      Vector(
        positioned("SingletonTypeTree", "\"literal\"", 0, ParserPositionProvenance.Synthetic),
        positioned("Literal", "\"literal\"", 0)
      ),
      Vector(token(ParserScannerTokenKind.Literal, "\"literal\"", 0, 9)),
      ParserPositionProvenance.Synthetic
    ),
    AtomCase(
      source("true"),
      "SingletonTypeTree",
      0,
      Vector("ref"),
      Vector(
        positioned("SingletonTypeTree", "true", 0, ParserPositionProvenance.Synthetic),
        positioned("Literal", "true", 0)
      ),
      Vector(token(ParserScannerTokenKind.Literal, "true", 0, 4)),
      ParserPositionProvenance.Synthetic
    ),
    AtomCase(
      source("(A)"),
      "Parens",
      0,
      Vector("t"),
      Vector(positioned("Parens", "(A)", 0), positioned("Ident", "A", 1)),
      Vector(
        token(ParserScannerTokenKind.LeftParenthesis, "(", 0, 1),
        token(ParserScannerTokenKind.RightParenthesis, ")", 2, 3)
      )
    )
  )
