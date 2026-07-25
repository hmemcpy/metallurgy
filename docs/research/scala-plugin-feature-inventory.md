# Scala plugin feature inheritance inventory

Status: implementation input for [the Scala 3 compiler backend design](../scala3-compiler-backend.md). The generated
catalog is [scala-plugin-feature-inventory-source.json](scala-plugin-feature-inventory-source.json).

## Conclusion

Most user-facing features should inherit the Scala 3 compiler backend through the Scala PSI type and resolve seams. That
is the default implementation strategy: populate the central backend correctly, preserve PSI identities, and test the
existing feature without adding an adapter. A feature-specific adapter is justified only by a source-traced bypass or a
red parity test.

The important exceptions are not ordinary consumers. They are semantic producers or alternate models: declared and
inferred definition roots, compiler-only symbols, Java light PSI, UAST conversion, TASTy/stub indices, debugger code
fragments, project import, and worksheet/REPL editors. These have explicit owning issues below.

## Source-derived baseline

The catalog was generated from intellij-scala revision `8dd22d153b65c847f4ced8917dd7e02b83561e5d`. The root
descriptor declares 26 shipped content modules in `pluginXml/resources/META-INF/plugin.xml:47-78` and includes the core
descriptors at lines 82-93. Across 37 source descriptors, the catalog contains:

| Registration kind | Count |
|---|---:|
| content modules | 26 |
| module dependencies | 23 |
| included descriptors | 10 |
| extension-point declarations | 38 |
| extension registrations | 1,042 |
| actions | 62 |
| action groups | 8 |
| production registry call sites | 35 |
| production experiment call sites | 3 |

The content modules cover BSP, compiler integration, debugger, worksheets, Scala CLI, test support and MUnit, UAST,
structural search, Gradle, Maven, Bazel, IntelliLang, Java decompiler, ML completion, i18n, text analysis, copyright,
DevKit, Markdown, properties, JUnit, and onboarding. Core Scala PSI/code insight, sbt, REPL, structure view, conversion,
Scala Meta, and Scalastyle are included descriptors rather than content modules.

Regenerate or verify the catalog without building intellij-scala:

```sh
python3 tools/scala_plugin_inventory.py generate ~/git/intellij-scala \
  --output docs/research/scala-plugin-feature-inventory-source.json
python3 tools/scala_plugin_inventory.py check ~/git/intellij-scala \
  docs/research/scala-plugin-feature-inventory-source.json
```

`check` compares the source revision, every descriptor registration, and production Scala/Java/Kotlin calls through
`Registry`, `RegistryManager`, and `Experiments`. A new module, EP, extension, action, registry key, experimental feature,
or source-level gate call makes the check fail. Non-literal arguments are retained as `expression:...` entries so a
renamed constant or newly indirect lookup cannot disappear from review. Stable, EAP, and nightly validation uses the
same command against each checkout/artifact source tree and keeps one catalog per graduation target when their
registrations differ. The catalog is discovery data, never a compatibility switch.

## Classification

- **Direct consumer:** calls a type/resolve root replaced by the backend. It should inherit semantics.
- **Indirect consumer:** calls another PSI service that ultimately reaches those roots. It should inherit semantics,
  but needs an end-to-end proof.
- **Model producer:** creates PSI, stubs, indices, project/classpath data, or compiler artifacts consumed by the backend.
- **Artifact producer:** compiles or decompiles files but does not own editor semantic queries.
- **Execution-only:** launches or controls a process after discovery/configuration.
- **Syntactic/unaffected:** depends on tokens, AST shape, text, or code style rather than semantic types.
- **Deferred:** deliberately outside the current semantic milestone while its existing execution path remains intact.

## Editing and code insight

