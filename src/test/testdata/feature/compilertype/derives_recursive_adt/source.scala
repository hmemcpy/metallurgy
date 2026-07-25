import scala.deriving.Mirror

enum Lst[+T] derives CanEqual:
  case Cns(t: T, ts: Lst[T])
  case Nl

val mirror = summon[Mirror.Of[Lst[Int]]]
type Elements = mirror.MirroredElemTypes
val elements: Elements = (Lst.Cns(1, Lst.Nl), Lst.Nl)
