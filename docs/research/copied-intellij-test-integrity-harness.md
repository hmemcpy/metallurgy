# Copied IntelliJ test integrity harness

## Decision

Metallurgy should vendor the selected IntelliJ Scala test sources and test data exactly, then mechanically generate an
adapted Scala test tree. Generated executable bodies retain their upstream bytes exactly, including test methods,
local helper methods, setup and version-selection overrides, field initializers, Scala snippets, helper calls,
assertion arguments, markers, comments, and expected values. Generation may change only declared host tokens outside
those protected bodies:

- package and import wiring;
- descriptive class and test-method names;
- the base class or mixed-in adapter;
- runner/category annotations that the local build replaces with equivalent execution wiring;
- a generated Apache 2.0 modification/provenance header.

The adapted methods continue to call upstream helper names such as `checkTextHasNoErrors`, `doTest`, and
`doCompletionTest`. Metallurgy-owned fixture adapters implement those original contracts. There is no neutral
operation record, generic case interpreter, `runUpstreamCase` method, rewritten assertion, or locally restated
expected value.

Three comparisons make the arrangement enforceable:

1. **Origin snapshot integrity:** every vendored upstream file must equal
   `git show <full-revision>:<path>` byte for byte.
2. **Adapted-tree reproducibility:** regenerating from that snapshot and the manifest must reproduce every adapted
   Scala file exactly.
3. **Protected-range integrity:** every executable body, assertion-bearing declaration range, and referenced test-data
   payload must have the same raw bytes and SHA-256 before and after adaptation. A rewrite intersecting a protected
   range is rejected before it can emit a file.

This gives Metallurgy descriptive local names and an owned harness without weakening the upstream test. The upstream
plugin is never built, compiled, or loaded.

## Findings from the current tree

