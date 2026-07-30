# Lossless token and trivia capture

## Question

Which exact-compiler path can provide the ordered physical source, trivia, parser roles, and virtual layout information
needed by deterministic whole-file Scala PSI without using the bundled Scala lexer, and which failures must leave a
file or module in neutral PSI?

## Decision

The production input is **verbatim source plus parser evidence**, not a replayed dotc token list:

```text
exact source text
  + option-faithful dotc untyped tree and parser diagnostics
  + dotc scanner evidence where it is independently reproducible
  -> host-owned ParserEvidenceSnapshot
  -> production catalog assigns every terminal interval and layout equivalent
  -> validated LosslessSourcePlan
  -> plan-backed lexer and PsiBuilder
```

The resolved source plan has two ordered lanes:

1. **Physical leaves** form a contiguous partition of every UTF-16 code unit in the exact source. Each leaf stores a
   range and a host-owned classification such as grammar token, whitespace, comment, interpolated-string fragment, XML
   fragment, or recovery text. Concatenating their source slices must reproduce the source UTF-16-code-unit for
   UTF-16-code-unit by exact `String` equality.
2. **Virtual layout equivalents** have an anchor offset and stable order but consume no source. They represent the
   structural effect of dotc's `NEWLINE`, `NEWLINES`, `INDENT`, and `OUTDENT` roles and carry the parser production and
   tree field that owns the boundary. They do not claim to be a retained history of the parser-owned scanner.

The exact source is the lossless authority. The untyped tree and other positioned parser products are the grammar-role
authority. A standalone dotc scanner is optional advisory evidence for ordinary lexemes, comments, interpolation, and
layout candidates, but it is not the parser's consumed token stream and cannot be promoted to that status. In
particular, the parser mutates scanner state, updates language-import context, invokes layout observation methods,
performs recovery skips, and hands XML to a separate character parser.

The ready PSI producer consumes only a completely validated source plan. It never searches forward for matching text,
never allows an unclaimed physical character, and never emits a partially classified Scala tree. Artifact preparation
or bridge failure makes the current parser-capability epoch unavailable and leaves the module in the already-specified
neutral, non-Scala, non-stub-bearing pending language. A source-specific unknown production or ambiguous terminal plan
produces a deterministic one-leaf neutral file for that exact content while the module remains ready. Parser
diagnostics and compiler-rejected edit states are not capability failures by themselves.

This decision replaces the current producer's `advanceToToken(text, bound)` and source-substring heuristics. It does not
introduce another general Scala lexer. Token ownership is declared by the production catalog against exact source
intervals and parser-tree fields; only then does an immutable plan-backed lexer expose those final intervals to
`PsiBuilder`.

## Compared paths

| Path | What it can establish | Why it is not the final design |
| --- | --- | --- |
| Record or replay dotc scanner tokens | A fresh scanner can expose candidate labels, offsets, comments, string modes, and virtual layout candidates for many ordinary regions. | There is no retained token-history or listener seam. Parser mutations, language imports, recovery, layout observations, and XML make standalone replay non-equivalent. |
| Exact-source interval planning | Verbatim source atoms cannot lose text. Positioned parser products assign contextual roles; the catalog resolves every final physical leaf and reconstructed layout equivalent before building PSI. | This is the selected path. Unknown or ambiguous claims fail closed instead of being guessed. |

