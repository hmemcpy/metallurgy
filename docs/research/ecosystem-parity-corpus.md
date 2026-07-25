# Scala 3 ecosystem parity corpus

The ecosystem gate has two complementary layers. `corpus/ecosystem.json` pins immutable source revisions of Cats,
Cats Effect, ZIO, Shapeless 3, Tapir, and FS2 together with each build's own Scala 3 line and compiler-clean command.
`tools/run_ecosystem_corpus.py` downloads those revisions into an isolated temporary workspace and runs every sbt build
behind GNU `gtimeout`, recording revision, compiler version(s), source-file/line counts, duration, exit status, and logs in
`target/ecosystem-corpus-report.json`. It never mutates a developer checkout.

Run the complete compiler-clean baseline with:

```sh
CORPUS_JAVA_HOME="$JAVA_HOME" \
  python3 tools/run_ecosystem_corpus.py
```

Use one or more `--project NAME` arguments for a focused retry. A nonzero download, timeout, or compile exit makes the
whole command fail; there is no diagnostic allowlist.

The IDE-facing layer is `EcosystemSemanticCorpusTest`. It resolves pinned published artifacts for the same six library
families and asks the exact module presentation compiler for exact inferred types over representative higher-kinded,
effect, stream, macro/derivation, and endpoint-builder expressions. It then verifies that Scala PSI and hover observe
the committed compiler result. This protects the compiler-to-PSI seam on every ordinary test run without cloning six
large repositories.

The full-source baseline and the IDE semantic suite answer different questions: the baseline proves each selected
upstream revision is compiler-clean with its own build, while the IDE suite proves Metallurgy does not reinterpret the
library semantics at its cache/PSI boundary. Final graduation records the baseline report alongside whole-project IDE
smoke analysis, loader equivalence, latency/memory measurements, and the upstream IntelliJ Scala 3 test campaign.

## Recorded baseline

The 2026-07-23 graduation run used JBR 17.0.14 for the upstream builds. Every process ran at its immutable revision with
the manifest command, no diagnostic allowlist, and a per-project `gtimeout --kill-after=10s` boundary.

| Project | Scala versions | Scala files | Scala lines | Wall time | Result |
|---|---:|---:|---:|---:|---:|
| Cats | 3.3.8 | 835 | 97,316 | 62.141 s | passed |
| Cats Effect | 3.3.7 | 457 | 65,497 | 37.138 s | passed |
| ZIO | 3.3.8 | 917 | 188,050 | 110.791 s | passed |
| Shapeless 3 | 3.3.8 | 17 | 4,701 | 13.337 s | passed |
| Tapir | 3.3.8 and 3.7.4 | 1,269 | 108,139 | 243.295 s | passed |
| FS2 | 3.3.8 | 455 | 65,929 | 34.690 s | passed |
| **Total** |  | **3,950** | **529,632** | **501.392 s** | **passed** |

`EcosystemSemanticCorpusTest` separately passed six exact compiler-to-PSI type and hover facts in 32.055 seconds on
the IntelliJ 261 test platform. The durable machine-readable reports and complete build logs are emitted below
`target/`; they are CI artifacts rather than version-controlled source.
