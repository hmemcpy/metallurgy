package com.hmemcpy.metallurgy.pc

import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

import java.nio.file.Path

final class Scala3ParserLegacyArtifactsTest:

  @Test
  def scala30ArtifactSatisfiesTheNeutralParserContract(): Unit =
    assertParserContract("3.0.2")

  @Test
  def scala31ArtifactSatisfiesTheNeutralParserContract(): Unit =
    assertParserContract("3.1.3")

  @Test
  def scala32ArtifactSatisfiesTheNeutralParserContract(): Unit =
    assertParserContract("3.2.2")

  @Test
  def scala331ArtifactSatisfiesTheNeutralParserContract(): Unit =
    assertParserContract("3.3.1")

  @Test
  def modernArtifactSatisfiesTheSameNeutralParserContract(): Unit =
    assertParserContract("3.7.4")

  private def assertParserContract(scalaVersion: String): Unit =
    val bridge = openBridge(scalaVersion)
    try
      val first  = parse(bridge, scalaVersion, "Valid", Source)
      val second = parse(bridge, scalaVersion, "Valid", Source)

      assertEquals(first, second)
      assertEquals(Source, first.sourceText)
      assertEquals(ParserSyntaxSnapshot.digest(Source), first.sourceDigest)
      assertTrue(first.diagnostics.isEmpty)
      assertTrue(first.capabilities.requiredUnavailable.isEmpty)
      assertEquals(Vector(ParserComment(PcSourceRange(0, 11), "// retained", ParserCommentKind.Line)), first.comments)

      val root = first.nodes
        .find(_.id == first.rootNodeId)
        .getOrElse:
          throw new AssertionError("parser root is absent")
      assertEquals("PackageDef", root.production)
      assertEquals(Vector("pid", "stats"), root.fields.map(_.name))
      assertEquals(
        ParserNodePosition.Positioned(
          PcSourceRange(Source.indexOf("object A"), Source.stripSuffix("\n").length),
          Source.indexOf("object A"),
          ParserPositionProvenance.Synthetic
        ),
        root.position
      )

      val function  = first.nodes.find: node =>
        node.production == "DefDef" &&
          node.fields.exists(field => field.name == "name" && field.value == ParserFieldValue.Name("value"))
      assertEquals(
        Vector("name", "paramss", "tpt", "preRhs", "mods"),
        function.toVector.flatMap(_.fields).map(_.name)
      )
      val modifiers = first.nodes
        .flatMap(_.fields)
        .collectFirst:
          case ParserSyntaxField("mods", ParserFieldValue.Product("Modifiers", fields), _) => fields
        .getOrElse(throw new AssertionError("definition modifiers are absent"))
      assertEquals(
        Some(ParserDeclaredShape.Scalar("LongInteger")),
        modifiers.find(_.name == "flags").flatMap(_.declaredShape)
      )

      val recovered = parse(bridge, scalaVersion, "Incomplete", IncompleteSource)
      assertTrue(recovered.nodes.exists(_.id == recovered.rootNodeId))
      assertTrue(recovered.diagnostics.exists(_.severity == ParserDiagnosticSeverity.Error))
    finally bridge.close()

  private def parse(
      bridge: Scala3ParserBridge,
      scalaVersion: String,
      caseName: String,
      source: String
  ): ParserSyntaxSnapshot =
    bridge
      .parse(
        Scala3ParserRequest(
          ParserSourceUri
            .from(s"file:///Scala${scalaVersion.replace('.', '_')}$caseName.scala")
            .fold(message => throw new AssertionError(message), identity),
          source,
          Vector.empty
        )
      )
      .fold(error => throw new AssertionError(error.toString), identity)

  private def openBridge(scalaVersion: String): Scala3ParserBridge =
    Scala3ParserBridge
      .open(
        Scala3ParserArtifactCoordinate("org.scala-lang", "scala3-compiler_3", scalaVersion),
        compilerDistribution(scalaVersion).map(_.toFile)
      )
      .fold(error => throw new AssertionError(error.toString), identity)

  private def compilerDistribution(scalaVersion: String): Seq[Path] =
    Scala3CompilerResolver.publicCoursier
      .resolve(scalaVersion)
      .fold(error => throw error.toException, identity)

  private val Source =
    """// retained
      |object A:
      |  def value[T](using current: T): T = current
      |""".stripMargin

  private val IncompleteSource =
    """object Broken:
      |  def value(
      |""".stripMargin
