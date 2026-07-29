package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compilerbackend.*
import com.hmemcpy.metallurgy.pc.*
import org.junit.Assert.*
import org.junit.Test
import org.jetbrains.org.objectweb.asm.Opcodes

final class Scala3PsiProductionCatalogTest:
  @Test def surfaceInventoryUsesStructuralAncestryAndConservativeAccessors(): Unit =
    val root      = InstalledScalaPluginClass(
      "org/jetbrains/plugins/scala/lang/psi/api/ScalaPsiElement",
      Some("java/lang/Object"),
      Vector("com/intellij/psi/PsiElement"),
      Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE,
      Vector.empty
    )
    val child     = InstalledScalaPluginClass(
      "org/jetbrains/plugins/scala/lang/psi/api/ScChild",
      Some("java/lang/Object"),
      Vector(root.internalName),
      Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE,
      Vector(
        InstalledScalaPluginMethod("child", s"()L${root.internalName};", Opcodes.ACC_PUBLIC),
        InstalledScalaPluginMethod("unit", "()V", Opcodes.ACC_PUBLIC),
        InstalledScalaPluginMethod("argument", "(Ljava/lang/String;)Ljava/lang/String;", Opcodes.ACC_PUBLIC),
        InstalledScalaPluginMethod("staticValue", "()Ljava/lang/String;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC),
        InstalledScalaPluginMethod("syntheticValue", "()Ljava/lang/String;", Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC)
      )
    )
    val helper    = InstalledScalaPluginClass(
      "org/jetbrains/plugins/scala/lang/psi/impl/Helper",
      Some("java/lang/Object"),
      Vector.empty,
      Opcodes.ACC_PUBLIC,
      Vector.empty
    )
    val inventory = ScalaPsiSurfaceInventory.from(
      InstalledScalaPluginSurface(
        InstalledScalaPluginArtifact("plugin.jar", 1L, "hash"),
        Vector(helper, child, root),
        Vector.empty,
        Vector.empty
      )
    )
    assertEquals(SurfaceFactKind.Class, inventory.rows.find(_.id == helper.internalName).get.kind)
    assertEquals(SurfaceClassification.Helper, inventory.rows.find(_.id == helper.internalName).get.classification)
    assertEquals(SurfaceClassification.Derived, inventory.rows.find(_.id == child.internalName).get.classification)
    val methods   = inventory.rows.filter(_.ownerId.contains(child.internalName))
    assertTrue(
      methods.exists(row => row.id.contains("#child") && row.classification == SurfaceClassification.SyntaxContract)
    )
    assertTrue(methods.exists(row => row.id.contains("#unit") && row.kind == SurfaceFactKind.Method))
    assertTrue(methods.exists(row => row.id.contains("#argument") && row.kind == SurfaceFactKind.Method))
    assertFalse(methods.exists(_.id.contains("#staticValue")))
    assertFalse(methods.exists(_.id.contains("#syntheticValue")))
    assertTrue(inventory.rows.find(_.id == child.internalName).get.evidence.exists(_.startsWith("interfaces:")))

  @Test def descriptorAndMalformedBinaryEvidenceFailClosed(): Unit =
    val descriptor =
      """<idea-plugin><extensions><stubElementTypeHolder class="example.Holder"/><stubIndex implementation="example.Index"/></extensions></idea-plugin>"""
    val facts      = InstalledScalaPluginSurfaceScanner.readDescriptor(descriptor).toOption.get
    assertEquals(
      Vector("example/Holder", "example/Index"),
      facts.flatMap(_.implementation)
    )
    assertTrue(InstalledScalaPluginSurfaceScanner.readDescriptor("<idea-plugin>").isLeft)
    assertTrue(InstalledScalaPluginSurfaceScanner.readClass(Array[Byte](1, 2, 3)).isLeft)

  @Test def surfaceCanonicalizationDistinguishesOptionalAndTextBoundaries(): Unit =
    val base  = ScalaPsiSurfaceRow(
      "surface\u0000id\uD800",
      SurfaceFactKind.Element,
      None,
      FactStatus.Available,
      SurfaceClassification.Derived,
      Vector("a\u0000b", "c")
    )
    val one   = ScalaPsiSurfaceInventory(Vector(base))
    val two   = ScalaPsiSurfaceInventory(Vector(base.copy(ownerId = Some(""))))
    val three = ScalaPsiSurfaceInventory(Vector(base.copy(evidence = Vector("a", "b\u0000c"))))
    assertNotEquals(one.fingerprint, two.fingerprint)
    assertNotEquals(one.fingerprint, three.fingerprint)

  @Test def surfaceInventoryCanonicalizesRawBinaryFactsAndBindsArtifact(): Unit =
    val api      = InstalledScalaPluginClass(
      "org/jetbrains/plugins/scala/lang/psi/api/ScSynthetic",
      Some("java/lang/Object"),
      Vector("com/intellij/psi/PsiElement"),
      Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE,
      Vector(
        InstalledScalaPluginMethod("bc", "()V", Opcodes.ACC_PUBLIC),
        InstalledScalaPluginMethod("b", "(Lc;)V", Opcodes.ACC_PUBLIC)
      )
    )
    val artifact = InstalledScalaPluginArtifact("scalaCommunity.jar", 17L, "abc")
    val facts    = Vector(
      InstalledScalaPluginDescriptorFact("stubIndex", Some("example.Index")),
      InstalledScalaPluginDescriptorFact("stubElementTypeHolder", None)
    )
    val forward  =
      ScalaPsiSurfaceInventory.from(InstalledScalaPluginSurface(artifact, Vector(api), facts, Vector("malformed")))
    val reverse  = ScalaPsiSurfaceInventory.from(
      InstalledScalaPluginSurface(
        artifact,
        Vector(api.copy(methods = api.methods.reverse)),
        facts.reverse,
        Vector("malformed")
      )
    )
    assertArrayEquals(forward.canonicalBytes, reverse.canonicalBytes)
    assertEquals(forward.fingerprint, reverse.fingerprint)
    assertTrue(forward.rows.exists(_.status == FactStatus.Unresolved("registration target is absent or unscanned")))
    assertTrue(forward.rows.exists(_.status == FactStatus.Unresolved("malformed")))
    val changed  = ScalaPsiSurfaceInventory.from(
      InstalledScalaPluginSurface(artifact.copy(byteSize = 18L), Vector(api), facts, Vector("malformed"))
    )
    assertNotEquals(forward.fingerprint, changed.fingerprint)
    val bytes    = forward.canonicalBytes
    bytes(0) = (bytes(0) + 1).toByte
    assertEquals(forward.fingerprint, CanonicalByteEncoder.sha256Hex(forward.canonicalBytes))

  @Test def installedSurfaceHasStableExactCategories(): Unit =
    val first  = ScalaPsiSurfaceInventory.installed().fold(message => throw new AssertionError(message), identity)
    val second = ScalaPsiSurfaceInventory.installed().fold(message => throw new AssertionError(message), identity)
    assertTrue(first.artifact.exists(a => a.fileName.nonEmpty && a.byteSize > 0 && a.sha256.length == 64))
    Vector(
      SurfaceFactKind.Element,
      SurfaceFactKind.PublicAccessor,
      SurfaceFactKind.Stub,
      SurfaceFactKind.Index,
      SurfaceFactKind.Navigation
    )
      .foreach(kind => assertTrue(s"missing $kind", first.rows.exists(_.kind == kind)))
    assertFalse(first.rows.exists(_.status.isInstanceOf[FactStatus.Unresolved]))
    assertTrue(
      first.rows.exists(row =>
        row.kind == SurfaceFactKind.Factory && row.evidence.contains("registration:stubElementTypeHolder")
      )
    )
    assertArrayEquals(first.canonicalBytes, second.canonicalBytes)
    assertEquals(first.fingerprint, second.fingerprint)

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

  @Test def aggregateUsesOnlyAgreedDeclarationsForEmptyContainers(): Unit =
    val base                                                                                  = inventory(snapshot("/one", 1, Vector.empty))
    def withValue(value: InventoryValueObservation)                                           =
      base.copy(parserEvidenceFingerprint = value.hashCode.toString, shapes = Vector(row(value)))
    def withField(value: InventoryValueObservation, declaration: Option[CatalogValuePattern]) =
      base.copy(shapes = Vector(row(value, declaration)))
    val repeated                                                                              = CatalogValuePattern.Repeated(CatalogValuePattern.Node)
    val optional                                                                              = CatalogValuePattern.Optional(CatalogValuePattern.Name)
    assertEquals(
      repeated,
      aggregate(
        Vector(withField(InventoryValueObservation.Repeated(Vector.empty), Some(repeated)))
      ).productions.head.fields.head.value
    )
    assertEquals(
      optional,
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
    Vector(
      Vector(
        withField(InventoryValueObservation.Repeated(Vector.empty), Some(repeated)),
        withField(
          InventoryValueObservation.Repeated(Vector.empty),
          Some(CatalogValuePattern.Repeated(CatalogValuePattern.Name))
        )
      ),
      Vector(
        withField(
          InventoryValueObservation.Repeated(Vector(InventoryValueObservation.Name("x"))),
          Some(repeated)
        )
      )
    ).foreach(values =>
      assertTrue(
        AggregatedCompilerProductionInventory
          .aggregate(values)
          .left
          .toOption
          .get
          .isInstanceOf[InventoryAggregationFailure.IncompatibleShape]
      )
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
          .copy(observation = Vector(InventoryFieldObservation("renamed", InventoryValueObservation.Name("x"))))
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
    val result   = planned(
      value,
      evidence,
      Scala3PsiProductionCatalog.Empty,
      aggregate(Vector(inventory(value))),
      ScalaPsiSurfaceInventory(Vector.empty)
    )
    val errors   = result.left.toOption.get.asInstanceOf[WholeFilePlanningFailure.InvalidCatalog].errors
    assertTrue(errors.exists(_.isInstanceOf[CatalogValidationError.UncoveredCompilerShape]))

  @Test def executableValidationDoesNotRequireAPartialCatalogToOwnUnrelatedInstalledSyntaxSurfaces(): Unit =
    val compiler         = inventory(snapshot("/one", 1, Vector.empty))
    val catalog          = completeCatalog(compiler)
    val unrelated        = ScalaPsiSurfaceRow(
      "element.Unrelated",
      SurfaceFactKind.Element,
      None,
      FactStatus.Available,
      SurfaceClassification.SyntaxContract
    )
    val surfaceInventory = surfaces(catalog).copy(rows = surfaces(catalog).rows :+ unrelated)
    val aggregate        = this.aggregate(Vector(compiler))
    val reportGate       = Scala3PsiProductionCatalogValidator.validate(catalog, aggregate, surfaceInventory)
    assertTrue(reportGate.contains(CatalogValidationError.UnaccountedSyntaxSurface(unrelated.id)))
    assertTrue(Scala3PsiProductionCatalogValidator.validateExecutable(catalog, aggregate, surfaceInventory).isEmpty)

  @Test def preparedCatalogBindsReviewedRowsBeforePlanningALiveSubset(): Unit =
    val value            = snapshot("/one", 1, Vector.empty)
    val baseCompiler     = inventory(value)
    val unusedValue      = value.copy(nodes = value.nodes.updated(1, value.nodes(1).copy(production = "Unused")))
    val unusedCompiler   = inventory(unusedValue)
    val baseCatalog      = completeCatalog(baseCompiler)
    val unusedProduction = completeCatalog(unusedCompiler).productions.find(_.id == "Unused").get
    val catalog          = baseCatalog.copy(productions = baseCatalog.productions :+ unusedProduction)
    val surface          = surfaces(catalog)
    val prepared         = PreparedProductionCatalog
      .prepare(catalog, aggregate(Vector(baseCompiler, unusedCompiler)), surface)
      .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)

    assertTrue(
      WholeFileProductionPlanner
        .plan(value, ProvisionalSourceEvidencePlanner.plan(value).toOption.get, prepared)
        .isRight
    )
    assertTrue(
      PreparedProductionCatalog
        .prepare(catalog, aggregate(Vector(baseCompiler)), surface)
        .left
        .toOption
        .get
        .exists(_.isInstanceOf[CatalogValidationError.UnrepresentedCatalogProduction])
    )
    assertTrue(
      scala.compiletime.testing
        .typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[PreparedProductionCatalog]]")
        .nonEmpty
    )

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

  @Test def canonicalNamePatternMatchesOrdinaryAndGeneratedRuntimeNames(): Unit =
    Vector(
      InventoryValueObservation.Name("name"),
      InventoryValueObservation.GeneratedName("name", "$", 1)
    ).foreach(observation => assertTrue(CatalogShapeMatcher.matches(CatalogValuePattern.Name, observation)))
    assertFalse(
      CatalogShapeMatcher.matches(
        CatalogValuePattern.GeneratedName,
        InventoryValueObservation.Name("name")
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
      child.copy(
        id = "Child.general",
        pattern = child.pattern.copy(occurrences =
          Vector(
            CompilerProductionContextPattern(ContextPattern.Any, child.pattern.occurrences.head.sourceClassification)
          )
        )
      )
    val catalog    = base.copy(productions = base.productions :+ ambiguous)
    val validation = Scala3PsiProductionCatalogValidator.validate(catalog, compiler, surfaces(catalog))
    assertTrue(validation.exists(_.isInstanceOf[CatalogValidationError.AmbiguousCompilerShape]))

  @Test def aggregatedValidationCoversShapesAndSourceClassificationsBeyondOneSnapshot(): Unit =
    val compiler   = inventory(snapshot("/one", 1, Vector.empty))
    val catalog    = completeCatalog(compiler)
    val root       = compiler.shapes.find(_.prefix == "Root").get
    val synthetic  = root.copy(sourceClassification = SourceClassification.Synthetic)
    val additional = row(InventoryValueObservation.Name("x"))
    val aggregated = aggregate(
      Vector(
        compiler,
        compiler.copy(
          parserEvidenceFingerprint = "additional",
          shapes = Vector(synthetic, additional)
        )
      )
    )
    val validation = Scala3PsiProductionCatalogValidator.validate(catalog, aggregated, surfaces(catalog))
    assertTrue(
      validation.contains(
        CatalogValidationError.UncoveredCompilerShape(
          root.kind,
          root.prefix,
          None,
          SourceClassification.Synthetic
        )
      )
    )
    assertTrue(
      validation.exists:
        case CatalogValidationError.UncoveredCompilerShape(_, "Observed", _, _) => true
        case _                                                                  => false
    )

  @Test def aggregatedValidationRejectsCatalogProductionsAbsentFromCanonicalInventory(): Unit =
    val compiler = inventory(snapshot("/one", 1, Vector.empty))
    val base     = completeCatalog(compiler)
    val stale    = base.productions.head.copy(
      id = "stale",
      pattern = base.productions.head.pattern.copy(prefix = "Removed")
    )
    val catalog  = base.copy(productions = base.productions :+ stale)
    assertTrue(
      Scala3PsiProductionCatalogValidator
        .validate(catalog, aggregate(Vector(compiler)), surfaces(catalog))
        .contains(CatalogValidationError.UnrepresentedCatalogProduction("stale"))
    )

  @Test def aggregatedValidationPreservesContextAndSourceClassificationAssociations(): Unit =
    val compiler      = inventory(snapshot("/one", 1, Vector.empty))
    val root          = compiler.shapes.find(_.prefix == "Root").get
    val parentContext = InventoryContext(
      InventoryKind.Node,
      "Owner",
      Vector(CatalogPathSegment.NamedField("value"))
    )
    val paired        = aggregate(
      Vector(
        compiler.copy(shapes =
          Vector(
            root.copy(contexts = Vector.empty, sourceClassification = SourceClassification.SourceReachable),
            root.copy(contexts = Vector(parentContext), sourceClassification = SourceClassification.Synthetic)
          )
        )
      )
    )
    val base          = completeCatalog(compiler).productions.find(_.pattern.prefix == "Root").get
    val pattern       = base.pattern.copy(occurrences =
      Vector(
        CompilerProductionContextPattern(ContextPattern.Root, SourceClassification.SourceReachable),
        CompilerProductionContextPattern(
          ContextPattern.Parent(parentContext.ownerKind, parentContext.ownerPrefix, parentContext.path),
          SourceClassification.Synthetic
        )
      )
    )
    val production    = base.copy(pattern = pattern)
    val catalog       = Scala3PsiProductionCatalog(Vector(production))
    assertFalse(
      Scala3PsiProductionCatalogValidator
        .validate(catalog, paired, surfaces(catalog))
        .exists(_.isInstanceOf[CatalogValidationError.UncoveredCompilerShape])
    )

    val crossed = paired.copy(productions =
      paired.productions.map(row =>
        row.copy(occurrences = row.occurrences.map {
          case CompilerProductionContext(None, _)          =>
            CompilerProductionContext(None, SourceClassification.Synthetic)
          case CompilerProductionContext(Some(context), _) =>
            CompilerProductionContext(Some(context), SourceClassification.SourceReachable)
        })
      )
    )
    assertTrue(
      Scala3PsiProductionCatalogValidator
        .validate(catalog, crossed, surfaces(catalog))
        .exists(_.isInstanceOf[CatalogValidationError.UncoveredCompilerShape])
    )

  @Test def aggregatedValidationRejectsAnUnobservedOccurrenceAlternative(): Unit =
    val compiler = inventory(snapshot("/one", 1, Vector.empty))
    val base     = completeCatalog(compiler)
    val root     = base.productions.find(_.pattern.prefix == "Root").get
    val stale    = root.copy(pattern =
      root.pattern.copy(occurrences =
        root.pattern.occurrences :+
          CompilerProductionContextPattern(ContextPattern.Root, SourceClassification.Synthetic)
      )
    )
    val catalog  = base.copy(productions = base.productions.map(p => if p.id == root.id then stale else p))
    assertTrue(
      Scala3PsiProductionCatalogValidator
        .validate(catalog, aggregate(Vector(compiler)), surfaces(catalog))
        .contains(CatalogValidationError.UnrepresentedCatalogProduction(root.id))
    )

  @Test def aggregatedValidationRejectsWildcardOccurrenceAlternatives(): Unit =
    val compiler = inventory(snapshot("/one", 1, Vector.empty))
    val base     = completeCatalog(compiler)
    val root     = base.productions.find(_.pattern.prefix == "Root").get
    val stale    = root.copy(pattern =
      root.pattern.copy(occurrences =
        root.pattern.occurrences :+
          CompilerProductionContextPattern(ContextPattern.Any, SourceClassification.SourceReachable)
      )
    )
    val catalog  = base.copy(productions = base.productions.map(p => if p.id == root.id then stale else p))
    assertTrue(
      Scala3PsiProductionCatalogValidator
        .validate(catalog, aggregate(Vector(compiler)), surfaces(catalog))
        .contains(CatalogValidationError.UnrepresentedCatalogProduction(root.id))
    )

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
    val catalog    = Scala3PsiProductionCatalog(Vector(production))
    assertFalse(
      Scala3PsiProductionCatalogValidator
        .validate(catalog, aggregate, surfaces(catalog))
        .exists:
          case _: CatalogValidationError.UncoveredCompilerShape |
              _: CatalogValidationError.UnrepresentedCatalogProduction =>
            true
          case _ => false
    )

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

  @Test def validatorRejectsStructurallyIncompleteCatalogDeclarations(): Unit =
    val compiler = inventory(snapshot("/one", 1, Vector.empty))
    val base     = completeCatalog(compiler)
    val root     = base.productions.find(_.pattern.prefix == "Root").get
    val child    = base.productions.find(_.pattern.prefix == "Child").get
    val invalid  = root.copy(
      pattern = root.pattern.copy(occurrences = Vector.empty),
      children = root.children.map(
        _.copy(
          roleId = "duplicate",
          productionId = "missing",
          cardinality = ChildCardinality.Repeated(2, Some(1))
        )
      ) ++ root.children.map(_.copy(roleId = "duplicate")),
      terminals = Vector.fill(2)(
        TerminalDeclaration(
          "duplicate",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.Repeated(-1, None)
        )
      ),
      layouts = Vector.empty,
      recovery = RecoveryPolicy.DiagnosticBound(ParserDiagnosticSeverity.Error, Vector.empty),
      accessors = Vector.fill(2)(AccessorObligation("accessor", required = true))
    )
    val catalog  = base.copy(productions = base.productions.map(p => if p.id == root.id then invalid else p))
    val surface  = surfaces(catalog).copy(rows =
      surfaces(catalog).rows :+
        ScalaPsiSurfaceRow(
          "accessor",
          SurfaceFactKind.PublicAccessor,
          None,
          FactStatus.Available,
          SurfaceClassification.Derived
        )
    )
    val errors   = Scala3PsiProductionCatalogValidator.validate(catalog, compiler, surface)
    Vector(
      CatalogValidationError.EmptyOccurrencePatterns(root.id),
      CatalogValidationError.DuplicateChildRoleId(root.id, "duplicate"),
      CatalogValidationError.UnknownChildProductionId(root.id, "missing"),
      CatalogValidationError.InvalidChildCardinality(root.id, "duplicate"),
      CatalogValidationError.DuplicateTerminalId(root.id, "duplicate"),
      CatalogValidationError.InvalidTerminalCardinality(root.id, "duplicate"),
      CatalogValidationError.DuplicateAccessorObligation(root.id, "accessor"),
      CatalogValidationError.EmptyLayoutAlternatives(root.id),
      CatalogValidationError.EmptyRecoveryAlternatives(root.id)
    ).foreach(error => assertTrue(error.toString, errors.contains(error)))
    assertFalse(errors.contains(CatalogValidationError.UnknownChildProductionId(root.id, child.id)))

  @Test def validatorAccountsTokenAndPersistenceClaimsAndRejectsIncompleteFacts(): Unit =
    val compiler    = inventory(snapshot("/one", 1, Vector.empty))
    val base        = completeCatalog(compiler)
    val root        = base.productions.head
    val claimedRoot = root.copy(
      terminals = Vector(
        TerminalDeclaration(
          "token",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token("token.surface"),
          OccurrenceCardinality.ExactlyOne
        )
      ),
      persistence = PersistenceObligations.Required(
        "stub.surface",
        "serializer.surface",
        Vector("index.surface"),
        "navigation.surface"
      )
    )
    val catalog     = base.copy(productions = claimedRoot +: base.productions.tail)
    val obligations = Vector(
      ScalaPsiSurfaceRow(
        "token.surface",
        SurfaceFactKind.Token,
        None,
        FactStatus.Available,
        SurfaceClassification.SyntaxContract
      ),
      ScalaPsiSurfaceRow(
        "stub.surface",
        SurfaceFactKind.Stub,
        None,
        FactStatus.Available,
        SurfaceClassification.SyntaxContract
      ),
      ScalaPsiSurfaceRow(
        "serializer.surface",
        SurfaceFactKind.Serializer,
        None,
        FactStatus.Available,
        SurfaceClassification.SyntaxContract
      ),
      ScalaPsiSurfaceRow(
        "index.surface",
        SurfaceFactKind.Index,
        None,
        FactStatus.Available,
        SurfaceClassification.SyntaxContract
      ),
      ScalaPsiSurfaceRow(
        "navigation.surface",
        SurfaceFactKind.Navigation,
        None,
        FactStatus.Available,
        SurfaceClassification.SyntaxContract
      )
    )
    val complete    = ScalaPsiSurfaceInventory(surfaces(catalog).rows ++ obligations)
    assertTrue(Scala3PsiProductionCatalogValidator.validate(catalog, compiler, complete).isEmpty)

    val unclaimed = base.copy(productions = root +: base.productions.tail)
    val errors    = Scala3PsiProductionCatalogValidator.validate(unclaimed, compiler, complete)
    obligations.foreach(row => assertTrue(errors.contains(CatalogValidationError.UnaccountedSyntaxSurface(row.id))))

    val incomplete = complete.copy(rows =
      complete.rows :+ ScalaPsiSurfaceRow(
        "incomplete.surface",
        SurfaceFactKind.Element,
        None,
        FactStatus.Unsupported("not constructible"),
        SurfaceClassification.Helper
      )
    )
    assertTrue(
      Scala3PsiProductionCatalogValidator
        .validate(catalog, compiler, incomplete)
        .exists(_.isInstanceOf[CatalogValidationError.UnresolvedSurface])
    )

    val neutralRows = Vector(
      ScalaPsiSurfaceRow(
        "helper.surface",
        SurfaceFactKind.Class,
        None,
        FactStatus.Available,
        SurfaceClassification.Helper
      ),
      ScalaPsiSurfaceRow(
        "method.surface",
        SurfaceFactKind.Method,
        None,
        FactStatus.Available,
        SurfaceClassification.Derived
      )
    )
    val wrongKinds  = catalog.copy(productions =
      claimedRoot.copy(
        targetSurfaceId = "helper.surface",
        accessors = Vector(AccessorObligation("method.surface", required = true))
      ) +: catalog.productions.tail
    )
    val kindErrors  = Scala3PsiProductionCatalogValidator.validate(
      wrongKinds,
      compiler,
      ScalaPsiSurfaceInventory(complete.rows ++ neutralRows)
    )
    assertTrue(
      kindErrors.contains(
        CatalogValidationError.InvalidSurface(claimedRoot.id, "helper.surface", SurfaceFactKind.Element)
      )
    )
    assertTrue(
      kindErrors.contains(
        CatalogValidationError.InvalidSurface(claimedRoot.id, "method.surface", SurfaceFactKind.PublicAccessor)
      )
    )

  @Test def coverageReportRendersCapabilityProbedCompatibleTargets(): Unit =
    val runtime = inventory(snapshot("/report", 1, Vector.empty))
    val base    = completeCatalog(runtime)
    val target  = "org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyIntegerLiteral"
    val catalog = base.copy(productions =
      base.productions.head.copy(targetSurfaceId = target, targetRequirement = TargetRequirement.Compatible) +:
        base.productions.tail
    )
    val report  = Scala3PsiProductionCoverageReport.markdown(catalog, aggregate(Vector(runtime)), surfaces(base))
    assertTrue(
      report,
      report.contains(s"`Element:$target` — **Available:catalog-referenced:${catalog.productions.head.id}**")
    )

  @Test def wholeFilePlanningCompilesAClosedTypedPlanDeterministically(): Unit =
    val value     = snapshot("/one", 1, Vector.empty)
    val compiler  = inventory(value)
    val catalog   = completeCatalog(compiler)
    val evidence  = ProvisionalSourceEvidencePlanner.plan(value).toOption.get
    val aggregate = this.aggregate(Vector(compiler))
    val surface   = surfaces(catalog)
    val first     = planned(value, evidence, catalog, aggregate, surface)
      .fold(failure => throw new AssertionError(failure.toString), identity)
    val second    = planned(
      value,
      evidence,
      catalog.copy(productions = catalog.productions.reverse),
      aggregate,
      surface.copy(rows = surface.rows.reverse)
    )
      .fold(failure => throw new AssertionError(failure.toString), identity)
    assertEquals(first, second)
    assertEquals(value.sourceUri, first.sourceUri)
    assertEquals(value.sourceDigest, first.sourceDigest)
    assertEquals(evidence.parserEvidenceFingerprint, first.parserEvidenceFingerprint)
    assertEquals(Vector("Root", "Child"), first.composites.map(_.productionId))
    assertEquals(Vector("element.Root", "element.Child"), first.targetAssertions.map(_.surfaceId))
    assertEquals(Vector.empty, first.virtualLayout)
    assertEquals(Vector.empty, first.accessorAssertions)
    assertEquals(Vector.empty, first.stubAssertions)
    val leaf      = first.physicalLeafOwnership.head
    val child     = first.composites(1).instance
    assertEquals((0L, 0, 1, child, "contents"), (leaf.atomId, leaf.start, leaf.end, leaf.owner, leaf.terminalId))
    assertEquals("x", value.sourceText.substring(leaf.start, leaf.end))
    assertEquals(
      Vector(ParserFieldPathSegment.NamedField("children"), ParserFieldPathSegment.RepeatedIndex(0)),
      first.composites.head.children.head.fieldPath
    )

  @Test def wholeFilePlanningFailsClosedForOwnershipAndChildContractGaps(): Unit =
    val value                                                                  = snapshot("/one", 1, Vector.empty)
    val compiler                                                               = inventory(value)
    val base                                                                   = completeCatalog(compiler)
    val evidence                                                               = ProvisionalSourceEvidencePlanner.plan(value).toOption.get
    val aggregate                                                              = this.aggregate(Vector(compiler))
    val root                                                                   = base.productions.find(_.id == "Root").get
    val child                                                                  = base.productions.find(_.id == "Child").get
    def failure(catalog: Scala3PsiProductionCatalog): WholeFilePlanningFailure =
      planned(value, evidence, catalog, aggregate, surfaces(catalog)).left.toOption.get

    val unowned = base.copy(productions =
      base.productions.map(p => if p.id == child.id then p.copy(terminals = Vector.empty) else p)
    )
    assertEquals(WholeFilePlanningFailure.UnownedSourceAtom(0, 0, 1), failure(unowned))

    val parentFallback     = base.copy(productions =
      base.productions.map(p =>
        if p.id == root.id then
          p.copy(terminals =
            Vector(
              TerminalDeclaration(
                "contents",
                TerminalIntervalSelector.WholeProduction,
                TerminalLeafTarget.Parent,
                OccurrenceCardinality.ExactlyOne
              )
            )
          )
        else p
      )
    )
    val parentFallbackPlan = planned(value, evidence, parentFallback, aggregate, surfaces(parentFallback))
      .fold(error => throw new AssertionError(error.toString), identity)
    assertEquals(
      child.id,
      parentFallbackPlan.composites
        .find(_.instance == parentFallbackPlan.physicalLeafOwnership.head.owner)
        .get
        .productionId
    )

    val conflict = base.copy(productions =
      base.productions.map(p =>
        if p.id == child.id then p.copy(terminals = p.terminals :+ p.terminals.head.copy(id = "duplicate")) else p
      )
    )
    assertTrue(failure(conflict).isInstanceOf[WholeFilePlanningFailure.ConflictingSourceAtomOwners])

    val cardinality = base.copy(productions =
      base.productions.map(p =>
        if p.id == root.id then
          p.copy(children = p.children.map(_.copy(cardinality = ChildCardinality.Repeated(2, None))))
        else p
      )
    )
    assertTrue(failure(cardinality).isInstanceOf[WholeFilePlanningFailure.ChildCardinalityMismatch])

    val layout = base.copy(productions =
      base.productions.map(p =>
        if p.id == child.id then p.copy(layouts = Vector(LayoutAlternative.Indented(Vector("i"), Vector("o")))) else p
      )
    )
    assertTrue(failure(layout).isInstanceOf[WholeFilePlanningFailure.UnsupportedLayout])

    val grouped = base.copy(productions =
      base.productions.map(p =>
        if p.id == root.id then
          p.copy(children = p.children.map(_.copy(cardinality = ChildCardinality.Grouped(1, None))))
        else p
      )
    )
    assertTrue(failure(grouped).isInstanceOf[WholeFilePlanningFailure.UnsupportedChildCardinality])

    val unsupported = base.copy(productions =
      base.productions.map(p =>
        if p.id == root.id then
          p.copy(
            dispositions = p.dispositions.map(_.copy(kind = FieldDispositionKind.Unsupported)),
            children = Vector.empty
          )
        else p
      )
    )
    assertTrue(failure(unsupported).isInstanceOf[WholeFilePlanningFailure.UnsupportedFieldDisposition])

  @Test def wholeFilePlanningRejectsMultiplyParentedDescendants(): Unit =
    val value     = sharedDescendantSnapshot
    val compiler  = inventory(value)
    val catalog   = completeCatalog(compiler)
    val aggregate = this.aggregate(Vector(compiler))
    val result    = planned(
      value,
      ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
      catalog,
      aggregate,
      surfaces(catalog)
    )
    assertTrue(result.left.toOption.get.isInstanceOf[WholeFilePlanningFailure.MultiplyConsumedChildReference])

  @Test def wholeFilePlanningRejectsUnprobedNativeCandidates(): Unit =
    val value     = snapshot("/candidate", 1, Vector.empty)
    val compiler  = inventory(value)
    val base      = completeCatalog(compiler)
    val candidate = base.copy(productions = base.productions.map:
      case production if production.id == "Root" =>
        production.copy(targetRequirement = TargetRequirement.NativeCandidate)
      case production                            => production
    )
    val result    = planned(
      value,
      ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
      candidate,
      aggregate(Vector(compiler)),
      surfaces(candidate)
    )
    assertTrue(result.left.toOption.get.isInstanceOf[WholeFilePlanningFailure.UnprobedNativeCandidate])

  @Test def positionedChildOriginsRemainAbsoluteAndFailAtTheSupportedSubsetBoundary(): Unit =
    val value    = positionedChildSnapshot
    val compiler = inventory(value)
    val child    = compiler.shapes.find(row => row.kind == InventoryKind.Node && row.prefix == "Leaf").get
    assertEquals(
      Vector(
        InventoryContext(
          InventoryKind.Node,
          "Root",
          Vector(
            CatalogPathSegment.NamedField("mods"),
            CatalogPathSegment.RepeatedElement,
            CatalogPathSegment.NamedField("child")
          )
        )
      ),
      child.contexts
    )
    val catalog  = completeCatalog(compiler)
    val result   = planned(
      value,
      ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
      catalog,
      this.aggregate(Vector(compiler)),
      surfaces(catalog)
    )
    assertTrue(result.left.toOption.get.isInstanceOf[WholeFilePlanningFailure.UnsupportedPositionedChildren])

  @Test def absentOptionalTokenTerminalProducesNoTargetAssertion(): Unit =
    val original = snapshot("/absent", 1, Vector.empty)
    val value    = original.copy(nodes =
      original.nodes.map(node => if node.id == 2 then node.copy(position = ParserNodePosition.Absent) else node)
    )
    val compiler = inventory(value)
    val base     = completeCatalog(compiler)
    val root     = base.productions.find(_.id == "Root").get
    val child    = base.productions.find(_.id == "Child").get
    val catalog  = base.copy(productions = base.productions.map:
      case production if production.id == root.id  =>
        production.copy(terminals =
          Vector(
            TerminalDeclaration(
              "root-contents",
              TerminalIntervalSelector.WholeProduction,
              TerminalLeafTarget.Parent,
              OccurrenceCardinality.ExactlyOne
            )
          )
        )
      case production if production.id == child.id =>
        production.copy(terminals =
          Vector(
            TerminalDeclaration(
              "optional-token",
              TerminalIntervalSelector.WholeProduction,
              TerminalLeafTarget.Token("token.optional"),
              OccurrenceCardinality.Optional
            )
          )
        )
      case production                              => production
    )
    val surface  = surfaces(catalog).copy(rows =
      surfaces(catalog).rows :+
        ScalaPsiSurfaceRow(
          "token.optional",
          SurfaceFactKind.Token,
          None,
          FactStatus.Available,
          SurfaceClassification.Derived
        )
    )
    val plan     = planned(
      value,
      ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
      catalog,
      this.aggregate(Vector(compiler)),
      surface
    )
      .fold(failure => throw new AssertionError(failure.toString), identity)
    assertFalse(plan.targetAssertions.exists(_.surfaceId == "token.optional"))
    assertFalse(plan.physicalLeafOwnership.exists(_.terminalId == "optional-token"))

  @Test def evidenceFingerprintMismatchFailsBeforeCatalogMatching(): Unit =
    val value    = snapshot("/one", 1, Vector.empty)
    val compiler = inventory(value)
    val catalog  = completeCatalog(compiler)
    val evidence = ProvisionalSourceEvidencePlanner.plan(value).toOption.get.copy(parserEvidenceFingerprint = "other")
    assertTrue(
      planned(
        value,
        evidence,
        catalog,
        aggregate(Vector(compiler)),
        surfaces(catalog)
      ).left.toOption.get
        .isInstanceOf[WholeFilePlanningFailure.EvidenceFingerprintMismatch]
    )

  @Test def wholeFilePlanningRecomputesDetachedEvidenceAndCompilerInventory(): Unit =
    val value     = snapshot("/one", 1, Vector.empty)
    val compiler  = inventory(value)
    val catalog   = completeCatalog(compiler)
    val evidence  = ProvisionalSourceEvidencePlanner.plan(value).toOption.get
    val firstAtom = evidence.atoms.head
    val detached  = evidence.copy(atoms = evidence.atoms.updated(0, firstAtom.copy(claims = Vector.empty)))
    assertEquals(
      Left(WholeFilePlanningFailure.SourceEvidencePlanMismatch),
      planned(
        value,
        detached,
        catalog,
        aggregate(Vector(compiler)),
        surfaces(catalog)
      )
    )
    assertEquals(
      (value.nodes.map(node => InventoryKind.Node -> node.id) ++
        value.positioned.map(positioned => InventoryKind.Positioned -> positioned.id)).toSet,
      compiler.shapes.map(row => row.kind -> row.id).toSet
    )

  @Test def wholeFilePlanningRejectsAnAggregateForAnotherCompilerIdentity(): Unit =
    val value            = snapshot("/one", 1, Vector.empty)
    val compiler         = inventory(value)
    val catalog          = completeCatalog(compiler)
    val catalogInventory = aggregate(Vector(compiler))
      .copy(identity = compiler.identity.copy(compilerOptions = compiler.identity.compilerOptions :+ "-different"))
    assertTrue(
      planned(
        value,
        ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
        catalog,
        catalogInventory,
        surfaces(catalog)
      ).left.toOption.get
        .isInstanceOf[WholeFilePlanningFailure.CatalogInventoryIdentityMismatch]
    )

  private def planned(
      value: ParserSyntaxSnapshot,
      evidence: ProvisionalSourceEvidencePlan,
      catalog: Scala3PsiProductionCatalog,
      aggregate: AggregatedCompilerProductionInventory,
      surface: ScalaPsiSurfaceInventory
  ): Either[WholeFilePlanningFailure, WholeFileProductionPlan] =
    PreparedProductionCatalog.prepare(catalog, aggregate, surface) match
      case Left(errors)    => Left(WholeFilePlanningFailure.InvalidCatalog(errors))
      case Right(prepared) => WholeFileProductionPlanner.plan(value, evidence, prepared)

  private def inventory(value: ParserSyntaxSnapshot): CompilerRuntimeInventory =
    CompilerRuntimeInventory.from(value).fold(f => throw new AssertionError(f.toString), identity)

  private def aggregate(values: Vector[CompilerRuntimeInventory]): AggregatedCompilerProductionInventory =
    AggregatedCompilerProductionInventory.aggregate(values).fold(f => throw new AssertionError(f.toString), identity)

  private def row(
      value: InventoryValueObservation,
      declaration: Option[CatalogValuePattern] = None
  ): CompilerShapeInventoryRow =
    CompilerShapeInventoryRow(
      InventoryKind.Node,
      1L,
      "Observed",
      Vector.empty,
      Vector(InventoryFieldObservation("value", value, declaration)),
      Vector(InventoryContext(InventoryKind.Node, "Owner", Vector(CatalogPathSegment.NamedField("value")))),
      SourceClassification.SourceReachable
    )

  private def failures(value: ParserSyntaxSnapshot): Vector[InventoryFailure] =
    CompilerRuntimeInventory.from(value).left.toOption.get

  private def completeCatalog(compiler: CompilerRuntimeInventory): Scala3PsiProductionCatalog =
    Scala3PsiProductionCatalog(
      compiler.shapes.map: shape =>
        def referencedProduction(value: InventoryValueObservation): Option[String] = value match
          case InventoryValueObservation.Node(_, prefix)       => Some(prefix)
          case InventoryValueObservation.Positioned(_, prefix) => Some(prefix)
          case InventoryValueObservation.Optional(value)       => value.flatMap(referencedProduction)
          case InventoryValueObservation.Repeated(values)      => values.flatMap(referencedProduction).headOption
          case InventoryValueObservation.Product(_, fields)    =>
            fields.flatMap(field => referencedProduction(field.value)).headOption
          case _: InventoryValueObservation.Name | _: InventoryValueObservation.GeneratedName |
              _: InventoryValueObservation.Scalar | _: InventoryValueObservation.Unsupported =>
            None
        val childField                                                             = shape.patternFields.headOption.map(_.name)
        val childProduction                                                        = shape.observation.flatMap(field => referencedProduction(field.value)).headOption
        Scala3PsiProduction(
          shape.prefix,
          CompilerProductionPattern(
            shape.kind,
            shape.prefix,
            shape.patternFields,
            (if shape.contexts.isEmpty then Vector(ContextPattern.Root)
             else
               shape.contexts.map(context =>
                 ContextPattern.Parent(context.ownerKind, context.ownerPrefix, context.path)
               )
            )
              .map(CompilerProductionContextPattern(_, shape.sourceClassification))
          ),
          childField.toVector.map(FieldDisposition(_, FieldDispositionKind.Child)),
          childField.toVector.flatMap(field =>
            childProduction.map(production =>
              ChildDeclaration("child", field, ChildCardinality.Repeated(0, None), ChildPlacement.Direct, production)
            )
          ),
          if childField.isEmpty then
            Vector(
              TerminalDeclaration(
                "contents",
                TerminalIntervalSelector.WholeProduction,
                TerminalLeafTarget.Parent,
                OccurrenceCardinality.ExactlyOne
              )
            )
          else Vector.empty,
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

  private def sharedDescendantSnapshot: ParserSyntaxSnapshot =
    val value  = snapshot("/shared", 1, Vector.empty)
    val range  = ParserNodePosition.Positioned(PcSourceRange(0, 1), 0, ParserPositionProvenance.SourceDerived)
    val root   = ParserSyntaxNode(
      1,
      "Root",
      Vector(
        ParserSyntaxField(
          "children",
          ParserFieldValue.Repeated(Vector(ParserFieldValue.Node(2), ParserFieldValue.Node(2)))
        )
      ),
      range,
      Vector.empty
    )
    val parent = ParserSyntaxNode(
      2,
      "Parent",
      Vector(ParserSyntaxField("children", ParserFieldValue.Repeated(Vector(ParserFieldValue.Node(3))))),
      range,
      Vector(
        ParserNodeOccurrence(
          1,
          Vector(ParserFieldPathSegment.NamedField("children"), ParserFieldPathSegment.RepeatedIndex(0))
        ),
        ParserNodeOccurrence(
          1,
          Vector(ParserFieldPathSegment.NamedField("children"), ParserFieldPathSegment.RepeatedIndex(1))
        )
      )
    )
    val leaf   = ParserSyntaxNode(
      3,
      "Leaf",
      Vector.empty,
      range,
      Vector(
        ParserNodeOccurrence(
          2,
          Vector(ParserFieldPathSegment.NamedField("children"), ParserFieldPathSegment.RepeatedIndex(0))
        )
      )
    )
    value.copy(nodes = Vector(root, parent, leaf))

  private def positionedChildSnapshot: ParserSyntaxSnapshot =
    val value      = snapshot("/positioned", 1, Vector.empty)
    val range      = ParserNodePosition.Positioned(PcSourceRange(0, 1), 0, ParserPositionProvenance.SourceDerived)
    val root       = ParserSyntaxNode(
      1,
      "Root",
      Vector(
        ParserSyntaxField(
          "mods",
          ParserFieldValue.Repeated(
            Vector(
              ParserFieldValue.Positioned(0),
              ParserFieldValue.Positioned(0)
            )
          )
        )
      ),
      range,
      Vector.empty
    )
    val positioned = ParserPositionedSyntax(
      0,
      "Metadata",
      Vector(ParserSyntaxField("child", ParserFieldValue.Node(2))),
      range,
      Vector(
        ParserPositionedOccurrence(
          1,
          Vector(ParserFieldPathSegment.NamedField("mods"), ParserFieldPathSegment.RepeatedIndex(0))
        ),
        ParserPositionedOccurrence(
          1,
          Vector(ParserFieldPathSegment.NamedField("mods"), ParserFieldPathSegment.RepeatedIndex(1))
        )
      )
    )
    val leaf       = ParserSyntaxNode(
      2,
      "Leaf",
      Vector.empty,
      range,
      Vector(
        ParserNodeOccurrence(
          1,
          Vector(
            ParserFieldPathSegment.NamedField("mods"),
            ParserFieldPathSegment.RepeatedIndex(0),
            ParserFieldPathSegment.NamedField("child")
          )
        )
      )
    )
    value.copy(nodes = Vector(root, leaf), positioned = Vector(positioned))

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
