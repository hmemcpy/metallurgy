# Extension Points Inventory (plugin.xml + EPs)

**Scope.** Every plugin XML descriptor under `/Users/hmemcpy/git/intellij-scala` (excluding `target/`), inventoried to give the Metals-PC Scala 3 rewrite a complete checklist of seams to keep, replace, wrap, or delete.

**Files covered (11 included via `xi:include`, plus 17 module descriptors loaded via `<content>`/optional dependencies).**

| File | Purpose |
|---|---|
| `pluginXml/resources/META-INF/plugin.xml` | Top-level descriptor, `<content>` module list, depends |
| `scala/scala-impl/resources/META-INF/scala-plugin-common.xml` | The 2 276-line core of the plugin (PSI, resolve, completion, inspections, refactorings, etc.) |
| `scala/scala-meta-impl/resources/META-INF/scala-meta-impl.xml` | scala.meta bindings |
| `scala/integration/scalastyle/.../scalastyle-integration.xml` | Scalastyle service |
| `scala/integration/packagesearch/.../packagesearch.xml` | Package-search integration (disabled in OSS build) |
| `scala/repl/.../scala-repl.xml` | Scala REPL console actions |
| `scala/conversion/.../conversion.xml` | Java ⇄ Scala paste conversion |
| `scala/structure-view/.../structure-view.xml` | Structure view + navbar |
| `scala/codeInsight/.../codeInsight.xml` | Inlay hints, live templates, rainbow, implicits UI |
| `sbt/sbt-api/.../sbt-api.xml` | sbt EP definitions + module type |
| `sbt/sbt-impl/.../sbt.xml` | sbt import, shell, run configs |
| `scala/compiler-integration/.../scalaCommunity.compiler-integration.xml` | Compile server, compiler-based highlighting, bytecode indices |
| `scala/worksheet/.../scalaCommunity.worksheet.xml` | Worksheets, Ammonite |
| `scala/debugger/.../scalaCommunity.debugger.xml` | Scala debugger (renderers, breakpoints, evaluator) |
| `scala/test-integration/testing-support{,munit}/...` | ScalaTest/Specs2/uTest/MUnit run configs |
| `scala/uast/.../scalaCommunity.uast.xml` | UAST plugin |
| `scala/structural-search/...` | Structural search profile |
| `scala-cli/resources/scalaCommunity.scala-cli.xml` | scala-cli BSP setup |
| `bsp-builtin/bsp{,-junit,-terminal}/...` | Built-in BSP support |

---

## 1. Plugin-defined `dynamic="true"` extension points

These are the **cleanest seams** for an alternative Scala 3 implementation: they live in the Scala plugin's own namespace, are declared `dynamic="true"`, and are already used by sub-modules (worksheet, compiler-integration, testing-support, scala-cli, …) to plug behavior in without recompiling the host. A new "Scala 3 Metals" module could register implementations against exactly these EPs without touching the platform or Scala 2 code paths.

| Qualified name | Interface | Defined in | Used by (current impl) | Category | Action for Scala 3 Metals |
|---|---|---|---|---|---|
| `org.intellij.scala.scalaIntentionAvailabilityChecker` | `util.IntentionAvailabilityChecker` | `scala-plugin-common.xml:5` | (no default) | Inspection | **Replace** — route availability through PC diagnostics |
| `org.intellij.scala.dependencyAwareInjectionSettings` | `settings.uiControls.DependencyAwareInjectionSettings` | `scala-plugin-common.xml:6` | (no default) | Editor | Keep |
| `org.intellij.scala.scalaLanguageDerivative` | `finder.ScalaLanguageDerivative` | `scala-plugin-common.xml:7` | (no default; Java finder hook) | Navigation | Keep (Java interop) |
| `org.intellij.scala.memberElementTypesExtension` | `util.MemberElementTypesExtension` | `scala-plugin-common.xml:8` | (no default) | PSI | Keep |
| `org.intellij.scala.scalaElementToRenameContributor` | `lang.refactoring.rename.ScalaElementToRenameContributor` | `scala-plugin-common.xml:9` | (no default) | Refactoring | **Replace** for Scala 3 (PC has symbol info) |
| `org.intellij.scala.scalaSyntheticClassProducer` | `lang.resolve.SyntheticClassProducer` | `scala-plugin-common.xml:10` | (no default) | Resolve / PSI | **Replace** (PC synthetic symbols) |
| `org.intellij.scala.syntheticMemberInjector` | `lang.psi.impl.toplevel.typedef.SyntheticMembersInjector` | `scala-plugin-common.xml:11` | 11 injectors (Case, Enum, Derives, Circe, Monocle, …) lines 33–44 | PSI / Resolve | **Wrap** — keep Scala 2 injectors; let Metals PC provide canonical Scala 3 ones |
| `org.intellij.scala.parameterInfoEnhancer` | `lang.parameterInfo.ScalaParameterInfoEnhancer` | `scala-plugin-common.xml:12` | (no default) | Completion | **Replace** with PC parameter info |
| `org.intellij.scala.scalaDynamicTypeResolver` | `lang.resolve.DynamicTypeReferenceResolver` | `scala-plugin-common.xml:13` | (no default) | Resolve | **Replace** (structural types via PC) |
| `org.intellij.scala.genericTypeNamesProvider` | `lang.refactoring.namesSuggester.genericTypes.GenericTypeNamesProvider` | `scala-plugin-common.xml:14` | 6 providers lines 46–57 | Refactoring | Keep |
| `org.intellij.scala.unresolvedReferenceFixProvider` | `annotator.UnresolvedReferenceFixProvider` | `scala-plugin-common.xml:15` | 3 import-fix providers lines 59–61 | Inspection | **Wrap** — PC-based auto-imports for Scala 3 |
| `org.intellij.scala.interpolatedStringMacroTypeProvider` | `lang.psi.impl.base.InterpolatedStringMacroTypeProvider` | `scala-plugin-common.xml:16` | (no default) | Macro / PSI | Keep |
| `org.intellij.scala.fileDeclarationsContributor` | `lang.psi.api.FileDeclarationsContributor` | `scala-plugin-common.xml:17` | worksheet + scala-cli (2 impls) | PSI | Keep (used by scala-cli scripts) |
| `org.intellij.scala.importUsedProvider` | `editor.importOptimizer.ImportInfoProvider` | `scala-plugin-common.xml:20` | `AmmoniteImportInfoProvider` (worksheet) | Editor | **Replace** for Scala 3 (PC imports) |
| `org.intellij.scala.importOptimizerHelper` | `editor.importOptimizer.ScalaImportOptimizerHelper` | `scala-plugin-common.xml:21` | `AmmoniteImportOptimizerHelper` | Editor | Keep |
| `org.intellij.scala.referenceExtraResolver` | `lang.psi.impl.base.ScStableCodeReferenceExtraResolver` | `scala-plugin-common.xml:22` | `AmmoniteScStableCodeReferenceExtraResolver` | Resolve | **Replace** for Scala 3 (PC resolve) |
| `org.intellij.scala.compilerSettingsProfileProvider` | `project.settings.ScalaCompilerSettingsProfileProvider` | `scala-plugin-common.xml:23` | `WorksheetScalaCompilerSettingsProfileProvider` | Build | Keep |
| `org.intellij.scala.findUsages.externalReferenceSearcher` | `findUsages.ExternalReferenceSearcher` | `scala-plugin-common.xml:25` | `CompilerIndicesReferencesSearch$` | Navigation / Index | **Replace** — Metals PC `findReferences` |
| `org.intellij.scala.findUsages.externalInheritorsSearcher` | `findUsages.ExternalInheritorsSearcher` | `scala-plugin-common.xml:26` | `CompilerIndicesInheritorsSearch$` | Navigation / Index | **Replace** — Metals PC `subclasses`/`superTypes` |
| `org.intellij.scala.findUsages.externalSearchScopeChecker` | `findUsages.ExternalSearchScopeChecker` | `scala-plugin-common.xml:27` | `CompilerIndicesReferencesSearcher$` | Navigation / Index | Wrap |
| `org.intellij.scala.newScalaFileActionExtension` | `actions.NewScalaFileActionExtension` | `scala-plugin-common.xml:29` | `ScalaCliNewScalaFileActionExtension` (scala-cli) | Editor | Keep |
| `org.intellij.scala.structureViewModelProvider` | `structureView.ScalaStructureViewModelProvider` | `structure-view.xml:8` | `TestStructureViewModelProvider` (testing-support) | Navigation | Keep |
| `org.intellij.scala.worksheetHighlightingCompiler` | `compiler.highlighting.WorksheetHighlightingCompiler` | `scalaCommunity.compiler-integration.xml:8` | `WorksheetHighlightingCompilerImpl` | Worksheet | **Replace** — route through PC |
| `org.intellij.scala.buildToolProjectSyncHelper` | `compiler.sync.BuildToolProjectSyncHelper` | `scalaCommunity.compiler-integration.xml:9` | `SbtProjectSyncHelper` | Build | Keep |
| `org.intellij.scala.evaluatorCompileHelper` | `debugger.evaluation.EvaluatorCompileHelper` | `scalaCommunity.debugger.xml:9` | (project service impl) | Debugger | **Replace** — compile via scala3 CLI or PC |
| `org.intellij.scala.testWorkingDirectoryProvider` | `testingSupport.TestWorkingDirectoryProvider` | `scalaCommunity.testing-support.xml:10` | (no default) | Run Config | Keep |
| `org.intellij.sbt.buildModuleUriProvider` | `project.SbtBuildModuleUriProvider` | `sbt-api.xml:3` | sbt + bsp | SBT | Keep |
| `org.intellij.sbt.sbtVersionProvider` | `project.SbtVersionProvider` | `sbt-api.xml:4` | sbt + bsp | SBT | Keep |
| `com.intellij.sbt.configurationDetailsExtractor` | `ModuleBasedConfigurationDetailsExtractor` | `sbt.xml:3` | test framework extractor | SBT / Run Config | Keep |
| `com.intellij.newProjectWizard.scala.buildSystem` | `BuildSystemScalaNewProjectWizard` | `sbt.xml:6` | sbt, scala-cli, IntelliJ | Project | Keep |
| `org.intellij.scala.sbtUnlinkedProjectAwareHelper` | `autolink.SbtUnlinkedProjectAwareHelper` | `sbt.xml:7` | `BspSbtUnlinkedProjectAwareHelper` | SBT / BSP | Keep |
| `org.jetbrains.sbt.buildToolModuleHandler` | `project.BuildToolModuleHandler` | `sbt.xml:8` | `BspModuleHandler` | SBT / BSP | Keep |
| `com.intellij.bspEnvironmentRunnerExtension` | `bsp.project.test.environment.BspEnvironmentRunnerExtension` | `scalaCommunity.bsp.xml:4` | app + junit | BSP / Run Config | Keep |
| `com.intellij.bspResolverNamingExtension` | `importing.BspResolverNamingExtension` | `scalaCommunity.bsp.xml:7` | — | BSP | Keep |
| `com.intellij.bspVcsRootExtension` | `data.BspVcsRootExtension` | `scalaCommunity.bsp.xml:10` | terminal start dir | BSP | Keep |
| `org.intellij.bsp.bspSetupProvider` | `importing.setup.BspSetupProvider` | `scalaCommunity.bsp.xml:13` | `MillSetupProvider`, `ScalaCliConfigSetupProvider` | BSP | **Keep + extend** — Scala 3 / scala-cli already uses it |

