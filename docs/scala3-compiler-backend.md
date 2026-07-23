# Metallurgy's definitive architecture: replacing IntelliJ's Scala type backend with Scala 3's compiler backend

**Status:** sole normative architecture and design document

**Baselines:** IntelliJ Platform 261.x, bundled Scala plugin 2026.1.20, Scala 3.7.4

**Decision:** retain Scala PSI as IntelliJ's object model and replace its bundled type-producing implementation with
the exact module's Scala 3 compiler. Metallurgy uses published IntelliJ, Scala-plugin, and Scalameta interfaces wherever
they reach the required semantic operation. Where they do not, one isolated compatibility bridge wraps the bundled
implementation or replaces that implementation outright. Structural access is preferred to raw reflection, and no
private implementation knowledge may escape the bridge.

This document supersedes every prior Metallurgy design, status, glossary, ADR, and research document. Those materials
are preserved under `docs/archive/` for provenance only and are non-normative. New architectural decisions amend this
document; they do not revive or add parallel ADRs.

Source coordinates describe the verified baseline at bundled Scala plugin tag `2026.1.20` and Scala tag `3.7.4`; they
are evidence, not compatibility gates. Paths beginning
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

This applies **only** when `ModuleDetectionService.isActive(module)` is true. Its target definition is:

```text
the module uses Scala 3  AND  the module is opted in  AND  Scala 3 CBH/compiler types are enabled
```

Compiler versions are artifact coordinates and diagnostic metadata, never compatibility tables. Every Scala 3 version
with a resolvable presentation-compiler artifact follows the same path. Base backend availability and optional
facilities such as BETASTY are independently capability-discovered; no minor-version floor infers either one.
An active module may briefly be `Pending` while its exact artifact is resolved, or `Unavailable` when no such artifact
is published. Neither state permits a compiler-derived PSI overwrite.
Every scheduler, tree extractor, side-table read, slot write, resolver contribution, synthetic declaration, cache
invalidation, and backend-provider entry must perform the same module check. A false gate means:

- no pc session creation, artifact fetch, retypecheck, PSI traversal, side-table allocation, slot mutation, cache flush,
  completion contribution, synthetic PSI, or background listener work;
- no semantic or UI behavior change in Scala 2, non-opted-in modules, Scala 3 modules without the required capability,
  and inactive modules in mixed projects;
- a process-wide hook, wrapper, or replacement must perform a constant-time gate before allocating bridge state or
  consulting a snapshot. Its inactive branch calls the untouched bundled implementation directly.

### Explicitly out of scope

- Replacing the Scala parser, PSI tree, stubs, indices, editor mechanics, project import, debugger, or build runner.
  IntelliJ's native sbt importer and IntelliJ's BSP importer remain the project-model owners; Metallurgy does not speak
  sbt or BSP. Worksheet and interactive REPL semantic integration is deferred to a later phase, while their execution
  remains owned by the bundled Scala plugin.
- Rebuilding steady-state compiler diagnostics. Compiler-based highlighting already owns that surface; Metallurgy's
  diagnostic pipeline stays transient plumbing unless a measured gap appears.
- Scala 2.
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

Populating `CompilerType` on a binding, definition, function, parameter, or `ScTypeElement` has no effect. Metallurgy's
Scala-plugin bridge must intercept or reimplement those roots; the slot alone cannot provide whole-backend replacement.

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

CBH remains a deliberate rollout failsafe and one available build/artifact producer; the replacement backend does not
technically require CBH to type a document. Its diagnostics are not a substitute for pc type population, and its type
reports must not be allowed to overwrite a newer pc generation in active modules. Once isolation and build-loop
ownership are proven independently, removing the CBH interlock is a separate product decision.

### 2.6 Cache topology

Type and resolve caches predominantly use `BlockModificationTracker(element)`, while broader PSI structures use
`ModTracker.anyScalaPsiChange`. `ScalaPsiManager.clearOnScalaElementChange` clears manager caches and walks outward until
it can increment the relevant stable-expression local counter (or the top-level tracker)
(`.../psi/impl/ScalaPsiManager.scala:610-631`). `ScalaPsiManager` also caches compound/intersection signature and type
maps (`ScalaPsiManager.scala:55-147`); it is a cache owner for `ScType` operations, not a general type evaluator.

This distinction is load-bearing: incrementing only `anyScalaPsiChange` does not invalidate an expression type cached
against its local `BlockModificationTracker`. Every changed backend value needs the per-element clear, followed by one
coalesced global increment for downstream usages and presentations.

### 2.7 Project loading and pipeline activation

Project loading is deliberately upstream of the compiler backend. The user's selected IntelliJ loader—native sbt
import or IntelliJ BSP import—creates and updates the workspace modules, source/test roots, dependency classpaths,
Scala SDK/compiler version, output paths, and compiler options. Metallurgy reads that normalized IntelliJ/Scala-plugin
model only after import has produced it. It neither launches sbt for semantic queries nor owns a BSP connection.

