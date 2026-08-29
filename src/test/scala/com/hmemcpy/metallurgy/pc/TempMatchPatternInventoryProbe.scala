package com.hmemcpy.metallurgy.pc

import org.junit.Assert.assertTrue
import org.junit.Test
final class TempMatchPatternInventoryProbe:

  @Test
  def dumpMatchExpressionInventoryShapes(): Unit =
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
            ParserSourceUri.from("file:///MatchPatternsProbe.scala").fold(sys.error, identity),
            Source,
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
        println(s"[mprobe] node=${node.production} id=${node.id} $position fields{$fields}")
      snapshot.positioned.foreach: p =>
        println(
          s"[mprobe] positioned=${p.production} id=${p.id} fields{${p.fields.map(f => s"${f.name}=${summarize(f.value.toString)}").mkString(" ")}}"
        )
    finally bridge.close()

  private def summarize(value: String): String =
    val oneLine = value.replaceAll("\\s+", " ")
    if oneLine.length <= 90 then oneLine else oneLine.take(87) + "..."

  private val ScalaVersion = "3.7.4"

  private val Source =
    """import scala.collection.immutable.Nil
      |def classify(x: Any): Any =
      |  x match
      |    case _ => "wildcard"
      |    case v => v
      |    case Nil => "stable"
      |    case 42 => "int"
      |    case s: String => s
      |    case n @ Some(v0) => n
      |    case (a, b) => b
      |    case Some(0) => "zero"
      |    case 1 | 2 => "alt"
      |    case List(t, rest*) => rest
      |    case n2: Int if n2 > 0 => n2
      |def braced(y: Any): Any = y match { case 1 => 2; case _ => 3 }
      |""".stripMargin
