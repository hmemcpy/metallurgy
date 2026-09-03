package com.hmemcpy.metallurgy.pc

import org.junit.Test

import java.nio.file.Path

final class Scala3MatchSingletonTypePreflightProbeTest:
  private val Versions = Vector("3.5.2", "3.7.4")

  private case class Shape(id: String, pattern: String)

  private val Shapes = Vector(
    Shape("ident", "y: stable.type"),
    Shape("select", "y: owner.stable.type"),
    Shape("deep-select", "y: pkg.owner.stable.type"),
    Shape("backticked", "y: `stable`.type"),
    Shape("given-anon", "given stable.type"),
    Shape("given-named", "n @ given stable.type"),
    Shape("given-wildcard", "_ @ given stable.type"),
    Shape("given-backticked", "`bt` @ given stable.type"),
    Shape("paren-wrapped", "y: (stable.type)"),
    Shape("applied-argument", "y: Box[stable.type]"),
    Shape("tuple-component", "y: (A, stable.type)"),
    Shape("wrapped-tuple-component", "y: (A, (stable.type))"),
    Shape("wildcard-bound", "y: Box[? <: stable.type]"),
    Shape("pattern-and-type", "(x: stable.type)"),
    Shape("nested-double", "y: ((stable.type))"),
    Shape("literal", "y: 1.type"),
    Shape("string-literal", "y: \"s\".type"),
    Shape("this-type", "y: this.type"),
    Shape("super-type", "y: super.type"),
    Shape("hash-qualifier", "y: Outer#Inner.type"),
    Shape("applied-qualifier", "y: F[Int].type"),
    Shape("missing-type-suffix", "y: stable"),
    Shape("missing-dot", "y: stabletype"),
    Shape("malformed-dot", "y: stable. type")
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
          val snapshot = parse(bridge, source, s"file:///P125L-$version-${shape.id}.scala")
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
                "Select",
                "Ident",
                "Literal"
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
