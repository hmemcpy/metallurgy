# Representative scale policy

P123-D proves faithful PSI for representative compiler-valid source shapes. The earlier capacity suite used widths of
10,000 and nesting depths of 256, 384, 512, and 1,024. Those limits measured compiler and IntelliJ capacity rather
than an additional PSI contract. Its preserved logs and samples remain under
`/Users/hmemcpy/git/metallurgy-123d-evidence/final-stress-blocker-20260808`,
`final-stress-recovery-20260808`, and `final-stress-final-20260808`.

The executable physical scale suite now has six cases. Width cases use 32 repeated elements and nesting cases use
depth 16. It retains mixed top-level generic definitions and right-hand-side payloads, nested generic owners,
expression payload and select chains, parenthesized singleton and applied type wrappers, positional and named type
arguments with bounded parameters and wildcards, and alternating bounded lambda and applied types. Every case checks
exact source reconstruction and representative first, middle, and last text, ranges, direct parents, accessors, and
order. Focused suites continue to own exhaustive edit, recovery, stub, reopen, index, and fail-closed contracts.

The six physical cases passed in 7.441 seconds total after fixture startup. Their recorded method times were 1.749,
1.187, 1.165, 1.120, 1.049, and 1.171 seconds in execution order. They run with the ordinary 120-second command fuse
and no wall-clock assertions.

Fast deterministic planner tests preserve complexity coverage without giant physical files. Final ownership work for
64, 128, 256, 512, and 1,024 source atoms was 384, 768, 1,536, 3,072, and 6,144 examined entries. The terminal range
series used 32, 64, 128, 256, and 512 tokens, 63, 127, 255, 511, and 1,023 lexical atoms, and 66, 130, 258, 514, and
1,026 lookups. Lexical and candidate entries examined were respectively 324/255, 709/575, 1,542/1,279,
3,335/2,815, and 7,176/6,143. Semantic reconstruction, identity, order, representative ownership, and fail-closed
checks remain primary; generous linear and n-log-n envelopes prevent repeated whole-vector scans without exact
operation goldens.

The alternating bounded lambda source at depth 16 compiles and types under Scala 3.5.2 and passes physical PSI. The
catalog accepts its repeating `LambdaTypeTree` and `AppliedTypeTree` lineage and an applied type accepts an explicit
type lambda as an argument. The historical depth-512 generated coordinate remains visible in the external archives as
a known representability defect, not an executable or ignored test. The exact depth-16 source, compile, REPL, and typer
evidence is preserved in `/Users/hmemcpy/git/metallurgy-123d-evidence/bounded-scale-policy-20260808` with SHA-256 hashes
`83fca87aef7566424a07a61fdb191215cb2a7a895fe9a1517c72ae807a0dad15`,
`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`,
`0f3667b5beac2f62f3fc6c40f9157e882a277f1d2b29216fde12aa175ec93319`, and
`0de49848c01d528ebc8f3c170babfd63629fd084768e2f748fb4e795f633274a` respectively.
