# Dotc type-boundary faithfulness (B-failure root cause and fix plan)

Records the investigation behind the `CompilerType` slot poisoning that makes the bundled annotator emit false
errors on dotc-valid code. Produced by consultation against scala/scala3 and the bundled intellij-scala source.

## Root cause

Metallurgy writes dotc's *display text* into the `CompilerType` slot as though it were a lossless serialized
`ScType`. It is not. For singletons, anonymous polymorphic binders, and method types the display text is lossy, so
`ScalaPsiElementFactory.createTypeFromText` degrades the reparse to `Any` (via `getOrAny`), and `ScExpressionAnnotator`
then reports a false conformance error against the expected type.

Path:

1. `CompilerTypeRequestResolver` schedules whole-file population.
2. `StructuralScala3PcBridge.normalizedType` converts a bare `MethodType` to a function type, but leaves
   `PolyType(MethodType)` and type lambdas as-is, so e.g. `List.empty` renders as `[A]: List[A]`.
3. `Scala3CompilerBackend` writes the rendered string into the `CompilerType` slot.
4. The bundled `ScExpression.getTypeWithoutImplicits` treats any populated slot as authoritative.
5. `createTypeFromText` converts failed semantic resolution to `Any` via `getOrAny`.
6. `ScExpressionAnnotator` compares the poisoned actual with the expected type and emits the false error.

Decisive boundary values:

| Expression | Slot text | Parsed `ScType` |
|---|---|---|
| `None` | `None` | `Any` |
| `List.empty` | `[A]: List[A]` | `[A] => () => Any` |
| `[_] => x => Option(x)` | `[_] => (x: _) => Option[_]` | `[_] => Any => Option[_]` |

A safety-invariant bug in `Scala3CompilerBackend.parsedState` let a syntactically-valid-but-semantically-unresolved
reparse become `Current`: the filter short-circuited on `!hasErrorElements`, so `isFallbackType` was never checked
for clean-but-degraded parses such as `None -> Any`.

## Fix plan

**Part 1 (done, commit `abe2645`):** check `isFallbackType` always in `parsedState` — a reparse whose canonical text
is a fallback type (`Any`/`Unit`/`Nothing`) differing from the rendered text is rejected to `Unavailable`, so no slot
is published and the bundled resolver stays responsible. Not error suppression. Fixes the top-level `None -> Any`
cases (FunctionLiteral, an Extensions case, a NamedTuples case) with no regressions. Insufficient for nested
degradation (`[_] => Any => Option[_]`).

**Parts 2-4 (deliberate refactor of the compiler-to-PSI type boundary):**

- `StructuralScala3PcBridge` rendering/DTO extraction: preserve singleton identity in a source-compatible form
  (e.g. a stable `.type` representation rather than `None`); assign stable synthetic names to anonymous poly binders
  so every `TypeParamRef` reconstructs to the same parameter; preserve `PolyType(MethodType(...))` structurally
  rather than forcing method types through the string-only channel.
- Where no faithful source string exists, extend the neutral DTO with structured type shapes and construct
  `ScTypePolymorphicType` / `ScMethodType` inside `ScalaPluginSemanticBridge`, or intercept the expression-type root.
- Regression tests at three seams: extraction (singleton, anonymous poly binder, polymorphic method DTOs);
  parse/publication (lossy renderings never become `Current` and never write slots); full compat (the B-cases pass
  with compiler types enabled while existing exact-type assertions stay unchanged).

## Version note

The compatibility harness configures Scala **3.5.2** (`Scala3CompatTestCase`), not 3.7.4. Standalone compiler probes
used 3.7.4, so a few A/B classifications need 3.5.2 re-validation before a fix lands. The root cause above is
version-independent.

## Correction: some "B" failures are bundled-annotator errors, not Metallurgy bugs

The A/B triage validated dotc (does the snippet compile) but not whether **Metallurgy causes** the annotator error.
The correct classifier is a toggle diagnostic: publish the snapshot under Metallurgy, capture ERROR highlights,
then set `useCompilerTypes(false)` and re-highlight.

