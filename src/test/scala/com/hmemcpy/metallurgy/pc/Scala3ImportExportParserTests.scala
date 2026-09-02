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
        ("import a.b.c\n", Vector("c"), "bce83990ec5fcd3d55cced5ab9179e738637fac081477d19c96cf9cba56d6f2e"),
        ("import a.b.*\n", Vector("_"), "cb35de90f55dbf5b9afe7579fa99497bb2854a84d8b6c12c4adcba688107477a"),
        ("import a.b.{c}\n", Vector("c"), "2dcbbe614a563040bc6bf07ed5457d7e42e61ab32c70e53628c7174fa1dd13c1"),
        (
          "import a.b.{c /* as */ as d}\n",
          Vector("c", "d"),
          "45b209f830108de9c1d886ca380ebf6f5de71b977fd690d7926f86dc3834ae36"
        ),
        (
          "import a.b.{c /* => */ => d}\n",
          Vector("c", "d"),
          "ea7672681ce217d313426b703e0ce3b9c4659c9728840260d932e082072bf1be"
        ),
        ("import a.b.given\n", Vector(""), "4775403064bba41a2e3a204d71fb45d32fcc4d3c3642d3a7b4260c3d6adfacd2"),
        ("import a.b.given T\n", Vector("", "T"), "4a43c7d0f5205be8c1c70e5a886e254ed00f07898a99ad3ba439e424febfb07e"),
        (
          "import a.b.{given, given T, *}\n",
          Vector("", "", "T", "_"),
          "af0f5116d75b20cc3073ff24a880e15d940d434fc9a555b6b56e15515971a7f7"
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
          "ef95561de3a7b401c967324bac1dc4f0231c7f2f3c2605cea2f5eb08a087f427"
        ),
        (
          "import a.b.given scala.math.Ordering[Int]\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Select", "Select", "Ident", "Ident"),
          "2a53624839fb1e93f8305f298a3cfc95baea576ea17df0267f5cd505666e5cb1"
        ),
        (
          "import a.b.given F[?]\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Ident", "TypeBoundsTree", "Thicket"),
          "b79b43001196d82fbdd2930f57e34229434d36fcd073790c23d3b371ab2e2a7d"
        ),
        (
          "import a.b.given F[? <: U]\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Ident", "TypeBoundsTree", "Ident", "Thicket"),
          "30d4e1f74b91173a2be09c0ed17f91ecbdc721679c3e94fc6911d56b65671456"
        ),
        (
          "import a.b.given F[? >: L]\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Ident", "TypeBoundsTree", "Ident", "Thicket"),
          "61027ec27fa57de95ebaca68d303a1a6565edf670aac72c80bcb0203c63c73d9"
        ),
        (
          "import a.b.given F[? >: L <: U]\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Ident", "TypeBoundsTree", "Ident", "Ident", "Thicket"),
          "1fe5697a921af089364bc67e7bcef0c998bc3a7accae2513057d564fdf9e6ffd"
        ),
        (
          "import a.b.given A | B & C <:< D\n",
          "InfixOp",
          Vector("InfixOp", "Ident", "Ident", "InfixOp", "Ident", "Ident", "InfixOp", "Ident", "Ident", "Ident"),
          "68e160a3a108e0eec2fc6ba98a9b074b6ec09cbbf3f6fd5838814c54b91f7c66"
        ),
        (
          "import a.b.given A | B | C\n",
          "InfixOp",
          Vector("InfixOp", "InfixOp", "Ident", "Ident", "Ident", "Ident", "Ident"),
          "cfc98b311b773b339466f36213611dc6a8928d2859f2fcd4f2fde63fcb51410f"
        ),
        (
          "export a.b.given scala.math.Ordering.Int\n",
          "Select",
          Vector("Select", "Select", "Select", "Ident"),
          "f13b0c922e71192fd8dc7dae508cdc7b0ff00795a623a0b74a443759e6a3027a"
        ),
        (
          "export a.b.{given scala.math.Ordering[Int]}\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Select", "Select", "Ident", "Ident"),
          "850a7841caec73a63caa2eeadc1f630c997ad370a658ee91beb0a703f9bdf572"
        ),
        (
          "export a.b.given F[?]\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Ident", "TypeBoundsTree", "Thicket"),
          "514bb02eb8c3fe6d7f915e4386d6d110cc196047c48231709173c000f545ba48"
        ),
        (
          "export a.b.{given F[? <: U]}\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Ident", "TypeBoundsTree", "Ident", "Thicket"),
          "4b6af5fa1f1a17c081a0ab816a3ea19dce384bf1eba4fb8b7bd59f4dadd1ac8b"
        ),
        (
          "export a.b.given F[? >: L]\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Ident", "TypeBoundsTree", "Ident", "Thicket"),
          "ee6141a6742fc1b72044aec4f530badc6459288dd2a3bab9a1dda42a7de02ea1"
        ),
        (
          "export a.b.{given F[? >: L <: U]}\n",
          "AppliedTypeTree",
          Vector("AppliedTypeTree", "Ident", "TypeBoundsTree", "Ident", "Ident", "Thicket"),
          "79dcd26460b559eccc34cb5df6c0c1c946115232203471f65c92837afd82b3e1"
        ),
        (
          "export a.b.given A | B & C <:< D\n",
          "InfixOp",
          Vector("InfixOp", "Ident", "Ident", "InfixOp", "Ident", "Ident", "InfixOp", "Ident", "Ident", "Ident"),
          "70e3d6cbc8c1209519f263dc7a9212789a64a10abb8538fd139a5b4c587bda19"
        ),
        (
          "export a.b.given A | B | C\n",
          "InfixOp",
          Vector("InfixOp", "InfixOp", "Ident", "Ident", "Ident", "Ident", "Ident"),
          "ce7949ea7118054132dc9d30cfb94903433398b136cd35bc771dc6a915830f64"
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
          "add1e26a4d6b457713b12c57f967f566466c679b9eda8541b72d8b3141f8fdd2",
          "9e378c25ba18108e830e06062b7c1ae11093aa8ae90475b622c839d2345f23d6",
          "1e17e40a0c262843d9242d138a50c57242b6a9364926433770b9a731fb3943a1",
          "3bb84dac50a971f16c707c0f6550126c02ed5d26e269a47435b62098be047097",
          "07bc4e5d2d79ab380553a0d76a6bbf83cc401cc1ff72fc091202d53a3fbf59d3",
          "9909b6f3bcbf1328c6d9d7ed01f9ef6dfd86b6f3542ecd9bc3386d96329e33c6",
          "79b0428dbee0ff919ff7b865b742e88d98f15711c4896833d84d48c8cb176bf3",
          "db78703a769a0316d304b487a2d658f87bef6e57a8fcea4721b9d3f5d0ea5a2b",
          "8dfc456524545e88e52f25d663939054ffd0cd880fdb1a24fdb7a16001f48135",
          "8b8495fc61ad4027d17fb68507cf73a751e38814e085e307f48c9e8b591e82f2",
          "6285a024e752f49065472f87cec01f905ef5828ec00dc02e91dacdbd1e5bfa3a",
          "d18c9f4ebc78613e16d15caf7d552368a41c5ef3086c1aca792177e20a7637df"
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
