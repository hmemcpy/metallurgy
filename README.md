# Metallurgy

Metallurgy is an IntelliJ plugin for Scala 3 projects. It reads Scala 3 files with the same compiler version used by
the project. IntelliJ remains the editor, project importer, build and test runner, and debugger.

> **Pre-alpha:** Metallurgy is under active development and is not ready for normal daily use. Only a limited part of
> Scala 3 syntax works today.

## What works today

Metallurgy currently supports:

- package declarations, including nested and chained packages, braces, indentation, and `end` markers;
- imports and exports with deep paths, named selectors, aliases, wildcards, and `given` selectors;
- qualified, wildcard-bounded, and infix `given` selector types in the forms covered by the current implementation.

For these forms, Metallurgy builds real PSI, IntelliJ's internal model of a source file. IntelliJ can save them in its
project lookup data, copy and edit them, and rebuild the same model after a file or project is reopened.

Detailed progress is tracked in [Epic #85](https://github.com/hmemcpy/metallurgy/issues/85).

## What is not ready

Most Scala 3 source files need syntax that Metallurgy does not support yet. This includes:

- most declarations and templates, such as classes, objects, traits, methods, values, and type definitions;
- expressions and control flow;
- patterns and matches;
- most type syntax outside the supported import and export selector forms;
- complete compiler powered types, reference resolution, completion, navigation, and error reporting;
- full behavior across modules when an upstream module has a broken build;
- broad compatibility across Scala versions, IntelliJ versions, and real projects;
- production performance and release packaging.

When a file uses unsupported syntax, it stays in a safe basic state. The status bar explains what is missing.
Metallurgy does not build a misleading partial model or mix in IntelliJ's older Scala parser for the rest of the file.

The idea — running the Scala compiler directly inside IntelliJ, without LSP — comes from
[Jędrzej Rochala's ScalaWAW #32 talk](https://www.youtube.com/watch?v=SlPDmwhxeok&t=3931s) (*The best Scala IDE
inside your favourite Scala IDE*).

## Current development baseline

The tested development baseline is:

- IntelliJ IDEA Community **261.26222.65**;
- Scala plugin **2026.1.20**;
- Scala **3.7.4**.

Support is not yet claimed for other combinations.

## Install

There is no normal user release yet. To build a development package from source:

```sh
sbt packageArtifactZip
```

Then `Settings | Plugins | Install plugin from disk…` → the zip in `target/`.

## Develop

```sh
sbt compile
sbt test
sbt runIDE
sbt fmt
sbt check
```

See [`CONTRIBUTING.md`](./CONTRIBUTING.md) and [`AGENTS.md`](./AGENTS.md).

## Docs

- [Architecture and reference](docs/scala3-compiler-backend.md)
- [Implementation program](docs/deterministic-scala3-psi-implementation-program.md)

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
