package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.progress.{ProgressIndicator, Task}
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
        val result = new CompletableFuture[Path]()
        new Task.Backgroundable(project, title, true):
          override def run(indicator: ProgressIndicator): Unit =
            try result.complete(work())
            catch case NonFatal(error) => result.completeExceptionally(error)
        .queue()
        result
