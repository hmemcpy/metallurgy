# Moving capability validation matrix

## Decision

Metallurgy validates two independently moving systems:

1. the exact Scala compiler artifact selected by a module; and
2. the IntelliJ Platform and bundled Scala plugin that host the resulting PSI.

Production behavior is selected only by executable capabilities. Version strings identify inputs, cache entries, and
reports; they never select a parser, PSI target, semantic route, or BETASTY flag.

The validation program has three complementary parts:

- a complete stable-Scala sweep against the compatibility baseline;
- a small covering matrix across maintained, stable, release-candidate, and nightly compiler and host lines;
- capability-transition tests that require old and new hosts to expose the same public PSI behavior even when one uses
  a compatibility implementation and the other uses a native implementation.

This is deliberately not a Cartesian product. The baseline host bears the full exact-compiler contract. Additional
hosts prove that the public `Sc*` boundary and capability discovery survive platform movement.

## What “any Scala 3 version” means

Every stable Scala 3 release from `3.0.0` onward is in the product contract. A compiler artifact does not become
unsupported merely because its minor line is no longer maintained or because the modern presentation-compiler
artifact was published later. Official Maven metadata currently exposes these final-version coordinates:

```text
3.0.0  3.0.1  3.0.2
3.1.0  3.1.1  3.1.2  3.1.3
3.2.0  3.2.1  3.2.2
3.3.0  3.3.1  3.3.2  3.3.3  3.3.4  3.3.5  3.3.6  3.3.7  3.3.8
3.4.0  3.4.1  3.4.2  3.4.3
3.5.0  3.5.1  3.5.2
3.6.0  3.6.1  3.6.2  3.6.3  3.6.4
3.7.0  3.7.1  3.7.2  3.7.3  3.7.4
3.8.0  3.8.1  3.8.2  3.8.3  3.8.4
```

