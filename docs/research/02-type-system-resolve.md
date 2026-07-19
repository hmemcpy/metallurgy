# 02 — Type System / Resolve / Type Inference / Implicits

Domain report for the Metals-PC redesign of Scala 3 support. This document covers the
hand-written engine the plugin uses today for **types, resolution, inference and
implicits**, identifies the correctness/perf cliffs, and proposes a seam against the
Metals presentation compiler (`pc` → `dotc`) so we can retire the in-tree type system
for Scala 3 files.

All `path:line` references are relative to the repo root.

---

## 1. Type representation

### 1.1 Core trait

The root of the type algebra is `trait ScType` in
`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/types/ScType.scala:13`. It
is `ProjectContextOwner` (so every type carries its `ProjectContext`) and exposes:

- `typeSystem: api.TypeSystem` — `ScType.scala:15`
- `aliasType` / `isAliasType` (cached) — `ScType.scala:19-28`
- `unpackedType` — `ScType.scala:32`
- `isValue`, `isFinalType`, `inferValueType` — `ScType.scala:48-52`
- `equivInner(...): ConstraintsResult` — `ScType.scala:60` (the structural equality hook)
- `visitType(visitor: ScalaTypeVisitor)` — `ScType.scala:64`
- `presentableText`, `canonicalText`, `typeText` — `ScType.scala:68-77`

`NamedType` (`ScType.scala:84`) just adds a `name: String` and a fast rendering path.

### 1.2 Concrete type shapes

All live alongside `ScType.scala` and under
`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/types/`:

| Class | File | Models |
|---|---|---|
| `ScCompoundType` | `ScCompoundType.scala:16` | `{ A; B; def f: Int }` — structural refinements |
| `ScExistentialType`, `ScExistentialArgument` | `ScExistentialType.scala`, `ScExistentialArgument.scala` | `F[_]`, `x.Stable` |
| `ScMatchType` | `ScMatchType.scala:27` | Scala 3 match types (with custom reducer `ScMatchType.scala:36`, depth-limited at 50 — `ScMatchType.scala:100`) |
| `ScAndType` / `ScOrType` | `ScAndType.scala`, `ScOrType.scala` | intersection / union types |
| `ScLiteralType` | `ScLiteralType.scala` | singleton literal types (incl. enum value hack — `ScLiteralType.scala:62`) |
| `ScParameterizedType` | `ScParameterizedType.scala` | `F[A]` |
| `ScAbstractType` | `ScAbstractType.scala` | unresolved type-parameter slots during inference |
| `ValueClassType` | `ValueClassType.scala` | value-class erased types |

Non-value types (`api/nonvalue/` and `nonvalue/`):
- `ScMethodType` (`nonvalue/ScMethodType.scala:64` — contains the famous
  SCL-16431 / SCL-15354 hack)
- `ScTypePolymorphicType` (`nonvalue/ScTypePolymorphicType.scala`) — wraps a type with
  `Seq[TypeParameter]`
- `Parameter` (`nonvalue/Parameter.scala`) — formal-parameter shape
- `NonValueType` (`api/nonvalue/NonValueType.scala`) — marker for method/poly types

Designator family (`api/designator/`):
- `ScDesignatorType` — points at a `PsiNamedElement`
- `ScProjectionType` (`ScProjectionType.scala:74` — SCL-15345 recursion guard)
- `ScThisType`

Intrinsics (`intrinsics/`): `TupleIntrinsics`, `NamedTupleIntrinsics`,
`TypeIntrinsics`, `CompileTimeOpsShims` — hand-coded special handling for tuple
arithmetic, `Size[T]`, `NamedTuple`, etc. These duplicate logic that lives in
`dotc`/`scala3-library` and are inherently fragile.

### 1.3 The operations vocabulary

The rich API is on the `ScTypeExt` implicit class in
`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/types/package.scala:42`. It
exposes, among others:

`equiv`, `conforms`, `weakConforms`, `conformanceSubstitutor`,
`isConservativelyCompatible`, `glb`, `lub`, `isAny/AnyRef/AnyVal/Nothing/Unit/...`,
`removeUndefines`, `removeAbstracts`, `removeVarianceAbstracts`, `removeInto`,
`extractDesignatedType`, `extractDesignated`, `extractClass`, `extractClassType`,
`extractClassSimple`, `canBeSameOrInheritor`, `canBeSameClass`,
`removeAliasDefinitions[AndReduceMatchTypes]`, `widen`, `widenIfLiteral`,
`tryExtractDesignatorSingleton`, `wrapIntoSeqType`,
`hasRecursiveTypeParameters`, `toPsiType`.

That's roughly 30 methods, all of which are surfaced to **every** consumer of `ScType`
across the plugin. Any `pc`-backed replacement has to service this contract.

### 1.4 TypeSystem, Equivalence, Conformance

`api/TypeSystem.scala:7` is a trait that mixes in `Equivalence`, `Conformance`,
`Bounds`, `PsiTypeBridge`, `TypePresentation`. There is exactly one production
implementation: `ScalaTypeSystem` in
`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/types/ScalaTypeSystem.scala:7`,
mixing `ScalaEquivalence`, `ScalaConformance`, `ScalaBounds`, `ScalaPsiTypeBridge`,
`ScalaTypePresentation`. It is the type system for **both** Scala 2 and Scala 3 —
there is no `Scala3TypeSystem`.

