# Recursive ADT derivation

This Scala 3.5.2 standard-library fixture checks the `Mirror.Sum` synthesized for a recursive enum and the precise
`MirroredElemTypes` refinement it exposes. Metallurgy must report `(Lst.Cns[Int], (Lst.Nl : Lst[Nothing]))` and keep the
dependent tuple ascription valid. Related bundled-plugin gaps: SCL-22004, SCL-21785, and SCL-21294.
