package com.hmemcpy.metallurgy.psiproducer

import com.intellij.lang.{ASTNode, PsiBuilderFactory}
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.lang.psi.stubs.elements.ScStubFileElementType

final class Scala3DotcFileElementType
    extends ScStubFileElementType(
      s"${Scala3DotcLanguage.INSTANCE.getDisplayName.toLowerCase} FILE".replace(' ', '.'),
      Scala3DotcLanguage.INSTANCE
    ):

  override protected def doParseContents(chameleon: ASTNode, psi: PsiElement): ASTNode =
    val source = chameleon.getChars.toString
    DotcTreeSource.extractionFor(source) match
      case Some(extraction) =>
        val builder = PsiBuilderFactory
          .getInstance()
          .createBuilder(psi.getProject, chameleon, null, Scala3DotcLanguage.INSTANCE, chameleon.getChars)
        DotcPsiProducer.parse(this, builder, extraction)
        builder.getTreeBuilt.getFirstChildNode
      case None             => super.doParseContents(chameleon, psi)
