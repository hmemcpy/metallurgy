# 03 — Highlighting Modes & Annotators (Built-in vs Compiler)

Domain report for the Metals-`pc` redesign of Scala 3 support. This document maps the
current "Built-in vs Compiler" highlighting split in IntelliJ Scala, traces the
data path from `dotc` to a squiggly underline, surveys the TASTy reader and the
absence of any BETASTy support, and proposes seams for a `pc`-driven replacement.

---

## 1. The `HighlightingMode` system

### 1.1 Two coexisting "type checkers", per language major version

The plugin does **not** have a single global on/off switch for highlighting. It
has a per-major-version toggle exposed in `ScalaProjectSettings.java:183-189`:

```java
private boolean COMPILER_HIGHLIGHTING_SCALA2 = false;
private boolean COMPILER_HIGHLIGHTING_SCALA3 = true;   // default for Scala 3
```

The two booleans flow through `ScalaHighlightingMode.scala:25-43`:

```scala
def showCompilerErrorsScala3(project: Project): Boolean =
  compilerHighlightingEnabledInTests ||
    !isInTestMode && ScalaProjectSettings.getInstance(project).isCompilerHighlightingScala3
```

and are combined into a single predicate `isShowErrorsFromCompilerEnabled`,
which has overloads for `Project`, `ScalaFile`, and `PsiJavaFile`
(`ScalaHighlightingMode.scala:40-82`). The Java overload exists so that Java
files in Scala 3 modules can also receive Scala-3-compiler errors.

Auxiliary settings on the same bean:

| Setting | Field | Effect |
|---|---|---|
| `isTypeAwareHighlightingEnabled` | `TYPE_AWARE_HIGHLIGHTING_ENABLED` (`ScalaProjectSettings.java:182`) | Master switch for "Built-in" semantic annotators |
| `isIncrementalHighlighting` | `INCREMENTAL_HIGHLIGHTING` (`ScalaProjectSettings.java:185`) | Visible-area-only highlighting (SCL-23216) |
| `isDisableInspections` | `DISABLE_INSPECTIONS` (`ScalaProjectSettings.java:186`) | When both compiler modes are on, suppress local inspections entirely |
| `isUseCompilerTypes` | `USE_COMPILER_TYPES` (`ScalaProjectSettings.java:187`) | Inject compiler-reported types back into PSI via the `intellij-compiler-plugin` |
| `getCompilerHighlightingDelay` | `COMPILER_HIGHLIGHTING_DELAY` (`ScalaProjectSettings.java:189`, default `750` ms) | Debounce for the document compiler |

### 1.2 UI surface

- The status-bar widget at `ScalaHighlightingModeWidget.scala:16-75` renders an
  HTML tooltip that branches on `project.hasScala2 && project.hasScala3`. When
  the two versions disagree on the mode, two tables are rendered side-by-side.
- Clicking the widget invokes `ScalaHighlightingModeAction.scala:25-27`, which
  opens the editor settings page `EditorSettingsSectionConfigurable.scala:6-9`
  → `EditorSettingsSectionPanel.java:45`. The panel shows two
  `JComboBox<TypeChecker>` (`EditorSettingsSectionPanel.java:79,82`), one per
  Scala major, plus the auxiliary checkboxes (`typeAwareHighlighting`,
  `incrementalHighlighting`, `disableInspections`, `useCompilerTypes`,
  `compilerHighlightingDelay`).

### 1.3 Registry keys

From `scala-plugin-common.xml:174-781` and the consumers:

| Key | Default | Consumer |
|---|---|---|
| `scala.highlighting.tracing` | `false` | `Tracing.scala:52` |
| `scala.highlighting.tracing.in.editor` | `true` | `Tracing.scala:54` |
| `scala.incremental.highlighting.lookaround` | `15` | `VisibleRange.scala:16` |
| `scala.highlighting.compilation.timeout.to.show.progress.millis` | `7000` | `ScalaHighlightingMode.scala:91` |
| `scala.compiler.highlighting.use.compiler.ranges` | `true` | `ScalaHighlightingMode.scala:93`; honored in `UpdateCompilerGeneratedStateListener.scala:105-111` |
| `scala.compiler.highlighting.suppress.java.highlighting` | `false` | `ScalaDefaultHighlightingSettingProvider.scala:20` |
| `scala.compiler.highlighting.document.use.in.memory.file` | `false` | `DocumentCompiler.scala:53` |

