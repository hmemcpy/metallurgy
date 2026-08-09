# P123-C evidence archive

Applied and named type argument evidence uses Scala 3.7.4. The protected `COMMANDS` file records the exact compiler,
REPL, parser, and PSI commands and flags.

- Archive identifier: `metallurgy-123c-evidence`
- Inner manifest: `SHA256SUMS`
- Manifest SHA-256: `8fbc50c8d9d64e9e6df98221c2c091bc36d1a28972edb7d3dd3f184558ab1c1b`
- Verified entries: 76
- Archive size: 15 GiB
- Recovery archive identifier: `metallurgy-target-recovery-20260808/idea-test-records`
- Recovery inner manifest: `SHA256SUMS`
- Recovery manifest SHA-256: `00a380086cdae76697de185806f5f5611e1f76ddf874d06f54acf273b449aee7`
- Recovery entries: 9,953
- Recovery archive size: 928 MiB
- Retention: preserve both archives through the maintenance and rollback window and until verified replacements are recorded

The primary archive retains verbatim commands, compiler and REPL output, parser bridge inventory, native host probes,
normalized nodes, exit records, and lane evidence. Generated compiler classes, TASTy, and Scala CLI caches are not part
of its durable manifest.
