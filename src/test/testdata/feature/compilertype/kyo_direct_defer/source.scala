import kyo.*

case class User(name: String)

def greet(first: User < IO, second: User < IO): String < IO =
  defer {
    val a = await(first)
    val b = await(second)
    s"Hello ${a.name} and ${b.name}"
  }