### 1.4 Mode coupling

`ToggleHighlightingModeListener.scala:20-49` is the wire between the bean and
the rest of the plugin. When either compiler-highlighting boolean flips:

1. If compiler mode is being **enabled**: `AnnotatorHints.clearIn(project)`
   clears the local annotators' cached hints.
2. If compiler mode is being **disabled**: `ExternalHighlightersService.eraseAllHighlightings`
   wipes every open editor's `ScalaCompilerPassId` highlighters and clears
   Wolf (`ExternalHighlightersService.scala:182-200`).
3. `forceStandardHighlighting` drops the resolve cache and PSI caches and
   restarts the daemon — there is no incremental transition.

### 1.5 What each mode does differently

- **Built-in (typeAware)**: drives `ScalaAnnotator` (§2) inside IntelliJ's
  `Annotator` extension point; checks run synchronously inside the daemon pass,
  per element, with type-aware bits gated by `HighlightingAdvisor.isTypeAwareHighlightingEnabled`
  (`HighlightingAdvisor.scala:30-54`).
- **Compiler**: dispatches an out-of-process `dotc`/`scalac` invocation via
  `CompilerHighlightingService` (§3); results land asynchronously as
  `ExternalHighlighting` records that are painted with a separate
  `ScalaCompilerPassId = 979132998` (`ExternalHighlightersService.scala:488`).
- **Incremental**: orthogonal to the above; toggles the visible-area listener
  (§4) on top of either mode. The "incremental" name refers to *visible-area
  scoping of the daemon*, not to incremental compilation.

---

## 2. The Built-in (hand-written) annotator stack

### 2.1 Entry points

Three independent `Annotator`/`HighlightVisitor` extensions are registered for
Scala files:

1. `ScalaAnnotator` (`ScalaAnnotator.scala:36-39`) — semantic checks. Inherits
   `FunctionAnnotator`, `OverridingAnnotator`, `DumbAware`.
2. `ScalaColorSchemeAnnotator` (`ScalaColorSchemeAnnotator.scala:38-47`) — text
   attributes (collection highlighting, ref-resolve colors, etc.).
3. `ScalaSyntaxHighlightingVisitor` (`ScalaSyntaxHighlightingVisitor.scala:22-96`)
   — a `HighlightVisitor` that re-derives token kinds (named args, soft
   keywords, parameter names, doc tokens). Comment at line 21 says this shape is
   faster than `Annotator` in complex code (SCL-23603).

### 2.2 Dispatch by element class

`ScalaAnnotator.annotate` (`ScalaAnnotator.scala:64-231`) constructs a
`ScalaElementVisitor` and dispatches it via `element.accept(visitor)`. The real
catalog lives in `ElementAnnotator.scala:25-87`, which is a registry of **42
typed `ElementAnnotator[T]` subclasses** (one per PSI class: `ScFunctionAnnotator`,
`ScReferenceAnnotator`, `ScTemplateDefinitionAnnotator`, …). Dispatch is cached
per `element.getClass` (`ElementAnnotator.scala:99-110`), and during dumb mode
only `DumbAware` annotators are executed (`ElementAnnotator.scala:93-97`).

The holder is `ScalaAnnotationHolder` (the API interface) implemented by
`ScalaAnnotationHolderAdapter.scala:6-19`, which wraps IntelliJ's
`AnnotationHolder` and produces `ScalaAnnotationBuilder`s. This is the seam
where every hand-written check produces an `Annotation`.

### 2.3 Type-aware gating

Each `annotate` call receives a `typeAware: Boolean` (`ScalaAnnotator.scala:64`)
computed by `ScalaAnnotator.annotate` at line 50-52:

```scala
val typeAware =
  if ((file ne null) && ScalaHighlightingMode.isShowErrorsFromCompilerEnabled(file)) false
  else HighlightingAdvisor.isTypeAwareHighlightingEnabled(element)
```

So **when compiler highlighting is on for a file, every built-in annotator is
downgraded to its non-type-aware branch** — i.e. it only performs syntactic
checks. This is precisely the carve-out the `pc` redesign needs to widen.

### 2.4 File-level filters

