package owneratoms

trait Base
trait Other

type TopAlias = Base
def topResult: Base = new Base {}
val topValue: Base = topResult
var topVariable: Base = topValue

trait Members extends Base:
  self: Other =>

  type Alias = Base
  def declared: Base
  def result(value: Base): Base = value
  val value: Base
  var variable: Base

class Parameters(value: Base)(using context: Other) extends Base
class ParenthesizedParent extends (Base)
enum Derived derives CanEqual:
  case Only
