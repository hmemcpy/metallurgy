package org.jetbrains.plugins.scala.lang.psi.impl.metallurgy

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import org.jetbrains.plugins.scala.ScalaLanguage
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.parser.SelfPsiCreator
import org.jetbrains.plugins.scala.lang.psi.api.base.types.MetallurgyCompilerBackendBridge
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScAssignment, ScMethodCall}
import org.jetbrains.plugins.scala.lang.psi.impl.expr.ScExpressionImplBase
import org.jetbrains.plugins.scala.lang.psi.types.ScType
import org.jetbrains.plugins.scala.lang.psi.types.result
import org.jetbrains.plugins.scala.lang.resolve.ScalaResolveResult

final class MetallurgyNamedArgument(node: ASTNode) extends ScExpressionImplBase(node) with ScAssignment:
  private def exactType: Either[result.Failure, ScType] =
    MetallurgyCompilerBackendBridge.rawExpressionType(this) match
      case value: ScType => Right(value)
      case _             => result.Failure("exact compiler named argument type is unavailable")

  override def `type`(): Either[result.Failure, ScType] = exactType

  override def innerType: Either[result.Failure, ScType] = exactType

  override def isNamedParameter: Boolean = true

  def assignmentToken: Option[PsiElement] = findFirstChildByType(ScalaTokenTypes.tASSIGN)

  override def mirrorMethodCall: Option[ScMethodCall] = None

  override def resolveAssignment: Option[ScalaResolveResult] = None

  override def shapeResolveAssignment: Option[ScalaResolveResult] = None

  override def assignNavigationElement: PsiElement = leftExpression

  override def isDynamicNamedAssignment: Boolean = false

object MetallurgyNamedArgument:
  object ElementType extends IElementType("METALLURGY_NAMED_ARGUMENT", ScalaLanguage.INSTANCE), SelfPsiCreator:
    override def createElement(node: ASTNode): PsiElement = new MetallurgyNamedArgument(node)
