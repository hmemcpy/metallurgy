package com.hmemcpy.metallurgy.ideprobe

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.jdk.CollectionConverters._

import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.Test
import org.virtuslab.ideprobe.{Config, IntelliJFixture}

private[ideprobe] final case class IdeaLaunchProcessResult(
    exitCode: Int,
    environment: Map[String, String],
    stderr: String
)

final class IdeaLaunchEnvironmentTest {
  private val DedicatedVariable   = "METALLURGY_IDEA_JAVA_OPTIONS"
  private val JavaOptionsVariable = "_JAVA_OPTIONS"
  private val JnaOption           = "-Djna.boot.library.path=$APP_PACKAGE/lib/jna/aarch64"
  private val AppleOptions        = Vector(
    "--add-opens=java.desktop/com.apple.eawt=ALL-UNNAMED",
    "--add-opens=java.desktop/com.apple.eawt.event=ALL-UNNAMED",
    "--add-opens=java.desktop/com.apple.laf=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED"
  )

  @Test
  def optionalVariableIsAbsentOrMappedOnlyToTheIDEChild(): Unit = {
    assertTrue(s"$JavaOptionsVariable must not reach the ide-probe driver", sys.env.get(JavaOptionsVariable).isEmpty)
    val driverEnvironment = IntelliJFixture
      .readIdeProbeConfig(Config.fromClasspath("ideprobe.conf"), "probe")
      .driver
      .env
    assertEquals(sys.env.get(DedicatedVariable), driverEnvironment.get(JavaOptionsVariable))
  }

  @Test
  def macOSArm64SelectsExactProductLaunchOptions(): Unit = withTemporaryDirectory("ideprobe-sdk") { root =>
    val sdk    = createSdk(root.resolve("sdk"), Seq(launch("macOS", "aarch64", JnaOption +: AppleOptions)))
    val result = runWrapper(root, sdk, "Darwin", "arm64")

    assertSuccess(result)
    val expected = s"-Djna.boot.library.path=${sdk.toRealPath()}/lib/jna/aarch64 ${AppleOptions.mkString(" ")}"
    assertEquals(Some(expected), result.environment.get(DedicatedVariable))
    assertEquals(None, result.environment.get(JavaOptionsVariable))
  }

  @Test
  def missingProductLaunchEntryFailsClearly(): Unit = withTemporaryDirectory("ideprobe-missing-launch") { root =>
    val sdk    = createSdk(root.resolve("sdk"), Seq(launch("macOS", "amd64", JnaOption +: AppleOptions)))
    val result = runWrapper(root, sdk, "Darwin", "arm64")

    assertFailureContains(result, "expected exactly one product-info launch entry for macOS/aarch64, found 0")
  }

  @Test
  def duplicateProductLaunchEntryFailsClearly(): Unit = withTemporaryDirectory("ideprobe-duplicate-launch") { root =>
    val entry  = launch("macOS", "aarch64", JnaOption +: AppleOptions)
    val sdk    = createSdk(root.resolve("sdk"), Seq(entry, entry))
    val result = runWrapper(root, sdk, "Darwin", "arm64")

    assertFailureContains(result, "expected exactly one product-info launch entry for macOS/aarch64, found 2")
  }

  @Test
  def missingRequiredProductOptionFailsClearly(): Unit = withTemporaryDirectory("ideprobe-missing-option") { root =>
    val missing = "--add-opens=java.desktop/com.apple.laf=ALL-UNNAMED"
    val sdk     =
      createSdk(root.resolve("sdk"), Seq(launch("macOS", "aarch64", JnaOption +: AppleOptions.filterNot(_ == missing))))
    val result  = runWrapper(root, sdk, "Darwin", "arm64")

    assertFailureContains(result, s"$missing (found 0)")
  }

  @Test
  def duplicateRequiredProductOptionFailsClearly(): Unit = withTemporaryDirectory("ideprobe-duplicate-option") { root =>
    val duplicate = "--add-opens=java.desktop/com.apple.laf=ALL-UNNAMED"
    val sdk       = createSdk(root.resolve("sdk"), Seq(launch("macOS", "aarch64", JnaOption +: (AppleOptions :+ duplicate))))
    val result    = runWrapper(root, sdk, "Darwin", "arm64")

    assertFailureContains(result, s"$duplicate (found 2)")
  }

  @Test
  def wrongJnaArchitectureFailsClearly(): Unit = withTemporaryDirectory("ideprobe-wrong-jna") { root =>
    val wrongJna = "-Djna.boot.library.path=$APP_PACKAGE/lib/jna/amd64"
    val sdk      = createSdk(root.resolve("sdk"), Seq(launch("macOS", "aarch64", wrongJna +: AppleOptions)))
    val result   = runWrapper(root, sdk, "Darwin", "arm64")

    assertFailureContains(result, "Selected JNA path does not match macOS/aarch64")
  }

