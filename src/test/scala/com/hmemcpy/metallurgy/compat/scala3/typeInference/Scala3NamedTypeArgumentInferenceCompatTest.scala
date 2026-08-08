package com.hmemcpy.metallurgy.compat.scala3.typeInference

import com.hmemcpy.metallurgy.compat.scala3.adapters.Scala3TypeInferenceFixture

final class Scala3NamedTypeArgumentInferenceCompatTest extends Scala3TypeInferenceFixture:

  def testDirectNamedTypeApplicationUsesTheExactExpressionRange(): Unit =
    doTest(
      s"""
         |import scala.language.experimental.namedTypeArguments
         |
         |def make[A]: A = ???
         |
         |val value = ${START}make[A = Int]$END
         |//Int
         |""".stripMargin
    )

  def testNamedTypeApplicationInvocationUsesTheExactOuterExpressionRange(): Unit =
    doTest(
      s"""
         |import scala.language.experimental.namedTypeArguments
         |
         |def pair[A, B](a: A, b: B): B = b
         |
         |val value = ${START}pair[A = Int](1, "text")$END
         |//String
         |""".stripMargin
    )
