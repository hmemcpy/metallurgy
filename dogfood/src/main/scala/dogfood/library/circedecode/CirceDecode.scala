package dogfood.library.circedecode

import io.circe.{Decoder, Json}
import io.circe.generic.semiauto.*

case class Event(id: Int, name: String)
object Event:
  given Decoder[Event] = deriveDecoder

val parsed: Option[Event] = summon[Decoder[Event]].decodeJson(Json.fromInt(1)).toOption
