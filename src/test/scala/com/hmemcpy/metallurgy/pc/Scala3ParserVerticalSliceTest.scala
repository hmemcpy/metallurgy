package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.psiproducer.{
  AggregatedCompilerProductionInventory,
  CatalogValidationError,
  CatalogShapeMatcher,
  CatalogValuePattern,
  CompilerRuntimeInventory,
  ContextPattern,
  FactStatus,
  GrammarRoleId,
  ImportPersistenceSurfaces,
  InventoryKind,
  NativePsiElementBindings,
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
  def boundedGivenTypeFormsHaveExactCompilerShapes(): Unit =
    val bridge = openBridge()
    try
      val forms                                                                            = Vector(
        (
          "import a.b.given scala.math.Ordering.Int\n",
          "Select",
          Vector("Select", "Select", "Select", "Ident"),
          "00670f260ab45176568fa19a5ee0c88543d9528f7247cb5098960dd000e3a795"
        ),
        (
          "import a.b.given scala.math.Ordering[Int]\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Select", "Select", "Ident", "Ident"),
          "7e0c093d85f13fc8bd51829458a6faf85326c4fe39c1d688def73efdb7265901"
        ),
        (
          "import a.b.given F[?]\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Ident", "TypeBoundsTree", "Thicket"),
          "a654db8bba027cbe5bb169b38f1592ef571248b6c33fc4b0218bcc28f8dcce2f"
        ),
        (
          "import a.b.given F[? <: U]\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Ident", "TypeBoundsTree", "Ident", "Thicket"),
          "1d787674f1aa62fae2c576a56d9ff10853d4b8d4f62a8dee444290909e1055b3"
        ),
        (
          "import a.b.given F[? >: L]\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Ident", "TypeBoundsTree", "Ident", "Thicket"),
          "0b27c0de49784407b6fc93540ae665b139c65dafc8ed91c77dfdf0bc11ab9c97"
        ),
        (
          "import a.b.given F[? >: L <: U]\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Ident", "TypeBoundsTree", "Ident", "Ident", "Thicket"),
          "6a09584ee94391fee28545e08ed2a88895af9e0178c4cb744aa173870733bd53"
        ),
        (
          "import a.b.given A | B & C <:< D\n",
          "InfixOp",
          Vector("InfixOp", "Ident", "Ident", "InfixOp", "Ident", "Ident", "InfixOp", "Ident", "Ident", "Ident"),
          "15a3ce77875b601800d9c46d3c993cc02cc625a59f50db72c8b9c4e0b9723ddd"
        ),
        (
          "import a.b.given A | B | C\n",
          "InfixOp",
          Vector("InfixOp", "InfixOp", "Ident", "Ident", "Ident", "Ident", "Ident"),
          "f5866204df57cf31aaabdadd06ad49bc4d50189e27d6f3bc6d1a056fd2f51f14"
        ),
        (
          "export a.b.given scala.math.Ordering.Int\n",
          "Select",
          Vector("Select", "Select", "Select", "Ident"),
          "dcd7f340cdc898d4d02442a1dae6bc3c6a2ae54faab5633372ae877a36a94345"
        ),
        (
          "export a.b.{given scala.math.Ordering[Int]}\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Select", "Select", "Ident", "Ident"),
          "1c7bb8ebd7a1bf5090af181d2980fb6ee1af1305a8dcfd462289679f83237a7b"
        ),
        (
          "export a.b.given F[?]\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Ident", "TypeBoundsTree", "Thicket"),
          "cb98be690b3071d0b2d436751e2b71c83032e0c96cbc59ea494dd49ef010b268"
        ),
        (
          "export a.b.{given F[? <: U]}\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Ident", "TypeBoundsTree", "Ident", "Thicket"),
          "f8876d8746ffa807298e80aba00b18cf8c30b4ee4c070d025396a411506a7907"
        ),
        (
          "export a.b.given F[? >: L]\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Ident", "TypeBoundsTree", "Ident", "Thicket"),
          "b10453241c54c134d8fa1f2d53662b6025740f297d7055661b7a8af7452c6619"
        ),
        (
          "export a.b.{given F[? >: L <: U]}\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Ident", "TypeBoundsTree", "Ident", "Ident", "Thicket"),
          "a7aebdeee6e71b90df164c7415a6b40eae112e336a57d71e390fa33f236ce84d"
        ),
        (
          "export a.b.given A | B & C <:< D\n",
          "InfixOp",
          Vector("InfixOp", "Ident", "Ident", "InfixOp", "Ident", "Ident", "InfixOp", "Ident", "Ident", "Ident"),
          "b3883dda132dd5c2a82b61f2d171dca3bd4ed8361e48f184fd52815c5e7b94d6"
        ),
        (
          "export a.b.given A | B | C\n",
          "InfixOp",
          Vector("InfixOp", "InfixOp", "Ident", "Ident", "Ident", "Ident", "Ident"),
          "da041de9f3fae7be61ccab1bfdb2e9e1b32100bae1bf2805640a009c97684763"
        )
      )
      def node(snapshot: ParserSyntaxSnapshot, id: Long): ParserSyntaxNode                 = snapshot.nodes.find(_.id == id).get
      def references(value: ParserFieldValue): Vector[Long]                                = value match
        case ParserFieldValue.Node(id)           => Vector(id)
        case ParserFieldValue.Optional(value)    => value.toVector.flatMap(references)
        case ParserFieldValue.Repeated(values)   => values.flatMap(references)
        case ParserFieldValue.Product(_, fields) => fields.flatMap(field => references(field.value))
        case _: ParserFieldValue.Positioned | _: ParserFieldValue.Name | _: ParserFieldValue.GeneratedName |
            _: ParserFieldValue.Scalar | _: ParserFieldValue.Unsupported =>
          Vector.empty
      def boundId(snapshot: ParserSyntaxSnapshot): Long                                    =
        snapshot.nodes
          .find(_.production == "ImportSelector")
          .get
          .fields
          .collectFirst { case ParserSyntaxField("bound", ParserFieldValue.Node(id), _) => id }
          .get
      def subtreeProductions(snapshot: ParserSyntaxSnapshot, rootId: Long): Vector[String] =
        val pending = java.util.ArrayDeque[Long]()
        pending.addFirst(rootId)
        val seen    = collection.mutable.Set.empty[Long]
        val result  = Vector.newBuilder[String]
        while !pending.isEmpty do
          val id = pending.removeFirst()
          if !seen(id) then
            seen += id
            val current  = node(snapshot, id)
            result += current.production
            val children = current.fields.iterator.flatMap(field => references(field.value)).toVector
            children.reverseIterator.foreach(pending.addFirst)
        result.result()

      val snapshots = forms.zipWithIndex.map: (form, index) =>
        val (source, rootProduction, expectedSubtree, fingerprint) = form
        val snapshot                                               = parse(bridge, source, s"file:///BoundedGivenType$index.scala")
        assertEquals(source, snapshot.sourceText)
        assertTrue(snapshot.diagnostics.isEmpty)
        assertEquals(fingerprint, ParserSyntaxSnapshot.evidenceFingerprint(snapshot))
        val bound                                                  = node(snapshot, boundId(snapshot))
        assertEquals(rootProduction, bound.production)
        assertEquals(
          expectedSubtree.groupMapReduce(identity)(_ => 1)(_ + _),
          subtreeProductions(snapshot, bound.id).groupMapReduce(identity)(_ => 1)(_ + _)
        )
        val typeStart                                              = source.indexOf("given") + "given ".length
        val typeEnd                                                = source.indexOf('}') match
          case -1    => source.length - 1
          case value => value
        assertEquals(
          PcSourceRange(typeStart, typeEnd),
          bound.position.asInstanceOf[ParserNodePosition.Positioned].range
        )
        snapshot.nodes
          .filter(_.production == "Select")
          .foreach(node => assertEquals(Vector("qualifier", "name"), node.fields.map(_.name)))
        snapshot.nodes
          .filter(_.production == "AppliedTypeTree")
          .foreach(node => assertEquals(Vector("tpt", "args"), node.fields.map(_.name)))
        snapshot.nodes
          .filter(_.production == "TypeBoundsTree")
          .foreach(bounds =>
            assertEquals(Vector("lo", "hi", "alias"), bounds.fields.map(_.name))
            def child(field: String): ParserSyntaxNode =
              val id =
                bounds.fields.collectFirst { case ParserSyntaxField(`field`, ParserFieldValue.Node(id), _) => id }.get
              node(snapshot, id)
            val lower                                  = child("lo")
            val upper                                  = child("hi")
            assertEquals(if source.contains(">:") then "Ident" else "Thicket", lower.production)
            assertEquals(if source.contains("<:") then "Ident" else "Thicket", upper.production)
            assertEquals("Thicket", child("alias").production)
            if source.contains(">:") then
              val offset = source.indexOf("L")
              assertEquals(
                ParserNodePosition
                  .Positioned(PcSourceRange(offset, offset + 1), offset, ParserPositionProvenance.SourceDerived),
                lower.position
              )
            else assertEquals(ParserNodePosition.Absent, lower.position)
            if source.contains("<:") then
              val offset = source.indexOf("U")
              assertEquals(
                ParserNodePosition
                  .Positioned(PcSourceRange(offset, offset + 1), offset, ParserPositionProvenance.SourceDerived),
                upper.position
              )
            else assertEquals(ParserNodePosition.Absent, upper.position)
            assertEquals(ParserNodePosition.Absent, child("alias").position)
          )
        snapshot.nodes
          .filter(_.production == "InfixOp")
          .foreach(node => assertEquals(Vector("left", "op", "right"), node.fields.map(_.name)))
        val evidence                                               = ProvisionalSourceEvidencePlanner.plan(snapshot).toOption.get
        assertEquals(source, evidence.reconstruct(source))
        assertFalse(evidence.atoms.exists(atom => atom.start == atom.end))
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
          assertEquals(
            snapshot.sourceText,
            plan.physicalLeafOwnership
              .sortBy(leaf => (leaf.start, leaf.end))
              .map(leaf => snapshot.sourceText.substring(leaf.start, leaf.end))
              .mkString
          )
          assertFalse(plan.physicalLeafOwnership.exists(leaf => leaf.start == leaf.end))
          assertEquals(
            plan.physicalLeafOwnership.map(_.atomId).distinct,
            plan.physicalLeafOwnership.map(_.atomId)
          )
          assertEquals(evidence.structural.map(_.id), plan.structuralEvidenceOwnership.map(_.eventId))
          assertEquals(
            plan.structuralEvidenceOwnership.map(_.eventId).distinct,
            plan.structuralEvidenceOwnership.map(_.eventId)
          )
          val byId     = plan.composites.map(value => value.instance -> value).toMap
          plan.composites.foreach: parent =>
            parent.children.foreach: child =>
              val range = byId(child.child).range
              assertTrue(parent.range.startOffset <= range.startOffset)
              assertTrue(range.endOffset <= parent.range.endOffset)
          if node(snapshot, boundId(snapshot)).production == "Select"
          then assertTrue(plan.composites.exists(_.productionId == "import-selector-given-bound-qualified-type"))
          if snapshot.nodes.exists(_.production == "TypeBoundsTree") then
            assertTrue(plan.composites.exists(_.productionId == "import-selector-given-bound-wildcard-type"))
          if snapshot.nodes.exists(_.production == "InfixOp") then
            assertEquals(
              snapshot.nodes.count(_.production == "InfixOp"),
              plan.composites.count(_.productionId == "import-selector-given-bound-infix-type")
            )

      val bareWildcard = Vector("import a.b.given ?\n", "export a.b.given ?\n").zipWithIndex.map: (source, index) =>
        parse(bridge, source, s"file:///BareGivenWildcard$index.scala")
      bareWildcard.foreach: snapshot =>
        assertTrue(snapshot.diagnostics.exists(_.severity == ParserDiagnosticSeverity.Error))
        val runtime  = CompilerRuntimeInventory.from(snapshot).toOption.get
        val selected = runtime.shapes
          .filter(_.prefix == "TypeBoundsTree")
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
        assertTrue(selected.isEmpty)
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
          "7e6fcfdbdf5611129e4d792e50c8baca5af4ccd8c72c6a82181681671e319d0f"
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
        Scala3PsiProductionCatalog.Reviewed.productions
          .filter(production => production.id == "file-package" || production.id.startsWith("package-stable"))
          .map: production =>
            if production.id != "file-package" then production
            else
              production.copy(pattern =
                production.pattern
                  .copy(occurrences = production.pattern.occurrences.filter(_.context == ContextPattern.Root))
              )
        ,
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
      assertEquals("root-remainder", trailing.terminalId)
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
      assertEquals("0881955fc251cd91ec67d9377fe894ce383c7f2d13054177fd4dcb4a611efaa1", aggregate.fingerprint)
      assertEquals("878bfefb423fd893f2a0fae757394766452d75950757ff05b24ccae6c8e5cd0a", installedSurfaces.fingerprint)
      val catalogErrors                                                                            = Scala3PsiProductionCatalogValidator.validate(
        Scala3PsiProductionCatalog.Reviewed,
        aggregate,
        surfaces
      )
      val catalogProducts                                                                          = Scala3PsiProductionCatalog.Reviewed.productions.collect:
        case production if production.pattern.kind == InventoryKind.Product => production.pattern.prefix
      val expectedUncovered                                                                        = aggregate.productions
        .filter(row => row.kind != InventoryKind.Product || catalogProducts.contains(row.prefix))
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
      val expectedAmbiguous                                                                        = aggregate.productions
        .filter(row => row.kind != InventoryKind.Product || catalogProducts.contains(row.prefix))
        .flatMap(row =>
          row.occurrences.flatMap: occurrence =>
            val selected = CatalogShapeMatcher.selectAggregated(Scala3PsiProductionCatalog.Reviewed, row, occurrence)
            Option.when(selected.size > 1)(
              CatalogValidationError.AmbiguousCompilerShape(
                row.kind,
                row.prefix,
                occurrence.context,
                occurrence.sourceClassification,
                selected.map(_.id).sorted
              )
            )
        )
        .toSet
      val actualAmbiguous                                                                          = catalogErrors.collect:
        case error: CatalogValidationError.AmbiguousCompilerShape => error
      assertEquals(expectedAmbiguous, actualAmbiguous.toSet)
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
            GrammarRoleId.PackageClause
          )
        )
      )
      assertFalse(
        catalogErrors.toString,
        catalogErrors.exists(error =>
          !error.isInstanceOf[CatalogValidationError.UncoveredCompilerShape] &&
            !error.isInstanceOf[CatalogValidationError.AmbiguousCompilerShape] &&
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

  private val ScalaVersion = "3.7.4"
