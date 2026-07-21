package com.hmemcpy.metallurgy.feature.diagnostics

import com.hmemcpy.metallurgy.pc.PcDiagnostic
import com.intellij.codeInsight.daemon.impl.{
  ErrorStripeUpdateManager,
  HighlightInfo,
  HighlightInfoType,
  UpdateHighlightersUtil
}
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager

import scala.annotation.nowarn
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Renders pc diagnostics to the editor on a dedicated markup layer, mirroring the bundled plugin's
  * `ExternalHighlightersService` (the compile-server / CBH pass). Writing directly via `UpdateHighlightersUtil` on a
  * distinct pass id bypasses the daemon, so pc's verdict appears on publish without a `DaemonCodeAnalyzer` restart (ADR
  * 0009). The `PcDiagnosticSetCache` writer is the only caller.
  *
  * Every version-bound write (`render`/`blank`) is gated on the document's modification stamp still matching the
  * published version at apply time — the analogue of upstream `DocumentUtil.stillValid`, which stops a superseded
  * publish from painting old ranges over newer text. `erase` is version-less and unconditional, for teardown.
  *
  * NOTE (follow-ups): coalescing rapid publishes (`coalesceBy`) and throttling the per-keystroke `blank` are not yet
  * implemented — the bundled service uses `ReadAction.nonBlocking(...).coalesceBy(file)`; this uses a bare
  * `invokeLater` for now.
  */
final class PcHighlightRenderer(project: Project) extends Disposable:

  private val Log = Logger.getInstance(classOf[PcHighlightRenderer])

  /** Replace the Metallurgy layer for `fileUrl`/`version` with `diagnostics`; dropped if the document has since moved
    * past `version`.
    */
  def render(fileUrl: String, version: Long, diagnostics: Seq[PcDiagnostic]): Unit =
    invoke(fileUrl, Some(version), diagnostics.map(toHighlightInfo))

  /** Clear the Metallurgy layer for a `version` that is now in flight (blank-while-pending); dropped if the document
    * has since moved past it.
    */
  def blank(fileUrl: String, version: Long): Unit =
    invoke(fileUrl, Some(version), Seq.empty)

  /** Unconditionally clear the layer for `fileUrl` (session discard / module removal / opt-out). */
  def erase(fileUrl: String): Unit =
    invoke(fileUrl, None, Seq.empty)

  private def invoke(fileUrl: String, version: Option[Long], infos: Seq[HighlightInfo]): Unit =
    ApplicationManager.getApplication.invokeLater: () =>
      if !project.isDisposed then
        try writeToOpenEditors(fileUrl, version, infos)
        catch case NonFatal(error) => Log.warn(s"Failed to render pc highlights for $fileUrl", error)

  @nowarn("cat=deprecation")
  private def writeToOpenEditors(fileUrl: String, version: Option[Long], infos: Seq[HighlightInfo]): Unit =
    val read: Runnable = () =>
      val file = VirtualFileManager.getInstance.findFileByUrl(fileUrl)
      if file != null then
        val document = FileDocumentManager.getInstance.getDocument(file)
        // stillValid gate: a superseded publish (document moved past `version`) must not paint stale ranges.
        if document != null && version.forall(_ == document.getModificationStamp) then
          val psiFile    = PsiManager.getInstance(project).findFile(file)
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
            if psiFile != null then
              ErrorStripeUpdateManager.getInstance(project).launchRepaintErrorStripePanel(editor, psiFile)
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
