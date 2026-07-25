package com.hmemcpy.metallurgy.compat.shared.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Port of the bundled Scala plugin's `CurriedTypeParamsApplyCallTest`. That class uses the default `supportedIn` (all
  * versions), so it runs under Scala 2 and Scala 3; under Scala 3 it exercises the pc backend. The `doTest` cases
  * assert the inferred type, which is the semantic info dotc must supply correctly (parity); the no-error case checks
  * the bundled annotator's highlights.
  */
final class CurriedTypeParamsApplyCallCompatTest extends Scala3CompatTestCase:

  def testCurriedTypeParamApplySingleResult(): Unit = assertExprType(
    s"""
       |object A {
       |  def m[A] = new C[A]
       |  class C[A] {
       |    def apply[B](f: A => (B)) = f
       |  }
       |
       |  ${START}m[Int](_.toString -> false)$END
       |}
       |//Int => (String, Boolean)
       |""".stripMargin
  )

  def testCurriedTypeParamApplyTupleResult(): Unit = assertExprType(
    s"""
       |object A {
       |  def m[A] = new C[A]
       |  class C[A] {
       |    def apply[B, C](f: A => (B, C)) = f
       |  }
       |  ${START}m[Int](_.toString -> false)$END
       |}
       |//Int => (String, Boolean)
       |""".stripMargin
  )

  def testOverloadedApplyWithImplicitGenSpawn(): Unit = checkTextHasNoErrors(
    s"""
       |object Main {
       |    trait IO[A]
       |    trait GenSpawn[F[_], E]
       |    trait Async[F[_]] extends GenSpawn[F, Throwable]
       |    def apply[F[_], E](implicit F: GenSpawn[F, E]): F.type = F
       |    def apply[F[_]](implicit F: GenSpawn[F, _], d: DummyImplicit): F.type = F
       |    implicit val x: Async[IO] = ???
       |
       |    val y = apply[IO]
       |    val _: Async[IO] = y
       |}
       |""".stripMargin
  )
