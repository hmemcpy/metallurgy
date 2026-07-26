# Declarative PSI production catalog

## Question

What abstract production model can describe dotc syntax discriminators, child roles and cardinality, token ownership,
recovery, public `Sc*` accessor behavior, stub structure, runtime-native validation, and compatibility emission for the
complete Scala 3 grammar; and how is exhaustive coverage generated from compiler and Scala-plugin sources?

## Decision

Use one checked-in, reviewed production catalog to join two independently generated inventories:

```text
exact Scala compiler sources + executable parser evidence
  -> compiler syntax inventory

pinned Scala-plugin sources + runtime element capabilities
  -> Scala PSI inventory

compiler syntax inventory
  + Scala PSI inventory
  + reviewed production catalog
  -> compiled whole-file production plan
  -> validated source plan
  -> one deterministic AST and stub spine
```

Generated inventories discover facts and detect drift. They never infer that a compiler product means a particular
Scala PSI production. The reviewed catalog owns that semantic mapping.

Each catalog entry matches an exact neutral compiler shape using product prefix, ordered named fields, parent-field
role, positioned syntax, and source-form evidence. It declares ordered child slots, cardinality, complete physical
token ownership, trivia binding, layout alternatives, recovery alternatives, the target PSI production, public
accessor postconditions, and stub obligations.

The catalog is compiled and validated before it can produce PSI. A successful whole-file match has exactly one owner
for every source interval and exactly one structural role for every required compiler value. An unknown or ambiguous
required shape fails the exact source to deterministic neutral PSI. It never falls through to raw emission, the
bundled parser, a textual search, or a partially repaired Scala tree.

Native reuse means emitting the bundled plugin's own `IElementType`, allowing its normal element factory to instantiate
the existing `Sc*Impl` and stub types. It does **not** mean parsing a region with the bundled parser. A native target is
eligible only after executable contract probes establish the required runtime PSI class, children, accessors, and stub
behavior. A compatibility target is selected only when a checked implementation satisfies the same public contract.
Selection is by capability result, never by Scala or Scala-plugin version.

## Why a catalog is necessary

Dotc's untyped products preserve essential surface roles but are not a CST containing every token. Shared products
represent several source productions, nested collection fields carry grammar grouping, and some syntax roles are
positioned values outside the tree hierarchy. For example:

- `DefDef.paramss` is a nested list whose inner boundaries are parameter-clause boundaries;
- `TypeDef` represents classes, traits, enums, type aliases, and type parameters according to parent role, modifiers,
  right-hand side, and source evidence;
- `Function` represents both term and type forms, while `FunctionWithMods` adds positioned modifiers;
- `ImportSelector` contains the imported, renamed, and bound roles;
- `Mod.Given`, `Mod.Implicit`, `Mod.Inline`, and the other modifier products are `Positioned`;
- interpolation, XML, punctuation, comments, and virtual indentation are not recoverable from a tree-only walk.

These shapes are visible in dotc's
[`Trees.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/ast/Trees.scala#L430-L1038),
[`untpd.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/ast/untpd.scala#L40-L250), and
[`Positioned.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/ast/Positioned.scala#L20-L122).

The Scala plugin similarly reads structure, not rendered text. `ScalaParserDefinitionBase.createElement` delegates to
`ASTNodeToPsiElement.map`; known stub element types create their own PSI, known non-stub types map to concrete
implementations, and unknown types become a generic `ASTWrapperPsiElement`
([`ScalaParserDefinitionBase.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/parser/ScalaParserDefinitionBase.scala#L8-L13),
[`ASTNodeToPsiElement.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/parser/ASTNodeToPsiElement.scala#L17-L180)).
Accessors then find exact direct children or stub fields. A node with the right text but the wrong child nesting is a
different program to those APIs.

## Catalog boundary

The catalog consumes only host-owned neutral values:

- exact source and provisional source atoms;
- ordered compiler products with prefix, ordered named fields, and nested collection boundaries;
- source-derived, synthetic, absent, and transparent span classifications;
- positioned syntax such as modifiers and end markers;
- parser diagnostics and recovery evidence;
- exact comments and optional scanner candidates;
- reconstructed interpolation, XML, and virtual-layout evidence.

It does not see compiler objects, classloaders, reflection handles, presentation-compiler sessions, semantic typed
trees, compiler coordinates as behavior switches, or bundled parser output.

The catalog produces:

- a complete `LosslessSourcePlan`;
- an ordered marker plan for the whole file;
- a target PSI capability requirement for every composite and leaf;
- accessor and stub assertions attached to each production instance;
- structured coverage and failure records.

Types, symbols, resolve, and compiler diagnostics remain semantic overlays. BETASTY remains an independently discovered
semantic facility for cross-module recovery and highlighting; it neither supplies the source grammar nor selects a PSI
production.

## Abstract model

The implementation uses typed Scala data rather than an unvalidated configuration map. The following names describe
the model; exact packaging may change without changing the contract.

```scala
final case class ProductionCatalog(
  productions: Vector[Production],
  boundedHandlers: Vector[BoundedHandler],
  targetCapabilities: Vector[TargetCapability],
  reportPolicy: CoveragePolicy
)

final case class Production(
  id: ProductionId,
  compiler: CompilerPattern,
  context: ContextPattern,
  variants: NonEmptyVector[ProductionVariant]
)

final case class ProductionVariant(
  id: VariantId,
  when: EvidencePredicate,
  target: TargetChoice,
  children: Vector[ChildSlot],
  terminals: Vector[TerminalClaim],
  trivia: TriviaPolicy,
  layout: LayoutPolicy,
  recovery: Vector[RecoveryAlternative],
  accessors: Vector[AccessorContract],
  stub: StubContract
)
```

Identifiers are stable descriptive names such as `function-definition`, `context-parameter-clause`, and
`named-type-argument`. They are not compiler class names, upstream test identifiers, issue identifiers, or version
labels.

### Compiler patterns

```scala
final case class CompilerPattern(
  productPrefix: String,
  fields: Vector[FieldPattern],
  positioned: Vector[PositionedPattern],
  sourceClass: SourceClassRequirement
)

final case class FieldPattern(
  name: String,
  valueShape: ValueShape
)

enum ValueShape:
  case Tree(role: CompilerRole)
  case OptionalTree(role: CompilerRole)
  case Trees(role: CompilerRole)
  case GroupedTrees(groupRole: CompilerRole, itemRole: CompilerRole)
  case Positioned(role: CompilerRole)
  case PositionedValues(role: CompilerRole)
  case Name(role: CompilerRole)
  case OptionalName(role: CompilerRole)
  case Scalar(kind: ScalarKind)
  case Unsupported
```

Matching uses `productPrefix` and the complete ordered named-field signature. A field addition, removal, reorder, or
new neutral value shape is a different discovered compiler shape. It is reported rather than silently ignored.

The field name is the stable role source. Product position is retained to check the exact runtime shape, but catalog
rules refer to fields by name. Nested collections are never flattened. Empty, singleton, repeated, optional, and
grouped values remain distinguishable.

`ContextPattern` can constrain:

- the owning production and full parent field path;
- term, type, pattern, definition, selector, or template position;
- source-derived versus synthetic ownership;
- positioned modifier roles;
- compiler-provided name and scalar values;
- bounded source-form evidence between already proven child spans;
- parser diagnostic identity for a recovery alternative.

Source predicates cannot perform an unbounded search or decide a role from spelling alone. A terminal such as `using`
is a keyword only when the compiler product, parent field, modifier/parameter evidence, and bounded source interval
establish that role.

### Child slots and cardinality

```scala
final case class ChildSlot(
  role: PsiRole,
  source: FieldPath,
  cardinality: Cardinality,
  order: OrderPolicy,
  production: ProductionRef,
  placement: Placement
)

enum Cardinality:
  case ExactlyOne
  case Optional
  case ZeroOrMore
  case OneOrMore
  case Groups(group: GroupCardinality, item: GroupCardinality)
  case EmptyNodeRequired

enum Placement:
  case Direct
  case Inside(wrapper: TargetProduction)
  case Before(anchor: PsiRole)
  case After(anchor: PsiRole)
```

`EmptyNodeRequired` captures contracts such as an empty `PARAM_CLAUSES` child required by a public accessor. `Groups`
preserves structures such as parameter clauses and grouped type or term parameters. Ordering is defined by the named
compiler field and nested collection order, then checked against source spans; sorting unrelated children by range is
not a substitute for grammar order.

Every compiler field receives one disposition:

- structural child;
- terminal or layout evidence;
- semantic-only value;
- recovery-only value;
- synthetic/non-source value;
- explicitly unsupported value, which makes a required source instance unrepresentable.

No field disappears merely because the current emitter does not use it.

### Physical token ownership

```scala
final case class TerminalClaim(
  role: TerminalRole,
  interval: IntervalSelector,
  leaf: LeafTarget,
  owner: Ownership,
  occurrences: Cardinality
)

enum IntervalSelector:
  case ExactPositioned(field: FieldPath)
  case ExactChildSpan(field: FieldPath)
  case BeforeChild(child: PsiRole, boundary: BoundaryGrammar)
  case BetweenChildren(left: PsiRole, right: PsiRole, boundary: BoundaryGrammar)
  case AfterChild(child: PsiRole, boundary: BoundaryGrammar)
  case ProductionEdge(edge: Edge, boundary: BoundaryGrammar)
  case Bounded(handler: BoundedHandlerId, outer: FieldPath)
```

`BoundaryGrammar` is a small closed set of operations over an already bounded interval: exact literal delimiter,
identifier/name equality, operator slice, comma/semicolon separation, bracket or brace pair, and validated
whitespace/comment partition. It cannot scan beyond the production range or choose a grammar production.

Each non-empty source interval has one physical owner. Delimiters belong to the production that introduces them,
separators belong to the containing repeated/grouped slot, and names belong to the declaration/reference production
that exposes them. Trivia has an explicit binder policy—leading, trailing, same-line, doc-owner, or separator-adjacent—
rather than being swept into whichever marker happens to remain open.

Interpolation and XML use named bounded handlers because dotc delegates them to local character grammars. A handler
can refine only its parser-proven outer range, must cross-check embedded Scala child spans, and must return a complete
partition. It cannot serve as a general fallback lexer.

### Layout

`LayoutPolicy` declares braced, indented, parenthesized, and delimiter-free alternatives. An indented alternative
identifies the owning child field, opening evidence, closing boundary, and ordered virtual equivalents. `Indent`,
`Outdent`, `Newline`, and `Newlines` are zero-width structural metadata and never physical lexer leaves.

The catalog compiler rejects:

- an unbalanced or ambiguously owned indentation region;
- a braced variant that also claims synthetic indentation;
- layout whose child nesting disagrees with compiler field nesting;
- a scanner layout candidate without matching structural evidence;
- different virtual ordering for the same source and options.

### Recovery

```scala
final case class RecoveryAlternative(
  diagnostic: DiagnosticPattern,
  missing: Vector[ExpectedTerminal],
  recovered: Vector[RecoveredInterval],
  childOverrides: Vector[RecoveryChildRule],
  targetErrors: Vector[ErrorNodeContract]
)
```

Recovery is part of a production, not a global permissive mode. An alternative is eligible only when exact parser
diagnostics and recovery-tree evidence match. It may:

- omit a declared optional/recoverable child;
- record a zero-width expected terminal tied to the diagnostic;
- assign an exact malformed interval to a recovery leaf;
- emit a visible error node required by the recovery PSI contract.

It may not fabricate physical text, consume an unbounded suffix, weaken unrelated invariants, hide a dotc diagnostic,
or convert compiler-valid syntax into recovery. If the source cannot be partitioned and nested without guessing, the
whole source receives the deterministic neutral result.

## Target PSI and runtime validation

### Native targets

```scala
final case class NativeTarget(
  elementType: ElementTypeCapability,
  expectedPsi: PublicPsiType,
  probe: ProbeId
)
```

The pinned plugin declares stub-bearing and non-stub element types in
[`ScalaElementType.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/parser/ScalaElementType.scala).
Stub types implement `SelfPsiCreator`; non-stub types are mapped by `ASTNodeToPsiElement`. Emitting one of those element
types through Metallurgy's complete marker plan therefore creates the normal bundled implementation without invoking
the bundled grammar.

A native probe builds a minimal complete production using the plan-backed lexer and ready parser definition, then
checks:

1. target element-type identity;
2. concrete PSI assignability to the declared public `Sc*` type;
3. exact direct-child and wrapper structure;
4. every declared accessor result, nullability, order, text range, and ownership;
5. stub eligibility, stub PSI round-trip, and serialized signature where applicable;
6. deterministic repetition in a fresh fixture.

Static presence of a field or class is insufficient. A newer runtime may expose an element type but implement
different child expectations; only the executable contract admits it.

### Compatibility targets

```scala
final case class CompatibilityTarget(
  capability: CompatibilityCapability,
  expectedPsi: PublicPsiType,
  probe: ProbeId
)
```

Compatibility element types and PSI implementations are private to the Scala-plugin compatibility bridge. They
implement the smallest stable public `Sc*` contract needed by consumers. The parser definition can instantiate them
through a dedicated element type/factory; they are not injected by rewriting an already built native subtree.

Compatibility is appropriate when:

- dotc accepts a source production that the runtime does not represent;
- a native runtime element exists but fails the catalog's accessor or stub probe;
- a stable parent `Sc*` contract can be preserved with a compatibility child.

It is not appropriate when the required public interface itself is absent or incompatible. Such a runtime fails the
named target capability, and affected sources remain neutral. Runtime bytecode fingerprints, implementation-class
allowlists, and artifact-version branches are forbidden.

Target selection happens while compiling the complete catalog for a module epoch, before any source is emitted. A
source plan contains one resolved target for every production. There is no native-first parse followed by
compatibility repair.

## Public `Sc*` accessor contracts

The catalog records syntax-facing public behavior rather than implementation method names alone:

```scala
final case class AccessorContract(
  owner: PublicPsiType,
  accessor: MethodSignature,
  result: AccessorResult,
  sourceRole: PsiRole,
  ordering: ResultOrdering,
  emptyBehavior: EmptyBehavior
)
```

Representative contracts include:

- `ScFunction.nameId`, `paramClauses`, `parameters`, `typeParameters`, `returnTypeElement`, and `body`;
- `ScParameter.nameId`, `typeElement`, modifiers, and default expression;
- value/variable pattern lists, declared elements, declared type, and expression;
- type-definition name, type parameters, primary constructor, extends block, parents, derives clause, and template
  body;
- reference qualifier/name/type arguments and call argument lists;
- import/export expression and selector collections;
- pattern, case-clause, enumerator, interpolation, XML, and type-element children.

The need for exact shape is directly visible in native implementations. `ScFunctionImpl.paramClauses` asks for a
`PARAM_CLAUSES` child and `returnTypeElement` asks for a direct `ScTypeElement`
([`ScFunctionImpl.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/statements/ScFunctionImpl.scala#L66-L132)).
`ScTemplateDefinitionImpl.extendsBlock` asks for `EXTENDS_BLOCK`
([`ScTemplateDefinitionImpl.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/toplevel/typedef/ScTemplateDefinitionImpl.scala#L145-L153)).
Import expressions, pattern lists, and parameter clauses likewise use exact stub-or-PSI child types.

Generated discovery can identify public API members and child-query call sites, but it cannot infer their semantic
source role. Every syntax-facing accessor is reviewed into one of:

- catalog shape contract;
- semantic overlay contract;
- mutation/refactoring contract;
- derived convenience method covered by another contract;
- not applicable to source PSI.

Unclassified public accessors fail coverage.

## Stub contract

```scala
enum StubContract:
  case None
  case Native(
    elementType: ElementTypeCapability,
    parent: ParentStubRule,
    fields: Vector[StubField],
    indices: Vector[IndexEffect],
    signature: StubSignature
  )
  case Compatible(
    capability: CompatibilityCapability,
    parent: ParentStubRule,
    fields: Vector[StubField],
    indices: Vector[IndexEffect],
    signature: StubSignature
  )
```

The catalog records the stub data required by the production: field order and meaning, parent constraints, locality,
names, type/body text, top-level qualifier, modifier flags, and index effects. It does not reimplement serialization
from intuition. The generated Scala PSI inventory extracts `createStubImpl`, `serialize`, `deserialize`,
`shouldCreateStub`, `indexStub`, and external-id evidence from every stub element type.

For example, the native function stub records its name, declaration status, annotations, return/body text, assignment
state, locality, implicit/given data, top-level qualifier, and extension status, then indexes method and selected
top-level names
([`ScFunctionElementType.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScFunctionElementType.scala#L25-L142)).
Property stubs derive a different ordered field set
([`ScPropertyElementType.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScPropertyElementType.scala#L13-L72)).

This ticket defines the information and validation boundary. The deterministic stub/index design specifies canonical
signature encoding, versioning, persistence, and cold/warm/restart matrices.

## Catalog compilation

Catalog compilation is a pure, deterministic step:

1. load the generated compiler and Scala PSI inventories;
2. validate every referenced product, field, positioned role, element type, public API, and stub surface;
3. resolve target capabilities through native and compatibility probes;
4. expand variants into a decision table ordered by structural specificity;
5. prove that compiler patterns are non-overlapping or have mutually exclusive predicates;
6. prove that every child field has one disposition;
7. prove local terminal ownership, trivia binding, and layout balance for each variant;
8. compile bounded handlers and recovery alternatives;
9. run canonical examples twice and compare the complete neutral plan, AST signature, accessor signature, and stub
   signature;
10. publish an immutable compiled catalog for the module epoch.

Rule order is not a tie-breaker. Two matching variants are an ambiguity and fail compilation.

At source-planning time, the compiler product hierarchy is matched top-down by named parent field. Child slots retain
their field and group order. Terminal claims refine only already bounded intervals. The completed marker plan is
validated against the completed physical source plan before `PsiBuilder` is created.

At emission time, one walk:

- advances through the plan-backed physical leaves;
- opens and closes balanced markers from the marker plan;
- remaps contextual leaf types already decided by the catalog;
- creates declared visible recovery errors;
- finishes at exactly `source.length`;
- returns the file element's unwrapped first child.

Emission contains no compiler-kind switch, source-text search, grammar decision, exception recovery, or fallback.

## Generated inventories

### Compiler syntax inventory

The compiler inventory is generated for each exact artifact identity without building scala/scala3.

Inputs:

- the exact `scala3-compiler_3` binary artifact and dependency hashes;
- its matching published source artifact;
- executable neutral snapshots from the exact parser bridge;
- module compiler options used by the probe matrix.

The source extractor inventories:

- concrete `Tree`, `untpd.Tree`, `Positioned`, modifier, selector, and related product definitions;
- constructor and case-accessor field names and nested value shapes;
- inheritance and term/type/pattern/definition classifications;
- parser construction sites and source-production helpers;
- local interpolation, XML, layout, and recovery entry points.

The runtime extractor inventories:

- every product prefix and complete ordered named-field signature observed by parser probes;
- positioned non-tree products reachable from those fields;
- span/source classifications and nested collection shapes;
- parser diagnostics and recovery shapes;
- source examples and options that observed each shape.

Source and runtime rows cross-check each other. A source-declared parser product not observed is `declared-unobserved`,
not silently covered. A runtime product missing from the source inventory is `observed-undeclared`. Both require review.
Abstract, helper, typed-only, synthetic-only, and non-source products remain explicit classified rows.

Source artifacts are data for discovery, not behavior selectors. If a vendor artifact lacks matching sources, runtime
capability preparation can still prove known catalog shapes, but the graduation coverage report is incomplete and
cannot certify that artifact as fully covered.

### Scala PSI inventory

The Scala PSI inventory is generated from the exact pinned source revision without building intellij-scala. Its source
roots include:

- `ScalaElementType` and all token definitions;
- `ASTNodeToPsiElement` and every `SelfPsiCreator`;
- every `ScStubElementType` implementation;
- public `Sc*` API traits;
- concrete `Sc*Impl` child/accessor implementations;
- parser production sources and marker completion sites;
- stub creation, serialization, deserialization, locality, and indexing code;
- parser, lexer, PSI, stub, and copied fixture sources used as validation examples.

The generator emits element-type/factory mappings, public API signatures, child-query evidence, stub schemas, index
effects, and parser-production provenance. Values passed indirectly through variables are retained as unresolved source
expressions for review; a regular expression that sees only `marker.done(ScalaElementType.X)` is not exhaustive.

The runtime extractor resolves:

- required element-type identities;
- public class/interface assignability;
- optional native elements discovered structurally;
- factory results;
- native production probes;
- compatibility production probes.

Raw reflection, if required for an optional plugin implementation detail, remains isolated inside the Scala-plugin
compatibility bridge. Stable public Scala PSI interfaces and helpers are used directly.

## Coverage report

Generation produces machine-readable JSON and a concise Markdown summary under build output. The checked-in catalog is
reviewed source; generated reports are reproducible evidence and drift gates.

Each compiler row has:

- artifact/source identities;
- product prefix and full ordered field signature;
- parent-field contexts;
- source/recovery examples and option set;
- source classification;
- matched production/variant;
- terminal, layout, recovery, target, accessor, and stub coverage;
- status and structured reason.

Each Scala PSI row has:

- plugin source/runtime identities;
- element/token/factory or public accessor/stub identity;
- native runtime probe result;
- catalog references;
- status and structured reason.

Statuses are:

- `native` — completely mapped to a validated bundled production;
- `compatible` — completely mapped to a validated compatibility production;
- `recovery` — used only by a declared recovery alternative;
- `semantic-only` — retained for semantic overlays and never emitted;
- `synthetic-only` — compiler-generated and never owns source PSI;
- `helper` — grammar implementation support rather than a source production;
- `not-applicable` — reviewed target surface outside Scala 3 source PSI;
- `unclassified`;
- `ambiguous`;
- `missing-capability`;
- `invalid-contract`.

Certification requires:

1. no source-reachable compiler row is unclassified or ambiguous;
2. every compiler field has a disposition;
3. every target element and syntax-facing public accessor referenced by the catalog is present and probed;
4. every stub-bearing target has a complete stub contract;
5. every physical and virtual role is covered by a catalog rule or bounded handler;
6. every copied compiler-valid fixture produces `native` or `compatible` whole-file success;
7. every invalid fixture produces a declared recovery result or the named neutral result with diagnostics visible;
8. generated inventories match their locked inputs.

The report is exhaustive over discovered source/runtime surfaces, not merely over examples that happened to pass.
Examples prove behavior; inventories prove accounting.

Mutation checks delete or alter one product field, child slot, terminal claim, layout rule, recovery alternative,
accessor assertion, stub field, or target probe and require the appropriate coverage or contract gate to fail. This
prevents an apparently green report whose required row is no longer measured.

## Representative catalog mappings

### Function definition

`DefDef[name,paramss,tpt,preRhs]` in a statement/member field maps to `FUNCTION_DEFINITION` when its source role is a
definition. The variant:

- owns `def` and the exact positioned name as direct children;
- preserves every inner `paramss` group as one `PARAM_CLAUSE` inside required `PARAM_CLAUSES`;
- maps leading type-parameter groups to `TYPE_PARAM_CLAUSE`;
- maps `tpt` to the direct return `ScTypeElement`;
- maps the right-hand side to the body role;
- declares `using`, `implicit`, erased, and ordinary clause variants from compiler role evidence;
- probes `nameId`, parameter/type-parameter order, return type, body, and the complete function stub.

It never reconstructs clauses by looking for `)(` between flattened parameter spans.

### Type definition family

`TypeDef[name,rhs]` is not one catalog production. Context and evidence select mutually exclusive variants for:

- class, trait, enum, and enum-case templates;
- type alias definition or declaration;
- opaque type;
- method or template type parameter;
- match/type-lambda-related definitions where dotc uses a definition product structurally.

Template variants map constructor, parents, derives, self type, and body from their named fields. The source keyword is
owned only after the modifier/product context and bounded prefix establish the variant; source spelling alone does not
dispatch the rule.

### Named type argument

A named type argument is matched from the exact compiler argument product/field role and bounded `name = type` source
range. If the runtime exposes a native named-type-argument element whose probe satisfies the parent type-argument and
accessor contracts, the native target is used. Otherwise the compatibility target preserves the name, equals token,
type child, exact ranges, and parent `TYPE_ARGS` behavior. Other type arguments in the same file still follow their
catalog variants, but the whole plan is resolved before any AST is emitted.

This is systematic composition, not a parser-error-triggered repair region.

## Proof suite

### Catalog unit contracts

For every variant:

- accepted and near-miss compiler shapes;
- child role, cardinality, grouping, and ordering;
- exact physical interval ownership;
- direct-child marker structure;
- trivia and doc binding;
- braced and indented alternatives;
- recovery alternatives and visible diagnostics;
- native and compatibility target probes;
- all declared accessors;
- stub eligibility and complete stub signature.

Near-miss tests add, remove, reorder, or change one field or positioned role and require a named failure.

### Cross-version compiler lanes

Run identical catalog and plan assertions against the established exact parser lanes, including Scala 3.3 LTS, the
3.5 baseline, the project compiler, and a moving nightly. Coordinates choose artifacts only. Parser and production
capabilities choose behavior.

A new compiler product or field first appears as a report row. If existing structural rules cover it exactly, no
version-specific change is needed. If it expresses a new production, one reviewed catalog entry or variant adds
support.

### Scala-plugin lanes

Run source inventory generation against the pinned baseline and available later source revisions. Run runtime probes
inside the actual bundled plugin. A later runtime can supply a native production that replaces a compatibility target
when the same probe passes; no version condition changes.

The intellij-scala repository is never compiled. Exact snippets, assertions, and expected outputs are copied into
Metallurgy's owned harness unchanged.

### Whole-file and editor contracts

Assert exact AST, ranges, direct children, runtime PSI types, public accessors, and complete stubs for:

- definitions, declarations, modifiers, annotations, templates, enums, givens, extensions, exports, and end markers;
- all type, pattern, expression, interpolation, XML, import, selector, and layout forms;
- Unicode, operators, backticks, comments, CRLF, tabs, and empty files;
- invalid edits with declared recovery;
- cold, warm, copy, edit, index, and restart paths;
- inactive Scala 3, Scala 2, and mixed projects.

Compiler-valid copied tests retain their assertions and expected output exactly. Broad complex examples exercise deep
trees rather than only minimized syntax. Real projects remain a later graduation lane.

## Implementation slices

1. Add source/runtime inventory generators and locked schemas.
2. Add the typed catalog ADTs, validator, compiler, and coverage report.
3. Add a small initial vertical mapping—file/package, template, function, parameter clauses, types, references, and
   leaves—with intentional unclassified failures for everything else.
4. Add plan-backed target discovery and native PSI probes.
5. Add the compatibility element factory and one feature whose baseline runtime needs it.
6. Expand definitions, expressions, types, patterns, imports/exports, modifiers, annotations, layout, interpolation,
   XML, and recovery until the compiler inventory is fully classified.
7. Attach public-accessor and stub contracts to every production.
8. Replace `DotcPsiProducer` with the compiled marker-plan emitter only after copied tests prove the new path.
9. Remove the old kind switch, textual advancement, flattened-clause heuristics, raw emission, and bundled runtime
   parse fallback together.

Each slice leaves unknown required syntax neutral and reported. No slice makes an incomplete catalog appear complete.

## Acceptance criteria

The catalog decision is implemented only when:

1. generated compiler and Scala PSI inventories are locked to exact source/runtime identities;
2. the reviewed catalog is the only mapping from compiler syntax evidence to Scala PSI;
3. complete ordered product fields and nested collection boundaries cross the parser bridge;
4. positioned non-tree syntax participates in matching and terminal ownership;
5. every compiler field has an explicit disposition;
6. every physical source interval and virtual layout equivalent has exactly one owner;
7. all variants are structurally exclusive and rule order cannot resolve ambiguity;
8. native reuse means validated element-type/factory reuse, never bundled regional parsing;
9. compatibility targets satisfy the same declared public contract and are selected by executable capability;
10. every syntax-facing public accessor is classified and every catalog accessor is probed;
11. every stub-bearing target has a complete checked contract;
12. recovery is production-specific, preserves exact text, and keeps all dotc diagnostics visible;
13. unknown or invalid required shapes fail the whole source to deterministic neutral PSI;
14. the coverage report has no unclassified, ambiguous, missing-capability, or invalid-contract row in a certified
    lane;
15. mutation checks prove that removing any required mapping or assertion fails coverage;
16. copied compiler-valid Scala 3 tests pass with snippets, assertions, and expected outputs unchanged;
17. no behavior is selected by artifact version, implementation allowlist, bytecode fingerprint, parser error,
    source-text special case, or test identity.

