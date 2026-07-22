# Metallurgy's definitive architecture: replacing IntelliJ's Scala type backend with Scala 3's compiler backend

**Status:** sole normative architecture and design document

**Baselines:** IntelliJ Platform 261.x, bundled Scala plugin 2026.1.20, Scala 3.7.4

**Decision:** pursue whole-file, versioned pc type population plus a pinned compatibility shim; retain Scala PSI as the
IntelliJ-facing object model, but select Scala 3's compiler backend instead of the bundled type engine in active modules.

This document supersedes every prior Metallurgy design, status, glossary, ADR, and research document. Those materials
are preserved under `docs/archive/` for provenance only and are non-normative. New architectural decisions amend this
document; they do not revive or add parallel ADRs.

Source coordinates below are pinned to the bundled Scala plugin tag `2026.1.20` and Scala tag `3.7.4`. Paths beginning
with `intellij-scala/` are relative to the bundled plugin repository; paths beginning with `scala3/` are relative to
the Scala 3 repository. Metallurgy coordinates are relative to this repository.

## 1. Goal and scope

The goal is deliberately stronger than “repair `Any` when the bundled plugin loses precision”:

> For an active Scala 3 module, replace IntelliJ's bundled Scala type backend with the real Scala 3 compiler, driven
> through the presentation compiler and supported by best-effort compilation, for every type IntelliJ observes.

After the exact document version's typed snapshot is published, a type-bearing PSI read selects the Scala 3 backend's
answer or an explicit unavailable/error result. It must not silently choose a conflicting bundled inference. The bundled
plugin remains the parser, PSI owner, UI integration, cache framework, refactoring framework, and the `ScType`
compatibility shell. This decision replaces its type-producing backend; it does not replace every IntelliJ subsystem.

This applies **only** when `ModuleDetectionService.isActive(module)` is true:

```text
Scala version >= 3.5  AND  the module is opted in  AND  Scala 3 CBH/compiler types are enabled
```

That predicate already exists at `src/main/scala/com/hmemcpy/metallurgy/module/ModuleDetectionService.scala:31-51`.
Every scheduler, tree extractor, side-table read, slot write, resolver contribution, synthetic declaration, cache
invalidation, and compatibility-shim entry must perform the same module check. A false gate means:

- no pc session creation, artifact fetch, retypecheck, PSI traversal, side-table allocation, slot mutation, cache flush,
  completion contribution, synthetic PSI, or background listener work;
- no semantic or UI behavior change in Scala 2, Scala 3.0–3.4, non-opted-in modules, and inactive modules in mixed
  projects;
- only the constant-time guard needed by a globally installed shim may execute. If “zero overhead” is interpreted as
  literally zero additional instructions, any process-wide method hook is disqualified; a third-party plugin then
  needs an upstream bundled-plugin dispatch seam. The implementation gate is therefore **no work beyond a cheap module
  predicate**, and the predicate cost must be measured.

### Explicitly out of scope

- Replacing the Scala parser, PSI tree, stubs, indices, editor mechanics, project import, debugger, worksheet/REPL, or
  build runner.
- Rebuilding steady-state compiler diagnostics. Compiler-based highlighting already owns that surface; Metallurgy's
  diagnostic pipeline stays transient plumbing unless a measured gap appears.
- Scala 2 and Scala 3 before 3.5.
- A one-to-one pc-tree/PSI-tree mapping. Position, ownership, symbol, and role shims are expected.
- Blocking synchronous PSI getters until pc finishes. During a pending or failed exact-version snapshot, IntelliJ must
  remain responsive and the backend state is explicitly `Pending`/`Unavailable`.
- Inventing members that Scala itself does not expose. In particular, Scala 3 macro annotations cannot add new
  definitions visible from user-written code; this is a language restriction, not a pc gap.
- Treating a rendered type string as sufficient for every compiler symbol. Compiler-only members require the symbol
  bridge described in sections 7 and 8.

## 2. Current bundled-plugin type-resolution architecture

### 2.1 There is no central `ScTypeEvaluator`

The bundled plugin has no production `ScTypeEvaluator` service or replaceable `typeOf(PsiElement)` dispatcher.
`Typeable` is only an interface with an abstract ``type(): TypeResult``
(`intellij-scala/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/types/result.scala:10-21`). Concrete PSI traits
and classes own their computations. This is the fundamental reason a single clean extension cannot replace the backend.

`ScType` is the bundled semantic algebra. It carries a project context/type system, alias and unpacking caches,
equivalence hooks, visitors, and renderers
(`intellij-scala/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/types/ScType.scala:13-77`). The one production
`ScalaTypeSystem` implements equivalence, conformance, bounds, Java `PsiType` bridging, and presentation for Scala 2 and
Scala 3 alike (`.../types/ScalaTypeSystem.scala:7-25`; `.../types/api/TypeSystem.scala:7-27`). Consequently, parsing a
pc-rendered type into an `ScType` changes the selected input type but later conformance, widening, member enumeration,
and Java bridging still execute bundled algorithms.

### 2.2 Expression type production

`ScExpression.type()` calls `getTypeAfterImplicitConversion`
(`.../psi/api/expr/ScExpression.scala:35-46`). That cached method obtains an initial type from
`getTypeWithoutImplicits`, computes an expected type, and may apply SAM adaptation or implicit conversion
(`ScExpression.scala:146-190`). Expected types are separately cached through `ExpectedTypes`
(`ScExpression.scala:226-253`), while `getNonValueType` normally delegates to the concrete expression's `innerType`
(`ScExpression.scala:258-299`).

`getTypeWithoutImplicits` is the strongest existing compiler seam. Its `BlockModificationTracker` cache first reads
`CompilerType(expr)`; when Scala 3 compiler highlighting and compiler types are enabled, it parses the string with
`ScalaPsiElementFactory.createTypeFromText`, otherwise it clears the slot and falls back to bundled inference
(`ScExpression.scala:301-328`). The slot itself is a copied user-data string plus a synchronous message-bus request
topic (`.../psi/impl/CompilerType.scala:7-30`).

This selects pc for a populated **expression's initial type**. It does not select pc for the
expected-type calculation, implicit search, adaptation, or nodes whose concrete ``type()`` never reaches an expression.

