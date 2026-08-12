package com.hmemcpy.metallurgy.pc

import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

import java.nio.file.Path

final class SelectionExpressionProbeTest:
  @Test
  def exactSelectionInventoryRetainsRecursiveQualifiersSuperFormsAndSourceRanges(): Unit =
    val bridge = Scala3ParserBridge
      .open(
        Scala3ParserArtifactCoordinate("org.scala-lang", "scala3-compiler_3", "3.7.4"),
        compilerDistribution.map(_.toFile)
      )
      .fold(error => throw new AssertionError(error.toString), identity)
    try
      val snapshot = bridge
        .parse(
          Scala3ParserRequest(
            ParserSourceUri.from("file:///SelectionExpressionProbe.scala").fold(sys.error, identity),
            Source,
            Vector.empty
          )
        )
        .fold(error => throw new AssertionError(error.toString), identity)
      assertTrue(snapshot.diagnostics.toString, snapshot.diagnostics.isEmpty)
      val nodes    = snapshot.nodes.map(node => node.id -> node).toMap
      val roots    = snapshot.nodes
        .filter(node =>
          node.occurrences.exists:
            case ParserNodeOccurrence(ownerId, Vector(ParserFieldPathSegment.NamedField("preRhs"))) =>
              val owner = nodes(ownerId)
              Set("DefDef", "ValDef")(owner.production) && owner.occurrences.exists:
                case ParserNodeOccurrence(
                      ancestorId,
                      Vector(ParserFieldPathSegment.NamedField(field), ParserFieldPathSegment.RepeatedIndex(_))
                    ) =>
                  Set("PackageDef" -> "stats", "Template" -> "preBody")(
                    nodes(ancestorId).production -> field
                  )
                case _ => false
            case _                                                                                  => false
        )
        .filter(_.position.isInstanceOf[ParserNodePosition.Positioned])
        .sortBy(node => positioned(node).range.startOffset)

      assertEquals(
        Vector(
          "Select(source.member)[13,26)@20{qualifier=Ident(source)[13,19)@13{name=source},name=member}",
          "Select(source.mid.member)[39,56)@50{qualifier=Select(source.mid)[39,49)@46{qualifier=Ident(source)[39,45)@39{name=source},name=mid},name=member}",
          "Select(this.member)[68,79)@73{qualifier=This(this)[68,72)@68{qual=Ident()[absent]{name=}},name=member}",
          "Select(C.this.member)[100,113)@107{qualifier=This(C.this)[100,106)@100{qual=Ident(C.)[100,102)@100{name=C}},name=member}",
          "Select(super.member)[127,139)@133{qualifier=Super(super)[127,132)@127{qual=This()[127,127)@127{qual=Ident()[absent]{name=}},mix=Ident()[absent]{name=}},name=member}",
          "Select(super[Mixin].member)[152,171)@165{qualifier=Super(super[Mixin])[152,164)@152{qual=This()[158,158)@158{qual=Ident()[absent]{name=}},mix=Ident(Mixin)[158,163)@158{name=Mixin}},name=member}",
          "Select(C.super.member)[206,220)@214{qualifier=Super(C.super)[206,213)@206{qual=This(C.)[206,208)@206{qual=Ident(C.)[206,208)@206{name=C}},mix=Ident()[absent]{name=}},name=member}",
          "Select(C.super[Mixin].member)[240,261)@255{qualifier=Super(C.super[Mixin])[240,254)@240{qual=This(C.)[240,242)@240{qual=Ident(C.)[240,242)@240{name=C}},mix=Ident(Mixin)[248,253)@248{name=Mixin}},name=member}",
          "Apply(source.member())[276,291)@289{fun=Select(source.member)[276,289)@283{qualifier=Ident(source)[276,282)@276{name=source},name=member},args=Repeated(Vector())}",
          "InfixOp(source member 1)[304,319)@311{left=Ident(source)[304,310)@304{name=source},op=Ident(member)[311,317)@311{name=member},right=Number(1)[318,319)@318{digits=Scalar(Text(1)),kind=Product(Whole,Vector(ParserSyntaxField(radix,Scalar(Integer(10)),Some(Scalar(Integer)))))}}",
          "Block({ source.member })[334,351)@334{stats=Repeated(Vector()),expr=Select(source.member)[336,349)@343{qualifier=Ident(source)[336,342)@336{name=source},name=member}}",
          "Block(val local = source.member\n  local)[366,399)@366{stats=Repeated(Vector(Node(70))),expr=Ident(local)[394,399)@394{name=local}}"
        ),
        roots.map(render(snapshot, nodes, _))
      )

      roots
        .take(8)
        .foreach: root =>
          assertEquals("Select", root.production)
          val range  = positioned(root).range
          val tokens = snapshot.scannerTokens
            .filter(token => token.range.startOffset >= range.startOffset && token.range.endOffset <= range.endOffset)
          assertTrue(tokens.forall(_.provenance == ParserPositionProvenance.SourceDerived))
          assertEquals(
            ".",
            Source.substring(
              tokens.filter(_.kind == ParserScannerTokenKind.Dot).last.range.startOffset,
              tokens.filter(_.kind == ParserScannerTokenKind.Dot).last.range.endOffset
            )
          )

      roots.drop(8).foreach(root => assertTrue(root.production, root.production != "Select"))
      val localSelection = snapshot.nodes
        .filter(node => text(snapshot, node) == "source.member")
        .sortBy(node => positioned(node).range.startOffset)
        .last
      assertTrue(
        localSelection.occurrences.exists(occurrence => nodes(occurrence.ownerNodeId).production == "ValDef")
      )
    finally bridge.close()

  private def render(
      snapshot: ParserSyntaxSnapshot,
      nodes: Map[Long, ParserSyntaxNode],
      node: ParserSyntaxNode
  ): String =
    val position = node.position match
      case ParserNodePosition.Absent                      => "[absent]"
      case ParserNodePosition.Positioned(range, point, _) => s"[${range.startOffset},${range.endOffset})@$point"
    val fields   = node.fields.map(field => s"${field.name}=${renderValue(snapshot, nodes, field.value)}").mkString(",")
    s"${node.production}(${text(snapshot, node)})$position${if fields.isEmpty then "" else s"{$fields}"}"

  private def renderValue(
      snapshot: ParserSyntaxSnapshot,
      nodes: Map[Long, ParserSyntaxNode],
      value: ParserFieldValue
  ): String = value match
    case ParserFieldValue.Node(id)                    => render(snapshot, nodes, nodes(id))
    case ParserFieldValue.Name(value)                 => value
    case ParserFieldValue.GeneratedName(base, sep, i) => s"$base$sep$i"
    case other                                        => other.toString

  private def positioned(node: ParserSyntaxNode): ParserNodePosition.Positioned = node.position match
    case value: ParserNodePosition.Positioned => value
    case other                                => throw new AssertionError(s"expected positioned node: $other")

  private def text(snapshot: ParserSyntaxSnapshot, node: ParserSyntaxNode): String = node.position match
    case ParserNodePosition.Positioned(range, _, _) => snapshot.sourceText.substring(range.startOffset, range.endOffset)
    case ParserNodePosition.Absent                  => ""

  private def compilerDistribution: Seq[Path] =
    Scala3CompilerResolver.publicCoursier
      .resolve("3.7.4")
      .fold(error => throw error.toException, identity)

  private val Source =
    """val simple = source.member
      |val chain = source.mid.member
      |val self = this.member
      |val qualifiedSelf = C.this.member
      |val parent = super.member
      |val mixin = super[Mixin].member
      |class Nested:
      |  val outerParent = C.super.member
      |  val outerMixin = C.super[Mixin].member
      |val applied = source.member()
      |val infix = source member 1
      |val blocked = { source.member }
      |def owner =
      |  val local = source.member
      |  local
      |""".stripMargin
