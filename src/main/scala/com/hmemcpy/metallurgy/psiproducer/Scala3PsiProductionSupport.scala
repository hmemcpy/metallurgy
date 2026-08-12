package com.hmemcpy.metallurgy.psiproducer

private[psiproducer] object Scala3PsiProductionSupport:
  def emptyModifiers(flags: Long): CatalogValuePattern = CatalogValuePattern.Product(
    "Modifiers",
    Vector(
      CompilerFieldPattern("flags", CatalogValuePattern.ExactScalar("LongInteger", s"LongInteger($flags)")),
      CompilerFieldPattern("privateWithin", CatalogValuePattern.ClassifiedName(NeutralNameClass.Empty)),
      CompilerFieldPattern("annotations", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Node)),
      CompilerFieldPattern("mods", CatalogValuePattern.EmptyRepeated(CatalogValuePattern.Positioned))
    )
  )

  val PackageSurface               =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/packaging/ScPackagingImpl"
  val EndSurface                   = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScEndImpl"
  val ImportStatementSurface       =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/imports/ScImportStmtImpl"
  val ExportStatementSurface       =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/imports/ScExportStmtImpl"
  private val ExportStatementApi   =
    "org/jetbrains/plugins/scala/lang/psi/api/toplevel/imports/ScExportStmt"
  val ImportExpressionSurface      =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/imports/ScImportExprImpl"
  val ImportSelectorsSurface       =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/imports/ScImportSelectorsImpl"
  val ImportSelectorSurface        =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/imports/ScImportSelectorImpl"
  val StableReferenceSurface       =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/ScStableCodeReferenceImpl"
  val SimpleTypeSurface            =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScSimpleTypeElementImpl"
  val TypeProjectionSurface        =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScTypeProjectionImpl"
  val LiteralTypeSurface           =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScLiteralTypeElementImpl"
  val ParenthesizedTypeSurface     =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScParenthesisedTypeElementImpl"
  val TupleTypeSurface             =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScTupleTypeElementImpl"
  val TupleTypesSurface            = "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScTypesImpl"
  val NamedTupleTypeSurface        =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScNamedTupleTypeElementImpl"
  val NamedTupleComponentSurface   =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScNamedTupleTypeComponentImpl"
  val FunctionTypeSurface          =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScFunctionalTypeElementImpl"
  val DependentFunctionTypeSurface =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScDependentFunctionTypeElementImpl"
  val PolyFunctionTypeSurface      =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScPolyFunctionTypeElementImpl"
  val IntegerLiteralSurface        =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/literals/ScIntegerLiteralImpl"
  val LongLiteralSurface           =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/literals/ScLongLiteralImpl"
  val FloatLiteralSurface          =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/literals/ScFloatLiteralImpl"
  val DoubleLiteralSurface         =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/literals/ScDoubleLiteralImpl"
  val CharLiteralSurface           =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/literals/ScCharLiteralImpl"
  val StringLiteralSurface         = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScStringLiteralImpl"
  val BooleanLiteralSurface        =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/literals/ScBooleanLiteralImpl"
  val NullLiteralSurface           =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/literals/ScNullLiteralImpl"
  val ReferenceExpressionSurface   =
    "org/jetbrains/plugins/scala/lang/psi/impl/expr/ScReferenceExpressionImpl"
  val ThisReferenceSurface         =
    "org/jetbrains/plugins/scala/lang/psi/impl/expr/ScThisReferenceImpl"
  val SuperReferenceSurface        =
    "org/jetbrains/plugins/scala/lang/psi/impl/expr/ScSuperReferenceImpl"
  val ParameterizedTypeSurface     =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScParameterizedTypeElementImpl"
  val TypeArgumentsSurface         =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScTypeArgsImpl"
  val NamedTypeArgumentsSurface    =
    "org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyTypeArguments"
  val NamedTypeArgumentSurface     =
    "org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyNamedTypeArgument"
  val WildcardTypeSurface          =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScWildcardTypeElementImpl"
  val ContextBoundSurface          =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScContextBoundImpl"
  val TypeLambdaSurface            =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScTypeLambdaTypeElementImpl"
  val InfixTypeSurface             =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScInfixTypeElementImpl"
  val MatchTypeSurface             =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScMatchTypeElementImpl"
  val MatchTypeCasesSurface        =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScMatchTypeCasesImpl"
  val MatchTypeCaseSurface         =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScMatchTypeCaseImpl"
  val MatchTypeVariableSurface     =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScTypeVariableTypeElementImpl"
  val CompoundTypeSurface          =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScCompoundTypeElementImpl"
  val RefinementSurface            =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScRefinementImpl"
  val AnnotatedTypeSurface         =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScAnnotTypeElementImpl"
  val CaptureTypeSurface           =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScCaptureTypeElementImpl"
  val CaptureSetSurface            =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/cc/ScCaptureSetImpl"
  val CaptureReferenceSurface      =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/cc/ScCaptureRefImpl"
  val CaptureFilterSurface         =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/cc/ScCaptureFilterImpl"
  val ModifierListSurface          = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScModifierListImpl"
  val AccessModifierSurface        = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScAccessModifierImpl"
  val AnnotationsSurface           = "org/jetbrains/plugins/scala/lang/psi/impl/expr/ScAnnotationsImpl"
  val AnnotationSurface            = "org/jetbrains/plugins/scala/lang/psi/impl/expr/ScAnnotationImpl"
  val AnnotationExprSurface        = "org/jetbrains/plugins/scala/lang/psi/impl/expr/ScAnnotationExprImpl"
  val ConstructorSurface           = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScConstructorInvocationImpl"
  val AnnotationArgumentsSurface   =
    "org/jetbrains/plugins/scala/lang/psi/impl/expr/ScArgumentExprListImpl"
  val ExpressionPayloadSurface     =
    "org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyExpressionPayload"
  private val ExpressionSurface    = "org/jetbrains/plugins/scala/lang/psi/api/expr/ScExpression"
  val ClassDefinitionSurface       =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/typedef/ScClassImpl"
  val TraitDefinitionSurface       =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/typedef/ScTraitImpl"
  val ObjectDefinitionSurface      =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/typedef/ScObjectImpl"
  val EnumDefinitionSurface        =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/typedef/ScEnumImpl"
  val EnumCasesSurface             =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScEnumCasesImpl"
  val EnumSingletonCaseSurface     =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScEnumSingletonCaseImpl"
  val EnumClassCaseSurface         =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScEnumClassCaseImpl"
  val ExtendsBlockSurface          =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/templates/ScExtendsBlockImpl"
  val TemplateBodySurface          =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/templates/ScTemplateBodyImpl"
  val PrimaryConstructorSurface    =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/ScPrimaryConstructorImpl"
  val ParameterClausesSurface      =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/params/ScParametersImpl"
  val ParameterClauseSurface       =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/params/ScParameterClauseImpl"
  val ParameterSurface             =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/params/ScParameterImpl"
  val ClassParameterSurface        =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/params/ScClassParameterImpl"
  val ParameterTypeSurface         =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/params/ScParameterTypeImpl"
  val PureParameterTypeSurface     =
    "org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyParameterType"
  val TemplateParentsSurface       =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/templates/ScTemplateParentsImpl"
  val SelfTypeSurface              =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScSelfTypeElementImpl"
  val DerivesClauseSurface         =
    "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/templates/ScDerivesClauseImpl"
  val TypeParameterClauseSurface   =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/params/ScTypeParamClauseImpl"
  val TypeParameterSurface         =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/params/ScTypeParamImpl"
  val FunctionDefinitionSurface    =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScFunctionDefinitionImpl"
  val FunctionDeclarationSurface   =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScFunctionDeclarationImpl"
  val PatternDefinitionSurface     =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScPatternDefinitionImpl"
  val ValueDeclarationSurface      =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScValueDeclarationImpl"
  val VariableDefinitionSurface    =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScVariableDefinitionImpl"
  val VariableDeclarationSurface   =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScVariableDeclarationImpl"
  val PatternListSurface           = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScPatternListImpl"
  val ReferencePatternSurface      =
    "org/jetbrains/plugins/scala/lang/psi/impl/base/patterns/ScReferencePatternImpl"
  val IdentifierListSurface        = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScIdListImpl"
  val FieldIdSurface               = "org/jetbrains/plugins/scala/lang/psi/impl/base/ScFieldIdImpl"
  val TypeAliasDeclarationSurface  =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScTypeAliasDeclarationImpl"
  val TypeAliasDefinitionSurface   =
    "org/jetbrains/plugins/scala/lang/psi/impl/statements/ScTypeAliasDefinitionImpl"

  def parameterPersistence(role: PsiOutputRoleId): PersistenceObligations = role match
    case PsiOutputRoleId.TypeParameterClause =>
      PersistenceObligations.Required(
        TemplatePersistenceSurfaces.TypeParameterClauseStub,
        TemplatePersistenceSurfaces.TypeParameterClauseSerializer,
        Vector.empty,
        ImportPersistenceSurfaces.SelfNavigation
      )
    case PsiOutputRoleId.TypeParameter       =>
      PersistenceObligations.Required(
        TemplatePersistenceSurfaces.TypeParameterStub,
        TemplatePersistenceSurfaces.TypeParameterSerializer,
        Vector.empty,
        ImportPersistenceSurfaces.SelfNavigation
      )
    case PsiOutputRoleId.TypeAliasDefinition =>
      PersistenceObligations.Required(
        DefinitionPersistenceSurfaces.TypeAliasStub,
        DefinitionPersistenceSurfaces.TypeAliasSerializer,
        Vector(
          DefinitionPersistenceSurfaces.TypeAliasNameIndex,
          DefinitionPersistenceSurfaces.TopLevelTypeAliasIndex
        ),
        ImportPersistenceSurfaces.SelfNavigation
      )
    case _                                   => PersistenceObligations.NotApplicable

  def outputComposite(
      id: String,
      parentId: Option[String],
      range: OutputRangeDeclaration,
      role: PsiOutputRoleId,
      surface: String,
      accessors: Vector[AccessorObligation],
      requirement: TargetRequirement = TargetRequirement.Native
  ): OutputCompositeDeclaration =
    val persistence = role match
      case PsiOutputRoleId.PackageStatement                                           =>
        PersistenceObligations.Required(
          PackagePersistenceSurfaces.Stub,
          PackagePersistenceSurfaces.Serializer,
          Vector(PackagePersistenceSurfaces.FqnIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ImportStatement                                            =>
        PersistenceObligations.Required(
          ImportPersistenceSurfaces.StatementStub,
          ImportPersistenceSurfaces.StatementSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ExportStatement                                            =>
        PersistenceObligations.Required(
          ExportPersistenceSurfaces.StatementStub,
          ExportPersistenceSurfaces.StatementSerializer,
          Vector(ExportPersistenceSurfaces.TopLevelPackageIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ImportExpression                                           =>
        PersistenceObligations.Required(
          ImportPersistenceSurfaces.ExpressionStub,
          ImportPersistenceSurfaces.ExpressionSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ImportSelectorSet                                          =>
        PersistenceObligations.Required(
          ImportPersistenceSurfaces.SelectorSetStub,
          ImportPersistenceSurfaces.SelectorSetSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ImportSelector                                             =>
        PersistenceObligations.Required(
          ImportPersistenceSurfaces.SelectorStub,
          ImportPersistenceSurfaces.SelectorSerializer,
          Vector(ImportPersistenceSurfaces.AliasedImportIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ModifierList                                               =>
        PersistenceObligations.Required(
          ModifierAnnotationPersistenceSurfaces.ModifierStub,
          ModifierAnnotationPersistenceSurfaces.ModifierSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.AccessModifier                                             =>
        PersistenceObligations.Required(
          ModifierAnnotationPersistenceSurfaces.AccessStub,
          ModifierAnnotationPersistenceSurfaces.AccessSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.Annotations                                                =>
        PersistenceObligations.Required(
          ModifierAnnotationPersistenceSurfaces.AnnotationsStub,
          ModifierAnnotationPersistenceSurfaces.AnnotationsSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.Annotation                                                 =>
        PersistenceObligations.Required(
          ModifierAnnotationPersistenceSurfaces.AnnotationStub,
          ModifierAnnotationPersistenceSurfaces.AnnotationSerializer,
          Vector(NativePsiElementBindings.AnnotatedMemberIndexSurface),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ClassDefinition | PsiOutputRoleId.TraitDefinition | PsiOutputRoleId.ObjectDefinition |
          PsiOutputRoleId.EnumDefinition | PsiOutputRoleId.EnumSingletonCase | PsiOutputRoleId.EnumClassCase =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.DefinitionStub,
          TemplatePersistenceSurfaces.DefinitionSerializer,
          TemplatePersistenceSurfaces.DefinitionIndices,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.TypeParameterClause                                        =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.TypeParameterClauseStub,
          TemplatePersistenceSurfaces.TypeParameterClauseSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.TypeParameter                                              =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.TypeParameterStub,
          TemplatePersistenceSurfaces.TypeParameterSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.EnumCases                                                  =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.EnumCasesStub,
          TemplatePersistenceSurfaces.GenericSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ExtendsBlock                                               =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.ExtendsBlockStub,
          TemplatePersistenceSurfaces.ExtendsBlockSerializer,
          Vector(TemplatePersistenceSurfaces.SuperClassNameIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.TemplateBody                                               =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.TemplateBodyStub,
          TemplatePersistenceSurfaces.GenericSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.PrimaryConstructor                                         =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.PrimaryConstructorStub,
          TemplatePersistenceSurfaces.GenericSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ParameterClauses                                           =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.ParameterClausesStub,
          TemplatePersistenceSurfaces.GenericSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ParameterClause                                            =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.ParameterClauseStub,
          TemplatePersistenceSurfaces.GenericSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.Parameter | PsiOutputRoleId.ClassParameter                 =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.ParameterStub,
          TemplatePersistenceSurfaces.ParameterSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.TemplateParents                                            =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.TemplateParentsStub,
          TemplatePersistenceSurfaces.TemplateParentsSerializer,
          Vector(TemplatePersistenceSurfaces.SuperClassNameIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.SelfType                                                   =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.SelfTypeStub,
          TemplatePersistenceSurfaces.SelfTypeSerializer,
          Vector(TemplatePersistenceSurfaces.SelfTypeClassNameIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.DerivesClause                                              =>
        PersistenceObligations.Required(
          TemplatePersistenceSurfaces.DerivesClauseStub,
          TemplatePersistenceSurfaces.GenericSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.FunctionDefinition                                         =>
        PersistenceObligations.Required(
          DefinitionPersistenceSurfaces.FunctionStub,
          DefinitionPersistenceSurfaces.FunctionSerializer,
          Vector(DefinitionPersistenceSurfaces.MethodNameIndex, DefinitionPersistenceSurfaces.TopLevelFunctionIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.FunctionDeclaration                                        =>
        PersistenceObligations.Required(
          DefinitionPersistenceSurfaces.FunctionStub,
          DefinitionPersistenceSurfaces.FunctionSerializer,
          Vector(DefinitionPersistenceSurfaces.MethodNameIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.PatternDefinition | PsiOutputRoleId.VariableDefinition | PsiOutputRoleId.ValueDeclaration |
          PsiOutputRoleId.VariableDeclaration =>
        PersistenceObligations.Required(
          DefinitionPersistenceSurfaces.PropertyStub,
          DefinitionPersistenceSurfaces.PropertySerializer,
          Vector(DefinitionPersistenceSurfaces.PropertyNameIndex, DefinitionPersistenceSurfaces.TopLevelPropertyIndex),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.PatternList                                                =>
        PersistenceObligations.Required(
          DefinitionPersistenceSurfaces.PatternListStub,
          DefinitionPersistenceSurfaces.PatternListSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.ReferencePattern                                           =>
        PersistenceObligations.Required(
          DefinitionPersistenceSurfaces.BindingStub,
          DefinitionPersistenceSurfaces.BindingSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.IdentifierList                                             =>
        PersistenceObligations.Required(
          DefinitionPersistenceSurfaces.IdentifierListStub,
          TemplatePersistenceSurfaces.GenericSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.FieldId                                                    =>
        PersistenceObligations.Required(
          DefinitionPersistenceSurfaces.FieldIdStub,
          DefinitionPersistenceSurfaces.FieldIdSerializer,
          Vector.empty,
          ImportPersistenceSurfaces.SelfNavigation
        )
      case PsiOutputRoleId.TypeAliasDeclaration | PsiOutputRoleId.TypeAliasDefinition =>
        PersistenceObligations.Required(
          DefinitionPersistenceSurfaces.TypeAliasStub,
          DefinitionPersistenceSurfaces.TypeAliasSerializer,
          Vector(
            DefinitionPersistenceSurfaces.TypeAliasNameIndex,
            DefinitionPersistenceSurfaces.TopLevelTypeAliasIndex
          ),
          ImportPersistenceSurfaces.SelfNavigation
        )
      case _                                                                          => PersistenceObligations.NotApplicable
    OutputCompositeDeclaration(
      id,
      parentId,
      range,
      role,
      surface,
      requirement,
      accessors,
      persistence,
      Some(NavigationObligation.Self)
    )

  lazy val CompoundTypeProductionIds = TypeAtomProductionIds ++ Set(
    "explicit-type-lambda",
    "ordinary-match-type",
    "ordinary-refinement-type",
    "ordinary-annotated-type",
    "capture-type-shorthand",
    "capture-type-explicit-set",
    "capture-function-result",
    "capture-function-result-ident",
    "match-type-pattern-reference",
    "match-type-pattern-variable",
    "match-type-pattern-wildcard"
  )

  def compoundChild(
      role: String,
      field: String,
      cardinality: ChildCardinality
  ): ChildDeclaration =
    val first = CompoundTypeProductionIds.toVector.sorted.head
    ChildDeclaration(role, field, cardinality, first, CompoundTypeProductionIds - first)

  val ImportStatementAccessors          = Vector(
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/toplevel/imports/ScImportOrExportStmt#importExprs()Lscala/collection/immutable/Seq;",
      required = true
    )
  )
  val ExportStatementAccessors          = ImportStatementAccessors ++ Vector(
    AccessorObligation(
      s"$ExportStatementApi#isTopLevel()Z",
      required = true,
      surfaceKind = SurfaceFactKind.Method
    ),
    AccessorObligation(
      s"$ExportStatementApi#topLevelQualifier()Lscala/Option;",
      required = true,
      SurfaceFactKind.Method
    )
  )
  val PackageAccessors                  = Vector(
    AccessorObligation(s"$PackageSurface#reference()Lscala/Option;", required = true),
    AccessorObligation(s"$PackageSurface#keyword()Lcom/intellij/psi/PsiElement;", required = true),
    AccessorObligation(
      s"$PackageSurface#parentPackageName()Ljava/lang/String;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(s"$PackageSurface#packageName()Ljava/lang/String;", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$PackageSurface#fullPackageName()Ljava/lang/String;", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$PackageSurface#isExplicit()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$PackageSurface#findExplicitMarker()Lscala/Option;", required = true),
    AccessorObligation(s"$PackageSurface#bodyText()Ljava/lang/String;", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$PackageSurface#packagings()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(s"$PackageSurface#getImportStatements()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(s"$PackageSurface#getExportStatements()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(s"$PackageSurface#getLBrace()Lscala/Option;", required = true),
    AccessorObligation(s"$PackageSurface#getRBrace()Lscala/Option;", required = true),
    AccessorObligation(s"$PackageSurface#getColon()Lscala/Option;", required = true),
    AccessorObligation(s"$PackageSurface#isEnclosedByBraces()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$PackageSurface#isEnclosedByColon()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$PackageSurface#end()Lscala/Option;", required = true)
  )
  val EndAccessors                      = Vector(
    AccessorObligation(s"$EndSurface#begin()Lscala/Option;", required = true),
    AccessorObligation(s"$EndSurface#keyword()Lcom/intellij/psi/PsiElement;", required = true),
    AccessorObligation(s"$EndSurface#tag()Lcom/intellij/psi/PsiElement;", required = true),
    AccessorObligation(s"$EndSurface#getName()Ljava/lang/String;", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$EndSurface#getReference()Lcom/intellij/psi/PsiReference;", required = true),
    AccessorObligation(s"$EndSurface#getElement()Lcom/intellij/psi/PsiElement;", required = true),
    AccessorObligation(
      s"$EndSurface#getRangeInElement()Lcom/intellij/openapi/util/TextRange;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(s"$EndSurface#resolve()Lcom/intellij/psi/PsiElement;", required = true),
    AccessorObligation(s"$EndSurface#isSoft()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$EndSurface#getCanonicalText()Ljava/lang/String;", required = true, SurfaceFactKind.Method)
  )
  val ImportExpressionAccessors         = Vector(
    AccessorObligation(s"$ImportExpressionSurface#reference()Lscala/Option;", required = true),
    AccessorObligation(s"$ImportExpressionSurface#selectorSet()Lscala/Option;", required = true),
    AccessorObligation(s"$ImportExpressionSurface#qualifier()Lscala/Option;", required = true)
  )
  val ImportSelectorsAccessors          = Vector(
    AccessorObligation(
      s"$ImportSelectorsSurface#selectors()Lscala/collection/immutable/Seq;",
      required = true
    )
  )
  val ImportSelectorAccessors           = Vector(
    AccessorObligation(
      s"$ImportSelectorSurface#parentImportExpression()Lorg/jetbrains/plugins/scala/lang/psi/api/toplevel/imports/ScImportExpr;",
      required = true
    ),
    AccessorObligation(s"$ImportSelectorSurface#reference()Lscala/Option;", required = true),
    AccessorObligation(s"$ImportSelectorSurface#givenTypeElement()Lscala/Option;", required = true)
  )
  val StableReferenceAccessors          = Vector(
    AccessorObligation(s"$StableReferenceSurface#qualifier()Lscala/Option;", required = true),
    AccessorObligation(
      s"$StableReferenceSurface#nameId()Lcom/intellij/psi/PsiElement;",
      required = true
    )
  )
  val SimpleTypeAccessors               = Vector(
    AccessorObligation(s"$SimpleTypeSurface#reference()Lscala/Option;", required = true),
    AccessorObligation(
      s"$SimpleTypeSurface#pathElement()Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScPathElement;",
      required = true
    ),
    AccessorObligation(s"$SimpleTypeSurface#isSingleton()Z", required = true, SurfaceFactKind.Method)
  )
  val TypeProjectionAccessors           = Vector(
    AccessorObligation(
      s"$TypeProjectionSurface#typeElement()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement;",
      required = true
    ),
    AccessorObligation(s"$TypeProjectionSurface#nameId()Lcom/intellij/psi/PsiElement;", required = true),
    AccessorObligation(s"$TypeProjectionSurface#qualifier()Lscala/Option;", required = true)
  )
  val LiteralTypeAccessors              = Vector(
    AccessorObligation(
      s"$LiteralTypeSurface#getLiteral()Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral;",
      required = true
    ),
    AccessorObligation(s"$LiteralTypeSurface#isSingleton()Z", required = true, SurfaceFactKind.Method)
  )
  val ParenthesizedTypeAccessors        = Vector(
    AccessorObligation(s"$ParenthesizedTypeSurface#innerElement()Lscala/Option;", required = true),
    AccessorObligation(s"$ParenthesizedTypeSurface#sameTreeParent()Lscala/Option;", required = true)
  )
  val TupleTypeAccessors                = Vector(
    AccessorObligation(
      s"$TupleTypeSurface#typeList()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypes;",
      required = true
    ),
    AccessorObligation(s"$TupleTypeSurface#components()Lscala/collection/immutable/Seq;", required = true)
  )
  val TupleTypesAccessors               = Vector(
    AccessorObligation(s"$TupleTypesSurface#types()Lscala/collection/immutable/Seq;", required = true)
  )
  val NamedTupleTypeAccessors           = Vector(
    AccessorObligation(s"$NamedTupleTypeSurface#components()Lscala/collection/immutable/Seq;", required = true)
  )
  val NamedTupleComponentAccessors      = Vector(
    AccessorObligation(
      s"$NamedTupleComponentSurface#namedTuple()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScNamedTupleTypeElement;",
      required = true
    ),
    AccessorObligation(s"$NamedTupleComponentSurface#nameElement()Lscala/Option;", required = true),
    AccessorObligation(s"$NamedTupleComponentSurface#typeElement()Lscala/Option;", required = true)
  )
  val FunctionTypeAccessors             = Vector(
    AccessorObligation(
      s"$FunctionTypeSurface#paramTypeElement()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement;",
      required = true
    ),
    AccessorObligation(s"$FunctionTypeSurface#returnTypeElement()Lscala/Option;", required = true),
    AccessorObligation(s"$FunctionTypeSurface#isContext()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$FunctionTypeSurface#isPure()Z", required = true, SurfaceFactKind.Method)
  )
  val DependentFunctionTypeAccessors    = Vector(
    AccessorObligation(
      s"$DependentFunctionTypeSurface#parameterClause()Lorg/jetbrains/plugins/scala/lang/psi/api/statements/params/ScParameterClause;",
      required = true
    ),
    AccessorObligation(s"$DependentFunctionTypeSurface#returnTypeElement()Lscala/Option;", required = true)
  )
  val PolyFunctionTypeAccessors         = Vector(
    AccessorObligation(s"$PolyFunctionTypeSurface#resultTypeElement()Lscala/Option;", required = true),
    AccessorObligation(s"$PolyFunctionTypeSurface#typeParameters()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(s"$PolyFunctionTypeSurface#typeParametersClause()Lscala/Option;", required = true)
  )
  val LiteralValueAccessors             = Vector(
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#getValue()Ljava/lang/Object;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#contentText()Ljava/lang/String;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#isSimpleLiteral()Z",
      required = true,
      SurfaceFactKind.Method
    )
  )
  private val AtomicExpressionAccessors = Vector(
    AccessorObligation(s"$ExpressionSurface#type()Lscala/util/Either;", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$ExpressionSurface#innerType()Lscala/util/Either;", required = true, SurfaceFactKind.Method)
  )
  val AtomicLiteralAccessors            = AtomicExpressionAccessors ++ Vector(
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#getValue()Ljava/lang/Object;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#contentRange()Lcom/intellij/openapi/util/TextRange;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#contentRangeInParent()Lcom/intellij/openapi/util/TextRange;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#contentText()Ljava/lang/String;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#isSimpleLiteral()Z",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/base/ScLiteral#literalType()Lorg/jetbrains/plugins/scala/lang/psi/types/ScType;",
      required = true,
      SurfaceFactKind.Method
    )
  )
  val TermReferenceAccessors            = AtomicExpressionAccessors ++ Vector(
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/base/ScReference#refName()Ljava/lang/String;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/base/ScReference#nameId()Lcom/intellij/psi/PsiElement;",
      required = true
    ),
    AccessorObligation(s"$ReferenceExpressionSurface#qualifier()Lscala/Option;", required = true)
  )
  val ThisReferenceAccessors            = AtomicExpressionAccessors ++ Vector(
    AccessorObligation(s"$ThisReferenceSurface#reference()Lscala/Option;", required = true),
    AccessorObligation(s"$ThisReferenceSurface#refTemplate()Lscala/Option;", required = true)
  )
  val SelectionExpressionAccessors      = TermReferenceAccessors
  val SuperReferenceAccessors           = AtomicExpressionAccessors ++ Vector(
    AccessorObligation(s"$SuperReferenceSurface#isHardCoded()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$SuperReferenceSurface#staticSuper()Lscala/Option;", required = true, SurfaceFactKind.Method),
    AccessorObligation(
      s"$SuperReferenceSurface#staticSuperName()Ljava/lang/String;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(s"$SuperReferenceSurface#drvTemplate()Lscala/Option;", required = true),
    AccessorObligation(s"$SuperReferenceSurface#reference()Lscala/Option;", required = true)
  )
  val ParameterizedTypeAccessors        = Vector(
    AccessorObligation(
      s"$ParameterizedTypeSurface#typeElement()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement;",
      required = true
    ),
    AccessorObligation(
      s"$ParameterizedTypeSurface#typeArgList()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeArgs;",
      required = true
    )
  )
  val TypeArgumentsAccessors            = Vector(
    AccessorObligation(s"$TypeArgumentsSurface#typeArgs()Lscala/collection/immutable/Seq;", required = true)
  )
  val NamedTypeArgumentsAccessors       = Vector(
    AccessorObligation(
      s"$NamedTypeArgumentsSurface#logicalTypeArguments()Lscala/collection/immutable/Seq;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(
      s"$NamedTypeArgumentsSurface#namedTypeArguments()Lscala/collection/immutable/Seq;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(
      s"$NamedTypeArgumentsSurface#typeArgs()Lscala/collection/immutable/Seq;",
      required = true,
      SurfaceFactKind.Method
    )
  )
  val NamedTypeArgumentAccessors        = Vector(
    AccessorObligation(s"$NamedTypeArgumentSurface#name()Lscala/Option;", required = true, SurfaceFactKind.Method),
    AccessorObligation(
      s"$NamedTypeArgumentSurface#nameElement()Lscala/Option;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(
      s"$NamedTypeArgumentSurface#typeElement()Lscala/Option;",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(s"$NamedTypeArgumentSurface#isNamed()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(
      s"$NamedTypeArgumentSurface#type()Lscala/util/Either;",
      required = true,
      SurfaceFactKind.Method
    )
  )
  val WildcardTypeAccessors             = Vector(
    AccessorObligation(s"$WildcardTypeSurface#lowerTypeElement()Lscala/Option;", required = true),
    AccessorObligation(s"$WildcardTypeSurface#upperTypeElement()Lscala/Option;", required = true)
  )
  val ContextBoundAccessors             = Vector(
    AccessorObligation(
      s"$ContextBoundSurface#typeElement()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement;",
      required = true
    ),
    AccessorObligation(s"$ContextBoundSurface#nameIdOpt()Lscala/Option;", required = true),
    AccessorObligation(s"$ContextBoundSurface#parentTypeParam()Lscala/Option;", required = true)
  )
  val TypeLambdaAccessors               = Vector(
    AccessorObligation(s"$TypeLambdaSurface#resultTypeElement()Lscala/Option;", required = true),
    AccessorObligation(s"$TypeLambdaSurface#typeParameters()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(s"$TypeLambdaSurface#typeParametersClause()Lscala/Option;", required = true)
  )
  val InfixTypeAccessors                = Vector(
    AccessorObligation(
      s"$InfixTypeSurface#left()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement;",
      required = true
    ),
    AccessorObligation(s"$InfixTypeSurface#rightOption()Lscala/Option;", required = true),
    AccessorObligation(
      s"$InfixTypeSurface#operation()Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScStableCodeReference;",
      required = true
    )
  )
  val MatchTypeAccessors                = Vector(
    AccessorObligation(
      s"$MatchTypeSurface#scrutineeTypeElement()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement;",
      required = true
    ),
    AccessorObligation(s"$MatchTypeSurface#cases()Lscala/Option;", required = true)
  )
  val MatchTypeCasesAccessors           = Vector(
    AccessorObligation(
      s"$MatchTypeCasesSurface#firstCase()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScMatchTypeCase;",
      required = true
    ),
    AccessorObligation(s"$MatchTypeCasesSurface#cases()Lscala/collection/immutable/Seq;", required = true)
  )
  val MatchTypeCaseAccessors            = Vector(
    AccessorObligation(s"$MatchTypeCaseSurface#pattern()Lscala/Option;", required = true),
    AccessorObligation(s"$MatchTypeCaseSurface#result()Lscala/Option;", required = true)
  )
  val MatchTypeVariableAccessors        = Vector(
    AccessorObligation(s"$MatchTypeVariableSurface#nameId()Lcom/intellij/psi/PsiElement;", required = true)
  )
  val CompoundTypeAccessors             = Vector(
    AccessorObligation(s"$CompoundTypeSurface#components()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(s"$CompoundTypeSurface#refinement()Lscala/Option;", required = true)
  )
  val RefinementAccessors               = Vector(
    AccessorObligation(s"$RefinementSurface#holders()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(s"$RefinementSurface#types()Lscala/collection/immutable/Seq;", required = true)
  )
  val AnnotatedTypeAccessors            = Vector(
    AccessorObligation(
      s"$AnnotatedTypeSurface#typeElement()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement;",
      required = true
    )
  )
  val CaptureTypeAccessors              = Vector(
    AccessorObligation(
      s"$CaptureTypeSurface#innerElement()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement;",
      required = true
    ),
    AccessorObligation(s"$CaptureTypeSurface#captureSet()Lscala/Option;", required = true)
  )
  val CaptureReferenceAccessors         = Vector(
    AccessorObligation(s"$CaptureReferenceSurface#captureRef()Lscala/Option;", required = true),
    AccessorObligation(
      s"$CaptureReferenceSurface#hasCapabilityReach()Z",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(s"$CaptureReferenceSurface#captureFilter()Lscala/Option;", required = true),
    AccessorObligation(
      s"$CaptureReferenceSurface#isReadOnlyCapability()Z",
      required = true,
      SurfaceFactKind.Method
    )
  )
  val CaptureFilterAccessors            = Vector(
    AccessorObligation(
      s"$CaptureFilterSurface#filterId()Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScReference;",
      required = true
    )
  )
  val ModifierListAccessors             = Vector(
    AccessorObligation(s"$ModifierListSurface#accessModifier()Lscala/Option;", required = true),
    AccessorObligation(s"$ModifierListSurface#modifiers()I", required = true, SurfaceFactKind.Method),
    AccessorObligation(
      s"$ModifierListSurface#modifiersOrdered()Lscala/collection/immutable/Seq;",
      required = true,
      SurfaceFactKind.Method
    )
  )
  val AccessModifierAccessors           = Vector(
    AccessorObligation(s"$AccessModifierSurface#idText()Lscala/Option;", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$AccessModifierSurface#isPrivate()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$AccessModifierSurface#isProtected()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$AccessModifierSurface#isThis()Z", required = true, SurfaceFactKind.Method)
  )
  val AnnotationsAccessors              = Vector(
    AccessorObligation(
      s"$AnnotationsSurface#getAnnotations()[Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScAnnotation;",
      required = true,
      SurfaceFactKind.Method
    )
  )
  val AnnotationAccessors               = Vector(
    AccessorObligation(
      s"$AnnotationSurface#annotationExpr()Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScAnnotationExpr;",
      required = true
    ),
    AccessorObligation(
      s"$AnnotationSurface#constructorInvocation()Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScConstructorInvocation;",
      required = true
    ),
    AccessorObligation(
      s"$AnnotationSurface#typeElement()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement;",
      required = true
    )
  )
  val AnnotationExprAccessors           = Vector(
    AccessorObligation(
      s"$AnnotationExprSurface#constructorInvocation()Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScConstructorInvocation;",
      required = true
    ),
    AccessorObligation(s"$AnnotationExprSurface#getAttributes()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(
      s"$AnnotationExprSurface#getAnnotationParameters()Lscala/collection/immutable/Seq;",
      required = true
    )
  )
  val ConstructorAccessors              = Vector(
    AccessorObligation(
      s"$ConstructorSurface#typeElement()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement;",
      required = true
    ),
    AccessorObligation(s"$ConstructorSurface#simpleTypeElement()Lscala/Option;", required = true),
    AccessorObligation(s"$ConstructorSurface#typeArgList()Lscala/Option;", required = true),
    AccessorObligation(s"$ConstructorSurface#args()Lscala/Option;", required = true),
    AccessorObligation(s"$ConstructorSurface#arguments()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(s"$ConstructorSurface#reference()Lscala/Option;", required = true)
  )
  val AnnotationArgumentsAccessors      = Vector(
    AccessorObligation(s"$AnnotationArgumentsSurface#exprs()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(s"$AnnotationArgumentsSurface#isUsing()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$AnnotationArgumentsSurface#isArgsInParens()Z", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$AnnotationArgumentsSurface#getArgsCount()I", required = true, SurfaceFactKind.Method)
  )
  val ExpressionPayloadAccessors        = Vector(
    AccessorObligation(s"$ExpressionSurface#type()Lscala/util/Either;", required = true, SurfaceFactKind.Method),
    AccessorObligation(s"$ExpressionSurface#innerType()Lscala/util/Either;", required = true, SurfaceFactKind.Method)
  )
  private val FunctionAccessors         = Vector(
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/ScFunction#hasAssign()Z",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/ScFunction#paramClauses()Lorg/jetbrains/plugins/scala/lang/psi/api/statements/params/ScParameters;",
      required = true
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/ScFunction#returnTypeElement()Lscala/Option;",
      required = true
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/toplevel/ScTypeParametersOwner#typeParameters()Lscala/collection/immutable/Seq;",
      required = true
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/toplevel/ScTypeParametersOwner#typeParametersClause()Lscala/Option;",
      required = true
    )
  )
  val FunctionDefinitionAccessors       =
    AccessorObligation(s"$FunctionDefinitionSurface#body()Lscala/Option;", required = true) +: FunctionAccessors
  val FunctionDeclarationAccessors      = FunctionAccessors
  val PropertyDefinitionAccessors       = Vector(
    AccessorObligation(
      s"$PatternDefinitionSurface#pList()Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScPatternList;",
      required = true
    ),
    AccessorObligation(s"$PatternDefinitionSurface#expr()Lscala/Option;", required = true),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/ScValueOrVariable#typeElement()Lscala/Option;",
      required = true
    )
  )
  val VariableDefinitionAccessors       = Vector(
    AccessorObligation(
      s"$VariableDefinitionSurface#pList()Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScPatternList;",
      required = true
    ),
    AccessorObligation(s"$VariableDefinitionSurface#expr()Lscala/Option;", required = true),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/ScValueOrVariable#typeElement()Lscala/Option;",
      required = true
    )
  )
  val PropertyDeclarationAccessors      = Vector(
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/ScValueOrVariableDeclaration#getIdList()Lorg/jetbrains/plugins/scala/lang/psi/api/base/ScIdList;",
      required = true
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/ScValueOrVariable#typeElement()Lscala/Option;",
      required = true
    )
  )
  val PatternListAccessors              = Vector(
    AccessorObligation(s"$PatternListSurface#bindings()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(s"$PatternListSurface#simplePatterns()Z", required = true, SurfaceFactKind.Method)
  )
  val ReferencePatternAccessors         = Vector(
    AccessorObligation(s"$ReferencePatternSurface#nameId()Lcom/intellij/psi/PsiElement;", required = true)
  )
  val IdentifierListAccessors           = Vector(
    AccessorObligation(s"$IdentifierListSurface#fieldIds()Lscala/collection/immutable/Seq;", required = true)
  )
  val FieldIdAccessors                  = Vector(
    AccessorObligation(s"$FieldIdSurface#nameId()Lcom/intellij/psi/PsiElement;", required = true)
  )
  val TypeAliasDeclarationAccessors     = Vector(
    AccessorObligation(s"$TypeAliasDeclarationSurface#nameId()Lcom/intellij/psi/PsiElement;", required = true),
    AccessorObligation(s"$TypeAliasDeclarationSurface#lowerTypeElement()Lscala/Option;", required = true),
    AccessorObligation(s"$TypeAliasDeclarationSurface#upperTypeElement()Lscala/Option;", required = true)
  )
  val TypeAliasDefinitionAccessors      = Vector(
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/ScTypeAliasDefinition#aliasedTypeElement()Lscala/Option;",
      required = true
    ),
    AccessorObligation(s"$TypeAliasDefinitionSurface#lowerTypeElement()Lscala/Option;", required = true),
    AccessorObligation(s"$TypeAliasDefinitionSurface#upperTypeElement()Lscala/Option;", required = true)
  )
  val ParameterAccessors                = Vector(
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/params/ScParameter#typeElement()Lscala/Option;",
      required = true
    )
  )
  val ParameterTypeAccessors            = Vector(
    AccessorObligation(
      s"$ParameterTypeSurface#typeElement()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement;",
      required = true
    )
  )
  val PureParameterTypeAccessors        = Vector(
    AccessorObligation(
      s"$PureParameterTypeSurface#typeElement()Lorg/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement;",
      required = true
    ),
    AccessorObligation(
      s"$PureParameterTypeSurface#isCallByNameParameter()Z",
      required = true
    )
  )
  val TemplateParentsAccessors          = Vector(
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/toplevel/templates/ScTemplateParents#typeElements()Lscala/collection/immutable/Seq;",
      required = true
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/toplevel/templates/ScTemplateParents#allTypeElements()Lscala/collection/immutable/Seq;",
      required = true
    )
  )
  val SelfTypeAccessors                 = Vector(
    AccessorObligation(s"$SelfTypeSurface#typeElement()Lscala/Option;", required = true)
  )
  val DerivesClauseAccessors            = Vector(
    AccessorObligation(s"$DerivesClauseSurface#derivedReferences()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(
      s"$DerivesClauseSurface#owner()Lorg/jetbrains/plugins/scala/lang/psi/api/toplevel/typedef/ScDerivesClauseOwner;",
      required = true
    )
  )
  val TypeParameterAccessors            = Vector(
    AccessorObligation(s"$TypeParameterSurface#lowerTypeElement()Lscala/Option;", required = true),
    AccessorObligation(s"$TypeParameterSurface#upperTypeElement()Lscala/Option;", required = true),
    AccessorObligation(s"$TypeParameterSurface#contextBounds()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(s"$TypeParameterSurface#typeParameters()Lscala/collection/immutable/Seq;", required = true),
    AccessorObligation(s"$TypeParameterSurface#typeParametersClause()Lscala/Option;", required = true),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/params/ScTypeParam#isCovariant()Z",
      required = true,
      SurfaceFactKind.Method
    ),
    AccessorObligation(
      "org/jetbrains/plugins/scala/lang/psi/api/statements/params/ScTypeParam#isContravariant()Z",
      required = true,
      SurfaceFactKind.Method
    )
  )

  val GivenSelectorBoundAnchor         = InventoryAncestor(
    InventoryKind.Node,
    "ImportSelector",
    Vector(CatalogPathSegment.NamedField("bound"))
  )
  val GivenTypeProductionIds           = Set(
    "import-selector-bound-type",
    "import-selector-bound-applied-type",
    "import-selector-given-bound-qualified-type",
    "type-atom-projection",
    "type-atom-singleton-ident",
    "type-atom-singleton-select",
    "type-atom-literal",
    "type-atom-parenthesized",
    "import-selector-given-bound-wildcard-type",
    "ordinary-infix-type"
  )
  val TypeAtomProductionIds            = Set(
    "import-selector-bound-type",
    "ordinary-applied-type",
    "import-selector-given-bound-qualified-type",
    "type-atom-projection",
    "type-atom-singleton-ident",
    "type-atom-singleton-select",
    "type-atom-literal",
    "type-atom-parenthesized",
    "ordinary-wildcard-type",
    "ordinary-tuple-type",
    "named-tuple-type",
    "ordinary-function-type",
    "pure-nullary-function-type",
    "pure-function-type",
    "capture-nullary-function-type",
    "capture-function-type",
    "dependent-function-type",
    "polymorphic-function-type",
    "ordinary-infix-type",
    "ordinary-match-type",
    "ordinary-refinement-type",
    "ordinary-annotated-type",
    "capture-type-shorthand",
    "capture-type-explicit-set",
    "capture-function-result",
    "capture-function-result-ident",
    "by-name-parameter-type",
    "impure-by-name-parameter-type",
    "pure-by-name-parameter-type",
    "capture-by-name-parameter-type",
    "repeated-parameter-type"
  )
  val SimpleTypeAliasProductionIds     = Set(
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
  )
  val RefinementTypeAliasProductionIds = SimpleTypeAliasProductionIds -- Set(
    "definition-opaque-simple-ident-type-alias",
    "definition-type-lambda-alias",
    "definition-opaque-bounded-type-alias"
  )
  val GivenTypeQualifierProductionIds  = Set(
    "import-selector-given-bound-qualifier-ident",
    "import-selector-given-bound-qualifier-select"
  )

  val OwnerTypeAnchors = Vector(
    InventoryAncestor(InventoryKind.Node, "DefDef", Vector(CatalogPathSegment.NamedField("tpt"))),
    InventoryAncestor(InventoryKind.Node, "ValDef", Vector(CatalogPathSegment.NamedField("tpt"))),
    InventoryAncestor(InventoryKind.Node, "TypeDef", Vector(CatalogPathSegment.NamedField("rhs"))),
    InventoryAncestor(InventoryKind.Node, "TermLambdaTypeTree", Vector(CatalogPathSegment.NamedField("body"))),
    InventoryAncestor(
      InventoryKind.Node,
      "Template",
      Vector(CatalogPathSegment.NamedField("preParentsOrDerived"), CatalogPathSegment.RepeatedElement)
    )
  )

  def givenTypeOccurrences: Vector[CompilerProductionContextPattern] =
    val direct = CompilerProductionContextPattern(
      ContextPattern.Parent(
        InventoryKind.Node,
        "ImportSelector",
        Vector(CatalogPathSegment.NamedField("bound"))
      ),
      SourceClassification.SourceReachable
    )
    val nested = Vector(
      "AppliedTypeTree" -> Vector(CatalogPathSegment.NamedField("tpt")),
      "AppliedTypeTree" -> Vector(
        CatalogPathSegment.NamedField("args"),
        CatalogPathSegment.RepeatedElement
      ),
      "InfixOp"         -> Vector(CatalogPathSegment.NamedField("left")),
      "InfixOp"         -> Vector(CatalogPathSegment.NamedField("right")),
      "TypeBoundsTree"  -> Vector(CatalogPathSegment.NamedField("lo")),
      "TypeBoundsTree"  -> Vector(CatalogPathSegment.NamedField("hi")),
      "Parens"          -> Vector(CatalogPathSegment.NamedField("t"))
    ).map: (owner, path) =>
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchor(InventoryKind.Node, owner, path, GivenSelectorBoundAnchor),
        SourceClassification.SourceReachable
      )
    direct +: nested

  def typeAtomOccurrences: Vector[CompilerProductionContextPattern] =
    val direct              = OwnerTypeAnchors.flatMap: anchor =>
      Vector(
        ContextPattern.Parent(anchor.ownerKind, anchor.ownerPrefix, anchor.path),
        ContextPattern.ParentUnderAnchor(
          InventoryKind.Node,
          "LambdaTypeTree",
          Vector(CatalogPathSegment.NamedField("body")),
          anchor
        )
      ).map(CompilerProductionContextPattern(_, SourceClassification.SourceReachable))
    val nestedPaths         = Vector("Parens" -> Vector(CatalogPathSegment.NamedField("t")))
    val appliedConstructors = OwnerTypeAnchors.map: anchor =>
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchor(
          InventoryKind.Node,
          "AppliedTypeTree",
          Vector(CatalogPathSegment.NamedField("tpt")),
          anchor
        ),
        SourceClassification.SourceReachable
      )
    givenTypeOccurrences ++ direct ++ appliedConstructors ++ boundTypeOccurrences ++ contextBoundTypeOccurrences ++
      compoundTypeChildOccurrences ++ OwnerTypeAnchors
        .flatMap(anchor =>
          nestedPaths.map: (owner, path) =>
            CompilerProductionContextPattern(
              ContextPattern.ParentUnderAnchor(InventoryKind.Node, owner, path, anchor),
              SourceClassification.SourceReachable
            )
        )

  private val CompoundTypeChildPaths = Vector(
    "Tuple"           -> Vector(CatalogPathSegment.NamedField("trees"), CatalogPathSegment.RepeatedElement),
    "NamedArg"        -> Vector(CatalogPathSegment.NamedField("arg")),
    "Function"        -> Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
    "Function"        -> Vector(CatalogPathSegment.NamedField("body")),
    "PolyFunction"    -> Vector(CatalogPathSegment.NamedField("body")),
    "ByNameTypeTree"  -> Vector(CatalogPathSegment.NamedField("result")),
    "PostfixOp"       -> Vector(CatalogPathSegment.NamedField("od")),
    "InfixOp"         -> Vector(CatalogPathSegment.NamedField("left")),
    "InfixOp"         -> Vector(CatalogPathSegment.NamedField("right")),
    "MatchTypeTree"   -> Vector(CatalogPathSegment.NamedField("bound")),
    "MatchTypeTree"   -> Vector(CatalogPathSegment.NamedField("selector")),
    "CaseDef"         -> Vector(CatalogPathSegment.NamedField("pat")),
    "CaseDef"         -> Vector(CatalogPathSegment.NamedField("body")),
    "RefinedTypeTree" -> Vector(CatalogPathSegment.NamedField("tpt")),
    "Annotated"       -> Vector(CatalogPathSegment.NamedField("arg"))
  )
  val CompoundTypeTraversedAncestors = (CompoundTypeChildPaths ++ Vector(
    "AppliedTypeTree"      -> Vector(CatalogPathSegment.NamedField("tpt")),
    "AppliedTypeTree"      -> Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
    "MatchTypeTree"        -> Vector(CatalogPathSegment.NamedField("cases"), CatalogPathSegment.RepeatedElement),
    "LambdaTypeTree"       -> Vector(CatalogPathSegment.NamedField("body")),
    "PolyFunction"         -> Vector(CatalogPathSegment.NamedField("targs"), CatalogPathSegment.RepeatedElement),
    "TypeDef"              -> Vector(CatalogPathSegment.NamedField("rhs")),
    "TypeBoundsTree"       -> Vector(CatalogPathSegment.NamedField("lo")),
    "TypeBoundsTree"       -> Vector(CatalogPathSegment.NamedField("hi")),
    "TypeBoundsTree"       -> Vector(CatalogPathSegment.NamedField("alias")),
    "ContextBoundTypeTree" -> Vector(CatalogPathSegment.NamedField("tycon")),
    "ContextBounds"        -> Vector(CatalogPathSegment.NamedField("bounds")),
    "ContextBounds"        -> Vector(CatalogPathSegment.NamedField("cxBounds"), CatalogPathSegment.RepeatedElement),
    "ValDef"               -> Vector(CatalogPathSegment.NamedField("tpt")),
    "Parens"               -> Vector(CatalogPathSegment.NamedField("t")),
    "TermLambdaTypeTree"   -> Vector(CatalogPathSegment.NamedField("body"))
  )).map((owner, path) => InventoryAncestor(InventoryKind.Node, owner, path)).distinct

  private val MatchTypeCasesAnchor   = InventoryAncestor(
    InventoryKind.Node,
    "MatchTypeTree",
    Vector(CatalogPathSegment.NamedField("cases"), CatalogPathSegment.RepeatedElement)
  )
  private val MatchTypePatternAnchor =
    InventoryAncestor(InventoryKind.Node, "CaseDef", Vector(CatalogPathSegment.NamedField("pat")))

  def matchTypePatternOccurrences: Vector[CompilerProductionContextPattern] =
    CompilerProductionContextPattern(
      ContextPattern.ParentWithAncestor(
        InventoryKind.Node,
        "CaseDef",
        Vector(CatalogPathSegment.NamedField("pat")),
        MatchTypeCasesAnchor
      ),
      SourceClassification.SourceReachable
    ) +: (CompoundTypeChildPaths ++ Vector(
      "AppliedTypeTree" -> Vector(CatalogPathSegment.NamedField("tpt")),
      "AppliedTypeTree" -> Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
    )).map: (owner, path) =>
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchorThroughWithParent(
          InventoryKind.Node,
          owner,
          path,
          CompoundTypeTraversedAncestors,
          MatchTypePatternAnchor,
          MatchTypeCasesAnchor
        ),
        SourceClassification.SourceReachable
      )

  def compoundTypeChildOccurrences: Vector[CompilerProductionContextPattern] =
    (GivenSelectorBoundAnchor +: OwnerTypeAnchors).flatMap: anchor =>
      CompoundTypeChildPaths.map: (owner, path) =>
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchorThrough(
            InventoryKind.Node,
            owner,
            path,
            CompoundTypeTraversedAncestors,
            anchor
          ),
          SourceClassification.SourceReachable
        )

  def compoundTypeArgumentOccurrences: Vector[CompilerProductionContextPattern] =
    OwnerTypeAnchors.map: anchor =>
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchor(
          InventoryKind.Node,
          "AppliedTypeTree",
          Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement),
          anchor
        ),
        SourceClassification.SourceReachable
      )

  def dependentParameterTypeOccurrences: Vector[CompilerProductionContextPattern] =
    Vector(
      CompilerProductionContextPattern(
        ContextPattern.ParentWithAncestor(
          InventoryKind.Node,
          "ValDef",
          Vector(CatalogPathSegment.NamedField("tpt")),
          InventoryAncestor(
            InventoryKind.Node,
            "DefDef",
            Vector(
              CatalogPathSegment.NamedField("paramss"),
              CatalogPathSegment.RepeatedElement,
              CatalogPathSegment.RepeatedElement
            )
          )
        ),
        SourceClassification.SourceReachable
      ),
      CompilerProductionContextPattern(
        ContextPattern.ParentWithAncestor(
          InventoryKind.Node,
          "ValDef",
          Vector(CatalogPathSegment.NamedField("tpt")),
          InventoryAncestor(
            InventoryKind.Node,
            "Function",
            Vector(CatalogPathSegment.NamedField("args"), CatalogPathSegment.RepeatedElement)
          )
        ),
        SourceClassification.SourceReachable
      )
    )

  def contextBoundTypeOccurrences: Vector[CompilerProductionContextPattern] =
    OwnerTypeAnchors.map: anchor =>
      CompilerProductionContextPattern(
        ContextPattern.ParentUnderAnchor(
          InventoryKind.Node,
          "ContextBoundTypeTree",
          Vector(CatalogPathSegment.NamedField("tycon")),
          anchor
        ),
        SourceClassification.SourceReachable
      )

  def boundTypeOccurrences: Vector[CompilerProductionContextPattern] =
    val direct                = OwnerTypeAnchors.flatMap: anchor =>
      Vector("lo", "hi", "alias").map: field =>
        CompilerProductionContextPattern(
          ContextPattern.ParentUnderAnchor(
            InventoryKind.Node,
            "TypeBoundsTree",
            Vector(CatalogPathSegment.NamedField(field)),
            anchor
          ),
          SourceClassification.SourceReachable
        )
    val polymorphicParameters = OwnerTypeAnchors.flatMap: anchor =>
      Vector("lo", "hi", "alias").map: field =>
        CompilerProductionContextPattern(
          ContextPattern.ParentWithAncestorPrefix(
            InventoryKind.Node,
            "TypeBoundsTree",
            Vector(CatalogPathSegment.NamedField(field)),
            Vector(
              InventoryAncestor(InventoryKind.Node, "TypeDef", Vector(CatalogPathSegment.NamedField("rhs"))),
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
    direct ++ polymorphicParameters

  def typeElementTemplate(
      outputRole: PsiOutputRoleId,
      surface: String,
      accessors: Vector[AccessorObligation],
      childRoles: String*
  ): LocalOutputCompositeTemplate =
    typeElementTemplateWithRange(
      outputRole,
      surface,
      accessors,
      OutputRangeDeclaration.CompilerPosition,
      childRoles*
    )

  def typeElementTemplateWithRange(
      outputRole: PsiOutputRoleId,
      surface: String,
      accessors: Vector[AccessorObligation],
      range: OutputRangeDeclaration,
      childRoles: String*
  ): LocalOutputCompositeTemplate =
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "type",
          None,
          range,
          outputRole,
          surface,
          accessors
        )
      ),
      childRoles.map(_ -> Some("type")).toMap
    )

  def qualifiedTypeTemplate: LocalOutputCompositeTemplate =
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
      Map("qualifier" -> Some("reference"))
    )

  def stableReferenceTemplate(childRoles: String*): LocalOutputCompositeTemplate =
    LocalOutputCompositeTemplate(
      Vector(
        outputComposite(
          "reference",
          None,
          OutputRangeDeclaration.CompilerPosition,
          PsiOutputRoleId.StableReference,
          StableReferenceSurface,
          StableReferenceAccessors
        )
      ),
      childRoles.map(_ -> Some("reference")).toMap
    )

  def transparentTemplate(childRoles: String*): LocalOutputCompositeTemplate =
    LocalOutputCompositeTemplate(Vector.empty, childRoles.map(_ -> None).toMap)