**Subtotal: 35 plugin-defined EPs (24 in `org.intellij.scala.*`, 6 in `org.intellij.sbt.*` / `com.intellij.sbt.*`, 5 in `org.intellij.bsp.*` / `com.intellij.bsp*`).** All `dynamic="true"`.

---

## 2. Platform (`com.intellij`) EPs registered for `language="Scala"`

These are the **platform seams** the IntelliJ platform invokes when it needs Scala-specific behavior. Every entry below is a hook where the platform calls into Scala code; each must either continue to work for Scala 2 or be replaced/wrapped for Scala 3.

### 2.1 PSI / Parser / Lexer / File types

| EP (`com.intellij.*`) | Impl | File:line | Cat | Action |
|---|---|---|---|---|
| `fileType` (`Scala`, `.scala;.mill`) | `ScalaFileType` | `scala-plugin-common.xml:169` | PSI | Keep (lexer layer reused) |
| `lang.parserDefinition` `Scala` | `ScalaParserDefinition` | :201 | PSI | Keep Scala 2 |
| `lang.parserDefinition` **`Scala 3`** | `Scala3ParserDefinition` | :202 | PSI | **Replace** — Metals PC doesn't parse; but keep for editor. Decide |
| `lang.parserDefinition` `ScalaDoc` | `ScalaDocParserDefinition` | :203 | Documentation | Keep |
| `lang.parserDefinition` `ScalaDirective` | `ScalaDirectiveParserDefinition` | :204 | PSI | Keep (scala-cli directives) |
| `lang.parserDefinition` `ScalaDocRefLink` | `ScalaDocRefLinkParserDefinition` | :205 | Documentation | Keep |
| `lang.ast.factory` `Scala` | `ScalaASTFactory` | :206 | PSI | Keep |
| `lang.substitutor` `Scala` | `ScalaLanguageSubstitutor` | :207 | PSI | **Critical seam** — picks Scala 2 vs Scala 3 language per file. Branch here. |
| `lang.fileViewProviderFactory` `Scala` | `ScFileViewProviderFactory` | :199 | PSI | Keep |
| `generation.topLevelFactory` `Scala` | `ScalaFactoryProvider` | :108 | PSI | Keep |
| `stubElementTypeHolder` | `ScalaElementType$`, `ScalaParserDefinition$`, `Scala3ParserDefinition$` | :650–652 | Index | Keep Scala 2; **wrap** Scala 3 to expose PC stubs |
| `compilableFileTypesProvider` | `ScalaCompilableFileTypesProvider` | :735 | Build | Keep |
| `psi.classFileDecompiler` | `ScClassFileDecompiler` | :737 | External Library | Keep (Decompiled .class — Scala-agnostic) |
| `fileType` `SIG` + `filetype.decompiler` | `SigFile…` | :747–753 | External Library | Scala 2 only — **delete for Scala 3** (TASTy replaces) |
| `fileType` `TASTy` + `filetype.decompiler` `TASTy` | `TastyFile…` | :757–760 | External Library | **Critical for Scala 3** — decode via TASTy; consider Metals `tasty` query |

**sbt module** (`sbt.xml`):
| `lang.parserDefinition` `sbt` / `sbt Scala 3` | `SbtParserDefinition`, `SbtParserDefinitionScala3` | :35–36 | PSI / SBT | Keep |
| `lang.substitutor` `sbt` | `SbtLanguageSubstitutor` | :37 | SBT | Branch sbt Scala 2 / 3 |
| `fileType` `sbt`, `sbtShell` | `SbtFileType$`, `SbtShellFileType$` | :40–44 | SBT | Keep |
| `lang.fileViewProviderFactory` `sbt` | `SbtFileViewProviderFactory` | :38 | SBT | Keep |

**Worksheet module** (`scalaCommunity.worksheet.xml`):
| `fileType` `Scala Worksheet` | `WorksheetFileType$` | :12 | Worksheet | Keep |
| `lang.parserDefinition` `Scala Worksheet` / `Scala 3 Worksheet` | `WorksheetParserDefinition`, `WorksheetParserDefinition3` | :21–22 | Worksheet | Branch |
| `lang.fileViewProviderFactory` `Scala Worksheet` / `Scala 3 Worksheet` | `WorksheetFileViewProviderFactory` | :24–25 | Worksheet | Branch |
| `lang.substitutor` `Scala Worksheet` | `WorksheetLanguageSubstitutor` | :30 | Worksheet | Branch |

### 2.2 Resolve / Search / Navigation

| EP | Impl | File:line | Cat | Action |
|---|---|---|---|---|
| `psi.referenceContributor` `Scala` | `ScalaReferenceContributor` | :460 | Resolve | **Replace** for Scala 3 — PC resolve |
| `java.elementFinder` (×3) | `SyntheticClassElementFinder`, `ScalaClassFinder`, `Scala3MainMethodSyntheticClassFinder` | :619–621 | Navigation | Keep (Java interop) |
| `java.shortNamesCache` | `ScalaShortNamesCache` | :622 | Navigation | Wrap — use PC `workspaceSymbol` for Scala 3 |
| `definitionsSearch` | `MethodImplementationsSearch` | :520 | Navigation | Keep (overrides already delegate to EP) |
| `overridingMethodsSearch` (×2) | `ScalaOverridingMemberSearcher`, `JavaRawOverridingSearcher` | :521–522 | Navigation | **Replace** — PC `subclasses` |
| `allOverridingMethodsSearch` | `JavaRawAllOverridingSearcher` | :523 | Navigation | Keep |
| `referencesSearch` (×13) | NamingParamsSearcher, ObjectTraitReferenceSearcher, ApplyMethodSearcher, UnapplyMethodSearcher, JavaValsUsagesSearcher, JavaFunctionUsagesSearcher, SetterMethodSearcher, ScalaAliasedImportedElementSearcher, TypeAliasUsagesSearcher, ScalaPackageUsagesSearcher, ApplyUnapplyForBindingSearcher, OperatorAndBacktickedSearcher, SelfInvocationSearcher | :588–600 | Navigation | Keep Scala 2; replace via `externalReferenceSearcher` EP for Scala 3 |
| `methodReferencesSearch` | `NonMemberMethodUsagesSearcher` | :602 | Navigation | Keep |
| `findUsagesHandlerFactory` | `ScalaFindUsagesHandlerFactory` | :603 | Navigation | Keep |
| `customUsageSearcher` | `ExtractorParamsInExtractorPatternSearcher` | :604 | Navigation | Keep |
| `directClassInheritorsSearch` | `ScalaDirectClassInheritorsSearcher` | :490 | Navigation | Replace for Scala 3 |
| `classInheritorsSearch` | `ScalaLocalInheritorsSearcher` | :491 | Navigation | Replace for Scala 3 |
| `annotatedElementsSearch` | `ScalaAnnotatedMembersSearcher` | :587 | Navigation | Replace for Scala 3 |
| `useScopeEnlarger` (×2) | `ScalaSharedSourcesUseScopeEnlarger`, `SbtBuildModuleUseScopeEnlarger` | :469 / sbt.xml:112 | Navigation | Keep |
| `resolveScopeEnlarger` | `ScalaSharedSourcesResolveScopeEnlarger` | :470 | Resolve | Keep |
| `resolveScopeProvider` | `ScalaOutOfSourcesResolveScopeProvider` | worksheet.xml:73 | Resolve | Keep |
| `targetElementEvaluator` `Scala`, `JAVA` | `ScalaTargetElementEvaluator` | :606–607 | Navigation | Keep |
| `gotoClassContributor` | `ScalaGoToClassContributor` | :634 | Navigation | Keep |
| `gotoSymbolContributor` | `ScalaGoToSymbolContributor` | :636 | Navigation | Keep |
| `searchEverywhereResultsEqualityProvider` | `ScalaSearchEverywhereEqualityProvider` | :638 | Navigation | Keep |
| `qualifiedNameProvider` | `ScalaQualifiedNameProvider` | :605 | Navigation | Keep |
| `typeDeclarationProvider` | `ScalaGotoTypeDeclarationProvider` | :550 | Navigation | Keep |
| `gotoDeclarationHandler` (×2 + worksheet) | `ScalaGoToDeclarationHandler`, `AmmoniteGotoHandler`, `WorksheetResNGotoHandler` | :497 / worksheet.xml:48–49 | Navigation | Wrap |
| `codeInsight.gotoSuper` `Scala` | `ScalaGoToSuperActionHandler` | :496 | Navigation | **Replace** — PC super method |
| `findUsagesProvider` `Scala` | `ScalaFindUsagesProvider` | :465 | Navigation | Keep |
| `fileStructuregroupRuleProvider` (×2) | `ScalaDeclarationGroupRuleProvider`, `ScalaDeclarationSecondLevelGroupRuleProvider` | :466–467 | Navigation | Keep |
| `usageTypeProvider` | `ScalaUsageTypeProvider` | :472 | Navigation | Keep |
| `importFilteringRule` | `ScalaImportFilteringRule` | :474 | Navigation | Keep |
| `jvm.declarationSearcher` `Scala` | `ScalaDeclarationSearcher` | :632 | Debugger / Navigation | Keep |

