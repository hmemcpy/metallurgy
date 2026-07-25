package com.hmemcpy.metallurgy.feature.completion

import com.hmemcpy.metallurgy.compilerbackend.Scala3CompilerBackend
import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.hmemcpy.metallurgy.pc.PcSessionManager
import com.intellij.codeInsight.completion.{CompletionContributor, CompletionParameters, CompletionResultSet}
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiDocumentManager

final class Scala3PcCompletionContributor extends CompletionContributor:

  private val Log = Logger.getInstance(classOf[Scala3PcCompletionContributor])

  override def fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet): Unit =
    val file    = parameters.getOriginalFile
    val project = file.getProject
    Log.debug(s"PC completion invoked for ${file.getName}:${parameters.getOffset}")
    val vfile   = file.getVirtualFile
    if vfile == null then
      Log.debug("PC completion skipped: no virtual file")
      return

    val module = ModuleUtilCore.findModuleForPsiElement(file) match
      case null =>
        Log.debug("PC completion skipped: no module")
        return
      case m    => m

    if !ModuleDetectionService.get(project).isActive(module) then
      Log.debug(s"PC completion skipped: ${module.getName} does not have an active Scala 3 compiler backend")
      return

    val document = PsiDocumentManager.getInstance(project).getDocument(file)
    if document == null then
      Log.debug("PC completion skipped: no document")
      return

    PcSessionManager
      .get(project)
      .sessionFor(module)
      .map(_.complete(vfile.getUrl, document.getText, document.getModificationStamp, parameters.getOffset))
      .foreach: items =>
        Log.debug(s"PC completion returned ${items.size} items for ${vfile.getName}:${parameters.getOffset}")
        val backend = Scala3CompilerBackend.get(project)
        PcCompletionMerger.mergeRemainingContributors(
          parameters,
          result,
          items,
          item =>
            item.symbol.flatMap: symbolId =>
              backend.symbolTargetForCompletion(
                file,
                module,
                document.getModificationStamp,
                symbolId,
                item.lookupName,
                item.detail.getOrElse(item.label),
                item.isType
              )
        )
