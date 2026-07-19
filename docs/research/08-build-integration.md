# Build Integration, BSP, sbt, scala-cli — Domain Report

Scope: every "how does the IDE know what to compile, where the classpath is,
and when to recompile" pathway in the plugin today, and the seams a Metals-`pc`
Scala 3 implementation must replace. All file references are `path:line`.

> Module-layout note: the task brief refers to `scala/bsp/`,
> `scala/bsp-builtin/`, etc. In the current tree BSP is a **top-level**
> module: `bsp-builtin/bsp/`, `bsp-builtin/bsp-junit/`,
> `bsp-builtin/bsp-integration-tests/`, `bsp-builtin/bsp-terminal/`, plus a
> thin `bsp/` build sub-project wrapper (`bsp/target/`). The Scala side lives
> under `scala/compiler-shared/`, `scala/compile-server/`,
> `scala/compiler-integration/`, `scala/compiler-integration-server-management/`.
> sbt lives under top-level `sbt/sbt-*`, scala-cli under `scala-cli/`.

---

## 1. BSP modules

### 1.1 Top-level layout

| Module | Role |
|---|---|
| `bsp-builtin/bsp/src/` | The whole BSP client: protocol adapters, session, project resolver, task runner, settings, project data services. |
| `bsp-builtin/bsp-junit/` | JUnit integration on top of BSP (`BspFetchEnvironmentTaskProvider` etc.). |
| `bsp-builtin/bsp-integration-tests/` | Heavy integration tests: `SbtOverBspProjectStructureImportingTest.scala`, `SbtOverBspProjectHighlightingTest.scala`. |
| `bsp-builtin/bsp-terminal/` | Console / widget plumbing for the BSP tool window and process I/O. |
| `bsp/target/` | Sbt build sub-project only — no sources. |

### 1.2 Protocol stack

The plugin ships its own bsp4j-based stack on top of `org.eclipse.lsp4j.jsonrpc`:

- **`BspCommunication`** — `bsp-builtin/bsp/src/org/jetbrains/bsp/protocol/BspCommunication.scala:36`.
  One per `(workspace, BspServerConfig)`. Caches a single `BspSession`, retries
  connection-file regeneration (`BspCommunication.scala:95-112`), and exposes
  `run(task, …)` (`:259`) as the single entry point for any BSP request.
- **`BspCommunicationService`** — `bsp-builtin/bsp/src/org/jetbrains/bsp/protocol/BspCommunicationService.scala:21`.
  Application service; `TrieMap[(URI, BspServerConfig), BspCommunication]`
  (`:35`) with a 10-minute idle reaper (`:32`, `:42-49`).
- **`BspSession`** — `bsp-builtin/bsp/src/org/jetbrains/bsp/protocol/session/BspSession.scala:34`.
  Owns the LSP4J `Launcher<BspServer>` (`:162-195`), a single-threaded job
  queue (`:51`, `:68-69`), the `BspClient` callback implementation
  (`BspSessionClient`, `:338-390`) and the trace logger (`bspTraceLogger`,
  `:143-159`, gated by `BSP_TRACE_PATH`).
- **`BspServer` trait** — `BspSession.scala:421-424`: composes
  `bsp4j.BuildServer + ScalaBuildServer + JavaBuildServer + JvmBuildServer`.
  This is the *only* extension surface today; no `PresentationCompilerServer`
  mix-in exists.
- **`BspNotifications`** — `bsp-builtin/bsp/src/org/jetbrains/bsp/protocol/BspNotifications.scala:5-16`:
  `LogMessage`, `ShowMessage`, `PublishDiagnostics`, `TaskStart/Progress/Finish`,
  `DidChangeBuildTarget`. No semanticdb/tasty notification type yet.
