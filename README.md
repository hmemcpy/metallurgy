# Metallurgy

An IntelliJ plugin that uses the real Scala 3 presentation compiler ([pc](https://scalameta.org/metals/), from Metals)
to enrich the type information available to IntelliJ — a more faithful representation of the actual type, completion
members, inline hints, and more, for Scala 3.5+.

> **Pre-alpha.** Type resolution, inline type hints, and completion are available behind per-module opt-in and require
> compiler-based highlighting to be on. The plugin is a hard no-op without it.

## What it does

The Before/After columns below are the **measured output** of the [test fixtures](src/test/testdata/feature/compilertype/)
— the compiler-type slot and red-state, with the plugin off vs on. (Cells are placeholders for screenshots.)

### Type resolution (hover / inspect)

| Before | After |
|---|---|
| `val result: TwoPlusTwo = 4`<br>type: *(empty)* | `val result: TwoPlusTwo = 4`<br>type: `(4 : Int)` |
| `val p: 8080 = Config.port`<br>type: *(empty)* · line red | `val p: 8080 = Config.port`<br>type: `(8080 : Int)` · not red |
| `val e1: Elem[List[Int]] = 42`<br>type: *(empty)* | `val e1: Elem[List[Int]] = 42`<br>type: `Int` |
| `val intTwo: 2 = natTwo`<br>type: *(empty)* · line red | `val intTwo: 2 = natTwo`<br>type: `(2 : Int)` · not red |
| `val head = h.head`<br>type: *(empty)* · line red | `val head = h.head`<br>type: `Int` · not red |
| `val selectedName = c.name`<br>type: *(empty)* | `val selectedName = c.name`<br>type: `Config{val name: String; val age: Int}` |

…also match types, named tuples, polymorphic and context functions, the Aux pattern, union/opaque types, and quoted
types — see the [type-resolution tests](src/test/scala/com/hmemcpy/metallurgy/pc/PcTypeResolutionTest.scala).

### Inline type hints

The plugin shows the compiler's inferred type as an inline hint after each `val`/`var`.

| Before | After |
|---|---|
| `val result = id(42)` | `val result: Int = id(42)` |

### Completion

The plugin offers completion for extension methods and structural-refinement members, with their real type.

| Before | After |
|---|---|
| `api.paths.` — no `` `/pet` `` offered, line red | `api.paths.`` `/pet` `` offered, type `HttpEndpoint[…]`, not red |

### Cross-module robustness

The plugin applies `-Ybest-effort -Ywith-best-effort-tasty` to the compile server, enabling cross-module type
resolution against upstream `.betasty` artifacts.

## How it works

The plugin runs the real Scala 3 presentation compiler — the same `pc` (dotc's `InteractiveDriver`) that powers
[Metals](https://scalameta.org/metals/) — in an isolated classloader and queries it for types and completions.
Those answers enrich IntelliJ's own type information (the compiler-type slot, inline hints, and the completion
list) for Scala 3.5+ modules, on top of compiler-based highlighting.

The idea — running the Scala presentation compiler directly inside IntelliJ, without LSP — comes from
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

- [`docs/design.md`](docs/design.md) — architecture
- [`CONTEXT.md`](CONTEXT.md) — glossary
- [`docs/adr/`](docs/adr/) — decisions (0008 CBH gate, 0010 native-clean, 0011 scope)
- [`docs/research/15`](docs/research/15-scala3-type-resolution-gaps.md) — the gap analysis

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