- `ScalaProblemHighlightFilter.scala:11-48` decides whether the daemon runs at
  all on a Scala file (scratch, console, source roots).
- `ScalaDefaultHighlightingSettingProvider.scala:12-33` returns
  `SKIP_HIGHLIGHTING` for Java files inside Scala 3 modules when
  `scala.compiler.highlighting.suppress.java.highlighting` is on — handing
  those files entirely to the compiler pass.
- `HighlightingAdvisor.shouldInspect` (`HighlightingAdvisor.scala:20-28`) is
  the per-element gate.

### 2.5 The 42-element catalog (sample)

Most element annotators are tiny: `ScExportStmtAnnotator`, `ScGivenAliasDefinitionAnnotator`,
`ScDerivesClauseAnnotator`, `ScEnumCaseAnnotator`, `ScNamedTupleTypeElementAnnotator`,
`ScOverriddenVarAnnotator`, etc. They live under
`scala/scala-impl/src/org/jetbrains/plugins/scala/annotator/element/`. Template-level
checks live under `annotator/template/` (`CaseClassWithoutParamList`,
`TraitHasImplicitBound`, `ImplicitParametersAnnotator`, `PrivateBeanProperty`).

---

## 3. Compiler-driven highlighting

### 3.1 Triggers

`TriggerCompilerHighlightingService.scala:29` is the project-scoped service that
owns three entry points:

- `triggerOnFileChange` (`TriggerCompilerHighlightingService.scala:71-92`):
  PSI/file listener; routes to incremental compilation, or to the *document
  compiler* if one is already warm for that file.
- `triggerOnEditorFocus` (`TriggerCompilerHighlightingService.scala:94-109`):
  focus gained in a Scala editor.
- `FileHighlightingSettingListener` (`TriggerCompilerHighlightingService.scala:33-69`):
  per-file "Power Save" / "None" toggles.

Each entry hands off to `CompilerHighlightingService.get(project)`.

### 3.2 The request queue

`CompilerHighlightingService.scala:48-653` is a single-bounded
`ScheduledExecutorService` plus a `ConcurrentSkipListSet<CompilationRequest>`
ordered by deadline. Three request shapes
(`CompilationRequest.WorksheetRequest`, `IncrementalRequest`, `DocumentRequest`)
are merged and debounced in `CompilationTask.run`
(`CompilerHighlightingService.scala:487-615`). The merge logic at lines 535-585
exists specifically to coalesce all open-editor requests into one JPS build.

### 3.3 The compile server path

The actual compile goes through:

1. `IncrementalCompiler.compile` (JPS path) or `BspProjectTaskRunner` (BSP path)
   in `CompilerHighlightingService.scala:234-284`.
2. `DocumentCompiler.compile` (`DocumentCompiler.scala:46-74`) for single-file
   recompile. It can either write a temp file (default) or use an in-memory
   source (`-Ystop-after:repeatableAnnotations` is forced for Scala 3 at line
   212 to suppress `.class` emission).
3. The compiler runs inside `CompileServer` (see `scala/compile-server/`).
4. It writes `ClientMsg`s back via the `org.jetbrains.jps.incremental.scala.Client`
   API. Messages carry `MessageKind`, text, optional `source`, optional
   `problemStart`/`problemEnd` (both `PosInfo(line, column)`), optional
   `pointer`, and a `List[Action]` of LSP-style code actions
   (`diagnostics.scala:5-9`).

### 3.4 From message to `HighlightInfo`

The bridge is `CompilerEventGeneratingClient.scala:14-75`, a `DummyClient`
override that:

1. Receives `message`, `progress`, `compilationStart`, `compilationEnd`.
2. Forwards each as a sealed `CompilerEvent` over the `CompilerEventListener` topic.
3. Tags the compilation with a `CompilationId(timestamp, documentVersions)` so
   stale results can be discarded (`CompilerEventGeneratingClient.scala:24`).

`UpdateCompilerGeneratedStateListener.scala:28-161` accumulates these events
into `CompilerGeneratedState` (per-file `Set[ExternalHighlighting]` plus a map
of compiler-reported types). On `CompilationFinished` it invokes
`ExternalHighlightersService.applyHighlightingState`
(`ExternalHighlightersService.scala:67-179`), which:

1. Computes `HighlightInfo`s from `ExternalHighlighting`s via
   `toHighlightInfo` (`ExternalHighlightersService.scala:282-365`). This is
   where the message-kind → `HighlightInfoType` mapping occurs (delegated to
   `CompilerMessageKinds.scala:7-43`, which handles wrong-ref sniffing,
   unused-import rewriting, fatal-warning promotion).
2. Calls `UpdateHighlightersUtil.setHighlightersToEditor(..., ScalaCompilerPassId)`
   (`ExternalHighlightersService.scala:130-136`).
3. `informWolf(errorFiles)` (`ExternalHighlightersService.scala:202-208`)
   forwards the set of files with errors to `WolfTheProblemSolver`, which is
   what paints red in the Project View.

### 3.5 `WolfTheProblemSolver`

Wolf is IntelliJ's platform abstraction for "this file has problems from an
external source". The plugin uses it four ways:

- `reportProblemsFromExternalSource(vFile, self)` — register errors
  (`ExternalHighlightersService.scala:206`).
- `clearProblemsFromExternalSource(vFile, self)` — on file delete
  (`CompilerHighlightingFileListener.scala:47`) and on mode toggle
  (`TriggerCompilerHighlightingService.scala:53`).
- `clearAllProblemsFromExternalSource(project, self)` — when erasing
  (`ExternalHighlightersService.scala:199`; impl in `ProblemSolverUtils.java:14-32`).
- `hasSyntaxErrors(vFile)` — queried by the worksheet auto-runner
  (`WorksheetAutoRunner.scala:122`).

The `self` argument is `ExternalHighlightersService` itself, used as the
distinguishing key for "problems originating from the Scala compiler pass".

### 3.6 What diagnostics does the compiler emit today?

`CompilerDiagnosticsTest_3.scala:7-291` is the canonical fixture. The cases
exercised under Scala 3.Latest, 3.5, 3.6, 3.7, 3.8 are:

- `testConvertToFunctionValue` — `Only function types can be followed by _ …`
  with quick fix "Rewrite to function value".
- `testInsertBracesForEmptyArgument` — `method foo … must be called with ()`
  with "Insert ()".
- `testRemoveRepeatModifier` — `Repeated modifier final` with "Remove repeated
  modifier".
- `testInsertMissingCasesEnum`, `…ForUnionStringType`, `…ForUnionIntType`,
  `…WithBraces` — `match may not be exhaustive.` (warning) with "Insert missing
  cases (N)".
- `testIntersectingRangesAfterApplication` — two distinct error ranges from
  one source line.
- `testOutOfBounds` — `String` followed by `_`.

`CompilerDiagnosticsTest_2_13.scala` and `_3_RC.scala` cover older compilers.
Each test exercises the **end-to-end path**: trigger compilation → fetch
`HighlightInfo`s → invoke every quick fix → assert the rewritten source.

Note that the `Action` payload (`diagnostics.scala:5`) carries
`WorkspaceEdit(changes: List[TextEdit])`. The compiler itself proposes the
fix; `CompilerDiagnosticIntentionAction.create` (referenced at
`ExternalHighlightersService.scala:264`) wraps each `Action` as an
`IntentionAction` registered on the highlight.

---

## 4. Incremental (visible-area) highlighting

Lives under `scala/scala-impl/src/org/jetbrains/plugins/scala/incremental/`.

### 4.1 Mechanism

`Listener.scala:16-112` is an `EditorFactoryListener` registered via the
extension point at `Listener.scala:117`. On editor creation it attaches four
listeners (`Listener.scala:86-96`): a `MarkupModelListener` on both the main
and filtered markup models, a `VisibleAreaListener`, a `FoldingListener`, and a
key listener that detects double-Escape to suppress highlighting
(`Listener.scala:63-74`).

### 4.2 The updater

`Updater.scala:19-62` debounces (`UPDATE_DELAY = 200` ms) and on each fire:

1. Recomputes the visible `TextRange` via `VisibleRange.saveIn`
   (`VisibleRange.scala:39-42`).
2. Conceals error-stripe marks outside the visible range, reveals those inside
   (`Updater.scala:69-102`) — purely a UI optimization so the scroll bar
   doesn't get saturated.
