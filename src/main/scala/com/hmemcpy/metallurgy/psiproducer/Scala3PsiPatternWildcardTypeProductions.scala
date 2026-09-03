package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiPatternWildcardTypeProductions:
  private[psiproducer] val MatchWildcardTypeProductionId = "match-pattern-wildcard-type"

  // TypeBoundsTree bounds descend to the match anchor through applied-type arguments and
  // the typed-pattern entry only; bound edges keep nested applied-type wildcards reachable.
  private[psiproducer] val matchWildcardTypeEdges: Vector[InventoryAncestor] =
    Scala3PsiPatternAppliedTypeProductions.nestedAppliedTypeEdges ++ Vector(
      InventoryAncestor(
        InventoryKind.Node,
        "TypeBoundsTree",
        Vector(CatalogPathSegment.NamedField("lo"))
      ),
      InventoryAncestor(
        InventoryKind.Node,
        "TypeBoundsTree",
        Vector(CatalogPathSegment.NamedField("hi"))
      )
    )

  private def matchWildcardTypeOccurrence: CompilerProductionContextPattern =
    CompilerProductionContextPattern(
      ContextPattern.ParentUnderAnchorThroughWithEvidence(
        InventoryKind.Node,
        "AppliedTypeTree",
        Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
        matchWildcardTypeEdges,
        Scala3PsiMatchExpressionProductions.MatchCasesAncestor,
        Scala3PsiMatchExpressionProductions.SourceSelectorAnchorEvidence
      ),
      SourceClassification.SourceReachable
    )

  private[psiproducer] def matchWildcardBoundOccurrence(field: String): CompilerProductionContextPattern =
    CompilerProductionContextPattern(
      ContextPattern.ParentUnderAnchorThrough(
        InventoryKind.Node,
        "TypeBoundsTree",
        Vector(CatalogPathSegment.NamedField(field)),
        matchWildcardTypeEdges,
        Scala3PsiMatchExpressionProductions.MatchCasesAncestor
      ),
      SourceClassification.SourceReachable
    )

  private val matchWildcardType = Scala3PsiProduction(
    id = MatchWildcardTypeProductionId,
    grammarRoleId = GrammarRoleId.WildcardType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "TypeBoundsTree",
      Vector(
        CompilerFieldPattern("lo", CatalogValuePattern.Node),
        CompilerFieldPattern("hi", CatalogValuePattern.Node),
        CompilerFieldPattern("alias", CatalogValuePattern.Node)
      ),
      Vector(matchWildcardTypeOccurrence)
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
        "template-absent-tree",
        Set(
          Scala3PsiMatchExpressionProductions.TypeIdentProductionId,
          Scala3PsiPatternAppliedTypeProductions.AppliedTypeProductionId,
          Scala3PsiPatternTupleTypeProductions.MatchTupleTypeProductionId,
          Scala3PsiPatternStableSelectProductions.MatchDottedTypeProductionId,
          Scala3PsiPatternStableSelectProductions.MatchHashProjectionProductionId,
          "match-pattern-parenthesized-type"
        )
      ),
      ChildDeclaration(
        "upper-bound",
        "hi",
        ChildCardinality.ExactlyOne,
        "template-absent-tree",
        Set(
          Scala3PsiMatchExpressionProductions.TypeIdentProductionId,
          Scala3PsiPatternAppliedTypeProductions.AppliedTypeProductionId,
          Scala3PsiPatternTupleTypeProductions.MatchTupleTypeProductionId,
          Scala3PsiPatternStableSelectProductions.MatchDottedTypeProductionId,
          Scala3PsiPatternStableSelectProductions.MatchHashProjectionProductionId,
          "match-pattern-parenthesized-type"
        )
      ),
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
        TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.Identifier, ScannerTokenOccurrence.First),
        TerminalLeafTarget.Token(NativePsiElementBindings.WildcardQuestionTokenSurface, Some("?")),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "under-marker",
        TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.Other, ScannerTokenOccurrence.First),
        TerminalLeafTarget.Token(NativePsiElementBindings.ImportLegacyWildcardTokenSurface, Some("_")),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "lower-bound-token",
        TerminalIntervalSelector.BeforeChild("lower-bound"),
        TerminalLeafTarget.Token(NativePsiElementBindings.LowerTypeBoundTokenSurface, Some(">:")),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "upper-bound-token",
        TerminalIntervalSelector.BeforeChild("upper-bound"),
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

  private[psiproducer] val PatternWildcardTypeSuffixSegment: Vector[Scala3PsiProduction] = Vector(matchWildcardType)
