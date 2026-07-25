import scala.quoted.*

inline def power(x: Double, inline n: Int): Double = ${ powerCode('x, 'n) }

def powerCode(x: Expr[Double], n: Expr[Int])(using Quotes): Expr[Double] =
  import quotes.reflect.*
  val nVal = n.valueOrError
  if nVal == 0 then '{ 1.0 }
  else if nVal % 2 == 0 then '{ val y = ${x} * ${x}; ${powerCode('y, Expr(nVal / 2))} }
  else '{ ${x} * ${powerCode(x, Expr(nVal - 1))} }

val result: Double = power(2.0, 10)