### 2.3 Definitions, type elements, functions, parameters, and patterns

The bypasses are structural, not exceptional:

- `ScTypeElement.type()` uses its own `BlockModificationTracker` cache and calls `innerType`; it never reads
  `CompilerType` (`.../psi/api/base/types/ScTypeElement.scala:15-41`).
- `ScPatternDefinitionImpl.type()` and `ScVariableDefinitionImpl.type()` choose a declared type element first. Only an
  unannotated definition delegates to its initializer expression
  (`.../psi/impl/statements/ScPatternDefinitionImpl.scala:37-56`;
  `.../psi/impl/statements/ScVariableDefinitionImpl.scala:31-43`). Therefore `val x = rhs` can inherit a populated
  initializer slot, while `val x: T = rhs` cannot.
- `ScFunctionImpl.definedReturnType` chooses the declared return type, `Unit`, or a super-member result
  (`.../psi/impl/statements/ScFunctionImpl.scala:168-192`); `getReturnType` caches the function's own ``type()`` and
  converts it to Java `PsiType` (`ScFunctionImpl.scala:155-165`).
- Parameter `outsideParamType` and `insideParamType` derive from the parameter's own ``type()`` and then adjust repeated
  and `into` types (`.../psi/api/statements/params/ScParameter.scala:58-84`).
- Pattern typing is distributed across concrete implementations. Examples include reference, typed, naming, tuple,
  extractor, and wildcard patterns under `.../psi/impl/base/patterns/`; pattern expected types are independently cached
  and recursively derived from the owning definition or extractor
  (`.../psi/api/base/patterns/ScPattern.scala:20-39,46-115`).

Populating `CompilerType` on a binding, definition, function, parameter, or `ScTypeElement` has no effect unless a shim
adds a reader to that concrete route.

### 2.4 Resolve, bind, and inference

`ScReferenceExpressionImpl.multiResolveScala` is cached by `BlockModificationTracker` and invokes
`ReferenceExpressionResolver.resolve` (`.../psi/impl/expr/ScReferenceExpressionImpl.scala:59-79`). The resolver builds
method/resolve processors, walks lexical declarations, applies precedence and applicability, and returns
`ScalaResolveResult` values. It is not delegated to CBH or pc. Qualifier/member resolution consumes `ScType`, so an
expression slot can indirectly change candidates; unqualified names, imports, overload applicability, implicit search,
and synthetic declarations still use the bundled resolver.

`ScStableCodeReferenceImpl` has a second direct compiler-slot read. For a qualified reference it parses the compiler
type under the same settings gate and extracts the designated symbol before falling through to normal resolution
(`.../psi/impl/base/ScStableCodeReferenceImpl.scala:470-493`). This is useful but remains reference-kind-specific.

Inference is likewise distributed: expression `innerType` implementations, expected types, method resolve processors,
constraint solving, implicit conversion/search, pattern inference, match-type reducers, and `ScType` conformance all
participate. A pc-derived `ScType` reaches many of these algorithms as an input; it does not replace them.

### 2.5 Compiler integration and CBH

CBH is a parallel asynchronous producer, not the core PSI type engine. `CompilerHighlightingService` serializes
compilation requests on a bounded executor, schedules document/incremental work, and invokes `DocumentCompiler`
(`intellij-scala/scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/CompilerHighlightingService.scala:47-65,105-166,294-313`).
`DocumentCompiler` compiles current document text through the remote compile-server connector
(`.../highlighting/DocumentCompiler.scala:46-73,123-149`); incremental work goes through `CompileServerCommand.CompileJps`
(`.../highlighting/IncrementalCompiler.scala:18-61`).

Compiler type requests are narrow: `CompilerTypeRequestListener` converts the topic event into a document compilation
request (`.../highlighting/CompilerTypeRequestListener.scala:11-23`). `ExternalHighlightersService` applies compiler
types only to exact-range `ScExpression` or `ScStableCodeReference` nodes in the focused editor
(`.../highlighting/ExternalHighlightersService.scala:76-99`). It version-checks the compilation before application,
clears Scala caches for every changed element, increments `anyScalaPsiChange`, and refreshes hints
(`ExternalHighlightersService.scala:104-145`).

CBH remains the isolation foundation and the build/artifact producer. Its diagnostics are not a substitute for pc type
population, and its type reports must not be allowed to overwrite a newer pc generation in active modules.

### 2.6 Cache topology

Type and resolve caches predominantly use `BlockModificationTracker(element)`, while broader PSI structures use
`ModTracker.anyScalaPsiChange`. `ScalaPsiManager.clearOnScalaElementChange` clears manager caches and walks outward until
it can increment the relevant stable-expression local counter (or the top-level tracker)
(`.../psi/impl/ScalaPsiManager.scala:610-631`). `ScalaPsiManager` also caches compound/intersection signature and type
maps (`ScalaPsiManager.scala:55-147`); it is a cache owner for `ScType` operations, not a general type evaluator.

This distinction is load-bearing: incrementing only `anyScalaPsiChange` does not invalidate an expression type cached
against its local `BlockModificationTracker`. Every changed backend value needs the per-element clear, followed by one
coalesced global increment for downstream usages and presentations.

## 3. Type consumers

The table describes steady-state behavior after a current pc snapshot exists. “Slot reaches it” means the consumer
eventually asks an expression for its type; it does not mean the entire subsystem selects the Scala 3 backend.

