package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.psiproducer.ProvisionalSourceEvidencePlanner
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

import java.nio.file.Path

final class Scala3OwnerTypeMountParserInventoryTest:

  @Test
  def exactOwnerTypeFieldsRangesAndDerivesSupplementAreDeterministic(): Unit =
    val source = OwnerTypeMountSource
    val bridge = openBridge()
    try
      val first  = parse(bridge, source, "file:///OwnerTypeMountInventoryFirst.scala")
      val second = parse(bridge, source, "file:///OwnerTypeMountInventorySecond.scala")
      assertEquals(first.copy(sourceUri = second.sourceUri), second)
      assertEquals(source, first.sourceText)
      assertEquals(source, evidence(first).reconstruct(source))
      assertTrue(first.diagnostics.toString, first.diagnostics.isEmpty)
      assertEquals(
        "50e6e2a6c6a314613d645d15b59073ffb762fca4f8d814dd6d79da567e245fd2",
        ParserSyntaxSnapshot.evidenceFingerprint(first)
      )

      Vector("topResult", "declared", "result").foreach(name =>
        assertNodeText(first, node(first, "DefDef", name), "tpt", "Base")
      )
      Vector("topValue", "topVariable", "value", "variable", "value", "context").foreach(name =>
        assertTrue(s"ValDef $name is missing", namedNodes(first, "ValDef", name).nonEmpty)
      )
      namedNodes(first, "ValDef", "value").foreach(value => assertNodeText(first, value, "tpt", "Base"))
      assertNodeText(first, node(first, "ValDef", "context"), "tpt", "Other")
      Vector("TopAlias", "Alias").foreach(name => assertNodeText(first, node(first, "TypeDef", name), "rhs", "Base"))

      val membersTemplate = child(first, node(first, "TypeDef", "Members"), "rhs")
      assertEquals(Vector("Base"), repeatedChildren(first, membersTemplate, "preParentsOrDerived").map(text(first, _)))
      val self            = child(first, membersTemplate, "self")
      assertEquals("self: Other =>", text(first, self))
      assertNodeText(first, self, "tpt", "Other")

      val parametersTemplate = child(first, node(first, "TypeDef", "Parameters"), "rhs")
      val constructor        = child(first, parametersTemplate, "constr")
      assertEquals(Vector(Vector("value"), Vector("context")), groupedNames(first, constructor, "paramss"))
      assertEquals(
        Vector("Base"),
        repeatedChildren(first, parametersTemplate, "preParentsOrDerived").map(text(first, _))
      )

      val parenthesized = child(first, node(first, "TypeDef", "ParenthesizedParent"), "rhs")
      assertEquals(Vector("Parens"), repeatedChildren(first, parenthesized, "preParentsOrDerived").map(_.production))
      val derived       = child(first, node(first, "TypeDef", "Derived"), "rhs")
      assertEquals(Vector("CanEqual"), repeatedChildren(first, derived, "preParentsOrDerived").map(text(first, _)))
      assertEquals(
        Vector(1),
        first.runtimeSupplements
          .filter(_.ownerNodeId == derived.id)
          .flatMap(_.fields)
          .collect:
            case ParserSyntaxField("derivedCount", ParserFieldValue.Scalar(ParserScalar.Integer(value)), _) => value
      )
    finally bridge.close()

  @Test
  def applicationShapedParentRemainsDistinctAndFailClosed(): Unit =
    val bridge = openBridge()
    try
      val snapshot = parse(bridge, ParentConstructorSource, "file:///ParentConstructorNegative.scala")
      assertTrue(snapshot.diagnostics.toString, snapshot.diagnostics.isEmpty)
      assertEquals(ParentConstructorSource, evidence(snapshot).reconstruct(ParentConstructorSource))
      val template = child(snapshot, node(snapshot, "TypeDef", "Rejected"), "rhs")
      val parent   = repeatedChildren(snapshot, template, "preParentsOrDerived").head
      assertEquals("Apply", parent.production)
      assertEquals("Parent(1)", text(snapshot, parent))
      assertEquals("Select", child(snapshot, parent, "fun").production)
    finally bridge.close()

  private def groupedNames(
      snapshot: ParserSyntaxSnapshot,
      node: ParserSyntaxNode,
      field: String
  ): Vector[Vector[String]] =
    val nodes = snapshot.nodes.map(value => value.id -> value).toMap
    node.fields.collectFirst:
      case ParserSyntaxField(`field`, ParserFieldValue.Repeated(groups), _) =>
        groups.map:
          case ParserFieldValue.Repeated(values) => values.collect { case ParserFieldValue.Node(id) => id }
          case _                                 => Vector.empty
    match
      case Some(groups) =>
        groups.map(
          _.map(id =>
            nodes(id).fields.collectFirst { case ParserSyntaxField("name", ParserFieldValue.Name(value), _) =>
              value
            }.get
          )
        )
      case None         => Vector.empty

  private def repeatedChildren(
      snapshot: ParserSyntaxSnapshot,
      owner: ParserSyntaxNode,
      field: String
  ): Vector[ParserSyntaxNode] =
    val nodes = snapshot.nodes.map(value => value.id -> value).toMap
    owner.fields.collectFirst:
      case ParserSyntaxField(`field`, ParserFieldValue.Repeated(values), _) =>
        values.collect { case ParserFieldValue.Node(id) => nodes(id) }
    match
      case Some(values) => values
      case None         => Vector.empty

  private def child(snapshot: ParserSyntaxSnapshot, owner: ParserSyntaxNode, field: String): ParserSyntaxNode =
    val id = owner.fields.collectFirst { case ParserSyntaxField(`field`, ParserFieldValue.Node(value), _) => value }.get
    snapshot.nodes.find(_.id == id).get

  private def node(snapshot: ParserSyntaxSnapshot, production: String, name: String): ParserSyntaxNode =
    namedNodes(snapshot, production, name).head

  private def namedNodes(snapshot: ParserSyntaxSnapshot, production: String, name: String): Vector[ParserSyntaxNode] =
    snapshot.nodes.filter(value =>
      value.production == production && value.fields.contains(
        ParserSyntaxField("name", ParserFieldValue.Name(name), Some(ParserDeclaredShape.Name))
      )
    )

  private def assertNodeText(
      snapshot: ParserSyntaxSnapshot,
      owner: ParserSyntaxNode,
      field: String,
      expected: String
  ): Unit =
    assertEquals(expected, text(snapshot, child(snapshot, owner, field)))

  private def text(snapshot: ParserSyntaxSnapshot, node: ParserSyntaxNode): String = node.position match
    case ParserNodePosition.Positioned(range, _, _) => snapshot.sourceText.substring(range.startOffset, range.endOffset)
    case other                                      => throw new AssertionError(s"${node.production} has no source range: $other")

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

  private val ScalaVersion            = "3.7.4"
  private val OwnerTypeMountSource    =
    """package owneratoms
      |
      |trait Base
      |trait Other
      |
      |type TopAlias = Base
      |def topResult: Base = new Base {}
      |val topValue: Base = topResult
      |var topVariable: Base = topValue
      |
      |trait Members extends Base:
      |  self: Other =>
      |
      |  type Alias = Base
      |  def declared: Base
      |  def result(value: Base): Base = value
      |  val value: Base
      |  var variable: Base
      |
      |class Parameters(value: Base)(using context: Other) extends Base
      |class ParenthesizedParent extends (Base)
      |enum Derived derives CanEqual:
      |  case Only
      |""".stripMargin
  private val ParentConstructorSource =
    """package parentnegative
      |
      |trait Base
      |class Parent(value: Int)
      |class Rejected extends Parent(1)
      |""".stripMargin
