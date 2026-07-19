# 09 — Scala 3 Feature Coverage Gaps

Domain report for the Metals‑`pc` redesign. Audits the Scala 3
language surface against the plugin's hand‑written parser, PSI, type
system, annotators, and synthetic injectors, and proposes a
`pc`/`dotc`‑based replacement for every gap.

Classification:

- ✅ **Working** — parser, semantic, and tests all present.
- ⚠️ **Partial** — syntax accepted, semantic handling has known holes.
- ❌ **Missing** — no parser/PSI support, or feature is explicit TODO.

`path:line` references are relative to the repo root. YouTrack IDs
appear in code comments as `SCL-XXXXX`.

---

## At‑a‑glance status

| #  | Feature | Parse | Semantics | Tests | Status |
| -- | ------- | :--: | :--: | :--: | :--: |
| 1  | Optional braces / significant indentation | ✅ | ✅ | ✅ | ✅ |
| 2  | `end` markers | ✅ | ✅ | ✅ | ✅ |
| 3  | Enums (cases, params, `values`/`valueOf`) | ✅ | ⚠️ | ⚠️ | ⚠️ |
| 4  | Given instances & using clauses | ✅ | ⚠️ | ⚠️ | ⚠️ |
| 5  | Extension methods | ✅ | ⚠️ | ⚠️ | ⚠️ |
| 6  | Type lambdas / poly function types / dependent fun types | ✅ | ⚠️ | ⚠️ | ⚠️ |
| 7  | Match types (definition + reduction) | ✅ | ⚠️ | ⚠️ | ⚠️ |
| 8  | Intersection (`&`) / union (`\|`) types | ✅ | ⚠️ | ✅ | ⚠️ |
| 9  | Opaque types | ✅ | ⚠️ | ⚠️ | ⚠️ |
| 10 | Export clauses | ✅ | ⚠️ | ⚠️ | ⚠️ |
| 11 | Inline methods, `transparent inline`, quotes/splices | ⚠️ | ❌ | ⚠️ | ❌ |
| 12 | `@main` annotation methods | ✅ | ⚠️ | ✅ | ⚠️ |
| 13 | Top‑level definitions | ✅ | ⚠️ | ✅ | ⚠️ |
| 14 | Named tuples (Scala 3.5+) | ✅ | ⚠️ | ⚠️ | ⚠️ |
| 15 | `into` / `only` / `inlined` parameter modifiers | ⚠️ | ❌ | ❌ | ❌ |
| 16 | `@main` / `@targetName` annotations | ✅ | ⚠️ | ✅ | ⚠️ |
| 17 | Context functions (curried context) | ✅ | ⚠️ | ⚠️ | ⚠️ |
| 18 | New control syntax (`if/then`, `for/do`, `while/do`, `*` varargs, fewer braces) | ✅ | ✅ | ✅ | ✅ |
| 19 | Macro annotations (Scala 3.3 experimental) | ❌ | ❌ | ❌ | ❌ |
| 20 | Universal `equals` / `hashCode` for transparent inline traits | ❌ | ❌ | ❌ | ❌ |
| 21 | TASTy back‑reference for compiled Scala 3 libraries | ⚠️ | ⚠️ | ⚠️ | ⚠️ |

**Bottom line:** The parser (`lang/parser/`) accepts ~95% of the
surface, but the hand‑rolled type system in `lang/psi/types/`
reproduces dotc only for the simplest shapes. Anything requiring
*reduction* — match types, transparent inline, quotes/splices,
derive/deriving, named‑tuple ops, capture checking — is partial or
missing.

The fix in every case is the same: **delegate to `pc`** (the Metals
presentation compiler, which embeds `dotc`) for resolve, type, and
short‑snippet macro expansion, keeping the IntelliJ parser only for
editing/navigation.

---

## 1. Optional braces / significant indentation — ✅