The list is resolved from official Maven metadata and checked against the
[Scala release archive](https://www.scala-lang.org/download/all.html), then locked into each run. Scala `3.3.2` is an
intentional special entry: its artifacts reached Maven Central but the release was subsequently abandoned because it
violated LTS TASTy compatibility, as documented in the official
[post-mortem](https://www.scala-lang.org/blog/2024/03/06/scala-3.3.2-post-mortem.html). It remains in the sweep because
the exact artifact exists and a project can still request it; this is test-input classification, not production
special-casing. New stable releases enter the next scheduled sweep automatically. Removed or rewritten metadata cannot
alter an already locked run.

This reveals a current product gap rather than a support exception:
`org.scala-lang:scala3-presentation-compiler_3` begins at `3.3.2`, while
`org.scala-lang:scala3-language-server_3` and `scala3-compiler_3` exist from `3.0.0`. The current resolver requests only
the modern artifact, so Scala `3.0.0` through `3.3.1` cannot currently form a session. The implementation program must
add a capability-discovered legacy adapter over the exact published compiler facilities, behind the same neutral
compiler bridge. It may attempt supported artifact shapes in a fixed general order—published Scalameta PC first,
published legacy compiler facilities second—but it may not branch on a Scala version.

An exact artifact that cannot execute on the host JBR is a failed product-contract cell until independently proven to
be an external runtime impossibility. That proof is reported explicitly and does not silently redefine the supported
range. The validation runner records the JBR identity because the official
[JDK compatibility table](https://docs.scala-lang.org/overviews/jdk-compatibility/overview.html) varies across compiler
releases; the production bridge still makes no JDK-version decision.

Scala itself maintains two current lines: Scala Next and Scala LTS. Scala Next is stable, not an experimental channel,
and experimental features remain explicitly gated. Scala's
[development guarantees](https://www.scala-lang.org/development/) define the compatibility and release model. As of
2026-07-27 the stable representatives are Scala Next `3.8.4` and LTS `3.3.8`; `3.9.0-RC4` is the latest published
candidate for the next LTS, and
`3.10.0-RC1-bin-20260726-a036a3a-NIGHTLY` is the latest compiler nightly in the official
[nightly repository](https://repo.scala-lang.org/artifactory/maven-nightlies/org/scala-lang/scala3-compiler_3/maven-metadata.xml).
Moving selectors are resolved at run start and never committed into production logic. The official
[nightly documentation](https://docs.scala-lang.org/overviews/core/nightlies.html) defines the repositories and
selectors.

## Host lines

The host axis is a platform build plus the compatible bundled Scala-plugin artifact. The Marketplace's compatibility
range, not a guessed pairing, selects the platform build. The
[Marketplace channel documentation](https://plugins.jetbrains.com/docs/marketplace/custom-release-channels.html)
defines stable and custom channels.

The initial locked representatives are:

| Host role | IntelliJ build | Scala plugin | Why it exists |
| --- | --- | --- | --- |
| Compatibility baseline | `261.26222.65` | `2026.1.20` | Current development target and the older PSI surface on which compatibility production must work. |
| Current stable | latest compatible `262.*` release | `2026.2.15` | Proves the same public contract when newer native Scala 3 productions are available. |
| Current EAP | latest artifact in the `eap` channel distinct from stable, if any | matching EAP platform | Detects the next supported host contract before release. If the channel currently mirrors stable, this cell is `not-distinct`, not a pass. |
| Current nightly | latest compatible `263.*` snapshot | `2026.3.190` | Detects public and private host changes early; it is a signal lane, not a stable-release prerequisite. |

The exact moving artifacts above are observations on 2026-07-27, not constants. Each run queries and locks:

- the stable Marketplace feed for plugin `org.intellij.scala`;
- the `eap` feed for plugin `1347`;
- the `nightly` feed for plugin `1347`;
- the IntelliJ release or snapshot repository for a build satisfying the plugin's declared compatibility range.

If several plugin artifacts target different platform branches, the runner selects the newest artifact on each distinct
branch and reports the extras. An update that claims the same branch replaces the previous moving representative in
the next run. The compatibility baseline remains locked until a deliberate design decision moves the minimum public
`Sc*` contract.

The baseline and stable host both run a named-type-argument transition fixture. The source and assertions are
identical. The baseline is expected to select a compatibility production where its native target fails the executable
contract; the newer host may select the native production only if that target passes. Both results must have identical
target-neutral AST, accessor, stub, index, resolve, and diagnostic observations.

## Capability model

Capabilities describe operations, not classes or methods. A method or field shape permits an attempt; only an
executable semantic assertion makes the capability available.

Each result is one of:

```text
Available     the operation completed twice in fresh contexts and satisfied its contract
Unavailable   no supported mechanism can provide the operation
Broken        a candidate mechanism exists but invocation or contract validation failed
NotApplicable a declared prerequisite is unavailable, with that prerequisite named
```

`NotApplicable` is valid only for an optional mechanism or a test whose declared prerequisite is absent. It cannot
turn a required role green. Every result contains:

- stable capability and probe identifiers;
- artifact coordinates, content digests, repository, platform build, plugin identity, JBR identity, and options digest;
- chosen mechanism category: public, typed structural, or isolated raw reflection;
- prerequisite capability identifiers;
- normalized observations and their digest;
- failure phase, exception type, message, and bounded trace when unsuccessful;
- duration, thread category, and repeated-run determinism result.

The registry distinguishes mandatory product roles from alternative mechanisms.

### Compiler-side roles

| Role | Required proof |
| --- | --- |
| Exact distribution | Resolve the requested compiler and its matching libraries without substituting a nearby version; isolate its implementation classes. |
| Interactive session | Construct, configure, open, update, cancel, and close an exact-version session; no exact-loader object crosses the bridge. |
| Parser evidence | Build a virtual source, parse synchronously with exact options, traverse ordered named products and positioned syntax, decode spans, extract parser diagnostics and comments, and produce deterministic neutral evidence. |
| Source PSI plan | Compile the complete production catalog and emit exact-source, balanced, deterministic AST and stub observations for accepted and recovery inputs. |
| Typed snapshot | Retypecheck one document version once and export all required type, symbol, resolve, occurrence, and compiler-diagnostic roles as neutral values. |
| Completion | Return compiler-ranked semantic completion with stable edits and symbol identity. |
| Hover | Return compiler type/signature/documentation for the selected occurrence. |
| Semantic document | Return SemanticDB when available; absence is acceptable only if the typed-snapshot route independently supplies every consumed occurrence and symbol role. |
| BETASTY production | A full compiler run with the discovered producer option emits generation-matched `.betasty` from a deliberately broken upstream module while preserving real upstream errors. |
| BETASTY consumption | A downstream exact compiler, with ordinary upstream classfiles removed, loads the discovered best-effort root and resolves the retained upstream API. |

The typed snapshot, completion, hover, and compiler diagnostics are mandatory outcomes even if their mechanism differs
between compiler lines. Public Scalameta operations take precedence. Typed structural access to the retained driver is
the fallback when the public boundary lacks a bulk operation. Isolated raw reflection is confined to the bridge and is
available only after an executable probe.

BETASTY production and consumption are separate capabilities because either may exist without the other. They remain
optional for ordinary same-module typing and clean cross-module compilation. They are nevertheless a required
graduation outcome on every matrix cell that exposes both capabilities: their original purpose is to preserve
downstream Scala 3 highlighting when an upstream module is broken. A capable cell that cannot complete the break,
consume, repair, and invalidate sequence fails. Product graduation also requires at least one stable compiler cell to
complete the real Cats Effect and FS2 cross-module sequences.

### Host-side roles

| Role | Required proof |
| --- | --- |
| Public Scala PSI contract | Required `Sc*` traits, accessors, element contracts, navigation elements, and modification semantics execute against probe trees. |
| Native production target | Native element type, factory, child shape, accessor behavior, and stub/index behavior satisfy one catalog production. |
| Compatibility production target | Private bridge implementation satisfies the same public contract and target-neutral observations when no native target does. |
| File and language lifecycle | Pending and ready languages, view providers, reparsing, copies, physical and light files, indexing, and epoch disposal preserve the specified deterministic state machine. |
| Semantic facade routing | Every active Scala 3 type, resolve, symbol, completion, and diagnostic root consumes the current compiler facade or returns explicit unknown; it never delegates to bundled inference. |
| Platform composition | Public extension points compose with existing providers where promised; a bridge wrapper is admitted only when an executable probe disproves clean composition. |
| Editor identity | Navigation, find usages, rename, completion insertion, intentions, quick fixes, and refactorings retain stable PSI/symbol identity across valid edits and defined recovery states. |

A native element's existence is not sufficient. Each production chooses exactly one target only after the public
contract probe passes. Compatibility targets are first-class implementations of the same contract, not repairs applied
to an already emitted native tree.

## Matrix

### Complete stable sweep

The compatibility baseline runs every stable Scala 3 release from `3.0.0` through the latest stable release, plus any
published final-version artifact such as abandoned `3.3.2`. This lane uses one generated version list and identical
assertions for every artifact:

- capability preparation and double-run self-tests;
- exact-source parser and recovery examples;
- the complete declarative production-catalog contract suite;
- deterministic AST, accessor, stub, index, and restart signatures;
- typed snapshot, diagnostics, resolve, completion, and hover contracts;
- clean two-module compilation and downstream highlighting;
- copied IntelliJ parity shards applicable to compiler-accepted source.

Feature flags come from the unchanged fixture environment. A feature test is applicable when the exact compiler accepts
its unchanged source with those flags. Compiler rejection is recorded as non-applicable to that test, not as a bridge
success. No coordinate changes an assertion.

This full sweep is scheduled because resolving and starting every historical compiler on every pull request is
unnecessarily expensive. A smaller pull-request set catches the distinct implementation shapes already established:

| Compiler representative | Purpose |
| --- | --- |
| `3.0.0` | Earliest Scala 3 artifact and legacy interactive-adapter boundary. |
| `3.3.0` | Earliest release in the maintained LTS line. |
| `3.3.1` | Last release before the modern presentation-compiler artifact exists. |
| current LTS (`3.3.8`) | Maintained library line and modern PC boundary. |
| `3.5.2` | Established parser shape and first required stable BETASTY exercise when capabilities are present. |
| project compiler (`3.7.4`) | Development and copied-test baseline. |
| current Scala Next (`3.8.4`) | Latest final language and library behavior. |

When two representatives produce identical capability-shape digests for three complete scheduled runs, the pull-request
lane may keep only the boundary member with the wider contract. Both remain in the scheduled stable sweep.

### Covering host matrix

| Host | Compiler set | Frequency | Gate |
| --- | --- | --- | --- |
| Compatibility baseline | pull-request representatives above | every pull request | merge-blocking |
| Compatibility baseline | every stable or published final-version Scala 3 artifact | nightly and before graduation | graduation-blocking |
| Current stable host | current LTS, project compiler, current Scala Next | every pull request after the host is provisioned | merge-blocking |
| Current stable host | all stable-minor endpoints plus current RC | nightly and before graduation | graduation-blocking |
| Current EAP host | current LTS, current Scala Next, current RC | daily when distinct | forward-signal |
| Current nightly host | current LTS, current Scala Next, current RC, current compiler nightly | daily | forward-signal |
| Compatibility baseline | current RC and current compiler nightly | daily | forward-signal |

“Stable-minor endpoints” means the first and latest final patch of every `3.x` line. They expose both the initial and
accumulated shape of each minor without repeating the complete historical sweep on every host.

The current RC is `3.9.0-RC4` at the time of this decision because Scala 3.9 is the upcoming LTS. The current nightly
has already moved to the following development line. These identities are reported facts only.

### Test depth

Every matrix cell runs the capability self-tests and concise cross-role contract suite. Depth then increases:

1. pull-request cells run copied parity shards, invalid-edit recovery, deterministic stubs/indices, semantic facade
   routing, and the small clean cross-module project;
2. scheduled stable cells run the complete copied suite and broad complex examples;
3. graduation cells add all pinned real projects, both loader variants, UI automation, resource budgets, and the real
   BETASTY state machines;
4. forward-signal cells run self-tests, representative copied shards, the named-type-argument transition, semantic
   facade routing, and a small cross-module BETASTY test when both capabilities are present.

Real-project graduation remains attached to certified stable cells. Compiler and host nightlies do not download and run
all projects unless a capability drift needs escalation.

## Blocking policy

### Merge blocking

A pull request cannot merge when a provisioned merge-blocking cell has:

- an unavailable or broken mandatory role;
- any compiler-valid source represented by neutral PSI;
- a PSI, accessor, stub, index, type, symbol, resolve, completion, hover, or diagnostic mismatch;
- any false error or warning;
- a hidden compiler error or warning;
- nondeterministic output for identical inputs;
- stale generation data, an EDT wait, an unexpected platform error, or a resource-budget failure;
- a failed BETASTY sequence on a cell that exposes both producer and consumer capabilities.

Infrastructure failure is reported separately and retried once on a clean worker. It never becomes a product pass.

### Graduation blocking

Graduation additionally requires:

- the full stable-and-published-final Scala sweep green;
- the current stable host's scheduled cells green;
- the copied IntelliJ invocation ledger complete, with conflicts independently proven and visible as non-passes;
- all stable real-project slices green;
- Cats Effect and FS2 BETASTY break/consume/repair sequences green on at least one stable capable compiler, and on every
  graduation cell advertising both capabilities;
- no unexplained capability removal or mechanism downgrade;
- a green repeated run from cold artifact, IDE, and index caches.

An EAP or nightly failure does not block a stable graduation merely because an upstream moving artifact is temporarily
unavailable or broken. It becomes graduation-blocking when:

1. the same failure is present in a release candidate intended to become the next stable compiler or host;
2. the failure appears in the newest three distinct nightly artifacts over at least 48 hours and affects a mandatory
   role on source accepted by the exact compiler; or
3. the capability change has landed in an artifact selected by a stable channel.

This delay filters publication and repository incidents, not semantic regressions. The first confirmed failure opens a
tracked report immediately. A new stable artifact cannot be declared certified while its cell is failing.

## Forward capability drift

Each run writes a machine-readable capability snapshot and a concise Markdown diff. The snapshot schema is owned by
Metallurgy and versioned independently of compiler or host artifacts.

The diff classifies:

- capability added, removed, or changed;
- public mechanism gained or lost;
- structural or reflective fallback newly used;
- prerequisites changed;
- normalized behavior changed with the same apparent API shape;
- previously unknown public PC operation discovered;
- native PSI target newly satisfying or ceasing to satisfy a catalog contract;
- performance or determinism threshold changed.

An added public operation is reported even when Metallurgy has no adapter for it. It does not alter behavior until an
IntelliJ-side adapter and executable contract are implemented. A newly valid native PSI target may replace a
compatibility target only after both produce identical target-neutral results. A lost public route may use an already
proven fallback for the current session, but the mechanism downgrade remains visible and blocks graduation until
reviewed.

The production registry is constructed from the same executable probes but does not read CI snapshots, channel names,
or version classifications. There is no generated allowlist to smuggle matrix decisions into runtime behavior.

## Runner and artifacts

The matrix runner owns resolution and locking:

1. read official release and channel metadata;
2. resolve each selector to exact coordinates and repository URLs;
3. verify checksums and record content digests;
4. compute the covering cells;
5. provision a fresh IntelliJ SDK and matching Scala plugin without building the upstream repository;
6. run bounded shards through the local harness and `~/git/ide-probe`;
7. retain raw test reports, IDE logs, process logs, capability snapshots, diffs, and environment lock;
8. publish one aggregate result whose cells cannot overwrite one another.

Every test JVM gets unique file URIs, IDE system/config/log roots, compiler caches, and project roots. Downloads, builds,
tests, and IDE processes use GNU `gtimeout` with a process-group kill deadline. Ordinary scheduled reruns use locked
artifacts and can run offline after preparation.

The environment lock includes:

- OS and architecture;
- JBR vendor, version, and digest;
- IntelliJ and Scala-plugin coordinates and digests;
- Scala compiler, presentation/legacy adapter artifacts, libraries, and digests;
- Scalameta interface artifact;
- compiler options, feature flags, classpath, module graph, and output roots;
- production-catalog, capability-schema, fixture, and harness digests;
- source commit and dirty-worktree state.

Absolute temporary paths and timestamps are excluded from semantic comparison but retained in raw logs.

## Capability failure at runtime

The same failure policy used by the tests applies in the IDE:

- while exact artifact and parser capabilities are preparing, active files use the unrelated neutral pending language;
- a missing or broken required module capability leaves the module unavailable and produces one project-level report;
- an unrepresentable source plan produces deterministic file-scoped neutral PSI and a project-level report;
- semantic state that is absent or stale is explicit unknown, never bundled Scala inference;
- a missing BETASTY producer or consumer leaves ordinary same-module and clean cross-module semantics operational but
  marks broken-upstream recovery unavailable;
- a runtime failure that disproves a published capability retires that module epoch and prepares a new one; it cannot
  switch parser or PSI shape inside the existing epoch.

No failure hides a diagnostic, modifies a source fixture, selects a nearby compiler, or falls back based on an artifact
version. Capabilities constrain only the operation they prove.

## Implementation consequences

The final implementation program must include:

1. a hierarchical capability registry with executable probes and evidence-bearing states;
2. a legacy exact-compiler adapter so releases before `3.3.2` participate in the same role contracts;
3. generated compiler-release and Marketplace-channel locks;
4. parameterized host SDK/plugin provisioning without building the Scala plugin;
5. complete stable-sweep, covering-matrix, drift-diff, and gate aggregation tasks;
6. the 2026.1-to-2026.2 native/compatibility transition test using unchanged named-type-argument source and assertions;
7. independent executable BETASTY producer and consumer probes plus small and real-project cross-module state machines;
8. retirement of shape-only capability checks once their executable replacements cover the same roles.

The matrix validates independence from versions by repeatedly changing versions while leaving the production decision
rules unchanged. Its success criterion is not that every artifact exposes the same mechanisms. It is that every stable
Scala 3 release and published final-version compiler artifact supplies the required compiler-owned behavior, every
certified host supplies the required public PSI behavior, and optional mechanisms change only their own named
capability.
