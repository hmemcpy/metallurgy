package com.hmemcpy.metallurgy.pc

private[metallurgy] final case class PcCompilerSymbol(
    id: String,
    name: String,
    flags: Set[String],
    ownerId: Option[String],
    navigation: Option[PcNavigationTarget],
    isType: Boolean = false,
    qualifiedName: Option[String] = None,
    isDeferred: Boolean = false
)
