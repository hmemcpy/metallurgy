package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.psiproducer.{
  AggregatedCompilerProductionInventory,
  CompilerRuntimeInventory,
  FactStatus,
  ImportPersistenceSurfaces,
  PersistenceObligations,
  PlanningWorkObserver,
  PreparedProductionCatalog,
  ProvisionalSourceEvidencePlanner,
  Scala3PsiProductionCatalog,
  ScalaPsiSurfaceInventory,
  ScalaPsiSurfaceRow,
  SurfaceClassification,
  SurfaceFactKind,
  TerminalDeclaration,
  TerminalLeafTarget,
  WholeFileProductionPlanner
}
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

import java.nio.file.Path

final class Scala3RefinementAnnotatedTypeParserInventoryTest:

  @Test
  def exactRefinementAndAnnotatedTypeProductsAreDeterministic(): Unit =
    val bridge = openBridge()
    try
      val first  = parse(bridge, Source, "file:///RefinementAnnotatedFirst.scala")
      val second = parse(bridge, Source, "file:///RefinementAnnotatedSecond.scala")
      assertEquals(first.copy(sourceUri = second.sourceUri), second)
      assertEquals(Source, first.sourceText)
      assertEquals(Source, evidence(first).reconstruct(Source))
      assertTrue(first.diagnostics.toString, first.diagnostics.forall(_.severity != ParserDiagnosticSeverity.Error))
      assertEquals(Vector.empty, first.attachments)

      val refinements = positioned(first, "RefinedTypeTree")
      assertEquals(
        Vector(
          """AnyRef {
            |  type Elem <: A
            |  val value: Elem
            |  def map(x: Elem): List[Elem @uncheckedVariance]
            |}""".stripMargin,
          """AnyRef:
            |  type Elem = A
            |  def current: Elem""".stripMargin,
          "{ type Elem; val current: Elem }"
        ),
        refinements.map(text(first, _))
      )
      assertEquals(Vector(Vector("tpt", "refinements")), refinements.map(_.fields.map(_.name)).distinct)
      assertEquals(Vector("LambdaTypeTree", "LambdaTypeTree", "TypeDef"), refinements.map(parentProduction(first, _)))
      assertEquals(
        Vector(
          Vector(ParserFieldPathSegment.NamedField("body")),
          Vector(ParserFieldPathSegment.NamedField("body")),
          Vector(ParserFieldPathSegment.NamedField("rhs"))
        ),
        refinements.map(_.occurrences.head.fieldPath)
      )
      assertEquals(
        Vector(
          Vector("TypeDef", "ValDef", "DefDef"),
          Vector("TypeDef", "DefDef"),
          Vector("TypeDef", "ValDef")
        ),
        refinements.map(repeatedChildProductions(first, _, "refinements"))
      )
      val parentTypes = refinements.map(child(first, _, "tpt"))
      assertEquals(Vector("Ident", "Ident", "Thicket"), parentTypes.map(_.production))
      assertEquals(Vector("AnyRef", "AnyRef"), parentTypes.take(2).map(text(first, _)))
      assertEquals(ParserNodePosition.Absent, parentTypes.last.position)
      assertTrue(refinements.forall(positionedValue(_).provenance == ParserPositionProvenance.SourceDerived))

      val annotated = positioned(first, "Annotated")
      assertEquals(
        Vector("Elem @uncheckedVariance", "List[A @uncheckedVariance] @unchecked", "A @uncheckedVariance"),
        annotated.map(text(first, _))
      )
      assertEquals(Vector(Vector("arg", "annot")), annotated.map(_.fields.map(_.name)).distinct)
      assertEquals(Vector("AppliedTypeTree", "Parens", "AppliedTypeTree"), annotated.map(parentProduction(first, _)))
      annotated.foreach: node =>
        val annotation = child(first, node, "annot")
        assertEquals("Apply", annotation.production)
        assertEquals(range(node).startOffset + text(first, node).lastIndexOf('@'), range(annotation).startOffset)
        assertTrue(text(first, annotation).startsWith("@"))

      val structuralTokens = first.scannerTokens.filter(token => Set("{", "}", ";", "@")(tokenText(first, token)))
      assertEquals(Vector("{", "@", "}", "{", ";", "}", "@", "@"), structuralTokens.map(tokenText(first, _)))
      assertTrue(structuralTokens.forall(_.provenance == ParserPositionProvenance.SourceDerived))
      val layoutColon      = first.scannerTokens.find(token => token.range.startOffset == Source.indexOf("AnyRef:") + 6).get
      assertEquals(ParserScannerTokenKind.Colon, layoutColon.kind)
      assertEquals(":", tokenText(first, layoutColon))
    finally bridge.close()

  @Test
  def representativeRefinementsAndAnnotationsHaveBoundedDeterministicPlannerWork(): Unit =
    val bridge = openBridge()
    try
      Vector(4, 8, 16, 32).foreach: width =>
        val members  = Vector.tabulate(width)(index => s"  type T$index").mkString("\n")
        val source   = s"type Refined$width = AnyRef {\n$members\n}\n"
        val snapshot = parse(bridge, source, s"file:///RefinementWidth$width.scala")
        val observer = CountingPlanningWorkObserver()
        val first    = planned(snapshot, observer)
        val second   = planned(snapshot, PlanningWorkObserver.NoOp)
        assertEquals(first, second)
        assertEquals(width, first.composites.count(_.productionId == "definition-unbounded-type-alias"))
        assertEquals(source, evidence(snapshot).reconstruct(source))
        assertTrue(
          s"width=$width ownership=${observer.finalOwnership} terminal=${observer.terminal}",
          observer.finalOwnership <= 64L * width && observer.terminal <= 192L * width
        )

      Vector(4, 8, 16).foreach: depth =>
        val annotations = Vector.fill(depth)(" @unchecked").mkString
        val source      = s"type Annotated$depth = String$annotations\n"
        val snapshot    = parse(bridge, source, s"file:///AnnotationDepth$depth.scala")
        val observer    = CountingPlanningWorkObserver()
        val first       = planned(snapshot, observer)
        val second      = planned(snapshot, PlanningWorkObserver.NoOp)
        assertEquals(first, second)
        assertEquals(depth, positioned(snapshot, "Annotated").size)
        assertEquals(2 * depth, first.composites.count(_.productionId == "ordinary-annotated-type"))
        assertEquals(source, evidence(snapshot).reconstruct(source))
        assertTrue(
          s"depth=$depth ownership=${observer.finalOwnership} terminal=${observer.terminal}",
          observer.finalOwnership <= 128L * depth && observer.terminal <= 384L * depth
        )
    finally bridge.close()

  private final case class CountingPlanningWorkObserver() extends PlanningWorkObserver:
    var finalOwnership: Long = 0L
    var terminal: Long       = 0L

    override def finalOwnershipEntries(count: Int): Unit    = finalOwnership += count
    override def terminalLexicalEntries(count: Int): Unit   = terminal += count
    override def terminalCandidateEntries(count: Int): Unit = terminal += count

  private def planned(snapshot: ParserSyntaxSnapshot, observer: PlanningWorkObserver) =
    val runtime   =
      CompilerRuntimeInventory.from(snapshot).fold(errors => throw AssertionError(errors.mkString("\n")), identity)
    val aggregate = AggregatedCompilerProductionInventory
      .aggregate(Vector(runtime))
      .fold(error => throw AssertionError(error.toString), identity)
    val surfaces  = enrichedSurfaces(
      ScalaPsiSurfaceInventory.installed().fold(error => throw AssertionError(error), identity)
    )
    val prepared  = PreparedProductionCatalog
      .prepareRuntimeSubset(Scala3PsiProductionCatalog.Reviewed, runtime, aggregate, surfaces)
      .fold(errors => throw AssertionError(errors.mkString("\n")), identity)
    WholeFileProductionPlanner
      .plan(snapshot, evidence(snapshot), prepared, observer)
      .fold(error => throw AssertionError(error.toString), identity)

  private def enrichedSurfaces(inventory: ScalaPsiSurfaceInventory): ScalaPsiSurfaceInventory =
    val catalog = Scala3PsiProductionCatalog.Reviewed
    val tokens  = catalog.productions.flatMap(_.terminals.collect {
      case TerminalDeclaration(_, _, TerminalLeafTarget.Token(surfaceId, _), _, _, _) => surfaceId
    })
    val indices = catalog.productions.flatMap(
      _.effectiveOutputRealizations
        .flatMap(_.template.composites)
        .flatMap(_.persistence match
          case PersistenceObligations.Required(_, _, values, _) => values
          case PersistenceObligations.NotApplicable             => Vector.empty
        )
    )
    inventory.copy(rows =
      inventory.rows ++ tokens.distinct.map(id =>
        ScalaPsiSurfaceRow(id, SurfaceFactKind.Token, None, FactStatus.Available, SurfaceClassification.SyntaxContract)
      ) ++ indices.distinct.map(id =>
        ScalaPsiSurfaceRow(id, SurfaceFactKind.Index, None, FactStatus.Available, SurfaceClassification.SyntaxContract)
      ) :+ ScalaPsiSurfaceRow(
        ImportPersistenceSurfaces.SelfNavigation,
        SurfaceFactKind.Navigation,
        None,
        FactStatus.Available,
        SurfaceClassification.SyntaxContract
      )
    )

  private def positioned(snapshot: ParserSyntaxSnapshot, production: String): Vector[ParserSyntaxNode] =
    snapshot.nodes.filter(_.production == production).sortBy(range(_).startOffset)

  private def range(node: ParserSyntaxNode): PcSourceRange = node.position match
    case ParserNodePosition.Positioned(value, _, _) => value
    case other                                      => throw AssertionError(other.toString)

  private def positionedValue(node: ParserSyntaxNode): ParserNodePosition.Positioned =
    node.position.asInstanceOf[ParserNodePosition.Positioned]

  private def text(snapshot: ParserSyntaxSnapshot, node: ParserSyntaxNode): String = node.position match
    case ParserNodePosition.Positioned(range, _, _) => snapshot.sourceText.substring(range.startOffset, range.endOffset)
    case _                                          => "<synthetic>"

  private def parentProduction(snapshot: ParserSyntaxSnapshot, node: ParserSyntaxNode): String =
    snapshot.nodes.find(_.id == node.occurrences.head.ownerNodeId).get.production

  private def child(snapshot: ParserSyntaxSnapshot, node: ParserSyntaxNode, fieldName: String): ParserSyntaxNode =
    val childId = node.fields.find(_.name == fieldName).get.value.asInstanceOf[ParserFieldValue.Node].nodeId
    snapshot.nodes.find(_.id == childId).get

  private def repeatedChildProductions(
      snapshot: ParserSyntaxSnapshot,
      node: ParserSyntaxNode,
      fieldName: String
  ): Vector[String] =
    val childIds = node.fields
      .find(_.name == fieldName)
      .get
      .value
      .asInstanceOf[ParserFieldValue.Repeated]
      .values
      .map(_.asInstanceOf[ParserFieldValue.Node].nodeId)
    childIds.map(id => snapshot.nodes.find(_.id == id).get.production)

  private def tokenText(snapshot: ParserSyntaxSnapshot, token: ParserScannerToken): String =
    snapshot.sourceText.substring(token.range.startOffset, token.range.endOffset)

  private def evidence(snapshot: ParserSyntaxSnapshot) =
    ProvisionalSourceEvidencePlanner
      .plan(snapshot)
      .fold(failures => throw AssertionError(failures.mkString("\n")), identity)

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
      .fold(error => throw AssertionError(error.toString), identity)

  private def openBridge(): Scala3ParserBridge =
    Scala3ParserBridge
      .open(
        Scala3ParserArtifactCoordinate("org.scala-lang", "scala3-compiler_3", ScalaVersion),
        compilerDistribution.map(_.toFile)
      )
      .fold(error => throw AssertionError(error.toString), identity)

  private def compilerDistribution: Seq[Path] =
    Scala3CompilerResolver.publicCoursier.resolve(ScalaVersion).fold(error => throw error.toException, identity)

  private val ScalaVersion = "3.7.4"
  private val Source       =
    """import scala.annotation.unchecked.uncheckedVariance
      |type Braced[A] = AnyRef {
      |  type Elem <: A
      |  val value: Elem
      |  def map(x: Elem): List[Elem @uncheckedVariance]
      |}
      |type Layout[A] = AnyRef:
      |  type Elem = A
      |  def current: Elem
      |type Parentless = { type Elem; val current: Elem }
      |type Stacked[A] = (List[A @uncheckedVariance] @unchecked)
      |""".stripMargin