- **`BspServerConnector` / `GenericConnector`** —
  `bsp-builtin/bsp/src/org/jetbrains/bsp/protocol/session/bspConnector.scala:14`,
  `:33`. The connector parses `.bsp/*.json` `BspConnectionDetails` files
  (`BspConnectionConfig.workspaceBspConfigs`, `BspConnectionConfig.scala:43`),
  spawns the server as an OS process (`GenericConnector.scala:34-61`), and
  constructs `InitializeBuildParams` with BSP version `"2.0"`
  (`bspConnector.scala:26`). Capabilities advertised are hard-coded to
  `List("scala","java")` (`BspCommunication.scala:154`).

### 1.3 Endpoints actually consumed

From `BspProjectResolver.targetData` (`BspProjectResolver.scala:312-369`) and
the test/run paths:

| BSP endpoint | Where called |
|---|---|
| `workspace/buildTargets` | `BspProjectResolver.scala:102` |
| `workspace/reload` | `BspProjectResolver.scala:94` (gated by `capabilities.getCanReload`) |
| `buildTarget/sources` | `BspProjectResolver.scala:321`, `BspJvmEnvironment.scala:215` |
| `buildTarget/dependencySources` | `BspProjectResolver.scala:330` (gated by `isDependencySourcesProvider`) |
| `buildTarget/resources` | `BspProjectResolver.scala:343` |
| `buildTarget/outputPaths` | `BspProjectResolver.scala:356` |
| `buildTarget/scalacOptions` | `BspProjectResolver.scala:384` |
| `buildTarget/javacOptions` | `BspProjectResolver.scala:406` |
| `buildTarget/compile` | `BspTask.compileRequest`, `bsp/…/project/BspTask.scala:229` |
| `buildTarget/cleanCache` | `BspTask.scala:220` |
| `buildTarget/jvmRunEnvironment` | `BspJvmEnvironment.scala:193` |
| `buildTarget/jvmTestEnvironment` | `BspJvmEnvironment.scala:199` |
| `buildTarget/test` | `BspTestRunner.scala:84` |
| `buildTarget/scalaTestClasses` | `FetchScalaTestClassesTask.scala:85` |

That is the full set: import-time discovery, compile, clean, run, test. There
is **no** `buildTarget/presentationCompiler`, no semanticdb or tasty
notification, no per-file virtual-source push. Everything goes via the
standard 2.0 protocol.

### 1.4 Server start/connect today

`BspCommunication.prepareSession` (`BspCommunication.scala:150-187`) decides
how to launch the server:

1. **`AutoConfig`** — read `.bsp/*.json` (`BspConnectionConfig.scala:21`);
   if any present, use `GenericConnector` (`:165`); else if `.bloop/` exists,
   launch Bloop via `BloopLocalLauncherConnector` or
   `BloopRemoteLauncherConnector` for the EEL-remote case (`:167`,
   `BloopLocalLauncherConnector.scala`).
2. **`BloopConfig`** — force Bloop.
3. **`BspConfigFile(path)`** — explicit user-provided file.

Scala CLI and Mill projects run a *pre-import* step that calls
`scala-cli setup-ide .` / `mill mill.bsp.BSP/install` to generate the
`.bsp/*.json` connection file before the session starts
(`scala-cli/src/org/jetbrains/scalaCli/project/importing/ScalaCliConfigSetup.scala:24-30`,
`bsp/…/project/importing/setup/MillConfigSetup.scala:15`).

`sbt` projects can be imported either through the native sbt resolver
(`sbt/sbt-impl/…/SbtProjectResolver.scala`) or **via BSP** through the
`SbtOverBsp` flow that runs `sbt -bsp` (see test class
`bsp-integration-tests/test/…/SbtOverBspProjectStructureImportingTest.scala`).
Both flows coexist; for Docker-remote projects the BSP path is now mandatory
(`projectImport.scala:364-378`).

---

## 2. sbt integration

### 2.1 Native (non-BSP) sbt import

`sbt/sbt-impl/src/org/jetbrains/sbt/project/SbtProjectResolver.scala:64` is a
classic IntelliJ `ExternalSystemProjectResolver`. It:

