package dogfood.library.circederives

import io.circe.{Codec, Encoder}

case class Person(name: String, age: Int) derives Codec.AsObject

val enc: Encoder[Person] = summon[Encoder[Person]]
