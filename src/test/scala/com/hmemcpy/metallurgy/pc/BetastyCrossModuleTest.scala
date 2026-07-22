package com.hmemcpy.metallurgy.pc

import com.hmemcpy.metallurgy.build.ScalacFlagsService
import com.intellij.openapi.application.ApplicationManager
import org.jetbrains.plugins.scala.ScalaVersion
import org.jetbrains.plugins.scala.base.ScalaLightCodeInsightFixtureTestCase
import org.junit.Assert.{assertEquals, assertTrue}

import java.net.{URL, URLClassLoader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.util.control.NonFatal

/** Cross-module behaviour of the presentation-compiler session.
  *
  * Module A is compiled with `-Ybest-effort -Ywith-best-effort-tasty` via the exact-version presentation-compiler
  * classloader (reflective `dotc.Main`); module B's [[PcSession]] reads A's output from its classpath.
  *
  * Findings encoded here:
  *   - A *clean* upstream rename is reflected by B's session: each retypecheck builds a fresh `InteractiveDriver` that
  *     re-reads the classpath directory, so the pc layer is NOT stale w.r.t. on-disk classpath changes.
  *   - The real cross-module gap is therefore upstream (CBH must recompile A) and in the BETASTy-specific case (a
  *     *broken* A emits only `.betasty`, which B must read).
  */
final class BetastyCrossModuleTest extends ScalaLightCodeInsightFixtureTestCase:

  private val testScalaVersion = ScalaVersion.fromString("3.5.2").get

  override def getTestDataPath: String =
    Path.of("src", "test", "testdata").toAbsolutePath.toString

  override protected def supportedIn(version: ScalaVersion): Boolean =
    version == testScalaVersion

  override protected def defaultVersionOverride: Option[ScalaVersion] =
    Some(testScalaVersion)

  private val moduleBUri = "file:///ModuleB.scala"

  def testModuleBReflectsUpstreamCleanRename(): Unit = withFetcher { fetcher =>
    val outA    = Files.createTempDirectory("metallurgy-moduleA")
    val moduleB = readFixture("module_b", "source.scala")

    try
      // Baseline: A defines `class Person`; B references it across the compiled classpath.
      compileModuleA(fetcher, outA, readFixture("module_a", "source.scala"))
      val session = newSession(fetcher, outA)
      try
        assertPerson(
          session,
          moduleB,
          version = 1L,
          resolves = true,
          because = "baseline: A defines Person, so B resolves it across the compiled classpath"
        )

        // A renames Person -> erson and recompiles into a cleaned output directory.
        clear(outA)
        compileModuleA(fetcher, outA, readFixture("module_a", "source.scala").replace("Person", "erson"))

        // The pc layer reflects the upstream change: Person is gone. Each retypecheck builds a fresh
        // InteractiveDriver that re-reads the classpath directory, so the session is NOT stale w.r.t.
        // on-disk classpath changes. The real cross-module gap is upstream — CBH must recompile A.
        assertPerson(
          session,
          moduleB,
          version = 2L,
          resolves = false,
          because = "after A renames Person to erson, B must see Person is gone"
        )
      finally session.close()
    finally deleteRecursively(outA)
  }

  def testBothPcPathsReadBrokenModuleABetasty(): Unit = withFetcher { fetcher =>
    val outA    = Files.createTempDirectory("metallurgy-moduleA")
    val brokenA = readFixture("module_a", "broken_source.scala")
    val probe   =
      """object O:
        |  val p: Per""".stripMargin
    val caret   = probe.lastIndexOf("Per") + 3

    try
      compileBrokenModuleA(fetcher, outA, brokenA)
      val session = newSession(fetcher, outA)
      try
        // With A's `META-INF/best-effort` dir on the classpath, both pc code paths resolve Person from the
        // best-effort tasty: the raw InteractiveDriver (diagnostics) reports no Person error, and the Metals
        // PresentationCompiler (complete) offers Person.
        assertPerson(
          session,
          readFixture("module_b", "source.scala"),
          version = 1L,
          resolves = true,
          because = "raw InteractiveDriver (diagnostics) reads Person from A's .betasty"
        )
        val snapshot = PcSnapshot(moduleBUri, 2L, probe)
        val _        = session.scheduleRetypecheck(snapshot).get(30, TimeUnit.SECONDS)
        val offered  = session.complete(moduleBUri, probe, 2L, caret).map(_.lookupName).toSet
        assertTrue(s"Metals pc (complete) did not offer Person: $offered", offered.contains("Person"))
      finally session.close()
    finally deleteRecursively(outA)
  }

  def testBetastyArtifactIsValidAndCrossModuleResolvable(): Unit = withFetcher { fetcher =>
    val outA    = Files.createTempDirectory("metallurgy-moduleA")
    val brokenA = readFixture("module_a", "broken_source.scala")
    val beDir   = outA.resolve("META-INF").resolve("best-effort")

    try
      compileBrokenModuleA(fetcher, outA, brokenA)
      val personBetasty = beDir.resolve("Person.betasty")
      assertTrue("Person.betasty not emitted", Files.exists(personBetasty))

      // The artifact is valid: dotc reconstructs Person from its best-effort tasty via -from-tasty.
      val pcJars = pcJarsFor(fetcher)
      val out2   = Files.createTempDirectory("readTasty")
      val args   = Array(
        "-from-tasty",
        "-Ywith-best-effort-tasty",
        "-classpath",
        pcJars.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator),
        "-d",
        out2.toAbsolutePath.toString,
        personBetasty.toAbsolutePath.toString
      )
      assertTrue("-from-tasty failed to reconstruct Person from .betasty", !invokeDotcMain(pcJars, args))

      // Cross-module read (plain dotc, not our PcSession): only with the flag AND the best-effort dir on the
      // classpath does B resolve Person — the control (no flag) must fail.
      assertTrue(
        "control: Person should NOT resolve without the betasty flag",
        !dotcResolvesPerson(fetcher, Seq(beDir.toFile), withBetasty = false)
      )
      assertTrue(
        "Person should resolve from A's .betasty with the flag + best-effort classpath",
        dotcResolvesPerson(fetcher, Seq(beDir.toFile), withBetasty = true)
      )
    finally deleteRecursively(outA)
  }

  private def assertPerson(
      session: PcSession,
      source: String,
      version: Long,
      resolves: Boolean,
      because: String
  ): Unit =
    val snapshot     = PcSnapshot(moduleBUri, version, source)
    val outcome      = session.scheduleRetypecheck(snapshot).get(30, TimeUnit.SECONDS)
    assertEquals(s"retypecheck did not apply (got $outcome): $because", RetypecheckOutcome.Applied, outcome)
    val personErrors = session
      .diagnostics(snapshot)
      .getOrElse(Seq.empty)
      .filter(diagnostic => diagnostic.isError && diagnostic.message.contains("Person"))
    val detail       = personErrors.map(_.message).mkString("; ")
    resolves match
      case true  =>
        assertTrue(s"Expected Person to resolve ($because), but got: $detail", personErrors.isEmpty)
      case false =>
        assertTrue(s"Expected Person to be unresolved ($because), but it resolved cleanly", personErrors.nonEmpty)

  /** Compiles a clean `source` for module A with the production BETASTy flags. */
  private def compileModuleA(fetcher: MtagsFetcher, outDir: Path, source: String): Unit =
    val hasErrors = runDotc(fetcher, outDir, source)
    assertTrue(s"module A failed to compile:\n$source", !hasErrors)

  /** Compiles a deliberately broken `source` (with `-Ybest-effort`) and asserts it both errored and emitted `.betasty`
    * — the only artifact a failing upstream module leaves for dependents.
    */
  private def compileBrokenModuleA(fetcher: MtagsFetcher, outDir: Path, source: String): Unit =
    val hasErrors = runDotc(fetcher, outDir, source)
    assertTrue(s"broken module A compiled cleanly (expected an error):\n$source", hasErrors)
    assertTrue(s"expected a .betasty artifact under $outDir", hasBetasty(outDir))

  /** Runs `dotc.Main` reflectively through a child-first classloader built from the exact-version pc jars, so `dotc`
    * runs at the target Scala version (not the test runtime's). Returns whether the run reported errors.
    */
  private def runDotc(fetcher: MtagsFetcher, outDir: Path, source: String): Boolean =
    val pcJars = pcJarsFor(fetcher)
    val src    = writeTempSource("moduleA", source)
    val args   = Array(
      "-classpath",
      pcJars.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator),
      "-Ybest-effort",
      "-Ywith-best-effort-tasty",
      "-d",
      outDir.toAbsolutePath.toString,
      src.toAbsolutePath.toString
    )
    invokeDotcMain(pcJars, args)

  /** Typechecks module B with plain `dotc.Main` (NOT our PcSession) against `classpath`, returning whether `Person`
    * resolves (no errors reported). `withBetasty` toggles `-Ywith-best-effort-tasty` — the flag pc must honour.
    */
  private def dotcResolvesPerson(
      fetcher: MtagsFetcher,
      classpath: Seq[java.io.File],
      withBetasty: Boolean
  ): Boolean =
    val pcJars = pcJarsFor(fetcher)
    val src    = writeTempSource("moduleB", readFixture("module_b", "source.scala"))
    val out    = Files.createTempDirectory("dotcB")
    val flags  = if withBetasty then Seq("-Ybest-effort", "-Ywith-best-effort-tasty") else Seq.empty[String]
    val args   = Array(
      "-classpath",
      (pcJars ++ classpath).map(_.getAbsolutePath).mkString(java.io.File.pathSeparator)
    ) ++ flags ++ Seq("-d", out.toAbsolutePath.toString, src.toAbsolutePath.toString)
    !invokeDotcMain(pcJars, args)

  private def invokeDotcMain(pcJars: Seq[java.io.File], args: Array[String]): Boolean =
    val loader = childFirstLoader(pcJars.map(_.toURI.toURL))
    try
      val mainModule = Class
        .forName("dotty.tools.dotc.Main$", true, loader)
        .getField("MODULE$")
        .get(null)
      val process    = mainModule.getClass.getMethods
        .find(method =>
          method.getName == "process" &&
            method.getParameterCount == 1 &&
            method.getParameterTypes()(0) == classOf[Array[String]]
        )
        .getOrElse(throw new NoSuchMethodException("dotty.tools.dotc.Main.process(Array[String])"))
      val reporter   = process.invoke(mainModule, args)
      reporter.getClass.getMethod("hasErrors").invoke(reporter).asInstanceOf[Boolean]
    finally
      try loader.close()
      catch case NonFatal(_) => ()

  private def pcJarsFor(fetcher: MtagsFetcher): Seq[java.io.File] =
    fetcher
      .cachedJars(testScalaVersion.minor)
      .getOrElse(throw new AssertionError("presentation-compiler artifacts are not cached"))

  private def writeTempSource(prefix: String, source: String): Path =
    val src = Files.createTempFile(prefix, ".scala")
    Files.write(src, source.getBytes(StandardCharsets.UTF_8))
    src

  private def hasBetasty(outDir: Path): Boolean =
    val bestEffort = outDir.resolve("META-INF").resolve("best-effort")
    if !Files.isDirectory(bestEffort) then false
    else
      val stream = Files.walk(bestEffort)
      try stream.anyMatch(_.getFileName.toString.endsWith(".betasty"))
      finally stream.close()

  private def newSession(fetcher: MtagsFetcher, moduleAOutput: Path): PcSession =
    // The driver's -classpath is the `classpath` arg verbatim (PcSession.create adds the cached jars only to the
    // classloader), so it must include the Scala library — the pc jars carry scala3-library + scala3-compiler.
    // BETASTy: `.betasty` must sit at a classpath ROOT, so point the classpath at the best-effort subdir of A's
    // output (mirrors scala3's `compileWithBestEffortTasty`: -classpath ...:<out>/META-INF/best-effort).
    val coreClasspath  = pcJarsFor(fetcher)
    val bestEffortDir  = moduleAOutput.resolve("META-INF").resolve("best-effort").toFile
    val moduleAEntries = Seq(moduleAOutput.toFile) ++ Option.when(Files.exists(bestEffortDir.toPath))(bestEffortDir)
    PcSession.create(
      testScalaVersion.minor,
      coreClasspath ++ moduleAEntries,
      ScalacFlagsService.RequiredFlags,
      fetcher
    )

  private def clear(directory: Path): Unit =
    deleteRecursively(directory)
    val _ = Files.createDirectories(directory)

  private def readFixture(module: String, name: String): String =
    Files.readString(Path.of(getTestDataPath, "feature", "betasty", module, name))

  private def withFetcher(test: MtagsFetcher => Unit): Unit =
    val temporaryDirectory = Files.createTempDirectory("metallurgy-betasty")
    val fetcher            = new MtagsFetcher(
      PcArtifactCache(temporaryDirectory.resolve("cache")),
      PresentationCompilerResolver.publicCoursier,
      BackgroundRunner.direct
    )
    try
      val _ = onPooledThread(fetcher.jarsFor(testScalaVersion.minor).get(120, TimeUnit.SECONDS))
      onPooledThread(test(fetcher))
    finally deleteRecursively(temporaryDirectory)

  private def onPooledThread[A](body: => A): A =
    ApplicationManager.getApplication.executeOnPooledThread(() => body).get(120, TimeUnit.SECONDS)

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      val stream = Files.walk(path)
      try stream.sorted(java.util.Comparator.reverseOrder()).forEach(Files.delete)
      finally stream.close()

  private def childFirstLoader(urls: Seq[URL]): URLClassLoader =
    new URLClassLoader(urls.toArray, getClass.getClassLoader):
      override protected def loadClass(name: String, resolve: Boolean): Class[?] =
        Option(findLoadedClass(name)).getOrElse:
          try
            val loaded = findClass(name)
            if resolve then resolveClass(loaded)
            loaded
          catch case _: ClassNotFoundException => super.loadClass(name, resolve)
