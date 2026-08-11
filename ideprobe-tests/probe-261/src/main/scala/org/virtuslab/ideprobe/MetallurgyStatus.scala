package org.virtuslab.ideprobe

import java.lang.reflect.Method
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.{CountDownLatch, TimeUnit}

import scala.util.control.NonFatal

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.impl.status.IdeStatusBarImpl
import com.intellij.openapi.wm.{StatusBarWidget, WindowManager}
import com.intellij.psi.PsiDocumentManager

import org.jetbrains.jps.incremental.scala.remote.SerializablePath
import org.jetbrains.plugins.scala.compiler.{CompilationUnitId, CompilerEvent, CompilerEventListener}
import org.jetbrains.plugins.scala.util.{CanonicalPath, CompilationId}
import org.virtuslab.ideprobe.handlers.{IntelliJApi, PSI, Projects}
import org.virtuslab.ideprobe.protocol.FileRef

object MetallurgyStatus extends IntelliJApi {
  private val PluginIdValue = PluginId.getId("com.hmemcpy.metallurgy")

  def inspect(fileRef: FileRef): Map[String, String] = {
    val project = Projects.resolve(fileRef.project)
    val loader = Option(PluginManagerCore.getPlugin(PluginIdValue))
      .map(_.getPluginClassLoader)
      .getOrElse(error("Metallurgy plugin is not loaded"))
    val settingsClass = Class.forName("com.hmemcpy.metallurgy.settings.MetallurgySettings", true, loader)
    val lifecycleClass = Class.forName(
      "com.hmemcpy.metallurgy.psiproducer.Scala3ParserPreparationLifecycle",
      true,
      loader
    )
    val settings = project.getService(settingsClass.asInstanceOf[Class[AnyRef]])
    val lifecycle = project.getService(lifecycleClass.asInstanceOf[Class[AnyRef]])
    val globallyEnabled = invoke(settingsClass, settings, "isGloballyEnabled").toString
    val stateFor = lifecycleClass.getMethod("stateFor", classOf[com.intellij.openapi.module.Module])
    val moduleStates = ModuleManager
      .getInstance(project)
      .getModules
      .toVector
      .map(module => s"module.${module.getName}" -> stateFor.invoke(lifecycle, module).toString)
    val language = read(PSI.resolve(fileRef).getLanguage.getDisplayName)
    (Vector("globallyEnabled" -> globallyEnabled, "language" -> language) ++
      syntaxWidget(project) ++ moduleStates).toMap
  }

