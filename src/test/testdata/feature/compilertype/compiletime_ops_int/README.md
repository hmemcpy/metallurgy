# Compile-time integer operation

Scala 3.5.2 stdlib-only `compiletime.ops.int` fixture. Metallurgy asks the real compiler to reduce `2 + 2` to the
singleton type `4`; the strict-off half leaves the compiler-type slot empty. Related: SCL-21198 and SCL-21528.
