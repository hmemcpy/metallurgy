package org.jetbrains.plugins.scala.lang.psi.api.base.types;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Process-lifetime bridge defined into the bundled Scala plugin's classloader before its caller is
 * woven. A {@code null} result is the protocol for continuing into the bundled method body. The
 * callback is enabled only after retransformation succeeds and is removed on a failed installation.
 */
public final class MetallurgyCompilerBackendBridge {
  private static volatile Function<Object, Object> backend;
  private static volatile Function<Object, Object> compilerTypeBackend;
  private static volatile BiFunction<Object, Integer, Object> semanticTypeBackend;
  private static volatile BiFunction<Object, Object, Object> referenceBackend;
  private static volatile boolean enabled;
  private static final Object MISSING_COMPILER_TYPE = new Object();

  private MetallurgyCompilerBackendBridge() {}

  public static void install(Function<Object, Object> candidate) {
    backend = candidate;
  }

  public static void installCompilerTypeBackend(Function<Object, Object> candidate) {
    compilerTypeBackend = candidate;
  }

  public static void installSemanticTypeBackend(BiFunction<Object, Integer, Object> candidate) {
    semanticTypeBackend = candidate;
  }

  public static void installReferenceBackend(BiFunction<Object, Object, Object> candidate) {
    referenceBackend = candidate;
  }

  public static Object missingCompilerType() {
    return MISSING_COMPILER_TYPE;
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
    compilerTypeBackend = null;
    semanticTypeBackend = null;
    referenceBackend = null;
  }

  public static Object declaredType(Object element) {
    Function<Object, Object> current = backend;
    return enabled && current != null ? current.apply(element) : null;
  }

  public static Object compilerType(Object element) {
    Function<Object, Object> current = compilerTypeBackend;
    if (!enabled || current == null) return null;
    Object value = current.apply(element);
    if (value == null) return null;
    if (value == MISSING_COMPILER_TYPE) return scala.None$.MODULE$;
    return scala.Option$.MODULE$.apply(value);
  }

  public static Object semanticType(Object element, int role) {
    BiFunction<Object, Integer, Object> current = semanticTypeBackend;
    return enabled && current != null ? current.apply(element, role) : null;
  }

  public static Object referenceResolution(Object reference, Object bundledResult) {
    BiFunction<Object, Object, Object> current = referenceBackend;
    return enabled && current != null ? current.apply(reference, bundledResult) : bundledResult;
  }
}