| Surface | Classification and inheritance decision | Evidence and required proof |
|---|---|---|
| Basic/smart/class-name/completion contributors and ML ranking | Direct/indirect consumer. Existing contributors remain; PC completion merges at the contributor boundary, while expected types and resolve inherit the backend. | 32 contributors are registered from `scala-plugin-common.xml:269-337`; smart completion compares resolved PSI at `ScalaSmartCompletionContributor.scala:644`. Existing PC completion tests plus active/inactive ranking and insertion tests belong to #58. |
| Parameter information/signature help | Indirect consumer. Keep the three platform handlers and repair only a demonstrated call-resolution bypass. | Registrations are `scala-plugin-common.xml:509-511`; function parameter info calls `resolve()` at `ScalaFunctionParameterInfoHandler.scala:595`. Add overload, contextual/given, extension, named-tuple, pending and inactive cases to #58. |
| Hover and Quick Documentation | Direct/indirect consumer. Rendered declaration types and resolved targets inherit definition/type/symbol roots. Mouse hover and explicit Quick Documentation are distinct entry points. | `ScalaDocumentationProvider` is registered at `scala-plugin-common.xml:398`; documentation links use multi-resolve in `ScalaDocUtil.scala:46-49`. Exact hover paths remain #58 acceptance. |
| Go to declaration/type/super and navigation | Direct resolve consumer. Source symbols should just work; compiler-only symbols need stable synthetic/fallback targets. | `ScalaGoToDeclarationHandler` is registered at `scala-plugin-common.xml:397`; type declaration and goto-super registrations are at lines 399 and 395. Source and compiler-only cases route to #56/#58. |
| Reference resolve and binding | Central semantic root, not merely a consumer. Expression references can inherit compiler types; symbol identity and declarations require the symbol bridge. | `ScStableCodeReferenceImpl` reads `CompilerType` at lines 479-485. Compiler-only declarations, exports, derives, extension members, and stable identity are #56. |
| Semantic highlighting and annotators | Mixed. CBH remains diagnostics owner; semantic colors/reference counting inherit resolve. Do not create a second diagnostics owner. | Scala annotators are registered at `scala-plugin-common.xml:79-80`; rainbow/reference visitors are in `codeInsight.xml:9-10`; compiler integration is a separate module. Representative semantic annotations belong to #58. |
| Inspections and quick fixes | Mixed direct/indirect consumers. The 183 inspections split into syntactic inspections (invariant) and type/resolve inspections (inherited). No per-inspection adapter without a failure. | Registrations span `scala-plugin-common.xml:941-2025` plus integration descriptors. A stratified suite—conformance, implicit/given, override, collection, DFA, deprecation, unused and syntax-only—belongs to #58. |
| Intentions | Mostly syntactic, with semantic preconditions inherited through type/resolve. | 69 intention registrations are cataloged. Test representative type-aware and syntax-only actions; preserve action availability in inactive modules. Route semantic failures to #58. |
| Postfix templates, live templates, editor handlers and smart keys | Syntactic/unaffected unless a template calls type/resolve to decide applicability. | The catalog contains 24 live-template macros plus postfix, typed, enter, backspace, join-lines and selection handlers. Prove invariance with representative invocation tests; no backend adapter is planned. |
| Import optimizer and auto-import | Indirect resolve/search consumer. Keep the optimizer; compiler-only symbols may require #56 search identities. | `ScalaImportOptimizer` is registered at `scala-plugin-common.xml:539`; Scala import helper/used-provider EPs are declared at lines 4-31. End-to-end import insertion/removal belongs to #58. |
| Language injection and interpolators | Mixed. Host detection is syntactic; interpolator result types and reference resolution are semantic consumers. | `ScalaLanguageInjector` is registered in `scalaCommunity.intelliLang.xml:14`; `interpolatedStringMacroTypeProvider` is declared in `scala-plugin-common.xml:16`. Add injected-language invariance plus custom-interpolator type tests to #58. |
| Inline hints, X-Ray and implicit views | Direct type/resolve consumers. Existing hints should inherit central snapshots; Metallurgy keeps only the pass needed to publish/version the backend. | Inlay settings are registered in `codeInsight.xml:30`; current bulk population/inlay tests cover freshness. User-facing bundled hint/X-Ray cases belong to #58/#59. |
| Override/implement and code generation | Direct member/type/resolve consumers. Source members should inherit; compiler-only members depend on #56 synthetic PSI. | Override and implement handlers are registered at `scala-plugin-common.xml:361-362`; delegate-method and toString generation are also cataloged. Characterization belongs to #58 after #56. |
| Refactorings | High-risk indirect consumers of types, resolve, usages and stable PSI identity. Keep bundled implementations and disable only unsafe compiler-only cases with an explanation. | Rename, move, safe-delete, change-signature, extract/introduce, inline and suggested-refactoring registrations are cataloged in `scala-plugin-common.xml`. Required characterization remains #58. |

