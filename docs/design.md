# Scala 3 PC Bridge — IntelliJ Plugin Design

> **Name:** **Metallurgy** (a play on Metals — whose backend `pc` is — and the discipline of working with metals).
> **Goal:** A 3rd-party IntelliJ plugin that **sits alongside the bundled Scala plugin** and **augments** it for Scala 3 modules by delegating to the real Scala 3 presentation compiler (`pc`) from the Metals project. Wherever the bundled plugin returns the wrong answer (typically `Any` for a transparent-inline call, an unresolved reference that should resolve, or a false-positive error) we *intercept and augment* with the answer from `pc`. Where the bundled plugin is correct, we get out of the way.
> **Non-goal:** Replace the bundled Scala plugin. Disable its compiler pass. Reimplement PSI. Touch Scala 2 modules. Replace the parser, lexer, formatter, build import, run configurations, debugger, REPL, or worksheet runtime.
> **Status:** Design + scaffolding. Implementation has not started.
> **Companion docs:** [`../CONTEXT.md`](../CONTEXT.md) (domain glossary), [`./adr/`](./adr/) (architectural decisions), [`./research/`](./research/) (deep-dive reports on the bundled Scala plugin's seams + canonical macro/inline patterns).

---

## Table of contents

1. [Strategy: intercept and augment](#1-strategy-intercept-and-augment)
2. [The detection principle](#2-the-detection-principle)
3. [Architecture](#3-architecture)
4. [BETASTy: cross-module error recovery](#4-betasty-cross-module-error-recovery)
5. [Feature 0 — Hijack `CompilerType`  *(smallest intercept, ship first)*](#5-feature-0--hijack-compilertype--smallest-intercept-ship-first)
6. [Feature 1 — Completion augmentation](#6-feature-1--completion-augmentation)
7. [Feature 2 — Diagnostics: add missing, suppress wrong](#7-feature-2--diagnostics-add-missing-suppress-wrong)
8. [Feature 3 — Type info & hover enrichment](#8-feature-3--type-info--hover-enrichment)
9. [Feature 4 — Inlay hints & semantic tokens](#9-feature-4--inlay-hints--semantic-tokens)
10. [Feature 5 — Synthetic members via `pc`](#10-feature-5--synthetic-members-via-pc)
11. [Feature 6 — Navigation & find-usages augmentation](#11-feature-6--navigation--find-usages-augmentation)
12. [Build integration & lifecycle](#12-build-integration--lifecycle)
13. [Classloader & versioning](#13-classloader--versioning)
14. [Explicit gaps & non-goals](#14-explicit-gaps--non-goals)
15. [Phased delivery](#15-phased-delivery)
16. [References](#16-references)

---

## 1. Strategy: intercept and augment

The bundled Scala plugin is not going away (it is too tightly integrated to remove). This plugin's framing is *intercept and augment, don't replace*: rather than disable the bundled plugin for Scala 3 modules, register additional EP implementations that run alongside it and *correct or enrich* its results only where it is provably wrong or incomplete. The phasing follows the same instinct — start with the smallest intercept that delivers value, expand only when the next gap is felt.

### 1.1 The plugin's job, in one sentence

> For each IntelliJ extension point that affects language semantics, register an additional implementation that runs **alongside** the bundled plugin's, asks `pc` for the authoritative answer, and **only adds, removes, or corrects** results where the bundled plugin is provably wrong or incomplete.

The plugin is invisible when not needed. Users see it only when they hit a Scala 3 feature the bundled plugin mishandles — and then the right thing happens.

### 1.2 The five interception patterns

Every feature in this plugin is one of these five patterns:

| Pattern | Mechanism | Triggered when | Example |
|---|---|---|---|
| **Add** missing results | Extra `completion.contributor`, extra `annotator`, extra `syntheticMemberInjector` | Bundled returns empty / `Any` / no member | Bundled returns `Any` for `MyType.derived` → we add the real signature from `pc` |
| **Suppress** wrong results | `problemHighlightFilter`, `inspectionSuppressor`, `annotator` that removes highlights | Bundled reports something `pc` says is fine | Bundled shows "unresolved reference" on a transparent-inline expansion → we suppress |
| **Enrich** results | `psiTargetProvider order="before"`, `documentationProvider`, `parameterInfoEnhancer` | Bundled's hover/type-info is correct but incomplete | Bundled shows "method foo: Any" → we show "method foo: Foo[String]" |
| **Replace** selectively | EP with `order="before …"` and a guard | Bundled is just wrong; can't be filtered | Bundled infers wrong overloaded variant → we provide the right one |
| **Layer** new services | Plain `projectService` + adapter hooks | No direct EP; downstream code can opt-in | `PcTypeService.getRealType(expr)` callable from any consumer |

The plugin never disables the bundled compiler-highlighting pass. It never registers a `parserDefinition`. It never creates a shadow file type. The bundled PSI stays the source of truth for syntactic operations; we add a *semantic side-table* keyed by `(PsiElement, DocumentVersion)` that callers can consult.

### 1.3 What this gives up vs. a full replacement

We inherit the bundled plugin's bugs in places where the bundled plugin returns *plausible-looking-but-wrong* results (because we only correct when we know to look). We accept this trade-off: every correction we *do* make is a strict win, and there is no migration risk because nothing is being replaced.

### 1.4 What this does *not* give up

- **Editor integration.** Native IntelliJ. No LSP UI.
- **Cross-language.** Java ↔ Scala navigation continues to work because the bundled PSI is intact.
- **Inspections / intentions / refactorings.** All 173 localInspection + 70 intentionAction EPs from the bundled plugin continue to work. When we feed them better types (via `PcTypeService`), they get more accurate for free.
- **Build / run / debug / REPL / worksheet.** Unchanged.

---

## 2. The detection principle

### 2.1 Module-level gate (performance only)

Every EP implementation in this plugin starts with:

```scala
if (!PcService.isScala3(moduleForFile(file))) return
```

This is a **performance gate**, not a behavior gate. We skip non-Scala-3 modules entirely. We do not change their behavior.

### 2.2 Per-feature trigger (the actual interception)

Within a Scala 3 module, each interception is gated by a *concrete signal* that the bundled plugin got it wrong:

| Signal | Where observed | What we do |
|---|---|---|
| Expression's inferred `ScType` is `Any` / `Nothing` / `Null` and the expression is a transparent-inline call, a quote/splice, or a macro call | `ScExpression.getType()` returns these | Augment hover, inlay hint, and completion with the real type from `pc` |
| Reference is unresolved but `pc` resolves it to a `Symbol` | `ScReference.resolve()` returns null; `pc.complete` finds it | Add completion item, add import quick-fix, suppress "unresolved" highlight |
| Bundled reports an error at range R with message M, but `pc.diagnose` produces no diagnostic at R | `pcHighlightingPass` compares the two sets | Suppress the bundled highlight via `problemHighlightFilter` |
| Bundled produces no diagnostic at R, but `pc.diagnose` does | Same comparison | Add the missing diagnostic via our own `externalAnnotator` |
| Synthetic member lookup misses a `derives`-generated member | `syntheticMemberInjector` returns nothing for the name | Add the member from `pc` |
| Bundled completion returns < N items for a Scala 3 advanced position (e.g. inside `'{ … }`, in a match-type RHS, in a polymorphic-function-typed argument) | Result count + position classifier | Add `pc` completions, deduplicate by name |

### 2.3 "Always-on" vs "ask-once"

Some interceptions are cheap enough to be always-on (e.g. hover enrichment calls `pc.hover` only when the user hovers). Others are expensive (e.g. running `pc.diagnose` on every keystroke). The expensive ones are:

- **Debounced** (300ms after last edit).
- **Viewport-scoped** (only the visible range + a configurable lookaround).
- **Cached** per `(file, DocumentVersion)`.

This mirrors the bundled plugin's incremental-highlighting strategy.

---

## 3. Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              IntelliJ IDEA                              │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                  Metallurgy  (this plugin)                       │  │
│  │                                                                   │  │
│  │   Completion           Diagnostics         Hover / Inlay          │  │
│  │   Contributor          Annotator           Providers              │  │
│  │   (order=after         (externalAnnotator) (order=before)         │  │
│  │    bundled)                                bundled)               │  │
│  │        │                   │                     │                │  │
│  │        └───────────────────┴─────────────────────┘                │  │
│  │                           │                                       │  │
│  │                  ┌────────▼────────┐                               │  │
│  │                  │  PcSession      │  (per-module, per-version)    │  │
│  │                  │  Manager        │                               │  │
│  │                  └────────┬────────┘                               │  │
│  │                           │                                        │  │
│  │                  ┌────────▼────────┐    ┌───────────────────────┐  │  │
│  │                  │  PcSnapshot     │    │ PcSemanticSideTable   │  │  │
│  │                  │  (per doc ver)  │    │ (PsiElement → types,  │  │  │
│  │                  │  • diagnostics  │    │  symbols, expansions) │  │  │
│  │                  │  • completions  │    │                       │  │  │
│  │                  │  • hover/types  │    └───────────────────────┘  │  │
│  │                  └─────────────────┘                               │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │          Bundled Scala plugin (unchanged, still active)           │  │
│  │   • Parser / PSI / Stub indices      • Compiler-highlighting pass │  │
│  │   • Type system / resolver           • Inspections / intentions   │  │
│  │   • sbt/BSP/JPS import + build       • Refactorings               │  │
│  │   • Editor mechanics                 • Run/Debug/REPL/Worksheet   │  │
│  │   • Scala 2 support                  • Synthetic member injectors │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                                                         │
│                                            ┌────────────────────────┐   │
│                                            │ target/                │   │
│                                            │  ├── **/*.tasty        │   │
│                                            │  ├── META-INF/         │   │
│                                            │  │   best-effort/      │   │
│                                            │  │   └── **/*.betasty  │   │
│                                            │  └── META-INF/         │   │
│                                            │      semanticdb/       │   │
│                                            └────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────┘
                                ▲
                                │  ServiceLoader (MiMa-binary-compat)
                                │
              ┌─────────────────┴───────────────────┐
              │  org.scalameta:mtags_<scalaBinVer>  │
              │   └── PresentationCompiler (pc)     │
              │        └── dotty.tools.dotc         │
              │             └── Interactive driver  │
              └─────────────────────────────────────┘
```

### 3.1 Components

| Component | Purpose | Risk |
|---|---|---|
| **`PcSessionManager`** (`projectService`) | One `PresentationCompiler` per `(module, scalaBinaryVersion, classpathHash)`. Hot-swaps on classpath change. | Low — solved pattern. |
| **`PcSnapshot`** (immutable, per `(file, DocumentVersion)`) | View of one file at one version: diagnostics, completions cache, hover cache, semantic tokens. | Med. |
| **`PcSemanticSideTable`** | `(PsiElement, DocumentVersion) → (Symbol, ScType, expansion)`. Updated when `PcSnapshot` refreshes. Hover / inlay / completion providers query this. | Med. |
| **`ModuleDetectionService`** | Tracks which modules are Scala 3. Used as a fast-path gate inside every EP impl. | Low. |
| **`BundledPluginProbe`** | Reflectively inspects the bundled plugin's results (e.g. `ScExpression.getType()`) to decide whether to augment. | Med — depends on bundled plugin's public API. |
| **Per-feature EP impls** | One class per EP we register. See §5–§10. | Low–Med each. |

---

## 4. BETASTy: cross-module error recovery

### 4.1 What BETASTy actually is

**BETASTy = "Best-Effort TASTy"**, introduced in Scala 3.5 ([docs](https://www.scala-lang.org/api/3.5.2/docs/docs/internals/best-effort-compilation.html)). Two compiler flags:

- **`-Ybest-effort`** — forces compilation through the typer *regardless of errors*, then writes a `.betasty` file to `META-INF/best-effort/` inside the output jar. The file uses a TASTy-like grammar extended with an `ERRORtype` constructor to represent untypeable parts.
- **`-Ywith-best-effort-tasty`** — when reading from classpath, accepts `.betasty` files. If one is read, the compiler is restricted to frontend phases only.

The pipeline (paraphrased from the docs):

```
Parser → (always) → Typer ─────────────┐
                                       │
                  with errors          │   no errors
                       │               │
                       ▼               ▼
            [stop after frontend]   [continue normally]
                       │               │
                       └─────►  Pickler writes .betasty (and/or .tasty)
```

### 4.2 Why this is the core primitive

Without BETASTy, the rule is binary: a module either compiles (and emits TASTy) or it doesn't (and downstream modules see stale or empty types). In an IDE this is catastrophic, because the user is *always* in a "doesn't compile yet" state. Until 3.5, `pc` could do best-effort *within a file* but had no good answer for *across modules*: change one file, break a public signature, and every downstream module immediately loses all type info for the affected symbols.

With BETASTy:
- Module A fails to compile → still emits `.betasty` containing the typer's best-effort view of A's symbols.
- Module B depends on A → `pc` runs with `-Ywith-best-effort-tasty` → sees A's `.betasty` → produces accurate completion / hover / diagnostics for B even though A doesn't compile.

This is the **core functionality allowing cross-module error recovery**: edit a file, break the build, and downstream modules still see the affected symbols with their best-effort types.

### 4.3 What this means for our plugin

Two implications:

1. **We must enable BETASTy in the build pipeline.** This is a single scalac flag. The bundled plugin's `ScalaCompilerConfiguration` API lets us add per-module flags. We add `-Ybest-effort -Ywith-best-effort-tasty` (Scala 3.5+) to opted-in modules. On 3.3 LTS, where the feature may not yet be available on the user's exact patch version, we degrade gracefully: `pc` still works but loses cross-module recovery.

2. **`pc` does the heavy lifting.** We do not parse `.betasty` ourselves. We rely on `pc.Interactive` to read it. Our plugin's job is to plumb the scalac flags into the build and to consume `pc`'s output.

### 4.4 What BETASTy is *not*

- It is **not** "untyped-tree positions" or "positions for collapsed trees" (a confusion with `-YretainTrees`, an unrelated debugging knob).
- It is **not** a stable format. The docs are explicit: *"no compatibility rules are defined for now, and the specification may change between the patch compiler versions."* We pin to a Scala 3 patch version per module and re-test on bumps.

---

## 5. Feature 0 — Hijack `CompilerType`  *(smallest intercept, ship first)*

### 5.1 The existing hack this replaces

The bundled Scala plugin already runs a hand-rolled "ask the real compiler for transparent-inline types" pipeline. We do not need to invent a new seam — we replace the transport, reusing every existing consumer.

The pipeline today:

| Layer | What it does | File |
|---|---|---|
| **Producer** (scalac plugin shipped by intellij-scala) | Phase `intellij-typer` running after `TyperPhase`, hooks `transformInlined`; for non-empty `tree.call`, prints `<type>…</type>` via `report.echo` for transparent-inline call sites | `scala/compiler-plugin/scala-3.3/src/CompilerPlugin.scala:40-54` (Scala 3.3+); parallel impls at `scala-2.12/src/CompilerPlugin.scala` and `scala-2.13/src/CompilerPlugin.scala` |
| **Transport** | Compile-server wire: the `report.echo` text flows back as `CompilerEvent.MessageEmitted(ClientMsg(MessageKind.Info, text, Some(source), _, Some(begin), Some(end), _))` | JPS protocol; produced by the bundled compile server |
| **Consumer** | Parses the `<type>` markers and stores as `FileCompilerGeneratedState.types: Map[((begin, end), tpe)]` keyed by `(file, compilationId)` | `scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/highlighting/UpdateCompilerGeneratedStateListener.scala:64-70` |
| **Application** | Sets `CompilerType(e) = Some(tpe)` as **copyable user-data** on the matching PSI element when its range matches | `ExternalHighlightersService.scala:104-106` |
| **Reads** | `ScExpression.getType()` consults `CompilerType(expr)` first; same for `ScStableCodeReferenceImpl` and the completion machinery | `ScExpression.scala:317-329`, `ScStableCodeReferenceImpl.scala:478-485`, `lang/completion/package.scala:273-281` |
| **On miss** | `CompilerType.requestFor(e)` publishes on a `Topic`; `CompilerTypeRequestListener` triggers a real recompile via the compile server to refresh | `CompilerType.scala:21-27`, `CompilerTypeRequestListener.scala:11-12` |
| **Setting** | The whole flow is gated by `ScalaProjectSettings.isUseCompilerTypes` (also exposed in the highlighting-mode widget as "compiler types") | `ScalaPluginAboutPopupDescriptionProvider.scala:115`, `ScalaHighlightingModeWidget.scala:50` |

### 5.2 Why this is the ideal first intercept

- **The consumers already exist and are battle-tested.** `ScExpression.getType()`, completion's transparent-inline branch, and `ScStableCodeReferenceImpl` already read from `CompilerType`. By writing into that user-data slot we get those three call sites working for free.
- **The transport is what's broken.** Today it requires a full compile-server round-trip per request, with type strings round-tripped through stdout markers. Latency is seconds; coverage is "whatever the compiler happened to print this pass".
- **`pc` answers the same question in milliseconds, in-process, with the authoritative type.** No compiler plugin. No stdout scraping. No recompile.

### 5.3 Our replacement

Register a `CompilerType.Listener` on the existing `Topic`:

```scala
project.getMessageBus.connect(disposable)
  .subscribe(CompilerType.Topic, (e: PsiElement) => {
    if (!PcService.isScala3(moduleOf(e))) return
    PcSessionManager.get(moduleOf(e)).foreach { pc =>
      for {
        file <- Option(e.getContainingFile)
        doc  <- Option(PsiDocumentManager.getInstance(project).getDocument(file))
        offset = e.getTextRange.getStartOffset
        tpe <- pc.typeAt(uri(file), offset, doc.getText)  // in-process, ms
      } {
        CompilerType(e) = Some(renderForCompilerTypeSlot(tpe))
      }
    }
  })
```

Where `renderForCompilerTypeSlot` produces the same string format the bundled consumers expect (the `toText` of `pc`'s `Type`, fully qualified — see `CompilerPlugin.TypePrinter` at `scala-3.3/src/CompilerPlugin.scala:59-67` for the format conventions: always print prefix, replace `<root>.this.` → `_root_.`, etc.).

### 5.4 What this gets us

With this one EP subscription, on opted-in Scala 3 modules:

- Hover / inlay / completion on a transparent-inline call shows the **real** expanded type instead of `Any`.
- The bundled plugin's existing completion branch in `lang/completion/package.scala:263-285` lights up correctly without any completion-contributor work from us.
- Latency drops from "wait for a recompile" to "wait for `pc` round-trip" (single-digit ms).

### 5.5 What this *does not* get us

- It only fills the `CompilerType` string slot. It doesn't add diagnostics, completions for things the bundled plugin doesn't already attempt, or synthetic members. Those are Features 1–5.
- The `CompilerType` slot stores a string, not a structured `ScType`. Some callers (e.g. `ScExpression.getType()`'s further use of the value) re-parse the string into `ScType`. We get the same parsing for free; we don't get to bypass it. That's a Phase-4 problem (see § 8.5).
- We use our own opt-in setting, **`MetallurgySettings.isEnabled(module)`** (see ADR 0006), not the bundled plugin's `isUseCompilerTypes`. The bundled setting is left entirely alone.
- Later, when we trust our impl, we can recommend users disable `isUseCompilerTypes` (the bundled plugin's redundant stdout-scraping producer falls silent) to reduce compile-server load.

---

## 6. Feature 1 — Completion augmentation

**Why this is Feature 1 (not 0):** Self-contained EP. No coordination with bundled highlighting. An existing POC has already proved the completion-contributor seam works at this exact EP. After Feature 0 (the `CompilerType` hijack) lands, this is the next user-visible win.

### 5.1 EP

```xml
<completion.contributor
    language="Scala"
    implementationClass="… Scala3PcCompletionContributor"
    order="after scalaCompletionContrubutor"/>
```

The bundled contributor (`scala-plugin-common.xml:396`) runs first. We run after, see what was added, and supplement.

### 5.2 Trigger logic

```
1. isScala3(file.module) ?
2. Are we at a "bundled-is-likely-incomplete" position?
     - Inside `'{ … }` or `${ … }` (quoted/spliced)
     - After a transparent inline method call
     - In a match-type reduction context
     - After `.derived` / `.asInstanceOf` on a `Mirror.Of`
     - In an extension-method receiver position with given/using in scope
     - Or: bundled returned 0 items at a non-trivial identifier position
3. Call pc.complete(uri, offset).
4. Deduplicate vs. bundled results by (name, kind).
5. Add the new lookup elements.
```

The "bundled-is-likely-incomplete" predicate is conservative. False negatives (we don't augment when we should) just leave the bundled result untouched. False positives (we augment when we didn't need to) cost a `pc` round-trip but produce correct items.

### 5.3 Lookup element presentation

Same source as in the previous design: `pc.CompletionItem.label` / `.detail` / `.kind` / `.deprecated` → IntelliJ `LookupElement` with bundled `ScalaColorsAndFontsPage` colors. Documentation is lazy via `pc.hover`.

### 5.4 Symbol search (capital-letter lookup across project)

Symbol search: type `ConcurrentMap` (no import) and see the class. Implementation: register `completion.contributor` with `id` and handle `parameters.isClassNameCompletion`. Inside, ask `pc` for workspace symbols — but feed `pc`'s `WorkspaceSymbolSearch` SPI from IntelliJ's `PsiShortNamesCache`. This gives us cross-language (Java + Scala) symbol search with one index.

### 5.5 What stays with the bundled plugin

- Keyword completion (`def`, `val`, `if`, …)
- Live templates
- Postfix templates
- `case`-clause exhaustive completion (we may enrich the sealed-parent enumeration later via `pc`)
- ScalaDirective completions

---

## 7. Feature 2 — Diagnostics: add missing, suppress wrong

### 6.1 Two-sided interception

```
For each Scala 3 file in the visible editor:
   let B = bundled plugin's diagnostics on this file
   let P = pc.diagnose(file, visibleRange)
   ToAdd    = P \ B   (in pc, not in bundled)   ← surface as our annotations
   ToSuppress = B \ P   (in bundled, not in pc) ← filter out via problemHighlightFilter
```

### 6.2 Adding missing diagnostics

Register `externalAnnotator language="Scala"`. IntelliJ invokes it after the bundled annotators on the same file. We:

1. Get the bundled `Annotation` set on the file (via `AnnotationHolderImpl` inspection — there are platform APIs for this; if not, we track them in our own `Annotator` that runs *before* the bundled one and stashes results in a `UserData` key on the `PsiFile`).
2. Ask `pc.diagnose(uri, range)` for the visible range.
3. For each `pc.Diagnostic` not already covered by a bundled `Annotation` (matched on `(range, severity, message-prefix)`), emit our own `Annotation`.

Field mapping:

| `pc.Diagnostic` | IntelliJ |
|---|---|
| `severity` | `HighlightInfoType.ERROR/WARNING/INFO/HINT` |
| `range` | `TextRange` |
| `message` | `Annotation.message` |
| `code` | inspection id for suppression UX |
| `actions` | `LocalQuickFix` adapters (§6.4) |

### 6.3 Suppressing wrong bundled diagnostics

Register `problemHighlightFilter implementationClass="Scala3PcProblemHighlightFilter"`. For Scala 3 files, the filter consults `pc.diagnose` and decides whether a given `HighlightInfo` should be shown.

Two practical concerns:

1. **Performance.** `problemHighlightFilter` is called per-highlight; calling `pc` per call is unacceptable. We *cache* `pc`'s diagnostic set per `(file, DocumentVersion)` and consult the cache.
2. **Conservatism.** We only suppress when we are *confident*. A bundled `ERROR` is suppressed only if `pc` produces no diagnostic in the same range *and* `pc`'s snapshot for this file is recent (within 2 saves). Otherwise, leave it. The user can always toggle the suppression globally.

### 6.4 Quick fixes from `pc`

`pc.Diagnostic.actions` cover the common cases:

| pc action | IntelliJ fix |
|---|---|
| Add import | Reuse bundled `ScalaImportTypeFix` (it works on PSI; we feed it the right `PsiClass`) |
| Insert implicit conversion | New `PcInsertImplicitConversionFix` (computes the conversion via `pc`, applies via PSI edit) |
| Implement missing members | Reuse bundled `ScalaImplementMethodsHandler` with `pc`-computed signatures |
| Replace with suggested source | `ReplaceTextFix` |

### 6.5 The bundled compiler-highlighting pass stays on

We do **not** disable the bundled pass. The user's existing setting (`ScalaHighlightingMode`) is unchanged. Our `externalAnnotator` adds to what the bundled pass produces; our `problemHighlightFilter` selectively subtracts.

This is the defining property of the strategy. We are robust to bundled plugin updates because we do not depend on its internal pipeline.

---

## 8. Feature 3 — Type info & hover enrichment

### 7.1 The seam

We do not replace the bundled `ScType`. We add a side-channel:

```scala
final class PcTypeService(project: Project) {
  def realTypeOf(expr: ScExpression): Option[ScType] = ???
  // Returns Some(realType) if:
  //   - file is Scala 3
  //   - expr's bundled type is Any/Nothing/Null OR
  //     expr is a transparent-inline call OR
  //     expr is a quoted/spliced expression
  //   - pc has a snapshot for this file
  // Returns None otherwise; caller falls back to bundled
}
```

Consumers of `PcTypeService`:

- **Hover provider** (§7.2)
- **Inlay hints** (§8)
- **Completion item details** (§5.3 — for transparent-inline macro return types)
- **Parameter info handler** (§7.3)
- **Anyone else who wants** — the service is a public project-level API

### 7.2 Hover enrichment

Register `platform.backend.documentation.psiTargetProvider order="before ScalaPsiDocumentationTargetProvider"` (`scala-plugin-common.xml:500`). For Scala 3 elements, call `pc.hover(uri, offset)`; render Markdown. If `pc` returns nothing or the file is not Scala 3, return null and let the bundled provider take over.

### 7.3 Parameter info enrichment

`parameterInfoEnhancer` EP (`scala-plugin-common.xml:12`). `Scala3PcParameterInfoEnhancer` calls `pc.signatureHelp(uri, offset)` and renders the current alternative's parameter list.

### 7.4 Macro-expansion view

For transparent-inline calls, `pc.expandInline(uri, range)` returns the expansion. We surface this as:

- A hover section ("Expanded to: …")
- A gutter icon (line marker) to view full expansion
- An inlay hint at the call site showing the expansion's type

---

## 9. Feature 4 — Inlay hints & semantic tokens

### 8.1 Inlay hints

`codeInsight.declarativeInlayProvider` (preferred over the legacy `InlayProvider`) for:

- Inferred type of `val`/`var`/`def` without annotation (only where bundled does not already show one — we can read the existing hints via `InlayModel`)
- Lambda parameter types
- Implicit / using parameter names at call sites
- Transparent-inline call expansion summaries
- Macro-derived member annotations

`pc.inlayHints(uri)` provides all of these in a single round-trip. Conversion to IntelliJ `InlineInlayInfo` is mechanical.

### 8.2 Semantic tokens (highlight enrichment)

We do **not** replace syntax highlighting. We add a `HighlightVisitor` that runs after the bundled one and applies extra text attributes from `pc.semanticTokens(uri)` for Scala 3 files. Use cases:

- Mark deprecated resolved references with strikethrough (the bundled plugin does this for declarations, not always for references)
- Mark implicit-conversion call sites with a distinguishing underline
- Mark transparent-inline call sites with a gutter marker

### 8.3 Line markers

Register `codeInsight.lineMarkerProvider order="before scalaLineMarkerProvider"` for Scala 3 only. Adds:

- Macro expansion marker
- "Run" arrow on `@main` methods (bundled already does this; we add for cases bundled misses)
- Trait-impl markers for `derives`-generated members

---

## 10. Feature 5 — Synthetic members via `pc`

### 9.1 The seam

The bundled plugin already exposes the `org.intellij.scala.syntheticMemberInjector` EP (`scala-plugin-common.xml:11`). It returns *source text* that gets re-parsed into PSI. We register a new injector:

```xml
<syntheticMemberInjector
    implementation="… Scala3PcSyntheticMembersInjector"
    order="last"/>
```

It runs *after* the bundled injectors (case-class, enum, derives, circe, monocle, etc.). For Scala 3 classes/objects:

1. Asks `pc` for all synthetic members of the underlying `Symbol`.
2. For each member the bundled injectors did not already produce (dedupe by signature), returns source text for it.

### 9.2 Why this is huge

Per `research/05-macros.md` §3 and `research/09-scala3-feature-gaps.md`, the bundled plugin has *no* real handling of:

- Transparent-inline-generated methods
- Typeclass `derives` for non-enum classes (circe Encoder/Decoder, monocle Lens, etc.)
- Macro-annotation-generated members (`@derive`, `@deriving`)

`pc` actually runs the typer, so it *knows* these members. Our injector adds them to PSI. This immediately unblocks: completion on `MyType.derived`, find-usages on a derived method, refactoring rename that updates derived sites, etc.

### 9.3 Gaps

- `pc` may not have all members on every snapshot (depending on what's compiled). We accept stale-then-fresh behavior.
- For macros that themselves fail to expand, `pc` reports `ERRORtype`. We surface that as a hint, not a synthetic member.

---

## 11. Feature 6 — Navigation & find-usages augmentation

### 10.1 Go-to-declaration

The bundled `gotoDeclarationHandler` (`scala-plugin-common.xml:497`) works for cases the bundled resolver resolves. We register `Scala3PcGotoDeclarationHandler order="before ScalaGoToDeclarationHandler"`:

1. If element is an unresolved reference in a Scala 3 file,
2. Ask `pc` for the target `Symbol`.
3. Resolve the symbol to a `PsiElement` (via offset in the symbol's source file).
4. Return that element.

### 10.2 Find-usages augmentation

The `externalReferenceSearcher` EP (`scala-plugin-common.xml:25`) exists specifically for "find references outside the PSI-resolvable scope". Today it's backed by compiler indices. We register `Scala3PcReferenceSearcher`:

1. Calls `pc.findReferences(symbol)`.
2. Converts each `(uri, range)` to a `PsiElement`.
3. Unions with bundled results.

This is dramatically more accurate for transparent-inline call sites, macro expansions, and synthetic members.

### 10.3 Rename

`renamePsiElementProcessor` EP. We register `Scala3PcRenameProcessor order="before RenameScalaMethodProcessor"` for Scala 3:

1. Use `pc.findReferences` to find all sites.
2. Apply rename via PSI edits.
3. Schedule `pc` recompile to verify no conflict.

---

## 12. Build integration & lifecycle

### 12.1 Classpath: we piggyback, we do not replace

**`pc` is an in-memory typechecker, not a build tool.** It runs the compiler frontend (parser → typer → a small set of post-typer phases including `inlining`) on the live source. It does **not** emit `.class` or `.tasty` files. But it cannot do its job without:

1. The compiled classpath of every module the file depends on (so it can resolve symbols outside the live buffer).
2. The Scala 3 stdlib + compiler jars matching the module's Scala version.
3. The module's scalac options (`-language:*`, `-Xexperimental:*`, etc., all affect typing).

In Metals, the BSP server produces these artifacts. In IntelliJ, **the bundled plugin's compile server + JPS + nailgun already produces them** into `target/` on every save. We read from there.

The concrete plumbing:

```
ScalaCompilerConfiguration.getSettings(module)  // bundled plugin's per-module settings
   │
   ├── module scope classpath (Production + Tests)
   │     = OutputRoots + OrderEntry[] (libraries + module deps)
   │
   ├── scalaInstance.libraryFiles                  // scala3-library_3, scala3-staging_3, …
   │
   └── compilerJars                                // scala3-compiler_3 for the mtags classloader
   │
   ▼
PcSession.start(
  classpath       = moduleClasspath ++ scalaInstance.libraryFiles,
  compilerJars    = scalaInstance.compilerClasspath,
  scalaBinaryVer  = module.scalaVersion.binary,
  scalacOptions   = module.scalacOptions
)
```

The `target/` directory supplies `.tasty` for resolved library/module symbols. With `-Ybest-effort` enabled (§12.2), `target/` also supplies `.betasty` for upstream modules that don't currently compile. **`pc` reads those files directly; we never parse them.**

Concretely, we hook **nothing** in the build pipeline. The bundled plugin's compile server keeps running as today; we listen to its `CompileServerNotification` only to invalidate `pc` snapshots when a dependency rebuilds.

### 12.2 What we add to the build

**Two scalac flags in opted-in modules.** The bundled plugin exposes `ScalaCompilerConfiguration` API to add module-specific flags. On project open (and on module reconfiguration), if `ModuleDetectionService` marks a module as Scala 3 and the user has the bridge enabled, we add:

```
-Ybest-effort                # Scala 3.5+ — emit .betasty even on errored source
-Ywith-best-effort-tasty      # Scala 3.5+ — let pc read .betasty from upstream
-Xsemanticdb                  # optional, recommended; richer cross-ref for find-usages
```

On Scala 3.3 LTS these flags may be unavailable depending on patch version; we degrade gracefully — `pc` still works, but cross-module recovery is weaker. The plugin surface detects this at module-detect time and tells the user.

### 12.3 `PcSessionManager` lifecycle

- Created lazily on first Scala 3 file open in a module.
- Refreshed when classpath changes (a dependency is rebuilt or reimported) — we subscribe to `LibraryTableListener` / `ModuleRootListener` for this.
- Closed when the module is removed.

### 12.4 `PcSnapshot` lifecycle

- One per `(file, DocumentVersion)`.
- Refreshed:
  - **300ms after a document edit** (debounced) → `pc` retypechecks this file.
  - **On save** → `pc` retypechecks this file + dependents (incrementally).
- Evicted when document changes again or after 10 minutes of disuse.

### 12.5 BETASTy and cross-module invalidation

When the build finishes compiling module A:
- If A succeeded, its `.tasty` is written.
- If A failed, its `.betasty` is written (under `-Ybest-effort`).

Either way, downstream modules' `pc` sessions see the new artifact on their next recompile. We hook the bundled plugin's compile-finished event to trigger `pc` invalidation for modules whose dependencies just rebuilt.

---

## 13. Classloader & versioning

### 13.1 Per-minor-version classloader

One `URLClassLoader` per Scala 3 minor version (3.3, 3.4, 3.5, …) used in the project. Each holds:

- `mtags_<scalaBinVer>.jar`
- `scala3-compiler_<scalaFullVer>.jar`
- `scala3-library_<scalaFullVer>.jar`
- `scala3-tasty-inspector_<scalaFullVer>.jar`
- dependencies (with their own versions matching the compiler)

Parents: IntelliJ platform classloader (so we can call into IntelliJ APIs).

### 13.2 ServiceLoader

`PresentationCompiler` is loaded via `META-INF/services/org.scalameta.metals.PresentationCompiler` from the mtags jar. MiMa keeps the interface binary-compatible across patch releases of the same minor version.

### 13.3 Version pinning

Per `research/05-macros.md` §4:

- LTS (3.3) and mainline (3.5) are kept on par — all `pc` fixes backport to LTS.
- We pin mtags to the module's Scala binary version.
- 3.3 is the floor. 3.2 users get a notification + no-op.

### 13.4 BETASTy caveat

The `.betasty` format is experimental and may change between patch versions. We do not parse it directly — `pc` does. Our only BETASTy-specific risk is flag-name or behavior changes, which are bounded by the Scala 3 release notes.

---

## 14. Explicit gaps & non-goals

### 14.1 Things we will not do (in 1.0 or ever)

- Replace the bundled PSI for Scala 3. (Earlier "shadow-PSI" idea — deferred indefinitely. The intercept-and-augment strategy does not need it.)
- Disable the bundled compiler-highlighting pass.
- Replace the parser, lexer, formatter, build import, run/debug pipeline.
- Support Scala 2. Ever.
- Provide a full shadow type system. We expose types via `PcTypeService` for opt-in callers; we do not swap `ScType`.

### 14.2 Things `pc` itself does not (yet) provide

| Capability | Status | Workaround |
|---|---|---|
| Full macro-annotation expansion on every keystroke | Partial | Read from `.betasty` / `.tasty` for already-compiled classes; show "expansion pending" for open files |
| `.betasty` for Scala 3.3 LTS | Not in all 3.3.x patch versions | Degrade gracefully — `pc` still works |
| Find-references from Java to Scala synthetic members | Partial | Java indexer may miss our synthetic members; mitigated by §9 |
| Type of an expression when the file has syntax errors | Partial | BETASTy within the file gives best-effort types; hover shows `pc`'s guess |

### 14.3 Things the bundled plugin does better

- Scala 2 modules. We don't touch them.
- Performance on syntactic features (formatting, folding, structure view). Bundled plugin is highly optimised.
- Refactorings that operate purely on PSI. They work on bundled PSI; we don't get in the way.
- Worksheets, REPL, debugger-evaluator. Bundled plugin owns.

### 14.4 Risks

| Risk | Likelihood | Mitigation |
|---|---|---|
| Bundled plugin's public API changes underneath us | Med | Pin to specific bundled-plugin versions in `plugin.xml`'s `<depends>`. CI against multiple versions. |
| `pc` API changes underneath us | Low | MiMa-enforced binary compat; pin mtags versions per module. |
| Performance regression from running `pc` alongside bundled | Med | Strict gating, debouncing, viewport-scoping. Measure early. |
| Diagnostic conflicts between bundled and our pass | Med | Conservative suppression rules; allow user to disable our additions. |
| `.betasty` format change between Scala 3 patches | Med | Don't parse it directly; let `pc` handle it. |
| PSI identity stability under lazy materialisation | Med | Stable element identity per `(file, range, kind)`; only side-data is versioned. |
| Stub-index population during project import | Low | Bundled parser's stubs stay authoritative at import time; our `PcSymbolIndex` overlays later. |
| Classloader isolation between mtags versions and IntelliJ runtime | Med | One URLClassLoader per Scala 3 minor version, parented to the platform classloader. Solved pattern (Metals). |
| Per-module `ProblemHighlightFilter` ordering | Med | Filter checks the file's `Module` + our opt-in flag; bundled compiler pass stays on for Scala 2 modules. |
| `pc` does not fully expand Scala 3 macro annotations on every keystroke | Med | Read from `.betasty`/`.tasty` for already-compiled classes; show "expansion pending" for open files. |

### 14.5 Scope rationale — why this is bounded work

A literal "replace the bundled Scala plugin for Scala 3" would be in the hundreds-of-person-months range: the inventory in `research/10-extension-points.md` counts 173 `localInspection` EPs, 70 `intentionAction` EPs, 13 `referencesSearch` EPs, 9 `renamePsiElementProcessor` EPs, 34 stub-index keys, and ~25 sub-packages of refactoring code in the bundled plugin. Reimplementing that surface area, supporting both Scala 2 and Scala 3, and integrating with every downstream consumer of PSI (refactorings, debugger, worksheet, structural search, conversion, run configurations) is what would make a from-scratch replacement a 20-engineer project.

Metallurgy's scope removes each of those cost categories by constraint:

| Constraint (decision) | What it removes |
|---|---|
| Scala 3 only (ADR 0001) | All Scala 2-specific code paths stay in the bundled plugin. Macro reflection, Scala 2 parser, kind-projector, simulacrum, derevo, etc. are not our problem. |
| 3rd-party plugin, opt-in per module (ADR 0006) | No need to remove or disable the bundled plugin. We augment when a module is opted in; the bundled plugin keeps working for Scala 2 modules, non-opted-in Scala 3 modules, and as a fallback. |
| Delegate language facilities to `pc` | We do not reimplement type inference, implicit search, macro expansion, or match-type reduction. The ~17 000 LOC of hand-rolled `ScType` machinery documented in `research/02-type-system-resolve.md` is what we are *replacing*, not maintaining. |
| Piggyback on IntelliJ for build/compile | We do not need our own BSP server, compile server, nailgun, or JPS. The bundled plugin + sbt/BSP import already produces classpaths, `target/` directories, and `.tasty` files. We consume them. |
| Editor mechanics stay syntactic | Format / fold / surround / smart-enter / typed / backspace / copy-paste / live templates are all purely syntactic and stay on the bundled parser (`research/07-code-insight.md` §7). |
| Intercept-and-augment, not replace (ADR 0004) | No shadow-PSI to satisfy every downstream consumer of `Sc*` interfaces. Each feature is one extra EP registration that runs alongside the bundled one. |

After applying these constraints, the actual scope is roughly:

1. A `pc` session manager that maps `Module → PresentationCompiler` and feeds it real classpaths from IntelliJ.
2. A fetch-on-demand mtags layer (ADR 0003).
3. A handful of EP implementations that rank ahead of (or alongside) the bundled plugin's, only for opted-in modules.
4. A diagnostic bridge from `pc.Diagnostics` → IntelliJ `Annotation` / `HighlightInfo`.
5. A TASTy / SemanticDB-backed symbol → PSI resolver for navigation, find-usages, and rename.

That is roughly 3–5 engineer-years to a usable 1.0, with most of the user-visible value landing in the first ~6 months (the CompilerType hijack + completion augmentation, which is exactly Phase 1).

---

## 15. Phased delivery

This time the phases are *much* smaller than the previous "replace" design, because nothing needs to be ripped out first. Each phase is independently useful.

### Phase 0 — Skeleton (days–weeks)

- Gradle IntelliJ Plugin 2.x project.
- `plugin.xml` declaring `<depends>org.intellij.scala</depends>`.
- `ModuleDetectionService` that identifies Scala 3 modules.
- `PcSessionManager` skeleton (loads mtags via ServiceLoader, no real use yet).
- Notification on first Scala 3 module detected: "Metallurgy is installed; nothing is enabled yet."

**Exit criterion:** Plugin loads in a dev IDE with the bundled Scala plugin present; the notification fires.

### Phase 1 — Hijack `CompilerType` (weeks)

The smallest possible intercept. See § 5 for the full design.

- Implement `PcSessionManager` for real (loads mtags per module via ServiceLoader, builds classpath from bundled plugin's `ScalaCompilerConfiguration`).
- Subscribe to `CompilerType.Topic`. On `onCompilerTypeRequest(e)` for a Scala 3 element, call `pc.typeAt(uri, offset, docText)` and set `CompilerType(e) = Some(tpeString)`.
- Add a project setting **`MetallurgySettings.setEnabled(module, true)`** persisted in `.idea/metallurgy.xml`, surfaced under `Settings | Languages & Frameworks | Metallurgy`. Default off. First detection of a Scala 3.5+ module triggers a notification prompting the user to enable (see ADR 0006 — we do not reuse `isUseCompilerTypes`).

**Exit criterion:** On a project with a transparent-inline-heavy library (e.g. circe, quill), hover / inlay / completion on a transparent-inline call site shows the real type instead of `Any`, with latency under 50ms p95. The bundled scalac plugin can stay installed and produce its stdout markers — our listener wins the race.

This phase alone is a complete, shippable plugin. Everything after is gravy.

### Phase 2 — Completion augmentation (1–2 months)

- One `completion.contributor` EP registered with `order="after scalaCompletionContrubutor"`.
- Trigger logic from §6.2.
- Deduplication.
- Symbol search via IntelliJ index exposed to `pc`.

**Exit criterion:** On a project with transparent-inline-heavy code (e.g. using `quill`, `tapir`, `circe`), completions return correct items where the bundled plugin returns none or wrong ones. No regressions on Scala 2 modules.

### Phase 3 — Diagnostics augmentation (2–3 months, overlapping Phase 2)

- `externalAnnotator` for "add missing" (§7.2).
- `problemHighlightFilter` for "suppress wrong" (§7.3), conservative.
- Quick fixes for top-10 diagnostic codes.
- BETASTy scalac flags added to opted-in modules.

**Exit criterion:** All `CompilerDiagnosticsTest_3.scala` bundled tests still pass. New tests showing bundled false-positives being suppressed. New tests showing bundled-missed errors being added.

### Phase 4 — Hover, inlay hints, parameter info (1–2 months)

- `psiTargetProvider order="before …"` for hover.
- `codeInsight.declarativeInlayProvider` for type hints, implicit params, inline summaries.
- `parameterInfoEnhancer`.
- `PcTypeService` exposed as a project service.

**Exit criterion:** Hovering a transparent-inline call shows the real type. Inlay hints show implicit parameter names. Parameter info is correct for `pc`-resolved overloads.

### Phase 5 — Synthetic members + macro-expansion view (1–2 months)

- `syntheticMemberInjector order="last"` backed by `pc`.
- `PcExpansionGutterProvider` showing macro/inline expansion.
- `HighlightVisitor` for semantic tokens.

**Exit criterion:** `MyType.derived` is completable, hoverable, and find-usages-able. Macro expansion view works on `pc`-expanded transparent-inline calls.

### Phase 6 — Navigation & find-usages augmentation (1–2 months)

- `gotoDeclarationHandler order="before …"`.
- `externalReferenceSearcher` backed by `pc.findReferences`.
- `renamePsiElementProcessor order="before …"` using `pc`.

**Exit criterion:** Go-to-declaration works on references the bundled plugin can't resolve. Find-usages returns sites the bundled plugin misses (transparent-inline call sites, synthetic member uses).

### Phase 7 — Polish & performance (ongoing)

- Profile and optimise.
- Add per-feature toggles in settings.
- Cover more diagnostic codes with quick fixes.
- Community-driven feature requests.

**Total to a useful 1.0 (Phases 0–4):** ~6 months part-time / ~3 months full-time for one strong engineer. No risky PSI-replacement work is on the path. **Phase 1 alone delivers a usable plugin** in a few weeks.

---

## 16. References

### Companion documents in this project

- [`../CONTEXT.md`](../CONTEXT.md) — Glossary of domain terms used across this design and the ADRs.
- [`adr/0001-scala-3.5-floor.md`](./adr/0001-scala-3.5-floor.md) through [`adr/0007-strict-intellij-range-with-reflection-fallback.md`](./adr/0007-strict-intellij-range-with-reflection-fallback.md) — Architectural decisions.
- [`research/01-psi-parser-lexer.md`](./research/01-psi-parser-lexer.md) — PSI / parser / lexer / stubs inventory.
- [`research/02-type-system-resolve.md`](./research/02-type-system-resolve.md) — `ScType`, resolve pipeline, implicit search.
- [`research/03-highlighting-annotators.md`](./research/03-highlighting-annotators.md) — HighlightingMode system + annotator stack.
- [`research/04-compiler-server.md`](./research/04-compiler-server.md) — Compile server, nailgun, JPS.
- [`research/05-macros.md`](./research/05-macros.md) — Macro evaluator + reflect expansion.
- [`research/06-tasty-reader.md`](./research/06-tasty-reader.md) — Existing TASTy reader + BETASTy analysis.
- [`research/07-code-insight.md`](./research/07-code-insight.md) — Completion / refactoring / navigation / find-usages.
- [`research/08-build-integration.md`](./research/08-build-integration.md) — BSP / sbt / scala-cli integration.
- [`research/09-scala3-feature-gaps.md`](./research/09-scala3-feature-gaps.md) — Feature-by-feature audit (21 features).
- [`research/10-extension-points.md`](./research/10-extension-points.md) — Full plugin.xml + EP inventory.
- [`research/11-canonical-macro-acceptance-tests.md`](./research/11-canonical-macro-acceptance-tests.md) — 80+ YouTrack tickets + 10 library patterns; source for Phase 1 acceptance corpus.

### External

- **BETASTy / Best-Effort Compilation** — official docs: <https://www.scala-lang.org/api/3.5.2/docs/docs/internals/best-effort-compilation.html>
- **Metals presentation compiler** — `mtags` subproject: <https://github.com/scalameta/metals>
- **An intro to the Scala Presentation Compiler** (Chris Kipp) — <https://www.chris-kipp.io/blog/an-intro-to-the-scala-presentation-compiler>
- **The Scala 3 `interactive` package** (upstream) — <https://github.com/scala/scala3/tree/main/compiler/src/dotty/tools/dotc/interactive>. Key files: `InteractiveDriver.scala` (the driver itself, owns the per-uri `CompilationUnit`s), `Interactive.scala` (`pathTo`, `enclosing`, `sourceSymbols` — the core "what's at this offset?" API), `Completion.scala` (the completion algorithm), `InteractiveCompiler.scala` (frontend-only compiler).
- **Scala 3 `PresentationCompiler` API** — `dotty.tools.pc.PresentationCompiler` (the Metals wrapper) and `scala.tools.tastyinspector`.
- **IntelliJ Platform SDK** — <https://plugins.jetbrains.com/docs/intellij/>
- **Scala Space podcast — "The Future of Scala IDEs"** — background context for the architecture: <https://www.youtube.com/watch?v=SlPDmwhxeok>

### Existing plugin sources referenced

- Bundled Scala plugin: <https://github.com/JetBrains/intellij-scala>
  - `pluginXml/resources/META-INF/plugin.xml`
  - `scala/scala-impl/resources/META-INF/scala-plugin-common.xml`
  - All other locations cited throughout `research/`.
