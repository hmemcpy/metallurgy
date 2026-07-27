# Metallurgy: deterministic Scala 3 PSI and compiler semantics

This document is the sole normative architecture for Metallurgy. Package instructions may add local constraints but
must not define another data flow or ownership model.

## 1. Product contract

For every opted-in Scala 3 module, Metallurgy:

1. loads the module's exact Scala 3 compiler artifacts in isolation;
2. parses each source file with that compiler's parser;
3. synchronously produces one complete, source-compatible IntelliJ Scala PSI tree;
4. derives deterministic stubs and indices from that tree;
5. publishes one current-generation compiler semantic snapshot;
6. supplies Scala 3 types, symbols, resolution, completion, navigation, and language diagnostics from that snapshot;
7. preserves downstream editor semantics for valid declarations in broken upstream modules when the exact compiler
   exposes best-effort TASTy production and consumption.

The existing IntelliJ Scala 3 tests define the required PSI behavior for every compiler-valid fixture. Their Scala
source, executable assertions, and expected results remain unchanged. Dotc defines language validity and semantic
meaning for the exact Scala version under test.

The central user-visible invariant is:

> Compiler-valid Scala 3 code has no false error or warning highlight.

No highlight filter, diagnostic suppression, bundled semantic fallback, feature allowlist, or test-specific behavior
may be used to satisfy that invariant.

## 2. Scope and activation

### 2.1 Module gate

`ModuleDetectionService.isActive(module)` is true only when:

- the module is Scala 3; and
- the user enabled Metallurgy for that module.

Metallurgy's gate is independent of the bundled plugin's compiler-highlighting setting. An inactive module uses the
installed Scala plugin unchanged and must allocate no Metallurgy parser session, compiler session, cache entry, or
background task.

Scala 2 semantic replacement is outside this architecture.

### 2.2 Exact compiler identity

Each active module is associated with:

- Scala compiler artifact coordinates;
- compiler options;
- source and generated-source roots;
- dependency and output classpaths;
- upstream-module relationships;
- module-model, options, classpath, and output generations.

Compiler and host versions are artifact and diagnostic identities. They never select behavior. Metallurgy discovers
operations as executable capabilities.

The baseline host is IntelliJ `261.26222.65` with Scala plugin `2026.1.20`; it is a development reference, not a
semantic limit. Any published Scala 3 compiler is eligible when its artifacts can be acquired and its required parser
and semantic capabilities are discovered.

### 2.3 Capability states

Capabilities are independently observed. At minimum the system distinguishes:

- exact parser construction;
- source and position extraction;
- typed whole-file snapshot extraction;
- presentation-compiler completion;
- best-effort TASTy production;
- best-effort TASTy consumption.

An optional capability cannot enable or disable another. In particular, best-effort TASTy availability never selects
the source grammar or PSI shape.

## 3. Whole-file syntax architecture

### 3.1 Syntax authority

The exact compiler's parser tree is the syntax authority. Typed trees are not syntax input: typing may desugar
surface constructs, erase punctuation and clause distinctions, introduce synthetic definitions, and move ownership.

For a ready module, every IntelliJ parse request follows one deterministic path:

```text
verbatim source
  -> exact compiler parser
  -> neutral parser products and positions
  -> lossless source-evidence plan
  -> typed PSI production catalog
  -> complete PsiBuilder plan
  -> whole-file Scala AST
  -> Scala PSI
  -> stubs and indices
```

The parser path is synchronous. It never waits for typer, presentation-compiler work, artifact download, an EDT task,
or a previously published semantic snapshot.

One walk produces one whole-file tree. No background task may replace a syntax tree or install a different stub spine
for the same file content.

### 3.2 Parser preparation lifecycle

Exact compiler artifacts and parser access are prepared before the module's files become Scala parser clients. Each
module epoch has three syntax states:

1. **Preparing** — artifacts and required parser capabilities are being acquired.
2. **Activating** — the ready parser state is published and one platform VFS reparse batch is queued.
3. **Ready** — active files parse synchronously through the exact compiler bridge.

