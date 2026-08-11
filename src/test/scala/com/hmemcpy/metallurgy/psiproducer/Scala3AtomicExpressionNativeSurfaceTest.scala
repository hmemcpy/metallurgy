package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.compat.scala3.Scala3CompatTestCase
import org.junit.Assert.{assertEquals, assertTrue}

final class Scala3AtomicExpressionNativeSurfaceTest extends Scala3CompatTestCase:
  def testNativeAtomicExpressionRolesTokensAndMethodsAreCapabilityProbed(): Unit =
    val bindings = NativePsiElementBindings.probe(getProject).fold(error => throw new AssertionError(error), identity)
    val roles    = Vector(
      PsiOutputRoleId.TermReference,
      PsiOutputRoleId.ThisReference,
      PsiOutputRoleId.IntegerExpression,
      PsiOutputRoleId.LongExpression,
      PsiOutputRoleId.FloatExpression,
      PsiOutputRoleId.DoubleExpression,
      PsiOutputRoleId.BooleanExpression,
      PsiOutputRoleId.CharExpression,
      PsiOutputRoleId.StringExpression,
      PsiOutputRoleId.NullExpression
    )
    assertTrue(roles.forall(bindings.outputRoles.contains))

    val tokens = Vector(
      NativePsiElementBindings.IntegerLiteralTokenSurface,
      NativePsiElementBindings.LongLiteralTokenSurface,
      NativePsiElementBindings.FloatLiteralTokenSurface,
      NativePsiElementBindings.DoubleLiteralTokenSurface,
      NativePsiElementBindings.CharLiteralTokenSurface,
      NativePsiElementBindings.StringLiteralTokenSurface
    )
    assertTrue(tokens.forall(bindings.elementTypes.contains))
    assertEquals(
      tokens.map(_ -> SurfaceFactKind.Token),
      bindings.surfaceRows.filter(row => tokens.contains(row.id)).map(row => row.id -> row.kind)
    )
    assertEquals(
      Some(SurfaceFactKind.Method),
      ScalaPsiSurfaceInventory
        .installed()
        .fold(error => throw new AssertionError(error), identity)
        .rows
        .find(_.id == "org/jetbrains/plugins/scala/lang/psi/api/base/ScReference#refName()Ljava/lang/String;")
        .map(_.kind)
    )
