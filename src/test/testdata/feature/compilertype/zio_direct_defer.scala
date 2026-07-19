// Library dependency: dev.zio %% zio-direct % 1.0.0-RC7 (Scala 3)
// Requires IvyManagedLoader("dev.zio", "zio-direct_3", "1.0.0-RC7") in test fixture
import zio.direct.*

def compute(a: zio.UIO[Int], b: zio.UIO[Int]): zio.UIO[Int] =
  defer {
    val x = a.run
    val y = b.run
    x + y
  }
