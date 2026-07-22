package com.hmemcpy.metallurgy.pc

private[metallurgy] final case class PcCompilerSymbol(
    id: String,
    flags: Set[String],
    ownerId: Option[String],
    navigation: Option[PcNavigationTarget]
)
