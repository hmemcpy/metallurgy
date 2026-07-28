package org.virtuslab.ideprobe.handlers

import java.util
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.locks.LockSupport

import scala.jdk.CollectionConverters._

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.DaemonListener
import com.intellij.codeInsight.daemon.impl.{DaemonCodeAnalyzerImpl, HighlightInfo}
import com.intellij.openapi.editor.{Document, Editor}
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.impl.text.AsyncEditorLoader
import com.intellij.openapi.fileEditor.{FileEditor, FileEditorManager, OpenFileDescriptor, TextEditor}
import com.intellij.openapi.project.{DumbService, Project}
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiDocumentManager, PsiFile}
import com.intellij.util.ui.UIUtil

import org.virtuslab.ideprobe.protocol
import org.virtuslab.ideprobe.protocol.FileRef

object Highlighting extends IntelliJApi {
  def infos(fileRef: FileRef): Seq[protocol.HighlightInfo] = {
    val psiFile = PSI.resolve(fileRef)
    val project = psiFile.getProject
    DumbService.getInstance(project).waitForSmartMode()
    val document = PSI.getDocument(psiFile)
    runHighlighting(psiFile)
    val highlights = read(DaemonCodeAnalyzerImpl.getHighlights(document, null, project).asScala.toVector)
    highlights.flatMap(format(_, fileRef, document))
  }

  private def format(
      info: HighlightInfo,
      fileRef: FileRef,
      document: Document
  ): Option[protocol.HighlightInfo] = {
    Option(info.getDescription).map { description =>
      val severity = info.getSeverity
      protocol.HighlightInfo(
        fileRef.path,
        document.getLineNumber(info.getStartOffset) + 1,
        info.getStartOffset,
        info.getEndOffset,
        protocol.HighlightInfo.Severity.from(severity.getName, severity.myVal),
        description
      )
    }
  }

  private def runHighlighting(psiFile: PsiFile): Unit = {
    val project    = psiFile.getProject
    var editor     = Option.empty[Editor]
    val completion = new CountDownLatch(1)
    val connection = project.getMessageBus.connect()
    connection.subscribe(
      DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC,
      new DaemonListener {
        override def daemonFinished(fileEditors: util.Collection[_ <: FileEditor]): Unit = {
          if (editor.exists(current => finishedFor(current, fileEditors))) completion.countDown()
        }
      }
    )
    try {
      editor = Some(runOnUISync(createEditor(project, psiFile.getVirtualFile)))
      runOnUISync(DaemonCodeAnalyzer.getInstance(project).restart(psiFile, "ide-probe highlighting request"))
      if (!completion.await(2, TimeUnit.MINUTES))
        error(s"Highlighting did not finish for ${psiFile.getVirtualFile.getPath}")
    } finally connection.disconnect()
  }

  private def finishedFor(editor: Editor, fileEditors: util.Collection[_ <: FileEditor]): Boolean = {
    fileEditors.asScala.exists {
      case text: TextEditor => text.getEditor == editor
      case _                => false
    }
  }

  private def createEditor(project: Project, file: VirtualFile): Editor = {
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file), false)
    if (editor == null) error(s"Could not open editor for ${file.getPath}")
    if (EditorUtil.isRealFileEditor(editor)) {
      UIUtil.dispatchAllInvocationEvents()
      while (!AsyncEditorLoader.isEditorLoaded(editor)) {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100))
        UIUtil.dispatchAllInvocationEvents()
      }
    }
    editor
  }
}
