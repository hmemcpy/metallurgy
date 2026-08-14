package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiErrorElement, PsiManager, SmartPointerManager}
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.stubs.{PsiFileStub, SerializationManagerEx, StubTree}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{
  ScDependentFunctionTypeElement,
  ScFunctionalTypeElement,
  ScNamedTupleTypeComponent,
  ScNamedTupleTypeElement,
  ScPolyFunctionTypeElement,
  ScTupleTypeElement
}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameter
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScGenericCall, ScMethodCall}
import org.jetbrains.plugins.scala.lang.psi.stubs.ScParameterStub
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.MetallurgyExpressionPayload
import org.junit.Assert.{assertEquals, assertNotNull, assertSame, assertTrue}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*

final class Scala3TupleFunctionTypePsiTest extends Scala3CompatTestCase:
  private val ExactScalaVersion = ScalaVersion.fromString("3.7.4").get

  override protected def supportedIn(version: ScalaVersion): Boolean = version == ExactScalaVersion

  override protected def defaultVersionOverride: Option[ScalaVersion] = Some(ExactScalaVersion)

  def testTupleAndFunctionTypesUseNativePhysicalPsi(): Unit =
    val source =
      """trait Evidence
        |class Box[A]
        |type TupleType = (Int, String, Box[(Long, Boolean)])
        |type NamedTupleType = (name: String, age: Int)
        |type FunctionType = (Int, String) => Boolean
        |type ContextFunctionType = Evidence ?=> Int
        |type DependentFunctionType = (x: Box[Int]) => x.type
        |type PolyFunctionType = [A] => A => Box[A]
        |def parameterTypes(lazyValue: => Int, values: String*): Unit
        |""".stripMargin
    val file   = physical("TupleFunctionTypes1.scala", source)

    val tuples = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScTupleTypeElement])
      .asScala
      .toVector
      .sortBy(_.getTextRange.getStartOffset)
    assertEquals(
      Vector("(Int, String, Box[(Long, Boolean)])", "(Long, Boolean)", "(Int, String)"),
      tuples.map(_.getText)
    )
    tuples.foreach(tuple =>
      assertSame(tuple, tuple.typeList.getParent)
      assertEquals(tuple.components, tuple.typeList.types)
      tuple.components.foreach(component => assertSame(tuple.typeList, component.getParent))
      assertPhysicalContract(tuple, source)
    )

    val namedTuple = PsiTreeUtil.findChildOfType(file, classOf[ScNamedTupleTypeElement])
    assertEquals("(name: String, age: Int)", namedTuple.getText)
    assertEquals(Vector("name: String", "age: Int"), namedTuple.components.map(_.getText))
    namedTuple.components.foreach: component =>
      assertSame(namedTuple, component.namedTuple)
      assertSame(namedTuple, component.getParent)
      assertEquals(Some(component.getText.takeWhile(_ != ':')), component.nameElement.map(_.getText))
      assertTrue(component.typeElement.nonEmpty)
      assertPhysicalContract(component, source)
    assertPhysicalContract(namedTuple, source)

    val functions = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunctionalTypeElement])
      .asScala
      .toVector
      .sortBy(_.getTextRange.getStartOffset)
    assertEquals(
      Vector("(Int, String) => Boolean", "Evidence ?=> Int", "A => Box[A]"),
      functions.map(_.getText)
    )
    assertEquals(Vector(false, true, false), functions.map(_.isContext))
    functions.foreach: function =>
      assertSame(function, function.paramTypeElement.getParent)
      assertTrue(function.returnTypeElement.nonEmpty)
      assertSame(function, function.returnTypeElement.get.getParent)
      assertPhysicalContract(function, source)

    val dependent = PsiTreeUtil.findChildOfType(file, classOf[ScDependentFunctionTypeElement])
    assertEquals("(x: Box[Int]) => x.type", dependent.getText)
    assertSame(dependent, dependent.parameterClause.getParent)
    assertEquals(Vector("x"), dependent.parameterClause.parameters.map(_.name))
    assertEquals(Vector("Box[Int]"), dependent.parameterClause.parameters.flatMap(_.typeElement).map(_.getText))
    assertEquals(Some("x.type"), dependent.returnTypeElement.map(_.getText))
    assertPhysicalContract(dependent.parameterClause, source)
    assertPhysicalContract(dependent, source)

    val polymorphic = PsiTreeUtil.findChildOfType(file, classOf[ScPolyFunctionTypeElement])
    assertEquals("[A] => A => Box[A]", polymorphic.getText)
    assertEquals(Vector("A"), polymorphic.typeParameters.map(_.name))
    assertEquals(Some("A => Box[A]"), polymorphic.resultTypeElement.map(_.getText))
    assertTrue(polymorphic.typeParametersClause.nonEmpty)
    assertSame(polymorphic, polymorphic.typeParametersClause.get.getParent)
    assertPhysicalContract(polymorphic.typeParametersClause.get, source)
    assertPhysicalContract(polymorphic, source)

    val components = PsiTreeUtil.findChildrenOfType(file, classOf[ScNamedTupleTypeComponent])
    assertEquals(2, components.size)

    val parameters     = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScParameter])
      .asScala
      .toVector
      .filter(parameter => Set("lazyValue", "values").contains(parameter.name))
      .sortBy(_.getTextRange.getStartOffset)
    assertEquals(Vector("lazyValue: => Int", "values: String*"), parameters.map(_.getText))
    assertEquals(Vector(true, false), parameters.map(_.isCallByNameParameter))
    assertEquals(Vector(false, true), parameters.map(_.isRepeatedParameter))
    val parameterTypes = parameters.flatMap(_.paramType)
    assertEquals(Vector("=> Int", "String*"), parameterTypes.map(_.getText))
    assertEquals(Vector(true, false), parameterTypes.map(_.isCallByNameParameter))
    assertEquals(Vector(false, true), parameterTypes.map(_.isRepeatedParameter))
    parameterTypes.foreach: parameterType =>
      assertSame(parameterType, parameterType.typeElement.getParent)
      assertPhysicalContract(parameterType, source)

    val stubTree       = file.asInstanceOf[PsiFileImpl].calcStubTree
    val parameterStubs = stubTree.getPlainList.asScala.collect { case stub: ScParameterStub => stub }.toVector
    val testedStubs    = parameterStubs.filter(stub => Set("lazyValue", "values").contains(stub.getName))
    assertEquals(Vector(false, true), testedStubs.map(_.isRepeated))
    assertEquals(Vector(true, false), testedStubs.map(_.isCallByNameParameter))
    val output         = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(stubTree.getRoot, output)
    val restored       = new StubTree(
      SerializationManagerEx.getInstanceEx
        .deserialize(new ByteArrayInputStream(output.toByteArray))
        .asInstanceOf[PsiFileStub[?]]
    )
    val restoredParams = restored.getPlainList.asScala.collect { case stub: ScParameterStub => stub }.toVector
    val restoredTested = restoredParams.filter(stub => Set("lazyValue", "values").contains(stub.getName))
    assertEquals(
      testedStubs.map(stub => stub.getName -> stub.typeText),
      restoredTested.map(stub => stub.getName -> stub.typeText)
    )
    assertEquals(testedStubs.map(_.isRepeated), restoredTested.map(_.isRepeated))
    assertEquals(testedStubs.map(_.isCallByNameParameter), restoredTested.map(_.isCallByNameParameter))
    file.asInstanceOf[PsiFileImpl].setTreeElementPointer(null)
    assertEquals(null, file.asInstanceOf[PsiFileImpl].getTreeElement)
    val unloadedParams = file
      .asInstanceOf[PsiFileImpl]
      .getStubTree
      .getPlainList
      .asScala
      .collect { case stub: ScParameterStub => stub }
      .filter(stub => Set("lazyValue", "values").contains(stub.getName))
      .toVector
    assertEquals(
      restoredTested.map(stub => stub.getName -> stub.typeText),
      unloadedParams.map(stub => stub.getName -> stub.typeText)
    )

  def testTupleAndFunctionTypesMountRecursivelyInAdmittedOwnerTypePositions(): Unit =
    val source =
      """trait Evidence
        |class Box[A]
        |trait ParentOwner extends (Int => String)
        |type LowerBound[A >: (Int, String)] = A
        |type WildcardBound = Box[? <: (Int => String)]
        |class ContextBounded[A: [X] =>> (X => String)]
        |class Parameters(value: (Int, String))(using f: Evidence ?=> Int)
        |trait Owners:
        |  self: ((Int, String) => Boolean) =>
        |  type Alias = (Int, String)
        |  type Applied = Box[Int => String]
        |  type Bounded[A <: (Int => String)] = A
        |  type Lambda = [A] =>> Box[(A, A)]
        |  def returned: (Int, String)
        |  val property: Int => String
        |  var variable: Evidence ?=> Int
        |  def takes(value: (Int, String), f: Int => String): [A] => A => A
        |""".stripMargin
    val file   = physical("TupleFunctionOwners1.scala", source)

    val tupleTexts = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScTupleTypeElement])
      .asScala
      .map(_.getText)
      .toVector
    assertEquals(7, tupleTexts.size)
    assertTrue(tupleTexts.forall(Set("(Int, String)", "(A, A)").contains))

    val functionTexts = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunctionalTypeElement])
      .asScala
      .map(_.getText)
      .toVector
    Vector(
      "Int => String",
      "(Int, String) => Boolean",
      "Evidence ?=> Int",
      "A => A"
    ).foreach(expected => assertTrue(functionTexts.toString, functionTexts.contains(expected)))
    assertEquals(11, functionTexts.size)

    val polyTexts = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScPolyFunctionTypeElement])
      .asScala
      .map(_.getText)
      .toVector
    assertEquals(Vector("[A] => A => A"), polyTexts)

  def testCopiesPointersRenameAndReparseTupleAndFunctionTypes(): Unit =
    val source           =
      """class Box[A]
        |type Named = (before: Int, peer: String)
        |type Dependent = (value: Box[Int]) => value.type
        |def parameters(thunk: => Int, values: String*): Unit
        |""".stripMargin
    val file             = physical("TupleFunctionEdits1.scala", source)
    val named            = PsiTreeUtil.findChildOfType(file, classOf[ScNamedTupleTypeElement])
    val component        = named.components.head
    val dependent        = PsiTreeUtil.findChildOfType(file, classOf[ScDependentFunctionTypeElement])
    val dependentParam   = dependent.parameterClause.parameters.head
    val componentPointer = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(component)
    val dependentPointer = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(dependent)
    val copy             = file.copy()
    assertEquals(source, copy.getText)
    assertEquals(
      "(before: Int, peer: String)",
      PsiTreeUtil.findChildOfType(copy, classOf[ScNamedTupleTypeElement]).getText
    )
    assertSame(component, component.getNavigationElement)
    assertSame(dependentParam, dependentParam.getNavigationElement)
    assertTrue(myFixture.findUsages(component).isEmpty)
    assertTrue(myFixture.findUsages(dependentParam).isEmpty)

    myFixture.renameElement(component, "after")
    assertEquals("after: Int", componentPointer.getElement.getText)
    assertEquals("(value: Box[Int]) => value.type", dependentPointer.getElement.getText)

    val document    = PsiDocumentManager.getInstance(getProject).getDocument(file)
    assertNotNull(document)
    val byNameStart = document.getText.indexOf("=> Int")
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(byNameStart, byNameStart + "=> Int".length, "Long")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    val parameters  = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScParameter])
      .asScala
      .toVector
      .filter(parameter => Set("thunk", "values").contains(parameter.name))
      .sortBy(_.getTextOffset)
    assertEquals(Vector("thunk: Long", "values: String*"), parameters.map(_.getText))
    assertEquals(Vector(false, true), parameters.map(_.isRepeatedParameter))

  def testRepresentativeTupleWidthAndFunctionDepthRetainOrderRangesAndAncestry(): Unit =
    val width      = 32
    val depth      = 16
    val components = Vector.tabulate(width)(index => s"field$index: T$index")
    val nested     = (0 until depth).reverse.foldLeft("Result")((body, index) => s"T$index => $body")
    val source     = s"type Wide = (${components.mkString(", ")})\ntype Deep = $nested\n"
    val file       = physical("TupleFunctionScale1.scala", source)
    val named      = PsiTreeUtil.findChildOfType(file, classOf[ScNamedTupleTypeElement])
    assertEquals(width, named.components.size)
    Vector(0, width / 2, width - 1).foreach: index =>
      val component = named.components(index)
      assertEquals(components(index), component.getText)
      assertEquals(component.getText, component.getTextRange.substring(source))
      assertSame(named, component.getParent)
    val functions  = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunctionalTypeElement])
      .asScala
      .toVector
      .sortBy(_.getTextRange.getStartOffset)
    assertEquals(depth, functions.size)
    Vector(0, depth / 2, depth - 1).foreach: index =>
      assertEquals(functions(index).getText, functions(index).getTextRange.substring(source))
      assertEquals(s"T$index", functions(index).paramTypeElement.getText)
    functions.indices.dropRight(1).foreach(index => assertSame(functions(index), functions(index + 1).getParent))

  def testNestedFunctionDelimitersAndHigherKindedPolyParametersHaveOnePhysicalOwner(): Unit =
    val source      =
      """class Box[A]
        |class Result
        |class Input
        |type NestedLeft = Box[(Int, String)] => Result
        |type NestedRight = Input => Box[(Int, String)]
        |type HigherKinded = [F[_]] => F[Int] => F[Int]
        |type NestedPoly = [F[_], A <: F[(Int, String)]] => A => A
        |""".stripMargin
    val file        = physical("TupleFunctionNestedDelimiters1.scala", source)
    val functions   = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunctionalTypeElement])
      .asScala
      .toVector
      .sortBy(_.getTextOffset)
    assertEquals(
      Vector(
        "Box[(Int, String)] => Result",
        "Input => Box[(Int, String)]",
        "F[Int] => F[Int]",
        "A => A"
      ),
      functions.map(_.getText)
    )
    functions.foreach(assertPhysicalContract(_, source))
    val polymorphic = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScPolyFunctionTypeElement])
      .asScala
      .toVector
      .sortBy(_.getTextOffset)
    assertEquals(
      Vector("[F[_]] => F[Int] => F[Int]", "[F[_], A <: F[(Int, String)]] => A => A"),
      polymorphic.map(_.getText)
    )
    assertEquals(Vector(Vector("F"), Vector("F", "A")), polymorphic.map(_.typeParameters.map(_.name).toVector))
    polymorphic.foreach(assertPhysicalContract(_, source))
    val tuples      = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScTupleTypeElement])
      .asScala
      .toVector
      .sortBy(_.getTextOffset)
    assertEquals(Vector.fill(3)("(Int, String)"), tuples.map(_.getText))
    tuples.foreach(assertPhysicalContract(_, source))

  def testRepeatedParametersAndTupleResultsKeepNamedTypeApplicationIslandsPhysical(): Unit =
    val source =
      """import scala.language.experimental.namedTypeArguments
        |def construct[Elem, Coll[_]](xs: Elem*): Coll[Elem] = ???
        |val xs = construct[Coll = List](1, 2, 3)
        |""".stripMargin
    val file   = physical("TupleFunctionApplicability1.scala", source)
    val call   = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScMethodCall])
      .asScala
      .find(_.getText == "construct[Coll = List](1, 2, 3)")
    assertTrue(call.toString, call.nonEmpty)
    assertEquals("construct[Coll = List]", call.get.getInvokedExpr.getText)
    assertTrue(call.get.getInvokedExpr.isInstanceOf[ScGenericCall])
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyExpressionPayload]).isEmpty)

  private def physical(name: String, source: String) =
    val pending = myFixture.addFileToProject(s"src/$name", source)
    val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals(source, file.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(failure.toString, failure.isEmpty)
    file

  private def assertPhysicalContract(element: PsiElement, source: String): Unit =
    assertEquals(element.getText, element.getTextRange.substring(source))
    val children = element.getNode.getChildren(null).toVector
    assertTrue(element.getText, children.nonEmpty)
    assertEquals(element.getText, children.map(_.getText).mkString)
    assertEquals(element.getTextRange.getStartOffset, children.head.getStartOffset)
    assertEquals(element.getTextRange.getEndOffset, children.last.getStartOffset + children.last.getTextLength)
    children
      .sliding(2)
      .foreach:
        case Vector(left, right) => assertEquals(left.getStartOffset + left.getTextLength, right.getStartOffset)
        case _                   => ()
    children.foreach(child => assertSame(element.getNode, child.getTreeParent))
