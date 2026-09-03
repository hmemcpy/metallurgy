package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiPatternSingletonTypeProductions:
  private[psiproducer] val MatchSingletonIdentProductionId  = "match-pattern-singleton-ident"
  private[psiproducer] val MatchSingletonSelectProductionId = "match-pattern-singleton-select"

  // Wrapper recursion descends only through type positions below the typed-pattern entry; the
  // Typed.tpt anchor is unreachable from term-pattern nesting, and the reference child stays inside
  // the parser-owned dotted-reference machinery, so ordinary stable types keep their owners without
  // a retention declaration.
  private[psiproducer] val matchSingletonTypeAnchor: InventoryAncestor =
    InventoryAncestor(InventoryKind.Node, "Typed", Vector(CatalogPathSegment.NamedField("tpt")))

  private def singletonEvidence(occurrence: CompilerProductionContextPattern): CompilerProductionContextPattern =
    occurrence.copy(scannerEvidence =
      ScannerEvidencePattern(
        required = Set(ParserScannerTokenKind.Dot, ParserScannerTokenKind.TypeKeyword)
      )
    )

  private def selectSingletonEvidence(
      occurrence: CompilerProductionContextPattern
  ): CompilerProductionContextPattern =
    occurrence.copy(scannerEvidence =
      ScannerEvidencePattern(
        required = Set(ParserScannerTokenKind.Dot, ParserScannerTokenKind.TypeKeyword),
        forbidden = Set(ParserScannerTokenKind.Hash)
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
        Scala3PsiPatternWildcardTypeProductions.matchWildcardTypeEdges,
        matchSingletonTypeAnchor
      ),
      SourceClassification.SourceReachable
    )

  private val matchSingletonTypeOccurrences: Vector[CompilerProductionContextPattern] =
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
    )

  private def matchSingletonType(
      id: String,
      refPrefix: String,
      childProductionId: String,
      scanner: CompilerProductionContextPattern => CompilerProductionContextPattern
  ): Scala3PsiProduction = Scala3PsiProduction(
    id = id,
    grammarRoleId = GrammarRoleId.SingletonType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "SingletonTypeTree",
      Vector(CompilerFieldPattern("ref", CatalogValuePattern.NodePrefix(refPrefix))),
      matchSingletonTypeOccurrences.map(scanner)
    ),
    dispositions = Vector(FieldDisposition("ref", FieldDispositionKind.Child)),
    children = Vector(
      ChildDeclaration(
        "reference",
        "ref",
        ChildCardinality.ExactlyOne,
        childProductionId
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "singleton-dot",
        TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.Dot, ScannerTokenOccurrence.Last),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypePathDotTokenSurface, Some(".")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "singleton-type",
        TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.TypeKeyword),
        TerminalLeafTarget.Token(NativePsiElementBindings.SingletonTypeKeywordTokenSurface, Some("type")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "reference-prefix-evidence",
        TerminalIntervalSelector.BeforeChild("reference"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "reference-suffix-evidence",
        TerminalIntervalSelector.AfterChild("reference"),
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
      typeElementTemplate(PsiOutputRoleId.SingletonType, SimpleTypeSurface, SimpleTypeAccessors, "reference")
    )
  )

  private val matchSingletonIdent = matchSingletonType(
    MatchSingletonIdentProductionId,
    "Ident",
    "match-pattern-dotted-reference-ident",
    singletonEvidence
  )

  private val matchSingletonSelect = matchSingletonType(
    MatchSingletonSelectProductionId,
    "Select",
    Scala3PsiPatternStableSelectProductions.MatchDottedReferenceProductionId,
    selectSingletonEvidence
  )

  private[psiproducer] val PatternSingletonTypeSuffixSegment: Vector[Scala3PsiProduction] =
    Vector(matchSingletonIdent, matchSingletonSelect)
