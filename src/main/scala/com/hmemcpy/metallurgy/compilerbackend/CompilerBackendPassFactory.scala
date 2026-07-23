package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.intellij.codeHighlighting.{
  Pass,
  TextEditorHighlightingPass,
  TextEditorHighlightingPassFactory,
  TextEditorHighlightingPassFactoryRegistrar,
  TextEditorHighlightingPassRegistrar
}
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile

/** Registers the semantic population pass that fills the CompilerType slot. The bundled Scala plugin's own
  * ScalaTypeHintsPass reads the slot through ScExpression.getTypeWithoutImplicits and renders hints with its native
  * settings, x-ray mode, and obvious-type filtering.
  */
final class CompilerBackendPassFactory
    extends TextEditorHighlightingPassFactory
    with TextEditorHighlightingPassFactoryRegistrar:

  override def createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass =
    if CompilerBackendPassFactory.isActiveScala(file) then new CompilerBackendPass(editor, file)
    else null

  override def registerHighlightingPassFactory(
      registrar: TextEditorHighlightingPassRegistrar,
      project: Project
  ): Unit =
    val _ = registrar.registerTextEditorHighlightingPass(this, Array(Pass.UPDATE_ALL), null, false, -1)

private[metallurgy] object CompilerBackendPassFactory:
  def isActiveScala(file: PsiFile): Boolean =
    file.getViewProvider.getPsi(file.getLanguage) match
      case _: ScalaFile =>
        Option(ModuleUtilCore.findModuleForPsiElement(file))
          .exists(ModuleDetectionService.get(file.getProject).isActive)
      case _            => false
