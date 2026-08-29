package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.ParserScannerTokenKind

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiMatchExpressionProductions:
  val CandidateProductionId = "match-expression-candidate"
  val FallbackProductionId  = "definition-payload-match"

  private val CaseClauseProductionId = "match-case-clause"
  private val GuardProductionId      = "match-guard"
  private val CaseBodyProductionId   = "match-case-body-block"
  private val WildcardProductionId   = "match-pattern-wildcard"
  private val ReferenceProductionId  = "match-pattern-reference"
  private val LiteralProductionId    = "match-pattern-literal"

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
    PsiOutputRoleId.LiteralPattern
  )

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
        "match-gap",
        TerminalIntervalSelector.ChildGap("selector", "cases"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "match-keyword",
        TerminalIntervalSelector.ChildGap("selector", "cases"),
        TerminalLeafTarget.Token(NativePsiElementBindings.MatchKeywordTokenSurface, Some("match")),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "right-brace",
        TerminalIntervalSelector.BalancedScannerTokenAfterChild(
          ParserScannerTokenKind.RightBrace,
          ParserScannerTokenKind.LeftBrace,
          ParserScannerTokenKind.RightBrace,
          "selector",
          ScannerTokenOccurrence.Last
        ),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "payload",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
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
        terminalIds = Some(Set("match-gap", "match-keyword", "right-brace"))
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
        Set(ReferenceProductionId, LiteralProductionId, "payload-descendant-ident")
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
    terminals = Vector.empty,
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
      caseDefChildOccurrences("pat")
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
      caseDefChildOccurrences("pat")
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
      caseDefChildOccurrences("pat")
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
    outputTemplate =
      Some(nativePatternTemplate(LiteralPatternSurface, LiteralPatternAccessors, PsiOutputRoleId.LiteralPattern)),
    outputRoleId = Some(PsiOutputRoleId.LiteralPattern)
  )

  val MatchExpressionSegment: Vector[Scala3PsiProduction] = Vector(
    Match,
    MatchCaseClause,
    MatchGuard,
    MatchCaseBodyBlock,
    PatternWildcard,
    PatternReference,
    PatternLiteral
  )
