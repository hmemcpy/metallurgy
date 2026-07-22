package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.pc.PcSessionManager
import com.intellij.codeHighlighting.EditorBoundHighlightingPass
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.psi.PsiFile

/** Schedules immutable Scala 3 compiler-backend population independently of every visible consumer. */
final class CompilerBackendPass(editor: Editor, file: PsiFile) extends EditorBoundHighlightingPass(editor, file, false):

  override def doCollectInformation(indicator: ProgressIndicator): Unit =
    indicator.checkCanceled()
    Option(file.getVirtualFile).foreach: virtualFile =>
      val project = file.getProject
      val future  = PcSessionManager.get(project).prepareCompilerBackend(virtualFile)
      if !future.isDone then
        val _ = future.thenAccept:
          case Some(_) =>
            ApplicationManager.getApplication.invokeLater: () =>
              if !project.isDisposed && file.isValid && CompilerBackendPassFactory.isActiveScala(file) then
                DaemonCodeAnalyzer.getInstance(project).restart(file, "Scala compiler backend snapshot published")
          case None    => ()

  override def doApplyInformationToEditor(): Unit = ()
