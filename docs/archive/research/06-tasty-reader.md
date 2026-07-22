# 06 — TASTy Reader / BETASTy

Domain report for the Metals-`pc` Scala 3 rewrite. Covers the in-tree
`tasty-reader` module (what it actually reads today), the TASTy and
BETASTy formats, and the proposed seam that turns post-typer compiler
artifacts into the source of truth for **error highlighting, semantic
info, resolve, find-usages, rename, and synthetic-PSI backfill**.

All `path:line` references are relative to the repo root.

---

## 1. The current `tasty-reader` module

### 1.1 Build wiring and shape

`tasty-reader` is an sbt sub-project built with **Scala 3.7.4**
(`build.sbt:355`, `project/dependencies.scala:10`), depending only on
`tasty-core_3` (`build.sbt:366`, `dependencies.scala:123`). It is not
an IntelliJ module — `intellijMainJars := Seq.empty` (`build.sbt:363`)
— and depends on the language-utils projects but nothing PSI-related.
The wider plugin pulls it in via `scala-impl` (`build.sbt:392`:
`tastyReader % "test->test;compile->compile"`).

The module exposes exactly one entry point:

```scala
class TastyImpl {
  def read(bytes: Array[Byte]): Option[(String, String, CompilerOptions)]
}
```
(`scala/tasty-reader/src/TastyImpl.scala:9`). The tuple is `(sourceFileName,
decompiledOutlineText, kindProjectorFlag)`. There is **no API to obtain
types, symbols, or positions** — `CompilerOptions` is a one-bit struct
(`CompilerOptions.scala:3`).

### 1.2 File-by-file

| File | Role |
|---|---|
| `package.scala:1` | Type aliases `Name = SimpleName = TermName` — everything is a string. |
| `TermName.scala:7` | `TermName` is `AnyVal` wrapping `String`. `SignedName` discards the signature: `original.value + "[...]"` (`:21`). No erasure, no `Signature.ParamSig`. |
| `TastyHeader.scala:7` | Case class `(uuid, major, minor, experimental, toolingVersion)`. Never re-emitted. |
| `HeaderReader.scala:13` | Replicates `dotty.tools.tasty.TastyHeaderUnpickler`. **Rejects `fileMajor <= 27`** (`:19`), i.e. Scala 3.0+ only. Pre-TASTy Dotty (0.27 and earlier) is skipped via `UnpickleException` in `TastyImpl.scala:17`. |
| `NameTable.scala:12` | `ArrayBuffer[TermName]` keyed by `NameRef.index`. |
| `NameTableReader.scala:12` | Walks the name section. **`readSignedRest` is a stub** (`:35-41`): `paramsSig` is never read and the signature is hard-coded as `"[...]"`. This is the most consequential simplification: the reader cannot distinguish overloaded methods by signature. |
| `Node.scala:6` | Mutable, lazy-children tree node. Carries `addr`, `tag`, `names`, `children`, `position: () => Option[Int]`, sibling links, `refTag/refName/refPrivate` for symbol refs, `isSharedType`. |
| `NodePrinter.scala:25` | Debug renderer that mimics `-Yprint-tasty` (`:35`). Used by the internal action only. |
| `TreeReader.scala:13` | The actual byte-level walker. Skips a lot: `RENAMED` body comment-only (`:58-59`), `RETURN/HOLE` ignored (`:64-65`), `TYPELAMBDAtype` reads only the first child (`:66-70`), `PARAMtype` skipped (`:71-72`). Position section is read only when `minorVersion >= 6` (`:149`); earlier files get `Map.empty`. Positions are decoded as `Map[Addr, Int]` carrying **only the start offset** — end offset and point offset are read and discarded (`:184-186`). |
| `TreePrinter.scala:29` | A 1269-line Scala-source outline emitter, with hundreds of ad-hoc pattern matches for given/implicit-class/value-class detection (`:41-89`). |
| `CompilerOptions.scala:3` | One boolean: `kindProjector` (set by detecting `-Xkind-projector`). |

