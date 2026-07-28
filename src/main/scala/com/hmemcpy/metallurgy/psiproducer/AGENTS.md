# `psiproducer` package — deterministic Scala 3 PSI production

This package synchronously turns exact-compiler parser evidence and verbatim source into one complete
Scala-plugin-compatible PSI tree. It owns the neutral preparation language, source-evidence planning, typed production
catalog, whole-file `PsiBuilder` plan, file element, and stub root.

The normative architecture is `docs/scala3-compiler-backend.md`. This file contains standing grammar and lifecycle
constraints only.

## One syntax path

A Ready active module has exactly one parser path:

```text
source
  -> exact parser bridge
  -> neutral parser snapshot
  -> source evidence
  -> production catalog
  -> closed whole-file builder plan
  -> Scala AST and PSI
```

The producer never:

- consumes typed trees as syntax;
- consults the bundled Scala parser;
- chooses repair regions;
- schedules compiler work from parsing;
- waits for background or EDT work;
- publishes a replacement syntax tree later;
- selects behavior from compiler or plugin versions.

## Neutral preparation

Preparing and Activating modules use an unrelated neutral language and file type. They have no Scala base language,
lexer, parser, references, stubs, indices, annotators, inspections, completion, or refactoring extensions.

Activation publishes a new module epoch and queues one `FileContentUtilCore.reparseFiles` batch. A stale preparation
cannot activate a newer epoch. Do not simulate dumb mode, call a view-provider reload directly, or request reindexing
manually.

Preserve virtual-file, document, and range identity through activation. Do not retain PSI pointers across the language
change.

## Parser evidence

Only neutral DTOs from the exact parser bridge enter this package. The evidence must include ordered named fields,
source ranges, point positions, zero-width and synthetic provenance, diagnostics, source identity, and exact compiler
identity.

Every source interval is assigned exactly once to a significant token, trivia leaf, delimiter, separator, or parent
production. Indentation and outdent events are zero-width structural evidence. Reassembling ordered leaves must equal
the original source byte for byte.

Scanner replay may validate evidence but cannot choose the production hierarchy.

## Production catalog

All grammar-to-PSI behavior is declared in the typed production catalog. An entry accounts for:

- every compiler field;
- child cardinality and order;
- source, token, trivia, delimiter, and layout ownership;
- recovery behavior;
- target PSI element type and implementation capability;
- every public `Sc*` accessor;
- stub fields, serializer, indices, and navigation identity.

Compile the entire plan before opening `PsiBuilder` markers. Unknown fields, unowned ranges, overlapping ownership,
missing accessors, incomplete stub contracts, or unbalanced plans fail closed.

New syntax support requires an inventory update and complete catalog entry. Do not add a parser-error check or an
isolated construct branch.

## PSI shape

The installed Scala PSI public interfaces are the consumer contract. Native implementations are reused only after an
executable probe proves the catalog entry. Compatibility PSI and stubs remain inside the Scala-plugin bridge.

Composites use balanced `PsiBuilder` markers and `marker.done(elementType)`. A leaf or collapsed node that looks the
same in a PSI viewer is not equivalent.

Public accessor tests define required direct-child and nesting relationships. Important examples include:

| Element | Required shape |
| --- | --- |
| `ScFunction` | name token directly under the function; complete parameter clauses; return type as `ScTypeElement` |
| `ScParameter` | declared type inside the parameter-type production |
| `ScPatternDefinition` | binding pattern inside a non-null pattern list |
| `ScReferenceExpression` | identifier/select represented by a reference-expression production |
| `ScStableCodeReference` | qualifier/name chain represented by stable-reference productions |
| file declarations | top-level definitions are direct children of the file content root |

Type grammar is recursive. Never wrap a composite type in a single reference node merely to obtain a visible tree.

## File root, stubs, and indices

`doParseContents` returns `builder.getTreeBuilt.getFirstChildNode`, matching the platform default. Returning the
wrapper root creates an extra file node and hides top-level declarations from lexical resolution.

The file element owns a stable `ScStubFileElementType` identity and schema version. Emit the complete AST before
`DefaultStubBuilder` derives stubs. Register compatibility serializers and indices statically.

Identical content, parser capability, catalog version, PSI target capabilities, and stub schema must produce identical:

- AST element types, ranges, and ordering;
- public accessor observations;
- stub types, fields, ordering, and serialized bytes;
- index keys and targets;
- pointers and navigation.

Verify cold and warm parsing, copies, edits, reparse, closed files, restart, index rebuild, and neutral/ready
transitions.

## Recovery and unknown syntax

Invalid edits preserve exact text and parser diagnostics while producing only catalog-declared structurally safe
recovery nodes.

An unknown required compiler-valid production produces deterministic neutral file-scoped PSI and a project capability
report. It does not publish partial Scala PSI, build stubs, consult the bundled parser, or hide the incompatibility.

## Tests

- Use broad nested sources alongside minimized upstream fixtures.
- Assert exact text, element type, range, parent, direct children, and every catalogued accessor.
- Assert complete stub and index signatures, not selected node counts.
- Exercise invalid intermediate edits and recovery at every production boundary.
- Compare native and compatibility targets through the same observable contract.
- Preserve copied Scala snippets, assertions, and expected values exactly.
