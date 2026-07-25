# Verbatim PSI fragments with dotc-authoritative typing

## Decision

Use a test-fixture **compilation projection**: IntelliJ parses the upstream snippet verbatim, while dotc receives a
compiler-only source in which each illegal top-level `ScExpression` is made the initializer of a synthetic top-level
value. The document's original characters remain unchanged and in order:

```scala
val t = Tuple1(1)
private val $metallurgy$fragment$0 = /*start*/t/*end*/
```

and:

```scala
private val $metallurgy$fragment$0 = (a = "") match {
  case (a = x) => /*start*/x/*end*/
}
```

The compatibility harness identifies the top-level expression through PSI and declares an insertion immediately before
its document range. The projection owns a piecewise bidirectional coordinate map around those insertions. Every
compiler-derived position is converted back to document coordinates before it leaves the compiler bridge.

This is a narrower form of candidate C. Model it as a source projection, not as an incidental preamble string or a
general production fallback.

This projection is preferable to wrapping the whole source in a `def` or `object`:

- existing definitions retain real top-level/package ownership;
- imports, companions, givens, opaque types, and qualified names are not moved into a synthetic owner;
- the formerly bare term gets the same unconstrained expected-type position as an inferred val initializer;
- compiler-only ranges that cover the synthetic val can be dropped while every original subtree keeps a precise mapped
  range.

Scala's own REPL provides the closest compiler precedent: it parses block statements and turns bare terms into
synthetic result vals before hosting them in a synthetic module. The insertion projection retains the useful
term-to-val transformation without adopting the REPL's whole-module ownership change.

There is no supported Scala 3.7.4 compiler or Scalameta presentation-compiler fragment mode that removes the need for
this projection. The private REPL pipeline could synthesize an offset-preserving AST, but integrating or reproducing it
would couple Metallurgy to substantially more dotc internals than a textual projection through the existing
`InteractiveDriver`.

The canonical architecture document remains the normative source. This note records the source investigation behind
the decision.

## Scala 3.7.4 source findings

### Ordinary compilation units reject bare terms

