# Evidence retention

Git retains the smallest deterministic record needed to understand and reproduce a result. A normal evidence packet
may include source and REPL inputs, exact commands, compiler versions and flags, concise Markdown or JSON results, and
content hashes. Raw sbt, parser, IDE, JUnit, process, JVM, and generated output remains in a verified external archive
and is ignored by Git.

A normal summary is at most 32 KiB. Tracking any evidence file larger than 64 KiB requires explicit approval. A packet
larger than 128 KiB belongs in an external archive.

Each summary records:

- the result and exact tool or compiler version
- the protected command file or exact commands and flags
- the archive URL or path
- the archive manifest SHA-256 and inner manifest name
- the verified entry count and archive size
- the retention requirement

External archives remain available through the maintenance and rollback window and until an independently verified
replacement archive is recorded. Hash verification is required before relying on an archive or removing a tracked raw
record.
