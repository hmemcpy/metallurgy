package com.hmemcpy.metallurgy.psiproducer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.stubs.*
import com.intellij.psi.{PsiFileFactory, PsiManager}
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.io.AbstractStringEnumerator
import org.jetbrains.plugins.scala.Scala3Language
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenType
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType
import org.jetbrains.plugins.scala.lang.psi.api.base.ScStableCodeReference
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScIntegerLiteral
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScParameterizedTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.{ScExportStmt, ScImportStmt}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScPackaging
import org.jetbrains.plugins.scala.lang.psi.stubs.{
  ScExportStmtStub,
  ScImportExprStub,
  ScImportSelectorStub,
  ScImportSelectorsStub,
  ScImportStmtStub,
  ScPackagingStub
}
import org.jetbrains.plugins.scala.lang.psi.stubs.index.ScalaIndexKeys
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.MetallurgyIntegerLiteral

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
  val ImportWildcardTokenSurface       = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#WildcardStar"
  val ImportLegacyWildcardTokenSurface = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tUNDER"
  val ImportAliasAsTokenSurface        = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#ImportAliasAs"
  val ImportAliasArrowTokenSurface     = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType#ImportAliasArrow"
  val TypeArgumentLeftTokenSurface     = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tLSQBRACKET"
  val TypeArgumentRightTokenSurface    = "org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes#tRSQBRACKET"

  def probe(project: Project): Either[String, NativePsiElementBindings] =
    if ApplicationManager.getApplication.isReadAccessAllowed then probeInReadAction(project)
    else
      ApplicationManager.getApplication.runReadAction(
        new Computable[Either[String, NativePsiElementBindings]]:
          override def compute(): Either[String, NativePsiElementBindings] = probeInReadAction(project)
      )

  private def probeInReadAction(project: Project): Either[String, NativePsiElementBindings] =
    val file                  = PsiFileFactory
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
          |import alpha.beta.`back-tick`
          |import alpha.beta._
          |export alpha.beta.{Original as Exported, given Bound, *}
          |val probe = 1
          |""".stripMargin
      )
    val packaging             = PsiTreeUtil.findChildOfType(file, classOf[ScPackaging])
    val reference             = Option(packaging).flatMap(_.reference).orNull
    val qualifier             = Option(reference).flatMap(_.qualifier).orNull
    val statements            = PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).asScala.toVector
    val expressions           = statements.flatMap(_.importExprs)
    val selectorSets          = expressions.flatMap(_.selectorSet)
    val selectors             = selectorSets.flatMap(_.selectors)
    val exportStatements      = PsiTreeUtil.findChildrenOfType(file, classOf[ScExportStmt]).asScala.toVector
    val exportExpressions     = exportStatements.flatMap(_.importExprs)
    val exportSelectorSets    = exportExpressions.flatMap(_.selectorSet)
    val exportSelectors       = exportSelectorSets.flatMap(_.selectors)
    val aliasSelectors        = selectors.filter(_.isAliasedImport)
    val aliasAsElement        = aliasSelectors.headOption
      .flatMap(selector => leafAtText(selector, "as"))
      .orNull
    val aliasArrowElement     = aliasSelectors
      .lift(1)
      .flatMap(selector => leafAtText(selector, "=>"))
      .orNull
    val givenSelector         = selectors.find(_.isGivenSelector)
    val wildcardSelector      = selectors.find(_.isWildcardSelector)
    val wildcardElement       = wildcardSelector.flatMap(_.wildcardElement).orNull
    val legacyWildcardElement = expressions.lastOption.flatMap(_.wildcardElement).orNull
    val givenType             = givenSelector.flatMap(_.givenTypeElement).orNull
    val parameterizedType     = PsiTreeUtil.findChildOfType(file, classOf[ScParameterizedTypeElement])
    val typeArguments         = Option(parameterizedType).map(_.typeArgList).orNull
    val leftTypeBracket       = Option(typeArguments).flatMap(leafAtText(_, "[")).orNull
    val rightTypeBracket      = Option(typeArguments).flatMap(leafAtText(_, "]")).orNull
    val parameterizedBase     = Option(parameterizedType).map(_.typeElement).orNull
    val parameterizedArgs     = Option(typeArguments).map(_.typeArgs.toVector).getOrElse(Vector.empty)
    val givenReference        = Option(givenType)
      .flatMap(value => Option(PsiTreeUtil.findChildOfType(value, classOf[ScStableCodeReference])))
      .orNull
    val integerLiteral        = PsiTreeUtil.findChildOfType(file, classOf[ScIntegerLiteral])
    val manager               = PsiManager.getInstance(project)
    val persistenceFailure    = probePersistence(file).left.toOption
    val candidates            =
      Vector(
        packaging,
        reference,
        qualifier,
        givenType,
        givenReference,
        parameterizedType,
        typeArguments,
        parameterizedBase,
        parameterizedArgs.headOption.orNull,
        integerLiteral
      ) ++ statements ++ expressions ++ selectorSets ++ selectors ++ exportStatements ++ exportExpressions ++
        exportSelectorSets ++ exportSelectors
    if packaging == null || reference == null || qualifier == null then Left("native package PSI probe is incomplete")
    else if statements.size != 7 || expressions.size != 7 || selectorSets.size != 3 || selectors.size != 5 then
      Left("native import PSI probe is incomplete")
    else if exportStatements.size != 1 || exportExpressions.map(_.getText) !=
        Vector("alpha.beta.{Original as Exported, given Bound, *}") || exportSelectorSets.size != 1 ||
        exportSelectors.size != 3
    then Left("native export PSI probe is incomplete")
    else if packaging.keyword == null || packaging.keyword.getText != "package" || reference.refName != "syntax" ||
      qualifier.refName != "example" || packaging.packageName != "example.syntax" || packaging.parentPackageName.nonEmpty ||
      reference.getText != "example.syntax" || qualifier.getText != "example"
    then Left("native package PSI accessors do not expose the required nested reference")
    else if expressions.map(_.getText) != Vector(
        "alpha.beta.Member",
        "alpha.beta.*",
        "alpha.beta.{Original as Renamed, given Bound, *}",
        "alpha.beta.{Original => Renamed}",
        "alpha.beta.given Ordering[Int]",
        "alpha.beta.`back-tick`",
        "alpha.beta._"
      ) || expressions.head.reference.forall(_.getText != "alpha.beta.Member") ||
      expressions.head.qualifier.forall(_.getText != "alpha.beta") || expressions.head.selectors.nonEmpty ||
      expressions.head.hasWildcardSelector || expressions.head.hasGivenSelector ||
      expressions(1).qualifier.forall(_.getText != "alpha.beta") || !expressions(1).hasWildcardSelector ||
      expressions(1).wildcardElement.forall(_.getText != "*") || expressions(1).selectorSet.nonEmpty ||
      expressions(2).qualifier.forall(_.getText != "alpha.beta") || !expressions(2).hasWildcardSelector ||
      !expressions(2).hasGivenSelector || !expressions(4).hasGivenSelector ||
      expressions(5).reference.forall(_.refName != "`back-tick`") ||
      !expressions(6).hasWildcardSelector || expressions(6).wildcardElement.forall(_.getText != "_")
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
    else if integerLiteral == null || integerLiteral.getText != "1" then
      Left("native integer literal PSI is inconsistent")
    else if ScalaIndexKeys.ALIASED_IMPORT_KEY == null || ScalaIndexKeys.TOP_LEVEL_EXPORT_BY_PKG_KEY == null then
      Left("native import or export index is unavailable")
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
    else if candidates.exists(value => value.getContainingFile != file || value.getProject != project) then
      Left("native PSI identity is inconsistent")
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
      else if grouped.values.exists(types => types.tail.exists(_ ne types.head)) then
        Left("native PSI implementation surface has inconsistent element types")
      else if packaging.getNode.getElementType == reference.getNode.getElementType ||
        reference.getNode.getElementType != qualifier.getNode.getElementType
      then Left("native package PSI element-type identities are inconsistent")
      else if (Vector(packaging) ++ statements ++ expressions ++ selectorSets ++ selectors).exists(value =>
          !value.getNode.getElementType.isInstanceOf[IStubElementType[?, ?]]
        )
      then Left("native stub-bearing PSI element type cannot produce stubs")
      else
        Right(
          NativePsiElementBindings(
            grouped.view.mapValues(_.head).toMap +
              ("org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyIntegerLiteral" ->
                MetallurgyIntegerLiteral.ElementType) +
              (ImportWildcardTokenSurface                                                      -> wildcardElement.getNode.getElementType) +
              (ImportLegacyWildcardTokenSurface                                                -> legacyWildcardElement.getNode.getElementType) +
              (ImportAliasAsTokenSurface                                                       -> aliasAsElement.getNode.getElementType) +
              (ImportAliasArrowTokenSurface                                                    -> aliasArrowElement.getNode.getElementType) +
              (TypeArgumentLeftTokenSurface                                                    -> leftTypeBracket.getNode.getElementType) +
              (TypeArgumentRightTokenSurface                                                   -> rightTypeBracket.getNode.getElementType),
            Map(
              PsiOutputRoleId.PackageStatement  -> packaging.getNode.getElementType,
              PsiOutputRoleId.ImportStatement   -> statements.head.getNode.getElementType,
              PsiOutputRoleId.ExportStatement   -> exportStatements.head.getNode.getElementType,
              PsiOutputRoleId.ImportExpression  -> expressions.head.getNode.getElementType,
              PsiOutputRoleId.ImportSelectorSet -> selectorSets.head.getNode.getElementType,
              PsiOutputRoleId.ImportSelector    -> selectors.head.getNode.getElementType,
              PsiOutputRoleId.StableReference   -> reference.getNode.getElementType,
              PsiOutputRoleId.SimpleType        -> givenType.getNode.getElementType,
              PsiOutputRoleId.ParameterizedType -> parameterizedType.getNode.getElementType,
              PsiOutputRoleId.TypeArguments     -> typeArguments.getNode.getElementType,
              PsiOutputRoleId.IntegerLiteral    -> integerLiteral.getNode.getElementType
            ),
            Map(
              PsiOutputRoleId.PackageStatement  -> surfaceId(packaging.getClass),
              PsiOutputRoleId.ImportStatement   -> surfaceId(statements.head.getClass),
              PsiOutputRoleId.ExportStatement   -> surfaceId(exportStatements.head.getClass),
              PsiOutputRoleId.ImportExpression  -> surfaceId(expressions.head.getClass),
              PsiOutputRoleId.ImportSelectorSet -> surfaceId(selectorSets.head.getClass),
              PsiOutputRoleId.ImportSelector    -> surfaceId(selectors.head.getClass),
              PsiOutputRoleId.StableReference   -> surfaceId(reference.getClass),
              PsiOutputRoleId.SimpleType        -> surfaceId(givenType.getClass),
              PsiOutputRoleId.ParameterizedType -> surfaceId(parameterizedType.getClass),
              PsiOutputRoleId.TypeArguments     -> surfaceId(typeArguments.getClass),
              PsiOutputRoleId.IntegerLiteral    -> surfaceId(integerLiteral.getClass)
            ),
            Vector(
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
                ImportPersistenceSurfaces.SelfNavigation,
                SurfaceFactKind.Navigation,
                None,
                FactStatus.Available,
                SurfaceClassification.SyntaxContract,
                Vector("capability-probed self navigation identity")
              )
            )
          )
        )

  private def surfaceId(value: Class[?]): String = value.getName.replace('.', '/')

  private def probePersistence(file: com.intellij.psi.PsiFile): Either[String, Unit] =
    try
      val stubs                                               = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala
      val packaging                                           = stubs.collectFirst { case value: ScPackagingStub => value }
      val statement                                           = stubs.collectFirst { case value: ScImportStmtStub => value }
      val exportStatement                                     = stubs.collectFirst { case value: ScExportStmtStub => value }
      val expression                                          = stubs.collectFirst { case value: ScImportExprStub if value.hasWildcardSelector => value }
      val selectorSet                                         = stubs.collectFirst { case value: ScImportSelectorsStub if value.hasWildcard => value }
      val selector                                            = stubs.collectFirst { case value: ScImportSelectorStub if value.isAliasedImport => value }
      val exportSelector                                      = stubs.collectFirst {
        case value: ScImportSelectorStub if value.isAliasedImport && value.aliasName.contains("Exported") => value
      }
      val givenSelectorStub                                   = stubs.collectFirst {
        case value: ScImportSelectorStub if value.isGivenSelector && value.typeText.nonEmpty => value
      }
      val enumerator                                          = new AbstractStringEnumerator:
        private val values                         = collection.mutable.ArrayBuffer.empty[String]
        override def enumerate(value: String): Int =
          val found = values.indexOf(value)
          if found >= 0 then found + 1 else values.addOne(value).size
        override def valueOf(id: Int): String      = values(id - 1)
        override def isDirty: Boolean              = false
        override def force(): Unit                 = ()
        override def markCorrupted(): Unit         = ()
        override def close(): Unit                 = ()
      def bytes(write: StubOutputStream => Unit): Array[Byte] =
        val sink   = new ByteArrayOutputStream
        val output = new StubOutputStream(sink, enumerator)
        write(output)
        output.flush()
        sink.toByteArray
      def input(serialized: Array[Byte]): StubInputStream     =
        new StubInputStream(new ByteArrayInputStream(serialized), enumerator)
      val actualTypes                                         = packaging.exists(_.getElementType eq ScalaElementType.PACKAGING) &&
        statement.exists(_.getElementType eq ScalaElementType.ImportStatement) &&
        exportStatement.exists(_.getElementType eq ScalaElementType.ExportStatement) &&
        expression.exists(_.getElementType eq ScalaElementType.IMPORT_EXPR) &&
        selectorSet.exists(_.getElementType eq ScalaElementType.IMPORT_SELECTORS) &&
        selector.exists(_.getElementType eq ScalaElementType.IMPORT_SELECTOR)
      val packagingCopy                                       = packaging.map(stub =>
        ScalaElementType.PACKAGING.deserialize(
          input(bytes(ScalaElementType.PACKAGING.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val statementCopy                                       = statement.map(stub =>
        ScalaElementType.ImportStatement.deserialize(
          input(bytes(ScalaElementType.ImportStatement.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val exportStatementCopy                                 = exportStatement.map(stub =>
        ScalaElementType.ExportStatement.deserialize(
          input(bytes(ScalaElementType.ExportStatement.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val expressionCopy                                      = expression.map(stub =>
        ScalaElementType.IMPORT_EXPR.deserialize(
          input(bytes(ScalaElementType.IMPORT_EXPR.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val selectorSetCopy                                     = selectorSet.map(stub =>
        ScalaElementType.IMPORT_SELECTORS.deserialize(
          input(bytes(ScalaElementType.IMPORT_SELECTORS.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val selectorCopy                                        = selector.map(stub =>
        ScalaElementType.IMPORT_SELECTOR.deserialize(
          input(bytes(ScalaElementType.IMPORT_SELECTOR.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val exportSelectorCopy                                  = exportSelector.map(stub =>
        ScalaElementType.IMPORT_SELECTOR.deserialize(
          input(bytes(ScalaElementType.IMPORT_SELECTOR.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val givenCopy                                           = givenSelectorStub.map(stub =>
        ScalaElementType.IMPORT_SELECTOR.deserialize(
          input(bytes(ScalaElementType.IMPORT_SELECTOR.serialize(stub, _))),
          new PsiFileStubImpl(null)
        )
      )
      val serialized                                          = packaging
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
          )
      var importAliasIndexed                                  = false
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
      var exportAliasIndexed                                  = false
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
      val packages                                            = Vector.newBuilder[String]
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
      val exportPackages                                      = Vector.newBuilder[String]
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
      Either.cond(
        actualTypes && serialized && importAliasIndexed && exportAliasIndexed &&
          packages.result() == Vector("example.syntax", "example") &&
          exportPackages.result() == Vector("example.syntax"),
        (),
        "native package/import/export persistence contracts are inconsistent"
      )
    catch case NonFatal(error) => Left(s"native persistence probe failed: ${error.getClass.getSimpleName}")

  private def leafAtText(
      selector: com.intellij.psi.PsiElement,
      text: String
  ) =
    val offset = selector.getText.indexOf(text)
    Option.when(offset >= 0)(selector.getNode.findLeafElementAt(offset)).flatMap(node => Option(node.getPsi))
