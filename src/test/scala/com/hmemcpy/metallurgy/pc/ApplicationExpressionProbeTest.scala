package com.hmemcpy.metallurgy.pc

import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.Test

import java.nio.file.Path

final class ApplicationExpressionProbeTest:
  @Test
  def exactOrdinaryApplicationInventoryClosesProductsFieldsRangesPathsAndSourceOrder(): Unit =
    withSnapshot: snapshot =>
      val nodes = snapshot.nodes.map(node => node.id -> node).toMap

      Vector(
        "zero"         -> "f()",
        "one"          -> "f(x)",
        "many"         -> "f( x , /* between */ 1 )",
        "selected"     -> "source.member(x)",
        "nested"       -> "f(g(x), 1)",
        "nestedMany"   -> "f(g(x, 1), 1)",
        "curriedCall"  -> "curried(x)(1)",
        "methodCall"   -> "f(x)",
        "templateCall" -> "member(1)"
      ).foreach: (name, source) =>
        val root = directRhs(nodes, name)
        assertEquals(name, "Apply", root.production)
        assertEquals(name, Vector("fun", "args"), root.fields.map(_.name))
        assertEquals(name, source, text(snapshot, root))
        assertEquals(name, positioned(child(nodes, root, "fun")).range.endOffset, positioned(root).point)
        assertDirectOwner(nodes, root, name)
        assertApplicationFields(nodes, root)
        assertCallPunctuation(snapshot, root, source)

      val zero = directRhs(nodes, "zero")
      assertEquals("Ident", child(nodes, zero, "fun").production)
      assertEquals(Vector.empty, repeatedChildren(nodes, zero, "args"))

      val one = directRhs(nodes, "one")
      assertEquals("Ident", child(nodes, one, "fun").production)
      assertEquals(Vector("Ident"), repeatedChildren(nodes, one, "args").map(_.production))

      val many          = directRhs(nodes, "many")
      assertEquals(Vector("Ident", "Number"), repeatedChildren(nodes, many, "args").map(_.production))
      assertEquals(Vector("x", "1"), repeatedChildren(nodes, many, "args").map(text(snapshot, _)))
      val manyFun       = child(nodes, many, "fun")
      val manyArguments = repeatedChildren(nodes, many, "args")
      assertEquals("( ", sourceBetween(snapshot, manyFun, manyArguments.head))
      assertEquals(" , /* between */ ", sourceBetween(snapshot, manyArguments.head, manyArguments.last))
      assertEquals(
        " )",
        snapshot.sourceText.substring(positioned(manyArguments.last).range.endOffset, positioned(many).range.endOffset)
      )
      val comment       = snapshot.comments.find(_.raw == "/* between */").get
      assertTrue(
        positioned(many).range.startOffset <= comment.range.startOffset &&
          comment.range.endOffset <= positioned(many).range.endOffset
      )

      val selected = directRhs(nodes, "selected")
      assertEquals("Select", child(nodes, selected, "fun").production)
      assertEquals("source.member", text(snapshot, child(nodes, selected, "fun")))

      val nested = directRhs(nodes, "nested")
      val inner  = repeatedChildren(nodes, nested, "args").head
      assertEquals("Apply", inner.production)
      assertEquals("g(x)", text(snapshot, inner))
      assertEquals(
        Vector(ParserFieldPathSegment.NamedField("args"), ParserFieldPathSegment.RepeatedIndex(0)),
        occurrenceFrom(inner, nested.id).fieldPath
      )

      val nestedMany = directRhs(nodes, "nestedMany")
      assertEquals(Vector("g(x, 1)", "1"), repeatedChildren(nodes, nestedMany, "args").map(text(snapshot, _)))

      val curried = directRhs(nodes, "curriedCall")
      val first   = child(nodes, curried, "fun")
      assertEquals("Apply", first.production)
      assertEquals("curried(x)", text(snapshot, first))
      assertEquals(Vector(ParserFieldPathSegment.NamedField("fun")), occurrenceFrom(first, curried.id).fieldPath)
      assertTrue(positioned(first).range.endOffset <= positioned(curried).range.endOffset)

  @Test
  def exactBoundaryInventoryKeepsNonOrdinaryRootsAndChildrenDistinct(): Unit =
    withSnapshot: snapshot =>
      val nodes    = snapshot.nodes.map(node => node.id -> node).toMap
      val expected = Vector(
        "typeApplied"      -> ("Apply", "TypeApply"),
        "namedTypeApplied" -> ("Apply", "TypeApply"),
        "namedArgument"    -> ("Apply", "Ident"),
        "usingArgument"    -> ("Apply", "Ident"),
        "repeatedArgument" -> ("Apply", "Ident"),
        "tupleArgument"    -> ("Apply", "Ident"),
        "blockArgument"    -> ("Apply", "Ident"),
        "lambdaArgument"   -> ("Apply", "Select"),
        "controlArgument"  -> ("Apply", "Ident"),
        "infix"            -> ("InfixOp", "Ident"),
        "constructor"      -> ("Apply", "Select")
      )
      expected.foreach:
        case (name, (rootPrefix, funPrefix)) =>
          val root = directRhs(nodes, name)
          assertEquals(name, rootPrefix, root.production)
          assertEquals(name, funPrefix, child(nodes, root, if rootPrefix == "InfixOp" then "op" else "fun").production)
          assertDirectOwner(nodes, root, name)

      assertEquals("NamedArg", repeatedChildren(nodes, directRhs(nodes, "namedArgument"), "args").head.production)
      assertEquals(
        "NamedArg",
        repeatedChildren(nodes, child(nodes, directRhs(nodes, "namedTypeApplied"), "fun"), "args").head.production
      )
      assertTrue(scannerKinds(snapshot, directRhs(nodes, "usingArgument")).contains(ParserScannerTokenKind.Identifier))
      assertEquals("Typed", repeatedChildren(nodes, directRhs(nodes, "repeatedArgument"), "args").head.production)
      assertEquals("Select", repeatedChildren(nodes, directRhs(nodes, "tupleArgument"), "args").head.production)
      assertEquals("Block", repeatedChildren(nodes, directRhs(nodes, "blockArgument"), "args").head.production)
      assertEquals("Function", repeatedChildren(nodes, directRhs(nodes, "lambdaArgument"), "args").head.production)
      assertEquals("If", repeatedChildren(nodes, directRhs(nodes, "controlArgument"), "args").head.production)
      assertEquals("New", child(nodes, child(nodes, directRhs(nodes, "constructor"), "fun"), "qualifier").production)

      val local = valDef(nodes, "local")
      assertEquals("Apply", child(nodes, local, "preRhs").production)
      assertTrue(local.occurrences.exists(occurrence => nodes(occurrence.ownerNodeId).production == "Block"))

      val defaultApply = snapshot.nodes
        .find(node =>
          text(snapshot, node) == "f()" && node.occurrences.exists: occurrence =>
            nodes(occurrence.ownerNodeId).production == "ValDef" && name(nodes(occurrence.ownerNodeId)) == "value"
        )
        .get
      assertFalse(defaultApply.occurrences.exists(occurrence => directDefinitionOwner(nodes, occurrence.ownerNodeId)))

      val annotatedOwner  = valDef(nodes, "annotated")
      val annotationApply = snapshot.nodes
        .find(node => node.production == "Apply" && node.occurrences.exists(_.ownerNodeId == annotatedOwner.id))
        .get
      assertEquals("Apply", annotationApply.production)
      assertTrue(
        annotationApply.occurrences.exists(_.fieldPath.contains(ParserFieldPathSegment.NamedField("annotations")))
      )

      val parentApply = snapshot.nodes.find(node => node.production == "Apply" && text(snapshot, node) == "C(f(1))").get
      assertEquals("Apply", parentApply.production)
      assertTrue(parentApply.occurrences.exists(occurrence => nodes(occurrence.ownerNodeId).production == "Template"))

      val refinementMember  = valDef(nodes, "refinementMember")
      val refinementApply   = child(nodes, refinementMember, "preRhs")
      assertEquals("Apply", refinementApply.production)
      assertFalse(
        refinementMember.occurrences.exists(occurrence => nodes(occurrence.ownerNodeId).production == "PackageDef")
      )
      val anonymousTemplate = nodes(
        refinementMember.occurrences
          .find(occurrence => nodes(occurrence.ownerNodeId).production == "Template")
          .get
          .ownerNodeId
      )
      assertTrue(anonymousTemplate.occurrences.exists(occurrence => nodes(occurrence.ownerNodeId).production == "New"))

  private def withSnapshot(body: ParserSyntaxSnapshot => Unit): Unit =
    val bridge = Scala3ParserBridge
      .open(
        Scala3ParserArtifactCoordinate("org.scala-lang", "scala3-compiler_3", "3.7.4"),
        compilerDistribution.map(_.toFile)
      )
      .fold(error => throw new AssertionError(error.toString), identity)
    try
      val snapshot = bridge
        .parse(
          Scala3ParserRequest(
            ParserSourceUri.from("file:///ApplicationExpressionProbe.scala").fold(sys.error, identity),
            Source,
            Vector.empty
          )
        )
        .fold(error => throw new AssertionError(error.toString), identity)
      assertTrue(snapshot.diagnostics.toString, snapshot.diagnostics.isEmpty)
      body(snapshot)
    finally bridge.close()

  private def directRhs(
      nodes: Map[Long, ParserSyntaxNode],
      definitionName: String
  ): ParserSyntaxNode = child(nodes, definition(nodes, definitionName), "preRhs")

  private def definition(nodes: Map[Long, ParserSyntaxNode], definitionName: String): ParserSyntaxNode =
    nodes.values.find(node => Set("DefDef", "ValDef")(node.production) && name(node) == definitionName).get

  private def valDef(nodes: Map[Long, ParserSyntaxNode], definitionName: String): ParserSyntaxNode =
    nodes.values.find(node => node.production == "ValDef" && name(node) == definitionName).get

  private def name(node: ParserSyntaxNode): String = node.fields
    .collectFirst:
      case ParserSyntaxField("name", ParserFieldValue.Name(value), _) => value
    .get

  private def child(nodes: Map[Long, ParserSyntaxNode], node: ParserSyntaxNode, field: String): ParserSyntaxNode =
    nodes(nodeId(node, field))

  private def nodeId(node: ParserSyntaxNode, field: String): Long = node.fields
    .collectFirst:
      case ParserSyntaxField(`field`, ParserFieldValue.Node(value), _) => value
    .get

  private def repeatedChildren(
      nodes: Map[Long, ParserSyntaxNode],
      node: ParserSyntaxNode,
      field: String
  ): Vector[ParserSyntaxNode] = node.fields
    .collectFirst:
      case ParserSyntaxField(`field`, ParserFieldValue.Repeated(values), _) =>
        values.map:
          case ParserFieldValue.Node(id) => nodes(id)
          case other                     => throw new AssertionError(s"expected node child: $other")
    .get

  private def assertApplicationFields(nodes: Map[Long, ParserSyntaxNode], application: ParserSyntaxNode): Unit =
    assertEquals(
      Vector(ParserFieldPathSegment.NamedField("fun")),
      occurrenceFrom(child(nodes, application, "fun"), application.id).fieldPath
    )
    repeatedChildren(nodes, application, "args").zipWithIndex.foreach: (argument, index) =>
      assertEquals(
        Vector(ParserFieldPathSegment.NamedField("args"), ParserFieldPathSegment.RepeatedIndex(index)),
        occurrenceFrom(argument, application.id).fieldPath
      )

  private def assertDirectOwner(
      nodes: Map[Long, ParserSyntaxNode],
      root: ParserSyntaxNode,
      definitionName: String
  ): Unit =
    val owner = definition(nodes, definitionName)
    assertEquals(
      Vector(ParserFieldPathSegment.NamedField("preRhs")),
      occurrenceFrom(root, owner.id).fieldPath
    )
    assertTrue(
      owner.occurrences.exists(occurrence =>
        Set("PackageDef", "Template")(nodes(occurrence.ownerNodeId).production) &&
          occurrence.fieldPath.headOption.contains(
            ParserFieldPathSegment.NamedField(
              if nodes(occurrence.ownerNodeId).production == "PackageDef" then "stats" else "preBody"
            )
          )
      )
    )

  private def directDefinitionOwner(nodes: Map[Long, ParserSyntaxNode], ownerId: Long): Boolean =
    val owner = nodes(ownerId)
    Set("DefDef", "ValDef")(owner.production) && owner.occurrences.exists(occurrence =>
      Set("PackageDef", "Template")(nodes(occurrence.ownerNodeId).production)
    )

  private def assertCallPunctuation(snapshot: ParserSyntaxSnapshot, root: ParserSyntaxNode, source: String): Unit =
    val tokens                   = scannerTokens(snapshot, root)
    assertEquals(source, source.count(_ == '('), tokens.count(_.kind == ParserScannerTokenKind.LeftParenthesis))
    assertEquals(source, source.count(_ == ')'), tokens.count(_.kind == ParserScannerTokenKind.RightParenthesis))
    assertEquals(source, source.count(_ == ','), tokens.count(_.kind == ParserScannerTokenKind.Comma))
    tokens.foreach(token => assertEquals(source, ParserPositionProvenance.SourceDerived, token.provenance))
    val nodes                    = snapshot.nodes.map(node => node.id -> node).toMap
    val funEnd                   = positioned(nodes(nodeId(root, "fun"))).range.endOffset
    val callTokens               = tokens.dropWhile(_.range.endOffset <= funEnd)
    assertEquals(source, ParserScannerTokenKind.LeftParenthesis, callTokens.head.kind)
    val (_, separators, closing) = callTokens.foldLeft((0, 0, Option.empty[ParserScannerToken])):
      case ((depth, separators, closing), token) =>
        token.kind match
          case ParserScannerTokenKind.LeftParenthesis     => (depth + 1, separators, closing)
          case ParserScannerTokenKind.RightParenthesis    =>
            val nextDepth = depth - 1
            (nextDepth, separators, if nextDepth == 0 then Some(token) else closing)
          case ParserScannerTokenKind.Comma if depth == 1 => (depth, separators + 1, closing)
          case _                                          => (depth, separators, closing)
    assertEquals(source, (repeatedChildren(nodes, root, "args").size - 1).max(0), separators)
    assertEquals(source, positioned(root).range.endOffset, closing.get.range.endOffset)

  private def scannerKinds(snapshot: ParserSyntaxSnapshot, root: ParserSyntaxNode): Vector[ParserScannerTokenKind] =
    scannerTokens(snapshot, root).map(_.kind)

  private def scannerTokens(snapshot: ParserSyntaxSnapshot, root: ParserSyntaxNode): Vector[ParserScannerToken] =
    val range = positioned(root).range
    snapshot.scannerTokens.filter(token =>
      token.range.startOffset >= range.startOffset && token.range.endOffset <= range.endOffset
    )

  private def sourceBetween(
      snapshot: ParserSyntaxSnapshot,
      left: ParserSyntaxNode,
      right: ParserSyntaxNode
  ): String = snapshot.sourceText.substring(positioned(left).range.endOffset, positioned(right).range.startOffset)

  private def occurrenceFrom(node: ParserSyntaxNode, ownerId: Long): ParserNodeOccurrence =
    node.occurrences.find(_.ownerNodeId == ownerId).get

  private def positioned(node: ParserSyntaxNode): ParserNodePosition.Positioned = node.position match
    case value: ParserNodePosition.Positioned => value
    case other                                => throw new AssertionError(s"expected positioned node: $other")

  private def text(snapshot: ParserSyntaxSnapshot, node: ParserSyntaxNode): String = node.position match
    case ParserNodePosition.Positioned(range, _, _) => snapshot.sourceText.substring(range.startOffset, range.endOffset)
    case ParserNodePosition.Absent                  => ""

  private def compilerDistribution: Seq[Path] =
    Scala3CompilerResolver.publicCoursier
      .resolve("3.7.4")
      .fold(error => throw error.toException, identity)

  private val Source =
    """class C(x: Int)
      |class D extends C(f(1))
      |class Ann(value: Int) extends scala.annotation.StaticAnnotation
      |def f(): Int = 1
      |def f(x: Int): Int = x
      |def f(x: Int, y: Int): Int = x + y
      |def f(using x: String): String = x
      |def g(x: Int): Int = x
      |def curried(x: Int)(y: Int): Int = x + y
      |def generic[A](x: A): A = x
      |def named(x: Int): Int = x
      |def repeated(xs: Int*): Int = xs.sum
      |object source:
      |  def member(x: Int): Int = x
      |  val templateCall = member(1)
      |val x = 1
      |val xs = Seq(1)
      |given String = "value"
      |val zero = f()
      |val one = f(x)
      |val many = f( x , /* between */ 1 )
      |val selected = source.member(x)
      |val nested = f(g(x), 1)
      |val nestedMany = f(g(x, 1), 1)
      |val curriedCall = curried(x)(1)
      |def methodCall = f(x)
      |val typeApplied = generic[Int](x)
      |val namedTypeApplied = generic[A = Int](x)
      |val namedArgument = named(x = 1)
      |val usingArgument = f(using summon[String])
      |val repeatedArgument = repeated(xs*)
      |val tupleArgument = f((x, 1)._1)
      |val blockArgument = f({ x })
      |val lambdaArgument = List(1).map(value => value)
      |val controlArgument = f(if x > 0 then 1 else 0)
      |val infix = x + 1
      |val constructor = new C(x)
      |def localOwner =
      |  val local = f(x)
      |  local
      |def defaultOwner(value: Int = f()): Int = value
      |@Ann(f())
      |val annotated = 1
      |val refinementOwner = new Object { val refinementMember = f() }
      |""".stripMargin