- `Equivalence.equiv` (`api/Equivalence.scala:30`) is cached, recursion-guarded
  (`Equivalence.scala:20`), and tied to a per-thread `DynamicVariable` evaluation
  flag (`Equivalence.scala:28`). `equivComputable` is implemented in
  `ScalaEquivalence.scala`.
- `Conformance.conformsInner` (`api/Conformance.scala:31`) is also cached. The
  fast-reject `canBeSameOrInheritor` (`package.scala:225`) short-circuits via
  `extractClassSimple` + IntelliJ's `sameOrInheritor`.
- `TypeResult = Either[Failure, ScType]` is defined in
  `result.scala:21`; `Failure(NlsString)(ProjectContext)` carries a localised
  diagnostic.

### 1.5 Java interop

`ScalaPsiTypeBridge` (`ScalaPsiTypeBridge.scala:68` — SCL-15936) converts between
`ScType` and IntelliJ's `PsiType` for Java cross-resolution. `StdType` carries a
synthetic `PsiClass` (`package.scala:402`) so that `Any`, `Int` etc. behave like
classes when the platform asks. `JavaArrayType` is modelled as its own `api` type.
Java statics are processed in `BaseProcessor.processTypeImpl` at
`BaseProcessor.scala:207-237`, including Kotlin-object workarounds for SCL-23032 /
SCL-25317.

### 1.6 Where `ScType` is consumed

Grep across the plugin shows `ScType` reaches into:

- `lang.resolve.*` — every processor (`processor/*.scala`)
- `lang.psi.implicits.*` — every file (`ImplicitCollector.scala`, `ImplicitProcessor.scala`, …)
- `codeInsight` — inlay hints (`ScalaTypeArgumentHintsPass.scala:197`), implicit hints
  (`codeInsight/implicits/ImplicitHintsPass.scala`)
- `lang.dfa` — `ExpressionTransformation.scala:31`
- `debugger` — `ScalaEvaluatorBuilderUtil.scala:1610`,
  `ScalaSmartStepIntoHandler.scala:202`
- `editor.importOptimizer` — `ScalaImportOptimizer.scala:1382`
- `actions.implicitArguments`, `actions.implicitConversions`,
  `autoImport.quickFix.ImportImplicitInstanceFix`
- `annotator` — pattern, type, and reference annotators
- Refactorings — extract method, rename, move

---

## 2. Resolve pipeline

### 2.1 Entrypoint

`trait ScReference` (`lang/psi/api/base/ScReference.scala:24`) declares:

- `multiResolveScala(incomplete: Boolean): Array[ScalaResolveResult]` — `ScReference.scala:34`
- `bind(): Option[ScalaResolveResult]` — `ScReference.scala:36`
- `getKinds(incomplete, completion)` — `ScReference.scala:202`
- `completionVariants`, `getSameNameVariants` — `ScReference.scala:204-206`

The concrete machinery for `ScReferenceExpression` lives in
`lang/resolve/ReferenceExpressionResolver.scala:44`. The flow is:

1. `ReferenceExpressionResolver.resolve(reference, shapesOnly, incomplete)` —
   `:72`
2. Build `MethodResolveProcessor` (`processor/MethodResolveProcessor.scala:35`) with
   invocation clauses, expected type, shape-only flag, etc.
3. Walk the PSI tree (`processor` walks up via `processDeclarations`), with each
   scope (`ScTemplateBody`, `ScPackaging`, local block, parameter list, import) given
   a chance to feed candidates.
4. Filter by precedence (`processor/precedence/`) — `BaseProcessor.scala:60` shows
   the `PrecedenceHelper` mix.
5. Pick a winner set; wrap each in `ScalaResolveResult`.

`ScStableCodeReferenceExtraResolver`
(`lang/psi/impl/base/ScStableCodeReferenceExtraResolver.scala:18`) is an
application-level EP (`org.intellij.scala.referenceExtraResolver`) so ammonite/shell
can add file-shape-specific resolution. `DynamicTypeReferenceResolver`
(`lang/resolve/DynamicTypeReferenceResolver.scala:7`) is another EP
(`org.intellij.scala.scalaDynamicTypeResolver`). `SyntheticClassProducer`
(`lang/resolve/SyntheticClassProducer.scala:9`, EP
`org.intellij.scala.scalaSyntheticClassProducer`) lets modules inject class symbols
out of thin air.

### 2.2 Processors

All processors live under `lang/resolve/processor/` and extend `BaseProcessor`
(`processor/BaseProcessor.scala:56`). The hierarchy:

- `BaseProcessor` — `:56`. Owns `candidatesSet`, `levelSet`, `uniqueNamesSet`,
  `RecursionState` (`BaseProcessor.scala:40`), accessibility checks, Java
  `ElementClassHint`, and crucially `processType` (`:142`) which structurally
  traverses a `ScType` to enumerate its members — this is the heart of "what can I
  call on `x`" and is where most of the type algebra gets exercised.
