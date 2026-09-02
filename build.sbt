import org.jetbrains.sbtidea.{AutoJbr, JbrPlatform}
import org.jetbrains.sbtidea.packaging.artifact.DistBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import scala.sys.process.Process

lazy val metallurgyBaseline = settingKey[Map[String, String]]("Exact Metallurgy toolchain and host baseline")
lazy val verifyMetallurgyBaseline = taskKey[Unit]("Verify the exact Metallurgy toolchain and host baseline")

def loadMetallurgyBaseline(file: File): Map[String, String] = {
  val required = Set(
    "dogfood.sbt.version",
    "ide.probe.version",
    "intellij.build",
    "intellij.product.code",
    "intellij.release",
    "java.bytecode.release",
    "jbr.java.runtime.version",
    "jbr.java.vendor",
    "jbr.java.vendor.version",
    "sbt.version",
    "scala.compiler.version",
    "scala.plugin.id",
    "scala.plugin.version",
    "testkit.scala.version"
  )
  if (!file.isFile) sys.error(s"Metallurgy baseline manifest is missing: $file")
  val properties = new Properties
  val input      = Files.newInputStream(file.toPath)
  try properties.load(input)
  finally input.close()
  val actual = properties.stringPropertyNames().toArray(new Array[String](0)).toSet
  if (actual != required)
    sys.error(s"Metallurgy baseline keys differ: missing=${required -- actual}, extra=${actual -- required}")
  val values = required.iterator.map { name =>
    val value = properties.getProperty(name)
    if (value == null || value.isEmpty) sys.error(s"Metallurgy baseline value is missing or empty: $name")
    name -> value
  }.toMap
  try values("java.bytecode.release").toInt
  catch {
    case _: NumberFormatException =>
      sys.error(s"Metallurgy baseline value is not an integer: java.bytecode.release=${values("java.bytecode.release")}")
  }
  values
}

def updateTestInputDigest(digest: MessageDigest, file: File): Unit = {
  val input  = Files.newInputStream(file.toPath)
  val buffer = new Array[Byte](64 * 1024)
  try {
    var count = input.read(buffer)
    while (count >= 0) {
      if (count > 0) digest.update(buffer, 0, count)
      count = input.read(buffer)
    }
  } finally input.close()
}

def testInputSha256(root: File): String = {
  val digest = MessageDigest.getInstance("SHA-256")
  val files =
    if (root.isDirectory) (root ** "*").get.filter(_.isFile).sortBy(file => IO.relativize(root, file).getOrElse(""))
    else Seq(root)
  files.foreach { file =>
    val relative = if (root.isDirectory) IO.relativize(root, file).getOrElse(file.getName) else file.getName
    digest.update(relative.getBytes(StandardCharsets.UTF_8))
    digest.update(0.toByte)
    updateTestInputDigest(digest, file)
    digest.update(0.toByte)
  }
  digest.digest().map(byte => f"${byte & 0xff}%02x").mkString
}

ThisBuild / metallurgyBaseline := loadMetallurgyBaseline((ThisBuild / baseDirectory).value / "project" / "metallurgy-baseline.properties")

ThisBuild / scalaVersion       := metallurgyBaseline.value("scala.compiler.version")
ThisBuild / version            := "0.1.0-SNAPSHOT"
ThisBuild / intellijPluginName := "metallurgy"
ThisBuild / intellijBuild      := metallurgyBaseline.value("intellij.build")

Global / intellijAttachSources := true

addCommandAlias("fmt", "scalafmtAll")
addCommandAlias("check", ";verifyMetallurgyBaseline;verifyCopiedIntellijTests;scalafmtCheckAll")
addCommandAlias("testHeadless", "test")
addCommandAlias("compilerTypeAcceptance", "testOnly com.hmemcpy.metallurgy.compilertype.*Test")
addCommandAlias(
  "verifyCopiedIntellijTests",
  ";verifyCopiedIntellijTestFiles;" +
    "testOnly com.hmemcpy.metallurgy.compat.scala3.CopiedIntellijInvocationAccountingTest " +
    "com.hmemcpy.metallurgy.compat.scala3.adapters.Scala3TypeInferenceFixtureContractTest " +
    "com.hmemcpy.metallurgy.generated.intellijscala.typeInference.NamedTypeArgumentsInferenceTest"
)

