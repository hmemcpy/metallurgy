# `psiproducer` package — dotc → Scala PSI producer

`Scala3DotcFileElementType.doParseContents` is the dialect file-root parse seam: it builds a bundled-compatible PSI
from the compiler's typed tree (`DotcPsiProducer`) when an extraction is installed (`DotcTreeSource`), returns a
pending placeholder leaf while the compiler has not decided, or falls back to the bundled parser. The decision state
is `ProducerParseState` (file-URI keyed). The normative architecture and the layered findings live in
`docs/scala3-compiler-backend.md` and `docs/research/producer-psi-delivery-findings.md`; this file holds only
standing contracts for producing faithful PSI.

## `doParseContents` must return the unwrapped first child

`ILazyParseableElementType.doParseContents` (platform default) wraps the parse in the file element type and returns
`node.getFirstChildNode()`. `LazyParseableElement.setChildren` makes the returned node the chameleon's **first child**.
Returning the wrapped root (`getTreeBuilt()`) nests an extra `ASTWrapperPsiElement(FILE)` whose only effect is to hide
top-level declarations from `ScalaFileImpl` (`ScDeclarationSequenceHolder.processDeclarations`) — **lexical resolve
silently breaks** with no error. Always return `builder.getTreeBuilt.getFirstChildNode` from the producer branch.

## PSI grammar contracts the resolver reads (emit these exactly)

| Element | Accessor | Required shape |
|---|---|---|
| `ScFunctionDefinition` | `nameId` | a direct `tIDENTIFIER` child of `FUNCTION_DEFINITION` (consume `def` + name before opening the param-clause marker) |
| | `keywordToken` (ScValueOrVariable) | the `val`/`var` keyword is a **direct child of the definition node** — consume it before opening `PATTERN_LIST` (else `keywordToken.get` throws `None.get`, surfaced via `getTextOffset`/breadcrumbs) |
| | `parameters` | `PARAM_CLAUSES`>`PARAM_CLAUSE`>`PARAM` (`ScParameter`); `paramClauses` must be non-null (emit an empty `PARAM_CLAUSES` when there are none) |
| | `returnTypeElement` | a `ScTypeElement` child — emit the `tpt` as `SIMPLE_TYPE`>`REFERENCE` |
| `ScParameter` | `typeElement` | the declared type wrapped in `PARAM_TYPE` |
| `ScPatternDefinition` | `declaredElements` | a binding pattern (`REFERENCE_PATTERN`) **inside `PATTERN_LIST`**; `pList` must be non-null |
| `ScReferenceExpression` | `nameId`/`resolve` | wrap free-standing `Ident`/`Select` in `REFERENCE_EXPRESSION` |
| file scope | `processDeclarations` | top-level defs are **direct children of the file** (a consequence of the unwrap rule above); same-file resolve is lexical |

**Type grammar is recursive.** A type-position child (`tpt`) is emitted by a recursive `emitTypeElement`, not the
generic value emit: a leaf named ref (`Ident`/`Select`) is `SIMPLE_TYPE > REFERENCE`; a `Tuple` is `TUPLE_TYPE`
whose elements recurse as types; other composites recurse children as types. Never wrap a non-ref (e.g. a tuple
`(A, B)`) in a single `REFERENCE` — it has no identifier, so `nameId` is null and navigation crashes
(`nameId is null for reference with text (A, B)`).

**Composites must use balanced `PsiBuilder` markers** (`marker.done(TYPE)`). `leaf()`/`collapse()` render identically
in the PSI viewer ("View PSI Structure") but are leaves, not the `Sc…Impl` the resolver casts to.

## dotc tree → PSI mapping

- `DefDef(name, paramss: List[ParamClause], tpt, rhs)`, `ValDef(name, tpt, rhs)`, `Ident(name)`, `Select(qualifier, name)`.
- **A parameter is a `ValDef` whose parent is a `DefDef`** (dotc models params as `ValDef`) — emit `PARAMETER`, not
  `ScPatternDefinition`. Body locals nest inside a `Block`, so a `ValDef` whose direct parent is a `DefDef` is a param.
- Surface `Name.toString` per node (`tree.getClass.getMethod("name")`) and tag type-position children by identity:
  access `tree.tpt`, match the returned tree against the walked entries via `IdentityHashMap`.
- Hardening (open): `advanceToToken(name)` matches textually; prefer the exact name **span** from dotc for
  backticked/encoded/operator/generated names.

## Delivery (the reload)

`AbstractFileViewProvider.onContentReload()` is `final` and is the full paired sequence
(`beforeChildrenChange`×2 → `PsiFileEx.onContentReload` → `contentsSynchronized` → `childrenChanged`×2) — the minimum
safe protocol; do not attempt an event-bypass. `ResolveCache` clears on `beforeChildrenChange` (not stale across the
reload). Do not call `requestReindex` for a physical, event-enabled provider (the reload triggers transient indexing).

## Don't block the parse

`doParseContents` has no thread guarantee (may run on the EDT) and holds the parse lock + read action; the reload's
write action cannot start while it is held → freeze/deadlock. Never block on the async extraction. A source the
bundled parser fragments returns a pending placeholder leaf (`Scala3DotcPendingLeaf.PendingFileContent`) — one leaf,
no `PsiErrorElement`, no `ScMethodCall` — so the file is never painted red and never trips the bundled `None.get`
crash (`ScMethodCallImpl.getInvokedExpr = findChild[ScExpression].get` on its own fragmented `[A = Int]` parse).
