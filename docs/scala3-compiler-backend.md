# Metallurgy: deterministic Scala 3 PSI and compiler semantics

This document is the sole normative architecture for Metallurgy. Package instructions may add local constraints but
must not define another data flow or ownership model.

## 1. Product contract

Metallurgy makes IntelliJ use the exact Scala 3 compiler that builds a project without giving up an editable IntelliJ
source model. The Scala 3 compiler is called **dotc**. Its answers define whether Scala 3 code is valid and what that
code means.

Several terms recur in this document:

- A **parser** reads source text and records its grammatical structure. Its result is a **syntax tree**: nested nodes
  such as a definition, call, argument list, and name, each tied to an exact source range.
- **PSI**, short for program structure interface, is IntelliJ's editable syntax tree. Editor features query and change
  PSI rather than reading raw text directly.
- **Semantic meaning** covers facts that syntax alone cannot answer, such as the type of an expression or the
  declaration named by a reference.
- **Physical ownership** means which component creates the one real PSI node that occupies a source range. Two
  overlapping trees cannot both own the same text.
- A **snapshot** is an immutable result for one exact source version and compiler session. Later edits make it stale.
- A **capability probe** asks the running compiler or installed plugin what operations and PSI shapes it actually
  provides. Artifact versions identify the environment but never choose behavior.
- A **fallback** is a lower-priority provider used only when the preferred provider cannot answer one exact role.
- An **opaque expression** is one PSI expression whose text is exact but whose internal expressions and punctuation
  are not exposed as rich child PSI yet.
- A **stub** is a compact saved summary of declarations. An **index** maps names and other keys from stubs to files so
  IntelliJ can search without opening every file. A **reparse** rebuilds PSI after an edit.

For every opted-in Scala 3 module in a supported compiler and IntelliJ host combination, Metallurgy:

1. loads the module's exact Scala 3 compiler artifacts in isolation;
2. parses each source file with that compiler's parser;
3. synchronously produces one complete, source-compatible IntelliJ Scala PSI tree;
4. derives deterministic stubs and indices from that tree;
5. publishes one current-generation compiler semantic snapshot;
6. supplies Scala 3 types, symbols, resolution, completion, navigation, and language diagnostics from that snapshot;
7. preserves downstream editor meaning for valid declarations in broken upstream modules when the exact compiler
   exposes best-effort TASTy, compiler data that retains usable declarations after a module fails to compile fully.

The existing IntelliJ Scala 3 tests define the required PSI behavior for every compiler-valid example. Their Scala
source, executable assertions, and expected results remain unchanged. Dotc defines language validity and semantic
meaning for the exact Scala version under test.

Opt-in outside a supported combination retains explicit capability states. Unknown syntax stays neutral or opaque,
and unknown semantic meaning stays unknown. Opt-in does not promise support for unseen grammar, PSI roles, semantic
contracts, or host bindings.

The central user-visible invariant is:

> Compiler-valid Scala 3 code has no false error or warning highlight.

No highlight filter, diagnostic suppression, bundled semantic fallback, feature allowlist, or test-specific behavior
may be used to satisfy that invariant.

### 1.1 From source text to editor answers

The current pipeline has one physical source tree and a separate semantic snapshot:

```text
exact source text
  -> exact dotc parser snapshot
     (tree ranges, scanner tokens, comments, and parser diagnostics)
  -> complete source evidence
  -> reviewed production catalog and whole-file plan
  -> one Metallurgy AST and PSI tree
  -> stubs, indices, and editor features

exact source text + classpath + compiler options
  -> immutable dotc semantic snapshot
  -> types, symbols, reference targets, completion, navigation, and diagnostics
  -> mapped onto that same physical PSI tree
```

An **AST**, or abstract syntax tree, is IntelliJ's lower-level tree beneath PSI. The production catalog is a reviewed
table that says how dotc parser shapes become IntelliJ PSI roles and who owns every source byte. The two paths meet by
exact file identity, source ranges, document version, and compiler generation. Semantic work never changes syntax.

The design admits providers in this strict order:

1. use a current dotc answer and proven Metallurgy PSI;
2. use a narrow Metallurgy family adapter where dotc does not expose enough concrete ownership evidence;
3. only then consider a capability-probed installed Scala plugin fallback for that exact role.

Today active modules use only the first two levels. Installed-plugin semantic fallback is not enabled. A current dotc
reference result with no target means “no target.” Pending, missing, failed, unavailable, or stale dotc reference state
means “unknown.” Neither case runs installed-plugin reference resolution. Inactive modules use the installed Scala
plugin unchanged.

### 1.2 Current expression coverage

Metallurgy already creates structured PSI for these direct definition right-hand sides. Most listed roles use installed
native PSI. Named type arguments use a small Metallurgy compatibility role because the installed plugin has no matching
public role:

