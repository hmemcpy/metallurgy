package com.hmemcpy.metallurgy

import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.compiler.CompilerMessageCategory
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.compiler.highlighting.ScalaCompilerHighlightingTestBase

import scala.jdk.CollectionConverters.*

/** Triage of epic #34's gap themes under the real baseline: **CBH on (compile server), Metallurgy off**.
  *
  * Each fixture is valid Scala 3 the bundled plugin has historically marked red. `compiler.make()` drives a synchronous
  * compile-server compile (the compile half of CBH) and returns its messages — the source of truth for "does the real
  * compiler error on this code." Valid code must produce no ERROR message; the `#control` case (a genuine type error)
  * must — it proves the harness detects errors, so a `native-red=false` on valid code is trustworthy.
  *
  * Reporting-only: the printed table is the measurement.
  */
final class Scala3GapTriageTest extends ScalaCompilerHighlightingTestBase:

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.fromString("3.5.2").get

  // (label, source) — sources are valid Scala 3 the bundled plugin has marked red, except the control.
  private val cases: Seq[(String, String)] = Seq(
    "#control real error (must be red)" ->
      """object Broken:
        |  val x: Int = "not an int"
        |""".stripMargin,
    "#35 given/using"                   ->
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
    "#36 overload/eta"                  ->
      """trait MyTc[T]
        |case class Foo private (i: Seq[Int])
        |object Foo:
        |  def apply[T <: Seq[Int]: MyTc](i: T): Foo = new Foo(i)
        |given MyTc[List[Int]] = null
        |val x: Foo = Foo(List(1))
        |""".stripMargin,
    "#38 extension"                     ->
      """extension (s: String) def slug: String = s.trim.toLowerCase
        |val x = " A ".slug
        |""".stripMargin,
    "#39 enum widening"                 ->
      """enum E:
        |  case A, B
        |val xs = List(E.A, E.B)
        |val head: E = xs.head
        |""".stripMargin,
    "#40 structural/refinement"         ->
      """import scala.reflect.Selectable.reflectiveSelectable
        |val api: { val x: Int } = new:
        |  val x: Int = 42
        |val v: Int = api.x
        |""".stripMargin,
    "#41 quote/splice macro"            ->
      """import scala.quoted.*
        |inline def power(x: Double, inline n: Int): Double = ${ powerCode('x, 'n) }
        |def powerCode(x: Expr[Double], n: Expr[Int])(using Quotes): Expr[Double] = '{ $x * $n }
        |val p = power(2.0, 3)
        |""".stripMargin,
    "#43 derives (Mirror type member)"  ->
      """import scala.deriving.Mirror
        |case class Person(name: String, age: Int)
        |val mirror = summon[Mirror.Of[Person]]
        |val labels: mirror.MirroredElemLabels = ???
        |""".stripMargin,
    "#44 export clause"                 ->
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
    "#45 match-type/ops"                ->
      """import scala.compiletime.ops.int.*
        |type Double[N] = N * 2
        |val x: Double[3] = 6
        |""".stripMargin,
    "#46 capture checking (Caprese)"    ->
      """import language.experimental.captureChecking
        |class File:
        |  def read: String = ""
        |def use(f: File^): String = f.read
        |""".stripMargin
  )

  // #37 (navigation) is a resolve/find-usages gap, not red-code; #42 (BETASTy) is two-module (BetastyCrossModuleTest).
  def testNativeCbhTriage(): Unit = runTriage()

  private def runTriage(): Unit =
    println(s"[cbh-triage] Metallurgy enabled for module: ${MetallurgySettings(getProject).isEnabled(getModule)}")
    cases.foreach((label, source) => measureOne(label, source))

  private def measureOne(label: String, source: String): Unit =
    val pkg    = "metallurgy_triage_" + label.replaceAll("[^A-Za-z0-9]", "").toLowerCase
    val file   = addFileToProjectSources(fileNameFor(label), s"package $pkg\n" + source)
    val errors = compiler
      .make()
      .asScala
      .filter(_.getCategory == CompilerMessageCategory.ERROR)
      .filter(_.getVirtualFile == file)
    val detail = errors.map(m => Option(m.getMessage).map(_.trim).getOrElse("")).mkString("; ")
    println(f"[cbh-triage] $label%-34s native-red=${errors.nonEmpty}  $detail")

  private def fileNameFor(label: String): String =
    label.replaceAll("[^A-Za-z0-9]", "") + ".scala"
