package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import org.jetbrains.plugins.scala.ScalaFileType
import org.junit.Assert.{assertNotNull, assertTrue}

final class DialectCompletionTest extends Scala3CompatTestCase:
  def testCompletionOnDialectFile(): Unit =
    myFixture.configureByText(ScalaFileType.INSTANCE, "class Foo\nval x: F<caret>")
    myFixture.completeBasic()
    val strings = myFixture.getLookupElementStrings
    assertNotNull("completion produced variants", strings)
    val has     = strings != null && strings.contains("Foo")
    println(s"[completion] variants=${if strings == null then "null" else strings.size} hasFoo=$has")
    assertTrue("Foo appears in completion on the dialect file", has)
