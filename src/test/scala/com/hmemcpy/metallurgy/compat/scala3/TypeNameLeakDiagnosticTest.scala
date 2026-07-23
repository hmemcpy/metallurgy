package com.hmemcpy.metallurgy.compat.scala3

import com.hmemcpy.metallurgy.compilerbackend.{CompilerBackendRole, Scala3CompilerBackend}
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.ScalaFileType
import org.jetbrains.plugins.scala.lang.psi.api.expr.ScReferenceExpression
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction

import scala.jdk.CollectionConverters.*

/** Diagnostic: dumps what the compiler backend renders for the elements of the `testSetter` snippet, to localize where
  * the `(name: Type)` form (e.g. `(y: Int)`) enters — the bridge render or the bundled annotator. Configures and awaits
  * the backend directly (no error assertion) so the dump runs regardless of reported errors.
  */
final class TypeNameLeakDiagnosticTest extends Scala3CompatTestCase:

  def testDumpSetterTypes(): Unit =
    myFixture.configureByText(
      ScalaFileType.INSTANCE,
      wrapForHighlighting(
        """
          |class Foo {
          |  private var _x = 1
          |  def x(using String): Int = _x
          |  def x_=(y: Int)(using String): Unit = _x = y
          |}
          |object Foo {
          |  def main(args: Array[String]): Unit = {
          |    val foo = Foo()
          |    given String = "foo"
          |    foo.x = 5
          |  }
          |}
          |""".stripMargin.trim
      )
    )
    awaitBackendPublished()
    val backend = Scala3CompilerBackend.get(getProject)
    val module  = com.intellij.openapi.module.ModuleUtilCore.findModuleForPsiElement(getFile)
    val refs    = PsiTreeUtil.findChildrenOfType(getFile, classOf[ScReferenceExpression]).asScala.toList
    println("[diag] === reference compiler types ===")
    refs.foreach: ref =>
      val exact = backend.validatedCompilerType(ref, module, CompilerBackendRole.ExpressionExact)
      println(s"[diag] ref='${ref.getText}' range=${ref.getTextRange} ExpressionExact=$exact")
    val fns     = PsiTreeUtil.findChildrenOfType(getFile, classOf[ScFunction]).asScala.toList
    println("[diag] === function exact/declared types ===")
    fns.foreach: fn =>
      val exact = backend.validatedCompilerType(fn, module, CompilerBackendRole.ExpressionExact)
      val decl  = backend.stateForActiveModule(fn, module, CompilerBackendRole.DeclaredType)
      println(s"[diag] fn='${fn.getText.take(48).replace("\n", " ")}' Exact=$exact Declared=$decl")
