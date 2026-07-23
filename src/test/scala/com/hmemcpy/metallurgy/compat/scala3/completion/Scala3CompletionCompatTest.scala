package com.hmemcpy.metallurgy.compat.scala3.completion

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase

/** Port of the bundled Scala plugin's `Scala3CompletionTest` (the BASIC/count-1 cases). The `checkLookupItemsExist`
  * cases that need SMART completion or second-invocation are omitted (different completion mechanics). Snippets kept.
  */
final class Scala3CompletionCompatTest extends Scala3CompatTestCase:

  def testImplicitParamSmartCompletion(): Unit = assertSmartCompletionContains(
    s"""
       |object Test {
       |  class Blub {
       |    def xxx: Int = 3
       |  }
       |
       |  def blubImplicit(implicit i: Int): Blub = ???
       |
       |  def hehe(i: Int) = 0
       |
       |  hehe(b${CARET})
       |}
       |""".stripMargin,
    "blubImplicit.xxx"
  )

  def testUsingParamSmartCompletion(): Unit = assertSmartCompletionContains(
    s"""
       |object Test {
       |  class Blub {
       |    def xxx: Int = 3
       |  }
       |
       |  def blubUsing(using Int): Blub = ???
       |
       |  def hehe(i: Int) = 0
       |
       |  hehe(b${CARET})
       |}
       |""".stripMargin,
    "blubUsing.xxx"
  )

  def testTypeLambdaMapMemberCompletion(): Unit = assertCompletionContains(
    s"""
       |object A {
       |  type MapStrV = [V] =>> Map[String, V]
       |  val map: MapStrV[Int] = Map("ok" -> 1)
       |  map.${CARET}
       |}
       |""".stripMargin,
    "values"
  )
