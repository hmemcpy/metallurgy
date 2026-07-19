package com.hmemcpy.metallurgy.feature.completion

import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.codeInsight.completion.{CompletionContributor, CompletionParameters, CompletionResultSet}
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.diagnostic.Logger

final class Scala3PcCompletionContributor extends CompletionContributor {

  private val Log = Logger.getInstance(classOf[Scala3PcCompletionContributor])

  override def fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet): Unit = {
    val project = parameters.getOriginalFile.getProject
    val file = parameters.getOriginalFile
    val vfile = file.getVirtualFile
    if (vfile == null) return

    val module = org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil.getModule(file) match {
      case null => return
      case m    => m
    }

    if (!ModuleDetectionService.get(project).isEligible(module)) return
    if (!MetallurgySettings(project).isEnabled(module)) return

    // Phase 1 completion augmentation will be wired here once PcSession can
    // actually invoke pc.complete(). For now, this is a no-op that gets out of
    // the way of the bundled plugin's contributor.
    super.fillCompletionVariants(parameters, result)
  }
}
