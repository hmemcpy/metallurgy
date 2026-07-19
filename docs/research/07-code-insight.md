# 07 — Code Insight (completion, refactorings, navigation, hints, indices, editor mechanics)

> Metals-`pc` redesign of Scala 3. Cross-refs: `01-psi-parser-lexer.md`,
> `02-type-system-resolve.md`, `10-extension-points.md`.

The plugin's "code insight" surface is large but unevenly coupled to its PSI/resolve.
Triage buckets:

* **(A) `pc`-backed semantic** — replace PSI/resolve machinery with a `pc` query.
* **(B) IntelliJ-syntactic — STAYS** — purely PSI/text.
* **(C) Hybrid** — IntelliJ drives; uses `pc` for resolve/type/references.
* **(D) Deprecated for Scala 3** — drop or hide.

---

## 1. Completion

### 1.1 Registered contributors

`scala-plugin-common.xml` declares **25 `completion.contributor`s**
(`scala-plugin-common.xml:369-432`). Effective ordering (first → last),
reconstructed from the `order=` attributes:

| # | id / class | priority |
|---|---|---|
| 1 | `ScalaLiteralTypeValuesCompletionContributor` | `first` (`:417`) |
| 2 | `ScalaNamedTupleCompletionContributor` | `before sameSignatureCallParametersProvider` (`:420`) |
| 3 | `SameSignatureCallParametersProvider` | `before keywordCompletionContributor, before scalaClassParamContributor` (`:401`) |
| 4 | `ScalaTypeAnnotationsCompletionContributor` | `before keywordCompletionContributor` (`:414`) |
| 5 | `ScalaEndMarkerCompletionContributor` | `before keywordCompletionContributor` (`:411`) |
| 6 | `CaseClassParametersCompletionContributor` | `before keywordCompletionContributor` (`:384`) |
| 7 | `ScalaKeywordCompletionContributor` | `before caseClauseCompletionContributor` (`:369`) |
| 8 | `ScalaOverrideContributor` | `before scalaIdentifierCompletionContributor, after keywordCompletionContributor` (`:393`) |
| 9 | `CaseClauseCompletionContributor` | `before scalaIdentifierCompletionContributor` (`:387`) |
| 10 | `ExhaustiveMatchCompletionContributor` | `before scalaIdentifierCompletionContributor` (`:390`) |
| 11 | `ScalaUnresolvedNameContributor` | `before scalaIdentifierCompletionContributor` (`:398`) |
| 12 | `ScalaAotCompletionContributor` | `before scalaCompletionContrubutor` (`:381`) |
| 13 | `ScalaBasicCompletionContributor` | (`:396`) |
| 14 | `ScalaSmartCompletionContributor` | (`:374`) |
| 15 | `ScalaDumbAwareCompletionContributor` | (`:372`) |
| 16 | `ScalaAfterNewCompletionContributor` | (`:375`) |
| 17 | `ScalaMemberNameCompletionContributor` | (`:377`) |
| 18 | `ScalaGlobalMembersCompletionContributor` | `before scalaClassNameCompletionContributor` (`:407`) |
| 19 | `ScalaPrefixPackageCompletionContributor` | `before scalaClassNameCompletionContributor` (`:409`) |
| 20 | `ScalaClassNameCompletionContributor` | `after scalaCompletionContrubutor` — last Scala contributor (`:404`) |
| 21–24 | `scalaDirective*` (ScalaDirective language) | (`:424-432`) |

### 1.2 Auxiliary EPs

* **Weighers** (`:442-455`): `ScalaContainingClassWeigher`,
  `ScalaMethodCompletionWeigher`, `ScalaByNameWeigher`,
  `ScalaKindCompletionWeigher`, `ScalaScopeWeigher`,
  `ScalaClassObjectWeigher`, `ScalaExplicitlyImportedWeigher` (in
  `lang/completion/weighter/`).
* **Statisticians** (`:437-440`): `ScalaProximityStatistician`,
  `ScalaCompletionStatistician`.
* **ML feature providers** (`:456-459`): `ScalaContextFeatureProvider`,
  `ScalaElementFeatureProvider`.
* **Postfix templates** (`:613-614`): `ScalaPostfixTemplateProvider` — **22
  templates** under `lang/completion/postfix/templates/`.
* **Clause completers** (`lang/completion/clauses/`):
  `CaseClassParametersCompletionContributor`, `CaseClauseCompletionContributor`,
  `ExhaustiveMatchCompletionContributor` — resolve-dependent.
* **Command completion** (`scala-plugin-common.xml:269-337`): 19 providers
  under `lang/completion/command/`.
* **completion.confidence** (`:434-435`): `ScalaCompletionConfidence`,
  `SkipAutopopupInStrings`.

### 1.3 What `pc.completionsAt(uri, pos, query)` replaces

The current semantic contributors funnel through `ScalaBasicCompletionProvider`
(`lang/completion/ScalaBasicCompletionProvider.scala:30`, uses `CompletionProcessor`),
`ScalaGlobalMembersCompletionContributor`, `ScalaClassNameCompletionContributor`,
`ScalaOverrideContributor`, and `ScalaUnresolvedNameContributor`. All of these
today do, at heart:

1. Walk the resolve scope via stub indices (`ScShortClassNameIndex`, etc.).
2. Filter by `expectedType` / `Typeable` / `applicability`.
3. Wrap resolved `PsiElement`s into `ScalaLookupItem`s.

`pc.completionsAt` returns a `CompletionList` of `PcCompletionItem` (each
carrying a `PcSymbol` + `PcType` + pos + kind), so it directly replaces (1)
and (2). The wrapping in (3) becomes `ScalaLookupItem.fromPcSymbol(sym, …)`.

### 1.4 What's **missing** from `pc` today

| Capability | Status | Disposition |
|---|---|---|
| Keyword completion (`ScalaKeywordCompletionContributor`) | Not in `pc` | **STAYS** — pure lexer-state lookup |
| Live-template / postfix templates | Not in `pc` | **STAYS** |
| End-marker / type-annotation / directive completion | Not in `pc` | **STAYS** |
| Case-clause / exhaustive-match / case-class-params (`clauses/`) | Partial (`pc` exposes `synthetics` for `copy`/`unapply`, enum cases) | **Hybrid** |
| Override/Implement (`ScalaOverrideContributor`) | `pc` has `overrideMembers` | **Hybrid** |
| Smart (`ScalaSmartCompletionContributor`) | `pc.completionsAt` already filters by expected type | **Replace** (semantic part) |
| ML / weighers / statisticians | N/A — UX layer | **STAYS** |

**Proposed split**: a single new `ScalaPcCompletionContributor`
(registered `order=before scalaClassNameCompletionContributor`) becomes the only
semantic contributor for Scala 3. It calls `pc.completionsAt`, converts items to
`ScalaLookupItem`s, and exposes them to the existing
weigher/statistician/ML pipeline unchanged. Syntactic contributors (keyword,
end-marker, postfix, live-template, scaladoc, directive, clause template
insertion) **stay**.

---

## 2. Refactorings

### 2.1 Inventory (`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/refactoring/`)

| Sub-package | File:line | Syntactic / Semantic |
|---|---|---|
| `extractMethod/` | `ScalaExtractMethodHandler.scala:39` | **Syntactic** (AST + scope lookup) |
| `extractTrait/` | `ScalaExtractTraitHandler.scala` | **Semantic** — must check overrides |
| `inline/{method,typeAlias,variable}/` | `ScalaInlineMethodHandler.scala` etc.; 3 EPs at `:528-530` | **Semantic** — must resolve every call site |
| `introduceVariable/`, `introduceField/`, `introduceParameter/` | dialogs + inplace renamers | Mostly **syntactic**; `introduceParameter` is **hybrid** |
| `memberPullUp/` | `ScalaPullUpProcessor.scala` | **Semantic** |
| `changeSignature/` | `ScalaChangeSignatureHandler.scala`; EPs at `:532-533` | **Semantic** — all call sites need rewriting |
| `move/members/`, `move/anonymousToInner/`, `ScalaMoveFilesOrDirectoriesHandler`, `ScalaMoveClassesOrPackagesHandler` | 4 `refactoring.moveHandler` EPs (`:96-99`) | **Hybrid** |
| `move/MoveScalaClassHandler`, `ScalaMoveDirectoryWithClassesHelper`, `MoveScalaFileHandler` | `:524-527` | **Semantic** |
| `delete/ScalaSafeDeleteProcessorDelegate` | `:531` | **Semantic** |
| `rename/` (16 files) | `:501-519` | **Semantic** |
| `copy/CopyScalaWorksheetHandler` | `:102` | **Syntactic** (worksheet only) |
| `namesSuggester/` (incl. 6 `genericTypeNamesProvider` EPs, `:46-57`) | | **Hybrid** — uses `ScType` |
| `suggested/ScalaSuggestedRefactoringSupport.scala:12` | `:541` | see §2.3 |

### 2.2 Refactoring-related EPs registered

* `refactoring.moveMemberHandler` (`:95`), `refactoring.moveHandler` ×4 (`:96-99`),
  `refactoring.moveClassHandler` (`:524`), `refactoring.moveDirectoryWithClassesHelper`
  (`:525`), `moveFileHandler` (`:527`), `refactoring.moveAllClassesInFileHandler` (`:100`).
* `refactoring.copyHandler` (`:102`).
* `renamePsiElementProcessor` ×8 (`:504-512`); `automaticRenamerFactory` ×3
  (`:501-503`); `renameHandler` ×3 (`:513-515`); `vetoRenameCondition` (`:516`);
  `nameSuggestionProvider` (`:517-519`).
* `inlineActionHandler` ×3 (`:528-530`). `refactoring.safeDeleteProcessor` (`:531`).
* `refactoring.changeSignatureUsageProcessor` ×2 (`:532-533`); `refactoring.helper` ×3
  (`:534`, `:537`, `:539`).
* `refactoring.elementListenerProvider` (`:526`); `lang.refactoringSupport` (`:218`)
  — `ScalaRefactoringSupportProvider`; `suggestedRefactoringSupport` (`:541`);
  `treeCopyHandler` (`:109`); `statementUpDownMover` (`:104`).

