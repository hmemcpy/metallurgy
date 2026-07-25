# `pc` package — Scala 3 presentation-compiler bridge

`StructuralScala3PcBridge` reflects over the exact-version dotc driver (isolated classloader) and exports
neutral span/type/symbol data. It renders dotc types into the strings the Scala plugin parses into the
`CompilerType` slot. The normative architecture is `docs/scala3-compiler-backend.md`.

This file holds only standing directives for working in the bridge. Investigation findings, root-cause notes, and
per-failure status live on the epic's GitHub issue (read its comments for continuity), not here.

## Reconcile dotc against the bundled PSI before trusting a rendering

A rendered type string must satisfy three parties, checked in order:

1. **dotc ground truth** — `scala-cli compile <file> --scala 3.5.2 --scala-opt -Xprint:typer` (the harness pins 3.5.2).
2. **Bundled-PSI ground truth** — with Metallurgy off (`MetallurgySettings.setEnabled(module, false)` plus
   `ScalaProjectSettings` `setCompilerHighlightingScala3(false)`/`setUseCompilerTypes(false)`), read
   `ScExpression.type().presentableText`.
3. **Round-trip** — `ScalaPsiElementFactory.createTypeFromText` must parse the string to the intended `ScType`
   (it degrades a failed sub-term to `Any` via `getOrAny`, so a returned `Some` is not alone proof of success).

The `useCompilerTypes(false)`-after-publish toggle is **not** a valid control: publication already populates the
copyable `CompilerType` user data, the side-table states, and the PC diagnostics layer, and flipping the setting does
not retire them, so on/off error sets come out identical even for code dotc rejects. Only the full gate-off above is.

## Rendering rules

- **Module/companion `TermRef`** widens to its underlying class/trait, losing object identity. Render before widening:
  - **Static** module (top-level or static owner): `symbol.showFullName` + `".type"` (resolvable fully-qualified).
  - **Path-dependent** module (`o.Inner`, nested in a class): `showFullName` uses the class owner (`Outer.Inner`,
    unresolvable), so render the raw `TermRef` via `Type.show`, which preserves the value prefix and appends `".type"`.
  - Discriminator: `symbol.isStatic` (false ⇒ path-dependent).
- **`Type.show` is not always valid source.** It emits `<empty>.type` for top-level modules (dotc's internal
  empty-package name) and `(ref : underlying)` singleton-parens for many types. Use it only where it yields clean
  source; prefer `showFullName` for statics.
- **Polymorphic function value** = dotc `RefinedType(scala.PolyFunction, apply, PolyType(...))`. Anonymous binders
  widen the dependent `TypeParamRef`s. Rename via `PolyType.newLikeThis(names, infos, resType)` (substitutes every
  bound `TypeParamRef`), rebuild via `derivedRefinedType`, let `RefinedPrinter` emit the faithful source form.
  Keep existing named binders; only rename wildcards, collision-free.
- **Polymorphic-function sub-expressions** (the body lambdas) carry free `TypeParamRef`s that render as `?` and are
  meaningless standalone; drop candidates whose range lies strictly inside a poly-function value's range, so only the
  enclosing (renamed) poly-function is published.

## Reflection gotchas

- dotc materializes types as `Cached*` runtime classes (`CachedTermRef`, `CachedMethodType`, `CachedExprType`,
  `CachedRefinedType`, ...). `getClass.getSimpleName == "TermRef"` is false; match by `getClass.getName.endsWith(...)`.
- Accessor shapes vary: `Context.definitions`, `Definitions.PolyFunctionClass`, `PolyType.paramNames`/`paramInfos`/
  `resType` are no-arg; `derivesFrom`/`newLikeThis`/`derivedRefinedType` take the context last; `Names.typeName` is a
  single `String` argument with no context. When a reflective lookup fails, enumerate the methods and read the actual
  signature rather than assuming.
- Build child-classloader Scala collections through `scala.jdk.javaapi.CollectionConverters.asScala(...).toList`.
