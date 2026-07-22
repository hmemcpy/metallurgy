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
      """import scala.compiletime.ops.int.*
        |type Two = 2 + 2
        |val r: Two = 4""".stripMargin,
      "4",
      Set("4")
    ),
    "compiletime.ops string Length"    -> (
      """import scala.compiletime.ops.string.*
        |type L = Length["abc"]
        |val l: L = 3""".stripMargin,
      "3",
      Set("3")
    ),
    "match type reduction"             -> (
      """type Elem[X] = X match
        |  case List[t] => t
        |  case Array[t] => t
        |val reduced: Elem[List[Int]] = 42""".stripMargin,
      "reduced",
      Set("Int")
    ),
    "named tuple"                      -> ("""val person = (name = "Ada", age = 1)""", "person", Set("name", "age")),
    "polymorphic function application" -> (
      """val id = [A] => (x: A) => x
        |val result = id(42)""".stripMargin,
      "result",
      Set("Int")
    ),
    "context function application"     -> (
      """val cf: Int ?=> Int = summon[Int]
        |val result: Int = cf(using 42)""".stripMargin,
      "result",
      Set("Int")
    ),
    "Mirror summon"                    -> (
      """import scala.deriving.Mirror
        |case class P(name: String, age: Int)
        |val mirror = summon[Mirror.Of[P]]""".stripMargin,
      "mirror",
      Set("Mirror")
    ),
    "structural refinement select"     -> (
      """import scala.reflect.Selectable.reflectiveSelectable
        |val s: { val x: Int } = new:
        |  val x: Int = 1
        |val selected = s.x""".stripMargin,
      "selected",
      Set("Int")
    ),
    "transparent inline singleton"     -> (
      """transparent inline def port: Int = 8080
        |val p: 8080 = port""".stripMargin,
      "p",
      Set("8080")
    ),
    "extension method result"          -> (
      """extension (s: String) def slug: String = s.trim.toLowerCase
        |val trimmed = " A ".slug""".stripMargin,
      "trimmed",
      Set("String")
    ),
    "generic tuple HList head"         -> (
      """val h: Int *: String *: EmptyTuple = (1, "two")
        |val head = h.head""".stripMargin,
      "head",
      Set("Int")
    ),
    "inline match (Peano) result"      -> (
      """trait Nat
        |case object Zero extends Nat
        |case class Succ[N <: Nat](n: N) extends Nat
        |transparent inline def toInt(n: Nat): Int =
        |  inline n match
        |    case Zero     => 0
        |    case Succ(n1) => toInt(n1) + 1
        |inline val two = toInt(Succ(Succ(Zero)))""".stripMargin,
      "two",
      Set("2")
    ),
    "opaque type"                      -> (
      """object Ports:
        |  opaque type Port = Int
        |  def apply(n: Int): Port = n
        |val p: Ports.Port = Ports(8080)""".stripMargin,
      "p",
      Set("Port")
    ),
    "union type"                       -> ("""val u: Int | String = 1""", "u", Set("Int")),
    "intersection type"                -> (
      """trait A
        |trait B
        |type AB = A & B
        |val ab: AB = new A with B {}""".stripMargin,
      "ab",
      Set("A")
    ),
    "extension on generic type"        -> (
      """extension [T](xs: List[T]) def firstOption: Option[T] = xs.headOption
        |val x = List(1).firstOption""".stripMargin,
      "x",
      Set("Option", "Int")
    ),
    "singleton literal type"           -> ("""val answer: 42 = 42""", "answer", Set("42")),
    "type-level boolean negation"      -> (
      """import scala.compiletime.ops.boolean.*
        |type NotFalse = ![false]
        |val x: NotFalse = true""".stripMargin,
      "x",
      Set("true")
    ),
    "quoted Expr"                      -> (
      """import scala.quoted.*
        |def makeExpr(using Quotes): Expr[Int] =
        |  val e: Expr[Int] = '{ 42 }
        |  e""".stripMargin,
      "e",
      Set("Expr", "Int")
    ),
    "circe derives Codec"              -> (
      """import io.circe.{Codec, Encoder}
        |case class Person(name: String, age: Int) derives Codec.AsObject
        |val enc = summon[Encoder[Person]]""".stripMargin,
      "enc",
      Set("Codec", "Person")
    ),
    "given summon"                     -> (
      """given Int = 42
        |val n = summon[Int]""".stripMargin,
      "n",
      Set("Int")
    ),
    "export clause"                    -> (
      """object B:
        |  def x: String = ""
        |object S:
        |  export B.x
        |import S.x
        |val v = x""".stripMargin,
      "v",
      Set("String")
    ),
    "path-dependent type"              -> (
      """trait Box:
        |  type T
        |object IntBox extends Box:
        |  type T = Int
        |val t: IntBox.T = 1""".stripMargin,
      "t",
      Set("Int")
    ),
    "parameterized type alias"         -> (
      """type Pair[T] = (T, T)
        |val p: Pair[Int] = (1, 2)""".stripMargin,
      "p",
      Set("Int")
    ),
    "overloaded method eta-expansion"  -> (
      """object O:
        |  def f(x: Int): Int = x
        |  def f(s: String): String = s
        |val g: Int => Int = O.f
        |val r = g(42)""".stripMargin,
      "r",
      Set("Int")
    ),
    "nested generic application"       -> (
      """val r = List(Option(1)).collect { case Some(x) => x }""",
      "r",
      Set("Int")
    ),
    "implicit Conversion"              -> (
      """given Conversion[String, Int] = _.toInt
        |val r: Int = "42"
        |""".stripMargin,
      "r",
      Set("Int")
    ),
    "higher-kinded type parameter"     -> (
      """class Box[F[_]](val f: F[Int])
        |val box = new Box(List(1, 2))""".stripMargin,
      "box",
      Set("Box", "List")
    ),
    "quoted Type summon"               -> (
      """import scala.quoted.*
        |def makeType(using Quotes): Type[Int] =
        |  val quotedType: Type[Int] = Type.of[Int]
        |  quotedType""".stripMargin,
      "quotedType",
      Set("Type", "Int")
    ),
    "intersection with refinement"     -> (
      """trait Reader:
        |  def read: String
        |val reader: Reader { def read: String } = new Reader:
        |  def read: String = "x"""".stripMargin,
      "reader",
      Set("Reader")
    ),
    "Aux pattern"                      -> (
      """trait Foo:
        |  type Out
        |  def out: Out
        |object Foo:
        |  type Aux[O] = Foo { type Out = O }
        |  def apply[O](v: O): Aux[O] = new Foo:
        |    type Out = O
        |    def out: Out = v
        |val fooInstance = Foo(42)
        |val resolved: Int = fooInstance.out""".stripMargin,
      "resolved",
      Set("Int")
    ),
    "refined type"                     -> (
      """import eu.timepit.refined.api.Refined
        |import eu.timepit.refined.numeric.Positive
        |type PosInt = Int Refined Positive
        |val value: PosInt = 1""".stripMargin,
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
