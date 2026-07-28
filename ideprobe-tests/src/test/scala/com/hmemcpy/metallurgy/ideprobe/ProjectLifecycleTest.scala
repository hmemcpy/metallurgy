package com.hmemcpy.metallurgy.ideprobe

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Instant

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.junit.Test
import org.junit.Assert.{assertEquals, assertTrue}
import org.virtuslab.ideprobe.jsonrpc.JsonRpc.Method.Request
import org.virtuslab.ideprobe.protocol.{FileRef, HighlightInfo, IdeMessage, ProjectRef}
import org.virtuslab.ideprobe.{IdeProbeFixture, WaitLogic}

import pureconfig.generic.auto._

final class ProjectLifecycleTest extends IdeProbeFixture {
  private val StatusEndpoint = Request[FileRef, Map[String, String]]("metallurgy/status")

  @Test
  def opensImportsIndexesAndHighlightsWithoutInternalErrors(): Unit = {
    val repoRoot = Path.of(sys.env("METALLURGY_REPO_ROOT"))
    val artifacts = repoRoot.resolve("ideprobe-tests/target/ideprobe-artifacts/latest")
    val exportedLogs = repoRoot.resolve("ideprobe-tests/target/ideprobe-artifacts/idea-logs")
    recreateDirectory(artifacts)
    recreateDirectory(exportedLogs)
    val timeline = artifacts.resolve("stages.log")

    val fixture = fixtureFromConfig("ideprobe.conf")
      .withAfterWorkspaceSetup { (_, workspace) =>
        writeProjectSettings(workspace)
      }
      .withAfterIntelliJInstall { (_, intelliJ) =>
        writeApplicationSettings(intelliJ.paths.config)
      }

    fixture.run { intelliJ =>
      val probe = intelliJ.probe
      record(timeline, "probe-connected", s"pid=${probe.pid()}")
      val plugins = probe.plugins
      write(artifacts.resolve("plugins.txt"), plugins.mkString("\n") + "\n")
      assertTrue("Metallurgy plugin is not loaded", plugins.exists(_.id == "com.hmemcpy.metallurgy"))
      probe.preconfigureJdk()
      record(timeline, "jdk-preconfigured", "ProjectJdkTable initialized")

      val openedProject = probe.openProject(intelliJ.workspace.resolve("build.sbt"), WaitLogic.none)
      record(timeline, "project-opened", openedProject.toString)
      val project = ProjectRef.Default

      var observedIndexing = false
      val model = await(10.minutes, 2.seconds, "sbt project model") {
        val tasks = probe.backgroundTasks()
        if (tasks.exists(_.startsWith("Indexing project "))) observedIndexing = true
        val current = probe.projectModel(project)
        Option.when(current.modules.nonEmpty && current.modules.exists(_.contentRoots.paths.sources.nonEmpty))(current)
      }
      record(
        timeline,
        "external-project-model",
        s"project=${model.name} modules=${model.moduleNames.mkString(",")}"
      )

      probe.await(
        WaitLogic.emptyNamedBackgroundTasks(
          basicCheckFrequency = 2.seconds,
          ensurePeriod = 3.seconds,
          ensureFrequency = 200.millis,
          atMost = 10.minutes
        )
      )
      record(timeline, "smart-and-background-tasks-settled", s"indexingObserved=$observedIndexing")

      val target = intelliJ.workspace.resolve("src/main/scala/dogfood/showcase/TransparentInline.scala")
      val fileRef = FileRef(target, project)
      val status = await(5.minutes, 1.second, "Metallurgy parser preparation") {
        val current = probe.send(StatusEndpoint, fileRef)
        val states = current.collect { case (key, value) if key.startsWith("module.") => value }
        Option.when(states.exists(_.startsWith("Ready(")) || states.exists(_.startsWith("Unavailable(")))(current)
      }
      write(artifacts.resolve("metallurgy-status.txt"), formatMap(status))
      assertEquals("true", status("globallyEnabled"))
      val unavailable = status.filter { case (key, value) => key.startsWith("module.") && value.startsWith("Unavailable(") }
      assertTrue(s"Metallurgy parser preparation failed: $unavailable", unavailable.isEmpty)
      assertTrue(s"Metallurgy parser never became ready: $status", status.values.exists(_.startsWith("Ready(")))
      record(timeline, "metallurgy-parser-ready", formatMap(status).trim)

      val infos = probe.highlightInfos(target, project)
      write(artifacts.resolve("highlights.txt"), infos.mkString("\n") + "\n")
      val errors = infos.filter(_.severity == HighlightInfo.Severity.Error)
      assertTrue(s"expected no ERROR highlights on TransparentInline.scala, got: $errors", errors.isEmpty)
      record(timeline, "highlighting-finished", s"highlights=${infos.size}")

      val messages = probe.messages()
      write(artifacts.resolve("ide-messages.txt"), messages.mkString("\n\n") + "\n")
      val internalErrors = messages.filter(_.level == IdeMessage.Level.Error)
      assertTrue(s"IDE reported internal errors: ${internalErrors.mkString("\n\n")}", internalErrors.isEmpty)
      record(timeline, "message-pool-clean", s"messages=${messages.size}")
    }

    val ideaLog = findIdeaLog(exportedLogs)
    Files.copy(ideaLog, artifacts.resolve("idea.log"), StandardCopyOption.REPLACE_EXISTING)
    val suspicious = suspiciousLogEntries(Files.readString(ideaLog))
    write(artifacts.resolve("internal-errors.txt"), suspicious.mkString("\n\n") + "\n")
    assertTrue(s"idea.log contains internal errors:\n${suspicious.mkString("\n\n")}", suspicious.isEmpty)
    record(timeline, "idea-log-clean-after-shutdown", s"path=$ideaLog")
    assertTrue("stage timeline was not retained", Files.isRegularFile(timeline))
  }

