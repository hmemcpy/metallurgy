# Deterministic stub and index contracts

## Question

What complete AST and ordered stub signatures must remain identical for the same source and capability set across cold
parse, warm parse, copies, indexing, edits, restart, and native-versus-compatibility PSI; and which index, pointer,
closed-file, and reparse assertions make nondeterminism impossible to overlook?

## Decision

The ready parser has one source of syntax and one source of stubs:

```text
exact source + compiled production catalog + resolved target capabilities
  -> complete deterministic AST
  -> registered Scala/compatibility stub factories
  -> complete ordered stub tree
  -> serialized stub tree + forward index
  -> StubIndex results and navigation
```

Metallurgy does not build a second compiler-to-stub representation. It emits the complete source-compatible AST, then
lets IntelliJ's `DefaultStubBuilder` visit that AST and invoke the registered native or compatibility stub factories.
The production catalog declares the expected AST, public accessor, stub-field, and index effects and verifies every
stage.

Four canonical projections are recorded for every fixture:

1. a complete logical AST signature, including every composite, leaf, recovery error, source interval, and virtual
   layout record;
2. a target-local AST signature, adding exact runtime element types and PSI implementations;
3. a complete ordered logical and target-local stub signature;
4. normalized forward-index occurrences and observable index-query results.

At any fixed document version, the same source, parser options, catalog, and resolved target set produce byte-identical
signatures across cold and warm construction, indexing, editor state, copies, cache eviction, and restart. An edit has
an exact expected delta, and every independent construction of the resulting version must agree. Native and
compatibility targets for the same catalog production must have identical logical AST, accessor, logical stub-field,
forward-index, query, and navigation projections. Their target-local element types, implementation classes, serializer
external IDs, and physical serialization layouts are expected to differ and are compared with separate exact
expectations.

This distinction is mandatory. Normalizing away runtime targets would miss an incorrect element type or serializer;
requiring native and compatibility element types to have the same identity would make a compatibility implementation
impossible.

Pending files remain unrelated, non-stub-bearing PSI. A module cannot enter the ready language until the production
catalog, target factories, stub serializers, and their executable contracts have passed capability validation. Syntax
or stub failure never becomes an error-suppression rule, a partial tree, a bundled-parser fallback, or a fabricated
stub.

## Platform facts that determine the design