### 2.3 Highlighting / Annotators

| EP | Impl | File:line | Cat | Action |
|---|---|---|---|---|
| `highlightVisitor` | `ScalaSyntaxHighlightingVisitor`, `ScalaRefCountVisitor`, `ScalaRainbowVisitor` | :79 / codeInsight.xml:9–11 | Highlighting | Wrap |
| `annotator` `Scala` | `ScalaColorSchemeAnnotator`, `ScalaAnnotator`, `SbtAnnotator` | :80–83 / sbt.xml:45 | Highlighting | **Replace** — PC diagnostics for Scala 3 |
| `defaultHighlightingSettingProvider` | `ScalaDefaultHighlightingSettingProvider` | :86 | Highlighting | Keep |
| `highlightErrorFilter` | `ScalaDocHighlightErrorFilter`, `CompilerHighlightingErrorFilter` | codeInsight.xml:17 / compiler-integration.xml:20 | Highlighting | Keep |
| `problemHighlightFilter` (×4) | `ScalaProblemHighlightFilter`, `ScalaProblemFileHighlightFilter`, `SbtProblemHighlightFilter`, `SbtProjectImportStateProblemHighlightFilter` | :245–246 / sbt.xml:199–200 | Highlighting | Keep |
| `problemFileHighlightFilter` | `ScalaProblemFileHighlightFilter` | :246 | Highlighting | Keep |
| `lang.inspectionSuppressor` `Scala` | `ScalaInspectionSuppressor`, `BspScalaCliInspectionSuppressor` | :244 / scala-cli.xml:9 | Inspection | Keep |
| `highlightingPassFactory` (×5) | `ScalaLocalVarCouldBeValPassFactory`, `ScalaUnusedImportsPassFactory`, `ScalaUnusedDeclarationPassFactory`, `ScalaAccessCanBeTightenedPassFactory`, `ImplicitHintsPassFactory` | :178–181 / codeInsight.xml:15 | Highlighting | **Replace** for Scala 3 — PC unused / hints |
| `codeInsight.lineMarkerProvider` `Scala` (×4 + worksheet + test) | `ScalaLineMarkerProvider`, `ReflectExpansionLineMarkerProvider`, `ScalaRecursiveCallLineMarkerProvider`, `ScalaRecursiveFunctionLineMarkerProvider`, `WorksheetLineMarkerProvider`, `ScalaTestRunLineMarkerProvider` | :492–495 / worksheet.xml:65 / testing-support.xml:22 | Highlighting | Keep (gutters are cheap, syntactic) |
| `highlightUsagesHandlerFactory` | `ScalaHighlightUsagesHandlerFactory`, `WorksheetResNHighlightFactory` | :626 / worksheet.xml:67 | Highlighting | Keep |
| `readWriteAccessDetector` | `ScalaReadWriteAccessDetector` | :489 | Highlighting | Keep |
| `daemon.changeLocalityDetector` | `ScalaChangeLocalityDetector` | codeInsight.xml:13 | Highlighting | Keep |
| `trafficLightRendererContributor` | `CustomTrafficLightRendererContributor` | compiler-integration.xml:17 | Highlighting | Keep |
| `compiler.isUpToDateCheckConsumer` (×2) | `CompilerHighlightingUpToDateChecker`, `IsUpToDateChecker` | compiler-integration.xml:35–36 | Highlighting / Build | Keep |

### 2.4 Completion

26 `completion.contributor` registrations (Scala + ScalaDirective + sbt + sbtShell). Highlights:

| EP | Impl | File:line | Cat | Action |
|---|---|---|---|---|
| `completion.contributor` `Scala` `id=scalaCompletionContrubutor` | `ScalaBasicCompletionContributor` | :396 | Completion | **Replace** — Metals PC `completion`/`autoImport` for Scala 3 |
| `completion.contributor` `id=scalaIdentifierCompletionContributor` | `ScalaAotCompletionContributor` | :381 | Completion | **Replace** |
| `completion.contributor` `id=scalaClassNameCompletionContributor` | `ScalaClassNameCompletionContributor` | :404 | Completion | **Replace** — PC `workspaceSymbol` |
| `completion.contributor` `ScalaGlobalMembers…` | `ScalaGlobalMembersCompletionContributor` | :407 | Completion | **Replace** — PC auto-import |
| `completion.contributor` `ScalaPrefixPackage…` | `ScalaPrefixPackageCompletionContributor` | :409 | Completion | **Replace** |
| `completion.contributor` `ScalaKeywordCompletionContributor` | `ScalaKeywordCompletionContributor` | :369 | Completion | Keep (syntactic) |
| `completion.contributor` `ScalaEndMarker…` | `ScalaEndMarkerCompletionContributor` | :411 | Completion | Keep (syntactic, Scala 3 specific) |
| `completion.contributor` `CaseClauseCompletionContributor`, `ExhaustiveMatchCompletionContributor`, `CaseClassParametersCompletionContributor` | (clauses pkg) | :384–392 | Completion | Wrap |
| `completion.contributor` `ScalaOverrideContributor` | `ScalaOverrideContributor` | :393 | Completion | **Replace** — PC override |
| `completion.contributor` `ScalaUnresolvedNameContributor` | `ScalaUnresolvedNameContributor` | :398 | Completion | **Replace** |
| `completion.contributor` `ScalaLiteralTypeValues…`, `ScalaNamedTuple…`, `ScalaTypeAnnotations…` | (3) | :417–422 | Completion | Wrap |
| `completion.contributor` `ScalaMemberNameCompletionContributor`, `ScalaDumbAwareCompletionContributor`, `ScalaAfterNewCompletionContributor`, `ScalaSmartCompletionContributor`, `SameSignatureCallParametersProvider`, `ScalaPlainTextSymbolCompletionContributor` | (6) | :372–403 | Completion | Wrap |
| `completion.contributor` `scaladocTagsCompletionContributor` | `ScalaDocCompletionContributor` | :379 | Completion / Docs | Keep |
| `completion.contributor` `ScalaDirective*` (×4) | ScalaDirective pkg | :424–432 | Completion | Keep (scala-cli) |
| `completion.confidence` `Scala` (×2 + sbt ×2 + directive ×1) | `ScalaCompletionConfidence`, `SkipAutopopupInStrings`, `EnableAutoPopupInDependencyStrings`, `EnableAutoPopupInScalacOptionsStrings`, `EnableAutoPopupInScalaDirectiveComment` | :426, :434–435 / sbt.xml:132–133 | Completion | Keep |
| `completion.plainTextSymbol` `Scala` | `ScalaPlainTextSymbolCompletionContributor` | :436 | Completion | Keep |
| `completion.ml.contextFeatures` / `completion.ml.elementFeatures` `Scala` | `ScalaContextFeatureProvider`, `ScalaElementFeatureProvider` | :456–459 | Completion | Keep (ML) |
| `codeInsight.completion.command.{factory,provider}` `Scala` (~20) | Scala + platform command providers | :269–367 | Completion | Keep |
| `codeInsight.wordCompletionFilter` `Scala` | `ScalaWordCompletionFilter` | :266 | Completion | Keep |
| `codeInsight.parameterInfo` `Scala` (×3) | `ScalaPatternParameterInfoHandler`, `ScalaFunctionParameterInfoHandler`, `ScalaTypeParameterInfoHandler` | :609–611 | Completion | **Replace** — PC `hover`/signature info |
| `codeInsight.overrideMethod` / `codeInsight.implementMethod` `Scala` | `ScalaOverrideMethodsHandler`, `ScalaImplementMethodsHandler` | :461–462 | Completion / Refactoring | **Replace** — PC |
| `methodImplementor` | `ScalaMethodImplementor` | :463 | Completion / Refactoring | **Replace** |
| `lookup.actionProvider`, `lookup.charFilter` | `ScalaImportStaticLookupActionProvider`, `ScalaCharFilter` | :441, :464 | Completion | Keep |
| `statistician` `proximity`, `completion` | `ScalaProximityStatistician`, `ScalaCompletionStatistician` | :437–440 | Completion | Keep |
| `weigher` `completion` (×4) + `proximity` (×2) | ScalaContainingClassWeigher, ScalaMethodCompletionWeigher, ScalaByNameWeigher, ScalaKindCompletionWeigher, ScalaClassObjectWeigher, ScalaExplicitlyImportedWeigher, ScopeWeigher | :442–455 | Completion | Keep (sorting) |
| sbt completion contributors (×5) + sbtShell (×1) | `SbtCompletionContributor`, `SbtMavenPackageSearchDependencyCompletionContributor`, `ScalaVersionCompletionContributor`, `SbtScalacOptions…`, `SbtScalacOptionArguments…`, `SbtShellCompletionContributor` | sbt.xml:115–130 | SBT | Keep |
| `weigher` `completion` (sbt) | `SbtDefinitionWeigher` | sbt.xml:139 | SBT | Keep |
| `createDirectoryCompletionContributor` | `SbtDirectoryCompletionContributor`, `BspDirectoryCompletionContributor` | sbt.xml:142 / bsp.xml:86 | SBT/BSP | Keep |
| `typedHandler` (auto popup) | `ScalacOptionsAutoPopupCompletionHandler` | sbt.xml:135 | SBT | Keep |

