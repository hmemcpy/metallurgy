package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.{
  CompilerSourceClass,
  CompilerSourceNode,
  CompilerTreeDto,
  CompilerTreeExtraction,
  PcSourceRange
}
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}

/** DotcTreeSource is the cache the dialect file-root parse consults. Installation is generation-idempotent: a caller
  * that re-installs the same extraction (the daemon re-analyzing after a producer reload) learns it changed nothing, so
  * a redundant re-reparse is suppressed without a one-shot flag.
  */
final class DotcTreeSourceTest extends ScalaLightCodeInsightFixtureTestCase:

  override def getTestDataPath: String =
    java.nio.file.Path.of("src", "test", "testdata").toAbsolutePath.toString

  private def extraction(nodeId: Long): CompilerTreeExtraction =
    val node = CompilerSourceNode(nodeId, None, "ValDef", Some(PcSourceRange(0, 5)), CompilerSourceClass.PhysicalSource)
    val dto  = CompilerTreeDto(Vector(node), Vector.empty)
    CompilerTreeExtraction(dto, Seq.empty)

  override def setUp(): Unit =
    super.setUp()
    DotcTreeSource.clear()

  override def tearDown(): Unit =
    DotcTreeSource.clear()
    super.tearDown()

  def testFirstInstallReportsChanged(): Unit =
    assertTrue("first install changes the cache", DotcTreeSource.install("val x = 1", extraction(1L)))

  def testReinstallSameExtractionIsIdempotent(): Unit =
    val source = "val x = 1"
    assertTrue(DotcTreeSource.install(source, extraction(1L)))
    assertFalse("re-installing the same extraction is a no-op", DotcTreeSource.install(source, extraction(1L)))

  def testDifferentExtractionReportsChanged(): Unit =
    val source = "val y = 2"
    assertTrue(DotcTreeSource.install(source, extraction(1L)))
    assertTrue("a different extraction changes the cache", DotcTreeSource.install(source, extraction(2L)))

  def testExtractionForReturnsLatestInstalled(): Unit =
    val source = "val z = 3"
    val later  = extraction(2L)
    val _      = DotcTreeSource.install(source, extraction(1L))
    val _      = DotcTreeSource.install(source, later)
    assertEquals("latest installed extraction is served", later, DotcTreeSource.extractionFor(source).orNull)

  def testExtractionForUnknownSourceIsEmpty(): Unit =
    assertTrue("unknown source has no extraction", DotcTreeSource.extractionFor("absent").isEmpty)
