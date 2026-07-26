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
      case "Apply"     => emitApply(node, ctx)
      case "TypeApply" => emitTypeApply(node, ctx)
      case "ValDef"    => emitValueDefinition(node, ctx)
      case "DefDef"    => emitFunctionDefinition(node, ctx)
      case _           => emitRaw(node, ctx)

  private def emitFunctionDefinition(node: CompilerSourceNode, ctx: EmitCtx): Unit =
    node.range.foreach: range =>
      val builder = ctx.builder
      advanceTo(range.startOffset, builder)
      val marker  = builder.mark()
      // ScFunction requires a non-null paramClauses; emit an empty parameters holder (the parameters themselves
      // remain raw lexer leaves until parameter mapping is added).
      builder.mark().done(ScalaElementType.PARAM_CLAUSES)
      ctx.childrenOf(node.id).sortBy(_.range.map(_.startOffset).getOrElse(0)).foreach(emit(_, ctx))
      advanceTo(range.endOffset, builder)
      marker.done(ScalaElementType.FUNCTION_DEFINITION)

  private def emitValueDefinition(node: CompilerSourceNode, ctx: EmitCtx): Unit =
    node.range.foreach: range =>
      val builder = ctx.builder
      advanceTo(range.startOffset, builder)
      val marker  = builder.mark()
      // ScPatternDefinition requires a non-null pList; emit an empty pattern list (bindings stay raw for now).
      builder.mark().done(ScalaElementType.PATTERN_LIST)
      ctx.childrenOf(node.id).sortBy(_.range.map(_.startOffset).getOrElse(0)).foreach(emit(_, ctx))
      advanceTo(range.endOffset, builder)
      marker.done(ScalaElementType.PATTERN_DEFINITION)

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
