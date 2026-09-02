package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.psiproducer.{
  AggregatedCompilerProductionInventory,
  CatalogShapeMatcher,
  CompilerRuntimeInventory,
  ContextPattern,
  PhysicalLeafOwner,
  Scala3PsiProductionCatalog,
  ScalaPsiSurfaceInventory,
  StableRoleInventory,
  ProvisionalSourceEvidencePlanner,
  PreparedProductionCatalog,
  WholeFileProductionPlanner
}
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

private[pc] trait Scala3ParserInventoryTests extends Scala3ParserTestSupport:

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
      assertEquals("ba839c28489b21b03305f26c8cefefd1c5b501f9b223af2277f5013d3d7e04f2", aggregate.fingerprint)
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
      assertEquals("3abe98c62642a41249211ef2a4289457fa7a15752066d827c9c4d75cdd1d730f", aggregate.fingerprint)
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

  private val FamilySource =
    """package example.syntax
      |
      |import scala.collection.immutable.List
      |""".stripMargin

  private val PackageSource = "package example.syntax\n"
