package com.hmemcpy.metallurgy.psiproducer

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiTemplateProductions:
  private def unboundedTypeBoundsProduction = Scala3PsiDefinitionProductions.unboundedTypeBounds
  private def boundedTypeBoundsProduction   = Scala3PsiDefinitionProductions.boundedTypeBounds

  private val TemplateOwnerOccurrences = Vector(
    CompilerProductionContextPattern(
      ContextPattern.Parent(
        InventoryKind.Node,
        "PackageDef",
        Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement)
      ),
      SourceClassification.SourceReachable
    ),
    CompilerProductionContextPattern(
      ContextPattern.Parent(
        InventoryKind.Node,
        "Template",
        Vector(CatalogPathSegment.NamedField("preBody"), CatalogPathSegment.RepeatedElement)
      ),
      SourceClassification.SourceReachable
    )
  )

  private def zeroOutput(
      id: String,
      parentId: String,
      role: PsiOutputRoleId,
      surface: String,
      boundary: OutputBoundary
  ): OutputCompositeDeclaration =
    val accessors = role match
      case PsiOutputRoleId.Annotations  => AnnotationsAccessors
      case PsiOutputRoleId.ModifierList => ModifierListAccessors
      case _                            => Vector.empty
    outputComposite(
      id,
      Some(parentId),
      OutputRangeDeclaration.BoundaryDerived(boundary, boundary),
      role,
      surface,
      accessors
    )

  private def definitionTemplate(
      role: PsiOutputRoleId,
      surface: String,
      implicitConstructor: Boolean,
      wrapper: Boolean
  ): LocalOutputCompositeTemplate =
    val definitionId   = if wrapper then "case-definition" else "definition"
    val definitionRoot =
      if wrapper then
        Vector(
          outputComposite(
            "enum-cases",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.EnumCases,
            EnumCasesSurface,
            Vector.empty
          ),
          zeroOutput(
            "annotations",
            "enum-cases",
            PsiOutputRoleId.Annotations,
            AnnotationsSurface,
            OutputBoundary.ProductionStart()
          ),
          zeroOutput(
            "modifiers",
            "enum-cases",
            PsiOutputRoleId.ModifierList,
            ModifierListSurface,
            OutputBoundary.ProductionStart()
          ),
          outputComposite(
            definitionId,
            Some("enum-cases"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.ProductionPoint,
              OutputBoundary.ProductionEnd()
            ),
            role,
            surface,
            Vector.empty
          )
        )
      else
        Vector(
          outputComposite(
            definitionId,
            None,
            OutputRangeDeclaration.CompilerPosition,
            role,
            surface,
            Vector.empty
          ),
          zeroOutput(
            "annotations",
            definitionId,
            PsiOutputRoleId.Annotations,
            AnnotationsSurface,
            OutputBoundary.ProductionStart()
          ),
          zeroOutput(
            "modifiers",
            definitionId,
            PsiOutputRoleId.ModifierList,
            ModifierListSurface,
            OutputBoundary.ProductionStart()
          )
        )
    val constructor    = Option
      .when(implicitConstructor)(
        Vector(
          zeroOutput(
            "constructor",
            definitionId,
            PsiOutputRoleId.PrimaryConstructor,
            PrimaryConstructorSurface,
            OutputBoundary.ProductionNameEnd
          ),
          zeroOutput(
            "constructor-annotations",
            "constructor",
            PsiOutputRoleId.Annotations,
            AnnotationsSurface,
            OutputBoundary.ProductionNameEnd
          ),
          zeroOutput(
            "constructor-modifiers",
            "constructor",
            PsiOutputRoleId.ModifierList,
            ModifierListSurface,
            OutputBoundary.ProductionNameEnd
          ),
          zeroOutput(
            "parameter-clauses",
            "constructor",
            PsiOutputRoleId.ParameterClauses,
            ParameterClausesSurface,
            OutputBoundary.ProductionNameEnd
          )
        )
      )
      .getOrElse(Vector.empty)
    LocalOutputCompositeTemplate(
      definitionRoot ++ constructor,
      Map(
        "template"  -> Some(definitionId),
        "modifiers" -> Some(if wrapper then "enum-cases" else definitionId)
      )
    )

  private def ownerRealizations(
      role: PsiOutputRoleId,
      surface: String,
      constructorOwner: Boolean,
      allowed: Vector[(String, Boolean)],
      wrapper: Boolean
  ): Vector[OutputRealization] = allowed.map: (templateRealization, implicitConstructor) =>
    OutputRealization(
      templateRealization,
      Vector(
        ChildOutcomeCondition(
          "template",
          ChildOccurrenceSelector.First,
          ChildOutcomeExpectation.Realization(templateRealization)
        )
      ),
      definitionTemplate(role, surface, constructorOwner && implicitConstructor, wrapper)
    )

  private def templateOwnerProduction(
      id: String,
      prefix: String,
      templateField: String,
      flags: Long,
      grammarRole: GrammarRoleId,
      outputRole: PsiOutputRoleId,
      surface: String,
      constructorOwner: Boolean,
      enumCase: Boolean = false,
      classCase: Boolean = false
  ): Scala3PsiProduction =
    val occurrences =
      if enumCase then
        Vector(
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              "Template",
              Vector(CatalogPathSegment.NamedField("preBody"), CatalogPathSegment.RepeatedElement)
            ),
            SourceClassification.SourceReachable
          )
        )
      else TemplateOwnerOccurrences
    val allowed     =
      if enumCase && !classCase then Vector("absent-synthetic" -> false)
      else
        for
          constructor <- Vector("synthetic", "explicit", "typed", "type")
          body        <- Vector(false, true)
          parents     <- Vector(false, true)
          derives     <- Vector(false, true)
        yield templateRealizationId(constructor, body, parents, derives) -> (constructor == "synthetic")
    Scala3PsiProduction(
      id = id,
      grammarRoleId = grammarRole,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        prefix,
        Vector(
          CompilerFieldPattern("name", CatalogValuePattern.Name),
          CompilerFieldPattern(templateField, CatalogValuePattern.NodePrefix("Template")),
          CompilerFieldPattern("mods", emptyModifiers(flags))
        ),
        occurrences
      ),
      dispositions = Vector(
        FieldDisposition("name", FieldDispositionKind.TerminalOrLayout),
        FieldDisposition(templateField, FieldDispositionKind.Child),
        FieldDisposition("mods", FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration("template", templateField, ChildCardinality.ExactlyOne, "template-template"),
        ChildDeclaration("modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
      ),
      terminals = Vector(
        TerminalDeclaration(
          "definition-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = surface,
      targetRequirement = TargetRequirement.Native,
      accessors = Vector.empty,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRealizations = ownerRealizations(
        outputRole,
        surface,
        constructorOwner,
        allowed,
        wrapper = enumCase
      ),
      outputRoleId = None
    )

  private def templateOutputTemplate(
      body: Boolean,
      parents: Boolean,
      derives: Boolean
  ): LocalOutputCompositeTemplate =
    val headerStart        =
      if parents then
        OutputBoundary.PreviousSignificantChildTokenStart(
          "parents",
          ChildOccurrenceSelector.First,
          PositionProvenancePolicy.SourceDerivedOnly
        )
      else if derives then
        OutputBoundary.PreviousSignificantChildTokenStart(
          "derives",
          ChildOccurrenceSelector.First,
          PositionProvenancePolicy.SourceDerivedOnly
        )
      else OutputBoundary.TemplateLayoutStart
    val end                = Option
      .when(body)(
        outputComposite(
          "end",
          Some("body"),
          OutputRangeDeclaration.CompilerEndMarker,
          PsiOutputRoleId.EndStatement,
          EndSurface,
          EndAccessors
        ).copy(requiresCompilerEndMarker = true)
      )
      .toVector
    val composites         =
      if body then
        Vector(
          outputComposite(
            "extends",
            None,
            OutputRangeDeclaration.BoundaryDerived(
              headerStart,
              OutputBoundary.ParentProductionEnd
            ),
            PsiOutputRoleId.ExtendsBlock,
            ExtendsBlockSurface,
            Vector.empty
          ),
          outputComposite(
            "body",
            Some("extends"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.TemplateLayoutStart,
              OutputBoundary.ParentProductionEnd
            ),
            PsiOutputRoleId.TemplateBody,
            TemplateBodySurface,
            Vector.empty
          )
        ) ++ end
      else
        Vector(
          outputComposite(
            "extends",
            None,
            if parents || derives then
              OutputRangeDeclaration.BoundaryDerived(headerStart, OutputBoundary.ParentProductionEnd)
            else
              OutputRangeDeclaration.BoundaryDerived(
                OutputBoundary.ParentProductionEnd,
                OutputBoundary.ParentProductionEnd
              )
            ,
            PsiOutputRoleId.ExtendsBlock,
            ExtendsBlockSurface,
            Vector.empty
          )
        )
    val parentsComposite   = Option.when(parents)(
      outputComposite(
        "parents",
        Some("extends"),
        OutputRangeDeclaration.BoundaryDerived(
          OutputBoundary
            .ChildStart("parents", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly),
          OutputBoundary.ChildEnd("parents", ChildOccurrenceSelector.Last, PositionProvenancePolicy.SourceDerivedOnly)
        ),
        PsiOutputRoleId.TemplateParents,
        TemplateParentsSurface,
        TemplateParentsAccessors
      )
    )
    val parentConstructors = Option.when(parents)(
      outputComposite(
        "parent-constructor",
        Some("parents"),
        OutputRangeDeclaration.BoundaryDerived(
          OutputBoundary.ChildStart(
            "parents",
            ChildOccurrenceSelector.First,
            PositionProvenancePolicy.SourceDerivedOnly
          ),
          OutputBoundary.ChildEnd("parents", ChildOccurrenceSelector.Last, PositionProvenancePolicy.SourceDerivedOnly)
        ),
        PsiOutputRoleId.ConstructorInvocation,
        ConstructorSurface,
        ConstructorAccessors
      ).copy(realization = OutputCompositeRealization.PerChildRole("parents"))
    )
    val derivesComposite   = Option.when(derives)(
      outputComposite(
        "derives",
        Some("extends"),
        OutputRangeDeclaration.BoundaryDerived(
          OutputBoundary.PreviousSignificantChildTokenStart(
            "derives",
            ChildOccurrenceSelector.First,
            PositionProvenancePolicy.SourceDerivedOnly
          ),
          OutputBoundary.ChildEnd("derives", ChildOccurrenceSelector.Last, PositionProvenancePolicy.SourceDerivedOnly)
        ),
        PsiOutputRoleId.DerivesClause,
        DerivesClauseSurface,
        DerivesClauseAccessors
      )
    )
    LocalOutputCompositeTemplate(
      composites ++ parentsComposite ++ parentConstructors ++ derivesComposite,
      Map(
        "constructor"        -> None,
        "self"               -> Option.when(body)("body"),
        "parents"            -> Option.when(parents)("parent-constructor"),
        "derives"            -> Option.when(derives)("derives"),
        "statements"         -> Option.when(body)("body"),
        "template-modifiers" -> None
      ),
      Option.when(derives)("derives" -> PsiOutputRoleId.StableReference).toMap
    )

  private def templateRealization(
      id: String,
      constructorId: String,
      body: Boolean,
      parents: Boolean,
      derives: Boolean
  ): OutputRealization = OutputRealization(
    id,
    Vector(
      ChildOutcomeCondition(
        "constructor",
        ChildOccurrenceSelector.First,
        ChildOutcomeExpectation.Production(constructorId)
      )
    ),
    templateOutputTemplate(body, parents, derives),
    Vector(
      EvidenceCondition.TemplateBodyLayout(body),
      EvidenceCondition.LeadingBeforeRuntimeTailPresent("preParentsOrDerived", "derivedCount", parents),
      EvidenceCondition.RuntimeSupplementPositive("derivedCount", derives)
    )
  )

  private def templateRealizationId(
      constructorLabel: String,
      body: Boolean,
      parents: Boolean,
      derives: Boolean
  ): String =
    if !parents && !derives then
      constructorLabel match
        case "synthetic" => if body then "layout-synthetic" else "absent-synthetic"
        case "explicit"  => if body then "layout-explicit" else "absent-explicit"
        case "type"      => if body then "type-layout" else "type-absent"
        case "typed"     => if body then "typed-layout" else "typed-absent"
    else s"$constructorLabel-body-$body-parents-$parents-derives-$derives"

  private val templateTemplateProduction = Scala3PsiProduction(
    id = "template-template",
    grammarRoleId = GrammarRoleId.Template,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Template",
      Vector(
        CompilerFieldPattern("constr", CatalogValuePattern.Node),
        CompilerFieldPattern("preParentsOrDerived", CatalogValuePattern.Repeated(CatalogValuePattern.Node)),
        CompilerFieldPattern("self", CatalogValuePattern.Node),
        CompilerFieldPattern("preBody", CatalogValuePattern.Repeated(CatalogValuePattern.Node)),
        CompilerFieldPattern("mods", emptyModifiers(0L))
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(InventoryKind.Node, "TypeDef", Vector(CatalogPathSegment.NamedField("rhs"))),
          SourceClassification.Synthetic
        ),
        CompilerProductionContextPattern(
          ContextPattern.Parent(InventoryKind.Node, "ModuleDef", Vector(CatalogPathSegment.NamedField("impl"))),
          SourceClassification.Synthetic
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("constr", FieldDispositionKind.Child),
      FieldDisposition("preParentsOrDerived", FieldDispositionKind.Child),
      FieldDisposition("self", FieldDispositionKind.Child),
      FieldDisposition("preBody", FieldDispositionKind.Child),
      FieldDisposition("mods", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "constructor",
        "constr",
        ChildCardinality.ExactlyOne,
        "template-constructor-synthetic",
        Set(
          "template-constructor-explicit-empty",
          "template-constructor-typed-parameters",
          "template-constructor-unbounded-type-parameters"
        )
      ),
      ChildDeclaration(
        "parents",
        "preParentsOrDerived",
        ChildCardinality.Repeated(0, None),
        "import-selector-bound-type",
        TypeAtomProductionIds - "import-selector-bound-type",
        ChildSlice.LeadingBeforeRuntimeTail("derivedCount")
      ),
      ChildDeclaration(
        "derives",
        "preParentsOrDerived",
        ChildCardinality.Repeated(0, None),
        "import-selector-bound-type",
        TypeAtomProductionIds - "import-selector-bound-type",
        ChildSlice.RuntimeTail("derivedCount")
      ),
      ChildDeclaration(
        "self",
        "self",
        ChildCardinality.ExactlyOne,
        "template-self-absent",
        Set("template-self-simple")
      ),
      ChildDeclaration(
        "statements",
        "preBody",
        ChildCardinality.Grouped(0, None),
        "template-absent-tree",
        Set(
          "template-class-definition",
          "template-trait-definition",
          "template-object-definition",
          "template-enum-definition",
          "enum-singleton-case",
          "enum-class-case",
          "definition-function-untyped",
          "definition-val-untyped",
          "definition-var-untyped",
          "definition-unbounded-type-alias"
        ) ++ SimpleTypeAliasProductionIds
      ),
      ChildDeclaration("template-modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
    ),
    terminals = Vector(
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
    targetSurfaceId = ExtendsBlockSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRealizations = (for
      (constructorLabel, constructorId) <- Vector(
                                             "synthetic" -> "template-constructor-synthetic",
                                             "explicit"  -> "template-constructor-explicit-empty",
                                             "typed"     -> "template-constructor-typed-parameters",
                                             "type"      -> "template-constructor-unbounded-type-parameters"
                                           )
      body                              <- Vector(false, true)
      parents                           <- Vector(false, true)
      derives                           <- Vector(false, true)
    yield templateRealization(
      templateRealizationId(constructorLabel, body, parents, derives),
      constructorId,
      body,
      parents,
      derives
    )),
    outputRoleId = None
  )

  private val templateConstructorSyntheticProduction = Scala3PsiProduction(
    id = "template-constructor-synthetic",
    grammarRoleId = GrammarRoleId.TemplateConstructor,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "DefDef",
      Vector(
        CompilerFieldPattern("name", CatalogValuePattern.Name),
        CompilerFieldPattern("paramss", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)),
        CompilerFieldPattern("tpt", CatalogValuePattern.Node),
        CompilerFieldPattern("preRhs", CatalogValuePattern.Node),
        CompilerFieldPattern("mods", emptyModifiers(0L))
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(InventoryKind.Node, "Template", Vector(CatalogPathSegment.NamedField("constr"))),
          SourceClassification.Synthetic
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("name", FieldDispositionKind.SemanticOnly),
      FieldDisposition("paramss", FieldDispositionKind.Synthetic),
      FieldDisposition("tpt", FieldDispositionKind.Child),
      FieldDisposition("preRhs", FieldDispositionKind.Child),
      FieldDisposition("mods", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration("type-tree", "tpt", ChildCardinality.ExactlyOne, "template-type-tree-synthetic"),
      ChildDeclaration("rhs", "preRhs", ChildCardinality.ExactlyOne, "template-absent-tree"),
      ChildDeclaration("constructor-modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
    ),
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = PrimaryConstructorSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(transparentTemplate("type-tree", "rhs", "constructor-modifiers")),
    outputRoleId = None
  )

  private val templateConstructorExplicitProduction = templateConstructorSyntheticProduction.copy(
    id = "template-constructor-explicit-empty",
    pattern = templateConstructorSyntheticProduction.pattern.copy(
      fields = templateConstructorSyntheticProduction.pattern.fields.updated(
        1,
        CompilerFieldPattern(
          "paramss",
          CatalogValuePattern.Repeated(CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node))
        )
      ),
      occurrences = templateConstructorSyntheticProduction.pattern.occurrences.map(
        _.copy(sourceClassification = SourceClassification.SourceReachable)
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "constructor-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "constructor",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.PrimaryConstructor,
            PrimaryConstructorSurface,
            Vector.empty
          ),
          zeroOutput(
            "annotations",
            "constructor",
            PsiOutputRoleId.Annotations,
            AnnotationsSurface,
            OutputBoundary.ProductionStart()
          ),
          zeroOutput(
            "modifiers",
            "constructor",
            PsiOutputRoleId.ModifierList,
            ModifierListSurface,
            OutputBoundary.ProductionStart()
          ),
          outputComposite(
            "parameter-clauses",
            Some("constructor"),
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.ParameterClauses,
            ParameterClausesSurface,
            Vector.empty
          ),
          outputComposite(
            "parameter-clause",
            Some("parameter-clauses"),
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.ParameterClause,
            ParameterClauseSurface,
            Vector.empty
          ).copy(
            realization = OutputCompositeRealization.PerRepeatedFieldOccurrence(
              "paramss",
              CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node),
              ClosedSourceLexicalKind.LeftParenthesis,
              ClosedSourceLexicalKind.RightParenthesis
            )
          )
        ),
        Map(
          "type-tree"             -> None,
          "rhs"                   -> None,
          "constructor-modifiers" -> None
        )
      )
    )
  )

  private val templateConstructorTypedParametersProduction = templateConstructorExplicitProduction.copy(
    id = "template-constructor-typed-parameters",
    pattern = templateConstructorExplicitProduction.pattern.copy(
      fields = templateConstructorExplicitProduction.pattern.fields.updated(
        1,
        CompilerFieldPattern(
          "paramss",
          CatalogValuePattern.Repeated(
            CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef"))
          )
        )
      )
    ),
    dispositions = templateConstructorExplicitProduction.dispositions.updated(
      1,
      FieldDisposition("paramss", FieldDispositionKind.Child)
    ),
    children = templateConstructorExplicitProduction.children :+ ChildDeclaration(
      "parameters",
      "paramss",
      ChildCardinality.Repeated(1, None),
      "template-class-parameter",
      Set("template-context-class-parameter", "template-enum-class-parameter")
    ),
    outputTemplate = templateConstructorExplicitProduction.outputTemplate.map(template =>
      template.copy(
        composites = template.composites.map:
          case output if output.id == "parameter-clause" =>
            output.copy(
              realization = OutputCompositeRealization.PerRepeatedFieldOccurrence(
                "paramss",
                CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
                ClosedSourceLexicalKind.LeftParenthesis,
                ClosedSourceLexicalKind.RightParenthesis
              )
            )
          case other                                     => other,
        childMounts = template.childMounts + ("parameters" -> Some("parameter-clause"))
      )
    )
  )

  private val templateConstructorTypeParametersProduction = templateConstructorExplicitProduction.copy(
    id = "template-constructor-unbounded-type-parameters",
    pattern = templateConstructorExplicitProduction.pattern.copy(
      fields = templateConstructorExplicitProduction.pattern.fields.updated(
        1,
        CompilerFieldPattern(
          "paramss",
          CatalogValuePattern.LeadingThenRepeated(
            CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("TypeDef")),
            CatalogValuePattern.Repeated(CatalogValuePattern.Node)
          )
        )
      )
    ),
    dispositions = templateConstructorExplicitProduction.dispositions.updated(
      1,
      FieldDisposition("paramss", FieldDispositionKind.Child)
    ),
    children = templateConstructorExplicitProduction.children ++ Vector(
      ChildDeclaration(
        "parameters",
        "paramss",
        ChildCardinality.Repeated(0, None),
        "template-class-parameter",
        Set("template-context-class-parameter", "template-enum-class-parameter"),
        ChildSlice.MatchingProductions
      ),
      ChildDeclaration(
        "type-parameters",
        "paramss",
        ChildCardinality.Repeated(1, None),
        "template-unbounded-type-parameter-invariant",
        Set(
          "template-unbounded-type-parameter-covariant",
          "template-unbounded-type-parameter-contravariant",
          "template-higher-kinded-type-parameter-invariant",
          "template-higher-kinded-type-parameter-covariant",
          "template-higher-kinded-type-parameter-contravariant",
          "template-context-bounded-type-parameter-invariant",
          "template-context-bounded-type-parameter-covariant",
          "template-context-bounded-type-parameter-contravariant"
        ),
        ChildSlice.MatchingProductions
      )
    ),
    outputTemplate = None,
    outputRealizations = Vector(
      OutputRealization(
        "without-empty-term-clauses",
        Vector.empty,
        LocalOutputCompositeTemplate(
          Vector(
            outputComposite(
              "type-parameter-clause",
              None,
              OutputRangeDeclaration.BoundaryDerived(
                OutputBoundary.ProductionStart(),
                OutputBoundary.ProductionEnd()
              ),
              PsiOutputRoleId.TypeParameterClause,
              TypeParameterClauseSurface,
              Vector.empty
            ).copy(
              realization = OutputCompositeRealization.PerRepeatedFieldOccurrence(
                "paramss",
                CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.Node),
                ClosedSourceLexicalKind.LeftBracket,
                ClosedSourceLexicalKind.RightBracket
              )
            ),
            zeroOutput(
              "constructor",
              "type-parameter-clause",
              PsiOutputRoleId.PrimaryConstructor,
              PrimaryConstructorSurface,
              OutputBoundary.ProductionEnd()
            ).copy(parentId = None),
            zeroOutput(
              "annotations",
              "constructor",
              PsiOutputRoleId.Annotations,
              AnnotationsSurface,
              OutputBoundary.ProductionEnd()
            ),
            zeroOutput(
              "modifiers",
              "constructor",
              PsiOutputRoleId.ModifierList,
              ModifierListSurface,
              OutputBoundary.ProductionEnd()
            ),
            zeroOutput(
              "parameter-clauses",
              "constructor",
              PsiOutputRoleId.ParameterClauses,
              ParameterClausesSurface,
              OutputBoundary.ProductionEnd()
            )
          ),
          Map(
            "type-tree"             -> None,
            "rhs"                   -> None,
            "constructor-modifiers" -> None,
            "parameters"            -> None,
            "type-parameters"       -> Some("type-parameter-clause")
          )
        ),
        Vector(
          EvidenceCondition.RepeatedFieldOccurrence(
            "paramss",
            CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node),
            present = false
          ),
          EvidenceCondition.RepeatedFieldOccurrence(
            "paramss",
            CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
            present = false
          )
        )
      ),
      OutputRealization(
        "with-typed-term-clauses",
        Vector.empty,
        LocalOutputCompositeTemplate(
          Vector(
            outputComposite(
              "type-parameter-clause",
              None,
              OutputRangeDeclaration.BoundaryDerived(
                OutputBoundary.ChildStart(
                  "type-parameters",
                  ChildOccurrenceSelector.First,
                  PositionProvenancePolicy.SourceDerivedOnly
                ),
                OutputBoundary.ChildEnd(
                  "type-parameters",
                  ChildOccurrenceSelector.Last,
                  PositionProvenancePolicy.SourceDerivedOnly
                )
              ),
              PsiOutputRoleId.TypeParameterClause,
              TypeParameterClauseSurface,
              Vector.empty
            ).copy(
              realization = OutputCompositeRealization.PerRepeatedFieldOccurrence(
                "paramss",
                CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("TypeDef")),
                ClosedSourceLexicalKind.LeftBracket,
                ClosedSourceLexicalKind.RightBracket
              )
            ),
            outputComposite(
              "constructor",
              None,
              OutputRangeDeclaration.BoundaryDerived(
                OutputBoundary.ChildStart(
                  "parameters",
                  ChildOccurrenceSelector.First,
                  PositionProvenancePolicy.SourceDerivedOnly
                ),
                OutputBoundary.ChildEnd(
                  "parameters",
                  ChildOccurrenceSelector.Last,
                  PositionProvenancePolicy.SourceDerivedOnly
                )
              ),
              PsiOutputRoleId.PrimaryConstructor,
              PrimaryConstructorSurface,
              Vector.empty
            ).copy(
              realization = OutputCompositeRealization.AcrossRepeatedFieldOccurrences(
                "paramss",
                CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
                ClosedSourceLexicalKind.LeftParenthesis,
                ClosedSourceLexicalKind.RightParenthesis
              )
            ),
            outputComposite(
              "annotations",
              Some("constructor"),
              OutputRangeDeclaration.BoundaryDerived(
                OutputBoundary.ChildStart(
                  "parameters",
                  ChildOccurrenceSelector.First,
                  PositionProvenancePolicy.SourceDerivedOnly
                ),
                OutputBoundary.ChildStart(
                  "parameters",
                  ChildOccurrenceSelector.First,
                  PositionProvenancePolicy.SourceDerivedOnly
                )
              ),
              PsiOutputRoleId.Annotations,
              AnnotationsSurface,
              AnnotationsAccessors
            ).copy(
              realization = OutputCompositeRealization.AtFirstRepeatedFieldOccurrenceStart(
                "paramss",
                CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
                ClosedSourceLexicalKind.LeftParenthesis,
                ClosedSourceLexicalKind.RightParenthesis
              )
            ),
            outputComposite(
              "modifiers",
              Some("constructor"),
              OutputRangeDeclaration.BoundaryDerived(
                OutputBoundary.ChildStart(
                  "parameters",
                  ChildOccurrenceSelector.First,
                  PositionProvenancePolicy.SourceDerivedOnly
                ),
                OutputBoundary.ChildStart(
                  "parameters",
                  ChildOccurrenceSelector.First,
                  PositionProvenancePolicy.SourceDerivedOnly
                )
              ),
              PsiOutputRoleId.ModifierList,
              ModifierListSurface,
              ModifierListAccessors
            ).copy(
              realization = OutputCompositeRealization.AtFirstRepeatedFieldOccurrenceStart(
                "paramss",
                CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
                ClosedSourceLexicalKind.LeftParenthesis,
                ClosedSourceLexicalKind.RightParenthesis
              )
            ),
            outputComposite(
              "parameter-clauses",
              Some("constructor"),
              OutputRangeDeclaration.CompilerPosition,
              PsiOutputRoleId.ParameterClauses,
              ParameterClausesSurface,
              Vector.empty
            ).copy(
              realization = OutputCompositeRealization.AcrossRepeatedFieldOccurrences(
                "paramss",
                CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
                ClosedSourceLexicalKind.LeftParenthesis,
                ClosedSourceLexicalKind.RightParenthesis
              )
            ),
            outputComposite(
              "parameter-clause",
              Some("parameter-clauses"),
              OutputRangeDeclaration.CompilerPosition,
              PsiOutputRoleId.ParameterClause,
              ParameterClauseSurface,
              Vector.empty
            ).copy(
              realization = OutputCompositeRealization.PerRepeatedFieldOccurrence(
                "paramss",
                CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
                ClosedSourceLexicalKind.LeftParenthesis,
                ClosedSourceLexicalKind.RightParenthesis
              )
            )
          ),
          Map(
            "type-tree"             -> None,
            "rhs"                   -> None,
            "constructor-modifiers" -> None,
            "parameters"            -> Some("parameter-clause"),
            "type-parameters"       -> Some("type-parameter-clause")
          )
        ),
        Vector(
          EvidenceCondition.RepeatedFieldOccurrence(
            "paramss",
            CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node),
            present = false
          ),
          EvidenceCondition.RepeatedFieldOccurrence(
            "paramss",
            CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
            present = true
          )
        )
      ),
      OutputRealization(
        "with-empty-term-clauses",
        Vector.empty,
        templateConstructorExplicitProduction.outputTemplate.get.copy(
          composites = templateConstructorExplicitProduction.outputTemplate.get.composites
            .map:
              case output if Set("constructor", "parameter-clauses").contains(output.id) =>
                output.copy(
                  realization = OutputCompositeRealization.AcrossRepeatedFieldOccurrences(
                    "paramss",
                    CatalogValuePattern.AnyOf(
                      Vector(
                        CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node),
                        CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef"))
                      )
                    ),
                    ClosedSourceLexicalKind.LeftParenthesis,
                    ClosedSourceLexicalKind.RightParenthesis
                  )
                )
              case output if Set("annotations", "modifiers").contains(output.id)         =>
                output.copy(
                  realization = OutputCompositeRealization.AtFirstRepeatedFieldOccurrenceStart(
                    "paramss",
                    CatalogValuePattern.AnyOf(
                      Vector(
                        CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node),
                        CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef"))
                      )
                    ),
                    ClosedSourceLexicalKind.LeftParenthesis,
                    ClosedSourceLexicalKind.RightParenthesis
                  )
                )
              case output if output.id == "parameter-clause"                             =>
                output.copy(
                  realization = OutputCompositeRealization.PerRepeatedFieldOccurrence(
                    "paramss",
                    CatalogValuePattern.AnyOf(
                      Vector(
                        CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node),
                        CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef"))
                      )
                    ),
                    ClosedSourceLexicalKind.LeftParenthesis,
                    ClosedSourceLexicalKind.RightParenthesis
                  )
                )
              case other                                                                 => other
          :+ outputComposite(
            "type-parameter-clause",
            None,
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.ProductionStart(),
              OutputBoundary.ProductionEnd()
            ),
            PsiOutputRoleId.TypeParameterClause,
            TypeParameterClauseSurface,
            Vector.empty
          ).copy(
            realization = OutputCompositeRealization.PerRepeatedFieldOccurrence(
              "paramss",
              CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("TypeDef")),
              ClosedSourceLexicalKind.LeftBracket,
              ClosedSourceLexicalKind.RightBracket
            )
          ),
          childMounts = templateConstructorExplicitProduction.outputTemplate.get.childMounts ++ Map(
            "parameters"      -> Some("parameter-clause"),
            "type-parameters" -> Some("type-parameter-clause")
          )
        ),
        Vector(
          EvidenceCondition.RepeatedFieldOccurrence(
            "paramss",
            CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node),
            present = true
          )
        )
      )
    )
  )

  private def varianceTerminals(id: String): Vector[TerminalDeclaration] =
    Option
      .when(id.endsWith("-covariant"))(
        TerminalDeclaration(
          "covariance",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(NativePsiElementBindings.VarianceTokenSurface, Some("+")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      )
      .toVector ++ Option
      .when(id.endsWith("-contravariant"))(
        TerminalDeclaration(
          "contravariance",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(NativePsiElementBindings.VarianceTokenSurface, Some("-")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      )
      .toVector

  private val contextBoundContainerTerminals = Vector(
    TerminalDeclaration(
      "context-bound-colons",
      TerminalIntervalSelector.WholeProduction,
      TerminalLeafTarget.Token(NativePsiElementBindings.ContextBoundColonTokenSurface, Some(":")),
      OccurrenceCardinality.Repeated(1, None),
      PsiOutputRoleId.SourceTerminal
    ),
    TerminalDeclaration(
      "context-bound-left-brace",
      TerminalIntervalSelector.WholeProduction,
      TerminalLeafTarget.Token(NativePsiElementBindings.ContextBoundLeftBraceTokenSurface, Some("{")),
      OccurrenceCardinality.Optional,
      PsiOutputRoleId.SourceTerminal
    ),
    TerminalDeclaration(
      "context-bound-right-brace",
      TerminalIntervalSelector.WholeProduction,
      TerminalLeafTarget.Token(NativePsiElementBindings.ContextBoundRightBraceTokenSurface, Some("}")),
      OccurrenceCardinality.Optional,
      PsiOutputRoleId.SourceTerminal
    ),
    TerminalDeclaration(
      "context-bound-commas",
      TerminalIntervalSelector.WholeProduction,
      TerminalLeafTarget.Token(NativePsiElementBindings.ContextBoundCommaTokenSurface, Some(",")),
      OccurrenceCardinality.Repeated(0, None),
      PsiOutputRoleId.SourceTerminal
    ),
    TerminalDeclaration(
      "context-bound-as",
      TerminalIntervalSelector.WholeProduction,
      TerminalLeafTarget.Token(NativePsiElementBindings.ContextBoundAsTokenSurface, Some("as")),
      OccurrenceCardinality.Repeated(0, None),
      PsiOutputRoleId.SourceTerminal
    )
  )

  private def unboundedTypeParameterProduction(id: String, flags: Long): Scala3PsiProduction = Scala3PsiProduction(
    id = id,
    grammarRoleId = GrammarRoleId.UnboundedTypeParameter,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "TypeDef",
      Vector(
        CompilerFieldPattern("name", CatalogValuePattern.Name),
        CompilerFieldPattern("rhs", CatalogValuePattern.NodePrefix("TypeBoundsTree")),
        CompilerFieldPattern("mods", emptyModifiers(flags))
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "DefDef",
            Vector(
              CatalogPathSegment.NamedField("paramss"),
              CatalogPathSegment.RepeatedElement,
              CatalogPathSegment.RepeatedElement
            )
          ),
          SourceClassification.SourceReachable
        )
      ) ++ OwnerTypeAnchors.map(anchor =>
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchor(
            InventoryKind.Node,
            "LambdaTypeTree",
            Vector(CatalogPathSegment.NamedField("tparams"), CatalogPathSegment.RepeatedElement),
            anchor
          ),
          SourceClassification.SourceReachable
        )
      ) ++ OwnerTypeAnchors.map(anchor =>
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchor(
            InventoryKind.Node,
            "PolyFunction",
            Vector(CatalogPathSegment.NamedField("targs"), CatalogPathSegment.RepeatedElement),
            anchor
          ),
          SourceClassification.SourceReachable
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("name", FieldDispositionKind.TerminalOrLayout),
      FieldDisposition("rhs", FieldDispositionKind.Child),
      FieldDisposition("mods", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "bounds",
        "rhs",
        ChildCardinality.ExactlyOne,
        "template-unbounded-type-bounds",
        Set("type-parameter-bounds")
      ),
      ChildDeclaration("type-parameter-modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
    ),
    terminals = Vector(
      TerminalDeclaration(
        "type-parameter-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ) ++ varianceTerminals(id),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = TypeParameterSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = TypeParameterAccessors,
    persistence = parameterPersistence(PsiOutputRoleId.TypeParameter),
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "type-parameter",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.TypeParameter,
            TypeParameterSurface,
            TypeParameterAccessors
          )
        ),
        Map("bounds" -> Some("type-parameter"), "type-parameter-modifiers" -> None)
      )
    ),
    outputRoleId = None,
    additionalGrammarRoleIds = Set(GrammarRoleId.BoundedTypeParameter)
  )

  private def contextBoundedTypeParameterProduction(id: String, flags: Long): Scala3PsiProduction =
    val base = unboundedTypeParameterProduction(id, flags)
    base.copy(
      grammarRoleId = GrammarRoleId.ContextBounds,
      pattern = base.pattern.copy(fields =
        base.pattern.fields.updated(1, CompilerFieldPattern("rhs", CatalogValuePattern.NodePrefix("ContextBounds")))
      ),
      children = Vector(
        ChildDeclaration("context-bounds", "rhs", ChildCardinality.ExactlyOne, "type-parameter-context-bounds"),
        ChildDeclaration("type-parameter-modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
      ),
      terminals = base.terminals ++ contextBoundContainerTerminals,
      outputTemplate = base.outputTemplate.map(template =>
        template.copy(childMounts = Map("context-bounds" -> Some("type-parameter"), "type-parameter-modifiers" -> None))
      )
    )

  private def higherKindedTypeParameterProduction(id: String, flags: Long): Scala3PsiProduction = Scala3PsiProduction(
    id = id,
    grammarRoleId = GrammarRoleId.HigherKindedTypeParameter,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "TypeDef",
      Vector(
        CompilerFieldPattern("name", CatalogValuePattern.Name),
        CompilerFieldPattern("rhs", CatalogValuePattern.NodePrefix("LambdaTypeTree")),
        CompilerFieldPattern("mods", emptyModifiers(flags))
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "DefDef",
            Vector(
              CatalogPathSegment.NamedField("paramss"),
              CatalogPathSegment.RepeatedElement,
              CatalogPathSegment.RepeatedElement
            )
          ),
          SourceClassification.SourceReachable
        )
      ) ++ OwnerTypeAnchors.map(anchor =>
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchor(
            InventoryKind.Node,
            "LambdaTypeTree",
            Vector(CatalogPathSegment.NamedField("tparams"), CatalogPathSegment.RepeatedElement),
            anchor
          ),
          SourceClassification.SourceReachable
        )
      ) ++ OwnerTypeAnchors.map(anchor =>
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchor(
            InventoryKind.Node,
            "PolyFunction",
            Vector(CatalogPathSegment.NamedField("targs"), CatalogPathSegment.RepeatedElement),
            anchor
          ),
          SourceClassification.SourceReachable
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("name", FieldDispositionKind.TerminalOrLayout),
      FieldDisposition("rhs", FieldDispositionKind.Child),
      FieldDisposition("mods", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration("nested-parameters", "rhs", ChildCardinality.ExactlyOne, "higher-kinded-parameter-lambda"),
      ChildDeclaration("type-parameter-modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
    ),
    terminals = Vector(
      TerminalDeclaration(
        "type-parameter-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ) ++ varianceTerminals(id),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = TypeParameterSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = TypeParameterAccessors,
    persistence = parameterPersistence(PsiOutputRoleId.TypeParameter),
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "type-parameter",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.TypeParameter,
            TypeParameterSurface,
            TypeParameterAccessors
          )
        ),
        Map("nested-parameters" -> Some("type-parameter"), "type-parameter-modifiers" -> None)
      )
    ),
    outputRoleId = None
  )

  private val higherKindedParameterLambdaProduction = Scala3PsiProduction(
    id = "higher-kinded-parameter-lambda",
    grammarRoleId = GrammarRoleId.TypeParameterClause,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "LambdaTypeTree",
      Vector(
        CompilerFieldPattern("tparams", CatalogValuePattern.Repeated(CatalogValuePattern.Node)),
        CompilerFieldPattern("body", CatalogValuePattern.Node)
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestorPrefix(
            InventoryKind.Node,
            "TypeDef",
            Vector(CatalogPathSegment.NamedField("rhs")),
            Vector(
              InventoryAncestor(
                InventoryKind.Node,
                "DefDef",
                Vector(
                  CatalogPathSegment.NamedField("paramss"),
                  CatalogPathSegment.RepeatedElement,
                  CatalogPathSegment.RepeatedElement
                )
              )
            )
          ),
          SourceClassification.Synthetic
        ),
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestorPrefix(
            InventoryKind.Node,
            "TypeDef",
            Vector(CatalogPathSegment.NamedField("rhs")),
            Vector(
              InventoryAncestor(
                InventoryKind.Node,
                "LambdaTypeTree",
                Vector(CatalogPathSegment.NamedField("tparams"), CatalogPathSegment.RepeatedElement)
              )
            )
          ),
          SourceClassification.Synthetic
        )
      ) ++ OwnerTypeAnchors.map: anchor =>
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestorPrefix(
            InventoryKind.Node,
            "TypeDef",
            Vector(CatalogPathSegment.NamedField("rhs")),
            Vector(
              InventoryAncestor(
                InventoryKind.Node,
                "PolyFunction",
                Vector(CatalogPathSegment.NamedField("targs"), CatalogPathSegment.RepeatedElement)
              ),
              anchor
            )
          ),
          SourceClassification.Synthetic
        )
    ),
    dispositions = Vector(
      FieldDisposition("tparams", FieldDispositionKind.Child),
      FieldDisposition("body", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "parameters",
        "tparams",
        ChildCardinality.Repeated(1, None),
        "function-unbounded-type-parameter",
        Set(
          "template-unbounded-type-parameter-invariant",
          "template-unbounded-type-parameter-covariant",
          "template-unbounded-type-parameter-contravariant",
          "template-higher-kinded-type-parameter-invariant",
          "template-higher-kinded-type-parameter-covariant",
          "template-higher-kinded-type-parameter-contravariant",
          "higher-kinded-nested-type-parameter"
        )
      ),
      ChildDeclaration(
        "body",
        "body",
        ChildCardinality.ExactlyOne,
        "higher-kinded-result-bounds",
        Set("type-parameter-context-bounds")
      )
    ),
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = TypeParameterClauseSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = parameterPersistence(PsiOutputRoleId.TypeParameterClause),
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "clause",
            None,
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.ChildStart(
                "parameters",
                ChildOccurrenceSelector.First,
                PositionProvenancePolicy.SourceDerivedOnly
              ),
              OutputBoundary.ChildEnd(
                "parameters",
                ChildOccurrenceSelector.Last,
                PositionProvenancePolicy.SourceDerivedOnly
              )
            ),
            PsiOutputRoleId.TypeParameterClause,
            TypeParameterClauseSurface,
            Vector.empty
          ).copy(
            realization = OutputCompositeRealization.AcrossRepeatedFieldOccurrences(
              "tparams",
              CatalogValuePattern.NodePrefix("TypeDef"),
              ClosedSourceLexicalKind.LeftBracket,
              ClosedSourceLexicalKind.RightBracket
            )
          )
        ),
        Map("parameters" -> Some("clause"), "body" -> None)
      )
    ),
    outputRoleId = None
  )

  private val higherKindedResultBoundsProduction = unboundedTypeBoundsProduction.copy(
    id = "higher-kinded-result-bounds",
    grammarRoleId = GrammarRoleId.TypeBounds,
    pattern = unboundedTypeBoundsProduction.pattern.copy(
      occurrences = OwnerTypeAnchors.map: anchor =>
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchor(
            InventoryKind.Node,
            "LambdaTypeTree",
            Vector(CatalogPathSegment.NamedField("body")),
            anchor
          ),
          SourceClassification.Synthetic
        )
    ),
    children = Vector(
      ChildDeclaration("lower", "lo", ChildCardinality.ExactlyOne, "template-absent-tree"),
      ChildDeclaration("upper", "hi", ChildCardinality.ExactlyOne, "template-absent-tree"),
      ChildDeclaration("alias", "alias", ChildCardinality.ExactlyOne, "template-absent-tree")
    )
  )

  private val contextBoundBaseBoundsProduction = unboundedTypeBoundsProduction.copy(
    id = "context-bound-base-bounds",
    grammarRoleId = GrammarRoleId.TypeBounds,
    pattern = unboundedTypeBoundsProduction.pattern.copy(
      occurrences = Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(InventoryKind.Node, "ContextBounds", Vector(CatalogPathSegment.NamedField("bounds"))),
          SourceClassification.Synthetic
        )
      )
    )
  )

  private val typeParameterContextBoundsProduction = Scala3PsiProduction(
    id = "type-parameter-context-bounds",
    grammarRoleId = GrammarRoleId.ContextBounds,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "ContextBounds",
      Vector(
        CompilerFieldPattern("bounds", CatalogValuePattern.NodePrefix("TypeBoundsTree")),
        CompilerFieldPattern(
          "cxBounds",
          CatalogValuePattern.Repeated(CatalogValuePattern.NodePrefix("ContextBoundTypeTree"))
        )
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(InventoryKind.Node, "TypeDef", Vector(CatalogPathSegment.NamedField("rhs"))),
          SourceClassification.SourceReachable
        ),
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestorPrefix(
            InventoryKind.Node,
            "LambdaTypeTree",
            Vector(CatalogPathSegment.NamedField("body")),
            Vector(InventoryAncestor(InventoryKind.Node, "TypeDef", Vector(CatalogPathSegment.NamedField("rhs"))))
          ),
          SourceClassification.SourceReachable
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("bounds", FieldDispositionKind.Child),
      FieldDisposition("cxBounds", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration("bounds", "bounds", ChildCardinality.ExactlyOne, "context-bound-base-bounds"),
      ChildDeclaration(
        "context-bound",
        "cxBounds",
        ChildCardinality.Repeated(1, None),
        "type-parameter-context-bound",
        Set("type-parameter-named-context-bound", "type-parameter-synthetic-context-bound")
      )
    ),
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ContextBoundSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    navigation = None,
    outputTemplate = Some(transparentTemplate("bounds", "context-bound")),
    outputRoleId = None
  )

  private val typeParameterContextBoundProduction = Scala3PsiProduction(
    id = "type-parameter-context-bound",
    grammarRoleId = GrammarRoleId.ContextBound,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "ContextBoundTypeTree",
      Vector(
        CompilerFieldPattern("tycon", CatalogValuePattern.Node),
        CompilerFieldPattern("paramName", CatalogValuePattern.Name),
        CompilerFieldPattern("ownName", CatalogValuePattern.ClassifiedName(NeutralNameClass.Empty))
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "ContextBounds",
            Vector(CatalogPathSegment.NamedField("cxBounds"), CatalogPathSegment.RepeatedElement)
          ),
          SourceClassification.SourceReachable
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("tycon", FieldDispositionKind.Child),
      FieldDisposition("paramName", FieldDispositionKind.Synthetic),
      FieldDisposition("ownName", FieldDispositionKind.Synthetic)
    ),
    children = Vector(
      ChildDeclaration(
        "bound-type",
        "tycon",
        ChildCardinality.ExactlyOne,
        TypeAtomProductionIds.toVector.sorted.head,
        TypeAtomProductionIds - TypeAtomProductionIds.toVector.sorted.head + "explicit-type-lambda"
      )
    ),
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ContextBoundSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = ContextBoundAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "context-bound",
            None,
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.ChildStart(
                "bound-type",
                ChildOccurrenceSelector.First,
                PositionProvenancePolicy.SourceDerivedOnly
              ),
              OutputBoundary.ChildEnd(
                "bound-type",
                ChildOccurrenceSelector.First,
                PositionProvenancePolicy.SourceDerivedOnly
              )
            ),
            PsiOutputRoleId.ContextBound,
            ContextBoundSurface,
            ContextBoundAccessors
          )
        ),
        Map("bound-type" -> Some("context-bound"))
      )
    ),
    outputRoleId = None
  )

  private val typeParameterSyntheticContextBoundProduction = typeParameterContextBoundProduction.copy(
    id = "type-parameter-synthetic-context-bound",
    pattern = typeParameterContextBoundProduction.pattern.copy(
      occurrences = Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "ContextBounds",
            Vector(CatalogPathSegment.NamedField("cxBounds"), CatalogPathSegment.RepeatedElement)
          ),
          SourceClassification.Synthetic
        )
      )
    ),
    targetSurfaceId = ExpressionPayloadSurface,
    targetRequirement = TargetRequirement.Compatible,
    accessors = Vector.empty,
    navigation = None,
    outputTemplate = Some(transparentTemplate("bound-type"))
  )

  private val typeParameterNamedContextBoundProduction = typeParameterContextBoundProduction.copy(
    id = "type-parameter-named-context-bound",
    pattern = typeParameterContextBoundProduction.pattern.copy(
      fields = typeParameterContextBoundProduction.pattern.fields.updated(
        2,
        CompilerFieldPattern("ownName", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))
      ),
      occurrences = typeParameterContextBoundProduction.pattern.occurrences.filter(
        _.sourceClassification == SourceClassification.SourceReachable
      )
    ),
    dispositions = typeParameterContextBoundProduction.dispositions.updated(
      2,
      FieldDisposition("ownName", FieldDispositionKind.TerminalOrLayout)
    ),
    terminals = Vector(
      TerminalDeclaration(
        "context-bound-name",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    outputTemplate = typeParameterContextBoundProduction.outputTemplate.map(template =>
      template.copy(composites =
        template.composites.map(composite =>
          composite.copy(range =
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.ChildStart(
                "bound-type",
                ChildOccurrenceSelector.First,
                PositionProvenancePolicy.SourceDerivedOnly
              ),
              OutputBoundary.ProductionEnd(PositionProvenancePolicy.SourceDerivedOnly)
            )
          )
        )
      )
    )
  )

  private val templateTypeTreeProduction = Scala3PsiProduction(
    id = "template-type-tree-synthetic",
    grammarRoleId = GrammarRoleId.TemplateTypeTree,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "TypeTree",
      Vector.empty,
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestor(
            InventoryKind.Node,
            "DefDef",
            Vector(CatalogPathSegment.NamedField("tpt")),
            InventoryAncestor(InventoryKind.Node, "Template", Vector(CatalogPathSegment.NamedField("constr")))
          ),
          SourceClassification.Synthetic
        )
      )
    ),
    dispositions = Vector.empty,
    children = Vector.empty,
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = PrimaryConstructorSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(transparentTemplate()),
    outputRoleId = None
  )

  private val templateSelfProduction = Scala3PsiProduction(
    id = "template-self-absent",
    grammarRoleId = GrammarRoleId.TemplateSelf,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "ValDef",
      Vector(
        CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Wildcard)),
        CompilerFieldPattern("tpt", CatalogValuePattern.Node),
        CompilerFieldPattern("preRhs", CatalogValuePattern.Node),
        CompilerFieldPattern("mods", emptyModifiers(8199L))
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(InventoryKind.Node, "Template", Vector(CatalogPathSegment.NamedField("self"))),
          SourceClassification.Absent
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("name", FieldDispositionKind.SemanticOnly),
      FieldDisposition("tpt", FieldDispositionKind.Child),
      FieldDisposition("preRhs", FieldDispositionKind.Child),
      FieldDisposition("mods", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration("type-tree", "tpt", ChildCardinality.ExactlyOne, "template-absent-tree"),
      ChildDeclaration("rhs", "preRhs", ChildCardinality.ExactlyOne, "template-absent-tree"),
      ChildDeclaration("self-modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
    ),
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ModifierListSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(transparentTemplate("type-tree", "rhs", "self-modifiers")),
    outputRoleId = None
  )

  private val templateSimpleSelfProduction = templateSelfProduction.copy(
    id = "template-self-simple",
    pattern = templateSelfProduction.pattern.copy(
      fields = templateSelfProduction.pattern.fields.updated(
        0,
        CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))
      ),
      occurrences = Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(InventoryKind.Node, "Template", Vector(CatalogPathSegment.NamedField("self"))),
          SourceClassification.SourceReachable
        )
      )
    ),
    children = Vector(
      ChildDeclaration(
        "declared-type",
        "tpt",
        ChildCardinality.ExactlyOne,
        "import-selector-bound-type",
        TypeAtomProductionIds - "import-selector-bound-type"
      ),
      ChildDeclaration("rhs", "preRhs", ChildCardinality.ExactlyOne, "template-absent-tree"),
      ChildDeclaration("self-modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
    ),
    terminals = Vector(
      TerminalDeclaration(
        "self-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    targetSurfaceId = SelfTypeSurface,
    accessors = SelfTypeAccessors,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "self",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.SelfType,
            SelfTypeSurface,
            SelfTypeAccessors
          )
        ),
        Map("declared-type" -> Some("self"), "rhs" -> None, "self-modifiers" -> None)
      )
    )
  )

  private val templateAbsentTreeProduction = Scala3PsiProduction(
    id = "template-absent-tree",
    grammarRoleId = GrammarRoleId.AbsentProduct,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Thicket",
      Vector(CompilerFieldPattern("trees", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node))),
      Vector(
        "DefDef"          -> "preRhs",
        "ValDef"          -> "tpt",
        "ValDef"          -> "preRhs",
        "TypeBoundsTree"  -> "lo",
        "TypeBoundsTree"  -> "hi",
        "TypeBoundsTree"  -> "alias",
        "Template"        -> "preBody",
        "CaseDef"         -> "guard",
        "Match"           -> "guard",
        "RefinedTypeTree" -> "tpt"
      ).flatMap { case (owner, field) =>
        owner match
          case "CaseDef" =>
            Vector(
              "MatchTypeTree",
              "Match"
            ).map: matchOwner =>
              CompilerProductionContextPattern(
                ContextPattern.ParentWithAncestor(
                  InventoryKind.Node,
                  owner,
                  Vector(CatalogPathSegment.NamedField(field)),
                  InventoryAncestor(
                    InventoryKind.Node,
                    matchOwner,
                    Vector(CatalogPathSegment.NamedField("cases"), CatalogPathSegment.RepeatedElement)
                  )
                ),
                SourceClassification.Absent
              )
          case _         =>
            Vector(
              CompilerProductionContextPattern(
                ContextPattern.Parent(
                  InventoryKind.Node,
                  owner,
                  Vector(CatalogPathSegment.NamedField(field)) ++
                    Option.when(owner == "Template")(CatalogPathSegment.RepeatedElement)
                ),
                SourceClassification.Absent
              )
            )
      }
        ++ OwnerTypeAnchors.map { anchor =>
          CompilerProductionContextPattern(
            ContextPattern.ParentUnderAnchorThrough(
              InventoryKind.Node,
              "MatchTypeTree",
              Vector(CatalogPathSegment.NamedField("bound")),
              CompoundTypeTraversedAncestors,
              anchor
            ),
            SourceClassification.Absent
          )
        }
        ++ Vector("lo", "hi", "alias").flatMap { field =>
          OwnerTypeAnchors.flatMap { anchor =>
            Vector(
              CompilerProductionContextPattern(
                ContextPattern.ParentWithAncestorPrefix(
                  InventoryKind.Node,
                  "TypeBoundsTree",
                  Vector(CatalogPathSegment.NamedField(field)),
                  Vector(
                    InventoryAncestor(
                      InventoryKind.Node,
                      "AppliedTypeTree",
                      Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
                    ),
                    anchor
                  )
                ),
                SourceClassification.Absent
              ),
              CompilerProductionContextPattern(
                ContextPattern.ParentWithAncestorPrefix(
                  InventoryKind.Node,
                  "TypeBoundsTree",
                  Vector(CatalogPathSegment.NamedField(field)),
                  Vector(
                    InventoryAncestor(
                      InventoryKind.Node,
                      "LambdaTypeTree",
                      Vector(CatalogPathSegment.NamedField("body"))
                    ),
                    anchor
                  )
                ),
                SourceClassification.Absent
              )
            )
          }
        }
        ++ Vector("lo", "hi", "alias").map { field =>
          CompilerProductionContextPattern(
            ContextPattern.ParentWithAncestorPrefix(
              InventoryKind.Node,
              "TypeBoundsTree",
              Vector(CatalogPathSegment.NamedField(field)),
              Vector(
                InventoryAncestor(InventoryKind.Node, "ContextBounds", Vector(CatalogPathSegment.NamedField("bounds")))
              )
            ),
            SourceClassification.Absent
          )
        }
    ),
    dispositions = Vector(FieldDisposition("trees", FieldDispositionKind.Synthetic)),
    children = Vector.empty,
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ModifierListSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(transparentTemplate()),
    outputRoleId = None
  )

  private val enumClassParameterProduction =
    val base = Scala3PsiDefinitionProductions.typedParameterProduction(
      "template-enum-class-parameter",
      16385L,
      classParameter = true
    )
    base.copy(
      pattern = base.pattern.copy(
        occurrences = Vector(
          CompilerProductionContextPattern(
            ContextPattern.ParentWithAncestorPrefix(
              InventoryKind.Node,
              "DefDef",
              Vector(
                CatalogPathSegment.NamedField("paramss"),
                CatalogPathSegment.RepeatedElement,
                CatalogPathSegment.RepeatedElement
              ),
              Vector(
                InventoryAncestor(InventoryKind.Node, "Template", Vector(CatalogPathSegment.NamedField("constr"))),
                InventoryAncestor(InventoryKind.Node, "TypeDef", Vector(CatalogPathSegment.NamedField("rhs"))),
                InventoryAncestor(
                  InventoryKind.Node,
                  "Template",
                  Vector(CatalogPathSegment.NamedField("preBody"), CatalogPathSegment.RepeatedElement)
                ),
                InventoryAncestor(InventoryKind.Node, "TypeDef", Vector(CatalogPathSegment.NamedField("rhs"))),
                InventoryAncestor(
                  InventoryKind.Node,
                  "PackageDef",
                  Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement)
                )
              )
            ),
            SourceClassification.SourceReachable
          )
        )
      )
    )

  private[psiproducer] val TemplateSegment: Vector[Scala3PsiProduction] = Vector(
    templateOwnerProduction(
      "template-class-definition",
      "TypeDef",
      "rhs",
      0L,
      GrammarRoleId.ClassDefinition,
      PsiOutputRoleId.ClassDefinition,
      ClassDefinitionSurface,
      constructorOwner = true
    ),
    templateOwnerProduction(
      "template-trait-definition",
      "TypeDef",
      "rhs",
      1026L,
      GrammarRoleId.TraitDefinition,
      PsiOutputRoleId.TraitDefinition,
      TraitDefinitionSurface,
      constructorOwner = false
    ),
    templateOwnerProduction(
      "template-object-definition",
      "ModuleDef",
      "impl",
      32771L,
      GrammarRoleId.ObjectDefinition,
      PsiOutputRoleId.ObjectDefinition,
      ObjectDefinitionSurface,
      constructorOwner = false
    ),
    templateOwnerProduction(
      "template-enum-definition",
      "TypeDef",
      "rhs",
      1099511627779L,
      GrammarRoleId.EnumDefinition,
      PsiOutputRoleId.EnumDefinition,
      EnumDefinitionSurface,
      constructorOwner = true
    ),
    templateOwnerProduction(
      "enum-singleton-case",
      "ModuleDef",
      "impl",
      1099511758851L,
      GrammarRoleId.EnumCase,
      PsiOutputRoleId.EnumSingletonCase,
      EnumSingletonCaseSurface,
      constructorOwner = false,
      enumCase = true
    ),
    templateOwnerProduction(
      "enum-class-case",
      "TypeDef",
      "rhs",
      1099511758851L,
      GrammarRoleId.EnumCase,
      PsiOutputRoleId.EnumClassCase,
      EnumClassCaseSurface,
      constructorOwner = false,
      enumCase = true,
      classCase = true
    ),
    templateTemplateProduction,
    templateConstructorSyntheticProduction,
    templateConstructorExplicitProduction,
    templateConstructorTypedParametersProduction,
    templateConstructorTypeParametersProduction,
    unboundedTypeParameterProduction("template-unbounded-type-parameter-invariant", 8455L),
    unboundedTypeParameterProduction("template-unbounded-type-parameter-covariant", 1057030L),
    unboundedTypeParameterProduction("template-unbounded-type-parameter-contravariant", 2105606L),
    unboundedTypeParameterProduction("function-unbounded-type-parameter", 259L),
    contextBoundedTypeParameterProduction("template-context-bounded-type-parameter-invariant", 8455L),
    contextBoundedTypeParameterProduction("template-context-bounded-type-parameter-covariant", 1057030L),
    contextBoundedTypeParameterProduction("template-context-bounded-type-parameter-contravariant", 2105606L),
    contextBoundedTypeParameterProduction("function-context-bounded-type-parameter", 259L),
    higherKindedTypeParameterProduction("template-higher-kinded-type-parameter-invariant", 8455L),
    higherKindedTypeParameterProduction("template-higher-kinded-type-parameter-covariant", 1057030L),
    higherKindedTypeParameterProduction("template-higher-kinded-type-parameter-contravariant", 2105606L),
    higherKindedTypeParameterProduction("higher-kinded-nested-type-parameter", 259L),
    higherKindedParameterLambdaProduction,
    higherKindedResultBoundsProduction,
    contextBoundBaseBoundsProduction,
    typeParameterContextBoundsProduction,
    typeParameterContextBoundProduction,
    typeParameterNamedContextBoundProduction,
    typeParameterSyntheticContextBoundProduction,
    unboundedTypeBoundsProduction,
    boundedTypeBoundsProduction,
    templateTypeTreeProduction,
    templateSelfProduction,
    templateSimpleSelfProduction,
    templateAbsentTreeProduction,
    Scala3PsiDefinitionProductions.inferredDefinitionTypeProduction,
    Scala3PsiDefinitionProductions.typedParameterProduction("definition-typed-parameter", 259L, classParameter = false),
    Scala3PsiDefinitionProductions.typeDefinitionTermParameter,
    Scala3PsiDefinitionProductions.typedParameterProduction("template-class-parameter", 24581L, classParameter = true),
    enumClassParameterProduction,
    Scala3PsiDefinitionProductions.typedParameterProduction(
      "template-context-class-parameter",
      536895493L,
      classParameter = true,
      contextual = true
    )
  )
