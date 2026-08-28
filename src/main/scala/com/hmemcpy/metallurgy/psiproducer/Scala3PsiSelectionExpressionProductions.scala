package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.ParserScannerTokenKind

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiSelectionExpressionProductions:
  private val DefinitionAnchorParents    = Vector("DefDef", "ValDef").flatMap: owner =>
    Vector("PackageDef" -> "stats", "Template" -> "preBody").map: (parent, field) =>
      InventoryAncestor(InventoryKind.Node, owner, Vector(CatalogPathSegment.NamedField("preRhs"))) ->
        InventoryAncestor(
          InventoryKind.Node,
          parent,
          Vector(CatalogPathSegment.NamedField(field), CatalogPathSegment.RepeatedElement)
        )
  private val SelectionQualifierAncestor = InventoryAncestor(
    InventoryKind.Node,
    "Select",
    Vector(CatalogPathSegment.NamedField("qualifier"))
  )

  private val directSelectionOccurrences = Vector("DefDef", "ValDef").flatMap: owner =>
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

  private def selectionQualifierOccurrences(
      owner: String,
      path: Vector[CatalogPathSegment],
      through: Vector[InventoryAncestor] = Vector(SelectionQualifierAncestor)
  ): Vector[CompilerProductionContextPattern] =
    val direct = DefinitionAnchorParents.map: (anchor, parent) =>
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchorThroughWithParent(
          InventoryKind.Node,
          owner,
          path,
          through,
          anchor,
          parent
        ),
        SourceClassification.SourceReachable
      )
    direct ++ Scala3PsiApplicationExpressionProductions.descendantOccurrences(
      owner,
      path,
      SourceClassification.SourceReachable,
      through
    )

  private val recursiveSelectionOccurrences = selectionQualifierOccurrences(
    "Select",
    Vector(CatalogPathSegment.NamedField("qualifier"))
  )

  private def transparentIdent(
      id: String,
      role: GrammarRoleId,
      occurrences: Vector[CompilerProductionContextPattern],
      nameClass: NeutralNameClass,
      output: Boolean
  ): Scala3PsiProduction =
    Scala3PsiProduction(
      id = id,
      grammarRoleId = role,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Ident",
        Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(nameClass))),
        occurrences
      ),
      dispositions = Vector(
        FieldDisposition(
          "name",
          if output then FieldDispositionKind.SemanticOnly else FieldDispositionKind.Synthetic
        )
      ),
      children = Vector.empty,
      terminals =
        if !output then Vector.empty
        else
          Vector(
            TerminalDeclaration(
              s"$id-name",
              TerminalIntervalSelector.LocalOutput("reference"),
              TerminalLeafTarget.Parent,
              OccurrenceCardinality.ExactlyOne,
              PsiOutputRoleId.SourceTerminal
            )
          )
      ,
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = ReferenceExpressionSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = if output then TermReferenceAccessors else Vector.empty,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Option.when(output)(NavigationObligation.Self),
      outputTemplate = Option
        .when(output)(
          LocalOutputCompositeTemplate(
            Vector(
              outputComposite(
                "reference",
                None,
                OutputRangeDeclaration.BoundaryDerived(
                  OutputBoundary.ProductionPoint,
                  OutputBoundary.ProductionNameEnd
                ),
                PsiOutputRoleId.TermReference,
                ReferenceExpressionSurface,
                TermReferenceAccessors
              )
            ),
            Map.empty
          )
        )
        .orElse(Option.when(!output)(transparentTemplate())),
      outputRoleId = None
    )

  private val qualifierIdent = transparentIdent(
    "selection-qualifier-ident",
    GrammarRoleId.SelectionQualifier,
    selectionQualifierOccurrences("Select", Vector(CatalogPathSegment.NamedField("qualifier"))) ++
      Scala3PsiDefinitionPayloadProductions.PositionalCandidateSelectionQualifierOccurrences ++
      Scala3PsiDefinitionPayloadProductions.NamedCandidateSelectionQualifierOccurrences ++
      Scala3PsiDefinitionPayloadProductions.NamedInvokedSelectionQualifierOccurrences ++
      Scala3PsiNamedArgumentProductions.CandidateSelectionQualifierOccurrences ++
      Scala3PsiRepeatedArgumentProductions.CandidateSelectionQualifierOccurrences,
    NeutralNameClass.Ordinary,
    output = true
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
        selectionQualifierOccurrences("Select", Vector(CatalogPathSegment.NamedField("qualifier"))),
        Vector(DirectNodeFieldEvidence("qual", qualifierClassification))
      ),
      dispositions = Vector(FieldDisposition("qual", FieldDispositionKind.Child)),
      children = Vector(ChildDeclaration("qualifier", "qual", ChildCardinality.ExactlyOne, qualifierProduction)),
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
          s"$id-dot",
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

  private val superThisOccurrences = selectionQualifierOccurrences(
    "Super",
    Vector(CatalogPathSegment.NamedField("qual")),
    Vector(SelectionQualifierAncestor)
  ).map(_.copy(sourceClassification = SourceClassification.Synthetic))

  private def superThis(id: String, qualifier: String, qualifierClassification: SourceClassification) =
    Scala3PsiProduction(
      id = id,
      grammarRoleId = GrammarRoleId.SelectionQualifier,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "This",
        Vector(CompilerFieldPattern("qual", CatalogValuePattern.NodePrefix("Ident"))),
        superThisOccurrences,
        Vector(DirectNodeFieldEvidence("qual", qualifierClassification))
      ),
      dispositions = Vector(FieldDisposition("qual", FieldDispositionKind.Child)),
      children = Vector(ChildDeclaration("qualifier", "qual", ChildCardinality.ExactlyOne, qualifier)),
      terminals = Vector.empty,
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = StableReferenceSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = Vector.empty,
      persistence = PersistenceObligations.NotApplicable,
      navigation = None,
      outputTemplate = Some(transparentTemplate("qualifier")),
      outputRoleId = None
    )

  private val superMixinOccurrences = selectionQualifierOccurrences(
    "Super",
    Vector(CatalogPathSegment.NamedField("mix")),
    Vector(SelectionQualifierAncestor)
  )
  private val emptySuperMixin       = transparentIdent(
    "selection-super-empty-mixin",
    GrammarRoleId.AbsentProduct,
    superMixinOccurrences.map(_.copy(sourceClassification = SourceClassification.Absent)),
    NeutralNameClass.Empty,
    output = false
  )
  private val superMixin            = transparentIdent(
    "selection-super-mixin",
    GrammarRoleId.SelectionQualifier,
    superMixinOccurrences,
    NeutralNameClass.Ordinary,
    output = false
  )

  private val superReference = Scala3PsiProduction(
    id = "selection-super-reference",
    grammarRoleId = GrammarRoleId.SuperReference,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Super",
      Vector(
        CompilerFieldPattern("qual", CatalogValuePattern.NodePrefix("This")),
        CompilerFieldPattern("mix", CatalogValuePattern.NodePrefix("Ident"))
      ),
      selectionQualifierOccurrences("Select", Vector(CatalogPathSegment.NamedField("qualifier")))
    ),
    dispositions = Vector(
      FieldDisposition("qual", FieldDispositionKind.Child),
      FieldDisposition("mix", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "owner",
        "qual",
        ChildCardinality.ExactlyOne,
        "selection-super-this-unqualified",
        Set("selection-super-this-qualified")
      ),
      ChildDeclaration(
        "mixin",
        "mix",
        ChildCardinality.ExactlyOne,
        "selection-super-empty-mixin",
        Set("selection-super-mixin")
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "selection-super-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "selection-super-owner-dot",
        TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.Dot),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypePathDotTokenSurface, Some(".")),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "selection-super-left-bracket",
        TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.LeftBracket),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeArgumentLeftTokenSurface, Some("[")),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "selection-super-right-bracket",
        TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.RightBracket),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypeArgumentRightTokenSurface, Some("]")),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = SuperReferenceSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = SuperReferenceAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "super",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.SuperReference,
            SuperReferenceSurface,
            SuperReferenceAccessors
          )
        ),
        Map("owner" -> Some("super"), "mixin" -> Some("super"))
      )
    ),
    outputRoleId = None
  )

  private val NativeQualifierProductionIds  = Vector(
    "selection-qualifier-ident",
    "selection-expression",
    "selection-this-unqualified",
    "selection-this-qualified",
    "selection-super-reference"
  )
  private val PayloadQualifierProductionIds = Vector(
    "payload-descendant-number",
    "payload-descendant-apply",
    "payload-descendant-tuple",
    "payload-descendant-block",
    "payload-descendant-infix",
    "payload-descendant-type-apply-positional",
    "payload-descendant-type-apply-named"
  )

  private def selectionTemplate(native: Boolean) =
    val role      = if native then PsiOutputRoleId.SelectionExpression else PsiOutputRoleId.ExpressionPayload
    val surface   = if native then ReferenceExpressionSurface else ExpressionPayloadSurface
    val accessors = if native then SelectionExpressionAccessors else ExpressionPayloadAccessors
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          if native then "selection" else "payload",
          None,
          OutputRangeDeclaration.CompilerPosition,
          role,
          surface,
          accessors,
          if native then TargetRequirement.Native else TargetRequirement.Compatible
        )
      ),
      Map("qualifier" -> Some(if native then "selection" else "payload"))
    )

  private val selectionExpression = Scala3PsiProduction(
    id = "selection-expression",
    grammarRoleId = GrammarRoleId.SelectionExpression,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Select",
      Vector(
        CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
        CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))
      ),
      directSelectionOccurrences ++ recursiveSelectionOccurrences ++
        Scala3PsiApplicationExpressionProductions.ChildOccurrences ++
        Scala3PsiDefinitionPayloadProductions.PositionalCandidateFunOccurrences ++
        Scala3PsiDefinitionPayloadProductions.NamedCandidateFunOccurrences ++
        Scala3PsiDefinitionPayloadProductions.NamedInvokedCandidateFunOccurrences ++
        Scala3PsiNamedArgumentProductions.CandidateFunOccurrences ++
        Scala3PsiRepeatedArgumentProductions.CandidateFunOccurrences
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
        NativeQualifierProductionIds.head,
        (NativeQualifierProductionIds.tail ++ PayloadQualifierProductionIds).toSet
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "selection-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "selection-dot",
        TerminalIntervalSelector.CompilerScannerToken(ParserScannerTokenKind.Dot, ScannerTokenOccurrence.Last),
        TerminalLeafTarget.Token(NativePsiElementBindings.TypePathDotTokenSurface, Some(".")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ReferenceExpressionSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = SelectionExpressionAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputRealizations = NativeQualifierProductionIds
      .filterNot(_ == "selection-expression")
      .map(id =>
        OutputRealization(
          s"native-$id",
          Vector(
            ChildOutcomeCondition(
              "qualifier",
              ChildOccurrenceSelector.First,
              ChildOutcomeExpectation.Production(id)
            )
          ),
          selectionTemplate(native = true)
        )
      ) ++ Vector(
      OutputRealization(
        "native-recursive",
        Vector(
          ChildOutcomeCondition(
            "qualifier",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.OutputRole(PsiOutputRoleId.SelectionExpression)
          )
        ),
        selectionTemplate(native = true)
      ),
      OutputRealization(
        "payload-recursive",
        Vector(
          ChildOutcomeCondition(
            "qualifier",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.OutputRole(PsiOutputRoleId.ExpressionPayload)
          )
        ),
        selectionTemplate(native = false)
      )
    ),
    outputRoleId = None
  )

  private[psiproducer] val SelectionExpressionSegment: Vector[Scala3PsiProduction] = Vector(
    qualifierIdent,
    selectionExpression,
    thisExpression(
      "selection-this-unqualified",
      GrammarRoleId.ThisReference,
      "atomic-this-empty-qualifier",
      SourceClassification.Absent,
      qualified = false
    ),
    thisExpression(
      "selection-this-qualified",
      GrammarRoleId.QualifiedThisReference,
      "atomic-this-qualifier",
      SourceClassification.SourceReachable,
      qualified = true
    ),
    superReference,
    superThis("selection-super-this-unqualified", "atomic-this-empty-qualifier", SourceClassification.Absent),
    superThis(
      "selection-super-this-qualified",
      "atomic-this-qualifier",
      SourceClassification.SourceReachable
    ),
    emptySuperMixin,
    superMixin
  )
