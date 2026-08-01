package org.jetbrains.plugins.scala.lang.psi.impl.metallurgy

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import org.jetbrains.plugins.scala.ScalaLanguage
import org.jetbrains.plugins.scala.lang.parser.SelfPsiCreator
import org.jetbrains.plugins.scala.lang.psi.api.base.types.MetallurgyCompilerBackendBridge
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.lang.psi.impl.expr.ScExpressionImplBase
import org.jetbrains.plugins.scala.lang.psi.types.ScType
import org.jetbrains.plugins.scala.lang.psi.types.result

final class MetallurgyExpressionPayload(node: ASTNode) extends ScExpressionImplBase(node) with ScExpression:
  private def exactType: Either[result.Failure, ScType] =
    MetallurgyCompilerBackendBridge.rawExpressionType(this) match
      case value: ScType => Right(value)
      case _             => result.Failure("exact compiler expression type is unavailable")

  override def `type`(): Either[result.Failure, ScType] = exactType

  override def innerType: Either[result.Failure, ScType] = exactType

object MetallurgyExpressionPayload:
  object ElementType extends IElementType("METALLURGY_EXPRESSION_PAYLOAD", ScalaLanguage.INSTANCE), SelfPsiCreator:
    override def createElement(node: ASTNode): PsiElement = new MetallurgyExpressionPayload(node)
