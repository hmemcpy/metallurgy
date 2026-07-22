package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.progress.{ProcessCanceledException, ProgressManager}
import com.intellij.openapi.util.TextRange

import java.io.File
import java.net.URI
import java.util
import java.util.concurrent.atomic.AtomicReference
import java.util.IdentityHashMap
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Session-owned bridge to the exact-version Scala compiler's typed trees.
  *
  * The bundled `PresentationCompiler` API deliberately renders ordinary hover signatures, which widens the result of
  * transparent inline expansion. Metallurgy needs the `Inlined` tree's type for the Scala plugin's `CompilerType` slot.
  * All reflected values stay behind this boundary so child-classloader Scala values never escape into the plugin.
  */
private[pc] final class PcInlineTypeDriver(
    classloader: ClassLoader,
    compilerClasspath: Seq[File],
    compilerOptions: Seq[String]
):

  private val driverClass   = Class.forName("dotty.tools.dotc.interactive.InteractiveDriver", true, classloader)
  private val driver        = driverClass
    .getConstructor(Class.forName("scala.collection.immutable.List", true, classloader))
    .newInstance(compilerSettings())
  private val typedDocument = new AtomicReference[Option[TypedDocument]](None)

  /** `InteractiveDriver` mutates its compilation state on `run`, so the session admits one writer at a time. */
  def typeAt(snapshot: PcSnapshot, range: TextRange): Option[String] = synchronized:
    require(
      typedDocument.get().contains(TypedDocument(snapshot.fileUri, snapshot.documentVersion)),
      "inline type queries require a matching typed document"
    )
    val uri        = PcSourceUri.normalize(snapshot.fileUri)
    val context    = driverClass.getMethod("currentCtx").invoke(driver)
    val sourceFile = mapValue(driverClass.getMethod("openedFiles").invoke(driver), uri)
    val trees      = mapValue(driverClass.getMethod("openedTrees").invoke(driver), uri)
    val span       = querySpan(range)
    val position   = sourceFile.getClass.getMethod("atSpan", java.lang.Long.TYPE).invoke(sourceFile, span)

    renderType(pathTo(trees, position, context), context)

  def diagnostics(snapshot: PcSnapshot): Seq[PcDiagnostic] = synchronized:
    require(
      typedDocument.get().contains(TypedDocument(snapshot.fileUri, snapshot.documentVersion)),
      "diagnostic queries require a matching typed document"
    )
    val context  = driverClass.getMethod("currentCtx").invoke(driver)
    val reporter = context.getClass.getMethod("reporter").invoke(context)
    val errors   = diagnosticList(reporter.getClass.getMethod("allErrors").invoke(reporter), isError = true)
    val warnings = diagnosticList(reporter.getClass.getMethod("allWarnings").invoke(reporter), isError = false)
    errors ++ warnings

  def typedTreeSnapshot(
      snapshot: PcSnapshot,
      currency: () => PcSnapshotCurrency
  ): PcTypedTreeExtraction = synchronized:
    require(
      typedDocument.get().contains(TypedDocument(snapshot.fileUri, snapshot.documentVersion)),
      "typed-tree extraction requires a matching typed document"
    )
    val startedAt          = System.nanoTime()
    val context            = driverClass.getMethod("currentCtx").invoke(driver)
    val uri                = PcSourceUri.normalize(snapshot.fileUri)
    val unit               = mapValue(driverClass.getMethod("compilationUnits").invoke(driver), uri)
    val root               = unit.getClass.getMethod("tpdTree").invoke(unit)
    val traversalStartedAt = System.nanoTime()
    collectTrees(root, currency) match
      case None            => PcTypedTreeExtraction.Superseded
      case Some(traversal) =>
        collectCandidates(traversal.trees, snapshot, context, currency) match
          case None             => PcTypedTreeExtraction.Superseded
          case Some(candidates) =>
            val retained           = retainCanonicalCandidates(candidates)
            val traversalDuration  = (System.nanoTime() - traversalStartedAt).nanos
            val renderingStartedAt = System.nanoTime()
            renderCandidates(retained, context, currency) match
              case None          => PcTypedTreeExtraction.Superseded
              case Some(entries) =>
                val renderingDuration = (System.nanoTime() - renderingStartedAt).nanos
                if !isCurrent(currency) then PcTypedTreeExtraction.Superseded
                else
                  PcTypedTreeExtraction.Completed(
                    buildTypedTreeSnapshot(
                      snapshot,
                      entries,
                      traversal,
                      candidates,
                      retained.size,
                      startedAt,
                      traversalDuration,
                      renderingDuration
                    )
                  )

  def structuralCompletions(snapshot: PcSnapshot, offset: Int): Seq[PcCompletion] = synchronized:
    require(
      typedDocument.get().contains(TypedDocument(snapshot.fileUri, snapshot.documentVersion)),
      "structural completion queries require a matching typed document"
    )
    val dotOffset = snapshot.sourceText.lastIndexOf('.', math.max(0, offset - 1))
    if dotOffset <= 0 then Seq.empty
    else
      val uri        = PcSourceUri.normalize(snapshot.fileUri)
      val context    = driverClass.getMethod("currentCtx").invoke(driver)
      val sourceFile = mapValue(driverClass.getMethod("openedFiles").invoke(driver), uri)
      val trees      = mapValue(driverClass.getMethod("openedTrees").invoke(driver), uri)
      val span       = querySpan(TextRange.from(dotOffset - 1, 1))
      val position   = sourceFile.getClass.getMethod("atSpan", java.lang.Long.TYPE).invoke(sourceFile, span)
      val path       = pathTrees(pathTo(trees, position, context))
      selectTree(path).toSeq.flatMap: selection =>
        val tpe = treeType(selection, context)
        refinementCompletions(tpe, context)

  private def querySpan(range: TextRange): AnyRef =
    if range.isEmpty then
      spansModule.getClass.getMethod("Span", classOf[Int]).invoke(spansModule, Int.box(range.getStartOffset))
    else
      spansModule.getClass
        .getMethod("Span", classOf[Int], classOf[Int])
        .invoke(spansModule, Int.box(range.getStartOffset), Int.box(range.getEndOffset))

  def retypecheck(snapshot: PcSnapshot): Unit = synchronized:
    ensureTyped(snapshot)

  def close(): Unit = synchronized:
    typedDocument.getAndSet(None).foreach(closeDocument)

  private def ensureTyped(snapshot: PcSnapshot): Unit =
    if !typedDocument.get().contains(TypedDocument(snapshot.fileUri, snapshot.documentVersion)) then run(snapshot)

  private def run(snapshot: PcSnapshot): Unit =
    ProgressManager.checkCanceled()
    typedDocument.get().filterNot(_.fileUri == snapshot.fileUri).foreach(closeDocument)
    driverClass
      .getMethod("run", classOf[URI], classOf[String])
      .invoke(driver, PcSourceUri.normalize(snapshot.fileUri), snapshot.sourceText)
    ProgressManager.checkCanceled()
    typedDocument.set(Some(TypedDocument(snapshot.fileUri, snapshot.documentVersion)))

  private def closeDocument(document: TypedDocument): Unit =
    val _ = driverClass.getMethod("close", classOf[URI]).invoke(driver, PcSourceUri.normalize(document.fileUri))

  private def compilerSettings(): AnyRef =
    val options     = Seq(
      "-classpath",
      compilerClasspath.map(_.getAbsolutePath).mkString(File.pathSeparator)
    ) ++ compilerOptions
    val converters  = Class.forName("scala.jdk.javaapi.CollectionConverters", true, classloader)
    val scalaBuffer = converters.getMethod("asScala", classOf[util.List[?]]).invoke(null, options.asJava)
    scalaBuffer.getClass.getMethod("toList").invoke(scalaBuffer)

  private def mapValue(scalaMap: AnyRef, key: AnyRef): AnyRef =
    scalaMap.getClass.getMethod("apply", classOf[Object]).invoke(scalaMap, key)

  private def collectTrees(root: AnyRef, currency: () => PcSnapshotCurrency): Option[TreeTraversal] =
    val treeClass  = Class.forName("dotty.tools.dotc.ast.Trees$Tree", true, classloader)
    val seen       = new IdentityHashMap[AnyRef, java.lang.Boolean]()
    val collected  = Vector.newBuilder[AnyRef]
    var visited    = 0
    var positioned = 0
    var current    = true

    def visit(value: AnyRef): Unit =
      if current && visited % 32 == 0 then current = isCurrent(currency)
      if current && treeClass.isInstance(value) && seen.put(value, java.lang.Boolean.TRUE) == null then
        visited += 1
        if isPositionedTypedTree(value) then positioned += 1
        collected += value
        productValues(value).takeWhile(_ => current).foreach(visit)
      else if current && isScalaIterable(value) then iteratorValues(value).takeWhile(_ => current).foreach(visit)

    visit(root)
    Option.when(current && isCurrent(currency))(TreeTraversal(collected.result(), visited, positioned))

  private def collectCandidates(
      trees: Vector[AnyRef],
      snapshot: PcSnapshot,
      context: AnyRef,
      currency: () => PcSnapshotCurrency
  ): Option[Vector[ReflectedTreeCandidate]] =
    val candidates = Vector.newBuilder[ReflectedTreeCandidate]
    val iterator   = trees.iterator.zipWithIndex
    var current    = true
    while iterator.hasNext && current do
      val (tree, index) = iterator.next()
      if index % 32 == 0 then current = isCurrent(currency)
      if current then candidates ++= treeCandidates(tree, snapshot, context)
    Option.when(current && isCurrent(currency))(candidates.result())

  private def renderCandidates(
      candidates: Vector[ReflectedTreeCandidate],
      context: AnyRef,
      currency: () => PcSnapshotCurrency
  ): Option[Vector[PcTypedTreeEntry]] =
    val entries  = Vector.newBuilder[PcTypedTreeEntry]
    val iterator = candidates.iterator
    var current  = true
    while iterator.hasNext && current do
      current = isCurrent(currency)
      if current then renderCandidate(iterator.next(), context).foreach(entries += _)
    Option.when(current && isCurrent(currency))(entries.result())

  private def retainCanonicalCandidates(
      candidates: Vector[ReflectedTreeCandidate]
  ): Vector[ReflectedTreeCandidate] =
    candidates
      .groupBy(candidate => (candidate.range, candidate.role, candidate.symbol.map(_.id)))
      .valuesIterator
      .map(_.minBy(candidate => (candidate.rank, -candidate.treeSize, candidate.tree.getClass.getName)))
      .toVector
      .sortBy(candidate =>
        (candidate.range.startOffset, candidate.range.endOffset, candidate.role.ordinal, candidate.rank)
      )

  private def buildTypedTreeSnapshot(
      snapshot: PcSnapshot,
      entries: Vector[PcTypedTreeEntry],
      traversal: TreeTraversal,
      candidates: Vector[ReflectedTreeCandidate],
      retainedCount: Int,
      startedAt: Long,
      traversalDuration: FiniteDuration,
      renderingDuration: FiniteDuration
  ): PcTypedTreeSnapshot =
    PcTypedTreeSnapshot(
      snapshot.fileUri,
      snapshot.documentVersion,
      entries,
      PcTypedTreeMetrics(
        extractionDuration = (System.nanoTime() - startedAt).nanos,
        traversalDuration = traversalDuration,
        renderingDuration = renderingDuration,
        visitedTreeCount = traversal.visited,
        positionedTreeCount = traversal.positioned,
        candidateCount = candidates.size,
        retainedEntryCount = entries.size,
        deduplicatedCandidateCount = candidates.size - retainedCount,
        compilerWrapperOverlapCount = compilerWrapperOverlapCount(candidates),
        renderedTypeCount = entries.size
      )
    )

  private def productValues(value: AnyRef): Iterator[AnyRef] =
    Try(value.getClass.getMethod("productIterator").invoke(value)).toOption.iterator.flatMap(iteratorValues)

  private def isScalaIterable(value: AnyRef): Boolean =
    value.getClass.getMethods.exists(method => method.getName == "iterator" && method.getParameterCount == 0)

  private def iteratorValues(value: AnyRef): Iterator[AnyRef] =
    val iterator = value.getClass.getMethod("iterator").invoke(value)
    val hasNext  = iterator.getClass.getMethod("hasNext")
    val next     = iterator.getClass.getMethod("next")
    Iterator
      .continually(iterator)
      .takeWhile(current => hasNext.invoke(current).asInstanceOf[Boolean])
      .flatMap(current => Option(next.invoke(current).asInstanceOf[AnyRef]))

  private def isPositionedTypedTree(tree: AnyRef): Boolean =
    val hasType = tree.getClass.getMethod("hasType").invoke(tree).asInstanceOf[Boolean]
    val span    = tree.getClass.getMethod("span").invoke(tree)
    hasType && spanExists(span)

  private def treeCandidates(tree: AnyRef, snapshot: PcSnapshot, context: AnyRef): Vector[ReflectedTreeCandidate] =
    val hasType = tree.getClass.getMethod("hasType").invoke(tree).asInstanceOf[Boolean]
    val span    = tree.getClass.getMethod("span").invoke(tree)
    Option
      .when(hasType && spanExists(span))(span)
      .toVector
      .flatMap: existingSpan =>
        val start = spanStart(existingSpan)
        val end   = spanEnd(existingSpan)
        Option
          .when(start >= 0 && end > start && end <= snapshot.sourceText.length)(())
          .toVector
          .flatMap: _ =>
            val symbol    = Try(invokeContextual(tree, "symbol", context)).toOption
            val details   = symbol.flatMap(symbolDetails(_, snapshot.fileUri, snapshot.sourceText.length, context))
            val parameter = symbol.exists(symbolHasFlag(_, "Param", context))
            treeRoles(tree, parameter, details.nonEmpty, snapshot.sourceText, start).map: role =>
              ReflectedTreeCandidate(
                tree,
                PcSourceRange(start, end),
                role,
                treeRank(tree, role),
                tree.getClass.getMethod("treeSize").invoke(tree).asInstanceOf[Int],
                details
              )

  /** A compiler wrapper may represent several IntelliJ semantic questions at the same source range. Expression roles
    * are therefore retained independently, while definition and pattern roles require a compiler symbol that the PSI
    * mapper can use to identify the individual declaration.
    */
  private def treeRoles(
      tree: AnyRef,
      parameter: Boolean,
      symbolAvailable: Boolean,
      sourceText: String,
      startOffset: Int
  ): Vector[PcTypedTreeRole] =
    val kind       = TypedTreeKind.from(tree)
    val isTermTree = tree.getClass.getMethod("isTerm").invoke(tree).asInstanceOf[Boolean]
    val isTypeTree = tree.getClass.getMethod("isType").invoke(tree).asInstanceOf[Boolean]
    val isPattern  = tree.getClass.getMethod("isPattern").invoke(tree).asInstanceOf[Boolean]
    Vector(
      Option.when(isTermTree)(PcTypedTreeRole.ExpressionExact),
      Option.when(isTermTree)(PcTypedTreeRole.ExpressionWidened),
      Option.when(isTypeTree && startsAfterTypeAscription(sourceText, startOffset))(PcTypedTreeRole.Declared),
      Option.when(kind == TypedTreeKind.ValDef && !parameter && symbolAvailable)(PcTypedTreeRole.Inferred),
      Option.when(kind == TypedTreeKind.ValDef && parameter && symbolAvailable)(PcTypedTreeRole.Parameter),
      Option.when(kind == TypedTreeKind.DefDef && symbolAvailable)(PcTypedTreeRole.Function),
      Option.when((kind == TypedTreeKind.Bind || isPattern) && symbolAvailable)(PcTypedTreeRole.Pattern)
    ).flatten.distinct

  private def startsAfterTypeAscription(sourceText: String, startOffset: Int): Boolean =
    sourceText.substring(0, startOffset).reverseIterator.dropWhile(_.isWhitespace).nextOption.contains(':')

  private def compilerWrapperOverlapCount(candidates: Vector[ReflectedTreeCandidate]): Int =
    candidates
      .groupBy(_.range.startOffset)
      .valuesIterator
      .count: sameKey =>
        val kinds = sameKey.iterator.map(candidate => TypedTreeKind.from(candidate.tree)).toSet
        Set(TypedTreeKind.Inlined, TypedTreeKind.Apply, TypedTreeKind.TypeApply).count(kinds.contains) >= 2

  private def treeRank(tree: AnyRef, role: PcTypedTreeRole): Int =
    val kind = TypedTreeKind.from(tree)
    role match
      case PcTypedTreeRole.ExpressionExact   =>
        kind match
          case TypedTreeKind.Inlined   => 0
          case TypedTreeKind.Apply     => 1
          case TypedTreeKind.TypeApply => 2
          case TypedTreeKind.Select    => 3
          case _                       => 4
      case PcTypedTreeRole.ExpressionWidened =>
        kind match
          case TypedTreeKind.Apply     => 0
          case TypedTreeKind.TypeApply => 1
          case TypedTreeKind.Select    => 2
          case TypedTreeKind.Inlined   => 3
          case _                       => 4
      case PcTypedTreeRole.Declared          => if kind == TypedTreeKind.Typed then 0 else 1
      case PcTypedTreeRole.Inferred          => 0
      case PcTypedTreeRole.Parameter         => 0
      case PcTypedTreeRole.Function          => 0
      case PcTypedTreeRole.Pattern           => if kind == TypedTreeKind.Bind then 0 else 1

  private def symbolDetails(
      symbol: AnyRef,
      fileUri: String,
      sourceLength: Int,
      context: AnyRef
  ): Option[PcCompilerSymbol] =
    Try:
      val id      = stableSymbolId(symbol, context)
      val denot   = invokeContextual(symbol, "denot", context)
      val flags   = symbolFlags(symbol, context)
      val owner   = denot.getClass.getMethod("owner").invoke(denot)
      val ownerId = Try(stableSymbolId(owner, context)).toOption
      val span    = symbol.getClass.getMethod("span").invoke(symbol)
      val nav     = for
        navigationUri <- symbolNavigationUri(symbol, fileUri, context)
        existingSpan  <- Option.when(spanExists(span))(span)
        target        <-
          val start               = spanStart(existingSpan)
          val end                 = spanEnd(existingSpan)
          val withinCurrentSource = navigationUri != fileUri || end <= sourceLength
          Option.when(start >= 0 && end >= start && withinCurrentSource):
            PcNavigationTarget(navigationUri, PcSourceRange(start, end))
      yield target
      PcCompilerSymbol(id, flags, ownerId, nav)
    .toOption

  private def symbolNavigationUri(symbol: AnyRef, fileUri: String, context: AnyRef): Option[String] =
    Try:
      val source      = invokeContextual(symbol, "source", context)
      val sourcePath  = source.getClass.getMethod("path").invoke(source).toString
      val normalized  = PcSourceUri.normalize(fileUri)
      val currentPath = Option(normalized.getPath).getOrElse("")
      val currentName = new File(currentPath).getName
      val isCurrent   = Set(fileUri, normalized.toString, currentPath, currentPath.stripPrefix("/"), currentName)
        .contains(sourcePath)
      if isCurrent then Some(fileUri)
      else if sourcePath.startsWith("file:") then Some(URI.create(sourcePath).toString)
      else Option.when(new File(sourcePath).isAbsolute)(new File(sourcePath).toURI.toString)
    .toOption.flatten

  private def stableSymbolId(symbol: AnyRef, context: AnyRef): String =
    val fullName  = invokeContextual(symbol, "showFullName", context).toString
    val signature = Try(invokeContextual(symbol, "signature", context).toString).filter(_ != "NotAMethod").toOption
    val span      = symbol.getClass.getMethod("span").invoke(symbol)
    val location  = Option.when(spanExists(span))(s"${spanStart(span)}:${spanEnd(span)}")
    s"$fullName${signature.fold("")(value => s"$value")}${location.fold("")(value => s"@$value")}"

  private def symbolFlags(symbol: AnyRef, context: AnyRef): Set[String] =
    Try:
      val denotation = invokeContextual(symbol, "denot", context)
      invokeContextual(denotation, "flagsString", context).toString.split("[\\s,]+").filter(_.nonEmpty).toSet
    .getOrElse(Set.empty)

  private def symbolHasFlag(symbol: AnyRef, flagName: String, context: AnyRef): Boolean =
    Try:
      val flagsModule = Class.forName("dotty.tools.dotc.core.Flags$", true, classloader).getField("MODULE$").get(null)
      val flag        = flagsModule.getClass.getMethod(flagName).invoke(flagsModule)
      val denotation  = invokeContextual(symbol, "denot", context)
      denotation.getClass.getMethods
        .find(method => method.getName == "is" && method.getParameterCount == 2)
        .getOrElse(throw new NoSuchMethodException("SymDenotation.is"))
        .invoke(denotation, flag, context)
        .asInstanceOf[Boolean]
    .getOrElse(false)

  private def renderCandidate(
      candidate: ReflectedTreeCandidate,
      context: AnyRef
  ): Option[PcTypedTreeEntry] =
    try
      val rawType   = candidate.tree.getClass.getMethod("tpe").invoke(candidate.tree)
      val rendering = candidate.role match
        case PcTypedTreeRole.ExpressionWidened | PcTypedTreeRole.Inferred => TypeRendering.Widened
        case _                                                            => TypeRendering.Exact
      val rendered  = renderCompilerType(normalizedType(rawType, rendering, context), context)
      Option.when(rendered.nonEmpty && rendered != "<empty>" && !rendered.contains("<error")):
        PcTypedTreeEntry(
          candidate.range,
          candidate.role,
          rendered,
          candidate.symbol
        )
    catch
      case canceled: ProcessCanceledException => throw canceled
      case NonFatal(_) => None

  private def isCurrent(currency: () => PcSnapshotCurrency): Boolean =
    ProgressManager.checkCanceled()
    currency() == PcSnapshotCurrency.Current

  private def spansModule: AnyRef =
    Class.forName("dotty.tools.dotc.util.Spans$", true, classloader).getField("MODULE$").get(null)

  private def spanStart(span: AnyRef): Int = spanExtension("start$extension", span)

  private def spanEnd(span: AnyRef): Int = spanExtension("end$extension", span)

  private def spanExists(span: AnyRef): Boolean =
    val module = Class.forName("dotty.tools.dotc.util.Spans$Span$", true, classloader).getField("MODULE$").get(null)
    module.getClass.getMethod("exists$extension", java.lang.Long.TYPE).invoke(module, span).asInstanceOf[Boolean]

  private def hasSameExtent(left: AnyRef, right: AnyRef): Boolean =
    val leftSpan  = left.getClass.getMethod("span").invoke(left)
    val rightSpan = right.getClass.getMethod("span").invoke(right)
    spanStart(leftSpan) == spanStart(rightSpan) && spanEnd(leftSpan) == spanEnd(rightSpan)

  private def spanExtension(methodName: String, span: AnyRef): Int =
    val module = Class.forName("dotty.tools.dotc.util.Spans$Span$", true, classloader).getField("MODULE$").get(null)
    module.getClass.getMethod(methodName, java.lang.Long.TYPE).invoke(module, span).asInstanceOf[Int]

  private def pathTo(trees: AnyRef, position: AnyRef, context: AnyRef): AnyRef =
    val interactive = Class
      .forName("dotty.tools.dotc.interactive.Interactive$", true, classloader)
      .getField("MODULE$")
      .get(null)
    val arguments   = Array(trees, position, context)
    interactive.getClass.getMethods
      .find: method =>
        method.getName == "pathTo" &&
          method.getParameterTypes.zip(arguments).forall((parameter, argument) => parameter.isInstance(argument))
      .getOrElse(throw new NoSuchMethodException("Interactive.pathTo"))
      .invoke(interactive, arguments*)

  private def renderType(path: AnyRef, context: AnyRef): Option[String] =
    selectTree(pathTrees(path)).map(selection => renderTreeType(selection, context))

  private def selectTree(trees: List[AnyRef]): Option[TypedTreeSelection] =
    trees.headOption.map: closest =>
      sameExtentTree(trees, closest, TypedTreeKind.Inlined)
        .map(TypedTreeSelection(_, TypeRendering.Exact))
        .orElse:
          sameExtentTree(trees, closest, TypedTreeKind.TypeApply)
            .filter(isRuntimeTypeCast)
            .map(TypedTreeSelection(_, TypeRendering.Widened))
        .orElse:
          sameExtentTree(trees, closest, TypedTreeKind.Apply)
            .map(TypedTreeSelection(_, TypeRendering.Widened))
        .orElse:
          sameExtentTree(trees, closest, TypedTreeKind.TypeApply)
            .map(TypedTreeSelection(_, TypeRendering.Widened))
        .getOrElse(TypedTreeSelection(closest, TypeRendering.Widened))

  private def sameExtentTree(trees: List[AnyRef], closest: AnyRef, kind: TypedTreeKind): Option[AnyRef] =
    trees.reverse.find(tree => kind.matches(tree) && hasSameExtent(tree, closest))

  private def isRuntimeTypeCast(tree: AnyRef): Boolean =
    val function = tree.getClass.getMethod("fun").invoke(tree)
    Option(function.getClass.getMethod("name").invoke(function))
      .exists(_.toString.contains("asInstanceOf"))

  private def pathTrees(path: AnyRef): List[AnyRef] =
    iteratorValues(path).toList

  private def diagnosticList(diagnostics: AnyRef, isError: Boolean): List[PcDiagnostic] =
    pathTrees(diagnostics).flatMap: diagnostic =>
      val position = diagnostic.getClass.getMethod("position").invoke(diagnostic)
      val present  = position.getClass.getMethod("isPresent").invoke(position).asInstanceOf[Boolean]
      Option.when(present):
        val sourcePosition = position.getClass.getMethod("get").invoke(position)
        val start          = sourcePosition.getClass.getMethod("start").invoke(sourcePosition).asInstanceOf[Int]
        val end            = sourcePosition.getClass.getMethod("end").invoke(sourcePosition).asInstanceOf[Int]
        val message        = diagnostic.getClass.getMethod("message").invoke(diagnostic).toString
        PcDiagnostic(TextRange(math.max(0, start), math.max(start, end)), isError, message)

  private def renderTreeType(selection: TypedTreeSelection, context: AnyRef): String =
    renderCompilerType(treeType(selection, context), context)

  private def treeType(selection: TypedTreeSelection, context: AnyRef): AnyRef =
    val rawType = selection.tree.getClass.getMethod("tpe").invoke(selection.tree)
    normalizedType(rawType, selection.rendering, context)

  private def normalizedType(rawType: AnyRef, rendering: TypeRendering, context: AnyRef): AnyRef =
    val base       = rendering match
      case TypeRendering.Exact   => rawType
      case TypeRendering.Widened => invokeContextual(rawType, "widenTermRefExpr", context)
    val dealiased  = invokeContextual(base, "dealias", context)
    val normalized = invokeContextual(dealiased, "normalized", context)
    invokeContextual(normalized, "simplified", context)

  private def refinementCompletions(tpe: AnyRef, context: AnyRef): List[PcCompletion] =
    Iterator
      .iterate(Option(tpe)):
        case Some(refined) if isRefinedType(refined) =>
          Option(refined.getClass.getMethod("parent").invoke(refined))
        case _                                       => None
      .takeWhile(_.nonEmpty)
      .flatMap: candidate =>
        candidate
          .filter(isRefinedType)
          .flatMap: refined =>
            val name = refined.getClass.getMethod("refinedName").invoke(refined)
            val term = name.getClass.getMethod("isTermName").invoke(name).asInstanceOf[Boolean]
            Option.when(term):
              val lookup = escapedIdentifier(name.toString)
              val info   = refined.getClass.getMethod("refinedInfo").invoke(refined)
              val detail = renderCompilerType(info, context)
              PcCompletion(lookup, s"$lookup: $detail", Some(detail))
      .toList
      .distinctBy(_.lookupName)

  private def isRefinedType(tpe: AnyRef): Boolean =
    val methods = tpe.getClass.getMethods.iterator.map(_.getName).toSet
    Set("parent", "refinedName", "refinedInfo").subsetOf(methods)

  private def escapedIdentifier(name: String): String =
    if name.matches("[A-Za-z_$][A-Za-z0-9_$]*") then name else s"`$name`"

  private def renderCompilerType(tpe: AnyRef, context: AnyRef): String =
    tpe.getClass.getMethods
      .find(method => method.getName == "show" && method.getParameterCount == 1)
      .getOrElse(throw new NoSuchMethodException("Type.show"))
      .invoke(tpe, context)
      .toString
      .replaceAll("\\u001B\\[[;\\d]*m", "")

  private def invokeContextual(receiver: AnyRef, methodName: String, context: AnyRef): AnyRef =
    receiver.getClass.getMethods
      .find: method =>
        method.getName == methodName &&
          method.getParameterCount == 1 &&
          method.getParameterTypes.head.isInstance(context)
      .getOrElse(throw new NoSuchMethodException(s"Type.$methodName"))
      .invoke(receiver, context)

