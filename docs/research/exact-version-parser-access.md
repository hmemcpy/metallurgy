# Exact-version Scala 3 parser access

## Question

Which published and typed-structural entry points can synchronously parse exact source across representative Scala 3
releases and a current nightly, which neutral values can cross the isolated classloader, and where is raw reflection
unavoidable?

## Decision

There is no published parser operation in either Scalameta's `PresentationCompiler` interface or
`scala3-interfaces`. The exact `scala3-compiler_3` artifact does expose a viable synchronous parser implementation:

```text
exact compiler artifact
  -> option-faithful Context through Driver.setup
  -> SourceFile.virtual over the verbatim text
  -> Parsers.Parser.parse
  -> untyped tree products + parser diagnostics
  -> neutral host-owned snapshot
```

This must be a parser-only bridge separate from the presentation-compiler bridge. It must be selected by executable
capabilities, not a Scala version check.

Most operations after bootstrap can be expressed as typed structural protocols with primitive, `String`, or `AnyRef`
results. Raw reflection remains necessary inside the bridge for module discovery, constructors, and calls whose JVM
descriptors contain exact-loader classes. The raw boundary is small but cannot be eliminated by replacing an
exact-loader parameter type with `AnyRef`; that changes the JVM descriptor and does not match the method.

Dotc does **not** return a standalone, lossless CST object. It returns an untyped AST whose nodes have source spans,
and its scanner exposes tokens while scanning. The guaranteed parser input for PSI composition is therefore:

```text
verbatim source + option-faithful untyped tree + parser diagnostics
```

Tokens and comments are an additional capability. A separately replayed scanner is not yet proven equivalent to the
parser-owned scanner for language-import state, XML recovery, and all virtual indentation tokens. Lossless token and
trivia capture requires its own proof before the production catalog can depend on it.

## Published interface result

