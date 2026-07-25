package com.hmemcpy.metallurgy.compat.shared.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Verbatim port of the bundled Scala plugin's `BoundsConformanceTest` (default `supportedIn` = all versions, so it
  * runs under Scala 2 and Scala 3). Snippets are kept exactly; only the asserting base class differs.
  */
final class BoundsConformanceCompatTest extends Scala3CompatTestCase:

  def testLowerAndUpperBoundsOnMethodTypeParams(): Unit = checkTextHasNoErrors(
    """
      |sealed trait Feeling
      |sealed trait Hungry extends Feeling
      |sealed trait Thirsty extends Feeling
      |
      |class Person[F <: Feeling] {
      |  def eat[T >: F <: Hungry] = println("Chomp!")
      |  def drink[T >: F <: Thirsty] = println("Glug!")
      |}
    """.stripMargin
  )

  def testCompoundLowerBoundChain(): Unit = checkTextHasNoErrors(
    """
      |  sealed trait TOption
      |
      |  sealed trait TNumericLowerTypeBound
      |
      |  def sum[T2 >: TOption, T1 >: TNumericLowerTypeBound <: T2, A1, A2]
      |  (b: Map[A1, T1])
      |  (implicit f: Map[A2, T2]) = {}
    """.stripMargin
  )

  def testPathDependentTypeBoundConformance(): Unit = checkTextHasNoErrors(
    """
      |trait A {
      |  type B[+T]
      |  type C[+T] <: B[T]
      |  def c: C[Int]
      |}
      |
      |object Q {
      |  val a: A = ???
      |  val b: a.B[Int] = a.c
      |}
    """.stripMargin
  )
