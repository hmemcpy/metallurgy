# Metallurgy

Metallurgy is an experimental IntelliJ plugin for Scala 3. Its goal is for IntelliJ to understand a project through
the same dotc compiler version that builds it.

Metallurgy generates IntelliJ PSI based on dotc trees. PSI is IntelliJ's internal model of source code. IntelliJ uses
that model to power features such as navigation, completion, refactoring, and error reporting.

## Why

Scala 3 changes quickly. When an IDE reads and understands Scala code separately from the compiler, the two can
disagree about valid syntax, types, or the meaning of code.

Metallurgy aims to make the project's compiler the single source of truth. It keeps IntelliJ's editor, project model,
debugger, refactoring UI, and plugin ecosystem. It runs inside IntelliJ rather than replacing IntelliJ with a
Language Server Protocol (LSP) client.

The idea of running the Scala compiler directly inside IntelliJ comes from
[Jędrzej Rochala's ScalaWAW #32 talk](https://www.youtube.com/watch?v=SlPDmwhxeok&t=3931s), *The best Scala IDE
inside your favourite Scala IDE*.

## Project status

> **Pre-alpha and work in progress.** Language coverage and editor feature coverage are incomplete. Metallurgy is not
> ready for normal daily use, and no normal user release exists.

## Compatibility

The exact current development baseline is reviewed in
[`project/metallurgy-baseline.properties`](project/metallurgy-baseline.properties). Other Scala 3 combinations are
not yet verified. Scala 2 is out of scope.

The manifest does not claim broad compatibility. It records the environment used for current development and the
limits of what has been verified.

## Learn more

- [Contributing](CONTRIBUTING.md)
- [Architecture and reference](docs/scala3-compiler-backend.md)
- [Implementation program](docs/deterministic-scala3-psi-implementation-program.md)
- [Apache License 2.0](LICENSE)
