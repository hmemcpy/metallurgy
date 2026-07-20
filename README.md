# Metallurgy

A third-party IntelliJ plugin that augments the bundled Scala plugin for Scala 3.5+ modules by delegating language semantics to the real Scala 3 presentation compiler (`pc`) from the [Metals](https://scalameta.org/metals/) project.

## Status

**Pre-alpha. Compiler-type resolution and completion are available behind module opt-in.**

The plugin loads, detects Scala 3.5+ modules, and offers to enable compiler-backed semantics for each module. See the [delivery plan](./docs/design.md#15-capability-delivery-order).

## Current example

Consider a transparent-inline API that returns a structural type:

```scala
val c = typesafeConfig("name" -> "John", "age" -> 42)
c.na
```

| Bundled Scala plugin | Bundled Scala plugin with Metallurgy |
| --- | --- |
| The result is widened to `Any` or `Config`, losing the generated members. Completion cannot offer `name` or `age`; only the generic `selectDynamic` API remains visible. | The compiler-reported type is `Config { val name: String; val age: Int }`. Completion offers `name: String` and `age: Int` using IntelliJ's native completion presentation. |

Paired acceptance fixtures verify the type difference with Metallurgy enabled and disabled, while completion tests verify that compiler-only structural members reach IntelliJ's native lookup.

## Current support and direction

When a Scala 3.5+ module is opted in, Metallurgy intercepts the bundled Scala plugin's results wherever they're wrong or incomplete and replaces them with the authoritative answer from `pc`:

- Transparent-inline calls show the real expanded type instead of `Any`.
- Completion offers the items the bundled plugin misses.

Compiler-backed diagnostics, enriched hover and inlay hints, synthetic members, and navigation are planned next. The [delivery plan](./docs/design.md#15-capability-delivery-order) tracks their order and scope.

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
