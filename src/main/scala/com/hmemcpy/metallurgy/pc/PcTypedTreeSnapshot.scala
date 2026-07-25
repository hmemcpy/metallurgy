package com.hmemcpy.metallurgy.pc

private[metallurgy] final case class PcTypedTreeSnapshot(
    fileUri: String,
    documentVersion: Long,
    entries: Vector[PcTypedTreeEntry],
    metrics: PcTypedTreeMetrics
)
