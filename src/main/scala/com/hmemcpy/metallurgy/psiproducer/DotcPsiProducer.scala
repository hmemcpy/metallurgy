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
    node.kind match
      case "Apply"            => emitApply(node, ctx)
      case "TypeApply"        => emitTypeApply(node, ctx)
      case "ValDef"           => emitValueDefinition(node, ctx)
      case "DefDef"           => emitFunctionDefinition(node, ctx)
      case "Ident" | "Select" => emitReference(node, ctx)
      case _                  => emitRaw(node, ctx)

  private def emitReference(node: CompilerSourceNode, ctx: EmitCtx): Unit =
    node.range.foreach: range =>
      val builder = ctx.builder
      advanceTo(range.startOffset, builder)
      val marker  = builder.mark()
      advanceTo(range.endOffset, builder)
      marker.done(ScalaElementType.REFERENCE_EXPRESSION)

  private def emitFunctionDefinition(node: CompilerSourceNode, ctx: EmitCtx): Unit =
    node.range.foreach: range =>
      val builder          = ctx.builder
      advanceTo(range.startOffset, builder)
      val marker           = builder.mark()
      // The name identifier must be a direct child of FUNCTION_DEFINITION (ScFunctionImpl.nameId reads it via
      // findChildByType(tIDENTIFIER)); consume the `def` keyword and the name before opening the param-clause marker.
      node.name.foreach: name =>
        advanceToToken(name, range.endOffset, builder)
        builder.advanceLexer()
      val children         = ctx.childrenOf(node.id).sortBy(_.range.map(_.startOffset).getOrElse(0))
      // A DefDef's direct ValDef children are its parameters (body locals nest inside a Block); emit them as real
      // ScParameter nodes inside a param clause so ScFunction.parameters is non-empty and calls can bind.
      val (params, others) = children.partition(_.kind == "ValDef")
      emitParamClauses(params, ctx)
      others.foreach(emit(_, ctx))
      advanceTo(range.endOffset, builder)
      marker.done(ScalaElementType.FUNCTION_DEFINITION)

  private def emitParamClauses(params: Vector[CompilerSourceNode], ctx: EmitCtx): Unit =
    val builder       = ctx.builder
    val clausesMarker = builder.mark()
    if params.nonEmpty then
      val clauseMarker = builder.mark()
      params
        .sortBy(_.range.map(_.startOffset).getOrElse(0))
        .foreach: p =>
          p.range.foreach: r =>
            advanceTo(r.startOffset, builder)
            val paramMarker = builder.mark()
            advanceTo(r.endOffset, builder)
            paramMarker.done(ScalaElementType.PARAM)
      clauseMarker.done(ScalaElementType.PARAM_CLAUSE)
    clausesMarker.done(ScalaElementType.PARAM_CLAUSES)

  private def emitValueDefinition(node: CompilerSourceNode, ctx: EmitCtx): Unit =
    node.range.foreach: range =>
      val builder        = ctx.builder
      advanceTo(range.startOffset, builder)
      val marker         = builder.mark()
      // A ScPatternDefinition declares its bindings through a binding pattern inside PATTERN_LIST; without it,
      // declaredElements is empty and the name is unreachable. Wrap the declared name token in a reference pattern.
      val patternsMarker = builder.mark()
      node.name.foreach: name =>
        advanceToToken(name, range.endOffset, builder)
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
