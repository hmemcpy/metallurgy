# In-tree testkit backport from `JetBrains/intellij-scala` `idea261.x`

Metallurgy's test infrastructure lives in-tree under `testkit/src/main`, freshly backported from the `idea261.x` branch of `JetBrains/intellij-scala`. It is not extracted into a shared library, not consumed as a published artifact, and not imported from any other project.

The backport remains Scala 2.13 because that is the source dialect used by the bundled plugin's fixtures. It has a small nested sbt build under `testkit/`; the Scala 3 plugin build compiles it before compiling tests and adds only its output directory to the test classpath. Keeping it out of the root sbt project graph also prevents `sbt-idea-plugin` from packaging the fixtures as another IntelliJ plugin node. Run the complete headless suite with `sbt test` (or its `testHeadless` alias).

The two alternatives we rejected:

- **Shared library** (`com.hmemcpy:intellij-scala-testkit`). Conceptually cleaner, and would let other third-party Scala IntelliJ plugins reuse the work. We rejected it for two reasons: (a) upstream (the bundled Scala plugin) rewrites its testkit API significantly without warning, so a published library would be a perpetual moving target; (b) the publishing/versioning/maintenance burden is real and the audience is small (~20 active Scala IntelliJ plugin authors worldwide).
- **Copy from zio-intellij.** zio-intellij's testkit is one major IntelliJ version behind, because it was backported for an earlier platform release and hasn't been refreshed. Copying it would mean inheriting that staleness.

Backporting fresh from `idea261.x` ensures the testkit matches the bundled-plugin version we depend on. The cost is duplicated effort with zio-intellij's own copy (drift between the two is inevitable). We accept that: when the bundled plugin updates, both projects re-backport independently. A future extraction to a shared library is not closed off, just deferred until drift between the two copies becomes painful enough to justify the publishing burden.