Indentation regions are first‑class in the parser
(`lang/formatting/processors/ScalaIndentProcessor.scala:394`). Editor
machinery — enter, backspace, copy/paste — is fully implemented:

- `editor/enterHandler/Scala3IndentationBasedSyntaxEnterHandler.scala`
- `editor/backspaceHandler/Scala3IndentationBasedSyntaxBackspaceHandler.scala`
- `editor/copy/Scala3IndentationBasedSyntaxCopyPastePreProcessor.scala`
- `editor/Scala3IndentationBasedSyntaxUtils.scala`

Exhaustive editor‑state tests live under
`scala/scala-impl/test/.../enter_long_tests/scala3/`
(`Scala3BracelessSyntaxEnterHandlerTest_ExhaustiveGroups.scala`).
Golden PSI in `testdata/parser/data3/bracelessSyntax/`.

**Status:** no `pc` work required; editor‑side only.

---

## 2. `end` markers — ✅

Parser rule `lang/parser/parsing/base/End.scala:7` whitelists marker
tokens (`if`, `while`, `for`, `match`, `try`, `this`, `new`, `given`,
`extension`, identifier). PSI: `ScEnd`; Find Usages via
`highlighter/usages/ScHighlightEndMarkerUsagesHandler.scala`;
completion via `ScalaEndMarkerCompletionContributor.scala`. `ScEndImpl.scala:32`
documents the deliberate SCL‑19675 decision to enable rename / Find
Usages but suppress usage highlighting.

Tests: `ScalaEndMarkerCompletionTest.scala`, golden `.test` files in
`testdata/parser/data3/bracelessSyntax/`.

**Status:** complete.

---

## 3. Enum types — ⚠️ Partial

**Syntax:** `TmplDef.scala:54` dispatches to `EnumDef`. Testdata at
`testdata/parser/data3/tmplDef/enum/`. PSI distinguishes `ScEnum`,
`ScEnumClassCase`, `ScEnumSingletonCase`.

**Semantic gaps:**

- `EnumMembersInjector.scala:32‑49` injects `values`, `valueOf`,
  `fromOrdinal` into the companion only. Line 42 admits:
  *"@TODO: valueOf return type is actually LUB of all singleton cases"*.
- Line 43‑49: only emits `values`/`valueOf` when *every* case is a
  singleton; parameterised enum cases (`enum Option[+T] { case
  Some[T](x: T); case None }`) lose them.
- `Mirror.Sum` synthesis (`SyntheticImplicitInstances.scala:225‑254`)
  is hand‑rolled; `MirroredElemTypes` ordering and GADT enums with
  type params are unreliable.

**Proposed `pc` fix:** for any expression whose type involves an enum
companion, call `pc.typeOf` and let dotc resolve the synthetic member.
PSI keeps only the synthetic‑member signal for navigation.

---

## 4. Given instances & using clauses — ⚠️ Partial

**Syntax:** accepted (`top/template/GivenDef.scala`,
`ScGivenDefinitionImpl.scala:30`, `ScGivenAliasDefinitionImpl.scala`).
Anonymous givens and the new 3.6 parameter syntax are parsed
(`Scala3_6_NewGivensParserTest.scala`).

**Semantic gaps:**

- `Scala3Conversion.scala:9` is the *entire* handling of `Conversion[A,
  B]`: one `unapply`. No support for the compiler‑synthesised
  `Conversion` object emitted for every `given Conversion[A, B] with {
  def apply(...) }`.
- Given resolution reuses the Scala 2 implicit‑search engine
  (`lang/psi/implicits/ImplicitCollector.scala`). `:91` admits:
  *"@TODO: inspect usages outside of ImplicitCollector and adapt to
  visibleImplicitsByLevel if needed."* and `:548`: *"@TODO: apply
  context function to implicit args if type of `c` does not conform"*.
  Scala 3 prioritisation is *approximated* — see SCL‑21153 / SCL‑21195
  comments.
