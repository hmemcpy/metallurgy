# BETASTy cross-module fixtures

Two source halves that express the founding problem (Scala Space podcast, ~[06:01]):
editing module A must propagate to module B's editor. IntelliJ has no native answer for
Scala 3 — a broken or changed upstream module leaves dependent references stale.

- `module_a/source.scala` — `class Person`. Renamed to `class erson` in-test to model an
  upstream change.
- `module_b/source.scala` — references `Person` across the module boundary.

`BetastyCrossModuleTest` compiles module A with `-Ybest-effort -Ywith-best-effort-tasty`
and puts its best-effort output on module B's presentation-compiler classpath. It proves
that both the public Scalameta completion path and the typed-tree path resolve declarations
from a genuinely broken upstream module. Separate controls prove that the nested
`META-INF/best-effort` root and `-Ywith-best-effort-tasty` are both required, and that a
clean upstream rename becomes visible after retypecheck.
