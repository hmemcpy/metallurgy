package com.hmemcpy.metallurgy.compat.shared.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Verbatim port of the bundled Scala plugin's `PartialUnificationHighlightingTest`. The upstream `setUp` adds the
  * Scala 2 flag `-Ypartial-unification`; Scala 3 has partial unification by default, so no flag is needed here.
  */
final class PartialUnificationHighlightingCompatTest extends Scala3CompatTestCase:

  def testPartialUnificationNestedProdType(): Unit = checkTextHasNoErrors(
    """
      |object T {
      |  class A[F[_]]
      |  class B[F[_]]
      |  class C[F[_]]
      |
      |  final case class Prod[F[_[_]], G[_[_]], A[_]](fa: F[A], ga: G[A])
      |
      |  val x = Prod(new A[List], Prod(new B[List], new C[List]))
      |}
    """.stripMargin
  )

  def testPartialUnificationHigherKindedApplication(): Unit = checkTextHasNoErrors(
    """
      |object Demo1b {
      |  class Foo[T, F[_]]
      |
      |  def meh[M[_[_]], F[_]](x: M[F]): M[F] = x
      |
      |  meh(new Foo[Int, List])
      |}
      |
      |object Demo1c {
      |  trait TC[T]
      |  class Foo[F[_], G[_]]
      |
      |  def meh[M[_[_]]](x: M[TC]): M[TC] = x
      |
      |  meh(new Foo[TC, TC])
      |}
      |
      |object Demo1d {
      |  trait TC[F[_]]
      |  trait TC2[F[_]]
      |  class Foo[F[_[_]], G[_[_]]]
      |  new Foo[TC, TC2]
      |
      |  def meh[M[_[_[_]]]](x: M[TC2]): M[TC2] = x
      |
      |  meh(new Foo[TC, TC2])
      |}
    """.stripMargin
  )

  def testPartialUnificationEitherNested(): Unit = checkTextHasNoErrors(
    """
      |object Test {
      |    def foo[F[_], A](a: F[F[A]]): Any = ???
      |    def either1: Either[String, Either[String, Int]] = ???
      |    foo(either1)
      |}
    """.stripMargin
  )