## Search and alternate models

| Surface | Classification and inheritance decision | Evidence and required proof |
|---|---|---|
| Find usages and reference searches | Indirect symbol consumer. Source-backed PSI should inherit; compiler-only symbols require stable identities and retirement. | `ScalaFindUsagesHandlerFactory` is registered at `scala-plugin-common.xml:503`; 13 reference-search executors and Scala external search EPs are cataloged. Route to #56/#58. |
| Compiler indices and stub indices | Model producer. Keep the 34 bundled indices for source/TASTy PSI. Add a PC-native index only for symbols that cannot be represented in those models. | Stub-index registrations occupy `scala-plugin-common.xml:554-576`, including givens, extensions, exports and top-level members. Index parity belongs to #56; stale-generation behavior belongs to #57. |
| Call/type/method hierarchies | Indirect resolve/index consumers. They should inherit source symbols; compiler-only edges depend on #56. | Providers are registered at `scala-plugin-common.xml:523-525`. Add source and compiler-only hierarchy tests to #58. |
| Structure and project views | Primarily syntactic/stub-driven and therefore unchanged. Only missing compiler-only declarations justify augmentation. | `ScalaStructureViewFactory` is registered in `structure-view.xml:9`; the Scala `structureViewModelProvider` EP is cataloged. #58 explicitly keeps this source/stub-driven. |
| Java PSI interoperability and light classes | Alternate semantic model. Scala-to-Java signatures, synthetic classes and Java resolve do not automatically follow `ScType`; they need explicit parity. | Three `java.elementFinder` registrations occur at `scala-plugin-common.xml:519`, including synthetic and Scala 3 main classes. Source signatures and compiler-only members route to #56/#58. |
| UAST | Direct Scala UAST conversion inherits backend expression/declaration types, method receiver/return reads, source and compiler-only resolve, conversion, evaluation, and cache freshness. The platform language plugin is globally unavailable while CBH disables built-in highlighting. | `ScalaUastLanguagePlugin` is registered in `scalaCommunity.uast.xml:9`; `scala.uast.enabled` is declared at line 14, but `scala/uast/.../package.scala:21-25` rejects all open projects under the retained CBH state. `CompilerBackendUastTest` is the direct-adapter contract; platform UAST inspections are an explicit fallback/gap rather than justification for a leaking global plugin. |
| Structural search | Mostly syntactic/PSI-pattern consumer. Semantic constraints would inherit resolve/type, but the bundled profile explicitly rejects projects with built-in highlighting disabled. | `ScalaStructuralSearchProfile` is registered in `scalaCommunity.structural-search.xml:8`; its guard is at `ScalaStructuralSearchProfile.scala:51-55`. Characterize the CBH fallback and add syntax-only/typed invariants only if the bundled profile is reachable; no global adapter is planned. |
| TASTy/SIG decompiler and compiled PSI | Model/artifact producer. Keep the bundled TASTy reader; PC/BETASTY is a separate exact-artifact input, not a decompiler replacement. | `TastyDecompiler` and `SigFileDecompiler` are registered at `scala-plugin-common.xml:653`. Compiled-source resolve parity routes to #56/#57. |
| External annotations and qualified names | Indirect PSI consumer or syntactic metadata. Preserve existing behavior. | Annotation support, annotated search, qualified-name and external-text providers are cataloged. Test a Scala/Java annotation round trip in #58. |

## Execution surfaces

