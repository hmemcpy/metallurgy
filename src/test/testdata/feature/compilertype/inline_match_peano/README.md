# Inline-match Peano reduction

Scala 3.5.2 stdlib-only inline-match fixture from the Scala 3 reference. Metallurgy must expose the reduced singleton
type `2` for `toInt(Succ(Succ(Zero)))`; without Metallurgy the slot is empty and the singleton ascription is red.
Related: SCL-21789.
