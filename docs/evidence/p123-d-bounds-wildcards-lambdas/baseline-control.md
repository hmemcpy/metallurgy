# Exact P123-C baseline control

The control worktree was clean at commit `31e8069b85e41c8ffdca1ddce03fbf8447c0e6d6`, tree
`4f9c329e657f0bf5883f6c56e0520f67e9cccbc2`.

The editor lane had the same result on P123-C and P123-D: 22 tests, 18 failures, and no errors. Both runs had
`BundledCompilerBackendConsumerTest` fail 17 of 18 tests and `DialectFindUsagesTest` fail its only test. Completion and
daemon tests passed.

The semantics lane also had the same result on P123-C and P123-D: 73 tests, 62 failures, and no errors. Both runs had
`BundledCompilerBackendShimTest` fail 30 of 36 tests with one unexpected invocation,
`CompilerBackendSnapshotPublisherTest` fail 31 of 32 tests, and `BetastyCompileServerTest` fail its only test.
`BetastyCrossModuleTest` passed all four tests.

The preserved run directories are:

- P123-D editor: `target/test-evidence/baseline-editor-operations/p123d-editor-20260808T1825Z`
- P123-D semantics: `target/test-evidence/baseline-semantics/p123d-semantics-20260808T1833Z`
- P123-C editor: `metallurgy-123c-applied-named-type-arguments/target/test-evidence/baseline-editor-operations/p123c-control-editor-20260808T1900Z`
- P123-C semantics: `metallurgy-123c-applied-named-type-arguments/target/test-evidence/baseline-semantics/p123c-control-semantics-20260808T1902Z`

The exact match proves that these failures are inherited applicability boundaries rather than P123-D regressions.
Admitting expression roles or P123-E type families to change them would violate this packet's scope.