### 2.5 Refactoring

| EP | Impl | File:line | Cat | Action |
|---|---|---|---|---|
| `lang.refactoringSupport` `Scala` | `ScalaRefactoringSupportProvider` | :218 | Refactoring | Wrap |
| `refactoring.moveHandler` (×4) | `ScalaMoveAnonymousToInnerDelegate`, `ScalaMoveMembersHandler`, `ScalaMoveFilesOrDirectoriesHandler`, `ScalaMoveClassesOrPackagesHandler` | :96–99 | Refactoring | Keep (mostly syntactic) |
| `refactoring.moveMemberHandler` `Scala` | `ScalaMoveMemberHandler` | :95 | Refactoring | Keep |
| `refactoring.moveAllClassesInFileHandler` | `MoveScalaClassesInFileHandler` | :100 | Refactoring | Keep |
| `refactoring.copyHandler` | `CopyScalaWorksheetHandler` | :102 | Refactoring | Keep |
| `refactoring.moveClassHandler` | `MoveScalaClassHandler` | :524 | Refactoring | Keep |
| `refactoring.moveDirectoryWithClassesHelper` | `ScalaMoveDirectoryWithClassesHelper` | :525 | Refactoring | Keep |
| `refactoring.elementListenerProvider` | `ScalaRunConfigurationRefactoringListenerProvider` | :526 | Refactoring | Keep |
| `moveFileHandler` | `MoveScalaFileHandler` | :527 | Refactoring | Keep |
| `inlineActionHandler` (×3) | `ScalaInlineMethodHandler`, `ScalaInlineTypeAliasHandler`, `ScalaInlineVariableHandler` | :528–530 | Refactoring | **Replace** — PC has rewrite capability |
| `refactoring.safeDeleteProcessor` | `ScalaSafeDeleteProcessorDelegate` | :531 | Refactoring | Keep |
| `refactoring.changeSignatureUsageProcessor` (×2) | `ScalaChangeSignatureUsageProcessor`, `ScalaIntroduceParameterUsageProcessor` | :532–533 | Refactoring | Keep |
| `refactoring.helper` (×3) | `ScalaChangeSignatureRefactoringHelper`, `ScalaPrioritizeImportsUsageRefactoringHelper`, `ScalaProcessImportsRefactoringHelper` | :534, :537, :539 | Refactoring | Keep |
| `suggestedRefactoringSupport` `Scala` | `ScalaSuggestedRefactoringSupport` | :541 | Refactoring | **Replace** with PC rename |
| `renamePsiElementProcessor` (×9) | `RenameScalaMethodProcessor`, `PrepareRenameScalaMethodProcessor`, `RenameScalaPackageProcessor`, `RenameScalaVariableProcessor`, `RenameLightProcessor`, `RenameScalaClassProcessor`, `RenameScalaSyntheticParamProcessor`, `RenameScalaTypeAliasProcessor`, `RenameScalaBindingPatternProcessor` | :504–512 | Refactoring | **Replace** — PC `prepareRename`/`rename` |
| `renameHandler` (×3) | `XmlRenameHandler`, `ScalaMemberInplaceRenameHandler`, `ScalaLocalInplaceRenameHandler` | :513–515 | Refactoring | Keep |
| `automaticRenamerFactory` (×3) | `AutomaticOverloadsRenamerFactory`, `AutomaticParameterRenamerFactory`, `AutomaticVariableRenamerFactory` | :501–503 | Refactoring | Keep |
| `vetoRenameCondition` | `ScalaVetoDefaultRenameCondition` | :516 | Refactoring | Keep |
| `nameSuggestionProvider` | `ScalaNameSuggestionProvider` | :517 | Refactoring | Keep |
| `listSplitJoinContext` `Scala` (×6) | ScalaSplitJoinArgumentsContext, …Parameters, …TupleTypes, …Tuples, …TypeArguments, …TypeParameters | :543–548 | Refactoring | Keep |
| `codeStyle.ReferenceAdjuster` `Scala` | `ScalaReferenceAdjuster` | :90 | Refactoring | Keep |

### 2.6 Editor / Typing / Folding / Formatting

| EP | Impl | File:line | Cat | Action |
|---|---|---|---|---|
| `lang.commenter` `Scala` | `ScalaCommenter` | :208 | Editor | Keep |
| `lang.foldingBuilder` `Scala` | `ScalaFoldingBuilder` | :209 | Editor | Keep |
| `lang.formatter` `Scala` | `ScalaFormattingModelBuilder` | :210 | Editor | Keep (scalafmt) |
| `lang.formatter.restriction` `Scala` | `ScalaLanguageFormattingRestriction` | :695 | Editor | Keep |
| `preFormatProcessor` (×2) | `ScalaDocNewlinedPreFormatProcessor`, `ScalaFmtPreFormatProcessor` | :213–214 | Editor | Keep |
| `postFormatProcessor` (×2) | `ScalaBracePostFormatProcessor`, `ScalaTrailingCommaProcessor` | :211–212 | Editor | Keep |
| `fileIndentOptionsProvider` | `ScalafmtFileIndentOptionsProvider` | :2157 | Editor | Keep |
| `disabledIndentRangesProvider` | `ScalaFmtDisabledIndentRangesProvider` | :2156 | Editor | Keep |
| `lang.lineWrapStrategy` `Scala` | `ScalaLineWrapPositionStrategy` | :115 | Editor | Keep |
| `langCodeStyleSettingsProvider` | `ScalaLanguageCodeStyleSettingsProvider` | :114 | Editor | Keep |
| `codeFoldingOptionsProvider` | `ScalaCodeFoldingOptionsProvider` | :113 | Editor | Keep |
| `typedHandler` (×4) | `ScalaDocTypedHandler`, `ScalaTypedHandler`, `ScalaAutoPopupCompletionHandler`, `ScalaDirectiveAutoPopupCompletionHandler` | :223–226 | Editor | Keep |
| `backspaceHandlerDelegate` (×2) | `ScalaBackspaceHandler`, **`Scala3IndentationBasedSyntaxBackspaceHandler`** | :227, :230 | Editor | Keep (Scala 3 syntax) |
| `enterHandlerDelegate` (×12) | ScalaDocMarkdownEnterHandler, ScalaDocTagEnterHandlerDelegate, InterpolatedStringEnterHandler, MultilineStringEnterHandler, EnterBetweenClosureBracesHandler, ScalaEnterAfterUnmatchedBraceHandler, AddUnitFunctionSignatureEnterHandler, PackageSplitEnterHandler, FormatEmptyTemplateBodyAfterEnterHandler, FormatKeywordAfterEnterHandler, AutoBraceEnterHandler, **`Scala3IndentationBasedSyntaxEnterHandler`**, TemplateParentsEnterHandler | :558–582 | Editor | Keep (mostly syntactic) |
| `joinLinesHandler` (×2) | `PackageJoinLinesHandler`, `StripMarginJoinLinesHandler` | :231–232 | Editor | Keep |
| `lang.smartEnterProcessor` `Scala` (×2) | `PackageSplitSmartEnterProcessor`, `ScalaSmartEnterProcessor` | :236–237 | Editor | Keep |
| `lineIndentProvider` | `ScalaLineIndentProvider` | :238 | Editor | Keep |
| `copyPastePreProcessor` (×5) | `StringLiteralCopyPastePreProcessor`, `MultiLineStringCopyPastePreProcessor`, **`Scala3IndentationBasedSyntaxCopyPastePreProcessor`**, `ScaladocCopyPastePreProcessor`, `UsingDirectiveDependencyCopyPastePreProcessor` | :239–243 | Editor | Keep |
| `copyPastePostProcessor` (×3, conversion) | `JavaCopyPastePostProcessor`, `ScalaCopyPastePostProcessor`, `TextJavaCopyPastePostProcessor` | conversion.xml:7–12 | Editor | Keep |
| `filePasteProvider` | `ScalaFilePasteProvider` | conversion.xml:14 | Editor | Keep |
| `lang.quoteHandler` `Scala` | `ScalaQuoteHandler` | codeInsight.xml:20 | Editor | Keep |
| `lang.braceMatcher` `Scala` | `ScalaBraceMatcher` | :197 | Editor | Keep |
| `codeBlockSupportHandler` `Scala` | `ScalaBlockSupportHandler` | :198 | Editor | Keep |
| `lang.surroundDescriptor` `Scala` (×3) | ScalaExpressionSurroundDescriptor, ScalaDocCommentDataSurroundDescriptor, ScalaIgnoreErrorHighlightingSurroundDescriptor | :219–221 | Editor | Keep |
| `lang.unwrapDescriptor` `Scala` | `ScalaUnwrapDescriptor` | :222 | Editor | Keep |
| `statementUpDownMover` | `ScalaStatementMover` | :104 | Editor | Keep |
| `moveLeftRightHandler` `Scala` | `ScalaMoveLeftRightHandler` | :105 | Editor | Keep |
| `extendWordSelectionHandler` (×6) | ScalaAttributeValueSelectioner, ScalaWordSelectioner, ScalaStringLiteralSelectioner, ScalaSemicolonSelectioner, ScalaStatementGroupSelectioner, ScalaCodeBlockSelectioner, ScalaDocCommentSelectioner | :726–733 | Editor | Keep |
| `basicWordSelectionFilter` | `ScalaWordSelectionerFilter` | :729 | Editor | Keep |
| `commentCompleteHandler` | `ScalaIsCommentComplete` | :551 | Editor | Keep |
| `editorSmartKeysConfigurable`, `autoImportOptionsProvider` | ScalaEditorSmartKeysConfigurable, ScalaAutoImportOptionsProvider | :112, :168 | Editor | Keep |

