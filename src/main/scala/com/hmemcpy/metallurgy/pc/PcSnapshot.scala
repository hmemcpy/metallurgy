package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.LocalFileSystem

final case class PcSnapshot(
    fileUri: String,
    documentVersion: Long,
    sourceText: String
) {
  def isStale(currentVersion: Long): Boolean = documentVersion != currentVersion
}

object PcSnapshot {
  def forFile(file: VirtualFile, version: Long): PcSnapshot = {
    val uri  = file.getUrl
    val text = new String(file.contentsToByteArray, file.getCharset)
    PcSnapshot(uri, version, text)
  }
}
