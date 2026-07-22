# Metallurgy

An IntelliJ plugin whose central architectural bet is to replace IntelliJ's Scala type resolution in opted-in Scala
3.5+ modules with the real Scala 3 compiler, driven through the presentation compiler and best-effort compilation.

> **Pre-alpha, post-PoC.** The proof of concept established compiler-type resolution, inline type hints, completion,
> and best-effort operation. The next phase makes pc authoritative across every type consumer. All behavior remains
> behind per-module opt-in and requires compiler-based highlighting; the plugin is a hard no-op outside that gate.

## Target architecture

The plugin runs the real Scala 3 compiler — the same one [Metals](https://scalameta.org/metals/) uses — once per
document version, walks the resulting typed tree, and feeds version-guarded types and symbols into IntelliJ's Scala
PSI compatibility layer. The completed architecture makes that snapshot authoritative for all type reads in active
Scala 3.5+ modules, not only the constructs where the bundled plugin currently loses precision.

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

Then `Settings | Plugins | Install plugin from disk…` → the zip in `target/`. Opt a module in via its settings.

## Develop

```sh
sbt compile         # build
sbt test            # run tests
sbt runIDE          # dev IDEA with the plugin loaded
sbt fmt | sbt check # format / verify (CI gate)
```

See [`CONTRIBUTING.md`](./CONTRIBUTING.md) and [`AGENTS.md`](./AGENTS.md).

## Docs

- [`docs/research/17-pc-authoritative-type-resolution.md`](docs/research/17-pc-authoritative-type-resolution.md) — the
  sole canonical architecture and design document
- [`docs/archive/`](docs/archive/) — superseded design, glossary, ADR, status, and research documents retained only for
  historical provenance

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
