# Parity, recovery, and compiler-conflict classification

## Decision

Classify the runtime checkpoints of every copied IntelliJ Scala 3 test invocation, then derive one invocation lane from
those checkpoint results. A checkpoint is the exact document state at which an unchanged helper makes an assertion or
invokes an editor operation. It is not merely a test method, source literal, or file on disk.

The three invocation lanes are:

1. **Parity** — every reached checkpoint is compatible with the exact compiler and the unchanged upstream oracle.
2. **Recovery** — at least one reached checkpoint deliberately exercises editor behavior on compiler-rejected source,
   and no checkpoint contradicts an exact compiler fact.
3. **Dotc-oracle conflict** — at least one compiler-owned semantic assertion is independently proven incompatible with
   dotc under the exact runtime source graph, compiler, options, classpath, and JDK.

The parity lane needs two checkpoint subtypes to be exhaustive:

- `parity.accepted`: dotc reports no errors and the checkpoint's compiler-owned claims agree with dotc.
- `parity.expected-rejection`: the source is a complete negative example, dotc rejects it, and the unchanged test
  asserts the corresponding compiler diagnostic rather than editor recovery.

Only `parity.accepted` contributes to the **compiler-valid parity** metric. A complete negative highlighting test is
ordinary parity work, but it is not compiler-valid. Without `parity.expected-rejection`, a total classifier would
incorrectly call every negative compiler test either edit recovery or an oracle conflict.

The recovery lane has checkpoint subtypes such as `recovery.parse`, `recovery.semantic`, and `recovery.editor-action`.
These are reporting dimensions, not alternate dispositions.

A conflict remains copied and generated unchanged. Its original invocation still runs. A separate conflict runner
records the original result plus an adapter-emitted observation of the disputed assertion, replays an independent
exact-dotc proof, and reports `observed-conflict`. It never reports `passed`, `skipped`, `ignored`, or expected-pass.
An unrelated failure, an unexpected pass, a stale proof, or failure to reach the disputed checkpoint fails the lane.

A conflict is semantic, not textual. It requires an accept-versus-reject disagreement, a real diagnostic
presence/absence or severity disagreement, a semantic type disagreement, or a symbol-identity disagreement. Diagnostic
wording/range differences and type rendering differences remain ordinary bridge/presentation parity work. They never
promote a test to the conflict lane.

Applicable invocations that are unclassified, ambiguous, or incompletely reproduced are hard failures. Non-applicable
runtime combinations remain explicit inventory facts but do not enter a lane or its counts. There is no fallback
category and no version-based exclusion.

## Why the checkpoint is the unit

The pinned upstream helpers establish that a method name is not the semantic boundary:

