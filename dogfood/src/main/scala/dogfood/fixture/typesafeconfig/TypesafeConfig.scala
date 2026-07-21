package dogfood.fixture.typesafeconfig

object Config:
  transparent inline def port: Int = 8080
  transparent inline def host: String = "0.0.0.0"
  transparent inline def enabled: Boolean = true

val p: 8080 = Config.port
val h: "0.0.0.0" = Config.host
val e: true = Config.enabled
