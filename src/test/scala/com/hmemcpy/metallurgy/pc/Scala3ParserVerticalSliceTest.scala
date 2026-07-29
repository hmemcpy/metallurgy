package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.psiproducer.{
  AggregatedCompilerProductionInventory,
  CatalogValidationError,
  CatalogValuePattern,
  CompilerRuntimeInventory,
  FactStatus,
  InventoryFieldObservation,
  InventoryValueObservation,
  Scala3PsiProductionCatalog,
  Scala3PsiProductionCatalogValidator,
  Scala3PsiProductionCoverageReport,
  ScalaPsiSurfaceInventory,
  SurfaceClassification,
  ProvisionalSourceEvidencePlanner
}
import org.junit.Assert.{assertArrayEquals, assertEquals, assertFalse, assertTrue}
import org.junit.Test

import java.nio.charset.StandardCharsets
import java.nio.file.Path

final class Scala3ParserVerticalSliceTest:

  @Test
  def minimizedFilePackageFamilyHasAnExactInventory(): Unit =
    val bridge = openBridge()
    try
      val value     = parse(bridge, PackageSource, "file:///Scala3PackageFamily.scala")
      val inventory = CompilerRuntimeInventory
        .from(value)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
      val aggregate = AggregatedCompilerProductionInventory
        .aggregate(Vector(inventory))
        .fold(failure => throw new AssertionError(failure.toString), identity)
      val evidence  = ProvisionalSourceEvidencePlanner
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
      assertEquals("f6e351328d6b5371aef687227db3678479cb27aa08f8c7ec8fcff37220efd3e5", aggregate.fingerprint)
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
      assertEquals("b7a285e27db862ca0ed7471d648e96d6d505a90e975e973f367276f2255db37c", aggregate.fingerprint)
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
      val surfaces                                                                                 = ScalaPsiSurfaceInventory
        .installed()
        .fold(message => throw new AssertionError(message), identity)
      assertEquals("e71b759e4fa3e4749945b2882c83c0568fb3c8e3febe968b6b66b245d4e675d0", aggregate.fingerprint)
      assertEquals("878bfefb423fd893f2a0fae757394766452d75950757ff05b24ccae6c8e5cd0a", surfaces.fingerprint)
      val catalogErrors                                                                            = Scala3PsiProductionCatalogValidator.validate(
        Scala3PsiProductionCatalog.Reviewed,
        aggregate,
        surfaces
      )
      val expectedUncovered                                                                        = aggregate.productions
        .flatMap(row =>
          row.occurrences.collect:
            case occurrence if row.prefix != "Number" =>
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
      val expectedUnaccounted                                                                      = surfaces.rows
        .filter(row =>
          row.status == FactStatus.Available &&
            row.classification == SurfaceClassification.SyntaxContract &&
            row.id != "org/jetbrains/plugins/scala/lang/psi/impl/base/literals/ScIntegerLiteralImpl"
        )
        .map(row => CatalogValidationError.UnaccountedSyntaxSurface(row.id))
        .toSet
      val actualUnaccounted                                                                        = catalogErrors.collect:
        case error: CatalogValidationError.UnaccountedSyntaxSurface => error
      assertEquals(expectedUnaccounted, actualUnaccounted.toSet)
      assertFalse(
        catalogErrors.toString,
        catalogErrors.exists(error =>
          !error.isInstanceOf[CatalogValidationError.UncoveredCompilerShape] &&
            !error.isInstanceOf[CatalogValidationError.UnaccountedSyntaxSurface]
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
      assertTrue(report, report.contains("**shape-mapped:NativeCandidate:integer-literal-number**"))
      assertTrue(report, report.contains("### `Node.PackageDef`"))
      assertTrue(report, report.contains("**unmapped:SourceReachable**"))
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

  private val ScalaVersion = "3.7.4"
