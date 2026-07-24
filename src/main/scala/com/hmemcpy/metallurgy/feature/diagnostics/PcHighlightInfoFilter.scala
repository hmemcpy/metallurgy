package com.hmemcpy.metallurgy.feature.diagnostics

import com.intellij.codeInsight.daemon.impl.{HighlightInfo, HighlightInfoFilter}
import com.intellij.psi.PsiFile

/** Passes every highlight through unchanged. Both the bundled annotator's highlights and dotc's diagnostics are shown —
  * they complement each other.
  */
final class PcHighlightInfoFilter extends HighlightInfoFilter:

  override def accept(info: HighlightInfo, file: PsiFile): Boolean = true
