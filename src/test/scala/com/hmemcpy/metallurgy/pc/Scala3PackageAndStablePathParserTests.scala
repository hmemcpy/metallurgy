package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.psiproducer.{
  AggregatedCompilerProductionInventory,
  AttachmentEvidence,
  CompilerRuntimeInventory,
  NativePsiElementBindings,
  PlanningWorkObserver,
  PhysicalLeafOwner,
  Scala3PsiProductionCatalog,
  ScalaPsiSurfaceInventory,
  TerminalLeafTarget,
  ProvisionalSourceEvidencePlanner,
  PreparedProductionCatalog,
  WholeFileProductionPlanner
}
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.Test

private[pc] trait Scala3PackageAndStablePathParserTests extends Scala3ParserTestSupport:

  @Test
  def packageBodiesHaveExactAttachmentEvidenceAndGenericClosedOutputForests(): Unit =
    val bridge = openBridge()
    try
      val cases     = Vector(
        (
          """package braced { /* open */
            |  import a.b; export c.d
            |  package nested { import e.f; export g.h }
            |}
            |package empty { /* body trivia */ }
            |""".stripMargin,
          3,
          0,
          2
        ),
        (
          """package outer:
            |  import a.b
            |  package inner:
            |    export c.d
            |  end inner
            |end outer
            |package sibling:
            |  import e.f
            |end sibling
            |""".stripMargin,
          3,
          3,
          2
        ),
        ("package a; package b\nimport c.d\nexport e.f\n", 2, 0, 1),
        ("package qualified.name { import a.b; export c.d } // trailing\n", 1, 0, 1),
        (
          """package first:
            |  import scala.collection.mutable
            |  export scala.Predef.identity
            |  import scala.collection.immutable; export scala.Predef.println
            |  // trailing indented
            |package peer:
            |  export scala.Predef.assert
            |""".stripMargin,
          2,
          0,
          2
        ),
        (
          """package outer { /* open */
            |  package inner { import scala.collection.mutable; /* inner tail */ }
            |  /* outer tail */
            |}
            |""".stripMargin,
          2,
          0,
          1
        )
      )
      val snapshots = cases.zipWithIndex.map { case ((source, _, _, _), index) =>
        val snapshot = parse(bridge, source, s"file:///PackageLayout$index.scala")
        assertTrue(snapshot.diagnostics.toString, snapshot.diagnostics.isEmpty)
        assertEquals(source, ProvisionalSourceEvidencePlanner.plan(snapshot).toOption.get.reconstruct(source))
        snapshot
      }
      val runtimes  = snapshots.map(CompilerRuntimeInventory.from(_).toOption.get)
      val aggregate = AggregatedCompilerProductionInventory.aggregate(runtimes).toOption.get
      val surfaces  = withImportTokenSurfaces(ScalaPsiSurfaceInventory.installed().toOption.get)

      snapshots.zip(runtimes).zip(cases).foreach { case ((snapshot, runtime), (_, packages, ends, roots)) =>
        val prepared          = PreparedProductionCatalog
          .prepareRuntimeSubset(Scala3PsiProductionCatalog.Reviewed, runtime, aggregate, surfaces)
          .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
        val evidence          = ProvisionalSourceEvidencePlanner.plan(snapshot).toOption.get
        val plan              = WholeFileProductionPlanner
          .plan(snapshot, evidence, prepared)
          .fold(error => throw new AssertionError(error.toString), identity)
        val packageComposites = plan.composites.filter(_.instance.localOutputId == "package")
        val endComposites     = plan.composites.filter(_.instance.localOutputId == "end")
        val parentByChild     = plan.composites.flatMap(parent => parent.children.map(_.child -> parent.instance)).toMap

        assertEquals(packages, packageComposites.size)
        assertEquals(ends, snapshot.endMarkers.size)
        assertEquals(ends, endComposites.size)
        assertTrue(
          endComposites.forall(end =>
            parentByChild.get(end.instance).exists(parent => packageComposites.exists(_.instance == parent))
          )
        )
        assertEquals(roots, packageComposites.count(packageClause => !parentByChild.contains(packageClause.instance)))
        assertEquals(
          snapshot.sourceText,
          plan.physicalLeafOwnership
            .sortBy(leaf => (leaf.start, leaf.end))
            .map(leaf => snapshot.sourceText.substring(leaf.start, leaf.end))
            .mkString
        )
        assertEquals(
          ends,
          plan.physicalLeafOwnership.count:
            case leaf @ com.hmemcpy.metallurgy.psiproducer.PlannedPhysicalLeaf(
                  _,
                  _,
                  _,
                  _,
                  _,
                  _,
                  TerminalLeafTarget.Token(surface, Some("end"))
                ) =>
              surface == NativePsiElementBindings.EndKeywordTokenSurface &&
              snapshot.sourceText.substring(leaf.start, leaf.end) == "end"
            case _ => false
        )
        if snapshot.sourceText.contains("// trailing indented") then
          val packageStart  = snapshot.sourceText.indexOf("package first")
          val packageClause = packageComposites.find(_.range.startOffset == packageStart).get
          val commentStart  = snapshot.sourceText.indexOf("// trailing indented")
          val comment       = plan.physicalLeafOwnership.find(_.start == commentStart).get
          assertEquals(packageClause.instance.origin, comment.sourceOwner)
          assertEquals(PhysicalLeafOwner.Composite(packageClause.instance), comment.owner)
        assertEquals(evidence.structural.map(_.id), plan.structuralEvidenceOwnership.map(_.eventId))
        assertFalse(
          plan.composites.exists(composite =>
            composite.instance.localOutputId.contains("body") || composite.instance.localOutputId.contains("block")
          )
        )
      }

      assertEquals(
        Vector(
          "cc3f6ee35c45c9e7b689e619377ac13474a6ceb5a002a864ee4fb4a5435eaa4f",
          "04e4d8bc6edeeb94a9e4e6093b167d9738f94d40787f4196dd4f8eaa82edc94a",
          "5e5b6520e00ea3c5f96380ccbb4b89c72d0b2553d2443535d2b1d78d95499a64",
          "27ed4cecad06bef262a37898c79db84f6f2a17d00e21cd4bbaf9ea7ccc653f4e",
          "1bd20209dc5ae9b4865f20761c06a4f5060ece5b47f40833924cfaaa4b9bf3cf",
          "4c4dfec05ede3ce02f0e00f1fb9f3ec44c2d52f6476fec2c98eab538343d030a"
        ),
        snapshots.map(ParserSyntaxSnapshot.evidenceFingerprint)
      )

      Vector(
        "package broken { import a.b\n",
        "package broken:\nimport a.b\n",
        "package a; import b.c; package d\n",
        "package a:\n  import b.c\nend wrong\n"
      ).zipWithIndex.foreach: (source, index) =>
        val snapshot = parse(bridge, source, s"file:///RecoveredPackageLayout$index.scala")
        assertTrue(snapshot.diagnostics.exists(_.severity == ParserDiagnosticSeverity.Error))
        val runtime  = CompilerRuntimeInventory.from(snapshot).toOption.get
        val combined = AggregatedCompilerProductionInventory.aggregate(Vector(runtime)).toOption.get
        PreparedProductionCatalog
          .prepareRuntimeSubset(Scala3PsiProductionCatalog.Reviewed, runtime, combined, surfaces)
          .foreach(prepared =>
            assertTrue(
              WholeFileProductionPlanner
                .plan(snapshot, ProvisionalSourceEvidencePlanner.plan(snapshot).toOption.get, prepared)
                .isLeft
            )
          )
    finally bridge.close()

  @Test
  def deeplyNestedPackageBodiesPlanDeterministicallyWithoutJvmRecursion(): Unit =
    val bridge = openBridge()
    try
      val depth      = 256
      val source     = Vector.tabulate(depth)(index => s"package p$index { ").mkString +
        "import scala.collection.mutable " + Vector.fill(depth)("}").mkString + "\n"
      val snapshot   = parse(bridge, source, "file:///DeepPackageBodies.scala")
      val repeated   = parse(bridge, source, "file:///DeepPackageBodies.scala")
      val evidence   = ProvisionalSourceEvidencePlanner
        .plan(snapshot)
        .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
      val runtime    = CompilerRuntimeInventory
        .from(snapshot)
        .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
      val aggregate  = AggregatedCompilerProductionInventory
        .aggregate(Vector(runtime))
        .fold(error => throw new AssertionError(error.toString), identity)
      val surfaces   = withImportTokenSurfaces(ScalaPsiSurfaceInventory.installed().toOption.get)
      val prepared   = PreparedProductionCatalog
        .prepareRuntimeSubset(Scala3PsiProductionCatalog.Reviewed, runtime, aggregate, surfaces)
        .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
      def plan()     = WholeFileProductionPlanner
        .plan(snapshot, evidence, prepared)
        .fold(error => throw new AssertionError(error.toString), identity)
      val first      = plan()
      val second     = plan()
      val packages   = first.composites.filter(_.instance.localOutputId == "package")
      val parents    = first.composites.flatMap(parent => parent.children.map(_.child -> parent.instance)).toMap
      val packageIds = packages.map(_.instance).toSet

      assertEquals(snapshot, repeated)
      assertTrue(snapshot.diagnostics.isEmpty)
      assertEquals(depth, snapshot.nodes.count(_.production == "PackageDef"))
      assertEquals(depth, packages.size)
      assertEquals(1, packages.count(packaging => !parents.contains(packaging.instance)))
      assertEquals(depth - 1, packages.count(packaging => parents.get(packaging.instance).exists(packageIds)))
      assertFalse(
        first.composites.exists(composite =>
          composite.instance.localOutputId.contains("body") || composite.instance.localOutputId.contains("block")
        )
      )
      assertEquals(first, second)
      assertEquals(source, evidence.reconstruct(source))
      assertEquals(
        source,
        first.physicalLeafOwnership
          .sortBy(leaf => (leaf.start, leaf.end))
          .map(leaf => source.substring(leaf.start, leaf.end))
          .mkString
      )
      assertEquals(evidence.structural.map(_.id), first.structuralEvidenceOwnership.map(_.eventId))
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
      assertEquals(
        Vector(
          3 -> Vector(
            (
              "Ident",
              19L,
              Vector(AttachmentEvidence("Backquoted", ParserAttachmentValue.RuntimeKind("BoxedUnit")))
            )
          )
        ),
        runtimes.zipWithIndex.flatMap: (runtime, index) =>
          val attached = runtime.shapes
            .filter(_.rootAttachments.nonEmpty)
            .map(row => (row.prefix, row.id, row.rootAttachments))
          Option.when(attached.nonEmpty)(index -> attached)
      )
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
          "950a6a85f4285b1a3efad61bccba1df52ec237f9fcb35c4cbaefb027f6eb5970",
          "b15f51c0a449cc9a0c8e55abd14feb523e79c8ef5875a57b356271fea2760f39"
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
        val snapshot                         = parse(bridge, source, uri)
        assertEquals(snapshot, parse(bridge, source, uri))
        val nodes                            = snapshot.nodes.map(node => node.id -> node).toMap
        val indices                          = snapshot.nodes.zipWithIndex.map((node, index) => node.id -> index).toMap
        val anchor                           =
          if productionPrefix == "package-stable" then nodes(snapshot.rootNodeId) -> "pid"
          else
            val statement = if source.startsWith("export") then "Export" else "Import"
            snapshot.nodes.find(_.production == statement).get -> "expr"
        val firstId                          = anchor._1.fields.collectFirst {
          case ParserSyntaxField(field, ParserFieldValue.Node(id), _) if field == anchor._2 => id
        }.get
        val lineage                          = Vector.newBuilder[ParserSyntaxNode]
        var current                          = nodes(firstId)
        assertEquals(
          Vector(ParserNodeOccurrence(anchor._1.id, Vector(ParserFieldPathSegment.NamedField(anchor._2)))),
          current.occurrences
        )
        var complete                         = false
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
        val chain                            = lineage.result()
        assertEquals(depth, chain.size)
        assertEquals(Vector.fill(depth - 1)("Select") :+ "Ident", chain.map(_.production))
        assertTrue(chain.map(node => indices(node.id)).sliding(2).forall {
          case Vector(left, right) => right == left + 1
          case _                   => true
        })
        val evidence                         = ProvisionalSourceEvidencePlanner
          .plan(snapshot)
          .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
        val runtime                          = CompilerRuntimeInventory
          .from(snapshot)
          .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
        val aggregate                        = AggregatedCompilerProductionInventory
          .aggregate(Vector(runtime))
          .fold(error => throw new AssertionError(error.toString), identity)
        val prepared                         = PreparedProductionCatalog
          .prepareRuntimeSubset(Scala3PsiProductionCatalog.Reviewed, runtime, aggregate, surfaces)
          .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
        val observer                         = CountingPlanningWorkObserver()
        def plan(work: PlanningWorkObserver) = WholeFileProductionPlanner
          .plan(snapshot, evidence, prepared, work)
          .fold(error => throw new AssertionError(error.toString), identity)
        val first                            = plan(observer)
        val repeated                         = Option.when(depth == 1024)(plan(PlanningWorkObserver.NoOp))

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
        assertTrue(
          s"depth=$depth finalOwnership=${observer.finalOwnership} terminal=${observer.terminal}",
          observer.finalOwnership <= 4L * depth && observer.terminal <= 3L * depth
        )

      Vector(64, 256, 1024).foreach: depth =>
        val segments = Vector.tabulate(depth)(index => s"p$index")
        assertPlan(
          s"package ${segments.mkString(".")}\n",
          s"file:///VeryDeepStablePackagePath$depth.scala",
          depth,
          "package-stable"
        )
      Vector(64, 256).foreach: depth =>
        val segments = Vector.tabulate(depth)(index => s"p$index")
        assertPlan(
          s"import ${segments.mkString(".")}.target\n",
          s"file:///VeryDeepStableImportPath$depth.scala",
          depth,
          "import-path"
        )
        assertPlan(
          s"export ${segments.mkString(".")}.target\n",
          s"file:///VeryDeepStableExportPath$depth.scala",
          depth,
          "import-path"
        )
    finally bridge.close()

  private final case class CountingPlanningWorkObserver() extends PlanningWorkObserver:
    var finalOwnership: Long = 0L
    var terminal: Long       = 0L

    override def finalOwnershipEntries(count: Int): Unit    = finalOwnership += count
    override def terminalLexicalEntries(count: Int): Unit   = terminal += count
    override def terminalCandidateEntries(count: Int): Unit = terminal += count
