package com.hmemcpy.metallurgy.psiproducer

import com.intellij.lang.DependentLanguage
import com.intellij.lang.InjectableLanguage
import com.intellij.lang.jvm.JvmLanguage
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.Scala3Language
import org.jetbrains.plugins.scala.ScalaLanguage
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunctionDefinition
import org.junit.Assert.{assertEquals, assertNotNull, assertTrue}

/** Phase 0 (#74), task 1: the Metallurgy dialect is a kind of Scala 3 and Scala, carries Scala3Language's marker
  * interfaces, and parses a Scala source into source-compatible Scala PSI. The seam is the file's public surface
  * (language, kind-of, marker types, parsed children).
  */
final class Scala3DotcLanguageTest extends ScalaLightCodeInsightFixtureTestCase:

  override def getTestDataPath: String =
    java.nio.file.Path.of("src", "test", "testdata").toAbsolutePath.toString

  def testLanguageGraphIsKindOfScala3AndScala(): Unit =
    val dialect = Scala3DotcLanguage.INSTANCE
    assertTrue("dialect is a kind of Scala 3", dialect.isKindOf(Scala3Language.INSTANCE))
    assertTrue("dialect is a kind of Scala", dialect.isKindOf(ScalaLanguage.INSTANCE))
    assertEquals("Scala 3 is the base", Scala3Language.INSTANCE, dialect.getBaseLanguage)

  def testCarriesScala3MarkerInterfaces(): Unit =
    val dialect = Scala3DotcLanguage.INSTANCE
    assertTrue("JvmLanguage", dialect.isInstanceOf[JvmLanguage])
    assertTrue("DependentLanguage", dialect.isInstanceOf[DependentLanguage])
    assertTrue("InjectableLanguage", dialect.isInstanceOf[InjectableLanguage])

  def testDialectFileParsesIntoScalaPsi(): Unit =
    val file = PsiFileFactory
      .getInstance(getProject)
      .createFileFromText("A.scala", Scala3DotcLanguage.INSTANCE, "class A {\n  def foo = 1\n}\n")
    assertEquals("file language is the dialect", Scala3DotcLanguage.INSTANCE, file.getLanguage)
    val fn   = PsiTreeUtil.findChildOfType(file, classOf[ScFunctionDefinition])
    assertNotNull("dialect file parses into Scala PSI (a function definition)", fn)
