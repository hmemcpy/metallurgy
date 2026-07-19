# Metallurgy

A third-party IntelliJ plugin that augments the bundled Scala plugin for Scala 3 modules by delegating language semantics to the real Scala 3 presentation compiler (`pc`). The name is a play on Metals (whose backend is `pc`) and the discipline of working with metals; the plugin "refines" the bundled plugin's output using `pc`. This is the glossary for the project.

## Language

### The plugin

**Bundled plugin**:
The official JetBrains Scala plugin (`org.intellij.scala`) shipped with IntelliJ IDEA. Owns parser, PSI, type system, build integration, Scala 2 support, refactorings, debugger, worksheet, REPL. This project does not modify or replace it.
_Avoid_: "the Scala plugin" (ambiguous — could mean us), "JetBrains Scala plugin" (use the canonical id).

**Metallurgy**:
This plugin. A third-party add-on that registers additional IntelliJ extension points to augment the bundled plugin's results for Scala 3 modules only.
_Avoid_: "the Metals plugin" (Metals is the VS Code / LSP server; we are not it), "PC plugin" (too generic).

### The compiler side

**pc**:
The Scala 3 presentation compiler — `dotty.tools.dotc.interactive.InteractiveDriver` behind the stable
`scala.meta.pc.PresentationCompiler` Java API used by Metals. Runs the compiler frontend (parser → typer
and a few post-typer phases like `inlining`) in-memory, with no `.class`/`.tasty` emission. Designed
for IDE use: asynchronous, interruptible, partial-result-on-error.
_Avoid_: "the compiler" (ambiguous — could mean `dotc` itself), "Metals" (Metals is the LSP server; `pc` is the library Metals *uses*).

**`dotc`**:
The Scala 3 compiler proper. `pc` embeds `dotc` and exposes a subset of its behaviour over a stable, MiMa-enforced interface.

**presentation-compiler distribution**:
The exact-version `org.scala-lang:scala3-presentation-compiler_3` artifact and its transitive graph.
`org.scalameta:mtags-interfaces` supplies the parent-loaded Java API; the implementation is loaded through
`ServiceLoader` in an isolated child-first classloader.

### The IDE side

**Scala 3 module**:
An IntelliJ `Module` whose `ScalaSdk` has `version.major == 3`. The unit of opt-in for this plugin. Detected by `ModuleDetectionService`.

**Opted-in module**:
A Scala 3.5+ module for which Metallurgy's features are active. The opt-in flag is `MetallurgySettings.isEnabled(module)` — our own per-module project setting, persisted in `.idea/metallurgy.xml`. Default off. First detection of a Scala 3.5+ module triggers a notification prompting the user to enable. See ADR 0006.

**MetallurgySettings**:
The persistent project-level state service. Holds per-module opt-in flags, the bulk "enable for all Scala 3 modules" toggle, and feature-level toggles (Phase 1 features, Phase 2 diagnostics, etc.). Lives under `Settings | Languages & Frameworks | Metallurgy`. Distinct from the bundled plugin's `ScalaProjectSettings` and from `isUseCompilerTypes` (which we leave alone).

**MtagsFetcher**:
The historically named component that resolves the exact Scala presentation-compiler distribution through
the bundled Scala plugin's dependency manager. It runs in `Task.Backgroundable` and maintains a validated
SHA-256 cache under IntelliJ's system cache directory. Offline-tolerant: a valid cache is local-only; a cold
cache without network leaves the bundled plugin as fallback. See ADR 0003.

**PcSession**:
A long-lived per-module handle owning a `PresentationCompiler` instance plus its classpath, scalac options, and Scala binary version. Created lazily on first use after `MtagsFetcher` has the artifacts; hot-swapped when classpath changes; closed when the module is removed. Single-writer, multi-reader; in-flight retypecheck can be cancelled.

**PcSnapshot**:
An immutable per-`(file, DocumentVersion)` view of one file's state inside its `PcSession`: typed tree, diagnostics, hover cache, completion cache, semantic tokens. Refreshed on a debounced (300 ms) edit / save cycle. Queries during a retypecheck see the prior snapshot; never block, never fail.

**PcSemanticSideTable**:
A `(PsiElement, DocumentVersion) → (Symbol, ScType, expansion)` lookup that downstream IntelliJ features (hover, inlay, navigation) consult. Populated from a `PcSnapshot`; not a separate data source.

### The transport seam