### 2.7 Indexes / Stub indexes / File-based

34 `stubIndex` entries (`scala-plugin-common.xml:654–687`), 2 `fileBasedIndex` entries (:691–692), 1 `indexPatternBuilder` (:485), 2 `todoIndexer` (:486–487).

| Stub index | Purpose | Cat | Action |
|---|---|---|---|
| `ScAllClassNamesIndex`, `ScShortClassNameIndex`, `ScNotVisibleInJavaShortClassNameIndex`, `ScClassFqnIndex`, `ScClassNameInPackageIndex`, `ScJavaClassNameInPackageIndex` | Class name lookup | Index | Keep Scala 2; **for Scala 3 prefer PC `workspaceSymbol`** (but stubs needed for Java interop) |
| `ScImplicitObjectKey`, `ImplicitConversionIndex`, `ImplicitInstanceIndex` | Implicit resolution | Index | **Replace** — PC implicit resolution |
| `ScPackageObjectFqnIndex`, `ScShortNamePackageObjectIndex`, `ScPackagingFqnIndex` | Package objects | Index | Keep |
| `ScFunctionNameIndex`, `ScPropertyNameIndex`, `ScPropertyClassNameIndex`, `ScClassParameterNameIndex`, `ScTypeAliasNameIndex`, `ScStableTypeAliasNameIndex`, `ScStableTypeAliasFqnIndex` | Member lookup | Index | Keep (Java interop); PC for Scala 3 |
| `ScAliasedClassNameKey`, `ScAliasedImportKey` | Imports | Index | Keep |
| `ScDirectInheritorsIndex`, `ScSelfTypeInheritorsIndex` | Inheritance | Index | Replace for Scala 3 |
| `ScAnnotatedMemberIndex`, `ScAnnotatedMainFunctionIndex` | Annotations | Index | Keep |
| `ScTopLevelPropertyByPackageIndex`, `ScTopLevelFunctionByPackageIndex`, `ScTopLevelAliasByPackageIndex`, `ScTopLevelImplicitClassByPackageIndex`, **`ScTopLevelGivenDefinitionsByPackageIndex`**, `ScTopLevelExportByPackageIndex`, `ScTopLevelExtensionByPackageIndex` | Top-level symbol enumeration | Index | **Keep Scala 3 ones** (givens/exports/extensions) — these are the substitutes when PC is offline |
| `ExtensionIndex`, `ScGivenIndex` | Scala 3 specific | Index | Keep |
| `fileBasedIndex` `ScalaDocAsteriskAlignStyleIndexer`, `ImportOrderingIndexer` | Style inference | Index | Keep |

### 2.8 Documentation / Hints / Inlay

| EP | Impl | File:line | Cat | Action |
|---|---|---|---|---|
| `lang.documentationProvider` `Scala` | `ScalaDocumentationProvider` | :498 | Documentation | **Replace** — PC `hover` |
| `platform.backend.documentation.psiTargetProvider` | `ScalaPsiDocumentationTargetProvider` | :500 | Documentation | **Replace** |
| `lang.documentationProvider` `Scala` (sbt ×2) | `SbtScalacOptionsDocumentationProvider`, `SbtDocumentationProvider` | sbt.xml:46–47 | Documentation | Keep |
| `codeInsight.parameterInfo` `Scala` (×3) | (see Completion) | :609–611 | Documentation | Replace with PC |
| `config.inlaySettingsProvider` | `ScalaInlayHintsSettingsProvider` | codeInsight.xml:30 | Documentation | Keep |
| `completion.ml.contextFeatures`/`elementFeatures` | (above) | :456 | Documentation | Keep |
| `vcs.codeVisionLanguageContext` `Scala` | `ScalaVcsCodeVisionContext` | :616 | Documentation | Keep |
| `iw.actionProvider` | `XRayModeWidgetActionProvider` | codeInsight.xml:328 | Documentation | Keep |

### 2.9 Project / Build / SDK

| EP | Impl | File:line | Cat | Action |
|---|---|---|---|---|
| `framework.type` | `ScalaFrameworkType` | :264 | Project | Keep |
| `library.type` | `ScalaLibraryType` | :2155 | Project | Keep |
| `projectStructureDetector` | `ScalaSourceRootFinder` | :643 | Project | Keep |
| `projectSdkSetupValidator` | `ScalaProjectSdkSetupValidator` | :555 | Project | Keep |
| `filePropertyPusher` | `ScalaFeaturePusher` | :689 | Project | **Critical seam** — pushes Scala 2 / 3 features per file. Override here to dispatch |
| `compileServer.plugin` | (jars list) | :745 | Build | Keep |
| `compiler.task` (×4) | `ReformatOnCompileTask`, `CompilationChartsBuildManagerListener`, `EraseCompilerProcessJdkOnce`, `EnsureModulesHaveDifferentProdAndTestOutputsTask`, `WriteScalaJpsProjectMetadataCompileTask`, `PrepareCompileServerTask` | :694 / compiler-integration.xml:13, :18, :29–33 | Build | Keep |
| `compiler.buildIssueContributor` | `MissingScalaSdkBuildIssueContributor` | compiler-integration.xml:15 | Build | Keep |
| `buildProcess.parametersProvider` (×2) | `ScalaBuildProcessParametersProvider`, `CompilerIndicesBuildProcessParametersProvider` | compiler-integration.xml:22, :49 | Build | Keep |
| `codeUsageScopeOptimizer` | `ScalaCompilerReferenceScopeOptimizer` | compiler-integration.xml:56 | Build / Index | Keep |
| `projectTaskRunner` (×2) | `SbtProjectTaskRunnerImpl`, `BspProjectTaskRunner` | sbt.xml:171 / bsp.xml:69 | Build | Keep |
| `externalSystemManager` (×2) | `SbtExternalSystemManager`, `BspExternalSystemManager` | sbt.xml:50 / bsp.xml:40 | SBT / BSP | Keep |
| `externalProjectDataService` (×11 sbt + ×5 bsp) | (see XML) | sbt.xml:58–69 / bsp.xml:42–46 | SBT / BSP | Keep |
| `externalWorkspaceDataService` (×2) | sbt | sbt.xml:71–72 | SBT | Keep |
| `externalSystemConfigLocator` | `SbtConfigLocator` | sbt.xml:74 | SBT | Keep |
| `externalSystemViewContributor` | `SbtViewContributor` | sbt.xml:75 | SBT | Keep |
| `projectImportProvider`/`Builder` (×2) | sbt + bsp | sbt.xml:78–79 / bsp.xml:58–59 | SBT / BSP | Keep |
| `projectOpenProcessor` (×2) | sbt + bsp | sbt.xml:81 / bsp.xml:61 | SBT / BSP | Keep |
| `externalSystemUnlinkedProjectAware` (×2) | sbt + bsp | sbt.xml:82 / bsp.xml:31 | SBT / BSP | Keep |
| `externalSystemTaskNotificationListener` | `SbtNotificationListener` | sbt.xml:114 | SBT | Keep |
| `externalSystemSettingsListener` | `ShowSbtShellAfterCreatingNewProject` | sbt.xml:103 | SBT | Keep |
| `externalTextProvider` | `SbtTextProvider` | sbt.xml:83 | SBT | Keep |
| `externalIconProvider` `SBT`/`BSP` | `SbtIconProvider`, `BspIconProvider` | sbt.xml:76 / bsp.xml:48 | SBT / BSP | Keep |
| `externalExecutionAware` `sbt` | `SbtExecutionAware` | :65 | SBT | Keep |
| `orderEnumerationHandlerFactory` | `SbtOrderEnumeratorHandlerFactory` | sbt.xml:197 | SBT | Keep |
| `moduleType` (×3) | `SBT_MODULE`, `SHARED_SOURCES_MODULE`, `BSP_SYNTHETIC_MODULE` | sbt-api.xml:9 / sbt.xml:87 / bsp.xml:37 | SBT / BSP | Keep |
| `moduleConfigurationEditorProvider` (×3) | sbt + bsp | sbt.xml:85, :88 / bsp.xml:38 | SBT / BSP | Keep |
| `moduleService` (×3) | `SbtModule`, `SharedSourcesOwnerModules`, `DisplayModuleName` | sbt-api.xml:8 / sbt.xml:52–53 | SBT | Keep |
| `newProjectWizard.languageGenerator` | `ScalaNewProjectWizard` | sbt.xml:193 | Project | Keep |
| `newProjectWizard.scala.buildSystem` (×3) | sbt, IntelliJ, scala-cli | sbt.xml:194–195 / scala-cli.xml:8 | Project | Keep |
| `projectConfigurable` (root + 10 children + compiler + compile-server + bytecodes + sbt + bsp) | see :116–165, :720–724, compiler-integration.xml:24, sbt.xml:91, bsp.xml:63 | Project | Keep |
| `projectService` (≈20) | `ScalaPsiManager`, `ScalaCompilerConfiguration`, `ScalaProjectSettings`, `ScalafmtDynamicConfigService`, `ScalaMacroEvaluator`, … | :166, :701–711 | Project | Keep |
| `applicationService` (≈10) | `ScalaApplicationSettings`, `ScalaCodeInsightSettings`, `ExpectedTypes`, `ScalafmtDynamicService`, `ScalaCompileServerSettings`, `ScalaCodeFoldingSettings`, `ScalaMetaApi`, `ScalastyleService`, … | :697–718 / scala-meta-impl.xml / scalastyle-integration.xml | Project | Keep |

