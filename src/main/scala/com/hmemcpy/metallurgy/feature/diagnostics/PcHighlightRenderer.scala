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
import com.intellij.util.Alarm

import java.util.concurrent.ConcurrentHashMap
import scala.annotation.nowarn
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Renders pc diagnostics to the editor on a dedicated markup layer, mirroring the bundled plugin's
  * `ExternalHighlightersService` (the compile-server / CBH pass). Writing directly via `UpdateHighlightersUtil` on a
  * distinct pass id bypasses the daemon, so pc's verdict appears on publish without a `DaemonCodeAnalyzer` restart (ADR
  * 0009). The `PcDiagnosticSetCache` writer is the only caller.
  *
  * Requests are coalesced per file on a short debounce: rapid publishes and the per-keystroke `blank` collapse so only
  * the latest request per file is applied per debounce window — no per-keystroke EDT flood. Every version-bound request
  * is still gated on the document's modification stamp at apply time (the `DocumentUtil.stillValid` analogue), so a
  * superseded publish can never paint old ranges over newer text.
  */
final class PcHighlightRenderer(project: Project) extends Disposable:

  private val Log     = Logger.getInstance(classOf[PcHighlightRenderer])
  private val pending = new ConcurrentHashMap[String, RenderRequest]()
  private val alarm   = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

  /** Replace the Metallurgy layer for `fileUrl`/`version` with `diagnostics`; dropped if the document has since moved
    * past `version`.
    */
  def render(fileUrl: String, version: Long, diagnostics: Seq[PcDiagnostic]): Unit =
    schedule(fileUrl, RenderRequest(Some(version), diagnostics.map(toHighlightInfo)))

  /** Clear the Metallurgy layer for a `version` now in flight (blank-while-pending); dropped if the document has since
    * moved past it.
    */
  def blank(fileUrl: String, version: Long): Unit =
    schedule(fileUrl, RenderRequest(Some(version), Seq.empty))

  /** Unconditionally clear the layer for `fileUrl` (session discard / module removal / opt-out). */
  def erase(fileUrl: String): Unit =
    schedule(fileUrl, RenderRequest(None, Seq.empty))

  private def schedule(fileUrl: String, request: RenderRequest): Unit =
    pending.put(fileUrl, request)
    if !alarm.isDisposed then
      alarm.cancelAllRequests()
      val _ = alarm.addRequest(() => flush(), PcHighlightRenderer.DebounceMillis)

  /** Drain and apply the latest request per file. Runs on the EDT (SWING_THREAD alarm). */
  private def flush(): Unit =
    val it = pending.entrySet().iterator()
    while it.hasNext do
      val entry = it.next()
      it.remove()
      try writeToOpenEditors(entry.getKey, entry.getValue)
      catch case NonFatal(error) => Log.warn(s"Failed to render pc highlights for ${entry.getKey}", error)

  @nowarn("cat=deprecation")
  private def writeToOpenEditors(fileUrl: String, request: RenderRequest): Unit =
    val read: Runnable = () =>
      val file = VirtualFileManager.getInstance.findFileByUrl(fileUrl)
      if file != null then
        val document = FileDocumentManager.getInstance.getDocument(file)
        // stillValid gate: a superseded publish (document moved past `version`) must not paint stale ranges.
        if document != null && request.version.forall(_ == document.getModificationStamp) then
          val psiFile    = PsiManager.getInstance(project).findFile(file)
          val collection = request.infos.asJavaCollection
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

  override def dispose(): Unit = alarm.dispose()

  private final case class RenderRequest(version: Option[Long], infos: Seq[HighlightInfo])

object PcHighlightRenderer:
  /** Collapse rapid publishes/blanks into one apply per window. */
  private val DebounceMillis: Long = 30L

  /** Distinct from the bundled `ScalaCompilerPassId` (`979132998`) so Metallurgy's markup layer never clobbers or
    * duplicates CBH's — they coexist as separate groups keyed by pass id.
    */
  final val PassId: Int = 979133000

  def get(project: Project): PcHighlightRenderer = project.getService(classOf[PcHighlightRenderer])
