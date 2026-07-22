package com.hmemcpy.metallurgy.pc

private[metallurgy] final case class PcTypedTreeEntry(
    range: PcSourceRange,
    role: PcTypedTreeRole,
    renderedType: String,
    symbol: Option[PcCompilerSymbol]
)
