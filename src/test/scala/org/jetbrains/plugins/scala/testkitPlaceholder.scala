// Placeholder. The full Scala plugin testkit (LightCodeInsightFixtureTestCase,
// ScalaLightProjectDescriptor, library loaders, etc.) is backported from
// JetBrains/intellij-scala's idea261.x branch — see ADR 0005.
//
// Phase 0 task: backport the ~60 files needed for Phase 1 tests, mirroring
// the structure of https://github.com/JetBrains/intellij-scala/tree/idea261.x/scala/test-scala
//
// Files expected here under src/test/scala/org/jetbrains/plugins/scala/:
//   base/ScalaLightProjectDescriptor.scala
//   base/ScalaLightCodeInsightFixtureTestCase.scala
//   base/LibrariesOwner.scala
//   base/ScalaSdkOwner.scala
//   base/libraryLoaders/* (LibraryLoader, ScalaSDKLoader, IvyManagedLoader,
//                          ThirdPartyLibraryLoader, MockScalaSDKLoader, SmartJDKLoader,
//                          ScalaReflectLibraryLoader, SourcesLoader)
//   util/Markers.scala
//   util/TestUtils.scala
//   util/CompilerTestUtil.scala
//   util/assertions/*
//   codeInspection/ScalaInspectionTestBase.scala
//   ... (see zio-intellij's src/test/scala/org/jetbrains/plugins/scala/* for reference)
package org.jetbrains.plugins.scala
