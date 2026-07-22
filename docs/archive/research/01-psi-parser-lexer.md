# 01 — PSI / Parser / Lexer / Stubs architecture

Domain report for the Metals-PC redesign of Scala 3 support. This document covers the
hand-written **lexer, parser, PSI tree, and stub index** the IntelliJ Scala plugin
uses today, identifies where the Scala 3 implementation diverges from upstream
`dotc`, and proposes a seam that lets a real Scala 3 `Tree`/`tpd.Tree` (or
`pc`'s `Interactive` driver) become the source of truth for Scala 3 files —
removing the plugin's hand-written Scala 3 grammar and its bespoke PSI element
hierarchy from the hot path.

All `path:line` references are relative to the repo root.

---

## 1. Parser & Lexer entry points

### 1.1 Two language flavors, two parser definitions, one parser class

The plugin defines a single base class `ScalaParserDefinitionBase`
(`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/parser/ScalaParserDefinitionBase.scala:9`)
extending IntelliJ's `com.intellij.lang.ParserDefinition`. Two concrete
subclasses are registered in `scala-plugin-common.xml:201-207`:

| EP (`lang.parserDefinition`) | Language | Class |
|---|---|---|
| `Scala` | Scala 2 | `ScalaParserDefinition` (`ScalaParserDefinition.scala:13`) |
| `Scala 3` | Scala 3 | `Scala3ParserDefinition` (`Scala3ParserDefinition.scala:13`) |

Both differ in only three lines: the lexer flag (`isScala3 = false/true`),
the `FileNodeType` (`ScalaParserDefinition.scala:28` vs
`Scala3ParserDefinition.scala:28`), and the parser instance
(`ScalaParserDefinition.scala:17` vs `Scala3ParserDefinition.scala:20`).
The produced `PsiFile` is the **same** `ScalaFileImpl` in both cases
(`ScalaParserDefinition.scala:22`, `Scala3ParserDefinition.scala:22`).

Two more EPs live alongside this one in `scala-plugin-common.xml:199-207`:

- `lang.fileViewProviderFactory` →
  `org.jetbrains.plugins.scala.lang.psi.ScFileViewProviderFactory`
  (`scala/scala-api/src/lang/psi/ScFileViewProviderFactory.scala:8`) —
  produces `ScFileViewProvider`, a `SingleRootFileViewProvider`
  (`scala/scala-api/src/lang/psi/ScFileViewProvider.scala:15`).
- `lang.ast.factory` → `ScalaASTFactory`
  (`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/parser/ScalaASTFactory.scala:15`)
  — only overrides leaf creation for ScalaDoc / directives.
- `lang.substitutor` → `ScalaLanguageSubstitutor`
  (`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/parser/ScalaLanguageSubstitutor.scala:21`)
  — the **only** mechanism that decides whether a `.scala` file in a Scala 3
  module gets the `Scala 3` language (and therefore the Scala 3 parser/lexer).
  The logic (`ScalaLanguageSubstitutor.scala:35-48`) is "module has Scala 3" OR
  "path looks like a Scala 3 library sources jar" (a regex match,
  `ScalaLanguageSubstitutor.scala:67-94`). **No file-content sniffing**.

### 1.2 The single parser class with an `isScala3` boolean

`ScalaParser` (`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/parser/ScalaParser.scala:7`)
is the only `PsiParser` for both languages:

```scala
class ScalaParser(isScala3: Boolean) extends PsiParser with LightPsiParser {
  def mkScalaPsiBuilder(delegate: PsiBuilder, isScala3: Boolean): ScalaPsiBuilder =
    new ScalaPsiBuilderImpl(delegate, isScala3)
  override def parseLight(rootElementType: IElementType, delegate: PsiBuilder): Unit = {
    implicit val builder: ScalaPsiBuilder = mkScalaPsiBuilder(delegate, isScala3)
    ...
    CompilationUnit()
    ...
  }
}
```

There is no separate Scala 3 grammar. Instead, every parsing rule (`ParsingRule`
subclass) reads `builder.isScala3` / `builder.isScala3IndentationBasedSyntaxEnabled`
inline. Grep counts ~20+ files in
`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/parser/parsing/` that branch
on `builder.isScala3` — e.g. `Extension.scala:22`, `TmplDef.scala:48`,
`Def.scala`, `Type.scala`, `Pattern1.scala:14`, `CompoundType.scala`,
`SimpleType.scala`, `GivenDef.scala`, `TypeArgs.scala`, `Refinement.scala`,
`PolyFunOrTypeLambda.scala`, `InfixType.scala`, `ClassParam.scala`,
`ClassParamClause.scala`, `Parents.scala`, `Derives.scala`, `Template.scala`,
`TypedFunParam.scala`, `StableId.scala`, `Pattern1.scala`, `TypeDef.scala`.

The relevant Scala 3 syntax lives in:

- Indentation region tracking —
  `parser/parsing/builder/IndentationRegion.scala`,
  `parser/parsing/builder/ScalaPsiBuilderImpl.scala:170-202` (a stack of
  `IndentationRegionHolder`s with rollback support).
- `end` markers — `parser/parsing/base/End.scala`.
- `enum` / `enum case` — `parser/parsing/statements/EnumCase.scala`,
  `parser/parsing/top/EnumDef.scala`.
- `given` — `parser/parsing/top/template/GivenDef.scala`.
- `extension` — `parser/parsing/base/Extension.scala` (212 lines), including a
  bespoke `ExtMethods` rule (`Extension.scala:46`) that re-implements the
  braceless `{ UsingParamClause } ExtMethods` grammar.
- Named tuples, match types, capture sets, poly functions, type lambdas —
  `parser/parsing/types/{MatchTypeSuffix,TypeCaseClauses,CaptureSet,
  PolyFunOrTypeLambda,TupleOrNamedTupleOrParenthesizedType}.scala`.

Every one of these is a hand-written re-implementation of syntax that `dotc`'s
`Parsers.scala` already handles. The parser produces an AST of `ASTNode`s whose
`IElementType` is one of the ~120 entries in
`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/parser/ScalaElementType.scala:24-322`.

### 1.3 The lexer: layered JFlex + XML

`ScalaLexer` (`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/lexer/ScalaLexer.java:36`)
is a 563-line Java hand-rolled **state machine** layered on top of:

1. `ScalaPlainLexer` (`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/lexer/ScalaPlainLexer.scala:11`)
   — a `LayeredLexer` with two JFlex-backed sub-lexers:
   - `ScalaSplittingFlexLexer` (splits doc-comment vs. plain Scala content).
   - `ScalaFlexLexer` wrapping `_ScalaCoreLexer` — the actual JFlex-generated
     lexer (`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/lexer/core/_ScalaCoreLexer.java`,
     2361 lines generated from `_ScalaCoreLexer.flex`, 646 lines).
2. `ScalaXmlLexer` — switches in and out of XML literals.

`_ScalaCoreLexer`'s constructor takes a single `boolean isScala3`
(`_ScalaCoreLexer.java:35`) and the `.flex` file references it for the
Scala-3-only tokens (`given`, `enum`, `extension`, `export`, `then`, `end`,
`as`, `derives`, `inline`, `opaque`, `open`, `transparent`, `erased`, `infix`,
`into`, `^`-capture, `rd`, `*`-reach, `->`, `?->`, `=>>`, `?=>`). These tokens
are all enumerated in `scala/scala-impl/src/org/jetbrains/plugins/scala/lang/lexer/ScalaTokenType.scala:20-62`.

The lexer's `getState()` (`ScalaLexer.java:95`) folds XML state, Scala plain
state, and "is the previous token XML?" into a single `int`; **the lexer state
does not encode `isScala3`** — that decision is made at the parser level by
inspecting `ScalaPsiBuilder.features` (`ScalaPsiBuilderImpl.scala:44-65`),
which is resolved from the file's module via `ScalaFeaturePusher`.

### 1.4 Where the grammar splits today — summary

| Concern | Where it lives | Notes |
|---|---|---|
| Scala 2 vs 3 file detection | `ScalaLanguageSubstitutor` | module-flag or jar-name regex |
| Token set | `_ScalaCoreLexer.flex` + `ScalaTokenType` | JFlex, two-state via `isScala3` |
| Grammar branches | every `ParsingRule` reads `builder.isScala3` | no separate Scala 3 grammar |
| Braceless regions | `IndentationRegion` stack on the builder | ad-hoc; re-invents `dotc` indentation |

---

## 2. PSI element hierarchy

### 2.1 Top-level layout

The PSI for Scala lives under `scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/`
with these subpackages:

| Package | Role |
|---|---|
| `api/` | `trait`s for PSI elements (no impl) — the public face |
| `impl/` | `class`es backed by an `ASTNode` or a `StubElement` |
| `fake/` | `FakePsiMethod`, `FakePsiType` — placeholders for synthetic Java-view methods |
| `light/` | `PsiClassWrapper`, `PsiTypedDefinitionWrapper`, `ScFunctionWrapper`, ... — Java-compatible light PSI that wraps a real Scala element |
| `compiled/` | `ScClsFileViewProvider`, `ScClassFileDecompiler`, `SigFileViewProviderFactory` — decompiled class files |
| `stubs/` | Stub interfaces, index keys, `ScStubElementType` |

### 2.2 Root traits

- `trait ScFile` (`scala/scala-api/src/lang/psi/api/ScFile.scala:9`) —
  `extends PsiFileWithStubSupport with PsiClassOwnerEx`. Adds `def isCompiled`.
- `trait ScalaFile` (`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/ScalaFile.scala:11`)
  — extends `ScFile`, `ScImportsHolder`, `ScExportsHolder`, `ScalaPsiElement`.
  Exposes `typeDefinitions`, `members`, `extensions`, `firstPackaging`,
  `packagingRanges`, `compilerOptions`, `topLevelWrapperObject`,
  `getContextModificationStamp`/`incContextModificationStamp`.
- `class ScalaFileImpl` (`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/ScalaFileImpl.scala:43`)
  — extends `PsiFileBase` and mixes in `ScalaFile`, `FileDeclarationsHolder`,
  `ScDeclarationSequenceHolder`, `ScControlFlowOwner`, `FileResolveScopeProvider`.
  The decompiled-class variant is the inner class
  `ScClsFileViewProvider.ScClsFileImpl` (`...compiled/ScClsFileViewProvider.scala:65`),
  which overrides `isCompiled = true` and supplies `compilerOptions` from TASTy.

### 2.3 The two PSI base classes

- `trait ScalaPsiElement` (`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/ScalaPsiElement.scala:15`)
  — extends `PsiElement` with `ProjectContextOwner`. **The hot interface**.
  Notably:
  - `getNode`-based lookup helpers (`ScalaPsiElement.scala:63-115`) —
    `findFirstChildByType`, `findChildrenByType`, `findLastChildByType`. These
    walk `getNode().getFirstChildNode()` chains and are everywhere in the
    codebase.
  - `acceptScala(visitor: ScalaElementVisitor)` dispatch hook
    (`ScalaPsiElement.scala:122`).
  - `context`/`child` user-data keys (`ScalaPsiElement.scala:134-135`) — a
    side-channel for synthetic elements that have no ASTNode parent.

- `abstract class ScalaPsiElementImpl(node: ASTNode)`
  (`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/ScalaPsiElementImpl.scala:25`)
  — extends IntelliJ's `ASTWrapperPsiElement`. Every "concrete-from-source"
  element subclasses this. Its `subtreeChanged` hook
  (`ScalaPsiElementImpl.scala:64-67`) bumps `ModTracker.anyScalaPsiChange`.

- `abstract class ScalaStubBasedElementImpl[T, S]`
  (`ScalaPsiElementImpl.scala:75`) — extends `StubBasedPsiElementBase[S]`,
  adding the `byStubOrPsi` / `byPsiOrStub` accessors
  (`ScalaPsiElementImpl.scala:120-129`) that all stub-aware PSI uses.

### 2.4 How `ASTNode`s become PSI

Two complementary paths:

1. **AST-driven**: `ScalaParserDefinitionBase.createElement`
   (`ScalaParserDefinitionBase.scala:11`) delegates to
   `ASTNodeToPsiElement.map(node)` (`parser/ASTNodeToPsiElement.scala:19`), a
   180-line pattern match on `ScalaElementType` constants. It returns
   `new ScReferenceExpressionImpl(node)`, `new ScInfixExprImpl(node)`, ... —
   there are ~140 entries.
2. **Stub-driven**: each `ScStubElementType` subtype implements
   `createElement(node: ASTNode)` and `createPsi(stub: S)` — see
   `ScalaElementType.scala:70-142` for the template-definition family
   (`ClassDefinition`, `TraitDefinition`, `ObjectDefinition`, `EnumDefinition`,
   `GivenDefinition`, ...).

Elements that opt out of the giant `ASTNodeToPsiElement` match implement
`SelfPsiCreator` (`parser/SelfPsiCreator.scala:6`) and are matched first
(`ASTNodeToPsiElement.scala:23`).

### 2.5 The `Sc*` interface surface

A new implementation has to provide (or re-implement) **at least** the
following top-level traits. All live under
`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/api/`:

- `ScalaFile` (`api/ScalaFile.scala:11`)
- Template definitions: `ScTypeDefinition` (`api/toplevel/typedef/ScTypeDefinition.scala:14`),
  `ScTypeDefinitionLike`, `ScClass`, `ScTrait`, `ScObject`, `ScEnum`,
  `ScGiven`, `ScGivenDefinition`, `ScGivenAlias`, `ScGivenAliasDeclaration`,
  `ScGivenAliasDefinition`, `ScEnumCase`, `ScDerivesClauseOwner`.
- Statements: `ScFunction`, `ScPatternDefinition`, `ScVariableDefinition`,
  `ScTypeAlias`, `ScExtension`, `ScMacroDefinition`.
- Toplevel: `ScPackaging`, `ScImportableDeclarationsOwner`,
  `ScImportStmt`, `ScImportExpr`, `ScImportSelector`, `ScExportStmt`.
- References / paths: `ScReference`, `ScStableCodeReference`,
  `ScConstructorInvocation`, `ScPathElement`, `ScPrimaryConstructor`.
- Expressions: `ScExpression`, `ScReferenceExpression`, `ScInfixExpr`,
  `ScBlockExpr`, `ScIf`, `ScMatch`, `ScFor`, `ScWhile`, `ScTry`, `ScReturn`,
  `ScThrow`, `ScAssignment`, `ScTuple`, `ScNamedTuple`, `ScGenericCall`,
  `ScMethodCall`, `ScSelfInvocation`, `ScUnderscoreSection`, `ScInterpolated*`.
- Literals: `ScLiteral`, `ScIntegerLiteral`, `ScLongLiteral`, `ScDoubleLiteral`,
  `ScFloatLiteral`, `ScBooleanLiteral`, `ScCharLiteral`, `ScStringLiteral`,
  `ScSymbolLiteral`, `ScNullLiteral`, `ScInterpolatedStringLiteral`.
- Patterns: `ScPattern`, `ScReferencePattern`, `ScNamingPattern`,
  ScTypedPattern`, `ScConstructorPattern`, `ScInfixPattern`, `ScTuplePattern`,
  `ScLiteralPattern`, `ScGivenPattern`, `ScNamedTuplePattern`,
  `Sc3TypedPattern`, `ScSeqWildcardPattern`, `ScQuotedPattern`.
- Type elements: `ScTypeElement`, `ScSimpleTypeElement`,
  `ScCompoundTypeElement`, `ScInfixTypeElement`, `ScParameterizedTypeElement`,
  `ScFunctionalTypeElement`, `ScExistentialTypeElement`, `ScLiteralTypeElement`,
  `ScTypeLambdaTypeElement`, `ScMatchTypeElement`, `ScPolyFunctionTypeElement`,
  `ScDependentFunctionTypeElement`, `ScNamedTupleTypeElement`,
  `ScCaptureTypeElement`, `ScQuotedType`, `ScRefinement`.
- Params: `ScParameter`, `ScClassParameter`, `ScTypeParam`, `ScParameterClause`,
  `ScTypeParameterClause`.
- Misc: `ScModifierListOwner`, `ScPolymorphicElement`, `ScTypeParametersOwner`,
  `ScAnnotations`, `ScAnnotation`, `ScAnnotationExpr`, `ScAccessModifier`,
  `ScSelfTypeElement`, `ScExtendsBlock`, `ScTemplateParents`, `ScTemplateBody`,
  `ScDerivesClause`, `ScEnd`.

There are also **806 `.scala` files** under `lang/psi/` — the surface is huge.
A real-world count of `Sc*` top-level public traits is well over 200.

### 2.6 `ScalaPsiBuilder`

`trait ScalaPsiBuilder extends PsiBuilder`
(`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/parser/parsing/builder/ScalaPsiBuilder.scala:7`)
is the parser's only handle to the outside world. It extends IntelliJ's
`PsiBuilder` with Scala-specific concerns:

- `isScala3`, `features: ScalaFeatures`, `isStrictMode`, `isMetaEnabled`,
  `isScala3IndentationBasedSyntaxEnabled` (`ScalaPsiBuilder.scala:35-43`).
- Newline tracking: `newlineBeforeCurrentToken`,
  `twoNewlinesBeforeCurrentToken`, `disableNewlines`/`enableNewlines`/
  `restoreNewlinesState` (`ScalaPsiBuilder.scala:9-25`).
- Indentation stack: `findPrecedingIndentation`, `currentIndentationRegion`,
  `pushIndentationRegion`/`popIndentationRegion`,
  `allPreviousIndentations` (`ScalaPsiBuilder.scala:45-53`).
- Quoted-pattern scope: `enterQuotedPattern`/`exitQuotedPattern`/`isInQuotedPattern`.
- Error-count tracking: `countDoneErrorsIn` (`ScalaPsiBuilder.scala:60`) —
  used for IJ's "reparse with fallback" heuristic.

The impl (`ScalaPsiBuilderImpl.scala:17`) extends `PsiBuilderAdapter` and is
~360 lines of bookkeeping (indentation stack, error marker stack, lazy feature
resolution).

---

## 3. Stub index system

### 3.1 Stub interfaces

`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/stubs/` defines
~25 stub interfaces: `ScFileStub` (`ScFileStub.scala:6`, just
`PsiClassHolderFileStub[ScalaFile]`), `ScFunctionStub` (`ScFunctionStub.scala:7`),
`ScPropertyStub`, `ScTypeAliasStub`, `ScPackagingStub`, `ScTemplateParentsStub`,
`ScImportStmtStub`, `ScImportExprStub`, `ScImportSelectorsStub`,
`ScAnnotationStub`, `ScModifiersStub`, `ScGivenStub`, `ScExtensionStub`,
`ScParamClauseStub`, `ScParamClausesStub`, `ScParameterStub`, `ScTypeParamStub`,
`ScEarlyDefinitionsStub`, `ScSelfTypeElementStub`, `ScBindingPatternStub`,
`ScFieldIdStub`, `ScEnumCasesStub`, `ScImplicitStub`, `ScMemberOrLocal`.

Each `Sc*Stub` is paired with an `Sc*ElementType` in
`lang/psi/stubs/elements/` (e.g. `ScFunctionElementType.scala`,
`ScTemplateDefinitionElementType.scala`, `ScPropertyElementType.scala`,
`ScPackagingElementType.scala`, `ScExtensionElementType.scala`, ...). All
element types extend `ScStubElementType[S, T]`
(`lang/psi/stubs/elements/ScStubElementType.scala:15`) which adds:

- a thread-local `Processing` flag (`ScStubElementType.scala:77-89`) used to
  detect recursion during stub creation;
- `indexStub(stub, sink)` — the hook that populates indices;
- `shouldCreateStub(node) = !isLocal(node)` — only "top-level-ish" elements
  are stubbed; expression-level and code-block element types are excluded
  (`ScStubElementType.scala:43, 66-75`).

The serialization format for each element type is hand-written (see
`ScFunctionElementType.serialize` at `ScFunctionElementType.scala:22-37` — it
writes name, isDeclaration, annotations, typeText, bodyText, hasAssign,
implicitConversionParameterClass, isLocal, implicitClassNames, isTopLevel,
topLevelQualifier, isExtensionMethod, isGiven, givenClassNames). The body
text is written as a fallback for stub-only PSI to re-parse on demand
(`ScFunctionElementType.scala:67-77`).

### 3.2 Index keys

All index keys live in `lang/psi/stubs/index/ScalaIndexKeys.scala:22-67`:

| Key | Element type | Use |
|---|---|---|
| `ALL_CLASS_NAMES` | `PsiClass` | completion |
| `SHORT_NAME_KEY` | `PsiClass` | short-name resolve |
| `NOT_VISIBLE_IN_JAVA_SHORT_NAME_KEY` | `PsiClass` | Java-interop filtering |
| `PACKAGE_OBJECT_SHORT_NAME_KEY` | `PsiClass` | package-object resolve |
| `METHOD_NAME_KEY` | `ScFunction` | method-by-name resolve, completion |
| `CLASS_NAME_IN_PACKAGE_KEY` | `PsiClass` | conflict detection |
| `JAVA_CLASS_NAME_IN_PACKAGE_KEY` | `PsiClass` | Java side |
| `IMPLICIT_OBJECT_KEY` | `ScObject` | implicit resolve |
| `ANNOTATED_MEMBER_KEY` | `ScAnnotation` | annotated-member search |
| `PROPERTY_NAME_KEY` / `PROPERTY_CLASS_NAME_KEY` | `ScValueOrVariable` | val/var resolve |
| `CLASS_PARAMETER_NAME_KEY` | `ScClassParameter` | named-arg completion |
| `TYPE_ALIAS_NAME_KEY` | `ScTypeAlias` | type-alias resolve |
| `STABLE_ALIAS_NAME_KEY` | `ScTypeAlias` | stable-alias resolve |
| `ALIASED_CLASS_NAME_KEY` | `ScTypeAlias` | aliased-class resolve |
| `SUPER_CLASS_NAME_KEY` | `ScExtendsBlock` | direct-inheritors index |
| `SELF_TYPE_CLASS_NAME_KEY` | `ScSelfTypeElement` | self-type resolve |
| `TOP_LEVEL_TYPE_ALIAS_BY_PKG_KEY` | `ScTypeAlias` | Scala 3 top-level |
| `TOP_LEVEL_VAL_OR_VAR_BY_PKG_KEY` | `ScValueOrVariable` | Scala 3 top-level |
| `TOP_LEVEL_FUNCTION_BY_PKG_KEY` | `ScFunction` | Scala 3 top-level |
| `TOP_LEVEL_IMPLICIT_CLASS_BY_PKG_KEY` | `ScClass` | Scala 3 top-level implicit class |
| `TOP_LEVEL_GIVEN_DEFINITIONS_BY_PKG_KEY` | `ScGivenDefinition` | Scala 3 top-level given |
| `TOP_LEVEL_EXTENSION_BY_PKG_KEY` | `ScExtension` | Scala 3 top-level extension |
| `TOP_LEVEL_EXPORT_BY_PKG_KEY` | `ScExportStmt` | Scala 3 top-level export |
| `ALIASED_IMPORT_KEY` | `ScImportSelector` | import rewriting |
| `CLASS_FQN_KEY` | `PsiClass` (CharSequence key) | FQN resolve |
| `PACKAGE_OBJECT_FQN_KEY` | `PsiClass` | package-object FQN |
| `PACKAGE_FQN_KEY` | `ScPackaging` | packaging lookup |
| `STABLE_ALIAS_FQN_KEY` | `ScTypeAlias` | stable alias FQN |
| `ANNOTATED_MAIN_FUNCTION_BY_PKG_KEY` | `ScFunction` | `@main` synthetic-class resolve |
| `IMPLICIT_CONVERSION_KEY` | `ScMember` | implicit-conversion resolve |
| `IMPLICIT_INSTANCE_KEY` | `ScMember` | implicit-instance resolve |
| `EXTENSION_KEY` | `ScExtension` | extension-method resolve |
| `GIVEN_KEY` | `ScGiven` | given resolve |

Each key has an `indexStub` implementation that decides which keys to emit
(see `ScFunctionElementType.indexStub` at `ScFunctionElementType.scala:114-140`
— emits `METHOD_NAME_KEY`, conditionally `TOP_LEVEL_FUNCTION_BY_PKG_KEY`,
`ANNOTATED_MAIN_FUNCTION_BY_PKG_KEY`, and delegates to `stub.indexImplicits`
and `stub.indexGivens`).

The classes registered against IntelliJ's `stubIndex` EP live alongside:
`ScClassNameInPackageIndex` (`ScClassNameInPackageIndex.scala:6`),
`ScClassFqnIndex`, `ScShortClassNameIndex`, `ScFunctionNameIndex`,
`ScAllClassNamesIndex`, `ScAnnotatedMemberIndex`, `ScPropertyNameIndex`,
`ScPropertyClassNameIndex`, `ScClassParameterNameIndex`, `ScTypeAliasNameIndex`,
`StableValIndex`, `ScAnnotatedMainFunctionIndex`, `ScDirectInheritorsIndex`,
`ScPackagingFqnIndex`, `ScPackageObjectFqnIndex`, `ScJavaClassNameInPackageIndex`,
`ScImplicitObjectKey`, `ScAliasedImportKey`, and the
`ScTopLevel*ByPackageIndex` family. Plus a separate `ImplicitIndex`,
`ImplicitConversionIndex`, `ImplicitInstanceIndex`,
`ExtensionIndex`, `ScGivenIndex`.

### 3.3 How stubs are consumed

`ScalaIndexKeys` defines two implicit classes
(`ScalaIndexKeys.scala:69-111`) that turn a `StubIndexKey[K, Psi]` into a
fluent `.elements(key, scope)` and `.allKeys` API. Consumers call these from
the resolve / completion paths. The stub tree is built by
`ScStubFileElementType.ScFileStubBuilderImpl`
(`lang/psi/stubs/elements/ScStubFileElementType.scala:35-45`) which walks the
PSI tree built by `ScalaParser` and serializes stubs into IntelliJ's
`SerializationManager`.

Stubs are used for:

- **Resolve**: e.g. looking up a class by FQN, a method by name+scope, a
  top-level definition by package — all go through `StubIndex.getElements`.
- **Completion**: the "all class names" key drives class-name completion.
- **Find usages**: `ScDirectInheritorsIndex`, `ScAnnotatedMemberIndex`,
  `ANNOTATED_MAIN_FUNCTION_BY_PKG_KEY` etc. enable usage search without
  parsing the whole project.
- **Refactoring**: rename, move — read stubs to find affected files.

`ScalaStubBasedElementImpl.byStubOrPsi` (`ScalaPsiElementImpl.scala:120`) is
the key idiom: when the AST is loaded, read from the AST; when only the stub
is in memory (typical for files outside the open set), read from the stub.
This is the **only** way the plugin can answer resolve queries across a
50k-file project without parsing every file.

---

## 4. PSI mutability, synthetic elements, injected members

### 4.1 `SyntheticMembersInjector` EP

Declared in `scala-plugin-common.xml:11` as a **dynamic** extension point
with qualified name `org.intellij.scala.syntheticMemberInjector` and
interface `SyntheticMembersInjector`
(`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/toplevel/typedef/SyntheticMembersInjector.scala:19`).

The base class exposes four hooks:

```scala
def injectFunctions(source: ScTypeDefinition): Seq[String]
def injectInners(source: ScTypeDefinition): Seq[String]
def needsCompanionObject(source: ScTypeDefinition): Boolean
def injectSupers(source: ScTypeDefinition): Seq[String]
def injectMembers(source: ScTypeDefinition): Seq[String]
```

Each returns **source text** that is parsed by
`ScalaPsiElementFactory.createMethodWithContext` /
`createTypeDefinitionWithContext` /
`createDefinitionWithContext`
(`scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/impl/ScalaPsiElementFactory.scala:1356-1365`).
The result is a real PSI element detached from any file (synthetic). Its
`syntheticNavigationElement` is set back to the source class
(`SyntheticMembersInjector.scala:99`). The factory also wires up `context` /
`child` user-data slots so the synthetic element can resolve as if it lived
inside the source template body (`SyntheticMembersInjector.scala:208-219`).

Registered injectors (`scala-plugin-common.xml:33-44`):

| Injector | Role |
|---|---|
| `CaseClassAndCompanionMembersInjector` | `apply`, `unapply`, `copy`, `_1.._22`, scala3 accessors |
| `AbstractTypeContextBoundsInjector` | implicit evidence for context bounds |
| `EnumMembersInjector` (`EnumMembersInjector.scala:11`) | `values`, `valueOf`, `fromOrdinal` on enum companions |
| `DerivesInjector` (`DerivesInjector.scala:9`) | synthesises `given` instances from `derives` clause |
| `QuasiQuotesInjector` | macro quote support |
| `MonocleInjector`, `ScalazDerivingInjector`, `CirceCodecInjector`,
  `NewTypeInjector`, `SimulacrumInjector`, `DerevoInjector`, `ScioInjector` | library-specific derivation |

`SyntheticMembersInjector.needsCompanion(source)`
(`SyntheticMembersInjector.scala:136-141`) is consulted during template
construction to decide whether to materialise a synthetic companion object.
The whole injector stack is evaluated lazily and cached against
`ModTracker.anyScalaPsiChange` (see `templateBodyOrSynthetic` at
`SyntheticMembersInjector.scala:208-219` which uses `cachedInUserData` with
`ModTracker.libraryAware(td)`).

### 4.2 Light PSI / compiled-element adapters

The `lang.psi.light.*` package (~20 classes) provides Java-compatible
wrappers around Scala PSI:

- `PsiClassWrapper` — exposes a `ScTypeDefinition` as a `PsiClass`.
- `PsiMethodWrapper`, `ScFunctionWrapper`, `StaticPsiMethodWrapper`,
  `StaticTraitScFunctionWrapper` — wrap `ScFunction` for Java resolve.
- `PsiTypedDefinitionWrapper`, `StaticPsiTypedDefinitionWrapper` — wrap
  `ScTypedDefinition`.
- `ScPrimaryConstructorWrapper`.
- `ScLightParameter`, `ScLightParameterList`, `ScLightTypeParam`,
  `ScLightField`, `ScLightModifierList`, `ScLightThrowsList`.

These are **LightElement** subclasses; they implement the full `PsiClass` /
`PsiMethod` / `PsiField` interface but have no ASTNode — their data is
derived from the underlying Scala PSI.

Compiled files go through `lang.psi.compiled.ScClsFileViewProvider`
(`ScClsFileViewProvider.scala:28`), which extends
`SingleRootFileViewProvider`. Its `ScClsFileImpl`
(`ScClsFileViewProvider.scala:65`) extends `ScalaFileImpl` with
`isCompiled = true`. The text comes from a decompiler
(`ScalaDecompilationResult`), and `compilerOptions` is populated from TASTy
(`ScClsFileViewProvider.scala:46, 72`). The decompiled element tree is
produced by `ScClassFileDecompiler` and a TASTy reader lives in
`scala/scala-impl/src/org/jetbrains/plugins/scala/tasty/`
(`TastyDecompiler.scala`, `TastyReader.scala`, `TastyFileStubBuilder.scala`).

There is **no** `PsiCompiledElement`-style fast path for Scala 3 source
files today — every open `.scala` file goes through the full parser + lexer.

---

## 5. What it would mean to back PSI with a Scala 3 `Tree`

### 5.1 Read sites that need a position-translation layer

The base `ScalaPsiElement` and its impl class reach for the AST in many
places. A representative grep:

- `ScalaPsiElement.scala:64, 72, 80, 92, 101` —
  `getNode.getFirstChildNode`/`getLastChildNode` walks (the
  `findFirstChildByType` / `findChildrenByType` / `findLastChildByType`
  family).
- `ScalaPsiElementImpl.scala:51, 59, 114` —
  `getParent.getNode.replaceChild(getNode, ...)` for `replace`/`delete`.
- `ScalaPsiElementImpl.scala:105-108` —
  `getStubOrPsiChildren(filter, f)` asserts that every passed token type is
  an `IStubElementType` (this is the stubbed-children API).
- `ASTNodeToPsiElement.scala:19-178` — the giant pattern match from element
  type to PSI impl.
- `ScFunctionElementType.scala:61-77` —
  `returnTypeElement.map(_.getText)` / `body.getText` during stub creation.
  `.getText` on a `StubBasedPsiElement` reads from the underlying ASTNode,
  which means the AST must be retained for any element whose stub creation
  reaches back into PSI text.

Hot external reads of `getText` / `getTextRange` / `getStartOffset` /
`getEndOffset` / `getTextOffset` are pervasive — completion, annotator,
folding, formatting, find-usages, refactoring, indexes. They all assume
that a PSI element's source span is a contiguous slice of the file text.

A `tpd.Tree` (or `untpd.Tree`) from `dotc` carries a `SourcePosition`
(`dotc`'s `util.Spans.Span`) which is **almost** a text range, with two
important differences:

1. Trees don't always carry their source (synthetic and inline-spliced
   trees often have `NoSpan`).
2. `dotc`'s positions are *partial* — they may cover only the "header" of a
   construct (e.g., a `def`'s name and signature, not its body) or be
   union/transparent positions.

A shadow PSI backed by `dotc.Tree` therefore needs a **position-translation
layer** that maps `(Tree, TextRange)` to `(Tree, Span)` and back. The
plugin already has the inverse of this problem in
`ScClsFileViewProvider.ScClsFileImpl` (`ScClsFileViewProvider.scala:65`),
which fabricates a textual PSI from a decompiled result; and in
`ScalaPsiElementImpl`'s `context`/`child` user-data slots
(`ScalaPsiElementImpl.scala:140-142`), which exist precisely because some
PSI elements (synthetics, light wrappers) live outside the AST tree.

### 5.2 Caching and invalidation hooks

The plugin's cache vocabulary lives in
`scala/scala-impl/src/org/jetbrains/plugins/scala/caches/`:

- `ModTracker` (`caches/ModTracker.scala:11`):
  - `anyScalaPsiChange: SimpleModificationTracker` (`:23`) — bumped on every
    PSI mutation via `ScalaPsiElementImpl.subtreeChanged()`
    (`ScalaPsiElementImpl.scala:64-67, 131-134`) and
    `ScalaFileImpl.subtreeChanged()` (`ScalaFileImpl.scala:397-402`).
  - `physicalPsiChange(project)` — delegates to `PsiModificationTracker`.
  - `libraryAware(element)` (`:28-35`) — `ProjectRootManager` for compiled
    library files, otherwise `BlockModificationTracker(element)`.
- `cached(name, dependency, f)` (`caches/package.scala:13`) — a
  function-level cache keyed by `dependency`'s modification count.
- `cachedInUserData(name, holder, dependency, v)(f)` (`package.scala:58`) —
  caches a value in the PSI element's `UserDataHolder`.
- `cachedWithRecursionGuard` (`package.scala:63-67`).

For a `dotc`/`pc`-backed PSI, the natural invalidation dependency is "pc's
`Interactive` driver retype-checked this file." The plugin already has a
close analogue: `ScalaFile.getContextModificationStamp` /
`incContextModificationStamp` (`ScalaFile.scala:50-51`,
`ScalaFileImpl.scala:419-425`) — a per-file stamp that can be bumped
without changing file text. A Metals-PC integration should expose a
`pc driver revision` as a `ModificationTracker` and feed it into all the
existing `cached(...)` call-sites via a new variant alongside
`ModTracker.anyScalaPsiChange`.

### 5.3 `ScFile` creation from a VirtualFile

The flow today:

1. IntelliJ calls `ScFileViewProviderFactory.createFileViewProvider`
   (`scala/scala-api/src/lang/psi/ScFileViewProviderFactory.scala:10`) →
   `new ScFileViewProvider(...)`.
2. The view provider creates a `PsiFile` via `createFile`
   (`scala/scala-api/src/lang/psi/ScFileViewProvider.scala:27-32`), which
   dispatches to `ParserDefinition.createFile(viewProvider)`
   (`ScalaParserDefinition.scala:22` = `new ScalaFileImpl(viewProvider)`).
3. IntelliJ's `PsiFileImpl` then calls `ParserDefinition.createParser`
   (`ScalaParserDefinition.scala:17` = `new ScalaParser(false)`) and runs
   it on the file's text.
4. The resulting `ASTNode` tree is attached to the file; `PsiElement.getNode`
   walks it.

For a `dotc`-backed Scala 3 PSI, the seam lives at **two** places:

- `Scala3ParserDefinition.createParser(project)` should return a parser
  whose `parseLight` populates a shadow tree by walking a `tpd.Tree` from
  the `pc` driver. The `ScalaPsiBuilder` API is no longer needed for Scala
  3 — its concerns (indentation regions, soft modifiers, braceless
  regions) are `dotc`'s job.
- `Scala3ParserDefinition.createFile(viewProvider)` should return a
  `ScalaFileImpl` subclass that owns a `pc` `Interactive` driver, lazily
  typechecks on demand, and produces children on `getChildren` /
  `findChildrenByClass` from the driver's trees.

The existing `ScClsFileImpl` (`ScClsFileViewProvider.scala:65`) is a
precedent for a `ScalaFileImpl` subclass that fabricates children from a
non-AST source; it could be generalized.

### 5.4 Stubbing without parsing

This is the highest-value lever. `pc` already produces a `SemanticDB`-like
`Symbol` index from the `dotc` frontend; TASTy files contain the same data
post-compile. A Metals-PC integration can populate the stub tree directly
from `pc`'s `Symbol`s:

| Stub field | Source in `dotc`/`pc` |
|---|---|
| class/trait/object name, FQN | `Symbol.name`, `Symbol.fullName` |
| type parameters | `Symbol.info.polyParam` / `TypeParamInfo` |
| parameter clauses | `info` / `methodParamTypes` |
| parents | `ClassInfo.parents` |
| `derives` clause | resolved via `dotc`'s `Deriving` symbol |
| annotations | `Symbol.annotations` (`Annotation.symbol`) |
| implicit/given flags | `Symbol.flags` (`Flags.Implicit`, `Flags.Given`) |
| top-level packaging | `Symbol.owner` chain |
| exports | `dotc`'s `Export` symbols (forwarded through `ExportedForwarders`) |

A new `ScStubFileElementType` for Scala 3 (or a sibling
`ScPcStubFileElementType`) could short-circuit
`DefaultStubBuilder.buildStubTree` and instead ask the `pc` indexer for a
symbol snapshot. **Crucially**, this means stubs are populated without
running the plugin's hand-written parser on **any** Scala 3 file — even
ones outside the open editor. This is the same trick `ScClsFileImpl` plays
for class files today, and it's the only realistic path to a usable
find-usages/rename experience.

---

## 6. Current Scala 3 PSI limitations

Concrete symptoms of the hand-written grammar drift (sourced from grep of
the parser sources and the testdata):

- **Indentation-region bookkeeping** is fragile. The
  `IndentationRegionHolder` in `ScalaPsiBuilderImpl.scala:334-355` carries
  an ad-hoc rollback list to undo premature indentation pushes; comments
  around `ScalaPsiBuilderImpl.scala:214-225` explicitly document an
  infix-expression edge case. This re-implements logic `dotc` already has
  in `dotc`'s `Parsers.scala`.
- **`extension` with `:`** is documented as broken: a comment at
  `Extension.scala:57-59` says "TODO: colon is not available in extension
  methods; we could still parse it and add an error in Annotator."
- **Match types** are parsed by `MatchTypeSuffix.scala` +
  `TypeCaseClauses.scala`; match-type **reduction** is implemented
  separately in `ScMatchType.scala` with a depth limit of 50 (see the
  companion report `02-type-system-resolve.md`). The grammar itself
  mishandles optional braces in some edge cases — the parser has both
  `CaseClausesInIndentationRegion.scala` and
  `BlockInIndentationRegion.scala` as separate rule objects, an
  indication of the special-casing needed.
- **Named tuples** required adding both
  `TupleOrNamedTupleOrParenthesizedType.scala` (types) and
  `ScNamedTuplePatternComponentImpl` (patterns) — the grammar was extended
  piecemeal.
- **Type lambdas / polymorphic function types** share an ambiguous parser
  entry: `PolyFunOrTypeLambda.scala` exists precisely because the syntax
  `[A] =>> A` vs. `[A] => (a: A) => a` cannot be disambiguated without
  lookahead, and the plugin's lookahead is limited to `PsiBuilder.predict`.
- **Capture sets / capability calculus** (`cc/CaptureRef.scala`,
  `cc/CaptureSet.scala`, `cc/CaptureFilter.scala` in
  `parser/parsing/types/`) are reimplemented from the experimental Scala
  3 capture calculus; these will keep moving target.
- **`end` markers** (`parser/parsing/base/End.scala`) are threaded through
  ~10 parsing rules by hand; the rule that they may close any region with
  a matching name is re-derived in each rule.
- **Macro quotes / splices** (`Quoted.scala`,
  `ScQuotedBlockImpl`, `ScQuotedTypeImpl`) are a hand-written grammar
  that doesn't model `dotc`'s quasiparse semantics.
- **Soft modifiers** (`SoftModifier.scala`) are detected via
  `builder.tryParseSoftKeywordWithRollbackMarker` — a heuristic that
  backtracks. `dotc` does this in the scanner.
- **The `failed/` parser testdata** (`testdata/parser/failed/`) currently
  contains `SCL13088.test`, `SCL3419.test`, `SCL3915.test`, `SCL4302.test`
  — long-standing parser regressions that were too expensive to fix
  in-tree and were instead quarantined.

Each of these is a class of bug that disappears if the parser is `dotc`.

---

## 7. Seam recommendations

### 7.1 Shadow PSI backed by `tpd.Tree`, with a typed/untyped split

The user's editor buffer is, by definition, **untyped** — it may not
typecheck at all. A `pc`-backed PSI therefore needs two layers:

1. **Syntactic layer**: `untpd.Tree` produced by `dotc`'s `Parser`. This is
   always available, always correct, and matches the source byte-for-byte.
   `dotc` parses very fast; running it on each keystroke is what `pc`
   already does.
2. **Semantic layer**: `tpd.Tree` produced by `pc`'s `Interactive`
   re-typecheck. This is the source of truth for types and resolve, and
   is invalidated and re-computed in the background.

The PSI should be backed by the syntactic layer for everything text-range,
structure, and stub-related; resolve and type-related queries should be
answered by lazily reaching into the semantic layer (with a "typecheck in
progress, fall back" path).

Concretely, introduce:

- `Scala3PcFile` extends `ScalaFileImpl`, owning a
  `scala.meta.internal.pc.PcCollector` / `InteractiveDriver`. Its
  `subtreeChanged` is overridden to schedule a debounced retypecheck
  rather than unconditionally bumping `ModTracker.anyScalaPsiChange`.
- A `Scala3PsiElement` mix-in that overrides the `getNode`-based helpers
  in `ScalaPsiElement.scala:63-115` to walk a `tpd.Tree`'s children
  instead. AST is still required for editing operations (see below).
- A `SpanIndex` over the file that translates `dotc`'s `Span` into
  IntelliJ `TextRange` on demand.

### 7.2 Editing path: keep a thin AST shim

For **mutating** operations (`replace`, `delete`, `rename-in-place`), keep
a minimal AST produced by `dotc`'s `untpd.Parser` and serialised back via
`dotc`'s pretty-printer (`scala.meta.prettyprinters` is unrelated; we
mean `dotc`'s `Show`). The current implementation's `replace`
(`ScalaPsiElementImpl.scala:49-53`) mutates the AST and lets IntelliJ's
document-commit cycle rebuild PSI; we keep that contract. Editing is
**rare** relative to read-only PSI access, so a slow edit path is fine.

### 7.3 Stubs from `pc`'s symbol index, not from our parser

The biggest win. Replace the stub-building path for Scala 3 files:

- New EP subclass `ScPcStubFileElementType extends IStubFileElementType`
  wired into `Scala3ParserDefinition.FileNodeType`.
- Its `DefaultStubBuilder.buildStubTree` override asks the `pc` indexer
  (which already runs during `pc`'s `Interactive` typecheck) for a
  snapshot of `Symbol`s in the file, and synthesises `Sc*Stub` objects
  directly. The plugin's `ScalaParser` is not invoked at all.
- Stale stubs are invalidated when `pc`'s driver rev changes (which is
  already what `pc` does for its own `SemanticdbTextDocument` path).

This is the only realistic way to populate `ScClassNameInPackageIndex`,
`ScFunctionNameIndex`, the top-level-* family, and the implicit/given
indices across a 50k-file Scala 3 codebase without re-parsing every file.

### 7.4 Roll-out order

1. Ship the **syntactic layer** first: a `Scala3PcFile` that uses `dotc`'s
   `Parser` to populate the PSI tree. This immediately fixes every issue
   in §6, removes ~20 `isScala3` branches from the parser, and replaces
   ~6000 lines of `ParsingRule` code. Resolution and types still use the
   hand-written engine.
2. Ship **stubs from `pc`**: the `ScPcStubFileElementType` above. This
   removes the plugin's parser from the index path entirely for Scala 3.
3. Wire the **semantic layer** as described in the companion report
   (`02-type-system-resolve.md`), letting resolve and types fall through
   to `pc`.

At each stage, the `Scala 2` file path is untouched — it keeps using
`ScalaParserDefinition` + `ScalaParser`.

### 7.5 What can be deleted

When step 3 lands, the following become dead code for Scala 3:

- All of `lang/parser/parsing/**/*.scala` (174 files in `parser/`,
  ~1355 lines just in `parsing/*.scala` + `statements/`).
- `_ScalaCoreLexer.flex` / `_ScalaCoreLexer.java` Scala-3 paths (the lexer
  stays for Scala 2).
- `ScalaPsiBuilder` / `ScalaPsiBuilderImpl` for Scala 3.
- The hand-written `IndentationRegion` / `IndentationWidth` machinery.
- All the Scala-3-only `Sc*ElementType` registration in
  `ScalaElementType.scala:37-148` (EXTENSION, GIVEN_*, EnumCases,
  NAMED_TUPLE_*, CAPTURE_*, MATCH_TYPE, etc.) — replaced by PSI produced
  from `dotc` trees.

### 7.6 Risks

- **Performance of `dotc` on each keystroke.** `pc` already does this; we
  reuse its scheduler. Cold-parse latency is the concern; mitigations
  include lazy children (only parse the changed region) and
  `ScCodeBlockElementType`-style reparse-customisation
  (`parser/ScCodeBlockElementType.scala:13` — currently an
  `IErrorCounterReparseableElementType`, which is a contract we can keep).
- **Stub versioning.** `ScStubFileElementType.getStubVersion`
  (`ScStubFileElementType.scala:17-18`) must be bumped whenever the `pc`
  symbol-shape contract changes — e.g., on `pc` version upgrade. This is
  already done for the decompiler (`ScClassFileDecompiler.ScClsStubBuilder.getStubVersion`).
- **Synthetic members.** The `SyntheticMembersInjector` EP returns **source
  text** that is parsed by the plugin (`SyntheticMembersInjector.scala:96`).
  For Scala 3, the injectors should instead return `dotc.Tree` or
  `Symbol`-shaped data; the existing text-based API can be retained as a
  fallback but is a perf and correctness cliff (it re-runs the hand parser).
- **Java interop.** `PsiClassWrapper` and friends consume the PSI through
  `ScalaPsiElement`'s `getNode` helpers. These need to keep working with
  the shadow PSI — i.e., the `Scala3PsiElement` overrides must satisfy
  the existing `findChildrenByClass` / `getStubOrPsiChildren` contracts
  without leaking `dotc` types.

---

## Appendix: file inventory

- `lang/parser/` — 174 `.scala` files (parser rules, element types, builder).
- `lang/lexer/` — 15 files; JFlex grammar `_ScalaCoreLexer.flex` (646 lines)
  generates `_ScalaCoreLexer.java` (2361 lines).
- `lang/psi/` — 806 `.scala` files (api + impl + stubs + light + compiled + fake).
- `lang/psi/stubs/` — ~25 stub interfaces, ~30 `Sc*ElementType`s, ~25
  `Sc*Index` extension classes, `ScalaIndexKeys` with 33 keys.
- `caches/` — `ModTracker`, `cached`, `cachedInUserData`,
  `cachedWithRecursionGuard`, `BlockModificationTracker` — the existing
  invalidation vocabulary.
- `tasty/` — TASTy file support already in-tree (`TastyDecompiler`,
  `TastyReader`, `TastyFileStubBuilder`) — a useful precedent for the
  proposed `ScPcStubFileElementType`.
