package com.hmemcpy.metallurgy.compilerbackend

/** Session inputs that must still match when a mapped file snapshot reaches its commit boundary. */
private[metallurgy] final case class CompilerBackendGeneration(
    session: Long,
    classpath: Long,
    compilerOptions: Long
)
