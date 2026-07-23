# Metallurgy

An IntelliJ plugin that replaces IntelliJ's Scala type backend with the real Scala 3 compiler.

> **Pre-alpha.** The replacement backend is implemented and undergoing its final compatibility and regression
> graduation.

## What it does

Metallurgy runs the module's real Scala 3 presentation compiler through
[Scalameta's published interfaces](https://scalameta.org/metals/) and exposes its types and symbols through the existing
Scala PSI model. IntelliJ keeps its editor, project import, build, test, debugger, and refactoring infrastructure while
Scala 3 supplies the semantic backend.

Compiler-backed types, symbol resolution, completion, hover, inline hints, navigation, and best-effort cross-module
operation are working. The implementation is tested against substantial Scala 3 codebases including Cats, Cats Effect,
ZIO, Shapeless 3, Tapir, and FS2.

The idea — running the Scala compiler directly inside IntelliJ, without LSP — comes from
[Jędrzej Rochala's ScalaWAW #32 talk](https://www.youtube.com/watch?v=SlPDmwhxeok&t=3931s) (*The best Scala IDE
inside your favourite Scala IDE*).

## Requirements

- IntelliJ IDEA **2026.1+** with the Scala plugin
- A **Scala 3** project

## Install

```sh
sbt packageArtifactZip
```

Then `Settings | Plugins | Install plugin from disk…` → the zip in `target/`.

## Develop

```sh
sbt compile         # build
sbt test            # run tests
sbt runIDE          # dev IDEA with the plugin loaded
sbt fmt | sbt check # format / verify (CI gate)
```

See [`CONTRIBUTING.md`](./CONTRIBUTING.md) and [`AGENTS.md`](./AGENTS.md).

## Docs

- [`docs/scala3-compiler-backend.md`](docs/scala3-compiler-backend.md) — the definitive architecture and reference
- [`docs/archive/`](docs/archive/) — superseded design, glossary, ADR, status, and research documents retained only for
  historical provenance

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
