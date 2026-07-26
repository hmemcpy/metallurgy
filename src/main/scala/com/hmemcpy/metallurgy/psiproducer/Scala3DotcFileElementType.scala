package com.hmemcpy.metallurgy.psiproducer

import com.intellij.lang.{ASTFactory, ASTNode, PsiBuilderFactory}
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.lang.psi.stubs.elements.ScStubFileElementType

/** The dialect file-root parse. Decision order, keyed by file URL:
  *
  *   1. An extraction installed in [[DotcTreeSource]] (keyed by verbatim text) builds the AST from the compiler's typed
  *      tree (the producer). The backend installs an extraction only for a source the compiler typed cleanly and the
  *      bundled parser cannot represent, so the no-suppression invariant holds at the publication layer. 2. A settled
  *      decision in [[ProducerParseState]] (`Rejected` or `BundledFine`) uses the bundled parser — the compiler vouched
  *      for or against the source, so the bundled parse is appropriate. 3. Otherwise: if the bundled parser represents
  *      the source cleanly, use it directly and settle now; only a source the bundled parser fragments gets the pending
  *      placeholder, a leaf holding the verbatim text with no error nodes and no Scala expression structure. The file
  *      is then never painted red and never trips a bundled-PSI invariant while the compiler decides. Every pending
  *      parse of an eligible physical file schedules the backend pass (coalesced by module/URI/version), so a lost
  *      one-shot cannot strand the file.
  */
final class Scala3DotcFileElementType
    extends ScStubFileElementType(
      s"${Scala3DotcLanguage.INSTANCE.getDisplayName.toLowerCase} FILE".replace(' ', '.'),
      Scala3DotcLanguage.INSTANCE
    ):

  override protected def doParseContents(chameleon: ASTNode, psi: PsiElement): ASTNode =
    val source  = chameleon.getChars.toString
    val fileUrl = fileUrlOf(psi)
    DotcTreeSource.extractionFor(source) match
      case Some(extraction)                           =>
        val builder = PsiBuilderFactory
          .getInstance()
          .createBuilder(psi.getProject, chameleon, null, Scala3DotcLanguage.INSTANCE, chameleon.getChars)
        DotcPsiProducer.parse(this, builder, extraction)
        // The platform's file parse wraps content in the file element type and returns the first child (unwrapped),
        // so the top-level nodes become direct children of the file (ScDeclarationSequenceHolder). Returning the
        // wrapped root would nest them under an extra node and hide them from lexical resolve.
        builder.getTreeBuilt.getFirstChildNode
      case _ if ProducerParseState.isSettled(fileUrl) =>
        super.doParseContents(chameleon, psi)
      case _                                          =>
        if !BundledScala3Parse.hasErrors(source, psi.getProject) then
          val _ = ProducerParseState.useBundled(fileUrl)
          super.doParseContents(chameleon, psi)
        else
          val _ = ProducerParseState.becomePendingIfUnknown(fileUrl)
          ProducerParseScheduler.scheduleIfEligible(psi)
          ASTFactory.leaf(Scala3DotcPendingLeaf.PendingFileContent, chameleon.getChars)

  private def fileUrlOf(psi: PsiElement): String =
    Option(psi.getContainingFile).flatMap(f => Option(f.getVirtualFile)).map(_.getUrl).getOrElse("")
