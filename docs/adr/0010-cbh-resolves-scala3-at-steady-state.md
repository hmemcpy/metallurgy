# CBH resolves Scala 3 at steady state — the highlighting pipeline is transient-only

Two headless CBH triages (`Scala3GapTriageTest`, `LibraryCbhTriageTest`) measured the actual error
`HighlightInfo`s at steady state — CBH on (compile server), Metallurgy off, a `#control` genuine type error
proving the harness detects red. Across **13 Scala 3 constructs**, every valid one was `native-red=false`:

- **Simple stdlib** (#35–#46): given/using, overload/eta, extension, enum widening, structural/refinement,
  quote/splice, **`derives` Mirror type member**, export, match-type/`compiletime.ops`, **Caprese `File^`**.
- **Library-backed** (circe-generic): **`derives Codec.AsObject`**, semiauto `deriveCodec`, derive+decode.

So the bundled hand-rolled annotator + CBH get simple *and* library-backed Scala 3 correct at steady state —
including the two headline doubts (Caprese, Mirror type members) and macro/typeclass derivation.

## Decision

Treat the PC-authoritative highlighting pipeline (epic #3 / ADR 0009: `PcDiagnosticSetCache` → writer →
`PcHighlightRenderer` + `PcHighlightInfoFilter`) as **transient-bridging only**, not a steady-state
false-positive fix. There is no steady-state false-positive target for simple or library Scala 3 under CBH.

- The pipeline's sole UX value is `blank-while-pending` — clearing the hand-rolled annotator's red during
  CBH's seconds of latency, faster than CBH overlays (pc ~300 ms). Real, but marginal once a module is warm
  (incremental CBH is also fast).
- Do **not** invest further in #3 (golden tests, provenance tagging) unless a genuine steady-state gap
  surfaces. The triages are the regression guard that none exists in simple/library code.

## Where Metallurgy's confirmed value actually is

- **The BETASTy flags** (`-Ybest-effort -Ywith-best-effort-tasty`, shipped via `ScalacFlagsService`) — these
  make the compile server *itself* cross-module-robust (downstream compiles against a broken upstream's
  `.betasty`). This is the high-value, low-complexity contribution; CBH does the surfacing.
- **pc completion** — independent of the highlighting pipeline.
- **#42 (cross-module) / #37 (resolve/navigation)** — need their own provenance check before investing; CBH
  (with Metallurgy's flags) may already cover them, as it does for highlighting.

## Consequences

- #3 is re-scoped to "transient-bridging"; its infrastructure (cache/renderer/filter) is retained as reusable
  plumbing, not a user-facing win.
- The triages (`Scala3GapTriageTest`, `LibraryCbhTriageTest`) are the canonical "is this still a gap?"
  guardrails — re-run before claiming any new Scala 3 highlighting gap.
- Re-opens the prioritisation: provenance (does CBH-with-flags already cover it?) must precede building, not
  follow it. The earlier CBH-off / `compiler.make()` triages that suggested gaps were measuring the wrong thing
  (the hand-rolled engine / compilation, not CBH highlighting).

> **Refined by [docs/research/15](../research/15-scala3-type-resolution-gaps.md) and ADR 0011.** The
> "clean at steady state" finding here holds for the **simple stdlib + circe** set the triages measured. A
> YouTrack dive (SCL) shows the bundled plugin still lags on **type-level computation** (`compiletime.ops`,
> match types, named tuples → `Any`), **macro/typeclass derivation** (shapeless, frameless, chisel, refined),
> **macro annotations**, and **new 3.5+ features**. Those are real, voted, open gaps — the refocused
> Metallurgy opportunity is type-resolution/completion for that set, not red suppression.
