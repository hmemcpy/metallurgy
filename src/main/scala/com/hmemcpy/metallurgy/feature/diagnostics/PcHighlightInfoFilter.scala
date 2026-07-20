package com.hmemcpy.metallurgy.feature.diagnostics

import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.hmemcpy.metallurgy.pc.{PcSessionManager, PcSnapshot}
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.codeInsight.daemon.impl.{HighlightInfo, HighlightInfoFilter}
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiFile

/** Keeps bundled errors only when the current compiler snapshot reports an overlapping error. On stale or unavailable
  * compiler data, the bundled result is left untouched.
  */
final class PcHighlightInfoFilter extends HighlightInfoFilter:

  override def accept(info: HighlightInfo, file: PsiFile): Boolean =
    if !isAnnotatorError(info) || ApplicationManager.getApplication.isDispatchThread then true
    else
      val project = file.getProject
      val module  = Option(ModuleUtilCore.findModuleForPsiElement(file))
      val vFile   = Option(file.getVirtualFile)
      val current =
        for
          candidate   <- module
          if ModuleDetectionService.get(project).isEligible(candidate)
          if MetallurgySettings(project).isEnabled(candidate)
          sourceFile  <- vFile
          document    <- Option(FileDocumentManager.getInstance.getDocument(sourceFile))
          session     <- PcSessionManager.get(project).sessionFor(candidate)
          diagnostics <- session.diagnostics(
                           PcSnapshot(sourceFile.getUrl, document.getModificationStamp, document.getText)
                         )
        yield diagnostics

      current.fold(true): diagnostics =>
        diagnostics.exists: diagnostic =>
          diagnostic.isError && diagnostic.range.intersectsStrict(info.getStartOffset, info.getEndOffset)

  private def isAnnotatorError(info: HighlightInfo): Boolean =
    info.getSeverity == HighlightSeverity.ERROR && info.isFromAnnotator