private final case class TypedDocument(fileUri: String, documentVersion: Long)

private final case class TypedTreeSelection(tree: AnyRef, rendering: TypeRendering)

private enum TypeRendering:
  case Exact
  case Widened

private enum TypedTreeKind(simpleName: String):
  case Inlined   extends TypedTreeKind("Inlined")
  case Apply     extends TypedTreeKind("Apply")
  case TypeApply extends TypedTreeKind("TypeApply")
  case Select    extends TypedTreeKind("Select")
  case Typed     extends TypedTreeKind("Typed")
  case ValDef    extends TypedTreeKind("ValDef")
  case DefDef    extends TypedTreeKind("DefDef")
  case Bind      extends TypedTreeKind("Bind")
  case Other     extends TypedTreeKind("")

  def matches(tree: AnyRef): Boolean = tree.getClass.getSimpleName == simpleName

private object TypedTreeKind:
  def from(tree: AnyRef): TypedTreeKind =
    TypedTreeKind.values.find(_.matches(tree)).getOrElse(TypedTreeKind.Other)

private[pc] final case class TreeTraversal(trees: Vector[AnyRef], visited: Int, positioned: Int)

private[pc] final case class ReflectedTreeCandidate(
    tree: AnyRef,
    range: PcSourceRange,
    role: PcTypedTreeRole,
    rank: Int,
    treeSize: Int,
    symbol: Option[PcCompilerSymbol]
)
