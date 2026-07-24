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
       |  extension(i: Int) { private def ${CARET}plus0: Int = i + 0 }
       |""".stripMargin
  )

  def testEnum(): Unit = checkHasErrorAroundCaret(
    s"""import scala.annotation.unused
       |@unused object Foo:
       |  private enum ${CARET}Fruit { case Banana }
       |""".stripMargin
  )

  def testParameterizedEnum(): Unit = checkHasErrorAroundCaret(
    s"""import scala.annotation.unused
       |@unused object Foo:
       |  private enum ${CARET}Fruit(val i: Int = 42) { case Banana }
       |""".stripMargin
  )
