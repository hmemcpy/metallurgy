# Metallurgy

A third-party IntelliJ plugin that replaces IntelliJ's Scala type backend with the real Scala 3 presentation compiler
through Scalameta's published `scala.meta.pc` interfaces while retaining the existing Scala PSI model.

- **Scope:** replace all IntelliJ Scala type resolution in active Scala 3 modules with the real Scala 3 compiler,
  driven through pc and best-effort compilation. Ordinary steady-state diagnostics remain owned by compiler-based
  highlighting unless a measured gap appears.
- **Canonical design:** [`docs/scala3-compiler-backend.md`](docs/scala3-compiler-backend.md).
  It is the sole normative architecture, terminology, and decision source. Everything under [`docs/archive/`](docs/archive/)
  is historical provenance only.
- **Status:** pre-alpha. Compiler-backed type resolution and completion are available.

## Discipline (non-negotiable)

- **"pc is never wrong."** A surprising result from the presentation compiler / dotc almost always means your snippet,
  needle, or assumption is wrong — not a `pc` limitation. Fix the test, don't blame the compiler.
- **The [scala/scala3](https://github.com/scala/scala3) repo is the source of truth for Scala language and compiler
  behaviour.** When something doesn't work where it seemingly should (a snippet that won't compile, a type that resolves
  unexpectedly, a macro that doesn't expand), check the upstream implementation, its tests (`tests/run`,
  `tests/run-macros`, `tests/pos`), and the issue tracker — against the **exact Scala version under test** — *before*
  stating a definitive answer or concluding it's a tooling gap. (Worked example: Scala 3 `MacroAnnotation` cannot add
  members visible to user code — "Can not see new definition in user written code" — confirmed against the upstream
  tests, so no tool can surface such members.)
