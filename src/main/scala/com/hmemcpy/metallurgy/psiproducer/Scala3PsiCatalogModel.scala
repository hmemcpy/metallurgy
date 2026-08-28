package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*

private[metallurgy] enum FieldDispositionKind:
  case Child, TerminalOrLayout, SemanticOnly, RecoveryOnly, Synthetic, Unsupported
private[metallurgy] final case class FieldDisposition(fieldName: String, kind: FieldDispositionKind)
private[metallurgy] final case class GrammarRoleId(value: String):
  require(value.nonEmpty)
private[metallurgy] object GrammarRoleId:
  val CompilationUnit           = GrammarRoleId("scala.compilation-unit")
  val PackageClause             = GrammarRoleId("scala.package.clause")
  val PackageReference          = GrammarRoleId("scala.package.reference")
  val ImportStatement           = GrammarRoleId("scala.import.statement")
  val ExportStatement           = GrammarRoleId("scala.export.statement")
  val AbsentProduct             = GrammarRoleId("scala.absent-product")
  val StableReference           = GrammarRoleId("scala.reference.stable")
  val ImportSelector            = GrammarRoleId("scala.import.selector")
  val ImportSelectorName        = GrammarRoleId("scala.import.selector-name")
  val SimpleType                = GrammarRoleId("scala.type.simple")
  val TypeProjection            = GrammarRoleId("scala.type.projection")
  val SingletonType             = GrammarRoleId("scala.type.singleton")
  val LiteralType               = GrammarRoleId("scala.type.literal")
  val ParenthesizedType         = GrammarRoleId("scala.type.parenthesized")
  val TupleType                 = GrammarRoleId("scala.type.tuple")
  val NamedTupleType            = GrammarRoleId("scala.type.named-tuple")
  val NamedTupleComponent       = GrammarRoleId("scala.type.named-tuple.component")
  val FunctionType              = GrammarRoleId("scala.type.function")
  val DependentFunctionType     = GrammarRoleId("scala.type.function.dependent")
  val PolyFunctionType          = GrammarRoleId("scala.type.function.polymorphic")
  val ByNameParameterType       = GrammarRoleId("scala.parameter.type.by-name")
  val RepeatedParameterType     = GrammarRoleId("scala.parameter.type.repeated")
  val RepeatedParameterStar     = GrammarRoleId("scala.parameter.type.repeated.star")
  val LiteralValue              = GrammarRoleId("scala.literal.value")
  val AppliedType               = GrammarRoleId("scala.type.applied")
  val TypeArgumentList          = GrammarRoleId("scala.type-argument.list")
  val PositionalTypeArgument    = GrammarRoleId("scala.type-argument.positional")
  val NamedTypeArgument         = GrammarRoleId("scala.type-argument.named")
  val ExpressionTypeApply       = GrammarRoleId("scala.expression.type-application-island")
  val WildcardType              = GrammarRoleId("scala.type.wildcard")
  val TypeBounds                = GrammarRoleId("scala.type.bounds")
  val ContextBounds             = GrammarRoleId("scala.type.context-bounds")
  val ContextBound              = GrammarRoleId("scala.type.context-bound")
  val TypeLambda                = GrammarRoleId("scala.type.lambda")
  val TermLambda                = GrammarRoleId("scala.type.term-lambda")
  val InfixType                 = GrammarRoleId("scala.type.infix")
  val MatchType                 = GrammarRoleId("scala.type.match")
  val MatchTypeCase             = GrammarRoleId("scala.type.match.case")
  val MatchTypePatternVariable  = GrammarRoleId("scala.type.match.pattern-variable")
  val RefinementType            = GrammarRoleId("scala.type.refinement")
  val AnnotatedType             = GrammarRoleId("scala.type.annotated")
  val CaptureType               = GrammarRoleId("scala.type.capture")
  val CaptureSet                = GrammarRoleId("scala.type.capture-set")
  val CaptureReference          = GrammarRoleId("scala.type.capture-reference")
  val CaptureFilter             = GrammarRoleId("scala.type.capture-filter")
  val CaptureSynthetic          = GrammarRoleId("scala.type.capture.synthetic")
  val ExpressionPayload         = GrammarRoleId("scala.expression.payload")
  val TermReference             = GrammarRoleId("scala.expression.reference.term")
  val ThisReference             = GrammarRoleId("scala.expression.reference.this")
  val QualifiedThisReference    = GrammarRoleId("scala.expression.reference.this-qualified")
  val SelectionExpression       = GrammarRoleId("scala.expression.selection")
  val OrdinaryApplication       = GrammarRoleId("scala.expression.application.ordinary")
  val NamedArgument             = GrammarRoleId("scala.expression.application.argument.named")
  val SuperReference            = GrammarRoleId("scala.expression.reference.super")
  val SelectionQualifier        = GrammarRoleId("scala.expression.selection.qualifier")
  val ExpressionIntegerLiteral  = GrammarRoleId("scala.expression.literal.integer")
  val ExpressionLongLiteral     = GrammarRoleId("scala.expression.literal.long")
  val ExpressionFloatLiteral    = GrammarRoleId("scala.expression.literal.float")
  val ExpressionDoubleLiteral   = GrammarRoleId("scala.expression.literal.double")
  val ExpressionBooleanLiteral  = GrammarRoleId("scala.expression.literal.boolean")
  val ExpressionCharLiteral     = GrammarRoleId("scala.expression.literal.char")
  val ExpressionStringLiteral   = GrammarRoleId("scala.expression.literal.string")
  val ExpressionNullLiteral     = GrammarRoleId("scala.expression.literal.null")
  val Modifiers                 = GrammarRoleId("scala.modifiers")
  val AccessModifier            = GrammarRoleId("scala.modifier.access")
  val KeywordModifier           = GrammarRoleId("scala.modifier.keyword")
  val Annotations               = GrammarRoleId("scala.annotations")
  val Annotation                = GrammarRoleId("scala.annotation")
  val AnnotationArguments       = GrammarRoleId("scala.annotation.arguments")
  val ClassDefinition           = GrammarRoleId("scala.definition.class")
  val TraitDefinition           = GrammarRoleId("scala.definition.trait")
  val ObjectDefinition          = GrammarRoleId("scala.definition.object")
  val EnumDefinition            = GrammarRoleId("scala.definition.enum")
  val EnumCase                  = GrammarRoleId("scala.definition.enum.case")
  val Template                  = GrammarRoleId("scala.template")
  val TemplateConstructor       = GrammarRoleId("scala.template.constructor")
  val TypeParameterClause       = GrammarRoleId("scala.type-parameter.clause")
  val UnboundedTypeParameter    = GrammarRoleId("scala.type-parameter.unbounded")
  val BoundedTypeParameter      = GrammarRoleId("scala.type-parameter.bounded")
  val HigherKindedTypeParameter = GrammarRoleId("scala.type-parameter.higher-kinded")
  val TermParameter             = GrammarRoleId("scala.term-parameter")
  val ClassParameter            = GrammarRoleId("scala.class-parameter")
  val TemplateSelf              = GrammarRoleId("scala.template.self")
  val TemplateTypeTree          = GrammarRoleId("scala.template.type-tree")
  val FunctionDefinition        = GrammarRoleId("scala.definition.function")
  val PropertyDefinition        = GrammarRoleId("scala.definition.property")
  val ReferenceBinding          = GrammarRoleId("scala.pattern.reference-binding")
  val TypeAliasDeclaration      = GrammarRoleId("scala.definition.type-alias-declaration")
  val TypeAliasDefinition       = GrammarRoleId("scala.definition.type-alias-definition")
  val InferredTypeAbsence       = GrammarRoleId("scala.type.inferred-absence")
  val OutputFreeExpression      = GrammarRoleId("scala.expression.output-free-descendant")
