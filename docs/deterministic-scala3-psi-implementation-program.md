# Deterministic Scala 3 PSI implementation program

## Destination

Metallurgy synchronously produces one complete, source-compatible Scala PSI tree from the exact Scala 3 compiler
parser for every active Scala 3 file in an admitted compiler/host cell. The compiler exclusively supplies Scala 3
types, symbols, resolve, completion, navigation, and language diagnostics. Every compiler-valid copied IntelliJ Scala
3 test retains its source, assertions, and expected output unchanged. Broken upstream modules preserve correct
downstream highlighting through capability-discovered best-effort TASTy. Active files outside an admitted cell fail
closed; opt-in does not extend the support promise to arbitrary unseen future grammar.

The implementation does not retain a compatibility rollout. New components may be exercised directly before they are
registered, but each active ownership boundary changes atomically and deletes the implementation it replaces.

## Invariants

1. The exact compiler parser is the syntax authority. Typed trees are semantic evidence, never syntax input.
2. One synchronous walk produces one whole-file AST. Background work may publish semantics but never change syntax
   shape.
3. Source text, tokens, trivia, indentation, and recovery structure are lossless and deterministically owned. Terminal
   and wrapper output roles may refine provisional source atoms only through reviewed generic lexer-boundary-safe
   interval contracts. Each contract atomically replaces one identified atom with an ordered exact partition without
   changing source order, text, or evidence claims; co-located zero-width events retain distinct evidence identities.
   Unknown or unsafe boundaries and overlapping, multiply claimed, or unowned bytes or events fail before physical
   emission.
4. A typed production catalog accounts for every compiler production, source range, public Scala PSI accessor, stub,
   and index obligation.
5. Unknown required compiler-valid syntax fails closed to neutral file-scoped PSI and a project capability report. It
   never falls through to the bundled Scala parser.
6. Active, ready Scala 3 modules use compiler semantics exclusively. Pending, unavailable, failed, missing, and stale
   generations are explicit unknown states, never invitations to bundled type inference.
7. Dotc language errors and warnings remain visible. IDE-only inspections remain only when their ownership is
   explicitly classified. No result is hidden by a highlight filter.
8. Copied snippets, executable bodies, assertions, and expected results remain exact. Local setup, generation, names,
   and invocation plumbing may vary.
9. Compiler and host versions identify artifacts and reports, not behavior. Executable capabilities select optional
   facilities.
10. IntelliJ's ordinary build and compile-server path is the authoritative producer for the end-to-end best-effort
    TASTy highlighting gate. Direct dotc invocations are lower-level controls.
11. Metallurgy owns stable neutral grammar roles and PSI output-role contracts. Compiler production names and host
    implementation classes are inventory evidence, not durable role identities.
12. Each output role independently binds to a capability-proven native implementation or a Metallurgy compatibility
    implementation. Native and compatible roles may coexist in one file without observable contract differences.
13. A newly published compiler with covered inventories and semantic contracts admits without production-code
    changes. Novel drift identifies bridge, role, or compatibility work and never selects behavior by version. The
    declared support promise covers admitted compiler/host cells, not arbitrary unseen future grammar.

## Dependency graph

| Stage | Requires | Unlocks |
| --- | --- | --- |
| Contract replacement | Confirmed implementation program | One non-contradictory architecture |
| Deterministic test execution | Contract replacement | Trustworthy test evidence |
| Owned test foundation | Deterministic test execution | Verbatim upstream-oracle verification |
| Executable product baselines | Owned test foundation | Regression gates for every replacement |
| Exact parser bridge | Executable product baselines | Compiler-owned syntax evidence |
| Neutral parser lifecycle | Exact parser bridge | Safe synchronous activation |
| Source evidence and catalog | Exact parser bridge | Exhaustive whole-file PSI planning |
| PSI, stub, and index production | Source evidence and catalog | Active syntax replacement |
| Atomic syntax cutover | Parser and physical-file gates | Stable Scala PSI for semantic work |
| Structured semantic facade | Atomic syntax cutover | Role-sized semantic replacement |
| Semantic role cutovers | Structured semantic facade | Compiler-exclusive IntelliJ behavior |
| Best-effort TASTy graduation | Semantic roles | Cross-module highlighting guarantee |
| Version and project graduation | All preceding gates | Completion |

