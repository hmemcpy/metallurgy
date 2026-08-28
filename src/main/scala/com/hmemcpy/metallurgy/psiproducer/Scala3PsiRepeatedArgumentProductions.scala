package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.{ParserAttachmentValue, ParserScannerTokenKind}

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiRepeatedArgumentProductions:
  val CandidateProductionId = "repeated-term-application-candidate"
  val NativeRealizationId   = "repeated-term-application-native"

  private val FallbackRealization        = "repeated-term-application-payload"
  private val PayloadApplyRootProduction = "definition-payload-apply"

  private val DirectOwners = Vector("DefDef", "ValDef").flatMap: definition =>
    Vector("PackageDef" -> "stats", "Template" -> "preBody").map: (outer, field) =>
      InventoryAncestor(
        InventoryKind.Node,
        definition,
        Vector(CatalogPathSegment.NamedField("preRhs"))
      ) -> InventoryAncestor(
        InventoryKind.Node,
        outer,
        Vector(CatalogPathSegment.NamedField(field), CatalogPathSegment.RepeatedElement)
      )

  private def edge(owner: String, field: String, repeated: Boolean = false) =
    InventoryAncestor(
      InventoryKind.Node,
      owner,
      Vector(CatalogPathSegment.NamedField(field)) ++ Option.when(repeated)(CatalogPathSegment.RepeatedElement)
    )

  private def routes(path: Vector[InventoryAncestor], rootProductionId: String = CandidateProductionId) =
    DirectOwners.map: (rootOwner, outerOwner) =>
      OwnedRootRoute(rootProductionId, path, rootOwner, outerOwner)

  private val LocalCoverageOwner = Vector(
    InventoryAncestor(InventoryKind.Node, "ValDef", Vector(CatalogPathSegment.NamedField("preRhs"))) ->
      InventoryAncestor(
        InventoryKind.Node,
        "Block",
        Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement)
      )
  )

  private def coverageRoutes(path: Vector[InventoryAncestor], rootProductionId: String) =
    routes(path, rootProductionId) ++ LocalCoverageOwner.map: (rootOwner, outerOwner) =>
      OwnedRootRoute(rootProductionId, path, rootOwner, outerOwner)

  private def enabledOccurrences(path: Vector[InventoryAncestor]) = Vector(
    CompilerProductionContextPattern(
      ContextPattern.DescendantOfEnabledCandidateRoot(routes(path)),
      SourceClassification.SourceReachable
    )
  )

  private def ownedOccurrences(path: Vector[InventoryAncestor], rootProductionIds: Vector[String]) = Vector(
    CompilerProductionContextPattern(
      ContextPattern.DescendantOfOwnedRoot(
        rootProductionIds.flatMap(rootProductionId => coverageRoutes(path, rootProductionId))
      ),
      SourceClassification.SourceReachable
    )
  )

  private val DirectOccurrencePath = Vector(edge("Apply", "args", repeated = true))

  private def outputFreeProduction(
      id: String,
      prefix: String,
      fields: Vector[CompilerFieldPattern],
      occurrences: Vector[CompilerProductionContextPattern],
      extraTerminals: Vector[TerminalDeclaration] = Vector.empty,
      children: Vector[ChildDeclaration] = Vector.empty,
      childMounts: Map[String, Option[String]] = Map.empty,
      syntheticStructural: Boolean = false
  ): Scala3PsiProduction =
    Scala3PsiProduction(
      id,
      GrammarRoleId.OutputFreeExpression,
      CompilerProductionPattern(InventoryKind.Node, prefix, fields, occurrences),
      fields.map(field =>
        FieldDisposition(
          field.name,
          if children.exists(_.fieldName == field.name) then FieldDispositionKind.Child
          else FieldDispositionKind.TerminalOrLayout
        )
      ),
      children,
      Option
        .unless(syntheticStructural)(
          TerminalDeclaration(
            "output-free-text",
            TerminalIntervalSelector.WholeProduction,
            TerminalLeafTarget.Parent,
            OccurrenceCardinality.ExactlyOne,
            PsiOutputRoleId.SourceTerminal
          )
        )
        .toVector ++ extraTerminals,
      Vector(LayoutAlternative.None),
      RecoveryPolicy.Reject,
      ExpressionPayloadSurface,
      TargetRequirement.Compatible,
      Vector.empty,
      PersistenceObligations.NotApplicable,
      None,
      Some(LocalOutputCompositeTemplate(Vector.empty, children.map(_.roleId -> None).toMap ++ childMounts)),
      Vector.empty,
      None
    )

  val CandidateFunOccurrences = enabledOccurrences(Vector(edge("Apply", "fun")))

  val CandidateSelectionQualifierOccurrences =
    enabledOccurrences(Vector(edge("Select", "qualifier"), edge("Apply", "fun")))

  val CandidateTypedValueOccurrences =
    enabledOccurrences(Vector(edge("Typed", "expr"), edge("Apply", "args", repeated = true)))

  val CandidateTypedStarOccurrences =
    enabledOccurrences(Vector(edge("Typed", "tpt"), edge("Apply", "args", repeated = true)))

  val CandidateTypedValueFallbackOccurrences =
    ownedOccurrences(
      Vector(edge("Typed", "expr"), edge("Apply", "args", repeated = true)),
      Vector(CandidateProductionId)
    )

  val CandidateTypedStarFallbackOccurrences =
    ownedOccurrences(
      Vector(edge("Typed", "tpt"), edge("Apply", "args", repeated = true)),
      Vector(CandidateProductionId)
    )

  val CandidateSelectedOperandQualifierFallbackOccurrences = Vector(
    CompilerProductionContextPattern(
      ContextPattern.DescendantOfOwnedRoot(
        Vector(
          OwnedRootRoute(
            "payload-descendant-select",
            Vector(edge("Select", "qualifier")),
            edge("Typed", "expr"),
            edge("Apply", "args", repeated = true)
          )
        )
      ),
      SourceClassification.SourceReachable
    )
  )

  private val LeadingArgumentPattern = CatalogValuePattern.AnyOf(
    Vector(
      CatalogValuePattern.NodePrefix("Ident"),
      CatalogValuePattern.NodePrefix("Number"),
      CatalogValuePattern.NodePrefix("Literal")
    )
  )

  private val ArgumentProductions = Vector(
    "atomic-term-ident",
    "atomic-literal-integer",
    "atomic-literal-string",
    "term-repeated-argument"
  )

  private val ArgumentRoles = Set(
    PsiOutputRoleId.TermReference,
    PsiOutputRoleId.IntegerExpression,
    PsiOutputRoleId.StringExpression,
    PsiOutputRoleId.TypedExpression
  )

  private val CalleeRoles = Set(PsiOutputRoleId.TermReference, PsiOutputRoleId.SelectionExpression)

  private val RepeatedArgument = Scala3PsiProduction(
    id = "term-repeated-argument",
    grammarRoleId = GrammarRoleId.RepeatedTermArgument,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Typed",
      Vector(
        CompilerFieldPattern("expr", CatalogValuePattern.Node),
        CompilerFieldPattern("tpt", CatalogValuePattern.Node)
      ),
      enabledOccurrences(DirectOccurrencePath).map: occurrence =>
        occurrence.copy(sourceClassification = SourceClassification.Synthetic)
    ),
    dispositions = Vector(
      FieldDisposition("expr", FieldDispositionKind.Child),
      FieldDisposition("tpt", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "value",
        "expr",
        ChildCardinality.ExactlyOne,
        "atomic-term-ident",
        Set(
          "atomic-literal-integer",
          "atomic-literal-string",
          "payload-descendant-ident",
          "payload-descendant-number",
          "payload-descendant-invoked-literal",
          "payload-descendant-select",
          "payload-descendant-apply",
          "payload-descendant-tuple",
          "payload-descendant-block",
          "payload-descendant-infix",
          "repeated-term-output-free-string"
        )
      ),
      ChildDeclaration(
        "type-evidence",
        "tpt",
        ChildCardinality.ExactlyOne,
        "repeated-term-star-evidence"
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "repeated-argument-gap",
        TerminalIntervalSelector.SourceDerivedChildToScannerTokenGap(
          "tpt",
          "value",
          ChildOccurrenceSelector.First,
          ParserScannerTokenKind.Identifier,
          ScannerTokenOccurrence.First
        ),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = TypedExpressionSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = TypedExpressionAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "typed-expression",
            None,
            OutputRangeDeclaration.CompilerPositionWithPolicy(PositionProvenancePolicy.PositionedIncludingSynthetic),
            PsiOutputRoleId.TypedExpression,
            TypedExpressionSurface,
            TypedExpressionAccessors
          ),
          outputComposite(
            "sequence-argument",
            Some("typed-expression"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.NextScannerTokenStartAfterChild(
                "value",
                ChildOccurrenceSelector.First,
                ParserScannerTokenKind.Identifier,
                PositionProvenancePolicy.PositionedIncludingSynthetic
              ),
              OutputBoundary.ProductionEnd(PositionProvenancePolicy.PositionedIncludingSynthetic)
            ),
            PsiOutputRoleId.RepeatedStar,
            SequenceArgumentSurface,
            SequenceArgumentAccessors
          )
        ),
        Map("value" -> Some("typed-expression"), "type-evidence" -> None)
      )
    ),
    outputRoleId = None
  )

  private val outputFreeTypedChildren = Vector(
    ChildDeclaration(
      "value",
      "expr",
      ChildCardinality.ExactlyOne,
      "atomic-term-ident",
      Set(
        "atomic-literal-integer",
        "atomic-literal-string",
        "payload-descendant-ident",
        "payload-descendant-number",
        "payload-descendant-invoked-literal",
        "payload-descendant-select",
        "payload-descendant-apply",
        "payload-descendant-tuple",
        "payload-descendant-block",
        "payload-descendant-infix",
        "payload-output-free-ident",
        "repeated-term-output-free-string"
      )
    ),
    ChildDeclaration(
      "type-evidence",
      "tpt",
      ChildCardinality.ExactlyOne,
      "repeated-term-star-evidence"
    )
  )

  private val outputFreeTyped = outputFreeProduction(
    id = "repeated-term-output-free-typed",
    prefix = "Typed",
    fields = Vector(
      CompilerFieldPattern("expr", CatalogValuePattern.Node),
      CompilerFieldPattern("tpt", CatalogValuePattern.Node)
    ),
    occurrences = ownedOccurrences(DirectOccurrencePath, Vector(CandidateProductionId, PayloadApplyRootProduction)),
    children = outputFreeTypedChildren
  )

  private val outputFreeTypedSynthetic = outputFreeProduction(
    id = "repeated-term-output-free-typed-synthetic",
    prefix = "Typed",
    fields = Vector(
      CompilerFieldPattern("expr", CatalogValuePattern.Node),
      CompilerFieldPattern("tpt", CatalogValuePattern.Node)
    ),
    occurrences = Vector("DefDef", "ValDef").flatMap: anchor =>
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchor(
            InventoryKind.Node,
            "Apply",
            Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
            InventoryAncestor(
              InventoryKind.Node,
              anchor,
              Vector(CatalogPathSegment.NamedField("preRhs"))
            )
          ),
          SourceClassification.Synthetic
        )
      ),
    children = outputFreeTypedChildren,
    childMounts = Map("value" -> None, "type-evidence" -> None),
    syntheticStructural = true,
    extraTerminals = Vector(
      TerminalDeclaration(
        "repeated-wrapper-gap",
        TerminalIntervalSelector.SourceDerivedChildToScannerTokenGap(
          "tpt",
          "value",
          ChildOccurrenceSelector.First,
          ParserScannerTokenKind.Identifier,
          ScannerTokenOccurrence.First
        ),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      )
    )
  )

  private val starEvidence = outputFreeProduction(
    id = "repeated-term-star-evidence",
    prefix = "Ident",
    fields = Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
    occurrences = enabledOccurrences(Vector(edge("Typed", "tpt"), edge("Apply", "args", repeated = true))) ++
      ownedOccurrences(
        Vector(edge("Typed", "tpt"), edge("Apply", "args", repeated = true)),
        Vector(CandidateProductionId)
      ) ++
      ownedOccurrences(
        Vector(edge("Typed", "tpt"), edge("Apply", "args", repeated = true)),
        Vector(PayloadApplyRootProduction)
      ),
    extraTerminals = Vector(
      TerminalDeclaration(
        "repeated-star-token",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Token(NativePsiElementBindings.RepeatedParameterStarTokenSurface, Some("*")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      )
    )
  )

  private val outputFreeString = outputFreeProduction(
    id = "repeated-term-output-free-string",
    prefix = "Literal",
    fields = Vector(
      CompilerFieldPattern(
        "const",
        CatalogValuePattern.Product("", Vector(CompilerFieldPattern("", CatalogValuePattern.Scalar("Text"))))
      )
    ),
    occurrences = ownedOccurrences(
      Vector(edge("Typed", "expr"), edge("Apply", "args", repeated = true)),
      Vector(CandidateProductionId)
    ) ++
      ownedOccurrences(
        Vector(edge("Typed", "expr"), edge("Apply", "args", repeated = true)),
        Vector(PayloadApplyRootProduction)
      )
  )

  private val Application = Scala3PsiProduction(
    id = CandidateProductionId,
    grammarRoleId = GrammarRoleId.RepeatedTermArgument,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Apply",
      Vector(
        CompilerFieldPattern("fun", CatalogValuePattern.NodeExceptPrefix("TypeApply")),
        CompilerFieldPattern(
          "args",
          CatalogValuePattern
            .NonEmptyRepeatedEndingWith(LeadingArgumentPattern, CatalogValuePattern.NodePrefix("Typed"))
        )
      ),
      DirectOwners.map: (rootOwner, outerOwner) =>
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestor(
            rootOwner.ownerKind,
            rootOwner.ownerPrefix,
            rootOwner.path,
            outerOwner
          ),
          SourceClassification.SourceReachable
        )
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
        "atomic-term-ident",
        Set("selection-expression")
      ),
      ChildDeclaration(
        "arguments",
        "args",
        ChildCardinality.Repeated(1, None),
        ArgumentProductions.head,
        ArgumentProductions.tail.toSet + "repeated-term-output-free-typed" + "repeated-term-output-free-typed-synthetic"
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "left-parenthesis",
        TerminalIntervalSelector.BalancedScannerTokenAfterChild(
          ParserScannerTokenKind.LeftParenthesis,
          ParserScannerTokenKind.LeftParenthesis,
          ParserScannerTokenKind.RightParenthesis,
          "callee",
          ScannerTokenOccurrence.First
        ),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeLeftParenthesisTokenSurface),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "right-parenthesis",
        TerminalIntervalSelector.BalancedScannerTokenAfterChild(
          ParserScannerTokenKind.RightParenthesis,
          ParserScannerTokenKind.LeftParenthesis,
          ParserScannerTokenKind.RightParenthesis,
          "callee",
          ScannerTokenOccurrence.Last
        ),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeRightParenthesisTokenSurface),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
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
        NativeRealizationId,
        Vector(
          ChildOutcomeCondition(
            "callee",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.AnyOf(
              Vector(
                ChildOutcomeExpectation.Production("atomic-term-ident"),
                ChildOutcomeExpectation.Realization("native-selection-qualifier-ident")
              )
            )
          )
        ),
        LocalOutputCompositeTemplate(
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
        ),
        evidenceConditions = Vector(
          EvidenceCondition.RootAttachment(
            AttachmentEvidence("KindOfApply", ParserAttachmentValue.Product("Using")),
            present = false
          ),
          EvidenceCondition.TrailingRepeatedNodeChild(
            repeatedFieldName = "args",
            nodePrefix = "Typed",
            nodeClassification = SourceClassification.Synthetic,
            childField = "tpt",
            childPrefix = "Ident",
            childClassification = SourceClassification.SourceReachable,
            childNameField = "name",
            childNameExpected = "_*",
            childSourceText = "*"
          )
        ),
        requiredChildRoots = Vector(
          RequiredChildRootOutcome(
            "callee",
            ChildRootOutcome.One(ChildOutcomeExpectation.OutputRoles(CalleeRoles))
          ),
          RequiredChildRootOutcome(
            "arguments",
            ChildRootOutcome.All(ChildOutcomeExpectation.OutputRoles(ArgumentRoles))
          )
        ),
        terminalIds = Some(
          Set("left-parenthesis", "right-parenthesis", "commas", "separator-evidence")
        )
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
          Map("callee" -> None, "arguments" -> None)
        ),
        childClosureAbsorptions = Vector(
          ChildClosureAbsorption("callee", ChildRootOutcome.AnyReviewed),
          ChildClosureAbsorption("arguments", ChildRootOutcome.AnyReviewed)
        ),
        terminalIds = Some(Set("payload"))
      )
    ),
    outputRoleId = None,
    realizationChoice = Some(
      RealizationChoice(
        Vector(NativeRealizationId),
        FallbackRealization,
        RealizationChoicePolicy.AtomicWholePlan
      )
    )
  )

  val RepeatedArgumentSegment: Vector[Scala3PsiProduction] = Vector(
    Application,
    RepeatedArgument,
    outputFreeTyped,
    outputFreeTypedSynthetic,
    starEvidence,
    outputFreeString
  )
