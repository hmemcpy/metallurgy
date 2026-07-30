package com.hmemcpy.metallurgy.status

import com.hmemcpy.metallurgy.pc.Scala3ParserLoaderIdentity.*
import com.hmemcpy.metallurgy.psiproducer.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.{StatusBar, StatusBarWidget, StatusBarWidgetFactory}

final class MetallurgyStatusBarWidgetFactory extends StatusBarWidgetFactory:
  override def getId: String = MetallurgyStatusBarWidgetFactory.Id

  override def getDisplayName: String = "Metallurgy"

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

  override def getText: String = currentSyntaxPresentation.fold(semanticText)(_.text)

  override def getTooltipText: String = currentSyntaxPresentation.fold(semanticTooltip)(_.tooltip)

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

  private def currentSyntaxPresentation: Option[MetallurgyStatusBarWidget.Presentation] =
    MetallurgyStatusBarWidget.syntaxPresentation(Scala3SyntaxCapabilityService.get(project).currentReports)

  private def semanticText: String = semanticStatus match
    case MetallurgyStatus.Enabled                     => "Metallurgy: enabled"
    case MetallurgyStatus.Resolving(_)                => "Metallurgy: resolving…"
    case MetallurgyStatus.Resolved(_, tpe)            => s"Metallurgy: ${abbreviate(tpe)}"
    case MetallurgyStatus.NoType(_)                   => "Metallurgy: no type"
    case MetallurgyStatus.Unavailable(_)              => "Metallurgy: unavailable"
    case MetallurgyStatus.Failed(_, _)                => "Metallurgy: error"
    case MetallurgyStatus.SyntaxCapability(_)         => "Metallurgy: syntax unavailable"
    case MetallurgyStatus.SyntaxCapabilityResolved(_) => "Metallurgy: syntax ready"

  private def semanticTooltip: String = semanticStatus match
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
      s"Metallurgy exact Scala syntax is unavailable for ${report.scope.moduleName}."
    case MetallurgyStatus.SyntaxCapabilityResolved(report) =>
      s"Metallurgy exact Scala syntax capability is ready for ${report.scope.moduleName}."

private[status] object MetallurgyStatusBarWidget:
  final case class Presentation(text: String, tooltip: String)

  def syntaxPresentation(reports: Vector[Scala3SyntaxCapabilityReport]): Option[Presentation] =
    val current = reports.distinct.sortBy(report =>
      (
        report.scope.moduleName,
        report.scope.file.fold("")(_.getPresentableUrl),
        report.scope.operation.toString
      )
    )
    Option.when(current.nonEmpty):
      val preparing   = current.count(_.state == Scala3SyntaxCapabilityState.Preparing)
      val unavailable = current.count(_.state == Scala3SyntaxCapabilityState.Unavailable)
      val text        = (preparing, unavailable) match
        case (1, 0) => "Metallurgy: preparing syntax…"
        case (n, 0) => s"Metallurgy: preparing syntax ($n)…"
        case (0, 1) => "Metallurgy: syntax unavailable"
        case (0, n) => s"Metallurgy: syntax unavailable ($n)"
        case (p, u) => s"Metallurgy: syntax unavailable ($u), preparing ($p)"
      val sections    = current.zipWithIndex.map: (report, index) =>
        val rows = Vector(
          row("State", report.state.toString),
          row("Affected scope and operation", scope(report)),
          row("Missing capability or stable role", requirement(report.requirement)),
          row("Compiler identity", compiler(report)),
          row("Host identity", host(report.hostIdentity)),
          row("Retained safe operations", report.retainedOperations.mkString(", ")),
          row("Evidence", evidence(report.evidence)),
          row("Remediation and retry", report.remediation.toString),
          row("Preparation epoch", report.preparationEpoch.value.toString)
        ) ++ report.sourceDigest.map(value => row("Source digest", value))
        s"<b>Report ${index + 1} of ${current.size}</b><br>${rows.mkString("<br>")}"
      Presentation(
        text,
        s"<html><b>Metallurgy exact Scala syntax capability</b><br><br>${sections.mkString("<br><br>")}</html>"
      )

  private def scope(report: Scala3SyntaxCapabilityReport): String =
    val file = report.scope.file.fold("")(value => s"; file=${value.getPresentableUrl}")
    s"module=${report.scope.moduleName}$file; operation=${report.scope.operation}"

  private def requirement(value: Scala3SyntaxCapabilityRequirement): String = value match
    case Scala3SyntaxCapabilityRequirement.Capability(id)    => s"capability=$id"
    case Scala3SyntaxCapabilityRequirement.GrammarRole(role) => s"grammar-role=${role.getOrElse("<unidentified>")}"
    case Scala3SyntaxCapabilityRequirement.OutputRole(role)  => s"output-role=${role.getOrElse("<unidentified>")}"

  private def compiler(report: Scala3SyntaxCapabilityReport): String =
    report.compilerIdentity
      .map: identity =>
        val artifacts =
          if identity.artifacts.isEmpty then "<none>"
          else
            identity.artifacts
              .map(artifact =>
                s"${artifact.fileName}[bytes=${artifact.byteSize};sha256=${artifact.sha256};ordinal=${artifact.ordinal}]"
              )
              .mkString(",")
        s"${coordinate(identity.coordinate)}; loader=${identity.loader.value}; artifacts=$artifacts"
      .orElse(report.compilerCoordinate.map(coordinate))
      .getOrElse("<unknown>")

  private def coordinate(value: com.hmemcpy.metallurgy.pc.Scala3ParserArtifactCoordinate): String =
    s"${value.organization}:${value.artifact}:${value.version}"

  private def host(value: Scala3SyntaxHostIdentity): String =
    val scalaPluginVersion = value.scalaPluginVersion.getOrElse("<unknown>")
    s"IDE build=${value.ideBuild}; Scala plugin=${value.scalaPluginId}:$scalaPluginVersion"

  private def evidence(value: Scala3SyntaxCapabilityEvidence): String =
    s"state=${value.state}; stage=${value.stage}; detail=${value.detail}"

  private def row(label: String, value: String): String =
    s"<b>$label:</b> ${StringUtil.escapeXmlEntities(value)}"
