package com.hmemcpy.metallurgy.compat.scala3.inspections

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import org.jetbrains.plugins.scala.codeInspection.declarationRedundancy.ScalaUnusedDeclarationInspection

/** Port of the bundled Scala plugin's `Scala3UnusedLocalDeclarationOneContainerInspectionTest`. Tests that unused
  * private declarations inside an object are flagged by the unused-declaration inspection with `setEnableInScala3`.
  */
final class Scala3UnusedLocalDeclarationInspectionCompatTest extends Scala3CompatTestCase:

  override protected def setUp(): Unit =
    super.setUp()
    val inspection = ScalaUnusedDeclarationInspection()
    inspection.setEnableInScala3(true)
    myFixture.enableInspections(inspection)

  def testExtensionMethod(): Unit = checkHasErrorAroundCaret(
    s"""import scala.annotation.unused
       |@unused object Foo:
       |  extension(i: Int) { private def ${START}plus0$END: Int = i + 0 }
       |end Foo
       |""".stripMargin
  )

  def testEnum(): Unit = checkHasErrorAroundCaret(
    s"""import scala.annotation.unused
       |@unused object Foo:
       |  private enum ${START}Fruit$END { case ${START}Banana$END }
       |end Foo
       |""".stripMargin
  )

  def testParameterizedEnum(): Unit = checkHasErrorAroundCaret(
    s"""import scala.annotation.unused
       |@unused object Foo:
       |  private enum ${START}Fruit$END(val i: Int = 42) { case ${START}Banana$END }
       |end Foo
       |""".stripMargin
  )
