package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.pc.PcSessionManager
import com.intellij.codeHighlighting.EditorBoundHighlightingPass
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.psi.PsiFile

/** Schedules immutable Scala 3 compiler-backend population. The publisher restarts the daemon on EDT when the snapshot
  * commits, so reference resolve caches are re-evaluated without a tab switch.
  */
final class CompilerBackendPass(editor: Editor, file: PsiFile) extends EditorBoundHighlightingPass(editor, file, false):

  override def doCollectInformation(indicator: ProgressIndicator): Unit =
    indicator.checkCanceled()
    Option(file.getVirtualFile).foreach: virtualFile =>
      val _ = PcSessionManager.get(file.getProject).prepareCompilerBackend(virtualFile)

  override def doApplyInformationToEditor(): Unit = ()