No semantic cutover depends on a partial or asynchronously replaced syntax tree. The existing best-effort TASTy tests
remain active while syntax changes, but their final editor guarantee depends on the compiler-exclusive semantic facade.

## Current implementation inventory

The active syntax path is `Scala3DotcFileElementType` → `Scala3ParserBridge` →
`ProvisionalSourceEvidencePlanner` → `PreparedProductionCatalog` → `WholeFileProductionPlanner` →
`PlannedScala3Lexer` and generic `DotcPsiProducer`. Preparing files use the neutral language; ready PSI construction
never invokes the bundled Scala parser or lexer. Editor coloring uses the installed Scala 3 lexical highlighter and
does not construct PSI. `Scala3SyntaxCapabilityService` retains exact file-scoped failure stage, detail, source digest,
parser preparation epoch, and compiler identity when a required production is unavailable.

The reviewed catalog currently owns file/package roots and the supported top-level import and export families:
arbitrary finite stable `Select`/`Ident` paths, direct and braced selectors, aliases, wildcard, unbounded `given`, and
bounded `given` with simple/applied, qualified, wildcard-bound, and infix/union/intersection types, plus
ordinary/operator/Unicode/quoted names. Package clauses cover unbraced, chained, nested, sibling, braced, and
colon-indented forms with matching package end markers and supported import/export children. Imports and exports have
independent statement grammar and output roles while sharing import-expression, selector, and stable-reference
descendants. Their local output-composite forests own exact physical evidence and fulfill native accessors, navigation,
stubs, serializers, the aliased-selector index, and the top-level export package index. Template, extension,
local-block, definitions/expressions/patterns, and unrelated type grammar remain unavailable and fail closed.
Native bindings execute their public PSI and persistence contracts before activation. All other grammar families
remain unavailable and therefore produce deterministic neutral file-scoped PSI.

The active semantic foundation consists of exact artifact/session management, immutable versioned snapshots,
compiler-type conversion, symbol occurrence mapping, completion integration, diagnostics, and best-effort TASTy
producer/consumer controls. Those components remain work in progress until every active Scala semantic role has an
executable no-fallback cutover. They are not syntax inputs.

Deterministic lane execution, copied-test provenance/invocation accounting, parser vertical slices, physical PSI
lifecycle tests, two-module best-effort controls, and IntelliJ lifecycle automation are active infrastructure. Full
grammar coverage, complete copied-suite execution, source-wide representative-project IDE lanes, moving compiler/host
matrices, and resource budgets remain graduation requirements.

## Change sequence

Each numbered change is an independently reviewable commit unless its text explicitly defines a short series. A
grammar-family or semantic-role series may contain several commits, but every commit closes a connected contract and
leaves its direct tests passing.

### 1. Maintain one architecture contract

Keep `docs/scala3-compiler-backend.md` and package instructions aligned with the synchronous parser bridge, neutral
preparation lifecycle, source evidence plan, production catalog, compatibility PSI boundary, compiler-exclusive
semantic facade, role-sized cutovers, owned-test integrity rules, and build-produced best-effort TASTy editor
guarantee.

**Gate:** documentation contains one architecture and every implementation instruction follows it.

### 2. Make test execution deterministic

Add exact-environment test tasks and scripts that:

- use the repository's JBR and bounded process-group timeouts;
- enumerate suites explicitly rather than relying on timed wildcard discovery;
- shard deterministically;
- preserve JUnit reports, process output, IntelliJ logs, environment coordinates, and exit status;
- fail when an expected suite or test invocation is missing;
- separate ordinary checks from scheduled version and real-project runs.

Align local and CI Java/runtime assumptions. Retain a fast compile and formatting gate.

**Gate:** two identical runs select the same tests and retain complete evidence, including failures and timeouts.

