package com.hmemcpy.metallurgy.ecosystem

import com.hmemcpy.metallurgy.compilerbackend.{CompilerBackendRole, CompilerBackendState, Scala3CompilerBackend}
import com.hmemcpy.metallurgy.pc.PcSessionManager
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.project.Project
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.PlatformTestUtil
import org.jetbrains.plugins.scala.DependencyManagerBase.RichStr
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.base.libraryLoaders.{IvyManagedLoader, LibraryLoader}
import org.jetbrains.plugins.scala.editor.documentationProvider.ScalaDocQuickInfoGenerator
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScBindingPattern
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.assertTrue

import scala.jdk.CollectionConverters.*

final class EcosystemSemanticCorpusTest extends ScalaLightCodeInsightFixtureTestCase:

  private final case class CorpusCase(name: String, source: String, expectedType: String)

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.fromString("3.5.2").get

  override protected def defaultVersionOverride: Option[ScalaVersion] =
    Some(new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2"))

  override protected def additionalLibraries: Seq[LibraryLoader] = Seq(
    IvyManagedLoader(("org.typelevel"               %% "cats-core"           % "2.13.0").transitive()),
    IvyManagedLoader(("org.typelevel"               %% "cats-effect"         % "3.6.3").transitive()),
    IvyManagedLoader(("co.fs2"                      %% "fs2-core"            % "3.12.2").transitive()),
    IvyManagedLoader(("dev.zio"                     %% "zio"                 % "2.1.21").transitive()),
    IvyManagedLoader(("org.typelevel"               %% "shapeless3-deriving" % "3.4.0").transitive()),
    IvyManagedLoader(("com.softwaremill.sttp.tapir" %% "tapir-core"          % "1.11.50").transitive())
  )

  override def getTestDataPath: String =
    java.nio.file.Path.of("src", "test", "testdata").toAbsolutePath.toString

  override protected def setUp(): Unit =
    super.setUp()
    MetallurgySettings(getProject).setEnabled(getModule, enabled = true)
    setCompilerBasedHighlighting(enabled = true)

  override protected def tearDown(): Unit =
    try
      Scala3CompilerBackend.get(getProject).clear()
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
    finally super.tearDown()

  def testPinnedLibrarySemanticFactsUseCurrentCompilerResults(): Unit =
    val cases = Seq(
      CorpusCase(
        "Cats",
        """import cats.syntax.all.*
          |val result = List(1, 2).traverse(value => Option(value.toString))
          |""".stripMargin,
        "Option[List[String]]"
      ),
      CorpusCase(
        "CatsEffect",
        """import cats.effect.IO
          |val result = IO.pure(42).map(_.toString)
          |""".stripMargin,
        "cats.effect.IO[String]"
      ),
      CorpusCase(
        "Fs2",
        """import fs2.Stream
          |val result = Stream.emits(List(1, 2)).map(_.toString)
          |""".stripMargin,
        "fs2.Stream[[x] =>> fs2.Pure[x], String]"
      ),
      CorpusCase(
        "Zio",
        """import zio.ZIO
          |val result = ZIO.succeed(42).map(_.toString)
          |""".stripMargin,
        "zio.ZIO[Any, Nothing, String]"
      ),
      CorpusCase(
        "Shapeless",
        """import shapeless3.deriving.Labelling
          |case class Person(name: String, age: Int)
          |val result = summon[Labelling[Person]].elemLabels
          |""".stripMargin,
        "IndexedSeq[String]"
      ),
      CorpusCase(
        "Tapir",
        """import sttp.tapir.*
          |val result = endpoint.get.in("hello").out(stringBody)
          |""".stripMargin,
        "sttp.tapir.Endpoint[Unit, Unit, Unit, String, Any]"
      )
    )

    val measured = cases.zipWithIndex.map: (corpus, index) =>
      val file     = myFixture.configureByText(s"Ecosystem$index.scala", corpus.source)
      val prepared = PlatformTestUtil.waitForFuture(
        PcSessionManager.get(getProject).prepareCompilerBackend(file.getVirtualFile),
        60000L
      )
      assertTrue(s"${corpus.name} backend preparation failed", prepared.nonEmpty)

      val binding = PsiTreeUtil
        .findChildrenOfType(file, classOf[ScBindingPattern])
        .asScala
        .find(_.name == "result")
        .get
      val state   = Scala3CompilerBackend
        .get(getProject)
        .stateForActiveModule(binding, getModule, CompilerBackendRole.Binding)

      val actual = state match
        case CompilerBackendState.Current(renderedType, _) => renderedType
        case other                                         => throw new AssertionError(s"${corpus.name} state was $other")

      val quickInfo = ScalaDocQuickInfoGenerator.getQuickNavigateInfo(binding, binding).getOrElse("")
      assertTrue(s"${corpus.name} hover omitted ${corpus.expectedType}: $quickInfo", quickInfo.contains("result"))
      println(s"[ecosystem] ${corpus.name}: $actual")
      (corpus.name, normalize(corpus.expectedType), normalize(actual))

    val failures = measured.filter { case (_, expected, actual) => expected != actual }
    assertTrue(
      failures.map { case (name, expected, actual) => s"$name: expected '$expected', got '$actual'" }.mkString("\n"),
      failures.isEmpty
    )

  private def normalize(value: String): String = value.trim.replaceAll("\\s+", " ")

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)
