class Config(map: Map[String, Any]) extends Selectable:
  def selectDynamic(name: String): Any = map(name)

transparent inline def typesafeConfig(inline pairs: (String, Any)*) =
  Config(Map.from(pairs)).asInstanceOf[Config { val name: String; val age: Int }]

val c = typesafeConfig("name" -> "John", "age" -> 42)
val selectedName: String = c.name
