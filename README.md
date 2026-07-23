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

## Implementation status

| Status | Area | Current state |
|:---:|---|---|
| ✅ | Compiler integration | Resolves each module's presentation compiler through published Scalameta interfaces and discovers optional capabilities without version allowlists. |
| ✅ | Type resolution | Whole-file compiler snapshots provide expression, declaration, binding, function, parameter, pattern, expected, widened, and exact types. |
| ✅ | Freshness and isolation | Document-version guards, generation retirement, per-element invalidation, and inactive-module no-op behavior are covered by tests. |
| ✅ | Resolve and navigation | Compiler symbols map to stable source PSI or generation-scoped light PSI for compiler-only declarations. |
| ✅ | IDE presentation | Completion, hover, Quick Documentation, inline hints, parameter information, navigation, inspections, search, and representative refactorings consume compiler results. |
| ✅ | Best-effort compilation | BETASTY capabilities, broken-upstream-module consumption, artifact freshness, and classpath/session replacement are implemented. |
| ✅ | Project loading | IntelliJ's native sbt and BSP loaders remain intact and feed the same loader-neutral compiler-backend pipeline. |
| ✅ | Worksheets | Physical Scala worksheets use the normal versioned compiler backend while the bundled plugin retains execution and result transport. |
| ✅ | Applications and tests | Scala 3 `main`, `@main`, ScalaTest, MUnit, Specs2, and uTest discovery/configuration continue through bundled run infrastructure. |
| ✅ | Ecosystem corpus | Pinned Cats, Cats Effect, ZIO, Shapeless 3, Tapir, and FS2 revisions compile cleanly; representative compiler-to-PSI semantic facts pass. |
| ⚠️ | Debugger fragments | Synthetic debugger expressions deliberately use the bundled evaluator because they do not have a safe source-document generation key. |
| ⚠️ | Interactive REPL console | Physical worksheet semantics are compiler-backed; synthetic console input remains an explicit bundled fallback. |
| ⚠️ | Platform UAST discovery | Direct Scala UAST conversion inherits compiler results, but the bundled UAST language plugin is unavailable while the compiler-highlighting failsafe is active. |
| ⏳ | Final graduation | The complete Metallurgy suite, all Scala 3-focused `intellij-scala` tests, aggregate latency/memory evidence, and the final go/no-go report remain in [#60](https://github.com/hmemcpy/metallurgy/issues/60). |

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
