package com.hmemcpy.metallurgy.feature.inlay

import com.intellij.codeHighlighting.{TextEditorHighlightingPass, TextEditorHighlightingPassFactory}
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

/** Registers the presentation-compiler type-hint pass to run after the bundled annotator. The pass is created only for
  * Scala sources in active modules; otherwise the factory returns `null` and the daemon skips it.
  */
final class PcTypeHintsPassFactory extends TextEditorHighlightingPassFactory:

  override def createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass =
    if !com.hmemcpy.metallurgy.compilerbackend.CompilerBackendPassFactory.isActiveScala(file) then null
    else new PcTypeHintsPass(editor, file)