### 2.10 Run Configurations

| EP | Impl | File:line | Cat | Action |
|---|---|---|---|---|
| `configurationType` (×6) | `ScalaConsoleConfigurationType`, `SbtConfigurationType`, `AmmoniteRunConfigurationType`, `Specs2ConfigurationType`, `ScalaTestConfigurationType`, `UTestConfigurationType`, `MUnitConfigurationType`, `BspTestRunType` | scala-repl.xml:5 / sbt.xml:166 / worksheet.xml:51 / testing-support.xml:28–30 / testing-support.munit.xml:12 / bsp.xml:72 | Run Config | Keep |
| `runConfigurationProducer` (×6) | `ScalaApplicationConfigurationProducer`, Specs2, ScalaTest, UTest, MUnit producers | :647 / testing-support.xml:32–34 / testing-support.munit.xml:14 | Run Config | Keep |
| `runConfigurationExtension` | `ScalaApplicationConfigurationExtension` | :648 | Run Config | Keep |
| `programRunner` (×2) | `SbtProgramRunner`, `SbtDebugProgramRunner` | sbt.xml:168–169 | Run Config / SBT | Keep |
| `runLineMarkerContributor` (×3) | `ScalaRunLineMarkerContributor`, `AmmoniteRunMarkerContributor`, `ScalaTestRunLineMarkerProvider` | :553 / worksheet.xml:64 / testing-support.xml:22 | Run Config | Keep |
| `runAnything.executionProvider` | `SbtRunAnythingProvider` | sbt.xml:173 | Run Config | Keep |
| `javaMainMethodProvider` (×2) | `ScalaMainMethodProvider`, **`Scala3MainMethodProvider`** | :584–585 | Run Config | Branch — Scala 3 `@main` |
| `java.programPatcher` | `BspJvmEnvironmentProgramPatcher` | bsp.xml:76 | Run Config / BSP | Keep |
| `stepsBeforeRunProvider` | `BspFetchEnvironmentTaskProvider` | bsp.xml:77 | Run Config / BSP | Keep |
| `bspEnvironmentRunnerExtension` (×3) | App + JUnit + ScalaTest | bsp.xml:35 / bsp-junit.xml:9 / testing-support.xml:36 | BSP / Run Config | Keep |

### 2.11 Inspections / Intentions

| Count | EP | Cat | Action |
|---|---|---|---|
| **173** `localInspection` (≈165 in `scala-plugin-common.xml`, 1 in `codeInsight.xml`, 3 in `sbt.xml`, 2 in `worksheet.xml`, 1 in `scala-cli`-related) | Inspections in groups: general, syntactic simplification, syntactic clarification, method signature, collections, scaladoc, dataflow analysis, internal, deprecated, etc. | Inspection | Mostly keep (syntactic); **replace semantic ones** for Scala 3 with PC diagnostics |
| **70** `intentionAction` (≈35 in `scala-plugin-common.xml`, ≈35 in `codeInsight.xml`) | Conversions, intentions, expression/argument transforms | Inspection | Keep |

### 2.12 Live Templates / Macros

| EP | Impl | File:line | Cat | Action |
|---|---|---|---|---|
| `defaultLiveTemplates` | `liveTemplates/scala`, `liveTemplates/sbt` | codeInsight.xml:34 / sbt.xml:144 | Macro | Keep |
| `liveTemplatePreprocessor` | `ScalaTemplatePreprocessor` | codeInsight.xml:35 | Macro | Keep |
| `liveTemplateContext` (×10) | `ScalaFileTemplateContextType`, **`Scala3FileTemplateContextType`**, ScalaXml, ScalaComment, ScalaString, ScalaCode, ScalaImplicitClass, ScalaBlankLine, Sbt | codeInsight.xml:36–57 / sbt.xml:145 | Macro | Keep (Scala 3 already has its own context) |
| `liveTemplateMacro` (×19) | ScalaAnnotatedMacro, ScalaVariableOfTypeMacro$RegularVariable/$ArrayVariable/$IterableVariable, ScalaClassNameMacro, ScalaSuggestVariableNameByTypeMacro, ScalaImplicitClassNameMacro, ScalaImplicitClassExtendsAnyValOptionalTextMacro, ScalaComponentTypeOfMacro, ScalaCurrentPackageMacro, ScalaExpressionTypeMacro, ScalaIterableComponentTypeMacro, ScalaMethodNameMacro, ScalaMethodParametersMacro, ScalaMethodReturnTypeMacro, ScalaQualifiedClassNameMacro, ScalaSubtypesMacro, ScalaTypeOfVariableMacro, ScalaPrimaryConstructorMacro$Params/$ParamNames/$ParamTypes, ScalaTypeParametersMacro, ScalaTypeParametersWithoutBoundsMacro, ScalaCompanionClassMacro | codeInsight.xml:59–82 | Macro | **Replace for Scala 3** — most need type info ⇒ PC |

### 2.13 Worksheet / REPL / Debugger

| EP | Impl | File:line | Cat | Action |
|---|---|---|---|---|
| (worksheet — see §2.1) | | | Worksheet | |
| `applicationService` `ScalaMetaApi` | `ScalaMetaApiImpl` | scala-meta-impl.xml:4 | Worksheet / Macro | Keep |
| `applicationService` `ScalastyleService` | `ScalastyleServiceImpl` | scalastyle-integration.xml:4 | Inspection | Keep |
| `debugger.*` (≈17 entries) | ScalaCodeFragmentFactory, ScalaPositionManagerFactory, ScalaEditorTextProvider, ScalaRuntimeRefRenderer, ScalaCollectionRendererProvider, ScalaClassRendererProvider, ScalaSyntheticProvider, ScalaFrameExtraVariablesProvider, ScalaSyntheticSteppingFilter, ScalaSimpleGetterProvider, ScalaSourcePositionProvider, ScalaBreakpointHandlerFactory, ScalaSourcePositionHighlighter, ScalaFieldNameAdjuster, ScalaParameterNameAdjuster, ScalaLineBreakpointType, ScalaDebuggerClassFilterProvider, ScalaDebuggerSettings, ScalaSmartStepIntoHandler | scalaCommunity.debugger.xml:13–31 | Debugger | Keep (debugger works on JVM bytecode; Scala-agnostic) |

### 2.14 Listeners, post-startup, services

