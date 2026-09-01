package com.hmemcpy.metallurgy.psiproducer

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiPatternAppliedTypeProductions:
  private[psiproducer] val AppliedTypeProductionId = "match-pattern-applied-type"

  private[psiproducer] val nestedAppliedTypeEdges =
    Scala3PsiMatchExpressionProductions.PatternNestingEdges ++ Vector(
      InventoryAncestor(InventoryKind.Node, "AppliedTypeTree", Vector(CatalogPathSegment.NamedField("tpt"))),
      InventoryAncestor(
        InventoryKind.Node,
        "AppliedTypeTree",
        Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
      ),
      InventoryAncestor(InventoryKind.Node, "Typed", Vector(CatalogPathSegment.NamedField("pat"))),
      InventoryAncestor(InventoryKind.Node, "Typed", Vector(CatalogPathSegment.NamedField("tpt")))
    )

  private def patternAppliedTypeOccurrences: Vector[CompilerProductionContextPattern] = Vector(
    CompilerProductionContextPattern(
      ContextPattern.ParentUnderAnchorThrough(
        InventoryKind.Node,
        "Typed",
        Vector(CatalogPathSegment.NamedField("tpt")),
        Scala3PsiMatchExpressionProductions.PatternNestingEdges,
        Scala3PsiMatchExpressionProductions.MatchCasesAncestor
      ),
      SourceClassification.SourceReachable
    ),
    CompilerProductionContextPattern(
      ContextPattern.ParentUnderAnchorThrough(
        InventoryKind.Node,
        "AppliedTypeTree",
        Vector(CatalogPathSegment.NamedField("tpt")),
        nestedAppliedTypeEdges,
        Scala3PsiMatchExpressionProductions.MatchCasesAncestor
      ),
      SourceClassification.SourceReachable
    ),
    CompilerProductionContextPattern(
      ContextPattern.ParentUnderAnchorThrough(
        InventoryKind.Node,
        "AppliedTypeTree",
        Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
        nestedAppliedTypeEdges,
        Scala3PsiMatchExpressionProductions.MatchCasesAncestor
      ),
      SourceClassification.SourceReachable
    ),
    Scala3PsiPatternTupleTypeProductions.matchTupleComponentOccurrence
  )

  private val patternAppliedType = Scala3PsiProduction(
    id = AppliedTypeProductionId,
    grammarRoleId = GrammarRoleId.AppliedType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "AppliedTypeTree",
      Vector(
        CompilerFieldPattern("tpt", CatalogValuePattern.Node),
        CompilerFieldPattern("args", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
      ),
      patternAppliedTypeOccurrences
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
        Scala3PsiMatchExpressionProductions.TypeIdentProductionId,
        Set(AppliedTypeProductionId)
      ),
      ChildDeclaration(
        "arguments",
        "args",
        ChildCardinality.Repeated(1, None),
        Scala3PsiMatchExpressionProductions.TypeIdentProductionId,
        Set(
          AppliedTypeProductionId,
          Scala3PsiPatternTupleTypeProductions.MatchTupleTypeProductionId
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
    outputTemplate = Some(Scala3PsiAppliedTypeProductions.appliedTypeOutputTemplate),
    additionalGrammarRoleIds = Set(GrammarRoleId.TypeArgumentList),
    nestedChildRequirements = Vector(
      RequiredChildRootOutcome(
        "constructor",
        ChildRootOutcome.One(
          ChildOutcomeExpectation.OutputRoles(
            Set(PsiOutputRoleId.SimpleType, PsiOutputRoleId.ParameterizedType)
          )
        )
      ),
      RequiredChildRootOutcome(
        "arguments",
        ChildRootOutcome.All(
          ChildOutcomeExpectation.OutputRoles(
            Set(PsiOutputRoleId.SimpleType, PsiOutputRoleId.ParameterizedType, PsiOutputRoleId.TupleType)
          )
        )
      )
    )
  )

  private[psiproducer] val PatternAppliedTypeSegment: Vector[Scala3PsiProduction] = Vector(patternAppliedType)
