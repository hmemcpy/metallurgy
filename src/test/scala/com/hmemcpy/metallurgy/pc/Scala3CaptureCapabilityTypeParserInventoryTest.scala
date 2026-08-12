package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.psiproducer.{
  AggregatedCompilerProductionInventory,
  CatalogShapeMatcher,
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
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.Test

import java.nio.file.Path

final class Scala3CaptureCapabilityTypeParserInventoryTest:

  @Test
  def exactCaptureTypeProductsAreDeterministic(): Unit =
    val bridge = openBridge()
    try
      val first  = parse(bridge, Source, "file:///CaptureCapabilityFirst.scala", CaptureOptions)
      val second = parse(bridge, Source, "file:///CaptureCapabilitySecond.scala", CaptureOptions)
      assertEquals(first.copy(sourceUri = second.sourceUri), second)
      assertEquals(Source, first.sourceText)
      assertEquals(Source, evidence(first).reconstruct(Source))
      assertEquals(CaptureOptions, first.compilerOptions)
      assertEquals(ScalaVersion, first.compilerIdentity.coordinate.version)
      assertEquals("org.scala-lang", first.compilerIdentity.coordinate.organization)
      assertEquals("scala3-compiler_3", first.compilerIdentity.coordinate.artifact)
      assertTrue(first.compilerIdentity.artifacts.nonEmpty)
      assertTrue(first.diagnostics.toString, first.diagnostics.forall(_.severity != ParserDiagnosticSeverity.Error))
      assertEquals(
        "3ce4c82c5f35c017e31b1b89d18b684ebb09f09551918744083ab346d4d41115",
        ParserSyntaxSnapshot.evidenceFingerprint(first)
      )
      assertEquals(Vector("RetainsAnnot", "RetainsAnnot", "RetainsAnnot"), first.attachments.map(_.keyKind))

      val capturesAndResults = positioned(first, "CapturesAndResult")
      assertEquals(
        Vector("x} String", "h.cap, xs*, x.rd, x.only[Kind]} String", "x} String", "x} String"),
        capturesAndResults.map(node => text(first, node))
      )
      capturesAndResults.foreach: node =>
        assertEquals(Vector("refs", "parent"), node.fields.map(_.name))
        val position = node.position.asInstanceOf[ParserNodePosition.Positioned]
        assertEquals(position.range.startOffset, position.point)
        assertEquals(ParserPositionProvenance.Synthetic, position.provenance)

      val structuralTokens =
        first.scannerTokens.filter(token => Set("^", "{", "}", "*", ".", "[", "]", "?->")(text(first, token.range)))
      assertTrue(structuralTokens.nonEmpty)
      assertTrue(structuralTokens.forall(_.provenance == ParserPositionProvenance.SourceDerived))
      val captureOperators = first.scannerTokens.filter(token => text(first, token.range) == "^")
      assertTrue(captureOperators.nonEmpty)
      assertTrue(captureOperators.forall(_.kind == ParserScannerTokenKind.CaptureOperator))
      assertEquals(first.scannerTokens.indices, first.scannerTokens.map(_.ordinal))
      first.nodes.foreach:
        case ParserSyntaxNode(_, _, _, ParserNodePosition.Positioned(range, point, provenance), _) =>
          assertTrue(range.toString, 0 <= range.startOffset && range.startOffset <= point && point <= range.endOffset)
          assertTrue(
            provenance.toString,
            provenance == ParserPositionProvenance.SourceDerived || provenance == ParserPositionProvenance.Synthetic
          )
        case _                                                                                     => ()
    finally bridge.close()

  @Test
  def exactCaptureOptionIsRequiredAndMissingOptionFailsClosed(): Unit =
    val bridge = openBridge()
    try
      val enabled  = parse(bridge, Source, "file:///CaptureCapabilityEnabledBeforeMissing.scala", CaptureOptions)
      val missing  = parse(bridge, Source, "file:///CaptureCapabilityMissingOption.scala", Vector.empty)
      val restored = parse(bridge, Source, "file:///CaptureCapabilityEnabledAfterMissing.scala", CaptureOptions)
      assertTrue(enabled.diagnostics.toString, enabled.diagnostics.forall(_.severity != ParserDiagnosticSeverity.Error))
      assertTrue(missing.diagnostics.toString, missing.diagnostics.exists(_.severity == ParserDiagnosticSeverity.Error))
      assertFalse(missing.nodes.exists(_.production == "CapturesAndResult"))
      assertEquals(Vector.empty, missing.compilerOptions)
      assertTrue(
        restored.diagnostics.toString,
        restored.diagnostics.forall(_.severity != ParserDiagnosticSeverity.Error)
      )
      assertTrue(restored.nodes.exists(_.production == "CapturesAndResult"))
      assertEquals(enabled.compilerOptions, restored.compilerOptions)
      assertEquals(
        enabled.copy(sourceUri = restored.sourceUri),
        restored
      )
    finally bridge.close()

  @Test
  def ordinaryGenericAnnotationSyntheticTreesCannotSelectCaptureProductions(): Unit =
    val bridge = openBridge()
    try
      val snapshot                 = parse(
        bridge,
        OrdinaryGenericAnnotation,
        "file:///OrdinaryGenericAnnotation.scala",
        CaptureOptions
      )
      val runtime                  = CompilerRuntimeInventory
        .from(snapshot)
        .fold(errors => throw AssertionError(errors.mkString("\n")), identity)
      val captureSyntheticPrefixes = Scala3PsiProductionCatalog.Reviewed.productions
        .filter(_.id.startsWith("capture-synthetic-"))
        .map(_.pattern.prefix)
        .toSet
      val rows                     = runtime.shapes.filter(row =>
        captureSyntheticPrefixes(row.prefix) &&
          row.contexts.exists(_.ancestors.exists(_.ownerPrefix == "Annotated"))
      )
      assertTrue(rows.toString, rows.nonEmpty)
      val selected                 = rows.flatMap: row =>
        row.contexts.flatMap(context =>
          CatalogShapeMatcher.select(
            Scala3PsiProductionCatalog.Reviewed,
            row.kind,
            row.prefix,
            row.observation,
            Some(context),
            row.sourceClassification,
            row.scannerTokenKinds,
            row.directNodeEvidence,
            row.rootAttachments
          )
        )
      assertTrue(selected.toString, selected.forall(!_.id.startsWith("capture-")))
    finally bridge.close()

  @Test
  def representativeCaptureSetsHaveBoundedDeterministicPlannerWork(): Unit =
    val bridge = openBridge()
    try
      Vector(4, 8, 16, 32).foreach: width =>
        val functions = Vector
          .tabulate(width)(index => s"def f$index: () ->{x} String = ???")
          .mkString("\n")
        val source    = s"class Capability extends caps.Capability\n$functions\n"
        val snapshot  = parse(bridge, source, s"file:///CaptureWidth$width.scala", CaptureOptions)
        val observer  = CountingPlanningWorkObserver()
        val first     = planned(snapshot, observer)
        val second    = planned(snapshot, PlanningWorkObserver.NoOp)
        assertEquals(first, second)
        assertEquals(2 * width, first.composites.count(_.productionId == "capture-nullary-function-type"))
        assertEquals(width, first.composites.count(_.productionId == "capture-function-result"))
        assertEquals(source, evidence(snapshot).reconstruct(source))
        assertTrue(
          s"width=$width ownership=${observer.finalOwnership} terminal=${observer.terminal}",
          observer.finalOwnership <= 96L * width && observer.terminal <= 320L * width
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
    case ParserNodePosition.Positioned(range, _, _) => text(snapshot, range)
    case ParserNodePosition.Absent                  => "<absent>"

  private def positioned(snapshot: ParserSyntaxSnapshot, production: String): Vector[ParserSyntaxNode] =
    snapshot.nodes.filter(node =>
      node.production == production && node.position.isInstanceOf[ParserNodePosition.Positioned]
    )

  private def text(snapshot: ParserSyntaxSnapshot, range: PcSourceRange): String =
    snapshot.sourceText.substring(range.startOffset, range.endOffset)

  private def evidence(snapshot: ParserSyntaxSnapshot) =
    ProvisionalSourceEvidencePlanner
      .plan(snapshot)
      .fold(failures => throw AssertionError(failures.mkString("\n")), identity)

  private def parse(
      bridge: Scala3ParserBridge,
      source: String,
      uri: String,
      options: Vector[String]
  ): ParserSyntaxSnapshot =
    bridge
      .parse(
        Scala3ParserRequest(
          ParserSourceUri.from(uri).fold(sys.error, identity),
          source,
          options,
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

  private val ScalaVersion              = "3.7.4"
  private val CaptureOptions            = Vector("-language:experimental.captureChecking")
  private val OrdinaryGenericAnnotation =
    """class Generic[A](value: String) extends scala.annotation.StaticAnnotation
      |type Ordinary = String @Generic[Int]("value")
      |""".stripMargin
  private val Source                    =
    """class Capability extends caps.Capability
      |class Box[A]
      |trait Holder:
      |  val cap: Capability
      |class Kind extends caps.Capability, caps.Classifier
      |type Universal = Capability^
      |type Mixed = (Capability @unchecked)^
      |def explicit(x: Capability): Box[String]^{x} = ???
      |def empty: Box[String]^{} = ???
      |def references(x: Capability, xs: List[Capability]): Box[String]^{x, xs*, x.rd, x.only[Kind]} = ???
      |def pure(x: Capability): () ->{x} String = ???
      |def pureRefs(x: Capability, xs: List[Capability], h: Holder): () ->{h.cap, xs*, x.rd, x.only[Kind]} String = ???
      |def context(x: Capability): Capability ?->{x} String = ???
      |def byName(x: Capability)(value: ->{x} String): String = value
      |""".stripMargin