| Subsystem | Actual route in 2026.1.20 | Does a populated expression slot reach it? | Additional backend work |
|---|---|---:|---|
| Hover / quick navigation | `ScalaDocQuickInfoGenerator` first `bind()`s the original reference, then renders functions, values, bindings, and parameters through `TypeAnnotationRenderer` (`.../editor/documentationProvider/ScalaDocQuickInfoGenerator.scala:33-52,101-114,247-275,311-350`). The renderer calls function `returnType`, given `givenType`, or generic ``Typeable.type()`` (`.../types/api/presentation/TypeAnnotationRenderer.scala:32-61`). | **Sometimes.** Untyped vals may flow to a populated initializer. Explicit vals, functions, parameters, and pattern bindings bypass it. | Patch all typed-definition routes; retain a pc-native hover fallback for unmappable compiler symbols. |
| Completion | Bundled completion copies/request the compiler slot only for transparent-inline calls (`.../lang/completion/package.scala:259-286`) and otherwise uses bundled resolve/processors. Metallurgy already contributes pc completion. | **Yes for the copied call**, but only after that trigger. | Whole-file proactive population; retain pc completion for symbols/member sets that rendered `ScType` cannot enumerate. |
| Reference resolve / navigation | `ScReferenceExpressionImpl.multiResolveScala` invokes bundled `ReferenceExpressionResolver` (`.../psi/impl/expr/ScReferenceExpressionImpl.scala:59-79`); qualified stable references have their own slot reader (`.../psi/impl/base/ScStableCodeReferenceImpl.scala:470-493`). | **Indirectly** for qualifier/member resolution; **no** for general symbol identity. | Feed pc receiver types, then add a pc-symbol resolver/synthetic PSI bridge for compiler-only declarations. |
| Semantic annotator / type mismatch highlighting | `ScalaAnnotator` and element annotators call `getTypeAfterImplicitConversion`; `ScExpressionAnnotator` uses it with expected types (`.../annotator/ScalaAnnotator.scala:92-106`; `.../annotator/element/ScExpressionAnnotator.scala:168-181`). CBH overlays compiler diagnostics separately. | **Yes** for actual expression type. | Expected types, implicit adaptation, and non-expression annotations remain bundled unless the backend dispatcher covers them. Do not duplicate CBH diagnostics. |
| Inspections / analyzer | Mixed. Type-aware inspections call expression typing or generic `Typeable`; others call `bind()` or are syntactic. Collection inspections explicitly expose a `Typeable` backed by `getTypeWithoutImplicits` (`.../codeInspection/collections/package.scala:516,550-560`). | **Mixed.** | The dispatcher covers type reads; the symbol bridge covers bind-based checks. Syntactic inspections remain unchanged. |
| Inline/type hints | Bundled `ScalaTypeHintsPass` calls value/variable ``type()`` and function `returnType` (`intellij-scala/scala/codeInsight/src/org/jetbrains/plugins/scala/codeInsight/hints/ScalaTypeHintsPass.scala:127-147`). `ImplicitHintsPass` hosts the pass and also runs implicit-resolution hints (`.../codeInsight/implicits/ImplicitHintsPass.scala:42-80,108-135`). | **Untyped values only**, through their initializer; not explicit definitions/functions. | Keep Metallurgy's pc hint renderer initially; after full dispatcher coverage, decide whether it is redundant. |
| Structure view | Primarily stub/source text: function return type text, val/var declared type text, and parameter type text (`intellij-scala/scala/structure-view/src/org/jetbrains/plugins/scala/structureView/element/Function.scala:20-26`; `ValOrVar.scala:20-24`; `element/package.scala:17-28`). Anonymous-class location resolves a type element (`ScalaAnonymousClassTreeElement.scala:36-42`). | **Generally no.** | This is presentation of source syntax, not inferred semantics. Leave it alone except compiler-synthetic declarations and the anonymous-class resolver. |
| Find usages | Searchers use indices/`ReferencesSearch` and validate with `PsiReference.resolve`/`isReferenceTo`; e.g. `ObjectTraitReferenceSearcher` (`.../findUsages/typeDef/ObjectTraitReferenceSearcher.scala:12-32`) and operator search (`.../findUsages/OperatorAndBacktickedSearcher.scala:25-100`). | **Only indirectly** when resolve depends on a qualifier type. | Stable pc-symbol-to-PSI identity is required for compiler-only members; type strings alone are insufficient. |
| Refactorings | Mixed: rename/move/find-super use PSI references and search; extract/introduce/change-signature/inline also call ``type()``, `returnType`, expected types, conformance, and Java `PsiType` bridges. `TypeAnnotationRenderer` itself lists override/implement and member-info consumers (`.../types/api/presentation/TypeAnnotationRenderer.scala:43-50`). | **Mixed.** | Treat refactorings as the highest-risk compatibility suite: type dispatcher plus stable symbol identity, with per-refactoring fallbacks when pc-to-PSI mapping is unavailable. |

The practical conclusion is that whole-file expression population has high impact, but it is not the definition of
“all.” Typed definitions and symbol identity are separate architectural workstreams.

## 4. Clean extension points and topics

The bundled Scala EP declarations are together in
`intellij-scala/scala/scala-impl/resources/META-INF/scala-plugin-common.xml:4-27`.

