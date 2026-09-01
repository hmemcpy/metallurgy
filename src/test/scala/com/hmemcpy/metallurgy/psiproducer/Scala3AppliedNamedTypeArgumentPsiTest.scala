package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.{PsiDocumentManager, PsiErrorElement, PsiManager, SmartPointerManager}
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.search.searches.ReferencesSearch
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
import com.intellij.util.FileContentUtilCore
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType
import org.jetbrains.plugins.scala.lang.psi.api.ScalaElementVisitor
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.{ScIntegerLiteral, ScStringLiteral}
import org.jetbrains.plugins.scala.lang.psi.api.base.types.{ScParameterizedTypeElement, ScSimpleTypeElement, ScTypeArgs}
import org.jetbrains.plugins.scala.lang.psi.api.expr.{
  ScArgumentExprList,
  ScExpression,
  ScGenericCall,
  ScMethodCall,
  ScReferenceExpression
}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunctionDefinition, ScPatternDefinition}
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.{
  MetallurgyExpressionPayload,
  MetallurgyNamedTypeArgument,
  MetallurgyTypeArguments
}
import org.junit.Assert.{assertArrayEquals, assertEquals, assertFalse, assertNull, assertSame, assertTrue}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

import scala.jdk.CollectionConverters.*

final class Scala3AppliedNamedTypeArgumentPsiTest extends Scala3CompatTestCase:
  def testAppliedTypesAndExpressionTypeApplicationIslandsUseExactPhysicalPsi(): Unit =
    val source =
      """import scala.language.experimental.namedTypeArguments
        |
        |type One = List[Int]
        |type Two = Either[Int, String]
        |type Three = Coll[Elem]
        |
        |val direct = /*start*/make[A = Int]/*end*/
        |//Int
        |val commented = make[A /*left*/ = /*right*/ Int]
        |val multiple = make[A = Int, B /* name */ = /* type */ String]
        |val selected = target.make[A = Int]
        |val invoked = /*start*/pair[A = Int](1, "text")/*end*/
        |//String
        |val allNamed = pair[A = Int, B = String](left, "text")
        |""".stripMargin
    val file   = physical("AppliedNamedTypeArguments1.scala", source)

    val parameterized = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScParameterizedTypeElement])
      .asScala
      .toVector
      .filter(value => Set("List[Int]", "Either[Int, String]", "Coll[Elem]")(value.getText))
      .sortBy(_.getTextRange.getStartOffset)
    assertEquals(Vector("List[Int]", "Either[Int, String]", "Coll[Elem]"), parameterized.map(_.getText))
    assertEquals(Vector("List", "Either", "Coll"), parameterized.map(_.typeElement.getText))
    assertEquals(Vector("[Int]", "[Int, String]", "[Elem]"), parameterized.map(_.typeArgList.getText))
    assertEquals(
      Vector(Vector("Int"), Vector("Int", "String"), Vector("Elem")),
      parameterized.map(_.typeArgList.typeArgs.map(_.getText).toVector)
    )
    parameterized.foreach: value =>
      assertSame(value, value.typeElement.getParent)
      assertSame(value, value.typeArgList.getParent)
      assertEquals(
        Vector(value.typeElement, value.typeArgList),
        value.getChildren.toVector.filterNot(_.getText.isBlank)
      )
      assertTrue(value.typeArgList.getClass.getName.endsWith("ScTypeArgsImpl"))

    val payloads = PsiTreeUtil
      .findChildrenOfType(file, classOf[MetallurgyExpressionPayload])
      .asScala
      .toVector
      .sortBy(_.getTextRange.getStartOffset)
    assertEquals(Vector.empty, payloads.map(_.getText))

    val calls      = genericCalls(file)
    assertEquals(
      Vector(
        "make[A = Int]",
        "make[A /*left*/ = /*right*/ Int]",
        "make[A = Int, B /* name */ = /* type */ String]",
        "target.make[A = Int]",
        "pair[A = Int]",
        "pair[A = Int, B = String]"
      ),
      calls.map(_.getText)
    )
    calls.foreach: call =>
      assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.expr.ScGenericCallImpl", call.getClass.getName)
      assertEquals(ScalaElementType.GENERIC_CALL, call.getNode.getElementType)
      assertEquals(call.getText, call.getTextRange.substring(source))
      assertEquals(
        "org.jetbrains.plugins.scala.lang.psi.impl.expr.ScReferenceExpressionImpl",
        call.referencedExpr.getClass.getName
      )
      assertEquals(call.typeArgs.getText, call.typeArgs.getTextRange.substring(source))
      assertSame(call, call.referencedExpr.getParent)
      assertSame(call, call.typeArgs.getParent)
      assertTrue(call.typeArgs.isInstanceOf[MetallurgyTypeArguments])
      assertTrue(call.arguments.isEmpty)
      assertTrue(call.typeArgs.typeArgs.isEmpty)
      assertEquals(
        Vector(call.referencedExpr, call.typeArgs),
        call.getChildren.toVector.collect { case child: org.jetbrains.plugins.scala.lang.psi.api.ScalaPsiElement =>
          child
        }
      )
    val references = calls.map(_.referencedExpr.asInstanceOf[ScReferenceExpression])
    assertEquals(Vector(None, None, None, Some("target"), None, None), references.map(_.qualifier.map(_.getText)))
    assertEquals("make", references(3).refName)
    assertEquals("make", references(3).nameId.getText)
    assertSame(references(3), references(3).nameId.getParent)
    assertSame(references(3), references(3).qualifier.get.getParent)

    val namedLists = calls.map(_.typeArgs.asInstanceOf[MetallurgyTypeArguments])
    assertEquals(Vector(1, 1, 2, 1, 1, 2), namedLists.map(_.logicalTypeArguments.size))
    assertEquals(Vector(1, 1, 2, 1, 1, 2), namedLists.map(_.namedTypeArguments.size))
    assertTrue(namedLists.forall(_.typeArgs.isEmpty))
    namedLists.foreach(list => assertEquals(list.logicalTypeArguments, list.namedTypeArguments))

    val named = namedLists.flatMap(_.namedTypeArguments)
    assertEquals(Vector("A", "A", "A", "B", "A", "A", "A", "B"), named.flatMap(_.name))
    assertEquals(
      Vector("Int", "Int", "Int", "String", "Int", "Int", "Int", "String"),
      named.flatMap(_.typeElement).map(_.getText)
    )
    assertEquals(
      Vector(
        "A = Int",
        "A /*left*/ = /*right*/ Int",
        "A = Int",
        "B /* name */ = /* type */ String",
        "A = Int",
        "A = Int",
        "A = Int",
        "B = String"
      ),
      named.map(_.getText)
    )
    named.foreach: argument =>
      assertTrue(argument.isNamed)
      assertEquals(argument.getText, argument.getTextRange.substring(source))
      assertEquals(argument.typeElement.get.`type`(), argument.`type`())
      assertSame(argument, argument.nameElement.get.getParent)
      assertSame(argument, argument.typeElement.get.getParent)
      assertTrue(argument.nameElement.get.getClass.getName.endsWith("ScStableCodeReferenceImpl"))
      assertTrue(argument.typeElement.get.isInstanceOf[ScSimpleTypeElement])
      assertEquals(
        Vector(argument.nameElement.get, argument.typeElement.get),
        argument.getChildren.toVector.collect { case value: org.jetbrains.plugins.scala.lang.psi.api.ScalaPsiElement =>
          value
        }
      )

    var visited = Vector.empty[ScGenericCall]
    calls.foreach: call =>
      call.accept(
        new ScalaElementVisitor:
          override def visitGenericCallExpression(value: ScGenericCall): Unit = visited :+= value
      )
    assertEquals(calls, visited)

    val methodCalls    = PsiTreeUtil.findChildrenOfType(file, classOf[ScMethodCall]).asScala.toVector
    assertEquals(
      Vector("pair[A = Int](1, \"text\")", "pair[A = Int, B = String](left, \"text\")"),
      methodCalls.map(_.getText)
    )
    methodCalls
      .zip(calls.takeRight(2))
      .foreach: (methodCall, genericCall) =>
        assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.expr.ScMethodCallImpl", methodCall.getClass.getName)
        assertSame(genericCall, methodCall.getInvokedExpr)
        assertSame(methodCall, genericCall.getParent)
        assertSame(methodCall, methodCall.args.getParent)
        assertEquals(
          Vector(genericCall, methodCall.args),
          methodCall.getChildren.toVector.collect {
            case child: org.jetbrains.plugins.scala.lang.psi.api.ScalaPsiElement => child
          }
        )
    assertEquals(Vector("1", "\"text\""), methodCalls.head.argumentExpressions.map(_.getText).toVector)
    assertEquals(Vector("left", "\"text\""), methodCalls.last.argumentExpressions.map(_.getText).toVector)
    var visitedMethods = Vector.empty[ScMethodCall]
    methodCalls.foreach: call =>
      call.accept(
        new ScalaElementVisitor:
          override def visitMethodCallExpression(value: ScMethodCall): Unit = visitedMethods :+= value
      )
    assertEquals(methodCalls, visitedMethods)
    assertEquals(2, PsiTreeUtil.findChildrenOfType(file, classOf[ScArgumentExprList]).size)
    assertFalse(PsiTreeUtil.findChildrenOfType(file, classOf[ScExpression]).isEmpty)

  def testInvokedNamedGenericCallsCoverEveryAdmittedDefinitionOwner(): Unit =
    val bodies = Vector(
      "def packageFunction = make[A = Int](value)\n",
      "val packageValue = make[A = Int](value)\n",
      "def packageSelected = target.make[A = Int](value)\n",
      "val packageSelected = target.make[A = Int](value)\n",
      "class Owner:\n  def memberFunction = make[A = Int](value)\n",
      "class Owner:\n  val memberValue = make[A = Int](value)\n"
    )
    bodies.zipWithIndex.foreach: (body, index) =>
      val source  = s"import scala.language.experimental.namedTypeArguments\n$body"
      val file    = physical(s"AppliedNamedOwner${index + 1}.scala", source)
      val call    = PsiTreeUtil.findChildOfType(file, classOf[ScMethodCall])
      val generic = call.getInvokedExpr.asInstanceOf[ScGenericCall]
      assertEquals(if body.contains("target.") then "target.make[A = Int]" else "make[A = Int]", generic.getText)
      assertEquals(s"${generic.getText}(value)", call.getText)
      assertSame(call, generic.getParent)
      assertSame(
        call,
        call.getParent match
          case function: ScFunctionDefinition => function.body.get
          case value: ScPatternDefinition     => value.expr.get
      )
      assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyExpressionPayload]).isEmpty)

  def testInvokedNamedCallsAdmitOnlyCandidateScopedReferencesIntegersAndStrings(): Unit =
    val source =
      """import scala.language.experimental.namedTypeArguments
        |val identifier = pair[A = Int](left)
        |val literals = pair[A = Int](1, "text")
        |val mixed = pair[/* before */ A /* equals */ = /* type */ Int](left, /* integer */ 1, /* string */ "text")
        |""".stripMargin
    val file   = physical("AppliedNamedArguments.scala", source)
    val calls  = PsiTreeUtil.findChildrenOfType(file, classOf[ScMethodCall]).asScala.toVector
    assertEquals(
      Vector(
        "pair[A = Int](left)",
        "pair[A = Int](1, \"text\")",
        "pair[/* before */ A /* equals */ = /* type */ Int](left, /* integer */ 1, /* string */ \"text\")"
      ),
      calls.map(_.getText)
    )
    assertEquals(Vector("left"), calls.head.argumentExpressions.map(_.getText).toVector)
    assertEquals(Vector("1", "\"text\""), calls(1).argumentExpressions.map(_.getText).toVector)
    assertEquals(Vector("left", "1", "\"text\""), calls.last.argumentExpressions.map(_.getText).toVector)
    assertEquals(2, PsiTreeUtil.findChildrenOfType(file, classOf[ScIntegerLiteral]).size)
    assertEquals(2, PsiTreeUtil.findChildrenOfType(file, classOf[ScStringLiteral]).size)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyExpressionPayload]).isEmpty)

    val payloadCases = Vector(
      "val result = pair[A = Int](1L)\n"       -> "pair[A = Int](1L)",
      "val result = pair[A = Int](true)\n"     -> "pair[A = Int](true)",
      "val result = pair[A = Int](inner(x))\n" -> "pair[A = Int](inner(x))",
      "val result = pair[A = Int](name = 1)\n" -> "pair[A = Int](name = 1)",
      "val result = pair[A = Int](using 1)\n"  -> "pair[A = Int](using 1)"
    )
    payloadCases.zipWithIndex.foreach: (entry, index) =>
      val (body, expected) = entry
      val excludedSource   = s"import scala.language.experimental.namedTypeArguments\n$body"
      val excluded         = physical(s"AppliedNamedArgumentFallback${index + 1}.scala", excludedSource)
      assertEquals(
        Vector(expected),
        PsiTreeUtil.findChildrenOfType(excluded, classOf[MetallurgyExpressionPayload]).asScala.map(_.getText).toVector
      )
      assertNoPartialInvokedNamedCallPsi(excludedSource, excluded)

    val isolatedCases = Vector(
      "def owner = { val local = pair[A = Int](1, \"text\"); local }\n",
      "val nested = call(pair[A = Int](1, \"text\"))\n",
      "def owner(value: Int = pair[A = Int](1, \"text\")) = value\n"
    )
    isolatedCases.zipWithIndex.foreach: (body, index) =>
      val isolatedSource = s"import scala.language.experimental.namedTypeArguments\n$body"
      val pending        = myFixture.addFileToProject(s"src/AppliedNamedArgumentIsolation${index + 1}.scala", isolatedSource)
      val isolated       = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
      isolated.getChildren
      assertTrue(isolatedSource, genericCalls(isolated).isEmpty)
      assertFalse(
        isolatedSource,
        PsiTreeUtil.findChildrenOfType(isolated, classOf[ScMethodCall]).asScala.exists(_.getText.startsWith("pair["))
      )

  def testDirectAndSelectedNamedReferencesRetainNavigationRenameAndEmptyUsages(): Unit =
    Vector(
      "val result = make[A = Int]\n"               -> "val result = build[A = Int]\n",
      "val result = target.make[A = Int]\n"        -> "val result = target.build[A = Int]\n",
      "val result = target.make[A = Int](value)\n" -> "val result = target.build[A = Int](value)\n"
    ).zipWithIndex.foreach: (entry, index) =>
      val (body, renamedBody) = entry
      val source              = s"import scala.language.experimental.namedTypeArguments\n$body"
      val file                = physical(s"AppliedNamedRename${index + 1}.scala", source)
      val call                = genericCalls(file).head
      val reference           = call.referencedExpr.asInstanceOf[ScReferenceExpression]
      assertSame(call, call.getNavigationElement)
      assertTrue(ReferencesSearch.search(reference).findAll().isEmpty)
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            val _ = reference.handleElementRename("build")
      )
      assertEquals(s"import scala.language.experimental.namedTypeArguments\n$renamedBody", file.getText)
      assertEquals(
        renamedBody.trim.stripPrefix("val result = ").stripSuffix("(value)"),
        genericCalls(file).head.getText
      )

  def testCopiesPointersAndPositionalNamedReparsePreserveExactIslands(): Unit =
    val source               =
      """import scala.language.experimental.namedTypeArguments
        |val result = make[A = Int]
        |""".stripMargin
    val file                 = physical("AppliedNamedTypeArguments2.scala", source)
    val generic              = genericCalls(file).head
    val pointer              = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(generic)
    val namedTypeArgsPointer =
      SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(generic.typeArgs)
    val copy                 = file.copy()

    assertEquals("make[A = Int]", genericCalls(copy.asInstanceOf[com.intellij.psi.PsiFile]).head.getText)
    val document = PsiDocumentManager.getInstance(getProject).getDocument(file)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          document.replaceString(source.indexOf("Int"), source.indexOf("Int") + 3, "String")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("make[A = String]", pointer.getElement.getText)

    val named             = file.getText
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val start = named.indexOf("A = String")
          document.replaceString(start, start + "A = String".length, "String")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertNull(namedTypeArgsPointer.getElement)
    val positionalFile    = PsiManager.getInstance(getProject).findFile(file.getVirtualFile)
    val positionalGeneric = genericCalls(positionalFile)
    assertEquals(Vector("make[String]"), positionalGeneric.map(_.getText))
    assertEquals("make[String]", positionalGeneric.head.getTextRange.substring(positionalFile.getText))
    assertEquals("make", positionalGeneric.head.referencedExpr.getText)
    assertEquals("[String]", positionalGeneric.head.typeArgs.getText)
    assertSame(positionalGeneric.head, positionalGeneric.head.referencedExpr.getParent)
    assertSame(positionalGeneric.head, positionalGeneric.head.typeArgs.getParent)
    assertEquals(
      Vector(positionalGeneric.head.referencedExpr, positionalGeneric.head.typeArgs),
      positionalGeneric.head.getChildren.toVector.collect {
        case child: org.jetbrains.plugins.scala.lang.psi.api.ScalaPsiElement => child
      }
    )
    assertTrue(PsiTreeUtil.findChildrenOfType(positionalFile, classOf[MetallurgyTypeArguments]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(positionalFile, classOf[MetallurgyExpressionPayload]).isEmpty)
    assertSame(positionalGeneric.head, pointer.getElement)
    val positionalPointer =
      SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(positionalGeneric.head.typeArgs)

    val positional   = file.getText
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val start = positional.indexOf("make[String]") + "make[".length
          document.replaceString(start, start + 6, "A = Long")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertNull(positionalPointer.getElement)
    val namedFile    = PsiManager.getInstance(getProject).findFile(file.getVirtualFile)
    assertEquals(
      Vector("make[A = Long]"),
      genericCalls(namedFile).map(_.getText)
    )
    assertSame(genericCalls(namedFile).head, pointer.getElement)
    assertEquals(
      "A = Long",
      PsiTreeUtil.findChildOfType(namedFile, classOf[MetallurgyTypeArguments]).namedTypeArguments.head.getText
    )
    assertTrue(PsiTreeUtil.findChildrenOfType(namedFile, classOf[MetallurgyExpressionPayload]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(namedFile, classOf[PsiErrorElement]).isEmpty)
    val namedPointer =
      SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(genericCalls(namedFile).head)
    FileContentUtilCore.reparseFiles(java.util.List.of(file.getVirtualFile))
    val reparsed     = PsiManager.getInstance(getProject).findFile(file.getVirtualFile)
    assertEquals("make[A = Long]", genericCalls(reparsed).head.getText)
    assertEquals("make[A = Long]", namedPointer.getElement.getText)

    val restored       = document.getText
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val start = restored.indexOf("Long")
          document.deleteString(start, start + "Long".length)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    val malformed      = PsiManager.getInstance(getProject).findFile(file.getVirtualFile)
    assertTrue(genericCalls(malformed).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(malformed, classOf[MetallurgyTypeArguments]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(malformed, classOf[MetallurgyExpressionPayload]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(malformed, classOf[ScExpression]).isEmpty)
    val directRecovery = recoveryObservation(malformed, document.getText)
    assertEquals(
      Vector("an identifier expected, but ']' found" -> ""),
      directRecovery.errors.map(error => error.description -> error.text)
    )
    assertTrue(
      Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(file.getVirtualFile, ParserSyntaxSnapshot.digest(document.getText))
        .isEmpty
    )

    val controlSource    =
      "import scala.language.experimental.namedTypeArguments\ndef owner = { val result = make[A = Long]; result }\n"
    val control          = physical("AppliedNamedMalformedControl.scala", controlSource)
    assertEquals(
      Vector("{ val result = make[A = Long]; result }", "make[A = Long]"),
      PsiTreeUtil.findChildrenOfType(control, classOf[MetallurgyExpressionPayload]).asScala.map(_.getText).toVector
    )
    val controlDocument  = PsiDocumentManager.getInstance(getProject).getDocument(control)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val start = controlDocument.getText.indexOf("Long")
          controlDocument.deleteString(start, start + "Long".length)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(controlDocument)
    val detachedRecovery = recoveryObservation(control, controlDocument.getText)
    assertEquals(directRecovery, detachedRecovery)

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.insertString(document.getText.indexOf("A = ") + "A = ".length, "Long")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals(Vector("make[A = Long]"), genericCalls(file).map(_.getText))

  def testInvokedCopiesPointersRoleChangesEntryEditsAndReparseRemainAtomic(): Unit =
    val source     =
      "import scala.language.experimental.namedTypeArguments\nval result = pair[A = Int, B = String](left, right)\n"
    val file       = physical("AppliedNamedInvokedEdits.scala", source)
    val call       = PsiTreeUtil.findChildOfType(file, classOf[ScMethodCall])
    val generic    = call.getInvokedExpr.asInstanceOf[ScGenericCall]
    val list       = generic.typeArgs.asInstanceOf[MetallurgyTypeArguments]
    val pointers   = SmartPointerManager.getInstance(getProject)
    val callPtr    = pointers.createSmartPsiElementPointer(call)
    val genericPtr = pointers.createSmartPsiElementPointer(generic)
    val listPtr    = pointers.createSmartPsiElementPointer(list)
    assertEquals(
      "pair[A = Int, B = String](left, right)",
      PsiTreeUtil.findChildOfType(file.copy(), classOf[ScMethodCall]).getText
    )

    val donor       = physical(
      "AppliedNamedInvokedDonor.scala",
      "import scala.language.experimental.namedTypeArguments\nval donor = make[A = Long]\n"
    )
    val replacement = PsiTreeUtil
      .findChildOfType(donor, classOf[MetallurgyTypeArguments])
      .namedTypeArguments
      .head
      .typeElement
      .get
      .copy()
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val _ = list.namedTypeArguments.head.typeElement.get.replace(replacement)
          list.namedTypeArguments.last.delete()
    )
    assertEquals("pair[A = Long](left, right)", callPtr.getElement.getText)
    assertSame(generic, genericPtr.getElement)
    assertSame(list, listPtr.getElement)
    assertEquals(Vector("A = Long"), list.namedTypeArguments.map(_.getText).toVector)

    val document          = PsiDocumentManager.getInstance(getProject).getDocument(file)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val start = document.getText.indexOf("A = Long")
          document.replaceString(start, start + "A = Long".length, "Long")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("pair[Long](left, right)", callPtr.getElement.getText)
    assertEquals("pair[Long]", genericPtr.getElement.getText)
    assertNull(listPtr.getElement)
    val positionalList    = genericPtr.getElement.typeArgs
    assertTrue(positionalList.getClass.getName.endsWith("ScTypeArgsImpl"))
    assertEquals(Vector("Long"), positionalList.typeArgs.map(_.getText).toVector)
    val positionalListPtr = pointers.createSmartPsiElementPointer(positionalList)

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val start = document.getText.indexOf("Long")
          document.replaceString(start, start + "Long".length, "A = Long")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("pair[A = Long](left, right)", callPtr.getElement.getText)
    assertEquals("pair[A = Long]", genericPtr.getElement.getText)
    assertNull(positionalListPtr.getElement)
    assertTrue(genericPtr.getElement.typeArgs.isInstanceOf[MetallurgyTypeArguments])

    FileContentUtilCore.reparseFiles(java.util.List.of(file.getVirtualFile))
    assertEquals("pair[A = Long](left, right)", callPtr.getElement.getText)
    assertEquals("pair[A = Long]", genericPtr.getElement.getText)

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val start = document.getText.indexOf("Long")
          document.deleteString(start, start + "Long".length)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    FileContentUtilCore.reparseFiles(java.util.List.of(file.getVirtualFile))
    val malformed = PsiManager.getInstance(getProject).findFile(file.getVirtualFile)
    assertTrue(PsiTreeUtil.findChildrenOfType(malformed, classOf[ScMethodCall]).isEmpty)
    assertTrue(genericCalls(malformed).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(malformed, classOf[ScTypeArgs]).isEmpty)
    assertFalse(PsiTreeUtil.findChildrenOfType(malformed, classOf[PsiErrorElement]).isEmpty)

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.insertString(document.getText.indexOf("A = ") + "A = ".length, "Long")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals(
      Vector("pair[A = Long](left, right)"),
      PsiTreeUtil.findChildrenOfType(file, classOf[ScMethodCall]).asScala.map(_.getText).toVector
    )

  def testNamedChildReplacementAndDeletionKeepThePhysicalListConsistent(): Unit =
    val source      =
      """import scala.language.experimental.namedTypeArguments
        |val result = pair[A = Int, B = String]
        |""".stripMargin
    val file        = physical("AppliedNamedTypeArguments3.scala", source)
    val donor       = physical(
      "AppliedNamedTypeArguments4.scala",
      "import scala.language.experimental.namedTypeArguments\nval donor = make[A = Long]\n"
    )
    val list        = PsiTreeUtil.findChildOfType(file, classOf[MetallurgyTypeArguments])
    val replacement = PsiTreeUtil
      .findChildOfType(donor, classOf[MetallurgyTypeArguments])
      .namedTypeArguments
      .head
      .typeElement
      .get
      .copy()

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val _ = list.namedTypeArguments.head.typeElement.get.replace(replacement)
    )
    assertEquals("[A = Long, B = String]", list.getText)
    assertTrue(list.typeArgs.isEmpty)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = list.namedTypeArguments.last.delete()
    )
    assertEquals("[A = Long]", list.getText)
    assertEquals(Vector("A = Long"), list.logicalTypeArguments.map(_.getText).toVector)
    assertEquals("pair[A = Long]", genericCalls(file).head.getText)

  def testMixedAndOrdinaryNamedTypeArgumentsFailClosedWithoutPartialPsi(): Unit =
    val mixedSource  =
      "import scala.language.experimental.namedTypeArguments\nval mixed = pair[Int, B = String]\n"
    val mixed        = myFixture.addFileToProject("src/AppliedNamedClosed1.scala", mixedSource)
    val mixedFile    = PsiManager.getInstance(getProject).findFile(mixed.getVirtualFile)
    mixedFile.getChildren
    // The 3.5.2 parser rejects named type arguments on an applied type (']' expected, but '=' found),
    // so the file is compiler-invalid here and must fail closed without any partial PSI.
    val mixedFailure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(mixed.getVirtualFile, ParserSyntaxSnapshot.digest(mixedSource))
    assertTrue(mixedSource, mixedFailure.isDefined)
    assertTrue(genericCalls(mixedFile).isEmpty)
    assertTrue(
      PsiTreeUtil.findChildrenOfType(mixedFile, classOf[MetallurgyExpressionPayload]).asScala.isEmpty
    )
    assertTrue(PsiTreeUtil.findChildrenOfType(mixedFile, classOf[ScReferenceExpression]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(mixedFile, classOf[ScTypeArgs]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(mixedFile, classOf[MetallurgyNamedTypeArgument]).isEmpty)

    val typeSource  = "import scala.language.experimental.namedTypeArguments\ntype Bad = F[A = Int]\n"
    val typePending = myFixture.addFileToProject("src/AppliedNamedClosed2.scala", typeSource)
    val typeFile    = PsiManager.getInstance(getProject).findFile(typePending.getVirtualFile)
    typeFile.getChildren
    val failure     = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(typePending.getVirtualFile, ParserSyntaxSnapshot.digest(typeSource))
    assertTrue(typeSource, failure.nonEmpty)
    assertTrue(typeSource, PsiTreeUtil.findChildrenOfType(typeFile, classOf[ScParameterizedTypeElement]).isEmpty)
    assertTrue(typeSource, PsiTreeUtil.findChildrenOfType(typeFile, classOf[ScTypeArgs]).isEmpty)
    assertTrue(typeSource, PsiTreeUtil.findChildrenOfType(typeFile, classOf[MetallurgyExpressionPayload]).isEmpty)
    assertTrue(typeSource, PsiTreeUtil.findChildrenOfType(typeFile, classOf[ScGenericCall]).isEmpty)
    assertTrue(typeSource, PsiTreeUtil.findChildrenOfType(typeFile, classOf[ScMethodCall]).isEmpty)
    assertTrue(typeSource, PsiTreeUtil.findChildrenOfType(typeFile, classOf[ScReferenceExpression]).isEmpty)
    assertTrue(typeSource, PsiTreeUtil.findChildrenOfType(typeFile, classOf[ScArgumentExprList]).isEmpty)

    val appliedSource =
      "import scala.language.experimental.namedTypeArguments\nval value = 1\nval bad = pair[A = Int](value, \"text\")\n"
    val applied       = physical("AppliedNamedCall.scala", appliedSource)
    assertTrue(PsiTreeUtil.findChildrenOfType(applied, classOf[MetallurgyExpressionPayload]).isEmpty)
    assertEquals(
      Vector("[A = Int]"),
      PsiTreeUtil.findChildrenOfType(applied, classOf[ScTypeArgs]).asScala.map(_.getText).toVector
    )
    assertEquals(
      Vector("pair[A = Int](value, \"text\")"),
      PsiTreeUtil.findChildrenOfType(applied, classOf[ScMethodCall]).asScala.map(_.getText).toVector
    )
    assertEquals(2, PsiTreeUtil.findChildrenOfType(applied, classOf[ScReferenceExpression]).size)
    assertEquals(1, PsiTreeUtil.findChildrenOfType(applied, classOf[MetallurgyTypeArguments]).size())
    assertEquals(1, PsiTreeUtil.findChildrenOfType(applied, classOf[MetallurgyNamedTypeArgument]).size())

    val selectedSource =
      "import scala.language.experimental.namedTypeArguments\nval bad = target.make[A = Int]\n"
    val selected       = physical("AppliedNamedSelected.scala", selectedSource)
    val selectedCall   = genericCalls(selected).head
    assertEquals("target.make[A = Int]", selectedCall.getText)
    assertEquals("target.make", selectedCall.referencedExpr.getText)
    assertEquals("[A = Int]", selectedCall.typeArgs.getText)
    assertTrue(PsiTreeUtil.findChildrenOfType(selected, classOf[MetallurgyExpressionPayload]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(selected, classOf[ScMethodCall]).isEmpty)

  def testExcludedNamedTypeApplicationsRetainTheirDetachedParentOutcome(): Unit =
    val payloadCases = Vector(
      ("def owner = { val local = make[A = Int]; local }\n", "{ val local = make[A = Int]; local }", 1),
      ("val deep = source.target.make[A = Int]\n", "source.target.make[A = Int]", 0)
    )
    payloadCases.zipWithIndex.foreach: (entry, index) =>
      val (body, expected, namedCount) = entry
      val source                       = s"import scala.language.experimental.namedTypeArguments\n$body"
      val file                         = physical(s"AppliedNamedExcluded${index + 1}.scala", source)
      assertTrue(source, genericCalls(file).isEmpty)
      val payloads                     = PsiTreeUtil
        .findChildrenOfType(file, classOf[MetallurgyExpressionPayload])
        .asScala
        .toVector
      assertEquals(source, 1, payloads.count(_.getText == expected))
      assertEquals(source, payloads.size, payloads.map(_.getTextRange).distinct.size)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScReferenceExpression]).isEmpty)
      assertEquals(source, namedCount, PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyTypeArguments]).size())
      assertEquals(
        source,
        namedCount,
        PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyNamedTypeArgument]).size()
      )

    val detachedCases = Vector(
      "val value = call(make[A = Int])\n",
      "val named = call(arg = make[A = Int])\n",
      "val contextual = call(using make[A = Int])\n",
      "def owner(value: Int = make[A = Int]) = value\n",
      "@ann(make[A = Int]) val annotated = 1\n",
      "class Child extends Parent[make[A = Int]]\n",
      "type Refined = AnyRef { val value: make[A = Int] }\n",
      "val malformed = make[A = ]\n",
      "val trailing = make[A = Int,]\n"
    )
    detachedCases.zipWithIndex.foreach: (body, index) =>
      val source  = s"import scala.language.experimental.namedTypeArguments\n$body"
      val pending = myFixture.addFileToProject(s"src/AppliedNamedDetached${index + 1}.scala", source)
      val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
      file.getChildren
      assertEquals(source, file.getText)
      assertTrue(source, genericCalls(file).isEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScReferenceExpression]).isEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeArgs]).isEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyNamedTypeArgument]).isEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyExpressionPayload]).isEmpty)
      val failure = Scala3SyntaxCapabilityService
        .get(getProject)
        .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))
      assertTrue(source, failure.nonEmpty)

  def testNamedGenericCallsRemainAstOnlyAcrossStubSerializationAndAstReload(): Unit =
    assertEquals(15, Scala3DotcFileElementType.SchemaVersion)
    assertEquals(
      "c14af31a5208fc230bc4bce30cd394a2dddab331af06db9682b8436469b55657",
      Scala3DotcFileElementType.PersistenceSchemaFingerprint
    )
    val source      =
      "package named\nimport scala.language.experimental.namedTypeArguments\nval direct = target.make[A = Int]\nval invoked = target.make[A = Int](value)\n"
    val file        = physical("AppliedNamedPersistence1.scala", source).asInstanceOf[PsiFileImpl]
    val tree        = file.calcStubTree
    val stubs       = tree.getPlainList.asScala.toVector
    val beforeShape = stubShape(stubs)
    val beforeIndex = indexShape(stubs)
    assertFalse(beforeShape.exists(row => row.contains("GenericCall") || row.contains("TypeArgs")))
    assertEquals(Vector("target.make[A = Int]", "target.make[A = Int]"), genericCalls(file).map(_.getText))
    assertEquals(
      Vector("target.make[A = Int](value)"),
      PsiTreeUtil.findChildrenOfType(file, classOf[ScMethodCall]).asScala.map(_.getText).toVector
    )

    val output   = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(tree.getRoot, output)
    val bytes    = output.toByteArray
    val restored = new StubTree(
      SerializationManagerEx.getInstanceEx
        .deserialize(new ByteArrayInputStream(bytes))
        .asInstanceOf[PsiFileStub[?]]
    )
    assertEquals(beforeShape, stubShape(restored.getPlainList.asScala))
    assertEquals(beforeIndex, indexShape(restored.getPlainList.asScala))
    val repeated = new ByteArrayOutputStream
    SerializationManagerEx.getInstanceEx.serialize(restored.getRoot, repeated)
    assertArrayEquals(bytes, repeated.toByteArray)

    file.setTreeElementPointer(null)
    assertNull(file.getTreeElement)
    assertEquals(beforeShape, stubShape(file.getStubTree.getPlainList.asScala))
    assertEquals(beforeIndex, indexShape(file.getStubTree.getPlainList.asScala))
    val reopened = genericCalls(file)
    assertEquals(Vector("target.make[A = Int]", "target.make[A = Int]"), reopened.map(_.getText))
    reopened.foreach(call => assertSame(call, call.getNavigationElement))

  def testTypeInferenceFixtureSourcesKeepMarkerAndRightTriviaOutsideTheExactExpression(): Unit =
    Vector(
      """import scala.language.experimental.namedTypeArguments
        |def make[A]: A = ???
        |val value = /*start*/make[A = Int]/*end*/
        |//Int
        |""".stripMargin -> "make[A = Int]",
      """import scala.language.experimental.namedTypeArguments
        |def pair[A, B](a: A, b: B): B = b
        |val value = /*start*/pair[A = Int](1, "text")/*end*/
        |//String
        |""".stripMargin -> "pair[A = Int](1, \"text\")"
    ).zipWithIndex.foreach { case ((source, expected), index) =>
      val file       = physical(s"AppliedNamedFixture${index + 1}.scala", source)
      val start      = source.indexOf(expected)
      val expression = PsiTreeUtil.findElementOfClassAtRange(
        file,
        start,
        start + expected.length,
        classOf[ScExpression]
      )
      assertEquals(expected, expression.getText)
    }

  private def genericCalls(file: com.intellij.psi.PsiFile): Vector[ScGenericCall] =
    PsiTreeUtil
      .findChildrenOfType(file, classOf[ScGenericCall])
      .asScala
      .toVector
      .sortBy(call => (call.getTextRange.getStartOffset, -call.getTextLength))

  private def stubShape(stubs: Iterable[Stub]): Vector[String] = stubs.iterator
    .flatMap(stub =>
      Option(stub.getStubSerializer).map(serializer => s"${stub.getClass.getName}|${serializer.getExternalId}")
    )
    .toVector

  private def indexShape(stubs: Iterable[Stub]): Vector[String] =
    val result = Vector.newBuilder[String]
    val sink   = new IndexSink:
      override def occurrence[Psi <: com.intellij.psi.PsiElement, K](indexKey: StubIndexKey[K, Psi], value: K): Unit =
        result += s"${indexKey.toString}|${value.toString}"
    stubs.foreach(stub =>
      Option(stub.getStubSerializer).foreach(
        _.asInstanceOf[ObjectStubSerializer[Stub, Stub]].indexStub(stub, sink)
      )
    )
    result.result()

  private def assertNoPartialInvokedNamedCallPsi(source: String, file: com.intellij.psi.PsiFile): Unit =
    val expressions = PsiTreeUtil.findChildrenOfType(file, classOf[ScExpression]).asScala.toVector
    assertEquals(source, 1, expressions.size)
    assertTrue(source, expressions.head.isInstanceOf[MetallurgyExpressionPayload])
    assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScReferenceExpression]).isEmpty)
    assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScGenericCall]).isEmpty)
    assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScMethodCall]).isEmpty)
    assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScArgumentExprList]).isEmpty)
    assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeArgs]).isEmpty)
    assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyTypeArguments]).isEmpty)
    assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyNamedTypeArgument]).isEmpty)
    assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScIntegerLiteral]).isEmpty)
    assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScStringLiteral]).isEmpty)

  private final case class RecoveryError(
      implementation: String,
      elementType: String,
      description: String,
      text: String,
      relativeStart: Int,
      relativeEnd: Int
  )

  private final case class RecoveryObservation(
      errors: Vector[RecoveryError],
      expressionCount: Int,
      payloadCount: Int,
      genericCallCount: Int,
      typeArgsCount: Int,
      namedArgumentCount: Int
  )

  private def recoveryObservation(file: com.intellij.psi.PsiFile, source: String): RecoveryObservation =
    val origin = source.indexOf("make")
    RecoveryObservation(
      PsiTreeUtil
        .findChildrenOfType(file, classOf[PsiErrorElement])
        .asScala
        .toVector
        .map(error =>
          RecoveryError(
            error.getClass.getName,
            error.getNode.getElementType.toString,
            error.getErrorDescription,
            error.getText,
            error.getTextRange.getStartOffset - origin,
            error.getTextRange.getEndOffset - origin
          )
        ),
      PsiTreeUtil.findChildrenOfType(file, classOf[ScExpression]).size(),
      PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyExpressionPayload]).size(),
      PsiTreeUtil.findChildrenOfType(file, classOf[ScGenericCall]).size(),
      PsiTreeUtil.findChildrenOfType(file, classOf[ScTypeArgs]).size(),
      PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyNamedTypeArgument]).size()
    )

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