This matches the bundled plugin's own split. Its compiler-highlighting service chooses BSP or JPS compilation from the
project model and constructs a `BspProjectTaskRunner` only on the BSP branch
(`intellij-scala/scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/CompilerHighlightingService.scala:205-220,234-262`).
After external builds, common Scala-plugin code refreshes source and output roots for both sbt shell and BSP
(`intellij-scala/scala/scala-impl/src/org/jetbrains/plugins/scala/util/ExternalSystemVfsUtil.scala:20-53`). Metallurgy
must consume those refreshed roots identically regardless of which importer produced them.

```text
sbt import or IntelliJ BSP import
  -> IntelliJ workspace/module model becomes current
  -> module/root/compiler-settings listeners invalidate the old backend generation
  -> exact compiler artifact and module classpath are resolved from the imported model
  -> the normal per-document compiler-backend pipeline starts
```

An incomplete or reloading project model is a `Pending` input, not permission to reuse a stale classpath. Project
reload, module replacement, SDK/compiler change, roots change, and imported scalac-option change each retire affected
sessions and schedule fresh snapshots for open active files. None of those listeners may depend on sbt-only module
types: BSP, sbt, Maven, Gradle, and manually configured Scala modules must converge on the same module descriptor.

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
| Scala UAST (experimental) | Registered behind experimental feature `scala.uast.enabled` (`intellij-scala/scala/uast/resources/scalaCommunity.uast.xml:12-18`). Its adapters commonly call Scala PSI ``type()`` and convert `ScType` to `PsiType`; expression, method-call receiver/return, method, variable, parameter, and resolve adapters also have specialized paths (`.../uast/baseAdapters/ScUExpression.scala:27-28`; `.../uast/expressions/ScUMethodCallExpression.scala:57-83`; `.../uast/declarations/ScUMethod.scala:58-62`). | **Mixed.** Generic expression UAST reads inherit backend types, while specialized declaration, resolve, light-variable, and conversion paths can bypass or lose them. | Test feature disabled/enabled. Audit `getExpressionType`, `getReturnType`, `getReceiverType`, variable/parameter `PsiType`, resolve, evaluation, conversion, caching, and UAST inspections; add bridge work only for demonstrated gaps. |
| Inline/type hints | Bundled `ScalaTypeHintsPass` calls value/variable ``type()`` and function `returnType` (`intellij-scala/scala/codeInsight/src/org/jetbrains/plugins/scala/codeInsight/hints/ScalaTypeHintsPass.scala:127-147`). `ImplicitHintsPass` hosts the pass and also runs implicit-resolution hints (`.../codeInsight/implicits/ImplicitHintsPass.scala:42-80,108-135`). | **Untyped values only**, through their initializer; not explicit definitions/functions. | Keep Metallurgy's pc hint renderer initially; after full dispatcher coverage, decide whether it is redundant. |
| Structure view | Primarily stub/source text: function return type text, val/var declared type text, and parameter type text (`intellij-scala/scala/structure-view/src/org/jetbrains/plugins/scala/structureView/element/Function.scala:20-26`; `ValOrVar.scala:20-24`; `element/package.scala:17-28`). Anonymous-class location resolves a type element (`ScalaAnonymousClassTreeElement.scala:36-42`). | **Generally no.** | This is presentation of source syntax, not inferred semantics. Leave it alone except compiler-synthetic declarations and the anonymous-class resolver. |
| Find usages | Searchers use indices/`ReferencesSearch` and validate with `PsiReference.resolve`/`isReferenceTo`; e.g. `ObjectTraitReferenceSearcher` (`.../findUsages/typeDef/ObjectTraitReferenceSearcher.scala:12-32`) and operator search (`.../findUsages/OperatorAndBacktickedSearcher.scala:25-100`). | **Only indirectly** when resolve depends on a qualifier type. | Stable pc-symbol-to-PSI identity is required for compiler-only members; type strings alone are insufficient. |
| Refactorings | Mixed: rename/move/find-super use PSI references and search; extract/introduce/change-signature/inline also call ``type()``, `returnType`, expected types, conformance, and Java `PsiType` bridges. `TypeAnnotationRenderer` itself lists override/implement and member-info consumers (`.../types/api/presentation/TypeAnnotationRenderer.scala:43-50`). | **Mixed.** | Treat refactorings as the highest-risk compatibility suite: type dispatcher plus stable symbol identity, with per-refactoring fallbacks when pc-to-PSI mapping is unavailable. |

The practical conclusion is that whole-file expression population has high impact, but it is not the definition of
“all.” Typed definitions and symbol identity are separate architectural workstreams.

