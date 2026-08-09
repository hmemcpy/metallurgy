class Capability extends caps.Capability
class Box[A]
class Kind extends caps.Capability, caps.Classifier
trait Holder:
  val cap: Capability

type Universal = Capability^
type Mixed = (Capability @unchecked)^
type Pure = Capability -> String
type ContextPure = Capability ?-> String
type NullaryPure = () -> String

def explicit(x: Capability): Box[String]^{x} = ???
def empty: Box[String]^{} = ???
def reached(xs: List[Capability]): Box[String]^{xs*} = ???
def readOnly(x: Capability): Box[String]^{x.rd} = ???
def filtered(x: Capability): Box[String]^{x.only[Kind]} = ???
def qualified(h: Holder): Box[String]^{h.cap} = ???
def pure(x: Capability): () ->{x} String = ???
def context(x: Capability): Capability ?->{x} String = ???
def pureByName(value: -> String): String = value
def byName(x: Capability)(value: ->{x} String): String = value
class Consumer(x: Capability, value: -> String, captured: ->{x} String)
