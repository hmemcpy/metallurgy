package com.hmemcpy.metallurgy.projectmodel

import com.hmemcpy.metallurgy.build.ScalacFlagsService
import com.hmemcpy.metallurgy.compilerbackend.ScalaPluginSemanticBridge
import com.hmemcpy.metallurgy.module.ModuleDetectionService
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.{CompilerModuleExtension, ModuleRootManager, OrderEnumerator}
import org.jetbrains.jps.model.java.JavaSourceRootProperties

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

/** Loader-neutral compiler input derived exclusively from IntelliJ's committed module model. */
private[metallurgy] final case class CompilerBackendModuleDescriptor(
    moduleName: String,
    scalaVersion: String,
    sourceRoots: Vector[CompilerBackendSourceRoot],
    dependencyModules: Vector[String],
    compileClasspath: Vector[File],
    compilerOptions: Vector[String],
    compilerPlugins: Vector[String],
    jdk: Option[CompilerBackendJdk],
    productionOutput: Option[String],
    testOutput: Option[String],
    fingerprint: String
)

private[metallurgy] final case class CompilerBackendSourceRoot(
    url: String,
    kind: String,
    generated: Boolean
)

private[metallurgy] final case class CompilerBackendJdk(name: String, version: Option[String], home: Option[String])

private[metallurgy] enum CompilerBackendModelState:
  case Inactive
  case Pending(detail: String)
  case Unavailable(detail: String)
  case Ready(descriptor: CompilerBackendModuleDescriptor)

private[metallurgy] object CompilerBackendModuleDescriptor:

  def read(project: Project, module: Module): CompilerBackendModelState =
    if module.isDisposed || !ModuleDetectionService.get(project).isActive(module) then
      CompilerBackendModelState.Inactive
    else
      Option(ScalaPluginSemanticBridge.getScalaVersion(module)) match
        case None               => CompilerBackendModelState.Pending("Scala compiler coordinate is not available")
        case Some(scalaVersion) =>
          val rootManager = ModuleRootManager.getInstance(module)
          val classpath   = OrderEnumerator
            .orderEntries(module)
            .recursively
            .compileOnly
            .withoutSdk
            .classes
            .getPathsList
            .getPathList
            .asScala
            .map(new File(_))
            .toVector
            .distinct
          if classpath.isEmpty then CompilerBackendModelState.Pending("Compile classpath is not available")
          else
            val options      = ScalacFlagsService.get(project).presentationCompilerOptions(module).toVector
            val roots        = rootManager.getContentEntries.toVector
              .flatMap(_.getSourceFolders.toVector)
              .map: folder =>
                val properties = folder.getJpsElement.getProperties
                val generated  = properties match
                  case java: JavaSourceRootProperties => java.isForGeneratedSources
                  case _                              => false
                CompilerBackendSourceRoot(folder.getUrl, folder.getRootType.toString, generated)
              .sortBy(root => (root.url, root.kind, root.generated))
            val dependencies = rootManager.getDependencies.toVector.map(_.getName).distinct
            val sdk          = Option(rootManager.getSdk).map: value =>
              CompilerBackendJdk(
                value.getName,
                Option(value.getVersionString),
                Option(value.getHomePath)
              )
            val compiler     = CompilerModuleExtension.getInstance(module)
            val production   = Option(compiler).flatMap(value => Option(value.getCompilerOutputUrl))
            val tests        = Option(compiler).flatMap(value => Option(value.getCompilerOutputUrlForTests))
            val plugins      = compilerPlugins(options)
            val fields       = Vector(
              scalaVersion,
              roots.map(root => s"${root.url}|${root.kind}|${root.generated}").mkString("\u0000"),
              dependencies.mkString("\u0000"),
              classpath.map(_.getAbsolutePath).mkString("\u0000"),
              options.mkString("\u0000"),
              plugins.mkString("\u0000"),
              sdk.fold("")(value => s"${value.name}|${value.version.getOrElse("")}|${value.home.getOrElse("")}"),
              production.getOrElse(""),
              tests.getOrElse("")
            )
            CompilerBackendModelState.Ready(
              CompilerBackendModuleDescriptor(
                module.getName,
                scalaVersion,
                roots,
                dependencies,
                classpath,
                options,
                plugins,
                sdk,
                production,
                tests,
                sha256(fields.mkString("\n"))
              )
            )

  private def compilerPlugins(options: Vector[String]): Vector[String] =
    options.zipWithIndex.flatMap: (option, index) =>
      if option.startsWith("-Xplugin:") then Vector(option.stripPrefix("-Xplugin:"))
      else if option == "-Xplugin" then options.lift(index + 1).toVector
      else Vector.empty

  def diff(
      previous: CompilerBackendModuleDescriptor,
      current: CompilerBackendModuleDescriptor
  ): Vector[String] =
    Vector(
      Option.when(previous.scalaVersion != current.scalaVersion)("scalaVersion"),
      Option.when(previous.sourceRoots != current.sourceRoots)("sourceRoots"),
      Option.when(previous.dependencyModules != current.dependencyModules)("dependencyModules"),
      Option.when(previous.compileClasspath != current.compileClasspath)("compileClasspath"),
      Option.when(previous.compilerOptions != current.compilerOptions)("compilerOptions"),
      Option.when(previous.compilerPlugins != current.compilerPlugins)("compilerPlugins"),
      Option.when(previous.jdk != current.jdk)("jdk"),
      Option.when(previous.productionOutput != current.productionOutput)("productionOutput"),
      Option.when(previous.testOutput != current.testOutput)("testOutput")
    ).flatten

  private def sha256(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.getBytes(StandardCharsets.UTF_8))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString
