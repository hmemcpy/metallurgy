# dotc → Scala PSI mapping

The producer builds bundled-compatible PSI from the Scala 3 compiler's **untyped (parser) tree** (`unit.untpdTree`).
The parser tree preserves the source grammar the typer desugars away (verified empirically: `-Xprint:parser` keeps
for-comprehensions as `ForYield`/`GenFrom`/`GenAlias`, param-clause kinds, modifiers; `-Xprint:typer` desugars them to
`flatMap`/`map`, `$anonfun` closures, split tuple bindings). The typed tree remains a pure semantic overlay
(types/symbols for the `CompilerType` slot).

The detailed sections below record the construct-by-construct contracts and were written against the typed-tree
producer; their **Current/Gap** columns are superseded by the table here.

## Current status (untyped-tree producer)

| Construct | Source node | Target PSI | Status |
|---|---|---|---|
| `def` | `DefDef` | `FUNCTION_DEFINITION` (name `tIDENTIFIER`, `TYPE_PARAM_CLAUSE`, `PARAM_CLAUSE`s, `returnTypeElement`, body) | ✅ |
| multi-clause params | `DefDef.paramss` (flattened by the walk) | one `PARAM_CLAUSE` per source clause (split on `)(` between param ranges) | ✅ |
| `val`/`var` | `ValDef` | `PATTERN_DEFINITION` (`REFERENCE_PATTERN` in `PATTERN_LIST`) | ✅ |
| param | `ValDef` (child of `DefDef`) | `PARAM` (`PARAM_TYPE`) | ✅ |
| ident/select ref | `Ident`/`Select` | `REFERENCE_EXPRESSION` | ✅ |
| call | `Apply` | `METHOD_CALL` (`ARG_EXPRS`) | ✅ |
| generic call | `TypeApply` | `GENERIC_CALL` (`TYPE_ARGS`) | ✅ |
| type element | role-tagged child | `SIMPLE_TYPE`>`REFERENCE`, `TUPLE_TYPE`, `PARAM_TYPE` | ✅ |
| packaging | `PackageDef` | `PACKAGING` (nested `REFERENCE` chain) | ✅ |
| `object` | `ModuleDef` | `ScObject` (`EXTENDS_BLOCK`>`TEMPLATE_BODY`) | ✅ |
| `class` | `TypeDef` (keyword `class`) | `ScClass` (`EXTENDS_BLOCK`>`TEMPLATE_BODY`) | ✅ |
| `trait` | `TypeDef` (keyword `trait`) | `ScTrait` (`EXTENDS_BLOCK`>`TEMPLATE_BODY`) | ✅ |
| trait/class type params | `<init>` DefDef's TypeDefs | `TYPE_PARAM_CLAUSE`>`TYPE_PARAM` (extracted from the synthetic primary constructor) | ✅ |
| for-comprehension | `ForYield` | `FOR_STMT`>`ENUMERATORS`>`GENERATOR`/`GUARD`/`FOR_BINDING`+`yield` | ✅ |
| `given` (anonymous) | `ModuleDef` (keyword `given`) | `ScGivenDefinition` | ✅ |
| type alias | `TypeDef` (keyword `type`) | `ScTypeAlias` (`TYPE_DEFINITION`) | ✅ |
| `enum` | `TypeDef` (keyword `enum`) | `ScEnum` (`EnumDefinition`) | ✅ (dispatch; runtime-untested — driver can't synthesize enum companions) |
| `using`/`implicit` clause keyword | param mods | `kUSING`/`kIMPLICIT` inside `PARAM_CLAUSE` | ❌ (keyword as `identifier`) |
| modifiers/annotations | `mods` | `MODIFIERS`/`ANNOTATIONS` | ❌ (empty; bundled creates them) |

The `ProducerDifferentialDumpProbeTest` dumps bundled-vs-producer PSI (`DebugUtil.psiToString`) for any snippet and
asserts no `PsiErrorElements` — use it to find the next gap before mapping.

---

The producer builds a bundled-compatible PSI from the Scala 3 compiler's typed tree. This is the construct-by-construct
mapping: the **target** (the bundled grammar the resolver reads), the **source** (what dotc's tree provides), the
**current** producer status, and the **gap**. Resolve is the critical path; everything else follows from the declaration
and reference grammar being faithful.

## What the resolver reads (the contracts the producer must satisfy)

| Element | Resolver accessor | Reads |
|---|---|---|
| `ScFunctionDefinition` | `nameId` | direct `tIDENTIFIER` child (the name token) |
| | `paramClauses` | `PARAM_CLAUSES` → `ScParameters` → `ScParameterClause` → `ScParameter` (name binding + type) |
| | `typeParameters` | `TYPE_PARAM_CLAUSE` → `ScTypeParam` |
| | `returnTypeElement` | `ScTypeElement` child (the `: Tpt`) |
| | `body` | the `= expr` |
| | `processDeclarations` | exposes type params + parameters to the scope |
| `ScPatternDefinition` | `pList` | `PATTERN_LIST` (`ScPatternList`) |
| | `declaredElements` = `bindings` | `ScBindingPattern`s **inside** `PATTERN_LIST` |
| `ScReferenceExpression` | `nameId` | direct `tIDENTIFIER` child |
| | `resolve` | lexical, via `ScDeclarationSequenceHolder.processDeclarations` (same-file; no stub index needed) |
| `ScMethodCall` | `getInvokedExpr` | first `ScExpression` child (the callee) |
| | `args` | `ARG_EXPRS` → argument expressions |
| `ScGenericCall` | `getInvokedExpr` | the callee (e.g. `ScReferenceExpression`) |
| | `typeArgs` | `TYPE_ARGS` (`ScTypeArgs`) |

Composites **must** be emitted with balanced `PsiBuilder` markers (`marker.done(TYPE)`); `leaf()`/`collapse()` produce a
leaf that the viewer renders like the composite but is **not** the `Sc…Impl` the resolver casts to.

## Construct-by-construct mapping

### `DefDef` → `ScFunctionDefinition` (`FUNCTION_DEFINITION`)
- **Source (dotc):** `name: TermName`, `paramss: List[List[ValDef]]` (param clauses), `tparams: List[TypeDef]`,
  `tpt: Tree` (return type), `rhs: Tree` (body), each with a span; `flags` (modifier bits).
- **Target children, in source order:** `MODIFIERS` · `def` · name `tIDENTIFIER` · `TYPE_PARAM_CLAUSE` ·
  `PARAM_CLAUSES`(`PARAM_CLAUSE`(`PARAMETER`…)…) · `returnTypeElement` · `=` · body.
- **Current:** emits `FUNCTION_DEFINITION` with an **empty `PARAM_CLAUSES`** and recurses raw — the name token lands as a
  loose child, params/tparams/return-type/body are undifferentiated leaves.
- **Gap:** no `TYPE_PARAM_CLAUSE`, no real `PARAMETER` nodes (params appear as `ScPatternDefinition` — dotc models a
  parameter as a `ValDef`), no `returnTypeElement`, no body slot. `nameId` may resolve (token present) but
  `paramClauses`/`parameters` are empty → calls cannot bind → no type inference.
- **Needs:** the `paramss` grouping (clause boundaries) and the role of each child (tparam vs param vs tpt vs rhs).

### `ValDef` (top-level `val`) → `ScPatternDefinition` (`PATTERN_DEFINITION`)
- **Source (dotc):** `name`, `tpt`, `rhs`, `flags`.
- **Target children:** `MODIFIERS` · `val` · `PATTERN_LIST`(`ScBindingPattern(name)`) · `:` · `tpt` · `=` · `rhs`.
- **Current:** emits `PATTERN_DEFINITION` with an **empty `PATTERN_LIST`** and the name as a loose `tIDENTIFIER` child.
- **Gap:** the name is **not** wrapped in a binding pattern inside `PATTERN_LIST` → `declaredElements` is empty → the
  binding is unreachable by name → `val v` declares nothing.
- **Needs:** wrap the name token in a `ScBindingPattern` (`REFERENCE_PATTERN` / binding element type) inside
  `PATTERN_LIST`.

### `ValDef` (parameter) → `ScParameter` (`PARAMETER`)
- **Source:** a `ValDef` that is an element of a `DefDef.paramss` clause.
- **Target:** `PARAM_CLAUSE` → `PARAMETER`(`tIDENTIFIER` name · `:` · parameter type).
- **Current:** emitted as `ScPatternDefinition` (the generic `ValDef` mapping) → not a parameter.
- **Gap:** parameters are not recognized as such; `function.parameters` is empty.
- **Needs:** the producer must know a `ValDef` is a parameter (its parent is a `DefDef` and it is in `paramss`), and emit
  `PARAMETER` inside `PARAM_CLAUSE`.

### `Apply` → `ScMethodCall` (`METHOD_CALL`)
- **Source:** `fun: Tree`, `args: List[Tree]`.
- **Target:** `METHOD_CALL`(callee `ScExpression` · `ARG_EXPRS`(args…)).
- **Current:** emits `METHOD_CALL` + `ARG_EXPRS`, first child = callee, rest = args. Structure is close.
- **Gap:** the callee is emitted via the generic walk, which may produce a leaf, not an `ScExpressionImpl`. Verify the
  callee is a real `ScReferenceExpression`/`ScGenericCall` composite.

### `TypeApply` → `ScGenericCall` (`GENERIC_CALL`)
- **Source:** `fun: Tree`, `args: List[Tree]` (type trees).
- **Target:** `GENERIC_CALL`(callee · `TYPE_ARGS`(`tIDENTIFIER`/`ScTypeElement`…)).
- **Current:** emits `GENERIC_CALL` + `TYPE_ARGS` + `SIMPLE_TYPE`>`REFERENCE` for type-arg idents. Structure is close.
- **Gap:** same composite-fidelity concern; named type args (`A = Int`) need the `=` preserved (currently the producer
  keeps the tokens, which is why the structure looks right — but the type-arg element may need a specific grammar for
  named args).

### `Ident` / `Select` → `ScReferenceExpression` (`REFERENCE_EXPRESSION`)
- **Source:** `name: Name`; `Select` also has `qualifier: Tree`.
- **Target:** `REFERENCE_EXPRESSION`(`tIDENTIFIER`); `Select` → a `.`-separated reference chain.
- **Current:** `TypeApply`'s callee path emits a `REFERENCE_EXPRESSION` around the callee range; standalone idents in
  `Apply`/body are raw leaves.
- **Gap:** free-standing references (the call's `foo`, the body's `x`) are not wrapped in `REFERENCE_EXPRESSION` →
  `nameId`/`resolve` do not apply → no resolve.
- **Needs:** wrap every `Ident`/`Select` in a `REFERENCE_EXPRESSION` composite with its `tIDENTIFIER`.

### `Literal`, `TypeTree`, `TypeParam`, `Import`
- **Current:** literals and types are raw leaves; imports are raw.
- **Gap:** literals need `ScLiteral`; type trees need `ScTypeElement` composites (for `returnTypeElement`); type params
  need `ScTypeParam`; imports need `ScImportStmt` for cross-file/stdlib (`Int`, `String`) resolve.

## Scope chain (same-file lexical resolve)

Top-level definitions must be reachable through `processDeclarations`. The bundled parser wraps top-level Scala 3
definitions so the file exposes them via a declaration-sequence holder. The producer currently drops definitions as
direct children of the file root; verify the file root (or a synthetic holder) runs `processDeclarations` over them, or
`foo` will not resolve to `def foo` even with correct name tokens.

## Extraction enrichment required

The current `CompilerSourceNode(id, parentId, kind, range, sourceClass)` carries only **shape**. The faithful mapping
needs, per node:
- **role** — is this `ValDef` a parameter, a local, or a top-level binding? Is this child the return type, the body, a
  type param?
- **name** — `TermName`/`TypeName` for declarations and references (the resolver is name-based).
- **clause grouping** — `DefDef.paramss` boundaries (which params form which clause; implicit/using clauses).

These are all present on dotc's typed tree and accessible through the bridge's reflection layer (`tree.name`,
`tree.paramss`, `tree.tpt`, `tree.rhs`, `tree.flags`). The DTO and the producer switch from "emit a marker per physical
node" to "emit the bundled grammar, guided by dotc's roles and names."

## Coverage summary

| Construct | Structure | Name token | Resolve-critical children | Status |
|---|---|---|---|---|
| `DefDef` | ✓ outer | partial | params/tparams/return-type/body ✗ | partial |
| `ValDef` (binding) | ✓ outer | loose token | binding in `PATTERN_LIST` ✗ | partial |
| `ValDef` (param) | ✗ (wrong type) | — | `PARAMETER` in `PARAM_CLAUSE` ✗ | missing |
| `Apply` | ✓ outer | — | callee composite (verify) | close |
| `TypeApply` | ✓ outer | — | type-arg grammar (named args) | close |
| `Ident`/`Select` | partial | — | `REFERENCE_EXPRESSION` wrap ✗ (free-standing) | partial |
| `Literal`/`TypeTree`/`TypeParam`/`Import` | ✗ raw | — | element composites ✗ | missing |

The shortest path to "resolve works for a simple `def foo(x: Int): Int = x; val v = foo(42)`":
1. Wrap free-standing `Ident`/`Select` in `REFERENCE_EXPRESSION` (so `foo`/`x` are references).
2. Emit real `PARAMETER` nodes in `PARAM_CLAUSE` for param `ValDef`s (so `foo(x: Int)` binds).
3. Wrap the `val` name in a binding pattern inside `PATTERN_LIST` (so `v` declares).
4. Verify the file root exposes top-level defs via `processDeclarations` (scope chain).

Validate with: `pairRef.multiResolveScala(false)`, `function.parameters`, `patternDefinition.declaredElements`,
`PsiFileImpl.calcStubTree()`.
