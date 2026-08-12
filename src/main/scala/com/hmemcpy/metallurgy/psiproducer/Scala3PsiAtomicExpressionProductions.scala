package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiAtomicExpressionProductions:
  private val directOwnerOccurrences = Vector("DefDef", "ValDef").flatMap: owner =>
    Vector("PackageDef" -> "stats", "Template" -> "preBody").map: (ancestor, field) =>
      CompilerProductionContextPattern(
        ContextPattern.ParentWithAncestor(
          InventoryKind.Node,
          owner,
          Vector(CatalogPathSegment.NamedField("preRhs")),
          InventoryAncestor(
            InventoryKind.Node,
            ancestor,
            Vector(CatalogPathSegment.NamedField(field), CatalogPathSegment.RepeatedElement)
          )
        ),
        SourceClassification.SourceReachable
      )

  private def atomicExpression(
      id: String,
      grammarRole: GrammarRoleId,
      prefix: String,
      fields: Vector[CompilerFieldPattern],
      outputRole: PsiOutputRoleId,
      surface: String,
      accessors: Vector[AccessorObligation],
      tokenSurface: Option[String] = None,
      scannerEvidence: ScannerEvidencePattern = ScannerEvidencePattern()
  ): Scala3PsiProduction =
    Scala3PsiProduction(
      id = id,
      grammarRoleId = grammarRole,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        prefix,
        fields,
        directOwnerOccurrences.map(_.copy(scannerEvidence = scannerEvidence))
      ),
      dispositions = fields.map(field => FieldDisposition(field.name, FieldDispositionKind.TerminalOrLayout)),
      children = Vector.empty,
      terminals = Vector(
        TerminalDeclaration(
          s"$id-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ) ++ tokenSurface.map(surfaceId =>
        TerminalDeclaration(
          s"$id-token",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(surfaceId),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = surface,
      targetRequirement = TargetRequirement.Native,
      accessors = accessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = Some(outputRole)
    )

  private def literal(
      id: String,
      grammarRole: GrammarRoleId,
      scalarKind: String,
      outputRole: PsiOutputRoleId,
      surface: String,
      tokenSurface: Option[String],
      forbidLeadingSign: Boolean = false
  ): Scala3PsiProduction =
    atomicExpression(
      id,
      grammarRole,
      "Literal",
      Vector(
        CompilerFieldPattern(
          "const",
          CatalogValuePattern.Product("", Vector(CompilerFieldPattern("", CatalogValuePattern.Scalar(scalarKind))))
        )
      ),
      outputRole,
      surface,
      AtomicLiteralAccessors,
      tokenSurface,
      ScannerEvidencePattern(
        forbidden = Option.when(forbidLeadingSign)(ParserScannerTokenKind.Identifier).toSet
      )
    )

  private val termReference = atomicExpression(
    "atomic-term-ident",
    GrammarRoleId.TermReference,
    "Ident",
    Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
    PsiOutputRoleId.TermReference,
    ReferenceExpressionSurface,
    TermReferenceAccessors
  )

  private val integerLiteral = atomicExpression(
    "atomic-literal-integer",
    GrammarRoleId.ExpressionIntegerLiteral,
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
    PsiOutputRoleId.IntegerExpression,
    IntegerLiteralSurface,
    AtomicLiteralAccessors,
    Some(NativePsiElementBindings.IntegerLiteralTokenSurface),
    ScannerEvidencePattern(forbidden = Set(ParserScannerTokenKind.Identifier))
  )

  private val nullLiteral = atomicExpression(
    "atomic-literal-null",
    GrammarRoleId.ExpressionNullLiteral,
    "Literal",
    Vector(
      CompilerFieldPattern(
        "const",
        CatalogValuePattern.Product(
          "",
          Vector(CompilerFieldPattern("", CatalogValuePattern.Scalar("NullValue")))
        )
      )
    ),
    PsiOutputRoleId.NullExpression,
    NullLiteralSurface,
    AtomicLiteralAccessors
  )

  private val thisQualifierOccurrences = Vector("DefDef", "ValDef").flatMap: owner =>
    val anchor    = InventoryAncestor(
      InventoryKind.Node,
      owner,
      Vector(CatalogPathSegment.NamedField("preRhs"))
    )
    val traversed = Vector(
      InventoryAncestor(InventoryKind.Node, "Select", Vector(CatalogPathSegment.NamedField("qualifier"))),
      InventoryAncestor(InventoryKind.Node, "Super", Vector(CatalogPathSegment.NamedField("qual"))),
      anchor
    )
    Vector(
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchorThrough(
          InventoryKind.Node,
          "This",
          Vector(CatalogPathSegment.NamedField("qual")),
          traversed,
          anchor
        ),
        SourceClassification.SourceReachable
      ),
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchorThrough(
          InventoryKind.Node,
          "This",
          Vector(CatalogPathSegment.NamedField("qual")),
          traversed,
          anchor
        ),
        SourceClassification.Absent
      )
    )

  private val emptyThisQualifier = Scala3PsiProduction(
    id = "atomic-this-empty-qualifier",
    grammarRoleId = GrammarRoleId.AbsentProduct,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Empty))),
      thisQualifierOccurrences.filter(_.sourceClassification == SourceClassification.Absent)
    ),
    dispositions = Vector(FieldDisposition("name", FieldDispositionKind.Synthetic)),
    children = Vector.empty,
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ThisReferenceSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    navigation = None,
    outputTemplate = Some(transparentTemplate()),
    outputRoleId = None
  )

  private val qualifiedThisReference = Scala3PsiProduction(
    id = "atomic-this-qualifier",
    grammarRoleId = GrammarRoleId.StableReference,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
      thisQualifierOccurrences.filter(_.sourceClassification == SourceClassification.SourceReachable)
    ),
    dispositions = Vector(FieldDisposition("name", FieldDispositionKind.SemanticOnly)),
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "qualified-this-name",
        TerminalIntervalSelector.LocalOutput("reference"),
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
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "reference",
            None,
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.ProductionPoint,
              OutputBoundary.ProductionNameEnd
            ),
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

  private def thisExpression(
      id: String,
      role: GrammarRoleId,
      qualifierProduction: String,
      qualifierClassification: SourceClassification,
      qualified: Boolean
  ): Scala3PsiProduction =
    Scala3PsiProduction(
      id = id,
      grammarRoleId = role,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "This",
        Vector(CompilerFieldPattern("qual", CatalogValuePattern.NodePrefix("Ident"))),
        directOwnerOccurrences,
        Vector(DirectNodeFieldEvidence("qual", qualifierClassification))
      ),
      dispositions = Vector(FieldDisposition("qual", FieldDispositionKind.Child)),
      children = Vector(
        ChildDeclaration("qualifier", "qual", ChildCardinality.ExactlyOne, qualifierProduction)
      ),
      terminals = Vector(
        TerminalDeclaration(
          s"$id-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ) ++ Option.when(qualified)(
        TerminalDeclaration(
          "qualified-this-dot",
          TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.Dot),
          TerminalLeafTarget.Token(NativePsiElementBindings.TypePathDotTokenSurface, Some(".")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = ThisReferenceSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = ThisReferenceAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputTemplate = Some(
        LocalOutputCompositeTemplate(
          Vector(
            outputComposite(
              "this",
              None,
              OutputRangeDeclaration.CompilerPosition,
              PsiOutputRoleId.ThisReference,
              ThisReferenceSurface,
              ThisReferenceAccessors
            )
          ),
          Map("qualifier" -> Option.when(qualified)("this"))
        )
      ),
      outputRoleId = None
    )

  private[psiproducer] val AtomicExpressionSegment: Vector[Scala3PsiProduction] = Vector(
    termReference,
    integerLiteral,
    literal(
      "atomic-literal-long",
      GrammarRoleId.ExpressionLongLiteral,
      "LongInteger",
      PsiOutputRoleId.LongExpression,
      LongLiteralSurface,
      Some(NativePsiElementBindings.LongLiteralTokenSurface),
      forbidLeadingSign = true
    ),
    literal(
      "atomic-literal-float",
      GrammarRoleId.ExpressionFloatLiteral,
      "FloatDecimal",
      PsiOutputRoleId.FloatExpression,
      FloatLiteralSurface,
      Some(NativePsiElementBindings.FloatLiteralTokenSurface),
      forbidLeadingSign = true
    ),
    literal(
      "atomic-literal-double",
      GrammarRoleId.ExpressionDoubleLiteral,
      "Decimal",
      PsiOutputRoleId.DoubleExpression,
      DoubleLiteralSurface,
      Some(NativePsiElementBindings.DoubleLiteralTokenSurface),
      forbidLeadingSign = true
    ),
    literal(
      "atomic-literal-boolean",
      GrammarRoleId.ExpressionBooleanLiteral,
      "Logical",
      PsiOutputRoleId.BooleanExpression,
      BooleanLiteralSurface,
      None
    ),
    literal(
      "atomic-literal-char",
      GrammarRoleId.ExpressionCharLiteral,
      "Character",
      PsiOutputRoleId.CharExpression,
      CharLiteralSurface,
      Some(NativePsiElementBindings.CharLiteralTokenSurface)
    ),
    literal(
      "atomic-literal-string",
      GrammarRoleId.ExpressionStringLiteral,
      "Text",
      PsiOutputRoleId.StringExpression,
      StringLiteralSurface,
      Some(NativePsiElementBindings.StringLiteralTokenSurface)
    ),
    nullLiteral,
    thisExpression(
      "atomic-this-unqualified",
      GrammarRoleId.ThisReference,
      "atomic-this-empty-qualifier",
      SourceClassification.Absent,
      qualified = false
    ),
    thisExpression(
      "atomic-this-qualified",
      GrammarRoleId.QualifiedThisReference,
      "atomic-this-qualifier",
      SourceClassification.SourceReachable,
      qualified = true
    ),
    emptyThisQualifier,
    qualifiedThisReference
  )
