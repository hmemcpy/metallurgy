package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.build.ScalacFlagsService
import com.hmemcpy.metallurgy.feature.compilertype.TypeRenderer
import com.hmemcpy.metallurgy.settings.MetallurgySettings
import com.intellij.openapi.roots.OrderEnumerator
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.jetbrains.plugins.scala.project.ScalaLanguageLevel
import org.junit.Assert.assertTrue

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

/** Characterization for Scala 3 `MacroAnnotation`. The presentation-compiler integration drives dotc's
  * `InteractiveDriver`, which expands inline and def macros but does not run `MacroAnnotation` expansion. A member an
  * annotation generates is therefore not resolvable through pc. This test pins that behavior; if the driver gains
  * macro-annotation expansion, the assertion will fail and the limitation can be lifted.
  */
final class PcMacroAnnotationTest extends ScalaLightCodeInsightFixtureTestCase:

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == ScalaVersion.fromString("3.5.2").get

  override protected def defaultVersionOverride: Option[ScalaVersion] =
    Some(new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2"))

  override def getTestDataPath: String =
    Path.of("src", "test", "testdata").toAbsolutePath.toString

  private val source: String =
    """import scala.annotation.{experimental, MacroAnnotation}
      |import scala.quoted.*
      |
      |@experimental
      |class addFoo extends MacroAnnotation:
      |  def transform(using Quotes)(
      |    definition: quotes.reflect.Definition,
      |    companion: Option[quotes.reflect.Definition]
      |  ): List[quotes.reflect.Definition] =
      |    import quotes.reflect.*
      |    definition match
      |      case ClassDef(name, ctr, parents, self, body) =>
      |        val cls = definition.symbol
      |        val fooSym = Symbol.newMethod(cls, "foo", MethodType(Nil)(_ => Nil, _ => TypeRepr.of[Int]), Flags.EmptyFlags, Symbol.noSymbol)
      |        val fooDef = DefDef(fooSym, _ => Some(Literal(IntConstant(42))))
      |        ClassDef.copy(definition)(name, ctr, parents, self, fooDef :: body) :: Nil
      |      case _ => definition :: Nil
      |
      |@experimental
      |@addFoo
      |class Box
      |
      |val box = new Box
      |val result = box.foo
      |""".stripMargin

  def testInteractiveDriverDoesNotExpandMacroAnnotations(): Unit = withSession: session =>
    val snapshot = PcSnapshot("file:///MacroAnnotation.scala", 1L, source)
    val _        = session.scheduleRetypecheck(snapshot).get(30, TimeUnit.SECONDS)
    val offset   = source.lastIndexOf("result")
    val rendered = TypeRenderer.render(session, snapshot, offset)
    println(s"[macro] box.foo -> ${rendered.getOrElse("<none>")}")
    assertTrue(
      "pc unexpectedly expanded the macro annotation and resolved the generated member; " +
        s"the InteractiveDriver limitation may be lifted (got '${rendered.getOrElse("<none>")}').",
      rendered.exists(_.contains("not a member"))
    )

  private def withSession(test: PcSession => Unit): Unit =
    val temporaryDirectory = Files.createTempDirectory("pc-macro-annotation")
    val fetcher            = new MtagsFetcher(
      PcArtifactCache(temporaryDirectory.resolve("cache")),
      PresentationCompilerResolver.bundled,
      BackgroundRunner.direct
    )
    val settings           = MetallurgySettings(getProject)
    try
      settings.setEnabled(getModule, enabled = true)
      val _ = onPooledThread(fetcher.jarsFor(testScalaVersion.minor).get(120, TimeUnit.SECONDS))
      onPooledThread:
        val options = ScalacFlagsService.get(getProject).compilerOptions(getModule) :+ "-experimental"
        val session = PcSession.create(testScalaVersion.minor, moduleClasspath, options, fetcher)
        try test(session)
        finally session.close()
    finally
      settings.setEnabled(getModule, enabled = false)
      deleteRecursively(temporaryDirectory)

  private def testScalaVersion: ScalaVersion = new ScalaVersion(ScalaLanguageLevel.Scala_3_5, "2")

  private def moduleClasspath =
    OrderEnumerator
      .orderEntries(getModule)
      .recursively
      .compileOnly
      .withoutSdk
      .classes
      .getPathsList
      .getPathList
      .asScala
      .map(new java.io.File(_))
      .toSeq

  private def onPooledThread[A](body: => A): A =
    com.intellij.openapi.application.ApplicationManager.getApplication
      .executeOnPooledThread(() => body)
      .get(120, TimeUnit.SECONDS)

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      val stream = Files.walk(path)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      finally stream.close()
