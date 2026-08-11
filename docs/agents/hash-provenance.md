# Hash provenance and expected-value updates

## Purpose

Hashes are useful only when their role and trust source are clear. A digest can show that bytes changed. It does not
explain the change, prove compiler meaning, or authenticate bytes when the expected digest came from the same place.

This policy applies to hashes, fingerprints, expected inventories, stable IDs, schema values, exact revisions, tool
pins, and retained evidence. It does not authorize changing any of them. A packet that changes a source of truth must
also update and prove every expected value owned by that source.

## Value classes

| Class | Examples | Rule |
| --- | --- | --- |
| Compatibility and stable identity | Persistence schema versions, serializer and index IDs, external IDs, grammar roles, output roles, production IDs | Keep stable. A rename, reuse, removal, or schema change needs an explicit compatibility or migration decision. |
| Upstream and source integrity | Exact upstream revisions, Git blob IDs, reviewed copied fixture bytes | Keep exact. Trust comes from comparison with an independently fetched pinned source, not from a local digest alone. |
| Deterministic consistency | Parser evidence, scanner evidence, runtime inventory, catalog, physical plan, and surface fingerprints | Keep checks that reject mixed or stale inputs. Show readable rows, roles, ranges, and order before the digest. |
| Brittle golden snapshot | Aggregate parser, inventory, plan, ordering, or installed-surface expected digests | Do not use as the only oracle. Retain only when direct structural assertions explain what the digest summarizes. |
| Evidence and archive bookkeeping | `SHA256SUMS`, lane output hashes, report and log digests | Keep when the raw bytes remain available. A seal identifies retained evidence; it does not prove the implementation correct. |
| Tool and version pin | Compiler, IntelliJ, Scala plugin, JBR, sbt, testkit, and probe versions | Keep exact enough to reproduce the admitted compiler and host cell. Update all owned copies together. |
| Safety and resource limit | Timeouts, retries, cache sizes, depth and width bounds | Keep named limits with a workload reason. They are operating policy, not integrity values. |

Exact copied and upstream provenance, stable compatibility IDs, persistence contracts, and archive hashes whose raw
bytes remain available must not be removed as "hardcoding." Aggregate hashes are secondary evidence when readable
roles, rows, ranges, cardinality, or order can be asserted directly.

SHA-256 is not automatically a security control. It authenticates content only when the expected value or source is
obtained independently. A digest generated beside a download detects later local corruption but does not authenticate
the original download.

## Current owners and commands

Each kept value needs one source of truth, a deterministic generation command, a verification command, an update
owner, and a useful mismatch. The table records what exists now. "None today" is a known gap, not permission to copy
an observed digest into an expected value.