`Parser.in` is a concrete `Scanner`, and `ParserPhase` retains only the resulting untyped tree and comments after parse.
`Scanner.nextToken` has no listener or event-recorder parameter
([`Parsers.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/Parsers.scala#L191-L210),
[`ParserPhase.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/ParserPhase.scala#L25-L40)).
Structural access can inspect the scanner's present state, but it cannot intercept the parser's direct calls or recover
past states once the parser reaches EOF. There is therefore no supported or typed-structural parser-owned recording
seam in the inspected artifacts. Bytecode instrumentation, generated subclasses, debug-output capture, and compiler
patching are rejected: each adds a fragile execution path without creating an upstream contract.

## Why neither available tree nor scanner is lossless alone

### The untyped tree preserves grammar, not all text

Dotc's parser constructs an untyped AST and retains scanner comments separately. The compiler parser phase installs
`unit.untpdTree` and then copies `p.in.comments` to the compilation unit; it does not publish a CST
([`ParserPhase.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/ParserPhase.scala#L25-L40)).
Untyped tree products preserve important surface productions, child fields, and source spans, but punctuation,
whitespace, most comments, and some literal internals are not tree children.

Accordingly, the untyped tree is richer than the typed tree for recovering source grammar, but it
is not itself lossless. Calling it lossless would incorrectly imply that punctuation, trivia, recovery skips, and XML
character structure can be reconstructed from tree products alone. The exact source is what makes the combined
representation lossless.

XML makes the limitation explicit. Dotc switches from the Scala scanner to `MarkupParser`, which reads characters
directly, then resumes the saved Scala scanner state
([`MarkupParsers.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/xml/MarkupParsers.scala#L325-L380)).
The XML builder immediately constructs Scala XML-library calls, and its own source states that many positions are
transparent and need rework for IDE navigation
([`SymbolicXMLBuilder.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/xml/SymbolicXMLBuilder.scala#L18-L29)).
The untyped result therefore cannot reconstruct XML leaves by itself.

### Standalone scanner replay is not parser replay

`Parsers.Parser` owns a concrete `Scanner`, and parser methods call and mutate it directly
([`Parsers.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/Parsers.scala#L191-L210)).
Scanner state includes the current and next token, source offsets, indentation regions, string-interpolation regions,
and a mutable language-import context
([`Scanners.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/Scanners.scala#L38-L104),
[`Scanners.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/Scanners.scala#L176-L249)).

The parser performs state changes that an independent `while (token != EOF) nextToken()` loop does not reproduce:

- accepted language imports replace `in.languageImportContext`
  ([`Parsers.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/Parsers.scala#L3604-L3633));
- colon and indentation-sensitive productions call `observeColonEOL`, `observeIndented`, `observeOutdented`, and
  `closeIndented`, while some productions rewrite the current token before advancing
  ([`Scanners.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/Scanners.scala#L652-L704),
  [`Parsers.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/Parsers.scala#L1450-L1525));
- parser recovery calls `Scanner.skip`, whose stopping rule depends on the current parser region and virtual separators
  ([`Scanners.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/Scanners.scala#L280-L322));
- XML consumes the scanner's underlying character reader outside `Scanner.nextToken`, then calls `resume`
  ([`MarkupParsers.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/xml/MarkupParsers.scala#L325-L380),
  [`Scanners.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/Scanners.scala#L1544-L1560)).

Even dotc's REPL syntax highlighter treats scanner and parser as separate evidence: it scans once for lexical colors
and comments, then parses independently for tree roles
([`SyntaxHighlighting.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/printing/SyntaxHighlighting.scala#L38-L130)).
That implementation supports using scanner ranges as candidates; it does not prove that scanner replay records the
parser's history.

### The bundled lexer cannot be the hidden fallback

The pinned Scala plugin's parser definition creates `ScalaLexer` directly
([`Scala3ParserDefinition.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/parser/Scala3ParserDefinition.scala),
[`ScalaParserDefinitionBase.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/parser/ScalaParserDefinitionBase.scala#L10-L31)).
That lexer contains its own Scala/XML handoff, tag stack, embedded-Scala brace stack, and recovery behavior
([`ScalaLexer.java`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/lexer/ScalaLexer.java#L36-L84),
[`ScalaLexer.java`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/lexer/ScalaLexer.java#L175-L330)).
Using it to construct the producer's leaves would reintroduce the implementation whose unsupported syntax motivated
the independent producer. It remains a pinned test oracle where it parses the same compiler-valid source; it is not a
runtime syntax authority.

## Neutral evidence and resolved-plan contracts

Only bootstrap- or plugin-loader values cross the exact compiler classloader:

```text
ParserEvidenceSnapshot
  source: String
  sourceLength: Int
  sourceDigest: Digest
  parserTree: ParserSyntaxTree
  positionedSyntax: Vector[PositionedSyntax]
  sourceAtoms: Vector[SourceAtom]
  diagnostics: Vector[ParserDiagnostic]
  comments: Vector[CommentEvidence]
  scannerCandidates: Vector[ScannerCandidate]

SourceAtom
  id: Int
  start: Int
  end: Int
  parserClaims: Vector[ParserClaim]
  triviaClaim: Option[TriviaClaim]
  recoveryEvidence: Option[RecoveryEvidence]

LosslessSourcePlan
  sourceDigest: Digest
  physicalLeaves: Vector[PhysicalLeaf]
  virtualLayout: Vector[VirtualLayoutEquivalent]
  diagnostics: Vector[ParserDiagnostic]

PhysicalLeaf
  start: Int
  end: Int
  kind: PhysicalKind
  targetLeafRole: String
  ownerProduction: String
  ownerField: String

PhysicalKind
  GrammarToken
  Whitespace
  LineComment
  BlockComment
  DocComment
  StringDelimiter
  StringContent
  InterpolationPrefix
  InterpolationBoundary
  XmlMarkup
  XmlText
  XmlEntity
  XmlEmbeddedScalaBoundary
  RecoveryText

VirtualLayoutEquivalent
  kind: Newline | Newlines | Indent | Outdent
  anchor: Int
  ordinalAtAnchor: Int
  ownerNodeId: Long
  ownerField: String
  evidence: TreeBoundary | ScannerCandidate

ParserClaim
  nodeId: Long
  fieldPath: Vector[String]
  role: String
```

`ParserEvidenceSnapshot` is produced entirely by the exact-version bridge. It contains no resolved Scala-plugin leaf
types and does not claim that each source atom has one production owner. The production catalog consumes that evidence
and creates `LosslessSourcePlan`; only that post-catalog plan has final physical leaf ownership. Evidence atoms are
provisional intervals. A catalog handler may refine one only inside a structurally claimed outer range, recording the
production and field that requested each new boundary.

Ranges use Java/IntelliJ UTF-16 offsets. A physical leaf always has `start < end`; an empty source has no physical
leaves. Virtual layout equivalents always have `start == end == anchor` and never participate in source coverage.
Numeric dotc token IDs do not cross the bridge; evidence uses host strings and source slices because token-number
assignments are an implementation detail of the exact artifact.

The inspected Scala 3 revisions change token numbers, parser constructor shapes, and selected product shapes. That
drift reinforces executable shape discovery and stable textual labels: neither a version table nor a numeric token
mapping belongs in the bridge.

Source atoms are not a Scala-plugin token stream. Mapping them to `ScalaTokenType`,
`ScalaTokenTypes`, an XML platform token, or a compatibility leaf is a production-catalog responsibility. The pinned
plugin distinguishes whitespace, three comment classes, ordinary and interpolated string fragments, identifiers,
soft-keyword token types, and many XML leaves
([`ScalaTokenTypes.java`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/lexer/ScalaTokenTypes.java#L31-L189)).
Those are target PSI leaf contracts, not permission to run its lexer.

## Capture pipeline

### 1. Parse the exact source once

Use the parser-only bridge already selected by executable capability:

1. create a fresh option-faithful context through `Driver.setup`;
2. create `SourceFile.virtual` from the exact file name and unchanged source;
3. invoke the discovered whole-source `Parsers.Parser.parse` shape;
4. export ordered product fields, source spans, parser diagnostics, scanner comments, and positioned syntax values;
5. dispose all exact-loader objects after copying neutral values.

Every parse receives the module's exact `-source`, `-language`, indentation, migration, rewrite, and experimental
options. No wrapper, enclosing declaration, filename-relative source edit, or alternate fragment grammar is permitted.

The positioned export is deliberately broader than a tree walk. Dotc models syntax modifiers such as `Mod.Given` and
`Mod.Implicit` as `Positioned` values rather than `Tree` children; their spans provide exact contextual anchors for
`using` and `implicit`. A tree-only bridge drops those roles
([`untpd.scala`](https://github.com/scala/scala3/blob/40be7608a48477951218ae3a8ac8749fe02ba988/compiler/src/dotty/tools/dotc/ast/untpd.scala#L190-L239)).

### 2. Create a provisional source boundary map

The host evidence builder records a boundary at:

- `0` and `source.length`;
- every source-derived tree `start` and `end`;
- every separately available name, end-marker, modifier, annotation, and child-field span;
- every parser diagnostic range endpoint;
- every scanner candidate `offset` and `lastOffset`;
- every scanner comment endpoint;
- every line start and line end.

The bridge retains `point` as parser/diagnostic focus metadata. It creates a physical boundary only when a parser claim
uses that point; focus alone does not imply a lexical split.

During catalog resolution, a production, interpolation, or XML handler may add boundaries only within its already
claimed outer interval and only through its declared local grammar. Source capture therefore never depends on a
compiler object having a leaf for each character, while an unbounded forward search still cannot acquire token
ownership. Provisional atoms are evidence ranges, not classifications of valid Scala.

Tree spans are nested and may overlap; atom boundaries are a sorted set, not a claim that tree ranges form a physical
partition. Absent, synthetic, transparent, reversed, or out-of-range spans are preserved as node metadata but cannot
claim physical text.

### 3. Overlay optional scanner evidence without treating it as grammar

When its executable probe succeeds, a fresh exact-artifact scanner in the same configured context may be replayed to
EOF. For every observed state, copy:

- canonical token label obtained from the exact artifact's token-name operation;
- current start, next-state `lastOffset`, name text, string value, and numeric base when defined;
- whether the token is physical or one of the layout candidates;
- all retained comments and scanner diagnostics.

The scanner's `offset` is the current start, while advancing exposes the preceding end in `lastOffset`; dotc's own
syntax highlighter uses that sequence
([`SyntaxHighlighting.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/printing/SyntaxHighlighting.scala#L56-L89)).

Scanner ranges are evidence, not final leaves. Post-processing can fuse tokens such as `case class`, propose virtual
layout at another token's offset, or span skipped trivia. The source boundary map splits such intervals at its complete
boundary map and retains the compiler label only on atoms it unambiguously covers.

Scanner replay is always advisory and optional, including for artifacts where its structural surface is absent. The
exact-source and positioned-parser path must be able to construct a plan without assuming that replay matches parser
history. When replay is available, its own probe must prove termination, monotone offsets, and consistent
ordinary-token evidence before any candidate is retained. A scanner mismatch never causes fallback to the bundled
lexer and never overrides terminal claims from the catalog.

### 4. Classify trivia from exact gaps

Whitespace and comments are reconstructed only inside intervals already proven to be outside strings, interpolation
content, XML, and other literal regions:

- spaces, tabs, carriage returns, line feeds, and form feeds outside literal/XML regions become whitespace leaves;
- `//` through the physical line end becomes a line comment;
- balanced, nested `/* ... */` becomes a block comment;
- a block comment beginning `/**` becomes a doc comment;
- an unclosed block comment runs to EOF only when the exact scanner/parser reports the corresponding incomplete-input
  condition.

Dotc's scanner skips comments while retaining exact `Comment(span, raw, ...)` values when comment retention is enabled
([`Scanners.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/Scanners.scala#L225-L252),
[`Comments.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/core/Comments.scala#L44-L86)).
The raw-source classifier is still required because `-Xdrop-comments` disables that buffer and because source
losslessness cannot vary with a compiler output setting. It never searches an unbounded gap for comment spelling:
literal/XML ownership is resolved first, and only the remaining ordinary-source interval may be classified. Where
compiler comments exist, range and raw-text equality are mandatory cross-checks.

No interval is called trivia merely because no tree spans it. A non-whitespace, non-comment gap remains unclassified
until a production, literal, or XML handler claims it.

### 5. Attach parser roles

Each source atom stores all parser nodes and named product-field paths whose source-derived ranges cover it. The
production catalog resolves those candidates into one owner. This is how the same scanned `IDENTIFIER` text can become:

- an ordinary identifier;
- a soft modifier such as `inline`, `opaque`, or `transparent`;
- a `using` clause introducer;
- an `as`, `derives`, `end`, `extension`, or other contextual keyword;
- a future exact-compiler role not known to the bundled parser.

The pinned plugin follows the same essential rule: soft words begin as identifier text and the grammar remaps the
current token only after recognizing its role
([`package.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/parser/package.scala#L75-L93),
[`SoftModifier.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/parser/parsing/base/SoftModifier.scala#L23-L60)).
Metallurgy derives the role from dotc's production and exact source range rather than rerunning that grammar.

No role may be assigned from spelling alone. If two production claims can own the same source atom and the catalog
cannot disambiguate them structurally, capture is incomplete and ready PSI is not emitted.

### 6. Reconstruct virtual layout as structural events

Dotc inserts `NEWLINE`, `NEWLINES`, `INDENT`, and `OUTDENT` into scanner state. They do not consume source and may share
an anchor with a physical token. Their insertion depends on compiler options, indentation-region state, statement
start/end sets, leading-infix rules, blank lines, and parser observations
([`Scanners.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/Scanners.scala#L498-L649)).

The snapshot records two forms of evidence:

- scanner replay may propose a virtual event and anchor;
- the untyped production and named child-field boundary must identify the region or separator that event explains.

Only the structurally validated equivalent is published. A catalog entry for an indentation-owning production
declares:

- the opening source token or production role;
- the ordered child field contained by the region;
- whether braces, indentation, or either form is legal;
- the physical line interval that supplies whitespace;
- the anchor and nesting relation for the virtual open/close;
- recovery alternatives accepted when dotc reported a parser error.

Virtual layout equivalents guide marker nesting and token ownership but are not emitted as physical IntelliJ lexer
tokens.
IntelliJ's token sequence derives a token's end from the next physical token start and expects a source-covering
sequence; virtual layout equivalents belong beside that sequence rather than inside it
([`TokenSequence.java`](https://github.com/JetBrains/intellij-community/blob/ddf64ea5690e21e271d51d47a826bc64a494d41e/platform/core-impl/src/com/intellij/lang/impl/TokenSequence.java#L18-L123)).

A replayed virtual candidate with no tree owner is evidence only. A tree indentation region with no reconstructible
physical line and balanced virtual boundary is a failure. This avoids claiming that an independently replayed scanner
is parser-equivalent while still preserving every layout fact the PSI catalog needs.

### 7. Reconstruct strings and interpolation from tree roles plus source

Dotc's scanner has a dedicated string region and produces `INTERPOLATIONID`, `STRINGPART`, interpolated identifiers,
embedded blocks, and the final `STRINGLIT`
([`Scanners.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/Scanners.scala#L1210-L1311)).
The parser records an `InterpolatedString(id, segments)` whose ordered segments alternate literal and argument roles
([`untpd.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/ast/untpd.scala#L62-L74),
[`Parsers.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/Parsers.scala#L1385-L1424)).

The string handler claims the complete outer range, then partitions it from exact source and ordered segment spans:

- interpolator prefix;
- single or triple opening delimiter;
- content and escape pieces;
- `$identifier`, `$this`, `$_` in patterns, or `${...}` boundaries;
- embedded Scala subtree range;
- closing delimiter, or recovery tail when the exact parser reports it missing.

The pinned PSI grammar requires these distinctions: it represents prefix, content, injection boundary, and end as
separate token types and wraps interpolation arguments as references, patterns, or block expressions
([`CommonUtils.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/parser/parsing/CommonUtils.scala#L10-L91)).

Decoded `strVal` is validation evidence only. It never replaces the original slice, so escapes, quote counts, Unicode
spelling, and malformed tails remain exact.

### 8. Reconstruct XML inside parser-proven XML regions

XML cannot use standalone Scala scanner replay. The XML handler starts only at a source offset dotc classified as
`XMLSTART`. Candidate bounds come from source-derived enclosing/adjacent parser products and the exact source; the
retained parse does not expose the scanner's saved `resume` position as a value. Within an independently proven bound,
an isolated compatibility implementation performs a deterministic source partition following the exact compiler's
character grammar:

- start, end, and empty-element tags;
- qualified names and attributes;
- quoted attribute values and entities;
- text, character/entity references, comments, CDATA, processing instructions, and `xml:unparsed`;
- nested and adjacent XML elements;
- `{{` as literal text and a single `{...}` as embedded Scala;
- embedded Scala ranges cross-checked against source-derived untyped child trees;
- the exact malformed suffix associated with a parser XML diagnostic.

These states follow the compiler-owned character grammar: attributes and embedded blocks
([`MarkupParsers.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/xml/MarkupParsers.scala#L118-L159)),
comments/CDATA/entities
([`MarkupParsers.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/xml/MarkupParsers.scala#L162-L223)),
and nested content with Scala escapes
([`MarkupParsers.scala`](https://github.com/scala/scala3/blob/52731578d58f51af3501ed348b965e1cfb6fab23/compiler/src/dotty/tools/dotc/parsing/xml/MarkupParsers.scala#L225-L323)).

This handler does not decide whether XML is legal; the exact parser already did that and owns all diagnostics. It only
partitions the verbatim range into PSI-compatible roles. Its executable probe validates the implementation against
exact-parser acceptance, embedded-tree ranges, adjacent Scala token bounds, and complete source reconstruction. It
must not use the bundled `ScalaLexer` or accept a source range dotc did not identify as XML.

Because dotc's XML output is desugared and some internal spans are intentionally synthetic, tag and attribute source
ranges must be derived from the outer parser-proven range and exact character grammar, not from generated XML-library
call names. Every embedded Scala range and the final XML end offset must still agree with independent dotc evidence.
If no trustworthy outer/end bound exists—especially for malformed XML—the plan fails for that file rather than
guessing punctuation ownership.

### 9. Preserve malformed and recovery input

The exact parser is invoked even when it reports errors. Scanner `ERROR` or early `EOF`, parser recovery trees, skipped
regions, absent spans, and missing delimiters are first-class evidence, not exceptions.

Capture succeeds for malformed input when all of these remain true:

- the planned physical leaves still partition the complete exact source;
- every well-formed prefix has stable token/trivia ownership;
- every malformed interval is bounded by source, recovery-tree, and diagnostic evidence;
- every virtual layout equivalent remains ordered and balanced within the recovery contract;
- the production catalog has an explicit recovery shape for the involved node;
- no fabricated physical token is needed.

A missing token may be represented as a zero-width expected-token claim tied to a dotc diagnostic, but never as a
physical source piece. The producer does not hide that error: dotc's diagnostic remains visible through the diagnostic
path.

Recovery evidence fails when the parser does not terminate, bridge offsets regress or escape the source, XML or
interpolation cannot be resynchronized, a non-trivia interval has no role, or a recovery shape would require guessing
an owner. A broken parser/bridge contract is a module-capability failure; unresolved source ownership is a
source-plan failure. Neither permits running the bundled parser.

## Plan-backed lexer and PSI construction

The production catalog resolves `ParserEvidenceSnapshot` before any producer `PsiBuilder` is created:

1. assign every non-empty source interval exactly one target physical leaf role;
2. coalesce adjacent atoms only when one catalog terminal owns their complete interval;
3. resolve every reconstructed virtual layout equivalent to one production/field boundary;
4. validate the complete `LosslessSourcePlan`;
5. create an immutable lexer over that plan and pass it explicitly to the ready producer's `PsiBuilder`;
6. walk the same plan and catalog to create balanced composite markers.

The plan-backed lexer is a slice iterator, not a Scala grammar implementation. `start` verifies exact source equality
and positions the iterator at declared leaf boundaries; `getTokenType`, `getTokenStart`, and `getTokenEnd` return the
already-resolved physical leaf values. It cannot discover, reinterpret, or repair syntax. The initial lazy file content
may use a deterministic verbatim chameleon token, but the ready whole-file AST and stub spine are built only from the
explicit resolved plan.

Virtual layout equivalents never enter the lexer. They can share anchors and have stable same-anchor ordinals without
creating zero-length leaves. This separation follows IntelliJ's source-covering token sequence while allowing the
catalog to reproduce indentation-owned composite structure.

## Complete validation before PSI

Validation occurs at two boundaries. The bridge validates evidence without requiring catalog ownership. The catalog
then validates the resolved plan before constructing a lexer or PSI.

### Evidence invariants

1. `sourceLength == source.length` and the digest matches the input handed to `SourceFile.virtual`.
2. Source atoms are sorted, non-empty, non-overlapping, and contiguous from `0` to `source.length`.
3. Tree and positioned-syntax traversal terminates; IDs are unique; ordered product fields and nested boundaries are
   preserved.
4. Every physical parser claim uses a source-derived, in-range span. Synthetic and absent spans cannot own text.
5. Every retained comment raw value, name, literal value intended to be raw, or scanner slice agrees with exact source.
6. Comments are ordered, non-overlapping, outside parser-proven literal/XML content, and exact-source equal.
7. Scanner candidates, when available, have terminating, monotone, in-range offsets; no candidate is treated as a
   terminal claim.
8. No compiler-loader object, collection, iterator, name, span wrapper, class, method, or handle crosses the bridge.

### Resolved physical-plan invariants

9. Physical leaves are sorted, non-empty, non-overlapping, and contiguous from `0` to `source.length`.
10. `physicalLeaves.map(source.substring).mkString == source`.
11. Every physical leaf has exactly one catalog production, field, and target leaf role.
12. Every required keyword, delimiter, name, modifier, annotation, clause marker, literal boundary, and XML role is
   claimed by its declaring production rather than by text search.
13. Soft-keyword classification has a parser or positioned-syntax role; spelling alone is insufficient.

### Resolved layout invariants

14. Virtual layout anchors are in `[0, source.length]`, sorted by `(anchor, ordinalAtAnchor)`, and deterministic.
15. Every `Indent` has one structurally matching `Outdent`; nesting agrees with the owning tree fields.
16. `Newline` versus `Newlines` agrees with the exact physical line interval and configured language options.
17. Braced productions do not acquire synthetic indentation ownership, and indentation productions identify the exact
    child field contained by the region.
18. Scanner layout candidates that contradict terminal tree/catalog claims are reported and never override them.

### Resolved literal, XML, recovery, and determinism invariants

19. Each string or interpolation outer range has exactly one prefix/delimiter scheme, ordered embedded-tree ranges,
    and either a physical close or a matching parser diagnostic.
20. Each XML region begins at parser-proven `XMLSTART`, has an independently proven end bound, and partitions every
    character into one XML or embedded-Scala role.
21. A recovery leaf is permitted only beside parser/scanner diagnostic evidence and a declared catalog recovery path;
    parser errors do not relax any other invariant.
22. Two fresh evidence snapshots and resolved plans for the same artifact identity, options, filename, and source are
    identical, excluding elapsed time and diagnostic object identity.
23. Cache identity includes exact compiler artifacts, ordered options, file identity, and source digest.
24. Cold, warm, editor, indexing, copied-file, and restart parses produce the same physical leaves, layout equivalents,
    and eventual stub-bearing syntax for the same inputs.

Validation is all-or-nothing for a Scala plan. There is no `emitRaw`, best-effort name search, or partial catalog
result. Source-specific plan failure uses the deterministic neutral-file result described below.

## Capability discovery and failure policy

### Required artifact capabilities

Parser preparation must prove, by executable shape rather than version number:

- option-faithful context setup;
- exact virtual source construction;
- synchronous whole-file parser construction;
- ordered untyped product traversal with named fields;
- positioned non-tree syntax traversal;
- source span decoding and source-derived classification;
- parser diagnostic extraction;
- comment extraction when enabled;
- deterministic evidence construction and host-only values.

Scanner token access is an optional named capability. Plan correctness may use candidates when present, but cannot
require standalone replay to equal parser history. Layout, interpolation, XML, and recovery planning are
catalog/runtime self-tests over neutral evidence, not exact-loader linkage requirements.

Construction variants discovered for exact Scala artifacts remain isolated in the parser bridge. Artifact coordinates
are diagnostics and cache identity, never behavior switches.

### Exactly when PSI is neutral

Neutrality has two scopes.

The module remains in the unrelated pending language when:

1. its parser epoch is `Preparing`, `Unavailable`, or otherwise not ready;
2. required parser-bridge discovery fails;
3. the artifact self-test fails a required construction, traversal, span, diagnostic, classloader-isolation,
   termination, or determinism invariant;
4. a runtime bridge failure disproves a required capability already published for the epoch.

The module epoch remains ready, but one source receives a deterministic whole-file neutral result when:

1. its evidence snapshot is valid but the catalog does not recognize a required product or positioned role;
2. terminal, trivia, layout, interpolation, XML, or recovery claims cannot be resolved without ambiguity;
3. the resolved source plan fails coverage, ownership, nesting, or exact-text validation.

That file result is one verbatim `UNREPRESENTABLE_SOURCE` leaf under the ready file root, final for the tuple of module
epoch, file identity, and source digest. It is not an asynchronous placeholder and is never replaced for the same key.
It contributes no Scala declarations, references, or declaration stubs; the unavoidable file root remains
deterministic. An edit or new module epoch computes a new plan. One source-specific language feature therefore cannot
disable unrelated files in the module. Every Scala semantic consumer requires `PlanSuccess`; the neutral file cannot
fall through to bundled inference.

The bridge returns `EvidenceSuccess(snapshot)` or a structured module-capability failure. The catalog returns
`PlanSuccess(plan)` or a structured source-plan failure. Both failure forms produce one project-level report keyed by
artifact identity, options identity, capability or invariant name, file identity, and source digest. A rejected source
keeps its exact compiler diagnostics available to the diagnostic renderer even when its syntax plan fails, so the
neutral result does not turn erroneous code into an apparently clean Scala tree.

Neither scope may invoke the bundled parser, emit part of a Scala tree, or install a different stub spine later for
the same key.

Neutral PSI is **not** selected merely because:

- dotc reports a syntax error for an incomplete edit;
- the source has an unclosed comment, string, interpolation, or XML region that has complete recovery evidence;
- a warning or error should be displayed;
- semantic preparation is still running after parser readiness;
- the bundled parser disagrees with dotc;
- a Scala version or implementation class is unfamiliar.

Those cases either produce validated recovery PSI plus visible dotc diagnostics, remain asynchronously semantic, or
fail only if a concrete capability invariant is actually unmet.

## Executable proof suite

Preparation runs a fixed self-test twice in fresh contexts. Runtime tests then apply the same assertions to copied
upstream examples and broad complex files.

### Exact artifact lanes

At minimum, execute against the exact parser shapes already established for:

- Scala `3.3.7`;
- Scala `3.5.2`;
- Scala `3.7.4`;
- the moving nightly selected by the capability-validation lane.

The test selects by artifact coordinate only to load the requested compiler. Assertions are identical, and capability
discovery—not the coordinate—chooses construction calls.

### Required examples

| Area | Minimum source states |
| --- | --- |
| Physical coverage | empty file; CRLF; tabs; Unicode identifiers and operators; backticks; fused `case class`; leading/trailing trivia |
| Soft roles | each soft word as an identifier and as its grammar role; chained soft modifiers; `using` clauses and calls; `end` markers; language imports |
| Layout | nested indentation; braces; blank-line separators; leading infix; colon arguments; `match`/`catch`; EOF outdents; `-no-indent`; migration/source options |
| Comments | line, nested block, doc, trailing EOF, comment-only lines within indentation, comments around operators and delimiters, comment dropping enabled |
| Strings | ordinary/triple; escapes; raw and custom interpolators; `$id`; `$this`; `${nested}`; `$$`; pattern interpolation; missing close and bad injection |
| XML | nested/adjacent/empty tags; namespaces and attributes; entities; comments; CDATA; processing instructions; `xml:unparsed`; `{{`; embedded Scala with nested braces |
| Recovery | incomplete definition, unmatched delimiters, bad indentation, illegal character, unclosed comment/string/XML, parser skip across several statements |

For every example assert:

1. complete exact-source reconstruction;
2. stable tree-field and role claims;
3. stable virtual-event sequence;
4. no exact-loader value in the snapshot;
5. identical repeated snapshots;
6. expected success or named capability failure;
7. no bundled lexer or parser invocation;
8. recovery errors preserved, never suppressed.

### IntelliJ-side contract tests

The later implementation must also prove:

- the plan-backed lexer returns monotone, source-covering physical tokens;
- virtual layout equivalents do not create zero-width physical leaves;
- every produced leaf's range and exact text equal its source-tape piece or declared coalescing of adjacent pieces;
- parser markers consume the complete tape and finish at `source.length`;
- soft-keyword leaves have the token type demanded by their production role;
- whitespace and all three comment categories create the pinned runtime PSI classes expected by public `Sc*`
  accessors;
- interpolation and XML produce the pinned runtime token/element structure without running `ScalaLexer`;
- malformed edits either produce the declared recovery structure with dotc diagnostics or transition to neutral PSI;
- complete stub signatures remain identical across cold, warm, edit, copy, indexing, and restart paths.

The pinned plugin's existing lexer and parser examples for comments, interpolation, XML, identifiers, and newlines are
useful exact payloads for this proof. They are copied into Metallurgy's owned harness; the upstream build is not run.

## Consequences for the production catalog

The production catalog receives a stronger input than the current `kind/range/name/role` DTO:

- exact source partition;
- ordered named tree fields;
- multiple range-exact parser claims per piece;
- explicit virtual layout events;
- literal/XML substructure;
- recovery classification and diagnostics.

Each catalog entry must declare:

- accepted tree product and named-field shape;
- physical child roles and exact ownership;
- allowed trivia binders;
- soft-keyword remaps;
- delimiter and layout alternatives;
- public `Sc*` accessor and stub requirements;
- recovery alternatives;
- whether a pinned native runtime production can be reused after contract validation.

Unknown tree products or fields remain discoverable neutral data, but a required unknown production does not fall
through to raw emission. A future exact compiler feature is supported when its executable parser shape and catalog
contract succeed; it is never rejected merely because its compiler version was not named in advance.

The bundled parser may still appear in differential tests. It may not provide runtime tokens, repair a failed region,
decide whether source is supported, or replace a failed exact capture.

## Acceptance criteria

This research decision is implemented only when:

1. exact source plus tree evidence, not the bundled lexer, is the sole ready syntax input;
2. every physical UTF-16 code unit belongs to exactly one ordered physical leaf;
3. virtual layout is stored separately, ordered, structurally owned, and deterministic;
4. soft words are classified by parser role rather than spelling;
5. all comments remain exact even when compiler comment dropping is enabled;
6. interpolation preserves delimiters, escapes, injections, and malformed tails exactly;
7. XML is partitioned inside parser-proven ranges without relying on desugared XML-tree names or bundled Scala
   lexing;
8. recovery retains exact text and visible dotc diagnostics without weakening validation;
9. the twenty-four evidence and source-plan invariants run at their declared boundary;
10. capability self-tests cover releases and a moving nightly without version-conditioned behavior;
11. every failure produces a named structured report at the correct module or source scope;
12. the ready Scala language emits only a validated plan or the deterministic whole-file neutral result, never a
    placeholder, bundled fallback, partial tree, embedded unknown leaf, or nondeterministic stub spine;
13. copied pinned tests and broad complex examples prove exact leaf ranges, direct-child ownership, public accessors,
    recovery safety, and complete stub determinism.
