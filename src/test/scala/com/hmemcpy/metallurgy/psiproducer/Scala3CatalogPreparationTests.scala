package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import org.junit.Assert.*
import org.junit.Test

private[psiproducer] trait Scala3CatalogPreparationTests extends Scala3PsiProductionCatalogTestSupport:
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
    val pair                                            = generated.productions.find(_.id == "Pair").get
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
    val catalog                                         = generated.copy(productions = Vector(root, pair, owner) ++ selectedLeaves)
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
