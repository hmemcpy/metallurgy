# ideprobe-tests

ide-probe harness that drives a real IntelliJ with the Metallurgy plugin and asserts the delivered producer PSI
carries no ERROR highlights (and types present) — the repeatable, screen-free replacement for manual runIDE.

## Running

This is a standalone Scala 2.13 sbt build (like `../testkit`), because ide-probe is 2.13-only.

```
cd ideprobe-tests
sbt test
```

Slow: launches (and on first run downloads) an IDE. Requires `../target/plugin/metallurgy` packaged
(`sbt runIDE`/`prepareSandbox` in the root) and `../../metallurgy-dogfood` present. Adjust `src/test/resources/ideprobe.conf`.
