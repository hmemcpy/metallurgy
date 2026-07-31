package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.psiproducer.{
  AggregatedCompilerProductionInventory,
  CatalogValidationError,
  CatalogShapeMatcher,
  CatalogValuePattern,
  CompilerRuntimeInventory,
  ExportPersistenceSurfaces,
  FactStatus,
  GrammarRoleId,
  ImportPersistenceSurfaces,
  NativePsiElementBindings,
  PackagePersistenceSurfaces,
  PersistenceObligations,
  PhysicalLeafOwner,
  InventoryFieldObservation,
  InventoryValueObservation,
  Scala3PsiProductionCatalog,
  Scala3PsiProductionCatalogValidator,
  Scala3PsiProductionCoverageReport,
  ScalaPsiSurfaceInventory,
  ScalaPsiSurfaceRow,
  StableRoleInventory,
  SurfaceFactKind,
  SurfaceClassification,
  TerminalDeclaration,
  TerminalLeafTarget,
  ProvisionalSourceEvidencePlanner,
  PreparedProductionCatalog,
  WholeFileProductionPlanner
}
import org.junit.Assert.{assertArrayEquals, assertEquals, assertFalse, assertTrue}
import org.junit.Test

import java.nio.charset.StandardCharsets
import java.nio.file.Path

