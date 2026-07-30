# Metallurgy

An IntelliJ plugin that builds Scala PSI from the exact Scala 3 compiler parser and replaces Scala 3 semantic roles
with the real compiler.

> **Pre-alpha.** Exact package and import PSI are active. The remaining grammar and semantic role cutovers are not yet
> complete.

## What it does

Metallurgy loads each module's exact Scala 3 parser and presentation compiler behind neutral classloader-safe bridges.
IntelliJ keeps its editor, project import, build, test, debugger, and refactoring infrastructure. Ready active files use
one synchronous exact-parser-to-PSI path; unsupported grammar fails closed instead of falling back to bundled parsing.

## Implementation status

| Status | Area | Current state |
|:---:|---|---|
| ✅ | Exact parser boundary | Exact artifacts load in isolation and return neutral immutable syntax evidence. |
| ✅ | Activation lifecycle | Opted-in Scala 3 modules move from neutral files to one ready parser epoch in a single VFS batch. |
| 🚧 | Deterministic Scala PSI | Package and the supported import grammar produce native physical PSI, stubs, serialization, indices, copies, edits, reparses, and reopen behavior. Qualified, wildcard-bound, and infix bounded-given types await the connected type-grammar cut; other unsupported grammar fails closed. |
| 🚧 | Compiler semantics | Session, snapshot, type, completion, navigation, diagnostics, and best-effort TASTy foundations exist; active role cutovers and no-fallback verification remain incomplete. |
| 🚧 | Compatibility PSI | Stable output roles and capability-probed native bindings exist. Compatibility implementations are added only where an installed host cannot satisfy a role. |
| 🚧 | Graduation | Copied IntelliJ tests, full IDE lifecycle lanes, published Scala/host matrices, representative projects, and resource budgets must execute before any compatibility claim is complete. |

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

- [`docs/scala3-compiler-backend.md`](docs/scala3-compiler-backend.md) — the architecture and reference
- [`docs/deterministic-scala3-psi-implementation-program.md`](docs/deterministic-scala3-psi-implementation-program.md) — the implementation program

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).
