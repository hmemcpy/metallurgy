package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.build.ScalacFlagsService
import com.hmemcpy.metallurgy.feature.compilertype.TypeRenderer
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.roots.OrderEnumerator
import org.jetbrains.plugins.scala.DependencyManagerBase.RichStr
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.base.libraryLoaders.{IvyManagedLoader, LibraryLoader}
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.assertTrue

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** Verifies `pc` resolves the expected type for Scala 3 constructs the bundled plugin tends to widen or render as `Any`
  * — singleton/literal types, match types, named tuples, polymorphic and context functions, type derivation, structural
  * types, opaque/union/intersection types, quoted expressions. Each case retypechecks a snippet and reads the type of
  * the result val via [[TypeRenderer.render]].
  */
final class PcTypeResolutionTest extends ScalaLightCodeInsightFixtureTestCase:

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.fromString("3.5.2").get

  override protected def defaultVersionOverride: Option[ScalaVersion] =
    Some(new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2"))

  override def getTestDataPath: String =
    Path.of("src", "test", "testdata").toAbsolutePath.toString

  override protected def additionalLibraries: Seq[LibraryLoader] =
    Seq(
      IvyManagedLoader(("io.circe"   %% "circe-generic" % "0.14.10").transitive()),
      IvyManagedLoader(("eu.timepit" %% "refined"       % "0.11.3").transitive())
    )

  // (label, source, needle = the result val whose type we read, required substrings)
  private val cases: Seq[(String, (String, String, Set[String]))] = Seq(
    "compiletime.ops int singleton"    -> (
      "import scala.compiletime.ops.int.*\ntype Two = 2 + 2\nval r: Two = 4\n",
      "4",
      Set("4")
    ),
    "compiletime.ops string Length"    -> (
      "import scala.compiletime.ops.string.*\ntype L = Length[\"abc\"]\nval l: L = 3\n",
      "3",
      Set("3")
    ),
    "match type reduction"             ->
      (
        "type Elem[X] = X match\n  case List[t] => t\n  case Array[t] => t\nval reduced: Elem[List[Int]] = 42\n",
        "reduced",
        Set("Int")
      ),
    "named tuple"                      -> ("val person = (name = \"Ada\", age = 1)\n", "person", Set("name", "age")),
    "polymorphic function application" -> ("val id = [A] => (x: A) => x\nval result = id(42)\n", "result", Set("Int")),
    "context function application"     -> (
      "val cf: Int ?=> Int = summon[Int]\nval result: Int = cf(using 42)\n",
      "result",
      Set("Int")
    ),
    "Mirror summon"                    ->
      (
        "import scala.deriving.Mirror\ncase class P(name: String, age: Int)\nval mirror = summon[Mirror.Of[P]]\n",
        "mirror",
        Set("Mirror")
      ),
    "structural refinement select"     ->
      (
        "import scala.reflect.Selectable.reflectiveSelectable\nval s: { val x: Int } = new:\n  val x: Int = 1\nval selected = s.x\n",
        "selected",
        Set("Int")
      ),
    "transparent inline singleton"     -> (
      "transparent inline def port: Int = 8080\nval p: 8080 = port\n",
      "p",
      Set("8080")
    ),
    "extension method result"          ->
      (
        "extension (s: String) def slug: String = s.trim.toLowerCase\nval trimmed = \" A \".slug\n",
        "trimmed",
        Set("String")
      ),
    "generic tuple HList head"         -> (
      "val h: Int *: String *: EmptyTuple = (1, \"two\")\nval head = h.head\n",
      "head",
      Set("Int")
    ),
    "inline match (Peano) result"      ->
      (
        "trait Nat\ncase object Zero extends Nat\ncase class Succ[N <: Nat](n: N) extends Nat\n" +
          "transparent inline def toInt(n: Nat): Int =\n  inline n match\n    case Zero     => 0\n    case Succ(n1) => toInt(n1) + 1\n" +
          "inline val two = toInt(Succ(Succ(Zero)))\n",
        "two",
        Set("2")
      ),
    "opaque type"                      -> (
      "object Ports:\n  opaque type Port = Int\n  def apply(n: Int): Port = n\nval p: Ports.Port = Ports(8080)\n",
      "p",
      Set("Port")
    ),
    "union type"                       -> ("val u: Int | String = 1\n", "u", Set("Int")),
    "intersection type"                -> ("trait A\ntrait B\ntype AB = A & B\nval ab: AB = new A with B {}\n", "ab", Set("A")),
    "extension on generic type"        ->
      (
        "extension [T](xs: List[T]) def firstOption: Option[T] = xs.headOption\nval x = List(1).firstOption\n",
        "x",
        Set("Option", "Int")
      ),
    "singleton literal type"           -> ("val answer: 42 = 42\n", "answer", Set("42")),
    "type-level boolean negation"      ->
      ("import scala.compiletime.ops.boolean.*\ntype NotFalse = ![false]\nval x: NotFalse = true\n", "x", Set("true")),
    "quoted Expr"                      ->
      (
        "import scala.quoted.*\ndef makeExpr(using Quotes): Expr[Int] =\n  val e: Expr[Int] = '{ 42 }\n  e\n",
        "e",
        Set("Expr", "Int")
      ),
    "circe derives Codec"              ->
      (
        "import io.circe.{Codec, Encoder}\ncase class Person(name: String, age: Int) derives Codec.AsObject\nval enc = summon[Encoder[Person]]\n",
        "enc",
        Set("Codec", "Person")
      ),
    "given summon"                     -> ("given Int = 42\nval n = summon[Int]\n", "n", Set("Int")),
    "export clause"                    -> (
      "object B:\n  def x: String = \"\"\nobject S:\n  export B.x\nimport S.x\nval v = x\n",
      "v",
      Set("String")
    ),
    "path-dependent type"              -> (
      "trait Box:\n  type T\nobject IntBox extends Box:\n  type T = Int\nval t: IntBox.T = 1\n",
      "t",
      Set("Int")
    ),
    "parameterized type alias"         -> ("type Pair[T] = (T, T)\nval p: Pair[Int] = (1, 2)\n", "p", Set("Int")),
    "overloaded method eta-expansion"  -> (
      "object O:\n  def f(x: Int): Int = x\n  def f(s: String): String = s\nval g: Int => Int = O.f\nval r = g(42)\n",
      "r",
      Set("Int")
    ),
    "nested generic application"       -> (
      "val r = List(Option(1)).collect { case Some(x) => x }\n",
      "r",
      Set("Int")
    ),
    "implicit Conversion"              -> ("given Conversion[String, Int] = _.toInt\nval r: Int = \"42\"\n", "r", Set("Int")),
    "higher-kinded type parameter"     -> (
      "class Box[F[_]](val f: F[Int])\nval box = new Box(List(1, 2))\n",
      "box",
      Set("Box", "List")
    ),
    "quoted Type summon"               -> (
      "import scala.quoted.*\ndef makeType(using Quotes): Type[Int] =\n  val quotedType: Type[Int] = Type.of[Int]\n  quotedType\n",
      "quotedType",
      Set("Type", "Int")
    ),
    "intersection with refinement"     -> (
      "trait Reader:\n  def read: String\nval reader: Reader { def read: String } = new Reader:\n  def read: String = \"x\"\n",
      "reader",
      Set("Reader")
    ),
    "Aux pattern"                      -> (
      "trait Foo:\n  type Out\n  def out: Out\nobject Foo:\n  type Aux[O] = Foo { type Out = O }\n  def apply[O](v: O): Aux[O] = new Foo:\n    type Out = O\n    def out: Out = v\nval fooInstance = Foo(42)\nval resolved: Int = fooInstance.out\n",
      "resolved",
      Set("Int")
    ),
    "refined type"                     -> (
      "import eu.timepit.refined.api.Refined\nimport eu.timepit.refined.numeric.Positive\ntype PosInt = Int Refined Positive\nval value: PosInt = 1\n",
      "value",
      Set("Refined", "Positive")
    )
  )

  def testPcTypeResolution(): Unit = withSession: session =>
    val results = cases.zipWithIndex.map { case ((label, (source, needle, required)), idx) =>
      val snapshot = PcSnapshot(s"file:///Case$idx.scala", 1L, source)
      val outcome  = session.scheduleRetypecheck(snapshot).get(30, TimeUnit.SECONDS)
      val offset   = source.lastIndexOf(needle)
      val rendered =
        if offset < 0 then Some(s"<needle '$needle' not found>")
        else if outcome != RetypecheckOutcome.Applied then Some(s"<retypecheck $outcome>")
        else TypeRenderer.render(session, snapshot, offset)
      val ok       = rendered.exists(t => t != "Any" && required.forall(t.contains))
      println(f"[pc-type] ${if ok then "OK  " else "FAIL"} $label%-36s -> ${rendered.getOrElse("<none>")}")
      (ok, label, rendered.getOrElse("<none>"), required)
    }

    val failures = results.filterNot(_._1)
    assertTrue(
      s"${failures.size}/${cases.size} type-resolution cases failed:\n" +
        failures.map(f => s"  - ${f._2}: got '${f._3}', required ${f._4.mkString("[", ",", "]")}").mkString("\n"),
      failures.isEmpty
    )

  private def withSession(test: PcSession => Unit): Unit =
    val temporaryDirectory = Files.createTempDirectory("pc-type-resolution")
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
        val options =
          ScalacFlagsService.get(getProject).compilerOptions(getModule) :+ "-language:experimental.namedTuples"
        val session = PcSession.create(testScalaVersion.minor, moduleClasspath, options, fetcher)
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

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      val stream = Files.walk(path)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      finally stream.close()
