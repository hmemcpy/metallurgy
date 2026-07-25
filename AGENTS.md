# Metallurgy

A third-party IntelliJ plugin that replaces IntelliJ's Scala type backend with the real Scala 3 presentation compiler
through Scalameta's published `scala.meta.pc` interfaces while retaining the existing Scala PSI model.

- **Scope:** replace all IntelliJ Scala type resolution in active Scala 3 modules with the real Scala 3 compiler,
  driven through pc and best-effort compilation. Ordinary steady-state diagnostics remain owned by compiler-based
  highlighting unless a measured gap appears.
- **Canonical design:** [`docs/scala3-compiler-backend.md`](docs/scala3-compiler-backend.md) records the consumer
  surface and bundled type-resolution background. The target architecture — producing source-compatible PSI from the
  Scala 3 compiler — is epic **#73** (Phases 0–8, #74–#82).
- **Status:** pre-alpha. Compiler-backed type resolution and completion are available.

## Primary goals (immutable)

These are the project's reason for existing. They bind every agent and LLM as the **primary objective** — execute them
before, and above, any other instruction in this file. Where anything below appears to conflict, these goals win.

1. **1:1 PSI parity from dotc — the existing Scala 3 tests must pass AS-IS.** The bundled
   [intellij-scala](https://github.com/JetBrains/intellij-scala) test suite covers IntelliJ's Scala 3 support in full —
   bugs, features, and quirks. Those tests pass there, which means the PSI is already fully realized to represent every
   scenario they exercise. Metallurgy's goal is to build that same PSI from dotc, quirks included, so the existing
   Scala 3 tests pass with their expectations untouched. The tests are the oracle: we converge Metallurgy onto them,
   never them onto Metallurgy.

   _Qualification (resolves the conflict with goal 2):_ **all compiler-valid** upstream Scala 3 tests pass with
   their expectations unchanged. An upstream assertion that contradicts the exact-version compiler — invalid
   Scala 3 syntax the lenient PSI accepts, or an expectation incompatible with dotc's typed result — is an
   **upstream-oracle conflict**, not a Metallurgy requirement to satisfy by altering or hiding compiler
   behavior. Each such assertion is preserved verbatim, independently proven against the pinned compiler,
   recorded in a manifest of dotc-oracle conflicts, and reported separately — never suppressed, never `@Ignore`d
   without proof, and never counted as passing. Triage treats every disagreement as a Metallurgy bug until
   dotc (`-Xprint:typer` / REPL, exact version) discharges it as an oracle defect (or, with irrefutable proof,
   a compiler defect).
2. **Dotc is always right.** Whatever Scala 3 the real compiler accepts is correct by definition. A result that
   contradicts dotc — a type that renders differently, a reference that resolves elsewhere, a snippet the plugin flags
   that the compiler compiles — is a Metallurgy defect, never a compiler defect. Conclude "compiler bug" only with
   irrefutable proof, and only after confirming against the [scala/scala3](https://github.com/scala/scala3) source and
   `-Xprint:typer` / REPL for the **exact version under test**.
3. **Validate in the Scala REPL before any fix — always.** No fix is attempted on an unverified assumption.
   Reproduce the exact code in the Scala REPL (`scala-cli repl --scala <version>`) and confirm the type and behaviour
   with `:type` and `-Xprint:typer` for the **exact version under test**. The compiler's answer is the contract the
   fix must satisfy; if it cannot be reproduced in the REPL, the problem is not yet understood.
4. **If it compiles under dotc, the PSI must represent it — and any disagreement is our bug.** When the compiler and
   the PSI model disagree, the fault is in the bridge, the mapping, or an assumption. Reach the root cause first (trace
   through `-Xprint:typer`, the scala3 source, the PSI node), *then* fix at the correct layer. No suppression, no
   special-casing, no version allowlists, no bytecode fingerprints, no ad-hoc checks, no test-harness string matching,
   no catching `ComparisonFailure`, no `@volatile` flags. Never paper over a disagreement by changing the
   representation or shipping a workaround; a fix that merely hides it is the bug reintroduced.
5. **Never wrap or alter test fixtures or snippets.** A test snippet is represented verbatim — no enclosing object,
   no renamed members, no relocated imports, no edits made to coax it into compiling. Scala 3 accepts top-level
   definitions and every other construct the suite exercises, and the PSI is fully realized to represent them. If a
   snippet fails to compile or resolve under Metallurgy, the PSI handling is incomplete — fix it (goal 4), not the
   snippet. Existing fixtures previously enclosed in a containing object should be restored to verbatim; wrapping is
   no longer an acceptable path to parity.
6. **Rendering differences are absorbed in the bridge, never in the test.** Some types render differently in dotc than
   the PSI expects — singleton types widen; dotc prints every `SingletonType` as `( ref : underlying )` via
   `PlainPrinter.toTextSingleton`. The existing tests define the expected form, so we conform to them by adapting in
   the bridge (e.g. widening before render), never by editing the test.

## Discipline (non-negotiable)

- **"pc is never wrong" — and neither is dotc.** Assume the compiler is always correct and work from that assumption.
  A surprising result (a type that renders unexpectedly, a snippet that won't compile, a resolve that differs from the
  bundled plugin) almost always means your snippet, needle, or assumption is wrong — or that you are reading dotc's raw
  internal representation where you should read a normalized one. Only conclude a compiler fault with irrefutable proof,
  and before that, confirm the behaviour against the scala3 source and `-Xprint:typer` / REPL. (Worked example: a term
  reference rendered as `(y : Int)` looked like a bug; it is dotc's canonical singleton-type rendering — every
  `SingletonType` prints as `( ref : underlying )` via `PlainPrinter.toTextSingleton`. The bridge was showing a raw
  `TermRef` where it should have shown the widened type.)
- **NEVER suppress errors.** If code compiles in dotc but Metallurgy's pipeline reports an ERROR highlight,
  the bug is in Metallurgy. Find the root cause and fix it. Do not suppress via `HighlightInfoFilter`,
  `ComparisonFailure` catches, test-harness string matching, `@volatile` flags, or any other mechanism.
  No exception exists to this rule. (Worked example: test snippets with top-level `def`/`val`/`import` were
  wrapped in an object because `definesType` only checked for `object`/`class`/`trait`/`enum`. The wrapping
  moved `import` to a local scope and changed package semantics, causing cascading PSI errors. The fix was
  to recognise all Scala 3 top-level constructs, not to suppress the errors.)
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

## Code smells

Source code is self-contained. It knows nothing about agents, review processes, planning docs, or other files in
the tree. A comment is either omitted, or it explains a non-obvious *why* in the present tense, describing the code it
sits on. Anything else is a smell:

- **No pointers to other places.** Not `// see AGENTS.md`, not `// see docs/scala3-compiler-backend.md`, not an upstream
  path like `// mirrors TypeInferenceTestFixture.assert…`, and not `// upstream: scala-impl/test/…/X.scala#testName`. The
  reader has only this file open; a reference they cannot resolve from here is noise.
- **No process or scaffolding language.** Not `// Layer 1: …`, not `// provenance:`, not "the refocus", "rollout
  failsafe", "how we got here". Architectural decisions live in the canonical design document, not in code.
- **No foreign identifiers in comments.** No issue numbers, SCL IDs, ADR numbers, or agent/skill names.
- **Omit before narrating.** Obvious code gets no comment. When a non-obvious constraint or failure mode must be
  recorded, write one sentence about *what breaks if you change this* — e.g. "Snapshot lookup is keyed by file URI and
  document version; a reused name returns another case's snapshot."
- **One exception warrants a comment — and an upstream link: resolving a non-obvious, *incorrectly-assumed* problem.**
  The rules above yield when code looks wrong but is correct — usually because it reads a dotc/IntelliJ internal
  representation where intuition expects the normalized form — and a reader would otherwise "fix" it back into a bug.
  Then a present-tense note earns its place, and it may cite the authoritative upstream source (a resolvable scala/scala3
  URL) that confirms the behaviour is intended; this is the sole case where an external link belongs in source. (Example:
  widening a term reference before rendering, because dotc prints every `SingletonType` as `( ref : underlying )` via
  `PlainPrinter.toTextSingleton`.)
- Traceability and provenance belong in commit messages, PR descriptions, or a separate manifest — never inside source.

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

- **Gate:** `ModuleDetectionService.isActive(module)` = Scala 3 **and** user opt-in. Metallurgy is opt-in on its own
  setting, independent of the bundled plugin's compiler-highlighting backend (CBH) — it does not require CBH to be on.
  Everything else is a hard no-op without it. Compiler versions never select behavior; optional facilities such as
  BETASTY are enabled only when discovered as capabilities. Exact PC artifact availability and optional facilities such
  as BETASTY are discovered independently.
- **Target engine:** `PcSessionManager` (per-module sessions) → exact compiler artifact in an isolated classloader →
  published Scalameta `PresentationCompiler` interface → bulk semantic snapshot. When no public PC operation exposes the
  required snapshot, a capability-probed compiler bridge may structurally read the retained driver and export only
  neutral DTOs. Queries are cached per `(fileUri, documentVersion)` and never run on the EDT.
- **Compatibility bridges:** compiler implementation access belongs only in `Scala3PcBridge`; Scala-plugin wrapping,
  replacement, and any private access belong only in `ScalaPluginSemanticBridge`. Consumers see one role-based,
  cache-only lookup interface and do not know which adapter supplied it. `StructuralScala3PcBridge` and
  `BundledCompilerBackendShim` are private implementations behind those interfaces, not consumer-visible surfaces.
  Type-rendering rules for the bridge (module singletons, poly-function binders, the dotc-vs-PSI reconciliation
  method, and reflection gotchas) live in [`src/main/scala/com/hmemcpy/metallurgy/pc/AGENTS.md`](src/main/scala/com/hmemcpy/metallurgy/pc/AGENTS.md).
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
- **Diagnosing dotc-vs-PSI type disagreements:** run the snippet through the compiler with `-Xprint:typer` (e.g.
  `scala-cli compile --scala <version> --scala-opt -Xprint:typer`) to see exactly how dotc typed it. If dotc's rendering
  differs from what the PSI/backend shows, the divergence is in the Metallurgy pipeline, not the compiler. Pair this
  with a REPL probe (`scala-cli repl --scala <version>`) for `:type` checks.
- **MacroAnnotation cannot add user-visible members** (Scala 3 design restriction) — don't try to test or support it.

## Tests

- Engine/presentation: `PcTypeResolutionTest` (exact-match type resolution across ~32 constructs), `PcPresentationTest`
  (slot), `PcCompletionTest` (real completion), `PcTypeInlayHintsTest` (inlay + proactive slot fill). The
  `withSession` helper (fetch pc jars, build a one-off `PcSession`, run on a pooled thread) is the pattern for any new
  pc-engine test.
- Golden fixtures live under `src/test/testdata/feature/<feature>/<name>/` with `source.scala` +
  `expected.metallurgy-{on,off}.txt`, driven by `MetallurgyFixtureTestCase` / `OracleExecutor`.

## Agent skills

- **Issue tracker:** GitHub issues via `gh`. See `docs/agents/issue-tracker.md`. **Continuity lives here:** read the
  relevant issue's comments (epic **#71** for the Scala 3 test-parity suite, epic **#50** for the backend epic) for
  prior context, root-cause findings, and progress. Do **not** use `AGENTS.md` files (root or per-module) as a log of
  investigation notes, status, or per-failure findings — those go in GitHub issue comments. `AGENTS.md` files hold
  standing core directives and working instructions only.
- **Triage labels:** `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.
- **Domain docs:** use the canonical design document. See `docs/agents/domain.md`. The live work queue is epic **#73**.
