import zio.direct.*

def compute(a: zio.UIO[Int], b: zio.UIO[Int]): zio.UIO[Int] =
  defer {
    val x = a.run
    val y = b.run
    x + y
  }