- `ResolveProcessor` (`processor/ResolveProcessor.scala:22`) — generic name-based
  resolution; manages precedence levels and import precedence.
- `MethodResolveProcessor` (`processor/MethodResolveProcessor.scala:35`) — the
  workhorse. 1458 lines. Handles overload resolution, applicability, tupling,
  Scala 3 overloading rules (`MethodResolveProcessor.scala:90`), constructor
  resolve, dynamic dispatch name args.
- `CollectMethodsProcessor`, `CollectAllForImportProcessor`,
  `CompletionProcessor`, `ConstructorResolveProcessor`, `ExtractorResolveProcessor`,
  `ExpandedExtractorResolveProcessor`, `CompoundTypeCheckProcessor`,
  `DynamicResolveProcessor`.

Each carries its own `nameUniquenessStrategy` and `precedenceHolder`
(`processor/precedence/`).

### 2.3 `ScalaResolveResult`

`lang/resolve/ScalaResolveResult.scala:166` is the fat result type with ~30 fields,
each heavily commented inline (`:32-165`). The notable ones for the `pc` mapping:

- `element: PsiNamedElement` — the resolved target (`:167`)
- `substitutor: ScSubstitutor` — type-parameter bindings (`:168`)
- `importsUsed: Set[ImportUsed]` — for optimize-imports & unused-imports
  highlighting (`:169`)
- `problems: Seq[ApplicabilityProblem]` (`:171`) — see
  `lang/psi/types/ApplicabilityProblem.scala` for the full ADT
  (`TypeMismatch`, `MissedValueParameter`, `ExcessArgument`,
  `NotFoundImplicitParameter`, `AmbiguousImplicitParameters`, …)
- `implicitConversion: Option[ScalaResolveResult]` (`:172`)
- `implicitConversionResultType`, `innerResolveResult`, `parentElement`, `fromType`,
  `tuplingUsed`, `applicabilityConstraints`, `inferredType`,
  `implicitArguments: Seq[ImplicitArgumentsClause]`, `implicitReason`,
  `implicitSearchState`, `unresolvedTypeParameters`, `implicitScopeType`,
  `isExtensionCall`, `extensionContext`, `intersectedReturnType`,
  `matchClauseSubstitutor`, `exportedInfo`, `isExtensionFromGiven`.

Equality/hash (`:312-326`) intentionally under-use these fields so different paths to
the same element collapse, which matters for `Find Usages`.

`getPrecedence` (`ScalaResolveResult.scala:368`) computes a `PrecedenceTypes` int
(same package, imported, wildcard-imported, packaging, …).

---

## 3. Implicit search & conversions

### 3.1 Implicit Collector

`lang/psi/implicits/ImplicitCollector.scala:38` (1176 lines) is the implicit-search
engine. Public surface:

- `ImplicitCollector.visibleImplicits(place)` (`:92`)
- `ImplicitCollector.visibleImplicitsByLevel(place)` (`:95`)
- `ImplicitCollector.implicitsFromType(...)` (`:98`)
- `ImplicitCollector.probableArgumentsFor(parameter)` (`:77`)
- `new ImplicitCollector(state: ImplicitState).collect()` — instance entry-point

`ImplicitState` (`ImplicitCollector.scala:58`) carries: `place`, `tp`, `expandedTp`,
`coreElement`, `isImplicitConversion`, `recursionDepth`, `extensionData`,
`fullInfo`, `previousDivergenceStack`. The result type `ImplicitResult` ADT is at
`:42-56`: `NoResult`, `OkResult`, `ImplicitParameterNotFoundResult`,
`DivergedImplicitResult`, `CantInferTypeParameterResult`, `TypeDoesntConformResult`,
`BadTypeResult`, `CantFindExtensionMethodResult`, `UnhandledResult`,
`FunctionForParameterResult`.

### 3.2 Implicit Processor hierarchy

`ImplicitProcessor` (`lang/psi/implicits/ImplicitProcessor.scala:34`) extends
`BaseProcessor`. It owns the implicit-scope walk:

- `candidatesByPlace` (`:120`) — lexical scope walk via `treeWalkUp`
- `candidatesByType(expandedType)` (`:125`) — implicit-scope parts walk via
  `findImplicitScopeParts` (`:182`), with its own 250-line implementation
  (`:206-495`) covering class super-types, package objects, anchors, opaque/match
  aliases, raw Java types, value aliases, projections, and the path-term rules from
  the Scala 3 reference.

Three subclasses:

- `ImplicitConversionProcessor` (`ImplicitConversionProcessor.scala:12`) — finds
  implicit views and `Function1`-typed values
- `ImplicitParametersProcessor` (`ImplicitParametersProcessor.scala:17`) — finds
  candidates for a single implicit parameter slot
- `ExtensionProcessor` (`ExtensionProcessor.scala`) — Scala 3 extension methods

### 3.3 Implicit conversion data & applications

`ImplicitConversionData` (`ImplicitConversionData.scala:27`) is the descriptor for an
implicit view. `isApplicable(fromType, place)` (`:38`) does the conformance check
with `checkWeak = true` and (for parameterised conversions) recursively invokes
`ImplicitCollector` for **local type inference** (`:59-94`). This is one of the most
obvious places where the plugin is hand-rolling what `dotc` already does correctly.

