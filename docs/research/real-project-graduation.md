# Real-project graduation

## Decision

Graduation uses six isolated, release-aligned Scala 3 project slices:

1. Cats kernel and core;
2. Cats Effect kernel, standard library, and core;
3. FS2 core and I/O;
4. Shapeless 3 deriving and typeable;
5. ZIO internal macros, stack tracing, core, and streams;
6. Tapir core.

Every slice is pinned to the commit named by its published release tag. Its unmodified repository sources, generated
sources, ordered classpaths, compiler, options, JDK, and module graph are content-addressed. Ordinary CI downloads and
verifies those inputs, reconstructs the locked IntelliJ project model, and never invokes an upstream build. Scheduled
graduation additionally imports the exact checkout through native sbt and BSP, compiles it, and proves that both live
models are equivalent to the lock.

The source-wide gate loads, parses, indexes, and highlights every selected Scala file. Expensive resolve, type,
navigation, usage, and edit operations use a deterministic coverage selection recorded in the lock. No result is
accepted merely because the editor did not crash. Compiler-valid source must have complete Scala PSI, and its
compiler-owned findings must equal dotc's findings exactly. A false error or warning is a release blocker.

BETASTY graduation is a separate cross-module state transition over the Cats Effect and FS2 graphs. It proves the
original product requirement: a downstream module remains semantically usable while an upstream module is broken and
has emitted generation-matched best-effort output. It does not participate in source parsing and does not type the
current buffer.

## Why the current smoke checks are insufficient

The existing six-project source/build manifest pins branch commits newer than the release binaries used by the IDE
smoke test. For example, the Cats source pin is not the commit tagged `v2.13.0`, while the IDE test resolves
`cats-core_3:2.13.0`. The other five projects have the same source/binary skew.

The build runner proves only that an upstream command completed. The IDE test presents six small authored expressions
against published jars. Neither path opens the pinned project sources, reproduces their module graph, checks full-file
PSI, exercises indices or editor operations, or tests cross-module recovery. They are useful probes, but they cannot be
counted as real-project graduation.

Implementation replaces those assets with the design below. It does not extend their current names or report format.

## Pinned project set

The selected roots below contain 959 Scala files and 190,674 lines at the pinned commits. The selection hash is
SHA-256 over lines of:

```text
<file-content-sha256><two spaces><repository-relative-posix-path><newline>
```

Files are sorted by the unsigned UTF-8 bytes of their relative path. Hashing the selected files rather than the
transport archive makes verification independent of archive timestamps and compression.

