package com.hmemcpy.metallurgy.compat.scala3.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Port of the bundled Scala plugin's `Scala3DerivingTest`, keeping the cases whose snippet is valid and resolves. The
  * upstream uses `checkNoImplicitParameterProblems` (no unresolved-implicit errors); for these resolving cases that is
  * equivalent to `checkTextHasNoErrors`. Cases omitted: the two `testSyntheticGivenIgnores…` (summon deliberately
  * unresolved) and `testEnum`/`testEnumDerivesPassesContextBound`/`testDerivedVal`/`testDerivedObject`/
  * `testCurriedDeriveTooFewTypeParams` — their snippets carry non-implicit errors (empty enum, bare toplevel call,
  * derives-synthetic conformance, kind mismatch) that `checkNoImplicitParameterProblems` ignored but a no-error check
  * surfaces; they need the implicit-search-problem infra, not a no-error assertion. Snippets are kept exactly.
  */
final class Scala3DerivingCompatTest extends Scala3CompatTestCase:

  def testSimple(): Unit = checkTextHasNoErrors(
    s"""
       |trait Eq[A]
       |object Eq { def derived[A]: Eq[A] = ??? }
       |
       |case class Foo(x: Int) derives Eq
       |object A {
       |  ${START}implicitly[Eq[Foo]]$END
       |}
       |""".stripMargin
  )

  def testMultipleTypeParameters(): Unit = checkTextHasNoErrors(
    s"""
       |trait Eq[A]
       |object Eq { def derived[A]: Eq[A] = ??? }
       |
       |case class Foo[A, B, C](a: A, b: B, c: C) derives Eq
       |object A {
       |  given eqInt: Eq[Int] = ???
       |  given eqString: Eq[String] = ???
       |  given eqDouble: Eq[Double] = ???
       |  ${START}implicitly[Eq[Foo[Int, String, Double]]]$END
       |}
       |""".stripMargin
  )

  def testCanEqual(): Unit = checkTextHasNoErrors(
    s"""
       |class Foo[A, B, C[_], D[_, _]](a: A, b: B, c: C[A], d: D[A, B]) derives scala.CanEqual
       |object Foo {
       |  given cq: CanEqual[Double, Int] = ???
       |  ${START}implicitly[CanEqual[Foo[Double, String, List, [X, Y] =>> Int], Foo[Int, String, Option, [X,  Y] =>> String]]]$END
       |}
       |""".stripMargin
  )

  def testDeriveForTypeConstructorTC(): Unit = checkTextHasNoErrors(
    s"""
       |trait Functor[F[_]]
       |object Functor { def derived[F[_]]: Functor[F] = ??? }
       |
       |case class Foo[A](a: A) derives Functor
       |object A {
       |  ${START}implicitly[Functor[Foo]]$END
       |}
       |""".stripMargin
  )

  def testCurriedDeriveTooManyTypeParams(): Unit = checkTextHasNoErrors(
    s"""
       |trait Functor[F[_]]
       |object Functor { def derived[F[_]]: Functor[F] = ??? }
       |
       |case class Foo[A, B, C](a: A) derives Functor
       |object A {
       |  ${START}implicitly[Functor[[X] =>> Foo[Int, String, X]]]$END
       |}
       |""".stripMargin
  )

  def testDerivedWithImplicitParameters(): Unit = checkTextHasNoErrors(
    s"""
       |trait Ord[A]
       |trait Eq[A]
       |object Ord {
       |  def derived[A](implicit ev: Eq[A]): Ord[A] = ???
       |}
       |
       |case class Foo() derives Ord
       |object Foo {
       |  given eqFoo: Eq[Foo] = ???
       |}
       |
       |object A {
       |  ${START}implicitly[Ord[Foo]]$END
       |}
       |""".stripMargin
  )

  def testDeriveForTypeConstructorTC_WithFullyQualifiedTypeclassName(): Unit = checkTextHasNoErrors(
    """
      |package typeClasses.example1
      |
      |package lib1:
      |  trait Functor[F[_]]
      |  object Functor:
      |    def derived[F[_]]: Functor[F] = ???
      |
      |package demo:
      |  case class Box1[A](a: A) derives _root_.typeClasses.example1.lib1.Functor
      |
      |  object Use:
      |    summon[_root_.typeClasses.example1.lib1.Functor[Box1]]
      |""".stripMargin
  )

  def testDeriveForTypeConstructorTC_WithFullyQualifiedTypeclassName_AndNeighbourExplicitGiven(): Unit =
    checkTextHasNoErrors(
      """
      |package typeClasses.example1
      |
      |package lib1:
      |  trait Functor[F[_]]
      |  object Functor:
      |    def derived[F[_]]: Functor[F] = ???
      |
      |package demo:
      |  case class Box1[A](a: A) derives _root_.typeClasses.example1.lib1.Functor
      |
      |  case class Box2[A](a: A)
      |  object Box2:
      |    given derivedFunctor: _root_.typeClasses.example1.lib1.Functor[Box2] = ???
      |
      |  object Use:
      |    summon[_root_.typeClasses.example1.lib1.Functor[Box1]]
      |    summon[_root_.typeClasses.example1.lib1.Functor[Box2]]
      |""".stripMargin
    )
