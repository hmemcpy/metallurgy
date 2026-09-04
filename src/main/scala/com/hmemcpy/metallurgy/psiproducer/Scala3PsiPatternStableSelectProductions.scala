package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiPatternStableSelectProductions:
  private[psiproducer] val MatchDottedTypeProductionId      = "match-pattern-dotted-type"
  private[psiproducer] val MatchDottedReferenceProductionId = "match-pattern-dotted-reference"
  private[psiproducer] val MatchHashProjectionProductionId  = "match-pattern-hash-projection"

  // Both spellings share the Select node shape; only scanner evidence (Dot vs Hash)
  // distinguishes the dotted root from the projection root.
  private[psiproducer] val matchStableSelectEdges: Vector[InventoryAncestor] =
    Scala3PsiPatternAppliedTypeProductions.nestedAppliedTypeEdges ++ Vector(
      InventoryAncestor(
        InventoryKind.Node,
        "Select",
        Vector(CatalogPathSegment.NamedField("qualifier"))
      ),
      InventoryAncestor(
        InventoryKind.Node,
        "TypeBoundsTree",
        Vector(CatalogPathSegment.NamedField("lo"))
      ),
      InventoryAncestor(
        InventoryKind.Node,
        "TypeBoundsTree",
        Vector(CatalogPathSegment.NamedField("hi"))
      ),
      InventoryAncestor(
        InventoryKind.Node,
        "SingletonTypeTree",
        Vector(CatalogPathSegment.NamedField("ref"))
      ),
      InventoryAncestor(
        InventoryKind.Node,
        "InfixOp",
        Vector(CatalogPathSegment.NamedField("left"))
      ),
      InventoryAncestor(
        InventoryKind.Node,
        "InfixOp",
        Vector(CatalogPathSegment.NamedField("right"))
      )
    )

  private def matchEntry(
      edgeOwner: String,
      edgePath: Vector[CatalogPathSegment],
      separator: ParserScannerTokenKind
  ): CompilerProductionContextPattern =
    CompilerProductionContextPattern(
      ContextPattern.SeparatorOwned(
        separator,
        ContextPattern.ParentUnderAnchorThroughWithEvidence(
          InventoryKind.Node,
          edgeOwner,
          edgePath,
          matchStableSelectEdges,
          Scala3PsiMatchExpressionProductions.MatchCasesAncestor,
          Scala3PsiMatchExpressionProductions.SourceSelectorAnchorEvidence
        )
      ),
      SourceClassification.SourceReachable
    )

  private def dottedTypeOccurrences: Vector[CompilerProductionContextPattern] =
    (Scala3PsiMatchExpressionProductions.MatchPatternTypeEdges ++ Scala3PsiMatchExpressionProductions.InfixOperandEdges)
      .map { (owner, path) =>
        matchEntry(owner, path, ParserScannerTokenKind.Dot)
      }

  private def hashProjectionOccurrences: Vector[CompilerProductionContextPattern] =
    Scala3PsiMatchExpressionProductions.MatchPatternTypeEdges.map { (owner, path) =>
      matchEntry(owner, path, ParserScannerTokenKind.Hash)
    } ++ Vector(
      CompilerProductionContextPattern(
        ContextPattern.SeparatorOwned(
          ParserScannerTokenKind.Hash,
          ContextPattern.ParentUnderAnchorThrough(
            InventoryKind.Node,
            "Select",
            Vector(CatalogPathSegment.NamedField("qualifier")),
            matchStableSelectEdges,
            Scala3PsiMatchExpressionProductions.MatchCasesAncestor
          )
        ),
        SourceClassification.SourceReachable
      )
    ) ++ Scala3PsiMatchExpressionProductions.InfixOperandEdges.map { (owner, path) =>
      matchEntry(owner, path, ParserScannerTokenKind.Hash)
    }

  private val dottedReferenceIdent = Scala3PsiProduction(
    id = "match-pattern-dotted-reference-ident",
    grammarRoleId = GrammarRoleId.StableReference,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchorThrough(
            InventoryKind.Node,
            "Select",
            Vector(CatalogPathSegment.NamedField("qualifier")),
            matchStableSelectEdges,
            Scala3PsiMatchExpressionProductions.MatchCasesAncestor
          ),
          SourceClassification.SourceReachable
        ),
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchorThrough(
            InventoryKind.Node,
            "SingletonTypeTree",
            Vector(CatalogPathSegment.NamedField("ref")),
            matchStableSelectEdges,
            Scala3PsiMatchExpressionProductions.MatchCasesAncestor
          ),
          SourceClassification.SourceReachable
        )
      )
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
  )

  private val dottedReference = Scala3PsiProduction(
    id = MatchDottedReferenceProductionId,
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
          ContextPattern.SeparatorOwned(
            ParserScannerTokenKind.Dot,
            ContextPattern.ParentUnderAnchorThrough(
              InventoryKind.Node,
              "Select",
              Vector(CatalogPathSegment.NamedField("qualifier")),
              matchStableSelectEdges,
              Scala3PsiMatchExpressionProductions.MatchCasesAncestor
            )
          ),
          SourceClassification.SourceReachable
        ),
        CompilerProductionContextPattern(
          ContextPattern.SeparatorOwned(
            ParserScannerTokenKind.Dot,
            ContextPattern.ParentUnderAnchorThrough(
              InventoryKind.Node,
              "SingletonTypeTree",
              Vector(CatalogPathSegment.NamedField("ref")),
              matchStableSelectEdges,
              Scala3PsiMatchExpressionProductions.MatchCasesAncestor
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
        "match-pattern-dotted-reference-ident",
        Set(
          "match-pattern-dotted-reference-ident",
          MatchDottedReferenceProductionId
        )
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "reference-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "path-dot",
        TerminalIntervalSelector.AfterChild("qualifier"),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypePathDotTokenSurface, Some(".")),
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
  )

  private val dottedType = Scala3PsiProduction(
    id = MatchDottedTypeProductionId,
    grammarRoleId = GrammarRoleId.SimpleType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Select",
      Vector(
        CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
        CompilerFieldPattern("name", CatalogValuePattern.Name)
      ),
      dottedTypeOccurrences
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
        MatchDottedReferenceProductionId,
        Set(MatchDottedReferenceProductionId, "match-pattern-dotted-reference-ident")
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "type-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "path-dot",
        TerminalIntervalSelector.AfterChild("qualifier"),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypePathDotTokenSurface, Some(".")),
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
    outputTemplate = Some(nativeSimpleTypeTemplateFor("qualifier"))
  )

  private val hashProjection = Scala3PsiProduction(
    id = MatchHashProjectionProductionId,
    grammarRoleId = GrammarRoleId.TypeProjection,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Select",
      Vector(
        CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
        CompilerFieldPattern("name", CatalogValuePattern.Name)
      ),
      hashProjectionOccurrences
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
        "match-pattern-dotted-reference-ident",
        Set(
          "match-pattern-dotted-reference-ident",
          MatchDottedReferenceProductionId,
          MatchHashProjectionProductionId
        )
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "projection-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "projection-hash",
        TerminalIntervalSelector.AfterChild("qualifier"),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeProjectionHashTokenSurface, Some("#")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = TypeProjectionSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = TypeProjectionAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRoleId = None,
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "projection",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.TypeProjection,
            TypeProjectionSurface,
            TypeProjectionAccessors
          ),
          outputComposite(
            "qualifier-type",
            Some("projection"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary
                .ChildStart("qualifier", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly),
              OutputBoundary
                .ChildEnd("qualifier", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly)
            ),
            PsiOutputRoleId.SimpleType,
            SimpleTypeSurface,
            SimpleTypeAccessors
          )
        ),
        Map("qualifier" -> Some("qualifier-type"))
      )
    )
  )

  private def nativeSimpleTypeTemplateFor(qualifierRole: String): LocalOutputCompositeTemplate =
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
      Map(qualifierRole -> Some("reference"))
    )

  private[psiproducer] val PatternStableSelectSuffixSegment: Vector[Scala3PsiProduction] = Vector(
    dottedType,
    dottedReference,
    dottedReferenceIdent,
    hashProjection
  )
