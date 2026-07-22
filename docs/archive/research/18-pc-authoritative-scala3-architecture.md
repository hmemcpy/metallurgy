# 18 — Making the Scala 3 presentation compiler authoritative in IntelliJ

## Scope and source baseline

This catalog answers the deliberately maximal research question: what must a dependent plugin replace, extend, or
bridge if the Scala 3 presentation compiler (PC) is to become authoritative for **all** Scala type resolution in opted-in
Scala 3 modules? It is based on bundled Scala plugin **2026.1.20** (local checkout
`8dd22d153b65c847f4ced8917dd7e02b83561e5d`) and Scala **3.7.4**
([tag commit `40be7608`](https://github.com/scala/scala3/tree/40be7608a48477951218ae3a8ac8749fe02ba988)).
Links below use those versioned sources.

This goal is intentionally broader than Metallurgy's current scope in `CONTEXT.md`, ADR 0011, and the root
`AGENTS.md`, which limit the project to type resolution/presentation gaps and explicitly demote diagnostics. The goal in
this document **supersedes that scope for research and epic planning only**. It does not silently change those governing
documents; adopting it requires a separate scope decision.

### Executive answer

There is no one “Scala type evaluator” to replace. The bundled plugin has three coupled semantic planes:

```text
PSI element.type()/reference.bind()          ScType algebra + resolve processors
             |                                          |
             +---- completion, docs, hints, inspections-+
             +---- references/find usages/refactorings --+

compiler integration -> external diagnostics/types -> editor-owned overlays + CompilerType slots
```

`CompilerType` reaches only two narrow readers: `ScExpression.getTypeWithoutImplicits` and one qualified stable-reference
branch. It turns a compiler-rendered string back into a bundled `ScType`; every later operation—member enumeration,
conformance, inference, implicit search, overload selection, symbol identity—still belongs to the bundled engine
([`CompilerType.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/CompilerType.scala),
[`ScExpression.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/expr/ScExpression.scala#L301-L326)).

Making PC authoritative therefore requires, in priority order:

1. **P0 — a universal external semantic snapshot and interception contract** for types *and* symbols, keyed by exact
   `(module, compiler configuration/classpath epoch, file URI, document version, position/range)`.
2. **P0 — interception of `Typeable.type()` roots and `ScReference.bind/multiResolveScala`**, either by an upstream
   `intellij-scala` EP or unsupported bytecode/method-body instrumentation. No present EP does this.
3. **P0 — PC-native symbol/member/completion/definition services.** A rendered type string cannot represent compiler
   symbol identity or make PSI processors enumerate generated/structural members.
4. **P1 — migrate every semantic consumer** (docs, semantic highlighting, inspections, hints, find usages, refactorings)
   to the snapshot or a PC-native feature path; retain PSI only as syntax, editing, and navigation anchors.
5. **P1 — a separate best-effort artifact population compiler.** `InteractiveDriver` can consume `.betasty`, but its
   four-phase compiler cannot write it.

| Order | Epic outcome | Principal source-backed blocker | Exit evidence |
|---:|---|---|---|
| 1 / P0 | Exact-version semantic snapshot | PC runs are URI/content scoped and compiler objects are run-local ([`InteractiveDriver`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/interactive/InteractiveDriver.scala#L144-L175), [`CachingDriver`](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/CachingDriver.scala#L11-L54)) | Stale document/classpath generations cannot publish |
| 2 / P0 | Universal type + resolve interception | `Typeable` has no dispatcher and term resolve probes native non-value type before slot-aware type ([`result.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/types/result.scala#L10-L21), [`ReferenceExpressionResolver`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/resolve/ReferenceExpressionResolver.scala#L716-L733)) | Every gated `type`/`bind` test identifies a PC snapshot result |
| 3 / P0 | PC-native interactive surfaces | Completion, definition and semantic tokens already require typed-tree/synthetic repairs in upstream PC ([PC sources](https://github.com/scala/scala3/tree/3.7.4/presentation-compiler/src/main/dotty/tools/pc)) | Completion/hover/resolve/highlighting agree on symbol/type |
| 4 / P1 | Workspace symbol authority | Find usages is PSI/index based, while PC is not a workspace occurrence store ([Scala handler](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/findUsages/factory/ScalaFindUsagesHandler.scala)) | Closed-file usages/rename use compiler symbol IDs |
| 5 / P1 | Best-effort population | PC's phase plan has no Pickler ([`InteractiveCompiler`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/interactive/InteractiveCompiler.scala#L8-L21)) | Atomic `.betasty`/SemanticDB generation feeds rebuilt downstream sessions |

Priority notation: **P0** blocks the authority claim; **P1** is required for a complete user-facing replacement; **P2**
is compatibility/performance hardening. Risk is **critical/high/medium/low** and describes version coupling plus semantic
blast radius.

---

## 1. Type production sites

### 1.1 The type algebra and the missing dispatcher — P0, critical

`Typeable` is only an interface with an abstract ``type(): TypeResult``; it has no central evaluator or service dispatch
([`result.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/types/result.scala#L10-L21)).
Concrete PSI traits/classes compute their own types. `ScType` then supplies a large independent algebra—equivalence,
conformance, widening, LUB/GLB, extraction, substitution, presentation, and Java conversion—through `ScType`,
`ScTypeExt`, and the single production `ScalaTypeSystem`
([`ScType.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/types/ScType.scala),
[`package.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/types/package.scala#L42-L279),
[`ScalaTypeSystem.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/types/ScalaTypeSystem.scala)).

There is **no semantic `ScTypeEvaluator`**. The similarly named
[`ScalaTypeEvaluator`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/debugger/src/org/jetbrains/plugins/scala/debugger/evaluation/evaluator/ScalaTypeEvaluator.scala)
and `ScalaRuntimeTypeEvaluator` are debugger/runtime expression evaluators, not a seam for PSI typing.

| Production root | What it produces / current route | Compiler slot reaches it? | Clean seam | Required authority work | Risk |
|---|---|---:|---|---|---|
| `Typeable.type()` implementations | Canonical synchronous `TypeResult` for expressions, type elements, definitions, patterns, parameters | Per implementation | No | Add upstream `externalSemanticProvider`/dispatcher, or instrument every root | Critical |
| `ScType` + `ScalaTypeSystem` | Conformance, equivalence, bounds, presentation, PSI↔Java types | Only receives parsed slot result | No replaceable `TypeSystem` EP | Keep as compatibility projection only; PC answers semantic operations requiring truth | Critical |
| `ScalaPsiElementFactory.createTypeFromText` | Parses compiler string into `ScType` | Yes | Public callable API | Preserve for display/legacy callers, never confuse with symbol authority | High |

The robust upstream contract is a synchronous, cache-only lookup around each semantic root:
`externalType(element/key) -> bundled implementation`. It must never launch or await PC work under a PSI read action.
Without upstream cooperation, a third-party plugin needs version-pinned bytecode instrumentation; reflection alone can read
private fields or call methods but cannot replace method bodies.

### 1.2 Expressions, expected types, and inference — P0, critical

`ScExpression.type()` ultimately enters `getTypeWithoutImplicits`, whose cached body checks `CompilerType(expr)` before
running ordinary expression typing. When CBH/use-compiler-types is disabled it clears the slot
([`ScExpression.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/expr/ScExpression.scala#L301-L345)).
The fallback calls the concrete expression's `getNonValueType`, computes `expectedType`, widens literals/SAMs, then later
applies implicit conversions. Expected types and inference are themselves PSI implementations in
[`ExpectedTypesImpl`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/expr/ExpectedTypesImpl.scala),
[`InferUtil`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/InferUtil.scala),
and expression classes such as `ScMethodCall`, `ScGenericCall`, `ScBlock`, lambdas, matches, for-comprehensions, tuples,
and typed expressions.

The slot therefore makes PC authoritative only for the **first expression type value**. It does not replace contextual
expected-type calculation, implicit adaptation, method applicability, overload inference, or members subsequently
processed from the parsed `ScType`. P0 interception must expose at least expression type, expected type, selected overload,
type arguments, implicit/given arguments, and conversion/extension selection from the same PC snapshot.

### 1.3 Declared types, definitions, and patterns — P0, critical

`ScTypeElement.getType` is separately cached and calls `innerType`; it never consults `CompilerType`
([`ScTypeElement.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement.scala#L15-L45)).
`ScPatternDefinitionImpl.type()` chooses the explicit `ScTypeElement` first and only reaches the initializer expression
for an unannotated value
([`ScPatternDefinitionImpl.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/statements/ScPatternDefinitionImpl.scala#L35-L58)).
Equivalent independent roots exist for variables, functions/return types, parameters/class parameters, binding and typed
patterns, generators, type aliases/bounds, givens, enum cases, and template supertypes.

| Declaration root | Native production path | Slot reach | Source / overwrite consequence |
|---|---|---:|---|
| Functions | Explicit return `ScTypeElement`, otherwise body `type()` and method/poly wrapping | Body only | [`ScFunctionDefinitionImpl.returnTypeInner`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/statements/ScFunctionDefinitionImpl.scala#L150-L170); intercept return/method type, not only body |
| Parameters / class parameters | Declared type/default/expected owner signature | No general slot | [`ScParameter.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/statements/params/ScParameter.scala), [`ScParameterImpl.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/statements/params/ScParameterImpl.scala); publish per-symbol parameter type |
| Binding/destructuring patterns | `ScReferencePatternImpl.type()` uses pattern `expectedType` / pattern inference | Indirect at best | [`ScReferencePatternImpl.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/base/patterns/ScReferencePatternImpl.scala#L39-L51); key each binding separately |
| Variables | Declared type or initializer plus mutability/widening | Initializer only | [`ScVariableDefinitionImpl.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/statements/ScVariableDefinitionImpl.scala); intercept declaration root |
| Type aliases / bounds | Aliased/lower/upper `ScTypeElement` and substitutors | No | [`ScTypeAlias.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/statements/ScTypeAlias.scala), [`ScTypeAliasDefinitionImpl.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/statements/ScTypeAliasDefinitionImpl.scala); structured alias symbol/type required |
| Givens | Given type/alias/body and synthetic name/member machinery | Body only, if reached | [`ScGiven.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/toplevel/typedef/ScGiven.scala), [`ScGivenDefinitionImpl.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/toplevel/typedef/ScGivenDefinitionImpl.scala); publish declaration plus implicit-selection identity |
| Templates / parents / self types | Parent type elements, signatures, mixin nodes and synthetic members | No | [`ScTemplateDefinition.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/toplevel/typedef/ScTemplateDefinition.scala), [`TypeDefinitionMembers.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/toplevel/typedef/TypeDefinitionMembers.scala#L32-L100); member/super graph needs PC bridge |

Consequences:

- `val x = rhs` can inherit a slot on `rhs`; `val x: T = rhs` bypasses it through `T.type()`.
- One definition can bind several symbols. A range-to-type map must distinguish each binding in tuple/destructuring/named
  patterns; definition-level strings are insufficient.
- Function method/poly types, parameter types, type aliases, self types, and parents are not expression slots.

Required work is a PC semantic key for every source declaration plus external lookups in `ScTypeElement`, typed
definitions/patterns, functions/parameters, type aliases/bounds, and template parent computation. An upstream wrapper around
all `Typeable` calls is preferable to a growing patch list.

### 1.4 Reference resolution and binding — P0, critical

Term references enter `ScReference.multiResolveScala/bind`, then `ReferenceExpressionResolver` and
`MethodResolveProcessor`; stable/type references use `ScStableCodeReferenceImpl` and the same processor family
([`ScReference.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/base/ScReference.scala),
[`ReferenceExpressionResolver.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/resolve/ReferenceExpressionResolver.scala),
[`MethodResolveProcessor.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/resolve/processor/MethodResolveProcessor.scala)).
`BaseProcessor.processType` enumerates members from a bundled `ScType`; `ScalaResolveResult` carries the selected PSI
element, substitutor, imports, applicability problems, implicit conversion/arguments, extension metadata, and inferred type
([`BaseProcessor.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/resolve/processor/BaseProcessor.scala#L56-L237),
[`ScalaResolveResult.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/resolve/ScalaResolveResult.scala#L166-L311)).

The sole compiler-type binding exception is a qualified stable-code-reference branch: when the qualifier has a compiler
string, it creates a reference from the type text and binds that synthetic PSI reference
([`ScStableCodeReferenceImpl.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/base/ScStableCodeReferenceImpl.scala#L475-L493)).
This is not PC symbol resolution.

Term qualifier processing is worse than a simple partial route: it first calls `qualifier.getNonValueType()`, which goes
directly to expression `innerType` and can yield native candidates, before the later slot-aware `qualifier.type()` branch
([`ReferenceExpressionResolver.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/resolve/ReferenceExpressionResolver.scala#L716-L733)).
Thus even a populated expression slot does not necessarily win qualified resolution; this method or the reference root
must be overwritten.

Authority requires a PC result keyed by source occurrence containing stable symbol identity, definition location,
selected overload, instantiated signature, imports/implicit/extension path, and zero-or-more targets. It must intercept both
term and stable reference `bind/multiResolveScala`, with PSI/synthetic navigation adapters for callers that require
`PsiElement`/`ScalaResolveResult`.

### 1.5 Implicit search and inference state — P0, high

`ImplicitCollector`, `ImplicitProcessor`, applicability checking, `Compatibility`, and `TypeInference` independently
search scopes and manipulate `ScType` constraints
([`ImplicitCollector.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/implicits/ImplicitCollector.scala),
[`Compatibility.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/types/Compatibility.scala)).
A PC expression type does not replace those answers. The snapshot must publish chosen givens, conversions, extensions,
inferred type arguments and failure/ambiguity information; consumers should not re-run bundled inference when a current PC
answer exists.

### 1.6 Caches and invalidation — P0, critical

Type reads use `cachedWithRecursionGuard` and `BlockModificationTracker`. Stable subexpressions get local modification
counters; other nodes depend on file/top-level/root trackers
([`BlockModificationTracker.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/caches/BlockModificationTracker.scala)).
Writing copyable user data is not a PSI modification, so existing cached type/resolve answers survive unless explicitly
invalidated. The bundled external-type publisher calls `ScalaPsiManager.clearOnScalaElementChange`, increments
`anyScalaPsiChange`, and refreshes implicit/type hints
([`ExternalHighlightersService.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/ExternalHighlightersService.scala#L76-L145)).

`ScalaPsiManager` caches class/index lookup, implicits and compound/intersection member/signature maps; it is not a
`typeOf(element)` service and its non-open project-service registration is not a useful replacement seam
([`ScalaPsiManager.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/ScalaPsiManager.scala#L55-L133),
[`scala-plugin-common.xml`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/resources/META-INF/scala-plugin-common.xml#L601-L606)).

Metallurgy needs one generation-aware cache owner and one coalesced invalidation transaction per file/version. Cache keys
must include compiler version/options, source/classpath/output epochs and exact document version. Publication must revalidate
all keys, clear missing/stale entries, invalidate Scala caches once, and never block the EDT/read action.

### 1.7 Compile server, compiler types, and CBH — P1, high

`CompilerHighlightingService` schedules incremental/document compilation; `DocumentCompiler` invokes the compile server,
and `CompilerEventGeneratingClient` publishes compiler events. The Scala 3 document connector adds a compiler plugin which
prints selected transparent-inline types; `ExternalHighlightersService` range-matches those strings only to expressions or
stable references in the focused editor
([`CompilerHighlightingService.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/CompilerHighlightingService.scala),
[`DocumentCompiler.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/DocumentCompiler.scala),
[`ExternalHighlightersService.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/ExternalHighlightersService.scala#L76-L99)).
`CompilerType.requestFor` is a synchronous message-bus demand signal, and the bundled listener schedules document
compilation
([`CompilerTypeRequestListener.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/CompilerTypeRequestListener.scala)).

Metallurgy can subscribe to the public topic and coexist with CBH, but cannot replace the compiler integration project
service/scheduler through a semantic EP. For full authority it needs provenance/ownership arbitration for compiler
diagnostics and types, or an upstream compiler-result arbiter; otherwise two asynchronous writers race.

---

## 2. Type consumers

“Slot route” below means some expression type can arrive through `ScExpression`; it does **not** mean the whole feature is
PC-authoritative.

| Consumer routing matrix | Slot-aware expression type | Direct declaration/`Typeable` | `bind` / resolve processors | Other semantic source |
|---|---:|---:|---:|---|
| Hover/docs | Partial | Yes | Yes | PSI docs/supertypes ([quick-info source](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/editor/documentationProvider/ScalaDocQuickInfoGenerator.scala#L35-L67)) |
| Completion/signature | Narrow transparent-inline path | Yes | Yes | Scope/index/global completion ([completion source](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/completion/package.scala#L259-L286)) |
| Resolve/navigation | Qualified expression only; native bypass exists | Sometimes | **Primary** | Synthetic-target adjustment ([resolver](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/resolve/ReferenceExpressionResolver.scala#L716-L733)) |
| Semantic highlighting | Indirect | Sometimes | **Primary** | Syntax classification ([annotator](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/highlighter/ScalaColorSchemeAnnotator.scala#L67-L87)) |
| Inspections/analyzer | Partial | Yes | Yes | Indexes/conformance/implicit search ([annotator root](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/annotator/ScalaAnnotator.scala)) |
| Inlay hints | Partial | Yes | Yes | Implicit args/method chains ([PC replacement](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/PcInlayHintsProvider.scala)) |
| Structure view | No practical route | Explicit text / inherited members | Inherited path | Stubs/signature maps ([structure source](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/structure-view/src/org/jetbrains/plugins/scala/structureView/ScalaInheritedMembersNodeProvider.scala#L33-L77)) |
| Find usages | No | Hierarchy/signatures | **Identity validation** | PSI/compiler indexes ([handler](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/findUsages/factory/ScalaFindUsagesHandler.scala)) |
| Refactorings | Partial | Yes | **Primary safety check** | Reference search/hierarchy ([registrations](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/resources/META-INF/scala-plugin-common.xml#L524-L541)) |

| Consumer | Current semantic route | Slot route? | Clean replacement/addition point | Required action and risk |
|---|---|---:|---|---|
| **Hover / quick navigation** | `ScalaDocumentationProvider` delegates to `ScalaDocQuickInfoGenerator`; it first `bind()`s the original reference for a substitutor, then renders declaration/function/parameter/binding `Typeable` values and PSI supertypes | Partial | Platform `documentationProvider` can be registered | P1: PC hover provider or wrap/precede bundled provider. Reusing current generator still invokes bundled bind/declaration types. **High** ([quick info](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/editor/documentationProvider/ScalaDocQuickInfoGenerator.scala#L35-L67), [provider](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/editor/documentationProvider/ScalaDocumentationProvider.scala#L32-L65)). |
| **Completion** | `ScalaBasicCompletionContributor` and processors walk PSI scopes/types; a helper requests/copies a compiler slot only for transparent-inline calls | Partial, narrow | Platform `completion.contributor` | P0: keep PC-native contributor/merger for members and generated symbols; make current snapshot win and suppress/deduplicate bundled candidates. Exact replacement may need contributor ordering/filter shim. **Critical** ([completion helper](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/completion/package.scala#L259-L286), [basic contributor](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/completion/ScalaBasicCompletionContributor.scala)). |
| **Go to declaration / resolve** | PSI reference `resolve/bind/multiResolveScala` and resolver processors | Only narrow stable qualifier | No general Scala resolve EP; platform navigation providers can override a UI surface, not `PsiReference.resolve()` | P0: intercept reference methods or upstream symbol provider; PC definition can service navigation but internal callers still need adapters. **Critical**. |
| **Semantic highlighting** | `ScalaAnnotator`/reference annotator bind names; compiler highlighting writes separate editor highlights. Semantic coloring is mixed with syntax/resolve | Indirect | `annotator`, `highlightInfoFilter`, `highlightingPassFactory`; PC has a semantic-tokens provider | P1: PC semantic-token pass owns semantic colors for current snapshots; retain lexer/syntax. Filter or patch bundled semantic annotations to avoid double writers. **High** ([`ScalaAnnotator`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/annotator/ScalaAnnotator.scala), [`PcSemanticTokensProvider`](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/PcSemanticTokensProvider.scala)). |
| **Inspections / analyzer** | `ScalaAnnotator` fans out to construct annotators; local/global inspections call expression/declaration types, resolve, conformance, implicit search and indexes independently | Partial | `localInspection`, `globalInspection`, `annotator`, filters can add/suppress presentation | P1: inventory each semantic inspection; disable/filter bundled Scala-3 semantic diagnostics under the gate and reimplement from PC diagnostics/semantic data, or retain explicitly syntax/style-only inspections. Slots alone are unsafe. **Critical/high** ([annotator fan-out](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/annotator/element/ElementAnnotator.scala#L25-L86)). |
| **Inline/inlay hints** | `ImplicitHintsPass`, type hints, parameter/type-argument/method-chain passes call typed definitions, expression types, implicit args and resolve | Partial | Platform inlay EPs and `highlightingPassFactory`; Metallurgy already has an editor-bound pass | P1: PC-native inferred-type/implicit/parameter hints, or populate all semantic keys before bundled passes. Disable/dedup bundled semantic hint passes for opted-in modules. **High** ([PC inlays](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/PcInlayHintsProvider.scala)). |
| **Structure view / navbar** | Mostly source/stub text (`Function` renders explicit annotation text); inherited-member node provider traverses PSI supers/members | Usually no; inherited path uses bundled semantics | `org.intellij.scala.structureViewModelProvider`, Platform structure view factory | P2 for source declarations; P1 if generated/inherited members must be compiler-complete. Add PC-backed nodes with navigation adapters; avoid pretending source-less symbols are PSI children. **Medium** ([function presentation](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/structure-view/src/org/jetbrains/plugins/scala/structureView/element/Function.scala), [provider EP](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/structure-view/src/org/jetbrains/plugins/scala/structureView/ScalaStructureViewModelProvider.scala)). |
| **Find usages / highlight usages** | Indexed candidate search followed by PSI reference resolution; `ScalaUsageTypeProvider` explicitly calls `referenceExpr.bind()`. Compiler reference indexes offer a separate build-derived path | No general type need, but symbol identity is bundled | `findUsagesHandlerFactory`, `referencesSearch`, `usageTypeProvider`; existing compiler-index handler | P1: stable PC/SemanticDB symbol IDs plus workspace occurrence index; current per-open-file PC is insufficient. Route/validate results by symbol, then map locations to PSI. **Critical** ([usage classification](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/findUsages/ScalaUsageTypeProvider.scala#L117-L141), [handler selection](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/findUsages/factory/ScalaFindUsagesHandlerFactory.scala#L69-L76)). |
| **Refactorings** | Rename, safe delete, inline, change signature, extract, move and introduce processors mix PSI reference search, inferred types, signatures, overriding and textual edits | Partial | Many Platform handler/processor EPs; PC supplies rename, inline-value, extract-method and inferred-method operations | P1: PC-native edits where offered; symbol index for workspace rename/usages; keep syntactic PSI mutations after validating version. Bundled handlers cannot all be globally replaced by one EP. **Critical/high** ([PC definition/rename-related providers](https://github.com/scala/scala3/tree/3.7.4/presentation-compiler/src/main/dotty/tools/pc), [Scala registrations](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/resources/META-INF/scala-plugin-common.xml#L524-L541)). |
| **Parameter/signature info** | Function/type parameter handlers resolve call target and render PSI parameter types | Partial | `codeInsight.parameterInfo` | P1: use PC signature help and suppress/dedup bundled handler. **High** ([`SignatureHelpProvider`](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/SignatureHelpProvider.scala)). |
| **Debugger/evaluator** | Runtime/compiler evaluator constructs executable/evaluable code; not editor type production | No | Debugger evaluator EPs | P2: out of “type authority” unless the goal includes debugger expression typing. Do not patch the debugger `ScalaTypeEvaluator` as if it were semantic PSI. **Medium**. |
| **Java/UAST/inter-language callers** | Scala light wrappers and `ScalaPsiTypeBridge` convert `ScType` to `PsiType`; UAST reads PSI resolve/types | Only through projected `ScType` | UAST/Java EPs exist, not a Scala semantic interception | P1/P2: preserve compatibility projection; PC symbols/types need stable light PSI/PsiType adapters. **High** ([`ScalaPsiTypeBridge`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/types/ScalaPsiTypeBridge.scala)). |

Closed files and batch analysis are load-bearing: an editor highlighting pass cannot guarantee that a batch inspection,
find-usages search, or refactoring sees a populated snapshot. Those entry points need an explicit cancellable preflight that
types/indexes the required files off-EDT, or a well-defined “snapshot unavailable” fallback which weakens the authority claim.

---

## 3. Extension points and topics

### 3.1 Scala-plugin seams Metallurgy can use

The core Scala EP declarations are together in
[`scala-plugin-common.xml`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/resources/META-INF/scala-plugin-common.xml#L4-L23);
compiler EPs are in
[`scalaCommunity.compiler-integration.xml`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/compiler-integration/resources/scalaCommunity.compiler-integration.xml#L7-L12).

| EP / Topic | Purpose | Register / subscribe? | Authority value | Risk / limitation |
|---|---|---:|---|---|
| `CompilerType.Topic` / `CompilerType.Listener` | One-shot synchronous request for an element's compiler string | **Subscribe** via message bus or listener declaration | Useful demand signal | Not a producer EP; one request bit per PSI element; no version in message; callback must not block. Medium |
| `CompilerEventListener.topic` | Compile-server compilation/progress/message events | **Subscribe** | Invalidate classpath/output epoch; observe CBH | Observation, not scheduler/result ownership. Medium |
| `CompilerReferenceServiceStatusListener.topic` | Compiler-reference indexing started/finished and indexed modules | **Subscribe** | Coordinate/compare workspace-index generations | Status only; no occurrence payload. Medium ([topic](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/references/CompilerReferenceServiceStatusListener.scala#L8-L20)) |
| `CompilerHighlightingListener.Topic` | Highlighting setting/state changes | **Subscribe** | Gate and invalidate sessions | No semantic payload. Low |
| `org.intellij.scala.syntheticMemberInjector` | Adds textual functions, inner types, supers and members to `ScTypeDefinition` | **Register** | Compatibility materialization for selected source-less members | Synchronous PSI augmentation, no arbitrary type/reference interception; duplicating compiler symbols is fragile. High ([API](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/toplevel/typedef/SyntheticMembersInjector.scala)). |
| `scalaSyntheticClassProducer` | Supplies synthetic classes to class lookup | **Register** | Navigation adapter for source-less top-level class symbols | Class-only, not members/types. High |
| `fileDeclarationsContributor` | Adds declarations during Scala-file tree walk and can mute unresolved compiler highlights | **Register** | Top-level compiler-generated source-visible declarations | File-scope only; not qualified members/general types. High ([API](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/FileDeclarationsContributor.scala)) |
| `referenceExtraResolver` | Fallback for stable references (used by Ammonite) | **Register** | Narrow stable-reference compatibility | Not term references; fallback semantics, not universal interception. High ([API](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/base/ScStableCodeReferenceExtraResolver.scala)). |
| `scalaDynamicTypeResolver` | Candidates for Scala `Dynamic` references | **Register** | Only `Dynamic` | Cannot affect ordinary resolve. Low ([API](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/resolve/DynamicTypeReferenceResolver.scala)). |
| `interpolatedStringMacroTypeProvider` | Type of interpolated-string macro | **Register** | Construct-specific bridge | Not a generic type provider. Low ([API](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/base/InterpolatedStringMacroTypeProvider.scala)). |
| `compilerSettingsProfileProvider` | Contributes compiler settings profiles | **Register** | Feed exact module options into session/population planning | Does not execute or intercept typing. Medium |
| `compileServerClasspathProvider` / `compileServerVmOptionsProvider` | Adds jars/VM options to server | **Register** | Could host isolated helper/populator | Does not replace `DocumentCompiler` or event application. High |
| `worksheetHighlightingCompiler` | Pluggable worksheet compilation | **Register** | Worksheet-only | No ordinary Scala files. Low |
| `structureViewModelProvider` | Adds structure-view providers/nodes | **Register** | PC-generated/source-less view nodes | UI-only. Medium |
| `unresolvedReferenceFixProvider` | Adds quick fixes | **Register** | PC auto-import/fix presentation | Runs after bundled unresolved decision; not resolve. Medium |
| `findUsages.externalReferenceSearcher` | Adds compiler-index reference results | **Register**, but implementation lookup is effectively single-winner | Natural integration for a SemanticDB occurrence store | Ordering/non-compositional implementation makes coexistence fragile. High ([API](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/findUsages/ExternalReferenceSearcher.java)) |
| `findUsages.externalInheritorsSearcher` | Adds compiler-index inheritors | **Register**, same single-winner caveat | PC/SemanticDB hierarchy | Needs durable symbol index, not per-file PC. High ([API](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/findUsages/ExternalInheritorsSearcher.java)) |
| `findUsages.externalSearchScopeChecker` | Decides external-index scope applicability | **Register**, same caveat | Gate compiler-index searches by module/generation | Policy only. Medium ([API](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/findUsages/ExternalSearchScopeChecker.java)) |
| `scalaElementToRenameContributor` | Adjusts the Scala element selected for rename | **Register** | Map proxy/light/source symbols to canonical rename target | Does not find usages or validate edits. Medium ([declaration](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/resources/META-INF/scala-plugin-common.xml#L9)) |
| `parameterInfoEnhancer` | Enhances Scala parameter-info UI | **Register** | Supplement PC signature help compatibility | UI only, native target resolution remains. Low ([declaration](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/resources/META-INF/scala-plugin-common.xml#L12)) |
| `memberElementTypesExtension` | Adds parser/AST member element-type classifications | **Register** | None for semantic authority | Parser classification, not members/types. Low ([declaration](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/resources/META-INF/scala-plugin-common.xml#L8)) |
| `genericTypeNamesProvider` | Suggests refactoring names for generic types | **Register** | PC-informed naming polish | Not semantic production. Low ([declaration](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/resources/META-INF/scala-plugin-common.xml#L14)) |
| `importUsedProvider` / `importOptimizerHelper` | Extends import-use accounting/optimization | **Register** | Preserve PC-selected imports/conversions in optimize-imports | Cannot change resolve; incorrect bundled `importsUsed` still needs replacement. Medium ([declarations](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/resources/META-INF/scala-plugin-common.xml#L20-L21)) |

### 3.2 Platform seams that help at consumer boundaries

| Platform EP family | Registerability | What Metallurgy can own | Limit / risk | Scala registration evidence |
|---|---:|---|---|---|
| `completion.contributor`, weighers/confidence | Clean additive, ordered | PC candidates, edits, ranking/dedup | Cannot transparently stop every bundled contributor; high ordering risk | [`scala-plugin-common.xml`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/resources/META-INF/scala-plugin-common.xml#L369-L459) |
| `lang.documentationProvider` / documentation target providers | Clean additive | PC hover and source/symbol docs | Provider selection/fallback and PSI target still matter; medium | [`ScalaDocumentationProvider`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/editor/documentationProvider/ScalaDocumentationProvider.scala) |
| `annotator`, `externalAnnotator`, `highlightVisitor`, `highlightInfoFilter` | Clean additive/filtering | PC diagnostics and semantic colors | Different pass owners/provenance can race or double-paint; high | [`ScalaAnnotator`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/annotator/ScalaAnnotator.scala), [`ExternalHighlightersService`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/ExternalHighlightersService.scala) |
| `highlightingPassFactory`, inlay-provider EPs | Clean additive | Exact-version PC hints/tokens | Must suppress/dedup bundled passes; high | [`ScalaTypeHintsPass`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/codeInsight/src/org/jetbrains/plugins/scala/codeInsight/hints/ScalaTypeHintsPass.scala) |
| `codeInsight.parameterInfo` | Clean additive | PC signature help | Native handler can still resolve/render independently; medium | [`scala-plugin-common.xml`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/resources/META-INF/scala-plugin-common.xml#L609-L611) |
| goto/target/declaration navigation providers | Clean UI interception | PC definition locations and proxy targets | Does not alter `PsiReference.resolve()` for other consumers; high | [`ScalaTargetElementEvaluator`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/codeInsight/ScalaTargetElementEvaluator.scala) |
| `referencesSearch`, `findUsagesHandlerFactory`, `usageTypeProvider` | Clean additive/handler selection | Symbol-index workspace search | PSI reference identity and competing handlers remain; high | [`ScalaFindUsagesHandlerFactory`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/findUsages/factory/ScalaFindUsagesHandlerFactory.scala) |
| rename/inline/extract/change-signature/safe-delete/move processors | Feature-specific additive/selection | PC edits and gated handlers | No one refactoring-semantic provider; each workflow needs version validation; high | [`scala-plugin-common.xml`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/resources/META-INF/scala-plugin-common.xml#L524-L541) |
| structure-view factory/node providers | Clean additive | Generated/inherited PC nodes | UI only; no global member semantics; medium | [`ScalaStructureViewModelProvider`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/structure-view/src/org/jetbrains/plugins/scala/structureView/ScalaStructureViewModelProvider.scala) |

These EPs are clean for **adding a PC-native surface**. They do not change `ScExpression.type()`,
`ScTypeElement.type()`, or `ScReference.bind()` for other bundled/plugin callers
([type reader](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/expr/ScExpression.scala#L301-L326),
[reference contract](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/base/ScReference.scala)).

`com.intellij.lang.psiAugmentProvider` is not a hidden answer. Its `inferType(PsiTypeElement)` is called by Java PSI;
Scala's `ScTypeElement` is unrelated and never consults it. Likewise `PsiReferenceContributor` cannot replace references
already owned and returned by Scala PSI implementations.

### 3.3 Missing upstream EPs — proposed contract

The minimum clean addition to `intellij-scala` is one project-level `org.intellij.scala.externalSemanticProvider` with
cache-only methods:

```scala
externalType(TypeKey): Option[ScType]
externalResolve(OccurrenceKey): Option[ExternalResolveResult]
externalMembers(TypeKey): Option[Seq[ExternalSymbol]]
currentSnapshot(file): SnapshotState // Pending | Current | Failed | Unavailable
```

It must be consulted by the shared wrappers around all `Typeable` roots, term/stable reference resolution, member
enumeration, and cache invalidation. `None` means fall back; `Current` with an empty answer is authoritative empty, not
“unknown.” A second compiler-result/highlight arbiter should assign single-writer ownership for diagnostics/types by exact
document version.

---

## 4. Overwrite points with no clean EP

| Priority | Overwrite point | Why extension is insufficient | Viable research mechanism | Risk |
|---:|---|---|---|---|
| P0 | [`Typeable.type()`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/types/result.scala#L10-L21) concrete roots, especially `ScTypeElement`, definitions/patterns/functions/parameters | Compiler slot is read only in expressions | Upstream dispatcher; otherwise version-pinned instrumentation of trait/class methods | **Critical**: broad binary/API coupling, recursion/caching |
| P0 | [`ScReferenceExpression`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/expr/ScReferenceExpressionImpl.scala#L59-L99) and [`ScStableCodeReference`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/base/ScStableCodeReferenceImpl.scala#L316-L332) `bind/multiResolveScala` | Existing resolver EPs are construct-specific/fallback-only | Upstream external-resolve lookup; otherwise instrument implementations and synthesize `ScalaResolveResult` adapters | **Critical**: nearly every IDE feature assumes PSI target |
| P0 | [`BaseProcessor.processType`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/resolve/processor/BaseProcessor.scala#L142-L237), method applicability/inference/implicit search | A PC-rendered `ScType` is fed back into bundled member and overload logic | Short-circuit from external symbol/member/selection snapshot; retain bundled processors only for fallback | **Critical** |
| P0 | [`BlockModificationTracker`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/caches/BlockModificationTracker.scala)-backed cached answers | User-data publication does not advance trackers | Supported publisher/invalidation transaction or reflective calls mirroring [`ExternalHighlightersService`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/ExternalHighlightersService.scala#L104-L145) | **Critical**: stale truth and invalidation storms |
| P0 | [Synthetic/generated member representation](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/toplevel/typedef/SyntheticMembersInjector.scala#L19-L77) | Injectors accept source text and type definitions, not arbitrary compiler symbol graphs | Virtual/light PSI navigation stubs + PC-native completion/resolve; selective injector only for stable mappings | **High** |
| P1 | [Bundled completion candidate production](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/completion/ScalaBasicCompletionContributor.scala) | Another contributor can add, but bundled candidates still appear | PC merger/filter and contributor ordering; instrumentation only if exact suppression is impossible | **High** |
| P1 | [`ScalaAnnotator`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/annotator/ScalaAnnotator.scala)/semantic highlighting and CBH external writer | Different passes can both paint; no universal provenance arbiter | Filters where provenance exists; separate pass IDs; upstream single-writer arbiter; last-resort wrap service/pass | **High** |
| P1 | [`CompilerHighlightingService`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/CompilerHighlightingService.scala), [`DocumentCompiler`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/DocumentCompiler.scala), `ExternalHighlightersService` | Compiler EPs add classpath/options, not replace scheduling/application | Coexist and observe topics, or reflect/wrap internal project service; prefer upstream compiler-result provider | **High** |
| P1 | [Find usages/compiler reference indexes](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/references/search/CompilerIndicesReferencesSearcher.scala) | Per-file PC has no workspace occurrence database | Build SemanticDB/best-effort symbol index and replace handler/search executors for gated modules | **Critical** correctness across closed files |
| P1 | [Refactoring processors](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/resources/META-INF/scala-plugin-common.xml#L524-L541) | Each feature owns validation/search/edit logic | Register PC-specific handlers where ordering permits; instrument/delegate remaining Scala handlers | **High** |
| P2 | [`ScalaPsiManager`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/ScalaPsiManager.scala#L709-L720) | Service is not open and is the wrong semantic layer | Do not replace; call supported invalidation if exposed, narrowly reflect otherwise | **High**, little payoff |

Reflection is appropriate for calling otherwise inaccessible helpers or extracting PSI/cache state. It cannot alter virtual
dispatch or a compiled method body. Wrapping PSI globally is also not viable: parser element types create concrete PSI
classes and bundled callers pattern-match them. Runtime instrumentation is scientifically possible but must be isolated in
one compatibility module, fingerprint target bytecode/version, fail closed to bundled behavior, and carry integration tests
for every supported Scala-plugin build.

---

## 5. Best-effort TASTy (`.betasty`) and population

### 5.1 What Scala 3.7.4 actually does

The official design says `-Ybest-effort` forces erroneous programs through typer and Pickler, writes `.betasty` under
`<output>/META-INF/best-effort`, and can also emit SemanticDB. `-Ywith-best-effort-tasty` allows those artifacts on a
consumer classpath and restricts compilation to frontend phases if one is used
([design](https://github.com/scala/scala3/blob/3.7.4/docs/_docs/internals/best-effort-compilation.md),
[`ScalaSettings`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/config/ScalaSettings.scala#L448-L449)).
The format has a distinct header and `ERRORtype`; it is experimental with no patch-version compatibility guarantee
([`BestEffortTastyFormat.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/tasty/besteffort/BestEffortTastyFormat.scala),
[`TreeUnpickler.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/core/tasty/TreeUnpickler.scala#L492-L505)).

The writer runs in `Pickler.runOn` and resolves the fixed `META-INF/best-effort` directory before
`BestEffortTastyWriter` writes per-class files
([`Pickler.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/transform/Pickler.scala#L412-L423),
[`BestEffortTastyWriter.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/core/tasty/BestEffortTastyWriter.scala)).
When a best-effort dependency is consumed, backend/transform phases explicitly stop running; the compiler tracks
`usedBestEffortTasty`
([`Contexts.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/core/Contexts.scala#L483-L496),
[`GenBCode.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/backend/jvm/GenBCode.scala#L22-L25)).

### 5.2 PC can consume but cannot populate — P0

`InteractiveDriver` always uses `InteractiveCompiler`, whose complete phase plan is only Parser, Typer, SetRootTree and
CookComments
([`InteractiveDriver.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/interactive/InteractiveDriver.scala#L28-L42),
[`InteractiveCompiler.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/interactive/InteractiveCompiler.scala#L8-L21)).
There is no Pickler, so adding `-Ybest-effort` to a PC session cannot emit `.betasty`. The PC is explicitly an intended
consumer of `.betasty`, via `-Ywith-best-effort-tasty`, not its producer.

### 5.3 Required population architecture

1. Run a **separate full Scala `Compiler`/`Driver` invocation** with the module's exact Scala 3.7.4 compiler, options,
   classpath and source closure, plus `-Ybest-effort` and an isolated output directory. Reusing/extending the compile server
   is operationally attractive, but this is not `PcSession` work.
2. Publish a complete population generation atomically: `.betasty`, optional SemanticDB, source hashes, compiler version,
   scalac options, dependency/output epochs. Never mix artifacts between generations.
3. Put dependency population outputs on downstream PC classpaths and enable `-Ywith-best-effort-tasty`. Recreate the
   `InteractiveDriver` when the artifact/classpath epoch changes; its own source notes that directory classpaths can change
   and it lacks a proper notification model
   ([`InteractiveDriver.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/interactive/InteractiveDriver.scala#L45-L67)).
4. Compile modules in dependency order, debounce edits, cancel/supersede old work, and retain the last complete generation
   until a new one is atomically ready. Errors are expected output, not population failure.
5. Pin producer and consumer to the exact compiler patch because `.betasty` compatibility is undefined. Never parse
   `.betasty` in Metallurgy's hand-written TASTy reader; let matching dotc consume it.

The actual classpath entry must be the generated **directory** `<out>/META-INF/best-effort`, not merely `<out>` and not a
jar: directory classpaths recognize `.betasty`, while the 3.7.4 zip/jar lookup selects `.tasty`/`.class` only
([`DirectoryClassPath.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/classpath/DirectoryClassPath.scala#L274-L286),
[`ZipAndJarFileLookupFactory.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/classpath/ZipAndJarFileLookupFactory.scala#L42-L56)).
Loading is lazy/reference-driven, and `InteractiveDriver`'s bulk tree scan hard-codes `.tasty`; a `.betasty` population
index cannot rely on `allTreesContaining`
([`InteractiveDriver.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/interactive/InteractiveDriver.scala#L59-L61),
[`BestEffortOptionsTests.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/test/dotty/tools/dotc/BestEffortOptionsTests.scala#L19-L40)).

Add `-Xsemanticdb` to the population invocation. Best-effort keeps extraction runnable and SemanticDB supplies durable
definitions, occurrences, signatures and synthetics for workspace indexing, but it is not an arbitrary-expression type
table
([`ExtractSemanticDB.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/semanticdb/ExtractSemanticDB.scala#L39-L59)).
`ERRORtype` becomes `PreviousErrorType`; treat that as an unavailable/hole result and never let it overwrite a current good
type
([`TreeUnpickler.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/core/tasty/TreeUnpickler.scala#L490-L502),
[`Typer.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/typer/Typer.scala#L4915-L4925)).

This population pass solves cross-file/module visibility and can seed a SemanticDB symbol-occurrence index. It does not
replace the current-file `InteractiveDriver` snapshot, which remains the low-latency source for unsaved text.

---

## 6. PSI–PC compatibility gaps

### 6.1 Position/range mapping is many-to-many — P0

PC queries use typed-tree paths around a source position. `Interactive.pathTo` deliberately skips zero-extent trees, and
synthetic trees often share, widen, narrow, or lack source spans
([`Interactive.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/interactive/Interactive.scala#L247-L262),
[`SourceTree.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/interactive/SourceTree.scala#L20-L55)).
Metals' PC code contains special handling for for-comprehension desugarings, synthetic case-class apply/unapply, implicit
arguments, extension methods and name spans rather than assuming 1:1 trees
([`MetalsInteractive.scala`](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/MetalsInteractive.scala#L87-L276)).

The bridge must use occurrence keys and ranked mappings, not `psi.getTextRange == tree.span` as a universal invariant.
Store source symbol IDs, role (definition/reference/type/selected apply), compiler span/name span, and optional PSI smart
pointer. Ambiguous or synthetic mappings remain PC-native and receive light navigation targets only when useful.

### 6.2 A type string loses semantic identity — P0

Rendered text discards the exact symbol, owner chain, overload, path dependence, capture/refinement details, imports used,
implicit/extension selection, and source location. Parsing it through `ScalaPsiElementFactory` may also resolve names in a
different PSI context. Keep a structured compiler type/symbol handle in the snapshot; render text only at presentation or
legacy `ScType` boundaries.

### 6.3 Source-less and generated declarations — P0/P1

Compiler symbols include case-class/enum/derives/given/extension/export products, implicit evidence parameters,
desugarings, inline expansion trees and other synthetic definitions. PC itself treats many of these specially in
definition and completion logic
([`PcDefinitionProvider.scala`](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/PcDefinitionProvider.scala),
[`CompilerSearchVisitor.scala`](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/CompilerSearchVisitor.scala#L89-L103)).
They need not—and often should not—be materialized as mutable Scala PSI. Use PC completion/definition/hover directly and
create minimal light elements only for Platform APIs that mandate `PsiElement`.

Macro annotations have a hard compiler-language boundary: the official `MacroAnnotation` contract says generated
definitions are not visible outside the expansion and explicitly says user-written code cannot see a new definition
([`MacroAnnotation.scala`](https://github.com/scala/scala3/blob/40be7608a48477951218ae3a8ac8749fe02ba988/library/src/scala/annotation/MacroAnnotation.scala#L17-L37)).
The stock interactive compiler has no Inlining phase, while the full compiler expands annotations there, after Pickler;
therefore neither ordinary PC typed trees nor `.betasty` are a source of user-visible annotation-added API
([`InteractiveCompiler.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/interactive/InteractiveCompiler.scala#L10-L20),
[`Inlining.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/transform/Inlining.scala#L100-L125),
[`MacroAnnotations.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/transform/MacroAnnotations.scala#L155-L177)).
Do not fabricate such PSI members; ordinary typer/desugar synthetics such as case-class, enum, export, given and extension
products remain in scope.

### 6.4 Libraries and TASTy PSI — P1

The bundled Scala plugin decompiles `.tasty` to outline text and reparses it as Scala PSI; that PSI is useful for UI
navigation but is not the compiler symbol graph. Overloaded signatures, private/synthetic details and exact positions can
be lossy. Keep it as a display/navigation adapter and key truth by compiler symbol plus artifact identity, not by equality
of decompiled PSI text.

### 6.5 Workspace semantics exceed one PC session — P1

`InteractiveDriver` retains opened buffers and discovers trees from output/classpath; its API does not provide a durable
workspace occurrence index. Find usages, rename, hierarchy and some inspections require closed files and dependency-order
knowledge. Best-effort SemanticDB/artifact population plus an indexed symbol store is therefore part of type-resolution
replacement, not an optional later optimization.

### 6.6 Failure, pending, and fallback semantics — P0

Authority needs an explicit state machine:

- `CurrentSuccess(value or empty)` — PC is authoritative.
- `Pending` — do not publish stale results as current; UI may retain last-known decoration only with visible provenance.
- `Failed` / `Unavailable` — bundled fallback is allowed, and the product must label this as fallback rather than PC truth.

Latest generation wins. A PC query returning no tree/result is not proof that bundled PSI is correct. Conversely, project
discipline applies: surprising PC answers first trigger verification of URI, offset/needle, flags, classpath and exact
compiler version; they are not classified as compiler limitations without upstream evidence.

---

## Prioritized epic catalog

### Phase A — authority substrate (P0)

- Define `PcSemanticSnapshot`: exact-generation structured types, occurrences/symbols, selected calls, members,
  implicits/extensions, diagnostics and mapping metadata.
- Add tests proving stale versions/classpath epochs can never publish; centralize coalesced Scala cache invalidation.
- Prototype the upstream `externalSemanticProvider` type/resolve/member contract. In parallel, isolate a version-fingerprinted
  instrumentation shim solely to test feasibility if upstream changes are unavailable.
- Extend PC reflection/API bridging beyond rendered type to definition, hover, semantic tokens, signature help, inferred
  type, completion and structured symbol IDs, mirroring Scala 3.7.4 PC implementations rather than reimplementing dotc.

### Phase B — interactive semantic replacement (P0/P1)

- Intercept all expression/type-element/declaration/pattern/function/parameter type roots.
- Intercept term and stable reference resolution; provide light PSI/`ScalaResolveResult` compatibility adapters.
- Make PC completion and signature help authoritative; suppress/deduplicate bundled semantic candidates.
- Replace semantic highlighting, diagnostics ownership, hover and all semantic inlay passes for gated modules.

### Phase C — workspace and best effort (P1)

- Build the separate full-compiler `-Ybest-effort` population service and exact-patch artifact lifecycle.
- Feed `.betasty` to downstream/current PC sessions with `-Ywith-best-effort-tasty`.
- Persist/index SemanticDB or equivalent symbol occurrences for closed-file find usages, rename and hierarchy.
- Route find usages and refactorings by compiler symbol; apply edits only after document/generation validation.

### Phase D — long tail and compatibility (P1/P2)

- Classify every bundled inspection as syntactic/style (retain) or semantic (PC-backed/disabled).
- Add generated/inherited structure-view nodes and library/light-element adapters where they materially improve UX.
- Validate Java/UAST/debugger interop, worksheets, decompiled TASTy navigation, dumb mode, scratch/code fragments and mixed
  Scala/Java projects.
- Maintain a per-supported-bundled-plugin compatibility matrix and fail closed when fingerprints or API assumptions change.

### Exit criteria for “PC authoritative”

The claim is true only when, for an opted-in current snapshot: (1) every type and reference answer originates in dotc;
(2) completion/hover/highlighting/inspections/hints agree on that snapshot; (3) closed-file usages/refactorings use the same
compiler symbol identity; (4) generated/source-less symbols have a deliberate PC-native or light-PSI representation; and
(5) fallback is explicit, observable, and never presented as compiler authority.
