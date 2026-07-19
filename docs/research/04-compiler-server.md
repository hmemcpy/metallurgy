# Compiler Integration, Compile Server, Nailgun, JPS — Domain Report

Scope: every out-of-process and in-process compilation pathway the Scala plugin
currently uses, with an eye on what becomes redundant the moment Metals
`pc` is adopted for Scala 3. All file references are `path:line`.

---

## 1. Top-level module map

| Module | Process | Responsibility |
|---|---|---|
| `scala/compiler-shared/` | both (IDE + server) | Wire protocol shared by both sides: `Client` interface, `Event` ADT, command ids, socket chunking, port/token/properties/log helpers, and serializable DTOs (`Arguments`, `CompilerData`, `CompilationData`, `DocumentCompilationArguments`, `ComputeStampsArguments`, `ExpressionEvaluationArguments`). |
| `scala/compile-server/` | out-of-process (the nailgun JVM) | The actual compiler. `Server.scala` is the trait; `local/LocalServer.scala` dispatches to either `IdeaIncrementalCompiler` or `SbtCompiler` via `CompilerFactoryImpl`. `remote/Main.scala` is the nailgun Nail entry point. `remote/Jps.scala` runs a full JPS build *inside* the server JVM. `remote/EventGeneratingClient.scala` / `EncodingEventGeneratingClient.scala` turn compiler callbacks into base64-encoded serialized `Event`s written to the socket stdout. |
| `scala/compiler-jps/` | JPS build process (separate JVM, or in-server) | Plugs Scala into IntelliJ's JPS pipeline. `ScalaBuilderService.scala:9` registers `IdeaIncrementalBuilder`, `SbtBuilder`, `ScalaCompilerReferenceIndexBuilder`, etc. via SPI. `ScalaBuilder.scala:34` is the dispatch point: tries the remote compile server first, falls back to a `LocalServer` in-process on connection error (`ScalaBuilder.scala:55-62`). |
| `scala/jps/` | JPS build process | Tiny helper module: `Builder.scala` (compiler-reference index builder) and `ChunkExclusionService.scala`. |
| `scala/compiler-integration/` | IDE | The IDE-side pipeline. `CompileServerClient.scala`, `RemoteServerRunner.scala`, `RemoteServerConnectorBase.scala`, `CompilationProcess.scala` speak the wire protocol; `highlighting/*` (see §5) drives all per-keystroke highlighting through the server. |
| `scala/compiler-integration-server-management/` | IDE | Server *lifecycle*: starting/stopping the OS process, picking a JDK, port file, idle shutdown, widget, notifications (`CompileServerLauncher.scala`, `ServerInstance.scala`, `ProcessWatcher.scala`, `CompileServerShutdown.scala`, `CompileServerWidget.scala`). |
| `scala/compiler-integration-server-management-tests/` | IDE tests | Unit tests for `CompileServerToken` / `CompileServerPort` / `CompileServerLog` (e.g. `CompileServerTokenTest.scala`). |
| `scala/nailgun/` | out-of-process (entry point only) | `NailgunRunner.java` is what the IDE actually `java -cp`s. It sets up a custom `URLClassLoader` hierarchy, starts a Facebook `NGServer`, and registers `org.jetbrains.jps.incremental.scala.remote.Main` as the Nail behind the six aliases `compile`, `compute-stamps`, `compile-jps`, `compile-document`, `evaluate-expression`, `get-metrics` (`NailgunRunner.java:24-31`). `MainLightRunner.java` is a no-server variant used by worksheets (`MainLightRunner.java:12`). |
| `scala/compiler-plugin/` | runs inside scalac itself | An `-Xplugin:` Scala compiler plugin, cross-built for Scala 2.12, 2.13, and 3.3. The Scala 3 variant runs a `PluginPhase` after `TyperPhase` and prints types of transparent-inline trees via `report.echo` between `<type>…</type>` markers (`compiler-plugin/scala-3.3/src/CompilerPlugin.scala:31-54`). The IDE parses those markers (`UpdateCompilerGeneratedStateListener`) to inject "compiler types" onto `ScExpression` PSI elements. |

