package com.hmemcpy.metallurgy.psiproducer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.impl.java.stubs.index.JavaStubIndexKeys
import com.intellij.psi.stubs.*
import com.intellij.psi.{PsiErrorElement, PsiFileFactory, PsiManager}
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.io.AbstractStringEnumerator
import org.jetbrains.plugins.scala.Scala3Language
import org.jetbrains.plugins.scala.lang.lexer.{ScalaTokenType, ScalaTokenTypes}
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType
import org.jetbrains.plugins.scala.lang.psi.api.ScalaElementVisitor
import org.jetbrains.plugins.scala.lang.psi.ScExportsHolder
import org.jetbrains.plugins.scala.lang.psi.api.base.{
  ScAccessModifier,
  ScAnnotation,
  ScAnnotationExpr,
  ScAnnotations,
  ScConstructorInvocation,
  ScEnd,
  ScModifierList,
  ScPrimaryConstructor,
  ScStableCodeReference
}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScEnumCases, ScEnumClassCase, ScEnumSingletonCase}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{
  ScFunctionDeclaration,
  ScFunctionDefinition,
  ScPatternDefinition,
  ScTypeAliasDeclaration,
  ScTypeAliasDefinition,
  ScValueDeclaration,
  ScVariableDeclaration,
  ScVariableDefinition
}
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScReferencePattern
import org.jetbrains.plugins.scala.lang.psi.api.base.ScPatternList
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{
  ScClassParameter,
  ScParameter,
  ScParameterClause,
  ScParameterType,
  ScParameters,
  ScTypeParamClause
}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.{
  ScDerivesClause,
  ScExtendsBlock,
  ScTemplateBody,
  ScTemplateParents
}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScClass, ScEnum, ScObject, ScTrait}
import org.jetbrains.plugins.scala.lang.psi.api.expr.{
  ScAssignment,
  ScExpression,
  ScGenericCall,
  ScMethodCall,
  ScReferenceExpression,
  ScSuperReference,
  ScThisReference,
  ScTypedExpression
}
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.{
  ScBooleanLiteral,
  ScCharLiteral,
  ScDoubleLiteral,
  ScFloatLiteral,
  ScIntegerLiteral,
  ScLongLiteral,
  ScNullLiteral,
  ScStringLiteral
}
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{
  ScAnnotTypeElement,
  ScCompoundTypeElement,
  ScContextBound,
  ScDependentFunctionTypeElement,
  ScFunctionalTypeElement,
  ScInfixTypeElement,
  ScLiteralTypeElement,
  ScMatchTypeElement,
  ScNamedTupleTypeElement,
  ScParameterizedTypeElement,
  ScParenthesisedTypeElement,
  ScPolyFunctionTypeElement,
  ScSelfTypeElement,
  ScSequenceArg,
  ScSimpleTypeElement,
  ScTupleTypeElement,
  ScTypeArgs,
  ScTypeElement,
  ScTypeProjection,
  ScTypeLambdaTypeElement,
  ScTypeVariableTypeElement,
  ScWildcardTypeElement
}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.{ScExportStmt, ScImportStmt}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScPackaging
import org.jetbrains.plugins.scala.lang.psi.stubs.{
  ScAccessModifierStub,
  ScAnnotationStub,
  ScAnnotationsStub,
  ScExtendsBlockStub,
  ScExportStmtStub,
  ScImportExprStub,
  ScImportSelectorStub,
  ScImportSelectorsStub,
  ScImportStmtStub,
  ScModifiersStub,
  ScPackagingStub,
  ScTemplateDefinitionStub
}
import org.jetbrains.plugins.scala.lang.psi.stubs.index.ScalaIndexKeys
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.{
  MetallurgyExpressionPayload,
  MetallurgyNamedArgument,
  MetallurgyNamedTypeArgument,
  MetallurgyParameterType,
  MetallurgyTypeArguments
}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

private[metallurgy] final case class NativeOutputContract(
    targetSurfaceId: String,
    accessors: Vector[AccessorObligation],
    persistence: PersistenceObligations,
    navigation: Option[NavigationObligation]
)

private[metallurgy] final case class NativePsiElementBindings(
    elementTypes: Map[String, IElementType],
    outputRoles: Map[PsiOutputRoleId, IElementType],
    outputSurfaces: Map[PsiOutputRoleId, String],
    surfaceRows: Vector[ScalaPsiSurfaceRow] = Vector.empty,
    outputContracts: Map[PsiOutputRoleId, NativeOutputContract] = Map.empty,
    unavailableRealizations: Set[(String, String)] = Set.empty
):
  def validate(catalog: Scala3PsiProductionCatalog): Either[String, Unit] =
    bind(catalog).map(_ => ())

  def bind(catalog: Scala3PsiProductionCatalog): Either[String, NativePsiElementBindings] =
    val declarations     = catalog.productions
      .flatMap(_.effectiveOutputRealizations.flatMap(_.template.composites))
    val missingBindings  = declarations
      .filter(output => !elementTypes.contains(output.targetSurfaceId))
      .map(output => s"${output.outputRoleId.value}:${output.targetSurfaceId}")
      .distinct
      .sorted
    val mismatches       = catalog.productions
      .flatMap(_.effectiveOutputRealizations.flatMap(_.template.composites))
      .filter(_.targetRequirement == TargetRequirement.Native)
      .filter: output =>
        outputSurfaces.get(output.outputRoleId) != Some(output.targetSurfaceId) ||
          outputRoles.get(output.outputRoleId) != elementTypes.get(output.targetSurfaceId)
      .map(output => s"${output.outputRoleId.value}:${output.targetSurfaceId}")
      .distinct
      .sorted
    val contracts        = declarations
      .groupBy(_.outputRoleId)
      .map: (role, values) =>
        role -> values
          .map(value =>
            NativeOutputContract(value.targetSurfaceId, value.accessors, value.persistence, value.navigation)
          )
          .distinct
    val ambiguous        = contracts.collect:
      case (role, values) if values.size != 1 =>
        s"${role.value}=${values.mkString("[", ",", "]")}"
    val orderedAmbiguous = ambiguous.toVector.sorted
    val roleBindings     = declarations
      .groupBy(_.outputRoleId)
      .flatMap: (role, values) =>
        values.flatMap(value => elementTypes.get(value.targetSurfaceId)).distinct match
          case Vector(value) => Some(role -> value)
          case _             => None
    if missingBindings.nonEmpty then
      Left(s"output roles have no element-type binding: ${missingBindings.mkString(", ")}")
    else if roleBindings.size != declarations.map(_.outputRoleId).distinct.size then
      Left("output roles resolve to inconsistent element-type bindings")
    else if mismatches.nonEmpty then Left(s"native output-role bindings are inconsistent: ${mismatches.mkString(", ")}")
    else if orderedAmbiguous.nonEmpty then
      Left(s"native output-role contracts are inconsistent: ${orderedAmbiguous.mkString(", ")}")
    else
      Right(
        copy(
          outputRoles = outputRoles ++ roleBindings,
          outputContracts = contracts.view.mapValues(_.head).toMap
        )
      )

