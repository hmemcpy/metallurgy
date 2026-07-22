package com.hmemcpy.metallurgy.compilerbackend

import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.intellij.openapi.module.ModuleUtilCore
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement

import scala.util.control.NonFatal

private[metallurgy] object BundledCompilerBackendDispatcher:

  def declaredType(element: Object): Object =
    element match
      case typeElement: ScTypeElement => lookup(typeElement)
      case _                          => null

  private def lookup(element: ScTypeElement): Object =
    try
      val module = ModuleUtilCore.findModuleForPsiElement(element)
      if module == null then null
      else
        val project   = element.getProject
        val detection = ModuleDetectionService.get(project)
        if !detection.isActive(module) then null
        else
          Scala3CompilerBackend
            .get(project)
            .stateForActiveModule(element, module, CompilerBackendRole.DeclaredType) match
            case CompilerBackendState.Current(_, result) => result.asInstanceOf[Object]
            case _                                       => null
    catch case NonFatal(_) => null
