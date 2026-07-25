# Match-type reduction

Scala 3.5.2 stdlib-only match-type fixture. Metallurgy asks the real compiler to reduce `Elem[List[Int]]` to `Int`;
the strict-off half leaves the compiler-type slot empty. Related: SCL-21198, SCL-22088, and SCL-21528.