- `testPrivateGenericExtensionWithIntersectionType` (`List.empty[String & Int].test[Double]`): the
  "method apply does not take type arguments" error is **identical with Metallurgy on and off** -> it is a
  **bundled Scala-plugin annotator** error, not a Metallurgy bug. Goal 4 does not apply (Metallurgy is not
  reporting it).
- `testApplyOnValueOfCompanionlessClass` (`z(1)`/`zz(2)` apply): identical on/off -> bundled.
- `testPolymorphicFunctionTypeWithExplicitAndUnderscoreParams` (`[_] => x => Option(x)`): the `actual: [_$0] => Any => Option[?]`
  is identical on/off -> the bundled annotator itself widens the anonymous poly binder `_` to `Any`. Bundled.

3/3 tested B-cases (intersection, apply-resolution, poly-function) are bundled-annotator errors, not Metallurgy.
The remaining failures are the bundled plugin's own annotator limitations; Metallurgy neither causes nor owns them.

Implication: the codex-diagnosed `None -> Any` slot poisoning (fixed by `isFallbackType`/`introducesAny`) was
genuinely Metallurgy-induced, but the remaining intersection/apply failures are largely bundled-annotator behaviour
(Metallurgy-independent). They are not goal-4 Metallurgy bugs and are out of scope for the type-boundary refactor.
Each remaining failure should be re-classified with the toggle diagnostic before any fix attempt.

## Correction (codex-assessed, compiler-probe-verified): the on/off toggle is invalid; the compiler is the oracle

The toggle used above — publish with `useCompilerTypes` ON, capture, flip `useCompilerTypes(false)`, re-highlight —
is NOT a valid control. Publication has already populated copyable `CompilerType` user data, installed side-table
states, cached parsed `ScType` under local trackers, and painted PC diagnostics on the separate highlighting layer.
Flipping the setting does not retire the backend state or erase the PC markup layer, so an already-poisoned slot
survives and the on/off error sets come out identical for every case — including cases the compiler rejects. That
identity proves nothing about ownership.

The valid control is the exact-version compiler (`scala-cli compile --scala 3.5.2`, the harness version), confirmed
with `-Xprint:typer`/REPL per goal 3. Re-probing the 9 failing Extensions/UniversalApply cases under 3.5.2 yields the
true classification:

- **Oracle conflicts (dotc 3.5.2 rejects; upstream expects no error) — 5.** `testExtensionFromImplicitScope`,
  `testExtensionFromGivenInImplicitScope`, `testAmbiguousExtensionWithExpectedTypeAndTypeArgs`,
  `testAmbiguousExtensionWithExpectedTypeAndArgs` are already in `DotcOracleConflicts`. `Found: (123 : Int)` is dotc's
  own diagnostic wording for an *ambiguous-extension* rejection, surfaced faithfully by the PC layer — not slot
  poisoning. `testExtensionResolvedViaTypeclassGiven` is a **version-skew** conflict: `given T { ... }` (no `with`)
  is valid in 3.7.4 but rejected by the 3.5.2 parser; now added to the manifest.
- **Diagnostic-wording difference — 1.** `testWithCompanionObjectTypeMismatch`: dotc and the bundled annotator agree
  `A(true)` is an error; they differ only in wording (`Found: (true : Boolean)` vs `Type mismatch, expected: Int,
  actual: Boolean`). Not a slot bug; a wording-pairing-map item per the epic's diagnostics design.
- **Genuine Metallurgy bugs (dotc accepts, Metallurgy errors) — 3.** `testPrivateGenericExtensionWithIntersectionType`
  (`method apply does not take type arguments`), `testApplyOnValueOfCompanionlessClass` (`(x: Int) does not take
  parameters`), `testReferencingApply` (`Missing arguments for method apply(Int)`). These are the actual M4 scope.

The prior claim that intersection/apply-resolution/poly-function are "bundled-annotator, Metallurgy-independent" is
withdrawn. The gate-off baseline (Metallurgy fully disabled) measures the bundled plugin's *leniency* (it tolerates
what dotc rejects), not Metallurgy's correctness, so it over-counts Metallurgy-induced failures; the compiler probe
is the only oracle. `b972664`'s on/off identity was an artefact of slot/markup persistence.