The default expectation for every other Scala-plugin feature is **semantic inheritance**, not a bespoke Metallurgy
implementation: if the feature obtains types and resolve targets through the corrected central PSI seams, it should
work unchanged. The [source-derived feature inventory](research/scala-plugin-feature-inventory.md) exists to prove that
route and preserve it with regression tests; its generated catalog makes newly shipped registrations detectable. A
feature receives its own adapter only when source tracing or a failing parity test demonstrates a bypass such as
stub/index-only identity, specialized UAST conversion, an execution model, or a separate compiler service.

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

## 5. Extension-first compatibility architecture

The 2026.1.20 baseline has no general type-backend extension point. That does not make an upstream change a prerequisite.
Metallurgy owns the adaptation from Scalameta PC results to IntelliJ's Scala PSI model. It exhausts supported IntelliJ
and Scala-plugin extension points first, then contains unavoidable implementation access in two deep bridge modules.
No Scala 3 compiler or Scalameta modification is required for the product to function.

### 5.1 Selection order

For every semantic root or consumer, use the first mechanism that can completely implement its contract:

1. a published IntelliJ Platform extension point or interface;
2. a published `org.intellij.scala` extension point, topic, service, or PSI interface;
3. a wrapper around the bundled implementation that checks Metallurgy's cache first and invokes the original on the
   inactive, pending, unavailable, or failed path;
4. a Metallurgy-owned reimplementation when wrapping cannot preserve the required ordering or cache semantics;
5. narrowly scoped structural or reflective access inside the bridge when construction, registration, or delegation is
   otherwise impossible.

The choice is made per semantic root, not once for the entire plugin. A clean completion EP does not justify rewriting
completion, and an inadequate definition-type seam does not prevent using the clean interpolator or stable-reference EPs.
Each fallback must have an executable compatibility probe and an inactive-path equivalence test. Raw reflection is the
last resort; structural typing, public supertypes, method handles resolved by shape, or copied implementation behind a
small interface are preferred. Class names, bytecode fingerprints, exact method descriptors, Scala-plugin build numbers,
and version allowlists are not capability discovery.

### 5.2 Scala-plugin bridge

`ScalaPluginSemanticBridge` is the only module permitted to know how the bundled plugin implements semantic roots. Its
external interface is cache-only and role-based:

```text
lookup(element, role, generation) -> Current | Pending | Unavailable | Failed
```

Callers do not know whether an answer was reached through an EP, a wrapped method, or a replacement implementation.
The bridge covers expression exact/widened types, type elements, declared/inferred definitions, function/given results,
parameter inside/outside types, pattern and expected types, stable/expression reference resolution, synthetic symbols,
and the cache invalidation needed by each root.

At startup the bridge discovers supported mechanisms by behavior and shape: registered EP presence, assignability to a
public interface, callable-method shape, original-delegate availability, and a harmless probe against a disposable PSI
fixture. It installs only mechanisms whose full contract is proven. Unknown Scala-plugin builds remain load-safe: a
failed probe disables that adapter and records a structured reason; it never partially patches a root. Instrumentation
may be process-wide, but its first branch derives the module and checks `ModuleDetectionService.isActive(module)`.
The false branch delegates directly, adds no cache entry, and schedules no work.

Replacing `ScalaPsiManager` alone is insufficient. It owns caches and invalidation but not most type production:
`ScExpression`, `ScTypeElement`, definition/function/parameter implementations, patterns, and reference implementations
own separate roots (sections 2.2–2.4). Metallurgy may wrap or replace `ScalaPsiManager` for cache correctness, but it
must also cover those independent roots. If wholesale copied implementations are used, they live under the bridge,
track the public behavioral contract rather than one build's private layout, and are selected by capability probes.

### 5.3 Compiler bridge through Scalameta PC

`Scala3PcBridge` compiles against Scalameta's published Java `mtags-interfaces` interface and resolves
`org.scala-lang:scala3-presentation-compiler_3:<exact module Scala version>` using public dependency-resolution
infrastructure. It first uses `PresentationCompiler` operations such as completion, hover, definition, SemanticDB,
inlay hints, lifecycle, and configuration
(`metals/mtags-interfaces/src/main/java/scala/meta/pc/PresentationCompiler.java:39-117,194-364`). The exact compiler
implementation remains isolated in a per-artifact child classloader. Dotc trees, types, symbols, Scala collections,
IntelliJ PSI, and `ScType` never cross the classloader seam.

The published interface does not expose a complete whole-file span/type/symbol snapshot. `hover` is presentation data,
not a safe structured type transport, and per-position calls would repeat compiler work. After exhausting the public PC
operations, the compiler bridge may structurally access the already-loaded Scala 3 PC implementation's retained
`InteractiveDriver` and typed compilation unit to produce the missing snapshot. This access is read-only, isolated,
capability-probed, and returns only classloader-neutral DTOs. It must not modify the compiler artifact or require an
upstream Scalameta/Scala 3 change.

