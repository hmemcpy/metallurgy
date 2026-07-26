package com.hmemcpy.metallurgy.psiproducer

import java.util.concurrent.ConcurrentHashMap

/** Source-keyed decision for the dialect file-root parse when no accepted extraction is installed. A clean extraction
  * in [[DotcTreeSource]] drives the producer directly; this state covers the remaining outcomes so parse #1 never shows
  * a bundled parse the compiler has not yet vouched for or against:
  *
  *   - `Pending` — the compiler has not decided; the parse returns a placeholder leaf (no error nodes, no Scala
  *     expression structure) so the file is never painted red and never crashes on constructs the bundled parser cannot
  *     represent. The first `Unknown` parse schedules the backend pass.
  *   - `Rejected` — the compiler reported errors; the bundled parser is appropriate (its errors are real).
  *   - `BundledFine` — the compiler accepted the source and the bundled parser already represents it; no producer
  *     needed.
  *
  * Keyed by verbatim source text, matching [[DotcTreeSource]], so the decision follows the content (an edit that
  * changes the text starts at `Unknown` again).
  */
object ProducerParseState:

  sealed trait Decision
  case object Unknown     extends Decision
  case object Pending     extends Decision
  case object Rejected    extends Decision
  case object BundledFine extends Decision

  private val bySource = new ConcurrentHashMap[String, Decision]()

  def decisionFor(source: String): Decision =
    Option(bySource.get(source)).getOrElse(Unknown)

  /** A terminal decision that means the bundled parser should run (compiler-rejected, or bundled already fine). */
  def isSettled(source: String): Boolean =
    decisionFor(source) == Rejected || decisionFor(source) == BundledFine

  /** Atomically move Unknown -> Pending and report whether this thread won the race (so only it schedules the backend
    * work). A terminal decision is left untouched.
    */
  def becomePendingIfUnknown(source: String): Boolean =
    bySource.replace(source, Unknown, Pending) || bySource.putIfAbsent(source, Pending) == null

  def reject(source: String): Boolean =
    bySource.get(source) match
      case Rejected => false
      case _        => bySource.put(source, Rejected); true

  def useBundled(source: String): Boolean =
    bySource.get(source) match
      case BundledFine => false
      case _           => bySource.put(source, BundledFine); true

  def reset(source: String): Unit =
    val _ = bySource.remove(source)

  def clear(): Unit =
    bySource.clear()
