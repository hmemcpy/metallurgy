package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compilerbackend.*
import com.hmemcpy.metallurgy.pc.*
import org.junit.Assert.*
import org.junit.Test
import org.jetbrains.org.objectweb.asm.Opcodes

final class Scala3PsiProductionCatalogTest:
  private val TransparentRootGrammarRole = GrammarRoleId("test.grammar.transparent-root")
  private val SharedProductGrammarRole   = GrammarRoleId("test.grammar.shared-product")
  private val StructuralEventGrammarRole = GrammarRoleId("test.grammar.structural-event")
  private val SharedOutputRole           = PsiOutputRoleId("test.output.shared-composite")

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

  @Test def repeatedAncestorContextMatchesOnlyAContiguousLineageEndingAtItsAnchor(): Unit =
    val qualifier                                     = Vector(CatalogPathSegment.NamedField("qualifier"))
    val repeated                                      = InventoryAncestor(InventoryKind.Node, "Select", qualifier)
    val anchor                                        = InventoryAncestor(
      InventoryKind.Node,
      "Import",
      Vector(CatalogPathSegment.NamedField("expr"))
    )
    val pattern                                       = ContextPattern.ParentWithRepeatedAncestor(
      InventoryKind.Node,
      "Select",
      qualifier,
      repeated,
      anchor
    )
    val anchored                                      = ContextPattern.AnchorOrParentWithRepeatedAncestor(
      anchor,
      InventoryKind.Node,
      "Select",
      qualifier,
      repeated
    )
    def context(ancestors: Vector[InventoryAncestor]) =
      Some(InventoryContext(InventoryKind.Node, "Select", qualifier, ancestors))
    val direct                                        = Some(InventoryContext(anchor.ownerKind, anchor.ownerPrefix, anchor.path))

    Vector(
      context(Vector(anchor)),
      context(Vector(repeated, anchor)),
      context(Vector.fill(10000)(repeated) :+ anchor)
    ).foreach: candidate =>
      assertTrue(CatalogShapeMatcher.contextMatches(pattern, candidate))
      assertTrue(CatalogShapeMatcher.aggregateContextMatches(pattern, candidate))
      assertTrue(CatalogShapeMatcher.contextMatches(anchored, candidate))
      assertTrue(CatalogShapeMatcher.aggregateContextMatches(anchored, candidate))
    assertTrue(CatalogShapeMatcher.contextMatches(anchored, direct))
    assertTrue(CatalogShapeMatcher.aggregateContextMatches(anchored, direct))

    val adjacent = InventoryAncestor(InventoryKind.Node, "Apply", qualifier)
    Vector(
      None,
      context(Vector.empty),
      context(Vector.fill(10000)(repeated)),
      context(Vector(repeated, adjacent, anchor)),
      Some(
        InventoryContext(InventoryKind.Node, "Select", Vector(CatalogPathSegment.NamedField("other")), Vector(anchor))
      )
    ).foreach: candidate =>
      assertFalse(CatalogShapeMatcher.contextMatches(pattern, candidate))
      assertFalse(CatalogShapeMatcher.aggregateContextMatches(pattern, candidate))
      assertFalse(CatalogShapeMatcher.contextMatches(anchored, candidate))
      assertFalse(CatalogShapeMatcher.aggregateContextMatches(anchored, candidate))

  @Test def parentContextMatchesMixedTypeLineageOnlyUnderItsSelectorAnchor(): Unit =
    val anchor     = InventoryAncestor(
      InventoryKind.Node,
      "ImportSelector",
      Vector(CatalogPathSegment.NamedField("bound"))
    )
    val applied    = InventoryAncestor(
      InventoryKind.Node,
      "AppliedTypeTree",
      Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
    )
    val infix      = InventoryAncestor(
      InventoryKind.Node,
      "InfixOp",
      Vector(CatalogPathSegment.NamedField("right"))
    )
    val descendant = Some(
      InventoryContext(
        InventoryKind.Node,
        "TypeBoundsTree",
        Vector(CatalogPathSegment.NamedField("hi")),
        Vector(infix, applied, anchor)
      )
    )
    val deep       = Some(
      InventoryContext(
        InventoryKind.Node,
        "InfixOp",
        Vector(CatalogPathSegment.NamedField("right")),
        Vector.fill(10000)(infix) :+ applied :+ anchor
      )
    )
    val parent     = ContextPattern.ParentUnderAnchor(
      InventoryKind.Node,
      "TypeBoundsTree",
      Vector(CatalogPathSegment.NamedField("hi")),
      anchor
    )
    assertTrue(CatalogShapeMatcher.contextMatches(parent, descendant))
    assertTrue(CatalogShapeMatcher.aggregateContextMatches(parent, descendant))
    val deepParent = ContextPattern.ParentUnderAnchor(
      InventoryKind.Node,
      "InfixOp",
      Vector(CatalogPathSegment.NamedField("right")),
      anchor
    )
    assertTrue(CatalogShapeMatcher.contextMatches(deepParent, deep))
    assertTrue(CatalogShapeMatcher.aggregateContextMatches(deepParent, deep))
    Vector(
      None,
      Some(
        InventoryContext(
          InventoryKind.Node,
          "TypeBoundsTree",
          Vector(CatalogPathSegment.NamedField("hi")),
          Vector(infix, applied)
        )
      ),
      Some(
        InventoryContext(
          InventoryKind.Node,
          "TypeBoundsTree",
          Vector(CatalogPathSegment.NamedField("lo")),
          Vector(infix, applied, anchor)
        )
      )
    ).foreach: context =>
      assertFalse(CatalogShapeMatcher.contextMatches(parent, context))
      assertFalse(CatalogShapeMatcher.aggregateContextMatches(parent, context))

    val boundsFields                                        = Vector(
      InventoryFieldObservation("lo", InventoryValueObservation.Node(1L, "Thicket")),
      InventoryFieldObservation("hi", InventoryValueObservation.Node(2L, "Ident")),
      InventoryFieldObservation("alias", InventoryValueObservation.Node(1L, "Thicket"))
    )
    def selected(context: InventoryContext): Vector[String] = CatalogShapeMatcher
      .select(
        Scala3PsiProductionCatalog.Reviewed,
        InventoryKind.Node,
        "TypeBoundsTree",
        boundsFields,
        Some(context),
        SourceClassification.SourceReachable
      )
      .map(_.id)
    assertEquals(
      Vector("import-selector-given-bound-wildcard-type"),
      selected(
        InventoryContext(
          InventoryKind.Node,
          "AppliedTypeTree",
          Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
          Vector(anchor)
        )
      )
    )
    Vector(
      InventoryContext(InventoryKind.Node, "ImportSelector", Vector(CatalogPathSegment.NamedField("bound"))),
      InventoryContext(
        InventoryKind.Node,
        "AppliedTypeTree",
        Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
      ),
      InventoryContext(
        InventoryKind.Node,
        "InfixOp",
        Vector(CatalogPathSegment.NamedField("right")),
        Vector(anchor)
      )
    ).foreach(context => assertTrue(selected(context).isEmpty))

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
        CatalogValuePattern.Optional(CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))
      ),
      aggregate(optional).productions.map(_.fields.head.value).toSet
    )
    assertArrayEquals(aggregate(optional).canonicalBytes, aggregate(optional.reverse).canonicalBytes)
    assertEquals(
      Set(
        CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Scalar("Integer")),
        CatalogValuePattern.Repeated(CatalogValuePattern.Scalar("Integer"))
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

  @Test def reviewedCatalogOwnsClosedGrammarAndOutputRoleInventories(): Unit =
    val catalog  = Scala3PsiProductionCatalog.Reviewed
    val expected = Map(
      GrammarRoleId.CompilationUnit    -> Set("file-top-statements"),
      GrammarRoleId.PackageClause      -> Set("file-package", "file-package-top-statements"),
      GrammarRoleId.PackageReference   -> Set("file-import-empty-package"),
      GrammarRoleId.ImportStatement    -> Set("import-statement"),
      GrammarRoleId.ExportStatement    -> Set("export-statement"),
      GrammarRoleId.AbsentProduct      -> Set(
        "import-expression-absent",
        "import-selector-absent",
        "import-selector-given-bound-absent"
      ),
      GrammarRoleId.StableReference    -> Set(
        "import-path-identifier-reference",
        "import-path-reference",
        "import-path-identifier",
        "package-stable-identifier-reference",
        "package-stable-reference",
        "package-stable-identifier",
        "import-selector-given-bound-qualifier-ident",
        "import-selector-given-bound-qualifier-select",
        "import-selector-given-bound-infix-operator"
      ),
      GrammarRoleId.ImportSelector     -> Set("import-selector-direct", "import-selector-braced"),
      GrammarRoleId.ImportSelectorName -> Set(
        "import-selector-name",
        "import-selector-hidden-name",
        "import-selector-wildcard-name",
        "import-selector-empty-name"
      ),
      GrammarRoleId.SimpleType         -> Set(
        "import-selector-bound-type",
        "import-selector-given-bound-qualified-type"
      ),
      GrammarRoleId.AppliedType        -> Set("import-selector-bound-applied-type"),
      GrammarRoleId.WildcardType       -> Set("import-selector-given-bound-wildcard-type"),
      GrammarRoleId.InfixType          -> Set("import-selector-given-bound-infix-type"),
      GrammarRoleId.IntegerLiteral     -> Set("integer-literal-number")
    )
    val actual   = catalog.productions.groupMap(_.grammarRoleId)(_.id).view.mapValues(_.toSet).toMap
    assertEquals(expected, actual)
    assertEquals(expected.keySet, catalog.stableRoles.grammarRoles)
    assertTrue(
      catalog.productions.forall(production =>
        production.grammarRoleId.value != production.id &&
          production.grammarRoleId.value != production.pattern.prefix
      )
    )

    val composites = catalog.productions.flatMap(_.effectiveOutputRealizations.flatMap(_.template.composites))
    val terminals  = catalog.productions.flatMap(_.terminals)
    val usedRoles  = (composites.map(_.outputRoleId) ++ terminals.map(_.outputRoleId)).toSet
    assertEquals(catalog.stableRoles.outputRoles, usedRoles)
    assertTrue(composites.forall(output => output.outputRoleId.value != output.targetSurfaceId))
    assertTrue(terminals.forall(terminal => catalog.stableRoles.outputRoles(terminal.outputRoleId)))
    assertTrue(
      catalog.productions
        .filter(production => production.outputTemplate.isEmpty && production.outputRealizations.isEmpty)
        .forall(_.outputRoleId.nonEmpty)
    )

    val packageBody       = catalog.productions.find(_.id == "file-package-top-statements").get
    val packageStatements = packageBody.children.find(_.fieldName == "stats").get
    assertEquals(ChildCardinality.Grouped(1, None), packageStatements.cardinality)
    assertEquals(
      Set("import-statement", "export-statement", "file-package", "file-package-top-statements"),
      packageStatements.productionIds
    )
    assertEquals(Vector("package-text", "root-remainder", "end-keyword"), packageBody.terminals.map(_.id))
    val syntheticRoot     = catalog.productions.find(_.id == "file-top-statements").get
    val rootStatements    = syntheticRoot.children.find(_.fieldName == "stats").get
    assertEquals(ChildCardinality.Grouped(1, None), rootStatements.cardinality)
    assertEquals(packageStatements.productionIds, rootStatements.productionIds)
    assertTrue(syntheticRoot.outputTemplate.exists(_.composites.isEmpty))

    val exportFields = Vector(
      InventoryFieldObservation("expr", InventoryValueObservation.Node(1L, "Select")),
      InventoryFieldObservation(
        "selectors",
        InventoryValueObservation.Repeated(Vector(InventoryValueObservation.Node(2L, "ImportSelector")))
      )
    )
    val topContext   = Some(
      InventoryContext(
        InventoryKind.Node,
        "PackageDef",
        Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement)
      )
    )
    assertEquals(
      Vector("export-statement"),
      CatalogShapeMatcher
        .select(catalog, InventoryKind.Node, "Export", exportFields, topContext, SourceClassification.SourceReachable)
        .map(_.id)
    )
    Vector(
      None,
      Some(
        InventoryContext(
          InventoryKind.Node,
          "Template",
          Vector(CatalogPathSegment.NamedField("preBody"), CatalogPathSegment.RepeatedElement)
        )
      ),
      Some(
        InventoryContext(
          InventoryKind.Node,
          "Block",
          Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement)
        )
      )
    ).foreach: context =>
      assertTrue(
        CatalogShapeMatcher
          .select(catalog, InventoryKind.Node, "Export", exportFields, context, SourceClassification.SourceReachable)
          .isEmpty
      )

    val exportProduction = catalog.productions.find(_.id == "export-statement").get
    assertEquals(
      Set(
        PsiOutputRoleId.ExportStatement,
        PsiOutputRoleId.ImportExpression,
        PsiOutputRoleId.ImportSelectorSet,
        PsiOutputRoleId.StableReference
      ),
      exportProduction.effectiveOutputRealizations.flatMap(_.template.composites).map(_.outputRoleId).toSet
    )
    assertEquals(
      Set("import-path-reference", "import-path-identifier-reference", "import-expression-absent"),
      exportProduction.children.find(_.roleId == "path").get.productionIds
    )
    assertEquals(
      Set("import-selector-direct", "import-selector-braced"),
      exportProduction.children.find(_.roleId == "selectors").get.productionIds
    )

  @Test def roleValidationRejectsMissingUnknownAndEvidenceDerivedIdentities(): Unit =
    val compiler = inventory(snapshot("/roles", 1, Vector.empty))
    val base     = completeCatalog(compiler)
    val root     = base.productions.find(_.id == "Root").get
    def errors(
        production: Scala3PsiProduction,
        stableRoles: StableRoleInventory = base.stableRoles
    ): Vector[CatalogValidationError] =
      val catalog = base.copy(
        productions = base.productions.map(value => if value.id == production.id then production else value),
        stableRoles = stableRoles
      )
      Scala3PsiProductionCatalogValidator.validateExecutable(catalog, compiler, surfaces(catalog))

    assertTrue(
      errors(root.copy(outputRoleId = None)).contains(CatalogValidationError.MissingDefaultOutputRole(root.id))
    )

    val unknownGrammar  = GrammarRoleId("test.grammar.unknown")
    assertTrue(
      errors(root.copy(grammarRoleId = unknownGrammar))
        .contains(CatalogValidationError.UnknownGrammarRole(root.id, unknownGrammar))
    )
    val evidenceGrammar = GrammarRoleId(root.pattern.prefix)
    val evidenceErrors  = errors(root.copy(grammarRoleId = evidenceGrammar))
    assertTrue(
      evidenceErrors.contains(
        CatalogValidationError.CompilerDerivedGrammarRole(root.id, evidenceGrammar, root.pattern.prefix)
      )
    )
    assertTrue(
      evidenceErrors.contains(CatalogValidationError.CatalogAlternativeDerivedGrammarRole(root.id, evidenceGrammar))
    )

    val unknownOutput = PsiOutputRoleId("test.output.unknown")
    assertTrue(
      errors(root.copy(outputRoleId = Some(unknownOutput)))
        .contains(CatalogValidationError.UnknownOutputRole(root.id, "self", unknownOutput))
    )
    val hostOutput    = PsiOutputRoleId(root.targetSurfaceId)
    assertTrue(
      errors(
        root.copy(outputRoleId = Some(hostOutput)),
        base.stableRoles.copy(outputRoles = base.stableRoles.outputRoles + hostOutput)
      ).contains(CatalogValidationError.HostDerivedOutputRole(root.id, "self", hostOutput, root.targetSurfaceId))
    )

    val child                   = base.productions.find(_.id == "Child").get
    val childTerminal           = child.terminals.head
    val otherAlternativeGrammar = GrammarRoleId(child.id)
    assertTrue(
      errors(
        root.copy(grammarRoleId = otherAlternativeGrammar),
        base.stableRoles.copy(grammarRoles = base.stableRoles.grammarRoles + otherAlternativeGrammar)
      ).contains(CatalogValidationError.CatalogAlternativeDerivedGrammarRole(root.id, otherAlternativeGrammar))
    )
    val otherCompilerGrammar    = GrammarRoleId(child.pattern.prefix)
    assertTrue(
      errors(
        root.copy(grammarRoleId = otherCompilerGrammar),
        base.stableRoles.copy(grammarRoles = base.stableRoles.grammarRoles + otherCompilerGrammar)
      ).contains(
        CatalogValidationError.CompilerDerivedGrammarRole(root.id, otherCompilerGrammar, child.pattern.prefix)
      )
    )
    assertTrue(
      errors(child.copy(terminals = Vector(childTerminal.copy(outputRoleId = unknownOutput))))
        .contains(CatalogValidationError.UnknownOutputRole(child.id, childTerminal.id, unknownOutput))
    )
    val otherHostOutput         = PsiOutputRoleId(child.targetSurfaceId)
    assertTrue(
      errors(
        root.copy(outputRoleId = Some(otherHostOutput)),
        base.stableRoles.copy(outputRoles = base.stableRoles.outputRoles + otherHostOutput)
      ).contains(CatalogValidationError.HostDerivedOutputRole(root.id, "self", otherHostOutput, child.targetSurfaceId))
    )
    val installedHostSurface    = "test.host.installed-unreferenced"
    val installedHostRole       = PsiOutputRoleId(installedHostSurface)
    val installedHostProduction = root.copy(outputRoleId = Some(installedHostRole))
    val installedHostCatalog    = base.copy(
      productions = base.productions.updated(0, installedHostProduction),
      stableRoles = base.stableRoles.copy(outputRoles = base.stableRoles.outputRoles + installedHostRole)
    )
    val installedHostSurfaces   = surfaces(installedHostCatalog).copy(rows =
      surfaces(installedHostCatalog).rows :+
        ScalaPsiSurfaceRow(
          installedHostSurface,
          SurfaceFactKind.Element,
          None,
          FactStatus.Available,
          SurfaceClassification.SyntaxContract
        )
    )
    assertTrue(
      Scala3PsiProductionCatalogValidator
        .validateExecutable(installedHostCatalog, compiler, installedHostSurfaces)
        .contains(
          CatalogValidationError.HostDerivedOutputRole(
            root.id,
            "self",
            installedHostRole,
            installedHostSurface
          )
        )
    )
    val tokenSurface            = "test.host.token"
    val tokenHostRole           = PsiOutputRoleId(tokenSurface)
    val hostTerminal            = childTerminal.copy(
      target = TerminalLeafTarget.Token(tokenSurface),
      outputRoleId = tokenHostRole
    )
    assertTrue(
      errors(
        child.copy(terminals = Vector(hostTerminal)),
        base.stableRoles.copy(outputRoles = base.stableRoles.outputRoles + tokenHostRole)
      ).contains(CatalogValidationError.HostDerivedOutputRole(child.id, childTerminal.id, tokenHostRole, tokenSurface))
    )

    val extraGrammar    = GrammarRoleId("test.grammar.unreferenced")
    val extraOutput     = PsiOutputRoleId("test.output.unreferenced")
    val expandedRoles   = base.stableRoles.copy(
      grammarRoles = base.stableRoles.grammarRoles + extraGrammar,
      outputRoles = base.stableRoles.outputRoles + extraOutput
    )
    val inventoryErrors = Scala3PsiProductionCatalogValidator.validate(
      base.copy(stableRoles = expandedRoles),
      compiler,
      surfaces(base)
    )
    assertTrue(inventoryErrors.contains(CatalogValidationError.UnreferencedGrammarRole(extraGrammar)))
    assertTrue(inventoryErrors.contains(CatalogValidationError.UnreferencedOutputRole(extraOutput)))

    val tokenCatalog  = base.copy(productions = base.productions.map:
      case production if production.id == child.id =>
        production.copy(terminals = Vector(childTerminal.copy(target = TerminalLeafTarget.Token(tokenSurface))))
      case production                              => production
    )
    val tokenSurfaces = surfaces(tokenCatalog).copy(rows =
      surfaces(tokenCatalog).rows :+
        ScalaPsiSurfaceRow(
          tokenSurface,
          SurfaceFactKind.Token,
          None,
          FactStatus.Available,
          SurfaceClassification.Derived
        )
    )
    val tokenReport   = Scala3PsiProductionCoverageReport.markdown(
      tokenCatalog,
      aggregate(Vector(compiler)),
      tokenSurfaces
    )
    assertTrue(tokenReport.contains(s"${child.id}:terminal:${childTerminal.id}->$tokenSurface"))
    assertTrue(tokenReport.contains(s"host-targets=element.Child,$tokenSurface"))

    val grammarFailure     = Scala3SyntaxCapabilityFailure.from(
      "digest",
      Scala3SyntaxCapabilityStage.Catalog,
      Vector(CatalogValidationError.UnknownGrammarRole(root.id, unknownGrammar)),
      ParserPreparationEpoch(1),
      None
    )
    assertEquals(
      Scala3SyntaxCapabilityRequirement.GrammarRole(Some(unknownGrammar.value)),
      grammarFailure.requirement
    )
    val outputFailure      = Scala3SyntaxCapabilityFailure.from(
      "digest",
      Scala3SyntaxCapabilityStage.Catalog,
      Vector(CatalogValidationError.UnknownOutputRole(root.id, "self", unknownOutput)),
      ParserPreparationEpoch(1),
      None
    )
    assertEquals(
      Scala3SyntaxCapabilityRequirement.OutputRole(Some(unknownOutput.value)),
      outputFailure.requirement
    )
    val unaccountedFailure = Scala3SyntaxCapabilityFailure.from(
      "digest",
      Scala3SyntaxCapabilityStage.Catalog,
      Vector(CatalogValidationError.UnaccountedSyntaxSurface(tokenSurface)),
      ParserPreparationEpoch(1),
      None
    )
    assertEquals(
      Scala3SyntaxCapabilityRequirement.OutputRole(None),
      unaccountedFailure.requirement
    )

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
    val productions      = baseCatalog.productions :+ unusedProduction
    val catalog          = baseCatalog.copy(productions = productions, stableRoles = focusedRoleInventory(productions))
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

  @Test def runtimeSubsetRetainsCatalogAlternativesThatAreInactiveInTheCurrentFile(): Unit =
    val value       = snapshot("/runtime-subset", 1, Vector.empty)
    val runtime     = inventory(value)
    val alternative = inventory(
      value.copy(nodes = value.nodes.updated(1, value.nodes(1).copy(production = "Alternative")))
    )
    val base        = completeCatalog(runtime)
    val productions = base.productions :+ completeCatalog(alternative).productions.find(_.id == "Alternative").get
    val catalog     = base.copy(productions = productions, stableRoles = focusedRoleInventory(productions))
    val compiler    = aggregate(Vector(runtime, alternative))
    val prepared    = PreparedProductionCatalog.prepareRuntimeSubset(catalog, runtime, compiler, surfaces(catalog))
    assertTrue(prepared.left.toOption.toString, prepared.isRight)
    assertEquals(catalog.productions.map(_.id), prepared.toOption.get.catalog.productions.map(_.id))

  @Test def coveredFutureCompilerIdentityPreparesWhileNovelShapeAndRoleDriftRemainVisible(): Unit =
    val current        = snapshot("/current", 1, Vector.empty)
    val currentRuntime = inventory(current)
    val catalog        = completeCatalog(currentRuntime)
    val future         = current.copy(compilerIdentity =
      current.compilerIdentity.copy(coordinate = current.compilerIdentity.coordinate.copy(version = "99.0.0"))
    )
    val futureRuntime  = inventory(future)
    val futureCompiler = aggregate(Vector(futureRuntime))
    assertTrue(
      PreparedProductionCatalog.prepare(catalog, futureCompiler, surfaces(catalog)).isRight
    )

    val report = Scala3PsiProductionCoverageReport.markdown(catalog, futureCompiler, surfaces(catalog))
    assertTrue(report.contains("org:compiler:99.0.0"))
    assertTrue(report.contains("grammar-role=test.grammar.Root"))
    assertTrue(report.contains("catalog-alternative=Root"))

    val novel         = future.copy(nodes = future.nodes.updated(1, future.nodes(1).copy(production = "FutureNovelShape")))
    val shapeFailures = PreparedProductionCatalog
      .prepare(catalog, aggregate(Vector(inventory(novel))), surfaces(catalog))
      .left
      .toOption
      .get
    assertTrue(
      shapeFailures.exists:
        case CatalogValidationError.UncoveredCompilerShape(_, "FutureNovelShape", _, _) => true
        case _                                                                          => false
    )

    val compilerDerivedRole     = GrammarRoleId("FutureNovelShape")
    val compilerDerivedCatalog  = catalog.copy(
      productions = catalog.productions.updated(
        0,
        catalog.productions.head.copy(grammarRoleId = compilerDerivedRole)
      ),
      stableRoles = catalog.stableRoles.copy(grammarRoles = catalog.stableRoles.grammarRoles + compilerDerivedRole)
    )
    val compilerDerivedFailures = PreparedProductionCatalog
      .prepare(compilerDerivedCatalog, aggregate(Vector(inventory(novel))), surfaces(compilerDerivedCatalog))
      .left
      .toOption
      .get
    assertTrue(
      compilerDerivedFailures.contains(
        CatalogValidationError.CompilerDerivedGrammarRole(
          compilerDerivedCatalog.productions.head.id,
          compilerDerivedRole,
          "FutureNovelShape"
        )
      )
    )

    val unknownRole  = GrammarRoleId("test.grammar.future-novel")
    val roleCatalog  = catalog.copy(productions =
      catalog.productions.updated(
        0,
        catalog.productions.head.copy(grammarRoleId = unknownRole)
      )
    )
    val roleFailures = PreparedProductionCatalog
      .prepare(roleCatalog, futureCompiler, surfaces(roleCatalog))
      .left
      .toOption
      .get
    assertTrue(
      roleFailures.contains(CatalogValidationError.UnknownGrammarRole(roleCatalog.productions.head.id, unknownRole))
    )

    val unknownOutput  = PsiOutputRoleId("test.output.future-novel")
    val outputCatalog  = catalog.copy(productions =
      catalog.productions.updated(
        0,
        catalog.productions.head.copy(outputRoleId = Some(unknownOutput))
      )
    )
    val outputFailures = PreparedProductionCatalog
      .prepare(outputCatalog, futureCompiler, surfaces(outputCatalog))
      .left
      .toOption
      .get
    assertTrue(
      outputFailures.contains(
        CatalogValidationError.UnknownOutputRole(outputCatalog.productions.head.id, "self", unknownOutput)
      )
    )

  @Test def runtimePreparationUsesDirectNodeOwnersAndRetainedPositionedOrigins(): Unit =
    val inherited = ProductionInstanceId(
      InventoryKind.Node,
      2L,
      Some(ProductionOccurrenceId(1L, Vector(ParserFieldPathSegment.NamedField("outer"))))
    )
    val direct    = ProductionInstanceLineage.child(
      inherited,
      InventoryKind.Node,
      4L,
      Vector(ParserFieldPathSegment.NamedField("left"))
    )
    assertEquals(
      Some(ProductionOccurrenceId(2L, Vector(ParserFieldPathSegment.NamedField("left")))),
      direct.occurrence
    )

    val positioned = ProductionInstanceId(
      InventoryKind.Positioned,
      3L,
      Some(ProductionOccurrenceId(1L, Vector(ParserFieldPathSegment.NamedField("metadata"))))
    )
    val retained   = ProductionInstanceLineage.child(
      positioned,
      InventoryKind.Node,
      4L,
      Vector(ParserFieldPathSegment.NamedField("right"))
    )
    assertEquals(
      Some(
        ProductionOccurrenceId(
          1L,
          Vector(
            ParserFieldPathSegment.NamedField("metadata"),
            ParserFieldPathSegment.NamedField("right")
          )
        )
      ),
      retained.occurrence
    )
    assertEquals(
      Vector(ParserFieldPathSegment.NamedField("left")),
      ProductionInstanceLineage.relativePath(inherited, direct.occurrence.get)
    )
    assertEquals(
      Vector(ParserFieldPathSegment.NamedField("right")),
      ProductionInstanceLineage.relativePath(positioned, retained.occurrence.get)
    )

  @Test def preparationSelectsSharedChildrenFromTheirConcreteSamePrefixOwnerLineage(): Unit =
    val value                                           = samePrefixOwnersSharedChildSnapshot
    val runtime                                         = inventory(value)
    val generated                                       = completeCatalog(runtime)
    val root                                            = generated.productions.find(_.id == "Root").get
    val owners                                          = generated.productions.filter(_.id == "Owner")
    val leaves                                          = generated.productions.filter(_.id == "Leaf")
    def branch(production: Scala3PsiProduction): String =
      production.pattern.occurrences
        .flatMap(_.context match
          case ContextPattern.ParentWithAncestor(_, _, _, ancestor) => ancestor.path
          case _                                                    => Vector.empty
        )
        .collectFirst { case CatalogPathSegment.NamedField(name @ ("left" | "right")) => name }
        .get
    val selectedLeaves                                  = Vector("left", "right").map: side =>
      val production = leaves.head
      production.copy(
        id = s"Leaf-$side",
        pattern = production.pattern.copy(occurrences = production.pattern.occurrences.filter: occurrence =>
          branch(production.copy(pattern = production.pattern.copy(occurrences = Vector(occurrence)))) == side),
        outputRealizations =
          Vector(OutputRealization(s"realization-$side", Vector.empty, production.effectiveOutputTemplate))
      )
    val owner                                           = owners.head.copy(
      pattern = owners.head.pattern.copy(occurrences = owners.flatMap(_.pattern.occurrences)),
      children =
        owners.head.children.map(_.copy(productionId = "Leaf-left", additionalProductionIds = Set("Leaf-right")))
    )
    val catalog                                         = generated.copy(productions = Vector(root, owner) ++ selectedLeaves)
    val prepared                                        = PreparedProductionCatalog.prepare(catalog, aggregate(Vector(runtime)), surfaces(catalog))
    assertTrue(prepared.left.toOption.toString, prepared.isRight)

    val errors = RuntimeRealizationSelector.validate(catalog, runtime)
    assertTrue(errors.toString, errors.isEmpty)

  @Test def preparationRejectsAmbiguousAndUnknownConcreteScenarioRealizations(): Unit =
    val runtime         = inventory(snapshot("/realizations", 1, Vector.empty))
    val base            = completeCatalog(runtime)
    val root            = base.productions.find(_.id == "Root").get
    val template        = root.effectiveOutputTemplate
    val ambiguous       = base.copy(productions = base.productions.map:
      case production if production.id == root.id =>
        production.copy(outputRealizations =
          Vector(
            OutputRealization("first", Vector.empty, template),
            OutputRealization("second", Vector.empty, template)
          )
        )
      case production                             => production
    )
    val ambiguousErrors = PreparedProductionCatalog
      .prepare(ambiguous, aggregate(Vector(runtime)), surfaces(ambiguous))
      .left
      .toOption
      .get
    assertTrue(ambiguousErrors.exists(_.isInstanceOf[CatalogValidationError.AmbiguousScenarioRealization]))

    val unknown       = base.copy(productions = base.productions.map:
      case production if production.id == root.id =>
        production.copy(outputRealizations =
          Vector(
            OutputRealization(
              "missing",
              Vector(
                ChildOutcomeCondition(
                  "child",
                  ChildOccurrenceSelector.First,
                  ChildOutcomeExpectation.Production("Root")
                )
              ),
              template
            )
          )
        )
      case production                             => production
    )
    val unknownErrors = PreparedProductionCatalog
      .prepare(unknown, aggregate(Vector(runtime)), surfaces(unknown))
      .left
      .toOption
      .get
    assertTrue(unknownErrors.exists(_.isInstanceOf[CatalogValidationError.UnknownScenarioRealization]))

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
        .contains(CatalogValidationError.UnrepresentedCatalogProduction("stale", stale.grammarRoleId))
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
    val catalog       = Scala3PsiProductionCatalog(Vector(production), focusedRoleInventory(Vector(production)))
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
        .contains(CatalogValidationError.UnrepresentedCatalogProduction(root.id, root.grammarRoleId))
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
        .contains(CatalogValidationError.UnrepresentedCatalogProduction(root.id, root.grammarRoleId))
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
          OccurrenceCardinality.Repeated(-1, None),
          PsiOutputRoleId.SourceTerminal
        )
      ) :+ TerminalDeclaration(
        "gap",
        TerminalIntervalSelector.ChildGap("duplicate", "absent"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
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
      CatalogValidationError.EmptyRecoveryAlternatives(root.id),
      CatalogValidationError.UnknownTerminalChildRole(root.id, "absent")
    ).foreach(error => assertTrue(error.toString, errors.contains(error)))
    assertFalse(errors.contains(CatalogValidationError.UnknownChildProductionId(root.id, child.id)))

  @Test def validatorRejectsEveryMalformedOutputTemplateCategory(): Unit =
    val compiler                                                                       = inventory(snapshot("/templates", 1, Vector.empty))
    val base                                                                           = completeCatalog(compiler)
    val root                                                                           = base.productions.find(_.pattern.prefix == "Root").get
    val self                                                                           = root.effectiveOutputTemplate.composites.head
    def errors(template: LocalOutputCompositeTemplate): Vector[CatalogValidationError] =
      val updated = root.copy(outputTemplate = Some(template))
      val catalog = base.copy(productions = base.productions.map(p => if p.id == root.id then updated else p))
      Scala3PsiProductionCatalogValidator.validate(catalog, compiler, surfaces(catalog))

    assertTrue(
      errors(LocalOutputCompositeTemplate(Vector(self, self), Map("child" -> Some("self"))))
        .contains(CatalogValidationError.DuplicateOutputId(root.id, "self"))
    )
    assertTrue(
      errors(LocalOutputCompositeTemplate(Vector(self.copy(parentId = Some("missing"))), Map("child" -> Some("self"))))
        .contains(CatalogValidationError.UnknownOutputParent(root.id, "self", "missing"))
    )
    val cycle             = Vector(self.copy(id = "a", parentId = Some("b")), self.copy(id = "b", parentId = Some("a")))
    assertTrue(
      errors(LocalOutputCompositeTemplate(cycle, Map("child" -> Some("a"))))
        .contains(CatalogValidationError.CyclicOutputParent(root.id, "a"))
    )
    assertTrue(
      errors(LocalOutputCompositeTemplate(Vector(self), Map.empty))
        .contains(CatalogValidationError.MissingChildMountRole(root.id, "child"))
    )
    assertTrue(
      errors(LocalOutputCompositeTemplate(Vector(self), Map("child" -> Some("self"), "extra" -> None)))
        .contains(CatalogValidationError.ExtraChildMountRole(root.id, "extra"))
    )
    assertTrue(
      errors(LocalOutputCompositeTemplate(Vector(self), Map("child" -> Some("missing"))))
        .contains(CatalogValidationError.UnknownChildMountParent(root.id, "child", "missing"))
    )
    val invalidBoundary   = OutputBoundary.Advance(OutputBoundary.ProductionStart(), -1)
    val unsupported       = OutputRangeDeclaration.BoundaryDerived(invalidBoundary, OutputBoundary.ProductionEnd())
    assertTrue(
      errors(LocalOutputCompositeTemplate(Vector(self.copy(range = unsupported)), Map("child" -> Some("self"))))
        .contains(
          CatalogValidationError.InvalidOutputBoundary(root.id, "self", invalidBoundary, "negative boundary advance")
        )
    )
    val emptyDelimiters   = OutputBoundary.EvidenceBoundaryAfterChild(
      "child",
      ChildOccurrenceSelector.First,
      "child",
      ChildOccurrenceSelector.First,
      Vector.empty,
      PositionProvenancePolicy.SourceDerivedOnly
    )
    val delimiterRange    = OutputRangeDeclaration.BoundaryDerived(emptyDelimiters, OutputBoundary.ProductionEnd())
    assertTrue(
      errors(LocalOutputCompositeTemplate(Vector(self.copy(range = delimiterRange)), Map("child" -> Some("self"))))
        .contains(
          CatalogValidationError.InvalidOutputBoundary(
            root.id,
            "self",
            emptyDelimiters,
            "expected delimiters must be nonempty"
          )
        )
    )
    val packageRange      = OutputRangeDeclaration.CompilerPositionWithBodyLayoutOrEndMarker(
      "missing-header",
      Some("missing-body"),
      ClosedSourceLexicalKind.LeftBrace,
      ClosedSourceLexicalKind.RightBrace,
      ClosedSourceLexicalKind.Colon
    )
    val packageErrors     = errors(
      LocalOutputCompositeTemplate(Vector(self.copy(range = packageRange)), Map("child" -> Some("self")))
    )
    assertTrue(
      packageErrors.contains(CatalogValidationError.UnknownOutputRangeChildRole(root.id, "self", "missing-header"))
    )
    assertTrue(
      packageErrors.contains(CatalogValidationError.UnknownOutputRangeChildRole(root.id, "self", "missing-body"))
    )
    val unknownTerminal   = TerminalDeclaration(
      "missing-output-terminal",
      TerminalIntervalSelector.LocalOutput("missing-output"),
      TerminalLeafTarget.Parent,
      OccurrenceCardinality.Optional,
      PsiOutputRoleId.SourceTerminal
    )
    val unknownOutput     = root.copy(terminals = Vector(unknownTerminal))
    val unknownCatalog    =
      base.copy(productions = base.productions.map(p => if p.id == root.id then unknownOutput else p))
    assertTrue(
      Scala3PsiProductionCatalogValidator
        .validate(unknownCatalog, compiler, surfaces(unknownCatalog))
        .contains(CatalogValidationError.UnknownTerminalOutput(root.id, unknownTerminal.id, "missing-output"))
    )
    val siblingRoots      = Vector(self.copy(id = "left"), self.copy(id = "right"))
    assertTrue(
      errors(LocalOutputCompositeTemplate(siblingRoots, Map("child" -> Some("left"))))
        .contains(CatalogValidationError.OverlappingCompilerPositionSiblings(root.id, None, "left", "right"))
    )
    val parentAndSiblings = Vector(
      self.copy(id = "parent"),
      self.copy(id = "left", parentId = Some("parent")),
      self.copy(id = "right", parentId = Some("parent"))
    )
    assertTrue(
      errors(LocalOutputCompositeTemplate(parentAndSiblings, Map("child" -> Some("left"))))
        .contains(
          CatalogValidationError.OverlappingCompilerPositionSiblings(root.id, Some("parent"), "left", "right")
        )
    )
    val sharedAccessor    = AccessorObligation("shared", required = true)
    val wrappers          = Vector(
      self.copy(id = "outer", accessors = Vector(sharedAccessor)),
      self.copy(id = "inner", parentId = Some("outer"), accessors = Vector(sharedAccessor))
    )
    assertFalse(
      errors(LocalOutputCompositeTemplate(wrappers, Map("child" -> Some("inner"))))
        .contains(CatalogValidationError.DuplicateAccessorObligation(root.id, "shared"))
    )

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
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
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
        CatalogValidationError.InvalidSurface(
          claimedRoot.id,
          claimedRoot.outputRoleId.get,
          "helper.surface",
          SurfaceFactKind.Element
        )
      )
    )
    assertTrue(
      kindErrors.contains(
        CatalogValidationError.InvalidSurface(
          claimedRoot.id,
          claimedRoot.outputRoleId.get,
          "method.surface",
          SurfaceFactKind.PublicAccessor
        )
      )
    )

  @Test def coverageReportRendersCapabilityProbedCompatibleTargets(): Unit =
    val runtime  = inventory(snapshot("/report", 1, Vector.empty))
    val base     = completeCatalog(runtime)
    val target   = "org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyIntegerLiteral"
    val catalog  = base.copy(productions =
      base.productions.head.copy(targetSurfaceId = target, targetRequirement = TargetRequirement.Compatible) +:
        base.productions.tail
    )
    val compiler = aggregate(Vector(runtime))
    val surface  = surfaces(base)
    val report   = Scala3PsiProductionCoverageReport.markdown(catalog, compiler, surface)
    assertTrue(
      report,
      report.contains(s"`Element:$target` — **Available:catalog-referenced:${catalog.productions.head.id}**")
    )
    assertTrue(report.contains("grammar-role=test.grammar.Root"))
    assertTrue(report.contains("output-roles=test.output.Root"))
    assertTrue(report.contains("catalog-alternative=Root"))
    assertTrue(report.contains("compiler-shape=Node.Root"))
    assertTrue(report.contains(s"host-targets=$target"))
    assertTrue(report.contains("providers=Compatible"))
    assertEquals(
      report,
      Scala3PsiProductionCoverageReport.markdown(
        catalog.copy(productions = catalog.productions.reverse),
        aggregate(Vector(runtime.copy(shapes = runtime.shapes.reverse, nodes = runtime.nodes.reverse))),
        surface.copy(rows = surface.rows.reverse)
      )
    )

  @Test def sharedTransparentLoweringMergesTwoProductsAndCoLocatedEventsIntoOneClosedRole(): Unit =
    val value             = sharedLoweringSnapshot
    val runtime           = inventory(value)
    val catalog           = sharedLoweringCatalog
    val evidence          = ProvisionalSourceEvidencePlanner.plan(value).toOption.get
    val compiler          = aggregate(Vector(runtime))
    val surface           = sharedLoweringSurfaces
    val first             = planned(value, evidence, catalog, compiler, surface)
      .fold(failure => throw new AssertionError(failure.toString), identity)
    val reorderedRuntime  = runtime.copy(shapes = runtime.shapes.reverse, nodes = runtime.nodes.reverse)
    val reorderedCompiler = aggregate(Vector(reorderedRuntime))
    val second            = planned(
      value,
      evidence,
      catalog.copy(productions = catalog.productions.reverse),
      reorderedCompiler,
      surface.copy(rows = surface.rows.reverse)
    ).fold(failure => throw new AssertionError(failure.toString), identity)
    assertArrayEquals(compiler.canonicalBytes, reorderedCompiler.canonicalBytes)
    assertEquals(first, second)
    assertEquals(
      Set(SharedProductGrammarRole),
      catalog.productions
        .filter(production => Set("ExactLeft", "ExactRight")(production.pattern.prefix))
        .map(_.grammarRoleId)
        .toSet
    )
    assertTrue(catalog.productions.find(_.id == "exact-root").get.effectiveOutputTemplate.composites.isEmpty)

    assertEquals(value.sourceText, first.lexicalContract.reconstruct(value.sourceText))
    assertEquals(
      value.sourceText,
      first.physicalLeafOwnership
        .sortBy(leaf => (leaf.start, leaf.end))
        .map(leaf => value.sourceText.substring(leaf.start, leaf.end))
        .mkString
    )
    assertEquals(Vector((0, 1), (1, 2)), first.physicalLeafOwnership.map(leaf => leaf.start -> leaf.end))
    assertEquals(evidence.atoms.map(_.id), first.physicalLeafOwnership.map(_.atomId))
    assertEquals(first.physicalLeafOwnership.size, first.physicalLeafOwnership.map(_.atomId).distinct.size)
    assertEquals(Vector(2L, 3L), first.physicalLeafOwnership.map(_.sourceOwner.valueId))
    assertEquals(1, first.composites.size)
    assertEquals(PcSourceRange(0, 2), first.composites.head.range)
    assertEquals("exact-left-product", first.composites.head.productionId)
    assertTrue(first.composites.head.children.isEmpty)
    assertTrue(
      first.physicalLeafOwnership.forall(_.owner == PhysicalLeafOwner.Composite(first.composites.head.instance))
    )

    val coLocatedEvents = evidence.structural.collect:
      case event @ StructuralSourceEvidence(
            SourceEvidenceEventId.Positioned(id @ (10L | 11L)),
            _,
            ParserNodePosition.Positioned(PcSourceRange(1, 1), 1, ParserPositionProvenance.SourceDerived)
          ) =>
        id -> event.id
    assertEquals(Vector(10L, 11L), coLocatedEvents.map(_._1).sorted)
    assertEquals(2, coLocatedEvents.map(_._2).distinct.size)
    assertEquals(coLocatedEvents.map(_._2).toSet, first.structuralEvidenceOwnership.map(_.eventId).toSet)
    assertEquals(
      first.structuralEvidenceOwnership.size,
      first.structuralEvidenceOwnership.map(_.eventId).distinct.size
    )
    assertTrue(first.structuralEvidenceOwnership.forall(_.owner.role == SharedOutputRole))

    assertEquals(
      Vector(PlannedTargetIdentity.OutputRole(SharedOutputRole)),
      first.targetAssertions.map(_.targetIdentity)
    )
    assertEquals(
      Vector(PlannedAccessorAssertion(first.composites.head.instance, "test.host.shared.accessor", required = true)),
      first.accessorAssertions
    )
    assertEquals(
      Vector(
        PlannedStubAssertion(
          first.composites.head.instance,
          "test.host.shared.stub",
          "test.host.shared.serializer",
          Vector("test.host.shared.index"),
          "test.host.shared.stub-navigation"
        )
      ),
      first.stubAssertions
    )
    assertEquals(
      Vector(PlannedNavigationAssertion(first.composites.head.instance, NavigationObligation.Self)),
      first.navigationAssertions
    )
    assertTrue(first.virtualLayout.isEmpty)

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
    assertEquals(
      Vector(PsiOutputRoleId("test.output.Root"), PsiOutputRoleId("test.output.Child")),
      first.targetAssertions.collect:
        case PlannedTargetAssertion(_, PlannedTargetIdentity.OutputRole(outputRoleId), _) => outputRoleId
    )
    assertTrue(
      first.targetAssertions.forall(_.targetIdentity.isInstanceOf[PlannedTargetIdentity.OutputRole])
    )
    assertEquals(Vector.empty, first.virtualLayout)
    assertEquals(Vector.empty, first.accessorAssertions)
    assertEquals(Vector.empty, first.stubAssertions)
    val leaf      = first.physicalLeafOwnership.head
    val child     = first.composites(1).instance
    assertEquals(
      (SourceAtomId(0, 0), 0, 1, PhysicalLeafOwner.Composite(child), "contents"),
      (leaf.atomId, leaf.start, leaf.end, leaf.owner, leaf.terminalId)
    )
    assertEquals("x", value.sourceText.substring(leaf.start, leaf.end))
    assertEquals(
      Vector(ParserFieldPathSegment.NamedField("children"), ParserFieldPathSegment.RepeatedIndex(0)),
      first.composites.head.children.head.fieldPath
    )

  @Test def wholeFilePlanningLowersLocalParentsAndTransparentOutputs(): Unit =
    val value     = snapshot("/outputs", 1, Vector.empty)
    val compiler  = inventory(value)
    val base      = completeCatalog(compiler)
    val aggregate = this.aggregate(Vector(compiler))
    val evidence  = ProvisionalSourceEvidencePlanner.plan(value).toOption.get
    val root      = base.productions.find(_.id == "Root").get
    val self      = root.effectiveOutputTemplate.composites.head

    val wrappedRoot    = root.copy(outputTemplate =
      Some(
        LocalOutputCompositeTemplate(
          Vector(self.copy(id = "outer"), self.copy(id = "inner", parentId = Some("outer"))),
          Map("child" -> Some("inner"))
        )
      )
    )
    val wrappedCatalog = base.copy(productions = base.productions.map(p => if p.id == root.id then wrappedRoot else p))
    val wrapped        = planned(value, evidence, wrappedCatalog, aggregate, surfaces(wrappedCatalog))
      .fold(error => throw new AssertionError(error.toString), identity)
    val outer          = wrapped.composites.find(_.instance.localOutputId == "outer").get
    val inner          = wrapped.composites.find(_.instance.localOutputId == "inner").get
    assertEquals(Vector(inner.instance), outer.children.map(_.child))
    assertEquals("Child", wrapped.composites.find(_.instance == inner.children.head.child).get.productionId)
    assertEquals(3, wrapped.targetAssertions.count(_.owner.isInstanceOf[TargetAssertionOwner.Composite]))
    assertEquals(
      value.sourceText,
      wrapped.physicalLeafOwnership
        .sortBy(_.start)
        .map(leaf => value.sourceText.substring(leaf.start, leaf.end))
        .mkString
    )
    assertEquals(evidence.structural.map(_.id), wrapped.structuralEvidenceOwnership.map(_.eventId))

    val transparentRoot    =
      root.copy(outputTemplate = Some(LocalOutputCompositeTemplate(Vector.empty, Map("child" -> None))))
    val transparentCatalog =
      base.copy(productions = base.productions.map(p => if p.id == root.id then transparentRoot else p))
    val transparent        = planned(value, evidence, transparentCatalog, aggregate, surfaces(transparentCatalog))
      .fold(error => throw new AssertionError(error.toString), identity)
    assertEquals(Vector("Child"), transparent.composites.map(_.productionId))
    assertEquals(1, transparent.targetAssertions.count(_.owner.isInstanceOf[TargetAssertionOwner.Composite]))
    assertEquals(
      value.sourceText,
      transparent.physicalLeafOwnership
        .sortBy(_.start)
        .map(leaf => value.sourceText.substring(leaf.start, leaf.end))
        .mkString
    )
    assertEquals(evidence.structural.map(_.id), transparent.structuralEvidenceOwnership.map(_.eventId))

  @Test def emptyTransparentTemplatesValidateMountsAndAdvanceOverflowFailsClosed(): Unit =
    val value     = snapshot("/empty-template", 1, Vector.empty)
    val compiler  = inventory(value)
    val base      = completeCatalog(compiler)
    val aggregate = this.aggregate(Vector(compiler))
    val root      = base.productions.find(_.id == "Root").get
    val missing   = base.copy(productions = base.productions.map:
      case production if production.id == root.id =>
        production.copy(outputTemplate = Some(LocalOutputCompositeTemplate(Vector.empty, Map.empty)))
      case production                             => production
    )
    val errors    = Scala3PsiProductionCatalogValidator.validateExecutable(missing, compiler, surfaces(missing))
    assertTrue(errors.contains(CatalogValidationError.MissingChildMountRole(root.id, "child")))

    val output   = root.effectiveOutputTemplate.composites.head
    val overflow = base.copy(productions = base.productions.map:
      case production if production.id == root.id =>
        production.copy(outputTemplate =
          Some(
            root.effectiveOutputTemplate.copy(composites =
              Vector(
                output.copy(
                  range = OutputRangeDeclaration.BoundaryDerived(
                    OutputBoundary.ProductionStart(PositionProvenancePolicy.PositionedIncludingSynthetic),
                    OutputBoundary.Advance(
                      OutputBoundary.ProductionStart(PositionProvenancePolicy.PositionedIncludingSynthetic),
                      Int.MaxValue
                    )
                  )
                )
              )
            )
          )
        )
      case production                             => production
    )
    val failure  = planned(
      value,
      ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
      overflow,
      aggregate,
      surfaces(overflow)
    ).left.toOption.get
    assertTrue(failure.isInstanceOf[WholeFilePlanningFailure.OutputBoundaryResolutionFailed])

    val missingDelimiterBoundary = OutputBoundary.EvidenceBoundaryAfterChild(
      "child",
      ChildOccurrenceSelector.First,
      "child",
      ChildOccurrenceSelector.First,
      Vector("{"),
      PositionProvenancePolicy.SourceDerivedOnly
    )
    val missingDelimiter         = base.copy(productions = base.productions.map:
      case production if production.id == root.id =>
        production.copy(outputTemplate =
          Some(
            root.effectiveOutputTemplate.copy(composites =
              Vector(
                output.copy(
                  range = OutputRangeDeclaration.BoundaryDerived(
                    missingDelimiterBoundary,
                    OutputBoundary.ProductionEnd()
                  )
                )
              )
            )
          )
        )
      case production                             => production
    )
    val delimiterFailure         = planned(
      value,
      ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
      missingDelimiter,
      aggregate,
      surfaces(missingDelimiter)
    ).left.toOption.get
    assertTrue(delimiterFailure.isInstanceOf[WholeFilePlanningFailure.OutputBoundaryResolutionFailed])

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
    assertEquals(WholeFilePlanningFailure.UnownedSourceAtom(SourceAtomId(0, 0), 0, 1), failure(unowned))

    val parentFallback     = base.copy(productions =
      base.productions.map(p =>
        if p.id == root.id then
          p.copy(terminals =
            Vector(
              TerminalDeclaration(
                "contents",
                TerminalIntervalSelector.WholeProduction,
                TerminalLeafTarget.Parent,
                OccurrenceCardinality.ExactlyOne,
                PsiOutputRoleId.SourceTerminal
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
        .find(composite =>
          parentFallbackPlan.physicalLeafOwnership.head.owner == PhysicalLeafOwner.Composite(composite.instance)
        )
        .get
        .productionId
    )

    val trailingSource   = "x\n"
    val trailingValue    = value.copy(
      sourceText = trailingSource,
      sourceDigest = ParserSyntaxSnapshot.digest(trailingSource),
      sourceLength = trailingSource.length
    )
    val trailingCompiler = inventory(trailingValue)
    val wholeSource      = parentFallback.copy(productions =
      parentFallback.productions.map(production =>
        if production.id == root.id then
          production.copy(terminals = production.terminals.map(_.copy(selector = TerminalIntervalSelector.WholeSource)))
        else production
      )
    )
    val trailingPlan     = planned(
      trailingValue,
      ProvisionalSourceEvidencePlanner.plan(trailingValue).toOption.get,
      wholeSource,
      this.aggregate(Vector(trailingCompiler)),
      surfaces(wholeSource)
    ).fold(error => throw new AssertionError(error.toString), identity)
    val trailingLeaf     = trailingPlan.physicalLeafOwnership.last
    assertEquals("\n", trailingSource.substring(trailingLeaf.start, trailingLeaf.end))
    assertEquals(PhysicalLeafOwner.FileRoot, trailingLeaf.owner)

    val missingChildTerminal = wholeSource.copy(productions =
      wholeSource.productions.map(production =>
        if production.id == child.id then production.copy(terminals = Vector.empty) else production
      )
    )
    assertEquals(
      WholeFilePlanningFailure.UnownedSourceAtom(SourceAtomId(0, 0), 0, 1),
      planned(
        trailingValue,
        ProvisionalSourceEvidencePlanner.plan(trailingValue).toOption.get,
        missingChildTerminal,
        this.aggregate(Vector(trailingCompiler)),
        surfaces(missingChildTerminal)
      ).left.toOption.get
    )

    val emptySource   = ""
    val emptyPosition = ParserNodePosition.Positioned(
      PcSourceRange(0, 0),
      0,
      ParserPositionProvenance.SourceDerived
    )
    val emptyValue    = value.copy(
      sourceText = emptySource,
      sourceDigest = ParserSyntaxSnapshot.digest(emptySource),
      sourceLength = 0,
      nodes = Vector(value.nodes.head.copy(fields = Vector.empty, position = emptyPosition))
    )
    val emptyCompiler = inventory(emptyValue)
    val emptyBase     = completeCatalog(emptyCompiler)
    val emptyCatalog  = emptyBase.copy(productions =
      emptyBase.productions.map(production =>
        production.copy(terminals =
          Vector(
            TerminalDeclaration(
              "whole-source",
              TerminalIntervalSelector.WholeSource,
              TerminalLeafTarget.Parent,
              OccurrenceCardinality.ExactlyOne,
              PsiOutputRoleId.SourceTerminal,
              ownsStructuralEvidence = Some(true)
            )
          )
        )
      )
    )
    val emptyPlan     = planned(
      emptyValue,
      ProvisionalSourceEvidencePlanner.plan(emptyValue).toOption.get,
      emptyCatalog,
      this.aggregate(Vector(emptyCompiler)),
      surfaces(emptyCatalog)
    ).fold(error => throw new AssertionError(error.toString), identity)
    assertTrue(emptyPlan.physicalLeafOwnership.isEmpty)

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

    val grouped     = base.copy(productions =
      base.productions.map(p =>
        if p.id == root.id then
          p.copy(children = p.children.map(_.copy(cardinality = ChildCardinality.Grouped(1, None))))
        else p
      )
    )
    val groupedPlan = planned(value, evidence, grouped, aggregate, surfaces(grouped))
      .fold(error => throw new AssertionError(error.toString), identity)
    assertEquals(Vector("Root", "Child"), groupedPlan.composites.map(_.productionId))

    val groupedMinimum = grouped.copy(productions =
      grouped.productions.map(p =>
        if p.id == root.id then
          p.copy(children = p.children.map(_.copy(cardinality = ChildCardinality.Grouped(2, None))))
        else p
      )
    )
    assertTrue(failure(groupedMinimum).isInstanceOf[WholeFilePlanningFailure.ChildCardinalityMismatch])

    val groupedMultipleRoots        = grouped.copy(productions = grouped.productions.map: p =>
      if p.id == child.id then
        val template = p.effectiveOutputTemplate
        p.copy(outputTemplate =
          Some(template.copy(composites = template.composites :+ template.composites.head.copy(id = "second")))
        )
      else p)
    val groupedMultipleRootsFailure = failure(groupedMultipleRoots)
    assertEquals(
      WholeFilePlanningFailure.InvalidCatalog(
        Vector(CatalogValidationError.OverlappingCompilerPositionSiblings("Child", None, "second", "self"))
      ),
      groupedMultipleRootsFailure
    )

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

  @Test def wholeFilePlanningRejectsInactiveUnsupportedOrRecoveredCompilerDescendants(): Unit =
    val value                                                                  = snapshot("/inactive-unsupported", 1, Vector.empty)
    val compiler                                                               = inventory(value)
    val base                                                                   = completeCatalog(compiler)
    val inactiveChild                                                          = base.copy(productions = base.productions.map: production =>
      if production.id == "Root" then
        production.copy(
          dispositions = Vector(FieldDisposition("children", FieldDispositionKind.SemanticOnly)),
          children = Vector.empty,
          terminals = Vector(
            TerminalDeclaration(
              "source",
              TerminalIntervalSelector.WholeSource,
              TerminalLeafTarget.Parent,
              OccurrenceCardinality.ExactlyOne,
              PsiOutputRoleId.SourceTerminal
            )
          )
        )
      else production)
    def failure(catalog: Scala3PsiProductionCatalog): WholeFilePlanningFailure =
      planned(
        value,
        ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
        catalog,
        aggregate(Vector(compiler)),
        surfaces(catalog)
      ).left.toOption.get

    val unsupported         = inactiveChild.copy(productions = inactiveChild.productions.map: production =>
      if production.id == "Child" then
        production.copy(
          dispositions = Vector(FieldDisposition("inactive", FieldDispositionKind.Unsupported)),
          pattern = production.pattern.copy(fields =
            Vector(CompilerFieldPattern("inactive", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)))
          )
        )
      else production)
    val unsupportedValue    = value.copy(nodes =
      value.nodes.updated(
        1,
        value
          .nodes(1)
          .copy(fields =
            Vector(
              ParserSyntaxField(
                "inactive",
                ParserFieldValue.Repeated(Vector.empty),
                Some(ParserDeclaredShape.Repeated(ParserDeclaredShape.Node))
              )
            )
          )
      )
    )
    val unsupportedCompiler = inventory(unsupportedValue)
    assertEquals(
      WholeFilePlanningFailure.UnsupportedFieldDisposition(
        ProductionInstanceId(
          InventoryKind.Node,
          2,
          Some(
            ProductionOccurrenceId(
              1,
              Vector(ParserFieldPathSegment.NamedField("children"), ParserFieldPathSegment.RepeatedIndex(0))
            )
          )
        ),
        "inactive"
      ),
      planned(
        unsupportedValue,
        ProvisionalSourceEvidencePlanner.plan(unsupportedValue).toOption.get,
        unsupported,
        aggregate(Vector(unsupportedCompiler)),
        surfaces(unsupported)
      ).left.toOption.get
    )

    val recovered = inactiveChild.copy(productions = inactiveChild.productions.map: production =>
      if production.id == "Child" then
        production.copy(recovery = RecoveryPolicy.DiagnosticBound(ParserDiagnosticSeverity.Error, Vector("recovered")))
      else production)
    failure(recovered) match
      case WholeFilePlanningFailure.UnsupportedRecovery(owner, _) =>
        assertEquals(2, owner.valueId)
        assertTrue(owner.occurrence.nonEmpty)
      case other                                                  => fail(other.toString)

  @Test def transparentSiblingLeafProvenanceDoesNotBecomeFileRootAncestry(): Unit =
    val baseValue       = snapshot("/transparent-siblings", 1, Vector.empty)
    val root            = baseValue.nodes.head.copy(fields =
      Vector(
        ParserSyntaxField("left", ParserFieldValue.Node(2)),
        ParserSyntaxField("right", ParserFieldValue.Node(3))
      )
    )
    val child           = baseValue
      .nodes(1)
      .copy(occurrences = Vector(ParserNodeOccurrence(1, Vector(ParserFieldPathSegment.NamedField("left")))))
    val sibling         = baseValue
      .nodes(1)
      .copy(
        id = 3,
        production = "Sibling",
        occurrences = Vector(
          ParserNodeOccurrence(
            1,
            Vector(ParserFieldPathSegment.NamedField("right"))
          )
        )
      )
    val value           = baseValue.copy(nodes = Vector(root, child, sibling))
    val compiler        = inventory(value)
    val base            = completeCatalog(compiler)
    val childProduction = base.productions.find(_.id == "Child").get
    val catalog         = base.copy(productions = base.productions.map: production =>
      if production.id == childProduction.id then
        production.copy(outputTemplate = Some(LocalOutputCompositeTemplate(Vector.empty, Map.empty)))
      else if production.id == "Root" then
        production.copy(
          dispositions = Vector("left", "right").map(FieldDisposition(_, FieldDispositionKind.Child)),
          children = Vector(
            ChildDeclaration("left", "left", ChildCardinality.ExactlyOne, "Child"),
            ChildDeclaration("right", "right", ChildCardinality.ExactlyOne, "Sibling")
          )
        )
      else production)
    val result          = planned(
      value,
      ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
      catalog,
      aggregate(Vector(compiler)),
      surfaces(catalog).copy(rows = surfaces(catalog).rows.filterNot(_.id == childProduction.targetSurfaceId))
    )
    val conflict        = result.left.toOption.get match
      case value: WholeFilePlanningFailure.ConflictingSourceAtomOwners => value
      case failure                                                     => throw new AssertionError(failure.toString)
    assertEquals(Vector(2L, 3L), conflict.owners.map(_._1.valueId).sorted)

  @Test def wholeFilePlanningRejectsMultiplyParentedDescendants(): Unit =
    val value     = sharedDescendantSnapshot
    val compiler  = inventory(value)
    val leaf      = compiler.shapes.find(_.id == 3).get
    assertEquals(
      Set("left", "right"),
      leaf.contexts
        .flatMap(_.ancestors.headOption)
        .flatMap(_.path.collect { case CatalogPathSegment.NamedField(name) => name }.lastOption)
        .toSet
    )
    val catalog   = completeCatalog(compiler)
    val aggregate = this.aggregate(Vector(compiler))
    Vector(
      value,
      value.copy(nodes = value.nodes.updated(1, value.nodes(1).copy(occurrences = value.nodes(1).occurrences.reverse)))
    )
      .foreach: candidate =>
        val result = planned(
          candidate,
          ProvisionalSourceEvidencePlanner.plan(candidate).toOption.get,
          catalog,
          aggregate,
          surfaces(catalog)
        )
        assertTrue(
          result.toString,
          result.left.toOption.get.isInstanceOf[WholeFilePlanningFailure.MultiplyConsumedChildReference]
        )

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
              OccurrenceCardinality.ExactlyOne,
              PsiOutputRoleId.SourceTerminal
            )
          )
        )
      case production if production.id == child.id =>
        production.copy(
          outputTemplate = Some(LocalOutputCompositeTemplate(Vector.empty, Map.empty)),
          terminals = Vector(
            TerminalDeclaration(
              "optional-token",
              TerminalIntervalSelector.WholeProduction,
              TerminalLeafTarget.Token("token.optional"),
              OccurrenceCardinality.Optional,
              PsiOutputRoleId.SourceTerminal,
              ownsStructuralEvidence = Some(true)
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
    assertFalse(
      plan.targetAssertions.exists(_.targetIdentity match
        case PlannedTargetIdentity.TokenRole(_, "token.optional") => true
        case _                                                    => false
      )
    )
    assertFalse(plan.physicalLeafOwnership.exists(_.terminalId == "optional-token"))

    val unownedCatalog = catalog.copy(productions =
      catalog.productions.map(production =>
        production.copy(terminals = production.terminals.map(_.copy(ownsStructuralEvidence = Some(false))))
      )
    )
    assertEquals(
      WholeFilePlanningFailure.FinalSourceEvidenceFailures(
        Vector(FinalSourceEvidenceFailure.UnownedEvent(SourceEvidenceEventId.Node(2)))
      ),
      planned(
        value,
        ProvisionalSourceEvidencePlanner.plan(value).toOption.get,
        unownedCatalog,
        this.aggregate(Vector(compiler)),
        surface
      ).left.toOption.get
    )

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
    val productions = compiler.shapes.map: shape =>
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
        id = shape.prefix,
        grammarRoleId = GrammarRoleId(s"test.grammar.${shape.prefix}"),
        pattern = CompilerProductionPattern(
          shape.kind,
          shape.prefix,
          shape.patternFields,
          (if shape.contexts.isEmpty then Vector(ContextPattern.Root)
           else
             shape.contexts.map(context =>
               context.ancestors.headOption match
                 case Some(ancestor) =>
                   ContextPattern.ParentWithAncestor(
                     context.ownerKind,
                     context.ownerPrefix,
                     context.path,
                     ancestor
                   )
                 case None           => ContextPattern.Parent(context.ownerKind, context.ownerPrefix, context.path)
             )
          )
            .map(CompilerProductionContextPattern(_, shape.sourceClassification))
        ),
        dispositions = childField.toVector.map(FieldDisposition(_, FieldDispositionKind.Child)),
        children = childField.toVector.flatMap(field =>
          childProduction.map(production =>
            ChildDeclaration("child", field, ChildCardinality.Repeated(0, None), production)
          )
        ),
        terminals =
          if childField.isEmpty then
            Vector(
              TerminalDeclaration(
                "contents",
                TerminalIntervalSelector.WholeProduction,
                TerminalLeafTarget.Parent,
                OccurrenceCardinality.ExactlyOne,
                PsiOutputRoleId.SourceTerminal
              )
            )
          else Vector.empty,
        layouts = Vector(LayoutAlternative.None),
        recovery = RecoveryPolicy.Reject,
        targetSurfaceId = s"element.${shape.prefix}",
        targetRequirement = TargetRequirement.Compatible,
        accessors = Vector.empty,
        persistence = PersistenceObligations.NotApplicable,
        outputRoleId = Some(PsiOutputRoleId(s"test.output.${shape.prefix}"))
      )
    Scala3PsiProductionCatalog(productions, focusedRoleInventory(productions))

  private def focusedRoleInventory(productions: Vector[Scala3PsiProduction]): StableRoleInventory =
    StableRoleInventory(
      productions.map(_.grammarRoleId).toSet,
      productions
        .flatMap(production =>
          production.terminals.map(_.outputRoleId) ++
            production.effectiveOutputRealizations.flatMap(_.template.composites.map(_.outputRoleId))
        )
        .toSet
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

  private def sharedLoweringSnapshot: ParserSyntaxSnapshot =
    val base                                                                         = snapshot("/shared-lowering", 1, Vector.empty)
    val root                                                                         = ParserSyntaxNode(
      1,
      "ExactRoot",
      Vector(
        ParserSyntaxField(
          "products",
          ParserFieldValue.Repeated(Vector(ParserFieldValue.Node(2), ParserFieldValue.Node(3)))
        )
      ),
      ParserNodePosition.Positioned(PcSourceRange(0, 2), 0, ParserPositionProvenance.SourceDerived),
      Vector.empty
    )
    def product(id: Long, production: String, start: Int, eventId: Long, index: Int) =
      ParserSyntaxNode(
        id,
        production,
        Vector(ParserSyntaxField("event", ParserFieldValue.Positioned(eventId))),
        ParserNodePosition.Positioned(
          PcSourceRange(start, start + 1),
          start,
          ParserPositionProvenance.SourceDerived
        ),
        Vector(
          ParserNodeOccurrence(
            1,
            Vector(ParserFieldPathSegment.NamedField("products"), ParserFieldPathSegment.RepeatedIndex(index))
          )
        )
      )
    def event(id: Long, owner: Long)                                                 = ParserPositionedSyntax(
      id,
      "ExactEvent",
      Vector.empty,
      ParserNodePosition.Positioned(PcSourceRange(1, 1), 1, ParserPositionProvenance.SourceDerived),
      Vector(ParserPositionedOccurrence(owner, Vector(ParserFieldPathSegment.NamedField("event"))))
    )
    val source                                                                       = "xy"
    base.copy(
      sourceText = source,
      sourceDigest = ParserSyntaxSnapshot.digest(source),
      sourceLength = source.length,
      nodes = Vector(root, product(2, "ExactLeft", 0, 10, 0), product(3, "ExactRight", 1, 11, 1)),
      positioned = Vector(event(10, 2), event(11, 3))
    )

  private def sharedLoweringCatalog: Scala3PsiProductionCatalog =
    val sourceReachable                     = SourceClassification.SourceReachable
    val rootPattern                         = CompilerProductionPattern(
      InventoryKind.Node,
      "ExactRoot",
      Vector(CompilerFieldPattern("products", CatalogValuePattern.Repeated(CatalogValuePattern.Node))),
      Vector(CompilerProductionContextPattern(ContextPattern.Root, sourceReachable))
    )
    def childPattern(prefix: String)        = CompilerProductionPattern(
      InventoryKind.Node,
      prefix,
      Vector(CompilerFieldPattern("event", CatalogValuePattern.Positioned)),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "ExactRoot",
            Vector(CatalogPathSegment.NamedField("products"), CatalogPathSegment.RepeatedElement)
          ),
          sourceReachable
        )
      )
    )
    val eventPattern                        = CompilerProductionPattern(
      InventoryKind.Positioned,
      "ExactEvent",
      Vector.empty,
      Vector("ExactLeft", "ExactRight").map(owner =>
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            owner,
            Vector(CatalogPathSegment.NamedField("event"))
          ),
          sourceReachable
        )
      )
    )
    val transparent                         = LocalOutputCompositeTemplate(Vector.empty, Map("products" -> None))
    val eventTransparent                    = LocalOutputCompositeTemplate(Vector.empty, Map.empty)
    val root                                = Scala3PsiProduction(
      id = "exact-root",
      grammarRoleId = TransparentRootGrammarRole,
      pattern = rootPattern,
      dispositions = Vector(FieldDisposition("products", FieldDispositionKind.Child)),
      children = Vector(
        ChildDeclaration(
          "products",
          "products",
          ChildCardinality.Grouped(2, Some(2)),
          "exact-left-product",
          Set("exact-right-product")
        )
      ),
      terminals = Vector.empty,
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = "test.host.transparent.root",
      targetRequirement = TargetRequirement.Compatible,
      accessors = Vector.empty,
      persistence = PersistenceObligations.NotApplicable,
      outputTemplate = Some(transparent),
      outputRoleId = None
    )
    def product(id: String, prefix: String) = Scala3PsiProduction(
      id = id,
      grammarRoleId = SharedProductGrammarRole,
      pattern = childPattern(prefix),
      dispositions = Vector(FieldDisposition("event", FieldDispositionKind.Child)),
      children = Vector(ChildDeclaration("event", "event", ChildCardinality.ExactlyOne, "exact-event")),
      terminals = Vector(
        TerminalDeclaration(
          "source",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          SharedOutputRole
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = "test.host.shared.element",
      targetRequirement = TargetRequirement.Compatible,
      accessors = Vector(AccessorObligation("test.host.shared.accessor", required = true)),
      persistence = PersistenceObligations.Required(
        "test.host.shared.stub",
        "test.host.shared.serializer",
        Vector("test.host.shared.index"),
        "test.host.shared.stub-navigation"
      ),
      navigation = Some(NavigationObligation.Self),
      outputRoleId = Some(SharedOutputRole)
    )
    val positioned                          = Scala3PsiProduction(
      id = "exact-event",
      grammarRoleId = StructuralEventGrammarRole,
      pattern = eventPattern,
      dispositions = Vector.empty,
      children = Vector.empty,
      terminals = Vector(
        TerminalDeclaration(
          "event",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.Optional,
          SharedOutputRole,
          ownsStructuralEvidence = Some(true)
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = "test.host.transparent.event",
      targetRequirement = TargetRequirement.Compatible,
      accessors = Vector.empty,
      persistence = PersistenceObligations.NotApplicable,
      outputTemplate = Some(eventTransparent),
      outputRoleId = None
    )
    Scala3PsiProductionCatalog(
      Vector(
        root,
        product("exact-left-product", "ExactLeft"),
        product("exact-right-product", "ExactRight"),
        positioned
      ),
      StableRoleInventory(
        Set(TransparentRootGrammarRole, SharedProductGrammarRole, StructuralEventGrammarRole),
        Set(SharedOutputRole)
      )
    )

  private def sharedLoweringSurfaces: ScalaPsiSurfaceInventory =
    def row(id: String, kind: SurfaceFactKind) = ScalaPsiSurfaceRow(
      id,
      kind,
      None,
      FactStatus.Available,
      SurfaceClassification.Derived
    )
    ScalaPsiSurfaceInventory(
      Vector(
        row("test.host.shared.element", SurfaceFactKind.Element),
        row("test.host.shared.accessor", SurfaceFactKind.PublicAccessor),
        row("test.host.shared.stub", SurfaceFactKind.Stub),
        row("test.host.shared.serializer", SurfaceFactKind.Serializer),
        row("test.host.shared.index", SurfaceFactKind.Index),
        row("test.host.shared.stub-navigation", SurfaceFactKind.Navigation)
      )
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
          ParserFieldValue.Product(
            "Pair",
            Vector(
              ParserSyntaxField("left", ParserFieldValue.Node(2)),
              ParserSyntaxField("right", ParserFieldValue.Node(2))
            )
          )
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
          Vector(
            ParserFieldPathSegment.NamedField("children"),
            ParserFieldPathSegment.NestedProductBoundary("Pair"),
            ParserFieldPathSegment.NamedField("left")
          )
        ),
        ParserNodeOccurrence(
          1,
          Vector(
            ParserFieldPathSegment.NamedField("children"),
            ParserFieldPathSegment.NestedProductBoundary("Pair"),
            ParserFieldPathSegment.NamedField("right")
          )
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

  private def samePrefixOwnersSharedChildSnapshot: ParserSyntaxSnapshot =
    val value                         = snapshot("/same-prefix-owners", 1, Vector.empty)
    val range                         = ParserNodePosition.Positioned(PcSourceRange(0, 1), 0, ParserPositionProvenance.SourceDerived)
    val root                          = ParserSyntaxNode(
      1,
      "Root",
      Vector(
        ParserSyntaxField(
          "owners",
          ParserFieldValue.Product(
            "Pair",
            Vector(
              ParserSyntaxField("left", ParserFieldValue.Node(2)),
              ParserSyntaxField("right", ParserFieldValue.Node(3))
            )
          )
        )
      ),
      range,
      Vector.empty
    )
    def owner(id: Long, side: String) = ParserSyntaxNode(
      id,
      "Owner",
      Vector(ParserSyntaxField("child", ParserFieldValue.Node(4))),
      range,
      Vector(
        ParserNodeOccurrence(
          1,
          Vector(
            ParserFieldPathSegment.NamedField("owners"),
            ParserFieldPathSegment.NestedProductBoundary("Pair"),
            ParserFieldPathSegment.NamedField(side)
          )
        )
      )
    )
    val leaf                          = ParserSyntaxNode(
      4,
      "Leaf",
      Vector.empty,
      range,
      Vector(
        ParserNodeOccurrence(2, Vector(ParserFieldPathSegment.NamedField("child"))),
        ParserNodeOccurrence(3, Vector(ParserFieldPathSegment.NamedField("child")))
      )
    )
    value.copy(nodes = Vector(root, owner(2, "left"), owner(3, "right"), leaf))

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
      ),
      Vector.empty
    )
