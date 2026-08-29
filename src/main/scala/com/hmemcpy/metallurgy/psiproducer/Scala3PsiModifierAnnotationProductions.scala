package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiModifierAnnotationProductions:
  private val ModifierOwnerPrefixes = Vector("TypeDef", "ModuleDef", "DefDef", "ValDef", "PatDef", "Template")
  private val ModifierPath          = Vector(CatalogPathSegment.NamedField("mods"))
  private val ModifierChildrenPath  = Vector(
    CatalogPathSegment.NamedField("mods"),
    CatalogPathSegment.NestedProduct("Modifiers")
  )

  private def modifierOccurrences(classification: SourceClassification) = ModifierOwnerPrefixes.map(owner =>
    CompilerProductionContextPattern(
      ContextPattern.Parent(InventoryKind.Node, owner, ModifierPath),
      classification
    )
  )

  private def annotationAnchor(owner: String) = InventoryAncestor(
    InventoryKind.Node,
    owner,
    ModifierChildrenPath ++ Vector(
      CatalogPathSegment.NamedField("annotations"),
      CatalogPathSegment.RepeatedElement
    )
  )

  private def annotationOccurrences(classifications: SourceClassification*) =
    ModifierOwnerPrefixes.flatMap(owner =>
      classifications.map(classification =>
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            owner,
            annotationAnchor(owner).path
          ),
          classification
        )
      )
    ) ++ classifications.map(classification =>
      CompilerProductionContextPattern(
        ContextPattern.Parent(
          InventoryKind.Node,
          "Annotated",
          Vector(CatalogPathSegment.NamedField("annot"))
        ),
        classification
      )
    )

  private def annotationChildOccurrences(
      owner: String,
      path: Vector[CatalogPathSegment],
      directAncestors: Vector[InventoryAncestor],
      classification: SourceClassification
  ) = ModifierOwnerPrefixes.map(definition =>
    CompilerProductionContextPattern(
      ContextPattern.ParentWithAncestorPrefix(
        InventoryKind.Node,
        owner,
        path,
        directAncestors :+ annotationAnchor(definition)
      ),
      classification
    )
  ) :+ CompilerProductionContextPattern(
    ContextPattern.ParentWithAncestorPrefix(
      InventoryKind.Node,
      owner,
      path,
      directAncestors :+ InventoryAncestor(
        InventoryKind.Node,
        "Annotated",
        Vector(CatalogPathSegment.NamedField("annot"))
      )
    ),
    classification
  )

  private val ApplyFunAncestor           = InventoryAncestor(
    InventoryKind.Node,
    "Apply",
    Vector(CatalogPathSegment.NamedField("fun"))
  )
  private val SelectQualifierAncestor    = InventoryAncestor(
    InventoryKind.Node,
    "Select",
    Vector(CatalogPathSegment.NamedField("qualifier"))
  )
  private val NewTypeAncestor            = InventoryAncestor(
    InventoryKind.Node,
    "New",
    Vector(CatalogPathSegment.NamedField("tpt"))
  )
  private val ModifierChildProductionIds = Set(
    "modifier-access-private",
    "modifier-access-protected",
    "modifier-keyword-abstract",
    "modifier-keyword-final",
    "modifier-keyword-sealed",
    "modifier-keyword-implicit",
    "modifier-keyword-lazy",
    "modifier-keyword-override",
    "modifier-keyword-transparent",
    "modifier-keyword-inline",
    "modifier-keyword-infix",
    "modifier-keyword-open",
    "modifier-keyword-opaque",
    "modifier-keyword-given",
    "modifier-keyword-var"
  )

  private def modifierTemplate(withAnnotations: Boolean, withModifiers: Boolean): LocalOutputCompositeTemplate =
    val modifierRange =
      if withModifiers then
        OutputRangeDeclaration.BoundaryDerivedWithTrailingBalancedBrackets(
          OutputBoundary.ChildStart(
            "modifiers",
            ChildOccurrenceSelector.First,
            PositionProvenancePolicy.PositionedIncludingSynthetic
          ),
          OutputBoundary.ChildEnd(
            "modifiers",
            ChildOccurrenceSelector.Last,
            PositionProvenancePolicy.PositionedIncludingSynthetic
          )
        )
      else
        val end = OutputBoundary.ChildEnd(
          "annotations",
          ChildOccurrenceSelector.Last,
          PositionProvenancePolicy.PositionedIncludingSynthetic
        )
        OutputRangeDeclaration.BoundaryDerived(end, end)
    val annotations   = Option.when(withAnnotations)(
      outputComposite(
        "annotations",
        None,
        OutputRangeDeclaration.BoundaryDerived(
          OutputBoundary.ChildStart(
            "annotations",
            ChildOccurrenceSelector.First,
            PositionProvenancePolicy.PositionedIncludingSynthetic
          ),
          OutputBoundary.ChildEnd(
            "annotations",
            ChildOccurrenceSelector.Last,
            PositionProvenancePolicy.PositionedIncludingSynthetic
          )
        ),
        PsiOutputRoleId.Annotations,
        AnnotationsSurface,
        AnnotationsAccessors
      )
    )
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "modifiers",
          None,
          modifierRange,
          PsiOutputRoleId.ModifierList,
          ModifierListSurface,
          ModifierListAccessors
        )
      ) ++ annotations,
      Map(
        "annotations" -> Some(if withAnnotations then "annotations" else "modifiers"),
        "modifiers"   -> Some("modifiers")
      )
    )

  private def modifiersProduction(
      id: String,
      annotations: CatalogValuePattern,
      modifiers: CatalogValuePattern,
      classification: SourceClassification,
      template: LocalOutputCompositeTemplate,
      minimumAnnotations: Int,
      minimumModifiers: Int
  ): Scala3PsiProduction = Scala3PsiProduction(
    id = id,
    grammarRoleId = GrammarRoleId.Modifiers,
    pattern = CompilerProductionPattern(
      InventoryKind.Product,
      "Modifiers",
      Vector(
        CompilerFieldPattern("flags", CatalogValuePattern.Scalar("LongInteger")),
        CompilerFieldPattern("privateWithin", CatalogValuePattern.Name),
        CompilerFieldPattern("annotations", annotations),
        CompilerFieldPattern("mods", modifiers)
      ),
      modifierOccurrences(classification)
    ),
    dispositions = Vector(
      FieldDisposition("flags", FieldDispositionKind.SemanticOnly),
      FieldDisposition("privateWithin", FieldDispositionKind.SemanticOnly),
      FieldDisposition("annotations", FieldDispositionKind.Child),
      FieldDisposition("mods", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "annotations",
        "annotations",
        ChildCardinality.Repeated(minimumAnnotations, None),
        "annotation-apply-simple",
        Set("annotation-apply-arguments")
      ),
      ChildDeclaration(
        "modifiers",
        "mods",
        ChildCardinality.Repeated(minimumModifiers, None),
        ModifierChildProductionIds.head,
        ModifierChildProductionIds.tail
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "modifier-text",
        TerminalIntervalSelector.LocalOutput("modifiers"),
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ModifierListSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = ModifierListAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(template),
    outputRoleId = None,
    additionalGrammarRoleIds = Option
      .when(annotations != CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node))(
        GrammarRoleId.Annotations
      )
      .toSet
  )

  private def transparentProduction(
      id: String,
      role: GrammarRoleId,
      prefix: String,
      fields: Vector[CompilerFieldPattern],
      occurrences: Vector[CompilerProductionContextPattern],
      dispositions: Vector[FieldDisposition],
      children: Vector[ChildDeclaration]
  ): Scala3PsiProduction = Scala3PsiProduction(
    id,
    role,
    CompilerProductionPattern(InventoryKind.Node, prefix, fields, occurrences),
    dispositions,
    children,
    Vector.empty,
    Vector(LayoutAlternative.None),
    RecoveryPolicy.Reject,
    StableReferenceSurface,
    TargetRequirement.Native,
    Vector.empty,
    PersistenceObligations.NotApplicable,
    outputTemplate = Some(transparentTemplate(children.map(_.roleId)*)),
    outputRoleId = None
  )

  private def annotationTemplate(withArguments: Boolean): LocalOutputCompositeTemplate =
    val whole = OutputRangeDeclaration.CompilerPositionWithPolicy(
      PositionProvenancePolicy.PositionedIncludingSynthetic
    )
    val body  = OutputRangeDeclaration.BoundaryDerived(
      OutputBoundary.Advance(
        OutputBoundary.ProductionStart(PositionProvenancePolicy.PositionedIncludingSynthetic),
        1
      ),
      OutputBoundary.ProductionEnd(PositionProvenancePolicy.PositionedIncludingSynthetic)
    )
    val args  = Option.when(withArguments)(
      outputComposite(
        "arguments",
        Some("constructor"),
        OutputRangeDeclaration.BoundaryDerived(
          OutputBoundary.ChildEnd(
            "fun",
            ChildOccurrenceSelector.First,
            PositionProvenancePolicy.PositionedIncludingSynthetic
          ),
          OutputBoundary.ProductionEnd(PositionProvenancePolicy.PositionedIncludingSynthetic)
        ),
        PsiOutputRoleId.AnnotationArguments,
        AnnotationArgumentsSurface,
        AnnotationArgumentsAccessors
      )
    )
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite("annotation", None, whole, PsiOutputRoleId.Annotation, AnnotationSurface, AnnotationAccessors),
        outputComposite(
          "expression",
          Some("annotation"),
          body,
          PsiOutputRoleId.AnnotationExpr,
          AnnotationExprSurface,
          AnnotationExprAccessors
        ),
        outputComposite(
          "constructor",
          Some("expression"),
          body,
          PsiOutputRoleId.ConstructorInvocation,
          ConstructorSurface,
          ConstructorAccessors
        )
      ) ++ args,
      Map("fun" -> Some("constructor"), "args" -> Some(if withArguments then "arguments" else "constructor"))
    )

  private def annotationApplyProduction(id: String, withArguments: Boolean): Scala3PsiProduction =
    val arguments =
      if withArguments then CatalogValuePattern.Repeated(CatalogValuePattern.Node)
      else CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)
    Scala3PsiProduction(
      id = id,
      grammarRoleId = GrammarRoleId.Annotation,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Apply",
        Vector(
          CompilerFieldPattern("fun", CatalogValuePattern.Node),
          CompilerFieldPattern("args", arguments)
        ),
        annotationOccurrences(SourceClassification.SourceReachable, SourceClassification.Synthetic).map(
          _.copy(scannerEvidence = ScannerEvidencePattern(required = Set(ParserScannerTokenKind.AtSign)))
        )
      ),
      dispositions = Vector(
        FieldDisposition("fun", FieldDispositionKind.Child),
        FieldDisposition("args", FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration("fun", "fun", ChildCardinality.ExactlyOne, "annotation-constructor-select"),
        ChildDeclaration(
          "args",
          "args",
          ChildCardinality.Repeated(if withArguments then 1 else 0, None),
          "annotation-argument-literal-payload"
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "annotation-text",
          TerminalIntervalSelector.LocalOutput("annotation"),
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = AnnotationSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = AnnotationAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputTemplate = Some(annotationTemplate(withArguments)),
      outputRoleId = None,
      additionalGrammarRoleIds = Option.when(withArguments)(GrammarRoleId.AnnotationArguments).toSet
    )

  private def modifierKeywordProduction(prefix: String, expectedText: String, surface: String) =
    Scala3PsiProduction(
      id = s"modifier-keyword-${expectedText}",
      grammarRoleId = GrammarRoleId.KeywordModifier,
      pattern = CompilerProductionPattern(
        InventoryKind.Positioned,
        prefix,
        Vector.empty,
        ModifierOwnerPrefixes.map(owner =>
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              owner,
              ModifierChildrenPath ++ Vector(
                CatalogPathSegment.NamedField("mods"),
                CatalogPathSegment.RepeatedElement
              )
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
          TerminalLeafTarget.Token(surface, Some(expectedText)),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = surface,
      targetRequirement = TargetRequirement.Native,
      accessors = Vector.empty,
      persistence = PersistenceObligations.NotApplicable,
      outputTemplate = Some(LocalOutputCompositeTemplate(Vector.empty, Map.empty)),
      outputRoleId = None
    )

  private def accessModifierProduction(prefix: String, expectedText: String, surface: String) =
    Scala3PsiProduction(
      id = s"modifier-access-$expectedText",
      grammarRoleId = GrammarRoleId.AccessModifier,
      pattern = CompilerProductionPattern(
        InventoryKind.Positioned,
        prefix,
        Vector.empty,
        ModifierOwnerPrefixes.map(owner =>
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              owner,
              ModifierChildrenPath ++ Vector(
                CatalogPathSegment.NamedField("mods"),
                CatalogPathSegment.RepeatedElement
              )
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
          TerminalLeafTarget.Token(surface, Some(expectedText)),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = AccessModifierSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = AccessModifierAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputTemplate = Some(
        LocalOutputCompositeTemplate(
          Vector(
            outputComposite(
              "access",
              None,
              OutputRangeDeclaration.CompilerPositionWithTrailingBalancedBrackets(
                PositionProvenancePolicy.SourceDerivedOnly
              ),
              PsiOutputRoleId.AccessModifier,
              AccessModifierSurface,
              AccessModifierAccessors
            )
          ),
          Map.empty
        )
      ),
      outputRoleId = None
    )

  private def annotationConstructorSelectProduction: Scala3PsiProduction = transparentProduction(
    "annotation-constructor-select",
    GrammarRoleId.Annotation,
    "Select",
    Vector(
      CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
      CompilerFieldPattern("name", CatalogValuePattern.Name)
    ),
    annotationChildOccurrences(
      "Apply",
      Vector(CatalogPathSegment.NamedField("fun")),
      Vector.empty,
      SourceClassification.Synthetic
    ).map(_.copy(scannerEvidence = ScannerEvidencePattern(required = Set(ParserScannerTokenKind.AtSign)))),
    Vector(
      FieldDisposition("qualifier", FieldDispositionKind.Child),
      FieldDisposition("name", FieldDispositionKind.SemanticOnly)
    ),
    Vector(
      ChildDeclaration(
        "constructor",
        "qualifier",
        ChildCardinality.ExactlyOne,
        "annotation-constructor-new"
      )
    )
  )

  private def annotationConstructorNewProduction: Scala3PsiProduction = transparentProduction(
    "annotation-constructor-new",
    GrammarRoleId.Annotation,
    "New",
    Vector(CompilerFieldPattern("tpt", CatalogValuePattern.Node)),
    annotationChildOccurrences(
      "Select",
      Vector(CatalogPathSegment.NamedField("qualifier")),
      Vector(ApplyFunAncestor),
      SourceClassification.Synthetic
    ),
    Vector(FieldDisposition("tpt", FieldDispositionKind.Child)),
    Vector(
      ChildDeclaration(
        "designator",
        "tpt",
        ChildCardinality.ExactlyOne,
        "annotation-designator-ident",
        Set("annotation-designator-select")
      )
    )
  )

  private def annotationDesignatorOccurrences = annotationChildOccurrences(
    "New",
    Vector(CatalogPathSegment.NamedField("tpt")),
    Vector(SelectQualifierAncestor, ApplyFunAncestor),
    SourceClassification.SourceReachable
  )

  private def annotationDesignatorIdentProduction: Scala3PsiProduction = Scala3PsiProduction(
    id = "annotation-designator-ident",
    grammarRoleId = GrammarRoleId.SimpleType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
      annotationDesignatorOccurrences
    ),
    dispositions = Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "designator-text",
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

  private def annotationDesignatorSelectProduction: Scala3PsiProduction = Scala3PsiProduction(
    id = "annotation-designator-select",
    grammarRoleId = GrammarRoleId.SimpleType,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Select",
      Vector(
        CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
        CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))
      ),
      annotationDesignatorOccurrences
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
        "annotation-designator-qualifier-ident",
        Set("annotation-designator-qualifier-select")
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "designator-text",
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
    outputTemplate = Some(qualifiedTypeTemplate),
    outputRoleId = None
  )

  private def annotationQualifierOccurrences = ModifierOwnerPrefixes.map(definition =>
    CompilerProductionContextPattern(
      ContextPattern.ParentWithRepeatedAncestorPrefix(
        InventoryKind.Node,
        "Select",
        Vector(CatalogPathSegment.NamedField("qualifier")),
        SelectQualifierAncestor,
        Vector(NewTypeAncestor, SelectQualifierAncestor, ApplyFunAncestor, annotationAnchor(definition))
      ),
      SourceClassification.SourceReachable
    )
  )

  private def annotationQualifierProduction(prefix: String, recursive: Boolean): Scala3PsiProduction =
    val children = Option
      .when(recursive)(
        ChildDeclaration(
          "qualifier",
          "qualifier",
          ChildCardinality.ExactlyOne,
          "annotation-designator-qualifier-ident",
          Set("annotation-designator-qualifier-select")
        )
      )
      .toVector
    Scala3PsiProduction(
      id = s"annotation-designator-qualifier-${prefix.toLowerCase}",
      grammarRoleId = GrammarRoleId.StableReference,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        prefix,
        if recursive then
          Vector(
            CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
            CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))
          )
        else Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary))),
        annotationQualifierOccurrences
      ),
      dispositions =
        if recursive then
          Vector(
            FieldDisposition("qualifier", FieldDispositionKind.Child),
            FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)
          )
        else Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout)),
      children = children,
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
      outputTemplate = Some(stableReferenceTemplate(children.map(_.roleId)*)),
      outputRoleId = None
    )

  private def annotationLiteralPayloadProduction: Scala3PsiProduction = Scala3PsiProduction(
    id = "annotation-argument-literal-payload",
    grammarRoleId = GrammarRoleId.ExpressionPayload,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "Literal",
      Vector(
        CompilerFieldPattern(
          "const",
          CatalogValuePattern.Product(
            "",
            Vector(CompilerFieldPattern("", CatalogValuePattern.Scalar("Text")))
          )
        )
      ),
      annotationChildOccurrences(
        "Apply",
        Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
        Vector.empty,
        SourceClassification.SourceReachable
      )
    ),
    dispositions = Vector(FieldDisposition("const", FieldDispositionKind.TerminalOrLayout)),
    children = Vector.empty,
    terminals = Vector(
      TerminalDeclaration(
        "payload-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ExpressionPayloadSurface,
    targetRequirement = TargetRequirement.Compatible,
    accessors = ExpressionPayloadAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
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
        Map.empty
      )
    ),
    outputRoleId = None
  )

  private[psiproducer] val ModifierAnnotationSegment: Vector[Scala3PsiProduction] =
    val emptyNode          = CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)
    val repeatedNode       = CatalogValuePattern.Repeated(CatalogValuePattern.Node)
    val emptyPositioned    = CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Positioned)
    val repeatedPositioned = CatalogValuePattern.Repeated(CatalogValuePattern.Positioned)
    val modifiers          = Vector(
      modifiersProduction(
        "modifiers-annotations-synthetic",
        repeatedNode,
        emptyPositioned,
        SourceClassification.Synthetic,
        modifierTemplate(withAnnotations = true, withModifiers = false),
        minimumAnnotations = 1,
        minimumModifiers = 0
      ),
      modifiersProduction(
        "modifiers-annotations-source",
        repeatedNode,
        emptyPositioned,
        SourceClassification.SourceReachable,
        modifierTemplate(withAnnotations = true, withModifiers = false),
        minimumAnnotations = 1,
        minimumModifiers = 0
      ),
      modifiersProduction(
        "modifiers-keywords",
        emptyNode,
        repeatedPositioned,
        SourceClassification.SourceReachable,
        modifierTemplate(withAnnotations = false, withModifiers = true),
        minimumAnnotations = 0,
        minimumModifiers = 1
      ),
      modifiersProduction(
        "modifiers-annotations-keywords",
        repeatedNode,
        repeatedPositioned,
        SourceClassification.SourceReachable,
        modifierTemplate(withAnnotations = true, withModifiers = true),
        minimumAnnotations = 1,
        minimumModifiers = 1
      ),
      Scala3PsiProduction(
        id = "modifiers-absent",
        grammarRoleId = GrammarRoleId.Modifiers,
        pattern = CompilerProductionPattern(
          InventoryKind.Product,
          "Modifiers",
          Vector(
            CompilerFieldPattern("flags", CatalogValuePattern.Scalar("LongInteger")),
            CompilerFieldPattern("privateWithin", CatalogValuePattern.Name),
            CompilerFieldPattern("annotations", emptyNode),
            CompilerFieldPattern("mods", emptyPositioned)
          ),
          modifierOccurrences(SourceClassification.Absent)
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
    )
    val access             = Vector("Private" -> "private", "Protected" -> "protected").map: (prefix, text) =>
      accessModifierProduction(prefix, text, NativePsiElementBindings.AccessModifierKeywordSurfaceIds(prefix))
    val keyword            = Vector(
      "Abstract"    -> "abstract",
      "Final"       -> "final",
      "Sealed"      -> "sealed",
      "Implicit"    -> "implicit",
      "Lazy"        -> "lazy",
      "Override"    -> "override",
      "Var"         -> "var",
      "Transparent" -> "transparent",
      "Inline"      -> "inline",
      "Infix"       -> "infix",
      "Open"        -> "open",
      "Opaque"      -> "opaque",
      "Given"       -> "given"
    ).map: (prefix, text) =>
      modifierKeywordProduction(prefix, text, NativePsiElementBindings.ModifierKeywordSurfaceIds(prefix))
    modifiers ++ access ++ keyword ++ Vector(
      annotationApplyProduction("annotation-apply-simple", withArguments = false),
      annotationApplyProduction("annotation-apply-arguments", withArguments = true),
      annotationConstructorSelectProduction,
      annotationConstructorNewProduction,
      annotationDesignatorIdentProduction,
      annotationDesignatorSelectProduction,
      annotationQualifierProduction("Ident", recursive = false),
      annotationQualifierProduction("Select", recursive = true),
      annotationLiteralPayloadProduction
    )
