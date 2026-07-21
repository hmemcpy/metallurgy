package com.hmemcpy.metallurgy

import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.compiler.highlighting.ScalaCompilerHighlightingTestBase
import org.jetbrains.plugins.scala.util.CompilerTestUtil.runWithErrorsFromCompiler

/** Triage of epic #34's gap themes under the real baseline: **CBH on (compile server), Metallurgy off**.
  *
  * Measures the actual error **`HighlightInfo`s** on each file (what the user sees), NOT compiler messages — valid code
  * compiling says nothing about whether the bundled plugin's highlighting layer marks it red. CBH highlights apply
  * asynchronously (editor-focus -> compile server -> `ExternalHighlightersService`), so we wait for the error set to
  * settle.
  *
  * TODO: replace `waitForSettledErrors`'s `Thread.sleep` poll with a latch/semaphore driven by the
  * `ExternalHighlightersService` completion signal.
  *
  * Reporting-only: the printed table is the measurement. `#control` (a genuine type error) must be red — it proves the
  * harness detects error highlights, so a `native-red=false` on valid code is trustworthy.
  */
final class Scala3GapTriageTest extends ScalaCompilerHighlightingTestBase:

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.fromString("3.5.2").get

  // (label, source) — valid Scala 3 the bundled plugin has historically marked red, except the control.
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
    runWithErrorsFromCompiler(getProject):
      cases.foreach((label, source) => measureOne(label, source))

  private def measureOne(label: String, source: String): Unit =
    val pkg    = "metallurgy_triage_" + label.replaceAll("[^A-Za-z0-9]", "").toLowerCase
    val file   = addFileToProjectSources(fileNameFor(label), s"package $pkg\n" + source)
    waitUntilFileIsHighlighted(file)
    val errors = waitForSettledErrors(file)
    val detail = errors.map(e => Option(e.getDescription).getOrElse("")).mkString("; ")
    println(f"[cbh-triage] $label%-34s native-red=${errors.nonEmpty}  $detail")

  /** Temporary: poll the error-highlight set until it stabilises (1 s of no change, after >= 1.5 s elapsed), capped at
    * 30 s. To be replaced by a latch on the `ExternalHighlightersService` completion signal.
    */
  private def waitForSettledErrors(file: com.intellij.openapi.vfs.VirtualFile): Seq[HighlightInfo] =
    val started     = System.currentTimeMillis()
    val deadline    = started + 30_000L
    var previous    = Option.empty[Set[String]]
    var stableSince = started
    var current     = Seq.empty[HighlightInfo]
    while System.currentTimeMillis() < deadline do
      Thread.sleep(500)
      current = fetchHighlightInfos(file).filter(_.getSeverity == HighlightSeverity.ERROR)
      val now = System.currentTimeMillis()
      val sig = current.map(e => s"${e.getStartOffset}-${e.getEndOffset}").toSet
      previous match
        case Some(`sig`) if now - stableSince > 1000L && now - started > 1500L => return current
        case _                                                                 => previous = Some(sig); stableSince = now
    current

  private def fileNameFor(label: String): String =
    label.replaceAll("[^A-Za-z0-9]", "") + ".scala"
