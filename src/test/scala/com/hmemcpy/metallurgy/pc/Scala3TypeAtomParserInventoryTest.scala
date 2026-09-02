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
    "46a6d5c395e38063869c033d0e700127fcee91b7fe724b47009403c9a5bd76a7",
    "cb4810e645849414fd8a5ee8ca91d3fa98ebc3299e40f51c6f34f3c028699ef5",
    "9a71c4bb748d6aa327ea5b66b70b7eb5046a1b32180353015832d3035b08b5c7",
    "5c20ae699a46b2acbfc1b6f98e466d2f36afd873bb0602bf4047bb30636ff820",
    "b4a279f27ffbc9cef711b6cf47f530c0130ad023268089f02aa5426e7fd847c0",
    "5f00933782a794257dcee2b14289f86f36476c5331cfb156c8a4c4713dfc601d",
    "ce988b0b67ccecdefa8351361265060390e365b7ee2911dcc80c71405cc96187",
    "908ffa41a630705d8effe228e40c2fc3485cecdc2fc0806cb10c473c73ce0c78",
    "9026db0c0728f61ec960d8739171db9fac04c21d01b768f96ec0f2894dee62a1",
    "b66509f41605fc8dced654f8942f113fc88088e1ac8230aefaaac92ab4370365",
    "1768693eee38418e9c2e7453d8cb6872bcbe4264dc89310ef0ee99fc8b2019e5",
    "4bc4088a3311a549251a0d6b425853b8c1372ae11656e63b3fd724613703755c",
    "a7f1ee334ac76f447d3fc37ba94a4b9d61e263b8af3716ff958f0e9d4025b855",
    "1c3fa0f0062c307eea84e4a5bfade7ce0a531240b1cca72f9a3a4b73a0a3c1a2"
  )
  private val ScannerFingerprints  = Vector(
    "7c4e92a45538ff4796fde10b41486bbef6bc6694eae6fb11b0d11c1f67744e82",
    "8cf376504a1158a78d99f6e421f709001de684c0b8bd9243fc06075b2fbcdd5e",
    "eef1097047a109704756ee9d4c24f66e2493d2ab2d026a598824e56d9d4674a3",
    "43691074a1625296e69832cf130c1c55aea6cf37c72723b2522aea9dce67f45b",
    "eae30ed13c98f559f3f7277af3253501e8f23ffe7a56c45f25d08939b28be886",
    "6b81af7e5261d355e80d1fa2b38a6531c634dd1a2b32a5dc1e0f44421582333d",
    "a8f087f73d98381649b9a2fc10834714dd418d4bbe1d67d9ac0eaca5bb85b7e9",
    "c2f973697860882f375429d7a1976feac1c60295fdb9befe875595dfb68660e1",
    "151447b3a3798b2d0c7989a2e00ec717832aac8fbd0825dbef0f49765fbd7224",
    "04f1b745c0d38fda4b41d27b4152d67e3c7a1541a7688d347e354dbb0fabd443",
    "7e3e545ea5e661d0afae7f1b80e1f826a4cae05ac15ee0d4f8a0128c2d6cfb80",
    "da4eeda56163dc2ea62d90826afcf8519a4f6fb9ae6adf270a149ab35492577d",
    "2dd86afdcc6e34f64d644b67e4289cab5f493be3fd73d5803fc26f797e10e6a8",
    "84d6caea1aabce2a6d587a18c1f5cc69467938dba9ea765ff1dc1ec6ebdeddcf"
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
