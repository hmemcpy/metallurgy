package com.hmemcpy.metallurgy.pc

import org.junit.Assert.*
import org.junit.Test

import java.lang.reflect.Modifier
import java.net.URL
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** Feasibility probes for parser-owned, node-bounded Select separator provenance. Measures the exact parser surface
  * (P1), the separator correlation invariant over the token stream (P2), and a live capture of the original parse's own
  * scanner consumption through an injected recording scanner (P3). No production code depends on this.
  */
final class Scala3SelectSeparatorProvenanceProbeTest:
  private val ScalaVersion    = "3.7.4"
  private val ProbedArtifacts = Vector("3.5.2", ScalaVersion)

  private def distribution(version: String): Seq[Path] =
    Scala3CompilerResolver.publicCoursier
      .resolve(version)
      .fold(error => throw error.toException, identity)

  private final class ProbeLoader(urls: Array[URL])
      extends java.net.URLClassLoader(urls, ClassLoader.getPlatformClassLoader):
    def defineBytes(name: String, bytes: Array[Byte]): Class[?] =
      defineClass(name, bytes, 0, bytes.length)

  private def probeLoader(version: String): ProbeLoader =
    val urls = distribution(version).map(path => path.toUri.toURL).toArray
    new ProbeLoader(urls)

  @Test
  def parserSurfaceSupportsOwnedSeparatorCapture(): Unit =
    ProbedArtifacts.foreach: version =>
      val loader = probeLoader(version)
      try
        val sourceFileClass = loader.loadClass("dotty.tools.dotc.util.SourceFile")
        val contextClass    = loader.loadClass("dotty.tools.dotc.core.Contexts$Context")
        val parserClass     = loader.loadClass("dotty.tools.dotc.parsing.Parsers$Parser")
        val scannerClass    = loader.loadClass("dotty.tools.dotc.parsing.Scanners$Scanner")
        val profileClass    = loader.loadClass("dotty.tools.dotc.reporting.Profile")

        assertTrue(
          s"$version: Scanner must be a public non-final class",
          Modifier.isPublic(scannerClass.getModifiers) && !Modifier.isFinal(scannerClass.getModifiers)
        )
        assertTrue(
          s"$version: Scanner.nextToken must be public non-final",
          scannerClass.getMethods.exists(method =>
            method.getName == "nextToken" && method.getParameterCount == 0 &&
              Modifier.isPublic(method.getModifiers) && !Modifier.isFinal(method.getModifiers)
          )
        )
        assertTrue(
          s"$version: Scanner exact constructor must exist",
          scannerClass.getConstructors.exists(
            _.getParameterTypes.sameElements(
              Array(sourceFileClass, java.lang.Integer.TYPE, profileClass, java.lang.Boolean.TYPE, contextClass)
            )
          )
        )
        assertTrue(
          s"$version: Parser whole-source constructor must exist",
          parserClass.getConstructors.exists(_.getParameterTypes.sameElements(Array(sourceFileClass, contextClass)))
        )
        val inGetter = parserClass.getMethods
          .find(method => method.getName == "in" && method.getParameterCount == 0)
          .getOrElse(throw new NoSuchMethodException("Parser.in accessor"))
        assertTrue(s"$version: Parser.in accessor must be non-final", !Modifier.isFinal(inGetter.getModifiers))
        val inField  = parserClass.getDeclaredFields.find(field => field.getName == "in")
        assertTrue(s"$version: Parser.in backing field must exist", inField.isDefined)
        inField.foreach(field => assertEquals(s"$version: Parser.in field type", scannerClass, field.getType))
        println(s"[P1/$version] parser surface supports recording capture: in=${inField.map(_.getType.getName)}")
      finally loader.close()

  @Test
  def selectSeparatorOwnershipIsUniqueAndNodeBounded(): Unit =
    ProbedArtifacts.foreach: version =>
      val bridge = openBridge(version)
      try
        SelectShapes.foreach: shape =>
          val source   = wrapMatch(shape.source)
          val snapshot = parse(bridge, source, s"file:///SeparatorShapes-$version-${shape.id}.scala")
          val selects  = snapshot.nodes.filter(node => node.production == "Select")
          selects.foreach: select =>
            val position = select.position
            position match
              case ParserNodePosition.Positioned(range, _, _) =>
                select.fields.collectFirst:
                  case ParserSyntaxField("qualifier", ParserFieldValue.Node(qualifierId), _) => qualifierId
                match
                  case Some(qualifierId) =>
                    val qualifierEnd = snapshot.nodes
                      .find(_.id == qualifierId)
                      .flatMap(node =>
                        node.position match
                          case ParserNodePosition.Positioned(range, _, _) => Some(range.endOffset)
                          case _                                          => None
                      )
                    select.fields.collectFirst:
                      case ParserSyntaxField("name", ParserFieldValue.Name(name), _) => name
                    match
                      case Some(name) =>
                        qualifierEnd.foreach: end =>
                          val nameStart  = range.endOffset - name.length
                          val separators = snapshot.scannerTokens.filter(token =>
                            (token.kind == ParserScannerTokenKind.Dot || token.kind == ParserScannerTokenKind.Hash) &&
                              token.range.startOffset >= end && token.range.endOffset <= nameStart
                          )
                          assertEquals(
                            s"$version shape ${shape.id}: select '${snapshot.sourceText.substring(range.startOffset, range.endOffset)}' must own exactly one separator in [$end, $nameStart)",
                            1,
                            separators.size
                          )
                          separators.foreach(token =>
                            assertTrue(token.range.startOffset >= range.startOffset)
                            assertTrue(token.range.endOffset <= range.endOffset)
                          )
                      case None       => fail(s"$version shape ${shape.id}: Select without name")
                  case None              => fail(s"$version shape ${shape.id}: Select without qualifier node")
              case other                                      =>
                println(s"[P2/$version] shape ${shape.id}: non-positioned Select $other (excluded)")
          println(s"[P2/$version] shape ${shape.id}: ${selects.size} selects, all own exactly one separator")
      finally bridge.close()

  @Test
  @annotation.nowarn("cat=deprecation")
  def originalParseTokenStreamIsCapturableAndMatchesTheReplay(): Unit =
    val loader = probeLoader(ScalaVersion)
    try
      val _               = loader.defineBytes(
        "com.hmemcpy.metallurgy.pc.RecordingScannerProbe$Token",
        classBytes("com.hmemcpy.metallurgy.pc.RecordingScannerProbe$Token")
      )
      val recordingClass  = loader.defineBytes(
        "com.hmemcpy.metallurgy.pc.RecordingScannerProbe",
        classBytes("com.hmemcpy.metallurgy.pc.RecordingScannerProbe")
      )
      val sourceFileClass = loader.loadClass("dotty.tools.dotc.util.SourceFile")
      val contextClass    = loader.loadClass("dotty.tools.dotc.core.Contexts$Context")
      val reporterClass   = loader.loadClass("dotty.tools.dotc.reporting.Reporter")
      val storeReporter   = loader.loadClass("dotty.tools.dotc.reporting.StoreReporter")
      val driverClass     = loader.loadClass("dotty.tools.dotc.Driver")
      val compilerClass   = loader.loadClass("dotty.tools.dotc.Compiler")
      val parserClass     = loader.loadClass("dotty.tools.dotc.parsing.Parsers$Parser")
      val noProfile       = loader.loadClass("dotty.tools.dotc.reporting.NoProfile$").getField("MODULE$").get(null)

      val sourceText = wrapMatch(SelectShapes.map(_.source).mkString("\n"))
      val source     = loader
        .loadClass("dotty.tools.dotc.util.SourceFile$")
        .getField("MODULE$")
        .get(null)
      val virtual    = source.getClass.getMethods
        .find(method =>
          method.getName == "virtual" &&
            method.getParameterTypes.sameElements(Array(classOf[String], classOf[String], java.lang.Boolean.TYPE))
        )
        .getOrElse(throw new NoSuchMethodException("SourceFile.virtual(String, String, Boolean)"))
        .invoke(source, "Captured.scala", sourceText, java.lang.Boolean.TRUE)

      val base             = loader.loadClass("dotty.tools.dotc.core.Contexts$ContextBase").getConstructor().newInstance()
      val initial          = base.getClass.getMethod("initialCtx").invoke(base)
      val context          = initial.getClass.getMethod("fresh").invoke(initial)
      val reporter         = storeReporter.getConstructors
        .find(_.getParameterTypes.sameElements(Array(reporterClass, java.lang.Boolean.TYPE)))
        .map(_.newInstance(null, java.lang.Boolean.FALSE))
        .orElse(
          storeReporter.getConstructors
            .find(_.getParameterTypes.sameElements(Array(reporterClass)))
            .map(_.newInstance(null))
        )
        .getOrElse(throw new NoSuchMethodException("StoreReporter constructor"))
      val reporting        = context.getClass.getMethod("setReporter", reporterClass).invoke(context, reporter)
      val driver           = driverClass.getConstructor().newInstance()
      val classpath        = distribution(ScalaVersion).map(_.toAbsolutePath.toString).mkString(java.io.File.pathSeparator)
      val configuredOption = driver.getClass
        .getMethod("setup", classOf[Array[String]], contextClass)
        .invoke(driver, Array("-classpath", classpath, "Captured.scala"), reporting)
      val configured       = configuredOption.getClass.getMethod("get").invoke(configuredOption)
      val contextArgument  =
        configured.getClass.getMethod("productElement", classOf[Int]).invoke(configured, Int.box(1))
      val runContext       = compilerClass.getConstructor().newInstance()
      val run              = runContext.getClass
        .getMethod("newRun", contextClass)
        .invoke(runContext, contextArgument)
      val parseContext     = run.getClass.getMethod("runContext").invoke(run)

      val sink             = new java.util.ArrayList[AnyRef]
      val constructionSink = recordingClass.getField("constructionSink").get(null)
      constructionSink.getClass.getMethod("set", classOf[Object]).invoke(constructionSink, sink)
      val recordingScanner = recordingClass.getConstructors
        .find(_.getParameterTypes.length == 6)
        .getOrElse(throw new NoSuchMethodException("RecordingScannerProbe constructor"))
        .newInstance(virtual, Int.box(0), noProfile, java.lang.Boolean.TRUE, parseContext, sink)

      val parser      = parserClass.getConstructor(sourceFileClass, contextClass).newInstance(virtual, parseContext)
      val inField     = parserClass.getDeclaredField("in")
      inField.setAccessible(true)
      val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
      unsafeField.setAccessible(true)
      val unsafe      = unsafeField.get(null).asInstanceOf[sun.misc.Unsafe]
      unsafe.putObject(parser, unsafe.objectFieldOffset(inField), recordingScanner)

      val parsed = parser.getClass.getMethod("parse").invoke(parser)
      assertNotNull(parsed)

      val recorded = sink.asScala.toVector.map: token =>
        val cls  = token.getClass
        val tok  = cls.getField("token").get(token).asInstanceOf[java.lang.Integer].intValue()
        val off  = cls.getField("offset").get(token).asInstanceOf[java.lang.Integer].intValue()
        val last = cls.getField("lastOffset").get(token).asInstanceOf[java.lang.Integer].intValue()
        (tok, off, last)
      assertTrue(s"recording must capture tokens: ${recorded.size}", recorded.size > 10)

      val bridge             = openBridge(ScalaVersion)
      val snapshot           =
        try parse(bridge, sourceText, "file:///Captured.scala")
        finally bridge.close()
      val recordedProjection = recorded.map((token, offset, _) => (token, offset))
      val replayProjection   = snapshot.scannerTokens.map(token => (token.tokenId, token.range.startOffset))
      assertEquals(
        "the original parse must consume the replayed token stream in the same order",
        replayProjection,
        recordedProjection.take(replayProjection.size)
      )
      println(s"[P3] original-parse capture works: ${recorded.size} tokens recorded and equal to the replay stream")
    finally loader.close()

  @Test
  def parsesNeverDriveTheHostCopyOfTheRecordingScanner(): Unit =
    // The bridge defines its own bytecode copy inside the isolated compiler loader. A
    // sentinel placed in the HOST copy's construction sink detects any driving of that
    // copy: tokens would be recorded into it or its thread-local would be cleared.
    val sentinel = new java.util.ArrayList[SeparatorRecordingScanner.Token]
    SeparatorRecordingScanner.constructionSink.set(sentinel)
    try
      val bridge = openBridge(ScalaVersion)
      try
        val source   = wrapMatch(SelectShapes.head.source)
        val snapshot = parse(bridge, source, "file:///HostIsolationCheck.scala")
        assertTrue(snapshot.nodeSeparators.nonEmpty)
      finally bridge.close()
      assertSame(
        "nothing may clear the host copy's construction sink",
        sentinel,
        SeparatorRecordingScanner.constructionSink.get
      )
      assertTrue(
        "no parse token may be recorded through the host copy",
        sentinel.isEmpty
      )
    finally SeparatorRecordingScanner.constructionSink.set(null)

  @Test
  def productionSnapshotsCarryParserOwnedSeparators(): Unit =
    ProbedArtifacts.foreach: version =>
      val bridge = openBridge(version)
      try
        assertTrue(
          s"$version: separator provenance capability must be available",
          bridge.capabilities.separatorProvenance.isInstanceOf[ParserCapabilityStatus.Available.type] ||
            bridge.capabilities.separatorProvenance == ParserCapabilityStatus.Available
        )
        SelectShapes.foreach: shape =>
          val source          = wrapMatch(shape.source)
          val snapshot        = parse(bridge, source, s"file:///ProductionSeparators-$version-${shape.id}.scala")
          val selects         = snapshot.nodes.count(node => node.production == "Select")
          val expectedKinds   = Set(ParserScannerTokenKind.Dot, ParserScannerTokenKind.Hash)
          val rootSeparators  = snapshot.nodeSeparators
          assertTrue(
            s"$version shape ${shape.id}: separator evidence expected ($selects selects, ${rootSeparators.size} facts)",
            rootSeparators.nonEmpty &&
              rootSeparators.forall(fact =>
                expectedKinds(fact.kind) && fact.provenance == ParserPositionProvenance.SourceDerived
              )
          )
          val eligibleSelects = snapshot.nodes.count: node =>
            node.production == "Select" && node.position.isInstanceOf[ParserNodePosition.Positioned] &&
              node.fields.exists(field =>
                field.name == "qualifier" && field.value.isInstanceOf[ParserFieldValue.Node]
              ) &&
              node.fields.exists(field => field.name == "name" && field.value.isInstanceOf[ParserFieldValue.Name])
          assertEquals(
            s"$version shape ${shape.id}: every eligible Select owns exactly one separator fact",
            eligibleSelects,
            rootSeparators.size
          )
          assertEquals(
            s"$version shape ${shape.id}: separator owners are distinct Selects",
            rootSeparators.map(_.ownerNodeId).distinct.size,
            rootSeparators.size
          )
          rootSeparators.foreach: separator =>
            val select = snapshot.nodes.find(_.id == separator.ownerNodeId)
            assertTrue(
              s"$version shape ${shape.id}: separator owner must be a Select",
              select.exists(_.production == "Select")
            )
            select.foreach: node =>
              node.position match
                case ParserNodePosition.Positioned(range, _, _) =>
                  assertTrue(separator.range.startOffset >= range.startOffset)
                  assertTrue(separator.range.endOffset <= range.endOffset)
                case other                                      => fail(s"$version shape ${shape.id}: owner not positioned: $other")
        println(s"[P4/$version] production snapshots carry separators: ${SelectShapes.size} shapes verified")
      finally bridge.close()

  private case class Shape(id: String, source: String)

  private val SelectShapes = Vector(
    Shape("simple-dot", """case y: a.B => 1"""),
    Shape("recursive-dots", """case y: pkg.a.b.c.d.T => 2"""),
    Shape("simple-hash", """case y: Outer#T => 3"""),
    Shape("recursive-hashes", """case y: Outer#T#U => 4"""),
    Shape("qualified-hash", """case y: pkg.Outer#T => 5"""),
    Shape("dot-with-trivia", """case y: a. /* mid */ B => 6"""),
    Shape("dot-across-lines", """case y: a.\n  B => 7"""),
    Shape("applied-argument", """case y: Box[a.B] => 8"""),
    Shape("tuple-component", """case y: (a.B, Outer#T) => 9"""),
    Shape("wildcard-bound", """case y: Box[? <: a.B] => 10"""),
    Shape("dot-after-hash-recovered", """case y: Outer#T.U => 11"""),
    Shape("backticked-segment", """case y: pkg.`type`.T => 12"""),
    Shape("indentation-stress", """case y: a.B\n    .C => 13""")
  )

  private def wrapMatch(body: String): String =
    s"""def captured(x: Any): Any = x match
      |  $body
      |""".stripMargin.stripSuffix("\n").replaceAll("\\n", "\n")

  private def classBytes(name: String): Array[Byte] =
    val resource = s"/${name.replace('.', '/')}.class"
    val stream   = getClass.getResourceAsStream(resource)
    if stream == null then fail(s"class resource missing: $resource")
    stream.readAllBytes()

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