Preparing and Activating files use an unrelated neutral language and associated neutral file type. The neutral
language has:

- no Scala base language;
- no Scala lexer or parser;
- no references or declarations;
- no stubs or indices;
- no inherited Scala annotators, inspections, completion, or refactoring extensions.

Its file content is verbatim. File, document, and text-range identity survive activation; PSI identity does not cross
the language boundary.

Activation uses `FileContentUtilCore.reparseFiles` for one platform-managed batch. It does not simulate dumb mode,
reload a view provider directly, or request indices manually. A completed preparation may activate only the module
epoch that requested it.

### 3.3 Exact parser bridge

Compiler implementation access is isolated behind `Scala3ParserBridge`. The bridge accepts neutral input:

- exact artifact classloader;
- source URI and verbatim text;
- compiler options relevant to parsing;
- cancellation.

It exports only immutable neutral values:

```scala
final case class ParserSnapshot(
    sourceUri: SourceUri,
    sourceText: String,
    root: ParserProduct,
    diagnostics: Vector[ParserDiagnostic],
    capabilities: ParserCapabilities,
    compilerIdentity: CompilerIdentity
)

final case class ParserProduct(
    production: String,
    fields: Vector[ParserField],
    span: SourceSpan,
    point: Int,
    positionKind: PositionKind
)
```

The precise DTO vocabulary may differ, but it must preserve ordered named fields, nesting, ranges, point positions,
zero-width and synthetic provenance, parser diagnostics, and compiler identity. No dotc class or collection crosses
the classloader boundary.

Access order is:

1. published compiler or Scalameta interfaces;
2. typed structural protocols;
3. isolated raw reflection where construction cannot be expressed structurally.

The bridge probes callable shapes. It does not branch on version strings, bytecode fingerprints, or implementation
class allowlists. Older compiler artifact layouts adapt to the same neutral contract.

### 3.4 Lossless source evidence

Parser products alone do not own every source character. `SourceEvidencePlan` combines verbatim source with positioned
parser evidence and accounts for:

- significant tokens;
- whitespace;
- line and block comments;
- documentation comments;
- delimiters and separators;
- indentation, outdent, and other zero-width layout events;
- missing and recovery positions;
- synthetic positions;
- source intervals not represented as standalone parser nodes.

Every source interval has exactly one owner. Ownership cannot overlap. Reassembling the ordered leaves equals the
original source byte for byte.

Scanner replay may validate or enrich evidence, but it cannot choose the production hierarchy. A plan with an
unaccounted or multiply owned interval is invalid.

### 3.5 Production catalog

`Scala3PsiProductionCatalog` is the only grammar-to-PSI mapping authority. It is a reviewed typed data model generated
from and checked against two inventories:

- exact compiler parser productions and named fields;
- installed Scala PSI element types, public `Sc*` accessors, implementations, stubs, serializers, and indices.

Each catalog entry declares:

- the compiler production and accepted capability shape;
- source, token, trivia, delimiter, and layout ownership;
- required, optional, repeated, and recovered fields;
- child ordering and parent requirements;
- the native PSI target probe or compatibility target;
- every public Scala PSI accessor the result must satisfy;
- stub fields, serializer identity, indices, and navigation identity;
- behavior for compiler-valid source, invalid edits, and an unknown production.

The catalog compiler creates a closed whole-file production plan before any `PsiBuilder` marker is opened. Validation
rejects:

- an uncovered compiler production or named field;
- an unowned source interval;
- overlapping source ownership;
- a missing required child;
- a PSI accessor with no structural source;
- an incomplete stub or index contract;
- an unbalanced or order-dependent builder plan.

A newly published Scala syntax feature enters through capability and inventory discovery followed by a complete catalog
entry. Parser-error messages, version checks, and isolated construct patches are not grammar mechanisms.

### 3.6 Native and compatible PSI

The installed Scala plugin remains the public PSI vocabulary. Metallurgy prefers its element types and implementations
when executable probes demonstrate the complete catalog contract.

A probe verifies observable behavior:

- construction through the installed AST factory;
- expected public interface;
- direct-child and parent shape;
- named and typed accessors;
- visitor behavior;
- stub construction and serialization;
- index and navigation identity.

