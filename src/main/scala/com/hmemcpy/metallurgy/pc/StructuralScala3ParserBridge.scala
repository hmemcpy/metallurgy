package com.hmemcpy.metallurgy.pc

import java.io.File
import java.lang.reflect.{Constructor, Method, ParameterizedType, Type, TypeVariable, WildcardType}
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.util.{HashMap, IdentityHashMap}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import java.util.jar.JarFile
import com.intellij.openapi.diagnostic.ControlFlowException
import scala.jdk.CollectionConverters.*
import dotty.tools.tasty.TastyFormat.{
  ANNOTATEDtype,
  APPLIEDtpt,
  APPLIEDtype,
  CASEaccessor,
  LAMBDAtpt,
  PARAM,
  TEMPLATE,
  TYPEBOUNDS,
  TYPEDEF
}
import org.jetbrains.plugins.scala.tasty.reader.{Node as TastyNode, TreeReader as TastyTreeReader}
import scala.reflect.Selectable.reflectiveSelectable
import scala.util.Using
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
      discover(loader, artifacts) match
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

  private def discover(loader: Scala3ParserClassLoader, artifacts: Seq[File]): Either[String, ParserRuntime] =
    try
      val contextBaseClass   = loader.loadClass("dotty.tools.dotc.core.Contexts$ContextBase")
      val contextClass       = loader.loadClass("dotty.tools.dotc.core.Contexts$Context")
      val freshContextClass  = loader.loadClass("dotty.tools.dotc.core.Contexts$FreshContext")
      val reporterClass      = loader.loadClass("dotty.tools.dotc.reporting.Reporter")
      val storeReporter      = loader.loadClass("dotty.tools.dotc.reporting.StoreReporter")
      val driverClass        = loader.loadClass("dotty.tools.dotc.Driver")
      val compilerClass      = loader.loadClass("dotty.tools.dotc.Compiler")
      val runClass           = loader.loadClass("dotty.tools.dotc.Run")
      val sourceFileClass    = loader.loadClass("dotty.tools.dotc.util.SourceFile")
      val sourceModule       = module(loader, "dotty.tools.dotc.util.SourceFile$")
      val parserClass        = loader.loadClass("dotty.tools.dotc.parsing.Parsers$Parser")
      val scannerClass       = loader.loadClass("dotty.tools.dotc.parsing.Scanners$Scanner")
      val treeClass          = loader.loadClass("dotty.tools.dotc.ast.Trees$Tree")
      val defTreeClass       = loader.loadClass("dotty.tools.dotc.ast.Trees$DefTree")
      val lazyFieldsReader   =
        try
          val owner = loader.loadClass("dotty.tools.dotc.ast.Trees$WithLazyFields")
          Some(LazyFieldsReader(owner, owner.getMethod("forceFields", contextClass)))
        catch case NonFatal(_) => None
      val positionedClass    = loader.loadClass("dotty.tools.dotc.ast.Positioned")
      val productClass       = loader.loadClass("scala.Product")
      val optionClass        = loader.loadClass("scala.Option")
      val iterableClass      = loader.loadClass("scala.collection.Iterable")
      val nameClass          = loader.loadClass("dotty.tools.dotc.core.Names$Name")
      val uniqueNameKind     = loader.loadClass("dotty.tools.dotc.core.NameKinds$UniqueNameKind")
      val spansModule        = module(loader, "dotty.tools.dotc.util.Spans$Span$")
      val constantClass      = loader.loadClass("dotty.tools.dotc.core.Constants$Constant")
      val constantApply      = constantClass.getMethod("apply", classOf[Object])
      val constantTag        = constantClass.getMethod("tag")
      val nullConstant       = constantApply.invoke(null, Array[AnyRef](null)*)
      val nullConstantReader = NullConstantReader(
        constantClass,
        constantTag,
        constantTag.invoke(nullConstant).asInstanceOf[java.lang.Integer].intValue()
      )
      val declaredProducts   =
        readDeclaredProducts(artifacts).fold(message => throw new IllegalStateException(message), identity)

      val sourceFactory                = discoverSourceFactory(sourceModule, sourceFileClass)
      val parserFactory                = discoverParserFactory(loader, parserClass, sourceFileClass, contextClass)
      val scannerReader                = discoverScannerReader(loader, scannerClass, sourceFileClass, contextClass)
      val separatorRecorder            =
        discoverSeparatorRecorder(loader, parserClass, scannerClass, sourceFileClass, contextClass)
      val reporterFactory              = discoverReporterFactory(storeReporter, reporterClass)
      val diagnosticReader             = discoverDiagnosticReader(storeReporter, contextClass)
      val diagnosticPositionProvenance = discoverDiagnosticPositionProvenance(loader)
      val _                            = parserClass.getMethod("parse")
      val parserInput                  = parserClass.getMethods.find(method => method.getName == "in" && method.getParameterCount == 0)
      val commentReader                = parserInput.flatMap: input =>
        scannerClass.getMethods
          .find(method => method.getName == "comments" && method.getParameterCount == 0)
          .map(CommentReader.Modern(input, _))
          .orElse(
            scannerClass.getMethods
              .find(method => method.getName == "commentSpans" && method.getParameterCount == 0)
              .map(CommentReader.Legacy(input, _))
          )
      if commentReader.isEmpty then throw new NoSuchMethodException("parser input comments() or commentSpans()")
      val positionedSpan               = positionedClass.getMethod("span")
      val endMarkerReader              =
        try
          val owner = loader.loadClass("dotty.tools.dotc.ast.Trees$WithEndMarker")
          Right(EndMarkerReader(owner, owner.getMethod("hasEndMarker"), owner.getMethod("endSpan", contextClass)))
        catch case NonFatal(error) => Left(errorMessage(error))
      val defTreeRawMods               = defTreeClass.getMethod("rawMods")
      val attachments                  =
        try
          val keyClass = loader.loadClass("dotty.tools.dotc.util.Property$Key")
          val keyNames = new IdentityHashMap[AnyRef, String]()
          Vector("dotty.tools.dotc.ast.Trees$", "dotty.tools.dotc.ast.untpd$").foreach: moduleName =>
            val moduleClass = loader.loadClass(moduleName)
            val module      = moduleClass.getField("MODULE$").get(null)
            moduleClass.getMethods.toVector
              .filter(method =>
                Modifier.isPublic(method.getModifiers) &&
                  method.getParameterCount == 0 &&
                  keyClass.isAssignableFrom(method.getReturnType)
              )
              .sortBy(_.getName)
              .foreach(method => keyNames.put(method.invoke(module), method.getName))
          Some(AttachmentReader(treeClass.getMethod("allAttachments"), keyNames))
        catch case NonFatal(_) => None
      val runtime                      = ParserRuntime(
        loader,
        artifacts.map(_.getAbsolutePath).mkString(File.pathSeparator),
        contextBaseClass.getConstructor(),
        freshContextClass.getMethod("setReporter", reporterClass),
        reporterFactory,
        driverClass.getConstructor(),
        driverClass.getMethod("setup", classOf[Array[String]], contextClass),
        RunContextFactory(
          compilerClass.getConstructor(),
          compilerClass.getMethod("newRun", contextClass),
          runClass.getMethod("runContext")
        ),
        sourceFactory,
        parserFactory,
        scannerReader,
        separatorRecorder,
        diagnosticReader,
        diagnosticPositionProvenance,
        treeClass,
        defTreeClass,
        lazyFieldsReader,
        defTreeRawMods,
        attachments,
        endMarkerReader,
        positionedClass,
        positionedSpan,
        commentReader,
        productClass,
        optionClass,
        iterableClass,
        nameClass,
        uniqueNameKind,
        spansModule,
        nullConstantReader,
        declaredProducts
      )
      Right(runtime)
    catch case NonFatal(error) => Left(errorMessage(error))

  private def module(loader: ClassLoader, className: String): AnyRef =
    loader.loadClass(className).getField("MODULE$").get(null)

  private def readDeclaredProducts(artifacts: Seq[File]): Either[String, DeclaredProducts] =
    val entries    = Vector(
      "dotty/tools/dotc/ast/Trees.tasty"  -> "Trees$",
      "dotty/tools/dotc/ast/untpd.tasty"  -> "untpd$",
      "dotty/tools/dotc/core/Flags.tasty" -> "Flags$"
    )
    val candidates = artifacts.flatMap: artifact =>
      Using.resource(new JarFile(artifact)): jar =>
        Option.when(entries.forall((entryName, _) => jar.getJarEntry(entryName) != null))(artifact)
    candidates match
      case Seq(compilerArtifact) =>
        try
          Right(
            Using.resource(new JarFile(compilerArtifact)): jar =>
              val declarations = entries.map: (entryName, ownerName) =>
                val entry = jar.getJarEntry(entryName)
                Using.resource(jar.getInputStream(entry)): input =>
                  declaredProductsFrom(TastyTreeReader.treeFrom(input.readAllBytes()), ownerName)
              DeclaredProducts(
                declarations
                  .flatMap(_.products)
                  .groupMap(_._1)(_._2)
                  .view
                  .mapValues(uniqueDeclaration("product"))
                  .toMap,
                declarations.flatMap(_.aliases).groupMap(_._1)(_._2).view.mapValues(_.flatten).toMap,
                declarations.flatMap(_.representationBarriers).groupMapReduce(_._1)(_._2)(_ + _)
              )
          )
        catch
          case error: StackOverflowError =>
            Left(s"exact compiler TASTy schema exceeded the reader stack: ${errorMessage(error)}")
          case NonFatal(error)           => Left(s"exact compiler TASTy schema is unavailable: ${errorMessage(error)}")
      case Seq()                 => Left(s"the exact compiler artifacts do not contain ${entries.map(_._1).mkString(" and ")}")
      case _                     => Left(s"more than one exact compiler artifact contains the exact parser TASTy schemas")

  private def declaredProductsFrom(root: TastyNode, ownerName: String): DeclaredProducts =
    var pending   = List(root)
    var templates = Vector.empty[TastyNode]
    while pending.nonEmpty do
      val current = pending.head
      pending = current.children.toList.reverse_:::(pending.tail)
      if current.tag == TYPEDEF && tastyName(current).contains(ownerName) then
        templates = templates ++ current.children.filter(_.tag == TEMPLATE)

    val declarations = templates match
      case Vector(template) => template.children.filter(_.tag == TYPEDEF).toVector
      case _                => throw new IllegalStateException(s"exact compiler TASTy has no unique $ownerName declaration")

    val aliases = declarations.flatMap: declaration =>
      for
        name   <- tastyName(declaration)
        lambda <- declaration.children.find(_.tag == LAMBDAtpt)
        body   <- lambda.children.lastOption
        tpe    <- tastyType(body)
      yield name -> tpe

    val products = declarations.flatMap: declaration =>
      tastyName(declaration).flatMap: name =>
        declaration.children
          .find(_.tag == TEMPLATE)
          .flatMap: template =>
            val accessors = template.children.filter: child =>
              child.tag == PARAM && child.children.exists(_.tag == CASEaccessor)
            Option.when(accessors.nonEmpty):
              val fields = accessors.zipWithIndex.map: (child, ordinal) =>
                val decoded = for
                  fieldName <- tastyName(child)
                  fieldType <- child.children.headOption.flatMap(tastyType)
                yield DeclaredProductField(fieldName, fieldType)
                decoded.getOrElse:
                  throw new IllegalStateException(
                    s"exact compiler TASTy cannot decode $ownerName.$name field ordinal $ordinal: $child"
                  )
              DeclaredProductId(ownerName, name) -> fields.toVector

    val representationBarriers = declarations.flatMap: declaration =>
      tastyName(declaration).filter: _ =>
        declaration.children.exists(_.tag == TYPEBOUNDS) && descendants(declaration).exists: child =>
          child.refName.contains("opaques") || child.names.contains("opaques")

    DeclaredProducts(
      products.groupMap(_._1)(_._2).view.mapValues(uniqueDeclaration("product")).toMap,
      aliases.groupMap(_._1)(_._2),
      representationBarriers.groupMapReduce(identity)(_ => 1)(_ + _)
    )

  private def uniqueDeclaration[A](kind: String)(values: Vector[A]): A =
    values match
      case Vector(value) => value
      case _             => throw new IllegalStateException(s"exact compiler TASTy has ambiguous $kind declarations")

  private def tastyType(node: TastyNode): Option[DeclaredType] =
    if node.tag == APPLIEDtype || node.tag == APPLIEDtpt then
      for
        constructor <- node.children.headOption.flatMap(tastyName)
        arguments   <- sequence(node.children.tail.map(tastyType).toVector)
      yield DeclaredType.Applied(constructor, arguments)
    else if node.tag == ANNOTATEDtype then node.children.headOption.flatMap(tastyType)
    else tastyName(node).map(DeclaredType.Named.apply)

  private def sequence[A](values: Vector[Option[A]]): Option[Vector[A]] =
    Option.when(values.forall(_.nonEmpty))(values.flatten)

  private def descendants(root: TastyNode): Iterator[TastyNode] =
    Iterator.iterate(List(root))(_.flatMap(_.children)).takeWhile(_.nonEmpty).flatMap(_.iterator)

  private def tastyName(node: TastyNode): Option[String] =
    node.refName.orElse(node.names.headOption).filter(_.nonEmpty)

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

  private def discoverScannerReader(
      loader: ClassLoader,
      scannerClass: Class[?],
      sourceFileClass: Class[?],
      contextClass: Class[?]
  ): ScannerReader =
    val profileClass = loader.loadClass("dotty.tools.dotc.reporting.Profile")
    val constructor  = scannerClass.getConstructors
      .find(
        _.getParameterTypes.sameElements(
          Array(sourceFileClass, java.lang.Integer.TYPE, profileClass, java.lang.Boolean.TYPE, contextClass)
        )
      )
      .getOrElse(throw new NoSuchMethodException("Scanners.Scanner exact-source constructor"))
    val tokens       = module(loader, "dotty.tools.dotc.parsing.Tokens$")
    ScannerReader(
      constructor,
      module(loader, "dotty.tools.dotc.reporting.NoProfile$"),
      scannerClass.getMethod("token"),
      scannerClass.getMethod("offset"),
      scannerClass.getMethod("lastOffset"),
      scannerClass.getMethod("nextToken"),
      tokens,
      tokens.getClass.getMethod("showTokenDetailed", java.lang.Integer.TYPE)
    )

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

  private def discoverDiagnosticPositionProvenance(
      loader: Scala3ParserClassLoader
  ): Either[String, DiagnosticPositionProvenanceReader] =
    val sourcePositionClass = loader.loadClass("dotty.tools.dotc.util.SourcePosition")
    val sourceFileClass     = loader.loadClass("dotty.tools.dotc.util.SourceFile")
    val spansModule         = module(loader, "dotty.tools.dotc.util.Spans$")
    val spanCompanion       = module(loader, "dotty.tools.dotc.util.Spans$Span$")
    diagnosticPositionProvenanceReader(sourcePositionClass, spansModule, spanCompanion).flatMap: reader =>
      try
        val sourceSpan        = spansModule.getClass
          .getMethod("Span", java.lang.Integer.TYPE, java.lang.Integer.TYPE, java.lang.Integer.TYPE)
          .invoke(spansModule, Int.box(1), Int.box(2), Int.box(1))
        val syntheticSpan     = spanCompanion.getClass
          .getMethod("toSynthetic$extension", java.lang.Long.TYPE)
          .invoke(spanCompanion, sourceSpan)
        val noPosition        = module(loader, "dotty.tools.dotc.util.NoSourcePosition$")
        val noSource          = module(loader, "dotty.tools.dotc.util.NoSource$")
        val constructor       = sourcePositionClass.getConstructor(
          sourceFileClass,
          java.lang.Long.TYPE,
          sourcePositionClass
        )
        val sourcePosition    = constructor.newInstance(noSource, sourceSpan, noPosition)
        val syntheticPosition = constructor.newInstance(noSource, syntheticSpan, noPosition)
        if reader.read(sourcePosition) != Some(ParserDiagnosticPositionProvenance.SourceDerived) ||
          reader.read(syntheticPosition) != Some(ParserDiagnosticPositionProvenance.Synthetic) ||
          reader.read(noPosition).nonEmpty
        then Left("exact diagnostic positions do not preserve positioned and unpositioned provenance")
        else Right(reader)
      catch case NonFatal(error) => Left(errorMessage(error))

  private[pc] def diagnosticPositionProvenanceCapability(
      sourcePositionClass: Class[?],
      spansModule: AnyRef,
      spanCompanion: AnyRef
  ): ParserCapabilityStatus =
    diagnosticPositionProvenanceReader(sourcePositionClass, spansModule, spanCompanion)
      .fold(ParserCapabilityStatus.Unavailable.apply, _ => ParserCapabilityStatus.Available)

  private def diagnosticPositionProvenanceReader(
      sourcePositionClass: Class[?],
      spansModule: AnyRef,
      spanCompanion: AnyRef
  ): Either[String, DiagnosticPositionProvenanceReader] =
    try
      val reader        = DiagnosticPositionProvenanceReader(
        sourcePositionClass,
        sourcePositionClass.getMethod("exists"),
        sourcePositionClass.getMethod("span"),
        spanCompanion,
        spanCompanion.getClass.getMethod("isSourceDerived$extension", java.lang.Long.TYPE),
        spanCompanion.getClass.getMethod("isSynthetic$extension", java.lang.Long.TYPE)
      )
      val sourceSpan    = spansModule.getClass
        .getMethod("Span", java.lang.Integer.TYPE, java.lang.Integer.TYPE, java.lang.Integer.TYPE)
        .invoke(spansModule, Int.box(1), Int.box(2), Int.box(1))
      val syntheticSpan = spanCompanion.getClass
        .getMethod("toSynthetic$extension", java.lang.Long.TYPE)
        .invoke(spanCompanion, sourceSpan)
      if reader.readSpan(sourceSpan) != ParserDiagnosticPositionProvenance.SourceDerived ||
        reader.readSpan(syntheticSpan) != ParserDiagnosticPositionProvenance.Synthetic
      then Left("exact diagnostic positions do not distinguish source-derived and synthetic provenance")
      else Right(reader)
    catch case NonFatal(error) => Left(errorMessage(error))

  private def discoverSeparatorRecorder(
      loader: Scala3ParserClassLoader,
      parserClass: Class[?],
      scannerClass: Class[?],
      sourceFileClass: Class[?],
      contextClass: Class[?]
  ): Either[String, SeparatorRecorder] =
    try
      val tokenClass            = loader.defineBridgeClass(
        "com.hmemcpy.metallurgy.pc.SeparatorRecordingScanner$Token",
        bridgeClassBytes("com.hmemcpy.metallurgy.pc.SeparatorRecordingScanner$Token")
      )
      val scannerBridgeClass    = loader.defineBridgeClass(
        "com.hmemcpy.metallurgy.pc.SeparatorRecordingScanner",
        bridgeClassBytes("com.hmemcpy.metallurgy.pc.SeparatorRecordingScanner")
      )
      val constructor           = scannerBridgeClass.getConstructors
        .find(_.getParameterTypes.sameElements(Array(sourceFileClass, contextClass, classOf[java.util.List[?]])))
        .getOrElse(throw new NoSuchMethodException("SeparatorRecordingScanner exact constructor"))
      val constructionSinkField = scannerBridgeClass.getField("constructionSink")
      val inField               = parserClass.getDeclaredFields
        .find(field => field.getName == "in")
        .getOrElse(
          throw new NoSuchFieldException("Parsers.Parser in field")
        )
      inField.setAccessible(true)
      val scannerFinal          = Modifier.isFinal(scannerClass.getModifiers)
      val nextTokenFinal        = scannerClass.getMethods.exists(method =>
        method.getName == "nextToken" && method.getParameterCount == 0 && Modifier.isFinal(method.getModifiers)
      )
      if scannerFinal || nextTokenFinal then Left("the exact scanner class or its nextToken method is final")
      else
        val unsafeClass       = Class.forName("sun.misc.Unsafe")
        val unsafeField       = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.setAccessible(true)
        val unsafe            = unsafeField.get(null)
        val objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", classOf[java.lang.reflect.Field])
        val putObject         = unsafeClass.getMethod("putObject", classOf[Object], java.lang.Long.TYPE, classOf[Object])
        Right(
          SeparatorRecorder(
            constructor,
            constructionSinkField,
            tokenClass,
            tokenClass.getField("token"),
            tokenClass.getField("offset"),
            tokenClass.getField("lastOffset"),
            inField,
            unsafe,
            objectFieldOffset,
            putObject
          )
        )
    catch
      case NonFatal(error) => Left(errorMessage(error))
      case error: LinkageError => Left(errorMessage(error))

  private def bridgeClassBytes(name: String): Array[Byte] =
    val resource = s"/${name.replace('.', '/')}.class"
    val stream   = getClass.getResourceAsStream(resource)
    if stream == null then throw new NoSuchMethodException(s"bridge class resource missing: $resource")
    try stream.readAllBytes()
    finally stream.close()

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

  private def availableCapabilities(
      endMarkers: ParserCapabilityStatus,
      diagnosticPositionProvenance: ParserCapabilityStatus
  ): Scala3ParserCapabilities =
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
      diagnosticPositionProvenance = diagnosticPositionProvenance,
      positionedSyntax = ParserCapabilityStatus.Available,
      comments = ParserCapabilityStatus.Available,
      endMarkers = endMarkers,
      scannerTokens = ParserCapabilityStatus.Available
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
      diagnosticPositionProvenance = unavailable,
      positionedSyntax = unavailable,
      comments = unavailable,
      endMarkers = unavailable,
      scannerTokens = unavailable
    )

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getName)

  private final class Scala3ParserClassLoader(urls: Array[java.net.URL])
      extends URLClassLoader(urls, ClassLoader.getPlatformClassLoader):
    private val definedBridgeClasses = scala.collection.mutable.HashMap.empty[String, Class[?]]

    /** Defines packaged bridge bytecode inside this isolated compiler loader. */
    def defineBridgeClass(name: String, bytes: Array[Byte]): Class[?] =
      synchronized:
        definedBridgeClasses.getOrElseUpdate(
          name,
          findLoadedClass(name) match
            case null  => defineClass(name, bytes, 0, bytes.length)
            case found => found
        )

  private final case class ParserRuntime(
      loader: Scala3ParserClassLoader,
      compilerClasspath: String,
      contextBaseConstructor: Constructor[?],
      setReporter: Method,
      reporterFactory: ReporterFactory,
      driverConstructor: Constructor[?],
      driverSetup: Method,
      runContextFactory: RunContextFactory,
      sourceFactory: SourceFactory,
      parserFactory: ParserFactory,
      scannerReader: ScannerReader,
      separatorRecorder: Either[String, SeparatorRecorder],
      diagnosticReader: DiagnosticReader,
      diagnosticPositionProvenance: Either[String, DiagnosticPositionProvenanceReader],
      treeClass: Class[?],
      defTreeClass: Class[?],
      lazyFieldsReader: Option[LazyFieldsReader],
      defTreeRawMods: Method,
      attachments: Option[AttachmentReader],
      endMarkerReader: Either[String, EndMarkerReader],
      positionedClass: Class[?],
      positionedSpan: Method,
      commentReader: Option[CommentReader],
      productClass: Class[?],
      optionClass: Class[?],
      iterableClass: Class[?],
      nameClass: Class[?],
      uniqueNameKindClass: Class[?],
      spansModule: AnyRef,
      nullConstantReader: NullConstantReader,
      declaredProducts: DeclaredProducts
  ):
    def close(): Unit = loader.close()

  private final case class NullConstantReader(owner: Class[?], tag: Method, nullTag: Int):
    def isNullConstant(value: AnyRef): Boolean =
      owner.isInstance(value) && tag.invoke(value).asInstanceOf[java.lang.Integer].intValue() == nullTag

  private final case class SeparatorRecorder(
      scannerConstructor: Constructor[?],
      constructionSinkField: java.lang.reflect.Field,
      tokenClass: Class[?],
      tokenField: java.lang.reflect.Field,
      offsetField: java.lang.reflect.Field,
      lastOffsetField: java.lang.reflect.Field,
      inField: java.lang.reflect.Field,
      unsafe: AnyRef,
      objectFieldOffset: java.lang.reflect.Method,
      putObject: java.lang.reflect.Method
  )

  private final case class RunContextFactory(
      compilerConstructor: Constructor[?],
      newRun: Method,
      runContext: Method
  ):
    def create(context: AnyRef): AnyRef =
      val compiler = compilerConstructor.newInstance()
      val run      = newRun.invoke(compiler, context)
      runContext.invoke(run)

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

  private final case class DiagnosticPositionProvenanceReader(
      sourcePositionClass: Class[?],
      exists: Method,
      span: Method,
      spanCompanion: AnyRef,
      isSourceDerived: Method,
      isSynthetic: Method
  ):
    def read(position: AnyRef): Option[ParserDiagnosticPositionProvenance] =
      if !sourcePositionClass.isInstance(position) then
        throw new IllegalStateException("diagnostic position does not satisfy the exact source-position capability")
      else if !exists.invoke(position).asInstanceOf[java.lang.Boolean].booleanValue() then None
      else Some(readSpan(span.invoke(position)))

    def readSpan(value: AnyRef): ParserDiagnosticPositionProvenance =
      val sourceDerived = isSourceDerived.invoke(spanCompanion, value).asInstanceOf[java.lang.Boolean].booleanValue()
      val synthetic     = isSynthetic.invoke(spanCompanion, value).asInstanceOf[java.lang.Boolean].booleanValue()
      (sourceDerived, synthetic) match
        case (true, false) => ParserDiagnosticPositionProvenance.SourceDerived
        case (false, true) => ParserDiagnosticPositionProvenance.Synthetic
        case _             =>
          throw new IllegalStateException("diagnostic position provenance is neither source-derived nor synthetic")

  private final case class ScannerReader(
      constructor: Constructor[?],
      noProfile: AnyRef,
      token: Method,
      offset: Method,
      lastOffset: Method,
      nextToken: Method,
      tokens: AnyRef,
      showTokenDetailed: Method
  )

  private enum CommentReader(val parserInput: Method, val comments: Method):
    case Modern(input: Method, method: Method) extends CommentReader(input, method)
    case Legacy(input: Method, method: Method) extends CommentReader(input, method)

  private final case class EndMarkerReader(ownerClass: Class[?], hasMarker: Method, span: Method)

  private final case class LazyFieldsReader(ownerClass: Class[?], forceFields: Method)

  private final case class AttachmentReader(all: Method, keyNames: IdentityHashMap[AnyRef, String])

  private final case class DeclaredProducts(
      products: Map[DeclaredProductId, Vector[DeclaredProductField]],
      aliases: Map[String, Vector[DeclaredType]],
      representationBarriers: Map[String, Int]
  )

  private final case class DeclaredProductId(ownerName: String, productName: String)

  private final case class DeclaredProductField(name: String, fieldType: DeclaredType)

  private enum DeclaredType:
    case Named(name: String)
    case Applied(constructor: String, arguments: Vector[DeclaredType])

  private enum ReporterFactory:
    case SingleArgument(constructor: Constructor[?])
    case WithTyperState(constructor: Constructor[?])

    def create(): AnyRef =
      this match
        case SingleArgument(constructor) => constructor.newInstance(null)
        case WithTyperState(constructor) => constructor.newInstance(null, java.lang.Boolean.FALSE)

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

  private type TypeNameValue = {
    def toTermName(): AnyRef
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

  private val separatorAdmitted: Boolean =
    initialRuntime.separatorRecorder.exists(_ => separatorAdmissionProbe(initialRuntime).isRight)

  private val separatorStatus: ParserCapabilityStatus =
    if separatorAdmitted then ParserCapabilityStatus.Available
    else ParserCapabilityStatus.Unavailable("parser-owned separator admission failed")

  override val capabilities: Scala3ParserCapabilities =
    val base = availableCapabilities(
      initialRuntime.endMarkerReader
        .fold(ParserCapabilityStatus.Unavailable.apply, _ => ParserCapabilityStatus.Available),
      initialRuntime.diagnosticPositionProvenance.fold(
        ParserCapabilityStatus.Unavailable.apply,
        _ => ParserCapabilityStatus.Available
      )
    )
    base.copy(separatorProvenance = separatorStatus)

  private def separatorAdmissionProbe(
      active: ParserRuntime
  ): Either[String, Unit] =
    try
      withCompilerClassloader(active):
        val probeText        = "class SeparatorAdmissionProbe { def bound(v: pkg.Outer#T): pkg.Outer#T = v }"
        val request          = Scala3ParserRequest(
          ParserSourceUri
            .from("file:///separator-admission-probe.scala")
            .getOrElse(throw new IllegalStateException()),
          probeText,
          Vector.empty,
          Scala3ParserCancellation.Never
        )
        val stock            = parseSource(
          active,
          request.copy(forceStockParse = true),
          configuredContext(active, request).getOrElse {
            throw new IllegalStateException("admission context setup failed")
          }
        )
        val recordingContext = configuredContext(active, request)
          .getOrElse(throw new IllegalStateException("admission context setup failed"))
        val recording        = parseSource(active, request, recordingContext, forceRecording = true)
        (stock, recording) match
          case (Right(left), Right(right)) =>
            // Capabilities are per-bridge state that admission runs before it is assigned;
            // normalize the field so every per-parse part of both snapshots compares.
            val admissionSentinel = Scala3ParserCapabilities(
              ParserCapabilityStatus.Unavailable("admission comparison"),
              ParserCapabilityStatus.Unavailable("admission comparison"),
              ParserCapabilityStatus.Unavailable("admission comparison"),
              ParserCapabilityStatus.Unavailable("admission comparison"),
              ParserCapabilityStatus.Unavailable("admission comparison"),
              ParserCapabilityStatus.Unavailable("admission comparison"),
              ParserCapabilityStatus.Unavailable("admission comparison"),
              ParserCapabilityStatus.Unavailable("admission comparison"),
              ParserCapabilityStatus.Unavailable("admission comparison"),
              ParserCapabilityStatus.Unavailable("admission comparison"),
              ParserCapabilityStatus.Unavailable("admission comparison"),
              ParserCapabilityStatus.Unavailable("admission comparison"),
              ParserCapabilityStatus.Unavailable("admission comparison")
            )
            val equivalent        = left.copy(capabilities = admissionSentinel) ==
              right.copy(nodeSeparators = Vector.empty, capabilities = admissionSentinel)
            if !equivalent then Left("the recording parse changed the parser snapshot")
            else if right.nodeSeparators.isEmpty then
              Left("the recording parse produced no separator facts for the probe source")
            else Right(())
          case _                           => Left("an admission parse failed")
    catch
      case NonFatal(error) => Left(errorMessage(error))
      case error: LinkageError => Left(errorMessage(error))

  /** Constructs a parser whose scanner records the exact parse's consumed tokens. */
  private def constructRecordingParser(
      active: ParserRuntime,
      recorder: SeparatorRecorder,
      source: AnyRef,
      context: ParserContext,
      sink: java.util.List[AnyRef]
  ): AnyRef =
    val constructionSink = recorder.constructionSinkField.get(null).asInstanceOf[ThreadLocal[java.util.List[AnyRef]]]
    constructionSink.set(sink)
    try
      val recordingScanner = recorder.scannerConstructor.newInstance(source, context.value, sink)
      val parser           = active.parserFactory.create(source, context.value)
      val fieldOffset      =
        recorder.objectFieldOffset.invoke(recorder.unsafe, recorder.inField).asInstanceOf[java.lang.Long].longValue()
      recorder.putObject.invoke(recorder.unsafe, parser, java.lang.Long.valueOf(fieldOffset), recordingScanner)
      parser
    finally constructionSink.set(null)

  /** Correlates parser-owned separator evidence to Select nodes: every positioned Select with a positioned qualifier
    * and a name owns exactly one distinct source-derived Dot or Hash token in the node-bounded interval between the
    * qualifier end and the proven name start. Repeated visits of the same token identity collapse; conflicting
    * observations for one identity revoke all evidence. Selects without a proven unique separator receive no evidence
    * and stay unowned.
    */
  private def correlateSeparators(
      active: ParserRuntime,
      recorded: Vector[(Int, Int, Int)],
      sourceText: String,
      nodes: Vector[ParserSyntaxNode]
  ): Vector[ParserNodeSeparator] =
    // Each recorded observation names the token that became current and the end of the
    // token before it; a token's own end is the next observation's carried end.
    val consumption = recorded
      .zip(recorded.drop(1) :+ recorded.last)
      .map { case ((tokenId, offset, _), (_, _, nextEnd)) => (tokenId, offset, math.max(offset, nextEnd)) }
    val byOffset    =
      consumption
        .groupMap((tokenId, offset, _) => (tokenId, offset))(value => value)
        .map { case ((tokenId, offset), observations) =>
          val ends = observations.map((_, _, end) => end).distinct
          if ends.size != 1 then None
          else
            val runtimeKind =
              active.scannerReader.showTokenDetailed
                .invoke(active.scannerReader.tokens, Int.box(tokenId))
                .asInstanceOf[String]
            val kind        =
              scannerTokenKind(runtimeKind, sourceText.substring(offset, math.min(ends.head, sourceText.length)))
            Some(offset -> (ends.head, kind))
        }
        .toVector
    if byOffset.exists(_.isEmpty) then Vector.empty
    else
      val offsets = byOffset
        .collect { case Some(entry) => entry }
        .groupMap((offset, _) => offset)((_, fact) => fact)
      nodes.flatMap(node => separatorForSelect(offsets, sourceText, nodes, node))

  private def separatorForSelect(
      byOffset: Map[Int, Vector[(Int, ParserScannerTokenKind)]],
      sourceText: String,
      nodes: Vector[ParserSyntaxNode],
      node: ParserSyntaxNode
  ): Vector[ParserNodeSeparator] =
    node.position match
      case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived) =>
        val owned =
          for
            qualifierId  <- node.fields.collectFirst:
                              case ParserSyntaxField("qualifier", ParserFieldValue.Node(id), _) => id
            qualifier    <- nodes.find(_.id == qualifierId)
            qualifierEnd <- qualifier.position match
                              case ParserNodePosition.Positioned(
                                    qualifierRange,
                                    _,
                                    ParserPositionProvenance.SourceDerived
                                  ) =>
                                Some(qualifierRange.endOffset)
                              case _ => None
            name         <- node.fields.collectFirst:
                              case ParserSyntaxField("name", ParserFieldValue.Name(value), _) => value
            nameBoundary  = byOffset.toVector.flatMap: (offset, facts) =>
                              facts.collectFirst:
                                case (end, _) if end == range.endOffset =>
                                  val slice = sourceText.substring(offset, range.endOffset)
                                  if slice == name || slice == s"`$name`" then offset else Int.MinValue
            nameStart    <- nameBoundary.filter(_ != Int.MinValue) match
                              case Vector(single) => Some(single)
                              case _              =>
                                val derived = range.endOffset - name.length
                                val slice   = sourceText.substring(derived, range.endOffset)
                                if slice == name then Some(derived) else None
            if nameStart > qualifierEnd
            candidates    = byOffset.toVector.flatMap: (offset, facts) =>
                              facts.collect:
                                case (end, kind)
                                    if offset >= qualifierEnd && end <= nameStart &&
                                      (kind == ParserScannerTokenKind.Dot ||
                                        kind == ParserScannerTokenKind.Hash) =>
                                  (offset, end, kind)
            separator    <- candidates.toVector match
                              case Vector(single) => Some(single)
                              case _              => None
          yield ParserNodeSeparator(
            node.id,
            separator._3,
            PcSourceRange(separator._1, separator._2),
            separator._1,
            ParserPositionProvenance.SourceDerived
          )
        owned.toVector
      case _                                                                               => Vector.empty

  private def readRecordedTokens(
      recorder: SeparatorRecorder,
      sink: java.util.List[AnyRef]
  ): Vector[(Int, Int, Int)] =
    sink.asScala.toVector.map: token =>
      (
        recorder.tokenField.get(token).asInstanceOf[java.lang.Integer].intValue(),
        recorder.offsetField.get(token).asInstanceOf[java.lang.Integer].intValue(),
        recorder.lastOffsetField.get(token).asInstanceOf[java.lang.Integer].intValue()
      )

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
            catch
              case control: ControlFlowException => throw control
              case NonFatal(error)               =>
                controlFlowCause(error).fold(Left(Scala3ParserError.ParseFailed(errorMessage(error))))(throw _)
          .getOrElse(Left(Scala3ParserError.Closed))

  override def close(): Unit =
    runtime.getAndSet(None).foreach(_.retire())

  private def controlFlowCause(error: Throwable): Option[Throwable & ControlFlowException] =
    val seen = new IdentityHashMap[Throwable, java.lang.Boolean]()
    var next = Option(error)
    while next.nonEmpty && !seen.containsKey(next.get) do
      val value = next.get
      seen.put(value, java.lang.Boolean.TRUE)
      value match
        case control: ControlFlowException => return Some(control)
        case _                             => next = Option(value.getCause)
    None

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
    val setupArguments  =
      (Vector("-classpath", active.compilerClasspath) ++ request.compilerOptions :+ "ParserInput.scala").toArray
    val configured      = active.driverSetup.invoke(driver, setupArguments, reporting)
    val configuredValue = configured.asInstanceOf[OptionValue]
    if configuredValue.isEmpty() then
      Left(Scala3ParserError.SetupRejected("the exact compiler rejected parser options"))
    else
      val tuple   = configuredValue.get().asInstanceOf[ProductValue]
      val context = active.runContextFactory.create(tuple.productElement(1))
      Right(ParserContext(context, reporter))

  private def parseSource(
      active: ParserRuntime,
      request: Scala3ParserRequest,
      context: ParserContext,
      forceRecording: Boolean = false
  ): Either[Scala3ParserError, ParserSyntaxSnapshot] =
    request.cancellation.checkCanceled()
    val source                   = active.sourceFactory.create(request.sourceUri.value, request.sourceText)
    val sink                     = new java.util.ArrayList[AnyRef]
    val (parser, recordedStream) = active.separatorRecorder match
      case Right(recorder) if forceRecording || (separatorAdmitted && !request.forceStockParse) =>
        (
          constructRecordingParser(active, recorder, source, context, sink).asInstanceOf[ParserValue],
          Some((recorder, readRecordedTokens(recorder, sink)))
        )
      case _                                                                                    =>
        (active.parserFactory.create(source, context.value).asInstanceOf[ParserValue], None)
    val root                     = parser.parse()
    request.cancellation.checkCanceled()
    val nodes                    = collectNodes(active, root, context.value, request.sourceText, request.cancellation)
    val foundDiagnostics         = diagnostics(active, context, request.cancellation)
    val scannerTokens            =
      exactScannerTokens(active, source, context.value, request.sourceText, request.cancellation)
    nodes.map: collected =>
      val separators = recordedStream.map: (recorder, _) =>
        val recorded           = readRecordedTokens(recorder, sink)
        // Replay validates the capture's integrity as a whole, never which facts exist:
        // every replayed token identity must appear among the recorded visits, and any
        // recorded-only identity may only be the end-of-stream repeat. A violation
        // revokes all separator evidence for the parse; otherwise every parser-owned
        // fact publishes and selection resolves them without replay.
        val recordedIdentities = recorded.map((token, offset, _) => (token, offset)).toSet
        val replayIdentities   = scannerTokens.map(token => (token.tokenId, token.range.startOffset)).toSet
        // Every replayed token identity must appear among the recorded visits; the
        // recording may carry additional lookahead, folded-newline, or end-of-stream
        // identities the linear replay never emits.
        val integrityHolds     = replayIdentities.subsetOf(recordedIdentities)
        if integrityHolds then correlateSeparators(active, recorded, request.sourceText, collected.nodes)
        else Vector.empty
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
        foundDiagnostics,
        capabilities,
        identity,
        collected.endMarkers,
        collected.runtimeSupplements,
        collected.attachments,
        scannerTokens,
        separators.getOrElse(Vector.empty)
      )

  private def exactScannerTokens(
      active: ParserRuntime,
      source: AnyRef,
      context: AnyRef,
      sourceText: String,
      cancellation: Scala3ParserCancellation
  ): Vector[ParserScannerToken] =
    val sourceLength = sourceText.length
    val reader       = active.scannerReader
    val scanner      = reader.constructor
      .newInstance(source, Int.box(0), reader.noProfile, Boolean.box(true), context)
    val result       = Vector.newBuilder[ParserScannerToken]
    var ordinal      = 0
    var complete     = false
    while !complete do
      cancellation.checkCanceled()
      val tokenId     = reader.token.invoke(scanner).asInstanceOf[java.lang.Integer].intValue()
      val runtimeKind = reader.showTokenDetailed.invoke(reader.tokens, Int.box(tokenId)).asInstanceOf[String]
      if runtimeKind == "eof" then complete = true
      else
        val start      = reader.offset.invoke(scanner).asInstanceOf[java.lang.Integer].intValue()
        val _          = reader.nextToken.invoke(scanner)
        val rawEnd     = reader.lastOffset.invoke(scanner).asInstanceOf[java.lang.Integer].intValue()
        if start < 0 || start > sourceLength || rawEnd < 0 || rawEnd > sourceLength then
          throw new IllegalStateException(s"exact scanner returned invalid token offsets [$start,$rawEnd]")
        // Inserted layout tokens retain the end of the preceding source token. They are distinct zero-width events.
        val end        = math.max(start, rawEnd)
        val provenance =
          if rawEnd > start then ParserPositionProvenance.SourceDerived else ParserPositionProvenance.Synthetic
        result += ParserScannerToken(
          ordinal,
          tokenId,
          runtimeKind,
          scannerTokenKind(runtimeKind, sourceText.substring(start, end)),
          PcSourceRange(start, end),
          start,
          provenance
        )
        ordinal += 1
    result.result()

  private def scannerTokenKind(runtimeKind: String, sourceText: String): ParserScannerTokenKind = runtimeKind match
    case "'.'"                               => ParserScannerTokenKind.Dot
    case "#"                                 => ParserScannerTokenKind.Hash
    case "'('"                               => ParserScannerTokenKind.LeftParenthesis
    case "')'"                               => ParserScannerTokenKind.RightParenthesis
    case "'['"                               => ParserScannerTokenKind.LeftBracket
    case "']'"                               => ParserScannerTokenKind.RightBracket
    case "'{'"                               => ParserScannerTokenKind.LeftBrace
    case "'}'"                               => ParserScannerTokenKind.RightBrace
    case "','"                               => ParserScannerTokenKind.Comma
    case "@" | "'@'"                         => ParserScannerTokenKind.AtSign
    case ":"                                 => ParserScannerTokenKind.Colon
    case "=" | "'='"                         => ParserScannerTokenKind.Equals
    case "=>"                                => ParserScannerTokenKind.FunctionArrow
    case "?=>"                               => ParserScannerTokenKind.ContextFunctionArrow
    case "identifier" if sourceText == "=>"  => ParserScannerTokenKind.FunctionArrow
    case "identifier" if sourceText == "?=>" => ParserScannerTokenKind.ContextFunctionArrow
    case "identifier" if sourceText == "->"  => ParserScannerTokenKind.PureFunctionArrow
    case "identifier" if sourceText == "?->" => ParserScannerTokenKind.ContextPureFunctionArrow
    case "identifier" if sourceText == "^"   => ParserScannerTokenKind.CaptureOperator
    case "identifier" if sourceText == "@"   => ParserScannerTokenKind.AtSign
    case "identifier"                        => ParserScannerTokenKind.Identifier
    case "type"                              => ParserScannerTokenKind.TypeKeyword
    case "character literal" | "integer literal" | "number literal" | "number literal with exponent" | "long literal" |
        "float literal" | "double literal" | "string literal" | "true" | "false" =>
      ParserScannerTokenKind.Literal
    case _                                   => ParserScannerTokenKind.Other

  private def collectNodes(
      active: ParserRuntime,
      root: AnyRef,
      context: AnyRef,
      source: String,
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
      var endMarkers       = Vector.empty[ParserEndMarker]
      var supplements      = Vector.empty[ParserRuntimeSupplement]
      var attachments      = Vector.empty[ParserTreeAttachment]
      val positionedIds    = new IdentityHashMap[AnyRef, java.lang.Long]()
      var nextPositionedId = 0L

      sealed trait EvaluationResult
      final case class TreeResult(id: Long)                            extends EvaluationResult
      final case class PositionedResult(id: Long)                      extends EvaluationResult
      final case class FieldValueResult(value: ParserFieldValue)       extends EvaluationResult
      final case class FieldsResult(fields: Vector[ParserSyntaxField]) extends EvaluationResult

      sealed trait EvaluationFrame
      final case class EnterTree(tree: AnyRef, occurrence: Option[ParserNodeOccurrence]) extends EvaluationFrame
      final case class EvaluateTreeFields(tree: AnyRef, id: Long)                        extends EvaluationFrame
      final case class ContinueTreeFields(tree: AnyRef, id: Long)                        extends EvaluationFrame
      final case class FinishTreeFields(fields: Vector[ParserSyntaxField])               extends EvaluationFrame
      final case class FillTree(tree: AnyRef, id: Long, occurrence: Option[ParserNodeOccurrence])
          extends EvaluationFrame
      final case class EnterPositioned(
          value: AnyRef,
          ownerNodeId: Long,
          path: Vector[ParserFieldPathSegment]
      ) extends EvaluationFrame
      final case class FillPositioned(id: Long)                                          extends EvaluationFrame
      final case class EvaluateProductFields(
          product: ProductValue,
          ownerNodeId: Long,
          path: Vector[ParserFieldPathSegment]
      ) extends EvaluationFrame
      final case class EvaluateProductField(
          product: ProductValue,
          arity: Int,
          ownerNodeId: Long,
          path: Vector[ParserFieldPathSegment],
          index: Int,
          fields: Vector[ParserSyntaxField]
      ) extends EvaluationFrame
      final case class FinishProductField(
          product: ProductValue,
          arity: Int,
          ownerNodeId: Long,
          path: Vector[ParserFieldPathSegment],
          index: Int,
          fields: Vector[ParserSyntaxField],
          fieldName: String
      ) extends EvaluationFrame
      final case class EvaluateFieldValue(
          value: AnyRef,
          ownerNodeId: Long,
          path: Vector[ParserFieldPathSegment],
          nullIsConstant: Boolean = false
      ) extends EvaluationFrame
      case object FinishNodeValue                                                        extends EvaluationFrame
      case object FinishPositionedValue                                                  extends EvaluationFrame
      case object FinishOptionalValue                                                    extends EvaluationFrame
      final case class EvaluateRepeatedValue(
          values: Vector[AnyRef],
          ownerNodeId: Long,
          path: Vector[ParserFieldPathSegment],
          index: Int,
          fields: Vector[ParserFieldValue]
      ) extends EvaluationFrame
      final case class FinishRepeatedValue(
          values: Vector[AnyRef],
          ownerNodeId: Long,
          path: Vector[ParserFieldPathSegment],
          index: Int,
          fields: Vector[ParserFieldValue]
      ) extends EvaluationFrame
      final case class FinishProductValue(value: AnyRef, production: String)             extends EvaluationFrame

      def nodeOccurrence(id: Long, occurrence: ParserNodeOccurrence): Unit =
        val index   = collected.indexWhere(_.id == id)
        val current = collected(index)
        if !current.occurrences.contains(occurrence) then
          collected = collected.updated(index, current.copy(occurrences = current.occurrences :+ occurrence))

      def positionedOccurrence(id: Long, occurrence: ParserPositionedOccurrence): Unit =
        val index   = positioned.indexWhere(_.id == id)
        val current = positioned(index)
        if !current.occurrences.contains(occurrence) then
          positioned = positioned.updated(index, current.copy(occurrences = current.occurrences :+ occurrence))

      def treeResult(result: EvaluationResult): Long = result match
        case TreeResult(id) => id
        case other          => throw new IllegalStateException(s"expected tree traversal result, found $other")

      def positionedResult(result: EvaluationResult): Long = result match
        case PositionedResult(id) => id
        case other                => throw new IllegalStateException(s"expected positioned traversal result, found $other")

      def fieldValueResult(result: EvaluationResult): ParserFieldValue = result match
        case FieldValueResult(value) => value
        case other                   => throw new IllegalStateException(s"expected field traversal result, found $other")

      def fieldsResult(result: EvaluationResult): Vector[ParserSyntaxField] = result match
        case FieldsResult(fields) => fields
        case other                => throw new IllegalStateException(s"expected product traversal result, found $other")

      val stack                    = new java.util.ArrayDeque[EvaluationFrame]()
      var result: EvaluationResult = null
      stack.push(EnterTree(root, None))

      while !stack.isEmpty do
        stack.pop() match
          case EnterTree(tree, occurrence)                                                     =>
            Option(ids.get(tree)) match
              case Some(existing) =>
                val id = existing.longValue()
                occurrence.foreach(nodeOccurrence(id, _))
                result = TreeResult(id)
              case None           =>
                cancellation.checkCanceled()
                active.lazyFieldsReader.foreach: reader =>
                  if reader.ownerClass.isInstance(tree) then
                    val _ = reader.forceFields.invoke(tree, context)
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
                endMarker(active, tree, id, context, source).foreach(marker => endMarkers :+= marker)
                stack.push(FillTree(tree, id, occurrence))
                stack.push(EvaluateTreeFields(tree, id))
                result = null
          case EvaluateTreeFields(tree, id)                                                    =>
            stack.push(ContinueTreeFields(tree, id))
            stack.push(EvaluateProductFields(tree.asInstanceOf[ProductValue], id, Vector.empty))
            result = null
          case ContinueTreeFields(tree, id)                                                    =>
            val fields = fieldsResult(result)
            if active.defTreeClass.isInstance(tree) && !fields.exists(_.name == "mods") then
              stack.push(FinishTreeFields(fields))
              stack.push(
                EvaluateFieldValue(
                  active.defTreeRawMods.invoke(tree),
                  id,
                  Vector(ParserFieldPathSegment.NamedField("mods"))
                )
              )
              result = null
            else result = FieldsResult(fields)
          case FinishTreeFields(fields)                                                        =>
            result = FieldsResult(fields :+ ParserSyntaxField("mods", fieldValueResult(result)))
          case FillTree(tree, id, occurrence)                                                  =>
            val fields  = fieldsResult(result)
            val index   = collected.indexWhere(_.id == id)
            val current = collected(index)
            collected = collected.updated(index, current.copy(fields = fields))
            runtimeSupplement(active, tree, id, fields, cancellation).foreach(value => supplements :+= value)
            attachments = attachments ++ treeAttachments(active, tree, id, cancellation)
            occurrence.foreach(nodeOccurrence(id, _))
            result = TreeResult(id)
          case EnterPositioned(value, ownerNodeId, path)                                       =>
            Option(positionedIds.get(value)) match
              case Some(existing) =>
                val id = existing.longValue()
                positionedOccurrence(id, ParserPositionedOccurrence(ownerNodeId, path))
                result = PositionedResult(id)
              case None           =>
                val id      = nextPositionedId
                nextPositionedId += 1
                positionedIds.put(value, id)
                val product = value.asInstanceOf[ProductValue]
                positioned :+= ParserPositionedSyntax(
                  id,
                  product.productPrefix(),
                  Vector.empty,
                  positionedPosition(active, value),
                  Vector.empty
                )
                positionedOccurrence(id, ParserPositionedOccurrence(ownerNodeId, path))
                stack.push(FillPositioned(id))
                stack.push(EvaluateProductFields(product, ownerNodeId, path))
                result = null
          case FillPositioned(id)                                                              =>
            val fields  = fieldsResult(result)
            val index   = positioned.indexWhere(_.id == id)
            val current = positioned(index)
            positioned = positioned.updated(index, current.copy(fields = fields))
            result = PositionedResult(id)
          case EvaluateProductFields(product, ownerNodeId, path)                               =>
            stack.push(EvaluateProductField(product, product.productArity(), ownerNodeId, path, 0, Vector.empty))
            result = null
          case EvaluateProductField(product, arity, ownerNodeId, path, index, fields)          =>
            if index == arity then result = FieldsResult(fields)
            else
              cancellation.checkCanceled()
              val fieldName = product.productElementName(index)
              val value     = product.productElement(index)
              val fieldPath = path :+ ParserFieldPathSegment.NamedField(product.productElementName(index))
              stack.push(FinishProductField(product, arity, ownerNodeId, path, index, fields, fieldName))
              stack.push(
                EvaluateFieldValue(value, ownerNodeId, fieldPath, active.nullConstantReader.isNullConstant(product))
              )
              result = null
          case FinishProductField(product, arity, ownerNodeId, path, index, fields, fieldName) =>
            val field = ParserSyntaxField(
              fieldName,
              fieldValueResult(result),
              declaredFieldShape(active, product, index, product.productElementName(index))
            )
            stack.push(EvaluateProductField(product, arity, ownerNodeId, path, index + 1, fields :+ field))
            result = null
          case EvaluateFieldValue(value, ownerNodeId, path, nullIsConstant)                    =>
            if value == null && nullIsConstant then
              result = FieldValueResult(ParserFieldValue.Scalar(ParserScalar.NullValue))
            else if value == null then result = FieldValueResult(ParserFieldValue.Optional(None))
            else if active.treeClass.isInstance(value) then
              stack.push(FinishNodeValue)
              stack.push(EnterTree(value, Some(ParserNodeOccurrence(ownerNodeId, path))))
              result = null
            else
              value match
                case text: String                                =>
                  result = FieldValueResult(ParserFieldValue.Scalar(ParserScalar.Text(text)))
                case number: java.lang.Integer                   =>
                  result = FieldValueResult(ParserFieldValue.Scalar(ParserScalar.Integer(number.intValue())))
                case number: java.lang.Long                      =>
                  result = FieldValueResult(ParserFieldValue.Scalar(ParserScalar.LongInteger(number.longValue())))
                case number: java.lang.Double                    =>
                  result = FieldValueResult(ParserFieldValue.Scalar(ParserScalar.Decimal(number.doubleValue())))
                case number: java.lang.Float                     =>
                  result = FieldValueResult(ParserFieldValue.Scalar(ParserScalar.FloatDecimal(number.floatValue())))
                case logical: java.lang.Boolean                  =>
                  result = FieldValueResult(ParserFieldValue.Scalar(ParserScalar.Logical(logical.booleanValue())))
                case character: java.lang.Character              =>
                  result = FieldValueResult(ParserFieldValue.Scalar(ParserScalar.Character(character.charValue())))
                case _ if active.nameClass.isInstance(value)     =>
                  result = FieldValueResult(parserName(active, value, generated))
                case _ if active.optionClass.isInstance(value)   =>
                  val option = value.asInstanceOf[OptionValue]
                  if option.isEmpty() then result = FieldValueResult(ParserFieldValue.Optional(None))
                  else
                    stack.push(FinishOptionalValue)
                    stack.push(
                      EvaluateFieldValue(
                        option.get(),
                        ownerNodeId,
                        path :+ ParserFieldPathSegment.OptionalNesting
                      )
                    )
                    result = null
                case _ if active.iterableClass.isInstance(value) =>
                  val values = iteratorValues(value.asInstanceOf[IterableValue], cancellation)
                  stack.push(EvaluateRepeatedValue(values, ownerNodeId, path, 0, Vector.empty))
                  result = null
                case _ if active.productClass.isInstance(value)  =>
                  if active.positionedClass.isInstance(value) then
                    stack.push(FinishPositionedValue)
                    stack.push(EnterPositioned(value, ownerNodeId, path))
                    result = null
                  else if products.put(value, java.lang.Boolean.TRUE) != null then
                    result = FieldValueResult(ParserFieldValue.Unsupported(value.getClass.getName))
                  else
                    val product    = value.asInstanceOf[ProductValue]
                    val production = product.productPrefix()
                    val boundary   = product.productPrefix()
                    stack.push(FinishProductValue(value, production))
                    stack.push(
                      EvaluateProductFields(
                        product,
                        ownerNodeId,
                        path :+ ParserFieldPathSegment.NestedProductBoundary(boundary)
                      )
                    )
                    result = null
                case _                                           =>
                  result = FieldValueResult(ParserFieldValue.Unsupported(value.getClass.getName))
          case FinishNodeValue                                                                 =>
            result = FieldValueResult(ParserFieldValue.Node(treeResult(result)))
          case FinishPositionedValue                                                           =>
            result = FieldValueResult(ParserFieldValue.Positioned(positionedResult(result)))
          case FinishOptionalValue                                                             =>
            result = FieldValueResult(ParserFieldValue.Optional(Some(fieldValueResult(result))))
          case EvaluateRepeatedValue(values, ownerNodeId, path, index, fields)                 =>
            if index == values.size then result = FieldValueResult(ParserFieldValue.Repeated(fields))
            else
              stack.push(FinishRepeatedValue(values, ownerNodeId, path, index, fields))
              stack.push(
                EvaluateFieldValue(
                  values(index),
                  ownerNodeId,
                  path :+ ParserFieldPathSegment.RepeatedIndex(index)
                )
              )
              result = null
          case FinishRepeatedValue(values, ownerNodeId, path, index, fields)                   =>
            stack.push(
              EvaluateRepeatedValue(values, ownerNodeId, path, index + 1, fields :+ fieldValueResult(result))
            )
            result = null
          case FinishProductValue(value, production)                                           =>
            val fields = fieldsResult(result)
            products.remove(value)
            result = FieldValueResult(ParserFieldValue.Product(production, fields))

      val rootId = treeResult(result)
      Right(
        CollectedNodes(
          rootId,
          collected.sortBy(_.id),
          positioned.sortBy(_.id),
          endMarkers.sortBy(_.designatorRange.startOffset),
          supplements.sortBy(_.ownerNodeId),
          attachments.sortBy(value => (value.ownerNodeId, value.ordinal))
        )
      )

  private def runtimeSupplement(
      active: ParserRuntime,
      tree: AnyRef,
      ownerNodeId: Long,
      productFields: Vector[ParserSyntaxField],
      cancellation: Scala3ParserCancellation
  ): Option[ParserRuntimeSupplement] =
    val runtimeClass = tree.getClass
    val productOwner = runtimeClass.getMethod("productArity").getDeclaringClass
    if runtimeClass == productOwner then None
    else
      val productFieldNames = productFields.map(_.name).toSet
      val fields            = runtimeClass.getDeclaredMethods.toVector
        .filter(method =>
          Modifier.isPublic(method.getModifiers) &&
            method.getParameterCount == 0 &&
            !method.isBridge &&
            !method.isSynthetic &&
            active.iterableClass.isAssignableFrom(method.getReturnType) &&
            !productFieldNames(method.getName)
        )
        .sortBy(method => (method.getName, method.getReturnType.getName))
        .map: method =>
          cancellation.checkCanceled()
          val count = iteratorValues(method.invoke(tree).asInstanceOf[IterableValue], cancellation).size
          ParserSyntaxField(
            s"${method.getName}Count",
            ParserFieldValue.Scalar(ParserScalar.Integer(count)),
            Some(ParserDeclaredShape.Scalar("Integer"))
          )
      Option.when(fields.nonEmpty)(ParserRuntimeSupplement(ownerNodeId, fields))

  private def treeAttachments(
      active: ParserRuntime,
      tree: AnyRef,
      ownerNodeId: Long,
      cancellation: Scala3ParserCancellation
  ): Vector[ParserTreeAttachment] =
    active.attachments.toVector.flatMap: reader =>
      iteratorValues(reader.all.invoke(tree).asInstanceOf[IterableValue], cancellation).zipWithIndex.map:
        (entry, ordinal) =>
          cancellation.checkCanceled()
          if !active.productClass.isInstance(entry) then
            throw new IllegalStateException("compiler attachment entry is not a product")
          val pair = entry.asInstanceOf[ProductValue]
          if pair.productArity() != 2 then
            throw new IllegalStateException("compiler attachment entry is not a key/value pair")
          ParserTreeAttachment(
            ownerNodeId,
            ordinal,
            Option(reader.keyNames.get(pair.productElement(0))).getOrElse(runtimeKind(pair.productElement(0))),
            attachmentValue(active, pair.productElement(1))
          )

  private def attachmentValue(active: ParserRuntime, value: AnyRef): ParserAttachmentValue =
    value match
      case text: String                               => ParserAttachmentValue.Scalar(ParserScalar.Text(text))
      case number: java.lang.Integer                  => ParserAttachmentValue.Scalar(ParserScalar.Integer(number.intValue()))
      case number: java.lang.Long                     => ParserAttachmentValue.Scalar(ParserScalar.LongInteger(number.longValue()))
      case number: java.lang.Double                   => ParserAttachmentValue.Scalar(ParserScalar.Decimal(number.doubleValue()))
      case number: java.lang.Float                    => ParserAttachmentValue.Scalar(ParserScalar.FloatDecimal(number.floatValue()))
      case logical: java.lang.Boolean                 => ParserAttachmentValue.Scalar(ParserScalar.Logical(logical.booleanValue()))
      case character: java.lang.Character             => ParserAttachmentValue.Scalar(ParserScalar.Character(character.charValue()))
      case _ if active.nameClass.isInstance(value)    => ParserAttachmentValue.Name(value.toString)
      case _ if active.productClass.isInstance(value) =>
        ParserAttachmentValue.Product(value.asInstanceOf[ProductValue].productPrefix())
      case _                                          => ParserAttachmentValue.RuntimeKind(runtimeKind(value))

  private def runtimeKind(value: AnyRef): String =
    val simple = value.getClass.getSimpleName
    if simple.nonEmpty then simple else value.getClass.getName.split('.').lastOption.getOrElse(value.getClass.getName)

  private def declaredFieldShape(
      active: ParserRuntime,
      product: ProductValue,
      fieldIndex: Int,
      fieldName: String
  ): Option[ParserDeclaredShape] =
    val runtimeClass: Class[?] = product.getClass
    val productOwner           = runtimeClass.getMethod("productArity").getDeclaringClass
    val accessorMethods        = runtimeClass.getMethods.toVector
      .filter(method =>
        method.getName == fieldName && method.getParameterCount == 0 && !method.isBridge && !method.isSynthetic
      )
    val backingField           =
      try Some(productOwner.getDeclaredField(fieldName))
      catch case _: NoSuchFieldException => None
    val tasty                  = tastyDeclaredType(active, productOwner, product.productArity(), fieldIndex, fieldName)
    val reflected              = accessorMethods.flatMap(method => declaredShape(active, method.getGenericReturnType))
    val backing                = backingField.flatMap(field => declaredShape(active, field.getGenericType)).toVector
    val corroborating          = (reflected ++ backing).distinct
    tasty match
      case Some(declaredType) =>
        val carriers            = accessorMethods.map(_.getReturnType) ++ backingField.map(_.getType)
        val informativeCarriers = carriers.filterNot(_ == classOf[Object])
        val resolved            = declaredTypeShape(active, productOwner, declaredType, Set.empty)
          .orElse(runtimeCarrierShape(active, declaredType, carriers))
          .orElse:
            corroborating match
              case Vector(shape) => Some(shape)
              case Vector()      => None
              case conflicts     =>
                throw new IllegalStateException(
                  s"exact compiler declarations disagree for ${productOwner.getName}.$fieldName: ${conflicts.mkString(", ")}"
                )
        if resolved.isEmpty && informativeCarriers.nonEmpty && informativeCarriers.forall(
            active.productClass.isAssignableFrom
          )
        then None
        else
          val shape = resolved.getOrElse:
            throw new IllegalStateException(
              s"exact compiler TASTy field type is unsupported for ${productOwner.getName}.$fieldName: $declaredType"
            )
          if corroborating.nonEmpty && corroborating.exists(_ != shape) then
            throw new IllegalStateException(
              s"exact compiler declarations disagree for ${productOwner.getName}.$fieldName: TASTy=$shape, reflection=${corroborating.mkString(", ")}"
            )
          Some(shape)
      case None               =>
        corroborating match
          case Vector(shape) => Some(shape)
          case Vector()      => None
          case conflicts     =>
            throw new IllegalStateException(
              s"exact compiler declarations disagree for ${productOwner.getName}.$fieldName: ${conflicts.mkString(", ")}"
            )

  private def tastyDeclaredType(
      active: ParserRuntime,
      productOwner: Class[?],
      productArity: Int,
      fieldIndex: Int,
      fieldName: String
  ): Option[DeclaredType] =
    val ownerName = Option(productOwner.getEnclosingClass)
      .map(owner => owner.getSimpleName.stripSuffix("$") + "$")
      .getOrElse(productOwner.getName.split('.').last.split('$').head + "$")
    active.declaredProducts.products
      .get(DeclaredProductId(ownerName, productOwner.getSimpleName))
      .map: fields =>
        if fields.size != productArity then
          throw new IllegalStateException(
            s"exact compiler TASTy product arity disagrees for ${productOwner.getName}: expected $productArity, found ${fields.size}"
          )
        val field = fields
          .lift(fieldIndex)
          .getOrElse:
            throw new IllegalStateException(
              s"exact compiler TASTy has no field ordinal $fieldIndex for ${productOwner.getName}"
            )
        if field.name != fieldName then
          throw new IllegalStateException(
            s"exact compiler TASTy product order disagrees for ${productOwner.getName}: expected $fieldName, found ${field.name}"
          )
        field.fieldType

  private def runtimeCarrierShape(
      active: ParserRuntime,
      declaredType: DeclaredType,
      carriers: Vector[Class[?]]
  ): Option[ParserDeclaredShape] =
    val barrier = declaredType match
      case DeclaredType.Named(name) => active.declaredProducts.representationBarriers.get(name).contains(1)
      case _                        => false
    Option.when(barrier):
      val informative = carriers
        .filterNot(_ == classOf[Object])
        .map: carrier =>
          scalarShape(carrier).getOrElse:
            throw new IllegalStateException(s"exact compiler representation carrier is unsupported: ${carrier.getName}")
      informative.distinct match
        case Vector(shape) => shape
        case Vector()      => throw new IllegalStateException("exact compiler representation carrier is absent or erased")
        case conflicts     =>
          throw new IllegalStateException(
            s"exact compiler representation carriers disagree: ${conflicts.mkString(", ")}"
          )

  private def declaredTypeShape(
      active: ParserRuntime,
      productOwner: Class[?],
      declaredType: DeclaredType,
      resolvingAliases: Set[String]
  ): Option[ParserDeclaredShape] =
    declaredType match
      case DeclaredType.Named(name)                        =>
        compilerTreeShape(active, name)
          .orElse(ownerDeclaredShape(active, productOwner, name))
          .orElse(
            declaredLeafShape(active, name)
          )
          .orElse:
            aliasShape(active, productOwner, name, resolvingAliases)
      case DeclaredType.Applied("|", alternatives)         =>
        val resolved = alternatives.map(declaredTypeShape(active, productOwner, _, resolvingAliases))
        resolved.flatten.distinct match
          case Vector(shape) if resolved.nonEmpty && resolved.forall(_.contains(shape)) => Some(shape)
          case _                                                                        => None
      case DeclaredType.Applied("List", Vector(element))   =>
        declaredTypeShape(active, productOwner, element, resolvingAliases).map(ParserDeclaredShape.Repeated.apply)
      case DeclaredType.Applied("Option", Vector(element)) =>
        declaredTypeShape(active, productOwner, element, resolvingAliases).map(ParserDeclaredShape.Optional.apply)
      case DeclaredType.Applied("Lazy", Vector(value))     =>
        declaredTypeShape(active, productOwner, value, resolvingAliases)
      case DeclaredType.Applied(name, _)                   =>
        compilerTreeShape(active, name).orElse:
          aliasShape(active, productOwner, name, resolvingAliases)

  private def ownerDeclaredShape(
      active: ParserRuntime,
      productOwner: Class[?],
      simpleName: String
  ): Option[ParserDeclaredShape] =
    Option(productOwner.getEnclosingClass).flatMap: owner =>
      try
        val declaredClass = active.loader.loadClass(s"${owner.getName}$$$simpleName")
        if active.treeClass.isAssignableFrom(declaredClass) then Some(ParserDeclaredShape.Node)
        else if active.positionedClass.isAssignableFrom(declaredClass) then Some(ParserDeclaredShape.Positioned)
        else if active.nameClass.isAssignableFrom(declaredClass) then Some(ParserDeclaredShape.Name)
        else scalarShape(declaredClass)
      catch case _: ClassNotFoundException => None

  private def declaredLeafShape(active: ParserRuntime, name: String): Option[ParserDeclaredShape] =
    if declaredClassIs(active, active.nameClass, name) then Some(ParserDeclaredShape.Name)
    else if declaredClassIs(active, active.positionedClass, name) then Some(ParserDeclaredShape.Positioned)
    else
      name match
        case "String"  => Some(ParserDeclaredShape.Scalar("Text"))
        case "Int"     => Some(ParserDeclaredShape.Scalar("Integer"))
        case "Long"    => Some(ParserDeclaredShape.Scalar("LongInteger"))
        case "Double"  => Some(ParserDeclaredShape.Scalar("Decimal"))
        case "Float"   => Some(ParserDeclaredShape.Scalar("FloatDecimal"))
        case "Boolean" => Some(ParserDeclaredShape.Scalar("Logical"))
        case "Char"    => Some(ParserDeclaredShape.Scalar("Character"))
        case _         => None

  private def declaredClassIs(active: ParserRuntime, expectedBase: Class[?], simpleName: String): Boolean =
    if simpleName == expectedBase.getSimpleName then true
    else
      val binaryName = expectedBase.getName
      val ownerEnd   = binaryName.lastIndexOf('$')
      if ownerEnd < 0 then false
      else
        try expectedBase.isAssignableFrom(active.loader.loadClass(s"${binaryName.take(ownerEnd + 1)}$simpleName"))
        catch case _: ClassNotFoundException => false

  private def aliasShape(
      active: ParserRuntime,
      productOwner: Class[?],
      name: String,
      resolvingAliases: Set[String]
  ): Option[ParserDeclaredShape] =
    Option
      .when(!resolvingAliases(name))(active.declaredProducts.aliases.getOrElse(name, Vector.empty))
      .flatMap: aliases =>
        aliases.flatMap(declaredTypeShape(active, productOwner, _, resolvingAliases + name)).distinct match
          case Vector(shape) => Some(shape)
          case _             => None

  private def compilerTreeShape(active: ParserRuntime, simpleName: String): Option[ParserDeclaredShape] =
    try
      val declaredClass = active.loader.loadClass(s"dotty.tools.dotc.ast.Trees$$$simpleName")
      Option.when(active.treeClass.isAssignableFrom(declaredClass))(ParserDeclaredShape.Node)
    catch case _: ClassNotFoundException => None

  private def declaredShape(active: ParserRuntime, fieldType: Type): Option[ParserDeclaredShape] = fieldType match
    case cls: Class[?]                    =>
      if active.treeClass.isAssignableFrom(cls) then Some(ParserDeclaredShape.Node)
      else if active.positionedClass.isAssignableFrom(cls) then Some(ParserDeclaredShape.Positioned)
      else if active.nameClass.isAssignableFrom(cls) then Some(ParserDeclaredShape.Name)
      else scalarShape(cls)
    case parameterized: ParameterizedType =>
      parameterized.getRawType match
        case raw: Class[?] if active.treeClass.isAssignableFrom(raw)           => Some(ParserDeclaredShape.Node)
        case raw: Class[?] if active.positionedClass.isAssignableFrom(raw)     => Some(ParserDeclaredShape.Positioned)
        case raw: Class[?] if active.nameClass.isAssignableFrom(raw)           => Some(ParserDeclaredShape.Name)
        case raw: Class[?] if parameterized.getActualTypeArguments.length == 1 =>
          declaredShape(active, parameterized.getActualTypeArguments.head).flatMap: inner =>
            if active.optionClass.isAssignableFrom(raw) then Some(ParserDeclaredShape.Optional(inner))
            else if active.iterableClass.isAssignableFrom(raw) then Some(ParserDeclaredShape.Repeated(inner))
            else None
        case _                                                                 => None
    case variable: TypeVariable[?]        => unambiguousBoundShape(active, variable.getBounds)
    case wildcard: WildcardType           => unambiguousBoundShape(active, wildcard.getUpperBounds)
    case _                                => None

  private def unambiguousBoundShape(active: ParserRuntime, bounds: Array[Type]): Option[ParserDeclaredShape] =
    bounds.flatMap(declaredShape(active, _)).distinct.toVector match
      case Vector(shape) => Some(shape)
      case _             => None

  private def scalarShape(cls: Class[?]): Option[ParserDeclaredShape] =
    val kind =
      if cls == classOf[String] then Some("Text")
      else if cls == java.lang.Integer.TYPE || cls == classOf[java.lang.Integer] then Some("Integer")
      else if cls == java.lang.Long.TYPE || cls == classOf[java.lang.Long] then Some("LongInteger")
      else if cls == java.lang.Double.TYPE || cls == classOf[java.lang.Double] then Some("Decimal")
      else if cls == java.lang.Float.TYPE || cls == classOf[java.lang.Float] then Some("FloatDecimal")
      else if cls == java.lang.Boolean.TYPE || cls == classOf[java.lang.Boolean] then Some("Logical")
      else if cls == java.lang.Character.TYPE || cls == classOf[java.lang.Character] then Some("Character")
      else if cls == java.lang.Void.TYPE || cls == classOf[scala.runtime.BoxedUnit] then Some("UnitValue")
      else None
    kind.map(ParserDeclaredShape.Scalar.apply)

  private def parserName(
      active: ParserRuntime,
      value: AnyRef,
      generated: HashMap[String, java.lang.Integer]
  ): ParserFieldValue =
    val methods           = value.getClass.getMethods.iterator.map(_.getName).toSet
    val normalized        =
      if methods("toTermName") && !methods("info") then value.asInstanceOf[TypeNameValue].toTermName()
      else value
    val normalizedMethods = normalized.getClass.getMethods.iterator.map(_.getName).toSet
    if !normalizedMethods("info") || !normalizedMethods("underlying") then ParserFieldValue.Name(value.toString)
    else
      val name = normalized.asInstanceOf[NameValue]
      val kind = name.info().asInstanceOf[NameInfoValue].kind()
      if !active.uniqueNameKindClass.isInstance(kind) then ParserFieldValue.Name(value.toString)
      else
        val runtimeName                = normalized.toString
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
    while iterator.hasNext() do
      cancellation.checkCanceled()
      result += iterator.next()
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

  private def endMarker(
      active: ParserRuntime,
      tree: AnyRef,
      ownerNodeId: Long,
      context: AnyRef,
      source: String
  ): Option[ParserEndMarker] =
    active.endMarkerReader.toOption.flatMap: reader =>
      if !reader.ownerClass.isInstance(tree) then None
      else if !reader.hasMarker.invoke(tree).asInstanceOf[java.lang.Boolean].booleanValue() then None
      else
        val span = reader.span.invoke(tree, context).asInstanceOf[java.lang.Long].longValue()
        spanPosition(active, span) match
          case ParserNodePosition.Positioned(range, _, ParserPositionProvenance.SourceDerived)
              if range.startOffset >= 0 && range.startOffset < range.endOffset && range.endOffset <= source.length =>
            Some(ParserEndMarker(ownerNodeId, range))
          case ParserNodePosition.Absent => None
          case position                  =>
            throw new IllegalStateException(s"compiler end marker has no valid source-derived span: $position")

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
        diagnosticPosition(active, diagnostic.position())
      )

  private def diagnosticSeverity(level: Int): ParserDiagnosticSeverity =
    level match
      case 2 => ParserDiagnosticSeverity.Error
      case 1 => ParserDiagnosticSeverity.Warning
      case _ => ParserDiagnosticSeverity.Information

  private def diagnosticPosition(
      active: ParserRuntime,
      position: java.util.Optional[?]
  ): Option[ParserDiagnosticPosition] =
    if position.isEmpty then None
    else
      val value = position.get().asInstanceOf[DiagnosticPositionValue]
      active.diagnosticPositionProvenance
        .fold(reason => throw new IllegalStateException(reason), _.read(value.asInstanceOf[AnyRef]))
        .map(provenance =>
          ParserDiagnosticPosition(
            PcSourceRange(value.start(), value.end()),
            value.point(),
            provenance
          )
        )

  private def withCompilerClassloader[A](active: ParserRuntime)(body: => A): A =
    val thread   = Thread.currentThread
    val previous = thread.getContextClassLoader
    thread.setContextClassLoader(active.loader)
    try body
    finally thread.setContextClassLoader(previous)

  private def errorMessage(error: Throwable): String =
    val cause = Option(error.getCause).getOrElse(error)
    Option(cause.getMessage).filter(_.nonEmpty).getOrElse(cause.getClass.getName)

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
      positioned: Vector[ParserPositionedSyntax],
      endMarkers: Vector[ParserEndMarker],
      runtimeSupplements: Vector[ParserRuntimeSupplement],
      attachments: Vector[ParserTreeAttachment]
  )