  private def writeProjectSettings(workspace: Path): Unit = {
    val idea = Files.createDirectories(workspace.resolve(".idea"))
    write(
      idea.resolve("metallurgy.xml"),
      """<project version="4">
        |  <component name="MetallurgySettings">
        |    <option name="globallyEnabled" value="true" />
        |  </component>
        |</project>
        |""".stripMargin
    )
  }

  private def writeApplicationSettings(config: Path): Unit = {
    val options = Files.createDirectories(config.resolve("options"))
    write(
      options.resolve("ide.general.xml"),
      """<application>
        |  <component name="GeneralSettings">
        |    <option name="showTipsOnStartup" value="false" />
        |  </component>
        |</application>
        |""".stripMargin
    )
    write(
      options.resolve("updates.xml"),
      """<application>
        |  <component name="UpdatesConfigurable">
        |    <option name="CHECK_NEEDED" value="false" />
        |    <option name="PLUGINS_CHECK_NEEDED" value="false" />
        |    <option name="SHOW_WHATS_NEW_EDITOR" value="false" />
        |    <option name="WHATS_NEW_SHOWN_FOR" value="261" />
        |  </component>
        |</application>
        |""".stripMargin
    )
  }

  private def suspiciousLogEntries(content: String): Vector[String] = {
    val entryStart = """^\d{4}-\d{2}-\d{2}.*\s(?:INFO|WARN|ERROR|SEVERE)\s-.*$""".r
    val errorStart = """^\d{4}-\d{2}-\d{2}.*\s(?:ERROR|SEVERE)\s-.*$""".r
    val lines = content.linesIterator.toVector
    val starts = lines.indices.filter(index => entryStart.matches(lines(index))).toVector
    starts.zip(starts.drop(1) :+ lines.size).map { case (start, end) =>
      lines.slice(start, end).mkString("\n").trim
    }.filter { entry =>
      errorStart.matches(entry.linesIterator.next()) ||
      entry.contains("\n\tat ") ||
      entry.contains("\nCaused by:") ||
      entry.contains("Exception in thread")
    }
  }

  private def findIdeaLog(root: Path): Path = {
    val stream = Files.walk(root)
    try
      stream.iterator().asScala
        .filter(path => path.getFileName.toString == "idea.log" && Files.isRegularFile(path))
        .toVector
        .sortBy(path => Files.getLastModifiedTime(path).toMillis)
        .lastOption
        .getOrElse(throw new AssertionError(s"IDE log was not exported under $root"))
    finally stream.close()
  }

  private def await[A](atMost: FiniteDuration, interval: FiniteDuration, description: String)(
      attempt: => Option[A]
  ): A = {
    val deadline = atMost.fromNow
    var result = attempt
    while (result.isEmpty && deadline.hasTimeLeft()) {
      Thread.sleep(interval.toMillis)
      result = attempt
    }
    result.getOrElse(throw new AssertionError(s"Timed out waiting for $description after $atMost"))
  }

  private def record(path: Path, stage: String, detail: String): Unit = {
    val line = s"${Instant.now()} $stage $detail\n"
    Files.write(path, line.getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)
    println(s"[ideprobe-stage] $stage $detail")
  }

  private def formatMap(values: Map[String, String]): String =
    values.toVector.sortBy(_._1).map { case (key, value) => s"$key=$value" }.mkString("\n") + "\n"

  private def recreateDirectory(path: Path): Unit = {
    if (Files.exists(path)) {
      val stream = Files.walk(path)
      try stream.iterator().asScala.toVector.reverse.foreach(entry => Files.delete(entry))
      finally stream.close()
    }
    val _ = Files.createDirectories(path)
  }

  private def write(path: Path, content: String): Unit = {
    val _ = Files.createDirectories(path.getParent)
    Files.writeString(path, content, StandardCharsets.UTF_8)
    ()
  }
}
