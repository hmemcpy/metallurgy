package com.hmemcpy.metallurgy.feature.diagnostics

import com.hmemcpy.metallurgy.pc.PcDiagnostic
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Project-level single-writer store of the current pc analysis per file.
  *
  * The retypecheck flow is the only writer:
  *   - [[markPending]] on document edit,
  *   - [[publishSuccess]] / [[publishFailed]] when the compile settles,
  *   - [[markUnavailable]] on session close / module removal / Metallurgy disable.
  *
  * The ExternalAnnotator and `HighlightInfoFilter` only read. A publish for a version that has already been superseded
  * is dropped, so pc runs at most once per `(file, version)` and a stale result can never overwrite a newer pending
  * state. Lock-free: a `ConcurrentHashMap` of `AtomicReference`s; readers never block.
  */
final class PcDiagnosticSetCache extends Disposable:

  private val entries = new ConcurrentHashMap[String, AtomicReference[SnapshotState]]()

  /** Record that analysis of `version` is in flight. A strictly newer version supersedes any older state; an equal or
    * older version never regresses an already-published result.
    */
  def markPending(fileUrl: String, version: Long): Unit =
    val ref = entries.computeIfAbsent(fileUrl, _ => new AtomicReference(SnapshotState.Pending(version)))
    val _   = ref.updateAndGet: current =>
      if stampOf(current).exists(_ < version) then SnapshotState.Pending(version) else current

  /** Publish a successful analysis. Applied only when `version` is still the pending version; a publish for a
    * superseded version is a no-op.
    */
  def publishSuccess(fileUrl: String, version: Long, diagnostics: Seq[PcDiagnostic]): Unit =
    transitionIfPending(fileUrl, version, SnapshotState.CurrentSuccess(version, diagnostics))

  /** Record that analysis of `version` failed. Applied only when `version` is still the pending version. */
  def publishFailed(fileUrl: String, version: Long): Unit =
    transitionIfPending(fileUrl, version, SnapshotState.Failed(version))

  /** Drop all state for a file (session closed / module removed / Metallurgy disabled). */
  def markUnavailable(fileUrl: String): Unit =
    val _ = entries.remove(fileUrl)

  /** The current state for `fileUrl` relative to `currentVersion` (the document's modification stamp).
    *
    * A stored state is returned only when its version exactly matches `currentVersion`; any mismatch (the document has
    * advanced, or a stale entry lingers) yields `Unavailable`, so a stale result is never applied to newer text.
    * Blank-while-pending is driven by the retypecheck writer flipping the file to `Pending` on each edit — not inferred
    * here from a version mismatch, which would risk blanking forever after a `Failed`.
    */
  def stateFor(fileUrl: String, currentVersion: Long): SnapshotState =
    Option(entries.get(fileUrl)) match
      case None      => SnapshotState.Unavailable
      case Some(ref) =>
        val state = ref.get()
        if stampOf(state).contains(currentVersion) then state else SnapshotState.Unavailable

  /** Resolve the live document version for `file` and report its state. */
  def stateForFile(file: VirtualFile): SnapshotState =
    Option(FileDocumentManager.getInstance.getDocument(file)) match
      case None           => SnapshotState.Unavailable
      case Some(document) => stateFor(file.getUrl, document.getModificationStamp)

  override def dispose(): Unit = entries.clear()

  private def stampOf(state: SnapshotState): Option[Long] = state match
    case SnapshotState.Pending(v)           => Some(v)
    case SnapshotState.CurrentSuccess(v, _) => Some(v)
    case SnapshotState.Failed(v)            => Some(v)
    case SnapshotState.Unavailable          => None

  private def transitionIfPending(fileUrl: String, version: Long, next: SnapshotState): Unit =
    Option(entries.get(fileUrl)).foreach: ref =>
      val _ = ref.updateAndGet:
        case SnapshotState.Pending(v) if v == version => next
        case other                                    => other

object PcDiagnosticSetCache:
  def get(project: Project): PcDiagnosticSetCache = project.getService(classOf[PcDiagnosticSetCache])
