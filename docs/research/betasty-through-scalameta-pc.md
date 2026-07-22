# BETASTY through the Scalameta presentation-compiler boundary

Status: researched against Metallurgy's current source, Scala 3.7.4, and the
`mtags-interfaces` version used to build Scala 3.7.4.

## Answer

Yes: Metallurgy currently supplies both `-Ybest-effort` and
`-Ywith-best-effort-tasty` to active compiler configurations. It does not decode
`.betasty` into symbols or trees itself. Metallurgy finds and fingerprints the
artifacts, exposes their directory as a classpath root, and passes the classpath
and compiler options onward. The exact-version Scala 3 compiler then recognizes
and unpickles `.betasty`.

The published Scalameta `PresentationCompiler` API has no dedicated best-effort
TASTy operation. None is required for ordinary consumption: its public
`newInstance(buildTargetIdentifier, classpath, options)` boundary already
provides everything Scala 3 needs to load `.betasty` while answering completion,
hover, SemanticDB, or future semantic-snapshot requests. A future API may expose
best-effort provenance or capability metadata, but Metallurgy should not parse
the compiler's artifact format.

## The Metals demo and release lineage

Jędrzej Rochala's demo isolates the exact user-visible failure this feature
addresses. The sample has two build modules, with module B depending on module
A. Module A exposes an API object containing valid declarations alongside two
type errors. Before best-effort compilation, module B gets neither useful
completion for that API nor downstream diagnostics because module A produced no
typed artifacts ([talk, 15:39–16:52](https://www.youtube.com/watch?v=SlPDmwhxeok&t=939s)).

The demo then switches the build to the latest Scala 3.5 release candidate and
reimports it. Without changing editors or adding an editor integration, module
B gains completion for module A's valid members, keeps an `Any`-like fallback
for the broken declaration, reports a genuinely missing member, and observes
module A's repaired types after recompilation
([talk, 16:52–18:54](https://www.youtube.com/watch?v=SlPDmwhxeok&t=1012s)).
The screenshots and narration therefore establish that BETASTY is a
cross-build-target artifact and invalidation feature. Neovim is incidental;
Metals supplies the behavior through the build server and presentation
compiler.

At recording time Rochala was running a Metals PR build and said the support was
not merged yet ([talk, 20:18–20:42](https://www.youtube.com/watch?v=SlPDmwhxeok&t=1218s)).
The implementation landed as
[Metals #5219](https://github.com/scalameta/metals/pull/5219), merged on 18 July
2024, and shipped in
[Metals 1.3.4](https://scalameta.org/metals/blog/2024/07/24/thallium). Those
release notes describe the same pipeline: Scala 3 writes a TASTy-like artifact
for partially broken trees, and the presentation compiler later consumes it.
Metals 1.3.5 temporarily disabled the feature by default after early Scala
3.5.0 RC users saw false errors
([1.3.5 notes](https://scalameta.org/metals/blog/2024/08/01/thallium)); current
Metals exposes it as the `enable-best-effort` setting rather than removing the
integration.

Current Metals remains the definitive integration reference. Its
`CompilerConfiguration` removes `-Ybest-effort` from PC options, retains exactly
one `-Ywith-best-effort-tasty`, and appends the current and direct-dependency
best-effort directories to the PC classpath
([`CompilerConfiguration.scala:193-229`](https://github.com/scalameta/metals/blob/main/metals/src/main/scala/scala/meta/internal/metals/CompilerConfiguration.scala#L193-L229)).
Its BSP initialization asks a capable build server to enable artifact production
([`BuildServerConnection.scala:739-784`](https://github.com/scalameta/metals/blob/main/metals/src/main/scala/scala/meta/internal/metals/BuildServerConnection.scala#L739-L784)).
PR #5219 also restarts presentation compilers for dependent targets after a
best-effort compile report. These are the three behaviors IntelliJ must match:
producer negotiation, explicit best-effort roots in downstream PC classpaths,
and dependent-session retirement.

## Current Metallurgy flow

`ScalacFlagsService.RequiredFlags` contains both flags
([`ScalacFlagsService.scala:42-45`](../../src/main/scala/com/hmemcpy/metallurgy/build/ScalacFlagsService.scala#L42-L45)).
`enableFor` adds them to the bundled Scala compiler profile
([`ScalacFlagsService.scala:16-22`](../../src/main/scala/com/hmemcpy/metallurgy/build/ScalacFlagsService.scala#L16-L22)).
That is the **producer path**: compiler-based highlighting or another full
compiler run receives `-Ybest-effort` and can leave usable artifacts even when
an upstream module has errors.

When creating a presentation-compiler session, `PcSessionManager` first applies
those settings, reads the resulting compiler options, builds the module
classpath, and passes both into `PcSession.create`
([`PcSessionManager.scala:283-317`](../../src/main/scala/com/hmemcpy/metallurgy/pc/PcSessionManager.scala#L283-L317)).
For every directory classpath entry containing `META-INF/best-effort`, it adds
that nested directory as another classpath root
([`PcSessionManager.scala:321-333`](../../src/main/scala/com/hmemcpy/metallurgy/pc/PcSessionManager.scala#L321-L333),
[`PcSessionManager.scala:366-376`](../../src/main/scala/com/hmemcpy/metallurgy/pc/PcSessionManager.scala#L366-L376)).
It also hashes `.betasty` contents so replacing an artifact invalidates a stale
session even when filesystem metadata is unchanged
([`PcSessionManager.scala:378-409`](../../src/main/scala/com/hmemcpy/metallurgy/pc/PcSessionManager.scala#L378-L409)).

There are currently two semantic consumers:

- Completion uses the public Scalameta interface. `PcSession` calls
  `PresentationCompiler.newInstance` with the classpath and all compiler options
  ([`PcSession.scala:262-273`](../../src/main/scala/com/hmemcpy/metallurgy/pc/PcSession.scala#L262-L273)).
- The typed-tree proof of concept constructs `InteractiveDriver` reflectively
  with the same classpath and options
  ([`PcSession.scala:207-217`](../../src/main/scala/com/hmemcpy/metallurgy/pc/PcSession.scala#L207-L217),
  [`PcInlineTypeDriver.scala:22-31`](../../src/main/scala/com/hmemcpy/metallurgy/pc/PcInlineTypeDriver.scala#L22-L31),
  [`PcInlineTypeDriver.scala:150-157`](../../src/main/scala/com/hmemcpy/metallurgy/pc/PcInlineTypeDriver.scala#L150-L157)).
  This path is migration evidence, not the permanent interface.

The integration test proves the complete behavior: a broken upstream module
emits `Person.betasty`; both the Scalameta completion path and the direct-driver
proof of concept resolve `Person`; and the control compiler run fails unless
both the best-effort classpath root and consumption flag are present
([`BetastyCrossModuleTest.scala:76-139`](../../src/test/scala/com/hmemcpy/metallurgy/pc/BetastyCrossModuleTest.scala#L76-L139)).

## What Scala 3.7.4 does

Scala 3 defines separate production and consumption settings:
`-Ybest-effort` emits best-effort TASTy during the pickler phase, while
`-Ywith-best-effort-tasty` permits reading it
([`ScalaSettings.scala:448-449`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/config/ScalaSettings.scala#L448-L449)).
The full compiler includes `Pickler` after its frontend phases
([`Compiler.scala:29-56`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/Compiler.scala#L29-L56));
when best-effort mode is enabled, `Pickler` writes into
`META-INF/best-effort`
([`Pickler.scala:414-420`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/transform/Pickler.scala#L414-L420)),
and `BestEffortTastyWriter` assigns the `.betasty` extension
([`BestEffortTastyWriter.scala:11-42`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/core/tasty/BestEffortTastyWriter.scala#L11-L42)).

The interactive compiler only runs parser, typer, root-tree setup, and comment
processing; it has no pickler phase
([`InteractiveCompiler.scala:10-20`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/interactive/InteractiveCompiler.scala#L10-L20)).
Therefore `-Ybest-effort` does not make the presentation compiler produce
`.betasty`. Emission must remain a full-compiler/build-loop responsibility.

For consumption, Scala 3's symbol loader detects the `.betasty` extension,
checks `-Ywith-best-effort-tasty`, unpickles the file, and records that
best-effort TASTy was used
([`SymbolLoaders.scala:474-502`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/core/SymbolLoaders.scala#L474-L502)).
This is why Metallurgy should let the exact compiler artifact own the binary
format instead of introducing a plugin-side reader.

## Scalameta interface boundary

Scala 3.7.4 is built against `mtags-interfaces` 1.6.2
([`Build.scala:2590-2600`](https://github.com/scala/scala3/blob/3.7.4/project/Build.scala#L2590-L2600)).
That release's `PresentationCompiler` exposes generic source operations plus
`newInstance(..., classpath, options)`, but contains no best-effort or BETASTY
method
([`PresentationCompiler.java:28-240`](https://github.com/scalameta/metals/blob/v1.6.2/mtags-interfaces/src/main/java/scala/meta/pc/PresentationCompiler.java#L28-L240),
[`PresentationCompiler.java:318-335`](https://github.com/scalameta/metals/blob/v1.6.2/mtags-interfaces/src/main/java/scala/meta/pc/PresentationCompiler.java#L318-L335)).
Scala 3's implementation preserves those supplied options and appends the
classpath when constructing its `InteractiveDriver` settings
([`ScalaPresentationCompiler.scala:128-141`](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/ScalaPresentationCompiler.scala#L128-L141),
[`ScalaPresentationCompiler.scala:481-490`](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/ScalaPresentationCompiler.scala#L481-L490)).
Consequently `-Ywith-best-effort-tasty` flows through the public API without a
format-specific operation.

`PresentationCompiler.getTasty` is not such an operation. It is documented as a
pretty-printer for `.scala` or `.tasty`
([`PresentationCompiler.java:145-148`](https://github.com/scalameta/metals/blob/v1.6.2/mtags-interfaces/src/main/java/scala/meta/pc/PresentationCompiler.java#L145-L148)),
and Scala 3.7.4's implementation explicitly constructs `TastyPrinter` with
`isBestEffortTasty = false`
([`TastyUtils.scala:11-24`](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/TastyUtils.scala#L11-L24)).
It neither supplies symbols to compiler queries nor provides a supported
`.betasty` decoding boundary.

## Architectural consequences

The permanent PSI-to-Scalameta design preserves two loops:

1. A public Scala-plugin compiler-option contributor must add
   `-Ybest-effort` and `-Ywith-best-effort-tasty` to the full compiler/CBH build
   for active modules. This replaces the current private reflective settings
   mutation; the Scalameta PC cannot replace the artifact-producing build.
2. Metallurgy continues discovering `META-INF/best-effort` roots and tracking
   their generation, then initializes the exact-version public Scalameta PC with
   those roots and `-Ywith-best-effort-tasty`. The requested bulk semantic
   snapshot should be another public PC operation over the compiler's already
   loaded symbols, not a `.betasty` parser.

A future Scalameta capability can usefully report whether best-effort consumption
is supported and attach provenance such as `ordinary-tasty`, `best-effort-tasty`,
or `source` to semantic results. That is discovery and metadata, not a new binary
reader. Until such a capability exists, passing the option to an exact compiler
that accepts it is the supported mechanism; unsupported-option failure should
disable the feature cleanly rather than select behavior from a compiler-version
table.