Consequence for the fix plan: M4's heavy structured-DTO refactor is not yet warranted. The singleton-display leak
(`Found: (ref : underlying)`) is dotc's own diagnostic, not a published-slot value, so widening singletons in the
bridge would not change these cases. The three genuine bugs are traced at three boundaries (dotc type/symbol,
neutral snapshot entry, parsed `ScType`/source-or-light PSI) before any fix; `PolyType(MethodType)` or a
compiler-only callable symbol is the only shape that would justify a structured type channel, and only once a
faithful-source-string gap is proven.

## Intersection root cause (codex-assessed): module/companion singleton-identity loss

`testPrivateGenericExtensionWithIntersectionType` (`List.empty[String & Int].test[Double]`) compiles under dotc
3.5.2; gate-off is clean; Metallurgy ON reports `method apply does not take type arguments` at the `[String & Int]`
type argument. A backend entry dump shows the TypeApply slots are CORRECT (`List[String & Int]`, `List[String &
Double]`), so this is not slot poisoning of the apply. The `List` companion qualifier (the `List` reference) is
rendered as `scala.collection.immutable.List` (the trait type) rather than the module singleton `List.type`, and the
bundled annotator (provenance: `annotator=true`, not the PC layer) then cannot resolve `.empty` on a trait and falls
back to `apply`. `StructuralScala3PcBridge.normalizedType` does `widenTermRefExpr -> dealias -> normalized ->
simplified`; widening a module `TermRef` strips the `.type` identity. dotc 3.5.2's `Type.show` is display text
(`PlainPrinter.toTextSingleton` -> `(ref : underlying)`), not source syntax, so the faithful `<path>.type` must be
rendered from the preserved module `TermRef` symbol's `showFullName` plus `.type` (the Scala plugin's `SimpleType`
parses `Path . type`; `Foo.type` round-trips through `createTypeFromText`). The same mechanism is predicted to cover
`testApplyOnValueOfCompanionlessClass` (`val z = Foo`; dotc reports `z: Foo.type`). This remains a rendered-boundary
correction, NOT the structured-DTO refactor.

Resolution: the failed first attempt matched `getClass.getSimpleName == "TermRef"`, but dotc's runtime
instances are `Cached*` variants (`dotty.tools.dotc.core.Types$CachedTermRef`), so the predicate matched nothing
and the change was a no-op (the apparent `col` regression was unrelated test flakiness). Rendering module `TermRef`s
as `<fullName>.type` via `eventualModuleTermRef` (peel stable `TermRef` layers with a robust
`getName.endsWith("TermRef")` test to the eventual module symbol) now lands in `renderCandidate`: it fixes
`testPrivateGenericExtensionWithIntersectionType` and the `val z = Foo` half of
`testApplyOnValueOfCompanionlessClass` (`z(1)`), and improves `Scala3SelectableCompatTest` (4 -> 3), with no
regressions across the typeInference, engine, and presentation suites. The `def zz = Foo` half (`zz(2)`, `does not
take parameters`) remains: `zz` is a zero-arg method whose reference type is a `MethodType`, not a module `TermRef`, so
it is a separate method-type rendering item, not the module-singleton fix.

## method-type slot publication is load-bearing: `isMethodType` is intentionally not fixed

`StructuralScala3PcBridge.isMethodType` matches `getClass.getSimpleName == "MethodType"`, but dotc materializes
`CachedMethodType`/`CachedExprType`, so it returns false and `normalizedType` never calls `methodToFunctionType`. An
attempt to repair it (suffix match on `getName`) was reverted: converting method references to function types
*poisons* the slot — `zz(2)` then reads `Int => Foo` and reports `does not take parameters`, and `Test.apply` resolves
to `apply(T1)`, and a Java type-mismatch test regressed (`col` rendered as the singleton `(Test.col : ...)`). With the
predicate left broken, the raw `(param: Type): Result` rendering fails `createTypeFromText`, so `parsedState` rejects
the entry and NO slot is published — the bundled self-resolves the method reference correctly. The unconverted
method type is thus an accidental-but-correct outcome: the right M4 fix is to publish method-type references
deliberately (context-aware: suppress for application/eta-expansion, surface for `assertExprType` such as
`testTypeOfApply`), not to blanket-convert to function types. `zz(2)` and `testReferencingApply` belong here, not to
the module-singleton fix.
