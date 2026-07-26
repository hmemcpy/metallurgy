package com.hmemcpy.metallurgy.psiproducer

import java.util.concurrent.ConcurrentHashMap

/** Per-file decision for the dialect file-root parse when no accepted extraction is installed. A clean extraction in
  * [[DotcTreeSource]] drives the producer directly (keyed by verbatim text); this state covers the remaining outcomes
  * so parse #1 never shows a bundled parse the compiler has not yet vouched for or against:
  *
  *   - `Pending` — the compiler has not decided; the parse returns a placeholder leaf (no error nodes, no Scala
  *     expression structure) so the file is never painted red and never crashes on constructs the bundled parser cannot
  *     represent.
  *   - `Rejected` — the compiler reported errors; the bundled parser is appropriate (its errors are real).
  *   - `BundledFine` — the compiler accepted the source and the bundled parser already represents it; no producer
  *     needed.
  *
  * Keyed by file URL (not source text) so a PSI copy or index parse of the same content cannot win the pending race for
  * the physical file and strand it. Reset on edit so new content re-analyzes.
  */
object ProducerParseState:

  sealed trait Decision
  case object Unknown     extends Decision
  case object Pending     extends Decision
  case object Rejected    extends Decision
  case object BundledFine extends Decision

  private val byFile = new ConcurrentHashMap[String, Decision]()

  def decisionFor(fileUrl: String): Decision =
    Option(byFile.get(fileUrl)).getOrElse(Unknown)

  def isSettled(fileUrl: String): Boolean =
    decisionFor(fileUrl) == Rejected || decisionFor(fileUrl) == BundledFine

  def isPending(fileUrl: String): Boolean =
    decisionFor(fileUrl) == Pending

  /** Atomically move Unknown -> Pending and report whether this thread won the race. A terminal decision is untouched.
    */
  def becomePendingIfUnknown(fileUrl: String): Boolean =
    byFile.replace(fileUrl, Unknown, Pending) || byFile.putIfAbsent(fileUrl, Pending) == null

  def reject(fileUrl: String): Boolean =
    byFile.get(fileUrl) match
      case Rejected => false
      case _        => byFile.put(fileUrl, Rejected); true

  def useBundled(fileUrl: String): Boolean =
    byFile.get(fileUrl) match
      case BundledFine => false
      case _           => byFile.put(fileUrl, BundledFine); true

  def reset(fileUrl: String): Unit =
    val _ = byFile.remove(fileUrl)

  def clear(): Unit =
    byFile.clear()
