# Scala runtime delegation from a dependent IntelliJ plugin

## Question

Can a Scala 3.7.4 IntelliJ plugin that declares a required dependency on `org.intellij.scala` exclude both
`scala-library` and `scala3-library_3` from its distribution and resolve their classes from Scala plugin 2026.1.20 at
runtime? In particular, is `scala.deriving.Mirror$Singleton` in the Scala plugin's registered plugin classloader, or
only in an isolated compiler/presentation-compiler classloader?

## Conclusion

**Yes, for the inspected 2026.1.20 distribution.** Excluding both runtime JARs is the correct way to avoid defining a
second, incompatible `scala.collection.immutable.Seq` in the dependent plugin's classloader. With a required
`<depends>org.intellij.scala</depends>`, a class missing from the dependent plugin is sought in the Scala plugin's
registered classloader. Scala plugin 2026.1.20 puts both `scala-library.jar` and `scala3-library_3.jar` directly in its
root `plugins/Scala/lib/` directory. The latter is version 3.7.4 and contains
`scala/deriving/Mirror$Singleton.class`. It is therefore on the Scala plugin's ordinary plugin classpath and is visible
to a dependent plugin; it is not confined to an ad-hoc compiler child classloader.

This is concrete for Scala plugin 2026.1.20, not a permanent public API promise that every future Scala plugin must
ship these implementation libraries in the same place. A plugin supporting other Scala plugin versions should inspect
or smoke-test each supported distribution. It also remains coupled to the Scala runtime ABI/version shipped by that
Scala plugin.

## Evidence

### IntelliJ dependency classloading

The IntelliJ Platform SDK states that each plugin has a dedicated classloader and that, when `plugin.xml` declares a
`<depends>` relationship, dependency plugin classloaders are used for classes not found in the current plugin. This is
the mechanism that permits one plugin to reference another plugin's classes:
[Class Loaders — Loading Classes from Plugin Dependencies](https://plugins.jetbrains.com/docs/intellij/plugin-class-loaders.html#loading-classes-from-plugin-dependencies).

The implementation is self-first for plugin classes. `PluginClassLoader` first calls `loadClassInsideSelf`; if that
does not find the class, it walks its registered parent/dependency loaders and calls their `loadClassInsideSelf`:
[`PluginClassLoader.kt`, `tryLoadingClass`](https://github.com/JetBrains/intellij-community/blob/218af635f1ee4a96052e0b4f7b1a692c7658ff7c/platform/core-impl/src/com/intellij/ide/plugins/cl/PluginClassLoader.kt#L163-L207).
This explains both outcomes:

- if the dependent plugin bundles `scala-library`, its own copy wins and can conflict with Scala-PSI signatures loaded
  by `org.intellij.scala`;
- if it excludes that copy, lookup proceeds to the declared Scala plugin dependency and returns the Scala plugin's
  class object.

The dependency list is assembled from plugin descriptors' registered `pluginClassLoader` values, followed by the core
loader:
[`PluginClassLoader.kt`, parent construction](https://github.com/JetBrains/intellij-community/blob/218af635f1ee4a96052e0b4f7b1a692c7658ff7c/platform/core-impl/src/com/intellij/ide/plugins/cl/PluginClassLoader.kt#L259-L297).
An ordinary compiler/presentation-compiler child classloader created internally by a plugin would not appear in that
list and would not be visible upward to dependants. The location of the JAR in the plugin distribution is therefore
load-bearing.

### Scala plugin 2026.1.20 packaging

The exact Scala plugin tag defines its runtime versions as Scala 2.13.18 and Scala 3.7.4:
[`project/dependencies.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/project/dependencies.scala#L8-L10).

More importantly, its main-project packaging settings deliberately add both dependencies and map them to root plugin
library paths:

- `lib/scala-library.jar`
- `lib/scala3-library_3.jar`

See
[`project/Common.scala`](https://github.com/JetBrains/intellij-scala/blob/2026.1.20/project/Common.scala#L153-L174).
The IntelliJ Platform packaging contract says that **all JARs in a plugin's root `/lib` directory are automatically
added to the plugin classpath**:
[Plugin Content — Plugin With Dependencies](https://plugins.jetbrains.com/docs/intellij/plugin-content.html#plugin-with-dependencies).
Platform setup then constructs and registers the plugin loader with the descriptor's JAR classpath:
[`ClassLoaderConfigurator.kt`](https://github.com/JetBrains/intellij-community/blob/218af635f1ee4a96052e0b4f7b1a692c7658ff7c/platform/core-impl/src/com/intellij/ide/plugins/ClassLoaderConfigurator.kt#L185-L213).

The locally resolved target distribution confirms the published build layout rather than merely the build intention:

```text
~/.metallurgyPluginIC/sdk/261.26222.65/plugins/Scala/lib/scala-library.jar
~/.metallurgyPluginIC/sdk/261.26222.65/plugins/Scala/lib/scala3-library_3.jar
```

Its `lib/pluginXml.jar!/META-INF/plugin.xml` reports plugin ID `org.intellij.scala`, version `2026.1.20`. The Scala 3
library manifest reports `Implementation-Version: 3.7.4`, and its archive contains:

```text
scala/deriving/Mirror$.class
scala/deriving/Mirror$Product.class
scala/deriving/Mirror$Singleton.class
scala/deriving/Mirror$SingletonProxy.class
scala/deriving/Mirror$Sum.class
scala/deriving/Mirror.class
scala/deriving/Mirror.tasty
```

The JAR is beside the Scala plugin's main JARs in root `lib/`, not under `lib/modules/`, `lib/jps/`, a compiler cache,
or another isolated-loader directory. Consequently, `scala.deriving.Mirror$Singleton` resolves through the registered
`org.intellij.scala` plugin classloader when the dependent plugin does not provide its own definition.

## Practical boundary

For the fixed pair IntelliJ 261.x + Scala plugin 2026.1.20, the expected distribution is therefore:

```text
dependent plugin lib/: no scala-library.jar, no scala3-library_3.jar
Scala plugin lib/:     scala-library.jar 2.13.18, scala3-library_3.jar 3.7.4
```

The dependent plugin may still use those artifacts on its **compile** classpath; only their artifact packaging should
be disabled. Its `plugin.xml` must retain the required dependency on `org.intellij.scala`. A packaged-artifact check
and a runtime smoke test that loads `scala.deriving.Mirror$Singleton` are appropriate release checks if the supported
Scala plugin range later broadens.
