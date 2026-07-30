package com.hmemcpy.metallurgy.psiproducer

import com.intellij.lang.Language
import com.intellij.openapi.module.{Module, ModuleUtilCore}
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiFile, PsiManager, SingleRootFileViewProvider}
import org.jetbrains.plugins.scala.lang.psi.ScFileViewProvider

/** View provider for dialect files. Extends the same base the bundled ScFileViewProvider uses and mirrors its file
  * creation, so all Scala PSI consumers see a real ScalaFileImpl bound to the dialect. Owning the provider lets
  * Metallurgy drive the file's content lifecycle independently of the platform's reparse machinery.
  */
final class Scala3DotcFileViewProvider(
    manager: PsiManager,
    file: VirtualFile,
    eventSystemEnabled: Boolean,
    language: Language,
    moduleHint: Option[Module] = None
) extends SingleRootFileViewProvider(
      manager,
      file,
      eventSystemEnabled,
      ScFileViewProvider.calcBaseLanguage(file, language)
    ):

  private[psiproducer] val module: Option[Module] =
    moduleHint.orElse(Option(ModuleUtilCore.findModuleForFile(file, manager.getProject)))

  override def createFile(project: Project, file: VirtualFile, fileType: FileType): PsiFile =
    createFile(getBaseLanguage)

  override def createCopy(copy: VirtualFile): Scala3DotcFileViewProvider =
    new Scala3DotcFileViewProvider(getManager, copy, eventSystemEnabled = false, getBaseLanguage, module)
