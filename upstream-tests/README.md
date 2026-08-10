# Copied IntelliJ Scala tests

The manifest pins exact Git blobs from JetBrains IntelliJ Scala. Snapshot files are never compiled as an upstream
project. Declarative rewrites change only generated host wiring; the protected class body remains byte-identical.

The first generated suite covers Scala 3 named type arguments and the upstream `doTest` type-inference helper.

Use the embedded JBR from the IntelliJ SDK named by the
[`baseline manifest`](../project/metallurgy-baseline.properties) for every command:

```sh
JBR="$METALLURGY_INTELLIJ_HOME/jbr/Contents/Home"
/opt/homebrew/bin/gtimeout --kill-after=5s 120s \
  env JAVA_HOME="$JBR" PATH="$JBR/bin:$PATH" \
  sbt -batch -no-colors verifyCopiedIntellijTests
/opt/homebrew/bin/gtimeout --kill-after=5s 120s \
  env JAVA_HOME="$JBR" PATH="$JBR/bin:$PATH" \
  sbt -batch -no-colors generateCopiedIntellijTests
/opt/homebrew/bin/gtimeout --kill-after=5s 120s \
  env JAVA_HOME="$JBR" PATH="$JBR/bin:$PATH" \
  sbt -batch -no-colors \
    -Dintellij.scala.repo="$HOME/git/intellij-scala" \
    verifyCopiedIntellijTestsAgainstOrigin
```

Generation writes only below `target/copied-intellij-tests/generated`. Accepting generated output into the tracked
tree is a separate reviewed change.