- atomic references, literals, and `this` expressions;
- selection chains such as `source.member.next`;
- ordinary calls such as `f(x)` and nested or curried forms;
- explicit `using` calls such as `f(using context)`;
- positional and named type-argument lists inside an otherwise opaque expression.

The last item is deliberately narrow. For example, `pair[A = Int](1, "text")` is one opaque expression with a rich
`[A = Int]` child island. The pinned IntelliJ 261 Scala plugin has no public `ScTypeArgument` role for one named type
argument, so Metallurgy supplies a small compatible child. It does not create an active `ScGenericCall` shell. A
simple positional type-application shell has exact parser evidence and tests, but is not active yet.

The following expression families are still opaque or blocked until their own bounded family work proves complete
ownership: braced blocks; control flow, including end-marker forms; term named arguments; repeated or spliced
arguments; the wider positional type-application and generic-call shell; tuples; lambdas and context functions; infix
expressions; constructors and `new`; local definitions; default arguments; template parent applications; and
definition bodies inside refinements. Parser probes or negative tests exist for these boundaries, but they are not
active rich PSI support.

For example:

```scala
val configured = open(path = root) // term named argument
val computed = { val n = read(); n + 1 } // braced block
```

If Metallurgy cannot prove who owns every name, parenthesis, equals sign, comment, space, and recovery event, the whole
right-hand side remains one exact-source expression payload. Dotc can still provide the type and other semantic facts
for that range. Rich child PSI waits for the family adapter; the editor does not guess a tree.

Missing tests are added with the bounded family that needs them. Broad speculative tests do not stand in for an
implemented family contract.

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
semantic limit. Any published Scala 3 compiler is eligible for support. A compiler and IntelliJ host combination is
supported only when its discovered parser and semantic inventories, output-role contracts, and host bindings are
covered; acquiring artifacts and discovering callable capabilities alone does not admit unseen grammar or semantics.

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

Runtime probes check callable methods, parser definitions, PSI element types, public accessors, and required tree
shape. Their result includes a capability signature. The shadow witness returns `Unavailable` when a caller's expected
signature differs. Before a capability-probed fallback becomes active, host change and restart must rebuild and check
its prepared binding before it can emit PSI. A missing or conflicting probe result is `Unavailable`; it never selects
behavior from a compiler or plugin version number.

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

Terminal and wrapper output roles may refine provisional source atoms only through reviewed generic interval contracts
declared in the production catalog. A contract identifies one provisional atom by stable identity and unchanged
half-open interval in the source-evidence coordinate system, identifies the stable terminal or wrapper output role
requesting refinement, and declares an ordered replacement partition. Replacement intervals are non-empty,
contiguous, contained by and exactly cover the original atom in source order, and retain its evidence claims until
final ownership validation. A new cut must already be an evidence boundary or be proven safe by the same closed
lexical contract used to build the immutable lexer tape; matching source text alone is not proof. Applying a contract
atomically withdraws the original atom and installs its partition without changing source text or order.

Zero-width events are assigned by stable evidence identity, not offset alone, so co-located events remain distinct.
Final validation assigns exactly one owner to every source byte and zero-width event. An unknown atom, role, or event;
an unsafe boundary; a non-contiguous or incomplete partition; or overlapping, multiply claimed, or unowned evidence
invalidates the whole-file plan before lexer-tape construction, `PsiBuilder` creation, or physical emission.

Scanner replay may validate or enrich evidence, but it cannot choose the production hierarchy.

### 3.5 Production catalog

`Scala3PsiProductionCatalog` is the only grammar-to-PSI mapping authority. It is a reviewed typed data model generated
from and checked against two inventories:

- exact compiler parser productions and named fields;
- installed Scala PSI element types, public `Sc*` accessors, implementations, stubs, serializers, and indices.

The catalog owns stable neutral grammar roles and PSI output roles. Compiler production names and host implementation
classes are inventory evidence, not durable role identities. Parser products and output composites do not have to map
one-to-one: one parser product may lower to several output roles, and several products may supply one output role.

Each catalog entry declares:

- the compiler production and accepted capability shape;
- the neutral grammar role and one or more stable output roles;
- source, token, trivia, delimiter, and layout ownership;
- required, optional, repeated, and recovered fields;
- child ordering and parent requirements;
- each output role's native PSI probe or compatibility binding;
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

A newly published compiler whose discovered inventory is already covered requires no grammar production-code change.
Novel syntax enters through capability and inventory discovery followed by bridge normalization, a grammar-role or
output-role entry, or a compatibility binding as identified by the drift. Parser-error messages, version checks, and
isolated construct patches are not grammar mechanisms.

### 3.6 Native and compatible PSI

The installed Scala plugin remains the public PSI vocabulary and an optional implementation provider. Physical syntax
follows the precedence in section 1. Missing or conflicting evidence keeps the region as one opaque expression. It
never selects a richer shape by guesswork.

