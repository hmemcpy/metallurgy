# Metallurgy

A third-party IntelliJ plugin that produces Scala PSI from the exact Scala 3 compiler parser and replaces active
Scala 3 semantic roles with the real compiler while retaining IntelliJ's existing Scala PSI interfaces.

- **Scope:** deterministic whole-file Scala 3 PSI, stubs, and indices; compiler-owned types, symbols, resolve,
  completion, navigation, and language diagnostics; best-effort TASTy recovery for broken upstream modules.
- **Canonical design:** [`docs/scala3-compiler-backend.md`](docs/scala3-compiler-backend.md).
- **Implementation program:**
  [`docs/deterministic-scala3-psi-implementation-program.md`](docs/deterministic-scala3-psi-implementation-program.md).
- **Work queue:** [Deterministic Scala 3 PSI parity](https://github.com/hmemcpy/metallurgy/issues/85).
- **Status:** pre-alpha. The target architecture is fully specified; each active ownership cutover deletes the
  implementation it replaces in the same change.

## Plain communication

Use plain English in user-facing and public communication, including status updates, GitHub comments, pull request and
commit summaries, reports, and public documentation. Lead with the practical meaning or status. Explain necessary
technical terms briefly. Avoid dense prose and long metric lists unless they are needed for a decision or audit. Keep
detailed raw evidence in internal evidence files or threads and link to it instead of pasting it publicly.

## Autonomous routine decisions

Decide routine implementation choices, compiler artifacts, test fixes, and bounded refactor mechanics without asking.
Ask only for destructive or irreversible production actions, credentials or privileged access, material scope or
behavior changes, data loss, weaker acceptance criteria, or choices with meaningful user-visible effects. Follow the
repository's direct-delivery and sole-owner rules.

## Direct delivery to `idea261.x`

All ongoing implementation is committed directly to `idea261.x` from one clean sole-writer local worktree based on the
latest remote target. Do not create or push feature or task branches, and do not use pull requests for this sole-owner
delivery path.

Before work, fetch and reconcile remote state. Require the local base to be clean and exactly equal to remote
`idea261.x`, confirm there is one writer and no conflicting task process, and preserve unrelated divergent or recovery
worktrees. Local recovery checkpoints for uncommitted work are allowed when useful, but they must not create remote
task refs.

Before push, run the packet's required tests and an independent Medium review. Inspect the exact author and committer,
which must both be `Igal Tabachnik <hmemcpy@gmail.com>`, and reject automated attribution, trailers, signatures, or
extra commit headers. Reconfirm that remote `idea261.x` has not advanced. Push only an explicit normal non-force
fast-forward from `HEAD` to `refs/heads/idea261.x`.

After push, confirm minimally with a fresh `ls-remote` and fetch that remote `idea261.x` equals the commit. Repeat full
validation or integration audit only after an ambiguous push, drift, corruption, or material risk.

Audit stale remote branches against current content and history. Remove only branches whose work is fully represented,
using exact leases. Preserve unique or ambiguous branches pending a user decision. Never alter tags during branch
cleanup.

## Primary goals (immutable)

These goals are the project's reason for existing and take precedence over every other instruction in this file.

1. **1:1 PSI parity from dotc — existing Scala 3 tests pass as-is.** The bundled
   [intellij-scala](https://github.com/JetBrains/intellij-scala) tests cover Scala 3 behavior, bugs, features, and
   quirks. Metallurgy builds the same observable PSI from the exact compiler. Compiler-valid upstream tests retain
   their source, executable assertions, and expected results unchanged.

   An upstream assertion that contradicts the exact compiler is an **upstream-oracle conflict**, not a requirement to
   alter or hide compiler behavior. Preserve it verbatim, prove the disagreement independently against the exact
   compiler, and report it separately as a non-pass. Treat every disagreement as a Metallurgy bug until exact-version
   dotc evidence proves otherwise.
2. **Dotc is always right.** Whatever the real exact-version compiler accepts and means is correct by definition. A
   different type, resolve target, completion, or diagnostic is a Metallurgy defect. Conclude compiler defect only
   with irrefutable proof from the exact compiler, its source, and its tests.
3. **Validate in the Scala REPL before any fix — always.** Reproduce the exact code with
   `scala-cli repl --scala <version>`, `:type`, and `-Xprint:typer`. If the behavior cannot be reproduced, the problem
   is not understood.
4. **If dotc compiles it, the PSI must represent it.** A compiler/PSI disagreement belongs in the parser bridge,
   source evidence, production catalog, compatibility PSI, or semantic mapping. Find the root cause and fix the correct
   layer. Do not add suppression, version allowlists, fingerprints, ad-hoc checks, test-string matching,
   `ComparisonFailure` catches, or mutable escape flags.
5. **Never wrap or alter test fixtures or snippets.** Do not add an enclosing object, rename members, move imports, or
   edit source to make it compile. Scala 3 supports top-level definitions and the suite's other constructs. Fix the
   implementation.
6. **Rendering differences are absorbed at the bridge boundary.** Preserve compiler meaning while adapting
   presentation to the installed PSI's expected form. Never edit an expected result to accommodate raw compiler
   display text.

## Discipline (non-negotiable)

- **Use the exact commit identity.** Every commit author and committer must be exactly
  `Igal Tabachnik <hmemcpy@gmail.com>`.
- **Never add automated attribution.** Do not add `Co-authored-by`, `Signed-off-by`, `Amp-Thread-ID`, or any other
  commit trailer, header, or message attribution naming Amp, ampagent, ampcode, an Amp email/domain, or an automated
  agent.
- **Keep sole ownership explicit.** Metallurgy is a sole-owner research project. Its only owner, user, author, and
  committer is `Igal Tabachnik <hmemcpy@gmail.com>`. Unpushed local history may be rewritten to enforce this policy
  when active work is preserved and recoverable local backups exist. Never rewrite or force-push remote `idea261.x`.
- **Prevent attribution injection.** Hooks, ship prompts, commit templates, and tooling must not inject such
  attribution.
- **Inspect commits before push.** Inspect the complete raw commit object and message plus author and committer fields;
  stop if prohibited attribution exists.
- **Assume the compiler and PC are correct.** A surprising result normally means the probe, source range, flags,
  classpath, snapshot generation, or interpretation is wrong. Confirm the exact compiler behavior before changing
  implementation.
- **Never suppress errors.** If compiler-valid code has an ERROR or false WARNING highlight, Metallurgy is wrong.
  Fix the producing layer. Do not use `HighlightInfoFilter`, diagnostic dropping, exception catches, test matching, or
  any other concealment.
- **Use [scala/scala3](https://github.com/scala/scala3) as the language source of truth.** Check exact-version compiler
  source and tests before stating that a syntax, type, macro, or best-effort behavior is unsupported or defective.
- **Use [intellij-scala](https://github.com/JetBrains/intellij-scala) as the IntelliJ/Scala-plugin API reference.** A
  local checkout is at `~/git/intellij-scala`. Search it for existing parser, PSI, stub, index, fixture, and extension
  patterns before implementing one.
- **Do not build the Scala plugin.** Own copied tests and local harnesses in this repository. The upstream checkout is
  reference source only.
- **Adapt on the IntelliJ side.** Prefer published IntelliJ, Scala-plugin, compiler, and Scalameta interfaces. Use typed
  structural access next. Raw reflection is permitted only inside the appropriate compatibility bridge after supported
  interfaces are exhausted. No upstream Scala 3, Scalameta, or Scala-plugin change is a prerequisite.
- **Discover capabilities, never versions.** Compiler and plugin versions identify artifacts and reports. Do not use
  version switches, bytecode fingerprints, or unconditional implementation-class mappings.
- **Keep compatibility access isolated.** Exact compiler parser access belongs in `Scala3ParserBridge`; exact semantic
  compiler access belongs in `Scala3PcBridge`; Scala-plugin construction/private access belongs in
  `ScalaPluginSemanticBridge`. Consumers depend on neutral role interfaces.
- **No conversational or historical terms in source code.** Comments describe present constraints. No issue numbers,
  SCL identifiers, planning vocabulary, model attribution, changelog narration, or pointers to planning documents.
- **Run potentially long-lived commands through GNU `gtimeout`.** This includes sbt, Java/IntelliJ, tests, builds, and
  downloads:

  ```sh
  /opt/homebrew/bin/gtimeout --kill-after=5s <limit> <command>
  ```

  Use 120 seconds for focused builds/tests unless evidence justifies more. Retain and poll a yielded process session to
  its real exit. With output formatters, enable `pipefail`.
- **No `Thread.sleep` in production code.** Use latches, futures, alarms, and cancellation.

## Command execution policy

- Every command, test, build, and probe must have a hard timeout. The default command ceiling is 10 minutes.
- Most commands should finish within 2 minutes with active output. Split broad work into small, focused commands with
  visible progress.
- If a command shows no useful output or test phase progress for 2 minutes, inspect it immediately. Stop it unless its
  health is proven.
- A justified final complete suite or heavy stress gate may use a longer measured timeout without separate routine
  approval. Run an unchanged complete or heavy gate at most once per packet, feature, or epic, at the very end after
  focused and ordinary gates are green. Record the exact command, reason, expected duration, progress plan, and stop
  conditions before it starts.
- Monitor an approved longer run at least every 30 seconds. Investigate after 2 minutes without phase or test progress.
  Stop after 5 minutes without proven progress unless a known silent phase was documented before the run.
- Never rerun an unchanged command that timed out. Diagnose the cause, narrow the command, or fix the problem first.
  A second complete or heavy run requires a concrete fix or changed evidence from smaller probes.
- Consume output while commands run, use timestamped logs, and preserve the logs when stopping a command.
- Explain command results and blockers in plain English. Work in the sole-writer `idea261.x` checkout without feature
  branches or pull requests.

## Workspace hygiene

`target/` is scratch space, never a record. Regenerable artifacts must not outlive the run or packet that produced
them; accumulated build leftovers exhaust the disk and block all work.

- A packet is delivered only after its leftovers are cleaned. Fixture-test sandboxes (`idea-test-*`), lane logs, test
  reports, and stale compilation output are deleted when the run that created them ends, or at the latest when the
  packet closes.
- Test-lane evidence under `target/test-evidence/` is retained only for the current packet. Baselines are keyed to the
  source revision they were recorded against, so a superseded packet's evidence is stale by construction and is
  deleted at delivery. Preserving per-suite evidence applies to the active packet; it is not a license to accumulate.
- Evidence for an unresolved failure is kept until the failure is diagnosed and fixed, then follows the same rule.
- Before push, `target/` holds nothing except the current packet's evidence.
- `scripts/clean-workspace.sh` enforces this boundary: pass the active packet's prefix with `--keep` to retain current
  evidence, run it without `--keep` to reach the pre-push state, and use `--dry-run` to preview the plan.

## Source-code comments

Source code is self-contained. Omit obvious comments. A necessary comment states a non-obvious present-tense constraint
and what breaks if it changes.

- Do not point to AGENTS files, design documents, issue IDs, upstream file paths, or review artifacts.
- Do not narrate phases, migrations, earlier implementations, or replacement history.
- Do not name agents, models, reviewers, tickets, epics, or initiatives.
- Keep traceability in commit messages, issue comments, and manifests rather than source.
- An authoritative upstream link is justified only when code looks incorrect but matches a surprising documented
  compiler or platform behavior that a maintainer might otherwise undo.

## Build and test

Runtime is **JBR 25** and must be `JAVA_HOME` for builds and tests:

```sh
JBR=~/.metallurgyPluginIC/sdk/261.26222.65/jbr/Contents/Home
/opt/homebrew/bin/gtimeout --kill-after=5s 120s \
  env JAVA_HOME="$JBR" PATH="$JBR/bin:$PATH" \
  sbt -batch -no-colors "scalafmtAll" "testOnly <fully.qualified.Test>"
```

- sbt **1.11.7**.
- Plugin code: Scala **3.7.4**.
- In-tree testkit: Scala **2.13.16**, matching the installed plugin it mirrors.
- Baseline host: IntelliJ **261.26222.65**, Scala plugin **2026.1.20**.
- Aliases: `sbt fmt`, `sbt check`, `sbt runIDE`.
- Run `scalafmtAll` before every commit.
- `-Xfatal-warnings`, unused-value warnings, and non-Unit statement warnings are enabled. Fix every warning.
- Explicit deterministic test lanes live in `test-lanes/` and run through `scripts/run-test-lane.sh`. Preserve their
  per-suite evidence; do not replace them with wildcard selection.
- Copied IntelliJ Scala tests are pinned by `upstream-tests/intellij-scala.json`. Snapshot and generated sources are
  byte-controlled inputs: never format or edit them directly. Regenerate under `target/` and run
  `verifyCopiedIntellijTests` plus `verifyCopiedIntellijTestsAgainstOrigin`.

## Target architecture

### Gate and exact artifacts

`ModuleDetectionService.isActive(module)` is Scala 3 plus explicit user opt-in. It is independent of bundled
compiler-highlighting settings. Inactive modules execute the installed plugin unchanged and allocate no Metallurgy
work.

An active module resolves its exact compiler artifacts and independently probes parser, semantic, completion, and
best-effort TASTy capabilities. Artifact versions never select behavior.

A compiler/host cell is admitted only when its discovered inventories and contracts are covered. Artifact acquisition
and capability discovery alone do not admit unseen grammar or semantics.

### Syntax

Preparing modules use a neutral non-Scala, non-stub-bearing language. Once exact parser capabilities are ready, one
platform VFS batch activates the module.

Ready files synchronously follow:

```text
verbatim source
  -> exact compiler parser
  -> neutral parser products and positions
  -> lossless source-evidence plan
  -> typed production catalog
  -> complete whole-file PsiBuilder plan
  -> Scala AST, PSI, stubs, and indices
```

Typed trees never produce syntax. Parsing never waits for semantic work. No background operation replaces syntax.
Unknown required compiler-valid productions fail closed to deterministic neutral PSI rather than a bundled parse. The
project/file capability report names the exact compiler artifact and host, missing parser capability or stable role,
affected scope, retained operations, and evidence/remediation location.

Metallurgy owns stable neutral grammar roles and PSI output-role contracts. Compiler production names and installed
implementation classes are inventory evidence, not durable role identities. Parser products and output composites may
lower one-to-many or many-to-one. Each output role independently binds to a capability-proven native implementation or
a Metallurgy compatibility implementation satisfying the same observable role contract; native and compatible roles
may coexist in one file without observable provider differences.

### Semantics

`PcSessionManager` owns per-module exact-version sessions. Published Scalameta PC operations are preferred. A
capability-probed compiler bridge may structurally read the retained driver when no public operation exposes the
required whole-file snapshot.

One immutable snapshot carries structured types, symbols, occurrences, completion, navigation, diagnostics, source
version, compiler identity, and every freshness generation. Consumers query one `CompilerSemanticFacade`.

Only a Current snapshot supplies active Scala 3 semantics. Pending, Unavailable, Failed, Missing, and Stale are
explicit unknown states and never use bundled type inference or resolution.

### Compatibility bridges

`Scala3ParserBridge` and `Scala3PcBridge` export only neutral immutable DTOs. No compiler implementation object crosses
its isolated classloader.

`ScalaPluginSemanticBridge` owns capability-probed native PSI construction, compatibility PSI/stubs, and any necessary
private Scala-plugin access. Consumer code sees public IntelliJ/Scala PSI and role-based facade interfaces.

### Diagnostics

Dotc owns Scala language errors and compiler warnings. Explicitly classified IDE-only inspections remain. Semantic
inspections consume the compiler facade. Every visible finding has one owner; no highlight filter suppresses a result.

### Best-effort TASTy

IntelliJ's ordinary build/compile-server pipeline produces best-effort TASTy for broken upstream modules. Downstream
sessions consume it only when the exact compiler exposes the consumer capability. Artifact and session freshness
includes upstream output/classpath generation and artifact content.

Best-effort TASTy is a cross-module semantic input. It never parses the current file or selects PSI shape.

## Operational constraints

- `PcSession` snapshots are keyed by `(fileUri, documentVersion)`. Every test case needs a unique URI.
- `configureByText` filenames must contain no spaces or embedded dots; use `Case1.scala`.
- Single-character needles may select the wrong subtree. Needle the result definition name.
- `ScalaLightCodeInsightFixtureTestCase` requires `getTestDataPath`.
- A helper called from JUnit must not have a public `test*` name when it accepts a by-name body.
- Type tests assert exact whitespace-normalized rendering, never substrings.
- Experimental constructs require their exact compiler flags. Discover support as a capability; do not infer it from
  the version.
- Diagnose type disagreements with exact-version REPL and `-Xprint:typer` before editing the bridge.
- Scala 3 macro annotations cannot add members visible to user-written code; do not design a PSI feature around such
  members without contrary exact-compiler proof.
- `doParseContents` returns `builder.getTreeBuilt.getFirstChildNode`. Returning the wrapper root hides top-level
  declarations from lexical resolution.
- Composite PSI requires balanced `PsiBuilder` markers. A visually similar leaf is not an `Sc*` implementation.
- Parser evidence must account for all source ranges, trivia, delimiters, and zero-width layout events. Terminal and
  wrapper output roles may refine provisional source atoms only through reviewed generic lexer-boundary-safe interval
  contracts. A contract atomically replaces one identified half-open atom with a contiguous ordered partition that
  exactly covers it without changing source order, text, or evidence claims; every new cut is proven safe by the closed
  lexical contract used to build the lexer tape. Zero-width events retain distinct evidence identities even when they
  share an offset. Unknown or unsafe boundaries and overlapping, multiply claimed, or unowned bytes or events fail
  before lexer-tape construction, `PsiBuilder` creation, or physical emission.
- AST completion precedes stub derivation. Identical input and schema must produce identical stub and index signatures.
- IntelliJ `Logger.info` writes to `idea.log`, not the runIDE stdout redirect.

## Test contract

- Copied Scala 3 sources, executable assertions, and expected results remain exact.
- Local adapters use descriptive names; external tracker identifiers live only in provenance manifests.
- Every discovered copied test must execute or appear as a visible independently proven compiler conflict.
- Broad nested examples complement minimized upstream tests.
- PSI tests cover exact text, ranges, element types, parents, direct children, every declared accessor, recovery,
  copies, edits, reparse, restart, pointers, stubs, indices, navigation, rename, and usages.
- Semantic tests cover exact types, symbols, resolution, completion, navigation, one-owner diagnostics, stale states,
  and absence of fallback.
- Best-effort TASTy tests use a real two-module build/compile-server producer and verify downstream highlighting through
  break, consume, repair, rename, removal, reload, and restart.
- Final checks include published Scala versions, moving IntelliJ/Scala-plugin hosts, pinned real projects, and resource
  budgets.
- Compiler support is a declared rolling evidence matrix. A newly published artifact with covered inventories and
  contracts admits without production-code changes; novel drift identifies bridge, grammar-role, output-role,
  semantic-role, or compatibility-binding work. Never claim that an old binary supports arbitrary unseen grammar.

## Maintainable file boundaries

Avoid huge first-party source, test, and configuration files. Before adding unrelated growth to a mixed-responsibility
file, split it along cohesive semantic feature or ownership boundaries.

Retain one clear aggregation or registry entry point where ordering or discovery is part of the contract. Prefer the
smallest shared abstractions needed by multiple owners. Preserve deterministic order and public behavior, and avoid
initialization cycles or excessive fragmentation.

A known monolith may grow only while its dedicated decomposition packet is in progress, or during a bounded migration
completed in the same packet.

## Agent resources

- **Amp agent transport:** a persistent runner (`amp --no-tui --runner-id metallurgy`) runs in this directory and
  executes remotely created threads; keep exactly one alive and never start a duplicate. Submit agent work (oracle
  consultations, reviews, courier records) by continuing the coordinator thread with a delegation request - the
  coordinator creates runner-bound subthreads through its internal thread-creation tool (`executor: runner`,
  `runner_id: metallurgy`) - and read results with `amp threads markdown <subthread-id>`. Never run long agent turns
  through a foreground `amp --execute`: the blocked CLI times out and orphans the thread. `amp -ox` creates cloud-orb
  threads on fresh sandboxes that re-fetch sources and toolchains; reserve it for genuinely isolated scenarios. The
  public `@ampcode/sdk` exposes only `local` and `orb` executors, not runners.
- **Issue tracker:** GitHub via `gh`; see `docs/agents/issue-tracker.md`.
- **Continuity:** read the relevant implementation task and epic comments. Do not use AGENTS files as progress logs.
- **Triage labels:** see `docs/agents/triage-labels.md`.
- **Domain architecture:** this file points to the canonical design; see `docs/agents/domain.md`.
- **IntelliJ automation:** use the IntelliJ 261 compatibility lane in `ideprobe-tests/`; its README documents the
  memory-safe two-phase invocation. It packages the pinned Scala plugin, Metallurgy, and a locally adapted ide-probe
  0.53 plugin, then opens the real `dogfood` sbt project under Xvfb. Preserve the stage timeline and exported `idea.log`,
  wait for import/indexing and Metallurgy module readiness, run highlighting, inspect `MessagePool`, and validate the
  final log only after IDE shutdown so teardown exceptions cannot escape observation. First-run, trust, update, tips,
  and onboarding UI remain suppressed through `ideprobe.conf` and generated IDE settings. On macOS the IDE child needs
  the GUI login session (WindowServer); over SSH the probe times out waiting for it. Run the harness by writing an
  executable `.command` wrapper that exports `METALLURGY_REPO_ROOT`, `METALLURGY_INTELLIJ_HOME`, and `JAVA_HOME`, puts
  `$JAVA_HOME/bin` on `PATH`, unsets
  `_JAVA_OPTIONS` and `METALLURGY_IDEA_JAVA_OPTIONS`, redirects all output to a log file, runs `packageArtifact` and
  `run-ide-probe.sh sbt -java-home "$JAVA_HOME" -batch -no-colors test` - the sbt launcher script ignores the
  `JAVA_HOME` environment variable, so `-java-home` must select the JBR explicitly - copies the artifacts and test
  reports into `target/test-evidence/`, and
  writes a completion marker, captures the wrapper's own TTY, and closes the launching Terminal window with a detached
  AppleScript keyed on that TTY so the window does not linger - then launch it with `open -a Terminal <wrapper>` so it
  runs unattended inside the desktop session, and validate the results from the copied log files and evidence.
- **Platform sources:** the pinned IntelliJ source archive is under the resolved SDK's `sources/` directory, the pinned
  Scala plugin is under `custom-plugins/Scala`, and ide-probe sources may be checked out under `target/` for comparison.
  Use the upstream IntelliJ Community, intellij-scala, Scala 3, Metals, and ide-probe repositories when local artifacts
  do not expose the contract being adapted.
