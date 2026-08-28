package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.{ParserAttachmentValue, ParserScannerTokenKind}

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiNamedArgumentProductions:
  val CandidateProductionId = "named-term-application-candidate"
  val NativeRealizationId   = "named-term-application-native"

  private val FallbackRealization = "named-term-application-payload"

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

  private def routes(path: Vector[InventoryAncestor]) = DirectOwners.map: (rootOwner, outerOwner) =>
    OwnedRootRoute(CandidateProductionId, path, rootOwner, outerOwner)

  private def enabledOccurrences(path: Vector[InventoryAncestor]) = Vector(
    CompilerProductionContextPattern(
      ContextPattern.DescendantOfEnabledCandidateRoot(routes(path)),
      SourceClassification.SourceReachable
    )
  )

  val CandidateFunOccurrences = enabledOccurrences(Vector(edge("Apply", "fun")))

  val CandidateSelectionQualifierOccurrences =
    enabledOccurrences(Vector(edge("Select", "qualifier"), edge("Apply", "fun")))

  val CandidateArgumentOccurrences = enabledOccurrences(Vector(edge("Apply", "args", repeated = true)))

  val CandidateArgumentFallbackOccurrences = Vector(
    CompilerProductionContextPattern(
      ContextPattern.DescendantOfOwnedRoot(routes(Vector(edge("Apply", "args", repeated = true)))),
      SourceClassification.SourceReachable
    )
  )

  val CandidateNamedValueOccurrences =
    enabledOccurrences(Vector(edge("NamedArg", "arg"), edge("Apply", "args", repeated = true)))

  val CandidateNamedValueFallbackOccurrences = Vector(
    CompilerProductionContextPattern(
      ContextPattern.DescendantOfOwnedRoot(
        routes(Vector(edge("NamedArg", "arg"), edge("Apply", "args", repeated = true)))
      ),
      SourceClassification.SourceReachable
    )
  )

  private val NamedArgument = Scala3PsiProduction(
    id = "term-named-argument",
    grammarRoleId = GrammarRoleId.NamedArgument,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "NamedArg",
      Vector(
        CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)),
        CompilerFieldPattern("arg", CatalogValuePattern.Node)
      ),
      CandidateArgumentOccurrences
    ),
    dispositions = Vector(
      FieldDisposition("name", FieldDispositionKind.TerminalOrLayout),
      FieldDisposition("arg", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "value",
        "arg",
        ChildCardinality.ExactlyOne,
        "atomic-term-ident",
        Set("atomic-literal-integer", "atomic-literal-string")
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "named-argument-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "named-argument-assignment",
        TerminalIntervalSelector.BeforeChild("value"),
        TerminalLeafTarget.Token(NativePsiElementBindings.AssignmentTokenSurface, Some("=")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = NamedArgumentSurface,
    targetRequirement = TargetRequirement.Compatible,
    accessors = NamedArgumentAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "assignment",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.NamedArgument,
            NamedArgumentSurface,
            NamedArgumentAccessors,
            TargetRequirement.Compatible
          ),
          outputComposite(
            "name",
            Some("assignment"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.ProductionFirstIdentifierStart,
              OutputBoundary.ProductionFirstIdentifierEnd
            ),
            PsiOutputRoleId.TermReference,
            ReferenceExpressionSurface,
            TermReferenceAccessors
          )
        ),
        Map("value" -> Some("assignment"))
      )
    ),
    outputRoleId = None
  )

  private val ArgumentRoles = Set(
    PsiOutputRoleId.TermReference,
    PsiOutputRoleId.IntegerExpression,
    PsiOutputRoleId.StringExpression,
    PsiOutputRoleId.NamedArgument
  )

  private val Application = Scala3PsiProduction(
    id = CandidateProductionId,
    grammarRoleId = GrammarRoleId.OrdinaryApplication,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Apply",
      Vector(
        CompilerFieldPattern("fun", CatalogValuePattern.NodeExceptPrefix("TypeApply")),
        CompilerFieldPattern(
          "args",
          CatalogValuePattern.NonEmptyRepeated(
            CatalogValuePattern.AnyOf(
              Vector(
                CatalogValuePattern.NodePrefix("Ident"),
                CatalogValuePattern.NodePrefix("Number"),
                CatalogValuePattern.NodePrefix("Literal"),
                CatalogValuePattern.NodePrefix("NamedArg")
              )
            )
          )
        )
      ),
      DirectOwners.map: (owner, outer) =>
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestor(owner.ownerKind, owner.ownerPrefix, owner.path, outer),
          SourceClassification.SourceReachable,
          ScannerEvidencePattern(required = Set(ParserScannerTokenKind.Equals))
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
        "atomic-term-ident",
        Set(
          "atomic-literal-integer",
          "atomic-literal-string",
          NamedArgument.id,
          "payload-descendant-named-arg"
        )
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
        "argument-prefix-evidence",
        TerminalIntervalSelector.BalancedPrefixBeforeFirstChild(
          ClosedSourceLexicalKind.LeftParenthesis,
          "callee",
          "arguments"
        ),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "argument-suffix-evidence",
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
          EvidenceCondition.RepeatedNodeFieldDistinct("args", "NamedArg", "name"),
          EvidenceCondition.RepeatedNodesTrailingPrefix("args", "NamedArg")
        ),
        requiredChildRoots = Vector(
          RequiredChildRootOutcome(
            "callee",
            ChildRootOutcome.One(
              ChildOutcomeExpectation.OutputRoles(
                Set(PsiOutputRoleId.TermReference, PsiOutputRoleId.SelectionExpression)
              )
            )
          ),
          RequiredChildRootOutcome(
            "arguments",
            ChildRootOutcome.All(ChildOutcomeExpectation.OutputRoles(ArgumentRoles))
          )
        ),
        terminalIds = Some(
          Set(
            "left-parenthesis",
            "right-parenthesis",
            "commas",
            "separator-evidence",
            "argument-prefix-evidence",
            "argument-suffix-evidence"
          )
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

  val NamedArgumentSegment: Vector[Scala3PsiProduction] = Vector(Application, NamedArgument)
