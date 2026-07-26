package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.CompilerTreeExtraction

import java.util.concurrent.ConcurrentHashMap

/** Cache-only source of the compiler's typed tree for a dialect file. The dialect file-root parse consults this during
  * [[Scala3DotcFileElementType.doParseContents]] keyed by the verbatim source text; it never starts compiler work or
  * blocks. A test installs a fixed extraction and clears it; production is fed by the background pc session.
  */
object DotcTreeSource:

  private val bySource = new ConcurrentHashMap[String, CompilerTreeExtraction]()

  /** Install the extraction for a source and report whether the cache changed. Returns false when the same extraction
    * is already installed, so a caller re-analyzing after a reload (the daemon re-running the backend pass) learns the
    * producer generation is already applied and suppresses a redundant re-reparse.
    */
  def install(source: String, extraction: CompilerTreeExtraction): Boolean =
    val prev = bySource.put(source, extraction)
    prev == null || prev != extraction

  def clear(): Unit =
    bySource.clear()

  private[psiproducer] def extractionFor(source: CharSequence): Option[CompilerTreeExtraction] =
    Option(bySource.get(source.toString))
