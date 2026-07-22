package com.hmemcpy.metallurgy.compilerbackend

/** Installs the Scala-plugin-side semantic adapters.
  *
  * Consumers depend on this interface, not on instrumentation or installed Scala-plugin implementation classes. The
  * bridge discovers compatible semantic roots from their public PSI ancestry and callable shape. Unsupported shapes
  * leave the bundled implementation unchanged.
  */
private[metallurgy] object ScalaPluginSemanticBridge:

  def install(): CompilerBackendShimStatus =
    BundledCompilerBackendShim.install()
