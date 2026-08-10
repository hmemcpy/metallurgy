package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.psiproducer.ProvisionalSourceEvidencePlanner
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

private[pc] trait Scala3NamedTypeArgumentParserTests extends Scala3ParserTestSupport:

  @Test
  def appliedAndNamedTypeArgumentsRetainExactCompilerParserEvidence(): Unit =
    val bridge = openBridge()
    val source =
      """import scala.language.experimental.namedTypeArguments
        |class Coll[Elem]
        |type One = List[Int]
        |type Two = Either[Int, String]
        |type Three[Elem] = Coll[Elem]
        |def make[A]: A = ???
        |def pair[A, B](first: A, second: B): A = first
        |val positional = pair[Int, String](1, "text")
        |val direct = make[A = Int]
        |val invoked = pair[A = Int](1, "text")
        |val allNamed = pair[A = Int, B = String](1, "text")
        |val trivia = make[A /* left */ = /* right */ Int]
        |""".stripMargin
    try
      val snapshot = parse(bridge, source, "file:///P123CParserEvidence.scala")

      assertTrue(snapshot.diagnostics.isEmpty)
      assertEquals(source, snapshot.sourceText)
      assertEquals(ParserSyntaxSnapshot.digest(source), snapshot.sourceDigest)
      assertEquals(source, ProvisionalSourceEvidencePlanner.plan(snapshot).toOption.get.reconstruct(source))

      def ranged(production: String, text: String, from: Int = 0): ParserSyntaxNode =
        val start = source.indexOf(text, from)
        assertTrue(s"missing source text $text", start >= 0)
        snapshot.nodes
          .find:
            case ParserSyntaxNode(_, `production`, _, ParserNodePosition.Positioned(range, _, _), _) =>
              range == PcSourceRange(start, start + text.length)
            case _                                                                                   => false
          .getOrElse(throw new AssertionError(s"missing $production at '$text'"))

      def nodeField(node: ParserSyntaxNode, name: String): ParserFieldValue =
        node.fields.find(_.name == name).map(_.value).getOrElse(throw new AssertionError(s"missing $name on $node"))

      val one   = ranged("AppliedTypeTree", "List[Int]")
      val two   = ranged("AppliedTypeTree", "Either[Int, String]")
      val three = ranged("AppliedTypeTree", "Coll[Elem]", source.indexOf("type Three"))
      assertTrue(nodeField(one, "tpt").isInstanceOf[ParserFieldValue.Node])
      assertEquals(1, nodeField(one, "args").asInstanceOf[ParserFieldValue.Repeated].values.size)
      assertEquals(2, nodeField(two, "args").asInstanceOf[ParserFieldValue.Repeated].values.size)
      assertEquals(1, nodeField(three, "args").asInstanceOf[ParserFieldValue.Repeated].values.size)

      val positional = ranged("TypeApply", "pair[Int, String]")
      val direct     = ranged("TypeApply", "make[A = Int]")
      val invocation = ranged("Apply", "pair[A = Int](1, \"text\")")
      val nested     = ranged("TypeApply", "pair[A = Int]", source.indexOf("val invoked"))
      assertEquals(ParserFieldValue.Node(nested.id), nodeField(invocation, "fun"))
      assertEquals(2, nodeField(invocation, "args").asInstanceOf[ParserFieldValue.Repeated].values.size)
      assertEquals(2, nodeField(positional, "args").asInstanceOf[ParserFieldValue.Repeated].values.size)
      assertEquals(1, nodeField(direct, "args").asInstanceOf[ParserFieldValue.Repeated].values.size)

      val allNamedStart = source.indexOf("pair[A = Int, B = String]")
      val allNamed      = ranged("TypeApply", "pair[A = Int, B = String]", allNamedStart)
      val namedIds      = nodeField(allNamed, "args")
        .asInstanceOf[ParserFieldValue.Repeated]
        .values
        .collect:
          case ParserFieldValue.Node(id) => id
      assertEquals(2, namedIds.size)
      assertEquals(
        Vector("A", "B"),
        namedIds.map: id =>
          val named = snapshot.nodes.find(_.id == id).get
          assertEquals("NamedArg", named.production)
          assertTrue(
            named.occurrences.exists(occurrence =>
              occurrence.ownerNodeId == allNamed.id && occurrence.fieldPath.nonEmpty
            )
          )
          nodeField(named, "name").asInstanceOf[ParserFieldValue.Name].value
      )

      val trivia = ranged("NamedArg", "A /* left */ = /* right */ Int")
      assertEquals(
        Vector("/* left */", "/* right */"),
        snapshot.comments.filter(_.range.startOffset >= source.indexOf("val trivia")).map(_.raw)
      )
      trivia.position match
        case ParserNodePosition.Positioned(range, point, ParserPositionProvenance.SourceDerived) =>
          assertEquals(
            source.indexOf("A /* left */ = /* right */ Int", source.indexOf("val trivia")),
            range.startOffset
          )
          assertEquals(range.startOffset, point)
        case other                                                                               => throw new AssertionError(s"unexpected named argument position $other")

      val punctuation = snapshot.scannerTokens.filter: token =>
        val text = source.substring(token.range.startOffset, token.range.endOffset)
        text.length == 1 && "[],=".contains(text)
      assertTrue(punctuation.nonEmpty)
      assertTrue(punctuation.forall(_.provenance == ParserPositionProvenance.SourceDerived))
      assertTrue(punctuation.forall(token => token.point == token.range.startOffset))
      assertTrue(
        snapshot.nodes
          .flatMap(_.fields)
          .exists(field => field.name == "mods" && field.value.isInstanceOf[ParserFieldValue.Product])
      )
      assertEquals(snapshot, parse(bridge, source, "file:///P123CParserEvidence.scala"))
    finally bridge.close()

  @Test
  def invalidNamedTypeArgumentContextsRetainExactDiagnostics(): Unit =
    val bridge = openBridge()
    try
      val ordinary = parse(
        bridge,
        "import scala.language.experimental.namedTypeArguments\ntype Bad = List[A = Int]\n",
        "file:///P123COrdinaryNamedNegative.scala"
      )
      val mixed    = parse(
        bridge,
        "import scala.language.experimental.namedTypeArguments\ndef pair[A, B](a: A, b: B) = a\nval bad = pair[A = Int, String](1, \"text\")\n",
        "file:///P123CMixedNamedNegative.scala"
      )

      assertTrue(ordinary.diagnostics.exists(_.severity == ParserDiagnosticSeverity.Error))
      assertTrue(mixed.diagnostics.exists(_.severity == ParserDiagnosticSeverity.Error))
      assertTrue(
        ordinary.diagnostics
          .flatMap(_.position)
          .forall(position => position.point >= 0 && position.point <= ordinary.sourceLength)
      )
      assertTrue(
        mixed.diagnostics
          .flatMap(_.position)
          .forall(position => position.point >= 0 && position.point <= mixed.sourceLength)
      )
      assertEquals(
        ordinary.sourceText,
        ProvisionalSourceEvidencePlanner.plan(ordinary).toOption.get.reconstruct(ordinary.sourceText)
      )
      assertEquals(
        mixed.sourceText,
        ProvisionalSourceEvidencePlanner.plan(mixed).toOption.get.reconstruct(mixed.sourceText)
      )
    finally bridge.close()
