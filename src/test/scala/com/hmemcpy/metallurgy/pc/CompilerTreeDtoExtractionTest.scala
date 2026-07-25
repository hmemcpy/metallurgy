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

/** The compiler-tree DTO extraction drives a real typed tree and partitions nodes by source provenance: physical nodes
  * (real, non-zero, source-derived spans, each carrying a source range) are separated from synthetic ones.
  */
final class CompilerTreeDtoExtractionTest extends ScalaLightCodeInsightFixtureTestCase:

  override def getTestDataPath: String = "src/test/testdata"

  private val scalaVersion = new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2")

  def testExtractsPhysicalAndSyntheticNodes(): Unit =
    withSession: session =>
      val source    = "class A {\n  val x = 1\n}\n"
      val snapshot  = PcSnapshot("file:///DtoCase.scala", 0L, source)
      val _         = onPooledThread(session.scheduleRetypecheck(snapshot).get(30, TimeUnit.SECONDS))
      val dto       = session.compilerTreeDto(snapshot)
      assertTrue("compiler-tree DTO present after retypecheck", dto.isDefined)
      val extracted = dto.get
      assertTrue("has physical (source-derived) nodes", extracted.physicalNodes.nonEmpty)
      assertTrue("every physical node carries a source range", extracted.physicalNodes.forall(_.range.isDefined))
      assertTrue("a ValDef is among the physical nodes", extracted.physicalNodes.exists(_.kind == "ValDef"))
      val all       = extracted.physicalNodes ++ extracted.syntheticNodes
      val ids       = all.map(_.id).toSet
      assertTrue("nodes carry parent ids (hierarchy)", all.exists(_.parentId.isDefined))
      assertTrue("every parent id resolves to a known node", all.flatMap(_.parentId).forall(ids.contains))

  private def withSession(test: PcSession => Unit): Unit =
    val temporaryDirectory = Files.createTempDirectory("pc-dto-extraction")
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
      .compileOnly
      .withoutSdk
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
