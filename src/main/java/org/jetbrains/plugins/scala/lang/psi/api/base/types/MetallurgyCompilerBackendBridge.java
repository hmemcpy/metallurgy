package org.jetbrains.plugins.scala.lang.psi.api.base.types;

import java.util.function.Function;

/**
 * Process-lifetime bridge defined into the bundled Scala plugin's classloader before its caller is
 * woven. A {@code null} result is the protocol for continuing into the bundled method body. The
 * callback is enabled only after retransformation succeeds and is removed on a failed installation.
 */
public final class MetallurgyCompilerBackendBridge {
  private static volatile Function<Object, Object> backend;
  private static volatile boolean enabled;

  private MetallurgyCompilerBackendBridge() {}

  public static void install(Function<Object, Object> candidate) {
    backend = candidate;
  }

  public static void enable() {
    enabled = true;
  }

  public static void disable() {
    enabled = false;
  }

  public static void uninstall() {
    enabled = false;
    backend = null;
  }

  public static Object declaredType(Object element) {
    Function<Object, Object> current = backend;
    return enabled && current != null ? current.apply(element) : null;
  }
}
