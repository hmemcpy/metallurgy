package com.hmemcpy.metallurgy.pc

import org.junit.Assert.*
import org.junit.Test

import java.nio.file.Path

// Named tuple types in typed patterns parse into Tuple(trees = NamedArg...) under
// Typed.tpt, but the exact compiler's pattern typing rejects every such spelling on both
// supported versions: a tuple with named elements typed under Mode.Pattern routes through
// the named-pattern adaptation, which demands a case-class or named-tuple expected type
// and structurally receives the wildcard for a typed pattern's type tree. This probe pins
// the parse-level structural cause, the per-version parser disagreement on the
// single-paren spellings, and the absence of parser diagnostics where the parser accepts.
final class Scala3MatchNamedTupleTypePreflightProbeTest:
  private val Versions = Vector("3.5.2", "3.7.4")

  private case class Shape(id: String, source: String, parseErrorVersions: Set[String] = Set.empty)

  private case class ComponentRange(namedArgStart: Int, namedArgEnd: Int, argStart: Int, argEnd: Int)

  private case class NodeRanges(tupleStart: Int, tupleEnd: Int, components: Vector[ComponentRange])

  private case class ShapeExpectation(
      route: String,
      completeRoute: Vector[(String, String)],
      components: Vector[(String, String)],
      fingerprint: String,
      nodeRanges: NodeRanges
  )

  private def ShapeExpectations(shapeId: String, version: String): ShapeExpectation =
    val twoComponents = Vector("a" -> "A", "b" -> "B")
    (shapeId, version) match
      case ("wrapped", "3.5.2")          =>
        ShapeExpectation(
          "Parens.NamedField(t)",
          Vector(("Parens", "NamedField(t)"), ("Typed", "NamedField(tpt)")),
          twoComponents,
          "b458d030cfb162723da9e50d3523de5cae59f86e5723914e2c953213b211ac45",
          NodeRanges(61, 73, Vector(ComponentRange(62, 66, 65, 66), ComponentRange(68, 72, 71, 72)))
        )
      case ("applied-argument", "3.5.2") =>
        ShapeExpectation(
          "AppliedTypeTree.NamedField(args)/RepeatedIndex(0)",
          Vector(("AppliedTypeTree", "NamedField(args)/RepeatedIndex(0)"), ("Typed", "NamedField(tpt)")),
          twoComponents,
          "44dd6738d6af62004aaf0845c07b37f4008eff22b6d80fc437aff5dd042c916b",
          NodeRanges(78, 90, Vector(ComponentRange(79, 83, 82, 83), ComponentRange(85, 89, 88, 89)))
        )
      case ("wildcard-bound", "3.5.2")   =>
        ShapeExpectation(
          "TypeBoundsTree.NamedField(hi)",
          Vector(
            ("TypeBoundsTree", "NamedField(hi)"),
            ("AppliedTypeTree", "NamedField(args)/RepeatedIndex(0)"),
            ("Typed", "NamedField(tpt)")
          ),
          twoComponents,
          "42e3784fae2f3fc46ca0f7078ab50bf0c1092694b6a549a218ba3c6dae8781d3",
          NodeRanges(83, 95, Vector(ComponentRange(84, 88, 87, 88), ComponentRange(90, 94, 93, 94)))
        )
      case ("direct", "3.7.4")           =>
        ShapeExpectation(
          "Typed.NamedField(tpt)",
          Vector(("Typed", "NamedField(tpt)")),
          twoComponents,
          "aeccca6b24a17d8ee542bfd4ec490fedf06dc9a62cc34f75ebc091561b5e1681",
          NodeRanges(60, 72, Vector(ComponentRange(61, 65, 64, 65), ComponentRange(67, 71, 70, 71)))
        )
      case ("wrapped", "3.7.4")          =>
        ShapeExpectation(
          "Parens.NamedField(t)",
          Vector(("Parens", "NamedField(t)"), ("Typed", "NamedField(tpt)")),
          twoComponents,
          "8592f83d0bec6ee41ccfdb5ac4cc3453bfa1f1aed6678e9460decab96a0abe2f",
          NodeRanges(61, 73, Vector(ComponentRange(62, 66, 65, 66), ComponentRange(68, 72, 71, 72)))
        )
      case ("applied-argument", "3.7.4") =>
        ShapeExpectation(
          "AppliedTypeTree.NamedField(args)/RepeatedIndex(0)",
          Vector(("AppliedTypeTree", "NamedField(args)/RepeatedIndex(0)"), ("Typed", "NamedField(tpt)")),
          twoComponents,
          "042f46fd25477cb9ea52eddafa15df7d803104639c642ce47ec8daf0c1de2603",
          NodeRanges(78, 90, Vector(ComponentRange(79, 83, 82, 83), ComponentRange(85, 89, 88, 89)))
        )
      case ("wildcard-bound", "3.7.4")   =>
        ShapeExpectation(
          "TypeBoundsTree.NamedField(hi)",
          Vector(
            ("TypeBoundsTree", "NamedField(hi)"),
            ("AppliedTypeTree", "NamedField(args)/RepeatedIndex(0)"),
            ("Typed", "NamedField(tpt)")
          ),
          twoComponents,
          "5de85997aed7e99297941c58b8f3485da50f892407f34f086bde76df3422c6a4",
          NodeRanges(83, 95, Vector(ComponentRange(84, 88, 87, 88), ComponentRange(90, 94, 93, 94)))
        )
      case ("given-anon", "3.7.4")       =>
        ShapeExpectation(
          "Typed.NamedField(tpt)",
          Vector(("Typed", "NamedField(tpt)"), ("Bind", "NamedField(body)")),
          twoComponents,
          "2c18bf3dbe2303e9e2d1324482bd5565e726e035946bc1baa5295ead04de349a",
          NodeRanges(63, 75, Vector(ComponentRange(64, 68, 67, 68), ComponentRange(70, 74, 73, 74)))
        )
      case ("single-component", "3.7.4") =>
        ShapeExpectation(
          "Typed.NamedField(tpt)",
          Vector(("Typed", "NamedField(tpt)")),
          Vector("a" -> "A"),
          "09327ff873c44ce4a81f0357ed423a84ba3ca5c33e303964febad1e2a56ab7bd",
          NodeRanges(51, 57, Vector(ComponentRange(52, 56, 55, 56)))
        )
      case other                         => sys.error(s"no expectation for $other")

  private val Shapes = Vector(
    Shape(
      "direct",
      """class A; class B
        |def probe(x: Any): Any = x match
        |  case y: (a: A, b: B) => 1
        |""".stripMargin,
      parseErrorVersions = Set("3.5.2")
    ),
    Shape(
      "wrapped",
      """class A; class B
        |def probe(x: Any): Any = x match
        |  case y: ((a: A, b: B)) => 1
        |""".stripMargin
    ),
    Shape(
      "applied-argument",
      """class A; class B; class Box[T]
        |def probe(x: Any): Any = x match
        |  case y: Box[(a: A, b: B)] => 1
        |""".stripMargin
    ),
    Shape(
      "wildcard-bound",
      """class A; class B; class Box[T]
        |def probe(x: Any): Any = x match
        |  case y: Box[? <: (a: A, b: B)] => 1
        |""".stripMargin
    ),
    Shape(
      "given-anon",
      """class A; class B
        |def probe(x: Any): Any = x match
        |  case given (a: A, b: B) => 1
        |""".stripMargin,
      parseErrorVersions = Set("3.5.2")
    ),
    Shape(
      "single-component",
      """class A
        |def probe(x: Any): Any = x match
        |  case y: (a: A) => 1
        |""".stripMargin,
      parseErrorVersions = Set("3.5.2")
    )
  )

  @Test
  def probeStructuralShapeWithoutParserDiagnostics(): Unit =
    Versions.foreach: version =>
      val bridge = openBridge(version)
      try
        Shapes.foreach: shape =>
          val snapshot = parse(bridge, shape.source, s"file:///named-tuple-probe-$version-${shape.id}.scala")
          if shape.parseErrorVersions.contains(version) then
            assert(
              snapshot.diagnostics.nonEmpty,
              s"$version ${shape.id} must be a parser error"
            )
          else
            assert(
              snapshot.diagnostics.isEmpty,
              s"$version ${shape.id} must parse without parser diagnostics: ${snapshot.diagnostics}"
            )
          if shape.parseErrorVersions.contains(version) then
            assertEquals(s"$version ${shape.id} diagnostic count", 1, snapshot.diagnostics.size)
            val diagnostic                   = snapshot.diagnostics.head
            assertEquals(s"$version ${shape.id} diagnostic severity", "Error", diagnostic.severity.toString)
            val position                     = diagnostic.position
            assert(position.isDefined, s"$version ${shape.id} diagnostic must carry a position")
            position.foreach { pos =>
              assert(
                pos.range.startOffset >= 0 && pos.range.endOffset <= shape.source.length,
                s"$version ${shape.id} diagnostic must be anchored inside the source"
              )
            }
            // The scanner evidence carries no diagnostic wording, so pinning it keeps the
            // parse-error cells independent of exact compiler message text.
            val parseErrorScannerFingerprint = shape.id match
              case "direct"           =>
                "fe1bfd311f457929a79bf5cbfee87e040bcafbfd2fe6a674e01e0c4a3deceafd"
              case "given-anon"       =>
                "928cb4a52a2594ebd17c74fad4373a7c9b35b40d72080bec1150b0aaf5e30867"
              case "single-component" =>
                "da92330c28ce2c2f338395b59c36032277d4a71001e9d4a4492068644414a77a"
              case other              => sys.error(s"no parse-error expectation for $other")
            assertEquals(
              s"$version ${shape.id} parse-error scanner fingerprint",
              parseErrorScannerFingerprint,
              ParserSyntaxSnapshot.scannerEvidenceFingerprint(snapshot)
            )
            val diag                         = snapshot.diagnostics.head
            val expectedRange                = shape.id match
              case "direct"           => (62, 63, 62)
              case "given-anon"       => (65, 66, 65)
              case "single-component" => (53, 54, 53)
              case other              => sys.error(s"no diagnostic position expectation for $other")
            diag.position.foreach { pos =>
              assertEquals(
                s"$version ${shape.id} diagnostic range",
                (pos.range.startOffset, pos.range.endOffset, pos.point),
                expectedRange
              )
            }
            println(s"=== $version ${shape.id} parser-error diagnostics=1 ===")
          else probeShape(version, shape, snapshot)
      finally bridge.close()

  private def probeShape(version: String, shape: Shape, snapshot: ParserSyntaxSnapshot): Unit =
    val byId                                            = snapshot.nodes.map(n => n.id -> n).toMap
    val tuples                                          = snapshot.nodes.filter(node => node.production == "Tuple" && hasNamedArgChildren(node, byId))
    assert(
      tuples.nonEmpty,
      s"$version ${shape.id} must expose a Tuple with NamedArg children"
    )
    val expected                                        = ShapeExpectations(shape.id, version)
    assertEquals(1, tuples.size)
    val tuple                                           = tuples.head
    assertEquals(s"$version ${shape.id} tuple fields", Vector("trees"), tuple.fields.map(_.name))
    val owners                                          = tuple.occurrences.map { occurrence =>
      val owner = byId(occurrence.ownerNodeId)
      s"${owner.production}.${occurrence.fieldPath.mkString("/")}"
    }
    assertEquals(s"$version ${shape.id} tuple route", Vector(expected.route), owners)
    def edges(nodeId: Long): Vector[(String, String)]   =
      byId(nodeId).occurrences.headOption
        .map { occurrence =>
          val owner = byId(occurrence.ownerNodeId)
          val edge  = (owner.production, occurrence.fieldPath.mkString("/"))
          Vector(edge) ++ edges(occurrence.ownerNodeId)
        }
        .getOrElse(Vector.empty)
    val typeRoute                                       = edges(tuple.id).takeWhile((production, _) => production != "CaseDef")
    assertEquals(s"$version ${shape.id} complete type route", expected.completeRoute, typeRoute)
    if System.getenv("NT_PROBE_DEBUG") != null then
      def chain(nodeId: Long): Vector[String] =
        val node   = byId(nodeId)
        val parent = node.occurrences.headOption
          .map { o =>
            val ownerText = byId(o.ownerNodeId).production + "." + o.fieldPath.mkString("/")
            ownerText + " -> " + chain(o.ownerNodeId).mkString(" -> ")
          }
          .getOrElse("")
        Vector(node.production) ++ (if parent.isEmpty then Vector.empty else Vector(parent))
      println(s"DBGCHAIN $version ${shape.id} ${chain(tuple.id).mkString(" | ")}")
    assert(
      ancestorsContain(byId, tuple.id, "Typed"),
      s"$version ${shape.id} tuple must sit under a Typed pattern node: $owners"
    )
    val namedArgs                                       = tuple.fields
      .collectFirst { case f if f.name == "trees" => f.value.asInstanceOf[ParserFieldValue.Repeated].values }
      .getOrElse(Vector.empty)
    assertEquals(s"$version ${shape.id} component count", expected.components.size, namedArgs.size)
    namedArgs.zip(expected.components).foreach { case (value, (componentName, argType)) =>
      val namedArg  = value match
        case ParserFieldValue.Node(id) => byId(id)
        case other                     => sys.error(s"unexpected tuple element: $other")
      assertEquals(s"$version ${shape.id} NamedArg fields", Vector("name", "arg"), namedArg.fields.map(_.name))
      val nameValue = namedArg.fields
        .collectFirst {
          case f if f.name == "name" => f.value.toString
        }
        .getOrElse("?")
      assertEquals(s"$version ${shape.id} component name", s"Name($componentName)", nameValue)
      namedArg.fields.collectFirst {
        case f if f.name == "arg" =>
          f.value match
            case ParserFieldValue.Node(id2) =>
              val ident   = byId(id2)
              assertEquals(s"$version ${shape.id} component arg", "Ident", ident.production)
              assertEquals(
                s"$version ${shape.id} component arg fields",
                Vector("name"),
                ident.fields.map(_.name)
              )
              val argName = ident.fields
                .collectFirst {
                  case f2 if f2.name == "name" => f2.value.toString
                }
                .getOrElse("?")
              assertEquals(s"$version ${shape.id} component arg name", s"Name($argType)", argName)
            case other                      => sys.error(s"unexpected component arg: $other")
      }
    }
    assertEquals(
      s"$version ${shape.id} evidence fingerprint",
      expected.fingerprint,
      ParserSyntaxSnapshot.evidenceFingerprint(snapshot)
    )
    val ParserNodePosition.Positioned(tupleRange, _, _) = tuple.position: @unchecked
    val expectedRanges                                  = expected.nodeRanges
    assertEquals(
      s"$version ${shape.id} tuple range",
      (expectedRanges.tupleStart, expectedRanges.tupleEnd),
      (tupleRange.startOffset, tupleRange.endOffset)
    )
    namedArgs.zip(expectedRanges.components).zipWithIndex.foreach { case ((value, expectedComponent), componentIndex) =>
      val ParserFieldValue.Node(id)                          = value: @unchecked
      val namedArg                                           = byId(id)
      val ParserNodePosition.Positioned(namedArgRange, _, _) = namedArg.position: @unchecked
      assertEquals(
        s"$version ${shape.id} component range",
        (expectedComponent.namedArgStart, expectedComponent.namedArgEnd),
        (namedArgRange.startOffset, namedArgRange.endOffset)
      )
      val ParserFieldValue.Node(argId)                       = namedArg.fields.find(_.name == "arg").get.value: @unchecked
      val ParserNodePosition.Positioned(argRange, _, _)      = byId(argId).position: @unchecked
      assertEquals(
        s"$version ${shape.id} component arg range",
        (expectedComponent.argStart, expectedComponent.argEnd),
        (argRange.startOffset, argRange.endOffset)
      )
      val namedArgOwners                                     = namedArg.occurrences.map { occurrence =>
        val owner = byId(occurrence.ownerNodeId)
        s"${owner.production}.${occurrence.fieldPath.mkString("/")}"
      }
      assertEquals(
        s"$version ${shape.id} NamedArg occurrence route",
        Vector(s"Tuple.NamedField(trees)/RepeatedIndex($componentIndex)"),
        namedArgOwners
      )
      val argOwners                                          = byId(argId).occurrences.map { occurrence =>
        val owner = byId(occurrence.ownerNodeId)
        s"${owner.production}.${occurrence.fieldPath.mkString("/")}"
      }
      assertEquals(
        s"$version ${shape.id} arg Ident occurrence route",
        Vector("NamedArg.NamedField(arg)"),
        argOwners
      )
    }

  private def hasNamedArgChildren(node: ParserSyntaxNode, byId: Map[Long, ParserSyntaxNode]): Boolean =
    node.fields.exists { field =>
      field.name == "trees" &&
      field.value.isInstanceOf[ParserFieldValue.Repeated] &&
      field.value
        .asInstanceOf[ParserFieldValue.Repeated]
        .values
        .nonEmpty &&
      field.value
        .asInstanceOf[ParserFieldValue.Repeated]
        .values
        .forall {
          case ParserFieldValue.Node(id) => byId.get(id).exists(_.production == "NamedArg")
          case _                         => false
        }
    }

  private def ancestorsContain(byId: Map[Long, ParserSyntaxNode], id: Long, production: String): Boolean =
    def parentOf(nodeId: Long): Option[Long] =
      byId(nodeId).occurrences.headOption.map(_.ownerNodeId)
    LazyList
      .iterate(parentOf(id))(_.flatMap(parentOf))
      .takeWhile(_.isDefined)
      .flatten
      .exists(byId(_).production == production)

  @Test
  def probeExactCompilerTypingRejection(): Unit =
    Versions.foreach(version => confirmLaunchedCompilerVersion(version))
    val rejected = Vector(
      "direct"           -> Shapes.find(_.id == "direct").get.source,
      "wrapped"          -> Shapes.find(_.id == "wrapped").get.source,
      "applied-argument" -> Shapes.find(_.id == "applied-argument").get.source,
      "wildcard-bound"   -> Shapes.find(_.id == "wildcard-bound").get.source,
      "given-anon"       -> Shapes.find(_.id == "given-anon").get.source,
      "single-component" -> Shapes.find(_.id == "single-component").get.source
    )
    val admitted = Vector(
      "alias-route"   ->
        """import scala.language.experimental.namedTuples
          |class A; class B
          |type NT = (a: A, b: B)
          |def probe(x: Any): Any = x match
          |  case y: NT => 1
          |  case given NT => 2
          |""".stripMargin,
      "term-patterns" ->
        """import scala.language.experimental.namedTuples
          |class A; class B
          |type NT = (a: A, b: B)
          |def terms(x: NT): Any = x match
          |  case (a = p, b = q) => 1
          |  case (p: A, q: B) => 2
          |""".stripMargin
    )
    Versions.foreach: version =>
      rejected.foreach { case (id, source) =>
        assertEquals(
          s"$version $id must be rejected by the exact compiler's typing",
          CompileOutcome.Rejected,
          compileOutcome(version, source)
        )
      }
      admitted.foreach { case (id, source) =>
        assertEquals(
          s"$version $id must compile cleanly on its existing owner",
          CompileOutcome.Clean,
          compileOutcome(version, source)
        )
      }

  private enum CompileOutcome:
    case Clean, Rejected, InfrastructureFailure

  private def compileOutcome(version: String, source: String): CompileOutcome =
    var sourceFile: java.io.File      = null
    var outputDir: java.nio.file.Path = null
    var outputLog: java.nio.file.Path = null
    val cleanupFailures               = scala.collection.mutable.ArrayBuffer[String]()
    try
      sourceFile = java.io.File.createTempFile("named-tuple-compile", ".scala")
      outputDir = java.nio.file.Files.createTempDirectory("named-tuple-out")
      outputLog = java.nio.file.Files.createTempFile("named-tuple-compile", ".log")
      val scala3Library             = distribution(version)
        .find(_.getFileName.toString.startsWith("scala3-library_3"))
        .map(_.toString)
        .getOrElse(sys.error(s"scala3-library jar missing for $version"))
      val scala2Library             = distribution(version)
        .find(_.getFileName.toString.startsWith("scala-library-"))
        .map(_.toString)
        .getOrElse(sys.error(s"scala-library jar missing for $version"))
      val fullClasspath             =
        (scala3Library +: scala2Library +: distribution(version).map(_.toString)).mkString(java.io.File.pathSeparator)
      val libraryClasspath          = s"$scala3Library${java.io.File.pathSeparator}$scala2Library"
      java.nio.file.Files.writeString(sourceFile.toPath, source)
      val (exitCode, output)        = runForked(
        s"$legacyJavaHome/bin/java",
        Vector(
          "-classpath",
          fullClasspath,
          "dotty.tools.dotc.Main",
          sourceFile.getPath,
          "-d",
          outputDir.toString,
          "-classpath",
          libraryClasspath,
          "-bootclasspath",
          libraryClasspath,
          "-color:never"
        ),
        outputLog,
        timeoutSeconds = 120,
        label = s"$version compile"
      )
      val infrastructureFailure     =
        output.contains("unhandled exception") ||
          output.contains("Exception in thread") ||
          output.contains("NoClassDefFoundError") ||
          output.contains("Unable to initialize") ||
          output.contains("LinkageError") ||
          output.contains("NoSuchMethodError") ||
          output.contains("UnsupportedClassVersionError") ||
          output.contains("Could not find or load main class") ||
          output.linesIterator.exists(line => line.startsWith("\tat "))
      // A completed failed compile ends with dotc's error-count summary; a crash never
      // prints it, so this positive completion marker is conclusive without a denylist.
      val completedWithErrorSummary =
        output.linesIterator.exists { line =>
          val trimmed   = line.trim
          val wordCount = Map("one" -> 1, "two" -> 2)
          trimmed match
            case strictly if strictly.matches("(one|two|\\d+) errors? found") =>
              wordCount.getOrElse(strictly.takeWhile(_.isLetter), 0) > 0 ||
              strictly.takeWhile(_.isDigit).toIntOption.exists(_ > 0)
            case _                                                            => false
        }
      // dotc prefixes warnings with the same marker as errors, so an error diagnostic is a
      // marker line naming the fixture that is not a warning.
      val fixtureDiagnostic         = output.linesIterator.exists { line =>
        (line.startsWith("-- [E") || line.startsWith("-- Error")) &&
        line.contains(sourceFile.getPath) && !line.contains("Warning")
      }
      val anyErrorDiagnostic        = output.linesIterator.exists { line =>
        (line.startsWith("-- [E") || line.startsWith("-- Error")) && !line.contains("Warning")
      }
      if exitCode == 0 && !infrastructureFailure && !completedWithErrorSummary && !anyErrorDiagnostic
      then CompileOutcome.Clean
      else if exitCode != 0 && !infrastructureFailure && completedWithErrorSummary && fixtureDiagnostic
      then CompileOutcome.Rejected
      else
        println(s"=== $version compile exit=$exitCode without a fixture-anchored diagnostic ===")
        output.linesIterator.foreach(println)
        CompileOutcome.InfrastructureFailure
    finally
      def track(step: => Unit): Unit =
        try step
        catch case failure: Throwable => cleanupFailures += failure.toString
      track(
        if sourceFile == null then ()
        else if !sourceFile.delete() then cleanupFailures += s"failed to delete ${sourceFile.getPath}"
      )
      track(
        if outputLog == null then ()
        else if !java.nio.file.Files.deleteIfExists(outputLog) then cleanupFailures += s"failed to delete $outputLog"
      )
      track(if outputDir == null then () else deleteRecursively(outputDir.toFile, cleanupFailures))
      val failures                   = cleanupFailures.toVector
      assert(failures.isEmpty, s"temporary cleanup failed: ${failures.mkString(", ")}")

  private def runForked(
      javaExecutable: String,
      arguments: Vector[String],
      outputLog: java.nio.file.Path,
      timeoutSeconds: Long,
      label: String
  ): (Int, String) =
    val argumentsWithExecutable = (javaExecutable +: arguments).toArray
    val process                 = new java.lang.ProcessBuilder(argumentsWithExecutable*)
      .redirectErrorStream(true)
      .redirectOutput(outputLog.toFile)
      .start()
    try
      val finished =
        try process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        catch
          case interrupted: InterruptedException =>
            process.destroyForcibly()
            val killed = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            Thread.currentThread().interrupt()
            if !killed then sys.error(s"$label survived a forcible kill after interruption")
            throw interrupted
      if !finished then
        process.destroyForcibly()
        val exited = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        sys.error(s"$label did not terminate${if exited then "" else " even after a forcible kill"}")
      (process.exitValue(), java.nio.file.Files.readString(outputLog))
    finally
      val _                     = process.destroyForcibly()
      var exited                = false
      var interruptedDuringKill = false
      var attempts              = 0
      while !exited && attempts < 3 do
        attempts += 1
        try exited = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        catch
          case _: InterruptedException =>
            interruptedDuringKill = true
            val _ = process.destroyForcibly()
            if process.isAlive then Thread.currentThread().interrupt()
      if interruptedDuringKill then Thread.currentThread().interrupt()
      assert(!process.isAlive, s"$label survived the final cleanup kill")

  private def deleteRecursively(file: java.io.File, failures: scala.collection.mutable.ArrayBuffer[String]): Unit =
    val children = file.listFiles()
    if children != null then children.foreach(deleteRecursively(_, failures))
    if !file.delete() then failures += s"failed to delete ${file.getPath}"

  private def confirmLaunchedCompilerVersion(version: String): Unit =
    val scala3Library = distribution(version)
      .find(_.getFileName.toString.startsWith("scala3-library_3"))
      .map(_.toString)
      .getOrElse(sys.error(s"scala3-library jar missing for $version"))
    val scala2Library = distribution(version)
      .find(_.getFileName.toString.startsWith("scala-library-"))
      .map(_.toString)
      .getOrElse(sys.error(s"scala-library jar missing for $version"))
    val fullClasspath =
      (scala3Library +: scala2Library +: distribution(version).map(_.toString)).mkString(java.io.File.pathSeparator)
    val outputLog     = java.nio.file.Files.createTempFile("named-tuple-version", ".log")
    try
      val (exitCode, output) = runForked(
        s"$legacyJavaHome/bin/java",
        Vector("-classpath", fullClasspath, "dotty.tools.dotc.Main", "-version"),
        outputLog,
        timeoutSeconds = 60,
        label = "version probe"
      )
      val reportedVersion    = output.linesIterator
        .collectFirst {
          case line if line.contains("version ") =>
            line.trim.replaceAll(".*version ", "").takeWhile(c => c.isDigit || c == '.')
        }
        .getOrElse("")
      assert(
        exitCode == 0 && reportedVersion == version,
        s"launched compiler must report exactly the resolved version $version but reported '$reportedVersion': $output"
      )
    finally assert(java.nio.file.Files.deleteIfExists(outputLog), s"failed to delete $outputLog")

  // The exact older compiler versions crash on current JVMs while typechecking; run the
  // compile probes on an installed Java 17 home. There is no fallback: if the host cannot
  // provide one, the probe must fail rather than silently report from a crashing runtime.
  private val legacyJavaHome: String =
    val outputLog                    = java.nio.file.Files.createTempFile("named-tuple-javahome", ".log")
    val (exitCode, output)           =
      try runForked("/usr/libexec/java_home", Vector("-v", "17"), outputLog, timeoutSeconds = 15, label = "java_home")
      finally
        assert(java.nio.file.Files.deleteIfExists(outputLog), s"failed to delete $outputLog")
    val path                         = output.trim
    assert(
      exitCode == 0 && path.nonEmpty,
      s"an installed Java 17 home is required for the exact compile probes: $output"
    )
    val versionLog                   = java.nio.file.Files.createTempFile("named-tuple-javaversion", ".log")
    val (versionExit, versionOutput) =
      try runForked(s"$path/bin/java", Vector("-version"), versionLog, timeoutSeconds = 30, label = "java -version")
      finally assert(java.nio.file.Files.deleteIfExists(versionLog), s"failed to delete $versionLog")
    val reportedJavaMajor            = versionOutput.linesIterator
      .collectFirst {
        case line if line.contains("version \"") =>
          line.trim.replaceAll(".*version \"", "").takeWhile(_ != '"').split("\\.").headOption.getOrElse("")
      }
      .getOrElse("")
    assert(
      versionExit == 0 && reportedJavaMajor == "17",
      s"the selected Java home must run a Java 17 executable (reported major '$reportedJavaMajor'): $versionOutput"
    )
    path

  private def distribution(version: String): Seq[Path] =
    Scala3CompilerResolver.publicCoursier.resolve(version).fold(error => throw error.toException, identity)

  private def openBridge(version: String): Scala3ParserBridge =
    Scala3ParserBridge
      .open(
        Scala3ParserArtifactCoordinate("org.scala-lang", "scala3-compiler_3", version),
        distribution(version).map(_.toFile)
      )
      .fold(error => throw new AssertionError(error.toString), identity)

  private def parse(bridge: Scala3ParserBridge, source: String, uri: String): ParserSyntaxSnapshot =
    bridge
      .parse(
        Scala3ParserRequest(
          ParserSourceUri.from(uri).fold(sys.error, identity),
          source,
          Vector.empty,
          Scala3ParserCancellation.Never
        )
      )
      .fold(error => throw new AssertionError(error.toString), identity)