- `checkTextHasNoErrors` passes its argument directly to `configureByText` and then asks the daemon to highlight the
  realized virtual file; it does not trim or wrap the text
  ([source](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/base/ScalaLightCodeInsightFixtureTestCase.scala#L190-L209)).
- editor-action tests configure a document, perform one or more actions, commit it, and compare the resulting document
  and carets. A single invocation therefore observes multiple source versions
  ([source](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/base/EditorActionTestBase.scala#L41-L108)).
- completion helpers remove caret markers as part of their established fixture contract, invoke completion, select an
  item, and compare the resulting text; an incomplete import with no closing brace is a normal input
  ([test](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/lang/completion3/Scala3KeywordCompletionTest.scala#L1752-L1767),
  [helper](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/lang/completion3/base/ScalaCompletionTestBase.scala#L55-L86)).
- parser tests compare a complete PSI dump, while a separate helper compares the exact position and description of
  every `PsiErrorElement`
  ([source](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/lang/parser/ScalaParserTestOps.scala#L14-L30)).
- the upstream incomplete-code type-inference tests explicitly waive the usual parser-error precondition and still
  assert an exact type for a selected expression
  ([tests](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/lang/typeInference/IncompleteCodeInferenceTest.scala#L3-L28),
  [fixture](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/lang/typeInference/TypeInferenceTestFixture.scala#L42-L92)).

The classifier consequently observes each registered helper boundary at runtime. For every checkpoint it records:

- the invocation identity, helper symbol, helper-call ordinal, and assertion ordinal;
- the unchanged protected call-site and expected-payload hashes;
- all realized documents, auxiliary files, module relationships, and their exact bytes and relative paths;
- caret/selection/range markers after the original helper's own marker processing;
- the operation and oracle claims the helper contract makes;
- the exact compilation environment and compiler observation;
- the resulting checkpoint classification.

Static source analysis discovers candidate checkpoints and proves that the generated adapter exposes the original
helper. Runtime capture is authoritative for computed strings, inherited helpers, parameterized cases, marker
processing, setup-created files, document edits, and generated suites.

The captured compiler source is the document that the unchanged upstream helper actually presents to IntelliJ.
Metallurgy does not trim, enclose, relocate, rename declarations, insert synthetic values, or otherwise make it compile.
If an upstream helper intentionally creates a fragment or dummy declaration as part of its own protected contract, the
capture records that helper-produced source as a distinct document; the harness does not add another projection.

## Exact compilation environment

Scala version alone is not a proof key. Upstream runtime selection can inject an exact version, choose the latest
applicable Scala 3 version, apply `supportedIn`, or accept an environment/property override
([source](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/base/ScalaSdkOwner.scala#L17-L43),
[selection](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/base/ScalaSdkOwner.scala#L94-L126)).
The light fixture also supplies a JDK, Scala SDK, additional libraries, and module compiler options
([source](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/base/ScalaLightCodeInsightFixtureTestCase.scala#L62-L86),
[options](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/base/ScalaLightCodeInsightFixtureTestCase.scala#L119-L145)).

Each checkpoint therefore has a content-addressed `environmentId` computed from:

| Input | Recorded form |
|---|---|
| Compiler | exact Maven coordinate, resolved artifact graph in classloader order, SHA-256 of every artifact |
| Scala library | exact coordinate and artifact SHA-256 |
| Compiler plugins | option spelling, order, plugin artifact graph, and hashes |
| Sources | verbatim bytes, relative paths, source-root and module identity, compile order where meaningful |
| Generated setup sources | recipe/helper identity, source bytes, output digest, and owning module |
| Options | complete ordered argument vector after module setup, including `-source`, language imports, warning policy, target, release, and platform flags |
| Classpath | complete ordered entries; artifact hashes and deterministic directory-tree hashes, with output provenance |
| JDK | vendor, runtime version, target/release, and hashes or module identities of the boot/runtime inputs used |
| Modules | dependency direction, production/test source role, output roots, and any best-effort input provenance |
| Fixture mode | file, fragment, worksheet, script, compiler-highlighting, indexing mode, and helper-owned transformations |
| Compiler capability | observed exact-loader capabilities used by the run, not a claimed version range |

Absolute temporary paths, timestamps, terminal color, and nondeterministic process data are excluded from the ID after
their semantic inputs have been represented. Classpath order, option order, relative file paths, and duplicate entries
are retained because they can change compiler behavior.

Dotc's command setup distills the complete argument vector into settings and source files
([source](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/Driver.scala#L70-L94)),
and the public compiler entry point directs callers to `Reporter.hasErrors` to determine success
([source](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/Driver.scala#L124-L169)).
`Reporter` counts errors and warnings independently
([source](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/reporting/Reporter.scala#L96-L119)).
The validity predicate is therefore:

```text
accepted(checkpoint) =
  exact full-compiler run completed normally
  AND Reporter.hasErrors == false after final reporting
```

A process exit code, a presentation-compiler tree, a generated class file, or “some diagnostics were returned” is not
the validity predicate. Warning settings are part of the exact options. In particular, `-Werror` turns warning-bearing
input into a compiler error during final reporting
([source](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/reporting/Reporter.scala#L150-L178),
[setting](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/config/ScalaSettings.scala#L154-L162)).

The proof runner invokes the ordinary full compiler with the exact options first. Separate evidence runs may add
`-Xprint:parser` or `-Xprint:typer`, but those outputs never replace the untouched-options verdict. The proof bridge
records the phase active when each structured diagnostic is reported. This is stronger than guessing the phase from
message text. The compiler's normal frontend orders parser before typer
([source](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/Compiler.scala#L30-L45)),
and later phases are normally not runnable after reporter errors
([source](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/core/Phases.scala#L308-L332)).

## Oracle claims

Classification is about compatibility between an unchanged upstream claim and the exact compiler observation. It is
not inferred from a method name, source-path keyword, diagnostic severity, comment, or current Metallurgy result.

`adapters.json` gives every supported helper a closed claim contract. A helper may emit more than one claim:

| Claim owner | Examples | Classification use |
|---|---|---|
| `compiler.acceptance` | no language errors; a language error must exist | Compared with `Reporter.hasErrors` and structured diagnostics |
| `compiler.diagnostic` | diagnostic presence/absence, semantic category, severity, warning | Compared with exact reporter output under exact warning options |
| `compiler.type` | exact/widened/expected type at an asserted source range | Compared with the exact typed tree and `-Xprint:typer` probe |
| `compiler.symbol` | resolve target, overload, owner, symbol absence | Compared with exact typed-tree symbol identity |
| `ide.recovery` | recovered PSI shape, accessor behavior, error element, stable surrounding declaration | Selects recovery when the source is rejected |
| `ide.operation` | completion insertion, typing/backspace result, caret, formatting, navigation mechanics | Preserved as an IntelliJ behavior claim; may be recovery on rejected source |
| `ide.inspection` | unused/private/style/deprecation inspection owned by the IDE | Never made compiler-owned merely because its severity is warning or error |
| `ide.presentation` | rendered PSI dump, ordered annotations, quick-fix text | Compared unchanged; compiler facts constrain any semantic fields it contains |

Some upstream highlighting fixtures recursively invoke the annotator and explicitly document that their output can
differ from editor daemon highlighting
([source](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/annotator/ScalaHighlightingTestLike.scala#L11-L24)).
That fixture compares the complete ordered rendered messages
([source](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/annotator/ScalaHighlightingTestLike.scala#L32-L74)).
The registry must therefore distinguish an IDE annotator assertion from a compiler-highlighting assertion. Message text
alone cannot identify the owner.

Exact diagnostic wording, anchoring range, underline extent, and ordering are protected presentation assertions, but
they are not conflict predicates. Likewise, source-facing type text is protected parity work and follows the bridge's
rendering rules; conflict adjudication compares semantic compiler types before presentation.

A compound helper decomposes its assertion. For example, completion on a member can contain:

- a compiler-symbol claim that the member exists;
- an IDE-operation claim about lookup ordering, selected insertion text, imports, and caret placement.

Unknown helper symbols, dynamic dispatch to an unregistered override, or a helper digest that no longer matches the
pinned implementation make the checkpoint unclassified.

## Executable classification algorithm

For every runtime invocation:

1. Apply the invocation collector's exact Scala/JDK/version/category/indexing applicability rules.
2. Execute copied setup and the generated adapter without changing protected bodies or initializers.
3. At every registered helper/assertion boundary, capture the realized source graph and oracle claims.
4. Replay that graph through the exact full compiler in a fresh isolated process/classloader and record structured
   diagnostics, reporter counts, typed facts requested by claims, and an environment ID.
5. Classify the checkpoint with the following ordered rules.
6. Derive the invocation lane from all reached checkpoints.
7. Require the manifest to account for the runtime invocation and every reached checkpoint exactly once.

The checkpoint classifier is:

```text
if any compiler-owned claim contradicts an exact dotc fact:
  conflict-candidate
else if dotc accepted:
  parity.accepted
else if the helper contract asserts a complete negative compiler diagnostic
        and does not assert recovered PSI/editor behavior:
  parity.expected-rejection
else if the helper contract explicitly exercises recovered PSI,
        a semantic read in an invalid file, completion/refactoring/editing on invalid source,
        or a transition through an invalid document state:
  recovery
else:
  unclassified
```

`conflict-candidate` becomes `dotc-oracle-conflict` only after the independent proof protocol below succeeds. Until
then it is an unclassified hard failure.

The complete decision matrix is:

| Exact dotc verdict | Registered unchanged oracle | Semantic contradiction? | Checkpoint result |
|---|---|---:|---|
| accepted | clean/semantic/editor assertion | no | `parity.accepted` |
| accepted | compiler error or parser error must exist | yes | `conflict-candidate` |
| accepted with warnings | compiler warning or IDE inspection assertion | no | `parity.accepted` |
| rejected | complete negative compiler-diagnostic assertion | no | `parity.expected-rejection` |
| rejected | recovered PSI, semantic read in a red file, completion, refactoring, or edit transition | no | `recovery` |
| rejected | clean acceptance claim | yes | `conflict-candidate` |
| either | semantic type or symbol claim | yes, after exact semantic normalization | `conflict-candidate` |
| either | only wording, diagnostic range, ordering, or type-rendering difference | no | ordinary parity/recovery failure to fix |
| unavailable or ambiguous | any | unknown | `unclassified` |

The invocation roll-up is deterministic:

```text
if any checkpoint is dotc-oracle-conflict:
  invocation lane = conflict
else if any checkpoint is recovery:
  invocation lane = recovery
else:
  invocation lane = parity
```

Checkpoint counts remain visible even after roll-up. A mixed edit test can therefore report one rejected recovery
checkpoint followed by one accepted parity checkpoint while belonging to the recovery invocation lane.

### Compiler-valid parity

`parity.accepted` requires the untouched-options full compiler to have no errors. Warnings are retained in the
observation and checked when the helper owns a warning claim. Compiler-owned type and symbol assertions must match
dotc. IDE-only inspections and presentation assertions remain unchanged and must pass, but they do not change compiler
validity.

Examples:

- valid source plus an expected unused-declaration IDE warning remains accepted parity;
- valid source plus completion insertion formatting remains accepted parity;
- valid source whose expected type or resolve target disagrees with dotc is a conflict candidate, not parity;
- valid source whose test expects a bundled `PsiErrorElement` is a conflict candidate because a parser error asserts
  that accepted syntax is invalid.

The last rule covers a bundled-parser lag directly. Metallurgy must represent the compiler-valid syntax, so the
unchanged upstream parser-error expectation cannot be made to pass by emitting a false error. The preserved test and
proof are reported as a conflict until the pinned upstream expectation changes.

### Expected compiler-diagnostic parity

`parity.expected-rejection` covers a complete negative example whose purpose is to assert an ordinary language error or
warning, without relying on useful PSI around a malformed edit. Dotc rejection is the expected compiler result, not a
conflict.

Examples include a complete program that deliberately has an ambiguous overload, a type mismatch, or a missing given
and asserts the corresponding diagnostic. Exact message and presentation assertions remain protected. The claim
registry says which portions are compiler-owned and which are IDE rendering.

A source rejection alone does not select this subtype. The helper must explicitly own a negative diagnostic claim.

### Invalid-edit recovery

Recovery requires both compiler rejection and explicit recovery behavior in the unchanged helper contract. The parser
or presentation compiler may still return a useful tree when source is invalid. Dotc's parser reports an incomplete
input specially at EOF and otherwise skips to a safe point
([source](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/Parsers.scala#L277-L320));
the parser phase installs the untyped tree even when reporter errors exist
([source](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/ParserPhase.scala#L25-L40)).
That recovered structure is useful, but it does not make the source compiler-valid.

Recovery includes:

- parser tests asserting rich PSI and errors around incomplete syntax;
- type, resolve, navigation, completion, inspection, or refactoring in a rejected document;
- typing, deletion, or paste sequences whose intermediate document is rejected;
- a broken auxiliary file where the test asserts safe behavior in an unaffected region;
- cross-module editor behavior when the checkpoint's owning module is still rejected after consuming any discovered
  best-effort output from an upstream module with errors.

Recovery never authorizes hiding, filtering, downgrading, or omitting a dotc error. The exact compiler diagnostics stay
visible and attributable; recovery adds useful PSI and editor behavior around them. A recovery test that passes only
because an error was suppressed is a hard failure.

The upstream given-parser tests demonstrate the required richness: malformed aliases still have given definitions,
parameters, references, and exact `Type expected` error nodes
([source](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/lang/parser/scala3/GivenNewSyntaxParserTest.scala#L2366-L2427)).
A neutral whole-file placeholder is therefore a parser-capability pending state, not a passing recovery result.

Ordinary batch rejection does not by itself contradict an exact type assertion in a deliberately malformed file. The
checkpoint is recovery unless dotc also exposes a stable, non-error typed or symbol fact for the asserted range and
that precise fact contradicts the upstream assertion. This prevents incomplete-code type-inference tests from being
misclassified merely because the whole file cannot compile.

### Dotc-oracle conflict

A conflict requires a precise compiler-owned proposition and its exact contradiction. Supported predicates include:

| Upstream proposition | Exact-dotc contradiction |
|---|---|
| source has no language errors | reporter has one or more errors |
| source has a language/parser error | reporter has no errors and the exact parser reports none |
| compiler warning is present/absent or has a stated semantic severity/category | exact reporter warning set contradicts it |
| expression has semantic type `T` | exact typed fact for the asserted range is not semantically equivalent to `T` |
| reference resolves to symbol `S` | exact typed symbol is different or resolution is an exact compiler error |
| candidate/member exists or is absent | exact compiler symbol/member observation contradicts it |

An error anywhere in the daemon output is not proof. Neither is a different diagnostic sentence when the helper is
asserting an IDE-owned rendering. Different diagnostic anchoring and different type text are also not proof. The proof
identifies the claim owner, asserted semantic fact, actual dotc fact, semantic comparison, and first reporting phase.

## Special cases

### Mixed and ambiguous invocations

Every runtime source version and assertion is a checkpoint. An invocation with accepted and rejected states is not
forced into a single validity claim:

- conflict dominates the invocation lane;
- otherwise recovery dominates;
- otherwise the invocation is parity;
- accepted and expected-rejection parity checkpoint counts remain separate.

If a loop contains multiple helper calls, each call has an ordinal. If the first disputed assertion terminates the
method, later statically discovered checkpoints are reported `not-reached-after-conflict` and are not counted as
executed. Each disputed payload still needs its own independent proof. A moving pin that changes reachability forces
reclassification.

Ambiguity never chooses the more convenient lane. Missing runtime context, an unknown helper override, an unclear
diagnostic owner, an unstable source range, inability to reconstruct an auxiliary module, or disagreement between
capture and replay yields `unclassified` and a failing verifier.

### Expected diagnostics and warnings

Severity does not select a lane:

- an accepted source may have warnings;
- an IDE inspection warning may be expected on accepted source;
- exact warning options, including suppression and promotion to errors, belong to the environment;
- a complete expected compiler error is `parity.expected-rejection`;
- a rejected source used for useful editor behavior is recovery;
- an expected compiler error or warning that dotc disproves is a conflict candidate.

The report keeps compiler errors, compiler warnings, parser errors, annotator findings, daemon findings, and IDE
inspection findings in separate fields. It never pairs them merely by overlapping ranges or downgrades a mismatch to
console output.

### Compiler-valid source with expected bundled-parser errors

This is an active conflict, not recovery. Recovery is for source dotc rejects. Emitting the expected bundled parser
error would violate dotc and the producer's compiler-valid contract. The original expected tree/error payload remains
byte-identical and its invocation runs in the conflict lane.

The proof must establish both sides:

1. exact dotc accepts the captured source with the captured environment;
2. the protected upstream assertion requires a parser error at a specific checkpoint.

### Bare expressions and fragments

A bare top-level expression rejected as an ordinary compilation unit is not automatically a conflict. The helper
contract determines whether it asserts a clean file or intentionally asks the IDE to type a fragment/recoverable
region. The former can be a conflict candidate; the latter is recovery.

The classifier compiles the verbatim realized document. It does not insert a synthetic value or containing object.
Any fragment context created by the upstream helper itself is recorded as a separate helper-owned source state.

### Known-failure inversion

Upstream `FailableTest` reverses equality when `shouldPass` is false
([source](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/test-utils-common/test/org/jetbrains/plugins/scala/base/FailableTest.scala#L5-L18)).
JUnit green therefore does not prove parity for such an invocation.

The runtime inventory records the effective `shouldPass` value and the adapter emits the pre-inversion expected and
actual facts. A `shouldPass=false` invocation:

- may become a dotc-oracle conflict when an exact proof shows that the deliberately inverted compiler-owned
  expectation contradicts dotc;
- may be recovery when its unchanged contract explicitly exercises invalid editor state and makes no compiler
  contradiction;
- otherwise remains unclassified and fails the selection gate.

It is never counted as parity solely because the upstream inversion made JUnit green.

### Best-effort TASTy

Best-effort compilation is not same-file recovery and does not change the validity predicate. Scala 3 describes
`-Ybest-effort` as producing best-effort output regardless of errors and
`-Ywith-best-effort-tasty` as permitting its consumption
([source](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/config/ScalaSettings.scala#L451-L452)).

For a cross-module test:

- the upstream producer module is still rejected when its reporter has errors;
- capability-probed best-effort emission and the exact output digest become environment inputs;
- the downstream compiler runs with the exact discovered consumer capability and classpath;
- a downstream checkpoint accepted by that compiler is `parity.accepted`, even though the producer remains rejected;
- the checkpoint is recovery only when its owning source is rejected and the unchanged test asserts useful editor
  behavior across that broken boundary;
- a downstream compiler-owned fact that contradicts the exact best-effort consumer result is a conflict candidate.

Best-effort flags are never added to a clean-validity run merely to obtain acceptance. They appear only when the
unchanged test/module setup and discovered capability require that exact cross-module scenario.

### Runtime applicability

Applicability is evaluated before classification from the compiled copied class and runner semantics. It is not copied
from a path heuristic. The manifest records the injected version and every `supportedIn`/runner decision.

The current local port shows why this matters. The pinned upstream `Scala3ExtensionsTest` supports Scala 3.7 or later
([source](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/lang/typeInference/Scala3ExtensionsTest.scala#L8-L14)).
Its anonymous-given example is upstream method `testSCL24177`
([source](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/lang/typeInference/Scala3ExtensionsTest.scala#L735-L760)),
while the local descriptive name is `testExtensionResolvedViaTypeclassGiven`. A proof must map those identities and run
the applicable upstream version. A locally convenient 3.5.2 execution is not evidence about that pinned invocation.

## Manifest and proof artifacts

Extend the integrity-harness layout with three strict, generated-or-verified files:

```text
upstream-tests/
  intellij-scala-classification.json
  intellij-scala-environments.lock.json
  compiler-conflicts/
    <descriptive-conflict-id>.json
target/
  copied-intellij-test-report.json
  copied-intellij-test-report.md
  copied-intellij-test-raw/
    ...
```

`intellij-scala-classification.json` is exhaustive over the runtime invocation inventory. A representative entry is:

```json
{
  "schemaVersion": 1,
  "originRevision": "8dd22d153b65c847f4ced8917dd7e02b83561e5d",
  "invocations": [
    {
      "id": "origin-owner#origin-method[scala=<exact-scala-version>,jdk=17,indexing=smart,case=0]",
      "generatedOwner": "descriptive.local.Owner",
      "generatedMethod": "testDescriptiveName",
      "runtimeApplicability": {
        "selected": true,
        "scalaVersion": "<exact>",
        "jdk": "<exact>",
        "runnerFactsSha256": "<sha256>"
      },
      "lane": "recovery",
      "checkpoints": [
        {
          "id": "helper-call-1/assertion-1/document-0",
          "helper": "fully.qualified.Helper#method(signature)",
          "helperClosureSha256": "<sha256>",
          "protectedCallSiteSha256": "<sha256>",
          "expectedPayloadSha256": "<sha256>",
          "realizedSourceGraphSha256": "<sha256>",
          "environmentId": "<sha256>",
          "claims": [
            {
              "owner": "ide.recovery",
              "dimension": "psi-tree",
              "range": {"file": "dummy.scala", "start": 0, "end": 10}
            }
          ],
          "classification": "recovery.parse"
        }
      ]
    }
  ]
}
```

The environment lock contains the complete normalized inputs described earlier and all raw artifact hashes. An
environment ID must resolve to exactly one lock entry. Absolute local paths and mutable coordinates are rejected.

A conflict certificate references existing protected payloads; it never embeds a second editable copy of a snippet:

```json
{
  "schemaVersion": 1,
  "id": "descriptive-compiler-disagreement",
  "invocationId": "<runtime invocation id>",
  "checkpointId": "<checkpoint id>",
  "environmentId": "<sha256>",
  "origin": {
    "revision": "<40-character commit>",
    "path": "<origin path>",
    "owner": "<origin owner>",
    "method": "<origin method>",
    "protectedBodySha256": "<sha256>",
    "assertionRangeSha256": "<sha256>",
    "expectedPayloadSha256": "<sha256>"
  },
  "claim": {
    "owner": "compiler.acceptance",
    "dimension": "accepted",
    "expected": true
  },
  "dotc": {
    "accepted": false,
    "firstErrorPhase": "typer",
    "structuredDiagnosticsSha256": "<sha256>",
    "parserTreeSha256": "<sha256>",
    "typerTreeSha256": "<sha256-or-null>",
    "compilerRunSha256": "<sha256>"
  },
  "independentProbe": {
    "kind": "repl-or-minimal-full-compiler",
    "recipeSha256": "<sha256>",
    "observationSha256": "<sha256>"
  },
  "predictedOriginalObservation": {
    "helper": "<helper symbol>",
    "assertionOrdinal": 1,
    "mismatch": "<structured predicate>"
  }
}
```

The certificate stores normalized structured observations, while raw stdout, stderr, compiler arguments, temporary
source tree, and original JUnit event stream are generated under `target/` and archived by CI. Reproduction compares
the structured certificate and raw-input hashes, not host-specific paths or terminal rendering.

For type and resolve conflicts, the independent probe uses the exact-version REPL when the claim can be represented
without changing scope. Multi-file, package, compiler-plugin, macro, or cross-module claims instead use a second
minimal full-compiler recipe that preserves those semantics. The ordinary full-source replay always remains primary.
`-Xprint:typer` and exact Scala 3 source inspection are mandatory adjudication evidence for a surprising typed fact.

## Conflict proof protocol

A candidate is promoted only when all of these checks pass:

1. **Origin integrity:** the origin source, protected method/initializer, assertion, expected payload, and test data
   match the pinned Git objects.
2. **Generated integrity:** the generated body and all protected ranges remain byte-identical and the adapter helper
   closure matches its reviewed digest.
3. **Runtime identity:** the locally collected invocation maps one-to-one to the origin invocation, including inherited,
   parameterized, generated, Scala/JDK, category, and indexing dimensions.
4. **Source capture:** the realized documents and auxiliary files come from the unchanged helper contract; no trim,
   enclosure, insertion, relocation, or harness-only source change occurs.
5. **Environment replay:** every exact compiler artifact, option, classpath entry, module edge, JDK input, and capability
   matches the content-addressed environment lock.
6. **Precise claim:** a registered helper contract identifies the compiler-owned proposition and protected assertion
   that makes it.
7. **Precise contradiction:** structured dotc output disproves that proposition, not merely an adjacent assertion.
8. **Phase evidence:** the first reporting phase, parser output, typer output when reachable, reporter counts, and
   diagnostic identities are captured.
9. **Independent reproduction:** the exact-version REPL or a second minimal full-compiler recipe reproduces the
   semantic fact without changing its relevant scope.
10. **Original execution:** the unchanged copied invocation reaches the predicted checkpoint and emits the predicted
    mismatch observation. A normal JUnit failure is allowed to propagate; a listener observes it outside the test.
11. **Failure attribution:** setup errors, timeouts, crashes, earlier assertions, missing events, and different
    mismatches are rejected as unrelated failures.
12. **Reversal check:** if dotc now agrees with the upstream assertion, the proof fails and the invocation returns to
    unclassified for normal parity/recovery execution.

No production behavior, test adapter, diagnostic filter, or compiler bridge consults the conflict manifest. It is test
accounting only.

## Status transitions

The classifier uses an explicit state machine:

```text
discovered disagreement
  -> Metallurgy defect (default)
  -> unclassified only while exact classification evidence is gathered
  -> parity.accepted
  -> parity.expected-rejection
  -> recovery
  -> conflict-candidate
       -> active-conflict

classified --input/applicability/helper/environment drift--> stale -> unclassified
active-conflict --dotc agrees or proof changes-------------> resolved -> unclassified
```

Rules:

- Every new disagreement starts as a Metallurgy defect. A conflict label is earned only by the complete independent
  proof; it is never the initial presumption.
- `discovered` and `unclassified` fail CI.
- A conflict candidate is not an active conflict and fails CI.
- Any origin pin, protected range, helper closure, runtime applicability, source graph, compiler artifact, options,
  classpath, JDK, module graph, or capability change invalidates the old classification.
- `stale` is never resolved by accepting a new hash alone; runtime capture and classification rerun.
- An active conflict that stops reproducing returns to the normal classifier. It is not silently carried forward.
- If dotc agrees with the protected upstream semantic assertion, the case returns to its ordinary parity or recovery
  lane and any Metallurgy failure remains a Metallurgy defect.
- Removed upstream invocations remain visible only in Git history; they are not runtime exclusions.

### No version allowlists

The exact version is evidence inside an environment ID, never a selector for behavior. The implementation may not ask
whether a compiler version is in a conflict set. There are:

- no version ranges;
- no “before/after” rules;
- no per-version skipped-test tables;
- no bytecode fingerprints selecting adapters;
- no fallback to a nearby compiler;
- no conflict record that applies when its exact environment ID differs.

A moving-version lane creates new runtime invocation/environment identities and classifies them from observed
capabilities and exact compiler results. An old conflict certificate is historical evidence, not permission to classify
the new version.

## Execution and reporting

Run three separate owned tasks after integrity and classification verification:

```text
runCopiedIntellijParity
runCopiedIntellijRecovery
runCopiedIntellijConflicts
```

The parity and recovery tasks use ordinary test semantics. Every selected invocation must finish successfully; an
unexpected skip, failure, error, crash, or timeout fails its task.

The conflict task runs each copied invocation in isolation and preserves the raw framework result. It does not catch
`ComparisonFailure` or `AssertionError` inside the test, invert an assertion, install `@Ignore`, or make a conflict
method look green. A runner listener and adapter observation stream record:

- whether the disputed checkpoint was reached;
- the pre-assertion structured expected and actual facts;
- the original JUnit outcome;
- the exact thrown failure when one propagates;
- whether the observation matches the certificate;
- whether the independent dotc proof reproduced.

For an upstream `shouldPass=false` helper, the original framework result may be green because upstream itself inverted
the assertion. The conflict task still reports `observed-conflict`, never `passed`, and requires the pre-inversion
observation to match the certificate. Without that structured observation, the invocation is unclassified.

Conflict results use dedicated JSON and Markdown, not ordinary JUnit pass accounting. Raw JUnit XML may be archived for
debugging, but the known-conflict suite is not published to systems that would count a green inversion as a pass or a
deliberate failure as a regression.

The report has a conservation equation:

```text
selected runtime invocations
  = parity invocations
  + recovery invocations
  + active conflict invocations

reached checkpoints
  = accepted parity checkpoints
  + expected-rejection parity checkpoints
  + recovery checkpoints
  + observed conflict checkpoints
```

It prints, at minimum:

- origin revision and environment IDs;
- total discovered, applicable, selected, and unclassified invocations;
- accepted parity pass/fail;
- expected-rejection parity pass/fail;
- recovery pass/fail by subtype;
- active conflicts observed, unexpectedly passed/agreed, mismatched, stale, or infrastructure-failed;
- compiler errors and warnings separately from parser, annotator, daemon, and IDE inspection findings;
- new, removed, reclassified, and environment-drifted entries relative to the target branch;
- every conflict's origin invocation, exact environment, claim, dotc observation, proof artifact, and raw original
  result.

An active conflict is in no pass numerator. The compiler-valid parity denominator contains only applicable invocations
whose reached checkpoints are all `parity.accepted`. Expected-rejection parity, recovery, and active conflicts are
reported alongside it, not hidden inside it.

## CI exit behavior

The outer CI gate succeeds only when:

1. snapshot/generated/protected-range/adapter integrity passes;
2. runtime invocation accounting is one-to-one and contains no unsupported or unclassified invocation;
3. every environment lock reproduces exactly;
4. every parity invocation passes unchanged;
5. every recovery invocation passes unchanged;
6. every active conflict proof reproduces;
7. every active conflict invocation reaches and exhibits its certified structured disagreement;
8. no conflict unexpectedly agrees, passes without the certified observation, fails elsewhere, crashes, times out, or
   becomes stale;
9. all report conservation equations hold.

The conflict subprocess can exit nonzero because the unchanged assertion failed. The outer conflict task may exit zero
only after recording `proof-verification: verified` for that exact certified non-pass. It must not call the result a
test pass. This is accounting, not suppression: the report still records a conflict and the pass total does not
increase.

The following always produce a nonzero outer exit:

- unclassified or missing invocation/checkpoint;
- manually ignored or filtered copied invocation;
- compiler/artifact/options/classpath/JDK mismatch;
- proof based on a transformed source;
- proof based only on presentation-compiler recovery or any daemon error;
- an active conflict that dotc no longer reproduces;
- a conflict test that fails at the wrong checkpoint;
- a JUnit-green `shouldPass=false` invocation presented as parity;
- a diagnostic mismatch that is printed but not classified;
- a report whose totals do not balance.

Ordinary pull-request CI runs integrity, classification, every active proof, and a representative parity/recovery slice.
The full lane runs all parity and recovery invocations plus every conflict invocation and proof. Both lanes fail on
selection or classification drift.

## Deficiencies in the current prototype

The current files are useful evidence but do not implement this decision:

- `Scala3CompatTestCase` forces every compatibility invocation to Scala 3.5.2
  ([source](../../src/test/scala/com/hmemcpy/metallurgy/compat/scala3/Scala3CompatTestCase.scala#L42-L47)).
- five current conflict entries claim Scala 3.7.4 while the proof suite inherits that 3.5.2 fixture; the proof ignores
  each entry's `scalaVersion` and `dotcPhase`
  ([manifest](../../src/test/scala/com/hmemcpy/metallurgy/compat/scala3/DotcOracleConflicts.scala#L21-L123),
  [proof](../../src/test/scala/com/hmemcpy/metallurgy/compat/scala3/DotcOracleConflictProofTest.scala#L10-L15)).
- the current conflict helper trims the source and accepts any daemon `ERROR`, without proving compiler provenance,
  exact environment, phase, diagnostic identity, or the disputed assertion
  ([source](../../src/test/scala/com/hmemcpy/metallurgy/compat/scala3/Scala3CompatTestCase.scala#L279-L297)).
- the current proof catches `AssertionError` inside an aggregate loop rather than observing the unchanged copied
  invocation in an isolated conflict lane
  ([source](../../src/test/scala/com/hmemcpy/metallurgy/compat/scala3/DotcOracleConflictProofTest.scala#L10-L15)).
- the current diagnostic helper requires only some dotc error and prints range/message differences without making them
  classified, attributable results
  ([source](../../src/test/scala/com/hmemcpy/metallurgy/compat/scala3/Scala3CompatTestCase.scala#L162-L208)).
- the current conflict object duplicates editable snippet strings instead of referencing the integrity harness's
  protected origin/runtime payload.

The exact-source integrity decision already requires runtime-complete invocation accounting, unchanged protected
payloads, and separately proven conflicts
([decision](copied-intellij-test-integrity-harness.md#test-selection-and-completeness)). The classifier should be built
into that owned generator, adapter registry, and runtime collector rather than extending the current hand-written
compatibility list.

## Relationship to the PSI architecture

Deterministic syntax remains separate from asynchronous semantics. Compiler-valid copied tests pass unchanged;
invalid-edit safety and exact-dotc conflicts are reported separately.

Classification does not choose production behavior:

- accepted source must receive deterministic compiler-valid PSI;
- rejected source must receive honest recovered PSI and errors;
- pending parser capability remains a neutral, non-Scala state;
- asynchronous semantic publication never changes syntax shape;
- conflicts never cause the producer to emit a false compiler result;
- conflict manifests are invisible to production code.

The presentation compiler is deliberately recovery-oriented and runs only parser, typer, root-tree setup, and comment
cooking
([source](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/interactive/InteractiveCompiler.scala#L10-L20)).
It supplies editor semantics and recovery evidence; the ordinary full compiler supplies the accepted/rejected verdict.
BETASTY supplies capability-probed cross-module information after upstream errors; it does not replace either same-file
recovery or the full-compiler verdict.

## Required implementation slices

This decision yields the following dependency order for the synthesis map:

1. Extend runtime invocation collection with exact applicability, effective `shouldPass`, JDK, compiler options,
   libraries, indexing mode, and generated/parameterized identity.
2. Extend the adapter registry with closed oracle-claim contracts and structured checkpoint events.
3. Capture every realized document/source graph without changing helper behavior.
4. Add the content-addressed exact compiler environment lock and isolated full-compiler replay.
5. Add structured compiler diagnostics, phase capture, typed fact probes, and independent proof recipes.
6. Generate exhaustive checkpoint and invocation classifications; reject unknowns.
7. Add isolated conflict execution and failure attribution without modifying copied bodies.
8. Emit dedicated balanced JSON/Markdown reports and wire the three lanes into bounded CI tasks.
9. Mutation-test classification boundaries: accepted/rejected verdict, warning promotion, helper owner, source byte,
   classpath order, option, JDK, checkpoint reachability, conflict observation, and every report count.

## Acceptance criteria

The classification design is implemented when:

1. every applicable runtime invocation and every reached assertion/editor checkpoint is accounted for exactly once;
2. every checkpoint uses the exact realized source graph and content-addressed compiler environment;
3. accepted parity, expected-rejection parity, recovery, and conflict predicates are executable and mutation-tested;
4. compiler-valid parity counts only `parity.accepted`;
5. complete negative diagnostic tests remain ordinary parity and are not mislabeled recovery/conflict;
6. incomplete parser/type/resolve/completion/refactoring/edit tests are recovery when exact dotc rejects their document;
7. a compiler-valid test expecting a bundled parser error is a preserved, independently proven conflict;
8. warnings and IDE inspections retain their owner and do not determine validity by severity alone;
9. mixed tests expose every checkpoint classification and derive their invocation lane by the fixed precedence;
10. active conflicts reference protected payloads, reproduce under the exact environment, and exhibit the predicted
    unchanged-test disagreement;
11. conflicts, upstream assertion inversions, skips, and unrelated failures never enter pass counts;
12. any source, helper, applicability, artifact, option, classpath, JDK, module, capability, or proof drift fails closed;
13. moving compiler versions are classified from exact observations without version allowlists;
14. CI can succeed with certified conflicts separately reported, but cannot succeed with an unclassified, stale,
    suppressed, transformed, or misattributed test;
15. no production path reads classification or conflict data.
