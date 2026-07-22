# 17 — Making the Scala 3 presentation compiler authoritative for Scala PSI types

## Question

Where can a dependent IntelliJ plugin inject presentation-compiler types so that every Scala subsystem which asks PSI
for a type sees the compiler result, including explicitly typed bindings? The answer below is based on
`intellij-scala` **2026.1.20** and IntelliJ Platform **261.26222.65**.

## Conclusion

**There is no supported injection point in these versions that lets a separate plugin replace Scala PSI typing
globally.** `CompilerType` is the closest mechanism, but it is deliberately narrow: it stores a string on any PSI
element, while the only general expression reader is `ScExpression.getTypeWithoutImplicits`. The Scala type system is
not dispatched through a replaceable `TypeEvaluator`, `ScalaPsiManager`, or Platform EP. `Typeable` is only an
interface whose concrete PSI classes implement `type()` themselves.

Therefore the strict goal has two possible outcomes:

1. **Supported and robust:** make a small change upstream in `intellij-scala` that generalizes the compiler-type read
   path to typed definitions/type elements (ideally behind a public external-type-provider contract), then let
   Metallurgy remain the asynchronous producer and cache owner.
2. **Separate plugin only:** keep the existing expression slot, PC completion, and PC inlays, but accept that an
   explicitly typed binding cannot have its canonical `type()` replaced without per-surface behavior or unsupported
   bytecode instrumentation. Populating `CompilerType(binding)` alone has no effect in 2026.1.20.

A wrapper PSI, a `SyntheticMembersInjector`, a Platform `PsiAugmentProvider`, or a `ScalaPsiManager` service override
does not close this gap. Runtime method-body instrumentation could technically patch the bundled class, but it is not
a stable IntelliJ plugin architecture and should not be shipped.

There is a second limit worth making explicit: a rendered compiler type is converted back into the bundled plugin's
`ScType`. That makes the PC authoritative for the *selected type expression*, but subsequent conformance, member
enumeration, and reference resolution still run in the bundled Scala type system. PC completion/definition support is
still needed for compiler-only structural or generated symbols which cannot be represented by ordinary Scala PSI.

## What `CompilerType` actually provides

`CompilerType` owns two private user-data keys, a public message-bus topic, and three operations: read the copied string,
write it, and publish a one-shot synchronous request. It does not own or intercept `Typeable.type()`:
[`CompilerType.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/CompilerType.scala#L7-L31).

The consumer is embedded directly in `ScExpression.getTypeWithoutImplicits`. When CBH and “use compiler types” are on,
the string is parsed with `ScalaPsiElementFactory.createTypeFromText`; otherwise the slot is cleared and ordinary
inference runs:
[`ScExpression.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/expr/ScExpression.scala#L301-L326).
This is a strong seam for expression consumers because `ScExpression.type()` flows through this machinery, but it is
not a cross-cutting PSI interception layer.

The bundled producer is similarly scoped. `ExternalHighlightersService` maps compiler-reported ranges only to
`ScExpression` or `ScStableCodeReference`, and only in the focused editor:
[`ExternalHighlightersService.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/ExternalHighlightersService.scala#L76-L99).
Completion requests a missing type only for a transparent-inline call, then copies the result into its completion-file
call expression:
[`completion/package.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/completion/package.scala#L259-L286).
The topic listener simply starts a document compilation request:
[`CompilerTypeRequestListener.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/CompilerTypeRequestListener.scala#L11-L23).

This explains both the usefulness and the limits of Metallurgy's current listener/pass approach: the topic is a useful
trigger and the slot is a useful cached value, but neither makes other concrete `type()` implementations read it.

## Why bindings bypass it

Scala PSI has no central evaluator. Its common `Typeable` contract contains only an abstract `type()` method:
[`result.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/types/result.scala#L10-L21).
Concrete nodes own the computation.

For a pattern definition, the implementation chooses the declared `ScTypeElement` first and asks that element for its
type; only an unannotated definition reaches the initializer expression:
[`ScPatternDefinitionImpl.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/statements/ScPatternDefinitionImpl.scala#L35-L56).
`ScTypeElement` has its own cached `getType` path which calls `innerType` and does not consult `CompilerType`:
[`ScTypeElement.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement.scala#L15-L41).

Consequently:

- `val x = rhs`: the definition reaches `rhs.type()`, so a slot on `rhs` can affect the binding type.
- `val x: T = rhs`: the definition reaches `T.type()` first, so a slot on `rhs`, `x`, or the definition is ignored.
- destructuring and typed patterns add more concrete pattern `type()`/`expectedType` paths; a single definition-level
  string is not necessarily the type of every bound symbol.

The best *upstream* minimal seam for explicit type syntax is `ScTypeElement.getType`: a compiler-type check there would
cover the declared-type branch without patching every caller. For exact per-binding compiler types, especially patterns,
the binding/pattern `type()` path must also consult a compiler result keyed to that binding. A genuinely universal
design would refactor `Typeable` evaluation behind one dispatcher or add the external-type check to each semantic root
(expressions, type elements, value/variable definitions and patterns, functions, and parameters).

## Existing extension mechanisms do not supply a general type

The Scala plugin's extension declarations include several names that sound relevant, but none intercepts arbitrary
Scala PSI typing. The declarations are visible together in
[`scala-plugin-common.xml`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/resources/META-INF/scala-plugin-common.xml#L4-L23).

### `org.intellij.scala.syntheticMemberInjector`

`SyntheticMembersInjector` injects textual functions, inner types, supers, or members into an
`ScTypeDefinition`; its API is class/member augmentation, not an element-type provider:
[`SyntheticMembersInjector.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/toplevel/typedef/SyntheticMembersInjector.scala#L20-L74).
It is useful when a library has a small, deterministic synthetic class API. It cannot replace `val x: T` typing, and a
synchronous injector must not wait for an out-of-process or isolated PC query. Materializing every compiler-visible
member through it would duplicate the compiler symbol model and still not intercept expression/binding types.

### Narrow Scala resolver/type EPs

- `org.intellij.scala.interpolatedStringMacroTypeProvider` can infer only an interpolated-string macro result:
  [`InterpolatedStringMacroTypeProvider.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/base/InterpolatedStringMacroTypeProvider.scala#L12-L38).
- `org.intellij.scala.scalaDynamicTypeResolver` contributes resolve results only for references which already qualify
  for Scala `Dynamic` processing:
  [`DynamicTypeReferenceResolver.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/resolve/DynamicTypeReferenceResolver.scala#L7-L18).
- `org.intellij.scala.referenceExtraResolver` is an internal fallback for stable code references (created for Ammonite),
  not a type evaluator:
  [`ScStableCodeReferenceExtraResolver.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/base/ScStableCodeReferenceExtraResolver.scala#L13-L39).

These can solve their named constructs, not impose a PC type on general PSI.

### `ScalaPsiManager`

`ScalaPsiManager` holds indexes, implicit caches, and signature/type-member maps for already-created `ScType` values;
for example, its `getTypes` methods build compound/intersection member maps:
[`ScalaPsiManager.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/ScalaPsiManager.scala#L55-L133).
It is not called as a general `typeOf(PsiElement)` service. Although `instance(project)` is a project-service lookup
([source](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/ScalaPsiManager.scala#L709-L720)),
the service registration is not marked `open`:
[`scala-plugin-common.xml`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/scala-impl/resources/META-INF/scala-plugin-common.xml#L601-L606).
Platform 261 permits a service override only when the original descriptor is `open`:
[`ServiceDescriptor.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/core-api/src/com/intellij/openapi/components/ServiceDescriptor.java#L74-L95).
Even a supported manager override would be at the wrong layer.

### Platform `PsiAugmentProvider`

Platform's `com.intellij.lang.psiAugmentProvider` can add Java PSI members and has an `inferType(PsiTypeElement)` hook:
[`PsiAugmentProvider.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/java/java-psi-api/src/com/intellij/psi/augment/PsiAugmentProvider.java#L40-L110).
The caller is Java's `PsiTypeElementImpl.calculateType`:
[`PsiTypeElementImpl.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/java/java-psi-impl/src/com/intellij/psi/impl/source/PsiTypeElementImpl.java#L102-L113).
Scala's `ScTypeElement` is a separate Scala PSI abstraction and never calls this provider, so the hook does not apply.

## Evaluation of the proposed approaches

| Approach | Result | Why |
|---|---|---|
| Broadly populate `CompilerType` | **Keep for expressions; insufficient for bindings** | It is cheap to read and copyable, but no slot value matters unless that node's concrete type path reads it. A daemon pass also covers open/analyzed files, not arbitrary batch-inspection PSI. |
| Resolve/typing EP | **No suitable EP exists** | The available Scala EPs are construct-specific. A completion contributor can replace completion candidates, but it does not change `Typeable.type()` for hover, resolve, or inspections. |
| Wrapping/decorating PSI | **Reject** | PSI implementations are created from AST element types by the language parser definition. Platform requires an AST element type to map unambiguously to its PSI implementation ([`ParserDefinition.createElement`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/core-api/src/com/intellij/lang/ParserDefinition.java#L83-L101)). A dependent plugin cannot transparently substitute wrappers throughout the existing Scala tree, and callers commonly use concrete Scala PSI types. |
| Replace the slot plus hijack binding computation | **No supported hijack in a separate plugin** | Reflection can access the private key and public Scala objects, but cannot change the body/virtual dispatch of `ScPatternDefinitionImpl.type()`. Pre-seeding private cache user data would depend on implementation-generated cache keys and still would not cover all type paths. Java-agent/bytecode transformation is startup- and version-coupled unsupported patching. |

## Recommended architecture

### Required bundled-plugin seam

Submit an `intellij-scala` change before claiming strict PC authority. The smallest viable version is:

1. Generalize compiler-type parsing into one helper, for example `CompilerType.scalaType(element, context)`.
2. Consult it at the start of both `ScExpression.getTypeWithoutImplicits` and `ScTypeElement.getType`.
3. Consult a binding-keyed result before the ordinary `ScPatternDefinition`/`ScVariableDefinition` and binding-pattern
   paths, so tuple/destructuring bindings can each carry their own PC type.
4. Extend the same contract to functions and parameters if “every typed definition” is literal, not only `val`/`var`.
5. Prefer a public `org.intellij.scala.externalTypeProvider` EP whose callback is **cache-only and synchronous**, or make
   `CompilerType` itself a supported public producer API. The callback must never launch or wait for PC work.

The broader, cleaner upstream refactor is a final type dispatcher around the current `Typeable.type()` implementations:
external cached type first, bundled implementation second. That is the only genuine single injection point, because
2026.1.20's `Typeable` is currently only an abstract method.

### Metallurgy side

Keep compiler work outside PSI getters and split it into producer, immutable snapshot, and publisher:

1. **`PcSessionManager` / `PcSemanticSnapshotService`:** own per-module PC sessions and immutable results keyed by
   `(file URI, document modification stamp, compiler options/classpath generation)`. Store range/position-to-type data;
   never treat a result from another document version as current.
2. **`PcTypeSnapshotPass`:** an `EditorBoundHighlightingPass` (plus file-open/document-change scheduling) captures text,
   ranges, and smart pointers under a read action, schedules/reuses the PC retypecheck on a bounded background executor,
   and computes expression and per-binding types. A daemon pass is the proactive UI path, not a guarantee for closed-file
   batch inspections; strict batch authority needs an upstream inspection/analyzer lifecycle which prewarms the snapshot.
3. **`PcCompilerTypePublisher`:** on a non-blocking read/UI finish step, revalidate project, pointer, file URI, and exact
   document stamp. Publish to expressions and, once the upstream seam exists, bindings/type elements. Clear old slots
   when a current snapshot has no type; never let an old completion overwrite a newer document.
4. **`CompilerTypeRequestResolver`:** retain the topic subscriber as a demand signal. Since `requestFor` uses a
   synchronous message-bus publisher, the listener must do only a cache lookup/schedule and return immediately. If the
   exact snapshot is ready it may publish immediately; otherwise schedule work and trigger a later daemon/completion
   refresh. It must never wait on the EDT.
5. **`Scala3PcCompletionContributor`:** keep PC-native completion for symbols that a rendered `ScType` cannot make the
   bundled member resolver enumerate. Type injection and compiler completion are complementary.

### Cache invalidation and threading

Writing copyable user data is not a PSI modification. The bundled implementation explicitly clears Scala caches for
every changed expression, increments the general Scala modification counter, and refreshes hints after applying compiler
types:
[`ExternalHighlightersService.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/ExternalHighlightersService.scala#L104-L145).
Metallurgy must preserve the equivalent invalidation after a *changed* value, preferably through a supported upstream
publisher API rather than reflective global invalidation. Coalesce publication per file/version to avoid a cache flush
per element.

The safe direction is one-way:

```text
document/version -> background PC retypecheck -> immutable semantic snapshot
                 -> short read/UI validation -> slot publication + Scala cache invalidation
                 -> hover/resolve/completion/inspection perform synchronous cached reads
```

Never reverse it by making `type()` or a message-bus callback wait for the PC. PSI type reads occur on EDT and inside
read actions; waiting for work that itself needs read access or UI publication creates classic read/EDT starvation and
deadlock. Cancellation and “latest generation wins” checks are mandatory. The observable contract should be: the PC is
authoritative for the latest *published* exact-version snapshot; before publication the result is unavailable and the
bundled implementation may be used as a temporary fallback. Absolute authority from the first arbitrary synchronous
read would require eagerly typing every file or blocking that read, neither of which is a viable IDE contract.

## Decision

For Metallurgy as a third-party plugin, continue using `CompilerType` as the expression bridge and PC-native completion
as the symbol bridge, but do not describe a binding slot as authoritative on 2026.1.20. Make the upstream generalized
type-reader/provider seam a prerequisite for “all type reads.” If an upstream change is impossible, explicitly narrow
the product claim to expression types, inlays, and PC completion; there is no robust hidden EP that completes the
binding story.
