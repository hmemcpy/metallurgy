# Compiler-authoritative semantic and diagnostic roots

## Question

Which IntelliJ and Scala-plugin query roots can produce semantic answers, and what
must Metallurgy own so that active Scala 3 files cannot silently return to the
bundled type engine?

This inventory uses the versions in the current build:

| Component | Revision |
| --- | --- |
| Metallurgy | [`3fad72e5511bafaf154c93c5af4181cbff6b44d0`](https://github.com/hmemcpy/metallurgy/tree/3fad72e5511bafaf154c93c5af4181cbff6b44d0) |
| IntelliJ Scala plugin 2026.1.20 | [`c2cc2b200999033e6b054ea361883a0d3fd79e26`](https://github.com/JetBrains/intellij-scala/tree/c2cc2b200999033e6b054ea361883a0d3fd79e26) |
| IntelliJ Platform 261.26222.65 | [`456919a9624bae72ac12efafc35d2b916cb0c5c5`](https://github.com/JetBrains/intellij-community/tree/456919a9624bae72ac12efafc35d2b916cb0c5c5) |
| Scala 3.7.4 | [`40be7608a48477951218ae3a8ac8749fe02ba988`](https://github.com/scala/scala3/tree/40be7608a48477951218ae3a8ac8749fe02ba988) |
| `mtags-interfaces` 1.3.4 | [`df81755a265c4a5657bb883ed2545c1455a3b951`](https://github.com/scalameta/metals/tree/df81755a265c4a5657bb883ed2545c1455a3b951) |

## Conclusion

Metallurgy needs one cache-only `CompilerSemanticFacade` between compiler
snapshots and every semantic PSI consumer. For an active, ready Scala 3 file,
`Current` compiler data is exclusive. `Pending`, `Unavailable`, `Failed`,
`MissingRole`, and a document-version mismatch are explicit unknown states; none
may invoke bundled inference or expose an older snapshot. Inactive modules and
Scala 2 continue to use the bundled implementation unchanged.

The facade must publish a structured, immutable whole-file snapshot. A rendered
type string in `CompilerType` is not sufficient: the Scala plugin asks for
conformance, equivalence, substitutions, member lookup, Java `PsiType`
conversion, resolve metadata, stable symbol identity, and navigation targets.
The compatibility layer must therefore convert neutral compiler types losslessly
to native `ScType` where possible, and provide a compiler-backed `ScType` or a
PC-native operation where a future compiler type has no native representation.
It must return explicit unknown when an operation cannot be represented, never
`Any`, `Object`, a stale value, or a bundled guess.

Native Scala PSI remains reusable production by production. A behavior
probe must demonstrate that every semantic entry point on that production routes
through the facade. Otherwise the production catalog selects a compatible PSI
implementation whose accessors call the facade directly. Descriptor scanning
and bytecode interception are not part of the target design.

The authority state machine is deliberately asymmetric:

| Scope and state | Semantic result |
| --- | --- |
| Inactive Scala 3 or any Scala 2 file | Untouched bundled implementation. |
| Active file, current snapshot and requested role present | Compiler answer only. |
| Active file, current snapshot but requested role absent | Explicit `MissingRole`; no bundled inference. |
| Active file, parser or semantic preparation pending | Explicit `Pending`; no old snapshot and no bundled inference. |
| Active file, capability unavailable | Explicit `Unavailable` with the failed capability. |
| Active file, compiler/session failure | Explicit `Failed` with provenance; no guessed semantic value. |
| Active file, stale document/model/classpath/options generation | Ineligible snapshot, reported as non-current. |

This rule applies to types, resolve, completion, navigation, usages, refactoring
validation, light wrappers, UAST, and semantic inspections. Presentation code
may show a progress state or omit an unavailable adornment; it may not manufacture
a semantic fact.

## Semantic roots

### Types

`Typeable.type()` is the common public type root, but it is not a single backend
hook. The bundled implementation distributes inference across many PSI classes:

| Consumer root | Bundled behavior that must be replaced for active files |
| --- | --- |
| `ScExpression.type()` | Reads the adapted type, implicit conversions, expected type, non-value type, and initial type. The narrow `CompilerType` slot is consulted only by initial-type logic and then parsed back into bundled `ScType`. |
| `ScTypeElement.type()` | Own cached computation independent of `ScExpression`. |
| `ScPatternDefinition`, `ScVariableDefinition` | Derive definition types from declarations, patterns, and initializers. |
| `ScFunction` and `ScFunctionDefinition` | Derive return types from declarations, bodies, supers, and Java-facing conversion. |
| `ScParameter` | Produces distinct inside and outside types. |
| `ScPattern` | Computes expected and inferred pattern types through distributed cached logic. |
| `ScGivenDefinition` | Computes the exposed given type independently of ordinary function and value roots. |
| `ScTypeAliasDefinition`, opaque aliases, and match aliases | Compute the aliased type and distinct lower and upper bounds. |
| `ScTypeBoundsOwner` and type parameters | Compute lower/upper bounds and contextual constraints used by inference and conformance. |
| `ScTypeDefinition` | Produces designator, `this`, super, inheritance, and member-facing types. |
| `ScType` / `TypeSystem` | Perform equivalence, conformance, bounds, substitution, member operations, presentation, and `PsiType` conversion after the initial answer. |

The source paths show why a topic that fills one string slot cannot establish
compiler authority:

- [`Typeable.type()`](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/types/result.scala#L10-L21)
- [`ScExpression` type roots](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/expr/ScExpression.scala#L39-L46)
- [`ScExpression` initial type and `CompilerType`](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/expr/ScExpression.scala#L301-L327)
- [`CompilerType` storage and request topic](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/CompilerType.scala#L7-L30)
- [`ScTypeElement.type()`](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/base/types/ScTypeElement.scala#L15-L41)
- [`ScParameter` inside and outside types](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/statements/params/ScParameter.scala#L58-L84)
- [`ScPattern` inferred and expected types](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/base/patterns/ScPattern.scala#L20-L115)
- [`ScGivenDefinition.givenType()`](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/toplevel/typedef/ScGivenDefinition.scala#L20-L31)
- [`ScTypeAliasDefinition` alias and bounds](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/statements/ScTypeAliasDefinition.scala#L20-L43)
- [`ScTypeBoundsOwner`](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/toplevel/ScTypeBoundsOwner.scala#L12-L42)
- [`ScType` algebra](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/types/ScType.scala#L13-L78)
- [`TypeSystem` operations](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/types/api/TypeSystem.scala#L7-L27)

The facade's type protocol is not merely “type at range.” Its lookup key must
identify both the semantic occurrence and the operation family:

| Operation family | Distinct answers that must not be conflated |
| --- | --- |
| Expression | exact/raw, non-value, widened, expected, adapted after implicit conversion, selected implicit conversion, and implicit arguments |
| Declared syntax | resolved type element while retaining the verbatim source syntax |
| Definition | definition and individual binding types, including destructuring |
| Callable | complete method/function signature, result type, given type, and extension receiver/application |
| Parameter | inside, outside, by-name, repeated, context, and `into` views |
| Pattern | inferred type, expected type, bound symbols, and irrefutability |
| Type declaration | alias right-hand side, opaque view, match alias, lower/upper bounds, type-parameter bounds, contextual constraints, designator, `this`, and supers |
| Type algebra | equivalence, conformance, substitution, member selection, base types, presentation, and Java `PsiType` conversion |

An exact answer for one role cannot be widened, normalized, or reused for
another unless dotc supplies that relationship explicitly. The protocol should
be an extensible family of typed operations with capability states, not a fixed
enum that must predict every future Scala construct.

### Symbols and resolve

Platform references expose `resolve`, `isReferenceTo`, rename/bind operations,
and completion variants. Scala references additionally expose
`multiResolveScala`, shape resolution, and canonical text:

- [`PsiReference`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/core-api/src/com/intellij/psi/PsiReference.java#L12-L124)
- [`PsiPolyVariantReference`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/core-api/src/com/intellij/psi/PsiPolyVariantReference.java#L6-L25)
- [`ScReference`](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/base/ScReference.scala#L24-L93)
- [`ScReferenceExpressionImpl` bundled resolver](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/expr/ScReferenceExpressionImpl.scala#L59-L99)

`ScalaResolveResult` carries substantially more than a target element:
substitution, imports, renamed elements, applicability problems, implicit
conversion and arguments, accessibility, receiver type, extensions, and export
metadata. A bridge that constructs only `new ScalaResolveResult(named)` loses
observable semantics:

- [`ScalaResolveResult`](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/resolve/ScalaResolveResult.scala#L32-L80)

The neutral symbol model therefore needs a stable compiler symbol ID, kind,
owner, flags, signature, alternatives, overrides, source/classpath origin, and
navigation location. Resolve results additionally need substitutions,
applicability, imports/exports, implicit and extension metadata, and all viable
alternatives. Source symbols map to deterministic source PSI. Classpath symbols
map to compiled, TASTy, or decompiled PSI where possible. A compiler-only light
identity is stable by module/model epoch, symbol ID, and origin; it is not
recreated per document revision.

### Completion, hover, definitions, references, and rename

The published PC surface provides completion, hover/signature help, definition,
type definition, document highlights, references, rename preparation and edits,
code actions, inlay hints, synthetics, symbol information, and SemanticDB:

- [`PresentationCompiler`](https://github.com/scalameta/metals/blob/df81755a265c4a5657bb883ed2545c1455a3b951/mtags-interfaces/src/main/java/scala/meta/pc/PresentationCompiler.java#L43-L204)
- [`DefinitionResult`](https://github.com/scalameta/metals/blob/df81755a265c4a5657bb883ed2545c1455a3b951/mtags-interfaces/src/main/java/scala/meta/pc/DefinitionResult.java)
- [`PcSymbolInformation`](https://github.com/scalameta/metals/blob/df81755a265c4a5657bb883ed2545c1455a3b951/mtags-interfaces/src/main/java/scala/meta/pc/PcSymbolInformation.java)
- [`ReferencesResult`](https://github.com/scalameta/metals/blob/df81755a265c4a5657bb883ed2545c1455a3b951/mtags-interfaces/src/main/java/scala/meta/pc/ReferencesResult.java)

For a current PC completion result, compiler semantic candidates are exclusive.
Only explicitly categorized syntax-only IDE items may be composed with them.
Unmatched bundled semantic candidates cannot be retained, and overload identity
must use compiler symbol/signature rather than lookup text.

Navigation and usages need stable PSI identity beyond a single PC response.
IntelliJ usage search enumerates index candidates and compares reference targets:

- [`ReferencesSearch`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/indexing-api/src/com/intellij/psi/search/searches/ReferencesSearch.java#L27-L159)

The project search path should keep indices for candidate enumeration and use
facade symbol identity for validation. Compiler-only symbols may require a
dedicated query executor but must not be inserted as ephemeral entries in global
stub indices. Refactoring is permitted only when a compiler symbol maps to
writable source PSI. PC rename operations can validate local edits, while
project-wide usages and rename retain IntelliJ's stable element contract.

### Light PSI, Java interop, and UAST

Light wrappers are semantic consumers. Method wrappers and typed-definition
wrappers call Scala type roots and convert the result to `PsiType`; UAST repeats
the same dependency for expression types, method return types, receivers, type
arguments, and call resolution:

- [`PsiMethodWrapper`](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/light/PsiMethodWrapper.scala#L67-L81)
- [`ScUExpression`](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/uast/src/org/jetbrains/plugins/scala/lang/psi/uast/baseAdapters/ScUExpression.scala#L20-L28)
- [`ScUMethodCallExpression`](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/uast/src/org/jetbrains/plugins/scala/lang/psi/uast/expressions/ScUMethodCallExpression.scala#L57-L82)

These consumers should inherit compiler authority through compatible Sc PSI and
lossless `PsiType` conversion. They must not replace an unavailable active type
with `Object` or `Any`. Physical worksheets can participate when they have a
stable document and semantic epoch. Debugger fragments, console fragments, and
other nonphysical files without that identity remain outside active-ready state
and retain their bundled behavior.

## Diagnostic ownership

Diagnostics have three distinct owners:

1. **Compiler language diagnostics** are dotc parser, typer, and warning
   diagnostics for the exact current source and compiler configuration.
2. **IDE-only inspections** report style, editor, and project concerns that the
   compiler does not own.
3. **Compiler-backed semantic inspections** are IntelliJ presentations of facts
   such as expected/actual type or resolution. They may run only from a
   `Current` facade snapshot.

For active ready files, parser recovery diagnostics are compiler language
diagnostics derived from the deterministic dotc parse plan. A bundled
`PsiErrorElement` or semantic annotator cannot contradict compiler-accepted
syntax. During asynchronous semantic preparation, parser diagnostics remain
visible while type-dependent inspections have no result yet; “unknown” is not
itself an error.

The bundled plugin's compiler-highlighting path compiles the current document,
checks the document version, publishes compiler highlights, and writes
`CompilerType` slots:

- [`CompilerHighlightingService`](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/CompilerHighlightingService.scala#L47-L166)
- [`DocumentCompiler`](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/DocumentCompiler.scala#L46-L175)
- [`ExternalHighlightersService`](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/ExternalHighlightersService.scala#L51-L145)

The public PC interface is not a dependable diagnostics capability. Its own
documentation says Metals obtains diagnostics from the build, and Scala 3.7.4's
PC `didChange` returns an empty sequence:

- [`PresentationCompiler` diagnostics note](https://github.com/scalameta/metals/blob/df81755a265c4a5657bb883ed2545c1455a3b951/mtags-interfaces/src/main/java/scala/meta/pc/PresentationCompiler.java#L300-L317)
- [`ScalaPresentationCompiler.didChange`](https://github.com/scala/scala3/blob/40be7608a48477951218ae3a8ac8749fe02ba988/presentation-compiler/src/main/dotty/tools/pc/ScalaPresentationCompiler.scala#L501-L504)

The retained interactive driver does expose current compilation units, trees,
contexts, and parser/typer diagnostics. If no published PC operation supplies
the required snapshot, a capability-probed structural bridge can read the
retained driver and export neutral DTOs:

- [`InteractiveDriver`](https://github.com/scala/scala3/blob/40be7608a48477951218ae3a8ac8749fe02ba988/compiler/src/dotty/tools/dotc/interactive/InteractiveDriver.scala#L28-L57)
- [`InteractiveDriver.run`](https://github.com/scala/scala3/blob/40be7608a48477951218ae3a8ac8749fe02ba988/compiler/src/dotty/tools/dotc/interactive/InteractiveDriver.scala#L144-L179)

Method presence is not a capability probe; the empty `didChange` implementation
demonstrates that behavior must be tested with known input. No diagnostic owner
may hide a produced highlight. Ownership is decided before rendering, and there
is no role for a `HighlightInfoFilter` that suppresses disagreement.

## Snapshot and facade contract

The facade lookup key includes:

- module and project-model epoch;
- exact compiler artifact, classpath, options, and parser-plan generations;
- file URI and document stamp or source digest;
- production and semantic occurrence identity;
- requested semantic role.

Population happens off the EDT. A file snapshot is published atomically only if
all generations still match. An edit immediately makes the old snapshot
ineligible; late work is discarded. PSI getters perform cache-only synchronous
lookups and never call the compiler or wait for it.

The neutral snapshot contains structured type graphs, symbols, resolve results,
diagnostics, and completion entries. Compiler-classloader objects never cross
the bridge. Rendered strings and `CompilerType` slots are transitional
presentation aids, not semantic authority.

`ScStubElementType.createElement` and `createPsi` make a production catalog
possible without changing consumer APIs:

- [`ScStubElementType`](https://github.com/JetBrains/intellij-scala/blob/c2cc2b200999033e6b054ea361883a0d3fd79e26/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScStubElementType.scala#L15-L64)

Each production declares its syntactic shape, stub contract, semantic roles,
native behavior-probe result, and selected native or compatible PSI
implementation. Syntax-only native nodes can be retained. A semantic native node
is retained only after the facade-routing probe passes.

## BETASTY: cross-module recovery

The motivating failure is cross-module Scala 3 highlighting when an upstream
module is broken. The upstream module's compiler errors remain visible.
Best-effort compilation may additionally emit generation-matched `.betasty`
artifacts so an exact downstream compiler can recover the upstream API and keep
downstream resolution, types, and highlighting available.

Scala 3 exposes separate producer and consumer flags, writes best-effort TASTy
from the full compiler pickling path, and loads it only when the consumer
capability is enabled:

- [`-Ybest-effort` producer and consumer settings](https://github.com/scala/scala3/blob/40be7608a48477951218ae3a8ac8749fe02ba988/compiler/src/dotty/tools/dotc/config/ScalaSettings.scala#L448-L449)
- [`BestEffortTastyWriter`](https://github.com/scala/scala3/blob/40be7608a48477951218ae3a8ac8749fe02ba988/compiler/src/dotty/tools/dotc/core/tasty/BestEffortTastyWriter.scala#L11-L42)
- [`SymbolLoaders` best-effort consumption](https://github.com/scala/scala3/blob/40be7608a48477951218ae3a8ac8749fe02ba988/compiler/src/dotty/tools/dotc/core/SymbolLoaders.scala#L474-L502)
- [`Pickler` integration](https://github.com/scala/scala3/blob/40be7608a48477951218ae3a8ac8749fe02ba988/compiler/src/dotty/tools/dotc/transform/Pickler.scala#L194-L312)

Producer and consumer support are discovered independently. Artifacts are tied
to the upstream output, classpath, compiler options, and model generation, and
cannot remain current after any of those change. Missing support disables only
broken-upstream cross-module recovery.

BETASTY does not type the current broken buffer, produce current-source
diagnostics, construct source PSI or stubs, select a grammar, or replace the
facade and current-buffer PC. The presentation compiler's interactive phases
include parser and typer but not the pickler:

- [`InteractiveCompiler` phases](https://github.com/scala/scala3/blob/40be7608a48477951218ae3a8ac8749fe02ba988/compiler/src/dotty/tools/dotc/interactive/InteractiveCompiler.scala#L10-L20)

## Implementation disposition

### Retain and deepen

- Scala 3 opt-in module gate and exact compiler artifact resolution.
- Isolated PC sessions and normalized project model.
- `Scala3PcBridge` as the only compiler-implementation boundary, with structural
  access confined to it and neutral DTOs crossing it.
- Coalesced background population, cancellation, and immutable publication.
- Public PC adapters for completion, hover, navigation, references, rename, and
  other operations when behavior probes confirm them.
- Retained-driver diagnostics.
- Independently discovered BETASTY producer and consumer facilities.

### Replace

- Per-file rendered-type maps with the structured whole-file facade snapshot.
- The small current role set with roles covering every type, resolve, symbol,
  diagnostic, completion, light-PSI, and alternate-model consumer.
- Span/ancestor matching with production and semantic-occurrence identities.
- The completion merger with compiler-exclusive semantic completion plus
  explicitly categorized syntax-only composition.
- Current light elements with stable compiler symbol identity and lossless type
  conversion.
- Multiple diagnostic caches/renderers with one ownership broker.
- Adapters that permit active non-current states to delegate to bundled
  inference.

### Retire after migration

- Descriptor scanning, bytecode fingerprints, and bundled-backend interception.
- `CompilerTypeRequestResolver`, slot writes, and topic requests as authority.
- Highlight suppression filters.
- Rendered strings as the source of type truth.
- Duplicate compiler-highlight layers once the diagnostic broker owns
  publication.

Current tests that assert nonempty bundled resolve wins, that pending,
unavailable, failed, or stale states fall through, that unmatched bundled
semantic completion is preserved, or that descriptor discovery is required
lock in behavior the target forbids. Replace those expectations while preserving
the Scala snippets and public consumer entry points.

## Capability policy

Capabilities are discovered by behavior, independently of version labels:

- PC construction, lifecycle, and configuration;
- structured whole-file typed snapshot;
- retained diagnostics;
- each public PC operation;
- BETASTY production and consumption separately;
- native Scala-PSI routing through the facade;
- compatible PSI construction, accessor, and stub contracts.

Supported public APIs come first, structural typing second, and isolated raw
reflection only inside the compiler or Scala-plugin compatibility bridge. No
nearest-version fallback, implementation-class allowlist, or bytecode
fingerprint selects behavior.

If a required parser capability is absent, the file remains in a neutral
pending/unavailable state. If a required semantic capability is absent for an
active ready file, its affected role is explicitly unavailable. An optional
operation makes only that operation unavailable. None of these cases activates
bundled inference.

## Verification matrix

The implementation is complete only when the same unchanged fixtures exercise:

- inactive Scala 2 and Scala 3 delegation;
- parser pending, active `Current`, edit-induced `Pending`, `Unavailable`,
  `Failed`, `MissingRole`, stale publication, and model/configuration changes;
- every type role, `ScType` operation, and Java `PsiType` conversion;
- expression and stable-reference resolve, overloads, givens, extensions,
  imports, exports, and implicit conversions;
- compiler errors and warnings, PSI syntax errors, IDE-only inspections, and
  compiler-backed semantic inspections;
- compiler completion, overload identity, auto-import edits, and syntax-only
  composition;
- hover, signature help, navigation, usages, rename, light PSI, UAST,
  worksheets, and nonphysical-file delegation;
- clean and broken upstream modules with BETASTY generation, consumption,
  invalidation, repair, and rename;
- cold/warm caches, PSI copies, indexing, file close/reopen, cancellation, and
  concurrent edits.

Assertions include exact dotc/REPL/`-Xprint:typer` answers, identical public
outputs through forced native and compatible lanes, zero bundled-inference calls
for active non-current states, rejection of stale publication, no compiler work
on the EDT or in PSI read hooks, and visibility of every produced highlight.
Mutation checks should deliberately restore each forbidden escape hatch:
bundled fallback, unmatched bundled semantic completion, bytecode interception,
string-slot authority, method-existence-only diagnostics, and stale BETASTY.

After fixture parity, broad examples with complex trees and pinned real projects
such as Cats, FS2, and Shapeless provide graduation coverage for type-level and
cross-module behavior.
