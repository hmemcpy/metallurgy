package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import org.junit.Assert.*
import org.junit.Test

final class Scala3PsiProductionCatalogTest:
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
    assertTrue(root.observations.head.head.value.isInstanceOf[InventoryValueObservation.Repeated])

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
    val nested = root.copy(contexts =
      Vector(
        InventoryContext(InventoryKind.Node, "Owner", Vector(CatalogPathSegment.NamedField("value")))
      )
    )
    val result = aggregate(Vector(base.copy(shapes = Vector(root)), base.copy(shapes = Vector(nested))))
    assertTrue(result.productions.head.contexts.contains(None))
    assertTrue(result.productions.head.contexts.exists(_.nonEmpty))

  @Test def aggregateRejectsMissingObservationsWithoutDiscardingTheRow(): Unit =
    val base    = inventory(snapshot("/one", 1, Vector.empty))
    val present = row(InventoryValueObservation.Name("x"))
    val missing = present.copy(observations = Vector.empty)
    Vector(Vector(missing), Vector(present, missing)).foreach: shapes =>
      assertEquals(
        Left(InventoryAggregationFailure.MissingObservations(InventoryKind.Node, "Observed")),
        AggregatedCompilerProductionInventory.aggregate(Vector(base.copy(shapes = shapes)))
      )

  @Test def aggregateInfersOptionalAndRepeatedFromAllEvidence(): Unit =
    val identity                                      = inventory(snapshot("/one", 1, Vector.empty)).identity
    def value(observation: InventoryValueObservation) = CompilerRuntimeInventory(
      identity,
      s"evidence-${observation.hashCode}",
      Vector(row(observation))
    )
    val optional                                      = Vector(
      value(InventoryValueObservation.Optional(None)),
      value(InventoryValueObservation.Optional(Some(InventoryValueObservation.Name("x"))))
    )
    val repeated                                      = Vector(
      value(InventoryValueObservation.Repeated(Vector.empty)),
      value(InventoryValueObservation.Repeated(Vector(InventoryValueObservation.Scalar(ParserScalar.Integer(1)))))
    )
    assertEquals(
      CatalogValuePattern.Optional(CatalogValuePattern.Name),
      aggregate(optional).productions.head.fields.head.value
    )
    assertArrayEquals(aggregate(optional).canonicalBytes, aggregate(optional.reverse).canonicalBytes)
    assertEquals(
      CatalogValuePattern.Repeated(CatalogValuePattern.Scalar("Integer")),
      aggregate(repeated).productions.head.fields.head.value
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
    assertTrue(
      AggregatedCompilerProductionInventory
        .aggregate(Vector(withValue(InventoryValueObservation.Repeated(Vector.empty))))
        .left
        .toOption
        .get
        .isInstanceOf[InventoryAggregationFailure.UnresolvedShape]
    )
    assertTrue(
      AggregatedCompilerProductionInventory
        .aggregate(
          Vector(
            withValue(InventoryValueObservation.Name("x")),
            withValue(InventoryValueObservation.Scalar(ParserScalar.Text("x")))
          )
        )
        .left
        .toOption
        .get
        .isInstanceOf[InventoryAggregationFailure.IncompatibleShape]
    )
    val different                                   = inventory(snapshot("/one", 1, Vector("different")))
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
        first.shapes.head.copy(observations =
          Vector(
            Vector(InventoryFieldObservation("renamed", InventoryValueObservation.Name("x")))
          )
        )
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
    assertTrue(
      AggregatedCompilerProductionInventory
        .aggregate(Vector(first.copy(shapes = Vector(row(product))), first.copy(shapes = Vector(row(different)))))
        .left
        .toOption
        .get
        .isInstanceOf[InventoryAggregationFailure.IncompatibleShape]
    )

  @Test def aggregateCanonicalBytesCannotBeMutatedThroughTheResult(): Unit =
    val result = aggregate(Vector(inventory(snapshot("/one", 1, Vector.empty))))
    val bytes  = result.canonicalBytes
    bytes(0) = (bytes(0) + 1).toByte
    assertFalse(java.util.Arrays.equals(bytes, result.canonicalBytes))
    assertEquals(CanonicalByteEncoder.sha256Hex(result.canonicalBytes), result.fingerprint)

  @Test def emptyCatalogFailsValidationForNonemptyInventory(): Unit =
    val value    = snapshot("/one", 1, Vector.empty)
    val evidence = ProvisionalSourceEvidencePlanner.plan(value).toOption.get
    val result   = WholeFileProductionPlanner.plan(
      value,
      evidence,
      Scala3PsiProductionCatalog.Empty,
      inventory(value),
      ScalaPsiSurfaceInventory(Vector.empty)
    )
    val errors   = result.left.toOption.get.asInstanceOf[WholeFilePlanningFailure.InvalidCatalog].errors
    assertTrue(errors.exists(_.isInstanceOf[CatalogValidationError.UncoveredCompilerShape]))

  @Test def matcherDistinguishesNodesFromScalarsAndChecksNestedFields(): Unit =
    assertFalse(
      CatalogShapeMatcher.matches(
        CatalogValuePattern.Node,
        InventoryValueObservation.Scalar(ParserScalar.Logical(true))
      )
    )
    val observed = InventoryValueObservation.Product(
      "Pair",
      Vector(InventoryFieldObservation("actual", InventoryValueObservation.Name("x")))
    )
    assertFalse(
      CatalogShapeMatcher.matches(
        CatalogValuePattern.Product(
          "Pair",
          Vector(CompilerFieldPattern("expected", CatalogValuePattern.Name))
        ),
        observed
      )
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

  @Test def validatorRejectsAmbiguousGeneralAndSpecificContexts(): Unit =
    val compiler   = inventory(snapshot("/one", 1, Vector.empty))
    val base       = completeCatalog(compiler)
    val child      = base.productions.find(_.pattern.prefix == "Child").get
    val ambiguous  =
      child.copy(id = "Child.general", pattern = child.pattern.copy(contexts = Vector(ContextPattern.Any)))
    val catalog    = base.copy(productions = base.productions :+ ambiguous)
    val validation = Scala3PsiProductionCatalogValidator.validate(catalog, compiler, surfaces(catalog))
    assertTrue(validation.exists(_.isInstanceOf[CatalogValidationError.AmbiguousCompilerShape]))

  @Test def validatorRequiresExactFieldDispositionsAndChildDeclarations(): Unit =
    val compiler                                                             = inventory(snapshot("/one", 1, Vector.empty))
    val base                                                                 = completeCatalog(compiler)
    val root                                                                 = base.productions.find(_.pattern.prefix == "Root").get
    def errors(updated: Scala3PsiProduction): Vector[CatalogValidationError] =
      val catalog = base.copy(productions = base.productions.map(p => if p.id == root.id then updated else p))
      Scala3PsiProductionCatalogValidator.validate(catalog, compiler, surfaces(catalog))

    assertTrue(
      errors(root.copy(dispositions = Vector.empty))
        .exists(_.isInstanceOf[CatalogValidationError.MissingFieldDisposition])
    )
    assertTrue(
      errors(root.copy(dispositions = root.dispositions ++ root.dispositions))
        .exists(_.isInstanceOf[CatalogValidationError.DuplicateFieldDisposition])
    )
    assertTrue(
      errors(root.copy(children = Vector.empty))
        .exists(_.isInstanceOf[CatalogValidationError.MissingChildDeclaration])
    )

  @Test def evidenceFingerprintMismatchFailsBeforeCatalogMatching(): Unit =
    val value    = snapshot("/one", 1, Vector.empty)
    val evidence = ProvisionalSourceEvidencePlanner.plan(value).toOption.get.copy(parserEvidenceFingerprint = "other")
    assertTrue(
      WholeFileProductionPlanner
        .plan(
          value,
          evidence,
          Scala3PsiProductionCatalog.Empty,
          inventory(value),
          ScalaPsiSurfaceInventory(Vector.empty)
        )
        .left
        .toOption
        .get
        .isInstanceOf[WholeFilePlanningFailure.EvidenceFingerprintMismatch]
    )

  private def inventory(value: ParserSyntaxSnapshot): CompilerRuntimeInventory =
    CompilerRuntimeInventory.from(value).fold(f => throw new AssertionError(f.toString), identity)

  private def aggregate(values: Vector[CompilerRuntimeInventory]): AggregatedCompilerProductionInventory =
    AggregatedCompilerProductionInventory.aggregate(values).fold(f => throw new AssertionError(f.toString), identity)

  private def row(value: InventoryValueObservation): CompilerShapeInventoryRow =
    CompilerShapeInventoryRow(
      InventoryKind.Node,
      "Observed",
      Vector.empty,
      Vector(Vector(InventoryFieldObservation("value", value))),
      Vector(InventoryContext(InventoryKind.Node, "Owner", Vector(CatalogPathSegment.NamedField("value")))),
      Vector(SourceClassification.SourceReachable)
    )

  private def failures(value: ParserSyntaxSnapshot): Vector[InventoryFailure] =
    CompilerRuntimeInventory.from(value).left.toOption.get

  private def completeCatalog(compiler: CompilerRuntimeInventory): Scala3PsiProductionCatalog =
    Scala3PsiProductionCatalog(
      compiler.shapes.map: shape =>
        val childField = shape.patternFields.headOption.map(_.name)
        Scala3PsiProduction(
          shape.prefix,
          CompilerProductionPattern(
            shape.kind,
            shape.prefix,
            shape.patternFields,
            if shape.contexts.isEmpty then Vector(ContextPattern.Root)
            else
              shape.contexts.map(context => ContextPattern.Parent(context.ownerKind, context.ownerPrefix, context.path))
          ),
          childField.toVector.map(FieldDisposition(_, FieldDispositionKind.Child)),
          childField.toVector.map(field =>
            ChildDeclaration("child", field, ChildCardinality.Repeated(0, None), ChildPlacement.Direct, "Child")
          ),
          Vector.empty,
          Vector(LayoutAlternative.None),
          RecoveryPolicy.Reject,
          s"element.${shape.prefix}",
          TargetRequirement.Compatible,
          Vector.empty,
          PersistenceObligations.NotApplicable
        )
    )

  private def surfaces(catalog: Scala3PsiProductionCatalog): ScalaPsiSurfaceInventory =
    ScalaPsiSurfaceInventory(
      catalog.productions
        .map(p =>
          ScalaPsiSurfaceRow(
            p.targetSurfaceId,
            SurfaceFactKind.Element,
            None,
            FactStatus.Available,
            SurfaceClassification.Derived
          )
        )
        .distinct
    )

  private def node(id: Long, value: ParserFieldValue) = ParserSyntaxNode(
    id,
    if id == 1 then "Root" else "Child",
    Vector(ParserSyntaxField("children", value)),
    ParserNodePosition.Positioned(PcSourceRange(0, 1), 0, ParserPositionProvenance.SourceDerived),
    Vector.empty
  )

  private def snapshot(path: String, loader: Long, options: Vector[String]): ParserSyntaxSnapshot =
    val source = "x"
    val child  = ParserSyntaxNode(
      2,
      "Child",
      Vector.empty,
      ParserNodePosition.Positioned(PcSourceRange(0, 1), 0, ParserPositionProvenance.SourceDerived),
      Vector(
        ParserNodeOccurrence(
          1,
          Vector(ParserFieldPathSegment.NamedField("children"), ParserFieldPathSegment.RepeatedIndex(0))
        )
      )
    )
    ParserSyntaxSnapshot(
      ParserSourceUri.from("file:///Catalog.scala").toOption.get,
      source,
      ParserSyntaxSnapshot.digest(source),
      source.length,
      options,
      1,
      Vector(node(1, ParserFieldValue.Repeated(Vector(ParserFieldValue.Node(2)))), child),
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Scala3ParserCapabilities(
        ParserCapabilityStatus.Available,
        ParserCapabilityStatus.Available,
        ParserCapabilityStatus.Available,
        ParserCapabilityStatus.Available,
        ParserCapabilityStatus.Available,
        ParserCapabilityStatus.Available,
        ParserCapabilityStatus.Available,
        ParserCapabilityStatus.Available,
        ParserCapabilityStatus.Available
      ),
      Scala3ParserCompilerIdentity(
        Scala3ParserArtifactCoordinate("org", "compiler", "3"),
        Vector(
          Scala3ParserArtifactIdentity("a.jar", path, 1, "a", 0),
          Scala3ParserArtifactIdentity("b.jar", path, 2, "b", 1)
        ),
        Scala3ParserLoaderIdentity(loader)
      )
    )
