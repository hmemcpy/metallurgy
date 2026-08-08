import scala.language.experimental.namedTypeArguments

trait F[A]

type Rejected = F[A = Int]
