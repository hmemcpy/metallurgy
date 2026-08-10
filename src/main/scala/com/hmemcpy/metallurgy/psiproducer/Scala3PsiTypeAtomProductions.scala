package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiTypeAtomProductions:
  private val integerLiteralProduction = Scala3PsiProduction(
    id = "integer-literal-number",
    grammarRoleId = GrammarRoleId.IntegerLiteral,
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
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "InfixOp",
            Vector(CatalogPathSegment.NamedField("right"))
          ),
          SourceClassification.SourceReachable
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("digits", FieldDispositionKind.TerminalOrLayout),
      FieldDisposition("kind", FieldDispositionKind.TerminalOrLayout)
    ),
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "integer-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = "org/jetbrains/plugins/scala/lang/psi/impl/base/literals/ScIntegerLiteralImpl",
    targetRequirement = TargetRequirement.NativeCandidate,
    accessors = Vector(
      AccessorObligation(
        "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#getValue()Ljava/lang/Object;",
        required = true,
        surfaceKind = SurfaceFactKind.Method
      ),
      AccessorObligation(
        "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#contentText()Ljava/lang/String;",
        required = true,
        surfaceKind = SurfaceFactKind.Method
      ),
      AccessorObligation(
        "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#contentRangeInParent()Lcom/intellij/openapi/util/TextRange;",
        required = true,
        surfaceKind = SurfaceFactKind.Method
      ),
      AccessorObligation(
        "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#isSimpleLiteral()Z",
        required = true,
        surfaceKind = SurfaceFactKind.Method
      ),
      AccessorObligation(
        "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#literalType()Lorg/jetbrains/plugins/scala/lang/psi/types/ScType;",
        required = true,
        surfaceKind = SurfaceFactKind.Method
      )
    ),
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRoleId = Some(PsiOutputRoleId.IntegerLiteral)
  )

  private[psiproducer] val IntegerLiteralSegment: Vector[Scala3PsiProduction] = Vector(integerLiteralProduction)

  private val SingletonReferenceProductionIds = Set(
    "type-atom-singleton-reference-ident",
    "type-atom-singleton-reference-select"
  )
  private val LiteralValueProductionIds       = Set(
    "type-atom-literal-value-integer",
    "type-atom-literal-value-long",
    "type-atom-literal-value-float",
    "type-atom-literal-value-double",
    "type-atom-literal-value-char",
    "type-atom-literal-value-string",
    "type-atom-literal-value-boolean"
  )

  private val singletonReferenceOccurrences = (GivenSelectorBoundAnchor +: OwnerTypeAnchors).map(anchor =>
    CompilerProductionContextPattern(
      ContextPattern.ParentUnderAnchor(
        InventoryKind.Node,
        "SingletonTypeTree",
        Vector(CatalogPathSegment.NamedField("ref")),
        anchor
      ),
      SourceClassification.SourceReachable
    )
  )

  private val singletonTypeOccurrences = typeAtomOccurrences.flatMap: occurrence =>
    Vector(occurrence, occurrence.copy(sourceClassification = SourceClassification.Synthetic))

  private val literalTypeOccurrences = typeAtomOccurrences.map:
    _.copy(sourceClassification = SourceClassification.Synthetic)

  private def literalValueProduction(
      id: String,
      scalarKind: String,
      outputRole: PsiOutputRoleId,
      surface: String
  ): Scala3PsiProduction = Scala3PsiProduction(
    id = id,
    grammarRoleId = GrammarRoleId.LiteralValue,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Literal",
      Vector(
        CompilerFieldPattern(
          "const",
          CatalogValuePattern.Product("", Vector(CompilerFieldPattern("", CatalogValuePattern.Scalar(scalarKind))))
        )
      ),
      singletonReferenceOccurrences
    ),
    dispositions = Vector(FieldDisposition("const", FieldDispositionKind.TerminalOrLayout)),
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "literal-value-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = surface,
    targetRequirement = TargetRequirement.Native,
    accessors = LiteralValueAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRoleId = Some(outputRole)
  )

  private[psiproducer] val TypeAtomSegment: Vector[Scala3PsiProduction] = Vector(
    Scala3PsiProduction(
      id = "type-atom-projection",
      grammarRoleId = GrammarRoleId.TypeProjection,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Select",
        Vector(
          CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
          CompilerFieldPattern("name", CatalogValuePattern.Name)
        ),
        typeAtomOccurrences.map(
          _.copy(scannerEvidence =
            ScannerEvidencePattern(
              required = Set(ParserScannerTokenKind.Hash)
            )
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
          "import-selector-given-bound-qualifier-ident",
          GivenTypeQualifierProductionIds - "import-selector-given-bound-qualifier-ident"
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
          TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.Hash),
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
    ),
    Scala3PsiProduction(
      id = "type-atom-singleton-ident",
      grammarRoleId = GrammarRoleId.SingletonType,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "SingletonTypeTree",
        Vector(CompilerFieldPattern("ref", CatalogValuePattern.NodePrefix("Ident"))),
        singletonTypeOccurrences.map(
          _.copy(scannerEvidence =
            ScannerEvidencePattern(
              required = Set(ParserScannerTokenKind.Dot, ParserScannerTokenKind.TypeKeyword),
              forbidden = Set(ParserScannerTokenKind.Hash)
            )
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
          SingletonReferenceProductionIds - "type-atom-singleton-reference-ident"
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "singleton-dot",
          TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.Dot),
          TerminalLeafTarget.Token(NativePsiElementBindings.TypePathDotTokenSurface, Some(".")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "singleton-type",
          TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.TypeKeyword),
          TerminalLeafTarget.Token(NativePsiElementBindings.SingletonTypeKeywordTokenSurface, Some("type")),
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
      outputTemplate = Some(
        typeElementTemplateWithRange(
          PsiOutputRoleId.SingletonType,
          SimpleTypeSurface,
          SimpleTypeAccessors,
          OutputRangeDeclaration.CompilerPositionWithPolicy(PositionProvenancePolicy.PositionedIncludingSynthetic),
          "reference"
        )
      )
    ),
    Scala3PsiProduction(
      id = "type-atom-singleton-select",
      grammarRoleId = GrammarRoleId.SingletonType,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "SingletonTypeTree",
        Vector(CompilerFieldPattern("ref", CatalogValuePattern.NodePrefix("Select"))),
        singletonTypeOccurrences.map(
          _.copy(scannerEvidence =
            ScannerEvidencePattern(
              required = Set(ParserScannerTokenKind.Dot, ParserScannerTokenKind.TypeKeyword),
              forbidden = Set(ParserScannerTokenKind.Hash)
            )
          )
        )
      ),
      dispositions = Vector(FieldDisposition("ref", FieldDispositionKind.Child)),
      children = Vector(
        ChildDeclaration(
          "reference",
          "ref",
          ChildCardinality.ExactlyOne,
          "type-atom-singleton-reference-select",
          SingletonReferenceProductionIds - "type-atom-singleton-reference-select"
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "singleton-dot",
          TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.Dot, ScannerTokenOccurrence.Last),
          TerminalLeafTarget.Token(NativePsiElementBindings.TypePathDotTokenSurface, Some(".")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "singleton-type",
          TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.TypeKeyword),
          TerminalLeafTarget.Token(NativePsiElementBindings.SingletonTypeKeywordTokenSurface, Some("type")),
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
      outputTemplate = Some(
        typeElementTemplateWithRange(
          PsiOutputRoleId.SingletonType,
          SimpleTypeSurface,
          SimpleTypeAccessors,
          OutputRangeDeclaration.CompilerPositionWithPolicy(PositionProvenancePolicy.PositionedIncludingSynthetic),
          "reference"
        )
      )
    ),
    Scala3PsiProduction(
      id = "type-atom-literal",
      grammarRoleId = GrammarRoleId.LiteralType,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "SingletonTypeTree",
        Vector(CompilerFieldPattern("ref", CatalogValuePattern.NodePrefix("Literal"))),
        literalTypeOccurrences.map(
          _.copy(scannerEvidence =
            ScannerEvidencePattern(
              required = Set(ParserScannerTokenKind.Literal),
              forbidden = Set(ParserScannerTokenKind.Hash, ParserScannerTokenKind.TypeKeyword)
            )
          )
        )
      ),
      dispositions = Vector(FieldDisposition("ref", FieldDispositionKind.Child)),
      children = Vector(
        ChildDeclaration(
          "literal",
          "ref",
          ChildCardinality.ExactlyOne,
          "type-atom-literal-value-integer",
          LiteralValueProductionIds - "type-atom-literal-value-integer"
        )
      ),
      terminals = Vector.empty,
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = LiteralTypeSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = LiteralTypeAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(
        typeElementTemplateWithRange(
          PsiOutputRoleId.LiteralType,
          LiteralTypeSurface,
          LiteralTypeAccessors,
          OutputRangeDeclaration.CompilerPositionWithPolicy(PositionProvenancePolicy.PositionedIncludingSynthetic),
          "literal"
        )
      )
    ),
    Scala3PsiProduction(
      id = "type-atom-parenthesized",
      grammarRoleId = GrammarRoleId.ParenthesizedType,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Parens",
        Vector(CompilerFieldPattern("t", CatalogValuePattern.Node)),
        typeAtomOccurrences.map(
          _.copy(scannerEvidence =
            ScannerEvidencePattern(
              required = Set(ParserScannerTokenKind.LeftParenthesis, ParserScannerTokenKind.RightParenthesis)
            )
          )
        )
      ),
      dispositions = Vector(FieldDisposition("t", FieldDispositionKind.Child)),
      children = Vector(compoundChild("inner", "t", ChildCardinality.ExactlyOne)),
      terminals = Vector(
        TerminalDeclaration(
          "left-parenthesis",
          TerminalIntervalSelector
            .CompilerScannerToken(ParserScannerTokenKind.LeftParenthesis, ScannerTokenOccurrence.First),
          TerminalLeafTarget.Token(NativePsiElementBindings.TypeLeftParenthesisTokenSurface, Some("(")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "right-parenthesis",
          TerminalIntervalSelector
            .CompilerScannerToken(ParserScannerTokenKind.RightParenthesis, ScannerTokenOccurrence.Last),
          TerminalLeafTarget.Token(NativePsiElementBindings.TypeRightParenthesisTokenSurface, Some(")")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = ParenthesizedTypeSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = ParenthesizedTypeAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = None,
      outputTemplate = Some(
        typeElementTemplate(
          PsiOutputRoleId.ParenthesizedType,
          ParenthesizedTypeSurface,
          ParenthesizedTypeAccessors,
          "inner"
        )
      )
    ),
    Scala3PsiProduction(
      id = "type-atom-singleton-reference-ident",
      grammarRoleId = GrammarRoleId.StableReference,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Ident",
        Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
        singletonReferenceOccurrences
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
      outputRoleId = Some(PsiOutputRoleId.StableReference)
    ),
    Scala3PsiProduction(
      id = "type-atom-singleton-reference-select",
      grammarRoleId = GrammarRoleId.StableReference,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Select",
        Vector(
          CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
          CompilerFieldPattern("name", CatalogValuePattern.Name)
        ),
        singletonReferenceOccurrences
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
          "import-selector-given-bound-qualifier-ident",
          GivenTypeQualifierProductionIds - "import-selector-given-bound-qualifier-ident"
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
          TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.Dot),
          TerminalLeafTarget.Token(NativePsiElementBindings.TypePathDotTokenSurface, Some(".")),
          OccurrenceCardinality.Repeated(1, None),
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
    ),
    literalValueProduction(
      "type-atom-literal-value-integer",
      "Integer",
      PsiOutputRoleId.IntegerLiteralValue,
      IntegerLiteralSurface
    ),
    literalValueProduction(
      "type-atom-literal-value-long",
      "LongInteger",
      PsiOutputRoleId.LongLiteralValue,
      LongLiteralSurface
    ),
    literalValueProduction(
      "type-atom-literal-value-float",
      "FloatDecimal",
      PsiOutputRoleId.FloatLiteralValue,
      FloatLiteralSurface
    ),
    literalValueProduction(
      "type-atom-literal-value-double",
      "Decimal",
      PsiOutputRoleId.DoubleLiteralValue,
      DoubleLiteralSurface
    ),
    literalValueProduction(
      "type-atom-literal-value-char",
      "Character",
      PsiOutputRoleId.CharLiteralValue,
      CharLiteralSurface
    ),
    literalValueProduction(
      "type-atom-literal-value-string",
      "Text",
      PsiOutputRoleId.StringLiteralValue,
      StringLiteralSurface
    ),
    literalValueProduction(
      "type-atom-literal-value-boolean",
      "Logical",
      PsiOutputRoleId.BooleanLiteralValue,
      BooleanLiteralSurface
    )
  )
