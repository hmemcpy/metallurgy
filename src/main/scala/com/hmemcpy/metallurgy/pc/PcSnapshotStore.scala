package com.hmemcpy.metallurgy.pc

import java.time.Duration
import java.util.LinkedHashMap

/** Bounded, access-ordered snapshot ownership for one presentation-compiler session. */
private[pc] final class PcSnapshotStore(
    maxEntries: Int = 10,
    timeToIdle: Duration = Duration.ofMinutes(10),
    nanoTime: () => Long = () => System.nanoTime()
):
  require(maxEntries > 0, "snapshot capacity must be positive")

  private val snapshots = new LinkedHashMap[String, PcSnapshot](maxEntries + 1, 0.75f, true):
    override protected def removeEldestEntry(eldest: java.util.Map.Entry[String, PcSnapshot]): Boolean =
      super.size() > maxEntries

  def accept(candidate: PcSnapshot): PcSnapshot = synchronized:
    evictExpired()
    val active = Option(snapshots.get(candidate.fileUri))
      .filter(_.isFor(candidate.documentVersion))
      .getOrElse:
        snapshots.put(candidate.fileUri, candidate)
        candidate
    active.touch(nanoTime())
    active

  def current(fileUri: String): Option[PcSnapshot] = synchronized:
    evictExpired()
    Option(snapshots.get(fileUri)).map: snapshot =>
      snapshot.touch(nanoTime())
      snapshot

  def matching(fileUri: String, documentVersion: Long): Option[PcSnapshot] =
    current(fileUri).filter(_.isFor(documentVersion))

  def clear(): Unit = synchronized(snapshots.clear())

  private[pc] def size: Int = synchronized:
    evictExpired()
    snapshots.size()

  private def evictExpired(): Unit =
    val cutoff   = nanoTime() - timeToIdle.toNanos
    val iterator = snapshots.entrySet().iterator()
    while iterator.hasNext do if iterator.next().getValue.lastAccessNanos < cutoff then iterator.remove()