`DivergenceChecker` (`DivergenceChecker.scala:60`) implements the SIP on implicit
divergence by hand. `DivergenceInfo.dominates` (`:20`) uses three heuristics:
complexity, `topLevelTypeConstructors`, and `coveringSet`.

### 3.4 Scala 3 specifics

`Scala3Conversion` (`lang/psi/implicits/Scala3Conversion.scala:8`) is a 14-line
extractor for `scala.Conversion[A, B]`, which is how Scala 3 represents implicit
conversions under the new scheme. Note: it ignores the `into` modifier, which
`ScTypeExt.tryRemoveInto` (`package.scala:168`) handles separately by name match on
`scala.Conversion.into` — a clear correctness gap vs. `dotc`.

`isExtensionCall`, `extensionContext`, `isExtensionFromGiven`, `exportedInfo`
on `ScalaResolveResult` are all Scala 3-only fields; the entire
`ExtensionMethodData` / `ExtensionMethodApplication` / `ExtensionConversionData`
family in `lang/psi/implicits/` is a parallel resolver specifically for Scala 3
extension methods — duplicating logic that `dotc`'s typer already handles.

### 3.5 Consumers of implicit data

Consumers that any `pc`-based replacement must keep feeding:

- `codeInsight/implicits/ImplicitHintsPass.scala:108` — inlay hints for implicit
  args and conversions. Reads `owner.implicitArguments` and
  `srr.implicitConversion`.
- `actions/implicitArguments/ShowImplicitArgumentsAction.scala:133` — tree popup
- `actions/implicitConversions/ShowImplicitConversionsAction.scala:104` — popup
- `actions/implicitArguments/ImplicitArgumentNodes.scala:117` — tree model
- `autoImport/quickFix/ImportImplicitInstanceFix.scala:99` — quick-fix
- `editor/importOptimizer/ScalaImportOptimizer.scala:1382` — pulls
  `srr.implicitArguments` to retain necessary imports
- `debugger/.../ScalaSmartStepIntoHandler.scala:202` — uses `implicitElement()` to
  decide stepping targets
- `lang/dfa/.../ExpressionTransformation.scala:31` — DFA needs the resolved
  implicit function
- `debugger/.../ScalaEvaluatorBuilderUtil.scala:1610`

---

## 4. Type inference

### 4.1 `ScExpression.getType()`

`ScExpression` (`lang/psi/api/expr/ScExpression.scala:32`) extends `Typeable`. Its
`` `type`() `` (`:39`) just delegates to `getTypeAfterImplicitConversion().tr`.

`getTypeAfterImplicitConversion(...)` (`ScExpression.scala:146`) is cached via
`cachedWithRecursionGuard` keyed on the expression and a 5-tuple of arguments
(`:153-159`). Flow:

1. `getTypeWithoutImplicits(ignoreBaseTypes, fromUnderscore)` (`:304`)
2. `expectedType` / `expectedTypes` via `ExpectedTypes.instance()` (`:229-246`)
3. If `tp.conforms(expType)` → done.
4. Otherwise: try SAM adaptation (`tryAdaptTypeToSAM`), then
   `updateTypeWithImplicitConversion(tp, expType)` which invokes
   `ImplicitConversionData.getPossibleConversions(expr)`.

`smartExpectedType` (`ScExpression.scala:248`) is the second entry-point, used by
`implicitElement` and several refactorings.

`Tracing.inference(this)` (`ScExpression.scala:160,195`) feeds the
`#org.jetbrains.plugins.scala.Tracing` profiler — useful evidence that inference is a
recognised hot path.

### 4.2 Expected types & pattern-match inference

`ExpectedTypes` (`lang/psi/api/expr/ExpectedTypes.scala:9`,
`lang/psi/impl/expr/ExpectedTypesImpl.scala`) implements upward type propagation,
including pattern-match inference (`matchClauseSubstitutor` on `ScalaResolveResult`
is the carrier — `ScalaResolveResult.scala:194`). The comment at
`ScalaResolveResult.scala:152-158` cites the spec on "Type Inference in Patterns".

`PatternTypeInference` (`lang/psi/impl/expr/PatternTypeInference.scala`) does the
scrutinee narrowing; substitutes are accumulated as the resolver walks outwards.

### 4.3 Performance machinery

- `cachedWithRecursionGuard` (`caches/CacheWithRecursionGuard.scala:12,93`) is the
  universal cache. Used for `getTypeWithoutImplicits`, `getTypeAfterImplicitConversion`,
  `smartExpectedType`, `getNonValueType`, `expectedTypesEx`, etc. Invalidated by
  `BlockModificationTracker(element)` (`caches/BlockModificationTracker.scala:16`)
  or by `ModTracker.anyScalaPsiChange` / `ModTracker.libraryAware(element)`
  (`caches/ModTracker.scala:26,33`).
- `RecursionManager.RecursionGuard` (`caches/RecursionManager.scala:42`) and
  `markStack()` (`:27`) — the platform-style stack-stamp guard, used in every
  `equiv`/`conforms` cache (`api/Equivalence.scala:20`, `api/Conformance.scala:21`).
