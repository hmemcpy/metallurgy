package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.psiproducer.{
  AggregatedCompilerProductionInventory,
  CatalogValidationError,
  CompilerRuntimeInventory,
  PreparedProductionCatalog,
  ProvisionalSourceEvidencePlanner,
  Scala3PsiProductionCatalog,
  ScalaPsiSurfaceInventory,
  StructuralSourceEvidence
}
import com.intellij.openapi.diagnostic.ControlFlowException
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.Test

import java.nio.file.Path
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

final class Scala3DefinitionParserInventoryTest:

  @Test
  def connectedDefinitionFamilyHasExactProductsFieldsPositionsAndOwnership(): Unit =
    val bridge = openBridge()
    try
      val first  = parse(bridge, DefinitionSource, "file:///DefinitionFamilyInventory.scala")
      val second = parse(bridge, DefinitionSource, "file:///DefinitionFamilyInventory.scala")

      assertEquals(first, second)
      assertEquals(
        ParserSyntaxSnapshot.evidenceFingerprint(first),
        ParserSyntaxSnapshot.evidenceFingerprint(second)
      )
      assertEquals(
        "cce29f4a9908b20bdd5dab833d75087e5af6de7e4771231cf859bc29357afd13",
        ParserSyntaxSnapshot.evidenceFingerprint(first)
      )
      assertEquals(DefinitionSource, first.sourceText)
      assertEquals(ParserSyntaxSnapshot.digest(DefinitionSource), first.sourceDigest)
      assertTrue(first.diagnostics.toString, first.diagnostics.isEmpty)
      assertEquals(
        Vector(
          ParserComment(
            PcSourceRange(0, "/** Definition family. */".length),
            "/** Definition family. */",
            ParserCommentKind.Doc
          )
        ),
        first.comments
      )
      assertExactDefinitionFields(first)
      assertExactDefinitionPositions(first)
      assertExactDefinitionOwnership(first)
      assertNoUnsupportedValues(first)
      assertNeutral(first)

      val evidence = ProvisionalSourceEvidencePlanner
        .plan(first)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
      assertEquals(DefinitionSource, evidence.reconstruct(DefinitionSource))
      assertEquals(ParserSyntaxSnapshot.evidenceFingerprint(first), evidence.parserEvidenceFingerprint)
      assertTrue(evidence.structural.exists:
        case StructuralSourceEvidence(
              _,
              _,
              ParserNodePosition.Positioned(range, _, ParserPositionProvenance.Synthetic)
            ) =>
          range.startOffset == range.endOffset
        case _ => false
      )
      CompilerRuntimeInventory
        .from(first)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), _ => ())
    finally bridge.close()

  @Test
  def minimizedConnectedDefinitionsHaveAStableExactFingerprint(): Unit =
    val bridge = openBridge()
    try
      val first          = parse(bridge, MinimizedSource, "file:///MinimizedDefinitionInventory.scala")
      val second         = parse(bridge, MinimizedSource, "file:///MinimizedDefinitionInventory.scala")
      val expectedFields = Map(
        "TypeDef"        -> Vector("name", "rhs", "mods"),
        "ModuleDef"      -> Vector("name", "impl", "mods"),
        "Template"       -> Vector("constr", "preParentsOrDerived", "self", "preBody", "mods"),
        "DefDef"         -> Vector("name", "paramss", "tpt", "preRhs", "mods"),
        "ValDef"         -> Vector("name", "tpt", "preRhs", "mods"),
        "PatDef"         -> Vector("mods", "pats", "tpt", "rhs"),
        "ExtMethods"     -> Vector("paramss", "methods"),
        "TypeBoundsTree" -> Vector("lo", "hi", "alias")
      )

      assertEquals(first, second)
      assertTrue(first.diagnostics.toString, first.diagnostics.isEmpty)
      assertEquals(
        "d13e06273e3e0c89f1e0b6734cb608a66e6c19aa9fc9b4c1c14a371e1a683c2f",
        ParserSyntaxSnapshot.evidenceFingerprint(first)
      )
      expectedFields.foreach: (production, fields) =>
        val observed = first.nodes.filter(_.production == production)
        assertTrue(s"$production is absent", observed.nonEmpty)
        assertEquals(production, Set(fields), observed.map(_.fields.map(_.name)).toSet)
      assertEquals(
        MinimizedSource,
        ProvisionalSourceEvidencePlanner
          .plan(first)
          .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
          .reconstruct(MinimizedSource)
      )
      assertNoUnsupportedValues(first)
      assertNeutral(first)
      CompilerRuntimeInventory
        .from(first)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), _ => ())
    finally bridge.close()

  @Test
  def definitionModifiersAndSyntacticModifierProductsHaveAnExactInventory(): Unit =
    val bridge = openBridge()
    try
      val snapshot  = parse(bridge, ModifierSource, "file:///DefinitionModifierInventory.scala")
      val modifiers = nestedProducts(snapshot)
        .collect { case ("Modifiers", fields) => fields }
      val syntactic = snapshot.positioned

      assertTrue(snapshot.diagnostics.toString, snapshot.diagnostics.isEmpty)
      assertTrue(modifiers.nonEmpty)
      assertTrue(modifiers.forall(_.map(_.name) == Vector("flags", "privateWithin", "annotations", "mods")))
      assertTrue(modifiers.forall(_.head.value.isInstanceOf[ParserFieldValue.Scalar]))
      assertTrue(
        modifiers.exists(
          _.exists(field => field.name == "privateWithin" && field.value == ParserFieldValue.Name("modifierinventory"))
        )
      )
      assertTrue(
        modifiers.exists(_.exists {
          case ParserSyntaxField("annotations", ParserFieldValue.Repeated(values), _) => values.nonEmpty
          case _                                                                      => false
        })
      )
      assertEquals(
        Set(
          "Abstract",
          "Final",
          "Given",
          "Implicit",
          "Infix",
          "Inline",
          "Lazy",
          "Opaque",
          "Open",
          "Override",
          "Private",
          "Protected",
          "Sealed",
          "Transparent",
          "Var"
        ),
        syntactic.map(_.production).toSet
      )
      assertTrue(syntactic.forall(_.fields.isEmpty))
      assertTrue(syntactic.forall(_.occurrences.nonEmpty))
      assertTrue(
        syntactic
          .flatMap(_.occurrences)
          .forall: occurrence =>
            occurrence.fieldPath.contains(ParserFieldPathSegment.NestedProductBoundary("Modifiers")) &&
              occurrence.fieldPath.contains(ParserFieldPathSegment.NamedField("mods"))
      )
      syntactic.foreach: value =>
        value.position match
          case ParserNodePosition.Positioned(range, _, _) =>
            val text = ModifierSource.substring(range.startOffset, range.endOffset)
            assertTrue(s"${value.production}: '$text'", text.nonEmpty)
          case ParserNodePosition.Absent                  =>
            throw new AssertionError(s"${value.production} modifier has no source position")
      assertNoUnsupportedValues(snapshot)
      assertNeutral(snapshot)
      val evidence = ProvisionalSourceEvidencePlanner
        .plan(snapshot)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
      assertEquals(ModifierSource, evidence.reconstruct(ModifierSource))
    finally bridge.close()

  @Test
  def definitionEvidenceRemainsOutsideThePreparedProductionCatalog(): Unit =
    val bridge = openBridge()
    try
      val snapshot  = parse(bridge, DefinitionSource, "file:///DefinitionCatalogQuarantine.scala")
      val runtime   = CompilerRuntimeInventory
        .from(snapshot)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
      val aggregate = AggregatedCompilerProductionInventory
        .aggregate(Vector(runtime))
        .fold(failure => throw new AssertionError(failure.toString), identity)
      val surfaces  = ScalaPsiSurfaceInventory
        .installed()
        .fold(message => throw new AssertionError(message), identity)
      val errors    = PreparedProductionCatalog
        .prepareRuntimeSubset(Scala3PsiProductionCatalog.Reviewed, runtime, aggregate, surfaces)
        .swap
        .getOrElse(throw new AssertionError("definition inventory unexpectedly prepared a production catalog"))
      val uncovered = errors.collect:
        case error: CatalogValidationError.UncoveredCompilerShape => error.prefix

      Set("TypeDef", "ModuleDef", "Template", "DefDef", "ValDef", "PatDef", "ExtMethods").foreach: production =>
        assertTrue(s"$production was not rejected as uncovered: $errors", uncovered.contains(production))
      assertTrue(snapshot.runtimeSupplements.nonEmpty)
      snapshot.runtimeSupplements.foreach: supplement =>
        val runtimeShape = runtime.shapes.find(_.id == supplement.ownerNodeId).getOrElse {
          throw new AssertionError(s"missing runtime shape for supplemental owner ${supplement.ownerNodeId}")
        }
        assertFalse(runtimeShape.patternFields.exists(_.name.endsWith("Count")))
      assertFalse(
        aggregate.productions.exists(_.fields.exists(_.name.endsWith("Count")))
      )
    finally bridge.close()

  @Test
  def repeatedAndDeepDefinitionsRemainDeterministicWithoutTraversalCaps(): Unit =
    val bridge = openBridge()
    try
      val repeatedSource = repeatedDefinitions(1024)
      val repeatedFirst  = parse(bridge, repeatedSource, "file:///RepeatedDefinitionInventory.scala")
      val repeatedSecond = parse(bridge, repeatedSource, "file:///RepeatedDefinitionInventory.scala")
      val repeatedValues = repeatedFirst.nodes.filter(node =>
        node.production == "ValDef" && node.fields.exists {
          case ParserSyntaxField("name", ParserFieldValue.Name(name), _) => name.startsWith("value")
          case _                                                         => false
        }
      )
      val module         = namedNode(repeatedFirst, "ModuleDef", "RepeatedDefinitions")
      val templateId     = nodeField(module, "impl")

      assertEquals(repeatedFirst, repeatedSecond)
      assertEquals(1024, repeatedValues.size)
      assertEquals(
        (0 until 1024).map(index =>
          Vector(
            ParserNodeOccurrence(
              templateId,
              Vector(ParserFieldPathSegment.NamedField("preBody"), ParserFieldPathSegment.RepeatedIndex(index))
            )
          )
        ),
        repeatedValues.map(_.occurrences)
      )
      assertEquals(
        repeatedSource,
        ProvisionalSourceEvidencePlanner
          .plan(repeatedFirst)
          .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
          .reconstruct(repeatedSource)
      )
      CompilerRuntimeInventory
        .from(repeatedFirst)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), _ => ())

      val deepSource = nestedDefinitions(256)
      val deepFirst  = parse(bridge, deepSource, "file:///DeepDefinitionInventory.scala")
      val deepSecond = parse(bridge, deepSource, "file:///DeepDefinitionInventory.scala")
      assertEquals(deepFirst, deepSecond)
      assertEquals(256, deepFirst.nodes.count(_.production == "ModuleDef"))
      assertEquals(
        deepSource,
        ProvisionalSourceEvidencePlanner
          .plan(deepFirst)
          .fold(failures => throw new AssertionError(failures.mkString("\n")), identity)
          .reconstruct(deepSource)
      )
      CompilerRuntimeInventory
        .from(deepFirst)
        .fold(failures => throw new AssertionError(failures.mkString("\n")), _ => ())
      assertNoUnsupportedValues(repeatedFirst)
      assertNoUnsupportedValues(deepFirst)
      assertNeutral(repeatedFirst)
      assertNeutral(deepFirst)
    finally bridge.close()

  @Test
  def cancellationDuringDefinitionExportLeavesTheBridgeReusable(): Unit =
    val bridge       = openBridge()
    val cancellation = new CountingCancellation(256)
    try
      val canceled =
        try
          val _ = bridge.parse(
            request(repeatedDefinitions(1024), "file:///CanceledDefinitionInventory.scala", cancellation)
          )
          false
        catch case _: TestControlFlowException => true

      assertTrue(canceled)
      assertTrue(cancellation.checks.get() > 256)
      assertEquals(Scala3ParserLoaderState.Open, bridge.loaderState)
      val recovered = parse(bridge, DefinitionSource, "file:///RecoveredDefinitionInventory.scala")
      assertTrue(recovered.diagnostics.isEmpty)
      assertTrue(recovered.runtimeSupplements.nonEmpty)
      assertEquals(Scala3ParserLoaderState.Open, bridge.loaderState)
    finally bridge.close()

  private def assertExactDefinitionFields(snapshot: ParserSyntaxSnapshot): Unit =
    val expected = Map(
      "TypeDef"        -> Vector("name", "rhs", "mods"),
      "ModuleDef"      -> Vector("name", "impl", "mods"),
      "Template"       -> Vector("constr", "preParentsOrDerived", "self", "preBody", "mods"),
      "DefDef"         -> Vector("name", "paramss", "tpt", "preRhs", "mods"),
      "ValDef"         -> Vector("name", "tpt", "preRhs", "mods"),
      "PatDef"         -> Vector("mods", "pats", "tpt", "rhs"),
      "ExtMethods"     -> Vector("paramss", "methods"),
      "TypeBoundsTree" -> Vector("lo", "hi", "alias")
    )
    expected.foreach: (production, fields) =>
      val observed = snapshot.nodes.filter(_.production == production)
      assertTrue(s"$production is absent", observed.nonEmpty)
      assertEquals(production, Set(fields), observed.map(_.fields.map(_.name)).toSet)

    assertFalse(snapshot.nodes.exists(_.production == "DerivingTemplate"))
    assertEquals(
      Vector(1, 1),
      snapshot.runtimeSupplements
        .flatMap(_.fields)
        .collect:
          case ParserSyntaxField("derivedCount", ParserFieldValue.Scalar(ParserScalar.Integer(value)), _) => value
    )
    assertTrue(
      snapshot.runtimeSupplements.forall(supplement =>
        snapshot.nodes.find(_.id == supplement.ownerNodeId).exists(_.production == "Template")
      )
    )
    assertTrue(
      snapshot.nodes.exists(node => node.production == "Thicket" && node.position == ParserNodePosition.Absent)
    )
    assertFalse(snapshot.nodes.exists(_.production == "EmptyTree"))
    assertFalse(snapshot.nodes.exists(_.production == "EmptyValDef"))
    val absentSelf  = snapshot.nodes
      .find(node => node.production == "ValDef" && node.position == ParserNodePosition.Absent)
      .getOrElse(throw new AssertionError("absent self ValDef is missing"))
    assertTrue(absentSelf.occurrences.size >= 4)
    val sharedEmpty = snapshot.nodes
      .find(node => node.production == "Thicket" && node.position == ParserNodePosition.Absent)
      .getOrElse(throw new AssertionError("shared empty tree is missing"))
    assertTrue(sharedEmpty.occurrences.size >= 20)
    assertTrue(
      snapshot.nodes.exists(node =>
        node.production == "DefDef" && node.fields.exists(_.value == ParserFieldValue.Name("<init>"))
      )
    )
    assertTrue(
      snapshot.nodes.exists(node =>
        node.position match
          case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.Synthetic) =>
            range.startOffset == range.endOffset && node.fields.exists(
              _.value.isInstanceOf[ParserFieldValue.GeneratedName]
            )
          case _                                                                           => false
      )
    )

  private def assertExactDefinitionPositions(snapshot: ParserSyntaxSnapshot): Unit =
    val definition = namedNode(snapshot, "TypeDef", "DefinitionProbe")
    assertEquals(
      ParserNodePosition.Positioned(
        PcSourceRange(
          DefinitionSource.indexOf("@deprecated"),
          DefinitionSource.indexOf("end DefinitionProbe") + "end DefinitionProbe".length
        ),
        DefinitionSource.indexOf("DefinitionProbe"),
        ParserPositionProvenance.SourceDerived
      ),
      definition.position
    )
    val template   = snapshot.nodes.find(_.id == nodeField(definition, "rhs")).get
    template.position match
      case ParserNodePosition.Positioned(range, point, ParserPositionProvenance.Synthetic) =>
        assertEquals(DefinitionSource.indexOf("DefinitionProbe") + "DefinitionProbe".length, range.startOffset)
        assertEquals(range.startOffset, point)
        assertEquals(DefinitionSource.indexOf("\nend DefinitionProbe"), range.endOffset)
      case other                                                                           =>
        throw new AssertionError(s"unexpected DefinitionProbe template position $other")
    val pattern    = snapshot.nodes.find(_.production == "PatDef").get
    assertEquals(
      DefinitionSource.indexOf("val (left, right)"),
      pattern.position.asInstanceOf[ParserNodePosition.Positioned].range.startOffset
    )
    val extension  = snapshot.nodes.find(_.production == "ExtMethods").get
    assertEquals(
      PcSourceRange(
        DefinitionSource.indexOf("extension [A]"),
        DefinitionSource.indexOf("\n\n  given listOrdering")
      ),
      extension.position.asInstanceOf[ParserNodePosition.Positioned].range
    )
    val bounds     = namedNode(snapshot, "TypeDef", "Abstract")
    val boundsNode = snapshot.nodes.find(_.id == nodeField(bounds, "rhs")).get
    assertEquals("TypeBoundsTree", boundsNode.production)
    assertEquals(
      ParserPositionProvenance.SourceDerived,
      boundsNode.position.asInstanceOf[ParserNodePosition.Positioned].provenance
    )
    snapshot.nodes.foreach:
      case ParserSyntaxNode(_, _, _, ParserNodePosition.Absent, _)                      => ()
      case ParserSyntaxNode(_, _, _, ParserNodePosition.Positioned(range, point, _), _) =>
        assertTrue(range.startOffset >= 0)
        assertTrue(range.endOffset <= DefinitionSource.length)
        assertTrue(point >= range.startOffset)
        assertTrue(point <= range.endOffset)

  private def assertExactDefinitionOwnership(snapshot: ParserSyntaxSnapshot): Unit =
    val root             = snapshot.nodes.find(_.id == snapshot.rootNodeId).get
    val definition       = namedNode(snapshot, "TypeDef", "DefinitionProbe")
    val template         = snapshot.nodes.find(_.id == nodeField(definition, "rhs")).get
    val constructor      = snapshot.nodes.find(_.id == nodeField(template, "constr")).get
    assertEquals(
      Vector(
        ParserNodeOccurrence(
          root.id,
          Vector(ParserFieldPathSegment.NamedField("stats"), ParserFieldPathSegment.RepeatedIndex(1))
        )
      ),
      definition.occurrences
    )
    assertEquals(
      Vector(ParserNodeOccurrence(definition.id, Vector(ParserFieldPathSegment.NamedField("rhs")))),
      template.occurrences
    )
    assertEquals(
      Vector(ParserNodeOccurrence(template.id, Vector(ParserFieldPathSegment.NamedField("constr")))),
      constructor.occurrences
    )
    val concrete         = namedNode(snapshot, "DefDef", "concrete")
    val clauses          = concrete.fields.collectFirst:
      case ParserSyntaxField("paramss", ParserFieldValue.Repeated(values), _) => values
    assertEquals(
      Vector(1, 1, 1),
      clauses.get.map {
        case ParserFieldValue.Repeated(values) => values.size
        case other                             => throw new AssertionError(s"unexpected parameter clause $other")
      }
    )
    val contextParameter = snapshot.nodes.find(node =>
      node.production == "ValDef" && node.occurrences.contains(
        ParserNodeOccurrence(
          concrete.id,
          Vector(
            ParserFieldPathSegment.NamedField("paramss"),
            ParserFieldPathSegment.RepeatedIndex(2),
            ParserFieldPathSegment.RepeatedIndex(0)
          )
        )
      )
    )
    assertTrue(contextParameter.nonEmpty)
    assertTrue(snapshot.endMarkers.forall(marker => snapshot.nodes.exists(_.id == marker.ownerNodeId)))
    assertEquals(Vector("InventoryParent", "Nested", "DefinitionProbe", "InventorySignal"), endMarkerNames(snapshot))
    assertTrue(snapshot.attachments.exists(_.keyKind == "DocComment"))
    assertTrue(
      snapshot.attachments.exists(attachment =>
        attachment.keyKind == "KindOfApply" && attachment.value == ParserAttachmentValue.Product("Using")
      )
    )
    assertTrue(snapshot.attachments.forall(attachment => snapshot.nodes.exists(_.id == attachment.ownerNodeId)))
    assertTrue(
      snapshot.attachments
        .groupBy(_.ownerNodeId)
        .values
        .forall(values => values.map(_.ordinal) == values.indices.toVector)
    )

  private def endMarkerNames(snapshot: ParserSyntaxSnapshot): Vector[String] =
    snapshot.endMarkers.map(marker =>
      DefinitionSource.substring(marker.designatorRange.startOffset, marker.designatorRange.endOffset)
    )

  private def namedNode(snapshot: ParserSyntaxSnapshot, production: String, name: String): ParserSyntaxNode =
    snapshot.nodes
      .find(node =>
        node.production == production && node.fields.exists {
          case ParserSyntaxField("name", ParserFieldValue.Name(`name`), _) => true
          case _                                                           => false
        }
      )
      .getOrElse(throw new AssertionError(s"$production $name is missing"))

  private def nodeField(node: ParserSyntaxNode, name: String): Long =
    node.fields.collectFirst:
      case ParserSyntaxField(`name`, ParserFieldValue.Node(id), _) => id
    match
      case Some(id) => id
      case None     => throw new AssertionError(s"${node.production}.$name is not a node")

  private def nestedProducts(snapshot: ParserSyntaxSnapshot): Vector[(String, Vector[ParserSyntaxField])] =
    val pending = collection.mutable.Stack.from(
      snapshot.nodes.flatMap(_.fields).map(_.value) ++ snapshot.positioned.flatMap(_.fields).map(_.value)
    )
    val values  = Vector.newBuilder[(String, Vector[ParserSyntaxField])]
    while pending.nonEmpty do
      pending.pop() match
        case ParserFieldValue.Optional(value)         => value.foreach(pending.push)
        case ParserFieldValue.Repeated(nested)        => nested.reverseIterator.foreach(pending.push)
        case ParserFieldValue.Product(prefix, fields) =>
          values += prefix -> fields
          fields.reverseIterator.foreach(field => pending.push(field.value))
        case _                                        => ()
    values.result()

  private def assertNoUnsupportedValues(snapshot: ParserSyntaxSnapshot): Unit =
    val pending = collection.mutable.Stack.from(
      snapshot.nodes.flatMap(_.fields).map(_.value) ++
        snapshot.positioned.flatMap(_.fields).map(_.value) ++
        snapshot.runtimeSupplements.flatMap(_.fields).map(_.value)
    )
    while pending.nonEmpty do
      pending.pop() match
        case ParserFieldValue.Unsupported(runtimeType) =>
          throw new AssertionError(s"unsupported parser field value $runtimeType")
        case ParserFieldValue.Optional(value)          => value.foreach(pending.push)
        case ParserFieldValue.Repeated(values)         => values.reverseIterator.foreach(pending.push)
        case ParserFieldValue.Product(_, fields)       => fields.reverseIterator.foreach(field => pending.push(field.value))
        case _                                         => ()

  private def assertNeutral(value: Any): Unit =
    val visited = new IdentityHashMap[AnyRef, java.lang.Boolean]()
    val pending = collection.mutable.Stack[Any](value)
    while pending.nonEmpty do
      pending.pop() match
        case null                                                                        => ()
        case reference: AnyRef if visited.put(reference, java.lang.Boolean.TRUE) != null => ()
        case product: Product                                                            =>
          assertFalse(
            s"exact compiler value escaped: ${product.getClass.getName}",
            product.getClass.getName.startsWith("dotty.tools.")
          )
          product.productIterator.foreach(pending.push)
        case iterable: Iterable[?]                                                       => iterable.foreach(pending.push)
        case reference: AnyRef                                                           =>
          assertFalse(
            s"exact compiler value escaped: ${reference.getClass.getName}",
            reference.getClass.getName.startsWith("dotty.tools.")
          )
        case _                                                                           => ()

  private def repeatedDefinitions(count: Int): String =
    (0 until count).map(index => s"  val value$index: Int = $index\n").mkString("object RepeatedDefinitions:\n", "", "")

  private def nestedDefinitions(depth: Int): String =
    val opening = (0 until depth).map(index => s"object Level$index {").mkString
    val closing = "}" * depth
    s"$opening val leaf: Int = 1 $closing"

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

  private def parse(bridge: Scala3ParserBridge, source: String, uri: String): ParserSyntaxSnapshot =
    bridge
      .parse(request(source, uri))
      .fold(error => throw new AssertionError(error.toString), identity)

  private def request(
      source: String,
      uri: String,
      cancellation: Scala3ParserCancellation = Scala3ParserCancellation.Never
  ): Scala3ParserRequest =
    Scala3ParserRequest(
      ParserSourceUri.from(uri).fold(message => throw new AssertionError(message), identity),
      source,
      Vector.empty,
      cancellation
    )

  private val ScalaVersion = "3.7.4"

  private val MinimizedSource =
    """final class Minimal[A](val value: A) derives CanEqual:
      |  type Alias = A
      |  val (left, right): (A, A) = (value, value)
      |  def id(input: A): A = input
      |  extension (other: A)
      |    def pair: (A, A) = (value, other)
      |
      |object Minimal
      |""".stripMargin

  private val DefinitionSource =
    """/** Definition family. */
      |trait InventoryParent[A]:
      |  def declared(value: A): A
      |end InventoryParent
      |
      |@deprecated("inventory", "1")
      |abstract class DefinitionProbe[A <: Matchable] protected (val seed: A)(using val ordering: Ordering[A])
      |    extends InventoryParent[A]
      |    derives CanEqual:
      |  self: DefinitionProbe[A] =>
      |
      |  type Abstract >: Nothing <: A
      |  type Alias = A
      |  opaque type Hidden >: Nothing <: A = A
      |
      |  protected val declaredValue: A
      |  lazy val concreteValue: A = seed
      |  val (left, right): (A, A) = (seed, seed)
      |
      |  def declared(value: A): A
      |  inline def concrete[B >: A](value: B)(using CanEqual[B, B]): B = value
      |
      |  object Nested:
      |    final case class Member[B](value: B)
      |  end Nested
      |end DefinitionProbe
      |
      |enum InventorySignal[+A] derives CanEqual:
      |  case Empty
      |  case Data(value: A)
      |end InventorySignal
      |
      |object InventoryExtensions {
      |  extension [A](value: A)
      |    def pair[B](other: B): (A, B) = (value, other)
      |
      |  given listOrdering[A](using ordering: Ordering[A]): Ordering[List[A]] =
      |    Ordering.by[List[A], Option[A]](_.headOption)
      |
      |  def take[A](using value: A): A = value
      |  def explicitOrdering[A](using ordering: Ordering[A]): Ordering[A] =
      |    take[Ordering[A]](using ordering)
      |}
      |""".stripMargin

  private val ModifierSource =
    """package modifierinventory
      |
      |class Mark extends scala.annotation.StaticAnnotation
      |
      |@Mark
      |sealed abstract class ModifierBase private[modifierinventory] (protected val input: Int):
      |  final val stable: Int = input
      |  private[modifierinventory] var changing: Int = input
      |  protected lazy val delayed: Int = changing
      |  implicit val implicitValue: Int = delayed
      |  def operation(value: Int): Int = value
      |end ModifierBase
      |
      |trait ModifierStack:
      |  def operation(value: Int): Int
      |
      |trait ModifierLayer extends ModifierStack:
      |  abstract override def operation(value: Int): Int = super.operation(value)
      |end ModifierLayer
      |
      |open class OpenModifier extends ModifierBase(0):
      |  transparent inline def transparentValue: Int = input
      |  infix def combine(other: Int): Int = input + other
      |end OpenModifier
      |
      |opaque type OpaqueValue = Int
      |
      |given OpaqueOrdering: Ordering[OpaqueValue] = Ordering.Int
      |""".stripMargin

  private final class CountingCancellation(limit: Int) extends Scala3ParserCancellation:
    val checks = new AtomicInteger(0)

    override def checkCanceled(): Unit =
      if checks.incrementAndGet() > limit then throw new TestControlFlowException

  private final class TestControlFlowException extends RuntimeException("cancelled"), ControlFlowException
