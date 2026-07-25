package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.plugins.scala.Scala3Language
import org.jetbrains.plugins.scala.caches.ScalaShortNamesCacheManager
import org.junit.Assert.{assertEquals, assertTrue}

/** A `.scala` file in an active Metallurgy module loads as the dialect, its declarations are stub-indexed through the
  * Scala short-names cache, and an inactive module stays on the bundled Scala 3. Exercises real file loading (a
  * physical module file), not an in-memory copy.
  */
final class DialectFileLoadingTest extends Scala3CompatTestCase:

  def testActiveModuleScalaFileLoadsAsDialect(): Unit =
    val file = myFixture.addFileToProject("Foo.scala", "class Foo\n")
    assertEquals("active-module .scala loads as the dialect", Scala3DotcLanguage.INSTANCE, file.getLanguage)

  def testDialectFileClassIsStubIndexed(): Unit =
    myFixture.addFileToProject("Bar.scala", "class Bar\n")
    val found = ScalaShortNamesCacheManager
      .getInstance(using getProject)
      .getClassesByName("Bar", GlobalSearchScope.projectScope(getProject))
    assertTrue("a class in a dialect file is stub-indexed and resolvable", found.exists(_.getName == "Bar"))

  def testInactiveModuleLoadsAsBundledScala3(): Unit =
    MetallurgySettings(getProject).setEnabled(getModule, enabled = false)
    try
      val file = myFixture.addFileToProject("Baz.scala", "class Baz\n")
      assertEquals("inactive module stays on bundled Scala 3", Scala3Language.INSTANCE, file.getLanguage)
    finally MetallurgySettings(getProject).setEnabled(getModule, enabled = true)
