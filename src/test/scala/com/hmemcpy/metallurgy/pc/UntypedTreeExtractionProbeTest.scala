package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.build.ScalacFlagsService
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.roots.OrderEnumerator
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.assertTrue

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** Probes the untyped (parser) tree extraction to confirm it preserves source grammar the typed tree desugars. */
final class UntypedTreeExtractionProbeTest extends ScalaLightCodeInsightFixtureTestCase:

  override def getTestDataPath: String = "src/test/testdata"

  private val scalaVersion = new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2")

  def testUntypedTreePreservesForComprehension(): Unit =
    withSession: session =>
      val source   =
        """object O:
           |  for
           |    x <- List(1, 2, 3)
           |    if x > 1
           |    y = x * 2
           |  yield y
           |""".stripMargin
      val snapshot = PcSnapshot("file:///ForProbe.scala", 0L, source)
      val _        = onPooledThread(session.scheduleRetypecheck(snapshot).get(30, TimeUnit.SECONDS))
      val dto      = session.untypedTreeDto(snapshot)
      assertTrue("untyped tree extracted", dto.isDefined)
      val kinds    = dto.get.physicalNodes.map(_.kind).toSet
      // The parser tree keeps the for-yield as a distinct construct; the typed tree would desugar it to flatMap/map.
      dump("for-comprehension", source, dto.get)
      assertTrue(s"expected a ForYield node, got kinds: $kinds", kinds.contains("ForYield"))

  def testUntypedTreePreservesParamClauseKinds(): Unit =
    withSession: session =>
      val source   =
        """trait Ord[A]
           |object O:
           |  def combine(using ev: Ord[Int])(x: Int)(y: String): Int = x
           |""".stripMargin
      val snapshot = PcSnapshot("file:///ClauseProbe.scala", 0L, source)
      val _        = onPooledThread(session.scheduleRetypecheck(snapshot).get(30, TimeUnit.SECONDS))
      val dto      = session.untypedTreeDto(snapshot)
      assertTrue("untyped tree extracted", dto.isDefined)
      dump("param-clauses", source, dto.get)
      val defDef   = dto.get.physicalNodes.find(_.kind == "DefDef")
      assertTrue("DefDef present", defDef.isDefined)
      val params   = dto.get.physicalNodes.filter(_.kind == "ValDef")
      assertTrue(s"expected 3 params (ev, x, y) across 3 clauses, got ${params.size}", params.size == 3)

  private def dump(label: String, source: String, dto: CompilerTreeDto): Unit =
    println(s"\n========== UNTYPED TREE: $label ==========")
    println(s"--- source ---\n$source")
    println("--- physical nodes (kind | range | name) ---")
    dto.physicalNodes.foreach { node =>
      val r       = node.range.fold("no-range")(rg => s"${rg.startOffset}-${rg.endOffset}")
      val snippet =
        node.range
          .flatMap(rg => Some(source.substring(rg.startOffset, math.min(rg.endOffset, source.length))))
          .getOrElse("")
      println(
        f"  ${node.id}%3d parent=${node.parentId.getOrElse("-")}%3s  ${node.kind}%-16s $r%-12s name=${node.name.getOrElse("")}  role=${node.role.getOrElse("-")}  src=[${snippet.replace("\n", "\\n")}]"
      )
    }

  private def withSession(test: PcSession => Unit): Unit =
    val temporaryDirectory = Files.createTempDirectory("pc-untyped-probe")
    val fetcher            = new MtagsFetcher(
      PcArtifactCache(temporaryDirectory.resolve("cache")),
      PresentationCompilerResolver.publicCoursier,
      BackgroundRunner.direct
    )
    val settings           = MetallurgySettings(getProject)
    try
      settings.setEnabled(getModule, enabled = true)
      val _ = onPooledThread(fetcher.jarsFor(scalaVersion.minor).get(120, TimeUnit.SECONDS))
      onPooledThread:
        val options =
          ScalacFlagsService.get(getProject).compilerOptions(getModule) :+ "-language:experimental.namedTuples"
        val session = PcSession.create(scalaVersion.minor, moduleClasspath, options, fetcher)
        try test(session)
        finally session.close()
    finally
      settings.setEnabled(getModule, enabled = false)
      deleteRecursively(temporaryDirectory)

  private def moduleClasspath: Seq[java.io.File] =
    OrderEnumerator
      .orderEntries(getModule)
      .recursively
      .withoutSdk
      .compileOnly
      .classes
      .getPathsList
      .getPathList
      .asScala
      .map(new java.io.File(_))
      .toSeq

  private def onPooledThread[A](body: => A): A =
    ApplicationManager.getApplication.executeOnPooledThread(() => body).get(120, TimeUnit.SECONDS)

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      val stream = Files.walk(path)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      finally stream.close()
