# Deterministic test lanes

Each `<lane>.txt` file is a bytewise-sorted, duplicate-free list of fully qualified test-suite classes. Its
`<lane>.invocations.txt` companion lists every expected `class<TAB>test-name` identity. Selection is explicit: wildcards
and runtime discovery never decide which suites or test methods a lane intends to execute.

`ci.txt` is the representative ordinary check. `compatibility.txt` is the current locally owned Scala 3 compatibility
area.

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
