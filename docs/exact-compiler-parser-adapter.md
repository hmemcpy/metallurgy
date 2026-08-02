# Exact compiler parser adapter

## Status

This document records a possible replacement for broad reflective access to dotc parser internals. It is not the
active parser architecture. The canonical compiler backend design remains `scala3-compiler-backend.md`.

## Problem

Metallurgy is one plugin binary, but each active module supplies its exact Scala compiler. Dotc does not publish a
stable interface for its untyped parser tree. The host therefore discovers and invokes compiler internals through an
isolated classloader. This preserves exact compiler ownership, but Java reflection reconstructs a Scala interface from
names and erased JVM descriptors. Failures appear during discovery or parsing instead of compilation.

The JVM process is not the root problem. The missing stable parser interface is. Same-process execution adds risks
from class identity, compiler global state, threads, and classloader retention, but a separate process would still
need a typed compiler-side implementation.

## Proposed module

A compiler-side adapter is compiled by the module's exact dotc during preparation. It owns every dotc and Scala value
and exposes one small host-owned Java interface or versioned binary protocol.

```text
IntelliJ and Metallurgy
  -> neutral request bytes
  -> exact compiler adapter
  -> typed dotc parser calls and tree traversal
  -> neutral snapshot bytes
  -> host validation and PSI planning
```

The external interface contains no Scala collection, function, compiler tree, compiler exception, or host Scala case
class. A minimal shape is:

```java
interface ExactParserAdapter {
  byte[] probe(byte[] request);
  byte[] parse(byte[] request);
}
```

The adapter directly uses the exact compiler's parser, context, source file, untyped trees, lazy fields, spans,
comments, diagnostics, end markers, and attachments. It converts them to the neutral wire model before returning.

## Preparation and caching

Preparation performs these steps before a module becomes ready:

1. Resolve and hash every exact compiler artifact.
2. Read compiler declaration evidence from classfiles and compiler TASTy.
3. Compile the adapter source with the exact compiler.
4. Load it with the exact compiler and run executable capability probes.
5. Admit only covered grammar, output, and semantic inventories.

The compiled adapter cache key includes all compiler artifact hashes and order, adapter source hash, wire schema
version, JDK target, and compilation flags. Compilation never occurs on the synchronous file parse path.

Capability discovery remains structural. A compiler version identifies evidence and artifacts but does not select
behavior. A future compiler admits only when the same adapter source compiles, the required capabilities execute, and
all inventories remain covered. New interface or grammar drift fails closed with a named capability.

## TASTy's role

The compiler jar's own TASTy retains many declarations that JVM generic signatures erase. `Trees.tasty` and
`untpd.tasty` can prove ordered product fields, aliases, unions, inheritance, nested collection element types, and
lazy field wrappers when those declarations are physically present. The encoding is not complete by itself:
supported compiler cells omit some physical product declarations, and an opaque type such as `FlagSet` hides its
`Long` carrier. Exact classfile declarations and executable probes must complement TASTy rather than being replaced
by it. TASTy-proven nominal declarations and classfile carrier evidence must agree whenever both are informative.

Compiler TASTy does not replace current-source parsing. A project's generated TASTy is typed compilation output. It
is unavailable for a new or broken edit, contains transformed trees, and does not preserve the complete trivia,
delimiter, layout, recovery, or source ownership evidence required for lossless PSI.

## Process placement

The first implementation can remain in the IntelliJ process behind a filtered child classloader. Parent-owned types
are limited to the Java interface and JDK classes; compiler and Scala dependencies remain child-owned.

The same wire protocol permits moving the adapter into a managed helper JVM if compiler static state, leaked threads,
fatal failures, cancellation, or memory isolation become operational problems. Process isolation is a deployment
choice, not a substitute for the typed adapter.

## Rejected primary approaches

- Precompiled per-version adapters impose a release matrix. They may seed the content-addressed cache but do not
  define admission.
- Method handles centralize and speed lookup but retain erased declarations and runtime ABI reconstruction. They are
  suitable only for the fixed adapter entry point.
- A compiler plugin runs inside compilation phases, may not run after parser failure, and does not naturally retain
  all parser-owned source evidence.
- Compiler print output is an unstable presentation format without complete structure, identity, positions, or
  recovery evidence.
- Tasty Inspector and tasty-query consume typed compiled TASTy rather than the live untyped parser result.

## Migration

1. Use exact compiler TASTy to validate every physically retained parser declaration, while retaining exact classfile
   evidence for omitted products and hidden runtime carriers.
2. Define and test the neutral wire schema, cancellation contract, and classloader purity rules.
3. Compile and cache the exact compiler adapter during preparation.
4. Compare adapter snapshots with the reflective bridge across every covered compiler, valid and malformed source,
   compiler options, comments, end markers, attachments, positions, and closure behavior.
5. Cut over atomically and delete the reflective parser bootstrap and tree traversal that the adapter replaces.

The active reflective bridge remains the sole parser until the adapter proves complete snapshot parity.
