package com.hmemcpy.metallurgy.psiproducer

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.icons.AllIcons
import javax.swing.Icon

final class Scala3ParserPendingLanguage private () extends Language("Scala 3 parser pending"):
  override def getAssociatedFileType: LanguageFileType = Scala3ParserPendingFileType.INSTANCE

object Scala3ParserPendingLanguage:
  val INSTANCE: Scala3ParserPendingLanguage = new Scala3ParserPendingLanguage

final class Scala3ParserPendingFileType private () extends LanguageFileType(Scala3ParserPendingLanguage.INSTANCE):

  override def getName: String = "Scala 3 parser pending"

  override def getDescription: String = "Verbatim source awaiting an exact Scala 3 parser"

  override def getDefaultExtension: String = "scala-pending"

  override def getIcon: Icon = AllIcons.FileTypes.Text

object Scala3ParserPendingFileType:
  val INSTANCE: Scala3ParserPendingFileType = new Scala3ParserPendingFileType