The current backport inventory is useful to a reader and pins
`8dd22d153b65c847f4ced8917dd7e02b83561e5d`, but it is not executable provenance. It embeds a developer-specific
absolute path, names a target path different from the actual `testkit/` directory, and records no file hashes
([current manifest](../../testkit/src/main/scala/org/jetbrains/plugins/scala/BACKPORT_MANIFEST.txt#L1-L15)).

The current compatibility tests also demonstrate why exact method bodies and exact helper contracts are necessary:

- Upstream keyword completion selects a lookup item and compares the resulting editor text, including its caret
  ([upstream example](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/lang/completion3/Scala3KeywordCompletionTest.scala#L14-L32)).
  The local port merely checks that the lookup string is present
  ([local port](../../src/test/scala/com/hmemcpy/metallurgy/compat/scala3/completion/Scala3KeywordCompletionCompatTest.scala#L5-L19)).
  That is a different and weaker assertion.
- Upstream `checkTextHasNoErrors` passes the supplied string directly to `configureByText`
  ([upstream helper](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/base/ScalaLightCodeInsightFixtureTestCase.scala#L190-L210)).
  The local override calls `text.trim`
  ([local helper](../../src/test/scala/com/hmemcpy/metallurgy/compat/scala3/Scala3CompatTestCase.scala#L131-L142)).
  It therefore removes source characters before IntelliJ sees the fixture.
- The current harness sanity test confirms that a handful of malformed examples fail
  ([sanity test](../../src/test/scala/com/hmemcpy/metallurgy/compat/scala3/Scala3CompatHarnessSanityTest.scala#L5-L50)).
  It does not establish equivalence for completion insertion, ordered findings, exact type rendering, resolve target
  identity, inspections, negative completion, test-name lookup, or multi-file setup.

The upstream helpers carry behavior not visible in a snippet alone. `TypeInferenceDoTest`, for example, controls file
names, parser-error handling, selected-expression lookup, and the behavior of tests expected to fail
([source](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/test/org/jetbrains/plugins/scala/lang/typeInference/TypeInferenceDoTest.scala#L27-L61)).
The integrity design must therefore preserve both the call site and the helper contract.

## Tracked layout

```text
upstream-tests/
  intellij-scala.json
  intellij-scala-selection.json
  adapters.json
  patches/
    <descriptive-support-adaptation>.patch
  rewrites/
    <descriptive-generated-suite>.json
third_party/
  intellij-scala/
    <full-revision>/
      LICENSE.txt
      scala/
        ... exact selected source and test-data files ...
src/test/generated/
  intellij-scala/
    ... generated adapted Scala sources ...
src/test/upstream-data/
  intellij-scala/
    ... exact copied fixture files ...
THIRD_PARTY_NOTICES.md
```

The pinned snapshot is intentionally narrow: it contains every source file and data file selected by the manifest,
every transitive upstream helper source used to define an adapter contract, and the root license. It is sufficient to
reproduce the adapted tests, but it is not an upstream build checkout.

`src/test/generated/intellij-scala` is checked in so code review can see package/base/name rewrites alongside unchanged
method bodies. It is excluded from scalafmt and all other source rewriters. The generator is the only writer.

Broad, independently authored Metallurgy tests remain under ordinary `src/test/scala` and `src/test/testdata` paths.
They are not counted as copied-test parity and do not share generated files with the upstream snapshot.

## Machine-readable manifest

Use strict JSON with a schema version. Unknown fields, duplicate entries, non-canonical paths, branch names, and
abbreviated revisions are errors.

```json
{
  "schemaVersion": 1,
  "origin": {
    "repository": "https://github.com/JetBrains/intellij-scala.git",
    "revision": "8dd22d153b65c847f4ced8917dd7e02b83561e5d",
    "licensePath": "LICENSE.txt",
    "licenseSha256": "<raw-byte sha256>"
  },
  "snapshotRoot": "third_party/intellij-scala/8dd22d153b65c847f4ced8917dd7e02b83561e5d",
  "supportFiles": [],
  "suites": []
}
```

An unchanged copied support file is declared as:

```json
{
  "originPath": "scala/scala-impl/test/org/jetbrains/plugins/scala/base/ScalaSdkOwner.scala",
  "snapshotPath": "scala/scala-impl/test/org/jetbrains/plugins/scala/base/ScalaSdkOwner.scala",
  "localPath": "testkit/src/main/scala/org/jetbrains/plugins/scala/base/ScalaSdkOwner.scala",
  "mode": "exact",
  "originBlob": "<Git blob id>",
  "sha256": "<raw-byte sha256>"
}
```

A support file that cannot remain exact is declared with a complete patch:

```json
{
  "originPath": "scala/scala-impl/test/org/jetbrains/plugins/scala/base/package.scala",
  "snapshotPath": "scala/scala-impl/test/org/jetbrains/plugins/scala/base/package.scala",
  "localPath": "testkit/src/main/scala/org/jetbrains/plugins/scala/base/package.scala",
  "mode": "patched",
  "originBlob": "<Git blob id>",
  "patch": "upstream-tests/patches/testkit-root-junit-import.patch",
  "resultSha256": "<raw-byte sha256>",
  "reason": "Resolve JUnit from the root package against the packaged Scala plugin."
}
```

Patches apply with zero fuzz and cannot touch a test method, literal fixture payload, assertion call, or expected
value. New Metallurgy behavior should normally be supplied by an adapter outside the copied package instead of
patching upstream support code.

A generated suite entry has this shape:

```json
{
  "id": "scala3-keyword-completion",
  "origin": {
    "path": "scala/scala-impl/test/org/jetbrains/plugins/scala/lang/completion3/Scala3KeywordCompletionTest.scala",
    "owner": "org.jetbrains.plugins.scala.lang.completion3.Scala3KeywordCompletionTest",
    "sourceBlob": "<Git blob id>",
    "sha256": "<raw-byte sha256>"
  },
  "generated": {
    "path": "src/test/generated/intellij-scala/com/hmemcpy/metallurgy/compat/scala3/completion/KeywordCompletionTest.scala",
    "owner": "com.hmemcpy.metallurgy.compat.scala3.completion.KeywordCompletionTest",
    "sha256": "<raw-byte sha256>"
  },
  "adapter": "scala3-completion-fixture.v1",
  "rewrite": "upstream-tests/rewrites/scala3-keyword-completion.json",
  "methods": []
}
```

Each method entry separates origin identity from the descriptive local identity:

```json
{
  "originName": "testInfixTopLevel",
  "localName": "testCompletesInfixAtTopLevel",
  "originBody": {
    "startByte": 612,
    "endByte": 773,
    "sha256": "<raw-byte sha256>"
  },
  "generatedBody": {
    "startByte": 744,
    "endByte": 905,
    "sha256": "<same raw-byte sha256>"
  },
  "upstreamRuntimeName": "InfixTopLevel",
  "tags": ["completion", "scala3", "ordinary-ci"]
}
```

Offsets are evidence generated from a parsed syntax tree, not hand-maintained selectors. The verifier reparses both
files, resolves owner and declaration uniquely, recomputes offsets, and then compares raw ranges. Moving a body is
allowed; changing one byte in it is not. The protected range starts immediately after a method's `=` or opening body
delimiter, or at a field initializer's expression, and includes the complete body through its final token and internal
trivia. Every executable declaration in the generated owner is protected, not only JUnit-discovered test methods.
For a declaration whose behavior is expressed in a default argument, annotation, or parameter list, those semantic
ranges are protected separately.

The body contains the original helper call and its arguments. The generator does not decode or re-encode string
literals, run scalafmt, normalize line endings, change indentation, remove comments, or reconstruct an AST printer
representation.

## Rewrite boundary

Each rewrite file contains only token-targeted edits:

```json
{
  "schemaVersion": 1,
  "package": {
    "from": "org.jetbrains.plugins.scala.lang.completion3",
    "to": "com.hmemcpy.metallurgy.compat.scala3.completion"
  },
  "imports": {
    "remove": [
      "org.jetbrains.plugins.scala.lang.completion3.base.ScalaCompletionTestBase"
    ],
    "add": [
      "com.hmemcpy.metallurgy.compat.scala3.adapters.Scala3CompletionFixture"
    ]
  },
  "owner": {
    "from": "Scala3KeywordCompletionTest",
    "to": "KeywordCompletionTest",
    "baseFrom": "ScalaCompletionTestBase",
    "baseTo": "Scala3CompletionFixture"
  },
  "methodNames": {
    "testInfixTopLevel": "testCompletesInfixAtTopLevel"
  },
  "annotations": {
    "removeAsWiring": ["org.junit.experimental.categories.Category"],
    "replaceEquivalent": {
      "org.jetbrains.plugins.scala.util.runners.RunWithScalaVersions": "scala3-completion-fixture.v1"
    },
    "preserveSemantics": ["org.jetbrains.plugins.scala.util.runners.WithIndexingMode"]
  }
}
```

Generation proceeds as follows:

1. Parse the exact snapshot source with a pinned parser.
2. Locate the declared owner and all selected test methods.
3. Mark every method body and field initializer, assertion-bearing signatures/annotations, embedded literals outside
   executable bodies, and referenced test-data bytes as protected.
4. Resolve every requested rewrite to exactly one non-protected token.
5. Reject overlapping edits, unresolved edits, edits that change token order beyond the declared host element, and
   edits intersecting protected bytes.
6. Apply edits from the end of the file toward the beginning so original byte ranges stay stable.
7. Insert a generated modification/provenance header.
8. Reparse the result, pair methods through the manifest, and require each protected range to be byte-identical.
9. Emit the source and all hashes deterministically.

The rewrite vocabulary is closed. It has no arbitrary search/replace, regular-expression replacement, source formatter,
body template, or post-processing command. Supporting a new host-language shape requires a generator implementation
and tests that prove protected ranges cannot be touched.

Method/class renaming is optional. Prefer descriptive local names, but do not rename when the upstream name already
describes the behavior clearly. Upstream tracker identifiers appear only in the manifest's origin fields; a generated
local name must describe behavior.

## Test data and runtime test-name mapping

Fixture files are copied from the exact snapshot without edits. Their relative path, file name, encoding, executable
bit, and raw content hash are manifest fields. Expected-output files are test data and receive the same treatment.
There is no whitespace normalization or expected-output rewriting.

Some upstream helpers derive a fixture path from `getTestName(false)`. Renaming a local method must not change that
lookup. The adapter base resolves the currently executing local owner/method through a generated name table and
returns the recorded `upstreamRuntimeName` when upstream helper code asks for the test name. Contract tests cover
JUnit 3 names, JUnit 4 `@Test` names, parameterized suffixes, camel-case conversion, and nested/generated suites.

Name mapping changes only fixture lookup. It does not rename a source file inside a fixture, relocate a package,
alter a test-data path recorded by the upstream method, or edit source text.

Multi-file tests declare every auxiliary path that the protected method or helper loads. Static references are
discovered from the method and helper tree; dynamic test-name-derived paths use the runtime-name table. A missing,
extra, or unmanifested file under the copied test-data root fails integrity verification.

## Fixture adapters preserve original helper contracts

Generated bodies retain the upstream helper names and argument lists. `adapters.json` records how those symbols become
available locally:

```json
{
  "id": "scala3-completion-fixture.v1",
  "base": "com.hmemcpy.metallurgy.compat.scala3.adapters.Scala3CompletionFixture",
  "helpers": [
    {
      "symbol": "doCompletionTest",
      "originPath": "scala/scala-impl/test/org/jetbrains/plugins/scala/lang/completion3/base/ScalaCompletionTestBase.scala",
      "originImplementationSha256": "<transitive implementation sha256>",
      "localContractTest": "Scala3CompletionFixtureContractTest"
    }
  ]
}
```

Use this implementation order:

1. Copy the upstream helper implementation unchanged when its dependencies already exist in the packaged plugin or
   copied testkit.
2. Otherwise expose the same method name, parameter list, defaults, and observable contract from a Metallurgy-owned
   adapter. Platform/module setup, exact compiler preparation, asynchronous readiness, and cleanup may differ.
3. Keep backend preparation outside the input/output transformation. An adapter may wait before invoking completion
   or highlighting, but it must pass the protected string unchanged and perform every upstream assertion afterward.

Every adapted helper requires contract tests that:

- capture the exact string and file name reaching `configureByText`/`addFileToProject`;
- prove markers map to the same offsets without deleting source characters;
- exercise every argument and default;
- pass the expected outcome;
- independently perturb every expected value, order, duplicate, severity, range, target, selected item, and invocation
  count that the upstream helper observes and require failure;
- prove negative assertions fail when the forbidden result appears;
- prove backend unavailability fails instead of falling through to bundled semantics;
- prove readiness waiting changes scheduling only, not configured or expected values.

For keyword completion, the adapter must invoke completion, select the requested item, and compare the complete result
text. A presence-only helper cannot satisfy `doCompletionTest`. For `checkTextHasNoErrors`, the captured text must
equal the protected argument exactly; calling `trim` fails the contract test.

The adapter registry records a digest over each pinned upstream helper implementation and its transitive helper
closures. A changed digest stops generation until the contract is reviewed. There is no per-test adapter callback,
weaker substitute helper, ignored parameter, output filter, or `allowDifference`.

## Test selection and completeness

Integrity and selection are distinct. Integrity proves that selected tests remain unchanged; it does not prove that
the selection is complete.

`intellij-scala-selection.json` accounts for every discovered Scala 3 test invocation under the declared roots. A
compiler-valid invocation is selected, snapshotted, generated, and executed. An assertion independently proven to
contradict the exact compiler remains snapshotted and generated unchanged and links to the separately defined
dotc-oracle-conflict proof. There is no product-scope exclusion for a compiler-valid Scala 3 test. Unsupported
extraction, missing fixture support, and an unclassified invocation are hard failures rather than dispositions.

Candidate source discovery may conservatively use paths and syntax inspection, but those heuristics are not the
completeness authority. The authoritative invocation inventory is produced from the locally compiled copied classes,
without compiling the upstream plugin. An owned collector mirrors the upstream runner's runtime rules: it reflects
public zero-argument JUnit 3 `test*` methods and JUnit 4 tests through inheritance, injects the exact Scala version,
evaluates the copied `supportedIn` and version annotations, expands registered parameterized/generated suites, and
retains category and indexing-mode metadata. The selection gate requires a one-to-one mapping between that inventory
and selected or proven-conflict entries. Free-form “temporarily skipped,” `@Ignore`, substring filters, missing
entries, and silent extractor omissions are invalid.

Execution tags choose schedules without changing source or assertions:

- ordinary CI includes representative tests for every implemented helper and PSI production, including multi-file and
  deeply nested examples;
- the parity lane runs every selected compiler-valid test;
- separately authored broad tests and pinned real-project graduation remain independent lanes.

Local broad examples must not be presented as a substitute for an unselected copied test.

## Verification commands

Add bounded sbt tasks:

```sh
# Verify the exact snapshot, generated tree, manifests, protected ranges, data closure, and adapter contracts.
sbt -batch -no-colors verifyCopiedIntellijTests

# Compare the vendored snapshot with immutable Git objects. A dirty working tree is harmless.
sbt -batch -no-colors \
  -Dintellij.scala.repo="$INTELLIJ_SCALA_REPOSITORY" \
  verifyCopiedIntellijTestsAgainstOrigin

# Regenerate into target/ and diff it with the checked-in adapted tree. Never overwrite tracked sources.
sbt -batch -no-colors generateCopiedIntellijTests

# Discover candidate/disposition drift at a proposed immutable revision.
sbt -batch -no-colors \
  -Dintellij.scala.revision=<40-character-commit> \
  discoverCopiedIntellijTests
```

`verifyCopiedIntellijTests` must:

1. validate strict schemas and canonical relative paths;
2. verify every snapshot, generated source, copied data, patch, and manifest hash;
3. reparse origin and generated Scala sources and prove every protected range byte-identical;
4. regenerate into `target/` and compare the entire adapted tree;
5. verify each rewrite resolves once and remains outside protected bytes;
6. apply support-file patches in memory with zero fuzz and compare complete results;
7. reject unmanifested files under snapshot, generated, copied-data, and copied-testkit roots;
8. prove every generated local owner/method maps to exactly one upstream owner/method;
9. verify runtime-name mappings for all name-derived fixture paths;
10. require and run every adapter contract test;
11. verify license and third-party notices;
12. compare the runtime invocation inventory with selected and proven-conflict entries.

The against-origin task obtains bytes only with revision-qualified Git operations:

```sh
git -C "$INTELLIJ_SCALA_REPOSITORY" cat-file -e \
  8dd22d153b65c847f4ced8917dd7e02b83561e5d^{commit}
git -C "$INTELLIJ_SCALA_REPOSITORY" show \
  8dd22d153b65c847f4ced8917dd7e02b83561e5d:scala/scala-impl/test/.../Scala3ExtensionsTest.scala
```

An adjacent checkout is only a Git object store; its working-tree files are never read. CI fetches the exact full
commit into a shallow or bare cache. It does not clone submodules, resolve upstream build dependencies, load upstream
sbt settings, compile upstream code, or run upstream tests.

The normal `check` alias verifies the checked-in snapshot and generated tree before `Test / compile`. Pull-request CI
also runs against-origin verification and the representative generated-test slice. The full lane runs all selected
tests and candidate/disposition completeness. Repository JBR 25, GNU `gtimeout`, and `JAVA_HOME` rules apply to all
sbt invocations.

## Update workflow

Moving the upstream pin is an explicit review:

1. Fetch the proposed 40-character commit into a clean or bare Git object store.
2. Run source discovery and the locally compiled runtime collector at old and new revisions. Review added, removed,
   renamed, inherited, generated, parameterized, and Scala-version applicability changes.
3. Select every compiler-valid invocation; preserve and link the exact-version proof for every asserted conflict.
4. Copy selected origin blobs and test data exactly into a proposed snapshot under `target/`.
5. Reapply declarative host-token rewrites. Any fuzzy, ambiguous, or protected-range edit fails.
6. Review generated diffs. Method-body differences are never accepted as adaptations; they must be exact upstream
   changes caused by moving the snapshot pin.
7. Review helper implementation digest changes and update adapter implementations/contracts without modifying call
   sites.
8. Rebase support-file patches with zero fuzz. Prefer removing a patch when the adapter can own the difference.
9. Preserve changed snippets, assertion calls, and expected outputs exactly as they appear at the new revision.
10. Run exact-version dotc proof for a newly disputed assertion and link a separately tracked conflict proof.
11. Run snapshot, generated-tree, protected-range, against-origin, adapter-contract, representative, and full parity
    gates.
12. Commit the pin, snapshot, manifest, generated sources/data, rewrites, adapters, and notices together.

Generation writes only to `target/`. A separate explicit copy step accepts reviewed output; the generator cannot edit
tracked files. Editing the revision alone fails because blob IDs, snapshot bytes, helper digests, generated hashes, and
candidate dispositions no longer agree.

## License and provenance

The pinned upstream repository uses Apache License 2.0
([upstream license](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/LICENSE.txt)).
Section 4 requires distribution of the license, prominent notices on modified files, retention of applicable
attribution, and reproduction of an upstream notice file when present. Metallurgy's root license includes those terms
([local license](../../LICENSE#L90-L118)).

The snapshot therefore includes upstream `LICENSE.txt` exactly, and `THIRD_PARTY_NOTICES.md` names JetBrains IntelliJ
Scala, its repository, revision, license, and copied paths. The pinned tree has no root `NOTICE` file; origin
verification checks that fact, and a future added notice blocks a pin update until it is included.

Exact snapshot files retain all original notices. Every adapted generated source carries a prominent header stating
that Metallurgy changed host wiring and names while preserving protected test bodies, plus the origin path and
revision. Existing copyright/license headers remain. A patched support source carries its required modification
notice and is reproducible from its declared patch. The third-party notice supplements rather than replaces per-file
requirements.

## Failure modes

| Failure | Detection | Required response |
|---|---|---|
| Pinned commit absent | `git cat-file` fails | Fetch that exact commit or fail; never use a branch head. |
| Snapshot differs from Git | Raw hash/object comparison fails | Restore the exact blob or move the pin through the update workflow. |
| Origin selector resolves zero or multiple times | Parsed selector cardinality differs from one | Review the upstream structure and update the manifest explicitly. |
| A rewrite intersects a method body or assertion-bearing range | Interval check fails before generation | Move the adaptation outside the protected range. |
| Generated method body differs by whitespace, comment, or literal encoding | Raw protected-range hash differs | Restore the exact bytes; do not format generated sources. |
| Helper call remains but local helper is weaker | Adapter mutation contract fails | Implement the full upstream contract under the same helper name. |
| A helper trims, wraps, relocates, or normalizes input | Fixture capture differs from protected argument bytes | Remove the transformation and adapt setup around the call. |
| Descriptive renaming breaks `getTestName` lookup | Runtime-name contract/data closure fails | Add or correct the explicit origin-name mapping. |
| Test-data file changes path, bytes, encoding, or mode | Snapshot/data hash fails | Preserve it exactly or review the upstream change with a new pin. |
| Parameterized case is reordered or duplicated | Expanded candidate identity changes | Review every invocation and regenerate mappings. |
| Support source drifts | Exact comparison or patch result differs | Restore it or revise a small reviewed patch. |
| New upstream test is omitted | Runtime inventory/accounting completeness fails | Select it or attach a valid exact-version conflict proof; unsupported extraction remains a failure. |
| A disputed assertion is edited or hidden | Protected range and proof-link checks fail | Preserve it and adjudicate against exact-version dotc. |
| Generated/copied file has no manifest entry | Root closure check fails | Add provenance or move independently authored work outside copied roots. |
| License or notice material is missing | Packaging integrity fails | Restore required material before distribution. |
| Verifier reads a dirty upstream working tree | Dirty-tree mutation changes verifier output | Treat it as a verifier bug; all origin reads must be revision-qualified. |

## Acceptance criteria

The integrity harness is complete when:

1. the prose-only backport inventory is replaced by the exact snapshot and executable manifest;
2. every selected source, helper source, and test-data file is proven against the pinned Git commit;
3. generation allows only declared package/import/base/annotation/name wiring outside protected ranges;
4. every executable body and initializer in a generated suite is byte-identical to upstream;
5. every generated assertion invokes the same upstream helper name with the same arguments and expected values;
6. adapters expose those helper contracts completely, with mutation-tested behavioral coverage;
7. keyword completion selects the item and compares final editor text, and no source helper trims or wraps a fixture;
8. descriptive renaming preserves upstream runtime names for test-data discovery;
9. runtime discovery reports no missing, excluded, or unsupported compiler-valid Scala 3 invocation;
10. exact snapshot, generated tree, against-origin, representative, and full parity lanes pass without building the
    upstream plugin;
11. distribution contains the required license, provenance, and per-file modification notices.

Only a test satisfying all applicable gates is counted as an unchanged upstream test.
