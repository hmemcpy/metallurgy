package com.hmemcpy.metallurgy.compilerbackend

import com.intellij.psi.impl.light.LightPsiClassBuilder
import com.intellij.psi.search.{LocalSearchScope, SearchScope}
import com.intellij.psi.{PsiElement, PsiFile}
import com.intellij.util.IncorrectOperationException

/** Generation-scoped IntelliJ class identity for a compiler type symbol that has no source PSI target. */
private[compilerbackend] final class CompilerBackendLightClass(
    containingFile: PsiFile,
    val symbolId: String,
    name: String,
    qualifiedName: Option[String],
    val renderedType: String,
    val flags: Set[String],
    current: () => Boolean
) extends LightPsiClassBuilder(containingFile, name):

  override def getQualifiedName: String = qualifiedName.orNull

  override def getContainingFile: PsiFile = containingFile

  override def getContext: PsiFile = containingFile

  override def getUseScope: SearchScope = LocalSearchScope(containingFile)

  override def isValid: Boolean = current()

  override def isWritable: Boolean = false

  override def setName(newName: String): PsiElement =
    throw new IncorrectOperationException(s"Compiler-only type '$name' cannot be renamed")

  override def copy(): PsiElement =
    throw new IncorrectOperationException(s"Compiler-only type '$name' cannot be copied")

  override def toString: String = s"CompilerBackendLightClass($symbolId)"
