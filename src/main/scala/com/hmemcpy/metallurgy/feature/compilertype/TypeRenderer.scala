package com.hmemcpy.metallurgy.feature.compilertype

import com.hmemcpy.metallurgy.pc.{PcSession, PcSnapshot}

object TypeRenderer:

  def render(session: PcSession, snapshot: PcSnapshot, offset: Int): Option[String] =
    session.inlineType(snapshot, offset)