Note: there is no `scala/bsp/` module. BSP lives in `bsp-builtin/` (a top-level
IntelliJ module); see §6.

---

## 2. Compile Server lifecycle

### Startup

`CompileServerLauncher.ensureServerRunning(project)` in
`scala/compiler-integration-server-management/src/org/jetbrains/plugins/scala/compiler/CompileServerLauncher.scala:686`
is the single entry point. It is invoked from
`scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/CompilerHighlightingService.scala:351`
before every highlighting cycle and from the JPS path indirectly via
`ScalaBuilder.getServer`.

Inside, `start(project, jdk)` (`CompileServerLauncher.scala:99`) builds the
command line:

```
<jdk.executable> -cp <nailgunClasspath> [vm opts] [add-opens] [rt.jar opts]
    org.jetbrains.plugins.scala.nailgun.NailgunRunner <id> <classpath>
    <scalaCompileServerSystemDir> <jpsBuildSystemDir>
```

`compileServerJars` (`CompileServerLauncher.scala:491`) is the union of JPS
builders, scala-library, scala3-library, scala-reflect, nailgun,
compiler-shared, scala-jps, compile-server, compiler-jps, repl-interface, and
the sbt/zinc jars. The nailgun process prints
`NGServer <version> started on <addr>, port <N>` to stdout; the launcher's
`waitUntilNailgunServerIsReady` (`CompileServerLauncher.scala:756`) parses that
line, and the moment the port is known two things happen:

1. `CompileServerToken.generateAndWriteTokenFor(systemDir, port)` writes a
   fresh UUID to `<systemDir>/tokens/<port>` with POSIX `0600` permissions
   (`compiler-shared/src/org/jetbrains/plugins/scala/server/CompileServerToken.scala:27-55`).
2. `writePortFile(systemDir, port)` writes the port to
   `<systemDir>/port.txt` (`CompileServerLauncher.scala:877`).

The token file is the shared secret that allows only this IDE instance to talk
to this server. `CompileServerPort` (`compiler-shared/.../server/CompileServerPort.scala`)
distinguishes `Local(port)` from `Remote(local, remote)` for the EEL-remote
case where a tunnel is set up (`CompileServerLauncher.scala:262`).

### Token refresh / restart triggers

The token is regenerated **only on process restart**, not on each request —
`CompileServerToken` is write-once per `(systemDir, port)`. The IDE increments
effective identity by *restarting the server* whenever any of the following
"restart reasons" fire (`CompileServerLauncher.scala:720-743`):

- working directory changed,
- system directory changed,
- JDK changed,
- JVM parameters changed,
- `compiler.unified.ic.implementation` advanced setting toggled,
- incremental compiler type (`SBT` ↔ `IDEA`) changed,
- (for remote EEL) project close on `CompileServerShutdown.scala:25`.

Each request validates the token on the server side in
`Main.validateToken` (`scala/compile-server/src/org/jetbrains/jps/incremental/scala/remote/Main.scala:321-336`).
Mismatch aborts the request (but not the server).

### Sending requests and receiving diagnostics

IDE side (`scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/RemoteServerRunner.scala:31`)
builds a `CompilationProcess`, opens a `Socket` to
`InetAddress.getByName(null)` (=127.0.0.1) on `compileServerPort.forCommunication`,
and delegates to `RemoteResourceOwner.send`
(`scala/compiler-shared/src/org/jetbrains/jps/incremental/scala/remote/RemoteResourceOwner.scala:29`).
That method:

1. Writes Nailgun chunks: one `ARGUMENT` chunk per arg, a
   `WORKINGDIRECTORY` chunk, a `COMMAND` chunk (`createChunks` at line 110).