When no native production satisfies the contract, `ScalaPluginSemanticBridge` owns a source-compatible PSI and stub
implementation. Consumers receive ordinary public IntelliJ and Scala PSI interfaces and cannot observe how a target
was supplied.

Raw Scala-plugin implementation access is confined to this bridge. Production consumers do not inspect plugin versions
or implementation class names.

### 3.7 AST, stubs, and indices

The producer emits a complete balanced AST. Composite PSI uses balanced `PsiBuilder.Marker.done(elementType)` calls;
visual resemblance from a leaf or collapsed node is insufficient.

`doParseContents` returns `builder.getTreeBuilt.getFirstChildNode`, matching
`ILazyParseableElementType.doParseContents`. Returning the wrapper root nests an extra file node and hides top-level
declarations from `ScDeclarationSequenceHolder.processDeclarations`.

The file element uses an owned `ScStubFileElementType` identity and explicit schema version. `DefaultStubBuilder`
derives stubs only after the whole AST exists. Stub serializers and indices are registered statically.

For identical file content, catalog, compiler parser capability, and stub schema, all of these are deterministic:

- AST element types, ranges, and parent/child order;
- public accessor observations;
- stub type, fields, and order;
- serialized stub bytes;
- index keys and targets;
- pointer and navigation identity.

The same contract applies to cold parse, warm parse, closed files, copies, edits, reparse, restart, and index rebuild.

### 3.8 Invalid edits and unknown productions

Invalid intermediate edits retain the exact source, parser diagnostics, and a structurally safe recovery tree described
by the catalog. Recovery cannot claim that invalid code is compiler-valid.

An unknown required production in compiler-valid source fails closed:

- the file uses deterministic neutral file-scoped PSI;
- no partial Scala tree or stub is published;
- a project-level capability report names the missing production and exact compiler identity;
- no bundled Scala parse is substituted.

This state is a compatibility failure to implement, not a reason to hide diagnostics or manufacture semantic results.

## 4. Compiler semantic architecture

### 4.1 Whole-file semantic snapshot

`PcSessionManager` owns per-module exact-version sessions. Scalameta's published `scala.meta.pc` operations are used
where they expose the required semantics. A capability-probed `Scala3PcBridge` may structurally access the retained
driver when no public operation exports a required whole-file snapshot.

Only neutral immutable DTOs cross the compiler boundary:

```scala
final case class SemanticSnapshot(
    key: SemanticSnapshotKey,
    types: Vector[CompilerTypeEntry],
    symbols: Vector[CompilerSymbol],
    occurrences: Vector[CompilerOccurrence],
    completions: CompletionIndex,
    navigation: Vector[CompilerNavigationTarget],
    diagnostics: Vector[CompilerDiagnostic],
    status: SemanticStatus
)

final case class SemanticSnapshotKey(
    moduleId: ModuleId,
    fileUri: SourceUri,
    documentVersion: DocumentVersion,
    sourceHash: SourceHash,
    compilerIdentity: CompilerIdentity,
    sessionGeneration: SessionGeneration,
    classpathGeneration: ClasspathGeneration,
    optionsGeneration: OptionsGeneration,
    moduleModelGeneration: ModuleModelGeneration,
    upstreamOutputGeneration: UpstreamOutputGeneration
)
```

The exact data model may be refined, but types, symbols, occurrences, diagnostics, compiler identity, and all freshness
generations publish atomically. URI, module, source-hash, compiler, and generation identities use distinct domain
types; interchangeable strings are not valid snapshot keys.

### 4.2 Semantic facade and states

All IntelliJ consumers use one role-based `CompilerSemanticFacade`. They do not know whether a neutral value came from
a public PC operation or a structural compiler bridge.

For a query, the facade observes one state:

- **Current** — the snapshot key exactly matches the active file and module generations;
- **Pending** — work for the current key is in flight;
- **Unavailable** — required compiler capability or artifact is absent;
- **Failed** — current work terminated with an explicit failure;
- **Missing** — no snapshot exists;
- **Stale** — a snapshot exists for another generation;
- **Inactive** — the module gate is false.