- **The bundled [intellij-scala](https://github.com/JetBrains/intellij-scala) plugin is the canonical reference for
  IntelliJ / Scala-plugin APIs.** A local checkout lives at `~/git/intellij-scala`. Before writing an implementation,
  helper, or test fixture, search it for an existing pattern to mirror.
- **Adapt on the IntelliJ side.** Use published IntelliJ, Scala-plugin, and Scalameta PC interfaces first. Exhaust the
  available IntelliJ/Scala-plugin extension points for each semantic root; where they cannot implement the required
  contract, isolate a wrapper or reimplementation in the compatibility bridge. Structural access is preferred to raw
  reflection, which is permitted only inside that bridge and only after supported interfaces are exhausted. Compiler
  and plugin versions are artifact coordinates/diagnostics, not compatibility switches. Do not add bytecode
  fingerprints, unconditional implementation-class mappings, or version allowlists. No upstream Scala 3, Scalameta, or
  Scala-plugin change is a prerequisite.
- **No conversational or historical terms in source code** (comments or type names). Comments describe what the code
  *is*, present-tense — no ADR cross-references, issue numbers, SCL IDs, or journey language ("the refocus",
  "wide-net", "how we got here"). Decisions live in the canonical design document, not in code.
- **Run commands that can hang or spawn long-lived children through GNU `gtimeout`.** This includes `sbt`, Java/IntelliJ,
  builds, tests, and downloads. Use `/opt/homebrew/bin/gtimeout --kill-after=5s <limit> <command>` so the command's process
  group is terminated at the deadline and escalated to `SIGKILL` after five seconds. Never rely on a tool's output-yield
  deadline, a shell/Python alarm, or killing only the launcher process. If a command yields a session, retain and poll
  that session through its real exit. Choose the limit deliberately (normally 120s for focused builds/tests) and raise
  it only when the operation is known to require more time. Routine bounded commands such as `git`, `gh`, `rg`, and
  `sed` do not require a timeout. When piping a bounded command through `tail` or another formatter, enable shell
  `pipefail` so the formatter cannot mask a test failure or timeout exit.
- No `Thread.sleep` for timing in production code — use latches/futures.

## Build & test

Runtime is **JBR 25**; it must be `JAVA_HOME` for builds and tests:

```sh
JBR=~/.metallurgyPluginIC/sdk/261.26222.65/jbr/Contents/Home
/opt/homebrew/bin/gtimeout --kill-after=5s 120s \
  env JAVA_HOME="$JBR" PATH="$JBR/bin:$PATH" \
  sbt -batch -no-colors "scalafmtAll" "testOnly <fully.qualified.Test>"
```

- sbt **1.11.7**, plugin code **Scala 3.7.4**, the in-tree testkit backport (`testkit/`) is **Scala 2.13.16** to match
  the bundled plugin it mirrors. Target platform: IntelliJ **261.x** (`261.26222.65`), bundled Scala plugin **2026.1.20**.
- Aliases: `sbt fmt` (scalafmtAll), `sbt check` (scalafmtCheckAll, CI gate), `sbt runIDE` (dev IDEA with the plugin).
  Run `scalafmtAll` before every commit.
- **`-Xfatal-warnings` is on** — fix every warning. Common ones: unused imports; `var x = _` → `= uninitialized`
  (Scala 3.7); `ReadAction.compute(...)` deprecated → `runReadAction` with a typed `Computable`/`Runnable` (mind
  overload ambiguity); discarded non-`Unit` values → `val _ = …`.

## Architecture (data flow)

- **Gate:** `ModuleDetectionService.isActive(module)` = Scala 3 **and** user opt-in **and** CBH on. CBH is retained as a
  rollout failsafe, not because the replacement backend technically depends on it.
  Everything else is a hard no-op without it. Compiler versions never select behavior; optional facilities such as
  BETASTY are enabled only when discovered as capabilities. `ScalaPluginSemanticBridge.usesCompilerTypes(project)` reads the
  CBH settings. Exact PC artifact availability and optional facilities such as BETASTY are discovered independently.
- **Target engine:** `PcSessionManager` (per-module sessions) → exact compiler artifact in an isolated classloader →
  published Scalameta `PresentationCompiler` interface → bulk semantic snapshot. When no public PC operation exposes the
  required snapshot, a capability-probed compiler bridge may structurally read the retained driver and export only
  neutral DTOs. Queries are cached per `(fileUri, documentVersion)` and never run on the EDT.
- **Compatibility bridges:** compiler implementation access belongs only in `Scala3PcBridge`; Scala-plugin wrapping,
  replacement, and any private access belong only in `ScalaPluginSemanticBridge`. Consumers see one role-based,
  cache-only lookup interface and do not know which adapter supplied it. `StructuralScala3PcBridge` and
  `BundledCompilerBackendShim` are private implementations behind those interfaces, not consumer-visible surfaces.
- **Presentation (Feature 0):** `CompilerTypeRequestResolver` subscribes to the bundled `CompilerType` topic and fills
  the compiler-type slot. Note: the bundled *requests* the type only for transparent-inline calls during completion,
  then *reads* the slot for any expression — so this path is completion-triggered.
- **Semantic population:** `CompilerBackendPass` schedules one coalesced population per document generation and returns
  without waiting in the daemon read action. Cold compiler-artifact resolution uses a cancelable `Task.Backgroundable`
  queued on the EDT; compilation, mapping, and publication stay off the EDT. Publication restarts the affected file.
- **Inlay pass:** `PcTypeHintsPass` consumes only the current immutable compiler-backend snapshot. It renders inline type
  hints and writes the compiler-type slot on each value definition's initializer; it never initiates or waits for pc work.
- **Diagnostics (demoted to transient plumbing):** `PcDiagnosticSetCache` + `PcHighlightRenderer` + `PcHighlightInfoFilter`.
- **Completion:** `Scala3PcCompletionContributor` + `PcCompletionMerger` (merges pc items over the bundled's).

## Gotchas (load-bearing — these cost hours each)

- **`PcSession` snapshots are keyed by `(fileUri, documentVersion)`.** Give each test case a **unique URI**
  (`s"file:///Case$idx.scala"`) or they collide and silently reuse case 1's snapshot.
- **`configureByText` filename must have no spaces or dots** (URISyntaxException) — use `s"Case$idx.scala"`.
- **Single-character needles land on the wrong sub-tree.** Needle the *result val name*, not a one-char identifier.
- **`ScalaLightCodeInsightFixtureTestCase` requires a `getTestDataPath` override** (point it at `src/test/testdata`).
- **Scala-3-JUnit closure:** routing `runWithErrorsFromCompiler { … }` through a `test*` method makes JUnit reflect the
  by-name body as a test method — move it into a non-`test*` helper.
- **Engine/presentation tests assert the EXACT rendered type** (whitespace-normalized), not a substring — a substring
  check let `IntBox` satisfy the `"Int"` requirement. Add new cases the same way.
- **Feature flags per construct:** named tuples need `-language:experimental.namedTuples`; opaque types must be
  object-scoped *and* used outside for pc to show the alias. When a case fails, suspect the snippet/flags first (see
  "pc is never wrong") and confirm usage against `scala/scala3`.
- **MacroAnnotation cannot add user-visible members** (Scala 3 design restriction) — don't try to test or support it.

## Tests

- Engine/presentation: `PcTypeResolutionTest` (exact-match type resolution across ~32 constructs), `PcPresentationTest`
  (slot), `PcCompletionTest` (real completion), `PcTypeInlayHintsTest` (inlay + proactive slot fill). The
  `withSession` helper (fetch pc jars, build a one-off `PcSession`, run on a pooled thread) is the pattern for any new
  pc-engine test.
- Golden fixtures live under `src/test/testdata/feature/<feature>/<name>/` with `source.scala` +
  `expected.metallurgy-{on,off}.txt`, driven by `MetallurgyFixtureTestCase` / `OracleExecutor`.

## Agent skills

- **Issue tracker:** GitHub issues via `gh`. See `docs/agents/issue-tracker.md`.
- **Triage labels:** `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.
- **Domain docs:** use the canonical design document. See `docs/agents/domain.md`. The live work queue is epic **#50**.
