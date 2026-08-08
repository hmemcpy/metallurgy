import scala.language.experimental.namedTypeArguments

trait Coll[Elem]

type One = List[Int]
type Two = Either[Int, String]
type Three[Elem] = Coll[Elem]

def make[A]: A = ???
def pair[A, B](first: A, second: B): (A, B) = (first, second)

val direct = make[A = Int]
val commented = make[A /* name */ = /* type */ Int]
val invoked = pair[A = Int](1, "text")
val positional = pair[Int, String](1, "text")
val allNamed = pair[A = Int, B = String](1, "text")
