# Neutral parser-pending lifecycle

## Question

How can an active Scala file remain verbatim, non-stub-bearing, and free of false semantic findings while its exact
parser capability is prepared, then change once to the deterministic stub-bearing dialect without stale PSI, duplicate
index entries, lost editor state, or an artificial dumb-mode interval?

## Decision

Use two unrelated languages and one module-scoped capability epoch:

- `Scala3ParserPendingLanguage` is a neutral language with no Scala base language or Scala marker interfaces. It owns
  an associated neutral `LanguageFileType`. Its parser definition mirrors IntelliJ's plain-text definition: an ordinary
  `IFileElementType`, one leaf containing the exact source, a minimal `PsiFileBase`, no references, and no stubs.
- `Scala3DotcLanguage` remains the Scala-derived, stub-bearing ready language. Its parser is synchronous and consumes
  only the exact-version parser capability captured by its file view provider.
- `Scala3DotcLanguageSubstitutor` is a pure query. For an active module it returns the ready language only while the
  current module epoch is `Activating` or `Ready`; every other parser-capability state returns the pending language.
- A project lifecycle service prepares the parser capability off the EDT. It activates only a still-current epoch,
  updates IntelliJ's language-substitution record, asks IntelliJ to replace the file view provider through one
  `FileContentUtilCore.reparseFiles` batch, and publishes `Ready` after the batch.

The transition deliberately replaces PSI identity. The stable identities across it are the `VirtualFile`, document,
editor, caret, selection, and document `RangeMarker`s. A pointer to pending PSI is not a supported cross-transition
handle. Pending PSI contains no declarations or references worth retaining, and Platform 261 file pointers record the
old language ID and PSI class, so promising that they survive a language/class replacement would contradict the
platform implementation.

Do not enter dumb mode to cover parser preparation. Neutral PSI is the correctness mechanism. The reparse's synthetic
VFS event naturally schedules index maintenance; whether the platform briefly enters dumb mode for that work is its
own scheduling decision and is not observable correctness state.

## Why this is the platform-owned path

