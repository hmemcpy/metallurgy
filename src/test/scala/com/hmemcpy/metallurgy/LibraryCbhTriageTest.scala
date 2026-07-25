package com.hmemcpy.metallurgy

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import org.jetbrains.plugins.scala.DependencyManagerBase.RichStr
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.libraryLoaders.{IvyManagedLoader, LibraryLoader}
import org.jetbrains.plugins.scala.compiler.highlighting.ScalaCompilerHighlightingTestBase
import org.jetbrains.plugins.scala.util.CompilerTestUtil.runWithErrorsFromCompiler

/** Companion to `Scala3GapTriageTest` for **library-backed** Scala 3 — where real steady-state red would hide
  * (macro/typeclass derivation from a library the hand-rolled annotator can't reproduce). Same measurement: CBH on,
  * Metallurgy off, actual error `HighlightInfo`s at steady state, `#control` to prove the harness detects red.
  *
  * Settle helper duplicated from `Scala3GapTriageTest` for now (measurement tooling); extract if a third consumer
  * appears.
  */
final class LibraryCbhTriageTest extends ScalaCompilerHighlightingTestBase:

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.fromString("3.5.2").get

  override protected def additionalLibraries: Seq[LibraryLoader] =
    Seq(IvyManagedLoader(("io.circe" %% "circe-generic" % "0.14.10").transitive()))

  private val cases: Seq[(String, String)] = Seq(
    "#control real error"          ->
      """object Broken:
        |  val x: Int = "not an int"
        |""".stripMargin,
    "circe derives Codec.AsObject" ->
      """import io.circe.{Codec, Encoder}
        |case class Person(name: String, age: Int) derives Codec.AsObject
        |val enc: Encoder[Person] = summon[Encoder[Person]]
        |""".stripMargin,
    "circe semiauto deriveCodec"   ->
      """import io.circe.Codec
        |import io.circe.generic.semiauto.*
        |case class Box(value: Int)
        |object Box:
        |  given Codec[Box] = deriveCodec
        |""".stripMargin,
    "circe deriveEncoder + decode" ->
      """import io.circe.Decoder
        |import io.circe.generic.semiauto.*
        |case class Event(id: Int, name: String)
        |object Event:
        |  given Decoder[Event] = deriveDecoder
        |val parsed: Option[Event] = summon[Decoder[Event]].decodeJson(io.circe.Json.fromInt(1)).toOption
        |""".stripMargin
  )

  def testLibraryCbhTriage(): Unit = runTriage()

  private def runTriage(): Unit =
    runWithErrorsFromCompiler(getProject):
      cases.foreach((label, source) => measureOne(label, source))

  private def measureOne(label: String, source: String): Unit =
    val pkg    = "metallurgy_libtriage_" + label.replaceAll("[^A-Za-z0-9]", "").toLowerCase
    val file   = addFileToProjectSources(fileNameFor(label), s"package $pkg\n" + source)
    waitUntilFileIsHighlighted(file)
    val errors = waitForSettledErrors(file)
    val detail = errors.map(e => Option(e.getDescription).getOrElse("")).mkString("; ")
    println(f"[lib-triage] $label%-34s native-red=${errors.nonEmpty}  $detail")

  private def waitForSettledErrors(file: com.intellij.openapi.vfs.VirtualFile): Seq[HighlightInfo] =
    val started     = System.currentTimeMillis()
    val deadline    = started + 30_000L
    var previous    = Option.empty[Set[String]]
    var stableSince = started
    var current     = Seq.empty[HighlightInfo]
    while System.currentTimeMillis() < deadline do
      Thread.sleep(500)
      current = fetchHighlightInfos(file).filter(_.getSeverity == HighlightSeverity.ERROR)
      val now = System.currentTimeMillis()
      val sig = current.map(e => s"${e.getStartOffset}-${e.getEndOffset}").toSet
      previous match
        case Some(`sig`) if now - stableSince > 1000L && now - started > 1500L => return current
        case _                                                                 => previous = Some(sig); stableSince = now
    current

  private def fileNameFor(label: String): String =
    label.replaceAll("[^A-Za-z0-9]", "") + ".scala"
