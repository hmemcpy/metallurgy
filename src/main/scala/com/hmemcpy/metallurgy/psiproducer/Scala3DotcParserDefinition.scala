package com.hmemcpy.metallurgy.psiproducer

import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import org.jetbrains.plugins.scala.ScalaFileType
import org.jetbrains.plugins.scala.lang.lexer.ScalaLexer
import org.jetbrains.plugins.scala.lang.parser.{ScalaParser, ScalaParserDefinitionBase}
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaFileImpl
import org.jetbrains.plugins.scala.lang.psi.stubs.elements.ScStubFileElementType

/** Parser definition for the Metallurgy Scala 3 dialect. Lexing and parsing delegate to the bundled Scala 3 parser;
  * only file creation is dialect-aware, passing [[Scala3DotcLanguage]] so the file accepts the dialect and IntelliJ's
  * dialect-aware extension lookup applies. A dialect-owned [[ScStubFileElementType]] keeps the stub root distinct (the
  * stub builder asks the view provider for the exact language).
  */
final class Scala3DotcParserDefinition extends ScalaParserDefinitionBase:
  override def getFileNodeType: ScStubFileElementType                = Scala3DotcParserDefinition.FileNodeType
  override def createLexer(project: Project): ScalaLexer             = new ScalaLexer(true, project)
  override def createParser(project: Project): ScalaParser           = new ScalaParser(isScala3 = true)
  override def createFile(viewProvider: FileViewProvider): ScalaFile =
    new ScalaFileImpl(viewProvider, ScalaFileType.INSTANCE, Scala3DotcLanguage.INSTANCE)

object Scala3DotcParserDefinition:
  val FileNodeType: ScStubFileElementType = ScStubFileElementType(Scala3DotcLanguage.INSTANCE)
