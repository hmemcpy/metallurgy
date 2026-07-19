package com.hmemcpy.metallurgy.module

import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.notification.{Notification, NotificationAction, NotificationGroupManager, NotificationType}
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.module.Module

object FirstDetectionNotifier {

  private final val GroupId = "metallurgy.general"

  def notify(module: Module): Unit = {
    val project = module.getProject
    val settings = MetallurgySettings(project)
    val moduleName = module.getName

    if (!settings.shouldAsk(moduleName)) return
    if (settings.isEnabled(module)) return

    val notification = NotificationGroupManager.getInstance
      .getNotificationGroup(GroupId)
      .createNotification(
        "Metallurgy: Scala 3.5+ module detected",
        s"Enable Metallurgy for accurate types, completion, and error highlighting in <b>$moduleName</b>?",
        NotificationType.INFORMATION
      )

    notification.addAction(NotificationAction.createExpiring("Enable", (_: AnActionEvent, _: Notification) => {
      settings.setEnabled(moduleName, enabled = true)
    }))

    notification.addAction(NotificationAction.createExpiring("Not now", (_: AnActionEvent, _: Notification) => ()))

    notification.addAction(NotificationAction.createExpiring("Never ask for this module", (_: AnActionEvent, _: Notification) => {
      settings.neverAsk(moduleName)
    }))

    notification.notify(project)
    // Suppress re-prompting within the session; the setting persists across restarts.
    settings.neverAsk(moduleName)
  }
}