2. Reads response chunks in a loop (`handle` at line 54). `CHUNKTYPE_STDOUT`
   payloads are base64-decoded and deserialized into `Event` via
   `Event.fromBytes`; `ClientEventProcessor.process` dispatches to the
   appropriate `Client` method.

On the server side, the base64 encoding is done by
`EncodingEventGeneratingClient.eventHandler`
(`scala/compile-server/src/org/jetbrains/jps/incremental/scala/remote/EncodingEventGeneratingClient.scala:29`).
`EventGeneratingClient`
(`scala/compile-server/.../remote/EventGeneratingClient.scala:13`) wraps each
`Client` call (`message`, `progress`, `generated`, `compilationStart`, etc.)
into the corresponding `Event` case class from
`scala/compiler-shared/src/org/jetbrains/jps/incremental/scala/remote/Event.scala`.
The full event taxonomy: `MessageEvent`, `ProgressEvent`, `GeneratedEvent`,
`DeletedEvent`, `CompilationStartEvent`, `CompilationPhaseEvent`,
`CompilationUnitEvent`, `CompilationEndEvent`, `WorksheetOutputEvent`,
`MetricsEvent`, etc. — that is the entire IDE↔server data surface.

### Shutdown

Two paths:

1. **Idle timeout** — `shutdown.delay.seconds` system property; `Main.resetShutdownTimer`
   (`scala/compile-server/.../remote/Main.scala:350`) schedules a `TimerTask`
   that calls `server.shutdown()`. The shutdown prefix is detected by
   `ProcessWatcher.MyProcessListener` (`CompileServerLauncher`'s watcher,
   `scala/compiler-integration-server-management/src/org/jetbrains/plugins/scala/compiler/ProcessWatcher.scala:70`),
   which marks `_terminatedByIdleTimeout` and fires the "stopped by idle"
   notification.
2. **Explicit stop** — `CompileServerLauncher.stopServerAndWait` /
   `stopServerAndWaitFor` (`CompileServerLauncher.scala:348-396`) destroys the
   OS process and removes the token file.

`CompileServerShutdown.registerShutdownTask`
(`scala/compiler-integration-server-management/src/org/jetbrains/plugins/scala/compiler/CompileServerShutdown.scala:20`)
hooks both application quit and remote-project-close.

---

## 3. Nailgun