### 1.3 What it supports

TASTy format: **major 28+** (Scala 3.0+). The minor-version gate at
`TreeReader.scala:149` means positions are read for Scala 3.0.x minor ≥ 6
and all later; earlier minors silently lose positions.

The reader is **statically incomplete**: signatures are dropped, several
tree tags are ignored, shared types are inlined rather than de-duplicated
(`TreeReader.scala:104-108`, `_README..md:2`), `StackOverflowError` is
caught and treated as "not a TASTy file" (`TastyImpl.scala:18`, SCL-21005
/ SCL-21080). It is single-pass, with no symbol table — references
(`TYPEREFsymbol`, `TERMREFsymbol`, `TERMREFdirect`, `TYPEREFdirect`)
record only `refTag`, `refName`, and `refPrivate` (`TreeReader.scala:112-121`).

### 1.4 Where it is consumed

The reader has **one seam**: `org.jetbrains.plugins.scala.tasty.TastyReader`
(`scala/scala-impl/src/org/jetbrains/plugins/scala/tasty/TastyReader.scala:5`)
wraps `TastyImpl`. From there it flows into:

1. **`TastyDecompiler`** (`scala/scala-impl/src/org/jetbrains/plugins/scala/tasty/TastyDecompiler.scala:13`),
   a `BinaryFileDecompiler` registered against `.tasty` files via
   `TastyFileViewProviderFactory.scala:9` and `TastyFileType.scala`.
2. **`DecompilationResult.sourceNameAndText`** (`DecompilationResult.scala:131`),
   cached into a VFS `FileAttribute` (`:166`) and `SoftReference`
   user-data (`:60`), keyed by `(file, timeStamp)`.
3. **`ScClsFileViewProvider`** (`ScClsFileViewProvider.scala:42`), which
   feeds `decompilationResult.sourceText` as the *content* of a synthetic
   Scala 3 PSI file. The normal parser then runs on the **outline text**,
   producing PSI for navigation/completion over libraries.
4. **`ShowDecompiledTastyRawInternalAction`**
   (`ShowDecompiledTastyRawInternalAction.scala:35`) — internal action,
   prints raw nodes via `NodePrinter`.
5. **`ScalaFileImpl.compilerOptions`** /
   **`ScalaFile.compilerOptions`** surface the `kindProjector` bit to
   type-element parsing (`ScalaFile.scala:17`, `ScalaFileImpl.scala:34`).

### 1.5 Experimental? Live?

It is **live and on the critical path** for any user navigating into a
Scala 3 library: every `.tasty` file in a dependency has its outline
parsed on demand and re-parsed by the Scala 3 parser to build PSI. It
is, however, limited to that one job — *outline decompilation for
navigation*. Nothing in the plugin currently uses `tasty-reader` for
type information, symbol identity, or positions.

There is a second, parallel path that uses the **real compiler**:
`scala/semantic-tests/test/org/jetbrains/plugins/scala/semantic/Decompiler.scala:20`
spins up a real `dotc.Driver` with `-YretainTrees -fromTasty` and uses
`PartialTASTYDecompiler` + `QuotesImpl` to produce Syntax-highlighted
source. This is the canonical Scala 3 way to read TASTy. It lives only
in test code today; `scala3-tasty-inspector` is a test-only dependency
(`build.sbt:381`, `dependencies.scala:124`).

---

## 2. TASTy format background

### 2.1 TASTy

TASTy (Typed Abstract Syntax Tree) is the binary, post-typer serialization
of the Scala 3 compiler's `untpd.Tree → tpd.Tree` result. The compiler
writes one `.tasty` file alongside each `.class` (under
`-YemitTasty`, which is on by default). The format is documented in
`tasty-core`'s `TastyFormat.scala` (referenced from
`scala/tasty-reader/src/HeaderReader.scala:3` and `TreeReader.scala:4`).

What TASTy gives an IDE for free (currently hand-rolled in the plugin):

