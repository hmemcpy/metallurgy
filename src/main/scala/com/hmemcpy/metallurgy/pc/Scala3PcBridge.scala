package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.util.TextRange

import java.io.File

/** Compiler-facing seam for operations that the published Scalameta presentation-compiler interface does not expose
  * with the structure required by the Scala PSI adapter. Compiler implementation values stay behind this interface.
  */
private[pc] trait Scala3PcBridge extends AutoCloseable:

  def retypecheck(snapshot: PcSnapshot): Unit

  def typeAt(snapshot: PcSnapshot, range: TextRange): Option[String]

  def diagnostics(snapshot: PcSnapshot): Seq[PcDiagnostic]

  def typedTreeSnapshot(snapshot: PcSnapshot, currency: () => PcSnapshotCurrency): PcTypedTreeExtraction

  def structuralCompletions(snapshot: PcSnapshot, offset: Int): Seq[PcCompletion]

private[pc] object Scala3PcBridge:

  def open(
      classloader: ClassLoader,
      compilerClasspath: Seq[File],
      compilerOptions: Seq[String]
  ): Scala3PcBridge =
    new StructuralScala3PcBridge(classloader, compilerClasspath, compilerOptions)
