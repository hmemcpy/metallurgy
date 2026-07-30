package com.hmemcpy.metallurgy.status

import com.hmemcpy.metallurgy.psiproducer.Scala3SyntaxCapabilityService
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.{StatusBar, StatusBarWidget, StatusBarWidgetFactory}

final class MetallurgyStatusBarWidgetFactory extends StatusBarWidgetFactory:
  override def getId: String = MetallurgyStatusBarWidgetFactory.Id

  override def getDisplayName: String = "Metallurgy"

  override def isAvailable(project: Project): Boolean = project.isOpen

  override def createWidget(project: Project): StatusBarWidget =
    MetallurgyStatusBarWidget(project)

  override def canBeEnabledOn(statusBar: StatusBar): Boolean = statusBar.getProject != null

private object MetallurgyStatusBarWidgetFactory:
  val Id = "Metallurgy"

private final class MetallurgyStatusBarWidget(project: Project)
    extends StatusBarWidget,
      StatusBarWidget.TextPresentation,
      MetallurgyStatusListener:

  @volatile private var semanticStatus: MetallurgyStatus = MetallurgyStatus.Enabled
  @volatile private var statusBar: Option[StatusBar]     = None

  private val connection = project.getMessageBus.connect(this)

  override def ID(): String = MetallurgyStatusBarWidgetFactory.Id

  override def getPresentation: StatusBarWidget.WidgetPresentation = this

  override def install(installedStatusBar: StatusBar): Unit =
    statusBar = Some(installedStatusBar)
    connection.subscribe(MetallurgyStatus.Topic, this)

  override def getText: String = currentStatus match
    case MetallurgyStatus.Enabled                     => "Metallurgy: enabled"
    case MetallurgyStatus.Resolving(_)                => "Metallurgy: resolving…"
    case MetallurgyStatus.Resolved(_, tpe)            => s"Metallurgy: ${abbreviate(tpe)}"
    case MetallurgyStatus.NoType(_)                   => "Metallurgy: no type"
    case MetallurgyStatus.Unavailable(_)              => "Metallurgy: unavailable"
    case MetallurgyStatus.Failed(_, _)                => "Metallurgy: error"
    case MetallurgyStatus.SyntaxCapability(_)         => "Metallurgy: syntax unavailable"
    case MetallurgyStatus.SyntaxCapabilityResolved(_) => "Metallurgy: syntax ready"

  override def getTooltipText: String = currentStatus match
    case MetallurgyStatus.Enabled                          =>
      "Metallurgy is enabled, but has not written a compiler type in this session."
    case MetallurgyStatus.Resolving(moduleName)            =>
      s"Metallurgy is resolving a compiler type in $moduleName."
    case MetallurgyStatus.Resolved(moduleName, tpe)        =>
      s"Last compiler type written by Metallurgy in $moduleName: $tpe"
    case MetallurgyStatus.NoType(moduleName)               =>
      s"The Metallurgy presentation compiler returned no type in $moduleName."
    case MetallurgyStatus.Unavailable(moduleName)          =>
      s"No Metallurgy presentation compiler session is available for $moduleName."
    case MetallurgyStatus.Failed(moduleName, detail)       =>
      s"Metallurgy failed to resolve a compiler type in $moduleName: $detail"
    case MetallurgyStatus.SyntaxCapability(report)         =>
      val target   = report.scope.file.fold(report.scope.moduleName)(_.getPresentableUrl)
      val identity = report.compilerIdentity
        .map(_.toString)
        .orElse(
          report.compilerCoordinate.map(coordinate =>
            s"${coordinate.organization}:${coordinate.artifact}:${coordinate.version}"
          )
        )
        .fold("")(value => s" using $value")
      s"Metallurgy cannot represent exact Scala syntax for $target at ${report.evidence.stage}$identity: ${report.evidence.detail}"
    case MetallurgyStatus.SyntaxCapabilityResolved(report) =>
      val target = report.scope.file.fold(report.scope.moduleName)(_.getPresentableUrl)
      s"Metallurgy exact Scala syntax capability is ready for $target."

  override def getAlignment: Float = 0.5f

  override def statusChanged(newStatus: MetallurgyStatus): Unit =
    newStatus match
      case _: MetallurgyStatus.SyntaxCapability | _: MetallurgyStatus.SyntaxCapabilityResolved => ()
      case semantic                                                                            => semanticStatus = semantic
    statusBar.foreach(_.updateWidget(ID()))

  override def dispose(): Unit =
    statusBar = None

  private def abbreviate(tpe: String): String =
    val MaxLength = 36
    if tpe.length <= MaxLength then tpe else s"${tpe.take(MaxLength - 1)}…"

  private def currentStatus: MetallurgyStatus =
    Scala3SyntaxCapabilityService
      .get(project)
      .currentFailures
      .headOption
      .map(MetallurgyStatus.SyntaxCapability.apply)
      .getOrElse(semanticStatus)
