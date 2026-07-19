import scala.quoted.*

class Config(map: Map[String, Any]) extends Selectable:
  def selectDynamic(name: String): Any = map(name)

transparent inline def typesafeConfig(inline pairs: (String, Any)*): Config = ${ typesafeConfigImpl('pairs) }

def typesafeConfigImpl(pairs: Expr[Seq[(String, Any)]])(using Quotes): Expr[Config] =
  import quotes.reflect.*
  val unpacked = Varargs.unapply(pairs).getOrElse(Nil).map:
    case '{ ($k: String) -> ($v: t) } => k.valueOrError -> v
  val typ = unpacked.foldLeft(TypeRepr.of[Config]): (acc, entry) =>
    Refinement(acc, entry._1, entry._2.asTerm.tpe.widen)
  val params = unpacked.map: (k, v) =>
    '{ ${Expr(k)} -> $v }
  typ.asType match
    case '[t] => '{ Config(Map(${Varargs(params)}: _*)).asInstanceOf[t] }
