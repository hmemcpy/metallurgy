# Fetch the Scala presentation compiler on demand

Metallurgy ships small and resolves `org.scala-lang:scala3-presentation-compiler_3:<module-scala-version>`
plus its transitive graph on first use of an exact Scala version. The stable Java API comes from
`org.scalameta:mtags-interfaces`; there is no stable `org.scalameta:mtags_3.5` or `mtags_3.7`
artifact.

Resolution runs in IntelliJ's cancellable `Task.Backgroundable` and delegates to the bundled Scala
plugin's `DependencyManager`. That preserves IntelliJ proxy, repository, cancellation, and Ivy-cache
behaviour without adding coursier to Metallurgy or passing Scala values across plugin classloaders.
Resolved binary jars are copied into
`PathManager.getSystemDir()/caches/metallurgy/presentation-compiler/<scala-full-version>/`, with a
SHA-256 manifest validated on every warm-cache lookup.

The alternative was bundling the presentation-compiler graph for every supported Scala version directly
in the plugin zip (~50 MB per version). We rejected that because:

- The plugin download size scales linearly with the number of supported Scala 3 minors; with fetch-on-demand, it stays constant.
- IntelliJ and the bundled Scala plugin already provide the infrastructure we need (background tasks, progress UI, proxy/auth handling, repositories, and transitive Ivy resolution). No coursier dependency, no extra weight.
- The cost (one-time first-use download of ~50 MB per Scala version) is paid once and amortised across every project on the user's machine that shares that Scala version.

The trade-off is offline support: users without cached artifacts and no network get a logged resolution
failure and a no-op plugin. We accept this — the bundled plugin keeps working offline regardless — and
the validated cache makes later opens local-only.