private[metallurgy] enum ChildCardinality:
  case ExactlyOne, Optional
  case Repeated(minimum: Int, maximum: Option[Int])
  case Grouped(minimum: Int, maximum: Option[Int])
private[metallurgy] enum ChildSlice:
  case All
  case MatchingProductions
  case LeadingBeforeRuntimeTail(fieldName: String)
  case RuntimeTail(fieldName: String)
private[metallurgy] final case class ChildDeclaration(
    roleId: String,
    fieldName: String,
    cardinality: ChildCardinality,
    productionId: String,
    additionalProductionIds: Set[String] = Set.empty,
    slice: ChildSlice = ChildSlice.All
):
  require(productionId.nonEmpty)
  val productionIds: Set[String] = additionalProductionIds + productionId
private[metallurgy] enum TerminalIntervalSelector:
  case FieldBounds(startField: String, endField: String)
  case ChildGap(startRole: String, endRole: String)
  case ChildSeparators(roleId: String)
  case BeforeChild(roleId: String)
  case AfterChild(roleId: String)
  case BeforeChildOutputs(roleId: String)
  case ChildOutputGap(startRole: String, endRole: String)
  case ChildOutputSeparators(roleId: String)
  case CompilerEndMarkerKeyword
  case CompilerScannerToken(
      kind: ParserScannerTokenKind,
      occurrence: ScannerTokenOccurrence = ScannerTokenOccurrence.All
  )
  case CompilerScannerTokenBeforeChildOutputs(kind: ParserScannerTokenKind, roleId: String)
  case CompilerScannerTokenInChildGap(kind: ParserScannerTokenKind, startRole: String, endRole: String)
  case CompilerScannerTokenInChildOutputGap(kind: ParserScannerTokenKind, startRole: String, endRole: String)
  case BalancedScannerTokenAfterChild(
      kind: ParserScannerTokenKind,
      opening: ParserScannerTokenKind,
      closing: ParserScannerTokenKind,
      roleId: String,
      occurrence: ScannerTokenOccurrence
  )
  case BalancedKeywordBeforeFirstChild(
      opening: ClosedSourceLexicalKind,
      closing: ClosedSourceLexicalKind,
      precedingRoleId: String,
      childRoleId: String
  )
  case BalancedPrefixBeforeFirstChild(
      opening: ClosedSourceLexicalKind,
      precedingRoleId: String,
      childRoleId: String
  )
  case BalancedSuffixAfterLastChild(
      opening: ClosedSourceLexicalKind,
      closing: ClosedSourceLexicalKind,
      precedingRoleId: String,
      childRoleId: String
  )
  case LocalOutput(outputId: String)
  case RootOutsideLocalOutput(outputId: String)
  case WholeProduction, WholeSource
private[metallurgy] enum ScannerTokenOccurrence:
  case All, First, Last
private[metallurgy] enum TerminalLeafTarget:
  case Token(surfaceId: String, expectedText: Option[String] = None)
  case Trivia, Delimiter, Separator, Parent
private[metallurgy] enum OccurrenceCardinality:
  case ExactlyOne, Optional
  case Repeated(minimum: Int, maximum: Option[Int])
private[metallurgy] final case class TerminalDeclaration(
    id: String,
    selector: TerminalIntervalSelector,
    target: TerminalLeafTarget,
    cardinality: OccurrenceCardinality,
    outputRoleId: PsiOutputRoleId,
    ownsStructuralEvidence: Option[Boolean] = None
):
  val claimsStructuralEvidence: Boolean = ownsStructuralEvidence.getOrElse(target == TerminalLeafTarget.Parent)
private[metallurgy] enum LayoutAlternative:
  case None
  case Braced(openPath: Vector[String], closePath: Vector[String])
  case Indented(indentPath: Vector[String], outdentPath: Vector[String])
private[metallurgy] enum RecoveryPolicy:
  case Reject
  case DiagnosticBound(diagnostic: ParserDiagnosticSeverity, alternatives: Vector[String])
private[metallurgy] enum TargetRequirement:
  case Native, NativeCandidate, Compatible
private[metallurgy] final case class AccessorObligation(
    surfaceId: String,
    required: Boolean,
    surfaceKind: SurfaceFactKind = SurfaceFactKind.PublicAccessor
)
private[metallurgy] enum PersistenceObligations:
  case NotApplicable
  case Required(
      stubSurfaceId: String,
      serializerSurfaceId: String,
      indexSurfaceIds: Vector[String],
      navigationSurfaceId: String
  )
private[metallurgy] enum NavigationObligation:
  case Self
private[metallurgy] enum PositionProvenancePolicy:
  case SourceDerivedOnly, PositionedIncludingSynthetic
private[metallurgy] enum ChildOccurrenceSelector:
  case First, Last
  case Exact(index: Int)
