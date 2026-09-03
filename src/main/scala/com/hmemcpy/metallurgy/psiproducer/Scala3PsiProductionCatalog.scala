package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.CanonicalByteEncoder

private[metallurgy] final case class Scala3PsiProductionCatalog(
    productions: Vector[Scala3PsiProduction],
    stableRoles: StableRoleInventory,
    productionAlternatives: Vector[ProductionAlternatives] = Vector.empty
)

private[metallurgy] final case class ProductionAlternatives(candidateId: String, fallbackId: String)

private[metallurgy] final case class PersistedSchemaStructure(rows: Vector[String]):
  lazy val canonicalBytes: Array[Byte] = StructuralRows.canonicalBytes(rows)
  lazy val fingerprint: String         = CanonicalByteEncoder.sha256Hex(canonicalBytes)
  def text: String                     = StructuralRows.text(rows)

private[metallurgy] final case class CatalogPlanStructure(rows: Vector[String]):
  lazy val canonicalBytes: Array[Byte] = StructuralRows.canonicalBytes(rows)
  lazy val fingerprint: String         = CanonicalByteEncoder.sha256Hex(canonicalBytes)
  def text: String                     = StructuralRows.text(rows)

private[psiproducer] object StructuralRows:
  def row(kind: String, values: Any*): String =
    (kind +: values.map(value => escape(String.valueOf(value)))).mkString("\t")

  def canonicalBytes(rows: Vector[String]): Array[Byte] =
    val encoder = CanonicalByteEncoder()
    encoder.sequence(rows)(encoder.string)
    encoder.result()

  def text(rows: Vector[String]): String = rows.mkString("\n") + "\n"

  def diff(expected: Vector[String], actual: Vector[String]): String =
    val expectedCounts = expected.groupMapReduce(identity)(_ => 1)(_ + _)
    val actualCounts   = actual.groupMapReduce(identity)(_ => 1)(_ + _)
    val missing        =
      expected.distinct.flatMap(row => Vector.fill((expectedCounts(row) - actualCounts.getOrElse(row, 0)).max(0))(row))
    val extra          =
      actual.distinct.flatMap(row => Vector.fill((actualCounts(row) - expectedCounts.getOrElse(row, 0)).max(0))(row))
    val reordered      =
      if missing.isEmpty && extra.isEmpty && expected != actual then
        expected
          .zip(actual)
          .zipWithIndex
          .collect:
            case ((left, right), index) if left != right => s"$index\texpected=$left\tactual=$right"
      else Vector.empty
    val changed        = Vector.empty
    Vector(
      s"practical meaning: ${missing.size} missing, ${extra.size} extra, ${changed.size} changed, ${reordered.size} reordered rows",
      section("missing", missing),
      section("extra", extra),
      section("changed", changed),
      section("reordered", reordered),
      s"expected hash: ${CanonicalByteEncoder.sha256Hex(canonicalBytes(expected))}",
      s"actual hash: ${CanonicalByteEncoder.sha256Hex(canonicalBytes(actual))}"
    ).mkString("\n")

  private def section(name: String, rows: Vector[String]): String =
    s"$name:\n${if rows.isEmpty then "  none" else rows.map(row => s"  $row").mkString("\n")}"

  private def escape(value: String): String = value
    .replace("\\", "\\\\")
    .replace("\t", "\\t")
    .replace("\r", "\\r")
    .replace("\n", "\\n")

