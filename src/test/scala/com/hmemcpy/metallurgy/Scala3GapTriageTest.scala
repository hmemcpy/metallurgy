package com.hmemcpy.metallurgy

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import java.nio.file.Path
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import scala.jdk.CollectionConverters.*

/** Measures how many of epic #34's proposed gaps are still reproducible against the current bundled plugin.
  *
  * For each golden source: configure with Metallurgy OFF (compiler-based highlighting off — the bundled hand-rolled
  * semantic engine, the reliable headless path) and report whether the target line is still `red`. `notRed` means the
  * bundled plugin now handles it natively -> the gap is closed and the subtask is `wontfix: native`.
  *
  * Reporting-only (no assertions): the printed table is the measurement.
  */
final class Scala3GapTriageTest extends ScalaLightCodeInsightFixtureTestCase:

  private val testScalaVersion = ScalaVersion.fromString("3.5.2").get

  override protected def supportedIn(version: ScalaVersion): Boolean  = version == testScalaVersion
  override protected def defaultVersionOverride: Option[ScalaVersion] = Some(testScalaVersion)
  override def getTestDataPath: String                                = Path.of("src", "test", "testdata").toAbsolutePath.toString

  override protected def setUp(): Unit =
    super.setUp()
    setCompilerBasedHighlighting(enabled = false)

  // (label, source, target line 1-based) — the subtask number from #34 is in the label.
  private val cases: Seq[(String, String, Int)] = Seq(
    "#35 given/using"                ->
      (
        """import scala.compiletime.deferred
         |trait ResultOps[T]:
         |  def emptyResult: T
         |trait ProcessCompiled[R]:
         |  given ops: ResultOps[R] = deferred
         |class Box3D
         |def boundingBox(): Unit =
         |  given resultOps: ResultOps[Box3D] with
         |    override def emptyResult: Box3D = new Box3D
         |  object AaBb extends ProcessCompiled[Box3D]
         |""".stripMargin,
        10
      ),
    "#36 overload/eta"               ->
      (
        """trait MyTc[T]
         |case class Foo private (i: Seq[Int])
         |object Foo:
         |  def apply[T <: Seq[Int]: MyTc](i: T): Foo = new Foo(i)
         |given MyTc[List[Int]] = null
         |val x: Foo = Foo(List(1))
         |""".stripMargin,
        6
      ),
    "#38 extension"                  ->
      (
        """extension (s: String) def slug: String = s.trim.toLowerCase
         |val x = " A ".slug
         |""".stripMargin,
        2
      ),
    "#39 enum widening"              ->
      (
        """enum E:
         |  case A, B
         |val xs = List(E.A, E.B)
         |val head: E = xs.head
         |""".stripMargin,
        4
      ),
    "#40 structural/refinement"      ->
      (
        """import scala.reflect.Selectable.reflectiveSelectable
         |val api: { val x: Int } = new:
         |  val x: Int = 42
         |val v: Int = api.x
         |""".stripMargin,
        4
      ),
    "#41 quote/splice macro"         ->
      (
        """import scala.quoted.*
         |inline def power(x: Double, inline n: Int): Double = ${ powerCode('x, 'n) }
         |def powerCode(x: Expr[Double], n: Expr[Int])(using Quotes): Expr[Double] = '{ $x * $n }
         |val p = power(2.0, 3)
         |""".stripMargin,
        2
      ),
    "#43 derives typeclass"          ->
      (
        """trait Show[T]:
         |  extension (x: T) def show: String
         |given Show[Int] = _.toString
         |object Show:
         |  inline def derived[T]: Show[T] = new Show[T]:
         |    extension (x: T) def show = x.toString
         |case class Foo(i: Int) derives Show
         |val s: String = Foo(1).show
         |""".stripMargin,
        8
      ),
    "#44 export clause"              ->
      (
        """object B:
         |  object F:
         |    def x: String = ""
         |  trait F
         |  object D
         |object S:
         |  export B.{F, D}
         |import S.F.x
         |val v: String = x
         |""".stripMargin,
        9
      ),
    "#45 match-type/ops"             ->
      (
        """import scala.compiletime.ops.int.*
         |type Double[N] = N * 2
         |val x: Double[3] = 6
         |""".stripMargin,
        3
      ),
    "#46 capture checking (Caprese)" ->
      (
        """import language.experimental.captureChecking
         |class File:
         |  def read: String = ""
         |def use(f: File^): String = f.read
         |""".stripMargin,
        4
      )
  ).map { case (label, (src, line)) => (label, src, line) }

  // #37 (navigation) is a resolve/find-usages gap, not red-code -> not triaged here.
  // #42 (BETASTy cross-module) is two-module and already proven in BetastyCrossModuleTest.

  // Deeper drill-down: which derivation shapes does the bundled plugin actually get wrong?
  // The simple `Foo(1).show` resolves because DerivesInjector fabricates `given Show[Foo] = ???`, so it
  // does NOT exercise the real derivation gap. These probe Mirror synthesis and type-member consumption.
  private val derivationCases: Seq[(String, String, Int)] = Seq(
    "D1 case-class Mirror (baseline)"   ->
      (
        """import scala.deriving.Mirror
         |case class P(name: String, age: Int)
         |val m = summon[Mirror.Of[P]]
         |""".stripMargin,
        3
      ),
    "D2 sealed-trait Mirror"            ->
      (
        """import scala.deriving.Mirror
         |sealed trait Adt
         |object Adt:
         |  case class A(x: Int) extends Adt
         |  case object B extends Adt
         |val m = summon[Mirror.Of[Adt]]
         |""".stripMargin,
        6
      ),
    "D3 GADT enum Mirror"               ->
      (
        """import scala.deriving.Mirror
         |enum Lst[+T]:
         |  case Cns(t: T, ts: Lst[T])
         |  case Nl
         |val m = summon[Mirror.Of[Lst[Int]]]
         |""".stripMargin,
        5
      ),
    "D4 derives ClassTag"               ->
      (
        """enum Color derives scala.reflect.ClassTag:
         |  case Red, Green, Blue
         |val ct = summon[scala.reflect.ClassTag[Color]]
         |""".stripMargin,
        3
      ),
    "D5 Mirror type-member consumption" ->
      (
        """import scala.deriving.Mirror
         |case class P(name: String, age: Int)
         |val m = summon[Mirror.Of[P]]
         |val labels: m.MirroredElemLabels = ???
         |""".stripMargin,
        4
      ),
    "D6 derives Show + .show (control)" ->
      (
        """trait Show[T]:
         |  extension (x: T) def show: String
         |given Show[Int] = _.toString
         |object Show:
         |  inline def derived[T]: Show[T] = new Show[T]:
         |    extension (x: T) def show = x.toString
         |case class Foo(i: Int) derives Show
         |val s: String = Foo(1).show
         |""".stripMargin,
        8
      )
  ).map { case (label, (src, line)) => (label, src, line) }

  def testDerivationVariants(): Unit =
    val results = derivationCases.map { (label, src, line) =>
      myFixture.configureByText("Derive.scala", src)
      val doc = PsiDocumentManager.getInstance(getProject).getDocument(myFixture.getFile)
      val red = bundledRedOnLine(doc, line)
      println(f"[derive] $label%-38s bundled-red=${red.found}  ${red.descriptions.mkString("; ")}")
      label -> red.found
    }
    val red     = results.count(_._2)
    println(f"[derive] SUMMARY: $red%d of ${results.size}%d still red")

  def testMeasureBundledRedAcrossGaps(): Unit =
    println(f"[triage] Metallurgy enabled for module: ${MetallurgySettings(getProject).isEnabled(getModule)}")
    val results = cases.map { (label, src, line) =>
      myFixture.configureByText("Triage.scala", src)
      val doc    = PsiDocumentManager.getInstance(getProject).getDocument(myFixture.getFile)
      val red    = bundledRedOnLine(doc, line)
      val detail = bundledRedOnLine(doc, line).descriptions.mkString("; ")
      println(f"[triage] $label%-32s bundled-red=${red.found}  $detail")
      label -> red.found
    }
    val red     = results.count(_._2)
    val clean   = results.size - red
    println(f"[triage] SUMMARY: $red%d of ${results.size}%d still red; $clean%d already clean (native)")

  private def bundledRedOnLine(doc: Document, line: Int): RedResult =
    val infos  = myFixture.doHighlighting().asScala.filter(_.getSeverity == HighlightSeverity.ERROR)
    val start  = doc.getLineStartOffset(line - 1)
    val end    = doc.getLineEndOffset(line - 1)
    val onLine = infos.filter(i => i.getStartOffset <= end && i.getEndOffset >= start)
    RedResult(onLine.nonEmpty, onLine.flatMap(i => Option(i.getDescription)).toSeq)

  private case class RedResult(found: Boolean, descriptions: Seq[String])

  private def setCompilerBasedHighlighting(enabled: Boolean): Unit =
    val cls = Class.forName("org.jetbrains.plugins.scala.settings.ScalaProjectSettings")
    val s   = cls.getMethod("getInstance", classOf[Project]).invoke(null, getProject)
    val on  = java.lang.Boolean.valueOf(enabled)
    val _   = cls.getMethod("setCompilerHighlightingScala3", classOf[Boolean]).invoke(s, on)
    val _   = cls.getMethod("setUseCompilerTypes", classOf[Boolean]).invoke(s, on)
