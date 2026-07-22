# Native Scala 3 IDE support has caught up — Metallurgy re-scoped

A visual inspection of the `dogfood/` project (25 valid, compiling Scala 3.7.4 samples — the triage cases,
circe, and the golden fixtures) in a **clean IntelliJ with the bundled Scala plugin and CBH, no Metallurgy**
found that **24 of 25 are fully supported natively**: correct types on hover/inspect, no false red. The
single exception is **jing's `inlineYaml`**, which resolves to `Any` instead of the refined structural type
the macro produces.

This corroborates the headless provenance (`Scala3GapTriageTest`, `LibraryCbhTriageTest`, ADR 0010) but
**exposes a blind spot in it**: those triages measured *error* `HighlightInfo`s (red) only. jing is
`native-red=false` there — its failure is **type resolution**, not red. "No red" ≠ "correct type."

## Decision

Re-scope Metallurgy from a broad highlighting pipeline to a **narrow type-resolution + completion plugin
for macro-driven Scala 3**, plus the already-shipped BETASTy flags. Concretely:

1. **The #3 highlighting pipeline (cache/writer/renderer/filter, ADR 0009) is demoted to transient-only /
   reusable plumbing.** There is no steady-state false-red to suppress for ordinary or library Scala 3 —
   native support handles it. Do not invest further unless a real steady-state red surfaces.
2. **The live value is type resolution where the bundled plugin falls back to `Any`** — macro-driven
   structural typing (jing `inlineYaml`, and likely tapir/magnolia/iron-style schema/refined macros). `pc`
   runs real `dotc`, expands the macro, and reduces the refined type, so it fixes exactly this. This is the
   "Enrich" interception pattern (CONTEXT), not "Suppress."
3. **pc completion** stays (independent, shipped).
4. **BETASTy cross-module symbol highlighting is retained as scope.** The `Person → erson` rename case
   (upstream renames a symbol; downstream's stale reference must go red / the rename must propagate) is a
   cross-module *symbol-highlighting* concern, distinct from in-file type resolution. `BetastyCrossModuleTest`
   proves `pc` reflects upstream changes; the open provenance question is whether **native CBH** (with or
   without Metallurgy's `-Ybest-effort -Ywith-best-effort-tasty` flags) already handles it. Resolve that
   before building anything.

## Methodology consequence

The triages must measure **type resolution (hover/compiler-type), not just red**, or they'll miss the
jing category entirely. `Scala3GapTriageTest` is to be extended with a `type-resolves` check (compiler type
at a representative offset ≠ `Any`). Sizing the macro-driven type-resolution gap requires broadening the
sample set beyond stdlib/circe (tapir, iron, magnolia, …) — the dogfood is the place to add those for
eyeball sizing.

## Consequences

- Metallurgy's proposition is no longer "fix Scala 3 red code" — it is "give correct types/completion for
  macro-heavy Scala 3 the bundled plugin renders as `Any`," gated to those cases.
- The `dogfood/` project + the (type-aware) triages are the canonical "is this still a gap?" guardrails.
- Open: (a) size the macro type-resolution gap (broader samples); (b) native-CBH BETASTy cross-module
  provenance. Both precede any new build.
- Supersedes the framing in `docs/research/14` and the original #3 scope as a *highlighting* feature; the
  cache/renderer infrastructure is retained as plumbing for the type-resolution work.
