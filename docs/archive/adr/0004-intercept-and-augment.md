# Intercept and augment — never replace the bundled plugin

Metallurgy registers additional IntelliJ extension points that run *alongside* the bundled Scala plugin's, not *instead of*. We add results the bundled plugin missed, suppress results `pc` says are wrong, and enrich results that were correct-but-incomplete. Where the bundled plugin is right, we get out of the way. The bundled plugin's compiler-highlighting pass stays on; its compiler-scalac-plugin stdout-scraping transparent-inline pipeline stays available; its parser, lexer, formatter, build import, run/debug pipeline all stay.

The alternative was a "shadow plugin" that effectively replaces the bundled plugin for opted-in Scala 3 modules (disable its compiler pass, swap its PSI for ours, etc.). It is feasible but the cost is an order of magnitude higher: every downstream consumer of PSI (refactorings, debugger, worksheet, structural search, conversion, run configs) has to be re-validated against our shadow-PSI; every bundled plugin update becomes a coordination problem. The scope-rationale section of `docs/design.md` walks through this trade-off in detail.

The intercept-and-augment strategy costs us nothing in correctness (we delegate every type/resolve/macro decision to `pc`) and very little in code (each feature is one extra EP registration). It buys us:

- Independence from bundled-plugin internals.
- Zero migration risk: nothing existing is being replaced.
- Every correction we make is a strict win.

Every correction Metallurgy makes under this strategy is a strict win: the bundled plugin's behaviour is unchanged for users who don't opt in, and strictly better (when `pc` and bundled-plugin results differ) for users who do.