| Information | Hand-rolled today | TASTy source |
|---|---|---|
| Type of every expression | `Typeable.type()` + ~700-line `TypeInference.scala` (see `02-type-system-resolve.md`) | Every `Term` node carries its `TypeTree`. |
| Symbol identity / owner chain | `ScalaPsiUtil` + `ScType.designator` chain | `TERMREFsymbol`/`TYPEREFsymbol`/`METHODTYPE`/`TYPEPARAM` — named, addressed, owner-linked. |
| Resolved reference target | `ScReferenceImpl.bind()` → `ReferenceExpressionResolver.scala:72` (~900 lines) | Every `TERMREF`/`TYPEREF` is a pointer to a symbol address. |
| Positions | Parser `ASTNode` range + post-facto position from text | Dedicated Positions section (`TreeReader.scala:152`) with `(start, end, point)` triples. |
| Inlined trees (macro expansions) | `ScalaMacroEvaluator` + 6 shapeless emulators (`05-macros.md` §1.1) | `INLINED` nodes with `call` reference to the inlinee. |
| Synthetic members | 11 `SyntheticMembersInjector` impls (`SyntheticMembersInjector.scala:19`, EP `org.intellij.scala.syntheticMemberInjector`) | `VALDEF`/`DEFDEF` with `SYNTHETIC` modifier, generated by typer. |
| Doc comments | `ScDocComment` over PSI | `DOC`/`SourceFile` attributes carry raw text in some builds. |
| Implicit search results | `ImplicitCollector.scala` (see `02-type-system-resolve.md` §3) | `INLINED` + `INLINE` flags show the path the compiler picked. |
| Dispatch (overloaded resolution) | `Signature` + `TermSignature` matching (`Signature.scala:103`) | `SIGNED` names with full `paramsSig` (`NameTableReader.scala:35` — currently discarded). |

### 2.2 BETASTy

**BETASTy ("Best-Effort TASTy")** is a Scala 3.5+ feature —
<https://www.scala-lang.org/api/3.5.2/docs/docs/internals/best-effort-compilation.html>
— that lets the compiler emit a TASTy-like artifact **even when the source
has errors**, so that downstream compilation (and `pc`) can still see
useful types and symbols from upstream modules that don't compile. This is
the core mechanism enabling cross-module error recovery in the IDE.

Two compiler flags:

- **`-Ybest-effort`** — forces compilation through the typer *regardless of
  errors*, then writes a `.betasty` file to `META-INF/best-effort/` in the
  output jar. The file uses a TASTy-like grammar extended with an
  `ERRORtype` constructor to represent untypeable parts.
- **`-Ywith-best-effort-tasty`** — when reading from classpath, accepts
  `.betasty` files. If one is read, the compiler is restricted to frontend
  phases only.

The compiler pipeline (paraphrased from the official docs):

```
Parser → (always) → Typer ─────────────────────────┐
                                                   │
                            with errors            │   no errors
                                 │                 │
                                 ▼                 ▼
                      [stop after frontend]   [continue normally]
                                 │                 │
                                 └──►  Pickler writes .betasty (and/or .tasty)
```

Why this matters for the IDE: **without** BETASTy, a module that fails to
compile emits no TASTy, and downstream modules see stale or empty types
for its symbols. In an IDE the user is constantly in a "doesn't compile
yet" state; until 3.5, `pc` could do best-effort *within a file* but had
no good answer *across modules*. With BETASTy, `pc` runs with
`-Ywith-best-effort-tasty` and sees upstream symbols even from broken
dependencies — partial types where possible, `ERRORtype` placeholders
where not.

**Format stability.** The docs are explicit: *"no compatibility rules
are defined for now, and the specification may change between the patch
compiler versions."* The plugin must pin to a Scala 3 patch version per
module and let `pc` parse the file — we should never parse `.betasty`
directly.

---

## 3. Seam recommendations

### 3.1 Replace `tasty-reader` with `scala3-tasty-inspector`