  def replaceWithSupportedSyntax(fileRef: FileRef): Map[String, String] = {
    val project = Projects.resolve(fileRef.project)
    val file = PSI.resolve(fileRef)
    val document = Option(PsiDocumentManager.getInstance(project).getDocument(file))
      .getOrElse(error(s"Document is absent for ${fileRef.path}"))
    val completion = new CompilerEventCompletion
    val subscription = new CompilerEventSubscription(project, completion)
    try {
      val subscribedAt = subscription.subscribe()
      val editStartedAt = Instant.now()
      val generation = onEdt {
        WriteCommandAction.runWriteCommandAction(
          project,
          new Runnable {
            override def run(): Unit =
              document.setText(
                """package dogfood.showcase
                  |
                  |trait Base
                  |trait Other
                  |import scala.language.experimental.namedTypeArguments
                  |import scala.language.experimental.modularity
                  |trait Lower extends Base
                  |trait Upper
                  |trait Evidence[A]
                  |trait BinaryEvidence[A, B]
                  |type TopAlias = Base
                  |type AppliedAlias = List[Int]
                  |type BoundedAlias[A >: Lower <: Base, F[_]] = F[A]
                  |type WildcardAlias = List[? >: Lower <: Base]
                  |type LambdaAlias = [A >: Lower <: Base] =>> List[A]
                  |opaque type OpaqueAlias >: Lower <: Base = Base
                  |def typedTop(value: Base): Base = value
                  |def bounded[A >: Lower <: Base, F[_]]: A = ???
                  |def namedBound[A: Evidence as evidence](value: A): Evidence[A] = evidence
                  |def aggregateBound[A: {Evidence, [X] =>> BinaryEvidence[X, X]}](value: A): A = value
                  |def choose[A]: A = ???
                  |val namedApplication = choose[A = Int]
                  |val typedValue: Base = ???
                  |var typedVariable: Base = typedValue
                  |def topApply = List(1)
                  |val topNumber = 1
                  |var topIdent = topNumber
                  |class Braced[A >: Lower <: Base, +B, -C, F[_]](value: Base)(using context: Other) extends Base derives CanEqual {
                  |  self: Other =>
                  |  type Alias = Base
                  |  type AbstractAlias >: Lower <: Base
                  |  def declared: Base = value
                  |  val declaredValue: Base = value
                  |  var declaredVariable: Base = value
                  |  trait NestedTrait[T]():
                  |    type Abstract
                  |  end NestedTrait
                  |  object NestedObject:
                  |    def selected = List(1).head
                  |    val tupled = (topNumber, topIdent)
                  |    var infixed = topNumber + topIdent
                  |  end NestedObject
                  |}
                  |trait Indented[-T]():
                  |  def blocked = {
                  |    val local = 1
                  |    local
                  |  }
                  |end Indented
                  |object Empty {}
                  |enum Signal:
                  |  case Ready
                  |end Signal
                  |""".stripMargin
              )
          }
        )
        PsiDocumentManager.getInstance(project).commitDocument(document)
        FileDocumentManager.getInstance().saveDocument(document)
        EditedDocumentGeneration(
          CanonicalPath(
            Option(file.getVirtualFile.getCanonicalPath)
              .getOrElse(error(s"Canonical path is absent for ${fileRef.path}"))
          ),
          Path.of(file.getVirtualFile.getPath).toAbsolutePath.normalize(),
          documentVersion(document)
        )
      }
      val editFinishedAt = Instant.now()
      completion.expect(generation)
      val evidence = completion.await(2, TimeUnit.MINUTES)
      awaitMetallurgyCompilerBackend(project, file.getVirtualFile, 2, TimeUnit.MINUTES)
      inspect(fileRef) ++ evidence.fields(subscribedAt, editStartedAt, editFinishedAt, project.getName) +
        ("compilerEvent.metallurgyBackendQuiesced" -> "true")
    } finally subscription.close()
  }

  private def awaitMetallurgyCompilerBackend(
      project: Project,
      file: com.intellij.openapi.vfs.VirtualFile,
      timeout: Long,
      unit: TimeUnit
  ): Unit = {
    val loader = Option(PluginManagerCore.getPlugin(PluginIdValue))
      .map(_.getPluginClassLoader)
      .getOrElse(error("Metallurgy plugin is not loaded"))
    val managerClass = Class.forName("com.hmemcpy.metallurgy.pc.PcSessionManager", true, loader)
    val manager = project.getService(managerClass.asInstanceOf[Class[AnyRef]])
    val future = managerClass
      .getMethod("prepareCompilerBackend", classOf[com.intellij.openapi.vfs.VirtualFile])
      .invoke(manager, file)
      .asInstanceOf[CompletableFuture[Option[AnyRef]]]
    if (future.get(timeout, unit).isEmpty)
      error(s"Metallurgy compiler backend did not publish ${file.getUrl}")
  }

  private def documentVersion(document: Document): Long = document match {
    case value: DocumentEx => value.getModificationSequence.toLong
    case _                 => document.getModificationStamp
  }

