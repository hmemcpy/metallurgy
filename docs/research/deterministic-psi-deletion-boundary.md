# Deterministic PSI deletion boundary

## Question

Which parts of the current implementation belong to asynchronous syntax replacement, which contracts remain useful for
deterministic whole-file composition, and where should the replacement start?

## Decision

The current syntax path is one coupled mechanism and should be removed as a unit:

```text
stub-bearing dialect parse
  -> bundled-parser probe
  -> stub-bearing pending leaf
  -> parser-triggered semantic preparation
  -> retained untyped tree after retypecheck
  -> source-keyed extraction cache
  -> per-file terminal decision
  -> view-provider content reload
  -> a different AST and stub spine for the same text
```

The dialect identity, Scala-compatible file/view-provider boundary, module gate, exact-version artifact isolation,
immutable semantic snapshots, and freshness checks remain valid. Syntax parsing must move to a separate ready parser
capability that synchronously produces one deterministic whole-file result. Semantic preparation may continue
asynchronously but must not install, select, reload, or otherwise change syntax.

## Current production flow

The dialect is selected for active modules by `Scala3DotcLanguageSubstitutor`, registered before the bundled Scala
substitutor. Its parser definition and view-provider factory are separately registered for the dialect
([`plugin.xml`](../../src/main/resources/META-INF/plugin.xml#L65-L74)).

`Scala3DotcFileElementType.doParseContents` currently has three syntax authorities:

1. an extraction in `DotcTreeSource` invokes `DotcPsiProducer`;
2. a settled `ProducerParseState` invokes the bundled parser;
3. an undecided source is either parsed bundled or represented by a pending leaf while
   `ProducerParseScheduler` starts compiler work
   ([`Scala3DotcFileElementType.scala`](../../src/main/scala/com/hmemcpy/metallurgy/psiproducer/Scala3DotcFileElementType.scala#L26-L48)).

The asynchronous compiler path later extracts `unit.untpdTree`, installs it in `DotcTreeSource`, changes
`ProducerParseState`, calls `AbstractFileViewProvider.onContentReload`, and restarts the daemon
([`PcSessionManager.scala`](../../src/main/scala/com/hmemcpy/metallurgy/pc/PcSessionManager.scala#L327-L403)).
Document edits reset the parse decision before scheduling another semantic analysis
([`PcSessionManager.scala`](../../src/main/scala/com/hmemcpy/metallurgy/pc/PcSessionManager.scala#L258-L266)).

This coupling is the deletion boundary. Retaining any one of its cache, decision, scheduler, placeholder, reload, or
case-based emission pieces would preserve a second syntax-generation path.

## Component disposition

### Delete

| Component | Reason |
|---|---|
| `DotcTreeSource` | It is a source-text-keyed handoff from asynchronous semantic compilation to synchronous parsing. A ready parser must derive syntax directly from the exact source, not consume a later-installed extraction. |
| `ProducerParseState` | `Unknown`, `Pending`, `Rejected`, and `BundledFine` select different AST shapes for the same stub-bearing language. Parser readiness belongs to a separate language/capability state, not a per-file syntax verdict. |
| `ProducerParseScheduler` | A parser must not start asynchronous semantic work. Parser capability preparation is owned by project/module lifecycle code before the ready language is selected. |
| `Scala3DotcPendingLeaf` | Pending text must belong to an explicitly non-stub-bearing pending language. A leaf under `ScStubFileElementType` recreates the conflicting stub spine. |
| `BundledScala3Parse` as a production decision service | `PsiErrorElement` presence is not a sufficient production contract. Bundled parsing may remain a test/reference input or a catalog-validated implementation source, but cannot decide runtime ownership by itself. |
| `DotcPsiProducer` | The `node.kind match` emitter and textual reconstruction heuristics are replaced by the declarative production catalog. Unknown nodes currently fall through to `emitRaw`, which is incompatible with fail-closed coverage ([`DotcPsiProducer.scala`](../../src/main/scala/com/hmemcpy/metallurgy/psiproducer/DotcPsiProducer.scala#L27-L45)). |
| Current `Scala3DotcFileElementType` implementation | Its cache/state/probe branches are the syntax race. The dialect still needs a stub file element type, but its parse implementation must consume only the ready deterministic parser. |
| `PcSessionManager.installAndReload` and its syntax imports | Semantic publication must no longer install untyped trees, mutate parse decisions, reload the file, or restart the daemon because syntax changed. |
| `PcSessionManager` edit-time `ProducerParseState.reset` | Edits already create new document versions for semantic freshness. Syntax reparsing is owned by the platform and deterministic parser. |

### Refactor or replace

| Component | Valid contract | Required replacement |
|---|---|---|
| `Scala3DotcParserDefinition` | Dialect-owned lexer, parser definition, `ScalaFile`, and `ScStubFileElementType` are necessary because the Scala stub builder requests PSI for its exact language ([`Scala3DotcParserDefinition.scala`](../../src/main/scala/com/hmemcpy/metallurgy/psiproducer/Scala3DotcParserDefinition.scala#L17-L25); upstream `ScStubFileElementType.scala:31-45`). | Split pending and ready parser definitions. The ready definition invokes deterministic whole-file composition; the pending definition is explicitly non-stub-bearing. |
| `Scala3DotcLanguageSubstitutor` | Active/inactive module gating and fall-through for untouched modules remain correct ([`Scala3DotcLanguageSubstitutor.scala`](../../src/main/scala/com/hmemcpy/metallurgy/psiproducer/Scala3DotcLanguageSubstitutor.scala#L16-L20)). | Select pending or ready language from parser capability state, then request a platform reparse when readiness changes. It must remain a pure query. |
| `Scala3DotcFileViewProvider` and factory | A dialect-specific `ScalaFile` provider and copy behavior are required because bundled `ScFileViewProvider` is final. | Remove syntax-lifecycle ownership claims. Keep only file creation, language identity, copy, and platform event compatibility. |
| `CompilerTreeDto` | Same-source classification, exact ranges, physical/synthetic separation, and neutral values across the isolated classloader remain useful ([`CompilerTreeDto.scala`](../../src/main/scala/com/hmemcpy/metallurgy/pc/CompilerTreeDto.scala#L3-L36)). | Replace `kind/range/name/role` with the production catalog's concrete child roles, cardinality, token ownership, modifiers, patterns, clauses, recovery status, and stable syntax discriminators. Syntax and semantic DTOs should not share an accidental minimal shape. |
| `Scala3PcBridge.untypedTreeDto` / `untypedTreeExtraction` | Exact compiler objects remain isolated and neutral DTOs cross the boundary ([`Scala3PcBridge.scala`](../../src/main/scala/com/hmemcpy/metallurgy/pc/Scala3PcBridge.scala#L27-L38)). | Move parser access to a parser-only bridge usable before semantic retypecheck. Prefer published operations, then typed structural access, then isolated capability-probed raw reflection. |
| `StructuralScala3PcBridge` tree walking | Source ownership, span validation, hierarchy walking, name extraction, and child-classloader collection conversion are reusable algorithms. | Separate syntax extraction from retained `InteractiveDriver` state and from diagnostics. Export the richer catalog DTO and fail explicitly on an unsupported required production. |
| `Scala3CompatTestCase` | Verbatim source configuration, exact-version preparation, semantic readiness waits, type rendering, highlighting, and conflict proof are useful harness contracts. | Remove `DotcTreeSource`/`ProducerParseState` clearing, bundled-error takeover decisions, and `preCompileAndInstall`. Add explicit parser-ready, parser-pending, semantic-ready, and neutral-file helpers. |

### Retain

| Component | Contract retained |
|---|---|
| `Scala3DotcLanguage` | A dialect based on `Scala3Language` that also implements `JvmLanguage`, `DependentLanguage`, and `InjectableLanguage` inherits dialect-aware Scala extensions while satisfying marker checks ([`Scala3DotcLanguage.scala`](../../src/main/scala/com/hmemcpy/metallurgy/psiproducer/Scala3DotcLanguage.scala#L9-L21)). Rename only if pending/ready terminology requires it. |
| `ModuleDetectionService` | Scala 3 plus explicit opt-in remains the constant-time outer gate. |
| Exact artifact cache and isolated classloader | Exact compiler coordinates remain artifact identity. Capability probes, not version branches, select parser and semantic facilities. |
| `PcSnapshot`, snapshot currency, session generations, and semantic publication | URI/version freshness and immutable publication remain necessary for asynchronous types, symbols, completion, and diagnostics. They stop owning syntax. |
| `PcTypedTreeSnapshot` and semantic backend roles | These remain the current semantic evidence until the semantic-root inventory specifies their final replacement. |
| `CompilerBackendPass` | A free highlighting pass may continue to schedule asynchronous semantic preparation ([`CompilerBackendPass.scala`](../../src/main/scala/com/hmemcpy/metallurgy/compilerbackend/CompilerBackendPass.scala#L9-L18)). It must not be needed to make syntax parseable. |
| `CompilerType` compatibility bridge | It remains transitional semantic plumbing and is retired role by role only after equivalent compiler-authoritative PSI tests pass. |
| `PcDiagnosticSetCache` and renderer freshness | Exact-version diagnostic state and stale-range rejection remain useful. Diagnostic ownership is reviewed separately; the existing highlight filter passes everything unchanged ([`PcHighlightInfoFilter.scala`](../../src/main/scala/com/hmemcpy/metallurgy/feature/diagnostics/PcHighlightInfoFilter.scala#L6-L10)). |

## Test disposition

### Delete with the retired mechanism

- `DotcTreeSourceTest`: tests only the source-keyed async-to-parser cache.
- The current assertions in `PendingPlaceholderParseTest`: they prove a leaf under the stub-bearing dialect rather
  than the separate pending-language contract.
- Publication-specific `ProducerReloadIndexTest` assertions that require semantic publication to change syntax.
- `Scala3CompatTestCase.preCompileAndInstall` and tests whose only purpose is bypassing parse-before-publication.

### Replace with stronger contracts

- Replace `DotcPsiProducerTest` with catalog production, accessor, exact-range, recovery, and complete ordered-stub
  tests. Preserve its useful snippets and public-accessor assertions.
- Replace `ProducerDifferentialDumpProbeTest` with an executable comparison. Printing trees and checking only for
  `PsiErrorElement` does not establish parity
  ([`ProducerDifferentialDumpProbeTest.scala`](../../src/test/scala/com/hmemcpy/metallurgy/pc/ProducerDifferentialDumpProbeTest.scala#L100-L131)).
- Replace `UntypedTreeExtractionProbeTest` with parser-only bridge tests that prove parsing does not require successful
  typing and that repeated extraction is structurally identical.
- Replace reload-based index tests with cold/warm/copy/edit/restart signatures for the same source and capability set.
- Extend pending tests to prove no false error, warning, unresolved coloring, Scala reference PSI, or Scala stub claims
  before readiness, followed by a platform-owned language transition.

### Retain and adapt

- Keep language graph, marker-interface, module substitution, daemon inheritance, completion, find-usages, file loading,
  stub-index, view-provider, and copy tests. Run them for ready files and add explicit pending-language expectations.
- Keep compiler type, resolve, diagnostics, completion, navigation, usage, refactoring, inactive-module, mixed-project,
  and exact-version tests. Remove assumptions that semantic publication reparses syntax.
- Keep copied IntelliJ parity tests and conflict proofs; the integrity/classification tickets define their final
  harness and manifest.

## Real-IDE validation

`ideprobe-tests` is already a standalone Scala 2.13 build using ide-probe 0.53.0. Its current test opens the dogfood
named-type-argument file and checks highlights only after the old producer settles
([`NamedTypeArgsHighlightTest.scala`](../../ideprobe-tests/src/test/scala/com/hmemcpy/metallurgy/ideprobe/NamedTypeArgsHighlightTest.scala#L17-L37)).

Retain the module and replace the delivery-specific assertion with real-IDE lifecycle contracts:

- cold start with uncached parser capability;
- verbatim neutral pending file with no false findings;
- progress/background-task visibility;
- platform-owned pending-to-ready language reparse;
- ready AST and complete stub signature;
- restart and warm-cache determinism;
- edit retirement and semantic convergence;
- navigation, completion, usages, and cross-file indices;
- absence of PSI exceptions, stub mismatches, freezes, and severe IDE log entries.

The local `~/git/ide-probe` checkout is the primary source for available endpoints and waiting behavior. Its driver
exposes background tasks and highlight queries, and its probe runs inside a real IntelliJ process. Manual `runIDE`
observation remains useful for diagnosis but is not graduation evidence.

## Deletion and replacement order

1. Add failing deterministic signatures for AST, direct-child structure, public accessors, and complete stubs. Add the
   separate pending-language safety contract.
2. Introduce parser capability state and the pending language without connecting the ready producer.
3. Introduce the parser-only bridge and richer neutral syntax DTO.
4. Introduce the production catalog, compatibility emitters, whole-file validator, and ready parser definition.
5. Switch the language substitutor from pending to ready through a platform-owned reparse.
6. Remove `DotcTreeSource`, `ProducerParseState`, `ProducerParseScheduler`, `Scala3DotcPendingLeaf`,
   production `BundledScala3Parse`, `DotcPsiProducer`, and the old file-element implementation together.
7. Remove `PcSessionManager.installAndReload`, syntax-related imports, edit resets, and untyped-tree publication.
8. Rewrite or delete the old mechanism tests and update the compatibility harness.
9. Run focused light-fixture contracts, the complete Metallurgy suite, and ide-probe cold/warm/restart validation.
10. Retire semantic overlays only through the later semantic-root plan; do not combine that work with syntax deletion.

## Resulting boundary

After the cut:

```text
module capability lifecycle
  -> pending non-stub language OR ready stub-bearing language

ready parse(source, exact parser capability)
  -> parser-only neutral CST
  -> declarative production catalog
  -> validated whole-file AST + deterministic stub spine

async semantic preparation(source, document version)
  -> immutable types/symbols/diagnostics
  -> compiler-authoritative semantic consumers
```

No asynchronous result crosses back into syntax selection or syntax installation.