### 3. Establish the owned IntelliJ test foundation

Vendor the pinned upstream Scala 3 test sources and add a generator that produces locally compiled adapters. Add:

- a provenance manifest containing upstream path, revision, original test identity, local descriptive identity, and
  required Scala/compiler capabilities;
- mechanical verification that executable bodies, snippets, assertions, and expected output are exact;
- helper-contract verification for inherited fixture behavior;
- runtime invocation accounting for every discovered Scala 3 test;
- visible classification of accepted parity, expected-rejection parity, invalid-state recovery, and independently
  proven compiler conflicts.

Land one representative generated suite first. Additional suites join the tree with the grammar family or semantic
role they exercise.

**Gate:** mutating one snippet, assertion, helper contract, or expected value makes the integrity check fail.

### 4. Record executable product baselines

Convert the measured current state into reproducible test lanes for:

- parser and AST shape;
- public `Sc*` accessors;
- stubs, indices, navigation, and closed-file behavior;
- copy, edit, reparse, restart, smart pointer, completion, rename, and find-usages operations;
- current semantic successes and failures;
- runtime invocation and conflict accounting.

Add a true two-module best-effort TASTy editor test. IntelliJ's ordinary compile server builds a deliberately broken
module A. Module B consumes A's best-effort output and must retain correct highlighting, resolution, and completion for
valid public declarations. Repairing and changing A must refresh B. A direct-dotc producer/consumer test remains as a
control.

Rename the existing real-project semantic suite and remove prohibited terminology from maintained source and
documentation.

**Gate:** every later ownership change has an executable before/after comparison, and the build-produced cross-module
test is mandatory.

### 5. Introduce the exact compiler parser boundary

Add a parser-specific bridge alongside the presentation-compiler bridge. Only neutral immutable values may cross its
classloader boundary:

- ordered named productions and fields;
- source ranges, point positions, and synthetic-position provenance;
- parser diagnostics;
- capability results;
- exact artifact coordinates and loader identity.

The whole-file plan lowers physical ownership to an immutable lexer tape before constructing `PsiBuilder`. Ready files
must pass that tape explicitly; a null lexer or registered Scala lexer silently reintroduces bundled syntax ownership.

Published interfaces come first, typed structural protocols second, and isolated raw reflection last. Raw reflection
is limited to exact-loader construction and operations that cannot be expressed structurally.

**Gate:** classloader tests prove that no dotc implementation object escapes and that bridge closure releases its
loader.

### 6. Prove a modern parser vertical slice

Drive `Driver.setup`, `SourceFile.virtual`, and the parser directly for a broad, unmodified Scala source containing
packages, imports, templates, type and value parameters, definitions, applications, selections, comments, and
significant indentation. Keep the path unregistered and test it directly.

The bridge returns parser products and diagnostics only; it does not run typer and does not consult bundled PSI.

**Gate:** repeated parsing yields byte-identical neutral output and exact ranges while the source text round-trips
unchanged.

### 7. Add the legacy exact-artifact adapter

Support exact Scala 3 artifacts whose published presentation-compiler layout does not expose the modern parser-loading
shape. Probe general structural operations and adapt successful shapes to the same neutral parser DTOs.

No condition may inspect a compiler version, implementation fingerprint, or allowlist.

**Gate:** representative Scala 3.0 through 3.3.1 artifacts and modern artifacts execute the same parser contract before
grammar expansion begins.

### 8. Implement the neutral preparation lifecycle

Create an unrelated neutral language and associated file type with no Scala base language, lexer, parser, references,
stubs, indices, or inherited Scala extensions. Model each active module epoch as:

1. `Preparing` — exact artifacts and parser capabilities are being acquired;
2. `Activating` — substitution state is ready and one VFS batch is scheduled;
3. `Ready` — active files synchronously use the compiler parser.

Use `FileContentUtilCore.reparseFiles` for one platform-managed batch. Preserve file, document, and range identity
across the language transition; do not preserve cross-language PSI pointers. Do not invoke artificial dumb mode,
direct reload, or manual reindexing.

