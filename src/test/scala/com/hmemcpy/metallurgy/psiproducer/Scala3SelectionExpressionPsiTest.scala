package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
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
import org.jetbrains.plugins.scala.lang.psi.api.expr.{
  ScExpression,
  ScGenericCall,
  ScReferenceExpression,
  ScSuperReference,
  ScThisReference
}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{
  ScFunctionDefinition,
  ScPatternDefinition,
  ScVariableDefinition
}
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.MetallurgyExpressionPayload
import org.junit.Assert.{assertArrayEquals, assertEquals, assertFalse, assertNull, assertSame, assertTrue}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import scala.jdk.CollectionConverters.*

final class Scala3SelectionExpressionPsiTest extends Scala3CompatTestCase:
  def testPackageAndTemplateDirectDefinitionSelectionsExposeNativePhysicalPsi(): Unit =
    val source   =
      """def packageDef = source.member
        |val packageVal = source.mid.member
        |var packageVar = source /*left*/ . /*right*/ member
        |trait Mixin
        |class C extends Mixin:
        |  def plainThis = this.member
        |  val qualifiedThis = C.this.member
        |  var plainSuper = super.member
        |  def mixedSuper = super[Mixin].member
        |  val qualifiedSuper = C.super.member
        |  var qualifiedMixedSuper = C.super[Mixin].member
        |""".stripMargin
    val file     = physical("SelectionExpressions1.scala", source)
    val roots    = directRhs(file)
    val expected = Vector(
      "source.member",
      "source.mid.member",
      "source /*left*/ . /*right*/ member",
      "this.member",
      "C.this.member",
      "super.member",
      "super[Mixin].member",
      "C.super.member",
      "C.super[Mixin].member"
    )
    assertEquals(expected, roots.map(_.getText))
    roots.foreach: root =>
      assertEquals(root.getText, root.getTextRange.substring(source))
      assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.expr.ScReferenceExpressionImpl", root.getClass.getName)
      assertSame(root, root.getNode.getPsi)
      assertTrue(root.getParent.getClass.getName, isDefinition(root.getParent))
      assertSame(root, ownerRhs(root.getParent))
      assertEquals("member", root.refName)
      assertEquals("member", root.nameId.getText)
      assertSame(root, root.nameId.getParent)
      assertTrue(root.qualifier.nonEmpty)

    assertEquals(Vector("source", ".", "member"), astChildren(roots(0)))
    assertEquals(Vector("source.mid", ".", "member"), astChildren(roots(1)))
    assertEquals(Vector("source", " ", "/*left*/", " ", ".", " ", "/*right*/", " ", "member"), astChildren(roots(2)))
    assertEquals(Vector("source", "source.mid"), qualifierChain(roots(1)))
    assertEquals(Vector("source", ".", "mid"), astChildren(roots(1).qualifier.get))

    val thisRefs = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScThisReference])
      .asScala
      .toVector
      .sortBy(_.getTextRange.getStartOffset)
    assertEquals(Vector("this", "C.this"), thisRefs.map(_.getText))
    assertTrue(thisRefs.head.reference.isEmpty)
    assertTrue(thisRefs.head.refTemplate.nonEmpty)
    assertEquals("C", thisRefs.last.reference.get.refName)
    assertEquals("C", thisRefs.last.reference.get.nameId.getText)
    assertSame(thisRefs.last, thisRefs.last.reference.get.getParent)
    assertTrue(thisRefs.last.refTemplate.nonEmpty)
    assertEquals(Vector("C", ".", "this"), astChildren(thisRefs.last))

    val superRefs = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScSuperReference])
      .asScala
      .toVector
      .sortBy(_.getTextRange.getStartOffset)
    assertEquals(Vector("super", "super[Mixin]", "C.super", "C.super[Mixin]"), superRefs.map(_.getText))
    assertEquals(Vector(None, None, Some("C"), Some("C")), superRefs.map(_.reference.map(_.refName)))
    assertEquals(Vector("", "Mixin", "", "Mixin"), superRefs.map(_.staticSuperName))
    assertTrue(superRefs.forall(!_.isHardCoded))
    assertTrue(superRefs.forall(_.drvTemplate.nonEmpty))
    assertEquals(Vector("super"), astChildren(superRefs(0)))
    assertEquals(Vector("super", "[", "Mixin", "]"), astChildren(superRefs(1)))
    assertEquals(Vector("C", ".", "super"), astChildren(superRefs(2)))
    assertEquals(Vector("C", ".", "super", "[", "Mixin", "]"), astChildren(superRefs(3)))

  def testSelectionCopiesPointersEditsAndReparsesDeterministically(): Unit =
    val source   = "val result = source.mid.member\n"
    val file     = physical("SelectionExpressions2.scala", source)
    val root     = directRhs(file).head
    val pointer  = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(root)
    assertEquals("source.mid.member", directRhs(file.copy()).head.getText)
    val document = PsiDocumentManager.getInstance(getProject).getDocument(file)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          document.replaceString(source.indexOf("mid"), source.indexOf("mid") + 3, "next")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("source.next.member", pointer.getElement.getText)
    assertEquals(Vector("source", "source.next"), qualifierChain(pointer.getElement))
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          document.replaceString(
            document.getText.indexOf("source.next"),
            document.getText.indexOf("source.next") + 11,
            "this"
          )
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("this.member", pointer.getElement.getText)
    assertEquals("this", pointer.getElement.qualifier.get.getText)

  def testSelectionsRemainAstOnlyAcrossStubSerializationAndReload(): Unit =
    assertEquals(15, Scala3DotcFileElementType.SchemaVersion)
    assertEquals(
      "c688331774df2b35d921eb330639c39987f976462376a7f8254a0b3c1d09f4ab",
      Scala3DotcFileElementType.PersistenceSchemaFingerprint
    )
    val source      =
      "package selections\nval stable = source.mid.member\nclass C { val inherited = C.super[Mixin].member }\n"
    val file        = physical("SelectionPersistence1.scala", source).asInstanceOf[PsiFileImpl]
    val tree        = file.calcStubTree
    val beforeShape = stubShape(tree.getPlainList.asScala)
    val beforeIndex = indexShape(tree.getPlainList.asScala)
    assertFalse(beforeShape.exists(row => row.contains("Expression") || row.contains("This") || row.contains("Super")))
    assertEquals(Vector("source.mid.member", "C.super[Mixin].member"), directRhs(file).map(_.getText))
    val output      = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(tree.getRoot, output)
    val bytes       = output.toByteArray
    val restored    = new StubTree(
      SerializationManagerEx.getInstanceEx.deserialize(new ByteArrayInputStream(bytes)).asInstanceOf[PsiFileStub[?]]
    )
    assertEquals(beforeShape, stubShape(restored.getPlainList.asScala))
    assertEquals(beforeIndex, indexShape(restored.getPlainList.asScala))
    val repeated    = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(restored.getRoot, repeated)
    assertArrayEquals(bytes, repeated.toByteArray)

    val opaqueSource =
      "package selections\nval stable = \"012345678901234\"\nclass C { val inherited = \"0123456789012345678\" }\n"
    val opaque       = physical("SelectionOpaque1.scala", opaqueSource).asInstanceOf[PsiFileImpl]
    val opaqueTree   = opaque.calcStubTree
    assertEquals(beforeShape, stubShape(opaqueTree.getPlainList.asScala))
    assertEquals(beforeIndex, indexShape(opaqueTree.getPlainList.asScala))

    file.setTreeElementPointer(null)
    assertNull(file.getTreeElement)
    assertEquals(beforeShape, stubShape(file.getStubTree.getPlainList.asScala))
    assertEquals(beforeIndex, indexShape(file.getStubTree.getPlainList.asScala))
    assertEquals(Vector("source.mid.member", "C.super[Mixin].member"), directRhs(file).map(_.getText))

  def testPositionalTypeApplicationsAreNativeWhileUnsupportedSelectionRootsRemainCompletePayloads(): Unit =
    val positionalSource = "val typed = source.member[Int]\n"
    val positionalFile   = physical("SelectionClosed1.scala", positionalSource)
    val genericCall      = PsiTreeUtil.findChildOfType(positionalFile, classOf[ScGenericCall])
    assertEquals("source.member[Int]", genericCall.getText)
    assertEquals("source.member", genericCall.referencedExpr.getText)
    assertEquals("[Int]", genericCall.typeArgs.getText)
    assertSame(genericCall, genericCall.referencedExpr.getParent)
    assertSame(genericCall, genericCall.typeArgs.getParent)
    assertTrue(PsiTreeUtil.findChildrenOfType(positionalFile, classOf[MetallurgyExpressionPayload]).isEmpty)

    val sources = Vector(
      "val deepTyped = source.a.b.member[Int]\n"                -> Some("source.a.b.member[Int]"),
      "val closed = source().member\n"                          -> Some("source().member"),
      "val blocked = { source.member }\n"                       -> Some("{ source.member }"),
      "val infixed = source.member + other\n"                   -> Some("source.member + other"),
      "def outer = { val local = source.mid.member; local }\n"  ->
        Some("{ val local = source.mid.member; local }"),
      "def nested = { val local = source.a.b.member; local }\n" ->
        Some("{ val local = source.a.b.member; local }"),
      "type Refined = AnyRef { val member: source.member }\n"   -> None
    )
    sources.zipWithIndex.foreach: (entry, index) =>
      val (source, expected) = entry
      val file               = physical(s"SelectionClosed${index + 2}.scala", source)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScReferenceExpression]).isEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScThisReference]).isEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScSuperReference]).isEmpty)
      val payloads           = PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyExpressionPayload]).asScala.toVector
      expected match
        case None                => assertTrue(source, payloads.isEmpty)
        case Some(expectedValue) =>
          val complete = payloads.filter(_.getText == expectedValue)
          assertEquals(source, 1, complete.size)
          val payload  = complete.head
          assertEquals(
            new TextRange(source.indexOf(expectedValue), source.indexOf(expectedValue) + expectedValue.length),
            payload.getTextRange
          )
          assertTrue(PsiTreeUtil.findChildrenOfType(payload, classOf[ScReferenceExpression]).isEmpty)
          assertTrue(PsiTreeUtil.findChildrenOfType(payload, classOf[ScThisReference]).isEmpty)
          assertTrue(PsiTreeUtil.findChildrenOfType(payload, classOf[ScSuperReference]).isEmpty)

    Vector(
      "def default(value: Int = source.member) = value\n",
      "@ann(source.member) val annotated = 1\n"
    ).zipWithIndex.foreach: (source, index) =>
      val pending = myFixture.addFileToProject(s"SelectionRejected${index + 1}.scala", source)
      val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
      assertEquals(source, file.getText)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScReferenceExpression]).isEmpty)
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(source, failure.nonEmpty)

    Vector(
      "val named = call(arg = source.member)\n" -> "call(arg = source.member)"
    ).zipWithIndex.foreach: (entry, index) =>
      val (source, expected) = entry
      val file               = physical(s"SelectionNestedClosed${index + 1}.scala", source)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScReferenceExpression]).isEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScThisReference]).isEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScSuperReference]).isEmpty)
      val payloads           = PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyExpressionPayload]).asScala.toVector
      assertEquals(source, 1, payloads.count(_.getText == expected))
      assertEquals(source, payloads.size, payloads.map(_.getTextRange).distinct.size)

  private def directRhs(root: PsiElement): Vector[ScReferenceExpression] =
    PsiTreeUtil
      .findChildrenOfType(root, classOf[ScReferenceExpression])
      .asScala
      .toVector
      .filter(reference => isDefinition(reference.getParent))
      .sortBy(_.getTextRange.getStartOffset)

  private def qualifierChain(root: ScReferenceExpression): Vector[String] =
    Iterator
      .iterate(root.qualifier.orNull) {
        case reference: ScReferenceExpression => reference.qualifier.orNull
        case _                                => null
      }
      .takeWhile(_ != null)
      .toVector
      .reverse
      .map(_.getText)

  private def isDefinition(element: PsiElement): Boolean        =
    element.isInstanceOf[ScFunctionDefinition] || element.isInstanceOf[ScPatternDefinition] || element
      .isInstanceOf[ScVariableDefinition]
  private def ownerRhs(owner: PsiElement): ScExpression         = owner match
    case value: ScFunctionDefinition => value.body.get
    case value: ScPatternDefinition  => value.expr.get
    case value: ScVariableDefinition => value.expr.get
  private def astChildren(element: PsiElement): Vector[String]  =
    element.getNode.getChildren(null).toVector.map(_.getText)
  private def stubShape(stubs: Iterable[Stub]): Vector[String]  = stubs.iterator
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
      Option(stub.getStubSerializer).foreach(_.asInstanceOf[ObjectStubSerializer[Stub, Stub]].indexStub(stub, sink))
    )
    result.result()
  private def physical(name: String, source: String)            =
    val pending = myFixture.addFileToProject(s"src/$name", source)
    val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals(source, file.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(failure.toString, failure.isEmpty)
    file
