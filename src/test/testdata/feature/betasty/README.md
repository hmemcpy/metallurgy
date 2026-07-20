# BETASTy cross-module fixtures

Two source halves that express the founding problem (Scala Space podcast, ~[06:01]):
editing module A must propagate to module B's editor. IntelliJ has no native answer for
Scala 3 — a broken or changed upstream module leaves dependent references stale.

- `module_a/source.scala` — `class Person`. Renamed to `class erson` in-test to model an
  upstream change.
- `module_b/source.scala` — references `Person` across the module boundary.

`BetastyCrossModuleTest` compiles module A with `-Ybest-effort -Ywith-best-effort-tasty`,
puts its output on module B's presentation-compiler classpath, then proves that B's cached
session does **not** see A's rename until the session is rebuilt. That staleness is the
cross-module invalidation gap; closing it (detect upstream recompile → discard dependent
sessions) is the next step. A genuinely *broken* upstream (exercising `.betasty` reading)
follows once propagation is wired.