Platform 261's `LanguageSubstitutors` records the last substituted language on the `VirtualFile`, coalesces changed
language requests, and ultimately calls `FileContentUtilCore.reparseFiles`. The first substitution is record-only, and
unit-test mode deliberately omits its queued reparse
([`LanguageSubstitutors.java`](https://github.com/JetBrains/intellij-community/blob/ddf64ea5690e21e271d51d47a826bc64a494d41e/platform/core-api/src/com/intellij/psi/LanguageSubstitutors.java#L50-L128)).
The lifecycle therefore makes the operation explicit and deterministic:

1. Publish `Activating(epoch, capability)` with compare-and-set. Parser lookup may use the capability, but semantic
   consumers still treat the module as unavailable.
2. In a background read action, collect the module's physical active Scala files. Move the immutable batch to the EDT.
3. Call `LanguageUtil.getLanguageForPsi(project, file)` for each file. This runs the registered substitutor and updates
   IntelliJ's previous-language record
   ([`LanguageUtil.java`](https://github.com/JetBrains/intellij-community/blob/ddf64ea5690e21e271d51d47a826bc64a494d41e/platform/core-api/src/com/intellij/lang/LanguageUtil.java#L42-L64)).
4. Cancel the queued automatic request for each file with `LanguageSubstitutors.cancelReparsing`.
5. Call `FileContentUtilCore.reparseFiles(files)` once for the batch.
6. Publish `Ready` only if the epoch is still `Activating`.

The explicit batch handles the first-substitution case, behaves the same in unit and production modes, and prevents a
second queued reparse. `forceReload` also cancels pending requests for cached providers, but the explicit cancellation
covers module files that have index state without cached editor PSI. Files without PSI are included because the same VFS
event replaces their pending no-stub index state.

`Activating` closes the stale-PSI window: the substitutor and indexer can construct ready PSI, while semantic consumers
remain stopped until every cached pending provider in the batch has been invalidated. No consumer may treat parser
capability existence alone as permission to use ready Scala PSI. Each ready file view provider captures an immutable
capability and epoch when it is created; parse callbacks never look up whichever module capability happens to be
current.

`FileContentUtilCore.reparseFiles` emits synthetic same-name property changes inside one platform write action and
publishes the normal foreground and background VFS events
([`FileContentUtilCore.java`](https://github.com/JetBrains/intellij-community/blob/ddf64ea5690e21e271d51d47a826bc64a494d41e/platform/core-api/src/com/intellij/util/FileContentUtilCore.java#L36-L70)).
The PSI listener recognizes that requestor and calls `forceReload`, which removes the cached view provider with normal
PSI change events
([`PsiVFSListener.kt`](https://github.com/JetBrains/intellij-community/blob/ddf64ea5690e21e271d51d47a826bc64a494d41e/platform/lang-impl/src/com/intellij/psi/impl/file/impl/PsiVFSListener.kt#L286-L301),
[`FileManagerImpl.java`](https://github.com/JetBrains/intellij-community/blob/ddf64ea5690e21e271d51d47a826bc64a494d41e/platform/core-impl/src/com/intellij/psi/impl/file/impl/FileManagerImpl.java#L172-L212)).
The replacement provider is then created from the newly substituted language. No private PSI reload or direct
`onContentReload` call belongs in Metallurgy.

## Capability state

The parser-capability registry is keyed by module identity and model epoch, not file text or document version.

| State | Substituted language | Allowed action |
| --- | --- | --- |
| `Inactive` | no Metallurgy substitution | Bundled plugin owns the file |
| `Preparing(epoch)` | pending | One background preparation owns the epoch |
| `Activating(epoch, capability)` | ready | Parser and index transition only; semantic consumers remain stopped |
| `Ready(epoch, capability)` | ready | Synchronous deterministic parse |
| `Unavailable(epoch, report)` | pending | Neutral file plus one project-level capability report |
| `Disposed` | no result | Ignore completions and release the isolated loader |

`Preparing` includes exact parser artifact acquisition, isolated-loader construction, and capability probing.
`Activating` is a short platform-transition state, not background preparation. Both are separate from
presentation-compiler readiness and semantic snapshot freshness. A document edit never moves a ready module back to
pending.

A compiler coordinate, compiler options, classpath model, or opt-in change creates a new epoch. Completion from an old
epoch is discarded. If an active module's parser identity changes, publish the new `Preparing` epoch before disposing
the old bridge and use the same platform path for ready-to-pending replacement. The later pending-to-ready replacement
is the single readiness transition for that new epoch. Deactivation falls through to the bundled substitutor and uses
the same replacement path.

The old capability remains leased by any old ready view provider or parse already in flight. Dispose its isolated
loader only after the replacement batch invalidates those providers and all parser leases close. This prevents a
model-change race from making a previously created ready provider consult the new epoch or a closed classloader.

An unavailable parser remains neutral rather than falling back to bundled Scala parsing. Retry creates a new epoch.
The project-level report includes the exact artifact coordinates, failed capability, and cause; it is not a file
highlight.

## Pending language contract

The pending language must not derive from `ScalaLanguage` or `Scala3Language`, and must not implement `JvmLanguage`,
`DependentLanguage`, or `InjectableLanguage`. This prevents inherited Scala annotators, inspections, completion,
references, refactoring hooks, and stub builders from treating pending text as Scala PSI.

This separation is enforced by extension lookup: `LanguageExtension.findForLanguage` walks the selected language and
each base language until it finds an implementation
([`LanguageExtension.java`](https://github.com/JetBrains/intellij-community/blob/ddf64ea5690e21e271d51d47a826bc64a494d41e/platform/core-api/src/com/intellij/lang/LanguageExtension.java#L98-L115)).
A Scala-derived pending dialect would therefore inherit bundled Scala extensions even if its own parser definition were
neutral.

Its parser definition follows IntelliJ's own non-stub plain-text shape
([`PlainTextParserDefinition.java`](https://github.com/JetBrains/intellij-community/blob/ddf64ea5690e21e271d51d47a826bc64a494d41e/platform/core-impl/src/com/intellij/openapi/fileTypes/PlainTextParserDefinition.java#L25-L83)):

- one `IFileElementType`, never `IStubFileElementType`;
- `parseContents` returns one leaf containing `chameleon.getChars`;
- empty whitespace, comment, and string token sets;
- `createElement` returns `PsiUtilCore.NULL_PSI_ELEMENT`;
- `createFile` returns a minimal pending `PsiFileBase` bound to the pending language;
- no Scala file-view-provider factory is registered for it.

The language's associated neutral `LanguageFileType` is not registered for the `.scala` extension. It exists only so
IntelliJ's indexing view substitutes both language and file type. `SubstitutedFileType` leaves the original Scala file
type in place when the substituted language has no associated file type
([`SubstitutedFileType.java`](https://github.com/JetBrains/intellij-community/blob/ddf64ea5690e21e271d51d47a826bc64a494d41e/platform/core-impl/src/com/intellij/util/indexing/SubstitutedFileType.java#L27-L41)),
and `IndexedFileImpl` uses that substituted view
([`IndexedFileImpl.java`](https://github.com/JetBrains/intellij-community/blob/ddf64ea5690e21e271d51d47a826bc64a494d41e/platform/core-impl/src/com/intellij/util/indexing/IndexedFileImpl.java#L33-L43)).
Without the neutral associated file type, the stub index can still inspect the original Scala file type and admit the
bundled Scala stub builder. The neutral type instead leads it to the pending parser definition's ordinary file node,
which has no stub descriptor
([`StubUpdatingIndex.java`](https://github.com/JetBrains/intellij-community/blob/ddf64ea5690e21e271d51d47a826bc64a494d41e/platform/indexing-impl/src/com/intellij/psi/stubs/StubUpdatingIndex.java#L78-L121)).

Do not reuse `PsiPlainTextFileImpl`: for a non-plain-text base language it reports `PlainTextFileType` and consults
reference providers
([`PsiPlainTextFileImpl.java`](https://github.com/JetBrains/intellij-community/blob/ddf64ea5690e21e271d51d47a826bc64a494d41e/platform/core-impl/src/com/intellij/psi/impl/source/PsiPlainTextFileImpl.java#L18-L51)).
The pending file instead keeps the view provider's `.scala` file type, exposes no reference-host interface, and uses
the pending parser definition's exact file node type. `PsiFileBase` verifies that the root element type belongs to the
view provider's language
([`PsiFileBase.java`](https://github.com/JetBrains/intellij-community/blob/ddf64ea5690e21e271d51d47a826bc64a494d41e/platform/core-impl/src/com/intellij/extapi/psi/PsiFileBase.java#L17-L61)).

The `.scala` file type and document remain unchanged. “Verbatim” means the pending PSI text, document text, and virtual
file content agree exactly, including line endings, comments, whitespace, Unicode, and malformed edit fragments.

Pending consumers do not need feature-specific suppression. They are not handed a `ScalaFile`, `Sc*` elements,
references, or a stub tree, so Scala semantic extensions are inapplicable. Any project service that starts from a
`VirtualFile` must also require the ready capability before requesting Scala PSI.

## PSI, editor, and pointer ownership

The platform compares the old and new view providers by provider class, file type, base language, language set, and PSI
class. A language change makes them non-equivalent and causes full provider replacement
([`FileManagerImpl.java`](https://github.com/JetBrains/intellij-community/blob/ddf64ea5690e21e271d51d47a826bc64a494d41e/platform/core-impl/src/com/intellij/psi/impl/file/impl/FileManagerImpl.java#L796-L845)).
The old pending `PsiFile` and its leaf become invalid. Caches keyed by `PsiElement`, `PsiFile`, view provider, or
language must observe PSI modification events or be explicitly cleared by their owner.

Platform file smart pointers persist the root language ID and PSI class name, and restoration rejects a replacement
whose class differs
([`FileElementInfo.java`](https://github.com/JetBrains/intellij-community/blob/ddf64ea5690e21e271d51d47a826bc64a494d41e/platform/core-impl/src/com/intellij/psi/impl/smartPointers/FileElementInfo.java#L24-L45)).
Therefore:

- do not create or publish PSI-element pointers while a file is pending;
- store navigation and delayed work as `VirtualFile` plus document `RangeMarker` or stable offset;
- reacquire ready PSI after the replacement event;
- pointers created from ready PSI retain the ordinary platform guarantees across subsequent document edits.

The real editor is not closed. The force-reload request is handled as an editor content reload, so document identity and
editor state remain platform-owned. Runtime tests must prove the caret, selection, scroll position, and unsaved document
text survive; source inspection is not a substitute for that integration contract.

## Stub and index behavior

The pending language cannot contribute Scala stubs because its root is not stub-bearing and its PSI is not Scala PSI.
At readiness, the synthetic `PROP_NAME` event is observed by `IndexedFilesListener` as a non-content-only change, which
schedules all applicable indexes for the file
([`IndexedFilesListener.java`](https://github.com/JetBrains/intellij-community/blob/ddf64ea5690e21e271d51d47a826bc64a494d41e/platform/lang-impl/src/com/intellij/util/indexing/events/IndexedFilesListener.java#L86-L139)).
Indexing recreates PSI using the current substituted language
([`FileContentImpl.java`](https://github.com/JetBrains/intellij-community/blob/ddf64ea5690e21e271d51d47a826bc64a494d41e/platform/core-impl/src/com/intellij/util/indexing/FileContentImpl.java#L91-L113)).

Metallurgy must not call `FileBasedIndex.requestReindex`, build a temporary stub, or mutate stub storage. One VFS reparse
batch is the sole invalidation signal. The platform event merger owns coalescing and replaces the pending no-stub index
state with the ready stub state. Tests must still prove there is exactly one declaration result and no
`UpToDateStubIndexMismatch`; this is a required runtime invariant, not an assumption derived solely from the APIs.

## Dumb mode and progress

Parser preparation runs in a cancelable background task and may expose normal progress UI for artifact acquisition.
It must not queue a `DumbModeTask`, toggle dumb state, or wait for smart mode as a correctness device. IntelliJ defines
dumb mode as the period in which indexes are being updated
([`DumbService.kt`](https://github.com/JetBrains/intellij-community/blob/ddf64ea5690e21e271d51d47a826bc64a494d41e/platform/core-api/src/com/intellij/openapi/project/DumbService.kt#L54-L105));
parser availability is not indexing.

The platform may choose a natural dumb-mode interval after the reparse if index work warrants it. Ready-language
publication does not wait for that interval: PSI is already deterministic, while index-dependent consumers follow the
platform's normal dumb-awareness rules.

## Rejected alternatives

- **A pending leaf under the ready stub language:** indexing and editor parsing can observe different stub spines for
  identical content, which is the mismatch this lifecycle removes.
- **Bundled Scala PSI while the exact parser prepares:** unsupported compiler-valid syntax can create false errors and
  unresolved references before readiness.
- **A Scala-derived pending dialect:** base-language extension inheritance leaks bundled Scala behavior into the
  supposedly neutral phase.
- **Direct `AbstractFileViewProvider.onContentReload`:** it clears one provider's contents but does not perform a
  language/class replacement or emit the VFS event used by indexing.
- **Only the automatic substitutor reparse:** the first substitution and unit-test mode intentionally omit it, so that
  path is not a deterministic lifecycle boundary.
- **A manual `FileBasedIndex.requestReindex`:** it duplicates the invalidation already carried by the platform reparse
  and creates two independently ordered transitions.
- **A `DumbModeTask` around parser preparation:** dumb mode describes index maintenance, not parser availability, and
  cannot make stub-bearing nondeterministic PSI safe.
- **Preserving pending PSI pointers:** Platform 261 intentionally rejects restoration when the root language or PSI
  class changes. Stable file/range identity is the correct cross-transition contract.

## Required tests

### State and parser tests

1. A current-epoch completion performs one `Preparing -> Activating -> Ready` transition; duplicate and stale
   completions do nothing.
2. An edit preserves `Ready`; a model change creates a new epoch and rejects the old completion.
3. The substitutor is a pure capability-state query for active, inactive, ready, pending, unavailable, and disposed
   modules.
4. Pending PSI preserves exact text for valid Scala 3, unsupported experimental syntax, and malformed edit fragments.
5. Pending PSI has an ordinary file element, no stub tree, no `ScalaFile`, no `Sc*`, no `PsiReference`, and no
   `PsiErrorElement`.
6. Running highlighting on pending PSI produces no semantic error or warning. No highlight filter participates.

### Platform transition tests

Use physical fixture files because explicit-language `createFileFromText` bypasses substitution, and because
`LanguageSubstitutors` omits automatic reparsing in unit-test mode.

1. Start with cached pending PSI, publish readiness, run the lifecycle transition, and assert one provider replacement,
   ready language, `ScalaFile`, stub-bearing root, and unchanged document text.
2. Assert the old pending file is invalid and reacquisition through `PsiManager` returns the ready file.
3. Hold a `VirtualFile`, document, and `RangeMarker` across transition and assert all still address the same text.
4. Assert a pending PSI smart pointer is not part of the contract; create a ready declaration pointer after transition
   and prove its normal edit survival.
5. Query the stub index before and after transition: zero pending declarations, then exactly one result for each ready
   declaration, with no stale or duplicate entries.
6. Exercise simultaneous readiness for several files and assert one batched transition per module epoch.
7. Exercise cancellation, unavailable capability, project disposal, module removal, deactivation, and a compiler-model
   epoch change.
8. Hold an old ready provider and an in-flight parser lease across a model change; both finish against the old
   capability, while newly acquired PSI uses only the new epoch and the old loader closes after its last lease.

### IntelliJ process tests

Automate the installed IDE with the local `ide-probe` checkout. Its existing driver can observe background tasks, open
editors, and exact highlight information
([`ProbeDriver.scala`](https://github.com/VirtusLab/ide-probe/blob/9caeeb7d92c0a0fdbbfe2c2a69b0d72cc2efad6e/core/driver/sources/src/main/scala/org/virtuslab/ideprobe/ProbeDriver.scala#L43-L52),
[`Highlighting.scala`](https://github.com/VirtusLab/ide-probe/blob/9caeeb7d92c0a0fdbbfe2c2a69b0d72cc2efad6e/core/probePlugin/src/main/scala/org/virtuslab/ideprobe/handlers/Highlighting.scala#L30-L100)).
Add a small test endpoint for current PSI language/class, root element type, stub presence, document identity, caret,
selection, and index query counts.

Run these scenarios:

- cold artifact cache: open and edit while preparation is visible, observe neutral PSI and no false findings, then one
  ready replacement;
- warm artifact cache: first PSI is ready or the pending interval still performs only one replacement;
- unsaved edits during preparation: the ready parse uses the latest committed document verbatim;
- restart with a ready cache: no pending stub shape is ever indexed;
- exact compiler coordinate change: ready-to-pending-to-ready across a new epoch with no old-version PSI;
- unavailable parser capability: neutral file, one project report, no retry loop, and no file-level error;
- multi-module project: only files in the completed module epoch transition;
- repeated close/open and project restart: no duplicate stub-index results or severe PSI/index log entries.

The test must fail on any ERROR or WARNING highlight not reported by dotc, any `PsiErrorElement` while pending, any
`UpToDateStubIndexMismatch`, duplicate index result, unexpected second replacement, editor-state loss, EDT compiler
work, or leaked background task.

## Implementation boundary

Introduce the pending language, parser definition, parser-capability registry, and lifecycle coordinator before
connecting the deterministic producer. Then:

1. make substitution select pending or ready from the registry;
2. move parser preparation out of parse callbacks and presentation-compiler session creation;
3. replace private `onContentReload` syntax installation with the explicit language-record/update/reparse batch;
4. remove the pending leaf and every per-file asynchronous syntax decision;
5. retain semantic document-version state separately from parser capability state.

This lifecycle is complete only when the physical-fixture and `ide-probe` transition tests pass. Source-level tests
alone cannot establish editor-state and index behavior across a real provider replacement.
