package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiPatternTupleTypeProductions:
  private[psiproducer] val MatchTupleTypeProductionId = "match-pattern-tuple-type"

  private[psiproducer] val matchTupleTypeEdges: Vector[InventoryAncestor] =
    Scala3PsiPatternAppliedTypeProductions.nestedAppliedTypeEdges

  // Type-tuple descendants descend from the typed-pattern entry through tuple and applied-type
  // edges only; pattern tuples reach the case without Typed.tpt, so the anchor excludes them.
  private[psiproducer] val matchTupleNestedEdges: Vector[InventoryAncestor] = Vector(
    InventoryAncestor(
      InventoryKind.Node,
      "Tuple",
      Vector(CatalogPathSegment.NamedField("trees"), CatalogPathSegment.RepeatedElement)
    ),
    InventoryAncestor(
      InventoryKind.Node,
      "AppliedTypeTree",
      Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
    )
  )

  private[psiproducer] val matchTupleTypeAnchor: InventoryAncestor =
    InventoryAncestor(InventoryKind.Node, "Typed", Vector(CatalogPathSegment.NamedField("tpt")))

  private def matchTupleTypeOccurrence(
      owner: String,
      path: Vector[CatalogPathSegment]
  ): CompilerProductionContextPattern =
    CompilerProductionContextPattern(
      ContextPattern.ParentUnderAnchorThroughWithEvidence(
        InventoryKind.Node,
        owner,
        path,
        matchTupleTypeEdges,
        Scala3PsiMatchExpressionProductions.MatchCasesAncestor,
        Scala3PsiMatchExpressionProductions.SourceSelectorAnchorEvidence
      ),
      SourceClassification.SourceReachable
    )

  private def matchTupleNestedOccurrence(
      owner: String,
      path: Vector[CatalogPathSegment]
  ): CompilerProductionContextPattern =
    CompilerProductionContextPattern(
      ContextPattern
        .ParentUnderAnchorThrough(InventoryKind.Node, owner, path, matchTupleNestedEdges, matchTupleTypeAnchor),
      SourceClassification.SourceReachable
    )

  private val matchTupleTypeOccurrences: Vector[CompilerProductionContextPattern] = Vector(
    matchTupleTypeOccurrence("Typed", Vector(CatalogPathSegment.NamedField("tpt"))),
    matchTupleNestedOccurrence(
      "Tuple",
      Vector(CatalogPathSegment.NamedField("trees"), CatalogPathSegment.RepeatedElement)
    ),
    matchTupleNestedOccurrence(
      "AppliedTypeTree",
      Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
    ),
    Scala3PsiPatternWildcardTypeProductions.matchWildcardBoundOccurrence("lo"),
    Scala3PsiPatternWildcardTypeProductions.matchWildcardBoundOccurrence("hi"),
    CompilerProductionContextPattern(
      ContextPattern.ParentUnderAnchorThrough(
        InventoryKind.Node,
        "Tuple",
        Vector(CatalogPathSegment.NamedField("trees"), CatalogPathSegment.RepeatedElement),
        Scala3PsiPatternWildcardTypeProductions.matchWildcardTypeEdges,
        Scala3PsiMatchExpressionProductions.MatchCasesAncestor
      ),
      SourceClassification.SourceReachable
    ),
    CompilerProductionContextPattern(
      ContextPattern.ParentUnderAnchorThrough(
        InventoryKind.Node,
        "AppliedTypeTree",
        Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
        Scala3PsiPatternWildcardTypeProductions.matchWildcardTypeEdges,
        Scala3PsiMatchExpressionProductions.MatchCasesAncestor
      ),
      SourceClassification.SourceReachable
    )
  )

  private[psiproducer] def matchTupleComponentOccurrence: CompilerProductionContextPattern =
    matchTupleNestedOccurrence(
      "Tuple",
      Vector(CatalogPathSegment.NamedField("trees"), CatalogPathSegment.RepeatedElement)
    )

  private val matchTupleType = Scala3PsiProduction(
    id = MatchTupleTypeProductionId,
    grammarRoleId = GrammarRoleId.TupleType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Tuple",
      Vector(
        CompilerFieldPattern("trees", CatalogValuePattern.Repeated(CatalogValuePattern.NodeExceptPrefix("NamedArg")))
      ),
      matchTupleTypeOccurrences
    ),
    dispositions = Vector(FieldDisposition("trees", FieldDispositionKind.Child)),
    children = Vector(
      ChildDeclaration(
        "components",
        "trees",
        ChildCardinality.Repeated(2, None),
        Scala3PsiMatchExpressionProductions.TypeIdentProductionId,
        Set(
          Scala3PsiMatchExpressionProductions.TypeIdentProductionId,
          Scala3PsiPatternAppliedTypeProductions.AppliedTypeProductionId,
          MatchTupleTypeProductionId
        )
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "tuple-left-parenthesis",
        TerminalIntervalSelector.CompilerScannerToken(
          ParserScannerTokenKind.LeftParenthesis,
          ScannerTokenOccurrence.First
        ),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeLeftParenthesisTokenSurface, Some("(")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "tuple-prefix-evidence",
        TerminalIntervalSelector.BeforeChild("components"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "tuple-commas",
        TerminalIntervalSelector.ChildSeparators("components"),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeCommaTokenSurface, Some(",")),
        OccurrenceCardinality.Repeated(1, None),
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "tuple-separator-evidence",
        TerminalIntervalSelector.ChildSeparators("components"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.Repeated(1, None),
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "tuple-right-parenthesis",
        TerminalIntervalSelector.CompilerScannerToken(
          ParserScannerTokenKind.RightParenthesis,
          ScannerTokenOccurrence.Last
        ),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeRightParenthesisTokenSurface, Some(")")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "tuple-suffix-evidence",
        TerminalIntervalSelector.AfterChild("components"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = TupleTypeSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = TupleTypeAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "tuple",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.TupleType,
            TupleTypeSurface,
            TupleTypeAccessors
          ),
          outputComposite(
            "types",
            Some("tuple"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary
                .ChildStart("components", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly),
              OutputBoundary
                .ChildEnd("components", ChildOccurrenceSelector.Last, PositionProvenancePolicy.SourceDerivedOnly)
            ),
            PsiOutputRoleId.TupleTypes,
            TupleTypesSurface,
            TupleTypesAccessors
          )
        ),
        Map("components" -> Some("types"))
      )
    ),
    outputRoleId = None,
    nestedChildRequirements = Vector(
      RequiredChildRootOutcome(
        "components",
        ChildRootOutcome.All(
          ChildOutcomeExpectation.OutputRoles(
            Set(PsiOutputRoleId.SimpleType, PsiOutputRoleId.ParameterizedType, PsiOutputRoleId.TupleType)
          )
        )
      )
    )
  )

  private[psiproducer] val PatternTupleTypeSuffixSegment: Vector[Scala3PsiProduction] = Vector(matchTupleType)
