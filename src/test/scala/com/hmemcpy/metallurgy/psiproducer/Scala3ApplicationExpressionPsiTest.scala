package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import com.hmemcpy.metallurgy.pc.ParserSyntaxSnapshot
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.{PsiDocumentManager, PsiErrorElement, PsiManager, SmartPointerManager}
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenType
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScSequenceArg
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScArgumentExprList, ScMethodCall}
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeArgs
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScGenericCall, ScReferenceExpression}
import org.jetbrains.plugins.scala.lang.psi.impl.metallurgy.{MetallurgyExpressionPayload, MetallurgyTypeArguments}
import org.junit.Assert.{assertEquals, assertFalse, assertSame, assertTrue}

import scala.jdk.CollectionConverters.*

final class Scala3ApplicationExpressionPsiTest extends Scala3CompatTestCase:
  def testNativeExplicitUsingCallBindingIsCapabilityProbed(): Unit =
    val bindings           = NativePsiElementBindings.probe(getProject).fold(error => throw new AssertionError(error), identity)
    val namedTermCandidate =
      Scala3PsiNamedArgumentProductions.CandidateProductionId ->
        Scala3PsiNamedArgumentProductions.NativeRealizationId

    assertEquals(ScalaTokenType.UsingKeyword, bindings.elementTypes(NativePsiElementBindings.UsingKeywordTokenSurface))
    assertEquals(
      Some(SurfaceFactKind.Token),
      bindings.surfaceRows.find(_.id == NativePsiElementBindings.UsingKeywordTokenSurface).map(_.kind)
    )
    assertTrue(bindings.elementTypes.contains(Scala3PsiProductionSupport.NamedArgumentSurface))
    assertTrue(bindings.unavailableRealizations.subsetOf(Set(namedTermCandidate)))

  def testOrdinaryDirectRhsApplicationsExposeNativeCallsAndArgumentLists(): Unit =
    val source =
      """def empty = f()
        |val one = f(x)
        |var many = f(x, 1)
        |val selected = source.member(x)
        |def nested = outer(inner(x), source.member(1))
        |val curried = f()(x)
        |class Parent:
        |  def inherited = 1
        |class C extends Parent:
        |  val member = f(this)
        |  val thisApplied = this.member()
        |  val superApplied = super.inherited()
        |""".stripMargin
    val file   = physical("ApplicationExpressions1.scala", source)
    val calls  = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScMethodCall])
      .asScala
      .toVector
      .sortBy(call => (call.getTextRange.getStartOffset, -call.getTextLength))

    assertEquals(
      Vector(
        "f()",
        "f(x)",
        "f(x, 1)",
        "source.member(x)",
        "outer(inner(x), source.member(1))",
        "inner(x)",
        "source.member(1)",
        "f()(x)",
        "f()",
        "f(this)",
        "this.member()",
        "super.inherited()"
      ),
      calls.map(_.getText)
    )
    calls.foreach: call =>
      assertEquals("org.jetbrains.plugins.scala.lang.psi.impl.expr.ScMethodCallImpl", call.getClass.getName)
      assertSame(call, call.getInvokedExpr.getParent)
      assertSame(call, call.args.getParent)
      assertEquals(call.args.exprs.toVector, call.argumentExpressions.toVector)
      assertTrue(call.args.isArgsInParens)
      assertFalse(call.args.isUsing)
      assertEquals(call.args.exprs.size, call.args.getArgsCount)
      assertEquals(call.getText, call.getTextRange.substring(source))
    assertEquals(calls.size, PsiTreeUtil.findChildrenOfType(file, classOf[ScArgumentExprList]).size)
    val stubClasses = file.asInstanceOf[PsiFileImpl].calcStubTree.getPlainList.asScala.map(_.getClass.getName)
    assertFalse(stubClasses.exists(_.contains("MethodCall")))

  def testExplicitUsingApplicationsExposeNativeCallsArgumentsAndKeywordInSourceOrder(): Unit =
    val source =
      """val one = f(using x)
        |val many = g(using 1, "x")
        |val selected = source.member(using x)
        |val nested = outer(using inner(using x), source.member(using 1))
        |val curried = h(1)(using "x")
        |val comments = f(/* before */ using /* after */ x)
        |val trailingComment = f(using x /* trailing */)
        |val newlines = f(
        |  using
        |  x
        |)
        |""".stripMargin
    val file   = physical("ApplicationUsing1.scala", source)
    val calls  = PsiTreeUtil
      .findChildrenOfType(file, classOf[ScMethodCall])
      .asScala
      .toVector
      .sortBy(call => (call.getTextRange.getStartOffset, -call.getTextLength))

    assertEquals(
      Vector(
        "f(using x)",
        "g(using 1, \"x\")",
        "source.member(using x)",
        "outer(using inner(using x), source.member(using 1))",
        "inner(using x)",
        "source.member(using 1)",
        "h(1)(using \"x\")",
        "h(1)",
        "f(/* before */ using /* after */ x)",
        "f(using x /* trailing */)",
        "f(\n  using\n  x\n)"
      ),
      calls.map(_.getText)
    )
    calls.foreach: call =>
      assertSame(call, call.getInvokedExpr.getParent)
      assertSame(call, call.args.getParent)
      assertEquals(call.args.exprs.toVector, call.argumentExpressions.toVector)
      assertEquals(call.args.exprs.size, call.args.getArgsCount)
      assertEquals(call.getText, call.getTextRange.substring(source))
      val keywords =
        call.args.getNode.getChildren(null).toVector.filter(_.getElementType == ScalaTokenType.UsingKeyword)
      if call.getText == "h(1)" then
        assertFalse(call.args.isUsing)
        assertTrue(keywords.isEmpty)
      else
        assertTrue(call.args.isUsing)
        assertEquals(1, keywords.size)
        assertEquals("using", keywords.head.getText)
        assertSame(call.args, keywords.head.getPsi.getParent)
    val one = calls.head
    assertEquals(Vector("(", "using", " ", "x", ")"), one.args.getNode.getChildren(null).toVector.map(_.getText))

  def testExplicitUsingCopiesPointersEditsAndReparsesDeterministically(): Unit =
    val source   = "val value = f(using x)\n"
    val file     = physical("ApplicationUsingEdits1.scala", source)
    val call     = PsiTreeUtil.findChildOfType(file, classOf[ScMethodCall])
    val pointer  = SmartPointerManager.getInstance(getProject).createSmartPsiElementPointer(call)
    val copied   = PsiTreeUtil.findChildOfType(file.copy(), classOf[ScMethodCall])
    assertEquals("f(using x)", copied.getText)
    assertTrue(copied.args.isUsing)
    val document = PsiDocumentManager.getInstance(getProject).getDocument(file)

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.replaceString(source.indexOf("x"), source.indexOf("x") + 1, "next")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("f(using next)", pointer.getElement.getText)
    assertTrue(pointer.getElement.args.isUsing)

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val start = document.getText.indexOf("using ")
          document.deleteString(start, start + "using ".length)
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("f(next)", pointer.getElement.getText)
    assertFalse(pointer.getElement.args.isUsing)

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.insertString(document.getText.indexOf("next"), "using ")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    assertEquals("f(using next)", pointer.getElement.getText)
    assertTrue(pointer.getElement.args.isUsing)

  def testUnsupportedApplicationsRemainOneOpaquePayloadWithoutNativeDescendants(): Unit =
    val sources = Vector(
      "val value = f(using name = x)"           -> Some("f(using name = x)"),
      "val value = f(using (x, y))"             -> Some("f(using (x, y))"),
      "val value = f(using { x })"              -> Some("f(using { x })"),
      "val value = f[Int](using x)"             -> Some("f[Int](using x)"),
      "val value = f((x, y))"                   -> Some("f((x, y))"),
      "val value = f { x }"                     -> Some("f { x }"),
      "val value = xs map f"                    -> Some("xs map f"),
      "def owner = { val local = f(x); local }" -> None
    )
    sources.zipWithIndex.foreach: (entry, index) =>
      val (source, expectedPayload) = entry
      val file                      = physical(s"ApplicationFallback$index.scala", source)
      val payloads                  = PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyExpressionPayload]).asScala.toVector
      expectedPayload.foreach(expected => assertEquals(source, Vector(expected), payloads.map(_.getText)))
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScMethodCall]).isEmpty)
      assertTrue(source, PsiTreeUtil.findChildrenOfType(file, classOf[ScArgumentExprList]).isEmpty)
      payloads.foreach: payload =>
        assertTrue(source, PsiTreeUtil.findChildrenOfType(payload, classOf[ScMethodCall]).isEmpty)
        assertTrue(source, PsiTreeUtil.findChildrenOfType(payload, classOf[ScArgumentExprList]).isEmpty)
        assertTrue(source, PsiTreeUtil.findChildrenOfType(payload, classOf[ScReferenceExpression]).isEmpty)

  def testRepeatedUsingArgumentsRemainASeparateHardNegative(): Unit =
    val source  = "val value = f(using xs*)"
    val pending = myFixture.addFileToProject("src/ApplicationRepeated.scala", source)
    val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    file.getChildren
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))

    assertTrue(failure.toString, failure.isEmpty)
    val payloads = PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyExpressionPayload]).asScala.toVector
    assertEquals(Vector("f(using xs*)"), payloads.map(_.getText))
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScMethodCall]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScArgumentExprList]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScSequenceArg]).isEmpty)
    assertTrue(
      PsiTreeUtil
        .findChildrenOfType(file, classOf[org.jetbrains.plugins.scala.lang.psi.api.expr.ScTypedExpression])
        .isEmpty
    )

  def testControlFlowArgumentsRemainASeparateHardNegative(): Unit =
    val source  = "val value = f(if condition then x else y)"
    val pending = myFixture.addFileToProject("src/ApplicationControl.scala", source)
    val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    file.getChildren
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))

    assertTrue(failure.toString, failure.exists(_.detail.contains("UncoveredCompilerShape")))
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyExpressionPayload]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScMethodCall]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScArgumentExprList]).isEmpty)

  def testPositionalAndAllNamedTypeApplicationsExposeNativeInvokedCalls(): Unit =
    val source        =
      """import scala.language.experimental.namedTypeArguments
        |val positional = f[Int](x)
        |val selected = source.f[String](x)
        |val named = f[A = Int](x)
        |""".stripMargin
    val file          = physical("ApplicationTypeFallback.scala", source)
    val payloads      = PsiTreeUtil
      .findChildrenOfType(file, classOf[MetallurgyExpressionPayload])
      .asScala
      .toVector
      .filter(_.getText == "f[A = Int](x)")
    val methodCalls   = PsiTreeUtil.findChildrenOfType(file, classOf[ScMethodCall]).asScala.toVector
    val genericCalls  = PsiTreeUtil.findChildrenOfType(file, classOf[ScGenericCall]).asScala.toVector
    val typeArguments = genericCalls.map(_.typeArgs) ++
      payloads.flatMap(payload => PsiTreeUtil.findChildrenOfType(payload, classOf[ScTypeArgs]).asScala)

    assertTrue(payloads.isEmpty)
    assertEquals(Vector("f[Int](x)", "source.f[String](x)", "f[A = Int](x)"), methodCalls.map(_.getText))
    assertEquals(Vector("f[Int]", "source.f[String]", "f[A = Int]"), genericCalls.map(_.getText))
    methodCalls
      .zip(genericCalls)
      .foreach: (methodCall, genericCall) =>
        assertSame(genericCall, methodCall.getInvokedExpr)
        assertSame(methodCall, methodCall.args.getParent)
    assertEquals(Vector("[Int]", "[String]", "[A = Int]"), typeArguments.map(_.getText))
    assertEquals(
      Vector("A = Int"),
      typeArguments
        .collect { case value: MetallurgyTypeArguments => value }
        .flatMap(_.namedTypeArguments)
        .map(_.getText)
    )
    assertEquals(Vector("(x)", "(x)", "(x)"), methodCalls.map(_.args.getText))

  def testConstructorSyntheticApplicationRemainsASeparateHardNegative(): Unit =
    val source  = "val value = new C(x)"
    val pending = myFixture.addFileToProject("src/ApplicationConstructor.scala", source)
    val file    = PsiManager.getInstance(getProject).findFile(pending.getVirtualFile)
    file.getChildren
    val failure = Scala3SyntaxCapabilityService
      .get(getProject)
      .failureFor(pending.getVirtualFile, ParserSyntaxSnapshot.digest(source))

    assertTrue(failure.toString, failure.exists(_.detail.contains("UncoveredCompilerShape")))
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[MetallurgyExpressionPayload]).isEmpty)
    assertTrue(PsiTreeUtil.findChildrenOfType(file, classOf[ScMethodCall]).isEmpty)

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
