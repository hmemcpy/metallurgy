package com.hmemcpy.metallurgy.ideprobe

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Instant
import java.util.concurrent.{CountDownLatch, TimeUnit}

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
  private val ReplaceWithSupportedSyntaxEndpoint =
    Request[FileRef, Map[String, String]]("metallurgy/replace-with-supported-syntax")
  private val PollDelay = new CountDownLatch(1)

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

      val openedProject = probe.openProject(intelliJ.workspace, WaitLogic.none)
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
      assertEquals("false", status("compatibleIntegerLiteral.loadedBeforeProbe"))
      assertEquals("true", status("compatibleIntegerLiteral.loadedAfterProbe"))
      assertTrue(
        s"Compatible integer literal probe failed: ${status("compatibleIntegerLiteral.probe")}",
        status("compatibleIntegerLiteral.probe").startsWith("Right(Vector(")
      )
      record(timeline, "metallurgy-parser-ready", formatMap(status).trim)
      assertEquals("true", status("syntaxWidget.present"))
      assertEquals("true", status("syntaxWidget.componentVisible"))
      assertEquals("true", status("syntaxWidget.componentShowing"))
      record(timeline, "syntax-capability-widget-visible", s"text=${status("syntaxWidget.text")}")

      val originalSource = Files.readString(repoRoot.resolve("dogfood/src/main/scala/dogfood/showcase/TransparentInline.scala"))
      assertEquals(originalSource, Files.readString(target))
      val infos = probe.highlightInfos(target, project)
      write(artifacts.resolve("highlights.txt"), infos.mkString("\n") + "\n")
      val errors = infos.filter(_.severity == HighlightInfo.Severity.Error)
      assertTrue(s"expected no ERROR highlights on TransparentInline.scala, got: $errors", errors.isEmpty)
      record(timeline, "highlighting-finished", s"highlights=${infos.size}")
      assertEquals("unsupported source changed while failing closed", originalSource, Files.readString(target))

      val unavailableStatus = await(2.minutes, 250.millis, "visible syntax capability finding") {
        val current = probe.send(StatusEndpoint, fileRef)
        Option.when(
          current("syntaxWidget.text").startsWith("Metallurgy: syntax unavailable") &&
            occurrences(current("syntaxWidget.tooltip"), s"file=$target") == 1
        )(current)
      }
      write(artifacts.resolve("syntax-capability-unavailable.txt"), formatMap(unavailableStatus))
      val unavailableTooltip = unavailableStatus("syntaxWidget.tooltip")
      assertEquals("true", unavailableStatus("syntaxWidget.componentShowing"))
      assertTrue(unavailableTooltip.contains("<b>State:</b> Unavailable"))
      assertTrue(unavailableTooltip.contains("operation=ProduceWholeFilePsi"))
      assertTrue(unavailableTooltip.contains("grammar-role=&lt;unidentified&gt;"))
      assertTrue(unavailableTooltip.contains("org.scala-lang:scala3-compiler_3:3.7.4"))
      assertTrue(
        unavailableTooltip.contains("IDE build=IC-261.26222.65; Scala plugin=org.intellij.scala:2026.1.20")
      )
      assertTrue(unavailableTooltip.contains("ReadVerbatimSource, EditVerbatimSource"))
      assertTrue(unavailableTooltip.contains("state=Recorded; stage=Catalog"))
      assertTrue(unavailableTooltip.contains("<b>Remediation and retry:</b> ImplementationRequired"))
      record(timeline, "syntax-capability-finding-visible", s"text=${unavailableStatus("syntaxWidget.text")}")

      val repeatedInfos = probe.highlightInfos(target, project)
      val repeatedErrors = repeatedInfos.filter(_.severity == HighlightInfo.Severity.Error)
      assertTrue(s"repeated highlighting produced ERROR findings: $repeatedErrors", repeatedErrors.isEmpty)
      val deduplicatedStatus = await(2.minutes, 250.millis, "deduplicated syntax capability finding") {
        val current = probe.send(StatusEndpoint, fileRef)
        Option.when(occurrences(current("syntaxWidget.tooltip"), s"file=$target") == 1)(current)
      }
      write(artifacts.resolve("syntax-capability-deduplicated.txt"), formatMap(deduplicatedStatus))
      record(timeline, "syntax-capability-finding-deduplicated", s"text=${deduplicatedStatus("syntaxWidget.text")}")

      val compilerEvent = probe.send(ReplaceWithSupportedSyntaxEndpoint, fileRef)
      write(artifacts.resolve("compiler-event-quiescence.txt"), formatMap(compilerEvent))
      assertEquals("true", compilerEvent("compilerEvent.subscribedBeforeEdit"))
      assertEquals("true", compilerEvent("compilerEvent.metallurgyBackendQuiesced"))
      assertEquals("<none>", compilerEvent("compilerEvent.compilationUnit"))
      assertEquals(target.toString, compilerEvent("compilerEvent.documentPath"))
      assertEquals(target.toString, compilerEvent("compilerEvent.matchedSource"))
      assertTrue(
        compilerEvent("compilerEvent.correlation"),
        compilerEvent("compilerEvent.correlation").contains("startAndFinish=true")
      )
      assertTrue(
        compilerEvent("compilerEvent.correlation"),
        compilerEvent("compilerEvent.correlation").contains("documentGeneration=true")
      )
      assertTrue(
        compilerEvent("compilerEvent.correlation"),
        compilerEvent("compilerEvent.correlation").contains("finishedSource=true")
      )
      record(
        timeline,
        "scala-compiler-event-subscribed-before-edit",
        s"subscribedAt=${compilerEvent("compilerEvent.subscribedAt")} " +
          s"editStartedAt=${compilerEvent("compilerEvent.editStartedAt")}"
      )
      record(
        timeline,
        "scala-compilation-started-after-edit",
        s"at=${compilerEvent("compilerEvent.compilationStartedAt")} id=${compilerEvent("compilerEvent.compilationId")}"
      )
      record(
        timeline,
        "scala-compilation-finished-matched",
        s"at=${compilerEvent("compilerEvent.compilationFinishedAt")} " +
          s"documentVersion=${compilerEvent("compilerEvent.documentVersion")} " +
          s"matchedSource=${compilerEvent("compilerEvent.matchedSource")} " +
          s"sourceCount=${compilerEvent("compilerEvent.sourceCount")}"
      )
      record(timeline, "metallurgy-compiler-backend-quiesced", s"documentVersion=${compilerEvent("compilerEvent.documentVersion")}")
      record(timeline, "scala-compilation-correlation-proven", compilerEvent("compilerEvent.correlation"))
      val resolvedInfos = probe.highlightInfos(target, project)
      write(artifacts.resolve("resolved-highlights.txt"), resolvedInfos.mkString("\n") + "\n")
      val resolvedErrors = resolvedInfos.filter(_.severity == HighlightInfo.Severity.Error)
      assertTrue(s"resolved supported source has ERROR highlights: $resolvedErrors", resolvedErrors.isEmpty)
      probe.await(
        WaitLogic.emptyBackgroundTasks(
          basicCheckFrequency = 100.millis,
          ensurePeriod = 3.seconds,
          ensureFrequency = 50.millis,
          atMost = 2.minutes
        )
      )
      record(timeline, "all-background-tasks-stably-empty", "ensurePeriod=3 seconds")
      val resolvedStatus = await(2.minutes, 250.millis, "cleared syntax capability finding") {
        val current = probe.send(StatusEndpoint, fileRef)
        Option.when(occurrences(current("syntaxWidget.tooltip"), s"file=$target") == 0)(current)
      }
      write(artifacts.resolve("syntax-capability-resolved.txt"), formatMap(resolvedStatus))
      assertEquals("true", resolvedStatus("syntaxWidget.present"))
      assertEquals("true", resolvedStatus("syntaxWidget.componentShowing"))
      assertEquals(
        """package dogfood.showcase
          |
          |trait Base
          |trait Other
          |import scala.language.experimental.namedTypeArguments
          |type TopAlias = Base
          |type AppliedAlias = List[Int]
          |def typedTop(value: Base): Base = value
          |def choose[A]: A = ???
          |val namedApplication = choose[A = Int]
          |val typedValue: Base = ???
          |var typedVariable: Base = typedValue
          |def topApply = List(1)
          |val topNumber = 1
          |var topIdent = topNumber
          |class Braced[A, +B, -C](value: Base)(using context: Other) extends Base derives CanEqual {
          |  self: Other =>
          |  type Alias = Base
          |  def declared: Base = value
          |  val declaredValue: Base = value
          |  var declaredVariable: Base = value
          |  trait NestedTrait[T]():
          |    type Abstract
          |  end NestedTrait
          |  object NestedObject:
          |    def selected = List(1).head
          |    val tupled = (topNumber, topIdent)
          |    var infixed = topNumber + topIdent
          |  end NestedObject
          |}
          |trait Indented[-T]():
          |  def blocked = {
          |    val local = 1
          |    local
          |  }
          |end Indented
          |object Empty {}
          |enum Signal:
          |  case Ready
          |end Signal
          |""".stripMargin,
        Files.readString(target)
      )
      record(timeline, "syntax-capability-finding-cleared", s"text=${resolvedStatus("syntaxWidget.text")}")

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
      val _ = PollDelay.await(interval.toNanos, TimeUnit.NANOSECONDS)
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

  private def occurrences(value: String, needle: String): Int =
    value.sliding(needle.length).count(_ == needle)

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
