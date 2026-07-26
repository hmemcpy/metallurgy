package com.hmemcpy.metallurgy.psiproducer

import com.intellij.lang.Language
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{FileViewProviderFactory, PsiManager}

final class Scala3DotcFileViewProviderFactory extends FileViewProviderFactory:
  override def createFileViewProvider(
      file: VirtualFile,
      language: Language,
      manager: PsiManager,
      eventSystemEnabled: Boolean
  ): Scala3DotcFileViewProvider =
    new Scala3DotcFileViewProvider(manager, file, eventSystemEnabled, language)
