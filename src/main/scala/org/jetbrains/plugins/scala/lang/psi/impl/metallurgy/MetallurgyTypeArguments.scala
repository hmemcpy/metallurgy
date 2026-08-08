package org.jetbrains.plugins.scala.lang.psi.impl.metallurgy

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import org.jetbrains.plugins.scala.ScalaLanguage
import org.jetbrains.plugins.scala.lang.parser.SelfPsiCreator
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.api.base.ScStableCodeReference
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{ScTypeArgs, ScTypeElement}
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementImpl
import org.jetbrains.plugins.scala.lang.psi.impl.base.types.ScTypeArgsImpl
import org.jetbrains.plugins.scala.lang.psi.types.{ScType, result}

import scala.collection.immutable.ArraySeq

final class MetallurgyTypeArguments(node: ASTNode) extends ScTypeArgsImpl(node) with ScTypeArgs:
  def logicalTypeArguments: Seq[PsiElement] =
    ArraySeq
      .unsafeWrapArray(getChildren)
      .collect:
        case value: ScTypeElement               => value
        case value: MetallurgyNamedTypeArgument => value

  def namedTypeArguments: Seq[MetallurgyNamedTypeArgument] =
    logicalTypeArguments.collect { case value: MetallurgyNamedTypeArgument => value }

  override def typeArgs: Seq[ScTypeElement] =
    logicalTypeArguments.collect { case value: ScTypeElement => value }

  override def deleteChildInternal(child: ASTNode): Unit =
    val entries = logicalTypeArguments
    val isEntry = entries.exists(_.getNode == child)
    if isEntry && entries.size == 1 then delete()
    else if isEntry then ScalaPsiUtil.deleteElementInCommaSeparatedList(this, child)
    else super.deleteChildInternal(child)

object MetallurgyTypeArguments:
  object ElementType extends IElementType("METALLURGY_TYPE_ARGUMENTS", ScalaLanguage.INSTANCE), SelfPsiCreator:
    override def createElement(node: ASTNode): PsiElement = new MetallurgyTypeArguments(node)

final class MetallurgyNamedTypeArgument(node: ASTNode) extends ScalaPsiElementImpl(node):
  def nameElement: Option[ScStableCodeReference] =
    ArraySeq.unsafeWrapArray(getChildren).collectFirst { case value: ScStableCodeReference => value }

  def typeElement: Option[ScTypeElement] =
    ArraySeq.unsafeWrapArray(getChildren).collectFirst { case value: ScTypeElement => value }

  def name: Option[String] = nameElement.map(_.refName)

  def isNamed: Boolean = true

  def `type`(): Either[result.Failure, ScType] =
    typeElement.fold[Either[result.Failure, ScType]](result.Failure("named type argument has no type element"))(
      _.`type`()
    )

object MetallurgyNamedTypeArgument:
  object ElementType extends IElementType("METALLURGY_NAMED_TYPE_ARGUMENT", ScalaLanguage.INSTANCE), SelfPsiCreator:
    override def createElement(node: ASTNode): PsiElement = new MetallurgyNamedTypeArgument(node)
