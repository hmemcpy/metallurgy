package dogfood.showcase

object Ports:
  opaque type Port = Int
  def apply(n: Int): Port = n

val opaquePort: Ports.Port = Ports(8080)
// hover `opaquePort` — with the plugin: Ports.Port