Global / javacOptions := Seq("--release", metallurgyBaseline.value("java.bytecode.release"))

ThisBuild / resolvers ++= Seq(
  "JetBrains IntelliJ Repository" at "https://www.jetbrains.com/intellij-repository/releases",
  "JetBrains IntelliJ Dependencies" at "https://cache-redirector.jetbrains.com/intellij-dependencies"
)

ThisBuild / scalacOptions ++= Seq(
  "-explain",
  "-deprecation",
  "-unchecked",
  "-feature",
  "-Xfatal-warnings",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  "-language:implicitConversions",
  "-language:reflectiveCalls",
  "-language:existentials",
  "-language:unsafeNulls"
)

lazy val scalaPluginVersion           = settingKey[String]("Pinned Scala plugin version")
// revision of scala-library paired with the Scala 3.7.x toolchain
lazy val scala2LibraryVersion         = settingKey[String]("Scala 2 library paired with the Scala 3 toolchain")
lazy val intellijTestFrameworkVersion = settingKey[String]("Pinned IntelliJ test framework version")

ThisBuild / scalaPluginVersion           := metallurgyBaseline.value("scala.plugin.version")
ThisBuild / scala2LibraryVersion         := metallurgyBaseline.value("testkit.scala.version")
ThisBuild / intellijTestFrameworkVersion := metallurgyBaseline.value("intellij.build")

lazy val intellijTestFrameworkDependencies = Def.setting {
  Seq(
    "com.jetbrains.intellij.platform" % "test-framework-core"         % intellijTestFrameworkVersion.value,
    "com.jetbrains.intellij.platform" % "test-framework-common"       % intellijTestFrameworkVersion.value,
    "com.jetbrains.intellij.platform" % "test-framework"              % intellijTestFrameworkVersion.value,
    "com.jetbrains.intellij.platform" % "test-framework-junit5"       % intellijTestFrameworkVersion.value,
    "com.jetbrains.intellij.java"     % "java-test-framework-shared"  % intellijTestFrameworkVersion.value,
    "com.jetbrains.intellij.java"     % "java-test-framework-backend" % intellijTestFrameworkVersion.value,
    "com.jetbrains.intellij.java"     % "java-test-framework"         % intellijTestFrameworkVersion.value
  ).map(_.exclude("com.google.protobuf", "protobuf-java"))
}

lazy val intellijPluginDependencies = Def.setting {
  Seq(
    "com.intellij.java".toPlugin,
    "JUnit".toPlugin,
    s"org.intellij.scala:${scalaPluginVersion.value}".toPlugin
  )
}

lazy val compileTestkit         = taskKey[Unit]("Compile the in-tree Scala plugin TestKit backport")
lazy val prepareIntellijTestSdk = taskKey[Unit]("Prepare SDK resources expected by IntelliJ light fixtures")
lazy val writeTestInventory     = taskKey[Unit]("Write discovered test names and exact environment coordinates")
lazy val verifyCopiedIntellijTestFiles =
  taskKey[Unit]("Verify copied IntelliJ test provenance, generation, and protected bytes")
lazy val verifyCopiedIntellijTestsAgainstOrigin =
  taskKey[Unit]("Compare copied IntelliJ test bytes with the pinned Git revision")
lazy val generateCopiedIntellijTests =
  taskKey[Unit]("Generate copied IntelliJ test adapters under target")

