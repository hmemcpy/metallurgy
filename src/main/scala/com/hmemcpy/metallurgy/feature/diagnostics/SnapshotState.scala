package com.hmemcpy.metallurgy.feature.diagnostics

import com.hmemcpy.metallurgy.pc.PcDiagnostic

/** Lifecycle of one file's pc analysis, as observed by the highlighting extension points.
  *
  * The [[PcDiagnosticSetCache]] is the single writer per `(file, DocumentVersion)`; the ExternalAnnotator (add side)
  * and the `HighlightInfoFilter` (suppress side) only read it. This is the pc-side analogue of the upstream
  * `DocumentUtil.stillValid(documentVersions)` gate in `ExternalHighlightersService`: a result is authoritative only
  * for the exact version it was computed against.
  *
  *   - `Pending` — analysis for this version is in flight. Blank-while-pending: show no semantic red until pc settles.
  *     The retypecheck writer flips a file to `Pending` synchronously on each edit, ahead of the async daemon pass, so
  *     this state is observed — never inferred from a version mismatch.
  *   - `CurrentSuccess` — pc accepted this exact version. Authoritative: an empty diagnostic list means "no semantic
  *     errors" (suppress bundled semantic red); the annotator emits pc's diagnostics.
  *   - `Failed` — pc could not analyze this version. Leave bundled diagnostics untouched (uncertainty).
  *   - `Unavailable` — no entry: Metallurgy inactive, the file was never analyzed, or the document has moved past the
  *     last published version. Leave bundled untouched.
  */
sealed trait SnapshotState

object SnapshotState:
  case class Pending(version: Long)                                        extends SnapshotState
  case class CurrentSuccess(version: Long, diagnostics: Seq[PcDiagnostic]) extends SnapshotState
  case class Failed(version: Long)                                         extends SnapshotState
  case object Unavailable                                                  extends SnapshotState
