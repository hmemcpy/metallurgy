# P123-A type-atom evidence

## Candidate basis and scope

- Parent commit: `0b73620b1e84fb26b0ee7b00b4d1771198a09671`
- Parent tree: `16b01015825b91b8342e76b1081986389e1de0f5`
- Compiler: `org.scala-lang:scala3-compiler_3:3.7.4`
- Host: IntelliJ IC `261.26222.65`, Scala plugin `2026.1.20`, JBR `25.0.3+9-b329.124`
- Persistence schema version: `7`
- Persistence schema fingerprint: `fca7c1c121b889b900e4dcd62e8fb8ccc1be6bd8916aaddf710a84073c0b26b5`

The packet adds non-stub grammar and output roles for type references and paths, type projections, singleton types,
literal types, and parenthesized types. Physical admission remains limited to the existing import-selector given-bound
context. Definition, template, parameter, parent, self-type, derives, type-application, and compound-type ownership is
not admitted.

## Exact compiler evidence

Scala CLI compile, REPL, and `-Xprint:typer` use the same verbatim source covering `A`, `p.A`, `T#A`, `x.type`,
integer, negative integer, long, float, double, character, string, and boolean literal types, and `(A)`. Their local
evidence files have these SHA-256 digests:

| Evidence | SHA-256 |
| --- | --- |
| `TypeAtoms.scala` | `54a9799b8fbac36445d27cc361180f57cb87c6473358f598a330b55beaaaff4c` |
| `repl.in` | `00cbb2781461f2e89e56d2aa20d09cf7c1f082634cde6f43febdd294b71207f6` |
| compile | `e6e6b900d41dae128dc72d5983515959a887f84126799633f7698f596d85e5b9` |
| typer | `60e581158f9b832bf68a5b282ed345ae0ebd33484329d65759601f082676a979` |
| REPL | `dfdadf5798903208d4f4f947cedbd9c1de265d23ef474c09a34c1b8a98be095a` |

The parser inventory asserts exact products, fields, source ranges, point offsets, position provenance, occurrences,
source reconstruction, deterministic snapshots, and scanner tokens for 14 cases. It proves `Ident(name)`,
`Select(qualifier,name)`, `SingletonTypeTree(ref)`, synthetic `SingletonTypeTree(Literal(const))`, and `Parens(t)`.
Scanner evidence owns `.` and `#` separately, keeps every source token non-empty, represents inserted layout events as
distinct zero-width synthetic evidence, and rejects inverted ranges. The exact evidence and scanner fingerprint vectors
are executable constants in `Scala3TypeAtomParserInventoryTest`.

The exact compiler artifact inventory recorded by the IC-261 lifecycle includes:

| Artifact | SHA-256 |
| --- | --- |
| `scala3-compiler_3-3.7.4.jar` | `1a60682b898fb04753dec78f2904078d48670079b7ad5106d442a0f431d346e0` |
| `scala3-interfaces-3.7.4.jar` | `c19ba8ea1a13c7fcdef675353c8ddd671156cf220879dcb28b27e986aae31ea9` |
| `scala3-library_3-3.7.4.jar` | `73089d33f763b94758170e0a66be978f9397c14b7795e343898aa89b3d7b6639` |
| `tasty-core_3-3.7.4.jar` | `24dfe7089232a42d28d5bee1209f462e4b4ca90622e0b13752399c12585e3069` |
| `scala-asm-9.8.0-scala-1.jar` | `86af037580bdf9ce9c05f8b2afd734daf1a8564c38cd10ca5d08bf81508ad2e4` |
| `compiler-interface-1.10.7.jar` | `2bacc5761e03920a228e5c9d20b33d9c51d43aaf2f52e8f839ece630966eb880` |
| `scala-library-2.13.16.jar` | `1ebb2b6f9e4eb4022497c19b1e1e825019c08514f962aaac197145f88ed730f1` |

## Validation

| Gate | Result |
| --- | --- |
| Parser inventory | 3/3 passed in 1.794 s |
| Production catalog | 66/66 passed in 3.211 s |
| Parser bridge | 11/11 passed in 5.155 s |
| Source evidence planner | 7/7 passed |
| Generic emitter | 17/17 passed in 9.724 s |
| Final parser vertical slice | 11/11 passed in 75.986 s |
| Physical package/type-atom suite | 7/7 passed in 35.912 s |
| Ordinary lifecycle and unchanged regressions | 50/50 passed in 77 s |
| Baseline syntax/PSI lane | 5/5 suites, 34/34 tests passed |
| Local CI lane | 11/11 suites, 62/62 tests passed |
| `Test/compile` and `packageArtifact` | passed |
| `scalafmtAll` and `scalafmtCheckAll` | passed |
| IC-261 lifecycle | 11/11 passed in 73 s; message pool and final `idea.log` clean |
| Complete unchanged stress suite | 7/7 passed in 898.383 s; command completed in 901 s |

The copied IntelliJ origin audit matched all five pinned blobs and bytes. The local copied-byte audit found no new
difference and retained the approved `Scala3CompatTestCase.scala` tracked-versus-manifest mismatch. Copied type
inference snippets remain outside this packet because type atoms are not yet attached to definition owners.

## Review

The Medium standards review passed with no finding. The Medium specification review's sole concern was that the
aggregated compiler inventory encoding tag should equal the file persistence schema. They are separate revisions: the
file persistence schema remains exactly `7`, while the inventory's canonical encoding moved from `5` to `6` because
scanner-token evidence was added. The green persistence fingerprint and serialization tests cover both boundaries.

## Local stress-run proof

The successful unchanged suite ran on Hax.lan, an arm64 Apple M2 Pro with 10 CPUs and 32 GiB RAM. It used the published
JBR 25 binary, one non-parallel forked test group, `-Xms128m`, `-Xmx2048m`, G1 GC, and the repository's existing JVM
options. The sbt host used `-Xms1024m`, `-Xmx1024m`, and `-Xss4M`. No environment JVM override was set.

| Evidence | SHA-256 |
| --- | --- |
| Full stress log | `5dd26acbd2d14dc8ad0724c76e9385e98bdd9b0346337dd298b7d64ce087f76b` |
| Process and progress monitor | `0e7989160c9fedb4d8c79312a168bc52c7e757088cc83628c36356816a5cf7b5` |
| Effective forked-JVM argument file | `a7a54151ce62b4c5475f168c27a06bfbfcf892b65de0ee025eed2e2ff8396a11` |
| First responsive thread sample | `13b1e0ba310c447847683c7de69b4fa7cc8f6d33de92722bd62369347da43a90` |
| Repeated-RHS responsive thread sample | `3429f5b200e9faa452c63cfd16c14cd0a94fd048ed5dae73dc2ec0292472bb6b` |
| Local runner/JBR audit | `2de5dcfccb16fb0b9c96a7de74b99da7083b4204a8d844cdb968d356dc45fcd2` |
| Effective sbt test settings | `12fe14c3d53cf759d4dd227bd3774447ebd8a321aee8ea13bf858a9ee409cd75` |

The durable archive identifier is `metallurgy-123a-evidence/final-20260807T150028Z`. Its `SHA256SUMS` file has SHA-256
`fc31fa3fec117033acae907bf5b9ecba312d3979ba956d9e86ce895485f7d782`, and every archived entry was verified after
creation.
