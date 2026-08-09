import scala.annotation.unchecked.uncheckedVariance

type Structural[A] = AnyRef {
  type Elem <: A
  val value: Elem
  def map(x: Elem): List[Elem @uncheckedVariance]
}

type Layout[A] = AnyRef:
  type Elem = A
  def current: Elem

type Parentless = { type Elem; val current: Elem }
type Marked[A] = List[A @uncheckedVariance] @unchecked