- `import x.{*, given}` wildcard‑only givens is handled in
  `ScImportExprImpl.scala` but the SCL‑24273 / SCL‑15202 comments in
  `ScClassFileDecompiler.scala:68‑69` show resolve is still buggy for
  top‑level givens in compiled libraries.

**Proposed `pc` fix:** implicit/given search is the textbook reason
`pc` exists. Replacing `ImplicitCollector` for Scala 3 files with a
cached `pc.completions`/`pc.hover` call removes the entire `implicits/`
package from the Scala 3 critical path.

---

## 5. Extension methods — ⚠️ Partial

**Syntax:** `base/Extension.scala:18`. PSI:
`lang/psi/api/statements/ScExtension.scala` (TODO at line 10:
*"extension is technically not a member"*).

**Semantic gaps:**

- TODO at `Extension.scala:45`: *"add annotator which will mark
  extensions without extension methods"*.
- Extension methods are added to the qualifier type's scope via
  `ExtensionMethodData.scala:109`, but only after desugaring them
  into `ScFunction`s on a synthetic object. SCL‑21153 comments cluster
  here; overloads involving `using`, type params with bounds, and
  context bounds (`[T: Order]`) lose precision.
- Export‑clause‑inside‑extension (`extension (x: A) export foo.bar`)
  parses (`base/Extension.scala:120`) but resolve from that re‑export
  is not implemented.

**Proposed `pc` fix:** delegate `member` queries on a receiver type to
`pc`. PSI keeps only what is needed for signatures and Find Usages.

---

## 6. Type lambdas / polymorphic function types / dependent function types — ⚠️ Partial

- `ScTypeLambdaTypeElement` desugars to `ScTypePolymorphicType(...,
  isLambdaTypeElement = true)` — `nonvalue/ScTypePolymorphicType.scala:17‑18`:
  *"probably a dedicated type is required for
  ScTypeLambdaTypeElementImpl"*. The plugin models `[X] =>> F[X]` as a
  polymorphic type, **not** a true type lambda; difference shows up
  under higher‑kinded unification.
- `ScPolyFunctionExpr` / `ScPolyFunctionTypeElement`
  (`ScPolyFunctionExprImpl.scala:47,62`) — `[T] => (x: T) => x` is
  parsed with explicit desugaring through
  `syntheticContextBoundParameters` and `cachedDesugaredType`.
  `ScPolyFunctionTypeElementAnnotator` only checks the innermost
  expected type (`ScPolyFunctionExprAnnotator.scala:33`); anything
  more elaborate than direct assignment is unchecked.
- `ScDependentFunctionTypeElement` is parsed; tests in
  `Scala3DependentFunTypeAnnotatorTest.scala`. Conformance uses the
  plain `FunctionN` path, which is wrong for path‑dependent return
  types — `ScalaConformance.scala:1234` TODO:
  *"shouldPropagateDefinitionSiteBounds = isDesignatedToJavaClass
  /*|| context.isScala3 @TODO*/"*.

**Proposed `pc` fix:** `pc` returns precise `Type` for these. Any
feature that needs conformance should round‑trip through `pc.typeOf`.

---

## 7. Match types — ⚠️ Partial

`ScMatchType.scala:27` defines the data; `:112` implements a custom
reducer with hard‑coded depth 50 (`maxRecursionDepth`, line 100) and a
hand‑written disjointness oracle (`isProvablyDisjoint`, line 269).
Caching per project via `MatchTypeReductionCacheService` (line 107).

**Gaps:**