final class Scala3ParserVerticalSliceTest:

  @Test
  def minimizedCommaImportLowersSiblingCompilerProductsIntoOneStatement(): Unit =
    val bridge = openBridge()
    try
      val source     = "import a.b, c.d\n"
      val snapshot   = parse(bridge, source, "file:///CommaImport.scala")
      assertTrue(snapshot.diagnostics.isEmpty)
      assertEquals(source, snapshot.sourceText)
      assertEquals(
        "685b6d2968cd76b304ee2dd83868d07a143f1fdc0f95f93638af15525ccd2ba4",
        ParserSyntaxSnapshot.evidenceFingerprint(snapshot)
      )
      assertEquals(
        Vector(
          "PackageDef",
          "Ident",
          "Import",
          "Ident",
          "ImportSelector",
          "Ident",
          "Thicket",
          "Import",
          "Ident",
          "ImportSelector",
          "Ident"
        ),
        snapshot.nodes.map(_.production)
      )
      assertEquals(
        Vector(
          ParserNodePosition.Positioned(PcSourceRange(0, 10), 7, ParserPositionProvenance.SourceDerived),
          ParserNodePosition.Positioned(PcSourceRange(12, 15), 12, ParserPositionProvenance.SourceDerived)
        ),
        snapshot.nodes.filter(_.production == "Import").map(_.position)
      )
      val evidence   = ProvisionalSourceEvidencePlanner.plan(snapshot).toOption.get
      val runtime    = CompilerRuntimeInventory.from(snapshot).toOption.get
      val aggregate  = AggregatedCompilerProductionInventory.aggregate(Vector(runtime)).toOption.get
      val surfaces   = withImportTokenSurfaces(ScalaPsiSurfaceInventory.installed().toOption.get)
      val prepared   = PreparedProductionCatalog
        .prepareRuntimeSubset(Scala3PsiProductionCatalog.Reviewed, runtime, aggregate, surfaces)
        .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
      val plan       = WholeFileProductionPlanner
        .plan(snapshot, evidence, prepared)
        .fold(error => throw new AssertionError(error.toString), identity)
      val statements =
        plan.composites.filter(_.productionId == "import-statement").filter(_.instance.localOutputId == "statement")
      assertEquals(1, statements.size)
      assertEquals(PcSourceRange(0, 15), statements.head.range)
      assertEquals(2, statements.head.children.size)
      assertEquals(
        source,
        plan.physicalLeafOwnership.sortBy(_.start).map(leaf => source.substring(leaf.start, leaf.end)).mkString
      )
      assertEquals(evidence.structural.map(_.id), plan.structuralEvidenceOwnership.map(_.eventId))
      assertEquals(
        plan.structuralEvidenceOwnership.map(_.eventId).distinct,
        plan.structuralEvidenceOwnership.map(_.eventId)
      )
    finally bridge.close()

  @Test
  def minimizedImportFormsHaveExactCompilerShapes(): Unit =
    val bridge = openBridge()
    try
      val forms         = Vector(
        ("import a.b.c\n", Vector("c"), "262c1533c8ca5b09b137b7f5136d3fb2923a30856ab94b46189eec35032aa144"),
        ("import a.b.*\n", Vector("_"), "ccb4206d2afbb0c55c24beed219536e2129c2bf39d24c8ac7c72d5158c06a9ec"),
        ("import a.b.{c}\n", Vector("c"), "01bb164a10401a3e5c2f060b39982e89d97b75f16ddf71ffca4b8ced1915917d"),
        (
          "import a.b.{c /* as */ as d}\n",
          Vector("c", "d"),
          "5a23963f840d0d5ea665be4db02dbc32119e145e6b126f29ff5d666228353e5a"
        ),
        (
          "import a.b.{c /* => */ => d}\n",
          Vector("c", "d"),
          "9da9e2b6eba42625c7d7f1b36e496d33e7bcdfedfe4baa8196c89bf6a8375dfc"
        ),
        ("import a.b.given\n", Vector(""), "83ad88645218cd5ae17ec9e1555c55e2eeac71881005d03d4591a83773d25c76"),
        ("import a.b.given T\n", Vector("", "T"), "e925d7c12a5a58e8a147c9a6c5c7954b1de31fe7a3347f2514ff2f656079841b"),
        (
          "import a.b.{given, given T, *}\n",
          Vector("", "", "T", "_"),
          "b600221d01be377489fad79b19d6b773c39f0ff860112329a51a6c8e12b8b39f"
        ),
        ("import a.b._\n", Vector("_"), ""),
        ("import a.b.{c as _}\n", Vector("c", "_"), ""),
        ("import a.b.{c => _}\n", Vector("c", "_"), ""),
        ("import java as j\n", Vector("java", "j"), ""),
        ("import a.b.c as _\n", Vector("c", "_"), ""),
        ("import a.b.given Ordering[Int]\n", Vector("", "Ordering", "Int"), ""),
        ("import a.b.given F[G[Int]]\n", Vector("", "F", "G", "Int"), ""),
        ("import a.b.given Either[Int, String]\n", Vector("", "Either", "Int", "String"), "")
      )
      val snapshots     = forms.zipWithIndex.map: (form, index) =>
        val (source, names, fingerprint) = form
        val snapshot                     = parse(bridge, source, s"file:///ImportForm$index.scala")
        assertTrue(snapshot.diagnostics.forall(_.severity != ParserDiagnosticSeverity.Error))
        assertEquals(source, snapshot.sourceText)
        if fingerprint.nonEmpty then assertEquals(fingerprint, ParserSyntaxSnapshot.evidenceFingerprint(snapshot))
        assertEquals(
          if index != 11 then Vector("PackageDef", "Ident", "Import", "Select", "Ident")
          else Vector("PackageDef", "Ident", "Import", "Thicket", "ImportSelector"),
          snapshot.nodes.take(5).map(_.production)
        )
        assertEquals(
          names,
          snapshot.nodes.drop(5).filter(_.production == "Ident").flatMap(_.fields).collect {
            case ParserSyntaxField("name", ParserFieldValue.Name(value), _) => value
          }
        )
        val statement                    = snapshot.nodes(2)
        assertEquals(
          ParserNodePosition.Positioned(PcSourceRange(0, source.length - 1), 7, ParserPositionProvenance.SourceDerived),
          statement.position
        )
        assertEquals(source, ProvisionalSourceEvidencePlanner.plan(snapshot).toOption.get.reconstruct(source))
        snapshot
      val comma         = parse(bridge, "import a.b, c.d\n", "file:///ImportFormComma.scala")
      val exportSibling = parse(bridge, "export a.b.c\n", "file:///ImportFormExportSibling.scala")
      val runtimes      = (snapshots ++ Vector(comma, exportSibling)).map(snapshot =>
        CompilerRuntimeInventory.from(snapshot).toOption.get
      )
      val aggregate     = AggregatedCompilerProductionInventory.aggregate(runtimes).toOption.get
      val installed     = ScalaPsiSurfaceInventory.installed().toOption.get
      val surfaces      = withImportTokenSurfaces(installed)
      snapshots
        .zip(runtimes)
        .foreach: (snapshot, runtime) =>
          val prepared  = PreparedProductionCatalog
            .prepareRuntimeSubset(Scala3PsiProductionCatalog.Reviewed, runtime, aggregate, surfaces)
            .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
          val evidence  = ProvisionalSourceEvidencePlanner.plan(snapshot).toOption.get
          val plan      = WholeFileProductionPlanner
            .plan(snapshot, evidence, prepared)
            .fold(error => throw new AssertionError(s"${snapshot.sourceUri}: $error"), identity)
          assertEquals(
            snapshot.sourceText,
            plan.physicalLeafOwnership
              .sortBy(_.start)
              .map(leaf => snapshot.sourceText.substring(leaf.start, leaf.end))
              .mkString
          )
          assertEquals(evidence.structural.map(_.id), plan.structuralEvidenceOwnership.map(_.eventId))
          val tokenText = plan.physicalLeafOwnership.collect:
            case leaf @ com.hmemcpy.metallurgy.psiproducer.PlannedPhysicalLeaf(
                  _,
                  _,
                  _,
                  _,
                  _,
                  _,
                  TerminalLeafTarget.Token(_, Some(expected))
                ) =>
              snapshot.sourceText.substring(leaf.start, leaf.end) -> expected
          assertTrue(tokenText.forall((actual, expected) => actual == expected))
          if snapshot.sourceText.contains("/* as */") then assertEquals(Vector("as" -> "as"), tokenText)
          if snapshot.sourceText.contains("/* => */") then assertEquals(Vector("=>" -> "=>"), tokenText)
    finally bridge.close()

  @Test
  def minimizedExportFormsHaveExactCompilerShapesAndClosedOutputForests(): Unit =
    val bridge = openBridge()
    try
      val sources   = Vector(
        "export scala.Predef.identity\n",
        "package probe.deep\nexport scala.collection.immutable.List.apply\n",
        "package probe.wildcard\nexport scala.Predef.*\n",
        "package probe.braced\nexport scala.Predef.{assert, identity}\n",
        "package probe.scala3alias\nexport scala.Predef.{identity as renamedIdentity}\n",
        "package probe.legacyalias\nexport scala.Predef.{identity => renamedIdentity}\n",
        "package probe.hiding\nexport scala.Predef.{assert as _, *}\n",
        "package probe.unboundedgiven\nexport scala.math.Ordering.given\n",
        "package probe.simplegiven\nexport scala.Predef.{given DummyImplicit}\n",
        "package probe.appliedgiven\nimport scala.math.Ordering\nexport scala.math.Ordering.{given Ordering[Int]}\n",
        """package probe.mixed
          |import scala.math.Ordering
          |export scala.Predef.identity
          |import scala.collection.immutable.List
          |export scala.Predef.assert
          |export scala.math.Ordering.{given Ordering[Int]}
          |""".stripMargin
      )
      val snapshots = sources.zipWithIndex.map: (source, index) =>
        val snapshot = parse(bridge, source, s"file:///ExportForm$index.scala")
        assertTrue(snapshot.diagnostics.forall(_.severity != ParserDiagnosticSeverity.Error))
        assertEquals(source, snapshot.sourceText)
        assertEquals(source, ProvisionalSourceEvidencePlanner.plan(snapshot).toOption.get.reconstruct(source))
        val exports  = snapshot.nodes.filter(_.production == "Export")
        assertEquals(if index == sources.size - 1 then 3 else 1, exports.size)
        exports.foreach: statement =>
          assertEquals(Vector("expr", "selectors"), statement.fields.map(_.name))
          assertTrue(statement.position.isInstanceOf[ParserNodePosition.Positioned])
        snapshot
      val runtimes  = snapshots.map(CompilerRuntimeInventory.from(_).toOption.get)
      val aggregate = AggregatedCompilerProductionInventory.aggregate(runtimes).toOption.get
      val surfaces  = withImportTokenSurfaces(ScalaPsiSurfaceInventory.installed().toOption.get)
      snapshots
        .zip(runtimes)
        .foreach: (snapshot, runtime) =>
          val prepared = PreparedProductionCatalog
            .prepareRuntimeSubset(Scala3PsiProductionCatalog.Reviewed, runtime, aggregate, surfaces)
            .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
          val evidence = ProvisionalSourceEvidencePlanner.plan(snapshot).toOption.get
          val plan     = WholeFileProductionPlanner
            .plan(snapshot, evidence, prepared)
            .fold(error => throw new AssertionError(error.toString), identity)
          val exports  = plan.composites
            .filter(_.productionId == "export-statement")
            .filter(_.instance.localOutputId == "statement")
          assertEquals(snapshot.nodes.count(_.production == "Export"), exports.size)
          assertEquals(
            snapshot.sourceText,
            plan.physicalLeafOwnership
              .sortBy(leaf => (leaf.start, leaf.end))
              .map(leaf => snapshot.sourceText.substring(leaf.start, leaf.end))
              .mkString
          )
          assertFalse(plan.physicalLeafOwnership.exists(leaf => leaf.start == leaf.end))
          assertEquals(evidence.structural.map(_.id), plan.structuralEvidenceOwnership.map(_.eventId))
          assertEquals(
            plan.structuralEvidenceOwnership.map(_.eventId).distinct,
            plan.structuralEvidenceOwnership.map(_.eventId)
          )
      assertEquals(
        Vector(
          "2338e4efcfc827a797dd575e28052072aecdae948dbee6857923e990f9c577bb",
          "79e73e7d082334a111ac145fb2bbb06cac3dc4c1c6da0d03f88d02f62f28d329",
          "302769ac0ce1a0723ecef61bebee4fa04ac03b59113ba34fb303e05fc93cdb1d",
          "261fd3405ba2558e532d3fc425289e7bce1407ea6e8c4a0a81efb1abbbbcc5c5",
          "65d97a09104976d5b772df8b45de5dfe74208c27d7d8129e2d5cfa002a3bd4fc",
          "0d48d0ad26d8b86262a4f0632fbe64ce6c52912790517b28e7cf8cc821705d95",
          "98e5e5ea430a3c7ecdd55818b229d8b78eeb81815782f320d87c411f3774067c",
          "ef342d4d893eeab5bfb03d80113226445605149acea525515acd2251a7d2a6c3",
          "8a20d5586ed2b1547d68aeab12a69961cae6093745588e3d0dc90b7357599fbc",
          "9f57fe4ba147db56584acfa50a11aec2e605798a36e04b339164b706366c2593",
          "348006c26fde1b162dd64f5dff833e33375c0eca61a7550bf513b160c58e2500",
          "39b5261875e03534e38f0eb974ddc74f732cfe745f85b4b45187480f7dcf8c08"
        ),
        snapshots.map(ParserSyntaxSnapshot.evidenceFingerprint) :+ aggregate.fingerprint
      )

      def selectedExportProductions(snapshot: ParserSyntaxSnapshot): Vector[String] =
        val runtime = CompilerRuntimeInventory.from(snapshot).toOption.get
        runtime.shapes
          .filter(_.prefix == "Export")
          .flatMap(row =>
            row.contexts.flatMap(context =>
              CatalogShapeMatcher.select(
                Scala3PsiProductionCatalog.Reviewed,
                row.kind,
                row.prefix,
                row.observation,
                Some(context),
                row.sourceClassification
              )
            )
          )
          .map(_.id)
      val local                                                                     = parse(
        bridge,
        "def local =\n  export scala.Predef.identity\n",
        "file:///InvalidLocalExport.scala"
      )
      assertFalse(local.nodes.exists(_.production == "Export"))
      assertTrue(local.diagnostics.exists(_.severity == ParserDiagnosticSeverity.Error))
      assertEquals(
        local.sourceText,
        ProvisionalSourceEvidencePlanner.plan(local).toOption.get.reconstruct(local.sourceText)
      )
      assertTrue(selectedExportProductions(local).isEmpty)

      val packageWildcard = parse(
        bridge,
        "package probe.packagewildcard\nexport scala.collection.*\n",
        "file:///SemanticallyInvalidPackageWildcardExport.scala"
      )
      assertTrue(packageWildcard.nodes.exists(_.production == "Export"))
      assertTrue(packageWildcard.diagnostics.forall(_.severity != ParserDiagnosticSeverity.Error))
      assertEquals(
        packageWildcard.sourceText,
        ProvisionalSourceEvidencePlanner.plan(packageWildcard).toOption.get.reconstruct(packageWildcard.sourceText)
      )
      assertEquals(Vector("export-statement"), selectedExportProductions(packageWildcard))
    finally bridge.close()

  @Test
  def recursiveStablePackageAndImportPathsHaveExactCompilerShapesAndClosedPlans(): Unit =
    val bridge = openBridge()
    try
      val packagePaths  = Vector(
        "package alpha.beta.gamma.delta\n" -> Vector("alpha", "beta", "gamma", "delta"),
        "package alpha.`match`.δ.++\n"     -> Vector("alpha", "match", "δ", "++")
      )
      val importSources = Vector(
        """import packet.alpha.`match`.δ.deep.ordinary
          |import packet.alpha.`match`.δ.deep.++ as plus
          |import packet.alpha.`match`.δ.deep.λ as unicode
          |import packet.alpha.`match`.δ.deep.`back-tick` as quoted
          |""".stripMargin,
        """import packet.alpha.`match`.δ.deep.{ordinary as named, ++ as operator, λ as unicodeBraced, `back-tick` as quotedBraced, *}
          |import packet.alpha.`match`.δ.deep.{ordinary => legacy}
          |import packet.alpha.`match`.δ.deep.ordinary as _
          |import packet.alpha.`match`.δ.deep.given
          |import packet.alpha.`match`.δ.deep.given Box[Int]
          |""".stripMargin
      )
      val fixtures      = packagePaths.map(_._1) ++ importSources
      val snapshots     = fixtures.zipWithIndex.map: (source, index) =>
        val snapshot = parse(bridge, source, s"file:///RecursiveStablePath$index.scala")
        assertEquals(source, snapshot.sourceText)
        assertTrue(snapshot.diagnostics.forall(_.severity != ParserDiagnosticSeverity.Error))
        assertEquals(source, ProvisionalSourceEvidencePlanner.plan(snapshot).toOption.get.reconstruct(source))
        snapshot

      def node(snapshot: ParserSyntaxSnapshot, id: Long): ParserSyntaxNode                                       = snapshot.nodes.find(_.id == id).get
      def childId(value: ParserFieldValue): Long                                                                 = value match
        case ParserFieldValue.Node(id) => id
        case other                     => throw new AssertionError(s"stable path child is $other")
      def name(value: ParserFieldValue): String                                                                  = value match
        case ParserFieldValue.Name(value) => value
        case other                        => throw new AssertionError(s"stable path name is $other")
      def stableSegments(snapshot: ParserSyntaxSnapshot, id: Long): Vector[String]                               =
        val current = node(snapshot, id)
        current.production match
          case "Ident"  => Vector(name(current.fields.find(_.name == "name").get.value))
          case "Select" =>
            stableSegments(snapshot, childId(current.fields.find(_.name == "qualifier").get.value)) :+
              name(current.fields.find(_.name == "name").get.value)
          case other    => throw new AssertionError(s"stable path production is $other")
      def assertLineage(snapshot: ParserSyntaxSnapshot, rootId: Long, anchorId: Long, anchorField: String): Unit =
        var current = node(snapshot, rootId)
        assertEquals(
          Vector(ParserNodeOccurrence(anchorId, Vector(ParserFieldPathSegment.NamedField(anchorField)))),
          current.occurrences
        )
        while current.production == "Select" do
          val child = node(snapshot, childId(current.fields.find(_.name == "qualifier").get.value))
          assertEquals(
            Vector(ParserNodeOccurrence(current.id, Vector(ParserFieldPathSegment.NamedField("qualifier")))),
            child.occurrences
          )
          current = child
        assertEquals("Ident", current.production)

      packagePaths
        .zip(snapshots.take(packagePaths.size))
        .foreach: (fixture, snapshot) =>
          val expected = fixture._2
          val root     = node(snapshot, snapshot.rootNodeId)
          val pathId   = childId(root.fields.find(_.name == "pid").get.value)
          assertEquals(expected, stableSegments(snapshot, pathId))
          assertLineage(snapshot, pathId, root.id, "pid")
          assertEquals(expected.size - 1, snapshot.nodes.count(_.production == "Select"))

      val qualifier = Vector("packet", "alpha", "match", "δ", "deep")
      importSources
        .zip(snapshots.drop(packagePaths.size))
        .foreach: (source, snapshot) =>
          val imports = snapshot.nodes.filter(_.production == "Import")
          assertEquals(source.count(_ == '\n'), imports.size)
          imports.foreach: statement =>
            val pathId = childId(statement.fields.find(_.name == "expr").get.value)
            assertEquals(qualifier, stableSegments(snapshot, pathId))
            assertLineage(snapshot, pathId, statement.id, "expr")

      val runtimes  = snapshots.map(snapshot => CompilerRuntimeInventory.from(snapshot).toOption.get)
      val aggregate = AggregatedCompilerProductionInventory.aggregate(runtimes).toOption.get
      val surfaces  = withImportTokenSurfaces(ScalaPsiSurfaceInventory.installed().toOption.get)
      snapshots
        .zip(runtimes)
        .foreach: (snapshot, runtime) =>
          val prepared = PreparedProductionCatalog
            .prepareRuntimeSubset(Scala3PsiProductionCatalog.Reviewed, runtime, aggregate, surfaces)
            .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
          val evidence = ProvisionalSourceEvidencePlanner.plan(snapshot).toOption.get
          val plan     = WholeFileProductionPlanner
            .plan(snapshot, evidence, prepared)
            .fold(error => throw new AssertionError(error.toString), identity)
          assertEquals(
            snapshot.sourceText,
            plan.physicalLeafOwnership
              .sortBy(_.start)
              .map(leaf => snapshot.sourceText.substring(leaf.start, leaf.end))
              .mkString
          )
          assertFalse(plan.physicalLeafOwnership.exists(leaf => leaf.start == leaf.end))
          assertEquals(evidence.structural.map(_.id), plan.structuralEvidenceOwnership.map(_.eventId))

      assertEquals(
        Vector(
          "1c3d14098fae95344e6f9e910b17d40aaca45af24c7afe3fb8d4089faded3076",
          "7f0c6d410d48d5e7b5c2c975266b5c180d6e12140a769d1c4b0e126b22193888",
          "99512bdb0e36455981a357bec40959a0d8682fd38eb391632ee320f4efd9f797",
          "7512a4ff34739076d77ccbe91e8aadd2ccc2078296dbc6c021f90375bf2249ea",
          "ed8263721056a9710eba372b4c462f410000a9d06ebecb63b7d89812b36ea4a4"
        ),
        snapshots.map(ParserSyntaxSnapshot.evidenceFingerprint) :+ aggregate.fingerprint
      )
    finally bridge.close()

  @Test
  def veryDeepStablePathsCompleteAggregateAndWholeFilePlanningDeterministically(): Unit =
    val bridge = openBridge()
    try
      val surfaces                                                                            = withImportTokenSurfaces(
        ScalaPsiSurfaceInventory.installed().fold(message => throw new AssertionError(message), identity)
      )
      def assertPlan(source: String, uri: String, depth: Int, productionPrefix: String): Unit =
        val snapshot  = parse(bridge, source, uri)
        assertEquals(snapshot, parse(bridge, source, uri))
        val nodes     = snapshot.nodes.map(node => node.id -> node).toMap
        val indices   = snapshot.nodes.zipWithIndex.map((node, index) => node.id -> index).toMap
        val anchor    =
          if productionPrefix == "package-stable" then nodes(snapshot.rootNodeId) -> "pid"
          else
            val statement = if source.startsWith("export") then "Export" else "Import"
            snapshot.nodes.find(_.production == statement).get -> "expr"
        val firstId   = anchor._1.fields.collectFirst {
          case ParserSyntaxField(field, ParserFieldValue.Node(id), _) if field == anchor._2 => id
        }.get
        val lineage   = Vector.newBuilder[ParserSyntaxNode]
        var current   = nodes(firstId)
        assertEquals(
          Vector(ParserNodeOccurrence(anchor._1.id, Vector(ParserFieldPathSegment.NamedField(anchor._2)))),
          current.occurrences
        )
        var complete  = false
        while !complete do
          lineage += current
          current.production match
            case "Select" =>
              val nextId = current.fields.collectFirst {
                case ParserSyntaxField("qualifier", ParserFieldValue.Node(id), _) => id
              }.get
              val next   = nodes(nextId)
              assertEquals(
                Vector(ParserNodeOccurrence(current.id, Vector(ParserFieldPathSegment.NamedField("qualifier")))),
                next.occurrences
              )
              current = next
            case "Ident"  => complete = true
            case other    => throw new AssertionError(s"deep stable path contains $other")
        val chain     = lineage.result()
        assertEquals(depth, chain.size)
        assertEquals(Vector.fill(depth - 1)("Select") :+ "Ident", chain.map(_.production))
        assertTrue(chain.map(node => indices(node.id)).sliding(2).forall {
          case Vector(left, right) => right == left + 1
          case _                   => true
        })
        val evidence  = ProvisionalSourceEvidencePlanner
          .plan(snapshot)
          .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
        val runtime   = CompilerRuntimeInventory
          .from(snapshot)
          .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
        val aggregate = AggregatedCompilerProductionInventory
          .aggregate(Vector(runtime))
          .fold(error => throw new AssertionError(error.toString), identity)
        val prepared  = PreparedProductionCatalog
          .prepareRuntimeSubset(Scala3PsiProductionCatalog.Reviewed, runtime, aggregate, surfaces)
          .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
        def plan()    = WholeFileProductionPlanner
          .plan(snapshot, evidence, prepared)
          .fold(error => throw new AssertionError(error.toString), identity)
        val first     = plan()
        val repeated  = Option.when(depth == 1024)(plan())

        assertEquals(depth - 1, snapshot.nodes.count(_.production == "Select"))
        val stable = first.composites.filter(_.productionId.startsWith(productionPrefix))
        assertEquals(
          Vector.fill(depth - 1)(s"$productionPrefix-reference") :+ s"$productionPrefix-identifier",
          stable.map(_.productionId)
        )
        assertTrue(stable.map(_.range).sliding(2).forall {
          case Vector(outer, inner) => outer.startOffset == inner.startOffset && inner.endOffset < outer.endOffset
          case _                    => true
        })
        repeated.foreach(second => assertEquals(first, second))
        assertEquals(source, evidence.reconstruct(source))
        assertEquals(
          source,
          first.physicalLeafOwnership.sortBy(_.start).map(leaf => source.substring(leaf.start, leaf.end)).mkString
        )
        assertFalse(first.physicalLeafOwnership.exists(leaf => leaf.start == leaf.end))
        assertEquals(evidence.structural.map(_.id), first.structuralEvidenceOwnership.map(_.eventId))

      Vector(1024, 4096).foreach: depth =>
        val segments = Vector.tabulate(depth)(index => s"p$index")
        assertPlan(
          s"package ${segments.mkString(".")}\n",
          s"file:///VeryDeepStablePackagePath$depth.scala",
          depth,
          "package-stable"
        )
        assertPlan(
          s"import ${segments.mkString(".")}.target\n",
          s"file:///VeryDeepStableImportPath$depth.scala",
          depth,
          "import-path"
        )
        if depth == 1024 then
          assertPlan(
            s"export ${segments.mkString(".")}.target\n",
            s"file:///VeryDeepStableExportPath$depth.scala",
            depth,
            "import-path"
          )
    finally bridge.close()

  @Test
  def minimizedFilePackageFamilyHasAnExactInventory(): Unit =
    val bridge = openBridge()
    try
      val value                = parse(bridge, PackageSource, "file:///Scala3PackageFamily.scala")
      val inventory            = CompilerRuntimeInventory
        .from(value)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
      val aggregate            = AggregatedCompilerProductionInventory
        .aggregate(Vector(inventory))
        .fold(failure => throw new AssertionError(failure.toString), identity)
      val evidence             = ProvisionalSourceEvidencePlanner
        .plan(value)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
      assertEquals(
        Vector(
          ParserSyntaxNode(
            0,
            "PackageDef",
            Vector(
              ParserSyntaxField("pid", ParserFieldValue.Node(1), Some(ParserDeclaredShape.Node)),
              ParserSyntaxField(
                "stats",
                ParserFieldValue.Repeated(Vector.empty),
                Some(ParserDeclaredShape.Repeated(ParserDeclaredShape.Node))
              )
            ),
            ParserNodePosition.Positioned(PcSourceRange(0, 22), 16, ParserPositionProvenance.SourceDerived),
            Vector.empty
          ),
          ParserSyntaxNode(
            1,
            "Select",
            Vector(
              ParserSyntaxField("qualifier", ParserFieldValue.Node(2), Some(ParserDeclaredShape.Node)),
              ParserSyntaxField("name", ParserFieldValue.Name("syntax"), Some(ParserDeclaredShape.Name))
            ),
            ParserNodePosition.Positioned(PcSourceRange(8, 22), 16, ParserPositionProvenance.SourceDerived),
            Vector(ParserNodeOccurrence(0, Vector(ParserFieldPathSegment.NamedField("pid"))))
          ),
          ParserSyntaxNode(
            2,
            "Ident",
            Vector(ParserSyntaxField("name", ParserFieldValue.Name("example"), Some(ParserDeclaredShape.Name))),
            ParserNodePosition.Positioned(PcSourceRange(8, 15), 8, ParserPositionProvenance.SourceDerived),
            Vector(ParserNodeOccurrence(1, Vector(ParserFieldPathSegment.NamedField("qualifier"))))
          )
        ),
        value.nodes
      )
      assertEquals(PackageSource, evidence.reconstruct(PackageSource))
      assertEquals("96d9782ed2920c73cbd6529a4c6ab804ba0b2e97ec8d61de1c67e10c402ef484", aggregate.fingerprint)
      val identifierPackage    = parse(bridge, "package example\n", "file:///Scala3IdentifierPackageFamily.scala")
      val identifierInventory  = CompilerRuntimeInventory
        .from(identifierPackage)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
      val preparationAggregate = AggregatedCompilerProductionInventory
        .aggregate(Vector(inventory, identifierInventory))
        .fold(failure => throw new AssertionError(failure.toString), identity)
      val surfaces             = withImportTokenSurfaces(
        ScalaPsiSurfaceInventory.installed().fold(message => throw new AssertionError(message), identity)
      )
      val catalog              = Scala3PsiProductionCatalog(
        Scala3PsiProductionCatalog.Reviewed.productions.filter(production =>
          production.id == "file-package" || production.id.startsWith("package-stable")
        ),
        StableRoleInventory.Reviewed
      )
      val prepared             = PreparedProductionCatalog
        .prepare(catalog, preparationAggregate, surfaces)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
      val plan                 = WholeFileProductionPlanner
        .plan(value, evidence, prepared)
        .fold(failure => throw new AssertionError(failure.toString), identity)
      assertEquals(
        Vector("file-package", "package-stable-reference", "package-stable-identifier"),
        plan.composites.map(_.productionId)
      )
      assertEquals(
        2L,
        plan.physicalLeafOwnership.find(leaf => leaf.start == 8 && leaf.end == 15).get.owner match
          case PhysicalLeafOwner.Composite(owner) => owner.origin.valueId
          case PhysicalLeafOwner.FileRoot         => throw new AssertionError("package identifier leaf is file-owned")
      )
      assertEquals(
        1L,
        plan.physicalLeafOwnership.find(leaf => leaf.start == 15 && leaf.end == 22).get.owner match
          case PhysicalLeafOwner.Composite(owner) => owner.origin.valueId
          case PhysicalLeafOwner.FileRoot         => throw new AssertionError("package identifier leaf is file-owned")
      )
      val trailing             = plan.physicalLeafOwnership.find(leaf => leaf.start == 22 && leaf.end == 23).get
      assertEquals(PhysicalLeafOwner.FileRoot, trailing.owner)
      assertEquals("whole-file", trailing.terminalId)
      assertEquals(
        PackageSource,
        plan.physicalLeafOwnership.sortBy(_.start).map(leaf => PackageSource.substring(leaf.start, leaf.end)).mkString
      )
    finally bridge.close()

  @Test
  def minimizedFilePackageImportFamilyHasAnExactInventory(): Unit =
    val bridge = openBridge()
    try
      val first     = parse(bridge, FamilySource, "file:///Scala3FileFamily.scala")
      val second    = parse(bridge, FamilySource, "file:///Scala3FileFamily.scala")
      val inventory = CompilerRuntimeInventory
        .from(first)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
      val aggregate = AggregatedCompilerProductionInventory
        .aggregate(Vector(inventory))
        .fold(failure => throw new AssertionError(failure.toString), identity)
      val evidence  = ProvisionalSourceEvidencePlanner
        .plan(first)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)

      assertEquals(first, second)
      assertEquals(FamilySource, first.sourceText)
      assertEquals(ParserSyntaxSnapshot.digest(FamilySource), first.sourceDigest)
      assertEquals(ParserSyntaxSnapshot.evidenceFingerprint(first), evidence.parserEvidenceFingerprint)
      assertEquals(FamilySource, evidence.reconstruct(FamilySource))
      assertTrue(first.diagnostics.isEmpty)
      assertEquals(
        Vector(
          "PackageDef"     -> Vector("pid", "stats"),
          "Select"         -> Vector("qualifier", "name"),
          "Ident"          -> Vector("name"),
          "Import"         -> Vector("expr", "selectors"),
          "Select"         -> Vector("qualifier", "name"),
          "Select"         -> Vector("qualifier", "name"),
          "Ident"          -> Vector("name"),
          "ImportSelector" -> Vector("imported", "renamed", "bound"),
          "Ident"          -> Vector("name"),
          "Thicket"        -> Vector("trees")
        ),
        first.nodes.map(node => node.production -> node.fields.map(_.name))
      )
      assertEquals(
        Vector(
          ParserNodePosition.Positioned(PcSourceRange(0, 62), 16, ParserPositionProvenance.SourceDerived),
          ParserNodePosition.Positioned(PcSourceRange(8, 22), 16, ParserPositionProvenance.SourceDerived),
          ParserNodePosition.Positioned(PcSourceRange(8, 15), 8, ParserPositionProvenance.SourceDerived),
          ParserNodePosition.Positioned(PcSourceRange(24, 62), 31, ParserPositionProvenance.SourceDerived),
          ParserNodePosition.Positioned(PcSourceRange(31, 57), 48, ParserPositionProvenance.SourceDerived),
          ParserNodePosition.Positioned(PcSourceRange(31, 47), 37, ParserPositionProvenance.SourceDerived),
          ParserNodePosition.Positioned(PcSourceRange(31, 36), 31, ParserPositionProvenance.SourceDerived),
          ParserNodePosition.Positioned(PcSourceRange(58, 62), 58, ParserPositionProvenance.Synthetic),
          ParserNodePosition.Positioned(PcSourceRange(58, 62), 58, ParserPositionProvenance.SourceDerived),
          ParserNodePosition.Absent
        ),
        first.nodes.map(_.position)
      )
      assertEquals(
        Vector(
          Vector.empty,
          Vector(ParserNodeOccurrence(0, Vector(ParserFieldPathSegment.NamedField("pid")))),
          Vector(ParserNodeOccurrence(1, Vector(ParserFieldPathSegment.NamedField("qualifier")))),
          Vector(
            ParserNodeOccurrence(
              0,
              Vector(ParserFieldPathSegment.NamedField("stats"), ParserFieldPathSegment.RepeatedIndex(0))
            )
          ),
          Vector(ParserNodeOccurrence(3, Vector(ParserFieldPathSegment.NamedField("expr")))),
          Vector(ParserNodeOccurrence(4, Vector(ParserFieldPathSegment.NamedField("qualifier")))),
          Vector(ParserNodeOccurrence(5, Vector(ParserFieldPathSegment.NamedField("qualifier")))),
          Vector(
            ParserNodeOccurrence(
              3,
              Vector(ParserFieldPathSegment.NamedField("selectors"), ParserFieldPathSegment.RepeatedIndex(0))
            )
          ),
          Vector(ParserNodeOccurrence(7, Vector(ParserFieldPathSegment.NamedField("imported")))),
          Vector(
            ParserNodeOccurrence(7, Vector(ParserFieldPathSegment.NamedField("renamed"))),
            ParserNodeOccurrence(7, Vector(ParserFieldPathSegment.NamedField("bound")))
          )
        ),
        first.nodes.map(_.occurrences)
      )
      assertEquals("a7f0c41f1d1496860eeb3bc380f2a1ad7aca5706921119d171f465056ff2be62", aggregate.fingerprint)
      val catalog                = Scala3PsiProductionCatalog(
        Scala3PsiProductionCatalog.Reviewed.productions.filter(production =>
          production.id.startsWith("file-package") || production.id.startsWith("package-stable")
        ),
        StableRoleInventory.Reviewed
      )
      def selected(nodeId: Long) =
        val row      = inventory.shapes.find(_.id == nodeId).get
        val contexts = if row.contexts.isEmpty then Vector(None) else row.contexts.map(Some(_))
        contexts.flatMap(context =>
          CatalogShapeMatcher.select(catalog, row.kind, row.prefix, row.observation, context, row.sourceClassification)
        )
      assertEquals(Vector("file-package-top-statements"), selected(0).map(_.id))
      assertTrue(selected(6).isEmpty)
    finally bridge.close()

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

      val productions                                                                              = first.nodes.map(_.production).toSet
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
      val greetingId                                                                               = first.nodes
        .find(node =>
          node.production == "DefDef" &&
            node.fields.exists(field => field.name == "name" && field.value == ParserFieldValue.Name("greeting"))
        )
        .map(_.id)
        .getOrElse(throw new AssertionError("greeting definition is absent"))
      assertTrue(
        first.nodes.exists(node =>
          node.production == "ValDef" &&
            node.occurrences.contains(
              ParserNodeOccurrence(
                greetingId,
                Vector(
                  ParserFieldPathSegment.NamedField("paramss"),
                  ParserFieldPathSegment.RepeatedIndex(1),
                  ParserFieldPathSegment.RepeatedIndex(0)
                )
              )
            )
        )
      )
      val personId                                                                                 = first.nodes
        .find(node =>
          node.production == "TypeDef" &&
            node.fields.exists(field => field.name == "name" && field.value == ParserFieldValue.Name("Person"))
        )
        .map(_.id)
        .getOrElse(throw new AssertionError("Person definition is absent"))
      assertTrue(
        first.positioned.exists(value =>
          value.production == "Final" &&
            value.occurrences.contains(
              ParserPositionedOccurrence(
                personId,
                Vector(
                  ParserFieldPathSegment.NamedField("mods"),
                  ParserFieldPathSegment.NestedProductBoundary("Modifiers"),
                  ParserFieldPathSegment.NamedField("mods"),
                  ParserFieldPathSegment.RepeatedIndex(0)
                )
              )
            )
        )
      )
      val firstInventory                                                                           = CompilerRuntimeInventory
        .from(first)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
      val secondInventory                                                                          = CompilerRuntimeInventory
        .from(second)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
      val firstAggregation                                                                         = AggregatedCompilerProductionInventory.aggregate(Vector(firstInventory, secondInventory))
      assertTrue(firstAggregation.toString, firstAggregation.isRight)
      def modifierAnnotations(value: InventoryValueObservation): Vector[InventoryFieldObservation] = value match
        case InventoryValueObservation.Product("Modifiers", fields) => fields.filter(_.name == "annotations")
        case InventoryValueObservation.Product(_, fields)           => fields.flatMap(f => modifierAnnotations(f.value))
        case InventoryValueObservation.Optional(value)              => value.toVector.flatMap(modifierAnnotations)
        case InventoryValueObservation.Repeated(values)             => values.flatMap(modifierAnnotations)
        case _                                                      => Vector.empty
      val annotations                                                                              = firstInventory.shapes
        .flatMap(_.observation)
        .flatMap(field => modifierAnnotations(field.value))
        .headOption
        .getOrElse(throw new AssertionError("Modifiers.annotations is absent"))
      assertEquals(InventoryValueObservation.Repeated(Vector.empty), annotations.value)
      assertEquals(Some(CatalogValuePattern.Repeated(CatalogValuePattern.Node)), annotations.declaredPattern)
      assertEquals(
        firstAggregation,
        AggregatedCompilerProductionInventory.aggregate(Vector(secondInventory, firstInventory))
      )
      val number                                                                                   = first.nodes
        .find(_.production == "Number")
        .getOrElse(throw new AssertionError("integer Number production is absent"))
      val numberStart                                                                              = Source.indexOf("0)")
      assertEquals(
        Vector(
          ParserSyntaxField(
            "digits",
            ParserFieldValue.Scalar(ParserScalar.Text("0")),
            Some(ParserDeclaredShape.Scalar("Text"))
          ),
          ParserSyntaxField(
            "kind",
            ParserFieldValue.Product(
              "Whole",
              Vector(
                ParserSyntaxField(
                  "radix",
                  ParserFieldValue.Scalar(ParserScalar.Integer(10)),
                  Some(ParserDeclaredShape.Scalar("Integer"))
                )
              )
            )
          )
        ),
        number.fields
      )
      assertEquals(
        ParserNodePosition.Positioned(
          PcSourceRange(numberStart, numberStart + 1),
          numberStart,
          ParserPositionProvenance.SourceDerived
        ),
        number.position
      )
      val comparison                                                                               = first.nodes
        .find(node => node.production == "InfixOp" && node.fields.exists(_.value == ParserFieldValue.Node(number.id)))
        .getOrElse(throw new AssertionError("integer Number owner is absent"))
      assertEquals(
        Vector(
          ParserNodeOccurrence(
            comparison.id,
            Vector(ParserFieldPathSegment.NamedField("right"))
          )
        ),
        number.occurrences
      )
      val aggregate                                                                                = firstAggregation.toOption.get
      val installedSurfaces                                                                        = ScalaPsiSurfaceInventory
        .installed()
        .fold(message => throw new AssertionError(message), identity)
      val surfaces                                                                                 = withImportTokenSurfaces(installedSurfaces)
      assertEquals("abd7b4fa78eb68661b6d46f5e74dededc2d1be5dbd31f78c8eca5fef3ffe3f26", aggregate.fingerprint)
      assertEquals("878bfefb423fd893f2a0fae757394766452d75950757ff05b24ccae6c8e5cd0a", installedSurfaces.fingerprint)
      val catalogErrors                                                                            = Scala3PsiProductionCatalogValidator.validate(
        Scala3PsiProductionCatalog.Reviewed,
        aggregate,
        surfaces
      )
      val expectedUncovered                                                                        = aggregate.productions
        .flatMap(row =>
          row.occurrences.collect:
            case occurrence
                if CatalogShapeMatcher
                  .selectAggregated(Scala3PsiProductionCatalog.Reviewed, row, occurrence)
                  .isEmpty =>
              CatalogValidationError.UncoveredCompilerShape(
                row.kind,
                row.prefix,
                occurrence.context,
                occurrence.sourceClassification
              )
        )
        .toSet
      val actualUncovered                                                                          = catalogErrors.collect:
        case error: CatalogValidationError.UncoveredCompilerShape => error
      assertEquals(expectedUncovered, actualUncovered.toSet)
      val accounted                                                                                = Scala3PsiProductionCatalog.Reviewed.productions
        .flatMap(_.effectiveOutputRealizations)
        .flatMap(_.template.composites)
        .flatMap: composite =>
          val persistence = composite.persistence match
            case PersistenceObligations.NotApplicable                                   => Vector.empty
            case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
              Vector(stub, serializer, navigation) ++ indices
          Vector(composite.targetSurfaceId) ++ composite.accessors.map(_.surfaceId) ++ persistence
        .toSet ++ Scala3PsiProductionCatalog.Reviewed.productions.flatMap(_.terminals.collect {
        case TerminalDeclaration(_, _, TerminalLeafTarget.Token(surfaceId, _), _, _, _) => surfaceId
      })
      val expectedUnaccounted                                                                      = surfaces.rows
        .filter(row =>
          row.status == FactStatus.Available &&
            row.classification == SurfaceClassification.SyntaxContract &&
            !accounted(row.id)
        )
        .map(row => CatalogValidationError.UnaccountedSyntaxSurface(row.id))
        .toSet
      val actualUnaccounted                                                                        = catalogErrors.collect:
        case error: CatalogValidationError.UnaccountedSyntaxSurface => error
      assertEquals(expectedUnaccounted, actualUnaccounted.toSet)
      assertTrue(
        catalogErrors.contains(
          CatalogValidationError.UnrepresentedCatalogProduction(
            "file-package",
            GrammarRoleId.CompilationUnit
          )
        )
      )
      assertFalse(
        catalogErrors.toString,
        catalogErrors.exists(error =>
          !error.isInstanceOf[CatalogValidationError.UncoveredCompilerShape] &&
            !error.isInstanceOf[CatalogValidationError.UnaccountedSyntaxSurface] &&
            !error.isInstanceOf[CatalogValidationError.UnrepresentedCatalogProduction]
        )
      )
      val report                                                                                   = Scala3PsiProductionCoverageReport.markdown(
        Scala3PsiProductionCatalog.Reviewed,
        aggregate,
        surfaces
      )
      assertEquals(
        report,
        Scala3PsiProductionCoverageReport.markdown(Scala3PsiProductionCatalog.Reviewed, aggregate, surfaces)
      )
      assertTrue(report, report.contains("### `Node.Number`"))
      assertTrue(report, report.contains("- Validation: **incomplete**"))
      assertTrue(report, report.contains("grammar-role=scala.literal.integer"))
      assertTrue(report, report.contains("catalog-alternative=integer-literal-number"))
      assertTrue(report, report.contains("output-roles=scala.literal.integer,scala.source.terminal"))
      assertTrue(report, report.contains("providers=NativeCandidate"))
      assertTrue(report, report.contains("missing-boundary=compatibility-binding"))
      assertTrue(report, report.contains("### `Node.PackageDef`"))
      assertTrue(report, report.contains("compiler-context=root:SourceReachable"))
      assertTrue(report, report.contains("missing-boundary=bridge-normalization-or-neutral-grammar-role"))
      assertTrue(
        report,
        report.contains(
          "`Element:org/jetbrains/plugins/scala/lang/psi/impl/base/literals/ScIntegerLiteralImpl` — **Available:catalog-referenced:integer-literal-number**"
        )
      )
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
      case ParserSyntaxNode(_, `production`, _, position: ParserNodePosition.Positioned, _) => position
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
        node.fields.exists(field => field.name == "name" && field.value == ParserFieldValue.Name("greeting"))
    val clauses  = greeting.toVector
      .flatMap(_.fields)
      .collectFirst:
        case ParserSyntaxField("paramss", ParserFieldValue.Repeated(values), _) => values
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
        case ParserFieldValue.Positioned(_)          => false
        case ParserFieldValue.Name(_)                => false
        case ParserFieldValue.GeneratedName(_, _, _) => false
        case ParserFieldValue.Scalar(_)              => false

    assertFalse(snapshot.nodes.flatMap(_.fields).exists(field => containsUnsupported(field.value)))

  private def assertAllPositionsBelongToSource(snapshot: ParserSyntaxSnapshot): Unit =
    snapshot.nodes.foreach:
      case ParserSyntaxNode(_, _, _, ParserNodePosition.Absent, _)                      => ()
      case ParserSyntaxNode(_, _, _, ParserNodePosition.Positioned(range, point, _), _) =>
        assertTrue(range.startOffset >= 0)
        assertTrue(range.endOffset <= Source.length)
        assertTrue(point >= range.startOffset)
        assertTrue(point <= range.endOffset)

  private def parse(bridge: Scala3ParserBridge): ParserSyntaxSnapshot =
    parse(bridge, Source, "file:///Scala3ParserVerticalSlice.scala")

  private def parse(bridge: Scala3ParserBridge, source: String, uri: String): ParserSyntaxSnapshot =
    bridge
      .parse(
        Scala3ParserRequest(
          ParserSourceUri
            .from(uri)
            .fold(message => throw new AssertionError(message), identity),
          source,
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
      |@deprecated("sample", "1")
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

  private val FamilySource =
    """package example.syntax
      |
      |import scala.collection.immutable.List
      |""".stripMargin

  private val PackageSource = "package example.syntax\n"

  private def withImportTokenSurfaces(inventory: ScalaPsiSurfaceInventory): ScalaPsiSurfaceInventory =
    val tokens = Vector(
      NativePsiElementBindings.ImportWildcardTokenSurface,
      NativePsiElementBindings.ImportLegacyWildcardTokenSurface,
      NativePsiElementBindings.ImportAliasAsTokenSurface,
      NativePsiElementBindings.ImportAliasArrowTokenSurface
    )
    inventory.copy(rows =
      inventory.rows ++ tokens.map(id =>
        ScalaPsiSurfaceRow(
          id,
          SurfaceFactKind.Token,
          None,
          FactStatus.Available,
          SurfaceClassification.SyntaxContract
        )
      ) ++ Vector(
        ScalaPsiSurfaceRow(
          ImportPersistenceSurfaces.AliasedImportIndex,
          SurfaceFactKind.Index,
          None,
          FactStatus.Available,
          SurfaceClassification.SyntaxContract
        ),
        ScalaPsiSurfaceRow(
          ExportPersistenceSurfaces.TopLevelPackageIndex,
          SurfaceFactKind.Index,
          None,
          FactStatus.Available,
          SurfaceClassification.SyntaxContract
        ),
        ScalaPsiSurfaceRow(
          PackagePersistenceSurfaces.FqnIndex,
          SurfaceFactKind.Index,
          None,
          FactStatus.Available,
          SurfaceClassification.SyntaxContract
        ),
        ScalaPsiSurfaceRow(
          ImportPersistenceSurfaces.SelfNavigation,
          SurfaceFactKind.Navigation,
          None,
          FactStatus.Available,
          SurfaceClassification.SyntaxContract
        )
      )
    )

  private val ScalaVersion = "3.7.4"