### 2.3 Syntactic vs semantic split

**Purely syntactic** (no resolve; **STAYS**): Extract Method (`ScalaExtractMethodHandler.scala:39`),
Extract Variable/Field (basic), Surround With (`lang/surroundWith/descriptors/`),
Statement up/down mover (`ScalaStatementMover`), Copy worksheet
(`CopyScalaWorksheetHandler`).

**Semantic** (need resolve — **Hybrid: use `pc.findReferences(symbol)` instead
of PSI `referencesSearch`**):

* **Rename** — `RenameScalaMethodProcessor.scala`, `RenameScalaClassProcessor.scala`,
  `RenameScalaVariableProcessor.scala`, etc., today rely on
  `ScalaRenameUtil` → `ReferencesSearch.search`. The `automaticRenamerFactory`
  chain (`AutomaticOverloadsRenamer`, `AutomaticParameterRenamer`,
  `AutomaticVariableRenamer`) extends the rename set by walking overloads and
  parameter positions. **Proposal**: replace `ReferencesSearch` with
  `ExternalReferenceSearcher` backed by `pc` (see §3) — same `UsageInfo` shape,
  far fewer false-positives, instant for library symbols.
* **Move members / pull up / push down** — `ScalaMoveMemberHandler`,
  `ScalaPullUpProcessor`. Build the conflict set via `pc.overrides(symbol)`
  rather than `ScalaOverridingMemberSearcher.scala:48`.
* **Inline method/val/type** — `ScalaInlineMethodProcessor` walks every call
  site. Use `pc.findReferences` to enumerate call sites, then AST-edit locally.
* **Change signature** — `ScalaChangeSignatureUsageProcessor`. Use `pc`'s view
  of the method symbol (params, defaults, override hierarchy) to drive conflict
  detection.
* **Safe delete** — `ScalaSafeDeleteProcessorDelegate`. Replace usage search
  with `pc.findReferences`.

### 2.4 `SuggestedRefactoring` framework

`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/refactoring/suggested/ScalaSuggestedRefactoringSupport.scala:12`
implements IntelliJ's `SuggestedRefactoringSupport` EP (`scala-plugin-common.xml:541`).
The platform uses it for the in-editor "press Shift+F6 to rename" hint and for
the rename preview. Today only **rename** is implemented — see the four
`TODO(SCL-17973)` markers at `:13,18,25,29` asking for Change Signature. The
class returns `nameRange`, `signatureRange`, `importsRange` so the platform
knows where to draw the active-edit region. This is **syntactic glue** and
**stays**; only `getStateChanges`/`getAvailability` need upgrading if Change
Signature is added.

---

## 3. Navigation, find-usages, searchers

### 3.1 EP inventory

| EP | Implementation | xml line |
|---|---|---|
| `gotoDeclarationHandler` / `codeInsight.gotoSuper` | `ScalaGoToDeclarationHandler` / `ScalaGoToSuperActionHandler` | `:497` / `:496` |
| `directClassInheritorsSearch` / `classInheritorsSearch` | `ScalaDirectClassInheritorsSearcher` / `ScalaLocalInheritorsSearcher` | `:490` / `:491` |
| `overridingMethodsSearch` ×2 / `allOverridingMethodsSearch` | `ScalaOverridingMemberSearcher`, `JavaRawOverridingSearcher` / `JavaRawAllOverridingSearcher` | `:521-523` |
| `definitionsSearch` / `annotatedElementsSearch` | `MethodImplementationsSearch` / `ScalaAnnotatedMembersSearcher` | `:520` / `:587` |
| `referencesSearch` ×13 | NamingParams, ObjectTrait, Apply, Unapply, JavaVals, JavaFunction, Setter, AliasedImported, TypeAlias, Package, ApplyUnapplyForBinding, OperatorAndBackticked, SelfInvocation | `:588-600` |
| `methodReferencesSearch` / `customUsageSearcher` | `NonMemberMethodUsagesSearcher` / `ExtractorParamsInExtractorPatternSearcher` | `:602` / `:604` |
| UX wrappers | `findUsagesHandlerFactory`, `usageTypeProvider`, `findUsagesProvider`, `importFilteringRule`, `fileStructureGroupRuleProvider` ×2 | `:465-474` |
| Goto / targets | `gotoClassContributor`, `gotoSymbolContributor`, `qualifiedNameProvider`, `targetElementEvaluator` (Scala+Java), `typeDeclarationProvider` | `:550`, `:605-607`, `:634-637` |

### 3.2 External-search EPs (`org.intellij.scala.findUsages.*`)

Declared at `scala-plugin-common.xml:25-27`, implemented in
`scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/references/search/`:

* `externalReferenceSearcher` ← `CompilerIndicesReferencesSearch$`
  (`scalaCommunity.compiler-integration.xml:69`) — already a precedent for
  handing find-usages to an external index.
* `externalInheritorsSearcher` ← `CompilerIndicesInheritorsSearch$` (`:70`).
* `externalSearchScopeChecker` ← `CompilerIndicesReferencesSearcher$` (`:71`) —
  tells the platform "the external index has full coverage, you can skip the
  in-PSI scan".