| EP | Count | File:line | Cat | Action |
|---|---|---|---|---|
| `postStartupActivity` | ≈18 (scala-plugin-common.xml, sbt, bsp, compiler-integration, codeInsight, scala-cli, worksheet) | various | Project | Keep |
| `editorFactoryListener` | 3 (`incremental.Listener`, `ScalaEditorFactoryListener`, `CompilerHighlightingEditorFocusListenerRegisterer$ByListener`) | :172 / codeInsight.xml:32 / compiler-integration.xml:58 | Highlighting | Keep |
| `vfs.asyncListener` | `CompilerHighlightingFileListener` | compiler-integration.xml:61 | Build | Keep |
| `psi.treeChangeListener` | `ScalaPsiChangeListener` | :2172 | PSI | Keep |
| `applicationListeners` (5) | `ScalaDynamicPluginManager`, `DeregisterSyntheticClassesListener`, `ScalafmtProjectListener`, `ScalafmtReformatOnFileSaveTask`, `ScalaCodeStyleProjectListener` | :2177–2190 | Project | Keep |
| `projectListeners` (≈8) | `SyncOutputExecutionListener`, `ScalaHighlightingModeWidgetFactory$Listener`, `ScalaHighlightingModeWidgetModuleRootListener`, `ScalaPsiManager$SPMModuleRootListener`, sbt import listeners, `UpdateCompilationProgressListener`, `UpdateCompilerGeneratedStateListener`, `CompilerTypeRequestListener`, `CompilerEventFromCustomBuilderMessageListener`, `CompilerHighlightingModuleRootListener`, `ToggleHighlightingModeListener$*`, worksheet editor/dumb listeners, debugger listeners, `BspFetchTestEnvironmentTaskInstaller` | various | Project / Build | Keep |
| `notificationGroup` (≈20) | scala.*, sbt.*, scalafmt.*, scala.worksheet, BSP | various | Project | Keep |
| `toolWindow` (×4) | `internal-profiler`, `sbt`, `sbt-shell-toolwindow`, `BSP` | :261 / sbt.xml:95–102 / bsp.xml:65 | Project | Keep |
| `statusBarWidgetFactory` (×3) | `ScalaHighlightingModeWidgetFactory`, `CompileServerWidgetFactory`, `BspServerWidgetFactory` | :84 / compiler-integration.xml:38 / bsp.xml:74 | Project | Keep |
| `registryKey` (≈25) | `scala.highlighting.*`, `scala.incremental.*`, `scala.compiler.*`, `scala.worksheet.*`, `sbt.*`, `BSP.*`, `scala.enable.match.type.intrinsics`, etc. | various | Project | Keep |
| `iconMapper` (×2) | `ScalaIconMappings.json`, `SbtIconMappings.json`, `BSPIconMappings.json` | :2167 / sbt-api.xml:11 / bsp.xml:88 | Editor | Keep |
| `internalFileTemplate` (×8) | Scala Class, CaseClass, CaseObject, Trait, Object, Package Object, Scala File, Scala Enum, Scala Worksheet | :475–482 / worksheet.xml:10 | Project | Keep |
| `defaultTemplatePropertiesProvider` | `ScalaDefaultTemplatePropertiesProvider` | :483 | Project | Keep |

### 2.15 External Library support

| EP / Item | Impl | File:line | Cat | Action |
|---|---|---|---|---|
| Synthetic member injectors (11 impls) | CaseClassAndCompanionMembersInjector, AbstractTypeContextBoundsInjector, EnumMembersInjector, DerivesInjector, MonocleInjector, ScalazDerivingInjector, CirceCodecInjector, QuasiQuotesInjector, NewTypeInjector, SimulacrumInjector, DerevoInjector, ScioInjector | scala-plugin-common.xml:33–44 | External Library | Wrap |
| `externalLibraries.bm4.BetterMonadicForSupport` (project service) | — | :709 | External Library | Keep |
| `externalLibraries.kindProjector.KindProjectorUtil` (project service) | — | :710 | External Library | Keep |

### 2.16 Other integrations

| EP | Impl | File | Cat | Action |
|---|---|---|---|---|
| `externalSystem.dependencyModifier` | `SbtDependencyModifier` | packagesearch.xml:4 | SBT | Keep (optional dep) |
| `packagesearch.*` (×3) | sbt | packagesearch.xml:5–7 | SBT | Keep (optional dep) |
| `patterns.patternClass` | `ScalaPatterns` alias `scala` | :1038 | Inspection | Keep |
| `webHelpProvider` | `ScalaWebHelpProvider` | :66 | Project | Keep |
| `statistics.*` (7 counter + 3 project usages + 1 import processor) | — | :68–77 | Project | Keep |
| `generalTroubleInfoCollector` | `ScalaGeneralTroubleInfoCollector` | :2169 | Project | Keep |
| `aboutPopupDescriptionProvider` | `ScalaPluginAboutPopupDescriptionProvider` | :2170 | Project | Keep |
| `ide.dynamicPluginVetoer` | `ScalaDynamicPluginVetoer` | :2174 | Project | Keep |
| `exceptionFilter` / `consoleFilterProvider` | `ScalaPackageObjectFilterFactory`, `ScalaPackageObjectConsoleFilterProvider` | :92–93 | Editor | Keep |
| `editorTabTitleProvider` | `PackageObjectEditorTabTitleProvider` | :107 | Editor | Keep |
| `editorFileSwapper` | `ScalaEditorFileSwapper` | :642 | Editor | Keep |
| `treeStructureProvider` | `ScalaTreeStructureProvider` | :196 | Navigation | Keep |
| `iconProvider` | `ScalaIconProvider` | :110 | Editor | Keep |
| `breadcrumbsInfoProvider` | `ScalaBreadcrumbsInfoProvider` | :88 | Editor | Keep |
| `navbar` | `ScalaNavBarModelExtension` | structure-view.xml:16 | Navigation | Keep |
| `lang.psiStructureViewFactory` `Scala` | `ScalaStructureViewFactory` | structure-view.xml:14 | Navigation | Keep |
| `lang.rearranger` `Scala` | `ScalaRearranger` | :91 | Editor | Keep |
| `lang.namesValidator` `Scala` | `ScalaNamesValidator` | :640 | Refactoring | Keep |
| `treeCopyHandler` | `ScalaChangeUtilSupport` | :109 | PSI | Keep |
| `lang.tokenSeparatorGenerator` `ScalaDirective` | `ScalaTokenSeparatorGenerator` | :190 | PSI | Keep |
| `lang.importOptimizer` `Scala` | `ScalaImportOptimizer` | :639 | Editor | **Replace** for Scala 3 (PC organize imports) |
| `lang.floatingToolbarCustomizer` `Scala` | `FloatingToolbarCustomizer$DefaultGroup` | :217 | Editor | Keep |
| `colorSettingsPage`, `additionalTextAttributes` (×4) | `ScalaColorsAndFontsPage`, schemes | :111, :192–195 | Editor | Keep |
| `lang.syntaxHighlighterFactory` / `lang.syntaxHighlighter` (×4) | Scala, ScalaDoc, ScalaDirective, Scala Worksheet, Scala 3 Worksheet | :183–188 / worksheet.xml:27–28 | Highlighting | Keep |
| `annotationSupport` `Scala` | `ScalaAnnotationSupport` | :630 | PSI | Keep |
| `constantExpressionEvaluator` `Scala` | `ScalaConstantExpressionEvaluator` | :629 | PSI | Keep |
| `typeHierarchyProvider`/`methodHierarchyProvider`/`callHierarchyProvider` `Scala` | Scala versions | :623–625 | Navigation | Keep |
| `scratch.creationHelper` `Scala` | `ScalaScratchFileCreationHelper` | worksheet.xml:32 | Worksheet | Keep |
| `deadCode` | `ScalaTestingFrameworkEntryPoint` | testing-support.xml:14 | Run Config | Keep |
| `testCreator`/`testFinder`/`testGenerator` `Scala` | Scala versions | testing-support.xml:16–18 | Run Config | Keep |
| `testFramework` (×4) | Specs2, ScalaTest, UTest, MUnit | testing-support.xml:24–26 / testing-support.munit.xml:10 | Run Config | Keep |
| `exceptionFilter` | `ScalaTestFailureLocationFilterFactory` | testing-support.xml:20 | Run Config | Keep |
| `uastLanguagePlugin` / `generate.uastCodeGenerationPlugin` | `ScalaUastLanguagePlugin`, `ScalaUastCodeGenerationPlugin` | scalaCommunity.uast.xml:9–10 | UAST | Keep |
| `experimentalFeature` `scala.uast.enabled` | — | scalaCommunity.uast.xml:14 | UAST | Keep |
| Structural search: `profile`, `filterProvider` | `ScalaStructuralSearchProfile`, `ScalaFilterProvider` | scalaCommunity.structural-search.xml:7–8 | Inspection | Keep |

---

## 3. Cross-cut: Scala 2 vs Scala 3 branches

`isScala3`/`Scala3Language`/`ScalaLanguageLevel.Scala_3` appear in **128 source files** under `scala-impl/src` alone. The ones that matter for routing an alternative Scala 3 implementation:

