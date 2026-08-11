# Contributing to Metallurgy

Thanks for your interest in contributing.

## Setup

1. Clone the repo.
2. Set `METALLURGY_INTELLIJ_HOME` to the pinned IntelliJ SDK. Use its embedded JBR for builds and tests. The default
   SDK location derives from the build in [`project/metallurgy-baseline.properties`](project/metallurgy-baseline.properties).
3. Install sbt. The exact baseline is in the manifest and the launcher pin remains in `project/build.properties`.
4. Open the project in IntelliJ IDEA with the bundled Scala plugin; let it import as an sbt project.

## Build / test

```sh
JBR="$METALLURGY_INTELLIJ_HOME/jbr/Contents/Home"
/opt/homebrew/bin/gtimeout --kill-after=5s 120s env JAVA_HOME="$JBR" PATH="$JBR/bin:$PATH" sbt compile
/opt/homebrew/bin/gtimeout --kill-after=5s 120s env JAVA_HOME="$JBR" PATH="$JBR/bin:$PATH" sbt test
/opt/homebrew/bin/gtimeout --kill-after=5s 120s env JAVA_HOME="$JBR" PATH="$JBR/bin:$PATH" sbt packageArtifactZip
/opt/homebrew/bin/gtimeout --kill-after=5s 120s env JAVA_HOME="$JBR" PATH="$JBR/bin:$PATH" sbt runIDE
```

Increase the 120-second limit only when evidence justifies it.

## Code style

- Plugin and testkit versions are exact in
  [`project/metallurgy-baseline.properties`](project/metallurgy-baseline.properties). The testkit backport
  (`testkit/`, ADR 0005) matches the bundled Scala plugin it mirrors.
- `sbt fmt` applies `scalafmt`; `sbt check` verifies formatting (CI gates on this).
- Prefer idiomatic Scala 3. **Java-isms are fine where the IntelliJ / bundled-Scala-plugin APIs force them** — don't fight the platform for purity.
- **The bundled [intellij-scala](https://github.com/JetBrains/intellij-scala) plugin is the definitive reference** for IntelliJ / Scala-plugin APIs. Before writing an implementation, helper, or test fixture, check it (the GitHub repo, or your local checkout) for an existing one to mirror.
- **The [scala/scala3](https://github.com/scala/scala3) repo is the source of truth for Scala language and compiler behaviour.** When something doesn't work where it seemingly should — a snippet that won't compile, a type that resolves unexpectedly, a macro that doesn't expand — check the upstream compiler implementation, its tests (`tests/run`, `tests/run-macros`, `tests/pos`), and the issue tracker *before* concluding it's a tooling limitation. This is the companion to **"pc is never wrong"**: a surprising `pc`/dotc result almost always means the snippet or assumption is wrong, and the canonical usage lives upstream (verified against the exact Scala version under test).
- When running tests, always bound the timeout — a hung compile-server test should not stall the suite.
- Follow the [hash provenance and expected-value update policy](docs/agents/hash-provenance.md) before changing a
  fingerprint, copied-source digest, schema value, stable ID, pin, or evidence seal.

## Branches

This repo uses one long-lived branch per IntelliJ platform version; `idea261.x` is current. Ongoing implementation is
committed directly from one clean sole-writer local checkout that exactly matches the latest remote `idea261.x`. Do not
create or push feature or task branches, and do not use pull requests for this sole-owner delivery path.

Before work, fetch and reconcile the remote target, confirm the checkout is clean and exactly equal to it, and confirm
that no other writer or task process conflicts. Preserve unrelated divergent and recovery worktrees. Local recovery
checkpoints may protect uncommitted work, but must not create remote task refs.

Run the packet's required tests and independent Medium review before push. Verify that both author and committer are
exactly `Igal Tabachnik <hmemcpy@gmail.com>` and that the commit has no automated attribution, trailers, signatures, or
extra headers. Reconfirm the remote target has not advanced, then push only an explicit normal non-force fast-forward
from `HEAD` to `refs/heads/idea261.x`. Confirm the result with a fresh `ls-remote` and fetch. Repeat broader validation
only if the push is ambiguous or there is drift, corruption, or material risk.

Stale remote branches are removed under exact leases only after their content and history are proven fully represented.
Unique or ambiguous branches remain until the user decides. Tags are not part of branch cleanup and remain untouched.

## Commit messages

Use the packet's required subject. Keep messages plain and do not add automated attribution or metadata.

## Tests

Every change that adds or modifies behaviour should ship with tests. Where a behaviour depends on the bundled Scala plugin's testkit (currently being backported under `src/test/scala/org/jetbrains/plugins/scala/*`), tests can land alongside the testkit work; otherwise plain JUnit tests are fine.

## Reporting bugs / features

Use [GitHub Issues](https://github.com/hmemcpy/metallurgy/issues). For bugs, include IDEA version, Scala version, bundled Scala plugin version, and a minimal reproducer. For features, point at the design section that motivates the request.

## License

By contributing you agree that your contributions are licensed under the Apache License 2.0.