- launches an sbt process via `SbtRunner` (`sbt/sbt-impl/…/process/SbtRunner.scala`),
- runs an sbt-side `sbt-structure` dumper (`sbt/sbt-impl/…/project/structure/SbtStructureDumper.scala`),
- parses the dumped XML into IntelliJ `DataNode`s
  (`SbtProjectResolver.scala:70-100`),
- emits per-module `ScalaSdkData`, libraries, source roots, content roots.

Settings plumbing: `SbtExternalSystemManager.executionSettingsFor`
(`sbt/sbt-impl/…/project/SbtExternalSystemManager.scala:86-134`) constructs
`SbtExecutionSettings` carrying JDK, VM options, sbt options, launcher,
`useShellForImport`, etc. `SbtTaskManager` (`SbtTaskManager.scala:10`) is
deliberately a no-op — task execution actually goes through the **sbt shell**
(`sbt-shell-runtime-tests/`, `SbtExternalSystemUtil`).

### 2.2 What sbt integration does that BSP doesn't

- **Live shell** for `run`/`test`/arbitrary tasks (`sbt/sbt-impl/…/shell/`).
  BSP only handles compile + test execution.
- **Source structure dumping with metadata** — produces richer module
  information (sbt version, cross-build data, `Structure` data kinds) than BSP
  exposes today.
- **Settings UI**: `SbtExternalSystemConfigurable`, "use sbt shell for
  import", "download sources/Docs", etc.
- **Auto-import awareness** via `AutoImportAwareness`
  (`SbtExternalSystemManager.scala:34`).
- **Mock-process support for tests**: `sbt/sbt-mock-process/src/MockSbtProcess.java`
  plus `sbt-shell-runtime-tests` for end-to-end shell behaviour without real sbt.

### 2.3 What BSP provides that native sbt doesn't

- **A standardised wire protocol** (bsp4j) usable from any build tool.
- **Cross-tool on-save build loop** (`BspBuildLoopService.scala:24`).
- **Remote (EEL/Docker) support** — `GenericConnector.scala:39-50` handles
  `OSProcessHandler.processCanBeKilledByOS` and Ijent-remote processes.
- **Standard compile/diagnostics streaming** — `buildTargetCompile` returns
  per-task `taskStart/Progress/Finish` plus `publishDiagnostics`
  (`BspTask.scala:223-310`).

### 2.4 Project import model summary

Both native sbt and BSP resolve to the **same** IntelliJ external-system
abstractions: `DataNode<ProjectData>` → `DataNode<ModuleData>` with attached
`ScalaSdkData`, `BspMetadata`, etc. (`bsp/…/data/dataObjects.scala:201-244`).
That means a `Module` always carries the BSP target URI(s) on the side via
`BspMetadata.targetIds` (`dataObjects.scala:230-242`), retrievable through
`BspMetadata.get(project, module)` (`:247-267`).

---

## 3. scala-cli integration

`scala-cli/src/main/` is small and reuses BSP plumbing:

- **`ScalaCliConfigSetup`** (`scala-cli/src/org/jetbrains/scalaCli/project/importing/ScalaCliConfigSetup.scala:12`)
  extends `CommandBasedBspConfigSetup`. Its `installCommand` returns
  `scala-cli setup-ide .` (`:24-30`). The same code path supports
  Scala-3.5.0+ bundled Scala CLI through `ScalaCliUtils.detectScalaCliInstallKind`
  (`ScalaCliUtils.scala:55-69`).
- **`ScalaCliConfigSetupProvider`** is registered as a `BspSetupProvider`
  extension (sister to `MillSetupProvider` at
  `bsp/…/project/importing/setup/MillSetupProvider.scala:11`).
- **Detection** (`ScalaCliUtils.scala:127-151`): bundled "Scala CLI" is
  considered available when `scala -version` reports ≥ 3.5.0. Standalone
  `scala-cli` is checked via `BspUtil.isToolInstalledCheckViaVersion`
  (`:71-72`).
