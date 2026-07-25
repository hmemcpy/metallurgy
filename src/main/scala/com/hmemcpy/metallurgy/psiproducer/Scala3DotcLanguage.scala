package com.hmemcpy.metallurgy.psiproducer

import com.intellij.lang.DependentLanguage
import com.intellij.lang.InjectableLanguage
import com.intellij.lang.Language
import com.intellij.lang.jvm.JvmLanguage
import org.jetbrains.plugins.scala.Scala3Language

/** A Scala 3 dialect Metallurgy owns for active modules. Its base is the bundled [[Scala3Language]], so IntelliJ's
  * dialect-aware extension lookup inherits every "Scala 3" and "Scala" extension (annotators, inspections, daemons,
  * completion, refactoring, inlays, find-usages). The marker interfaces are re-implemented because base-language
  * inheritance does not confer them.
  */
final class Scala3DotcLanguage private ()
    extends Language(Scala3Language.INSTANCE, "Scala 3 (dotc)")
    with JvmLanguage
    with DependentLanguage
    with InjectableLanguage

object Scala3DotcLanguage:
  val INSTANCE: Scala3DotcLanguage = new Scala3DotcLanguage
