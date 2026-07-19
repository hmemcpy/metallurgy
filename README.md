# Metallurgy

A third-party IntelliJ plugin that augments the bundled Scala plugin for Scala 3.5+ modules by delegating language semantics to the real Scala 3 presentation compiler (`pc`) from the [Metals](https://scalameta.org/metals/) project.

## Status

**Pre-alpha. Compiler-type resolution and completion are available behind module opt-in.**

The plugin loads, detects Scala 3.5+ modules, and offers to enable compiler-backed semantics for each module. See the [delivery plan](./docs/design.md#15-capability-delivery-order).

## What it will do

When a Scala 3.5+ module is opted in, Metallurgy intercepts the bundled Scala plugin's results wherever they're wrong or incomplete and replaces them with the authoritative answer from `pc`:

- Transparent-inline calls show the real expanded type instead of `Any`.
- Completion offers the items the bundled plugin misses.
- Diagnostics suppress bundled false-positives and add bundled-missed errors.
- Macro-derived members (e.g. `derives Codec`) are visible to navigation, completion, and find-usages.

Where the bundled plugin is correct, Metallurgy gets out of the way. It never disables the bundled plugin.

## Requirements

- IntelliJ IDEA 2026.1+
- Scala 3.5.0+ (the floor that ships [BETASTy](https://www.scala-lang.org/api/3.5.2/docs/docs/internals/best-effort-compilation.html))
- The bundled Scala plugin (installed by default with IDEA's Scala support)

## Installation

Until the first stable release, build from source:

```sh
sbt packageArtifactZip
```

The plugin zip is written to `target/metallurgy-<version>.zip`. Install via `Settings | Plugins | ⚙ | Install plugin from disk…`, then restart IDEA.

## Development

```sh
sbt compile         # build
sbt test            # run tests
sbt runIDE          # launch a dev IDEA with the plugin loaded
sbt fmt             # format source (scalafmt)
sbt check           # check formatting (CI uses this)
```

See [`CONTRIBUTING.md`](./CONTRIBUTING.md) for branch / PR conventions.

## Documentation

- [`docs/design.md`](./docs/design.md) — full architecture and phased roadmap
- [`CONTEXT.md`](./CONTEXT.md) — domain glossary
- [`docs/adr/`](./docs/adr/) — seven architectural decisions
- [`docs/research/`](./docs/research/) — deep-dive reports on the bundled Scala plugin's seams

## License

Apache License 2.0 — see [`LICENSE`](./LICENSE).

## Reporting issues

Use [GitHub Issues](https://github.com/hmemcpy/metallurgy/issues). Bug reports should include the IDEA version, the Scala version, the bundled Scala plugin version, and the smallest reproducer you can manage.
