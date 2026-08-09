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
import org.jetbrains.plugins.scala.lang.psi.api.base.ScAnnotations
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{ScAnnotTypeElement, ScCompoundTypeElement}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{
  ScFunctionDeclaration,
  ScTypeAlias,
  ScTypeAliasDefinition,
  ScValueDeclaration,
  ScVariableDeclaration
}
import org.jetbrains.plugins.scala.lang.psi.stubs.ScTypeAliasStub
import org.junit.Assert.{assertEquals, assertFalse, assertSame, assertTrue}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*

final class Scala3RefinementAnnotatedTypePsiTest extends Scala3CompatTestCase:
  private val ExactScalaVersion = ScalaVersion.fromString("3.7.4").get

  override protected def supportedIn(version: ScalaVersion): Boolean = version == ExactScalaVersion

  override protected def defaultVersionOverride: Option[ScalaVersion] = Some(ExactScalaVersion)

  def testBracedRefinementAndAnnotatedTypesUseNativePhysicalPsi(): Unit =
    val source =
      """type Structural = AnyRef { type Elem = String; val value: Elem; var next: Elem; def current: Elem }
        |type Marked = List[String @unchecked] @unchecked
        |""".stripMargin
    val file   = physical("RefinementAnnotated1.scala", source)

    val compound   = PsiTreeUtil.findChildOfType(file, classOf[ScCompoundTypeElement])
    val refinement = compound.refinement.get
    assertEquals(Vector("AnyRef"), compound.components.map(_.getText).toVector)
    assertSame(compound, compound.components.head.getParent)
    assertSame(compound, refinement.getParent)
    assertEquals("{ type Elem = String; val value: Elem; var next: Elem; def current: Elem }", refinement.getText)
    assertEquals(Vector("Elem"), refinement.types.map(_.name).toVector)
    assertEquals(
      Vector("value", "next", "current"),
      refinement.holders.flatMap(_.declaredElements).map(_.getName).toVector
    )
    assertTrue(refinement.types.head.isInstanceOf[ScTypeAliasDefinition])
    assertTrue(refinement.holders.exists(_.isInstanceOf[ScValueDeclaration]))
    assertTrue(refinement.holders.exists(_.isInstanceOf[ScVariableDeclaration]))
    assertTrue(refinement.holders.exists(_.isInstanceOf[ScFunctionDeclaration]))

    val annotated            = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScAnnotTypeElement])
      .asScala
      .toVector
      .sortBy(_.getTextOffset)
    assertEquals(Vector("List[String @unchecked] @unchecked", "String @unchecked"), annotated.map(_.getText))
    assertEquals(Vector("List[String @unchecked]", "String"), annotated.map(_.typeElement.getText))
    annotated.foreach(value => assertSame(value, value.typeElement.getParent))
    val annotationContainers =
      annotated.map(value => value.getChildren.collectFirst { case child: ScAnnotations => child }.get)
    assertEquals(Vector("@unchecked", "@unchecked"), annotationContainers.map(_.getText))
    annotationContainers.zip(annotated).foreach((annotations, owner) => assertSame(owner, annotations.getParent))
    assertEquals(
      Vector("@unchecked", "@unchecked"),
      annotationContainers.flatMap(_.getAnnotations).map(_.getText)
    )

    assertDirectChildren(compound, source)
    assertDirectChildren(refinement, source)
    annotated.foreach(assertDirectChildren(_, source))

  def testLayoutParentlessAndRecursiveOwnerContextsRemainExact(): Unit =
    val source =
      """class Box[A]
        |type Layout = AnyRef:
        |  type Elem
        |  def value: Elem
        |type Parentless = { type Elem = String }
        |type Nested = Box[(AnyRef { type Elem = String }) @unchecked]
        |trait Owners[A <: AnyRef { type Elem = String }]:
        |  type Bound = A @unchecked
        |  def result: AnyRef { val value: A }
        |  val property: A @unchecked
        |""".stripMargin
    val file   = physical("RefinementOwners1.scala", source)

    val compounds = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScCompoundTypeElement])
      .asScala
      .toVector
      .sortBy(_.getTextOffset)
    assertEquals(5, compounds.size)
    assertEquals(Vector(1, 0, 1, 1, 1), compounds.map(_.components.size))
    assertEquals(
      Vector(2, 1, 1, 1, 1),
      compounds.map(value => value.refinement.get.holders.size + value.refinement.get.types.size)
    )
    assertTrue(compounds.head.getText.contains("AnyRef:\n  type Elem\n  def value: Elem"))
    assertEquals("{ type Elem = String }", compounds(1).getText)
    compounds.foreach(value => assertDirectChildren(value, source))

    val annotated = PsiTreeUtil.findChildrenOfType(file, classOf[ScAnnotTypeElement]).asScala.toVector
    assertEquals(3, annotated.size)
    assertTrue(annotated.exists(_.getText == "(AnyRef { type Elem = String }) @unchecked"))
    assertEquals(2, annotated.count(_.getText == "A @unchecked"))

  def testCopiesPointersEditsAndMalformedRecoveryRemainDeterministic(): Unit =
    val source     = "type Structural = AnyRef { type Elem = String; def value: Elem }\n"
    val file       = physical("RefinementEdits1.scala", source)
    val compound   = PsiTreeUtil.findChildOfType(file, classOf[ScCompoundTypeElement])
    val pointer    = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(compound)
    val copy       = file.copy()
    val copiedType = PsiTreeUtil.findChildOfType(copy, classOf[ScCompoundTypeElement])
    assertEquals(source, copy.getText)
    assertEquals(compound.getText, copiedType.getText)
    assertSame(compound, compound.getNavigationElement)

    val document = PsiDocumentManager.getInstance(getProject).getDocument(file)
    val start    = document.getText.indexOf("String")
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(start, start + "String".length, "List[Int] @unchecked")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertTrue(pointer.getElement.getText.contains("type Elem = List[Int] @unchecked"))
    assertEquals(1, PsiTreeUtil.findChildrenOfType(file, classOf[ScAnnotTypeElement]).size)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)

    val malformedSource  = "type Broken = AnyRef { type Elem =\n"
    val malformedPending = myFixture.addFileToProject("src/RefinementMalformed1.scala", malformedSource)
    val malformed        = PsiManager.getInstance(getProject).findFile(malformedPending.getVirtualFile)
    assertTrue(PsiTreeUtil.findChildrenOfType(malformed, classOf[ScTypeAliasDefinition]).isEmpty)
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(malformedPending.getVirtualFile, ParserSyntaxSnapshot.digest(malformedSource))
        .nonEmpty
    )

  def testAliasStubsSerializeAndReopenWithoutTypeRoleStubs(): Unit =
    val source      =
      """type Structural = AnyRef { type Elem = String; def value: Elem }
        |type Marked = List[String @unchecked]
        |""".stripMargin
    val file        = physical("RefinementPersistence1.scala", source).asInstanceOf[PsiFileImpl]
    val tree        = file.calcStubTree
    val stubs       = tree.getPlainList.asScala.toVector
    val aliases     = stubs.collect { case stub: ScTypeAliasStub => stub }
    assertEquals(Vector("Structural", "Elem", "Marked"), aliases.map(_.getName))
    assertEquals(
      Vector(Some("AnyRef { type Elem = String; def value: Elem }"), Some("String"), Some("List[String @unchecked]")),
      aliases.map(_.typeText)
    )
    assertFalse(
      stubs.exists(stub =>
        stub.getClass.getName.contains("CompoundType") || stub.getClass.getName.contains("AnnotType")
      )
    )
    val beforeIndex = indexShape(stubs)
    assertEquals(
      Vector(
        "sc.type.alias.name|Structural",
        "sc.stable.alias.name|Structural",
        "sc.top.level.alias.by.package.key|",
        "sc.type.alias.name|Elem",
        "sc.aliased.class.name|String",
        "sc.method.name|value",
        "sc.type.alias.name|Marked",
        "sc.stable.alias.name|Marked",
        "sc.top.level.alias.by.package.key|",
        "sc.aliased.class.name|List",
        "sc.annotated.member.name|unchecked"
      ),
      beforeIndex
    )

    val output          = ByteArrayOutputStream()
    SerializationManagerEx.getInstanceEx.serialize(tree.getRoot, output)
    val restored        = StubTree(
      SerializationManagerEx.getInstanceEx
        .deserialize(ByteArrayInputStream(output.toByteArray))
        .asInstanceOf[PsiFileStub[?]]
    )
    val restoredAliases = restored.getPlainList.asScala.collect { case stub: ScTypeAliasStub => stub }.toVector
    assertEquals(
      aliases.map(stub => stub.getName -> stub.typeText),
      restoredAliases.map(stub => stub.getName -> stub.typeText)
    )
    assertEquals(beforeIndex, indexShape(restored.getPlainList.asScala))
    file.setTreeElementPointer(null)
    assertEquals(null, file.getTreeElement)
    assertEquals(
      restoredAliases.map(stub => stub.getName -> stub.typeText),
      file.getStubTree.getPlainList.asScala
        .collect { case stub: ScTypeAliasStub => stub }
        .map(stub => stub.getName -> stub.typeText)
        .toVector
    )
    assertEquals(beforeIndex, indexShape(file.getStubTree.getPlainList.asScala))

  def testUnsupportedRefinementMembersAndExcludedTypeFamiliesFailClosed(): Unit =
    Vector(
      "type Unsupported = AnyRef { def value: Int = 1 }\n",
      "type Unsupported = AnyRef { class Nested }\n",
      "type Unsupported = AnyRef { opaque type Hidden = String }\n",
      "type Unsupported = AnyRef { private type Hidden = String }\n",
      "type Unsupported = AnyRef { type Lambda = [X] =>> X }\n",
      "type Capture = String^\n",
      "val term = new Object { val value = 1 }\n"
    ).zipWithIndex.foreach: (source, index) =>
      val pending = myFixture.addFileToProject(s"src/RefinementBoundary$index.scala", source)
      val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeAlias]).isEmpty)
      assertTrue(
        source,
        Scala3SyntaxCapabilityService
          .get(getProject)
          .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
          .nonEmpty
      )

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

  private def assertDirectChildren(element: PsiElement, source: String): Unit =
    assertEquals(element.getText, element.getTextRange.substring(source))
    val children = element.getNode.getChildren(null).toVector
    assertFalse(element.getText, children.isEmpty)
    assertEquals(element.getText, children.map(_.getText).mkString)
    children.foreach(child => assertSame(element.getNode, child.getTreeParent))

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
