package com.hmemcpy.metallurgy.feature.compilertype

import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.hmemcpy.metallurgy.pc.PcSessionManager
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.lang.psi.impl.CompilerType

final class CompilerTypeHijack(project: Project) extends com.intellij.openapi.Disposable {

  private val Log = Logger.getInstance(classOf[CompilerTypeHijack])

  locally {
    val bus = project.getMessageBus.connect(this)
    bus.subscribe(CompilerType.Topic, new CompilerType.Listener {
      override def onCompilerTypeRequest(e: PsiElement): Unit = handleRequest(e)
    })
  }

  private def handleRequest(element: PsiElement): Unit = {
    val module = org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil.getModule(element) match {
      case null => return
      case m    => m
    }

    if (!ModuleDetectionService.get(project).isEligible(module)) return
    if (!MetallurgySettings(project).isEnabled(module)) return

    val sessionManager = PcSessionManager.get(project)
    sessionManager.sessionFor(module).foreach { session =>
      val offset = element.getTextRange.getStartOffset
      val file = element.getContainingFile
      val vfile = file.getVirtualFile
      if (vfile != null) {
        val snapshot = com.hmemcpy.metallurgy.pc.PcSnapshot.forFile(
          vfile,
          file.getModificationStamp
        )
        val typeString = TypeRenderer.render(session, snapshot, offset)
        typeString.foreach { tpe =>
          CompilerType(element) = Some(tpe)
        }
      }
    }
  }

  def dispose(): Unit = {}
}

object CompilerTypeHijack {
  def apply(project: Project): CompilerTypeHijack = project.getService(classOf[CompilerTypeHijack])
}