# Refinement and annotated type proof

## Exact compiler contract

The source and REPL input in this directory run unchanged with Scala 3.7.4. Compile, REPL `:type`, and
`-Xprint:typer` establish the exact compiler meaning for braced, layout, and parentless refinements and for nested type
annotations. The parser inventory proves the exact `RefinedTypeTree(tpt, refinements)` and `Annotated(arg, annot)`
products, fields, positions, ancestry, source ranges, scanner tokens, provenance, attachments, deterministic snapshot,
and lossless reconstruction.

| Evidence | SHA-256 |
| --- | --- |
| `RefinementAnnotatedTypes.scala` | `4f09522ce711cd23df859ba0441cd5284e6399aeaff78cf366ba436634ac7433` |
| `repl.in` | `7112e11b9996a10b707fe88130134264e81db2566fa4fc8bc85b36851e651122` |
| Compile output | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |
| Typer output | `43ec3594eb576e3e225406ae3034dbec4a46edc4a20cce8515d8ccc70351d893` |
| REPL output | `3fc72e95372654032edf11c8337bcd93c15f361c8e3d40c57ae3c26cdc053391` |

## PSI contract and boundary

IC 261.26222.65 with Scala plugin 2026.1.20 supplies native `ScCompoundTypeElement`, `ScRefinement`, and
`ScAnnotTypeElement` roles. Capability probes verify compound components and refinement, refinement types and declared
holders, annotated type children, and the annotation container and annotation child. Physical tests cover exact text,
ranges, direct children, parents, order, all public accessors, copies, pointers, edits, recovery, stubs, serialization,
reopen, recursive admitted type owners, and braced, layout, and parentless forms.

Refinement members are limited to already admitted function, value, variable, and type alias declaration shells.
Definitions with term bodies, nested templates, capture forms, term refinements, and unrelated expression, pattern,
quote, semantic, and modified owner families fail closed. Annotation ancestry is admitted only through the compiler's
`Annotated.annot` field; definition annotations retain their separate modifier ancestry.

## Persistence

The native role additions change the canonical physical plan and advance the file persistence schema to 14. The
reviewed canonical schema fingerprint is `87f1db3803235457f6f9cc8c04fdd5f46f1ce028ab5ae5fd2879c48c261e52d6`.