- **Project model** is *not* scala-cli-specific. Scala CLI emits BSP
  `WorkspaceBuildTargets` and the generic `BspProjectResolver`
  (`BspProjectResolver.scala:38`) does the rest. The plugin uses
  `BspUtil.isBspScalaCliProject` (e.g. `BspExternalSystemManager.scala:83`) to
  opt into scala-cli-specific behaviour such as regenerating the BSP config
  before server startup (`BspCommunication.scala:209-215`).

So scala-cli already speaks pure BSP — it is the cleanest existing example of
the architecture the rest of this report recommends.

---

## 4. Incremental compilation today

There are **three** distinct notions of "incremental" in the plugin:

### 4.1 Editor-side visible-range highlighting

`scala/scala-impl/src/org/jetbrains/plugins/scala/incremental/` — purely a UI
optimisation, no compilation:

- `Highlighting.scala:13` toggles "incremental highlighting" mode.
- `Updater.scala:19-62` schedules a 200 ms timer (`UPDATE_DELAY`, `:65`) on
  visible-area / folding / scroll events.
- `VisibleRange` decides what subset of error stripe marks to render
  (`Updater.scala:36-57`).
- `Listener.scala:44-94` wires VFS, folding, and key listeners to a per-editor
  `Updater`.

This layer decides **what part of the file** gets markers shown. It is
orthogonal to where the markers come from.

### 4.2 Compile-server-based incrementality (JPS / `sbt.inc` / Zinc)

For `Make`/`Build`/`Compile` invocations the plugin uses JPS, which routes
through the Scala compile server. The server picks one of two
implementations in
`scala/compile-server/src/org/jetbrains/jps/incremental/scala/local/CompilerFactoryImpl.scala:18-66`:

- **`IncrementalityType.SBT`** → `SbtCompiler` (full Zinc: `sbt.internal.inc`,
  `AnalyzingCompiler`, real incremental analysis).
- **`IncrementalityType.IDEA`** → `IdeaIncrementalCompiler`
  (`IdeaIncrementalCompiler.scala:16`) — wraps `AnalyzingCompiler` with
  `emptyChanges`, i.e. "compile this file fresh without reusing prior
  analysis". This is what per-document highlighting uses.

`DocumentCompilationData` (used in `DocumentCompiler.scala:153-177`) ships
the source content of a single file as a `StringVirtualFile`
(`IdeaIncrementalCompiler.scala:61`), bypassing the filesystem entirely.

### 4.3 Per-keystroke / per-document compiler

`scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/`
contains the orchestrator that turns document edits into compiler runs:

- **`CompilerHighlightingService`** (`CompilerHighlightingService.scala:48`)
  is a project service. `triggerIncrementalCompilation` (`:105`) and
  `triggerDocumentCompilation` (`:125`) enqueue `CompilationRequest`s into a
  `ConcurrentSkipListSet` (`:58`).
- **Three request types** (`CompilationRequest.scala`, dispatched at `:159-166`):
  - `WorksheetRequest` — full worksheet run.
  - `IncrementalRequest` — runs `IncrementalCompiler.compile`
    (`IncrementalCompiler.scala:20`) over modules; **delegates to BSP** if
    `BspUtil.isBspProject(project)` (`CompilerHighlightingService.scala:215`).
  - `DocumentRequest` — single-file compile via `DocumentCompiler`
    (`DocumentCompiler.scala:30`).
- **`doBspIncrementalCompilation`** (`CompilerHighlightingService.scala:234-263`)
  bridges into `BspProjectTaskRunner.run` — so for BSP projects, every
  highlighting cycle is a `buildTarget/compile` call. After it succeeds,
  `DocumentCompiler` is allowed to take over per-file (`enableDocumentCompiler`,
  `:286-292`).
