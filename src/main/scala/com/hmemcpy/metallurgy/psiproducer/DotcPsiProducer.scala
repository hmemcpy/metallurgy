package com.hmemcpy.metallurgy.psiproducer

import com.hmemcpy.metallurgy.pc.{CompilerSourceNode, CompilerTreeExtraction}
import com.intellij.lang.PsiBuilder
import com.intellij.psi.tree.IElementType
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType

/** Produces a whole-file bundled-compatible PSI tree from the compiler's typed tree. The walk emits a marker for each
  * compiler node whose kind has a bundled element-type mapping and advances over the original token stream, so every
  * emitted node's text is the verbatim source substring. Tokens not under a mapped node remain ordinary lexer leaves.
  */
object DotcPsiProducer:

  def parse(fileElementType: IElementType, builder: PsiBuilder, extraction: CompilerTreeExtraction): Unit =
    val nodes      = extraction.tree.physicalNodes
    val byId       = nodes.map(n => n.id -> n).toMap
    val childrenBy = nodes.groupBy(_.parentId)
    val ctx        = EmitCtx(childrenBy, builder)
    val topLevel   =
      nodes.filter(n => n.parentId.forall(p => !byId.contains(p))).sortBy(_.range.map(_.startOffset).getOrElse(0))
    val root       = builder.mark()
    topLevel.foreach(n => emit(n, ctx))
    advanceTo(builder.getOriginalText.length(), builder)
    root.done(fileElementType)

  private def emit(node: CompilerSourceNode, ctx: EmitCtx): Unit =
    // A type-position child (a def's return type, a param/val's declared type) is emitted as a type element, not a
    // reference, so ScFunction.returnTypeElement / parameter types resolve.
    if node.role.isDefined then emitTypeElement(node, ctx)
    else
      node.kind match
        case "Apply"            => emitApply(node, ctx)
        case "TypeApply"        => emitTypeApply(node, ctx)
        case "ValDef"           => emitValueDefinition(node, ctx)
        case "DefDef"           => emitFunctionDefinition(node, ctx)
        case "PackageDef"       => emitPackaging(node, ctx)
        case "Ident" | "Select" => emitReference(node, ctx)
        case _                  => emitRaw(node, ctx)

  private def emitPackaging(node: CompilerSourceNode, ctx: EmitCtx): Unit =
    node.range.foreach: range =>
      val builder  = ctx.builder
      advanceTo(range.startOffset, builder)
      val marker   = builder.mark()
      val children = ctx.childrenOf(node.id).sortBy(_.range.map(_.startOffset).getOrElse(0))
      // The leading Select/Ident is the package qualifier; emit it as a stable code reference (ScStableCodeReference)
      // so ScPackaging.reference / qualName resolve the package FQN. The bundled QualId is a single REFERENCE node
      // wrapping the dotted name.
      children.headOption match
        case Some(pid) if pid.kind == "Select" || pid.kind == "Ident" =>
          emitStableReference(pid, ctx)
          children.tail.foreach(emit(_, ctx))
        case _                                                        =>
          children.foreach(emit(_, ctx))
      advanceTo(range.endOffset, builder)
      marker.done(ScalaElementType.PACKAGING)

  private def emitStableReference(node: CompilerSourceNode, ctx: EmitCtx): Unit =
    node.range.foreach: range =>
      val builder = ctx.builder
      advanceTo(range.startOffset, builder)
      val marker  = builder.mark() // REFERENCE (ScStableCodeReference); a dotted qualifier is a nested chain
      node.kind match
        case "Select" =>
          // qualifier (a child Ident/Select) emitted as an inner REFERENCE, then `.` + the name token
          ctx
            .childrenOf(node.id)
            .sortBy(_.range.map(_.startOffset).getOrElse(0))
            .headOption
            .foreach(emitStableReference(_, ctx))
          node.name.foreach: name =>
            advanceToToken(name, range.endOffset, builder)
            builder.advanceLexer()
        case "Ident"  =>
          node.name.foreach: name =>
            advanceToToken(name, range.endOffset, builder)
            builder.advanceLexer()
        case _        =>
          advanceTo(range.endOffset, builder)
      marker.done(ScalaElementType.REFERENCE)

  private def emitTypeElement(node: CompilerSourceNode, ctx: EmitCtx): Unit =
    node.range.foreach: range =>
      val builder  = ctx.builder
      val children = ctx.childrenOf(node.id).sortBy(_.range.map(_.startOffset).getOrElse(0))
      advanceTo(range.startOffset, builder)
      node.kind match
        case "Ident" | "Select" =>
          // a leaf named type reference: SIMPLE_TYPE > REFERENCE (the resolver's nameId is the identifier token)
          val outer = builder.mark()
          val inner = builder.mark()
          advanceTo(range.endOffset, builder)
          inner.done(ScalaElementType.REFERENCE)
          outer.done(ScalaElementType.SIMPLE_TYPE)
        case "Tuple"            =>
          // a tuple type (A, B): TUPLE_TYPE whose elements are themselves types, so each is navigatable.
          val wrapper = builder.mark()
          children.foreach(emitTypeElement(_, ctx))
          advanceTo(range.endOffset, builder)
          wrapper.done(ScalaElementType.TUPLE_TYPE)
        case _                  =>
          // any other composite type: emit the children as types (no specific wrapper yet) so a REFERENCE never wraps a
          // non-ref (which would leave nameId null and crash navigation).
          children.foreach(emitTypeElement(_, ctx))
          advanceTo(range.endOffset, builder)

  private def emitReference(node: CompilerSourceNode, ctx: EmitCtx): Unit =
    node.range.foreach: range =>
      val builder = ctx.builder
      advanceTo(range.startOffset, builder)
      val marker  = builder.mark()
      advanceTo(range.endOffset, builder)
      marker.done(ScalaElementType.REFERENCE_EXPRESSION)

  private def emitFunctionDefinition(node: CompilerSourceNode, ctx: EmitCtx): Unit =
    node.range.foreach: range =>
      val builder            = ctx.builder
      advanceTo(range.startOffset, builder)
      val marker             = builder.mark()
      // The name identifier must be a direct child of FUNCTION_DEFINITION (ScFunctionImpl.nameId reads it via
      // findChildByType(tIDENTIFIER)); consume the `def` keyword and the name before opening the param-clause marker.
      node.name.foreach: name =>
        advanceToToken(name, range.endOffset, builder)
        builder.advanceLexer()
      val children           = ctx.childrenOf(node.id).sortBy(_.range.map(_.startOffset).getOrElse(0))
      // A DefDef's direct TypeDef children are its type parameters and its direct ValDef children its value
      // parameters (body locals nest in a Block). Emit the type-param clause first so the type params are in scope
      // for the param/return types (otherwise A/B resolve to Any).
      val (typeParams, rest) = children.partition(_.kind == "TypeDef")
      val (params, others)   = rest.partition(_.kind == "ValDef")
      emitTypeParamClause(typeParams, ctx)
      emitParamClauses(params, ctx)
      others.foreach(emit(_, ctx))
      advanceTo(range.endOffset, builder)
      marker.done(ScalaElementType.FUNCTION_DEFINITION)

  private def emitTypeParamClause(typeParams: Vector[CompilerSourceNode], ctx: EmitCtx): Unit =
    if typeParams.nonEmpty then
      val builder      = ctx.builder
      val clauseMarker = builder.mark()
      typeParams
        .sortBy(_.range.map(_.startOffset).getOrElse(0))
        .foreach: tp =>
          tp.range.foreach: r =>
            advanceTo(r.startOffset, builder)
            val tpMarker = builder.mark()
            advanceTo(r.endOffset, builder)
            tpMarker.done(ScalaElementType.TYPE_PARAM)
      clauseMarker.done(ScalaElementType.TYPE_PARAM_CLAUSE)

  private def emitParamClauses(params: Vector[CompilerSourceNode], ctx: EmitCtx): Unit =
    val builder       = ctx.builder
    val clausesMarker = builder.mark()
    // Params arrive flattened under the DefDef (the reflection walk descends DefDef.paramss, a List of Lists, without
    // recording clause membership). Two params belong to different clauses when the source between the previous
    // param's end and this one's start contains a `)` followed by a `(` — the closing paren of one clause and the
    // opening paren of the next.
    val source        = builder.getOriginalText
    splitIntoClauses(params, source).foreach: clauseParams =>
      val clauseMarker = builder.mark()
      clauseParams.foreach: p =>
        p.range.foreach: r =>
          advanceTo(r.startOffset, builder)
          val paramMarker = builder.mark()
          p.name.foreach: name =>
            advanceToToken(name, r.endOffset, builder)
            builder.advanceLexer()
          ctx
            .childrenOf(p.id)
            .sortBy(_.range.map(_.startOffset).getOrElse(0))
            .foreach: c =>
              if c.role.isDefined then
                c.range.foreach: cr =>
                  advanceTo(cr.startOffset, builder)
                  val paramTypeMarker = builder.mark()
                  emit(c, ctx)
                  paramTypeMarker.done(ScalaElementType.PARAM_TYPE)
          advanceTo(r.endOffset, builder)
          paramMarker.done(ScalaElementType.PARAM)
      clauseMarker.done(ScalaElementType.PARAM_CLAUSE)
    clausesMarker.done(ScalaElementType.PARAM_CLAUSES)

  private def splitIntoClauses(
      params: Vector[CompilerSourceNode],
      source: CharSequence
  ): Vector[Vector[CompilerSourceNode]] =
    val sorted = params.sortBy(_.range.map(_.startOffset).getOrElse(0))
    sorted.foldLeft(Vector.empty[Vector[CompilerSourceNode]]): (clauses, param) =>
      if clauses.isEmpty then Vector(Vector(param))
      else
        val prev       = clauses.last.last
        val prevEnd    = prev.range.map(_.endOffset).getOrElse(0)
        val paramStart = param.range.map(_.startOffset).getOrElse(0)
        val between    = if prevEnd < paramStart then source.subSequence(prevEnd, paramStart).toString else ""
        if between.contains(')') && between.contains('(') then clauses :+ Vector(param)
        else clauses.init :+ (clauses.last :+ param)

  private def emitValueDefinition(node: CompilerSourceNode, ctx: EmitCtx): Unit =
    node.range.foreach: range =>
      val builder        = ctx.builder
      advanceTo(range.startOffset, builder)
      val marker         = builder.mark()
      // The `val`/`var` keyword must be a direct child of PATTERN_DEFINITION (ScValueOrVariable.keywordToken reads
      // it via findChildByType); advance to the name first so the keyword lands in PATTERN_DEFINITION, then open
      // PATTERN_LIST and wrap the name in a reference pattern so declaredElements is non-empty.
      node.name.foreach: name =>
        advanceToToken(name, range.endOffset, builder)
      val patternsMarker = builder.mark()
      if node.name.isDefined then
        val refMarker = builder.mark()
        builder.advanceLexer()
        refMarker.done(ScalaElementType.REFERENCE_PATTERN)
      patternsMarker.done(ScalaElementType.PATTERN_LIST)
      ctx.childrenOf(node.id).sortBy(_.range.map(_.startOffset).getOrElse(0)).foreach(emit(_, ctx))
      advanceTo(range.endOffset, builder)
      marker.done(ScalaElementType.PATTERN_DEFINITION)

  private def advanceToToken(text: String, bound: Int, builder: PsiBuilder): Unit =
    while !builder.eof() && builder.getCurrentOffset < bound && builder.getTokenText != text do builder.advanceLexer()

  private def emitApply(node: CompilerSourceNode, ctx: EmitCtx): Unit =
    node.range.foreach: range =>
      val builder    = ctx.builder
      advanceTo(range.startOffset, builder)
      val methodCall = builder.mark()
      val children   = ctx.childrenOf(node.id).sortBy(_.range.map(_.startOffset).getOrElse(0))
      children.headOption.foreach(emit(_, ctx))
      val argExprs   = builder.mark()
      children.tail.foreach(emit(_, ctx))
      advanceTo(range.endOffset, builder)
      argExprs.done(ScalaElementType.ARG_EXPRS)
      methodCall.done(ScalaElementType.METHOD_CALL)

  private def emitRaw(node: CompilerSourceNode, ctx: EmitCtx): Unit =
    node.range.foreach: range =>
      advanceTo(range.startOffset, ctx.builder)
      ctx.childrenOf(node.id).sortBy(_.range.map(_.startOffset).getOrElse(0)).foreach(c => emit(c, ctx))
      advanceTo(range.endOffset, ctx.builder)

  private def emitTypeApply(node: CompilerSourceNode, ctx: EmitCtx): Unit =
    node.range.foreach: range =>
      val builder  = ctx.builder
      advanceTo(range.startOffset, builder)
      val generic  = builder.mark()
      val children = ctx.childrenOf(node.id).sortBy(_.range.map(_.startOffset).getOrElse(0))
      children.headOption.foreach: callee =>
        callee.range.foreach: r =>
          advanceTo(r.startOffset, builder)
          val ref = builder.mark()
          advanceTo(r.endOffset, builder)
          ref.done(ScalaElementType.REFERENCE_EXPRESSION)
      val typeArgs = builder.mark()
      children.tail.foreach(emitTypeArg(_, ctx))
      advanceTo(range.endOffset, builder)
      typeArgs.done(ScalaElementType.TYPE_ARGS)
      generic.done(ScalaElementType.GENERIC_CALL)

  private def emitTypeArg(node: CompilerSourceNode, ctx: EmitCtx): Unit =
    node.range.foreach: range =>
      val builder = ctx.builder
      advanceTo(range.startOffset, builder)
      ctx
        .childrenOf(node.id)
        .sortBy(_.range.map(_.startOffset).getOrElse(0))
        .foreach: c =>
          c.kind match
            case "Ident" | "Select" | "TypeTree" =>
              c.range.foreach: r =>
                advanceTo(r.startOffset, builder)
                val outer = builder.mark()
                val inner = builder.mark()
                advanceTo(r.endOffset, builder)
                inner.done(ScalaElementType.REFERENCE)
                outer.done(ScalaElementType.SIMPLE_TYPE)
            case _                               => emit(c, ctx)
      advanceTo(range.endOffset, builder)

  private def advanceTo(offset: Int, builder: PsiBuilder): Unit =
    while !builder.eof() && builder.getCurrentOffset < offset do builder.advanceLexer()

  private final case class EmitCtx(childrenBy: Map[Option[Long], Vector[CompilerSourceNode]], builder: PsiBuilder):
    def childrenOf(parentId: Long): Vector[CompilerSourceNode] =
      childrenBy.getOrElse(Some(parentId), Vector.empty)
