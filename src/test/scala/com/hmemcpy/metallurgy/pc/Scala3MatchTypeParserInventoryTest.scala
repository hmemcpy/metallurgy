package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.psiproducer.{
  AggregatedCompilerProductionInventory,
  CatalogPathSegment,
  CatalogShapeMatcher,
  CompilerRuntimeInventory,
  FactStatus,
  ImportPersistenceSurfaces,
  InventoryAncestor,
  InventoryContext,
  InventoryKind,
  InventoryValueObservation,
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
import org.junit.Assert.{assertEquals, assertFalse, assertSame, assertTrue}
import org.junit.Test

import java.nio.file.Path

final class Scala3MatchTypeParserInventoryTest:

  @Test
  def exactMatchTypeProductsFieldsPositionsTokensAndPlansAreDeterministic(): Unit =
    val bridge = openBridge()
    try
      val first  = parse(bridge, Source, "file:///MatchTypesFirst.scala")
      val second = parse(bridge, Source, "file:///MatchTypesSecond.scala")
      assertEquals(first.copy(sourceUri = second.sourceUri), second)
      assertEquals(Source, first.sourceText)
      assertEquals(Source, evidence(first).reconstruct(Source))
      assertTrue(first.diagnostics.toString, first.diagnostics.forall(_.severity != ParserDiagnosticSeverity.Error))
      assertEquals(Vector.empty, first.attachments)

      val matchTypes = positioned(first, "MatchTypeTree")
      assertEquals(2, matchTypes.size)
      assertEquals(
        Vector(
          "X match\n  case String => Char\n  case Array[t] => t\n  case _ => Nothing",
          "X match { case Int | Long => Boolean; case _ => X }"
        ),
        matchTypes.map(text(first, _))
      )
      matchTypes.foreach: matched =>
        assertEquals(Vector("bound", "selector", "cases"), matched.fields.map(_.name))
        assertEquals(ParserPositionProvenance.SourceDerived, positionedValue(matched).provenance)
        assertEquals(
          Source.indexOf("match", positionedValue(matched).range.startOffset),
          positionedValue(matched).point
        )
        assertEquals("Thicket", child(first, matched, "bound").production)
        assertEquals(ParserNodePosition.Absent, child(first, matched, "bound").position)
        assertEquals("Ident", child(first, matched, "selector").production)
        assertEquals("X", text(first, child(first, matched, "selector")))

      val cases = matchTypes.flatMap(repeatedChildren(first, _, "cases"))
      assertEquals(5, cases.size)
      assertTrue(cases.forall(_.production == "CaseDef"))
      assertEquals(
        Vector(
          "case String => Char",
          "case Array[t] => t",
          "case _ => Nothing",
          "case Int | Long => Boolean;",
          "case _ => X"
        ),
        cases.map(text(first, _))
      )
      cases.foreach: matchCase =>
        assertEquals(Vector("pat", "guard", "body"), matchCase.fields.map(_.name))
        assertSame(child(first, matchTypes.head, "bound"), child(first, matchCase, "guard"))
        assertEquals(ParserPositionProvenance.SourceDerived, positionedValue(matchCase).provenance)
        assertEquals(positionedValue(matchCase).range.startOffset, positionedValue(matchCase).point)

      val variablePatterns = cases
        .flatMap(matchCase => descendants(first, child(first, matchCase, "pat")))
        .filter(node => node.production == "Ident" && Set("t", "_").contains(text(first, node)))
      assertEquals(Vector("t", "_", "_"), variablePatterns.map(text(first, _)))
      assertTrue(
        variablePatterns.forall(node => positionedValue(node).provenance == ParserPositionProvenance.SourceDerived)
      )

      val tokens = first.scannerTokens.filter(token =>
        token.kind == ParserScannerTokenKind.FunctionArrow ||
          Set("match", "case", "{", "}", ";").contains(tokenText(first, token))
      )
      assertEquals(2, tokens.count(tokenText(first, _) == "match"))
      assertEquals(5, tokens.count(tokenText(first, _) == "case"))
      assertEquals(5, tokens.count(_.kind == ParserScannerTokenKind.FunctionArrow))
      assertEquals(1, tokens.count(tokenText(first, _) == "{"))
      assertEquals(1, tokens.count(tokenText(first, _) == "}"))
      assertEquals(1, tokens.count(tokenText(first, _) == ";"))
      assertTrue(tokens.forall(_.provenance == ParserPositionProvenance.SourceDerived))
      assertTrue(
        tokens.forall(token =>
          tokenText(first, token) == Source.substring(token.range.startOffset, token.range.endOffset)
        )
      )

      val plan = planned(first, PlanningWorkObserver.NoOp)
      assertEquals(4, plan.composites.count(_.productionId == "ordinary-match-type"))
      assertEquals(5, plan.composites.count(_.productionId == "match-type-case"))
      assertEquals(1, plan.composites.count(_.productionId == "match-type-pattern-variable"))
      assertEquals(2, plan.composites.count(_.productionId == "match-type-pattern-wildcard"))
      assertEquals(
        Source,
        plan.physicalLeafOwnership.sortBy(_.start).map(leaf => Source.substring(leaf.start, leaf.end)).mkString
      )
      assertFalse(plan.physicalLeafOwnership.exists(leaf => leaf.start == leaf.end))

      val termCase              = parse(
        bridge,
        "val value: Any = Array(\"x\")\nval result = value match { case lower: Array[String] => lower; case Upper => Upper; case _ => value }\n",
        "file:///TermCasePatterns.scala"
      )
      val termInventory         = CompilerRuntimeInventory
        .from(termCase)
        .fold(errors => throw AssertionError(errors.mkString("\n")), identity)
      val termPatternShapes     = termInventory.shapes
        .filter(row => row.prefix == "Ident" && row.contexts.exists(termPatternContext))
      assertTrue(termPatternShapes.nonEmpty)
      assertTrue(termPatternShapes.exists(row => text(termCase, termCase.nodes.find(_.id == row.id).get) == "String"))
      val termPatternSelections = termPatternShapes
        .flatMap(row =>
          row.contexts.flatMap(context =>
            CatalogShapeMatcher.select(
              Scala3PsiProductionCatalog.Reviewed,
              row.kind,
              row.prefix,
              row.observation,
              Some(context),
              row.sourceClassification,
              row.scannerTokenKinds
            )
          )
        )
        .map(_.id)
      assertTrue(
        termPatternSelections.toSet
          .intersect(
            Set("match-type-pattern-reference", "match-type-pattern-variable", "match-type-pattern-wildcard")
          )
          .isEmpty
      )
      val termAggregate         = AggregatedCompilerProductionInventory
        .aggregate(Vector(termInventory))
        .fold(error => throw AssertionError(error.toString), identity)
      val aggregateSelections   = termAggregate.productions
        .filter(_.prefix == "Ident")
        .flatMap(row =>
          row.occurrences
            .filter(_.context.exists(termPatternContext))
            .flatMap(CatalogShapeMatcher.selectAggregated(Scala3PsiProductionCatalog.Reviewed, row, _))
        )
        .map(_.id)
      assertTrue(
        aggregateSelections.toSet
          .intersect(
            Set("match-type-pattern-reference", "match-type-pattern-variable", "match-type-pattern-wildcard")
          )
          .isEmpty
      )

      val backticked          = parse(
        bridge,
        "type foo = String\ntype Backticked[X] = X match { case `foo` => Char; case _ => Nothing }\n",
        "file:///MatchTypeBackticked.scala"
      )
      val backtickedInventory = CompilerRuntimeInventory
        .from(backticked)
        .fold(errors => throw AssertionError(errors.mkString("\n")), identity)
      val backtickedPattern   = backtickedInventory.shapes
        .find(row =>
          row.prefix == "Ident" && row.id == positioned(backticked, "Ident").find(text(backticked, _) == "`foo`").get.id
        )
        .get
      assertEquals(
        InventoryValueObservation.BacktickedName("foo"),
        backtickedPattern.observation.find(_.name == "name").get.value
      )
      val backtickedPlan      = planned(backticked, PlanningWorkObserver.NoOp)
      assertEquals(2, backtickedPlan.composites.count(_.productionId == "match-type-pattern-reference"))
      assertEquals(0, backtickedPlan.composites.count(_.productionId == "match-type-pattern-variable"))
    finally bridge.close()

  @Test
  def representativeMatchTypeCasesHaveBoundedDeterministicPlannerWork(): Unit =
    val bridge = openBridge()
    try
      Vector(4, 8, 16, 32).foreach: width =>
        val cases    = Vector.tabulate(width)(index => s"  case T$index => R$index").mkString("\n")
        val source   = s"type Cases[X] = X match\n$cases\n"
        val snapshot = parse(bridge, source, s"file:///MatchTypeWidth$width.scala")
        val observer = CountingPlanningWorkObserver()
        val first    = planned(snapshot, observer)
        val second   = planned(snapshot, PlanningWorkObserver.NoOp)
        assertEquals(first, second)
        assertEquals(width, first.composites.count(_.productionId == "match-type-case"))
        assertEquals(source, evidence(snapshot).reconstruct(source))
        assertTrue(
          s"width=$width ownership=${observer.finalOwnership} terminal=${observer.terminal}",
          observer.finalOwnership <= 64L * width && observer.terminal <= 192L * width
        )
    finally bridge.close()

  private final case class CountingPlanningWorkObserver() extends PlanningWorkObserver:
    var finalOwnership: Long = 0L
    var terminal: Long       = 0L

    override def finalOwnershipEntries(count: Int): Unit    = finalOwnership += count
    override def terminalLexicalEntries(count: Int): Unit   = terminal += count
    override def terminalCandidateEntries(count: Int): Unit = terminal += count

  private def child(snapshot: ParserSyntaxSnapshot, owner: ParserSyntaxNode, field: String): ParserSyntaxNode =
    val id = owner.fields.collectFirst { case ParserSyntaxField(`field`, ParserFieldValue.Node(id), _) => id }.get
    snapshot.nodes.find(_.id == id).get

  private def repeatedChildren(
      snapshot: ParserSyntaxSnapshot,
      owner: ParserSyntaxNode,
      field: String
  ): Vector[ParserSyntaxNode] =
    val ids = owner.fields.collectFirst { case ParserSyntaxField(`field`, ParserFieldValue.Repeated(values), _) =>
      values.collect { case ParserFieldValue.Node(id) => id }
    }.get
    ids.map(id => snapshot.nodes.find(_.id == id).get)

  private def descendants(snapshot: ParserSyntaxSnapshot, root: ParserSyntaxNode): Vector[ParserSyntaxNode] =
    val byId                                                   = snapshot.nodes.map(node => node.id -> node).toMap
    def loop(node: ParserSyntaxNode): Vector[ParserSyntaxNode] =
      node +: node.fields.flatMap(_.value match
        case ParserFieldValue.Node(id)        => loop(byId(id))
        case ParserFieldValue.Repeated(items) =>
          items.collect { case ParserFieldValue.Node(id) => id }.flatMap(id => loop(byId(id)))
        case _                                => Vector.empty
      )
    loop(root)

  private def termPatternContext(context: InventoryContext): Boolean =
    val pattern = InventoryAncestor(
      InventoryKind.Node,
      "CaseDef",
      Vector(CatalogPathSegment.NamedField("pat"))
    )
    context.ownerKind == pattern.ownerKind && context.ownerPrefix == pattern.ownerPrefix && context.path == pattern.path ||
    context.ancestors.contains(pattern)

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

  private def text(snapshot: ParserSyntaxSnapshot, node: ParserSyntaxNode): String = positionedValue(node).range match
    case range => snapshot.sourceText.substring(range.startOffset, range.endOffset)

  private def positionedValue(node: ParserSyntaxNode): ParserNodePosition.Positioned =
    node.position.asInstanceOf[ParserNodePosition.Positioned]

  private def positioned(snapshot: ParserSyntaxSnapshot, production: String): Vector[ParserSyntaxNode] =
    snapshot.nodes.filter(node =>
      node.production == production && node.position.isInstanceOf[ParserNodePosition.Positioned]
    )

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
    """type Element[X] = X match
      |  case String => Char
      |  case Array[t] => t
      |  case _ => Nothing
      |type Braced[X] = X match { case Int | Long => Boolean; case _ => X }
      |""".stripMargin
