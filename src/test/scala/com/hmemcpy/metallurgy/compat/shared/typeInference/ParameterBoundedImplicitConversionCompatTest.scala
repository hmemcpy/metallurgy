package com.hmemcpy.metallurgy.compat.shared.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Verbatim port of the bundled Scala plugin's `ParameterBoundedImplicitConversionTest` (default `supportedIn` = all).
  */
final class ParameterBoundedImplicitConversionCompatTest extends Scala3CompatTestCase:

  def testParameterBoundedImplicitConversionFromNum(): Unit = checkTextHasNoErrors(
    """import scala.language.implicitConversions
      |import scala.language.higherKinds
      |
      |object Wrapper {
      |  trait Foo[T]
      |
      |  trait FooBuild[M[_], Face] {
      |    implicit def fromNum[T <: Face, Out <: T](value: T): M[Out] = ???
      |  }
      |
      |  implicit object Foo extends FooBuild[Foo, scala.Int]
      |
      |  def tryme[T](t: Foo[T]) = ???
      |
      |  tryme(40)
      |}
      |""".stripMargin
  )

  def testImplicitClassChainedWithValue(): Unit = checkTextHasNoErrors(
    """object Wrapper {
      |  implicit class Test[T](name: Int) {
      |    def withValue(value: T): Test[T] = this
      |  }
      |
      |  val test: Test[Boolean] = 123.withValue(true)
      |}
      |""".stripMargin
  )

  def testImplicitConversionViaTraitBoundInstance(): Unit = checkTextHasNoErrors(
    """object Wrapper {
      |  trait TestClassA
      |  trait TestClassB
      |  object TestClassA extends TestUtil[TestClassA]
      |
      |  trait TestUtil[I <: TestClassA] {
      |    implicit def convert(classA: TestClassA)(implicit smt: I): TestClassB = null
      |  }
      |  implicit val instA: TestClassA = null
      |  val instB: TestClassB = instA
      |}
      |""".stripMargin
  )