private[metallurgy] enum OutputBoundary:
  case ProductionStart(policy: PositionProvenancePolicy = PositionProvenancePolicy.SourceDerivedOnly)
  case ProductionEnd(policy: PositionProvenancePolicy = PositionProvenancePolicy.SourceDerivedOnly)
  case ProductionPoint
  case ProductionNameEnd
  case ProductionFirstIdentifierStart
  case ProductionFirstIdentifierEnd
  case ParentProductionEnd
  case TemplateLayoutStart
  case PreviousSignificantChildTokenStart(
      roleId: String,
      occurrence: ChildOccurrenceSelector,
      policy: PositionProvenancePolicy
  )
  case PreviousSignificantChildTokenStartWithinOwner(
      roleId: String,
      occurrence: ChildOccurrenceSelector,
      policy: PositionProvenancePolicy
  )
  case ChildStart(roleId: String, occurrence: ChildOccurrenceSelector, policy: PositionProvenancePolicy)
  case ChildEnd(roleId: String, occurrence: ChildOccurrenceSelector, policy: PositionProvenancePolicy)
  case EvidenceBoundaryAfterChild(
      roleId: String,
      occurrence: ChildOccurrenceSelector,
      followingRoleId: String,
      followingOccurrence: ChildOccurrenceSelector,
      expectedDelimiters: Vector[String],
      policy: PositionProvenancePolicy,
      fallbackToFollowingChildStart: Boolean = false
  )
  case NextScannerTokenStartAfterChild(
      roleId: String,
      occurrence: ChildOccurrenceSelector,
      kind: ParserScannerTokenKind,
      policy: PositionProvenancePolicy
  )
  case Advance(boundary: OutputBoundary, boundaryCount: Int)
private[metallurgy] enum OutputRangeDeclaration:
  case CompilerPosition
  case CompilerPositionWithPolicy(policy: PositionProvenancePolicy)
  case CompilerPositionWithTrailingBalancedBrackets(policy: PositionProvenancePolicy)
  case CompilerPositionWithBodyLayoutOrEndMarker(
      headerRole: String,
      bodyRole: Option[String],
      opening: ClosedSourceLexicalKind,
      closing: ClosedSourceLexicalKind,
      indentation: ClosedSourceLexicalKind
  )
  case BoundaryDerived(startBoundary: OutputBoundary, endBoundary: OutputBoundary)
  case BoundaryDerivedWithTrailingBalancedBrackets(startBoundary: OutputBoundary, endBoundary: OutputBoundary)
  case BalancedLexicalRangeBeforeChildOutput(
      roleId: String,
      opening: ClosedSourceLexicalKind,
      closing: ClosedSourceLexicalKind
  )
  case CompilerEndMarker
private[metallurgy] final case class PsiOutputRoleId(value: String):
  require(value.nonEmpty)
