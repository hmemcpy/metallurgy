package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiErrorElement, PsiManager, SmartPointerManager}
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
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.psi.api.base.{ScAnnotations, ScEnd, ScModifierList}
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.{
  ScEnumCase,
  ScEnumCases,
  ScEnumClassCase,
  ScEnumSingletonCase,
  ScFunctionDefinition,
  ScPatternDefinition,
  ScTypeAliasDeclaration,
  ScVariableDefinition
}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.{
  ScClassParameter,
  ScParameter,
  ScParameters,
  ScTypeParam,
  ScTypeParamClause
}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScClass, ScConstructorOwner, ScEnum, ScTypeDefinition}
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.MetallurgyExpressionPayload
import org.jetbrains.plugins.scala.lang.psi.stubs.{ScExtendsBlockStub, ScTemplateDefinitionStub}
import org.junit.Assert.{assertEquals, assertFalse, assertNotNull, assertSame, assertTrue}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*

final class Scala3ParentlessTemplatePsiTest extends Scala3CompatTestCase:

  def testUntypedDefinitionShellsExposeExactNativePsiAndGenericExpressionIslands(): Unit =
    val source    =
      """def f = List(1).head
        |val x = 1
        |var y = f
        |trait T:
        |  type A
        |  def nested = (x, y)
        |""".stripMargin
    val file      = physical("Definitions1.scala", source)
    val functions = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScFunctionDefinition])
      .asScala
      .toVector
      .sortBy(_.getTextRange.getStartOffset)
    val values    = PsiTreeUtil.findChildrenOfType(file, classOf[ScPatternDefinition]).asScala.toVector
    val variables = PsiTreeUtil.findChildrenOfType(file, classOf[ScVariableDefinition]).asScala.toVector
    val aliases   = PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeAliasDeclaration]).asScala.toVector
    val payloads  = PsiTreeUtil
      .findChildrenOfType(file, classOf[MetallurgyExpressionPayload])
      .asScala
      .toVector
      .sortBy(value => (value.getTextRange.getStartOffset, -value.getTextLength))

    assertEquals(Vector("f", "nested"), functions.map(_.name))
    assertSame(file, functions.head.getParent)
    assertEquals(Some("List(1).head"), functions.head.body.map(_.getText))
    assertEquals(Some("(x, y)"), functions.last.body.map(_.getText))
    assertTrue(functions.forall(_.hasAssign))
    assertEquals(Vector("x"), values.flatMap(_.bindings.map(_.name)))
    assertEquals(Vector("y"), variables.flatMap(_.bindings.map(_.name)))
    assertEquals(Vector("1"), values.flatMap(_.expr.map(_.getText)))
    assertEquals(Vector("f"), variables.flatMap(_.expr.map(_.getText)))
    assertEquals(Vector("A"), aliases.map(_.name))
    assertTrue(aliases.forall(alias => alias.lowerTypeElement.isEmpty && alias.upperTypeElement.isEmpty))
    assertEquals(
      Vector("List(1).head", "List(1)", "List", "1", "1", "f", "(x, y)", "x", "y"),
      payloads.map(_.getText)
    )
    assertTrue(
      payloads.forall(payload =>
        PsiTreeUtil
          .findChildrenOfType(payload, classOf[ScExpression])
          .asScala
          .forall(
            _.isInstanceOf[MetallurgyExpressionPayload]
          )
      )
    )
    assertEquals("List(1)", payloads.find(_.getText == "List(1)").get.copy().getText)

    val tree        = file.asInstanceOf[PsiFileImpl].calcStubTree
    val stubs       = tree.getPlainList.asScala.toVector
    val externalIds = stubs.flatMap(stub => Option(stub.getStubSerializer).map(_.getExternalId)).toSet
    assertTrue(
      externalIds.toVector.sorted.mkString(","),
      Set(
        "scala.function definition",
        "scala.value definition",
        "scala.variable definition",
        "scala.pattern list",
        "scala.reference pattern",
        "scala.type alias declaration"
      ).subsetOf(externalIds)
    )
    val beforeIndex = indexShape(stubs)
    Vector(
      "sc.method.name|f",
      "sc.method.name|nested",
      "sc.property.name|x",
      "sc.property.name|y",
      "sc.type.alias.name|A"
    ).foreach(entry => assertEquals(entry, 1, beforeIndex.count(_ == entry)))
    val output      = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(tree.getRoot, output)
    val restored    = new StubTree(
      SerializationManagerEx.getInstanceEx
        .deserialize(new ByteArrayInputStream(output.toByteArray))
        .asInstanceOf[PsiFileStub[?]]
    )
    assertEquals(stubShape(stubs), stubShape(restored.getPlainList.asScala))
    assertEquals(beforeIndex, indexShape(restored.getPlainList.asScala))

  def testBlockLocalDefinitionsAreOutputFreeWhileTheirExactRhsPayloadsRemainPhysical(): Unit =
    val source   =
      """def block =
        |  val local = List(1)
        |  var mutable = local
        |  (local, mutable)
        |""".stripMargin
    val file     = physical("Definitions2.scala", source)
    val payloads = PsiTreeUtil
      .findChildrenOfType(file, classOf[MetallurgyExpressionPayload])
      .asScala
      .toVector
      .sortBy(value => (value.getTextRange.getStartOffset, -value.getTextLength))

    assertEquals(
      Vector("block"),
      PsiTreeUtil.findChildrenOfType(file, classOf[ScFunctionDefinition]).asScala.map(_.name).toVector
    )
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScPatternDefinition]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScVariableDefinition]).isEmpty)
    assertTrue(payloads.exists(_.getText == "List(1)"))
    assertTrue(payloads.exists(_.getText == "local"))
    assertTrue(payloads.exists(_.getText == "(local, mutable)"))
    assertEquals(1, payloads.count(_.getText.startsWith("val ")))
    assertTrue(payloads.exists(_.getText == source.substring(source.indexOf("val local"), source.length - 1)))
    assertFalse(payloads.exists(_.getText == "val local = List(1)"))
    assertFalse(payloads.exists(_.getText == "var mutable = local"))
    assertTrue(payloads.forall(payload => payload.getTextRange.getEndOffset <= source.length))

  def testExactParserErrorProducesOnlyNeutralPsiAtItsExactZeroWidthRange(): Unit =
    val source = ")\nval result = 1\n"
    val added  = myFixture.addFileToProject("src/RecoveredDefinition.scala", source)
    val file   = PsiManager.getInstance(getProject).findFile(added.getVirtualFile)
    val errors = PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).asScala.toVector

    assertEquals(source, file.getText)
    assertEquals(1, errors.size)
    assertEquals("eof expected, but ')' found", errors.head.getErrorDescription)
    assertEquals((0, 0), (errors.head.getTextRange.getStartOffset, errors.head.getTextRange.getEndOffset))
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScExpression]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScFunctionDefinition]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScPatternDefinition]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScVariableDefinition]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeAliasDeclaration]).isEmpty)

  def testClassUnboundedTypeParametersExposeExactNativePsi(): Unit =
    val source = "class C[A, +B, -C]()()\n"
    val file   = physical("TypeParams1.scala", source)
    val owner  = PsiTreeUtil.findChildOfType(file, classOf[ScClass])
    val clause = PsiTreeUtil.findChildOfType(file, classOf[ScTypeParamClause])
    val params = PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeParam]).asScala.toVector

    assertEquals("[A, +B, -C]", clause.getText)
    assertSame(owner, clause.getParent)
    assertEquals(Vector("A", "+B", "-C"), params.map(_.getText))
    assertEquals(Vector("A", "B", "C"), params.map(_.name))
    assertEquals(Vector("invariant", "covariant", "contravariant"), params.map(_.variance.toString))
    assertEquals(
      Vector((8, 9), (11, 13), (15, 17)),
      params.map(value => (value.getTextRange.getStartOffset, value.getTextRange.getEndOffset))
    )
    assertEquals((7, 18), (clause.getTextRange.getStartOffset, clause.getTextRange.getEndOffset))
    assertEquals(Vector(0, 1, 2), params.map(clause.getTypeParameterIndex))
    assertTrue(params.forall(param => param.owner == owner && param.getParent == clause))
    assertTrue(params.forall(param => param.lowerTypeElement.isEmpty && param.upperTypeElement.isEmpty))
    assertEquals(Vector("[", "A", ",", "+", "B", ",", "-", "C", "]"), significantLeafTexts(clause))
    assertEquals(Vector("A", "+", "B", "-", "C"), params.flatMap(significantLeafTexts))
    assertEquals(Vector("()", "()"), owner.constructor.get.parameterList.clauses.map(_.getText).toVector)
    assertEquals(
      Vector("(", ")", "(", ")"),
      owner.constructor.get.parameterList.clauses.flatMap(significantLeafTexts).toVector
    )
    assertEquals(
      Vector("ScAnnotations", "ScModifierList", "ScParameters"),
      owner.constructor.get.getChildren.toVector.map:
        case _: ScAnnotations  => "ScAnnotations"
        case _: ScModifierList => "ScModifierList"
        case _: ScParameters   => "ScParameters"
        case other             => other.getClass.getName
    )
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScParameter]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScClassParameter]).isEmpty)

  def testNestedClassUnboundedTypeParametersExposeEquivalentNativePsi(): Unit =
    val file    = physical("TypeParams2.scala", "class Outer[A]()():\n  class Inner[+B, -C]()()\n")
    val classes =
      PsiTreeUtil.findChildrenOfType(file, classOf[ScClass]).asScala.toVector.sortBy(_.getTextRange.getStartOffset)
    val clauses = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScTypeParamClause])
      .asScala
      .toVector
      .sortBy(_.getTextRange.getStartOffset)

    assertEquals(Vector("Outer", "Inner"), classes.map(_.name))
    assertEquals(Vector("[A]", "[+B, -C]"), clauses.map(_.getText))
    assertEquals(Vector(Vector("A"), Vector("B", "C")), clauses.map(_.typeParameters.map(_.name).toVector))
    assertEquals(
      Vector("()", "()", "()", "()"),
      classes.flatMap(_.constructor.get.parameterList.clauses.map(_.getText))
    )

  def testEmptyConstructorClausesPreserveInteriorTrivia(): Unit =
    val source      = "class Spaced[A]( /* first */ )(\n  )\n"
    val file        = physical("TypeParams3.scala", source)
    val owner       = PsiTreeUtil.findChildOfType(file, classOf[ScClass])
    val constructor = owner.constructor.get
    val clauses     = constructor.parameterList.clauses.toVector

    assertEquals("( /* first */ )(\n  )", constructor.getText)
    assertEquals(Vector("( /* first */ )", "(\n  )"), clauses.map(_.getText))
    assertEquals(Vector("(", "/* first */", ")"), significantLeafTexts(clauses.head))
    assertEquals(Vector("(", ")"), significantLeafTexts(clauses.last))
    assertTrue(clauses.forall(_.hasParenthesis))
    assertTrue(clauses.forall(_.parameters.isEmpty))
    clauses.foreach(clause => assertSame(constructor, clause.owner))

  def testAbsentAndExplicitEmptyOwnersExposeExactNativeAccessors(): Unit =
    val source =
      """class C
        |trait T
        |object O
        |class EC()
        |class RC()()
        |trait ET()
        |""".stripMargin
    val file   = physical("Case1.scala", source)
    val owners = definitions(file)

    assertEquals(Vector("C", "T", "O", "EC", "RC", "ET"), owners.map(_.name))
    owners.foreach: owner =>
      assertEquals(owner.name, owner.nameId.getText)
      assertSame(owner, owner.nameId.getParent)
      assertSame(owner, owner.getNavigationElement)
      assertEquals(owner.getTextRange.getEndOffset, owner.extendsBlock.getTextRange.getStartOffset)
      assertEquals(owner.extendsBlock.getTextRange.getStartOffset, owner.extendsBlock.getTextRange.getEndOffset)
      assertTrue(owner.extendsBlock.templateBody.isEmpty)
    assertConstructor(owners.find(_.name == "C").get.asInstanceOf[ScConstructorOwner], "")
    assertConstructor(owners.find(_.name == "EC").get.asInstanceOf[ScConstructorOwner], "()")
    val repeated = owners.find(_.name == "RC").get.asInstanceOf[ScConstructorOwner].constructor.get
    assertEquals(Vector("()", "()"), repeated.parameterList.clauses.map(_.getText).toVector)
    assertConstructor(owners.find(_.name == "ET").get.asInstanceOf[ScConstructorOwner], "()")
    assertTrue(owners.find(_.name == "T").get.asInstanceOf[ScConstructorOwner].constructor.isEmpty)

  def testBracedEmptyAndNestedBodiesHaveExactExtendsAndBodyRanges(): Unit =
    val source =
      """class C {}
        |trait T {}
        |object O {}
        |class Outer {
        |  trait Nested
        |}
        |""".stripMargin
    val file   = physical("Case2.scala", source)
    val owners = definitions(file)

    Vector("C", "T", "O").foreach: name =>
      val owner = owners.find(_.name == name).get
      assertEquals("{}", owner.extendsBlock.getText)
      assertEquals("{}", owner.extendsBlock.templateBody.get.getText)
      assertTrue(owner.extendsBlock.templateBody.get.isEmpty)
    val outer = owners.find(_.name == "Outer").get
    assertEquals("{\n  trait Nested\n}", outer.extendsBlock.getText)
    assertEquals(outer.extendsBlock.getTextRange, outer.extendsBlock.templateBody.get.getTextRange)
    assertFalse(outer.extendsBlock.templateBody.get.isEmpty)
    assertEquals("Nested", owners.find(_.name == "Nested").get.name)

  def testColonBodiesWithAndWithoutEndMarkersRetainExactOwnership(): Unit =
    val source =
      """class Empty:
        |end Empty
        |class Outer:
        |  trait Nested
        |class After
        |""".stripMargin
    val file   = physical("Case3.scala", source)
    val owners = definitions(file)
    val empty  = owners.find(_.name == "Empty").get
    val outer  = owners.find(_.name == "Outer").get

    assertEquals(":\nend Empty", empty.extendsBlock.getText)
    assertEquals(empty.extendsBlock.getTextRange, empty.extendsBlock.templateBody.get.getTextRange)
    assertEquals("end Empty", PsiTreeUtil.findChildOfType(empty, classOf[ScEnd]).getText)
    assertEquals(":\n  trait Nested", outer.extendsBlock.getText)
    assertEquals(outer.extendsBlock.getTextRange, outer.extendsBlock.templateBody.get.getTextRange)
    assertEquals(0, PsiTreeUtil.findChildrenOfType(outer, classOf[ScEnd]).size)

  def testColonAndBracedEnumsExposePerCaseWrappersAndConstructors(): Unit =
    val source =
      """enum E:
        |  case A
        |  case B()
        |end E
        |enum F { case C; case D() }
        |""".stripMargin
    val file   = physical("Case4.scala", source)
    val enums  = PsiTreeUtil.findChildrenOfType(file, classOf[ScEnum]).asScala.toVector.sortBy(_.name)
    val cases  = PsiTreeUtil.findChildrenOfType(file, classOf[ScEnumCase]).asScala.toVector.sortBy(_.name)

    assertEquals(Vector("E", "F"), enums.map(_.name))
    assertEquals(Vector("A", "B", "C", "D"), cases.map(_.name))
    assertEquals(4, PsiTreeUtil.findChildrenOfType(file, classOf[ScEnumCases]).size)
    assertEquals(Vector("A", "C"), cases.collect { case value: ScEnumSingletonCase => value.name })
    assertEquals(Vector("B", "D"), cases.collect { case value: ScEnumClassCase => value.name })
    cases.foreach: enumCase =>
      assertSame(enumCase.enumCases, enumCase.getParent)
      assertSame(enumCase, enumCase.getNavigationElement)
      assertEquals(enumCase.name, enumCase.nameId.getText)
    cases.collect { case value: ScEnumClassCase => value }.foreach(value => assertConstructor(value, "()"))

  def testUnsupportedTemplateShapesFailClosedAtTheCatalog(): Unit =
    Vector(
      "class Parent\nclass Child extends Parent\n",
      "class C derives CanEqual\n",
      "class C(x: Int)\n",
      "class C:\n  self: C =>\n",
      "enum E:\n  case A, B\n",
      "class C:\n  given Int = 1\n",
      "class C(val value: Int)\n",
      "class C(value: Int)\n",
      "class C[A <: Any]\n",
      "class C[A: Ordering]\n",
      "class C[A <% Any]\n",
      "class C[A = Any]\n",
      "class C[[A] =>> List[A]]\n",
      "def method[A](value: A): A = value\n",
      "type Alias[A] = A\n"
    ).zipWithIndex.foreach: (source, index) =>
      val pending       = myFixture.addFileToProject(s"src/Unsupported${index + 1}.scala", source)
      val file          = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
      assertEquals(source, file.getText)
      file.getChildren
      val failure       = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(source, failure.nonEmpty)
      val expectedStage =
        if Set("class C[A = Any]\n", "class C[[A] =>> List[A]]\n")(source) then Scala3SyntaxCapabilityStage.Planner
        else Scala3SyntaxCapabilityStage.Catalog
      assertEquals(source, expectedStage, failure.get.stage)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeParamClause]).isEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeParam]).isEmpty)

  def testCopiesPointersAndIncrementalReparsePreservePhysicalTemplatePsi(): Unit =
    val source   = "class Before[A]()() {}\n"
    val file     = physical("Case5.scala", source)
    val original = PsiTreeUtil.findChildOfType(file, classOf[ScClass])
    val pointer  = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(original)
    val copy     = file.copy()

    assertEquals("[A]", PsiTreeUtil.findChildOfType(copy, classOf[ScTypeParamClause]).getText)
    val parameterPointer = SmartPointerManager
      .getInstance(getProject)
      .createSmartPsiElementPointer(
        PsiTreeUtil.findChildOfType(file, classOf[ScTypeParam])
      )
    val document         = PsiDocumentManager.getInstance(getProject).getDocument(file)
    assertNotNull(document)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          document.replaceString(source.indexOf("Before"), source.indexOf("Before") + 6, "After")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("After", pointer.getElement.name)
    assertEquals("A", parameterPointer.getElement.name)
    assertEquals("class After[A]()() {}\n", file.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)

  def testNavigationRenameAndEmptyUsageSearchPreserveOwnerIdentity(): Unit =
    val file        = physical("Case7.scala", "class Before[Element]\n")
    val owner       = definitions(file).head
    val param       = PsiTreeUtil.findChildOfType(file, classOf[ScTypeParam])
    val constructor = owner.asInstanceOf[ScConstructorOwner].constructor.get

    assertSame(owner, owner.getNavigationElement)
    assertTrue(myFixture.findUsages(owner).isEmpty)
    assertSame(param, param.getNavigationElement)
    assertTrue(myFixture.findUsages(param).isEmpty)
    assertEquals("", constructor.getText)
    assertEquals(constructor.getTextRange, constructor.parameterList.getTextRange)
    assertEquals(constructor.getTextRange.getStartOffset, constructor.getTextRange.getEndOffset)
    assertTrue(constructor.parameterList.clauses.isEmpty)
    myFixture.renameElement(param, "Renamed")
    myFixture.renameElement(owner, "After")
    assertEquals("class After[Renamed]\n", file.getText)
    assertEquals("After", definitions(file).head.name)

  def testTemplateStubPreorderAndIndexInputsSurviveAstReload(): Unit =
    val source =
      """package p122c
        |class C[A, +B, -C]()()
        |trait T[T]()()
        |object O
        |enum E:
        |  case A
        |  case B()
        |end E
        |""".stripMargin
    val file   = physical("Case6.scala", source).asInstanceOf[PsiFileImpl]
    val tree   = file.calcStubTree
    val stubs  = tree.getPlainList.asScala.toVector

    assertEquals(6, stubs.count(_.isInstanceOf[ScTemplateDefinitionStub[?]]))
    assertEquals(
      2,
      stubs.count(stub => Option(stub.getStubSerializer).exists(_.getExternalId == "scala.type parameter clause"))
    )
    assertEquals(
      4,
      stubs.count(stub => Option(stub.getStubSerializer).exists(_.getExternalId == "scala.type parameter"))
    )
    assertEquals(
      6,
      stubs.count(stub => Option(stub.getStubSerializer).exists(_.getExternalId == "scala.extends block"))
    )
    assertEquals(2, stubs.count(stub => Option(stub.getStubSerializer).exists(_.getExternalId == "scala.ScEnumCases")))
    assertTrue(stubs.collect { case value: ScExtendsBlockStub => value.baseClasses.toVector }.forall(_.nonEmpty))
    stubs
      .filter(stub => Option(stub.getStubSerializer).exists(_.getExternalId == "scala.primary constructor"))
      .foreach: constructor =>
        assertEquals(
          Vector("scala.annotations", "scala.modifiers", "scala.parameter clauses"),
          constructor.getChildrenStubs.asScala.toVector.flatMap(child =>
            Option(child.getStubSerializer).map(_.getExternalId)
          )
        )
    val externalIds          = stubs.flatMap(stub => Option(stub.getStubSerializer).map(_.getExternalId)).toSet
    assertTrue(TemplatePersistenceSurfaces.ExternalIds.values.toSet.subsetOf(externalIds))
    val output               = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(tree.getRoot, output)
    val restored             = new StubTree(
      SerializationManagerEx.getInstanceEx
        .deserialize(new ByteArrayInputStream(output.toByteArray))
        .asInstanceOf[PsiFileStub[?]]
    )
    val beforeIndex          = indexShape(stubs)
    val typeParameterStubIds = Set("scala.type parameter clause", "scala.type parameter")
    val typeParameterStubs   = stubs.filter(stub =>
      Option(stub.getStubSerializer).exists(serializer => typeParameterStubIds(serializer.getExternalId))
    )
    assertTrue(indexShape(typeParameterStubs).isEmpty)
    assertEquals(stubShape(stubs), stubShape(restored.getPlainList.asScala))
    assertEquals(beforeIndex, indexShape(restored.getPlainList.asScala))
    assertTrue(Vector("C", "T", "O", "E", "A", "B").forall(name => beforeIndex.contains(s"sc.class.shortName|$name")))
    assertTrue(
      Set(
        "sc.class.shortName|C",
        "sc.class.fqn|p122c.C",
        "sc.class.name.in.package|p122c",
        "java.class.shortname|C",
        "java.class.fqn|p122c.C",
        "sc.not.visible.in.java.class.shortName|O",
        "sc.all.class.names|C",
        "sc.java.class.name.in.package|p122c",
        "sc.super.class.name|AnyRef"
      ).subsetOf(beforeIndex.toSet)
    )
    file.setTreeElementPointer(null)
    assertEquals(null, file.getTreeElement)
    assertEquals(6, file.getStubTree.getPlainList.asScala.count(_.isInstanceOf[ScTemplateDefinitionStub[?]]))
    assertEquals(stubShape(stubs), stubShape(file.getStubTree.getPlainList.asScala))

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

  private def physical(name: String, source: String): com.intellij.psi.PsiFile =
    val pending = myFixture.addFileToProject(s"src/$name", source)
    val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    val errors  = PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement])
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(failure.toString, failure.isEmpty)
    assertEquals(source, file.getText)
    assertTrue(errors.isEmpty)
    file

  private def definitions(file: com.intellij.psi.PsiFile): Vector[ScTypeDefinition] =
    PsiTreeUtil
      .findChildrenOfType(file, classOf[ScTypeDefinition])
      .asScala
      .toVector
      .sortBy(_.getTextRange.getStartOffset)

  private def significantLeafTexts(element: PsiElement): Vector[String] =
    PsiTreeUtil
      .collectElements(element, child => child.getFirstChild == null && !child.getText.trim.isEmpty)
      .toVector
      .map(_.getText)

  private def assertConstructor(owner: ScConstructorOwner, text: String): Unit =
    val constructor = owner.constructor.get
    assertEquals(text, constructor.getText)
    assertSame(constructor, constructor.getNavigationElement)
    assertEquals(constructor.getTextRange, constructor.parameterList.getTextRange)
    assertEquals(0, constructor.parameters.size)
    if text.isEmpty then assertEquals(constructor.getTextRange.getStartOffset, constructor.getTextRange.getEndOffset)
