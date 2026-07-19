# 05 — Macros (Scala 2 Reflect + Scala 3 Inline/Quotes)

Domain report for the Metals-`pc` rewrite. Covers the existing macro
facilities, their Scala 3 status, and the proposed `pc`-based replacements.

---

## 1. Scala 2 macro / reflect expansion (current)

The plugin ships two completely independent mechanisms for "what does this
macro produce?": a hand-written in-process emulator
(`ScalaMacroEvaluator`) and an out-of-process reflect-output scraper
(`ReflectExpansionsCollector`). Neither covers Scala 3.

### 1.1 In-process emulation — `ScalaMacroEvaluator`

The class lives at
`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/macros/evaluator/ScalaMacroEvaluator.scala:31`
and is exposed as a project service (`getInstance`, line 77). It maintains two
rule tables:

- `typingRules`  → `ScalaMacroTypeable` providers that return a synthetic
  `ScType` for whitebox macros (`ScalaMacroTraits.scala:34`).
- `expansionRules` → `ScalaMacroExpandable` providers that return a synthetic
  `ScExpression` (`ScalaMacroTraits.scala:38`).

Both tables are populated from a fixed set of defaults
(`ScalaMacroEvaluator.scala:84-94`) and from the dynamic EP
`LibraryExtensionsManager.getExtensions[T]` (line 43). `MacroDef.unapply`
(`macros/MacroDef.scala:6-17`) is the matcher: it accepts an
`ScMacroDefinition` or any `ScFunction` carrying the
`scala.reflect.macros.internal.macroImpl` annotation.

The hand-written emulators for shapeless live under
`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/macros/evaluator/impl/`
and reproduce the *textual* output of the real macro:

- `ShapelessMaterializeGeneric.scala:15-27` — emits
  `Generic.Aux[Foo, Int :: String :: HNil]` from case-class fields.
- `ShapelessProductArgs.scala:30-61` — rewrites
  `lhs.method(23, "foo")` into `lhs.methodProduct(23 :: "foo" :: HNil)`.
- `ShapelessWitnessSelectDynamic.scala:11-44`,
  `ShapelessMkSelector.scala:18`, `ShapelessForProduct.scala:32`,
  `ShapelessDefaultSymbolicLabelling.scala:16`.

These providers are invoked from five PSI hot spots:
`InferUtil.scala:286`, `ImplicitCollector.scala:927`,
`ScStableCodeReferenceImpl.scala:542`, `MethodInvocationImpl.scala:540-545`,
`ScSimpleTypeElementImpl.scala:237`. The evaluator is therefore on the
critical path of *every* resolve/type request that may touch a macro.

### 1.2 Reflect-output scraping — `ReflectExpansionsCollector`

The class at
`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/macros/expansion/ReflectExpansionsCollector.scala:18`
is a project service that stores a `HashMap[Place, MacroExpansion]` keyed by
`(sourceFile, offset)` and serialised to
`System.getProperty("java.io.tmpdir")/expansion-${project.getName}` (line 71).
The collector is driven externally:

