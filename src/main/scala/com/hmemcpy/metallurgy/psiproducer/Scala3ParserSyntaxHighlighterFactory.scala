package com.hmemcpy.metallurgy.psiproducer

import com.intellij.lexer.{EmptyLexer, Lexer}
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.{SyntaxHighlighter, SyntaxHighlighterFactory}
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.LanguageSubstitutors
import com.intellij.psi.tree.IElementType
import org.jetbrains.plugins.scala.{Scala3Language, ScalaLanguage}
import org.jetbrains.plugins.scala.highlighter.{
  ScalaSyntaxHighlighterFactory,
  ScalaSyntaxHighlighterFactory as ScalaHighlighter
}

final class Scala3ParserSyntaxHighlighterFactory extends SyntaxHighlighterFactory:
  override def getSyntaxHighlighter(project: Project, file: VirtualFile): SyntaxHighlighter =
    val language =
      if project == null || file == null then ScalaLanguage.INSTANCE
      else LanguageSubstitutors.getInstance.substituteLanguage(ScalaLanguage.INSTANCE, file, project)
    if language == Scala3ParserPendingLanguage.INSTANCE then Scala3ParserPendingSyntaxHighlighter
    else if language == Scala3DotcLanguage.INSTANCE then
      ScalaHighlighter.createScalaSyntaxHighlighter(project, file, Scala3Language.INSTANCE)
    else new ScalaSyntaxHighlighterFactory().getSyntaxHighlighter(project, file)

private object Scala3ParserPendingSyntaxHighlighter extends SyntaxHighlighter:
  override def getHighlightingLexer: Lexer = new EmptyLexer

  override def getTokenHighlights(tokenType: IElementType): Array[TextAttributesKey] =
    TextAttributesKey.EMPTY_ARRAY
