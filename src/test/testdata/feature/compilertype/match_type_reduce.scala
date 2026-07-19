type Elem[X] = X match {
  case String      => Char
  case Array[t]    => t
  case List[t]     => t
  case Option[t]   => t
  case Map[k, v]   => (k, v)
}

val e1: Char       = Elem[String]
val e2: Int        = Elem[List[Int]]
val e3: Int        = Elem[Option[Int]]
val e4: (Int, Int) = Elem[Map[Int, Int]]
