package com.hmemcpy.metallurgy.pc

import com.intellij.openapi.progress.ProgressManager

import java.io.File
import java.net.URI
import java.util
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
  def typeAt(snapshot: PcSnapshot, offset: Int): Option[String] =
    require(
      typedDocument.get().contains(TypedDocument(snapshot.fileUri, snapshot.documentVersion)),
      "inline type queries require a matching typed document"
    )
    val uri        = URI.create(snapshot.fileUri)
    val context    = driverClass.getMethod("currentCtx").invoke(driver)
    val sourceFile = mapValue(driverClass.getMethod("openedFiles").invoke(driver), uri)
    val trees      = mapValue(driverClass.getMethod("openedTrees").invoke(driver), uri)
    val span       = spansModule.getClass.getMethod("Span", classOf[Int]).invoke(spansModule, Int.box(offset))
    val position   = sourceFile.getClass.getMethod("atSpan", java.lang.Long.TYPE).invoke(sourceFile, span)

    firstInlinedType(pathTo(trees, position, context), context)

  def retypecheck(snapshot: PcSnapshot): Unit = synchronized:
    ensureTyped(snapshot)

  def close(): Unit = synchronized:
    val close = driverClass.getMethod("close", classOf[URI])
    typedDocument.getAndSet(None).foreach(document => close.invoke(driver, URI.create(document.fileUri)))

  private def ensureTyped(snapshot: PcSnapshot): Unit =
    if !typedDocument.get().contains(TypedDocument(snapshot.fileUri, snapshot.documentVersion)) then run(snapshot)

  private def run(snapshot: PcSnapshot): Unit =
    ProgressManager.checkCanceled()
    driverClass
      .getMethod("run", classOf[URI], classOf[String])
      .invoke(driver, URI.create(snapshot.fileUri), snapshot.sourceText)
    ProgressManager.checkCanceled()
    typedDocument.set(Some(TypedDocument(snapshot.fileUri, snapshot.documentVersion)))

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

  private def firstInlinedType(path: AnyRef, context: AnyRef): Option[String] =
    val iterator = path.getClass.getMethod("iterator").invoke(path)
    val hasNext  = iterator.getClass.getMethod("hasNext")
    val next     = iterator.getClass.getMethod("next")
    Iterator
      .continually(iterator)
      .takeWhile(it => hasNext.invoke(it).asInstanceOf[Boolean])
      .map(it => next.invoke(it).asInstanceOf[AnyRef])
      .find(_.getClass.getSimpleName == "Inlined")
      .map: tree =>
        val tpe = tree.getClass.getMethod("tpe").invoke(tree)
        tpe.getClass.getMethods
          .find(method => method.getName == "show" && method.getParameterCount == 1)
          .getOrElse(throw new NoSuchMethodException("Type.show"))
          .invoke(tpe, context)
          .toString
          .replaceAll("\\u001B\\[[;\\d]*m", "")

private final case class TypedDocument(fileUri: String, documentVersion: Long)
