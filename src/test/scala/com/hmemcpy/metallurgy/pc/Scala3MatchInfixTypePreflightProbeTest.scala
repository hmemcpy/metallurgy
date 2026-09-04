package com.hmemcpy.metallurgy.pc

import org.junit.Assert.*
import org.junit.Test

import java.nio.file.Path

final class Scala3MatchInfixTypePreflightProbeTest:
  private val Versions = Vector("3.5.2", "3.7.4")

  private case class Shape(id: String, pattern: String, source: Option[String] = None)

  private val SharedClasses =
    "class E1; class E2\nclass A; class B; class C; class D\nclass Box[T]\nclass P; class T\nclass X\n"

  private val Shapes = Vector(
    Shape("union-paren", "y: (A | B)"),
    Shape("intersection-paren", "y: (A & B)"),
    Shape("union-chain", "y: (A | B | C)"),
    Shape("intersection-chain", "y: (A & B & C)"),
    Shape("precedence-mixed", "y: (A | B & C)"),
    Shape("precedence-parens", "y: ((A | B) & C)"),
    Shape("applied-argument", "y: Box[A | B]"),
    Shape("tuple-components", "y: (A & B, C | D)"),
    Shape("wildcard-bound", "y: Box[? <: A | B]"),
    Shape("given-anon", "given (A | B)"),
    Shape("given-named", "n @ given (A & B)"),
    Shape("given-wildcard", "_ @ given (A | B)"),
    Shape("given-backticked", "`bt` @ given (A | B)"),
    Shape("pattern-and-type", "(x: A | B)"),
    Shape("nested-parens", "y: ((A | B) | C)"),
    Shape("owned-family-mix", "y: Box[A & stable.type | (C, D)]"),
    Shape("trivia", "y: (A /* c1 */ | /* c2 */ B)"),
    Shape("newline", "y: (A\n  | B)"),
    Shape("unparenthesized-direct", "y: A | B"),
    Shape("custom-infix", "y: (A :+: B)"),
    Shape("missing-right-operand", "y: (A |)"),
    Shape("missing-left-operand", "y: (| A)"),
    Shape("missing-operator", "y: (A B)"),
    Shape("term-alternative-pattern", "A | B"),
    Shape("deferred-function", "y: (A => B | C)"),
    Shape("deferred-refinement", "y: (A { type X } | B)"),
    Shape("deferred-annotation", "y: (A @unchecked | B)"),
    Shape("deferred-type-lambda", "y: ([X] =>> (A | B))"),
    Shape(
      "context-catch",
      "",
      Some(
        SharedClasses +
          """def trapping(x: Any): Any =
        |  try x.asInstanceOf[Int]
        |  catch { case y: (E1 | E2) => 0 }
        |""".stripMargin
      )
    ),
    Shape(
      "context-partial-function",
      "",
      Some(
        SharedClasses +
          """def partial(x: Any): Any =
        |  val pf: PartialFunction[Any, Any] = { case y: (E1 | E2) => y }
        |  pf(x)
        |""".stripMargin
      )
    ),
    Shape(
      "context-generator",
      "",
      Some(
        SharedClasses +
          """def generating(x: Any): Any =
        |  for case y: (E1 | E2) <- Seq(x) yield y
        |""".stripMargin
      )
    ),
    Shape(
      "context-quote",
      "",
      Some(
        SharedClasses +
          """def quoted(using q: scala.quoted.Quotes): scala.quoted.Expr[Any] =
        |  '{ (v: Any) => v match { case y: (E1 | E2) => y } }
        |""".stripMargin
      )
    ),
    Shape(
      "context-definition",
      "",
      Some(
        SharedClasses +
          """def defined(x: Any): (E1 | E2) = ???
        |""".stripMargin
      )
    ),
    Shape("lower-bound", "y: Box[? >: A | B]"),
    Shape("context-function", "y: (String ?=> (A | B))"),
    Shape("dependent-function", "y: ((x: A) => (B | C))"),
    Shape("poly-function", "y: ([X] => (B | C) => A)"),
    Shape("named-tuple", "y: ((a: A, b: B))"),
    Shape("match-type", "y: ((A match { case t => t }) | B)"),
    Shape("by-name", "y: ((=> A) | B)"),
    Shape("unit-operand", "y: (Unit | B)"),
    Shape("projection-qualifier", "y: ((P)#T | B)"),
    Shape("capture", "y: (A^ | B)"),
    Shape("repeated", "y: (A* | B)")
  )

  private val Admitted           = Set(
    "union-paren",
    "intersection-paren",
    "union-chain",
    "intersection-chain",
    "precedence-mixed",
    "precedence-parens",
    "applied-argument",
    "tuple-components",
    "wildcard-bound",
    "given-anon",
    "given-named",
    "given-wildcard",
    "given-backticked",
    "nested-parens",
    "owned-family-mix",
    "trivia",
    "lower-bound"
  )
  private val ParseError         = Set(
    "newline",
    "missing-right-operand",
    "missing-left-operand",
    "missing-operator",
    "capture",
    "repeated",
    "by-name"
  )
  private val DeferredParseValid = Set(
    "context-function",
    "dependent-function",
    "poly-function",
    "named-tuple",
    "match-type",
    "unit-operand",
    "projection-qualifier"
  )
  private val ExcludedContext    = Set(
    "context-catch",
    "context-partial-function",
    "context-generator",
    "context-quote"
  )
  private val DeferredOperand    = Set("deferred-refinement", "deferred-annotation", "deferred-type-lambda")

  @Test
  def dumpExactShapes(): Unit =
    val structures = Shapes.map(s => s.id -> scala.collection.mutable.Map.empty[String, Vector[String]]).toMap
    Versions.foreach: version =>
      val bridge = openBridge(version)
      try
        Shapes.foreach: shape =>
          val source                                               = shape.source.getOrElse:
            s"""def probe(x: Any): Any = x match
               |  case ${shape.pattern} => 1
               |""".stripMargin
          val snapshot                                             = parse(bridge, source, s"file:///infix-probe-$version-${shape.id}.scala")
          val byId                                                 = snapshot.nodes.map(n => n.id -> n).toMap
          def parentOf(id: Long): Option[Long]                     =
            byId(id).occurrences.headOption.map(_.ownerNodeId)
          def ancestors(id: Long): Set[Long]                       =
            LazyList.iterate(parentOf(id))(_.flatMap(parentOf)).takeWhile(_.isDefined).flatten.toSet
          def ancestorProductions(id: Long): Set[String]           =
            ancestors(id).map(byId(_).production)
          def fieldNodes(value: ParserFieldValue): Vector[Long]    =
            value match
              case ParserFieldValue.Node(n)            => Vector(n)
              case ParserFieldValue.Positioned(n)      => Vector(n)
              case ParserFieldValue.Optional(inner)    => inner.toVector.flatMap(fieldNodes)
              case ParserFieldValue.Repeated(values)   => values.flatMap(fieldNodes)
              case ParserFieldValue.Product(_, fields) => fields.flatMap(f => fieldNodes(f.value))
              case _                                   => Vector.empty
          def childNodes(id: Long): Vector[Long]                   =
            byId(id).fields.flatMap(field => fieldNodes(field.value))
          def subtree(id: Long): Set[Long]                         =
            val children = childNodes(id)
            children.toSet + id ++ children.flatMap(subtree)
          def subtreeProductions(id: Long): Set[String]            =
            subtree(id).map(byId(_).production)
          def operatorName(node: ParserSyntaxNode): Option[String] =
            node.fields.find(_.name == "op").flatMap { field =>
              field.value match
                case ParserFieldValue.Node(id) =>
                  byId(id).fields.find(_.name == "name").flatMap { nameField =>
                    nameField.value match
                      case ParserFieldValue.Name(value) => Some(value)
                      case _                            => None
                  }
                case _                         => None
            }
          val infixOps                                             = snapshot.nodes.filter(_.production == "InfixOp")
          val operators                                            = infixOps.flatMap(operatorName)
          def typedInfixes                                         = infixOps.filter(op => ancestorProductions(op.id).contains("Typed"))
          if Admitted(shape.id) then
            assert(snapshot.diagnostics.isEmpty, s"$version ${shape.id} must parse without diagnostics")
            assert(infixOps.nonEmpty, s"$version ${shape.id} must contain an InfixOp")
            assert(
              operators.forall(v => v == "|" || v == "&"),
              s"$version ${shape.id} operators must be exactly | or &: $operators"
            )
            assert(
              infixOps.forall(op => ancestorProductions(op.id).contains("Typed")),
              s"$version ${shape.id} infix operands must sit under a Typed node"
            )
            assertAdmittedInfixStructure(shape.id, source, snapshot, byId, operatorName, infixOps)
            assertAdmittedOccurrencePaths(shape.id, version, byId, infixOps)
            assertAdmittedExactExpectations(shape.id, version, byId, infixOps)
            if infixOps.sizeIs > 1 then
              val nested   = infixOps.exists { op =>
                val operandIds = op.fields.collect {
                  case f if f.name == "left" || f.name == "right" =>
                    f.value match
                      case ParserFieldValue.Node(n) => n
                      case _                        => 0L
                }
                operandIds.exists(id => id != 0L && subtreeProductions(id).contains("InfixOp")) ||
                ancestorProductions(op.id).contains("InfixOp")
              }
              val ranges   = infixOps.map(op => op.position.asInstanceOf[ParserNodePosition.Positioned].range)
              val disjoint = ranges.forall(r1 =>
                ranges.forall(r2 => r1 == r2 || r1.endOffset <= r2.startOffset || r2.endOffset <= r1.startOffset)
              )
              assert(
                nested || disjoint,
                s"$version ${shape.id} with several infix operators must nest or separate them"
              )
            if shape.id == "precedence-mixed" then
              val outer = infixOps.find(op => operatorName(op).contains("|")).get
              val inner = infixOps.find(op => operatorName(op).contains("&")).get
              assert(
                ancestors(inner.id).contains(outer.id),
                s"$version precedence-mixed must nest the intersection under the union"
              )
          else if ParseError(shape.id) then
            assert(
              snapshot.diagnostics.nonEmpty,
              s"$version ${shape.id} must be a parse error"
            )
          else
            shape.id match
              case "custom-infix"                                                             =>
                assert(snapshot.diagnostics.isEmpty, s"$version custom-infix must parse without diagnostics")
                assert(infixOps.nonEmpty, s"$version custom-infix must parse as InfixOp")
                assert(
                  operators.forall(v => v != "|" && v != "&"),
                  s"$version custom-infix must not use the owned operator names: $operators"
                )
              case "unparenthesized-direct" | "term-alternative-pattern" | "pattern-and-type" =>
                assert(snapshot.diagnostics.isEmpty, s"$version ${shape.id} must parse without diagnostics")
                assert(
                  typedInfixes.isEmpty,
                  s"$version ${shape.id} must keep its union outside typed-pattern type space"
                )
              case "deferred-function"                                                        =>
                assert(snapshot.diagnostics.isEmpty, s"$version deferred-function must parse without diagnostics")
                assert(
                  typedInfixes.forall(op => ancestorProductions(op.id).contains("Function")),
                  s"$version deferred-function must keep its union inside the unowned function family"
                )
              case id if DeferredOperand(id)                                                  =>
                assert(snapshot.diagnostics.isEmpty, s"$version $id must parse without diagnostics")
                val expectedFamily = id match
                  case "deferred-refinement"  => "RefinedTypeTree"
                  case "deferred-annotation"  => "Annotated"
                  case "deferred-type-lambda" => "LambdaTypeTree"
                val familyNodes    = snapshot.nodes.filter(_.production == expectedFamily)
                val satisfied      = familyNodes.nonEmpty && infixOps.nonEmpty && infixOps.exists { op =>
                  val operandIds = op.fields.collect {
                    case f if f.name == "left" || f.name == "right" =>
                      f.value match
                        case ParserFieldValue.Node(n) => n
                        case _                        => 0L
                  }
                  ancestorProductions(op.id).contains(expectedFamily) ||
                  familyNodes.exists(family => ancestors(op.id).contains(family.id)) ||
                  operandIds.exists(id => id != 0L && subtreeProductions(id).contains(expectedFamily))
                }
                assert(familyNodes.nonEmpty, s"$version $id must contain a $expectedFamily node")
                assert(
                  satisfied,
                  s"$version $id must keep its infix bound to the $expectedFamily family"
                )
              case id if ExcludedContext(id)                                                  =>
                assert(snapshot.diagnostics.isEmpty, s"$version $id must parse without diagnostics")
                assert(infixOps.nonEmpty, s"$version $id must parse its union as InfixOp")
                assert(
                  operators.forall(v => v == "|" || v == "&"),
                  s"$version $id must use the owned operator names"
                )
              case id if DeferredParseValid(id)                                               =>
                assert(snapshot.diagnostics.isEmpty, s"$version $id must parse without diagnostics")
                assert(
                  operators.forall(v => v == "|" || v == "&"),
                  s"$version $id must use the owned operator names at parse level"
                )
                assert(
                  infixOps.forall(op => ancestorProductions(op.id).contains("Typed")),
                  s"$version $id union must sit under a Typed node at parse level"
                )
              case "context-definition"                                                       =>
                assert(snapshot.diagnostics.isEmpty, s"$version context-definition must parse without diagnostics")
                assert(
                  infixOps.nonEmpty && infixOps.forall { op =>
                    val productions = ancestorProductions(op.id)
                    productions.contains("DefDef") && !productions.contains("Match")
                  },
                  s"$version context-definition must own its union in definition position"
                )
              case other                                                                      => sys.error(s"unclassified probe shape: $other")
          if Admitted(shape.id) then structures(shape.id)(version) = structuralEvidence(source, snapshot)
          if shape.id == "union-paren" then assertUnionParenStructure(source, snapshot, byId, operatorName)
          val relevant                                             = snapshot.nodes
            .filter(node =>
              Set(
                "CaseDef",
                "Typed",
                "Bind",
                "Parens",
                "Tuple",
                "AppliedTypeTree",
                "TypeBoundsTree",
                "InfixOp",
                "SingletonTypeTree",
                "Select",
                "Ident",
                "Literal",
                "Function",
                "RefinedTypeTree",
                "Annotated",
                "LambdaTypeTree",
                "Match",
                "Try",
                "DefDef",
                "ValDef",
                "GivenBind",
                "Alternative"
              )(node.production)
            )
            .sortBy(node =>
              node.position match
                case ParserNodePosition.Positioned(range, _, _) => (range.startOffset, -range.endOffset)
                case _                                          => (Int.MaxValue, 0)
            )
            .map: node =>
              val position    = node.position match
                case ParserNodePosition.Positioned(range, point, provenance) =>
                  s"${range.startOffset}:${range.endOffset}:$point:$provenance"
                case other                                                   => other.toString
              val occurrences = node.occurrences.map(occurrence =>
                s"${snapshot.nodes.find(_.id == occurrence.ownerNodeId).map(_.production).getOrElse("?")}.${occurrence.fieldPath.mkString("/")}"
              )
              s"${node.id}:${node.production}@$position fields=${node.fields.map(field => s"${field.name}=${field.value}").mkString("[")}${"]"} occurrences=${occurrences.mkString("[")}${"]"}"
          val tokens                                               = snapshot.scannerTokens
            .map(token =>
              s"${token.kind}:${token.range.startOffset}:${token.range.endOffset}:'${source.substring(token.range.startOffset, token.range.endOffset)}'"
            )
          println(s"=== $version ${shape.id} fingerprint=${ParserSyntaxSnapshot.evidenceFingerprint(snapshot)} ===")
          relevant.foreach(println)
          println(s"TOKENS ${tokens.mkString(" | ")}")
          println(s"DIAGNOSTICS ${snapshot.diagnostics.mkString(" | ")}")
      finally bridge.close()
    Admitted.foreach { id =>
      val perVersion = structures(id)
      assert(
        Versions.forall(perVersion.contains),
        s"$id must be structurally captured on every version: ${perVersion.keySet}"
      )
      assert(
        perVersion.values.toSet.size == 1,
        s"$id must produce identical parser evidence on both versions: ${perVersion
            .map { case (v, lines) => v -> lines.size }}"
      )
    }

  private def structuralEvidence(source: String, snapshot: ParserSyntaxSnapshot): Vector[String] =
    val byId                                         = snapshot.nodes.map(n => n.id -> n).toMap
    def posOf(node: ParserSyntaxNode): String        = node.position match
      case ParserNodePosition.Positioned(range, point, provenance) =>
        s"${range.startOffset}:${range.endOffset}:$point:$provenance"
      case other                                                   => other.toString
    def renderValue(value: ParserFieldValue): String = value match
      case ParserFieldValue.Node(id)               => s"Node(${byId.get(id).map(n => n.production + "@" + posOf(n)).getOrElse("?")})"
      case ParserFieldValue.Positioned(id)         =>
        s"Positioned(${byId.get(id).map(n => n.production + "@" + posOf(n)).getOrElse("?")})"
      case ParserFieldValue.Optional(inner)        => s"Optional(${inner.map(renderValue).getOrElse("_")})"
      case ParserFieldValue.Repeated(values)       => s"Repeated(${values.map(renderValue).mkString(",")})"
      case ParserFieldValue.Product(p, fields)     =>
        s"Product($p,${fields.map(f => f.name + "=" + renderValue(f.value)).mkString(",")})"
      case ParserFieldValue.Name(v)                => s"Name($v)"
      case ParserFieldValue.GeneratedName(b, s, i) => s"GeneratedName($b,$s,$i)"
      case ParserFieldValue.Scalar(s)              => s"Scalar($s)"
      case ParserFieldValue.Unsupported(t)         => s"Unsupported($t)"
    snapshot.nodes.map { node =>
      val fields = node.fields.map(f => f.name + "=" + renderValue(f.value)).mkString(",")
      val occ    = node.occurrences
        .map { o =>
          val owner = byId.get(o.ownerNodeId).map(n => n.production).getOrElse("?")
          s"$owner.${o.fieldPath.mkString("/")}"
        }
        .mkString(",")
      s"${node.production}@${posOf(node)} fields=$fields occ=$occ"
    }.sorted ++ snapshot.scannerTokens.map(t =>
      s"TOKEN:${t.kind}:${t.range.startOffset}:${t.range.endOffset}:${source.substring(t.range.startOffset, t.range.endOffset)}"
    )

  private case class ChildExpectation(start: Int, end: Int, point: Int)
  private case class InfixExpectation(
      start: Int,
      end: Int,
      point: Int,
      left: ChildExpectation,
      op: ChildExpectation,
      right: ChildExpectation,
      occurrencePath: String
  )

  private val AdmittedExpectations: Map[String, Vector[InfixExpectation]] = Map(
    "union-paren"        -> Vector(
      InfixExpectation(
        44,
        49,
        46,
        ChildExpectation(44, 45, 44),
        ChildExpectation(46, 47, 46),
        ChildExpectation(48, 49, 48),
        "Parens.NamedField(t)"
      )
    ),
    "intersection-paren" -> Vector(
      InfixExpectation(
        44,
        49,
        46,
        ChildExpectation(44, 45, 44),
        ChildExpectation(46, 47, 46),
        ChildExpectation(48, 49, 48),
        "Parens.NamedField(t)"
      )
    ),
    "union-chain"        -> Vector(
      InfixExpectation(
        44,
        53,
        50,
        ChildExpectation(44, 49, 46),
        ChildExpectation(50, 51, 50),
        ChildExpectation(52, 53, 52),
        "Parens.NamedField(t)"
      ),
      InfixExpectation(
        44,
        49,
        46,
        ChildExpectation(44, 45, 44),
        ChildExpectation(46, 47, 46),
        ChildExpectation(48, 49, 48),
        "InfixOp.NamedField(left)"
      )
    ),
    "intersection-chain" -> Vector(
      InfixExpectation(
        44,
        53,
        50,
        ChildExpectation(44, 49, 46),
        ChildExpectation(50, 51, 50),
        ChildExpectation(52, 53, 52),
        "Parens.NamedField(t)"
      ),
      InfixExpectation(
        44,
        49,
        46,
        ChildExpectation(44, 45, 44),
        ChildExpectation(46, 47, 46),
        ChildExpectation(48, 49, 48),
        "InfixOp.NamedField(left)"
      )
    ),
    "precedence-mixed"   -> Vector(
      InfixExpectation(
        44,
        53,
        46,
        ChildExpectation(44, 45, 44),
        ChildExpectation(46, 47, 46),
        ChildExpectation(48, 53, 50),
        "Parens.NamedField(t)"
      ),
      InfixExpectation(
        48,
        53,
        50,
        ChildExpectation(48, 49, 48),
        ChildExpectation(50, 51, 50),
        ChildExpectation(52, 53, 52),
        "InfixOp.NamedField(right)"
      )
    ),
    "precedence-parens"  -> Vector(
      InfixExpectation(
        44,
        55,
        52,
        ChildExpectation(44, 51, 44),
        ChildExpectation(52, 53, 52),
        ChildExpectation(54, 55, 54),
        "Parens.NamedField(t)"
      ),
      InfixExpectation(
        45,
        50,
        47,
        ChildExpectation(45, 46, 45),
        ChildExpectation(47, 48, 47),
        ChildExpectation(49, 50, 49),
        "Parens.NamedField(t)"
      )
    ),
    "applied-argument"   -> Vector(
      InfixExpectation(
        47,
        52,
        49,
        ChildExpectation(47, 48, 47),
        ChildExpectation(49, 50, 49),
        ChildExpectation(51, 52, 51),
        "AppliedTypeTree.NamedField(args)/RepeatedIndex(0)"
      )
    ),
    "tuple-components"   -> Vector(
      InfixExpectation(
        44,
        49,
        46,
        ChildExpectation(44, 45, 44),
        ChildExpectation(46, 47, 46),
        ChildExpectation(48, 49, 48),
        "Tuple.NamedField(trees)/RepeatedIndex(0)"
      ),
      InfixExpectation(
        51,
        56,
        53,
        ChildExpectation(51, 52, 51),
        ChildExpectation(53, 54, 53),
        ChildExpectation(55, 56, 55),
        "Tuple.NamedField(trees)/RepeatedIndex(1)"
      )
    ),
    "wildcard-bound"     -> Vector(
      InfixExpectation(
        52,
        57,
        54,
        ChildExpectation(52, 53, 52),
        ChildExpectation(54, 55, 54),
        ChildExpectation(56, 57, 56),
        "TypeBoundsTree.NamedField(hi)"
      )
    ),
    "lower-bound"        -> Vector(
      InfixExpectation(
        52,
        57,
        54,
        ChildExpectation(52, 53, 52),
        ChildExpectation(54, 55, 54),
        ChildExpectation(56, 57, 56),
        "TypeBoundsTree.NamedField(lo)"
      )
    ),
    "given-anon"         -> Vector(
      InfixExpectation(
        47,
        52,
        49,
        ChildExpectation(47, 48, 47),
        ChildExpectation(49, 50, 49),
        ChildExpectation(51, 52, 51),
        "Parens.NamedField(t)"
      )
    ),
    "given-named"        -> Vector(
      InfixExpectation(
        51,
        56,
        53,
        ChildExpectation(51, 52, 51),
        ChildExpectation(53, 54, 53),
        ChildExpectation(55, 56, 55),
        "Parens.NamedField(t)"
      )
    ),
    "given-wildcard"     -> Vector(
      InfixExpectation(
        51,
        56,
        53,
        ChildExpectation(51, 52, 51),
        ChildExpectation(53, 54, 53),
        ChildExpectation(55, 56, 55),
        "Parens.NamedField(t)"
      )
    ),
    "given-backticked"   -> Vector(
      InfixExpectation(
        54,
        59,
        56,
        ChildExpectation(54, 55, 54),
        ChildExpectation(56, 57, 56),
        ChildExpectation(58, 59, 58),
        "Parens.NamedField(t)"
      )
    ),
    "nested-parens"      -> Vector(
      InfixExpectation(
        44,
        55,
        52,
        ChildExpectation(44, 51, 44),
        ChildExpectation(52, 53, 52),
        ChildExpectation(54, 55, 54),
        "Parens.NamedField(t)"
      ),
      InfixExpectation(
        45,
        50,
        47,
        ChildExpectation(45, 46, 45),
        ChildExpectation(47, 48, 47),
        ChildExpectation(49, 50, 49),
        "Parens.NamedField(t)"
      )
    ),
    "owned-family-mix"   -> Vector(
      InfixExpectation(
        47,
        71,
        63,
        ChildExpectation(47, 62, 49),
        ChildExpectation(63, 64, 63),
        ChildExpectation(65, 71, 65),
        "AppliedTypeTree.NamedField(args)/RepeatedIndex(0)"
      ),
      InfixExpectation(
        47,
        62,
        49,
        ChildExpectation(47, 48, 47),
        ChildExpectation(49, 50, 49),
        ChildExpectation(51, 62, 51),
        "InfixOp.NamedField(left)"
      )
    ),
    "trivia"             -> Vector(
      InfixExpectation(
        44,
        67,
        55,
        ChildExpectation(44, 45, 44),
        ChildExpectation(55, 56, 55),
        ChildExpectation(66, 67, 66),
        "Parens.NamedField(t)"
      )
    )
  )

  private def assertAdmittedInfixStructure(
      shapeId: String,
      source: String,
      snapshot: ParserSyntaxSnapshot,
      byId: Map[Long, ParserSyntaxNode],
      operatorName: ParserSyntaxNode => Option[String],
      infixOps: Vector[ParserSyntaxNode]
  ): Unit =
    infixOps.foreach { op =>
      assertEquals(Vector("left", "op", "right"), op.fields.map(_.name))
      val ParserNodePosition.Positioned(opRange, opOwnPoint, opProvenance) = op.position: @unchecked
      assertEquals("SourceDerived", opProvenance.toString)
      op.fields.foreach { field =>
        field.value match
          case ParserFieldValue.Node(id) =>
            val child                                                   = byId(id)
            val ParserNodePosition.Positioned(range, point, provenance) = child.position: @unchecked
            assertEquals("SourceDerived", provenance.toString)
            assert(
              opRange.startOffset <= range.startOffset && range.endOffset <= opRange.endOffset,
              s"$shapeId field ${field.name} range must sit inside its InfixOp"
            )
            val rendered                                                = source.substring(range.startOffset, range.endOffset)
            assert(
              rendered.nonEmpty && !rendered.startsWith(" ") && !rendered.endsWith(" "),
              s"$shapeId field ${field.name} range must exclude trivia: '$rendered'"
            )
            if child.production == "Ident" then
              val nameValue   = child.fields.collectFirst {
                case f if f.name == "name" && f.value.isInstanceOf[ParserFieldValue.Name] =>
                  f.value.asInstanceOf[ParserFieldValue.Name].value
              }
              assertEquals(rendered, nameValue.getOrElse("<missing name>"))
              val identLength = range.endOffset - range.startOffset
              assert(
                rendered.length == identLength,
                s"$shapeId Ident range must be exactly its name"
              )
              assert(
                point == range.startOffset,
                s"$shapeId Ident point must be its range start"
              )
            assert(
              range.startOffset <= point && point < range.endOffset,
              s"$shapeId field ${field.name} point must sit inside its range"
            )
          case other                     => fail(s"$shapeId field ${field.name} must reference a node: $other")
      }
      assert(op.occurrences.nonEmpty, s"$shapeId infix must declare its occurrence path")
      val opId                                                             = op.fields
        .find(_.name == "op")
        .map(_.value)
        .getOrElse(sys.error("missing op"))
        .asInstanceOf[ParserFieldValue.Node]
        .nodeId
      val opNode                                                           = byId(opId)
      assertEquals("Ident", opNode.production)
      val ParserNodePosition.Positioned(opIdentRange, opIdentPoint, _)     = opNode.position: @unchecked
      val operator                                                         = operatorName(op).getOrElse(sys.error("missing operator name"))
      assertEquals(operator, source.substring(opIdentRange.startOffset, opIdentRange.endOffset))
      assert(
        opIdentRange.startOffset <= opIdentPoint && opIdentPoint < opIdentRange.endOffset,
        s"$shapeId operator point must sit inside its range"
      )
      assert(
        opOwnPoint == opIdentRange.startOffset,
        s"$shapeId InfixOp point must be its operator's range start"
      )
      val token                                                            = snapshot.scannerTokens
        .find(t => t.range.startOffset == opIdentRange.startOffset && t.range.endOffset == opIdentRange.endOffset)
      assert(
        token.isDefined,
        s"$shapeId scanner must report the operator token at ${opIdentRange.startOffset}"
      )
      assertEquals(operator, source.substring(token.get.range.startOffset, token.get.range.endOffset))
      assertEquals("Identifier", token.get.kind.toString)
    }

  private def assertAdmittedOccurrencePaths(
      shapeId: String,
      version: String,
      byId: Map[Long, ParserSyntaxNode],
      infixOps: Vector[ParserSyntaxNode]
  ): Unit =
    val allowedPaths: Map[String, Set[String]] = Map(
      "union-paren"        -> Set("Parens.NamedField(t)"),
      "intersection-paren" -> Set("Parens.NamedField(t)"),
      "union-chain"        -> Set("Parens.NamedField(t)", "InfixOp.NamedField(left)"),
      "intersection-chain" -> Set("Parens.NamedField(t)", "InfixOp.NamedField(left)"),
      "precedence-mixed"   -> Set("Parens.NamedField(t)", "InfixOp.NamedField(right)"),
      "precedence-parens"  -> Set("Parens.NamedField(t)"),
      "applied-argument"   -> Set("AppliedTypeTree.NamedField(args)/RepeatedIndex(0)"),
      "tuple-components"   -> Set(
        "Tuple.NamedField(trees)/RepeatedIndex(0)",
        "Tuple.NamedField(trees)/RepeatedIndex(1)"
      ),
      "wildcard-bound"     -> Set("TypeBoundsTree.NamedField(hi)"),
      "lower-bound"        -> Set("TypeBoundsTree.NamedField(lo)"),
      "given-anon"         -> Set("Parens.NamedField(t)"),
      "given-named"        -> Set("Parens.NamedField(t)"),
      "given-wildcard"     -> Set("Parens.NamedField(t)"),
      "given-backticked"   -> Set("Parens.NamedField(t)"),
      "nested-parens"      -> Set("Parens.NamedField(t)"),
      "owned-family-mix"   -> Set(
        "AppliedTypeTree.NamedField(args)/RepeatedIndex(0)",
        "InfixOp.NamedField(left)"
      ),
      "trivia"             -> Set("Parens.NamedField(t)")
    )
    val expected                               = allowedPaths.getOrElse(shapeId, sys.error(s"no occurrence expectation for $shapeId"))
    val observed                               = infixOps
      .flatMap(_.occurrences.map { occurrence =>
        val owner = byId(occurrence.ownerNodeId).production
        owner + "." + occurrence.fieldPath.mkString("/")
      })
      .toSet
    assert(
      observed == expected,
      s"$version $shapeId occurrence paths must be exactly $expected but were $observed"
    )

  private def assertAdmittedExactExpectations(
      shapeId: String,
      version: String,
      byId: Map[Long, ParserSyntaxNode],
      infixOps: Vector[ParserSyntaxNode]
  ): Unit =
    val expected = AdmittedExpectations.getOrElse(shapeId, sys.error(s"no exact expectation for $shapeId"))
    val ordered  = infixOps.sortBy(op => op.position.asInstanceOf[ParserNodePosition.Positioned].range.startOffset)
    assert(
      ordered.size == expected.size,
      s"$version $shapeId must have exactly ${expected.size} infix operators: ${ordered.size}"
    )
    ordered.zip(expected).foreach { case (op, expectation) =>
      val ParserNodePosition.Positioned(range, point, provenance) = op.position: @unchecked
      assertEquals("SourceDerived", provenance.toString)
      assert(
        range.startOffset == expectation.start && range.endOffset == expectation.end && point == expectation.point,
        s"$version $shapeId InfixOp must be at ${expectation.start}:${expectation.end} point ${expectation.point} but was $range/$point"
      )
      val fieldChildren                                           = op.fields.collect {
        case f if f.name == "left" || f.name == "op" || f.name == "right" =>
          f.name -> f.value.asInstanceOf[ParserFieldValue.Node].nodeId
      }.toMap
      val expectedChildren                                        = Map(
        "left"  -> expectation.left,
        "op"    -> expectation.op,
        "right" -> expectation.right
      )
      fieldChildren.foreach { case (name, nodeId) =>
        val want                                                     = expectedChildren(name)
        val ParserNodePosition.Positioned(childRange, childPoint, _) = byId(nodeId).position: @unchecked
        assert(
          childRange.startOffset == want.start && childRange.endOffset == want.end && childPoint == want.point,
          s"$version $shapeId $name child must be at ${want.start}:${want.end} point ${want.point} but was $childRange/$childPoint"
        )
      }
      val path                                                    = op.occurrences
        .map(o => byId(o.ownerNodeId).production + "." + o.fieldPath.mkString("/"))
        .mkString(",")
      assertEquals(expectation.occurrencePath, path)
    }

  private def assertUnionParenStructure(
      source: String,
      snapshot: ParserSyntaxSnapshot,
      byId: Map[Long, ParserSyntaxNode],
      operatorName: ParserSyntaxNode => Option[String]
  ): Unit =
    val union                                                       = snapshot.nodes
      .find(node => node.production == "InfixOp" && operatorName(node).contains("|"))
      .getOrElse(sys.error("union-paren must contain a union InfixOp"))
    assertEquals(Vector("left", "op", "right"), union.fields.map(_.name))
    val opFieldValue                                                = union.fields.find(_.name == "op").map(_.value).getOrElse(sys.error("missing op field"))
    val opId                                                        = opFieldValue.asInstanceOf[ParserFieldValue.Node].nodeId
    val opNode                                                      = byId(opId)
    assertEquals("Ident", opNode.production)
    val ParserNodePosition.Positioned(opRange, opPoint, provenance) = opNode.position: @unchecked
    assertEquals("SourceDerived", provenance.toString)
    assertEquals("|", source.substring(opRange.startOffset, opRange.endOffset))
    assertEquals("|", source.substring(opPoint, opPoint + 1))
    val occurrencePath                                              = union.occurrences.map { occurrence =>
      val owner = byId(occurrence.ownerNodeId)
      s"${owner.production}.${occurrence.fieldPath.mkString("/")}"
    }
    assert(
      occurrencePath.contains("Parens.NamedField(t)") || occurrencePath.exists(_.startsWith("Parens.")),
      s"union-paren infix must attach through the parenthesized type: $occurrencePath"
    )
    val pipeToken                                                   = snapshot.scannerTokens
      .find(token => source.substring(token.range.startOffset, token.range.endOffset) == "|")
      .getOrElse(sys.error("scanner must report the union bar token"))
    assertEquals(opRange.startOffset, pipeToken.range.startOffset)
    assertEquals(opRange.endOffset, pipeToken.range.endOffset)

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
