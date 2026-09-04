package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiPatternLiteralTypeProductions:
  private[psiproducer] val MatchLiteralTypeProductionId = "match-pattern-literal-type"

  private val LiteralValueChildProductionIds = Vector(
    "type-atom-literal-value-integer",
    "type-atom-literal-value-long",
    "type-atom-literal-value-float",
    "type-atom-literal-value-double",
    "type-atom-literal-value-char",
    "type-atom-literal-value-string",
    "type-atom-literal-value-boolean"
  )

  private[psiproducer] val matchLiteralTypeAnchor: InventoryAncestor =
    InventoryAncestor(InventoryKind.Node, "Typed", Vector(CatalogPathSegment.NamedField("tpt")))

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
      SourceClassification.Synthetic
    )

  private def nestedOccurrence(owner: String, path: Vector[CatalogPathSegment]): CompilerProductionContextPattern =
    CompilerProductionContextPattern(
      ContextPattern.ParentUnderAnchorThrough(
        InventoryKind.Node,
        owner,
        path,
        Scala3PsiPatternWildcardTypeProductions.matchWildcardTypeEdges,
        matchLiteralTypeAnchor
      ),
      SourceClassification.Synthetic
    )

  private val matchLiteralTypeOccurrences: Vector[CompilerProductionContextPattern] =
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
      nestedOccurrence("TypeBoundsTree", Vector(CatalogPathSegment.NamedField("hi"))),
      nestedOccurrence("InfixOp", Vector(CatalogPathSegment.NamedField("left"))),
      nestedOccurrence("InfixOp", Vector(CatalogPathSegment.NamedField("right")))
    )

  private val matchLiteralType = Scala3PsiProduction(
    id = MatchLiteralTypeProductionId,
    grammarRoleId = GrammarRoleId.LiteralType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "SingletonTypeTree",
      Vector(CompilerFieldPattern("ref", CatalogValuePattern.NodePrefix("Literal"))),
      matchLiteralTypeOccurrences.map(
        _.copy(scannerEvidence =
          ScannerEvidencePattern(
            required = Set(ParserScannerTokenKind.Literal),
            forbidden = Set(ParserScannerTokenKind.Hash, ParserScannerTokenKind.TypeKeyword)
          )
        )
      )
    ),
    dispositions = Vector(FieldDisposition("ref", FieldDispositionKind.Child)),
    children = Vector(
      ChildDeclaration(
        "literal",
        "ref",
        ChildCardinality.ExactlyOne,
        LiteralValueChildProductionIds.head,
        LiteralValueChildProductionIds.tail.toSet
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "literal-type-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(true)
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = LiteralTypeSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = LiteralTypeAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRoleId = None,
    outputTemplate = Some(
      typeElementTemplateWithRange(
        PsiOutputRoleId.LiteralType,
        LiteralTypeSurface,
        LiteralTypeAccessors,
        OutputRangeDeclaration.CompilerPositionWithPolicy(PositionProvenancePolicy.PositionedIncludingSynthetic),
        "literal"
      )
    )
  )

  private[psiproducer] val PatternLiteralTypeSuffixSegment: Vector[Scala3PsiProduction] =
    Vector(matchLiteralType)
