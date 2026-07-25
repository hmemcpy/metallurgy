package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import org.jetbrains.plugins.scala.ScalaFileType
import org.junit.Assert.{assertEquals, assertTrue}

import scala.jdk.CollectionConverters.*

/** The bundled daemon runs on a dialect .scala file: a configured active-module file resolves to the dialect and the
  * daemon's Scala-plugin passes produce highlights on its PSI. This is the extension-inheritance + daemon-smoke proof
  * ("Scala 3"- and "Scala"-registered extensions apply to the dialect).
  */
final class DialectDaemonTest extends Scala3CompatTestCase:

  def testConfiguredScalaFileIsDialect(): Unit =
    val file = myFixture.configureByText(ScalaFileType.INSTANCE, "class Foo { val x: Int = 1 }\n")
    assertEquals("configured .scala resolves to the dialect", Scala3DotcLanguage.INSTANCE, file.getLanguage)

  def testDaemonProducesScalaHighlightsOnDialectFile(): Unit =
    myFixture.configureByText(ScalaFileType.INSTANCE, "class Foo { val x: Int = 1 }\n")
    val highlights = myFixture.doHighlighting().asScala
    assertTrue("the daemon produced highlights on the dialect file", highlights.nonEmpty)
    // SYMBOL_TYPE_SEVERITY is a Scala-plugin-defined severity; its presence means a Scala-plugin pass ran on the dialect.
    assertTrue(
      "a Scala-plugin pass produced a highlight on the dialect PSI",
      highlights.exists(_.getSeverity.toString.contains("SYMBOL_TYPE"))
    )
