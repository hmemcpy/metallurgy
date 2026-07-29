package org.jetbrains.plugins.scala.lang.psi.impl.metallurgy

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import org.jetbrains.plugins.scala.ScalaLanguage
import org.jetbrains.plugins.scala.lang.parser.SelfPsiCreator
import org.jetbrains.plugins.scala.lang.psi.api.base.ScLiteral
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScIntegerLiteral
import org.jetbrains.plugins.scala.lang.psi.impl.base.literals.{
  NumericLiteralImplBase,
  ScIntegerLiteralImpl,
  parseInteger
}
import org.jetbrains.plugins.scala.lang.psi.types.{ScType, api}

final class MetallurgyIntegerLiteral(node: ASTNode)
    extends NumericLiteralImplBase(node, MetallurgyIntegerLiteral.ElementType.toString)
    with ScIntegerLiteral:

  override protected def parseNumber(text: String): Integer =
    parseInteger(text, stripLeading0 = true)

  override protected def wrappedValue(value: Integer): ScLiteral.Value[Integer] =
    ScIntegerLiteralImpl.Value(value)

  override protected def fallbackType: ScType = api.Int

  override private[psi] def unwrappedValue(value: Integer): Int = value.intValue

object MetallurgyIntegerLiteral:
  object ElementType extends IElementType("METALLURGY_INTEGER_LITERAL", ScalaLanguage.INSTANCE), SelfPsiCreator:
    override def createElement(node: ASTNode): PsiElement = new MetallurgyIntegerLiteral(node)
