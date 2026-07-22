package dogfood.showcase

type Elem[X] = X match
  case List[t]  => t
  case Array[t] => t

val first: Elem[List[Int]] = 42
// hover `first` — with the plugin: Int