| Surface | Classification and inheritance decision | Evidence and required proof |
|---|---|---|
| Application discovery, gutter and run configuration | Source-backed `main` and Scala 3 `@main` configuration production remains bundled and creates no backend session. Process launch is execution-only. | `ScalaApplicationConfigurationProducer` is at `scala-plugin-common.xml:547`; main gutter at line 453; Scala 3 synthetic main finder at line 519. `BundledExecutionDiscoveryTest` proves both source shapes and module selection. Execution, rerun and source navigation remain in #70. |
| ScalaTest, MUnit, Specs2 and uTest | All four bundled framework finders and configuration producers discover indexed source suites unchanged. Active/inactive gutter signatures are identical and inactive discovery creates no backend session. | Four `testFramework` registrations are in `scalaCommunity.testing-support.xml:23` and `scalaCommunity.testing-support.munit.xml:9`; producers are at lines 31 and 13. `BundledExecutionDiscoveryTest` is the discovery/configuration/gutter contract. Individual test-name mapping, execution, failure navigation and rerun-failed coverage remain in #70. |
| Debugger, evaluator and smart step | Explicit bundled fallback. Debugger fragments are unversioned, synthetic PSI without a safe document-generation commit key, so Metallurgy does not create a backend session for them. The bundled fragment still resolves and types ordinary expressions; runtime evaluator, transport, position manager, renderers and smart-step remain unchanged. Type-aware breakpoint variants are disabled under CBH by a bundled guard, independently of Metallurgy. | `ScalaCodeFragmentFactory` is registered in `scalaCommunity.debugger.xml:12`; smart-step, position manager, renderer and evaluator-related extensions are cataloged there. `scala/debugger/.../package.scala:14-15` defines the CBH guard. `BundledDebuggerFallbackTest` locks the no-backend fragment fallback and bundled type result. Execution parity remains #70. |
| Worksheets, scratch files, Ammonite, Scala console and REPL | Physical Scala 3 worksheet sources already inherit the versioned backend because `WorksheetFile` extends `ScalaFileImpl`; plain and REPL are execution settings on that same file. Type publication, hover, edit retirement and inactive zero-work are proven. Worksheet/console execution and result transport stay bundled. The interactive console's `LightVirtualFile` and separately rebuilt context PSI are an explicit bundled fallback because they have no source-document generation contract. Scratch worksheets likewise fall back unless IntelliJ supplies stable module/document identity. | `WorksheetFile.scala:9-24` is the shared PSI model; `ScalaLanguageConsole.scala:181-266` rebuilds synthetic context PSI. Worksheet run markers and execution remain registered at `scalaCommunity.worksheet.xml:34-68`, and REPL is included by `plugin.xml:87`. `WorksheetCompilerBackendTest` locks physical-source semantics, edit freshness, inactive zero-work, and the synthetic-console fallback. Remaining execution invariance is owned by #67. |

## Build and project model

| Surface | Classification and inheritance decision | Evidence and required proof |
|---|---|---|
| Native sbt import | Model producer. Keep IntelliJ's loader; normalize its module/version/classpath/options/output into the backend descriptor. | `SbtExternalSystemManager` is registered in `sbt.xml:50`; sbt import services and settings are cataloged. Owned by #65. |
| IntelliJ BSP import | Model producer. Keep IntelliJ's BSP implementation and feed the same normalized descriptor/lifecycle as sbt. | `BspExternalSystemManager` is registered in `scalaCommunity.bsp.xml:40`; BSP module is declared by `plugin.xml:48`. Owned by #65. |
| Maven, Gradle, Bazel, Scala CLI and Mill where supported | Model producers. Consume the resulting IntelliJ module model; add loader-specific tests only when normalization differs. | Dedicated content modules are declared in `plugin.xml:53-75`; external project data services and import hooks are cataloged. Route normalization to #65. |
| JPS, compile server and compiler-based highlighting | Artifact/diagnostic producer. Keep compilation and CBH; backend session lifecycle observes module/document/artifact changes. | Compiler integration is a content module at `plugin.xml:59`; compile-server plugin and compiler tasks are registered in `scala-plugin-common.xml:645-652`. Lifecycle tests span #57/#59. |
| Generated/shared/test sources and compiler plugins | Project/artifact inputs. They affect source ownership, classpath and compiler options, not the semantic API shape. | External project data services, source-set model services and compiler-settings EPs are cataloged. Equivalent descriptors and generation invalidation belong to #65/#57. |
| Scala.js, Scala Native and SemanticDB | Artifact/model variants. Preserve plugin/toolchain setup; pass their exact classpath/options to the PC. | Their behavior is represented through compiler settings, libraries, import data and compile tasks rather than a replacement type engine. Add representative imported-project fixtures to #65/#66. |
| Package/dependency resolution | Artifact producer. Reuse public resolver infrastructure; resolution failure is an explicit unavailable backend and never a version switch. | Package Search, dependency manager, sbt/Scala CLI completion and repository providers are cataloged. Exact-PC resolution is already covered by #64; project variants belong to #65. |