| Project | Release commit and compiler library | Selected JVM source slice | Files / lines | Selection hash |
|---|---|---|---:|---|
| Cats | [`v2.13.0`](https://github.com/typelevel/cats/tree/32a50dcfad9d897459bb755c4b5a22b4c7bc745c), Scala 3.3.4 | `kernel`, `core`, and the Scala-3-specific shared test root | 325 / 47,950 | `c8572232dd6f26582dcc9f51d32325f776b41e93e9d322b78844da99425559dc` |
| Cats Effect | [`v3.6.3`](https://github.com/typelevel/cats-effect/tree/624af3207e53be6f143564ddea85fa942cc9218a), Scala 3.3.4 | JVM source roots of `kernel`, `std`, and `core` | 163 / 31,644 | `054cd410db1de3b97bd3f9df4b5dd488b6fdcd6ea5fe7ffa6e91381ceb538bbc` |
| FS2 | [`v3.12.2`](https://github.com/typelevel/fs2/tree/46e2dc3abf994dcf3d0b804b2ddb3c10c04d4976), Scala 3.3.5 | JVM source roots of `core` and `io` | 126 / 26,204 | `0ff1192c2e8344dbd548818ec23d7f79936dc1d88cd83ef28717b1e6775f23a4` |
| Shapeless 3 | [`v3.4.0`](https://github.com/typelevel/shapeless-3/tree/8939588c144870ef75c034c96160d5c11893cebe), Scala 3.3.1 | all main and test roots of `deriving` and `typeable` | 14 / 4,204 | `96029f2d793a14c12f44fb6122ed88be1d79397f71010db98d07e08195d99182` |
| ZIO | [`v2.1.21`](https://github.com/zio/zio/tree/07efbcb7b987f0789cd1e5132bd3727a4185684f), Scala 3.3.6 | JVM source roots of `internal-macros`, `stacktracer`, `core`, and `streams` | 234 / 68,102 | `8e4dbd67e93a591ea3fc1db32f423eb561870ff692ecb5d6102571e1a9253029` |
| Tapir | [`v1.11.50`](https://github.com/softwaremill/tapir/tree/0e9bad48ab02ba7dfc8617906e827b0292318945), Scala 3.3.7 | JVM main and test roots of `core` | 97 / 12,570 | `20460cacf83685b56de0efef6ded461659b5745ab9065d248ced3c6d535e7372` |

The exact roots are:

- Cats: `kernel/src/main/{scala,scala-2.13+}`,
  `core/src/main/{scala,scala-2.13+,scala-3}`, and `tests/shared/src/test/scala-3`.
- Cats Effect: `{kernel,std,core}/shared/src/main/scala`,
  `kernel/shared/src/main/scala-3`, and the applicable
  `{kernel,std,core}/{jvm-native,jvm}/src/main/scala` roots.
- FS2: `{core,io}/shared/src/main/scala`, their applicable `scala-2.13+` and `scala-3` roots, and the applicable
  `{shared,js-jvm,jvm-native,jvm}` JVM roots.
- Shapeless 3: `modules/{deriving,typeable}/src/{main,test}/scala`.
- ZIO: `internal-macros`, `stacktracer`, `core`, and `streams` main roots selected by the JVM cross-project, including
  their applicable `scala-2.13+` and `scala-3` roots.
- Tapir: `core/src/{main,test}/{scala,scala-2.13+,scala-3}` where the directory exists.

These slices are deliberately complementary. Cats stresses higher-kinded abstractions and generated declarations.
Cats Effect and FS2 add deep contextual resolution, overloaded syntax, variance, and real source-module dependencies.
Shapeless is the concentrated inline, quoted, mirror, tuple, and derivation case. ZIO adds macro expansion, large
declaration-dense files, and another source-module chain. Tapir adds quoted schema derivation, endpoint type growth,
enums, and large nested trees.

The release coordinates that seed the locked classpaths are:

| Project | Selected published modules |
|---|---|
| Cats | `org.typelevel:cats-kernel_3:2.13.0`, `org.typelevel:cats-core_3:2.13.0` |
| Cats Effect | `org.typelevel:cats-effect-kernel_3:3.6.3`, `cats-effect-std_3:3.6.3`, `cats-effect_3:3.6.3` |
| FS2 | `co.fs2:fs2-core_3:3.12.2`, `co.fs2:fs2-io_3:3.12.2` |
| Shapeless 3 | `org.typelevel:shapeless3-deriving_3:3.4.0`, `shapeless3-typeable_3:3.4.0` |
| ZIO | `dev.zio:zio_3:2.1.21`, `dev.zio:zio-streams_3:2.1.21` |
| Tapir | `com.softwaremill.sttp.tapir:tapir-core_3:1.11.50` |

All twelve coordinates resolve from Maven Central. Their POMs establish the Scala 3 library versions in the first
table: [Cats](https://repo1.maven.org/maven2/org/typelevel/cats-core_3/2.13.0/cats-core_3-2.13.0.pom),
[Cats Effect](https://repo1.maven.org/maven2/org/typelevel/cats-effect_3/3.6.3/cats-effect_3-3.6.3.pom),
[FS2](https://repo1.maven.org/maven2/co/fs2/fs2-core_3/3.12.2/fs2-core_3-3.12.2.pom),
[Shapeless 3](https://repo1.maven.org/maven2/org/typelevel/shapeless3-deriving_3/3.4.0/shapeless3-deriving_3-3.4.0.pom),
[ZIO](https://repo1.maven.org/maven2/dev/zio/zio_3/2.1.21/zio_3-2.1.21.pom), and
[Tapir](https://repo1.maven.org/maven2/com/softwaremill/sttp/tapir/tapir-core_3/1.11.50/tapir-core_3-1.11.50.pom).
A compiler version written in this manifest is provenance, not a behavior switch.

## Input and project-model lock

The owned manifest lives at `real-projects/projects.json`. Each entry records:

- repository, release tag, full commit SHA, license path, and selected-source hash;
- logical modules and the exact ordered source, test, resource, and generated roots;
- module dependencies, production/test scope, output roots, and source-to-binary attachment rules;
- exact compiler coordinate, ordered scalac options, compiler plugins, JDK identity, and platform;
- every Maven URL, coordinate, classifier, byte size, and SHA-256 in classpath order;
- binary and source-jar identities for every selected release module;
- the normalized native-sbt and BSP descriptor hash;
- deterministic semantic anchors, usage anchors, edit recipes, and per-lane budgets.

The preparation tool performs these steps:

1. download the source archive by full commit SHA under a process-group timeout;
2. reject any selected path outside the manifest, any missing root, and any source-selection hash mismatch;
3. resolve the locked artifacts into a run-specific Coursier cache and verify every byte hash before use;
4. expand published source jars into a separate generated-source area;
5. require every source-jar file that overlaps a repository file to be byte-identical, and retain only non-overlapping
   generated files;
6. construct the IntelliJ modules from the checked normalized descriptor;
7. disable network access before any PSI, index, compiler, or editor observation begins.

For a selected module's own source analysis, its binary jar is absent from its compiler classpath. A downstream module
may use the release-aligned upstream jar as its initial compiled output while retaining a source-module dependency for
navigation. The scheduled build lane replaces that jar with the checkout's actual output and requires the semantic
results to remain identical. Edit and BETASTY lanes always use the newly built upstream output; they never leave a stale
release jar earlier on the classpath.

Generated sources are inputs, not harness-authored substitutes. Cats' source generators and ZIO's build information are
materialized only from the matching published source jars or from a scheduled model export whose bytes are locked.
Missing, conflicting, or unproven generated sources fail preparation.

## Graduation lanes

### Input verification

This fast lane validates the manifest schema, source hashes, artifact hashes, classpath order, project descriptor, and
offline replay. It fails on any drift. It performs no IDE assertions and cannot count as project graduation.

### Ordinary change validation

Ordinary CI runs an integrity-checked representative slice against the locked model, with no upstream build:

- all Shapeless files;
- a deterministic minimum cover from every other project;
- both ends of every selected source-module edge;
- the three largest files in each project;
- every file named by a semantic, usage, or edit anchor.

The minimum cover's universe is every observed production-catalog identifier, semantic role, origin
(`source`, `generated`, `compiled`, `cross-module`), logical module, and file-size quintile. Repeatedly select the file
covering the most uncovered values; break ties by unsigned UTF-8 relative-path order. Freeze the resulting paths and
content hashes in the manifest. Regeneration must be byte-for-byte reproducible. If the slice exceeds 128 files, CI
fails and the project is split into more jobs; coverage is never dropped to fit the budget.

This lane runs full PSI and diagnostic assertions on its selected files, plus every locked semantic and editor anchor
located in those files.

### Full source graduation

This lane runs on all 959 selected files. It:

- concatenates every AST leaf and requires exact source bytes;
- requires a complete Scala file rather than neutral pending PSI for every compiler-valid input;
- rejects parser errors, unknown required productions, extra file wrappers, leaf/composite substitutions, and
  production-catalog misses;
- invokes every applicable required public `Sc*` accessor and records its result or exception;
- compares cold-stub, open-AST, closed-file, rebuilt-index, and restarted-project observations;
- highlights every file and compares compiler-owned diagnostics with the exact dotc run;
- runs all locked semantic, navigation, usage, and editor anchors;
- records capability reports, fallbacks, exceptions, thread activity, invalidations, and resource measurements.

The source-wide structural and diagnostic checks are exhaustive. Sampling applies only to expensive semantic and editor
queries.

### Loader equivalence

Scheduled jobs fetch the release commit into an isolated workspace and use the project-supported JDK to:

1. compile the selected Scala 3 modules with their exact upstream command;
2. import the checkout through native sbt;
3. import the checkout through BSP;
4. normalize both live module descriptors and compare them to one another and to the checked lock;
5. replace bootstrap module jars with actual outputs and rerun the full source lane.

The normalized comparison includes module edges, source/test/generated roots, ordered compiler and plugin options,
ordered classpaths, output roots, project SDK, and compiler coordinate. Absolute work paths and timestamps are excluded.
Import and editor automation use the owned harness together with `~/git/ide-probe`; the IntelliJ Scala plugin source is
not built.

### Capability-upgrade and BETASTY

Release artifacts in this project set were produced on Scala 3.3.x. Cross-module best-effort recovery therefore runs in
a separate cell:

1. select a compiler only after executable probes confirm both best-effort production and consumption;
2. prove the pinned unmodified source modules compile cleanly under that exact compiler and locked options;
3. compile the upstream module cleanly and prove the downstream module resolves from ordinary output;
4. apply the locked one-hunk break, compile the upstream module, require real errors and generation-matched `.betasty`;
5. remove ordinary upstream classfiles from the consumer path and require downstream diagnostics, types, completion,
   navigation, and usage anchors to remain current from best-effort output;
6. repair the source, rebuild, require ordinary output to replace best-effort state, and observe a new semantic
   generation without restarting the IDE.

The two required graphs are:

- Cats Effect `kernelJVM → stdJVM/coreJVM`. The edit changes one `Boolean` result branch in
  `kernel/shared/src/main/scala/cats/effect/kernel/Outcome.scala` to a non-Boolean expression while preserving the
  declared API. Downstream `core` anchors over `Outcome` must remain usable. The upstream build declares these
  relationships directly
  ([build](https://github.com/typelevel/cats-effect/blob/624af3207e53be6f143564ddea85fa942cc9218a/build.sbt#L425-L512)).
- FS2 `coreJVM → ioJVM`. The edit changes the body of a declared `Stream` method to an expression of the wrong type
  without changing its signature. Existing `io` references to `Stream` must remain usable
  ([build](https://github.com/typelevel/fs2/blob/46e2dc3abf994dcf3d0b804b2ddb3c10c04d4976/build.sbt#L295-L390)).

Each edit recipe records the file hash, exact before bytes, exact after bytes, expected upstream diagnostic identity,
retained public symbols, downstream anchors, and repaired hash. It applies only to a disposable checkout. There is no
source search-and-replace heuristic.

If a runtime lacks either BETASTY operation, this lane records `not-applicable` for that runtime rather than passing or
failing it. Product graduation still requires at least one capability-probed runtime to complete both real-project
graphs. All runtimes must pass the ordinary clean cross-module path.

## Oracles and measurements

| Surface | Oracle and graduation assertion |
|---|---|
| PSI | Exact leaf-byte reconstruction, catalog production identity, required child/cardinality/ownership contracts, public accessor behavior, and no neutral PSI for compiler-valid input. |
| Diagnostics | Exact dotc source graph and environment. Compare normalized severity, code/category, message identity, source range, and generation. Every real dotc error or warning remains visible; every extra or missing compiler-owned finding fails. IDE-only inspections are reported separately and must independently justify their findings. |
| Resolve and type | Exact compiler symbol identity and normalized compiler type at the same source range and semantic role. Types are exact values, never substring matches. |
| Navigation | The resolved compiler symbol's source file and range. A compiled symbol must navigate to its attached release source; a selected source-module symbol must navigate to repository source. |
| Usages | The exact set of compiler reference ranges for a selected symbol, partitioned into read, write, type, import, given, extension, and synthetic origins. Missing and extra usages both fail. |
| Edits | Exact document bytes and version at each checkpoint, deterministic recovery PSI while invalid, current semantic generation after repair, preserved caret/selection/folding state, and no stale result. |
| Index | Equal symbol/name/supertype index entries from cold stubs, open PSI, closed files, rebuild, restart, and loader variants. Duplicate entries, stale entries, stub exceptions, or pending-file stubs fail. |
| Runtime | Parser/PC invocation counts, thread and read/write-action context, snapshot generations, cache size, invalidation/restart count, process/thread termination, heap/RSS, and wall-clock distributions. |
| Logs | Zero unclassified IntelliJ `ERROR`/`SEVERE`, failed stub trees, PSI/document/model mutations during highlighting, stale-PSI access, index corruption, uncaught exceptions, or capability failures. Expected dotc diagnostics are data, not log exceptions. |

Semantic anchors are selected from the exact compiler snapshot. Their universe is every available combination of
semantic role, production identifier, owner kind, origin, and local/cross-module status. For every project, retain all
members of a bucket containing three or fewer nodes and otherwise select three by `(file path, start offset, end
offset, compiler symbol)` order. Then add:

- the three largest files;
- every rare syntax feature found by the production catalog;
- every generated-source boundary;
- both sides of each module edge;
- twelve usage symbols per project, covering term, type, local, member, given, extension, macro/inline, generated, and
  cross-module symbols when present.

The resulting anchor manifest stores source hashes and exact ranges. A missing anchor is drift and fails before tests
run. New compiler or catalog features expand the universe automatically; they cannot silently remain unsampled.

## Editor state transitions

Each project has one locked valid edit, one incomplete edit, and one rename preview:

1. start from a cold, indexed, compiler-current project;
2. apply the exact edit and commit the document;
3. observe the expected syntax and semantic generation state without waiting on the EDT;
4. invoke highlighting, resolve, type, completion, navigation, and usages at the locked anchors;
5. undo to the exact original bytes and require the original structural/index/semantic hashes;
6. redo and repair, then require one current publication for the final document version.

The incomplete edit removes a real closing delimiter or right-hand side from a complex selected tree. Its compiler
errors remain visible. Recovery is judged by deterministic PSI, surviving unaffected declarations, editor operations,
and absence of exceptions—not by hiding diagnostics.

The rename runs as a preview in the disposable workspace. The expected changed-range set comes from the compiler usage
oracle. It fails if the refactoring proposes a missing, extra, or non-source edit.

## Budgets

All commands use GNU `gtimeout --kill-after=5s`. Timeout, resource exhaustion, missing measurements, and truncated
reports are failures, never skips.

### Job budgets

| Lane | Hard budget |
|---|---:|
| Input preparation and offline replay | 5 minutes |
| Ordinary change validation | 20 minutes, 4 GiB maximum heap |
| Cats compiler-clean build | 15 minutes |
| Cats Effect compiler-clean build | 15 minutes |
| FS2 compiler-clean build | 15 minutes |
| Shapeless compiler-clean build | 10 minutes |
| ZIO compiler-clean build | 20 minutes |
| Tapir compiler-clean build | 30 minutes |
| Full IDE analysis, per project and loader | 20 minutes; 30 minutes for ZIO and Tapir |
| Each real-project BETASTY state machine | 20 minutes |
| Parallel scheduled workflow | 45 minutes overall |

Full jobs use a 6 GiB maximum heap. Peak process-tree RSS must remain below 8 GiB, and retained RSS after project close
and forced test-only collection must be within 256 MiB of the pre-open baseline. Download caches and IDE system
directories are outside the retained-memory number but have a combined 12 GiB disk ceiling per job.

### Operation budgets

Measure one cold run followed by five warm runs on the same pinned runner. Record p50, p95, and max per file-size bucket
and typed-node count. Both the absolute and regression limits apply; the stricter result wins.

| Operation | Absolute limit |
|---|---:|
| Whole-file parse and PSI plan, file ≤ 20 KiB | p95 75 ms, max 250 ms |
| Whole-file parse and PSI plan, 20–100 KiB | p95 250 ms, max 750 ms |
| Whole-file parse and PSI plan, file > 100 KiB | p95 1 s, max 2 s |
| Cache-only semantic lookup | p95 1 ms, max 5 ms, zero compiler calls |
| Edit to current semantic snapshot, file ≤ 100 KiB | p95 2 s, max 5 s |
| Edit to current semantic snapshot, file > 100 KiB | p95 5 s, max 10 s |
| Project close and owned worker termination | max 10 s |

No presentation-compiler, full compiler, artifact resolution, or project-model work may run on the EDT. Any
Metallurgy-attributable EDT task over 100 ms fails. Each file version permits one parser walk and one semantic compiler
walk; coalesced abandoned versions are counted and reported but may not publish.

Against the accepted baseline for the same project, environment, size bucket, and operation, p95 may regress by at most
20 percent and max by at most 50 percent. Retained heap and snapshot bytes may regress by at most 20 percent. A baseline
update requires an explicit reviewed manifest change with before/after reports; CI never rewrites it.

## Machine-readable result

Every lane emits one JSON document with:

- schema and runner versions;
- project, source-selection, project-model, environment, capability, and artifact-lock identities;
- all discovered and executed files, anchors, edits, and module edges;
- dotc and IDE diagnostics with ownership and generation;
- structural, semantic, navigation, usage, edit, and index observations;
- exceptions, platform log events, fallbacks, thread violations, timeouts, and missing data;
- cold/warm latency distributions, invocation counts, heap/RSS, disk, and process-tree data;
- explicit `passed`, `failed`, `infrastructure-failed`, or `not-applicable` state for each assertion.

The report is emitted atomically only after inventory closure. A project passes only when every applicable assertion is
present and passed. Aggregate graduation requires all six projects, both loader variants, both BETASTY graphs on at
least one capable runtime, and an intact ordinary-CI slice.

## Implementation consequences

The implementation program must:

1. replace the current source/build runner and six-expression IDE smoke test with the locked inputs and lanes above;
2. add an owned project-model constructor, source/artifact verifier, anchor generator, and result schema;
3. add full-source structural, diagnostic, semantic, editor, index, log, and runtime observers;
4. integrate native sbt and BSP imports through the owned IntelliJ automation;
5. implement the Cats Effect and FS2 BETASTY state machines;
6. keep ordinary CI offline after preparation and reserve upstream builds and full imports for scheduled/release lanes;
7. make the complete real-project report a required final gate after copied-test parity, never a substitute for it.
