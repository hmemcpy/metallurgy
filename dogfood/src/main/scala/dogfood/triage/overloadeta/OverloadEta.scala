package dogfood.triage.overloadeta

trait MyTc[T]
case class Foo private (i: Seq[Int])
object Foo:
  def apply[T <: Seq[Int]: MyTc](i: T): Foo = new Foo(i)
given MyTc[List[Int]] = null

val x: Foo = Foo(List(1))