private[metallurgy] object Scala3PsiProductionCatalog:
  val Empty: Scala3PsiProductionCatalog = Scala3PsiProductionCatalog(Vector.empty, StableRoleInventory.Empty)

  private val PersistenceExternalIds =
    TemplatePersistenceSurfaces.ExternalIds ++ DefinitionPersistenceSurfaces.ExternalIds

  def persistedSchemaStructure(
      catalog: Scala3PsiProductionCatalog,
      schemaVersion: Int,
      rootExternalId: String
  ): PersistedSchemaStructure =
    persistedSchemaStructure(catalog, schemaVersion, rootExternalId, PersistenceExternalIds)

  private[psiproducer] def persistedSchemaStructure(
      catalog: Scala3PsiProductionCatalog,
      schemaVersion: Int,
      rootExternalId: String,
      externalIds: Map[PsiOutputRoleId, String]
  ): PersistedSchemaStructure =
    val directlyPersistedIds = catalog.productions.collect:
      case production
          if production.effectiveOutputRealizations.exists(
            _.template.composites.exists(_.persistence != PersistenceObligations.NotApplicable)
          ) =>
        production.id
    val productionsById      = catalog.productions.map(production => production.id -> production).toMap
    var persistedRoutingIds  = directlyPersistedIds.toSet
    var expanded             = true
    while expanded do
      val routingParents        = catalog.productions.collect:
        case production if production.children.exists(_.productionIds.exists(persistedRoutingIds.contains)) =>
          production.id
      val conditionDependencies = catalog.productions
        .filter(production => persistedRoutingIds.contains(production.id))
        .flatMap: production =>
          def dependencies(roleId: String, expected: ChildOutcomeExpectation): Set[String] =
            production.children
              .filter(_.roleId == roleId)
              .flatMap: child =>
                expected.alternatives.flatMap:
                  case ChildOutcomeExpectation.Production(productionId)   =>
                    child.productionIds.filter(_ == productionId)
                  case ChildOutcomeExpectation.Realization(realizationId) =>
                    child.productionIds.filter(productionId =>
                      productionsById
                        .get(productionId)
                        .exists(_.effectiveOutputRealizations.exists(_.id == realizationId))
                    )
                  case ChildOutcomeExpectation.OutputRole(role)           =>
                    child.productionIds.filter(productionId =>
                      productionsById
                        .get(productionId)
                        .exists(
                          _.effectiveOutputRealizations.exists(_.template.composites.exists(_.outputRoleId == role))
                        )
                    )
                  case ChildOutcomeExpectation.OutputRoles(roles)         =>
                    child.productionIds.filter(productionId =>
                      productionsById
                        .get(productionId)
                        .exists(
                          _.effectiveOutputRealizations.exists(
                            _.template.composites.exists(output => roles(output.outputRoleId))
                          )
                        )
                    )
                  case ChildOutcomeExpectation.AnyOf(_)                   => Set.empty
              .toSet
          production.effectiveOutputRealizations.flatMap: realization =>
            realization.conditions.flatMap(condition => dependencies(condition.roleId, condition.expected)) ++
              realization.childClosureAbsorptions.flatMap:
                case ChildClosureAbsorption(roleId, ChildRootOutcome.AnyReviewed, _)   =>
                  production.children.filter(_.roleId == roleId).flatMap(_.productionIds)
                case ChildClosureAbsorption(roleId, ChildRootOutcome.One(expected), _) =>
                  dependencies(roleId, expected)
                case ChildClosureAbsorption(roleId, ChildRootOutcome.All(expected), _) =>
                  dependencies(roleId, expected)
      val next                  = persistedRoutingIds ++ routingParents ++ conditionDependencies
      expanded = next.size != persistedRoutingIds.size
      persistedRoutingIds = next
    val rows                 = Vector.newBuilder[String]
    rows += StructuralRows.row("schema", schemaVersion)
    rows += StructuralRows.row("root", rootExternalId)
    catalog.productions
      .filter(production => persistedRoutingIds.contains(production.id))
      .zipWithIndex
      .foreach: (production, productionIndex) =>
        val relevantChildren = production.children.filter(_.productionIds.exists(persistedRoutingIds.contains))
        rows += StructuralRows.row(
          "persisted-production",
          productionIndex,
          production.id,
          production.pattern.kind,
          production.pattern.prefix,
          production.pattern.fields.mkString(","),
          production.pattern.occurrences.mkString(","),
          production.pattern.directNodeEvidence.mkString(","),
          production.grammarRoleId.value,
          production.dispositions.map(value => s"${value.fieldName}:${value.kind}").mkString(","),
          relevantChildren
            .map(child =>
              s"${child.roleId}:${child.fieldName}:${child.productionIds.filter(persistedRoutingIds.contains).toVector.sorted.mkString("+")}:${child.cardinality}:${child.slice}"
            )
            .mkString(",")
        )
        production.effectiveOutputRealizations.zipWithIndex
          .foreach: (realization, realizationIndex) =>
            val childMounts        = realization.template.childMounts.toVector
              .sortBy(_._1)
              .map((role, parent) => s"$role:${parent.getOrElse("")}")
              .mkString(",")
            val childSelections    = realization.template.childOutputSelections.toVector
              .sortBy(_._1)
              .map((role, selected) => s"$role:${selected.value}")
              .mkString(",")
            val conditions         = realization.conditions.zipWithIndex
              .map((condition, index) => s"$index:$condition")
              .mkString(",")
            val evidenceConditions = realization.evidenceConditions.zipWithIndex
              .map((condition, index) => s"$index:$condition")
              .mkString(",")
            rows += StructuralRows.row(
              "persisted-realization",
              productionIndex,
              production.id,
              realizationIndex,
              realization.id,
              conditions,
              evidenceConditions,
              childMounts,
              childSelections
            )
            realization.childClosureAbsorptions.zipWithIndex.foreach((absorption, index) =>
              rows += StructuralRows.row(
                "persisted-child-closure-absorption",
                productionIndex,
                production.id,
                realizationIndex,
                realization.id,
                index,
                absorption.roleId,
                absorption.rootOutcome,
                absorption.retainedRootRoles.toVector.sortBy(_.value).map(_.value).mkString(",")
              )
            )
            realization.template.composites
              .filter(_.persistence != PersistenceObligations.NotApplicable)
              .zipWithIndex
              .foreach: (output, outputIndex) =>
                output.persistence match
                  case PersistenceObligations.NotApplicable                                   => throw new AssertionError("filtered persisted output")
                  case PersistenceObligations.Required(stub, serializer, indices, navigation) =>
                    rows += StructuralRows.row(
                      "persisted-output",
                      productionIndex,
                      production.id,
                      realizationIndex,
                      realization.id,
                      outputIndex,
                      output.id,
                      output.parentId.getOrElse(""),
                      output.realization,
                      output.outputRoleId.value,
                      output.targetSurfaceId,
                      externalIds.getOrElse(output.outputRoleId, ""),
                      stub,
                      serializer,
                      indices.mkString(","),
                      navigation,
                      output.navigation
                    )
    PersistedSchemaStructure(rows.result())

  def catalogPlanStructure(catalog: Scala3PsiProductionCatalog): CatalogPlanStructure =
    val rows = Vector.newBuilder[String]
    catalog.stableRoles.grammarRoles.toVector
      .sortBy(_.value)
      .foreach(role => rows += StructuralRows.row("grammar-role", role.value))
    catalog.stableRoles.outputRoles.toVector
      .sortBy(_.value)
      .foreach(role => rows += StructuralRows.row("output-role", role.value))
    catalog.productions.zipWithIndex.foreach: (production, productionIndex) =>
      val prefix = Vector(productionIndex, production.id)
      rows += StructuralRows.row(
        "production",
        (prefix ++ Vector(
          production.pattern.kind,
          production.pattern.prefix,
          production.grammarRoleId.value,
          production.targetSurfaceId,
          production.targetRequirement,
          production.persistence,
          production.navigation,
          production.outputRoleId
        ))*
      )
      production.pattern.fields.zipWithIndex.foreach((field, index) =>
        rows += StructuralRows.row("pattern-field", (prefix ++ Vector(index, field.name, field.value))*)
      )
      production.pattern.occurrences.zipWithIndex.foreach((occurrence, index) =>
        rows += StructuralRows.row("pattern-occurrence", (prefix ++ Vector(index, occurrence))*)
      )
      production.pattern.directNodeEvidence.zipWithIndex.foreach((evidence, index) =>
        rows += StructuralRows.row(
          "direct-evidence",
          (prefix ++ Vector(index, evidence.fieldName, evidence.sourceClassification))*
        )
      )
      production.pattern.requiredAttachments.zipWithIndex.foreach((attachment, index) =>
        rows += StructuralRows.row(
          "required-attachment",
          (prefix ++ Vector(index, attachment.keyKind, attachment.value))*
        )
      )
      production.grammarRoleIds.toVector
        .sortBy(_.value)
        .foreach(role => rows += StructuralRows.row("production-grammar-role", (prefix :+ role.value)*))
      production.dispositions.zipWithIndex.foreach((disposition, index) =>
        rows += StructuralRows.row(
          "field-disposition",
          (prefix ++ Vector(index, disposition.fieldName, disposition.kind))*
        )
      )
      production.children.zipWithIndex.foreach((child, index) =>
        rows += StructuralRows.row(
          "child",
          (prefix ++ Vector(
            index,
            child.roleId,
            child.fieldName,
            child.productionIds.toVector.sorted.mkString(","),
            child.cardinality,
            child.slice
          ))*
        )
      )
      production.terminals.zipWithIndex.foreach((terminal, index) =>
        rows += StructuralRows.row(
          "terminal",
          (prefix ++ Vector(
            index,
            terminal.id,
            terminal.selector,
            terminal.target,
            terminal.cardinality,
            terminal.outputRoleId.value,
            terminal.ownsStructuralEvidence,
            terminal.claimsStructuralEvidence
          ))*
        )
      )
      production.layouts.zipWithIndex.foreach((layout, index) =>
        rows += StructuralRows.row("layout", (prefix ++ Vector(index, layout))*)
      )
      rows += StructuralRows.row("recovery", (prefix :+ production.recovery)*)
      production.accessors.zipWithIndex.foreach((accessor, index) =>
        rows += StructuralRows.row("production-accessor", (prefix ++ Vector(index, accessor))*)
      )
      production.effectiveOutputRealizations.zipWithIndex.foreach: (realization, realizationIndex) =>
        val realizationPrefix = prefix ++ Vector(realizationIndex, realization.id)
        rows += StructuralRows.row("realization", realizationPrefix*)
        realization.conditions.zipWithIndex.foreach((condition, index) =>
          rows += StructuralRows.row("realization-condition", (realizationPrefix ++ Vector(index, condition))*)
        )
        realization.evidenceConditions.zipWithIndex.foreach((condition, index) =>
          rows += StructuralRows.row("realization-evidence", (realizationPrefix ++ Vector(index, condition))*)
        )
        realization.childClosureAbsorptions.zipWithIndex.foreach((absorption, index) =>
          rows += StructuralRows.row(
            "child-closure-absorption",
            (realizationPrefix ++ Vector(
              index,
              absorption.roleId,
              absorption.rootOutcome,
              absorption.retainedRootRoles.toVector.sortBy(_.value).map(_.value).mkString(",")
            ))*
          )
        )
        realization.requiredChildRoots.zipWithIndex.foreach((requirement, index) =>
          rows += StructuralRows.row(
            "required-child-root",
            (realizationPrefix ++ Vector(index, requirement.roleId, requirement.rootOutcome))*
          )
        )
        realization.terminalIds.foreach(
          _.toVector.sorted.foreach(id =>
            rows += StructuralRows.row("realization-terminal", (realizationPrefix ++ Vector(id))*)
          )
        )
        realization.template.composites.zipWithIndex.foreach: (output, outputIndex) =>
          val outputPrefix = realizationPrefix ++ Vector(outputIndex, output.id)
          rows += StructuralRows.row(
            "output",
            (outputPrefix ++ Vector(
              output.parentId.getOrElse(""),
              output.range,
              output.outputRoleId.value,
              output.targetSurfaceId,
              PersistenceExternalIds.getOrElse(output.outputRoleId, ""),
              output.targetRequirement,
              output.persistence,
              output.navigation,
              output.ownsStructuralEvidence,
              output.requiresCompilerEndMarker,
              output.realization
            ))*
          )
          output.accessors.zipWithIndex.foreach((accessor, index) =>
            rows += StructuralRows.row("output-accessor", (outputPrefix ++ Vector(index, accessor))*)
          )
        realization.template.childMounts.toVector
          .sortBy(_._1)
          .foreach((role, parent) =>
            rows += StructuralRows.row("child-mount", (realizationPrefix ++ Vector(role, parent.getOrElse("")))*)
          )
        realization.template.childOutputSelections.toVector
          .sortBy(_._1)
          .foreach((role, selected) =>
            rows += StructuralRows.row("child-selection", (realizationPrefix ++ Vector(role, selected.value))*)
          )
      production.realizationChoice.foreach(choice =>
        rows += StructuralRows.row(
          "realization-choice",
          (prefix ++ choice.candidateIds ++ Vector(choice.fallbackId))*
        )
        if choice.policy != RealizationChoicePolicy.LocalAssessment then
          rows += StructuralRows.row("realization-choice-policy", (prefix :+ choice.policy)*)
        choice.trialEligibility.zipWithIndex.foreach: (requirement, requirementIndex) =>
          rows += StructuralRows.row(
            "realization-choice-trial-eligibility",
            (prefix ++ Vector(requirementIndex, requirement.roleId, requirement.rootOutcome))*
          )
      )
    catalog.productionAlternatives.zipWithIndex.foreach((alternatives, index) =>
      rows += StructuralRows.row("production-alternatives", index, alternatives.candidateId, alternatives.fallbackId)
    )
    CatalogPlanStructure(rows.result())

  lazy val Reviewed: Scala3PsiProductionCatalog = Scala3PsiProductionCatalog(
    Scala3PsiPackageImportExportProductions.PackageImportExportPrefixSegment ++
      Scala3PsiAppliedTypeProductions.AppliedTypeSegment ++
      Scala3PsiPackageImportExportProductions.PackageImportExportGivenSegment ++
      Scala3PsiCompoundTypeProductions.CompoundInfixSegment ++
      Scala3PsiPackageImportExportProductions.PackageImportExportStablePathSegment ++
      Scala3PsiModifierAnnotationProductions.ModifierAnnotationSegment ++
      Scala3PsiTemplateProductions.TemplateSegment ++
      Scala3PsiDefinitionProductions.DefinitionSegment ++
      Scala3PsiNamedArgumentProductions.NamedArgumentSegment ++
      Scala3PsiRepeatedArgumentProductions.RepeatedArgumentSegment ++
      Scala3PsiApplicationExpressionProductions.ApplicationExpressionSegment ++
      Scala3PsiMatchExpressionProductions.MatchExpressionSegment ++
      Scala3PsiPatternAppliedTypeProductions.PatternAppliedTypeSegment ++
      Scala3PsiAtomicExpressionProductions.AtomicExpressionSegment ++
      Scala3PsiSelectionExpressionProductions.SelectionExpressionSegment ++
      Scala3PsiDefinitionPayloadProductions.DefinitionPayloadSegment ++
      Scala3PsiTupleFunctionTypeProductions.TupleFunctionPrefixSegment ++
      Scala3PsiCaptureTypeProductions.CaptureFunctionSegment ++
      Scala3PsiTupleFunctionTypeProductions.TupleFunctionMiddleSegment ++
      Scala3PsiCaptureTypeProductions.CaptureByNameSegment ++
      Scala3PsiTupleFunctionTypeProductions.TupleFunctionSuffixSegment ++
      Scala3PsiCompoundTypeProductions.CompoundTypeSegment ++
      Scala3PsiCaptureTypeProductions.CaptureTypeSegment ++
      Scala3PsiTypeAtomProductions.TypeAtomSegment ++
      Scala3PsiMatchExpressionProductions.MatchGivenSuffixSegment ++
      Scala3PsiMatchExpressionProductions.MatchParenthesizedSuffixSegment ++
      Scala3PsiPatternTupleTypeProductions.PatternTupleTypeSuffixSegment ++
      Scala3PsiPatternWildcardTypeProductions.PatternWildcardTypeSuffixSegment ++
      Scala3PsiPatternStableSelectProductions.PatternStableSelectSuffixSegment ++
      Scala3PsiPatternParenthesizedTypeProductions.PatternParenthesizedTypeSuffixSegment ++
      Scala3PsiPatternSingletonTypeProductions.PatternSingletonTypeSuffixSegment,
    StableRoleInventory.Reviewed,
    Vector(
      ProductionAlternatives(
        Scala3PsiNamedArgumentProductions.CandidateProductionId,
        Scala3PsiApplicationExpressionProductions.FallbackProductionId
      ),
      ProductionAlternatives(
        Scala3PsiRepeatedArgumentProductions.CandidateProductionId,
        Scala3PsiApplicationExpressionProductions.FallbackProductionId
      ),
      ProductionAlternatives(
        Scala3PsiMatchExpressionProductions.CandidateProductionId,
        Scala3PsiMatchExpressionProductions.FallbackProductionId
      ),
      ProductionAlternatives("term-named-argument", "payload-descendant-named-arg"),
      ProductionAlternatives("term-repeated-argument", "repeated-term-output-free-typed-synthetic"),
      ProductionAlternatives(
        Scala3PsiApplicationExpressionProductions.CandidateProductionId,
        Scala3PsiApplicationExpressionProductions.FallbackProductionId
      ),
      ProductionAlternatives(
        Scala3PsiApplicationExpressionProductions.CandidateProductionId,
        "definition-payload-applied-call"
      ),
      ProductionAlternatives(
        Scala3PsiApplicationExpressionProductions.CandidateProductionId,
        "payload-descendant-apply"
      ),
      ProductionAlternatives("positional-applied-call-candidate", "definition-payload-applied-call"),
      ProductionAlternatives("named-invoked-call-candidate", "definition-payload-applied-call"),
      ProductionAlternatives("named-type-application-candidate", "definition-payload-type-apply-named"),
      ProductionAlternatives("atomic-term-ident", "payload-descendant-ident"),
      ProductionAlternatives("atomic-term-ident", "payload-output-free-ident"),
      ProductionAlternatives("atomic-literal-integer", "payload-descendant-number"),
      ProductionAlternatives("atomic-literal-integer", "named-term-output-free-integer"),
      ProductionAlternatives("atomic-literal-string", "named-term-output-free-string"),
      ProductionAlternatives("selection-expression", "payload-descendant-select"),
      ProductionAlternatives("selection-expression", "payload-output-free-select"),
      ProductionAlternatives(Scala3PsiMatchExpressionProductions.WildcardProductionId, "payload-descendant-ident"),
      ProductionAlternatives(Scala3PsiMatchExpressionProductions.ReferenceProductionId, "payload-descendant-ident"),
      ProductionAlternatives(
        Scala3PsiMatchExpressionProductions.StableReferenceProductionId,
        "payload-descendant-ident"
      ),
      ProductionAlternatives(Scala3PsiMatchExpressionProductions.LiteralProductionId, "payload-descendant-number"),
      ProductionAlternatives(
        Scala3PsiPatternAppliedTypeProductions.AppliedTypeProductionId,
        "expression-type-argument-applied"
      ),
      ProductionAlternatives(
        Scala3PsiMatchExpressionProductions.LiteralStringProductionId,
        "payload-descendant-invoked-literal"
      ),
      ProductionAlternatives(
        Scala3PsiMatchExpressionProductions.LiteralCharProductionId,
        "payload-descendant-invoked-literal"
      ),
      ProductionAlternatives(
        Scala3PsiMatchExpressionProductions.LiteralBooleanProductionId,
        "payload-descendant-invoked-literal"
      ),
      ProductionAlternatives(
        Scala3PsiMatchExpressionProductions.LiteralDoubleProductionId,
        "payload-descendant-invoked-literal"
      ),
      ProductionAlternatives(
        Scala3PsiMatchExpressionProductions.LiteralFloatProductionId,
        "payload-descendant-invoked-literal"
      ),
      ProductionAlternatives(
        Scala3PsiMatchExpressionProductions.LiteralLongProductionId,
        "payload-descendant-invoked-literal"
      ),
      ProductionAlternatives(
        Scala3PsiMatchExpressionProductions.LiteralNullProductionId,
        "payload-descendant-invoked-literal"
      ),
      ProductionAlternatives(Scala3PsiMatchExpressionProductions.TypedProductionId, "payload-descendant-ident"),
      ProductionAlternatives(Scala3PsiMatchExpressionProductions.TypeIdentProductionId, "payload-descendant-ident"),
      ProductionAlternatives(Scala3PsiMatchExpressionProductions.NamingProductionId, "payload-descendant-ident"),
      ProductionAlternatives(
        Scala3PsiMatchExpressionProductions.NamingSequenceProductionId,
        "payload-descendant-ident"
      ),
      ProductionAlternatives(
        Scala3PsiMatchExpressionProductions.SequenceWildcardProductionId,
        "payload-descendant-ident"
      ),
      ProductionAlternatives(Scala3PsiMatchExpressionProductions.TupleProductionId, "payload-descendant-tuple"),
      ProductionAlternatives(
        Scala3PsiMatchExpressionProductions.UnitTupleProductionId,
        "payload-descendant-tuple"
      ),
      ProductionAlternatives(Scala3PsiMatchExpressionProductions.AlternativeProductionId, "payload-descendant-ident"),
      ProductionAlternatives(Scala3PsiMatchExpressionProductions.ConstructorProductionId, "payload-descendant-apply"),
      ProductionAlternatives("import-selector-given-bound-absent", "template-absent-tree"),
      ProductionAlternatives(
        Scala3PsiMatchExpressionProductions.SequenceMarkerProductionId,
        "payload-descendant-ident"
      ),
      ProductionAlternatives(
        Scala3PsiMatchExpressionProductions.SequenceMarkerProductionId,
        Scala3PsiMatchExpressionProductions.ReferenceProductionId
      ),
      ProductionAlternatives(Scala3PsiMatchExpressionProductions.GivenProductionId, "payload-descendant-ident"),
      ProductionAlternatives(Scala3PsiMatchExpressionProductions.GivenNamedProductionId, "payload-descendant-ident"),
      ProductionAlternatives(Scala3PsiMatchExpressionProductions.GivenTypedProductionId, "payload-descendant-ident"),
      ProductionAlternatives(Scala3PsiMatchExpressionProductions.ParenthesizedProductionId, "payload-descendant-ident"),
      ProductionAlternatives(
        Scala3PsiPatternTupleTypeProductions.MatchTupleTypeProductionId,
        "payload-descendant-tuple"
      ),
      ProductionAlternatives(
        Scala3PsiPatternStableSelectProductions.MatchDottedTypeProductionId,
        "payload-descendant-select"
      ),
      ProductionAlternatives(
        Scala3PsiPatternStableSelectProductions.MatchDottedReferenceProductionId,
        "payload-descendant-select"
      ),
      ProductionAlternatives(
        Scala3PsiPatternStableSelectProductions.MatchHashProjectionProductionId,
        "payload-descendant-select"
      )
    )
  )
