package dogfood.triage.quotesplice

import scala.quoted.*

inline def power(x: Double, inline n: Int): Double = ${ powerCode('x, 'n) }

def powerCode(x: Expr[Double], n: Expr[Int])(using Quotes): Expr[Double] = '{ $x * $n }