A file has one authoritative physical tree. Metallurgy may lower neutral witness facts into its own whole-file plan,
but it never transplants, reparents, copies, or delegates to hidden Scala-plugin PSI or AST nodes. Stub and index roots
cannot mix providers. Whole-file Scala-plugin fallback is a separate explicit file state chosen before parsing; it is
not implemented by regional splicing.

A probe verifies observable behavior:

- construction through the installed AST factory;
- expected public interface;
- direct-child and parent shape;
- named and typed accessors;
- visitor behavior;
- stub construction and serialization;
- index and navigation identity.

Each output role binds independently. When no native production satisfies its contract,
`ScalaPluginSemanticBridge` owns a source-compatible PSI and stub implementation. Native and compatible roles may
coexist in one file. Consumers receive ordinary public IntelliJ and Scala PSI interfaces and cannot observe how a
target was supplied.

A transient **shadow witness** may parse the complete exact source through the installed plugin's ordinary Scala 3
language. “Shadow” means the parse is read-only evidence, not another physical tree. A caller supplies one exact direct
range. The bridge returns immutable neutral facts and then discards the synthetic file and its PSI:

- `Equal` means the witness roles, ranges, child order, and leaves satisfy their neutral contract, reconstruct the
  dotc-bounded source exactly, and match the supplied dotc owner, right-hand-side, and named-argument ranges.
- `Conflict` means witness evidence is missing, extra, or different, or a range, ownership, order, or resource bound is
  invalid.
- `Unavailable` means the required host parser shape, source freshness, or capability signature is absent.

The witness never moves plugin AST or PSI nodes into the Metallurgy tree and never performs a semantic query. It is
unwired from the active catalog, planner, emitter, file lifecycle, stubs, indices, and persistence. No active fallback
or ownership cutover uses it today. A changed capability signature makes a request with an older expected signature
unavailable. TASTy, SemanticDB, and dotc ranges may bound or confirm evidence but do not own current-file syntax.

Raw Scala-plugin implementation access is confined to this bridge. Production consumers do not inspect plugin versions
or implementation class names. Concrete implementation identities remain capability evidence behind the role binding;
they never become grammar dispatch keys.

### 3.7 AST, stubs, and indices

The producer emits a complete balanced AST. Composite PSI uses balanced `PsiBuilder.Marker.done(elementType)` calls;
visual resemblance from a leaf or collapsed node is insufficient.

The completed whole-file plan also supplies the immutable lexer tape used by `PsiBuilder`. Exact physical ownership
and catalogued terminal targets determine token boundaries and native token identities before the builder exists. The
registered dialect parser definition has only a closed neutral lexer/parser for platform entry points that cannot
carry a completed plan; ready parsing never instantiates the bundled Scala lexer or parser.

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

Today one Metallurgy file root, one schema, and one ownership plan drive stubs and indices. The transient shadow
witness does not participate in any of them. A future active fallback must prove the editor and lifecycle contracts
that apply to its role. If a whole-file installed-plugin fallback is ever added, it is one atomic file state with its
own persistence root. It is never mixed into a Metallurgy file or stub tree.

### 3.8 Invalid edits and unknown productions

Invalid intermediate edits retain the exact source, parser diagnostics, and a structurally safe recovery tree described
by the catalog. Recovery cannot claim that invalid code is compiler-valid.

An unknown required production, output role, or binding in compiler-valid source stops before partial Scala PSI is
published:

- the file uses deterministic neutral file-scoped PSI;
- no partial Scala tree, stub, or index is published;
- a project/file capability report names the exact compiler artifact and host, missing parser capability or stable
  role, affected scope, retained operations, and evidence/remediation location;
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

Reference resolution already enforces this order. A current target wins over an installed-plugin target. A current
answer with no target stays empty. Every non-current active state returns unknown. Other semantic roles still require
their own complete cutovers before any narrow installed-plugin fallback can be admitted.

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

Dotc is the ultimate semantic authority. Reference resolution now enforces dotc-first precedence. A current dotc answer
wins; current-no-target remains empty; and pending, unavailable, stale, failed, missing, or conflicting evidence stays
unknown. A future role-specific installed-plugin fallback must be capability-probed and must not replace those states.
The transient physical witness does not call semantic code.

### 8.3 Failure behavior

A failed optional capability disables only its own operation. A failed required syntax or semantic capability is
reported explicitly for the affected module/file and does not select a bundled active-module result.

Inactive modules remain exact pass-through behavior.

### 8.4 Ownership transitions

New syntax and semantic components may be exercised through direct tests while they remain unregistered. Active
runtime ownership has one syntax route: exact parser evidence is lowered through the reviewed catalog into a closed
output-role forest. The neutral lifecycle represents modules whose required parser capabilities are not ready.

