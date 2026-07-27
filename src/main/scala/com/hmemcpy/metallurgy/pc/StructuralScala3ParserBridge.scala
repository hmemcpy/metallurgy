package com.hmemcpy.metallurgy.pc

import java.io.File
import java.lang.reflect.{Constructor, Method}
import java.net.URLClassLoader
import java.util.IdentityHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.reflect.Selectable.reflectiveSelectable
import scala.util.control.NonFatal

private[pc] object StructuralScala3ParserBridge:

  def open(
      identity: Scala3ParserCompilerIdentity,
      artifacts: Seq[File]
  ): Either[Scala3ParserOpenError, Scala3ParserBridge] =
    if artifacts.isEmpty then Left(Scala3ParserOpenError.InvalidArtifacts("the exact compiler artifact set is empty"))
    else if artifacts.exists(file => !file.isFile || !file.canRead) then
      Left(Scala3ParserOpenError.InvalidArtifacts("every exact compiler artifact must be a readable file"))
    else
      val loader = new Scala3ParserClassLoader(artifacts.map(_.toURI.toURL).toArray)
      discover(loader) match
        case Right(runtime) => Right(new StructuralScala3ParserBridge(identity, runtime))
        case Left(message)  =>
          loader.close()
          val capabilities = unavailableCapabilities(message)
          Left(
            Scala3ParserOpenError.MissingCapabilities(
              identity,
              capabilities,
              capabilities.requiredUnavailable
            )
          )

  private def discover(loader: Scala3ParserClassLoader): Either[String, ParserRuntime] =
    try
      val contextBaseClass  = loader.loadClass("dotty.tools.dotc.core.Contexts$ContextBase")
      val contextClass      = loader.loadClass("dotty.tools.dotc.core.Contexts$Context")
      val freshContextClass = loader.loadClass("dotty.tools.dotc.core.Contexts$FreshContext")
      val reporterClass     = loader.loadClass("dotty.tools.dotc.reporting.Reporter")
      val storeReporter     = loader.loadClass("dotty.tools.dotc.reporting.StoreReporter")
      val driverClass       = loader.loadClass("dotty.tools.dotc.Driver")
      val sourceFileClass   = loader.loadClass("dotty.tools.dotc.util.SourceFile")
      val sourceModule      = module(loader, "dotty.tools.dotc.util.SourceFile$")
      val parserClass       = loader.loadClass("dotty.tools.dotc.parsing.Parsers$Parser")
      val treeClass         = loader.loadClass("dotty.tools.dotc.ast.Trees$Tree")
      val productClass      = loader.loadClass("scala.Product")
      val optionClass       = loader.loadClass("scala.Option")
      val iterableClass     = loader.loadClass("scala.collection.Iterable")
      val nameClass         = loader.loadClass("dotty.tools.dotc.core.Names$Name")
      val spansModule       = module(loader, "dotty.tools.dotc.util.Spans$Span$")

      val sourceFactory = discoverSourceFactory(sourceModule, sourceFileClass)
      val parserFactory = discoverParserFactory(loader, parserClass, sourceFileClass, contextClass)
      val _             = parserClass.getMethod("parse")
      val _             = storeReporter.getMethod("allErrors")
      val _             = storeReporter.getMethod("allWarnings")
      val runtime       = ParserRuntime(
        loader,
        contextBaseClass.getConstructor(),
        freshContextClass.getMethod("setReporter", reporterClass),
        storeReporter.getConstructor(reporterClass, java.lang.Boolean.TYPE),
        driverClass.getConstructor(),
        driverClass.getMethod("setup", classOf[Array[String]], contextClass),
        sourceFactory,
        parserFactory,
        treeClass,
        productClass,
        optionClass,
        iterableClass,
        nameClass,
        spansModule
      )
      Right(runtime)
    catch case NonFatal(error) => Left(errorMessage(error))

  private def module(loader: ClassLoader, className: String): AnyRef =
    loader.loadClass(className).getField("MODULE$").get(null)

  private def discoverSourceFactory(module: AnyRef, sourceFileClass: Class[?]): SourceFactory =
    val candidates = module.getClass.getMethods.filter: method =>
      method.getName == "virtual" &&
        sourceFileClass.isAssignableFrom(method.getReturnType) &&
        method.getParameterTypes.take(2).sameElements(Array(classOf[String], classOf[String]))

    candidates
      .find(_.getParameterTypes.sameElements(Array(classOf[String], classOf[String], java.lang.Boolean.TYPE)))
      .map(SourceFactory.WholeSource(module, _))
      .orElse(
        candidates
          .find(_.getParameterTypes.sameElements(Array(classOf[String], classOf[String])))
          .map(SourceFactory.RangeSource(module, _))
      )
      .getOrElse(throw new NoSuchMethodException("SourceFile.virtual(String, String[, boolean])"))

  private def discoverParserFactory(
      loader: ClassLoader,
      parserClass: Class[?],
      sourceFileClass: Class[?],
      contextClass: Class[?]
  ): ParserFactory =
    parserClass.getConstructors
      .find(_.getParameterTypes.sameElements(Array(sourceFileClass, contextClass)))
      .map(ParserFactory.WholeSource.apply)
      .orElse:
        parserClass.getConstructors
          .find(
            _.getParameterTypes.sameElements(
              Array(sourceFileClass, java.lang.Integer.TYPE, java.lang.Integer.TYPE, contextClass)
            )
          )
          .map: constructor =>
            val defaults = module(loader, "dotty.tools.dotc.parsing.Parsers$Parser$")
            ParserFactory.RangeSource(
              constructor,
              defaults.getClass.getMethod("$lessinit$greater$default$2"),
              defaults.getClass.getMethod("$lessinit$greater$default$3"),
              defaults
            )
      .getOrElse(throw new NoSuchMethodException("Parsers.Parser whole-source or range-source constructor"))

  private def availableCapabilities: Scala3ParserCapabilities =
    Scala3ParserCapabilities(
      publishedParser = ParserCapabilityStatus.Unavailable(
        "published Scalameta and scala3-interfaces APIs do not expose a parser"
      ),
      contextSetup = ParserCapabilityStatus.Available,
      sourceConstruction = ParserCapabilityStatus.Available,
      parserConstruction = ParserCapabilityStatus.Available,
      productTraversal = ParserCapabilityStatus.Available,
      sourcePositions = ParserCapabilityStatus.Available,
      diagnostics = ParserCapabilityStatus.Available
    )

  private def unavailableCapabilities(reason: String): Scala3ParserCapabilities =
    val unavailable = ParserCapabilityStatus.Unavailable(reason)
    Scala3ParserCapabilities(
      publishedParser = ParserCapabilityStatus.Unavailable(
        "published Scalameta and scala3-interfaces APIs do not expose a parser"
      ),
      contextSetup = unavailable,
      sourceConstruction = unavailable,
      parserConstruction = unavailable,
      productTraversal = unavailable,
      sourcePositions = unavailable,
      diagnostics = unavailable
    )

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getName)

  private final class Scala3ParserClassLoader(urls: Array[java.net.URL])
      extends URLClassLoader(urls, ClassLoader.getPlatformClassLoader)

  private final case class ParserRuntime(
      loader: Scala3ParserClassLoader,
      contextBaseConstructor: Constructor[?],
      setReporter: Method,
      reporterConstructor: Constructor[?],
      driverConstructor: Constructor[?],
      driverSetup: Method,
      sourceFactory: SourceFactory,
      parserFactory: ParserFactory,
      treeClass: Class[?],
      productClass: Class[?],
      optionClass: Class[?],
      iterableClass: Class[?],
      nameClass: Class[?],
      spansModule: AnyRef
  ):
    def close(): Unit = loader.close()

  private enum SourceFactory:
    case WholeSource(module: AnyRef, method: Method)
    case RangeSource(module: AnyRef, method: Method)

    def create(name: String, sourceText: String): AnyRef =
      this match
        case WholeSource(module, method) =>
          method.invoke(module, name, sourceText, java.lang.Boolean.TRUE)
        case RangeSource(module, method) =>
          method.invoke(module, name, sourceText)

  private enum ParserFactory:
    case WholeSource(constructor: Constructor[?])
    case RangeSource(constructor: Constructor[?], startDefault: Method, limitDefault: Method, defaults: AnyRef)

    def create(source: AnyRef, context: AnyRef): AnyRef =
      this match
        case WholeSource(constructor)                                       =>
          constructor.newInstance(source, context)
        case RangeSource(constructor, startDefault, limitDefault, defaults) =>
          constructor.newInstance(
            source,
            startDefault.invoke(defaults),
            limitDefault.invoke(defaults),
            context
          )

  val capabilities: Scala3ParserCapabilities = availableCapabilities