Only Current supplies active Scala 3 semantics. Pending, Unavailable, Failed, Missing, and Stale return explicit unknown
or not-ready values. They never invoke bundled Scala type inference, resolution, completion, or semantic diagnostics.
Inactive executes the installed plugin unchanged.

No synchronous PSI or editor query starts compiler work and waits.

### 4.3 Type model and rendering

The facade carries a structured neutral type algebra rather than treating rendered text as semantic identity. It
preserves:

- named, applied, parameter, singleton, path-dependent, and projection types;
- unions, intersections, refinements, match types, type lambdas, and bounds;
- method, context-function, dependent-function, and polymorphic-function types;
- aliases, opaque types, captures, annotations, constants, wildcards, and error provenance;
- symbol identity and substitution relationships.

Rendering is a boundary concern. Dotc's internal display text may not be valid Scala source or may differ from the
installed PSI's expected presentation. The bridge normalizes only presentation while preserving compiler meaning.

Examples include widening a term reference before ordinary type rendering and reconstructing stable binder names for
polymorphic function values. A rendering used to create `ScType` must round-trip through
`ScalaPsiElementFactory.createTypeFromText`; failure remains explicit and never becomes `Any`.

### 4.4 Symbols and resolution

Compiler symbols are stable neutral identities scoped by semantic generation. The facade supplies:

- lexical and qualified references;
- inherited and overridden members;
- contextual parameters and givens;
- extension methods;
- synthetic and compiler-generated declarations;
- source, compiled, and best-effort-loaded symbols;
- definitions and occurrences for rename and find usages.

Source-backed symbols map to physical PSI through file URI, exact source/name ranges, owner identity, and generation.
Compiler-only declarations use minimal stable compatibility PSI keyed by compiler symbol and generation. Navigation
targets the nearest physical source or compiled owner.

### 4.5 Completion

Compiler completion is the exclusive semantic candidate source for active ready Scala 3 modules. An IntelliJ adapter
converts candidates to lookup elements while preserving:

- lookup and display names;
- insertion text and edits;
- import and qualification behavior;
- type parameters and parameter lists;
- deprecation and relevance metadata;
- source or compiled navigation identity.

Bundled semantic candidates are not merged into the active result.

### 4.6 Documentation, navigation, and usages

Documentation, declaration navigation, implementation navigation, usages, rename, and other symbol consumers use the
same compiler identities and occurrences.

A native Scala PSI implementation may remain only when an executable probe demonstrates that its semantic entry point
routes through the facade. Otherwise the compatibility bridge owns that role.

### 4.7 Diagnostics and inspections

Diagnostic ownership is explicit:

- dotc owns Scala language errors and compiler warnings;
- IDE-only inspections own editor concerns that the compiler does not define;
- semantic inspections consume the compiler facade;
- syntax recovery exposes exact parser diagnostics for invalid edits.

Every visible finding has one owner. No `HighlightInfoFilter` suppresses a result. No duplicate bundled semantic
annotator remains active for an owned role.

During parser preparation, the file is neutral and Scala diagnostics do not run. This is a language transition, not
diagnostic filtering. Once Ready, all actual current diagnostics are visible.

## 5. Best-effort TASTy and cross-module highlighting

Best-effort TASTy addresses cross-module editor semantics when an upstream Scala 3 module is broken.

The exact compiler may expose two independent capabilities:

- a full compiler run accepts `-Ybest-effort` and writes `.betasty` under `META-INF/best-effort`;
- a downstream compiler or PC accepts `-Ywith-best-effort-tasty` and reads those artifacts.

The interactive presentation compiler cannot produce the artifact because its phase plan does not run the pickler.
IntelliJ's ordinary build and compile-server pipeline is therefore the authoritative producer. Downstream sessions add
the best-effort directory as a classpath root only when the consumer capability is present.

Best-effort TASTy never participates in current-file syntax production. It is a generation-matched semantic input for
downstream modules.

Artifact and session freshness includes:

- upstream module output generation;
- classpath generation;
- artifact path, content, and compiler identity;
- producer and consumer capability identities.

A rewrite may preserve file size and timestamp, so metadata alone is not a sufficient freshness key.

Every environment exposing both capabilities must preserve this sequence:

1. a broken upstream module reports its real compiler errors and emits best-effort artifacts;
2. valid upstream declarations remain resolvable, navigable, and completion-visible downstream;
3. downstream code has no false error or warning highlight;
4. unavailable upstream declarations retain honest error or unknown provenance;
5. repair, rename, removal, rebuild, reload, and restart invalidate stale downstream semantics.

Cats Effect and FS2 provide the final multi-module break-consume-repair checks because their type-level APIs exercise
cross-module symbol and type behavior beyond small fixtures.

## 6. Threading, caching, and publication

### 6.1 Thread ownership

- Parsing is synchronous on the caller's parse/index thread and cannot wait for background or EDT work.
- Artifact acquisition runs in a cancelable background task.
- Compilation, mapping, and snapshot construction run off the EDT.
- IntelliJ PSI reads occur under read access.
- PSI-visible publication and daemon restart use the appropriate platform write/EDT boundary.
- Production timing uses futures, latches, alarms, and cancellation; never `Thread.sleep`.

### 6.2 Cache identity

Syntax caches are optional optimizations keyed by exact source content, compiler parser identity, options affecting
parsing, catalog version, and target PSI capability identity. Cache hits and misses produce identical AST and stub
observations.

Semantic queries are keyed by file URI and document version plus every module/session freshness generation. A reused
display filename is not an identity.

### 6.3 Publication

Semantic publication:

1. captures immutable compiler input outside the daemon read action;
2. coalesces duplicate work for the same key;
3. builds one neutral snapshot off the EDT;
4. revalidates every key field;
5. atomically commits the snapshot;
6. restarts only affected files;
7. discards stale results without side effects.

Edits retire the current snapshot immediately. Opt-out, module removal, classpath change, options change, project
reload, and upstream output change invalidate the corresponding sessions and snapshots.

## 7. IntelliJ consumer ownership

| Consumer | Target ownership |
| --- | --- |
| Scala file parsing | Exact compiler parser plus production catalog |
| Scala PSI accessors | Native or compatibility PSI satisfying catalog contracts |
| Stubs and indices | Complete produced AST through owned file-stub root |
| Expression and definition types | Compiler semantic facade |
| Expected types, conformance, substitution | Compiler semantic facade |
| Reference resolution and binding | Compiler symbols and occurrences |
| Completion | Compiler completion adapter |
| Documentation and quick navigation | Compiler type/symbol facade |
| Find usages and rename | Compiler occurrences plus stable PSI identity |
| Inlay and type hints | Current compiler type snapshot |
| Scala language errors and warnings | Dotc diagnostics |
| IDE-only inspections | Explicitly classified IntelliJ inspections |
| Worksheets backed by physical Scala files | Same syntax and semantic contracts |
| Debugger fragments and interactive consoles | Separate capability work; never treated as a physical source snapshot |
| Build and execution transport | Installed IntelliJ/Scala infrastructure |
| Broken upstream module recovery | Build-produced, generation-matched best-effort TASTy |

Extension points are preferred when they implement complete ownership. Where IntelliJ or the Scala plugin exposes no
adequate extension point, adaptation remains isolated in `ScalaPluginSemanticBridge`. Partial interception that permits
a conflicting bundled semantic result is not an implementation of the role.

## 8. Compatibility boundaries

### 8.1 Compiler boundary

`Scala3ParserBridge` and `Scala3PcBridge` are the only modules that may access exact compiler implementations. Their
structural implementations are private. They export neutral DTOs, capability results, and diagnostics.

### 8.2 Scala-plugin boundary

`ScalaPluginSemanticBridge` is the only module that may:

- instantiate or wrap private Scala-plugin PSI implementations;
- provide compatibility PSI or stubs;
- adapt native semantic entry points;
- perform capability-probed private access when published extension points are insufficient.

Consumers depend on public role interfaces, not adapter identities.

### 8.3 Failure behavior