Provider construction follows the same rule. Prefer published service metadata or a public factory when present. If an
artifact has neither, discover a public no-argument `PresentationCompiler` implementation within the isolated artifact
and verify its public interface before use. Scala 3.7.4, for example, exposes the public no-argument
`dotty.tools.pc.ScalaPresentationCompiler` constructor but publishes no
`META-INF/services/scala.meta.pc.PresentationCompiler` entry
(`scala3/presentation-compiler/src/main/dotty/tools/pc/ScalaPresentationCompiler.scala:61-73`). Shape-based discovery
finds that public subtype without embedding its concrete name; future service providers and experimental public
operations win automatically.

Capabilities are observed operations, not version claims. Base PC creation, whole-file snapshot extraction, BETASTY
consumption, completion, hover, and future experimental facilities are advertised independently after their probes
succeed. Stable, RC/EAP, nightly, and vendor artifacts use the same discovery path. Failure returns a typed unavailable
reason and cannot select a nearby compiler or a hardcoded manifest entry.

The capability report retains both typed states for the operations Metallurgy currently consumes and the complete set
of public operations exposed by the discovered `PresentationCompiler` implementation. A newly published Scalameta
operation is therefore visible to future adapters immediately; supporting it requires an IntelliJ-side adapter, not a
new compiler-version entry. The discovered prototype is session-owned and reused for first configuration, so capability
probing does not rescan or instantiate the exact compiler a second time.

`PcSessionManager` records the active backend lifecycle as `Undiscovered`, `Pending(exactVersion)`,
`Available(exactVersion, capabilities)`, or `Unavailable(reason)`. Artifact preparation and session construction are
different unavailable reasons and complete asynchronous callers with no session rather than leaking an exception into
an IntelliJ feature callback. Inactive modules allocate no lifecycle entry; an explicit status query derives `Inactive`
from the module gate. The fallback therefore remains observable without adding work or state to the false-gate path.

The supporting source studies are archived as non-normative evidence under `docs/archive/research/`.

### 5.4 Wrap and reimplementation map

These are the known roots that no clean baseline EP fully replaces. The bridge inventory is live: a future public EP
that satisfies a row automatically moves it to the clean path after its capability test passes.