private[metallurgy] object PsiOutputRoleId:
  val SourceTerminal        = PsiOutputRoleId("scala.source.terminal")
  val PackageStatement      = PsiOutputRoleId("scala.package.statement")
  val EndStatement          = PsiOutputRoleId("scala.end.statement")
  val EndKeyword            = PsiOutputRoleId("scala.end.keyword")
  val ImportStatement       = PsiOutputRoleId("scala.import.statement")
  val ExportStatement       = PsiOutputRoleId("scala.export.statement")
  val ImportExpression      = PsiOutputRoleId("scala.import.expression")
  val ImportSelectorSet     = PsiOutputRoleId("scala.import.selector-set")
  val ImportSelector        = PsiOutputRoleId("scala.import.selector")
  val StableReference       = PsiOutputRoleId("scala.reference.stable")
  val SimpleType            = PsiOutputRoleId("scala.type.simple")
  val SingletonType         = PsiOutputRoleId("scala.type.singleton")
  val TypeProjection        = PsiOutputRoleId("scala.type.projection")
  val LiteralType           = PsiOutputRoleId("scala.type.literal")
  val ParenthesizedType     = PsiOutputRoleId("scala.type.parenthesized")
  val TupleType             = PsiOutputRoleId("scala.type.tuple")
  val TupleTypes            = PsiOutputRoleId("scala.type.tuple.components")
  val NamedTupleType        = PsiOutputRoleId("scala.type.named-tuple")
  val NamedTupleComponent   = PsiOutputRoleId("scala.type.named-tuple.component")
  val FunctionType          = PsiOutputRoleId("scala.type.function")
  val DependentFunctionType = PsiOutputRoleId("scala.type.function.dependent")
  val PolyFunctionType      = PsiOutputRoleId("scala.type.function.polymorphic")
  val IntegerLiteralValue   = PsiOutputRoleId("scala.literal.integer-value")
  val LongLiteralValue      = PsiOutputRoleId("scala.literal.long-value")
  val FloatLiteralValue     = PsiOutputRoleId("scala.literal.float-value")
  val DoubleLiteralValue    = PsiOutputRoleId("scala.literal.double-value")
  val CharLiteralValue      = PsiOutputRoleId("scala.literal.char-value")
  val StringLiteralValue    = PsiOutputRoleId("scala.literal.string-value")
  val BooleanLiteralValue   = PsiOutputRoleId("scala.literal.boolean-value")
  val ParameterizedType     = PsiOutputRoleId("scala.type.parameterized")
  val TypeArguments         = PsiOutputRoleId("scala.type.arguments")
  val NamedTypeArguments    = PsiOutputRoleId("scala.type.arguments.named-compatible")
  val NamedTypeArgument     = PsiOutputRoleId("scala.type.argument.named-compatible")
  val WildcardType          = PsiOutputRoleId("scala.type.wildcard")
  val ContextBound          = PsiOutputRoleId("scala.type.context-bound")
  val TypeLambda            = PsiOutputRoleId("scala.type.lambda")
  val InfixType             = PsiOutputRoleId("scala.type.infix")
  val MatchType             = PsiOutputRoleId("scala.type.match")
  val MatchTypeCases        = PsiOutputRoleId("scala.type.match.cases")
  val MatchTypeCase         = PsiOutputRoleId("scala.type.match.case")
  val MatchTypeVariable     = PsiOutputRoleId("scala.type.match.variable")
  val CompoundType          = PsiOutputRoleId("scala.type.compound")
  val Refinement            = PsiOutputRoleId("scala.type.refinement")
  val AnnotatedType         = PsiOutputRoleId("scala.type.annotated")
  val CaptureType           = PsiOutputRoleId("scala.type.capture")
  val CaptureSet            = PsiOutputRoleId("scala.type.capture-set")
  val CaptureReference      = PsiOutputRoleId("scala.type.capture-reference")
  val CaptureFilter         = PsiOutputRoleId("scala.type.capture-filter")
  val ExpressionPayload     = PsiOutputRoleId("scala.expression.payload")
  val TermReference         = PsiOutputRoleId("scala.expression.reference.term")
  val ThisReference         = PsiOutputRoleId("scala.expression.reference.this")
  val SelectionExpression   = PsiOutputRoleId("scala.expression.selection")
  val GenericCall           = PsiOutputRoleId("scala.expression.type-application.generic-call")
  val MethodCall            = PsiOutputRoleId("scala.expression.application.method-call")
  val ArgumentExpressions   = PsiOutputRoleId("scala.expression.application.arguments")
  val NamedArgument         = PsiOutputRoleId("scala.expression.application.argument.named-compatible")
  val SuperReference        = PsiOutputRoleId("scala.expression.reference.super")
  val IntegerExpression     = PsiOutputRoleId("scala.expression.literal.integer")
  val LongExpression        = PsiOutputRoleId("scala.expression.literal.long")
  val FloatExpression       = PsiOutputRoleId("scala.expression.literal.float")
  val DoubleExpression      = PsiOutputRoleId("scala.expression.literal.double")
  val BooleanExpression     = PsiOutputRoleId("scala.expression.literal.boolean")
  val CharExpression        = PsiOutputRoleId("scala.expression.literal.char")
  val StringExpression      = PsiOutputRoleId("scala.expression.literal.string")
  val NullExpression        = PsiOutputRoleId("scala.expression.literal.null")
  val ModifierList          = PsiOutputRoleId("scala.modifiers")
  val AccessModifier        = PsiOutputRoleId("scala.modifier.access")
  val Annotations           = PsiOutputRoleId("scala.annotations")
  val Annotation            = PsiOutputRoleId("scala.annotation")
  val AnnotationExpr        = PsiOutputRoleId("scala.annotation.expression")
  val ConstructorInvocation = PsiOutputRoleId("scala.annotation.constructor")
  val AnnotationArguments   = PsiOutputRoleId("scala.annotation.arguments")
  val ClassDefinition       = PsiOutputRoleId("scala.definition.class")
  val TraitDefinition       = PsiOutputRoleId("scala.definition.trait")
  val ObjectDefinition      = PsiOutputRoleId("scala.definition.object")
  val EnumDefinition        = PsiOutputRoleId("scala.definition.enum")
  val EnumCases             = PsiOutputRoleId("scala.definition.enum.cases")
  val EnumSingletonCase     = PsiOutputRoleId("scala.definition.enum.case.singleton")
  val EnumClassCase         = PsiOutputRoleId("scala.definition.enum.case.class")
  val ExtendsBlock          = PsiOutputRoleId("scala.template.extends-block")
  val TemplateBody          = PsiOutputRoleId("scala.template.body")
  val PrimaryConstructor    = PsiOutputRoleId("scala.template.constructor.primary")
  val ParameterClauses      = PsiOutputRoleId("scala.template.parameter-clauses")
  val ParameterClause       = PsiOutputRoleId("scala.template.parameter-clause")
  val TypeParameterClause   = PsiOutputRoleId("scala.type-parameter.clause")
  val TypeParameter         = PsiOutputRoleId("scala.type-parameter")
  val Parameter             = PsiOutputRoleId("scala.parameter")
  val ClassParameter        = PsiOutputRoleId("scala.class-parameter")
  val ParameterType         = PsiOutputRoleId("scala.parameter.type")
  val PureParameterType     = PsiOutputRoleId("scala.parameter.type.pure-compatible")
  val TemplateParents       = PsiOutputRoleId("scala.template.parents")
  val SelfType              = PsiOutputRoleId("scala.template.self-type")
  val DerivesClause         = PsiOutputRoleId("scala.template.derives")
  val FunctionDefinition    = PsiOutputRoleId("scala.definition.function")
  val FunctionDeclaration   = PsiOutputRoleId("scala.declaration.function")
  val PatternDefinition     = PsiOutputRoleId("scala.definition.pattern")
  val ValueDeclaration      = PsiOutputRoleId("scala.declaration.value")
  val VariableDefinition    = PsiOutputRoleId("scala.definition.variable")
  val VariableDeclaration   = PsiOutputRoleId("scala.declaration.variable")
  val PatternList           = PsiOutputRoleId("scala.pattern.list")
  val ReferencePattern      = PsiOutputRoleId("scala.pattern.reference")
  val IdentifierList        = PsiOutputRoleId("scala.identifier.list")
  val FieldId               = PsiOutputRoleId("scala.field.id")
  val TypeAliasDeclaration  = PsiOutputRoleId("scala.definition.type-alias-declaration")
  val TypeAliasDefinition   = PsiOutputRoleId("scala.definition.type-alias-definition")
