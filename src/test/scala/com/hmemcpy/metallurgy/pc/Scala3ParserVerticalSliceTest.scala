package com.hmemcpy.metallurgy.pc

import org.junit.Assert.{assertArrayEquals, assertEquals, assertFalse, assertTrue}
import org.junit.Test

import java.nio.charset.StandardCharsets
import java.nio.file.Path

final class Scala3ParserVerticalSliceTest:

  @Test
  def broadIndentationSourceProducesAStableExactParserSnapshot(): Unit =
    val bridge = openBridge()
    try
      val first  = parse(bridge)
      val second = parse(bridge)

      assertEquals(first, second)
      assertArrayEquals(
        first.toString.getBytes(StandardCharsets.UTF_8),
        second.toString.getBytes(StandardCharsets.UTF_8)
      )
      assertEquals(Source, first.sourceText)
      assertArrayEquals(Source.getBytes(StandardCharsets.UTF_8), first.sourceText.getBytes(StandardCharsets.UTF_8))
      assertEquals(ParserSyntaxSnapshot.digest(Source), first.sourceDigest)
      assertTrue(first.diagnostics.isEmpty)

      val productions = first.nodes.map(_.production).toSet
      RequiredProductions.foreach(production => assertTrue(production, productions(production)))
      assertExactPosition(
        first,
        "PackageDef",
        0,
        Source.indexOf("syntax"),
        Source.stripSuffix("\n").length
      )
      assertExactPosition(
        first,
        "Import",
        Source.indexOf("import scala.collection"),
        Source.indexOf("scala.collection"),
        Source.indexOf("\n\n/**")
      )
      assertExactPosition(
        first,
        "DefDef",
        Source.indexOf("def greeting"),
        Source.indexOf("greeting"),
        Source.indexOf("\n\nobject Program")
      )
      assertContextParameterClauseGrouping(first)
      assertNoUnsupportedValues(first)
      assertAllPositionsBelongToSource(first)
    finally bridge.close()

  private def assertExactPosition(
      snapshot: ParserSyntaxSnapshot,
      production: String,
      expectedStart: Int,
      expectedPoint: Int,
      expectedEnd: Int
  ): Unit =
    val positions = snapshot.nodes.collect:
      case ParserSyntaxNode(_, `production`, _, position: ParserNodePosition.Positioned) => position
    assertTrue(s"$production has no exact source position", positions.nonEmpty)
    assertTrue(
      s"$production does not contain [$expectedStart,$expectedEnd): $positions",
      positions.exists(position =>
        position.range.startOffset == expectedStart &&
          position.point == expectedPoint &&
          position.range.endOffset == expectedEnd
      )
    )

  private def assertContextParameterClauseGrouping(snapshot: ParserSyntaxSnapshot): Unit =
    val greeting = snapshot.nodes.find: node =>
      node.production == "DefDef" &&
        node.fields.contains(ParserSyntaxField("name", ParserFieldValue.Name("greeting")))
    val clauses  = greeting.toVector
      .flatMap(_.fields)
      .collectFirst:
        case ParserSyntaxField("paramss", ParserFieldValue.Repeated(values)) => values
      .getOrElse(throw new AssertionError("greeting parameter clauses are absent"))
    assertEquals(
      Vector(1, 1),
      clauses.map:
        case ParserFieldValue.Repeated(parameters) => parameters.size
        case other                                 => throw new AssertionError(s"unexpected parameter clause $other")
    )

  private def assertNoUnsupportedValues(snapshot: ParserSyntaxSnapshot): Unit =
    def containsUnsupported(value: ParserFieldValue): Boolean =
      value match
        case ParserFieldValue.Unsupported(_)         => true
        case ParserFieldValue.Optional(value)        => value.exists(containsUnsupported)
        case ParserFieldValue.Repeated(values)       => values.exists(containsUnsupported)
        case ParserFieldValue.Product(_, fields)     => fields.exists(field => containsUnsupported(field.value))
        case ParserFieldValue.Node(_)                => false
        case ParserFieldValue.Name(_)                => false
        case ParserFieldValue.GeneratedName(_, _, _) => false
        case ParserFieldValue.Scalar(_)              => false

    assertFalse(snapshot.nodes.flatMap(_.fields).exists(field => containsUnsupported(field.value)))

  private def assertAllPositionsBelongToSource(snapshot: ParserSyntaxSnapshot): Unit =
    snapshot.nodes.foreach:
      case ParserSyntaxNode(_, _, _, ParserNodePosition.Absent)                      => ()
      case ParserSyntaxNode(_, _, _, ParserNodePosition.Positioned(range, point, _)) =>
        assertTrue(range.startOffset >= 0)
        assertTrue(range.endOffset <= Source.length)
        assertTrue(point >= range.startOffset)
        assertTrue(point <= range.endOffset)

  private def parse(bridge: Scala3ParserBridge): ParserSyntaxSnapshot =
    bridge
      .parse(
        Scala3ParserRequest(
          ParserSourceUri
            .from("file:///Scala3ParserVerticalSlice.scala")
            .fold(message => throw new AssertionError(message), identity),
          Source,
          Vector.empty
        )
      )
      .fold(error => throw new AssertionError(error.toString), identity)

  private def openBridge(): Scala3ParserBridge =
    Scala3ParserBridge
      .open(
        Scala3ParserArtifactCoordinate("org.scala-lang", "scala3-compiler_3", ScalaVersion),
        compilerDistribution().map(_.toFile)
      )
      .fold(error => throw new AssertionError(error.toString), identity)

  private def compilerDistribution(): Seq[Path] =
    Scala3CompilerResolver.publicCoursier
      .resolve(ScalaVersion)
      .fold(error => throw error.toException, identity)

  private val RequiredProductions = Set(
    "PackageDef",
    "Import",
    "Template",
    "TypeDef",
    "ValDef",
    "DefDef",
    "Apply",
    "Select"
  )

  private val Source =
    """package example.syntax
      |
      |import scala.collection.immutable.List
      |
      |/** A named value rendered by the sample program. */
      |trait Named:
      |  def name: String
      |
      |final case class Person(name: String, age: Int) extends Named:
      |  def greeting(prefix: String)(using suffix: String): String =
      |    val rendered = List(prefix, name).mkString(" ")
      |    rendered.concat(suffix)
      |
      |object Program:
      |  given String = "!"
      |
      |  def run[A <: Person](values: List[A]): List[String] =
      |    values
      |      .filter(_.age > 0)
      |      .map(person => person.greeting("Hello"))
      |""".stripMargin

  private val ScalaVersion = "3.7.4"