| Semantic root | Baseline target | Bridge strategy | Principal risk and required proof |
|---|---|---|---|
| Expression initial/exact type | `ScExpression.getTypeWithoutImplicits` (`.../psi/api/expr/ScExpression.scala:301-328`) | Keep `CompilerType` as the first compatibility adapter; wrap the getter only to enforce side-table freshness and role selection that the string slot cannot encode. | `BlockModificationTracker` can retain a stale parse. Test edit, reparse, copied user data, pending generation, and inactive direct delegation. |
| Expression widened/adapted type and expected types | `ScExpression.getTypeAfterImplicitConversion`, `expectedType`, `getNonValueType` (`ScExpression.scala:146-190,226-299`) | Wrap the public PSI trait methods if interception preserves dispatch; otherwise reimplement their small dispatcher layer and delegate non-current cases to the original implementation. | Many annotators and applicability checks assume bundled adaptation. Test exact versus widened roles and never block inside a read action. |
| Type syntax | `ScTypeElement.type()` (`.../psi/api/base/types/ScTypeElement.scala:15-41`) | Wrap cached entry and return a role-specific parsed compiler type when current. | Declared source syntax and compiler normalization differ; preserve separate declared/inferred records. |
| Values and variables | `ScPatternDefinitionImpl.type()`, `ScVariableDefinitionImpl.type()` (`.../psi/impl/statements/ScPatternDefinitionImpl.scala:37-56`; `ScVariableDefinitionImpl.scala:31-43`) | Reuse the wrapped type-element/initializer routes where complete; wrap the definition method for individual binding symbols and destructuring. | A tuple/RHS type is not each binding's type. Require compiler symbol mapping per binding. |
| Functions and givens | `ScFunctionImpl.definedReturnType` and function ``type()`` (`.../psi/impl/statements/ScFunctionImpl.scala:155-192`) | Wrap the return-type root and delegate cache construction to the original when no current compiler record exists. | Recursive definitions, overridden members, and explicit `Unit` carry special contracts. Test all separately. |
| Parameters | `ScParameter.outsideParamType`, `insideParamType`, and ``type()`` (`.../psi/api/statements/params/ScParameter.scala:58-84`) | Wrap both role-specific entries; reimplement only the adjustment dispatcher if the original cannot accept compiler input. | Repeated, context, by-name, and `into` parameters have intentionally different inside/outside types. |
| Patterns | `ScPattern.expectedType` plus concrete pattern ``type()`` methods (`.../psi/api/base/patterns/ScPattern.scala:20-39,46-115`) | Wrap the shared expected-type root, then add adapters only for concrete pattern classes whose ``type()`` bypasses it. | Large implementor set and overlapping spans. Discover by public trait/behavior, not a frozen class list; leave unmapped bindings unavailable. |
| Expression references | `ScReferenceExpressionImpl.multiResolveScala` (`.../psi/impl/expr/ScReferenceExpressionImpl.scala:59-79`) | Use reference/contributor EPs where the Platform permits; otherwise wrap resolution to prepend current compiler-symbol results and invoke the original for non-current states. | Resolve identity drives search/refactoring. Light PSI must be stable for a generation and inactive results must be byte-for-byte bundled. |
| Stable/type references | `ScStableCodeReferenceImpl` and `referenceExtraResolver` (`.../psi/impl/base/ScStableCodeReferenceImpl.scala:470-493`; `.../psi/impl/base/ScStableCodeReferenceExtraResolver.scala:11-38`) | Prefer `referenceExtraResolver`; wrap only qualifier/compiler-slot freshness that the EP cannot affect. | The EP is stable-reference-only; do not generalize it to expressions. |
| Cache owner | `ScalaPsiManager.clearOnScalaElementChange` and project service registration (`.../psi/impl/ScalaPsiManager.scala:610-631`; `.../resources/META-INF/scala-plugin-common.xml:605`) | Call supported invalidation methods first. Wrap or replace the project service only if bulk invalidation/generation ownership cannot be implemented externally. | A wholesale manager copy does not replace type roots and may miss upstream cache additions. Prove service construction, original delegation, and every local tracker clear. |
| CBH compiler-type publication | `ExternalHighlightersService` application (`.../highlighting/ExternalHighlightersService.scala:76-145`) | Subscribe to compiler events and reject stale/foreign writes where possible; wrap publication only if event ordering cannot protect Metallurgy-owned slots. | Two asynchronous producers can overwrite each other. Record slot provenance and generation. |
| Compiler-visible synthetic declarations | `syntheticMemberInjector`, `scalaSyntheticClassProducer`, stable-reference and platform navigation EPs | Compose clean EP adapters over `PcSymbolBridge`; create minimal light PSI only when an ordinary PSI target is absent. | Synthetic identities can leak into global indices/search. Gate at callback entry and filter scopes/results. |

Instrumentation, service replacement, and copied implementations are packaging techniques behind
`ScalaPluginSemanticBridge`, not interfaces exposed to the rest of Metallurgy. The deletion test for the bridge is
intentional: removing it should force knowledge of Scala-plugin call topology back into every consumer. Keeping that
knowledge local is what makes the module earn its complexity.

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

Neither flag is a requirement for the base compiler backend. `scala.meta.pc.PresentationCompiler` does not publish an
API for enumerating compiler settings, so `Scala3PcBridge` performs the narrowest remaining bridge-local shape probe
against the exact compiler distribution: the producer capability requires `YSettings.YbestEffort`, and the consumer
capability requires `YSettings.YwithBestEffortTasty`. The results are independent structured capability states. Before
discovery, Metallurgy removes both managed flags; after discovery, it adds only supported flags and passes only the
consumer flag to the PC session. Older, nightly, and vendor Scala 3 compilers therefore use the same base-PC path
without a version guess, while BETASTY activates wherever the exact compiler exposes it. A missing BETASTY capability
disables cross-module best-effort recovery only; it cannot disable ordinary PC typing.

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
| Declared vs inferred type | PSI prefers source type syntax; pc may expose an alias-reduced, singleton, widened, or inferred view. | Store separate roles (`Declared`, `Inferred`, `InsideParameter`, `OutsideParameter`, `ExpressionExact`, `ExpressionWidened`) and make each dispatcher request the correct role. |
| Compiler-only/synthetic declarations | Derived, inline-expanded, or best-effort-loaded symbols may have no source PSI. | Create minimal stable light PSI keyed by compiler symbol id and generation; navigation points to the nearest source owner or TASTy/decompiled owner. Completion/hover can remain pc-native if no safe PSI exists. |
| Rendered syntax not parsable as `ScType` | Dotc display text may contain compiler names, captures, anonymous refinements, or forms the bundled parser cannot reconstruct. | Prefer a source-compatible renderer; validate with `createTypeFromText`; on failure retain a display string and pc type handle for pc-native surfaces, and mark `ScType` unavailable. Never substitute `Any`. |
| `ScType` semantic mismatch | Parsed types subsequently use bundled conformance/member algorithms. | Measure first. Add narrow public pc-backed conformance/member operations only where mismatches are observed; do not replace the entire `ScType` algebra speculatively. |
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
2. **`ScalaProjectModelAdapter`** — derives a loader-neutral descriptor from the imported IntelliJ model: compiler
   coordinate, production/test source roots, dependency/output classpath, compiler options, JDK, and model epoch. It
   observes project/root/settings changes but never calls sbt or BSP.