**Gate:** multi-file activation performs one reparse batch, pending files remain neutral and verbatim, and stale epoch
completion cannot activate a newer module state.

### 9. Build lossless source evidence

Create a plan-backed leaf stream from verbatim source and positioned parser evidence. Account for:

- significant tokens;
- whitespace and comments;
- indentation, outdent, and other zero-width layout events;
- delimiters and separators owned by parent productions;
- missing, zero-width, synthetic, and recovery positions;
- source ranges not represented as standalone compiler nodes.

Scanner replay may compare or enrich evidence but cannot decide the production tree. Any source interval with no unique
owner is a planning failure.

Each reviewed generic refinement contract identifies one provisional atom and stable terminal or wrapper output role,
then atomically replaces the atom with contiguous, ordered, non-empty half-open intervals that exactly partition its
range and retain its evidence claims. New cuts must already be evidence boundaries or be proven safe by the closed
lexical contract used to build the immutable lexer tape; source-text matching alone is insufficient. Zero-width events
are assigned by stable evidence identity rather than offset. Unknown atoms, roles, or events; unsafe boundaries;
incomplete partitions; and overlapping, multiply claimed, or unowned evidence fail before lexer-tape construction,
`PsiBuilder` creation, or physical emission.

**Gate:** reconstruction equals the original source byte for byte, ownership is non-overlapping and complete, and
layout-sensitive fixtures are deterministic.

### 10. Generate inventories and compile the production catalog

Generate one inventory of exact compiler parser productions and fields and one inventory of Scala PSI element types,
implementations, public accessors, stub element types, serializers, and indices. Normalize them through Metallurgy-owned
grammar roles and stable PSI output roles, then join them through a reviewed typed catalog. Parser products and output
composites may lower one-to-many or many-to-one.

Each catalog entry declares:

- compiler production and capability shape;
- neutral grammar role and explicit local output-role forest;
- source and token ownership;
- required, optional, repeated, and recovered children;
- ordering and parent contract;
- per-role native target probe or compatibility binding;
- every public accessor the resulting `Sc*` element must satisfy;
- stub fields, serializer, indices, and navigation identity;
- compiler-valid, invalid-edit, and unknown-production behavior.

Generate a closed whole-file production plan before touching `PsiBuilder`. The validator rejects uncovered compiler
fields, unreachable PSI requirements, overlapping ownership, or incomplete stub/index accounting.

**Gate:** the compiler and Scala PSI inventories are reproducible, stable output roles are distinct from native
implementation identities, and the catalog validator fails closed for every unaccounted production or unbound role.

### 11. Add PSI and stub factories

Probe native Scala-plugin production classes by observable construction and accessor behavior. Reuse them only when
they satisfy the catalog contract. Otherwise create compatibility PSI and stub implementations inside
`ScalaPluginSemanticBridge`.

Add an owned file-stub root with a stable external identity and explicit schema version. Register compatibility
serializers and indices statically. Build the complete AST first and allow `DefaultStubBuilder` to derive stubs from
it.

**Gate:** a native and compatibility implementation of the same catalog contract produce equivalent accessor, stub,
index, and navigation observations.

### 12. Expand grammar by connected production families

Implement catalog entries as connected families, never as isolated reported-syntax patches:

1. file structure, packages, imports, exports, and layout;
2. templates, constructors, definitions, parameters, bounds, and modifiers;
3. type syntax and type-level forms;
4. expressions, applications, selections, control flow, givens, extensions, and context functions;
5. patterns, matches, comprehensions, quotes, and splices;
6. capability-discovered experimental productions.

Every family commit adds:

- direct AST and exact text assertions;
- all declared public-accessor assertions;
- copy, edit, and invalid-state recovery tests;
- stub and index assertions for stub-bearing entries;
- broad examples with nested, interacting productions;
- the corresponding generated upstream tests with unchanged executable content.

A new compiler production enters through generated inventory drift, a catalog entry, and its complete contract. It
never enters through parser-error string matching or a feature/version branch.

**Gate:** every discovered production is catalogued, every compiler-valid generated syntax test passes, and recovery
tests remain structurally safe.