| Extension / topic | What it injects | Fit for replacing the type backend | Required isolation |
|---|---|---|---|
| `CompilerType.Topic` | Synchronous notification that a compiler type was requested (`.../psi/impl/CompilerType.scala:12,21-30`). | Useful demand signal and cache lookup trigger; not a universal producer. Listener must never wait for pc. | Resolve element → module and return immediately unless `isActive`. |
| Compiler event topic | Build/compile-server events consumed by current `CompilerTypeReportInterceptor`. | Useful to suppress or reconcile late CBH type reports. Not the primary population mechanism. | Ignore inactive modules and reject any report whose document version/generation is not current. |
| `org.intellij.scala.syntheticMemberInjector` | Textual functions, inner types, supers, and members on an `ScTypeDefinition` (`.../psi/impl/toplevel/typedef/SyntheticMembersInjector.scala:19-63,82-107,143-180`). | Secondary compatibility adapter for a small, stable set of compiler-visible members. Not a general Scala 3 backend vector; synchronous and historically macro-oriented. | Gate on the source definition's module; cache-only; never launch pc in the callback. |
| `org.intellij.scala.scalaDynamicTypeResolver` | Resolve results for `ScReferenceExpression` dynamic handling (`.../lang/resolve/DynamicTypeReferenceResolver.scala:7-18`). | Narrowly useful for actual `scala.Dynamic` paths, not arbitrary members. | Gate every expression. |
| `org.intellij.scala.interpolatedStringMacroTypeProvider` | Result type for interpolated-string macros (`.../psi/impl/base/InterpolatedStringMacroTypeProvider.scala:12-38`). | Clean, exact fit for that construct; use current snapshot only. | Gate the literal's module and return bundled fallback on cache miss. |
| `org.intellij.scala.memberElementTypesExtension` | Adds AST token kinds treated as members (`.../util/MemberElementTypesExtension.java:7-23`; `.../lang/TokenSets.scala:60-80`). | Not a type provider. Relevant only if Metallurgy introduces a new PSI member element kind, which is not recommended. | A process-wide token-set change would leak to inactive modules; avoid it. |
| `org.intellij.scala.scalaSyntheticClassProducer` | Supplies `PsiClass` values for FQNs (`.../lang/resolve/SyntheticClassProducer.scala:8-21`). | Useful for compiler symbols with no class PSI, especially generated top-level/classpath-visible types. Internal API and identity-sensitive. | Check request scope/project and return nothing unless a matching active-module snapshot/artifact exists. |
| `org.intellij.scala.referenceExtraResolver` | Fallback for stable code references, originally for Ammonite (`.../psi/impl/base/ScStableCodeReferenceExtraResolver.scala:11-38`). | Useful for stable/type references backed by pc symbols; not `ScReferenceExpression`. | `acceptsFile` must be an exact active-file gate; resolver must be cache-only. |
| Platform `completion.contributor` | Adds/reorders completion items. | Already appropriate for pc-native member/symbol completion. | Early file/module gate before session lookup. |
| Platform `highlightingPassFactory` / inlay APIs | Schedules a daemon pass and renders inlays. | Appropriate scheduler for proactive population and measurement; backend population should eventually be a dedicated pass rather than coupled to visible hints. | Factory and pass both gate; no pass object with pc state for inactive files. |
| Platform documentation/navigation EPs | Can provide pc-native documentation or navigation targets. | Fallback for compiler symbols that cannot be represented by normal Scala PSI. Does not change core ``type()``. | Gate the source element and use exact-version cache only. |
| Platform `PsiAugmentProvider` | Adds Java PSI members and can infer a Java `PsiTypeElement` (`intellij-community/java/java-psi-api/src/com/intellij/psi/augment/PsiAugmentProvider.java:38-40,47-67,91-106`). | **Not** a Scala type hook. Scala `ScTypeElement` never calls it; Java's `PsiTypeElementImpl` does. | Do not register a broad provider for this design. |
| Platform reference/search/refactoring EPs | Add reference contributors, search executors, rename handlers, etc. | Surface-specific escape hatches for synthetic pc symbols, not a coherent compiler-backend layer. | Every callback must derive and check the originating module; global search contributions must filter scope/results. |

Clean EPs should be preferred for completion, interpolation, stable references, synthetic classes, and UI fallbacks.
They do not eliminate the overwrite points below.

## 5. Overwrite points with no clean EP

The compatibility layer is version-pinned to 261.x / bundled Scala plugin 2026.1.20. It should expose one internal
contract—`Scala3CompilerBackend.lookup(element, role)`—even if the underlying interception uses reflection, method handles,
or bytecode instrumentation. Every hook performs the gate before consulting state.

| Priority | Target | Shim strategy | Risk |
|---|---|---|---|
| P0 | `ScTypeElement.getType` / `getNonValueType` (`.../psi/api/base/types/ScTypeElement.scala:23-41`) | Intercept before `innerType`; return the current side-table `ScType` for role `DeclaredType`, otherwise call the original. | High: private/package visibility, cache wrapper shape, constructors and singleton type syntax. |
| P0 | `ScPatternDefinitionImpl.type` and `ScVariableDefinitionImpl.type` (`.../psi/impl/statements/...:41-52` and `:37-41`) | Intercept at the definition and per-binding pattern. Use pc result keyed by declaration symbol/range; never reuse the whole RHS type for destructuring bindings. | High: multiple bindings, literal widening, stable paths. |
| P0 | All concrete `ScPattern.type` implementations and pattern `expectedType` | Weave the shared backend-selection prelude into implementors discovered and pinned by a compatibility manifest; fall through when no exact mapping exists. | Very high: distributed implementations and recursion-sensitive expected-type caches. |
| P0 | `ScFunction` return/defined type, `ScGiven` type, and `ScParameter.type`/inside/outside type | Intercept semantic roots, not every presentation caller. Preserve wrappers such as method/poly types and repeated/by-name/`into` adjustments where the pc DTO records their role. | Very high: Java `PsiMethod` bridges, override checks, polymorphic/method types. |
| P0 | `ScExpression.getTypeWithoutImplicits` | Keep the existing slot reader for compatibility, but make the backend side table/version guard the source of truth. A patched prelude can avoid stale copied user data and distinguish pending/unavailable. | Medium: hottest path; any extra lookup affects active-module editor latency. |
| P0 | Cache invalidation | For every element whose committed compiler value changed or disappeared, reflectively call `ScalaPsiManager.clearOnScalaElementChange`; after the batch increment `anyScalaPsiChange` once, mirroring `ExternalHighlightersService.scala:126-134`. | High: under-invalidation returns stale bundled types; over-invalidation causes resolve storms. |
| P1 | `ScReferenceExpressionImpl.multiResolveScala` / resolver fallback | If bundled resolve fails or resolves a member inconsistent with the pc symbol, contribute a `ScalaResolveResult` backed by mapped or synthetic PSI. A general extra-resolver shim is required because no EP exists for arbitrary reference expressions. | Very high: precedence, overloads, implicit conversions, navigation identity. |
| P1 | `ScStableCodeReferenceImpl` compiler-type branch | Reconcile it with the versioned compiler-backend store; keep `referenceExtraResolver` for symbols that cannot be extracted from parsed `ScType`. | Medium: direct method/body coupling. |
| P1 | `ScType` member/signature enumeration and conformance hot spots | First use the parsed pc-rendered `ScType`. When that loses compiler-only structure, attach a pc type handle/symbol id and intercept member lookup/conformance selectively rather than recreating all dotc types. | Extreme: many consumers assume concrete `ScType` shapes, equality, substitutors, and stable PSI owners. |
| P1 | Scala 3 synthesized/derived members | Materialize minimal light/synthetic PSI from pc symbol DTOs, using `SyntheticMembersInjector`/`SyntheticClassProducer` where their contracts fit and resolver shims elsewhere. | High: stable identity across snapshots, navigation, find usages, refactoring. |
| P2 | CBH compiler type application | In active modules, reject or replace a late native compiler type if it does not match the current pc generation; inactive modules remain untouched. | Medium: races with completion and editor focus; reflection into private compiler-integration services. |