| Location | Branch | Why it matters |
|---|---|---|
| `ScalaLanguage`/`Scala3Language` (Language instances) | Two distinct `Language`s registered to the platform | Already a clean split — `lang.parserDefinition` etc. are per-Language |
| `lang.substitutor` `Scala` → `ScalaLanguageSubstitutor` | Picks Scala 2 vs Scala 3 per file (based on SDK / `using` directives) | **The dispatch seam.** Substitute into a hypothetical `Scala3MetalsLanguage` for alternative impl |
| `filePropertyPusher` → `ScalaFeaturePusher` | Pushes `ScalaFeatures` (isScala3, source3, etc.) onto files | Could push a "use Metals" flag here |
| `ScalaVersion` + `ScalaLanguageLevel` (`Scala_3_0`…`Scala_3_9`) | 10 explicit Scala 3 minor versions | Version dispatch |
| `ScalaFeatures` (`project/ScalaFeatures.scala`) | `isScala3`, `hasSourceFutureFlag`, `indentationBasedSyntaxEnabled`, `literalTypesEnabled` | Feature-flag dispatch per file |
| `extensions/package.scala:160` `isScala3File` / `isInScala3Module` | Used pervasively (128 files) | Hook here to mean "use Metals" |
| `Scala3ParserDefinition` (`scala-plugin-common.xml:202`) | Separate parser | Distinct seam |
| `Scala3IndentationBasedSyntax{Enter,Backspace,CopyPaste}Handler` (:230, :241, :581) | Scala 3 significant-indentation editor | Distinct per-language editor handlers |
| `Scala3MainMethodProvider`, `Scala3MainMethodSyntheticClassFinder` (:585, :621) | `@main` support | Already Scala-3-only |
| `Scala3DeprecatedPackageObjectInspection`, `Scala3DeprecatedAlphanumericInfixCallInspection` (:1072, :1078) | Scala 3 specific inspections | Already Scala-3-only |
| `SbtParserDefinitionScala3` (sbt.xml:36) | sbt in Scala 3 | Distinct seam |
| `WorksheetParserDefinition3` (worksheet.xml:22) | Worksheets in Scala 3 | Distinct seam |
| `Scala3FileTemplateContextType` (codeInsight.xml:39) | Live templates | Distinct seam |
| Parser utilities `InScala3`, `InBracelessScala3` (`lang/parser/util/`) | Parser branching | Distinct seam |
| `Annotator` branches: `ScFunctionAnnotator`, `ScMethodInvocationAnnotator.isScala3dotcErrorsMode`, `ScNumericLiteralAnnotator`, `ScImportExprAnnotator` | Already calls into Scala 3 compiler errors mode | Existing seam for PC diagnostics |
| `ScalaHighlightingMode.showCompilerErrorsScala3(project)` (`settings/ScalaHighlightingMode.scala:70`) | Project-wide flag for compiler-based errors | **Toggle the Metals backend here** |
| `ScalaModuleSettings.hasScala3` (`project/ScalaModuleSettings.scala:72`) | Per-module flag | Use to gate Metals |
| `project/package.scala:640` `ModuleExt.isScala3` | Module-level predicate | Dispatch |
| `project/package.scala:842` `isScala3OrSource3Enabled` | Combined predicate | Dispatch |
| `DependencyManager.scala:380, 428` | Resolves `_3` artifacts | Library resolution |
| `runner/ScalaApplicationConfigurationProducer.isScala3ApplicationConfiguration` | Run config detection | Already Scala-3-aware |

---

## 4. Top-level plugin descriptor: classloader / module boundaries

From `pluginXml/resources/META-INF/plugin.xml`:

### 4.1 Hard plugin dependencies (`<dependencies>`)

- `com.intellij.java` — Java plugin (PSI, refactorings)
- `intellij.testRunner.plugin`
- `intellij.libraries.misc.plugin`
- `org.intellij.intelliLang`
- Modules: `intellij.java.backend`, `intellij.java.execution.impl`, `intellij.libraries.commons.text`

### 4.2 Optional plugin dependencies

- `com.jetbrains.packagesearch.intellij-plugin` (commented out in OSS build) — see `packagesearch.xml`

### 4.3 Plugin modules loaded via `<content namespace="jetbrains">`

These are the **installable modules** — the natural place to add a new **`scalaCommunity.scala3-metals`** module:

| Module | File | Notes |
|---|---|---|
| `scalaCommunity.bsp` | `scalaCommunity.bsp.xml` | BSP client; primary build driver |
| `scalaCommunity.bsp-junit` | `scalaCommunity.bsp-junit.xml` | JUnit via BSP |
| `scalaCommunity.bsp-terminal` | `scalaCommunity.bsp-terminal.xml` | Terminal start dir |
| `scalaCommunity.sbt-kotlin-ij-plugin-interop` | — | sbt Kotlin interop |
| `scalaCommunity.testing-support` | `scalaCommunity.testing-support.xml` | ScalaTest/Specs2/uTest |
| `scalaCommunity.testing-support.munit` | `scalaCommunity.testing-support.munit.xml` | MUnit |
| `scalaCommunity.scala-cli` | `scalaCommunity.scala-cli.xml` | scala-cli (BSP setup) |
| `scalaCommunity.worksheet` | `scalaCommunity.worksheet.xml` | Worksheets / Ammonite |
| `scalaCommunity.debugger` | `scalaCommunity.debugger.xml` | Scala debugger |
| `scalaCommunity.compiler-integration` | `scalaCommunity.compiler-integration.xml` | Compile server, compiler-based highlighting |
| `scalaCommunity.structural-search` | `scalaCommunity.structural-search.xml` | Structural search profile |
| `scalaCommunity.uast` | `scalaCommunity.uast.xml` | UAST |
| Integrations: `copyright`, `devkit`, `featuresTrainer`, `gradle`, `i18n`, `intellij-bazel`, `javaDecompiler`, `junit`, `markdown`, `maven`, `properties`, `textAnalysis`, `intelliLang` | `scalaCommunity.<name>.xml` | Each optional, depends on the corresponding host plugin |

### 4.4 `xi:include` order (top-level plugin assembly)

`plugin.xml` includes:
1. `sbt-api.xml` (defines sbt EPs, `SbtModule` type)
2. `scala-plugin-common.xml` (the 2 276-line core)
3. `sbt.xml` (sbt impl)
4. `codeInsight.xml` (inlay hints, templates)
5. `conversion.xml` (Java⇄Scala paste)
6. `scala-repl.xml`
7. `structure-view.xml`
8. `scala-meta-impl.xml`
9. `scalastyle-integration.xml`
10. (Optional ultimate): `scala-ultimate-plugin.xml`

---

## 5. Recommended seams for a Metals-PC-backed Scala 3 implementation

Ranked from cleanest to most invasive:

1. **New optional module `scalaCommunity.scala3-metals`** (added to `<content>` in `plugin.xml`). Depends on `scalaCommunity.bsp` + a Metals-PC classpath. Owns its own services and registers implementations against the EPs listed below.
2. **Plugin-defined `dynamic="true"` EPs** (§1) — register Metals-backed implementations:
   - `findUsages.externalReferenceSearcher` (PC `findReferences`)
   - `findUsages.externalInheritorsSearcher` (PC `subclasses`)
   - `syntheticMemberInjector` (PC synthetic symbols for Scala 3)
   - `parameterInfoEnhancer` (PC `hover`/signature)
   - `unresolvedReferenceFixProvider` (PC auto-imports)
   - `importUsedProvider` + `importOptimizerHelper` (PC organize imports)
   - `referenceExtraResolver` (PC resolve)
   - `scalaDynamicTypeResolver` (PC structural types)
   - `scalaSyntheticClassProducer`
   - `scalaElementToRenameContributor` (PC rename)
   - `worksheetHighlightingCompiler` (PC worksheet eval)
   - `evaluatorCompileHelper` (PC debugger eval)
3. **`Scala3Language` per-language registrations** — keep Scala 2 untouched. Replace these for the Scala 3 language:
   - `lang.documentationProvider` (PC `hover`)
   - `completion.contributor` (PC `completion` — replace `ScalaBasicCompletionContributor`, `ScalaClassNameCompletionContributor`, `ScalaGlobalMembersCompletionContributor`, `ScalaOverrideContributor`, `ScalaUnresolvedNameContributor`)
   - `annotator` `Scala` (PC diagnostics)
   - `highlightingPassFactory` (PC unused hints)
   - `codeInsight.parameterInfo` (PC signature)
   - `codeInsight.overrideMethod` / `codeInsight.implementMethod` / `methodImplementor` (PC)
   - `inlineActionHandler` (PC rewrite)
   - `renamePsiElementProcessor` (PC rename)
   - `overridingMethodsSearch` / `directClassInheritorsSearch` / `classInheritorsSearch` / `annotatedElementsSearch` (PC)
4. **Dispatch seam**: `ScalaLanguageSubstitutor` + `ScalaFeaturePusher` — substitute into a `Scala3MetalsLanguage` (or set a feature flag) for files where the Metals backend is active, then the per-language EPs in step 3 take effect automatically.
5. **Existing toggle**: `ScalaHighlightingMode.showCompilerErrorsScala3` — already a project-wide flag for compiler-based errors; reuse as the user-facing switch.
6. **TASTy / TASTyFileDecompiler** — Metals can serve richer info than the existing TASTy reader, but the existing one is good enough as a fallback.

**Estimated work**: ~40 EP registrations to add or override; ~10 new service implementations wrapping Metals PC; zero changes required to Scala 2 code paths if the language-substitutor seam is used.
