# Contributing to Metallurgy

Thanks for your interest in contributing.

## Setup

1. Clone the repo.
2. Install JDK 21 (Temurin or any OpenJDK 21+ build works for the build; IntelliJ 2026.1 ships JBR 25 at runtime).
3. Install sbt 1.9.9+ (the project is pinned to 1.9.9 via `project/build.properties`).
4. Open the project in IntelliJ IDEA with the bundled Scala plugin; let it import as an sbt project.

## Build / test

```sh
sbt compile        # build the plugin
sbt test           # run unit tests
sbt packageArtifactZip   # produce the distributable zip
sbt runIDE         # launch a dev IDEA with the plugin loaded
```

## Code style

- Scala 2.13.16 for plugin code (binary compatibility with the bundled Scala plugin).
- `sbt fmt` applies `scalafmt`; `sbt check` verifies formatting (CI gates on this).
- Idiomatic Scala; when in doubt, follow the patterns in the bundled [intellij-scala](https://github.com/JetBrains/intellij-scala) plugin (canonical reference).

## Branches

This repo uses one long-lived branch per IntelliJ platform version: `idea261.x` is current. PRs target the active branch.

## Commit messages

Reference the issue number at the start of the summary:

```
#42: Short description of the change

Body explaining the why and how.
```

Sign off your commits:

```
Signed-off-by: Your Name <your.email@example.com>
```

This is a [DCO-style](https://developercertificate.org/) signoff — by signing off you certify that you wrote the change or otherwise have the right to contribute it.

## Tests

Every change that adds or modifies behaviour should ship with tests. Where a behaviour depends on the bundled Scala plugin's testkit (currently being backported under `src/test/scala/org/jetbrains/plugins/scala/*`), tests can land alongside the testkit work; otherwise plain JUnit tests are fine.

## Reporting bugs / features

Use [GitHub Issues](https://github.com/hmemcpy/metallurgy/issues). For bugs, include IDEA version, Scala version, bundled Scala plugin version, and a minimal reproducer. For features, point at the design section that motivates the request.

## License

By contributing you agree that your contributions are licensed under the Apache License 2.0.