- **`DocumentCompiler`** ships the in-memory source to the compile server
  (`DocumentCompiler.scala:104-177`), injecting `-Xplugin:intellij-compiler-plugin`
  for ≥ Scala 2.12 / Scala 3.3 (`:184-205`) and
  `-Ystop-after:repeatableAnnotations` for Scala 3 (`:212`) so no `.class` is
  written.

### 4.4 `compiler-shared` value types

Three small serialisable types form the cross-process identity model:

- **`CompilationId`** (`scala/compiler-shared/src/org/jetbrains/plugins/scala/util/CompilationId.scala:3`)
  = `(timestamp, documentVersions: Map[CanonicalPath, Long])`. This is the
  *content hash* sent to the compile server so it can decide whether an
  in-flight compile is still valid for what the editor currently shows.
- **`DocumentVersion`** (`util/DocumentVersion.scala:3`) = `(path, version)`.
- **`CanonicalPath`** (`util/CanonicalPath.scala:3`) wraps a canonicalised
  string so the map key is stable across OS / EEL-local / EEL-remote.

`DocumentUtil.stillValid(documentVersions)`
(`CompilerHighlightingService.scala:255, 276`) is the staleness check the
service uses to drop results from an outdated `BspProjectTaskRunner` run
before letting `DocumentCompiler` take over.

---

## 5. Seam recommendations

### 5.1 Vision: a single Scala 3 build pipeline driven by BSP + `pc`

The Metals `Interactive` driver wants to live in the same process as the
build system that knows the classpath. Today IntelliJ's architecture splits
this across three places:

| Concern | Current home | Proposed home |
|---|---|---|
| Classpath / scalac options | IntelliJ module settings (`ScalaCompilerSettingsStateBuilder`) | BSP `buildTarget/scalacOptions` + `buildTarget/sources` |
| Diagnostics on save | Compile server (`IdeaIncrementalCompiler`) **or** BSP `buildTarget/compile` (Bloop/sbt) | BSP `buildTarget/compile` only |
| Per-keystroke diagnostics | `DocumentCompiler` → compile server, single file, `emptyChanges` | `pc.Interactive` inside the BSP server |
| Completions / hover / goto | None (IntelliJ PSI only) | A new BSP extension, e.g. `buildTarget/presentationCompiler` |
| SemanticDB / TASTy | None | Read from `target/` after compile, or via custom BSP notification |

The key idea: **let the BSP server own `pc`**. Bloop already embeds Scala 3
for compile; we extend it (or run a sibling process) to also expose
`pc`-style JSON-RPC. IntelliJ keeps its single `BspSession`
(`BspSession.scala:34`) and adds two new request types.

#### 5.1.1 Server-side: extend the BSP server, do not start a new one

Bloop is the natural host. Alternatives (a separate `pc` JVM per project,
mill-bsp, sbt-bsp) all duplicate classpath discovery. The proposed layering:

```
  IntelliJ ──bsp4j──▶ Bloop (compile + diagnostics)
             └──────▶ pc-over-BSP (completion, hover, signature help)
                          ↑ shares classpath & symbol table with Bloop
```

Concretely:

- Reuse the existing `BspServer` trait (`BspSession.scala:421`) by adding a
  Scala 3 `@psipcx` mix-in or a sibling `BspServer2` trait that includes
  Metals-style methods.
- Stream diagnostics via the **existing** `build/publishDiagnostics`
  notification (`BspNotifications.scala:11`, handled at `BspTask.scala:257-310`).
  No new wire format needed.
- Stream SemanticDB by (a) writing files into `target/` and reading them
  from the IDE side, **or** (b) adding a `build/publishSemanticDb`
  notification analogous to `publishDiagnostics`. The compile-server already
  knows how to add `-Xsemanticdb` for Scala 3 (`compiler-jps/…/data/CompilerDataFactory.scala:153-174`),
  so option (a) is the smaller change.
- TASTy / BETASTy files are already on disk under `target/`; the IDE's
  `scala/tasty-reader/` (see report `06-tasty-reader.md`) can read them
  directly. No new transport needed.

