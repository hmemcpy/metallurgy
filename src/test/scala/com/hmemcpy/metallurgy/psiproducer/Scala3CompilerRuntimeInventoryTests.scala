package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import org.junit.Assert.*
import org.junit.Test

private[psiproducer] trait Scala3CompilerRuntimeInventoryTests extends Scala3PsiProductionCatalogTestSupport:
  @Test def evidenceFingerprintUsesUnambiguousSequenceEncoding(): Unit =
    assertNotEquals(
      ParserSyntaxSnapshot.evidenceFingerprint(snapshot("/one", 1, Vector("a, b"))),
      ParserSyntaxSnapshot.evidenceFingerprint(snapshot("/one", 1, Vector("a", "b")))
    )

  @Test def evidenceFingerprintPreservesUnpairedUtf16CodeUnits(): Unit =
    val first  = snapshot("/one", 1, Vector.empty).copy(sourceText = "\uD800", sourceLength = 1)
    val second = first.copy(sourceText = "\uD801")
    assertNotEquals(
      ParserSyntaxSnapshot.evidenceFingerprint(first),
      ParserSyntaxSnapshot.evidenceFingerprint(second)
    )

  @Test def evidenceFingerprintIncludesCanonicalDeclaredShapes(): Unit =
    val base     = snapshot("/one", 1, Vector.empty)
    val repeated = base.copy(nodes =
      base.nodes.updated(
        0,
        base.nodes.head.copy(fields =
          base.nodes.head.fields
            .map(_.copy(declaredShape = Some(ParserDeclaredShape.Repeated(ParserDeclaredShape.Node))))
        )
      )
    )
    val optional = repeated.copy(nodes =
      repeated.nodes.updated(
        0,
        repeated.nodes.head.copy(fields =
          repeated.nodes.head.fields
            .map(_.copy(declaredShape = Some(ParserDeclaredShape.Optional(ParserDeclaredShape.Node))))
        )
      )
    )
    assertNotEquals(ParserSyntaxSnapshot.evidenceFingerprint(base), ParserSyntaxSnapshot.evidenceFingerprint(repeated))
    assertNotEquals(
      ParserSyntaxSnapshot.evidenceFingerprint(repeated),
      ParserSyntaxSnapshot.evidenceFingerprint(optional)
    )

  @Test def runtimeIdentityIgnoresPathAndLoaderButRetainsOptionsAndArtifactOrder(): Unit =
    val first    = inventory(snapshot("/one", 1, Vector("a", "b")))
    val second   = inventory(snapshot("/two", 2, Vector("a", "b")))
    assertEquals(first.identity, second.identity)
    assertEquals(first.parserEvidenceFingerprint, second.parserEvidenceFingerprint)
    assertNotEquals(first.identity, inventory(snapshot("/one", 1, Vector("b", "a"))).identity)
    val reversed = snapshot("/one", 1, Vector("a", "b")).copy(compilerIdentity =
      snapshot("/one", 1, Vector("a", "b")).compilerIdentity
        .copy(artifacts = snapshot("/one", 1, Vector("a", "b")).compilerIdentity.artifacts.reverse)
    )
    assertNotEquals(first.identity, inventory(reversed).identity)

  @Test def malformedReferencesAreStructured(): Unit =
    val malformed = snapshot("/one", 1, Vector.empty).copy(nodes = Vector(node(1, ParserFieldValue.Node(99))))
    assertTrue(
      CompilerRuntimeInventory
        .from(malformed)
        .left
        .toOption
        .get
        .exists(_.isInstanceOf[InventoryFailure.MissingReference])
    )

  @Test def optionalAndRepeatedConcreteValuesRemainObservations(): Unit =
    val result = inventory(snapshot("/one", 1, Vector.empty))
    val root   = result.shapes.find(_.prefix == "Root").get
    assertTrue(root.patternFields.head.value.isInstanceOf[CatalogValuePattern.Repeated])
    assertTrue(root.observation.head.value.isInstanceOf[InventoryValueObservation.Repeated])

  @Test def aggregateIsCanonicalAndDeduplicatesEvidence(): Unit =
    val first    = inventory(snapshot("/one", 1, Vector.empty))
    val second   = first.copy(parserEvidenceFingerprint = "second")
    val forward  = aggregate(Vector(first, second, first))
    val backward = aggregate(Vector(second, first, first))
    assertArrayEquals(forward.canonicalBytes, backward.canonicalBytes)
    assertEquals(forward.fingerprint, backward.fingerprint)
    assertEquals(Vector(first.parserEvidenceFingerprint, "second").sorted, forward.sourceEvidenceFingerprints)
    assertTrue(forward.productions.forall(row => row.contexts.distinct == row.contexts))
    assertTrue(forward.productions.forall(row => row.sourceClassifications.distinct == row.sourceClassifications))

  @Test def aggregateRetainsRootAndParentContexts(): Unit =
    val base   = inventory(snapshot("/one", 1, Vector.empty))
    val root   = row(InventoryValueObservation.Name("x")).copy(contexts = Vector.empty)
    val parent = InventoryContext(InventoryKind.Node, "Owner", Vector(CatalogPathSegment.NamedField("value")))
    val nested = root.copy(
      contexts = Vector(parent),
      sourceClassification = SourceClassification.Synthetic
    )
    val result = aggregate(Vector(base.copy(shapes = Vector(root)), base.copy(shapes = Vector(nested))))
    assertEquals(
      Set(
        CompilerProductionContext(None, SourceClassification.SourceReachable),
        CompilerProductionContext(Some(parent), SourceClassification.Synthetic)
      ),
      result.productions.head.occurrences.toSet
    )

  @Test def inventoryLineageResolutionIsOrderedIterativeAndFailClosed(): Unit =
    val position                                                                        = ParserNodePosition.Positioned(PcSourceRange(0, 1), 0, ParserPositionProvenance.SourceDerived)
    val child                                                                           = Vector(ParserFieldPathSegment.NamedField("child"))
    def value(id: Long, production: String, occurrences: Vector[ParserNodeOccurrence])  =
      ParserSyntaxNode(id, production, Vector.empty, position, occurrences)
    val root                                                                            = value(1, "Root", Vector.empty)
    val left                                                                            = value(2, "Left", Vector(ParserNodeOccurrence(1, Vector(ParserFieldPathSegment.NamedField("left")))))
    val right                                                                           = value(
      3,
      "Right",
      Vector(ParserNodeOccurrence(1, Vector(ParserFieldPathSegment.NamedField("right"))))
    )
    val leaf                                                                            = value(4, "Leaf", Vector(ParserNodeOccurrence(2, child), ParserNodeOccurrence(3, child)))
    val nodes                                                                           = Vector(root, left, right, leaf).map(node => node.id -> node).toMap
    val expected                                                                        = Vector(
      Vector(
        InventoryAncestor(InventoryKind.Node, "Left", Vector(CatalogPathSegment.NamedField("child"))),
        InventoryAncestor(InventoryKind.Node, "Root", Vector(CatalogPathSegment.NamedField("left")))
      ),
      Vector(
        InventoryAncestor(InventoryKind.Node, "Right", Vector(CatalogPathSegment.NamedField("child"))),
        InventoryAncestor(InventoryKind.Node, "Root", Vector(CatalogPathSegment.NamedField("right")))
      )
    )
    def ancestries(candidate: ParserSyntaxNode, inventory: Map[Long, ParserSyntaxNode]) =
      InventoryContextLineage
        .resolver(inventory)
        .contexts(candidate, Vector(ParserFieldPathSegment.NamedField("value")))
        .map(_.ancestors)

    assertEquals(expected, ancestries(leaf, nodes))
    assertEquals(
      expected.reverse,
      ancestries(
        leaf.copy(occurrences = leaf.occurrences.reverse),
        nodes.updated(4, leaf.copy(occurrences = leaf.occurrences.reverse))
      )
    )

    val firstCycle  = value(5, "FirstCycle", Vector(ParserNodeOccurrence(6, child)))
    val secondCycle = value(6, "SecondCycle", Vector(ParserNodeOccurrence(5, child)))
    assertTrue(ancestries(firstCycle, Map(5L -> firstCycle, 6L -> secondCycle)).isEmpty)
    val missing     = value(7, "Missing", Vector(ParserNodeOccurrence(99, child)))
    assertTrue(ancestries(missing, Map(7L -> missing)).isEmpty)

  @Test def aggregateInfersOptionalAndRepeatedFromAllEvidence(): Unit =
    val identity                                      = inventory(snapshot("/one", 1, Vector.empty)).identity
    def value(observation: InventoryValueObservation) = CompilerRuntimeInventory(
      identity,
      s"evidence-${observation.hashCode}",
      Vector(row(observation)),
      Vector.empty
    )
    val optionalDeclaration                           = CatalogValuePattern.Optional(CatalogValuePattern.Name)
    val optional                                      = Vector(
      value(InventoryValueObservation.Optional(None))
        .copy(shapes = Vector(row(InventoryValueObservation.Optional(None), Some(optionalDeclaration)))),
      value(InventoryValueObservation.Optional(Some(InventoryValueObservation.Name("x"))))
    )
    val repeatedDeclaration                           = CatalogValuePattern.Repeated(CatalogValuePattern.Scalar("Integer"))
    val repeated                                      = Vector(
      value(InventoryValueObservation.Repeated(Vector.empty))
        .copy(shapes = Vector(row(InventoryValueObservation.Repeated(Vector.empty), Some(repeatedDeclaration)))),
      value(InventoryValueObservation.Repeated(Vector(InventoryValueObservation.Scalar(ParserScalar.Integer(1)))))
    )
    assertEquals(
      Set(
        CatalogValuePattern.EmptyOptional(CatalogValuePattern.Name),
        CatalogValuePattern.Optional(CatalogValuePattern.LowercaseName)
      ),
      aggregate(optional).productions.map(_.fields.head.value).toSet
    )
    assertArrayEquals(aggregate(optional).canonicalBytes, aggregate(optional.reverse).canonicalBytes)
    assertEquals(
      Set(
        CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Scalar("Integer")),
        CatalogValuePattern.Repeated(CatalogValuePattern.ExactScalar("Integer", "Integer(1)"))
      ),
      aggregate(repeated).productions.map(_.fields.head.value).toSet
    )
    assertArrayEquals(aggregate(repeated).canonicalBytes, aggregate(repeated.reverse).canonicalBytes)

  @Test def aggregateReportsUnresolvedConflictAndIdentityMismatch(): Unit =
    val base                                        = inventory(snapshot("/one", 1, Vector.empty))
    def withValue(value: InventoryValueObservation) =
      base.copy(parserEvidenceFingerprint = value.hashCode.toString, shapes = Vector(row(value)))
    assertTrue(
      AggregatedCompilerProductionInventory
        .aggregate(Vector(withValue(InventoryValueObservation.Optional(None))))
        .left
        .toOption
        .get
        .isInstanceOf[InventoryAggregationFailure.UnresolvedShape]
    )

  @Test def aggregateUsesOnlyAgreedDeclarationsForEmptyContainers(): Unit =
    val base                                                                                  = inventory(snapshot("/one", 1, Vector.empty))
    def withValue(value: InventoryValueObservation)                                           =
      base.copy(parserEvidenceFingerprint = value.hashCode.toString, shapes = Vector(row(value)))
    def withField(value: InventoryValueObservation, declaration: Option[CatalogValuePattern]) =
      base.copy(shapes = Vector(row(value, declaration)))
    val repeated                                                                              = CatalogValuePattern.Repeated(CatalogValuePattern.Node)
    val optional                                                                              = CatalogValuePattern.Optional(CatalogValuePattern.Name)
    assertEquals(
      CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node),
      aggregate(
        Vector(withField(InventoryValueObservation.Repeated(Vector.empty), Some(repeated)))
      ).productions.head.fields.head.value
    )
    assertEquals(
      CatalogValuePattern.EmptyOptional(CatalogValuePattern.Name),
      aggregate(
        Vector(withField(InventoryValueObservation.Optional(None), Some(optional)))
      ).productions.head.fields.head.value
    )
    assertTrue(
      AggregatedCompilerProductionInventory
        .aggregate(Vector(withField(InventoryValueObservation.Repeated(Vector.empty), None)))
        .left
        .toOption
        .get
        .isInstanceOf[InventoryAggregationFailure.UnresolvedShape]
    )
    assertEquals(
      2,
      aggregate(
        Vector(
          withField(InventoryValueObservation.Repeated(Vector.empty), Some(repeated)),
          withField(
            InventoryValueObservation.Repeated(Vector.empty),
            Some(CatalogValuePattern.Repeated(CatalogValuePattern.Name))
          )
        )
      ).productions.size
    )
    assertTrue(
      AggregatedCompilerProductionInventory
        .aggregate(
          Vector(
            withField(
              InventoryValueObservation.Repeated(Vector(InventoryValueObservation.Name("x"))),
              Some(repeated)
            )
          )
        )
        .left
        .toOption
        .get
        .isInstanceOf[InventoryAggregationFailure.IncompatibleShape]
    )
    assertTrue(
      AggregatedCompilerProductionInventory
        .aggregate(Vector(withValue(InventoryValueObservation.Repeated(Vector.empty))))
        .left
        .toOption
        .get
        .isInstanceOf[InventoryAggregationFailure.UnresolvedShape]
    )
    assertEquals(
      2,
      AggregatedCompilerProductionInventory
        .aggregate(
          Vector(
            withValue(InventoryValueObservation.Name("x")),
            withValue(InventoryValueObservation.Scalar(ParserScalar.Text("x")))
          )
        )
        .toOption
        .get
        .productions
        .size
    )
    val different                                                                             = inventory(snapshot("/one", 1, Vector("different")))
    assertTrue(
      AggregatedCompilerProductionInventory
        .aggregate(Vector(base, different))
        .left
        .toOption
        .get
        .isInstanceOf[InventoryAggregationFailure.RuntimeIdentityMismatch]
    )

  @Test def aggregateRejectsFieldAndNestedProductShapeConflicts(): Unit =
    val base      = inventory(snapshot("/one", 1, Vector.empty))
    val first     = base.copy(shapes = Vector(row(InventoryValueObservation.Name("x"))))
    val renamed   = first.copy(shapes =
      Vector(
        first.shapes.head
          .copy(observation = Vector(InventoryFieldObservation("renamed", InventoryValueObservation.Name("_"))))
      )
    )
    assertTrue(
      AggregatedCompilerProductionInventory
        .aggregate(Vector(first, renamed))
        .left
        .toOption
        .get
        .isInstanceOf[InventoryAggregationFailure.FieldSignatureConflict]
    )
    val product   = InventoryValueObservation.Product(
      "Pair",
      Vector(InventoryFieldObservation("left", InventoryValueObservation.Name("x")))
    )
    val different = InventoryValueObservation.Product(
      "Pair",
      Vector(InventoryFieldObservation("right", InventoryValueObservation.Name("x")))
    )
    assertEquals(
      2,
      aggregate(
        Vector(first.copy(shapes = Vector(row(product))), first.copy(shapes = Vector(row(different))))
      ).productions.size
    )

  @Test def aggregateCanonicalBytesCannotBeMutatedThroughTheResult(): Unit =
    val result = aggregate(Vector(inventory(snapshot("/one", 1, Vector.empty))))
    val bytes  = result.canonicalBytes
    bytes(0) = (bytes(0) + 1).toByte
    assertFalse(java.util.Arrays.equals(bytes, result.canonicalBytes))
    assertEquals(CanonicalByteEncoder.sha256Hex(result.canonicalBytes), result.fingerprint)

  @Test def inlineProductsRetainExactOccurrenceIdentityAndScaleWithoutRecursion(): Unit =
    val exactRuntime = inventory(annotationModifierSnapshot)
    val modifiers    = exactRuntime.products.filter(_.production == "Modifiers")
    assertEquals(1, modifiers.size)
    assertEquals(Vector("flags", "privateWithin", "annotations", "mods"), modifiers.head.fields.map(_.name))
    assertEquals(
      ParserNodePosition.Positioned(
        PcSourceRange(0, 27),
        0,
        ParserPositionProvenance.SourceDerived
      ),
      modifiers.head.position
    )
    assertEquals(
      Vector(ParserNodeOccurrence(1, Vector(ParserFieldPathSegment.NamedField("mods")))),
      modifiers.head.occurrences
    )

    val arity   = 10000
    val runtime = inventory(colocatedProductSnapshot(arity))
    val boxes   = runtime.products.filter(_.production == "Box")
    assertEquals(arity, boxes.size)
    assertEquals(arity, boxes.map(_.id).distinct.size)
    assertEquals(arity, boxes.flatMap(_.occurrences).distinct.size)
    assertTrue(
      boxes.forall(
        _.position == ParserNodePosition.Positioned(
          PcSourceRange(0, 0),
          0,
          ParserPositionProvenance.Synthetic
        )
      )
    )
    assertEquals(
      boxes.map(product => product.id -> product.occurrences),
      inventory(colocatedProductSnapshot(arity)).products
        .filter(_.production == "Box")
        .map(product => product.id -> product.occurrences)
    )

  @Test def inventoryRejectsMissingAndWrongOccurrences(): Unit =
    val value         = snapshot("/one", 1, Vector.empty)
    val missing       = value.copy(nodes = value.nodes.updated(1, value.nodes(1).copy(occurrences = Vector.empty)))
    assertTrue(failures(missing).exists(_.isInstanceOf[InventoryFailure.MissingOccurrence]))
    val wrong         = value.copy(nodes =
      value.nodes.updated(
        1,
        value
          .nodes(1)
          .copy(occurrences =
            Vector(
              ParserNodeOccurrence(1, Vector(ParserFieldPathSegment.NamedField("wrong")))
            )
          )
      )
    )
    val wrongFailures = failures(wrong)
    assertTrue(wrongFailures.exists(_.isInstanceOf[InventoryFailure.MissingOccurrence]))
    assertTrue(wrongFailures.exists(_.isInstanceOf[InventoryFailure.ExtraOccurrence]))

  @Test def inventoryRejectsUnreachableNodesAndPositionedValues(): Unit =
    val value                 = snapshot("/one", 1, Vector.empty)
    val unreachableNode       = ParserSyntaxNode(
      3,
      "Detached",
      Vector.empty,
      ParserNodePosition.Absent,
      Vector.empty
    )
    val unreachablePositioned = ParserPositionedSyntax(
      4,
      "DetachedPositioned",
      Vector.empty,
      ParserNodePosition.Absent,
      Vector.empty
    )
    val found                 = failures(value.copy(nodes = value.nodes :+ unreachableNode, positioned = Vector(unreachablePositioned)))
    assertTrue(found.contains(InventoryFailure.UnreachableValue(InventoryKind.Node, 3)))
    assertTrue(found.contains(InventoryFailure.UnreachableValue(InventoryKind.Positioned, 4)))

  @Test def aggregateGeneratedNamesRemainCoveredByTheCanonicalNamePattern(): Unit =
    val compiler   = inventory(snapshot("/one", 1, Vector.empty))
    val generated  = row(
      InventoryValueObservation.GeneratedName("x", "$", 1),
      Some(CatalogValuePattern.Name)
    )
    val aggregate  = AggregatedCompilerProductionInventory
      .aggregate(Vector(compiler.copy(shapes = Vector(generated))))
      .fold(failure => throw new AssertionError(failure.toString), identity)
    val context    = generated.contexts.head
    val base       = completeCatalog(compiler).productions.head
    val production = base.copy(
      id = "Observed",
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Observed",
        Vector(CompilerFieldPattern("value", CatalogValuePattern.Name)),
        Vector(
          CompilerProductionContextPattern(
            ContextPattern.Parent(context.ownerKind, context.ownerPrefix, context.path),
            SourceClassification.SourceReachable
          )
        )
      )
    )
    val catalog    = Scala3PsiProductionCatalog(Vector(production), focusedRoleInventory(Vector(production)))
    assertFalse(
      Scala3PsiProductionCatalogValidator
        .validate(catalog, aggregate, surfaces(catalog))
        .exists:
          case _: CatalogValidationError.UncoveredCompilerShape |
              _: CatalogValidationError.UnrepresentedCatalogProduction =>
            true
          case _ => false
    )

  private def colocatedProductSnapshot(arity: Int): ParserSyntaxSnapshot =
    val position = ParserNodePosition.Positioned(
      PcSourceRange(0, 0),
      0,
      ParserPositionProvenance.Synthetic
    )
    val products = Vector.tabulate(arity)(index =>
      ParserFieldValue.Product(
        "Box",
        Vector(ParserSyntaxField("event", ParserFieldValue.Positioned(index.toLong)))
      )
    )
    val root     = ParserSyntaxNode(
      1,
      "Root",
      Vector(ParserSyntaxField("products", ParserFieldValue.Repeated(products))),
      ParserNodePosition.Positioned(PcSourceRange(0, 1), 0, ParserPositionProvenance.SourceDerived),
      Vector.empty
    )
    val events   = Vector.tabulate(arity)(index =>
      ParserPositionedSyntax(
        index.toLong,
        "Point",
        Vector.empty,
        position,
        Vector(
          ParserPositionedOccurrence(
            1,
            Vector(
              ParserFieldPathSegment.NamedField("products"),
              ParserFieldPathSegment.RepeatedIndex(index),
              ParserFieldPathSegment.NestedProductBoundary("Box"),
              ParserFieldPathSegment.NamedField("event")
            )
          )
        )
      )
    )
    val base     = snapshot("/colocated-products", 1, Vector.empty)
    base.copy(
      sourceUri = base.sourceUri,
      sourceText = base.sourceText,
      sourceDigest = base.sourceDigest,
      sourceLength = base.sourceLength,
      compilerOptions = base.compilerOptions,
      rootNodeId = 1,
      nodes = Vector(root),
      positioned = events,
      comments = Vector.empty,
      diagnostics = Vector.empty,
      capabilities = base.capabilities,
      compilerIdentity = base.compilerIdentity,
      endMarkers = Vector.empty,
      runtimeSupplements = Vector.empty,
      attachments = Vector.empty
    )
