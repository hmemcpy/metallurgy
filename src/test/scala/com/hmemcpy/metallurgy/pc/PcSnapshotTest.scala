package com.hmemcpy.metallurgy.pc

import org.junit.Assert.{assertEquals, assertFalse, assertSame, assertTrue}
import org.junit.Test

import java.time.Duration

final class PcSnapshotTest:

  @Test
  def queryValuesAreWriteOnceAndScopedToDocumentVersion(): Unit =
    val snapshot = PcSnapshot.atTime("file:///Main.scala", 1L, "val value = 1", 0L)
    val key      = QueryKey.TypeAt(4)

    assertTrue(snapshot.cached[Option[String]](key, 1L).isEmpty)
    assertEquals(Some("Int"), snapshot.cache(key, Some("Int"), 2L))
    assertEquals(Some("Int"), snapshot.cache(key, Some("String"), 3L))
    assertEquals(Some(Some("Int")), snapshot.cached[Option[String]](key, 4L))
    assertTrue(snapshot.isFor(1L))
    assertTrue(snapshot.isStale(2L))

  @Test
  def matchingSnapshotReusesTheActiveInstance(): Unit =
    val store     = new PcSnapshotStore()
    val original  = PcSnapshot("file:///Main.scala", 1L, "val value = 1")
    val duplicate = PcSnapshot("file:///Main.scala", 1L, "val value = 1")

    assertSame(original, store.accept(original))
    assertSame(original, store.accept(duplicate))
    assertTrue(store.matching(original.fileUri, 1L).contains(original))
    assertTrue(store.matching(original.fileUri, 2L).isEmpty)

  @Test
  def eleventhFileEvictsLeastRecentlyUsedSnapshot(): Unit =
    var now   = 0L
    val store = new PcSnapshotStore(nanoTime = () => now)
    (1 to 10).foreach: index =>
      now += 1
      store.accept(PcSnapshot.atTime(s"file:///$index.scala", 1L, "", now))

    val _ = store.current("file:///1.scala")
    now += 1
    val _ = store.accept(PcSnapshot.atTime("file:///11.scala", 1L, "", now))

    assertEquals(10, store.size)
    assertTrue(store.current("file:///1.scala").isDefined)
    assertFalse(store.current("file:///2.scala").isDefined)

  @Test
  def idleSnapshotsExpire(): Unit =
    var now   = 0L
    val store = new PcSnapshotStore(timeToIdle = Duration.ofNanos(10), nanoTime = () => now)
    val _     = store.accept(PcSnapshot.atTime("file:///Main.scala", 1L, "", now))

    now = 11L

    assertEquals(0, store.size)
