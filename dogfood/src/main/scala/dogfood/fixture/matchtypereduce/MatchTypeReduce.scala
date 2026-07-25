package dogfood.fixture.matchtypereduce

type Elem[X] = X match
  case List[t]  => t
  case Array[t] => t
  case String   => Char

val e1: Elem[List[Int]] = 42
val e2: Elem[String] = 'c'
