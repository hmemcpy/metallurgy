package com.hmemcpy.metallurgy.feature.compilertype

import com.hmemcpy.metallurgy.pc.{PcSession, PcSnapshot}
import com.intellij.openapi.util.TextRange

object TypeRenderer:

  def render(session: PcSession, snapshot: PcSnapshot, offset: Int): Option[String] =
    render(session, snapshot, TextRange.from(offset, 0))

  def render(session: PcSession, snapshot: PcSnapshot, range: TextRange): Option[String] =
    session.inlineType(snapshot, range)
