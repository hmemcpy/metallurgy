package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.stubs.PsiFileStubImpl
import com.intellij.psi.stubs.StubInputStream
import com.intellij.psi.stubs.StubOutputStream
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType
import org.jetbrains.plugins.scala.lang.psi.api.ScalaElementVisitor
import org.jetbrains.plugins.scala.lang.psi.api.ScalaPsiElement
import org.jetbrains.plugins.scala.lang.psi.api.base.ScStableCodeReference
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScBooleanLiteral
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScCharLiteral
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScDoubleLiteral
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScFloatLiteral
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScIntegerLiteral
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScLongLiteral
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScStringLiteral
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScInfixTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScLiteralTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScParameterizedTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScParenthesisedTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScSimpleTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeProjection
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScWildcardTypeElement
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScExportStmt
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScImportSelector
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScImportStmt
import org.jetbrains.plugins.scala.lang.psi.stubs.ScImportExprStub
import org.jetbrains.plugins.scala.lang.psi.stubs.ScImportSelectorStub
import org.jetbrains.plugins.scala.lang.psi.stubs.ScImportStmtStub
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.{assertEquals, assertFalse, assertNotNull, assertSame, assertTrue}
import scala.jdk.CollectionConverters.*

private[psiproducer] trait Scala3PackagePsiTypeAndGivenTests extends Scala3PackagePsiProducerTestSupport:
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
    val pending   = codeInsightFixture.addFileToProject("src/TypeAtomCase.scala", source)
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

    val editPending  = codeInsightFixture.addFileToProject("src/TypeAtomEditCase.scala", "import a.b.given A\n")
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

    val deletePending  = codeInsightFixture.addFileToProject("src/TypeAtomDeleteCase.scala", "import a.b.given x.type\n")
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

    val malformed     = codeInsightFixture.addFileToProject("src/TypeAtomMalformedCase.scala", "import a.b.given (A\n")
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
    val pending           = codeInsightFixture.addFileToProject("src/BoundedGivenTypeCase.scala", source)
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

    val editPending  = codeInsightFixture.addFileToProject(
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
    val pending            = codeInsightFixture.addFileToProject("src/DeepBoundedGivenTypeCase.scala", source)
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
    val pending  = codeInsightFixture.addFileToProject("src/ExactImportCase.scala", source)
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
    val triviaFile   = codeInsightFixture.addFileToProject("src/TriviaImportCase.scala", triviaSource)
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
