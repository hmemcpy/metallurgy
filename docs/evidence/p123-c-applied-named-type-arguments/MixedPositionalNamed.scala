import scala.language.experimental.namedTypeArguments

def pair[A, B](first: A, second: B): (A, B) = (first, second)

val positionalThenNamed = pair[Int, B = String](1, "text")
val namedThenPositional = pair[A = Int, String](1, "text")
