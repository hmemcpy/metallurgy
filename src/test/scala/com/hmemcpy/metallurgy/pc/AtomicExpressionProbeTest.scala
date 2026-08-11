package com.hmemcpy.metallurgy.pc

import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.Test

import java.nio.file.Path

final class AtomicExpressionProbeTest:
  @Test
  def exactAtomicExpressionInventoryRetainsProductsOwnersAndSourceTokens(): Unit =
    val bridge = Scala3ParserBridge
      .open(
        Scala3ParserArtifactCoordinate("org.scala-lang", "scala3-compiler_3", "3.7.4"),
        compilerDistribution.map(_.toFile)
      )
      .fold(error => throw new AssertionError(error.toString), identity)
    try
      val snapshot  = bridge
        .parse(
          Scala3ParserRequest(
            ParserSourceUri.from("file:///AtomicExpressionProbe.scala").fold(sys.error, identity),
            Source,
            Vector.empty
          )
        )
        .fold(error => throw new AssertionError(error.toString), identity)
      assertEquals(Source, snapshot.sourceText)
      assertTrue(snapshot.diagnostics.toString, snapshot.diagnostics.isEmpty)
      val nodes     = snapshot.nodes.map(node => node.id -> node).toMap
      val directRhs = snapshot.nodes
        .flatMap(node =>
          node.occurrences.collect:
            case ParserNodeOccurrence(ownerId, Vector(ParserFieldPathSegment.NamedField("preRhs"))) =>
              (nodes(ownerId), node)
        )
        .filter(_._2.position.isInstanceOf[ParserNodePosition.Positioned])
        .sortBy(value => positioned(value._2).range.startOffset)
      assertEquals(
        Vector(
          ("ValDef", "PackageDef.stats[]", "Ident", "source"),
          ("ValDef", "PackageDef.stats[]", "Number", "0x7f_ff"),
          ("ValDef", "PackageDef.stats[]", "Literal", "9_223L"),
          ("ValDef", "PackageDef.stats[]", "Literal", "1.25f"),
          ("ValDef", "PackageDef.stats[]", "Literal", "2.5d"),
          ("ValDef", "PackageDef.stats[]", "Literal", "true"),
          ("ValDef", "PackageDef.stats[]", "Literal", "false"),
          ("ValDef", "PackageDef.stats[]", "Literal", "'\\n'"),
          ("ValDef", "PackageDef.stats[]", "Literal", "\"a\\tb\""),
          ("ValDef", "PackageDef.stats[]", "Literal", "null"),
          ("DefDef", "Template.preBody[]", "This", "this"),
          ("DefDef", "Template.preBody[]", "This", "C.this"),
          ("ValDef", "PackageDef.stats[]", "Select", "source.member"),
          ("ValDef", "PackageDef.stats[]", "Apply", "source(1)"),
          ("ValDef", "PackageDef.stats[]", "Tuple", "(source, 1)"),
          ("ValDef", "PackageDef.stats[]", "Block", "{ val local = 1; local }"),
          ("ValDef", "Block.stats[]", "Number", "1"),
          ("ValDef", "PackageDef.stats[]", "Number", "-1"),
          ("ValDef", "PackageDef.stats[]", "InterpolatedString", "s\"$source\"")
        ),
        directRhs.map:
          case (owner, node) =>
            (owner.production, ownerContext(nodes, owner), node.production, nodeText(snapshot, node))
      )

      val nullLiteral = directRhs.map(_._2).find(node => nodeText(snapshot, node) == "null").get
      assertEquals(
        Vector(
          ParserSyntaxField(
            "const",
            ParserFieldValue.Product(
              "",
              Vector(
                ParserSyntaxField("", ParserFieldValue.Scalar(ParserScalar.NullValue), None)
              )
            ),
            None
          )
        ),
        nullLiteral.fields
      )
      assertFalse(
        snapshot.nodes
          .flatMap(_.fields)
          .exists(field => field.name != "const" && containsNullValue(field.value))
      )

      val admittedRanges = directRhs
        .take(12)
        .map:
          case (_, node) => positioned(node).range
      val admittedTokens = snapshot.scannerTokens
        .filter(token =>
          admittedRanges.exists(range =>
            token.range.startOffset >= range.startOffset && token.range.endOffset <= range.endOffset
          )
        )
      assertTrue(admittedTokens.forall(_.provenance == ParserPositionProvenance.SourceDerived))
      assertEquals(
        Vector("source", "0x7f_ff", "9_223L", "1.25f", "2.5d", "true", "false", "'\\n'", "\"a\\tb\"", "null"),
        directRhs
          .take(10)
          .map:
            case (_, node) => nodeText(snapshot, node)
      )
      val qualifiedThis  = directRhs.map(_._2).find(node => nodeText(snapshot, node) == "C.this").get
      assertEquals(
        PcSourceRange(Source.indexOf("C.this"), Source.indexOf("C.this") + 6),
        positioned(qualifiedThis).range
      )
      assertEquals(
        Vector("C", ".", "this"),
        snapshot.scannerTokens
          .filter(token =>
            token.range.startOffset >= positioned(qualifiedThis).range.startOffset &&
              token.range.endOffset <= positioned(qualifiedThis).range.endOffset
          )
          .map(token => Source.substring(token.range.startOffset, token.range.endOffset))
      )
      assertFalse(
        directRhs
          .drop(12)
          .exists:
            case (_, node) => Set("Ident", "Literal", "This")(node.production)
      )
    finally bridge.close()

  private def positioned(node: ParserSyntaxNode): ParserNodePosition.Positioned = node.position match
    case value: ParserNodePosition.Positioned => value
    case other                                => throw new AssertionError(s"expected positioned node: $other")

  private def nodeText(snapshot: ParserSyntaxSnapshot, node: ParserSyntaxNode): String =
    val range = positioned(node).range
    snapshot.sourceText.substring(range.startOffset, range.endOffset)

  private def containsNullValue(value: ParserFieldValue): Boolean = value match
    case ParserFieldValue.Scalar(ParserScalar.NullValue) => true
    case ParserFieldValue.Optional(value)                => value.exists(containsNullValue)
    case ParserFieldValue.Repeated(values)               => values.exists(containsNullValue)
    case ParserFieldValue.Product(_, fields)             => fields.exists(field => containsNullValue(field.value))
    case _                                               => false

  private def ownerContext(nodes: Map[Long, ParserSyntaxNode], owner: ParserSyntaxNode): String =
    owner.occurrences.collectFirst:
      case ParserNodeOccurrence(
            parentId,
            Vector(
              ParserFieldPathSegment.NamedField(field),
              ParserFieldPathSegment.RepeatedIndex(_)
            )
          ) =>
        s"${nodes(parentId).production}.$field[]"
    match
      case Some(value) => value
      case None        => throw new AssertionError(s"direct RHS owner has no repeated parent: $owner")

  private def compilerDistribution: Seq[Path] =
    Scala3CompilerResolver.publicCoursier
      .resolve("3.7.4")
      .fold(error => throw error.toException, identity)

  private val Source =
    """val ident = source
      |val integer = 0x7f_ff
      |val long = 9_223L
      |val float = 1.25f
      |val double = 2.5d
      |val boolTrue = true
      |val boolFalse = false
      |val char = '\n'
      |val string = "a\tb"
      |val nil = null
      |class C:
      |  def plainThis = this
      |  def qualifiedThis = C.this
      |val selected = source.member
      |val applied = source(1)
      |val tupled = (source, 1)
      |val blocked = { val local = 1; local }
      |val negative = -1
      |val interpolated = s"$source"
      |""".stripMargin
