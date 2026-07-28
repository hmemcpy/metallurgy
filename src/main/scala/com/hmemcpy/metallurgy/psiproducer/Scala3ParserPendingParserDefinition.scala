package com.hmemcpy.metallurgy.psiproducer

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.{ASTFactory, ASTNode, ParserDefinition, PsiParser}
import com.intellij.lexer.{EmptyLexer, Lexer}
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.tree.{IElementType, IFileElementType, TokenSet}
import com.intellij.psi.util.PsiUtilCore
import com.intellij.psi.{FileViewProvider, PsiElement, PsiFile}

final class Scala3ParserPendingParserDefinition extends ParserDefinition:
  import Scala3ParserPendingParserDefinition.*

  override def createLexer(project: Project): Lexer = new EmptyLexer

  override def createParser(project: Project): PsiParser =
    throw new UnsupportedOperationException("The pending file element parses its content directly")

  override def getFileNodeType: IFileElementType = FileNodeType

  override def getWhitespaceTokens: TokenSet = TokenSet.EMPTY

  override def getCommentTokens: TokenSet = TokenSet.EMPTY

  override def getStringLiteralElements: TokenSet = TokenSet.EMPTY

  override def createElement(node: ASTNode): PsiElement = PsiUtilCore.NULL_PSI_ELEMENT

  override def createFile(viewProvider: FileViewProvider): PsiFile =
    new Scala3ParserPendingFile(viewProvider)

  override def spaceExistenceTypeBetweenTokens(
      left: ASTNode,
      right: ASTNode
  ): ParserDefinition.SpaceRequirements = ParserDefinition.SpaceRequirements.MAY

private object Scala3ParserPendingParserDefinition:
  private val ContentElementType =
    IElementType("SCALA3_PARSER_PENDING_CONTENT", Scala3ParserPendingLanguage.INSTANCE)

  val FileNodeType: IFileElementType =
    new IFileElementType("SCALA3_PARSER_PENDING_FILE", Scala3ParserPendingLanguage.INSTANCE):
      override def parseContents(chameleon: ASTNode): ASTNode =
        ASTFactory.leaf(ContentElementType, chameleon.getChars)

private final class Scala3ParserPendingFile(viewProvider: FileViewProvider)
    extends PsiFileBase(viewProvider, Scala3ParserPendingLanguage.INSTANCE):

  override def getFileType: FileType = getViewProvider.getVirtualFile.getFileType

  override def toString: String = "Scala 3 parser pending file"
