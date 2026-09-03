package com.hmemcpy.metallurgy.pc

import org.junit.Test

import java.nio.file.Path

final class Scala3MatchLiteralTypePreflightProbeTest:
  private val Versions = Vector("3.5.2", "3.7.4")

  private case class Shape(id: String, pattern: String)

  private val Shapes = Vector(
    Shape("int", "y: 42"),
    Shape("int-folded-minus", "y: -42"),
    Shape("int-hex", "y: 0x1F"),
    Shape("int-max", "y: 2147483647"),
    Shape("int-min-folded", "y: -2147483648"),
    Shape("long", "y: 1L"),
    Shape("float", "y: 1.0f"),
    Shape("double", "y: 1.0"),
    Shape("char", "y: 'a'"),
    Shape("string", "y: \"lit\""),
    Shape("true", "y: true"),
    Shape("false", "y: false"),
    Shape("given-anon", "given 42"),
    Shape("given-named", "n @ given 42"),
    Shape("paren-wrapped", "y: (42)"),
    Shape("applied-argument", "y: Box[42]"),
    Shape("tuple-component", "y: (A, 42)"),
    Shape("wildcard-bound", "y: Box[? <: 42]"),
    Shape("pattern-and-type", "(x: 42)"),
    Shape("nested-double", "y: ((42))"),
    Shape("trailing-comment", "y: 42 /* c */"),
    Shape("literal-ref-suffix", "y: 1.type"),
    Shape("null-type", "y: null"),
    Shape("unit-type", "y: ()"),
    Shape("out-of-range-long", "y: 99999999999999999999"),
    Shape("lowercase-l-suffix", "y: 42l"),
    Shape("d-suffix", "y: 1.0d"),
    Shape("plus-sign", "y: +42")
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
          val snapshot = parse(bridge, source, s"file:///P125M-$version-${shape.id}.scala")
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
                "SingletonTypeTree",
                "Literal",
                "Ident"
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
