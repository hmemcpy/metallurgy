# IntelliJ lifecycle validation

This standalone build adapts the exact ide-probe and IntelliJ coordinates in the
[`baseline manifest`](../project/metallurgy-baseline.properties). It starts a clean IDE under Xvfb, installs the pinned
Scala plugin and the current Metallurgy package, imports the `dogfood` sbt build, waits for indexing and
Metallurgy parser preparation, highlights a Scala 3 source file, and checks both the IDE message pool and `idea.log`.
The test retains its stage timeline, project state, highlights, and logs under `target/ideprobe-artifacts`.

The cross-platform IntelliJ SDK has no `Contents/MacOS/idea`. On macOS, the pinned ide-probe therefore falls back to
`Contents/bin/idea.sh`, which carries Linux amd64 JNA and module settings. The repository wrapper corrects only the IDE
child on macOS arm64. It does not change the SDK, global Java settings, the ide-probe driver, or user wrappers.

`run-ide-probe.sh` reads the current host OS and architecture from `uname`. It maps Darwin to the product metadata name
`macOS` and host `arm64` to product metadata `aarch64`, then requires exactly one matching launch entry in the selected
SDK's `product-info.json`. The wrapper selects that entry's JNA option and required Apple desktop module openings,
expands the SDK placeholder, and passes them through `METALLURGY_IDEA_JAVA_OPTIONS`. `ideprobe.conf` maps that dedicated
variable to child-only `_JAVA_OPTIONS`. No product version or machine path is encoded in the wrapper.

## Running

Install `jq`, GNU coreutils, XQuartz with Xvfb, and the repository-supported `xvfb-run` before running the harness. Export
these paths when they are not already provided by `.agents/setup`:

```sh
export METALLURGY_REPO_ROOT="$(cd .. && pwd)"
export METALLURGY_INTELLIJ_HOME="<path-to-the-pinned-IntelliJ-SDK>"
if [[ -d "$METALLURGY_INTELLIJ_HOME/jbr/Contents/Home" ]]; then
  export JAVA_HOME="$METALLURGY_INTELLIJ_HOME/jbr/Contents/Home"
else
  export JAVA_HOME="$METALLURGY_INTELLIJ_HOME/jbr"
fi
export PATH="$HOME/.local/bin:$JAVA_HOME/bin:$PATH"
unset _JAVA_OPTIONS METALLURGY_IDEA_JAVA_OPTIONS
```

On a machine with enough memory, package the plugin and run the harness normally:

```sh
cd "$METALLURGY_REPO_ROOT"
gtimeout --kill-after=5s 5m sbt -batch -no-colors packageArtifact
cd ideprobe-tests
gtimeout --kill-after=15s 15m ./run-ide-probe.sh sbt -batch -no-colors test
```

In a memory-constrained orb, exit sbt before launching IntelliJ. This avoids retaining the sbt host heap while the IDE
indexes dependencies:

```sh
cd "$METALLURGY_REPO_ROOT/ideprobe-tests"
gtimeout --kill-after=5s 3m sbt -batch -no-colors \
  prepareProbe261 prepareMetallurgyPlugin prepareScalaPlugin \
  'Test/compile' 'export Test/fullClasspath'

classpath="$(cat target/streams/test/fullClasspath/_global/streams/export)"
gtimeout --kill-after=15s 15m \
  ./run-ide-probe.sh \
  "$JAVA_HOME/bin/java" \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  -Xms64m -Xmx300m -cp "$classpath" \
  org.junit.runner.JUnitCore \
  com.hmemcpy.metallurgy.ideprobe.ProjectLifecycleTest
```

The `java.lang` opening is required by the pinned ide-probe to reconstruct a remote stack trace on the pinned JBR. It does not alter
the IDE under test.

On Linux and non-arm64 macOS, the wrapper directly executes the requested command without setting
`METALLURGY_IDEA_JAVA_OPTIONS`; the ide-probe child environment remains unchanged. Do not set the dedicated variable
manually. The wrapper owns it so stale arm64 values cannot reach another host.

## Troubleshooting

- A missing or duplicate `macOS/aarch64` launch entry is a mismatch in the selected SDK. Check
  `$METALLURGY_INTELLIJ_HOME/product-info.json`; do not copy options from another SDK.
- Missing or repeated JNA and Apple module options fail before the driver starts. Use the exact complete SDK rather than
  weakening the check.
- A missing JNA directory means the selected product metadata and SDK contents disagree.
- If the wrapper reports that `METALLURGY_IDEA_JAVA_OPTIONS` is already set, unset it. If the driver itself prints
  `Picked up _JAVA_OPTIONS`, remove that global setting; only the IDE child should receive it.
- No wrapper message or Java option injection is expected on Linux or Intel macOS.

Historical diagnosis may exist at
`target/qualification/epic85-idea261-378d950-20260801T1150Z/diagnosis-summary.md`. This ignored path is machine-local,
non-normative evidence only. Current behavior always comes from the selected SDK's `product-info.json`.