## Peripheral integrations

Formatting/scalafmt, rearrangement, copyright, documentation generation, Grazie/text analysis, spellchecking, i18n,
DevKit, Markdown fences, properties, onboarding, notebooks and other virtual-file languages are syntactic or integration
consumers unless a traced implementation calls Scala type/resolve. Their registrations are present in the generated
catalog. The rule is invariance: keep the existing implementation, run representative active/inactive Scala 3 and
Scala 2 tests, and add an adapter only for a demonstrated semantic bypass.

## Hidden and experimental gates

The descriptors declare 27 registry keys and one experimental feature. The relevant families are:

- UAST: `scala.uast.enabled` (`scalaCommunity.uast.xml:14`).
- semantic engine: `scala.enable.match.type.intrinsics`, type-presentation debugging, highlighting tracing and incremental
  lookaround (`scala-plugin-common.xml:174-176,687-690`).
- CBH/compiler documents: compiler ranges, Java-highlight suppression, in-memory document compilation and compiler
  timeout (`scala-plugin-common.xml:663-681`, `scalaCommunity.compiler-integration.xml:45-52`).
- loaders/resolvers: sbt/BSP in-process and import variants plus filesystem-only dependency resolution (`sbt.xml:172-183`,
  `scalaCommunity.bsp.xml:81`, `scala-plugin-common.xml:669-675`).
- debugger and worksheets: lazy evaluator resolve and REPL error/evaluation modes (`scalaCommunity.debugger.xml:34`,
  `scalaCommunity.worksheet.xml:57-62`).

The generated catalog also contains 35 production registry call sites and three experiment call sites. This closes the
descriptor/source split: literal keys retain their names, while indirect arguments are explicit review items. Unknown
gates must be reported, not silently mapped to a compatibility branch.

## Test strategy

The matrix is tested by semantic boundary, not by creating 1,042 bespoke tests.

1. **Catalog drift:** run the generator against every graduation target. Any registration delta requires classification.
2. **Central inheritance contract:** reuse one exact-type/symbol corpus through expression, declaration, resolve, hover,
   completion, parameter info, representative inspections, hints, hierarchy, usage and refactoring entry points.
3. **Alternate-model contracts:** separately test Java PSI, UAST, debugger fragments, TASTy/compiled PSI and compiler-only
   synthetic targets because they can bypass ordinary Scala PSI.
4. **Model-producer contracts:** import equivalent native-sbt and BSP projects and compare normalized descriptors,
   generations, classpaths, options, source sets and artifacts.
5. **Execution invariance:** for applications and every test framework, separate discovery/navigation assertions from
   actual launch/rerun assertions. The backend must not replace the execution infrastructure.
6. **Ecosystem and graduation:** run the pinned compiler-clean corpus (#66), Metallurgy parity suite, and finally all
   Scala 3-focused intellij-scala tests (#60) under hard timeouts. The final step is the only one that builds that checkout.

Every affected boundary gets current, pending/unavailable, stale-generation, inactive Scala 3, Scala 2 and mixed-project
coverage where that state is observable. Syntactic and execution-only paths instead prove behavior invariance and zero
backend work when inactive.

## Work routing and gaps

- #55 owns type-bearing declarations, functions, parameters, patterns and expected types.
- #56 owns compiler symbols, synthetic/light PSI, navigation identity, search and indices.
- #57 owns BETASTY/artifact freshness; #59 owns the dedicated population pass and performance.
- #58 owns ordinary consumer inheritance: hover, parameter info, inspections, hints, search, hierarchies, Java PSI,
  imports, generation and refactorings.
- #65 owns loader-neutral sbt/BSP and other imported-project descriptors.
- #66 owns ecosystem parity; #67 owns deferred worksheet/REPL semantics; #68 owns UAST and hidden gates.
- #70 owns application/test-framework discovery, debugger behavior, and execution invariance. It was the only
  implementation/testing gap found by this inventory.
- #60 owns the final bounded upstream Scala 3 graduation suite.

No other feature-specific adapter is currently justified. New work is created only when source tracing or a parity test
demonstrates that the feature bypasses the central compiler backend.
