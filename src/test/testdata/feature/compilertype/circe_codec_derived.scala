// Library dependency: io.circe %% circe-generic % 0.14.10 (Scala 3)
// Requires IvyManagedLoader("io.circe", "circe-generic_3", "0.14.10") in test fixture
import io.circe.Codec
import io.circe.generic.semiauto._

case class Person(name: String, age: Int) derives Codec.AsObject

val person = Person("Alice", 30)
val json = person.asJson
val decoded = json.as[Person]