  @Test
  def LinuxLeavesTheCommandEnvironmentUnchanged(): Unit = withTemporaryDirectory("ideprobe-linux-control") { root =>
    val result = runWrapper(root, root.resolve("absent-sdk"), "Linux", "aarch64")

    assertSuccess(result)
    assertEquals(None, result.environment.get(DedicatedVariable))
    assertEquals(None, result.environment.get(JavaOptionsVariable))
  }

  @Test
  def IntelMacOSLeavesTheCommandEnvironmentUnchanged(): Unit = withTemporaryDirectory("ideprobe-intel-control") {
    root =>
      val result = runWrapper(root, root.resolve("absent-sdk"), "Darwin", "x86_64")

      assertSuccess(result)
      assertEquals(None, result.environment.get(DedicatedVariable))
      assertEquals(None, result.environment.get(JavaOptionsVariable))
  }

  @Test
  def sdkPathWithSpacesIsQuotedForJavaOptionParsing(): Unit = withTemporaryDirectory("ideprobe-space-control") { root =>
    val sdk    = createSdk(root.resolve("SDK With Spaces"), Seq(launch("macOS", "aarch64", JnaOption +: AppleOptions)))
    val result = runWrapper(root, sdk, "Darwin", "arm64")

    assertSuccess(result)
    val expectedJna = s"\"-Djna.boot.library.path=${sdk.toRealPath()}/lib/jna/aarch64\""
    assertTrue(result.environment(DedicatedVariable).startsWith(expectedJna))
    assertEquals(None, result.environment.get(JavaOptionsVariable))
  }

  private def launch(os: String, architecture: String, arguments: Seq[String]): String = {
    val renderedArguments = arguments.map(value => s"\"$value\"").mkString(",")
    s"""{"os":"$os","arch":"$architecture","additionalJvmArguments":[$renderedArguments]}"""
  }

  private def createSdk(path: Path, entries: Seq[String]): Path = {
    val sdk = Files.createDirectories(path)
    val _   = Files.createDirectories(sdk.resolve("lib/jna/aarch64"))
    Files.writeString(
      sdk.resolve("product-info.json"),
      s"""{"launch":[${entries.mkString(",")}]}""",
      StandardCharsets.UTF_8
    )
    sdk
  }

  private def runWrapper(root: Path, sdk: Path, os: String, architecture: String): IdeaLaunchProcessResult = {
    val bin   = Files.createDirectories(root.resolve("bin"))
    val uname = bin.resolve("uname")
    Files.writeString(
      uname,
      s"""#!/bin/sh
         |case "$$1" in
         |  -s) printf '%s\\n' '$os' ;;
         |  -m) printf '%s\\n' '$architecture' ;;
         |  *) exit 2 ;;
         |esac
         |""".stripMargin,
      StandardCharsets.UTF_8
    )
    val _     = uname.toFile.setExecutable(true)

    val repoRoot         = Path.of(sys.env("METALLURGY_REPO_ROOT"))
    val wrapper          = repoRoot.resolve("ideprobe-tests/run-ide-probe.sh")
    val process          = new ProcessBuilder(
      wrapper.toString,
      "/usr/bin/env",
      "-0"
    )
    val environment      = process.environment()
    environment.put("PATH", s"$bin${java.io.File.pathSeparator}${environment.get("PATH")}")
    environment.put("METALLURGY_INTELLIJ_HOME", sdk.toString)
    environment.remove(DedicatedVariable)
    environment.remove(JavaOptionsVariable)
    val running          = process.start()
    val stdout           = new String(running.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    val stderr           = new String(running.getErrorStream.readAllBytes(), StandardCharsets.UTF_8)
    val exitCode         = running.waitFor()
    val childEnvironment = stdout
      .split("\u0000")
      .iterator
      .flatMap { entry =>
        entry.indexOf('=') match {
          case -1    => None
          case index => Some(entry.substring(0, index) -> entry.substring(index + 1))
        }
      }
      .toMap
    IdeaLaunchProcessResult(exitCode, childEnvironment, stderr)
  }

  private def assertSuccess(result: IdeaLaunchProcessResult): Unit =
    assertEquals(result.stderr, 0, result.exitCode)

  private def assertFailureContains(result: IdeaLaunchProcessResult, expected: String): Unit = {
    assertFalse(s"Expected failure containing '$expected'", result.exitCode == 0)
    assertTrue(result.stderr, result.stderr.contains(expected))
  }

  private def withTemporaryDirectory[A](prefix: String)(body: Path => A): A = {
    val root = Files.createTempDirectory(prefix)
    try body(root)
    finally {
      val stream = Files.walk(root)
      try stream.iterator().asScala.toVector.reverse.foreach(path => Files.deleteIfExists(path))
      finally stream.close()
    }
  }
}
