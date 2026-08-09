package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.psiproducer.{
  AggregatedCompilerProductionInventory,
  CompilerRuntimeInventory,
  FactStatus,
  ImportPersistenceSurfaces,
  PlanningWorkObserver,
  PersistenceObligations,
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
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.Test

import java.nio.file.Path

final class Scala3InfixTypeParserInventoryTest:

  @Test
  def exactUnionIntersectionAndInfixProductsAreDeterministic(): Unit =
    val bridge = openBridge()
    try
      val first  = parse(bridge, Source, "file:///InfixTypesFirst.scala")
      val second = parse(bridge, Source, "file:///InfixTypesSecond.scala")
      assertEquals(first.copy(sourceUri = second.sourceUri), second)
      assertEquals(Source, first.sourceText)
      assertEquals(Source, evidence(first).reconstruct(Source))
      assertTrue(first.diagnostics.toString, first.diagnostics.forall(_.severity != ParserDiagnosticSeverity.Error))
      assertEquals(Vector.empty, first.attachments)

      val infix = positioned(first, "InfixOp")
      assertEquals(
        Vector(
          "String | Int",
          "Product & Serializable",
          "String Or Int",
          "String | Int & Product Or Boolean",
          "String | Int & Product",
          "Int & Product",
          "String | Int & Product",
          "Int & Product"
        ),
        infix.map(text(first, _))
      )
      infix.foreach: node =>
        assertEquals(Vector("left", "op", "right"), node.fields.map(_.name))
        val operator = child(first, node, "op")
        val value    = text(first, operator)
        assertTrue(Set("|", "&", "Or").contains(value))
        val position = node.position.asInstanceOf[ParserNodePosition.Positioned]
        assertEquals(ParserPositionProvenance.SourceDerived, position.provenance)
        assertEquals(Source.indexOf(value, position.range.startOffset), position.point)
        assertEquals(
          Vector(ParserNodeOccurrence(node.id, Vector(ParserFieldPathSegment.NamedField("op")))),
          operator.occurrences
        )

      val operatorTokens = first.scannerTokens.filter(token =>
        infix
          .map(node => child(first, node, "op").position.asInstanceOf[ParserNodePosition.Positioned].range)
          .contains(token.range)
      )
      assertEquals(infix.size, operatorTokens.size)
      assertTrue(operatorTokens.forall(_.kind == ParserScannerTokenKind.Identifier))

      val plan = planned(first, PlanningWorkObserver.NoOp)
      assertEquals(infix.size, plan.composites.count(_.productionId == "ordinary-infix-type"))
      assertEquals(infix.size, plan.composites.count(_.productionId == "infix-type-operator"))
      assertEquals(
        Source,
        plan.physicalLeafOwnership.sortBy(_.start).map(leaf => Source.substring(leaf.start, leaf.end)).mkString
      )
      assertFalse(plan.physicalLeafOwnership.exists(leaf => leaf.start == leaf.end))
    finally bridge.close()

  @Test
  def representativeInfixSequencesHaveBoundedDeterministicPlannerWork(): Unit =
    val bridge = openBridge()
    try
      Vector(4, 8, 16, 32).foreach: width =>
        val source   = s"type Wide = ${Vector.tabulate(width)(index => s"T$index").mkString(" | ")}\n"
        val snapshot = parse(bridge, source, s"file:///InfixWidth$width.scala")
        val observer = CountingPlanningWorkObserver()
        val first    = planned(snapshot, observer)
        val second   = planned(snapshot, PlanningWorkObserver.NoOp)
        assertEquals(first, second)
        assertEquals(width - 1, first.composites.count(_.productionId == "ordinary-infix-type"))
        assertEquals(source, evidence(snapshot).reconstruct(source))
        assertTrue(
          s"width=$width ownership=${observer.finalOwnership} terminal=${observer.terminal}",
          observer.finalOwnership <= 24L * width && observer.terminal <= 96L * width
        )
    finally bridge.close()

  private final class CountingPlanningWorkObserver extends PlanningWorkObserver:
    var finalOwnership: Long = 0L
    var terminal: Long       = 0L

    override def finalOwnershipEntries(count: Int): Unit    = finalOwnership += count
    override def terminalLexicalEntries(count: Int): Unit   = terminal += count
    override def terminalCandidateEntries(count: Int): Unit = terminal += count

  private def child(snapshot: ParserSyntaxSnapshot, owner: ParserSyntaxNode, field: String): ParserSyntaxNode =
    val id = owner.fields.collectFirst { case ParserSyntaxField(`field`, ParserFieldValue.Node(id), _) => id }.get
    snapshot.nodes.find(_.id == id).get

  private def planned(snapshot: ParserSyntaxSnapshot, observer: PlanningWorkObserver) =
    val runtime   =
      CompilerRuntimeInventory.from(snapshot).fold(errors => throw AssertionError(errors.mkString("\n")), identity)
    val aggregate = AggregatedCompilerProductionInventory
      .aggregate(Vector(runtime))
      .fold(error => throw AssertionError(error.toString), identity)
    val surfaces  = enrichedSurfaces(
      ScalaPsiSurfaceInventory.installed().fold(error => throw new AssertionError(error), identity)
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
        ScalaPsiSurfaceRow(
          id,
          SurfaceFactKind.Token,
          None,
          FactStatus.Available,
          SurfaceClassification.SyntaxContract
        )
      ) ++ indices.distinct.map(id =>
        ScalaPsiSurfaceRow(
          id,
          SurfaceFactKind.Index,
          None,
          FactStatus.Available,
          SurfaceClassification.SyntaxContract
        )
      ) :+
        ScalaPsiSurfaceRow(
          ImportPersistenceSurfaces.SelfNavigation,
          SurfaceFactKind.Navigation,
          None,
          FactStatus.Available,
          SurfaceClassification.SyntaxContract
        )
    )

  private def text(snapshot: ParserSyntaxSnapshot, node: ParserSyntaxNode): String = node.position match
    case ParserNodePosition.Positioned(range, _, _) => snapshot.sourceText.substring(range.startOffset, range.endOffset)
    case ParserNodePosition.Absent                  => "<absent>"

  private def positioned(snapshot: ParserSyntaxSnapshot, production: String): Vector[ParserSyntaxNode] =
    snapshot.nodes.filter(node =>
      node.production == production && node.position.isInstanceOf[ParserNodePosition.Positioned]
    )

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
    """trait Or[A, B]
      |type Union = String | Int
      |type Intersection = Product & Serializable
      |type Custom = String Or Int
      |type Mixed = String | Int & Product Or Boolean
      |type Nested = List[String | Int & Product]
      |""".stripMargin
