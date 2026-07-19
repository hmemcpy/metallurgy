# Separate opt-in setting — do not reuse `isUseCompilerTypes`

Metallurgy's opt-in is its own per-module project setting (`MetallurgySettings.isEnabled(module)`), persisted in `.idea/metallurgy.xml`, with a notification on first detection of a Scala 3.5+ module. We do *not* reuse the bundled plugin's existing `ScalaProjectSettings.isUseCompilerTypes` flag, even though that flag's UX surface (the "Use compiler types" checkbox in the highlighting widget) would be free.

Reusing `isUseCompilerTypes` would couple us to a setting whose semantics the bundled plugin controls. Every bundled plugin update that touches the flag's behaviour or meaning becomes a coordination problem for us. Worse, the flag's current meaning — "enable the bundled plugin's scalac-plugin stdout-scraping transparent-inline pipeline" — is *exactly* the pipeline Metallurgy replaces. Encouraging users to enable it (so we can intercept) would be self-defeating.

A separate setting keeps us in control of our own semantics, makes the user's mental model unambiguous ("this knob means Metallurgy is active"), and lets users leave the bundled hack off (saving its cost) while still getting the value. The cost is a small settings panel and a notification — both are cheap to build.

The bundled plugin's `isUseCompilerTypes` is left entirely alone. Users who want belt-and-braces can enable both; we win the race because we answer in-process. Users who want only us turn ours on and theirs off.