`CompilerIndicesFindUsagesHandler`
(`scala/scala-impl/src/org/jetbrains/plugins/scala/findUsages/factory/CompilerIndicesFindUsagesHandler.scala:40-66`)
shows the pattern: when the target is in a library, it delegates to
`ExternalReferenceSearcher.searchExternally(e)`; for source, falls back to PSI
searchers.

### 3.3 How `pc`/SemanticDB beats PSI traversal

Today `referencesSearch` is **13 separate PSI walkers** because Scala has many
ways to spell a reference: `foo`, `this.foo`, `Foo.foo`, `Foo apply x`,
`Foo(x)` (apply), `case Foo(x)` (unapply), imports, aliases, setters (`foo_=`),
backticks, infix operators. Each searcher encodes one spelling rule and they
are layered. `pc`/`dotc` already knows all of these — it produced the symbol
reference in the first place.

**Proposal**: for Scala 3 source files, replace
`CompilerIndicesReferencesSearch` (which consumes SemanticDB emitted by the JPS
compile server) with an in-process `pc` query
(`pc.findReferences(symbol, includeExternal = true)`). This:

* Returns references in *un-saved* files (current indexer requires compile).
* Handles extension methods, given imports, type classes — all the cases where
  the 13 hand-written searchers underperform.
* Speeds library find-usages dramatically: today's PSI scan walks every JAR
  class; `pc` semanticdb is pre-indexed.

The `ExternalSearchScopeChecker` EP (`:27`) lets us tell the platform "trust
the external result, skip PSI traversal" — already wired.

`ScalaOverridingMemberSearcher.scala:48` and
`ScalaDirectClassInheritorsSearcher.scala:22` likewise map to
`pc.overrides(symbol)` and `pc.subclasses(symbol)`.

---

## 4. Inspections and intentions

### 4.1 Counts

* **`localInspection` entries** in `scala-plugin-common.xml`: **173**.
* **`intentionAction` entries** in `scala-plugin-common.xml`: **70**.
* **`localInspection` + `intentionAction`** in `codeInsight.xml`: **67**.
* Total ≈ **92** `LocalInspectionTool` subclasses; ≈ **137** total intentions
  (including fix-attached ones).

### 4.2 Inspection categories (`scala/scala-impl/src/org/jetbrains/plugins/scala/codeInspection/`)

| Bucket | Sub-packages (selected) |
|---|---|
| **Syntactic — STAYS** | `booleans/`, `parentheses/`, `redundantBlock/`, `format/`, `literal/`, `forwardReferenceInspection/`, `internal/`, `syntacticClarification/`, `syntacticSimplification/`, `packageNameInspection/`, `scaladoc/`, `xml/`, `modifiers/`, `redundantClassParamClause/`, `postfix/`, `prefixMutableCollections/`, `valInTraitInspection/`, `varCouldBeValInspection/` |
| **Semantic — candidate (D)** | `cast/`, `collections/`, `comparingUnrelatedTypes`, `implicits/`, `declarationRedundancy/`, `dfa/`, `functionExpressions/`, `methodSignature/`, `parameters/`, `resourceLeaks/`, `SAM/`, `shadow/`, `source3/`, `typeChecking/`, `typeAnnotation/`, `caseClassParamInspection/`, `catchAll/`, `controlFlow/`, `delayedInit/`, `feature/`, `infiniteCycle/`, `monads/`, `notImplementedCode/`, `annotations/`, `deprecation/`, `imports/`, `relativeImports/`, `targetNameAnnotation/` |
| Tooling / UX | `scalastyle/`, `suppression/`, `ui/` |

### 4.3 Migrating to `pc`/LSP diagnostics

`pc.diagnose(path, pos)` returns `PcDiagnostic`s — real compiler diagnostics.
Semantic inspections whose **only purpose is to mirror compiler warnings** should
be retired in favour of `pc` diagnostics routed through IntelliJ's
`ExternalAnnotator`. Examples:

* `ScalaDeprecationInspection`, `Scala3DeprecatedPackageObjectInspection`,
  `Scala3DeprecatedAlphanumericInfixCallInspection`.
* `TypeAnnotationInspection`, `AbstractValueInTraitInspection`,
  `NotImplementedCodeInspection`.
* `ScalaRedundantCastInspection`, `ScalaRedundantConversionInspection`,
  `ComparingUnrelatedTypesInspection`.
* `PrivateShadowInspection`, `TypeParameterShadowInspection`,
  `VariablePatternShadowInspection`.
* The `targetNameAnnotation/` family (`Overriding*TargetNameInspection`,
  `MultipleTargetsTargetNameInspection`, etc.).
* `ScalaUnusedDeclarationInspection`, `ScalaAccessCanBeTightenedInspection`
  (today driven by 4 dedicated `highlightingPassFactory`s at `:178-181`).

**Stays**: the entire syntactic taxonomy (booleans, parentheses, redundantBlock,
format, etc.) — these are *style* inspections the compiler will never emit.

### 4.4 Intentions

70 + 67 ≈ **137 registered intentions**, organised under
`codeInsight/intention/{argument, booleans, collections, comprehension,
controlFlow, declarations, expression, imports, lists, matcher, recursion,
stringLiteral, types}` plus fix-attached intentions in `scala-impl/codeInspection/`.
Notable families:

* **Types** (`intention/types/`): `ToggleTypeAnnotation`,
  `AddUnitTypeAnnotationIntention`, `RegenerateTypeAnnotation`,
  `MakeTypeMoreSpecificIntention`, `ConvertToInfixIntention`,
  `ConvertFromInfixIntention`, `AdjustTypesIntention`,
  `ConvertImplicitBoundsToImplicitParameter` — all read `ScType`; become
  **Hybrid** via `pc.typeAt`.
* **Argument conversions / Boolean algebra / Strings / Formatted / Control flow /
  Comprehension** — **syntactic**, STAYS.
* **Pattern matching** (`intention/matcher/`) — **Hybrid**.
* **Imports** (`intention/imports/`) — driven by `autoImport/` (§4.5).
* **Recursion** (`AddTailRecursionAnnotationIntention`) — **Hybrid**.

### 4.5 `unresolvedReferenceFixProvider` and auto-import

EP at `scala-plugin-common.xml:15`. Three providers (`:59-61`):
`ScalaImportTypeFix$Provider` (`autoImport/quickFix/ScalaImportTypeFix.scala:72`),
`ImportImplicitConversionFixes$Provider`, `ScalaImportGlobalMemberFix$Provider`.

These drive the auto-import popup and the red-squiggle → Import… quick-fix.
Today, they look up candidates via stub indices (`ScAllClassNamesIndex`,
`ScShortClassNameIndex`, `ImplicitConversionIndex`, `ImplicitInstanceIndex`,
`ExtensionIndex`, `ScGivenIndex`). For Scala 3, `pc.completionsAt(uri, pos, "")`
returns the importable candidates directly. The three providers become a single
**`PcUnresolvedReferenceFixProvider`** that wraps `pc` results into
`ElementToImport` (`autoImport/quickFix/ElementToImport.scala`).

---

## 5. Inlay hints, parameter info, documentation

### 5.1 Parameter info

* `codeInsight.parameterInfo` ×3 (`scala-plugin-common.xml:609-611`):
  `ScalaPatternParameterInfoHandler`, `ScalaFunctionParameterInfoHandler`,
  `ScalaTypeParameterInfoHandler` (in `lang/parameterInfo/`).
* EP `parameterInfoEnhancer` (`scala-plugin-common.xml:12`,
  `ScalaParameterInfoEnhancer.scala`) — lets extensions expand a signature
  (synthetic/implicit conversions).

`pc.signatureHelp(uri, pos)` returns the parameter index + list — **replace**
the three handlers with one `PcParameterInfoHandler`. The
`ScalaParameterInfoEnhancer` EP can stay as an extension seam.

### 5.2 Inlay hints

No `codeInsight.inlayProvider` EPs declared — Scala uses the legacy
`InlayParameterHintsProvider` (`ScalaInlayParameterHintsProvider.scala:11`, in
`scala/codeInsight/`) wired in code, plus a set of
**`TextEditorHighlightingPassFactory`** implementations:

* `ImplicitHintsPassFactory` (`codeInsight.xml:15`) → `ImplicitHintsPass`
  (implicit conversions + arguments).
* `ScalaInlayParameterHintsPass` (`ScalaInlayParameterHintsPass.scala:32`) —
  parameter-name hints.
* `ScalaTypeHintsPass` (`ScalaTypeHintsPass.scala:39`) — inferred type hints.
* `ScalaApplyMethodHintsPass`, `ScalaTypeArgumentHintsPass`,
  `ScalaMethodChainInlayHintsPass` (aligned/unaligned),
  `RangeInlayHintsPass` (`rangeHints/`).

Annotator-side renderers live in `scala/scala-impl/src/org/jetbrains/plugins/scala/annotator/hints/`
(`Hint.scala`, `Corners.scala`, `Text.scala`, `AnnotatorHints.scala`); settings
in `codeInsight/hints/`. `TypeMismatchHints.scala:26` ties into the type-mismatch
annotator.

### 5.3 What `pc` provides

`pc` exposes through `PcSymbolInfo` and `infoAt(path, pos)`:

* Inferred type for val/def — replaces `ScalaTypeHintsPass` engine.
* Implicit conversion + implicit args — replaces `ImplicitHintsPass`.
* Parameter name (for non-named args) — replaces `ScalaInlayParameterHintsPass`.
* Type-argument inference — replaces `ScalaTypeArgumentHintsPass`.

**Migration**: keep the IntelliJ renderer stack (`Hint`/`Corners`/`Text`/popup
UI); replace only the resolve layer. The `Typeable.type()` calls inside
`ScalaTypeHintsPass.scala:40` (and friends) become `pc.typeAt(place)`.
Method-chain alignment and range hints (`0 until 10` → `..< 10`) are
**Syntactic / STAYS**.

### 5.4 Documentation

