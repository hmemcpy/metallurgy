import scala.language.experimental.modularity

trait High
trait Low extends High
trait Evidence[A]
trait BinaryEvidence[A, B]
trait KindEvidence[F[_]]
trait Coll[F[_]]
trait Higher[F[_], G[X >: Low <: High], H[_[_]]]
trait Variance[+A >: Low <: High, -B]

type Upper[A <: High] = A
type Lower[A >: Low] = A
type Both[A >: Low <: High] = A
type WildUpper[T] = List[? <: T]
type WildBoth = List[? >: Low <: High]
type TypeLambda = [X >: Low <: High] =>> List[X]
type HigherLambda = [F[_]] =>> F[High]
opaque type AliasBounds >: Low <: High = High

def unnamed[A: Evidence](value: A): A = value
def named[A: Evidence as evidence](value: A): Evidence[A] = evidence
def aggregate[A: {Evidence, [X] =>> BinaryEvidence[X, X]}](value: A): A = value
def higher[F[_]: KindEvidence](value: F[High]): F[High] = value
