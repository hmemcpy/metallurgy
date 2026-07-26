package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.PcSessionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.AppExecutorUtil

/** Schedules the compiler backend pass for a file whose parse just entered the pending state. Runs off the parse thread
  * (never blocks it); the backend publishes a terminal state and reloads the file when the compiler decides.
  */
object ProducerParseScheduler:

  def schedule(psi: PsiElement): Unit =
    val project = psi.getProject
    val vfile   = read(() => Option(psi.getContainingFile).flatMap(f => Option(f.getVirtualFile)).orNull)
    if vfile == null then return
    val _       = AppExecutorUtil.getAppExecutorService.submit(
      new Runnable:
        override def run(): Unit =
          val _ = PcSessionManager.get(project).prepareCompilerBackend(vfile)
    )

  private def read[A](computation: () => A): A =
    if ApplicationManager.getApplication.isReadAccessAllowed then computation()
    else
      ApplicationManager.getApplication.runReadAction(new Computable[A] { override def compute(): A = computation() })
