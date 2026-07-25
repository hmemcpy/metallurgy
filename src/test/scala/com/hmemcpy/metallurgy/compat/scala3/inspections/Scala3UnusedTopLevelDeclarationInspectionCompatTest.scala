package com.hmemcpy.metallurgy.compat.scala3.inspections

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import org.jetbrains.plugins.scala.codeInspection.declarationRedundancy.ScalaUnusedDeclarationInspection

/** Port of the bundled Scala plugin's `Scala3UnusedTopLevelDeclarationInspectionTest`. The upstream uses
  * `Scala3UnusedDeclarationInspectionTestBase` which enables `setEnableInScala3(true)`. Here the inspection instance is
  * created, configured, and enabled directly.
  */
final class Scala3UnusedTopLevelDeclarationInspectionCompatTest extends Scala3CompatTestCase:

  override protected def setUp(): Unit =
    super.setUp()
    val inspection = ScalaUnusedDeclarationInspection()
    inspection.setEnableInScala3(true)
    myFixture.enableInspections(inspection)

  def testTopLevelPrivateDefUnused(): Unit = checkHasErrorAroundCaret(
    s"""package reproduction
       |
       |private def ${START}sharedHelperMethod$END(): Unit = ()
       |""".stripMargin
  )

  def testTopLevelPrivateValUnused(): Unit = checkHasErrorAroundCaret(
    s"""package reproduction
       |
       |private val ${START}sharedHelperValue$END: Int = 42
       |""".stripMargin
  )

  def testTopLevelPrivateDefUsedIsNotFlagged(): Unit =
    myFixture.addFileToProject(
      "reproduction/HelperMethodConsumer.scala",
      """package reproduction
        |
        |object HelperMethodConsumer {
        |  def invokeSharedHelper(): Unit =
        |    sharedHelperMethod()
        |}
        |""".stripMargin
    )
    checkTextHasNoErrors(
      """package reproduction
        |
        |private def sharedHelperMethod(): Unit = ()
        |""".stripMargin
    )
