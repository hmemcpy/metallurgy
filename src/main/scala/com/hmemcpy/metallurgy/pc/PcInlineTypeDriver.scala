package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange

import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

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
    val uri        = compilerUri(snapshot.fileUri)
    val context    = driverClass.getMethod("currentCtx").invoke(driver)
    val sourceFile = mapValue(driverClass.getMethod("openedFiles").invoke(driver), uri)
    val trees      = mapValue(driverClass.getMethod("openedTrees").invoke(driver), uri)
    val span       = querySpan(range)
    val position   = sourceFile.getClass.getMethod("atSpan", java.lang.Long.TYPE).invoke(sourceFile, span)

    renderType(pathTo(trees, position, context), context)

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
      .invoke(driver, compilerUri(snapshot.fileUri), snapshot.sourceText)
    ProgressManager.checkCanceled()
    typedDocument.set(Some(TypedDocument(snapshot.fileUri, snapshot.documentVersion)))

  private def closeDocument(document: TypedDocument): Unit =
    val _ = driverClass.getMethod("close", classOf[URI]).invoke(driver, compilerUri(document.fileUri))

  /** Dotty converts interactive URIs to NIO paths. IntelliJ test and scratch files can use non-file VFS schemes. */
  private def compilerUri(fileUri: String): URI =
    val uri = URI.create(fileUri)
    if uri.getScheme == "file" then uri
    else
      val stableName = UUID.nameUUIDFromBytes(fileUri.getBytes(StandardCharsets.UTF_8))
      Path.of(System.getProperty("java.io.tmpdir"), "metallurgy-pc", s"$stableName.scala").toUri

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

  private def spansModule: AnyRef =
    Class.forName("dotty.tools.dotc.util.Spans$", true, classloader).getField("MODULE$").get(null)

  private def spanStart(span: AnyRef): Int = spanExtension("start$extension", span)

  private def spanEnd(span: AnyRef): Int = spanExtension("end$extension", span)

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
            .map(TypedTreeSelection(_, TypeRendering.Widened))
        .getOrElse(TypedTreeSelection(closest, TypeRendering.Widened))

  private def sameExtentTree(trees: List[AnyRef], closest: AnyRef, kind: TypedTreeKind): Option[AnyRef] =
    trees.reverse.find(tree => kind.matches(tree) && hasSameExtent(tree, closest))

  private def pathTrees(path: AnyRef): List[AnyRef] =
    val iterator = path.getClass.getMethod("iterator").invoke(path)
    val hasNext  = iterator.getClass.getMethod("hasNext")
    val next     = iterator.getClass.getMethod("next")
    Iterator
      .continually(iterator)
      .takeWhile(it => hasNext.invoke(it).asInstanceOf[Boolean])
      .map(it => next.invoke(it).asInstanceOf[AnyRef])
      .toList

  private def renderTreeType(selection: TypedTreeSelection, context: AnyRef): String =
    val rawType    = selection.tree.getClass.getMethod("tpe").invoke(selection.tree)
    val base       = selection.rendering match
      case TypeRendering.Exact   => rawType
      case TypeRendering.Widened => invokeContextual(rawType, "widenTermRefExpr", context)
    val dealiased  = invokeContextual(base, "dealias", context)
    val normalized = invokeContextual(dealiased, "normalized", context)
    val tpe        = invokeContextual(normalized, "simplified", context)
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
  case TypeApply extends TypedTreeKind("TypeApply")

  def matches(tree: AnyRef): Boolean = tree.getClass.getSimpleName == simpleName
