package com.hmemcpy.metallurgy.feature.diagnostics

import com.intellij.codeInsight.daemon.impl.{HighlightInfo, HighlightInfoFilter}
import com.intellij.psi.PsiFile

/** Passes every highlight through unchanged. The bundled plugin layers its diagnostics on top of the resolved PSI,
  * oblivious to the backend compiler, and pc renders its own diagnostics on a separate markup layer
  * ([[PcHighlightRenderer]]); both sets are shown. A construct flagged by both currently yields two highlights.
  */
final class PcHighlightInfoFilter extends HighlightInfoFilter:

  override def accept(info: HighlightInfo, file: PsiFile): Boolean = true
