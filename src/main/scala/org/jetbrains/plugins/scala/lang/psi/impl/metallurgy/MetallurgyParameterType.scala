package org.jetbrains.plugins.scala.lang.psi.impl.metallurgy

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import org.jetbrains.plugins.scala.ScalaLanguage
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenType
import org.jetbrains.plugins.scala.lang.parser.SelfPsiCreator
import org.jetbrains.plugins.scala.lang.psi.impl.statements.params.ScParameterTypeImpl

final class MetallurgyParameterType(node: ASTNode) extends ScParameterTypeImpl(node):
  override def isCallByNameParameter: Boolean =
    super.isCallByNameParameter ||
      findChildrenByType(ScalaTokenType.PureFunctionArrow).nonEmpty

object MetallurgyParameterType:
  object ElementType extends IElementType("METALLURGY_PARAMETER_TYPE", ScalaLanguage.INSTANCE), SelfPsiCreator:
    override def createElement(node: ASTNode): PsiElement = new MetallurgyParameterType(node)
