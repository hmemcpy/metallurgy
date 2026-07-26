# Current parity and failure baseline

## Result

The current Metallurgy-owned compatibility area contains 47 discovered suites and 406 tests. Fresh reports exist for
every discovered suite:

| Outcome | Suites | Tests |
|---|---:|---:|
| Passing | 39 | 391 |
| Failing | 8 | 15 |
| Missing report | 0 | 0 |
| Skipped | — | 0 |

The machine-readable observation is
[`current-parity-and-failure-baseline.json`](current-parity-and-failure-baseline.json). It pins the source revision,
environment, discovery hash, run outcomes, individual failures, log events, and known coverage gaps.

This is a measurement of the hand-written compatibility area that exists today. It is not a parity percentage. A green
test establishes only that its current local adapter assertion completed in the measured environment.

## Execution

The suite inventory is the sorted set of classes declared by `*CompatTest.scala`,
`Scala3CompatHarnessSanityTest.scala`, and `DotcOracleConflictProofTest.scala` below
`src/test/scala/com/hmemcpy/metallurgy/compat`. The 47 fully qualified class names hash to:

```text
9034b154c5a60f1e4731b5a20d8f4236dfa906d537e013de8a4a886363833c85
```

The first bounded run selected `com.hmemcpy.metallurgy.compat.*`. It reached the 600-second process-group timeout after
writing reports for 36 suites. Those reports contain 224 tests and 10 failures. The timeout is an orchestration outcome,
not an additional test failure.

The second bounded run selected exactly the 11 discovered classes without a fresh report. It completed in 508 seconds
with 182 tests and five failures. Combining the two report sets gives exact discovery closure: 47 expected reports,
47 observed reports, no missing reports, and no unexpected reports.

Both runs used JBR 25.0.3, IntelliJ 261.26222.65, and the bundled Scala plugin 2026.1.20. The compatibility fixture
forces Scala 3.5.2 with `JDK_17`; that is distinct from the plugin implementation's Scala 3.7.4 build version.

## Failure shape

The 15 failing tests divide into:

- two fixture crashes in partially named type-argument tests;
- one named-tuple type-rendering disagreement;
- twelve highlighting disagreements involving named tuples, extensions, universal apply, curried type parameters,
  infix type arguments, and overloaded higher-order calls.

The JUnit files report all 15 as failures and no tests as errors. That convention hides an important distinction: the
two `None.get` results are crashes, not assertion mismatches. Their IntelliJ log contains 36 failed stub-tree build
events rooted in `ScTupleTypeElement.typeList`; each is logged with a `PluginException` whose cause is the same
`NoSuchElementException`.

There are no lines at IntelliJ's `SEVERE` log level in either retained log. One additional test-process event reported
that PSI, document, or model changes were attempted during highlighting. It was visible while
`testWithCompanionObjectTypeMismatch` ran, but it is not present in the retained JUnit XML or `idea.log`; the JSON
records it as an uncounted console-only observation. A repeatable runner must retain process output so this event can be
counted and attributed on the next measurement.

The failure labels are observations, not root-cause judgments. In particular, an unexpected highlight has not been
classified as a false compiler diagnostic until the unchanged source graph and exact environment have been checked
against dotc.

## What passing does not establish

The current suite is not the integrity-checked generated test tree described in
[`copied-intellij-test-integrity-harness.md`](copied-intellij-test-integrity-harness.md). Known differences include
trimming source before configuration and checking only completion-item presence where the upstream helper performs an
editor insertion and compares the resulting document.

Consequently, the 391 green results do not establish:

- unchanged upstream executable bodies and expected payloads;
- equivalent upstream fixture and helper contracts;
- complete composite and leaf PSI shape or stub/index shape;
- navigation, find usages, refactoring, light PSI, or UAST behavior;
- every compiler-valid, expected-rejection, and incomplete-edit checkpoint;
- exact-environment conflict classification;
- a moving capability matrix across supported compiler and Scala-plugin combinations;
- generation-matched BETASTY recovery for downstream highlighting across module boundaries;
- graduation against real projects with demanding type-level code.

Checkpoint classification and exact-environment proof requirements are defined in
[`parity-recovery-compiler-conflict-classification.md`](parity-recovery-compiler-conflict-classification.md).

## Repeatable baseline contract

The implemented baseline runner should preserve the JSON shape in this directory and replace this manual two-run
measurement with the following deterministic process:

1. discover suite class names from the generated selected-test manifest, sort them, and record the inventory hash;
2. run bounded class-level shards in a stable order, with a fresh IntelliJ system directory and retained stdout,
   stderr, `idea.log`, and JUnit XML for each shard;
3. require exactly one report for every discovered suite and reject missing or unexpected reports;
4. distinguish assertion disagreements, fixture crashes, process timeouts, and platform log events even when JUnit
   serializes them all as failures;
5. store exact source revision, compiler artifacts, fixture options, classpath identity, JDK, IntelliJ build, and
   Scala-plugin artifact identity;
6. emit results atomically only after report closure and schema validation;
7. compare later observations by stable suite, test, checkpoint, and environment identities rather than elapsed time or
   temporary paths.

Until that runner and the generated selected-test inventory exist, this JSON is a factual starting point for planning,
not a CI gate and not evidence that structural parity is 96.3 percent complete.
