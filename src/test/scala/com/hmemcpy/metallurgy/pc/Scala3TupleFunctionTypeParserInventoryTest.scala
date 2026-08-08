package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.psiproducer.ProvisionalSourceEvidencePlanner
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

import java.nio.file.Path

final class Scala3TupleFunctionTypeParserInventoryTest:

  @Test
  def exactTupleFunctionAndParameterTypesAreDeterministic(): Unit =
    val bridge = openBridge()
    try
      val first  = parse(bridge, Source, "file:///TupleFunctionFirst.scala")
      val second = parse(bridge, Source, "file:///TupleFunctionSecond.scala")
      assertEquals(first.copy(sourceUri = second.sourceUri), second)
      assertEquals(Source, first.sourceText)
      assertEquals(Source, evidence(first).reconstruct(Source))
      assertTrue(first.diagnostics.toString, first.diagnostics.forall(_.severity != ParserDiagnosticSeverity.Error))
      assertEquals(Vector.empty, first.attachments)

      val tuples          = positioned(first, "Tuple")
      assertEquals(
        Vector("(Int, String, Box[(Long, Boolean)])", "(Long, Boolean)", "(name: String, age: Int)"),
        tuples.map(text(first, _))
      )
      assertTrue(tuples.forall(_.fields.map(_.name) == Vector("trees")))
      val namedComponents = positioned(first, "NamedArg")
      assertEquals(Vector("name: String", "age: Int"), namedComponents.map(text(first, _)))
      assertTrue(namedComponents.forall(_.fields.map(_.name) == Vector("name", "arg")))
      assertEquals(
        Vector(
          "Vector(ParserNodeOccurrence(24,Vector(NamedField(trees), RepeatedIndex(0))))",
          "Vector(ParserNodeOccurrence(24,Vector(NamedField(trees), RepeatedIndex(1))))"
        ),
        namedComponents.map(_.occurrences.toString)
      )

      val functions       = positioned(first, "Function")
      assertEquals(
        Vector(
          "(Int, String) => Boolean",
          "Evidence ?=> Int",
          "(x: Box[Int], y: String) => x.type",
          "A => Box[A]"
        ),
        functions.map(text(first, _))
      )
      assertTrue(functions.forall(_.fields.map(_.name) == Vector("args", "body")))
      assertTrue(first.nodes.forall(_.production != "FunctionWithMods"))
      val contextFunction = functions(1)
      assertEquals(
        ParserNodePosition.Positioned(
          PcSourceRange(
            Source.indexOf("Evidence ?=> Int"),
            Source.indexOf("Evidence ?=> Int") + "Evidence ?=> Int".length
          ),
          Source.indexOf("?=>", Source.indexOf("type ContextFunctionType")),
          ParserPositionProvenance.SourceDerived
        ),
        contextFunction.position
      )

      val dependentParameters =
        positioned(first, "ValDef").filter(node => Set("x: Box[Int]", "y: String").contains(text(first, node)))
      assertEquals(Vector("x: Box[Int]", "y: String"), dependentParameters.map(text(first, _)))
      assertEquals(
        Vector(
          "Vector(ParserNodeOccurrence(39,Vector(NamedField(args), RepeatedIndex(0))))",
          "Vector(ParserNodeOccurrence(39,Vector(NamedField(args), RepeatedIndex(1))))"
        ),
        dependentParameters.map(_.occurrences.toString)
      )
      assertTrue(dependentParameters.forall(_.fields.map(_.name) == Vector("name", "tpt", "preRhs", "mods")))

      val poly     = positioned(first, "PolyFunction")
      assertEquals(Vector("[A] => A => Box[A]"), poly.map(text(first, _)))
      assertEquals(Vector("targs", "body"), poly.head.fields.map(_.name))
      val byName   = positioned(first, "ByNameTypeTree")
      assertEquals(Vector("=> Int"), byName.map(text(first, _)))
      assertEquals(Vector("result"), byName.head.fields.map(_.name))
      val repeated = positioned(first, "PostfixOp")
      assertEquals(Vector("String*"), repeated.map(text(first, _)))
      assertEquals(Vector("od", "op"), repeated.head.fields.map(_.name))

      val structuralTokens = first.scannerTokens.filter(token =>
        Set(
          ParserScannerTokenKind.LeftParenthesis,
          ParserScannerTokenKind.RightParenthesis,
          ParserScannerTokenKind.LeftBracket,
          ParserScannerTokenKind.RightBracket,
          ParserScannerTokenKind.Comma,
          ParserScannerTokenKind.Colon,
          ParserScannerTokenKind.FunctionArrow,
          ParserScannerTokenKind.ContextFunctionArrow
        ).contains(token.kind)
      )
      assertTrue(
        structuralTokens.forall(token =>
          Set("(", ")", "[", "]", ",", ":", "=>", "?=>")
            .contains(Source.substring(token.range.startOffset, token.range.endOffset))
        )
      )
      assertEquals(5, structuralTokens.count(_.kind == ParserScannerTokenKind.FunctionArrow))
      assertEquals(1, structuralTokens.count(_.kind == ParserScannerTokenKind.ContextFunctionArrow))

      val relevant               = Set(
        "Tuple",
        "NamedArg",
        "Function",
        "FunctionWithMods",
        "PolyFunction",
        "ByNameTypeTree",
        "PostfixOp",
        "ValDef",
        "TypeDef"
      )
      first.nodes
        .filter(node => relevant(node.production))
        .foreach(node => println(render(first, node)))
      println(s"compilerIdentity=${first.compilerIdentity}")
      println(s"sourceDigest=${first.sourceDigest}")
      println(s"fingerprint=${ParserSyntaxSnapshot.evidenceFingerprint(first)}")
      println(s"attachments=${first.attachments}")
      println(
        s"tokens=${first.scannerTokens.map(token => s"${token.runtimeKind}:${token.kind}:${Source.substring(token.range.startOffset, token.range.endOffset)}:${token.range}")}"
      )
      val higherKinded           = parse(bridge, HigherKindedSource, "file:///HigherKindedFunction.scala")
      assertEquals(HigherKindedSource, evidence(higherKinded).reconstruct(HigherKindedSource))
      assertTrue(
        higherKinded.diagnostics.toString,
        higherKinded.diagnostics.forall(_.severity != ParserDiagnosticSeverity.Error)
      )
      assertEquals(Vector.empty, higherKinded.attachments)
      val higherKindedPoly       = positioned(higherKinded, "PolyFunction").head
      assertEquals("[F[_], A <: F[(Int, String)]] => A => A", text(higherKinded, higherKindedPoly))
      assertEquals(Vector("targs", "body"), higherKindedPoly.fields.map(_.name))
      val higherKindedParameters = positioned(higherKinded, "TypeDef").filter(node =>
        Set("F[_]", "A <: F[(Int, String)]").contains(text(higherKinded, node))
      )
      assertEquals(Vector("F[_]", "A <: F[(Int, String)]"), higherKindedParameters.map(text(higherKinded, _)))
      assertEquals(
        Vector(
          "Vector(ParserNodeOccurrence(11,Vector(NamedField(targs), RepeatedIndex(0))))",
          "Vector(ParserNodeOccurrence(11,Vector(NamedField(targs), RepeatedIndex(1))))"
        ),
        higherKindedParameters.map(_.occurrences.toString)
      )
      val parameterLambda        = positioned(higherKinded, "LambdaTypeTree").head
      assertEquals("_", text(higherKinded, parameterLambda))
      assertEquals(Vector("tparams", "body"), parameterLambda.fields.map(_.name))
      val boundedParameter       =
        positioned(higherKinded, "TypeBoundsTree").find(node => text(higherKinded, node) == "<: F[(Int, String)]").get
      assertEquals(
        "Vector(ParserNodeOccurrence(17,Vector(NamedField(rhs))))",
        boundedParameter.occurrences.toString
      )
    finally bridge.close()

  private def render(snapshot: ParserSyntaxSnapshot, node: ParserSyntaxNode): String =
    s"${node.id}:${node.production}:${node.position}:${text(snapshot, node)}:${node.fields}:${node.occurrences}"

  private def text(snapshot: ParserSyntaxSnapshot, node: ParserSyntaxNode): String = node.position match
    case ParserNodePosition.Positioned(range, _, _) => snapshot.sourceText.substring(range.startOffset, range.endOffset)
    case _                                          => "<synthetic>"

  private def positioned(snapshot: ParserSyntaxSnapshot, production: String): Vector[ParserSyntaxNode] =
    snapshot.nodes.filter(node =>
      node.production == production && node.position.isInstanceOf[ParserNodePosition.Positioned]
    )

  private def evidence(snapshot: ParserSyntaxSnapshot) =
    ProvisionalSourceEvidencePlanner
      .plan(snapshot)
      .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)

  private def parse(bridge: Scala3ParserBridge, source: String, uri: String): ParserSyntaxSnapshot =
    bridge
      .parse(
        Scala3ParserRequest(
          ParserSourceUri.from(uri).fold(sys.error, identity),
          source,
          Vector.empty,
          Scala3ParserCancellation.Never
        )
      )
      .fold(error => throw new AssertionError(error.toString), identity)

  private def openBridge(): Scala3ParserBridge =
    Scala3ParserBridge
      .open(
        Scala3ParserArtifactCoordinate("org.scala-lang", "scala3-compiler_3", ScalaVersion),
        compilerDistribution.map(_.toFile)
      )
      .fold(error => throw new AssertionError(error.toString), identity)

  private def compilerDistribution: Seq[Path] =
    Scala3CompilerResolver.publicCoursier.resolve(ScalaVersion).fold(error => throw error.toException, identity)

  private val ScalaVersion       = "3.7.4"
  private val HigherKindedSource =
    """class Box[A]
      |type NestedPoly = [F[_], A <: F[(Int, String)]] => A => A
      |""".stripMargin
  private val Source             =
    """trait Evidence
      |class Box[A]
      |type TupleType = (Int, String, Box[(Long, Boolean)])
      |type NamedTupleType = (name: String, age: Int)
      |type FunctionType = (Int, String) => Boolean
      |type ContextFunctionType = Evidence ?=> Int
      |type DependentFunctionType = (x: Box[Int], y: String) => x.type
      |type PolyFunctionType = [A] => A => Box[A]
      |def parameterTypes(thunk: => Int, values: String*): Int = ???
      |""".stripMargin
