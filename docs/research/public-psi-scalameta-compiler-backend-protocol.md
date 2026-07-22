# Public PSI–Scalameta compiler-backend protocol

Research date: 2026-07-22

## Decision

Metallurgy's permanent boundary is the existing Scala PSI model on the IntelliJ side and Scalameta's published
`scala.meta.pc` interfaces on the compiler side. The plugin must not discover, fingerprint, reflect over, or rewrite
private implementations in either the bundled Scala plugin or dotc.

The design needs two upstream public seams:

1. a Scala-plugin semantic-backend extension point consulted by all PSI semantic roots; and
2. additive Scalameta capabilities for provider discovery and a one-pass, whole-file semantic snapshot.

Compiler and Scala-plugin version strings may select exact artifacts and appear in diagnostics. They do not select code
paths, compatibility manifests, private class names, or method layouts.

## Evidence

The detailed Metals/Scala 3 source audit is in
[`metals-scala3-pc-coupling.md`](metals-scala3-pc-coupling.md). Its important findings are:

- Metals compiles against the Java-only `mtags-interfaces` boundary and loads exact-version compiler artifacts in
  isolated classloaders. Direct `InteractiveDriver` access stays inside the Scala 3 PC artifact that ships with that
  compiler.
- Metals dynamically resolves `scala3-presentation-compiler_3:<project Scala version>`, including snapshot/nightly
  repositories, but still has a hardcoded minimum version, removed-version table, and reflective implementation-class
  fallback. Those are compatibility compromises, not part of this protocol.
- `PresentationCompiler` evolves optional functionality through concrete methods with neutral defaults.
  `supportedCodeActions()` is the existing string-ID capability precedent.
- Current public operations such as `hover`, `semanticdbTextDocument`, `inlayHints`, and `info` do not provide every
  expression/declaration type from one typed file snapshot.
- The published Scala 3.7.4 PC artifact has no
  `META-INF/services/scala.meta.pc.PresentationCompiler` provider descriptor, so pure provider discovery is not yet
  possible for that artifact.
- The 2026.1.20 Scala plugin exposes several narrow EPs but no general semantic/type-backend dispatcher. No compiler-side
  protocol can make private PSI interception forward-compatible.

True forward compatibility therefore requires upstream cooperation. An old artifact that does not publish a service or
capability cannot be made to reveal it through "discovery"; retroactive support would require one of the prohibited
hardcoded or reflective techniques. Such artifacts must remain load-compatible and fall back to bundled behavior.

## Boundary A: Scala plugin ↔ Metallurgy

Add a public dynamic extension point, provisionally `org.intellij.scala.externalCompilerBackend`. The bundled Scala
plugin owns and calls the dispatcher; Metallurgy implements a provider.

The provider contract must:

- receive public PSI/API types plus a semantic role, never concrete implementation classes;
- be synchronous and cache-only—no artifact resolution, compiler call, waiting, or write action from a PSI getter;
- check module applicability before provider/session lookup;
- return an explicit state: `Current(value)`, `Pending`, `Unsupported`, or `Failed`;
- let the bundled implementation run for inactive modules and for pre-publication operational fallback;
- cover expression exact/widened types, declared/inferred definitions, function/given results, parameter inside/outside
  types, pattern type/expected type, and reference/symbol resolution;
- expose supported cache invalidation rather than requiring reflective calls to `ScalaPsiManager` internals; and
- remain additive so newer Scala-plugin builds may introduce semantic roles that older providers ignore safely.

The Scala plugin should centralize these calls in public PSI traits/dispatchers. Metallurgy must not enumerate concrete
pattern, definition, or reference implementations. Existing narrow EPs remain useful for completion, synthetic classes,
and presentation fallbacks, but are not substitutes for the semantic dispatcher.

## Boundary B: Metallurgy ↔ Scalameta PC

Metallurgy compiles against a current binary-compatible `mtags-interfaces` release. It resolves the exact module
coordinate `org.scala-lang:scala3-presentation-compiler_3:<scalaVersion>` through public Coursier APIs and constructs one
isolated compiler classloader per resolved artifact identity.

The host shares only JDK types, LSP4J DTOs, and `scala.meta.pc` interface/DTO classes with that loader. Scala
collections, IntelliJ PSI, `ScType`, and dotc classes never cross it. The exact-version Scala 3 artifact may use dotc
directly internally.

