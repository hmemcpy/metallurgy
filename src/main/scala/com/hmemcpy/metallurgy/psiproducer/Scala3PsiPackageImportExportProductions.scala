package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiPackageImportExportProductions:
  private def givenTypeQualifierOccurrence(owner: String): Vector[CompilerProductionContextPattern] =
    Vector(
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchor(
          InventoryKind.Node,
          owner,
          Vector(CatalogPathSegment.NamedField("qualifier")),
          GivenSelectorBoundAnchor
        ),
        SourceClassification.SourceReachable
      )
    )

  private def typeAtomQualifierOccurrences(owner: String): Vector[CompilerProductionContextPattern] =
    givenTypeQualifierOccurrence(owner) ++ OwnerTypeAnchors.map(anchor =>
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchor(
          InventoryKind.Node,
          owner,
          Vector(CatalogPathSegment.NamedField("qualifier")),
          anchor
        ),
        SourceClassification.SourceReachable
      )
    )

  private val importSelectorAppliedTypeProduction = Scala3PsiAppliedTypeProductions
    .appliedTypeProduction(
      "import-selector-bound-applied-type",
      givenTypeOccurrences,
      Set.empty
    )
    .copy(
      children = Vector(
        ChildDeclaration(
          "constructor",
          "tpt",
          ChildCardinality.ExactlyOne,
          "import-selector-bound-type",
          GivenTypeProductionIds - "import-selector-bound-type"
        ),
        ChildDeclaration(
          "arguments",
          "args",
          ChildCardinality.Repeated(1, None),
          "import-selector-bound-type",
          GivenTypeProductionIds - "import-selector-bound-type"
        )
      )
    )

  private def packageTemplate(
      childRoles: Vector[String],
      bodyRole: Option[String]
  ): LocalOutputCompositeTemplate =
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "package",
          None,
          OutputRangeDeclaration.CompilerPositionWithBodyLayoutOrEndMarker(
            "package-reference",
            bodyRole,
            ClosedSourceLexicalKind.LeftBrace,
            ClosedSourceLexicalKind.RightBrace,
            ClosedSourceLexicalKind.Colon
          ),
          PsiOutputRoleId.PackageStatement,
          PackageSurface,
          PackageAccessors
        ),
        outputComposite(
          "end",
          Some("package"),
          OutputRangeDeclaration.CompilerEndMarker,
          PsiOutputRoleId.EndStatement,
          EndSurface,
          EndAccessors
        ).copy(requiresCompilerEndMarker = true)
      ),
      childRoles.map(_ -> Some("package")).toMap
    )

  private def selectorTemplate(
      range: OutputRangeDeclaration,
      childRoles: String*
  ): LocalOutputCompositeTemplate =
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "selector",
          None,
          range,
          PsiOutputRoleId.ImportSelector,
          ImportSelectorSurface,
          ImportSelectorAccessors
        )
      ),
      childRoles.map(_ -> Some("selector")).toMap
    )

  private def namedSelectorTemplate: LocalOutputCompositeTemplate =
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "selector",
          None,
          OutputRangeDeclaration.CompilerPosition,
          PsiOutputRoleId.ImportSelector,
          ImportSelectorSurface,
          ImportSelectorAccessors
        ),
        outputComposite(
          "reference",
          Some("selector"),
          OutputRangeDeclaration.BoundaryDerived(
            OutputBoundary.ChildStart(
              "imported",
              ChildOccurrenceSelector.First,
              PositionProvenancePolicy.SourceDerivedOnly
            ),
            OutputBoundary.ChildEnd(
              "imported",
              ChildOccurrenceSelector.First,
              PositionProvenancePolicy.SourceDerivedOnly
            )
          ),
          PsiOutputRoleId.StableReference,
          StableReferenceSurface,
          StableReferenceAccessors
        )
      ),
      Map("imported" -> Some("reference"), "renamed" -> Some("selector"), "bound" -> Some("selector"))
    )

  private def selectorSetStatementTemplate(
      statementRole: PsiOutputRoleId,
      statementSurface: String,
      statementAccessors: Vector[AccessorObligation]
  ): LocalOutputCompositeTemplate =
    val pathStart     = OutputBoundary.ChildStart(
      "path",
      ChildOccurrenceSelector.First,
      PositionProvenancePolicy.SourceDerivedOnly
    )
    val selectorStart = OutputBoundary.EvidenceBoundaryAfterChild(
      "path",
      ChildOccurrenceSelector.First,
      "selectors",
      ChildOccurrenceSelector.First,
      Vector("{", "given"),
      PositionProvenancePolicy.SourceDerivedOnly,
      fallbackToFollowingChildStart = true
    )
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "statement",
          None,
          OutputRangeDeclaration.CompilerPosition,
          statementRole,
          statementSurface,
          statementAccessors
        ),
        outputComposite(
          "expression",
          Some("statement"),
          OutputRangeDeclaration.BoundaryDerived(
            pathStart,
            OutputBoundary.ProductionEnd()
          ),
          PsiOutputRoleId.ImportExpression,
          ImportExpressionSurface,
          ImportExpressionAccessors
        ),
        outputComposite(
          "selectors",
          Some("expression"),
          OutputRangeDeclaration.BoundaryDerived(
            selectorStart,
            OutputBoundary.ProductionEnd()
          ),
          PsiOutputRoleId.ImportSelectorSet,
          ImportSelectorsSurface,
          ImportSelectorsAccessors
        )
      ),
      Map("path" -> Some("expression"), "selectors" -> Some("selectors"))
    )

  private def directStatementTemplate(
      outerReference: Boolean,
      statementRole: PsiOutputRoleId,
      statementSurface: String,
      statementAccessors: Vector[AccessorObligation]
  ): LocalOutputCompositeTemplate =
    val pathStart = OutputBoundary.ChildStart(
      "path",
      ChildOccurrenceSelector.First,
      PositionProvenancePolicy.SourceDerivedOnly
    )

    val selectorEnd     = OutputBoundary.ChildEnd(
      "selectors",
      ChildOccurrenceSelector.Last,
      PositionProvenancePolicy.PositionedIncludingSynthetic
    )
    val expressionRange = OutputRangeDeclaration.BoundaryDerived(pathStart, selectorEnd)
    val base            = Vector(
      outputComposite(
        "statement",
        None,
        OutputRangeDeclaration.CompilerPosition,
        statementRole,
        statementSurface,
        statementAccessors
      ),
      outputComposite(
        "expression",
        Some("statement"),
        expressionRange,
        PsiOutputRoleId.ImportExpression,
        ImportExpressionSurface,
        ImportExpressionAccessors
      )
    )
    val composites      =
      if outerReference then
        base :+ outputComposite(
          "reference",
          Some("expression"),
          expressionRange,
          PsiOutputRoleId.StableReference,
          StableReferenceSurface,
          StableReferenceAccessors
        )
      else base
    LocalOutputCompositeTemplate(
      composites,
      Map(
        "path"      -> Some(if outerReference then "reference" else "expression"),
        "selectors" -> Some(if outerReference then "reference" else "expression")
      )
    )

  private def selectorOwnedStatementTemplate(
      statementRole: PsiOutputRoleId,
      statementSurface: String,
      statementAccessors: Vector[AccessorObligation]
  ): LocalOutputCompositeTemplate =
    val selectorStart =
      OutputBoundary.ChildStart("selectors", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly)
    val selectorEnd   =
      OutputBoundary.ChildEnd("selectors", ChildOccurrenceSelector.Last, PositionProvenancePolicy.SourceDerivedOnly)
    val range         = OutputRangeDeclaration.BoundaryDerived(selectorStart, selectorEnd)
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "statement",
          None,
          OutputRangeDeclaration.CompilerPosition,
          statementRole,
          statementSurface,
          statementAccessors
        ),
        outputComposite(
          "expression",
          Some("statement"),
          range,
          PsiOutputRoleId.ImportExpression,
          ImportExpressionSurface,
          ImportExpressionAccessors
        ),
        outputComposite(
          "selectors",
          Some("expression"),
          range,
          PsiOutputRoleId.ImportSelectorSet,
          ImportSelectorsSurface,
          ImportSelectorsAccessors
        )
      ),
      Map("path" -> Some("expression"), "selectors" -> Some("selectors"))
    )

  private def statementProduction(
      id: String,
      compilerProduction: String,
      grammarRole: GrammarRoleId,
      outputRole: PsiOutputRoleId,
      statementSurface: String,
      statementAccessors: Vector[AccessorObligation]
  ): Scala3PsiProduction = Scala3PsiProduction(
    id = id,
    grammarRoleId = grammarRole,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      compilerProduction,
      Vector(
        CompilerFieldPattern("expr", CatalogValuePattern.Node),
        CompilerFieldPattern("selectors", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "PackageDef",
            Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement)
          ),
          SourceClassification.SourceReachable
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("expr", FieldDispositionKind.Child),
      FieldDisposition("selectors", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "path",
        "expr",
        ChildCardinality.ExactlyOne,
        "import-path-reference",
        Set("import-path-identifier-reference", "import-expression-absent")
      ),
      ChildDeclaration(
        "selectors",
        "selectors",
        ChildCardinality.Repeated(1, None),
        "import-selector-direct",
        Set("import-selector-braced")
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "statement-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = statementSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = statementAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRoleId = None,
    outputRealizations = Vector(
      OutputRealization(
        "selector-owned",
        Vector(
          ChildOutcomeCondition(
            "path",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Production("import-expression-absent")
          ),
          ChildOutcomeCondition(
            "selectors",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Realization("braced-alias")
          )
        ),
        selectorOwnedStatementTemplate(outputRole, statementSurface, statementAccessors)
      ),
      OutputRealization(
        "plain",
        Vector(
          ChildOutcomeCondition(
            "selectors",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Realization("direct-plain")
          )
        ),
        directStatementTemplate(
          outerReference = true,
          outputRole,
          statementSurface,
          statementAccessors
        )
      ),
      OutputRealization(
        "wildcard",
        Vector(
          ChildOutcomeCondition(
            "selectors",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Realization("direct-wildcard")
          )
        ),
        directStatementTemplate(
          outerReference = false,
          outputRole,
          statementSurface,
          statementAccessors
        )
      ),
      OutputRealization(
        "given-direct",
        Vector(
          ChildOutcomeCondition(
            "selectors",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Realization("direct-given")
          )
        ),
        selectorSetStatementTemplate(outputRole, statementSurface, statementAccessors)
      ),
      OutputRealization(
        "named-selectors",
        Vector(
          ChildOutcomeCondition(
            "selectors",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Realization("braced-named")
          )
        ),
        selectorSetStatementTemplate(outputRole, statementSurface, statementAccessors)
      ),
      OutputRealization(
        "given-selectors",
        Vector(
          ChildOutcomeCondition(
            "selectors",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Realization("braced-alias")
          )
        ),
        selectorSetStatementTemplate(outputRole, statementSurface, statementAccessors)
      ),
      OutputRealization(
        "hidden-selectors",
        Vector(
          ChildOutcomeCondition(
            "selectors",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Realization("braced-hidden")
          )
        ),
        selectorSetStatementTemplate(outputRole, statementSurface, statementAccessors)
      ),
      OutputRealization(
        "wildcard-selectors",
        Vector(
          ChildOutcomeCondition(
            "selectors",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Realization("braced-wildcard")
          )
        ),
        selectorSetStatementTemplate(outputRole, statementSurface, statementAccessors)
      ),
      OutputRealization(
        "given-braced-selectors",
        Vector(
          ChildOutcomeCondition(
            "selectors",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Realization("braced-given")
          )
        ),
        selectorSetStatementTemplate(outputRole, statementSurface, statementAccessors)
      )
    )
  )

  private def packageProduction(id: String, body: Boolean): Scala3PsiProduction =
    val packageIds       = Set("file-package", "file-package-top-statements")
    val templateOwnerIds = Set(
      "template-class-definition",
      "template-trait-definition",
      "template-object-definition",
      "template-enum-definition",
      "definition-function-untyped",
      "definition-val-untyped",
      "definition-var-untyped"
    ) ++ SimpleTypeAliasProductionIds
    val children         = Vector(
      ChildDeclaration(
        "package-reference",
        "pid",
        ChildCardinality.ExactlyOne,
        "package-stable-reference",
        Set("package-stable-identifier-reference")
      )
    ) ++ Option.when(body)(
      ChildDeclaration(
        "package-statements",
        "stats",
        ChildCardinality.Grouped(1, None),
        "import-statement",
        Set("export-statement") ++ packageIds ++ templateOwnerIds
      )
    )
    Scala3PsiProduction(
      id = id,
      grammarRoleId = GrammarRoleId.PackageClause,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "PackageDef",
        Vector(
          CompilerFieldPattern("pid", CatalogValuePattern.Node),
          CompilerFieldPattern(
            "stats",
            if body then CatalogValuePattern.Repeated(CatalogValuePattern.Node)
            else CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)
          )
        ),
        Vector(
          CompilerProductionContextPattern(ContextPattern.Root, SourceClassification.SourceReachable),
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              "PackageDef",
              Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement)
            ),
            SourceClassification.SourceReachable
          )
        )
      ),
      dispositions = Vector(
        FieldDisposition("pid", FieldDispositionKind.Child),
        FieldDisposition("stats", if body then FieldDispositionKind.Child else FieldDispositionKind.SemanticOnly)
      ),
      children = children,
      terminals = Vector(
        TerminalDeclaration(
          "package-text",
          TerminalIntervalSelector.LocalOutput("package"),
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "root-remainder",
          TerminalIntervalSelector.RootOutsideLocalOutput("package"),
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.Optional,
          PsiOutputRoleId.SourceTerminal,
          ownsStructuralEvidence = Some(false)
        ),
        TerminalDeclaration(
          "end-keyword",
          TerminalIntervalSelector.CompilerEndMarkerKeyword,
          TerminalLeafTarget.Token(NativePsiElementBindings.EndKeywordTokenSurface, Some("end")),
          OccurrenceCardinality.Optional,
          PsiOutputRoleId.EndKeyword
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = PackageSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = PackageAccessors,
      persistence = PersistenceObligations.Required(
        PackagePersistenceSurfaces.Stub,
        PackagePersistenceSurfaces.Serializer,
        Vector(PackagePersistenceSurfaces.FqnIndex),
        ImportPersistenceSurfaces.SelfNavigation
      ),
      navigation = Some(NavigationObligation.Self),
      outputTemplate = Some(packageTemplate(children.map(_.roleId), Option.when(body)("package-statements"))),
      outputRoleId = None
    )

  private[psiproducer] val PackageImportExportPrefixSegment: Vector[Scala3PsiProduction]     = Vector(
    packageProduction("file-package", body = false),
    packageProduction("file-package-top-statements", body = true),
    Scala3PsiProduction(
      id = "file-top-statements",
      grammarRoleId = GrammarRoleId.CompilationUnit,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "PackageDef",
        Vector(
          CompilerFieldPattern("pid", CatalogValuePattern.Node),
          CompilerFieldPattern("stats", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
        ),
        Vector(CompilerProductionContextPattern(ContextPattern.Root, SourceClassification.Synthetic))
      ),
      dispositions = Vector(
        FieldDisposition("pid", FieldDispositionKind.Synthetic),
        FieldDisposition("stats", FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration(
          "top-statements",
          "stats",
          ChildCardinality.Grouped(1, None),
          "import-statement",
          Set(
            "export-statement",
            "file-package",
            "file-package-top-statements",
            "template-class-definition",
            "template-trait-definition",
            "template-object-definition",
            "template-enum-definition",
            "definition-function-untyped",
            "definition-val-untyped",
            "definition-var-untyped"
          ) ++ SimpleTypeAliasProductionIds
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "whole-file",
          TerminalIntervalSelector.WholeSource,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = ImportStatementSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = ImportStatementAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(transparentTemplate("top-statements"))
    ),
    statementProduction(
      id = "import-statement",
      compilerProduction = "Import",
      grammarRole = GrammarRoleId.ImportStatement,
      outputRole = PsiOutputRoleId.ImportStatement,
      statementSurface = ImportStatementSurface,
      statementAccessors = ImportStatementAccessors
    ),
    statementProduction(
      id = "export-statement",
      compilerProduction = "Export",
      grammarRole = GrammarRoleId.ExportStatement,
      outputRole = PsiOutputRoleId.ExportStatement,
      statementSurface = ExportStatementSurface,
      statementAccessors = ExportStatementAccessors
    ),
    Scala3PsiProduction(
      id = "import-expression-absent",
      grammarRoleId = GrammarRoleId.AbsentProduct,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Thicket",
        Vector(CompilerFieldPattern("trees", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node))),
        Vector("Import", "Export").map: owner =>
          CompilerProductionContextPattern(
            ContextPattern.Parent(InventoryKind.Node, owner, Vector(CatalogPathSegment.NamedField("expr"))),
            SourceClassification.Absent
          )
      ),
      dispositions = Vector(FieldDisposition("trees", FieldDispositionKind.Synthetic)),
      children = Vector.empty,
      terminals = Vector.empty,
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = ImportExpressionSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = ImportExpressionAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(transparentTemplate())
    ),
    Scala3PsiProduction(
      id = "file-import-empty-package",
      grammarRoleId = GrammarRoleId.PackageReference,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Ident",
        Vector(
          CompilerFieldPattern(
            "name",
            CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)
          )
        ),
        Vector(
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              "PackageDef",
              Vector(CatalogPathSegment.NamedField("pid"))
            ),
            SourceClassification.Synthetic
          )
        )
      ),
      dispositions = Vector(FieldDisposition("name", FieldDispositionKind.Synthetic)),
      children = Vector.empty,
      terminals = Vector.empty,
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = ImportStatementSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = Vector.empty,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(transparentTemplate())
    ),
    Scala3PsiProduction(
      id = "import-path-identifier-reference",
      grammarRoleId = GrammarRoleId.StableReference,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Ident",
        Vector(
          CompilerFieldPattern(
            "name",
            CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)
          )
        ),
        Vector("Import", "Export").map: owner =>
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              owner,
              Vector(CatalogPathSegment.NamedField("expr"))
            ),
            SourceClassification.SourceReachable
          )
      ),
      dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
      children = Vector.empty,
      terminals = Vector(
        TerminalDeclaration(
          "identifier-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = StableReferenceSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = StableReferenceAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = Some(PsiOutputRoleId.StableReference)
    ),
    Scala3PsiProduction(
      id = "import-path-reference",
      grammarRoleId = GrammarRoleId.StableReference,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Select",
        Vector(
          CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
          CompilerFieldPattern(
            "name",
            CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)
          )
        ),
        Vector("Import", "Export").map: owner =>
          CompilerProductionContextPattern(
            ContextPattern.AnchorOrParentWithRepeatedAncestor(
              InventoryAncestor(
                InventoryKind.Node,
                owner,
                Vector(CatalogPathSegment.NamedField("expr"))
              ),
              InventoryKind.Node,
              "Select",
              Vector(CatalogPathSegment.NamedField("qualifier")),
              InventoryAncestor(
                InventoryKind.Node,
                "Select",
                Vector(CatalogPathSegment.NamedField("qualifier"))
              )
            ),
            SourceClassification.SourceReachable
          )
      ),
      dispositions = Vector(
        FieldDisposition("qualifier", FieldDispositionKind.Child),
        FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)
      ),
      children = Vector(
        ChildDeclaration(
          "qualifier",
          "qualifier",
          ChildCardinality.ExactlyOne,
          "import-path-identifier",
          Set("import-path-reference")
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "reference-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = StableReferenceSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = StableReferenceAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = Some(PsiOutputRoleId.StableReference)
    ),
    Scala3PsiProduction(
      id = "import-path-identifier",
      grammarRoleId = GrammarRoleId.StableReference,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Ident",
        Vector(
          CompilerFieldPattern(
            "name",
            CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)
          )
        ),
        Vector("Import", "Export").map: owner =>
          CompilerProductionContextPattern(
            ContextPattern.ParentWithRepeatedAncestor(
              InventoryKind.Node,
              "Select",
              Vector(CatalogPathSegment.NamedField("qualifier")),
              InventoryAncestor(
                InventoryKind.Node,
                "Select",
                Vector(CatalogPathSegment.NamedField("qualifier"))
              ),
              InventoryAncestor(
                InventoryKind.Node,
                owner,
                Vector(CatalogPathSegment.NamedField("expr"))
              )
            ),
            SourceClassification.SourceReachable
          )
      ),
      dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
      children = Vector.empty,
      terminals = Vector(
        TerminalDeclaration(
          "identifier-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = StableReferenceSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = StableReferenceAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = Some(PsiOutputRoleId.StableReference)
    ),
    Scala3PsiProduction(
      id = "import-selector-direct",
      grammarRoleId = GrammarRoleId.ImportSelector,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "ImportSelector",
        Vector(
          CompilerFieldPattern("imported", CatalogValuePattern.Node),
          CompilerFieldPattern("renamed", CatalogValuePattern.Node),
          CompilerFieldPattern("bound", CatalogValuePattern.Node)
        ),
        Vector("Import", "Export").map: owner =>
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              owner,
              Vector(CatalogPathSegment.NamedField("selectors"), CatalogPathSegment.RepeatedElement)
            ),
            SourceClassification.Synthetic
          )
      ),
      dispositions = Vector(
        FieldDisposition("imported", FieldDispositionKind.Child),
        FieldDisposition("renamed", FieldDispositionKind.Child),
        FieldDisposition("bound", FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration(
          "imported",
          "imported",
          ChildCardinality.ExactlyOne,
          "import-selector-name",
          Set("import-selector-wildcard-name", "import-selector-empty-name")
        ),
        ChildDeclaration(
          "renamed",
          "renamed",
          ChildCardinality.ExactlyOne,
          "import-selector-name",
          Set("import-selector-hidden-name", "import-selector-absent")
        ),
        ChildDeclaration(
          "bound",
          "bound",
          ChildCardinality.ExactlyOne,
          "import-selector-bound-type",
          (GivenTypeProductionIds - "import-selector-bound-type") + "import-selector-absent"
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "selector-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.Optional,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = ImportSelectorSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = ImportSelectorAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputRealizations = Vector(
        OutputRealization(
          "direct-plain",
          Vector(
            ChildOutcomeCondition(
              "imported",
              ChildOccurrenceSelector.First,
              ChildOutcomeExpectation.Production("import-selector-name")
            ),
            ChildOutcomeCondition(
              "renamed",
              ChildOccurrenceSelector.First,
              ChildOutcomeExpectation.Production("import-selector-absent")
            ),
            ChildOutcomeCondition(
              "bound",
              ChildOccurrenceSelector.First,
              ChildOutcomeExpectation.Production("import-selector-absent")
            )
          ),
          transparentTemplate("imported", "renamed", "bound")
        ),
        OutputRealization(
          "direct-wildcard",
          Vector(
            ChildOutcomeCondition(
              "imported",
              ChildOccurrenceSelector.First,
              ChildOutcomeExpectation.Production("import-selector-wildcard-name")
            )
          ),
          transparentTemplate("imported", "renamed", "bound")
        ),
        OutputRealization(
          "direct-given",
          Vector(
            ChildOutcomeCondition(
              "imported",
              ChildOccurrenceSelector.First,
              ChildOutcomeExpectation.Production("import-selector-empty-name")
            )
          ),
          selectorTemplate(
            OutputRangeDeclaration.CompilerPositionWithPolicy(
              PositionProvenancePolicy.PositionedIncludingSynthetic
            ),
            "imported",
            "renamed",
            "bound"
          )
        )
      )
    ),
    Scala3PsiProduction(
      id = "import-selector-braced",
      grammarRoleId = GrammarRoleId.ImportSelector,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "ImportSelector",
        Vector(
          CompilerFieldPattern("imported", CatalogValuePattern.Node),
          CompilerFieldPattern("renamed", CatalogValuePattern.Node),
          CompilerFieldPattern("bound", CatalogValuePattern.Node)
        ),
        Vector("Import", "Export").map: owner =>
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              owner,
              Vector(CatalogPathSegment.NamedField("selectors"), CatalogPathSegment.RepeatedElement)
            ),
            SourceClassification.SourceReachable
          )
      ),
      dispositions = Vector(
        FieldDisposition("imported", FieldDispositionKind.Child),
        FieldDisposition("renamed", FieldDispositionKind.Child),
        FieldDisposition("bound", FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration(
          "imported",
          "imported",
          ChildCardinality.ExactlyOne,
          "import-selector-name",
          Set("import-selector-wildcard-name", "import-selector-empty-name")
        ),
        ChildDeclaration(
          "renamed",
          "renamed",
          ChildCardinality.ExactlyOne,
          "import-selector-name",
          Set("import-selector-hidden-name", "import-selector-absent")
        ),
        ChildDeclaration(
          "bound",
          "bound",
          ChildCardinality.ExactlyOne,
          "import-selector-bound-type",
          (GivenTypeProductionIds - "import-selector-bound-type") + "import-selector-absent"
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "selector-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "scala3-alias-separator",
          TerminalIntervalSelector.ChildGap("imported", "renamed"),
          TerminalLeafTarget.Token(NativePsiElementBindings.ImportAliasAsTokenSurface, Some("as")),
          OccurrenceCardinality.Optional,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "scala2-alias-separator",
          TerminalIntervalSelector.ChildGap("imported", "renamed"),
          TerminalLeafTarget.Token(NativePsiElementBindings.ImportAliasArrowTokenSurface, Some("=>")),
          OccurrenceCardinality.Optional,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = ImportSelectorSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = ImportSelectorAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputRealizations = Vector(
        OutputRealization(
          "braced-named",
          Vector(
            ChildOutcomeCondition(
              "imported",
              ChildOccurrenceSelector.First,
              ChildOutcomeExpectation.Production("import-selector-name")
            ),
            ChildOutcomeCondition(
              "renamed",
              ChildOccurrenceSelector.First,
              ChildOutcomeExpectation.Production("import-selector-absent")
            )
          ),
          namedSelectorTemplate
        ),
        OutputRealization(
          "braced-alias",
          Vector(
            ChildOutcomeCondition(
              "imported",
              ChildOccurrenceSelector.First,
              ChildOutcomeExpectation.Production("import-selector-name")
            ),
            ChildOutcomeCondition(
              "renamed",
              ChildOccurrenceSelector.First,
              ChildOutcomeExpectation.Production("import-selector-name")
            )
          ),
          namedSelectorTemplate
        ),
        OutputRealization(
          "braced-hidden",
          Vector(
            ChildOutcomeCondition(
              "imported",
              ChildOccurrenceSelector.First,
              ChildOutcomeExpectation.Production("import-selector-name")
            ),
            ChildOutcomeCondition(
              "renamed",
              ChildOccurrenceSelector.First,
              ChildOutcomeExpectation.Production("import-selector-hidden-name")
            )
          ),
          namedSelectorTemplate
        ),
        OutputRealization(
          "braced-wildcard",
          Vector(
            ChildOutcomeCondition(
              "imported",
              ChildOccurrenceSelector.First,
              ChildOutcomeExpectation.Production("import-selector-wildcard-name")
            )
          ),
          selectorTemplate(OutputRangeDeclaration.CompilerPosition, "imported", "renamed", "bound")
        ),
        OutputRealization(
          "braced-given",
          Vector(
            ChildOutcomeCondition(
              "imported",
              ChildOccurrenceSelector.First,
              ChildOutcomeExpectation.Production("import-selector-empty-name")
            )
          ),
          selectorTemplate(OutputRangeDeclaration.CompilerPosition, "imported", "renamed", "bound")
        )
      )
    ),
    Scala3PsiProduction(
      id = "import-selector-name",
      grammarRoleId = GrammarRoleId.ImportSelectorName,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Ident",
        Vector(
          CompilerFieldPattern(
            "name",
            CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)
          )
        ),
        Vector(
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              "ImportSelector",
              Vector(CatalogPathSegment.NamedField("imported"))
            ),
            SourceClassification.SourceReachable
          ),
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              "ImportSelector",
              Vector(CatalogPathSegment.NamedField("renamed"))
            ),
            SourceClassification.SourceReachable
          )
        )
      ),
      dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
      children = Vector.empty,
      terminals = Vector(
        TerminalDeclaration(
          "name-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = StableReferenceSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = StableReferenceAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(transparentTemplate())
    ),
    Scala3PsiProduction(
      id = "import-selector-hidden-name",
      grammarRoleId = GrammarRoleId.ImportSelectorName,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Ident",
        Vector(
          CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Wildcard))
        ),
        Vector(
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              "ImportSelector",
              Vector(CatalogPathSegment.NamedField("renamed"))
            ),
            SourceClassification.SourceReachable
          )
        )
      ),
      dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
      children = Vector.empty,
      terminals = Vector(
        TerminalDeclaration(
          "hidden-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(NativePsiElementBindings.ImportLegacyWildcardTokenSurface, Some("_")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = ImportSelectorSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = ImportSelectorAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(transparentTemplate())
    ),
    Scala3PsiProduction(
      id = "import-selector-wildcard-name",
      grammarRoleId = GrammarRoleId.ImportSelectorName,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Ident",
        Vector(
          CompilerFieldPattern(
            "name",
            CatalogValuePattern.ClassifiedName(NeutralNameClass.Wildcard)
          )
        ),
        Vector(
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              "ImportSelector",
              Vector(CatalogPathSegment.NamedField("imported"))
            ),
            SourceClassification.SourceReachable
          )
        )
      ),
      dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
      children = Vector.empty,
      terminals = Vector(
        TerminalDeclaration(
          "wildcard-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(NativePsiElementBindings.ImportWildcardTokenSurface, Some("*")),
          OccurrenceCardinality.Optional,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "legacy-wildcard-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(NativePsiElementBindings.ImportLegacyWildcardTokenSurface, Some("_")),
          OccurrenceCardinality.Optional,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = ImportSelectorSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = ImportSelectorAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(transparentTemplate())
    ),
    Scala3PsiProduction(
      id = "import-selector-empty-name",
      grammarRoleId = GrammarRoleId.ImportSelectorName,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Ident",
        Vector(
          CompilerFieldPattern(
            "name",
            CatalogValuePattern.ClassifiedName(NeutralNameClass.Empty)
          )
        ),
        Vector(
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              "ImportSelector",
              Vector(CatalogPathSegment.NamedField("imported"))
            ),
            SourceClassification.SourceReachable
          )
        )
      ),
      dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
      children = Vector.empty,
      terminals = Vector(
        TerminalDeclaration(
          "given-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = ImportSelectorSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = ImportSelectorAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(transparentTemplate())
    ),
    Scala3PsiProduction(
      id = "import-selector-bound-type",
      grammarRoleId = GrammarRoleId.SimpleType,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Ident",
        Vector(
          CompilerFieldPattern(
            "name",
            CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)
          )
        ),
        typeAtomOccurrences
      ),
      dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
      children = Vector.empty,
      terminals = Vector(
        TerminalDeclaration(
          "type-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = SimpleTypeSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = SimpleTypeAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(
        LocalOutputCompositeTemplate(
          Vector(
            outputComposite(
              "type",
              None,
              OutputRangeDeclaration.CompilerPosition,
              PsiOutputRoleId.SimpleType,
              SimpleTypeSurface,
              SimpleTypeAccessors
            ),
            outputComposite(
              "reference",
              Some("type"),
              OutputRangeDeclaration.CompilerPosition,
              PsiOutputRoleId.StableReference,
              StableReferenceSurface,
              StableReferenceAccessors
            )
          ),
          Map.empty
        )
      )
    ),
    importSelectorAppliedTypeProduction
  )
  private[psiproducer] val PackageImportExportGivenSegment: Vector[Scala3PsiProduction]      = Vector(
    Scala3PsiProduction(
      id = "import-selector-given-bound-qualified-type",
      grammarRoleId = GrammarRoleId.SimpleType,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Select",
        Vector(
          CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
          CompilerFieldPattern("name", CatalogValuePattern.Name)
        ),
        typeAtomOccurrences.map(pattern =>
          CompilerProductionContextPattern(
            ContextPattern.SeparatorOwned(ParserScannerTokenKind.Dot, pattern.context),
            pattern.sourceClassification
          )
        )
      ),
      dispositions = Vector(
        FieldDisposition("qualifier", FieldDispositionKind.Child),
        FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)
      ),
      children = Vector(
        ChildDeclaration(
          "qualifier",
          "qualifier",
          ChildCardinality.ExactlyOne,
          "import-selector-given-bound-qualifier-ident",
          GivenTypeQualifierProductionIds - "import-selector-given-bound-qualifier-ident"
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "qualified-type-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "path-dot",
          TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.Dot),
          TerminalLeafTarget.Token(NativePsiElementBindings.TypePathDotTokenSurface, Some(".")),
          OccurrenceCardinality.Repeated(1, None),
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = SimpleTypeSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = SimpleTypeAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(qualifiedTypeTemplate)
    ),
    Scala3PsiProduction(
      id = "import-selector-given-bound-qualifier-ident",
      grammarRoleId = GrammarRoleId.StableReference,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Ident",
        Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
        typeAtomQualifierOccurrences("Select")
      ),
      dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
      children = Vector.empty,
      terminals = Vector(
        TerminalDeclaration(
          "reference-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = StableReferenceSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = StableReferenceAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(stableReferenceTemplate())
    ),
    Scala3PsiProduction(
      id = "import-selector-given-bound-qualifier-select",
      grammarRoleId = GrammarRoleId.StableReference,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Select",
        Vector(
          CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
          CompilerFieldPattern("name", CatalogValuePattern.Name)
        ),
        typeAtomQualifierOccurrences("Select")
      ),
      dispositions = Vector(
        FieldDisposition("qualifier", FieldDispositionKind.Child),
        FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)
      ),
      children = Vector(
        ChildDeclaration(
          "qualifier",
          "qualifier",
          ChildCardinality.ExactlyOne,
          "import-selector-given-bound-qualifier-ident",
          GivenTypeQualifierProductionIds - "import-selector-given-bound-qualifier-ident"
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "reference-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = StableReferenceSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = StableReferenceAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(stableReferenceTemplate("qualifier"))
    ),
    Scala3PsiProduction(
      id = "import-selector-given-bound-wildcard-type",
      grammarRoleId = GrammarRoleId.WildcardType,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "TypeBoundsTree",
        Vector(
          CompilerFieldPattern("lo", CatalogValuePattern.Node),
          CompilerFieldPattern("hi", CatalogValuePattern.Node),
          CompilerFieldPattern("alias", CatalogValuePattern.Node)
        ),
        Vector(
          CompilerProductionContextPattern(
            ContextPattern.ParentUnderAnchor(
              InventoryKind.Node,
              "AppliedTypeTree",
              Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
              GivenSelectorBoundAnchor
            ),
            SourceClassification.SourceReachable
          )
        )
      ),
      dispositions = Vector(
        FieldDisposition("lo", FieldDispositionKind.Child),
        FieldDisposition("hi", FieldDispositionKind.Child),
        FieldDisposition("alias", FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration(
          "lower-bound",
          "lo",
          ChildCardinality.ExactlyOne,
          "import-selector-given-bound-absent",
          GivenTypeProductionIds
        ),
        ChildDeclaration(
          "upper-bound",
          "hi",
          ChildCardinality.ExactlyOne,
          "import-selector-given-bound-absent",
          GivenTypeProductionIds
        ),
        ChildDeclaration(
          "alias",
          "alias",
          ChildCardinality.ExactlyOne,
          "import-selector-given-bound-absent"
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "wildcard-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "question-mark",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(NativePsiElementBindings.WildcardQuestionTokenSurface, Some("?")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "lower-bound-token",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(NativePsiElementBindings.LowerTypeBoundTokenSurface, Some(">:")),
          OccurrenceCardinality.Optional,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "upper-bound-token",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(NativePsiElementBindings.UpperTypeBoundTokenSurface, Some("<:")),
          OccurrenceCardinality.Optional,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = WildcardTypeSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = WildcardTypeAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(
        typeElementTemplate(
          PsiOutputRoleId.WildcardType,
          WildcardTypeSurface,
          WildcardTypeAccessors,
          "lower-bound",
          "upper-bound",
          "alias"
        )
      )
    ),
    Scala3PsiProduction(
      id = "import-selector-given-bound-absent",
      grammarRoleId = GrammarRoleId.AbsentProduct,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Thicket",
        Vector(CompilerFieldPattern("trees", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node))),
        Vector("lo", "hi", "alias").map: field =>
          CompilerProductionContextPattern(
            ContextPattern.ParentUnderAnchor(
              InventoryKind.Node,
              "TypeBoundsTree",
              Vector(CatalogPathSegment.NamedField(field)),
              GivenSelectorBoundAnchor
            ),
            SourceClassification.Absent
          )
      ),
      dispositions = Vector(FieldDisposition("trees", FieldDispositionKind.Synthetic)),
      children = Vector.empty,
      terminals = Vector.empty,
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = WildcardTypeSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = WildcardTypeAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(transparentTemplate())
    )
  )
  private[psiproducer] val PackageImportExportStablePathSegment: Vector[Scala3PsiProduction] = Vector(
    Scala3PsiProduction(
      id = "import-selector-absent",
      grammarRoleId = GrammarRoleId.AbsentProduct,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Thicket",
        Vector(
          CompilerFieldPattern(
            "trees",
            CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)
          )
        ),
        Vector(
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              "ImportSelector",
              Vector(CatalogPathSegment.NamedField("renamed"))
            ),
            SourceClassification.Absent
          ),
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              "ImportSelector",
              Vector(CatalogPathSegment.NamedField("bound"))
            ),
            SourceClassification.Absent
          )
        )
      ),
      dispositions = Vector(FieldDisposition("trees", FieldDispositionKind.Synthetic)),
      children = Vector.empty,
      terminals = Vector.empty,
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = ImportSelectorSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = ImportSelectorAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(transparentTemplate())
    ),
    Scala3PsiProduction(
      id = "package-stable-identifier-reference",
      grammarRoleId = GrammarRoleId.StableReference,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Ident",
        Vector(
          CompilerFieldPattern(
            "name",
            CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)
          )
        ),
        Vector(
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              "PackageDef",
              Vector(CatalogPathSegment.NamedField("pid"))
            ),
            SourceClassification.SourceReachable
          )
        )
      ),
      dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
      children = Vector.empty,
      terminals = Vector(
        TerminalDeclaration(
          "identifier-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = StableReferenceSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = StableReferenceAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = Some(PsiOutputRoleId.StableReference)
    ),
    Scala3PsiProduction(
      id = "package-stable-reference",
      grammarRoleId = GrammarRoleId.StableReference,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Select",
        Vector(
          CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
          CompilerFieldPattern("name", CatalogValuePattern.Name)
        ),
        Vector(
          CompilerProductionContextPattern(
            ContextPattern.AnchorOrParentWithRepeatedAncestor(
              InventoryAncestor(
                InventoryKind.Node,
                "PackageDef",
                Vector(CatalogPathSegment.NamedField("pid"))
              ),
              InventoryKind.Node,
              "Select",
              Vector(CatalogPathSegment.NamedField("qualifier")),
              InventoryAncestor(
                InventoryKind.Node,
                "Select",
                Vector(CatalogPathSegment.NamedField("qualifier"))
              )
            ),
            SourceClassification.SourceReachable
          )
        )
      ),
      dispositions = Vector(
        FieldDisposition("qualifier", FieldDispositionKind.Child),
        FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)
      ),
      children = Vector(
        ChildDeclaration(
          "qualifier",
          "qualifier",
          ChildCardinality.ExactlyOne,
          "package-stable-identifier",
          Set("package-stable-reference")
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "reference-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScStableCodeReferenceImpl",
      targetRequirement = TargetRequirement.Native,
      accessors = StableReferenceAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = Some(PsiOutputRoleId.StableReference)
    ),
    Scala3PsiProduction(
      id = "package-stable-identifier",
      grammarRoleId = GrammarRoleId.StableReference,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Ident",
        Vector(CompilerFieldPattern("name", CatalogValuePattern.Name)),
        Vector(
          CompilerProductionContextPattern(
            ContextPattern.ParentWithRepeatedAncestor(
              InventoryKind.Node,
              "Select",
              Vector(CatalogPathSegment.NamedField("qualifier")),
              InventoryAncestor(
                InventoryKind.Node,
                "Select",
                Vector(CatalogPathSegment.NamedField("qualifier"))
              ),
              InventoryAncestor(
                InventoryKind.Node,
                "PackageDef",
                Vector(CatalogPathSegment.NamedField("pid"))
              )
            ),
            SourceClassification.SourceReachable
          )
        )
      ),
      dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
      children = Vector.empty,
      terminals = Vector(
        TerminalDeclaration(
          "identifier-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScStableCodeReferenceImpl",
      targetRequirement = TargetRequirement.Native,
      accessors = StableReferenceAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = Some(PsiOutputRoleId.StableReference)
    )
  )
