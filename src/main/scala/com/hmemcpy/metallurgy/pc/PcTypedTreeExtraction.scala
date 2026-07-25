package com.hmemcpy.metallurgy.pc

private[metallurgy] enum PcTypedTreeExtraction:
  case Completed(snapshot: PcTypedTreeSnapshot)
  case Superseded
