package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.*
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.hmemcpy.metallurgy.status.{MetallurgyStatus, MetallurgyStatusListener}
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiErrorElement, PsiManager, SmartPointerManager}
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.{IndexSink, PsiFileStubImpl, StubIndex, StubIndexKey, StubInputStream, StubOutputStream}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.{IndexingTestUtil, PlatformTestUtil, ServiceContainerUtil}
import com.intellij.util.io.AbstractStringEnumerator
import org.jetbrains.plugins.scala.lang.lexer.{ScalaTokenType, ScalaTokenTypes}
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType
import org.jetbrains.plugins.scala.lang.psi.api.{ScalaElementVisitor, ScalaPsiElement}
import org.jetbrains.plugins.scala.lang.psi.ScExportsHolder
import org.jetbrains.plugins.scala.lang.psi.api.base.{ScEnd, ScPrimaryConstructor, ScStableCodeReference}
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.{
  ScBooleanLiteral,
  ScCharLiteral,
  ScDoubleLiteral,
  ScFloatLiteral,
  ScIntegerLiteral,
  ScLongLiteral,
  ScStringLiteral
}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScEnumCases, ScEnumClassCase, ScEnumSingletonCase}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.ScTemplateBody
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{
  ScInfixTypeElement,
  ScLiteralTypeElement,
  ScParameterizedTypeElement,
  ScParenthesisedTypeElement,
  ScSimpleTypeElement,
  ScTypeElement,
  ScTypeProjection,
  ScWildcardTypeElement
}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScPackaging
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.{ScExportStmt, ScImportSelector, ScImportStmt}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScClass, ScEnum, ScObject, ScTrait, ScTypeDefinition}
import org.jetbrains.plugins.scala.lang.psi.stubs.{
  ScExportStmtStub,
  ScImportExprStub,
  ScImportSelectorStub,
  ScImportSelectorsStub,
  ScImportStmtStub,
  ScPackagingStub
}
import org.jetbrains.plugins.scala.lang.psi.stubs.index.ScalaIndexKeys
import org.junit.Assert.{assertEquals, assertFalse, assertNotNull, assertSame, assertTrue}

import scala.jdk.CollectionConverters.*

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

