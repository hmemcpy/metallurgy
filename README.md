# Metallurgy

A third-party IntelliJ plugin that fills Scala 3 semantic gaps using the presentation compiler from [Metals](https://scalameta.org/metals/).

## Status

**Pre-alpha. Compiler-backed types, completion, and false-error suppression are available behind module opt-in.**

## Examples

| Capability | IntelliJ out of the box | With Metallurgy |
| --- | --- | --- |
| Generated structural APIs (`api.paths.`) | Jing's `` `/pet` `` path is missing from completion and valid access is marked as an error. | Completion offers `` `/pet` `` and reports its exact `HttpEndpoint` type. |
| Recursive derivation (`mirror.MirroredElemTypes`) | The derived recursive tuple is not understood and its valid assignment is red. | The full tuple type is available, including singleton enum cases. |
| Inline matches (`toInt(Succ(Succ(Zero)))`) | The reduced singleton result is unavailable and `val intTwo: 2` is red. | The result is reported as `(2 : Int)`. |
| Match types (`Elem[List[Int]]`) | The applied match type is not reduced through the compiler-type path. | The applied type is reported as `Int`. |
| Compile-time operations (`2 + 2`) | The reduced singleton type is unavailable. | The result is reported as `(4 : Int)`. |
| Error filtering | Macro-related false errors remain visible. | False errors are removed only from a matching fresh compiler snapshot; real errors remain visible. |

See the [delivery plan](./docs/design.md#15-capability-delivery-order) for planned capabilities.

## Roadmap

| Capability | Status |
| --- | --- |
| Compiler-backed type resolution | ✅ Complete |
| Completion augmentation | ✅ Complete |
| False-positive error suppression | ✅ Complete |
| Missing compiler diagnostics | Planned |
| Hover, inlay hints, and parameter info | Planned |
| Synthetic members and macro expansion | Planned |
| Navigation and find usages | Planned |

## Requirements

- IntelliJ IDEA 2026.1+
- Scala 3.5.0+
- The bundled Scala plugin

## Installation

```sh
sbt packageArtifactZip
```

Install the zip from `target/` via `Settings | Plugins | Install plugin from disk…`.

## Development

```sh
sbt compile         # build
sbt test            # run tests
sbt runIDE          # launch a dev IDEA with the plugin loaded
sbt fmt             # format source (scalafmt)
sbt check           # check formatting (CI uses this)
```

See [`CONTRIBUTING.md`](./CONTRIBUTING.md) for contribution guidelines.

## Documentation

- [`docs/design.md`](./docs/design.md) — architecture and delivery plan
- [`CONTEXT.md`](./CONTEXT.md) — domain glossary
- [`docs/adr/`](./docs/adr/) — architectural decisions

## License

Apache License 2.0 — see [`LICENSE`](./LICENSE).

## Reporting issues

Use [GitHub Issues](https://github.com/hmemcpy/metallurgy/issues).