- `ContextDependent` (`api/Conformance.scala:53`) — caches results *per
  `Context`*, since the same `(left, right)` pair can have different conformance
  outcomes depending on `Context` (typically `Context(place)`).
- `ConstraintSystem` + `ConstraintsResult` (`lang/psi/types/ConstraintsResult.scala`,
  `ConstraintSystem.scala`) — the partial-result type returned from
  `equivInner`/`conformsInner`. `Right(system)` carries inferred type-variable
  bindings.
- `ImplicitCollectorCache` (`ImplicitCollectorCache.scala`, reached via
  `ImplicitCollector.cache(project)` — `ImplicitCollector.scala:39`) caches implicit
  candidates per `(place, type)`.
- `ImplicitObjectsCache` in `ScalaPsiManager` (referenced from
  `ImplicitProcessor.scala:190`) caches the implicit scope decomposition.
- `DivergenceChecker.currentStack` (thread-local) prevents runaway recursive
  implicit search.

---

## 5. Correctness and performance pain points

### 5.1 YouTrack references in the type code

`rg 'SCL-\d+'` in `lang/psi/types/` returns 35 hits. Notable:

- `ScalaType.scala:39` — `SCL-3592` "ugly hack" for abstract-type alias expansion
- `FunctionType.scala:50` — `SCL-6880` infinite-loop workaround with hard depth
  cutoff
- `nonvalue/ScMethodType.scala:64` — `SCL-16431`, `SCL-15354` method-type hack
- `ScalaEquivalence.scala:65` — `SCL-22598` extension methods on abstract types
- `SubtypeUpdater.scala:156` — `SCL-23190`, `SCL-20263` recursive type aliases
- `ScProjectionType.scala:74` — `SCL-15345` recursive projection
- `ScalaTypePresentation.scala:369-372` — `SCL-25555`, `SCL-25553` (open TODO):
  detect Scala 3 to suppress `Foo.this.` prefixes
- `Context.scala:25` — `SCL-23892` "Unify context parameters" (open)
- `ScalaTypePresentation.scala:468` — `TODO SCL-20394`: type lambdas and
  polymorphic function types **not modelled**
- `ScType.scala:93` — `SCL-21178`, `TypePresentation.scala:91` — `SCL-21183`:
  canonical renderer is insufficient

In the resolver (`lang/resolve`):

- `ResolveProcessor.scala:193` — "hack for self type elements to filter duplicates"
- `MostSpecificUtil.scala:212` — "todo: this is hack!!! see SCL-3846, SCL-4048"
- `MethodTypeProvider.scala:141` — TODO about returning the wrong shape
- `ScalaResolveResult.scala:90-93` — TODO that `tuplingUsed` is unreliable for
  later clauses; `:250` — TODO that `isAmbiguousImplicitParameter` is unreliable;
  `:454-455` — TODO: "this conflates imported functions and imported implicit
  views"
- `BaseProcessor.scala:198` — `@TODO: temporary fix, should be removed once
  lub/glb is implemented in a version specific manner`
- `ImplicitCollector.scala:73` — wraps `tp.presentableText` in
  `SlowOperations.knownIssue("SCL-23054")`

### 5.2 Scala 3 features not modelled (or partially modelled)

| Feature | Status |
|---|---|
| Match types | Hand-rolled `ScMatchType` with a 50-deep custom reducer (`ScMatchType.scala:100`) — diverges from `dotc`'s real reduction |
| Type lambdas | Only syntactic (`ScTypeLambdaTypeElement`); `ScalaTypePresentation.scala:468` admits they aren't modelled at the type level. `ScTypePolymorphicType` is the closest analogue but doesn't compose. |
| Polymorphic function types (`[T] => T => T`) | Parsed (`ScPolyFunctionExpr`), rendered as `PolyFunction` refinement (`package.scala:27`), but no first-class `ScPolyFunctionType`. |
| Dependent function types | Parsed (`ScDependentFunctionTypeElement`); typed via SAM-style hack |
| Match types in patterns | Not specifically modelled |
| `into` modifier | Hand special-case via `tryRemoveInto` (`package.scala:168`); `Scala3Conversion` ignores it |
| Singleton literal types in patterns | Partial; enum-value singleton via `ScLiteralType.scala:62` hack |
| Full intersection/union semantics | `ScAndType`/`ScOrType` exist but conformance rules are simpler than dotc's |
| `scala.compiletime.ops.*` | Shims in `intrinsics/CompileTimeOpsShims.scala` — covers a subset |
| `inline`/`transparent` | Not modelled in `ScType` |
| Type-class derivation (`Mirror.Of`) | Not modelled |
| Inheritance through match types | Reduced eagerly in `BaseProcessor.processTypeImpl` (`:326`); incorrect for non-reducible matches |

### 5.3 Performance

The single biggest perf cost is that every `equiv`/`conforms` call is a structural
recursion over `ScType` plus a `RecursionGuard` lookup plus a `ContextDependent`
cache miss on first `Context`. The implicit search then layers its own walk
(`ImplicitProcessor.findImplicitObjectsImpl`) on top, re-traversing the same `ScType`
to extract implicit-scope parts. `dotc` does both in a single typer pass.

---