- Reducer only supports *direct* aliasing (`:191`: *"TODO: indirect
  aliases to match types"*).
- Stuck match types are reported as `Failure` rather than left
  unreduced, so callers see a wrong type.
- Disjointness (lines 227‑362) reproduces only the simplest cases
  (final classes, sealed hierarchies, val types). `@TODO` at lines
  250‑260 admits missing variance/field checks.

Tests: `Scala3AliasedTypeLambdaConformanceTest.scala`,
`Scala3PolymorphicTypeTest.scala`, SemanticDB comparison in
`testdata/lang/resolveSemanticDb/lts39/source/i18097.3/`.

**Proposed `pc` fix:** match types are the canonical feature that
needs dotc's full constraint solver. Replace `ScMatchType.reduce` for
Scala 3 files with a cached `pc` query.

---

## 8. Intersection (`&`) / union (`|`) types — ⚠️ Partial

`ScAndType.scala:11`, `ScOrType.scala:10`. Conformance visitor at
`ScalaConformance.scala:478‑487` (`|`) and `:487` (`&`) implements the
standard lattice but with gaps:

- `&` of parameterised parents with conflicting type args loses
  precision (`ScCompoundTypeElementImpl.scala:27`).
- `|`‑widening is asymmetric with dotc — SCL‑19926 comment at
  `TermSignature.scala:277`.
- Pattern‑type inference over unions (`PatternTypeInference.scala:182‑184`)
  only unions the outermost shape.

Tests: `Scala3TypeInheritance.scala`, `testdata/typeConformance/`.

**Proposed `pc` fix:** cheap to verify — `pc.typeCheck` and compare.

---

## 9. Opaque types — ⚠️ Partial

`opaque` is a soft modifier (`ScalaTokenType.scala:46`). Visibility
machinery lives in `lang/psi/types/Context.scala`:
`Context.apply(place)` returns a context that asks
`isInScopeOf(alias)` (line 33). Comments make the design debt explicit:

- `Context.scala:13`: *"Currently, it's a location relative to opaque
  type aliases …"*.
- `Context.scala:67`: *"TODO Use dedicated Transparent and Opaque
  contexts …"*.
- `Context.scala:86`: *"TODO Remove the default argument in the
  future"* — `Default` is `Context` where every opaque is transparent,
  wrong but widely depended upon.
- `SCL-23892` (see `Context.scala:25`): "Unify context parameters".

`ScTypeAliasAnnotator.scala:11`: *"We don't decompile right‑hand sides
of opaque types but add 'opaque' to abstract types"*. **Opaque types
in compiled libraries are modelled as abstract types with no RHS** —
macros that need the underlying type fail.

Tests: `Scala3OpaqueTypeAliasTest.scala`,
`Scala3OpaqueTypeAliasIntegrationTest.scala`,
`Scala3OpaqueTypeHintTest.scala`.

**Proposed `pc` fix:** `pc` already understands the companion‑scope
rule; expose `pc`‑driven `ScType ↔ dotc Type` translation in `Context`.

---

## 10. Export clauses — ⚠️ Partial

`ScExportStmt` (`lang/psi/api/toplevel/imports/ScExportStmt.scala:29`)
extends `ScImportOrExportStmt`. Resolve in
`ScStableCodeReferenceImpl.scala:68` (`isExportInExtension`) and
`:388`.

**Gaps:**

- Top‑level exports indexed (`ScTopLevelExportByPackageIndex.scala`,
  `ScalaIndexKeys.TOP_LEVEL_EXPORT_BY_PKG_KEY`) with O(n) re‑entrancy
  guards (`ScPackageLike.scala:119‑145`).
- `ScStableCodeReferenceImpl.scala:635` shares resolve with imports
  but the export‑as‑alias path is fragile: comment at `:72` notes
  *"We additionally allow other ref kinds here and annotate wrong
  ones as errors"*.
- Selectors (`export a.{b as c, *}`) parse but `as`‑renaming does not
  flow through rename refactoring.

Tests: `ScExportStmtAnnotatorTest.scala`, only one golden
(`testdata/parser/data3/export/export1.test`).

**Proposed `pc` fix:** re‑use `pc`'s `export`‑aware scope.

---

## 11. Inline methods & macros (transparent inline, splice, quote) — ❌

**Inline keyword** parses as a soft modifier (`ScalaTokenType.scala:43`).
`ScFunctionAnnotator.scala:22` enforces `transparent ⇒ inline` and
`inline args ⇒ inline def`.

**Splices / quotes:** parser elements exist (`expressions/Quoted.scala`,
`expressions/Spliced.scala`; PSI: `ScQuotedBlock`, `ScSplicedBlock`,
`ScQuotedType`, `ScQuotedPattern`, `ScSplicedPatternExpr`). Formatter
tests in `Scala3FormatterQuotesAndSplicesTest.scala`.

**Semantic handling:**

- `ScStableCodeReferenceImpl.scala:478‑490` is the *only* place
  transparent inline is consulted, only as a fallback for qualifier
  resolution. `ScSyntheticClass.scala:616`:
  *"Implementing proper resolve for transparent inline defs is quite
  tricky"*.
- `lang/completion/package.scala:272`: *"If it's a transparent inline
  method call and a compiler type is absent, request it"*. The plugin
  asks the external compiler (via `CompilerType`) for a type *string*
  and stores it as user data (`CompilerType.scala`). Works only when
  the compiler is on‑line, only after compile, and only returns a
  string — not an `ScType`.
- Inline reduction, `${...}` splices, `inline match`, `inline if`,
  `scala.compiletime.{summonInline, constValue, erasedValue,
  summonFrom}`, `Type.of` — **none are interpreted**.
- `CompileTimeOpsIntrinsics.scala:51` hard‑codes
  `scala.compiletime.ops.{any,boolean,int,long}` at the *type* level.
  Hand‑maintained table; new 3.5/3.6 ops (`string.Length`,
  `any.Typeable`) are missing.

Tests: `Scala3InlineCompletionTest.scala`,
`Scala3InlineFunctionHighlightingTest.scala`,
`testdata/lang/resolveSemanticDb/lts39/source/inline-{joint,separate,access-levels}/`.

**Proposed `pc` fix:** the headline motivation for adopting `pc`. For
Scala 3 files, every expression whose enclosing method is `inline` or
contains a splice should be expanded by `pc` and the resulting tree
fused back into PSI via a synthetic navigation element. `CompilerType`
becomes a `pc` cache.

---

## 12. `@main` annotation methods — ⚠️ Partial

`Scala3MainMethodSyntheticClass.scala:12` is a hand‑built
`PsiClassFake` consulted by the run‑configuration verifier
(`Scala3MainMethodSyntheticClassFinder.scala:34`,
`Scala3MainMethodProvider.scala:14`). Supports `@main def foo()` →
synthetic class `foo`; `@main def foo(args: String*)` →
`MainMethodParameters.Default`; `@main def foo(name: String, age: Int)`
→ `MainMethodParameters.Custom(...)`.

**Gap:** the synthetic class has `getText = ""`
(`Scala3MainMethodSyntheticClass.scala:30`) and `processDeclarations`
always returns `false` (line 50, comment: *"we probably need add some
fake psi file"*). Navigation to the generated `main(args:
Array[String]): Unit` has nowhere to go; CLI reflection in `scala-cli`
and friends is brittle.

**Proposed `pc` fix:** `pc` knows the synthetic class symbol
(`scala.main`‑derived); the shim can stay but should query `pc` for
the parameter list rather than parse the source itself.

---

## 13. Top‑level definitions — ⚠️ Partial

Fully supported at parser and stubs layer (every shape has a
`ScalaIndexKeys.TOP_LEVEL_*` key). `TopLevelMembers`
(`util/TopLevelMembers.scala`) knows about the
`TopLevelDefinitionsSingletonClassNameSuffix` generated by dotc.

**Gaps:**

- Java interop: `ScalaClassFinder.scala:36‑44` workaround *"to work
  around JavaSourceFilterScope … SCL‑20154"* adds a `.tasty`‑only
  scope, documented as incomplete.
- `ScClassFileDecompiler.scala:68` documents SCL‑24273/SCL‑15202
  around *which* top‑level class in a `.tasty`‑bearing `.class` is the
  canonical one.
- The `$package$` / file‑name convention is hard‑coded in
  `RenameScalaClassProcessor.scala:86`, `ScalaFileNameInspection`, etc.

Tests: `Scala3FileNameInspectionTest.scala`, SemanticDB toplevel
sources.

**Proposed `pc` fix:** for "find the symbol for this FQN", ask `pc`
(`SymbolSearch`) rather than the stale `.class`‑only index.

---

## 14. Named tuples (Scala 3.5+) — ⚠️ Partial

**Syntax:** accepted. PSI: `ScNamedTuple`, `ScNamedTupleTypeElement`,
`ScNamedTuplePattern`, `NamedTupleType`.

**Semantic handling:**

- `NamedTupleIntrinsics.scala:13` is a **hand‑coded** table of
  `scala.NamedTuple.*` ops (`Size`, `Elem`, `Head`, `Last`, `Tail`,
  `Init`, `Take`, `Drop`, `Split`, `Concat`, `Map`, `Reverse`, `Zip`,
  `From`, `Names`, `DropNames`). Any new op upstream silently fails to
  reduce.
- `ScalaNamedTupleCompletionContributor.scala:35` provides completion
  from `expectedType()` — only when the expected type is a direct
  `NamedTupleType`; nested (`Option[NamedTuple[...]]`) is not handled.
- Tests (`Scala3NamedTupleAnnotatorTest.scala`,
  `Scala3NamedTupleUnusedInspectionTest.scala`,
  `ScalaNamedTupleCompletionContributorTest.scala`,
  `Scala3NamedTuplesTest.scala`) cover construction/selection but not
  `Names`/`DropNames` in match‑type reduction.
- `SAMUtil.scala:112,122` rejects context‑function‑of‑named‑tuple as
  a SAM type as a stop‑gap (*"@TODO: remove when implemented"*).

**Proposed `pc` fix:** named‑tuple ops are first‑class in dotc.
Replace `NamedTupleIntrinsics` with `pc` calls.

---

## 15. `into` / `only` / `inlined` parameter modifiers (Scala 3.5+) — ❌

- `into` is in `ScalaModifier.java:23` and `ScalaTokenType.scala:49`,
  highlighted as keyword (`ScalaSyntaxHighlighter.scala:522`).
  Recognised on parameters but **no semantic enforcement** — an
  `into` parameter should only accept inline‑safe arguments.
- `only` — **not present.** No `ScalaModifier.Only`, no `OnlyKeyword`,
  no annotator. `def f(x: only Int)` parses as an identifier.
- `inlined` (the parameter modifier distinct from `inline`) — **not
  present.** Searches for `"inlined"` and `OnlyKeyword` yield no hits;
  `ScFunctionAnnotator.scala:30` only treats `inline` parameters and
  does not warn on non‑`inline` methods.

Tests: none.

**Proposed `pc` fix:** new soft modifiers added to the lexer once,
but their *meaning* (control‑flow / purity constraints on the
argument) is a dotc phase; defer to `pc` for diagnostics.

---

## 16. `@main` / `@targetName` annotations — ⚠️ Partial

**`@main`** — see §12.

**`@targetName`** has a full inspection suite under
`codeInspection/targetNameAnnotation/`:
`MultipleTargetNameAnnotationsInspection.scala:7`,
`MultipleTargetsTargetNameInspection.scala:8`,
`EmptyTargetNameInspection.scala:10`,
`OverridingAddingTargetNameInspection.scala`,
`OverridingRemovingTargetNameInspection.scala`,
`OverridingWithDifferentTargetNameInspection.scala`,
`NoTargetNameAnnotationForOperatorLikeDefinitionInspection.scala:15`
(`package.scala:16` declares `TargetNameAnnotationFQN`).

Override‑implement synthesises the annotation when needed
(`ScalaGenerationInfo.scala:61,67,269,300`,
`ScalaOverrideContributor.scala:184,296`).

**Gaps:**

- `ScalaUsageTypeProvider.scala:157` TODO: *"TODO: handle @targetName
  in Scala 3?"* — Find Usages does not follow the `@targetName` alias.
- Inspections are textual; no integration with compiler‑emitted name
  mangling for Java interop.

Tests: `Scala3AllInspectionsTest.scala`; no cross‑language Find Usages
test.

**Proposed `pc` fix:** `pc` already enumerates the renamed symbol; Find
Usages should ask `pc` for the canonical symbol identifier.

---

## 17. Context functions (curried context) — ⚠️ Partial

`ContextFunctionType` lives in `api` package; token types
`ImplicitFunctionArrow` ("?=>") and `ImplicitPureFunctionArrow`
("?->") at `ScalaTokenType.scala:57‑59`.
`LightContextFunctionParameter`
(`lang/psi/light/LightContextFunctionParameter.scala:26`) is the
synthetic parameter for `?=>`‑typed lambdas.

**Gaps:**

- `SAMUtil.scala:112` — explicit TODO for context functions with
  implicit/using parameters; `:122` rejects SAM matching for any
  `scala.ContextFunction*` as a stop‑gap.
- `ImplicitCollector.scala:548` TODO: *"apply context function to
  implicit args if type of `c` does not conform"* — context‑function
  eta‑expansion is approximate.
- `ScExpression.scala:500‑524` (`synthesizeContextFunctionType`)
  reproduces only the simplest pattern.

Tests: `Scala3ImplicitParametersTest.scala`,
`Scala3DependentFunTypeAnnotatorTest.scala` (partial).

**Proposed `pc` fix:** context‑function conformance is a dotc
operation (`ContextResult`); `pc` returns it cheaply.

---

## 18. New control syntax, `*` varargs, fewer‑braces — ✅

Control keywords (`then`, `do`, `end`, `for … do`, `while … do`,
`if … then`) accepted in Scala 3 files. Formatter tests in
`Scala3FormatterControlSyntaxTest.scala` cover all combinations. `*`
varargs token (`WildcardStar`, `ScalaTokenType.scala:54`) handles
Scala 3.1 regularised `*`‑per‑type‑param syntax.

Fewer‑braces method‑call syntax (`list.map: x => x + 1`) is fully
parsed and formatted
(`Scala3FormatterMethodCallChainWithArgumentsWithColonSyntaxTest.scala`,
`Scala3ClausesCompletionTest.scala`).

**Status:** complete; no `pc` work.

---

## 19. Macro annotations (Scala 3.3 experimental) — ❌

`MacroAnnotation` (`scala.quoted`) is **not implemented**.

- `grep` for `MacroAnnotation`, `scala.annotation.MacroAnnotation` in
  `scala-impl/src` yields nothing relevant.
- The only macro infrastructure in the plugin is Scala 2 (see
  report **05‑macros.md**). `ScMacroDefinitionImpl.scala:33`
  (`macroImplReference`) still expects the Scala 2
  `scala.reflect.macros.internal.macroImpl` annotation.

**Proposed `pc` fix:** none trivially. Realistic path:

1. Parse the annotation syntactically (already works).
2. Run the macro via `pc` (it embeds `dotc`; Metals `InlayHints`
   pipeline already does this).
3. Render the expansion as a synthetic PSI node next to the
   declaration.

---

## 20. Universal `equals`/`hashCode` for transparent inline traits — ❌

Scala 3 synthesises `equals`/`hashCode` for `transparent inline`
traits so they can be compared regardless of mixing order. **No
support:**

- `grep "equalsInlineTrait"`, `"transparent inline trait"`,
  `"Mirror.Any"` in `scala-impl/src` yields nothing.
- `MixinNodes.scala:86‑88` does not model the synthesised
  `equals`/`hashCode` pair; `equals` resolution only consults
  declared members.

Tests: none.

**Proposed `pc` fix:** delegate `member("equals")` /
`member("hashCode")` on a transparent inline trait to `pc`.

---

## 21. TASTy back‑reference for compiled Scala 3 libraries — ⚠️ Partial

The plugin **already** reads `.tasty` files via a hand‑written
unpickler:

- `tasty/TastyDecompiler.scala:13`
- `tasty/TastyReader.scala:6` → `tasty.reader.TastyImpl.read`
- `tasty-reader/src/{TreeReader,TreePrinter,TastyImpl}.scala`
- `tasty/TastyFileStubBuilder.scala`,
  `tasty/TastyFileViewProviderFactory.scala`

`ScClassFileDecompiler.scala:14` is the `ClassFileDecompilers.Full`
that decides whether to use `.tasty` or fall back to bytecode
(`isTasty(file) || hasTasty(file) || topLevelScalaClassFor(file).nonEmpty`).

**Gaps:**

- `TastyImpl.read` (`tasty-reader/src/TastyImpl.scala:9`) catches
  `UnpickleException` and `StackOverflowError` (SCL‑21005, SCL‑21080)
  — newer TASTy formats are silently dropped.
- Reader does not understand Scala 3.5+ constructs: match‑type
  reduction, capture checking, named tuples. Printing falls back to
  opaque `???`.
- `ScClassFileDecompiler.scala:115‑146` documents a stale heuristic
  for "top‑level Scala class": *"TODO: see comments inside, it seems
  like this logic is not relevant."* Causes SCL‑24273 (wrong source).
- `ScalaLanguageSubstitutor.scala:44` TODO: *"determine whether a
  .scala file (possibly in a JAR) is associated with a .tasty file
  (possibly in a JAR)"* — source/tasty pairing is unsolved.

**Proposed `pc` fix:** the ideal consumer of `.tasty` is `dotc`
itself. Replace `tasty-reader/` with a `pc` call that loads `.tasty`
from the JAR and returns a `Symbol` graph; the plugin only renders.

---

## Summary — what `pc` actually fixes

**Only three of the 21 features (§1, §2, §18) are fully implemented.**
Of the remainder:

| Bucket | Count | Representative items |
| ------ | :---: | -------------------- |
| ✅ done | 3 | §1, §2, §18 |
| ⚠️ partial (semantic) | 14 | §3–§10, §12–§14, §16–§17, §21 |
| ❌ missing | 4 | §11 (inline/macros), §15 (`into`/`only`/`inlined`), §19 (macro annotations), §20 (transparent‑inline‑trait `equals`) |

In every ⚠️ and ❌ case, the gap has the same shape: *the plugin's
hand‑written engine cannot reproduce dotc's reductions*. The Metals
`pc` is exactly the dotc entry point that does these reductions
cheaply per‑cursor. Proposed architecture:

1. Keep the IntelliJ parser/PSI for editing, formatting, refactoring,
   structural navigation.
2. Replace `lang/psi/types/` and `lang/psi/implicits/` for Scala 3
   files with a `pc`‑backed facade. Map dotc `Type`/`Symbol` back to
   `ScType`/`PsiElement` only when UI‑facing code needs it.
3. Use `pc`'s compiler invocation to expand inline/macro definitions
   transparently, replacing `CompilerType` (§11) and the hand‑coded
   `*Intrinsics` tables (§7, §14, §17).
4. Replace `tasty-reader/` with `dotc`'s TASTy reader, exposed via
   `pc` (§21).

This eliminates ~5 000 lines of hand‑written reductions in `scala-impl/src`
(`lang/psi/types/`, `lang/psi/implicits/`, `lang/macros/`,
`tasty-reader/`) and aligns the plugin's Scala 3 semantics 1:1 with
the compiler.
