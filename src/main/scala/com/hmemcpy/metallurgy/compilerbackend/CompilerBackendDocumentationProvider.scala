package com.hmemcpy.metallurgy.compilerbackend

import com.intellij.lang.documentation.{AbstractDocumentationProvider, DocumentationMarkup}
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement

/** Documentation fallback for generation-scoped compiler symbols that have no source Scala PSI declaration. */
final class CompilerBackendDocumentationProvider extends AbstractDocumentationProvider:

  override def getQuickNavigateInfo(element: PsiElement, originalElement: PsiElement): String =
    compilerSignature(element).orNull

  override def generateDoc(element: PsiElement, originalElement: PsiElement): String =
    compilerSignature(element)
      .map(signature =>
        DocumentationMarkup.DEFINITION_START + StringUtil.escapeXmlEntities(
          signature
        ) + DocumentationMarkup.DEFINITION_END
      )
      .orNull

  private def compilerSignature(element: PsiElement): Option[String] =
    element match
      case symbol: CompilerBackendLightSymbol if symbol.isValid =>
        val prefix = if symbol.flags.exists(_.toLowerCase.contains("method")) then "def" else "val"
        Some(s"$prefix ${symbol.getName}: ${symbol.renderedType}")
      case symbol: CompilerBackendLightClass if symbol.isValid  =>
        Some(s"type ${symbol.getName} = ${symbol.renderedType}")
      case _                                                    => None
