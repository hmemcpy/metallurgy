# Compiler error preservation

This regression fixture verifies that diagnostic filtering is fail-open and only removes bundled semantic errors that
the exact compiler snapshot disproves. A genuine `Int`-to-`String` mismatch remains visible with Metallurgy enabled.