final class Scala3PackagePsiProducerTest extends Scala3CompatTestCase:

  private def assertTypeAtomsUseNativePhysicalPsi(): Unit =
    val typeTexts = Vector(
      "A",
      "p.A",
      "T#A",
      "p.T#A",
      "x.type",
      "p.x.type",
      "42",
      "-42",
      "1L",
      "1.0f",
      "1.0",
      "'a'",
      "\"literal\"",
      "true",
      "(A)",
      "((p.x.type))"
    )
    val source    = typeTexts.map(value => s"import a.b.given $value\n").mkString
    val pending   = myFixture.addFileToProject("src/TypeAtomCase.scala", source)
    val bindings  = NativePsiElementBindings.probe(getProject).fold(error => throw new AssertionError(error), identity)

    def assertFile(file: com.intellij.psi.PsiFile): Unit =
      assertEquals(source, file.getText)
      assertEquals(source, PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector.map(_.getText).mkString)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
      val failure   = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(failure.toString, failure.isEmpty)
      val selectors = PsiTreeUtil
        .findChildrenOfType(file, classOf[ScImportSelector])
        .asScala
        .toVector
        .sortBy(_.getTextRange.getStartOffset)
      assertEquals(typeTexts, selectors.flatMap(_.givenTypeElement).map(_.getText))
      val types     = selectors.map(_.givenTypeElement.get)
      types.foreach: value =>
        assertSame(value.getParent, selectors(types.indexOf(value)))
        assertEquals(
          value.getText,
          source.substring(value.getTextRange.getStartOffset, value.getTextRange.getEndOffset)
        )
        assertSame(file, value.getContainingFile)
        assertSame(getProject, value.getProject)
        assertSame(value, value.getNode.getPsi)
        assertSame(value, value.getNavigationElement)
        val copy = value.copy()
        assertEquals(value.getClass, copy.getClass)
        assertEquals(value.getText, copy.getText)

      val simpleA             = types(0).asInstanceOf[ScSimpleTypeElement]
      val path                = types(1).asInstanceOf[ScSimpleTypeElement]
      val projection          = types(2).asInstanceOf[ScTypeProjection]
      val qualifiedProjection = types(3).asInstanceOf[ScTypeProjection]
      val singleton           = types(4).asInstanceOf[ScSimpleTypeElement]
      val selectedSingleton   = types(5).asInstanceOf[ScSimpleTypeElement]
      val literals            = types.slice(6, 14).map(_.asInstanceOf[ScLiteralTypeElement])
      val parens              = types(14).asInstanceOf[ScParenthesisedTypeElement]
      val nestedParens        = types(15).asInstanceOf[ScParenthesisedTypeElement]

      assertEquals(bindings.outputRoles(PsiOutputRoleId.SimpleType), simpleA.getNode.getElementType)
      assertEquals("A", simpleA.reference.get.getText)
      assertSame(simpleA, simpleA.reference.get.getParent)
      assertFalse(simpleA.isSingleton)
      assertSame(simpleA.reference.get, simpleA.pathElement)
      assertStablePath(path.reference.get, Vector("p", "A"))
      assertSame(path, path.reference.get.getParent)

      assertEquals(bindings.outputRoles(PsiOutputRoleId.TypeProjection), projection.getNode.getElementType)
      assertEquals("T", projection.typeElement.getText)
      assertSame(projection, projection.typeElement.getParent)
      assertEquals("A", projection.nameId.getText)
      assertSame(projection, projection.nameId.getParent)
      assertTrue(projection.qualifier.isEmpty)
      assertEquals(
        Vector("T"),
        projection.getChildren.toVector.collect { case value: ScTypeElement => value.getText }
      )
      assertEquals("p.T", qualifiedProjection.typeElement.getText)
      assertStablePath(
        qualifiedProjection.typeElement.asInstanceOf[ScSimpleTypeElement].reference.get,
        Vector("p", "T")
      )
      assertEquals("A", qualifiedProjection.nameId.getText)
      assertTrue(qualifiedProjection.qualifier.isEmpty)

      assertEquals(bindings.outputRoles(PsiOutputRoleId.SingletonType), singleton.getNode.getElementType)
      assertTrue(singleton.isSingleton)
      assertEquals("x", singleton.reference.get.getText)
      assertSame(singleton, singleton.reference.get.getParent)
      assertSame(singleton.reference.get, singleton.pathElement)
      assertTrue(selectedSingleton.isSingleton)
      assertStablePath(selectedSingleton.reference.get, Vector("p", "x"))
      assertSame(selectedSingleton, selectedSingleton.reference.get.getParent)
      assertSame(selectedSingleton.reference.get, selectedSingleton.pathElement)

      assertEquals(
        Vector(true, true, true, true, true, true, true, true),
        Vector(
          literals(0).getLiteral.isInstanceOf[ScIntegerLiteral],
          literals(1).getLiteral.isInstanceOf[ScIntegerLiteral],
          literals(2).getLiteral.isInstanceOf[ScLongLiteral],
          literals(3).getLiteral.isInstanceOf[ScFloatLiteral],
          literals(4).getLiteral.isInstanceOf[ScDoubleLiteral],
          literals(5).getLiteral.isInstanceOf[ScCharLiteral],
          literals(6).getLiteral.isInstanceOf[ScStringLiteral],
          literals(7).getLiteral.isInstanceOf[ScBooleanLiteral]
        )
      )
      literals.foreach: value =>
        assertEquals(bindings.outputRoles(PsiOutputRoleId.LiteralType), value.getNode.getElementType)
        assertTrue(value.isSingleton)
        assertSame(value, value.getLiteral.getParent)
        assertEquals(Vector(value.getLiteral), value.getChildren.toVector)
        assertTrue(value.getLiteral.isSimpleLiteral)
      assertEquals(Vector(42, -42, 1L, 1.0f, 1.0, 'a', "literal", true), literals.map(_.getLiteral.getValue))
      assertEquals(
        Vector("42", "-42", "1L", "1.0f", "1.0", "a", "literal", "true"),
        literals.map(_.getLiteral.contentText)
      )

      assertEquals(bindings.outputRoles(PsiOutputRoleId.ParenthesizedType), parens.getNode.getElementType)
      assertEquals("A", parens.innerElement.get.getText)
      assertSame(parens, parens.innerElement.get.getParent)
      assertTrue(parens.sameTreeParent.isEmpty)
      assertEquals(
        Vector("(", "A", ")"),
        PsiTreeUtil.collectElements(parens, _.getFirstChild == null).toVector.map(_.getText)
      )
      assertEquals("(p.x.type)", nestedParens.innerElement.get.getText)
      assertEquals(
        Vector("(", "(", "p", ".", "x", ".", "type", ")", ")"),
        PsiTreeUtil.collectElements(nestedParens, _.getFirstChild == null).toVector.map(_.getText)
      )

      val expectedTokens = Vector(
        "#"    -> NativePsiElementBindings.TypeProjectionHashTokenSurface,
        "."    -> NativePsiElementBindings.TypePathDotTokenSurface,
        "type" -> NativePsiElementBindings.SingletonTypeKeywordTokenSurface,
        "("    -> NativePsiElementBindings.TypeLeftParenthesisTokenSurface,
        ")"    -> NativePsiElementBindings.TypeRightParenthesisTokenSurface
      )
      expectedTokens.foreach: (text, surface) =>
        val leaves = PsiTreeUtil
          .collectElements(file, element => element.getFirstChild == null && element.getText == text)
          .toVector
        assertTrue(text, leaves.nonEmpty)
        leaves.foreach: leaf =>
          assertEquals(bindings.elementTypes(surface), leaf.getNode.getElementType)
          assertEquals(text.length, leaf.getTextRange.getLength)

      val visited = collection.mutable.ArrayBuffer.empty[String]
      val visitor = new ScalaElementVisitor:
        override def visitSimpleTypeElement(value: ScSimpleTypeElement): Unit               = visited += s"simple:${value.getText}"
        override def visitTypeProjection(value: ScTypeProjection): Unit                     = visited += s"projection:${value.getText}"
        override def visitParenthesisedTypeElement(value: ScParenthesisedTypeElement): Unit =
          visited += s"parenthesized:${value.getText}"
        override def visitTypeElement(value: ScTypeElement): Unit                           = visited += s"type:${value.getText}"
        override def visitScalaElement(value: ScalaPsiElement): Unit                        = visited += s"scala:${value.getText}"
      types.foreach(_.accept(visitor))
      assertEquals(
        Vector(
          "simple:A",
          "simple:p.A",
          "projection:T#A",
          "projection:p.T#A",
          "simple:x.type",
          "simple:p.x.type",
          "scala:42",
          "scala:-42",
          "scala:1L",
          "scala:1.0f",
          "scala:1.0",
          "scala:'a'",
          "scala:\"literal\"",
          "scala:true",
          "parenthesized:(A)",
          "parenthesized:((p.x.type))"
        ),
        visited.toVector
      )

      val stubs         = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala.toVector
      val selectorStubs = stubs.collect { case value: ScImportSelectorStub => value }
      assertEquals(typeTexts, selectorStubs.flatMap(_.typeText))
      val enumerator    = new TestStringEnumerator
      selectorStubs.foreach: stub =>
        val sink   = new ByteArrayOutputStream
        val output = new StubOutputStream(sink, enumerator)
        ScalaElementType.IMPORT_SELECTOR.serialize(stub, output)
        output.flush()
        val copy   = ScalaElementType.IMPORT_SELECTOR.deserialize(
          new StubInputStream(new ByteArrayInputStream(sink.toByteArray), enumerator),
          new PsiFileStubImpl(null)
        )
        assertEquals(stub.typeText, copy.typeText)
        assertEquals(stub.referenceText, copy.referenceText)
        assertEquals(stub.isGivenSelector, copy.isGivenSelector)

    val file = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertFile(file)
    assertFile(file.copy().asInstanceOf[com.intellij.psi.PsiFile])

    val projectionPointer = SmartPointerManager
      .getInstance(getProject)
      .createSmartPsiElementPointer(PsiTreeUtil.findChildOfType(file, classOf[ScTypeProjection]))
    val document          = FileDocumentManager.getInstance.getDocument(pending.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.insertString(0, "\n")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("T#A", projectionPointer.getElement.getText)

    val beforeStubs = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala.map(_.getClass.getName).toVector
    file.asInstanceOf[PsiFileImpl].setTreeElementPointer(null)
    assertEquals(null, file.asInstanceOf[PsiFileImpl].getTreeElement)
    assertEquals(
      beforeStubs,
      file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala.map(_.getClass.getName).toVector
    )
    assertEquals("T#A", projectionPointer.getElement.getText)

    val editPending  = myFixture.addFileToProject("src/TypeAtomEditCase.scala", "import a.b.given A\n")
    val editDocument = FileDocumentManager.getInstance.getDocument(editPending.getVirtualFile)
    typeTexts.tail.foreach: replacement =>
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            val start = editDocument.getText.indexOf("given") + "given ".length
            editDocument.replaceString(start, editDocument.getTextLength - 1, replacement)
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(editDocument)
      val reparsed = PsiManager.getInstance(getProject).findFile(editPending.getVirtualFile)
      val selector = PsiTreeUtil.findChildOfType(reparsed, classOf[ScImportSelector])
      assertEquals(replacement, selector.givenTypeElement.get.getText)
      assertTrue(
        Scala3SyntaxCapabilityService
          .get(getProject)
          .failureFor(editPending.getVirtualFile, ParserSyntaxSnapshot.digest(editDocument.getText))
          .isEmpty
      )

    val deletePending  = myFixture.addFileToProject("src/TypeAtomDeleteCase.scala", "import a.b.given x.type\n")
    val deleteFile     = PsiManager.getInstance(getProject).findFile(deletePending.getVirtualFile)
    val deletePointer  = SmartPointerManager
      .getInstance(getProject)
      .createSmartPsiElementPointer(PsiTreeUtil.findChildOfType(deleteFile, classOf[ScSimpleTypeElement]))
    val deleteDocument = FileDocumentManager.getInstance.getDocument(deletePending.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = deleteDocument.deleteString(0, deleteDocument.getTextLength)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(deleteDocument)
    assertEquals(null, deletePointer.getElement)

    val malformed     = myFixture.addFileToProject("src/TypeAtomMalformedCase.scala", "import a.b.given (A\n")
    val malformedFile = PsiManager.getInstance(getProject).findFile(malformed.getVirtualFile)
    assertTrue(PsiTreeUtil.findChildrenOfType(malformedFile, classOf[ScImportStmt]).isEmpty)
    assertTrue(malformedFile.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala.drop(1).isEmpty)
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(malformed.getVirtualFile, ParserSyntaxSnapshot.digest(malformedFile.getText))
        .nonEmpty
    )

  private def assertQualifiedWildcardAndInfixGivenSelectorsUseNativePhysicalPsi(): Unit =
    val source            =
      """import a.b.given scala.math.Ordering.Int
        |import a.b.given scala.math.Ordering[Int]
        |import a.b.given F[?]
        |import a.b.given F[? <: U]
        |import a.b.given F[? >: L]
        |import a.b.{c, given F[? >: L <: U], given A | B & C <:< D}
        |import a.b.given A | B | C
        |export a.b.given scala.math.Ordering.Int
        |export a.b.{given scala.math.Ordering[Int]}
        |export a.b.given F[?]
        |export a.b.{given F[? <: U]}
        |export a.b.given F[? >: L]
        |export a.b.{c, given F[? >: L <: U], given A | B & C <:< D}
        |export a.b.given A | B | C
        |""".stripMargin
    val pending           = myFixture.addFileToProject("src/BoundedGivenTypeCase.scala", source)
    val expectedTypeTexts = Vector(
      "scala.math.Ordering.Int",
      "scala.math.Ordering[Int]",
      "F[?]",
      "F[? <: U]",
      "F[? >: L]",
      "F[? >: L <: U]",
      "A | B & C <:< D",
      "A | B | C"
    ).flatMap(value => Vector(value, value))
    val bindings          = NativePsiElementBindings.probe(getProject).fold(error => throw new AssertionError(error), identity)
    Vector(
      PsiManager.getInstance(getProject).findFile(pending.getVirtualFile),
      PsiManager.getInstance(getProject).findFile(pending.getVirtualFile).copy().asInstanceOf[com.intellij.psi.PsiFile]
    ).foreach: file =>
      assertEquals(source, file.getText)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
      assertEquals(source, PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector.map(_.getText).mkString)
      val selectors        = PsiTreeUtil.findChildrenOfType(file, classOf[ScImportSelector]).asScala.toVector
      val failure          = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertEquals(failure.toString, 18, selectors.size)
      assertTrue(failure.toString, failure.isEmpty)
      assertEquals(16, selectors.count(_.isGivenSelector))
      assertEquals(expectedTypeTexts.sorted, selectors.flatMap(_.givenTypeElement).map(_.getText).sorted)
      val qualified        = PsiTreeUtil
        .findChildrenOfType(file, classOf[ScSimpleTypeElement])
        .asScala
        .filter(_.getText == "scala.math.Ordering.Int")
        .toVector
      assertEquals(2, qualified.size)
      qualified.foreach: value =>
        val reference = value.reference.get
        assertEquals("scala.math.Ordering.Int", reference.getText)
        assertSame(value, reference.getParent)
        val nested    = Iterator
          .iterate(Option(reference))(_.flatMap(_.qualifier))
          .takeWhile(_.nonEmpty)
          .flatten
          .toVector
        assertEquals(
          Vector("scala.math.Ordering.Int", "scala.math.Ordering", "scala.math", "scala"),
          nested.map(_.getText)
        )
        nested
          .sliding(2)
          .foreach:
            case Vector(parent, child) => assertSame(parent, child.getParent)
            case _                     => ()
      val parameterized    = PsiTreeUtil.findChildrenOfType(file, classOf[ScParameterizedTypeElement]).asScala.toVector
      assertEquals(10, parameterized.size)
      assertEquals(2, parameterized.count(_.typeElement.getText == "scala.math.Ordering"))
      parameterized.foreach: value =>
        assertSame(value, value.typeElement.getParent)
        assertSame(value, value.typeArgList.getParent)
        assertEquals(
          value.typeArgList.typeArgs.toVector,
          value.typeArgList.getChildren.collect {
            case argument: org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement => argument
          }.toVector
        )
      val wildcards        = PsiTreeUtil.findChildrenOfType(file, classOf[ScWildcardTypeElement]).asScala.toVector
      assertEquals(8, wildcards.size)
      assertEquals(
        Vector("?", "? <: U", "? >: L", "? >: L <: U").flatMap(value => Vector(value, value)).sorted,
        wildcards.map(_.getText).sorted
      )
      wildcards.foreach: value =>
        val expectedLower = Option.when(value.getText.contains(">:"))("L")
        val expectedUpper = Option.when(value.getText.contains("<:"))("U")
        assertEquals(expectedLower, value.lowerTypeElement.map(_.getText))
        assertEquals(expectedUpper, value.upperTypeElement.map(_.getText))
        value.lowerTypeElement.foreach(bound => assertSame(value, bound.getParent))
        value.upperTypeElement.foreach(bound => assertSame(value, bound.getParent))
        val question      =
          PsiTreeUtil.collectElements(value, element => element.getFirstChild == null && element.getText == "?")
        assertEquals(1, question.length)
        assertEquals(
          bindings.elementTypes(NativePsiElementBindings.WildcardQuestionTokenSurface),
          question.head.getNode.getElementType
        )
        Vector(
          ">:" -> NativePsiElementBindings.LowerTypeBoundTokenSurface,
          "<:" -> NativePsiElementBindings.UpperTypeBoundTokenSurface
        )
          .foreach: (text, surface) =>
            val tokens =
              PsiTreeUtil.collectElements(value, element => element.getFirstChild == null && element.getText == text)
            assertEquals(if value.getText.contains(text) then 1 else 0, tokens.length)
            tokens.foreach(token => assertEquals(bindings.elementTypes(surface), token.getNode.getElementType))
      val infixTypes       = PsiTreeUtil.findChildrenOfType(file, classOf[ScInfixTypeElement]).asScala.toVector
      val outerInfixTypes  = infixTypes.filter(_.getText == "A | B & C <:< D")
      val associativeTypes = infixTypes.filter(_.getText == "A | B | C")
      assertEquals(10, infixTypes.size)
      assertEquals(2, outerInfixTypes.size)
      outerInfixTypes.foreach: outer =>
        assertEquals("A", outer.left.getText)
        assertEquals("|", outer.operation.getText)
        val intersection = outer.rightOption.get.asInstanceOf[ScInfixTypeElement]
        assertEquals("B", intersection.left.getText)
        assertEquals("&", intersection.operation.getText)
        val evidence     = intersection.rightOption.get.asInstanceOf[ScInfixTypeElement]
        assertEquals("C", evidence.left.getText)
        assertEquals("<:<", evidence.operation.getText)
        assertEquals("D", evidence.rightOption.get.getText)
        Vector(outer, intersection, evidence).foreach: value =>
          assertSame(value, value.left.getParent)
          assertSame(value, value.operation.getParent)
          assertSame(value, value.rightOption.get.getParent)
      assertEquals(2, associativeTypes.size)
      associativeTypes.foreach: outer =>
        val left = outer.left.asInstanceOf[ScInfixTypeElement]
        assertEquals("A | B", left.getText)
        assertEquals("A", left.left.getText)
        assertEquals("|", left.operation.getText)
        assertEquals("B", left.rightOption.get.getText)
        assertEquals("|", outer.operation.getText)
        assertEquals("C", outer.rightOption.get.getText)
        assertSame(outer, left.getParent)
      val composites       =
        PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).asScala.toVector ++
          PsiTreeUtil.findChildrenOfType(file, classOf[ScExportStmt]).asScala.toVector ++ selectors ++
          PsiTreeUtil.findChildrenOfType(file, classOf[ScStableCodeReference]).asScala.toVector ++
          PsiTreeUtil.findChildrenOfType(file, classOf[ScSimpleTypeElement]).asScala.toVector ++ parameterized ++
          wildcards ++ infixTypes
      composites.foreach: element =>
        assertEquals(
          element.getText,
          source.substring(element.getTextRange.getStartOffset, element.getTextRange.getEndOffset)
        )
        assertSame(file, element.getContainingFile)
        assertSame(getProject, element.getProject)
        assertSame(element, element.getNode.getPsi)
        assertSame(element, element.getNavigationElement)

      val selectorStubs = file
        .asInstanceOf[PsiFileImpl]
        .calcStubTree
        .getPlainList
        .asScala
        .collect { case value: ScImportSelectorStub => value }
        .toVector
      val givenStubs    = selectorStubs.filter(_.isGivenSelector)
      assertEquals(expectedTypeTexts.sorted, givenStubs.flatMap(_.typeText).sorted)
      val enumerator    = new TestStringEnumerator
      givenStubs.foreach: stub =>
        val sink   = new ByteArrayOutputStream
        val output = new StubOutputStream(sink, enumerator)
        ScalaElementType.IMPORT_SELECTOR.serialize(stub, output)
        output.flush()
        val copy   = ScalaElementType.IMPORT_SELECTOR.deserialize(
          new StubInputStream(new ByteArrayInputStream(sink.toByteArray), enumerator),
          new PsiFileStubImpl(null)
        )
        assertEquals(stub.typeText, copy.typeText)
        assertEquals(stub.referenceText, copy.referenceText)
        assertEquals(stub.isGivenSelector, copy.isGivenSelector)

    val original = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    val prefix   = PsiTreeUtil
      .findChildrenOfType(original, classOf[ScStableCodeReference])
      .asScala
      .find(_.getText == "scala.math")
      .get
    val pointer  = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(prefix)
    val document = FileDocumentManager.getInstance.getDocument(pending.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.insertString(source.length, "\n")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("scala.math", pointer.getElement.getText)
    assertSame(pointer.getElement, pointer.getElement.getNavigationElement)

    val editPending  = myFixture.addFileToProject(
      "src/BoundedGivenTypeEditCase.scala",
      "import a.b.given scala.math.Ordering.Int\n"
    )
    val editDocument = FileDocumentManager.getInstance.getDocument(editPending.getVirtualFile)
    Vector("F[scala.math.Ordering.Int]", "F[? >: L <: U]", "A | B", "T").foreach: replacement =>
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            val start = editDocument.getText.indexOf("given") + "given ".length
            editDocument.replaceString(start, editDocument.getTextLength - 1, replacement)
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(editDocument)
      val reparsed = PsiManager.getInstance(getProject).findFile(editPending.getVirtualFile)
      val selector = PsiTreeUtil.findChildOfType(reparsed, classOf[ScImportSelector])
      assertEquals(replacement, selector.givenTypeElement.get.getText)
      assertSame(selector, selector.givenTypeElement.get.getParent)
      assertTrue(PsiTreeUtil.findChildrenOfType(reparsed, classOf[PsiErrorElement]).isEmpty)
      assertTrue(
        Scala3SyntaxCapabilityService
          .get(getProject)
          .failureFor(editPending.getVirtualFile, ParserSyntaxSnapshot.digest(editDocument.getText))
          .isEmpty
      )

  private def assertDeepQualifiedAndInfixGivenSelectorsRemainStackSafe(): Unit =
    val qualificationDepth = 512
    val infixDepth         = 512
    val qualified          = Vector.tabulate(qualificationDepth)(index => s"q$index").mkString(".") + ".T"
    val infix              = Vector.tabulate(infixDepth + 1)(index => s"T$index").mkString(" | ")
    val source             = s"import a.b.given $qualified\nexport a.b.given $infix\n"
    val pending            = myFixture.addFileToProject("src/DeepBoundedGivenTypeCase.scala", source)
    val file               = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals(source, file.getText)
    assertEquals(source, PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector.map(_.getText).mkString)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    assertEquals(
      Vector(qualified, infix),
      PsiTreeUtil
        .findChildrenOfType(file, classOf[ScImportSelector])
        .asScala
        .toVector
        .flatMap(_.givenTypeElement)
        .map(_.getText)
    )
    assertEquals(
      qualificationDepth + 1,
      PsiTreeUtil
        .findChildrenOfType(file, classOf[ScStableCodeReference])
        .asScala
        .count(reference => reference.getText.startsWith("q0"))
    )
    assertEquals(infixDepth, PsiTreeUtil.findChildrenOfType(file, classOf[ScInfixTypeElement]).size)
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
        .isEmpty
    )

  def testExactAliasAndAppliedGivenImportsUseNativePhysicalPsi(): Unit =
    val source   =
      "import java as j\nimport a.b.c as _\nimport a.b.given Ordering[Int]\nimport a.b.given F[G[Int]]\nimport a.b.given Either[Int, String]\n"
    val pending  = myFixture.addFileToProject("src/ExactImportCase.scala", source)
    Vector(
      PsiManager.getInstance(getProject).findFile(pending.getVirtualFile),
      PsiManager.getInstance(getProject).findFile(pending.getVirtualFile).copy().asInstanceOf[com.intellij.psi.PsiFile]
    ).foreach: file =>
      assertEquals(source, file.getText)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
      val statements    = PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).asScala.toVector
      val expressions   = statements.flatMap(_.importExprs)
      val selectorSets  = expressions.flatMap(_.selectorSet)
      val selectors     = expressions.flatMap(_.selectors)
      val failure       = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(failure.toString, failure.isEmpty)
      assertEquals(
        Vector(
          "java as j",
          "a.b.c as _",
          "a.b.given Ordering[Int]",
          "a.b.given F[G[Int]]",
          "a.b.given Either[Int, String]"
        ),
        expressions.map(_.getText)
      )
      assertEquals(
        Vector("java as j", "c as _", "given Ordering[Int]", "given F[G[Int]]", "given Either[Int, String]"),
        selectorSets.map(_.getText)
      )
      assertEquals(
        Vector("java as j", "c as _", "given Ordering[Int]", "given F[G[Int]]", "given Either[Int, String]"),
        selectors.map(_.getText)
      )
      assertTrue(selectors.take(2).forall(_.isAliasedImport))
      assertEquals(Vector(Some("j"), Some("_")), selectors.take(2).map(_.aliasName))
      val parameterized = PsiTreeUtil.findChildOfType(file, classOf[ScParameterizedTypeElement])
      assertNotNull(parameterized)
      assertEquals("Ordering[Int]", parameterized.getText)
      assertEquals("Ordering", parameterized.typeElement.getText)
      assertEquals("[Int]", parameterized.typeArgList.getText)
      assertEquals(Vector("Int"), parameterized.typeArgList.typeArgs.map(_.getText).toVector)
      assertSame(parameterized, parameterized.typeElement.getParent)
      assertSame(parameterized, parameterized.typeArgList.getParent)
      val bindings      = NativePsiElementBindings.probe(getProject).fold(error => throw new AssertionError(error), identity)
      assertSame(
        org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.MetallurgyTypeArguments.ElementType,
        bindings.outputRoles(PsiOutputRoleId.NamedTypeArguments)
      )
      assertSame(
        org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.MetallurgyNamedTypeArgument.ElementType,
        bindings.outputRoles(PsiOutputRoleId.NamedTypeArgument)
      )
      assertEquals(
        "org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyTypeArguments",
        bindings.outputSurfaces(PsiOutputRoleId.NamedTypeArguments)
      )
      assertEquals(
        "org/jetbrains/plugins/scala/lang/psi/impl/metallurgy/MetallurgyNamedTypeArgument",
        bindings.outputSurfaces(PsiOutputRoleId.NamedTypeArgument)
      )
      val brackets      = PsiTreeUtil
        .collectElements(file, element => element.getFirstChild == null && Set("[", "]")(element.getText))
        .toVector
      assertEquals(8, brackets.size)
      brackets.foreach: bracket =>
        val surface =
          if bracket.getText == "[" then NativePsiElementBindings.TypeArgumentLeftTokenSurface
          else NativePsiElementBindings.TypeArgumentRightTokenSurface
        assertEquals(bindings.elementTypes(surface), bracket.getNode.getElementType)
        assertEquals(1, bracket.getTextRange.getLength)
      val stubs         = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala
      assertEquals(5, stubs.count(_.isInstanceOf[ScImportStmtStub]))
      assertEquals(5, stubs.count(_.isInstanceOf[ScImportExprStub]))
      assertEquals(
        Vector("Ordering[Int]", "F[G[Int]]", "Either[Int, String]"),
        stubs.collect { case stub: ScImportSelectorStub => stub }.flatMap(_.typeText)
      )
    val document = FileDocumentManager.getInstance.getDocument(pending.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          document.replaceString(source.indexOf("Ordering"), source.indexOf("Ordering") + 8, "Comparator")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    val reparsed = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals("Comparator[Int]", PsiTreeUtil.findChildOfType(reparsed, classOf[ScParameterizedTypeElement]).getText)

    val triviaSource = "import a.b /* before */ . /* after */ {c}\nimport a.b /* before */ . /* after */ given T\n"
    val triviaFile   = myFixture.addFileToProject("src/TriviaImportCase.scala", triviaSource)
    val triviaPsi    = PsiManager.getInstance(getProject).findFile(triviaFile.getVirtualFile)
    assertEquals(triviaSource, triviaPsi.getText)
    assertEquals(
      triviaSource,
      PsiTreeUtil.collectElements(triviaPsi, _.getFirstChild == null).toVector.map(_.getText).mkString
    )
    assertTrue(PsiTreeUtil.findChildrenOfType(triviaPsi, classOf[PsiErrorElement]).isEmpty)
    assertEquals(
      Vector("a.b /* before */ . /* after */ {c}", "a.b /* before */ . /* after */ given T"),
      PsiTreeUtil
        .findChildrenOfType(triviaPsi, classOf[ScImportStmt])
        .asScala
        .toVector
        .flatMap(_.importExprs)
        .map(_.getText)
    )
    assertTypeAtomsUseNativePhysicalPsi()
    assertQualifiedWildcardAndInfixGivenSelectorsUseNativePhysicalPsi()
    assertDeepQualifiedAndInfixGivenSelectorsRemainStackSafe()

  def testSyntaxCapabilityFailureLifecycleUsesExistingStatusTopic(): Unit =
    val pending        = myFixture.addFileToProject("src/CapabilityStatusCase.scala", "import a.b\n")
    val statuses       = collection.mutable.ArrayBuffer.empty[MetallurgyStatus]
    val connection     = getProject.getMessageBus.connect(getTestRootDisposable)
    connection.subscribe(
      MetallurgyStatus.Topic,
      new MetallurgyStatusListener:
        override def statusChanged(status: MetallurgyStatus): Unit = statuses += status
    )
    val failure        = Scala3SyntaxCapabilityFailure(
      ParserSyntaxSnapshot.digest("import a.b\n"),
      Scala3SyntaxCapabilityStage.Planner,
      "unsupported closed output forest",
      Scala3ParserPreparationLifecycle.get(getProject).stateFor(getModule).currentEpoch,
      None,
      Scala3SyntaxCapabilityRequirement.GrammarRole(None)
    )
    val service        = Scala3SyntaxCapabilityService.get(getProject)
    service.publish(pending.getVirtualFile, failure)
    val afterFirst     = statuses.size
    service.publish(pending.getVirtualFile, failure)
    assertEquals("an identical capability failure must not be published twice", afterFirst, statuses.size)
    val second         = myFixture.addFileToProject("src/SecondCapabilityStatusCase.scala", "import c.d\n")
    service.publish(
      second.getVirtualFile,
      failure.copy(
        sourceDigest = ParserSyntaxSnapshot.digest("import c.d\n"),
        detail = "second unsupported forest",
        requirement = Scala3SyntaxCapabilityRequirement.OutputRole(Some(PsiOutputRoleId.IntegerLiteral.value))
      )
    )
    assertEquals(
      Vector(pending.getVirtualFile, second.getVirtualFile),
      service.currentFailures.flatMap(_.scope.file)
    )
    val published      = statuses.last.asInstanceOf[MetallurgyStatus.SyntaxCapability].report
    assertEquals(Some(second.getVirtualFile), published.scope.file)
    assertEquals(getModule.getName, published.scope.moduleName)
    assertEquals(Scala3SyntaxCapabilityOperation.ProduceWholeFilePsi, published.scope.operation)
    assertEquals(
      Scala3SyntaxCapabilityRequirement.OutputRole(Some(PsiOutputRoleId.IntegerLiteral.value)),
      published.requirement
    )
    assertEquals(Scala3SyntaxCapabilityState.Unavailable, published.state)
    assertEquals(Scala3SyntaxCapabilityEvidenceState.Recorded, published.evidence.state)
    assertEquals(Scala3SyntaxCapabilityStage.Planner, published.evidence.stage)
    assertEquals("second unsupported forest", published.evidence.detail)
    assertEquals(Scala3SyntaxCapabilityRemediationState.ImplementationRequired, published.remediation)
    assertEquals(Some(ParserSyntaxSnapshot.digest("import c.d\n")), published.sourceDigest)
    assertEquals(failure.preparationEpoch, published.preparationEpoch)
    assertEquals(Scala3SyntaxCapabilityService.RetainedOperations, published.retainedOperations)
    assertTrue(published.compilerCoordinate.nonEmpty)
    assertTrue(published.hostIdentity.ideBuild.nonEmpty)
    assertEquals("org.intellij.scala", published.hostIdentity.scalaPluginId)
    assertTrue(published.hostIdentity.scalaPluginVersion.nonEmpty)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = pending.getVirtualFile.rename(this, "RenamedCapabilityStatusCase.scala")
    )
    assertEquals("RenamedCapabilityStatusCase.scala", service.currentFailures.head.scope.file.get.getName)
    service.discard(Vector(pending.getVirtualFile))
    assertTrue(statuses.last.isInstanceOf[MetallurgyStatus.SyntaxCapability])
    assertEquals(Vector(second.getVirtualFile), service.currentFailures.flatMap(_.scope.file))
    val secondDocument = FileDocumentManager.getInstance.getDocument(second.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = secondDocument.setText("import changed.d\n")
    )
    assertTrue(service.currentFailures.isEmpty)

  def testSimpleOwnerTypeMountsUseNativePhysicalPsi(): Unit =
    assertEquals(
      Math.addExact(org.jetbrains.plugins.scala.lang.parser.Scala3ParserDefinition.FileNodeType.getStubVersion, 14),
      Scala3DotcParserDefinition.FileNodeType.getStubVersion
    )
    assertEquals(
      "e3649a7979469c4fc106d226259f006bd32726ea0fb4f1a4b7c89284a72baa41",
      Scala3DotcFileElementType.SchemaFingerprint
    )
    val source  =
      """trait B
        |class C[A](x: A) extends B:
        |  self: B =>
        |  def value: A = x
        |""".stripMargin
    val pending = myFixture.addFileToProject("src/OwnerTypeMountCase.scala", source)
    val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals(source, file.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(failure.toString, failure.isEmpty)
    assertEquals(
      Vector("A"),
      PsiTreeUtil
        .findChildrenOfType(file, classOf[org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScClass])
        .asScala
        .toVector
        .filter(_.name == "C")
        .flatMap(_.parameters)
        .flatMap(_.typeElement)
        .map(_.getText)
    )
    assertEquals(
      Vector("B"),
      PsiTreeUtil
        .findChildrenOfType(
          file,
          classOf[org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.ScTemplateParents]
        )
        .asScala
        .toVector
        .flatMap(_.typeElements)
        .map(_.getText)
    )
    assertEquals(
      Vector("B"),
      PsiTreeUtil
        .findChildrenOfType(
          file,
          classOf[org.jetbrains.plugins.scala.lang.psi.api.base.types.ScSelfTypeElement]
        )
        .asScala
        .toVector
        .flatMap(_.typeElement)
        .map(_.getText)
    )

  def testParentlessTemplateDefinitionsUseNativePhysicalPsi(): Unit =
    val source      =
      """class C
        |trait T
        |object O
        |class Explicit()
        |class Braced {
        |  trait Nested
        |}
        |enum E:
        |  case A
        |  case B()
        |end E
        |""".stripMargin
    val pending     = myFixture.addFileToProject("src/ParentlessTemplateCase.scala", source)
    val file        = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    val classes     = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScClass])
      .asScala
      .filterNot(_.isInstanceOf[ScEnum | ScEnumClassCase])
      .map(_.name)
      .toVector
      .sorted
    val failure     = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue(failure.toString, failure.isEmpty)
    assertEquals(source, file.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    assertEquals(Vector("Braced", "C", "Explicit"), classes)
    assertEquals(
      Vector("Nested", "T"),
      PsiTreeUtil.findChildrenOfType(file, classOf[ScTrait]).asScala.map(_.name).toVector.sorted
    )
    assertEquals(
      Vector("O"),
      PsiTreeUtil
        .findChildrenOfType(file, classOf[ScObject])
        .asScala
        .filterNot(_.isInstanceOf[ScEnumSingletonCase])
        .map(_.name)
        .toVector
    )
    val enumeration = PsiTreeUtil.findChildOfType(file, classOf[ScEnum])
    assertEquals("E", enumeration.name)
    assertEquals(
      Vector("A"),
      PsiTreeUtil.findChildrenOfType(file, classOf[ScEnumSingletonCase]).asScala.map(_.name).toVector
    )
    assertEquals(
      Vector("B"),
      PsiTreeUtil.findChildrenOfType(file, classOf[ScEnumClassCase]).asScala.map(_.name).toVector
    )
    assertEquals(2, PsiTreeUtil.findChildrenOfType(file, classOf[ScEnumCases]).size)
    assertEquals(5, PsiTreeUtil.findChildrenOfType(file, classOf[ScPrimaryConstructor]).size)
    assertEquals(2, PsiTreeUtil.findChildrenOfType(file, classOf[ScTemplateBody]).size)

  private def indexedPackages(fqn: String): Vector[ScPackaging] =
    StubIndex
      .getElements(
        ScalaIndexKeys.PACKAGE_FQN_KEY,
        fqn,
        getProject,
        GlobalSearchScope.projectScope(getProject),
        classOf[ScPackaging]
      )
      .asScala
      .toVector

  private def indexedExports(packageName: String): Vector[ScExportStmt] =
    StubIndex
      .getElements(
        ScalaIndexKeys.TOP_LEVEL_EXPORT_BY_PKG_KEY,
        packageName,
        getProject,
        GlobalSearchScope.projectScope(getProject),
        classOf[ScExportStmt]
      )
      .asScala
      .toVector

  private def awaitReady(lifecycle: Scala3ParserPreparationLifecycle, label: String): Unit =
    PlatformTestUtil.waitWithEventsDispatching(
      label,
      () => lifecycle.stateFor(getModule).isInstanceOf[ParserPreparationState.Ready],
      10000
    )

  private def assertRecursiveStablePathsPreserveNativePsiPersistenceAndEditIdentity(): Unit =
    val packageSource   = "package alpha.beta.gamma.delta\n"
    val packagePending  = myFixture.addFileToProject("src/RecursivePackageCase.scala", packageSource)
    val packageFile     = PsiManager.getInstance(getProject).findFile(packagePending.getVirtualFile)
    assertSame(packageFile, PsiManager.getInstance(getProject).findFile(packagePending.getVirtualFile))
    assertPackagePath(packageFile, packageSource, Vector("alpha", "beta", "gamma", "delta"), persistence = true)
    assertPackagePath(
      packageFile.copy().asInstanceOf[com.intellij.psi.PsiFile],
      packageSource,
      Vector("alpha", "beta", "gamma", "delta"),
      persistence = true
    )
    val packagePrefix   = PsiTreeUtil
      .findChildrenOfType(packageFile, classOf[ScStableCodeReference])
      .asScala
      .find(_.getText == "alpha.beta")
      .get
    val packagePointer  = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(packagePrefix)
    val packageDocument = FileDocumentManager.getInstance.getDocument(packagePending.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = packageDocument.insertString(packageSource.length - 1, ".epsilon.zeta")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(packageDocument)
    val increased       = PsiManager.getInstance(getProject).findFile(packagePending.getVirtualFile)
    assertPackagePath(
      increased,
      "package alpha.beta.gamma.delta.epsilon.zeta\n",
      Vector("alpha", "beta", "gamma", "delta", "epsilon", "zeta"),
      persistence = true
    )
    assertEquals("alpha.beta", packagePointer.getElement.getText)
    assertSame(packagePointer.getElement, packagePointer.getElement.getNavigationElement)

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = packageDocument.setText("package alpha.beta\n")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(packageDocument)
    val decreased = PsiManager.getInstance(getProject).findFile(packagePending.getVirtualFile)
    assertPackagePath(decreased, "package alpha.beta\n", Vector("alpha", "beta"), persistence = true)
    assertEquals("alpha.beta", packagePointer.getElement.getText)
    assertSame(packagePointer.getElement, packagePointer.getElement.getNavigationElement)

    val namedSource  = "package alpha.`match`.δ.++\n"
    val namedPending = myFixture.addFileToProject("src/NamedRecursivePackageCase.scala", namedSource)
    val namedFile    = PsiManager.getInstance(getProject).findFile(namedPending.getVirtualFile)
    assertPackagePath(namedFile, namedSource, Vector("alpha", "`match`", "δ", "++"), persistence = true)

    val importSource   =
      """import packet.alpha.`match`.δ.deep.ordinary
        |import packet.alpha.`match`.δ.deep.*
        |import packet.alpha.`match`.δ.deep.ordinary as renamed
        |import packet.alpha.`match`.δ.deep.{ordinary}
        |import packet.alpha.`match`.δ.deep.{ordinary as alias}
        |import packet.alpha.`match`.δ.deep.{ordinary => legacy}
        |import packet.alpha.`match`.δ.deep.given
        |import packet.alpha.`match`.δ.deep.given Box[Int]
        |import packet.alpha.`match`.δ.deep.{given, given Box[Int], *}
        |import packet.alpha.`match`.δ.deep.++
        |import packet.alpha.`match`.δ.deep.λ
        |import packet.alpha.`match`.δ.deep.`back-tick`
        |""".stripMargin
    val importPending  = myFixture.addFileToProject("src/RecursiveImportCase.scala", importSource)
    val importFile     = PsiManager.getInstance(getProject).findFile(importPending.getVirtualFile)
    val qualifier      = Vector("packet", "alpha", "`match`", "δ", "deep")
    assertSame(importFile, PsiManager.getInstance(getProject).findFile(importPending.getVirtualFile))
    assertImportPaths(importFile, importSource, qualifier)
    assertImportPaths(importFile.copy().asInstanceOf[com.intellij.psi.PsiFile], importSource, qualifier)
    val importPrefix   = PsiTreeUtil
      .findChildrenOfType(importFile, classOf[ScStableCodeReference])
      .asScala
      .find(_.getText == "packet.alpha")
      .get
    val importPointer  = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(importPrefix)
    val importDocument = FileDocumentManager.getInstance.getDocument(importPending.getVirtualFile)
    val increasedText  = importSource.replace("packet.alpha.`match`.δ.deep", "packet.alpha.`match`.δ.deep.more")
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = importDocument.setText(increasedText)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(importDocument)
    assertImportPaths(
      PsiManager.getInstance(getProject).findFile(importPending.getVirtualFile),
      increasedText,
      qualifier :+ "more"
    )
    assertEquals("packet.alpha", importPointer.getElement.getText)
    assertSame(importPointer.getElement, importPointer.getElement.getNavigationElement)

    val decreasedText = increasedText.replace("packet.alpha.`match`.δ.deep.more", "packet.alpha.deep")
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = importDocument.setText(decreasedText)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(importDocument)
    assertImportPaths(
      PsiManager.getInstance(getProject).findFile(importPending.getVirtualFile),
      decreasedText,
      Vector("packet", "alpha", "deep")
    )
    assertEquals("packet.alpha", importPointer.getElement.getText)
    assertSame(importPointer.getElement, importPointer.getElement.getNavigationElement)

    val adjacentSource  = "package alpha.beta.gamma.delta\n\nfinal class Adjacent\n"
    val adjacentPending = myFixture.addFileToProject("src/AdjacentRecursivePackageCase.scala", adjacentSource)
    val adjacentFile    = PsiManager.getInstance(getProject).findFile(adjacentPending.getVirtualFile)
    assertEquals(adjacentSource, adjacentFile.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(adjacentFile, classOf[ScPackaging]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(adjacentFile, classOf[ScTypeDefinition]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(adjacentFile, classOf[PsiErrorElement]).isEmpty)
    val failure         = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(adjacentPending.getVirtualFile, ParserSyntaxSnapshot.digest(adjacentSource))
    assertTrue(failure.toString, failure.nonEmpty)
    assertEquals(Scala3SyntaxCapabilityStage.Catalog, failure.get.stage)
    assertTrue(adjacentFile.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala.drop(1).isEmpty)

  def testUnsupportedCompilerValidProductionFailsClosedWithCapabilityReason(): Unit =
    val source      = "class Parent(value: Int)\nclass Unsupported extends Parent(1)\n"
    val pending     = myFixture.addFileToProject("src/UnsupportedCase.scala", source)
    val file        = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals(source, file.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeDefinition]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val failure     = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
    assertTrue("unsupported syntax must publish a file-scoped capability failure", failure.nonEmpty)
    assertEquals(ParserSyntaxSnapshot.digest(source), failure.get.sourceDigest)
    assertEquals(Scala3SyntaxCapabilityStage.Catalog, failure.get.stage)
    assertTrue(failure.get.detail.nonEmpty)
    assertTrue(failure.get.compilerIdentity.nonEmpty)
    val report      = Scala3SyntaxCapabilityService
      .get(getProject)
      .currentFailures
      .find(_.scope.file.contains(pending.getVirtualFile))
      .get
    assertEquals(Scala3SyntaxCapabilityState.Unavailable, report.state)
    assertEquals(Scala3SyntaxCapabilityOperation.ProduceWholeFilePsi, report.scope.operation)
    assertEquals(Scala3SyntaxCapabilityRequirement.GrammarRole(None), report.requirement)
    assertEquals(Some(ParserSyntaxSnapshot.digest(source)), report.sourceDigest)
    assertEquals(failure.get.preparationEpoch, report.preparationEpoch)
    assertEquals(failure.get.compilerIdentity, report.compilerIdentity)
    assertEquals(failure.get.compilerIdentity.map(_.coordinate), report.compilerCoordinate)
    assertEquals(Scala3SyntaxCapabilityEvidenceState.Recorded, report.evidence.state)
    assertEquals(Scala3SyntaxCapabilityStage.Catalog, report.evidence.stage)
    assertEquals(Scala3SyntaxCapabilityRemediationState.ImplementationRequired, report.remediation)
    assertEquals(Scala3SyntaxCapabilityService.RetainedOperations, report.retainedOperations)
    assertTrue(report.hostIdentity.ideBuild.nonEmpty)
    assertTrue(report.hostIdentity.scalaPluginVersion.nonEmpty)
    val _           = myFixture.openFileInEditor(pending.getVirtualFile)
    val errors      = myFixture.doHighlighting().asScala.filter(_.getSeverity == HighlightSeverity.ERROR)
    assertTrue(s"capability findings must not become Scala errors: $errors", errors.isEmpty)
    val fileStubs   = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala.toVector
    assertTrue("fail-closed PSI must publish no declaration stubs", fileStubs.drop(1).isEmpty)
    val replacement = failure.get.copy(detail = "new current capability evidence")
    Scala3SyntaxCapabilityService.get(getProject).publish(pending.getVirtualFile, replacement)
    Scala3SyntaxCapabilityService
      .get(getProject)
      .resolve(
        pending.getVirtualFile,
        failure.get,
        failure.get.compilerIdentity.get
      )
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, failure.get.sourceDigest)
        .contains(replacement)
    )
    Scala3SyntaxCapabilityService
      .get(getProject)
      .resolve(pending.getVirtualFile, replacement, replacement.compilerIdentity.get)
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, replacement.sourceDigest)
        .isEmpty
    )
    Scala3SyntaxCapabilityService.get(getProject).publish(pending.getVirtualFile, replacement)
    val copy        = file.copy().asInstanceOf[com.intellij.psi.PsiFile]
    assertEquals(source, copy.getText)
    assertEquals(
      Vector(pending.getVirtualFile),
      Scala3SyntaxCapabilityService.get(getProject).currentFailures.flatMap(_.scope.file)
    )
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest("class Changed\n"))
        .isEmpty
    )

    assertUnsupportedExportsFailClosedWithoutPartialPersistence()
    assertUnsupportedGivenTypesFailClosedWithoutPartialPersistence()
    val document = FileDocumentManager.getInstance.getDocument(pending.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.setText("import a.b.c\n")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    val reparsed = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertNotNull(PsiTreeUtil.findChildOfType(reparsed, classOf[ScImportStmt]))
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest("import a.b.c\n"))
        .isEmpty
    )

    Scala3SyntaxCapabilityService.get(getProject).publish(pending.getVirtualFile, failure.get)
    Scala3ParserPreparationLifecycle.get(getProject).deactivate(getModule)
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, failure.get.sourceDigest)
        .isEmpty
    )

    val settings = MetallurgySettings(getProject)
    settings.setEnabled(getModule, enabled = false)
    settings.setGloballyEnabled(enabled = true)
    Scala3SyntaxCapabilityService.get(getProject).publish(pending.getVirtualFile, failure.get)
    try
      settings.setGloballyEnabled(enabled = false)
      assertTrue(
        Scala3SyntaxCapabilityService
          .get(getProject)
          .failureFor(pending.getVirtualFile, failure.get.sourceDigest)
          .isEmpty
      )
    finally
      settings.setGloballyEnabled(enabled = false)
      settings.setEnabled(getModule, enabled = true)

  def testReadyPhysicalImportsUseNativePsiAndReparseAndStubs(): Unit =
    val source            =
      """import a.b.c
        |import a.b.*
        |import a.b.{c}
        |import a.b.{c /* as */ as d}
        |import a.b.{c /* => */ => d}
        |import a.b.given
        |import a.b.given T
        |import a.b.{given, given T, *}
        |import a.b.++
        |import a.b.foo_+
        |import a.b.empty_?
        |import a.b.op_🚀
        |import a.b.`back-tick`
        |""".stripMargin
    val pending           = myFixture.addFileToProject("src/ImportCase.scala", source)
    val file              = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    val _                 = myFixture.openFileInEditor(pending.getVirtualFile)
    val highlightingLexer = new Scala3ParserSyntaxHighlighterFactory()
      .getSyntaxHighlighter(getProject, pending.getVirtualFile)
      .getHighlightingLexer
    highlightingLexer.start("given")
    assertEquals(ScalaTokenType.GivenKeyword, highlightingLexer.getTokenType)
    assertImports(file, source, "c")
    assertImports(file.copy().asInstanceOf[com.intellij.psi.PsiFile], source, "c")
    val statementPointer  = SmartPointerManager
      .getInstance(getProject)
      .createSmartPsiElementPointer(PsiTreeUtil.findChildOfType(file, classOf[ScImportStmt]))

    val document = FileDocumentManager.getInstance.getDocument(pending.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(11, 12, "changed")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    val reparsed = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertImports(reparsed, source.replaceFirst("a\\.b\\.c", "a.b.changed"), "changed")
    assertNotNull(statementPointer.getElement)
    assertEquals("import a.b.changed", statementPointer.getElement.getText)

    val groupedSource  = "package example\nimport a.b, c.d\nimport e.f\n"
    val groupedPending = myFixture.addFileToProject("src/PackageGroupedImportsCase.scala", groupedSource)
    val groupedFile    = PsiManager.getInstance(getProject).findFile(groupedPending.getVirtualFile)
    assertPackageGroupedImports(groupedFile, groupedSource, "c.d")
    assertPackageGroupedImports(groupedFile.copy().asInstanceOf[com.intellij.psi.PsiFile], groupedSource, "c.d")

    val groupedDocument = FileDocumentManager.getInstance.getDocument(groupedPending.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          groupedDocument.replaceString(
            groupedSource.indexOf("c.d"),
            groupedSource.indexOf("c.d") + 3,
            "changed.d"
          )
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(groupedDocument)
    assertPackageGroupedImports(
      PsiManager.getInstance(getProject).findFile(groupedPending.getVirtualFile),
      groupedSource.replace("c.d", "changed.d"),
      "changed.d"
    )

    val legacySource  = "import a.b._\nimport a.b.{c as _}\nimport a.b.{c => _}\n"
    val legacyPending = myFixture.addFileToProject("src/LegacyAndHiddenImportsCase.scala", legacySource)
    val legacyFile    = PsiManager.getInstance(getProject).findFile(legacyPending.getVirtualFile)
    assertLegacyAndHiddenImports(legacyFile, legacySource)
    assertLegacyAndHiddenImports(legacyFile.copy().asInstanceOf[com.intellij.psi.PsiFile], legacySource)
    assertRecursiveStablePathsPreserveNativePsiPersistenceAndEditIdentity()
    assertReadyPhysicalExportsUseNativePsiPersistenceIndexesAndReparse()

  private def assertPackageBodiesUseDirectNativePsiAndFailClosedOnRecovery(): Unit =
    val source  =
      """package braced { /* header */
        |  import alpha.braced.Member; export alpha.braced.Member
        |  import alpha.braced.Other; export alpha.braced.Other
        |  package nested { import alpha.nested.Member; export alpha.nested.Member /* nested tail */ }
        |  /* braced tail */
        |}
        |package outer:
        |  import alpha.outer.Member
        |  package inner:
        |    export alpha.inner.Member
        |  end inner
        |end outer
        |package empty { /* body trivia */ }
        |package first:
        |  import alpha.first.Member
        |  export alpha.first.Member
        |  import alpha.first.Other; export alpha.first.Other
        |  // trailing indented
        |package peer:
        |  export alpha.peer.Member
        |""".stripMargin
    val pending = myFixture.addFileToProject("src/PackageLayoutCase.scala", source)

    def assertLayout(file: com.intellij.psi.PsiFile): Unit =
      assertEquals(source, file.getText)
      assertEquals(source, PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector.map(_.getText).mkString)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
      val failure  = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(failure.toString, failure.isEmpty)
      val packages = PsiTreeUtil.findChildrenOfType(file, classOf[ScPackaging]).asScala.toVector
      val byFull   = packages.map(value => value.fullPackageName -> value).toMap
      val braced   = byFull("braced")
      val nested   = byFull("braced.nested")
      val outer    = byFull("outer")
      val inner    = byFull("outer.inner")
      val empty    = byFull("empty")
      val first    = byFull("first")
      val peer     = byFull("peer")
      val ends     = PsiTreeUtil.findChildrenOfType(file, classOf[ScEnd]).asScala.toVector

      assertEquals(7, packages.size)
      assertEquals(
        Vector(braced, outer, empty, first, peer),
        file.getChildren.collect { case value: ScPackaging => value }.toVector
      )
      assertSame(braced, nested.getParent)
      assertSame(outer, inner.getParent)
      assertEquals(Vector(nested), braced.packagings.toVector)
      assertEquals(Vector(inner), outer.packagings.toVector)
      assertTrue(packages.forall(_.isExplicit))
      assertTrue(Vector(braced, nested, empty).forall(_.isEnclosedByBraces))
      assertTrue(Vector(outer, inner, first, peer).forall(_.isEnclosedByColon))
      assertEquals(Vector("{", "{", ":", ":", "{", ":", ":"), packages.map(_.findExplicitMarker.get.getText))
      assertTrue(Vector(braced, nested, empty).forall(_.getLBrace.nonEmpty))
      assertTrue(
        Vector(braced, nested, empty)
          .map(packaging =>
            s"${packaging.fullPackageName}:${packaging.getTextRange}:${packaging.getRBrace.map(_.getText)}"
          )
          .mkString(", "),
        Vector(braced, nested, empty).forall(_.getRBrace.nonEmpty)
      )
      assertTrue(Vector(outer, inner, first, peer).forall(_.getColon.nonEmpty))
      packages.flatMap(_.getColon).foreach(value => assertEquals(ScalaTokenTypes.tCOLON, value.getNode.getElementType))
      assertEquals(
        Vector(
          ("braced", "", "braced"),
          ("nested", "braced", "braced.nested"),
          ("outer", "", "outer"),
          ("inner", "outer", "outer.inner"),
          ("empty", "", "empty"),
          ("first", "", "first"),
          ("peer", "", "peer")
        ),
        packages.map(packaging => (packaging.packageName, packaging.parentPackageName, packaging.fullPackageName))
      )
      assertEquals(Vector("inner", "outer"), ends.map(_.getName))
      ends.foreach: end =>
        assertEquals(s"end ${end.getName}", end.getText)
        assertEquals(ScalaElementType.END_STMT, end.getNode.getElementType)
        assertEquals(ScalaTokenType.EndKeyword, end.keyword.getNode.getElementType)
        assertEquals("end", end.keyword.getText)
        assertEquals(end.getName, end.tag.getText)
        assertSame(end, end.getReference)
        assertSame(end, end.getElement)
        assertEquals(end.tag.getTextRangeInParent, end.getRangeInElement)
        assertTrue(end.isSoft)
        assertEquals("ScEnd", end.getCanonicalText)
        assertSame(end.getParent, end.begin.get)
        assertSame(end, end.getParent.getLastChild)
        val resolved = end.resolve()
        assertNotNull(resolved)
        assertSame(end, resolved.getContext)
        assertFalse(end.isReferenceTo(resolved))
        assertSame(end, end.bindToElement(resolved))
      assertEquals(Vector(ends.find(_.getName == "inner").get), inner.end.toVector)
      assertEquals(Vector(ends.find(_.getName == "outer").get), outer.end.toVector)
      assertTrue(Vector(braced, nested, empty, first, peer).forall(_.end.isEmpty))

      val imports         = PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).asScala.toVector
      val exports         = PsiTreeUtil.findChildrenOfType(file, classOf[ScExportStmt]).asScala.toVector
      assertEquals(Vector(braced, braced, nested, outer, first, first), imports.map(_.getParent))
      assertEquals(Vector(braced, braced, nested, inner, first, first, peer), exports.map(_.getParent))
      assertEquals(
        Vector("braced", "braced", "braced.nested", "outer.inner", "first", "first", "peer"),
        exports.flatMap(_.topLevelQualifier)
      )
      assertEquals(Vector(imports(0), imports(1)), braced.getImportStatements.toVector)
      assertEquals(Vector(exports(0), exports(1)), braced.asInstanceOf[ScExportsHolder].getExportStatements.toVector)
      assertEquals(
        source.substring(source.indexOf("package first"), source.indexOf("package peer")),
        first.getText
      )
      val trailingComment = PsiTreeUtil.collectElements(
        file,
        element => element.getFirstChild == null && element.getText == "// trailing indented"
      )
      assertEquals(Vector(first), trailingComment.toVector.map(_.getParent))
      assertEquals(
        """ /* header */
          |  import alpha.braced.Member; export alpha.braced.Member
          |  import alpha.braced.Other; export alpha.braced.Other
          |  package nested { import alpha.nested.Member; export alpha.nested.Member /* nested tail */ }
          |  /* braced tail */
          |""".stripMargin,
        braced.bodyText
      )
      assertEquals(" import alpha.nested.Member; export alpha.nested.Member /* nested tail */ ", nested.bodyText)
      assertEquals(
        """
          |  import alpha.outer.Member
          |  package inner:
          |    export alpha.inner.Member
          |  end inner
          |end outer""".stripMargin,
        outer.bodyText
      )
      assertEquals("\n    export alpha.inner.Member\n  end inner", inner.bodyText)
      assertEquals(" /* body trivia */ ", empty.bodyText)
      assertEquals(
        """
          |  import alpha.first.Member
          |  export alpha.first.Member
          |  import alpha.first.Other; export alpha.first.Other
          |  // trailing indented
          |""".stripMargin,
        first.bodyText
      )
      assertEquals("\n  export alpha.peer.Member\n", peer.bodyText)
      assertTrue(empty.getImportStatements.isEmpty)
      assertTrue(empty.asInstanceOf[ScExportsHolder].getExportStatements.isEmpty)
      val semicolons      =
        PsiTreeUtil.collectElements(file, element => element.getFirstChild == null && element.getText == ";")
      assertEquals(Vector(braced, braced, nested, first), semicolons.toVector.map(_.getParent))
      Vector(
        "/* header */"      -> braced,
        "/* nested tail */" -> nested,
        "/* braced tail */" -> braced,
        "/* body trivia */" -> empty
      ).foreach: (text, owner) =>
        val comment =
          PsiTreeUtil.collectElements(file, element => element.getFirstChild == null && element.getText == text)
        assertEquals(s"$text parent", Vector(owner), comment.toVector.map(_.getParent))

      val composites = packages ++ ends ++ imports ++ exports
      composites.foreach: element =>
        assertSame(file, element.getContainingFile)
        assertSame(getProject, element.getProject)
        assertSame(element, element.getNode.getPsi)
        assertSame(element, element.getNavigationElement)
        assertEquals(
          element.getText,
          source.substring(element.getTextRange.getStartOffset, element.getTextRange.getEndOffset)
        )

      val stubs          = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala.toVector
      val packageStubs   = stubs.collect { case value: ScPackagingStub => value }
      assertEquals(7, packageStubs.size)
      assertEquals(
        Vector(
          ("braced", "", true),
          ("nested", "braced", true),
          ("outer", "", true),
          ("inner", "outer", true),
          ("empty", "", true),
          ("first", "", true),
          ("peer", "", true)
        ),
        packageStubs.map(stub => (stub.packageName, stub.parentPackageName, stub.isExplicit))
      )
      assertEquals(6, stubs.count(_.isInstanceOf[ScImportStmtStub]))
      assertEquals(7, stubs.count(_.isInstanceOf[ScExportStmtStub]))
      assertTrue(
        stubs.forall(stub =>
          (!stub.isInstanceOf[ScImportStmtStub] && !stub.isInstanceOf[ScExportStmtStub]) ||
            stub.getParentStub.isInstanceOf[ScPackagingStub]
        )
      )
      val enumerator     = new TestStringEnumerator
      val sink           = new ByteArrayOutputStream
      val output         = new StubOutputStream(sink, enumerator)
      val nestedStub     = packageStubs(1)
      ScalaElementType.PACKAGING.serialize(nestedStub, output)
      output.flush()
      val nestedCopy     = ScalaElementType.PACKAGING.deserialize(
        new StubInputStream(new ByteArrayInputStream(sink.toByteArray), enumerator),
        new PsiFileStubImpl(null)
      )
      assertEquals(nestedStub.packageName, nestedCopy.packageName)
      assertEquals(nestedStub.parentPackageName, nestedCopy.parentPackageName)
      assertEquals(nestedStub.isExplicit, nestedCopy.isExplicit)
      val packageFqns    = packageStubs.map: stub =>
        val values = Vector.newBuilder[String]
        ScalaElementType.PACKAGING.indexStub(
          stub,
          new IndexSink:
            override def occurrence[Psi <: PsiElement, K](_indexKey: StubIndexKey[K, Psi], value: K): Unit =
              assertSame(ScalaIndexKeys.PACKAGE_FQN_KEY, _indexKey)
              values += value.toString
        )
        values.result()
      assertEquals(
        Vector(
          Vector("braced"),
          Vector("braced.nested"),
          Vector("outer"),
          Vector("outer.inner"),
          Vector("empty"),
          Vector("first"),
          Vector("peer")
        ),
        packageFqns
      )
      val exportPackages = stubs
        .collect { case stub: ScExportStmtStub => stub }
        .map: stub =>
          val values = Vector.newBuilder[String]
          ScalaElementType.ExportStatement.indexStub(
            stub,
            new IndexSink:
              override def occurrence[Psi <: PsiElement, K](_indexKey: StubIndexKey[K, Psi], value: K): Unit =
                assertSame(ScalaIndexKeys.TOP_LEVEL_EXPORT_BY_PKG_KEY, _indexKey)
                values += value.toString
          )
          values.result()
      assertEquals(
        Vector("braced", "braced", "braced.nested", "outer.inner", "first", "first", "peer").map(Vector(_)),
        exportPackages
      )
    val file                                               = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertLayout(file)
    assertLayout(file.copy().asInstanceOf[com.intellij.psi.PsiFile])
    val chainedSource                                      =
      "package qualified.name; package chained\nimport alpha.chained.Member\n"
    val chainedPending                                     = myFixture.addFileToProject("src/ChainedPackageLayout.scala", chainedSource)
    val chainedFile                                        = PsiManager.getInstance(getProject).findFile(chainedPending.getVirtualFile)
    val chainedPackages                                    = PsiTreeUtil
      .findChildrenOfType(chainedFile, classOf[ScPackaging])
      .asScala
      .toVector
    val chainedImport                                      = PsiTreeUtil.findChildOfType(chainedFile, classOf[ScImportStmt])
    assertEquals(chainedSource, chainedFile.getText)
    assertEquals(Vector("qualified.name", "qualified.name.chained"), chainedPackages.map(_.fullPackageName))
    assertEquals(
      Vector(chainedPackages.head),
      chainedFile.getChildren.collect { case value: ScPackaging => value }.toVector
    )
    assertSame(chainedPackages.head, chainedPackages(1).getParent)
    assertSame(chainedPackages(1), chainedImport.getParent)
    assertTrue(chainedPackages.forall(!_.isExplicit))
    assertTrue(chainedPackages.forall(_.findExplicitMarker.isEmpty))
    val pointer                                            = SmartPointerManager
      .getInstance(getProject)
      .createSmartPsiElementPointer(
        PsiTreeUtil.findChildrenOfType(file, classOf[ScPackaging]).asScala.find(_.fullPackageName == "outer.inner").get
      )
    val document                                           = FileDocumentManager.getInstance.getDocument(pending.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.setText(
          source.replace(
            "package empty { /* body trivia */ }",
            "package empty:\n  import alpha.empty.Member\nend empty"
          )
        )
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertNotNull(pointer.getElement)
    assertEquals("outer.inner", pointer.getElement.fullPackageName)
    val edited                                             = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertEquals(3, PsiTreeUtil.findChildrenOfType(edited, classOf[ScEnd]).size)

    val transitionSource                          = "package transition\nimport alpha.transition.Member\n"
    val transitionPending                         = myFixture.addFileToProject("src/PackageLayoutTransition.scala", transitionSource)
    val transitionDocument                        = FileDocumentManager.getInstance.getDocument(transitionPending.getVirtualFile)
    def transitionPackages(): Vector[ScPackaging] =
      PsiTreeUtil
        .findChildrenOfType(
          PsiManager.getInstance(getProject).findFile(transitionPending.getVirtualFile),
          classOf[ScPackaging]
        )
        .asScala
        .toVector
    def replaceTransition(text: String): Unit     =
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit = transitionDocument.setText(text)
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(transitionDocument)
    val initialTransition                         = transitionPackages().head
    val transitionPointer                         = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(initialTransition)
    assertFalse(initialTransition.isExplicit)
    assertTrue(initialTransition.findExplicitMarker.isEmpty)
    replaceTransition(
      "package transition { import alpha.transition.Member; package nested { export alpha.nested.Member } }\n"
    )
    val bracedTransition                          = transitionPackages()
    assertEquals(Vector("transition", "transition.nested"), bracedTransition.map(_.fullPackageName))
    assertTrue(bracedTransition.forall(_.isEnclosedByBraces))
    assertSame(bracedTransition.head, bracedTransition(1).getParent)
    assertNotNull(transitionPointer.getElement)
    replaceTransition(
      """package transition:
        |  import alpha.transition.Member
        |  package nested:
        |    export alpha.nested.Member
        |  end nested
        |end transition
        |""".stripMargin
    )
    val colonTransition                           = transitionPackages()
    assertEquals(Vector("transition", "transition.nested"), colonTransition.map(_.fullPackageName))
    assertTrue(colonTransition.forall(_.isEnclosedByColon))
    assertEquals(2, PsiTreeUtil.findChildrenOfType(colonTransition.head.getContainingFile, classOf[ScEnd]).size)
    assertNotNull(transitionPointer.getElement)
    replaceTransition(transitionSource)
    val finalTransition                           = transitionPackages()
    assertEquals(1, finalTransition.size)
    assertFalse(finalTransition.head.isExplicit)
    assertNotNull(transitionPointer.getElement)

    Vector(
      "package broken { import a.b\n",
      "package broken import a.b\n",
      "package broken:\nimport a.b\n",
      "package a; import b.c; package d\n",
      "package a:\n  import b.c\nend\n",
      "package a:\n  import b.c\nend wrong\n",
      "package unsupported { class Parent(value: Int); import a.b; class Definition extends Parent(1) }\n",
      "package unsupported:\n  class Parent(value: Int)\n  import a.b\n  object Template extends Parent(1)\n",
      "package unsupported:\n  extension (value: Int) def increment = value + 1\n"
    ).zipWithIndex.foreach: (invalid, index) =>
      val recovered = myFixture.addFileToProject(s"src/RecoveredPackageLayout$index.scala", invalid)
      val psi       = PsiManager.getInstance(getProject).findFile(recovered.getVirtualFile).asInstanceOf[PsiFileImpl]
      assertEquals(invalid, psi.getText)
      assertTrue(PsiTreeUtil.findChildrenOfType(psi, classOf[ScPackaging]).isEmpty)
      assertTrue(PsiTreeUtil.findChildrenOfType(psi, classOf[ScImportStmt]).isEmpty)
      assertTrue(PsiTreeUtil.findChildrenOfType(psi, classOf[ScExportStmt]).isEmpty)
      assertTrue(PsiTreeUtil.findChildrenOfType(psi, classOf[ScEnd]).isEmpty)
      assertTrue(psi.calcStubTree.getPlainList.asScala.drop(1).isEmpty)
      assertTrue(
        Scala3SyntaxCapabilityService
          .get(getProject)
          .failureFor(recovered.getVirtualFile, ParserSyntaxSnapshot.digest(invalid))
          .nonEmpty
      )

  private def assertReadyPhysicalExportsUseNativePsiPersistenceIndexesAndReparse(): Unit =
    val source     =
      """package exportcase; /* header trivia */
        |import scala.math.Ordering
        |export scala.Predef.identity
        |export scala.collection.immutable.List.apply
        |export scala.Predef.*
        |export scala.Predef.{assert, identity}
        |export scala.Predef.{identity as renamedIdentity}
        |export scala.Predef.{identity => legacyIdentity}
        |export scala.Predef.{assert as _, *}
        |export scala.math.Ordering.given
        |export scala.Predef.{given DummyImplicit}
        |export scala.math.Ordering.{given Ordering[Int]}
        |export scala.Predef.identity
        |""".stripMargin
    val pending    = myFixture.addFileToProject("src/ExportCase.scala", source)
    val file       = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertExports(file, source, "exportcase", 11)
    assertExports(file.copy().asInstanceOf[com.intellij.psi.PsiFile], source, "exportcase", 11)
    IndexingTestUtil.waitUntilIndexesAreReady(getProject)
    assertEquals(
      Vector(pending.getVirtualFile),
      indexedExports("exportcase").map(_.getContainingFile.getVirtualFile).distinct
    )
    val aliasFiles = StubIndex
      .getElements(
        ScalaIndexKeys.ALIASED_IMPORT_KEY,
        "identity",
        getProject,
        GlobalSearchScope.projectScope(getProject),
        classOf[ScImportSelector]
      )
      .asScala
      .map(_.getContainingFile.getVirtualFile)
      .toSet
    assertTrue(aliasFiles.contains(pending.getVirtualFile))
    val first      = PsiTreeUtil.findChildOfType(file, classOf[ScExportStmt])
    val pointer    = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(first)
    val document   = FileDocumentManager.getInstance.getDocument(pending.getVirtualFile)
    val direct     = "export scala.Predef.identity"
    val braced     = "export scala.Predef.{identity, assert}"
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val start = document.getText.indexOf(direct)
          document.replaceString(start, start + direct.length, braced)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    val bracedText = source.replaceFirst(direct, braced)
    val reparsed   = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertExports(reparsed, bracedText, "exportcase", 11)
    assertNotNull(pointer.getElement)
    assertEquals(braced, pointer.getElement.getText)

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val start = document.getText.indexOf("identity, assert")
          document.replaceString(start, start + "identity, assert".length, "assert")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    val selectorChanged = bracedText.replaceFirst("identity, assert", "assert")
    assertExports(
      PsiManager.getInstance(getProject).findFile(pending.getVirtualFile),
      selectorChanged,
      "exportcase",
      11
    )

    val defaultSource  = "export scala.Predef.identity\nexport scala.Predef.{assert as exposedAssert}\n"
    val defaultPending = myFixture.addFileToProject("src/DefaultExportCase.scala", defaultSource)
    val defaultFile    = PsiManager.getInstance(getProject).findFile(defaultPending.getVirtualFile)
    assertExports(defaultFile, defaultSource, "", 2)
    IndexingTestUtil.waitUntilIndexesAreReady(getProject)
    assertEquals(
      Vector(defaultPending.getVirtualFile),
      indexedExports("").map(_.getContainingFile.getVirtualFile).distinct
    )

  private def assertUnsupportedExportsFailClosedWithoutPartialPersistence(): Unit =
    val sources = Vector(
      "object Owner:\n  export scala.Predef.identity\n",
      "extension (value: Int)\n  export scala.Predef.identity\n",
      "export scala.Predef.{given (A, B)}\n"
    )
    sources.zipWithIndex.foreach: (source, index) =>
      val pending = myFixture.addFileToProject(s"src/UnsupportedExportCase$index.scala", source)
      val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
      assertEquals(source, file.getText)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScExportStmt]).isEmpty)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
      assertTrue(file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala.drop(1).isEmpty)
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(failure.toString, failure.nonEmpty)

  private def assertUnsupportedGivenTypesFailClosedWithoutPartialPersistence(): Unit =
    val types   = Vector(
      "?",
      "(A, B)",
      "A => B",
      "A match { case _ => B }",
      "A { type X }",
      "A @ann",
      "(x: A) => x.B",
      "new A"
    )
    val sources = types.flatMap(bound => Vector(s"import a.b.given $bound\n", s"export a.b.{given $bound}\n"))
    sources.zipWithIndex.foreach: (source, index) =>
      val pending      = myFixture.addFileToProject(s"src/UnsupportedGivenTypeCase$index.scala", source)
      val file         = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
      assertEquals(source, file.getText)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).isEmpty)
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScExportStmt]).isEmpty)
      val parserErrors = PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).asScala.toVector
      assertTrue(
        parserErrors.forall(error =>
          error.getTextRange.getStartOffset >= 0 && error.getTextRange.getStartOffset <= source.length &&
            error.getTextRange.getEndOffset <= source.length && error.getErrorDescription.nonEmpty
        )
      )
      assertEquals(
        parserErrors.size,
        parserErrors.map(error => error.getTextRange -> error.getErrorDescription).distinct.size
      )
      assertTrue(file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala.drop(1).isEmpty)
      val failure      = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(s"$source: $failure", failure.nonEmpty)

  def testReadyPhysicalPackageUsesNativePsiAndReparsesAndStubs(): Unit =
    assertPackageBodiesUseDirectNativePsiAndFailClosedOnRecovery()
    val source     = "package example.syntax\n"
    val installed  = Scala3ParserPreparationLifecycle.get(getProject)
    installed.dispose()
    val preparer   = new DeferredPreparer
    var files      = Vector.empty[VirtualFile]
    val activation = new PlatformRecordingActivation(getProject)
    val lifecycle  = new Scala3ParserPreparationLifecycle(
      getProject,
      preparer,
      _ => files,
      activation
    )
    ServiceContainerUtil.replaceService(
      getProject,
      classOf[Scala3ParserPreparationLifecycle],
      lifecycle,
      getTestRootDisposable
    )
    val _          = lifecycle.prepare(getModule)
    val pending    = myFixture.addFileToProject("src/PackageCase.scala", source)
    files = Vector(pending.getVirtualFile)
    preparer.complete(0, new TestParserBridge(Some((bridge, request) => uncoveredSnapshot(request, bridge))))
    awaitReady(lifecycle, "fail-closed package parser activation")
    val failClosed = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile).asInstanceOf[PsiFileImpl]
    assertEquals(source, failClosed.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(failClosed, classOf[ScPackaging]).isEmpty)
    assertTrue(failClosed.calcStubTree.getPlainList.asScala.drop(1).isEmpty)
    IndexingTestUtil.waitUntilIndexesAreReady(getProject)
    assertTrue(indexedPackages("example.syntax").isEmpty)

    val _ = lifecycle.prepare(getModule)
    preparer.complete(1, new TestParserBridge(Some((bridge, request) => packageSnapshot(request, bridge))))
    awaitReady(lifecycle, "covered package parser reactivation")
    assertEquals(3, activation.batchCount)
    assertFalse(failClosed.isValid)
    IndexingTestUtil.waitUntilIndexesAreReady(getProject)
    assertPlan(lifecycle.parserFor(getModule).get, pending.getVirtualFile.getUrl, source)

    val file = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertPackage(file, source, "syntax")
    assertEquals(
      Vector(pending.getVirtualFile),
      indexedPackages("example.syntax").map(_.getContainingFile.getVirtualFile).distinct
    )
    val copy = file.copy().asInstanceOf[com.intellij.psi.PsiFile]
    assertPackage(copy, source, "syntax")

    val document = FileDocumentManager.getInstance.getDocument(pending.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(16, 22, "changed")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    val reparsed = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    assertPackage(reparsed, "package example.changed\n", "changed")

  private def assertExports(
      file: com.intellij.psi.PsiFile,
      text: String,
      packageName: String,
      expectedStatements: Int
  ): Unit =
    assertEquals(text, file.getText)
    val leaves                                                                  = PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector
    assertEquals(text, leaves.map(_.getText).mkString)
    assertFalse(leaves.exists(_.getText.isEmpty))
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val statements                                                              = PsiTreeUtil.findChildrenOfType(file, classOf[ScExportStmt]).asScala.toVector
    val expressions                                                             = statements.flatMap(_.importExprs)
    val capability                                                              = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(text))
    assertTrue(capability.toString, capability.isEmpty)
    assertEquals(expectedStatements, statements.size)
    assertEquals(expectedStatements, expressions.size)
    assertTrue(statements.forall(_.isTopLevel))
    assertTrue(statements.forall(_.topLevelQualifier == Some(packageName)))
    assertEquals(
      Vector.fill(expectedStatements)("org.jetbrains.plugins.scala.lang.psi.impl.toplevel.imports.ScExportStmtImpl"),
      statements.map(_.getClass.getName)
    )
    statements.foreach: statement =>
      assertEquals(ScalaElementType.ExportStatement, statement.getNode.getElementType)
      assertSame(file, statement.getContainingFile)
      assertSame(getProject, statement.getProject)
      assertSame(statement, statement.getNode.getPsi)
      assertSame(statement, statement.getNavigationElement)
      assertEquals(
        statement.importExprs,
        statement.getChildren.toVector.collect {
          case expression: org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScImportExpr => expression
        }
      )
    statements.zip(expressions).foreach((statement, expression) => assertSame(statement, expression.getParent))
    expressions.foreach: expression =>
      expression.reference.foreach: reference =>
        assertSame(expression, reference.getParent)
        var current = Option(reference)
        while current.nonEmpty do
          val value = current.get
          assertEquals(ScalaElementType.REFERENCE, value.getNode.getElementType)
          value.qualifier.foreach(qualifier => assertSame(value, qualifier.getParent))
          current = value.qualifier
    val selectorSets                                                            = expressions.flatMap(_.selectorSet)
    val selectors                                                               = expressions.flatMap(_.selectors)
    selectorSets.foreach(set => assertTrue(expressions.contains(set.getParent)))
    selectors.foreach: selector =>
      assertTrue(selectorSets.contains(selector.getParent))
      assertSame(selector.parentImportExpression, selector.getParent.getParent)
    assertTrue(selectors.exists(_.isAliasedImport))
    val stableReferences                                                        = PsiTreeUtil.findChildrenOfType(file, classOf[ScStableCodeReference]).asScala.toVector
    val simpleTypes                                                             = PsiTreeUtil.findChildrenOfType(file, classOf[ScSimpleTypeElement]).asScala.toVector
    val appliedTypes                                                            = PsiTreeUtil.findChildrenOfType(file, classOf[ScParameterizedTypeElement]).asScala.toVector
    val composites                                                              = statements ++ expressions ++ selectorSets ++ selectors ++ stableReferences ++ simpleTypes ++
      appliedTypes
    composites.foreach: element =>
      assertEquals(
        element.getText,
        text.substring(element.getTextRange.getStartOffset, element.getTextRange.getEndOffset)
      )
      assertSame(file, element.getContainingFile)
      assertSame(getProject, element.getProject)
      assertSame(element, element.getNode.getPsi)
      assertSame(element, element.getNavigationElement)
    if packageName.nonEmpty then
      assertTrue(expressions.exists(_.hasWildcardSelector))
      assertTrue(expressions.exists(_.hasGivenSelector))
      assertTrue(selectors.exists(_.isWildcardSelector))
      assertTrue(selectors.exists(_.isGivenSelector))
      assertTrue(simpleTypes.exists(_.getText == "DummyImplicit"))
      assertTrue(appliedTypes.exists(_.getText == "Ordering[Int]"))
    val stubs                                                                   = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala
    def hasExportAncestor(stub: com.intellij.psi.stubs.StubElement[?]): Boolean =
      var parent = stub.getParentStub
      while parent != null && !parent.isInstanceOf[ScExportStmtStub] do parent = parent.getParentStub
      parent != null
    val statementStubs                                                          = stubs.collect { case value: ScExportStmtStub => value }
    val expressionStubs                                                         = stubs.collect { case value: ScImportExprStub if hasExportAncestor(value) => value }
    val selectorSetStubs                                                        = stubs.collect { case value: ScImportSelectorsStub if hasExportAncestor(value) => value }
    val selectorStubs                                                           = stubs.collect { case value: ScImportSelectorStub if hasExportAncestor(value) => value }
    assertEquals(expectedStatements, statementStubs.size)
    assertEquals(expectedStatements, expressionStubs.size)
    assertEquals(selectorSets.size, selectorSetStubs.size)
    assertEquals(selectors.size, selectorStubs.size)
    assertTrue(statementStubs.forall(_.isTopLevel))
    assertTrue(statementStubs.forall(_.topLevelQualifier == Some(packageName)))
    assertEquals(statements.map(_.getText), statementStubs.map(_.importText).toVector)
    assertTrue(selectorStubs.exists(_.isAliasedImport))
    if packageName.nonEmpty then
      assertTrue(expressionStubs.exists(_.hasWildcardSelector))
      assertTrue(expressionStubs.exists(_.hasGivenSelector))
      assertTrue(selectorStubs.exists(_.isGivenSelector))

    val enumerator                                          = new TestStringEnumerator
    def bytes(write: StubOutputStream => Unit): Array[Byte] =
      val sink   = new ByteArrayOutputStream
      val output = new StubOutputStream(sink, enumerator)
      write(output)
      output.flush()
      sink.toByteArray
    val statementCopy                                       = ScalaElementType.ExportStatement.deserialize(
      new StubInputStream(
        new ByteArrayInputStream(bytes(ScalaElementType.ExportStatement.serialize(statementStubs.head, _))),
        enumerator
      ),
      new PsiFileStubImpl(null)
    )
    assertEquals(statementStubs.head.importText, statementCopy.importText)
    assertEquals(statementStubs.head.isTopLevel, statementCopy.isTopLevel)
    assertEquals(statementStubs.head.topLevelQualifier, statementCopy.topLevelQualifier)
    val indexedPackages                                     = Vector.newBuilder[String]
    statementStubs.foreach: stub =>
      ScalaElementType.ExportStatement.indexStub(
        stub,
        new IndexSink:
          override def occurrence[Psi <: PsiElement, K](_indexKey: StubIndexKey[K, Psi], value: K): Unit =
            assertSame(ScalaIndexKeys.TOP_LEVEL_EXPORT_BY_PKG_KEY, _indexKey)
            indexedPackages += value.toString
      )
    assertEquals(Vector.fill(expectedStatements)(packageName), indexedPackages.result())
    val indexedAliases                                      = Vector.newBuilder[String]
    selectorStubs.foreach: stub =>
      ScalaElementType.IMPORT_SELECTOR.indexStub(
        stub,
        new IndexSink:
          override def occurrence[Psi <: PsiElement, K](_indexKey: StubIndexKey[K, Psi], value: K): Unit =
            assertSame(ScalaIndexKeys.ALIASED_IMPORT_KEY, _indexKey)
            indexedAliases += value.toString
      )
    assertTrue(indexedAliases.result().nonEmpty)

    Option(PsiTreeUtil.findChildOfType(file, classOf[ScPackaging])) match
      case Some(packaging) =>
        assertEquals(packageName, packaging.packageName)
        statements.foreach(statement => assertSame(packaging, statement.getParent))
        val topStatements = packaging.getChildren.toVector.collect {
          case statement: ScImportStmt => statement.getText
          case statement: ScExportStmt => statement.getText
        }
        assertEquals("import scala.math.Ordering", topStatements.head)
        assertEquals(statements.map(_.getText), topStatements.tail)
      case None            => statements.foreach(statement => assertSame(file, statement.getParent))

  private def assertPackage(file: com.intellij.psi.PsiFile, text: String, name: String): Unit =
    assertEquals(text, file.getText)
    val leaves    = PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector.map(_.getText)
    assertEquals(Vector("package", " ", "example", ".", name, "\n"), leaves)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val packaging = PsiTreeUtil.findChildOfType(file, classOf[ScPackaging])
    assertNotNull(packaging)
    val reference = packaging.reference.get
    val qualifier = reference.qualifier.get
    assertEquals(
      "org.jetbrains.plugins.scala.lang.psi.impl.toplevel.packaging.ScPackagingImpl",
      packaging.getClass.getName
    )
    assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.base.ScStableCodeReferenceImpl", reference.getClass.getName)
    assertEquals(ScalaElementType.PACKAGING, packaging.getNode.getElementType)
    assertEquals(ScalaElementType.REFERENCE, reference.getNode.getElementType)
    assertEquals(ScalaElementType.REFERENCE, qualifier.getNode.getElementType)
    assertEquals("package", packaging.keyword.getText)
    assertEquals(s"example.$name", packaging.packageName)
    assertEquals("", packaging.parentPackageName)
    assertEquals(name, reference.refName)
    assertEquals("example", qualifier.refName)
    assertSame(packaging, reference.getParent)
    assertSame(reference, qualifier.getParent)
    assertSame(file, packaging.getContainingFile)
    Vector(packaging, reference, qualifier).foreach: element =>
      assertSame(getProject, element.getProject)
      assertSame(element, element.getNode.getPsi)
      assertSame(element, element.getNavigationElement)
    assertEquals(
      Vector(reference),
      packaging.getChildren.toVector.collect { case value: ScStableCodeReference => value }
    )
    assertEquals(
      Vector(qualifier),
      reference.getChildren.toVector.collect { case value: ScStableCodeReference => value }
    )
    val stubTree  = file.asInstanceOf[PsiFileImpl].calcStubTree
    val stub      = stubTree.getPlainList.asScala.collectFirst { case value: ScPackagingStub => value }.orNull
    assertNotNull(stub)
    assertEquals(s"example.$name", stub.packageName)
    assertEquals("", stub.parentPackageName)
    assertFalse(stub.isExplicit)
    assertEquals(ScalaElementType.PACKAGING, stub.getElementType)
    val indexed   = Vector.newBuilder[String]
    ScalaElementType.PACKAGING.indexStub(
      stub,
      new IndexSink:
        override def occurrence[Psi <: PsiElement, K](_indexKey: StubIndexKey[K, Psi], value: K): Unit =
          indexed += value.toString
    )
    assertEquals(Vector(s"example.$name", "example"), indexed.result())

  private def assertPackagePath(
      file: com.intellij.psi.PsiFile,
      text: String,
      segments: Vector[String],
      persistence: Boolean
  ): Unit =
    assertEquals(text, file.getText)
    val leaves    = PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector
    assertEquals(text, leaves.map(_.getText).mkString)
    assertFalse(leaves.exists(_.getText.isEmpty))
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val packaging = PsiTreeUtil.findChildOfType(file, classOf[ScPackaging])
    assertNotNull(packaging)
    val reference = packaging.reference.get
    assertStablePath(reference, segments)
    assertEquals(segments.mkString("."), packaging.packageName)
    assertEquals("", packaging.parentPackageName)
    assertEquals(
      Vector(reference),
      packaging.getChildren.toVector.collect { case value: ScStableCodeReference => value }
    )
    assertSame(file, packaging.getContainingFile)
    assertSame(getProject, packaging.getProject)
    assertSame(packaging, packaging.getNode.getPsi)
    assertSame(packaging, packaging.getNavigationElement)
    if persistence then
      val stub    = file
        .asInstanceOf[PsiFileImpl]
        .calcStubTree
        .getPlainList
        .asScala
        .collectFirst { case value: ScPackagingStub => value }
        .orNull
      assertNotNull(stub)
      assertEquals(segments.mkString("."), stub.packageName)
      assertEquals("", stub.parentPackageName)
      assertFalse(stub.isExplicit)
      val indexed = Vector.newBuilder[String]
      ScalaElementType.PACKAGING.indexStub(
        stub,
        new IndexSink:
          override def occurrence[Psi <: PsiElement, K](_indexKey: StubIndexKey[K, Psi], value: K): Unit =
            assertSame(ScalaIndexKeys.PACKAGE_FQN_KEY, _indexKey)
            indexed += value.toString
      )
      assertEquals(
        segments.indices.reverse
          .map(index => segments.take(index + 1).map(_.stripPrefix("`").stripSuffix("`")).mkString("."))
          .toVector,
        indexed.result()
      )

  private def assertImportPaths(
      file: com.intellij.psi.PsiFile,
      text: String,
      qualifierSegments: Vector[String]
  ): Unit =
    assertEquals(text, file.getText)
    val leaves         = PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector
    assertEquals(text, leaves.map(_.getText).mkString)
    assertFalse(leaves.exists(_.getText.isEmpty))
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val statements     = PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).asScala.toVector
    val expressions    = statements.flatMap(_.importExprs)
    val qualifierText  = qualifierSegments.mkString(".")
    val finalNames     = Vector(
      Some("ordinary"),
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      Some("++"),
      Some("λ"),
      Some("`back-tick`")
    )
    val referenceTexts = finalNames.map(_.fold(qualifierText)(name => s"$qualifierText.$name"))
    assertEquals(12, statements.size)
    assertEquals(12, expressions.size)
    assertEquals(referenceTexts, expressions.flatMap(_.reference).map(_.getText))
    expressions
      .zip(finalNames)
      .foreach: (expression, finalName) =>
        val reference = expression.reference.get
        assertStablePath(reference, qualifierSegments ++ finalName)
        assertSame(expression, reference.getParent)
    statements.zip(expressions).foreach((statement, expression) => assertSame(statement, expression.getParent))
    val selectorSets   = expressions.flatMap(_.selectorSet)
    val selectors      = expressions.flatMap(_.selectors)
    assertEquals(7, selectorSets.size)
    assertEquals(9, selectors.size)
    assertEquals(3, selectors.count(_.isAliasedImport))
    assertEquals(4, selectors.count(_.isGivenSelector))
    assertEquals(1, selectors.count(_.isWildcardSelector))
    assertEquals(Vector("Box[Int]", "Box[Int]"), selectors.flatMap(_.givenTypeElement).map(_.getText))
    val capability     = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(text))
    assertTrue(capability.toString, capability.isEmpty)

    val stubs           = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala
    val expressionStubs = stubs.collect { case value: ScImportExprStub => value }
    val selectorStubs   = stubs.collect { case value: ScImportSelectorStub => value }
    assertEquals(12, stubs.count(_.isInstanceOf[ScImportStmtStub]))
    assertEquals(12, expressionStubs.size)
    assertEquals(7, stubs.count(_.isInstanceOf[ScImportSelectorsStub]))
    assertEquals(9, selectorStubs.size)
    assertEquals(referenceTexts.map(Some(_)), expressionStubs.map(_.referenceText).toVector)
    val enumerator      = new TestStringEnumerator
    val sink            = new ByteArrayOutputStream
    val output          = new StubOutputStream(sink, enumerator)
    ScalaElementType.IMPORT_EXPR.serialize(expressionStubs(8), output)
    output.flush()
    val expressionCopy  = ScalaElementType.IMPORT_EXPR.deserialize(
      new StubInputStream(new ByteArrayInputStream(sink.toByteArray), enumerator),
      new PsiFileStubImpl(null)
    )
    assertEquals(expressionStubs(8).referenceText, expressionCopy.referenceText)
    assertEquals(expressionStubs(8).hasWildcardSelector, expressionCopy.hasWildcardSelector)
    assertEquals(expressionStubs(8).hasGivenSelector, expressionCopy.hasGivenSelector)
    val indexed         = Vector.newBuilder[String]
    selectorStubs.foreach: stub =>
      ScalaElementType.IMPORT_SELECTOR.indexStub(
        stub,
        new IndexSink:
          override def occurrence[Psi <: PsiElement, K](_indexKey: StubIndexKey[K, Psi], value: K): Unit =
            assertSame(ScalaIndexKeys.ALIASED_IMPORT_KEY, _indexKey)
            indexed += value.toString
      )
    assertEquals(Vector.fill(4)("ordinary"), indexed.result())

  private def assertStablePath(reference: ScStableCodeReference, segments: Vector[String]): Unit =
    var current = Option(reference)
    segments.indices.reverse.foreach: index =>
      val value        = current.getOrElse(throw new AssertionError(s"missing stable path segment ${segments(index)}"))
      val expectedText = segments.take(index + 1).mkString(".")
      assertEquals(expectedText, value.getText)
      assertEquals(segments(index), value.refName)
      assertEquals(segments(index), value.nameId.getText)
      assertEquals(ScalaElementType.REFERENCE, value.getNode.getElementType)
      assertEquals(value.getTextRange.getStartOffset + expectedText.length, value.getTextRange.getEndOffset)
      assertEquals(
        value.getTextRange.getEndOffset - segments(index).length,
        value.nameId.getTextRange.getStartOffset
      )
      assertSame(getProject, value.getProject)
      assertSame(value, value.getNode.getPsi)
      assertSame(value, value.getNavigationElement)
      val qualifier    = value.qualifier
      assertEquals(
        qualifier.toVector,
        value.getChildren.toVector.collect { case child: ScStableCodeReference => child }
      )
      qualifier.foreach(child => assertSame(value, child.getParent))
      current = qualifier
    assertTrue(current.isEmpty)

  private def assertPackageGroupedImports(
      file: com.intellij.psi.PsiFile,
      text: String,
      secondExpression: String
  ): Unit =
    assertEquals(text, file.getText)
    assertEquals(text, PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector.map(_.getText).mkString)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val packaging   = PsiTreeUtil.findChildOfType(file, classOf[ScPackaging])
    val statements  = PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).asScala.toVector
    val expressions = statements.flatMap(_.importExprs)
    val capability  = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(text))
    assertNotNull(capability.map(_.toString).orNull, packaging)
    assertEquals(Vector("import a.b, " + secondExpression, "import e.f"), statements.map(_.getText))
    assertEquals(Vector("a.b", secondExpression, "e.f"), expressions.map(_.getText))
    assertEquals(Vector(2, 1), statements.map(_.importExprs.size))
    statements.foreach(statement => assertSame(packaging, statement.getParent))
    statements
      .zip(Vector(expressions.take(2), expressions.drop(2)))
      .foreach: (statement, children) =>
        children.foreach(expression => assertSame(statement, expression.getParent))
    (Vector(packaging) ++ statements ++ expressions).foreach: element =>
      assertSame(file, element.getContainingFile)
      assertSame(getProject, element.getProject)
      assertSame(element, element.getNode.getPsi)
      assertSame(element, element.getNavigationElement)
    val stubs       = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala
    assertEquals(1, stubs.count(_.isInstanceOf[ScPackagingStub]))
    assertEquals(2, stubs.count(_.isInstanceOf[ScImportStmtStub]))
    assertEquals(3, stubs.count(_.isInstanceOf[ScImportExprStub]))

  private def assertImports(file: com.intellij.psi.PsiFile, text: String, plainName: String): Unit =
    assertEquals(text, file.getText)
    val leaves         = PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector
    assertEquals(text, leaves.map(_.getText).mkString)
    assertFalse(leaves.exists(_.getText.isEmpty))
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val statements     = PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).asScala.toVector
    val expressions    = statements.flatMap(_.importExprs)
    val selectorSets   = expressions.flatMap(_.selectorSet)
    val selectors      = selectorSets.flatMap(_.selectors)
    val failure        =
      Scala3SyntaxCapabilityService.get(getProject).failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(text))
    assertTrue(failure.toString, failure.isEmpty)
    assertEquals(13, statements.size)
    assertEquals(13, expressions.size)
    assertEquals(6, selectorSets.size)
    assertEquals(8, selectors.size)
    assertEquals(
      Vector(
        "{c}",
        "{c /* as */ as d}",
        "{c /* => */ => d}",
        "given",
        "given T",
        "{given, given T, *}"
      ),
      selectorSets.map(_.getText)
    )
    assertEquals(
      Vector(
        s"a.b.$plainName",
        "a.b.*",
        "a.b.{c}",
        "a.b.{c /* as */ as d}",
        "a.b.{c /* => */ => d}",
        "a.b.given",
        "a.b.given T",
        "a.b.{given, given T, *}",
        "a.b.++",
        "a.b.foo_+",
        "a.b.empty_?",
        "a.b.op_🚀",
        "a.b.`back-tick`"
      ),
      expressions.map(_.getText)
    )
    assertEquals(
      Vector("++", "foo_+", "empty_?", "op_🚀", "`back-tick`"),
      expressions.takeRight(5).flatMap(_.reference).map(_.refName)
    )
    val plain          = expressions.head
    val wildcard       = expressions(1)
    val boundedGiven   = expressions(6)
    val mixed          = expressions(7)
    assertEquals(Some(s"a.b.$plainName"), plain.reference.map(_.getText))
    assertEquals(Some("a.b"), plain.qualifier.map(_.getText))
    assertTrue(plain.selectors.isEmpty)
    assertFalse(plain.hasWildcardSelector)
    assertFalse(plain.hasGivenSelector)
    assertEquals(Some("a.b"), wildcard.reference.map(_.getText))
    assertEquals(Some("a.b"), wildcard.qualifier.map(_.getText))
    assertTrue(wildcard.selectorSet.isEmpty)
    assertTrue(wildcard.hasWildcardSelector)
    assertEquals(Some("*"), wildcard.wildcardElement.map(_.getText))
    assertTrue(boundedGiven.hasGivenSelector)
    assertTrue(mixed.hasGivenSelector)
    assertTrue(mixed.hasWildcardSelector)
    assertEquals(Some("*"), mixed.wildcardElement.map(_.getText))
    val aliases        = selectors.filter(_.isAliasedImport)
    val givenSelectors = selectors.filter(_.isGivenSelector)
    val wildcardInSet  = selectors.filter(_.isWildcardSelector)
    assertEquals(2, aliases.size)
    assertEquals(Vector(Some("d"), Some("d")), aliases.map(_.importedName))
    assertEquals(Vector(Some("d"), Some("d")), aliases.map(_.aliasName))
    assertEquals(Vector("c", "c"), aliases.flatMap(_.reference).map(_.getText))
    assertTrue(aliases.head.isScala3StyleAliasImport)
    assertTrue(aliases(1).isScala2StyleAliasImport)
    val bindings       = NativePsiElementBindings.probe(getProject).fold(error => throw new AssertionError(error), identity)
    val asOffset       = aliases.head.getText.lastIndexOf("as")
    val asLeaf         = aliases.head.getNode.findLeafElementAt(asOffset)
    assertEquals("as", asLeaf.getText)
    assertEquals(bindings.elementTypes(NativePsiElementBindings.ImportAliasAsTokenSurface), asLeaf.getElementType)
    assertEquals(
      TextRange(
        aliases.head.getTextRange.getStartOffset + asOffset,
        aliases.head.getTextRange.getStartOffset + asOffset + 2
      ),
      asLeaf.getTextRange
    )
    val arrowOffset    = aliases(1).getText.lastIndexOf("=>")
    val arrowLeaf      = aliases(1).getNode.findLeafElementAt(arrowOffset)
    assertEquals("=>", arrowLeaf.getText)
    assertEquals(bindings.elementTypes(NativePsiElementBindings.ImportAliasArrowTokenSurface), arrowLeaf.getElementType)
    assertEquals(
      TextRange(
        aliases(1).getTextRange.getStartOffset + arrowOffset,
        aliases(1).getTextRange.getStartOffset + arrowOffset + 2
      ),
      arrowLeaf.getTextRange
    )
    assertEquals(4, givenSelectors.size)
    assertEquals(Vector("T", "T"), givenSelectors.flatMap(_.givenTypeElement).map(_.getText))
    assertEquals(1, wildcardInSet.size)
    assertEquals(Some("*"), wildcardInSet.head.wildcardElement.map(_.getText))
    selectors.foreach(selector =>
      assertSame(selector.parentImportExpression, selectorSets.find(_.selectors.contains(selector)).get.getParent)
    )
    statements.zip(expressions).foreach((statement, expression) => assertSame(statement, expression.getParent))
    selectorSets.foreach(set => assertTrue(expressions.contains(set.getParent)))
    selectors.foreach(selector => assertTrue(selectorSets.contains(selector.getParent)))
    val simpleTypes    = PsiTreeUtil.findChildrenOfType(file, classOf[ScSimpleTypeElement]).asScala.toVector
    assertEquals(Vector("T", "T"), simpleTypes.map(_.getText))
    simpleTypes.foreach: simpleType =>
      val reference = PsiTreeUtil.findChildOfType(simpleType, classOf[ScStableCodeReference])
      assertNotNull(reference)
      assertSame(simpleType, reference.getParent)
      assertTrue(givenSelectors.contains(simpleType.getParent))
    val composites     = statements ++ expressions ++ selectorSets ++ selectors ++ simpleTypes ++
      PsiTreeUtil.findChildrenOfType(file, classOf[ScStableCodeReference]).asScala
    composites.foreach: element =>
      assertSame(file, element.getContainingFile)
      assertSame(getProject, element.getProject)
      assertSame(element, element.getNode.getPsi)
      assertSame(element, element.getNavigationElement)

    val stubTree                                            = file.asInstanceOf[PsiFileImpl].calcStubTree
    val stubs                                               = stubTree.getPlainList.asScala
    val statementStubs                                      = stubs.collect { case value: ScImportStmtStub => value }
    val expressionStubs                                     = stubs.collect { case value: ScImportExprStub => value }
    val selectorStubs                                       = stubs.collect { case value: ScImportSelectorStub => value }
    val selectorSetStubs                                    = stubs.collect { case value: ScImportSelectorsStub => value }
    assertEquals(13, statementStubs.size)
    assertEquals(13, expressionStubs.size)
    assertEquals(6, selectorSetStubs.size)
    assertEquals(8, selectorStubs.size)
    assertEquals(s"a.b.$plainName", expressionStubs.head.referenceText.get)
    assertFalse(expressionStubs.head.hasWildcardSelector)
    assertTrue(expressionStubs(1).hasWildcardSelector)
    assertTrue(expressionStubs(6).hasGivenSelector)
    assertEquals(2, selectorStubs.count(_.isAliasedImport))
    assertEquals(4, selectorStubs.count(_.isGivenSelector))
    assertEquals(1, selectorStubs.count(_.isWildcardSelector))
    assertEquals(Vector("T", "T"), selectorStubs.flatMap(_.typeText))
    val enumerator                                          = new TestStringEnumerator
    def bytes(write: StubOutputStream => Unit): Array[Byte] =
      val sink   = new ByteArrayOutputStream
      val output = new StubOutputStream(sink, enumerator)
      write(output)
      output.flush()
      sink.toByteArray
    val statementCopy                                       = ScalaElementType.ImportStatement.deserialize(
      new StubInputStream(
        new ByteArrayInputStream(bytes(ScalaElementType.ImportStatement.serialize(statementStubs.head, _))),
        enumerator
      ),
      new PsiFileStubImpl(null)
    )
    assertNotNull(statementCopy)
    val expressionCopy                                      = ScalaElementType.IMPORT_EXPR.deserialize(
      new StubInputStream(
        new ByteArrayInputStream(bytes(ScalaElementType.IMPORT_EXPR.serialize(expressionStubs(7), _))),
        enumerator
      ),
      new PsiFileStubImpl(null)
    )
    assertEquals(expressionStubs(7).referenceText, expressionCopy.referenceText)
    assertEquals(expressionStubs(7).hasWildcardSelector, expressionCopy.hasWildcardSelector)
    assertEquals(expressionStubs(7).hasGivenSelector, expressionCopy.hasGivenSelector)
    val selectorSetCopy                                     = ScalaElementType.IMPORT_SELECTORS.deserialize(
      new StubInputStream(
        new ByteArrayInputStream(bytes(ScalaElementType.IMPORT_SELECTORS.serialize(selectorSetStubs.last, _))),
        enumerator
      ),
      new PsiFileStubImpl(null)
    )
    assertEquals(selectorSetStubs.last.hasWildcard, selectorSetCopy.hasWildcard)
    val selectorCopy                                        = ScalaElementType.IMPORT_SELECTOR.deserialize(
      new StubInputStream(
        new ByteArrayInputStream(bytes(ScalaElementType.IMPORT_SELECTOR.serialize(selectorStubs(1), _))),
        enumerator
      ),
      new PsiFileStubImpl(null)
    )
    assertEquals(selectorStubs(1).isAliasedImport, selectorCopy.isAliasedImport)
    assertEquals(selectorStubs(1).referenceText, selectorCopy.referenceText)
    assertEquals(selectorStubs(1).importedName, selectorCopy.importedName)
    assertEquals(selectorStubs(1).aliasName, selectorCopy.aliasName)
    val indexedSelectors                                    = Vector.newBuilder[String]
    selectorStubs.foreach: stub =>
      ScalaElementType.IMPORT_SELECTOR.indexStub(
        stub,
        new IndexSink:
          override def occurrence[Psi <: PsiElement, K](_indexKey: StubIndexKey[K, Psi], value: K): Unit =
            assertSame(ScalaIndexKeys.ALIASED_IMPORT_KEY, _indexKey)
            indexedSelectors += value.toString
      )
    assertEquals(Vector("c", "c", "c"), indexedSelectors.result())

  private def assertLegacyAndHiddenImports(file: com.intellij.psi.PsiFile, text: String): Unit =
    assertEquals(text, file.getText)
    val leaves           = PsiTreeUtil.collectElements(file, _.getFirstChild == null).toVector
    assertEquals(text, leaves.map(_.getText).mkString)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[PsiErrorElement]).isEmpty)
    val statements       = PsiTreeUtil.findChildrenOfType(file, classOf[ScImportStmt]).asScala.toVector
    val expressions      = statements.flatMap(_.importExprs)
    val selectors        = expressions.flatMap(_.selectors)
    assertEquals(Vector("a.b._", "a.b.{c as _}", "a.b.{c => _}"), expressions.map(_.getText))
    assertTrue(expressions.head.hasWildcardSelector)
    assertEquals(Some("_"), expressions.head.wildcardElement.map(_.getText))
    assertEquals(2, selectors.size)
    assertTrue(selectors.forall(_.isAliasedImport))
    assertTrue(selectors.forall(_.reference.exists(_.getText == "c")))
    assertTrue(selectors.forall(_.aliasName.contains("_")))
    val bindings         = NativePsiElementBindings.probe(getProject).fold(error => throw new AssertionError(error), identity)
    val underscoreLeaves = leaves.filter(_.getText == "_")
    assertEquals(3, underscoreLeaves.size)
    underscoreLeaves.foreach: leaf =>
      assertSame(
        bindings.elementTypes(NativePsiElementBindings.ImportLegacyWildcardTokenSurface),
        leaf.getNode.getElementType
      )
    val stubs            = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala
    val selectorStubs    = stubs.collect { case value: ScImportSelectorStub => value }
    assertEquals(3, stubs.count(_.isInstanceOf[ScImportStmtStub]))
    assertEquals(3, stubs.count(_.isInstanceOf[ScImportExprStub]))
    assertEquals(2, selectorStubs.size)
    assertTrue(selectorStubs.forall(_.isAliasedImport))
    assertTrue(selectorStubs.forall(_.aliasName.contains("_")))

  private final class TestStringEnumerator extends AbstractStringEnumerator:
    private val values = collection.mutable.ArrayBuffer.empty[String]

    override def enumerate(value: String): Int =
      val index = values.indexOf(value)
      if index >= 0 then index + 1
      else
        values += value
        values.size

    override def valueOf(id: Int): String = values(id - 1)
    override def isDirty: Boolean         = false
    override def force(): Unit            = ()
    override def markCorrupted(): Unit    = ()
    override def close(): Unit            = ()

  private def assertPlan(prepared: PreparedScala3Parser, uri: String, source: String): Unit =
    val request   = Scala3ParserRequest(ParserSourceUri.from(uri).toOption.get, source, prepared.compilerOptions)
    val snapshot  = prepared.bridge.parse(request).fold(error => throw new AssertionError(error.toString), identity)
    val evidence  = ProvisionalSourceEvidencePlanner
      .plan(snapshot)
      .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
    val runtime   = CompilerRuntimeInventory
      .from(snapshot)
      .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
    val aggregate = AggregatedCompilerProductionInventory
      .aggregate(Vector(runtime))
      .fold(error => throw new AssertionError(error.toString), identity)
    val catalog   = PreparedProductionCatalog
      .prepareRuntimeSubset(prepared.catalog, runtime, aggregate, prepared.surfaces)
      .fold(errors => throw new AssertionError(errors.mkString("\n")), identity)
    val plan      = WholeFileProductionPlanner
      .plan(snapshot, evidence, catalog)
      .fold(error => throw new AssertionError(error.toString), identity)
    assertEquals(3, plan.composites.size)

  private def uncoveredSnapshot(
      request: Scala3ParserRequest,
      bridge: TestParserBridge
  ): Either[Scala3ParserError, ParserSyntaxSnapshot] =
    val source = request.sourceText
    Right(
      ParserSyntaxSnapshot(
        request.sourceUri,
        source,
        ParserSyntaxSnapshot.digest(source),
        source.length,
        request.compilerOptions,
        1,
        Vector(
          ParserSyntaxNode(
            1,
            "Uncovered",
            Vector.empty,
            ParserNodePosition.Positioned(
              PcSourceRange(0, source.length),
              0,
              ParserPositionProvenance.SourceDerived
            ),
            Vector.empty
          )
        ),
        Vector.empty,
        Vector.empty,
        Vector.empty,
        bridge.capabilities,
        bridge.identity,
        Vector.empty
      )
    )

  private def packageSnapshot(
      request: Scala3ParserRequest,
      bridge: TestParserBridge
  ): Either[Scala3ParserError, ParserSyntaxSnapshot] =
    val source = request.sourceText
    if !source.matches("package [^.\\n]+\\.[^.\\n]+\\n") then Left(Scala3ParserError.Closed)
    else
      val dot      = source.indexOf('.')
      val end      = source.length - 1
      val position = (from: Int, point: Int, to: Int) =>
        ParserNodePosition.Positioned(
          PcSourceRange(from, to),
          point,
          ParserPositionProvenance.SourceDerived
        )
      val nodes    = Vector(
        ParserSyntaxNode(
          1,
          "PackageDef",
          Vector(
            ParserSyntaxField("pid", ParserFieldValue.Node(2), Some(ParserDeclaredShape.Node)),
            ParserSyntaxField(
              "stats",
              ParserFieldValue.Repeated(Vector.empty),
              Some(ParserDeclaredShape.Repeated(ParserDeclaredShape.Node))
            )
          ),
          position(0, dot + 1, end),
          Vector.empty
        ),
        ParserSyntaxNode(
          2,
          "Select",
          Vector(
            ParserSyntaxField("qualifier", ParserFieldValue.Node(3), Some(ParserDeclaredShape.Node)),
            ParserSyntaxField(
              "name",
              ParserFieldValue.Name(source.substring(dot + 1, end)),
              Some(ParserDeclaredShape.Name)
            )
          ),
          position(8, dot + 1, end),
          Vector(ParserNodeOccurrence(1, Vector(ParserFieldPathSegment.NamedField("pid"))))
        ),
        ParserSyntaxNode(
          3,
          "Ident",
          Vector(
            ParserSyntaxField(
              "name",
              ParserFieldValue.Name(source.substring(8, dot)),
              Some(ParserDeclaredShape.Name)
            )
          ),
          position(8, 8, dot),
          Vector(ParserNodeOccurrence(2, Vector(ParserFieldPathSegment.NamedField("qualifier"))))
        )
      )
      Right(
        ParserSyntaxSnapshot(
          request.sourceUri,
          source,
          ParserSyntaxSnapshot.digest(source),
          source.length,
          request.compilerOptions,
          1,
          nodes,
          Vector.empty,
          Vector.empty,
          Vector.empty,
          bridge.capabilities,
          bridge.identity,
          Vector.empty
        )
      )