| Contract | Source of truth and update owner | Deterministic generation | Verification | Required mismatch |
| --- | --- | --- | --- | --- |
| Copied IntelliJ sources and protected fixture body | `upstream-tests/intellij-scala.json`, `upstream-tests/intellij-scala-selection.json`, and the pinned bytes under `third_party/intellij-scala/`; the copied-test packet owns updates | `sbt generateCopiedIntellijTests` writes a candidate under `target/` | `sbt verifyCopiedIntellijTests`; then `sbt -Dintellij.scala.repo=<independent-checkout> verifyCopiedIntellijTestsAgainstOrigin` | Name the missing or changed path, origin blob, protected range, method, or invocation before any digest. |
| Local copied-test adapters | `upstream-tests/adapters.json`; the adapter and its contract test own an update together | None today. The current verifier computes local file digests. | `sbt verifyCopiedIntellijTests` plus the named adapter contract tests selected by that alias | Name the changed adapter or contract file. Its digest is bookkeeping and cannot bless its behavior. |
| Stable grammar, output, production, target, serializer, and index identities | `src/main/scala/com/hmemcpy/metallurgy/psiproducer/Scala3PsiCatalogModel.scala`, `Scala3PsiProductionSupport.scala`, and the ten `Scala3Psi*Productions.scala` files in that directory; the catalog owner controls changes | None today. There is no standalone stable-ID registry dump. | `sbt "testOnly com.hmemcpy.metallurgy.psiproducer.Scala3PsiProductionCatalogTest"` | Show missing, extra, renamed, reused, or reordered IDs and the compatibility effect. |
| Persistence schema and catalog/plan fingerprints | `PersistedSchemaStructure` owns schema 14, root and child external IDs, persisted topology, serializers, stubs, indices, and navigation. `CatalogPlanStructure` owns stable roles and the complete reviewed catalog and physical-plan contract. Both are in `src/main/scala/com/hmemcpy/metallurgy/psiproducer/Scala3PsiProductionCatalog.scala`; the catalog and stub packet owns changes. | With the exact manifest JBR in `JAVA_HOME`, run `scripts/generate-catalog-structure.sh <run-id>`. It writes readable structures, a representative actual plan, fingerprints, and `SHA256SUMS` under `target/catalog-structure/<run-id>/`. | Run the command twice with different IDs, verify both manifests, and compare the two directories byte for byte. Then run `sbt "testOnly com.hmemcpy.metallurgy.psiproducer.Scala3PsiProductionCatalogTest"`. | State the compatibility or planning meaning, then list missing, extra, changed, and reordered rows. Print persistence and catalog hashes last. AST-only outputs must not appear in persisted identity. |
| Parser and scanner evidence fingerprints | `src/main/scala/com/hmemcpy/metallurgy/pc/Scala3ParserBridge.scala`; each parser family owns its exact fixture and expected evidence | None today as one repository command. Parser inventory tests compute fingerprints for their fixtures. | Run the exact named `com.hmemcpy.metallurgy.pc.*ParserInventoryTest` classes affected by the source change. | Start with products, fields, ranges, scanner tokens, attachments, and order. Raw fingerprint detail comes last. |
| Runtime production and installed PSI surface fingerprints | `src/main/scala/com/hmemcpy/metallurgy/psiproducer/CompilerRuntimeInventory.scala` and `src/main/scala/com/hmemcpy/metallurgy/psiproducer/ScalaPsiSurfaceInventory.scala`; runtime inventory and host compatibility packets own changes | None today as a checked structural dump. | `sbt "testOnly com.hmemcpy.metallurgy.psiproducer.Scala3PsiProductionCatalogTest"` and the affected parser inventory tests | Show missing, extra, changed, or reordered productions, methods, target roles, and capability bindings. A later cleanup packet must add a single structural dump command. |
| Test lane suites and invocations | `test-lanes/*.txt` and `test-lanes/*.invocations.txt`; the packet adding or removing tests owns both | None today. `scripts/run-test-lane.sh` validates and copies the hand-maintained manifests but does not generate them. | `scripts/test-test-lane-runner.sh`, then run `scripts/run-test-lane.sh <lane-manifest> --run-id <new-id>` with isolated `METALLURGY_TEST_EVIDENCE_DIR` | Show missing, extra, duplicate, or reordered suites and invocations. Empty manifests, missing discovery, zero-invocation reports, and filtered zero-test runs must fail. A later cleanup packet will add one generator and remove duplicated hand maintenance. |
| Evidence reports and archives | `docs/evidence/README.md` and the commit-scoped packet summary; that packet owns its seal | Create a sorted manifest from the completed external bundle, for example `(cd <bundle> && find . -type f ! -name SHA256SUMS -print0 | LC_ALL=C sort -z | xargs -0 shasum -a 256 > SHA256SUMS)` | Run the archive completeness and hash check below | Name a missing, extra, or changed archive entry first, then its size and digest. The report must include an immutable external archive reference. |
| Exact tool and host pins | `project/metallurgy-baseline.properties`; a toolchain update owns the manifest and every named consumer together | Edit the strict ASCII, bytewise-key-sorted manifest first; static verification then names every consumer that must change | `java scripts/MetallurgyBaselineVerifier.java static`; then run `java scripts/MetallurgyBaselineVerifier.java host "$METALLURGY_INTELLIJ_HOME"` under the SDK JBR; reload each build and run `sbt check` | Name the coordinate, expected and actual values, file, and semantic location. Missing, extra, and mismatched consumers block delivery. |
| Safety and resource limits | The enforcing file, including `scripts/run-test-lane.sh`, `src/main/scala/com/hmemcpy/metallurgy/pc/PcSession.scala`, and `src/main/scala/com/hmemcpy/metallurgy/pc/PcSnapshotStore.scala` | Not generated | Run the focused lifecycle, timeout, eviction, or runner test owned by the changed limit | State which workload can hang, fail early, leak, or consume excess resources. Do not present the value as a hash contract. |

Run sbt commands with the JBR and timeout required by `AGENTS.md`. A command listed here verifies only the contracts it
actually checks. It does not become a general update command merely because it exits successfully.

The baseline manifest owns compatibility coordinates, not local paths. `METALLURGY_INTELLIJ_HOME` selects the SDK.
Bootstrap Java may run static verification and read a validated value before SDK acquisition. Host verification runs
only with the SDK's embedded JBR and checks product metadata, the Scala plugin descriptor, the JBR release file, and
the live runtime identity. A baseline update must preserve resolved dependency, plugin, package, copied-input, and lane
structure unless the same reviewed change explicitly owns that difference.