The in-tree reader is a 1500-line re-implementation of
`tasty-core`'s `TastyUnpickler`/`TastyReader`. It is incomplete by
design (`_README..md:2`: "we skip some nodes"), hard-codes signatures
as `"[...]"` (`NameTableReader.scala:35-41`), throws away position
end/point (`TreeReader.scala:184-186`), and is brittle enough to
require a `StackOverflowError` catch (`TastyImpl.scala:18`).

**Recommendation.** Replace it as the source of truth with the
official `scala.tasty.inspector.TastyInspector` (already a test
dependency at `build.sbt:381`). Promote that dependency to
`scala-impl` (or a new `scala3-tasty` sub-module), and re-implement
`TastyDecompiler.decompile`
(`scala/scala-impl/src/org/jetbrains/plugins/scala/tasty/TastyDecompiler.scala:14`)
on top of the existing test-only `Decompiler` class
(`scala/semantic-tests/test/org/jetbrains/plugins/scala/semantic/Decompiler.scala:20`),
which already does the right thing: `dotc.Driver` +
`PartialTASTYDecompiler` + `-YretainTrees -fromTasty`
(`Decompiler.scala:22-27`). Keep the current `HeaderReader` (35 lines,
pure bytes) as a fast header-only fallback.

**Classloader & version-skew.** `TastyInspector` reflects into
`dotty.tools.dotc.core.Contexts`; the inspector classloader must match
the Scala 3 version that wrote the bytes. The plugin already solves
this for the compile server
(`scala/compiler-integration/src/org/jetbrains/plugins/scala/compiler/`)
by spawning a per-SDK process; the same pattern (long-lived `pc`
process per `ScalaSdk`) is the right shape for TASTy inspection of
library artifacts. `Decompiler.scala:25`
(`ctx.setSetting(classpath, ...)`) is process-local and must run with
the SDK's `scala3-compiler_3` jar, **not** the plugin's bundled one
(`dependencies.scala:121`). For `.tasty` files inside JARs, read bytes
via `VirtualFile.contentsToByteArray` (as `TastyDecompiler.scala:15`
already does) and feed them to a process-local inspector.

### 3.2 Side-table: `(file, offset) -> Symbol+Type+InlinedFrom`

The redesign proposes one new data structure, **`TastySideTable`**:

```scala
final case class TastySideTable(
  fileVersion: Long,                       // document modification stamp
  symbols: Map[Int, TastySymbol],          // start offset → symbol info
  types:  Map[Int, ScType],                // start offset → resolved type
  inlineSites: Map[Int, InlineInfo],       // start offset → inlinee symbol
  diagnostics: Seq[TastyDiagnostic]        // compiler-reported errors with ranges
)
```

Built by the Metals `pc`/`dotc` after every save (or idle pause) and
stored per `ScalaFile` (keyed by `getVirtualFile` +
`getContextModificationStamp`, `ScalaFile.scala:50`). The IntelliJ side
only consumes it. Each row maps onto an existing IntelliJ call site:

| Side-table entry | IntelliJ API call | Today's implementer |
|---|---|---|
| `symbols(offset)` | `ScReference.resolve()` (`ScReferenceImpl.scala:13`) | `ReferenceExpressionResolver.resolve` (`:72`, ~900 lines) |
| `symbols(offset)` | `ScStableCodeReference.bind()` (`StableCodeReferenceResolver.scala:77`) | same processor family |
| `types(offset)` | `ScExpression.type().getOrElse(...)` (`Typeable`) | `TypeInference.scala` + 30+ `ScXxxType` classes |
| `inlineSites(offset)` | "go to inline definition" / macro expansion view | `ScalaMacroEvaluator` (`05-macros.md` §1) |
| `types(offset)` for hover | `ScalaDocumentationProvider` | same |
| `diagnostics` | `ScalaAnnotator.annotate` (`ScalaAnnotator.scala:41`) | 30-element annotator fan-out (`annotator/element/Sc*Annotator.scala`) |
| `symbols(symId).members` (synthetic) | `ScTypeDefinition.syntheticMembers` (`ScTemplateDefinition.scala:51`, `SyntheticMembersInjector.scala:82`) | 11 injectors under `lang/psi/impl/toplevel/typedef/` |