### Shim delivery choice

There are two viable delivery modes:

1. **Preferred long-term:** an upstream bundled-plugin `ExternalCompilerBackend` dispatcher invoked at the semantic roots.
   It is synchronous/cache-only and receives the PSI element and role. Metallurgy supplies the provider. This is the
   only route with maintainable coverage and a deliberately optimized inactive fast path.
2. **Research implementation:** a startup compatibility transformer pinned to the exact bundled build, with a manifest
   of target class/method descriptors and bytecode fingerprints. Installation must fail closed: if any fingerprint is
   unexpected, disable the Scala 3 backend for the project and leave the bundled plugin untouched. Reflection alone can read
   fields and call private invalidators, but cannot replace method bodies; a transformer or patched bundled plugin is
   required for the definition/type-element routes.

The Phase 0 tracer bullet selects the research implementation. On JBR 25 it installs Byte Buddy's agent at Metallurgy
startup, defines a minimal Java bridge in the bundled Scala plugin's classloader, and retransforms the default
`ScTypeElement.type(): Either` method. The transformer inserts a cache-only call to `Scala3CompilerBackend` before the
original body. The bridge remains disabled until retransformation succeeds. The exact 2026.1.20 class and method
SHA-256 fingerprints are checked before the agent or bridge is installed; a mismatch leaves the original bytecode and
behavior intact. Dynamic agent loading currently succeeds on the target JBR but emits the JDK's deprecation warning, so
a future hardened runtime may disable this research hook. That failure is fail-closed and strengthens the case for the
preferred upstream dispatch seam.

The rejected alternatives for this milestone are a locally patched Scala plugin, because it would change the tested
deployment unit, and a reflection-only wrapper, because reflection cannot replace the cached default method. The tracer
measures the unavoidable globally woven guard in inactive modules. On the target test runtime, 20,000 warmed calls
through the transformed method measured approximately 0.8 microseconds additional p50 latency, 1.0 microsecond
additional p95 latency, and 56 incremental bytes per call relative to the same bridge disabled. This is acceptable only
for the research tracer: it performs no compiler work or Metallurgy state publication, and all inactive behavior remains
bundled. Literal zero additional instructions or allocations requires the upstream dispatch seam and is a release
blocker before broad distribution.

Pre-seeding private cached-value keys, substituting parser-created PSI wrappers, or overriding `ScalaPsiManager` are not
sound substitutes. The cache keys are implementation details, parser element types determine concrete PSI, and
`ScalaPsiManager` is not a type dispatcher.

## 6. BETASTY and best-effort compilation

Scala 3.7.4 exposes two distinct flags: `-Ybest-effort` writes best-effort TASTy during pickling, and
`-Ywith-best-effort-tasty` permits consuming it (`scala3/compiler/src/dotty/tools/dotc/config/ScalaSettings.scala:448-449`).
In a full compiler run, `Pickler` is forced runnable for best effort, serializes error-bearing trees, and writes
`.betasty` under `META-INF/best-effort` (`.../transform/Pickler.scala:192-202,264-312,380-420`;
`.../core/tasty/BestEffortTastyWriter.scala:11-42`). The loader recognizes `.betasty` only when consumption is enabled,
then marks the context as having used best-effort TASTy (`.../core/SymbolLoaders.scala:474-502`).

The interactive compiler cannot itself emit `.betasty`: its phase plan is only parser, typer, root-tree setup, and
comment cooking (`scala3/compiler/src/dotty/tools/dotc/interactive/InteractiveCompiler.scala:10-20`), while the full
compiler places `Pickler` after frontend phases (`.../Compiler.scala:29-56`). `-Ybest-effort` makes a full run push
through typer toward pickling (`.../Run.scala:348-380`); it does not add Pickler to the interactive phase plan.

Therefore the architecture has two cooperating loops:

- **Per-document pc loop:** in-memory, debounced, no output; produces the exact current file's typed tree and type/symbol
  snapshot.
- **Best-effort build loop:** CBH/compile-server or a dedicated full-dotc invocation writes `.betasty` for broken
  upstream modules. Downstream pc sessions add `<output>/META-INF/best-effort` as a classpath root and pass
  `-Ywith-best-effort-tasty`. Metallurgy already exposes those roots
  (`src/main/scala/com/hmemcpy/metallurgy/pc/PcSessionManager.scala:244-256,282-292`) and manages both flags
  (`src/main/scala/com/hmemcpy/metallurgy/build/ScalacFlagsService.scala:16-45`).

Best effort improves cross-module symbol availability; it is not the mechanism for typing the current unsaved buffer.
The current buffer is always the interactive driver's `(fileUri, documentVersion)` snapshot.

### Performance model

One retypecheck is paid per debounced file version. The population pass must traverse the resulting typed tree once and
render each retained canonical span once. It must not issue one `pathTo`/hover query per PSI node. Expected cost is:

```text
retypecheck O(compiler frontend) + typed-tree walk O(tree nodes) + PSI mapping O(retained spans log PSI nodes)
```

Rendering stays in the isolated pc classloader and crosses the boundary only as Java/Scala-stdlib-safe DTOs: ranges,
role, rendered type, symbol id, flags, and optional owner/navigation data. A type/symbol is rendered lazily only if its
span can feed a supported PSI role; duplicate spans are resolved before rendering.

## 7. PSI ↔ pc compatibility gaps

The compatibility goal is not tree isomorphism. It is a stable answer for each IntelliJ semantic role.

