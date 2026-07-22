package com.hmemcpy.metallurgy.pc

import scala.concurrent.duration.FiniteDuration

private[metallurgy] final case class PcTypedTreeMetrics(
    extractionDuration: FiniteDuration,
    traversalDuration: FiniteDuration,
    renderingDuration: FiniteDuration,
    visitedTreeCount: Int,
    positionedTreeCount: Int,
    candidateCount: Int,
    retainedEntryCount: Int,
    deduplicatedCandidateCount: Int,
    compilerWrapperOverlapCount: Int,
    renderedTypeCount: Int
)