### 3.3 BETASTy: enabling it in the build

Two scalac flags on opted-in Scala 3 modules:

- `-Ybest-effort` — produces `.betasty` even when the source has errors.
- `-Ywith-best-effort-tasty` — lets `pc` (and downstream modules) consume
  upstream `.betasty` files from the classpath.

These are added via the bundled plugin's existing `ScalaCompilerConfiguration`
API; no new build infrastructure is required. `pc` automatically uses these
settings when fed the module's classpath and options.

What BETASTy specifically buys:

- **Cross-module type recovery when an upstream module has errors.** Module
  A fails to compile → `.betasty` is emitted → Module B's `pc` session sees
  A's symbols with `ERRORtype` placeholders for untypeable parts. Without
  BETASTy, B sees stale or empty types for A's symbols.
- **Symbol/type info for "doesn't compile yet" intermediate states** — the
  common case in IDE editing.

What BETASTy does **not** buy:

- It is not a source of untyped-tree positions. That idea is a conflation
  with `-YretainTrees`, an unrelated debugging knob.
- It does not change single-file best-effort typing; `pc` has always done
  that. BETASTy extends best-effort across module boundaries.

---

## 4. Where it plugs in

### 4.1 `Annotator` / `HighlightingPass` data flow

```
Editor ──save/pause──▶ ScalaFile ──▶ Metals pc process ──▶ TastySideTable
   ▲                  (modified)     (dotc -YretainTrees     keyed by
   │                                  -fromTasty -Xsemanticdb) (file, modStamp)
   │                                                        │
   └──────── read ────── TastyAnnotator ◀───────────────────┘
                         (Scala 3 only; replaces type-aware branch
                          of ScalaAnnotator.scala:50)
```

`ScalaAnnotator.annotate` already branches on
`ScalaHighlightingMode.isShowErrorsFromCompilerEnabled`
(`ScalaAnnotator.scala:51`). When that flag is on, the type-aware
plugin path is disabled; we extend the same branch to read from
`TastySideTable.diagnostics` directly. The 30-file
`annotator/element/Sc*Annotator.scala` family is bypassed for Scala 3
files in this mode.

### 4.2 `ScReferenceElement.resolve()`

The current resolve path:
`ScReferenceImpl.resolve` (`ScReferenceImpl.scala:13`) →
`bind()` (`:35`) → `multiResolveScala(false)` →
`ReferenceExpressionResolver.resolve(...)` (`ReferenceExpressionResolver.scala:72`)
→ processor walk through scopes, imports, implicits (~800 lines).

The TASTy-side replacement is a single lookup:

```scala
override def resolve(): PsiElement =
  TastySideTable.forFile(getContainingFile)
    .flatMap(_.symbols(nameId.getTextRange.getStartOffset))
    .map(_.toPsiElement(getProject))
    .orNull
```

`toPsiElement` walks the symbol's owner chain and uses the existing
`JavaPsiFacade.findClass` / `ScPackage.findObject` infrastructure. The
hand-written processor walk stays in place for **Scala 2** and as a
**fallback** when the side-table is stale — `None` from the table falls
through to the old resolver.

### 4.3 Find Usages

The current external-search EPs (both flagged "Replace — Metals PC" in
`10-extension-points.md:54-55`):

- `org.intellij.scala.findUsages.externalReferenceSearcher`
  → `CompilerIndicesReferencesSearcher`
  (`CompilerIndicesReferencesSearcher.scala:37`).
- `org.intellij.scala.findUsages.externalInheritorsSearcher`
  → `CompilerIndicesInheritorsSearch`
  (`CompilerIndicesInheritorsSearcher.scala:21`).