private[metallurgy] final case class StableRoleInventory(
    grammarRoles: Set[GrammarRoleId],
    outputRoles: Set[PsiOutputRoleId]
)
private[metallurgy] object StableRoleInventory:
  val Empty = StableRoleInventory(Set.empty, Set.empty)

  val Reviewed = StableRoleInventory(
    Set(
      GrammarRoleId.CompilationUnit,
      GrammarRoleId.PackageClause,
      GrammarRoleId.PackageReference,
      GrammarRoleId.ImportStatement,
      GrammarRoleId.ExportStatement,
      GrammarRoleId.AbsentProduct,
      GrammarRoleId.StableReference,
      GrammarRoleId.ImportSelector,
      GrammarRoleId.ImportSelectorName,
      GrammarRoleId.SimpleType,
      GrammarRoleId.TypeProjection,
      GrammarRoleId.SingletonType,
      GrammarRoleId.LiteralType,
      GrammarRoleId.ParenthesizedType,
      GrammarRoleId.TupleType,
      GrammarRoleId.NamedTupleType,
      GrammarRoleId.NamedTupleComponent,
      GrammarRoleId.FunctionType,
      GrammarRoleId.DependentFunctionType,
      GrammarRoleId.PolyFunctionType,
      GrammarRoleId.ByNameParameterType,
      GrammarRoleId.RepeatedParameterType,
      GrammarRoleId.RepeatedParameterStar,
      GrammarRoleId.LiteralValue,
      GrammarRoleId.AppliedType,
      GrammarRoleId.TypeArgumentList,
      GrammarRoleId.PositionalTypeArgument,
      GrammarRoleId.NamedTypeArgument,
      GrammarRoleId.ExpressionTypeApply,
      GrammarRoleId.WildcardType,
      GrammarRoleId.TypeBounds,
      GrammarRoleId.ContextBounds,
      GrammarRoleId.ContextBound,
      GrammarRoleId.TypeLambda,
      GrammarRoleId.TermLambda,
      GrammarRoleId.InfixType,
      GrammarRoleId.MatchType,
      GrammarRoleId.MatchTypeCase,
      GrammarRoleId.MatchTypePatternVariable,
      GrammarRoleId.RefinementType,
      GrammarRoleId.AnnotatedType,
      GrammarRoleId.CaptureType,
      GrammarRoleId.CaptureSet,
      GrammarRoleId.CaptureReference,
      GrammarRoleId.CaptureFilter,
      GrammarRoleId.CaptureSynthetic,
      GrammarRoleId.ExpressionPayload,
      GrammarRoleId.TermReference,
      GrammarRoleId.ThisReference,
      GrammarRoleId.QualifiedThisReference,
      GrammarRoleId.SelectionExpression,
      GrammarRoleId.OrdinaryApplication,
      GrammarRoleId.NamedArgument,
      GrammarRoleId.SuperReference,
      GrammarRoleId.SelectionQualifier,
      GrammarRoleId.ExpressionIntegerLiteral,
      GrammarRoleId.ExpressionLongLiteral,
      GrammarRoleId.ExpressionFloatLiteral,
      GrammarRoleId.ExpressionDoubleLiteral,
      GrammarRoleId.ExpressionBooleanLiteral,
      GrammarRoleId.ExpressionCharLiteral,
      GrammarRoleId.ExpressionStringLiteral,
      GrammarRoleId.ExpressionNullLiteral,
      GrammarRoleId.Modifiers,
      GrammarRoleId.AccessModifier,
      GrammarRoleId.KeywordModifier,
      GrammarRoleId.Annotations,
      GrammarRoleId.Annotation,
      GrammarRoleId.AnnotationArguments,
      GrammarRoleId.ClassDefinition,
      GrammarRoleId.TraitDefinition,
      GrammarRoleId.ObjectDefinition,
      GrammarRoleId.EnumDefinition,
      GrammarRoleId.EnumCase,
      GrammarRoleId.Template,
      GrammarRoleId.TemplateConstructor,
      GrammarRoleId.TypeParameterClause,
      GrammarRoleId.UnboundedTypeParameter,
      GrammarRoleId.BoundedTypeParameter,
      GrammarRoleId.HigherKindedTypeParameter,
      GrammarRoleId.TermParameter,
      GrammarRoleId.ClassParameter,
      GrammarRoleId.TemplateSelf,
      GrammarRoleId.TemplateTypeTree,
      GrammarRoleId.FunctionDefinition,
      GrammarRoleId.PropertyDefinition,
      GrammarRoleId.ReferenceBinding,
      GrammarRoleId.TypeAliasDeclaration,
      GrammarRoleId.TypeAliasDefinition,
      GrammarRoleId.InferredTypeAbsence,
      GrammarRoleId.OutputFreeExpression
    ),
    Set(
      PsiOutputRoleId.SourceTerminal,
      PsiOutputRoleId.PackageStatement,
      PsiOutputRoleId.EndStatement,
      PsiOutputRoleId.EndKeyword,
      PsiOutputRoleId.ImportStatement,
      PsiOutputRoleId.ExportStatement,
      PsiOutputRoleId.ImportExpression,
      PsiOutputRoleId.ImportSelectorSet,
      PsiOutputRoleId.ImportSelector,
      PsiOutputRoleId.StableReference,
      PsiOutputRoleId.SimpleType,
      PsiOutputRoleId.SingletonType,
      PsiOutputRoleId.TypeProjection,
      PsiOutputRoleId.LiteralType,
      PsiOutputRoleId.ParenthesizedType,
      PsiOutputRoleId.TupleType,
      PsiOutputRoleId.TupleTypes,
      PsiOutputRoleId.NamedTupleType,
      PsiOutputRoleId.NamedTupleComponent,
      PsiOutputRoleId.FunctionType,
      PsiOutputRoleId.DependentFunctionType,
      PsiOutputRoleId.PolyFunctionType,
      PsiOutputRoleId.IntegerLiteralValue,
      PsiOutputRoleId.LongLiteralValue,
      PsiOutputRoleId.FloatLiteralValue,
      PsiOutputRoleId.DoubleLiteralValue,
      PsiOutputRoleId.CharLiteralValue,
      PsiOutputRoleId.StringLiteralValue,
      PsiOutputRoleId.BooleanLiteralValue,
      PsiOutputRoleId.ParameterizedType,
      PsiOutputRoleId.TypeArguments,
      PsiOutputRoleId.NamedTypeArguments,
      PsiOutputRoleId.NamedTypeArgument,
      PsiOutputRoleId.WildcardType,
      PsiOutputRoleId.ContextBound,
      PsiOutputRoleId.TypeLambda,
      PsiOutputRoleId.InfixType,
      PsiOutputRoleId.MatchType,
      PsiOutputRoleId.MatchTypeCases,
      PsiOutputRoleId.MatchTypeCase,
      PsiOutputRoleId.MatchTypeVariable,
      PsiOutputRoleId.CompoundType,
      PsiOutputRoleId.Refinement,
      PsiOutputRoleId.AnnotatedType,
      PsiOutputRoleId.CaptureType,
      PsiOutputRoleId.CaptureSet,
      PsiOutputRoleId.CaptureReference,
      PsiOutputRoleId.CaptureFilter,
      PsiOutputRoleId.ExpressionPayload,
      PsiOutputRoleId.TermReference,
      PsiOutputRoleId.ThisReference,
      PsiOutputRoleId.SelectionExpression,
      PsiOutputRoleId.GenericCall,
      PsiOutputRoleId.MethodCall,
      PsiOutputRoleId.ArgumentExpressions,
      PsiOutputRoleId.NamedArgument,
      PsiOutputRoleId.SuperReference,
      PsiOutputRoleId.IntegerExpression,
      PsiOutputRoleId.LongExpression,
      PsiOutputRoleId.FloatExpression,
      PsiOutputRoleId.DoubleExpression,
      PsiOutputRoleId.BooleanExpression,
      PsiOutputRoleId.CharExpression,
      PsiOutputRoleId.StringExpression,
      PsiOutputRoleId.NullExpression,
      PsiOutputRoleId.ModifierList,
      PsiOutputRoleId.AccessModifier,
      PsiOutputRoleId.Annotations,
      PsiOutputRoleId.Annotation,
      PsiOutputRoleId.AnnotationExpr,
      PsiOutputRoleId.ConstructorInvocation,
      PsiOutputRoleId.AnnotationArguments,
      PsiOutputRoleId.ClassDefinition,
      PsiOutputRoleId.TraitDefinition,
      PsiOutputRoleId.ObjectDefinition,
      PsiOutputRoleId.EnumDefinition,
      PsiOutputRoleId.EnumCases,
      PsiOutputRoleId.EnumSingletonCase,
      PsiOutputRoleId.EnumClassCase,
      PsiOutputRoleId.ExtendsBlock,
      PsiOutputRoleId.TemplateBody,
      PsiOutputRoleId.PrimaryConstructor,
      PsiOutputRoleId.ParameterClauses,
      PsiOutputRoleId.ParameterClause,
      PsiOutputRoleId.TypeParameterClause,
      PsiOutputRoleId.TypeParameter,
      PsiOutputRoleId.Parameter,
      PsiOutputRoleId.ClassParameter,
      PsiOutputRoleId.ParameterType,
      PsiOutputRoleId.PureParameterType,
      PsiOutputRoleId.TemplateParents,
      PsiOutputRoleId.SelfType,
      PsiOutputRoleId.DerivesClause,
      PsiOutputRoleId.FunctionDefinition,
      PsiOutputRoleId.FunctionDeclaration,
      PsiOutputRoleId.PatternDefinition,
      PsiOutputRoleId.ValueDeclaration,
      PsiOutputRoleId.VariableDefinition,
      PsiOutputRoleId.VariableDeclaration,
      PsiOutputRoleId.PatternList,
      PsiOutputRoleId.ReferencePattern,
      PsiOutputRoleId.IdentifierList,
      PsiOutputRoleId.FieldId,
      PsiOutputRoleId.TypeAliasDeclaration,
      PsiOutputRoleId.TypeAliasDefinition
    )
  )
