# Metals and the Scala 3 presentation compiler: coupling, version selection, and reusable boundaries

Research date: 2026-07-22

Source baselines:

- Metals commit [`c7402f4b05aada79b2c84959d551cd6a658ebc1a`](https://github.com/scalameta/metals/tree/c7402f4b05aada79b2c84959d551cd6a658ebc1a) (current `main` when this note was written).
- Scala 3 tag [`3.7.4`](https://github.com/scala/scala3/tree/3.7.4), commit `40be7608a48477951218ae3a8ac8749fe02ba988`, matching Metallurgy's current compiler.
- Scala 3 commit [`7c8ee3cb7e1e69f46cdad16ff88b2076d916e58a`](https://github.com/scala/scala3/tree/7c8ee3cb7e1e69f46cdad16ff88b2076d916e58a) (current `main` when this note was written), used only to check the direction of the public PC boundary.

## Executive conclusion

Metals does **not** compile its server against `dotty.tools.dotc` or reflect over compiler internals. It compiles against the Java-only, non-cross-built `mtags-interfaces` API, resolves the exact `scala3-presentation-compiler_3:<project Scala version>` artifact at runtime, isolates that artifact and its exact compiler dependencies in a child classloader, and calls it through `scala.meta.pc.PresentationCompiler`. The version-matched Scala 3 PC implementation itself directly imports and uses `InteractiveDriver`; that tight compiler coupling lives inside the Scala 3 distribution, where it can evolve in lockstep with the compiler.

That is the central pattern Metallurgy should reuse. It does not, however, solve Metallurgy's whole problem as-is. Metals has a hardcoded minimum version for choosing the standalone Scala 3 PC, and the published Scala 3 PC does not currently register a Java service provider. Consequently, Metals' `ServiceLoader` attempt falls back to a hardcoded implementation class and reflective construction. Nor does `PresentationCompiler` expose a general capability handshake or the bulk typed-tree/type-snapshot operation required by Metallurgy. Avoiding private IntelliJ and dotc hacks therefore requires a small **published Scalameta API addition** to `mtags-interfaces`, implemented by `scala3-presentation-compiler`, rather than a Metallurgy-private compiler protocol or discovery of bundled-plugin classes/dotc trees.

## 1. The coupling boundary

Metals' own contributor documentation states the intended split precisely: PC work is per build target and per Scala version; Scala 3's implementation is maintained in `scala/scala3`; and communication happens through `mtags-interfaces`' `PresentationCompiler.java` ([`docs/contributors/getting-started.md:28-38`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/docs/contributors/getting-started.md#L28-L38)).

### Compile-time dependencies

The host/server side depends on `mtags-interfaces`, a Java 8, non-cross-built project. Metals runs MiMa against several earlier interface releases, making binary compatibility an explicit release invariant ([`build.sbt:248-269`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/build.sbt#L248-L269)). `mtags-shared`, which Metals consumes, also depends on that interfaces project ([`build.sbt:271-296`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/build.sbt#L271-L296)).

`PresentationCompiler` is an abstract Java class whose documentation calls it the public PC API ([`PresentationCompiler.java:28-33`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/mtags-interfaces/src/main/java/scala/meta/pc/PresentationCompiler.java#L28-L33)). Its signatures use Java/JDK types, LSP4J DTOs, and other `scala.meta.pc` interfaces rather than Scala collections or dotc types. Metals calls public operations such as completion, hover, definition, SemanticDB generation, and lifecycle/configuration through this class ([`PresentationCompiler.java:39-117`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/mtags-interfaces/src/main/java/scala/meta/pc/PresentationCompiler.java#L39-L117), [`PresentationCompiler.java:242-364`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/mtags-interfaces/src/main/java/scala/meta/pc/PresentationCompiler.java#L242-L364)).

The Scala 3 artifact is built against both its exact compiler and a particular `mtags-interfaces` release. At Scala 3.7.4, the project depends on the bootstrapped compiler/library and `mtags-interfaces` 1.6.2 ([`project/Build.scala:2561-2565`](https://github.com/scala/scala3/blob/3.7.4/project/Build.scala#L2561-L2565), [`project/Build.scala:2589-2600`](https://github.com/scala/scala3/blob/3.7.4/project/Build.scala#L2589-L2600)). The published [3.7.4 POM](https://repo1.maven.org/maven2/org/scala-lang/scala3-presentation-compiler_3/3.7.4/scala3-presentation-compiler_3-3.7.4.pom) confirms exact `scala3-compiler_3:3.7.4`, `scala3-library_3:3.7.4`, and `mtags-interfaces:1.6.2` dependencies.

### Where direct dotc coupling lives

The Scala 3 PC implementation extends the public `PresentationCompiler` API but directly imports `dotty.tools.dotc.interactive.InteractiveDriver` and constructs the compiler wrapper/driver ([`ScalaPresentationCompiler.scala:31-57`](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/ScalaPresentationCompiler.scala#L31-L57), [`ScalaPresentationCompiler.scala:128-141`](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/ScalaPresentationCompiler.scala#L128-L141)). Individual public operations unwrap the version-specific driver internally; for example, hover delegates to `HoverProvider` using the driver ([`ScalaPresentationCompiler.scala:444-454`](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/ScalaPresentationCompiler.scala#L444-L454)), while SemanticDB construction delegates through a driver-backed provider ([`ScalaPresentationCompiler.scala:275-287`](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/ScalaPresentationCompiler.scala#L275-L287)).

This is direct, compile-time coupling—not reflection—but it is confined to an artifact released with the exact compiler. That placement is what makes the coupling sustainable.

## 2. Runtime artifact selection

The build target's Scala version is the selection key. For Scala versions at or after the hardcoded `3.3.4-RC1` threshold, `MtagsResolver` chooses the standalone Scala 3 PC path; earlier versions use version-specific Metals `mtags` artifacts ([`MtagsResolver.scala:109-139`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/MtagsResolver.scala#L109-L139)). It then resolves either:

- `org.scala-lang:scala3-presentation-compiler_3:<exact Scala version>`, or
- `org.scalameta:mtags_<full Scala version>:<Metals version>`

([`MtagsResolver.scala:145-183`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/MtagsResolver.scala#L145-L183), [`Embedded.scala:347-364`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/Embedded.scala#L347-L364)). Resolution is cached by version, retried up to five times on a failure, and a failed entry becomes retryable after five minutes ([`MtagsResolver.scala:156-194`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/MtagsResolver.scala#L156-L194), [`MtagsResolver.scala:218-242`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/MtagsResolver.scala#L218-L242)).

Coursier receives the exact requested version. Metals adds Maven Central's snapshot repository generally and adds Scala's nightly repository when the version contains `NIGHTLY`; it retains an older special snapshot repository for Scala 3.4 artifacts ([`Embedded.scala:208-218`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/Embedded.scala#L208-L218), [`Embedded.scala:395-443`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/Embedded.scala#L395-L443)). `downloadScala3PresentationCompiler` does not map a Scala version to a different compiler: it downloads that exact coordinate ([`Embedded.scala:539-543`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/Embedded.scala#L539-L543)).

Therefore stable releases, RCs, and nightlies work dynamically **when the exact PC artifact and all dependencies are published in a configured repository**. This is artifact-driven compatibility after the initial threshold, not a matrix of implementation fingerprints. It is also not literally universal: the minimum-version decision is hardcoded, repository availability still matters, vendor suffix handling is incomplete for artifact selection, and old releases rely on a maintained compatibility table ([`MtagsResolver.scala:46-107`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/MtagsResolver.scala#L46-L107)).

## 3. Classloader isolation and object exchange

For each compiler version, Metals builds a `URLClassLoader` over all resolved PC/compiler jars. Its parent is a deliberately restrictive `PresentationCompilerClassLoader` rather than the Metals application loader ([`Embedded.scala:182-190`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/Embedded.scala#L182-L190)). The parent delegates only these shared API families back to Metals:

- `scala.meta.pc`
- `org.eclipse.lsp4j`
- `com.google.gson`
- `javax`

Everything else is rejected by the parent, allowing the child URL loader to load the exact compiler's Scala, dotc, and implementation dependencies ([`PresentationCompilerClassLoader.scala:3-24`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/PresentationCompilerClassLoader.scala#L3-L24)).

This is the load-bearing isolation mechanism. It prevents the host's Scala version from becoming the compiler's Scala version and constrains cross-loader values to stable DTO/interface types. The classloader is cached under the Scala version with vendor suffix removed ([`Embedded.scala:86-105`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/Embedded.scala#L86-L105)).

After loading a factory-like uninitialized PC, Metals configures it through public `with...` methods and calls `newInstance` with the target classpath and scalac options ([`CompilerConfiguration.scala:288-336`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/CompilerConfiguration.scala#L288-L336)). Scala 3.7.4 implements `newInstance` as a copy carrying those target-specific inputs ([`ScalaPresentationCompiler.scala:481-490`](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/ScalaPresentationCompiler.scala#L481-L490)).

## 4. Service loading, reflection, and hardcoded knowledge

Metals first asks `ServiceLoader` for a `PresentationCompiler` implementation. If none is found, it loads a supplied class name, requests its no-argument constructor, calls `setAccessible(true)`, and instantiates it reflectively ([`Embedded.scala:108-123`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/Embedded.scala#L108-L123)). For Scala 3 the supplied implementation name is hardcoded as `dotty.tools.pc.ScalaPresentationCompiler` ([`Embedded.scala:86-105`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/Embedded.scala#L86-L105)).

This fallback is not merely theoretical for Scala 3. Inspection of the published `scala3-presentation-compiler_3:3.7.4` jar found `ScalaPresentationCompiler` classes but no `META-INF/services/scala.meta.pc.PresentationCompiler` entry. The source tree likewise contains no service descriptor. The implementation does expose a public no-argument constructor ([`ScalaPresentationCompiler.scala:61-73`](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/ScalaPresentationCompiler.scala#L61-L73)), so `setAccessible(true)` is unnecessary for that release, but the loader still assumes a concrete class name.

Metals therefore minimizes private compiler coupling but does not achieve pure provider discovery. Metallurgy should copy the boundary, not this fallback. A proper provider registration in the Scala 3 PC artifact would let `ServiceLoader` be the only implementation-discovery mechanism.

## 5. Forward compatibility and feature discovery

### What exists today

The broad compatibility strategy is binary API evolution. Core historical operations remain abstract. New optional operations are commonly added as concrete methods with neutral defaults, so an older implementation can run under a newer host API. Examples include semantic tokens, references, inline value, inlay hints, synthetic decorations, and symbol information ([`PresentationCompiler.java:39-46`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/mtags-interfaces/src/main/java/scala/meta/pc/PresentationCompiler.java#L39-L46), [`PresentationCompiler.java:112-143`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/mtags-interfaces/src/main/java/scala/meta/pc/PresentationCompiler.java#L112-L143), [`PresentationCompiler.java:169-216`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/mtags-interfaces/src/main/java/scala/meta/pc/PresentationCompiler.java#L169-L216)). Several newer configuration methods similarly default to returning `this` ([`PresentationCompiler.java:263-327`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/mtags-interfaces/src/main/java/scala/meta/pc/PresentationCompiler.java#L263-L327)). MiMa checks enforce this convention on the interfaces artifact.

There is one explicit feature list: `supportedCodeActions()`. Its default is empty, and the Scala 3 PC returns stable string IDs for its implemented actions ([`PresentationCompiler.java:138-143`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/mtags-interfaces/src/main/java/scala/meta/pc/PresentationCompiler.java#L138-L143), [`ScalaPresentationCompiler.scala:61-69`](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/ScalaPresentationCompiler.scala#L61-L69)). Metals consults the list before choosing the generic code-action entry point ([`Compilers.scala:839-868`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/Compilers.scala#L839-L868)). This is the closest existing precedent for semantic capability negotiation.

Other optional operations do not have explicit capability discovery. Metals calls them and interprets neutral output as absence. For example, it calls `inlayHints`; if the result is empty, it calls the older `syntheticDecorations` path ([`Compilers.scala:706-785`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/Compilers.scala#L706-L785)). That cannot distinguish “supported, legitimately no results” from “not implemented,” so it is not a sufficient model for a replacement backend.

`hover` is not a structured type-query substitute. Scala 3's provider computes a distinct rendered expression type and
passes it to `ScalaHover` ([`HoverProvider.scala:101-150`](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/HoverProvider.scala#L101-L150)),
but the published `HoverSignature` interface exposes only the symbol signature, LSP markup, and range
([`HoverSignature.java:8-15`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/mtags-interfaces/src/main/java/scala/meta/pc/HoverSignature.java#L8-L15)).
The shared implementation retains `expressionType` internally, then folds it together with signature and documentation
when producing presentation markup
([`ScalaHover.scala:12-20,40-52`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/mtags-shared/src/main/scala/scala/meta/internal/pc/ScalaHover.scala#L12-L52)).
Parsing Markdown or reflectively calling the implementation accessor would couple Metallurgy to presentation or private
implementation details. A public structured expression-type accessor could be an incremental upstream improvement, but
it would still require one query per PSI root and would not supply the whole-file symbol/synthetic records needed here.

Compiler language experiments are a separate concern. Metals forwards ordinary build-target scalac options to the exact PC after filtering compiler-plugin options ([`CompilerConfiguration.scala:193-227`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/CompilerConfiguration.scala#L193-L227), [`CompilerPlugins.scala:39-58`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/CompilerPlugins.scala#L39-L58)). Thus a new syntax/type-system feature that the exact compiler understands can work without Metals understanding the compiler's private trees. By contrast, a new host-visible operation still requires a public API method or generic capability protocol.

### What is missing

There is no general `capabilities()` contract covering operation ID, payload schema, stability, or freshness semantics. There is also no public bulk operation that returns every relevant expression/declaration type from one typed snapshot. The existing closest operations—per-position `hover`, `semanticdbTextDocument`, symbol `info`, and inlay hints—do not expose the complete typed tree or a complete per-span type map ([`PresentationCompiler.java:76-117`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/mtags-interfaces/src/main/java/scala/meta/pc/PresentationCompiler.java#L76-L117), [`PresentationCompiler.java:194-240`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/mtags-interfaces/src/main/java/scala/meta/pc/PresentationCompiler.java#L194-L240)).

More exactly, the missing Scalameta operation is: **type one source file once and return a version-correlated collection of semantic records for all relevant source spans**. Each record must be able to carry at least the source span, syntactic/semantic role, rendered inferred and/or declared type, stable symbol identifier where one exists, and flags needed to distinguish source declarations from synthetic/derived results. The response must distinguish unsupported, cancelled, failed, and successful-empty, and echo the request's URI/version or generation so the host can reject stale results. It must not return dotc trees, contexts, symbols, or types.

A plausible additive `mtags-interfaces` shape is a concrete default `capabilities()` method plus a concrete default generic query method. The capability descriptor and query envelope should be Java 8 classes added once and then kept binary compatible. A generic envelope—stable operation ID, payload-schema version, byte/string payload, and explicit status—lets later Scala nightlies advertise experiments without requiring a new Java DTO class at the classloader boundary for every experiment. Conceptually:

```java
public List<PcCapability> capabilities() {
  return Collections.emptyList();
}

public CompletableFuture<PcQueryResult> query(PcQuery request) {
  return CompletableFuture.completedFuture(PcQueryResult.unsupported());
}
```

The first standardized operation could be `scala.meta.pc.semantic-snapshot` schema 1. Its request would carry `VirtualFileParams` plus the host document generation; its payload would encode the span/type/symbol records above. Concrete defaults preserve the existing “new host, old provider” compatibility direction. Establishing the generic envelope up front also minimizes “old host, newer provider” linkage hazards: future capabilities reuse the old boundary classes and opaque payload instead of adding new types to provider method signatures. This proposal belongs upstream in Scalameta's published interface and Scala 3's PC implementation, not in a Metallurgy-only API.

The new `RawPresentationCompiler` on current Scala 3 removes asynchronous/synchronization policy from the implementation boundary, but it is still a fixed Java API rather than a generic capability protocol. Its documentation explicitly requires the consumer to serialize access ([`RawPresentationCompiler.java:23-31`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/mtags-interfaces/src/main/java/scala/meta/pc/RawPresentationCompiler.java#L23-L31)); current Scala 3 implements it with direct `InteractiveDriver` access ([`RawScalaPresentationCompiler.scala:31-50`](https://github.com/scala/scala3/blob/7c8ee3cb7e1e69f46cdad16ff88b2076d916e58a/presentation-compiler/src/main/dotty/tools/pc/RawScalaPresentationCompiler.scala#L31-L50)). It is useful evidence that synchronization can live in the host, but not a complete answer to feature discovery.

## 6. Version-specific rules and shims Metals still carries

Metals is pragmatic, not version-agnostic in the absolute sense:

- The standalone Scala 3 PC path begins at a hardcoded `3.3.4-RC1` ([`MtagsResolver.scala:109-139`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/MtagsResolver.scala#L109-L139)).
- Removed older Scala releases map to the last compatible Metals release ([`MtagsResolver.scala:46-107`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/MtagsResolver.scala#L46-L107)).
- Scala 3 best-effort handling is gated by a parsed minimum 3.5 minor version ([`ScalaTarget.scala:126-138`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/ScalaTarget.scala#L126-L138)).
- Some server behavior still uses version checks, such as explain diagnostics, and its parser dialect recognizes a fixed set of experimental-language flags ([`ScalaTarget.scala:35-64`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/ScalaTarget.scala#L35-L64), [`ScalaTarget.scala:166-170`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/ScalaTarget.scala#L166-L170)).
- Compiler plugins are filtered through a hardcoded semantic-plugin allowlist ([`CompilerPlugins.scala:13-58`](https://github.com/scalameta/metals/blob/c7402f4b05aada79b2c84959d551cd6a658ebc1a/metals/src/main/scala/scala/meta/internal/metals/CompilerPlugins.scala#L13-L58)).

These rules are useful warnings for Metallurgy: exact artifact resolution and an isolated public API remove most binary coupling, but host-side assumptions can quietly reintroduce a version matrix.

## 7. What Metallurgy can reuse

### Reuse directly

1. **Exact-version artifact selection.** Obtain the module's Scala version from public project/build metadata and resolve `org.scala-lang:scala3-presentation-compiler_3:<exact version>` through public Coursier APIs. Attempt resolution/provider discovery based on capability; do not first classify the version with a support threshold. Compiler and plugin version strings should be diagnostic metadata, not compatibility allowlists.
2. **The published Scalameta Java boundary.** Use `mtags-interfaces` rather than inventing a Metallurgy compiler ABI. Keep all cross-loader inputs and outputs in JDK types or stable Scalameta DTOs. Do not expose `ScType`, PSI implementation objects, Scala collections, dotc contexts, trees, types, or symbols across the loader boundary.
3. **Per-version child classloaders.** Share only the SPI/DTO packages and required platform DTOs with the parent. Load the exact PC/compiler dependency graph in the child. Cache by resolved artifact identity, not by an IntelliJ Scala-plugin implementation fingerprint.
4. **Factory then target instance.** Discover a provider, configure host services, then create a target/session instance with classpath, compiler options, and lifecycle. Carry `(file URI, document version/generation)` in every snapshot operation.
5. **Direct dotc usage stays compiler-side.** Implement the new bulk semantic operation in the exact-version Scala 3 PC artifact behind the published Scalameta interface. Do not reproduce typed-tree traversal through reflection in the IntelliJ plugin.

### Improve rather than copy

1. **Require real provider registration.** Add a service descriptor/provider for the Scala 3 PC and fail cleanly if no compatible provider is present. Do not fall back to `dotty.tools.pc.ScalaPresentationCompiler` by name and do not call private constructors reflectively.
2. **Negotiate semantic capabilities.** Add a concrete, backward-compatible `capabilities()` method to `PresentationCompiler`. Capability IDs should be stable and namespaced; descriptors should state payload schema version, stability (`stable`/`experimental`), threading/lifecycle requirements, and whether the operation promises document-version atomicity. Unknown capabilities must be ignored.
3. **Make unsupported distinct from empty.** An operation response needs an explicit unsupported status. Empty type results are valid semantic answers and must not double as feature detection.
4. **Put the bulk snapshot in the public seam.** Metallurgy needs a capability such as `scala3.semantic-snapshot` whose operation performs one retypecheck and returns classloader-neutral span/type/symbol/declaration records. It should expose compiler renderings and stable symbol identifiers, not raw typed trees.
5. **Make experiments additive.** A compiler nightly can advertise a new namespaced experimental capability without changing version tables. Metallurgy may log or expose unknown descriptors immediately; consuming a new payload still requires a handler for its declared schema. Forward-compatible discovery cannot make an unknown data model meaningful by itself.
6. **Resolve through public infrastructure only.** Replace any use of IntelliJ Scala plugin dependency-manager internals with Coursier or another public resolver. The bundled Scala plugin should provide a public backend extension/dispatcher at semantic roots; bytecode instrumentation of its private PSI implementations is not made safe by making the instrumentation targets discoverable.

## 8. Consequence for the IntelliJ replacement design

Metals answers “how do we couple to arbitrary Scala compilers?” but not “how do we replace IntelliJ Scala's internal type engine?” The former can be solved with exact artifacts, a stable Java SPI, and isolation. The latter requires a public IntelliJ Scala-plugin seam that routes semantic operations to an external backend. No compiler-side discovery protocol can prevent breakage if Metallurgy must still overwrite private `ScExpression`, binding, resolve, or cache implementations.

The least-hack architecture therefore has two public boundaries:

1. **IntelliJ Scala plugin ↔ external compiler backend:** a public extension point/service covering all semantic roots, gated by the module before provider/session work.
2. **Metallurgy host ↔ exact Scala 3 PC:** a classloader-neutral provider SPI discovered by `ServiceLoader`, with additive capability negotiation and a bulk semantic-snapshot operation.

Until those seams exist upstream, a private shim may demonstrate feasibility but cannot satisfy the requirement to support stable, EAP/nightly Scala plugins and future minor versions without assumptions. Capability discovery should replace private implementation discovery, not merely choose which private method to patch.

## 9. Recommended acceptance tests for the protocol subissue

- Load two different Scala 3 PC versions in one IDE process and prove that no Scala/dotc classes cross their loader boundaries.
- Discover providers exclusively through their public service registration; a missing provider returns a typed unavailable result without class-name fallback.
- Run a host built against a newer SPI with an older provider and an older host fixture with a newer provider; optional capabilities degrade explicitly.
- Resolve and load exact stable, RC, and nightly coordinates from configured repositories without a version allowlist.
- Pass an unknown experimental capability through discovery without failing provider startup; ignore it safely when no handler is installed.
- Distinguish unsupported, supported-empty, cancelled, stale-generation, and failed operation outcomes.
- Demonstrate a one-retypecheck bulk semantic snapshot committed only for the requested `(URI, document version)`.
- Verify that the provider boundary contains no IntelliJ PSI implementations, Scala collections, `ScType`, or dotc objects.
- Verify that module gating happens before artifact resolution, classloader creation, provider discovery, or compiler session startup.