`ScalaDocumentationProvider` (`scala-plugin-common.xml:498`) and
`ScalaPsiDocumentationTargetProvider` (`:500`, `order=first`) produce quick-doc.
They render `ScalaDocContentGenerator` output from `ScDocComment`.
**`pc.hover(uri, pos)` returns the docstring + signature as Markdown**; route
the target provider through `pc.hover` for Scala 3 *library* symbols (no PSI
available), keep the rich local-PSI renderer for source symbols.
`ElementRenderer.scala` / `ScalaDocGenerator.scala` pipeline **stays**.

### 5.5 Type info via TASTy

`pc.typeAt(uri, pos)` is the entry point; for library symbols without source,
`pc` reads TASTy (see `06-tastyreader.md`). Covers the gap left by
`PcBackedScType` (see `02-type-system-resolve.md:453`) when there's no source
PSI to bind to.

---

## 6. Indices

### 6.1 Stub indices registered (`scala-plugin-common.xml:654-687`)

**35 stub indices** under `lang/psi/stubs/index/`. By purpose:

* **Class lookup**: `ScAllClassNamesIndex`, `ScShortClassNameIndex`,
  `ScNotVisibleInJavaShortClassNameIndex`, `ScClassFqnIndex`,
  `ScClassNameInPackageIndex`, `ScJavaClassNameInPackageIndex`,
  `ScPropertyClassNameIndex`, `ScAliasedClassNameKey`.
* **Member lookup**: `ScFunctionNameIndex`, `ScPropertyNameIndex`,
  `ScClassParameterNameIndex`, `ScTypeAliasNameIndex`,
  `ScStableTypeAliasNameIndex`, `ScStableTypeAliasFqnIndex`.
* **Packages**: `ScPackageObjectFqnIndex`, `ScShortNamePackageObjectIndex`,
  `ScPackagingFqnIndex`.
* **Imports**: `ScAliasedImportKey`.
* **Implicits**: `ImplicitConversionIndex`, `ImplicitInstanceIndex`,
  `ScImplicitObjectKey`.
* **Inheritance**: `ScDirectInheritorsIndex`, `ScSelfTypeInheritorsIndex`.
* **Annotations**: `ScAnnotatedMemberIndex`, `ScAnnotatedMainFunctionIndex`.
* **Top-level (Scala 3)**: `ScTopLevel{Property,Function,Alias,
  ImplicitClass,GivenDefinitions,Export,Extension}ByPackageIndex`.
* **Scala 3 specifics**: `ExtensionIndex`, `ScGivenIndex`.

### 6.2 Custom searchers (`lang/psi/impl/search/`)

* `ScalaDirectClassInheritorsSearcher` (`directClassInheritorsSearch`, `:490`) — uses `ScDirectInheritorsIndex`.
* `ScalaLocalInheritorsSearcher` (`classInheritorsSearch`, `:491`).
* `ScalaOverridingMemberSearcher` (`overridingMethodsSearch`, `:521`).
* `ScalaAnnotatedMembersSearcher` (`annotatedElementsSearch`, `:587`).
* `MethodImplementationsSearch` (`definitionsSearch`, `:520`).
* `JavaRawOverridingSearcher`, `JavaRawAllOverridingSearcher`
  (`overridingMethodsSearch`/`allOverridingMethodsSearch`, `:522-523`).

### 6.3 Scope enlargers

* `useScopeEnlarger` ← `ScalaSharedSourcesUseScopeEnlarger` (`:469`) — makes a
  symbol declared in shared sources visible from Java & vice versa.
* `resolveScopeEnlarger` ← `ScalaSharedSourcesResolveScopeEnlarger` (`:470`).

### 6.4 File-based indices

* `ScalaDocAsteriskAlignStyleIndexer` (`:691`) — formatter inference.
* `ImportOrderingIndexer` (`:692`) — auto-import ordering.
* `ScalaTodoIndexer` (`:486-487`).

### 6.5 Populating stub indices when the parser is `pc`+TASTy

Stub indices today are populated by `ScalaParser` walking the file and emitting
`StubElement`s via `ScalaElementType`/`ScalaParserDefinition` (registered at
`:650-652`). For Scala 3:

* **Source files**: keep the IntelliJ parser → stubs as today (editing still
  needs it; see `01-psi-parser-lexer.md`).
* **Library classes**: today rely on `ScClassFileDecompiler` (`:737`) and
  `TastyFileStubBuilder` (`:759`). Extend the latter to emit entries for the new
  Scala 3 indices (`ExtensionIndex`, `ScGivenIndex` — both already exist).
* **Classpath-level queries** ("all subclasses of X in any JAR"): today
  `ScDirectInheritorsIndex` walks JAR-decompiled PSI. **Replace** with
  `pc.subclasses(symbol)` which queries the SemanticDB index directly — far
  faster, no JAR decompilation required.

The `externalSearchScopeChecker` EP lets us tell the platform: "for Scala 3
library symbols, skip the stub index entirely and trust `pc`".

---

## 7. Editor mechanics — **STAYS**

These are purely syntactic (operate on the IntelliJ lexer/parser only, never on
resolve). They remain untouched by the `pc` migration.

### 7.1 Formatting (`scala-plugin-common.xml:210-218`)

* `lang.formatter` ← `ScalaFormattingModelBuilder` (`:210`).
* `postFormatProcessor` ×2: `ScalaBracePostFormatProcessor`,
  `ScalaTrailingCommaProcessor` (`:211-212`).
