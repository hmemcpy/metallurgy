/*
 * Metallurgy adaptation: package, owner, and fixture wiring.
 * Executable class-body bytes equal the licensed source snapshot.
 */
package com.hmemcpy.metallurgy.generated.intellijscala.typeInference

import org.jetbrains.plugins.scala.ScalaVersion
import com.hmemcpy.metallurgy.compat.scala3.adapters.Scala3TypeInferenceFixture

final class NamedTypeArgumentsInferenceTest extends Scala3TypeInferenceFixture {
  override protected def supportedIn(version: ScalaVersion): Boolean = version.isScala3

  def testMethodInvocationWithPartiallyNamedTypeArguments_InferSecondParam(): Unit = doTest(
    s"""
       |import scala.language.experimental.namedTypeArguments
       |
       |def pair[A, B](a: A, b: B): (A, B) = (a, b)
       |
       |val value = ${START}pair[A = Int](1, "text")$END
       |//(Int, String)
       |""".stripMargin
  )

  def testMethodInvocationWithPartiallyNamedTypeArguments_InferFirstParam(): Unit = doTest(
    s"""
       |import scala.language.experimental.namedTypeArguments
       |
       |def pair[A, B](a: A, b: B): (A, B) = (a, b)
       |
       |val value = ${START}pair[B = String](1, "text")$END
       |//(Int, String)
       |""".stripMargin
  )

  def testGenericCallWithPartiallyNamedTypeArguments_InferSecondParamFromExpectedType(): Unit = doTest(
    s"""
       |import scala.language.experimental.namedTypeArguments
       |
       |def make[A, B]: (A, B) = ???
       |
       |val value: (Int, String) = ${START}make[A = Int]$END
       |//(Int, String)
       |""".stripMargin
  )

  def testGenericCallWithPartiallyNamedTypeArguments_InferFirstParamFromExpectedType(): Unit = doTest(
    s"""
       |import scala.language.experimental.namedTypeArguments
       |
       |def make[A, B]: (A, B) = ???
       |
       |val value: (Int, String) = ${START}make[B = String]$END
       |//(Int, String)
       |""".stripMargin
  )

  def testDocsExampleConstructWithNamedTypeArguments(): Unit = doTest(
    s"""
       |import scala.language.experimental.namedTypeArguments
       |
       |def construct[Elem, Coll[_]](xs: Elem*): Coll[Elem] = ???
       |
       |val xs1 = construct[Coll = List, Elem = Int](1, 2, 3)
       |val xs2 = ${START}construct[Coll = List](1, 2, 3)$END
       |//List[Int]
       |""".stripMargin
  )
}
