package com.hmemcpy.metallurgy.feature.inlay

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

/** Registers the presentation-compiler type-hint pass to run after the bundled annotator. The pass is created only for
  * Scala sources in active modules; otherwise the factory returns `null` and the daemon skips it.
  */
final class PcTypeHintsPassFactory
    extends TextEditorHighlightingPassFactory
    with TextEditorHighlightingPassFactoryRegistrar:

  override def createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass =
    if !isScala3Active(file) then null
    else new PcTypeHintsPass(editor, file)

  override def registerHighlightingPassFactory(
      registrar: TextEditorHighlightingPassRegistrar,
      project: Project
  ): Unit =
    val _ = registrar.registerTextEditorHighlightingPass(this, Array(Pass.UPDATE_ALL), null, false, -1)

  private def isScala3Active(file: PsiFile): Boolean =
    file.getViewProvider.getPsi(file.getLanguage) match
      case _: ScalaFile =>
        Option(ModuleUtilCore.findModuleForPsiElement(file))
          .exists(com.hmemcpy.metallurgy.module.ModuleDetectionService.get(file.getProject).isActive)
      case _            => false
