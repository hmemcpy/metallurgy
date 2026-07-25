package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.CompilerTreeExtraction

import java.util.concurrent.ConcurrentHashMap

/** Cache-only source of the compiler's typed tree for a dialect file. The dialect file-root parse consults this during
  * [[Scala3DotcFileElementType.doParseContents]] keyed by the verbatim source text; it never starts compiler work or
  * blocks. A test installs a fixed extraction and clears it; production is fed by the background pc session.
  */
object DotcTreeSource:

  private val bySource = new ConcurrentHashMap[String, CompilerTreeExtraction]()

  def install(source: String, extraction: CompilerTreeExtraction): Unit =
    val _ = bySource.put(source, extraction)

  def clear(): Unit =
    bySource.clear()

  private[psiproducer] def extractionFor(source: CharSequence): Option[CompilerTreeExtraction] =
    Option(bySource.get(source.toString))
