import scala.deriving.Mirror

enum Lst[+T] derives scala.reflect.ClassTag:
  case Cns(t: T, ts: Lst[T])
  case Nl

object Lst:
  val m = summon[Mirror.Of[Lst[Int]]]
