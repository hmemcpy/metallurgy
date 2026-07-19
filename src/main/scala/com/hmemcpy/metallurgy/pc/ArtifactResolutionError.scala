package com.hmemcpy.metallurgy.pc

private[pc] enum ArtifactResolutionError:
  case DependencyResolution(scalaVersion: String, cause: Throwable)
  case CacheWrite(path: String, cause: Throwable)
  case DuplicateArtifact(fileName: String)

  def toException: RuntimeException = this match
    case DependencyResolution(version, cause) =>
      new RuntimeException(s"Could not resolve the Scala $version presentation compiler", cause)
    case CacheWrite(path, cause)              =>
      new RuntimeException(s"Could not populate presentation compiler cache at $path", cause)
    case DuplicateArtifact(fileName)          =>
      new RuntimeException(s"Presentation compiler dependency graph contains duplicate file name: $fileName")
