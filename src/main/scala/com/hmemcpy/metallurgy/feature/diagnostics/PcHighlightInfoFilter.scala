package com.hmemcpy.metallurgy.feature.diagnostics

import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.intellij.codeInsight.daemon.impl.{HighlightInfo, HighlightInfoFilter}
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiFile

/** Suppresses the bundled semantic annotator's errors once pc has spoken for the current document version
  * (PC-authoritative: an empty `CurrentSuccess` means clean), and blanks them while pc analysis is in flight
  * (blank-while-pending). Parser errors, warnings, and anything from a non-active module are left untouched. pc's own
  * diagnostics are rendered on a separate markup layer by [[PcHighlightRenderer]] (ADR 0009); this filter only removes
  * the bundled annotator's competing semantic red.
  */
final class PcHighlightInfoFilter extends HighlightInfoFilter:

  override def accept(info: HighlightInfo, file: PsiFile): Boolean =
    if !isSemanticError(info) then true
    else
      val project = file.getProject
      Option(ModuleUtilCore.findModuleForPsiElement(file)) match
        case Some(module) if ModuleDetectionService.get(project).isActive(module) =>
          val virtualFile = file.getVirtualFile
          if virtualFile == null then true
          else
            PcDiagnosticSetCache.get(project).stateForFile(virtualFile) match
              case SnapshotState.Pending(_) | SnapshotState.CurrentSuccess(_, _) => false
              case SnapshotState.Failed(_) | SnapshotState.Unavailable           => true
        case _                                                                    => true

  private def isSemanticError(info: HighlightInfo): Boolean =
    info.getSeverity == HighlightSeverity.ERROR && info.isFromAnnotator