### 13. Prove physical-file, stub, and index behavior

Run target-local and target-neutral checks for:

- cold and warm physical parsing;
- in-memory copy and non-physical files;
- edit and incremental reparse;
- stub serialization/deserialization;
- closed-file indices;
- restart and schema invalidation;
- smart pointers and navigation;
- ready-to-neutral and neutral-to-ready transitions;
- native-versus-compatible production equivalence.

**Gate:** AST, stub, and index signatures are deterministic across process restart and lifecycle transitions.

### 14. Cut syntax over atomically

Register the ready parser and neutral lifecycle as the only active syntax route. Keep language/module detection,
compiler artifact boundaries, parser definition, file element, view provider, and substitution registrations aligned
with synchronous whole-file production rather than layering a second route beside them.

**Gate:** active ready files have one parser path; all
syntax, PSI, stub, index, recovery, and build-produced best-effort TASTy baselines pass.

### 15. Build the structured semantic facade

Create one current-generation, cache-only facade backed by a whole-file typed compiler snapshot. Its neutral model
covers:

- normalized Scala types and provenance;
- stable compiler symbols and ownership;
- definitions, references, and occurrences;
- completion candidates;
- documentation and navigation targets;
- language diagnostics;
- generation, source version, classpath, options, and best-effort TASTy freshness.

Consumers cannot observe the compiler implementation, presentation-compiler adapter, or bundled bridge. Snapshot work
runs off the EDT and publication is atomic.

**Gate:** stale and mismatched generations cannot be queried as current, and every semantic result can be traced to
one exact snapshot identity.

### 16. Cut the type role over atomically

Route active Scala 3 expression, definition, member, expected-type, conformance, widening, substitution, and rendering
queries through the facade. Reconcile rendering at the bridge boundary while preserving exact compiler meaning.

Delete in the same role cutover:

- `CompilerTypeRequestResolver`;
- compiler-type topic and slot population owned by Metallurgy;
- initializer slot writes from inlay passes;
- superseded type side tables and compatibility paths.

Active non-current states return explicit unknown and never invoke bundled inference.

**Gate:** exact type expectations pass across copied tests and broad type-level examples, with no substring
assertions.

### 17. Cut symbols and resolve over atomically

Route lexical, qualified, inherited, contextual, extension, synthetic, compiled, and cross-module resolution through
compiler symbols and occurrences. Compatibility PSI provides stable IntelliJ identities where no source PSI exists.

Delete the superseded resolve/light-symbol adapters for this role in the same commit.

**Gate:** resolve, rename, usages, pointers, and compiled/source navigation pass for current snapshots; missing and
stale snapshots never resolve through bundled inference.

### 18. Cut completion over atomically

Make compiler completion the exclusive candidate source for active Scala 3 modules and adapt candidates to IntelliJ
lookup elements. Preserve insertion behavior and editor contracts without admitting bundled semantic candidates.

Delete `PcCompletionMerger` and the superseded contributor behavior in the same commit.

**Gate:** copied completion expectations, broad contextual cases, incomplete edits, and cross-module best-effort TASTy
completion pass without duplicate or bundled-only candidates.

### 19. Cut navigation roles over atomically

Replace documentation, declaration navigation, usages, light declarations, and related semantic consumers. A native
PSI implementation remains only after an executable probe proves its semantic entry point routes through the facade;
otherwise the compatibility bridge owns it.

Delete each superseded adapter when its complete role activates.

**Gate:** source, compiled, synthetic, and cross-module targets remain stable across edit, retypecheck, and restart.

### 20. Cut diagnostics over atomically

Install one diagnostic broker:

- dotc owns Scala language errors and compiler warnings;
- explicitly classified IDE-only inspections remain;
- semantic inspections consume the compiler facade;
- duplicate bundled semantic findings have no active producer.

Delete in the same commit:

- `PcDiagnosticSetCache`;
- `PcHighlightRenderer`;
- duplicate diagnostic pass wiring;
- remaining bundled semantic diagnostic routes for active Scala 3 files.