Both consume `ScalaCompilerReferenceService.usagesOf(target)`
(`ScalaCompilerReferenceService.scala:329`) which reads a JPS-built
backward-reference index — essentially a custom SemanticDB substitute
that requires a full project build. Replacing it with TASTy
inspection: every reference site in a Scala 3 source file is a
`TERMREF`/`TYPEREF` node with a `(start, end)` position and a target
symbol address — exactly the data the side-table already has. Find
Usages becomes a project-wide scan of per-file side-tables for rows
pointing at the target symbol. No backward-ref index build step; no
separate compilation pass; works the moment a file is type-checked.
For non-project (library) code, the same inspector that decompiles
`.tasty` files indexes references there.

### 4.4 Rename

Today's flow goes through
`org.intellij.scala.scalaElementToRenameContributor` EP
(`10-extension-points.md:41`, "Replace for Scala 3"). With the
side-table, the set of textual occurrences to rename = every offset
where `symbols(offset).target == element`. The new name is validated
against Scala 3 identifier rules (existing `ScalaNamesValidator.isIdentifier`,
already used in `ScReference.scala:63`). The tricky part —
`RenameScalaVariableProcessor` skipping synthetic methods
(`RenameScalaVariableProcessor.scala:109`) — becomes trivial: synthetic
`apply`/`copy`/`unapply` symbols have `isSynthetic == true` in TASTy
(the `SYNTHETIC` modifier, `Node.scala:22`) and the renamer simply
ignores them.

### 4.5 PSI backfill — synthetic members, expanded macros

`SyntheticMembersInjector.inject(source)` (`SyntheticMembersInjector.scala:82`)
runs 11 hand-written injectors to materialise PSI for case-class
`apply`/`copy`/`unapply`, enum `valueOf`, derives proxies, and
library-specific expansions (circe, monocle, simulacrum, derevo,
scio, scalaz-deriving, estatico-newtype — `scala-plugin-common.xml:33-44`).

With TASTy, every synthetic member is already in the `.tasty` file
with `SYNTHETIC` flag set. The replacement is a single generic
`TastySyntheticMembersInjector` that: (1) asks the side-table for the
file's own symbol's members; (2) filters to `isSynthetic == true`;
(3) materialises each as a `ScFunction` / `ScTypeDefinition` via
`ScalaPsiElementFactory.createMethodWithContext` (already used by the
current injectors, `SyntheticMembersInjector.scala:96`); (4) sets
`syntheticNavigationElement`/`syntheticContainingClass` exactly as
today (`SyntheticMembersInjector.scala:99-100`).

All 11 hand-written injectors stay in place for Scala 2 / macros the
compiler doesn't expand in PC mode; for Scala 3 the TASTy-driven
injector becomes authoritative.

---

## 5. Performance and storage

### 5.1 Lifetime and invalidation

`TastySideTable` lives in a `@Service(Level.PROJECT)` cache keyed by
`(VirtualFile, ScalaFile.getContextModificationStamp)`. The latter
(`ScalaFile.scala:50`) is the existing cache-buster for PSI edits —
bumped by `incContextModificationStamp()` on every structural change.
On edit, the affected file's table is dropped and a recompute is
scheduled with a debounce (matching the pattern in
`ScalaLocalVarCouldBeValPassFactory.scala:14`).

Library files (read-only, identified by `HighlightingAdvisor.scala:64`)
get a stable table computed once per VFS stamp, mirroring
`DecompilationResult.getFromFileAttribute` (`DecompilationResult.scala:143`)
which already caches decompiled text in a VFS `FileAttribute`.

### 5.2 Where to compute

Two options, both needed:

1. **`pc` process emits TASTy chunks back to the IDE.** The Metals
   presentation compiler already runs out-of-process; extend its
   protocol to return a compact binary form of the side-table (a flat
   array of `(offset, symbolId, typeTag)` plus a symbol dictionary).
   Estimated size: ~50–200 bytes per source line, on the order of
   SemanticDB.
