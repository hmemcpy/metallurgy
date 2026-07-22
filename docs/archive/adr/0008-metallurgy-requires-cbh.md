# Metallurgy requires compiler-based highlighting (CBH)

Metallurgy **piggybacks on the bundled plugin's compiler-based highlighting (CBH)** — the compile-server pass that writes `.class`/`.tasty`/`.betasty` to the module output. The presentation compiler reads those artifacts from the classpath; without CBH there is no compilation, so there is nothing for `pc` to read. ("Piggyback" is hand-wavy, but it is the reason CBH is mandatory: the compile server is what populates the classpath `pc` typechecks against. Jędrzej Rochala, ScalaWAW #32 [90:07]–[91:41]: *"with this single feature we have handled the incremental compilation which is necessary for presentation compiler to work."*)

## Decision

1. **Hard no-op when CBH is off.** Metallurgy's activation predicate gates on `BundledPluginBridge.usesCompilerTypes(project)` (= `isCompilerHighlightingScala3 && isUseCompilerTypes`). When CBH is off, **no** pc sessions are created, no completion contributor runs, no `HighlightInfoFilter` suppression — Metallurgy is invisible. This prevents the silent-degradation failure mode (sessions that resolve nothing). Today only the redundant `CompilerTypeRequestResolver`/`CompilerTypeReportInterceptor` check this; the live feature paths (`PcSessionManager.isManaged`, `Scala3PcCompletionContributor`, `PcHighlightInfoFilter`) do not, and must be brought under the gate.
2. **The opt-in setting is presented nested under the CBH checkbox** in the bundled Scala project-settings panel, branded with the Metallurgy logo, and **disabled unless CBH is on**. This refines ADR 0006 ("a small settings panel") to a co-located control, making the dependency visually obvious: the user ticks "compiler-based highlighting" and finds Metallurgy sitting directly beneath it. Toggling Metallurgy on may auto-enable CBH (or prompt).

## Implementation

The bundled settings panel (`ScalaProjectSettingsConfigurable` + its form) exposes **no public EP** for injecting controls, so the inline-under-checkbox placement is **reflection**: locate the CBH `JCheckBox` in the bundled form and insert our branded `JCheckBox` as its sibling in the parent panel. This is ADR 0007 territory — strict IntelliJ version pinning, with a reflection fallback.

**Fallback** if reflection proves too brittle across versions: register a child `Configurable` whose `parentId` is the Scala settings configurable — a clean platform EP that nests as a sub-page under the Scala settings node (satisfies "nested", though not inline-under-the-checkbox).

Branding (logo) is deferred until an asset exists; a placeholder icon is used until then.

## Consequences

- The CBH dependency is enforced at two layers: the activation gate (no-op) and the UI (disabled checkbox), so users cannot reach a silently-broken state.
- ADR 0006's storage decision stands unchanged — our own `MetallurgySettings.isEnabled(module)` in `.idea/metallurgy.xml`; we never mutate `isUseCompilerTypes`.
- Coupling cost: a bundled-plugin change to the settings form layout can break the reflection injection — bounded by ADR 0007's version pin and the child-configurable fallback.
- Triage corollary: since CBH is always assumed on, gap discovery must run under CBH on (the compile-server path), not CBH off (the hand-rolled engine). CBH-off "red" is the hand-rolled engine's false positives, not a user-facing gap.