#### 5.1.2 Client-side: IntelliJ keeps driving the loop

The orchestration in `CompilerHighlightingService.scala:48` should remain.
What changes is the body of `doBspIncrementalCompilation`
(`CompilerHighlightingService.scala:234-263`) and `DocumentCompiler.compile`
(`DocumentCompiler.scala:46`):

- For Scala 3 modules, `triggerDocumentCompilation` should route to a
  `PcClient` that talks to the BSP server's `pc` extension instead of
  spinning up a single-file `IdeaIncrementalCompiler` job.
- For Scala 2 modules, today's compile-server path is preserved unchanged.

### 5.2 Module → Build Target Identifier

IntelliJ `Module`s are already mapped to BSP targets via
`BspMetadata.targetIds` (`dataObjects.scala:230-242`). `BspMetadata.get`
(`dataObjects.scala:247-267`) walks the external-system data-node graph and
returns the list of `BuildTargetIdentifier` URIs for a module.

`BspProjectTaskRunner.run` (`BspProjectTaskRunner.scala:46-100`) already
implements the reverse lookup (Module → `BspTarget(workspaceUri, targetUri)`,
`:54-73`). The proposed pc client reuses exactly this mapping.

For non-BSP projects (native sbt imports, plain IDEA modules), there is no
`BspMetadata`. Recommendation: **always import through BSP** for Scala 3
modules, so the mapping exists. The native sbt path remains for Scala 2 /
legacy projects.

### 5.3 Shipping classpath and scalac options

Today `ScalaCompilerSettingsStateBuilder.getOptionsAsStrings`
(`scala/compiler-shared/src/org/jetbrains/plugins/scala/compiler/data/ScalaCompilerSettingsStateBuilder.scala:46-65`)
builds the scalac option list from per-module settings, then
`RemoteServerConnectorBase` ships them to the compile server
(`compiler-integration/src/…/compiler/RemoteServerConnectorBase.scala:39`).

In a BSP-driven world, **the server already has these** via
`buildTarget/scalacOptions` (`BspProjectResolver.scala:384`). The IDE's
job shrinks to:

1. For "what to display": read `ScalaBuildTarget` from `BspResolverLogic`
   (already populates `ScalaSdkData`, see `dataObjects.scala:201-224`).
2. For "what to send to `pc`": nothing per-option — the BSP server holds the
   canonical state. The IDE only sends **per-file deltas** (unsaved buffer
   content) over the `pc` extension.

This eliminates the divergence between "options the editor thinks are in
effect" and "options the compiler actually used" — a long-standing source of
SCL bugs.

### 5.4 Trigger: per-keystroke (pc-style) vs. on-save (current)

Two regimes coexist today:

- **On-save / on-build**: `BspBuildLoopService.scala:24` polls VFS, debounces
  30 ms, calls `ProjectTaskManager.build(...)` (`BspBuildLoopService.scala:53-107`).
  Disabled unless `bspSettings.buildOnSave` (`settings.scala:32`).
- **Per-keystroke**: `CompilerHighlightingService` queues
  `DocumentRequest`s with a deadline (`CompilationRequest.compilationDeadline`).
  Today these go to the compile server's single-file compiler.

Proposed hybrid for Scala 3:

| Trigger | Action | Latency budget |
|---|---|---|
| Keystroke | `pc.completion` / `pc.hover` against in-memory buffer | < 50 ms |
| Document saved / focus lost | `buildTarget/compile` (BSP) → diagnostics refresh | seconds |
| Build action | `buildTarget/compile` with all targets | seconds |
| Visible-area scroll | `Updater.scala:36` (no compiler call) | 200 ms |

The `CompilationId`/`DocumentVersion` trio
(`compiler-shared/…/util/{CompilationId,DocumentVersion,CanonicalPath}.scala`)
is exactly what `pc` needs to correlate responses with stale requests — keep
it as the cancellation token in the new `pc` client.

