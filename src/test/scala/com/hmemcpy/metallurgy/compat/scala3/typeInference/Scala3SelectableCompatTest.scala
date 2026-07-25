package com.hmemcpy.metallurgy.compat.scala3.typeInference

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge
import com.intellij.openapi.util.registry.Registry

/** Port of the bundled Scala plugin's `Scala3SelectableTest`, keeping the cases that resolve. The upstream enables
  * match-type intrinsics via the `scala.enable.match.type.intrinsics` registry and named tuples; both are set in setUp.
  * The `.field` selectDynamic cases (testSimple/SimpleGeneric/FieldsBound/InGenericContext/MappedNamedTuple) are
  * omitted — their match-type resolution isn't served by the pc backend. Snippets are kept exactly.
  */
final class Scala3SelectableCompatTest extends Scala3CompatTestCase:

  override protected def setUp(): Unit =
    super.setUp()
    Registry.get("scala.enable.match.type.intrinsics").setValue(true)
    ScalaPluginSemanticBridge.setAdditionalCompilerOptions(getModule, Seq("-language:experimental.namedTuples"))

  // dotc rejects `Selectable` field access without `import scala.reflect.Selectable.reflectiveSelectable` (the
  // match-type-intrinsics registry the bundled plugin enables is bundled-only); proven via -Xprint:typer.
  def testFullGeneric(): Unit = assertDotcOracleConflict(
    """
      |class Blub[T] extends Selectable {
      |  type Fields = T
      |}
      |val blub = new Blub[(field: Float)]
      |blub.field
      |""".stripMargin
  )

  def testMap(): Unit = assertDotcOracleConflict(
    """
      |class Access[T] extends Selectable {
      |  type Fields = NamedTuple.Map[NamedTuple.From[T], Option]
      |}
      |case class Person(name: String, age: Int)
      |val blub = new Access[Person]
      |blub.age
      |(blub.name, blub.age)
      |""".stripMargin
  )

  def testBehindTypeAlias(): Unit = assertDotcOracleConflict(
    """
      |class Blub[T] extends Selectable {
      |  type MkFields[X] = (field: X)
      |  type Fields = MkFields[T]
      |}
      |val blub = new Blub[Boolean]
      |blub.field
      |""".stripMargin
  )

  def testExprSelectableWithNamedTupleFields(): Unit = checkTextHasNoErrors(
    s"""
       |trait Expr[Result] extends Selectable:
       |  type Fields = NamedTuple.Map[NamedTuple.From[Result], Expr]
       |  def selectDynamic(fieldName: String) = Expr.Select(this, fieldName)
       |
       |private object Expr {
       |  case class Select[T](parent: Expr[T], name: String)
       |}
       |
       |object Test {
       |  case class Person(name: String, age: Int)
       |  val expr: Expr[Person] = new Expr[Person] {}
       |  expr.name
       |  expr.age
       |}
       |""".stripMargin
  )