Provider discovery is exclusively Java `ServiceLoader`. Scala 3 must publish the service descriptor upstream.
Metallurgy must remove its own hardcoded provider resource and must not fall back to an implementation class name.

Artifact resolution is attempt-based:

1. take the module's exact reported compiler coordinate;
2. resolve it from configured release/snapshot/nightly/vendor repositories;
3. discover public providers;
4. negotiate capabilities; and
5. report a typed unavailable reason if any step fails.

There is no semantic-version threshold, nearest-version substitution, Scala-plugin build allowlist, or fingerprint.

## Additive Scalameta capability protocol

Add concrete-default methods to `PresentationCompiler` (and, if retained, the corresponding raw interface):

```java
public List<PcCapability> capabilities() {
  return Collections.emptyList();
}

public CompletableFuture<PcQueryResult> query(PcQuery request) {
  return CompletableFuture.completedFuture(PcQueryResult.unsupported());
}
```

The boundary DTOs are Java 8 classes maintained under the same binary-compatibility policy as `PresentationCompiler`.
A capability descriptor contains:

- a namespaced operation ID;
- supported payload schema versions;
- stability (`stable` or `experimental`);
- lifecycle/threading constraints; and
- semantic guarantees such as file-atomic results and request-generation echoing.

The generic query envelope is intentional. Future compiler nightlies can advertise an experimental operation without
adding a new method signature or cross-loader DTO type. Old hosts ignore unknown descriptors. A host can only consume a
payload after it gains a handler for the advertised schema; discovery alone cannot interpret an unknown data model.

Results distinguish `Unsupported`, `Cancelled`, `Failed`, and `Success`. `Success` may contain zero records, so empty
output is never feature detection.

## First stable capability: semantic snapshot

Standardize `scala.meta.pc.semantic-snapshot`, schema 1. One request carries the usual virtual-file input plus an opaque
host generation. One compiler operation types the file once and returns records containing at least:

- source range;
- semantic role;
- source-compatible rendered type and presentation text where they differ;
- stable symbol identifier and owner when available;
- declaration/synthetic/derived flags; and
- request URI and generation.

The provider performs typed-tree traversal, role selection, deduplication, and rendering inside the exact compiler
artifact. It never returns a typed tree, context, dotc type, or compiler symbol object. Metallurgy maps the records to
current PSI, converts renderings to `ScType` where possible, and commits only if document, module gate, session,
classpath, options, and request generations remain current.

Additional stable capabilities can later cover best-effort artifact provenance, richer member/conformance queries, and
compiler-symbol navigation. Experimental capabilities use their own namespaced IDs without changing version tables.

## Compatibility contract

“Compatible with stable, EAP, and nightly” has two precise meanings:

- **Load compatibility:** missing or unknown public capabilities never break plugin loading or alter inactive/bundled
  behavior.
- **Backend availability:** replacement semantics are available when both public seams and the required capability are
  advertised. The plugin does not claim backend support for functionality an artifact does not expose.

This distinction is unavoidable and prevents “supports every version” from becoming hidden assumptions. Version labels
do not confer capability; providers do.

## Verification matrix

- New host with an old provider: concrete defaults yield `Unsupported`; no linkage error.
- Old host fixture with a new provider: the provider loads and its unknown capabilities are ignored.
- Two exact Scala compiler versions in one IDE process: no Scala/dotc object crosses loaders.
- Stable, RC/EAP, nightly, and unavailable coordinates: the same resolution/discovery algorithm runs for each.
- Missing service metadata: typed unavailable state, no class-name fallback.
- Unknown experimental capability: visible to diagnostics, ignored safely without a handler.
- Supported-empty vs unsupported vs cancelled vs failed: all remain distinct.
- Semantic snapshot: exactly one typed pass, complete role records, version-guarded commit.
- Inactive module: no resolution, classloader, provider discovery, session, snapshot, state allocation, or invalidation.
- API audit: no implementation-class constants, private reflection, bytecode instrumentation, classpath implementation
  scanning, or version compatibility tables.

## Migration consequence

The successful private shim/direct-driver proof of concept remains evidence that the semantic mapping works. It is not
the production compatibility layer. Production work pauses at the public boundaries: upstream the two seams, migrate
the snapshot and PSI dispatcher to them, then delete the transformer, implementation scanner, private dependency
resolver, hardcoded provider descriptor, and reflected dotc driver.
