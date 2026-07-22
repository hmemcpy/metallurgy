package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.{ProcessCanceledException, ProgressIndicator, Task}
import com.intellij.openapi.project.Project

import java.nio.file.Path
import java.util.concurrent.{CompletableFuture, Executor}
import scala.util.control.NonFatal

private[pc] trait BackgroundRunner:
  def submit(title: String)(work: () => Path): CompletableFuture[Path]

private[pc] object BackgroundRunner:

  val direct: BackgroundRunner = fromExecutor((command: Runnable) => command.run())

  def fromExecutor(executor: Executor): BackgroundRunner =
    new BackgroundRunner:
      override def submit(title: String)(work: () => Path): CompletableFuture[Path] =
        CompletableFuture.supplyAsync(() => work(), executor)

  def intellij(project: Project): BackgroundRunner =
    new BackgroundRunner:
      override def submit(title: String)(work: () => Path): CompletableFuture[Path] =
        val result             = new CompletableFuture[Path]()
        val schedule: Runnable = () =>
          if project.isDisposed then
            val _ = result.cancel(false)
          else
            val task = new Task.Backgroundable(project, title, true):
              override def run(indicator: ProgressIndicator): Unit =
                try
                  indicator.checkCanceled()
                  val resolved = work()
                  indicator.checkCanceled()
                  val _        = result.complete(resolved)
                catch
                  case canceled: ProcessCanceledException =>
                    val _ = result.cancel(true)
                    throw canceled
                  case NonFatal(error)                    =>
                    val _ = result.completeExceptionally(error)

              override def onCancel(): Unit =
                val _ = result.cancel(true)

            task.queue()

        ApplicationManager.getApplication.invokeLater(schedule)
        result
