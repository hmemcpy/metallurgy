package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.psiproducer.{
  AggregatedCompilerProductionInventory,
  CatalogShapeMatcher,
  CompilerRuntimeInventory,
  Scala3PsiProductionCatalog,
  ScalaPsiSurfaceInventory,
  TerminalLeafTarget,
  ProvisionalSourceEvidencePlanner,
  PreparedProductionCatalog,
  WholeFileProductionPlanner
}
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.Test

private[pc] trait Scala3ImportExportParserTests extends Scala3ParserTestSupport:

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
        ),
        ("import a.b.given A\n", "Ident", Vector("Ident"), ""),
        ("import a.b.given p.A\n", "Select", Vector("Select", "Ident"), ""),
        ("import a.b.given T#A\n", "Select", Vector("Select", "Ident"), ""),
        (
          "import a.b.given x.type\n",
          "SingletonTypeTree",
          Vector("SingletonTypeTree", "Ident"),
          ""
        ),
        (
          "import a.b.given p.x.type\n",
          "SingletonTypeTree",
          Vector("SingletonTypeTree", "Select", "Ident"),
          ""
        ),
        (
          "import a.b.given 42\n",
          "SingletonTypeTree",
          Vector("SingletonTypeTree", "Literal"),
          ""
        ),
        (
          "import a.b.given \"literal\"\n",
          "SingletonTypeTree",
          Vector("SingletonTypeTree", "Literal"),
          ""
        ),
        (
          "import a.b.given true\n",
          "SingletonTypeTree",
          Vector("SingletonTypeTree", "Literal"),
          ""
        ),
        ("import a.b.given (A)\n", "Parens", Vector("Parens", "Ident"), "")
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
        if fingerprint.nonEmpty then assertEquals(fingerprint, ParserSyntaxSnapshot.evidenceFingerprint(snapshot))
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
          if node(snapshot, boundId(snapshot)).production == "Select" then
            val expected =
              if snapshot.sourceText.contains("#") then "type-atom-projection"
              else "import-selector-given-bound-qualified-type"
            assertTrue(plan.composites.exists(_.productionId == expected))
          if snapshot.nodes.exists(_.production == "SingletonTypeTree") then
            val expected =
              if snapshot.nodes.exists(_.production == "Literal") then "type-atom-literal"
              else if subtreeProductions(snapshot, boundId(snapshot)).contains("Select") then
                "type-atom-singleton-select"
              else "type-atom-singleton-ident"
            assertTrue(plan.composites.exists(_.productionId == expected))
          if node(snapshot, boundId(snapshot)).production == "Parens" then
            assertTrue(plan.composites.exists(_.productionId == "type-atom-parenthesized"))
          if snapshot.nodes.exists(_.production == "TypeBoundsTree") then
            assertTrue(plan.composites.exists(_.productionId == "import-selector-given-bound-wildcard-type"))
          if snapshot.nodes.exists(_.production == "InfixOp") then
            assertEquals(
              snapshot.nodes.count(_.production == "InfixOp"),
              plan.composites.count(_.productionId == "ordinary-infix-type")
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
          "9d5b2dcbd1df65af482655458dc11ccd1590008c73b120b9a2e59ee412796ef2"
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
