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
import org.jetbrains.plugins.scala.lang.psi.api.base.ScEnd
import org.jetbrains.plugins.scala.lang.psi.api.statements.{
  ScEnumCase,
  ScEnumCases,
  ScEnumClassCase,
  ScEnumSingletonCase
}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScClass, ScConstructorOwner, ScEnum, ScTypeDefinition}
import org.jetbrains.plugins.scala.lang.psi.stubs.{ScExtendsBlockStub, ScTemplateDefinitionStub}
import org.junit.Assert.{assertEquals, assertFalse, assertNotNull, assertSame, assertTrue}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*

final class Scala3ParentlessTemplatePsiTest extends Scala3CompatTestCase:

  def testAbsentAndExplicitEmptyOwnersExposeExactNativeAccessors(): Unit =
    val source =
      """class C
        |trait T
        |object O
        |class EC()
        |trait ET()
        |""".stripMargin
    val file   = physical("Case1.scala", source)
    val owners = definitions(file)

    assertEquals(Vector("C", "T", "O", "EC", "ET"), owners.map(_.name))
    owners.foreach: owner =>
      assertEquals(owner.name, owner.nameId.getText)
      assertSame(owner, owner.nameId.getParent)
      assertSame(owner, owner.getNavigationElement)
      assertEquals(owner.getTextRange.getEndOffset, owner.extendsBlock.getTextRange.getStartOffset)
      assertEquals(owner.extendsBlock.getTextRange.getStartOffset, owner.extendsBlock.getTextRange.getEndOffset)
      assertTrue(owner.extendsBlock.templateBody.isEmpty)
    assertConstructor(owners.find(_.name == "C").get.asInstanceOf[ScConstructorOwner], "")
    assertConstructor(owners.find(_.name == "EC").get.asInstanceOf[ScConstructorOwner], "()")
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
      "class C:\n  def value = 1\n",
      "class C:\n  given Int = 1\n"
    ).zipWithIndex.foreach: (source, index) =>
      val pending = myFixture.addFileToProject(s"src/Unsupported${index + 1}.scala", source)
      val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
      assertEquals(source, file.getText)
      file.getChildren
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(source, failure.nonEmpty)
      assertEquals(source, Scala3SyntaxCapabilityStage.Catalog, failure.get.stage)

  def testCopiesPointersAndIncrementalReparsePreservePhysicalTemplatePsi(): Unit =
    val source   = "class Before {}\n"
    val file     = physical("Case5.scala", source)
    val original = PsiTreeUtil.findChildOfType(file, classOf[ScClass])
    val pointer  = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(original)
    val copy     = file.copy()

    assertEquals("Before", PsiTreeUtil.findChildOfType(copy, classOf[ScClass]).name)
    val document = PsiDocumentManager.getInstance(getProject).getDocument(file)
    assertNotNull(document)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          document.replaceString(source.indexOf("Before"), source.indexOf("Before") + 6, "After")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("After", pointer.getElement.name)
    assertEquals("class After {}\n", file.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)

  def testNavigationRenameAndEmptyUsageSearchPreserveOwnerIdentity(): Unit =
    val file  = physical("Case7.scala", "class Before\n")
    val owner = definitions(file).head

    assertSame(owner, owner.getNavigationElement)
    assertTrue(myFixture.findUsages(owner).isEmpty)
    myFixture.renameElement(owner, "After")
    assertEquals("class After\n", file.getText)
    assertEquals("After", definitions(file).head.name)

  def testTemplateStubPreorderAndIndexInputsSurviveAstReload(): Unit =
    val source =
      """package p122c
        |class C
        |trait T
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
      6,
      stubs.count(stub => Option(stub.getStubSerializer).exists(_.getExternalId == "scala.extends block"))
    )
    assertEquals(2, stubs.count(stub => Option(stub.getStubSerializer).exists(_.getExternalId == "scala.ScEnumCases")))
    assertTrue(stubs.collect { case value: ScExtendsBlockStub => value.baseClasses.toVector }.forall(_.nonEmpty))
    val externalIds = stubs.flatMap(stub => Option(stub.getStubSerializer).map(_.getExternalId)).toSet
    assertTrue(TemplatePersistenceSurfaces.ExternalIds.values.toSet.subsetOf(externalIds))
    val output      = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(tree.getRoot, output)
    val restored    = new StubTree(
      SerializationManagerEx.getInstanceEx
        .deserialize(new ByteArrayInputStream(output.toByteArray))
        .asInstanceOf[PsiFileStub[?]]
    )
    val beforeIndex = indexShape(stubs)
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

  private def assertConstructor(owner: ScConstructorOwner, text: String): Unit =
    val constructor = owner.constructor.get
    assertEquals(text, constructor.getText)
    assertSame(constructor, constructor.getNavigationElement)
    assertEquals(constructor.getTextRange, constructor.parameterList.getTextRange)
    assertEquals(0, constructor.parameters.size)
    if text.isEmpty then assertEquals(constructor.getTextRange.getStartOffset, constructor.getTextRange.getEndOffset)
