# Metallurgy

A third-party IntelliJ plugin that augments the bundled Scala plugin for Scala 3.5+ modules by delegating language semantics to the real Scala 3 presentation compiler (`pc`) from the Metals project.

- **Design doc:** [`docs/design.md`](docs/design.md)
- **Glossary:** [`CONTEXT.md`](CONTEXT.md)
- **Architectural decisions:** [`docs/adr/`](docs/adr/)
- **Status:** pre-alpha. Compiler-type resolution and completion are available behind module opt-in.

## Discipline

- **"pc is never wrong."** A surprising result from the presentation compiler / dotc almost always means your snippet, needle, or assumption is wrong — not a `pc` limitation. Fix the test, don't blame the compiler.
- **The [scala/scala3](https://github.com/scala/scala3) repo is the source of truth for Scala language and compiler behaviour.** When something doesn't work where it seemingly should (a snippet that won't compile, a type that resolves unexpectedly, a macro that doesn't expand), check the upstream compiler implementation, its tests (`tests/run`, `tests/run-macros`, `tests/pos`), and the issue tracker — against the exact Scala version under test — *before* stating a definitive answer or concluding it's a tooling gap. Example: Scala 3 `MacroAnnotation` cannot add members visible to user code ("Can not see new definition in user written code"), so no tool can surface such members — confirmed against the upstream tests, saving the effort of implementing an impossible feature.

## Agent skills

### Issue tracker

GitHub issues in this repo (`gh` CLI). See `docs/agents/issue-tracker.md`.

### Triage labels

Defaults: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` at repo root + `docs/adr/`. See `docs/agents/domain.md`.
