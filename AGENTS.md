# Metallurgy

A third-party IntelliJ plugin that augments the bundled Scala plugin for Scala 3.5+ modules by delegating language semantics to the real Scala 3 presentation compiler (`pc`) from the Metals project.

- **Design doc:** [`docs/design.md`](docs/design.md)
- **Glossary:** [`CONTEXT.md`](CONTEXT.md)
- **Architectural decisions:** [`docs/adr/`](docs/adr/)
- **Status:** pre-alpha. Phase 0 scaffold only; no features enabled yet.

## Agent skills

### Issue tracker

GitHub issues in this repo (`gh` CLI). See `docs/agents/issue-tracker.md`.

### Triage labels

Defaults: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` at repo root + `docs/adr/`. See `docs/agents/domain.md`.