private[metallurgy] object NativePsiElementBindings:
  private final case class NativeNamedTypeArguments(
      list: ScTypeArgs,
      entry: com.intellij.psi.PsiElement
  )

  val EndKeywordTokenSurface               = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#EndKeyword"
  val ImportWildcardTokenSurface           = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#WildcardStar"
  val ImportLegacyWildcardTokenSurface     = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tUNDER"
  val ImportAliasAsTokenSurface            = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#ImportAliasAs"
  val ImportAliasArrowTokenSurface         = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#ImportAliasArrow"
  val TypeArgumentLeftTokenSurface         = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tLSQBRACKET"
  val TypeArgumentRightTokenSurface        = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tRSQBRACKET"
  val WildcardQuestionTokenSurface         =
    "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#WildcardTypeQuestionMark"
  val LowerTypeBoundTokenSurface           = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tLOWER_BOUND"
  val UpperTypeBoundTokenSurface           = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tUPPER_BOUND"
  val VarianceTokenSurface                 = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tIDENTIFIER"
  val ContextBoundColonTokenSurface        = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tCOLON"
  val ContextBoundLeftBraceTokenSurface    = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tLBRACE"
  val ContextBoundRightBraceTokenSurface   = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tRBRACE"
  val ContextBoundCommaTokenSurface        = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tCOMMA"
  val ContextBoundAsTokenSurface           = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#AsKeyword"
  val AssignmentTokenSurface               = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tASSIGN"
  val ValueKeywordTokenSurface             = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#kVAL"
  val IntegerLiteralTokenSurface           = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#Integer"
  val LongLiteralTokenSurface              = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#Long"
  val FloatLiteralTokenSurface             = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#Float"
  val DoubleLiteralTokenSurface            = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#Double"
  val CharLiteralTokenSurface              = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tCHAR"
  val StringLiteralTokenSurface            = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tSTRING"
  val TypePathDotTokenSurface              = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tDOT"
  val TypeProjectionHashTokenSurface       = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tINNER_CLASS"
  val SingletonTypeKeywordTokenSurface     = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#kTYPE"
  val TypeLeftParenthesisTokenSurface      = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tLPARENTHESIS"
  val TypeRightParenthesisTokenSurface     = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tRPARENTHESIS"
  val TypeCommaTokenSurface                = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tCOMMA"
  val UsingKeywordTokenSurface             = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#UsingKeyword"
  val TypeColonTokenSurface                = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tCOLON"
  val FunctionArrowTokenSurface            = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tFUNTYPE"
  val ContextFunctionArrowTokenSurface     = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#ImplicitFunctionArrow"
  val PureFunctionArrowTokenSurface        = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#PureFunctionArrow"
  val ContextPureFunctionArrowTokenSurface =
    "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#ImplicitPureFunctionArrow"
  val RepeatedParameterStarTokenSurface    = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tIDENTIFIER"
  val MatchKeywordTokenSurface             = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#kMATCH"
  val CaseKeywordTokenSurface              = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#kCASE"
  val SemicolonTokenSurface                = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tSEMICOLON"
  val CaptureOperatorTokenSurface          = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#CaptureOperator"
  val CaptureReachTokenSurface             = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#ReachCapabilityStar"
  val CaptureReadOnlyTokenSurface          =
    "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#ReadOnlyCapabilityKeyword"
  val AnnotatedMemberIndexSurface          =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#ANNOTATED_MEMBER_KEY"
  val ModifierKeywordSurfaceIds            = Map(
    "Abstract"    -> "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#kABSTRACT",
    "Final"       -> "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#kFINAL",
    "Sealed"      -> "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#kSEALED",
    "Implicit"    -> "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#kIMPLICIT",
    "Lazy"        -> "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#kLAZY",
    "Override"    -> "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#kOVERRIDE",
    "Var"         -> "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#kVAR",
    "Transparent" -> "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#TransparentKeyword",
    "Inline"      -> "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#InlineKeyword",
    "Infix"       -> "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#InfixKeyword",
    "Open"        -> "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#OpenKeyword",
    "Opaque"      -> "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#OpaqueKeyword",
    "Given"       -> "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#GivenKeyword"
  )
  val AccessModifierKeywordSurfaceIds      = Map(
    "Private"   -> "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#kPRIVATE",
    "Protected" -> "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#kPROTECTED"
  )
  private val ModifierTokenSurfaceIds      = ModifierKeywordSurfaceIds ++ AccessModifierKeywordSurfaceIds

  def probe(project: Project): Either[String, NativePsiElementBindings] =
    if ApplicationManager.getApplication.isReadAccessAllowed then probeInReadAction(project)
    else
      ApplicationManager.getApplication.runReadAction(
        new Computable[Either[String, NativePsiElementBindings]]:
          override def compute(): Either[String, NativePsiElementBindings] = probeInReadAction(project)
      )

  private def probeInReadAction(project: Project): Either[String, NativePsiElementBindings] =
    val file                                                   = PsiFileFactory
      .getInstance(project)
      .createFileFromText(
        "NativeBindingProbe.scala",
        Scala3Language.INSTANCE,
        """package example.syntax
          |import alpha.beta.Member
          |import alpha.beta.*
          |import alpha.beta.{Original as Renamed, given Bound, *}
          |import alpha.beta.{Original => Renamed}
          |import alpha.beta.given Ordering[Int]
          |import alpha.beta.given alpha.gamma.Bound
          |import alpha.beta.given alpha.gamma.Box[? >: Lower <: Upper]
          |import alpha.beta.given Left | Middle & Right
          |import alpha.beta.`back-tick`
          |import alpha.beta._
          |export alpha.beta.{Original as Exported, given Bound, *}
          |class scope
          |@ann @pkg.ann @deprecated("m", "1")
          |private[scope] abstract class AnnotatedProbe:
          |  protected[this] final inline def member = 1
          |class EmptyConstructor()
          |class Generic[+A, -B, C]()
          |trait BoundsProbe[+D >: Lower <: Upper, F[_], G: Bound]
          |type LambdaProbe = [X >: Lower <: Upper] =>> List[X]
          |trait TraitProbe
          |object ObjectProbe
          |enum EnumProbe derives CanEqual:
          |  case Singleton
          |  case ClassCase()
          |val probe = 1
          |def directFunction = 1
          |val directValue = 1
          |var directVariable = 1
          |val atomicReference = singletonValue
          |val atomicInteger = 0x7f_ff
          |val atomicLong = 9_223L
          |val atomicFloat = 1.25f
          |val atomicDouble = 2.5d
          |val atomicBoolean = true
          |val atomicChar = '\n'
          |val atomicString = "a\tb"
          |val atomicNull = null
          |def nativeGeneric[A, B]: A = ???
          |val nativeGenericCall = nativeGeneric[Int, List[String]]
          |val nativeCall = atomicReference.toString(atomicInteger, atomicReference)
          |def nativeNamed(first: Int, second: String) = second
          |val nativeNamedCall = nativeNamed(first = atomicInteger, second = atomicString)
          |def nativeUsing(using first: Int, second: Int) = first + second
          |val nativeUsingCall = nativeUsing(using atomicInteger, atomicReference)
          |def nativeRepeated(values: Int*): Int = values.sum
          |val nativeInts = Seq(1)
          |val nativeRepeatedCall = nativeRepeated(atomicInteger, nativeInts*)
          |class AtomicThisProbe:
          |  def unqualifiedThis = this
          |  def qualifiedThis = AtomicThisProbe.this
          |trait AtomicSelectionMixin:
          |  def member = 1
          |class AtomicSelectionParent:
          |  def member = 1
          |class AtomicSelectionProbe(val source: AtomicSelectionParent) extends AtomicSelectionParent, AtomicSelectionMixin:
          |  override def member = 2
          |  def selected = source.member
          |  def chained = source.toString.length
          |  def selectedThis = this.member
          |  def selectedQualifiedThis = AtomicSelectionProbe.this.member
          |  def selectedSuper = super.member
          |  def selectedMixinSuper = super[AtomicSelectionMixin].member
          |  class Nested:
          |    def selectedOuterSuper = AtomicSelectionProbe.super.member
          |    def selectedOuterMixinSuper = AtomicSelectionProbe.super[AtomicSelectionMixin].member
          |trait DefinitionOwner extends TraitProbe:
          |  self: TraitProbe =>
          |  type DirectAbstract
          |  type DirectAlias = TraitProbe
          |  def declaredFunction: TraitProbe
          |  val declaredValue: TraitProbe
          |  var declaredVariable: TraitProbe
          |  def typedFunction(value: TraitProbe): TraitProbe = value
          |class TypedParameters(value: TraitProbe)
          |val singletonValue = 1
          |type TypePathProbe = alpha.gamma.Bound
          |type TypeProjectionProbe = DefinitionOwner#DirectAbstract
          |type SingletonTypeProbe = singletonValue.type
          |type IntegerLiteralTypeProbe = 42
          |type LongLiteralTypeProbe = 1L
          |type FloatLiteralTypeProbe = 1.0f
          |type DoubleLiteralTypeProbe = 1.0
          |type CharLiteralTypeProbe = 'a'
          |type StringLiteralTypeProbe = "literal"
          |type BooleanLiteralTypeProbe = true
          |type ParenthesizedTypeProbe = (alpha.gamma.Bound)
          |type TupleTypeProbe = (Lower, Upper)
          |type NamedTupleTypeProbe = (lower: Lower, upper: Upper)
          |type FunctionTypeProbe = (Lower, Upper) => Bound
          |type ContextFunctionTypeProbe = Bound ?=> Lower
          |type DependentFunctionTypeProbe = (value: Lower) => value.type
          |type PolyFunctionTypeProbe = [A] => A => A
          |type MatchTypeProbe[X] = X match { case Array[t] => t; case _ => Nothing }
          |type RefinementTypeProbe = AnyRef { type RefinedElem; val refinedValue: RefinedElem; def refinedCurrent: RefinedElem }
          |type AnnotatedTypeProbe = TraitProbe @unchecked
          |def ParameterTypeProbe(thunk: => Lower, values: Upper*): Lower = thunk
          |""".stripMargin
      )
    val packageLayoutFile                                      = PsiFileFactory
      .getInstance(project)
      .createFileFromText(
        "NativePackageLayoutBindingProbe.scala",
        Scala3Language.INSTANCE,
        """package braced {
          |  import alpha.braced.Member
          |}
          |package outer:
          |  package inner:
          |    export alpha.inner.Member
          |  end inner
          |end outer
          |""".stripMargin
      )
    val packaging                                              = PsiTreeUtil.findChildOfType(file, classOf[ScPackaging])
    val layoutPackagings                                       = PsiTreeUtil
      .findChildrenOfType(packageLayoutFile, classOf[ScPackaging])
      .asScala
      .toVector
    val bracedPackaging                                        = layoutPackagings.find(_.fullPackageName == "braced").orNull
    val outerPackaging                                         = layoutPackagings.find(_.fullPackageName == "outer").orNull
    val innerPackaging                                         = layoutPackagings.find(_.fullPackageName == "outer.inner").orNull
    val layoutEnds                                             = PsiTreeUtil.findChildrenOfType(packageLayoutFile, classOf[ScEnd]).asScala.toVector
    val innerEnd                                               = layoutEnds.find(_.getName == "inner").orNull
    val outerEnd                                               = layoutEnds.find(_.getName == "outer").orNull
    val bracedImport                                           = Option(bracedPackaging)
      .flatMap(value => Option(PsiTreeUtil.findChildOfType(value, classOf[ScImportStmt])))
      .orNull
    val innerExport                                            = Option(innerPackaging)
      .flatMap(value => Option(PsiTreeUtil.findChildOfType(value, classOf[ScExportStmt])))
      .orNull
    val reference                                              = Option(packaging).flatMap(_.reference).orNull
    val qualifier                                              = Option(reference).flatMap(_.qualifier).orNull
    val statements                                             = PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).asScala.toVector
    val expressions                                            = statements.flatMap(_.importExprs)
    val selectorSets                                           = expressions.flatMap(_.selectorSet)
    val selectors                                              = selectorSets.flatMap(_.selectors)
    val exportStatements                                       = PsiTreeUtil.findChildrenOfType(file, classOf[ScExportStmt]).asScala.toVector
    val exportExpressions                                      = exportStatements.flatMap(_.importExprs)
    val exportSelectorSets                                     = exportExpressions.flatMap(_.selectorSet)
    val exportSelectors                                        = exportSelectorSets.flatMap(_.selectors)
    val aliasSelectors                                         = selectors.filter(_.isAliasedImport)
    val aliasAsElement                                         = aliasSelectors.headOption
      .flatMap(selector => leafAtText(selector, "as"))
      .orNull
    val aliasArrowElement                                      = aliasSelectors
      .lift(1)
      .flatMap(selector => leafAtText(selector, "=>"))
      .orNull
    val givenSelector                                          = selectors.find(_.isGivenSelector)
    val wildcardSelector                                       = selectors.find(_.isWildcardSelector)
    val wildcardElement                                        = wildcardSelector.flatMap(_.wildcardElement).orNull
    val legacyWildcardElement                                  = expressions.lastOption.flatMap(_.wildcardElement).orNull
    val givenType                                              = givenSelector.flatMap(_.givenTypeElement).orNull
    val parameterizedType                                      = PsiTreeUtil.findChildOfType(file, classOf[ScParameterizedTypeElement])
    val typeArguments                                          = Option(parameterizedType).map(_.typeArgList).orNull
    val leftTypeBracket                                        = Option(typeArguments).flatMap(leafAtText(_, "[")).orNull
    val rightTypeBracket                                       = Option(typeArguments).flatMap(leafAtText(_, "]")).orNull
    val parameterizedBase                                      = Option(parameterizedType).map(_.typeElement).orNull
    val parameterizedArgs                                      = Option(typeArguments).map(_.typeArgs.toVector).getOrElse(Vector.empty)
    val givenReference                                         = Option(givenType)
      .flatMap(value => Option(PsiTreeUtil.findChildOfType(value, classOf[ScStableCodeReference])))
      .orNull
    val qualifiedType                                          = selectors
      .flatMap(_.givenTypeElement)
      .collectFirst { case value: ScSimpleTypeElement if value.getText == "alpha.gamma.Bound" => value }
      .orNull
    val qualifiedReference                                     = Option(qualifiedType).flatMap(_.reference).orNull
    val qualifiedQualifier                                     = Option(qualifiedReference).flatMap(_.qualifier).orNull
    val wildcardType                                           = PsiTreeUtil.findChildOfType(file, classOf[ScWildcardTypeElement])
    val wildcardLower                                          = Option(wildcardType).flatMap(_.lowerTypeElement).orNull
    val wildcardUpper                                          = Option(wildcardType).flatMap(_.upperTypeElement).orNull
    val contextBound                                           = PsiTreeUtil.findChildOfType(file, classOf[ScContextBound])
    val typeLambda                                             = PsiTreeUtil.findChildOfType(file, classOf[ScTypeLambdaTypeElement])
    val wildcardQuestion                                       = Option(wildcardType).flatMap(leafAtText(_, "?")).orNull
    val lowerBoundToken                                        = Option(wildcardType).flatMap(leafAtText(_, ">:")).orNull
    val upperBoundToken                                        = Option(wildcardType).flatMap(leafAtText(_, "<:")).orNull
    val infixType                                              = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScInfixTypeElement])
      .asScala
      .find(_.getText == "Left | Middle & Right")
      .orNull
    val nestedInfixType                                        =
      Option(infixType).flatMap(_.rightOption).collect { case value: ScInfixTypeElement => value }.orNull
    val infixLeft                                              = Option(infixType).map(_.left).orNull
    val infixRight                                             = Option(infixType).flatMap(_.rightOption).orNull
    val infixOperation                                         = Option(infixType).map(_.operation).orNull
    val integerLiteral                                         = PsiTreeUtil.findChildOfType(file, classOf[ScIntegerLiteral])
    val typeProjection                                         = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScTypeProjection])
      .asScala
      .find(_.getText == "DefinitionOwner#DirectAbstract")
      .orNull
    val literalTypes                                           = PsiTreeUtil.findChildrenOfType(file, classOf[ScLiteralTypeElement]).asScala.toVector
    val integerLiteralType                                     = literalTypes.find(_.getText == "42").orNull
    val longLiteralType                                        = literalTypes.find(_.getText == "1L").orNull
    val floatLiteralType                                       = literalTypes.find(_.getText == "1.0f").orNull
    val doubleLiteralType                                      = literalTypes.find(_.getText == "1.0").orNull
    val charLiteralType                                        = literalTypes.find(_.getText == "'a'").orNull
    val stringLiteralType                                      = literalTypes.find(_.getText == "\"literal\"").orNull
    val booleanLiteralType                                     = literalTypes.find(_.getText == "true").orNull
    val integerLiteralValue                                    =
      Option(integerLiteralType).map(_.getLiteral).collect { case value: ScIntegerLiteral => value }.orNull
    val longLiteralValue                                       =
      Option(longLiteralType).map(_.getLiteral).collect { case value: ScLongLiteral => value }.orNull
    val floatLiteralValue                                      =
      Option(floatLiteralType).map(_.getLiteral).collect { case value: ScFloatLiteral => value }.orNull
    val doubleLiteralValue                                     =
      Option(doubleLiteralType).map(_.getLiteral).collect { case value: ScDoubleLiteral => value }.orNull
    val charLiteralValue                                       =
      Option(charLiteralType).map(_.getLiteral).collect { case value: ScCharLiteral => value }.orNull
    val stringLiteralValue                                     =
      Option(stringLiteralType).map(_.getLiteral).collect { case value: ScStringLiteral => value }.orNull
    val booleanLiteralValue                                    =
      Option(booleanLiteralType).map(_.getLiteral).collect { case value: ScBooleanLiteral => value }.orNull
    val parenthesizedType                                      = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScParenthesisedTypeElement])
      .asScala
      .find(_.getText == "(alpha.gamma.Bound)")
      .orNull
    val tupleType                                              = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScTupleTypeElement])
      .asScala
      .find(_.getText == "(Lower, Upper)")
      .orNull
    val tupleTypes                                             = Option(tupleType).map(_.typeList).orNull
    val namedTupleType                                         = PsiTreeUtil.findChildOfType(file, classOf[ScNamedTupleTypeElement])
    val namedTupleComponents                                   = Option(namedTupleType).map(_.components.toVector).getOrElse(Vector.empty)
    val functionalTypes                                        = PsiTreeUtil.findChildrenOfType(file, classOf[ScFunctionalTypeElement]).asScala.toVector
    val functionType                                           = functionalTypes.find(_.getText == "(Lower, Upper) => Bound").orNull
    val contextFunctionType                                    = functionalTypes.find(_.getText == "Bound ?=> Lower").orNull
    val dependentFunctionType                                  = PsiTreeUtil.findChildOfType(file, classOf[ScDependentFunctionTypeElement])
    val polyFunctionType                                       = PsiTreeUtil.findChildOfType(file, classOf[ScPolyFunctionTypeElement])
    val matchType                                              = PsiTreeUtil.findChildOfType(file, classOf[ScMatchTypeElement])
    val matchTypeCases                                         = Option(matchType).flatMap(_.cases).orNull
    val matchCases                                             = Option(matchTypeCases).map(_.cases.toVector).getOrElse(Vector.empty)
    val matchTypeVariable                                      = matchCases.headOption
      .flatMap(_.pattern)
      .toVector
      .flatMap(value => PsiTreeUtil.findChildrenOfType(value, classOf[ScTypeVariableTypeElement]).asScala)
      .headOption
      .orNull
    val matchKeyword                                           = Option(matchType).flatMap(leafAtText(_, "match")).orNull
    val caseKeyword                                            = matchCases.headOption.flatMap(leafAtText(_, "case")).orNull
    val matchCaseArrow                                         = matchCases.headOption.flatMap(leafAtText(_, "=>")).orNull
    val matchCaseSemicolon                                     = Option(matchTypeCases).flatMap(leafAtText(_, ";")).orNull
    val compoundType                                           = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScCompoundTypeElement])
      .asScala
      .find(_.getText.startsWith("AnyRef { type RefinedElem"))
      .orNull
    val refinement                                             = Option(compoundType).flatMap(_.refinement).orNull
    val annotatedType                                          = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScAnnotTypeElement])
      .asScala
      .find(_.getText == "TraitProbe @unchecked")
      .orNull
    val typeAnnotationContainer                                = Option(annotatedType)
      .flatMap(value => Option(PsiTreeUtil.findChildOfType(value, classOf[ScAnnotations])))
      .orNull
    val typeAnnotation                                         = Option(typeAnnotationContainer)
      .flatMap(value => Option(PsiTreeUtil.findChildOfType(value, classOf[ScAnnotation])))
      .orNull
    val ordinaryFunctionArrow                                  = Option(functionType).flatMap(leafAtText(_, "=>")).orNull
    val contextFunctionArrow                                   = Option(contextFunctionType).flatMap(leafAtText(_, "?=>")).orNull
    val singletonType                                          = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScSimpleTypeElement])
      .asScala
      .find(_.getText == "singletonValue.type")
      .orNull
    val modifierLists                                          = PsiTreeUtil.findChildrenOfType(file, classOf[ScModifierList]).asScala.toVector
    val annotatedModifiers                                     = modifierLists.find(_.getText == "private[scope] abstract").orNull
    val memberModifiers                                        = modifierLists.find(_.getText.contains("protected[this]")).orNull
    val accessModifiers                                        = PsiTreeUtil.findChildrenOfType(file, classOf[ScAccessModifier]).asScala.toVector
    val annotationsContainers                                  = PsiTreeUtil.findChildrenOfType(file, classOf[ScAnnotations]).asScala.toVector
    val annotationContainer                                    = annotationsContainers.find(_.getText.startsWith("@ann")).orNull
    val annotations                                            = Option(annotationContainer).toVector.flatMap(_.getAnnotations)
    val annotationExpressions                                  =
      annotations.flatMap(value => PsiTreeUtil.findChildrenOfType(value, classOf[ScAnnotationExpr]).asScala)
    val deprecatedAnnotation                                   = annotations.find(_.getText == "@deprecated(\"m\", \"1\")").orNull
    val constructorInvocations                                 = annotationExpressions.flatMap(value =>
      PsiTreeUtil.findChildrenOfType(value, classOf[ScConstructorInvocation]).asScala
    )
    val argumentLists                                          = constructorInvocations.flatMap(_.args)
    val classes                                                = PsiTreeUtil.findChildrenOfType(file, classOf[ScClass]).asScala.toVector
    val traits                                                 = PsiTreeUtil.findChildrenOfType(file, classOf[ScTrait]).asScala.toVector
    val objects                                                = PsiTreeUtil.findChildrenOfType(file, classOf[ScObject]).asScala.toVector
    val enums                                                  = PsiTreeUtil.findChildrenOfType(file, classOf[ScEnum]).asScala.toVector
    val enumCases                                              = PsiTreeUtil.findChildrenOfType(file, classOf[ScEnumCases]).asScala.toVector
    val enumSingletonCases                                     = PsiTreeUtil.findChildrenOfType(file, classOf[ScEnumSingletonCase]).asScala.toVector
    val enumClassCases                                         = PsiTreeUtil.findChildrenOfType(file, classOf[ScEnumClassCase]).asScala.toVector
    val extendsBlocks                                          = PsiTreeUtil.findChildrenOfType(file, classOf[ScExtendsBlock]).asScala.toVector
    val templateBodies                                         = PsiTreeUtil.findChildrenOfType(file, classOf[ScTemplateBody]).asScala.toVector
    val primaryConstructors                                    = PsiTreeUtil.findChildrenOfType(file, classOf[ScPrimaryConstructor]).asScala.toVector
    val parameterClauses                                       = PsiTreeUtil.findChildrenOfType(file, classOf[ScParameters]).asScala.toVector
    val parameterClause                                        = PsiTreeUtil.findChildrenOfType(file, classOf[ScParameterClause]).asScala.toVector
    val typeParameterClauses                                   = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScTypeParamClause])
      .asScala
      .filter(_.getText == "[+A, -B, C]")
      .toVector
    val typeParameters                                         = typeParameterClauses.flatMap(_.typeParameters)
    val boundsParameters                                       = traits.find(_.name == "BoundsProbe").toVector.flatMap(_.typeParameters)
    val parameters                                             = PsiTreeUtil.findChildrenOfType(file, classOf[ScParameter]).asScala.toVector
    val classParameters                                        = PsiTreeUtil.findChildrenOfType(file, classOf[ScClassParameter]).asScala.toVector
    val parameterTypes                                         = PsiTreeUtil.findChildrenOfType(file, classOf[ScParameterType]).asScala.toVector
    val templateParents                                        = PsiTreeUtil.findChildrenOfType(file, classOf[ScTemplateParents]).asScala.toVector
    val selfTypes                                              = PsiTreeUtil.findChildrenOfType(file, classOf[ScSelfTypeElement]).asScala.toVector
    val derivesClauses                                         = PsiTreeUtil.findChildrenOfType(file, classOf[ScDerivesClause]).asScala.toVector
    val functionDefinitions                                    = PsiTreeUtil.findChildrenOfType(file, classOf[ScFunctionDefinition]).asScala.toVector
    val functionDeclarations                                   = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunctionDeclaration])
      .asScala
      .filter(_.name == "declaredFunction")
      .toVector
    val patternDefinitions                                     = PsiTreeUtil.findChildrenOfType(file, classOf[ScPatternDefinition]).asScala.toVector
    def patternExpression(name: String): Option[ScExpression]  = patternDefinitions
      .find(_.bindings.exists(_.name == name))
      .flatMap(_.expr)
    def functionExpression(name: String): Option[ScExpression] = functionDefinitions
      .find(_.name == name)
      .flatMap(_.body)
    val atomicReference                                        = patternExpression("atomicReference").collect:
      case value: ScReferenceExpression => value
    val atomicUnqualifiedThis                                  = functionExpression("unqualifiedThis").collect:
      case value: ScThisReference => value
    val atomicQualifiedThis                                    = functionExpression("qualifiedThis").collect:
      case value: ScThisReference => value
    val selectionReferences                                    = Vector(
      "selected",
      "chained",
      "selectedThis",
      "selectedQualifiedThis",
      "selectedSuper",
      "selectedMixinSuper",
      "selectedOuterSuper",
      "selectedOuterMixinSuper"
    ).flatMap(name => functionExpression(name).collect { case value: ScReferenceExpression => value })
    val selectionSuperReferences                               = selectionReferences.flatMap(_.qualifier.collect:
      case value: ScSuperReference => value
    )
    val atomicInteger                                          = patternExpression("atomicInteger").collect:
      case value: ScIntegerLiteral => value
    val atomicLong                                             = patternExpression("atomicLong").collect:
      case value: ScLongLiteral => value
    val atomicFloat                                            = patternExpression("atomicFloat").collect:
      case value: ScFloatLiteral => value
    val atomicDouble                                           = patternExpression("atomicDouble").collect:
      case value: ScDoubleLiteral => value
    val atomicBoolean                                          = patternExpression("atomicBoolean").collect:
      case value: ScBooleanLiteral => value
    val atomicChar                                             = patternExpression("atomicChar").collect:
      case value: ScCharLiteral => value
    val atomicString                                           = patternExpression("atomicString").collect:
      case value: ScStringLiteral => value
    val atomicNull                                             = patternExpression("atomicNull").collect:
      case value: ScNullLiteral => value
    val nativeCall                                             = patternExpression("nativeCall").collect:
      case value: ScMethodCall => value
    val nativeArguments                                        = nativeCall.map(_.args)
    val nativeNamedCall                                        = patternExpression("nativeNamedCall").collect:
      case value: ScMethodCall => value
    val nativeNamedArguments                                   = nativeNamedCall.map(_.args)
    val nativeAssignments                                      = nativeNamedArguments.toVector.flatMap(_.exprs.collect:
      case value: ScAssignment => value
    )
    val nativeNamedAssignmentContract                          =
      try
        nativeNamedCall.nonEmpty && nativeNamedArguments.exists(arguments =>
          arguments.getText == "(first = atomicInteger, second = atomicString)" &&
            arguments.exprs.toVector == nativeAssignments && arguments.getArgsCount == 2 &&
            arguments.isArgsInParens && !arguments.isUsing
        ) && nativeAssignments.size == 2 &&
          nativeAssignments
            .zip(Vector("first" -> "atomicInteger", "second" -> "atomicString"))
            .forall:
              case (assignment, (name, value)) =>
                var visited = false
                assignment.accept(
                  new ScalaElementVisitor:
                    override def visitAssignment(current: ScAssignment): Unit = visited = current eq assignment
                )
                val token   = assignment.assignmentToken
                assignment.getParent == nativeNamedArguments.get && assignment.leftExpression.getParent == assignment &&
                assignment.rightExpression
                  .exists(_.getParent == assignment) && assignment.referenceName.contains(name) &&
                assignment.leftExpression.getText == name && assignment.rightExpression.exists(_.getText == value) &&
                assignment.isNamedParameter && token.exists(element =>
                  element.isPhysical && element.getParent == assignment && element.getText == "=" &&
                    element.getNode.getElementType == ScalaTokenTypes.tASSIGN
                ) && visited
      catch case NonFatal(_) => false
    val nativeGenericCall                                      = patternExpression("nativeGenericCall").collect:
      case value: ScGenericCall => value
    val nativeUsingCall                                        = patternExpression("nativeUsingCall").collect:
      case value: ScMethodCall => value
    val nativeUsingArguments                                   = nativeUsingCall.map(_.args)
    val nativeRepeatedCall                                     = patternExpression("nativeRepeatedCall").collect:
      case value: ScMethodCall => value
    val nativeRepeatedArguments                                = nativeRepeatedCall.map(_.args)
    val nativeRepeatedTyped                                    = nativeRepeatedArguments.toVector.flatMap: arguments =>
      arguments.exprs.collect:
        case typed: ScTypedExpression if typed.isSequenceArg => typed
    val nativeRepeatedSequence                                 = nativeRepeatedTyped.headOption.flatMap: typed =>
      Option(typed.getLastChild).collect:
        case sequence: ScSequenceArg => sequence
    val nativeRepeatedContract                                 =
      try
        nativeRepeatedCall.nonEmpty && nativeRepeatedArguments.exists(arguments =>
          arguments.getText == "(atomicInteger, nativeInts*)" && arguments.exprs.size == 2 && !arguments.isUsing
        ) && nativeRepeatedTyped.size == 1 &&
          nativeRepeatedTyped.forall: typed =>
            typed.expr.getText == "nativeInts" && typed.typeElement.isEmpty && typed.isSequenceArg &&
              !typed.hasAnnotation && typed.annotations.isEmpty && typed.getParent == nativeRepeatedArguments.get &&
              nativeRepeatedSequence.exists: sequence =>
                sequence.getText == "*" && sequence.getParent == typed && (typed.getLastChild eq sequence)
      catch case NonFatal(_) => false
    val nativeUsingKeyword                                     = nativeUsingArguments.toVector
      .flatMap(_.getNode.getChildren(null))
      .filter(_.getElementType == ScalaTokenType.UsingKeyword)
    val atomicLiterals                                         = Vector(
      atomicInteger,
      atomicLong,
      atomicFloat,
      atomicDouble,
      atomicBoolean,
      atomicChar,
      atomicString,
      atomicNull
    ).flatten
    val valueDeclarations                                      = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScValueDeclaration])
      .asScala
      .filter(_.declaredElements.exists(_.getName == "declaredValue"))
      .toVector
    val variableDefinitions                                    = PsiTreeUtil.findChildrenOfType(file, classOf[ScVariableDefinition]).asScala.toVector
    val variableDeclarations                                   = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScVariableDeclaration])
      .asScala
      .filter(_.declaredElements.exists(_.getName == "declaredVariable"))
      .toVector
    val typeAliasDeclarations                                  = PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeAliasDeclaration]).asScala.toVector
    val typeAliasDefinitions                                   = PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeAliasDefinition]).asScala.toVector
    val patternLists                                           = PsiTreeUtil.findChildrenOfType(file, classOf[ScPatternList]).asScala.toVector
    val referencePatterns                                      = PsiTreeUtil.findChildrenOfType(file, classOf[ScReferencePattern]).asScala.toVector
    val identifierLists                                        = (valueDeclarations.map(_.getIdList) ++ variableDeclarations.map(_.getIdList)).toVector
    val fieldIds                                               = identifierLists.flatMap(_.fieldIds)
    val directFunction                                         = functionDefinitions.find(_.name == "directFunction").orNull
    val directPattern                                          = patternDefinitions.find(_.bindings.exists(_.name == "directValue")).orNull
    val directVariable                                         = variableDefinitions.find(_.bindings.exists(_.name == "directVariable")).orNull
    val directPatternList                                      = Option(directPattern).map(_.pList).orNull
    val directReferencePattern                                 = referencePatterns.find(_.name == "directValue").orNull
    val directTypeAlias                                        = typeAliasDeclarations.find(_.name == "DirectAbstract").orNull
    val annotationPayloads                                     =
      annotationExpressions.flatMap(value => PsiTreeUtil.findChildrenOfType(value, classOf[ScExpression]).asScala)
    val manager                                                = PsiManager.getInstance(project)
    val persistenceFailure                                     = probePersistence(file).left.toOption
    val candidates                                             =
      Vector(
        packaging,
        bracedPackaging,
        outerPackaging,
        innerPackaging,
        innerEnd,
        outerEnd,
        bracedImport,
        innerExport,
        reference,
        qualifier,
        givenType,
        givenReference,
        parameterizedType,
        typeArguments,
        parameterizedBase,
        parameterizedArgs.headOption.orNull,
        qualifiedType,
        qualifiedReference,
        qualifiedQualifier,
        wildcardType,
        wildcardLower,
        wildcardUpper,
        contextBound,
        typeLambda,
        infixType,
        nestedInfixType,
        infixLeft,
        infixRight,
        infixOperation,
        integerLiteral,
        atomicReference.orNull,
        atomicUnqualifiedThis.orNull,
        atomicQualifiedThis.orNull,
        selectionReferences.headOption.orNull,
        selectionSuperReferences.headOption.orNull,
        typeProjection,
        integerLiteralType,
        longLiteralType,
        floatLiteralType,
        doubleLiteralType,
        charLiteralType,
        stringLiteralType,
        booleanLiteralType,
        integerLiteralValue,
        longLiteralValue,
        floatLiteralValue,
        doubleLiteralValue,
        charLiteralValue,
        stringLiteralValue,
        booleanLiteralValue,
        parenthesizedType,
        tupleType,
        tupleTypes,
        namedTupleType,
        namedTupleComponents.headOption.orNull,
        functionType,
        contextFunctionType,
        dependentFunctionType,
        polyFunctionType,
        matchType,
        matchTypeCases,
        matchCases.headOption.orNull,
        matchTypeVariable,
        compoundType,
        refinement,
        annotatedType,
        typeAnnotationContainer,
        typeAnnotation,
        singletonType,
        annotatedModifiers,
        memberModifiers,
        deprecatedAnnotation
      ) ++ atomicLiterals ++ nativeGenericCall ++ nativeCall ++ nativeArguments ++ nativeUsingCall ++ nativeUsingArguments ++
        nativeRepeatedTyped ++ nativeRepeatedSequence ++ selectionReferences ++ selectionSuperReferences ++ statements ++ expressions ++ selectorSets ++ selectors ++ exportStatements ++ exportExpressions ++
        exportSelectorSets ++ exportSelectors ++ accessModifiers ++ annotationsContainers ++ annotations ++
        annotationExpressions ++ constructorInvocations ++ argumentLists ++ annotationPayloads
        ++ classes ++ traits ++ objects ++ enums ++ enumCases ++ enumSingletonCases ++ enumClassCases ++
        extendsBlocks ++ templateBodies ++ primaryConstructors ++ parameterClauses ++ parameterClause ++
        typeParameterClauses ++ typeParameters ++ parameters ++ classParameters ++ parameterTypes ++ templateParents ++
        selfTypes ++ derivesClauses
        ++ functionDefinitions ++ functionDeclarations ++ patternDefinitions ++ valueDeclarations ++ variableDefinitions ++
        variableDeclarations ++ typeAliasDeclarations ++
        typeAliasDefinitions ++ patternLists ++
        referencePatterns ++ identifierLists ++ fieldIds
    val nativeNamedTypeArguments                               = probeNativeNamedTypeArguments(project)
    val allCandidates                                          =
      candidates ++ nativeNamedTypeArguments.toOption.flatten.toVector.flatMap(value => Vector(value.list, value.entry))
    if nativeNamedTypeArguments.isLeft then Left(nativeNamedTypeArguments.left.toOption.get)
    else if packaging == null || reference == null || qualifier == null || bracedPackaging == null || outerPackaging == null ||
      innerPackaging == null || innerEnd == null || outerEnd == null || bracedImport == null || innerExport == null
    then Left("native package PSI probe is incomplete")
    else if statements.size != 10 || expressions.size != 10 || selectorSets.size != 6 || selectors.size != 8 then
      Left("native import PSI probe is incomplete")
    else if exportStatements.size != 1 || exportExpressions.map(_.getText) !=
        Vector("alpha.beta.{Original as Exported, given Bound, *}") || exportSelectorSets.size != 1 ||
        exportSelectors.size != 3
    then Left("native export PSI probe is incomplete")
    else if packaging.keyword == null || packaging.keyword.getText != "package" || reference.refName != "syntax" ||
      qualifier.refName != "example" || packaging.packageName != "example.syntax" || packaging.parentPackageName.nonEmpty ||
      reference.getText != "example.syntax" || qualifier.getText != "example"
    then Left("native package PSI accessors do not expose the required nested reference")
    else if packaging.isExplicit || packaging.findExplicitMarker.nonEmpty || packaging.getLBrace.nonEmpty ||
      packaging.getRBrace.nonEmpty || packaging.getColon.nonEmpty || packaging.isEnclosedByBraces ||
      packaging.isEnclosedByColon || packaging.end.nonEmpty
    then Left("native unbraced package PSI accessors are inconsistent")
    else if !bracedPackaging.isExplicit || bracedPackaging.findExplicitMarker.forall(_.getText != "{") ||
      bracedPackaging.getLBrace.forall(_.getNode.getElementType != ScalaTokenTypes.tLBRACE) ||
      bracedPackaging.getRBrace.forall(_.getNode.getElementType != ScalaTokenTypes.tRBRACE) ||
      bracedPackaging.getColon.nonEmpty || !bracedPackaging.isEnclosedByBraces || bracedPackaging.isEnclosedByColon ||
      bracedPackaging.end.nonEmpty || bracedPackaging.packagings.nonEmpty ||
      bracedPackaging.getImportStatements.toVector != Vector(
        bracedImport
      ) || bracedImport.getParent != bracedPackaging ||
      !bracedPackaging.bodyText.contains(bracedImport.getText)
    then Left("native braced package PSI accessors are inconsistent")
    else if !outerPackaging.isExplicit || outerPackaging.findExplicitMarker.forall(_.getText != ":") ||
      outerPackaging.getColon.forall(_.getNode.getElementType != ScalaTokenTypes.tCOLON) ||
      outerPackaging.getLBrace.nonEmpty || outerPackaging.getRBrace.nonEmpty || outerPackaging.isEnclosedByBraces ||
      !outerPackaging.isEnclosedByColon || outerPackaging.packagings.toVector != Vector(innerPackaging) ||
      innerPackaging.getParent != outerPackaging || innerPackaging.parentPackageName != "outer" ||
      innerPackaging.packageName != "inner" || innerPackaging.fullPackageName != "outer.inner" ||
      innerPackaging.asInstanceOf[ScExportsHolder].getExportStatements.toVector != Vector(innerExport) ||
      innerExport.getParent != innerPackaging ||
      outerPackaging.end.toVector != Vector(outerEnd) || innerPackaging.end.toVector != Vector(innerEnd)
    then Left("native colon package PSI accessors are inconsistent")
    else if Vector(innerEnd, outerEnd).exists(end =>
        end.getNode.getElementType != ScalaElementType.END_STMT || end.keyword.getText != "end" ||
          end.keyword.getNode.getElementType != ScalaTokenType.EndKeyword || end.tag.getText != end.getName ||
          end.getReference != end || end.getElement != end || end.getRangeInElement != end.tag.getTextRangeInParent ||
          !end.isSoft || end.getCanonicalText != "ScEnd" || end.begin.forall(_ != end.getParent) ||
          end.getParent.getLastChild != end
      )
    then Left("native end-marker PSI accessors are inconsistent")
    else if expressions.map(_.getText) != Vector(
        "alpha.beta.Member",
        "alpha.beta.*",
        "alpha.beta.{Original as Renamed, given Bound, *}",
        "alpha.beta.{Original => Renamed}",
        "alpha.beta.given Ordering[Int]",
        "alpha.beta.given alpha.gamma.Bound",
        "alpha.beta.given alpha.gamma.Box[? >: Lower <: Upper]",
        "alpha.beta.given Left | Middle & Right",
        "alpha.beta.`back-tick`",
        "alpha.beta._"
      ) || expressions.head.reference.forall(_.getText != "alpha.beta.Member") ||
      expressions.head.qualifier.forall(_.getText != "alpha.beta") || expressions.head.selectors.nonEmpty ||
      expressions.head.hasWildcardSelector || expressions.head.hasGivenSelector ||
      expressions(1).qualifier.forall(_.getText != "alpha.beta") || !expressions(1).hasWildcardSelector ||
      expressions(1).wildcardElement.forall(_.getText != "*") || expressions(1).selectorSet.nonEmpty ||
      expressions(2).qualifier.forall(_.getText != "alpha.beta") || !expressions(2).hasWildcardSelector ||
      !expressions(2).hasGivenSelector || !expressions(4).hasGivenSelector ||
      expressions(8).reference.forall(_.refName != "`back-tick`") ||
      !expressions(9).hasWildcardSelector || expressions(9).wildcardElement.forall(_.getText != "_")
    then Left("native import expression accessors are inconsistent")
    else if aliasSelectors.size != 2 || aliasSelectors.exists(_.importedName != Some("Renamed")) ||
      aliasSelectors.exists(_.aliasName != Some("Renamed")) ||
      aliasSelectors.exists(_.reference.forall(_.getText != "Original")) ||
      givenSelector.flatMap(_.givenTypeElement).forall(_.getText != "Bound") ||
      wildcardSelector.flatMap(_.wildcardElement).forall(_.getText != "*")
    then Left("native import selector accessors are inconsistent")
    else if !exportStatements.head.isTopLevel || exportStatements.head.topLevelQualifier != Some("example.syntax") ||
      exportExpressions.head.getParent != exportStatements.head || exportSelectorSets.head.getParent != exportExpressions.head ||
      exportSelectors.exists(_.getParent != exportSelectorSets.head)
    then Left("native export PSI accessors are inconsistent")
    else if aliasAsElement == null || aliasAsElement.getText != "as" || aliasArrowElement == null ||
      aliasArrowElement.getText != "=>" || aliasAsElement.getNode.getElementType == aliasArrowElement.getNode.getElementType
    then Left("native import alias tokens are inconsistent")
    else if wildcardElement == null || wildcardElement.getNode.getElementType != ScalaTokenType.WildcardStar ||
      legacyWildcardElement == null || legacyWildcardElement.getNode.getElementType == wildcardElement.getNode.getElementType
    then Left("native import wildcard token is inconsistent")
    else if givenType == null || givenReference == null || givenReference.getText != "Bound" ||
      givenType.getParent != givenSelector.orNull || givenReference.getParent != givenType
    then Left("native given import type PSI is inconsistent")
    else if parameterizedType == null || typeArguments == null || parameterizedBase == null ||
      leftTypeBracket == null || rightTypeBracket == null ||
      parameterizedType.getText != "Ordering[Int]" || parameterizedBase.getText != "Ordering" ||
      typeArguments.getText != "[Int]" || parameterizedArgs.map(_.getText) != Vector("Int") ||
      parameterizedBase.getParent != parameterizedType || typeArguments.getParent != parameterizedType ||
      parameterizedArgs.exists(_.getParent != typeArguments)
    then Left("native parameterized given import type PSI is inconsistent")
    else if qualifiedType == null || qualifiedReference == null || qualifiedQualifier == null ||
      qualifiedReference.getText != "alpha.gamma.Bound" || qualifiedReference.refName != "Bound" ||
      qualifiedQualifier.getText != "alpha.gamma" || qualifiedType.getParent == null ||
      qualifiedReference.getParent != qualifiedType || qualifiedQualifier.getParent != qualifiedReference
    then Left("native qualified given type PSI is inconsistent")
    else if wildcardType == null || wildcardLower == null || wildcardUpper == null || wildcardQuestion == null ||
      lowerBoundToken == null || upperBoundToken == null || wildcardType.getText != "? >: Lower <: Upper" ||
      wildcardLower.getText != "Lower" || wildcardUpper.getText != "Upper" ||
      wildcardLower.getParent != wildcardType || wildcardUpper.getParent != wildcardType ||
      wildcardQuestion.getParent != wildcardType || lowerBoundToken.getParent != wildcardType ||
      upperBoundToken.getParent != wildcardType ||
      wildcardQuestion.getNode.getElementType != ScalaTokenType.WildcardTypeQuestionMark ||
      lowerBoundToken.getNode.getElementType != ScalaTokenTypes.tLOWER_BOUND ||
      upperBoundToken.getNode.getElementType != ScalaTokenTypes.tUPPER_BOUND
    then Left("native wildcard given type PSI is inconsistent")
    else if infixType == null || nestedInfixType == null || infixLeft == null || infixRight == null ||
      infixOperation == null || infixLeft.getText != "Left" || infixOperation.getText != "|" ||
      infixRight.getText != "Middle & Right" || nestedInfixType.left.getText != "Middle" ||
      nestedInfixType.operation.getText != "&" || nestedInfixType.rightOption.forall(_.getText != "Right") ||
      infixLeft.getParent != infixType || infixRight.getParent != infixType || infixOperation.getParent != infixType ||
      nestedInfixType.getParent != infixType
    then Left("native infix given type PSI is inconsistent")
    else if matchType == null || matchTypeCases == null || matchCases.size != 2 || matchTypeVariable == null ||
      matchKeyword == null || caseKeyword == null || matchCaseArrow == null || matchCaseSemicolon == null
    then Left("native match type PSI probe is incomplete")
    else if matchType.getText != "X match { case Array[t] => t; case _ => Nothing }" ||
      matchType.scrutineeTypeElement.getText != "X" || matchType.scrutineeTypeElement.getParent != matchType ||
      matchType.cases != Some(matchTypeCases) || matchTypeCases.getParent != matchType ||
      matchTypeCases.firstCase != matchCases.head || matchTypeCases.cases.toVector != matchCases ||
      matchCases.map(_.pattern.map(_.getText)) != Vector(Some("Array[t]"), Some("_")) ||
      matchCases.map(_.result.map(_.getText)) != Vector(Some("t"), Some("Nothing")) ||
      matchCases.exists(_.getParent != matchTypeCases) ||
      matchCases.exists(value =>
        value.pattern.exists(_.getParent != value) || value.result.exists(_.getParent != value)
      ) ||
      matchTypeVariable.name != "t" || matchTypeVariable.nameId.getText != "t"
    then Left("native match type accessors are inconsistent")
    else if matchKeyword.getNode.getElementType != ScalaTokenTypes.kMATCH ||
      caseKeyword.getNode.getElementType != ScalaTokenTypes.kCASE ||
      matchCaseArrow.getNode.getElementType != ScalaTokenTypes.tFUNTYPE ||
      matchCaseSemicolon.getNode.getElementType != ScalaTokenTypes.tSEMICOLON
    then Left("native match type tokens are inconsistent")
    else if compoundType == null || refinement == null || annotatedType == null || typeAnnotationContainer == null ||
      typeAnnotation == null
    then Left("native refinement or annotated type PSI probe is incomplete")
    else if compoundType.components.map(_.getText).toVector != Vector("AnyRef") ||
      compoundType.refinement != Some(refinement) || refinement.getParent != compoundType ||
      refinement.types.map(_.name).toVector != Vector("RefinedElem") ||
      refinement.holders.flatMap(_.declaredElements).map(_.getName).toVector !=
        Vector("refinedValue", "refinedCurrent")
    then Left("native refinement type accessors are inconsistent")
    else if annotatedType.typeElement.getText != "TraitProbe" || annotatedType.typeElement.getParent != annotatedType ||
      typeAnnotationContainer.getParent != annotatedType || typeAnnotationContainer.getAnnotations.toVector !=
        Vector(typeAnnotation) || typeAnnotation.getText != "@unchecked"
    then Left("native annotated type accessors are inconsistent")
    else if integerLiteral == null || integerLiteral.getText != "1" then
      Left("native integer literal PSI is inconsistent")
    else if atomicReference.isEmpty || atomicUnqualifiedThis.isEmpty || atomicQualifiedThis.isEmpty ||
      atomicLiterals.size != 8
    then Left("native atomic expression PSI probe is incomplete")
    else if nativeGenericCall.isEmpty || nativeGenericCall.exists(call =>
        var visited = false
        call.accept(
          new ScalaElementVisitor:
            override def visitGenericCallExpression(value: ScGenericCall): Unit = visited = value eq call
        )
        call.getText != "nativeGeneric[Int, List[String]]" ||
        call.referencedExpr.getText != "nativeGeneric" || call.referencedExpr.getParent != call ||
        call.typeArgs.getText != "[Int, List[String]]" || call.typeArgs.getParent != call ||
        call.arguments.map(_.getText).toVector != Vector("Int", "List[String]") ||
        call.getChildren.toVector.collect { case value: org.jetbrains.plugins.scala.lang.psi.api.ScalaPsiElement =>
          value
        } !=
          Vector(call.referencedExpr, call.typeArgs) || !visited ||
          call.shapeType == null || call.multiResolve == null || call.bindInvokedExpr == null
      )
    then Left("native generic-call PSI accessors are inconsistent")
    else if nativeCall.isEmpty || nativeArguments.isEmpty ||
      nativeCall.exists(call =>
        call.getText != "atomicReference.toString(atomicInteger, atomicReference)" ||
          call.getInvokedExpr.getText != "atomicReference.toString" ||
          call.getInvokedExpr.getParent != call || call.args.getParent != call ||
          call.argumentExpressions.map(_.getText).toVector != Vector("atomicInteger", "atomicReference")
      ) || nativeArguments.exists(arguments =>
        arguments.getText != "(atomicInteger, atomicReference)" ||
          arguments.exprs.map(_.getText).toVector != Vector("atomicInteger", "atomicReference") ||
          arguments.getArgsCount != 2 || !arguments.isArgsInParens || arguments.isUsing
      )
    then Left("native application expression PSI accessors are inconsistent")
    else if nativeUsingCall.isEmpty || nativeUsingArguments.isEmpty || nativeUsingKeyword.size != 1 ||
      nativeUsingCall.exists(call =>
        call.getText != "nativeUsing(using atomicInteger, atomicReference)" ||
          call.getInvokedExpr.getText != "nativeUsing" || call.getInvokedExpr.getParent != call ||
          call.args.getParent != call ||
          call.argumentExpressions.map(_.getText).toVector != Vector("atomicInteger", "atomicReference")
      ) || nativeUsingArguments.exists(arguments =>
        arguments.getText != "(using atomicInteger, atomicReference)" ||
          arguments.exprs.map(_.getText).toVector != Vector("atomicInteger", "atomicReference") ||
          arguments.getArgsCount != 2 || !arguments.isArgsInParens || !arguments.isUsing ||
          arguments.getTextRange.getStartOffset + 1 != nativeUsingKeyword.head.getStartOffset ||
          arguments.getNode.getChildren(null).toVector.map(_.getText) !=
          Vector("(", "using", " ", "atomicInteger", ",", " ", "atomicReference", ")")
      ) || nativeUsingKeyword.exists(keyword =>
        keyword.getText != "using" || keyword.getTreeParent != nativeUsingArguments.get.getNode ||
          keyword.getPsi.getParent != nativeUsingArguments.get
      )
    then Left("native explicit-using application PSI accessors are inconsistent")
    else if nativeRepeatedCall.isEmpty || nativeRepeatedArguments.isEmpty || nativeRepeatedTyped.isEmpty ||
      nativeRepeatedSequence.isEmpty
    then Left("native repeated argument PSI probe is incomplete")
    else if selectionReferences.size != 8 || selectionSuperReferences.size != 4 ||
      selectionReferences.exists(reference =>
        reference.qualifier.isEmpty || reference.nameId.getParent != reference || reference.refName != "member" &&
          reference.refName != "length"
      ) || selectionSuperReferences.map(_.getText) != Vector(
        "super",
        "super[AtomicSelectionMixin]",
        "AtomicSelectionProbe.super",
        "AtomicSelectionProbe.super[AtomicSelectionMixin]"
      ) || selectionSuperReferences.exists(reference =>
        reference.staticSuperName.nonEmpty != reference.getText.contains("[") ||
          reference.reference.nonEmpty != reference.getText.startsWith("AtomicSelectionProbe.")
      )
    then Left("native selection or super expression accessors are inconsistent")
    else if atomicReference.exists(reference =>
        reference.qualifier.nonEmpty || reference.refName != "singletonValue" ||
          reference.nameId.getText != "singletonValue" || reference.getFirstChild != reference.nameId
      ) || atomicUnqualifiedThis.exists(_.reference.nonEmpty) ||
      atomicQualifiedThis.exists(reference =>
        reference.reference.forall(_.getText != "AtomicThisProbe") ||
          reference.reference.exists(_.getParent != reference)
      ) ||
      atomicLiterals.exists(literal =>
        literal.getTextRange.isEmpty || literal.contentRange.isEmpty || literal.contentRangeInParent.isEmpty ||
          literal.contentText == null
      ) ||
      atomicInteger.exists(_.getValue != Integer.valueOf(32767)) ||
      atomicLong.exists(_.getValue != java.lang.Long.valueOf(9223L)) ||
      atomicFloat.exists(_.getValue != java.lang.Float.valueOf(1.25f)) ||
      atomicDouble.exists(_.getValue != java.lang.Double.valueOf(2.5d)) ||
      atomicBoolean.exists(_.getValue != java.lang.Boolean.TRUE) ||
      atomicChar.exists(_.getValue != Character.valueOf('\n')) || atomicString.exists(_.getValue != "a\tb") ||
      atomicNull.exists(_.getValue != null) || !atomicLiterals.init.forall(_.isSimpleLiteral) ||
      atomicNull.exists(_.isSimpleLiteral)
    then Left("native atomic expression accessors or direct children are inconsistent")
    else if typeProjection == null || integerLiteralType == null || longLiteralType == null || floatLiteralType == null ||
      doubleLiteralType == null || charLiteralType == null || stringLiteralType == null || booleanLiteralType == null ||
      integerLiteralValue == null || longLiteralValue == null || floatLiteralValue == null || doubleLiteralValue == null ||
      charLiteralValue == null || stringLiteralValue == null || booleanLiteralValue == null ||
      parenthesizedType == null || singletonType == null
    then Left("native type atom PSI probe is incomplete")
    else if tupleType == null || tupleTypes == null || namedTupleType == null || namedTupleComponents.size != 2 ||
      functionType == null || contextFunctionType == null || dependentFunctionType == null || polyFunctionType == null ||
      ordinaryFunctionArrow == null || contextFunctionArrow == null
    then Left("native tuple or function type PSI probe is incomplete")
    else if tupleType.components.map(_.getText).toVector != Vector("Lower", "Upper") ||
      tupleType.typeList != tupleTypes || tupleTypes.types.toVector != tupleType.components.toVector ||
      namedTupleComponents.map(_.name) != Vector("lower", "upper") ||
      namedTupleComponents.map(_.typeElement.map(_.getText)) != Vector(Some("Lower"), Some("Upper")) ||
      namedTupleComponents.exists(_.namedTuple != namedTupleType) ||
      functionType.paramTypeElement.getText != "(Lower, Upper)" ||
      functionType.paramTypeElement.getParent != functionType ||
      functionType.returnTypeElement.map(_.getText) != Some("Bound") ||
      functionType.isContext || contextFunctionType.paramTypeElement.getText != "Bound" ||
      contextFunctionType.returnTypeElement.map(_.getText) != Some("Lower") || !contextFunctionType.isContext ||
      dependentFunctionType.parameterClause.parameters.map(_.name).toVector != Vector("value") ||
      dependentFunctionType.returnTypeElement.map(_.getText) != Some("value.type") ||
      polyFunctionType.typeParameters.map(_.name).toVector != Vector("A") ||
      polyFunctionType.resultTypeElement.map(_.getText) != Some("A => A")
    then Left("native tuple or function type accessors are inconsistent")
    else if typeProjection.typeElement.getText != "DefinitionOwner" || typeProjection.nameId.getText != "DirectAbstract" ||
      typeProjection.qualifier.nonEmpty || typeProjection.typeElement.getParent != typeProjection ||
      Vector(
        integerLiteralType -> integerLiteralValue,
        longLiteralType    -> longLiteralValue,
        floatLiteralType   -> floatLiteralValue,
        doubleLiteralType  -> doubleLiteralValue,
        charLiteralType    -> charLiteralValue,
        stringLiteralType  -> stringLiteralValue,
        booleanLiteralType -> booleanLiteralValue
      ).exists((literalType, literalValue) => literalType.getLiteral != literalValue || !literalType.isSingleton) ||
      parenthesizedType.innerElement.forall(_.getText != "alpha.gamma.Bound") ||
      parenthesizedType.innerElement.exists(_.getParent != parenthesizedType) ||
      singletonType.reference.forall(_.getText != "singletonValue") || !singletonType.isSingleton ||
      singletonType.pathElement.getText != "singletonValue"
    then Left("native type atom accessors or direct children are inconsistent")
    else if annotatedModifiers == null || memberModifiers == null || accessModifiers.size != 2 ||
      annotationContainer == null || annotations.map(_.getText) !=
        Vector("@ann", "@pkg.ann", "@deprecated(\"m\", \"1\")") || annotationExpressions.size != 3 ||
        constructorInvocations.size != 3 || argumentLists.map(_.getText) != Vector("(\"m\", \"1\")") ||
        deprecatedAnnotation == null
    then
      Left(
        s"native modifier or annotation PSI probe is incomplete: " +
          s"modifiers=${modifierLists.map(_.getText)}, access=${accessModifiers.map(_.getText)}, " +
          s"containers=${annotationsContainers.map(_.getText)}, annotations=${annotations.map(_.getText)}, " +
          s"expressions=${annotationExpressions.map(_.getText)}, constructors=${constructorInvocations.map(_.getText)}, " +
          s"arguments=${argumentLists.map(_.getText)}"
      )
    else if classes.isEmpty || traits.size != 4 || objects.isEmpty || enums.size != 1 || enumCases.size != 2 ||
      enumSingletonCases.size != 1 || enumClassCases.size != 1 || extendsBlocks.isEmpty || templateBodies.isEmpty ||
      primaryConstructors.isEmpty || parameterClauses.isEmpty || parameterClause.isEmpty
    then Left("native template PSI probe is incomplete")
    else if contextBound == null || typeLambda == null || contextBound.typeElement.getText != "Bound" ||
      typeLambda.resultTypeElement.map(_.getText) != Some("List[X]")
    then Left("native bounds or type lambda PSI probe is incomplete")
    else if directFunction == null || functionDeclarations.size != 1 || directPattern == null ||
      valueDeclarations.size != 1 || directVariable == null || variableDeclarations.size != 1 ||
      directPatternList == null || directReferencePattern == null || identifierLists.size != 2 || fieldIds.size != 2 ||
      directTypeAlias == null
    then Left("native definition PSI probe is incomplete")
    else if !directFunction.hasAssign || directFunction.paramClauses == null || !directPatternList.simplePatterns ||
      directPattern.bindings.toVector != Vector(directReferencePattern) ||
      directVariable.bindings.map(_.name).toVector != Vector(
        "directVariable"
      ) || functionDeclarations.head.hasAssign ||
      functionDeclarations.head.returnTypeElement.map(_.getText) != Some("TraitProbe") ||
      valueDeclarations.head.getIdList.fieldIds.map(_.name).toVector != Vector("declaredValue") ||
      variableDeclarations.head.getIdList.fieldIds.map(_.name).toVector != Vector("declaredVariable") ||
      identifierLists.flatMap(_.fieldIds).toVector != fieldIds ||
      directTypeAlias.lowerTypeElement.nonEmpty || directTypeAlias.upperTypeElement.nonEmpty
    then Left("native definition PSI accessors are inconsistent")
    else if typeParameterClauses.size != 1 || typeParameters.map(_.name) != Vector("A", "B", "C") ||
      typeParameterClauses.head.typeParameters.toVector != typeParameters ||
      typeParameters.map(typeParameterClauses.head.getTypeParameterIndex) != Vector(0, 1, 2) ||
      !typeParameters.head.isCovariant || typeParameters(1).isCovariant || !typeParameters(1).isContravariant ||
      typeParameters(2).isCovariant || typeParameters(2).isContravariant ||
      typeParameters.exists(value =>
        value.nameId.getText != value.name || value.lowerTypeElement.nonEmpty ||
          value.upperTypeElement.nonEmpty ||
          value.viewTypeElement.nonEmpty || value.contextBounds.nonEmpty || value.owner != classes
            .find(_.name == "Generic")
            .orNull ||
          value.getNavigationElement != value
      )
    then Left("native type parameter PSI accessors are inconsistent")
    else if boundsParameters.map(_.name) != Vector("D", "F", "G") ||
      !boundsParameters.head.isCovariant || boundsParameters.head.isContravariant ||
      boundsParameters.head.lowerTypeElement.map(_.getText) != Some("Lower") ||
      boundsParameters.head.upperTypeElement.map(_.getText) != Some("Upper") ||
      boundsParameters(1).typeParameters.map(_.getText).toVector != Vector("_") ||
      boundsParameters(2).contextBounds.map(_.getText).toVector != Vector("Bound") ||
      boundsParameters.exists(_.name.matches("_\\$[0-9]+")) ||
      typeLambda.typeParameters.map(_.name).toVector != Vector("X") ||
      typeLambda.typeParameters.head.lowerTypeElement.map(_.getText) != Some("Lower") ||
      typeLambda.typeParameters.head.upperTypeElement.map(_.getText) != Some("Upper")
    then Left("native bounds PSI accessors are inconsistent")
    else if Vector(
        PsiOutputRoleId.ClassDefinition      -> classes.head,
        PsiOutputRoleId.TraitDefinition      -> traits.head,
        PsiOutputRoleId.ObjectDefinition     -> objects.head,
        PsiOutputRoleId.EnumDefinition       -> enums.head,
        PsiOutputRoleId.EnumCases            -> enumCases.head,
        PsiOutputRoleId.EnumSingletonCase    -> enumSingletonCases.head,
        PsiOutputRoleId.EnumClassCase        -> enumClassCases.head,
        PsiOutputRoleId.ExtendsBlock         -> extendsBlocks.head,
        PsiOutputRoleId.TemplateBody         -> templateBodies.head,
        PsiOutputRoleId.PrimaryConstructor   -> primaryConstructors.head,
        PsiOutputRoleId.ParameterClauses     -> parameterClauses.head,
        PsiOutputRoleId.ParameterClause      -> parameterClause.head,
        PsiOutputRoleId.TypeParameterClause  -> typeParameterClauses.head,
        PsiOutputRoleId.TypeParameter        -> typeParameters.head,
        PsiOutputRoleId.Parameter            -> parameters.find(!_.isInstanceOf[ScClassParameter]).get,
        PsiOutputRoleId.ClassParameter       -> classParameters.head,
        PsiOutputRoleId.TemplateParents      -> templateParents.head,
        PsiOutputRoleId.SelfType             -> selfTypes.head,
        PsiOutputRoleId.DerivesClause        -> derivesClauses.head,
        PsiOutputRoleId.FunctionDefinition   -> directFunction,
        PsiOutputRoleId.FunctionDeclaration  -> functionDeclarations.head,
        PsiOutputRoleId.PatternDefinition    -> directPattern,
        PsiOutputRoleId.ValueDeclaration     -> valueDeclarations.head,
        PsiOutputRoleId.VariableDefinition   -> directVariable,
        PsiOutputRoleId.VariableDeclaration  -> variableDeclarations.head,
        PsiOutputRoleId.PatternList          -> directPatternList,
        PsiOutputRoleId.ReferencePattern     -> directReferencePattern,
        PsiOutputRoleId.IdentifierList       -> identifierLists.head,
        PsiOutputRoleId.FieldId              -> fieldIds.head,
        PsiOutputRoleId.TypeAliasDeclaration -> directTypeAlias,
        PsiOutputRoleId.TypeAliasDefinition  -> typeAliasDefinitions.head
      ).exists: (role, element) =>
        element.getNode.getElementType match
          case stub: IStubElementType[?, ?] =>
            (TemplatePersistenceSurfaces.ExternalIds ++ DefinitionPersistenceSurfaces.ExternalIds)
              .get(role)
              .forall(_ != stub.getExternalId)
          case _                            => true
    then Left("native PSI external IDs are inconsistent")
    else if annotatedModifiers.getNode.getElementType != ScalaElementType.MODIFIERS ||
      accessModifiers.exists(_.getNode.getElementType != ScalaElementType.ACCESS_MODIFIER) ||
      annotationContainer.getNode.getElementType != ScalaElementType.ANNOTATIONS ||
      annotations.exists(_.getNode.getElementType != ScalaElementType.ANNOTATION) ||
      annotationExpressions.exists(_.getNode.getElementType != ScalaElementType.ANNOTATION_EXPR)
    then Left("native modifier or annotation element types are inconsistent")
    else if annotatedModifiers.accessModifier.forall(value =>
        !value.isPrivate || value.isProtected || value.isThis || value.idText != Some("scope")
      ) || memberModifiers.accessModifier.forall(value =>
        value.isPrivate || !value.isProtected || !value.isThis || value.idText.nonEmpty
      ) || annotatedModifiers.modifiersOrdered.map(_.text).toVector != Vector("private", "abstract") ||
      memberModifiers.modifiersOrdered.map(_.text).toVector != Vector("protected", "final", "inline") ||
      !annotatedModifiers.hasModifierProperty("private") || !annotatedModifiers.hasModifierProperty("abstract") ||
      !memberModifiers.hasModifierProperty("protected") || !memberModifiers.hasModifierProperty("final") ||
      !memberModifiers.hasModifierProperty("inline")
    then Left("native modifier accessors are inconsistent")
    else if annotationContainer.getAnnotations.toVector != annotations ||
      annotations.exists(value => value.getParent != annotationContainer) ||
      annotations
        .zip(annotationExpressions)
        .exists((annotation, expression) =>
          annotation.annotationExpr != expression || expression.getParent != annotation ||
            annotation.constructorInvocation != expression.constructorInvocation ||
            annotation.typeElement != annotation.constructorInvocation.typeElement
        ) || constructorInvocations.map(_.typeElement.getText) != Vector("ann", "pkg.ann", "deprecated") ||
      constructorInvocations
        .map(_.reference.map(_.getText)) != Vector(Some("ann"), Some("pkg.ann"), Some("deprecated")) ||
      constructorInvocations.map(_.args.map(_.getText)) != Vector(None, None, Some("(\"m\", \"1\")")) ||
      constructorInvocations
        .zip(annotationExpressions)
        .exists((constructor, expression) => constructor.getParent != expression) ||
      deprecatedAnnotation.annotationExpr.getAttributes.nonEmpty ||
      deprecatedAnnotation.annotationExpr.getAnnotationParameters.map(_.getText).toVector != Vector("\"m\"", "\"1\"")
    then
      Left(
        s"native annotation accessors are inconsistent: " +
          s"designators=${constructorInvocations.map(_.typeElement.getText)}, " +
          s"arguments=${constructorInvocations.map(_.args.map(_.getText))}, " +
          s"attributes=${deprecatedAnnotation.annotationExpr.getAttributes.map(_.getText).toVector}, " +
          s"parameters=${deprecatedAnnotation.annotationExpr.getAnnotationParameters.map(_.getText).toVector}"
      )
    else if ScalaIndexKeys.ALIASED_IMPORT_KEY == null || ScalaIndexKeys.TOP_LEVEL_EXPORT_BY_PKG_KEY == null ||
      ScalaIndexKeys.ANNOTATED_MEMBER_KEY == null || ScalaIndexKeys.SHORT_NAME_KEY == null ||
      ScalaIndexKeys.CLASS_FQN_KEY == null || ScalaIndexKeys.CLASS_NAME_IN_PACKAGE_KEY == null ||
      ScalaIndexKeys.JAVA_CLASS_NAME_IN_PACKAGE_KEY == null || ScalaIndexKeys.NOT_VISIBLE_IN_JAVA_SHORT_NAME_KEY == null ||
      ScalaIndexKeys.ALL_CLASS_NAMES == null || ScalaIndexKeys.SUPER_CLASS_NAME_KEY == null ||
      ScalaIndexKeys.SELF_TYPE_CLASS_NAME_KEY == null ||
      ScalaIndexKeys.METHOD_NAME_KEY == null || ScalaIndexKeys.TOP_LEVEL_FUNCTION_BY_PKG_KEY == null ||
      ScalaIndexKeys.PROPERTY_NAME_KEY == null || ScalaIndexKeys.TOP_LEVEL_VAL_OR_VAR_BY_PKG_KEY == null ||
      ScalaIndexKeys.TYPE_ALIAS_NAME_KEY == null || ScalaIndexKeys.TOP_LEVEL_TYPE_ALIAS_BY_PKG_KEY == null ||
      JavaStubIndexKeys.CLASS_SHORT_NAMES == null || JavaStubIndexKeys.CLASS_FQN == null
    then Left("native import or export index is unavailable")
    else if persistenceFailure.nonEmpty then Left(persistenceFailure.get)
    else if reference.getParent != packaging || qualifier.getParent != reference then
      Left("native package PSI direct parents are inconsistent")
    else if expressions.exists(_.getParent == null) ||
      statements.zip(expressions).exists((statement, expression) => expression.getParent != statement) ||
      selectorSets
        .zip(Vector(expressions(2), expressions(3), expressions(4)))
        .exists((selectors, expression) => selectors.getParent != expression) ||
      selectors.exists(selector => !selectorSets.contains(selector.getParent))
    then Left("native import PSI direct parents are inconsistent")
    else if allCandidates.exists(value =>
        (value.getContainingFile != file && value.getContainingFile != packageLayoutFile &&
          !nativeNamedTypeArguments.toOption.flatten.exists(_.list.getContainingFile == value.getContainingFile)) ||
          value.getProject != project
      )
    then Left("native PSI identity is inconsistent")
    else if allCandidates.exists(value => value.getNode.getPsi ne value) then
      Left("native AST and PSI identity is inconsistent")
    else if allCandidates.exists(value => !manager.areElementsEquivalent(value, value.getNavigationElement))
    then Left("native package PSI navigation is not self-identical")
    else
      val values          = allCandidates.map(value => surfaceId(value.getClass) -> value.getNode.getElementType)
      val grouped         = values.groupMap(_._1)(_._2)
      val packageSurfaces = Set(
        "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/packaging/ScPackagingImpl",
        "org/jetbrains/plugins/scala/lang/psi/impl/base/ScStableCodeReferenceImpl"
      )
      if !packageSurfaces.subsetOf(grouped.keySet) then
        Left("native package PSI implementation surfaces are unexpected")
      else if !Set(
          "org/jetbrains/plugins/scala/lang/psi/impl/base/ScModifierListImpl",
          "org/jetbrains/plugins/scala/lang/psi/impl/base/ScAccessModifierImpl",
          "org/jetbrains/plugins/scala/lang/psi/impl/expr/ScAnnotationsImpl",
          "org/jetbrains/plugins/scala/lang/psi/impl/expr/ScAnnotationImpl",
          "org/jetbrains/plugins/scala/lang/psi/impl/expr/ScAnnotationExprImpl",
          "org/jetbrains/plugins/scala/lang/psi/impl/base/ScConstructorInvocationImpl",
          "org/jetbrains/plugins/scala/lang/psi/impl/expr/ScArgumentExprListImpl"
        ).subsetOf(grouped.keySet)
      then Left("native modifier or annotation implementation surfaces are unexpected")
      else if grouped.values.exists(types => types.tail.exists(_ ne types.head)) then
        Left("native PSI implementation surface has inconsistent element types")
      else if packaging.getNode.getElementType == reference.getNode.getElementType ||
        reference.getNode.getElementType != qualifier.getNode.getElementType
      then Left("native package PSI element-type identities are inconsistent")
      else if (Vector(packaging) ++ statements ++ expressions ++ selectorSets ++ selectors).exists(value =>
          !value.getNode.getElementType.isInstanceOf[IStubElementType[?, ?]]
        )
      then Left("native stub-bearing PSI element type cannot produce stubs")
      else if (classes ++ traits ++ objects ++ enums ++ enumCases ++ enumSingletonCases ++ enumClassCases ++ extendsBlocks ++
          templateBodies ++ primaryConstructors ++ parameterClauses ++ parameterClause ++ typeParameterClauses ++
          typeParameters).exists(value => !value.getNode.getElementType.isInstanceOf[IStubElementType[?, ?]])
      then Left("native template stub-bearing PSI element type cannot produce stubs")
      else if Vector(
          directFunction,
          directPattern,
          directVariable,
          directPatternList,
          directReferencePattern,
          directTypeAlias
        ).exists(value => !value.getNode.getElementType.isInstanceOf[IStubElementType[?, ?]])
      then Left("native definition stub-bearing PSI element type cannot produce stubs")
      else if (Vector(
          atomicReference,
          atomicUnqualifiedThis,
          atomicQualifiedThis
        ).flatten ++ atomicLiterals ++ nativeGenericCall ++ nativeCall ++ nativeArguments ++ nativeUsingCall ++ nativeUsingArguments ++
          nativeRepeatedTyped ++ nativeRepeatedSequence ++ selectionReferences ++ selectionSuperReferences).exists(
          _.getNode.getElementType.isInstanceOf[IStubElementType[?, ?]]
        )
      then Left("native atomic expression element type unexpectedly produces stubs")
      else if (Vector(annotatedModifiers, annotationContainer) ++ accessModifiers ++ annotations).exists(value =>
          !value.getNode.getElementType.isInstanceOf[IStubElementType[?, ?]]
        ) || annotationExpressions.exists(value => value.getNode.getElementType.isInstanceOf[IStubElementType[?, ?]])
      then
        Left(
          "native modifier or annotation stub-bearing status is inconsistent: " +
            (Vector(annotatedModifiers) ++ accessModifiers ++ Vector(annotationContainer) ++ annotations ++
              annotationExpressions).map(value =>
              s"${value.getClass.getSimpleName}=${value.getNode.getElementType.getClass.getName}"
            )
        )
      else
        val namedListType     = nativeNamedTypeArguments.toOption.flatten
          .map(_.list.getNode.getElementType)
          .getOrElse(MetallurgyTypeArguments.ElementType)
        val namedEntryType    = nativeNamedTypeArguments.toOption.flatten
          .map(_.entry.getNode.getElementType)
          .getOrElse(MetallurgyNamedTypeArgument.ElementType)
        val namedListSurface  = nativeNamedTypeArguments.toOption.flatten
          .map(value => surfaceId(value.list.getClass))
          .getOrElse("org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyTypeArguments")
        val namedEntrySurface = nativeNamedTypeArguments.toOption.flatten
          .map(value => surfaceId(value.entry.getClass))
          .getOrElse("org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyNamedTypeArgument")
        Right(
          NativePsiElementBindings(
            grouped.view.mapValues(_.head).toMap +
              ("org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScCaptureTypeElementImpl"    ->
                ScalaElementType.CAPTURE_TYPE) +
              ("org/jetbrains/plugins/scala/lang/psi/impl/base/types/cc/ScCaptureSetImpl"         ->
                ScalaElementType.CAPTURE_SET) +
              ("org/jetbrains/plugins/scala/lang/psi/impl/base/types/cc/ScCaptureRefImpl"         ->
                ScalaElementType.CAPTURE_REF) +
              ("org/jetbrains/plugins/scala/lang/psi/impl/base/types/cc/ScCaptureFilterImpl"      ->
                ScalaElementType.CAPTURE_FILTER) +
              ("org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyExpressionPayload" ->
                MetallurgyExpressionPayload.ElementType) +
              ("org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyNamedArgument"     ->
                MetallurgyNamedArgument.ElementType) +
              ("org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyTypeArguments"     ->
                namedListType) +
              ("org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyNamedTypeArgument" ->
                namedEntryType) +
              ("org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyParameterType"     ->
                MetallurgyParameterType.ElementType) +
              ("org/jetbrains/plugins/scala/lang/psi/impl/expr/ScTypedExpressionImpl"             ->
                nativeRepeatedTyped.head.getNode.getElementType) +
              ("org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScSequenceArgImpl"           ->
                nativeRepeatedSequence.head.getNode.getElementType) +
              (EndKeywordTokenSurface                                                             -> ScalaTokenType.EndKeyword) +
              (ImportWildcardTokenSurface                                                         -> wildcardElement.getNode.getElementType) +
              (ImportLegacyWildcardTokenSurface                                                   -> legacyWildcardElement.getNode.getElementType) +
              (ImportAliasAsTokenSurface                                                          -> aliasAsElement.getNode.getElementType) +
              (ImportAliasArrowTokenSurface                                                       -> aliasArrowElement.getNode.getElementType) +
              (TypeArgumentLeftTokenSurface                                                       -> leftTypeBracket.getNode.getElementType) +
              (TypeArgumentRightTokenSurface                                                      -> rightTypeBracket.getNode.getElementType) +
              (WildcardQuestionTokenSurface                                                       -> wildcardQuestion.getNode.getElementType) +
              (LowerTypeBoundTokenSurface                                                         -> lowerBoundToken.getNode.getElementType) +
              (UpperTypeBoundTokenSurface                                                         -> upperBoundToken.getNode.getElementType) ++
              Map(VarianceTokenSurface -> ScalaTokenTypes.tIDENTIFIER) ++
              Map(ContextBoundColonTokenSurface -> ScalaTokenTypes.tCOLON) ++
              Map(ContextBoundLeftBraceTokenSurface -> ScalaTokenTypes.tLBRACE) ++
              Map(ContextBoundRightBraceTokenSurface -> ScalaTokenTypes.tRBRACE) ++
              Map(ContextBoundCommaTokenSurface -> ScalaTokenTypes.tCOMMA) ++
              Map(ContextBoundAsTokenSurface -> ScalaTokenType.AsKeyword) ++
              Map(AssignmentTokenSurface -> ScalaTokenTypes.tASSIGN) ++
              Map(ValueKeywordTokenSurface -> ScalaTokenTypes.kVAL) ++
              Map(IntegerLiteralTokenSurface -> ScalaTokenType.Integer) ++
              Map(LongLiteralTokenSurface -> ScalaTokenType.Long) ++
              Map(FloatLiteralTokenSurface -> ScalaTokenType.Float) ++
              Map(DoubleLiteralTokenSurface -> ScalaTokenType.Double) ++
              Map(CharLiteralTokenSurface -> ScalaTokenTypes.tCHAR) ++
              Map(StringLiteralTokenSurface -> ScalaTokenTypes.tSTRING) ++
              Map(TypePathDotTokenSurface -> ScalaTokenTypes.tDOT) ++
              Map(TypeProjectionHashTokenSurface -> ScalaTokenTypes.tINNER_CLASS) ++
              Map(SingletonTypeKeywordTokenSurface -> ScalaTokenTypes.kTYPE) ++
              Map(TypeLeftParenthesisTokenSurface -> ScalaTokenTypes.tLPARENTHESIS) ++
              Map(TypeRightParenthesisTokenSurface -> ScalaTokenTypes.tRPARENTHESIS) ++
              Map(TypeCommaTokenSurface -> ScalaTokenTypes.tCOMMA) ++
              Map(UsingKeywordTokenSurface -> ScalaTokenType.UsingKeyword) ++
              Map(TypeColonTokenSurface -> ScalaTokenTypes.tCOLON) ++
              Map(FunctionArrowTokenSurface -> ordinaryFunctionArrow.getNode.getElementType) ++
              Map(ContextFunctionArrowTokenSurface -> contextFunctionArrow.getNode.getElementType) ++
              Map(PureFunctionArrowTokenSurface -> ScalaTokenType.PureFunctionArrow) ++
              Map(ContextPureFunctionArrowTokenSurface -> ScalaTokenType.ImplicitPureFunctionArrow) ++
              Map(RepeatedParameterStarTokenSurface -> ScalaTokenTypes.tIDENTIFIER) ++
              Map(MatchKeywordTokenSurface -> matchKeyword.getNode.getElementType) ++
              Map(CaseKeywordTokenSurface -> caseKeyword.getNode.getElementType) ++
              Map(SemicolonTokenSurface -> matchCaseSemicolon.getNode.getElementType) ++
              Map(CaptureOperatorTokenSurface -> ScalaTokenType.CaptureOperator) ++
              Map(CaptureReachTokenSurface -> ScalaTokenType.ReachCapabilityStar) ++
              Map(CaptureReadOnlyTokenSurface -> ScalaTokenType.ReadOnlyCapabilityKeyword) ++
              Map(
                ModifierKeywordSurfaceIds("Abstract")        -> ScalaTokenTypes.kABSTRACT,
                ModifierKeywordSurfaceIds("Final")           -> ScalaTokenTypes.kFINAL,
                ModifierKeywordSurfaceIds("Sealed")          -> ScalaTokenTypes.kSEALED,
                ModifierKeywordSurfaceIds("Implicit")        -> ScalaTokenTypes.kIMPLICIT,
                ModifierKeywordSurfaceIds("Lazy")            -> ScalaTokenTypes.kLAZY,
                ModifierKeywordSurfaceIds("Override")        -> ScalaTokenTypes.kOVERRIDE,
                ModifierKeywordSurfaceIds("Var")             -> ScalaTokenTypes.kVAR,
                ModifierKeywordSurfaceIds("Transparent")     -> ScalaTokenType.TransparentKeyword,
                ModifierKeywordSurfaceIds("Inline")          -> ScalaTokenType.InlineKeyword,
                ModifierKeywordSurfaceIds("Infix")           -> ScalaTokenType.InfixKeyword,
                ModifierKeywordSurfaceIds("Open")            -> ScalaTokenType.OpenKeyword,
                ModifierKeywordSurfaceIds("Opaque")          -> ScalaTokenType.OpaqueKeyword,
                ModifierKeywordSurfaceIds("Given")           -> ScalaTokenType.GivenKeyword,
                AccessModifierKeywordSurfaceIds("Private")   -> ScalaTokenTypes.kPRIVATE,
                AccessModifierKeywordSurfaceIds("Protected") -> ScalaTokenTypes.kPROTECTED
              ),
            Map(
              PsiOutputRoleId.PackageStatement      -> packaging.getNode.getElementType,
              PsiOutputRoleId.EndStatement          -> innerEnd.getNode.getElementType,
              PsiOutputRoleId.ImportStatement       -> statements.head.getNode.getElementType,
              PsiOutputRoleId.ExportStatement       -> exportStatements.head.getNode.getElementType,
              PsiOutputRoleId.ImportExpression      -> expressions.head.getNode.getElementType,
              PsiOutputRoleId.ImportSelectorSet     -> selectorSets.head.getNode.getElementType,
              PsiOutputRoleId.ImportSelector        -> selectors.head.getNode.getElementType,
              PsiOutputRoleId.StableReference       -> reference.getNode.getElementType,
              PsiOutputRoleId.SimpleType            -> givenType.getNode.getElementType,
              PsiOutputRoleId.SingletonType         -> singletonType.getNode.getElementType,
              PsiOutputRoleId.TypeProjection        -> typeProjection.getNode.getElementType,
              PsiOutputRoleId.LiteralType           -> integerLiteralType.getNode.getElementType,
              PsiOutputRoleId.ParenthesizedType     -> parenthesizedType.getNode.getElementType,
              PsiOutputRoleId.TupleType             -> tupleType.getNode.getElementType,
              PsiOutputRoleId.TupleTypes            -> tupleTypes.getNode.getElementType,
              PsiOutputRoleId.NamedTupleType        -> namedTupleType.getNode.getElementType,
              PsiOutputRoleId.NamedTupleComponent   -> namedTupleComponents.head.getNode.getElementType,
              PsiOutputRoleId.FunctionType          -> functionType.getNode.getElementType,
              PsiOutputRoleId.DependentFunctionType -> dependentFunctionType.getNode.getElementType,
              PsiOutputRoleId.PolyFunctionType      -> polyFunctionType.getNode.getElementType,
              PsiOutputRoleId.IntegerLiteralValue   -> integerLiteralValue.getNode.getElementType,
              PsiOutputRoleId.LongLiteralValue      -> longLiteralValue.getNode.getElementType,
              PsiOutputRoleId.FloatLiteralValue     -> floatLiteralValue.getNode.getElementType,
              PsiOutputRoleId.DoubleLiteralValue    -> doubleLiteralValue.getNode.getElementType,
              PsiOutputRoleId.CharLiteralValue      -> charLiteralValue.getNode.getElementType,
              PsiOutputRoleId.StringLiteralValue    -> stringLiteralValue.getNode.getElementType,
              PsiOutputRoleId.BooleanLiteralValue   -> booleanLiteralValue.getNode.getElementType,
              PsiOutputRoleId.ParameterizedType     -> parameterizedType.getNode.getElementType,
              PsiOutputRoleId.TypeArguments         -> typeArguments.getNode.getElementType,
              PsiOutputRoleId.WildcardType          -> wildcardType.getNode.getElementType,
              PsiOutputRoleId.ContextBound          -> contextBound.getNode.getElementType,
              PsiOutputRoleId.TypeLambda            -> typeLambda.getNode.getElementType,
              PsiOutputRoleId.InfixType             -> infixType.getNode.getElementType,
              PsiOutputRoleId.MatchType             -> matchType.getNode.getElementType,
              PsiOutputRoleId.MatchTypeCases        -> matchTypeCases.getNode.getElementType,
              PsiOutputRoleId.MatchTypeCase         -> matchCases.head.getNode.getElementType,
              PsiOutputRoleId.MatchTypeVariable     -> matchTypeVariable.getNode.getElementType,
              PsiOutputRoleId.CompoundType          -> compoundType.getNode.getElementType,
              PsiOutputRoleId.Refinement            -> refinement.getNode.getElementType,
              PsiOutputRoleId.AnnotatedType         -> annotatedType.getNode.getElementType,
              PsiOutputRoleId.CaptureType           -> ScalaElementType.CAPTURE_TYPE,
              PsiOutputRoleId.CaptureSet            -> ScalaElementType.CAPTURE_SET,
              PsiOutputRoleId.CaptureReference      -> ScalaElementType.CAPTURE_REF,
              PsiOutputRoleId.CaptureFilter         -> ScalaElementType.CAPTURE_FILTER,
              PsiOutputRoleId.TermReference         -> atomicReference.get.getNode.getElementType,
              PsiOutputRoleId.ThisReference         -> atomicUnqualifiedThis.get.getNode.getElementType,
              PsiOutputRoleId.SelectionExpression   -> selectionReferences.head.getNode.getElementType,
              PsiOutputRoleId.GenericCall           -> nativeGenericCall.get.getNode.getElementType,
              PsiOutputRoleId.MethodCall            -> nativeCall.get.getNode.getElementType,
              PsiOutputRoleId.ArgumentExpressions   -> nativeArguments.get.getNode.getElementType,
              PsiOutputRoleId.NamedArgument         -> MetallurgyNamedArgument.ElementType,
              PsiOutputRoleId.TypedExpression       -> nativeRepeatedTyped.head.getNode.getElementType,
              PsiOutputRoleId.RepeatedStar          -> nativeRepeatedSequence.head.getNode.getElementType,
              PsiOutputRoleId.SuperReference        -> selectionSuperReferences.head.getNode.getElementType,
              PsiOutputRoleId.IntegerExpression     -> atomicInteger.get.getNode.getElementType,
              PsiOutputRoleId.LongExpression        -> atomicLong.get.getNode.getElementType,
              PsiOutputRoleId.FloatExpression       -> atomicFloat.get.getNode.getElementType,
              PsiOutputRoleId.DoubleExpression      -> atomicDouble.get.getNode.getElementType,
              PsiOutputRoleId.BooleanExpression     -> atomicBoolean.get.getNode.getElementType,
              PsiOutputRoleId.CharExpression        -> atomicChar.get.getNode.getElementType,
              PsiOutputRoleId.StringExpression      -> atomicString.get.getNode.getElementType,
              PsiOutputRoleId.NullExpression        -> atomicNull.get.getNode.getElementType,
              PsiOutputRoleId.ModifierList          -> annotatedModifiers.getNode.getElementType,
              PsiOutputRoleId.AccessModifier        -> accessModifiers.head.getNode.getElementType,
              PsiOutputRoleId.Annotations           -> annotationContainer.getNode.getElementType,
              PsiOutputRoleId.Annotation            -> annotations.head.getNode.getElementType,
              PsiOutputRoleId.AnnotationExpr        -> annotationExpressions.head.getNode.getElementType,
              PsiOutputRoleId.ConstructorInvocation -> constructorInvocations.head.getNode.getElementType,
              PsiOutputRoleId.AnnotationArguments   -> argumentLists.head.getNode.getElementType,
              PsiOutputRoleId.ExpressionPayload     -> MetallurgyExpressionPayload.ElementType,
              PsiOutputRoleId.NamedTypeArguments    -> namedListType,
              PsiOutputRoleId.NamedTypeArgument     -> namedEntryType,
              PsiOutputRoleId.ClassDefinition       -> classes.head.getNode.getElementType,
              PsiOutputRoleId.TraitDefinition       -> traits.head.getNode.getElementType,
              PsiOutputRoleId.ObjectDefinition      -> objects.head.getNode.getElementType,
              PsiOutputRoleId.EnumDefinition        -> enums.head.getNode.getElementType,
              PsiOutputRoleId.EnumCases             -> enumCases.head.getNode.getElementType,
              PsiOutputRoleId.EnumSingletonCase     -> enumSingletonCases.head.getNode.getElementType,
              PsiOutputRoleId.EnumClassCase         -> enumClassCases.head.getNode.getElementType,
              PsiOutputRoleId.ExtendsBlock          -> extendsBlocks.head.getNode.getElementType,
              PsiOutputRoleId.TemplateBody          -> templateBodies.head.getNode.getElementType,
              PsiOutputRoleId.PrimaryConstructor    -> primaryConstructors.head.getNode.getElementType,
              PsiOutputRoleId.ParameterClauses      -> parameterClauses.head.getNode.getElementType,
              PsiOutputRoleId.ParameterClause       -> parameterClause.head.getNode.getElementType,
              PsiOutputRoleId.TypeParameterClause   -> typeParameterClauses.head.getNode.getElementType,
              PsiOutputRoleId.TypeParameter         -> typeParameters.head.getNode.getElementType,
              PsiOutputRoleId.Parameter             -> parameters
                .find(!_.isInstanceOf[ScClassParameter])
                .get
                .getNode
                .getElementType,
              PsiOutputRoleId.ClassParameter        -> classParameters.head.getNode.getElementType,
              PsiOutputRoleId.ParameterType         -> parameterTypes.head.getNode.getElementType,
              PsiOutputRoleId.TemplateParents       -> templateParents.head.getNode.getElementType,
              PsiOutputRoleId.SelfType              -> selfTypes.head.getNode.getElementType,
              PsiOutputRoleId.DerivesClause         -> derivesClauses.head.getNode.getElementType,
              PsiOutputRoleId.FunctionDefinition    -> directFunction.getNode.getElementType,
              PsiOutputRoleId.FunctionDeclaration   -> functionDeclarations.head.getNode.getElementType,
              PsiOutputRoleId.PatternDefinition     -> directPattern.getNode.getElementType,
              PsiOutputRoleId.ValueDeclaration      -> valueDeclarations.head.getNode.getElementType,
              PsiOutputRoleId.VariableDefinition    -> directVariable.getNode.getElementType,
              PsiOutputRoleId.VariableDeclaration   -> variableDeclarations.head.getNode.getElementType,
              PsiOutputRoleId.PatternList           -> directPatternList.getNode.getElementType,
              PsiOutputRoleId.ReferencePattern      -> directReferencePattern.getNode.getElementType,
              PsiOutputRoleId.IdentifierList        -> identifierLists.head.getNode.getElementType,
              PsiOutputRoleId.FieldId               -> fieldIds.head.getNode.getElementType,
              PsiOutputRoleId.TypeAliasDeclaration  -> directTypeAlias.getNode.getElementType,
              PsiOutputRoleId.TypeAliasDefinition   -> typeAliasDefinitions.head.getNode.getElementType
            ),
            Map(
              PsiOutputRoleId.PackageStatement      -> surfaceId(packaging.getClass),
              PsiOutputRoleId.EndStatement          -> surfaceId(innerEnd.getClass),
              PsiOutputRoleId.ImportStatement       -> surfaceId(statements.head.getClass),
              PsiOutputRoleId.ExportStatement       -> surfaceId(exportStatements.head.getClass),
              PsiOutputRoleId.ImportExpression      -> surfaceId(expressions.head.getClass),
              PsiOutputRoleId.ImportSelectorSet     -> surfaceId(selectorSets.head.getClass),
              PsiOutputRoleId.ImportSelector        -> surfaceId(selectors.head.getClass),
              PsiOutputRoleId.StableReference       -> surfaceId(reference.getClass),
              PsiOutputRoleId.SimpleType            -> surfaceId(givenType.getClass),
              PsiOutputRoleId.SingletonType         -> surfaceId(singletonType.getClass),
              PsiOutputRoleId.TypeProjection        -> surfaceId(typeProjection.getClass),
              PsiOutputRoleId.LiteralType           -> surfaceId(integerLiteralType.getClass),
              PsiOutputRoleId.ParenthesizedType     -> surfaceId(parenthesizedType.getClass),
              PsiOutputRoleId.TupleType             -> surfaceId(tupleType.getClass),
              PsiOutputRoleId.TupleTypes            -> surfaceId(tupleTypes.getClass),
              PsiOutputRoleId.NamedTupleType        -> surfaceId(namedTupleType.getClass),
              PsiOutputRoleId.NamedTupleComponent   -> surfaceId(namedTupleComponents.head.getClass),
              PsiOutputRoleId.FunctionType          -> surfaceId(functionType.getClass),
              PsiOutputRoleId.DependentFunctionType -> surfaceId(dependentFunctionType.getClass),
              PsiOutputRoleId.PolyFunctionType      -> surfaceId(polyFunctionType.getClass),
              PsiOutputRoleId.IntegerLiteralValue   -> surfaceId(integerLiteralValue.getClass),
              PsiOutputRoleId.LongLiteralValue      -> surfaceId(longLiteralValue.getClass),
              PsiOutputRoleId.FloatLiteralValue     -> surfaceId(floatLiteralValue.getClass),
              PsiOutputRoleId.DoubleLiteralValue    -> surfaceId(doubleLiteralValue.getClass),
              PsiOutputRoleId.CharLiteralValue      -> surfaceId(charLiteralValue.getClass),
              PsiOutputRoleId.StringLiteralValue    -> surfaceId(stringLiteralValue.getClass),
              PsiOutputRoleId.BooleanLiteralValue   -> surfaceId(booleanLiteralValue.getClass),
              PsiOutputRoleId.ParameterizedType     -> surfaceId(parameterizedType.getClass),
              PsiOutputRoleId.TypeArguments         -> surfaceId(typeArguments.getClass),
              PsiOutputRoleId.WildcardType          -> surfaceId(wildcardType.getClass),
              PsiOutputRoleId.ContextBound          -> surfaceId(contextBound.getClass),
              PsiOutputRoleId.TypeLambda            -> surfaceId(typeLambda.getClass),
              PsiOutputRoleId.InfixType             -> surfaceId(infixType.getClass),
              PsiOutputRoleId.MatchType             -> surfaceId(matchType.getClass),
              PsiOutputRoleId.MatchTypeCases        -> surfaceId(matchTypeCases.getClass),
              PsiOutputRoleId.MatchTypeCase         -> surfaceId(matchCases.head.getClass),
              PsiOutputRoleId.MatchTypeVariable     -> surfaceId(matchTypeVariable.getClass),
              PsiOutputRoleId.CompoundType          -> surfaceId(compoundType.getClass),
              PsiOutputRoleId.Refinement            -> surfaceId(refinement.getClass),
              PsiOutputRoleId.AnnotatedType         -> surfaceId(annotatedType.getClass),
              PsiOutputRoleId.CaptureType           ->
                "org/jetbrains/plugins/scala/lang/psi/impl/base/types/ScCaptureTypeElementImpl",
              PsiOutputRoleId.CaptureSet            ->
                "org/jetbrains/plugins/scala/lang/psi/impl/base/types/cc/ScCaptureSetImpl",
              PsiOutputRoleId.CaptureReference      ->
                "org/jetbrains/plugins/scala/lang/psi/impl/base/types/cc/ScCaptureRefImpl",
              PsiOutputRoleId.CaptureFilter         ->
                "org/jetbrains/plugins/scala/lang/psi/impl/base/types/cc/ScCaptureFilterImpl",
              PsiOutputRoleId.TermReference         -> surfaceId(atomicReference.get.getClass),
              PsiOutputRoleId.ThisReference         -> surfaceId(atomicUnqualifiedThis.get.getClass),
              PsiOutputRoleId.SelectionExpression   -> surfaceId(selectionReferences.head.getClass),
              PsiOutputRoleId.GenericCall           -> surfaceId(nativeGenericCall.get.getClass),
              PsiOutputRoleId.MethodCall            -> surfaceId(nativeCall.get.getClass),
              PsiOutputRoleId.ArgumentExpressions   -> surfaceId(nativeArguments.get.getClass),
              PsiOutputRoleId.NamedArgument         ->
                "org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyNamedArgument",
              PsiOutputRoleId.TypedExpression       -> surfaceId(nativeRepeatedTyped.head.getClass),
              PsiOutputRoleId.RepeatedStar          -> surfaceId(nativeRepeatedSequence.head.getClass),
              PsiOutputRoleId.SuperReference        -> surfaceId(selectionSuperReferences.head.getClass),
              PsiOutputRoleId.IntegerExpression     -> surfaceId(atomicInteger.get.getClass),
              PsiOutputRoleId.LongExpression        -> surfaceId(atomicLong.get.getClass),
              PsiOutputRoleId.FloatExpression       -> surfaceId(atomicFloat.get.getClass),
              PsiOutputRoleId.DoubleExpression      -> surfaceId(atomicDouble.get.getClass),
              PsiOutputRoleId.BooleanExpression     -> surfaceId(atomicBoolean.get.getClass),
              PsiOutputRoleId.CharExpression        -> surfaceId(atomicChar.get.getClass),
              PsiOutputRoleId.StringExpression      -> surfaceId(atomicString.get.getClass),
              PsiOutputRoleId.NullExpression        -> surfaceId(atomicNull.get.getClass),
              PsiOutputRoleId.ModifierList          -> surfaceId(annotatedModifiers.getClass),
              PsiOutputRoleId.AccessModifier        -> surfaceId(accessModifiers.head.getClass),
              PsiOutputRoleId.Annotations           -> surfaceId(annotationContainer.getClass),
              PsiOutputRoleId.Annotation            -> surfaceId(annotations.head.getClass),
              PsiOutputRoleId.AnnotationExpr        -> surfaceId(annotationExpressions.head.getClass),
              PsiOutputRoleId.ConstructorInvocation -> surfaceId(constructorInvocations.head.getClass),
              PsiOutputRoleId.AnnotationArguments   -> surfaceId(argumentLists.head.getClass),
              PsiOutputRoleId.ExpressionPayload     ->
                "org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyExpressionPayload",
              PsiOutputRoleId.NamedTypeArguments    -> namedListSurface,
              PsiOutputRoleId.NamedTypeArgument     -> namedEntrySurface,
              PsiOutputRoleId.ClassDefinition       -> surfaceId(classes.head.getClass),
              PsiOutputRoleId.TraitDefinition       -> surfaceId(traits.head.getClass),
              PsiOutputRoleId.ObjectDefinition      -> surfaceId(objects.head.getClass),
              PsiOutputRoleId.EnumDefinition        -> surfaceId(enums.head.getClass),
              PsiOutputRoleId.EnumCases             -> surfaceId(enumCases.head.getClass),
              PsiOutputRoleId.EnumSingletonCase     -> surfaceId(enumSingletonCases.head.getClass),
              PsiOutputRoleId.EnumClassCase         -> surfaceId(enumClassCases.head.getClass),
              PsiOutputRoleId.ExtendsBlock          -> surfaceId(extendsBlocks.head.getClass),
              PsiOutputRoleId.TemplateBody          -> surfaceId(templateBodies.head.getClass),
              PsiOutputRoleId.PrimaryConstructor    -> surfaceId(primaryConstructors.head.getClass),
              PsiOutputRoleId.ParameterClauses      -> surfaceId(parameterClauses.head.getClass),
              PsiOutputRoleId.ParameterClause       -> surfaceId(parameterClause.head.getClass),
              PsiOutputRoleId.TypeParameterClause   -> surfaceId(typeParameterClauses.head.getClass),
              PsiOutputRoleId.TypeParameter         -> surfaceId(typeParameters.head.getClass),
              PsiOutputRoleId.Parameter             -> surfaceId(parameters.find(!_.isInstanceOf[ScClassParameter]).get.getClass),
              PsiOutputRoleId.ClassParameter        -> surfaceId(classParameters.head.getClass),
              PsiOutputRoleId.ParameterType         -> surfaceId(parameterTypes.head.getClass),
              PsiOutputRoleId.TemplateParents       -> surfaceId(templateParents.head.getClass),
              PsiOutputRoleId.SelfType              -> surfaceId(selfTypes.head.getClass),
              PsiOutputRoleId.DerivesClause         -> surfaceId(derivesClauses.head.getClass),
              PsiOutputRoleId.FunctionDefinition    -> surfaceId(directFunction.getClass),
              PsiOutputRoleId.FunctionDeclaration   -> surfaceId(functionDeclarations.head.getClass),
              PsiOutputRoleId.PatternDefinition     -> surfaceId(directPattern.getClass),
              PsiOutputRoleId.ValueDeclaration      -> surfaceId(valueDeclarations.head.getClass),
              PsiOutputRoleId.VariableDefinition    -> surfaceId(directVariable.getClass),
              PsiOutputRoleId.VariableDeclaration   -> surfaceId(variableDeclarations.head.getClass),
              PsiOutputRoleId.PatternList           -> surfaceId(directPatternList.getClass),
              PsiOutputRoleId.ReferencePattern      -> surfaceId(directReferencePattern.getClass),
              PsiOutputRoleId.IdentifierList        -> surfaceId(identifierLists.head.getClass),
              PsiOutputRoleId.FieldId               -> surfaceId(fieldIds.head.getClass),
              PsiOutputRoleId.TypeAliasDeclaration  -> surfaceId(directTypeAlias.getClass),
              PsiOutputRoleId.TypeAliasDefinition   -> surfaceId(typeAliasDefinitions.head.getClass)
            ),
            Vector(
              ScalaPsiSurfaceRow(
                EndKeywordTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native end keyword token")
              ),
              ScalaPsiSurfaceRow(
                ImportWildcardTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native import wildcard token")
              ),
              ScalaPsiSurfaceRow(
                ImportLegacyWildcardTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native legacy import wildcard token")
              ),
              ScalaPsiSurfaceRow(
                ImportAliasAsTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native Scala 3 import alias token")
              ),
              ScalaPsiSurfaceRow(
                ImportAliasArrowTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native Scala 2 import alias token")
              ),
              ScalaPsiSurfaceRow(
                WildcardQuestionTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native wildcard type token")
              ),
              ScalaPsiSurfaceRow(
                LowerTypeBoundTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native lower type-bound token")
              ),
              ScalaPsiSurfaceRow(
                UpperTypeBoundTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native upper type-bound token")
              ),
              ScalaPsiSurfaceRow(
                VarianceTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native variance token")
              ),
              ScalaPsiSurfaceRow(
                ContextBoundColonTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native context-bound colon token")
              ),
              ScalaPsiSurfaceRow(
                ContextBoundLeftBraceTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native context-bound left-brace token")
              ),
              ScalaPsiSurfaceRow(
                ContextBoundRightBraceTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native context-bound right-brace token")
              ),
              ScalaPsiSurfaceRow(
                ContextBoundCommaTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native context-bound comma token")
              ),
              ScalaPsiSurfaceRow(
                ContextBoundAsTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native context-bound as token")
              ),
              ScalaPsiSurfaceRow(
                AssignmentTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native assignment token")
              ),
              ScalaPsiSurfaceRow(
                ValueKeywordTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native value keyword token")
              ),
              ScalaPsiSurfaceRow(
                IntegerLiteralTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native integer literal token")
              ),
              ScalaPsiSurfaceRow(
                LongLiteralTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native long literal token")
              ),
              ScalaPsiSurfaceRow(
                FloatLiteralTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native float literal token")
              ),
              ScalaPsiSurfaceRow(
                DoubleLiteralTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native double literal token")
              ),
              ScalaPsiSurfaceRow(
                CharLiteralTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native character literal token")
              ),
              ScalaPsiSurfaceRow(
                StringLiteralTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native string literal token")
              ),
              ScalaPsiSurfaceRow(
                TypePathDotTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native type path token")
              ),
              ScalaPsiSurfaceRow(
                TypeProjectionHashTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native type projection token")
              ),
              ScalaPsiSurfaceRow(
                SingletonTypeKeywordTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native singleton type keyword token")
              ),
              ScalaPsiSurfaceRow(
                TypeArgumentLeftTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native type left bracket token")
              ),
              ScalaPsiSurfaceRow(
                TypeArgumentRightTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native type right bracket token")
              ),
              ScalaPsiSurfaceRow(
                TypeLeftParenthesisTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native type left parenthesis token")
              ),
              ScalaPsiSurfaceRow(
                TypeRightParenthesisTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native type right parenthesis token")
              ),
              ScalaPsiSurfaceRow(
                UsingKeywordTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native using keyword token")
              ),
              ScalaPsiSurfaceRow(
                FunctionArrowTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native function arrow token")
              ),
              ScalaPsiSurfaceRow(
                ContextFunctionArrowTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native context function arrow token")
              ),
              ScalaPsiSurfaceRow(
                PureFunctionArrowTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native pure function arrow token")
              ),
              ScalaPsiSurfaceRow(
                ContextPureFunctionArrowTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native context pure function arrow token")
              ),
              ScalaPsiSurfaceRow(
                MatchKeywordTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native match keyword token")
              ),
              ScalaPsiSurfaceRow(
                CaseKeywordTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native case keyword token")
              ),
              ScalaPsiSurfaceRow(
                SemicolonTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native semicolon token")
              ),
              ScalaPsiSurfaceRow(
                CaptureOperatorTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native capture operator token")
              ),
              ScalaPsiSurfaceRow(
                CaptureReachTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native reach capability token")
              ),
              ScalaPsiSurfaceRow(
                CaptureReadOnlyTokenSurface,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native read-only capability token")
              )
            ) ++ ModifierTokenSurfaceIds.toVector.sortBy(_._1).map { (prefix, id) =>
              ScalaPsiSurfaceRow(
                id,
                SurfaceFactKind.Token,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector(s"capability-probed native ${prefix.toLowerCase} modifier token")
              )
            } ++ Vector(
              ScalaPsiSurfaceRow(
                ImportPersistenceSurfaces.AliasedImportIndex,
                SurfaceFactKind.Index,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native aliased import index")
              ),
              ScalaPsiSurfaceRow(
                ExportPersistenceSurfaces.TopLevelPackageIndex,
                SurfaceFactKind.Index,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native top-level export package index")
              ),
              ScalaPsiSurfaceRow(
                PackagePersistenceSurfaces.FqnIndex,
                SurfaceFactKind.Index,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native package index")
              ),
              ScalaPsiSurfaceRow(
                AnnotatedMemberIndexSurface,
                SurfaceFactKind.Index,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native annotated member index")
              ),
              ScalaPsiSurfaceRow(
                ImportPersistenceSurfaces.SelfNavigation,
                SurfaceFactKind.Navigation,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed self navigation identity")
              )
            ) ++ Vector(
              DefinitionPersistenceSurfaces.MethodNameIndex,
              DefinitionPersistenceSurfaces.TopLevelFunctionIndex,
              DefinitionPersistenceSurfaces.PropertyNameIndex,
              DefinitionPersistenceSurfaces.TopLevelPropertyIndex,
              DefinitionPersistenceSurfaces.TypeAliasNameIndex,
              DefinitionPersistenceSurfaces.TopLevelTypeAliasIndex
            ).map(id =>
              ScalaPsiSurfaceRow(
                id,
                SurfaceFactKind.Index,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed native definition index")
              )
            ) ++ TemplatePersistenceSurfaces.DefinitionIndices
              .appended(TemplatePersistenceSurfaces.SuperClassNameIndex)
              .appended(TemplatePersistenceSurfaces.SelfTypeClassNameIndex)
              .distinct
              .sorted
              .map(id =>
                ScalaPsiSurfaceRow(
                  id,
                  SurfaceFactKind.Index,
                  None,
                  FactStatus.Available,
                  SurfaceClassification.SyntaxContract,
                  Vector("capability-probed native template index")
                )
              ),
            Map.empty,
            Option
              .unless(nativeNamedAssignmentContract)(
                Set(
                  Scala3PsiNamedArgumentProductions.CandidateProductionId ->
                    Scala3PsiNamedArgumentProductions.NativeRealizationId
                )
              )
              .getOrElse(Set.empty) ++ Option
              .unless(nativeRepeatedContract)(
                Set(
                  Scala3PsiRepeatedArgumentProductions.CandidateProductionId ->
                    Scala3PsiRepeatedArgumentProductions.NativeRealizationId
                )
              )
              .getOrElse(Set.empty)
          )
        )

  private def surfaceId(value: Class[?]): String = value.getName.replace('.', '/')

  private def probeNativeNamedTypeArguments(project: Project): Either[String, Option[NativeNamedTypeArguments]] =
    val source =
      """import scala.language.experimental.namedTypeArguments
        |def pair[A, B]: A = ???
        |val value = pair[A /*left*/ = /*right*/ Int, B = String]
        |""".stripMargin
    val file   = PsiFileFactory
      .getInstance(project)
      .createFileFromText("NativeNamedTypeArgumentBindingProbe.scala", Scala3Language.INSTANCE, source)
    val errors = PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).asScala.toVector
    if errors.nonEmpty then Right(None)
    else
      val lists = PsiTreeUtil
        .findChildrenOfType(file, classOf[ScTypeArgs])
        .asScala
        .filter(_.getText == "[A /*left*/ = /*right*/ Int, B = String]")
        .toVector
      lists match
        case Vector(list) =>
          val entries =
            list.getChildren.toVector.filter(value => Set("A /*left*/ = /*right*/ Int", "B = String")(value.getText))
          entries match
            case Vector(firstEntry, secondEntry)
                if entries.map(_.getText) == Vector("A /*left*/ = /*right*/ Int", "B = String") &&
                  list.getNode.getChildren(null).toVector.map(_.getText) ==
                  Vector("[", "A /*left*/ = /*right*/ Int", ",", " ", "B = String", "]") &&
                  firstEntry.getNode.getChildren(null).toVector.map(_.getText) ==
                  Vector("A", " ", "/*left*/", " ", "=", " ", "/*right*/", " ", "Int") &&
                  secondEntry.getNode.getChildren(null).toVector.map(_.getText) ==
                  Vector("B", " ", "=", " ", "String") =>
              val references   = entries.map(_.getChildren.toVector.collect { case value: ScStableCodeReference =>
                value
              })
              val types        = entries.map(_.getChildren.toVector.collect { case value: ScTypeElement => value })
              val listMethods  = list.getClass.getMethods
                .filter(_.getParameterCount == 0)
                .groupBy(_.getName)
                .view
                .mapValues(_.head)
                .toMap
              val entryMethods = entries.map(entry =>
                entry.getClass.getMethods
                  .filter(_.getParameterCount == 0)
                  .groupBy(_.getName)
                  .view
                  .mapValues(_.head)
                  .toMap
              )
              if references.exists(_.size != 1) || types.exists(_.size != 1) || entries.exists(_.getParent != list) ||
                entries.map(_.getClass).distinct.size != 1 ||
                entries.map(_.getNode.getElementType).distinct.size != 1 ||
                !Set("typeArguments", "namedTypeArgs", "hasNamedTypeArgs", "getArgsCount").subsetOf(
                  listMethods.keySet
                ) ||
                entryMethods.exists(methods =>
                  !Set("name", "nameElement", "typeElement", "isNamed", "type").subsetOf(methods.keySet)
                ) || entries.indices.exists(index =>
                  entries(index).getChildren.toVector.collect {
                    case value: org.jetbrains.plugins.scala.lang.psi.api.ScalaPsiElement => value
                  } != Vector(references(index).head, types(index).head)
                ) || entries.exists(entry =>
                  source.substring(entry.getTextRange.getStartOffset, entry.getTextRange.getEndOffset) != entry.getText
                )
              then Right(None)
              else
                try
                  val logical  = listMethods("typeArguments").invoke(list).asInstanceOf[scala.collection.Iterable[?]]
                  val named    = listMethods("namedTypeArgs").invoke(list).asInstanceOf[scala.collection.Iterable[?]]
                  val hasNamed = listMethods("hasNamedTypeArgs").invoke(list)
                  val count    = listMethods("getArgsCount").invoke(list)
                  val names    = entryMethods.zip(entries).map((methods, entry) => methods("name").invoke(entry))
                  val namePsi  = entryMethods.zip(entries).map((methods, entry) => methods("nameElement").invoke(entry))
                  val typePsi  = entryMethods.zip(entries).map((methods, entry) => methods("typeElement").invoke(entry))
                  val isNamed  = entryMethods.zip(entries).map((methods, entry) => methods("isNamed").invoke(entry))
                  val tpes     = entryMethods.zip(entries).map((methods, entry) => methods("type").invoke(entry))
                  Right(
                    Option.when(
                      logical.iterator.toVector == entries && named.iterator.toVector == entries &&
                        hasNamed == java.lang.Boolean.TRUE && count == Integer.valueOf(entries.size) &&
                        names == Vector(Some("A"), Some("B")) &&
                        namePsi == references.map(value => Some(value.head)) &&
                        typePsi == types.map(value => Some(value.head)) &&
                        isNamed.forall(_ == java.lang.Boolean.TRUE) &&
                        tpes.forall(_.isInstanceOf[scala.util.Either[?, ?]])
                    )(NativeNamedTypeArguments(list, firstEntry))
                  )
                catch case NonFatal(_) => Right(None)
            case _ => Right(None)
        case _            => Right(None)

  private def probePersistence(file: com.intellij.psi.PsiFile): Either[String, Unit] =
    try
      val tree                                                               = file.asInstanceOf[PsiFileImpl].calcStubTree
      val stubs                                                              = tree.getPlainList.asScala
      val packaging                                                          = stubs.collectFirst { case value: ScPackagingStub => value }
      val statement                                                          = stubs.collectFirst { case value: ScImportStmtStub => value }
      val exportStatement                                                    = stubs.collectFirst { case value: ScExportStmtStub => value }
      val expression                                                         = stubs.collectFirst { case value: ScImportExprStub if value.hasWildcardSelector => value }
      val selectorSet                                                        = stubs.collectFirst { case value: ScImportSelectorsStub if value.hasWildcard => value }
      val selector                                                           = stubs.collectFirst { case value: ScImportSelectorStub if value.isAliasedImport => value }
      val exportSelector                                                     = stubs.collectFirst {
        case value: ScImportSelectorStub if value.isAliasedImport && value.aliasName.contains("Exported") => value
      }
      val givenSelectorStub                                                  = stubs.collectFirst {
        case value: ScImportSelectorStub if value.isGivenSelector && value.typeText.nonEmpty => value
      }
      val modifierStubs                                                      = stubs.collect { case value: ScModifiersStub => value }
      val privateAccessStub                                                  = stubs.collectFirst {
        case value: ScAccessModifierStub if value.isPrivate && value.idText.contains("scope") => value
      }
      val protectedAccessStub                                                = stubs.collectFirst {
        case value: ScAccessModifierStub if value.isProtected && value.isThis => value
      }
      val annotationStubs                                                    = stubs.collect { case value: ScAnnotationStub => value }
      val annotationsStubs                                                   = stubs.collect { case value: ScAnnotationsStub => value }
      val deprecatedAnnotationStub                                           = annotationStubs.find(
        _.annotationText == "deprecated(\"m\", \"1\")"
      )
      def templateShape(values: Iterable[Stub]): Vector[String]              = values.iterator
        .flatMap: stub =>
          Option(stub.getStubSerializer).map: serializer =>
            val detail = stub match
              case value: ScTemplateDefinitionStub[?] =>
                Vector(
                  value.getName,
                  value.getQualifiedName,
                  value.javaName,
                  value.javaQualifiedName,
                  value.additionalJavaName.toString,
                  value.isPackageObject.toString,
                  value.isDeprecated.toString,
                  value.isLocal.toString,
                  value.isVisibleInJava.toString,
                  value.isImplicitObject.toString,
                  value.isTopLevel.toString,
                  value.topLevelQualifier.toString,
                  value.isGiven.toString,
                  value.enumClassCaseMentionsParentTypeParams.toString
                ).map(value => Option(value).getOrElse("<null>")).mkString("[", ",", "]")
              case value: ScExtendsBlockStub          => value.baseClasses.mkString("[", ",", "]")
              case _                                  => ""
            s"${stub.getClass.getName}|${serializer.getExternalId}|$detail"
        .toVector
      def indexShape(values: Iterable[Stub]): Vector[String]                 =
        val result = Vector.newBuilder[String]
        val sink   = new IndexSink:
          override def occurrence[Psi <: com.intellij.psi.PsiElement, K](
              indexKey: StubIndexKey[K, Psi],
              value: K
          ): Unit = result += s"${indexKey.toString}|${value.toString}"
        values.foreach(stub =>
          Option(stub.getStubSerializer).foreach(
            _.asInstanceOf[ObjectStubSerializer[Stub, Stub]].indexStub(stub, sink)
          )
        )
        result.result()
      def templateIndexSurface(indexKey: StubIndexKey[?, ?]): Option[String] =
        if indexKey == ScalaIndexKeys.SHORT_NAME_KEY then Some(TemplatePersistenceSurfaces.ShortNameIndex)
        else if indexKey == ScalaIndexKeys.CLASS_FQN_KEY then Some(TemplatePersistenceSurfaces.ClassFqnIndex)
        else if indexKey == ScalaIndexKeys.CLASS_NAME_IN_PACKAGE_KEY then
          Some(TemplatePersistenceSurfaces.ClassNameInPackageIndex)
        else if indexKey == JavaStubIndexKeys.CLASS_SHORT_NAMES then
          Some(TemplatePersistenceSurfaces.JavaClassShortNameIndex)
        else if indexKey == JavaStubIndexKeys.CLASS_FQN then Some(TemplatePersistenceSurfaces.JavaClassFqnIndex)
        else if indexKey == ScalaIndexKeys.NOT_VISIBLE_IN_JAVA_SHORT_NAME_KEY then
          Some(TemplatePersistenceSurfaces.NotVisibleInJavaIndex)
        else if indexKey == ScalaIndexKeys.ALL_CLASS_NAMES then Some(TemplatePersistenceSurfaces.AllClassNamesIndex)
        else if indexKey == ScalaIndexKeys.JAVA_CLASS_NAME_IN_PACKAGE_KEY then
          Some(TemplatePersistenceSurfaces.JavaClassNameInPackageIndex)
        else if indexKey == ScalaIndexKeys.SUPER_CLASS_NAME_KEY then
          Some(TemplatePersistenceSurfaces.SuperClassNameIndex)
        else if indexKey == ScalaIndexKeys.SELF_TYPE_CLASS_NAME_KEY then
          Some(TemplatePersistenceSurfaces.SelfTypeClassNameIndex)
        else None
      def templateIndexShape(values: Iterable[Stub]): Set[(String, String)]  =
        val result = Set.newBuilder[(String, String)]
        val sink   = new IndexSink:
          override def occurrence[Psi <: com.intellij.psi.PsiElement, K](
              indexKey: StubIndexKey[K, Psi],
              value: K
          ): Unit = templateIndexSurface(indexKey).foreach(surface => result += surface -> value.toString)
        values.foreach(stub =>
          Option(stub.getStubSerializer).foreach(
            _.asInstanceOf[ObjectStubSerializer[Stub, Stub]].indexStub(stub, sink)
          )
        )
        result.result()
      val serializedTreeOutput                                               = new ByteArrayOutputStream
      SerializationManagerEx.getInstanceEx.serialize(tree.getRoot, serializedTreeOutput)
      val restoredTree                                                       = new StubTree(
        SerializationManagerEx.getInstanceEx
          .deserialize(new ByteArrayInputStream(serializedTreeOutput.toByteArray))
          .asInstanceOf[PsiFileStub[?]]
      )
      val restoredStubs                                                      = restoredTree.getPlainList.asScala
      val templateIndices                                                    = templateIndexShape(stubs)
      val expectedTemplateIndices                                            = Set(
        TemplatePersistenceSurfaces.ShortNameIndex              -> "EmptyConstructor",
        TemplatePersistenceSurfaces.ClassFqnIndex               -> "example.syntax.EmptyConstructor",
        TemplatePersistenceSurfaces.ClassNameInPackageIndex     -> "example.syntax",
        TemplatePersistenceSurfaces.JavaClassShortNameIndex     -> "EmptyConstructor",
        TemplatePersistenceSurfaces.JavaClassFqnIndex           -> "example.syntax.EmptyConstructor",
        TemplatePersistenceSurfaces.NotVisibleInJavaIndex       -> "ObjectProbe",
        TemplatePersistenceSurfaces.AllClassNamesIndex          -> "EmptyConstructor",
        TemplatePersistenceSurfaces.JavaClassNameInPackageIndex -> "example.syntax",
        TemplatePersistenceSurfaces.SuperClassNameIndex         -> "AnyRef",
        TemplatePersistenceSurfaces.SuperClassNameIndex         -> "TraitProbe",
        TemplatePersistenceSurfaces.SelfTypeClassNameIndex      -> "TraitProbe"
      )
      val templatePersistence                                                =
        templateShape(stubs) == templateShape(restoredStubs) && indexShape(stubs) == indexShape(restoredStubs) &&
          templateIndices == templateIndexShape(restoredStubs) &&
          expectedTemplateIndices.subsetOf(templateIndices) &&
          templateIndices.map(_._1) ==
          (TemplatePersistenceSurfaces.DefinitionIndices ++ Vector(
            TemplatePersistenceSurfaces.SuperClassNameIndex,
            TemplatePersistenceSurfaces.SelfTypeClassNameIndex
          )).toSet &&
          TemplatePersistenceSurfaces.ExternalIds.values.toSet.subsetOf(
            stubs.flatMap(stub => Option(stub.getStubSerializer).map(_.getExternalId)).toSet
          )
      val enumerator                                                         = new AbstractStringEnumerator:
        private val values                         = collection.mutable.ArrayBuffer.empty[String]
        override def enumerate(value: String): Int =
          val found = values.indexOf(value)
          if found >= 0 then found + 1 else values.addOne(value).size
        override def valueOf(id: Int): String      = values(id - 1)
        override def isDirty: Boolean              = false
        override def force(): Unit                 = ()
        override def markCorrupted(): Unit         = ()
        override def close(): Unit                 = ()
      def bytes(write: StubOutputStream => Unit): Array[Byte]                =
        val sink   = new ByteArrayOutputStream
        val output = new StubOutputStream(sink, enumerator)
        write(output)
        output.flush()
        sink.toByteArray
      def input(serialized: Array[Byte]): StubInputStream                    =
        new StubInputStream(new ByteArrayInputStream(serialized), enumerator)
      val actualTypes                                                        = packaging.exists(_.getElementType eq ScalaElementType.PACKAGING) &&
        statement.exists(_.getElementType eq ScalaElementType.ImportStatement) &&
        exportStatement.exists(_.getElementType eq ScalaElementType.ExportStatement) &&
        expression.exists(_.getElementType eq ScalaElementType.IMPORT_EXPR) &&
        selectorSet.exists(_.getElementType eq ScalaElementType.IMPORT_SELECTORS) &&
        selector.exists(_.getElementType eq ScalaElementType.IMPORT_SELECTOR) && modifierStubs.nonEmpty &&
        modifierStubs.forall(_.getElementType eq ScalaElementType.MODIFIERS) &&
        privateAccessStub.exists(_.getElementType eq ScalaElementType.ACCESS_MODIFIER) &&
        protectedAccessStub.exists(_.getElementType eq ScalaElementType.ACCESS_MODIFIER) && annotationsStubs.nonEmpty &&
        annotationsStubs.forall(_.getElementType eq ScalaElementType.ANNOTATIONS) && annotationStubs.nonEmpty &&
        annotationStubs.forall(_.getElementType eq ScalaElementType.ANNOTATION)
      val packagingCopy                                                      = packaging.map(stub =>
        ScalaElementType.PACKAGING.deserialize(
          input(bytes(ScalaElementType.PACKAGING.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val statementCopy                                                      = statement.map(stub =>
        ScalaElementType.ImportStatement.deserialize(
          input(bytes(ScalaElementType.ImportStatement.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val exportStatementCopy                                                = exportStatement.map(stub =>
        ScalaElementType.ExportStatement.deserialize(
          input(bytes(ScalaElementType.ExportStatement.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val expressionCopy                                                     = expression.map(stub =>
        ScalaElementType.IMPORT_EXPR.deserialize(
          input(bytes(ScalaElementType.IMPORT_EXPR.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val selectorSetCopy                                                    = selectorSet.map(stub =>
        ScalaElementType.IMPORT_SELECTORS.deserialize(
          input(bytes(ScalaElementType.IMPORT_SELECTORS.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val selectorCopy                                                       = selector.map(stub =>
        ScalaElementType.IMPORT_SELECTOR.deserialize(
          input(bytes(ScalaElementType.IMPORT_SELECTOR.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val exportSelectorCopy                                                 = exportSelector.map(stub =>
        ScalaElementType.IMPORT_SELECTOR.deserialize(
          input(bytes(ScalaElementType.IMPORT_SELECTOR.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val givenCopy                                                          = givenSelectorStub.map(stub =>
        ScalaElementType.IMPORT_SELECTOR.deserialize(
          input(bytes(ScalaElementType.IMPORT_SELECTOR.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val modifierCopies                                                     = modifierStubs.map(stub =>
        ScalaElementType.MODIFIERS.deserialize(
          input(bytes(ScalaElementType.MODIFIERS.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val privateAccessCopy                                                  = privateAccessStub.map(stub =>
        ScalaElementType.ACCESS_MODIFIER.deserialize(
          input(bytes(ScalaElementType.ACCESS_MODIFIER.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val protectedAccessCopy                                                = protectedAccessStub.map(stub =>
        ScalaElementType.ACCESS_MODIFIER.deserialize(
          input(bytes(ScalaElementType.ACCESS_MODIFIER.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val annotationCopies                                                   = annotationStubs.map(stub =>
        ScalaElementType.ANNOTATION.deserialize(
          input(bytes(ScalaElementType.ANNOTATION.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val annotationsCopies                                                  = annotationsStubs.map(stub =>
        ScalaElementType.ANNOTATIONS.deserialize(
          input(bytes(ScalaElementType.ANNOTATIONS.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val serialized                                                         = packaging
        .zip(packagingCopy)
        .exists((before, after) =>
          before.packageName == after.packageName && before.parentPackageName == after.parentPackageName &&
            before.isExplicit == after.isExplicit
        ) && statement.zip(statementCopy).exists((before, after) => before.importText == after.importText) &&
        exportStatement
          .zip(exportStatementCopy)
          .exists((before, after) =>
            before.importText == after.importText && before.isTopLevel == after.isTopLevel &&
              before.topLevelQualifier == after.topLevelQualifier
          ) && expression
          .zip(expressionCopy)
          .exists((before, after) =>
            before.referenceText == after.referenceText && before.hasWildcardSelector == after.hasWildcardSelector &&
              before.hasGivenSelector == after.hasGivenSelector
          ) && selectorSet.zip(selectorSetCopy).exists((before, after) => before.hasWildcard == after.hasWildcard) &&
        selector
          .zip(selectorCopy)
          .exists((before, after) =>
            before.isAliasedImport == after.isAliasedImport && before.isWildcardSelector == after.isWildcardSelector &&
              before.isGivenSelector == after.isGivenSelector && before.referenceText == after.referenceText &&
              before.importedName == after.importedName && before.aliasName == after.aliasName &&
              before.typeText == after.typeText
          ) && exportSelector
          .zip(exportSelectorCopy)
          .exists((before, after) =>
            before.isAliasedImport == after.isAliasedImport && before.referenceText == after.referenceText &&
              before.importedName == after.importedName && before.aliasName == after.aliasName
          ) && givenSelectorStub
          .zip(givenCopy)
          .exists((before, after) =>
            before.isGivenSelector == after.isGivenSelector && before.typeText == after.typeText
          ) && modifierStubs.nonEmpty && modifierStubs.map(_.modifiers) == modifierCopies.map(_.modifiers) &&
        privateAccessStub
          .zip(privateAccessCopy)
          .exists((before, after) =>
            before.isPrivate == after.isPrivate && before.isProtected == after.isProtected &&
              before.isThis == after.isThis && before.idText == after.idText
          ) && protectedAccessStub
          .zip(protectedAccessCopy)
          .exists((before, after) =>
            before.isPrivate == after.isPrivate && before.isProtected == after.isProtected &&
              before.isThis == after.isThis && before.idText == after.idText
          ) && annotationsStubs.size == annotationsCopies.size &&
        annotationsCopies.forall(
          _.getElementType eq ScalaElementType.ANNOTATIONS
        ) && annotationStubs.nonEmpty && annotationStubs
          .zip(annotationCopies)
          .forall((before, after) => before.name == after.name && before.annotationText == after.annotationText)
      var importAliasIndexed                                                 = false
      selectorCopy.foreach(stub =>
        ScalaElementType.IMPORT_SELECTOR.indexStub(
          stub,
          new IndexSink:
            override def occurrence[Psi <: com.intellij.psi.PsiElement, K](
                indexKey: StubIndexKey[K, Psi],
                value: K
            ): Unit = importAliasIndexed ||= indexKey == ScalaIndexKeys.ALIASED_IMPORT_KEY && value == "Original"
        )
      )
      var exportAliasIndexed                                                 = false
      exportSelectorCopy.foreach(stub =>
        ScalaElementType.IMPORT_SELECTOR.indexStub(
          stub,
          new IndexSink:
            override def occurrence[Psi <: com.intellij.psi.PsiElement, K](
                indexKey: StubIndexKey[K, Psi],
                value: K
            ): Unit = exportAliasIndexed ||= indexKey == ScalaIndexKeys.ALIASED_IMPORT_KEY && value == "Original"
        )
      )
      val packages                                                           = Vector.newBuilder[String]
      packagingCopy.foreach(stub =>
        ScalaElementType.PACKAGING.indexStub(
          stub,
          new IndexSink:
            override def occurrence[Psi <: com.intellij.psi.PsiElement, K](
                indexKey: StubIndexKey[K, Psi],
                value: K
            ): Unit =
              if indexKey == ScalaIndexKeys.PACKAGE_FQN_KEY then packages += value.toString
        )
      )
      val exportPackages                                                     = Vector.newBuilder[String]
      exportStatementCopy.foreach(stub =>
        ScalaElementType.ExportStatement.indexStub(
          stub,
          new IndexSink:
            override def occurrence[Psi <: com.intellij.psi.PsiElement, K](
                indexKey: StubIndexKey[K, Psi],
                value: K
            ): Unit =
              if indexKey == ScalaIndexKeys.TOP_LEVEL_EXPORT_BY_PKG_KEY then exportPackages += value.toString
        )
      )
      var deprecatedAnnotationIndexed                                        = false
      val annotationIndexOccurrences                                         = Vector.newBuilder[(String, String)]
      deprecatedAnnotationStub.foreach(stub =>
        ScalaElementType.ANNOTATION.indexStub(
          stub,
          new IndexSink:
            override def occurrence[Psi <: com.intellij.psi.PsiElement, K](
                indexKey: StubIndexKey[K, Psi],
                value: K
            ): Unit =
              annotationIndexOccurrences += indexKey.toString -> value.toString
              deprecatedAnnotationIndexed ||=
                indexKey == ScalaIndexKeys.ANNOTATED_MEMBER_KEY && value.toString == "deprecated"
        )
      )
      Either.cond(
        actualTypes && serialized && templatePersistence && importAliasIndexed && exportAliasIndexed &&
          deprecatedAnnotationIndexed &&
          packages.result() == Vector("example.syntax", "example") &&
          exportPackages.result() == Vector("example.syntax"),
        (),
        s"native package/import/export persistence contracts are inconsistent: actualTypes=$actualTypes, " +
          s"serialized=$serialized, templatePersistence=$templatePersistence, importAlias=$importAliasIndexed, " +
          s"exportAlias=$exportAliasIndexed, " +
          s"annotation=$deprecatedAnnotationIndexed, names=${annotationStubs.map(_.name)}, " +
          s"texts=${annotationStubs.map(_.annotationText)}, occurrences=${annotationIndexOccurrences.result()}"
      )
    catch case NonFatal(error) => Left(s"native persistence probe failed: ${error.getClass.getSimpleName}")

  private def leafAtText(
      selector: com.intellij.psi.PsiElement,
      text: String
  ) =
    val offset = selector.getText.indexOf(text)
    Option.when(offset >= 0)(selector.getNode.findLeafElementAt(offset)).flatMap(node => Option(node.getPsi))
