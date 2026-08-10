import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class MetallurgyBaselineVerifier {
  private static final List<String> REQUIRED_KEYS = List.of(
      "dogfood.sbt.version",
      "ide.probe.version",
      "intellij.build",
      "intellij.product.code",
      "intellij.release",
      "java.bytecode.release",
      "jbr.java.runtime.version",
      "jbr.java.vendor",
      "jbr.java.vendor.version",
      "sbt.version",
      "scala.compiler.version",
      "scala.plugin.id",
      "scala.plugin.version",
      "testkit.scala.version");

  private static final Set<String> SCANNED_ROOTS = Set.of(
      ".agents", ".github", "scripts", "test-lanes", "ideprobe-tests", "dogfood");
  private static final Set<String> SCANNED_FILES = Set.of(
      "AGENTS.md", "build.sbt", "CONTRIBUTING.md", "README.md",
      "docs/scala3-compiler-backend.md", "docs/deterministic-scala3-psi-implementation-program.md",
      "docs/agents/hash-provenance.md", "testkit/build.sbt",
      "testkit/project/build.properties",
      "testkit/src/main/scala/org/jetbrains/plugins/scala/BACKPORT_MANIFEST.txt",
      "testkit/src/main/scala/org/jetbrains/plugins/scala/util/runners/TestScalaVersion.java",
      "project/build.properties");
  private static final Set<String> SCAN_EXCLUSIONS = Set.of(
      "scripts/MetallurgyBaselineVerifier.java",
      "scripts/test-metallurgy-baseline-verifier.sh",
      "project/metallurgy-baseline.properties",
      "dogfood/target", "ideprobe-tests/target", "testkit/target");

  private record Finding(String coordinate, String expected, String actual, String file, String location) {}

  private static final class Report {
    final List<Finding> missing = new ArrayList<>();
    final List<Finding> extra = new ArrayList<>();
    final List<Finding> mismatched = new ArrayList<>();

    boolean failed() {
      return !missing.isEmpty() || !extra.isEmpty() || !mismatched.isEmpty();
    }

    void printAndExit() {
      printSection("Missing coordinates", missing);
      printSection("Extra coordinates", extra);
      printSection("Mismatched consumers", mismatched);
      System.exit(1);
    }

    private void printSection(String title, List<Finding> findings) {
      System.err.println(title);
      if (findings.isEmpty()) {
        System.err.println("  none");
      } else {
        for (Finding finding : findings) {
          System.err.printf(
              "  coordinate=%s expected=%s actual=%s file=%s location=%s%n",
              display(finding.coordinate), display(finding.expected), display(finding.actual),
              display(finding.file), display(finding.location));
        }
      }
    }

    private String display(String value) {
      return value == null || value.isEmpty() ? "<empty>" : value.replace("\n", "\\n");
    }
  }

  private record Baseline(Path root, Path file, Map<String, String> values) {}

  public static void main(String[] args) throws Exception {
    if (args.length == 0) usage();
    Path root = findRepositoryRoot();
    Report report = new Report();
    Baseline baseline = loadBaseline(root, report);
    if (report.failed()) report.printAndExit();

    switch (args[0]) {
      case "static" -> {
        if (args.length != 1) usage();
        verifyStatic(baseline, report);
        finish(report, "Metallurgy baseline static verification passed");
      }
      case "host" -> {
        if (args.length != 2) usage();
        verifyStatic(baseline, report);
        if (!report.failed()) verifyHost(baseline, Paths.get(args[1]), report);
        finish(report, "Metallurgy baseline host verification passed");
      }
      case "value" -> {
        if (args.length != 2) usage();
        String value = baseline.values().get(args[1]);
        if (value == null) {
          report.extra.add(new Finding(args[1], "known coordinate", "unknown coordinate",
              baseline.file().toString(), "value argument"));
          report.printAndExit();
        }
        System.out.println(value);
      }
      default -> usage();
    }
  }

  private static void usage() {
    System.err.println("Usage: MetallurgyBaselineVerifier static | host <intellij-home> | value <name>");
    System.exit(2);
  }

  private static void finish(Report report, String success) {
    if (report.failed()) report.printAndExit();
    System.out.println(success);
  }

  private static Path findRepositoryRoot() throws IOException {
    String configured = System.getProperty("metallurgy.repo.root");
    if (configured == null || configured.isEmpty()) configured = System.getenv("METALLURGY_REPO_ROOT");
    if (configured != null && !configured.isEmpty()) return Paths.get(configured).toAbsolutePath().normalize();

    String command = System.getProperty("sun.java.command", "");
    if (!command.isEmpty()) {
      for (String argument : command.split(" ")) {
        if (argument.endsWith("MetallurgyBaselineVerifier.java")) {
          Path source = Paths.get(argument).toAbsolutePath().normalize();
          Path scripts = source.getParent();
          if (scripts != null && scripts.getFileName().toString().equals("scripts")) return scripts.getParent();
        }
      }
    }

    Path current = Paths.get("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("project/metallurgy-baseline.properties"))) return current;
      current = current.getParent();
    }
    throw new IOException("Cannot locate project/metallurgy-baseline.properties");
  }

  private static Baseline loadBaseline(Path root, Report report) throws IOException {
    Path file = root.resolve("project/metallurgy-baseline.properties");
    if (!Files.isRegularFile(file)) {
      for (String key : REQUIRED_KEYS) {
        report.missing.add(new Finding(key, "non-empty value", "missing manifest", relative(root, file), "manifest"));
      }
      return new Baseline(root, file, Map.of());
    }

    byte[] bytes = Files.readAllBytes(file);
    for (int index = 0; index < bytes.length; index++) {
      int value = bytes[index] & 0xff;
      if (value == '\n') continue;
      if (value < 0x20 || value > 0x7e) {
        report.mismatched.add(new Finding("manifest", "ASCII rows with LF endings",
            String.format("byte 0x%02x", value), relative(root, file), "byte " + index));
        return new Baseline(root, file, Map.of());
      }
    }
    if (bytes.length == 0 || bytes[bytes.length - 1] != '\n') {
      report.mismatched.add(new Finding("manifest", "final LF", "missing final LF", relative(root, file), "file ending"));
    }

    String text = new String(bytes, StandardCharsets.US_ASCII);
    String[] rows = text.split("\n", -1);
    Map<String, String> values = new LinkedHashMap<>();
    String previous = null;
    for (int index = 0; index < rows.length - 1; index++) {
      String row = rows[index];
      int line = index + 1;
      if (row.isEmpty()) {
        report.mismatched.add(new Finding("manifest", "name=value", "empty row", relative(root, file), "line " + line));
        continue;
      }
      if (row.indexOf('\\') >= 0) {
        report.mismatched.add(new Finding("manifest", "literal unescaped row", row, relative(root, file), "line " + line));
        continue;
      }
      int separator = row.indexOf('=');
      if (separator <= 0 || separator != row.lastIndexOf('=')) {
        report.mismatched.add(new Finding("manifest", "one name=value separator", row, relative(root, file), "line " + line));
        continue;
      }
      String key = row.substring(0, separator);
      String value = row.substring(separator + 1);
      if (!key.matches("[a-z][a-z0-9.]*")) {
        report.mismatched.add(new Finding(key, "unpadded lowercase coordinate", key, relative(root, file), "line " + line));
        continue;
      }
      if (value.isEmpty() || !value.equals(value.strip())) {
        report.mismatched.add(new Finding(key, "non-empty unpadded value", value, relative(root, file), "line " + line));
      }
      if (previous != null && previous.compareTo(key) >= 0) {
        report.mismatched.add(new Finding(key, "strict bytewise key order after " + previous, key,
            relative(root, file), "line " + line));
      }
      previous = key;
      if (values.putIfAbsent(key, value) != null) {
        report.extra.add(new Finding(key, "one row", "duplicate row", relative(root, file), "line " + line));
      }
    }

    for (String key : REQUIRED_KEYS) {
      if (!values.containsKey(key)) {
        report.missing.add(new Finding(key, "required coordinate", "missing", relative(root, file), "manifest schema"));
      }
    }
    for (String key : values.keySet()) {
      if (!REQUIRED_KEYS.contains(key)) {
        report.extra.add(new Finding(key, "not present", values.get(key), relative(root, file), "manifest schema"));
      }
    }
    String bytecodeRelease = values.get("java.bytecode.release");
    if (bytecodeRelease != null) {
      try {
        if (Integer.parseInt(bytecodeRelease) <= 0) throw new NumberFormatException();
      } catch (NumberFormatException ignored) {
        report.mismatched.add(new Finding("java.bytecode.release", "positive integer", bytecodeRelease,
            relative(root, file), "manifest value"));
      }
    }
    return new Baseline(root, file, Collections.unmodifiableMap(values));
  }

  private static void verifyStatic(Baseline baseline, Report report) throws IOException {
    Path root = baseline.root();
    Map<String, String> values = baseline.values();

    checkBaselineReference(root, report, "build.sbt", "scala.compiler.version",
        "ThisBuild\\s*/\\s*scalaVersion\\s*:=\\s*metallurgyBaseline\\.value\\(\"([^\"]+)\"\\)", "ThisBuild / scalaVersion");
    checkBaselineReference(root, report, "build.sbt", "intellij.build",
        "ThisBuild\\s*/\\s*intellijBuild\\s*:=\\s*metallurgyBaseline\\.value\\(\"([^\"]+)\"\\)", "ThisBuild / intellijBuild");
    checkBaselineReference(root, report, "build.sbt", "scala.plugin.version",
        "ThisBuild\\s*/\\s*scalaPluginVersion\\s*:=\\s*metallurgyBaseline\\.value\\(\"([^\"]+)\"\\)", "scalaPluginVersion");
    checkBaselineReference(root, report, "build.sbt", "testkit.scala.version",
        "ThisBuild\\s*/\\s*scala2LibraryVersion\\s*:=\\s*metallurgyBaseline\\.value\\(\"([^\"]+)\"\\)", "scala2LibraryVersion");
    checkBaselineReference(root, report, "build.sbt", "intellij.build",
        "ThisBuild\\s*/\\s*intellijTestFrameworkVersion\\s*:=\\s*metallurgyBaseline\\.value\\(\"([^\"]+)\"\\)", "intellijTestFrameworkVersion");
    checkBaselineReference(root, report, "build.sbt", "java.bytecode.release",
        "javacOptions\\s*:=\\s*Seq\\(\"--release\",\\s*metallurgyBaseline\\.value\\(\"([^\"]+)\"\\)\\)", "Global / javacOptions");

    checkBaselineReference(root, report, "testkit/build.sbt", "testkit.scala.version",
        "ThisBuild\\s*/\\s*scalaVersion\\s*:=\\s*metallurgyBaseline\\.value\\.getOrElse\\(\"([^\"]+)\"", "ThisBuild / scalaVersion");
    checkBaselineReference(root, report, "testkit/build.sbt", "intellij.build",
        "ThisBuild\\s*/\\s*intellijBuild\\s*:=\\s*metallurgyBaseline\\.value\\.getOrElse\\(\"([^\"]+)\"", "intellijBuild");
    checkBaselineReference(root, report, "ideprobe-tests/build.sbt", "testkit.scala.version",
        "ThisBuild\\s*/\\s*scalaVersion\\s*:=\\s*metallurgyBaseline\\.value\\.getOrElse\\(\"([^\"]+)\"", "ThisBuild / scalaVersion");
    checkBaselineReference(root, report, "ideprobe-tests/build.sbt", "ide.probe.version",
        "ThisBuild\\s*/\\s*ideProbeVersion\\s*:=\\s*metallurgyBaseline\\.value\\.getOrElse\\(\"([^\"]+)\"", "ideProbeVersion");
    checkBaselineReference(root, report, "dogfood/build.sbt", "scala.compiler.version",
        "ThisBuild\\s*/\\s*scalaVersion\\s*:=\\s*metallurgyBaseline\\.value\\.getOrElse\\(\"([^\"]+)\"", "ThisBuild / scalaVersion");

    checkProperty(root, report, values, "project/build.properties", "sbt.version", "sbt launcher");
    checkProperty(root, report, values, "testkit/project/build.properties", "sbt.version", "sbt launcher");
    checkProperty(root, report, values, "ideprobe-tests/project/build.properties", "sbt.version", "sbt launcher");
    checkProperty(root, report, values, "dogfood/project/build.properties", "dogfood.sbt.version", "sbt launcher");
    checkQuotedAssignment(root, report, values, "ideprobe-tests/src/test/resources/ideprobe.conf",
        "intellij.build", "build", "probe.intellij.version.build");
    checkQuotedAssignment(root, report, values, "ideprobe-tests/src/test/resources/ideprobe.conf",
        "intellij.release", "release", "probe.intellij.version.release");

    checkLiteral(root, report, values, "AGENTS.md", "intellij.build", "baseline host guidance");
    checkLiteral(root, report, values, "AGENTS.md", "scala.plugin.version", "baseline host guidance");
    checkLiteral(root, report, values, "AGENTS.md", "scala.compiler.version", "build guidance");
    checkLiteral(root, report, values, "AGENTS.md", "testkit.scala.version", "build guidance");
    checkLiteral(root, report, values, "AGENTS.md", "sbt.version", "build guidance");
    checkLiteral(root, report, values,
        "ideprobe-tests/src/test/scala/com/hmemcpy/metallurgy/ideprobe/ProjectLifecycleTest.scala",
        "scala.compiler.version", "capability identity assertion");
    checkLiteral(root, report, values,
        "ideprobe-tests/src/test/scala/com/hmemcpy/metallurgy/ideprobe/ProjectLifecycleTest.scala",
        "intellij.build", "capability identity assertion");
    checkLiteral(root, report, values,
        "ideprobe-tests/src/test/scala/com/hmemcpy/metallurgy/ideprobe/ProjectLifecycleTest.scala",
        "scala.plugin.version", "capability identity assertion");
    checkLiteral(root, report, values, "docs/scala3-compiler-backend.md", "intellij.build", "canonical baseline prose");
    checkLiteral(root, report, values, "docs/scala3-compiler-backend.md", "scala.plugin.version", "canonical baseline prose");
    checkLiteral(root, report, values, "docs/deterministic-scala3-psi-implementation-program.md",
        "intellij.build", "canonical baseline command");
    checkLiteral(root, report, values,
        "testkit/src/main/scala/org/jetbrains/plugins/scala/BACKPORT_MANIFEST.txt",
        "scala.plugin.version", "backport note");
    checkLiteral(root, report, values,
        "testkit/src/main/scala/org/jetbrains/plugins/scala/BACKPORT_MANIFEST.txt",
        "intellij.build", "backport note");
    checkLiteral(root, report, values,
        "testkit/src/main/scala/org/jetbrains/plugins/scala/util/runners/TestScalaVersion.java",
        "scala.plugin.version", "backport note");

    checkOperationalScripts(root, values, report);
    scanForUncatalogedValues(root, values, report);
  }

  private static void checkOperationalScripts(Path root, Map<String, String> values, Report report) throws IOException {
    checkContains(root, report, ".agents/setup", "value intellij.build", "SDK path derivation");
    checkContains(root, report, ".agents/setup", "value sbt.version", "sbt bootstrap derivation");
    checkContains(root, report, ".agents/setup", " static", "bootstrap verification");
    checkContains(root, report, ".agents/setup", " host ", "host verification");
    checkContains(root, report, ".agents/resume", "value intellij.build", "SDK path derivation");
    checkContains(root, report, ".agents/resume", " host ", "host verification");
    checkContains(root, report, ".github/workflows/ci.yml", "MetallurgyBaselineVerifier.java static", "bootstrap verification");
    checkContains(root, report, ".github/workflows/ci.yml", "MetallurgyBaselineVerifier.java host", "host verification");
    checkContains(root, report, "scripts/run-test-lane.sh", "MetallurgyBaselineVerifier.java", "host verification");
  }

  private static void checkBaselineReference(Path root, Report report, String file, String coordinate,
      String expression, String location) throws IOException {
    String text = read(root, file, report, coordinate, location);
    if (text == null) return;
    Matcher matcher = Pattern.compile(expression).matcher(text);
    if (!matcher.find()) {
      report.mismatched.add(new Finding(coordinate, "manifest-backed setting", "missing or non-manifest setting", file, location));
    } else if (!coordinate.equals(matcher.group(1))) {
      report.mismatched.add(new Finding(coordinate, coordinate, matcher.group(1), file, location));
    }
  }

  private static void checkProperty(Path root, Report report, Map<String, String> values, String file,
      String coordinate, String location) throws IOException {
    String text = read(root, file, report, coordinate, location);
    if (text == null) return;
    String expected = "sbt.version=" + values.get(coordinate) + "\n";
    if (!text.equals(expected)) report.mismatched.add(new Finding(coordinate, values.get(coordinate), text.strip(), file, location));
  }

  private static void checkQuotedAssignment(Path root, Report report, Map<String, String> values, String file,
      String coordinate, String name, String location) throws IOException {
    String text = read(root, file, report, coordinate, location);
    if (text == null) return;
    Matcher matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(name) + "\\s*=\\s*\"([^\"]*)\"\\s*$").matcher(text);
    String actual = matcher.find() ? matcher.group(1) : "missing";
    if (!values.get(coordinate).equals(actual)) {
      report.mismatched.add(new Finding(coordinate, values.get(coordinate), actual, file, location));
    }
  }

  private static void checkLiteral(Path root, Report report, Map<String, String> values, String file,
      String coordinate, String location) throws IOException {
    String text = read(root, file, report, coordinate, location);
    if (text != null && !text.contains(values.get(coordinate))) {
      report.mismatched.add(new Finding(coordinate, values.get(coordinate), "literal absent", file, location));
    }
  }

  private static void checkContains(Path root, Report report, String file, String expected, String location)
      throws IOException {
    String text = read(root, file, report, "operational.flow", location);
    if (text != null && !text.contains(expected)) {
      report.mismatched.add(new Finding("operational.flow", expected, "absent", file, location));
    }
  }

  private static String read(Path root, String file, Report report, String coordinate, String location) throws IOException {
    Path path = root.resolve(file);
    if (!Files.isRegularFile(path)) {
      report.missing.add(new Finding(coordinate, "maintained consumer", "missing", file, location));
      return null;
    }
    return Files.readString(path, StandardCharsets.UTF_8);
  }

  private static void scanForUncatalogedValues(Path root, Map<String, String> values, Report report) throws IOException {
    Set<String> distinctive = Set.of(
        "ide.probe.version", "intellij.build", "intellij.release", "jbr.java.runtime.version",
        "jbr.java.vendor.version", "scala.plugin.version", "testkit.scala.version",
        "sbt.version", "dogfood.sbt.version");
    Map<String, Set<String>> allowed = allowedValueFiles();
    try (var paths = Files.walk(root)) {
      paths.filter(Files::isRegularFile).forEach(path -> {
        String file = relative(root, path);
        if (!isScanned(file) || isExcluded(file)) return;
        String text;
        try {
          text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ignored) {
          return;
        }
        for (String coordinate : distinctive) {
          String value = values.get(coordinate);
          if (value != null && text.contains(value) && !allowed.getOrDefault(coordinate, Set.of()).contains(file)) {
            report.extra.add(new Finding(coordinate, "manifest or reviewed consumer", value, file, "uncataloged current value"));
          }
        }
      });
    }
  }

  private static Map<String, Set<String>> allowedValueFiles() {
    Map<String, Set<String>> allowed = new LinkedHashMap<>();
    allowed.put("ide.probe.version", Set.of());
    allowed.put("intellij.release", Set.of("ideprobe-tests/src/test/resources/ideprobe.conf"));
    allowed.put("jbr.java.runtime.version", Set.of());
    allowed.put("jbr.java.vendor.version", Set.of());
    allowed.put("dogfood.sbt.version", Set.of("dogfood/project/build.properties"));
    allowed.put("sbt.version", Set.of("AGENTS.md", "project/build.properties", "testkit/project/build.properties",
        "ideprobe-tests/project/build.properties"));
    allowed.put("testkit.scala.version", Set.of("AGENTS.md"));
    allowed.put("intellij.build", Set.of("AGENTS.md", "ideprobe-tests/src/test/resources/ideprobe.conf",
        "ideprobe-tests/src/test/scala/com/hmemcpy/metallurgy/ideprobe/ProjectLifecycleTest.scala",
        "docs/scala3-compiler-backend.md", "docs/deterministic-scala3-psi-implementation-program.md",
        "testkit/src/main/scala/org/jetbrains/plugins/scala/BACKPORT_MANIFEST.txt"));
    allowed.put("scala.plugin.version", Set.of("AGENTS.md",
        "ideprobe-tests/src/test/scala/com/hmemcpy/metallurgy/ideprobe/ProjectLifecycleTest.scala",
        "docs/scala3-compiler-backend.md",
        "testkit/src/main/scala/org/jetbrains/plugins/scala/BACKPORT_MANIFEST.txt",
        "testkit/src/main/scala/org/jetbrains/plugins/scala/util/runners/TestScalaVersion.java"));
    return allowed;
  }

  private static boolean isScanned(String file) {
    if (SCANNED_FILES.contains(file)) return true;
    int slash = file.indexOf('/');
    return slash > 0 && SCANNED_ROOTS.contains(file.substring(0, slash));
  }

  private static boolean isExcluded(String file) {
    if (file.startsWith("target/") || file.contains("/target/")) return true;
    for (String exclusion : SCAN_EXCLUSIONS) {
      if (file.equals(exclusion) || file.startsWith(exclusion + "/")) return true;
    }
    return file.startsWith("dogfood/src/main/scala/") && !file.endsWith("/README.md")
        || file.startsWith("ideprobe-tests/probe-261/")
        || file.startsWith("ideprobe-tests/project/") && !file.endsWith("build.properties");
  }

  private static void verifyHost(Baseline baseline, Path requestedHome, Report report) throws Exception {
    Path home = requestedHome.toAbsolutePath().normalize();
    Map<String, String> values = baseline.values();
    checkJsonField(home.resolve("product-info.json"), "productCode", "intellij.product.code", values, report, home);
    checkJsonField(home.resolve("product-info.json"), "buildNumber", "intellij.build", values, report, home);
    checkJsonField(home.resolve("product-info.json"), "version", "intellij.release", values, report, home);

    Path plugin = Files.isDirectory(home.resolve("custom-plugins/Scala"))
        ? home.resolve("custom-plugins/Scala") : home.resolve("plugins/Scala");
    Path descriptorJar = plugin.resolve("lib/pluginXml.jar");
    if (!Files.isRegularFile(descriptorJar)) {
      report.missing.add(new Finding("scala.plugin.id", values.get("scala.plugin.id"), "missing",
          descriptorJar.toString(), "META-INF/plugin.xml"));
    } else {
      try (ZipFile zip = new ZipFile(descriptorJar.toFile())) {
        ZipEntry entry = zip.getEntry("META-INF/plugin.xml");
        if (entry == null) {
          report.missing.add(new Finding("scala.plugin.id", values.get("scala.plugin.id"), "missing",
              descriptorJar.toString(), "META-INF/plugin.xml"));
        } else {
          String xml;
          try (InputStream input = zip.getInputStream(entry)) {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
          }
          checkXmlElement(xml, "id", "scala.plugin.id", values, report, descriptorJar);
          checkXmlElement(xml, "version", "scala.plugin.version", values, report, descriptorJar);
        }
      }
    }

    Path jbr = Files.isDirectory(home.resolve("jbr/Contents/Home"))
        ? home.resolve("jbr/Contents/Home") : home.resolve("jbr");
    checkRelease(jbr.resolve("release"), values, report);
    checkRuntime(jbr, values, report);
  }

  private static void checkJsonField(Path file, String field, String coordinate, Map<String, String> values,
      Report report, Path home) throws IOException {
    if (!Files.isRegularFile(file)) {
      report.missing.add(new Finding(coordinate, values.get(coordinate), "missing", file.toString(), field));
      return;
    }
    String json = Files.readString(file, StandardCharsets.UTF_8);
    Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
    String actual = matcher.find() ? matcher.group(1) : "missing";
    if (!values.get(coordinate).equals(actual)) {
      report.mismatched.add(new Finding(coordinate, values.get(coordinate), actual, file.toString(), field));
    }
  }

  private static void checkXmlElement(String xml, String element, String coordinate, Map<String, String> values,
      Report report, Path file) {
    Matcher matcher = Pattern.compile("<" + element + ">\\s*([^<]+?)\\s*</" + element + ">").matcher(xml);
    String actual = matcher.find() ? matcher.group(1) : "missing";
    if (!values.get(coordinate).equals(actual)) {
      report.mismatched.add(new Finding(coordinate, values.get(coordinate), actual, file.toString(), element));
    }
  }

  private static void checkRelease(Path release, Map<String, String> values, Report report) throws IOException {
    if (!Files.isRegularFile(release)) {
      report.missing.add(new Finding("jbr.java.runtime.version", values.get("jbr.java.runtime.version"),
          "missing", release.toString(), "embedded JBR release"));
      return;
    }
    Map<String, String> fields = new LinkedHashMap<>();
    for (String row : Files.readAllLines(release, StandardCharsets.UTF_8)) {
      int separator = row.indexOf('=');
      if (separator > 0) fields.put(row.substring(0, separator), unquote(row.substring(separator + 1)));
    }
    checkValue("jbr.java.runtime.version", values.get("jbr.java.runtime.version"), fields.get("JAVA_RUNTIME_VERSION"),
        release.toString(), "JAVA_RUNTIME_VERSION", report);
    checkValue("jbr.java.vendor", values.get("jbr.java.vendor"), fields.get("IMPLEMENTOR"),
        release.toString(), "IMPLEMENTOR", report);
    checkValue("jbr.java.vendor.version", values.get("jbr.java.vendor.version"), fields.get("IMPLEMENTOR_VERSION"),
        release.toString(), "IMPLEMENTOR_VERSION", report);
  }

  private static void checkRuntime(Path jbr, Map<String, String> values, Report report) throws Exception {
    Path java = jbr.resolve("bin/java");
    if (!Files.isExecutable(java)) {
      report.missing.add(new Finding("jbr.java.runtime.version", values.get("jbr.java.runtime.version"),
          "missing executable", java.toString(), "selected JBR java"));
      return;
    }
    Process process = new ProcessBuilder(java.toString(), "-XshowSettings:properties", "-version")
        .redirectErrorStream(true).start();
    String output;
    try (InputStream input = process.getInputStream()) {
      output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    int exit = process.waitFor();
    if (exit != 0) {
      report.mismatched.add(new Finding("jbr.java.runtime.version", values.get("jbr.java.runtime.version"),
          "java exit " + exit, java.toString(), "live runtime"));
      return;
    }
    checkValue("jbr.java.runtime.version", values.get("jbr.java.runtime.version"), property(output, "java.runtime.version"),
        java.toString(), "java.runtime.version", report);
    checkValue("jbr.java.vendor", values.get("jbr.java.vendor"), property(output, "java.vendor"),
        java.toString(), "java.vendor", report);
    checkValue("jbr.java.vendor.version", values.get("jbr.java.vendor.version"), property(output, "java.vendor.version"),
        java.toString(), "java.vendor.version", report);

    Path running = Paths.get(System.getProperty("java.home")).toRealPath();
    Path selected = jbr.toRealPath();
    if (!running.equals(selected)) {
      report.mismatched.add(new Finding("jbr.java.runtime.version", selected.toString(), running.toString(),
          java.toString(), "verifier runtime java.home"));
    }
  }

  private static String property(String output, String name) {
    Matcher matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(name) + "\\s*=\\s*(.*?)\\s*$").matcher(output);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static void checkValue(String coordinate, String expected, String actual, String file,
      String location, Report report) {
    if (!expected.equals(actual)) report.mismatched.add(new Finding(coordinate, expected, actual, file, location));
  }

  private static String unquote(String value) {
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  private static String relative(Path root, Path file) {
    try {
      return root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    } catch (IllegalArgumentException ignored) {
      return file.toString();
    }
  }
}
