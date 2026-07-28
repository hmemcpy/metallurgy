package com.hmemcpy.metallurgy.psiproducer

import com.intellij.lang.Language
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.LanguageSubstitutor
import com.hmemcpy.metallurgy.module.ModuleDetectionService

/** Selects neutral or ready syntax from the module's exact-parser state. */
final class Scala3DotcLanguageSubstitutor extends LanguageSubstitutor:
  override def getLanguage(file: VirtualFile, project: Project): Language =
    val module = ModuleUtilCore.findModuleForFile(file, project)
    if module != null && ModuleDetectionService.get(project).isActive(module) then
      Scala3ParserPreparationLifecycle.get(project).languageFor(module)
    else null
