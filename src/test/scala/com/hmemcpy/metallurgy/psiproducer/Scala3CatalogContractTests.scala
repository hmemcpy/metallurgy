package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.ParserScannerTokenKind
import org.junit.Assert.*
import org.junit.Test

private[psiproducer] trait Scala3CatalogContractTests extends Scala3PsiProductionCatalogTestSupport:
  @Test def reviewedCatalogOwnsClosedGrammarAndOutputRoleInventories(): Unit =
    val catalog  = Scala3PsiProductionCatalog.Reviewed
    val expected = Map(
      GrammarRoleId.CompilationUnit           -> Set("file-top-statements"),
      GrammarRoleId.PackageClause             -> Set("file-package", "file-package-top-statements"),
      GrammarRoleId.PackageReference          -> Set("file-import-empty-package"),
      GrammarRoleId.ImportStatement           -> Set("import-statement"),
      GrammarRoleId.ExportStatement           -> Set("export-statement"),
      GrammarRoleId.AbsentProduct             -> Set(
        "import-expression-absent",
        "import-selector-absent",
        "import-selector-given-bound-absent",
        "atomic-this-empty-qualifier",
        "selection-super-empty-mixin",
        "template-absent-tree"
      ),
      GrammarRoleId.StableReference           -> Set(
        "match-pattern-dotted-reference",
        "match-pattern-dotted-reference-ident",
        "import-path-identifier-reference",
        "import-path-reference",
        "import-path-identifier",
        "package-stable-identifier-reference",
        "package-stable-reference",
        "package-stable-identifier",
        "import-selector-given-bound-qualifier-ident",
        "import-selector-given-bound-qualifier-select",
        "infix-type-operator",
        "annotation-designator-qualifier-ident",
        "annotation-designator-qualifier-select",
        "type-atom-singleton-reference-ident",
        "type-atom-singleton-reference-select",
        "capture-reference-ident",
        "atomic-this-qualifier"
      ),
      GrammarRoleId.TermReference             -> Set("atomic-term-ident"),
      GrammarRoleId.ThisReference             -> Set("atomic-this-unqualified", "selection-this-unqualified"),
      GrammarRoleId.QualifiedThisReference    -> Set("atomic-this-qualified", "selection-this-qualified"),
      GrammarRoleId.SelectionExpression       -> Set("selection-expression"),
      GrammarRoleId.OrdinaryApplication       -> Set(
        "ordinary-application-candidate",
        "named-term-application-candidate"
      ),
      GrammarRoleId.NamedArgument             -> Set("term-named-argument"),
      GrammarRoleId.RepeatedTermArgument      -> Set(
        "repeated-term-application-candidate",
        "term-repeated-argument"
      ),
      GrammarRoleId.SuperReference            -> Set("selection-super-reference"),
      GrammarRoleId.SelectionQualifier        -> Set(
        "selection-qualifier-ident",
        "selection-super-this-unqualified",
        "selection-super-this-qualified",
        "selection-super-mixin"
      ),
      GrammarRoleId.ExpressionIntegerLiteral  -> Set(
        "atomic-literal-integer",
        "named-invoked-literal-integer"
      ),
      GrammarRoleId.ExpressionLongLiteral     -> Set("atomic-literal-long"),
      GrammarRoleId.ExpressionFloatLiteral    -> Set("atomic-literal-float"),
      GrammarRoleId.ExpressionDoubleLiteral   -> Set("atomic-literal-double"),
      GrammarRoleId.ExpressionBooleanLiteral  -> Set("atomic-literal-boolean"),
      GrammarRoleId.ExpressionCharLiteral     -> Set("atomic-literal-char"),
      GrammarRoleId.ExpressionStringLiteral   -> Set("atomic-literal-string", "named-invoked-literal-string"),
      GrammarRoleId.ExpressionNullLiteral     -> Set("atomic-literal-null"),
      GrammarRoleId.ImportSelector            -> Set("import-selector-direct", "import-selector-braced"),
      GrammarRoleId.ImportSelectorName        -> Set(
        "import-selector-name",
        "import-selector-hidden-name",
        "import-selector-wildcard-name",
        "import-selector-empty-name"
      ),
      GrammarRoleId.SimpleType                -> Set(
        "match-pattern-dotted-type",
        "import-selector-bound-type",
        "import-selector-given-bound-qualified-type",
        "annotation-designator-ident",
        "annotation-designator-select",
        "match-type-pattern-reference",
        "expression-named-type-argument-type",
        "capture-function-result-ident",
        "match-pattern-type-ident"
      ),
      GrammarRoleId.TypeProjection            -> Set("type-atom-projection", "match-pattern-hash-projection"),
      GrammarRoleId.SingletonType             -> Set("type-atom-singleton-ident", "type-atom-singleton-select"),
      GrammarRoleId.LiteralType               -> Set("type-atom-literal"),
      GrammarRoleId.ParenthesizedType         -> Set("type-atom-parenthesized"),
      GrammarRoleId.TupleType                 -> Set("ordinary-tuple-type", "match-pattern-tuple-type"),
      GrammarRoleId.NamedTupleType            -> Set("named-tuple-type"),
      GrammarRoleId.NamedTupleComponent       -> Set("named-tuple-component"),
      GrammarRoleId.FunctionType              -> Set(
        "ordinary-function-type",
        "pure-nullary-function-type",
        "pure-function-type",
        "capture-nullary-function-type",
        "capture-function-type"
      ),
      GrammarRoleId.DependentFunctionType     -> Set("dependent-function-type"),
      GrammarRoleId.PolyFunctionType          -> Set("polymorphic-function-type"),
      GrammarRoleId.ByNameParameterType       -> Set(
        "by-name-parameter-type",
        "impure-by-name-parameter-type",
        "pure-by-name-parameter-type",
        "capture-by-name-parameter-type"
      ),
      GrammarRoleId.RepeatedParameterType     -> Set("repeated-parameter-type"),
      GrammarRoleId.RepeatedParameterStar     -> Set("repeated-parameter-synthetic-star"),
      GrammarRoleId.LiteralValue              -> Set(
        "type-atom-literal-value-integer",
        "type-atom-literal-value-long",
        "type-atom-literal-value-float",
        "type-atom-literal-value-double",
        "type-atom-literal-value-char",
        "type-atom-literal-value-string",
        "type-atom-literal-value-boolean"
      ),
      GrammarRoleId.AppliedType               -> Set(
        "import-selector-bound-applied-type",
        "match-pattern-applied-type",
        "ordinary-applied-type",
        "type-argument-applied",
        "expression-type-argument-applied"
      ),
      GrammarRoleId.TypeBounds                -> Set(
        "higher-kinded-result-bounds",
        "context-bound-base-bounds",
        "type-parameter-bounds",
        "type-alias-bounds"
      ),
      GrammarRoleId.ContextBounds             -> Set(
        "template-context-bounded-type-parameter-invariant",
        "template-context-bounded-type-parameter-covariant",
        "template-context-bounded-type-parameter-contravariant",
        "function-context-bounded-type-parameter",
        "type-parameter-context-bounds"
      ),
      GrammarRoleId.ContextBound              -> Set(
        "type-parameter-context-bound",
        "type-parameter-named-context-bound",
        "type-parameter-synthetic-context-bound"
      ),
      GrammarRoleId.TypeLambda                -> Set("explicit-type-lambda"),
      GrammarRoleId.TermLambda                -> Set("type-definition-term-lambda"),
      GrammarRoleId.TypeArgumentList          -> Set(
        "import-selector-bound-applied-type",
        "match-pattern-applied-type",
        "ordinary-applied-type",
        "type-argument-applied",
        "expression-type-argument-applied",
        "definition-payload-type-apply-positional",
        "definition-payload-type-apply-named",
        "payload-descendant-type-apply-positional",
        "payload-descendant-type-apply-named",
        "positional-applied-type-apply-candidate",
        "named-type-application-candidate",
        "named-invoked-type-application"
      ),
      GrammarRoleId.PositionalTypeArgument    -> Set(
        "type-argument-ident",
        "type-argument-applied",
        "expression-type-argument-ident",
        "expression-nested-type-ident",
        "expression-type-argument-applied"
      ),
      GrammarRoleId.NamedTypeArgument         -> Set("expression-named-type-argument"),
      GrammarRoleId.WildcardType              -> Set(
        "import-selector-given-bound-wildcard-type",
        "ordinary-wildcard-type",
        "match-pattern-wildcard-type"
      ),
      GrammarRoleId.InfixType                 -> Set("ordinary-infix-type"),
      GrammarRoleId.MatchType                 -> Set("ordinary-match-type"),
      GrammarRoleId.MatchTypeCase             -> Set("match-type-case"),
      GrammarRoleId.RefinementType            -> Set("ordinary-refinement-type"),
      GrammarRoleId.AnnotatedType             -> Set("ordinary-annotated-type"),
      GrammarRoleId.CaptureType               -> Set("capture-type-shorthand", "capture-type-explicit-set"),
      GrammarRoleId.CaptureSet                -> Set(
        "capture-type-explicit-set",
        "capture-annotation-apply",
        "capture-synthetic-select",
        "capture-synthetic-new",
        "capture-synthetic-type-apply",
        "capture-set-group",
        "capture-synthetic-typed-splice",
        "capture-synthetic-type-tree",
        "capture-synthetic-ident",
        "by-name-captures-and-result",
        "capture-function-result"
      ),
      GrammarRoleId.CaptureReference          -> Set(
        "capture-reference",
        "capture-function-reference",
        "capture-function-qualified-reference",
        "capture-reference-modifier-reach",
        "capture-reference-modifier-read-only",
        "capture-function-reference-modifier-reach",
        "capture-function-reference-modifier-read-only"
      ),
      GrammarRoleId.CaptureFilter             -> Set(
        "capture-reference-modifier-filter",
        "capture-function-reference-modifier-filter"
      ),
      GrammarRoleId.CaptureSynthetic          -> Set(
        "by-name-capture-root-select",
        "by-name-capture-root-middle-select",
        "by-name-capture-root-inner-select",
        "by-name-capture-root-ident"
      ),
      GrammarRoleId.MatchTypePatternVariable  -> Set(
        "match-type-pattern-variable",
        "match-type-pattern-wildcard"
      ),
      GrammarRoleId.Modifiers                 -> Set(
        "modifiers-annotations-synthetic",
        "modifiers-annotations-source",
        "modifiers-keywords",
        "modifiers-annotations-keywords",
        "modifiers-absent",
        "match-pattern-binder-modifiers",
        "match-pattern-given-modifiers"
      ),
      GrammarRoleId.PatternGiven              -> Set(
        "match-pattern-given",
        "match-pattern-given-named",
        "match-pattern-given-typed",
        "match-pattern-given-modifier",
        "match-pattern-given-wildcard"
      ),
      GrammarRoleId.AccessModifier            -> Set("modifier-access-private", "modifier-access-protected"),
      GrammarRoleId.KeywordModifier           -> Set(
        "modifier-keyword-abstract",
        "modifier-keyword-final",
        "modifier-keyword-sealed",
        "modifier-keyword-implicit",
        "modifier-keyword-lazy",
        "modifier-keyword-override",
        "modifier-keyword-var",
        "modifier-keyword-transparent",
        "modifier-keyword-inline",
        "modifier-keyword-infix",
        "modifier-keyword-open",
        "modifier-keyword-opaque",
        "modifier-keyword-given"
      ),
      GrammarRoleId.Annotations               -> Set(
        "modifiers-annotations-synthetic",
        "modifiers-annotations-source",
        "modifiers-annotations-keywords"
      ),
      GrammarRoleId.Annotation                -> Set(
        "annotation-apply-simple",
        "annotation-apply-arguments",
        "annotation-constructor-select",
        "annotation-constructor-new"
      ),
      GrammarRoleId.AnnotationArguments       -> Set("annotation-apply-arguments"),
      GrammarRoleId.ClassDefinition           -> Set("template-class-definition"),
      GrammarRoleId.TraitDefinition           -> Set("template-trait-definition"),
      GrammarRoleId.ObjectDefinition          -> Set("template-object-definition"),
      GrammarRoleId.EnumDefinition            -> Set("template-enum-definition"),
      GrammarRoleId.EnumCase                  -> Set("enum-singleton-case", "enum-class-case"),
      GrammarRoleId.Template                  -> Set("template-template"),
      GrammarRoleId.TemplateConstructor       -> Set(
        "template-constructor-synthetic",
        "template-constructor-explicit-empty",
        "template-constructor-typed-parameters",
        "template-constructor-unbounded-type-parameters"
      ),
      GrammarRoleId.TypeParameterClause       -> Set(
        "template-unbounded-type-bounds",
        "higher-kinded-parameter-lambda",
        "type-definition-lambda-encoding",
        "type-definition-ident-lambda-encoding",
        "type-definition-select-lambda-encoding",
        "type-definition-singleton-lambda-encoding",
        "type-definition-literal-lambda-encoding",
        "type-definition-parenthesized-lambda-encoding",
        "type-definition-applied-lambda-encoding",
        "type-definition-match-lambda-encoding",
        "type-definition-nested-lambda-encoding"
      ),
      GrammarRoleId.UnboundedTypeParameter    -> Set(
        "template-unbounded-type-parameter-invariant",
        "template-unbounded-type-parameter-covariant",
        "template-unbounded-type-parameter-contravariant",
        "function-unbounded-type-parameter"
      ),
      GrammarRoleId.BoundedTypeParameter      -> Set(
        "template-unbounded-type-parameter-invariant",
        "template-unbounded-type-parameter-covariant",
        "template-unbounded-type-parameter-contravariant",
        "template-context-bounded-type-parameter-invariant",
        "template-context-bounded-type-parameter-covariant",
        "template-context-bounded-type-parameter-contravariant",
        "function-unbounded-type-parameter",
        "function-context-bounded-type-parameter"
      ),
      GrammarRoleId.HigherKindedTypeParameter -> Set(
        "template-higher-kinded-type-parameter-invariant",
        "template-higher-kinded-type-parameter-covariant",
        "template-higher-kinded-type-parameter-contravariant",
        "higher-kinded-nested-type-parameter"
      ),
      GrammarRoleId.TermParameter             -> Set(
        "definition-typed-parameter",
        "type-definition-term-parameter",
        "dependent-function-parameter"
      ),
      GrammarRoleId.ClassParameter            -> Set(
        "template-class-parameter",
        "template-enum-class-parameter",
        "template-context-class-parameter"
      ),
      GrammarRoleId.TemplateSelf              -> Set("template-self-absent", "template-self-simple"),
      GrammarRoleId.TemplateTypeTree          -> Set("template-type-tree-synthetic"),
      GrammarRoleId.FunctionDefinition        -> Set(
        "definition-function-untyped",
        "refinement-function-declaration"
      ),
      GrammarRoleId.PropertyDefinition        -> Set(
        "definition-val-untyped",
        "definition-var-untyped",
        "refinement-value-declaration",
        "refinement-variable-declaration"
      ),
      GrammarRoleId.ReferenceBinding          -> Set(
        "definition-val-untyped",
        "definition-var-untyped",
        "refinement-value-declaration",
        "refinement-variable-declaration",
        "match-pattern-reference"
      ),
      GrammarRoleId.MatchExpression           -> Set("match-expression-candidate"),
      GrammarRoleId.CaseClause                -> Set(
        "match-case-clause",
        "match-guard",
        "match-case-body-block",
        "match-case-body-block-recovered"
      ),
      GrammarRoleId.PatternWildcard           -> Set("match-pattern-wildcard"),
      GrammarRoleId.PatternLiteral            -> Set(
        "match-pattern-literal",
        "match-pattern-literal-decimal",
        "match-pattern-literal-floating",
        "match-pattern-literal-string",
        "match-pattern-literal-char",
        "match-pattern-literal-boolean",
        "match-pattern-literal-double",
        "match-pattern-literal-float",
        "match-pattern-literal-long",
        "match-pattern-literal-null"
      ),
      GrammarRoleId.PatternStableIdentifier   -> Set("match-pattern-stable-reference"),
      GrammarRoleId.PatternTyped              -> Set("match-pattern-typed"),
      GrammarRoleId.PatternNaming             -> Set("match-pattern-naming"),
      GrammarRoleId.PatternSequenceWildcard   -> Set(
        "match-pattern-naming-sequence",
        "match-pattern-sequence-wildcard",
        "match-pattern-sequence-wildcard-marker"
      ),
      GrammarRoleId.PatternTuple              -> Set("match-pattern-tuple", "match-pattern-unit-tuple"),
      GrammarRoleId.PatternParenthesized      -> Set("match-pattern-parenthesized"),
      GrammarRoleId.PatternAlternative        -> Set("match-pattern-alternative"),
      GrammarRoleId.PatternConstructor        -> Set("match-pattern-constructor"),
      GrammarRoleId.TypeAliasDeclaration      -> Set("definition-unbounded-type-alias"),
      GrammarRoleId.TypeAliasDefinition       -> Set(
        "definition-simple-ident-type-alias",
        "definition-simple-select-type-alias",
        "definition-simple-singleton-type-alias",
        "definition-simple-literal-type-alias",
        "definition-simple-parenthesized-type-alias",
        "definition-applied-type-alias",
        "definition-tuple-type-alias",
        "definition-function-type-alias",
        "definition-polymorphic-function-type-alias",
        "definition-infix-type-alias",
        "definition-match-type-alias",
        "definition-refinement-type-alias",
        "definition-annotated-type-alias",
        "definition-opaque-simple-ident-type-alias",
        "definition-opaque-bounded-type-alias",
        "definition-type-lambda-alias"
      ),
      GrammarRoleId.InferredTypeAbsence       -> Set("definition-inferred-type-absence"),
      GrammarRoleId.OutputFreeExpression      -> Set(
        "payload-descendant-val",
        "payload-descendant-var",
        "payload-output-free-ident",
        "payload-output-free-select",
        "payload-descendant-named-arg",
        "payload-qualifier-ident",
        "payload-qualifier-this",
        "payload-qualifier-super",
        "type-application-output-free-ident",
        "type-application-output-free-ident-argument",
        "type-application-output-free-number",
        "type-application-output-free-literal",
        "named-invoked-output-free-integer",
        "named-invoked-output-free-string",
        "named-invoked-output-free-ident",
        "named-term-output-free-integer",
        "named-term-output-free-string",
        "repeated-term-output-free-typed",
        "repeated-term-output-free-typed-synthetic",
        "repeated-term-star-evidence",
        "repeated-term-output-free-string"
      ),
      GrammarRoleId.ExpressionTypeApply       -> Set(
        "definition-payload-type-apply-positional",
        "definition-payload-type-apply-named",
        "definition-payload-applied-call",
        "payload-descendant-type-apply-positional",
        "payload-descendant-type-apply-named",
        "positional-applied-call-candidate",
        "named-invoked-call-candidate",
        "positional-applied-type-apply-candidate",
        "named-type-application-candidate",
        "named-invoked-type-application"
      ),
      GrammarRoleId.ExpressionPayload         -> Set(
        "annotation-argument-literal-payload",
        "definition-payload-number",
        "definition-payload-ident",
        "definition-payload-apply",
        "definition-payload-select",
        "definition-payload-tuple",
        "definition-payload-block",
        "definition-payload-infix",
        "definition-payload-match",
        "payload-descendant-ident",
        "payload-descendant-number",
        "payload-descendant-invoked-literal",
        "payload-descendant-apply",
        "payload-descendant-select",
        "payload-descendant-tuple",
        "payload-descendant-block",
        "payload-descendant-infix"
      )
    )
    val actual   = catalog.productions
      .flatMap(production => production.grammarRoleIds.map(_ -> production.id))
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap
    assertEquals(expected, actual)
    assertEquals(expected.keySet, catalog.stableRoles.grammarRoles)
    assertTrue(
      catalog.productions.forall(production =>
        production.grammarRoleId.value != production.id &&
          production.grammarRoleId.value != production.pattern.prefix
      )
    )

    val composites            = catalog.productions.flatMap(_.effectiveOutputRealizations.flatMap(_.template.composites))
    val sourceContextBound    = catalog.productions.find(_.id == "type-parameter-context-bound").get
    val syntheticContextBound = catalog.productions.find(_.id == "type-parameter-synthetic-context-bound").get
    assertEquals(
      Set(SourceClassification.SourceReachable),
      sourceContextBound.pattern.occurrences.map(_.sourceClassification).toSet
    )
    assertEquals(
      Set(SourceClassification.Synthetic),
      syntheticContextBound.pattern.occurrences.map(_.sourceClassification).toSet
    )
    assertEquals(
      Vector(PsiOutputRoleId.ContextBound),
      sourceContextBound.effectiveOutputTemplate.composites.map(_.outputRoleId)
    )
    assertTrue(syntheticContextBound.effectiveOutputTemplate.composites.isEmpty)
    val terminals             = catalog.productions.flatMap(_.terminals)
    val usedRoles             = (composites.map(_.outputRoleId) ++ terminals.map(_.outputRoleId)).toSet
    assertEquals(catalog.stableRoles.outputRoles, usedRoles)
    assertTrue(composites.forall(output => output.outputRoleId.value != output.targetSurfaceId))
    assertTrue(terminals.forall(terminal => catalog.stableRoles.outputRoles(terminal.outputRoleId)))
    assertTrue(
      catalog.productions
        .filter(production => production.outputTemplate.isEmpty && production.outputRealizations.isEmpty)
        .forall(_.outputRoleId.nonEmpty)
    )
    val installedErrors       = Scala3PsiProductionCatalogValidator.validateExecutable(
      catalog,
      inventory(annotationModifierSnapshot),
      ScalaPsiSurfaceInventory.installed().fold(message => throw new AssertionError(message), identity)
    )
    val packetMethods         = Set(
      "org/jetbrains/plugins/scala/lang/psi/impl/base/ScModifierListImpl#modifiersOrdered()Lscala/collection/immutable/Seq;",
      "org/jetbrains/plugins/scala/lang/psi/impl/base/ScAccessModifierImpl#idText()Lscala/Option;",
      "org/jetbrains/plugins/scala/lang/psi/impl/expr/ScAnnotationsImpl#getAnnotations()[Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScAnnotation;",
      "org/jetbrains/plugins/scala/lang/psi/api/expr/ScExpression#type()Lscala/util/Either;",
      "org/jetbrains/plugins/scala/lang/psi/api/expr/ScExpression#innerType()Lscala/util/Either;"
    )
    val methodFailures        = installedErrors.collect:
      case value @ CatalogValidationError.InvalidSurface(_, _, id, _) if packetMethods(id)          => value
      case value @ CatalogValidationError.InvalidSurfaceOwner(_, _, id, _) if packetMethods(id)     => value
      case value @ CatalogValidationError.IncompleteSurfaceStatus(_, _, id, _) if packetMethods(id) => value
    assertTrue(methodFailures.mkString("\n"), methodFailures.isEmpty)

    val packageBody       = catalog.productions.find(_.id == "file-package-top-statements").get
    val packageStatements = packageBody.children.find(_.fieldName == "stats").get
    assertEquals(ChildCardinality.Grouped(1, None), packageStatements.cardinality)
    assertEquals(
      Set(
        "import-statement",
        "export-statement",
        "file-package",
        "file-package-top-statements",
        "template-class-definition",
        "template-trait-definition",
        "template-object-definition",
        "template-enum-definition",
        "definition-function-untyped",
        "definition-val-untyped",
        "definition-var-untyped",
        "definition-simple-ident-type-alias",
        "definition-simple-select-type-alias",
        "definition-simple-singleton-type-alias",
        "definition-simple-literal-type-alias",
        "definition-simple-parenthesized-type-alias",
        "definition-applied-type-alias",
        "definition-tuple-type-alias",
        "definition-function-type-alias",
        "definition-polymorphic-function-type-alias",
        "definition-infix-type-alias",
        "definition-match-type-alias",
        "definition-refinement-type-alias",
        "definition-annotated-type-alias",
        "definition-opaque-simple-ident-type-alias",
        "definition-type-lambda-alias",
        "definition-opaque-bounded-type-alias"
      ),
      packageStatements.productionIds
    )

    assertEquals(Vector("package-text", "root-remainder", "end-keyword"), packageBody.terminals.map(_.id))
    val syntheticRoot  = catalog.productions.find(_.id == "file-top-statements").get
    val rootStatements = syntheticRoot.children.find(_.fieldName == "stats").get
    assertEquals(ChildCardinality.Grouped(1, None), rootStatements.cardinality)
    assertEquals(packageStatements.productionIds, rootStatements.productionIds)
    assertTrue(syntheticRoot.outputTemplate.exists(_.composites.isEmpty))

    val exportFields = Vector(
      InventoryFieldObservation("expr", InventoryValueObservation.Node(1L, "Select")),
      InventoryFieldObservation(
        "selectors",
        InventoryValueObservation.Repeated(Vector(InventoryValueObservation.Node(2L, "ImportSelector")))
      )
    )
    val topContext   = Some(
      InventoryContext(
        InventoryKind.Node,
        "PackageDef",
        Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement)
      )
    )
    assertEquals(
      Vector("export-statement"),
      CatalogShapeMatcher
        .select(catalog, InventoryKind.Node, "Export", exportFields, topContext, SourceClassification.SourceReachable)
        .map(_.id)
    )
    Vector(
      None,
      Some(
        InventoryContext(
          InventoryKind.Node,
          "Template",
          Vector(CatalogPathSegment.NamedField("preBody"), CatalogPathSegment.RepeatedElement)
        )
      ),
      Some(
        InventoryContext(
          InventoryKind.Node,
          "Block",
          Vector(CatalogPathSegment.NamedField("stats"), CatalogPathSegment.RepeatedElement)
        )
      )
    ).foreach: context =>
      assertTrue(
        CatalogShapeMatcher
          .select(catalog, InventoryKind.Node, "Export", exportFields, context, SourceClassification.SourceReachable)
          .isEmpty
      )

    val exportProduction = catalog.productions.find(_.id == "export-statement").get
    assertEquals(
      Set(
        PsiOutputRoleId.ExportStatement,
        PsiOutputRoleId.ImportExpression,
        PsiOutputRoleId.ImportSelectorSet,
        PsiOutputRoleId.StableReference
      ),
      exportProduction.effectiveOutputRealizations.flatMap(_.template.composites).map(_.outputRoleId).toSet
    )
    assertEquals(
      Set("import-path-reference", "import-path-identifier-reference", "import-expression-absent"),
      exportProduction.children.find(_.roleId == "path").get.productionIds
    )
    assertEquals(
      Set("import-selector-direct", "import-selector-braced"),
      exportProduction.children.find(_.roleId == "selectors").get.productionIds
    )

  @Test def astOnlyCatalogPlansChangeCatalogIdentityWithoutChangingPersistedIdentity(): Unit =
    val catalog               = Scala3PsiProductionCatalog.Reviewed
    val astOnly               = catalog.productions
      .find(production =>
        production.children.isEmpty && production.effectiveOutputRealizations.forall(
          _.template.composites.forall(_.persistence == PersistenceObligations.NotApplicable)
        )
      )
      .get
      .copy(id = "ast-only-fingerprint-contract")
    val changed               = catalog.copy(productions = astOnly +: catalog.productions)
    val currentPersistence    = persisted(catalog)
    val changedPersistence    = persisted(changed)
    val withAstOnlyOutput     = mutatePersistedRealization(catalog, _.template.composites.nonEmpty)(realization =>
      realization.copy(template =
        realization.template.copy(composites =
          realization.template.composites.head.copy(
            id = "ast-only-output",
            persistence = PersistenceObligations.NotApplicable
          ) +: realization.template.composites
        )
      )
    )
    val withoutAstAlternative = catalog.copy(productions =
      catalog.productions.map(production =>
        production.copy(children = production.children.map: child =>
          val retained = (child.productionIds - "modifier-keyword-abstract").toVector.sorted
          if retained.size == child.productionIds.size then child
          else child.copy(productionId = retained.head, additionalProductionIds = retained.tail.toSet)
        )
      )
    )

    assertNotEquals(
      Scala3PsiProductionCatalog.catalogPlanStructure(catalog).fingerprint,
      Scala3PsiProductionCatalog.catalogPlanStructure(changed).fingerprint
    )
    assertEquals(currentPersistence.rows, changedPersistence.rows)
    assertEquals(currentPersistence.fingerprint, changedPersistence.fingerprint)
    assertNotEquals(
      Scala3PsiProductionCatalog.catalogPlanStructure(catalog).fingerprint,
      Scala3PsiProductionCatalog.catalogPlanStructure(withAstOnlyOutput).fingerprint
    )
    assertEquals(currentPersistence.rows, persisted(withAstOnlyOutput).rows)
    assertEquals(currentPersistence.fingerprint, persisted(withAstOnlyOutput).fingerprint)
    assertNotEquals(
      Scala3PsiProductionCatalog.catalogPlanStructure(catalog).fingerprint,
      Scala3PsiProductionCatalog.catalogPlanStructure(withoutAstAlternative).fingerprint
    )
    assertEquals(currentPersistence.rows, persisted(withoutAstAlternative).rows)
    assertEquals(currentPersistence.fingerprint, persisted(withoutAstAlternative).fingerprint)
    assertEquals(
      Math.addExact(org.jetbrains.plugins.scala.lang.parser.Scala3ParserDefinition.FileNodeType.getStubVersion, 15),
      Scala3DotcParserDefinition.FileNodeType.getStubVersion
    )

  @Test def invokedNamedCallDeclarationsKeepBaselineAndEnabledRoutesExact(): Unit =
    val catalog                              = Scala3PsiProductionCatalog.Reviewed
    def production(id: String)               = catalog.productions.find(_.id == id).get
    def contexts(id: String)                 = production(id).pattern.occurrences.map(_.context)
    def routes(id: String, enabled: Boolean) = contexts(id).collect:
      case ContextPattern.DescendantOfEnabledCandidateRoot(values) if enabled => values
      case ContextPattern.DescendantOfOwnedRoot(values) if !enabled           => values

    val positional = production("positional-applied-call-candidate")
    val outer      = production("named-invoked-call-candidate")
    val choices    = Vector(positional, outer).map(_.realizationChoice.get)
    assertEquals(
      Vector(Vector("positional-applied-call-native"), Vector("named-invoked-call-native")),
      choices.map(_.candidateIds)
    )
    assertEquals(Vector("positional-applied-call-payload", "named-invoked-call-payload"), choices.map(_.fallbackId))
    assertTrue(choices.forall(_.policy == RealizationChoicePolicy.AtomicWholePlan))
    assertTrue(
      positional.pattern.occurrences.forall(_.scannerEvidence.forbidden == Set(ParserScannerTokenKind.Equals))
    )
    assertTrue(outer.pattern.occurrences.forall(_.scannerEvidence.required == Set(ParserScannerTokenKind.Equals)))
    assertEquals(
      Set("payload-descendant-type-apply-positional", "positional-applied-type-apply-candidate"),
      positional.children.find(_.roleId == "fun").get.productionIds
    )
    assertEquals(
      Set("payload-descendant-type-apply-named", "named-invoked-type-application"),
      outer.children.find(_.roleId == "fun").get.productionIds
    )
    assertTrue(production("named-invoked-type-application").realizationChoice.isEmpty)

    val argumentPath  = Vector(
      InventoryAncestor(
        InventoryKind.Node,
        "Apply",
        Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
      )
    )
    val qualifierPath = Vector(
      InventoryAncestor(InventoryKind.Node, "Select", Vector(CatalogPathSegment.NamedField("qualifier"))),
      InventoryAncestor(InventoryKind.Node, "TypeApply", Vector(CatalogPathSegment.NamedField("fun"))),
      InventoryAncestor(InventoryKind.Node, "Apply", Vector(CatalogPathSegment.NamedField("fun")))
    )
    def assertExact(
        values: Vector[Vector[OwnedRootRoute]],
        path: Vector[InventoryAncestor],
        dedicated: Boolean = false
    ): Unit =
      val all       = values.flatten
      val flattened = all.filter(route => route.rootProductionId == outer.id && route.descendantPath == path)
      assertEquals(4, flattened.size)
      if dedicated then assertEquals(flattened, all)
      assertEquals(
        Set("DefDef" -> "PackageDef", "DefDef" -> "Template", "ValDef" -> "PackageDef", "ValDef" -> "Template"),
        flattened.map(route => route.rootOwner.ownerPrefix -> route.outerOwner.ownerPrefix).toSet
      )

    assertExact(routes("payload-output-free-ident", enabled = false), qualifierPath)
    assertExact(routes("selection-qualifier-ident", enabled = true), qualifierPath)
    Vector(
      "named-invoked-output-free-ident",
      "named-invoked-output-free-integer",
      "named-invoked-output-free-string"
    ).foreach(id => assertExact(routes(id, enabled = false), argumentPath, dedicated = true))
    assertExact(routes("atomic-term-ident", enabled = true), argumentPath)
    Vector("named-invoked-literal-integer", "named-invoked-literal-string")
      .foreach(id => assertExact(routes(id, enabled = true), argumentPath, dedicated = true))

    Vector("payload-descendant-apply", "payload-descendant-invoked-literal")
      .foreach: id =>
        val fallback = production(id)
        assertTrue(
          fallback.effectiveOutputTemplate.composites.exists(_.outputRoleId == PsiOutputRoleId.ExpressionPayload)
        )
        assertTrue(
          contexts(id).exists:
            case ContextPattern.ParentWithNodeFieldUnderAnchor(
                  InventoryKind.Node,
                  "Apply",
                  Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
                  "fun",
                  "TypeApply",
                  _
                ) =>
              true
            case _ => false
        )

  @Test def conditionOutcomeMatchersChangePersistedIdentity(): Unit =
    val catalog = Scala3PsiProductionCatalog.Reviewed
    val current = persisted(catalog)
    val changed = persisted(
      mutateProduction(catalog, "import-expression-absent")(production =>
        production.copy(pattern = production.pattern.copy(prefix = s"${production.pattern.prefix}-changed"))
      )
    )

    assertTrue(current.rows.exists(_.contains("\timport-expression-absent\t")))
    assertTrue(current.rows.exists(_.contains("\timport-selector-absent\t")))
    assertNotEquals(current.rows, changed.rows)
    assertNotEquals(current.fingerprint, changed.fingerprint)

  @Test def outputRoleConditionsInsidePersistedRoutingChangePersistedIdentity(): Unit =
    val catalog     = Scala3PsiProductionCatalog.Reviewed
    val selection   = catalog.productions.find(_.id == "selection-expression").get
    val recursive   = selection.outputRealizations.find(_.id == "native-recursive").get
    val changedRole = recursive.copy(conditions =
      recursive.conditions.map(
        _.copy(expected = ChildOutcomeExpectation.OutputRole(PsiOutputRoleId.ThisReference))
      )
    )
    val changed     = mutateProduction(catalog, selection.id)(
      _.copy(outputRealizations =
        selection.outputRealizations.map(value => if value.id == recursive.id then changedRole else value)
      )
    )
    val unknownRole = PsiOutputRoleId("test.output.unknown-condition")
    val invalid     = mutateProduction(catalog, selection.id)(
      _.copy(outputRealizations =
        selection.outputRealizations.map(value =>
          if value.id == recursive.id then
            value.copy(conditions =
              value.conditions.map(
                _.copy(expected = ChildOutcomeExpectation.OutputRole(unknownRole))
              )
            )
          else value
        )
      )
    )
    val compiler    = aggregate(Vector(inventory(snapshot("/output-role-condition", 1, Vector.empty))))
    val errors      = Scala3PsiProductionCatalogValidator.validate(invalid, compiler, surfaces(invalid))

    assertTrue(
      errors.contains(CatalogValidationError.UnknownConditionOutputRole(selection.id, recursive.id, unknownRole))
    )
    assertNotEquals(
      Scala3PsiProductionCatalog.catalogPlanStructure(catalog).fingerprint,
      Scala3PsiProductionCatalog.catalogPlanStructure(changed).fingerprint
    )
    assertNotEquals(persisted(catalog).rows, persisted(changed).rows)
    assertNotEquals(persisted(catalog).fingerprint, persisted(changed).fingerprint)

  @Test def outputRoleConditionsOutsidePersistedRoutingRemainAstOnly(): Unit =
    val base             = completeCatalog(inventory(snapshot("/output-role-condition", 1, Vector.empty)))
    val root             = base.productions.find(_.id == "Root").get
    val condition        = ChildOutcomeCondition(
      "child",
      ChildOccurrenceSelector.First,
      ChildOutcomeExpectation.OutputRole(PsiOutputRoleId("test.output.Child"))
    )
    val withCondition    = base.copy(productions =
      base.productions.map(p =>
        if p.id == root.id then
          p.copy(outputRealizations =
            Vector(
              OutputRealization(
                "conditioned",
                Vector(condition),
                LocalOutputCompositeTemplate(
                  Vector(
                    OutputCompositeDeclaration(
                      "self",
                      None,
                      OutputRangeDeclaration.CompilerPosition,
                      PsiOutputRoleId("test.output.Root"),
                      "element.Root",
                      TargetRequirement.Compatible,
                      Vector.empty,
                      PersistenceObligations.NotApplicable,
                      None
                    )
                  ),
                  Map.empty
                )
              )
            )
          )
        else p
      )
    )
    val unknownRole      = PsiOutputRoleId("test.output.unknown-condition")
    val invalidCondition = condition.copy(expected = ChildOutcomeExpectation.OutputRole(unknownRole))
    val invalid          = withCondition.copy(productions =
      withCondition.productions.map(p =>
        if p.id == root.id then
          p.copy(outputRealizations = p.outputRealizations.map(r => r.copy(conditions = Vector(invalidCondition))))
        else p
      )
    )
    val invalidCompiler  = aggregate(Vector(inventory(snapshot("/output-role-condition", 1, Vector.empty))))
    val errors           = Scala3PsiProductionCatalogValidator.validate(invalid, invalidCompiler, surfaces(invalid))

    assertTrue(
      errors.contains(CatalogValidationError.UnknownConditionOutputRole(root.id, "conditioned", unknownRole))
    )
    val mutatedExpected = ChildOutcomeExpectation.OutputRole(PsiOutputRoleId("test.output.Child"))
    val mutated         = withCondition.copy(productions =
      withCondition.productions.map(p =>
        if p.id == root.id then
          p.copy(outputRealizations =
            p.outputRealizations.map(r => r.copy(conditions = Vector(condition.copy(expected = mutatedExpected))))
          )
        else p
      )
    )
    assertEquals(persisted(withCondition).rows, persisted(mutated).rows)
    assertEquals(persisted(withCondition).fingerprint, persisted(mutated).fingerprint)

  @Test def validatorRejectsInvalidOwnedPayloadRootDeclarations(): Unit =
    val reviewed                                    = Scala3PsiProductionCatalog.Reviewed
    val descendant                                  = reviewed.productions.find(_.id == "payload-output-free-ident").get
    val occurrence                                  = descendant.pattern.occurrences.collectFirst:
      case value @ CompilerProductionContextPattern(ContextPattern.DescendantOfOwnedRoot(_), _, _) => value
    val route                                       = occurrence.get.context match
      case ContextPattern.DescendantOfOwnedRoot(routes) => routes.head
      case _                                            => throw new AssertionError("expected owned-root route")
    val compiler                                    = aggregate(Vector(inventory(snapshot("/owned-root-validation", 1, Vector.empty))))
    def errors(catalog: Scala3PsiProductionCatalog) =
      Scala3PsiProductionCatalogValidator.validate(catalog, compiler, surfaces(catalog))
    def withRoute(value: OwnedRootRoute)            = mutateProduction(reviewed, descendant.id): production =>
      production.copy(pattern =
        production.pattern.copy(occurrences =
          Vector(
            occurrence.get.copy(context = ContextPattern.DescendantOfOwnedRoot(Vector(value)))
          )
        )
      )

    val missingRoute = route.copy(rootProductionId = "missing-root")
    assertTrue(
      errors(withRoute(missingRoute)).contains(
        CatalogValidationError.InvalidOwnedRootRoute(descendant.id, missingRoute, "missing root production")
      )
    )

    val nonPayloadRoute = route.copy(rootProductionId = "selection-expression")
    assertTrue(
      errors(withRoute(nonPayloadRoute)).contains(
        CatalogValidationError.InvalidOwnedRootRoute(descendant.id, nonPayloadRoute, "root is not one complete payload")
      )
    )

    val duplicated = withRoute(route).copy(
      productions = reviewed.productions :+ reviewed.productions.find(_.id == route.rootProductionId).get
    )
    assertTrue(
      errors(duplicated).contains(
        CatalogValidationError.InvalidOwnedRootRoute(descendant.id, route, "ambiguous root production")
      )
    )

    val rootOutput = reviewed.productions.find(_.id == route.rootProductionId).get.outputTemplate
    val emitting   = mutateProduction(withRoute(route), descendant.id)(_.copy(outputTemplate = rootOutput))
    assertTrue(
      errors(emitting).contains(
        CatalogValidationError.InvalidOwnedRootRoute(descendant.id, route, "descendant production emits output")
      )
    )

    val selectQualifier = InventoryAncestor(
      InventoryKind.Node,
      "Select",
      Vector(CatalogPathSegment.NamedField("qualifier"))
    )
    val invalidIndex    = route.copy(repeatedEdge = Some(RepeatedOwnedRootEdge(0, selectQualifier)))
    assertTrue(
      errors(withRoute(invalidIndex)).contains(
        CatalogValidationError.InvalidOwnedRootRoute(descendant.id, invalidIndex, "invalid repeated edge")
      )
    )

    val unknownEdge = route.copy(repeatedEdge =
      Some(
        RepeatedOwnedRootEdge(
          1,
          InventoryAncestor(
            InventoryKind.Node,
            "Select",
            Vector(CatalogPathSegment.NamedField("unknown"))
          )
        )
      )
    )
    assertTrue(
      errors(withRoute(unknownEdge)).contains(
        CatalogValidationError.InvalidOwnedRootRoute(descendant.id, unknownEdge, "invalid repeated edge")
      )
    )

    val noClaim = mutateProduction(withRoute(route), descendant.id)(_.copy(terminals = Vector.empty))
    assertTrue(
      errors(noClaim).contains(
        CatalogValidationError.InvalidOwnedRootRoute(
          descendant.id,
          route,
          "descendant must have one whole-production parent claim"
        )
      )
    )

  @Test def everyPersistedCompatibilityObligationChangesPersistedIdentity(): Unit =
    val catalog      = Scala3PsiProductionCatalog.Reviewed
    val current      = persisted(catalog)
    val externalIds  = TemplatePersistenceSurfaces.ExternalIds ++ DefinitionPersistenceSurfaces.ExternalIds
    val externalRole = catalog.productions
      .flatMap(_.effectiveOutputRealizations)
      .flatMap(_.template.composites)
      .find(output =>
        externalIds.contains(output.outputRoleId) && output.persistence != PersistenceObligations.NotApplicable
      )
      .map(_.outputRoleId)
      .get
    val changedIds   = Scala3PsiProductionCatalog.persistedSchemaStructure(
      catalog,
      Scala3DotcFileElementType.SchemaVersion,
      Scala3DotcFileElementType.ExternalId,
      externalIds.updated(externalRole, s"${externalIds(externalRole)}.changed")
    )
    val mutations    = Vector(
      "schema number"               -> Scala3PsiProductionCatalog.persistedSchemaStructure(
        catalog,
        Scala3DotcFileElementType.SchemaVersion + 1,
        Scala3DotcFileElementType.ExternalId
      ),
      "root external ID"            -> Scala3PsiProductionCatalog.persistedSchemaStructure(
        catalog,
        Scala3DotcFileElementType.SchemaVersion,
        s"${Scala3DotcFileElementType.ExternalId}.changed"
      ),
      "child external ID"           -> changedIds,
      "stub surface"                -> persisted(mutatePersistedOutput(catalog): output =>
        output.copy(persistence = required(output).copy(stubSurfaceId = s"${required(output).stubSurfaceId}.changed"))),
      "serializer surface"          -> persisted(mutatePersistedOutput(catalog): output =>
        output.copy(persistence =
          required(output).copy(serializerSurfaceId = s"${required(output).serializerSurfaceId}.changed")
        )),
      "declared index order"        -> persisted(
        mutatePersistedOutput(
          catalog,
          output => required(output).indexSurfaceIds.size > 1
        ): output =>
          output.copy(persistence = required(output).copy(indexSurfaceIds = required(output).indexSurfaceIds.reverse))
      ),
      "navigation identity"         -> persisted(mutatePersistedOutput(catalog): output =>
        output.copy(persistence =
          required(output).copy(navigationSurfaceId = s"${required(output).navigationSurfaceId}.changed")
        )),
      "persisted ancestry"          -> persisted(
        mutatePersistedOutput(catalog)(output => output.copy(parentId = Some("changed-parent")))
      ),
      "persisted topology"          -> persisted(
        mutatePersistedChild(catalog)(child => child.copy(fieldName = s"${child.fieldName}-changed"))
      ),
      "persisted routing closure"   -> persisted(
        mutateProduction(catalog, "ordinary-refinement-type")(production =>
          production.copy(children = production.children.map:
            case child if child.roleId == "members" => child.copy(cardinality = ChildCardinality.Optional)
            case child                              => child
          )
        )
      ),
      "persisted child mount"       -> persisted(
        mutatePersistedRealization(catalog, _.template.childMounts.nonEmpty)(realization =>
          realization.copy(template =
            realization.template.copy(childMounts =
              realization.template.childMounts.updated(
                realization.template.childMounts.keys.head,
                Some("changed-parent")
              )
            )
          )
        )
      ),
      "persisted child selection"   -> persisted(
        mutatePersistedRealization(catalog, _.template.childOutputSelections.nonEmpty)(realization =>
          realization.copy(template =
            realization.template.copy(childOutputSelections =
              realization.template.childOutputSelections.updated(
                realization.template.childOutputSelections.keys.head,
                PsiOutputRoleId("changed.output.role")
              )
            )
          )
        )
      ),
      "realization condition"       -> persisted(
        mutatePersistedRealization(catalog, _.conditions.nonEmpty)(realization =>
          realization.copy(conditions = realization.conditions :+ realization.conditions.head)
        )
      ),
      "realization evidence"        -> persisted(
        mutatePersistedRealization(catalog, _.evidenceConditions.nonEmpty)(realization =>
          realization.copy(evidenceConditions = realization.evidenceConditions :+ realization.evidenceConditions.head)
        )
      ),
      "persisted field disposition" -> persisted(
        mutatePersistedProduction(catalog)(production =>
          production.copy(dispositions = production.dispositions.reverse)
        )
      )
    )
    mutations.foreach: (meaning, changed) =>
      assertNotEquals(meaning, current.rows, changed.rows)
      assertNotEquals(meaning, current.fingerprint, changed.fingerprint)

  @Test def structuralDiffExplainsRowsBeforeHashes(): Unit =
    val report    = StructuralRows.diff(Vector("a", "b"), Vector("b", "c"))
    assertTrue(report, report.startsWith("practical meaning:"))
    assertTrue(report, report.indexOf("missing:") < report.indexOf("expected hash:"))
    assertTrue(report, report.indexOf("extra:") < report.indexOf("actual hash:"))
    assertTrue(report, report.contains("changed:"))
    assertTrue(report, report.contains("changed:\n  none"))
    assertTrue(report, report.contains("reordered:"))
    val insertion = StructuralRows.diff(Vector("a", "b", "c"), Vector("a", "inserted", "b", "c"))
    assertTrue(insertion, insertion.contains("1 extra"))
    assertTrue(insertion, insertion.contains("changed:\n  none"))
    assertFalse(insertion, insertion.contains("expected=b\tactual=inserted"))

  @Test def coverageReportRendersCapabilityProbedCompatibleTargets(): Unit =
    val runtime  = inventory(snapshot("/report", 1, Vector.empty))
    val base     = completeCatalog(runtime)
    val target   = "org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyExpressionPayload"
    val catalog  = base.copy(productions =
      base.productions.head.copy(targetSurfaceId = target, targetRequirement = TargetRequirement.Compatible) +:
        base.productions.tail
    )
    val compiler = aggregate(Vector(runtime))
    val surface  = surfaces(base)
    val report   = Scala3PsiProductionCoverageReport.markdown(catalog, compiler, surface)
    assertTrue(
      report,
      report.contains(s"`Element:$target` — **Available:catalog-referenced:${catalog.productions.head.id}**")
    )
    assertTrue(report.contains("grammar-role=test.grammar.Root"))
    assertTrue(report.contains("output-roles=test.output.Root"))
    assertTrue(report.contains("catalog-alternative=Root"))
    assertTrue(report.contains("compiler-shape=Node.Root"))
    assertTrue(report.contains(s"host-targets=$target"))
    assertTrue(report.contains("providers=Compatible"))
    assertEquals(
      report,
      Scala3PsiProductionCoverageReport.markdown(
        catalog,
        aggregate(Vector(runtime.copy(shapes = runtime.shapes.reverse, nodes = runtime.nodes.reverse))),
        surface.copy(rows = surface.rows.reverse)
      )
    )

  private def persisted(catalog: Scala3PsiProductionCatalog): PersistedSchemaStructure =
    Scala3PsiProductionCatalog.persistedSchemaStructure(
      catalog,
      Scala3DotcFileElementType.SchemaVersion,
      Scala3DotcFileElementType.ExternalId
    )

  private def persistedOutput(
      catalog: Scala3PsiProductionCatalog,
      predicate: OutputCompositeDeclaration => Boolean
  ): (OutputCompositeDeclaration, Int, Int, Int) =
    catalog.productions.zipWithIndex
      .flatMap: (production, productionIndex) =>
        production.effectiveOutputRealizations.zipWithIndex.flatMap: (realization, realizationIndex) =>
          realization.template.composites.zipWithIndex.collect:
            case (output, outputIndex)
                if output.persistence != PersistenceObligations.NotApplicable && predicate(output) =>
              (output, productionIndex, realizationIndex, outputIndex)
      .head

  private def mutatePersistedOutput(
      catalog: Scala3PsiProductionCatalog,
      predicate: OutputCompositeDeclaration => Boolean = _ => true
  )(mutation: OutputCompositeDeclaration => OutputCompositeDeclaration): Scala3PsiProductionCatalog =
    val (output, productionIndex, realizationIndex, outputIndex) = persistedOutput(catalog, predicate)
    val production                                               = catalog.productions(productionIndex)
    val realization                                              = production.effectiveOutputRealizations(realizationIndex)
    val template                                                 = realization.template.copy(
      composites = realization.template.composites.updated(outputIndex, mutation(output))
    )
    catalog.copy(productions =
      catalog.productions.updated(
        productionIndex,
        production.copy(outputRealizations =
          production.effectiveOutputRealizations.updated(
            realizationIndex,
            realization.copy(template = template)
          )
        )
      )
    )

  private def mutatePersistedProduction(
      catalog: Scala3PsiProductionCatalog,
      predicate: Scala3PsiProduction => Boolean = _ => true
  )(mutation: Scala3PsiProduction => Scala3PsiProduction): Scala3PsiProductionCatalog =
    val productionIndex = catalog.productions.indexWhere(production =>
      predicate(production) && production.effectiveOutputRealizations.exists(
        _.template.composites.exists(_.persistence != PersistenceObligations.NotApplicable)
      )
    )
    catalog.copy(productions =
      catalog.productions.updated(productionIndex, mutation(catalog.productions(productionIndex)))
    )

  private def mutateProduction(
      catalog: Scala3PsiProductionCatalog,
      productionId: String
  )(mutation: Scala3PsiProduction => Scala3PsiProduction): Scala3PsiProductionCatalog =
    val productionIndex = catalog.productions.indexWhere(_.id == productionId)
    catalog.copy(productions =
      catalog.productions.updated(productionIndex, mutation(catalog.productions(productionIndex)))
    )

  private def mutatePersistedChild(
      catalog: Scala3PsiProductionCatalog
  )(mutation: ChildDeclaration => ChildDeclaration): Scala3PsiProductionCatalog =
    val persistedProductionIds = catalog.productions.collect:
      case production
          if production.effectiveOutputRealizations.exists(
            _.template.composites.exists(_.persistence != PersistenceObligations.NotApplicable)
          ) =>
        production.id
    val productionIndex        = catalog.productions.indexWhere(production =>
      production.effectiveOutputRealizations.exists(
        _.template.composites.exists(_.persistence != PersistenceObligations.NotApplicable)
      ) && production.children.exists(_.productionIds.exists(persistedProductionIds.contains))
    )
    val production             = catalog.productions(productionIndex)
    val childIndex             = production.children.indexWhere(_.productionIds.exists(persistedProductionIds.contains))
    catalog.copy(productions =
      catalog.productions.updated(
        productionIndex,
        production.copy(children = production.children.updated(childIndex, mutation(production.children(childIndex))))
      )
    )

  private def mutatePersistedRealization(
      catalog: Scala3PsiProductionCatalog,
      predicate: OutputRealization => Boolean
  )(mutation: OutputRealization => OutputRealization): Scala3PsiProductionCatalog =
    val (productionIndex, realizationIndex) = catalog.productions.zipWithIndex
      .flatMap: (production, productionIndex) =>
        production.effectiveOutputRealizations.zipWithIndex.collect:
          case (realization, realizationIndex)
              if predicate(realization) && realization.template.composites.exists(
                _.persistence != PersistenceObligations.NotApplicable
              ) =>
            (productionIndex, realizationIndex)
      .head
    val production                          = catalog.productions(productionIndex)
    catalog.copy(productions =
      catalog.productions.updated(
        productionIndex,
        production.copy(outputRealizations =
          production.effectiveOutputRealizations.updated(
            realizationIndex,
            mutation(production.effectiveOutputRealizations(realizationIndex))
          )
        )
      )
    )

  private def required(output: OutputCompositeDeclaration): PersistenceObligations.Required =
    output.persistence match
      case value: PersistenceObligations.Required => value
      case PersistenceObligations.NotApplicable   => throw new AssertionError("expected persisted output")
