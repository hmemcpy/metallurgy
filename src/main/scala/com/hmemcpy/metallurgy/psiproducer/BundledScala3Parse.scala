package com.hmemcpy.metallurgy.psiproducer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.Scala3Language

/** Probes whether the bundled Scala 3 parser (not the dialect) would fragment a source into error elements. Used to
  * decide whether the producer should take over a source the compiler accepts: only where the bundled parser cannot
  * represent it. Parses with the bundled Scala 3 language directly so the dialect's pending placeholder never masks the
  * answer.
  */
object BundledScala3Parse:

  def hasErrors(source: String, project: Project): Boolean =
    if ApplicationManager.getApplication.isReadAccessAllowed then probe(source, project)
    else
      ApplicationManager.getApplication.runReadAction(
        new Computable[Boolean]:
          override def compute(): Boolean = probe(source, project)
      )

  private def probe(source: String, project: Project): Boolean =
    val file = PsiFileFactory.getInstance(project).createFileFromText("Probe.scala", Scala3Language.INSTANCE, source)
    PsiTreeUtil.hasErrorElements(file)