[Nailgun](https://github.com/facebook/nailgun) is a TCP server that lets you
invoke Java classes in an already-running JVM. The Scala plugin uses it for
two reasons:

1. **Warm JVM**: `scalac` and `dotc` JIT to a usable speed only after a few
   runs. Cold-starting a separate JVM per save would cost seconds per file;
   nailgun keeps one process alive across the IDE session.
2. **Cheap classloader isolation**: `NailgunRunner.constructClassLoader`
   (`scala/nailgun/src/org/jetbrains/plugins/scala/nailgun/NailgunRunner.java:98-120`)
   splits `repl-interface.jar` into its own loader whose parent is the
   nailgun loader, so the server can spawn per-REPL loaders with arbitrary
   Scala versions without colliding with the server's fixed Scala 2.13 stdlib
   (full diagram in the javadoc at lines 76-95). The same pattern is the
   natural place to put a future `pc`/`dotc` child loader (§6).

The IDE launches nailgun exactly once per "server instance"
(`CompileServerLauncher.start` at line 99 invokes
`NailgunRunner.main` with four positional args). After that, every command
(`compile`, `compile-jps`, `compile-document`, …) is just a Nailgun alias
registered to the same Nail class `org.jetbrains.jps.incremental.scala.remote.Main`
(`NailgunRunner.java:146-148`).

`Main.nailMain(NGContext)`
(`scala/compile-server/.../remote/Main.scala:96`) is invoked per request on a
fresh `NGSession` thread inside the server JVM. It cancels the idle-shutdown
timer, validates the token, and dispatches into `handleCommand`
(line 176) which switches on the `CompileServerCommand` ADT from
`scala/compiler-shared/src/org/jetbrains/jps/incremental/scala/remote/CompileServerCommand.scala:9-92`.

`MainLightRunner` (`scala/nailgun/.../MainLightRunner.java:14`) is the same
pipeline without a persistent NGServer — it loads the classloader, calls
`Main.main(args)` once, and exits. It is used by the in-process worksheet
runner (`org.jetbrains.plugins.scala.worksheet.server.NonServerRunner`).

---

## 4. JPS integration

JPS (JetBrains Project System) is the platform's incremental build engine.
The Scala plugin hooks into it from `scala/compiler-jps/`:

- `ScalaBuilderService` (`scala/compiler-jps/src/org/jetbrains/jps/incremental/scala/ScalaBuilderService.scala:9`)
  is registered as a JPS `BuilderService` (via `META-INF/services`). It
  contributes two `IdeaIncrementalBuilder` instances (one
  `SOURCE_PROCESSOR`, one `OVERWRITING_TRANSLATOR`, to handle
  `CompileOrder.Mixed` vs `JavaThenScala` — see `IdeaIncrementalBuilder.isEnabled`,
  line 97), an `SbtBuilder` (the zinc-driven path), a `ScalaCompilerReferenceIndexBuilder`
  (see §7), a `ScalaClassPostProcessorBuilder`, and a single `ZincResourceBuilder`.
- `IdeaIncrementalBuilder.build`
  (`scala/compiler-jps/src/org/jetbrains/jps/incremental/scala/IdeaIncrementalBuilder.scala:26`)
  is the per-chunk entry point. It collects dirty sources from JPS, wraps
  them in an `IdeClientIdea`, and calls `ScalaBuilder.compile`.
- `ScalaBuilder.compile`
  (`scala/compiler-jps/src/org/jetbrains/jps/incremental/scala/ScalaBuilder.scala:34`)
  decides whether to use the remote compile server (port file readable,
  server flag on, not currently disabled) or fall back to a cached
  `LocalServer` in-process on connection error.

Two distinct JPS paths feed `ScalaBuilder`:

1. **Explicit Build action** (Build menu, Run, Make). IntelliJ's build
   pipeline spawns the platform's JPS build process; the Scala
   `BuilderService` is loaded there.
2. **IDE-side highlighting for non-BSP projects**. Instead of spawning a
   separate JPS process for every highlighting cycle, the IDE sends a
   `CompileServerCommand.CompileJps`
   (`scala/compiler-shared/.../remote/CompileServerCommand.scala:40-66`)
   through the nailgun socket. The server-side `Jps.compileJpsLogic`
   (`scala/compile-server/.../remote/Jps.scala:30`) **runs a JPS build
   inside the compile server JVM** using the platform's `BuildRunner` +
   `JpsModelLoaderImpl`, scoped by module names, with FS state explicitly
   saved back to disk so the IDE's JPS process sees consistent state
   (`Jps.saveFsState`, line 119). This is what
   `IncrementalCompiler.compile`
   (`scala/compiler-integration/.../highlighting/IncrementalCompiler.scala:52`)
   invokes through `CompileServerClient.execCommand`.

For BSP projects (`BspUtil.isBspProject(project)`), highlighting bypasses
the compile server entirely and runs through `BspProjectTaskRunner`
(`CompilerHighlightingService.doBspIncrementalCompilation` at line 234 of
`scala/compiler-integration/.../highlighting/CompilerHighlightingService.scala`;
BSP runner at `bsp-builtin/bsp/src/org/jetbrains/bsp/project/BspProjectTaskRunner.scala:46`).
This is already evidence that the IDE-JPS path is replaceable.

---

## 5. compiler-integration: the IDE-side highlighting pipeline

The pipeline lives in
`scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/`.
The classes named in the prompt (`CompileServerHighlighting*`,
`RemoteServerHighlighting*`, `HighlightingTask`) do not exist under those
exact names in the current tree; the active names are:

- **`CompilerHighlightingService`** (line 48) — the central scheduler. A
  `@Service(Level.PROJECT)` `Disposable` with two single-threaded
  `ScheduledExecutorService`s: one to run, one to show the progress
  indicator. Requests (`CompilationRequest.IncrementalRequest`,
  `.DocumentRequest`, `.WorksheetRequest`, defined in
  `CompilationRequest.scala`) land in a `ConcurrentSkipListSet` ordered by
  deadline (`CompilationRequest.compilationRequestOrdering`). The
  `CompilationTask` runnable (line 487) debounces / merges per-file
  requests.
- **`IncrementalCompiler`** (`highlighting/IncrementalCompiler.scala:18`) —
  sends `CompileServerCommand.CompileJps` via `CompileServerClient.get(project).execCommand`.
  This is the path that triggers a full JPS build inside the compile
  server.
- **`DocumentCompiler`** (`highlighting/DocumentCompiler.scala:30`) —
  invoked after `IncrementalCompiler` succeeds, per open editor. Writes the
  document content to a temp file under `workingDirectory()` (or uses an
  in-memory `StringVirtualFile` when the
  `scala.compiler.highlighting.document.use.in.memory.file` registry is on,
  line 53), then sends `CommandIds.Compile` or `CommandIds.CompileDocument`
  via a `RemoteServerConnectorBase` subclass. Scala-3-specific option
  injection lives in `AbstractRemoteServerConnector.scalaParameters`
  (line 184): it forces `-Ystop-after:repeatableAnnotations` (line 212) and
  `-Wunused:imports` (line 225), and adds the IDE's compiler-types plugin
  via `-Xplugin:` when "use compiler types" is on.
- **`CompilerEventGeneratingClient`** (`highlighting/CompilerEventGeneratingClient.scala:14`)
  — the IDE-side `Client` impl. Publishes `CompilerEvent.MessageEmitted` /
  `CompilationStarted` / `CompilationFinished` / `ProgressEmitted` on the
  project message bus via `CompilerEventListener.topic`. Cancellation is
  wired to the IntelliJ `ProgressIndicator`.
- **`ExternalHighlightersService`** (`highlighting/ExternalHighlightersService.scala:52`)
  — subscribes to those events, converts `ExternalHighlighting` into
  IntelliJ `HighlightInfo` (the heavy logic in `toHighlightInfo`, line 282),
  and applies them via `UpdateHighlightersUtil.setHighlightingsToEditor`
  (line 130) under pass id `ScalaCompilerPassId = 979132998`. Also injects
  "compiler types" onto `ScExpression` PSI elements from `ExternalTypes`
  (line 90).
- **`CompilerHighlightingPsiChangeListener`** (registered by
  `CompilerHighlightingSetupActivity.execute`, line 11) — triggers
  `triggerIncrementalCompilation` / `triggerDocumentCompilation` on PSI
  edits.
- **`CompilerHighlightingBuildManagerListener`**,
  **`CompilerHighlightingEditorFocusListener`**,
  **`CompilerHighlightingFileListener`**,
  **`CompilerHighlightingModuleRootListener`** — additional triggers.

The actual socket I/O on the IDE side is in `RemoteServerRunner.buildProcess`
(`scala/compiler-integration/.../RemoteServerRunner.scala:31`), which
constructs a `CompilationProcess` whose `run()` (line 41) reads the token
from disk, calls `RemoteResourceOwner.send`, and retries up to
`ConnectionRetryAttempts = 10` times on `ConnectException`.

---

## 6. Seam recommendations: replace with Metals `pc`

### Where to put `pc`

Two viable shapes, in increasing order of disruption:

**A. In-process via the existing compile-server.**
`pc` is just a library. The server already has all the machinery for
per-module classloader isolation:
`CompilerFactoryImpl.createScalaInstance`
(`scala/compile-server/.../local/CompilerFactoryImpl.scala:112`) creates
per-`CompilerJars` `URLClassLoader`s with a `DualLoader` that keeps
`xsbti.*` classes resolved to the server's own loader (lines 119-128).
The same pattern, with the boundary moved to "everything `dotty.*`,
`scala.runtime.*`, `scala.tasty.*`, and `scala.meta.internal.pc.*`", gives
a clean child loader for `pc` + `dotc` + scala3-library without leaking
into scala-impl's Scala 2.13 stdlib. `nailgun`'s `NailgunRunner.constructClassLoader`
already proves the pattern works (`scala/nailgun/.../NailgunRunner.java:98`).
Net effect: keep the nailgun server, keep the warm JVM, keep the
port/token/widget lifecycle unchanged; *replace* `Main.compileDocumentLogic`
and `LocalServer.compileDocument`
(`scala/compile-server/.../local/LocalServer.scala:86`) with a new
`PcServer` command that holds a long-lived `pc.InteractiveDriver` per
module URI.

**B. Out-of-process via BSP.** The plugin already ships BSP support
(`bsp-builtin/`). `BspProjectTaskRunner` is already wired into
`CompilerHighlightingService.doBspIncrementalCompilation`
(`scala/compiler-integration/.../highlighting/CompilerHighlightingService.scala:234`).
Bloop, sbt's BSP server, and Mill's BSP server all already embed `pc` for
Scala 3. Route the Scala 3 highlighting path through BSP *unconditionally*
(even when the project is technically not a BSP project) by registering a
synthetic " Scala 3 pc" build tool. The BSP server emits
`build/taskStart`/`build/taskFinish`/`build/publishDiagnostics`
notifications (consumed by `CompilerEventReporter` at
`bsp-builtin/bsp/src/org/jetbrains/plugins/scala/build/CompilerEventReporter.scala`),
plus a custom `build/scalac/options` response that carries `pc`-resolved
semantic data. Trade-off: clean isolation, but JSON-RPC overhead per
keystroke and one extra process to manage.

**Recommendation: A as the default, B as an opt-in.** A keeps latency
low (one socket round-trip per keystroke, no JSON encoding), reuses the
existing compile-server lifecycle, and is invisible to users.

### Concrete data flow

```
PSI edit ─▶ CompilerHighlightingService.triggerDocumentCompilation
         ─▶ DocumentCompiler.compile (existing)
         ─▶ RemoteServerRunner.buildProcess("pc-compile", …)  (new command id)
         ─▶ socket ─▶ Main.nailMain
         ─▶ PcServer.compileDocument(uri, content)
                  ├─ pc.InteractiveDriver#withCompilingUnit (runs typer + a few phases)
                  ├─  diagnostics        ─▶ MessageEvent (existing)
                  ├─  symbol occurrences ─▶ new SemanticEvent (TASTy / SemanticDB payload)
                  └─  hover / completions / type-info ─▶ new PcInfoEvent (lazy)
         ─▶ EventGeneratingClient ─▶ socket ─▶ ClientEventProcessor
         ─▶ CompilerEventGeneratingClient ─▶ message bus ─▶ ExternalHighlightersService
                                                            + new PcSemanticService
```

The wire protocol additions are small and additive: one new
`CompileServerCommand.PcCompile` case class in
`scala/compiler-shared/.../remote/CompileServerCommand.scala`, two new
`Event` variants in `Event.scala`. Nothing else in the IDE-side scheduler
or in `ExternalHighlightersService` needs to change.

### Per-module scalac options

`ScalaCompilerSettingsStateBuilder.getOptionsAsStrings`
(`scala/compiler-shared/.../ScalaCompilerSettingsStateBuilder.scala:46`)
already produces the per-module option list. `RemoteServerConnectorBase.scalaParameters`
(`scala/compiler-integration/.../RemoteServerConnectorBase.scala:38`) and
`DocumentCompiler.AbstractRemoteServerConnector.scalaParameters`
(`scala/compiler-integration/.../highlighting/DocumentCompiler.scala:184`)
already pass that list into the `Arguments`. The same list feeds
`pc.ScalaOptions` — no new plumbing.

### Cadence

`CompilerHighlightingService` is already debounced per-keystroke; the
scheduler at line 487 already merges and deduplicates. With `pc`'s
incremental driver, the `compile-jps` round-trip becomes unnecessary for
IDE-side checks (it remains for the Build menu). `pc` re-runs only the
affected phases of the affected unit, which makes the current
`-Ystop-after:repeatableAnnotations` hack in
`DocumentCompiler.scala:212` redundant.

---

## 7. Incremental compilation

### What exists today

`IncrementalityType` is a two-value enum
(`scala/compiler-shared/.../data/IncrementalityType.java`):

- **`SBT`** — zinc-driven, class-based invalidation + name-hashing. The
  full pipeline is in `SbtCompiler.doCompile`
  (`scala/compile-server/.../local/SbtCompiler.scala:20-126`): builds
  zinc's `IncrementalCompilerImpl`, wires `IntellijExternalLookup` /
  `IntelljExternalHooks` / `IntellijClassfileManager` /
  `IntellijEntryLookup` (all under
  `scala/compile-server/src/org/jetbrains/jps/incremental/scala/local/zinc/`),
  persists analysis via `AnalysisStoreFactory.createAnalysisStore(cacheFile)`.
- **`IDEA`** — skips zinc's incremental algorithm entirely. `IdeaIncrementalCompiler.compile`
  (`scala/compile-server/.../local/IdeaIncrementalCompiler.scala:19-56`)
  passes `emptyChanges` to `AnalyzingCompiler.compile`, meaning the
  compiler is re-invoked on the IDE-supplied dirty source set with no
  reuse of typed AST. This is the path used by `DocumentCompiler` for
  per-keystroke highlighting — i.e., the slowest possible mode.

### State sharing

The IDE and the server share incremental state only through:

1. The `cacheFile` path on disk (a zinc `AnalysisStore`).
2. `CompileServerCommand.ComputeStamps`
   (`scala/compiler-shared/.../remote/CompileServerCommand.scala:29`),
   handled by `LocalServer.computeStamps`
   (`scala/compile-server/.../local/LocalServer.scala:54-84`) — patches
   product stamps in the analysis after an external class-file change.
3. The `Jps.saveFsState` writeback (`scala/compile-server/.../remote/Jps.scala:119`),
   which forces the IDE's JPS process to re-check its FS state.

Otherwise each compile request carries the full source set and
`emptyChanges` — there is no in-memory incremental state reuse between
requests through the socket boundary.

### How `pc` changes this

`pc.InteractiveDriver` (Scala 3) keeps a per-URI `CompilationUnit` with
typed `Context` and invalidates only the affected units on edit. For
IDE-side checks this replaces:

- `SbtCompiler` (zinc setup, analysis store, external hooks),
- `IdeaIncrementalCompiler` (cold `AnalyzingCompiler` per request),
- `LocalServer.compileDocument`,
- the `CompileServerCommand.CompileDocument` and `ComputeStamps` commands,
- the temp-file round-trip in `DocumentCompiler.compilePhysicalFile`
  (`scala/compiler-integration/.../highlighting/DocumentCompiler.scala:76`),
- the `intellij-compiler-plugin` (`scala/compiler-plugin/scala-3.3/`) —
  `pc` produces types directly through its `MtagsIndexer` / `Scala3Tasty`
  interface, no `report.echo` hack needed.

JPS stays for explicit Build actions and for Scala 2. For Scala 3 IDE-side
checks, the only "incremental state" worth persisting is `pc`'s in-memory
`Context`, which lives in the long-lived nailgun JVM between requests —
exactly what `pc` is designed for.

The `ScalaCompilerReferenceIndexBuilder` (referenced from
`ScalaBuilderService.scala:19`) consumes `xsbti.AnalysisCallback` data,
which is per-build metadata; this stays for the JPS path but is not
needed for `pc`-driven checks.

---

## 8. Current Scala 3 limitations

The current compile server is fundamentally a Scala 2 design pressed into
Scala 3 service. Concrete deficiencies:

1. **No real `pc`-style incremental driver.** Every keystroke runs
   `IdeaIncrementalCompiler.compileDocument`
   (`scala/compile-server/.../local/IdeaIncrementalCompiler.scala:58`)
   from scratch: a fresh `AnalyzingCompiler.compile` with
   `emptyChanges = true` over a one-file source set. The compiler is
   re-loaded from the classloader cache (`CompilerFactoryImpl.scala:68`),
   but typed AST is never reused across keystrokes. Latency is dominated
   by parser+typer cold starts.
2. **No SemanticDB generation by default.** The `scalacOptions` passed by
   `RemoteServerConnectorBase.scalaParameters` come from the user's
   project settings; nothing injects `-Ysemanticdb`. The plugin has
   SemanticDB test infrastructure (`scala-impl/test/.../resolveSemanticDb/`)
   but it is for tests, not production. As a result, go-to-definition,
   find-usages, and refactorings still rely on the PSI, not on what the
   compiler actually resolved.
3. **Type info is collected by a side channel.** `compiler-plugin/scala-3.3/src/CompilerPlugin.scala:31`
   runs a `PluginPhase` after typer and prints types via `report.echo`
   between `<type>…</type>` markers, parsed by the IDE's
   `UpdateCompilerGeneratedStateListener`. This only covers
   transparent-inline trees; it is a poor substitute for `pc`'s
   `InteractiveDriver.symbolOf` / `typeOf`.
4. **Hard-coded phase cutoff.** `DocumentCompiler.AbstractRemoteServerConnector.scalaParameters`
   forces `-Ystop-after:repeatableAnnotations` for Scala 3
   (`scala/compiler-integration/.../highlighting/DocumentCompiler.scala:212`).
   This drops everything after typer, which is correct for "fast
   diagnostics" but blocks any future feature that needs picklers,
   inlining, or TASTy.
5. **Per-request plugin-jar transfer.** When "compiler types" is on,
   `scala-3.3/src/CompilerPlugin.scala` is transferred through EEL on
   every request (`DocumentCompiler.scala:194-205`), because the
   classpath isn't reused.
6. **Worksheet expression evaluation is a separate path.**
   `Main.evaluateExpressionLogic`
   (`scala/compile-server/.../remote/Main.scala:234-262`) reflectively
   loads `dotty.tools.debug.ExpressionCompilerBridge` per call, with its
   own `URLClassLoader` cache (`classLoaderCache` at line 232). `pc`
   already has an expression evaluator interface; consolidating would
   remove ~30 lines of reflective glue.
7. **JPS-in-server state is fragile.** `Jps.compileJpsLogic`
   (`scala/compile-server/.../remote/Jps.scala:30`) manipulates global
   `System.setProperty` for external project config under a lock (line
   168-203) because the platform API doesn't allow passing it cleanly.
   This is a source of cross-project interference bugs when one server
   serves multiple open projects.
8. **Diagnostics actions are limited.** `AbstractCompiler.ClientReporter.createDiagnostics`
   (`scala/compile-server/.../local/AbstractCompiler.scala:122-147`)
   only translates `xsbti.Problem.actions()` into `TextEdit`s. Scala 3's
   richer diagnostic codes, related-info, and code actions (available
   through `pc`) are partially lost in transit.

---

### Summary

For Scala 3 only, the in-server `pc` route (Option A) is the lowest-risk
replacement: it removes the `IdeaIncrementalCompiler` cold-recompile path,
eliminates the temp-file round-trip in `DocumentCompiler`, retires the
`intellij-compiler-plugin` `<type>` echo hack, and reuses the existing
nailgun server, port/token/widget lifecycle, and wire protocol with only
two additive changes (one new `CompileServerCommand`, two new `Event`s).
JPS stays for explicit builds; BSP stays for users who already have a
BSP server. Scala 2 keeps the existing `SbtCompiler`/`IdeaIncrementalCompiler`
unchanged.
