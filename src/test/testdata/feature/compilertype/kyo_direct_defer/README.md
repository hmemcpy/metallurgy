# Kyo Direct effect extraction

Kyo Direct 0.15.1 is built with Scala 3.5.2, matching the fixture module. Its transparent inline `defer` macro rewrites
`await` calls into effectful binds. The compiler must expose each extracted value as `User` and accept its members in
the remainder of the block.