private[metallurgy] object ImportPersistenceSurfaces:
  val StatementStub         = "org/jetbrains/plugins/scala/lang/psi/stubs/ScImportStmtStub"
  val ExpressionStub        = "org/jetbrains/plugins/scala/lang/psi/stubs/ScImportExprStub"
  val SelectorSetStub       = "org/jetbrains/plugins/scala/lang/psi/stubs/ScImportSelectorsStub"
  val SelectorStub          = "org/jetbrains/plugins/scala/lang/psi/stubs/ScImportSelectorStub"
  val StatementSerializer   =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScImportStmtElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScImportStmtStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val ExpressionSerializer  =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScImportExprElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScImportExprStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val SelectorSetSerializer =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScImportSelectorsElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScImportSelectorsStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val SelectorSerializer    =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScImportSelectorElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScImportSelectorStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val AliasedImportIndex    =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#ALIASED_IMPORT_KEY"
  val SelfNavigation        = "scala.psi.navigation.self"
private[metallurgy] object ExportPersistenceSurfaces:
  val StatementStub        = "org/jetbrains/plugins/scala/lang/psi/stubs/ScExportStmtStub"
  val StatementSerializer  =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScExportStmtElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScExportStmtStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val TopLevelPackageIndex =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#TOP_LEVEL_EXPORT_BY_PKG_KEY"
private[metallurgy] object PackagePersistenceSurfaces:
  val Stub       = "org/jetbrains/plugins/scala/lang/psi/stubs/ScPackagingStub"
  val Serializer =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScPackagingElementType$#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScPackagingStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val FqnIndex   = "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#PACKAGE_FQN_KEY"
private[metallurgy] object ModifierAnnotationPersistenceSurfaces:
  val ModifierStub          = "org/jetbrains/plugins/scala/lang/psi/stubs/ScModifiersStub"
  val ModifierSerializer    =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScModifiersElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScModifiersStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val AccessStub            = "org/jetbrains/plugins/scala/lang/psi/stubs/ScAccessModifierStub"
  val AccessSerializer      =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScAccessModifierElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScAccessModifierStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val AnnotationsStub       = "org/jetbrains/plugins/scala/lang/psi/stubs/ScAnnotationsStub"
  val AnnotationsSerializer =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScStubElementType#serialize(Lcom/intellij/psi/stubs/StubElement;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val AnnotationStub        = "org/jetbrains/plugins/scala/lang/psi/stubs/ScAnnotationStub"
  val AnnotationSerializer  =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScAnnotationElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScAnnotationStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