A failed optional capability disables only its own operation. A failed required syntax or semantic capability is
reported explicitly for the affected module/file and does not select a bundled active-module result.

Inactive modules remain exact pass-through behavior.

### 8.4 Ownership transitions

New syntax and semantic components may be exercised through direct tests while they remain unregistered. Active
runtime ownership never has parallel old and new routes.

Syntax changes in one atomic boundary: register the exact-parser/catalog path and neutral lifecycle while deleting the
asynchronous syntax handoff, placeholder, bundled-parser decision path, publication hooks, and mechanism-specific
helpers and tests in the same change.

Semantic ownership changes at complete IntelliJ role boundaries:

1. types and rendering;
2. symbols and resolution;
3. completion;
4. documentation, navigation, and usages;
5. diagnostics and semantic inspections.

Each role cutover activates its compiler-facade route and deletes the implementation, side table, adapter, extension
registration, and tests that exist only for the replaced route in the same change. Disabled remnants and compatibility
switches are not retained. Diagnostics change last because their semantic inspections depend on the preceding roles.

## 9. Verification contract

### 9.1 Copied IntelliJ tests

Pinned upstream test sources are owned locally and compiled by a local harness. The harness mechanically verifies:

- executable method bodies;
- Scala snippets;
- assertions;
- expected outputs;
- inherited fixture helper contracts;
- complete runtime invocation.

Local class names, descriptive test names, setup, and execution plumbing may differ. External tracker identifiers live
only in the provenance manifest.

A compiler-valid upstream test must pass unchanged. An assertion that contradicts the exact compiler remains
verbatim, executes visibly, is independently proven against that compiler, and reports separately as a non-pass.

### 9.2 Syntax and PSI

Tests cover:

- exact source round-trip;
- parser product and catalog coverage;
- element types, ranges, and direct-child order;
- every declared public `Sc*` accessor;
- native-versus-compatible targets;
- invalid-edit recovery;
- cold/warm parse, copy, edit, reparse, and restart;
- complete stub and index signatures;
- pointers, navigation, rename, usages, and closed-file behavior.

Broad nested examples complement minimized upstream cases.

### 9.3 Semantics and diagnostics

Each active role proves:

- exact types, not substring matches;
- compiler symbol identity and resolution;
- completion content and insertion behavior;
- navigation and occurrence stability;
- absence of bundled fallback in non-current states;
- one-owner diagnostics;
- no false error or warning on compiler-valid source;
- visible compiler errors and warnings on invalid source.

### 9.4 Version and host movement

Ordinary checks use the baseline host and a representative exact-compiler selection. Scheduled checks cover:

- every published final Scala 3 artifact;
- representative pull-request Scala versions;
- stable, EAP, and nightly IntelliJ/Scala-plugin hosts;
- transitions where a production moves from compatibility PSI to native PSI;
- every capable best-effort TASTy cell.

Drift appears as capability and contract evidence, never as a production version switch.

### 9.5 Real projects

Pinned offline slices run in ordinary CI. Scheduled source-wide and live sbt/BSP lanes cover six release-aligned
projects, including Cats Effect, FS2, and Shapeless-style type-level machinery.

Graduation checks exact diagnostics, representative types and symbols, editor operations, resource budgets, restart,
and multi-module best-effort TASTy behavior. The IntelliJ Scala plugin and Scala compiler repositories are not built.

## 10. Completion

The architecture is implemented only when:

- every compiler-valid copied Scala 3 test passes unchanged;
- every copied execution is accounted for;
- compiler conflicts remain visible and independently proven;
- parser, catalog, accessor, recovery, stub, index, and editor-operation contracts pass;
- active semantic roles are compiler-exclusive;
- valid Scala 3 has no false error or warning highlight;
- capable best-effort TASTy environments pass build-produced cross-module break-consume-repair;
- published-version, moving-host, and real-project lanes pass;
- formatting, fatal warnings, packaging, resource, and clean-restart gates pass.

A partial semantic overlay, a hidden diagnostic, a retained active-module fallback, or an unaccounted copied test does
not meet this contract.
