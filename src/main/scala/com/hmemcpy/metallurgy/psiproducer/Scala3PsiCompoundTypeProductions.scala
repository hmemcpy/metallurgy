package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiCompoundTypeProductions:
  private[psiproducer] val CompoundInfixSegment: Vector[Scala3PsiProduction] = Vector(
    Scala3PsiProduction(
      id = "ordinary-infix-type",
      grammarRoleId = GrammarRoleId.InfixType,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "InfixOp",
        Vector(
          CompilerFieldPattern("left", CatalogValuePattern.Node),
          CompilerFieldPattern("op", CatalogValuePattern.Node),
          CompilerFieldPattern("right", CatalogValuePattern.Node)
        ),
        typeAtomOccurrences ++ compoundTypeArgumentOccurrences
      ),
      dispositions = Vector(
        FieldDisposition("left", FieldDispositionKind.Child),
        FieldDisposition("op", FieldDispositionKind.Child),
        FieldDisposition("right", FieldDispositionKind.Child)
      ),
      children = Vector(
        compoundChild("left", "left", ChildCardinality.ExactlyOne),
        ChildDeclaration(
          "operator",
          "op",
          ChildCardinality.ExactlyOne,
          "infix-type-operator"
        ),
        compoundChild("right", "right", ChildCardinality.ExactlyOne)
      ),
      terminals = Vector(
        TerminalDeclaration(
          "infix-type-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = InfixTypeSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = InfixTypeAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(
        typeElementTemplate(
          PsiOutputRoleId.InfixType,
          InfixTypeSurface,
          InfixTypeAccessors,
          "left",
          "operator",
          "right"
        )
      )
    ),
    Scala3PsiProduction(
      id = "infix-type-operator",
      grammarRoleId = GrammarRoleId.StableReference,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Ident",
        Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
        (GivenSelectorBoundAnchor +: OwnerTypeAnchors).map: anchor =>
          CompilerProductionContextPattern(
            ContextPattern.ParentUnderAnchorThrough(
              InventoryKind.Node,
              "InfixOp",
              Vector(CatalogPathSegment.NamedField("op")),
              CompoundTypeTraversedAncestors,
              anchor
            ),
            SourceClassification.SourceReachable
          )
      ),
      dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
      children = Vector.empty,
      terminals = Vector(
        TerminalDeclaration(
          "operator-text",
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
    )
  )

  private lazy val matchTypeProduction: Scala3PsiProduction = Scala3PsiProduction(
    id = "ordinary-match-type",
    grammarRoleId = GrammarRoleId.MatchType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "MatchTypeTree",
      Vector(
        CompilerFieldPattern("bound", CatalogValuePattern.Node),
        CompilerFieldPattern("selector", CatalogValuePattern.Node),
        CompilerFieldPattern("cases", CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("CaseDef")))
      ),
      typeAtomOccurrences ++ compoundTypeArgumentOccurrences
    ),
    dispositions = Vector(
      FieldDisposition("bound", FieldDispositionKind.Child),
      FieldDisposition("selector", FieldDispositionKind.Child),
      FieldDisposition("cases", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "bound",
        "bound",
        ChildCardinality.ExactlyOne,
        "template-absent-tree",
        CompoundTypeProductionIds
      ),
      compoundChild("selector", "selector", ChildCardinality.ExactlyOne),
      ChildDeclaration("cases", "cases", ChildCardinality.Repeated(1, None), "match-type-case")
    ),
    terminals = Vector(
      TerminalDeclaration(
        "match-type-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "match-keyword",
        TerminalIntervalSelector.ChildGap("selector", "cases"),
        TerminalLeafTarget.Token(NativePsiElementBindings.MatchKeywordTokenSurface, Some("match")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "left-brace",
        TerminalIntervalSelector.ChildGap("selector", "cases"),
        TerminalLeafTarget.Token(NativePsiElementBindings.ContextBoundLeftBraceTokenSurface, Some("{")),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "right-brace",
        TerminalIntervalSelector.AfterChild("cases"),
        TerminalLeafTarget.Token(NativePsiElementBindings.ContextBoundRightBraceTokenSurface, Some("}")),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = MatchTypeSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = MatchTypeAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "match-type",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.MatchType,
            MatchTypeSurface,
            MatchTypeAccessors
          ),
          outputComposite(
            "cases",
            Some("match-type"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary
                .ChildStart("cases", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly),
              OutputBoundary.ChildEnd("cases", ChildOccurrenceSelector.Last, PositionProvenancePolicy.SourceDerivedOnly)
            ),
            PsiOutputRoleId.MatchTypeCases,
            MatchTypeCasesSurface,
            MatchTypeCasesAccessors
          )
        ),
        Map("bound" -> None, "selector" -> Some("match-type"), "cases" -> Some("cases"))
      )
    ),
    outputRoleId = None
  )

  private lazy val matchTypeCaseProduction: Scala3PsiProduction = Scala3PsiProduction(
    id = "match-type-case",
    grammarRoleId = GrammarRoleId.MatchTypeCase,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "CaseDef",
      Vector(
        CompilerFieldPattern("pat", CatalogValuePattern.Node),
        CompilerFieldPattern("guard", CatalogValuePattern.NodePrefix("Thicket")),
        CompilerFieldPattern("body", CatalogValuePattern.Node)
      ),
      OwnerTypeAnchors.map: anchor =>
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchor(
            InventoryKind.Node,
            "MatchTypeTree",
            Vector(CatalogPathSegment.NamedField("cases"), CatalogPathSegment.RepeatedElement),
            anchor
          ),
          SourceClassification.SourceReachable
        )
    ),
    dispositions = Vector(
      FieldDisposition("pat", FieldDispositionKind.Child),
      FieldDisposition("guard", FieldDispositionKind.Child),
      FieldDisposition("body", FieldDispositionKind.Child)
    ),
    children = Vector(
      compoundChild("pattern", "pat", ChildCardinality.ExactlyOne),
      ChildDeclaration("guard", "guard", ChildCardinality.ExactlyOne, "template-absent-tree"),
      compoundChild("result", "body", ChildCardinality.ExactlyOne)
    ),
    terminals = Vector(
      TerminalDeclaration(
        "case-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "case-keyword",
        TerminalIntervalSelector.BeforeChild("pattern"),
        TerminalLeafTarget.Token(NativePsiElementBindings.CaseKeywordTokenSurface, Some("case")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "case-arrow",
        TerminalIntervalSelector.ChildGap("pattern", "result"),
        TerminalLeafTarget.Token(NativePsiElementBindings.FunctionArrowTokenSurface, Some("=>")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "case-semicolon",
        TerminalIntervalSelector.AfterChild("result"),
        TerminalLeafTarget.Token(NativePsiElementBindings.SemicolonTokenSurface, Some(";")),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = MatchTypeCaseSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = MatchTypeCaseAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "case",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.MatchTypeCase,
            MatchTypeCaseSurface,
            MatchTypeCaseAccessors
          )
        ),
        Map("pattern" -> Some("case"), "guard" -> None, "result" -> Some("case"))
      )
    ),
    outputRoleId = None
  )

  private lazy val matchTypePatternReferenceProduction: Scala3PsiProduction = Scala3PsiProduction(
    id = "match-type-pattern-reference",
    grammarRoleId = GrammarRoleId.SimpleType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(
        CompilerFieldPattern(
          "name",
          CatalogValuePattern.AnyOf(Vector(CatalogValuePattern.NonLowercaseName, CatalogValuePattern.BacktickedName))
        )
      ),
      matchTypePatternOccurrences
    ),
    dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "pattern-text",
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

  private def matchTypePatternVariable(
      id: String,
      namePattern: CatalogValuePattern
  ): Scala3PsiProduction = Scala3PsiProduction(
    id = id,
    grammarRoleId = GrammarRoleId.MatchTypePatternVariable,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(CompilerFieldPattern("name", namePattern)),
      matchTypePatternOccurrences
    ),
    dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "variable-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = MatchTypeVariableSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = MatchTypeVariableAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRoleId = Some(PsiOutputRoleId.MatchTypeVariable)
  )

  private lazy val matchTypePatternVariableProduction: Scala3PsiProduction =
    matchTypePatternVariable("match-type-pattern-variable", CatalogValuePattern.LowercaseName)
  private lazy val matchTypePatternWildcardProduction: Scala3PsiProduction = matchTypePatternVariable(
    "match-type-pattern-wildcard",
    CatalogValuePattern.ClassifiedName(NeutralNameClass.Wildcard)
  )

  private lazy val refinementTypeProduction: Scala3PsiProduction =
    val memberProductionIds = Set(
      "refinement-function-declaration",
      "refinement-value-declaration",
      "refinement-variable-declaration",
      "definition-unbounded-type-alias"
    ) ++ RefinementTypeAliasProductionIds
    Scala3PsiProduction(
      id = "ordinary-refinement-type",
      grammarRoleId = GrammarRoleId.RefinementType,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "RefinedTypeTree",
        Vector(
          CompilerFieldPattern("tpt", CatalogValuePattern.Node),
          CompilerFieldPattern("refinements", CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.Node))
        ),
        typeAtomOccurrences ++ compoundTypeArgumentOccurrences
      ),
      dispositions = Vector(
        FieldDisposition("tpt", FieldDispositionKind.Child),
        FieldDisposition("refinements", FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration(
          "parent-type",
          "tpt",
          ChildCardinality.ExactlyOne,
          "template-absent-tree",
          CompoundTypeProductionIds
        ),
        ChildDeclaration(
          "members",
          "refinements",
          ChildCardinality.Repeated(1, None),
          memberProductionIds.toVector.sorted.head,
          memberProductionIds - memberProductionIds.toVector.sorted.head
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "refinement-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "left-brace",
          TerminalIntervalSelector.BeforeChild("members"),
          TerminalLeafTarget.Token(NativePsiElementBindings.ContextBoundLeftBraceTokenSurface, Some("{")),
          OccurrenceCardinality.Optional,
          PsiOutputRoleId.SourceTerminal,
          ownsStructuralEvidence = Some(false)
        ),
        TerminalDeclaration(
          "layout-colon",
          TerminalIntervalSelector.BeforeChild("members"),
          TerminalLeafTarget.Token(NativePsiElementBindings.TypeColonTokenSurface, Some(":")),
          OccurrenceCardinality.Optional,
          PsiOutputRoleId.SourceTerminal,
          ownsStructuralEvidence = Some(false)
        ),
        TerminalDeclaration(
          "member-semicolons",
          TerminalIntervalSelector.ChildSeparators("members"),
          TerminalLeafTarget.Token(NativePsiElementBindings.SemicolonTokenSurface, Some(";")),
          OccurrenceCardinality.Repeated(0, None),
          PsiOutputRoleId.SourceTerminal,
          ownsStructuralEvidence = Some(false)
        ),
        TerminalDeclaration(
          "right-brace",
          TerminalIntervalSelector.AfterChild("members"),
          TerminalLeafTarget.Token(NativePsiElementBindings.ContextBoundRightBraceTokenSurface, Some("}")),
          OccurrenceCardinality.Optional,
          PsiOutputRoleId.SourceTerminal,
          ownsStructuralEvidence = Some(false)
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = CompoundTypeSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = CompoundTypeAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputTemplate = Some(
        LocalOutputCompositeTemplate(
          Vector(
            outputComposite(
              "compound",
              None,
              OutputRangeDeclaration.CompilerPosition,
              PsiOutputRoleId.CompoundType,
              CompoundTypeSurface,
              CompoundTypeAccessors
            ),
            outputComposite(
              "refinement",
              Some("compound"),
              OutputRangeDeclaration.BoundaryDerived(
                OutputBoundary.PreviousSignificantChildTokenStart(
                  "members",
                  ChildOccurrenceSelector.First,
                  PositionProvenancePolicy.SourceDerivedOnly
                ),
                OutputBoundary.ProductionEnd()
              ),
              PsiOutputRoleId.Refinement,
              RefinementSurface,
              RefinementAccessors
            )
          ),
          Map("parent-type" -> Some("compound"), "members" -> Some("refinement"))
        )
      ),
      outputRoleId = None
    )

  private lazy val annotatedTypeProduction: Scala3PsiProduction = Scala3PsiProduction(
    id = "ordinary-annotated-type",
    grammarRoleId = GrammarRoleId.AnnotatedType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Annotated",
      Vector(
        CompilerFieldPattern("arg", CatalogValuePattern.Node),
        CompilerFieldPattern("annot", CatalogValuePattern.NodePrefix("Apply"))
      ),
      (typeAtomOccurrences ++ compoundTypeArgumentOccurrences).map(
        _.copy(scannerEvidence = ScannerEvidencePattern(required = Set(ParserScannerTokenKind.Other)))
      ),
      Vector(DirectNodeFieldEvidence("annot", SourceClassification.Synthetic, hasSourceWidth = Some(true)))
    ),
    dispositions = Vector(
      FieldDisposition("arg", FieldDispositionKind.Child),
      FieldDisposition("annot", FieldDispositionKind.Child)
    ),
    children = Vector(
      compoundChild("annotated-type", "arg", ChildCardinality.ExactlyOne),
      ChildDeclaration(
        "annotation",
        "annot",
        ChildCardinality.ExactlyOne,
        "annotation-apply-simple",
        Set("annotation-apply-arguments")
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "annotated-type-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = AnnotatedTypeSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = AnnotatedTypeAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "annotated",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.AnnotatedType,
            AnnotatedTypeSurface,
            AnnotatedTypeAccessors
          ),
          outputComposite(
            "annotations",
            Some("annotated"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.ChildStart(
                "annotation",
                ChildOccurrenceSelector.First,
                PositionProvenancePolicy.PositionedIncludingSynthetic
              ),
              OutputBoundary.ChildEnd(
                "annotation",
                ChildOccurrenceSelector.Last,
                PositionProvenancePolicy.PositionedIncludingSynthetic
              )
            ),
            PsiOutputRoleId.Annotations,
            AnnotationsSurface,
            AnnotationsAccessors
          )
        ),
        Map("annotated-type" -> Some("annotated"), "annotation" -> Some("annotations"))
      )
    ),
    outputRoleId = None
  )

  private[psiproducer] val CompoundTypeSegment: Vector[Scala3PsiProduction] = Vector(
    matchTypeProduction,
    matchTypeCaseProduction,
    matchTypePatternReferenceProduction,
    matchTypePatternVariableProduction,
    matchTypePatternWildcardProduction,
    refinementTypeProduction,
    annotatedTypeProduction
  )