3. **`Scala3PcBridge`** — configures the exact compiler through published Scalameta PC interfaces, performs one
   retypecheck, and builds the missing bulk snapshot through capability-probed structural access when no public PC
   operation supplies it. It returns only neutral spans, roles, rendered types, stable symbols, and synthetic
   declarations; dotc objects never leave its isolated classloader.
4. **`PcPsiMapper`** — under a cancellable non-blocking read action, maps DTO spans/symbols to current PSI elements and
   semantic roles. It creates smart pointers only for the short commit window; persistent identity is URI/version/range/
   role/symbol id, not a raw PSI object.
5. **`Scala3CompilerBackend`** — atomically publishes immutable mapped snapshots and answers synchronous cache-only
   lookups. Its first instruction is the active-module gate. It exposes `Current`, `Pending`, `Unavailable`, and `Failed`,
   not `Option[String]` alone.
6. **`Scala3CompilerPublisher`** — commits expression/reference compatibility slots, computes the changed/removed
   element set, performs per-element Scala cache clears, increments the global tracker once, and restarts only the
   necessary daemon/hint/completion surfaces.
7. **`ScalaPluginSemanticBridge`** — routes each semantic root through the clean EPs in section 4 where possible and
   through a capability-probed wrapper or replacement elsewhere. It asks the store for a role-specific result and
   parses/caches source-compatible text as `ScType`. The inactive path invokes the untouched bundled implementation.
8. **`PcSymbolBridge`** — maps compiler symbol ids to source PSI or stable light PSI and supplies completion, stable
   reference, arbitrary reference fallback, navigation, and search identity.

### 8.2 Population pass shape

The pass is deliberately bulk and version-guarded:

```text
capture file URI + text + document version + module/classpath/options generation
  -> gate ModuleDetectionService.isActive(module)
  -> resolve the exact-version presentation-compiler distribution
  -> discover and configure a scala.meta.pc PresentationCompiler through its published interface
  -> retypecheck once off EDT and extract one immutable boundary-safe snapshot
     (public operation when available, structural bridge fallback otherwise)
  -> validate capability/schema/status and deduplicate by span + role + symbol
  -> non-blocking read action maps DTOs to PSI for the captured version
  -> commit only if URI, document version, module gate, session generation,
     classpath generation, and compiler-options generation are unchanged
  -> atomically publish side table
  -> write/clear compatibility CompilerType slots
  -> clearOnScalaElementChange(changedElement) for every changed/removed element
  -> increment anyScalaPsiChange once and restart affected presentations
```

The proof of concept establishes the required record shape and publication lifecycle. Its reflection is contained in
`StructuralScala3PcBridge`, converted to structural/capability probes where possible, and kept out of consumers.
`PcSession`'s debounce, generation supersession, single-writer serialization, and winning-snapshot publication remain
reusable host-side behavior
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
`BlockModificationTracker` (`PcTypeHintsPass.scala:69`; `ScalaPluginSemanticBridge.scala:242-269`). The first milestone should
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

### Phase 0 — Capability model and bridge seams

- Define compiler-backend roles/states/generation keys and the exact inactive fast-path contract.
- Add timings and counters around current retypecheck, `typeAt`, PSI traversal, cache invalidation, and daemon restart.
- Introduce the small `Scala3PcBridge` and `ScalaPluginSemanticBridge` interfaces; move all compiler/Scala-plugin
  implementation access behind them.
- Introduce `ScalaProjectModelAdapter`; prove that representative native-sbt and IntelliJ-BSP imports produce equivalent
  compiler-backend descriptors without exposing loader-specific types.
- Replace private dependency resolution with public Coursier and add stable/RC/nightly artifact-discovery fixtures
  without version allowlists. Discover service providers, public factories, and structural fallbacks in that order.
- Inventory each semantic root against section 4's EPs and record why wrapping or replacement is needed where no EP fits.
- **Go/no-go:** no bytecode fingerprints or version compatibility table; private access exists only inside a bridge,
  every adapter has a capability probe, and absent capabilities/inactive modules preserve bundled behavior.

### Phase 1 — Pc everywhere in the existing pass, behind measurement

- Consume one bulk semantic snapshot from `Scala3PcBridge` and replace per-binding `typeAt` queries.
- Map and publish all safely matched expressions while still scheduling from `PcTypeHintsPass`.
- Perform version-guarded commit and correct per-element invalidation.
- Keep visible hints behavior unchanged so the experiment has a narrow UI footprint.
- **Go/no-go:** exact-type tests stay exact; no stale type after edit/reparse; one retypecheck and one tree walk per version.