**CompilerType (slot)**:
A copyable `UserData` key on `PsiElement` (`org.jetbrains.plugins.scala.lang.psi.impl.CompilerType`) where the bundled plugin today stores a *string*-rendered type for transparent-inline call sites, scraped from the compile-server output stream via the bundled `intellij-compiler-plugin` scalac plugin. Consumers — `ScExpression.getType()`, `ScStableCodeReferenceImpl`, the completion machinery — read this slot first and fall through to the bundled resolver if empty.
_Avoid_: "the type slot", "compiler-types" (that's the *setting*, not the slot).

**`isUseCompilerTypes` (bundled setting)**:
The bundled plugin's existing `ScalaProjectSettings` boolean that turns the bundled scalac-plugin-based transparent-inline pipeline on or off. **Metallurgy does not use this flag.** It is the bundled plugin's, untouched. Users who want belt-and-braces can turn both on; users who only want us turn ours on and theirs off (saving the bundled pipeline's compile-server round-trip cost). See ADR 0006.

**CompilerType.Topic**:
The IntelliJ `Topic` that fires `onCompilerTypeRequest(e)` when a consumer needs a type the bundled plugin couldn't compute. Today it triggers a full compile-server recompile. This plugin subscribes and answers from `pc` instead — Feature 0. The Topic is published by the bundled plugin; we are a consumer, not a producer.

### The artifacts

**TASTy**:
Typed AST serialization (`.tasty`), emitted by `dotc` to `target/` next to each `.class` on successful compilation. Authoritative source of types/symbols for compiled modules.

**BETASTy**:
"Best-Effort TASTy" (`.betasty`), emitted under `META-INF/best-effort/` when `-Ybest-effort` is set. Forces compilation through typer regardless of errors and serializes the result with `ERRORtype` placeholders for untypeable parts. Read with `-Ywith-best-effort-tasty` so downstream compilation (and `pc`) can see useful symbols from upstream modules that don't compile.
_Avoid_: "untyped TASTy", "pre-typer TASTy", "retained trees" (those refer to `-YretainTrees`, an unrelated in-memory debug knob).

**Classpath (of a module)**:
The compiled artifact roots (output dirs + library jars) `pc` needs to resolve symbols outside the live source buffer. Supplied by the bundled plugin's `ScalaCompilerConfiguration` + module scope; never produced by us.

### The interception patterns

**Augment**:
Pattern: register an additional EP that runs *alongside* the bundled one and *adds* results it missed (e.g. completions for transparent-inline macros).
_Avoid_: "replace", "override" (those are different patterns).

**Suppress**:
Pattern: register an EP (typically `problemHighlightFilter`) that *removes* a bundled result `pc` says is wrong (e.g. a false-positive "unresolved reference").

**Enrich**:
Pattern: register an EP with `order="before …"` that returns a richer answer than the bundled one (e.g. hover with the real transparent-inline type instead of `Any`), falling through to bundled if we have nothing.

**Piggyback**:
Pattern: reuse the bundled plugin's data or pipeline (classpath, build, indices) instead of running a parallel one. Distinguished from *augment* (which is about IntelliJ EPs).

### Scope boundaries

**Scala 2 module**:
Explicit non-goal. We never touch Scala 2 modules. Detection short-circuits all our EP implementations.

**Scala 3 module below 3.5.0**:
Explicit non-goal. BETASTy is the floor; we have no graceful-degradation matrix for older Scala 3 versions. Detection shows a notification, plugin is no-op for that module. See ADR 0001.

**Editor mechanics**:
Format / fold / surround / smart-enter / typed / backspace / copy-paste / live templates / structure view / breadcrumbs. Pure syntax; owned by the bundled plugin; untouched by us.

**Build pipeline**:
sbt/BSP/JPS import, compile server, nailgun, run configurations, debugger, REPL, worksheet runtime. Owned by the bundled plugin. We add two scalac flags (`-Ybest-effort`, `-Ywith-best-effort-tasty`) to opted-in modules; everything else is piggyback.

### Acceptance corpus

**Phase 1 macro/inline acceptance corpus**:
Ten canonical fixtures under `testdata/feature/compilertype/` covering transparent-inline → refined type, typeclass derivation (`derives`), inline match, match-type reduction, `compiletime.ops` arithmetic, native tuple `*:` HList, zio-direct `defer`/`run` rewriting, jing structural-type API navigation, and quote/splice macros. Each fixture has a "Metallurgy on" assertion (must pass) and a "Metallurgy off" baseline (must demonstrate the bundled plugin's failure, so our value-add is provably non-zero). See `docs/design.md` §15 and `docs/research/11-canonical-macro-acceptance-tests.md`.
