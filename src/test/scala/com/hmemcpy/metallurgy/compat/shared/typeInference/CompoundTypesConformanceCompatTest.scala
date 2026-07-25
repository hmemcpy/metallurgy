package com.hmemcpy.metallurgy.compat.shared.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Verbatim port of the bundled Scala plugin's `CompoundTypesConformanceTest` (default `supportedIn` = all versions).
  */
final class CompoundTypesConformanceCompatTest extends Scala3CompatTestCase:

  def testCompoundTypeConformanceInSuperConstructor(): Unit = checkTextHasNoErrors(
    """trait Container[T]
      |trait Concrete
      |trait A
      |trait B
      |class Parent[T](t: Container[Concrete with T])
      |class Child(t: Container[Concrete with A with B]) extends Parent[A with B](t)
    """.stripMargin
  )

  def testCompoundTypeInServerEndpointResult(): Unit = checkTextHasNoErrors(
    """
      |object A {
      |  trait MyZIO[-R]
      |  trait MyZServerEndpoint[R]
      |
      |  trait MyZPartialServerEndpoint[R] {
      |    def serverLogic[R0](logic: MyZIO[R0]): MyZServerEndpoint[R with R0] = ???
      |  }
      |
      |  val value: MyZPartialServerEndpoint[Any] = ???
      |
      |  val value3: MyZServerEndpoint[Any] = value.serverLogic((null: MyZIO[Any]))
      |}
      |""".stripMargin
  )