2. **Read `.tasty` files from `target/` directories.** When the user
   has built their project, `.tasty` files exist on disk next to
   `.class`. The IDE can read them directly via the official
   `TastyInspector` in a worker thread, with no `pc` round-trip — same
   pattern as `TastyDecompiler.scala:15`.

Option 2 is for stable library code (fast, no compiler needed, updated
on build); option 1 is for the currently-edited file and its open
dependencies (always fresh, drives live highlighting).

For persistence across sessions: store the binary side-table next to
the existing `DecompilationResult` `FileAttribute`
(`DecompilationResult.scala:63`). A 200 KB file produces ~10–40 KB of
side-table; well within VFS attribute limits.

---

## 6. Comparison: what % of resolve/type/implicit problems does TASTy solve?

Approximate, based on the surfaces inventoried in
`02-type-system-resolve.md`, `05-macros.md`, and `10-extension-points.md`:

| Problem class | Hand-rolled LOC today | TASTy/BETASTy coverage | Notes |
|---|---|---|---|
| Reference resolve | ~3 000 (`ReferenceExpressionResolver` + processors) | **~95 %** | TASTy carries every `TERMREF`/`TYPEREF` target. Gap: dynamic invocation (`DynamicTypeReferenceResolver` EP, `scala-plugin-common.xml:13`). |
| Type inference | ~7 000 (`types/`, `TypeInference`, `ScMatchType` reducer) | **~90 %** | TASTy has fully inferred types; BETASTy extends this across modules with errors via `ERRORtype`. Loss: live typing before save. |
| Implicit search | ~2 500 (`ImplicitCollector`, shapeless emulators) | **~80 %** | TASTy records the chosen implicit via `INLINED`; failed candidates come from `pc` diagnostics. |
| Overload resolution | ~1 500 (`Signature`/`TermSignature`) | **100 %** | Today's reader discards signatures (`NameTableReader.scala:35-41`); inspector restores them. |
| Macro expansion (Scala 3 inline/quotes) | ~600 (`05-macros.md` §2) | **100 %** | TASTy carries inlined bodies; BETASTy extends across modules with errors. |
| Macro expansion (Scala 2 reflect) | ~2 000 (`05-macros.md` §1) | **0 %** | Out of scope — Scala 2 has no TASTy. |
| Synthetic members (case class, enum, derives) | ~1 500 (11 injectors) | **~90 %** | Library-specific expansions (circe/monocle) still need the EP unless derived via `Mirror`. |
| Pattern matching exhaustiveness | ~1 000 | **100 %** via diagnostics | Not in TASTy per se, but emitted by `dotc`. |
| Find Usages across project | ~1 500 (JPS index) | **~95 %** | Same data as resolve; loses dynamic dispatch and Java-side refs. |
| Documentation (Scaladoc) | ~800 | **~30 %** | TASTy carries `DOC` only for some builds. |

**Overall: of the ~17 000 LOC the plugin dedicates to Scala 3 type-aware
logic today, TASTy + BETASTy + `pc` diagnostics can authoritatively
answer roughly 85–90 %.** The remaining 10–15 % is split between Scala 2
(no TASTy), dynamic invocations, in-editor live typing before save, and
Scaladoc rendering — all of which keep their current implementations.

The first concrete wins, in priority order:

1. **Decompiler correctness.** Replace `TastyImpl` with
   `TastyInspector`-based decompilation (`Decompiler.scala:20` shows
   the pattern). Fixes SCL-21005/SCL-21080 (the `StackOverflow`
   catches in `TastyImpl.scala:18`) and the dropped signatures in
   `NameTableReader.scala:35`.
2. **Synthetic members** via a single TASTy-driven
   `SyntheticMembersInjector` — drops ~1 500 LOC and the per-library
   EPs.
3. **Reference resolve + type info** via the side-table, initially as
   fast-path with the existing resolver as fallback — the
   `referenceExtraResolver` EP (`scala-plugin-common.xml:22`) is the
   clean injection point.
4. **Find Usages** via the side-table scan, replacing the JPS
   backward-ref index for Scala 3 modules.
