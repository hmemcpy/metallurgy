package com.hmemcpy.metallurgy.feature.diagnostics

import com.hmemcpy.metallurgy.pc.PcDiagnostic
import com.intellij.codeInsight.daemon.impl.{HighlightInfo, HighlightInfoType, UpdateHighlightersUtil}
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager

import scala.annotation.nowarn
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Renders pc diagnostics to the editor on a dedicated markup layer, mirroring the bundled plugin's
  * `ExternalHighlightersService` (the compile-server / CBH pass). Writing directly via `UpdateHighlightersUtil` on a
  * distinct pass id bypasses the daemon, so pc's verdict appears on publish without a `DaemonCodeAnalyzer` restart (ADR
  * 0009). The `PcDiagnosticSetCache` writer is the only caller; the `PcHighlightInfoFilter` handles the daemon-side
  * suppression of the bundled annotator.
  */
final class PcHighlightRenderer(project: Project) extends Disposable:

  private val Log = Logger.getInstance(classOf[PcHighlightRenderer])

  /** Replace the Metallurgy layer for `fileUrl` with `diagnostics`; an empty list clears it (blank-while-pending or
    * teardown). Scheduled onto the EDT and applied to every open editor for the file.
    */
  def render(fileUrl: String, diagnostics: Seq[PcDiagnostic]): Unit =
    invoke(fileUrl, diagnostics.map(toHighlightInfo))

  def blank(fileUrl: String): Unit =
    invoke(fileUrl, Seq.empty)

  private def invoke(fileUrl: String, infos: Seq[HighlightInfo]): Unit =
    ApplicationManager.getApplication.invokeLater: () =>
      if !project.isDisposed then
        try writeToOpenEditors(fileUrl, infos)
        catch case NonFatal(error) => Log.warn(s"Failed to render pc highlights for $fileUrl", error)

  @nowarn("cat=deprecation")
  private def writeToOpenEditors(fileUrl: String, infos: Seq[HighlightInfo]): Unit =
    val read: Runnable = () =>
      val file = VirtualFileManager.getInstance.findFileByUrl(fileUrl)
      if file != null then
        val document = FileDocumentManager.getInstance.getDocument(file)
        if document != null then
          val collection = infos.asJavaCollection
          for
            editor <- EditorFactory.getInstance.getAllEditors
            if document == editor.getDocument && project == editor.getProject
          do
            UpdateHighlightersUtil.setHighlightersToEditor(
              project,
              document,
              0,
              document.getTextLength,
              collection,
              editor.getColorsScheme,
              PcHighlightRenderer.PassId
            )
    ApplicationManager.getApplication.runReadAction(read)

  private def toHighlightInfo(diagnostic: PcDiagnostic): HighlightInfo =
    val kind = if diagnostic.isError then HighlightInfoType.ERROR else HighlightInfoType.WARNING
    HighlightInfo
      .newHighlightInfo(kind)
      .range(diagnostic.range)
      .descriptionAndTooltip(diagnostic.message)
      .group(PcHighlightRenderer.PassId)
      .create()

  override def dispose(): Unit = ()

object PcHighlightRenderer:
  /** Distinct from the bundled `ScalaCompilerPassId` (`979132998`) so Metallurgy's markup layer never clobbers or
    * duplicates CBH's — they coexist as separate groups keyed by pass id.
    */
  final val PassId: Int = 979133000

  def get(project: Project): PcHighlightRenderer = project.getService(classOf[PcHighlightRenderer])
