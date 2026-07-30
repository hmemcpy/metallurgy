# Deterministic test lanes

Each `<lane>.txt` file is a bytewise-sorted, duplicate-free list of fully qualified test-suite classes. Its
`<lane>.invocations.txt` companion lists every expected `class<TAB>test-name` identity. Selection is explicit: wildcards
and runtime discovery never decide which suites or test methods a lane intends to execute.

`ci.txt` is the representative ordinary check for the currently admitted grammar and backend surface.
`compatibility.txt` is the complete locally owned Scala 3 compatibility area, including tests that remain red while
their syntax roles are unsupported. The broader retained evidence is split by ownership boundary:

- `baseline-syntax-psi.txt` covers parser/AST shape, public Scala PSI accessors, stubs, indices, closed-file loading,
  navigation, copies, reload, project close/reopen, and smart pointers.
- `baseline-editor-operations.txt` covers daemon highlighting, completion, find usages, hover, documentation, parameter
  info, inspections, structure view, rename, inline, change-signature, introduce-variable, extract-method, and
  implement-method operations.
- `baseline-semantics.txt` covers semantic publication and invalidation, current failure states, compiler-only symbols,
  capability discovery, direct best-effort controls, and the ordinary-build two-module editor scenario.
- `baseline-invocation-accounting.txt` verifies generated-test accounting, adapter contracts, exact compiler-conflict
  proofs, and the representative unchanged generated suite.
- `compatibility.txt` executes the complete currently selected compatibility inventory.

The baseline lanes record current failures as ordinary JUnit failures; they never turn them into expected passes. The
runner therefore returns nonzero while a recorded defect remains, while still retaining complete invocation evidence
for every later comparison.

## Run a lane

Use JetBrains Runtime 25:

```sh
JBR=~/.metallurgyPluginIC/sdk/261.26222.65/jbr/Contents/Home
env JAVA_HOME="$JBR" PATH="$JBR/bin:$PATH" \
  scripts/run-test-lane.sh test-lanes/ci.txt
```

The runner uses GNU `gtimeout` on macOS and GNU `timeout` on Linux. Every suite runs in its own bounded sbt/IntelliJ
process. A failing or timed-out suite cannot prevent later suites from running.

Useful options:

```text
--plan-only
--run-id <stable-output-identity>
--timeout-seconds <per-suite-seconds>
```

The runner rejects unsorted manifests, duplicates, invalid identities, mismatched suite/invocation manifests, or a
selected class absent from sbt test discovery.

## Evidence

The default output is:

```text
target/test-evidence/<lane>/<run-id>/
```

Each run retains:

- the exact manifest and deterministic `selection.json`;
- source revision, working-tree status, and source patch;
- JBR, sbt, Scala, IntelliJ, Scala-plugin, and fixture compiler coordinates;
- a SHA-256 inventory of every test classpath entry;
- discovery stdout, stderr, exit code, and complete discovered test names;
- one directory per suite containing stdout, stderr, exit code, JUnit XML, IntelliJ system/log state, and a normalized
  result;
- an atomically published `summary.json`.

Completed test failures, per-suite timeouts, missing reports, unexpected reports, invocation mismatches, invalid
reports, masked failures, and process failures have distinct statuses. The runner retains the expected, actual,
missing, and unexpected invocation identities for each shard, completes every selected shard, publishes the summary,
and returns nonzero unless all shards pass.

`scripts/test-test-lane-runner.sh` proves that identical plans produce byte-identical selections, invalid or missing
selections fail, every shard runs after a timeout or test failure, and zero-invocation reports cannot pass.
