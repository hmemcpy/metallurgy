package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiTupleFunctionTypeProductions:
  private val tupleTypeProduction = Scala3PsiProduction(
    id = "ordinary-tuple-type",
    grammarRoleId = GrammarRoleId.TupleType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Tuple",
      Vector(
        CompilerFieldPattern("trees", CatalogValuePattern.Repeated(CatalogValuePattern.NodeExceptPrefix("NamedArg")))
      ),
      typeAtomOccurrences ++ compoundTypeArgumentOccurrences
    ),
    dispositions = Vector(FieldDisposition("trees", FieldDispositionKind.Child)),
    children = Vector(compoundChild("components", "trees", ChildCardinality.Repeated(2, None))),
    terminals = Vector(
      TerminalDeclaration(
        "tuple-left-parenthesis",
        TerminalIntervalSelector.CompilerScannerToken(
          ParserScannerTokenKind.LeftParenthesis,
          ScannerTokenOccurrence.First
        ),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeLeftParenthesisTokenSurface, Some("(")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "tuple-prefix-evidence",
        TerminalIntervalSelector.BeforeChild("components"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "tuple-commas",
        TerminalIntervalSelector.ChildSeparators("components"),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeCommaTokenSurface, Some(",")),
        OccurrenceCardinality.Repeated(1, None),
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "tuple-separator-evidence",
        TerminalIntervalSelector.ChildSeparators("components"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.Repeated(1, None),
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "tuple-right-parenthesis",
        TerminalIntervalSelector.CompilerScannerToken(
          ParserScannerTokenKind.RightParenthesis,
          ScannerTokenOccurrence.Last
        ),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeRightParenthesisTokenSurface, Some(")")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "tuple-suffix-evidence",
        TerminalIntervalSelector.AfterChild("components"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = TupleTypeSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = TupleTypeAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "tuple",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.TupleType,
            TupleTypeSurface,
            TupleTypeAccessors
          ),
          outputComposite(
            "types",
            Some("tuple"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary
                .ChildStart("components", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly),
              OutputBoundary
                .ChildEnd("components", ChildOccurrenceSelector.Last, PositionProvenancePolicy.SourceDerivedOnly)
            ),
            PsiOutputRoleId.TupleTypes,
            TupleTypesSurface,
            TupleTypesAccessors
          )
        ),
        Map("components" -> Some("types"))
      )
    ),
    outputRoleId = None
  )

  private val namedTupleTypeProduction = tupleTypeProduction.copy(
    id = "named-tuple-type",
    grammarRoleId = GrammarRoleId.NamedTupleType,
    pattern = tupleTypeProduction.pattern.copy(fields =
      Vector(
        CompilerFieldPattern("trees", CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("NamedArg")))
      )
    ),
    children = Vector(
      ChildDeclaration(
        "components",
        "trees",
        ChildCardinality.Repeated(1, None),
        "named-tuple-component"
      )
    ),
    terminals = tupleTypeProduction.terminals.map:
      case terminal if terminal.id == "tuple-commas" =>
        terminal.copy(cardinality = OccurrenceCardinality.Repeated(0, None))
      case terminal                                  => terminal,
    targetSurfaceId = NamedTupleTypeSurface,
    accessors = NamedTupleTypeAccessors,
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "tuple",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.NamedTupleType,
            NamedTupleTypeSurface,
            NamedTupleTypeAccessors
          )
        ),
        Map("components" -> Some("tuple"))
      )
    )
  )

  private val namedTupleComponentProduction = Scala3PsiProduction(
    id = "named-tuple-component",
    grammarRoleId = GrammarRoleId.NamedTupleComponent,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "NamedArg",
      Vector(
        CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)),
        CompilerFieldPattern("arg", CatalogValuePattern.Node)
      ),
      OwnerTypeAnchors.map: anchor =>
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchor(
            InventoryKind.Node,
            "Tuple",
            Vector(CatalogPathSegment.NamedField("trees"), CatalogPathSegment.RepeatedElement),
            anchor
          ),
          SourceClassification.SourceReachable
        )
    ),
    dispositions = Vector(
      FieldDisposition("name", FieldDispositionKind.TerminalOrLayout),
      FieldDisposition("arg", FieldDispositionKind.Child)
    ),
    children = Vector(compoundChild("component-type", "arg", ChildCardinality.ExactlyOne)),
    terminals = Vector(
      TerminalDeclaration(
        "component-colon",
        TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.Colon),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeColonTokenSurface, Some(":")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "component-prefix-evidence",
        TerminalIntervalSelector.BeforeChild("component-type"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = NamedTupleComponentSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = NamedTupleComponentAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRoleId = Some(PsiOutputRoleId.NamedTupleComponent)
  )

  private def functionOutputTemplate(parameterParent: Option[String]): LocalOutputCompositeTemplate =
    val parameterComposites = parameterParent.toVector.flatMap:
      case "parenthesized" =>
        Vector(
          outputComposite(
            "parenthesized",
            Some("function"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.ProductionStart(),
              OutputBoundary.Advance(
                OutputBoundary.EvidenceBoundaryAfterChild(
                  "arguments",
                  ChildOccurrenceSelector.Last,
                  "result",
                  ChildOccurrenceSelector.First,
                  Vector(")"),
                  PositionProvenancePolicy.SourceDerivedOnly
                ),
                1
              )
            ),
            PsiOutputRoleId.ParenthesizedType,
            ParenthesizedTypeSurface,
            ParenthesizedTypeAccessors
          )
        )
      case "tuple"         =>
        Vector(
          outputComposite(
            "tuple",
            Some("function"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.ProductionStart(),
              OutputBoundary.Advance(
                OutputBoundary.EvidenceBoundaryAfterChild(
                  "arguments",
                  ChildOccurrenceSelector.Last,
                  "result",
                  ChildOccurrenceSelector.First,
                  Vector(")"),
                  PositionProvenancePolicy.SourceDerivedOnly
                ),
                1
              )
            ),
            PsiOutputRoleId.TupleType,
            TupleTypeSurface,
            TupleTypeAccessors
          ),
          outputComposite(
            "types",
            Some("tuple"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary
                .ChildStart("arguments", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly),
              OutputBoundary
                .ChildEnd("arguments", ChildOccurrenceSelector.Last, PositionProvenancePolicy.SourceDerivedOnly)
            ),
            PsiOutputRoleId.TupleTypes,
            TupleTypesSurface,
            TupleTypesAccessors
          )
        )
      case other           => throw IllegalArgumentException(other)
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "function",
          None,
          OutputRangeDeclaration.CompilerPosition,
          PsiOutputRoleId.FunctionType,
          FunctionTypeSurface,
          FunctionTypeAccessors
        )
      ) ++ parameterComposites,
      Map(
        "arguments" -> parameterParent.map:
          case "tuple" => "types"
          case other   => other,
        "result"    -> Some("function")
      )
    )

  private[psiproducer] def functionTypeOccurrences(kinds: Vector[ParserScannerTokenKind]) =
    (typeAtomOccurrences ++ compoundTypeArgumentOccurrences).flatMap: occurrence =>
      kinds.map(kind => occurrence.copy(scannerEvidence = ScannerEvidencePattern(required = Set(kind))))

  private val ordinaryFunctionArrowKinds =
    Vector(ParserScannerTokenKind.FunctionArrow, ParserScannerTokenKind.ContextFunctionArrow)

  private val pureFunctionArrowKindValues =
    Vector(ParserScannerTokenKind.PureFunctionArrow, ParserScannerTokenKind.ContextPureFunctionArrow)

  private[psiproducer] def pureFunctionArrowKinds: Vector[ParserScannerTokenKind] = pureFunctionArrowKindValues

  private lazy val functionTypeProduction = Scala3PsiProduction(
    id = "ordinary-function-type",
    grammarRoleId = GrammarRoleId.FunctionType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Function",
      Vector(
        CompilerFieldPattern("args", CatalogValuePattern.Repeated(CatalogValuePattern.NodeExceptPrefix("ValDef"))),
        CompilerFieldPattern("body", CatalogValuePattern.Node)
      ),
      functionTypeOccurrences(ordinaryFunctionArrowKinds),
      Vector(DirectNodeFieldEvidence("body", SourceClassification.SourceReachable))
    ),
    dispositions = Vector(
      FieldDisposition("args", FieldDispositionKind.Child),
      FieldDisposition("body", FieldDispositionKind.Child)
    ),
    children = Vector(
      compoundChild("arguments", "args", ChildCardinality.Repeated(1, None)),
      compoundChild("result", "body", ChildCardinality.ExactlyOne)
    ),
    terminals = Vector(
      TerminalDeclaration(
        "function-left-parenthesis",
        TerminalIntervalSelector.BeforeChild("arguments"),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeLeftParenthesisTokenSurface, Some("(")),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "function-prefix-evidence",
        TerminalIntervalSelector.BeforeChild("arguments"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "function-commas",
        TerminalIntervalSelector.ChildSeparators("arguments"),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeCommaTokenSurface, Some(",")),
        OccurrenceCardinality.Repeated(0, None),
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "function-separator-evidence",
        TerminalIntervalSelector.ChildSeparators("arguments"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.Repeated(0, None),
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "function-right-parenthesis",
        TerminalIntervalSelector.ChildGap("arguments", "result"),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeRightParenthesisTokenSurface, Some(")")),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "ordinary-arrow",
        TerminalIntervalSelector.CompilerScannerTokenInChildGap(
          ParserScannerTokenKind.FunctionArrow,
          "arguments",
          "result"
        ),
        TerminalLeafTarget.Token(NativePsiElementBindings.FunctionArrowTokenSurface, None),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "context-arrow",
        TerminalIntervalSelector.CompilerScannerTokenInChildGap(
          ParserScannerTokenKind.ContextFunctionArrow,
          "arguments",
          "result"
        ),
        TerminalLeafTarget.Token(NativePsiElementBindings.ContextFunctionArrowTokenSurface, None),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "function-arrow-evidence",
        TerminalIntervalSelector.ChildGap("arguments", "result"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = FunctionTypeSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = FunctionTypeAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRoleId = None,
    outputRealizations = Vector(
      OutputRealization(
        "single-direct",
        Vector.empty,
        LocalOutputCompositeTemplate(
          Vector(
            outputComposite(
              "function",
              None,
              OutputRangeDeclaration.CompilerPosition,
              PsiOutputRoleId.FunctionType,
              FunctionTypeSurface,
              FunctionTypeAccessors
            )
          ),
          Map("arguments" -> Some("function"), "result" -> Some("function"))
        ),
        Vector(
          EvidenceCondition.RepeatedFieldSize("args", 1, Some(1)),
          EvidenceCondition.ProductionStartsWith(ClosedSourceLexicalKind.LeftParenthesis, present = false)
        )
      ),
      OutputRealization(
        "single-parenthesized",
        Vector.empty,
        functionOutputTemplate(Some("parenthesized")),
        Vector(
          EvidenceCondition.RepeatedFieldSize("args", 1, Some(1)),
          EvidenceCondition.ProductionStartsWith(ClosedSourceLexicalKind.LeftParenthesis, present = true)
        )
      ),
      OutputRealization(
        "multiple",
        Vector.empty,
        functionOutputTemplate(Some("tuple")),
        Vector(EvidenceCondition.RepeatedFieldSize("args", 2, None))
      )
    )
  )

  private val dependentFunctionTypeProduction = functionTypeProduction.copy(
    id = "dependent-function-type",
    grammarRoleId = GrammarRoleId.DependentFunctionType,
    pattern = functionTypeProduction.pattern.copy(fields =
      Vector(
        CompilerFieldPattern("args", CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef"))),
        CompilerFieldPattern("body", CatalogValuePattern.Node)
      )
    ),
    children = Vector(
      ChildDeclaration(
        "parameters",
        "args",
        ChildCardinality.Repeated(1, None),
        "dependent-function-parameter"
      ),
      compoundChild("result", "body", ChildCardinality.ExactlyOne)
    ),
    terminals = functionTypeProduction.terminals.map: terminal =>
      terminal.copy(selector = terminal.selector match
        case TerminalIntervalSelector.BeforeChild("arguments")                                    =>
          TerminalIntervalSelector.BeforeChild("parameters")
        case TerminalIntervalSelector.AfterChild("arguments")                                     =>
          TerminalIntervalSelector.AfterChild("parameters")
        case TerminalIntervalSelector.ChildSeparators("arguments")                                =>
          TerminalIntervalSelector.ChildSeparators("parameters")
        case TerminalIntervalSelector.ChildGap("arguments", "result")                             =>
          TerminalIntervalSelector.ChildGap("parameters", "result")
        case TerminalIntervalSelector.CompilerScannerTokenInChildGap(kind, "arguments", "result") =>
          TerminalIntervalSelector.CompilerScannerTokenInChildGap(kind, "parameters", "result")
        case other                                                                                => other
      ),
    targetSurfaceId = DependentFunctionTypeSurface,
    accessors = DependentFunctionTypeAccessors,
    outputRealizations = Vector.empty,
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "function",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.DependentFunctionType,
            DependentFunctionTypeSurface,
            DependentFunctionTypeAccessors
          ),
          outputComposite(
            "clause",
            Some("function"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.ProductionStart(),
              OutputBoundary.EvidenceBoundaryAfterChild(
                "parameters",
                ChildOccurrenceSelector.Last,
                "result",
                ChildOccurrenceSelector.First,
                Vector("=>", "?=>"),
                PositionProvenancePolicy.SourceDerivedOnly
              )
            ),
            PsiOutputRoleId.ParameterClause,
            ParameterClauseSurface,
            Vector.empty
          )
        ),
        Map("parameters" -> Some("clause"), "result" -> Some("function"))
      )
    )
  )

  private val dependentFunctionParameterProduction = Scala3PsiProduction(
    id = "dependent-function-parameter",
    grammarRoleId = GrammarRoleId.TermParameter,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "ValDef",
      Vector(
        CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)),
        CompilerFieldPattern("tpt", CatalogValuePattern.Node),
        CompilerFieldPattern("preRhs", CatalogValuePattern.Node),
        CompilerFieldPattern("mods", emptyModifiers(259L))
      ),
      OwnerTypeAnchors.map: anchor =>
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchor(
            InventoryKind.Node,
            "Function",
            Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
            anchor
          ),
          SourceClassification.SourceReachable
        )
    ),
    dispositions = Vector(
      FieldDisposition("name", FieldDispositionKind.TerminalOrLayout),
      FieldDisposition("tpt", FieldDispositionKind.Child),
      FieldDisposition("preRhs", FieldDispositionKind.Child),
      FieldDisposition("mods", FieldDispositionKind.Child)
    ),
    children = Vector(
      compoundChild("declared-type", "tpt", ChildCardinality.ExactlyOne),
      ChildDeclaration("default", "preRhs", ChildCardinality.ExactlyOne, "template-absent-tree"),
      ChildDeclaration("modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
    ),
    terminals = Vector(
      TerminalDeclaration(
        "parameter-colon",
        TerminalIntervalSelector.BeforeChild("declared-type"),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeColonTokenSurface, Some(":")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "parameter-prefix-evidence",
        TerminalIntervalSelector.BeforeChild("declared-type"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ParameterSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = ParameterAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRoleId = None,
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "parameter",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.Parameter,
            ParameterSurface,
            ParameterAccessors
          ),
          outputComposite(
            "parameter-type",
            Some("parameter"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary
                .ChildStart("declared-type", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly),
              OutputBoundary
                .ChildEnd("declared-type", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly)
            ),
            PsiOutputRoleId.ParameterType,
            ParameterTypeSurface,
            ParameterTypeAccessors,
            TargetRequirement.Native
          )
        ),
        Map("declared-type" -> Some("parameter-type"), "default" -> None, "modifiers" -> Some("parameter"))
      )
    )
  )

  private val polyFunctionTypeProduction = Scala3PsiProduction(
    id = "polymorphic-function-type",
    grammarRoleId = GrammarRoleId.PolyFunctionType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "PolyFunction",
      Vector(
        CompilerFieldPattern("targs", CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("TypeDef"))),
        CompilerFieldPattern("body", CatalogValuePattern.Node)
      ),
      typeAtomOccurrences ++ compoundTypeArgumentOccurrences
    ),
    dispositions = Vector(
      FieldDisposition("targs", FieldDispositionKind.Child),
      FieldDisposition("body", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "parameters",
        "targs",
        ChildCardinality.Repeated(1, None),
        "function-unbounded-type-parameter",
        Set("function-context-bounded-type-parameter", "higher-kinded-nested-type-parameter")
      ),
      compoundChild("result", "body", ChildCardinality.ExactlyOne)
    ),
    terminals = Vector(
      TerminalDeclaration(
        "poly-function-left-bracket",
        TerminalIntervalSelector.BeforeChild("parameters"),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeArgumentLeftTokenSurface, Some("[")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "poly-function-prefix-evidence",
        TerminalIntervalSelector.BeforeChild("parameters"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "poly-function-right-bracket",
        TerminalIntervalSelector.ChildGap("parameters", "result"),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeArgumentRightTokenSurface, Some("]")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "poly-function-arrow",
        TerminalIntervalSelector.ChildGap("parameters", "result"),
        TerminalLeafTarget.Token(NativePsiElementBindings.FunctionArrowTokenSurface, Some("=>")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "poly-function-commas",
        TerminalIntervalSelector.ChildSeparators("parameters"),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeCommaTokenSurface, Some(",")),
        OccurrenceCardinality.Repeated(0, None),
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "poly-function-separator-evidence",
        TerminalIntervalSelector.ChildSeparators("parameters"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.Repeated(0, None),
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "poly-function-arrow-evidence",
        TerminalIntervalSelector.ChildGap("parameters", "result"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = PolyFunctionTypeSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = PolyFunctionTypeAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRoleId = None,
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "function",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.PolyFunctionType,
            PolyFunctionTypeSurface,
            PolyFunctionTypeAccessors
          ),
          outputComposite(
            "clause",
            Some("function"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.PreviousSignificantChildTokenStartWithinOwner(
                "parameters",
                ChildOccurrenceSelector.First,
                PositionProvenancePolicy.SourceDerivedOnly
              ),
              OutputBoundary.Advance(
                OutputBoundary.EvidenceBoundaryAfterChild(
                  "parameters",
                  ChildOccurrenceSelector.Last,
                  "result",
                  ChildOccurrenceSelector.First,
                  Vector("]"),
                  PositionProvenancePolicy.SourceDerivedOnly
                ),
                1
              )
            ),
            PsiOutputRoleId.TypeParameterClause,
            TypeParameterClauseSurface,
            Vector.empty
          )
        ),
        Map("parameters" -> Some("clause"), "result" -> Some("function"))
      )
    )
  )

  private val byNameParameterTypeProduction = Scala3PsiProduction(
    id = "by-name-parameter-type",
    grammarRoleId = GrammarRoleId.ByNameParameterType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "ByNameTypeTree",
      Vector(CompilerFieldPattern("result", CatalogValuePattern.Node)),
      dependentParameterTypeOccurrences.map(
        _.copy(scannerEvidence = ScannerEvidencePattern(required = Set(ParserScannerTokenKind.FunctionArrow)))
      ),
      Vector(DirectNodeFieldEvidence("result", SourceClassification.SourceReachable))
    ),
    dispositions = Vector(FieldDisposition("result", FieldDispositionKind.Child)),
    children = Vector(compoundChild("result", "result", ChildCardinality.ExactlyOne)),
    terminals = Vector(
      TerminalDeclaration(
        "by-name-arrow",
        TerminalIntervalSelector.BeforeChild("result"),
        TerminalLeafTarget.Token(NativePsiElementBindings.FunctionArrowTokenSurface, Some("=>")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal,
        ownsStructuralEvidence = Some(false)
      ),
      TerminalDeclaration(
        "by-name-prefix-evidence",
        TerminalIntervalSelector.BeforeChild("result"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ParameterTypeSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = ParameterTypeAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(transparentTemplate("result")),
    outputRoleId = None
  )

  private[psiproducer] def ordinaryFunctionTypeProduction: Scala3PsiProduction = functionTypeProduction

  private[psiproducer] def byNameParameterType: Scala3PsiProduction = byNameParameterTypeProduction

  private val repeatedParameterTypeProduction = Scala3PsiProduction(
    id = "repeated-parameter-type",
    grammarRoleId = GrammarRoleId.RepeatedParameterType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "PostfixOp",
      Vector(
        CompilerFieldPattern("od", CatalogValuePattern.Node),
        CompilerFieldPattern("op", CatalogValuePattern.NodePrefix("Ident"))
      ),
      dependentParameterTypeOccurrences
    ),
    dispositions = Vector(
      FieldDisposition("od", FieldDispositionKind.Child),
      FieldDisposition("op", FieldDispositionKind.SemanticOnly)
    ),
    children = Vector(compoundChild("operand", "od", ChildCardinality.ExactlyOne)),
    terminals = Vector(
      TerminalDeclaration(
        "repeated-parameter-star",
        TerminalIntervalSelector.CompilerScannerToken(
          ParserScannerTokenKind.Identifier,
          ScannerTokenOccurrence.Last
        ),
        TerminalLeafTarget.Token(NativePsiElementBindings.VarianceTokenSurface, Some("*")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ParameterTypeSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = ParameterTypeAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(transparentTemplate("operand")),
    outputRoleId = None
  )

  private val repeatedParameterSyntheticStarProduction = Scala3PsiProduction(
    id = "repeated-parameter-synthetic-star",
    grammarRoleId = GrammarRoleId.RepeatedParameterStar,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "PostfixOp",
            Vector(CatalogPathSegment.NamedField("op"))
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
    targetSurfaceId = ParameterTypeSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    navigation = None,
    outputTemplate = Some(transparentTemplate()),
    outputRoleId = None
  )

  private[psiproducer] val TupleFunctionPrefixSegment: Vector[Scala3PsiProduction] = Vector(
    tupleTypeProduction,
    namedTupleTypeProduction,
    namedTupleComponentProduction,
    functionTypeProduction
  )

  private[psiproducer] val TupleFunctionMiddleSegment: Vector[Scala3PsiProduction] = Vector(
    dependentFunctionTypeProduction,
    dependentFunctionParameterProduction,
    polyFunctionTypeProduction,
    byNameParameterTypeProduction
  )

  private[psiproducer] val TupleFunctionSuffixSegment: Vector[Scala3PsiProduction] = Vector(
    repeatedParameterTypeProduction,
    repeatedParameterSyntheticStarProduction
  )
