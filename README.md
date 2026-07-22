# Metallurgy

An IntelliJ plugin that replaces IntelliJ's Scala type backend with the real Scala 3 compiler.

> **Pre-alpha, post-PoC.** Compiler-backed type resolution, inline type hints, completion, and best-effort operation are
> working. The next phase carries the Scala 3 backend across every IntelliJ type consumer.

## Target architecture

The plugin runs the real Scala 3 compiler — the same one [Metals](https://scalameta.org/metals/) uses — inside IntelliJ,
then exposes its types and symbols through Scala PSI so existing IDE features continue to work.

The idea — running the Scala compiler directly inside IntelliJ, without LSP — comes from
[Jędrzej Rochala's ScalaWAW #32 talk](https://www.youtube.com/watch?v=SNc7xeHrKnQ&t=3931s) (*The best Scala IDE
inside your favourite Scala IDE*).

## Requirements

- IntelliJ IDEA **2026.1+**
- **Scala 3.5.0+**
- The Scala plugin, with **compiler-based highlighting** enabled

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
