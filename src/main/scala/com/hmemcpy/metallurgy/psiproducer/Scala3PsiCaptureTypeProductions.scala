package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

import Scala3PsiProductionSupport.*
import Scala3PsiTupleFunctionTypeProductions.{
  byNameParameterType,
  functionTypeOccurrences,
  ordinaryFunctionTypeProduction,
  pureFunctionArrowKinds
}

private[psiproducer] object Scala3PsiCaptureTypeProductions:

  private val captureNullaryFunctionTypeProduction = Scala3PsiProduction(
    id = "capture-nullary-function-type",
    grammarRoleId = GrammarRoleId.FunctionType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Function",
      Vector(
        CompilerFieldPattern("args", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)),
        CompilerFieldPattern("body", CatalogValuePattern.Node)
      ),
      functionTypeOccurrences(pureFunctionArrowKinds),
      Vector(DirectNodeFieldEvidence("body", SourceClassification.Synthetic))
    ),
    dispositions = Vector(
      FieldDisposition("args", FieldDispositionKind.TerminalOrLayout),
      FieldDisposition("body", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration("result", "body", ChildCardinality.ExactlyOne, "capture-function-result")
    ),
    terminals = Vector(
      TerminalDeclaration(
        "function-prefix",
        TerminalIntervalSelector.BeforeChildOutputs("result"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "capture-result-separator",
        TerminalIntervalSelector.ChildOutputSeparators("result"),
        TerminalLeafTarget.Trivia,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "pure-function-arrow",
        TerminalIntervalSelector.CompilerScannerTokenBeforeChildOutputs(
          ParserScannerTokenKind.PureFunctionArrow,
          "result"
        ),
        TerminalLeafTarget.Token(NativePsiElementBindings.PureFunctionArrowTokenSurface, None),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = FunctionTypeSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = FunctionTypeAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "function",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.FunctionType,
            FunctionTypeSurface,
            FunctionTypeAccessors
          ),
          outputComposite(
            "parameters",
            Some("function"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.ProductionStart(),
              OutputBoundary.Advance(OutputBoundary.ProductionStart(), 2)
            ),
            PsiOutputRoleId.ParenthesizedType,
            ParenthesizedTypeSurface,
            ParenthesizedTypeAccessors
          )
        ),
        Map("result" -> Some("function"))
      )
    ),
    outputRoleId = None
  )

  private lazy val captureFunctionTypeProduction = ordinaryFunctionTypeProduction.copy(
    id = "capture-function-type",
    pattern = ordinaryFunctionTypeProduction.pattern.copy(
      fields = Vector(
        CompilerFieldPattern(
          "args",
          CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodeExceptPrefix("ValDef"))
        ),
        CompilerFieldPattern("body", CatalogValuePattern.Node)
      ),
      occurrences = functionTypeOccurrences(pureFunctionArrowKinds),
      directNodeEvidence = Vector(DirectNodeFieldEvidence("body", SourceClassification.Synthetic))
    ),
    children = Vector(
      compoundChild("arguments", "args", ChildCardinality.Repeated(1, None)),
      ChildDeclaration("result", "body", ChildCardinality.ExactlyOne, "capture-function-result")
    ),
    terminals = (ordinaryFunctionTypeProduction.terminals
      .filterNot(_.target == TerminalLeafTarget.Parent)
      .map: terminal =>
        val selector = terminal.selector match
          case TerminalIntervalSelector.BeforeChild("arguments")        =>
            TerminalIntervalSelector.BeforeChildOutputs("arguments")
          case TerminalIntervalSelector.ChildSeparators("arguments")    =>
            TerminalIntervalSelector.ChildOutputSeparators("arguments")
          case TerminalIntervalSelector.ChildGap("arguments", "result") =>
            TerminalIntervalSelector.ChildOutputGap("arguments", "result")
          case TerminalIntervalSelector.CompilerScannerTokenInChildGap(
                ParserScannerTokenKind.FunctionArrow,
                "arguments",
                "result"
              ) =>
            TerminalIntervalSelector.CompilerScannerTokenInChildOutputGap(
              ParserScannerTokenKind.PureFunctionArrow,
              "arguments",
              "result"
            )
          case TerminalIntervalSelector.CompilerScannerTokenInChildGap(
                ParserScannerTokenKind.ContextFunctionArrow,
                "arguments",
                "result"
              ) =>
            TerminalIntervalSelector.CompilerScannerTokenInChildOutputGap(
              ParserScannerTokenKind.ContextPureFunctionArrow,
              "arguments",
              "result"
            )
          case other                                                    => other
        val target   = terminal.id match
          case "ordinary-arrow" =>
            TerminalLeafTarget.Token(NativePsiElementBindings.PureFunctionArrowTokenSurface, None)
          case "context-arrow"  =>
            TerminalLeafTarget.Token(NativePsiElementBindings.ContextPureFunctionArrowTokenSurface, None)
          case _                => terminal.target
        terminal.copy(selector = selector, target = target)
    ) ++ Vector(
      TerminalDeclaration(
        "capture-function-evidence",
        TerminalIntervalSelector.ChildOutputGap("arguments", "result"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "capture-result-separator",
        TerminalIntervalSelector.ChildOutputSeparators("result"),
        TerminalLeafTarget.Trivia,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    )
  )

  private lazy val pureFunctionTypeProduction = ordinaryFunctionTypeProduction.copy(
    id = "pure-function-type",
    pattern =
      ordinaryFunctionTypeProduction.pattern.copy(occurrences = functionTypeOccurrences(pureFunctionArrowKinds)),
    terminals = ordinaryFunctionTypeProduction.terminals.map: terminal =>
      terminal.id match
        case "ordinary-arrow" =>
          terminal.copy(
            selector = TerminalIntervalSelector.CompilerScannerTokenInChildGap(
              ParserScannerTokenKind.PureFunctionArrow,
              "arguments",
              "result"
            ),
            target = TerminalLeafTarget.Token(NativePsiElementBindings.PureFunctionArrowTokenSurface, None)
          )
        case "context-arrow"  =>
          terminal.copy(
            selector = TerminalIntervalSelector.CompilerScannerTokenInChildGap(
              ParserScannerTokenKind.ContextPureFunctionArrow,
              "arguments",
              "result"
            ),
            target = TerminalLeafTarget.Token(NativePsiElementBindings.ContextPureFunctionArrowTokenSurface, None)
          )
        case _                => terminal
  )

  private lazy val pureNullaryFunctionTypeProduction = captureNullaryFunctionTypeProduction.copy(
    id = "pure-nullary-function-type",
    pattern = captureNullaryFunctionTypeProduction.pattern.copy(
      directNodeEvidence = Vector(DirectNodeFieldEvidence("body", SourceClassification.SourceReachable))
    ),
    children = Vector(compoundChild("result", "body", ChildCardinality.ExactlyOne)),
    terminals = captureNullaryFunctionTypeProduction.terminals.filterNot(_.id == "capture-result-separator")
  )

  private lazy val impureByNameParameterTypeProduction =
    byNameParameterType.copy(
      id = "impure-by-name-parameter-type",
      pattern = byNameParameterType.pattern.copy(
        directNodeEvidence = Vector(DirectNodeFieldEvidence("result", SourceClassification.Synthetic))
      ),
      children = Vector(
        ChildDeclaration("result", "result", ChildCardinality.ExactlyOne, "by-name-captures-and-result")
      )
    )

  private lazy val byNameCaptureSetTemplate = LocalOutputCompositeTemplate(
    Vector(
      outputComposite(
        "capture-set",
        None,
        OutputRangeDeclaration.BalancedLexicalRangeBeforeChildOutput(
          "result",
          ClosedSourceLexicalKind.LeftBrace,
          ClosedSourceLexicalKind.RightBrace
        ),
        PsiOutputRoleId.CaptureSet,
        CaptureSetSurface,
        Vector.empty
      )
    ),
    Map("capture-references" -> Some("capture-set"), "result" -> None)
  )

  private def byNameCaptureSetRealization(id: String, referenceProductionId: String): OutputRealization =
    OutputRealization(
      id,
      Vector(
        ChildOutcomeCondition(
          "capture-references",
          ChildOccurrenceSelector.First,
          ChildOutcomeExpectation.Production(referenceProductionId)
        )
      ),
      byNameCaptureSetTemplate,
      Vector(EvidenceCondition.RepeatedFieldSize("refs", 1, None))
    )

  private lazy val byNameCapturesAndResultProduction = Scala3PsiProduction(
    id = "by-name-captures-and-result",
    grammarRoleId = GrammarRoleId.CaptureSet,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "CapturesAndResult",
      Vector(
        CompilerFieldPattern("refs", CatalogValuePattern.Repeated(CatalogValuePattern.Node)),
        CompilerFieldPattern("parent", CatalogValuePattern.Node)
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(InventoryKind.Node, "ByNameTypeTree", Vector(CatalogPathSegment.NamedField("result"))),
          SourceClassification.Synthetic
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("refs", FieldDispositionKind.Child),
      FieldDisposition("parent", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "capture-references",
        "refs",
        ChildCardinality.Repeated(0, None),
        "capture-function-reference",
        Set(
          "capture-function-qualified-reference",
          "capture-function-reference-modifier-reach",
          "capture-function-reference-modifier-read-only",
          "capture-function-reference-modifier-filter",
          "by-name-capture-root-select"
        )
      ),
      compoundChild("result", "parent", ChildCardinality.ExactlyOne)
    ),
    terminals = Vector(
      TerminalDeclaration(
        "capture-reference-commas",
        TerminalIntervalSelector.ChildOutputSeparators("capture-references"),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeCommaTokenSurface, Some(",")),
        OccurrenceCardinality.Repeated(0, None),
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "capture-reference-separator-evidence",
        TerminalIntervalSelector.ChildOutputSeparators("capture-references"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.Repeated(0, None),
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = CaptureSetSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputRealizations = Vector(
      OutputRealization(
        "synthetic-capture-root",
        Vector(
          ChildOutcomeCondition(
            "capture-references",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Production("by-name-capture-root-select")
          )
        ),
        transparentTemplate("capture-references", "result"),
        Vector(EvidenceCondition.RepeatedFieldSize("refs", 1, None))
      ),
      OutputRealization(
        "empty-explicit-set",
        Vector.empty,
        byNameCaptureSetTemplate,
        Vector(EvidenceCondition.RepeatedFieldSize("refs", 0, Some(0)))
      ),
      byNameCaptureSetRealization("direct-explicit-set", "capture-function-reference"),
      byNameCaptureSetRealization("qualified-explicit-set", "capture-function-qualified-reference"),
      byNameCaptureSetRealization("reach-explicit-set", "capture-function-reference-modifier-reach"),
      byNameCaptureSetRealization("read-only-explicit-set", "capture-function-reference-modifier-read-only"),
      byNameCaptureSetRealization("filter-explicit-set", "capture-function-reference-modifier-filter")
    ),
    outputRoleId = None
  )

  private lazy val byNameCaptureRootSelectProduction = Scala3PsiProduction(
    id = "by-name-capture-root-select",
    grammarRoleId = GrammarRoleId.CaptureSynthetic,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Select",
      Vector(
        CompilerFieldPattern("qualifier", CatalogValuePattern.NodePrefix("Select")),
        CompilerFieldPattern("name", CatalogValuePattern.Name)
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestorPrefix(
            InventoryKind.Node,
            "CapturesAndResult",
            Vector(CatalogPathSegment.NamedField("refs"), CatalogPathSegment.RepeatedElement),
            Vector(
              InventoryAncestor(
                InventoryKind.Node,
                "ByNameTypeTree",
                Vector(CatalogPathSegment.NamedField("result"))
              )
            )
          ),
          SourceClassification.Synthetic
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("qualifier", FieldDispositionKind.Child),
      FieldDisposition("name", FieldDispositionKind.SemanticOnly)
    ),
    children = Vector(
      ChildDeclaration(
        "qualifier",
        "qualifier",
        ChildCardinality.ExactlyOne,
        "by-name-capture-root-middle-select"
      )
    ),
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = CaptureSetSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(transparentTemplate("qualifier")),
    outputRoleId = None
  )

  private lazy val byNameCaptureRootMiddleSelectProduction = byNameCaptureRootSelectProduction.copy(
    id = "by-name-capture-root-middle-select",
    pattern = byNameCaptureRootSelectProduction.pattern.copy(
      occurrences = Vector(
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestorPrefix(
            InventoryKind.Node,
            "Select",
            Vector(CatalogPathSegment.NamedField("qualifier")),
            Vector(
              InventoryAncestor(
                InventoryKind.Node,
                "CapturesAndResult",
                Vector(CatalogPathSegment.NamedField("refs"), CatalogPathSegment.RepeatedElement)
              ),
              InventoryAncestor(
                InventoryKind.Node,
                "ByNameTypeTree",
                Vector(CatalogPathSegment.NamedField("result"))
              )
            )
          ),
          SourceClassification.Synthetic
        )
      )
    ),
    children = Vector(
      ChildDeclaration(
        "qualifier",
        "qualifier",
        ChildCardinality.ExactlyOne,
        "by-name-capture-root-inner-select"
      )
    )
  )

  private lazy val byNameCaptureRootInnerSelectProduction = byNameCaptureRootSelectProduction.copy(
    id = "by-name-capture-root-inner-select",
    pattern = byNameCaptureRootSelectProduction.pattern.copy(
      fields = Vector(
        CompilerFieldPattern("qualifier", CatalogValuePattern.NodePrefix("Ident")),
        CompilerFieldPattern("name", CatalogValuePattern.Name)
      ),
      occurrences = Vector(
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestorPrefix(
            InventoryKind.Node,
            "Select",
            Vector(CatalogPathSegment.NamedField("qualifier")),
            Vector(
              InventoryAncestor(
                InventoryKind.Node,
                "Select",
                Vector(CatalogPathSegment.NamedField("qualifier"))
              ),
              InventoryAncestor(
                InventoryKind.Node,
                "CapturesAndResult",
                Vector(CatalogPathSegment.NamedField("refs"), CatalogPathSegment.RepeatedElement)
              ),
              InventoryAncestor(
                InventoryKind.Node,
                "ByNameTypeTree",
                Vector(CatalogPathSegment.NamedField("result"))
              )
            )
          ),
          SourceClassification.Synthetic
        )
      )
    ),
    children = Vector(
      ChildDeclaration("qualifier", "qualifier", ChildCardinality.ExactlyOne, "by-name-capture-root-ident")
    )
  )

  private lazy val byNameCaptureRootIdentProduction = Scala3PsiProduction(
    id = "by-name-capture-root-ident",
    grammarRoleId = GrammarRoleId.CaptureSynthetic,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.Name)),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestorPrefix(
            InventoryKind.Node,
            "Select",
            Vector(CatalogPathSegment.NamedField("qualifier")),
            Vector(
              InventoryAncestor(
                InventoryKind.Node,
                "Select",
                Vector(CatalogPathSegment.NamedField("qualifier"))
              ),
              InventoryAncestor(
                InventoryKind.Node,
                "Select",
                Vector(CatalogPathSegment.NamedField("qualifier"))
              ),
              InventoryAncestor(
                InventoryKind.Node,
                "CapturesAndResult",
                Vector(CatalogPathSegment.NamedField("refs"), CatalogPathSegment.RepeatedElement)
              ),
              InventoryAncestor(
                InventoryKind.Node,
                "ByNameTypeTree",
                Vector(CatalogPathSegment.NamedField("result"))
              )
            )
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
    targetSurfaceId = CaptureSetSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(transparentTemplate()),
    outputRoleId = None
  )

  private val captureByNameParameterTypeProduction = Scala3PsiProduction(
    id = "capture-by-name-parameter-type",
    grammarRoleId = GrammarRoleId.ByNameParameterType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "ByNameTypeTree",
      Vector(CompilerFieldPattern("result", CatalogValuePattern.Node)),
      dependentParameterTypeOccurrences.map(
        _.copy(scannerEvidence = ScannerEvidencePattern(required = Set(ParserScannerTokenKind.PureFunctionArrow)))
      ),
      Vector(DirectNodeFieldEvidence("result", SourceClassification.Synthetic))
    ),
    dispositions = Vector(FieldDisposition("result", FieldDispositionKind.Child)),
    children = Vector(
      ChildDeclaration("result", "result", ChildCardinality.ExactlyOne, "by-name-captures-and-result")
    ),
    terminals = Vector(
      TerminalDeclaration(
        "capture-left-brace",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Token(NativePsiElementBindings.ContextBoundLeftBraceTokenSurface, Some("{")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "capture-right-brace",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Token(NativePsiElementBindings.ContextBoundRightBraceTokenSurface, Some("}")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "by-name-arrow",
        TerminalIntervalSelector.CompilerScannerTokenBeforeChildOutputs(
          ParserScannerTokenKind.PureFunctionArrow,
          "result"
        ),
        TerminalLeafTarget.Token(NativePsiElementBindings.PureFunctionArrowTokenSurface, None),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "by-name-prefix-evidence",
        TerminalIntervalSelector.BeforeChildOutputs("result"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "capture-result-separator",
        TerminalIntervalSelector.ChildOutputSeparators("result"),
        TerminalLeafTarget.Trivia,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = PureParameterTypeSurface,
    targetRequirement = TargetRequirement.Compatible,
    accessors = PureParameterTypeAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(transparentTemplate("result")),
    outputRoleId = None
  )

  private val pureByNameParameterTypeProduction =
    byNameParameterType.copy(
      id = "pure-by-name-parameter-type",
      pattern = byNameParameterType.pattern.copy(
        occurrences = dependentParameterTypeOccurrences.map(
          _.copy(scannerEvidence = ScannerEvidencePattern(required = Set(ParserScannerTokenKind.PureFunctionArrow)))
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "by-name-arrow",
          TerminalIntervalSelector.CompilerScannerTokenBeforeChildOutputs(
            ParserScannerTokenKind.PureFunctionArrow,
            "result"
          ),
          TerminalLeafTarget.Token(NativePsiElementBindings.PureFunctionArrowTokenSurface, None),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal,
          ownsStructuralEvidence = Some(false)
        ),
        byNameParameterType.terminals
          .find(_.id == "by-name-prefix-evidence")
          .get
      ),
      targetSurfaceId = PureParameterTypeSurface,
      targetRequirement = TargetRequirement.Compatible,
      accessors = PureParameterTypeAccessors
    )

  private val captureTypeFields = Vector(
    CompilerFieldPattern("arg", CatalogValuePattern.Node),
    CompilerFieldPattern("annot", CatalogValuePattern.NodePrefix("Apply"))
  )

  private def captureTypeProduction(explicitSet: Boolean): Scala3PsiProduction =
    val id              = if explicitSet then "capture-type-explicit-set" else "capture-type-shorthand"
    val scannerEvidence =
      if explicitSet then
        ScannerEvidencePattern(required = Set(ParserScannerTokenKind.CaptureOperator, ParserScannerTokenKind.LeftBrace))
      else
        ScannerEvidencePattern(
          required = Set(ParserScannerTokenKind.CaptureOperator),
          forbidden = Set(ParserScannerTokenKind.LeftBrace)
        )
    val captureType     = outputComposite(
      "capture-type",
      None,
      OutputRangeDeclaration.CompilerPosition,
      PsiOutputRoleId.CaptureType,
      CaptureTypeSurface,
      CaptureTypeAccessors
    )
    val composites      =
      if explicitSet then
        Vector(
          captureType,
          outputComposite(
            "capture-set",
            Some("capture-type"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.Advance(
                OutputBoundary.ChildEnd(
                  "captured-type",
                  ChildOccurrenceSelector.First,
                  PositionProvenancePolicy.SourceDerivedOnly
                ),
                1
              ),
              OutputBoundary.ProductionEnd()
            ),
            PsiOutputRoleId.CaptureSet,
            CaptureSetSurface,
            Vector.empty
          )
        )
      else Vector(captureType)
    Scala3PsiProduction(
      id = id,
      grammarRoleId = GrammarRoleId.CaptureType,
      additionalGrammarRoleIds = Option.when(explicitSet)(GrammarRoleId.CaptureSet).toSet,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Annotated",
        captureTypeFields,
        (typeAtomOccurrences ++ compoundTypeArgumentOccurrences).map(_.copy(scannerEvidence = scannerEvidence)),
        Vector(
          DirectNodeFieldEvidence(
            "annot",
            SourceClassification.Synthetic,
            hasSourceWidth = Option.when(!explicitSet)(false),
            requiredAttachmentKinds = Option.when(explicitSet)("RetainsAnnot").toSet
          )
        )
      ),
      dispositions = Vector(
        FieldDisposition("arg", FieldDispositionKind.Child),
        FieldDisposition("annot", FieldDispositionKind.Child)
      ),
      children = Vector(
        compoundChild("captured-type", "arg", ChildCardinality.ExactlyOne),
        ChildDeclaration("capture-annotation", "annot", ChildCardinality.ExactlyOne, "capture-annotation-apply")
      ),
      terminals = Vector(
        TerminalDeclaration(
          "capture-type-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "capture-operator",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(NativePsiElementBindings.CaptureOperatorTokenSurface, Some("^")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal,
          ownsStructuralEvidence = Some(false)
        ),
        TerminalDeclaration(
          "capture-left-brace",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(NativePsiElementBindings.ContextBoundLeftBraceTokenSurface, Some("{")),
          if explicitSet then OccurrenceCardinality.ExactlyOne else OccurrenceCardinality.Optional,
          PsiOutputRoleId.SourceTerminal,
          ownsStructuralEvidence = Some(false)
        ),
        TerminalDeclaration(
          "capture-right-brace",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(NativePsiElementBindings.ContextBoundRightBraceTokenSurface, Some("}")),
          if explicitSet then OccurrenceCardinality.ExactlyOne else OccurrenceCardinality.Optional,
          PsiOutputRoleId.SourceTerminal,
          ownsStructuralEvidence = Some(false)
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = CaptureTypeSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = CaptureTypeAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputTemplate = Some(
        LocalOutputCompositeTemplate(
          composites,
          Map(
            "captured-type"      -> Some("capture-type"),
            "capture-annotation" -> Some(if explicitSet then "capture-set" else "capture-type")
          )
        )
      ),
      outputRoleId = None
    )

  private val captureAnnotationApplyProduction = Scala3PsiProduction(
    id = "capture-annotation-apply",
    grammarRoleId = GrammarRoleId.CaptureSet,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Apply",
      Vector(
        CompilerFieldPattern("fun", CatalogValuePattern.Node),
        CompilerFieldPattern("args", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node))
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "Annotated",
            Vector(CatalogPathSegment.NamedField("annot"))
          ),
          SourceClassification.Synthetic
        )
      ).map(_.copy(scannerEvidence = ScannerEvidencePattern(forbidden = Set(ParserScannerTokenKind.AtSign))))
    ),
    dispositions = Vector(
      FieldDisposition("fun", FieldDispositionKind.Child),
      FieldDisposition("args", FieldDispositionKind.Synthetic)
    ),
    children = Vector(
      ChildDeclaration(
        "annotation-function",
        "fun",
        ChildCardinality.ExactlyOne,
        "capture-synthetic-select",
        Set("capture-synthetic-type-apply")
      )
    ),
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = CaptureSetSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(transparentTemplate("annotation-function")),
    outputRoleId = None
  )

  private def captureTransparentProduction(
      id: String,
      prefix: String,
      fields: Vector[CompilerFieldPattern],
      occurrences: Vector[CompilerProductionContextPattern],
      dispositions: Vector[FieldDisposition],
      children: Vector[ChildDeclaration],
      directNodeEvidence: Vector[DirectNodeFieldEvidence] = Vector.empty
  ): Scala3PsiProduction = Scala3PsiProduction(
    id = id,
    grammarRoleId = GrammarRoleId.CaptureSet,
    pattern = CompilerProductionPattern(InventoryKind.Node, prefix, fields, occurrences, directNodeEvidence),
    dispositions = dispositions,
    children = children,
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = CaptureSetSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(transparentTemplate(children.map(_.roleId)*)),
    outputRoleId = None
  )

  private val CaptureAnnotationAnchor   = InventoryAncestor(
    InventoryKind.Node,
    "Annotated",
    Vector(CatalogPathSegment.NamedField("annot"))
  )
  private val CaptureAnnotationEvidence = Vector(
    AncestorEvidencePattern(
      directNodeEvidence = Vector(
        DirectNodeFieldEvidence("annot", SourceClassification.Synthetic, hasSourceWidth = Some(false))
      )
    ),
    AncestorEvidencePattern(
      directNodeEvidence = Vector(
        DirectNodeFieldEvidence(
          "annot",
          SourceClassification.Synthetic,
          requiredAttachmentKinds = Set("RetainsAnnot")
        )
      )
    ),
    AncestorEvidencePattern(
      scannerEvidence = ScannerEvidencePattern(forbidden = Set(ParserScannerTokenKind.AtSign)),
      directNodeEvidence = Vector(
        DirectNodeFieldEvidence("annot", SourceClassification.Synthetic, hasSourceWidth = Some(true))
      )
    )
  )

  private def captureSyntheticOccurrence(
      ownerPrefix: String,
      path: Vector[CatalogPathSegment]
  ): CompilerProductionContextPattern = CompilerProductionContextPattern(
    ContextPattern.ParentUnderAnchorWithEvidence(
      InventoryKind.Node,
      ownerPrefix,
      path,
      CaptureAnnotationAnchor,
      CaptureAnnotationEvidence
    ),
    SourceClassification.Synthetic
  )

  private val captureSyntheticSelectProduction = captureTransparentProduction(
    "capture-synthetic-select",
    "Select",
    Vector(
      CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
      CompilerFieldPattern("name", CatalogValuePattern.Name)
    ),
    Vector(
      captureSyntheticOccurrence("Apply", Vector(CatalogPathSegment.NamedField("fun"))),
      captureSyntheticOccurrence("New", Vector(CatalogPathSegment.NamedField("tpt"))),
      captureSyntheticOccurrence("Select", Vector(CatalogPathSegment.NamedField("qualifier"))),
      captureSyntheticOccurrence("TypeApply", Vector(CatalogPathSegment.NamedField("fun")))
    ),
    Vector(
      FieldDisposition("qualifier", FieldDispositionKind.Child),
      FieldDisposition("name", FieldDispositionKind.SemanticOnly)
    ),
    Vector(
      ChildDeclaration(
        "qualifier",
        "qualifier",
        ChildCardinality.ExactlyOne,
        "capture-synthetic-ident",
        Set("capture-synthetic-new", "capture-synthetic-select", "capture-synthetic-typed-splice")
      )
    )
  )

  private val captureSyntheticTypeApplyProduction = captureTransparentProduction(
    "capture-synthetic-type-apply",
    "TypeApply",
    Vector(
      CompilerFieldPattern("fun", CatalogValuePattern.NodePrefix("Select")),
      CompilerFieldPattern("args", CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.Node))
    ),
    Vector(
      captureSyntheticOccurrence("Apply", Vector(CatalogPathSegment.NamedField("fun")))
    ),
    Vector(FieldDisposition("fun", FieldDispositionKind.Child), FieldDisposition("args", FieldDispositionKind.Child)),
    Vector(
      ChildDeclaration(
        "type-function",
        "fun",
        ChildCardinality.ExactlyOne,
        "capture-synthetic-select"
      ),
      ChildDeclaration(
        "type-arguments",
        "args",
        ChildCardinality.Repeated(1, None),
        "capture-set-group",
        Set("capture-reference", "capture-synthetic-typed-splice", "capture-reference-ident")
      )
    )
  )

  private val captureSyntheticNewProduction = captureTransparentProduction(
    "capture-synthetic-new",
    "New",
    Vector(CompilerFieldPattern("tpt", CatalogValuePattern.Node)),
    Vector(
      captureSyntheticOccurrence("Select", Vector(CatalogPathSegment.NamedField("qualifier")))
    ),
    Vector(FieldDisposition("tpt", FieldDispositionKind.Child)),
    Vector(
      ChildDeclaration(
        "new-type",
        "tpt",
        ChildCardinality.ExactlyOne,
        "capture-synthetic-select",
        Set("capture-synthetic-typed-splice")
      )
    ),
    directNodeEvidence = Vector(DirectNodeFieldEvidence("tpt", SourceClassification.Synthetic))
  )

  private val captureSetGroupProduction = captureTransparentProduction(
    "capture-set-group",
    "AppliedTypeTree",
    Vector(
      CompilerFieldPattern("tpt", CatalogValuePattern.NodePrefix("TypedSplice")),
      CompilerFieldPattern("args", CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.Node))
    ),
    Vector(
      captureSyntheticOccurrence(
        "TypeApply",
        Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
      ),
      captureSyntheticOccurrence(
        "AppliedTypeTree",
        Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
      )
    ),
    Vector(FieldDisposition("tpt", FieldDispositionKind.Child), FieldDisposition("args", FieldDispositionKind.Child)),
    Vector(
      ChildDeclaration(
        "set-constructor",
        "tpt",
        ChildCardinality.ExactlyOne,
        "capture-synthetic-typed-splice"
      ),
      ChildDeclaration(
        "set-elements",
        "args",
        ChildCardinality.Repeated(1, None),
        "capture-set-group",
        Set("capture-reference")
      )
    )
  )

  private val captureSyntheticTypedSpliceProduction = captureTransparentProduction(
    "capture-synthetic-typed-splice",
    "TypedSplice",
    Vector(CompilerFieldPattern("splice", CatalogValuePattern.NodePrefix("TypeTree"))),
    Vector(
      captureSyntheticOccurrence("New", Vector(CatalogPathSegment.NamedField("tpt"))),
      captureSyntheticOccurrence("AppliedTypeTree", Vector(CatalogPathSegment.NamedField("tpt"))),
      captureSyntheticOccurrence(
        "TypeApply",
        Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
      )
    ),
    Vector(FieldDisposition("splice", FieldDispositionKind.Child)),
    Vector(
      ChildDeclaration(
        "splice",
        "splice",
        ChildCardinality.ExactlyOne,
        "capture-synthetic-type-tree"
      )
    )
  )

  private val captureSyntheticTypeTreeProduction = captureTransparentProduction(
    "capture-synthetic-type-tree",
    "TypeTree",
    Vector.empty,
    Vector(
      captureSyntheticOccurrence("TypedSplice", Vector(CatalogPathSegment.NamedField("splice")))
    ),
    Vector.empty,
    Vector.empty
  )

  private val captureSyntheticIdentProduction = captureTransparentProduction(
    "capture-synthetic-ident",
    "Ident",
    Vector(CompilerFieldPattern("name", CatalogValuePattern.Name)),
    Vector(
      captureSyntheticOccurrence("Select", Vector(CatalogPathSegment.NamedField("qualifier")))
    ),
    Vector(FieldDisposition("name", FieldDispositionKind.SemanticOnly)),
    Vector.empty
  )

  private val captureReferenceIdentProduction = Scala3PsiProduction(
    id = "capture-reference-ident",
    grammarRoleId = GrammarRoleId.StableReference,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.Name)),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestorPrefix(
            InventoryKind.Node,
            "Annotated",
            Vector(CatalogPathSegment.NamedField("arg")),
            Vector(
              InventoryAncestor(
                InventoryKind.Node,
                "SingletonTypeTree",
                Vector(CatalogPathSegment.NamedField("ref"))
              )
            )
          ),
          SourceClassification.SourceReachable
        ),
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestorPrefix(
            InventoryKind.Node,
            "TypeApply",
            Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
            Vector(
              InventoryAncestor(InventoryKind.Node, "Apply", Vector(CatalogPathSegment.NamedField("fun"))),
              InventoryAncestor(InventoryKind.Node, "Annotated", Vector(CatalogPathSegment.NamedField("annot")))
            )
          ),
          SourceClassification.SourceReachable
        ),
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestorPrefix(
            InventoryKind.Node,
            "Annotated",
            Vector(CatalogPathSegment.NamedField("arg")),
            Vector(
              InventoryAncestor(
                InventoryKind.Node,
                "CapturesAndResult",
                Vector(CatalogPathSegment.NamedField("refs"), CatalogPathSegment.RepeatedElement)
              )
            )
          ),
          SourceClassification.SourceReachable
        )
      )
    ),
    dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "capture-reference-text",
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
    outputTemplate = Some(stableReferenceTemplate()),
    outputRoleId = None
  )

  private val captureReferenceProduction = Scala3PsiProduction(
    id = "capture-reference",
    grammarRoleId = GrammarRoleId.CaptureReference,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "SingletonTypeTree",
      Vector(CompilerFieldPattern("ref", CatalogValuePattern.Node)),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "TypeApply",
            Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
          ),
          SourceClassification.Synthetic
        ),
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "AppliedTypeTree",
            Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
          ),
          SourceClassification.Synthetic
        )
      )
    ),
    dispositions = Vector(FieldDisposition("ref", FieldDispositionKind.Child)),
    children = Vector(
      ChildDeclaration(
        "reference",
        "ref",
        ChildCardinality.ExactlyOne,
        "type-atom-singleton-reference-ident",
        Set(
          "type-atom-singleton-reference-select",
          "capture-reference-modifier-reach",
          "capture-reference-modifier-read-only",
          "capture-reference-modifier-filter"
        )
      )
    ),
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = CaptureReferenceSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = CaptureReferenceAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "capture-reference",
            None,
            OutputRangeDeclaration.CompilerPositionWithPolicy(PositionProvenancePolicy.PositionedIncludingSynthetic),
            PsiOutputRoleId.CaptureReference,
            CaptureReferenceSurface,
            CaptureReferenceAccessors
          )
        ),
        Map("reference" -> Some("capture-reference"))
      )
    ),
    outputRoleId = None
  )

  private def captureReferenceModifierProduction(
      id: String,
      required: Set[ParserScannerTokenKind],
      forbidden: Set[ParserScannerTokenKind],
      filter: Boolean
  ): Scala3PsiProduction =
    val output =
      if filter then
        Some(
          LocalOutputCompositeTemplate(
            Vector(
              outputComposite(
                "capture-filter",
                None,
                OutputRangeDeclaration.BoundaryDerived(
                  OutputBoundary.ChildEnd(
                    "reference",
                    ChildOccurrenceSelector.First,
                    PositionProvenancePolicy.SourceDerivedOnly
                  ),
                  OutputBoundary.ProductionEnd()
                ),
                PsiOutputRoleId.CaptureFilter,
                CaptureFilterSurface,
                CaptureFilterAccessors
              )
            ),
            Map("reference" -> None, "modifier" -> Some("capture-filter"))
          )
        )
      else Some(transparentTemplate("reference", "modifier"))
    Scala3PsiProduction(
      id = id,
      grammarRoleId = if filter then GrammarRoleId.CaptureFilter else GrammarRoleId.CaptureReference,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Annotated",
        captureTypeFields,
        Vector(
          CompilerProductionContextPattern(
            ContextPattern
              .Parent(InventoryKind.Node, "SingletonTypeTree", Vector(CatalogPathSegment.NamedField("ref"))),
            SourceClassification.SourceReachable,
            ScannerEvidencePattern(required, forbidden)
          )
        ),
        Vector(DirectNodeFieldEvidence("annot", SourceClassification.Synthetic))
      ),
      dispositions = Vector(
        FieldDisposition("arg", FieldDispositionKind.Child),
        FieldDisposition("annot", FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration("reference", "arg", ChildCardinality.ExactlyOne, "capture-reference-ident"),
        ChildDeclaration("modifier", "annot", ChildCardinality.ExactlyOne, "capture-annotation-apply")
      ),
      terminals = Vector(
        TerminalDeclaration(
          "capture-reference-modifier-source",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "capture-reach",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(NativePsiElementBindings.CaptureReachTokenSurface, Some("*")),
          if id.endsWith("reach") then OccurrenceCardinality.ExactlyOne else OccurrenceCardinality.Optional,
          PsiOutputRoleId.SourceTerminal,
          ownsStructuralEvidence = Some(false)
        ),
        TerminalDeclaration(
          "capture-read-only",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(NativePsiElementBindings.CaptureReadOnlyTokenSurface, Some("rd")),
          if id.endsWith("read-only") then OccurrenceCardinality.ExactlyOne else OccurrenceCardinality.Optional,
          PsiOutputRoleId.SourceTerminal,
          ownsStructuralEvidence = Some(false)
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = if filter then CaptureFilterSurface else CaptureReferenceSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = if filter then CaptureFilterAccessors else CaptureReferenceAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputTemplate = output,
      outputRoleId = None
    )

  private val captureReferenceModifierProductions = Vector(
    captureReferenceModifierProduction(
      "capture-reference-modifier-reach",
      Set.empty,
      Set(ParserScannerTokenKind.Dot, ParserScannerTokenKind.LeftBracket),
      filter = false
    ),
    captureReferenceModifierProduction(
      "capture-reference-modifier-read-only",
      Set(ParserScannerTokenKind.Dot),
      Set(ParserScannerTokenKind.LeftBracket),
      filter = false
    ),
    captureReferenceModifierProduction(
      "capture-reference-modifier-filter",
      Set(ParserScannerTokenKind.Dot, ParserScannerTokenKind.LeftBracket),
      Set.empty,
      filter = true
    )
  )

  private val captureFunctionReferenceProduction = Scala3PsiProduction(
    id = "capture-function-reference",
    grammarRoleId = GrammarRoleId.CaptureReference,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.Name)),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "CapturesAndResult",
            Vector(CatalogPathSegment.NamedField("refs"), CatalogPathSegment.RepeatedElement)
          ),
          SourceClassification.SourceReachable
        )
      )
    ),
    dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "capture-reference-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = CaptureReferenceSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = CaptureReferenceAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "capture-reference",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.CaptureReference,
            CaptureReferenceSurface,
            CaptureReferenceAccessors
          ),
          outputComposite(
            "reference",
            Some("capture-reference"),
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.StableReference,
            StableReferenceSurface,
            StableReferenceAccessors
          )
        ),
        Map.empty
      )
    ),
    outputRoleId = None
  )

  private val captureFunctionQualifiedReferenceProduction = Scala3PsiProduction(
    id = "capture-function-qualified-reference",
    grammarRoleId = GrammarRoleId.CaptureReference,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Select",
      Vector(
        CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
        CompilerFieldPattern("name", CatalogValuePattern.Name)
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "CapturesAndResult",
            Vector(CatalogPathSegment.NamedField("refs"), CatalogPathSegment.RepeatedElement)
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
        "import-selector-given-bound-qualifier-ident"
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "capture-reference-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = CaptureReferenceSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = CaptureReferenceAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "capture-reference",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.CaptureReference,
            CaptureReferenceSurface,
            CaptureReferenceAccessors
          ),
          outputComposite(
            "reference",
            Some("capture-reference"),
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.StableReference,
            StableReferenceSurface,
            StableReferenceAccessors
          )
        ),
        Map("qualifier" -> Some("reference"))
      )
    ),
    outputRoleId = None
  )

  private def captureFunctionReferenceModifierProduction(
      id: String,
      required: Set[ParserScannerTokenKind],
      forbidden: Set[ParserScannerTokenKind],
      filter: Boolean
  ): Scala3PsiProduction =
    val composites = Vector(
      outputComposite(
        "capture-reference",
        None,
        OutputRangeDeclaration.CompilerPosition,
        PsiOutputRoleId.CaptureReference,
        CaptureReferenceSurface,
        CaptureReferenceAccessors
      )
    ) ++ Option.when(filter)(
      outputComposite(
        "capture-filter",
        Some("capture-reference"),
        OutputRangeDeclaration.BoundaryDerived(
          OutputBoundary.ChildEnd(
            "reference",
            ChildOccurrenceSelector.First,
            PositionProvenancePolicy.SourceDerivedOnly
          ),
          OutputBoundary.ProductionEnd()
        ),
        PsiOutputRoleId.CaptureFilter,
        CaptureFilterSurface,
        CaptureFilterAccessors
      )
    )
    captureReferenceModifierProduction(id, required, forbidden, filter).copy(
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Annotated",
        captureTypeFields,
        Vector(
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              "CapturesAndResult",
              Vector(CatalogPathSegment.NamedField("refs"), CatalogPathSegment.RepeatedElement)
            ),
            SourceClassification.SourceReachable,
            ScannerEvidencePattern(required, forbidden)
          )
        ),
        Vector(DirectNodeFieldEvidence("annot", SourceClassification.Synthetic))
      ),
      outputTemplate = Some(
        LocalOutputCompositeTemplate(
          composites,
          Map(
            "reference" -> Some("capture-reference"),
            "modifier"  -> Some(if filter then "capture-filter" else "capture-reference")
          )
        )
      )
    )

  private val captureFunctionReferenceModifierProductions = Vector(
    captureFunctionReferenceModifierProduction(
      "capture-function-reference-modifier-reach",
      Set.empty,
      Set(ParserScannerTokenKind.Dot, ParserScannerTokenKind.LeftBracket),
      filter = false
    ),
    captureFunctionReferenceModifierProduction(
      "capture-function-reference-modifier-read-only",
      Set(ParserScannerTokenKind.Dot),
      Set(ParserScannerTokenKind.LeftBracket),
      filter = false
    ),
    captureFunctionReferenceModifierProduction(
      "capture-function-reference-modifier-filter",
      Set(ParserScannerTokenKind.Dot, ParserScannerTokenKind.LeftBracket),
      Set.empty,
      filter = true
    )
  )

  private val captureFunctionResultIdentProduction = Scala3PsiProduction(
    id = "capture-function-result-ident",
    grammarRoleId = GrammarRoleId.SimpleType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(
        CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "CapturesAndResult",
            Vector(CatalogPathSegment.NamedField("parent"))
          ),
          SourceClassification.SourceReachable
        )
      )
    ),
    dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
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
    outputTemplate = Some(
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
        Map.empty
      )
    ),
    outputRoleId = None
  )

  private val captureFunctionResultProduction = Scala3PsiProduction(
    id = "capture-function-result",
    grammarRoleId = GrammarRoleId.CaptureSet,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "CapturesAndResult",
      Vector(
        CompilerFieldPattern("refs", CatalogValuePattern.Repeated(CatalogValuePattern.Node)),
        CompilerFieldPattern("parent", CatalogValuePattern.Node)
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(InventoryKind.Node, "Function", Vector(CatalogPathSegment.NamedField("body"))),
          SourceClassification.Synthetic
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("refs", FieldDispositionKind.Child),
      FieldDisposition("parent", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "capture-references",
        "refs",
        ChildCardinality.Repeated(0, None),
        "capture-function-reference",
        Set(
          "capture-function-qualified-reference",
          "capture-function-reference-modifier-reach",
          "capture-function-reference-modifier-read-only",
          "capture-function-reference-modifier-filter"
        )
      ),
      compoundChild("result", "parent", ChildCardinality.ExactlyOne)
    ),
    terminals = Vector(
      TerminalDeclaration(
        "capture-left-brace",
        TerminalIntervalSelector.LocalOutput("capture-set"),
        TerminalLeafTarget.Token(NativePsiElementBindings.ContextBoundLeftBraceTokenSurface, Some("{")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "capture-right-brace",
        TerminalIntervalSelector.LocalOutput("capture-set"),
        TerminalLeafTarget.Token(NativePsiElementBindings.ContextBoundRightBraceTokenSurface, Some("}")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "capture-reference-commas",
        TerminalIntervalSelector.ChildOutputSeparators("capture-references"),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeCommaTokenSurface, Some(",")),
        OccurrenceCardinality.Repeated(0, None),
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "capture-reference-separator-evidence",
        TerminalIntervalSelector.ChildOutputSeparators("capture-references"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.Repeated(0, None),
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = CaptureSetSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "capture-set",
            None,
            OutputRangeDeclaration.BalancedLexicalRangeBeforeChildOutput(
              "result",
              ClosedSourceLexicalKind.LeftBrace,
              ClosedSourceLexicalKind.RightBrace
            ),
            PsiOutputRoleId.CaptureSet,
            CaptureSetSurface,
            Vector.empty
          )
        ),
        Map("capture-references" -> Some("capture-set"), "result" -> None)
      )
    ),
    outputRoleId = None
  )

  private[psiproducer] lazy val CaptureFunctionSegment: Vector[Scala3PsiProduction] = Vector(
    pureNullaryFunctionTypeProduction,
    pureFunctionTypeProduction,
    captureNullaryFunctionTypeProduction,
    captureFunctionTypeProduction
  )

  private[psiproducer] lazy val CaptureByNameSegment: Vector[Scala3PsiProduction] = Vector(
    impureByNameParameterTypeProduction,
    byNameCapturesAndResultProduction,
    byNameCaptureRootSelectProduction,
    byNameCaptureRootMiddleSelectProduction,
    byNameCaptureRootInnerSelectProduction,
    byNameCaptureRootIdentProduction,
    pureByNameParameterTypeProduction,
    captureByNameParameterTypeProduction
  )

  private[psiproducer] lazy val CaptureTypeSegment: Vector[Scala3PsiProduction] = Vector(
    captureTypeProduction(explicitSet = false),
    captureTypeProduction(explicitSet = true),
    captureAnnotationApplyProduction,
    captureSyntheticSelectProduction,
    captureSyntheticNewProduction,
    captureSyntheticTypeApplyProduction,
    captureSetGroupProduction,
    captureSyntheticTypedSpliceProduction,
    captureSyntheticTypeTreeProduction,
    captureSyntheticIdentProduction,
    captureReferenceIdentProduction,
    captureReferenceProduction,
    captureFunctionReferenceProduction,
    captureFunctionQualifiedReferenceProduction,
    captureFunctionResultIdentProduction,
    captureFunctionResultProduction
  ) ++ captureReferenceModifierProductions ++ captureFunctionReferenceModifierProductions