  private def syntaxWidget(project: com.intellij.openapi.project.Project): Vector[(String, String)] = onEdt {
    val statusBar = Option(WindowManager.getInstance().getStatusBar(project))
    val widget = statusBar.flatMap(value => Option(value.getWidget("Metallurgy")))
    val presentation = widget.flatMap(value =>
      Option(value.getPresentation).collect { case text: StatusBarWidget.TextPresentation => text }
    )
    val component = statusBar.collect { case value: IdeStatusBarImpl => value }.flatMap(value =>
      Option(value.getWidgetComponent("Metallurgy"))
    )
    Vector(
      "syntaxWidget.present" -> widget.nonEmpty.toString,
      "syntaxWidget.text" -> presentation.fold("<absent>")(_.getText),
      "syntaxWidget.tooltip" -> presentation.fold("<absent>")(_.getTooltipText),
      "syntaxWidget.componentVisible" -> component.exists(_.isVisible).toString,
      "syntaxWidget.componentShowing" -> component.exists(_.isShowing).toString
    )
  }

  private def onEdt[A](body: => A): A = {
    val application = ApplicationManager.getApplication
    if (application.isDispatchThread) body
    else {
      val result = new AtomicReference[A]()
      application.invokeAndWait(new Runnable {
        override def run(): Unit = result.set(body)
      })
      result.get()
    }
  }

  private def invoke(owner: Class[_], receiver: AnyRef, name: String): AnyRef = {
    val method: Method = owner.getMethod(name)
    method.invoke(receiver)
  }
}

private[ideprobe] final case class EditedDocumentGeneration(
    canonicalPath: CanonicalPath,
    sourcePath: Path,
    documentVersion: Long
)

private[ideprobe] final case class CompilerEventEvidence(
    compilationId: Long,
    compilationUnit: String,
    documentPath: String,
    documentVersion: Long,
    startedAt: Instant,
    finishedAt: Instant,
    matchedSource: String,
    sourceCount: Int
) {
  def fields(
      subscribedAt: Instant,
      editStartedAt: Instant,
      editFinishedAt: Instant,
      projectName: String
  ): Map[String, String] = Map(
    "compilerEvent.subscribedBeforeEdit"  -> (subscribedAt.compareTo(editStartedAt) <= 0).toString,
    "compilerEvent.subscribedAt"          -> subscribedAt.toString,
    "compilerEvent.editStartedAt"         -> editStartedAt.toString,
    "compilerEvent.editFinishedAt"        -> editFinishedAt.toString,
    "compilerEvent.compilationStartedAt"  -> startedAt.toString,
    "compilerEvent.compilationFinishedAt" -> finishedAt.toString,
    "compilerEvent.compilationId"         -> compilationId.toString,
    "compilerEvent.compilationUnit"       -> compilationUnit,
    "compilerEvent.documentPath"          -> documentPath,
    "compilerEvent.documentVersion"       -> documentVersion.toString,
    "compilerEvent.matchedSource"          -> matchedSource,
    "compilerEvent.sourceCount"            -> sourceCount.toString,
    "compilerEvent.correlation"           ->
      s"project=$projectName; startAndFinish=true; documentGeneration=true; finishedSource=true"
  )
}

