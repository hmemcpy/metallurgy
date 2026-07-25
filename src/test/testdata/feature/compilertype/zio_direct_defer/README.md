# ZIO Direct effect extraction

This fixture checks the types produced for `run` calls inside a ZIO Direct `defer` block. The presentation compiler
must expose each extracted value as `Int` and accept their use in the enclosing effect.
