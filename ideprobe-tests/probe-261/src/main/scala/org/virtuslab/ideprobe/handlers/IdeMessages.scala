package org.virtuslab.ideprobe.handlers

import java.io.{PrintWriter, StringWriter}

import scala.jdk.CollectionConverters._

import com.intellij.diagnostic.MessagePool
import com.intellij.ide.plugins.PluginUtil

import org.virtuslab.ideprobe.log.{Message, MessageLog}
import org.virtuslab.ideprobe.protocol.IdeMessage

object IdeMessages {
  def list: Array[IdeMessage] = {
    val fatal = MessagePool
      .getInstance
      .getFatalErrors(true, true)
      .asScala
      .map(value => Message(Option(value.getMessage), Option(value.getThrowable), IdeMessage.Level.Error))
    (MessageLog.all ++ fatal)
      .distinct
      .map { value =>
        val pluginId = value.throwable
          .flatMap(error => Option(PluginUtil.getInstance.findPluginId(error)))
          .map(_.getIdString)
        IdeMessage(value.level, render(value), pluginId)
      }
      .toArray
  }

  private def render(value: Message): String = {
    val throwable = value.throwable.map { error =>
      val output = new StringWriter
      error.printStackTrace(new PrintWriter(output))
      output.toString
    }
    (value.content.toVector ++ throwable).mkString("\n")
  }
}
