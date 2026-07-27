# Executable pre-migration baseline

## Result

The current implementation is measured by four focused deterministic lanes plus the complete selected compatibility
inventory. Every lane has a sorted suite manifest, an exact invocation manifest, one bounded process per suite, and
retained JUnit, stdout, stderr, IntelliJ log, environment, source-state, and exit evidence.

| Lane | Suites | Tests | Passing | Failing | Result |
|---|---:|---:|---:|---:|---|
| Syntax and PSI | 5 | 39 | 39 | 0 | passed |
| Editor operations | 4 | 22 | 18 | 4 | recorded failures |
| Semantics | 4 | 72 | 68 | 4 | recorded failures |
| Invocation accounting | 4 | 13 | 13 | 0 | passed |
| Complete compatibility inventory | 47 | 405 | 392 | 13 | recorded failures |

The machine-readable record is
[`current-parity-and-failure-baseline.json`](current-parity-and-failure-baseline.json). The retained run summaries are
under `target/test-evidence/`. The compatibility and original focused measurements are reconstructible at
`40145b89b26a4cfe62f887732a95cf0ad1705616`; the strengthened syntax and lifecycle lane is reconstructible at
`6bf2cc03d12cab3d07f0873024087a8b7d23447d`.

## Coverage boundaries

`baseline-syntax-psi` exercises parser and AST shape, public Scala PSI accessors, stub creation, index lookup,
closed-file stub lookup and navigation without AST loading, copies, reload, project close/reopen, and smart pointers.

`baseline-editor-operations` exercises daemon highlighting, completion, find usages, hover, documentation, parameter
info, inspections, structure view, rename, inline, change signature, introduce variable, extract method, and implement
method.

`baseline-semantics` exercises semantic mapping, publication, invalidation, stale and unavailable states,
compiler-only symbols, capability discovery, direct best-effort controls, and the build-produced two-module editor
scenario.

`baseline-invocation-accounting` verifies generated-test accounting, adapter contracts, exact compiler-conflict proofs,
and the representative unchanged generated suite. `compatibility` executes all 405 currently selected compatibility
invocations.

## Build-produced cross-module contract

`BetastyCompileServerTest` creates a real IntelliJ module B with a dependency on module A. IntelliJ's ordinary compile
server builds a deliberately broken A and must report the real compiler error while emitting `Person.betasty`. In B,
the editor must then:

- contain no highlight errors for valid downstream code;
- resolve the valid upstream member through both bundled PSI and the compiler backend;
- offer the valid member through actual editor completion;
- remain correct after A is repaired and rebuilt;
- replace the old member after A changes its public API, with no stale completion item from the same prefix lookup;
- remove deleted members after a clean rebuild;
- recover correct downstream semantics after the compile-server process is stopped and restarted.

The lower-level `BetastyCrossModuleTest` remains a direct compiler and presentation-compiler control.

## Recorded failures

The focused editor lane records four failures in `BundledCompilerBackendConsumerTest`: receiver completion source
lookup, compiler-driven inspection input, implement-method input, and extract-method output.

The focused semantic lane records two state-isolation failures in `BundledCompilerBackendShimTest` and two
document-generation/type-slot failures in `CompilerBackendSnapshotPublisherTest`.

The complete compatibility inventory records 13 unchanged-test disagreements across named tuples, extensions,
universal apply, curried type parameters, infix type arguments, and overloaded higher-order calls. The two generated
named-type-argument fixture crashes in the superseded measurement no longer occur. The 110-test opaque-type suite takes
about 270 seconds and its 19-test integration companion about 90 seconds, so their completed evidence uses a
600-second per-suite bound.

These are ordinary visible failures, not expected-pass annotations. The lanes return nonzero while they remain.
Every compiler disagreement remains a Metallurgy defect until exact-version compiler proof classifies an upstream
oracle conflict.

## Reproduction

Use JBR 25 and run any manifest through the deterministic runner:

```sh
JBR=~/.metallurgyPluginIC/sdk/261.26222.65/jbr/Contents/Home
env JAVA_HOME="$JBR" PATH="$JBR/bin:$PATH" \
  scripts/run-test-lane.sh test-lanes/baseline-syntax-psi.txt
```

The other maintained manifests are:

```text
test-lanes/baseline-editor-operations.txt
test-lanes/baseline-semantics.txt
test-lanes/baseline-invocation-accounting.txt
test-lanes/compatibility.txt
```

Use at least 600 seconds per suite for the complete compatibility inventory. Invocation mismatches, missing or
unexpected reports, timeouts, process failures, and test failures remain distinct in `summary.json`.
