package dogfood.library.circesemiauto

import io.circe.Codec
import io.circe.generic.semiauto.*

case class Box(value: Int)
object Box:
  given Codec[Box] = deriveCodec
