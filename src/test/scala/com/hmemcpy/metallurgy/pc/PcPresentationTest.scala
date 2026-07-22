package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.feature.compilertype.CompilerTypeRequestResolver
import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.hmemcpy.metallurgy.status.{MetallurgyStatus, MetallurgyStatusListener}
import com.intellij.openapi.project.Project
import com.intellij.psi.{PsiElement, PsiFile}
import com.intellij.testFramework.PlatformTestUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.assertTrue

import java.util.concurrent.{CompletableFuture, TimeUnit}

/** Verifies the type the user would see (the `CompilerType` slot Feature 0 fills) for the wider set of Scala 3
  * constructs — the same cases [[PcTypeResolutionTest]] proved `pc` resolves. Each case configures a real PsiFile, asks
  * `pc` to resolve the type via the slot, and asserts the presented text.
  */
final class PcPresentationTest extends ScalaLightCodeInsightFixtureTestCase:

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.fromString("3.5.2").get

  override protected def defaultVersionOverride: Option[ScalaVersion] =
    Some(new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2"))

  override def getTestDataPath: String =
    java.nio.file.Path.of("src", "test", "testdata").toAbsolutePath.toString

  // (label, source, needle, required substrings in the presented type)
  private val cases: Seq[(String, (String, String, String))] = Seq(
    "compiletime.ops singleton"    -> (
      """import scala.compiletime.ops.int.*
        |type Two = 2 + 2
        |val r = scala.compiletime.constValue[Two]""".stripMargin,
      "scala.compiletime.constValue[Two]",
      "(4 : Int)"
    ),
    "match type"                   -> (
      """type Elem[X] = X match
        |  case List[t] => t
        |def elemValue(): Elem[List[Int]] = 42
        |val reduced = elemValue()""".stripMargin,
      "elemValue()",
      "Int"
    ),
    "polymorphic function"         -> (
      """val id = [A] => (x: A) => x
        |val result = id(42)""".stripMargin,
      "id(42)",
      "Int"
    ),
    "opaque type"                  -> (
      """object Ports:
        |  opaque type Port = Int
        |  def apply(n: Int): Port = n
        |val p = Ports(8080)""".stripMargin,
      "Ports(8080)",
      "Ports.Port"
    ),
    "union type"                   -> (
      """def unionValue(): Int | String = 1
        |val u = unionValue()""".stripMargin,
      "unionValue()",
      "Int | String"
    ),
    "transparent inline singleton" -> (
      """transparent inline def port: Int = 8080
        |val p: 8080 = port""".stripMargin,
      "p",
      "(8080 : Int)"
    )
  )

  override protected def setUp(): Unit =
    super.setUp()
    MetallurgySettings(getProject).setEnabled(getModule, enabled = true)
    setCompilerBasedHighlighting(enabled = true)

  override protected def tearDown(): Unit =
    try
      MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
      setCompilerBasedHighlighting(enabled = false)
    finally super.tearDown()

  def testPresentedTypes(): Unit =
    val results  = cases.zipWithIndex.map { case ((label, (source, needle, expected)), idx) =>
      val file      = myFixture.configureByText(s"Case$idx.scala", source)
      val _         = PlatformTestUtil.waitForFuture(
        PcSessionManager.get(getProject).prepareCompilerBackend(file.getVirtualFile),
        TimeUnit.SECONDS.toMillis(60)
      )
      val offset    = source.lastIndexOf(needle)
      val element   = typedElementAt(file, offset, needle.length)
      val presented = presentedType(element)
      val ok        = presented.exists(t => normalize(t) == normalize(expected))
      println(f"[present] ${if ok then "OK  " else "FAIL"} $label%-30s -> ${presented.getOrElse("<none>")}")
      (ok, label, presented.map(normalize).getOrElse("<none>"), normalize(expected))
    }
    val failures = results.filterNot(_._1)
    assertTrue(
      s"${failures.size}/${cases.size} presentation cases failed:\n" +
        failures
          .map(f => s"  - ${f._2}:\n      got:      '${f._3}'\n      expected: '${f._4}'")
          .mkString("\n"),
      failures.isEmpty
    )

  private def normalize(renderedType: String): String =
    renderedType.trim.replaceAll("\\s+", " ")

  private def presentedType(element: PsiElement): Option[String] =
    val project    = element.getProject
    val done       = new CompletableFuture[MetallurgyStatus]()
    val connection = project.getMessageBus.connect()
    connection.subscribe(
      MetallurgyStatus.Topic,
      (_: MetallurgyStatus) match
        case MetallurgyStatus.Resolving(_) | MetallurgyStatus.Enabled => ()
        case terminal                                                 => val _ = done.complete(terminal)
    )
    try
      CompilerTypeRequestResolver(project).request(element)
      PlatformTestUtil.waitForFuture(done, TimeUnit.SECONDS.toMillis(60))
    finally connection.disconnect()
    Option(ScalaPluginSemanticBridge.getCompilerType(element)).filter(_.nonEmpty)

  private def typedElementAt(file: PsiFile, offset: Int, needleLength: Int): PsiElement =
    val leaf    = file.findElementAt(offset)
    val parents = Iterator.iterate(leaf: PsiElement)(_.getParent).takeWhile(_ != null).toList
    val needle  = com.intellij.openapi.util.TextRange.from(offset, needleLength)
    parents
      .collect { case e: ScExpression if e.getTextRange.contains(needle) => e }
      .minByOption(_.getTextRange.getLength)
      .getOrElse(throw new AssertionError(s"No typed PSI element at offset $offset (${leaf.getText})"))

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)
