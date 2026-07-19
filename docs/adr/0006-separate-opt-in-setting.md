# Separate opt-in setting — do not own `isUseCompilerTypes`

Metallurgy's opt-in is its own per-module project setting (`MetallurgySettings.isEnabled(module)`), persisted in `.idea/metallurgy.xml`, with a notification on first detection of a Scala 3.5+ module. We do *not* reuse the bundled plugin's existing `ScalaProjectSettings.isUseCompilerTypes` flag as that opt-in, even though its UX surface (the "Use compiler types" checkbox in the highlighting widget) would be free.

Reusing `isUseCompilerTypes` as Metallurgy's opt-in would couple product activation to a setting whose semantics the bundled plugin controls. Every bundled plugin update that touches the flag's behaviour or meaning would then become a coordination problem for all Metallurgy features.

A separate setting keeps us in control of our own semantics and makes the user's mental model unambiguous ("this knob means Metallurgy is active"). The cost is a small settings panel and a notification — both are cheap to build.

The bundled plugin's `isUseCompilerTypes` is left entirely alone. Feature 0 reuses consumers that are themselves gated by this option, so users must currently enable it for compiler-type request resolution. Metallurgy intercepts those requests in-process and replaces the bundled producer's result. This compatibility requirement does not make the bundled flag Metallurgy's opt-in and does not apply to independent features such as PC completion.