### 5.5 What disappears

In the proposed split:

| Component | Status |
|---|---|
| `scala/compile-server/` (nailgun + JPS) | Stays for Scala 2; bypassed for Scala 3 |
| `IdeaIncrementalCompiler` (`compile-server/…/local/IdeaIncrementalCompiler.scala:16`) | Effectively unused for Scala 3 (replaced by `pc`) |
| `DocumentCompiler` physical/in-memory file dance (`DocumentCompiler.scala:76-177`) | Replaced by `pc` JSON-RPC, no temp files |
| `compiler-plugin` (`scala/compiler-plugin/`, the `-Xplugin:intellij-compiler-plugin` that prints `<type>…</type>`) | Becomes redundant once `pc` provides types directly (see report `05-macros.md`) |

---

## 6. Bazel / Mill / Gradle

The plugin already supports several build systems through three distinct
channels:

| Build tool | Channel | Key file |
|---|---|---|
| sbt | Native resolver **and** BSP | `sbt/sbt-impl/…/SbtProjectResolver.scala:64` |
| Scala CLI | BSP only (via `setup-ide`) | `scala-cli/…/ScalaCliConfigSetup.scala:12` |
| Mill | BSP only | `bsp/…/project/importing/setup/MillConfigSetup.scala:15` |
| Gradle | Gradle plugin (separate) | `scala/integration/gradle/src/…/ScalaGradleProjectResolverExtension.scala` |
| Bazel | Bazel plugin (separate) | `scala/integration/intellij-bazel/src/…/*.scala` |

Gradle and Bazel integrations are **separate IntelliJ plugins** that hook
Scala through extension points (see report `10-extension-points.md`). They
do not currently feed BSP, and they each re-implement module configuration
(`ScalaGradleDataService`, `BazelModuleHandler`).

A BSP-first design unifies all five tools: anything that can emit
`WorkspaceBuildTargets` + `buildTarget/scalacOptions` plugs into the same
IntelliJ-side pipeline. The Gradle and Bazel plugins would gain a
"delegate to BSP" mode where the build tool's own BSP server (Gradle BPG,
Bazel BSP) is the source of truth, and the Scala plugin becomes a pure BSP
client. This is already how scala-cli and Mill work today.

The Fastpass/legacy code under
`bsp/…/project/importing/setup/FastpassConfigSetup.scala:23` is being
retired (SCL-24892) — it is not part of the recommended future.

---

## 7. Compile-on-visible vs. compile-on-edit

The plugin today does **neither** in the LSP sense; it does:

1. **Compile-on-edit-with-debounce** for diagnostics via
   `CompilerHighlightingService.triggerDocumentCompilation`
   (`CompilerHighlightingService.scala:125-135`).
2. **Render-on-visible** for already-computed highlighters via
   `Updater.scala:36-57` — purely visual, no compiler call.

The Metals `pc` model is closer to **compile-on-edit-streaming**: the
editor sends a `textDocument/didChange`-equivalent and the server updates
its in-memory compiler state incrementally without rebuilding. Adopting
this means:

- Replace the 200 ms `Updater.scala:65` timer with a debounced
  `pc/didChange` notification that *does* cause server-side work — but only
  for the affected compilation unit.
- Keep `Updater` purely as a **viewport filter** on the highlighters the
  server pushes back. This is exactly its current role (`Listener.scala:44-59`).

The hybrid rule: **compile-on-edit** for the open file (cheap because `pc`
re-uses its `Interactive` context), **compile-on-save** for transitive
diagnostics from upstream modules (`BspBuildLoopService`).

---

## 8. Current Scala 3 limitations in the build path

Scattered through the code:

- **Compiler-bridge jar selection by minor version**
  (`CompilerFactoryImpl.getOrCompileInterfaceJar`,
  `compile-server/…/local/CompilerFactoryImpl.scala:159-207`): hard-coded
  branches for 3.0/3.1/3.2/3.3/3.4. Each new Scala 3 minor requires a code
  change. Adopting `pc` removes the bridge jar entirely for Scala 3 — `pc`
  uses the real `dotty` compiler.
