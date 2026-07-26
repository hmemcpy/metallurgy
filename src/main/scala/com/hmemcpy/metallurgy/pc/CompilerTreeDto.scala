package com.hmemcpy.metallurgy.pc

/** Whether a compiler tree node corresponds to text the user wrote or to a compiler insertion. */
enum CompilerSourceClass:
  case PhysicalSource
  case SyntheticSemantic

/** A compiler tree node classified by source provenance. Physical nodes carry an exact source range and feed PSI
  * production; synthetic nodes carry semantic data only and never become physical, navigable, or indexed PSI. `name` is
  * the compiler's declared/reference name (a def/val/param/ident name), carried so the producer can place it in the
  * resolver's expected slot without re-deriving it from the token stream.
  */
final case class CompilerSourceNode(
    id: Long,
    parentId: Option[Long],
    kind: String,
    range: Option[PcSourceRange],
    sourceClass: CompilerSourceClass,
    name: Option[String] = None
)

/** The compiler's typed tree as neutral DTOs, partitioned by source provenance. */
final case class CompilerTreeDto(physicalNodes: Vector[CompilerSourceNode], syntheticNodes: Vector[CompilerSourceNode])

/** The typed tree and the compiler's diagnostics for the same snapshot, extracted together so a consumer never observes
  * a tree from one compiler run and diagnostics from another. Diagnostics are the authority for the
  * no-error-suppression invariant: a range the compiler rejected is never represented as valid PSI.
  */
final case class CompilerTreeExtraction(tree: CompilerTreeDto, diagnostics: Seq[PcDiagnostic])

object CompilerTreeDto:

  /** Admit a compiler tree to the physical collection only when its span is a real, non-zero, source-derived range. No
    * span, a synthetic span, or a zero-extent span is synthetic-only: it carries semantics but no source range that PSI
    * production may occupy.
    */
  def sourceClassOf(spanExists: Boolean, spanIsSourceDerived: Boolean, start: Int, end: Int): CompilerSourceClass =
    if spanExists && spanIsSourceDerived && start >= 0 && start < end then CompilerSourceClass.PhysicalSource
    else CompilerSourceClass.SyntheticSemantic

  /** Partition classified nodes into physical and synthetic collections. */
  def apply(nodes: Iterable[CompilerSourceNode]): CompilerTreeDto =
    val (physical, synthetic) = nodes.partition(_.sourceClass == CompilerSourceClass.PhysicalSource)
    CompilerTreeDto(physical.toVector, synthetic.toVector)