- `compilationStarted()` clears the map (line 42).
- `processCompilerMessage(text)` is fed by sbt output via
  `sbt/sbt-impl/src/org/jetbrains/sbt/shell/SbtProjectTaskRunnerImpl.scala:365-376`
  (the `onOutputLine` hook on the build's event processor).
- `compilationFinished()` flushes, serialises, and restarts the daemon
  analyser (line 47).

The scraping itself is a regex state machine in
`ScalaReflectMacroExpansionParser.scala:9-63`. The triggering pattern is the
line `performing macro expansion <foo.Bar> at source-<file>,line-<n>,offset=<m>`
which is emitted by `scala.reflect.macros.whitebox.Context`/`blackbox.Context`
when `-Ymacro-debug-lite` (or equivalent) is on. The body and AST tree lines
are accumulated into `MacroExpansion.body/tree`.

The line marker `ReflectExpansionLineMarkerProvider.scala:14-74` consumes the
map: it locates the expansion for the element under the caret, then
**physically replaces the source PSI** with `ScalaPsiElementFactory.createExpressionFromText`
after running `ensugarExpansion` (line 52) — a `java.util.regex` rewrite that
strips compiler attributes, rewrites `<init>` → `this`, etc. This is a
scala.meta-style "expand in place" action.

Both line marker providers are gated by
`ScalaProjectSettings.getScalaMetaMode != ScalaMetaMode.Disabled`
(`MacroExpansionLineMarkerProvider.scala:32`).

### 1.3 Synthetic-member injection — the EP

The extension point is declared in
`scala/scala-impl/resources/META-INF/scala-plugin-common.xml:11` and 12 impls
are registered at lines 33-44:

| Impl | File | What it synthesises |
|---|---|---|
| `CaseClassAndCompanionMembersInjector` | internal | case-class <-> companion members |
| `AbstractTypeContextBoundsInjector` | internal | context-bound abstract types |
| `EnumMembersInjector` | `EnumMembersInjector.scala:11` | Scala 3 `values/valueOf/fromOrdinal` |
| `DerivesInjector` | `DerivesInjector.scala:9` | Scala 3 `derives` companion given |
| `QuasiQuotesInjector` | internal | quasiquotes support |
| `MonocleInjector` | `MonocleInjector.scala:9-62` | `def lensX: Lens[Foo, T] = ???` per ctor param |
| `ScalazDerivingInjector` | `ScalazDerivingInjector.scala:18-69` | `implicit def TC: TypeClass[Foo] = ???` |
| `CirceCodecInjector` | `CirceCodecInjector.scala:18-85` | `implicit def enc_$id: Encoder[Foo] = ???` etc. |
| `NewTypeInjector` | `NewTypeInjector.scala:7-66` | newtype `deriving/derivingK/Coercible` |
| `SimulacrumInjector` | `SimulacrumInjector.scala:18-280` | `Ops/ToOps/AllOps/ops/nonInheritedOps` |
| `DerevoInjector` | `DerevoInjector.scala:16-203` | `implicit def idea$derevo_injected_$i: TC[Foo] = ???` |
| `ScioInjector` | `ScioInjector.scala:12-32` | adds `HasAvroAnnotation` super |

The base class (`SyntheticMembersInjector.scala:19-63`) is intentionally
stringly-typed — each injector returns `Seq[String]` of *source text* that is
parsed by `ScalaPsiElementFactory` and attached as synthetic members
(`SyntheticMembersInjector.inject` at line 82). There is no semantic
contact with the real compiler.

The Scala 3 `Mirror` synthesis (compiler-generated instance for
`scala.deriving.Mirror`) is implemented separately, in
`SyntheticImplicitInstances.scala:38-77` — the plugin fabricates the
refinement `Mirror.Product { type MirroredType = ...; type MirroredElemTypes = ...; ... }`
purely from PSI.

---

## 2. Scala 3 macro support status

Quote/splice is parsed, but no semantic expansion is performed.

- **Lexer/parser**: tokens `SpliceStart`/`QuoteStart`
  (`lexer/ScalaTokenType.scala:51-52`); PSI `ScQuotedBlock`, `ScSplicedBlock`,
  `ScSplicedPatternExpr` (`parser/ASTNodeToPsiElement.scala:58-91`,
  `parser/parsing/expressions/Quoted.scala`, `Spliced.scala`,
  `SplicedPatternExpr.scala`). Quoted patterns and splice-in-type-position
  are recognised (see `parser/parsing/types/SimpleType.scala:111`).
- **No semantic layer**: there is no `InlineExpansion` pass, no
  `Quotes`/`Expr`/`Type[T]` interpreter, and no `inline def` rewriter.
  `ScMacroDefinitionImpl.scala:27-30` still falls back to `Any` for macro
  return types ("TODO look up type from the macro impl").
- **Workaround for transparent inline**: a hand-registered stub at
  `ScSyntheticClass.scala:619-626` injects `package scala.quoted; val quotes: Quotes = ???`
  so that `import scala.quoted.quotes.*` resolves. The comment at line 616
  admits this is a temporary hack because proper transparent-inline resolve
  causes recursion problems.
- **Compiler-types bridge**: for transparent-inline calls, the plugin defers
  to the compile server via `CompilerType` user-data
  (`lang/psi/impl/CompilerType.scala:7-32`). When resolution hits a
  transparent inline call and `isCompilerHighlightingScala3 &&
  isUseCompilerTypes` is on, it uses the type string the compiler produced
  (`ScExpression.scala:317-329`, `ScStableCodeReferenceImpl.scala:478-490`,
  `completion/package.scala:263-283`). The request is dispatched by
  `CompilerTypeRequestListener.scala:11-24` →
  `CompilerHighlightingService.executeDocumentCompilationRequest`. This is
  effectively "ask the compiler, store the type string, present it as
  resolved".
- **`derives`**: handled correctly by `DerivesInjector.scala:15-31`
  + `DerivesUtil.scala:60-90`, which fabricates a
  `given derived$TC: TC[Foo] = ???` in the companion.
- **`import scala.language.experimental.*`**: only Scala 2's `macros` flag
  is modelled (`codeInspection/feature/LanguageFeatureInspection.scala:66`).
  Scala 3 feature imports are not.
- **Quoted patterns**: parsed but never type-checked against a Quote pattern
  matcher; `$ { x }` bindings don't get types.

Net: Scala 3 macro expansion as such is **unsupported**. What exists is a
narrow workaround for transparent-inline return types via the compiler-types
bridge. Ordinary `inline def` bodies, `inline if`/`inline match`, quoted
macros (`inline def foo(using Quotes): Expr[T] = '{...}`), and `derives`
backed by real `derived` methods are all resolved with no expansion.

---

## 3. TASTy as a source of macro expansions

When dotc compiles a Scala 3 source, the *post-expansion* tree is what gets
written to `.tasty`. Inlined call sites are wrapped in `INLINED` nodes that
record the original call, the expansion body, and (since TASTy 6) the source
file of the inlined definition. Two integrations are relevant:

### 3.1 Current reader explicitly drops inlined metadata

`scala/tasty-reader/src/TreeReader.scala:109` reads:

```scala
case INLINED => children().head
```

It unwraps the `INLINED` node and returns the body, discarding the call
metadata, the source-file pointer, and the call-site span. The decompiler
test at `scala/tasty-reader/testdata/types/Inlined.scala` shows that the
expected decompiled text already contains the *expanded* form (e.g.
`foo1.T` becomes `Foo.T`); the wrapping `InlineCall`/`SourceFile` are lost.
This is acceptable for the `.tasty` decompiler view but useless for "show me
what was inlined at this call site" or "navigate to macro definition".

### 3.2 `pc.Interactive` is the right seam

The Metals presentation compiler (`pc`) drives a real `dotc.Interactive`
instance and exposes:

- `Interactive.context` — the typer state with all `Inlined` nodes intact
  (positions preserved via `dotc.tasty.PositionMapper`).
- `Symbol.source` — for every inline body, the path to the `.scala` file
  from which the expansion originated (the same path TASTy records in
  `INLINED.sourceFile`).
- `Interactive.completion` / `Interactive.typeCheck` — return expanded
  `tpd.Tree`, which can be walked to recover splice/quote substitutions.

Compared to the current scheme of parsing compiler stdout into regex-captured
strings (§1.2), `pc` offers:

1. **Positions** for every node in the expansion, including nested
   expansions.
2. **Inlined call origin** — the `Symbol` of the `inline def` (or macro),
   which lets the IDE provide navigation, "find expansion", and inlay hints
   at the call site.
3. **Correct types** for the expansion body, including polymorphic
   instantiation and implicit resolution performed *inside* the macro.

A `.tasty`-only reader cannot match this for source files being edited
(no `.tasty` exists until compile). So the seam is `pc`, and the
`tasty-reader` should grow an `InlinedNode` case (preserving the call +
source + body) only to enrich the decompiled-file view, not for live
macro expansion.

---

## 4. Seam recommendations

### 4.1 New component: `PcExpansionProvider`

A read-only facade over the per-module `pc.Interactive` driver:

```
trait PcExpansionProvider:
  def expandedTreeAt(file: VirtualFile, offset: Int, docVersion: Long):
      Option[PcExpansion]
  def syntheticMembersOf(td: ScTypeDefinition): Seq[PcSyntheticMember]
  def completionAt(file, offset): Option[PcCompletionList]
```

- `PcExpansion` = call symbol + expansion body (as a `pc.Tree` /
  position-bearing wrapper) + source-of-inline.
- `PcSyntheticMember` = (name, signature text, navigation target).
- The provider is registered only for Scala 3 modules
  (`ScalaFeatures.isScala3`).

It is fed by `pc`'s existing `Interactive`/`Completion`/`Hover` endpoints —
no new compiler entry points are required.

### 4.2 Consumers it replaces (Scala 3 only)

| Old component | Scala 3 replacement |
|---|---|
| `ScalaMacroEvaluator.checkMacro` (whitebox ScType) | `PcExpansionProvider.expandedTreeAt` |
| `ScalaMacroEvaluator.expandMacro` | `PcExpansionProvider.expandedTreeAt` |
| `ReflectExpansionsCollector` + `ScalaReflectMacroExpansionParser` | n/a — Scala 3 has no compiler message to scrape; use `pc` |
| `ReflectExpansionLineMarkerProvider` | intention action that calls `PcExpansionProvider` and shows the tree |
| `MacroExpansionLineMarkerProvider` (scala.meta mode) | same intention, scoped to Scala 3 files |
| `CompilerType` user-data hack (`ScExpression.scala:317-329`, `ScStableCodeReferenceImpl.scala:478-490`, `completion/package.scala:263-283`) | resolved directly from `pc` types; `CompilerType` becomes a one-line cache around `pc.typeAt` |
| `ScSyntheticClass` `val quotes` stub (`ScSyntheticClass.scala:619-626`) | no longer needed once `pc` resolves `Quotes` from the real library |
| `ScMacroDefinitionImpl.returnType` fallback to `Any` (line 27-30) | `pc` resolves the return type from the macro definition's TASTy |
| `SyntheticImplicitInstances.mirrorType` (Mirror synthesis, lines 38-77) | `pc` returns the real `Mirror` instance synthesized by dotc |

### 4.3 `SyntheticMembersInjector` impls — keep, migrate, or retire

The EP (`SyntheticMembersInjector.scala:19-63`) is shared across Scala 2
and Scala 3. Proposed split:

**Keep for Scala 2 only** (the underlying libraries are Scala 2 macro
libraries and the injectors emulate those macros):

- `MonocleInjector` — `@Lenses` is a Scala 2 macro annotation.
- `ScalazDerivingInjector` — `@deriving` is a Scala 2 macro annotation.
- `CirceCodecInjector` — `@JsonCodec` uses Scala 2 whitebox macros.
- `NewTypeInjector` — `@newtype` is a Scala 2 macro.
- `SimulacrumInjector` — `@typeclass` is a Scala 2 macro annotation.
- `DerevoInjector` — `@derive` is a Scala 2 macro annotation.
- `ScioInjector` — `@AvroType.toSchema` etc. are Scala 2 macros.

For Scala 3 these libraries are either unmaintained or have macro-free
replacements (`scala.deriving.Mirror`, type-class `deriving`); the
injectors should be short-circuited via `source.getLanguage
.isKindOf(Scala3Language.INSTANCE)` guards and the work delegated to
`PcExpansionProvider`.

**Retire for Scala 3 in favour of `pc`**:

- `DerivesInjector` (`DerivesInjector.scala:9-32`) — Scala 3 `derives` is
  typechecked by dotc and the synthesised given is available from `pc`'s
  typer.
- `EnumMembersInjector` (`EnumMembersInjector.scala:11`) — `values`,
  `valueOf`, `fromOrdinal` are compiler-synthesised; `pc` will report them.
- `SyntheticImplicitInstances.mirrorType` for `scala.deriving.Mirror` (lines
  38-77) — dotc generates the real Mirror instance.

**New: `PcSyntheticMembersInjector`**

A single Scala 3 injector registered only for Scala 3 modules that
delegates to `PcExpansionProvider.syntheticMembersOf(td)`:

```scala
final class PcSyntheticMembersInjector extends SyntheticMembersInjector:
  override def needsCompanionObject(source: ScTypeDefinition): Boolean =
    source.isInScala3File &&
      pcProvider(source).exists(_.needsCompanionFor(source))
  override def injectFunctions(source: ScTypeDefinition): Seq[String] =
    if !source.isInScala3File then Nil
    else pcProvider(source).map(_.syntheticMembersOf(source))
      .getOrElse(Nil).map(_.signatureText)
```

This works because the EP is stringly-typed
(`SyntheticMembersInjector.inject` at line 82). A more invasive change is
to widen the EP to return `Seq[PcSyntheticMember]` with a
`navigationElement` so the synthetic members can carry the real `Symbol`
from `pc` instead of being unattached PSI.

### 4.4 Inlay hints / gutter icons / intentions

- **Gutter icon "show expansion"** (replaces `ReflectExpansionLineMarkerProvider`):
  available at every `pc`-reported `Inlined` call. Clicking opens a
  read-only editor tab with the expansion formatted by `pc.NodePrinter`
  and positions remapped to the inline definition's source file.
- **Inlay hints for inlined calls** — decorate `inline def` call sites with
  a collapsed hint (à la "1+1 → 2" for `transparent inline val`). Backed by
  the same `PcExpansionProvider.expandedTreeAt` call; cheap because the
  tree is already in `pc`'s typer state.
- **"Go to macro definition"** — `Symbol.source` from the inlined call's
  `InlineCall` node gives the `.scala` file and offset directly.

### 4.5 Scala 2 — keep the compile-server reflect output

The reflect scraper (`ReflectExpansionsCollector`,
`ScalaReflectMacroExpansionParser`) and `ScalaMacroEvaluator` should stay
for Scala 2 modules: they consume compiler stdout, which is the only
available source of expansion data without bundling `scalac` into the IDE.
The sbt hookup at
`sbt/sbt-impl/src/org/jetbrains/sbt/shell/SbtProjectTaskRunnerImpl.scala:365-377,453`
remains the right seam.

Suggested deprecations over time:

- The hand-written shapeless emulators in
  `lang/macros/evaluator/impl/Shapeless*.scala` should be gated behind an
  explicit "no compile server available" fallback. They actively
  mis-lead users on Scala 2.13 + shapeless 3 builds.
- `ScalaMetaMode` (the user-facing toggle in
  `MacroExpansionLineMarkerProvider.scala:32`) should be retired for
  Scala 3; expansion is always available via `pc`.

---

## 5. Performance

Macro expansion is the single most expensive operation the IDE can perform:
running a Scala 3 macro means running arbitrary user code inside `dotc`.
Guidelines for when to invoke `PcExpansionProvider`:

- **Never on file open.** Lazily compute on first request (completion,
  goto, hover, "show expansion" intention). `pc` already maintains a
  per-file typer state that is reused across queries.
- **On save, warm the cache.** Mirror the existing
  `TriggerCompilerHighlightingService` pattern
  (`compiler-integration/src/.../TriggerCompilerHighlightingService.scala:142`):
  a document-save trigger enqueues a `pc.typeCheck` and stores the
  result keyed by `(fileUri, documentVersion)`. The cache lives until
  the document changes or the project closes.
- **Cache key = document version.** The plugin already has the
  `documentVersionsFor(request)` plumbing in
  `CompilerHighlightingService.executeDocumentCompilationRequest`
  (`compiler-integration/src/.../CompilerHighlightingService.scala:294`).
  The same versioning applies to `pc`: each PcExpansion is tied to a
  specific document version and is invalidated on the next PSI change.
- **Visible range only.** When used for inlay hints, expand only the
  calls intersecting the editor viewport. `pc` can be invoked with a
  position range restriction; do not expand the whole file.
- **Debounce completion expansion.** `pc` completion is already debounced;
  expansion for completion items should be requested only when an item
  is *highlighted* in the lookup, not for every item.
- **Synthetic-member injector.** `PcSyntheticMembersInjector.injectFunctions`
  must be O(1) for unrelated type definitions. The fast-path check
  (`source.getLanguage.isKindOf(Scala3Language.INSTANCE) &&
   pcProvider(source).exists(_.knows(source))`) avoids any compiler work.
- **Budget per editor frame.** Cap concurrent `pc` invocations to the
  number of CPU cores; reject additional requests with
  `ProcessCanceledException` so the daemon retries on the next pass.

Caches to introduce:

```
PcExpansionCache:
  key: (VirtualFile, Long documentVersion, Int offset)
  value: PcExpansion (call symbol, body tree, source span)

PcSyntheticMembersCache:
  key: (PsiClass modTracker stamp)
  value: Seq[PcSyntheticMember]
```

Both invalidate on `BlockModificationTracker` for the containing file, mirroring
the existing `cachedInUserData("getTypeWithoutImplicits", ...)` pattern at
`ScExpression.scala:308-314`.

---

## 6. Current limitations — known-broken in Scala 3

These are not YouTrack IDs (the codebase uses `SCL-NNNNN` mostly for
project-structure issues), but limitations visible directly in the source:

- **Transparent inline return types** — only available via the
  `CompilerType` user-data hack in `ScSyntheticClass.scala:619-626` and
  the `CompilerTypeRequestListener` round-trip. Without compiler
  highlighting enabled, every transparent-inline call resolves to `Any`.
- **Recursive transparent inline** — explicitly unsupported; the
  `ScalaMacroEvaluator.expandMacro` has an `isMacroExpansion` guard
  (`ScalaMacroEvaluator.scala:55`) preventing recursion but only for
  Scala 2 emulators; Scala 3 has no equivalent guard and falls back to
  the unresolved case.
- **Inline match / inline if** — no expansion at all. The plugin parses
  them (`parser/parsing/...`) but treats the result as an ordinary
  expression. The user sees the un-expanded tree.
- **Quoted macros** (`inline def foo(using Quotes): Expr[T] = '{ ... }`)
  — `'{ }` and `${ }` parse, but the splice has no type. The plugin's
  `ScSplicedBlock` (`psi/api/expr/ScSplicedBlock.scala:16`) does not
  compute a type for the spliced expression.
- **Quoted patterns** — `$ { x }` in a quoted pattern
  (`ScSplicedPatternExpr.scala:13`) does not bind `x` to a typed
  capture.
- **`derives` with derivation macros** — `DerivesInjector.scala:23` always
  emits `$typeClassRef.derived`. If the typeclass has a `derived` that
  itself uses a macro (e.g. Magnolia, Circe-derivation), the call
  resolves but the *result type* of the implicit is unknown.
- **`scala.deriving.Mirror` synthesis** — handled purely in PSI
  (`SyntheticImplicitInstances.scala:38-77`). Any case that the PSI-based
  `mirrorDescriptorFor` (line 228) doesn't recognise (e.g. sealed traits
  with package-private cases, generic enums with non-simple types) will
  fall back silently.
- **Macro definition return types** — `ScMacroDefinitionImpl.scala:27-30`
  returns `Any` for any macro def without an explicit return type.
- **Scala 2 macro inside Scala 3 source** — not supported; the
  `LanguageFeatureInspection.scala:66` only registers
  `scala.language.experimental.macros` and there is no way for a Scala 3
  file to declare a Scala 2 macro.
- **Expansion of macros in dependencies** — the `.tasty` reader discards
  inlined metadata (`TreeReader.scala:109`), so even for a fully compiled
  dependency, the IDE cannot show what a macro expanded to.
- **`ScMetaIntentionAction`** (`macros/expansion/ScalaMetaIntentionAction.scala`)
  — empty stub; no scala.meta integration is actually shipped.

These limitations all collapse to a single root cause: there is no real
Scala 3 typer behind the plugin's view of the source. Wiring `pc` as
`PcExpansionProvider` resolves them in one move.
