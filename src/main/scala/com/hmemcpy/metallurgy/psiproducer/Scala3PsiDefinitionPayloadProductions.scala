package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.{ParserAttachmentValue, ParserScannerTokenKind}

import Scala3PsiProductionSupport.*

private[psiproducer] object Scala3PsiDefinitionPayloadProductions:
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

  private def nonAtomicDefinitionChildOccurrences(field: String) =
    Vector("DefDef", "ValDef").map(owner =>
      CompilerProductionContextPattern(
        ContextPattern.ParentWithAncestor(
          InventoryKind.Node,
          owner,
          Vector(CatalogPathSegment.NamedField(field)),
          InventoryAncestor(
            InventoryKind.Node,
            "RefinedTypeTree",
            Vector(CatalogPathSegment.NamedField("refinements"), CatalogPathSegment.RepeatedElement)
          )
        ),
        SourceClassification.SourceReachable
      )
    ) ++
      localDefinitionChildOccurrences(field, SourceClassification.SourceReachable)

  private def negativeNumberOccurrences(field: String) =
    Vector("DefDef", "ValDef").flatMap(owner =>
      Vector("PackageDef" -> "stats", "Template" -> "preBody").map((ancestor, ancestorField) =>
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
          SourceClassification.SourceReachable,
          ScannerEvidencePattern(required = Set(ParserScannerTokenKind.Identifier))
        )
      )
    )

  private val payloadExpressionProductionIds = Set(
    "definition-payload-number",
    "definition-payload-ident",
    "definition-payload-apply",
    "definition-payload-select",
    "definition-payload-tuple",
    "definition-payload-block",
    "definition-payload-infix",
    "definition-payload-type-apply-positional",
    "definition-payload-type-apply-named",
    "match-expression-candidate",
    "named-type-application-candidate",
    "definition-payload-applied-call",
    "positional-applied-call-candidate",
    "named-invoked-call-candidate",
    "named-term-application-candidate",
    "payload-descendant-number",
    "payload-descendant-invoked-literal",
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
    "named-term-output-free-integer",
    "named-term-output-free-string",
    "repeated-term-output-free-typed",
    "repeated-term-output-free-typed-synthetic",
    "repeated-term-star-evidence",
    "repeated-term-output-free-string"
  )

  private val payloadRootIds            = payloadExpressionProductionIds.filter(id =>
    id.startsWith("definition-payload-") ||
      id == "positional-applied-call-candidate" ||
      id == "named-invoked-call-candidate" ||
      id == "named-term-application-candidate"
  )
  private val payloadLocalDefinitionIds = Set("payload-descendant-val", "payload-descendant-var")

  private def payloadRoot(
      id: String,
      prefix: String,
      fields: Vector[CompilerFieldPattern],
      children: Vector[ChildDeclaration],
      occurrences: Vector[CompilerProductionContextPattern] =
        definitionChildOccurrences("preRhs").map(_.copy(sourceClassification = SourceClassification.SourceReachable)) ++
          localDefinitionChildOccurrences("preRhs", SourceClassification.SourceReachable)
  ) = Scala3PsiProduction(
    id,
    GrammarRoleId.ExpressionPayload,
    CompilerProductionPattern(
      InventoryKind.Node,
      prefix,
      fields,
      occurrences
    ),
    fields.map(field =>
      FieldDisposition(
        field.name,
        if children.exists(_.fieldName == field.name) then FieldDispositionKind.Child
        else FieldDispositionKind.TerminalOrLayout
      )
    ),
    children,
    Vector(
      TerminalDeclaration(
        "payload-text",
        TerminalIntervalSelector.WholeProduction,
        TerminalLeafTarget.Parent,
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    Vector(LayoutAlternative.None),
    RecoveryPolicy.Reject,
    ExpressionPayloadSurface,
    TargetRequirement.Compatible,
    ExpressionPayloadAccessors,
    PersistenceObligations.NotApplicable,
    Some(NavigationObligation.Self),
    Some(
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
        children.map(_.roleId -> Some("payload")).toMap
      )
    ),
    Vector.empty,
    None
  )

  private val ExpressionTypeApplicationAnchors = Vector("DefDef", "ValDef").map(owner =>
    InventoryAncestor(InventoryKind.Node, owner, Vector(CatalogPathSegment.NamedField("preRhs")))
  )

  private val DirectPayloadOwners = Vector("DefDef", "ValDef").flatMap: definition =>
    Vector("PackageDef" -> "stats", "Template" -> "preBody").map: (outer, field) =>
      InventoryAncestor(InventoryKind.Node, definition, Vector(CatalogPathSegment.NamedField("preRhs"))) ->
        InventoryAncestor(
          InventoryKind.Node,
          outer,
          Vector(CatalogPathSegment.NamedField(field), CatalogPathSegment.RepeatedElement)
        )

  private def nodeEdge(owner: String, field: String, repeated: Boolean = false): InventoryAncestor =
    InventoryAncestor(
      InventoryKind.Node,
      owner,
      Vector(CatalogPathSegment.NamedField(field)) ++ Option.when(repeated)(CatalogPathSegment.RepeatedElement)
    )

  private def ownedRootRoutes(
      rootProductionIds: Vector[String],
      descendantPaths: Vector[Vector[InventoryAncestor]]
  ): Vector[OwnedRootRoute] =
    for
      rootProductionId        <- rootProductionIds
      descendantPath          <- descendantPaths
      (rootOwner, outerOwner) <- DirectPayloadOwners
    yield OwnedRootRoute(rootProductionId, descendantPath, rootOwner, outerOwner)

  private def positionalCandidateRoutes(
      descendantPath: Vector[InventoryAncestor],
      applied: Boolean
  ): Vector[OwnedRootRoute] =
    val rootId = if applied then "positional-applied-call-candidate" else "definition-payload-type-apply-positional"
    DirectPayloadOwners.map: (rootOwner, outerOwner) =>
      OwnedRootRoute(
        rootId,
        descendantPath ++ Option.when(applied)(nodeEdge("Apply", "fun")),
        rootOwner,
        outerOwner
      )

  private def namedCandidateRoutes(descendantPath: Vector[InventoryAncestor]): Vector[OwnedRootRoute] =
    DirectPayloadOwners.map: (rootOwner, outerOwner) =>
      OwnedRootRoute("named-type-application-candidate", descendantPath, rootOwner, outerOwner)

  private[psiproducer] val NamedCandidateFunOccurrences =
    Vector(
      CompilerProductionContextPattern(
        ContextPattern.DescendantOfEnabledCandidateRoot(
          namedCandidateRoutes(Vector(nodeEdge("TypeApply", "fun")))
        ),
        SourceClassification.SourceReachable
      )
    )

  private[psiproducer] val NamedCandidateSelectionQualifierOccurrences =
    Vector(
      CompilerProductionContextPattern(
        ContextPattern.DescendantOfEnabledCandidateRoot(
          namedCandidateRoutes(
            Vector(nodeEdge("Select", "qualifier"), nodeEdge("TypeApply", "fun"))
          )
        ),
        SourceClassification.SourceReachable
      )
    )

  private def namedInvokedCallRoutes(descendantPath: Vector[InventoryAncestor]): Vector[OwnedRootRoute] =
    DirectPayloadOwners.map: (rootOwner, outerOwner) =>
      OwnedRootRoute("named-invoked-call-candidate", descendantPath, rootOwner, outerOwner)

  private val NamedInvokedSelectionQualifierRoutes = namedInvokedCallRoutes(
    Vector(
      nodeEdge("Select", "qualifier"),
      nodeEdge("TypeApply", "fun"),
      nodeEdge("Apply", "fun")
    )
  )

  private[psiproducer] val NamedInvokedSelectionQualifierOccurrences =
    Vector(
      CompilerProductionContextPattern(
        ContextPattern.DescendantOfEnabledCandidateRoot(NamedInvokedSelectionQualifierRoutes),
        SourceClassification.SourceReachable
      )
    )

  private val NamedInvokedCandidateTypeApplyOccurrences =
    Vector(
      CompilerProductionContextPattern(
        ContextPattern.DescendantOfEnabledCandidateRoot(
          namedInvokedCallRoutes(Vector(nodeEdge("Apply", "fun")))
        ),
        SourceClassification.SourceReachable
      )
    )

  private[psiproducer] val NamedInvokedCandidateFunOccurrences =
    Vector(
      CompilerProductionContextPattern(
        ContextPattern.DescendantOfEnabledCandidateRoot(
          namedInvokedCallRoutes(Vector(nodeEdge("TypeApply", "fun"), nodeEdge("Apply", "fun")))
        ),
        SourceClassification.SourceReachable
      )
    )

  private val NamedInvokedArgumentRoutes = namedInvokedCallRoutes(
    Vector(nodeEdge("Apply", "args", repeated = true))
  )

  private val NamedInvokedArgumentFallbackOccurrences =
    Vector(
      CompilerProductionContextPattern(
        ContextPattern.DescendantOfOwnedRoot(
          NamedInvokedArgumentRoutes
        ),
        SourceClassification.SourceReachable
      )
    )

  private[psiproducer] val PositionalCandidateFunOccurrences =
    Vector(
      CompilerProductionContextPattern(
        ContextPattern.DescendantOfEnabledCandidateRoot(
          positionalCandidateRoutes(Vector(nodeEdge("TypeApply", "fun")), applied = false) ++
            positionalCandidateRoutes(Vector(nodeEdge("TypeApply", "fun")), applied = true)
        ),
        SourceClassification.SourceReachable
      )
    )

  private val PositionalCandidateTypeApplyOccurrences =
    Vector(
      CompilerProductionContextPattern(
        ContextPattern.DescendantOfEnabledCandidateRoot(
          DirectPayloadOwners.map: (rootOwner, outerOwner) =>
            OwnedRootRoute(
              "positional-applied-call-candidate",
              Vector(nodeEdge("Apply", "fun")),
              rootOwner,
              outerOwner
            )
        ),
        SourceClassification.SourceReachable
      )
    )

  private[psiproducer] val PositionalCandidateSelectionQualifierOccurrences =
    Vector(
      CompilerProductionContextPattern(
        ContextPattern.DescendantOfEnabledCandidateRoot(
          positionalCandidateRoutes(
            Vector(nodeEdge("Select", "qualifier"), nodeEdge("TypeApply", "fun")),
            applied = false
          ) ++ positionalCandidateRoutes(
            Vector(nodeEdge("Select", "qualifier"), nodeEdge("TypeApply", "fun")),
            applied = true
          )
        ),
        SourceClassification.SourceReachable
      )
    )

  private[psiproducer] val PositionalCandidateTermArgumentOccurrences =
    Vector(
      CompilerProductionContextPattern(
        ContextPattern.DescendantOfEnabledCandidateRoot(
          DirectPayloadOwners.map: (rootOwner, outerOwner) =>
            OwnedRootRoute(
              "positional-applied-call-candidate",
              Vector(nodeEdge("Apply", "args", repeated = true)),
              rootOwner,
              outerOwner
            )
        ),
        SourceClassification.SourceReachable
      )
    )

  private[psiproducer] val NamedInvokedLiteralArgumentOccurrences =
    Vector(
      CompilerProductionContextPattern(
        ContextPattern.DescendantOfEnabledCandidateRoot(
          NamedInvokedArgumentRoutes
        ),
        SourceClassification.SourceReachable
      )
    )

  private val SelectionRootPaths =
    Vector(
      Vector(nodeEdge("Apply", "fun"))                                                                -> Vector("definition-payload-apply"),
      Vector(nodeEdge("Apply", "args", repeated = true))                                              -> Vector("definition-payload-apply"),
      Vector(nodeEdge("TypeApply", "fun"))                                                            ->
        Vector("definition-payload-type-apply-positional", "definition-payload-type-apply-named"),
      Vector(nodeEdge("Select", "qualifier"), nodeEdge("TypeApply", "fun"), nodeEdge("Apply", "fun")) ->
        Vector("definition-payload-applied-call"),
      Vector(nodeEdge("Tuple", "trees", repeated = true))                                             -> Vector("definition-payload-tuple"),
      Vector(nodeEdge("Block", "expr"))                                                               -> Vector("definition-payload-block"),
      Vector(nodeEdge("InfixOp", "left"))                                                             -> Vector("definition-payload-infix"),
      Vector(nodeEdge("InfixOp", "op"))                                                               -> Vector("definition-payload-infix"),
      Vector(nodeEdge("InfixOp", "right"))                                                            -> Vector("definition-payload-infix"),
      Vector(nodeEdge("NamedArg", "arg"), nodeEdge("Apply", "args", repeated = true))                 ->
        Vector("definition-payload-apply")
    ).flatMap: (path, rootIds) =>
      ownedRootRoutes(rootIds, Vector(path)) ++
        ownedRootRoutes(rootIds, Vector(nodeEdge("Select", "qualifier") +: path)).map(
          _.copy(repeatedEdge = Some(RepeatedOwnedRootEdge(1, nodeEdge("Select", "qualifier"))))
        )

  private val LocalSelectionRootRoutes = Vector(
    OwnedRootRoute(
      "definition-payload-select",
      Vector(nodeEdge("Select", "qualifier")),
      nodeEdge("ValDef", "preRhs"),
      nodeEdge("Block", "stats", repeated = true),
      Some(RepeatedOwnedRootEdge(1, nodeEdge("Select", "qualifier")))
    )
  )

  private val SelectionQualifierRootRoutes =
    SelectionRootPaths.filter(_.descendantPath.headOption.contains(nodeEdge("Select", "qualifier"))) ++
      LocalSelectionRootRoutes

  private def qualifierRoutes(prefixes: Vector[Vector[InventoryAncestor]]): Vector[OwnedRootRoute] =
    for
      prefix <- prefixes
      route  <- SelectionQualifierRootRoutes
    yield route.copy(
      descendantPath = prefix ++ route.descendantPath,
      repeatedEdge = route.repeatedEdge.map(value => value.copy(insertionIndex = value.insertionIndex + prefix.size))
    )

  private def expressionTypeApplicationRootOccurrences: Vector[CompilerProductionContextPattern] =
    definitionChildOccurrences("preRhs").map(_.copy(sourceClassification = SourceClassification.SourceReachable)) ++
      localDefinitionChildOccurrences("preRhs", SourceClassification.SourceReachable)

  private def expressionTypeApplicationChildOccurrences(
      owner: String,
      field: String
  ): Vector[CompilerProductionContextPattern] =
    ExpressionTypeApplicationAnchors.map: anchor =>
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchor(
          InventoryKind.Node,
          owner,
          Vector(CatalogPathSegment.NamedField(field)) ++
            Option.when(field == "args")(CatalogPathSegment.RepeatedElement),
          anchor
        ),
        SourceClassification.SourceReachable
      )

  private def outputFreeAppliedCallArgumentOccurrences: Vector[CompilerProductionContextPattern] =
    ExpressionTypeApplicationAnchors.map: anchor =>
      CompilerProductionContextPattern(
        ContextPattern.ParentWithNodeFieldUnderAnchor(
          InventoryKind.Node,
          "Apply",
          Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
          "fun",
          "TypeApply",
          anchor
        ),
        SourceClassification.SourceReachable
      )

  private def outputFreeExpressionProduction(
      id: String,
      prefix: String,
      fields: Vector[CompilerFieldPattern],
      occurrences: Vector[CompilerProductionContextPattern]
  ): Scala3PsiProduction =
    Scala3PsiProduction(
      id,
      GrammarRoleId.OutputFreeExpression,
      CompilerProductionPattern(InventoryKind.Node, prefix, fields, occurrences),
      fields.map(field => FieldDisposition(field.name, FieldDispositionKind.TerminalOrLayout)),
      Vector.empty,
      Vector(
        TerminalDeclaration(
          "output-free-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      Vector(LayoutAlternative.None),
      RecoveryPolicy.Reject,
      ExpressionPayloadSurface,
      TargetRequirement.Compatible,
      Vector.empty,
      PersistenceObligations.NotApplicable,
      None,
      Some(transparentTemplate()),
      Vector.empty,
      None
    )

  private val typeApplicationOutputFreeIdent = outputFreeExpressionProduction(
    "type-application-output-free-ident",
    "Ident",
    Vector(CompilerFieldPattern("name", CatalogValuePattern.Name)),
    expressionTypeApplicationChildOccurrences("TypeApply", "fun")
  )

  private val appliedCallOutputFreeNumber = outputFreeExpressionProduction(
    "type-application-output-free-number",
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
    outputFreeAppliedCallArgumentOccurrences
  )

  private val appliedCallOutputFreeLiteral = outputFreeExpressionProduction(
    "type-application-output-free-literal",
    "Literal",
    Vector(
      CompilerFieldPattern(
        "const",
        CatalogValuePattern.Product("", Vector(CompilerFieldPattern("", CatalogValuePattern.Scalar("Text"))))
      )
    ),
    outputFreeAppliedCallArgumentOccurrences
  )

  private val namedInvokedOutputFreeInteger = outputFreeExpressionProduction(
    "named-invoked-output-free-integer",
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
    NamedInvokedArgumentFallbackOccurrences
  )

  private val namedInvokedOutputFreeString = outputFreeExpressionProduction(
    "named-invoked-output-free-string",
    "Literal",
    Vector(
      CompilerFieldPattern(
        "const",
        CatalogValuePattern.Product("", Vector(CompilerFieldPattern("", CatalogValuePattern.Scalar("Text"))))
      )
    ),
    NamedInvokedArgumentFallbackOccurrences
  )

  private val namedInvokedOutputFreeIdent = outputFreeExpressionProduction(
    "named-invoked-output-free-ident",
    "Ident",
    Vector(CompilerFieldPattern("name", CatalogValuePattern.Name)),
    NamedInvokedArgumentFallbackOccurrences
  )

  private val namedTermOutputFreeInteger = outputFreeExpressionProduction(
    "named-term-output-free-integer",
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
    Scala3PsiNamedArgumentProductions.CandidateNamedValueFallbackOccurrences
  )

  private val namedTermOutputFreeString = outputFreeExpressionProduction(
    "named-term-output-free-string",
    "Literal",
    Vector(
      CompilerFieldPattern(
        "const",
        CatalogValuePattern.Product("", Vector(CompilerFieldPattern("", CatalogValuePattern.Scalar("Text"))))
      )
    ),
    Scala3PsiNamedArgumentProductions.CandidateNamedValueFallbackOccurrences
  )

  private def namedInvokedLiteralProduction(
      id: String,
      prefix: String,
      fields: Vector[CompilerFieldPattern],
      grammarRoleId: GrammarRoleId,
      outputRoleId: PsiOutputRoleId,
      targetSurfaceId: String,
      tokenSurfaceId: String
  ): Scala3PsiProduction =
    Scala3PsiProduction(
      id = id,
      grammarRoleId = grammarRoleId,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        prefix,
        fields,
        NamedInvokedLiteralArgumentOccurrences
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
        ),
        TerminalDeclaration(
          s"$id-token",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Token(tokenSurfaceId),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = targetSurfaceId,
      targetRequirement = TargetRequirement.Native,
      accessors = AtomicLiteralAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputRoleId = Some(outputRoleId)
    )

  private val namedInvokedIntegerLiteral = namedInvokedLiteralProduction(
    "named-invoked-literal-integer",
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
    GrammarRoleId.ExpressionIntegerLiteral,
    PsiOutputRoleId.IntegerExpression,
    IntegerLiteralSurface,
    NativePsiElementBindings.IntegerLiteralTokenSurface
  )

  private val namedInvokedStringLiteral = namedInvokedLiteralProduction(
    "named-invoked-literal-string",
    "Literal",
    Vector(
      CompilerFieldPattern(
        "const",
        CatalogValuePattern.Product("", Vector(CompilerFieldPattern("", CatalogValuePattern.Scalar("Text"))))
      )
    ),
    GrammarRoleId.ExpressionStringLiteral,
    PsiOutputRoleId.StringExpression,
    StringLiteralSurface,
    NativePsiElementBindings.StringLiteralTokenSurface
  )

  private val appliedCallOutputFreeIdent = outputFreeExpressionProduction(
    "type-application-output-free-ident-argument",
    "Ident",
    Vector(CompilerFieldPattern("name", CatalogValuePattern.Name)),
    outputFreeAppliedCallArgumentOccurrences
  )

  private def expressionTypeArgumentIdent(
      id: String,
      occurrences: Vector[CompilerProductionContextPattern],
      role: GrammarRoleId
  ): Scala3PsiProduction =
    Scala3PsiAppliedTypeProductions.positionalTypeArgument.copy(
      id = id,
      grammarRoleId = role,
      pattern = Scala3PsiAppliedTypeProductions.positionalTypeArgument.pattern.copy(occurrences = occurrences)
    )

  private val expressionPositionalTypeArgument = expressionTypeArgumentIdent(
    "expression-type-argument-ident",
    expressionTypeApplicationChildOccurrences("TypeApply", "args"),
    GrammarRoleId.PositionalTypeArgument
  )

  private val expressionNestedTypeIdent = expressionTypeArgumentIdent(
    "expression-nested-type-ident",
    expressionTypeApplicationChildOccurrences("AppliedTypeTree", "tpt") ++
      expressionTypeApplicationChildOccurrences("AppliedTypeTree", "args"),
    GrammarRoleId.PositionalTypeArgument
  )

  private val expressionAppliedTypeArgument =
    Scala3PsiAppliedTypeProductions
      .appliedTypeProduction(
        "expression-type-argument-applied",
        expressionTypeApplicationChildOccurrences("TypeApply", "args") ++
          expressionTypeApplicationChildOccurrences("AppliedTypeTree", "args"),
        Set(GrammarRoleId.PositionalTypeArgument)
      )
      .copy(children =
        Vector(
          ChildDeclaration(
            "constructor",
            "tpt",
            ChildCardinality.ExactlyOne,
            expressionNestedTypeIdent.id
          ),
          ChildDeclaration(
            "arguments",
            "args",
            ChildCardinality.Repeated(1, None),
            expressionNestedTypeIdent.id,
            Set("expression-type-argument-applied")
          )
        )
      )

  private val expressionNamedArgumentType = expressionTypeArgumentIdent(
    "expression-named-type-argument-type",
    expressionTypeApplicationChildOccurrences("NamedArg", "arg"),
    GrammarRoleId.SimpleType
  )

  private val expressionNamedTypeArgument = Scala3PsiProduction(
    id = "expression-named-type-argument",
    grammarRoleId = GrammarRoleId.NamedTypeArgument,
    pattern = CompilerProductionPattern(
      InventoryKind.Node,
      "NamedArg",
      Vector(
        CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)),
        CompilerFieldPattern("arg", CatalogValuePattern.Node)
      ),
      expressionTypeApplicationChildOccurrences("TypeApply", "args")
    ),
    dispositions = Vector(
      FieldDisposition("name", FieldDispositionKind.TerminalOrLayout),
      FieldDisposition("arg", FieldDispositionKind.Child)
    ),
    children = Vector(
      ChildDeclaration(
        "type",
        "arg",
        ChildCardinality.ExactlyOne,
        "expression-named-type-argument-type"
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
        TerminalIntervalSelector.BeforeChild("type"),
        TerminalLeafTarget.Token(NativePsiElementBindings.AssignmentTokenSurface, Some("=")),
        OccurrenceCardinality.ExactlyOne,
        PsiOutputRoleId.SourceTerminal
      )
    ),
    layouts = Vector(LayoutAlternative.None),
    recovery = RecoveryPolicy.Reject,
    targetSurfaceId = NamedTypeArgumentSurface,
    targetRequirement = TargetRequirement.Compatible,
    accessors = NamedTypeArgumentAccessors,
    persistence = PersistenceObligations.NotApplicable,
    navigation = Some(NavigationObligation.Self),
    outputTemplate = Some(
      LocalOutputCompositeTemplate(
        Vector(
          outputComposite(
            "named",
            None,
            OutputRangeDeclaration.CompilerPosition,
            PsiOutputRoleId.NamedTypeArgument,
            NamedTypeArgumentSurface,
            NamedTypeArgumentAccessors,
            TargetRequirement.Compatible
          ),
          outputComposite(
            "name",
            Some("named"),
            OutputRangeDeclaration.BoundaryDerived(
              OutputBoundary.ProductionPoint,
              OutputBoundary.ProductionNameEnd
            ),
            PsiOutputRoleId.StableReference,
            StableReferenceSurface,
            StableReferenceAccessors
          )
        ),
        Map("type" -> Some("named"))
      )
    ),
    outputRoleId = None
  )

  private def expressionTypeApplyProduction(
      id: String,
      named: Boolean,
      root: Boolean,
      appliedCandidate: Boolean = false,
      namedCandidate: Boolean = false
  ): Scala3PsiProduction =
    val nativeCandidate  = appliedCandidate || (root && (!named || namedCandidate))
    val argumentPattern  =
      if named then CatalogValuePattern.NodePrefix("NamedArg")
      else CatalogValuePattern.NodeExceptPrefix("NamedArg")
    val argumentId       = if named then "expression-named-type-argument" else "expression-type-argument-ident"
    val argumentRole     = if named then PsiOutputRoleId.NamedTypeArguments else PsiOutputRoleId.TypeArguments
    val argumentSurface  = if named then NamedTypeArgumentsSurface else TypeArgumentsSurface
    val argumentAccess   = if named then NamedTypeArgumentsAccessors else TypeArgumentsAccessors
    val argumentTarget   = if named then TargetRequirement.Compatible else TargetRequirement.Native
    val payload          = Option.when(root)(
      outputComposite(
        "payload",
        None,
        OutputRangeDeclaration.CompilerPosition,
        PsiOutputRoleId.ExpressionPayload,
        ExpressionPayloadSurface,
        ExpressionPayloadAccessors,
        TargetRequirement.Compatible
      )
    )
    val argumentParent   = Option.when(root)("payload")
    val nativeTemplate   = LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "generic-call",
          None,
          OutputRangeDeclaration.CompilerPosition,
          PsiOutputRoleId.GenericCall,
          GenericCallSurface,
          GenericCallAccessors
        ),
        outputComposite(
          "arguments",
          Some("generic-call"),
          OutputRangeDeclaration.BoundaryDerived(
            OutputBoundary.ChildEnd("fun", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly),
            OutputBoundary.ProductionEnd()
          ),
          argumentRole,
          argumentSurface,
          argumentAccess,
          argumentTarget
        )
      ),
      Map("fun" -> Some("generic-call"), "arguments" -> Some("arguments"))
    )
    val fallbackTemplate = LocalOutputCompositeTemplate(
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
      Map("fun" -> None, "arguments" -> None)
    )
    Scala3PsiProduction(
      id = id,
      grammarRoleId = GrammarRoleId.ExpressionTypeApply,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "TypeApply",
        Vector(
          CompilerFieldPattern("fun", CatalogValuePattern.Node),
          CompilerFieldPattern("args", CatalogValuePattern.Repeated(argumentPattern))
        ),
        if appliedCandidate then
          if named then NamedInvokedCandidateTypeApplyOccurrences else PositionalCandidateTypeApplyOccurrences
        else if root && (!named || namedCandidate) then
          DirectPayloadOwners.map: (owner, outer) =>
            CompilerProductionContextPattern(
              ContextPattern.ParentWithAncestor(owner.ownerKind, owner.ownerPrefix, owner.path, outer),
              SourceClassification.SourceReachable
            )
        else if root then expressionTypeApplicationRootOccurrences
        else expressionTypeApplicationChildOccurrences("Apply", "fun")
      ),
      dispositions = Vector(
        FieldDisposition("fun", FieldDispositionKind.Child),
        FieldDisposition("args", FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration(
          "fun",
          "fun",
          ChildCardinality.ExactlyOne,
          "type-application-output-free-ident",
          Set(
            "payload-descendant-select",
            "payload-output-free-select",
            "atomic-term-ident",
            "selection-expression"
          )
        ),
        ChildDeclaration(
          "arguments",
          "args",
          ChildCardinality.Repeated(1, None),
          argumentId,
          Option.when(!named)(expressionAppliedTypeArgument.id).toSet
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "type-application-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = if nativeCandidate then GenericCallSurface else ExpressionPayloadSurface,
      targetRequirement = if nativeCandidate then TargetRequirement.Native else TargetRequirement.Compatible,
      accessors = if nativeCandidate then GenericCallAccessors else ExpressionPayloadAccessors,
      persistence = PersistenceObligations.NotApplicable,
      navigation = Some(NavigationObligation.Self),
      outputTemplate = Option
        .when(!nativeCandidate)(
          LocalOutputCompositeTemplate(
            payload.toVector :+ outputComposite(
              "arguments",
              argumentParent,
              OutputRangeDeclaration.BoundaryDerived(
                OutputBoundary
                  .ChildEnd("fun", ChildOccurrenceSelector.First, PositionProvenancePolicy.SourceDerivedOnly),
                OutputBoundary.ProductionEnd()
              ),
              argumentRole,
              argumentSurface,
              argumentAccess,
              argumentTarget
            ),
            Map("fun" -> argumentParent, "arguments" -> Some("arguments"))
          )
        )
        .orElse(Option.when(appliedCandidate)(nativeTemplate)),
      outputRealizations = Option
        .when(root && (!named || namedCandidate) && !appliedCandidate)(
          Vector(
            OutputRealization(
              if namedCandidate then "named-type-application-native" else "positional-type-application-native",
              Vector.empty,
              nativeTemplate,
              requiredChildRoots = Vector(
                RequiredChildRootOutcome(
                  "fun",
                  ChildRootOutcome.One(
                    ChildOutcomeExpectation.OutputRoles(
                      Set(PsiOutputRoleId.TermReference, PsiOutputRoleId.SelectionExpression)
                    )
                  )
                ),
                RequiredChildRootOutcome(
                  "arguments",
                  ChildRootOutcome.All(
                    ChildOutcomeExpectation.OutputRoles(
                      Set(PsiOutputRoleId.SimpleType, PsiOutputRoleId.ParameterizedType)
                    )
                  )
                )
              ).map: requirement =>
                if namedCandidate && requirement.roleId == "arguments" then
                  RequiredChildRootOutcome(
                    "arguments",
                    ChildRootOutcome.All(ChildOutcomeExpectation.OutputRole(PsiOutputRoleId.NamedTypeArgument))
                  )
                else requirement
            ),
            OutputRealization(
              if namedCandidate then "named-type-application-payload" else "positional-type-application-payload",
              Vector.empty,
              fallbackTemplate,
              childClosureAbsorptions = Vector(
                ChildClosureAbsorption("fun", ChildRootOutcome.AnyReviewed),
                ChildClosureAbsorption("arguments", ChildRootOutcome.AnyReviewed)
              )
            )
          )
        )
        .getOrElse(Vector.empty),
      outputRoleId = None,
      additionalGrammarRoleIds = Set(GrammarRoleId.TypeArgumentList),
      realizationChoice = Option.when(root && (!named || namedCandidate) && !appliedCandidate)(
        RealizationChoice(
          Vector(if namedCandidate then "named-type-application-native" else "positional-type-application-native"),
          if namedCandidate then "named-type-application-payload" else "positional-type-application-payload",
          RealizationChoicePolicy.AtomicWholePlan
        )
      )
    )

  private def appliedCallProduction(named: Boolean): Scala3PsiProduction =
    val prefix            = if named then "named-invoked" else "positional-applied"
    val productionId      = s"$prefix-call-candidate"
    val nativeId          = s"$prefix-call-native"
    val fallbackId        = s"$prefix-call-payload"
    val funProduction     = if named then "named-invoked-type-application" else "positional-applied-type-apply-candidate"
    val argumentRoles     =
      if named then
        Set(PsiOutputRoleId.TermReference, PsiOutputRoleId.IntegerExpression, PsiOutputRoleId.StringExpression)
      else Set(PsiOutputRoleId.TermReference)
    val nativeTemplate    = LocalOutputCompositeTemplate(
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
              "fun",
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
      Map("fun" -> Some("method-call"), "args" -> Some("arguments"))
    )
    def nativeRealization = OutputRealization(
      nativeId,
      Vector.empty,
      nativeTemplate,
      evidenceConditions = Vector(
        EvidenceCondition.RootAttachment(
          AttachmentEvidence("KindOfApply", ParserAttachmentValue.Product("Using")),
          present = false
        )
      ),
      requiredChildRoots = Vector(
        RequiredChildRootOutcome(
          "fun",
          ChildRootOutcome.One(ChildOutcomeExpectation.OutputRole(PsiOutputRoleId.GenericCall))
        ),
        RequiredChildRootOutcome(
          "args",
          ChildRootOutcome.All(ChildOutcomeExpectation.OutputRoles(argumentRoles))
        )
      ),
      terminalIds = Some(Set("left-parenthesis", "right-parenthesis", "commas", "separator-evidence"))
    )
    Scala3PsiProduction(
      id = productionId,
      grammarRoleId = GrammarRoleId.ExpressionTypeApply,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Apply",
        Vector(
          CompilerFieldPattern("fun", CatalogValuePattern.NodePrefix("TypeApply")),
          CompilerFieldPattern("args", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
        ),
        DirectPayloadOwners.map: (owner, outer) =>
          CompilerProductionContextPattern(
            ContextPattern.ParentWithAncestor(owner.ownerKind, owner.ownerPrefix, owner.path, outer),
            SourceClassification.SourceReachable,
            ScannerEvidencePattern(
              required = Option.when(named)(ParserScannerTokenKind.Equals).toSet,
              forbidden = Option.when(!named)(ParserScannerTokenKind.Equals).toSet
            )
          )
      ),
      dispositions = Vector(
        FieldDisposition("fun", FieldDispositionKind.Child),
        FieldDisposition("args", FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration(
          "fun",
          "fun",
          ChildCardinality.ExactlyOne,
          if named then "payload-descendant-type-apply-named" else "payload-descendant-type-apply-positional",
          Set(funProduction)
        ),
        ChildDeclaration(
          "args",
          "args",
          ChildCardinality.Repeated(1, None),
          if named then "named-invoked-output-free-ident" else "type-application-output-free-number",
          if named then
            Set(
              "named-invoked-output-free-string",
              "named-invoked-output-free-integer",
              "type-application-output-free-literal",
              "type-application-output-free-number",
              "type-application-output-free-ident-argument",
              "atomic-term-ident",
              "named-invoked-literal-integer",
              "named-invoked-literal-string",
              "payload-descendant-apply",
              "payload-descendant-invoked-literal",
              "payload-descendant-named-arg"
            )
          else
            Set(
              "type-application-output-free-literal",
              "type-application-output-free-ident-argument",
              "atomic-term-ident"
            )
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "applied-call-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "left-parenthesis",
          TerminalIntervalSelector.BalancedScannerTokenAfterChild(
            ParserScannerTokenKind.LeftParenthesis,
            ParserScannerTokenKind.LeftParenthesis,
            ParserScannerTokenKind.RightParenthesis,
            "fun",
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
            "fun",
            ScannerTokenOccurrence.Last
          ),
          TerminalLeafTarget.Token(NativePsiElementBindings.TypeRightParenthesisTokenSurface),
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        ),
        TerminalDeclaration(
          "commas",
          TerminalIntervalSelector.ChildSeparators("args"),
          TerminalLeafTarget.Token(NativePsiElementBindings.TypeCommaTokenSurface, Some(",")),
          OccurrenceCardinality.Repeated(0, None),
          PsiOutputRoleId.SourceTerminal,
          ownsStructuralEvidence = Some(false)
        ),
        TerminalDeclaration(
          "separator-evidence",
          TerminalIntervalSelector.ChildSeparators("args"),
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.Repeated(0, None),
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
        nativeRealization,
        OutputRealization(
          fallbackId,
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
            Map("fun" -> None, "args" -> None)
          ),
          childClosureAbsorptions = Vector(
            ChildClosureAbsorption("fun", ChildRootOutcome.AnyReviewed),
            ChildClosureAbsorption("args", ChildRootOutcome.AnyReviewed)
          ),
          terminalIds = Some(Set("applied-call-text"))
        )
      ),
      outputRoleId = None,
      realizationChoice = Some(
        RealizationChoice(
          Vector(nativeId),
          fallbackId,
          RealizationChoicePolicy.AtomicWholePlan,
          if named then Vector.empty
          else
            Vector(
              RequiredChildRootOutcome(
                "fun",
                ChildRootOutcome.One(ChildOutcomeExpectation.OutputRole(PsiOutputRoleId.TypeArguments))
              ),
              RequiredChildRootOutcome(
                "args",
                ChildRootOutcome.All(
                  ChildOutcomeExpectation.Production("type-application-output-free-ident-argument")
                )
              )
            )
        )
      )
    )

  private def expressionAppliedCallProduction: Scala3PsiProduction =
    Scala3PsiProduction(
      id = "definition-payload-applied-call",
      grammarRoleId = GrammarRoleId.ExpressionTypeApply,
      pattern = CompilerProductionPattern(
        InventoryKind.Node,
        "Apply",
        Vector(
          CompilerFieldPattern("fun", CatalogValuePattern.NodePrefix("TypeApply")),
          CompilerFieldPattern("args", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
        ),
        expressionTypeApplicationRootOccurrences
      ),
      dispositions = Vector(
        FieldDisposition("fun", FieldDispositionKind.Child),
        FieldDisposition("args", FieldDispositionKind.Child)
      ),
      children = Vector(
        ChildDeclaration(
          "fun",
          "fun",
          ChildCardinality.ExactlyOne,
          "payload-descendant-type-apply-positional",
          Set("payload-descendant-type-apply-named")
        ),
        ChildDeclaration(
          "args",
          "args",
          ChildCardinality.Repeated(0, None),
          "type-application-output-free-number",
          Set("type-application-output-free-literal", "type-application-output-free-ident-argument")
        )
      ),
      terminals = Vector(
        TerminalDeclaration(
          "applied-call-text",
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
          Map("fun" -> Some("payload"), "args" -> Some("payload"))
        )
      ),
      outputRoleId = None
    )

  private val definitionPayloadProductions = Vector(
    payloadRoot(
      "definition-payload-match",
      "Match",
      Vector(
        CompilerFieldPattern("selector", CatalogValuePattern.Node),
        CompilerFieldPattern("cases", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
      ),
      Vector.empty
    ),
    payloadRoot(
      "definition-payload-number",
      "Number",
      Vector(
        CompilerFieldPattern("digits", CatalogValuePattern.Scalar("Text")),
        CompilerFieldPattern(
          "kind",
          CatalogValuePattern
            .Product("Whole", Vector(CompilerFieldPattern("radix", CatalogValuePattern.Scalar("Integer"))))
        )
      ),
      Vector.empty,
      nonAtomicDefinitionChildOccurrences("preRhs") ++ negativeNumberOccurrences("preRhs")
    ),
    payloadRoot(
      "definition-payload-ident",
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.Name)),
      Vector.empty,
      nonAtomicDefinitionChildOccurrences("preRhs")
    ),
    payloadRoot(
      "definition-payload-apply",
      "Apply",
      Vector(
        CompilerFieldPattern("fun", CatalogValuePattern.NodeExceptPrefix("TypeApply")),
        CompilerFieldPattern("args", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
      ),
      Vector(
        ChildDeclaration(
          "fun",
          "fun",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        ),
        ChildDeclaration(
          "args",
          "args",
          ChildCardinality.Repeated(0, None),
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        )
      )
    ),
    payloadRoot(
      "definition-payload-select",
      "Select",
      Vector(
        CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
        CompilerFieldPattern("name", CatalogValuePattern.Name)
      ),
      Vector(
        ChildDeclaration(
          "qualifier",
          "qualifier",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        )
      ),
      nonAtomicDefinitionChildOccurrences("preRhs")
    ),
    payloadRoot(
      "definition-payload-tuple",
      "Tuple",
      Vector(CompilerFieldPattern("trees", CatalogValuePattern.Repeated(CatalogValuePattern.Node))),
      Vector(
        ChildDeclaration(
          "trees",
          "trees",
          ChildCardinality.Repeated(1, None),
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        )
      )
    ),
    payloadRoot(
      "definition-payload-block",
      "Block",
      Vector(
        CompilerFieldPattern("stats", CatalogValuePattern.Repeated(CatalogValuePattern.Node)),
        CompilerFieldPattern("expr", CatalogValuePattern.Node)
      ),
      Vector(
        ChildDeclaration(
          "stats",
          "stats",
          ChildCardinality.Repeated(0, None),
          payloadLocalDefinitionIds.head,
          payloadLocalDefinitionIds.tail
        ),
        ChildDeclaration(
          "expr",
          "expr",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        )
      )
    ),
    payloadRoot(
      "definition-payload-infix",
      "InfixOp",
      Vector(
        CompilerFieldPattern("left", CatalogValuePattern.Node),
        CompilerFieldPattern("op", CatalogValuePattern.Node),
        CompilerFieldPattern("right", CatalogValuePattern.Node)
      ),
      Vector(
        ChildDeclaration(
          "left",
          "left",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        ),
        ChildDeclaration(
          "op",
          "op",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        ),
        ChildDeclaration(
          "right",
          "right",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        )
      )
    ),
    expressionTypeApplyProduction("definition-payload-type-apply-positional", named = false, root = true),
    expressionTypeApplyProduction("definition-payload-type-apply-named", named = true, root = true),
    expressionTypeApplyProduction(
      "named-type-application-candidate",
      named = true,
      root = true,
      namedCandidate = true
    ),
    appliedCallProduction(named = false),
    appliedCallProduction(named = true),
    expressionAppliedCallProduction
  )

  private def payloadDescendant(
      id: String,
      prefix: String,
      fields: Vector[CompilerFieldPattern],
      dispositions: Vector[FieldDisposition],
      children: Vector[ChildDeclaration],
      grammarRoleId: GrammarRoleId = GrammarRoleId.ExpressionPayload
  ) =
    val anchors                      = Vector("DefDef", "ValDef").map(owner =>
      InventoryAncestor(InventoryKind.Node, owner, Vector(CatalogPathSegment.NamedField("preRhs")))
    )
    val parents                      = Vector(
      "Apply"     -> Vector(CatalogPathSegment.NamedField("fun")),
      "Apply"     -> Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
      "TypeApply" -> Vector(CatalogPathSegment.NamedField("fun")),
      "NamedArg"  -> Vector(CatalogPathSegment.NamedField("arg")),
      "Select"    -> Vector(CatalogPathSegment.NamedField("qualifier")),
      "Typed"     -> Vector(CatalogPathSegment.NamedField("expr")),
      "Tuple"     -> Vector(CatalogPathSegment.NamedField("trees"), CatalogPathSegment.RepeatedElement),
      "Block"     -> Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement),
      "Block"     -> Vector(CatalogPathSegment.NamedField("expr")),
      "InfixOp"   -> Vector(CatalogPathSegment.NamedField("left")),
      "InfixOp"   -> Vector(CatalogPathSegment.NamedField("op")),
      "InfixOp"   -> Vector(CatalogPathSegment.NamedField("right"))
    )
    val contextParents               =
      if id == "payload-descendant-select" then parents.filterNot(_._1 == "Select")
      else if id == "payload-descendant-ident" then
        parents.filterNot(parent => Set("TypeApply", "NamedArg", "Select")(parent._1))
      else parents.filterNot(_._1 == "TypeApply")
    val outputFree                   = Set("payload-output-free-ident", "payload-output-free-select").contains(id)
    val candidateFallbackOccurrences =
      Option
        .when(
          Set("payload-descendant-apply", "payload-descendant-invoked-literal")(id)
        )(
          ExpressionTypeApplicationAnchors.map: anchor =>
            CompilerProductionContextPattern(
              ContextPattern.ParentWithNodeFieldUnderAnchor(
                InventoryKind.Node,
                "Apply",
                Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
                "fun",
                "TypeApply",
                anchor
              ),
              SourceClassification.SourceReachable
            )
        )
        .toVector
        .flatten
    Scala3PsiProduction(
      id,
      grammarRoleId,
      CompilerProductionPattern(
        InventoryKind.Node,
        prefix,
        fields,
        if outputFree then
          val routes =
            if id == "payload-output-free-ident" then
              val excluded = Set(nodeEdge("Select", "qualifier"), nodeEdge("TypeApply", "fun"))
              SelectionRootPaths.filterNot(route => route.descendantPath.headOption.exists(excluded)) ++
                NamedInvokedSelectionQualifierRoutes
            else SelectionRootPaths ++ LocalSelectionRootRoutes
          Vector(
            CompilerProductionContextPattern(
              ContextPattern.DescendantOfOwnedRoot(routes),
              SourceClassification.SourceReachable
            )
          )
        else
          anchors.flatMap(anchor =>
            contextParents.map((parent, path) =>
              CompilerProductionContextPattern(
                if parent == "Apply" && path.headOption.contains(CatalogPathSegment.NamedField("args")) then
                  ContextPattern.ParentWithoutNodeFieldPrefixUnderAnchor(
                    InventoryKind.Node,
                    parent,
                    path,
                    "fun",
                    "TypeApply",
                    anchor
                  )
                else ContextPattern.ParentUnderAnchor(InventoryKind.Node, parent, path, anchor),
                SourceClassification.SourceReachable
              )
            )
          ) ++ candidateFallbackOccurrences
      ),
      dispositions,
      children,
      Vector(
        TerminalDeclaration(
          "payload-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.ExactlyOne,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      Vector(LayoutAlternative.None),
      RecoveryPolicy.Reject,
      ExpressionPayloadSurface,
      TargetRequirement.Compatible,
      if outputFree then Vector.empty else ExpressionPayloadAccessors,
      PersistenceObligations.NotApplicable,
      Option.when(!outputFree)(NavigationObligation.Self),
      Some(
        if outputFree then transparentTemplate(children.map(_.roleId)*)
        else
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
            children.map(_.roleId -> Some("payload")).toMap
          )
      ),
      Vector.empty,
      None
    )

  private def payloadDescendantProductions = Vector(
    payloadDescendant(
      "payload-descendant-ident",
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.Name)),
      Vector(FieldDisposition("name", FieldDispositionKind.SemanticOnly)),
      Vector.empty
    ),
    payloadDescendant(
      "payload-output-free-ident",
      "Ident",
      Vector(CompilerFieldPattern("name", CatalogValuePattern.Name)),
      Vector(FieldDisposition("name", FieldDispositionKind.SemanticOnly)),
      Vector.empty,
      GrammarRoleId.OutputFreeExpression
    ),
    payloadDescendant(
      "payload-descendant-number",
      "Number",
      Vector(
        CompilerFieldPattern("digits", CatalogValuePattern.Scalar("Text")),
        CompilerFieldPattern(
          "kind",
          CatalogValuePattern
            .Product("Whole", Vector(CompilerFieldPattern("radix", CatalogValuePattern.Scalar("Integer"))))
        )
      ),
      Vector(
        FieldDisposition("digits", FieldDispositionKind.SemanticOnly),
        FieldDisposition("kind", FieldDispositionKind.SemanticOnly)
      ),
      Vector.empty
    ),
    payloadDescendant(
      "payload-descendant-invoked-literal",
      "Literal",
      Vector(
        CompilerFieldPattern(
          "const",
          CatalogValuePattern.Product(
            "",
            Vector(
              CompilerFieldPattern(
                "",
                CatalogValuePattern.AnyOf(
                  Vector(
                    CatalogValuePattern.Scalar("LongInteger"),
                    CatalogValuePattern.Scalar("FloatDecimal"),
                    CatalogValuePattern.Scalar("Decimal"),
                    CatalogValuePattern.Scalar("Logical"),
                    CatalogValuePattern.Scalar("Character"),
                    CatalogValuePattern.Scalar("NullValue")
                  )
                )
              )
            )
          )
        )
      ),
      Vector(FieldDisposition("const", FieldDispositionKind.SemanticOnly)),
      Vector.empty
    ),
    payloadDescendant(
      "payload-descendant-apply",
      "Apply",
      Vector(
        CompilerFieldPattern("fun", CatalogValuePattern.NodeExceptPrefix("TypeApply")),
        CompilerFieldPattern("args", CatalogValuePattern.Repeated(CatalogValuePattern.Node))
      ),
      Vector(FieldDisposition("fun", FieldDispositionKind.Child), FieldDisposition("args", FieldDispositionKind.Child)),
      Vector(
        ChildDeclaration(
          "fun",
          "fun",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail + "atomic-term-ident"
        ),
        ChildDeclaration(
          "args",
          "args",
          ChildCardinality.Repeated(0, None),
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail + "atomic-term-ident"
        )
      )
    ),
    payloadDescendant(
      "payload-descendant-select",
      "Select",
      Vector(
        CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
        CompilerFieldPattern("name", CatalogValuePattern.Name)
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
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail ++ Set(
            "payload-qualifier-ident",
            "payload-qualifier-this",
            "payload-qualifier-super"
          )
        )
      )
    ),
    payloadDescendant(
      "payload-output-free-select",
      "Select",
      Vector(
        CompilerFieldPattern("qualifier", CatalogValuePattern.Node),
        CompilerFieldPattern("name", CatalogValuePattern.Name)
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
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail ++ Set(
            "payload-qualifier-ident",
            "payload-qualifier-this",
            "payload-qualifier-super"
          )
        )
      ),
      GrammarRoleId.OutputFreeExpression
    ),
    payloadDescendant(
      "payload-descendant-tuple",
      "Tuple",
      Vector(CompilerFieldPattern("trees", CatalogValuePattern.Repeated(CatalogValuePattern.Node))),
      Vector(FieldDisposition("trees", FieldDispositionKind.Child)),
      Vector(
        ChildDeclaration(
          "trees",
          "trees",
          ChildCardinality.Repeated(1, None),
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        )
      )
    ),
    payloadDescendant(
      "payload-descendant-block",
      "Block",
      Vector(
        CompilerFieldPattern("stats", CatalogValuePattern.Repeated(CatalogValuePattern.Node)),
        CompilerFieldPattern("expr", CatalogValuePattern.Node)
      ),
      Vector(
        FieldDisposition("stats", FieldDispositionKind.Child),
        FieldDisposition("expr", FieldDispositionKind.Child)
      ),
      Vector(
        ChildDeclaration(
          "stats",
          "stats",
          ChildCardinality.Repeated(0, None),
          payloadLocalDefinitionIds.head,
          payloadLocalDefinitionIds.tail
        ),
        ChildDeclaration(
          "expr",
          "expr",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        )
      )
    ),
    payloadDescendant(
      "payload-descendant-infix",
      "InfixOp",
      Vector(
        CompilerFieldPattern("left", CatalogValuePattern.Node),
        CompilerFieldPattern("op", CatalogValuePattern.Node),
        CompilerFieldPattern("right", CatalogValuePattern.Node)
      ),
      Vector(
        FieldDisposition("left", FieldDispositionKind.Child),
        FieldDisposition("op", FieldDispositionKind.Child),
        FieldDisposition("right", FieldDispositionKind.Child)
      ),
      Vector(
        ChildDeclaration(
          "left",
          "left",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        ),
        ChildDeclaration(
          "op",
          "op",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        ),
        ChildDeclaration(
          "right",
          "right",
          ChildCardinality.ExactlyOne,
          payloadExpressionProductionIds.head,
          payloadExpressionProductionIds.tail
        )
      )
    ),
    expressionTypeApplyProduction("payload-descendant-type-apply-positional", named = false, root = false),
    expressionTypeApplyProduction("payload-descendant-type-apply-named", named = true, root = false),
    payloadLocalDefinition("payload-descendant-val", 0L, mutable = false),
    payloadLocalDefinition("payload-descendant-var", 4097L, mutable = true),
    payloadQualifierIdent,
    payloadQualifierThis,
    payloadQualifierSuper,
    payloadDescendantNamedArg
  )

  private def payloadQualifierOccurrences(
      owner: String,
      path: Vector[CatalogPathSegment],
      sourceClassification: SourceClassification
  ): Vector[CompilerProductionContextPattern] =
    val direct   = InventoryAncestor(InventoryKind.Node, owner, path)
    val prefixes =
      if owner == "Select" then Vector(Vector.empty)
      else if owner == "This" then Vector(Vector(direct), Vector(direct, nodeEdge("Super", "qual")))
      else Vector(Vector(direct))
    Vector(
      CompilerProductionContextPattern(
        ContextPattern.DescendantOfOwnedRoot(qualifierRoutes(prefixes)),
        sourceClassification
      )
    )

  private def outputFreeQualifier(
      id: String,
      prefix: String,
      fields: Vector[CompilerFieldPattern],
      dispositions: Vector[FieldDisposition],
      children: Vector[ChildDeclaration],
      occurrences: Vector[CompilerProductionContextPattern],
      directEvidence: Vector[DirectNodeFieldEvidence] = Vector.empty
  ): Scala3PsiProduction =
    Scala3PsiProduction(
      id = id,
      grammarRoleId = GrammarRoleId.OutputFreeExpression,
      pattern = CompilerProductionPattern(InventoryKind.Node, prefix, fields, occurrences, directEvidence),
      dispositions = dispositions,
      children = children,
      terminals = Vector(
        TerminalDeclaration(
          s"$id-text",
          TerminalIntervalSelector.WholeProduction,
          TerminalLeafTarget.Parent,
          OccurrenceCardinality.Optional,
          PsiOutputRoleId.SourceTerminal
        )
      ),
      layouts = Vector(LayoutAlternative.None),
      recovery = RecoveryPolicy.Reject,
      targetSurfaceId = ExpressionPayloadSurface,
      targetRequirement = TargetRequirement.Compatible,
      accessors = Vector.empty,
      persistence = PersistenceObligations.NotApplicable,
      navigation = None,
      outputTemplate = Some(transparentTemplate(children.map(_.roleId)*)),
      outputRoleId = None
    )

  private val payloadQualifierIdent = outputFreeQualifier(
    "payload-qualifier-ident",
    "Ident",
    Vector(CompilerFieldPattern("name", CatalogValuePattern.Name)),
    Vector(FieldDisposition("name", FieldDispositionKind.SemanticOnly)),
    Vector.empty,
    Vector(
      CompilerProductionContextPattern(
        ContextPattern.ParentWithAncestor(
          InventoryKind.Node,
          "Select",
          Vector(CatalogPathSegment.NamedField("qualifier")),
          nodeEdge("TypeApply", "fun")
        ),
        SourceClassification.SourceReachable
      )
    ) ++ payloadQualifierOccurrences(
      "Select",
      Vector(CatalogPathSegment.NamedField("qualifier")),
      SourceClassification.SourceReachable
    ) ++ Scala3PsiRepeatedArgumentProductions.CandidateSelectedOperandQualifierFallbackOccurrences ++ payloadQualifierOccurrences(
      "This",
      Vector(CatalogPathSegment.NamedField("qual")),
      SourceClassification.SourceReachable
    ) ++ payloadQualifierOccurrences(
      "This",
      Vector(CatalogPathSegment.NamedField("qual")),
      SourceClassification.Absent
    ) ++ payloadQualifierOccurrences(
      "Super",
      Vector(CatalogPathSegment.NamedField("mix")),
      SourceClassification.SourceReachable
    ) ++ payloadQualifierOccurrences(
      "Super",
      Vector(CatalogPathSegment.NamedField("mix")),
      SourceClassification.Absent
    )
  )

  private val payloadQualifierThis = outputFreeQualifier(
    "payload-qualifier-this",
    "This",
    Vector(CompilerFieldPattern("qual", CatalogValuePattern.NodePrefix("Ident"))),
    Vector(FieldDisposition("qual", FieldDispositionKind.Child)),
    Vector(ChildDeclaration("qualifier", "qual", ChildCardinality.ExactlyOne, "payload-qualifier-ident")),
    payloadQualifierOccurrences(
      "Select",
      Vector(CatalogPathSegment.NamedField("qualifier")),
      SourceClassification.SourceReachable
    ) ++ payloadQualifierOccurrences(
      "Super",
      Vector(CatalogPathSegment.NamedField("qual")),
      SourceClassification.Synthetic
    ) ++ payloadQualifierOccurrences(
      "Super",
      Vector(CatalogPathSegment.NamedField("qual")),
      SourceClassification.SourceReachable
    )
  )

  private val payloadQualifierSuper = outputFreeQualifier(
    "payload-qualifier-super",
    "Super",
    Vector(
      CompilerFieldPattern("qual", CatalogValuePattern.NodePrefix("This")),
      CompilerFieldPattern("mix", CatalogValuePattern.NodePrefix("Ident"))
    ),
    Vector(FieldDisposition("qual", FieldDispositionKind.Child), FieldDisposition("mix", FieldDispositionKind.Child)),
    Vector(
      ChildDeclaration("owner", "qual", ChildCardinality.ExactlyOne, "payload-qualifier-this"),
      ChildDeclaration("mixin", "mix", ChildCardinality.ExactlyOne, "payload-qualifier-ident")
    ),
    payloadQualifierOccurrences(
      "Select",
      Vector(CatalogPathSegment.NamedField("qualifier")),
      SourceClassification.SourceReachable
    )
  )

  private val payloadDescendantNamedArg = outputFreeQualifier(
    "payload-descendant-named-arg",
    "NamedArg",
    Vector(
      CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)),
      CompilerFieldPattern("arg", CatalogValuePattern.Node)
    ),
    Vector(
      FieldDisposition("name", FieldDispositionKind.SemanticOnly),
      FieldDisposition("arg", FieldDispositionKind.Child)
    ),
    Vector(
      ChildDeclaration(
        "argument",
        "arg",
        ChildCardinality.ExactlyOne,
        payloadExpressionProductionIds.head,
        payloadExpressionProductionIds.tail + "expression-named-type-argument-type"
      )
    ),
    Vector(
      CompilerProductionContextPattern(
        ContextPattern.DescendantOfOwnedRoot(
          ownedRootRoutes(
            Vector("definition-payload-apply", "payload-descendant-apply"),
            Vector(Vector(nodeEdge("Apply", "args", repeated = true)))
          ) ++ Vector(
            OwnedRootRoute(
              "definition-payload-apply",
              Vector(nodeEdge("Apply", "args", repeated = true)),
              nodeEdge("ValDef", "preRhs"),
              nodeEdge("Block", "stats", repeated = true)
            )
          )
        ),
        SourceClassification.SourceReachable
      )
    ) ++ Scala3PsiNamedArgumentProductions.CandidateArgumentFallbackOccurrences ++
      ExpressionTypeApplicationAnchors.map: anchor =>
        CompilerProductionContextPattern(
          ContextPattern.ParentWithNodeFieldUnderAnchor(
            InventoryKind.Node,
            "Apply",
            Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
            "fun",
            "TypeApply",
            anchor
          ),
          SourceClassification.SourceReachable
        )
  )

  private def payloadLocalDefinition(id: String, flags: Long, mutable: Boolean): Scala3PsiProduction =
    val modifiers =
      if mutable then
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
    payloadDescendant(
      id,
      "ValDef",
      Vector(
        CompilerFieldPattern("name", CatalogValuePattern.ClassifiedName(NeutralNameClass.Ordinary)),
        CompilerFieldPattern("tpt", CatalogValuePattern.Node),
        CompilerFieldPattern("preRhs", CatalogValuePattern.Node),
        CompilerFieldPattern("mods", modifiers)
      ),
      Vector(
        FieldDisposition("name", FieldDispositionKind.SemanticOnly),
        FieldDisposition("tpt", FieldDispositionKind.Child),
        FieldDisposition("preRhs", FieldDispositionKind.Child),
        FieldDisposition("mods", FieldDispositionKind.SemanticOnly)
      ),
      Vector(
        ChildDeclaration("inferred-type", "tpt", ChildCardinality.ExactlyOne, "definition-inferred-type-absence"),
        ChildDeclaration(
          "payload",
          "preRhs",
          ChildCardinality.ExactlyOne,
          payloadRootIds.head,
          payloadRootIds.tail
        )
      ),
      GrammarRoleId.OutputFreeExpression
    ).copy(outputTemplate = Some(transparentTemplate("inferred-type", "payload")))

  private[psiproducer] val DefinitionPayloadSegment: Vector[Scala3PsiProduction] =
    definitionPayloadProductions ++ payloadDescendantProductions ++ Vector(
      typeApplicationOutputFreeIdent,
      namedInvokedOutputFreeInteger,
      namedInvokedOutputFreeString,
      namedInvokedOutputFreeIdent,
      namedTermOutputFreeInteger,
      namedTermOutputFreeString,
      namedInvokedIntegerLiteral,
      namedInvokedStringLiteral,
      appliedCallOutputFreeIdent,
      appliedCallOutputFreeNumber,
      appliedCallOutputFreeLiteral,
      expressionTypeApplyProduction(
        "positional-applied-type-apply-candidate",
        named = false,
        root = false,
        appliedCandidate = true
      ),
      expressionTypeApplyProduction(
        "named-invoked-type-application",
        named = true,
        root = false,
        appliedCandidate = true
      ),
      expressionPositionalTypeArgument,
      expressionNestedTypeIdent,
      expressionAppliedTypeArgument,
      expressionNamedArgumentType,
      expressionNamedTypeArgument
    )