Host verification is platform-specific. A macOS lifecycle result does not prove the Linux SDK or JBR. Run the Linux
host gate only where the exact Linux IntelliJ SDK and its embedded JBR are available; never substitute a guessed JBR
download. Until that gate runs, record Linux host verification as unavailable rather than inferred.

The archive verifier must reject extra unlisted files as well as missing or changed files:

```sh
(
  cd <bundle>
  actual=$(mktemp)
  manifested=$(mktemp)
  trap 'rm -f "$actual" "$manifested"' EXIT
  find . -type f ! -name SHA256SUMS -print | LC_ALL=C sort > "$actual"
  sed -E 's/^[0-9a-f]{64}  //' SHA256SUMS | LC_ALL=C sort > "$manifested"
  if cmp "$manifested" "$actual"; then
    shasum -a 256 -c SHA256SUMS
  else
    exit 1
  fi
)
```

## Accepting an expected-value change

An expected fingerprint or inventory may change only when all of these are true:

1. Record the exact source commit and tree. Record exact compiler artifacts and options and the IntelliJ, Scala
   plugin, and JBR identity when they affect the result.
2. Use a clean isolated worktree or clone. Use an isolated output, cache, test root, and evidence directory where the
   tool permits it. Do not harvest final values from an existing incremental `target`.
3. Regenerate twice from the same recorded inputs and require byte-identical structural output.
4. Prove non-zero test discovery and invocation. An sbt `testOnly` command that reports success after selecting no
   tests is not evidence.
5. Review a direct structural diff of the intended rows, roles, IDs, ranges, methods, or invocations. Explain each
   addition, removal, change, and reorder before considering the aggregate digest.
6. Establish independent meaning. Candidate code and a digest produced by that same code cannot bless each other.
   Use exact-version compiler evidence, independently pinned upstream bytes, compatibility requirements, or direct
   reviewed structure as the oracle.
7. Run every required verifier and require green results. A red required verifier blocks sealing, commit delivery,
   and integration. There is no approved stale mismatch.
8. Review the expected-value diff itself. Never paste a candidate-produced digest into expected output without the
   independent proof above.

A stale expected value is fixed in the same packet that changes its source. It must not be carried into later packet
evidence.

## Mismatch output

Mismatch output starts with practical meaning, such as "one production role was removed" or "copied bytes differ
from the pinned upstream blob." It then lists readable missing, extra, changed, and reordered structures. Raw expected
and actual hashes come last. A hash-only failure is not enough when the producer can emit readable structure.

## Commit-scoped evidence

Evidence seals and reports are immutable after their exact commit and tree scope is recorded. A historical report
describes that commit only. It must never label its schema, catalog, plan, or inventory value as current for a later
commit. Reports that predate this rule may receive one clearly marked scope correction that does not change their
commands, hashes, or recorded result. After that correction, later mistakes use a separate erratum or superseding
report rather than rewriting history.

When raw bundles live outside Git, record the exact command, archive size, archive manifest hash, inner manifest name,
verified entry count, and an immutable external archive reference. A path to a transient local `target` directory is
not an archive reference. Keep the raw bytes for the retention period in `docs/evidence/README.md`.

Two confirmed examples show why these rules matter:

- The copied-adapter digest stayed stale through several deliveries until commit `f13451a`. The copied upstream bytes
  were not shown to be corrupt; the required local bookkeeping verifier was red. A red required verifier now blocks
  the seal and delivery.
- The P123-H refinement and annotated-type report was correct for its own commit. P123-I later altered the catalog
  value, but later text still quoted the P123-H value as current. Historical values now stay tied to their own commit
  and tree.

Retained evidence does not prove that the final refinement or capture values were harvested from a dirty build. That
claim remains unproven and must not be repeated as fact.

## Maintainer checklist

### Generate

- Identify the value class, source of truth, owner, exact commit and tree, artifacts, host, and options.
- Start from a clean isolated worktree and isolated output or cache where supported.
- Run the documented generation twice and prove non-zero test discovery and invocation.

### Review

- Read the structural missing, extra, changed, and ordered diff before hashes.
- Check independent compiler, upstream, or compatibility meaning. Reject circular self-blessing.
- Review every expected-value change and require all named verifiers to pass.

### Deliver

- Update stale expected values in the same packet as their source.
- Seal only green results. Record the exact command, commit, tree, identities, archive reference, size, and hashes.
- Review the final diff and prove protected copied bytes, stable IDs, schema values, pins, and lane inputs changed only
  when the packet explicitly owns them.

### Audit later

- Verify the archive manifest against available raw bytes and its immutable external reference.
- Compare an independent current file inventory with the archive manifest so extra unlisted files cannot pass.
- Read historical reports only in the context of their recorded commit and tree.
- Confirm current docs do not quote an older schema, catalog, plan, or inventory value as current.