private final class StructuralScala3ParserBridge private (
    val identity: Scala3ParserCompilerIdentity,
    initialRuntime: StructuralScala3ParserBridge.ParserRuntime
) extends Scala3ParserBridge:
  import StructuralScala3ParserBridge.*

  private type ProductValue = {
    def productPrefix(): String
    def productArity(): Int
    def productElement(index: Int): AnyRef
    def productElementName(index: Int): String
  }

  private type ContextBaseValue = {
    def initialCtx(): AnyRef
  }

  private type ContextValue = {
    def fresh(): AnyRef
  }

  private type ParserValue = {
    def parse(): AnyRef
  }

  private type ReporterValue = {
    def allErrors(): AnyRef
    def allWarnings(): AnyRef
  }

  private type OptionValue = {
    def isEmpty(): Boolean
    def get(): AnyRef
  }

  private type IterableValue = {
    def iterator(): AnyRef
  }

  private type IteratorValue = {
    def hasNext(): Boolean
    def next(): AnyRef
  }

  private type TreeValue = {
    def span(): Long
  }

  private type SpanValue = {
    def `exists$extension`(span: Long): Boolean
    def `start$extension`(span: Long): Int
    def `end$extension`(span: Long): Int
    def `point$extension`(span: Long): Int
    def `isSourceDerived$extension`(span: Long): Boolean
  }

  private type DiagnosticValue = {
    def message(): String
    def level(): Int
    def position(): java.util.Optional[?]
  }

  private type DiagnosticPositionValue = {
    def start(): Int
    def point(): Int
    def end(): Int
  }

  private val runtime = new AtomicReference[Option[ParserRuntimeLease]](Some(new ParserRuntimeLease(initialRuntime)))

  override val capabilities: Scala3ParserCapabilities = StructuralScala3ParserBridge.capabilities

  override def loaderState: Scala3ParserLoaderState =
    if runtime.get().nonEmpty then Scala3ParserLoaderState.Open else Scala3ParserLoaderState.Closed

  override def parse(request: Scala3ParserRequest): Either[Scala3ParserError, ParserSyntaxSnapshot] =
    runtime.get() match
      case None        => Left(Scala3ParserError.Closed)
      case Some(lease) =>
        lease
          .use: active =>
            try
              request.cancellation.checkCanceled()
              withCompilerClassloader(active):
                configuredContext(active, request) match
                  case Left(error)    => Left(error)
                  case Right(context) =>
                    parseSource(active, request, context)
            catch case NonFatal(error) => Left(Scala3ParserError.ParseFailed(errorMessage(error)))
          .getOrElse(Left(Scala3ParserError.Closed))

  override def close(): Unit =
    runtime.getAndSet(None).foreach(_.retire())

  private def configuredContext(
      active: ParserRuntime,
      request: Scala3ParserRequest
  ): Either[Scala3ParserError, ParserContext] =
    val base            = active.contextBaseConstructor.newInstance().asInstanceOf[ContextBaseValue]
    val initial         = base.initialCtx().asInstanceOf[ContextValue]
    val context         = initial.fresh()
    val reporter        = active.reporterConstructor.newInstance(null, java.lang.Boolean.FALSE)
    val reporting       = active.setReporter.invoke(context, reporter)
    val driver          = active.driverConstructor.newInstance()
    val setupArguments  = (request.compilerOptions :+ "ParserInput.scala").toArray
    val configured      = active.driverSetup.invoke(driver, setupArguments, reporting)
    val configuredValue = configured.asInstanceOf[OptionValue]
    if configuredValue.isEmpty() then
      Left(Scala3ParserError.SetupRejected("the exact compiler rejected parser options"))
    else
      val tuple   = configuredValue.get().asInstanceOf[ProductValue]
      val context = tuple.productElement(1)
      Right(ParserContext(context, reporter))

  private def parseSource(
      active: ParserRuntime,
      request: Scala3ParserRequest,
      context: ParserContext
  ): Either[Scala3ParserError, ParserSyntaxSnapshot] =
    request.cancellation.checkCanceled()
    val source = active.sourceFactory.create(request.sourceUri.value, request.sourceText)
    val parser = active.parserFactory.create(source, context.value).asInstanceOf[ParserValue]
    val root   = parser.parse()
    request.cancellation.checkCanceled()
    val nodes  = collectNodes(active, root, request.cancellation)
    nodes.map: collected =>
      ParserSyntaxSnapshot(
        request.sourceUri,
        request.sourceText,
        ParserSyntaxSnapshot.digest(request.sourceText),
        collected.rootNodeId,
        collected.nodes,
        diagnostics(context.reporter),
        capabilities,
        identity
      )

  private def collectNodes(
      active: ParserRuntime,
      root: AnyRef,
      cancellation: Scala3ParserCancellation
  ): Either[Scala3ParserError, CollectedNodes] =
    if !active.treeClass.isInstance(root) then
      Left(Scala3ParserError.ParseFailed("the exact parser did not return a compiler tree"))
    else
      val ids       = new IdentityHashMap[AnyRef, java.lang.Long]()
      val products  = new IdentityHashMap[AnyRef, java.lang.Boolean]()
      var nextId    = 0L
      var collected = Vector.empty[ParserSyntaxNode]

      def visitTree(tree: AnyRef): Long =
        Option(ids.get(tree))
          .map(_.longValue())
          .getOrElse:
            cancellation.checkCanceled()
            val id       = nextId
            nextId += 1
            ids.put(tree, id)
            val product  = tree.asInstanceOf[ProductValue]
            val fields   = productFields(active, product, cancellation, visitTree, products)
            val position = treePosition(active, tree.asInstanceOf[TreeValue])
            collected = collected :+ ParserSyntaxNode(id, product.productPrefix(), fields, position)
            id

      val rootId = visitTree(root)
      Right(CollectedNodes(rootId, collected.sortBy(_.id)))

  private def productFields(
      active: ParserRuntime,
      product: ProductValue,
      cancellation: Scala3ParserCancellation,
      visitTree: AnyRef => Long,
      products: IdentityHashMap[AnyRef, java.lang.Boolean]
  ): Vector[ParserSyntaxField] =
    Vector.tabulate(product.productArity()): index =>
      cancellation.checkCanceled()
      ParserSyntaxField(
        product.productElementName(index),
        fieldValue(active, product.productElement(index), cancellation, visitTree, products)
      )

  private def fieldValue(
      active: ParserRuntime,
      value: AnyRef,
      cancellation: Scala3ParserCancellation,
      visitTree: AnyRef => Long,
      products: IdentityHashMap[AnyRef, java.lang.Boolean]
  ): ParserFieldValue =
    if value == null then ParserFieldValue.Optional(None)
    else if active.treeClass.isInstance(value) then ParserFieldValue.Node(visitTree(value))
    else
      value match
        case text: String                                => ParserFieldValue.Scalar(ParserScalar.Text(text))
        case number: java.lang.Integer                   => ParserFieldValue.Scalar(ParserScalar.Integer(number.intValue()))
        case number: java.lang.Long                      => ParserFieldValue.Scalar(ParserScalar.LongInteger(number.longValue()))
        case number: java.lang.Double                    => ParserFieldValue.Scalar(ParserScalar.Decimal(number.doubleValue()))
        case logical: java.lang.Boolean                  => ParserFieldValue.Scalar(ParserScalar.Logical(logical.booleanValue()))
        case character: java.lang.Character              =>
          ParserFieldValue.Scalar(ParserScalar.Character(character.charValue()))
        case _ if active.nameClass.isInstance(value)     =>
          ParserFieldValue.Name(value.toString)
        case _ if active.optionClass.isInstance(value)   =>
          val option = value.asInstanceOf[OptionValue]
          ParserFieldValue.Optional(
            Option.unless(option.isEmpty())(
              fieldValue(
                active,
                option.get(),
                cancellation,
                visitTree,
                products
              )
            )
          )
        case _ if active.iterableClass.isInstance(value) =>
          ParserFieldValue.Repeated(
            iteratorValues(value.asInstanceOf[IterableValue]).map: element =>
              fieldValue(active, element, cancellation, visitTree, products)
          )
        case _ if active.productClass.isInstance(value)  =>
          if products.put(value, java.lang.Boolean.TRUE) != null then
            ParserFieldValue.Unsupported(value.getClass.getName)
          else
            val product = value.asInstanceOf[ProductValue]
            val result  = ParserFieldValue.Product(
              product.productPrefix(),
              productFields(active, product, cancellation, visitTree, products)
            )
            products.remove(value)
            result
        case _                                           =>
          ParserFieldValue.Unsupported(value.getClass.getName)

  private def iteratorValues(iterable: IterableValue): Vector[AnyRef] =
    val iterator = iterable.iterator().asInstanceOf[IteratorValue]
    val result   = Vector.newBuilder[AnyRef]
    while iterator.hasNext() do result += iterator.next()
    result.result()

  private def treePosition(active: ParserRuntime, tree: TreeValue): ParserNodePosition =
    val span = tree.span()
    val ops  = active.spansModule.asInstanceOf[SpanValue]
    if !ops.`exists$extension`(span) then ParserNodePosition.Absent
    else
      val provenance =
        if ops.`isSourceDerived$extension`(span) then ParserPositionProvenance.SourceDerived
        else ParserPositionProvenance.Synthetic
      ParserNodePosition.Positioned(
        PcSourceRange(ops.`start$extension`(span), ops.`end$extension`(span)),
        ops.`point$extension`(span),
        provenance
      )

  private def diagnostics(reporter: AnyRef): Vector[ParserDiagnostic] =
    val value    = reporter.asInstanceOf[ReporterValue]
    val errors   = iteratorValues(value.allErrors().asInstanceOf[IterableValue])
    val warnings = iteratorValues(value.allWarnings().asInstanceOf[IterableValue])
    (errors ++ warnings).map: value =>
      val diagnostic = value.asInstanceOf[DiagnosticValue]
      ParserDiagnostic(
        diagnosticSeverity(diagnostic.level()),
        diagnostic.message(),
        diagnosticPosition(diagnostic.position())
      )

  private def diagnosticSeverity(level: Int): ParserDiagnosticSeverity =
    level match
      case 2 => ParserDiagnosticSeverity.Error
      case 1 => ParserDiagnosticSeverity.Warning
      case _ => ParserDiagnosticSeverity.Information

  private def diagnosticPosition(
      position: java.util.Optional[?]
  ): Option[ParserNodePosition.Positioned] =
    if position.isEmpty then None
    else
      val value = position.get().asInstanceOf[DiagnosticPositionValue]
      Some(
        ParserNodePosition.Positioned(
          PcSourceRange(value.start(), value.end()),
          value.point(),
          ParserPositionProvenance.SourceDerived
        )
      )

  private def withCompilerClassloader[A](active: ParserRuntime)(body: => A): A =
    val thread   = Thread.currentThread
    val previous = thread.getContextClassLoader
    thread.setContextClassLoader(active.loader)
    try body
    finally thread.setContextClassLoader(previous)

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getName)

  private final class ParserRuntimeLease(active: ParserRuntime):
    private val readers  = new AtomicInteger(0)
    private val retired  = new AtomicBoolean(false)
    private val released = new AtomicBoolean(false)

    def use[A](operation: ParserRuntime => A): Option[A] =
      if !acquire() then None
      else
        try Some(operation(active))
        finally release()

    def retire(): Unit =
      if retired.compareAndSet(false, true) && readers.get() == 0 then releaseRuntime()

    private def acquire(): Boolean =
      readers.incrementAndGet()
      if retired.get() then
        release()
        false
      else true

    private def release(): Unit =
      if readers.decrementAndGet() == 0 && retired.get() then releaseRuntime()

    private def releaseRuntime(): Unit =
      if released.compareAndSet(false, true) then active.close()

  private final case class ParserContext(value: AnyRef, reporter: AnyRef)
  private final case class CollectedNodes(rootNodeId: Long, nodes: Vector[ParserSyntaxNode])
