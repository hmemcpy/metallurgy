# 14 — Codex consultation: the "good code red" architecture

Non-interactive consultation with Codex (`codex exec`, gpt-5.6-sol, read-only sandbox) on the most
elegant architecture for Metallurgy to eliminate Scala 3 "good code red," given the empirical finding
that the compile server (CBH) is correct and the pain lives in the highlighting layer. Codex grounded
its answer in the codebase (it read `ExternalAnnotator` / `ExternalHighlightersService` internals and
`docs/design.md`). Captured here because it is the architectural basis for epic #3 / #34.

## The question (abridged)

CBH (compile server = real compiler) is correct but slow + lossy-bridged; the hand-rolled PSI annotator
is fast but wrong; Metallurgy has a fast in-process `pc`; must co-exist with the bundled plugin; Scala 3
only; opt-in; and (ADR 0008) piggybacks on CBH. Weigh: (1) augment (filter + externalAnnotator), (2)
replace the hand-rolled Scala 3 path with pc-driven highlights, (3) pc as the fast correctness path with
CBH as backend-only. What is the cleanest IntelliJ highlighting seam, and the failure modes (racing CBH's
async overlay, double-highlighting)?

## Key finding — the elegant option (3) is not achievable via public EPs

> "There is no single public EP that can order or deduplicate all three producers. `HighlightInfoFilter`
> governs the normal daemon pass; `ExternalAnnotator` is a later `EXTERNAL_TOOLS` pass; Scala CBH writes a
> separate pass group directly. So 'PC is the sole renderer while CBH only populates artifacts' requires
> one deliberately isolated bridge to Scala-plugin internals — otherwise it is not achievable under the
> stated constraints."

Making `pc` the sole renderer would couple to bundled internals — rejected. So the answer is **not**
"augment vs fast path"; it is "augment today, single-writer arbiter as the intended seam."

## Recommendation

> "A PC fast path using augmenting EPs today, backed by a single-writer diagnostics arbiter as the intended
> seam. CBH produces artifacts and serves as an exact-version fallback; it should not independently compete
> with PC for the final editor state."

1. **Ship a PC `ExternalAnnotator` (adds missing) + a cache-only `HighlightInfoFilter` (suppresses bundled false-red).** PC and CBH under different owners; never reuse `ScalaCompilerPassId`.
2. **PC is authoritative for the exact current snapshot** — not an overlap oracle. A successful *empty* PC result means "clear semantic errors." **Blank-while-pending**: show no semantic red while PC analysis is in flight (retain parser errors) — "brief absence is less misleading than known-stale red."
3. **Explicit snapshot FSM** — `Pending | CurrentSuccess(diagnostics) | Failed | Unavailable`:
   - Edit → invalidate the previous result; debounce ~100–300 ms; cancel/supersede older requests; run on a pooled, cancellable task.
   - Apply → re-check document stamp, classpath epoch, module, session generation, opt-in; publish only an exact match. **Empty success is significant** ("clear semantic errors").
   - CBH/build completion or roots change → bump the artifact/classpath epoch; recreate/refresh the module's PC session; retypecheck open opted-in docs; **CBH's visual result must not supersede a current PC snapshot**.
   - CBH off / Metallurgy off / module removal / session failure → cancel pending, clear Metallurgy highlights, restart the daemon, fall back to bundled.
   - Viewport change → don't re-run `pc` if the whole-file snapshot is current.
4. **Dedup by normalized key** (severity + normalized range + diagnostic code + message family), not overlap — overlap silently drops distinct errors at the same expression.
5. **North star:** pursue a small Scala-plugin diagnostics-arbitration EP next to `ExternalHighlightersService` (upstream contribution) — the single-writer arbiter.

## Suppression rules

- Parser/lexer errors: always keep.
- Metallurgy PC diagnostic: always keep when its snapshot is current.
- Bundled semantic-annotator error: suppress once the PC result is current, **including when PC returned an empty list**.
- CBH diagnostic: suppress only through reliable provenance or at the shared arbiter.
- Duplicate quick fixes: merge by stable action identity; prefer PC's text/range, retain unique CBH actions.

## How this lands

Validates and refines the existing epic #3 (Phase 2: Diagnostics Augmentation), which already specified the
`externalAnnotator` + `problemHighlightFilter` pair. Codex's high-value additions: **PC-authoritative
(empty = clean)**, the **explicit snapshot FSM**, **blank-while-pending**, **normalized dedup**, and the
**arbiter EP** as the long-term seam (vs internal bridging).

## Status of this consultation

Codex's second pass timed out mid-investigation (it was deep-diving `TextEditorHighlightingPassRegistrar`
anchors); both passes converged on the conclusions above. The raw transcript was noisy (`/tmp`, hook/error
traces); this document is the clean synthesis. Re-run if deeper EP-ordering detail is needed.