**Gate:** valid code has no error or warning highlight, invalid code exposes dotc findings, IDE-only inspections retain
their classified behavior, and every visible finding has exactly one owner.

### 21. Graduate best-effort TASTy semantics

Run the two-module build-produced break-consume-repair scenario through the exclusive semantic facade. Treat producer
and consumer support as independent capabilities. Key artifacts and sessions by module output/classpath generation so
stale best-effort files cannot win.

Every environment exposing both capabilities must prove:

- a broken upstream module emits best-effort output through IntelliJ's build;
- valid upstream declarations remain resolvable and completion-visible downstream;
- downstream highlighting contains no false findings;
- unavailable upstream members retain honest unknown/error provenance;
- repair, rename, removal, clean rebuild, project reload, and restart refresh downstream state.

**Gate:** the same editor assertions pass in focused fixtures and the selected Cats Effect and FS2 multi-module
break-repair scenarios.

### 22. Graduate rolling compiler, host, and project admission

Completion requires all of the following:

- every compiler-valid copied Scala 3 test passes with unchanged executable content;
- independently proven compiler conflicts execute and report separately as non-passes;
- invalid-state recovery and editor-operation lanes pass;
- catalog, accessor, stub, index, and runtime-invocation accounting are complete;
- every compiler artifact in the declared rolling support matrix passes the baseline parser and semantic capability
  contract;
- newly published artifacts with covered inventories and contracts admit without production-code changes;
- deliberate parser, semantic, and host drift fails closed and identifies the missing bridge, grammar role, output
  role, semantic role, or compatibility binding;
- representative pull-request Scala versions and stable/EAP/nightly hosts pass their applicable roles;
- all pinned real-project offline slices pass;
- exhaustive source-wide and live sbt/BSP real-project lanes pass;
- Cats Effect and FS2 pass build-produced best-effort TASTy break-repair;
- resource budgets, clean restart, packaging, formatting, and fatal-warning checks pass.
- preparing, unsupported syntax/output roles, unavailable or stale semantics, and asymmetric best-effort capabilities
  expose deterministic project/file capability UX naming the exact compiler artifact and host, missing capability or
  stable role, affected scope, retained operations, and evidence/remediation location.

The IntelliJ Scala plugin and Scala compiler repositories are never built by these lanes.

## Required commands

The deterministic test work adds named tasks or scripts for the following lanes. Every bounded build or test command
uses the repository JBR:

```sh
JBR=~/.metallurgyPluginIC/sdk/261.26222.65/jbr/Contents/Home

/opt/homebrew/bin/gtimeout --kill-after=5s 120s \
  env JAVA_HOME="$JBR" PATH="$JBR/bin:$PATH" \
  sbt -batch -no-colors scalafmtAll

/opt/homebrew/bin/gtimeout --kill-after=5s 120s \
  env JAVA_HOME="$JBR" PATH="$JBR/bin:$PATH" \
  sbt -batch -no-colors scalafmtCheckAll
```

The final task names must cover, without wildcard discovery:

```text
testCopiedScala3Parity
testScala3PsiLifecycle
testScala3StubsAndIndices
testScala3EditorOperations
testScala3SemanticRoles
testScala3BetastyHighlighting
testScala3PublishedVersions
testScala3HostMatrix
testScala3RealProjectsOffline
testScala3RealProjectsFull
```

Each task retains its reports and logs and fails if expected invocations are absent. Scheduled lanes may use longer
explicit timeouts justified by their measured runtime.

## Completion evidence

The implementation is complete only when a clean checkout can reproduce every required lane and the retained evidence
proves:

- source fidelity;
- complete runtime invocation;
- exact compiler artifact and host identity;
- production-catalog completeness;
- deterministic AST, stub, and index signatures;
- compiler-exclusive semantic ownership;
- absence of false editor findings;
- build-produced cross-module best-effort TASTy recovery;
- successful real-project and moving-capability graduation.

Passing a narrower unit suite, retaining an unexercised fallback, or failing to account for a copied test is not
completion.
