package com.hmemcpy.metallurgy.psiproducer

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiPatternInfixTypeProductions:
  private[psiproducer] val MatchInfixTypeProductionId                 = "match-pattern-infix-type"
  private[psiproducer] val MatchInfixOperatorUnionProductionId        = "match-pattern-infix-operator-union"
  private[psiproducer] val MatchInfixOperatorIntersectionProductionId = "match-pattern-infix-operator-intersection"

  private[psiproducer] val matchInfixTypeAnchor: InventoryAncestor =
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
      SourceClassification.SourceReachable
    )

  private def nestedOccurrence(owner: String, path: Vector[CatalogPathSegment]): CompilerProductionContextPattern =
    CompilerProductionContextPattern(
      ContextPattern.ParentUnderAnchorThrough(
        InventoryKind.Node,
        owner,
        path,
        Scala3PsiPatternWildcardTypeProductions.matchWildcardTypeEdges,
        matchInfixTypeAnchor
      ),
      SourceClassification.SourceReachable
    )

  private val matchInfixTypeOccurrences: Vector[CompilerProductionContextPattern] =
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

  private val infixChildRoles = ChildOutcomeExpectation.OutputRoles(
    Set(
      PsiOutputRoleId.SimpleType,
      PsiOutputRoleId.ParameterizedType,
      PsiOutputRoleId.TupleType,
      PsiOutputRoleId.WildcardType,
      PsiOutputRoleId.TypeProjection,
      PsiOutputRoleId.ParenthesizedType,
      PsiOutputRoleId.SingletonType,
      PsiOutputRoleId.LiteralType,
      PsiOutputRoleId.InfixType
    )
  )

  private def operandChild(role: String, field: String): ChildDeclaration =
    ChildDeclaration(
      role,
      field,
      ChildCardinality.ExactlyOne,
      Scala3PsiMatchExpressionProductions.TypeIdentProductionId,
      Set(
        Scala3PsiMatchExpressionProductions.TypeIdentProductionId,
        Scala3PsiPatternAppliedTypeProductions.AppliedTypeProductionId,
        Scala3PsiPatternTupleTypeProductions.MatchTupleTypeProductionId,
        Scala3PsiPatternWildcardTypeProductions.MatchWildcardTypeProductionId,
        Scala3PsiPatternStableSelectProductions.MatchDottedTypeProductionId,
        Scala3PsiPatternStableSelectProductions.MatchHashProjectionProductionId,
        "match-pattern-parenthesized-type",
        "match-pattern-singleton-ident",
        "match-pattern-singleton-select",
        "match-pattern-literal-type",
        MatchInfixTypeProductionId
      )
    )

  private def operatorProduction(id: String, operator: String): Scala3PsiProduction = Scala3PsiProduction(
    id = id,
    grammarRoleId = GrammarRoleId.StableReference,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.ExactName(operator))),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchorThrough(
            InventoryKind.Node,
            "InfixOp",
            Vector(CatalogPathSegment.NamedField("op")),
            Scala3PsiPatternWildcardTypeProductions.matchWildcardTypeEdges,
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

  private val matchInfixOperatorUnion = operatorProduction(
    MatchInfixOperatorUnionProductionId,
    "|"
  )

  private val matchInfixOperatorIntersection = operatorProduction(
    MatchInfixOperatorIntersectionProductionId,
    "&"
  )

  private val matchInfixType = Scala3PsiProduction(
    id = MatchInfixTypeProductionId,
    grammarRoleId = GrammarRoleId.InfixType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "InfixOp",
      Vector(
        CompilerFieldPattern("left", CatalogValuePattern.Node),
        CompilerFieldPattern("op", CatalogValuePattern.Node),
        CompilerFieldPattern("right", CatalogValuePattern.Node)
      ),
      matchInfixTypeOccurrences
    ),
    dispositions = Vector(
      FieldDisposition("left", FieldDispositionKind.Child),
      FieldDisposition("op", FieldDispositionKind.Child),
      FieldDisposition("right", FieldDispositionKind.Child)
    ),
    children = Vector(
      operandChild("left", "left"),
      ChildDeclaration(
        "operator",
        "op",
        ChildCardinality.ExactlyOne,
        MatchInfixOperatorUnionProductionId,
        Set(MatchInfixOperatorUnionProductionId, MatchInfixOperatorIntersectionProductionId)
      ),
      operandChild("right", "right")
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
    ),
    nestedChildRequirements = Vector(
      RequiredChildRootOutcome("left", ChildRootOutcome.One(infixChildRoles)),
      RequiredChildRootOutcome("right", ChildRootOutcome.One(infixChildRoles))
    )
  )

  private[psiproducer] val PatternInfixTypeSuffixSegment: Vector[Scala3PsiProduction] =
    Vector(matchInfixOperatorUnion, matchInfixOperatorIntersection, matchInfixType)