* `preFormatProcessor` ×2: `ScalaDocNewlinedPreFormatProcessor`,
  `ScalaFmtPreFormatProcessor` (`:213-214`).
* `langCodeStyleSettingsProvider`, `lang.lineWrapStrategy`.
* **scalafmt** integration (`lang.formatting.scalafmt.*`, dynamic service at
  `:701-702`); `compiler.task` `ReformatOnCompileTask` (`:694`).

### 7.2 Folding, structure, breadcrumbs

* `lang.foldingBuilder` ← `ScalaFoldingBuilder` (`:209`).
* `lang.psiStructureViewFactory` ← `ScalaStructureViewFactory`
  (`scala/structure-view/.../ScalaStructureViewFactory.scala:9`, registered in
  `scala/structure-view/resources/META-INF/structure-view.xml:14`).
* `navbar` ← `ScalaNavBarModelExtension` (`structure-view.xml:16`).
* `breadcrumbsInfoProvider` ← `ScalaBreadcrumbsInfoProvider` (`:88`)
  — `lang/breadcrumbs/ScalaBreadcrumbsInfoProvider.scala:18`; *does* read
  `ScType` for function tooltips, so mildly hybrid, but works fine with
  `PcBackedScType`.
* `codeFoldingOptionsProvider` (`:113`).

### 7.3 Smart enter, typed, backspace, join, surround, copy/paste

All registered in `scala-plugin-common.xml`. Summary:

* **Smart enter / enter handlers** (`:236-237`, `:558-582`): `ScalaSmartEnterProcessor` + 13 `enterHandlerDelegate`s (doc/markdown, interpolated/multiline strings, closure braces, unmatched brace, unit-fn-sig, package-split, format-empty-template, format-keyword, auto-brace, scala3-indentation, template-parents).
* **Typed / backspace** (`:223-230`): `ScalaDocTypedHandler`, `ScalaTypedHandler`, `ScalaAutoPopupCompletionHandler`, `ScalaDirectiveAutoPopupCompletionHandler`; `ScalaBackspaceHandler`, `Scala3IndentationBasedSyntaxBackspaceHandler`.
* **Join / surround / copy-paste** (`:219-232`, `:240-243`): 2 `joinLinesHandler`, 3 `lang.surroundDescriptor`, 4 `copyPastePreProcessor`.
* **Selection / matching** (`:726-733`): 6 `extendWordSelectionHandler`; `lang.braceMatcher` (`:197`), `codeBlockSupportHandler` (`:198`), `lang.quoteHandler` (codeInsight.xml:20).
* **Misc**: `editorFileSwapper` (`:642`), `moveLeftRightHandler` (`:105`), `statementUpDownMover` (`:104`), `lang.rearranger` (`:91`).

All **STAYS** — pure syntactic.

### 7.4 Live templates

Declared in `scala/codeInsight/resources/META-INF/codeInsight.xml:34-83`:

* `defaultLiveTemplates file="liveTemplates/scala"` (`:34`).
* `liveTemplatePreprocessor` ← `ScalaTemplatePreprocessor` (`:35`).
* 8 `liveTemplateContext`s (SCALA, SCALA3, SCALA_XML, SCALA_COMMENT,
  SCALA_STRING, SCALA_CODE, SCALA_IMPLICIT_CLASS, SCALA_BLANK_LINE).
* 23 `liveTemplateMacro`s — `ScalaClassNameMacro`, `ScalaExpressionTypeMacro`,
  `ScalaMethodReturnTypeMacro`, `ScalaTypeParametersMacro`,
  `ScalaVariableOfTypeMacro`, etc. Some read `Typeable.type()` and become
  **Hybrid** (route through `pc`).

### 7.5 Internal file templates (`:475-482`)

`Scala Class`, `Scala CaseClass`, `Scala CaseObject`, `Scala Trait`,
`Scala Object`, `Package Object`, `Scala File`, `Scala Enum`. Pure **STAYS**.

---

## 8. Migration plan — triage table

