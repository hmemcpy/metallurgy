package com.hmemcpy.metallurgy.psiproducer

import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import org.jetbrains.plugins.scala.ScalaFileType
import org.jetbrains.plugins.scala.lang.parser.ScalaParserDefinitionBase
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaFileImpl

/** Parser definition for the Metallurgy Scala 3 dialect. The file element supplies its completed plan-backed lexer and
  * emitter directly. A dialect-owned file element type keeps the stub root distinct, and file creation remains
  * dialect-aware so IntelliJ's language extension lookup applies.
  */
final class Scala3DotcParserDefinition extends ScalaParserDefinitionBase:
  override def getFileNodeType: Scala3DotcFileElementType            = Scala3DotcParserDefinition.FileNodeType
  override def createLexer(project: Project)                         = PlannedScala3Lexer.closed
  override def createParser(project: Project)                        =
    (root, builder) =>
      val marker = builder.mark()
      while !builder.eof() do builder.advanceLexer()
      marker.done(root)
      builder.getTreeBuilt
  override def createFile(viewProvider: FileViewProvider): ScalaFile =
    new ScalaFileImpl(viewProvider, ScalaFileType.INSTANCE, Scala3DotcLanguage.INSTANCE)

object Scala3DotcParserDefinition:
  val FileNodeType: Scala3DotcFileElementType = new Scala3DotcFileElementType
