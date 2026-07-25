package dogfood.showcase

object Config:
  transparent inline def port: Int = 8080

val transparentPort: 8080 = Config.port
// hover `transparentPort` — with the plugin: (8080 : Int)
