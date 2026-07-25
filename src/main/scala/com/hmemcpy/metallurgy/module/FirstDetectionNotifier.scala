package com.hmemcpy.metallurgy.module

import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.notification.{Notification, NotificationAction, NotificationGroupManager, NotificationType}
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.module.Module

object FirstDetectionNotifier {

  private final val GroupId = "metallurgy.general"

  def notify(modules: Seq[Module]): Unit = {
    val project = modules.headOption.map(_.getProject).orNull
    if project == null then return

    val settings     = MetallurgySettings(project)
    val modulesToAsk = modules.filter(module => settings.shouldAsk(module.getName) && !settings.isEnabled(module))
    if modulesToAsk.isEmpty then return

    val moduleNames       = modulesToAsk.map(_.getName)
    val moduleDescription =
      if moduleNames.size == 1 then s"<b>${moduleNames.head}</b>"
      else s"all ${moduleNames.size} detected modules"

    val notification = NotificationGroupManager.getInstance
      .getNotificationGroup(GroupId)
      .createNotification(
        "Metallurgy: Scala 3 module detected",
        s"Enable Metallurgy for accurate types, completion, and error highlighting in $moduleDescription?",
        NotificationType.INFORMATION
      )

    notification.addAction(
      NotificationAction.createExpiring(
        "Enable",
        (_: AnActionEvent, _: Notification) => {
          modulesToAsk.foreach(settings.setEnabled(_, enabled = true))
        }
      )
    )

    notification.addAction(NotificationAction.createExpiring("Not now", (_: AnActionEvent, _: Notification) => ()))

    notification.addAction(
      NotificationAction.createExpiring(
        "Never ask for this module",
        (_: AnActionEvent, _: Notification) => {
          moduleNames.foreach(settings.neverAsk)
        }
      )
    )

    notification.notify(project)
  }
}
