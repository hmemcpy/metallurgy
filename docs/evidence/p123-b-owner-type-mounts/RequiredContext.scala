trait B
class C[A](x: A) extends B:
  self: B =>
  def value: A = x