| Gap | Why it happens | Graceful strategy |
|---|---|---|
| Different tree granularity | `Inlined`, `Apply`, `TypeApply`, desugared blocks, synthetic closures, and wrappers can share or omit source spans. | Group by normalized span; rank trees by role-specific priority; retain the pc symbol/owner to break ties. Never choose by “deepest tree” alone. |
| Multiple PSI nodes at one range | A name, binding, definition, and typeable wrapper may overlap. | Map one compiler DTO to several explicit roles; the side table key includes PSI role, not only range. |
| Destructuring bindings | The RHS type is not each bound variable's type. | Map pc `ValDef`/pattern symbols individually. If no individual symbol exists, leave that binding unavailable rather than publish the tuple/RHS type. |
| Declared vs inferred type | PSI prefers source type syntax; pc may expose an alias-reduced, singleton, widened, or inferred view. | Store separate roles (`Declared`, `Inferred`, `InsideParameter`, `OutsideParameter`, `ExpressionExact`, `ExpressionWidened`) and make each shim request the correct role. |
| Compiler-only/synthetic declarations | Derived, inline-expanded, or best-effort-loaded symbols may have no source PSI. | Create minimal stable light PSI keyed by compiler symbol id and generation; navigation points to the nearest source owner or TASTy/decompiled owner. Completion/hover can remain pc-native if no safe PSI exists. |
| Rendered syntax not parsable as `ScType` | Dotc display text may contain compiler names, captures, anonymous refinements, or forms the bundled parser cannot reconstruct. | Prefer a source-compatible renderer; validate with `createTypeFromText`; on failure retain a display string and pc type handle for pc-native surfaces, and mark `ScType` unavailable. Never substitute `Any`. |
| `ScType` semantic mismatch | Parsed types subsequently use bundled conformance/member algorithms. | Measure first. Add narrow pc-backed conformance/member shims only where mismatches are observed; do not replace the entire `ScType` algebra speculatively. |
| Reparse copies user data | `CompilerType` uses copyable user data, so stale strings can survive PSI replacement. | The side table's `(fileUri, documentVersion, generation)` determines freshness. A slot without a matching current entry is ignored/cleared. |
| Best-effort error types | `.betasty` legitimately contains error placeholders. | Preserve `Unavailable/Error` provenance. Publish nearby valid types and symbols; never disguise an error placeholder as a valid Scala 3 compiler result. |

Scala 3 macro annotations are not a synthetic-member use case for new public members: upstream behavior intentionally
does not make newly added definitions visible to user-written code. Existing overrides/private helpers may affect
implementation but do not justify inventing public PSI members.

## 8. Recommended architecture

### 8.1 Components

1. **`Scala3CompilerSnapshot`** — immutable results for
   `(module, fileUri, documentVersion, classpathGeneration, compilerOptionsGeneration)`. It contains canonical typed
   spans, rendered-type DTOs, compiler symbol DTOs, diagnostics provenance, and extraction timings.
2. **`PcTypedTreeExtractor`** — runs inside the pc classloader after the one retypecheck. It traverses the compilation
   unit's `tpdTree`, filters invalid/unpositioned/error-only nodes, groups by span, selects role candidates, renders
   types there, and returns boundary-safe DTOs. Scala 3.7.4's driver forces `YretainTrees`, records both top-level opened
   trees and the full `CompilationUnit`, and stores the unit after `run.compileSources`; the full walk therefore starts
   at `compilationUnits(uri).tpdTree`, not the smaller `openedTrees(uri)` list
   (`scala3/compiler/src/dotty/tools/dotc/interactive/InteractiveDriver.scala:27-57,144-169`).
3. **`PcPsiMapper`** — under a cancellable non-blocking read action, maps DTO spans/symbols to current PSI elements and
   semantic roles. It creates smart pointers only for the short commit window; persistent identity is URI/version/range/
   role/symbol id, not a raw PSI object.
4. **`Scala3CompilerBackend`** — atomically publishes immutable mapped snapshots and answers synchronous cache-only
   lookups. Its first instruction is the active-module gate. It exposes `Current`, `Pending`, `Unavailable`, and `Failed`,
   not `Option[String]` alone.
5. **`Scala3CompilerPublisher`** — commits expression/reference compatibility slots, computes the changed/removed
   element set, performs per-element Scala cache clears, increments the global tracker once, and restarts only the
   necessary daemon/hint/completion surfaces.
6. **`BundledCompilerBackendShim`** — intercepts the P0 semantic roots in section 5 and asks the store for a role-specific
   `ScType`. It parses/caches source-compatible rendered text in the bundled classloader. On inactive modules or no
   current compiler value it invokes the original implementation.
7. **`PcSymbolBridge`** — maps compiler symbol ids to source PSI or stable light PSI and supplies completion, stable
   reference, arbitrary reference fallback, navigation, and search identity.

### 8.2 Population pass shape

The pass is deliberately bulk and version-guarded:

```text
capture file URI + text + document version + module/classpath/options generation
  -> gate ModuleDetectionService.isActive(module)
  -> background InteractiveDriver.run(uri, text) once
  -> obtain compilationUnits(uri).tpdTree and current Context
  -> foreachSubTree:
       collect positioned, typed candidates
       normalize [spanStart, spanEnd), role, symbol, exact/widened policy
  -> group and deduplicate by span + role + symbol
  -> render selected types inside the pc classloader
  -> return immutable boundary-safe DTO snapshot
  -> non-blocking read action maps DTOs to PSI for the captured version
  -> commit only if URI, document version, module gate, session generation,
     classpath generation, and compiler-options generation are unchanged
  -> atomically publish side table
  -> write/clear compatibility CompilerType slots
  -> clearOnScalaElementChange(changedElement) for every changed/removed element
  -> increment anyScalaPsiChange once and restart affected presentations
```

The existing reflected driver already owns the relevant ingredients: `InteractiveDriver.run`, `currentCtx`,
`openedFiles`, `openedTrees`, tree spans, type normalization/rendering, and a single-writer lease
(`src/main/scala/com/hmemcpy/metallurgy/pc/PcInlineTypeDriver.scala:24-43,83-111,119-203,233-248`). It should gain one
bulk-extraction call rather than looping over `typeAt`. `PcSession` already debounces by 300 ms, supersedes generations,
serializes retypechecks, and publishes only the winning driver/snapshot
(`src/main/scala/com/hmemcpy/metallurgy/pc/PcSession.scala:107-121,164-209,349-365`).

### 8.3 Backend selection rules

- A `Current` pc result wins over every bundled type for the same element/role/version.
- `Pending` never exposes the previous document version. UI may temporarily use the original bundled implementation,
  but it is explicitly a pre-publication fallback, not a competing backend result.
- `Unavailable` in rollout/audit mode uses the original bundled path and records the gap. In the target strict-backend
  mode, a successfully published current snapshot with no safe pc mapping returns an explicit `TypeResult` failure or a
  pc-native surface result; it does **not** silently treat bundled inference as Scala 3 compiler output.
