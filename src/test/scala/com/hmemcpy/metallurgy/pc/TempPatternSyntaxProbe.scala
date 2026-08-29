package com.hmemcpy.metallurgy.pc

import org.junit.Assert.assertTrue
import org.junit.Test

final class TempPatternSyntaxProbe:

  @Test
  def dumpPatternSyntaxShapes(): Unit =
    probe(
      """def classify(x: Any): Any =
        |  x match
        |    case List(t2, old2 @ _*) => old2
        |    case List(t3, star3*) => star3
        |    case _: Int => "wildcardTyped"
        |""".stripMargin
    )

  private def probe(source: String): Unit =
    val bridge = Scala3ParserBridge
      .open(
        Scala3ParserArtifactCoordinate("org.scala-lang", "scala3-compiler_3", ScalaVersion),
        Scala3CompilerResolver.publicCoursier
          .resolve(ScalaVersion)
          .fold(error => throw error.toException, _.map(_.toFile))
      )
      .fold(error => throw AssertionError(error.toString), identity)
    try
      val snapshot = bridge
        .parse(
          Scala3ParserRequest(
            ParserSourceUri.from("file:///PatternSyntaxProbe.scala").fold(sys.error, identity),
            source,
            Vector.empty,
            Scala3ParserCancellation.Never
          )
        )
        .fold(error => throw AssertionError(error.toString), identity)
      assertTrue(
        snapshot.diagnostics.toString,
        snapshot.diagnostics.forall(_.severity != ParserDiagnosticSeverity.Error)
      )
      snapshot.nodes.foreach: node =>
        val position = node.position match
          case ParserNodePosition.Positioned(range, point, provenance) =>
            s"range=${range.startOffset}-${range.endOffset} point=$point $provenance"
          case other                                                   => s"$other"
        val fields   = node.fields
          .map: field =>
            s"${field.name}=${summarize(field.value.toString)}"
          .mkString(" ")
        println(s"[sprobe] node=${node.production} id=${node.id} $position fields{$fields}")
    finally bridge.close()

  private def summarize(value: String): String =
    val oneLine = value.replaceAll("\\s+", " ")
    if oneLine.length <= 90 then oneLine else oneLine.take(87) + "..."

  private val ScalaVersion = "3.7.4"
