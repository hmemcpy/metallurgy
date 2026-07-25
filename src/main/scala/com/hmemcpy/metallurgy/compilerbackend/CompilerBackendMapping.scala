package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.pc.{PcCompilerSymbol, PcSourceRange}
import com.intellij.psi.{PsiElement, SmartPsiElementPointer}

/** PSI mapping held only between the cancellable read action and its short UI-thread commit. */
private[metallurgy] final case class CompilerBackendMapping(
    element: SmartPsiElementPointer[PsiElement],
    range: PcSourceRange,
    role: CompilerBackendRole,
    renderedType: String,
    symbolId: Option[String],
    symbol: Option[PcCompilerSymbol] = None
)

/** A mapping resolved to a live PSI element and parsed compiler state, produced off-EDT so the UI-thread commit
  * performs only lightweight slot writes.
  */
private[metallurgy] final case class ResolvedEntry(
    mapping: CompilerBackendMapping,
    element: PsiElement,
    state: CompilerBackendState
)