The normal parser's `TopStatSeq` accepts packages, imports, exports, extensions, and definitions, but not an expression.
`compilationUnit()` invokes that grammar at the outermost level. The block/template grammar separately accepts
expressions. See
[`Parsers.topStatSeq`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/parsing/Parsers.scala#L4624-L4658),
[`blockStatSeq`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/parsing/Parsers.scala#L4764-L4811),
and
[`compilationUnit`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/parsing/Parsers.scala#L4814-L4858).

`InteractiveDriver.run(uri, text)` creates a normal source file and calls `run.compileSources(List(source))`; it has no
fragment-parser branch. It retains the resulting compilation unit, opened source, and typed trees under the supplied
URI. See
[`InteractiveDriver.run`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/interactive/InteractiveDriver.scala#L144-L179).

### The REPL accepts fragments by synthesizing an AST

The REPL is the one relevant dotc facility that accepts a sequence of definitions and expressions. It does not make
those terms legal in a normal compilation unit:

- `ParseResult.parseStats` invokes `Parser.blockStatSeq(outermost = true)`, not the compilation-unit grammar
  ([source](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/repl/ParseResult.scala#L153-L160)).
- `ReplPhase` rewrites each term to a synthetic `val resN = expr`, preserving the term's span, then wraps all definitions
  in a synthetic module
  ([source](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/repl/ReplCompiler.scala#L253-L352)).
- `ReplCompiler.typeCheck` similarly builds a package/class/val AST around parsed fragment trees and compiles that
  prebuilt tree
  ([source](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/repl/ReplCompiler.scala#L158-L230)).

This is useful confirmation that a synthetic owner is the compiler's own solution to fragment typing. It is not a
presentation-compiler mode: adopting it would require private `ReplCompiler`, state, parser, and phase integration,
and would no longer exercise the production `InteractiveDriver` path.

### Script support is not a fragment-PC alternative

Scala 3.7.4's scripting drivers still invoke ordinary compilation. `ScriptingDriver` delegates to the standard
`Driver.doCompile`, while `StringDriver` creates a virtual source and invokes `compileSources`
([`ScriptingDriver`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/scripting/ScriptingDriver.scala),
[`StringDriver`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/scripting/StringDriver.scala)).

The old `ScriptParser` is not usable: its `parse()` method is `unsupported`, and the implementation that once wrapped
template statements is commented out
([source](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/parsing/ScriptParsers.scala#L46-L147)).
There is no active `-Xscript` setting in the 3.7.4 compiler settings.

### `-Ymagic-offset-header` is precedent, not the solution

Scala 3.7.4 has a generated-wrapper aid, `-Ymagic-offset-header`
([setting](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/config/ScalaSettings.scala#L451)).
When a wrapper contains the configured marker and names a loadable original file, `Positioned.sourcePos` maps a tree
after the marker to the original source with a constant negative shift
([header lookup](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/util/SourceFile.scala#L65-L92),
[`sourcePos`](https://github.com/scala/scala3/blob/3.7.4/compiler/src/dotty/tools/dotc/ast/Positioned.scala#L53-L64)).

That facility does not make a fragment valid or create a wrapper. Raw tree spans remain wrapper-relative, PC request
offsets remain wrapper-relative, and the named original source must exist through dotc's file lookup. Metallurgy's bulk
bridge currently reads raw `tree.span`, so the flag would not transparently normalize its snapshot. It is strong
upstream precedent for an affine source projection, but explicit mapping in Metallurgy is more complete and
controllable.

## Scalameta presentation-compiler findings

The published PC request model supplies a URI, the complete text, a cancellation token, and optionally outline files.
It has no fragment kind, synthetic preamble, script mode, source map, or prebuilt-tree request. Metallurgy compiles
against `mtags-interfaces` 1.3.4; its `VirtualFileParams.text()` is documented as the file's text contents, and
`PresentationCompiler` operations accept `VirtualFileParams`, `OffsetParams`, or a URI plus code.

Scala 3.7.4 consumes that input literally. `WithCompilationUnit` creates
`SourceFile.virtual(filePath.toString, sourceText)`, calls `driver.run(uri, source)`, and interprets the request offset
in that source
([source](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/WithCompilationUnit.scala#L20-L45)).
Hover follows the same source-file/driver path
([source](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/HoverProvider.scala#L34-L50)).
PC path lookup constructs spans directly from request offsets in the opened source
([source](https://github.com/scala/scala3/blob/3.7.4/presentation-compiler/src/main/dotty/tools/pc/utils/InteractiveEnrichments.scala#L38-L76)).

Therefore a single prefix of length `P` has a deterministic affine map:

```text
compiler offset = document offset + P
document offset = compiler offset - P
```

For multiple insertions, the same rule becomes piecewise affine: add or subtract the cumulative length of insertions
strictly before the mapped point. The map is sound for any range whose compiler span contains no inserted bytes.
A tree or diagnostic intersecting an insertion has no single verbatim document range and must be dropped, not clamped.

## IntelliJ oracle and current Metallurgy path

The upstream fixture configures its `ScalaFile`, asserts no parser errors by default, locates the marked
`ScExpression`, calls `expression.type()`, and compares exact `presentableText`
([`TypeInferenceTestFixture`](https://github.com/JetBrains/intellij-scala/blob/master/scala/scala-impl/test/org/jetbrains/plugins/scala/lang/typeInference/TypeInferenceTestFixture.scala#L42-L116)).
The bare `Tuple1` reference is present verbatim in
[`Scala3NewTuplesTest`](https://github.com/JetBrains/intellij-scala/blob/master/scala/scala-impl/test/org/jetbrains/plugins/scala/lang/typeInference/Scala3NewTuplesTest.scala#L69-L75);
the pure bare-match cases are present in
[`Scala3NamedTuplesTest`](https://github.com/JetBrains/intellij-scala/blob/master/scala/scala-impl/test/org/jetbrains/plugins/scala/lang/typeInference/Scala3NamedTuplesTest.scala#L166-L219).

Metallurgy currently breaks the desired separation in the test fixture: `assertExprType` passes
`wrapForCompilation(code)` to `configureByText`, so both PSI and dotc see the wrapper
([local source](../../src/test/scala/com/hmemcpy/metallurgy/compat/scala3/Scala3CompatTestCase.scala)).

The production bridge passes `snapshot.sourceText` directly to `InteractiveDriver.run`
([local source](../../src/main/scala/com/hmemcpy/metallurgy/pc/StructuralScala3PcBridge.scala)). Its typed-tree
extraction reads raw start/end spans and stores `PcSourceRange`; the snapshot publisher then matches those ranges
against exact or end-anchored PSI ancestors
([local source](../../src/main/scala/com/hmemcpy/metallurgy/compilerbackend/CompilerBackendSnapshotPublisher.scala)).
Publication is already range-based. If the bridge exports document-coordinate ranges, publication needs no
fragment-specific behavior.

## Placement of the seam

Use three responsibilities:

1. **The compatibility harness declares the projection.** It knows that an upstream oracle input is a fragment. It
   configures PSI with the verbatim document, finds `ScExpression` children whose semantic parent is the `ScalaFile`,
   and requests backend preparation with explicit insertions for that exact file and document version. Marker text does
   not select or delimit the transformation. No source-text heuristic, extension check, parser-error fallback, or
   production invalid-source detection should select it.
2. **The PC/session layer carries the compilation input.** Replace the assumption that one snapshot text serves both
   consumers with an immutable value containing:
   - document identity: URI, document version, verbatim document text;
   - compiler input: projected text;
   - a bidirectional point map and a partial range map defined only for ranges that do not cross inserted text;
   - a stable projection identity/fingerprint for cache and in-flight keys.
   Production constructs only the identity projection. Test support explicitly installs the synthetic projection.
3. **The compiler bridge normalizes all compiler positions.** It translates document offsets to compiler offsets for
   point/range requests, uses compiler text while inspecting trees and source substrings, and translates every
   source-derived result back before constructing neutral DTOs. The snapshot publisher receives only document
   coordinates and stays unchanged.

Do not put prefix subtraction in `CompilerBackendSnapshotPublisher`. By then diagnostics, SemanticDB occurrences,
navigation targets, completion/type-at offsets, tree-role classification, and source substring operations may already
have used the wrong coordinate space. The compiler boundary is the single place where wrapper coordinates should
exist.

Do not let the bridge decide *whether* to wrap. That is test-fixture policy and would turn an oracle accommodation into
production invalid-source recovery.

## Complete mapping contract

For sorted insertions `(d_i, text_i)` at document gaps `d_i`:

- a document start/point at `d` maps after an insertion at `d` (right-biased), while a document end at `d` maps
  before an insertion at `d` (left-biased);
- a compiler point outside inserted intervals maps back by subtracting the cumulative preceding insertion length;
- a compiler range maps only when neither endpoint lies in an insertion and the range contains no inserted interval;
- drop synthetic-val ranges and any range crossing an insertion;
- translate every document query point/range through the same projection;
- retain the document URI and version as the public snapshot identity;
- include projection identity in retypecheck/cache coalescing so an earlier identity compile cannot satisfy a projected
  request for the same URI/version;
- keep all original bytes unchanged and ordered: no indentation, newline normalization, marker removal, or trailing
  comment relocation.

The bridge must apply this contract to:

- typed-tree entries and symbol navigation ranges in the current source;
- dotc diagnostics;
- SemanticDB occurrence ranges;
- hover, completion, inline-type, structural-completion, and other position-based queries;
- any source slices used to classify a tree or recover an occurrence name.

Synthetic symbols and trees owned wholly by the wrapper must never be published. A same-source navigation target is
shifted only when its range is inside the payload; external-source targets remain untouched.

## Highlighting wrappers

`wrapForHighlighting` is a separate harness path today, but it is not a separate architectural exception. The overrides
in `FunctionLiteralToPartialFunctionCompatTest`, `InfixGenericCallCompatTest`, and
`Scala3DeprecatedInfixCallInspectionCompatTest` currently alter the PSI text by wrapping it in an object. That conflicts
with the verbatim-fixture rule for the same reason as `wrapForCompilation`.

Do not make the new expression-type projection implicitly affect highlighting tests. Migrate those overrides
deliberately:

1. configure their upstream snippet/template verbatim;
2. use the same explicit top-level-expression insertion projection only when dotc needs bare expressions hosted;
3. run daemon highlighting against document-coordinate diagnostics and semantic entries;
4. remove `wrapForHighlighting` after parity tests prove caret/error ranges and inspection ownership.

The insertion projection preserves their class/object/val/package relationships better than either existing whole-file
object wrapping or a method-local block. Caret offsets remain document offsets and pass through the piecewise map only
at the compiler boundary.

## Implementation and verification plan

1. Add focused Scala 3.7.4 probes to the research/test evidence:
   - ordinary compile rejects and omits the bare term;
   - a synthetic top-level val initializer types the term and preserves the expected exact type;
   - `-Xprint:typer` confirms the marked expression's tree;
   - REPL `:type` confirms representative expected types.
2. Introduce an immutable identity/insertion `PcCompilationProjection` with checked point/range conversion. Unit-test
   empty ranges, EOF, multiple insertions at distinct offsets, points on insertion boundaries, ranges crossing an
   insertion, synthetic-val ranges, and overflow.
3. Separate document text from compiler text in the snapshot/query model. Include the projection fingerprint in
   pending-retypecheck and query-cache identity.
4. Add an explicit test-support entry point that registers or passes the projection for one URI/version before backend
   preparation. Production file/document listeners always create the identity projection.
5. Run the structural driver on compiler text. Convert typed-tree entries, navigation ranges, diagnostics, and
   SemanticDB occurrences to document coordinates inside the bridge.
6. Translate all document-originating query offsets/ranges to compiler coordinates. Add round-trip tests for hover,
   completion, and `typeAt`, even if the first oracle cases only consume bulk types.
7. Leave `CompilerBackendSnapshotPublisher` unchanged and add an integration test proving an entry extracted at
   `[P + s, P + e]` commits to the verbatim PSI expression at `[s, e]`.
8. Change `assertExprType` to call `configureByText(code)` verbatim, find bare top-level PSI expressions, and explicitly
   request the insertion projection.
   Delete `wrapForCompilation`, `wrapInDef`, and `definesType`.
9. Add two end-to-end oracle tests:
   - `val t = Tuple1(1)` followed by the marked bare `t`;
   - the pure bare named-tuple match with marked pattern binding.
   Assert zero PSI parser errors, exact dotc-backed role state, exact rendered type, and no bundled fallback.
10. Migrate each `wrapForHighlighting` override separately and test exact caret/highlight document ranges.
11. Run focused compatibility suites, formatting/checks, then the broader compiler-backend and compatibility suites
    under the repository's required JBR and GNU `gtimeout`.

## Risks and required guardrails

- **Expression-sensitive legality:** a top-level val initializer accepts the current reference and match fragments, but
  it is not a universal host for every possible statement-like fragment (`return` is an obvious counterexample).
  Projection selection must be explicit and narrow; new expression forms require an exact REPL and `-Xprint:typer`
  probe before admission.
- **Synthetic name leakage:** wrapper owner names must not appear in rendered types, symbols, navigation, completion,
  diagnostics, or stable IDs exported for document elements. Test for this rather than sanitizing strings after the
  fact.
- **Mixed coordinate spaces:** shifting only typed-tree entries is insufficient. One missed diagnostic, SemanticDB, or
  query path can silently target another PSI element. Compiler-coordinate values should be an internal type distinct
  from document-coordinate values where practical.
- **Insertion-crossing spans:** the synthetic `ValDef` covers inserted and original text and has no document range.
  Drop it and any other crossing range; never clamp it onto the document. Its original expression descendants remain
  mappable.
- **Duplicate publication:** an identity compile and projected compile for the same URI/version must not share an
  in-flight/cache key.
- **Line endings:** construct the prefix and suffix around the exact in-memory document string. Do not normalize CRLF
  or add indentation.
- **Fallback masking:** integration tests must assert the requested `CompilerBackendRole` is current and compiler-backed,
  not merely that `ScExpression.type()` happened to return the expected text.
