package com.hmemcpy.metallurgy.pc

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID

private[pc] object PcSourceUri:

  /** Dotty converts interactive URIs to NIO paths. IntelliJ test, scratch, and injected files may use other schemes. */
  def normalize(fileUri: String): URI =
    val uri = URI.create(fileUri)
    if uri.getScheme == "file" then uri
    else
      val stableName = UUID.nameUUIDFromBytes(fileUri.getBytes(StandardCharsets.UTF_8))
      Path.of(System.getProperty("java.io.tmpdir"), "metallurgy-pc", s"$stableName.scala").toUri
