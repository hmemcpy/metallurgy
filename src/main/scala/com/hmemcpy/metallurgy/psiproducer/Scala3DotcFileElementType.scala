package com.hmemcpy.metallurgy.psiproducer

import com.intellij.lang.{ASTFactory, ASTNode, PsiBuilderFactory}
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.lang.psi.stubs.elements.ScStubFileElementType

/** The dialect file-root parse. Decision order:
  *
  *   1. An extraction installed in [[DotcTreeSource]] builds the AST from the compiler's typed tree (the producer). The
  *      backend installs an extraction only for a source the compiler typed cleanly and the bundled parser cannot
  *      represent, so the no-suppression invariant holds at the publication layer. 2. A settled decision in
  *      [[ProducerParseState]] (`Rejected` or `BundledFine`) uses the bundled parser — the compiler vouched for or
  *      against the source, so the bundled parse is appropriate. 3. Otherwise (no extraction, not settled): if the
  *      bundled parser represents the source cleanly, use it directly and settle now; only a source the bundled parser
  *      fragments gets the pending placeholder, a leaf holding the verbatim text with no error nodes and no Scala
  *      expression structure. The file is then never painted red and never trips a bundled-PSI invariant while the
  *      compiler decides. The first such parse schedules the backend.
  */
final class Scala3DotcFileElementType
    extends ScStubFileElementType(
      s"${Scala3DotcLanguage.INSTANCE.getDisplayName.toLowerCase} FILE".replace(' ', '.'),
      Scala3DotcLanguage.INSTANCE
    ):

  override protected def doParseContents(chameleon: ASTNode, psi: PsiElement): ASTNode =
    val source = chameleon.getChars.toString
    DotcTreeSource.extractionFor(source) match
      case Some(extraction)                          =>
        val builder = PsiBuilderFactory
          .getInstance()
          .createBuilder(psi.getProject, chameleon, null, Scala3DotcLanguage.INSTANCE, chameleon.getChars)
        DotcPsiProducer.parse(this, builder, extraction)
        builder.getTreeBuilt
      case _ if ProducerParseState.isSettled(source) =>
        super.doParseContents(chameleon, psi)
      case _                                         =>
        if !BundledScala3Parse.hasErrors(source, psi.getProject) then
          val _ = ProducerParseState.useBundled(source)
          super.doParseContents(chameleon, psi)
        else
          if ProducerParseState.becomePendingIfUnknown(source) then ProducerParseScheduler.schedule(psi)
          ASTFactory.leaf(Scala3DotcPendingLeaf.PendingFileContent, chameleon.getChars)