### Phase 2 — Typed definitions and role-specific backend selection

- Activate `ScalaPluginSemanticBridge` coverage for type elements, vals/vars and individual bindings,
  functions/givens, parameters, and concrete patterns, using EPs first and wrapper/replacement adapters elsewhere.
- Preserve distinct declared/inferred/inside/outside/exact/widened roles.
- Add consumer tests for hover, bundled hints, annotator, representative inspections, Java `PsiType` conversion, and
  refactoring previews.
- **Go/no-go:** after publication, no tested semantic root returns a conflicting bundled type; fallbacks are counted and
  explainable.

### Phase 3 — Resolver and synthetic-symbol bridge

- Map pc symbols to existing PSI where possible.
- Add stable light PSI for unmapped compiler-visible declarations, then wire stable references, general reference
  fallback, completion, navigation, find usages, and selected refactorings.
- Use clean resolve/synthetic EPs first. Where they cannot cover expression references or symbol identity, install a
  bridge-local resolver wrapper or replacement with an exact inactive fall-through.
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
- Generate a complete surface inventory from shipped Scala-plugin modules, plugin XML registrations, services,
  listeners, actions, languages, EPs, and integration modules. Classify every feature as a direct/indirect semantic
  consumer, model/artifact producer, execution-only, syntactic/unaffected, or deferred. In particular, cover test
  framework discovery/runners, parameter info, code generation, hierarchies, debugger evaluation, Java PSI/UAST,
  structural search, language injection, compiler indices, TASTy/decompiler, scratches/consoles, and build-tool/native
  platform variants.
- Audit Scala UAST with `scala.uast.enabled` off and on, and inventory every registry key/experimental feature in the
  target Platform and Scala-plugin builds that can alter project loading, compiler highlighting, type/resolve,
  completion, UAST, debugger, worksheet/REPL, or process/classloader behavior. Test relevant non-default states.
- Test stable/RC/nightly compiler artifacts and stable/EAP/nightly Scala-plugin builds through capabilities rather than
  versions. Unsupported mechanisms must disable independently without preventing the plugin from loading.
- Remove the proof-of-concept manifest/fingerprint selection, private bundled dependency resolver, and reflection outside
  the two bridges. Retain only the smallest proven structural/reflective adapters required after public interfaces and
  extension points are exhausted.

### Phase 7 — Ecosystem and project-loader parity

- Maintain a reproducible corpus of pinned, compiler-clean open-source Scala 3 projects. It must include conventional
  application code and non-trivial macro/type-level libraries; initial candidates are ZIO, Cats, Shapeless, Cats Effect,
  FS2, and Tapir where their pinned revisions support Scala 3.
- Import representative projects through both native sbt and IntelliJ BSP. Assert equivalent backend module descriptors,
  source/test/generated roots, compiler coordinates/options, classpaths, cross-module symbols, and session invalidation
  after reload.
- Run steady-state highlighting over every source file and require zero Metallurgy-introduced errors for revisions that
  compile cleanly with the same exact compiler/options. Keep an allowlist only for verified project/upstream baseline
  diagnostics, with source, reason, and expiry; never use it to hide backend failures.
- Sample exact types, resolve targets, completion sets, hover signatures, navigation, find usages, and safe refactoring
  previews from every project and compare them to the exact compiler/PC result, not merely to the bundled plugin.
- Exercise clean build, broken upstream module with BETASTY, edit/fix, project reload, branch/classpath change, and cold
  cache. Record crashes, timeouts, unmapped roles/symbols, fallbacks, and false positive/negative diagnostics.
- **Go/no-go:** all pinned compiler-clean projects have zero false errors and zero crashes; sampled facts match the exact
  compiler; sbt/BSP descriptors and results are semantically equivalent; performance stays within Phase 1 budgets.

### Phase 8 — Worksheet and interactive REPL integration (deferred)

- Keep worksheet and REPL execution, process lifecycle, result transport, and BSP-specific execution owned by the
  bundled Scala plugin.
- Route Scala PSI type/resolve/presentation reads inside worksheet and REPL editors through the same compiler backend
  when their virtual files and module context can be versioned safely.
- Add worksheet plain/REPL modes and BSP/non-BSP execution fixtures; execution results must remain unchanged when the
  backend is inactive.
- **Go/no-go:** semantic presentation uses current compiler snapshots without changing execution behavior or coupling
  the core backend to a worksheet/REPL protocol.

### Phase 9 — Graduation

- Run the entire Metallurgy suite, the ecosystem/project-loader corpus, and all Scala 3-focused tests from the target
  `intellij-scala` project under hard timeouts. Building `intellij-scala` is reserved for this final step.