## 6. Seam recommendations for swapping in `pc`

### 6.1 Map `pc` API to current call-sites

`pc`'s `ScalaPresentationCompiler` exposes approximately:

| `pc` method | Replaces |
|---|---|
| `typeAt(path, offset)` | `ScExpression.type()` / `Typeable.`type`()` for Scala 3 |
| `completionsAt(path, offset, query)` | `ScReferenceExpression.completionVariants` |
| `diagnose(path, offset)` / `infoAt` | `ScalaAnnotator` semantic phase, `ApplicabilityProblem` enumeration |
| `semanticTokens(path)` | syntax highlighting & "weak" semantic highlights |
| `infoAt(path, offset)` | quick-doc, `ShowTypeInfoAction` |

`pc` returns `pc.PcSymbol` / `pc.PcType` / `pc.PcDiagnostic` over its own
`Interactive` driver snapshot. The hard part is that the rest of the plugin expects
`ScType` and `PsiElement`.

### 6.2 `PcBackedScType` — a lazy `ScType` shim

Add `lang/psi/types/pc/PcBackedScType.scala`:

```scala
final class PcBackedScType(
  val snapshot: PcSession.Snapshot,
  val pcType: pc.PcType,
  val contextPlace: Option[PsiElement]   // for Context(place)
) extends ScType { ... }
```

`PcBackedScType` is a `LeafType` that does **not** expose `equivInner` directly —
the cache of `pc.typeAt` is used. The mapping of `ScType` operations to `pc`
queries:

| `ScTypeExt` method | `pc` query |
|---|---|
| `equiv(other)` / `conforms(other)` / `weakConforms` | `snapshot.typeIsSameType` / `typeIsSubType` (+ `Weak` flag) |
| `widen`, `widenIfLiteral` | `snapshot.widen(pcType)` (pc already widens literals) |
| `extractClass` / `extractClassType` | `pcType.symbol.info.classSymbol` → `PsiManager.findClass(...)` |
| `extractDesignated` / `extractDesignatedType` | `pcType.symbol` → symbol→Psi bridge (§6.4) |
| `removeAliasDefinitions[AndReduceMatchTypes]` | `snapshot.dealias` (+ `snapshot.reduceMatchType`) |
| `lub`, `glb` | `snapshot.lub(types)`, `snapshot.glb(types)` |
| `isAny/Nothing/Unit/...` | fast-class check on `pcType.classSymbol` |
| `canonicalText` / `presentableText` | `snapshot.render(pcType, Canonical/PresentableOptions)` |
| `toPsiType` | bridge through `extractClass` → `JavaPsiFacade` |
| `removeUndefines`, `removeAbstracts`, `removeVarianceAbstracts` | `snapshot.instantiate(pcType, bounds)` |
| `updateLeaves` | unsupported (only used by internal algorithms; replaced by `pc`-side ops) |
| `conformanceSubstitutor` | `snapshot.unify(pcType, otherPcType)` → `PcSubstitutor` |
| `hasRecursiveTypeParameters` | `snapshot.containsParamIds(pcType, ids)` |

Operations that today rely on the *shape* of `ScType` (`processType` in
`BaseProcessor.scala:142`, the implicit-scope walk in `ImplicitProcessor.scala:182`)
need new entry-points on `PcBackedScType`: `members`, `implicitScopeParts`,
`companionObjects`. These map to `pc` queries `member`, `implicitScope`,
`companionSymbol`.

For sites that *pattern-match* on `ScType` (`BaseProcessor.processTypeImpl`,
`ScCompoundType.equivInner`, etc.) we provide `pcType.kind` and a small extractor
vocabulary: `PcKind.Class`, `.Trait`, `.Object`, `.Compound`, `.Match`,
`.Lambda`, `.Method`, `.Poly`, `.ByName`, `.Literal`, etc. We must resist the urge
to materialise full `ScType` subtrees; the shim's value is that it stays opaque.

### 6.3 Sessions, snapshots, invalidation

```scala
final class PcSession(driver: Interactive[Symbols], sourceFile: ScalaFile) {
  def snapshot: Snapshot = ...
  def unsafeDriver: Interactive[Symbols] = driver
}
```

Each open Scala 3 file gets one `PcSession`. Document edits are sent to `pc` via
`driver.run` (in-memory `VirtualFile` sources). A `Snapshot` is taken per
read-action and is what `PcBackedScType` closes over — this makes a `pc` call on a
stale snapshot cheap and safe to detect.

Invalidation mirrors the existing scheme:

- `PsiModificationTracker` for the file → bump `driver` (re-run on next query)
- `BlockModificationTracker(expr)` for body-level caches is replaced by
  `PcSession.snapshotId` — any cached `PcBackedScType` whose `snapshotId != current`
  is dropped.
- `ModTracker.libraryAware` is still used as a dependency for *class* caches
  (`ImplicitConversionData.fromRegularImplicitConversion` at
  `ImplicitConversionData.scala:147`) — we can keep library-aware invalidation by
  keying `PcSession` on a classpath version, which `pc` already supports via
  `InteractiveDriver`'s classloader hash.