3. Calls `daemon.combineDirtyScopes(document, newlyVisibleRange)` then
   `daemon.stopProcess(restart = true)` (`Updater.scala:48-54`). These two
   calls go through reflection on platform-private methods
   (`package.scala:15-37`) — the daemon's dirty scopes are expanded to cover
   the newly visible region and then the pass is restarted.

### 4.3 Visibility predicate

`Highlighting.ElementHighlightingExt.isVisible`
(`Highlighting.scala:41-48`) is called at the top of every `Annotator.annotate`
(`ScalaAnnotator.scala:48`, `ScalaColorSchemeAnnotator.scala:43`). If the
element's range does not intersect any editor's visible range (with
`lookaround = 15` lines), the annotator returns early. This is the only thing
that makes 50k-line generated files editable.

### 4.4 Tracing

`Tracing.scala` paints resolve/inference hotspots in-editor when
`scala.highlighting.tracing` is on. `Tracing.annotator(element)` is invoked at
`ScalaAnnotator.scala:56`. It also surfaces "Resolve: …" / "Inference: …"
markers on the error stripe.

---

## 5. TASTy / BETASTy reader

### 5.1 Current scope of `tasty-reader`

The module at `scala/tasty-reader/src/` is a **single-purpose decompiler**:

- `TastyImpl.scala:9-21` exposes one method: `read(bytes): Option[(String, String, CompilerOptions)]`
  returning (file path, decompiled text, scalac options).
- `TreeReader.scala:13-191` parses the binary format. It understands the AST
  section and (since minor version ≥ 6) the Positions section
  (`TreeReader.scala:149-157`).
- `Node.scala:6-52` is the lazy tree node: `addr`, `tag`, `names`, lazy
  `children`, lazy `modifierTags`, plus `refTag`/`refName`/`refPrivate` for
  symbol refs.

### 5.2 Where is it consumed?

Only for **decompilation**, not for highlighting:

- `scala/scala-impl/src/org/jetbrains/plugins/scala/tasty/TastyReader.scala:5-9`
  wraps `TastyImpl`.
- `TastyDecompiler` (referenced at `TastyDecompiler.scala:15`) is invoked from
  `DecompilationResult.scala:134` when opening `.tasty` files in the IDE.
- `ShowDecompiledTastyRawInternalAction.scala:10` imports `NameTable`,
  `NodePrinter`, `TreeReader` directly for an internal action.

There is **no** consumer of TASTy in the highlighting pipeline.

### 5.3 BETASTy

Grepping for `BETASTy | bETASTy | betasty | Betasty | BetaSty` finds **four
matches**, all in `scala-impl/resources/org/jetbrains/sbt/language/completion/scalac-options.json:4493-4496`:

> Enable best-effort compilation attempting to produce betasty to the
> `META-INF/best-effort` directory, regardless of errors, as part of the
> pickler phase.

So BETASTy is **greenfield** for this codebase: the option is documented for
Scala 3.5–3.8 users, but nothing in the plugin reads, parses, or consumes it.

### 5.4 Why this matters for the redesign

`-Ybest-effort` produces a `.betasty` file (a TASTy-like artifact, with an
added `ERRORtype` constructor) **even when the source has errors**. With
`-Ywith-best-effort-tasty`, downstream compilation (and `pc`) can read those
`.betasty` files from the classpath, so the typechecker sees useful symbols
and types from upstream modules that don't compile. This is the substrate
that lets `pc` provide:

- type info on every node, even in broken code;
- inline expansion visualization (the `INLINED` node already has special
  handling in `TreeReader.scala:109`);
- semantic tokens (deprecation, implicit conversion source, etc.) for the
  syntax highlighter;
- **cross-module error recovery** — the single biggest IDE win, since the
  user is almost always editing against a not-currently-compiling graph.

The existing `TreeReader` already parses positions; extending it to surface
those positions to a highlighting pass is a natural seam — though the
recommended path is to let `pc` parse `.betasty` itself rather than
extending the in-tree reader.

---

## 6. Seam recommendations

### 6.1 `pc.Diagnostics` → `Annotation` / `HighlightInfo`

The Metals `pc.Diagnostics` carries `range: Range`, `severity: DiagnosticSeverity`,
`message: String`, and `actions: List[lsp.CodeAction]`. The plugin already has
an isomorphic type — `ExternalHighlighting` (`ExternalHighlighting.scala:15-18`)
and `Action`/`WorkspaceEdit`/`TextEdit` (`diagnostics.scala:5-9`). The mapping
is mechanical:

| `pc` field | Plugin type | Site |
|---|---|---|
| `range.start.line`/`.character` | `PosInfo(line, column)` | `UpdateCompilerGeneratedStateListener.scala:77-81` |
| `severity` | `HighlightInfoType` via `CompilerMessageKinds.highlightInfoType` | `CompilerMessageKinds.scala:7` |
| `message` | `ExternalHighlighting.message` | `ExternalHighlighting.scala:16` |
| `actions` | `List[Action]` wrapped by `CompilerDiagnosticIntentionAction.create` | `ExternalHighlightersService.scala:264` |

So **the conversion layer already exists** for the compile-server pipeline. A
`pc`-driven Scala 3 implementation just needs to produce `ClientMsg`s (or
directly `ExternalHighlighting`s) and skip the `CompileServer` hop.

### 6.2 A `PcHighlightingPass` instead of `CompilerHighlightingService`

The IntelliJ pass-based model is fundamentally lazy: the daemon asks for
highlighting of a visible `TextRange` on demand. `pc` is fundamentally eager:
`pc.diagnose(uri)` returns the whole-file diagnostic list. Reconcile:

```
PcHighlightingPass extends TextEditorHighlightingPass
  - collects visible range from VisibleRange.in(editor)  // reuses §4.3
  - on edit or visible-area change, schedule:
       PcSession(file).diagnose()  // long-running, cancelable
  - results cached in PcSession keyed by DocumentVersion
  - on result, convert List[pc.Diagnostic] -> Set[HighlightInfo]
       via the §6.1 mapping
  - UpdateHighlightersUtil.setHighlightersToEditor(..., ScalaCompilerPassId)
  - Wolf.reportProblemsFromExternalSource(vFile, PcHighlightingService)
```

This replaces `CompilerHighlightingService` + `DocumentCompiler` +
`ExternalHighlightersService` + `UpdateCompilerGeneratedStateListener` for
Scala 3 files, while reusing `ExternalHighlighting`, `HighlightInfoType`,
`WolfTheProblemSolver`, and the `ScalaCompilerPassId` constant.

### 6.3 Per-source TASTy from `pc`/`dotc` consumed via an extended `tasty-reader`

Today `TastyReader.read` returns a `(path, text, options)` triple used only by
the decompiler. For the `pc` design we propose a second entry point:

```
TastyReader.readSemantic(bytes): Option[TastySemanticModel]
  case class TastySemanticModel(
    types:   Map[Addr, String],          // symbol-full type at each tree addr
    symbols: Map[Addr, SymbolInfo],      // resolved symbol per reference
    positions: Map[Addr, Int]            // already parsed by TreeReader.scala:165
  )
```

