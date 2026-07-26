package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import org.jetbrains.plugins.scala.ScalaFileType
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScClass
import org.junit.Assert.assertTrue
import com.intellij.psi.util.PsiTreeUtil

final class DialectFindUsagesTest extends Scala3CompatTestCase:
  def testFindUsagesOnDialectFile(): Unit =
    myFixture.configureByText(ScalaFileType.INSTANCE, "class Foo\nval x: Foo = null\nval y: Foo = null\n")
    val fooClass = PsiTreeUtil.getChildOfType(getFile, classOf[ScClass])
    val usages   = myFixture.findUsages(fooClass)
    println(s"[findusages] found=${usages.size()}")
    assertTrue("find-usages finds both references on the dialect file", usages.size() == 2)
