package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.psiproducer.{
  AggregatedCompilerProductionInventory,
  CatalogValidationError,
  CatalogShapeMatcher,
  CatalogValuePattern,
  CompilerRuntimeInventory,
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
      val forms     = Vector(
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
      val snapshots = forms.zipWithIndex.map: (form, index) =>
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
      val comma     = parse(bridge, "import a.b, c.d\n", "file:///ImportFormComma.scala")
      val runtimes  = (snapshots :+ comma).map(snapshot => CompilerRuntimeInventory.from(snapshot).toOption.get)
      val aggregate = AggregatedCompilerProductionInventory.aggregate(runtimes).toOption.get
      val catalog   = Scala3PsiProductionCatalog(
        Scala3PsiProductionCatalog.Reviewed.productions.filter(production =>
          production.id.startsWith("file-import") || production.id.startsWith("import-")
        ),
        StableRoleInventory.Reviewed
      )
      val installed = ScalaPsiSurfaceInventory.installed().toOption.get
      val surfaces  = withImportTokenSurfaces(installed)
      val prepared  = PreparedProductionCatalog
        .prepare(catalog, aggregate, surfaces)
        .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
      snapshots.foreach: snapshot =>
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
      assertEquals(Vector("file-package-imports"), selected(0).map(_.id))
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
