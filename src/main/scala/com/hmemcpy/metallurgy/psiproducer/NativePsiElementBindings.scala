package com.hmemcpy.metallurgy.psiproducer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.{PsiFileFactory, PsiManager}
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.Scala3Language
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScImportStmt
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScPackaging

import scala.jdk.CollectionConverters.*

private[metallurgy] final case class NativePsiElementBindings(elementTypes: Map[String, IElementType])

private[metallurgy] object NativePsiElementBindings:
  def probe(project: Project): Either[String, NativePsiElementBindings] =
    if ApplicationManager.getApplication.isReadAccessAllowed then probeInReadAction(project)
    else
      ApplicationManager.getApplication.runReadAction(
        new Computable[Either[String, NativePsiElementBindings]]:
          override def compute(): Either[String, NativePsiElementBindings] = probeInReadAction(project)
      )

  private def probeInReadAction(project: Project): Either[String, NativePsiElementBindings] =
    val file             = PsiFileFactory
      .getInstance(project)
      .createFileFromText(
        "NativeBindingProbe.scala",
        Scala3Language.INSTANCE,
        """package example.syntax
          |import alpha.beta.Member
          |import alpha.beta.*
          |import alpha.beta.{Original as Renamed, given Bound, *}
          |""".stripMargin
      )
    val packaging        = PsiTreeUtil.findChildOfType(file, classOf[ScPackaging])
    val reference        = Option(packaging).flatMap(_.reference).orNull
    val qualifier        = Option(reference).flatMap(_.qualifier).orNull
    val statements       = PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).asScala.toVector
    val expressions      = statements.flatMap(_.importExprs)
    val selectorSets     = expressions.flatMap(_.selectorSet)
    val selectors        = selectorSets.flatMap(_.selectors)
    val aliasSelector    = selectors.find(_.isAliasedImport)
    val givenSelector    = selectors.find(_.isGivenSelector)
    val wildcardSelector = selectors.find(_.isWildcardSelector)
    val manager          = PsiManager.getInstance(project)
    val candidates       = Vector(packaging, reference, qualifier) ++ statements ++ expressions ++ selectorSets ++ selectors
    if packaging == null || reference == null || qualifier == null then Left("native package PSI probe is incomplete")
    else if statements.size != 3 || expressions.size != 3 || selectorSets.size != 1 || selectors.size != 3 then
      Left("native import PSI probe is incomplete")
    else if packaging.keyword == null || packaging.keyword.getText != "package" || reference.refName != "syntax" ||
      qualifier.refName != "example" || packaging.packageName != "example.syntax" || packaging.parentPackageName.nonEmpty ||
      reference.getText != "example.syntax" || qualifier.getText != "example"
    then Left("native package PSI accessors do not expose the required nested reference")
    else if expressions.map(_.getText) != Vector(
        "alpha.beta.Member",
        "alpha.beta.*",
        "alpha.beta.{Original as Renamed, given Bound, *}"
      ) || expressions.head.reference.forall(_.getText != "alpha.beta.Member") ||
      expressions.head.qualifier.forall(_.getText != "alpha.beta") || expressions.head.selectors.nonEmpty ||
      expressions.head.hasWildcardSelector || expressions.head.hasGivenSelector ||
      expressions(1).qualifier.forall(_.getText != "alpha.beta") || !expressions(1).hasWildcardSelector ||
      expressions(1).wildcardElement.forall(_.getText != "*") || expressions(1).selectorSet.nonEmpty ||
      expressions(2).qualifier.forall(_.getText != "alpha.beta") || !expressions(2).hasWildcardSelector ||
      !expressions(2).hasGivenSelector
    then Left("native import expression accessors are inconsistent")
    else if aliasSelector.flatMap(_.importedName) != Some("Renamed") ||
      aliasSelector.flatMap(_.aliasName) != Some("Renamed") ||
      aliasSelector.flatMap(_.reference).forall(_.getText != "Original") ||
      givenSelector.flatMap(_.givenTypeElement).forall(_.getText != "Bound") ||
      wildcardSelector.flatMap(_.wildcardElement).forall(_.getText != "*")
    then Left("native import selector accessors are inconsistent")
    else if reference.getParent != packaging || qualifier.getParent != reference then
      Left("native package PSI direct parents are inconsistent")
    else if expressions.exists(_.getParent == null) ||
      statements.zip(expressions).exists((statement, expression) => expression.getParent != statement) ||
      selectorSets.exists(_.getParent != expressions(2)) || selectors.exists(_.getParent != selectorSets.head)
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
      else Right(NativePsiElementBindings(grouped.view.mapValues(_.head).toMap))

  private def surfaceId(value: Class[?]): String = value.getName.replace('.', '/')
