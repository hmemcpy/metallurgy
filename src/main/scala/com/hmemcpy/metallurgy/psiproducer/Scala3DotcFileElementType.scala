package com.hmemcpy.metallurgy.psiproducer

import com.intellij.lang.{ASTNode, PsiBuilderFactory}
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.lang.psi.stubs.elements.ScStubFileElementType

/** The dialect file-root parse. The bundled lexer and file/stub mechanics remain the substrate; this element type owns
  * how the whole-file AST is produced for an active module. When a current compiler extraction is available the AST is
  * built from the typed tree; otherwise the bundled parser is used.
  */
final class Scala3DotcFileElementType
    extends ScStubFileElementType(
      s"${Scala3DotcLanguage.INSTANCE.getDisplayName.toLowerCase} FILE".replace(' ', '.'),
      Scala3DotcLanguage.INSTANCE
    ):

  override protected def doParseContents(chameleon: ASTNode, psi: PsiElement): ASTNode =
    DotcTreeSource.extractionFor(chameleon.getChars) match
      case Some(extraction) =>
        val builder = PsiBuilderFactory
          .getInstance()
          .createBuilder(psi.getProject, chameleon, null, Scala3DotcLanguage.INSTANCE, chameleon.getChars)
        DotcPsiProducer.parse(this, builder, extraction)
        builder.getTreeBuilt
      case None             =>
        super.doParseContents(chameleon, psi)
