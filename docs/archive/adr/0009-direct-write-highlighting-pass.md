# Render pc diagnostics via a direct-write highlighting pass

The presentation compiler publishes its diagnostics **asynchronously**, off the IntelliJ daemon pass. The
question is how those results reach the editor. The earlier architecture sketch
([`docs/research/14`](../research/14-codex-highlighting-architecture.md), from the codex consultation)
proposed an `externalAnnotator` (add-side) + a cache-only `HighlightInfoFilter` (suppress-side). That leaves
an unresolved crux: after `pc` settles and flips the per-file snapshot to `CurrentSuccess`, **nothing
re-triggers highlighting** — the daemon pass that ran on the edit saw `Pending` and blanked semantic red; pc's
fresh verdict never renders without another keystroke. `blank-while-pending` alone is then unsafe: it hides
real errors pc finds for the whole Pending window with no prompt re-show.

A kimi consultation grounded in the canonical reference — the bundled plugin's `ExternalHighlightersService`
(`~/git/intellij-scala/.../compiler/highlighting/ExternalHighlightersService.scala`) — resolved it.

## Decision

Render pc diagnostics the same way the bundled compile-server (CBH) path does: **write them directly to the
editor via `UpdateHighlightersUtil.setHighlightersToEditor(...)` on a dedicated pass id**, driven from the
writer's publish path. Keep the `HighlightInfoFilter` (it suppresses the bundled *annotator's* semantic red,
which lives on the daemon's own layer); **drop the `externalAnnotator`** — the direct write is the add-side.

1. **Distinct pass id.** A constant `MetallurgyPcPassId` ≠ `ScalaCompilerPassId` (`979132998`). `UpdateHighlightersUtil`
   keys markup by group, so Metallurgy's layer never clobbers or duplicates CBH's; they coexist as separate
   layers (and opted-in modules don't run the competing path anyway).
2. **Publish path** (`PcSessionManager.publishOutcome`, pooled thread): `ReadAction.nonBlocking(buildInfos)`
   `.inSmartMode.expireWhen(version-mismatch).coalesceBy(virtualFile).finishOnUiThread(setHighlightersToEditor
   + ErrorStripeUpdateManager)`. The `expireWhen` gate is the cache's own `stateFor` version check — the
   analogue of upstream `DocumentUtil.stillValid(documentVersions)`. `coalesceBy(virtualFile)` collapses rapid
   publishes to the latest.
3. **Blank the pc layer during `Pending`.** The same empty-list write fires from `markPending` (throttled), so
   stale pc squiggles from version *N−1* don't linger through the Pending window. This closes the
   "blank-while-pending hides real errors" hole: bundled red is blanked by the filter, pc's own layer by the
   empty write, and pc's fresh verdict lands ~300 ms later with no keystroke.
4. **Erase on teardown.** On file close / module opt-out / `markUnavailable`, write an empty collection for the
   file (mirror `ExternalHighlightersService.eraseAllHighlightings`) so pc highlights don't outlive Metallurgy.

## Why not the alternatives

- **`DaemonCodeAnalyzer.restart(file)` on publish** — public API, but a full daemon re-run per publish (every
  inspection, both annotators), restart-per-publish coalescing out of our control, and `restart` during a
  `Pending`→publish race interleaves badly.
- **`ExternalAnnotator` + restart** — integrates with "next error" navigation, but inherits the restart
  chattiness and needs the annotator purely to re-emit what the direct write does natively.

## Consequences

- The async-refresh crux is solved by **bypassing the daemon** for pc's layer entirely; only the bundled
  annotator's suppression still flows through the daemon (via the `HighlightInfoFilter`).
- Coupling is to stable OpenAPI (`UpdateHighlightersUtil`) plus the same load-bearing calls CBH already
  carries — bounded and mirrored, not novel.
- **Trade-off:** pc highlights are invisible to inspection results / batch analysis / "next error" navigation
  (daemon-owned features). Acceptable for v1 (the goal is editor squiggles); revisit via a custom
  `TextEditorHighlightingPass` if navigation becomes a requirement.
- Supersedes the `externalAnnotator` add-side in `docs/research/14` and epic #3's original two-EP shape; the
  `HighlightInfoFilter` suppress-side stands unchanged.