private[ideprobe] final class CompilerEventCompletion {
  private val latch = new CountDownLatch(1)
  private val lock  = new AnyRef

  private var expected = Option.empty[EditedDocumentGeneration]
  private var starts   = Map.empty[(CompilationId, Option[CompilationUnitId]), Instant]
  private var finishes = Vector.empty[(CompilerEvent.CompilationFinished, Instant)]
  private var result   = Option.empty[CompilerEventEvidence]
  private var failure  = Option.empty[String]

  def expect(generation: EditedDocumentGeneration): Unit = lock.synchronized {
    if (expected.nonEmpty) throw new IllegalStateException("Edited document generation is already set")
    expected = Some(generation)
    try tryMatch()
    catch {
      case NonFatal(cause) =>
        fail(s"Could not inspect Scala compiler event: ${cause.getClass.getName}: ${cause.getMessage}")
    }
  }

  def eventReceived(event: CompilerEvent): Unit = lock.synchronized {
    try {
      event match {
        case started: CompilerEvent.CompilationStarted   =>
          starts += (started.compilationId -> started.compilationUnitId) -> Instant.now()
        case finished: CompilerEvent.CompilationFinished =>
          finishes :+= finished -> Instant.now()
          tryMatch()
        case _                                           =>
      }
    } catch {
      case NonFatal(cause) =>
        fail(s"Could not inspect Scala compiler event: ${cause.getClass.getName}: ${cause.getMessage}")
    }
  }

  def await(timeout: Long, unit: TimeUnit): CompilerEventEvidence = {
    val signalled = latch.await(timeout, unit)
    lock.synchronized {
      result.getOrElse {
        val reason = failure.getOrElse {
          val timeoutDescription = s"$timeout ${unit.toString.toLowerCase}"
          s"No matching Scala CompilationFinished event after $timeoutDescription " +
            s"(starts=${starts.size}, finishes=${finishes.size}, generation=${expected.map(_.documentVersion)})"
        }
        if (!signalled || failure.nonEmpty) throw new AssertionError(reason)
        throw new AssertionError("Scala compiler event wait completed without evidence")
      }
    }
  }

  def cancel(reason: String): Unit = lock.synchronized {
    if (result.isEmpty && failure.isEmpty) fail(reason)
  }

  private def tryMatch(): Unit =
    if (result.isEmpty && failure.isEmpty) {
      expected.foreach { generation =>
        finishes.iterator
          .flatMap { case (finished, finishedAt) =>
            starts
              .get(finished.compilationId -> finished.compilationUnitId)
              .filter(_ => finished.compilationUnitId.isEmpty)
              .filter(_ => hasDocumentGeneration(finished.compilationId, generation))
              .filter(_ => hasFinishedSource(finished.sources, generation.sourcePath))
              .map { startedAt =>
                CompilerEventEvidence(
                  finished.compilationId.timestamp,
                  finished.compilationUnitId.fold("<none>")(_.toString),
                  generation.canonicalPath.path,
                  generation.documentVersion,
                  startedAt,
                  finishedAt,
                  generation.sourcePath.toString,
                  finished.sources.size
                )
              }
          }
          .nextOption()
          .foreach { evidence =>
            result = Some(evidence)
            latch.countDown()
          }
      }
    }

  private def hasDocumentGeneration(
      compilationId: CompilationId,
      generation: EditedDocumentGeneration
  ): Boolean = {
    val versions = compilationId.documentVersions
    versions.get(generation.canonicalPath).contains(generation.documentVersion)
  }

  private def hasFinishedSource(
      sources: scala.collection.immutable.Set[SerializablePath],
      expectedPath: Path
  ): Boolean =
    sources.iterator.exists(_.toPath.toAbsolutePath.normalize() == expectedPath)

  private def fail(reason: String): Unit = {
    failure = Some(reason)
    latch.countDown()
  }
}

private final class CompilerEventSubscription(project: Project, completion: CompilerEventCompletion)
    extends Disposable {
  private val disposed = new AtomicBoolean(false)
  private val closeRequested = new AtomicBoolean(false)
  private var registered = false

  def subscribe(): Instant = {
    Disposer.register(project, this)
    registered = true
    try {
      project.getMessageBus
        .connect(this)
        .subscribe(
          CompilerEventListener.topic,
          new CompilerEventListener {
            override def eventReceived(event: CompilerEvent): Unit = completion.eventReceived(event)
          }
        )
      Instant.now()
    } catch {
      case NonFatal(cause) =>
        close()
        throw cause
    }
  }

  def close(): Unit =
    if (registered && closeRequested.compareAndSet(false, true)) Disposer.dispose(this)

  override def dispose(): Unit = {
    closeRequested.set(true)
    if (disposed.compareAndSet(false, true))
      completion.cancel("Project disposed before matching Scala CompilationFinished event")
  }
}
