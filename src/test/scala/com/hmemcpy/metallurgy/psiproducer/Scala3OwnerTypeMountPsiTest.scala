package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.stubs.{
  IndexSink,
  ObjectStubSerializer,
  PsiFileStub,
  SerializationManagerEx,
  Stub,
  StubIndexKey,
  StubTree
}
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiErrorElement, PsiManager, SmartPointerManager}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.api.base.ScConstructorInvocation
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{ScParameterizedTypeElement, ScSelfTypeElement, ScTypeArgs}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{ScClassParameter, ScParameter, ScParameterType}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{
  ScFunction,
  ScFunctionDeclaration,
  ScTypeAliasDefinition,
  ScValue,
  ScValueDeclaration,
  ScVariable,
  ScVariableDeclaration
}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.{ScDerivesClause, ScTemplateParents}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScClass, ScTrait}
import org.junit.Assert.{assertEquals, assertNotNull, assertSame, assertTrue}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*

final class Scala3OwnerTypeMountPsiTest extends Scala3CompatTestCase:

  def testSimpleTypesMountIntoEveryAdmittedOwner(): Unit =
    val source =
      """trait Base
        |trait Other
        |
        |type TopAlias = Base
        |def topResult: Base = ???
        |val topValue: Base = topResult
        |var topVariable: Base = topValue
        |
        |trait Members extends Base:
        |  self: Other =>
        |  type Alias = Base
        |  def declared: Base
        |  def result(value: Base): Base = value
        |  val value: Base
        |  var variable: Base
        |
        |class Parameters(value: Base)(using context: Other) extends Base
        |class ParenthesizedParent extends (Base)
        |class MultipleParents extends Base, Other
        |enum Derived derives CanEqual:
        |  case Only
        |""".stripMargin
    val file   = physical("OwnerTypeMounts1.scala", source)

    val functions = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunction])
      .asScala
      .toVector
      .sortBy(_.getTextRange.getStartOffset)
    assertEquals(Vector("topResult", "declared", "result"), functions.map(_.name))
    assertEquals(Vector("Base", "Base", "Base"), functions.flatMap(_.returnTypeElement).map(_.getText))
    assertEquals(Vector("Base"), functions.flatMap(_.parameters).flatMap(_.typeElement).map(_.getText))
    assertEquals(
      Vector("ScFunctionDefinitionImpl", "ScFunctionDeclarationImpl", "ScFunctionDefinitionImpl"),
      functions.map(_.getClass.getSimpleName)
    )

    val values    = PsiTreeUtil.findChildrenOfType(file, classOf[ScValue]).asScala.toVector
    assertEquals(Vector("Base", "Base"), values.flatMap(_.typeElement).map(_.getText))
    assertEquals(Vector("value"), values.collect { case value: ScValueDeclaration => value.getIdList.getText })
    assertEquals(Vector("ScPatternDefinitionImpl", "ScValueDeclarationImpl"), values.map(_.getClass.getSimpleName))
    val variables = PsiTreeUtil.findChildrenOfType(file, classOf[ScVariable]).asScala.toVector
    assertEquals(Vector("Base", "Base"), variables.flatMap(_.typeElement).map(_.getText))
    assertEquals(
      Vector("variable"),
      variables.collect { case variable: ScVariableDeclaration => variable.getIdList.getText }
    )
    assertEquals(
      Vector("ScVariableDefinitionImpl", "ScVariableDeclarationImpl"),
      variables.map(_.getClass.getSimpleName)
    )
    assertEquals(Vector("declared"), functions.collect { case function: ScFunctionDeclaration => function.name })

    val aliases = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScTypeAliasDefinition])
      .asScala
      .toVector
      .sortBy(_.getTextRange.getStartOffset)
    assertEquals(Vector("TopAlias", "Alias"), aliases.map(_.name))
    assertEquals(Vector("Base", "Base"), aliases.flatMap(_.aliasedTypeElement).map(_.getText))
    assertTrue(aliases.forall(_.getClass.getSimpleName == "ScTypeAliasDefinitionImpl"))

    val parameters = PsiTreeUtil.findChildOfType(file, classOf[ScClass]).parameters
    assertEquals(Vector("Base", "Other"), parameters.flatMap(_.typeElement).map(_.getText))
    assertTrue(
      parameters.forall(parameter => parameter.typeElement.exists(_.getParent.isInstanceOf[ScParameterType]))
    )
    assertTrue(parameters.forall(_.isInstanceOf[ScClassParameter]))
    assertTrue(parameters.forall(_.getClass.getSimpleName == "ScClassParameterImpl"))
    assertTrue(
      parameters
        .flatMap(_.typeElement)
        .forall(typeElement =>
          typeElement.getParent.getClass.getSimpleName == "ScParameterTypeImpl" &&
            typeElement.getParent.getParent.isInstanceOf[ScClassParameter]
        )
    )

    val parentClauses = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScTemplateParents])
      .asScala
      .toVector
      .flatMap(_.parentClauses)
    assertEquals(Vector("Base", "Base", "(Base)", "Base", "Other"), parentClauses.map(_.getText))
    assertTrue(parentClauses.forall(_.args.isEmpty))
    assertTrue(parentClauses.forall(_.getParent.isInstanceOf[ScTemplateParents]))
    assertTrue(parentClauses.forall(_.isInstanceOf[ScConstructorInvocation]))
    assertTrue(parentClauses.forall(_.getClass.getSimpleName == "ScConstructorInvocationImpl"))
    assertTrue(parentClauses.forall(_.arguments.isEmpty))
    assertTrue(parentClauses.forall(_.typeArgList.isEmpty))
    assertTrue(parentClauses.forall(invocation => invocation.typeElement.getParent == invocation))

    val selfType = PsiTreeUtil.findChildOfType(file, classOf[ScSelfTypeElement])
    assertEquals("self: Other =>", selfType.getText)
    assertEquals(Some("Other"), selfType.typeElement.map(_.getText))
    assertTrue(selfType.getParent.getParent.getParent.isInstanceOf[ScTrait])
    assertEquals("ScSelfTypeElementImpl", selfType.getClass.getSimpleName)
    assertTrue(selfType.typeElement.forall(_.getParent == selfType))

    val derives = PsiTreeUtil.findChildOfType(file, classOf[ScDerivesClause])
    assertEquals("derives CanEqual", derives.getText)
    assertEquals(Vector("CanEqual"), derives.derivedReferences.map(_.getText))
    assertSame(derives.getParent.getParent, derives.owner)
    assertEquals("ScDerivesClauseImpl", derives.getClass.getSimpleName)
    assertTrue(derives.derivedReferences.forall(_.getParent == derives))

  def testEveryTypeAtomMountsIntoAliasesResultsParametersAndProperties(): Unit =
    val source =
      """trait T:
        |  type A
        |
        |object p:
        |  type A = Int
        |
        |val x = 1
        |
        |type AliasReference = T
        |type AliasPath = p.A
        |type AliasProjection = T#A
        |type AliasSingleton = x.type
        |type AliasLiteral = 42
        |type AliasParenthesized = (T)
        |
        |trait Results:
        |  def reference: T
        |  def path: p.A
        |  def projection: T#A
        |  def singleton: x.type
        |  def literal: 42
        |  def parenthesized: (T)
        |  val referenceValue: T
        |  val pathValue: p.A
        |  val projectionValue: T#A
        |  val singletonValue: x.type
        |  val literalValue: 42
        |  val parenthesizedValue: (T)
        |
        |def parameters(
        |  reference: T,
        |  path: p.A,
        |  projection: T#A,
        |  singleton: x.type,
        |  literal: 42,
        |  parenthesized: (T)
        |): T = reference
        |
        |class ClassParameters(
        |  reference: T,
        |  path: p.A,
        |  projection: T#A,
        |  singleton: x.type,
        |  literal: 42,
        |  parenthesized: (T)
        |)
        |""".stripMargin
    val file   = physical("OwnerTypeAtoms1.scala", source)
    val atoms  = Vector("T", "p.A", "T#A", "x.type", "42", "(T)")

    val aliases = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScTypeAliasDefinition])
      .asScala
      .toVector
      .filter(_.name.startsWith("Alias"))
    assertEquals(atoms, aliases.flatMap(_.aliasedTypeElement).map(_.getText))

    val results = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunctionDeclaration])
      .asScala
      .toVector
    assertEquals(atoms, results.flatMap(_.returnTypeElement).map(_.getText))

    val values = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScValueDeclaration])
      .asScala
      .toVector
    assertEquals(atoms, values.flatMap(_.typeElement).map(_.getText))

    val functions  = PsiTreeUtil.findChildrenOfType(file, classOf[ScFunction]).asScala.toVector
    val parameters = functions.find(_.name == "parameters").get.parameters.toVector
    assertEquals(atoms, parameters.flatMap(_.typeElement).map(_.getText))
    assertEquals(Vector.fill(atoms.size)("ScParameterImpl"), parameters.map(_.getClass.getSimpleName))

    val classParameters = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScClass])
      .asScala
      .find(_.name == "ClassParameters")
      .get
      .parameters
      .toVector
    assertEquals(atoms, classParameters.flatMap(_.typeElement).map(_.getText))
    assertEquals(Vector.fill(atoms.size)("ScClassParameterImpl"), classParameters.map(_.getClass.getSimpleName))

  def testAppliedTypesMountIntoEveryAdmittedStubBearingOwner(): Unit =
    val source  =
      """trait Box[A]
        |type Alias = Box[Int]
        |def topResult: Box[Int] = ???
        |val topValue: Box[Int] = topResult
        |var topVariable: Box[Int] = topValue
        |trait Members:
        |  def declared: Box[Int]
        |  def result(value: Box[Int]): Box[Int] = value
        |  val value: Box[Int]
        |  var variable: Box[Int]
        |class Parameters(value: Box[Int]) extends Box[Int]
        |""".stripMargin
    val file    = physical("OwnerAppliedTypeMounts1.scala", source)
    val applied = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScParameterizedTypeElement])
      .asScala
      .toVector
      .filter(_.getText == "Box[Int]")

    assertEquals(11, applied.size)
    applied.foreach: value =>
      assertEquals("Box", value.typeElement.getText)
      assertEquals("[Int]", value.typeArgList.getText)
      assertSame(value, value.typeElement.getParent)
      assertSame(value, value.typeArgList.getParent)
      assertEquals(Vector("Int"), value.typeArgList.typeArgs.map(_.getText).toVector)
    val parent = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScConstructorInvocation])
      .asScala
      .find(_.getText == "Box[Int]")
      .get
    assertEquals(Some("[Int]"), parent.typeArgList.map(_.getText))
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeArgs]).asScala.forall(_.typeArgs.nonEmpty))

  def testLaterTypeFamiliesAndTermParentApplicationsRemainFailClosed(): Unit =
    Vector(
      "trait A\ntype Rejected = Map[K = Int, V = String]\n",
      "trait A\ntrait B\ntype Rejected = A & B\n",
      "trait A\ntrait B\ntype Rejected = A | B\n",
      "trait A\ntrait B\ntype Rejected = A *: B\n",
      "type Rejected = (Int => String) throws Exception\n",
      "trait A\ntrait B\ntype Rejected = A match { case A => B }\n",
      "trait A\ntype Rejected = A { type Member }\n",
      "trait A\ntype Rejected = A @unchecked\n",
      "class Parent(value: Int)\nclass Rejected extends Parent(1)\n"
    ).zipWithIndex.foreach: (source, index) =>
      val pending = myFixture.addFileToProject(s"src/OwnerTypeRejected${index + 1}.scala", source)
      val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
      assertEquals(source, file.getText)
      file.getChildren
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(source, failure.nonEmpty)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScFunction]).isEmpty)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeAliasDefinition]).isEmpty)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScTemplateParents]).isEmpty)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScParameter]).isEmpty)

  def testCopiesPointersAndReplacementDeletionInsertionReparseOwners(): Unit =
    val source        =
      """trait Base
        |trait Other
        |class Before(value: Base) extends Base:
        |  self: Base =>
        |  def result(input: Base): Base = input
        |""".stripMargin
    val file          = physical("OwnerTypeEdits1.scala", source)
    val owner         = PsiTreeUtil.findChildOfType(file, classOf[ScClass])
    val input         = owner.functions.head.parameters.head
    val result        = owner.functions.head.returnTypeElement.get
    val ownerPointer  = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(owner)
    val inputPointer  = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(input)
    val resultPointer = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(result)
    val copy          = file.copy()
    assertEquals(source, copy.getText)
    assertEquals("Base", PsiTreeUtil.findChildOfType(copy, classOf[ScClassParameter]).typeElement.get.getText)

    val document      = PsiDocumentManager.getInstance(getProject).getDocument(file)
    assertNotNull(document)
    val parameterType = source.indexOf("input: Base") + "input: ".length
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(parameterType, parameterType + 4, "Other")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("Other", inputPointer.getElement.typeElement.get.getText)
    assertEquals("Base", resultPointer.getElement.getText)
    assertEquals("Before", ownerPointer.getElement.name)

    val extendsType = document.getText.indexOf("extends Base") + "extends Base".length
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.insertString(extendsType, "(1)")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertTrue(file.getText.contains("extends Base(1):"))
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScClass]).isEmpty)

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.deleteString(extendsType, extendsType + 3)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    file.getChildren
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(file.getText))
        .isEmpty
    )
    assertEquals("Before", PsiTreeUtil.findChildOfType(file, classOf[ScClass]).name)
    assertEquals(
      "Other",
      PsiTreeUtil.findChildOfType(file, classOf[ScFunction]).parameters.head.typeElement.get.getText
    )

  def testOwnerStubSerializationIndicesAndAstReloadAreStable(): Unit =
    val source        =
      """package ownerstubs
        |trait Base
        |trait Owners extends Base:
        |  self: Base =>
        |  def declared(input: Base): Base
        |  val value: Base
        |  var variable: Base
        |  type Alias = Base
        |class Parameters(value: Base) extends Base
        |enum Derived derives CanEqual:
        |  case Only
        |""".stripMargin
    val file          = physical("OwnerTypeStubs1.scala", source).asInstanceOf[PsiFileImpl]
    val tree          = file.calcStubTree
    val stubs         = tree.getPlainList.asScala.toVector
    val externalIds   = stubs.flatMap(stub => Option(stub.getStubSerializer).map(_.getExternalId)).toSet
    assertTrue(
      Set(
        "scala.parameter",
        "scala.class parameter",
        "scala.template parents",
        "scala.self type element",
        "scala.template derives",
        "scala.function declaration",
        "scala.value declaration",
        "scala.variable declaration",
        "scala.id list",
        "scala.field id",
        "scala.type alias definition"
      ).subsetOf(externalIds)
    )
    val beforeShape   = stubShape(stubs)
    val beforeIndex   = indexShape(stubs)
    val renderedIndex = beforeIndex.mkString("\n")
    assertTrue(renderedIndex, beforeIndex.contains("sc.super.class.name|Base"))
    assertTrue(renderedIndex, beforeIndex.contains("sc.self.type.class.name.key|Base"))
    assertTrue(renderedIndex, beforeIndex.contains("sc.method.name|declared"))
    assertTrue(renderedIndex, beforeIndex.contains("sc.property.name|value"))
    assertTrue(renderedIndex, beforeIndex.contains("sc.property.name|variable"))
    assertTrue(renderedIndex, beforeIndex.contains("sc.type.alias.name|Alias"))

    val output   = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(tree.getRoot, output)
    val restored = new StubTree(
      SerializationManagerEx.getInstanceEx
        .deserialize(new ByteArrayInputStream(output.toByteArray))
        .asInstanceOf[PsiFileStub[?]]
    )
    assertEquals(beforeShape, stubShape(restored.getPlainList.asScala))
    assertEquals(beforeIndex, indexShape(restored.getPlainList.asScala))
    file.setTreeElementPointer(null)
    assertEquals(null, file.getTreeElement)
    assertEquals(beforeShape, stubShape(file.getStubTree.getPlainList.asScala))

  def testOwnerNavigationRenameAndEmptyUsageSearchPreserveIdentity(): Unit =
    val source               =
      """trait Base
        |class Before(value: Base) extends Base:
        |  def declared(input: Base): Base
        |  type Alias = Base
        |""".stripMargin
    val file                 = physical("OwnerTypeNavigation1.scala", source)
    val owner                = PsiTreeUtil.findChildrenOfType(file, classOf[ScClass]).asScala.find(_.name == "Before").get
    val constructorParameter = owner.parameters.head
    val function             = owner.functions.head
    val functionParameter    = function.parameters.head
    val alias                = PsiTreeUtil.findChildOfType(owner, classOf[ScTypeAliasDefinition])
    val parent               = owner.extendsBlock.templateParents.get.parentClauses.head

    Vector(owner, constructorParameter, function, functionParameter, alias, parent).foreach(element =>
      assertSame(element, element.getNavigationElement)
    )
    Vector(owner, constructorParameter, function, functionParameter, alias).foreach(element =>
      assertTrue(element.getText, myFixture.findUsages(element).isEmpty)
    )

    myFixture.renameElement(constructorParameter, "renamedValue")
    myFixture.renameElement(functionParameter, "renamedInput")
    myFixture.renameElement(function, "renamedDeclared")
    myFixture.renameElement(alias, "RenamedAlias")
    myFixture.renameElement(owner, "After")
    assertEquals(
      """trait Base
        |class After(renamedValue: Base) extends Base:
        |  def renamedDeclared(renamedInput: Base): Base
        |  type RenamedAlias = Base
        |""".stripMargin,
      file.getText
    )

  def testRepeatedAndDeepOwnerMountsRetainRepresentativeScale(): Unit =
    val parameterCount = 32
    val depth          = 16
    val parameters     = Vector.tabulate(parameterCount)(index => s"p$index: Base").mkString(", ")
    val nested         = Vector.tabulate(depth)(index => s"${"  " * index}class Level$index(value: Base):")
    val source         =
      s"trait Base\nclass Many($parameters)\n${nested.mkString("\n")}\n${"  " * depth}def result(value: Base): Base = value\n"
    val file           = physical("OwnerTypeScale1.scala", source)
    val classes        = PsiTreeUtil.findChildrenOfType(file, classOf[ScClass]).asScala.toVector.sortBy(_.getTextOffset)
    val many           = classes.head
    val levels         = classes.tail
    val function       = PsiTreeUtil.findChildOfType(file, classOf[ScFunction])
    assertEquals(parameterCount + depth + 1, PsiTreeUtil.findChildrenOfType(file, classOf[ScParameter]).size)
    assertEquals(depth + 1, PsiTreeUtil.findChildrenOfType(file, classOf[ScClass]).size)
    assertEquals(1, PsiTreeUtil.findChildrenOfType(file, classOf[ScFunction]).size)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    Vector(0, parameterCount / 2, parameterCount - 1).foreach: index =>
      val parameter = many.parameters(index)
      assertEquals(s"p$index: Base", parameter.getText)
      assertEquals("Base", parameter.typeElement.get.getText)
      assertTrue(parameter.typeElement.get.getParent.isInstanceOf[ScParameterType])
      assertSame(parameter, parameter.typeElement.get.getParent.getParent)
    Vector(0, depth / 2, depth - 1).foreach: index =>
      assertEquals(s"Level$index", levels(index).name)
      assertEquals(levels(index).getText, levels(index).getTextRange.substring(source))
    levels.indices
      .drop(1)
      .foreach(index =>
        assertSame(levels(index - 1), PsiTreeUtil.getParentOfType(levels(index), classOf[ScClass], true))
      )
    assertSame(levels.last, PsiTreeUtil.getParentOfType(function, classOf[ScClass], true))
    assertEquals("Base", function.returnTypeElement.get.getText)

  private def stubShape(stubs: Iterable[Stub]): Vector[String] = stubs.iterator
    .flatMap(stub =>
      Option(stub.getStubSerializer).map(serializer => s"${stub.getClass.getName}|${serializer.getExternalId}")
    )
    .toVector

  private def indexShape(stubs: Iterable[Stub]): Vector[String] =
    val result = Vector.newBuilder[String]
    val sink   = new IndexSink:
      override def occurrence[Psi <: PsiElement, K](indexKey: StubIndexKey[K, Psi], value: K): Unit =
        result += s"${indexKey.toString}|${value.toString}"
    stubs.foreach(stub =>
      Option(stub.getStubSerializer).foreach(
        _.asInstanceOf[ObjectStubSerializer[Stub, Stub]].indexStub(stub, sink)
      )
    )
    result.result()

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
