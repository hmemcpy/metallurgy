# Producer PSI delivery — findings

Status of delivering a Scala 3 compiler-produced PSI to the live IntelliJ editor (issue #83). This records the layered
problems, the codex-validated decisions, the evidence, and the open work, so the investigation survives independent of
chat history.

## The goal

Replace the bundled parser's PSI for active Scala 3 modules with a PSI built from the Scala 3 compiler's typed tree
(the "producer"), delivered as the **live file PSI** — so constructs the bundled parser cannot represent (named type
arguments, and beyond) are correct, resolvable, and typeable. The project invariant: **code the compiler accepts is
never shown red at any point.**

## Layer 1 — the delivery pipeline (works)

`Scala3DotcFileElementType.doParseContents` is the dialect file-root parse seam. The producer builds the AST there from
the typed tree when an extraction is installed in `DotcTreeSource`; otherwise the bundled parser runs. Publication goes
through the standard `AbstractFileViewProvider.onContentReload()` event sequence (codex-confirmed: it fires
`beforeChildrenChange` ×2 → `PsiFileEx.onContentReload` → `contentsSynchronized` → `childrenChanged` ×2 — the minimum
safe protocol; no bespoke event-bypass is viable because `PsiFileImpl` owns the AST lifecycle).

Committed: custom `Scala3DotcFileViewProvider` + `FileViewProviderFactory` EP (Phase A), generation-idempotent
`DotcTreeSource.install` (Phase B), controlled reload `installAndReload` with the `suppressPrepare` flag removed (Phase
C), smart-pointer survival across reload (Phase D). Commits `540bb03` → `f686450`.

### Key decisions (codex)
- A custom `FileViewProvider` is valid for file creation but **does not** own the AST lifecycle — `PsiFileImpl` does.
  Bypassing the platform event protocol recreates cache-corruption risk. Use the standard paired reload events; make
  publication **generation-idempotent** so the daemon re-analysis after a reload is a no-op (do not gate on a one-shot
  flag — it leaks across tests and races the daemon).
- `onContentReload` is sufficient for a physical, event-enabled provider; do **not** call `requestReindex` normally
  (only as recovery). `ResolveCache` clears on `beforeChildrenChange`, so it is not stale across the reload.

## Layer 2 — the cold-start race (fixed)

On parse #1 no extraction exists, so `doParseContents` fell back to the bundled parser, which eagerly emitted
`PsiErrorElement` (red) and a malformed `ScMethodCallImpl` (`None.get` at `findChild[ScExpression].get`) for `[A = Int]`
— *before* the producer could replace it. That violated the invariant.

### Rejected (codex)
- **Block `doParseContents` on the async extraction** — no thread guarantee (can run on EDT), holds the parse lock + read
  action, and the reload's write action cannot start while held → freeze/deadlock. A timeout also fails the invariant
  (code goes red if resolution exceeds it).
- **Post-process the bundled AST** (strip errors / fix malformed nodes) — removing `PsiErrorElement` changes text
  length; repairing nodes reconstructs the grammar.
- **Trigger the session in `LanguageSubstitutor`** — it must stay a pure language-selection query.

### Fix (committed `cb3bfdb`)
A source-keyed → file-URI-keyed `ProducerParseState` drives the parse:
- installed extraction → producer AST;
- settled verdict (`Rejected`/`BundledFine`) → bundled parser;
- a source the bundled parser **fragments** → a **pending placeholder leaf** (one `SCALA3_DOTC_PENDING_FILE_CONTENT`
  leaf holding the verbatim text: no error nodes, no Scala expression structure, no `ScMethodCall`), which schedules the
  backend; clean sources go straight to bundled and settle.

## Layer 3 — the placeholder never transitions (fixed)

In the live IDE the placeholder stayed a placeholder ("plain text tokens") and the backend never published. The test
passed only because `awaitBackendPublished` calls `prepareCompilerBackend` directly, bypassing the race.

### Root cause (codex)
`ProducerParseState` was keyed by **source text**, so a PSI *copy* or *index* parse of the same content won the
`Unknown→Pending` race first, called the scheduler on a file with no physical `VirtualFile`, and stranded the real file
in `Pending` forever. Additionally the `CompilerBackendPass` was registered with a `Pass.UPDATE_ALL` completion
predecessor, so a blocked update pipeline kept the backend from running.

### Fix (committed `12cfb3b`)
- Key `ProducerParseState` by **file URI** (not source text); reset on edit.
- Schedule the backend on **every** pending parse of an eligible physical module file (`backendInFlight` coalesces by
  module/URI/version).
- Register `CompilerBackendPass` as a **free pass** (no `UPDATE_ALL` predecessor).

## Layer 4 — the semantics gap (OPEN — the current blocker)

After Layers 1–3, the producer delivers a PSI whose **structure is correct** (verified via View PSI Structure:
`ScFunctionDefinition`, `ScPatternDefinition`, `ScMethodCall`, `ScGenericCall`, `ScReferenceExpression` at verbatim
ranges, no `PsiErrorElement`), but the file is **semantically hollow**: no types, no resolve, no navigation.

### Root cause (codex + PSI dump)
The producer emits the right **outer** element types, but the **inner grammar** the Scala resolver reads is wrong:

- **Parameters** are emitted as `ScPatternDefinition` (dotc models a parameter as a `ValDef`); `ScFunctionImpl.parameters`
  reads **`ScParameter`** → empty → calls cannot bind → no type inference.
- **`val` bindings**: the name is a loose `tIDENTIFIER`; `ScPatternDefinitionImpl.declaredElements` reads **binding
  patterns inside `PATTERN_LIST`** → empty → the binding declares nothing → unreachable.
- **Free-standing references** (`foo`, `x`) are not wrapped in `REFERENCE_EXPRESSION` → `nameId`/`resolve` do not apply.
- **Composites** must use balanced `marker.done(TYPE)`; `leaf()`/`collapse()` create leaves that render like composites
  in the viewer but are not the `Sc…Impl` the resolver casts to.

Same-file resolve is **lexical** via `ScDeclarationSequenceHolder.processDeclarations` — it does not need the stub
index, but it does need the real declaration internals (name tokens in the resolver's slots, real `ScParameter`s,
binding patterns) and the scope chain.

### The two semantic sources
1. **Types / inlays** — the **dotc overlay** (the `CompilerType` slot). Proven to work for a producer-built expression
   in the light-fixture test (`ExpressionExact=Current((Int, String))`). The live gap is the overlay associating with
   the **reloaded** PSI's new expression identities.
2. **Resolve / navigation** — the **bundled resolver**, which reads the PSI grammar → needs producer grammar fidelity.

The project's "dotc is always right" goal favours the overlay for types; grammar fidelity is needed for the bundled
resolve/navigation the editor expects.

### Mapping
See [`dotc-psi-mapping.md`](dotc-psi-mapping.md) for the construct-by-construct map (target grammar, dotc source,
current status, gap, and the extraction enrichment required: node **role**, **name**, **clause grouping**). The shortest
path to "resolve works for `def foo(x: Int): Int = x; val v = foo(42)`":
1. Wrap free-standing `Ident`/`Select` in `REFERENCE_EXPRESSION`.
2. Emit real `PARAMETER` nodes in `PARAM_CLAUSE` for param `ValDef`s.
3. Wrap `val` names in binding patterns inside `PATTERN_LIST`.
4. Verify top-level defs are exposed via `processDeclarations`.

Validate with `ref.multiResolveScala(false)`, `function.parameters`, `patternDefinition.declaredElements`,
`PsiFileImpl.calcStubTree()`.

## Evidence: how to observe at runtime

- **PSI dump**: `DebugUtil.psiToString(file, false, true)` — the same output as the internal "View PSI Structure → Copy
  PSI". Use it in a test after the full pipeline (`configureByText` → `awaitBackendPublished`) to assert the delivered
  tree.
- **Live `idea.log`** is at `~/.metallurgyPluginIC/system/log/idea.log` (IntelliJ `Logger.info` writes there, not
  stdout; only SEVERE reaches the `runIDE` stdout redirect).
- **Driver tool**: ide-probe (v0.53.0) drives a real IDE with a probe server and exposes a `HighlightInfo` endpoint
  (`severity`) — the repeatable, assertion-based replacement for manual runIDE; needs a Scala 2.13 test module
  (precedent: the in-tree `testkit/` backport).

## Committed state

| Commit | Layer | What |
|---|---|---|
| `540bb03` | 1 | dialect-owned `FileViewProvider` + EP (Phase A) |
| `62e4647` | 1 | generation-idempotent `DotcTreeSource.install` (Phase B) |
| `5b48f81` | 1 | controlled reload; `suppressPrepare` removed; named-type-args 6/6 via the production path (Phase C) |
| `f686450` | 1 | smart-pointer/index survival (Phase D) |
| `cb3bfdb` | 2 | pending-placeholder parse eliminates the cold-start red window |
| `12cfb3b` | 3 | key pending state by file URI; free the backend pass |

Tests green: named-type-args 6/6 (production path), `PendingPlaceholderParseTest` 1/1, producer 5/5, full dialect +
polymorphic/selectable regression. `#83` remains open on the semantics gap (Layer 4).

## Layer 5 — producer grammar fidelity (Phase F, done; `59a9ebe`)

The producer now emits faithful declaration/reference grammar, verified by a bundled-vs-producer
`DebugUtil.psiToString` comparison. For `def foo(x: Int): Int = x; val v = foo(42)` produced from dotc, **same-file
lexical resolve works**: `foo` resolves to `ScFunctionDefinitionImpl`; `parameters` and `declaredElements` are
populated.

What made it faithful:
- **Names** surfaced from dotc (`TermName`/`TypeName`) per node.
- **DefDef**: real `PARAMETER` in `PARAM_CLAUSE`; the function name consumed into `FUNCTION_DEFINITION` (`nameId`).
- **ValDef binding**: name wrapped in a reference pattern inside `PATTERN_LIST` (`declaredElements`).
- **Ident/Select**: wrapped in `REFERENCE_EXPRESSION`.
- **`doParseContents` returns the unwrapped first child** (`getTreeBuilt.getFirstChildNode`), mirroring the platform
  default `ILazyParseableElementType.doParseContents`, so top-level nodes are direct children of the file
  (`ScDeclarationSequenceHolder`).

codex review: directionally sound. Hardening item — `advanceToToken(node.name)` matches textually; prefer the exact
name **span** from dotc (backticked/encoded/operator/generated names). Overloading is fine once signatures are faithful.

## Layer 6 — type/literal grammar (Phase G, next; codex-steered)

Producer vs bundled still differ in the type grammar: param/return `Int` is a raw token (bundled:
`ParameterType`/`SimpleType`/`CodeReferenceElement`); literals are raw (bundled: `IntegerLiteral`); minor
(`AnnotationsList`/`Modifiers`, `)` placement). codex: emit faithful type/literal PSI so the bundled resolver computes
types, and retain the dotc overlay as semantic authority where bundled inference differs (named-type-args already
yields `(Int, String)` via the overlay in the test). Minimal grammar:

```
PARAM > ':' > PARAM_TYPE > SIMPLE_TYPE > REFERENCE "Int"
return: ':' > SIMPLE_TYPE > REFERENCE "Int"   (a ScTypeElement, so returnTypeElement resolves)
ARG_EXPRS > IntegerLiteral "42"
```

## codex systematic review (after Phase F+G grammar work)

### Verdict: the typed-tree-only producer is a useful vertical prototype, but full grammar parity needs a concrete-syntax producer.
A typed dotc tree is **lossy** relative to source grammar. What it cannot recover (tokens are available, but the typed
hierarchy drops distinctions):
- Multiple param clauses / clause kinds (`using`, `implicit`, erased) — already collapsed to one `PARAM_CLAUSE`.
- Desugared surface hierarchy: `for`, placeholder lambdas, extension methods, context bounds, patterns, givens, enum
  cases, named args, exports, some applications/type-applications.
- Modifiers, annotations, visibility, self types, end markers, indentation regions, repeated/by-name param syntax,
  comment/doc ownership.
- Infix terms/types need concrete operator/parenthesization (precedence-climbing in `InfixExpr`/`InfixType`); ordered
  typed spans alone are insufficient.
- Error-recovery PSI cannot come from a clean typed tree at all → bundled-parser fallback for invalid intermediate
  edits is unavoidable.

`kind/range/name/role` is not a sufficiently expressive intermediate representation.

### Two reported failures are consistent with crossing IntelliJ's syntax/stub lifecycle with an async-changing AST.
1. **Diagnostics don't update on edit (but PSI does).** The inspection derives the package from
   `file.members.map(_.topLevelQualifier)` (not `firstPackaging.packageName`). Instrument to distinguish: is the DIRECT
   inspection fresh (daemon didn't complete a post-reload pass) or stale (cache/stub defect)? Fix if daemon-only:
   `DaemonCodeAnalyzer.restart(target, reason)` after `onContentReload`, outside the write action.
2. **Stub/index mismatch ("Stub count 21 vs 1").** Root cause: **nondeterministic parsing for identical content** — one
   generation builds the produced stub tree, another (after restart, `DotcTreeSource` empty) builds the one-leaf
   pending AST. Contract: for a given indexed content + stub version, parsing must deterministically yield the same
   stub-bearing spine. Do **not** let placeholder, bundled, and dotc-produced shapes compete for the same indexed
   content. Options: keep a deterministic syntax AST during pending; or disable stub building for the dialect until
   deterministic.

### Systematic direction (the proper architecture)
- **Concrete-syntax layer:** token stream + dotc untyped/parser tree (preserves clauses, modifiers, patterns,
  indentation, operators, punctuation ownership).
- **Semantic overlay:** typed-tree identities, symbols, types, source roles.
- **Declarative production schema:** each bundled PSI production declares required children, cardinality, direct-child
  tokens, stub-bearing status.
- **Differential tests:** parse every compiler-valid upstream fixture through both producers (where bundled parsing
  succeeds); compare element types, ranges, direct-child structure, public PSI accessors, and the **complete stubbed
  spine** — not just rendered PSI.
- **Contract tests** for every stub-bearing production and the `pending → produced` edit/restart lifecycle.

Continuing with typed-tree-only, per-crash grammar patches will remain structurally reactive. The stub mismatch is the
strongest evidence that syntax production and asynchronous compiler publication need to be separated.
