package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.{Module, ModuleUtilCore}
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory
import org.jetbrains.plugins.scala.lang.psi.types.result.TypeResult

import java.util.concurrent.ConcurrentHashMap

final class Scala3CompilerBackend(project: Project):

  private final case class ElementKey(
      module: Module,
      fileUrl: String,
      startOffset: Int,
      endOffset: Int,
      role: CompilerBackendRole
  )
  private final case class VersionedState(documentVersion: Long, state: CompilerBackendState)

  private val states = new ConcurrentHashMap[ElementKey, VersionedState]()

  def publish(
      element: PsiElement,
      role: CompilerBackendRole,
      documentVersion: Long,
      renderedType: String
  ): CompilerBackendPublication =
    activeModule(element) match
      case Some(module) =>
        ScalaPsiElementFactory.createTypeFromText(renderedType, element, null) match
          case Some(scType) =>
            val result: TypeResult = Right(scType)
            put(element, module, role, documentVersion, CompilerBackendState.Current(renderedType, result))
            CompilerBackendPublication.Published
          case None         =>
            put(element, module, role, documentVersion, CompilerBackendState.Unavailable)
            CompilerBackendPublication.Unparsable
      case None         => CompilerBackendPublication.IgnoredInactive

  def publishState(
      element: PsiElement,
      role: CompilerBackendRole,
      documentVersion: Long,
      state: CompilerBackendState
  ): Unit =
    activeModule(element).foreach(module => put(element, module, role, documentVersion, state))

  private[compilerbackend] def stateForActiveModule(
      element: PsiElement,
      module: Module,
      role: CompilerBackendRole
  ): CompilerBackendState =
    (key(element, module, role), currentDocument(element)) match
      case (Some(elementKey), Some(document)) =>
        Option(states.get(elementKey)) match
          case Some(entry) if entry.documentVersion == document.getModificationStamp => entry.state
          case Some(_)                                                               => CompilerBackendState.Pending
          case None                                                                  => CompilerBackendState.Unavailable
      case _                                  => CompilerBackendState.Unavailable

  def clear(): Unit = states.clear()

  def clear(module: Module): Unit =
    val _ = states.keySet().removeIf(_.module == module)

  private def put(
      element: PsiElement,
      module: Module,
      role: CompilerBackendRole,
      documentVersion: Long,
      state: CompilerBackendState
  ): Unit =
    key(element, module, role).foreach(states.put(_, VersionedState(documentVersion, state)))

  private def key(element: PsiElement, module: Module, role: CompilerBackendRole): Option[ElementKey] =
    if element.getProject != project then None
    else
      for
        file      <- Option(element.getContainingFile)
        virtual   <- Option(file.getVirtualFile)
        textRange <- Option(element.getTextRange)
      yield ElementKey(module, virtual.getUrl, textRange.getStartOffset, textRange.getEndOffset, role)

  private def activeModule(element: PsiElement): Option[Module] =
    if element.getProject != project then None
    else
      Option(ModuleUtilCore.findModuleForPsiElement(element))
        .filter(ModuleDetectionService.get(project).isActive)

  private def currentDocument(element: PsiElement): Option[Document] =
    Option(element.getContainingFile)
      .flatMap(file => Option(file.getVirtualFile))
      .flatMap(file => Option(FileDocumentManager.getInstance().getDocument(file)))

object Scala3CompilerBackend:
  def get(project: Project): Scala3CompilerBackend = project.getService(classOf[Scala3CompilerBackend])
