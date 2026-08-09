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
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScInfixTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScTypeAliasDefinition
import org.jetbrains.plugins.scala.lang.psi.stubs.ScTypeAliasStub
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTrait
import org.junit.Assert.{assertEquals, assertFalse, assertSame, assertTrue}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*

final class Scala3InfixTypePsiTest extends Scala3CompatTestCase:
  private val ExactScalaVersion = ScalaVersion.fromString("3.7.4").get

  override protected def supportedIn(version: ScalaVersion): Boolean = version == ExactScalaVersion

  override protected def defaultVersionOverride: Option[ScalaVersion] = Some(ExactScalaVersion)

  def testUnionIntersectionAndCustomInfixTypesUseNativePhysicalPsi(): Unit =
    val source =
      """trait Left
        |trait Middle
        |trait Right
        |trait Tail
        |trait Or[A, B]
        |type Union = Left | Right
        |type Intersection = Left & Right
        |type Custom = Left Or Right
        |type Mixed = Left | Middle & Right Or Tail
        |""".stripMargin
    val file   = physical("InfixTypes1.scala", source)

    val aliases = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScTypeAliasDefinition])
      .asScala
      .toVector
      .sortBy(_.getTextOffset)
    assertEquals(Vector("Union", "Intersection", "Custom", "Mixed"), aliases.map(_.name))
    assertEquals(
      Vector("Left | Right", "Left & Right", "Left Or Right", "Left | Middle & Right Or Tail"),
      aliases.flatMap(_.aliasedTypeElement).map(_.getText)
    )
    aliases.foreach(alias =>
      assertSame(alias, alias.aliasedTypeElement.get.getParent)
      assertPhysicalContract(alias.aliasedTypeElement.get, source)
    )

    val infix = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScInfixTypeElement])
      .asScala
      .toVector
      .sortBy(element => (element.getTextOffset, -element.getTextLength))
    assertEquals(6, infix.size)
    assertTrue(infix.forall(_.getClass.getName.endsWith("ScInfixTypeElementImpl")))
    infix.foreach: element =>
      assertSame(element, element.left.getParent)
      assertSame(element, element.operation.getParent)
      assertTrue(element.rightOption.nonEmpty)
      assertSame(element, element.rightOption.get.getParent)
      val direct = element.getChildren.toVector
      assertEquals(
        element.left,
        direct.collectFirst { case value: org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement =>
          value
        }.get
      )
      assertEquals(
        element.operation,
        direct.collectFirst { case value: org.jetbrains.plugins.scala.lang.psi.api.base.ScStableCodeReference =>
          value
        }.get
      )
      assertPhysicalContract(element, source)

    val mixed        = infix.find(_.getText == "Left | Middle & Right Or Tail").get
    assertEquals("Left | Middle & Right", mixed.left.getText)
    assertEquals("Or", mixed.operation.getText)
    assertEquals("Tail", mixed.rightOption.get.getText)
    val union        = mixed.left.asInstanceOf[ScInfixTypeElement]
    assertEquals("Left", union.left.getText)
    assertEquals("|", union.operation.getText)
    val intersection = union.rightOption.get.asInstanceOf[ScInfixTypeElement]
    assertEquals("Middle & Right", intersection.getText)
    assertEquals("&", intersection.operation.getText)

  def testInfixTypesMountRecursivelyInAdmittedOwnerPositions(): Unit =
    val source =
      """trait Left
        |trait Right
        |class Box[A]
        |trait Or[A, B]
        |class Parameter(value: Left | Right)
        |trait Owners:
        |  self: Left & Right =>
        |  type Applied = Box[Left | Right]
        |  type Bounded[A <: Left & Right] = A
        |  type Lambda = [A] =>> A | Right
        |  def result(value: Left Or Right): Left | Right
        |  val property: Left & Right
        |""".stripMargin
    val file   = physical("InfixOwners1.scala", source)
    val texts  = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScInfixTypeElement])
      .asScala
      .map(_.getText)
      .toVector
    assertEquals(8, texts.size)
    Vector("Left | Right", "Left & Right", "A | Right", "Left Or Right").foreach(expected =>
      assertTrue(texts.toString, texts.contains(expected))
    )

  def testCopiesPointersEditsAndMalformedRecoveryRemainDeterministic(): Unit =
    val source   =
      """trait Left
        |trait Right
        |trait Or[A, B]
        |type Choice = Left | Right
        |""".stripMargin
    val file     = physical("InfixEdits1.scala", source)
    val original = PsiTreeUtil.findChildOfType(file, classOf[ScInfixTypeElement])
    val pointer  = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(original)
    val copy     = file.copy()
    assertEquals(source, copy.getText)
    assertEquals("Left | Right", PsiTreeUtil.findChildOfType(copy, classOf[ScInfixTypeElement]).getText)
    assertSame(original, original.getNavigationElement)

    val document = PsiDocumentManager.getInstance(getProject).getDocument(file)
    Vector("Left & Right", "Left Or Right", "Left | Right").foreach: replacement =>
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            val start = document.getText.indexOf("Left", document.getText.indexOf("type Choice"))
            document.replaceString(start, document.getTextLength - 1, replacement)
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(document)
      assertEquals(replacement, pointer.getElement.getText)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)

    val malformedPending = myFixture.addFileToProject("src/InfixMalformed1.scala", "type Broken = Left |\n")
    val malformed        = PsiManager.getInstance(getProject).findFile(malformedPending.getVirtualFile)
    assertTrue(PsiTreeUtil.findChildrenOfType(malformed, classOf[ScTypeAliasDefinition]).isEmpty)
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(malformedPending.getVirtualFile, ParserSyntaxSnapshot.digest(malformed.getText))
        .nonEmpty
    )

  def testInfixAliasStubsSerializeReopenAndIndexWithoutAst(): Unit =
    val source      =
      """trait Left
        |trait Right
        |type Union = Left | Right
        |type Intersection = Left & Right
        |""".stripMargin
    val file        = physical("InfixPersistence1.scala", source).asInstanceOf[PsiFileImpl]
    val tree        = file.calcStubTree
    val stubs       = tree.getPlainList.asScala.toVector
    val aliases     = stubs.collect { case stub: ScTypeAliasStub => stub }
    assertEquals(Vector("Union", "Intersection"), aliases.map(_.getName))
    assertEquals(Vector(Some("Left | Right"), Some("Left & Right")), aliases.map(_.typeText))
    val beforeShape = stubShape(stubs)
    val beforeIndex = indexShape(stubs)
    assertEquals(
      Vector(
        "org.jetbrains.plugins.scala.lang.psi.stubs.impl.ScTemplateDefinitionStubImpl|scala.ScTrait",
        "org.jetbrains.plugins.scala.lang.psi.stubs.impl.ScAnnotationsStubImpl|scala.annotations",
        "org.jetbrains.plugins.scala.lang.psi.stubs.impl.ScModifiersStubImpl|scala.modifiers",
        "org.jetbrains.plugins.scala.lang.psi.stubs.impl.ScExtendsBlockStubImpl|scala.extends block",
        "org.jetbrains.plugins.scala.lang.psi.stubs.impl.ScTemplateDefinitionStubImpl|scala.ScTrait",
        "org.jetbrains.plugins.scala.lang.psi.stubs.impl.ScAnnotationsStubImpl|scala.annotations",
        "org.jetbrains.plugins.scala.lang.psi.stubs.impl.ScModifiersStubImpl|scala.modifiers",
        "org.jetbrains.plugins.scala.lang.psi.stubs.impl.ScExtendsBlockStubImpl|scala.extends block",
        "org.jetbrains.plugins.scala.lang.psi.stubs.impl.ScTypeAliasStubImpl|scala.type alias definition",
        "org.jetbrains.plugins.scala.lang.psi.stubs.impl.ScTypeAliasStubImpl|scala.type alias definition"
      ),
      beforeShape
    )
    assertEquals(
      Vector(
        "java.class.shortname|Left",
        "sc.all.class.names|Left",
        "sc.class.shortName|Left",
        "sc.all.class.names|Left$class",
        "java.class.fqn|Left",
        "sc.java.class.name.in.package|",
        "sc.class.fqn|Left",
        "sc.class.name.in.package|",
        "sc.super.class.name|AnyRef",
        "java.class.shortname|Right",
        "sc.all.class.names|Right",
        "sc.class.shortName|Right",
        "sc.all.class.names|Right$class",
        "java.class.fqn|Right",
        "sc.java.class.name.in.package|",
        "sc.class.fqn|Right",
        "sc.class.name.in.package|",
        "sc.super.class.name|AnyRef",
        "sc.type.alias.name|Union",
        "sc.stable.alias.name|Union",
        "sc.top.level.alias.by.package.key|",
        "sc.aliased.class.name||",
        "sc.type.alias.name|Intersection",
        "sc.stable.alias.name|Intersection",
        "sc.top.level.alias.by.package.key|",
        "sc.aliased.class.name|&"
      ),
      beforeIndex
    )

    val output   = ByteArrayOutputStream()
    SerializationManagerEx.getInstanceEx.serialize(tree.getRoot, output)
    val restored = StubTree(
      SerializationManagerEx.getInstanceEx
        .deserialize(ByteArrayInputStream(output.toByteArray))
        .asInstanceOf[PsiFileStub[?]]
    )
    assertEquals(beforeShape, stubShape(restored.getPlainList.asScala))
    assertEquals(beforeIndex, indexShape(restored.getPlainList.asScala))
    file.setTreeElementPointer(null)
    assertEquals(null, file.getTreeElement)
    assertEquals(beforeShape, stubShape(file.getStubTree.getPlainList.asScala))

  def testLaterTypeFamiliesRemainFailClosed(): Unit =
    Vector(
      "type Deferred = Left { type Member }\n"
    ).zipWithIndex.foreach: (source, index) =>
      val pending = myFixture.addFileToProject(s"src/InfixBoundary$index.scala", source)
      val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeAliasDefinition]).isEmpty)
      assertTrue(
        source,
        Scala3SyntaxCapabilityService
          .get(getProject)
          .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
          .nonEmpty
      )

    val modifiedOwnerSource  = "infix trait Deferred[A, B]\n"
    val modifiedOwnerPending = myFixture.addFileToProject("src/InfixModifiedOwner.scala", modifiedOwnerSource)
    val modifiedOwnerFile    = PsiManager.getInstance(getProject).findFile(modifiedOwnerPending.getVirtualFile)
    assertTrue(PsiTreeUtil.findChildrenOfType(modifiedOwnerFile, classOf[ScTrait]).isEmpty)
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(modifiedOwnerPending.getVirtualFile, ParserSyntaxSnapshot.digest(modifiedOwnerSource))
        .nonEmpty
    )

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
    assertFalse(element.getText, children.isEmpty)
    assertEquals(element.getText, children.map(_.getText).mkString)
    children.foreach(child => assertSame(element.getNode, child.getTreeParent))
