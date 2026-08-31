package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.ParserScannerTokenKind

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiMatchExpressionProductions:
  val CandidateProductionId = "match-expression-candidate"
  val FallbackProductionId  = "definition-payload-match"

  private[psiproducer] val CaseClauseProductionId       = "match-case-clause"
  private[psiproducer] val GuardProductionId            = "match-guard"
  private[psiproducer] val CaseBodyProductionId         = "match-case-body-block"
  private[psiproducer] val WildcardProductionId         = "match-pattern-wildcard"
  private[psiproducer] val ReferenceProductionId        = "match-pattern-reference"
  private[psiproducer] val LiteralProductionId          = "match-pattern-literal"
  private[psiproducer] val LiteralDecimalProductionId   = "match-pattern-literal-decimal"
  private[psiproducer] val LiteralFloatingProductionId  = "match-pattern-literal-floating"
  private[psiproducer] val LiteralStringProductionId    = "match-pattern-literal-string"
  private[psiproducer] val LiteralCharProductionId      = "match-pattern-literal-char"
  private[psiproducer] val LiteralBooleanProductionId   = "match-pattern-literal-boolean"
  private[psiproducer] val LiteralDoubleProductionId    = "match-pattern-literal-double"
  private[psiproducer] val LiteralFloatProductionId     = "match-pattern-literal-float"
  private[psiproducer] val LiteralLongProductionId      = "match-pattern-literal-long"
  private[psiproducer] val LiteralNullProductionId      = "match-pattern-literal-null"
  private[psiproducer] val StableReferenceProductionId  = "match-pattern-stable-reference"
  private[psiproducer] val TypedProductionId            = "match-pattern-typed"
  private[psiproducer] val TypeIdentProductionId        = "match-pattern-type-ident"
  private[psiproducer] val NamingProductionId           = "match-pattern-naming"
  private[psiproducer] val NamingSequenceProductionId   = "match-pattern-naming-sequence"
  private[psiproducer] val SequenceWildcardProductionId = "match-pattern-sequence-wildcard"
  private[psiproducer] val SequenceMarkerProductionId   = "match-pattern-sequence-wildcard-marker"
  private[psiproducer] val TupleProductionId            = "match-pattern-tuple"
  private[psiproducer] val UnitTupleProductionId        = "match-pattern-unit-tuple"
  private[psiproducer] val GivenProductionId            = "match-pattern-given"
  private[psiproducer] val GivenNamedProductionId       = "match-pattern-given-named"
  private[psiproducer] val GivenTypedProductionId       = "match-pattern-given-typed"
  private[psiproducer] val GivenWildcardProductionId    = "match-pattern-given-wildcard"
  private[psiproducer] val GivenModifiersProductionId   = "match-pattern-given-modifiers"
  private[psiproducer] val GivenModifierProductionId    = "match-pattern-given-modifier"
  private[psiproducer] val AlternativeProductionId      = "match-pattern-alternative"
  private[psiproducer] val ConstructorProductionId      = "match-pattern-constructor"

  private val NativeRealization  = "match-expression-native"
  private val PayloadRealization = "match-expression-payload"

  val MatchCasesAncestor = InventoryAncestor(
    InventoryKind.Node,
    "Match",
    Vector(CatalogPathSegment.NamedField("cases"), CatalogPathSegment.RepeatedElement)
  )

  def underCaseDef(field: String): ContextPattern =
    ContextPattern.ParentWithAncestor(
      InventoryKind.Node,
      "CaseDef",
      Vector(CatalogPathSegment.NamedField(field)),
      MatchCasesAncestor
    )

  def caseDefChildOccurrences(field: String): Vector[CompilerProductionContextPattern] =
    OwnerAncestors.map: chain =>
      CompilerProductionContextPattern(
        ContextPattern.ParentWithAncestorPrefix(
          InventoryKind.Node,
          "CaseDef",
          Vector(CatalogPathSegment.NamedField(field)),
          MatchCasesAncestor +: chain
        ),
        SourceClassification.SourceReachable
      )

  private val OwnerAncestors = Vector("DefDef", "ValDef").flatMap: owner =>
    Vector("PackageDef" -> "stats", "Template" -> "preBody").map: (outer, field) =>
      Vector(
        InventoryAncestor(InventoryKind.Node, owner, Vector(CatalogPathSegment.NamedField("preRhs"))),
        InventoryAncestor(
          InventoryKind.Node,
          outer,
          Vector(CatalogPathSegment.NamedField(field), CatalogPathSegment.RepeatedElement)
        )
      )

  private val CaseClauseOccurrences = Vector("DefDef", "ValDef").flatMap: owner =>
    Vector("PackageDef" -> "stats", "Template" -> "preBody").map: (outer, field) =>
      CompilerProductionContextPattern(
        ContextPattern.ParentWithAncestorPrefix(
          InventoryKind.Node,
          "Match",
          Vector(CatalogPathSegment.NamedField("cases"), CatalogPathSegment.RepeatedElement),
          Vector(
            InventoryAncestor(InventoryKind.Node, owner, Vector(CatalogPathSegment.NamedField("preRhs"))),
            InventoryAncestor(
              InventoryKind.Node,
              outer,
              Vector(CatalogPathSegment.NamedField(field), CatalogPathSegment.RepeatedElement)
            )
          )
        ),
        SourceClassification.SourceReachable
      )

  def matchContextOccurrences(
      parent: String,
      field: String
  ): Vector[CompilerProductionContextPattern] =
    val intermediate = parent match
      case "Match"   => Vector.empty
      case "InfixOp" =>
        Vector(
          InventoryAncestor(
            InventoryKind.Node,
            "CaseDef",
            Vector(CatalogPathSegment.NamedField("guard"))
          ),
          MatchCasesAncestor
        )
      case "Block"   =>
        Vector(
          InventoryAncestor(
            InventoryKind.Node,
            "CaseDef",
            Vector(CatalogPathSegment.NamedField("body"))
          ),
          MatchCasesAncestor
        )
      case other     => sys.error(s"unsupported match context parent: $other")
    OwnerAncestors.map: ownerChain =>
      CompilerProductionContextPattern(
        ContextPattern.ParentWithAncestorPrefix(
          InventoryKind.Node,
          parent,
          Vector(CatalogPathSegment.NamedField(field)),
          intermediate ++ ownerChain
        ),
        SourceClassification.SourceReachable
      )

  private val DirectOccurrences = Vector("DefDef", "ValDef").flatMap: owner =>
    Vector("PackageDef" -> "stats", "Template" -> "preBody").map: (outer, field) =>
      CompilerProductionContextPattern(
        ContextPattern.ParentWithAncestor(
          InventoryKind.Node,
          owner,
          Vector(CatalogPathSegment.NamedField("preRhs")),
          InventoryAncestor(
            InventoryKind.Node,
            outer,
            Vector(CatalogPathSegment.NamedField(field), CatalogPathSegment.RepeatedElement)
          )
        ),
        SourceClassification.SourceReachable
      )

  val CaseBodyExpressionProductions = Vector(
    "atomic-term-ident",
    "atomic-literal-integer",
    "atomic-literal-string"
  )

  private val MatchContextExpressionRoles = Set(
    PsiOutputRoleId.TermReference,
    PsiOutputRoleId.IntegerExpression,
    PsiOutputRoleId.StringExpression
  )

  private val PatternPatternRoles = Set(
    PsiOutputRoleId.PatternWildcard,
    PsiOutputRoleId.ReferencePattern,
    PsiOutputRoleId.StableReferencePattern,
    PsiOutputRoleId.LiteralPattern,
    PsiOutputRoleId.TypedPattern,
    PsiOutputRoleId.NamingPattern,
    PsiOutputRoleId.GivenPattern,
    PsiOutputRoleId.SeqWildcardPattern,
    PsiOutputRoleId.TuplePattern,
    PsiOutputRoleId.CompositePattern,
    PsiOutputRoleId.ConstructorPattern
  )

  private val PatternChildProductionIds = Set(
    WildcardProductionId,
    ReferenceProductionId,
    LiteralProductionId,
    LiteralDecimalProductionId,
    LiteralFloatingProductionId,
    LiteralStringProductionId,
    LiteralCharProductionId,
    LiteralBooleanProductionId,
    LiteralDoubleProductionId,
    LiteralFloatProductionId,
    LiteralLongProductionId,
    LiteralNullProductionId,
    StableReferenceProductionId,
    TypedProductionId,
    GivenProductionId,
    GivenNamedProductionId,
    NamingProductionId,
    NamingSequenceProductionId,
    SequenceWildcardProductionId,
    SequenceMarkerProductionId,
    TupleProductionId,
    UnitTupleProductionId,
    AlternativeProductionId,
    ConstructorProductionId,
    "payload-descendant-ident"
  )

  private[psiproducer] val PatternNestingEdges: Vector[InventoryAncestor] = Vector(
    InventoryAncestor(
      InventoryKind.Node,
      "CaseDef",
      Vector(CatalogPathSegment.NamedField("pat"))
    ),
    InventoryAncestor(
      InventoryKind.Node,
      "Apply",
      Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
    ),
    InventoryAncestor(
      InventoryKind.Node,
      "Tuple",
      Vector(CatalogPathSegment.NamedField("trees"), CatalogPathSegment.RepeatedElement)
    ),
    InventoryAncestor(
      InventoryKind.Node,
      "Alternative",
      Vector(CatalogPathSegment.NamedField("trees"), CatalogPathSegment.RepeatedElement)
    ),
    InventoryAncestor(
      InventoryKind.Node,
      "Bind",
      Vector(CatalogPathSegment.NamedField("body"))
    ),
    InventoryAncestor(
      InventoryKind.Node,
      "Typed",
      Vector(CatalogPathSegment.NamedField("expr"))
    )
  )

  private def patternParentOccurrences(
      owner: String,
      field: String,
      repeated: Boolean,
      classification: SourceClassification = SourceClassification.SourceReachable
  ): Vector[CompilerProductionContextPattern] =
    Vector(
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchorThrough(
          InventoryKind.Node,
          owner,
          Vector(CatalogPathSegment.NamedField(field)) ++
            Option.when(repeated)(CatalogPathSegment.RepeatedElement),
          PatternNestingEdges,
          MatchCasesAncestor
        ),
        classification
      )
    )

  private val PatternSpaceOccurrences =
    patternParentOccurrences("CaseDef", "pat", repeated = false) ++
      patternParentOccurrences("Apply", "args", repeated = true) ++
      patternParentOccurrences("Tuple", "trees", repeated = true) ++
      patternParentOccurrences("Alternative", "trees", repeated = true) ++
      patternParentOccurrences("Bind", "body", repeated = false) ++
      patternParentOccurrences("Typed", "expr", repeated = false)

  private val PatternOwnerAncestors = Vector(
    InventoryAncestor(InventoryKind.Node, "DefDef", Vector(CatalogPathSegment.NamedField("preRhs"))),
    InventoryAncestor(InventoryKind.Node, "ValDef", Vector(CatalogPathSegment.NamedField("preRhs"))),
    InventoryAncestor(InventoryKind.Node, "Template", Vector(CatalogPathSegment.NamedField("preBody")))
  )

  val ConstructorFunOccurrences: Vector[CompilerProductionContextPattern] =
    patternParentOccurrences("Apply", "fun", repeated = false)

  private val nativeMatchTemplate = LocalOutputCompositeTemplate(
    Vector(
      outputComposite(
        "match",
        None,
        OutputRangeDeclaration.CompilerPosition,
        PsiOutputRoleId.MatchExpression,
        MatchSurface,
        MatchAccessors
      ),
      outputComposite(
        "case-clauses",
        Some("match"),
        OutputRangeDeclaration.BoundaryDerived(
          OutputBoundary
            .ChildStart("cases", ChildOccurrenceSelector.First, PositionProvenancePolicy.PositionedIncludingSynthetic),
          OutputBoundary
            .ChildEnd("cases", ChildOccurrenceSelector.Last, PositionProvenancePolicy.PositionedIncludingSynthetic)
        ),
        PsiOutputRoleId.CaseClauses,
        CaseClausesSurface,
        CaseClausesAccessors
      )
    ),
    Map("selector" -> Some("match"), "cases" -> Some("case-clauses"))
  )

  private val nativeCaseClauseTemplate = LocalOutputCompositeTemplate(
    Vector(
      outputComposite(
        "case-clause",
        None,
        OutputRangeDeclaration.CompilerPosition,
        PsiOutputRoleId.CaseClause,
        CaseClauseSurface,
        CaseClauseAccessors
      )
    ),
    Map("pat" -> Some("case-clause"), "guard" -> Some("case-clause"), "body" -> Some("case-clause"))
  )

  private val nativeGuardTemplate = LocalOutputCompositeTemplate(
    Vector(
      outputComposite(
        "guard",
        None,
        OutputRangeDeclaration.CompilerPosition,
        PsiOutputRoleId.Guard,
        GuardSurface,
        GuardAccessors
      )
    ),
    Map("left" -> Some("guard"), "op" -> Some("guard"), "right" -> Some("guard"))
  )

  private val nativeCaseBodyTemplate = LocalOutputCompositeTemplate(
    Vector(
      outputComposite(
        "block",
        None,
        OutputRangeDeclaration.CompilerPosition,
        PsiOutputRoleId.Block,
        BlockSurface,
        BlockAccessors
      )
    ),
    Map("stats" -> Some("block"), "expr" -> Some("block"))
  )

  private def nativePatternTemplate(
      surface: String,
      accessors: Vector[AccessorObligation],
      outputRole: PsiOutputRoleId
  ) =
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "pattern",
          None,
          OutputRangeDeclaration.CompilerPosition,
          outputRole,
          surface,
          accessors
        )
      ),
      Map.empty
    )

  private val nativeUnitTuplePatternTemplate = LocalOutputCompositeTemplate(
    Vector(
      outputComposite(
        "tuple",
        None,
        OutputRangeDeclaration.CompilerPosition,
        PsiOutputRoleId.TuplePattern,
        TuplePatternSurface,
        TuplePatternAccessors
      )
    ),
    Map.empty
  )

  private def nativeGroupedPatternTemplate(
      rootId: String,
      surface: String,
      outputRole: PsiOutputRoleId,
      accessors: Vector[AccessorObligation],
      childrenRole: String,
      extraMounts: Map[String, String] = Map.empty,
      childrenRoleOutput: PsiOutputRoleId = PsiOutputRoleId.Patterns,
      childrenSurface: String = PatternsSurface,
      childrenAccessors: Vector[AccessorObligation] = PatternsAccessors
  ) =
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          rootId,
          None,
          OutputRangeDeclaration.CompilerPosition,
          outputRole,
          surface,
          accessors
        ),
        outputComposite(
          s"$rootId-children",
          Some(rootId),
          OutputRangeDeclaration.BoundaryDerived(
            OutputBoundary
              .ChildStart(childrenRole, ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly),
            OutputBoundary
              .ChildEnd(childrenRole, ChildOccurrenceSelector.Last, PositionProvenancePolicy.SourceDerivedOnly)
          ),
          childrenRoleOutput,
          childrenSurface,
          childrenAccessors
        )
      ),
      Map(childrenRole -> Some(s"$rootId-children")) ++ extraMounts.view.mapValues(id => Some(id)).toMap
    )

  private val nativeTypedPatternTemplate = LocalOutputCompositeTemplate(
    Vector(
      outputComposite(
        "typed",
        None,
        OutputRangeDeclaration.CompilerPosition,
        PsiOutputRoleId.TypedPattern,
        Sc3TypedPatternSurface,
        Sc3TypedPatternAccessors
      ),
      outputComposite(
        "type-pattern",
        Some("typed"),
        OutputRangeDeclaration.BoundaryDerived(
          OutputBoundary
            .ChildStart("tpt", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly),
          OutputBoundary
            .ChildEnd("tpt", ChildOccurrenceSelector.Last, PositionProvenancePolicy.SourceDerivedOnly)
        ),
        PsiOutputRoleId.TypePattern,
        TypePatternSurface,
        TypePatternAccessors
      )
    ),
    Map("expr" -> Some("typed"), "tpt" -> Some("type-pattern"))
  )

  private val nativeSimpleTypeTemplate = LocalOutputCompositeTemplate(
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

  private val namingBodyPatternTemplate = LocalOutputCompositeTemplate(
    Vector(
      outputComposite(
        "naming",
        None,
        OutputRangeDeclaration.CompilerPosition,
        PsiOutputRoleId.NamingPattern,
        NamingPatternSurface,
        NamingPatternAccessors
      )
    ),
    Map("body" -> Some("naming"))
  )

  private val alternativePatternTemplate = LocalOutputCompositeTemplate(
    Vector(
      outputComposite(
        "composite",
        None,
        OutputRangeDeclaration.CompilerPosition,
        PsiOutputRoleId.CompositePattern,
        CompositePatternSurface,
        CompositePatternAccessors
      )
    ),
    Map("trees" -> Some("composite"))
  )

  private val sequenceWildcardTemplate = LocalOutputCompositeTemplate(
    Vector(
      outputComposite(
        "sequence",
        None,
        OutputRangeDeclaration.CompilerPosition,
        PsiOutputRoleId.SeqWildcardPattern,
        SeqWildcardPatternSurface,
        SeqWildcardPatternAccessors
      )
    ),
    Map("expr" -> None, "tpt" -> None)
  )

  private val namingSequenceTemplate = LocalOutputCompositeTemplate(
    Vector(
      outputComposite(
        "sequence",
        Some("naming"),
        OutputRangeDeclaration.BoundaryDerived(
          OutputBoundary
            .ChildStart("tpt", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly),
          OutputBoundary
            .ChildEnd("tpt", ChildOccurrenceSelector.Last, PositionProvenancePolicy.SourceDerivedOnly)
        ),
        PsiOutputRoleId.SeqWildcardPattern,
        SeqWildcardPatternSurface,
        SeqWildcardPatternAccessors
      ),
      outputComposite(
        "naming",
        None,
        OutputRangeDeclaration.CompilerPosition,
        PsiOutputRoleId.NamingPattern,
        NamingPatternSurface,
        NamingPatternAccessors
      )
    ),
    Map("expr" -> None, "tpt" -> None)
  )

  val MatchExpressionContextOccurrences: Vector[CompilerProductionContextPattern] =
    matchContextOccurrences("Match", "selector")

  val MatchGuardContextOccurrences: Vector[CompilerProductionContextPattern] =
    matchContextOccurrences("InfixOp", "left") ++
      matchContextOccurrences("InfixOp", "op") ++
      matchContextOccurrences("InfixOp", "right")

  val MatchCaseBodyContextOccurrences: Vector[CompilerProductionContextPattern] =
    matchContextOccurrences("Block", "expr") ++
      matchContextOccurrences("Block", "stats")

  private val Match = Scala3PsiProduction(
    id = CandidateProductionId,
    grammarRoleId = GrammarRoleId.MatchExpression,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Match",
      Vector(
        CompilerFieldPattern("selector", CatalogValuePattern.Node),
        CompilerFieldPattern("cases", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
      ),
      DirectOccurrences
    ),
    dispositions = Vector(
      FieldDisposition("selector", FieldDispositionKind.Child),
      FieldDisposition("cases", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "selector",
        "selector",
        ChildCardinality.ExactlyOne,
        CaseBodyExpressionProductions.head,
        CaseBodyExpressionProductions.tail.toSet
      ),
      ChildDeclaration(
        "cases",
        "cases",
        ChildCardinality.Repeated(1, None),
        CaseClauseProductionId
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "match-residual",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "payload",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = MatchSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = MatchAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRealizations = Vector(
      OutputRealization(
        NativeRealization,
        Vector.empty,
        nativeMatchTemplate,
        requiredChildRoots = Vector(
          RequiredChildRootOutcome(
            "selector",
            ChildRootOutcome.One(ChildOutcomeExpectation.OutputRoles(MatchContextExpressionRoles))
          ),
          RequiredChildRootOutcome(
            "cases",
            ChildRootOutcome.All(ChildOutcomeExpectation.OutputRoles(Set(PsiOutputRoleId.CaseClause)))
          )
        ),
        terminalIds = Some(Set("match-residual"))
      ),
      OutputRealization(
        PayloadRealization,
        Vector.empty,
        LocalOutputCompositeTemplate(
          Vector(
            outputComposite(
              "payload",
              None,
              OutputRangeDeclaration.CompilerPosition,
              PsiOutputRoleId.ExpressionPayload,
              ExpressionPayloadSurface,
              ExpressionPayloadAccessors,
              TargetRequirement.Compatible
            )
          ),
          Map("selector" -> Some("payload"), "cases" -> None)
        ),
        childClosureAbsorptions = Vector(
          ChildClosureAbsorption("selector", ChildRootOutcome.AnyReviewed, MatchContextExpressionRoles),
          ChildClosureAbsorption("cases", ChildRootOutcome.AnyReviewed)
        ),
        terminalIds = Some(Set("payload"))
      )
    ),
    outputRoleId = None,
    realizationChoice = Some(
      RealizationChoice(
        Vector(NativeRealization),
        PayloadRealization
      )
    ),
    nestedChildRequirements = Vector.empty
  )

  private val MatchCaseClause = Scala3PsiProduction(
    id = CaseClauseProductionId,
    grammarRoleId = GrammarRoleId.CaseClause,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "CaseDef",
      Vector(
        CompilerFieldPattern("pat", CatalogValuePattern.Node),
        CompilerFieldPattern("guard", CatalogValuePattern.Node),
        CompilerFieldPattern("body", CatalogValuePattern.Node)
      ),
      occurrences = CaseClauseOccurrences
    ),
    dispositions = Vector(
      FieldDisposition("pat", FieldDispositionKind.Child),
      FieldDisposition("guard", FieldDispositionKind.Child),
      FieldDisposition("body", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "pat",
        "pat",
        ChildCardinality.ExactlyOne,
        WildcardProductionId,
        PatternChildProductionIds - WildcardProductionId
      ),
      ChildDeclaration(
        "guard",
        "guard",
        ChildCardinality.Optional,
        GuardProductionId,
        Set("template-absent-tree")
      ),
      ChildDeclaration(
        "body",
        "body",
        ChildCardinality.ExactlyOne,
        CaseBodyProductionId
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "case-keyword",
        TerminalIntervalSelector.BeforeChild("pat"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "arrow",
        TerminalIntervalSelector.CompilerScannerTokenInChildGap(
          ParserScannerTokenKind.FunctionArrow,
          "pat",
          "body"
        ),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "pat-body-gap",
        TerminalIntervalSelector.ChildGap("pat", "body"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.Repeated(0, None),
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = CaseClauseSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = CaseClauseAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(nativeCaseClauseTemplate),
    outputRoleId = None,
    nestedChildRequirements = Vector(
      RequiredChildRootOutcome(
        "pat",
        ChildRootOutcome.One(ChildOutcomeExpectation.OutputRoles(PatternPatternRoles))
      )
    )
  )

  private val MatchGuard = Scala3PsiProduction(
    id = GuardProductionId,
    grammarRoleId = GrammarRoleId.CaseClause,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "InfixOp",
      Vector(
        CompilerFieldPattern("left", CatalogValuePattern.Node),
        CompilerFieldPattern("op", CatalogValuePattern.Node),
        CompilerFieldPattern("right", CatalogValuePattern.Node)
      ),
      caseDefChildOccurrences("guard")
    ),
    dispositions = Vector(
      FieldDisposition("left", FieldDispositionKind.Child),
      FieldDisposition("op", FieldDispositionKind.Child),
      FieldDisposition("right", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "left",
        "left",
        ChildCardinality.ExactlyOne,
        CaseBodyExpressionProductions.head,
        CaseBodyExpressionProductions.tail.toSet
      ),
      ChildDeclaration(
        "op",
        "op",
        ChildCardinality.ExactlyOne,
        "atomic-term-ident"
      ),
      ChildDeclaration(
        "right",
        "right",
        ChildCardinality.ExactlyOne,
        CaseBodyExpressionProductions.head,
        CaseBodyExpressionProductions.tail.toSet
      )
    ),
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = GuardSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = GuardAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(nativeGuardTemplate),
    outputRoleId = None
  )

  private val MatchCaseBodyBlock = Scala3PsiProduction(
    id = CaseBodyProductionId,
    grammarRoleId = GrammarRoleId.CaseClause,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Block",
      Vector(
        CompilerFieldPattern("stats", CatalogValuePattern.Repeated(CatalogValuePattern.Node)),
        CompilerFieldPattern("expr", CatalogValuePattern.Node)
      ),
      caseDefChildOccurrences("body")
    ),
    dispositions = Vector(
      FieldDisposition("stats", FieldDispositionKind.Child),
      FieldDisposition("expr", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "stats",
        "stats",
        ChildCardinality.Repeated(0, None),
        CaseBodyExpressionProductions.head,
        CaseBodyExpressionProductions.tail.toSet
      ),
      ChildDeclaration(
        "expr",
        "expr",
        ChildCardinality.ExactlyOne,
        CaseBodyExpressionProductions.head,
        CaseBodyExpressionProductions.tail.toSet
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "block-residual",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = BlockSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = BlockAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(nativeCaseBodyTemplate),
    outputRoleId = None
  )

  private val PatternWildcard = Scala3PsiProduction(
    id = WildcardProductionId,
    grammarRoleId = GrammarRoleId.PatternWildcard,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Wildcard))),
      PatternSpaceOccurrences
    ),
    dispositions = Vector(FieldDisposition("name", FieldDispositionKind.SemanticOnly)),
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "contents",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = WildcardPatternSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = WildcardPatternAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate =
      Some(nativePatternTemplate(WildcardPatternSurface, WildcardPatternAccessors, PsiOutputRoleId.PatternWildcard)),
    outputRoleId = Some(PsiOutputRoleId.PatternWildcard)
  )

  private val PatternReference = Scala3PsiProduction(
    id = ReferenceProductionId,
    grammarRoleId = GrammarRoleId.ReferenceBinding,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
      PatternSpaceOccurrences
    ),
    dispositions = Vector(FieldDisposition("name", FieldDispositionKind.SemanticOnly)),
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "contents",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ReferencePatternSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = ReferencePatternAccessors,
    persistence = PersistenceObligations.Required(
      DefinitionPersistenceSurfaces.BindingStub,
      DefinitionPersistenceSurfaces.BindingSerializer,
      Vector.empty,
      ImportPersistenceSurfaces.SelfNavigation
    ),
    navigation = Some(NavigationObligation.Self),
    outputTemplate =
      Some(nativePatternTemplate(ReferencePatternSurface, ReferencePatternAccessors, PsiOutputRoleId.ReferencePattern)),
    outputRoleId = Some(PsiOutputRoleId.ReferencePattern)
  )

  private val PatternLiteral = Scala3PsiProduction(
    id = LiteralProductionId,
    grammarRoleId = GrammarRoleId.PatternLiteral,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Number",
      Vector(
        CompilerFieldPattern("digits", CatalogValuePattern.Scalar("Text")),
        CompilerFieldPattern(
          "kind",
          CatalogValuePattern.Product(
            "Whole",
            Vector(CompilerFieldPattern("radix", CatalogValuePattern.Scalar("Integer")))
          )
        )
      ),
      PatternSpaceOccurrences
    ),
    dispositions = Vector(
      FieldDisposition("digits", FieldDispositionKind.SemanticOnly),
      FieldDisposition("kind", FieldDispositionKind.SemanticOnly)
    ),
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "contents",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = LiteralPatternSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = LiteralPatternAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(nativeLiteralPatternTemplate(IntegerLiteralSurface, PsiOutputRoleId.IntegerExpression)),
    outputRoleId = Some(PsiOutputRoleId.LiteralPattern)
  )

  private def nativeLiteralPatternTemplate(literalSurface: String, literalRole: PsiOutputRoleId) =
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "pattern",
          None,
          OutputRangeDeclaration.CompilerPosition,
          PsiOutputRoleId.LiteralPattern,
          LiteralPatternSurface,
          LiteralPatternAccessors
        ),
        outputComposite(
          "literal",
          Some("pattern"),
          OutputRangeDeclaration.CompilerPosition,
          literalRole,
          literalSurface,
          AtomicLiteralAccessors
        )
      ),
      Map.empty
    )

  private def numberPatternLiteral(
      id: String,
      numberKind: String,
      literalSurface: String,
      literalRole: PsiOutputRoleId
  ): Scala3PsiProduction =
    Scala3PsiProduction(
      id = id,
      grammarRoleId = GrammarRoleId.PatternLiteral,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Number",
        Vector(
          CompilerFieldPattern("digits", CatalogValuePattern.Scalar("Text")),
          CompilerFieldPattern("kind", CatalogValuePattern.Product(numberKind, Vector.empty))
        ),
        PatternSpaceOccurrences
      ),
      dispositions = Vector(
        FieldDisposition("digits", FieldDispositionKind.SemanticOnly),
        FieldDisposition("kind", FieldDispositionKind.SemanticOnly)
      ),
      children = Vector.empty,
      terminals = Vector(
        TerminalDeclaration(
          "contents",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = LiteralPatternSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = LiteralPatternAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputTemplate = Some(nativeLiteralPatternTemplate(literalSurface, literalRole)),
      outputRoleId = Some(PsiOutputRoleId.LiteralPattern)
    )

  private val PatternLiteralDecimal  =
    numberPatternLiteral(LiteralDecimalProductionId, "Decimal", DoubleLiteralSurface, PsiOutputRoleId.DoubleExpression)
  private val PatternLiteralFloating =
    numberPatternLiteral(
      LiteralFloatingProductionId,
      "Floating",
      DoubleLiteralSurface,
      PsiOutputRoleId.DoubleExpression
    )

  private def constPatternLiteral(
      id: String,
      scalarKind: String,
      literalSurface: String,
      literalRole: PsiOutputRoleId
  ): Scala3PsiProduction =
    Scala3PsiProduction(
      id = id,
      grammarRoleId = GrammarRoleId.PatternLiteral,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Literal",
        Vector(
          CompilerFieldPattern(
            "const",
            CatalogValuePattern.Product(
              "",
              Vector(CompilerFieldPattern("", CatalogValuePattern.Scalar(scalarKind)))
            )
          )
        ),
        PatternSpaceOccurrences
      ),
      dispositions = Vector(
        FieldDisposition("const", FieldDispositionKind.SemanticOnly)
      ),
      children = Vector.empty,
      terminals = Vector(
        TerminalDeclaration(
          "contents",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = LiteralPatternSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = LiteralPatternAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputTemplate = Some(nativeLiteralPatternTemplate(literalSurface, literalRole)),
      outputRoleId = Some(PsiOutputRoleId.LiteralPattern)
    )

  private val PatternLiteralString  =
    constPatternLiteral(LiteralStringProductionId, "Text", StringLiteralSurface, PsiOutputRoleId.StringExpression)
  private val PatternLiteralChar    =
    constPatternLiteral(LiteralCharProductionId, "Character", CharLiteralSurface, PsiOutputRoleId.CharExpression)
  private val PatternLiteralBoolean =
    constPatternLiteral(LiteralBooleanProductionId, "Logical", BooleanLiteralSurface, PsiOutputRoleId.BooleanExpression)
  private val PatternLiteralDouble  =
    constPatternLiteral(LiteralDoubleProductionId, "Decimal", DoubleLiteralSurface, PsiOutputRoleId.DoubleExpression)
  private val PatternLiteralFloat   =
    constPatternLiteral(LiteralFloatProductionId, "FloatDecimal", FloatLiteralSurface, PsiOutputRoleId.FloatExpression)

  private val PatternLiteralLong =
    constPatternLiteral(LiteralLongProductionId, "LongInteger", LongLiteralSurface, PsiOutputRoleId.LongExpression)
  private val PatternLiteralNull =
    constPatternLiteral(LiteralNullProductionId, "NullValue", NullLiteralSurface, PsiOutputRoleId.NullExpression)

  private val PatternStableReference = Scala3PsiProduction(
    id = StableReferenceProductionId,
    grammarRoleId = GrammarRoleId.PatternStableIdentifier,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.NonLowercaseName)),
      PatternSpaceOccurrences
    ),
    dispositions = Vector(FieldDisposition("name", FieldDispositionKind.SemanticOnly)),
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "contents",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = StableReferencePatternSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = StableReferencePatternAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      nativePatternTemplate(
        StableReferencePatternSurface,
        StableReferencePatternAccessors,
        PsiOutputRoleId.StableReferencePattern
      )
    ),
    outputRoleId = Some(PsiOutputRoleId.StableReferencePattern)
  )

  private val PatternTyped = Scala3PsiProduction(
    id = TypedProductionId,
    grammarRoleId = GrammarRoleId.PatternTyped,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Typed",
      Vector(
        CompilerFieldPattern("expr", CatalogValuePattern.Node),
        CompilerFieldPattern("tpt", CatalogValuePattern.Node)
      ),
      PatternSpaceOccurrences.map(value =>
        value.copy(scannerEvidence = ScannerEvidencePattern(required = Set(ParserScannerTokenKind.Colon)))
      )
    ),
    dispositions = Vector(
      FieldDisposition("expr", FieldDispositionKind.Child),
      FieldDisposition("tpt", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "expr",
        "expr",
        ChildCardinality.ExactlyOne,
        WildcardProductionId,
        PatternChildProductionIds - WildcardProductionId
      ),
      ChildDeclaration(
        "tpt",
        "tpt",
        ChildCardinality.ExactlyOne,
        TypeIdentProductionId,
        Set(
          Scala3PsiPatternAppliedTypeProductions.AppliedTypeProductionId
        )
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "colon-gap",
        TerminalIntervalSelector.ChildGap("expr", "tpt"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = Sc3TypedPatternSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Sc3TypedPatternAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(nativeTypedPatternTemplate),
    outputRoleId = None,
    nestedChildRequirements = Vector(
      RequiredChildRootOutcome(
        "expr",
        ChildRootOutcome.One(ChildOutcomeExpectation.OutputRoles(PatternPatternRoles))
      ),
      RequiredChildRootOutcome(
        "tpt",
        ChildRootOutcome.One(
          ChildOutcomeExpectation.OutputRoles(Set(PsiOutputRoleId.SimpleType, PsiOutputRoleId.ParameterizedType))
        )
      )
    )
  )

  private val PatternTypeIdent = Scala3PsiProduction(
    id = TypeIdentProductionId,
    grammarRoleId = GrammarRoleId.SimpleType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchorThrough(
            InventoryKind.Node,
            "Typed",
            Vector(CatalogPathSegment.NamedField("tpt")),
            PatternNestingEdges,
            MatchCasesAncestor
          ),
          SourceClassification.SourceReachable
        ),
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchorThrough(
            InventoryKind.Node,
            "AppliedTypeTree",
            Vector(CatalogPathSegment.NamedField("tpt")),
            Scala3PsiPatternAppliedTypeProductions.nestedAppliedTypeEdges,
            MatchCasesAncestor
          ),
          SourceClassification.SourceReachable
        ),
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchorThrough(
            InventoryKind.Node,
            "AppliedTypeTree",
            Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
            Scala3PsiPatternAppliedTypeProductions.nestedAppliedTypeEdges,
            MatchCasesAncestor
          ),
          SourceClassification.SourceReachable
        )
      )
    ),
    dispositions = Vector(FieldDisposition("name", FieldDispositionKind.SemanticOnly)),
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
    outputTemplate = Some(nativeSimpleTypeTemplate),
    outputRoleId = None
  )

  private val PatternNaming = Scala3PsiProduction(
    id = NamingProductionId,
    grammarRoleId = GrammarRoleId.PatternNaming,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Bind",
      Vector(
        CompilerFieldPattern("name", CatalogValuePattern.Name),
        CompilerFieldPattern("body", CatalogValuePattern.Node),
        CompilerFieldPattern("mods", emptyModifiers(0L))
      ),
      PatternSpaceOccurrences
    ),
    dispositions = Vector(
      FieldDisposition("name", FieldDispositionKind.SemanticOnly),
      FieldDisposition("body", FieldDispositionKind.Child),
      FieldDisposition("mods", FieldDispositionKind.SemanticOnly)
    ),
    children = Vector(
      ChildDeclaration(
        "body",
        "body",
        ChildCardinality.ExactlyOne,
        WildcardProductionId,
        PatternChildProductionIds - WildcardProductionId
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "binder",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = NamingPatternSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = NamingPatternAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(namingBodyPatternTemplate),
    outputRoleId = Some(PsiOutputRoleId.NamingPattern),
    nestedChildRequirements = Vector(
      RequiredChildRootOutcome(
        "body",
        ChildRootOutcome.One(ChildOutcomeExpectation.OutputRoles(PatternPatternRoles))
      )
    )
  )

  private val PatternNamingSequence = Scala3PsiProduction(
    id = NamingSequenceProductionId,
    grammarRoleId = GrammarRoleId.PatternSequenceWildcard,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Typed",
      Vector(
        CompilerFieldPattern("expr", CatalogValuePattern.Node),
        CompilerFieldPattern("tpt", CatalogValuePattern.Node)
      ),
      PatternSpaceOccurrences.map(value =>
        value.copy(scannerEvidence = ScannerEvidencePattern(required = Set(ParserScannerTokenKind.AtSign)))
      )
    ),
    dispositions = Vector(
      FieldDisposition("expr", FieldDispositionKind.Child),
      FieldDisposition("tpt", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "expr",
        "expr",
        ChildCardinality.ExactlyOne,
        WildcardProductionId,
        PatternChildProductionIds - WildcardProductionId
      ),
      ChildDeclaration(
        "tpt",
        "tpt",
        ChildCardinality.ExactlyOne,
        TypeIdentProductionId,
        Set(SequenceWildcardProductionId)
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "binder",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = NamingPatternSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = NamingPatternAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRealizations = Vector(
      OutputRealization(
        "native",
        Vector.empty,
        namingSequenceTemplate,
        childClosureAbsorptions = Vector(
          ChildClosureAbsorption("expr", ChildRootOutcome.AnyReviewed),
          ChildClosureAbsorption("tpt", ChildRootOutcome.AnyReviewed)
        )
      )
    ),
    outputRoleId = Some(PsiOutputRoleId.NamingPattern),
    nestedChildRequirements = Vector(
      RequiredChildRootOutcome(
        "tpt",
        ChildRootOutcome.One(ChildOutcomeExpectation.OutputRoles(Set(PsiOutputRoleId.SimpleType)))
      )
    )
  )

  private val PatternSequenceWildcard = Scala3PsiProduction(
    id = SequenceWildcardProductionId,
    grammarRoleId = GrammarRoleId.PatternSequenceWildcard,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Typed",
      Vector(
        CompilerFieldPattern("expr", CatalogValuePattern.Node),
        CompilerFieldPattern("tpt", CatalogValuePattern.Node)
      ),
      PatternSpaceOccurrences.map(_.copy(sourceClassification = SourceClassification.Synthetic))
    ),
    dispositions = Vector(
      FieldDisposition("expr", FieldDispositionKind.Child),
      FieldDisposition("tpt", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "expr",
        "expr",
        ChildCardinality.ExactlyOne,
        WildcardProductionId,
        PatternChildProductionIds - WildcardProductionId
      ),
      ChildDeclaration(
        "tpt",
        "tpt",
        ChildCardinality.ExactlyOne,
        TypeIdentProductionId
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "contents",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = SeqWildcardPatternSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = SeqWildcardPatternAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRealizations = Vector(
      OutputRealization(
        "native",
        Vector.empty,
        sequenceWildcardTemplate,
        childClosureAbsorptions = Vector(
          ChildClosureAbsorption("expr", ChildRootOutcome.AnyReviewed),
          ChildClosureAbsorption("tpt", ChildRootOutcome.AnyReviewed)
        )
      )
    ),
    outputRoleId = Some(PsiOutputRoleId.SeqWildcardPattern)
  )

  private val PatternTuple = Scala3PsiProduction(
    id = TupleProductionId,
    grammarRoleId = GrammarRoleId.PatternTuple,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Tuple",
      Vector(CompilerFieldPattern("trees", CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.Node))),
      PatternSpaceOccurrences
    ),
    dispositions = Vector(FieldDisposition("trees", FieldDispositionKind.Child)),
    children = Vector(
      ChildDeclaration(
        "trees",
        "trees",
        ChildCardinality.Repeated(0, None),
        WildcardProductionId,
        PatternChildProductionIds - WildcardProductionId
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = TuplePatternSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = TuplePatternAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      nativeGroupedPatternTemplate(
        "tuple",
        TuplePatternSurface,
        PsiOutputRoleId.TuplePattern,
        TuplePatternAccessors,
        "trees"
      )
    ),
    outputRoleId = None,
    nestedChildRequirements = Vector(
      RequiredChildRootOutcome(
        "trees",
        ChildRootOutcome.All(ChildOutcomeExpectation.OutputRoles(PatternPatternRoles))
      )
    )
  )

  private val PatternUnitTuple = Scala3PsiProduction(
    id = UnitTupleProductionId,
    grammarRoleId = GrammarRoleId.PatternTuple,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Tuple",
      Vector(CompilerFieldPattern("trees", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node))),
      PatternSpaceOccurrences
    ),
    dispositions = Vector(FieldDisposition("trees", FieldDispositionKind.SemanticOnly)),
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = TuplePatternSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = TuplePatternAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(nativeUnitTuplePatternTemplate),
    outputRoleId = None
  )

  private val PatternAlternative = Scala3PsiProduction(
    id = AlternativeProductionId,
    grammarRoleId = GrammarRoleId.PatternAlternative,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Alternative",
      Vector(CompilerFieldPattern("trees", CatalogValuePattern.Repeated(CatalogValuePattern.Node))),
      PatternSpaceOccurrences
    ),
    dispositions = Vector(FieldDisposition("trees", FieldDispositionKind.Child)),
    children = Vector(
      ChildDeclaration(
        "trees",
        "trees",
        ChildCardinality.Repeated(2, None),
        WildcardProductionId,
        PatternChildProductionIds - WildcardProductionId
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "separators",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = CompositePatternSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = CompositePatternAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(alternativePatternTemplate),
    outputRoleId = Some(PsiOutputRoleId.CompositePattern),
    nestedChildRequirements = Vector(
      RequiredChildRootOutcome(
        "trees",
        ChildRootOutcome.All(ChildOutcomeExpectation.OutputRoles(PatternPatternRoles))
      )
    )
  )

  private val PatternConstructor = Scala3PsiProduction(
    id = ConstructorProductionId,
    grammarRoleId = GrammarRoleId.PatternConstructor,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Apply",
      Vector(
        CompilerFieldPattern("fun", CatalogValuePattern.Node),
        CompilerFieldPattern("args", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
      ),
      PatternSpaceOccurrences
    ),
    dispositions = Vector(
      FieldDisposition("fun", FieldDispositionKind.Child),
      FieldDisposition("args", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration("fun", "fun", ChildCardinality.ExactlyOne, "atomic-term-ident"),
      ChildDeclaration(
        "args",
        "args",
        ChildCardinality.Repeated(0, None),
        WildcardProductionId,
        PatternChildProductionIds - WildcardProductionId
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ConstructorPatternSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = ConstructorPatternAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      nativeGroupedPatternTemplate(
        "constructor",
        ConstructorPatternSurface,
        PsiOutputRoleId.ConstructorPattern,
        ConstructorPatternAccessors,
        "args",
        Map("fun" -> "constructor"),
        PsiOutputRoleId.PatternArgumentList,
        PatternArgumentListSurface,
        PatternArgumentListAccessors
      )
    ),
    outputRoleId = None,
    nestedChildRequirements = Vector(
      RequiredChildRootOutcome(
        "fun",
        ChildRootOutcome.One(ChildOutcomeExpectation.OutputRoles(Set(PsiOutputRoleId.TermReference)))
      ),
      RequiredChildRootOutcome(
        "args",
        ChildRootOutcome.All(ChildOutcomeExpectation.OutputRoles(PatternPatternRoles))
      )
    )
  )

  private val PatternSequenceMarker = Scala3PsiProduction(
    id = SequenceMarkerProductionId,
    grammarRoleId = GrammarRoleId.PatternSequenceWildcard,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.ExactName("_*"))),
      PatternSpaceOccurrences
    ),
    dispositions = Vector(FieldDisposition("name", FieldDispositionKind.SemanticOnly)),
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "contents",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = SeqWildcardPatternSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = SeqWildcardPatternAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      nativePatternTemplate(SeqWildcardPatternSurface, SeqWildcardPatternAccessors, PsiOutputRoleId.SeqWildcardPattern)
    ),
    outputRoleId = Some(PsiOutputRoleId.SeqWildcardPattern)
  )

  private val PatternBindModifiers = Scala3PsiProduction(
    id = "match-pattern-binder-modifiers",
    grammarRoleId = GrammarRoleId.Modifiers,
    pattern = CompilerProductionPattern(
      InventoryKind.Product,
      "Modifiers",
      Vector(
        CompilerFieldPattern("flags", CatalogValuePattern.ExactScalar("LongInteger", "LongInteger(0)")),
        CompilerFieldPattern("privateWithin", CatalogValuePattern.ClassifiedName(NeutralNameClass.Empty)),
        CompilerFieldPattern("annotations", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)),
        CompilerFieldPattern("mods", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Positioned))
      ),
      PatternOwnerAncestors.map(parent =>
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchorThroughWithParent(
            InventoryKind.Node,
            "Bind",
            Vector(CatalogPathSegment.NamedField("mods")),
            PatternNestingEdges,
            MatchCasesAncestor,
            parent
          ),
          SourceClassification.Absent
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("flags", FieldDispositionKind.SemanticOnly),
      FieldDisposition("privateWithin", FieldDispositionKind.SemanticOnly),
      FieldDisposition("annotations", FieldDispositionKind.Synthetic),
      FieldDisposition("mods", FieldDispositionKind.Synthetic)
    ),
    children = Vector.empty,
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ModifierListSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(transparentTemplate()),
    outputRoleId = None
  )

  private def givenModifiersPattern: Vector[CompilerFieldPattern] = Vector(
    CompilerFieldPattern("flags", CatalogValuePattern.ExactScalar("LongInteger", "LongInteger(536870915)")),
    CompilerFieldPattern("privateWithin", CatalogValuePattern.ClassifiedName(NeutralNameClass.Empty)),
    CompilerFieldPattern("annotations", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)),
    CompilerFieldPattern("mods", CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.Positioned))
  )

  private def givenBindOccurrences(
      requiredScannerKinds: Set[ParserScannerTokenKind]
  ): Vector[CompilerProductionContextPattern] =
    PatternSpaceOccurrences.map(value =>
      value.copy(scannerEvidence =
        ScannerEvidencePattern(
          required = requiredScannerKinds,
          forbidden = if requiredScannerKinds.isEmpty then Set(ParserScannerTokenKind.AtSign) else Set.empty
        )
      )
    )

  private def nativeGivenComposite(
      id: String,
      parent: Option[String],
      range: OutputRangeDeclaration
  ) =
    outputComposite(
      id,
      parent,
      range,
      PsiOutputRoleId.GivenPattern,
      GivenPatternSurface,
      GivenPatternAccessors
    )

  private val PatternGiven = Scala3PsiProduction(
    id = GivenProductionId,
    grammarRoleId = GrammarRoleId.PatternGiven,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Bind",
      Vector(
        CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Wildcard)),
        CompilerFieldPattern("body", CatalogValuePattern.NodePrefix("Typed")),
        CompilerFieldPattern("mods", CatalogValuePattern.Product("Modifiers", givenModifiersPattern))
      ),
      givenBindOccurrences(Set.empty)
    ),
    dispositions = Vector(
      FieldDisposition("name", FieldDispositionKind.SemanticOnly),
      FieldDisposition("body", FieldDispositionKind.Child),
      FieldDisposition("mods", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration("body", "body", ChildCardinality.ExactlyOne, GivenTypedProductionId),
      ChildDeclaration("mods", "mods", ChildCardinality.ExactlyOne, GivenModifiersProductionId)
    ),
    terminals = Vector(
      TerminalDeclaration(
        "contents",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = GivenPatternSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = GivenPatternAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(nativeGivenComposite("given", None, OutputRangeDeclaration.CompilerPosition)),
        Map("body" -> Some("given"), "mods" -> Some("given"))
      )
    ),
    outputRoleId = Some(PsiOutputRoleId.GivenPattern)
  )

  private val PatternGivenNamed = Scala3PsiProduction(
    id = GivenNamedProductionId,
    grammarRoleId = GrammarRoleId.PatternGiven,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Bind",
      Vector(
        CompilerFieldPattern(
          "name",
          CatalogValuePattern.AnyOf(
            Vector(
              CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary),
              CatalogValuePattern.ClassifiedName(NeutralNameClass.Wildcard)
            )
          )
        ),
        CompilerFieldPattern("body", CatalogValuePattern.NodePrefix("Typed")),
        CompilerFieldPattern("mods", CatalogValuePattern.Product("Modifiers", givenModifiersPattern))
      ),
      givenBindOccurrences(Set(ParserScannerTokenKind.AtSign))
    ),
    dispositions = Vector(
      FieldDisposition("name", FieldDispositionKind.SemanticOnly),
      FieldDisposition("body", FieldDispositionKind.Child),
      FieldDisposition("mods", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration("body", "body", ChildCardinality.ExactlyOne, GivenTypedProductionId),
      ChildDeclaration("mods", "mods", ChildCardinality.ExactlyOne, GivenModifiersProductionId)
    ),
    terminals = Vector(
      TerminalDeclaration(
        "binder",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = NamingPatternSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = NamingPatternAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "naming",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.NamingPattern,
            NamingPatternSurface,
            NamingPatternAccessors
          ),
          nativeGivenComposite(
            "given",
            Some("naming"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary
                .ChildStart("mods", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly),
              OutputBoundary.ProductionEnd()
            )
          )
        ),
        Map("body" -> Some("given"), "mods" -> Some("given"))
      )
    ),
    outputRoleId = None
  )

  private val PatternGivenTyped = Scala3PsiProduction(
    id = GivenTypedProductionId,
    grammarRoleId = GrammarRoleId.PatternGiven,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Typed",
      Vector(
        CompilerFieldPattern("expr", CatalogValuePattern.Node),
        CompilerFieldPattern("tpt", CatalogValuePattern.Node)
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchorThrough(
            InventoryKind.Node,
            "Bind",
            Vector(CatalogPathSegment.NamedField("body")),
            PatternNestingEdges,
            MatchCasesAncestor
          ),
          SourceClassification.Synthetic
        )
      ),
      Vector(DirectNodeFieldEvidence("expr", SourceClassification.Synthetic))
    ),
    dispositions = Vector(
      FieldDisposition("expr", FieldDispositionKind.SemanticOnly),
      FieldDisposition("tpt", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "tpt",
        "tpt",
        ChildCardinality.ExactlyOne,
        TypeIdentProductionId,
        Set(Scala3PsiPatternAppliedTypeProductions.AppliedTypeProductionId)
      )
    ),
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = GivenPatternSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(transparentTemplate("tpt")),
    outputRoleId = None
  )

  private val PatternGivenWildcard = Scala3PsiProduction(
    id = GivenWildcardProductionId,
    grammarRoleId = GrammarRoleId.PatternGiven,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.ExactName("_"))),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchorThrough(
            InventoryKind.Node,
            "Typed",
            Vector(CatalogPathSegment.NamedField("expr")),
            InventoryAncestor(
              InventoryKind.Node,
              "Bind",
              Vector(CatalogPathSegment.NamedField("body"))
            ) +: PatternNestingEdges,
            MatchCasesAncestor
          ),
          SourceClassification.Synthetic
        )
      )
    ),
    dispositions = Vector(FieldDisposition("name", FieldDispositionKind.SemanticOnly)),
    children = Vector.empty,
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = GivenPatternSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(transparentTemplate()),
    outputRoleId = None
  )

  private val PatternGivenModifiers = Scala3PsiProduction(
    id = GivenModifiersProductionId,
    grammarRoleId = GrammarRoleId.Modifiers,
    pattern = CompilerProductionPattern(
      InventoryKind.Product,
      "Modifiers",
      givenModifiersPattern,
      PatternOwnerAncestors.map(parent =>
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchorThroughWithParent(
            InventoryKind.Node,
            "Bind",
            Vector(CatalogPathSegment.NamedField("mods")),
            PatternNestingEdges,
            MatchCasesAncestor,
            parent
          ),
          SourceClassification.SourceReachable
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("flags", FieldDispositionKind.SemanticOnly),
      FieldDisposition("privateWithin", FieldDispositionKind.SemanticOnly),
      FieldDisposition("annotations", FieldDispositionKind.Synthetic),
      FieldDisposition("mods", FieldDispositionKind.Child)
    ),
    children = Vector(ChildDeclaration("mods", "mods", ChildCardinality.ExactlyOne, GivenModifierProductionId)),
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ModifierListSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(transparentTemplate("mods")),
    outputRoleId = None
  )

  private val PatternGivenModifier = Scala3PsiProduction(
    id = GivenModifierProductionId,
    grammarRoleId = GrammarRoleId.PatternGiven,
    pattern = CompilerProductionPattern(
      InventoryKind.Positioned,
      "Given",
      Vector.empty,
      PatternOwnerAncestors.map(parent =>
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchorThroughWithParent(
            InventoryKind.Node,
            "Bind",
            Vector(
              CatalogPathSegment.NamedField("mods"),
              CatalogPathSegment.NestedProduct("Modifiers"),
              CatalogPathSegment.NamedField("mods"),
              CatalogPathSegment.RepeatedElement
            ),
            PatternNestingEdges,
            MatchCasesAncestor,
            parent
          ),
          SourceClassification.SourceReachable
        )
      )
    ),
    dispositions = Vector.empty,
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "keyword",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = GivenPatternSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(transparentTemplate()),
    outputRoleId = None
  )

  val MatchExpressionSegment: Vector[Scala3PsiProduction] = Vector(
    Match,
    MatchCaseClause,
    MatchGuard,
    MatchCaseBodyBlock,
    PatternWildcard,
    PatternReference,
    PatternLiteral,
    PatternLiteralDecimal,
    PatternLiteralFloating,
    PatternLiteralString,
    PatternLiteralChar,
    PatternLiteralBoolean,
    PatternLiteralDouble,
    PatternLiteralFloat,
    PatternLiteralLong,
    PatternLiteralNull,
    PatternStableReference,
    PatternTyped,
    PatternTypeIdent,
    PatternNaming,
    PatternNamingSequence,
    PatternSequenceWildcard,
    PatternSequenceMarker,
    PatternTuple,
    PatternUnitTuple,
    PatternAlternative,
    PatternConstructor,
    PatternBindModifiers,
    PatternGiven,
    PatternGivenNamed,
    PatternGivenTyped,
    PatternGivenWildcard,
    PatternGivenModifiers,
    PatternGivenModifier
  )
