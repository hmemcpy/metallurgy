package com.hmemcpy.metallurgy.compat.scala3.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Port of the bundled Scala plugin's `Scala3ImplicitParametersTest`, keeping the resolving cases. The upstream uses
  * `checkNoImplicitParameterProblems` (no unresolved implicits), mapped here to `checkTextHasNoErrors`. Omitted:
  * `testTopLevelGivenDefinition` (multi-file), the pattern-bound cases (undefined refs), `testGivenShadowingInMain`,
  * the two `_1` type-equality cases, and the nested-objects derives cases — they carry non-implicit errors or
  * divergences the implicit-only check ignored. Snippets are kept exactly.
  */
final class Scala3ImplicitParametersCompatTest extends Scala3CompatTestCase:

  def testSimpleGiven(): Unit = checkTextHasNoErrors(
    s"""
       |object Test {
       |  given int: Int = 123
       |  ${START}implicitly[Int]$END
       |}
       |""".stripMargin
  )

  def testAnonymousGiven(): Unit = checkTextHasNoErrors(
    s"""
       |object Test {
       |  given String = "s"
       |  def foo(using String): Unit = ???
       |  ${START}foo$END
       |}
       |""".stripMargin
  )

  def testSpecExample(): Unit = checkTextHasNoErrors(
    s"""
       |
       |object A {
       |  trait Ord[T]:
       |    def compare(x: T, y: T): Int
       |
       |  given intOrd: Ord[Int] with
       |    def compare(x: Int, y: Int) = ???
       |
       |  given listOrd[T](using ord: Ord[T]): Ord[List[T]] with
       |    def compare(xs: List[T], ys: List[T]): Int = ???
       |
       |  ${START}implicitly[Ord[List[Int]]]$END
       |}
       |""".stripMargin
  )

  def testAliasWithParameters(): Unit = checkTextHasNoErrors(
    s"""
       |object A {
       |  trait Config
       |  trait Factory
       |  given Config = ???
       |  given (using config: Config): Factory = ???
       |  ${START}implicitly[Factory]$END
       |}
       |""".stripMargin
  )

  def testLocalGivenInstanceResolves(): Unit = checkTextHasNoErrors(
    s"""
       |trait Result[T]:
       |  def res: T
       |
       |def run() =
       |  given Result[String] with {def res = "result"}
       |  ${START}implicitly[Result[String]]$END
       |""".stripMargin
  )

  def testEffectiveParameters_TypeParameterWithContextBoundAndContextParameter(): Unit = checkTextHasNoErrors(
    """def example(): Unit = {
      |  val res: String = fooContextBoundAndUsingParam[MyType]
      |}
      |
      |def fooContextBoundAndUsingParam[F : Monad](using ctx: Context): String = ???
      |
      |trait Context
      |trait Monad[F]
      |trait MyType
      |
      |given Context = ???
      |given Monad[MyType] = ???
      |""".stripMargin
  )

  def testEffectiveParameters_TypeParameterWithContextBoundAndImplicitParameter(): Unit = checkTextHasNoErrors(
    """def example(): Unit = {
      |  val res: String = fooContextBoundAndImplicitParam[MyType]
      |}
      |
      |def fooContextBoundAndImplicitParam[F : Monad](implicit ctx: Context): String = ???
      |
      |trait Context
      |trait Monad[F]
      |trait MyType
      |
      |given Context = ???
      |given Monad[MyType] = ???
      |""".stripMargin
  )

  def testWildcardImportedGivenDefinition(): Unit = checkTextHasNoErrors(
    s"""
       |trait SomeTrait:
       |  def foo: Int
       |
       |object Givens:
       |  given someTrait: SomeTrait with
       |    val foo = 2
       |
       |object Test:
       |  def foo(using someTrait: SomeTrait): Unit = println(someTrait.foo)
       |
       |  import Givens.given
       |  ${START}foo$END
       |""".stripMargin
  )

  def testNestingSimple(): Unit = checkTextHasNoErrors(
    s"""
       |object A {
       |  def foo(using ev: Int) = {
       |    def bar(using ev2: Int) = {
       |      ${START}summon[Int]$END
       |    }
       |  }
       |}
       |""".stripMargin
  )

  def testDerives_InLocalClass(): Unit = checkTextHasNoErrors(
    s"""class Test {
       |  trait CaseClassName[A]:
       |    def get: String
       |
       |  object CaseClassName:
       |    inline final def derived[A](using inline A: scala.deriving.Mirror.Of[A]): CaseClassName[A] = new CaseClassName[A]:
       |      def get = A.toString
       |
       |  case class CoolClass(i: Int) derives CaseClassName
       |
       |  def print(): Unit =
       |    println(summon[CaseClassName[CoolClass]].get)
       |}
       |""".stripMargin
  )