lazy val root =
  Project("metallurgy", file("."))
    .enablePlugins(SbtIdeaPlugin)
    .settings(
      name                      := "metallurgy",
      patchPluginXml            := pluginXmlOptions { xml =>
        xml.version = version.value
        xml.changeNotes = """<![CDATA[
          <b>Metallurgy</b> — pre-alpha.
          ]]>"""
      },
      libraryDependencies ++= Seq(
        ("org.scalameta"    % "mtags-interfaces"  % "1.3.4")
          .exclude("org.eclipse.lsp4j", "org.eclipse.lsp4j")
          .exclude("org.eclipse.lsp4j", "org.eclipse.lsp4j.jsonrpc"),
        "io.get-coursier"   % "interface"         % "1.0.28",
        "net.bytebuddy"     % "byte-buddy-agent" % "1.18.11",
        ("org.scala-lang"    % "scala3-compiler_3" % "3.7.4") % Test,
        "junit"             % "junit"             % "4.13.2" % Test,
        "com.github.sbt"    % "junit-interface"   % "0.13.3" % Test,
        "org.junit.jupiter" % "junit-jupiter-api" % "5.13.0" % Test
      ) ++ intellijTestFrameworkDependencies.value.map(_ % Test),
      Test / testReportsDirectory :=
        sys.props.get("metallurgy.test.reports").map(file).getOrElse((Test / target).value / "test-reports"),
      Test / javaOptions ++= {
        val testRoot = sys.props
          .get("metallurgy.test.root")
          .map(file)
          .getOrElse(target.value / s"idea-test-${ProcessHandle.current().pid()}")
        Seq(
          s"-Didea.system.path=${testRoot / "system"}",
          s"-Didea.config.path=${testRoot / "config"}",
          s"-Didea.log.path=${testRoot / "system" / "log"}",
          s"-Didea.home.path=${intellijBaseDirectory.value}",
          "-Didea.is.unit.test=true",
          "-Didea.is.headless=true"
        )
      },
      Test / parallelExecution := false,
      Test / unmanagedSourceDirectories +=
        baseDirectory.value / "src" / "test" / "generated" / "intellij-scala",
      Test / unmanagedClasspath +=
        Attributed.blank(baseDirectory.value / "testkit" / "target" / "scala-2.13" / "classes"),
      prepareIntellijTestSdk    := {
        updateIntellij.value
        val sdk    = intellijBaseDirectory.value
        val source = sdk / "plugins" / "java" / "lib" / "resources" / "jdkAnnotations.jar"
        val target = sdk / "lib" / "resources" / "jdkAnnotations.jar"
        if (!target.exists()) {
          IO.createDirectory(target.getParentFile)
          IO.copyFile(source, target)
        }
      },
      packageArtifact           := {
        val mappings        = packageMappings.value
        val outputDirectory = packageOutputDir.value
        val buildTarget     = target.value
        IO.delete(outputDirectory / "lib" / s"${intellijPluginName.value}.jar")
        IO.delete(buildTarget / "sbtidea.cache")
        new DistBuilder(streams.value, buildTarget).produceArtifact(mappings)
        outputDirectory
      },
      compileTestkit            := {
        prepareIntellijTestSdk.value
        val exitCode = Process(Seq("sbt", "--client", "compile"), baseDirectory.value / "testkit").!
        if (exitCode != 0) sys.error(s"TestKit compilation failed with exit code $exitCode")
      },
      Test / compile            := ((Test / compile) dependsOn compileTestkit).value,
      verifyCopiedIntellijTestFiles := {
        val exitCode = Process(
          Seq((baseDirectory.value / "scripts" / "test-copied-intellij-tests.sh").getPath),
          baseDirectory.value,
          "JAVA_HOME" -> sys.props("java.home")
        ).!
        if (exitCode != 0) sys.error(s"Copied IntelliJ test verification failed with exit code $exitCode")
      },
      verifyCopiedIntellijTestsAgainstOrigin := {
        val originRepository = sys.props
          .get("intellij.scala.repo")
          .map(file)
          .getOrElse(sys.error("intellij.scala.repo is required"))
        val exitCode         = Process(
          Seq((baseDirectory.value / "scripts" / "copied-intellij-tests.sh").getPath, "against-origin"),
          baseDirectory.value,
          "JAVA_HOME"                    -> sys.props("java.home"),
          "INTELLIJ_SCALA_REPOSITORY"    -> originRepository.getCanonicalPath
        ).!
        if (exitCode != 0) sys.error(s"Copied IntelliJ origin verification failed with exit code $exitCode")
      },
      generateCopiedIntellijTests := {
        val exitCode = Process(
          Seq((baseDirectory.value / "scripts" / "copied-intellij-tests.sh").getPath, "generate"),
          baseDirectory.value,
          "JAVA_HOME" -> sys.props("java.home")
        ).!
        if (exitCode != 0) sys.error(s"Copied IntelliJ test generation failed with exit code $exitCode")
      },
      verifyMetallurgyBaseline := {
        val verifier = baseDirectory.value / "scripts" / "MetallurgyBaselineVerifier.java"
        val exitCode = Process(Seq(sys.props("java.home") + "/bin/java", verifier.getPath, "static"), baseDirectory.value).!
        if (exitCode != 0) sys.error(s"Metallurgy baseline verification failed with exit code $exitCode")
      },
      writeTestInventory        := {
        val inventory = sys.props
          .get("metallurgy.test.inventory")
          .map(file)
          .getOrElse(sys.error("metallurgy.test.inventory is required"))
        val environment = sys.props
          .get("metallurgy.test.environment")
          .map(file)
          .getOrElse(sys.error("metallurgy.test.environment is required"))
        val classpath = sys.props
          .get("metallurgy.test.classpath")
          .map(file)
          .getOrElse(sys.error("metallurgy.test.classpath is required"))
        val names = (Test / definedTests).value.map(_.name).distinct.sorted
        val classpathEntries = (Test / fullClasspath).value.map(_.data.getCanonicalFile).distinct.sortBy(_.getPath)
        IO.createDirectory(inventory.getParentFile)
        IO.createDirectory(environment.getParentFile)
        IO.createDirectory(classpath.getParentFile)
        IO.writeLines(inventory, names)
        IO.writeLines(
          environment,
          Seq(
            s"intellij.build=${intellijBuild.value}",
            s"java.home=${sys.props("java.home")}",
            s"java.runtime.version=${sys.props("java.runtime.version")}",
            s"java.vendor=${sys.props("java.vendor")}",
            s"plugin.scala.version=${scalaVersion.value}",
            s"sbt.version=${sbtVersion.value}",
            s"test.fixture.compiler=org.scala-lang:scala3-compiler_3:3.5.2",
            s"scala.plugin.version=${scalaPluginVersion.value}",
            s"test.fixture.scala.version=3.5.2",
            s"testkit.scala.version=${scala2LibraryVersion.value}",
            s"baseline.intellij.product.code=${metallurgyBaseline.value("intellij.product.code")}",
            s"baseline.intellij.release=${metallurgyBaseline.value("intellij.release")}",
            s"baseline.jbr.java.runtime.version=${metallurgyBaseline.value("jbr.java.runtime.version")}",
            s"baseline.jbr.java.vendor=${metallurgyBaseline.value("jbr.java.vendor")}",
            s"baseline.jbr.java.vendor.version=${metallurgyBaseline.value("jbr.java.vendor.version")}",
            s"baseline.java.bytecode.release=${metallurgyBaseline.value("java.bytecode.release")}",
            s"baseline.scala.plugin.id=${metallurgyBaseline.value("scala.plugin.id")}",
            s"baseline.ide.probe.version=${metallurgyBaseline.value("ide.probe.version")}",
            s"baseline.dogfood.sbt.version=${metallurgyBaseline.value("dogfood.sbt.version")}"
          )
        )
        IO.writeLines(
          classpath,
          classpathEntries.map { entry =>
            val hash = if (entry.exists()) testInputSha256(entry) else "missing"
            s"$hash\t${entry.getPath}"
          }
        )
        streams.value.log.info(s"Wrote ${names.size} discovered tests to $inventory")
        streams.value.log.info(s"Wrote exact test environment to $environment")
        streams.value.log.info(s"Wrote ${classpathEntries.size} hashed classpath entries to $classpath")
      },
      testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-s", "-a", "+c", "+q"),
      buildIntellijOptionsIndex := {},
      intellijPlugins           := intellijPluginDependencies.value,
      // the bundled Scala plugin supplies the Scala runtime from its own classloader at runtime,
      // so neither scala-library nor scala3-library is bundled here
      packageLibraryMappings := Seq(
        "org.scala-lang" % "scala-library"     % scala2LibraryVersion.value -> None,
        "org.scala-lang" % "scala3-library_3" % scalaVersion.value   -> None
      )
    )
