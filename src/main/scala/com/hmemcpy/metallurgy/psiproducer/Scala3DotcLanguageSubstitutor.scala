package com.hmemcpy.metallurgy.psiproducer

import com.intellij.lang.Language
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.LanguageSubstitutor
import com.hmemcpy.metallurgy.module.ModuleDetectionService

/** Substitutes the Metallurgy Scala 3 dialect for active modules. Registered for the `"Scala"` language and ordered
  * before the bundled `ScalaLanguageSubstitutor`: an active Scala 3 module returns the dialect; everything else returns
  * `null` so the bundled substitutor selects ordinary Scala 3 or Scala 2. IntelliJ's substitutor chain returns after
  * the first non-null result, so returning `null` lets the bundled path run unchanged for inactive modules.
  */
final class Scala3DotcLanguageSubstitutor extends LanguageSubstitutor:
  override def getLanguage(file: VirtualFile, project: Project): Language =
    val module = ModuleUtilCore.findModuleForFile(file, project)
    if module != null && ModuleDetectionService.get(project).isActive(module) then Scala3DotcLanguage.INSTANCE
    else null