private[metallurgy] object TemplatePersistenceSurfaces:
  val DefinitionStub                = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScTemplateDefinitionStubImpl"
  val DefinitionSerializer          =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScTemplateDefinitionElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScTemplateDefinitionStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val EnumCasesStub                 = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScEnumCasesStubImpl"
  val ExtendsBlockStub              = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScExtendsBlockStubImpl"
  val ExtendsBlockSerializer        =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScExtendsBlockElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScExtendsBlockStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val TemplateBodyStub              = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScTemplateBodyStubImpl"
  val PrimaryConstructorStub        = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScPrimaryConstructorStubImpl"
  val ParameterClausesStub          = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScParamClausesStubImpl"
  val ParameterClauseStub           = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScParamClauseStubImpl"
  val ParameterStub                 = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScParameterStubImpl"
  val ParameterSerializer           =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/signatures/ScParamElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScParameterStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val TemplateParentsStub           = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScTemplateParentsStubImpl"
  val TemplateParentsSerializer     =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScTemplateParentsElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScTemplateParentsStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val SelfTypeStub                  = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScSelfTypeElementStubImpl"
  val SelfTypeSerializer            =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScSelfTypeElementElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScSelfTypeElementStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val DerivesClauseStub             = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScDerivesClauseStubImpl"
  val TypeParameterClauseStub       = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScTypeParamClauseStubImpl"
  val TypeParameterStub             = "org/jetbrains/plugins/scala/lang/psi/stubs/impl/ScTypeParamStubImpl"
  val TypeParameterClauseSerializer =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScTypeParamClauseElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScTypeParamClauseStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val TypeParameterSerializer       =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScTypeParamElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScTypeParamStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val GenericSerializer             =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScStubElementType#serialize(Lcom/intellij/psi/stubs/StubElement;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val ShortNameIndex                = "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#SHORT_NAME_KEY"
  val ClassFqnIndex                 = "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#CLASS_FQN_KEY"
  val ClassNameInPackageIndex       =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#CLASS_NAME_IN_PACKAGE_KEY"
  val JavaClassShortNameIndex       = "com/intellij/psi/impl/java/stubs/index/JavaStubIndexKeys#CLASS_SHORT_NAMES"
  val JavaClassFqnIndex             = "com/intellij/psi/impl/java/stubs/index/JavaStubIndexKeys#CLASS_FQN"
  val NotVisibleInJavaIndex         =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#NOT_VISIBLE_IN_JAVA_SHORT_NAME_KEY"
  val AllClassNamesIndex            = "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#ALL_CLASS_NAMES"
  val SuperClassNameIndex           =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#SUPER_CLASS_NAME_KEY"
  val SelfTypeClassNameIndex        =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#SELF_TYPE_CLASS_NAME_KEY"
  val JavaClassNameInPackageIndex   =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#JAVA_CLASS_NAME_IN_PACKAGE_KEY"
  val DefinitionIndices             = Vector(
    ShortNameIndex,
    ClassFqnIndex,
    ClassNameInPackageIndex,
    JavaClassShortNameIndex,
    JavaClassFqnIndex,
    NotVisibleInJavaIndex,
    AllClassNamesIndex,
    JavaClassNameInPackageIndex
  )
  val ExternalIds                   = Map(
    PsiOutputRoleId.ClassDefinition     -> "scala.ScClass",
    PsiOutputRoleId.TraitDefinition     -> "scala.ScTrait",
    PsiOutputRoleId.ObjectDefinition    -> "scala.ScObject",
    PsiOutputRoleId.EnumDefinition      -> "scala.ScEnum",
    PsiOutputRoleId.EnumCases           -> "scala.ScEnumCases",
    PsiOutputRoleId.EnumSingletonCase   -> "scala.ScEnumSingletonCase",
    PsiOutputRoleId.EnumClassCase       -> "scala.ScEnumClassCase",
    PsiOutputRoleId.ExtendsBlock        -> "scala.extends block",
    PsiOutputRoleId.TemplateBody        -> "scala.template body",
    PsiOutputRoleId.PrimaryConstructor  -> "scala.primary constructor",
    PsiOutputRoleId.ParameterClauses    -> "scala.parameter clauses",
    PsiOutputRoleId.ParameterClause     -> "scala.parameter clause",
    PsiOutputRoleId.Parameter           -> "scala.parameter",
    PsiOutputRoleId.ClassParameter      -> "scala.class parameter",
    PsiOutputRoleId.TemplateParents     -> "scala.template parents",
    PsiOutputRoleId.SelfType            -> "scala.self type element",
    PsiOutputRoleId.DerivesClause       -> "scala.template derives",
    PsiOutputRoleId.TypeParameterClause -> "scala.type parameter clause",
    PsiOutputRoleId.TypeParameter       -> "scala.type parameter"
  )
private[metallurgy] object DefinitionPersistenceSurfaces:
  val FunctionStub           = "org/jetbrains/plugins/scala/lang/psi/stubs/ScFunctionStub"
  val FunctionSerializer     =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScFunctionElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScFunctionStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val PropertyStub           = "org/jetbrains/plugins/scala/lang/psi/stubs/ScPropertyStub"
  val PropertySerializer     =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScPropertyElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScPropertyStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val PatternListStub        = "org/jetbrains/plugins/scala/lang/psi/stubs/ScPatternListStub"
  val PatternListSerializer  =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScPatternListElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScPatternListStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val BindingStub            = "org/jetbrains/plugins/scala/lang/psi/stubs/ScBindingPatternStub"
  val BindingSerializer      =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScBindingPatternElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScBindingPatternStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val IdentifierListStub     = "org/jetbrains/plugins/scala/lang/psi/stubs/ScIdListStub"
  val FieldIdStub            = "org/jetbrains/plugins/scala/lang/psi/stubs/ScFieldIdStub"
  val FieldIdSerializer      =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScFieldIdElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScFieldIdStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val TypeAliasStub          = "org/jetbrains/plugins/scala/lang/psi/stubs/ScTypeAliasStub"
  val TypeAliasSerializer    =
    "org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScTypeAliasElementType#serialize(Lorg/jetbrains/plugins/scala/lang/psi/stubs/ScTypeAliasStub;Lcom/intellij/psi/stubs/StubOutputStream;)V"
  val MethodNameIndex        = "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#METHOD_NAME_KEY"
  val TopLevelFunctionIndex  =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#TOP_LEVEL_FUNCTION_BY_PKG_KEY"
  val PropertyNameIndex      = "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#PROPERTY_NAME_KEY"
  val TopLevelPropertyIndex  =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#TOP_LEVEL_VAL_OR_VAR_BY_PKG_KEY"
  val TypeAliasNameIndex     = "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#TYPE_ALIAS_NAME_KEY"
  val TopLevelTypeAliasIndex =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#TOP_LEVEL_TYPE_ALIAS_BY_PKG_KEY"
  val ExternalIds            = Map(
    PsiOutputRoleId.FunctionDefinition   -> "scala.function definition",
    PsiOutputRoleId.FunctionDeclaration  -> "scala.function declaration",
    PsiOutputRoleId.PatternDefinition    -> "scala.value definition",
    PsiOutputRoleId.ValueDeclaration     -> "scala.value declaration",
    PsiOutputRoleId.VariableDefinition   -> "scala.variable definition",
    PsiOutputRoleId.VariableDeclaration  -> "scala.variable declaration",
    PsiOutputRoleId.PatternList          -> "scala.pattern list",
    PsiOutputRoleId.ReferencePattern     -> "scala.reference pattern",
    PsiOutputRoleId.IdentifierList       -> "scala.id list",
    PsiOutputRoleId.FieldId              -> "scala.field id",
    PsiOutputRoleId.TypeAliasDeclaration -> "scala.type alias declaration",
    PsiOutputRoleId.TypeAliasDefinition  -> "scala.type alias definition"
  )
