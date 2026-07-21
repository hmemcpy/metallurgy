package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.feature.compilertype.CompilerTypeRequestResolver
import com.hmemcpy.metallurgy.module.BundledPluginBridge
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.hmemcpy.metallurgy.status.{MetallurgyStatus, MetallurgyStatusListener}
import com.intellij.openapi.project.Project
import com.intellij.psi.{PsiElement, PsiFile}
import com.intellij.testFramework.PlatformTestUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
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
  private val cases: Seq[(String, (String, String, Set[String]))] = Seq(
    "compiletime.ops singleton"    -> (
      "import scala.compiletime.ops.int.*\ntype Two = 2 + 2\nval r: Two = 4\n",
      "r",
      Set("4")
    ),
    "match type" -> ("type Elem[X] = X match\n  case List[t] => t\nval reduced: Elem[List[Int]] = 42\n", "reduced", Set("Int")),
    "polymorphic function"         -> ("val id = [A] => (x: A) => x\nval result = id(42)\n", "result", Set("Int")),
    "opaque type"                  -> (
      "object Ports:\n  opaque type Port = Int\n  def apply(n: Int): Port = n\nval p: Ports.Port = Ports(8080)\n",
      "p",
      Set("Port")
    ),
    "union type"                   -> ("val u: Int | String = 1\n", "u", Set("Int")),
    "transparent inline singleton" -> (
      "transparent inline def port: Int = 8080\nval p: 8080 = port\n",
      "p",
      Set("8080")
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
    val results  = cases.zipWithIndex.map { case ((label, (source, needle, required)), idx) =>
      val file      = myFixture.configureByText(s"Case$idx.scala", source)
      PcSessionManager.get(getProject).prepareFile(file.getVirtualFile).get(60, TimeUnit.SECONDS)
      val offset    = source.lastIndexOf(needle)
      val element   = typedElementAt(file, offset)
      val presented = presentedType(element)
      val ok        = presented.exists(t => t != "Any" && required.forall(t.contains))
      println(f"[present] ${if ok then "OK  " else "FAIL"} $label%-30s -> ${presented.getOrElse("<none>")}")
      (ok, label, presented.getOrElse("<none>"), required)
    }
    val failures = results.filterNot(_._1)
    assertTrue(
      s"${failures.size}/${cases.size} presentation cases failed:\n" +
        failures.map(f => s"  - ${f._2}: got '${f._3}', required ${f._4.mkString("[", ",", "]")}").mkString("\n"),
      failures.isEmpty
    )

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
    Option(BundledPluginBridge.getCompilerType(element)).filter(_.nonEmpty)

  private def typedElementAt(file: PsiFile, offset: Int): PsiElement =
    val leaf    = file.findElementAt(offset)
    val parents = Iterator.iterate(leaf: PsiElement)(_.getParent).takeWhile(_ != null).toList
    parents
      .collectFirst { case e: ScTypeElement => e }
      .orElse(parents.collectFirst { case e: ScExpression => e })
      .getOrElse(leaf)

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)