- **Go/no-go:** no unexplained regressions, false errors, leaked inactive-module work, or compatibility-probe failures;
  every remaining fallback and private bridge operation is measured and documented.

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
5. mixed projects with capable Scala 3, inactive Scala 3, unavailable-capability Scala 3, Scala 2, and Java modules;
6. the pinned open-source ecosystem corpus, imported through native sbt and IntelliJ BSP where supported.

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
3. **Bridge drift.** Stable/EAP/nightly Scala-plugin builds may change private construction or call topology. Behavioral
   probes must disable only the affected adapter, and wrapper/reimplementation tests must prove exact inactive fallback.
4. **PC interface evolution.** Public Scalameta operations may be added or changed independently of the structural
   snapshot fallback. Discovery must prefer newly published operations automatically; unknown experimental operations
   remain unused until Metallurgy has a compatible neutral DTO handler.
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
14. **Artifact availability.** Stable, RC/EAP, nightly, and vendor coordinates may live in different repositories or may
    omit a PC artifact. Resolution failure is an explicit unavailable capability, never a reason to guess a nearby
    compiler version.
15. **Replacement maintenance.** Copied Scala-plugin implementations can diverge from upstream bug fixes. Prefer
    wrapping; when reimplementation is unavoidable, minimize the copied surface, test against the public behavioral
    contract, and run the complete Scala 3-focused upstream suite as the epic's final graduation check.
16. **Project-loader divergence.** Native sbt and IntelliJ BSP imports can represent source sets, generated sources,
    output roots, or compiler options differently. Backend code that reads loader-specific services can silently miss
    dependencies or fail to invalidate. The adapter contract must use only normalized IntelliJ module/root/SDK terms,
    with import/reload parity tests and a descriptor diff when the loaders disagree.
17. **Hidden feature gates.** Platform or Scala-plugin registry/experimental flags can activate alternate UAST,
    highlighting, type-intrinsic, document-compilation, project-loader, debugger, or worksheet paths after the normal
    suite passes. Compatibility discovery must inventory gates for every target build, classify their reachability, and
    test every gate that changes a semantic root, model input, cache, scheduler, or classloader boundary. Unknown enabled
    gates produce structured diagnostics; they must not silently select a version-specific adapter.
18. **Unknown consumer surfaces.** A backend can pass type fixtures while breaking a feature that consumes resolve,
    Java `PsiType`, compiler indices, synthetic PSI, or project metadata indirectly—for example a test-framework finder,
    parameter-info handler, hierarchy provider, debugger evaluator, or code generator. Graduation requires a
    source-derived feature inventory with an affected/unaffected rationale and regression ownership for every shipped
    Scala-plugin module/registration; absence from an ad hoc checklist is not evidence of safety.

## 12. Relationship to prior ADRs

The former ADR set is archived under `docs/archive/adr/` and is wholly superseded. The notes below record which ideas
this design retains as implementation constraints; they do not make the archived ADRs normative.

- **ADR-0011 is superseded in framing.** Its selective “repair `Any`/widening for macro-heavy Scala 3” scope remains a
  useful shipped waypoint and regression suite, but it is no longer the architectural destination. The destination is
  Scala 3's compiler backend for every type read in active modules.
- **ADR-0008 remains a rollout constraint.** CBH plus compiler types stays in `ModuleDetectionService.isActive` as a
  failsafe and can supply compiled/best-effort artifacts, but PC type replacement does not technically depend on it.
  Every new backend hook inherits the current gate until a separate decision removes the interlock.
- **ADR-0010 remains evidence about diagnostics, not a limit on backend replacement.** Native-clean steady-state highlighting
  justifies leaving diagnostics alone; it does not establish that bundled PSI types are compiler-equivalent.
- **ADR-0007's version pin/fingerprint strategy is superseded.** Permanent support uses published Scalameta interfaces,
  capability discovery, and isolated IntelliJ-side bridges. Structural or reflective access is permitted only inside
  those bridges after supported interfaces and extension points are exhausted.
- **ADR-0009's pass infrastructure is reusable plumbing.** Long-term compiler-backend population becomes its own pass;
  diagnostics and inlays become consumers of the same immutable snapshot.

The central bet is therefore precise: **one exact-version Scala 3 PC pass populates a whole-file semantic snapshot;
Metallurgy adapts that snapshot into Scala PSI through supported EPs first and isolated wrappers or replacements where
the bundled plugin exposes no sufficient seam; a pc-symbol bridge covers declarations that cannot be represented by a
parsed `ScType`.** All translation and compatibility work is on the IntelliJ side. Compiler and plugin versions never
select implementation hacks. The bet succeeds only if capability discovery, inactive isolation, cache invalidation,
consumer compatibility, and measured latency preserve the bundled plugin's years of stabilization.
