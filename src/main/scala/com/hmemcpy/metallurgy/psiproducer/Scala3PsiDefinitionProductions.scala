package com.hmemcpy.metallurgy.psiproducer

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiDefinitionProductions:
  private val unboundedTypeBoundsProduction = Scala3PsiProduction(
    id = "template-unbounded-type-bounds",
    grammarRoleId = GrammarRoleId.TypeParameterClause,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "TypeBoundsTree",
      Vector(
        CompilerFieldPattern("lo", CatalogValuePattern.Node),
        CompilerFieldPattern("hi", CatalogValuePattern.Node),
        CompilerFieldPattern("alias", CatalogValuePattern.Node)
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestorPrefix(
            InventoryKind.Node,
            "TypeDef",
            Vector(CatalogPathSegment.NamedField("rhs")),
            Vector(
              InventoryAncestor(
                InventoryKind.Node,
                "DefDef",
                Vector(
                  CatalogPathSegment.NamedField("paramss"),
                  CatalogPathSegment.RepeatedElement,
                  CatalogPathSegment.RepeatedElement
                )
              )
            )
          ),
          SourceClassification.Synthetic
        ),
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestorPrefix(
            InventoryKind.Node,
            "TypeDef",
            Vector(CatalogPathSegment.NamedField("rhs")),
            Vector(
              InventoryAncestor(
                InventoryKind.Node,
                "LambdaTypeTree",
                Vector(CatalogPathSegment.NamedField("tparams"), CatalogPathSegment.RepeatedElement)
              )
            )
          ),
          SourceClassification.Synthetic
        )
      ) ++ OwnerTypeAnchors.map: anchor =>
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestorPrefix(
            InventoryKind.Node,
            "TypeDef",
            Vector(CatalogPathSegment.NamedField("rhs")),
            Vector(
              InventoryAncestor(
                InventoryKind.Node,
                "PolyFunction",
                Vector(CatalogPathSegment.NamedField("targs"), CatalogPathSegment.RepeatedElement)
              ),
              anchor
            )
          ),
          SourceClassification.Synthetic
        )
    ),
    dispositions = Vector(
      FieldDisposition("lo", FieldDispositionKind.Child),
      FieldDisposition("hi", FieldDispositionKind.Child),
      FieldDisposition("alias", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration("lower", "lo", ChildCardinality.ExactlyOne, "template-absent-tree"),
      ChildDeclaration("upper", "hi", ChildCardinality.ExactlyOne, "template-absent-tree"),
      ChildDeclaration("alias", "alias", ChildCardinality.ExactlyOne, "template-absent-tree")
    ),
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = TypeParameterSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(transparentTemplate("lower", "upper", "alias")),
    outputRoleId = None
  )

  private val boundedTypeBoundsProduction = unboundedTypeBoundsProduction.copy(
    id = "type-parameter-bounds",
    grammarRoleId = GrammarRoleId.TypeBounds,
    pattern = unboundedTypeBoundsProduction.pattern.copy(
      occurrences = (Vector(
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestorPrefix(
            InventoryKind.Node,
            "TypeDef",
            Vector(CatalogPathSegment.NamedField("rhs")),
            Vector(
              InventoryAncestor(
                InventoryKind.Node,
                "DefDef",
                Vector(
                  CatalogPathSegment.NamedField("paramss"),
                  CatalogPathSegment.RepeatedElement,
                  CatalogPathSegment.RepeatedElement
                )
              )
            )
          ),
          SourceClassification.SourceReachable
        )
      ) :+ CompilerProductionContextPattern(
        ContextPattern.ParentWithAncestorPrefix(
          InventoryKind.Node,
          "TypeDef",
          Vector(CatalogPathSegment.NamedField("rhs")),
          Vector(
            InventoryAncestor(
              InventoryKind.Node,
              "LambdaTypeTree",
              Vector(CatalogPathSegment.NamedField("tparams"), CatalogPathSegment.RepeatedElement)
            )
          )
        ),
        SourceClassification.SourceReachable
      )) ++ OwnerTypeAnchors.map: anchor =>
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestorPrefix(
            InventoryKind.Node,
            "TypeDef",
            Vector(CatalogPathSegment.NamedField("rhs")),
            Vector(
              InventoryAncestor(
                InventoryKind.Node,
                "PolyFunction",
                Vector(CatalogPathSegment.NamedField("targs"), CatalogPathSegment.RepeatedElement)
              ),
              anchor
            )
          ),
          SourceClassification.SourceReachable
        )
    ),
    children = Vector(
      ChildDeclaration("lower", "lo", ChildCardinality.ExactlyOne, "template-absent-tree", TypeAtomProductionIds),
      ChildDeclaration("upper", "hi", ChildCardinality.ExactlyOne, "template-absent-tree", TypeAtomProductionIds),
      ChildDeclaration("alias", "alias", ChildCardinality.ExactlyOne, "template-absent-tree", TypeAtomProductionIds)
    ),
    terminals = Vector(
      TerminalDeclaration(
        "lower-bound-token",
        TerminalIntervalSelector.BeforeChild("lower"),
        TerminalLeafTarget.Token(NativePsiElementBindings.LowerTypeBoundTokenSurface, Some(">:")),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "upper-bound-token",
        TerminalIntervalSelector.BeforeChild("upper"),
        TerminalLeafTarget.Token(NativePsiElementBindings.UpperTypeBoundTokenSurface, Some("<:")),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    outputTemplate = Some(transparentTemplate("lower", "upper", "alias"))
  )

  private[psiproducer] def unboundedTypeBounds: Scala3PsiProduction = unboundedTypeBoundsProduction
  private[psiproducer] def boundedTypeBounds: Scala3PsiProduction   = boundedTypeBoundsProduction

  private def definitionOccurrences(owner: String, field: String = "stats") = Vector(
    CompilerProductionContextPattern(
      ContextPattern.Parent(
        InventoryKind.Node,
        owner,
        Vector(CatalogPathSegment.NamedField(field), CatalogPathSegment.RepeatedElement)
      ),
      SourceClassification.SourceReachable
    )
  )

  private val refinementMemberOccurrences = Vector(
    CompilerProductionContextPattern(
      ContextPattern.Parent(
        InventoryKind.Node,
        "RefinedTypeTree",
        Vector(CatalogPathSegment.NamedField("refinements"), CatalogPathSegment.RepeatedElement)
      ),
      SourceClassification.SourceReachable
    )
  )

  private def definitionChildOccurrences(field: String) =
    Vector("DefDef", "ValDef").flatMap(owner =>
      Vector("PackageDef" -> "stats", "Template" -> "preBody", "RefinedTypeTree" -> "refinements").map(
        (ancestor, ancestorField) =>
          CompilerProductionContextPattern(
            ContextPattern.ParentWithAncestor(
              InventoryKind.Node,
              owner,
              Vector(CatalogPathSegment.NamedField(field)),
              InventoryAncestor(
                InventoryKind.Node,
                ancestor,
                Vector(CatalogPathSegment.NamedField(ancestorField), CatalogPathSegment.RepeatedElement)
              )
            ),
            SourceClassification.Synthetic
          )
      )
    )

  private def localDefinitionChildOccurrences(field: String, sourceClassification: SourceClassification) =
    Vector("DefDef", "ValDef").map(anchor =>
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchor(
          InventoryKind.Node,
          "ValDef",
          Vector(CatalogPathSegment.NamedField(field)),
          InventoryAncestor(
            InventoryKind.Node,
            anchor,
            Vector(CatalogPathSegment.NamedField("preRhs"))
          )
        ),
        sourceClassification
      )
    )

  private val inferredDefinitionType = Scala3PsiProduction(
    id = "definition-inferred-type-absence",
    grammarRoleId = GrammarRoleId.InferredTypeAbsence,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "TypeTree",
      Vector.empty,
      definitionChildOccurrences("tpt") ++
        localDefinitionChildOccurrences("tpt", SourceClassification.Synthetic)
    ),
    dispositions = Vector.empty,
    children = Vector.empty,
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ExpressionPayloadSurface,
    targetRequirement = TargetRequirement.Compatible,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(transparentTemplate()),
    outputRoleId = None
  )

  private[psiproducer] def inferredDefinitionTypeProduction: Scala3PsiProduction = inferredDefinitionType

  private[psiproducer] def typedParameterProduction(
      id: String,
      flags: Long,
      classParameter: Boolean,
      contextual: Boolean = false
  ): Scala3PsiProduction =
    val ancestors             =
      if classParameter then
        Vector(InventoryAncestor(InventoryKind.Node, "Template", Vector(CatalogPathSegment.NamedField("constr"))))
      else
        Vector(
          InventoryAncestor(
            InventoryKind.Node,
            "Template",
            Vector(CatalogPathSegment.NamedField("preBody"), CatalogPathSegment.RepeatedElement)
          ),
          InventoryAncestor(
            InventoryKind.Node,
            "PackageDef",
            Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement)
          ),
          InventoryAncestor(
            InventoryKind.Node,
            "RefinedTypeTree",
            Vector(CatalogPathSegment.NamedField("refinements"), CatalogPathSegment.RepeatedElement)
          )
        )
    def outputTemplate(
        parameterTypeSurface: String,
        parameterTypeAccessors: Vector[AccessorObligation],
        parameterTypeRequirement: TargetRequirement
    ) = LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "parameter",
          None,
          OutputRangeDeclaration.CompilerPosition,
          if classParameter then PsiOutputRoleId.ClassParameter else PsiOutputRoleId.Parameter,
          if classParameter then ClassParameterSurface else ParameterSurface,
          ParameterAccessors
        ),
        outputComposite(
          "parameter-type",
          Some("parameter"),
          OutputRangeDeclaration.BoundaryDerived(
            OutputBoundary.ChildStart(
              "declared-type",
              ChildOccurrenceSelector.First,
              PositionProvenancePolicy.PositionedIncludingSynthetic
            ),
            OutputBoundary.ChildEnd(
              "declared-type",
              ChildOccurrenceSelector.Last,
              PositionProvenancePolicy.PositionedIncludingSynthetic
            )
          ),
          if parameterTypeRequirement == TargetRequirement.Compatible then PsiOutputRoleId.PureParameterType
          else PsiOutputRoleId.ParameterType,
          parameterTypeSurface,
          parameterTypeAccessors,
          parameterTypeRequirement
        )
      ),
      Map("declared-type" -> Some("parameter-type"), "default" -> None) ++
        Option.when(!contextual)("modifiers" -> Some("parameter"))
    )
    val pureByNameProductions = Set("pure-by-name-parameter-type", "capture-by-name-parameter-type")
    val nativeRealizations    = (TypeAtomProductionIds -- pureByNameProductions).toVector.sorted.map: productionId =>
      OutputRealization(
        s"native-$productionId",
        Vector(
          ChildOutcomeCondition(
            "declared-type",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Production(productionId)
          )
        ),
        outputTemplate(ParameterTypeSurface, ParameterTypeAccessors, TargetRequirement.Native)
      )
    val pureRealizations      = pureByNameProductions.toVector.sorted.map: productionId =>
      OutputRealization(
        s"compatible-$productionId",
        Vector(
          ChildOutcomeCondition(
            "declared-type",
            ChildOccurrenceSelector.First,
            ChildOutcomeExpectation.Production(productionId)
          )
        ),
        outputTemplate(PureParameterTypeSurface, PureParameterTypeAccessors, TargetRequirement.Compatible)
      )
    Scala3PsiProduction(
      id = id,
      grammarRoleId = if classParameter then GrammarRoleId.ClassParameter else GrammarRoleId.TermParameter,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "ValDef",
        Vector(
          CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)),
          CompilerFieldPattern("tpt", CatalogValuePattern.Node),
          CompilerFieldPattern("preRhs", CatalogValuePattern.Node),
          CompilerFieldPattern(
            "mods",
            CatalogValuePattern.Product(
              "Modifiers",
              Vector(
                CompilerFieldPattern("flags", CatalogValuePattern.ExactScalar("LongInteger", s"LongInteger($flags)")),
                CompilerFieldPattern("privateWithin", CatalogValuePattern.ClassifiedName(NeutralNameClass.Empty)),
                CompilerFieldPattern("annotations", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)),
                CompilerFieldPattern("mods", CatalogValuePattern.Repeated(CatalogValuePattern.Positioned))
              )
            )
          )
        ),
        ancestors.map(ancestor =>
          CompilerProductionContextPattern(
            ContextPattern.ParentWithAncestor(
              InventoryKind.Node,
              "DefDef",
              Vector(
                CatalogPathSegment.NamedField("paramss"),
                CatalogPathSegment.RepeatedElement,
                CatalogPathSegment.RepeatedElement
              ),
              ancestor
            ),
            SourceClassification.SourceReachable
          )
        )
      ),
      dispositions = Vector(
        FieldDisposition("name", FieldDispositionKind.TerminalOrLayout),
        FieldDisposition("tpt", FieldDispositionKind.Child),
        FieldDisposition("preRhs", FieldDispositionKind.Child),
        FieldDisposition("mods", if contextual then FieldDispositionKind.SemanticOnly else FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration(
          "declared-type",
          "tpt",
          ChildCardinality.ExactlyOne,
          "import-selector-bound-type",
          TypeAtomProductionIds - "import-selector-bound-type"
        ),
        ChildDeclaration("default", "preRhs", ChildCardinality.ExactlyOne, "template-absent-tree")
      ) ++ Option.when(!contextual)(
        ChildDeclaration(
          "modifiers",
          "mods",
          ChildCardinality.ExactlyOne,
          "modifiers-absent",
          Set("modifiers-keywords", "modifiers-annotations-source", "modifiers-annotations-keywords")
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "parameter-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = if classParameter then ClassParameterSurface else ParameterSurface,
      targetRequirement = TargetRequirement.Native,
      accessors = ParameterAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputTemplate = None,
      outputRealizations = nativeRealizations ++ pureRealizations,
      outputRoleId = None
    )

  private val typeDefinitionTermParameterProduction =
    val base = typedParameterProduction("type-definition-term-parameter", 259L, classParameter = false)
    base.copy(
      pattern = base.pattern.copy(
        occurrences = Vector(
          CompilerProductionContextPattern(
            ContextPattern.Parent(
              InventoryKind.Node,
              "TermLambdaTypeTree",
              Vector(CatalogPathSegment.NamedField("params"), CatalogPathSegment.RepeatedElement)
            ),
            SourceClassification.SourceReachable
          )
        )
      )
    )

  private[psiproducer] def typeDefinitionTermParameter: Scala3PsiProduction =
    typeDefinitionTermParameterProduction

  private val payloadExpressionProductionIds = Set(
    "atomic-term-ident",
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
    "payload-descendant-type-apply-named"
  )

  private val payloadRootIds =
    payloadExpressionProductionIds.filter(id => id.startsWith("definition-payload-") || id.startsWith("atomic-"))

  private def zeroOutput(
      id: String,
      parentId: String,
      role: PsiOutputRoleId,
      surface: String,
      boundary: OutputBoundary
  ): OutputCompositeDeclaration =
    val accessors = role match
      case PsiOutputRoleId.Annotations  => AnnotationsAccessors
      case PsiOutputRoleId.ModifierList => ModifierListAccessors
      case _                            => Vector.empty
    outputComposite(
      id,
      Some(parentId),
      OutputRangeDeclaration.BoundaryDerived(boundary, boundary),
      role,
      surface,
      accessors
    )

  private def definitionShell(
      id: String,
      prefix: String,
      role: PsiOutputRoleId,
      surface: String,
      accessors: Vector[AccessorObligation],
      flags: Long
  ) =
    val function                     = prefix == "DefDef"
    val variable                     = role == PsiOutputRoleId.VariableDefinition
    val modifiersShape               =
      if variable then
        CatalogValuePattern.Product(
          "Modifiers",
          Vector(
            CompilerFieldPattern("flags", CatalogValuePattern.ExactScalar("LongInteger", s"LongInteger($flags)")),
            CompilerFieldPattern("privateWithin", CatalogValuePattern.ClassifiedName(NeutralNameClass.Empty)),
            CompilerFieldPattern("annotations", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)),
            CompilerFieldPattern("mods", CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.Positioned))
          )
        )
      else emptyModifiers(flags)
    val children                     = Vector(
      ChildDeclaration(
        "inferred-type",
        "tpt",
        ChildCardinality.ExactlyOne,
        "definition-inferred-type-absence",
        TypeAtomProductionIds
      ),
      ChildDeclaration(
        "payload",
        "preRhs",
        ChildCardinality.ExactlyOne,
        payloadRootIds.head,
        payloadRootIds.tail + "template-absent-tree"
      )
    ) ++ Option.when(!variable)(
      ChildDeclaration(
        "modifiers",
        "mods",
        ChildCardinality.ExactlyOne,
        "modifiers-absent"
      )
    ) ++ Option.when(function)(
      ChildDeclaration(
        "parameters",
        "paramss",
        ChildCardinality.Repeated(0, None),
        "definition-typed-parameter",
        slice = ChildSlice.MatchingProductions
      )
    ) ++ Option.when(function)(
      ChildDeclaration(
        "type-parameters",
        "paramss",
        ChildCardinality.Repeated(0, None),
        "function-unbounded-type-parameter",
        Set("function-context-bounded-type-parameter", "higher-kinded-nested-type-parameter"),
        slice = ChildSlice.MatchingProductions
      )
    )
    val declarationRole              =
      if function then PsiOutputRoleId.FunctionDeclaration
      else if variable then PsiOutputRoleId.VariableDeclaration
      else PsiOutputRoleId.ValueDeclaration
    val declarationSurface           =
      if function then FunctionDeclarationSurface
      else if variable then VariableDeclarationSurface
      else ValueDeclarationSurface
    val declarationAccessors         =
      if function then FunctionDeclarationAccessors else PropertyDeclarationAccessors
    def root(declaration: Boolean)   =
      outputComposite(
        "definition",
        None,
        OutputRangeDeclaration.CompilerPosition,
        if declaration then declarationRole else role,
        if declaration then declarationSurface else surface,
        if declaration then declarationAccessors else accessors
      )
    def extras(declaration: Boolean) =
      if function then
        Vector(
          zeroOutput(
            "parameters",
            "definition",
            PsiOutputRoleId.ParameterClauses,
            ParameterClausesSurface,
            OutputBoundary.ProductionNameEnd
          )
        )
      else if declaration then
        Vector(
          outputComposite(
            "identifiers",
            Some("definition"),
            OutputRangeDeclaration.BoundaryDerived(OutputBoundary.ProductionPoint, OutputBoundary.ProductionNameEnd),
            PsiOutputRoleId.IdentifierList,
            IdentifierListSurface,
            IdentifierListAccessors
          ),
          outputComposite(
            "field-id",
            Some("identifiers"),
            OutputRangeDeclaration.BoundaryDerived(OutputBoundary.ProductionPoint, OutputBoundary.ProductionNameEnd),
            PsiOutputRoleId.FieldId,
            FieldIdSurface,
            FieldIdAccessors
          )
        )
      else
        Vector(
          outputComposite(
            "patterns",
            Some("definition"),
            OutputRangeDeclaration.BoundaryDerived(OutputBoundary.ProductionPoint, OutputBoundary.ProductionNameEnd),
            PsiOutputRoleId.PatternList,
            PatternListSurface,
            PatternListAccessors
          ),
          outputComposite(
            "binding",
            Some("patterns"),
            OutputRangeDeclaration.BoundaryDerived(OutputBoundary.ProductionPoint, OutputBoundary.ProductionNameEnd),
            PsiOutputRoleId.ReferencePattern,
            ReferencePatternSurface,
            ReferencePatternAccessors
          )
        )
    Scala3PsiProduction(
      id,
      if function then GrammarRoleId.FunctionDefinition else GrammarRoleId.PropertyDefinition,
      CompilerProductionPattern(
        InventoryKind.Node,
        prefix,
        (if function then
           Vector(
             CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)),
             CompilerFieldPattern(
               "paramss",
               CatalogValuePattern.Repeated(CatalogValuePattern.Repeated(CatalogValuePattern.Node))
             )
           )
         else
           Vector(CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)))
        ) ++ Vector(
          CompilerFieldPattern("tpt", CatalogValuePattern.Node),
          CompilerFieldPattern("preRhs", CatalogValuePattern.Node),
          CompilerFieldPattern("mods", modifiersShape)
        ),
        definitionOccurrences("PackageDef") ++ definitionOccurrences("Template", "preBody")
      ),
      (if function then
         Vector(
           FieldDisposition("name", FieldDispositionKind.TerminalOrLayout),
           FieldDisposition("paramss", FieldDispositionKind.Child)
         )
       else Vector(FieldDisposition("name", FieldDispositionKind.TerminalOrLayout))) ++ Vector(
        FieldDisposition("tpt", FieldDispositionKind.Child),
        FieldDisposition("preRhs", FieldDispositionKind.Child),
        FieldDisposition("mods", if variable then FieldDispositionKind.SemanticOnly else FieldDispositionKind.Child)
      ),
      children,
      Vector(
        TerminalDeclaration(
          "definition-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ) ++ Option.when(!function && !variable)(
        TerminalDeclaration(
          "value-keyword",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(NativePsiElementBindings.ValueKeywordTokenSurface, Some("val")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ) ++ Option.when(variable)(
        TerminalDeclaration(
          "variable-keyword",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(NativePsiElementBindings.ModifierKeywordSurfaceIds("Var"), Some("var")),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ) ++ Vector(
        TerminalDeclaration(
          "assignment",
          TerminalIntervalSelector.BeforeChild("payload"),
          TerminalLeafTarget.Token(NativePsiElementBindings.AssignmentTokenSurface, Some("=")),
          OccurrenceCardinality.Optional,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      Vector(LayoutAlternative.None),
      RecoveryPolicy.Reject,
      surface,
      TargetRequirement.Native,
      accessors,
      PersistenceObligations.NotApplicable,
      Some(NavigationObligation.Self),
      None,
      (if function then
         val mounts                                                  = Map(
           "inferred-type" -> Some("definition"),
           "payload"       -> Some("definition"),
           "modifiers"     -> Some("definition")
         )
         def typeParameterClause                                     =
           outputComposite(
             "type-parameter-clause",
             Some("definition"),
             OutputRangeDeclaration.BoundaryDerived(
               OutputBoundary.ProductionStart(),
               OutputBoundary.ProductionEnd()
             ),
             PsiOutputRoleId.TypeParameterClause,
             TypeParameterClauseSurface,
             Vector.empty
           ).copy(
             realization = OutputCompositeRealization.PerRepeatedFieldOccurrence(
               "paramss",
               CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("TypeDef")),
               ClosedSourceLexicalKind.LeftBracket,
               ClosedSourceLexicalKind.RightBracket
             )
           )
         def empty(declaration: Boolean, typeParameters: Boolean)    =
           LocalOutputCompositeTemplate(
             root(declaration) +: (extras(declaration) ++ Option.when(typeParameters)(typeParameterClause)),
             mounts ++ Map(
               "parameters"      -> Some("parameters"),
               "type-parameters" -> Option.when(typeParameters)("type-parameter-clause")
             )
           )
         val clauses                                                 = Vector(
           outputComposite(
             "parameters",
             Some("definition"),
             OutputRangeDeclaration.CompilerPosition,
             PsiOutputRoleId.ParameterClauses,
             ParameterClausesSurface,
             Vector.empty
           ).copy(
             realization = OutputCompositeRealization.AcrossRepeatedFieldOccurrences(
               "paramss",
               CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
               ClosedSourceLexicalKind.LeftParenthesis,
               ClosedSourceLexicalKind.RightParenthesis
             )
           ),
           outputComposite(
             "parameter-clause",
             Some("parameters"),
             OutputRangeDeclaration.CompilerPosition,
             PsiOutputRoleId.ParameterClause,
             ParameterClauseSurface,
             Vector.empty
           ).copy(
             realization = OutputCompositeRealization.PerRepeatedFieldOccurrence(
               "paramss",
               CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
               ClosedSourceLexicalKind.LeftParenthesis,
               ClosedSourceLexicalKind.RightParenthesis
             )
           )
         )
         def nonempty(declaration: Boolean, typeParameters: Boolean) =
           LocalOutputCompositeTemplate(
             root(declaration) +: (clauses ++ Option.when(typeParameters)(typeParameterClause)),
             mounts ++ Map(
               "parameters"      -> Some("parameter-clause"),
               "type-parameters" -> Option.when(typeParameters)("type-parameter-clause")
             )
           )
         val declarationCondition                                    = ChildOutcomeCondition(
           "payload",
           ChildOccurrenceSelector.First,
           ChildOutcomeExpectation.Production("template-absent-tree")
         )
         val definitionCondition                                     = Vector.empty[ChildOutcomeCondition]
         def realization(
             id: String,
             declaration: Boolean,
             termParameters: Boolean,
             typeParameters: Boolean
         ) =
           OutputRealization(
             id,
             if declaration then Vector(declarationCondition) else definitionCondition,
             if termParameters then nonempty(declaration, typeParameters) else empty(declaration, typeParameters),
             Vector(
               EvidenceCondition.RepeatedFieldOccurrence(
                 "paramss",
                 CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("ValDef")),
                 present = termParameters
               ),
               EvidenceCondition.RepeatedFieldOccurrence(
                 "paramss",
                 CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.NodePrefix("TypeDef")),
                 present = typeParameters
               )
             )
           )
         Vector(
           realization(
             "definition-without-parameters",
             declaration = false,
             termParameters = false,
             typeParameters = false
           ),
           realization(
             "definition-with-term-parameters",
             declaration = false,
             termParameters = true,
             typeParameters = false
           ),
           realization(
             "definition-with-type-parameters",
             declaration = false,
             termParameters = false,
             typeParameters = true
           ),
           realization(
             "definition-with-type-and-term-parameters",
             declaration = false,
             termParameters = true,
             typeParameters = true
           ),
           realization(
             "declaration-without-parameters",
             declaration = true,
             termParameters = false,
             typeParameters = false
           ),
           realization(
             "declaration-with-term-parameters",
             declaration = true,
             termParameters = true,
             typeParameters = false
           ),
           realization(
             "declaration-with-type-parameters",
             declaration = true,
             termParameters = false,
             typeParameters = true
           ),
           realization(
             "declaration-with-type-and-term-parameters",
             declaration = true,
             termParameters = true,
             typeParameters = true
           )
         )
       else
         val mounts               = Map(
           "inferred-type" -> Some("definition"),
           "payload"       -> Some("definition")
         ) ++ Option.when(!variable)("modifiers" -> Some("definition"))
         val definitionTemplate   =
           LocalOutputCompositeTemplate(root(declaration = false) +: extras(declaration = false), mounts)
         val declarationTemplate  =
           LocalOutputCompositeTemplate(root(declaration = true) +: extras(declaration = true), mounts)
         val declarationCondition = ChildOutcomeCondition(
           "payload",
           ChildOccurrenceSelector.First,
           ChildOutcomeExpectation.Production("template-absent-tree")
         )
         Vector(
           OutputRealization("definition", Vector.empty, definitionTemplate),
           OutputRealization("declaration", Vector(declarationCondition), declarationTemplate)
         )
      )
      ,
      None,
      Option.when(!function)(GrammarRoleId.ReferenceBinding).toSet
    )

  private def refinementDeclarationShell(
      id: String,
      prefix: String,
      role: PsiOutputRoleId,
      surface: String,
      accessors: Vector[AccessorObligation],
      flags: Long
  ): Scala3PsiProduction =
    val base = definitionShell(id, prefix, role, surface, accessors, flags)
    base.copy(
      pattern = base.pattern.copy(
        fields = base.pattern.fields.map:
          case CompilerFieldPattern("preRhs", _) =>
            CompilerFieldPattern("preRhs", CatalogValuePattern.NodePrefix("Thicket"))
          case field                             => field,
        occurrences = refinementMemberOccurrences
      ),
      children = base.children.map:
        case child @ ChildDeclaration("payload", _, _, _, _, _) =>
          child.copy(productionId = "template-absent-tree", additionalProductionIds = Set.empty)
        case child                                              => child
    )

  private val abstractTypeAlias = Scala3PsiProduction(
    "definition-unbounded-type-alias",
    GrammarRoleId.TypeAliasDeclaration,
    CompilerProductionPattern(
      InventoryKind.Node,
      "TypeDef",
      Vector(
        CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)),
        CompilerFieldPattern("rhs", CatalogValuePattern.NodePrefix("TypeBoundsTree")),
        CompilerFieldPattern("mods", emptyModifiers(0L))
      ),
      definitionOccurrences("Template", "preBody") ++ refinementMemberOccurrences
    ),
    Vector(
      FieldDisposition("name", FieldDispositionKind.TerminalOrLayout),
      FieldDisposition("rhs", FieldDispositionKind.Child),
      FieldDisposition("mods", FieldDispositionKind.Child)
    ),
    Vector(
      ChildDeclaration(
        "bounds",
        "rhs",
        ChildCardinality.ExactlyOne,
        "template-unbounded-type-bounds",
        Set("type-alias-bounds")
      ),
      ChildDeclaration("modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
    ),
    Vector(
      TerminalDeclaration(
        "alias-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    Vector(LayoutAlternative.None),
    RecoveryPolicy.Reject,
    TypeAliasDeclarationSurface,
    TargetRequirement.Native,
    TypeAliasDeclarationAccessors,
    parameterPersistence(PsiOutputRoleId.TypeAliasDeclaration),
    Some(NavigationObligation.Self),
    Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "alias",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.TypeAliasDeclaration,
            TypeAliasDeclarationSurface,
            TypeAliasDeclarationAccessors
          )
        ),
        Map("bounds" -> Some("alias"), "modifiers" -> Some("alias"))
      )
    ),
    Vector.empty,
    None
  )

  private def simpleTypeAlias(
      id: String,
      rootProduction: String,
      typeProductionIds: Set[String]
  ): Scala3PsiProduction =
    val firstTypeProduction = typeProductionIds.toVector.sorted.head
    abstractTypeAlias.copy(
      id = id,
      grammarRoleId = GrammarRoleId.TypeAliasDefinition,
      pattern = abstractTypeAlias.pattern.copy(
        fields = abstractTypeAlias.pattern.fields.updated(
          1,
          CompilerFieldPattern("rhs", CatalogValuePattern.NodePrefix(rootProduction))
        ),
        occurrences = definitionOccurrences("PackageDef") ++ definitionOccurrences("Template", "preBody") ++
          refinementMemberOccurrences
      ),
      children = Vector(
        ChildDeclaration(
          "aliased-type",
          "rhs",
          ChildCardinality.ExactlyOne,
          firstTypeProduction,
          typeProductionIds - firstTypeProduction
        ),
        ChildDeclaration("modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
      ),
      terminals = abstractTypeAlias.terminals :+ TerminalDeclaration(
        "alias-assignment",
        TerminalIntervalSelector.BeforeChild("aliased-type"),
        TerminalLeafTarget.Token(NativePsiElementBindings.AssignmentTokenSurface, Some("=")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      ),
      targetSurfaceId = TypeAliasDefinitionSurface,
      accessors = TypeAliasDefinitionAccessors,
      outputTemplate = Some(
        LocalOutputCompositeTemplate(
          Vector(
            outputComposite(
              "alias",
              None,
              OutputRangeDeclaration.CompilerPosition,
              PsiOutputRoleId.TypeAliasDefinition,
              TypeAliasDefinitionSurface,
              TypeAliasDefinitionAccessors
            )
          ),
          Map("aliased-type" -> Some("alias"), "modifiers" -> Some("alias"))
        )
      )
    )

  private lazy val simpleTypeAliases: Vector[Scala3PsiProduction] = Vector(
    simpleTypeAlias("definition-simple-ident-type-alias", "Ident", Set("import-selector-bound-type")),
    simpleTypeAlias(
      "definition-simple-select-type-alias",
      "Select",
      Set("import-selector-given-bound-qualified-type", "type-atom-projection")
    ),
    simpleTypeAlias(
      "definition-simple-singleton-type-alias",
      "SingletonTypeTree",
      Set("type-atom-singleton-ident", "type-atom-singleton-select", "type-atom-literal")
    ),
    simpleTypeAlias("definition-simple-literal-type-alias", "Literal", Set("type-atom-literal")),
    simpleTypeAlias("definition-simple-parenthesized-type-alias", "Parens", Set("type-atom-parenthesized")),
    simpleTypeAlias(
      "definition-applied-type-alias",
      "AppliedTypeTree",
      Set("ordinary-applied-type")
    ),
    simpleTypeAlias(
      "definition-tuple-type-alias",
      "Tuple",
      Set("ordinary-tuple-type", "named-tuple-type")
    ),
    simpleTypeAlias(
      "definition-function-type-alias",
      "Function",
      Set(
        "ordinary-function-type",
        "pure-nullary-function-type",
        "pure-function-type",
        "capture-nullary-function-type",
        "capture-function-type",
        "dependent-function-type"
      )
    ),
    simpleTypeAlias(
      "definition-polymorphic-function-type-alias",
      "PolyFunction",
      Set("polymorphic-function-type")
    ),
    simpleTypeAlias(
      "definition-infix-type-alias",
      "InfixOp",
      Set("ordinary-infix-type")
    ),
    simpleTypeAlias(
      "definition-match-type-alias",
      "MatchTypeTree",
      Set("ordinary-match-type")
    ),
    simpleTypeAlias(
      "definition-refinement-type-alias",
      "RefinedTypeTree",
      Set("ordinary-refinement-type")
    ),
    simpleTypeAlias(
      "definition-annotated-type-alias",
      "Annotated",
      Set("ordinary-annotated-type", "capture-type-shorthand", "capture-type-explicit-set")
    )
  )

  private val opaqueSimpleTypeAlias =
    val base = simpleTypeAlias(
      "definition-opaque-simple-ident-type-alias",
      "Ident",
      Set("import-selector-bound-type")
    )
    base.copy(
      pattern = base.pattern.copy(
        fields = base.pattern.fields.updated(
          2,
          CompilerFieldPattern(
            "mods",
            CatalogValuePattern.Product(
              "Modifiers",
              Vector(
                CompilerFieldPattern("flags", CatalogValuePattern.Scalar("LongInteger")),
                CompilerFieldPattern("privateWithin", CatalogValuePattern.Name),
                CompilerFieldPattern("annotations", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)),
                CompilerFieldPattern("mods", CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.Positioned))
              )
            )
          )
        ),
        occurrences = definitionOccurrences("PackageDef") ++ definitionOccurrences("Template", "preBody")
      ),
      children = base.children.updated(
        1,
        ChildDeclaration(
          "modifiers",
          "mods",
          ChildCardinality.ExactlyOne,
          "modifiers-keywords",
          Set("modifiers-annotations-keywords")
        )
      ),
      persistence = parameterPersistence(PsiOutputRoleId.TypeAliasDefinition)
    )

  private val TypeDefinitionLambdaEncodingProductionIds = Set(
    "type-definition-lambda-encoding",
    "type-definition-ident-lambda-encoding",
    "type-definition-select-lambda-encoding",
    "type-definition-singleton-lambda-encoding",
    "type-definition-literal-lambda-encoding",
    "type-definition-parenthesized-lambda-encoding",
    "type-definition-applied-lambda-encoding",
    "type-definition-match-lambda-encoding",
    "type-definition-nested-lambda-encoding"
  )

  private val typeLambdaAlias = abstractTypeAlias.copy(
    id = "definition-type-lambda-alias",
    grammarRoleId = GrammarRoleId.TypeAliasDefinition,
    pattern = abstractTypeAlias.pattern.copy(
      fields = abstractTypeAlias.pattern.fields.updated(
        1,
        CompilerFieldPattern("rhs", CatalogValuePattern.NodePrefix("LambdaTypeTree"))
      ),
      occurrences = definitionOccurrences("PackageDef") ++ definitionOccurrences("Template", "preBody")
    ),
    children = Vector(
      ChildDeclaration(
        "aliased-type",
        "rhs",
        ChildCardinality.ExactlyOne,
        "explicit-type-lambda",
        TypeDefinitionLambdaEncodingProductionIds
      ),
      ChildDeclaration("modifiers", "mods", ChildCardinality.ExactlyOne, "modifiers-absent")
    ),
    terminals = abstractTypeAlias.terminals :+ TerminalDeclaration(
      "alias-assignment",
      TerminalIntervalSelector.WholeProduction,
      TerminalLeafTarget.Token(NativePsiElementBindings.AssignmentTokenSurface, Some("=")),
      OccurrenceCardinality.ExactlyOne,
      PsiOutputRoleId.SourceTerminal
    ),
    targetSurfaceId = TypeAliasDefinitionSurface,
    accessors = TypeAliasDefinitionAccessors,
    persistence = parameterPersistence(PsiOutputRoleId.TypeAliasDefinition),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "alias",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.TypeAliasDefinition,
            TypeAliasDefinitionSurface,
            TypeAliasDefinitionAccessors
          )
        ),
        Map("aliased-type" -> Some("alias"), "modifiers" -> Some("alias"))
      )
    )
  )

  private val opaqueBoundedTypeAlias = abstractTypeAlias.copy(
    id = "definition-opaque-bounded-type-alias",
    grammarRoleId = GrammarRoleId.TypeAliasDefinition,
    pattern = abstractTypeAlias.pattern.copy(
      fields = Vector(
        CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)),
        CompilerFieldPattern("rhs", CatalogValuePattern.NodePrefix("TypeBoundsTree")),
        CompilerFieldPattern(
          "mods",
          CatalogValuePattern.Product(
            "Modifiers",
            Vector(
              CompilerFieldPattern("flags", CatalogValuePattern.Scalar("LongInteger")),
              CompilerFieldPattern("privateWithin", CatalogValuePattern.Name),
              CompilerFieldPattern("annotations", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)),
              CompilerFieldPattern("mods", CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.Positioned))
            )
          )
        )
      ),
      occurrences = definitionOccurrences("PackageDef") ++ definitionOccurrences("Template", "preBody")
    ),
    children = Vector(
      ChildDeclaration("bounds", "rhs", ChildCardinality.ExactlyOne, "type-alias-bounds"),
      ChildDeclaration(
        "modifiers",
        "mods",
        ChildCardinality.ExactlyOne,
        "modifiers-keywords",
        Set("modifiers-annotations-keywords")
      )
    ),
    terminals = abstractTypeAlias.terminals ++ Vector(
      TerminalDeclaration(
        "lower-bound-token",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Token(NativePsiElementBindings.LowerTypeBoundTokenSurface, Some(">:")),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "upper-bound-token",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Token(NativePsiElementBindings.UpperTypeBoundTokenSurface, Some("<:")),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      ),
      TerminalDeclaration(
        "alias-assignment",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Token(NativePsiElementBindings.AssignmentTokenSurface, Some("=")),
        OccurrenceCardinality.Optional,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    targetSurfaceId = TypeAliasDefinitionSurface,
    accessors = TypeAliasDefinitionAccessors,
    persistence = parameterPersistence(PsiOutputRoleId.TypeAliasDefinition),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "alias",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.TypeAliasDefinition,
            TypeAliasDefinitionSurface,
            TypeAliasDefinitionAccessors
          )
        ),
        Map("bounds" -> Some("alias"), "modifiers" -> Some("alias"))
      )
    )
  )

  private val typeAliasBounds = boundedTypeBoundsProduction.copy(
    id = "type-alias-bounds",
    pattern = boundedTypeBoundsProduction.pattern.copy(
      occurrences = Vector(
        InventoryAncestor(
          InventoryKind.Node,
          "PackageDef",
          Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement)
        ),
        InventoryAncestor(
          InventoryKind.Node,
          "Template",
          Vector(CatalogPathSegment.NamedField("preBody"), CatalogPathSegment.RepeatedElement)
        ),
        InventoryAncestor(
          InventoryKind.Node,
          "RefinedTypeTree",
          Vector(CatalogPathSegment.NamedField("refinements"), CatalogPathSegment.RepeatedElement)
        )
      ).flatMap: ancestor =>
        Vector(SourceClassification.SourceReachable, SourceClassification.Synthetic).map: classification =>
          CompilerProductionContextPattern(
            ContextPattern.ParentWithAncestorPrefix(
              InventoryKind.Node,
              "TypeDef",
              Vector(CatalogPathSegment.NamedField("rhs")),
              Vector(ancestor)
            ),
            classification
          )
    )
  )

  private val explicitTypeLambda = Scala3PsiProduction(
    id = "explicit-type-lambda",
    grammarRoleId = GrammarRoleId.TypeLambda,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "LambdaTypeTree",
      Vector(
        CompilerFieldPattern("tparams", CatalogValuePattern.Repeated(CatalogValuePattern.Node)),
        CompilerFieldPattern("body", CatalogValuePattern.Node)
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestorPrefix(
            InventoryKind.Node,
            "TypeDef",
            Vector(CatalogPathSegment.NamedField("rhs")),
            Vector(
              InventoryAncestor(
                InventoryKind.Node,
                "PackageDef",
                Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement)
              )
            )
          ),
          SourceClassification.SourceReachable
        ),
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestorPrefix(
            InventoryKind.Node,
            "TypeDef",
            Vector(CatalogPathSegment.NamedField("rhs")),
            Vector(
              InventoryAncestor(
                InventoryKind.Node,
                "Template",
                Vector(CatalogPathSegment.NamedField("preBody"), CatalogPathSegment.RepeatedElement)
              )
            )
          ),
          SourceClassification.SourceReachable
        ),
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "ContextBoundTypeTree",
            Vector(CatalogPathSegment.NamedField("tycon"))
          ),
          SourceClassification.SourceReachable
        ),
        CompilerProductionContextPattern(
          ContextPattern.ParentWithRepeatedAncestor(
            InventoryKind.Node,
            "LambdaTypeTree",
            Vector(CatalogPathSegment.NamedField("body")),
            InventoryAncestor(InventoryKind.Node, "LambdaTypeTree", Vector(CatalogPathSegment.NamedField("body"))),
            InventoryAncestor(InventoryKind.Node, "TypeDef", Vector(CatalogPathSegment.NamedField("rhs")))
          ),
          SourceClassification.SourceReachable
        ),
        CompilerProductionContextPattern(
          ContextPattern.ParentWithRepeatedAncestorSequencePrefix(
            InventoryKind.Node,
            "AppliedTypeTree",
            Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
            Vector(
              InventoryAncestor(InventoryKind.Node, "LambdaTypeTree", Vector(CatalogPathSegment.NamedField("body"))),
              InventoryAncestor(
                InventoryKind.Node,
                "AppliedTypeTree",
                Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
              )
            ),
            Vector(
              InventoryAncestor(InventoryKind.Node, "LambdaTypeTree", Vector(CatalogPathSegment.NamedField("body"))),
              InventoryAncestor(InventoryKind.Node, "TypeDef", Vector(CatalogPathSegment.NamedField("rhs")))
            )
          ),
          SourceClassification.SourceReachable
        )
      ) ++ OwnerTypeAnchors.map: anchor =>
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchor(
            InventoryKind.Node,
            "TypeDef",
            Vector(CatalogPathSegment.NamedField("rhs")),
            anchor
          ),
          SourceClassification.SourceReachable
        )
    ),
    dispositions = Vector(
      FieldDisposition("tparams", FieldDispositionKind.Child),
      FieldDisposition("body", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "parameters",
        "tparams",
        ChildCardinality.Repeated(1, None),
        "function-unbounded-type-parameter",
        Set("higher-kinded-nested-type-parameter")
      ),
      ChildDeclaration(
        "body",
        "body",
        ChildCardinality.ExactlyOne,
        TypeAtomProductionIds.toVector.sorted.head,
        TypeAtomProductionIds - TypeAtomProductionIds.toVector.sorted.head + "explicit-type-lambda"
      )
    ),
    terminals = Vector(
      TerminalDeclaration(
        "lambda-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = TypeLambdaSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = TypeLambdaAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "lambda",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.TypeLambda,
            TypeLambdaSurface,
            TypeLambdaAccessors
          ),
          outputComposite(
            "clause",
            Some("lambda"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.ChildStart(
                "parameters",
                ChildOccurrenceSelector.First,
                PositionProvenancePolicy.SourceDerivedOnly
              ),
              OutputBoundary.ChildEnd(
                "parameters",
                ChildOccurrenceSelector.Last,
                PositionProvenancePolicy.SourceDerivedOnly
              )
            ),
            PsiOutputRoleId.TypeParameterClause,
            TypeParameterClauseSurface,
            Vector.empty
          ).copy(
            realization = OutputCompositeRealization.AcrossRepeatedFieldOccurrences(
              "tparams",
              CatalogValuePattern.NodePrefix("TypeDef"),
              ClosedSourceLexicalKind.LeftBracket,
              ClosedSourceLexicalKind.RightBracket
            )
          )
        ),
        Map("parameters" -> Some("clause"), "body" -> Some("lambda"))
      )
    ),
    outputRoleId = None
  )

  private def typeDefinitionLambdaEncoding(
      id: String,
      bodyPattern: CatalogValuePattern,
      bodyProductionId: String,
      additionalBodyProductionIds: Set[String] = Set.empty
  ) = Scala3PsiProduction(
    id = id,
    grammarRoleId = GrammarRoleId.TypeParameterClause,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "LambdaTypeTree",
      Vector(
        CompilerFieldPattern("tparams", CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.Node)),
        CompilerFieldPattern("body", bodyPattern)
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "TypeDef",
            Vector(CatalogPathSegment.NamedField("rhs"))
          ),
          SourceClassification.Synthetic
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("tparams", FieldDispositionKind.Child),
      FieldDisposition("body", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "parameters",
        "tparams",
        ChildCardinality.Repeated(1, None),
        "function-unbounded-type-parameter",
        Set("function-context-bounded-type-parameter", "higher-kinded-nested-type-parameter")
      ),
      ChildDeclaration(
        "body",
        "body",
        ChildCardinality.ExactlyOne,
        bodyProductionId,
        additionalBodyProductionIds
      )
    ),
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = TypeParameterClauseSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "clause",
            None,
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
                  "body",
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
        Map("parameters" -> Some("clause"), "body" -> None)
      )
    ),
    outputRoleId = None
  )

  private val typeDefinitionLambdaEncodings = Vector(
    typeDefinitionLambdaEncoding(
      "type-definition-lambda-encoding",
      CatalogValuePattern.NodePrefix("TermLambdaTypeTree"),
      "type-definition-term-lambda"
    ),
    typeDefinitionLambdaEncoding(
      "type-definition-ident-lambda-encoding",
      CatalogValuePattern.NodePrefix("Ident"),
      "import-selector-bound-type"
    ),
    typeDefinitionLambdaEncoding(
      "type-definition-select-lambda-encoding",
      CatalogValuePattern.NodePrefix("Select"),
      "import-selector-given-bound-qualified-type",
      Set("type-atom-projection")
    ),
    typeDefinitionLambdaEncoding(
      "type-definition-singleton-lambda-encoding",
      CatalogValuePattern.NodePrefix("SingletonTypeTree"),
      "type-atom-singleton-ident",
      Set("type-atom-singleton-select", "type-atom-literal")
    ),
    typeDefinitionLambdaEncoding(
      "type-definition-literal-lambda-encoding",
      CatalogValuePattern.NodePrefix("Literal"),
      "type-atom-literal"
    ),
    typeDefinitionLambdaEncoding(
      "type-definition-parenthesized-lambda-encoding",
      CatalogValuePattern.NodePrefix("Parens"),
      "type-atom-parenthesized"
    ),
    typeDefinitionLambdaEncoding(
      "type-definition-applied-lambda-encoding",
      CatalogValuePattern.NodePrefix("AppliedTypeTree"),
      "ordinary-applied-type"
    ),
    typeDefinitionLambdaEncoding(
      "type-definition-match-lambda-encoding",
      CatalogValuePattern.NodePrefix("MatchTypeTree"),
      "ordinary-match-type"
    ),
    typeDefinitionLambdaEncoding(
      "type-definition-nested-lambda-encoding",
      CatalogValuePattern.NodePrefix("LambdaTypeTree"),
      "explicit-type-lambda"
    )
  )

  private val typeDefinitionTermLambda = Scala3PsiProduction(
    id = "type-definition-term-lambda",
    grammarRoleId = GrammarRoleId.TermLambda,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "TermLambdaTypeTree",
      Vector(
        CompilerFieldPattern("params", CatalogValuePattern.NonEmptyRepeated(CatalogValuePattern.Node)),
        CompilerFieldPattern("body", CatalogValuePattern.Node)
      ),
      Vector(
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "LambdaTypeTree",
            Vector(CatalogPathSegment.NamedField("body"))
          ),
          SourceClassification.Synthetic
        ),
        CompilerProductionContextPattern(
          ContextPattern.Parent(
            InventoryKind.Node,
            "TermLambdaTypeTree",
            Vector(CatalogPathSegment.NamedField("body"))
          ),
          SourceClassification.Synthetic
        )
      )
    ),
    dispositions = Vector(
      FieldDisposition("params", FieldDispositionKind.Child),
      FieldDisposition("body", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "parameters",
        "params",
        ChildCardinality.Repeated(1, None),
        "type-definition-term-parameter"
      ),
      ChildDeclaration(
        "body",
        "body",
        ChildCardinality.ExactlyOne,
        TypeAtomProductionIds.toVector.sorted.head,
        TypeAtomProductionIds - TypeAtomProductionIds.toVector.sorted.head + "type-definition-term-lambda"
      )
    ),
    terminals = Vector.empty,
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = ParameterClausesSurface,
    targetRequirement = TargetRequirement.Native,
    accessors = Vector.empty,
    persistence = PersistenceObligations.NotApplicable,
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "clauses",
            None,
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
                  "body",
                  ChildOccurrenceSelector.First,
                  Vector(")"),
                  PositionProvenancePolicy.SourceDerivedOnly
                ),
                1
              )
            ),
            PsiOutputRoleId.ParameterClauses,
            ParameterClausesSurface,
            Vector.empty
          ),
          outputComposite(
            "clause",
            Some("clauses"),
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
                  "body",
                  ChildOccurrenceSelector.First,
                  Vector(")"),
                  PositionProvenancePolicy.SourceDerivedOnly
                ),
                1
              )
            ),
            PsiOutputRoleId.ParameterClause,
            ParameterClauseSurface,
            Vector.empty
          )
        ),
        Map("parameters" -> Some("clause"), "body" -> None)
      )
    ),
    outputRoleId = None
  )

  private[psiproducer] val DefinitionSegment: Vector[Scala3PsiProduction] = Vector(
    definitionShell(
      "definition-function-untyped",
      "DefDef",
      PsiOutputRoleId.FunctionDefinition,
      FunctionDefinitionSurface,
      FunctionDefinitionAccessors,
      129L
    ),
    definitionShell(
      "definition-val-untyped",
      "ValDef",
      PsiOutputRoleId.PatternDefinition,
      PatternDefinitionSurface,
      PropertyDefinitionAccessors,
      0L
    ),
    definitionShell(
      "definition-var-untyped",
      "ValDef",
      PsiOutputRoleId.VariableDefinition,
      VariableDefinitionSurface,
      VariableDefinitionAccessors,
      4097L
    ),
    refinementDeclarationShell(
      "refinement-function-declaration",
      "DefDef",
      PsiOutputRoleId.FunctionDefinition,
      FunctionDefinitionSurface,
      FunctionDefinitionAccessors,
      129L
    ),
    refinementDeclarationShell(
      "refinement-value-declaration",
      "ValDef",
      PsiOutputRoleId.PatternDefinition,
      PatternDefinitionSurface,
      PropertyDefinitionAccessors,
      0L
    ),
    refinementDeclarationShell(
      "refinement-variable-declaration",
      "ValDef",
      PsiOutputRoleId.VariableDefinition,
      VariableDefinitionSurface,
      VariableDefinitionAccessors,
      4097L
    ),
    abstractTypeAlias
  ) ++ simpleTypeAliases ++ Vector(
    opaqueSimpleTypeAlias,
    typeLambdaAlias,
    opaqueBoundedTypeAlias,
    typeAliasBounds,
    explicitTypeLambda,
    typeDefinitionTermLambda
  ) ++ typeDefinitionLambdaEncodings
