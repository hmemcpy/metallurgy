package org.virtuslab.ideprobe

import pureconfig.generic.auto._

import org.virtuslab.ideprobe.ProbeHandlers.ProbeHandler
import org.virtuslab.ideprobe.jsonrpc.JsonRpc.Method.Request
import org.virtuslab.ideprobe.protocol.FileRef

object MetallurgyStatusEndpoint {
  val Status = Request[FileRef, Map[String, String]]("metallurgy/status")
}

final class MetallurgyStatusContributor extends ProbeHandlerContributor {
  override def registerHandlers(handler: ProbeHandler): ProbeHandler =
    handler.on(MetallurgyStatusEndpoint.Status)(MetallurgyStatus.inspect)
}