There is no separate Scala source-stub parser in the pinned plugin. `ScStubFileElementType.ScFileStubBuilderImpl`
extends IntelliJ's `DefaultStubBuilder`; it chooses the Scala file stub root and otherwise delegates the AST walk
([`ScStubFileElementType.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScStubFileElementType.scala#L13-L56)).
The platform walker visits source children in preorder, creates a stub only when a registered factory exists and
`shouldCreateStub` accepts the node, attaches it to the nearest stub ancestor, and marks it dangling when its immediate
AST parent has no stub
([`DefaultStubBuilder.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/core-impl/src/com/intellij/psi/stubs/DefaultStubBuilder.java#L20-L105)).
Stub preorder is therefore a direct observable consequence of the emitted AST.

The pinned Scala plugin shares child stub element types between Scala 2 and Scala 3. `ScStubElementType` supplies
factory behavior, left binding, external IDs, and the locality rule: elements below expression or code-block
ancestors are normally not stubbed, while template members remain eligible
([`ScStubElementType.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScStubElementType.scala#L15-L89)).
The complete native holder is registered before stub serialization initializes
([`ScalaElementType.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/parser/ScalaElementType.scala#L24-L147),
[`scala-plugin-common.xml`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/resources/META-INF/scala-plugin-common.xml#L550-L587)).

Stub payloads and indices are syntax-derived contracts, not an invitation to replace them with compiler symbol data.
For example, the native function serializer writes fourteen ordered values and derives method, top-level, main,
implicit, and given index effects from its stub
([`ScFunctionElementType.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScFunctionElementType.scala#L22-L140)).
Template definitions, properties, and type aliases have different ordered payloads and index effects
([`ScTemplateDefinitionElementType.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScTemplateDefinitionElementType.scala#L25-L220),
[`ScPropertyElementType.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScPropertyElementType.scala#L15-L70),
[`ScTypeAliasElementType.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScTypeAliasElementType.scala#L16-L128)).
The catalog must preserve the PSI shapes from which those native algorithms compute their values.

`StubTreeBuilder` selects the language stub descriptor from `FileContent`, constructs indexing PSI, finalizes stub
order, caches one tree per `FileContent`, and deterministically orders multiple language roots
([`StubTreeBuilder.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/core-impl/src/com/intellij/psi/stubs/StubTreeBuilder.java#L54-L222)).
This path is used for a closed file: `FileContentImpl` applies language substitution and creates indexing PSI against
the original `VirtualFile`, without requiring an editor, document, or pre-existing cached physical PSI
([`FileContentImpl.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/core-impl/src/com/intellij/util/indexing/FileContentImpl.java#L74-L95),
[`FileContentImpl.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/core-impl/src/com/intellij/util/indexing/FileContentImpl.java#L210-L237)).
An editor-only parser cache therefore cannot satisfy the contract.

`StubUpdatingIndex` chooses the builder and its version, builds the stub tree, serializes the tree, and stores its
forward occurrences
([`StubUpdatingIndex.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/indexing-impl/src/com/intellij/psi/stubs/StubUpdatingIndex.java#L157-L305)).
`SerializedStubTree.equals` compares only serialized tree bytes; the platform's debug similarity check compares
serializer, element type, and topology, but neither check proves payload-field or forward-index equality
([`SerializedStubTree.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/indexing-impl/src/com/intellij/psi/stubs/SerializedStubTree.java#L98-L120),
[`SerializedStubTree.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/indexing-impl/src/com/intellij/psi/stubs/SerializedStubTree.java#L210-L262)).
The owned verifier must check those omitted dimensions.

When both stubs and AST exist, IntelliJ reconciles them by exact preorder count and exact element-type identity.
Stubbed PSI is retained by its spine ordinal; a mismatch clears the file state, schedules rebuilding, and raises an
index-mismatch exception
([`FileTrees.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/core-impl/src/com/intellij/psi/impl/source/FileTrees.java#L125-L218)).
The platform also offers `StubTextInconsistencyException.checkStubTextConsistency`, which independently builds stubs
from text and from existing PSI, but its comparison is a debug rendering rather than the complete signature required
here
([`StubTextInconsistencyException.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/core-impl/src/com/intellij/psi/stubs/StubTextInconsistencyException.java#L50-L130)).

## Canonical encoding

All signatures use one checked, versioned binary encoding. A pretty JSON rendering is emitted for review and first
difference reporting, but JSON is not the equality authority.

The binary vocabulary is deliberately small:

```scala
enum CanonicalValue:
  case Absent
  case Bool(value: Boolean)
  case Int32(value: Int)
  case Int64(value: Long)
  case Text(utf16CodeUnits: Vector[Int])
  case Sequence(values: Vector[CanonicalValue])
  case Record(schema: Text, fields: Vector[(Text, CanonicalValue)])
```

Each value starts with a one-byte tag. Integers are fixed-width big-endian. A text value is a 32-bit count followed by
unsigned 16-bit Java/Scala `Char` values, preserving line endings, supplementary pairs, and unpaired surrogates
without charset replacement. Sequences and records carry 32-bit counts. Record field order is schema-defined and
duplicate fields are rejected. Arbitrary maps, object identity, `hashCode`, locale-sensitive text, implementation
`toString`, registry ordinals, timestamps, absolute paths, and iteration order from hash collections are forbidden.

Every canonical projection carries its signature schema version, exact source SHA-256 over canonically encoded UTF-16
code units, parser-option digest, parser-capability digest, and compiled-catalog digest. A target-local projection
additionally carries the resolved PSI-target-capability digest. These fields participate in its canonical bytes and
SHA-256.

An observation envelope carries the ready module epoch, source URI, exact parser artifact coordinates and digest,
target platform, and Scala-plugin identities for diagnostics. The envelope does not participate in projection
equality: a physical copy, a fresh epoch with the same inputs, a restart, and parser artifacts proving the same
capabilities must be comparable. Native and compatibility observations have different target-local projections but
compare the same logical projection bytes.

On mismatch, the test reports the first differing record path and both pretty values; a hash alone is not a useful
failure.

## Complete AST signatures

### Logical AST projection

The logical signature is a preorder sequence rooted at the unwrapped Scala file content:

```scala
final case class LogicalAstEntry(
  ordinal: Int,
  parentOrdinal: Option[Int],
  childOrdinal: Int,
  production: ProductionId,
  variant: VariantId,
  role: PsiRole,
  sourceStart: Int,
  sourceEnd: Int,
  node: LogicalNode
)

enum LogicalNode:
  case Composite(publicPsi: PublicPsiType, childCount: Int)
  case Leaf(tokenRole: TerminalRole, text: Utf16Text)
  case Error(description: Utf16Text, diagnosticIdentity: DiagnosticIdentity)
```

It includes every composite, whitespace leaf, comment, delimiter, identifier, literal part, interpolation/XML part,
and visible recovery error. A leaf's text must equal the exact source slice at its UTF-16 range. Composite ranges,
zero-length recovery points, sibling order, direct-child placement, and wrapper nodes are significant. The root also
contains the ordered virtual-layout records from the source plan, because indentation equivalents are structural
metadata rather than physical leaves.

The source-plan signature and the AST-walk signature are computed independently and joined by production instance,
tree path, range, and role. A plan that is deterministic but emits the wrong AST fails; an AST walker that cannot
associate a node with exactly one planned instance also fails.

PSI accessor results are a fifth logical record attached to the owning composite. Every catalog-declared accessor
records result cardinality, ordered target ordinals, null/empty behavior, text ranges, and primitive value when
applicable. This is how native and compatibility PSI prove the same public behavior even when their runtime classes
differ.

### Target-local AST projection

The target-local projection extends every entry with:

- selected target mode: native or compatibility;
- stable catalog target capability ID;
- actual `IElementType` language ID and debug name;
- stub serializer external ID when the element is stub-bearing;
- actual PSI implementation class;
- actual public `Sc*` interfaces required by the catalog;
- `PsiErrorElement.getErrorDescription` for errors.

Registry numeric element-type IDs are not persisted in the signature: their assignment depends on extension loading
order. Within one running process, the verifier separately asserts object identity with the capability-probed element
type. Across restart it compares the stable target tuple above.

Target-local equality is exact for repeated runs with the same resolved target set. A native-versus-compatibility run
compares each target-local projection with its own checked expectation, while requiring the complete logical
projection and accessor record to be identical.

### AST invariants

A ready file is accepted only when:

1. root text, concatenated physical leaves, document text, and source text are exactly equal;
2. every source code unit has exactly one physical leaf owner;
3. all composite and leaf ranges are nested, ordered, and inside the source;
4. every planned composite exists once with its expected direct-child shape;
5. every physical AST node has exactly one logical production role;
6. every error node is declared by a matched recovery alternative and every compiler parser diagnostic remains
   visible;
7. compiler-valid source has no recovery error;
8. two independent builds produce identical logical and target-local bytes.

Failure before complete AST emission produces the catalog's deterministic source-scoped neutral file result. Failure
after a supposedly validated ready AST is a Metallurgy correctness failure and is reported; it is never hidden from
highlighting or repaired with a regional parse.

## Complete ordered stub signature

The verifier builds stubs through all three platform paths:

1. `PsiFileImpl.calcStubTree` from the already emitted AST;
2. `StubTreeBuilder.buildStubTree(FileContent)` from exact text with no reused file PSI;
3. `SerializedStubTree` serialize/deserialize round trip.

All three yield the same canonical logical and target-local stub signatures.

### Logical stub projection

```scala
final case class LogicalStubEntry(
  ordinal: Int,
  parentOrdinal: Option[Int],
  childOrdinal: Int,
  production: ProductionId,
  role: PsiRole,
  dangling: Boolean,
  fields: Vector[LogicalStubField],
  indexEffects: Vector[LogicalIndexOccurrence]
)
```

Entries are the platform plain-list preorder. The root is ordinal zero. Parent and child ordinals are explicit, so a
tree cannot pass merely because it has the same flattened element names. `dangling` is part of the signature because
it records whether a stub-bearing node's immediate AST parent was skipped.

Fields appear in the catalog's reviewed serializer order. Every field has a descriptive ID, canonical type, presence,
and value. Arrays and nested values retain order. Optional absence differs from an empty string or empty sequence.
Text fields are exact source-derived values produced by the native factory; the verifier must not recalculate them
from compiler symbols. Every native `serialize`/`deserialize` operation has a matching field extractor in the Scala
PSI inventory, and every compatibility serializer declares the same logical-field mapping.

The logical projection is independent of physical serializer layout. Native and compatibility implementations for
one production must expose identical logical values unless the catalog explicitly declares an implementation-only
field, which is recorded solely in the target-local projection.

### Target-local stub projection

The target-local entry adds:

- actual element-type identity in process;
- stable serializer external ID;
- concrete stub and PSI classes;
- physical fields in exact serialization order with their codecs;
- `shouldCreateStub` result and parent-stub constraint;
- file-root external ID, debug name, and stub schema version.

For the same target set, exact preorder ordinal, parent, element type, dangling state, physical field order/value, and
index effects must match across all construction paths. Serialize then deserialize must reproduce both canonical
projections. Deserialization must not create PSI; PSI is materialized only when the platform asks for a stub ordinal.

Raw serialized bytes are compared only within the same initialized serializer-name store as an additional diagnostic.
They are not the cross-restart contract: IntelliJ stores serializers and repeated names through persistent numeric
enumerators. Serializer registration is keyed by stable external ID and sorted before enumeration
([`SerializationManagerImpl.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/indexing-impl/src/com/intellij/psi/stubs/SerializationManagerImpl.java#L245-L299)).
Cross-restart equality is proved after deserialization by the canonical stub and forward-index signatures.

## Forward-index and query signatures

The catalog inventories every Scala index key and every native/compatibility `indexStub` effect. The pinned keys are
centralized in `ScalaIndexKeys`
([`ScalaIndexKeys.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/stubs/index/ScalaIndexKeys.scala#L22-L68)).
Native sink helpers clean qualified names and omit null or empty occurrences
([`package.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/stubs/elements/package.scala#L48-L61)).
The inventory must cover Scala keys and Java-facing effects emitted for Scala definitions; testing only short Scala
names is insufficient.

Two records are required:

1. **ordered sink record** — invoke each serializer's `indexStub` with a recording `IndexSink` and retain stub ordinal,
   call ordinal, stable index-key name, and canonically encoded key;
2. **normalized stored record** — read the serialized forward map and sort by index-key name and that key's declared
   canonical codec, retaining each ordered `StubIdList`.

Each index key has a catalog-declared key codec. Strings and character sequences use exact UTF-16 content after the
native preprocessing contract. FQN-hash keys record both the user-facing cleaned text used for a query fixture and
the stored key form. No generic `toString` fallback is allowed. A new key type cannot participate until its codec and
query fixture are reviewed.

Repeated calls for the same stub/key/value may be coalesced by platform indexing, so the ordered sink record proves
emitter behavior while the normalized stored record proves persisted behavior. Both have target-specific exact
expectations. Native and compatibility runs must produce identical normalized records and query results.

For every expected occurrence, query tests assert:

- exact key and scope;
- exact result count, with no duplicate PSI;
- result public PSI type, name, containing `VirtualFile`, text range, production role, and stub ordinal;
- valid `getNavigationElement` and `getOriginalElement`;
- exact resolve/navigation identity from at least one reference in another file;
- absence from every stale key after rename, move, deletion, or package change.

Fixtures deliberately include duplicate short names in different packages and nested scopes so a name-only assertion
cannot pass accidentally. Top-level definitions, classes, traits, objects, enums and cases, functions, properties,
type aliases, givens, extensions, imports/exports, annotations, parents, self types, implicit-related entries, and
Java-facing class lookup are included according to the generated index-effect inventory.

## Serializer identity and schema evolution

IntelliJ's source stub builder identity is:

```text
file serializer external ID : stub version : file debug name
```

with pushed properties appended only when snapshot mappings are enabled
([`StubBuilderType.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/core-impl/src/com/intellij/psi/stubs/StubBuilderType.java#L68-L95)).
Platform 261 has `StubUpdatingIndex.USE_SNAPSHOT_MAPPINGS == false`, so Metallurgy must not claim that a pushed property
changes this builder identity. Capability transitions use the already specified pending/ready reparse lifecycle and
its normal VFS index invalidation.

The ready dialect owns a file stub element type directly rather than subclassing `ScStubFileElementType`:
`ScStubFileElementType.getStubVersion` is final and cannot include a Metallurgy schema counter. The owned root preserves
the native `ScalaFile` root behavior through `DefaultStubBuilder` and has:

- permanent external ID `metallurgy.scala3.file`;
- permanent debug name `METALLURGY_SCALA3_FILE`;
- a non-negative stub version equal to the bundled Scala 3 file-stub version plus
  `MetallurgyStubSchemaVersion`;
- the bundled `shouldBuildStubFor` behavior unless an executable capability proves that its public contract changed.

The bundled component is read from the native Scala 3 file element capability, not selected from a plugin-version
table. The sum follows the bundled plugin's own scheme, which adds its current compiled-Scala stub version to the
platform base
([`ScStubFileElementType.scala`](https://github.com/JetBrains/intellij-scala/blob/8dd22d153b65c847f4ced8917dd7e02b83561e5d/scala/scala-impl/src/org/jetbrains/plugins/scala/lang/psi/stubs/elements/ScStubFileElementType.scala#L13-L32)).

Every compatibility child serializer:

- is statically registered before index initialization through Metallurgy's stub-element holder;
- has a globally unique permanent external ID under `metallurgy.scala3.*`;
- computes neither ID nor schema from compiler coordinates, runtime class names, localized display names, or catalog
  iteration order;
- implements symmetric explicit `serialize` and `deserialize`;
- creates the same public `Sc*` behavior from AST and from stub;
- declares every index effect and key codec.

The checked stub-schema manifest contains the root identity/version, every target's external ID, parent rule,
`shouldCreateStub` rule, ordered physical and logical fields/codecs, and index effects. A build gate compares it with
the generated Scala PSI inventory and production catalog. Adding/removing/reparenting a stub, changing locality,
changing field presence/order/codec, changing a serializer, changing an index effect, or changing a native-versus-
compatibility mapping requires incrementing `MetallurgyStubSchemaVersion`. An index extension's key descriptor or
preprocessing change also requires its own index-extension version change.

Changing an external ID is not a substitute for a schema bump. Duplicate external IDs, late serializer
initialization, negative root versions, unchanged versions after a schema change, or deserialize-without-serializer
are hard capability/test failures.

Exact compiler coordinates remain artifact identity, not compatibility branches. A compiler model or parser-option
change creates a new module epoch, transitions ready files through the neutral pending language, and lets the one
platform reparse batch remove and rebuild index state. No explicit `FileBasedIndex.requestReindex` is added to normal
Metallurgy flow.

## Cache and reparse rules

Metallurgy owns no independent AST, stub-tree, or index-result cache. IntelliJ may cache one built stub on a
`FileContent` instance and may read an up-to-date persisted stub before building from content; neither is permission to
reuse a result for another source, virtual file, language, or module epoch. The verifier always records which path
supplied the observation.

The rules are:

1. a ready view provider captures one immutable parser capability, compiled catalog, and resolved target set;
2. a document edit uses the provider's synchronous ready parser and ordinary PSI change events; it does not replace
   syntax after semantic compilation;
3. committed PSI changes clear or reconcile the platform's attached green stub through normal `PsiFileImpl`
   behavior—Metallurgy never installs a stub into a file;
4. index work receives the latest platform-provided `FileContent`, including transient committed document content
   where applicable, and never consults an editor-only extraction;
5. pending/ready or ready/pending language changes replace the provider through the single lifecycle reparse batch;
6. an epoch completion cannot publish a tree or stub into a provider from an older epoch;
7. index queries are made only after document commit and the platform's index completion barrier; temporarily
   unavailable data during event processing or ordinary dumb mode is not treated as an empty successful result;
8. body-only changes may leave a per-file-element-type stub modification count unchanged when the serialized tree is
   unchanged, while structural or stored-payload changes must invalidate the affected stub/index state;
9. a nonphysical PSI copy may build direct stubs but never creates a `FileBasedIndex` entry;
10. cache eviction and restart may change object identity, never canonical content or navigation identity.

The platform's per-file stub tracker rebuilds against transient-aware latest content and distinguishes serialized
stub changes from body-only changes
([`PerFileElementTypeStubModificationTracker.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/lang-impl/src/com/intellij/psi/stubs/PerFileElementTypeStubModificationTracker.java#L214-L286)).
`PsiFileImpl` installs persisted/built roots atomically and reconciles them with any AST
([`PsiFileImpl.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/core-impl/src/com/intellij/psi/impl/source/PsiFileImpl.java#L778-L849)).

## Required state matrix

Every row uses physical files with exact unmodified fixture text and unique simple names. The broad fixture contains
complex nested Scala 3 trees and all relevant source-level stub/index roles; focused fixtures isolate each
compatibility target and each edit transition.

| Lane | Initial state and operation | Required assertions |
| --- | --- | --- |
| Cold AST | Ready capability, physical file, no cached provider or document; acquire PSI and force AST | Exact source, logical AST, target-local AST, accessors, and AST-derived stub signatures |
| Cold index | Ready capability, unopened physical file; allow ordinary indexing | Indexing uses substituted ready language without editor state; exact text-built stub and forward signatures; no cached editor/document prerequisite |
| Warm stub | Drop PSI/provider caches after indexing; query an index | Result is created from persisted stubs, has exact ordinal/type/fields/range, and does not load the file AST |
| Warm AST reconciliation | Hold warm stub PSI, then force file AST | Same PSI identity where the platform promises it; exact spine count/type/ordinal; no mismatch exception; logical and target-local AST/stub signatures unchanged |
| Repeated build | Build twice from fresh `FileContent`, twice from existing PSI, and twice after cache eviction | All target-local canonical bytes are identical; no accumulation or duplicate index effect |
| Nonphysical PSI copy | `PsiFile.copy` after AST and after stub-backed acquisition | Exact logical/target-local AST and direct stub signature; copy is not entered in `FileBasedIndex`; no effect on the original file's index results |
| Physical file copy | Copy exact bytes to another unique `VirtualFile` | Each file has the same local signatures; project query returns one result per physical file, each navigating to its own range |
| Closed file | Close editor, release document/provider/PSI caches, then query every declared key | Exact results and navigation from stubs with no AST load; forcing one result's AST reconciles without changing results |
| Body-only edit | Commit an unsaved body edit that leaves the declared stub payload unchanged | AST/source signatures change as expected; stub and forward signatures remain equal; no stale or duplicate query; platform may legitimately retain the per-type stub modification count |
| Stub-payload edit | Commit an edit to a stored type/body/modifier/annotation value without changing declaration identity | Exact affected field and any derived index effect change; unrelated entries and ordinals remain stable where source order is unchanged |
| Structural insertion | Insert a stub-bearing declaration before, between, and after existing declarations | New exact preorder; pointers and indices resolve correct logical declarations rather than old ordinals; no duplicate old entries |
| Rename/package move | Rename indexed declarations and change package/top-level qualifier | Every old key is empty, every new key has exactly one expected result, and cross-file navigation moves to the new target |
| Deletion | Delete a declaration and then the whole file | All corresponding Scala and Java index effects disappear; declaration pointers become invalid; unrelated file entries remain |
| Whole-text replacement | Replace and commit the complete document with another broad exact fixture | No old spine or index entries survive; new AST/stub/index signatures are exact |
| Ready epoch change | Change exact compiler model/options or catalog capability result | Ready-to-pending and pending-to-ready provider replacements follow the lifecycle; pending has zero Scala stubs/results; ready has exactly the new signatures after one platform batch |
| Restart | Persist index, close project/application, reuse the same project and index directory, then reopen without first opening files | Deserialized canonical stub/forward signatures and all queries equal the pre-restart values; no parser/editor warm state is required |
| Concurrent cold access | Race closed-file indexing, PSI acquisition, index query, and AST forcing under platform read/index rules | Every completed observation is one allowed pending or ready state; ready observations have one exact signature; no partial spine, deadlock, duplicate result, or mismatch |
| Cancellation | Cancel parser preparation or indexing at each supported boundary, then retry current epoch | Pending remains non-stub-bearing; canceled trees are unpublished; retry produces the same exact ready signatures |
| Native target | Capability probe selects bundled element/factory | Exact native target-local golden plus common logical/accessor/stub/index expectations |
| Compatibility target | Probe makes the native contract unavailable and selects the registered compatibility target | Exact compatibility target-local golden plus byte-identical common logical/accessor/stub/index/query/navigation expectations |

Index and project waits use the fixture's commit and indexing completion mechanisms. There are no timing sleeps and no
assertions based on a transient progress-bar duration.

## Smart pointer and navigation contracts

Source smart pointers are sensitive to stub structure. The target platform can anchor stubbed PSI by virtual file,
stub preorder ID, and element type; restoration rejects a different ID or type
([`SmartPsiElementPointerImpl.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/core-impl/src/com/intellij/psi/impl/smartPointers/SmartPsiElementPointerImpl.java#L213-L281),
[`PsiAnchor.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/core-impl/src/com/intellij/psi/PsiAnchor.java#L420-L443)).
Spine order is therefore public behavior, not merely persistence detail.

For each representative indexed declaration, the harness creates pointers in both modes:

- while the element is stub-backed and the file AST is unloaded;
- after the AST is loaded and reconciled.

It then proves:

1. the pointer restores the same logical production and exact range after cache eviction and editor close;
2. forcing AST preserves the pointer target and, where the platform reuses it, PSI object identity;
3. insertions/deletions before the target update its range without retargeting to a same-named neighbor;
4. a body-only edit preserves the target;
5. deletion invalidates the target;
6. an index result's navigation element and a reference's resolve target identify the same declaration;
7. no pointer restoration loads a different source file with matching text or name.

Pointer survival is scoped to one ready language and compatible target set across ordinary edits. Pending-to-ready,
ready-to-pending, or native-to-compatibility provider replacement changes language and/or PSI class; a PSI pointer is
not promised across that boundary. Delayed work spanning an epoch transition retains `VirtualFile` plus a document
`RangeMarker` and reacquires ready PSI, matching the pending lifecycle contract.

## Fixture architecture

The owned harness has five layers:

1. **Canonical signature library.** Walks actual AST/stubs, records index sinks, canonicalizes supported values, hashes,
   and prints first structural differences. It contains no Scala grammar or source-string decisions.
2. **Catalog contract fixture.** Builds exact source through the parser-only bridge and compiled catalog, checks plan
   versus AST, exercises every accessor/stub extractor, and can select a declared native or compatibility capability
   result without changing source text.
3. **Physical light/project fixture.** Creates simple unique `.scala` files in module content, commits documents, waits
   for normal indexing, drops caches through supported test APIs, and performs real `StubIndex`, Java facade, resolve,
   navigation, and smart-pointer queries.
4. **Fresh-process fixture.** Runs write/close and reopen/read phases against the same temporary project and IntelliJ
   system directory. It records canonical values before shutdown and recomputes them after deserialization; it never
   treats raw serializer numeric IDs as stable.
5. **IDE process fixture.** Uses the local ide-probe harness for pending/ready transitions, closed/open editors,
   cancellation, concurrent activity, highlighting, and severe-log inspection. The endpoint returns neutral canonical
   records and query results rather than implementation objects.

Each fixture records whether AST, provider, document, stub tree, and index data existed before and after an operation.
That prevents a nominal "closed-file" or "stub-only" test from silently loading AST before its assertion.

The broad fixtures are ordinary complex Scala 3 programs, not lists of isolated tokens. They include nested templates,
top-level definitions, extension/given/type-level machinery, imports/exports, indentation and braces, annotations,
overloads, duplicated short names, Java-visible definitions, and cross-file references. Copied upstream snippets and
expectations remain byte-for-byte unchanged. Graduation later repeats the same observational contracts on selected
real projects.

## Mutation tests

The verifier is not trusted until each of these controlled mutations causes the named failure:

1. remove, duplicate, reorder, reparent, or change the range of one AST node;
2. change one leaf type, one source code unit, one trivia owner, or one virtual-layout record;
3. remove or alter a declared recovery error;
4. return an accessor child in the wrong order or with the wrong null/empty behavior;
5. remove, duplicate, reorder, reparent, or change the dangling flag of one stub entry;
6. change one logical stub field, physical field codec/order, optional-presence bit, or array order;
7. serialize and deserialize fields in different orders;
8. remove, duplicate, or retarget one index occurrence, including a Java-facing effect;
9. leave one old index key after rename or deletion;
10. change a serializer external ID, introduce a duplicate ID, or instantiate a compatibility serializer after index
    initialization;
11. change stub topology/payload/index effects without incrementing `MetallurgyStubSchemaVersion`;
12. compare only `SerializedStubTree.equals` or only platform debug topology and demonstrate that the owned payload or
    forward-index gate still fails;
13. map a native and compatibility production to different logical fields, accessors, index keys, or navigation
    target;
14. let a pending file expose a stub descriptor or Scala index entry;
15. reuse an old ready stub after a module epoch change;
16. restore a pointer to the wrong same-named declaration after an ordinal-changing edit;
17. make closed-file parsing consult editor-only state;
18. publish a partially built or canceled stub tree.

Mutations operate on catalog/signature values, registered test serializers, and platform lifecycle seams. They do not
rewrite fixture snippets, catch comparison failures, suppress platform errors, or add production test flags.

## Failure diagnostics and acceptance gates

A failure record contains:

- source URI, source hash, document version, module epoch, and parser-option digest;
- compiler artifact identity, catalog digest, resolved target capabilities, platform build, and Scala-plugin identity;
- phase: plan, AST, accessor, AST-derived stub, text-derived stub, serialize, deserialize, forward index, query,
  reconciliation, pointer, or navigation;
- expected and actual signature hashes;
- first differing canonical record path and both values;
- production/variant/role, AST ordinal, stub ordinal, element type, serializer external ID, root external ID/version,
  index key, and query scope where applicable;
- exception class, attachments, and whether AST/document/provider/index state was loaded.

The ordinary test and IDE-process gates reject `UpToDateStubIndexMismatch`, `StubTextInconsistencyException`,
serializer-not-found, non-stub PSI requested by a stub factory, duplicate serializer ID, stale-key result, duplicate
index result, incorrect navigation, unresolved pointer, unexpected PSI error, severe log entry, or signature
disagreement.

The platform may decline unsafe index reads during dumb mode or PSI event processing and may rebuild after a detected
serializer problem
([`StubTreeLoaderImpl.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/lang-impl/src/com/intellij/psi/stubs/StubTreeLoaderImpl.java#L49-L153),
[`StubTreeLoaderImpl.java`](https://github.com/JetBrains/intellij-community/blob/456919a9624bae72ac12efafc35d2b916cb0c5c5/platform/lang-impl/src/com/intellij/psi/stubs/StubTreeLoaderImpl.java#L238-L258)).
Tests wait for a legal observation point; they do not turn "temporarily unavailable" into success. Metallurgy neither
calls manual reindex as a repair nor catches the platform mismatch to continue.

Acceptance requires exact success for every matrix lane and every generated stub/index inventory row. A narrow
in-memory stub-existence assertion, one short-name query, or a pointer that merely returns its still-cached object
does not satisfy any broad gate.

## Relationship to the production catalog and parser lifecycle

The production catalog owns:

- whether a composite is stub-bearing;
- its native or compatibility target;
- parent/locality and dangling expectations;
- ordered logical fields and their source/accessor derivation;
- physical serializer schema for compatibility targets;
- all index effects and codecs;
- logical native-versus-compatibility parity.

The generated Scala PSI inventory supplies the native element, serializer, field-order, locality, and index evidence.
The reviewed catalog assigns semantic roles. Runtime probes build complete examples and prove both AST-backed and
stub-backed public behavior before publishing the immutable compiled catalog.

The pending language has an ordinary non-stub file root, no Scala PSI, and no Scala index effects. `Preparing` and
`Unavailable` remain pending. `Activating` may construct ready PSI for the platform transition, but semantic consumers
do not run until `Ready`. The pending-to-ready batch is the sole normal invalidation signal; indexing then invokes the
same synchronous ready parser used by editors.

A document edit never waits for semantic compilation to decide syntax or stub shape. Within a ready epoch, exact source
plus parser capability produces one AST and one stub signature synchronously. A compiler model, parser option, or
capability change creates a new epoch and uses provider replacement; it does not mutate a ready tree in place.

BETASTY and presentation-compiler snapshots remain semantic facilities, especially for cross-module recovery and
highlighting. They contribute neither source AST nodes nor stub/index structure and cannot make an otherwise
non-deterministic source parser acceptable.

## Result

The deterministic boundary is observable end to end:

```text
source
  = physical leaves
  = PSI text

catalog logical AST
  = native logical AST
  = compatibility logical AST

AST stub spine
  = fresh-text stub spine
  = deserialized stub spine

declared index effects
  = serialized forward index
  = closed-file queries
  = navigation targets
```

Target-local identities remain exact within each selected implementation, and every intentional native/compatibility
difference is explicit. Any other disagreement identifies the first broken layer instead of being hidden by cache
warmth, editor state, serializer bytes, index timing, or a permissive assertion.
