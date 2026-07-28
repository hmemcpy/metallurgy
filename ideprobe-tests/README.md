# IntelliJ lifecycle validation

This standalone Scala 2.13 build adapts ide-probe 0.53 to IntelliJ 261. It starts a clean IDE under Xvfb, installs the
pinned Scala plugin and the current Metallurgy package, imports the `dogfood` sbt build, waits for indexing and
Metallurgy parser preparation, highlights a Scala 3 source file, and checks both the IDE message pool and `idea.log`.
The test retains its stage timeline, project state, highlights, and logs under `target/ideprobe-artifacts`.

## Running

Export these paths when they are not already provided by `.agents/setup`:

```sh
export METALLURGY_REPO_ROOT="$(cd .. && pwd)"
export METALLURGY_INTELLIJ_HOME="$HOME/.metallurgyPluginIC/sdk/261.26222.65"
export JAVA_HOME="$METALLURGY_INTELLIJ_HOME/jbr"
export PATH="$HOME/.local/bin:$JAVA_HOME/bin:$PATH"
```

On a machine with enough memory, package the plugin and run the harness normally:

```sh
cd "$METALLURGY_REPO_ROOT"
gtimeout --kill-after=5s 5m sbt -batch -no-colors packageArtifact
cd ideprobe-tests
gtimeout --kill-after=15s 15m sbt -batch -no-colors test
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
  "$JAVA_HOME/bin/java" \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --enable-native-access=ALL-UNNAMED \
  -Xms64m -Xmx300m -cp "$classpath" \
  org.junit.runner.JUnitCore \
  com.hmemcpy.metallurgy.ideprobe.ProjectLifecycleTest
```

The `java.lang` opening is required by ide-probe 0.53 to reconstruct a remote stack trace on JBR 25. It does not alter
the IDE under test.