| Capability | Bucket | Action |
|---|---|---|
| Semantic completion contributors (`ScalaBasicCompletionContributor`, `ScalaSmartCompletionContributor`, `ScalaGlobalMembersCompletionContributor`, `ScalaClassNameCompletionContributor`, `ScalaOverrideContributor`, `ScalaUnresolvedNameContributor`, `ScalaPrefixPackageCompletionContributor`) | **(A) pc-backed** | New `ScalaPcCompletionContributor` calls `pc.completionsAt`; expected-type filter and override list built-in |
| Weighers, statisticians, ML feature providers | **(C) Hybrid** | Wrap `PcCompletionItem` into `ScalaLookupItem`; pipeline unchanged |
| Syntactic completion (keyword, postfix, end-marker, type-annotation, scaladoc, directive, literal-type, named-tuple) | **(B) Stays** | Pure syntactic / template-driven |
| Clause completers (case-class-params, case-clause, exhaustive-match) | **(C) Hybrid** | `pc` provides symbol set; Scala PSI does indentation-aware insertion |
| Command completion (19 providers) | **(B) Stays** | Wraps existing actions |
| `ScalaImportTypeFix`, `ImportImplicitConversionFixes`, `ScalaImportGlobalMemberFix` | **(A)** | Single `PcUnresolvedReferenceFixProvider` backed by `pc` candidate list |
| Rename processors + `automaticRenamerFactory` ×3 | **(C) Hybrid** | Reference enumeration via `pc.findReferences`; AST rewrite unchanged |
| Extract Method / Variable / Field (basic), Surround, Statement mover | **(B) Stays** | Syntactic |
| Extract Trait, Move Member, Pull Up, Inline method/val/type, Change Signature, Safe Delete | **(C) Hybrid** | Conflict detection & usage enumeration via `pc`; UI stays |
| `SuggestedRefactoringSupport` (`ScalaSuggestedRefactoringSupport.scala:12`) | **(B) Stays** | Syntactic glue — already rename-only |
| `gotoDeclarationHandler`, `codeInsight.gotoSuper` | **(C) Hybrid** | Resolve via `pc`; existing handlers adapt |
| Inheritance/override/annotated searchers (`directClassInheritorsSearch`, `classInheritorsSearch`, `overridingMethodsSearch`, `annotatedElementsSearch`, `definitionsSearch`) | **(A)** | `pc.subclasses` / `pc.overrides` / `pc.annotated` — populate existing `Query` API |
| `referencesSearch` ×13 | **(A)** for Scala 3 | All collapse into `pc.findReferences`; keep Scala-2 PSI searchers |
| `externalReferenceSearcher`, `externalInheritorsSearcher`, `externalSearchScopeChecker` | **(A)** | Replace `CompilerIndices*` with `pc`-backed implementation |
| `findUsagesHandlerFactory`, `usageTypeProvider`, `findUsagesProvider`, `gotoClassContributor`, `gotoSymbolContributor` | **(B) Stays** | UX wrappers / stub-index driven |
| Semantic inspections (deprecation, unused, shadowing, redundant-cast, type-check, target-name, comparing-unrelated, abstract-val-in-trait, not-implemented) | **(D) Deprecated for Scala 3** | Replaced by `pc.diagnose` routed through `ExternalAnnotator` |
| Syntactic inspections (booleans, parentheses, redundant block, format, literal, modifiers, scaladoc, xml) | **(B) Stays** | Style checks; compiler doesn't emit them |
| Type-family intentions (`intention/types/*`) | **(C) Hybrid** | Read `pc.typeAt` instead of `Typeable.type` |
| Boolean / control-flow / string / comprehension / argument intentions | **(B) Stays** | Syntactic transforms |
| Import-family intentions | **(A)** | Backed by `pc` candidate list |
| `codeInsight.parameterInfo` ×3 | **(A)** | `PcParameterInfoHandler` calls `pc.signatureHelp` |
| `parameterInfoEnhancer` EP | **(B) Stays** | Extension seam |
| Type/implicit/parameter/type-arg/apply inlay hint passes | **(C) Hybrid** | Resolve via `pc`; existing renderer (`Hint`/`Corners`/`Text`) stays |
| Method-chain inlay hints, range hints | **(B) Stays** | Syntactic |
| `ScalaDocumentationProvider`, `ScalaPsiDocumentationTargetProvider` | **(C) Hybrid** | Library: `pc.hover`; Source: keep generator |
| Stub indices — source files | **(B) Stays** | IntelliJ parser still runs on source |
| Stub indices — library classes | **(A)** for new queries | TASTy-based; legacy JAR-decompiled PSI stays for Scala 2 |
| `useScopeEnlarger`, `resolveScopeEnlarger` | **(B) Stays** | Shared-sources logic |
| Formatting, folding, breadcrumbs, structure view, navbar | **(B) Stays** | Syntactic (breadcrumbs mildly hybrid via `ScType`) |
| Smart enter, typed/backspace/join/surround/copy-paste handlers | **(B) Stays** | Pure syntactic |
| Live templates & macros | **(B) Stays** (some macros **Hybrid**) | Templates stay; type-driven macros route through `pc` |
| Internal file templates | **(B) Stays** | |

### 8.1 Summary

* **(A) pc-backed semantic**: 1 new completion contributor, 3 reference-search
  EPs, 4 inheritance/override searchers, parameter-info, ~30 semantic
  inspections (deprecation, unused, shadowing, cast, type-check, target-name),
  unresolved-reference-fix, library stub indices.
* **(B) stays**: formatting, folding, smart-enter, typed/backspace/join,
  surround, copy-paste, live templates, structure view, breadcrumbs, file
  templates, all syntactic inspections/intentions, command completion,
  postfix templates, end-marker/keyword/directive completion, the entire
  weigher/statistician/ML UX layer.
* **(C) hybrid**: rename + automatic renamers, inline, extract trait, move
  member, pull up, change signature, safe delete, type-driven intentions,
  inlay hint engines, documentation, breadcrumbs (marginally), live-template
  macros, clause completers.
* **(D) deprecated for Scala 3**: any semantic inspection that simply mirrors
  a compiler warning (deprecation, redundant-cast, comparing-unrelated-types,
  unused, shadowing, target-name) — survives only for Scala 2.