- `Failed` leaves bundled behavior intact and surfaces telemetry/logging, never a guessed type. This is an operational
  failure fallback, distinct from a successful current snapshot that lacks a mapping.
- The raw `CompilerType` string is a compatibility cache, not the freshness guard.
- All cross-file and best-effort symbols carry classpath/options generation so a session rebuild invalidates them.

### 8.4 Why the current inlay pass is not the final owner

`PcTypeHintsPass` already awaits the exact typed snapshot and writes slots for simple untyped value definitions
(`src/main/scala/com/hmemcpy/metallurgy/feature/inlay/PcTypeHintsPass.scala:23-49,71-91,114-124`). That is the right
prototype scheduler. It is the wrong final semantic boundary because hints can be disabled, it visits only selected
definitions, it queries one offset at a time, and its current project-wide invalidation does not clear each expression's
`BlockModificationTracker` (`PcTypeHintsPass.scala:69`; `BundledPluginBridge.scala:120-137`). The first milestone should
instrument and broaden this pass; the later milestone should split population into a dedicated compiler-backend pass and let
hints consume its snapshot.

## 9. Cache invalidation and threading

### Thread ownership

- Document text/version capture: short read action or document-safe read.
- pc retypecheck/tree traversal/rendering: bounded pooled executor, never EDT, one writer per session/file driver.
- PSI mapping: `ReadAction.nonBlocking`, cancellable, expired by version/generation/gate changes.
- Publication/user-data mutation/cache invalidation: short UI finish step matching the bundled service's pattern
  (`ExternalHighlightersService.scala:139-145`). No compiler work occurs there.
- Synchronous type/resolve hooks: cache-only. They never schedule-and-wait, acquire the pc writer lock, invoke EDT, or
  enter a long read action.

### Deadlock avoidance

The forbidden cycle is: PSI getter holds read action → waits for pc → pc completion/publication needs read action or EDT.
The store therefore answers immediately. A topic listener may schedule work, but `CompilerType.Topic` uses a synchronous
publisher and must return without awaiting it (`.../psi/impl/CompilerType.scala:21-25`). Cancellation checks occur during
tree traversal, PSI mapping, and any bounded polling; production code uses futures/latches, never `Thread.sleep`.

### Generations and reparses

Every result carries document, session, classpath, options, and backend generations. “Latest generation wins” is
checked both before mapping and immediately before commit. A reparse invalidates PSI pointers but not range/symbol DTOs;
the mapper must reacquire PSI from the current file. Copyable user data copied onto new PSI is ignored unless the
side-table entry matches the exact current generation. Closing/disabling a module removes its store entries, clears only
Metallurgy-owned slots/synthetic PSI, retires drivers off EDT, and performs no work in other modules.

### Invalidation algorithm

For a successful current commit:

1. Diff old and new mapped values by stable key and rendered/structural value.
2. Write new slots and clear disappeared Metallurgy-owned slots.
3. Call `clearOnScalaElementChange` for each changed or removed semantic root, including the expression whose
   `getTypeWithoutImplicits` cache is keyed locally.
4. Increment `anyScalaPsiChange` once for usages and broader presentations.
5. Refresh hints/daemon only if the diff is non-empty.

Do not invalidate for identical republished values. Bound a pathological change set by falling back to a documented
file/top-level clear, and measure that path separately.

## 10. Phased implementation plan

### Phase 0 — Baseline and instrumentation contract

- Define compiler-backend roles/states/generation keys and the exact inactive fast-path contract.
- Add timings and counters around current retypecheck, `typeAt`, PSI traversal, cache invalidation, and daemon restart.
- Prototype the 2026.1.20 shim on one declared-type root with bytecode fingerprint/fail-closed behavior.
- **Go/no-go:** no behavior outside active modules; inactive hook overhead statistically indistinguishable from baseline;
  compatibility failure disables the Scala 3 backend cleanly.

### Phase 1 — Pc everywhere in the existing pass, behind measurement

- Add bulk `tpdTree` extraction and replace per-binding `typeAt` queries.
- Map and publish all safely matched expressions while still scheduling from `PcTypeHintsPass`.
- Perform version-guarded commit and correct per-element invalidation.
- Keep visible hints behavior unchanged so the experiment has a narrow UI footprint.
- **Go/no-go:** exact-type tests stay exact; no stale type after edit/reparse; one retypecheck and one tree walk per version.

### Phase 2 — Typed definitions and role-specific backend selection

- Activate shim coverage for type elements, vals/vars and individual bindings, functions/givens, parameters, and concrete
  patterns.
- Preserve distinct declared/inferred/inside/outside/exact/widened roles.
- Add consumer tests for hover, bundled hints, annotator, representative inspections, Java `PsiType` conversion, and
  refactoring previews.
- **Go/no-go:** after publication, no tested semantic root returns a conflicting bundled type; fallbacks are counted and
  explainable.

### Phase 3 — Resolver and synthetic-symbol bridge

- Map pc symbols to existing PSI where possible.
- Add stable light PSI for unmapped compiler-visible declarations, then wire stable references, general reference
  fallback, completion, navigation, find usages, and selected refactorings.
- Use clean EPs before resolver method shims.
- **Go/no-go:** stable identity across an unchanged version; edits retire old identities; no synthetic result appears in
  an inactive module or leaks through project-wide searches.

### Phase 4 — BETASTY lifecycle

- Prove full-dotc/CBH emission and downstream pc consumption across broken upstream modules.
- Version best-effort output by build/classpath generation and prevent stale artifacts from winning.
- Separate build-loop latency from per-document pc latency.
- **Go/no-go:** downstream types/symbols update after upstream break/fix/rename without restarting the IDE.

### Phase 5 — Split a dedicated compiler-backend pass

- Move population scheduling, mapping, publication, and invalidation out of the inlay pass.
- Make hints, hover, completion, resolver bridges, and diagnostics consume the same immutable snapshot.
- Retire duplicate slot-filling/report-interception work only after parity is proven.
- **Go/no-go:** disabling visible type hints does not disable backend population; exactly one population runs per version.

### Phase 6 — Compatibility hardening and backend expansion