The exact `mtags-interfaces` dependency used by Metallurgy is `1.3.4`. Its
[`PresentationCompiler`](https://github.com/scalameta/metals/blob/v1.3.4/mtags-interfaces/src/main/java/scala/meta/pc/PresentationCompiler.java)
surface exposes asynchronous editor operations such as completion, hover, semantic tokens, diagnostics through
`didChange`, and SemanticDB. It does not expose an untyped tree, parser, scanner, or whole-file concrete-syntax
snapshot.

The `scala3-interfaces` jars inspected for every version below contain compiler callbacks, diagnostics, reporters,
source files, and positions. They contain no parser interface. Dotc's
[`Driver.process`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/Driver.scala#L121-L174)
is explicitly designed for reflective cross-version compilation, but it runs compilation and does not return an
untyped tree through the published callback. It is not the synchronous parser seam.

The conclusion is precise:

- published Scalameta parser operation: unavailable;
- published dotc-interface parser operation: unavailable;
- public JVM implementation surface in `scala3-compiler_3`: available and usable through an isolated compatibility
  bridge.

## Exact artifact probes

The following artifacts were fetched and executed:

| Line | Exact artifact | Valid whole-file parse | Invalid-edit recovery | Tree-product walk | Scanner primitives |
|---|---|---:|---:|---:|---:|
| Scala 3 LTS | `3.3.7` | yes | `PackageDef`, 2 parser errors | 15 nodes | yes |
| Scala 3.5 baseline | `3.5.2` | yes | `PackageDef`, 2 parser errors | 15 nodes | yes |
| Project compiler | `3.7.4` | yes, including named type arguments | `PackageDef`, 2 parser errors | 15 nodes | yes |
| Current nightly on 2026-07-26 | `3.10.0-RC1-bin-20260726-a036a3a-NIGHTLY` | yes, including named type arguments | `PackageDef`, 2 parser errors | 15 nodes | yes |

The nightly coordinate came from the official
[`maven-nightlies` metadata](https://repo.scala-lang.org/artifactory/maven-nightlies/org/scala-lang/scala3-compiler_3/maven-metadata.xml).
The release artifacts came from Maven Central. All probes instantiated compiler classes from the exact artifact
classpath and performed parsing synchronously without calling `InteractiveDriver.run`, `Compiler.newRun`, or a typer
phase.

The common valid probe:

```scala
object A:
  def f[T](using x: T) = x
```

produced the same root and ordered product-field walk in all four artifacts:

```text
PackageDef[pid,stats]
Ident[name]
ModuleDef[name,impl]
Template[constr,preParentsOrDerived,self,preBody]
DefDef[name,paramss,tpt,preRhs]
TypeTree[]
EmptyTree[trees]
EmptyValDef[name,tpt,preRhs]
DefDef[name,paramss,tpt,preRhs]
TypeDef[name,rhs]
TypeBoundsTree[lo,hi,alias]
ValDef[name,tpt,preRhs]
Ident[name]
TypeTree[]
Ident[name]
```

This is evidence for a portable product traversal, not evidence that the set of node kinds is frozen. New language
features add tree types and fields; the production catalog must discover and classify them rather than maintain a
version-indexed inventory.

The invalid probe was represented verbatim:

```scala
object A:
  def broken(
```

Every artifact returned a recovery `PackageDef` and reported two parser errors. Parser recovery is therefore available
synchronously and independently of typing.

## Capability shapes

### Common structural surface

These JVM operations were present across all four artifacts:

```text
ContextBase()
ContextBase.initialCtx(): Context

Driver()
Driver.setup(String[], Context): Option[(List[AbstractFile], Context)]

StoreReporter(Reporter, boolean)
Reporter.errorCount(): int
Reporter.warningCount(): int
Reporter.allErrors(): scala.collection.immutable.List
Reporter.allWarnings(): scala.collection.immutable.List

Parsers$Parser.parse(): Trees$Tree
Parsers$Parser.in(): Scanners$Scanner

Scanner.token(): int
Scanner.offset(): int
Scanner.lastOffset(): int
Scanner.name(): SimpleName
Scanner.strVal(): String
Scanner.base(): int
Scanner.nextToken(): void
Scanner.comments(): scala.collection.immutable.List

Tree.span(): long
Product.productPrefix(): String
Product.productArity(): int
Product.productElement(int): Object
Product.productElementName(int): String
Product.productIterator(): scala.collection.Iterator

Spans$Span$.exists$extension(long): boolean
Spans$Span$.start$extension(long): int
Spans$Span$.end$extension(long): int
Spans$Span$.point$extension(long): int
Spans$Span$.isSourceDerived$extension(long): boolean
```

The corresponding sources are:

- parser and parser-owned scanner:
  [`3.3.7`](https://github.com/scala/scala3/blob/3.3.7/compiler/src/dotty/tools/dotc/parsing/Parsers.scala#L191-L204),
  [`3.5.2`](https://github.com/scala/scala3/blob/3.5.2/compiler/src/dotty/tools/dotc/parsing/Parsers.scala#L192-L205),
  [`3.7.4`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/parsing/Parsers.scala#L197-L210);
- compiler-owned invocation:
  [`ParserPhase`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/parsing/ParserPhase.scala#L25-L39);
- direct parser test setup:
  [`ModifiersParsingTest`](https://github.com/scala/scala3/blob/3.7.4/compiler/test/dotty/tools/dotc/parsing/ModifiersParsingTest.scala#L16-L22);
- scanner state:
  [`Scanners.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/parsing/Scanners.scala#L38-L104);
- untyped tree products:
  [`Trees.scala`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/ast/Trees.scala#L55-L122).

### Observed construction variants

The released artifacts expose:

```text
SourceFile$.virtual(String, String, boolean): SourceFile
Parsers$Parser(SourceFile, Context)
```

The current nightly exposes:

```text
SourceFile$.virtual(String, String): SourceFile
Parsers$Parser(SourceFile, int startFrom, int limit, Context)
Parsers$Parser$.$lessinit$greater$default$2(): int
Parsers$Parser$.$lessinit$greater$default$3(): int
```

The nightly source introduces range-aware construction:
[`Parsers.scala`](https://github.com/scala/scala3/blob/a036a3a/compiler/src/dotty/tools/dotc/parsing/Parsers.scala#L196-L207).
Its virtual-source factory removes the `maybeIncomplete` parameter:
[`SourceFile.scala`](https://github.com/scala/scala3/blob/a036a3a/compiler/src/dotty/tools/dotc/util/SourceFile.scala#L201-L208).

These are two executable capability shapes:

- `WholeSourceParser`: whole-source constructor with a name/content source factory whose third argument controls
  incomplete-source handling;
- `RangeSourceParser`: range-aware constructor with compiler-provided defaults and a two-value source factory.

The names describe behavior, not releases. Discovery must validate exact parameter classes, default accessors, and
return classes from the isolated loader. A new artifact may satisfy either shape or a future shape without changing
the selection policy.

Do not use `Parsers.parser(source)` as the whole-file entry point even when it exists. That helper selects a
`ScriptParser` for self-contained sources, while the compiler parser phase directly constructs `Parsers.Parser`.

## Option-faithful context

`ContextBase.initialCtx` is enough for a minimal parser test but is not the module's language contract. Parser behavior
depends on exact `-source`, `-language`, indentation, migration, rewrite, and experimental settings.

`Driver.setup` was structurally identical and executed successfully in every probed artifact. Its implementation
creates a fresh context, distills the exact arguments, installs the returned settings state, initializes compiler
position support, and returns the configured context:

- [`3.3.7 Driver.setup`](https://github.com/scala/scala3/blob/3.3.7/compiler/src/dotty/tools/dotc/Driver.scala#L73-L101)
- [`3.5.2 Driver.setup`](https://github.com/scala/scala3/blob/3.5.2/compiler/src/dotty/tools/dotc/Driver.scala#L79-L107)
- [`3.7.4 Driver.setup`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/Driver.scala#L79-L107)
- [`current-nightly Driver.setup`](https://github.com/scala/scala3/blob/a036a3a/compiler/src/dotty/tools/dotc/Driver.scala#L78-L108)

The parser bridge should pass the module's exact compiler options plus a synthetic filename used only by
`Driver.setup` to validate source-required command usage. The source itself remains the unmodified string supplied to
`SourceFile.virtual`; it is never wrapped, renamed, or relocated.

`InteractiveDriver.currentCtx` is not the parser bootstrap. Constructing an interactive driver initializes compiler
run state, and `run` performs typing. The parser bridge needs only `Driver.setup` and direct parser construction.

Each parse creates a fresh context, reporter, source, and parser. A `ContextBase` is mutable and checks thread
ownership; it must not be shared across IntelliJ parsing or indexing threads. The exact artifact classloader and
validated method descriptors may be cached.

## Neutral export boundary

No compiler tree, source, context, name, span wrapper, reporter, Scala collection, iterator, `Class`, `Method`, or
`MethodHandle` may appear in the returned snapshot.

The parser-only bridge can safely export host-owned values with this information:

```text
ParserSyntaxSnapshot
  source text, length, and content digest
  root node id
  ordered ParserSyntaxNode values
  parser diagnostics
  optional scanner evidence

ParserSyntaxNode
  stable snapshot-local id
  runtime product prefix
  start, point, end
  source-derived / synthetic / absent-span classification
  ordered named fields

ParserSyntaxField
  field name
  tree reference, optional tree, ordered trees, nested ordered groups,
  recognized name text, primitive scalar, or explicitly unsupported value

ParserToken
  canonical token label, start, end
  optional identifier/literal text
  physical or virtual classification

ParserDiagnostic
  severity, message, start, point, end
```

Named product fields and nested collection boundaries are required. The current flat parent-id DTO loses parameter
clause grouping, optionality, and the role of individual children. A field whose loader-owned value is not explicitly
recognized is recorded as unsupported; it is never converted through an arbitrary `toString` and treated as grammar.

Numeric token ids are not part of the neutral contract. Token labels and exact source slices are exported because
numeric assignments may evolve. Token end positions must be captured after advancing the scanner: `offset` is the
current token start, while the next scanner state exposes the preceding token's end through `lastOffset`.

All child Scala collections are iterated inside the bridge and copied into host builders. The finished DTO can be
validated recursively to prove that every value is loaded by the bootstrap or plugin classloader.

## Structural access and raw reflection

Typed structural protocols are the primary access style after capability discovery:

```scala
type ParserValue = {
  def parse(): AnyRef
  def in(): AnyRef
}

type ProductValue = {
  def productPrefix(): String
  def productArity(): Int
  def productElement(index: Int): AnyRef
  def productElementName(index: Int): String
}

type ScannerValue = {
  def token(): Int
  def offset(): Int
  def lastOffset(): Int
  def nextToken(): Unit
}
```

The actual implementation may wrap these protocols in cached call sites so lookup failures are discovered during
capability preparation, not during arbitrary file parses.

Raw reflection is unavoidable for:

- loading exact classes and Scala module `MODULE$` values;
- constructing `ContextBase`, `Driver`, and `Parsers.Parser`;
- choosing between validated parser/source construction shapes;
- invoking methods whose descriptors contain exact-loader `Context`, `SourceFile`, `SettingsState`, reporter, Scala
  collection, or tree classes;
- optional node-specific fields whose stable product representation is insufficient.

Raw fallback is permitted only inside the parser bridge. It resolves semantic roles by exact parameter and return
shapes, never by artifact version, bytecode fingerprint, or an unconditional implementation-class table. A fallback
shape becomes available only after the same executable self-test as the structural path.

## Capability preparation and failure policy

Parser capability preparation runs before the stub-bearing ready language is selected:

1. resolve the exact compiler artifact independently from presentation-compiler availability;
2. create the isolated classloader and set it as the thread context classloader for every compiler interaction;
3. discover context setup, source construction, parser construction, tree products, span decoding, reporter access,
   and optional scanner capabilities;
4. parse a fixed valid source twice and require identical neutral snapshots;
5. parse a fixed incomplete source and require a non-null recovery root plus parser diagnostics;
6. verify ordered in-range spans, terminating traversal, and a DTO with no exact-loader values;
7. publish `Ready` only after the complete required capability passes.

Tree parsing, product fields, spans, and parser diagnostics are required. Scanner evidence is not marked required until
lossless parser-coupled capture is proven. Missing optional evidence is reported by capability name.

If required discovery or self-testing fails, the parser capability is unavailable. The file remains in the neutral,
non-stub-bearing pending language and the project reports the exact failed capability. It must not fall back to bundled
Scala parsing, asynchronously replace syntax, or select behavior from a version allowlist.

## Implementation boundary

The implementation program should introduce:

- `Scala3ParserBridge`: parser-only interface returning neutral snapshots;
- `StructuralScala3ParserBridge`: private exact-loader implementation with the isolated raw bootstrap;
- `Scala3ParserCapabilities`: named executable capabilities and failure details;
- independent compiler-artifact readiness in the module lifecycle;
- a cache keyed by exact artifact identity, compiler-option identity, file URI, and source content digest;
- a fresh per-parse compiler context and reporter;
- cold/warm/restart probes covering every capability shape.

`Scala3PcBridge`, `InteractiveDriver`, `PcSnapshot`, and semantic document generations remain asynchronous semantic
infrastructure. They do not provide, install, or reload parser syntax.

The production catalog can consume only the neutral parser snapshot. It must not see compiler objects, reflection
handles, artifact coordinates as behavior switches, or presentation-compiler session state.

## Reproduction

Release artifacts:

```bash
cs fetch --classpath org.scala-lang:scala3-compiler_3:3.3.7
cs fetch --classpath org.scala-lang:scala3-compiler_3:3.5.2
cs fetch --classpath org.scala-lang:scala3-compiler_3:3.7.4
```

Current nightly:

```bash
cs fetch --classpath \
  -r https://repo.scala-lang.org/artifactory/maven-nightlies \
  org.scala-lang:scala3-compiler_3:3.10.0-RC1-bin-20260726-a036a3a-NIGHTLY
```

Binary shapes were inspected with `javap`. Executable probes used JShell with each exact Coursier classpath so even the
nightly constructor change could be tested without compiling against a different dotc implementation. Each probe:

1. constructed `ContextBase`;
2. invoked `Driver.setup` with exact options and a filename;
3. constructed `SourceFile` and `Parsers.Parser` through the discovered shape;
4. invoked `parse`;
5. traversed tree products and named fields;
6. decoded parser error counts;
7. separately inspected scanner token labels and offsets.

The standard scanner probe produced the same physical token labels and offsets in `3.3.7` and the current nightly,
including `using` as an identifier token. That result reinforces the need to combine raw text and tree role for soft
keywords rather than treating scanner labels as complete grammar.
