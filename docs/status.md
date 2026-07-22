# Status

## Scala runtime no longer bundled — classloader split fixed (`e0d4c0b`)

`runIDE` was throwing `java.lang.LinkageError` from `PcTypeHintsPass.collectHints` on
every active Scala 3 file:

> loader constraint violation … `ScValueOrVariableDefinition.bindings()` … different Class objects
> for `scala.collection.immutable.Seq`

**Root cause.** The plugin is compiled with Scala 3.7.4, so sbt injects `scala3-library_3` (and
transitively `scala-library` 2.13.x) onto the classpath. sbt-idea-plugin's automatic exclusion
matches `name.contains("scala-library")` / the `scala-.*` pattern, which catches the Scala 2.13
`scala-library` but **not** `scala3-library` — so neither was marked `Provided`, and both were
shipped in the artifact. The plugin's classloader then held its own `scala.collection.immutable.Seq`,
distinct from the bundled Scala plugin's, and the JVM loader-constraint check failed the first time
the inlay pass called a bundled-Scala-PSI method whose signature mentions `Seq`.

**Fix.** Exclude both from packaging via `packageLibraryMappings` (the same mechanism intellij-scala
uses for its Scala 3 modules):

```scala
packageLibraryMappings := Seq(
  "org.scala-lang" % "scala-library"     % scala2LibraryVersion -> None,
  "org.scala-lang" % "scala3-library_3" % scalaVersion.value   -> None
)
```

Both are supplied at runtime by the bundled Scala plugin's **main** classloader
(`plugins/Scala/lib/scala-library.jar`, `plugins/Scala/lib/scala3-library_3.jar`), reached through the
required `<depends>org.intellij.scala</depends>` delegation — confirmed by codex against the 2026.1.20
distribution and IntelliJ's `PluginClassLoader.tryLoadingClass`. Evidence and caveats are in
[`docs/research/16`](research/16-scala-runtime-plugin-classloading.md).

**Verified.** The artifact ships only `metallurgy.jar` + `mtags-interfaces-1.3.4.jar`; `runIDE` starts
with no class-load errors. Caveat: this is coupled to the Scala plugin shipping a compatible Scala 3
runtime in root `lib/` — re-check if the supported Scala-plugin range widens.

## Screenshots — manual

Editor automation from the agent is not viable: the bundled Scala plugin is a Java/AWT app whose
editor is an opaque canvas (code identifiers are not Accessibility elements), and codex's computer-use
is gated to OpenAI's signed app. So captures are done by hand.

- Showcase snippets: `dogfood/src/main/scala/dogfood/showcase/` (one file per capability, each annotated
  with what to hover and the type the plugin should show).
- Captures land in `docs/screenshots/` and are wired into `README.md` (work in progress).