- **Classloader hack for Scala 3** (`CompilerFactoryImpl.scala:117-128`):
  a custom `DualLoader` is needed because Scala 3 moved to
  `xsbti.CompilerInterface2`. With `pc`, the build tool embeds its own
  Scala 3 instance and the IDE never loads it directly.
- **`-Ystop-after:repeatableAnnotations`**
  (`DocumentCompiler.scala:212`): a workaround to prevent `.class`/`.tasty`
  emission during single-file compiles. `pc` does not write `.class` files
  at all.
- **Scala 3 doc jars omitted from `ScalaBuildTarget.jars`**
  (`bsp/…/data/ScalaSdkService.scala:56-58`): a documented BSP spec gap
  (`build-server-protocol/build-server-protocol#229`). The plugin works
  around it by skipping scaladoc classpath entirely.
- **No SemanticDB integration for code insights** beyond the test-only
  `resolveSemanticDb/` suite
  (`scala/scala-impl/test/org/jetbrains/plugins/scala/lang/resolveSemanticDb/`).
  Production never consumes `.semanticdb` files even though `-Xsemanticdb`
  is supported on the compile path
  (`compiler-jps/…/data/CompilerDataFactory.scala:153`).
- **Per-keystroke compiler-types injection** relies on the intellij
  compiler-plugin (`compiler-plugin/scala-3.3/`), which prints types into
  compiler stderr to be parsed by `UpdateCompilerGeneratedStateListener`.
  This is brittle (see reports `03-highlighting-annotators.md`,
  `05-macros.md`).
- **`BspTaskManager.cancelTask` always returns `false`**
  (`bsp/…/project/BspTaskManager.scala:9`): BSP task cancellation is not
  actually wired through `ExternalSystemTaskManager`; only the
  `BspCommunication.cancelSessionCreation()` path
  (`BspCommunication.scala:288-289`) handles pre-start cancellation.
- **Compile-server restart storm on settings change**: any of seven
  "restart reasons" (`CompileServerLauncher.scala:720-743`) kills the
  nailgun JVM. With a long-lived `pc` inside the BSP server, only the
  build-tool-side state matters and the IDE side becomes stateless about
  compiler internals.

---

## 9. Migration sequence (suggested)

1. **Add `BspServer` extension for `pc`.** Define
   `buildTarget/presentationCompiler { completion, hover, definition, … }`
   as a custom method. Server side: prototype in Bloop first.
2. **Wire `BspMetadata.get` → `BuildTargetIdentifier`** as the request
   routing key in a new `PcClient` IDE service. Reuse
   `BspProjectTaskRunner`'s module→target mapping (`BspProjectTaskRunner.scala:54-73`).
3. **Replace `DocumentCompiler` for Scala 3 modules** with `pc` calls,
   guarded by `module.scalaLanguageLevel.exists(_ >= ScalaLanguageLevel.Scala_3_3)`
   (the same guard already used at `DocumentCompiler.scala:223`).
4. **Move SemanticDB consumption** from tests into production: read
   `target/**.semanticdb` after each successful `buildTarget/compile`,
   using the same `BspProjectResolver.scala:356` `outputPaths` result to
   locate the directory.
5. **Deprecate the intellij compiler-plugin path** for Scala 3 once `pc`
   types are wired into `ScExpression.psiUserData` instead.
6. **Unify Gradle / Bazel** through BSP by delegating to their respective
   BSP servers instead of the bespoke data services in
   `scala/integration/gradle/` and `scala/integration/intellij-bazel/`.

Each step is independently shippable behind a registry flag. The end state
keeps today's BSP plumbing (`BspCommunication`, `BspSession`,
`BspProjectResolver`) and only replaces the per-file compiler hop with a
JSON-RPC stream to a `pc` instance that lives where the classpath already
is.