This stays inside the existing `tasty-reader` module (no new dependency on
`dotc`'s `TastyInspector`) and exposes:

- **Inline expansion visualization** — `TreeReader.scala:109` already special-cases
  `INLINED`; expose the inline source as a hint.
- **Type info at every node** — replaces the `intellij-compiler-plugin` round-trip
  done today via `<type>…</type>` messages parsed at
  `UpdateCompilerGeneratedStateListener.scala:64-70` and stored via
  `CompilerType` (`CompilerType.scala:7-19`).
- **Semantic tokens** — feed `ScalaSyntaxHighlightingVisitor` so it can paint
  deprecated refs, implicit-conversion targets, etc., without doing its own
  resolve.

### 6.4 Local-syntax inspections vs. `pc`-driven inspections

| Inspection | Lives in | Type info needed? | Decision |
|---|---|---|---|
| `Scala3DeprecatedAlphanumericInfixCallInspection` | `Scala3DeprecatedAlphanumericInfixCallInspection.scala:20-49` | Yes — must `resolve()` and check `infix` modifier | **Move to pc** (it's exactly a `@deprecated`-style semantic check) |
| `ScalaAnnotator` variance checks | `ScalaAnnotator.scala:233-357` | Yes — `calcType`, `expandAliases` | **Delete**; `dotc` reports variance errors with better ranges |
| `OverridingAnnotator` (override checks) | `annotator/OverridingAnnotator.scala` | Yes | **Delete**; `dotc`'s `override checks cover this` |
| `ImplicitParametersAnnotator` | `annotator/template/ImplicitParametersAnnotator.scala` | Yes | **Replace** with `pc`'s implicit-debug info |
| `ElementAnnotator` syntactic entries (`ScPatternAnnotator`, `ScAssignmentAnnotator`, `ScForAnnotator`, `ScInterpolatedStringLiteralAnnotator`) | `annotator/element/*` | Mostly no | **Keep** as local syntax checks |
| `ScalaColorSchemeAnnotator` (collection highlighting, ref colors) | `ScalaColorSchemeAnnotator.scala:38` | Yes | **Reimplement** on top of TASTy semantic model (§6.3) |
| `ScalaSyntaxHighlightingVisitor` | `ScalaSyntaxHighlightingVisitor.scala:22` | No — token-shape only | **Keep untouched** |
| `CaseClassWithoutParamList`, `TraitHasImplicitBound` | `annotator/template/*` | Borderline | **Keep** (cheap, syntactic) |

The rule of thumb: **if it calls `ScExpression.getType` or `resolve()`, it is a
candidate for deletion** because `pc` will already have computed that
information more accurately.

---

## 7. Migration path

**Separate syntactic and semantic annotators.** Split `ElementAnnotator.Instances`
(`ElementAnnotator.scala:27-87`) into two lists: `SyntacticAnnotators` and
`SemanticAnnotators`. The split is invisible to users but lets the next step
disable just the semantic half.

**Replace the transport, keep the producer.** Stand up
`PcHighlightingService` as a sibling of `CompilerHighlightingService`. Route
Scala 3 files to it. It still shells out to a remote compiler (the existing
compile server, but invoking `pc` instead of plain `dotc -Ystop-after`). Output
still flows through `ExternalHighlighting` → `HighlightInfo`. Delete
`DocumentCompiler.scala` for Scala 3.

**Run `pc` in process.** Ship `scala3-pc` as a library (similar to how
`scala-meta` is bundled). `PcHighlightingPass` calls `pc.diagnose(file)`
in-process, eliminating the compile-server hop and the
`DocumentCompilationArguments` serialization
(`DocumentCompiler.scala:157-177`). The pass reuses `VisibleRange` to scope
recomputation and `CompilationId`/`DocumentVersion` to discard stale results.

**Use TASTy-driven semantics.** Extend `tasty-reader` (§6.3) and feed
`ScalaColorSchemeAnnotator` and `ScalaSyntaxHighlightingVisitor` from the
semantic model. Delete the `intellij-compiler-plugin` round-trip
(`UpdateCompilerGeneratedStateListener.scala:64-70`, `CompilerType.scala`). At
this point `HighlightingAdvisor.isTypeAwareHighlightingEnabled`
(`HighlightingAdvisor.scala:30-54`) can always return `false` for Scala 3
files: every check that needs type info is either `pc`-driven or TASTy-driven.

**Remove obsolete semantic annotators.** Delete the semantic annotators identified in
§6.4. The 42-element catalog shrinks to roughly 15 syntactic annotators.
`ScalaAnnotator.annotate` loses its `typeAware` parameter for Scala 3 files.

**Recover from errors with BETASTy.** With `-Ybest-effort` producing `.betasty` under
errors, `PcHighlightingPass` can fall back to type info recovered from
`.betasty` (with `ERRORtype` placeholders) when `pc` itself fails to
typecheck a downstream module because an upstream module has errors. `pc`
parses the `.betasty` itself; the plugin's `tasty-reader` is not extended
to consume it.

---

## Summary

The plugin's "Built-in vs Compiler" split is already shaped like the proposed
"local-syntax vs `pc`-driven" split — the seam is the `typeAware` boolean in
`ScalaAnnotator.annotate` (`ScalaAnnotator.scala:50-52`). The compiler-driven
side already has an LSP-shaped `ExternalHighlighting` data type, an
`Action`/`WorkspaceEdit`/`TextEdit` triple, and a `Wolf` integration. The
`pc` redesign can plug into this seam directly; the main deletions are the
42-element semantic annotator catalog and the `DocumentCompiler`/compile-server
transport. The `tasty-reader` module, today a pure decompiler, becomes the
substrate for in-editor semantic information once extended with a
`readSemantic` entry point and (eventually) BETASTy support.
