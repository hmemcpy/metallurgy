trait T:
  type A

object p:
  type A = Int

val x = 1

type Reference = T
type Path = p.A
type Projection = T#A
type Singleton = x.type
type IntegerLiteral = 42
type NegativeLiteral = -42
type LongLiteral = 1L
type FloatLiteral = 1.0f
type DoubleLiteral = 1.0
type CharLiteral = 'a'
type StringLiteral = "literal"
type BooleanLiteral = true
type Parenthesized = (T)
