package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.feature.inlay.PcTypeHintsPassFactory
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

/** Registers semantic population before its visible type-hint consumer. */
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
    val populationPass = registrar.registerTextEditorHighlightingPass(this, Array(Pass.UPDATE_ALL), null, false, -1)
    val _              = registrar.registerTextEditorHighlightingPass(
      new PcTypeHintsPassFactory,
      Array(populationPass),
      null,
      false,
      -1
    )

private[metallurgy] object CompilerBackendPassFactory:
  def isActiveScala(file: PsiFile): Boolean =
    file.getViewProvider.getPsi(file.getLanguage) match
      case _: ScalaFile =>
        Option(ModuleUtilCore.findModuleForPsiElement(file))
          .exists(ModuleDetectionService.get(file.getProject).isActive)
      case _            => false