The macro-annotation caches (`@CachedWithRecursionGuard` on
` getTypeAfterImplicitConversion`, `smartExpectedType`, etc.) become
straightforward `cachedInUserData` calls whose value is a `PcBackedScType` bound to
the current `Snapshot`.

### 6.4 Symbol → PsiElement bridge

`pc.PcSymbol` carries `symbol.pos: Position` (file + start/end offset) when
available, and `symbol.fullName` for external/library symbols. Translating back to
PSI:

```scala
object PcSymbolPsi {
  def toPsi(sym: pc.PcSymbol, place: PsiElement): Option[PsiNamedElement] =
    sym.pos match {
      case pos if pos.source == place.sourceFile =>
        // local: by offset in this file
        place.getContainingFile.findElementAt(pos.start).parentOfType[PsiNamedElement]
      case pos =>
        // library: use ScalaPsiManager.getCachedClass
        place.elementScope.getCachedClass(sym.fullName).orElse {
          // package / package object
          ScalaPsiManager.instance(...).findPackage(sym.fullName)
        }
    }
}
```

This is the existing path the platform uses for Java `PsiClass` lookups, so we
inherit its caching and library-jar resolution. Synthetic symbols (e.g. `apply`,
`unapply`, `copy`, extension methods) require special handling: `pc` already reports
the *owner* symbol, so we synthesise a `ScSyntheticFunction` (see
`lang/psi/impl/toplevel/synthetic/`) — `BaseProcessor.scala:163` shows the existing
pattern.

### 6.5 Resolve: replacing the processor pipeline

`bind()` / `multiResolveScala(incomplete)` for Scala 3 references become:

```scala
override def multiResolveScala(incomplete: Boolean): Array[ScalaResolveResult] = {
  val session = PcSession.forFile(getContainingFile)
  val results = session.snapshot.resolve(refElement, incomplete)
  results.map { pcResult =>
    val element = PcSymbolPsi.toPsi(pcResult.symbol, this).getOrElse(ScalaResolveResult.empty.element)
    new ScalaResolveResult(
      element,
      substitutor        = pcResult.substitutor.toScSubstitutor,
      importsUsed        = pcResult.imports.map(toImportUsed).toSet,
      problems           = pcResult.diagnostics.map(toApplicabilityProblem).toSeq,
      implicitConversion = pcResult.implicitConversion.map(toScalaResolveResult),
      fromType           = pcResult.fromType.map(PcBackedScType(session.snapshot, _)),
      isExtensionCall    = pcResult.isExtension,
      // ... etc.
    )
  }
}
```

`MethodResolveProcessor` and the entire 1458-line `ReferenceExpressionResolver` for
Scala 3 go away. We keep them for Scala 2.

### 6.6 Implicit annotations & resolution results

Implicit data flows out through three channels:

1. `ScExpression.getTypeAfterImplicitConversion().implicitConversion` — single
   optional `ScalaResolveResult` representing the view applied
2. `ScalaResolveResult.implicitArguments: Seq[ImplicitArgumentsClause]` — implicit
   parameter clauses
3. `ScExpression.implicitElement(...)` (`ScExpression.scala:117`) — convenience for
   the implicit method/val

`pc` reports these via `PcSymbolInfo.implicitConversion` and
`PcSymbolInfo.implicitArguments`. Consumers (inlay hints, popups, optimizer,
debugger) read them through the same `ScalaResolveResult` fields, so as long as we
populate the result correctly they don't need to change.

`DivergenceChecker` and `ImplicitCollector` can be deleted for Scala 3 — `dotc`
already implements divergence correctly (and we have several open SCLs where ours is
wrong).

### 6.7 The migration boundary

The split point is `ProjectContext.typeSystem` (`project/ProjectContext.scala:15`)
which today always returns `ScalaTypeSystem.instance`. Introduce:

```scala
def typeSystem: TypeSystem =
  if (scalaLanguageLevel == Scala_3_LATEST) PcTypeSystem.instance(project)
  else ScalaTypeSystem.instance(project)
```

`PcTypeSystem` extends `api.TypeSystem` (so `Equivalence`, `Conformance`, `Bounds`,
`PsiTypeBridge`, `TypePresentation`) but every method delegates to `pc`. This is the
single highest-leverage seam: every `equiv`/`conforms`/`extractClass`/`presentableText`
call in the plugin is dispatched through it.

---

## 7. Public contract that must be replaced

The surface a `pc`-backed engine must satisfy. Many entries have 10+ overloads.

**Types** — `trait ScType` (`ScType.scala:13`), `NamedType`, the concrete type
classes in §1.2; `trait TypeSystem` and the five traits it mixes
(`api/TypeSystem.scala:7`, `api/Equivalence.scala:14`, `api/Conformance.scala:15`,
`api/Bounds.scala`, `api/ScTypePsiTypeBridge.scala`,
`api/presentation/TypePresentation.scala`); `ScalaTypeSystem`
(`ScalaTypeSystem.scala:7`); `ScTypeExt` (~30 ops — `package.scala:42`);
`ScTypesExt`, `ScalaSeqExt` (`package.scala:336,344`); extractor objects
`PolyFunctionType`, `ExtractDesignated`, `ImplicitMethodOrFunctionType`,
`FunctionLikeType`, `AnyArrayType`, `FullyAbstractType` (`package.scala:27` …
`:489`); `TypeResult`, `Failure`, `Typeable` (`result.scala:11,59`);
`ConstraintSystem`, `ConstraintsResult`; `AliasType`, `Signature`,
`TermSignature`, `TypeAliasSignature`, `TypeSignature`; `Context`,
`ContextDependent`; the `ApplicabilityProblem` ADT
(`ApplicabilityProblem.scala:11`); the `recursiveUpdate/*` substitutor family;
the entire `intrinsics/*` package (replace wholesale with `dotc`-driven
reductions).

