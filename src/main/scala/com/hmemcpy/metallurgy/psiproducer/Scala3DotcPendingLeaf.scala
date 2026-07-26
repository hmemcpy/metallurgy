package com.hmemcpy.metallurgy.psiproducer

import com.intellij.psi.tree.IElementType

/** A single leaf holding the whole verbatim text while the compiler has not yet decided whether the source is accepted,
  * rejected, or handled by the bundled parser. It carries no Scala expression structure and no error node, so a pending
  * file is never painted red and never trips a bundled-PSI invariant on constructs the bundled parser cannot represent.
  */
object Scala3DotcPendingLeaf:
  val PendingFileContent: IElementType = IElementType("SCALA3_DOTC_PENDING_FILE_CONTENT", Scala3DotcLanguage.INSTANCE)
