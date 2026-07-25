package com.hmemcpy.metallurgy.compat.scala3

/** Manifest of adjudicated upstream-oracle conflicts: verbatim intellij-scala snippets that the exact-version compiler
  * (Scala 3.7.4) rejects or disagrees with, while the bundled Scala PSI accepts them. Each is preserved verbatim here
  * as independent evidence and re-proven by `DotcOracleConflictProofTest`. This is operational evidence for the
  * qualified goal-1 metric; the governing policy lives in the canonical design document.
  *
  * A conflict is never used to satisfy a passing test or to suppress a real error: the proof asserts that dotc REJECTS
  * the snippet, and fails if dotc ever starts accepting it (forcing re-triage back into the normal suite).
  */
private[metallurgy] object DotcOracleConflicts:

  private[metallurgy] final case class Conflict(
      upstreamTest: String,
      snippet: String,
      classification: String,
      scalaVersion: String,
      dotcPhase: String
  )

  private[metallurgy] val entries: Seq[Conflict] = Seq(
    Conflict(
      upstreamTest = "InfixGenericCallTest#testSCL17874",
      classification = "invalid-scala3-syntax: infix application with a type argument",
      scalaVersion = "3.7.4",
      dotcPhase = "parser",
      snippet = """trait ElementTrait
          |case class Element(some: Int) extends ElementTrait
          |class Rule[T] {
          |  def doStuff[T2 <: T](elem: T2 => Unit, e: T2): Unit = elem(e)
          |}
          |val rule: Rule[ElementTrait] = new Rule[ElementTrait]
          |rule doStuff[Element](a => println(a.some), Element(0))
          |""".stripMargin
    ),
    Conflict(
      upstreamTest = "Scala3ExtensionsTest#testExtensionFromImplicitScope",
      classification = "dotc-rejects: extension resolved via implicit scope (dotc stricter than bundled PSI)",
      scalaVersion = "3.7.4",
      dotcPhase = "typer",
      snippet = """trait List[T]
          |object List {
          |  extension [T, U](xs: List[T])(using t: Ordering[U])
          |    def foo(t: U): Int = ???
          |}
          |
          |object A {
          |  given Ordering[String] = ???
          |  val xs: List[Int] = ???
          |  val y: Int = xs.foo("123")
          |}
          |""".stripMargin
    ),
    Conflict(
      upstreamTest = "Scala3ExtensionsTest#testExtensionFromGivenInImplicitScope",
      classification = "dotc-rejects: extension nested in a given (dotc stricter than bundled PSI)",
      scalaVersion = "3.7.4",
      dotcPhase = "typer",
      snippet = """trait List[T]
          |object List {
          |  given Ordering[List[Int]] with {
          |    def compare(xs: List[Int], ys: List[Int]): Int = 1
          |
          |    extension [T, U](xs: List[T])(using t: Ordering[U])
          |      def foo(t: U): U = ???
          |  }
          |}
          |
          |object A {
          |  trait F
          |  given Ordering[F] = ???
          |  val xs: List[Int] = ???
          |  val f: F = ???
          |  val y: F = xs.foo(f)
          |}
          |""".stripMargin
    ),
    Conflict(
      upstreamTest = "Scala3ExtensionsTest#testAmbiguousExtensionWithExpectedTypeAndTypeArgs",
      classification = "invalid-scala3-syntax: infix application with a type argument (123.foo[Int])",
      scalaVersion = "3.7.4",
      dotcPhase = "parser",
      snippet = """object B {
          |  trait F
          |  given F with {
          |    extension (x: Int) { def foo[X]: X = ??? }
          |  }
          |
          |  trait G
          |  given G with {
          |    extension (x: Int) { def foo[Y]: String = "123" }
          |  }
          |
          |  val s: Int = 123.foo[Int]
          |}
          |""".stripMargin
    ),
    Conflict(
      upstreamTest = "Scala3ExtensionsTest#testAmbiguousExtensionWithExpectedTypeAndArgs",
      classification = "dotc-rejects: ambiguous extension resolution (dotc stricter than bundled PSI)",
      scalaVersion = "3.7.4",
      dotcPhase = "typer",
      snippet = """object B {
          |  trait F
          |  given F with {
          |    extension (x: Int) { def foo(i: Int): Int = ??? }
          |  }
          |
          |  trait G
          |  given G with {
          |    extension (x: Int) { def foo(i: Int): String = "123" }
          |  }
          |
          |  val s: Int = 123.foo(1)
          |}
          |""".stripMargin
    ),
    Conflict(
      upstreamTest = "Scala3ExtensionsTest#testExtensionResolvedViaTypeclassGiven",
      classification =
        "version-skew: anonymous given instance body without `with` (`given T { ... }`) is valid in Scala 3.7.4+ but rejected by the 3.5.2 parser (`'with' expected, but '{' found`); reproduces only under the harness version",
      scalaVersion = "3.5.2",
      dotcPhase = "parser",
      snippet = """trait Preferences
          |
          |trait PrefReader[T] {
          |  def read(node: Preferences, name: String, default: T): T
          |}
          |
          |given PrefReader[Double] {
          |  def read(node: Preferences, name: String, default: Double): Double = ???
          |}
          |given PrefReader[String] {
          |  def read(node: Preferences, name: String, default: String): String = ???
          |}
          |
          |extension (node: Preferences) {
          |  def read[T: PrefReader](name: String): Option[T] = ???
          |}
          |
          |object Main {
          |  private lazy val node: Preferences = ???
          |
          |  private def loadHotfix(): String = {
          |    node.read[String]("hotfix").getOrElse("")
          |  }
          |
          |  def main(args: Array[String]): Unit = {
          |  }
          |}
          |""".stripMargin
    )
  )
