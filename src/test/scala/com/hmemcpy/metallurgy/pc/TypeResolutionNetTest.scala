package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.build.ScalacFlagsService
import com.hmemcpy.metallurgy.feature.compilertype.TypeRenderer
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.roots.OrderEnumerator
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.assertTrue

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** Wide-net probe of `pc`'s type resolution across the gap categories from `docs/research/15` — the constructs the
  * bundled plugin tends to render as `Any` or widen. Each case retypechecks a snippet, then asks `pc` for the type at a
  * chosen offset via [[TypeRenderer.render]]. A case **passes** when the rendered type contains every required
  * substring and is not bare `Any`; anything else is a target to investigate and fix.
  *
  * Run with `-Dnet.print=true` (or just read stdout) to see the per-case rendered types.
  */
final class TypeResolutionNetTest extends ScalaLightCodeInsightFixtureTestCase:

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.fromString("3.5.2").get

  override protected def defaultVersionOverride: Option[ScalaVersion] =
    Some(new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2"))

  override def getTestDataPath: String =
    Path.of("src", "test", "testdata").toAbsolutePath.toString

  // (label, source, needle whose last occurrence is the expression to type, required substrings)
  private val cases: Seq[(String, (String, String, Set[String]))] = Seq(
    "compiletime.ops int singleton"           ->
      ("import scala.compiletime.ops.int.*\ntype Two = 2 + 2\nval r: Two = 4\n", "4", Set("4")),
    "compiletime.ops string Length"           ->
      ("import scala.compiletime.ops.string.*\ntype L = Length[\"abc\"]\nval l: L = 3\n", "3", Set("3")),
    "match type reduction"                    ->
      (
        "type Elem[X] = X match\n  case List[t] => t\n  case Array[t] => t\nval e: Elem[List[Int]] = 42\n",
        "42",
        Set("Int")
      ),
    "named tuple"                             ->
      ("val person = (name = \"Ada\", age = 1)\n", "person", Set("name", "age")),
    "polymorphic function value"              ->
      ("val id = [A] => (x: A) => x\nval r = id(42)\n", "id(42)", Set("Int")),
    "context function application"            ->
      ("val cf: Int ?=> Int = summon[Int]\nval r: Int = cf(using 42)\n", "cf(using 42)", Set("Int")),
    "derives Mirror (type member)"            ->
      (
        "import scala.deriving.Mirror\ncase class P(name: String, age: Int)\nval m = summon[Mirror.Of[P]]\n",
        "summon",
        Set("Mirror")
      ),
    "structural refinement selectDynamic"     ->
      (
        "import scala.reflect.Selectable.reflectiveSelectable\nval s: { val x: Int } = new:\n  val x: Int = 1\nval v = s.x\n",
        "s.x",
        Set("Int")
      ),
    "transparent inline singleton"            ->
      ("transparent inline def port: Int = 8080\nval p: 8080 = port\n", "port", Set("8080")),
    "extension method"                        ->
      ("extension (s: String) def slug: String = s.trim.toLowerCase\nval v = \" A \".slug\n", ".slug", Set("String")),
    "generic tuple HList head"                ->
      ("val h: Int *: String *: EmptyTuple = (1, \"two\")\nval head = h.head\n", "h.head", Set("Int")),
    "inline match (Peano) reduces to literal" ->
      (
        "trait Nat\ncase object Zero extends Nat\ncase class Succ[N <: Nat](n: N) extends Nat\ntransparent inline def toInt(n: Nat): Int =\n  inline n match\n    case Zero     => 0\n    case Succ(n1) => toInt(n1) + 1\ninline val two = toInt(Succ(Succ(Zero)))\n",
        "toInt(Succ(Succ(Zero)))",
        Set("2")
      )
  )

  def testTypeResolutionNet(): Unit = withRealPcSession("type-net"): session =>
    val results = cases.zipWithIndex.map { case ((label, (source, needle, required)), idx) =>
      val snapshot = PcSnapshot(s"file:///Net$idx.scala", 1L, source)
      val outcome  = session.scheduleRetypecheck(snapshot).get(30, TimeUnit.SECONDS)
      val offset   = source.lastIndexOf(needle)
      val rendered =
        if offset < 0 then Some(s"<needle '$needle' not found>")
        else if outcome != RetypecheckOutcome.Applied then Some(s"<retypecheck $outcome>")
        else TypeRenderer.render(session, snapshot, offset)
      val ok       = rendered.exists(t => t != "Any" && required.forall(t.contains))
      println(f"[type-net] ${if ok then "OK  " else "FAIL"} $label%-38s -> ${rendered.getOrElse("<none>")}")
      (ok, label, rendered.getOrElse("<none>"), required)
    }

    val failures = results.filterNot(_._1)
    assertTrue(
      s"${failures.size}/${cases.size} type-resolution cases failed:\n" +
        failures.map(f => s"  - ${f._2}: got '${f._3}', required ${f._4.mkString("[", ",", "]")}").mkString("\n"),
      failures.isEmpty
    )

  private def withRealPcSession(prefix: String)(test: PcSession => Unit): Unit =
    val temporaryDirectory = Files.createTempDirectory(prefix)
    val fetcher            = new MtagsFetcher(
      PcArtifactCache(temporaryDirectory.resolve("cache")),
      PresentationCompilerResolver.bundled,
      BackgroundRunner.direct
    )
    val settings           = MetallurgySettings(getProject)
    try
      settings.setEnabled(getModule, enabled = true)
      val _ = onPooledThread(fetcher.jarsFor(testScalaVersion.minor).get(120, TimeUnit.SECONDS))
      onPooledThread:
        val session = PcSession.create(
          testScalaVersion.minor,
          moduleClasspath,
          ScalacFlagsService.get(getProject).compilerOptions(getModule),
          fetcher
        )
        try test(session)
        finally session.close()
    finally
      settings.setEnabled(getModule, enabled = false)
      deleteRecursively(temporaryDirectory)

  private def testScalaVersion: ScalaVersion = new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2")

  private def moduleClasspath =
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
    com.intellij.openapi.application.ApplicationManager.getApplication
      .executeOnPooledThread(() => body)
      .get(120, TimeUnit.SECONDS)

  private def deleteRecursively(path: java.nio.file.Path): Unit =
    if Files.exists(path) then
      val stream = Files.walk(path)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      finally stream.close()
