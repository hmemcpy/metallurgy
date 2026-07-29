package com.hmemcpy.metallurgy.psiproducer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.{PsiFileFactory, PsiManager}
import com.intellij.psi.stubs.IStubElementType
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.Scala3Language
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScPackaging

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
    val file       = PsiFileFactory
      .getInstance(project)
      .createFileFromText("NativeBindingProbe.scala", Scala3Language.INSTANCE, "package example.syntax\n")
    val packaging  = PsiTreeUtil.findChildOfType(file, classOf[ScPackaging])
    val reference  = Option(packaging).flatMap(_.reference).orNull
    val qualifier  = Option(reference).flatMap(_.qualifier).orNull
    val manager    = PsiManager.getInstance(project)
    val candidates = Vector(packaging, reference, qualifier)
    if packaging == null || reference == null || qualifier == null then Left("native package PSI probe is incomplete")
    else if packaging.keyword == null || packaging.keyword.getText != "package" || reference.refName != "syntax" ||
      qualifier.refName != "example" || packaging.getText != "package example.syntax" ||
      packaging.packageName != "example.syntax" || packaging.parentPackageName.nonEmpty ||
      reference.getText != "example.syntax" || qualifier.getText != "example"
    then Left("native package PSI accessors do not expose the required nested reference")
    else if reference.getParent != packaging || qualifier.getParent != reference then
      Left("native package PSI direct parents are inconsistent")
    else if candidates.exists(value => value.getContainingFile != file || value.getProject != project) then
      Left("native package PSI identity is inconsistent")
    else if candidates.exists(value => value.getNode.getPsi ne value) then
      Left("native package AST and PSI identity is inconsistent")
    else if !manager.areElementsEquivalent(packaging, packaging.getNavigationElement) ||
      !manager.areElementsEquivalent(reference, reference.getNavigationElement)
    then Left("native package PSI navigation is not self-identical")
    else
      val values   = candidates.map(value => surfaceId(value.getClass) -> value.getNode.getElementType)
      val expected = Set(
        "org/jetbrains/plugins/scala/lang/psi/impl/toplevel/packaging/ScPackagingImpl",
        "org/jetbrains/plugins/scala/lang/psi/impl/base/ScStableCodeReferenceImpl"
      )
      if values.map(_._1).toSet != expected then Left("native package PSI implementation surfaces are unexpected")
      else if packaging.getNode.getElementType == reference.getNode.getElementType ||
        reference.getNode.getElementType != qualifier.getNode.getElementType
      then Left("native package PSI element-type identities are inconsistent")
      else if !packaging.getNode.getElementType.isInstanceOf[IStubElementType[?, ?]] then
        Left("native packaging element type cannot produce stubs")
      else Right(NativePsiElementBindings(values.toMap))

  private def surfaceId(value: Class[?]): String = value.getName.replace('.', '/')
