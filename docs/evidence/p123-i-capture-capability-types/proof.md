# Capture and capability type proof

## Exact compiler contract

The source and REPL input in this directory run unchanged with Scala 3.7.4 and
`-language:experimental.captureChecking`. Compile, REPL `:type`, and `-Xprint:typer` establish compiler acceptance and
meaning. Parser inventory tests separately establish source syntax products, fields, positions, tokens, provenance,
attachments, lossless reconstruction, and the deterministic evidence fingerprint
`3ce4c82c5f35c017e31b1b89d18b684ebb09f09551918744083ab346d4d41115`. The snapshot records the exact
`org.scala-lang:scala3-compiler_3:3.7.4` coordinate and the exact option vector. Omitting the option produces compiler
errors and no capture-function parser product. Rendered typer text does not select syntax roles.

The admitted source forms are `T^`, `T^{...}`, references `x`, `h.cap`, `xs*`, `x.rd`, and `x.only[Kind]`, pure and
context arrows `A -> B` and `A ?-> B`, nullary arrows, and by-name forms `-> B` and `->{x} B`. Capture sets mount in
function results and ordinary or class parameter types. A capture filter classifier extends both `caps.Capability`
and `caps.Classifier`; extending only `caps.Classifier` is rejected. Exact compiler probes reject `.except` and
recursive reach, read-only, or filter combinations, so they are not admitted.

IC-261 supplies native `ScCaptureTypeElement`, `ScCaptureSet`, `ScCaptureRef`, and `ScCaptureFilter` contracts. The
installed Scala plugin is `2026.1.20`; its `scalaCommunity.jar` SHA-256 is
`10efcbaf065fecf85a9a9d33bb774b741479883abaa59829ad6f49c81d50c9f6`. Native `ScParameterTypeImpl` recognizes
`=>` but not the compiler-valid pure by-name `->`. The compatibility `MetallurgyParameterType` changes only this
arrow accessor contract; ordinary by-name parameters remain native.

## Boundary

The admitted family is type syntax only. Capture related terms, definition modifiers, user written annotations,
general expressions, patterns, quotes, semantics, and unrelated experimental grammar remain unavailable.

The capture PSI and pure parameter compatibility roles are AST-only and declare no stub or index persistence
obligations. The catalog fingerprint is
`e3649a7979469c4fc106d226259f006bd32726ea0fb4f1a4b7c89284a72baa41`; the file stub schema remains the native Scala
schema plus 14. Existing owner stubs, serialization, reopen, and indices remain unchanged.

## Validation evidence

Focused parser inventory, catalog, physical, and package tests passed 89 tests in 81 seconds after review fixes.
Earlier focused groups passed 80 tests in 40 seconds, 13 physical and package tests in 45 seconds, 19 affected tuple,
function, annotation, refinement, and owner tests in 46 seconds, and 21 parser lifecycle and project reopen tests in
31 seconds. Formatting, compilation, test compilation, packaging, and artifact packaging passed together.

The final serial `baseline-syntax-psi` lane passed 14 suites and 90 tests in 366 seconds; its summary is
`target/test-evidence/baseline-syntax-psi/p123i-review-fixes-baseline-20260810/summary.json`, SHA-256
`26fc542c46a70331145d270ef146cd75b95316feb839f4f085246ada626fb298`. The final serial `ci` lane passed 26 suites
and 131 tests in 460 seconds; its summary is
`target/test-evidence/ci/p123i-review-fixes-ci-20260810/summary.json`, SHA-256
`965021779fe3c41c5a99b16e29c4326315c1bf338789d67670ad4eade2e0ff80`. Final invocation accounting passed 4 suites
and 14 tests in 90 seconds; its summary is
`target/test-evidence/baseline-invocation-accounting/p123i-review-fixes-accounting-20260810/summary.json`, SHA-256
`4fb7520367c530bf0a7115550977d85d01b6b57ab6dd5167ade1a91c449a7722`.

Copied source verification passed locally with 13 executable tests and against the configured upstream checkout.
The IC-261 lifecycle passed 11 tests in 71 seconds, including parser readiness, unavailable-state reporting,
deduplication, edit and compile recovery, highlighting, a clean message pool, and a clean final `idea.log`. Its stage
log is `ideprobe-tests/target/ideprobe-artifacts/latest/stages.log`, SHA-256
`ecca7feb960b023fd5814ad1150ae2c708afa458b17af7756fbe36a68e9d248d`; the final `idea.log` SHA-256 is
`b8fecb478becdd25b619652e22bfa0fda69c449dd8b970586d9a48a57d685d92`.

External raw compiler logs are retained outside the tracked proof. Their SHA-256 values are: clean compile
`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`, typer
`c8fa122222d2fe040237ecdf26b31f2f958762d52f4e02034fe842c53eff03c3`, REPL
`4a4df5f0d8f2e7a4a593400800adad1e3e998da38a15a14d1a857e5e7ecc5b59`, and missing-option rejection
`6c8db41fd57f999b4f1c059a56afb01a4bf5603d4f0c2159e1f1f044e85bcb58`.