private[metallurgy] enum OutputCompositeRealization:
  case Once
  case PerChildRole(roleId: String)
  case AtFirstRepeatedFieldOccurrenceStart(
      fieldName: String,
      valuePattern: CatalogValuePattern,
      opening: ClosedSourceLexicalKind,
      closing: ClosedSourceLexicalKind
  )
  case AcrossRepeatedFieldOccurrences(
      fieldName: String,
      valuePattern: CatalogValuePattern,
      opening: ClosedSourceLexicalKind,
      closing: ClosedSourceLexicalKind
  )
  case PerRepeatedFieldOccurrence(
      fieldName: String,
      valuePattern: CatalogValuePattern,
      opening: ClosedSourceLexicalKind,
      closing: ClosedSourceLexicalKind
  )
private[metallurgy] final case class OutputCompositeDeclaration(
    id: String,
    parentId: Option[String],
    range: OutputRangeDeclaration,
    outputRoleId: PsiOutputRoleId,
    targetSurfaceId: String,
    targetRequirement: TargetRequirement,
    accessors: Vector[AccessorObligation],
    persistence: PersistenceObligations,
    navigation: Option[NavigationObligation],
    ownsStructuralEvidence: Boolean = false,
    requiresCompilerEndMarker: Boolean = false,
    realization: OutputCompositeRealization = OutputCompositeRealization.Once
)
private[metallurgy] final case class LocalOutputCompositeTemplate(
    composites: Vector[OutputCompositeDeclaration],
    childMounts: Map[String, Option[String]],
    childOutputSelections: Map[String, PsiOutputRoleId] = Map.empty
)
private[metallurgy] enum ChildOutcomeExpectation:
  case Production(productionId: String)
  case Realization(realizationId: String)
  case OutputRole(role: PsiOutputRoleId)
  case OutputRoles(roles: Set[PsiOutputRoleId])
  case AnyOf(expectations: Vector[ChildOutcomeExpectation])

  def alternatives: Vector[ChildOutcomeExpectation] = this match
    case AnyOf(expectations) => expectations.flatMap(_.alternatives)
    case expectation         => Vector(expectation)
private[metallurgy] final case class ChildOutcomeCondition(
    roleId: String,
    occurrence: ChildOccurrenceSelector,
    expected: ChildOutcomeExpectation
)
private[metallurgy] enum ChildRootOutcome:
  case One(expected: ChildOutcomeExpectation)
  case All(expected: ChildOutcomeExpectation)
  case AnyReviewed
private[metallurgy] final case class ChildClosureAbsorption(
    roleId: String,
    rootOutcome: ChildRootOutcome,
    retainedRootRoles: Set[PsiOutputRoleId] = Set.empty
)
private[metallurgy] final case class RequiredChildRootOutcome(
    roleId: String,
    rootOutcome: ChildRootOutcome
)
private[metallurgy] enum RealizationChoicePolicy:
  case LocalAssessment
  case AtomicWholePlan
private[metallurgy] final case class RealizationChoice(
    candidateIds: Vector[String],
    fallbackId: String,
    policy: RealizationChoicePolicy = RealizationChoicePolicy.LocalAssessment,
    trialEligibility: Vector[RequiredChildRootOutcome] = Vector.empty
)
private[metallurgy] enum EvidenceCondition:
  case TemplateBodyLayout(present: Boolean)
  case RepeatedFieldOccurrence(fieldName: String, valuePattern: CatalogValuePattern, present: Boolean)
  case RepeatedFieldSize(fieldName: String, minimum: Int, maximum: Option[Int])
  case RepeatedNodeFieldDistinct(repeatedFieldName: String, nodePrefix: String, nodeFieldName: String)
  case RepeatedNodesTrailingPrefix(repeatedFieldName: String, nodePrefix: String)
  case ProductionStartsWith(kind: ClosedSourceLexicalKind, present: Boolean)
  case RuntimeSupplementPositive(fieldName: String, present: Boolean)
  case LeadingBeforeRuntimeTailPresent(repeatedFieldName: String, countFieldName: String, present: Boolean)
  case RootAttachment(attachment: AttachmentEvidence, present: Boolean)
private[metallurgy] final case class OutputRealization(
    id: String,
    conditions: Vector[ChildOutcomeCondition],
    template: LocalOutputCompositeTemplate,
    evidenceConditions: Vector[EvidenceCondition] = Vector.empty,
    childClosureAbsorptions: Vector[ChildClosureAbsorption] = Vector.empty,
    requiredChildRoots: Vector[RequiredChildRootOutcome] = Vector.empty,
    terminalIds: Option[Set[String]] = None
)
private[metallurgy] final case class Scala3PsiProduction(
    id: String,
    grammarRoleId: GrammarRoleId,
    pattern: CompilerProductionPattern,
    dispositions: Vector[FieldDisposition],
    children: Vector[ChildDeclaration],
    terminals: Vector[TerminalDeclaration],
    layouts: Vector[LayoutAlternative],
    recovery: RecoveryPolicy,
    targetSurfaceId: String,
    targetRequirement: TargetRequirement,
    accessors: Vector[AccessorObligation],
    persistence: PersistenceObligations,
    navigation: Option[NavigationObligation] = None,
    outputTemplate: Option[LocalOutputCompositeTemplate] = None,
    outputRealizations: Vector[OutputRealization] = Vector.empty,
    outputRoleId: Option[PsiOutputRoleId],
    additionalGrammarRoleIds: Set[GrammarRoleId] = Set.empty,
    realizationChoice: Option[RealizationChoice] = None
):
  val grammarRoleIds: Set[GrammarRoleId]                                  = additionalGrammarRoleIds + grammarRoleId
  private def defaultOutputTemplate: Option[LocalOutputCompositeTemplate] = outputTemplate.orElse(
    outputRoleId.map(role =>
      LocalOutputCompositeTemplate(
        Vector(
          OutputCompositeDeclaration(
            "self",
            None,
            OutputRangeDeclaration.CompilerPosition,
            role,
            targetSurfaceId,
            targetRequirement,
            accessors,
            persistence,
            navigation
          )
        ),
        children.map(child => child.roleId -> Some("self")).toMap
      )
    )
  )
  def effectiveOutputRealizations: Vector[OutputRealization]              =
    if outputRealizations.nonEmpty then outputRealizations
    else defaultOutputTemplate.toVector.map(OutputRealization("self", Vector.empty, _))
  def effectiveOutputTemplate: LocalOutputCompositeTemplate               = effectiveOutputRealizations.head.template
