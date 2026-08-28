package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.{ParserAttachmentValue, ParserScannerTokenKind}

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiApplicationExpressionProductions:
  val CandidateProductionId = "ordinary-application-candidate"
  val FallbackProductionId  = "definition-payload-apply"

  private val OrdinaryCandidateRealization = "ordinary-application-native"
  private val UsingCandidateRealization    = "explicit-using-application-native"
  private val FallbackRealization          = "ordinary-application-payload"

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
        SourceClassification.SourceReachable,
        ScannerEvidencePattern(forbidden = Set(ParserScannerTokenKind.Equals))
      )

  private val TraversedApplications = Vector(
    InventoryAncestor(InventoryKind.Node, "Apply", Vector(CatalogPathSegment.NamedField("fun"))),
    InventoryAncestor(
      InventoryKind.Node,
      "Apply",
      Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
    )
  )

  val ChildOccurrences = Vector("DefDef", "ValDef").flatMap: definition =>
    Vector("PackageDef" -> "stats", "Template" -> "preBody").flatMap: (outer, field) =>
      val anchor = InventoryAncestor(
        InventoryKind.Node,
        definition,
        Vector(CatalogPathSegment.NamedField("preRhs"))
      )
      val parent = InventoryAncestor(
        InventoryKind.Node,
        outer,
        Vector(CatalogPathSegment.NamedField(field), CatalogPathSegment.RepeatedElement)
      )
      TraversedApplications.map: application =>
        CompilerProductionContextPattern(
          ContextPattern.ParentWithoutNodeFieldPrefixUnderAnchorThroughWithParent(
            application.ownerKind,
            application.ownerPrefix,
            application.path,
            "fun",
            "TypeApply",
            TraversedApplications,
            anchor,
            parent
          ),
          SourceClassification.SourceReachable
        )

  def descendantOccurrences(
      owner: String,
      path: Vector[CatalogPathSegment],
      classification: SourceClassification,
      through: Vector[InventoryAncestor] = Vector.empty
  ): Vector[CompilerProductionContextPattern] = Vector("DefDef", "ValDef").flatMap: definition =>
    Vector("PackageDef" -> "stats", "Template" -> "preBody").map: (outer, field) =>
      val anchor = InventoryAncestor(
        InventoryKind.Node,
        definition,
        Vector(CatalogPathSegment.NamedField("preRhs"))
      )
      CompilerProductionContextPattern(
        ContextPattern.ParentWithoutNodeFieldPrefixUnderAnchorThroughWithParent(
          InventoryKind.Node,
          owner,
          path,
          "fun",
          "TypeApply",
          through ++ TraversedApplications,
          anchor,
          InventoryAncestor(
            InventoryKind.Node,
            outer,
            Vector(CatalogPathSegment.NamedField(field), CatalogPathSegment.RepeatedElement)
          )
        ),
        classification
      )

  private val ReviewedFallbackProductions = Vector(
    "definition-payload-number",
    "definition-payload-ident",
    "definition-payload-apply",
    "definition-payload-select",
    "definition-payload-tuple",
    "definition-payload-block",
    "definition-payload-infix",
    "definition-payload-type-apply-positional",
    "definition-payload-type-apply-named",
    "definition-payload-applied-call",
    "payload-descendant-number",
    "payload-descendant-ident",
    "payload-descendant-apply",
    "payload-descendant-select",
    "payload-descendant-tuple",
    "payload-descendant-block",
    "payload-descendant-infix",
    "payload-descendant-type-apply-positional",
    "payload-descendant-type-apply-named",
    "type-application-output-free-ident-argument",
    "payload-output-free-ident",
    "payload-output-free-select",
    "payload-qualifier-ident",
    "payload-qualifier-this",
    "payload-qualifier-super",
    "payload-descendant-named-arg",
    "type-application-output-free-number",
    "type-application-output-free-literal"
  )

  private val CalleeProductions = Vector(
    "atomic-term-ident",
    "selection-expression",
    CandidateProductionId
  ) ++ ReviewedFallbackProductions

  private val ArgumentProductions = Vector(
    "atomic-term-ident",
    "selection-expression",
    "atomic-literal-integer",
    "atomic-literal-long",
    "atomic-literal-float",
    "atomic-literal-double",
    "atomic-literal-boolean",
    "atomic-literal-char",
    "atomic-literal-string",
    "atomic-literal-null",
    "atomic-this-unqualified",
    "atomic-this-qualified",
    CandidateProductionId
  ) ++ ReviewedFallbackProductions

  private val CalleeRoles = Set(
    PsiOutputRoleId.TermReference,
    PsiOutputRoleId.SelectionExpression,
    PsiOutputRoleId.MethodCall
  )

  private val ArgumentRoles = CalleeRoles ++ Set(
    PsiOutputRoleId.IntegerExpression,
    PsiOutputRoleId.LongExpression,
    PsiOutputRoleId.FloatExpression,
    PsiOutputRoleId.DoubleExpression,
    PsiOutputRoleId.BooleanExpression,
    PsiOutputRoleId.CharExpression,
    PsiOutputRoleId.StringExpression,
    PsiOutputRoleId.NullExpression,
    PsiOutputRoleId.ThisReference
  )

  private val NativeTerminalIds = Set("left-parenthesis", "right-parenthesis", "commas", "separator-evidence")
  private val UsingTerminalIds  =
    NativeTerminalIds ++ Set("using-keyword", "using-prefix-evidence", "using-suffix-evidence")

  private def nativeApplicationTemplate = LocalOutputCompositeTemplate(
    Vector(
      outputComposite(
        "method-call",
        None,
        OutputRangeDeclaration.CompilerPosition,
        PsiOutputRoleId.MethodCall,
        MethodCallSurface,
        MethodCallAccessors
      ),
      outputComposite(
        "arguments",
        Some("method-call"),
        OutputRangeDeclaration.BoundaryDerived(
          OutputBoundary.NextScannerTokenStartAfterChild(
            "callee",
            ChildOccurrenceSelector.First,
            ParserScannerTokenKind.LeftParenthesis,
            PositionProvenancePolicy.SourceDerivedOnly
          ),
          OutputBoundary.ProductionEnd()
        ),
        PsiOutputRoleId.ArgumentExpressions,
        ArgumentExpressionsSurface,
        ArgumentExpressionsAccessors
      )
    ),
    Map("callee" -> Some("method-call"), "arguments" -> Some("arguments"))
  )

  private def nativeChildRoots = Vector(
    RequiredChildRootOutcome("callee", ChildRootOutcome.One(ChildOutcomeExpectation.OutputRoles(CalleeRoles))),
    RequiredChildRootOutcome(
      "arguments",
      ChildRootOutcome.All(ChildOutcomeExpectation.OutputRoles(ArgumentRoles))
    )
  )

  private def balancedToken(
      id: String,
      kind: ParserScannerTokenKind,
      occurrence: ScannerTokenOccurrence,
      cardinality: OccurrenceCardinality,
      surface: String
  ) = TerminalDeclaration(
    id,
    TerminalIntervalSelector.BalancedScannerTokenAfterChild(
      kind,
      ParserScannerTokenKind.LeftParenthesis,
      ParserScannerTokenKind.RightParenthesis,
      "callee",
      occurrence
    ),
    TerminalLeafTarget.Token(surface),
    cardinality,
    PsiOutputRoleId.SourceTerminal
  )

  private val Application = Scala3PsiProduction(
    id = CandidateProductionId,
    grammarRoleId = GrammarRoleId.OrdinaryApplication,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Apply",
      Vector(
        CompilerFieldPattern("fun", CatalogValuePattern.NodeExceptPrefix("TypeApply")),
        CompilerFieldPattern("args", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
      ),
      DirectOccurrences ++ ChildOccurrences
    ),
    dispositions = Vector(
      FieldDisposition("fun", FieldDispositionKind.Child),
      FieldDisposition("args", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "callee",
        "fun",
        ChildCardinality.ExactlyOne,
        CalleeProductions.head,
        CalleeProductions.tail.toSet
      ),
      ChildDeclaration(
        "arguments",
        "args",
        ChildCardinality.Repeated(0, None),
        ArgumentProductions.head,
        ArgumentProductions.tail.toSet
      )
    ),
    terminals = Vector(
      balancedToken(
        "left-parenthesis",
        ParserScannerTokenKind.LeftParenthesis,
        ScannerTokenOccurrence.First,
        OccurrenceCardinality.ExactlyOne,
        NativePsiElementBindings.TypeLeftParenthesisTokenSurface
      ),
      balancedToken(
        "right-parenthesis",
        ParserScannerTokenKind.RightParenthesis,
        ScannerTokenOccurrence.Last,
        OccurrenceCardinality.ExactlyOne,
        NativePsiElementBindings.TypeRightParenthesisTokenSurface
      ),
      TerminalDeclaration(
        "commas",
        TerminalIntervalSelector.ChildSeparators("arguments"),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeCommaTokenSurface, Some(",")),
        OccurrenceCardinality.Repeated(0, None),
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "separator-evidence",
        TerminalIntervalSelector.ChildSeparators("arguments"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.Repeated(0, None),
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "using-keyword",
        TerminalIntervalSelector.BalancedKeywordBeforeFirstChild(
          ClosedSourceLexicalKind.LeftParenthesis,
          ClosedSourceLexicalKind.RightParenthesis,
          "callee",
          "arguments"
        ),
        TerminalLeafTarget.Token(NativePsiElementBindings.UsingKeywordTokenSurface),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "using-prefix-evidence",
        TerminalIntervalSelector.BalancedPrefixBeforeFirstChild(
          ClosedSourceLexicalKind.LeftParenthesis,
          "callee",
          "arguments"
        ),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "using-suffix-evidence",
        TerminalIntervalSelector.BalancedSuffixAfterLastChild(
          ClosedSourceLexicalKind.LeftParenthesis,
          ClosedSourceLexicalKind.RightParenthesis,
          "callee",
          "arguments"
        ),
        TerminalLeafTarget.Trivia,
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
    targetSurfaceId = MethodCallSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = MethodCallAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRealizations = Vector(
      OutputRealization(
        OrdinaryCandidateRealization,
        Vector.empty,
        nativeApplicationTemplate,
        evidenceConditions = Vector(
          EvidenceCondition.RootAttachment(
            AttachmentEvidence("KindOfApply", ParserAttachmentValue.Product("Using")),
            present = false
          )
        ),
        requiredChildRoots = nativeChildRoots,
        terminalIds = Some(NativeTerminalIds)
      ),
      OutputRealization(
        UsingCandidateRealization,
        Vector.empty,
        nativeApplicationTemplate,
        evidenceConditions = Vector(
          EvidenceCondition.RootAttachment(
            AttachmentEvidence("KindOfApply", ParserAttachmentValue.Product("Using")),
            present = true
          )
        ),
        requiredChildRoots = nativeChildRoots,
        terminalIds = Some(UsingTerminalIds)
      ),
      OutputRealization(
        FallbackRealization,
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
          Map("callee" -> Some("payload"), "arguments" -> None)
        ),
        childClosureAbsorptions = Vector(
          ChildClosureAbsorption(
            "callee",
            ChildRootOutcome.AnyReviewed,
            Set(PsiOutputRoleId.TypeArguments, PsiOutputRoleId.NamedTypeArguments)
          ),
          ChildClosureAbsorption("arguments", ChildRootOutcome.AnyReviewed)
        ),
        terminalIds = Some(Set("payload"))
      )
    ),
    outputRoleId = None,
    realizationChoice = Some(
      RealizationChoice(Vector(OrdinaryCandidateRealization, UsingCandidateRealization), FallbackRealization)
    )
  )

  val ApplicationExpressionSegment: Vector[Scala3PsiProduction] = Vector(Application)
