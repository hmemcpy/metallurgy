package com.hmemcpy.metallurgy.psiproducer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.impl.java.stubs.index.JavaStubIndexKeys
import com.intellij.psi.stubs.*
import com.intellij.psi.{PsiFileFactory, PsiManager}
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.io.AbstractStringEnumerator
import org.jetbrains.plugins.scala.Scala3Language
import org.jetbrains.plugins.scala.lang.lexer.{ScalaTokenType, ScalaTokenTypes}
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType
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
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{ScParameterClause, ScParameters}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.{ScExtendsBlock, ScTemplateBody}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScClass, ScEnum, ScObject, ScTrait}
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScArgumentExprList, ScExpression}
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScIntegerLiteral
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{
  ScInfixTypeElement,
  ScParameterizedTypeElement,
  ScSimpleTypeElement,
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
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.{MetallurgyExpressionPayload, MetallurgyIntegerLiteral}

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
    outputContracts: Map[PsiOutputRoleId, NativeOutputContract] = Map.empty
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
  val EndKeywordTokenSurface           = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#EndKeyword"
  val ImportWildcardTokenSurface       = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#WildcardStar"
  val ImportLegacyWildcardTokenSurface = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tUNDER"
  val ImportAliasAsTokenSurface        = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#ImportAliasAs"
  val ImportAliasArrowTokenSurface     = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#ImportAliasArrow"
  val TypeArgumentLeftTokenSurface     = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tLSQBRACKET"
  val TypeArgumentRightTokenSurface    = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tRSQBRACKET"
  val WildcardQuestionTokenSurface     =
    "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#WildcardTypeQuestionMark"
  val LowerTypeBoundTokenSurface       = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tLOWER_BOUND"
  val UpperTypeBoundTokenSurface       = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tUPPER_BOUND"
  val AnnotatedMemberIndexSurface      =
    "org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys#ANNOTATED_MEMBER_KEY"
  val ModifierKeywordSurfaceIds        = Map(
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
  val AccessModifierKeywordSurfaceIds  = Map(
    "Private"   -> "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#kPRIVATE",
    "Protected" -> "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#kPROTECTED"
  )
  private val ModifierTokenSurfaceIds  = ModifierKeywordSurfaceIds ++ AccessModifierKeywordSurfaceIds

  def probe(project: Project): Either[String, NativePsiElementBindings] =
    if ApplicationManager.getApplication.isReadAccessAllowed then probeInReadAction(project)
    else
      ApplicationManager.getApplication.runReadAction(
        new Computable[Either[String, NativePsiElementBindings]]:
          override def compute(): Either[String, NativePsiElementBindings] = probeInReadAction(project)
      )

  private def probeInReadAction(project: Project): Either[String, NativePsiElementBindings] =
    val file                   = PsiFileFactory
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
          |trait TraitProbe
          |object ObjectProbe
          |enum EnumProbe:
          |  case Singleton
          |  case ClassCase()
          |val probe = 1
          |""".stripMargin
      )
    val packageLayoutFile      = PsiFileFactory
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
    val packaging              = PsiTreeUtil.findChildOfType(file, classOf[ScPackaging])
    val layoutPackagings       = PsiTreeUtil
      .findChildrenOfType(packageLayoutFile, classOf[ScPackaging])
      .asScala
      .toVector
    val bracedPackaging        = layoutPackagings.find(_.fullPackageName == "braced").orNull
    val outerPackaging         = layoutPackagings.find(_.fullPackageName == "outer").orNull
    val innerPackaging         = layoutPackagings.find(_.fullPackageName == "outer.inner").orNull
    val layoutEnds             = PsiTreeUtil.findChildrenOfType(packageLayoutFile, classOf[ScEnd]).asScala.toVector
    val innerEnd               = layoutEnds.find(_.getName == "inner").orNull
    val outerEnd               = layoutEnds.find(_.getName == "outer").orNull
    val bracedImport           = Option(bracedPackaging)
      .flatMap(value => Option(PsiTreeUtil.findChildOfType(value, classOf[ScImportStmt])))
      .orNull
    val innerExport            = Option(innerPackaging)
      .flatMap(value => Option(PsiTreeUtil.findChildOfType(value, classOf[ScExportStmt])))
      .orNull
    val reference              = Option(packaging).flatMap(_.reference).orNull
    val qualifier              = Option(reference).flatMap(_.qualifier).orNull
    val statements             = PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).asScala.toVector
    val expressions            = statements.flatMap(_.importExprs)
    val selectorSets           = expressions.flatMap(_.selectorSet)
    val selectors              = selectorSets.flatMap(_.selectors)
    val exportStatements       = PsiTreeUtil.findChildrenOfType(file, classOf[ScExportStmt]).asScala.toVector
    val exportExpressions      = exportStatements.flatMap(_.importExprs)
    val exportSelectorSets     = exportExpressions.flatMap(_.selectorSet)
    val exportSelectors        = exportSelectorSets.flatMap(_.selectors)
    val aliasSelectors         = selectors.filter(_.isAliasedImport)
    val aliasAsElement         = aliasSelectors.headOption
      .flatMap(selector => leafAtText(selector, "as"))
      .orNull
    val aliasArrowElement      = aliasSelectors
      .lift(1)
      .flatMap(selector => leafAtText(selector, "=>"))
      .orNull
    val givenSelector          = selectors.find(_.isGivenSelector)
    val wildcardSelector       = selectors.find(_.isWildcardSelector)
    val wildcardElement        = wildcardSelector.flatMap(_.wildcardElement).orNull
    val legacyWildcardElement  = expressions.lastOption.flatMap(_.wildcardElement).orNull
    val givenType              = givenSelector.flatMap(_.givenTypeElement).orNull
    val parameterizedType      = PsiTreeUtil.findChildOfType(file, classOf[ScParameterizedTypeElement])
    val typeArguments          = Option(parameterizedType).map(_.typeArgList).orNull
    val leftTypeBracket        = Option(typeArguments).flatMap(leafAtText(_, "[")).orNull
    val rightTypeBracket       = Option(typeArguments).flatMap(leafAtText(_, "]")).orNull
    val parameterizedBase      = Option(parameterizedType).map(_.typeElement).orNull
    val parameterizedArgs      = Option(typeArguments).map(_.typeArgs.toVector).getOrElse(Vector.empty)
    val givenReference         = Option(givenType)
      .flatMap(value => Option(PsiTreeUtil.findChildOfType(value, classOf[ScStableCodeReference])))
      .orNull
    val qualifiedType          = selectors
      .flatMap(_.givenTypeElement)
      .collectFirst { case value: ScSimpleTypeElement if value.getText == "alpha.gamma.Bound" => value }
      .orNull
    val qualifiedReference     = Option(qualifiedType).flatMap(_.reference).orNull
    val qualifiedQualifier     = Option(qualifiedReference).flatMap(_.qualifier).orNull
    val wildcardType           = PsiTreeUtil.findChildOfType(file, classOf[ScWildcardTypeElement])
    val wildcardLower          = Option(wildcardType).flatMap(_.lowerTypeElement).orNull
    val wildcardUpper          = Option(wildcardType).flatMap(_.upperTypeElement).orNull
    val wildcardQuestion       = Option(wildcardType).flatMap(leafAtText(_, "?")).orNull
    val lowerBoundToken        = Option(wildcardType).flatMap(leafAtText(_, ">:")).orNull
    val upperBoundToken        = Option(wildcardType).flatMap(leafAtText(_, "<:")).orNull
    val infixType              = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScInfixTypeElement])
      .asScala
      .find(_.getText == "Left | Middle & Right")
      .orNull
    val nestedInfixType        =
      Option(infixType).flatMap(_.rightOption).collect { case value: ScInfixTypeElement => value }.orNull
    val infixLeft              = Option(infixType).map(_.left).orNull
    val infixRight             = Option(infixType).flatMap(_.rightOption).orNull
    val infixOperation         = Option(infixType).map(_.operation).orNull
    val integerLiteral         = PsiTreeUtil.findChildOfType(file, classOf[ScIntegerLiteral])
    val modifierLists          = PsiTreeUtil.findChildrenOfType(file, classOf[ScModifierList]).asScala.toVector
    val annotatedModifiers     = modifierLists.find(_.getText == "private[scope] abstract").orNull
    val memberModifiers        = modifierLists.find(_.getText.contains("protected[this]")).orNull
    val accessModifiers        = PsiTreeUtil.findChildrenOfType(file, classOf[ScAccessModifier]).asScala.toVector
    val annotationsContainers  = PsiTreeUtil.findChildrenOfType(file, classOf[ScAnnotations]).asScala.toVector
    val annotationContainer    = annotationsContainers.find(_.getText.startsWith("@ann")).orNull
    val annotations            = PsiTreeUtil.findChildrenOfType(file, classOf[ScAnnotation]).asScala.toVector
    val annotationExpressions  = PsiTreeUtil.findChildrenOfType(file, classOf[ScAnnotationExpr]).asScala.toVector
    val deprecatedAnnotation   = annotations.find(_.getText == "@deprecated(\"m\", \"1\")").orNull
    val constructorInvocations = PsiTreeUtil.findChildrenOfType(file, classOf[ScConstructorInvocation]).asScala.toVector
    val argumentLists          = PsiTreeUtil.findChildrenOfType(file, classOf[ScArgumentExprList]).asScala.toVector
    val classes                = PsiTreeUtil.findChildrenOfType(file, classOf[ScClass]).asScala.toVector
    val traits                 = PsiTreeUtil.findChildrenOfType(file, classOf[ScTrait]).asScala.toVector
    val objects                = PsiTreeUtil.findChildrenOfType(file, classOf[ScObject]).asScala.toVector
    val enums                  = PsiTreeUtil.findChildrenOfType(file, classOf[ScEnum]).asScala.toVector
    val enumCases              = PsiTreeUtil.findChildrenOfType(file, classOf[ScEnumCases]).asScala.toVector
    val enumSingletonCases     = PsiTreeUtil.findChildrenOfType(file, classOf[ScEnumSingletonCase]).asScala.toVector
    val enumClassCases         = PsiTreeUtil.findChildrenOfType(file, classOf[ScEnumClassCase]).asScala.toVector
    val extendsBlocks          = PsiTreeUtil.findChildrenOfType(file, classOf[ScExtendsBlock]).asScala.toVector
    val templateBodies         = PsiTreeUtil.findChildrenOfType(file, classOf[ScTemplateBody]).asScala.toVector
    val primaryConstructors    = PsiTreeUtil.findChildrenOfType(file, classOf[ScPrimaryConstructor]).asScala.toVector
    val parameterClauses       = PsiTreeUtil.findChildrenOfType(file, classOf[ScParameters]).asScala.toVector
    val parameterClause        = PsiTreeUtil.findChildrenOfType(file, classOf[ScParameterClause]).asScala.toVector
    val annotationPayloads     =
      annotationExpressions.flatMap(value => PsiTreeUtil.findChildrenOfType(value, classOf[ScExpression]).asScala)
    val manager                = PsiManager.getInstance(project)
    val persistenceFailure     = probePersistence(file).left.toOption
    val candidates             =
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
        infixType,
        nestedInfixType,
        infixLeft,
        infixRight,
        infixOperation,
        integerLiteral,
        annotatedModifiers,
        memberModifiers,
        deprecatedAnnotation
      ) ++ statements ++ expressions ++ selectorSets ++ selectors ++ exportStatements ++ exportExpressions ++
        exportSelectorSets ++ exportSelectors ++ accessModifiers ++ annotationsContainers ++ annotations ++
        annotationExpressions ++ constructorInvocations ++ argumentLists ++ annotationPayloads
        ++ classes ++ traits ++ objects ++ enums ++ enumCases ++ enumSingletonCases ++ enumClassCases ++
        extendsBlocks ++ templateBodies ++ primaryConstructors ++ parameterClauses ++ parameterClause
    if packaging == null || reference == null || qualifier == null || bracedPackaging == null || outerPackaging == null ||
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
    else if integerLiteral == null || integerLiteral.getText != "1" then
      Left("native integer literal PSI is inconsistent")
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
    else if classes.isEmpty || traits.size != 1 || objects.isEmpty || enums.size != 1 || enumCases.size != 2 ||
      enumSingletonCases.size != 1 || enumClassCases.size != 1 || extendsBlocks.isEmpty || templateBodies.isEmpty ||
      primaryConstructors.isEmpty || parameterClauses.isEmpty || parameterClause.isEmpty
    then Left("native template PSI probe is incomplete")
    else if Vector(
        PsiOutputRoleId.ClassDefinition    -> classes.head,
        PsiOutputRoleId.TraitDefinition    -> traits.head,
        PsiOutputRoleId.ObjectDefinition   -> objects.head,
        PsiOutputRoleId.EnumDefinition     -> enums.head,
        PsiOutputRoleId.EnumCases          -> enumCases.head,
        PsiOutputRoleId.EnumSingletonCase  -> enumSingletonCases.head,
        PsiOutputRoleId.EnumClassCase      -> enumClassCases.head,
        PsiOutputRoleId.ExtendsBlock       -> extendsBlocks.head,
        PsiOutputRoleId.TemplateBody       -> templateBodies.head,
        PsiOutputRoleId.PrimaryConstructor -> primaryConstructors.head,
        PsiOutputRoleId.ParameterClauses   -> parameterClauses.head,
        PsiOutputRoleId.ParameterClause    -> parameterClause.head
      ).exists: (role, element) =>
        element.getNode.getElementType match
          case stub: IStubElementType[?, ?] =>
            TemplatePersistenceSurfaces.ExternalIds.get(role).forall(_ != stub.getExternalId)
          case _                            => true
    then Left("native template PSI external IDs are inconsistent")
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
    else if candidates.exists(value =>
        (value.getContainingFile != file && value.getContainingFile != packageLayoutFile) || value.getProject != project
      )
    then Left("native PSI identity is inconsistent")
    else if candidates.exists(value => value.getNode.getPsi ne value) then
      Left("native AST and PSI identity is inconsistent")
    else if candidates.exists(value => !manager.areElementsEquivalent(value, value.getNavigationElement))
    then Left("native package PSI navigation is not self-identical")
    else
      val values          = candidates.map(value => surfaceId(value.getClass) -> value.getNode.getElementType)
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
          templateBodies ++ primaryConstructors ++ parameterClauses ++ parameterClause).exists(value =>
          !value.getNode.getElementType.isInstanceOf[IStubElementType[?, ?]]
        )
      then Left("native template stub-bearing PSI element type cannot produce stubs")
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
        Right(
          NativePsiElementBindings(
            grouped.view.mapValues(_.head).toMap +
              ("org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyIntegerLiteral"    ->
                MetallurgyIntegerLiteral.ElementType) +
              ("org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyExpressionPayload" ->
                MetallurgyExpressionPayload.ElementType) +
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
              PsiOutputRoleId.ParameterizedType     -> parameterizedType.getNode.getElementType,
              PsiOutputRoleId.TypeArguments         -> typeArguments.getNode.getElementType,
              PsiOutputRoleId.WildcardType          -> wildcardType.getNode.getElementType,
              PsiOutputRoleId.InfixType             -> infixType.getNode.getElementType,
              PsiOutputRoleId.IntegerLiteral        -> integerLiteral.getNode.getElementType,
              PsiOutputRoleId.ModifierList          -> annotatedModifiers.getNode.getElementType,
              PsiOutputRoleId.AccessModifier        -> accessModifiers.head.getNode.getElementType,
              PsiOutputRoleId.Annotations           -> annotationContainer.getNode.getElementType,
              PsiOutputRoleId.Annotation            -> annotations.head.getNode.getElementType,
              PsiOutputRoleId.AnnotationExpr        -> annotationExpressions.head.getNode.getElementType,
              PsiOutputRoleId.ConstructorInvocation -> constructorInvocations.head.getNode.getElementType,
              PsiOutputRoleId.AnnotationArguments   -> argumentLists.head.getNode.getElementType,
              PsiOutputRoleId.ExpressionPayload     -> MetallurgyExpressionPayload.ElementType,
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
              PsiOutputRoleId.ParameterClause       -> parameterClause.head.getNode.getElementType
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
              PsiOutputRoleId.ParameterizedType     -> surfaceId(parameterizedType.getClass),
              PsiOutputRoleId.TypeArguments         -> surfaceId(typeArguments.getClass),
              PsiOutputRoleId.WildcardType          -> surfaceId(wildcardType.getClass),
              PsiOutputRoleId.InfixType             -> surfaceId(infixType.getClass),
              PsiOutputRoleId.IntegerLiteral        -> surfaceId(integerLiteral.getClass),
              PsiOutputRoleId.ModifierList          -> surfaceId(annotatedModifiers.getClass),
              PsiOutputRoleId.AccessModifier        -> surfaceId(accessModifiers.head.getClass),
              PsiOutputRoleId.Annotations           -> surfaceId(annotationContainer.getClass),
              PsiOutputRoleId.Annotation            -> surfaceId(annotations.head.getClass),
              PsiOutputRoleId.AnnotationExpr        -> surfaceId(annotationExpressions.head.getClass),
              PsiOutputRoleId.ConstructorInvocation -> surfaceId(constructorInvocations.head.getClass),
              PsiOutputRoleId.AnnotationArguments   -> surfaceId(argumentLists.head.getClass),
              PsiOutputRoleId.ExpressionPayload     ->
                "org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyExpressionPayload",
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
              PsiOutputRoleId.ParameterClause       -> surfaceId(parameterClause.head.getClass)
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
            ) ++ TemplatePersistenceSurfaces.DefinitionIndices
              .appended(TemplatePersistenceSurfaces.SuperClassNameIndex)
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
              )
          )
        )

  private def surfaceId(value: Class[?]): String = value.getName.replace('.', '/')

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
        TemplatePersistenceSurfaces.SuperClassNameIndex         -> "AnyRef"
      )
      val templatePersistence                                                =
        templateShape(stubs) == templateShape(restoredStubs) && indexShape(stubs) == indexShape(restoredStubs) &&
          templateIndices == templateIndexShape(restoredStubs) &&
          expectedTemplateIndices.subsetOf(templateIndices) &&
          templateIndices.map(_._1) ==
          (TemplatePersistenceSurfaces.DefinitionIndices :+ TemplatePersistenceSurfaces.SuperClassNameIndex).toSet &&
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