**Resolve** — `trait ScReference` and `ScPolyResolvable`
(`lang/psi/api/base/ScReference.scala:24`); `ScalaResolveResult`
(`ScalaResolveResult.scala:166`); `ResolveProcessor`
(`processor/ResolveProcessor.scala:22`); `BaseProcessor`
(`processor/BaseProcessor.scala:56`); all other processors from §2.2;
`ReferenceExpressionResolver`, `StableCodeReferenceResolver`,
`ResolvableStableCodeReference`; `StdKinds`, `ResolveTargets`, `ResolveUtils`;
`ScalaResolveState` (`lang/resolve/ScalaResolveState.scala:127`); the
`MethodTypeProvider` family (`MethodTypeProvider.scala:21`); EPs
`SyntheticClassProducer`, `DynamicTypeReferenceResolver`,
`ScStableCodeReferenceExtraResolver`.

**Implicits** — `ImplicitCollector` object, `ImplicitState`, the `ImplicitResult`
ADT (`ImplicitCollector.scala:38,58,42`); `ImplicitProcessor`,
`ImplicitConversionProcessor`, `ImplicitParametersProcessor`,
`ExtensionProcessor`; `ImplicitConversionData` and subclasses
(`ImplicitConversionData.scala:27`); `ImplicitConversionApplication`,
`ImplicitConversionResolveResult`; `ExtensionMethodData`,
`ExtensionMethodApplication`, `ExtensionConversionData`, `ExtensionProcessor`;
`DivergenceChecker` / `DivergenceInfo` (`DivergenceChecker.scala`);
`ImplicitSearchScope`; `ImplicitCollectorCache`; `Scala3Conversion`
(`Scala3Conversion.scala:8` — subsumed by `pc`).

**Type inference entry-points** — `Typeable.type()` (`result.scala:11`);
`ScExpression.\`type\`()` (`ScExpression.scala:39`);
`getTypeAfterImplicitConversion` (`:146`); `getTypeWithoutImplicits` (`:304`);
`getNonValueType` (`:261`); the `ExpectedTypes` SPI
(`lang/psi/api/expr/ExpectedTypes.scala:9`,
`lang/psi/impl/expr/ExpectedTypesImpl.scala`); `smartExpectedType`,
`expectedType(s)(Ex)`; `PatternTypeInference`
(`lang/psi/impl/expr/PatternTypeInference.scala`); `InferUtil`
(`lang/psi/api/InferUtil.scala`).

**Caching infrastructure to keep** — `cachedWithRecursionGuard`,
`cachedInUserData` (`caches/CachesUtil.scala`,
`caches/CacheWithRecursionGuard.scala`); `RecursionManager` / `RecursionGuard`
(`caches/RecursionManager.scala:42`); `ModTracker`, `BlockModificationTracker`
(`caches/ModTracker.scala`, `caches/BlockModificationTracker.scala`). These stay;
the values they cache become `PcBackedScType` / `ScalaResolveResult` carrying
`PcBackedScType`.

---

## TL;DR

The plugin maintains a parallel type system that approximates
`scalac`/`dotc`: ~30 ops on `ScType` (`package.scala:42`), two custom engines
(`Equivalence`, `Conformance`), a 1458-line `MethodResolveProcessor`, a 1176-line
`ImplicitCollector`, a hand-written `DivergenceChecker`, fragile `ScMatchType`
reduction (`ScMatchType.scala:100`), and open SCL TODOs for type lambdas
(SCL-20394), polymorphic function types, dependent function types, and `into`.

The cleanest seam is `ProjectContext.typeSystem`
(`scala/scala-impl/src/org/jetbrains/plugins/scala/project/ProjectContext.scala:15`).
Introduce a `PcTypeSystem` for Scala 3 sources backed by a per-file `PcSession`
wrapping `pc`'s `Interactive` driver. Return `PcBackedScType` instances that lazily
RPC into `pc` for `equiv`/`conforms`/`widen`/`extractClass`/`dealias`/etc., and
populate `ScalaResolveResult` (the existing fat result type at
`ScalaResolveResult.scala:166`) from `pc.PcSymbol` / `PcDiagnostic`.
`MethodResolveProcessor`, `ReferenceExpressionResolver`, `ImplicitCollector`,
`ImplicitConversionProcessor`, `ImplicitParametersProcessor`,
`ExtensionProcessor`, `DivergenceChecker`, and `intrinsics/` can be retired for
Scala 3. The 30+ existing consumers — annotators, inlay hints, debugger, import
optimizer, refactorings — keep their `ScType` and `ScalaResolveResult` interfaces
and don't need to know `pc` is underneath.
