package com.hmemcpy.metallurgy.pc

import org.junit.Test

import java.nio.file.Path

final class Scala3MatchParenthesizedTypePreflightProbeTest:
  private val Versions = Vector("3.5.2", "3.7.4")

  private case class Shape(id: String, pattern: String)

  private val Shapes = Vector(
    Shape("single", "y: (A)"),
    Shape("nested", "y: ((A))"),
    Shape("applied-wrapper", "y: (F[A])"),
    Shape("wrapped-constructor", "y: (F)[A]"),
    Shape("wrapped-argument", "y: F[(A)]"),
    Shape("tuple-components", "y: (A, (B))"),
    Shape("wrapped-tuple", "y: ((A, B))"),
    Shape("wildcard-bounds", "y: F[? >: (A) <: (B)]"),
    Shape("dotted", "y: (p.q.T)"),
    Shape("projection", "y: (P#T)"),
    Shape("given", "given (A)"),
    Shape("given-double-paren", "given ((Int, String))"),
    Shape("given-named", "n @ given (A)"),
    Shape("given-wildcard", "_ @ given (A)"),
    Shape("given-backticked", "`bt` @ given (A)"),
    Shape("nested-tuple-regression", "y: ((Int, String))"),
    Shape("deep-composition", "y: F[((A, (B)))]"),
    Shape("bounds-wrapped-tuple", "y: Box[? >: ((A, B))]"),
    Shape("pattern-and-type", "(x: (A))"),
    Shape("paren-select-qualifier", "y: (P)#T"),
    Shape("unit", "y: ()"),
    Shape("trailing-comma", "y: (A,)"),
    Shape("function", "y: (A => B)"),
    Shape("refinement", "y: (A { type X })"),
    Shape("annotation", "y: (A @unchecked)"),
    Shape("intersection", "y: (A & B)"),
    Shape("union", "y: (A | B)"),
    Shape("singleton", "y: (p.type)"),
    Shape("literal", "y: (1)"),
    Shape("named-tuple", "y: ((a: A, b: B))"),
    Shape("missing-close", "y: (A")
  )

  @Test
  def dumpExactShapes(): Unit =
    Versions.foreach: version =>
      val bridge = openBridge(version)
      try
        Shapes.foreach: shape =>
          val source   =
            s"""def probe(x: Any): Any = x match
               |  case ${shape.pattern} => 1
               |""".stripMargin
          val snapshot = parse(bridge, source, s"file:///P125K-$version-${shape.id}.scala")
          val relevant = snapshot.nodes
            .filter(node =>
              Set(
                "CaseDef",
                "Typed",
                "Bind",
                "Parens",
                "Tuple",
                "AppliedTypeTree",
                "TypeBoundsTree",
                "Select",
                "Ident",
                "Function",
                "RefinedTypeTree",
                "Annotated",
                "AndTypeTree",
                "OrTypeTree",
                "SingletonTypeTree",
                "Literal",
                "NamedArg"
              )(node.production)
            )
            .sortBy(node =>
              node.position match
                case ParserNodePosition.Positioned(range, _, _) => (range.startOffset, -range.endOffset)
                case _                                          => (Int.MaxValue, 0)
            )
            .map: node =>
              val position    = node.position match
                case ParserNodePosition.Positioned(range, _, provenance) =>
                  s"${range.startOffset}:${range.endOffset}:$provenance"
                case other                                               => other.toString
              val occurrences = node.occurrences.map(occurrence =>
                s"${snapshot.nodes.find(_.id == occurrence.ownerNodeId).map(_.production).getOrElse("?")}.${occurrence.fieldPath.mkString("/")}"
              )
              s"${node.id}:${node.production}@$position fields=${node.fields.map(field => s"${field.name}=${field.value}").mkString("[")}${"]"} occurrences=${occurrences.mkString("[")}${"]"}"
          val tokens   = snapshot.scannerTokens
            .filter(token =>
              token.range.startOffset >= source.indexOf("case ") && token.range.endOffset <= source.indexOf(" =>")
            )
            .map(token =>
              s"${token.kind}:${token.range.startOffset}:${token.range.endOffset}:'${source.substring(token.range.startOffset, token.range.endOffset)}'"
            )
          println(s"=== $version ${shape.id} fingerprint=${ParserSyntaxSnapshot.evidenceFingerprint(snapshot)} ===")
          relevant.foreach(println)
          println(s"TOKENS ${tokens.mkString(" | ")}")
          println(s"DIAGNOSTICS ${snapshot.diagnostics.mkString(" | ")}")
      finally bridge.close()

  private def distribution(version: String): Seq[Path] =
    Scala3CompilerResolver.publicCoursier.resolve(version).fold(error => throw error.toException, identity)

  private def openBridge(version: String): Scala3ParserBridge =
    Scala3ParserBridge
      .open(
        Scala3ParserArtifactCoordinate("org.scala-lang", "scala3-compiler_3", version),
        distribution(version).map(_.toFile)
      )
      .fold(error => throw new AssertionError(error.toString), identity)

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