Semantic ownership changes at complete IntelliJ role boundaries:

1. types and rendering;
2. symbols and resolution;
3. completion;
4. documentation, navigation, and usages;
5. diagnostics and semantic inspections.

Each semantic role has one compiler-facade route and no inactive alternative implementation. Diagnostics change last
because their semantic inspections depend on the preceding roles.

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

- every compiler artifact in the declared rolling support matrix;
- newly published artifacts whose covered inventories must admit without production-code changes;
- representative pull-request Scala versions;
- stable, EAP, and nightly IntelliJ/Scala-plugin hosts;
- transitions where a production moves from compatibility PSI to native PSI;
- every combination that supports best-effort TASTy.

Each tested combination records exact artifacts and options, parser and semantic capabilities, inventory coverage,
output-role bindings, and retained lane evidence. Drift names the missing bridge normalization, grammar role, output
role, semantic role, or compatibility binding. It never becomes a production version switch. An old Metallurgy binary
does not claim support for arbitrary unseen future grammar.

### 9.5 Current boundary coverage

The current tests distinguish evidence from active editor support:

| Boundary | Evidence that exists now | Work still required for active rich PSI |
| --- | --- | --- |
| Atomic expressions and selections | Dotc parser probes, source reconstruction, physical PSI, accessor, edit, reparse, and persistence tests | Broader owner contexts are added with their family |
| Ordinary and explicit `using` calls | Parser probes, native physical PSI, accessor, copy, pointer, edit, reparse, and opaque-boundary tests | Repeated/spliced and control-flow arguments remain separate families |
| Positional type application | Exact `TypeApply` probe, source planning, type-argument island, and negative generic-shell tests | Active `ScGenericCall` shell plus full edit and persistence proof |
| Named type arguments | Exact `NamedArg` probe, compatible child island, copied inference examples, edit and reparse tests | No native named-argument role exists in the pinned host; the generic-call shell stays inactive |
| Term named arguments | Separate dotc parser-shape probe; shadow comparison of transient plugin PSI against caller-supplied ranges and neutral named-argument facts; malformed and stale controls | Independent dotc ownership mapping plus catalog, planner, emitter, accessor, edit, reparse, persistence, and navigation wiring |
| Blocks, tuples, lambdas, infix, constructors, locals, defaults, parent applications, and applications inside refinements | Parser boundary probes, opaque payload tests, or explicit negative tests as appropriate | One bounded family at a time must add complete physical and lifecycle coverage |
| Shadow bridge itself | Source freshness, host capability, bounds, internal ownership, exact reconstruction, conflict, and unavailable tests | It remains read-only and unwired; active use requires a separate ownership cutover |

Copied upstream tests retain their original source and assertions. Focused suites check parser facts, source ownership,
physical ranges and accessors, edits, reparse, stub bytes, indices, and navigation. Known red copied suites run with a
parent baseline and a current control so an existing upstream or host failure is not mistaken for a new regression.
Named deterministic lanes list every expected test invocation. Project lifecycle tests cover open, indexing, reopen,
and parser preparation. Focused bridge tests cover capability-signature mismatch. An active fallback still needs
host-upgrade and restart invalidation tests before cutover.

### 9.6 Troubleshooting visible states

Users and maintainers should expect these outcomes:

| State | What the editor shows | What to inspect |
| --- | --- | --- |
| Parser preparing | Verbatim neutral text; Scala PSI features are not active yet | Artifact acquisition and parser capability preparation |
| Parser unavailable | Verbatim neutral text and a capability report | Missing parser method, definition, element type, or host shape |
| Shadow conflict | No runtime change, because the witness is read-only | The readable role, range, child, leaf, reconstruction, or ownership differences |
| Opaque expression | Exact source in one expression node; dotc semantic facts may still be available | The blocked expression family and its missing partition or PSI role |
| Semantic pending or stale | The affected active role returns unknown | Document version, source digest, session, classpath, options, and module generations |
| Current semantic result with no reference target | Navigation returns no target | The current dotc occurrence and symbol mapping, not installed-plugin resolution |
| Inactive module | Normal installed Scala plugin behavior | The Metallurgy module opt-in gate |

Reports separate four causes: a dotc parser-evidence gap, host capability drift, semantic snapshot state, and a source
ownership conflict. They name the affected role and readable ranges or shapes. Known baseline test failures remain in
their parent-versus-current controls instead of being relabeled as capability failures.

### 9.7 Real projects

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
- rolling compiler-admission, moving-host, and real-project lanes pass;
- unknown required syntax, output roles, and semantic capabilities expose deterministic project/file capability UX;
- formatting, fatal warnings, packaging, resource, and clean-restart gates pass.

A partial semantic overlay, a hidden diagnostic, a retained active-module fallback, or an unaccounted copied test does
not meet this contract.
