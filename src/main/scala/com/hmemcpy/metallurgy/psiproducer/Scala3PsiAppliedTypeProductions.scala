package com.hmemcpy.metallurgy.psiproducer

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiAppliedTypeProductions:
  private def appliedTypeRootOccurrences: Vector[CompilerProductionContextPattern] =
    val direct = OwnerTypeAnchors.flatMap: anchor =>
      Vector(
        ContextPattern.Parent(anchor.ownerKind, anchor.ownerPrefix, anchor.path),
        ContextPattern.ParentUnderAnchor(
          InventoryKind.Node,
          "LambdaTypeTree",
          Vector(CatalogPathSegment.NamedField("body")),
          anchor
        )
      ).map(CompilerProductionContextPattern(_, SourceClassification.SourceReachable))
    direct ++ boundTypeOccurrences ++ contextBoundTypeOccurrences

  private def appliedTypeChildOccurrences(field: String): Vector[CompilerProductionContextPattern] =
    OwnerTypeAnchors.map: anchor =>
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchorExceptAncestor(
          InventoryKind.Node,
          "AppliedTypeTree",
          Vector(CatalogPathSegment.NamedField(field)) ++
            Option.when(field == "args")(CatalogPathSegment.RepeatedElement),
          anchor,
          InventoryAncestor(
            InventoryKind.Node,
            "CaseDef",
            Vector(CatalogPathSegment.NamedField("pat"))
          )
        ),
        SourceClassification.SourceReachable
      )

  private[psiproducer] def appliedTypeProduction(
      id: String,
      occurrences: Vector[CompilerProductionContextPattern],
      additionalRoles: Set[GrammarRoleId]
  ): Scala3PsiProduction =
    Scala3PsiProduction(
      id = id,
      grammarRoleId = GrammarRoleId.AppliedType,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "AppliedTypeTree",
        Vector(
          CompilerFieldPattern("tpt", CatalogValuePattern.Node),
          CompilerFieldPattern("args", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
        ),
        occurrences
      ),
      dispositions = Vector(
        FieldDisposition("tpt", FieldDispositionKind.Child),
        FieldDisposition("args", FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration(
          "constructor",
          "tpt",
          ChildCardinality.ExactlyOne,
          "import-selector-bound-type",
          (TypeAtomProductionIds ++ Set(
            "type-argument-applied",
            "match-type-pattern-reference",
            "match-type-pattern-variable",
            "match-type-pattern-wildcard"
          )) - "import-selector-bound-type"
        ),
        ChildDeclaration(
          "arguments",
          "args",
          ChildCardinality.Repeated(1, None),
          "type-argument-ident",
          Set(
            "type-argument-applied",
            "ordinary-wildcard-type",
            "explicit-type-lambda",
            "ordinary-tuple-type",
            "named-tuple-type",
            "ordinary-function-type",
            "pure-nullary-function-type",
            "pure-function-type",
            "dependent-function-type",
            "polymorphic-function-type",
            "ordinary-infix-type",
            "ordinary-match-type",
            "ordinary-refinement-type",
            "ordinary-annotated-type",
            "match-type-pattern-reference",
            "match-type-pattern-variable",
            "match-type-pattern-wildcard"
          )
        )
      ),
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
      targetSurfaceId = ParameterizedTypeSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = ParameterizedTypeAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(appliedTypeOutputTemplate),
      additionalGrammarRoleIds = additionalRoles + GrammarRoleId.TypeArgumentList
    )

  private[psiproducer] val appliedTypeOutputTemplate = LocalOutputCompositeTemplate(
    Vector(
      outputComposite(
        "parameterized",
        None,
        OutputRangeDeclaration.CompilerPosition,
        PsiOutputRoleId.ParameterizedType,
        ParameterizedTypeSurface,
        ParameterizedTypeAccessors
      ),
      outputComposite(
        "arguments",
        Some("parameterized"),
        OutputRangeDeclaration.BoundaryDerived(
          OutputBoundary
            .ChildEnd("constructor", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly),
          OutputBoundary.ProductionEnd()
        ),
        PsiOutputRoleId.TypeArguments,
        TypeArgumentsSurface,
        TypeArgumentsAccessors
      )
    ),
    Map("constructor" -> Some("parameterized"), "arguments" -> Some("arguments"))
  )

  private val positionalTypeArgumentProduction = Scala3PsiProduction(
    id = "type-argument-ident",
    grammarRoleId = GrammarRoleId.PositionalTypeArgument,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
      appliedTypeChildOccurrences("args")
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
    ),
    outputRoleId = None
  )

  private[psiproducer] def positionalTypeArgument: Scala3PsiProduction = positionalTypeArgumentProduction

  private val ordinaryWildcardTypeProduction = Scala3PsiProduction(
    id = "ordinary-wildcard-type",
    grammarRoleId = GrammarRoleId.WildcardType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "TypeBoundsTree",
      Vector(
        CompilerFieldPattern("lo", CatalogValuePattern.Node),
        CompilerFieldPattern("hi", CatalogValuePattern.Node),
        CompilerFieldPattern("alias", CatalogValuePattern.Node)
      ),
      OwnerTypeAnchors.map: anchor =>
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchor(
            InventoryKind.Node,
            "AppliedTypeTree",
            Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
            anchor
          ),
          SourceClassification.SourceReachable
        )
    ),
    dispositions = Vector(
      FieldDisposition("lo", FieldDispositionKind.Child),
      FieldDisposition("hi", FieldDispositionKind.Child),
      FieldDisposition("alias", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration("lower-bound", "lo", ChildCardinality.ExactlyOne, "template-absent-tree", TypeAtomProductionIds),
      ChildDeclaration("upper-bound", "hi", ChildCardinality.ExactlyOne, "template-absent-tree", TypeAtomProductionIds),
      ChildDeclaration("alias", "alias", ChildCardinality.ExactlyOne, "template-absent-tree")
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
    outputTemplate = Some(
      typeElementTemplate(
        PsiOutputRoleId.WildcardType,
        WildcardTypeSurface,
        WildcardTypeAccessors,
        "lower-bound",
        "upper-bound",
        "alias"
      )
    ),
    outputRoleId = None
  )

  private[psiproducer] val AppliedTypeSegment: Vector[Scala3PsiProduction] = Vector(
    appliedTypeProduction(
      "ordinary-applied-type",
      appliedTypeRootOccurrences ++ compoundTypeChildOccurrences,
      Set.empty
    ),
    appliedTypeProduction(
      "type-argument-applied",
      appliedTypeChildOccurrences("args"),
      Set(GrammarRoleId.PositionalTypeArgument)
    ),
    positionalTypeArgumentProduction,
    ordinaryWildcardTypeProduction
  )