- Run the full consumer matrix, target larger real projects, and add narrow pc-backed member/conformance operations only
  for measured `ScType` incompatibilities.
- Publish a compatibility manifest for each supported bundled build.
- Decide whether upstreaming the dispatcher is mandatory before any non-research distribution.

### Measurements

Record p50, p95, and max, separated by file size and typed-node count, for:

- pc retypecheck;
- typed-tree traversal/deduplication;
- type rendering;
- PSI mapping;
- commit and cache invalidation;
- time from edit to current Scala 3 compiler snapshot;
- synchronous backend lookup overhead and cache hit rate;
- count of mapped, deduplicated, unavailable, unparsable, and synthetic-symbol nodes;
- daemon/resolve work triggered by publication.

Use at least these corpora:

1. ordinary small/medium Scala 3 files;
2. very large generated or declaration-dense files;
3. macro-heavy, inline-heavy, structural/refinement, quoted, derivation, capture/type-level, and library-backed files;
4. cross-module broken builds consuming `.betasty`;
5. mixed projects with active Scala 3, inactive Scala 3, older Scala 3, Scala 2, and Java modules.

Phase 1 establishes numeric latency and memory budgets from evidence rather than inventing them here. Structural
budgets are fixed now: no more than one pc compile/tree walk per file version, no pc work on the EDT, bounded snapshot
retention, and no false-gate scheduling/session work. Very-large and macro/type-level maxima must be reported, not hidden
in averages. Repeated multi-second EDT work, unbounded invalidation storms, or measurable inactive-module regressions
are no-go results.

## 11. Risks and open questions

1. **`ScType` identity and algorithms.** Many subsystems pattern-match concrete `ScType`, compare them, carry
   substitutors, or expect a designated PSI element. A parsed compiler rendering may replace type production but
   still destabilize bundled member lookup/conformance later.
2. **Backend replacement is asynchronous.** Selecting Scala 3 on the first arbitrary synchronous read would require eager typing
   of every file or blocking PSI. The design instead guarantees Scala 3 results for the latest published exact-version
   snapshot and measures the pending window.
3. **Transformer lifecycle.** Dynamic retransformation works on the target JBR 25, including when `ScTypeElement` is
   already loaded, but the runtime warns that dynamic agent loading may be disabled by default in a future release.
   Plugin unload also cannot safely restore arbitrary third-party transformations. Treat the shim as process-lifetime,
   fail closed on installation failure, and require the upstream dispatcher before broad distribution.
4. **Inactive fast path.** A globally woven hook necessarily executes a branch. Is “zero overhead” satisfied by a cached
   module predicate with zero allocations/work, or must mixed-project inactive modules execute literally unchanged
   bytecode? The latter rules out third-party global interception.
5. **Rendered-type fidelity.** Which dotc types cannot round-trip through `createTypeFromText`, and which lose ownership,
   match-type state, capture sets, refinements, annotations, or singleton precision?
6. **Role selection.** Which compiler phase/tree type corresponds to IntelliJ's exact vs widened expression,
   definition, inside-parameter, outside-parameter, and expected-type contracts?
7. **Synthetic PSI stability.** How should compiler symbols without source ranges participate in equality, navigation,
   indexing, find usages, rename, and smart pointers across generations?
8. **Cache blast radius.** Per-element `clearOnScalaElementChange` is correct but may repeatedly clear shared manager
   caches. Can a supported bulk API preserve local tracker correctness with less churn?
9. **CBH races.** Native compiler type reports, pc population, completion-file copies, and reparses may compete for the
   same copyable slot. Ownership/provenance must be explicit.
10. **Closed and batch-analyzed files.** An editor-bound pass covers open files. Inspections, search, and refactorings may
    load closed PSI; decide whether to prewarm on demand asynchronously, restrict strict backend selection to analyzed files, or
    integrate with a broader analysis lifecycle.
11. **Best-effort freshness.** Output directories can contain stale `.betasty`; classpath generation and upstream build
    provenance must prevent old symbols from becoming current.
12. **Memory.** Whole-file types, symbol DTOs, parsed `ScType` caches, and light PSI can be large. Snapshots need bounded
    retention and weak/retired PSI associations.
13. **Failure semantics.** Returning bundled fallback preserves IDE utility but weakens the literal “all” claim.
    Returning unavailable everywhere is purer but may break callers. The backend state and telemetry must make this a
    deliberate per-role decision.
14. **Version maintenance.** Private APIs and bytecode shapes can change within 261.x. Fingerprints, startup probes, and
    a fail-closed compatibility matrix are mandatory.

## 12. Relationship to prior ADRs

The former ADR set is archived under `docs/archive/adr/` and is wholly superseded. The notes below record which ideas
this design retains as implementation constraints; they do not make the archived ADRs normative.

- **ADR-0011 is superseded in framing.** Its selective “repair `Any`/widening for macro-heavy Scala 3” scope remains a
  useful shipped waypoint and regression suite, but it is no longer the architectural destination. The destination is
  Scala 3's compiler backend for every type read in active modules.
- **ADR-0008 remains foundational.** CBH plus compiler types is still part of `ModuleDetectionService.isActive`; it
  supplies compiled/best-effort artifacts and the safety boundary. Every new backend hook inherits that exact gate.
- **ADR-0010 remains evidence about diagnostics, not a limit on backend replacement.** Native-clean steady-state highlighting
  justifies leaving diagnostics alone; it does not establish that bundled PSI types are compiler-equivalent.
- **ADR-0007's version pin/reflection fallback becomes stricter.** The compiler-backend shim requires explicit method
  fingerprints, startup verification, and fail-closed behavior, not opportunistic reflection.
- **ADR-0009's pass infrastructure is reusable plumbing.** Long-term compiler-backend population becomes its own pass;
  diagnostics and inlays become consumers of the same immutable snapshot.

The central bet is therefore precise: **one exact-version pc retypecheck populates a whole-file semantic snapshot; a
gated, version-pinned compatibility layer makes that snapshot the first answer at every Scala PSI type root, while a
pc-symbol bridge covers declarations that cannot be represented by a parsed `ScType`.** The bet succeeds only if its
inactive fast path, cache invalidation, consumer compatibility, and measured latency are good enough to preserve the
bundled plugin's years of stabilization.
