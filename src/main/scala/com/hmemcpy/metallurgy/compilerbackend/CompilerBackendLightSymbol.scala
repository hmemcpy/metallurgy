package com.hmemcpy.metallurgy.compilerbackend

import com.intellij.psi.impl.light.LightElement
import com.intellij.psi.search.{LocalSearchScope, SearchScope}
import com.intellij.psi.{PsiElement, PsiElementVisitor, PsiFile, PsiIdentifier, PsiManager, PsiNameIdentifierOwner}
import com.intellij.util.IncorrectOperationException
import org.jetbrains.plugins.scala.ScalaLanguage

/** Generation-scoped IntelliJ identity for a compiler symbol that has no source PSI target. */
private[compilerbackend] final class CompilerBackendLightSymbol(
    manager: PsiManager,
    containingFile: PsiFile,
    val symbolId: String,
    name: String,
    val renderedType: String,
    val flags: Set[String],
    current: () => Boolean
) extends LightElement(manager, ScalaLanguage.INSTANCE)
    with PsiNameIdentifierOwner:

  override def getName: String = name

  override def getNameIdentifier: PsiIdentifier = null

  override def getText: String = name

  override def getContainingFile: PsiFile = containingFile

  override def getContext: PsiFile = containingFile

  override def getUseScope: SearchScope = LocalSearchScope(containingFile)

  override def isValid: Boolean = current()

  override def isWritable: Boolean = false

  override def setName(newName: String): PsiElement =
    throw new IncorrectOperationException(s"Compiler-only symbol '$name' cannot be renamed")

  override def copy(): PsiElement =
    throw new IncorrectOperationException(s"Compiler-only symbol '$name' cannot be copied")

  override def accept(visitor: PsiElementVisitor): Unit = visitor.visitElement(this)

  override def toString: String = s"CompilerBackendLightSymbol($symbolId)"
