package com.hmemcpy.metallurgy.pc

private[metallurgy] final case class PcSourceRange(startOffset: Int, endOffset: Int):
  require(startOffset >= 0, s"start offset must be non-negative: $startOffset")
  require(endOffset >= startOffset, s"end offset $endOffset precedes start offset $startOffset")
