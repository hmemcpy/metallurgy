package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.util.TextRange
import scala.meta.pc.PresentationCompiler

import java.io.File
import java.util.concurrent.{ThreadPoolExecutor, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

/** Compiler-facing seam for operations that the published Scalameta presentation-compiler interface does not expose
  * with the structure required by the Scala PSI adapter. Compiler implementation values stay behind this interface.
  */
private[pc] trait Scala3PcBridge extends AutoCloseable:

  def retypecheck(snapshot: PcSnapshot): Unit

  def typeAt(snapshot: PcSnapshot, range: TextRange): Option[String]

  def diagnostics(snapshot: PcSnapshot): Seq[PcDiagnostic]

  def typedTreeSnapshot(snapshot: PcSnapshot, currency: () => PcSnapshotCurrency): PcTypedTreeExtraction

  /** The typed tree as source-classified DTOs: physical nodes (real, non-zero, source-derived spans) separated from
    * synthetic ones. A separate extraction path from the slot overlay; used by PSI production.
    */
  def compilerTreeDto(snapshot: PcSnapshot, currency: () => PcSnapshotCurrency): Option[CompilerTreeDto]

  /** The untyped (parser) tree as source-classified DTOs. The parser tree preserves the source grammar the typed tree
    * desugars away (param-clause kinds, for-comprehensions, extension, modifiers); it is the source of truth for PSI
    * grammar production.
    */
  def untypedTreeDto(snapshot: PcSnapshot, currency: () => PcSnapshotCurrency): Option[CompilerTreeDto]

  /** The typed tree and the compiler's diagnostics for the same snapshot, extracted together. */
  def compilerTreeExtraction(snapshot: PcSnapshot, currency: () => PcSnapshotCurrency): Option[CompilerTreeExtraction]

  /** The untyped (parser) tree paired with the same run's diagnostics; the producer's data source for faithful grammar.
    */
  def untypedTreeExtraction(snapshot: PcSnapshot, currency: () => PcSnapshotCurrency): Option[CompilerTreeExtraction]

  def semanticdbOccurrences(bytes: Array[Byte], sourceText: String): Vector[PcSemanticdbOccurrence]

  def structuralCompletions(snapshot: PcSnapshot, offset: Int): Seq[PcCompletion]

private[pc] object Scala3PcBridge:

  /** The published Scalameta interface does not enumerate compiler options or expose retained trees. This probe reads
    * only public JVM shape inside the exact compiler's isolated classloader; implementation values never cross the
    * bridge boundary.
    */
  def discoverCapabilities(
      classloader: ClassLoader,
      compilerArtifacts: Seq[File]
  ): Scala3PcBridgeCapabilities =
    val provider = PresentationCompilerDiscovery.load(classloader, compilerArtifacts)
    try discoverCapabilities(classloader, provider)
    finally provider.foreach(shutdownQuietly)

  def discoverCapabilities(
      classloader: ClassLoader,
      provider: Either[PresentationCompilerDiscoveryError, PresentationCompiler]
  ): Scala3PcBridgeCapabilities =
    val (basePresentationCompiler, publicOperations) = publicPcCapabilities(provider)
    val driver                                       = classShape(
      classloader,
      "dotty.tools.dotc.interactive.InteractiveDriver",
      Set("run" -> 2, "close" -> 1, "currentCtx" -> 0)
    )
    val inlineTypes                                  = driver.flatMap(requireMethods(_, Set("openedFiles" -> 0, "openedTrees" -> 0)))
    val snapshots                                    = driver.flatMap(requireMethods(_, Set("compilationUnits" -> 0)))
    val semanticdbParser                             = classShape(
      classloader,
      "dotty.tools.dotc.semanticdb.TextDocument$",
      Set("parseFrom" -> 1)
    )
    val settings                                     = loadClass(classloader, "dotty.tools.dotc.config.YSettings")

    Scala3PcBridgeCapabilities(
      basePresentationCompiler = basePresentationCompiler,
      shutdownBarrier = provider.fold(
        _ => PcCapabilityStatus.Unavailable("the presentation compiler is unavailable"),
        compiler =>
          if PresentationCompilerLifecycle.supports(compiler) then PcCapabilityStatus.Available
          else PcCapabilityStatus.Unavailable("the presentation compiler job queue cannot be awaited")
      ),
      completion = publicOperation(basePresentationCompiler, publicOperations, "complete"),
      hover = publicOperation(basePresentationCompiler, publicOperations, "hover"),
      semanticdb = publicOperation(basePresentationCompiler, publicOperations, "semanticdbTextDocument") match
        case PcCapabilityStatus.Available if semanticdbParser.nonEmpty => PcCapabilityStatus.Available
        case PcCapabilityStatus.Available                              =>
          PcCapabilityStatus.Unavailable("the exact compiler does not expose a SemanticDB parser")
        case unavailable: PcCapabilityStatus.Unavailable               => unavailable,
      inlineTypes = inlineTypes.toStatus("InteractiveDriver does not expose typed expression lookup"),
      typedTreeSnapshots = snapshots.toStatus("InteractiveDriver does not expose retained compilation units"),
      structuralCompletions = inlineTypes.toStatus("InteractiveDriver does not expose typed qualifier lookup"),
      bestEffortProduction = settings
        .flatMap(requireMethods(_, Set("YbestEffort" -> 0)))
        .toStatus("the exact compiler does not expose best-effort TASTy production"),
      bestEffortConsumption = settings
        .flatMap(requireMethods(_, Set("YwithBestEffortTasty" -> 0)))
        .toStatus("the exact compiler does not expose best-effort TASTy consumption"),
      publicOperations = publicOperations
    )

  def open(
      classloader: ClassLoader,
      compilerClasspath: Seq[File],
      compilerOptions: Seq[String]
  ): Scala3PcBridge =
    new StructuralScala3PcBridge(classloader, compilerClasspath, compilerOptions)

  def captureExecutor(provider: PresentationCompiler): Either[String, ThreadPoolExecutor] =
    PresentationCompilerLifecycle.captureExecutor(provider)

  def shutdown(provider: PresentationCompiler): Either[String, Unit] =
    PresentationCompilerLifecycle.shutdown(provider)

  def awaitTermination(executor: ThreadPoolExecutor, timeoutNanos: Long): Either[String, Unit] =
    PresentationCompilerLifecycle.awaitTermination(executor, timeoutNanos)

  private def classShape(
      classloader: ClassLoader,
      className: String,
      requiredMethods: Set[(String, Int)]
  ): Option[Class[?]] =
    loadClass(classloader, className).flatMap(requireMethods(_, requiredMethods))

  private def loadClass(classloader: ClassLoader, className: String): Option[Class[?]] =
    try Some(Class.forName(className, false, classloader))
    catch case NonFatal(_) => None

  private def requireMethods(shape: Class[?], required: Set[(String, Int)]): Option[Class[?]] =
    val available = shape.getMethods.iterator.map(method => method.getName -> method.getParameterCount).toSet
    Option.when(required.subsetOf(available))(shape)

  private def publicPcCapabilities(
      provider: Either[PresentationCompilerDiscoveryError, PresentationCompiler]
  ): (PcCapabilityStatus, Set[String]) =
    provider match
      case Left(error)     => PcCapabilityStatus.Unavailable(error.message) -> Set.empty
      case Right(provider) =>
        val operations = provider.getClass.getMethods.iterator.map(_.getName).toSet
        PcCapabilityStatus.Available -> operations

  private def publicOperation(
      base: PcCapabilityStatus,
      operations: Set[String],
      operation: String
  ): PcCapabilityStatus =
    base match
      case unavailable: PcCapabilityStatus.Unavailable => unavailable
      case PcCapabilityStatus.Available                =>
        if operations(operation) then PcCapabilityStatus.Available
        else PcCapabilityStatus.Unavailable(s"the published presentation compiler does not expose $operation")

  private def shutdownQuietly(provider: PresentationCompiler): Unit =
    val _ = Scala3PcBridge.shutdown(provider)

  extension (shape: Option[Class[?]])
    private def toStatus(reason: String): PcCapabilityStatus =
      if shape.nonEmpty then PcCapabilityStatus.Available else PcCapabilityStatus.Unavailable(reason)

private[metallurgy] enum PcCapabilityStatus:
  case Available
  case Unavailable(reason: String)

  def isAvailable: Boolean = this == Available

private[metallurgy] final case class Scala3PcBridgeCapabilities(
    basePresentationCompiler: PcCapabilityStatus,
    shutdownBarrier: PcCapabilityStatus,
    completion: PcCapabilityStatus,
    hover: PcCapabilityStatus,
    semanticdb: PcCapabilityStatus,
    inlineTypes: PcCapabilityStatus,
    typedTreeSnapshots: PcCapabilityStatus,
    structuralCompletions: PcCapabilityStatus,
    bestEffortProduction: PcCapabilityStatus,
    bestEffortConsumption: PcCapabilityStatus,
    publicOperations: Set[String]
):
  def unavailableReasons: Seq[String] =
    productIterator.collect { case PcCapabilityStatus.Unavailable(reason) => reason }.toSeq

  def presentationCompilerOptions(options: Seq[String]): Seq[String] =
    val base = options.filterNot(Scala3PcBridgeCapabilities.BestEffortOptions.contains)
    if bestEffortConsumption.isAvailable then base :+ Scala3PcBridgeCapabilities.BestEffortConsumerOption else base

  def buildCompilerOptions: Seq[String] =
    Seq(
      Option.when(bestEffortProduction.isAvailable)(Scala3PcBridgeCapabilities.BestEffortProducerOption),
      Option.when(bestEffortConsumption.isAvailable)(Scala3PcBridgeCapabilities.BestEffortConsumerOption)
    ).flatten

private[metallurgy] object Scala3PcBridgeCapabilities:
  val BestEffortProducerOption = "-Ybest-effort"
  val BestEffortConsumerOption = "-Ywith-best-effort-tasty"
  val BestEffortOptions        = Set(BestEffortProducerOption, BestEffortConsumerOption)

  val unavailable: Scala3PcBridgeCapabilities = Scala3PcBridgeCapabilities(
    basePresentationCompiler = PcCapabilityStatus.Unavailable("not discovered"),
    shutdownBarrier = PcCapabilityStatus.Unavailable("not discovered"),
    completion = PcCapabilityStatus.Unavailable("not discovered"),
    hover = PcCapabilityStatus.Unavailable("not discovered"),
    semanticdb = PcCapabilityStatus.Unavailable("not discovered"),
    inlineTypes = PcCapabilityStatus.Unavailable("not discovered"),
    typedTreeSnapshots = PcCapabilityStatus.Unavailable("not discovered"),
    structuralCompletions = PcCapabilityStatus.Unavailable("not discovered"),
    bestEffortProduction = PcCapabilityStatus.Unavailable("not discovered"),
    bestEffortConsumption = PcCapabilityStatus.Unavailable("not discovered"),
    publicOperations = Set.empty
  )

  val bestEffort: Scala3PcBridgeCapabilities = unavailable.copy(
    bestEffortProduction = PcCapabilityStatus.Available,
    bestEffortConsumption = PcCapabilityStatus.Available
  )

private object PresentationCompilerLifecycle:
  def supports(compiler: PresentationCompiler): Boolean =
    queueState(compiler).nonEmpty

  def captureExecutor(compiler: PresentationCompiler): Either[String, ThreadPoolExecutor] =
    queueState(compiler) match
      case None        => Left("the presentation compiler job queue cannot be observed")
      case Some(state) => queueExecutor(state)

  def shutdown(compiler: PresentationCompiler): Either[String, Unit] =
    try
      compiler.shutdown()
      Right(())
    catch case NonFatal(error) => Left(Option(error.getMessage).getOrElse(error.getClass.getName))

  private def queueState(compiler: PresentationCompiler): Option[AtomicReference[?]] =
    try
      compilerAccess(compiler).flatMap: access =>
        fields(access.getClass)
          .find(field =>
            val methods = field.getType.getMethods.iterator.map(_.getName).toSet
            Set("submit", "reset", "shutdown").subsetOf(methods)
          )
          .flatMap: field =>
            field.setAccessible(true)
            Option(field.get(access)).flatMap: queue =>
              fields(queue.getClass)
                .find(field => classOf[AtomicReference[?]].isAssignableFrom(field.getType))
                .flatMap: state =>
                  state.setAccessible(true)
                  Option(state.get(queue)).collect { case reference: AtomicReference[?] => reference }
    catch case NonFatal(_) => None

  private def compilerAccess(compiler: PresentationCompiler): Option[AnyRef] =
    try
      compiler.getClass.getMethods.iterator
        .find(method =>
          method.getParameterCount == 0 &&
            Set("isLoaded", "shutdownCurrentCompiler").subsetOf(method.getReturnType.getMethods.map(_.getName).toSet)
        )
        .flatMap(method => Option(method.invoke(compiler)))
    catch case NonFatal(_) => None

  private def queueExecutor(state: AtomicReference[?]): Either[String, ThreadPoolExecutor] =
    try
      Option(state.get()) match
        case None          => Left("the presentation compiler job queue has no observable state")
        case Some(current) =>
          val methods  = current.getClass.getMethods.iterator.filter(_.getParameterCount == 0).toVector
          val executor = methods
            .find(method => classOf[ThreadPoolExecutor].isAssignableFrom(method.getReturnType))
            .flatMap(method => Option(method.invoke(current)).collect { case value: ThreadPoolExecutor => value })
          executor.toRight("the presentation compiler job queue executor cannot be observed")
    catch case NonFatal(error) => Left(Option(error.getMessage).getOrElse(error.getClass.getName))

  def awaitTermination(executor: ThreadPoolExecutor, timeoutNanos: Long): Either[String, Unit] =
    try
      if timeoutNanos > 0 && executor.awaitTermination(timeoutNanos, TimeUnit.NANOSECONDS) then Right(())
      else Left("the presentation compiler job queue did not terminate")
    catch
      case _: InterruptedException =>
        Thread.currentThread.interrupt()
        Left("interrupted while awaiting the presentation compiler job queue")

  private def fields(origin: Class[?]): Vector[java.lang.reflect.Field] =
    Iterator
      .iterate(Option(origin))(_.flatMap(value => Option(value.getSuperclass)))
      .takeWhile(_.nonEmpty)
      .flatMap(_.toVector.flatMap(_.getDeclaredFields))
      .toVector
