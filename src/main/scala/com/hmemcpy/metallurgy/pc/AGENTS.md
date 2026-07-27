# `pc` package — exact Scala 3 compiler boundary

This package is the only boundary allowed to access exact Scala 3 compiler implementations. It owns parser and
semantic bridge protocols, isolated classloaders, neutral compiler DTOs, capability discovery, exact artifact
acquisition, and per-module presentation-compiler sessions.

The normative architecture is `docs/scala3-compiler-backend.md`. This file contains standing implementation
constraints only.

## Boundary rules

- Prefer published compiler and Scalameta interfaces.
- Use typed structural protocols when a published interface does not expose a required operation.
- Confine raw reflection to private bridge implementations and only where structural calls cannot express construction
  or invocation.
- Discover callable capabilities; never branch on compiler versions, implementation fingerprints, or class-name
  allowlists.
- Never let a dotc class, collection, context, tree, type, symbol, driver, or reporter escape its exact-artifact
  classloader.
- Export immutable neutral parser products, source positions, diagnostics, types, symbols, occurrences, navigation
  targets, capability results, and identities.
- Close sessions and classloaders deterministically.

Parser and semantic bridges are independent. Parser availability does not imply presentation-compiler availability,
and best-effort TASTy producer and consumer support are separate capabilities.

## Parser bridge

The parser bridge:

- constructs the parser from the exact compiler artifact;
- parses verbatim source without running typer;
- preserves ordered named product fields;
- exports source ranges, point positions, zero-width and synthetic provenance, and parser diagnostics;
- adapts older artifact layouts to the same neutral DTOs through structural capability probes.

Parsing is synchronous and must not wait for artifact acquisition, background compilation, an EDT callback, or a
semantic snapshot. Artifact preparation completes before the module activates its Scala parser.

No parser result may be reconstructed from a typed tree.

## Semantic bridge

The semantic bridge publishes one whole-file neutral snapshot for an exact source version and module generation. A
snapshot includes types, symbols, occurrences, completion data, navigation targets, diagnostics, compiler identity,
and every freshness generation.

Only a Current snapshot supplies semantics. Pending, Unavailable, Failed, Missing, and Stale states remain explicit and
never invoke bundled Scala inference.

No synchronous PSI or editor query starts compiler work and waits.

## Type rendering

A rendered type must agree with dotc and round-trip through the installed Scala PSI parser when it is used to construct
`ScType`.

Check in this order:

1. exact-version compiler output through a REPL `:type` probe and `-Xprint:typer`;
2. the intended normalized compiler type rather than a raw internal singleton or reference representation;
3. `ScalaPsiElementFactory.createTypeFromText` round-trip to the intended `ScType`.

`createTypeFromText` may degrade an invalid subterm to `Any`; a returned value alone is not proof.

### Module and companion references

- A static module reference renders as `symbol.showFullName + ".type"` when singleton identity is required.
- A path-dependent module uses the raw term-reference prefix because `showFullName` loses the value owner.
- Ordinary value types widen term references before rendering.
- Raw `Type.show` may emit internal names or singleton parentheses that are not valid source.

### Polymorphic function values

A polymorphic function value is represented by a refinement of `scala.PolyFunction` with a polymorphic `apply`.
Anonymous binders require collision-free stable names. Rebuild the `PolyType` with `newLikeThis`, allowing dotc to
substitute every bound `TypeParamRef`, then rebuild the refinement.

Do not publish standalone body subexpressions whose types contain free `TypeParamRef`s from the enclosing polymorphic
function.

## Structural access

Runtime compiler types frequently use cached implementation subclasses. Structural protocols must target callable
members and assignable contracts, not `getSimpleName` equality.

When a structural lookup fails:

1. enumerate the exact-loader methods and signatures;
2. confirm whether the context is explicit, implicit, or absent;
3. express the discovered general callable shape structurally when possible;
4. add an isolated reflective operation only when structural access cannot represent it;
5. add a capability and classloader-isolation test.

Build child-classloader Scala collections through APIs loaded by that classloader; never cast them to the host Scala
runtime's collection classes.

## Best-effort TASTy

The full compiler build is the producer. The interactive presentation compiler is a consumer only.

- Probe `-Ybest-effort` production independently from `-Ywith-best-effort-tasty` consumption.
- Add `META-INF/best-effort` as a downstream classpath root only when consumption is available.
- Key artifacts and sessions by upstream output and classpath generation plus artifact content.
- Preserve error or unknown provenance from broken upstream declarations.
- Never use best-effort TASTy to parse the current source file or select a PSI shape.

## Tests

- Give every snapshot case a unique file URI and document version.
- Assert exact rendered types after whitespace normalization; substring checks are insufficient.
- Reproduce every surprising type or diagnostic against the exact compiler before changing the bridge.
- Test public-interface access first, structural access second, isolated reflection last.
- Test classloader release and capability failure independently.
- Exercise build-produced cross-module break, consume, repair, rename, removal, reload, and restart.
