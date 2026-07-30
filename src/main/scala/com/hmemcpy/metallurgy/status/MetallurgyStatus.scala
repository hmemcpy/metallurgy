package com.hmemcpy.metallurgy.status

import com.hmemcpy.metallurgy.psiproducer.Scala3SyntaxCapabilityReport
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

private[metallurgy] enum MetallurgyStatus:
  case Enabled
  case Resolving(moduleName: String)
  case Resolved(moduleName: String, tpe: String)
  case NoType(moduleName: String)
  case Unavailable(moduleName: String)
  case Failed(moduleName: String, detail: String)
  case SyntaxCapability(report: Scala3SyntaxCapabilityReport)
  case SyntaxCapabilityResolved(report: Scala3SyntaxCapabilityReport)

private[metallurgy] trait MetallurgyStatusListener:
  def statusChanged(status: MetallurgyStatus): Unit

private[metallurgy] object MetallurgyStatus:
  val Topic: Topic[MetallurgyStatusListener] =
    new Topic("Metallurgy status bar update", classOf[MetallurgyStatusListener])

  def publish(project: Project, status: MetallurgyStatus): Unit =
    project.getMessageBus.syncPublisher(Topic).statusChanged(status)
