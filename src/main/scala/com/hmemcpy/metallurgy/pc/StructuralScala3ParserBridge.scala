package com.hmemcpy.metallurgy.pc

import java.io.File
import java.lang.reflect.{Constructor, Method}
import java.net.URLClassLoader
import java.util.{HashMap, IdentityHashMap}
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
      val scannerClass      = loader.loadClass("dotty.tools.dotc.parsing.Scanners$Scanner")
      val treeClass         = loader.loadClass("dotty.tools.dotc.ast.Trees$Tree")
      val defTreeClass      = loader.loadClass("dotty.tools.dotc.ast.Trees$DefTree")
      val positionedClass   = loader.loadClass("dotty.tools.dotc.ast.Positioned")
      val productClass      = loader.loadClass("scala.Product")
      val optionClass       = loader.loadClass("scala.Option")
      val iterableClass     = loader.loadClass("scala.collection.Iterable")
      val nameClass         = loader.loadClass("dotty.tools.dotc.core.Names$Name")
      val uniqueNameKind    = loader.loadClass("dotty.tools.dotc.core.NameKinds$UniqueNameKind")
      val spansModule       = module(loader, "dotty.tools.dotc.util.Spans$Span$")

      val sourceFactory    = discoverSourceFactory(sourceModule, sourceFileClass)
      val parserFactory    = discoverParserFactory(loader, parserClass, sourceFileClass, contextClass)
      val reporterFactory  = discoverReporterFactory(storeReporter, reporterClass)
      val diagnosticReader = discoverDiagnosticReader(storeReporter, contextClass)
      val _                = parserClass.getMethod("parse")
      val parserInput      = parserClass.getMethods.find(method => method.getName == "in" && method.getParameterCount == 0)
      val commentReader    = parserInput.flatMap: input =>
        scannerClass.getMethods
          .find(method => method.getName == "comments" && method.getParameterCount == 0)
          .map(CommentReader.Modern(input, _))
          .orElse(
            scannerClass.getMethods
              .find(method => method.getName == "commentSpans" && method.getParameterCount == 0)
              .map(CommentReader.Legacy(input, _))
          )
      if commentReader.isEmpty then throw new NoSuchMethodException("parser input comments() or commentSpans()")
      val positionedSpan   = positionedClass.getMethod("span")
      val defTreeRawMods   = defTreeClass.getMethod("rawMods")
      val runtime          = ParserRuntime(
        loader,
        contextBaseClass.getConstructor(),
        freshContextClass.getMethod("setReporter", reporterClass),
        reporterFactory,
        driverClass.getConstructor(),
        driverClass.getMethod("setup", classOf[Array[String]], contextClass),
        sourceFactory,
        parserFactory,
        diagnosticReader,
        treeClass,
        defTreeClass,
        defTreeRawMods,
        positionedClass,
        positionedSpan,
        commentReader,
        productClass,
        optionClass,
        iterableClass,
        nameClass,
        uniqueNameKind,
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

  private def discoverDiagnosticReader(
      reporterClass: Class[?],
      contextClass: Class[?]
  ): DiagnosticReader =
    val methods     = reporterClass.getMethods
    val partitioned =
      methods.exists(method => method.getName == "allErrors" && method.getParameterCount == 0) &&
        methods.exists(method => method.getName == "allWarnings" && method.getParameterCount == 0)
    if partitioned then DiagnosticReader.Partitioned
    else
      methods
        .find(method =>
          method.getName == "pendingMessages" &&
            method.getParameterTypes.sameElements(Array(contextClass))
        )
        .map(DiagnosticReader.Buffered.apply)
        .getOrElse(throw new NoSuchMethodException("StoreReporter diagnostic access"))

  private def discoverReporterFactory(
      storeReporterClass: Class[?],
      reporterClass: Class[?]
  ): ReporterFactory =
    storeReporterClass.getConstructors
      .find(_.getParameterTypes.sameElements(Array(reporterClass, java.lang.Boolean.TYPE)))
      .map(ReporterFactory.WithTyperState.apply)
      .orElse(
        storeReporterClass.getConstructors
          .find(_.getParameterTypes.sameElements(Array(reporterClass)))
          .map(ReporterFactory.SingleArgument.apply)
      )
      .getOrElse(throw new NoSuchMethodException("StoreReporter supported constructor"))

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
      diagnostics = ParserCapabilityStatus.Available,
      positionedSyntax = ParserCapabilityStatus.Available,
      comments = ParserCapabilityStatus.Available
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
      diagnostics = unavailable,
      positionedSyntax = unavailable,
      comments = unavailable
    )

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getName)

  private final class Scala3ParserClassLoader(urls: Array[java.net.URL])
      extends URLClassLoader(urls, ClassLoader.getPlatformClassLoader)

  private final case class ParserRuntime(
      loader: Scala3ParserClassLoader,
      contextBaseConstructor: Constructor[?],
      setReporter: Method,
      reporterFactory: ReporterFactory,
      driverConstructor: Constructor[?],
      driverSetup: Method,
      sourceFactory: SourceFactory,
      parserFactory: ParserFactory,
      diagnosticReader: DiagnosticReader,
      treeClass: Class[?],
      defTreeClass: Class[?],
      defTreeRawMods: Method,
      positionedClass: Class[?],
      positionedSpan: Method,
      commentReader: Option[CommentReader],
      productClass: Class[?],
      optionClass: Class[?],
      iterableClass: Class[?],
      nameClass: Class[?],
      uniqueNameKindClass: Class[?],
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

  private enum DiagnosticReader:
    case Partitioned
    case Buffered(method: Method)

  private enum CommentReader(val parserInput: Method, val comments: Method):
    case Modern(input: Method, method: Method) extends CommentReader(input, method)
    case Legacy(input: Method, method: Method) extends CommentReader(input, method)

  private enum ReporterFactory:
    case SingleArgument(constructor: Constructor[?])
    case WithTyperState(constructor: Constructor[?])

    def create(): AnyRef =
      this match
        case SingleArgument(constructor) => constructor.newInstance(null)
        case WithTyperState(constructor) => constructor.newInstance(null, java.lang.Boolean.FALSE)

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

  private type NameValue = {
    def info(): AnyRef
    def underlying(): AnyRef
  }

  private type NameInfoValue = {
    def kind(): AnyRef
  }

  private type UniqueNameKindValue = {
    def separator(): String
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
    val reporter        = active.reporterFactory.create()
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
        request.sourceText.length,
        request.compilerOptions,
        collected.rootNodeId,
        collected.nodes,
        collected.positioned,
        comments(active, parser, request.sourceText, request.cancellation),
        diagnostics(active, context, request.cancellation),
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
      val ids              = new IdentityHashMap[AnyRef, java.lang.Long]()
      val products         = new IdentityHashMap[AnyRef, java.lang.Boolean]()
      val generated        = new HashMap[String, java.lang.Integer]()
      var nextId           = 0L
      var collected        = Vector.empty[ParserSyntaxNode]
      var positioned       = Vector.empty[ParserPositionedSyntax]
      val positionedIds    = new IdentityHashMap[AnyRef, java.lang.Long]()
      var nextPositionedId = 0L

      def visitPositioned(
          value: AnyRef,
          ownerNodeId: Long,
          path: Vector[ParserFieldPathSegment]
      ): Long =
        val existing = Option(positionedIds.get(value))
        val id       = existing
          .map(_.longValue())
          .getOrElse:
            val assigned = nextPositionedId
            nextPositionedId += 1
            positionedIds.put(value, assigned)
            val product  = value.asInstanceOf[ProductValue]
            positioned :+= ParserPositionedSyntax(
              assigned,
              product.productPrefix(),
              Vector.empty,
              positionedPosition(active, value),
              Vector.empty
            )
            assigned

        val occurrence = ParserPositionedOccurrence(ownerNodeId, path)
        val index      = positioned.indexWhere(_.id == id)
        val current    = positioned(index)
        if !current.occurrences.contains(occurrence) then
          positioned = positioned.updated(index, current.copy(occurrences = current.occurrences :+ occurrence))
        if existing.isEmpty then
          val product   = value.asInstanceOf[ProductValue]
          val fields    = productFields(
            active,
            product,
            ownerNodeId,
            path,
            cancellation,
            visitTree,
            visitPositioned,
            products,
            generated
          )
          val refreshed = positioned(positioned.indexWhere(_.id == id))
          positioned = positioned.updated(
            positioned.indexWhere(_.id == id),
            refreshed.copy(fields = fields)
          )
        id

      def visitTree(tree: AnyRef, occurrence: Option[ParserNodeOccurrence]): Long =
        val existing = Option(ids.get(tree))
        val id       = existing
          .map(_.longValue())
          .getOrElse:
            cancellation.checkCanceled()
            val id      = nextId
            nextId += 1
            ids.put(tree, id)
            val product = tree.asInstanceOf[ProductValue]
            collected :+= ParserSyntaxNode(
              id,
              product.productPrefix(),
              Vector.empty,
              treePosition(active, tree.asInstanceOf[TreeValue]),
              Vector.empty
            )
            val fields  = productFields(
              active,
              product,
              id,
              Vector.empty,
              cancellation,
              visitTree,
              visitPositioned,
              products,
              generated
            ) ++ definitionModifiers(
              active,
              tree,
              id,
              cancellation,
              visitTree,
              visitPositioned,
              products,
              generated
            )
            val index   = collected.indexWhere(_.id == id)
            collected = collected.updated(index, collected(index).copy(fields = fields))
            id

        occurrence.foreach: value =>
          val index   = collected.indexWhere(_.id == id)
          val current = collected(index)
          if !current.occurrences.contains(value) then
            collected = collected.updated(index, current.copy(occurrences = current.occurrences :+ value))
        id

      val rootId = visitTree(root, None)
      Right(CollectedNodes(rootId, collected.sortBy(_.id), positioned.sortBy(_.id)))

  private def definitionModifiers(
      active: ParserRuntime,
      tree: AnyRef,
      ownerNodeId: Long,
      cancellation: Scala3ParserCancellation,
      visitTree: (AnyRef, Option[ParserNodeOccurrence]) => Long,
      visitPositioned: (AnyRef, Long, Vector[ParserFieldPathSegment]) => Long,
      products: IdentityHashMap[AnyRef, java.lang.Boolean],
      generated: HashMap[String, java.lang.Integer]
  ): Vector[ParserSyntaxField] =
    if !active.defTreeClass.isInstance(tree) then Vector.empty
    else
      Vector(
        ParserSyntaxField(
          "mods",
          fieldValue(
            active,
            active.defTreeRawMods.invoke(tree),
            ownerNodeId,
            Vector(ParserFieldPathSegment.NamedField("mods")),
            cancellation,
            visitTree,
            visitPositioned,
            products,
            generated
          )
        )
      )

  private def productFields(
      active: ParserRuntime,
      product: ProductValue,
      ownerNodeId: Long,
      path: Vector[ParserFieldPathSegment],
      cancellation: Scala3ParserCancellation,
      visitTree: (AnyRef, Option[ParserNodeOccurrence]) => Long,
      visitPositioned: (AnyRef, Long, Vector[ParserFieldPathSegment]) => Long,
      products: IdentityHashMap[AnyRef, java.lang.Boolean],
      generated: HashMap[String, java.lang.Integer]
  ): Vector[ParserSyntaxField] =
    Vector.tabulate(product.productArity()): index =>
      cancellation.checkCanceled()
      ParserSyntaxField(
        product.productElementName(index),
        fieldValue(
          active,
          product.productElement(index),
          ownerNodeId,
          path :+ ParserFieldPathSegment.NamedField(product.productElementName(index)),
          cancellation,
          visitTree,
          visitPositioned,
          products,
          generated
        )
      )

  private def fieldValue(
      active: ParserRuntime,
      value: AnyRef,
      ownerNodeId: Long,
      path: Vector[ParserFieldPathSegment],
      cancellation: Scala3ParserCancellation,
      visitTree: (AnyRef, Option[ParserNodeOccurrence]) => Long,
      visitPositioned: (AnyRef, Long, Vector[ParserFieldPathSegment]) => Long,
      products: IdentityHashMap[AnyRef, java.lang.Boolean],
      generated: HashMap[String, java.lang.Integer]
  ): ParserFieldValue =
    if value == null then ParserFieldValue.Optional(None)
    else if active.treeClass.isInstance(value) then
      ParserFieldValue.Node(visitTree(value, Some(ParserNodeOccurrence(ownerNodeId, path))))
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
          parserName(active, value, generated)
        case _ if active.optionClass.isInstance(value)   =>
          val option = value.asInstanceOf[OptionValue]
          ParserFieldValue.Optional(
            Option.unless(option.isEmpty())(
              fieldValue(
                active,
                option.get(),
                ownerNodeId,
                path :+ ParserFieldPathSegment.OptionalNesting,
                cancellation,
                visitTree,
                visitPositioned,
                products,
                generated
              )
            )
          )
        case _ if active.iterableClass.isInstance(value) =>
          ParserFieldValue.Repeated(
            iteratorValues(value.asInstanceOf[IterableValue], cancellation).zipWithIndex.map: (element, index) =>
              fieldValue(
                active,
                element,
                ownerNodeId,
                path :+ ParserFieldPathSegment.RepeatedIndex(index),
                cancellation,
                visitTree,
                visitPositioned,
                products,
                generated
              )
          )
        case _ if active.productClass.isInstance(value)  =>
          if active.positionedClass.isInstance(value) then
            ParserFieldValue.Positioned(visitPositioned(value, ownerNodeId, path))
          else if products.put(value, java.lang.Boolean.TRUE) != null then
            ParserFieldValue.Unsupported(value.getClass.getName)
          else
            val product     = value.asInstanceOf[ProductValue]
            lazy val fields =
              productFields(
                active,
                product,
                ownerNodeId,
                path :+ ParserFieldPathSegment.NestedProductBoundary(product.productPrefix()),
                cancellation,
                visitTree,
                visitPositioned,
                products,
                generated
              )
            val result      = ParserFieldValue.Product(
              product.productPrefix(),
              fields
            )
            products.remove(value)
            result
        case _                                           =>
          ParserFieldValue.Unsupported(value.getClass.getName)

  private def parserName(
      active: ParserRuntime,
      value: AnyRef,
      generated: HashMap[String, java.lang.Integer]
  ): ParserFieldValue =
    val methods = value.getClass.getMethods.iterator.map(_.getName).toSet
    if !methods("info") || !methods("underlying") then ParserFieldValue.Name(value.toString)
    else
      val name = value.asInstanceOf[NameValue]
      val kind = name.info().asInstanceOf[NameInfoValue].kind()
      if !active.uniqueNameKindClass.isInstance(kind) then ParserFieldValue.Name(value.toString)
      else
        val runtimeName                = value.toString
        val observed                   = Option(generated.get(runtimeName))
        val ordinal: java.lang.Integer = observed.getOrElse:
          val assigned = java.lang.Integer.valueOf(generated.size())
          generated.put(runtimeName, assigned)
          assigned
        ParserFieldValue.GeneratedName(
          name.underlying().toString,
          kind.asInstanceOf[UniqueNameKindValue].separator(),
          ordinal.intValue()
        )

  private def iteratorValues(
      iterable: IterableValue,
      cancellation: Scala3ParserCancellation
  ): Vector[AnyRef] =
    val runtimeType = iterable.getClass.getName
    if !runtimeType.startsWith("scala.collection.immutable.") then
      throw new IllegalStateException(s"unsupported compiler iterable: $runtimeType")
    val iterator    = iterable.iterator().asInstanceOf[IteratorValue]
    val result      = Vector.newBuilder[AnyRef]
    var count       = 0
    while iterator.hasNext() do
      cancellation.checkCanceled()
      if count == 1000000 then throw new IllegalStateException("compiler iterable exceeds traversal limit")
      result += iterator.next()
      count += 1
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

  private def positionedPosition(active: ParserRuntime, value: AnyRef): ParserNodePosition =
    val span = active.positionedSpan.invoke(value).asInstanceOf[java.lang.Long].longValue()
    spanPosition(active, span)

  private def spanPosition(active: ParserRuntime, span: Long): ParserNodePosition =
    val ops = active.spansModule.asInstanceOf[SpanValue]
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

  private def comments(
      active: ParserRuntime,
      parser: AnyRef,
      source: String,
      cancellation: Scala3ParserCancellation
  ): Vector[ParserComment] =
    active.commentReader.toVector.flatMap: reader =>
      val input  = reader.parserInput.invoke(parser)
      val values = iteratorValues(reader.comments.invoke(input).asInstanceOf[IterableValue], cancellation)
      values.map: value =>
        cancellation.checkCanceled()
        val span = commentSpan(active, value)
        spanPosition(active, span) match
          case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived)
              if range.startOffset >= 0 && range.endOffset <= source.length && range.startOffset <= range.endOffset =>
            if range.startOffset >= range.endOffset then throw new IllegalStateException("comment has an empty span")
            val slice = source.substring(range.startOffset, range.endOffset)
            val raw   = reader match
              case CommentReader.Modern(_, _) => commentRaw(active, value)
              case CommentReader.Legacy(_, _) => slice
            if raw != slice then throw new IllegalStateException("compiler comment raw does not match its source span")
            val kind  = classifyComment(raw)
            ParserComment(range, raw, kind)
          case position => throw new IllegalStateException(s"comment has no valid source-derived span: $position")

  private def commentSpan(active: ParserRuntime, value: AnyRef): Long =
    if value.isInstanceOf[java.lang.Long] then value.asInstanceOf[java.lang.Long].longValue()
    else
      value.getClass.getMethods
        .find(method => (method.getName == "span" || method.getName == "coords") && method.getParameterCount == 0)
        .map(_.invoke(value).asInstanceOf[java.lang.Long].longValue())
        .orElse:
          Option
            .when(active.productClass.isInstance(value)):
              val product = value.asInstanceOf[ProductValue]
              Vector
                .tabulate(product.productArity())(product.productElement)
                .collectFirst:
                  case span: java.lang.Long => span.longValue()
            .flatten
        .getOrElse(throw new IllegalStateException("comment has no span accessor"))

  private def commentRaw(active: ParserRuntime, value: AnyRef): String =
    value.getClass.getMethods
      .find(method => method.getName == "raw" && method.getParameterCount == 0)
      .map(_.invoke(value).asInstanceOf[String])
      .orElse:
        Option
          .when(active.productClass.isInstance(value)):
            val product = value.asInstanceOf[ProductValue]
            Vector
              .tabulate(product.productArity())(index =>
                product.productElementName(index) -> product.productElement(index)
              )
              .collectFirst { case ("raw", raw: String) => raw }
          .flatten
      .getOrElse(throw new IllegalStateException("comment has no raw accessor"))

  private def classifyComment(raw: String): ParserCommentKind =
    if raw.startsWith("//") then ParserCommentKind.Line
    else if raw.startsWith("/**") then ParserCommentKind.Doc
    else if raw.startsWith("/*") then ParserCommentKind.Block
    else throw new IllegalStateException("comment span does not select comment source text")

  private def diagnostics(
      active: ParserRuntime,
      context: ParserContext,
      cancellation: Scala3ParserCancellation
  ): Vector[ParserDiagnostic] =
    val values =
      active.diagnosticReader match
        case DiagnosticReader.Partitioned      =>
          val reporter = context.reporter.asInstanceOf[ReporterValue]
          iteratorValues(reporter.allErrors().asInstanceOf[IterableValue], cancellation) ++
            iteratorValues(reporter.allWarnings().asInstanceOf[IterableValue], cancellation)
        case DiagnosticReader.Buffered(method) =>
          iteratorValues(method.invoke(context.reporter, context.value).asInstanceOf[IterableValue], cancellation)
    values.map: value =>
      cancellation.checkCanceled()
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
  ): Option[ParserDiagnosticPosition] =
    if position.isEmpty then None
    else
      val value = position.get().asInstanceOf[DiagnosticPositionValue]
      Some(
        ParserDiagnosticPosition(
          PcSourceRange(value.start(), value.end()),
          value.point()
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
  private final case class CollectedNodes(
      rootNodeId: Long,
      nodes: Vector[ParserSyntaxNode],
      positioned: Vector[ParserPositionedSyntax]
  )
