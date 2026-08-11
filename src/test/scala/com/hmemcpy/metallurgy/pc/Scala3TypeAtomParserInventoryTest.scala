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
          (ParserScannerTokenKind.Identifier, "x", 0, 1, ParserPositionProvenance.SourceDerived),
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
    "1db31e784b89c666e7f3059d6d35abbad0dec889cb781524c0b905c606d6ba94",
    "2ec75dadae7787eba33951a1f1a734e14e312b22978c4b36cf920f517c57879c",
    "bdb5e264042cb6454f1c2198f1c72381724678c560256cbca76e9aee0610f8fb",
    "68c8aaf4d5cc762ed656f9b441162045aa828c67767814b57a525bf93c4f0d05",
    "2ad82e80591ec0f085f96bc7b93a6a3912682dfeaa3fb5562918368898884611",
    "ae9d7c2cd83559dca15e6e359b19f0f6765e56284ad8b9bd3afd823239c35f14",
    "0414d7261b0d888e33fe531504adaca0a80423d5de22ab34874007a5b6e35836",
    "05084d22f547ddb5fcf3b5cc8fee7c5bcfee342ff5f19a4600218d0d1791f987",
    "3d496f90527a7398773cd4a7740c718515a98a4f9e0152c0d969500d51a88550",
    "3d9706403c3ed88a0b7f2a7e0edf679420c65db7aaf40dcfdf97a7e4db0b0220",
    "13b83845a2c154d8ef39d5e4c0f498272cb2a8a4310319ba7f6c0edb57fb6acb",
    "9eafd11f2ef8181a2fe2ca1c94b186bfc638cc2cf33b76cdf519507bead22d68",
    "a86fd661b627144cdc9c761a1ce345e731e314d72e6423879e0eb1acb050d41f",
    "207b203a9e215c14e0e46bb5438202b639b3e3622efa0fcb457b458e2b8cf65c"
  )

  private val Cases = Vector(
    AtomCase(
      source("A"),
      "Ident",
      0,
      Vector("name"),
      Vector(positioned("Ident", "A", 0)),
      Vector(token(ParserScannerTokenKind.Identifier, "A", 0, 1))
    ),
    AtomCase(
      source("p.A"),
      "Select",
      2,
      Vector("qualifier", "name"),
      Vector(positioned("Select", "p.A", 2), positioned("Ident", "p", 0)),
      Vector(
        token(ParserScannerTokenKind.Identifier, "p", 0, 1),
        token(ParserScannerTokenKind.Dot, ".", 1, 2),
        token(ParserScannerTokenKind.Identifier, "A", 2, 3)
      )
    ),
    AtomCase(
      source("T#A"),
      "Select",
      2,
      Vector("qualifier", "name"),
      Vector(positioned("Select", "T#A", 2), positioned("Ident", "T", 0)),
      Vector(
        token(ParserScannerTokenKind.Identifier, "T", 0, 1),
        token(ParserScannerTokenKind.Hash, "#", 1, 2),
        token(ParserScannerTokenKind.Identifier, "A", 2, 3)
      )
    ),
    AtomCase(
      source("x.type"),
      "SingletonTypeTree",
      0,
      Vector("ref"),
      Vector(positioned("SingletonTypeTree", "x.type", 0), positioned("Ident", "x", 0)),
      Vector(
        token(ParserScannerTokenKind.Identifier, "x", 0, 1),
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
        token(ParserScannerTokenKind.Identifier, "p", 0, 1),
        token(ParserScannerTokenKind.Dot, ".", 1, 2),
        token(ParserScannerTokenKind.Identifier, "x", 2, 3),
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
      Vector(
        token(ParserScannerTokenKind.Identifier, "-", 0, 1),
        token(ParserScannerTokenKind.Literal, "42", 1, 3)
      ),
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
        token(ParserScannerTokenKind.Identifier, "A", 1, 2),
        token(ParserScannerTokenKind.RightParenthesis, ")", 2, 3)
      )
    )
  )
