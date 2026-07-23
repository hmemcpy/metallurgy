package com.hmemcpy.metallurgy.compat.shared.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Verbatim port of the bundled Scala plugin's `HKExpectedTypeConformanceTest` (default `supportedIn` = all). */
final class HKExpectedTypeConformanceCompatTest extends Scala3CompatTestCase:

  def testHigherKindedExpectedTypeInference(): Unit = checkTextHasNoErrors(
    """
      |def f[R[_], T](fun: String => R[T]): String => R[T] = fun
      |val result = f(str => Option(str))
    """.stripMargin
  )

  def testBuildFromCompositionResultType(): Unit = checkTextHasNoErrors(
    """
      |object Test {
      |  trait BuildFrom[-From, -A, +C]
      |  def useBuildFrom[B, That](b: B)(bf: BuildFrom[Seq[Any], B, That]): That = ???
      |  def buildFromIterableOps[CC[X] <: Iterable[X], A0, A]: BuildFrom[CC[A0], A, CC[A]] = ???
      |  def acceptSeq[A](as: Seq[A]): Seq[A] = ???
      |
      |  acceptSeq {
      |    useBuildFrom("")(buildFromIterableOps)
      |  }
      |    .head
      |    .chars()
      |}
      |""".stripMargin
  )
