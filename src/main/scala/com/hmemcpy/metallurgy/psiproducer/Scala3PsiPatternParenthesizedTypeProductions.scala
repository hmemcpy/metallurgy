package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiPatternParenthesizedTypeProductions:
  private[psiproducer] val MatchParenthesizedTypeProductionId = "match-pattern-parenthesized-type"

  // Wrapper recursion descends only through type positions below the typed-pattern entry; the
  // Typed.tpt anchor is unreachable from term-pattern nesting, so term-space Parens-in-Parens
  // keeps the parenthesized-pattern owner without a retention declaration.
  private[psiproducer] val matchParenthesizedTypeEdges: Vector[InventoryAncestor] =
    Scala3PsiPatternWildcardTypeProductions.matchWildcardTypeEdges

  private[psiproducer] val matchParenthesizedTypeAnchor: InventoryAncestor =
    InventoryAncestor(InventoryKind.Node, "Typed", Vector(CatalogPathSegment.NamedField("tpt")))

  private def parenDelimiterEvidence(occurrence: CompilerProductionContextPattern): CompilerProductionContextPattern =
    occurrence.copy(scannerEvidence =
      ScannerEvidencePattern(
        required = Set(ParserScannerTokenKind.LeftParenthesis, ParserScannerTokenKind.RightParenthesis)
      )
    )

  private def directOccurrence: CompilerProductionContextPattern =
    CompilerProductionContextPattern(
      ContextPattern.ParentUnderAnchorThroughWithEvidence(
        InventoryKind.Node,
        "Typed",
        Vector(CatalogPathSegment.NamedField("tpt")),
        Scala3PsiPatternAppliedTypeProductions.nestedAppliedTypeEdges,
        Scala3PsiMatchExpressionProductions.MatchCasesAncestor,
        Scala3PsiMatchExpressionProductions.SourceSelectorAnchorEvidence
      ),
      SourceClassification.SourceReachable
    )

  private def nestedOccurrence(owner: String, path: Vector[CatalogPathSegment]): CompilerProductionContextPattern =
    CompilerProductionContextPattern(
      ContextPattern.ParentUnderAnchorThrough(
        InventoryKind.Node,
        owner,
        path,
        matchParenthesizedTypeEdges,
        matchParenthesizedTypeAnchor
      ),
      SourceClassification.SourceReachable
    )

  private val matchParenthesizedTypeOccurrences: Vector[CompilerProductionContextPattern] =
    Vector(
      directOccurrence,
      nestedOccurrence("Parens", Vector(CatalogPathSegment.NamedField("t"))),
      nestedOccurrence("AppliedTypeTree", Vector(CatalogPathSegment.NamedField("tpt"))),
      nestedOccurrence(
        "AppliedTypeTree",
        Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
      ),
      nestedOccurrence("Tuple", Vector(CatalogPathSegment.NamedField("trees"), CatalogPathSegment.RepeatedElement)),
      nestedOccurrence("TypeBoundsTree", Vector(CatalogPathSegment.NamedField("lo"))),
      nestedOccurrence("TypeBoundsTree", Vector(CatalogPathSegment.NamedField("hi")))
    ).map(parenDelimiterEvidence)

  private val matchParenthesizedType = Scala3PsiProduction(
    id = MatchParenthesizedTypeProductionId,
    grammarRoleId = GrammarRoleId.ParenthesizedType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Parens",
      Vector(CompilerFieldPattern("t", CatalogValuePattern.Node)),
      matchParenthesizedTypeOccurrences
    ),
    dispositions = Vector(FieldDisposition("t", FieldDispositionKind.Child)),
    children = Vector(
      ChildDeclaration(
        "inner",
        "t",
        ChildCardinality.ExactlyOne,
        Scala3PsiMatchExpressionProductions.TypeIdentProductionId,
        Set(
          Scala3PsiMatchExpressionProductions.TypeIdentProductionId,
          Scala3PsiPatternAppliedTypeProductions.AppliedTypeProductionId,
          Scala3PsiPatternTupleTypeProductions.MatchTupleTypeProductionId,
          Scala3PsiPatternWildcardTypeProductions.MatchWildcardTypeProductionId,
          Scala3PsiPatternStableSelectProductions.MatchDottedTypeProductionId,
          Scala3PsiPatternStableSelectProductions.MatchHashProjectionProductionId,
          MatchParenthesizedTypeProductionId,
          "match-pattern-singleton-ident",
          "match-pattern-singleton-select"
        )
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "left-parenthesis",
        TerminalIntervalSelector
          .CompilerScannerToken(ParserScannerTokenKind.LeftParenthesis, ScannerTokenOccurrence.First),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeLeftParenthesisTokenSurface, Some("(")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "inner-prefix-evidence",
        TerminalIntervalSelector.BeforeChild("inner"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "inner-suffix-evidence",
        TerminalIntervalSelector.AfterChild("inner"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "right-parenthesis",
        TerminalIntervalSelector
          .CompilerScannerToken(ParserScannerTokenKind.RightParenthesis, ScannerTokenOccurrence.Last),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeRightParenthesisTokenSurface, Some(")")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ParenthesizedTypeSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = ParenthesizedTypeAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRoleId = None,
    outputTemplate = Some(
      typeElementTemplate(
        PsiOutputRoleId.ParenthesizedType,
        ParenthesizedTypeSurface,
        ParenthesizedTypeAccessors,
        "inner"
      )
    ),
    nestedChildRequirements = Vector(
      RequiredChildRootOutcome(
        "inner",
        ChildRootOutcome.One(
          ChildOutcomeExpectation.OutputRoles(
            Set(
              PsiOutputRoleId.SimpleType,
              PsiOutputRoleId.ParameterizedType,
              PsiOutputRoleId.TupleType,
              PsiOutputRoleId.WildcardType,
              PsiOutputRoleId.TypeProjection,
              PsiOutputRoleId.ParenthesizedType,
              PsiOutputRoleId.SingletonType
            )
          )
        )
      )
    )
  )

  private[psiproducer] val PatternParenthesizedTypeSuffixSegment: Vector[Scala3PsiProduction] =
    Vector(matchParenthesizedType)
